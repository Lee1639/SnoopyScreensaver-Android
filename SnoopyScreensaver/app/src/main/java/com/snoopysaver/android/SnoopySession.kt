package com.snoopysaver.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

class SnoopySession(
    private val context: Context,
    root: View,
    private val allowSetup: Boolean,
    private val requestTree: () -> Unit,
    private val requestAccess: () -> Unit,
) : Host {
    private val main = Handler(Looper.getMainLooper())
    private val back = root.findViewById<StageView>(R.id.back_stage)
    private val front = root.findViewById<StageView>(R.id.front_stage)
    private val activeView = root.findViewById<TextureView>(R.id.active_video)
    private val backgroundView = root.findViewById<TextureView>(R.id.background_video)
    private val fade = root.findViewById<View>(R.id.fade)
    private val hint = root.findViewById<TextView>(R.id.hint)
    private val gate = root.findViewById<ScrollView>(R.id.gate)
    private val gateBody = root.findViewById<TextView>(R.id.gate_body)
    private val heicPlayer = HeicPlayer(main)
    private val maskPlayer = MaskPlayer(main)
    private val alphaPlayer = AlphaClipPlayer(main)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var activePlayer: ExoPlayer? = null
    private var backgroundPlayer: ExoPlayer? = null
    private var acceptEnd = false
    private var ended: (() -> Unit)? = null
    private var ready: (() -> Unit)? = null
    private var readySent = false
    private var activeSerial = 0
    private var activeArmed = -1
    private var backgroundSerial = 0
    private var backgroundArmed = -1
    private var director: Director? = null
    private var booting = false
    private var faded = false
    private var destroyed = false
    private var promptedAccess = false
    private var backgroundRect = RectF()
    private var backgroundCallback: (() -> Unit)? = null

    init {
        activeView.setOpaque(false)
        backgroundView.setOpaque(false)
        back.role = StageView.ROLE_BACK
        front.role = StageView.ROLE_FRONT
        val tone = halftone()
        back.halftone = tone
        front.halftone = tone
        if (!allowSetup) hint.visibility = View.GONE
        root.findViewById<View>(R.id.gate_pick).setOnClickListener { requestTree() }
        root.findViewById<View>(R.id.gate_grant).setOnClickListener { requestAccess() }
        if (!allowSetup) {
            root.findViewById<View>(R.id.gate_pick).visibility = View.GONE
            root.findViewById<View>(R.id.gate_grant).visibility = View.GONE
        }
        front.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            if (right > left && bottom > top) {
                place(activeView, Placement.active(RectF(0f, 0f, front.width.toFloat(), front.height.toFloat())))
                if (backgroundView.visibility == View.VISIBLE) place(backgroundView, backgroundRect)
                director?.ensureStarted()
            }
        }
    }

    fun start() {
        if (destroyed || booting || director != null) return
        if (needsAllFilesAccess()) {
            showGate(context.getString(R.string.gate_denied))
            if (!promptedAccess && allowSetup) {
                promptedAccess = true
                requestAccess()
            }
            return
        }
        val root = findRoot()
        if (root == null) {
            showGate(null)
            return
        }
        boot(root)
    }

    fun retryIfNeeded() {
        if (director == null) start()
    }

    fun onTree(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, IntentFlags.READ)
        } catch (_: Exception) {
        }
        val file = treeToFile(uri)
        val resolved = file?.let { resolveAssetRoot(it) }
        if (resolved == null) {
            showGate(context.getString(R.string.gate_missing))
            return
        }
        prefs.edit().putString("root", resolved.absolutePath).apply()
        director?.stop()
        director = null
        booting = false
        boot(resolved)
    }

    fun stop() {
        destroyed = true
        director?.stop()
        heicPlayer.cancel()
        maskPlayer.cancel()
        alphaPlayer.stop()
        acceptEnd = false
        activePlayer?.release()
        backgroundPlayer?.release()
        activePlayer = null
        backgroundPlayer = null
    }

    private fun boot(root: File) {
        booting = true
        gate.visibility = View.GONE
        Thread {
            try {
                val catalog = loadCatalog(context)
                val library = Library(root, catalog)
                val saved = SelectionMemory.fromJson(prefs.getString("memory", null))
                main.post {
                    if (destroyed) return@post
                    booting = false
                    val created = Director(this, library)
                    created.attachMemory(saved)
                    director = created
                    created.ensureStarted()
                }
            } catch (error: Exception) {
                Log.e(TAG, "boot", error)
                main.post {
                    booting = false
                    showGate(error.message)
                }
            }
        }.start()
    }

    private fun showGate(extra: String?) {
        fade.visibility = View.GONE
        hint.visibility = View.GONE
        gate.visibility = View.VISIBLE
        val base = context.getString(R.string.gate_body)
        gateBody.text = if (extra.isNullOrBlank()) base else extra + "\n\n" + base
    }

    private fun findRoot(): File? {
        val saved = prefs.getString("root", null)?.let(::File)
        val ext = Environment.getExternalStorageDirectory()
        val candidates = listOfNotNull(
            saved,
            File(ext, "SnoopyAssets"),
            File(ext, "Download/SnoopyAssets"),
            File(ext, "Movies/SnoopyAssets"),
            File(ext, "Documents/SnoopyAssets"),
        )
        for (candidate in candidates) {
            val resolved = try {
                resolveAssetRoot(candidate)
            } catch (_: SecurityException) {
                null
            } ?: continue
            if (canReadSample(resolved)) {
                prefs.edit().putString("root", resolved.absolutePath).apply()
                return resolved
            }
        }
        return null
    }

    private fun needsAllFilesAccess(): Boolean {
        return Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()
    }

    /** Directory listing can succeed while opening the bytes is still denied. */
    private fun canReadSample(root: File): Boolean {
        val bundle = File(root, "idlechara_bundle0_4K_v9")
        val asset = bundle.listFiles()?.firstOrNull { it.isDirectory } ?: return false
        val sample = asset.listFiles()?.firstOrNull { it.isFile } ?: return false
        return try {
            sample.inputStream().use { it.read() >= 0 }
        } catch (error: Exception) {
            Log.w(TAG, "cannot read ${sample.name}: ${error.message}")
            false
        }
    }

    override val width: Int get() = front.width
    override val height: Int get() = front.height

    override fun render(state: DrawState) {
        back.drawState = state
        front.drawState = state
        back.invalidate()
        front.invalidate()
    }

    override fun playActive(file: File, onReady: () -> Unit, onEnded: () -> Unit) {
        acceptEnd = true
        readySent = false
        val serial = ++activeSerial
        activeArmed = -1
        ready = onReady
        ended = onEnded
        val player = activePlayer ?: ExoPlayer.Builder(context).build().also { created ->
            created.volume = 0f
            created.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (activeArmed != activeSerial || readySent) return
                    readySent = true
                    ready?.invoke()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED && acceptEnd) {
                        acceptEnd = false
                        ended?.invoke()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "video ${error.errorCodeName}")
                    if (acceptEnd) {
                        acceptEnd = false
                        ended?.invoke()
                    }
                }
            })
            activePlayer = created
        }
        // Keep the previous picture visible until a decoded frame exists.
        activeView.alpha = 0f
        activeView.visibility = View.VISIBLE
        place(activeView, Placement.active(RectF(0f, 0f, width.toFloat().coerceAtLeast(1f), height.toFloat().coerceAtLeast(1f))))
        player.setVideoTextureView(activeView)
        player.stop()
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        player.playWhenReady = true
        // Arm after prepare, so a frame already queued from the previous clip is ignored.
        main.post {
            if (serial != activeSerial) return@post
            activeArmed = serial
            if (!readySent && player.playbackState == Player.STATE_READY) {
                readySent = true
                ready?.invoke()
            }
        }
    }

    override fun showActive() {
        activeView.alpha = 1f
        activeView.visibility = View.VISIBLE
    }

    override fun holdActive() {
        activePlayer?.pause()
    }

    override fun stopActive() {
        acceptEnd = false
        readySent = true
        activeSerial++
        activePlayer?.stop()
        activeView.alpha = 0f
        activeView.visibility = View.INVISIBLE
    }

    override fun playBackground(file: File, rect: RectF, onReady: () -> Unit) {
        backgroundRect = rect
        val serial = ++backgroundSerial
        backgroundArmed = -1
        backgroundCallback = onReady
        val player = backgroundPlayer ?: ExoPlayer.Builder(context).build().also { created ->
            created.volume = 0f
            created.repeatMode = Player.REPEAT_MODE_ONE
            created.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (backgroundArmed != backgroundSerial) return
                    val ready = backgroundCallback ?: return
                    backgroundCallback = null
                    backgroundView.alpha = 1f
                    ready()
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "background ${error.errorCodeName}")
                    val ready = backgroundCallback ?: return
                    backgroundCallback = null
                    backgroundView.alpha = 1f
                    ready()
                }
            })
            backgroundPlayer = created
        }
        // Hide the previous frame until this clip has drawn its own.
        backgroundView.alpha = 0f
        backgroundView.visibility = View.VISIBLE
        place(backgroundView, rect)
        player.setVideoTextureView(backgroundView)
        player.stop()
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        player.playWhenReady = true
        main.post {
            if (serial != backgroundSerial) return@post
            backgroundArmed = serial
            val ready = backgroundCallback ?: return@post
            if (player.playbackState != Player.STATE_READY) return@post
            backgroundCallback = null
            backgroundView.alpha = 1f
            ready()
        }
    }

    override fun stopBackground() {
        backgroundSerial++
        backgroundCallback = null
        backgroundPlayer?.stop()
        backgroundView.alpha = 0f
        backgroundView.visibility = View.GONE
    }

    override fun playHeic(frames: List<File>, loops: Int, maxPx: Int, onFrame: (Bitmap) -> Unit, onDone: () -> Unit) {
        heicPlayer.play(frames, loops, maxPx, onFrame, onDone)
    }

    override fun stopHeic() {
        heicPlayer.cancel()
    }

    override fun playMask(
        masks: List<File>,
        outlines: List<File>,
        onFrame: (Bitmap?, Bitmap?) -> Unit,
        onDone: () -> Unit,
    ) {
        maskPlayer.play(masks, outlines, onFrame, onDone)
    }

    override fun stopMask() {
        maskPlayer.cancel()
    }

    override fun playAlpha(
        files: List<File>,
        onFrame: (Bitmap) -> Unit,
        onEnded: () -> Unit,
        onFailed: (String) -> Unit,
    ) {
        alphaPlayer.play(files, onFrame, onEnded, onFailed)
    }

    override fun stopAlpha() {
        alphaPlayer.stop()
    }

    override fun decodeStill(file: File, maxPx: Int, asMask: Boolean, done: (Bitmap?) -> Unit) {
        Thread {
            val bitmap = Heic.decode(file, maxPx, asMask)
            main.post { if (!destroyed) done(bitmap) }
        }.start()
    }

    override fun fadeIn() {
        if (faded) return
        faded = true
        fade.animate().alpha(0f).setDuration(1000).withEndAction { fade.visibility = View.GONE }
        if (allowSetup) {
            hint.animate().alpha(0f).setStartDelay(1600).setDuration(700).withEndAction {
                hint.visibility = View.GONE
            }
        }
    }

    override fun saveMemory(memory: SelectionMemory) {
        prefs.edit().putString("memory", memory.toJson()).apply()
    }

    override fun post(delayMs: Long, block: () -> Unit) {
        if (delayMs <= 0) main.post(block) else main.postDelayed(block, delayMs)
    }

    private fun place(view: View, rect: RectF) {
        if (rect.width() < 2f || rect.height() < 2f) return
        val params = FrameLayout.LayoutParams(rect.width().toInt(), rect.height().toInt())
        params.leftMargin = rect.left.toInt()
        params.topMargin = rect.top.toInt()
        view.layoutParams = params
    }

    private fun halftone(): Bitmap? = try {
        context.assets.open("halftone_pattern.png").use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val PREFS = "snoopy"
        private const val TAG = "SnoopySaver"

        fun treeToFile(uri: Uri): File? {
            val document = try {
                DocumentsContract.getTreeDocumentId(uri)
            } catch (_: Exception) {
                return null
            }
            val parts = document.split(":", limit = 2)
            if (parts.size == 2 && parts[0].equals("primary", true)) {
                return File(Environment.getExternalStorageDirectory(), parts[1])
            }
            return null
        }
    }
}

private object IntentFlags {
    const val READ = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
}
