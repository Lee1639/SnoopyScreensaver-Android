package com.snoopysaver.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

class ScreensaverActivity : Activity() {
    private var session: SnoopySession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableFullBleed()
        setContentView(R.layout.screensaver)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(0, 0, 0, 0)
            if (Build.VERSION.SDK_INT >= 30) WindowInsets.CONSUMED else insets
        }
        hideSystemBars()
        session = SnoopySession(
            context = this,
            root = findViewById(R.id.root),
            allowSetup = true,
            requestTree = { openTree() },
            requestAccess = { openAllFilesAccess() },
        )
        session?.start()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        session?.retryIfNeeded()
    }

    override fun onDestroy() {
        session?.stop()
        session = null
        super.onDestroy()
    }

    @Deprecated("Activity result API is enough for a single folder picker.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_TREE && resultCode == RESULT_OK) {
            data?.data?.let { session?.onTree(it) }
        }
    }

    private fun openTree() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, REQUEST_TREE)
    }

    private fun openAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            val specific = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            try {
                startActivity(specific)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            @Suppress("DEPRECATION")
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_READ)
        }
    }

    private fun enableFullBleed() {
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            // Some vendor builds throw if this runs before the decor view exists.
            val controller = try {
                window.decorView.windowInsetsController
            } catch (_: Exception) {
                null
            } ?: return
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    companion object {
        private const val REQUEST_TREE = 42
        private const val REQUEST_READ = 43
    }
}
