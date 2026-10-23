package com.snoopysaver.android

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log

interface Host {
    val width: Int
    val height: Int
    fun render(state: DrawState)
    fun playActive(file: java.io.File, onReady: () -> Unit, onEnded: () -> Unit)
    fun showActive()
    fun holdActive()
    fun stopActive()
    fun playBackground(file: java.io.File, rect: RectF, onReady: () -> Unit = {})
    fun stopBackground()
    fun playHeic(frames: List<java.io.File>, loops: Int, maxPx: Int, onFrame: (Bitmap) -> Unit, onDone: () -> Unit)
    fun stopHeic()
    fun playMask(masks: List<java.io.File>, outlines: List<java.io.File>, onFrame: (Bitmap?, Bitmap?) -> Unit, onDone: () -> Unit)
    fun stopMask()
    fun playAlpha(files: List<java.io.File>, onFrame: (Bitmap) -> Unit, onEnded: () -> Unit, onFailed: (String) -> Unit)
    fun stopAlpha()
    fun decodeStill(file: java.io.File, maxPx: Int, asMask: Boolean, done: (Bitmap?) -> Unit)
    fun fadeIn()
    fun saveMemory(memory: SelectionMemory)
    fun post(delayMs: Long = 0, block: () -> Unit)
}

class Director(private val host: Host, private val library: Library) {
    private val graph = Graph(library.catalog)
    private val policy = SelectionPolicy()
    private val relevancy = Relevancy()
    private val bag = SessionBag()
    private val memory = SelectionMemory.fromJson(null)
    private val state = DrawState()
    private var seed = kotlin.random.Random.nextLong().toULong()
    private var epoch = 0
    private var running = false
    private var currentPose: String? = null
    private var currentIdle: Asset? = null
    private var idleStarted = 0L
    private var visitorTimes = emptyList<Double>()
    private var visitorIndex = 0
    private var consecutiveKeeps = 0
    private var alphaOk = true
    private var alphaBusy = false
    private var fullscreenVisitor = false
    private var offsetX = 0.0
    private var offsetY = 0.0
    private var spins = 0
    private var loadedMemory = false

    fun attachMemory(saved: SelectionMemory) {
        if (loadedMemory) return
        loadedMemory = true
        memory.recent.putAll(saved.recent)
        memory.last.putAll(saved.last)
        memory.counts.putAll(saved.counts)
    }

    fun ensureStarted() {
        if (running) onResize() else start()
    }

    fun start() {
        if (running || host.width <= 0 || host.height <= 0) return
        running = true
        Log.i(TAG, "start ${host.width}x${host.height} assets=${library.catalog.assets.size}")
        val active = pickActive()
        if (active == null) {
            enterIdle(chooseIdle(keep = null), transition = null)
        } else {
            val transition = pickTransition(active, requireChange = false)
            playActiveFile(active) { enterIdle(chooseIdle(keep = null), transition) }
        }
    }

    fun stop() {
        running = false
        epoch++
        host.stopHeic()
        host.stopMask()
        host.stopAlpha()
        host.stopActive()
        host.stopBackground()
    }

    fun onResize() {
        if (!running) return
        val idle = currentIdle ?: return
        val sprite = idle.sprites.firstOrNull()
        state.backgroundRect = Placement.sprite(sprite, bounds(), offsetX, offsetY, false)
        state.activeViewport = Placement.active(bounds())
        host.render(state)
    }

    private fun playBeat() {
        if (!running) return
        val idle = currentIdle ?: return
        if (SystemClock.elapsedRealtime() - idleStarted >= IDLE_MS) {
            rotate()
            return
        }
        maybeVisitor(idle)
        val poseId = currentPose
        if (poseId == null) {
            again()
            return
        }
        if (!alphaOk || alphaBusy) {
            playShiftOrBase(poseId) { playBeat() }
            return
        }
        val world = worldNow()
        if (fullscreenVisitor) world.activeCategories += "sceneFullscreenEffectVisitor"
        val available = availableKinds(idle, poseId, world)
        val kind = bag.nextKind(available, seed) ?: AnimKind.BASE
        seed += 1u
        if (kind == AnimKind.BASE) {
            playBase(poseId) { playBeat() }
            return
        }
        val sequence = pickSequence(idle, poseId, kind, world)
        if (sequence == null) playBase(poseId) { playBeat() } else playSequence(sequence, 0) { playBeat() }
    }

    private fun rotate() {
        val pose = currentPose
        val exit = pose?.let { graph.reactionEnter(it) }
        fun go() {
            val active = pickActive()
            if (active == null) {
                idleStarted = SystemClock.elapsedRealtime()
                playBeat()
                return
            }
            val transition = pickTransition(active, requireChange = consecutiveKeeps >= 2)
            val reveal = transition?.revealId?.let { graph.byId[it] }
            val masks = reveal?.let { maskFrames(it) }.orEmpty()
            val outlines = reveal?.let { outlineFrames(it) }.orEmpty()
            playActiveFile(active, onReady = { token ->
                if (masks.isEmpty()) return@playActiveFile false
                // Stay on the idle picture until a real mask frame exists, then open the video
                // underneath it. Showing the video first paints the scene back on top for a flash.
                revealUnderMask(token, masks, outlines)
                true
            }, onEnded = {
                host.stopMask()
                state.mask = null
                state.outline = null
                val keep = if (transition?.preventsIdleChange == true) currentIdle else null
                enterIdle(chooseIdle(keep), transition)
            })
        }
        if (exit != null && library.heicFrames(exit).isNotEmpty()) {
            playHeicAsset(exit, 1, arm()) { go() }
        } else {
            go()
        }
    }

    private fun playActiveFile(asset: Asset, onReady: (Int) -> Boolean = { _ -> false }, onEnded: () -> Unit) {
        val file = library.primaryMovie(asset) ?: run {
            onEnded()
            return
        }
        val token = arm()
        val finish = once(token, onEnded)
        // Freeze the current picture, but leave it up until the new frame can replace it.
        host.stopHeic()
        host.stopAlpha()
        alphaBusy = false
        Log.i(TAG, "active ${asset.id}")
        host.playActive(file, onReady = {
            if (!live(token)) return@playActive
            host.fadeIn()
            if (!onReady(token) && live(token)) cutToActive()
        }, onEnded = { finish() })
        val seconds = asset.media.firstOrNull { it.name.endsWith(".mov") }?.durationSeconds ?: 45.0
        watch(token, (seconds * 1000 + 8_000).toLong()) {
            host.stopActive()
            finish()
        }
    }

    /** Drop the idle picture in the same turn the decoded video becomes visible. */
    private fun cutToActive() {
        state.mode = DrawState.MODE_ACTIVE
        state.character = null
        state.visitor = null
        state.pose = null
        state.mask = null
        state.outline = null
        host.stopBackground()
        host.render(state)
        host.showActive()
    }

    private fun revealUnderMask(token: Int, masks: List<java.io.File>, outlines: List<java.io.File>) {
        var opened = false
        host.playMask(masks, outlines, onFrame = { mask, outline ->
            if (!live(token) || mask == null) return@playMask
            state.mode = DrawState.MODE_TRANSITION
            state.activeViewport = Placement.active(bounds())
            state.mask = mask
            state.outline = outline
            if (!opened) {
                opened = true
                host.stopBackground()
                host.showActive()
            }
            host.render(state)
        }, onDone = {
            if (!live(token)) return@playMask
            state.mask = null
            state.outline = null
            cutToActive()
        })
    }

    private fun enterIdle(idle: Asset?, transition: TransitionPick?) {
        if (!running) return
        if (idle == null) {
            again()
            return
        }
        currentIdle = idle
        idleStarted = SystemClock.elapsedRealtime()
        bag.resetMix()
        visitorTimes = visitorSchedule(seed)
        seed += 1u
        visitorIndex = 0
        fullscreenVisitor = false
        if (transition?.preventsIdleChange == true) consecutiveKeeps++ else consecutiveKeeps = 0
        val pose = chooseBase()
        if (pose == null) {
            again()
            return
        }
        currentPose = pose.id
        Log.i(TAG, "idle ${idle.id} pose ${pose.id} keep=${transition?.preventsIdleChange == true}")
        showRoom(idle) {
            val hide = transition?.hideId?.let { graph.byId[it] }
            val masks = hide?.let { maskFrames(it) }.orEmpty()
            if (transition != null && masks.isNotEmpty() && hide != null) {
                playHide(hide, transition, pose) { settleEntry(pose) }
            } else {
                host.stopActive()
                state.mode = DrawState.MODE_IDLE
                host.render(state)
                settleEntry(pose)
            }
        }
    }

    private fun settleEntry(pose: Asset) {
        val entry = graph.reactionExit(pose.id)
        if (entry != null && library.heicFrames(entry).isNotEmpty()) {
            playHeicAsset(entry, 1, arm()) { playBase(pose.id) { playBeat() } }
        } else {
            playBase(pose.id) { playBeat() }
        }
    }

    private fun playHide(parameter: Asset, transition: TransitionPick, pose: Asset, done: () -> Unit) {
        val token = arm()
        host.holdActive()
        var remaining = 1
        fun step() {
            if (!live(token)) return
            remaining--
            if (remaining != 0) return
            state.mask = null
            state.outline = null
            state.pose = null
            state.mode = DrawState.MODE_IDLE
            host.stopActive()
            host.render(state)
            done()
        }
        val poseAsset = transition.hidePoses.firstNotNullOfOrNull { id ->
            graph.byId[id]?.takeIf { it.transitionPhase == "hide" && library.primaryMovie(it) != null }
        }
        if (poseAsset != null && alphaOk) {
            remaining++
            val files = library.phasedMovies(poseAsset, 1).ifEmpty { listOfNotNull(library.primaryMovie(poseAsset)) }
            alphaBusy = true
            host.playAlpha(files, onFrame = { bitmap ->
                if (!live(token)) return@playAlpha
                state.pose = bitmap
                state.poseRect = Placement.sprite(poseAsset.sprites.firstOrNull(), bounds(), offsetX, offsetY, false)
                host.render(state)
            }, onEnded = {
                alphaBusy = false
                step()
            }, onFailed = {
                alphaBusy = false
                alphaOk = false
                step()
            })
        }
        host.playMask(maskFrames(parameter), outlineFrames(parameter), onFrame = { mask, outline ->
            if (!live(token) || mask == null) return@playMask
            state.mode = DrawState.MODE_TRANSITION
            state.activeViewport = Placement.active(bounds())
            state.mask = mask
            state.outline = outline
            host.render(state)
        }, onDone = { step() })
        watch(token, 12_000) {
            host.stopMask()
            host.stopAlpha()
            alphaBusy = false
            remaining = 1
            step()
        }
    }

    private fun showRoom(idle: Asset, then: () -> Unit) {
        val palette = choosePalette(idle)
        state.palette = palette?.background ?: DEFAULT_PALETTE
        state.overlay = palette?.overlay ?: 0
        offsetX = idle.idle?.offsetX ?: 0.0
        offsetY = idle.idle?.offsetY ?: 0.0
        val sprite = idle.sprites.firstOrNull()
        val rect = Placement.sprite(sprite, bounds(), offsetX, offsetY, false)
        state.backgroundRect = rect
        val movie = idle.sprites.firstOrNull { it.type == "video" }?.let { library.movieFile(idle, it) }
        val still = library.heicFrames(idle, sprite?.baseName).firstOrNull()
        host.render(state)
        if (movie != null && VideoProbe.kind(movie) != VideoKind.ALPHA) {
            state.background = null
            var opened = false
            fun open() {
                if (opened || !running) return
                opened = true
                host.fadeIn()
                then()
            }
            // Keep the outgoing picture until this scene's first frame is actually drawn.
            host.playBackground(movie, rect) { open() }
            host.post(4_000) { open() }
        } else if (still != null) {
            host.stopBackground()
            host.decodeStill(still, maxPx(), false) { bitmap ->
                state.background = bitmap
                host.render(state)
                host.fadeIn()
                then()
            }
        } else {
            host.fadeIn()
            then()
        }
    }

    private fun playBase(poseId: String, done: () -> Unit) {
        val asset = graph.byId[poseId]
        val frames = asset?.let { library.heicFrames(it) }.orEmpty()
        if (asset == null || frames.isEmpty()) {
            done()
            return
        }
        val loops = basePoseLoops(seed)
        seed += 1u
        val token = arm()
        val finish = once(token) {
            bag.record(AnimKind.BASE, frames.size / 24.0 * loops)
            currentPose = poseId
            spins = 0
            done()
        }
        playHeicAsset(asset, loops, token, finish)
        watch(token, (frames.size / 24.0 * loops * 1000 + 6_000).toLong()) {
            host.stopHeic()
            finish()
        }
    }

    private fun playShiftOrBase(poseId: String, done: () -> Unit) {
        val targets = library.catalog.assets.filter {
            it.kind == "characterBasePose" && it.id != poseId && library.heicFrames(it).isNotEmpty()
        }
        val bridges = targets.mapNotNull { target -> graph.poseTransition(poseId, target.id)?.let { it to target } }
        if (bridges.isNotEmpty() && mix(seed) % 4uL == 0uL) {
            seed += 1u
            val index = (mix(seed) % bridges.size.toULong()).toInt()
            seed += 1u
            val (bridge, target) = bridges[index]
            playHeicAsset(bridge, 1, arm()) {
                currentPose = target.id
                playBase(target.id, done)
            }
        } else {
            seed += 1u
            playBase(poseId, done)
        }
    }

    private fun playSequence(assets: List<Asset>, index: Int, done: () -> Unit) {
        if (!running) return
        if (index >= assets.size) {
            done()
            return
        }
        val asset = assets[index]
        val movies = when (asset.kind) {
            "characterAdditionalPose" -> {
                val loops = if (asset.id.startsWith("101_AP00")) sustainedPoseLoops(seed) else extraPoseLoops(seed)
                seed += 1u
                library.phasedMovies(asset, loops)
            }
            "characterMoment" -> {
                val loops = 5 + (mix(seed) % 6u).toInt()
                seed += 1u
                library.phasedMovies(asset, loops)
            }
            "characterBasePose" -> emptyList()
            else -> library.phasedMovies(asset, 1)
        }
        val frames = library.heicFrames(asset)
        if (asset.kind == "characterBasePose" || (movies.isEmpty() && frames.isNotEmpty())) {
            val loops = if (asset.kind == "characterBasePose") basePoseLoops(seed).also { seed += 1u } else 1
            playHeicAsset(asset, loops, arm()) {
                if (asset.kind == "characterBasePose") {
                    currentPose = asset.id
                    bag.record(AnimKind.BASE, frames.size / 24.0 * loops)
                }
                playSequence(assets, index + 1, done)
            }
            return
        }
        if (movies.isEmpty() || !alphaOk) {
            if (asset.endPose != null) currentPose = asset.endPose
            playSequence(assets, index + 1, done)
            return
        }
        val token = arm()
        val rect = Placement.sprite(asset.sprites.firstOrNull { it.type == "video" }, bounds(), offsetX, offsetY, false)
        val finish = once(token) { playSequence(assets, index + 1, done) }
        alphaBusy = true
        host.playAlpha(movies, onFrame = { bitmap ->
            if (!live(token)) return@playAlpha
            state.character = bitmap
            state.characterRect = rect
            state.pose = null
            host.render(state)
            spins = 0
        }, onEnded = {
            alphaBusy = false
            val kind = if (asset.kind == "characterMoment") AnimKind.MOMENT else AnimKind.EXTRA
            bag.record(kind, movies.sumOf { library.durationSeconds(it) })
            asset.endPose?.let { currentPose = it }
            finish()
        }, onFailed = { reason ->
            Log.w(TAG, "alpha off after ${asset.id}: $reason")
            alphaBusy = false
            alphaOk = false
            asset.endPose?.let { currentPose = it }
            finish()
        })
        val estimate = movies.sumOf { library.durationSeconds(it) }
        watch(token, (estimate * 1000 + 8_000).toLong()) {
            host.stopAlpha()
            alphaBusy = false
            finish()
        }
    }

    private fun playHeicAsset(asset: Asset, loops: Int, token: Int, done: () -> Unit) {
        val sprite = asset.sprites.firstOrNull { it.type == "frameSequence" } ?: asset.sprites.firstOrNull()
        val frames = if (sprite?.baseName != null) library.heicFrames(asset, sprite.baseName) else library.heicFrames(asset)
        if (frames.isEmpty()) {
            if (live(token)) done()
            return
        }
        val rect = Placement.sprite(sprite, bounds(), offsetX, offsetY, false)
        host.playHeic(frames, loops, maxPx(), onFrame = { bitmap ->
            if (!live(token) || state.mode == DrawState.MODE_ACTIVE) return@playHeic
            state.character = bitmap
            state.characterRect = rect
            state.pose = null
            if (state.mode != DrawState.MODE_TRANSITION) state.mode = DrawState.MODE_IDLE
            host.render(state)
            host.fadeIn()
            spins = 0
        }, onDone = {
            if (live(token)) done()
        })
    }

    private fun maybeVisitor(idle: Asset) {
        if (!alphaOk || alphaBusy || visitorIndex >= visitorTimes.size) return
        val elapsed = (SystemClock.elapsedRealtime() - idleStarted) / 1000.0
        if (elapsed < visitorTimes[visitorIndex]) return
        visitorIndex++
        val world = worldNow()
        val candidates = graph.visitors(idle).filter { library.primaryMovie(it) != null }
        val visitor = pick(candidates, "visitors", world) ?: return
        val files = library.phasedMovies(visitor, 2).ifEmpty { listOfNotNull(library.primaryMovie(visitor)) }
        if (files.isEmpty()) return
        if (VideoProbe.kind(files.first()) == VideoKind.UNSUPPORTED) return
        fullscreenVisitor = visitor.visitor?.fullscreen == true
        val sprite = visitor.sprites.firstOrNull()
        val ignore = visitor.visitor?.ignoresOffset == true || visitor.visitor?.fullscreen == true
        val rect = if (visitor.visitor?.fullscreen == true) {
            Placement.fit(bounds())
        } else {
            Placement.sprite(sprite, bounds(), offsetX, offsetY, ignore)
        }
        state.visitorBehind = sprite?.plane?.startsWith("background") == true
        state.visitorRect = rect
        alphaBusy = true
        host.playAlpha(files, onFrame = { bitmap ->
            if (state.mode == DrawState.MODE_ACTIVE) return@playAlpha
            state.visitor = bitmap
            host.render(state)
        }, onEnded = {
            alphaBusy = false
            fullscreenVisitor = false
            state.visitor = null
            host.render(state)
        }, onFailed = {
            alphaBusy = false
            fullscreenVisitor = false
            alphaOk = false
            state.visitor = null
        })
    }

    private fun availableKinds(idle: Asset, poseId: String, world: World): Set<AnimKind> {
        val kinds = mutableSetOf(AnimKind.BASE)
        val extras = graph.actions("characterAdditionalPose", idle).filter {
            graph.actionSequence(poseId, it) != null && relevancy.score(it, world) != null
        }
        val moments = graph.actions("characterMoment", idle).filter {
            graph.actionSequence(poseId, it) != null && relevancy.score(it, world) != null
        }
        if (extras.isNotEmpty()) kinds += AnimKind.EXTRA
        if (moments.isNotEmpty()) kinds += AnimKind.MOMENT
        return kinds
    }

    private fun pickSequence(idle: Asset, poseId: String, kind: AnimKind, world: World): List<Asset>? {
        val type = if (kind == AnimKind.MOMENT) "characterMoment" else "characterAdditionalPose"
        val reachable = graph.actions(type, idle).mapNotNull { asset ->
            graph.actionSequence(poseId, asset)?.let { asset }
        }
        val target = pick(reachable, "characterActions", world) ?: return null
        return graph.actionSequence(poseId, target)
    }

    private fun pickActive(): Asset? {
        val assets = library.catalog.assets.filter { it.kind == "activeScene" && library.primaryMovie(it) != null }
        return pick(assets, "activeVideos", worldNow())
    }

    private fun chooseIdle(keep: Asset?): Asset? {
        if (keep != null && hasRoom(keep)) return keep
        val assets = library.catalog.assets.filter { it.kind == "idleScene" && hasRoom(it) }
        return pick(assets, "idleScenes", worldNow())
    }

    private fun chooseBase(): Asset? {
        val assets = library.catalog.assets.filter { it.kind == "characterBasePose" && library.heicFrames(it).isNotEmpty() }
        return pick(assets, "basePoses", worldNow())
    }

    private fun choosePalette(idle: Asset): PaletteColors? {
        var palettes = library.catalog.assets.filter { it.kind == "scenePalette" && it.palette != null }
        val related = palettes.filter { idle.id in (it.palette?.parentIdleIds ?: emptyList()) }
        if (related.isNotEmpty()) palettes = related
        val excluded = idle.idle?.excludedPalettes.orEmpty()
        palettes = palettes.filter { it.id !in excluded }
        return pick(palettes, "palettes", worldNow())?.palette
    }

    private fun pick(assets: List<Asset>, pool: String, world: World): Asset? {
        if (assets.isEmpty()) return null
        var weighted = policy.weighted(assets, world, memory, pool)
        if (weighted.isEmpty()) return null
        val limit = policy.recentLimit(pool, weighted.size)
        val recent = memory.recentIds(pool, limit)
        val fresh = weighted.filter { it.first.id !in recent }
        if (fresh.isNotEmpty()) weighted = fresh
        else {
            val previous = memory.last[pool]
            if (previous != null && weighted.size > 1) weighted = weighted.filter { it.first.id != previous }
        }
        val selected = bag.choose(weighted, pool, seed) ?: return null
        seed += 1u
        memory.record(selected.id, pool, limit)
        host.saveMemory(memory)
        return selected
    }

    private fun pickTransition(active: Asset, requireChange: Boolean): TransitionPick? {
        var candidates = transitionPicks(active).filter { playable(it) }
        if (requireChange) {
            val changing = candidates.filter { !it.preventsIdleChange }
            if (changing.isNotEmpty()) candidates = changing
        }
        if (candidates.isEmpty()) return null
        val world = worldNow()
        candidates = candidates.filter { relevancy.score(graph.byId[it.pairId] ?: return@filter false, world) != null }
        if (candidates.isEmpty()) return null
        val families = candidates.groupBy { it.family }
        val recentFamilies = memory.recentIds("sceneTransitionParameterFamilies", 4)
        var familyIds = families.keys.filter { it !in recentFamilies }.sorted()
        if (familyIds.isEmpty()) familyIds = families.keys.sorted()
        val weights = familyIds.map { id ->
            families.getValue(id).maxOf {
                policy.relevanceWeight(relevancy.score(graph.byId[it.pairId]!!, world) ?: 0)
            }
        }
        val selectedFamily = weightedPick(familyIds, weights)
        var pool = families.getValue(selectedFamily)
        val fresh = pool.filter { it.pairId !in memory.recentIds("sceneTransitionPairs", 4) && it.pairId !in bag.recentPairs }
        if (fresh.isNotEmpty()) pool = fresh
        val ordered = pool.sortedBy { it.pairId }
        val pairWeights = ordered.map { policy.relevanceWeight(relevancy.score(graph.byId[it.pairId]!!, world) ?: 0) }
        val selected = ordered[weightedIndex(pairWeights)]
        memory.record(selected.pairId, "sceneTransitionPairs", 6)
        memory.record(selected.categoryId, "sceneTransitionCategories", 6)
        memory.record(selected.family, "sceneTransitionParameterFamilies", 4)
        bag.rememberPair(selected.pairId)
        host.saveMemory(memory)
        Log.i(TAG, "transition ${selected.pairId} keep=${selected.preventsIdleChange}")
        return selected
    }

    private fun weightedPick(ids: List<String>, weights: List<Int>): String {
        return ids[weightedIndex(weights)]
    }

    private fun weightedIndex(weights: List<Int>): Int {
        val total = weights.sum().coerceAtLeast(1)
        var ticket = (mix(seed) % total.toULong()).toInt()
        seed += 1u
        for (index in weights.indices) {
            if (ticket < weights[index]) return index
            ticket -= weights[index]
        }
        return weights.lastIndex
    }

    private fun transitionPicks(active: Asset): List<TransitionPick> {
        val picks = mutableListOf<TransitionPick>()
        for (categoryId in active.transitionCategoryIds) {
            val category = graph.byId[categoryId]?.category ?: continue
            for (pairId in category.pairIds) {
                val pair = graph.byId[pairId]?.pair ?: continue
                picks += TransitionPick(
                    categoryId, pairId, pair.hideId, pair.revealId,
                    category.hidePoses, category.revealPoses, category.preventsIdleChange,
                )
            }
        }
        return picks
    }

    private fun playable(pick: TransitionPick): Boolean {
        val hide = pick.hideId?.let { graph.byId[it] } ?: return false
        val reveal = pick.revealId?.let { graph.byId[it] } ?: return false
        return maskFrames(hide).isNotEmpty() && outlineFrames(hide).isNotEmpty() &&
            maskFrames(reveal).isNotEmpty() && outlineFrames(reveal).isNotEmpty()
    }

    private fun maskFrames(asset: Asset): List<java.io.File> {
        val sprite = asset.sprites.firstOrNull { it.plane == "mask" } ?: return emptyList()
        return sprite.baseName?.let { library.heicFrames(asset, it) } ?: emptyList()
    }

    private fun outlineFrames(asset: Asset): List<java.io.File> {
        val sprite = asset.sprites.firstOrNull { it.plane == "foregroundEffect" } ?: return emptyList()
        return sprite.baseName?.let { library.heicFrames(asset, it) } ?: emptyList()
    }

    private fun hasRoom(idle: Asset): Boolean {
        if (library.heicFrames(idle).isNotEmpty()) return true
        return idle.sprites.any { it.type == "video" && library.movieFile(idle, it) != null }
    }

    private fun again() {
        spins++
        if (spins > 6) host.post(700) { spins = 0; playBeat() } else host.post { playBeat() }
    }

    private fun watch(token: Int, delayMs: Long, block: () -> Unit) {
        host.post(delayMs.coerceAtLeast(1_000)) {
            if (token == epoch && running) block()
        }
    }

    private fun arm(): Int = ++epoch
    private fun live(token: Int): Boolean = running && token == epoch

    private fun once(token: Int, block: () -> Unit): () -> Unit {
        var used = false
        return {
            if (!used && live(token)) {
                used = true
                block()
            }
        }
    }
    private fun bounds(): RectF = RectF(0f, 0f, host.width.toFloat().coerceAtLeast(1f), host.height.toFloat().coerceAtLeast(1f))
    private fun maxPx(): Int = maxOf(host.width, host.height).coerceIn(1080, 2880)

    companion object {
        private const val IDLE_MS = 240_000L
        private const val TAG = "SnoopySaver"
    }
}
