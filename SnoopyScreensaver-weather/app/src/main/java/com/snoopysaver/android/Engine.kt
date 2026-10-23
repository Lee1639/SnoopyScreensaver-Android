package com.snoopysaver.android

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class MediaFile(val name: String, val suffix: String, val durationSeconds: Double?)

data class Sprite(
    val type: String,
    val baseName: String?,
    val width: Double,
    val height: Double,
    val phase: String?,
    val plane: String?,
    val alignment: String,
    val mediaFiles: List<String>,
)

data class IdleInfo(
    val offsetX: Double,
    val offsetY: Double,
    val excludedPoses: Set<String>,
    val excludedMoments: Set<String>,
    val excludedPalettes: Set<String>,
    val excludedVisitors: Set<String>,
)

data class PaletteColors(val background: Int, val overlay: Int?, val parentIdleIds: List<String>)

data class VisitorInfo(val ignoresOffset: Boolean, val fullscreen: Boolean)

data class TransitionCategory(
    val hidePoses: List<String>,
    val revealPoses: List<String>,
    val pairIds: List<String>,
    val preventsIdleChange: Boolean,
)

data class TransitionPair(val hideId: String?, val revealId: String?)

data class Asset(
    val id: String,
    val bundle: String,
    val relativePath: String,
    val kind: String,
    val sprites: List<Sprite>,
    val media: List<MediaFile>,
    val valid: Boolean,
    val relevancy: JSONObject?,
    val startPose: String?,
    val endPose: String?,
    val transitionPhase: String?,
    val transitionCategoryIds: List<String>,
    val pair: TransitionPair?,
    val category: TransitionCategory?,
    val palette: PaletteColors?,
    val parentIdleIds: List<String>,
    val idle: IdleInfo?,
    val visitor: VisitorInfo?,
    val phaseKind: String?,
    val phaseStartPose: String?,
    val phaseEndPose: String?,
) {
    val hasConditions: Boolean
        get() {
            val info = relevancy?.optJSONArray("info") ?: return false
            return info.length() > 0
        }
}

class Catalog(val assets: List<Asset>, val byId: Map<String, Asset>)

class Library(val root: File, val catalog: Catalog) {
    private val existsCache = HashMap<String, Boolean>()
    private val durationCache = HashMap<String, Double>()

    fun file(asset: Asset, name: String): File = File(File(root, asset.relativePath), name)

    fun exists(file: File): Boolean {
        existsCache[file.path]?.let { return it }
        val ok = try {
            file.isFile
        } catch (_: SecurityException) {
            false
        }
        existsCache[file.path] = ok
        return ok
    }

    fun heicFrames(asset: Asset, baseName: String? = null): List<File> {
        val names = asset.media.map { it.name }.filter { name ->
            name.endsWith(".heic", true) && (baseName == null || name.startsWith(baseName))
        }.sortedBy { frameNumber(it) }
        return names.map { file(asset, it) }.filter { exists(it) }
    }

    fun movieFile(asset: Asset, sprite: Sprite): File? {
        val base = sprite.baseName ?: return null
        val name = asset.media.firstOrNull { it.name == "$base.mov" }?.name ?: "$base.mov"
        val candidate = file(asset, name)
        return candidate.takeIf { exists(it) }
    }

    fun primaryMovie(asset: Asset): File? {
        val sprite = asset.sprites.firstOrNull { it.type == "video" } ?: return null
        return movieFile(asset, sprite)
    }

    /** Intro once, loop phase repeated, outro once. Matches the macOS phased plan. */
    fun phasedMovies(asset: Asset, loopRepeats: Int): List<File> {
        val ranked = asset.sprites.filter { it.type == "video" }.sortedBy { phaseRank(it.phase) }
        val intro = mutableListOf<File>()
        val loop = mutableListOf<File>()
        val outro = mutableListOf<File>()
        for (sprite in ranked) {
            val url = movieFile(asset, sprite) ?: continue
            when (phaseRank(sprite.phase)) {
                0 -> intro += url
                2 -> outro += url
                else -> loop += url
            }
        }
        if (intro.isEmpty() && loop.isEmpty() && outro.isEmpty()) return emptyList()
        val explicit = ranked.any { phaseName ->
            val value = (phaseName.phase ?: "").lowercase()
            "intro" in value || "loop" in value || "outro" in value
        }
        val files = mutableListOf<File>()
        files += intro
        if (!explicit) {
            files += loop
            files += outro
        } else if (loop.isEmpty()) {
            files += outro
        } else {
            repeat(loopRepeats.coerceAtLeast(1)) { files += loop }
            files += outro
        }
        return files
    }

    fun durationSeconds(file: File): Double {
        durationCache[file.path]?.let { return it }
        var seconds = 2.0
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_DURATION)) {
                    seconds = format.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0
                    break
                }
            }
        } catch (_: Exception) {
        } finally {
            extractor.release()
        }
        if (seconds < 0.05) seconds = 2.0
        durationCache[file.path] = seconds
        return seconds
    }
}

fun loadCatalog(context: Context): Catalog {
    val text = context.assets.open("asset-index.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
    val root = JSONObject(text)
    val array = root.getJSONArray("assets")
    val parsed = ArrayList<Asset>(array.length())
    for (index in 0 until array.length()) {
        val obj = array.optJSONObject(index) ?: continue
        parsed += parseAsset(obj)
    }
    val byId = LinkedHashMap<String, Asset>()
    parsed.sortedWith(compareBy({ it.bundle }, { it.relativePath })).forEach { byId[it.id] = it }
    return Catalog(byId.values.toList(), byId)
}

fun resolveAssetRoot(dir: File): File? {
    if (!dir.isDirectory) return null
    if (File(dir, "idlechara_bundle0_4K_v9").isDirectory) return dir
    val nested = File(dir, "SnoopyAssets")
    if (File(nested, "idlechara_bundle0_4K_v9").isDirectory) return nested
    val children = dir.listFiles() ?: return null
    if (children.any { it.isDirectory && it.name.startsWith("idlechara_bundle") }) return dir
    return children.firstOrNull { child ->
        child.isDirectory && File(child, "idlechara_bundle0_4K_v9").isDirectory
    }
}

private fun parseAsset(o: JSONObject): Asset {
    val sprites = parseSprites(o.optJSONArray("sprites"))
    val media = parseMedia(o.optJSONArray("media")).toMutableList()
    for (sprite in sprites) {
        for (name in sprite.mediaFiles) {
            if (media.none { it.name == name }) {
                val suffix = name.substringAfterLast('.', "")
                media += MediaFile(name, if (suffix.isEmpty()) "" else ".$suffix", null)
            }
        }
    }
    val integrity = o.optJSONObject("integrity")
    val valid = if (integrity == null || !integrity.has("valid")) true else integrity.optBoolean("valid")
    val phase = o.optJSONObject("phase")
    val idleObj = o.optJSONObject("idleScene")
    val exclusions = idleObj?.optJSONObject("exclusions")
    val offset = idleObj?.optJSONObject("sceneOffset")
    val paletteObj = o.optJSONObject("scenePalette")
    val pairObj = o.optJSONObject("transitionPair")
    val categoryObj = o.optJSONObject("transitionCategory")
    val visitorObj = o.optJSONObject("visitor")
    return Asset(
        id = o.optString("id"),
        bundle = o.optString("bundle"),
        relativePath = o.optString("relativePath").ifEmpty { o.optString("path") },
        kind = o.optString("metadataType").ifEmpty { o.optString("type", "unknown") },
        sprites = sprites,
        media = media,
        valid = valid,
        relevancy = o.optJSONObject("relevancyData"),
        startPose = o.str("startCharacterBasePoseID"),
        endPose = o.str("endCharacterBasePoseID"),
        transitionPhase = o.str("transitionPhase"),
        transitionCategoryIds = strings(o.optJSONArray("transitionCategoryIDs")),
        pair = pairObj?.let { TransitionPair(it.str("hideParametersID"), it.str("revealParametersID")) },
        category = categoryObj?.let {
            TransitionCategory(
                hidePoses = strings(it.optJSONArray("hideCharacterPoseIDs")),
                revealPoses = strings(it.optJSONArray("revealCharacterPoseIDs")),
                pairIds = strings(it.optJSONArray("sceneTransitionPairIDs")),
                preventsIdleChange = it.optBoolean("preventsIdleSceneChange"),
            )
        },
        palette = paletteObj?.let { parsePalette(it) },
        parentIdleIds = strings(o.optJSONArray("parentIdleSceneIDs")),
        idle = if (idleObj == null) null else IdleInfo(
            offsetX = offset?.optDouble("x") ?: 0.0,
            offsetY = offset?.optDouble("y") ?: 0.0,
            excludedPoses = stringSet(exclusions, "excludedCharacterAdditionalPoses"),
            excludedMoments = stringSet(exclusions, "excludedCharacterMoments"),
            excludedPalettes = stringSet(exclusions, "excludedPalettes"),
            excludedVisitors = stringSet(exclusions, "excludedVisitors"),
        ),
        visitor = visitorObj?.let {
            VisitorInfo(it.optBoolean("ignoresSceneOffset"), it.optBoolean("isFullscreenEffect"))
        },
        phaseKind = phase?.str("kind"),
        phaseStartPose = phase?.str("startCharacterPoseID"),
        phaseEndPose = phase?.str("endCharacterPoseID"),
    )
}

private fun parsePalette(o: JSONObject): PaletteColors {
    return PaletteColors(
        background = colorOf(o.optJSONObject("backgroundColor")) ?: DEFAULT_PALETTE,
        overlay = colorOf(o.optJSONObject("overlayColor")),
        parentIdleIds = strings(o.optJSONArray("parentIdleSceneIDs")),
    )
}

private fun parseSprites(array: JSONArray?): List<Sprite> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val o = array.optJSONObject(index) ?: continue
            val size = o.optJSONArray("assetSize")
            val placement = o.optJSONObject("placement")?.optJSONObject("anchored")
            add(
                Sprite(
                    type = o.optString("spriteType"),
                    baseName = o.str("assetBaseName"),
                    width = size?.optDouble(0) ?: 0.0,
                    height = size?.optDouble(1) ?: 0.0,
                    phase = o.str("phase"),
                    plane = o.str("plane"),
                    alignment = placement?.optString("alignment")?.ifEmpty { null } ?: "center",
                    mediaFiles = strings(o.optJSONArray("mediaFiles")),
                )
            )
        }
    }
}

private fun parseMedia(array: JSONArray?): List<MediaFile> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val o = array.optJSONObject(index) ?: continue
            val name = o.optString("name")
            if (name.isEmpty()) continue
            add(
                MediaFile(
                    name = name,
                    suffix = o.optString("suffix"),
                    durationSeconds = if (o.has("durationSeconds")) o.optDouble("durationSeconds") else null,
                )
            )
        }
    }
}

private fun colorOf(o: JSONObject?): Int? {
    if (o == null || !o.has("red")) return null
    val red = o.optDouble("red").toInt().coerceIn(0, 255)
    val green = o.optDouble("green").toInt().coerceIn(0, 255)
    val blue = o.optDouble("blue").toInt().coerceIn(0, 255)
    val alpha = ((if (o.has("alpha")) o.optDouble("alpha") else 1.0).coerceIn(0.0, 1.0) * 255).toInt()
    return android.graphics.Color.argb(alpha, red, green, blue)
}

private fun strings(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            if (array.opt(index) is String) {
                val value = array.optString(index)
                if (value.isNotEmpty()) add(value)
            }
        }
    }
}

private fun stringSet(o: JSONObject?, key: String): Set<String> = strings(o?.optJSONArray(key)).toSet()

private fun JSONObject.str(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).ifEmpty { null }
}

private fun frameNumber(name: String): Int {
    val stem = name.substringAfterLast('/').substringBeforeLast('.')
    return stem.takeLastWhile { it.isDigit() }.toIntOrNull() ?: 0
}

private fun phaseRank(phase: String?): Int {
    val value = phase?.lowercase() ?: return 1
    return when {
        "intro" in value -> 0
        "outro" in value -> 2
        else -> 1
    }
}

const val DEFAULT_PALETTE = 0xFFFAB85E.toInt()

enum class AnimKind { BASE, EXTRA, MOMENT }

class World(
    val timeOfDay: String,
    val routines: Set<String>,
    val weather: Set<String>,
    val calendar: Set<String>,
    val hourly: Set<String>,
    val moon: Set<String>,
    val fulfilled: Set<String> = emptySet(),
    val excluded: Set<String> = emptySet(),
    val activeCategories: MutableSet<String> = mutableSetOf(),
)

fun worldNow(now: java.util.Calendar = java.util.Calendar.getInstance()): World {
    val month = now.get(java.util.Calendar.MONTH) + 1
    val day = now.get(java.util.Calendar.DAY_OF_MONTH)
    val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
    val minute = now.get(java.util.Calendar.MINUTE)
    val weekday = now.get(java.util.Calendar.DAY_OF_WEEK)
    val ordinal = (day - 1) / 7 + 1
    val events = mutableSetOf<String>()
    if (month == 1 && day == 1) events += "newYearsDay"
    if (month == 2 && day == 14) events += "valentinesDay"
    if (month == 4 && day == 1) events += "aprilFoolsDay"
    if (month == 4 && day == 22) events += "earthDay"
    if (month == 5 && weekday == java.util.Calendar.SUNDAY && ordinal == 2) events += "mothersDay"
    if (month == 6 && weekday == java.util.Calendar.SUNDAY && ordinal == 3) events += "fathersDay"
    if (month == 7 && day == 4) events += "fourthOfJuly"
    if (month == 7 && day == 20) events += "peanutsMoonlanding"
    if (month == 8 && day == 10) events += "peanutsSnoopysBirthday"
    if (month == 8 && day == 19) events += "aviationDay"
    if (month == 10 && day == 2) events += "peanutsFirstComicStrip"
    if (month == 10 && day == 4) events += "peanutsSnoopyDebut"
    if (month == 10 && day == 30) events += "peanutsCharlieBrownDay"
    if (month == 12 && day == 16) events += "peanutsBeethovensBirthday"
    if (month == 10) events += "halloweenSeason"
    if (month == 10 && day == 31) events += "halloween"
    if (month == 12 && day == 24) events += setOf("christmasEve", "christmasSeason")
    if (month == 12 && day == 25) events += setOf("christmas", "christmasSeason")
    if (month == 12 && day == 31) events += "newYearsEve"
    if (month == 11 && weekday == java.util.Calendar.THURSDAY && ordinal == 4) {
        events += setOf("thanksgiving", "thanksgivingSeason", "peanutsCharlieBrownThanksgiving")
    }
    if (month == 11 && day >= 15) events += "thanksgivingSeason"
    if (month == 12 || (month == 1 && day <= 6)) events += "christmasSeason"
    if (month == 3 && day == 20) events += "startOfSpring"
    if (month == 6 && day == 21) events += "startOfSummer"
    if (month == 9 && day == 22) events += "startOfFall"
    if (month == 12 && day == 21) events += "startOfWinter"
    if ((month == 3 && day >= 20) || month == 4 || month == 5 || (month == 6 && day < 21)) events += "spring"
    if ((month == 6 && day >= 21) || month == 7 || month == 8 || (month == 9 && day < 22)) events += "summer"
    if ((month == 9 && day >= 22) || month == 10 || month == 11 || (month == 12 && day < 21)) events += "fall"
    if ((month == 12 && day >= 21) || month == 1 || month == 2 || (month == 3 && day < 20)) events += "winter"
    val lunarNewYear = mapOf(
        2024 to (2 to 10), 2025 to (1 to 29), 2026 to (2 to 17), 2027 to (2 to 6),
        2028 to (1 to 26), 2029 to (2 to 13), 2030 to (2 to 3), 2031 to (1 to 23),
        2032 to (2 to 11), 2033 to (1 to 31), 2034 to (2 to 19),
    )
    val lunar = lunarNewYear[now.get(java.util.Calendar.YEAR)]
    if (lunar != null && month == lunar.first && day == lunar.second) events += "lunarNewYear"
    val routine = when (hour) {
        in 5..9 -> "morning"
        in 10..11 -> "brunch"
        in 12..13 -> "lunch"
        in 14..17 -> "afternoon"
        in 18..20 -> "dinner"
        in 21..22 -> "bedtime"
        else -> "lateNight"
    }
    val routines = mutableSetOf(routine)
    val minuteOfDay = hour * 60 + minute
    if (minuteOfDay in 7 * 60 until 10 * 60 + 30) {
        routines += "goingToSchool"
        routines += "goingToWork"
    }
    val hourly = mutableSetOf<String>()
    if (minute <= 5 || minute >= 55) hourly += "onTheHour"
    if (hour == 0 && minute < 15) hourly += "midnight"
    if (hour == 12 && minute < 15) hourly += "midday"
    val nowMillis = now.timeInMillis
    if (nearEvent(WeatherState.sunriseMillis, nowMillis)) hourly += "sunrise"
    if (nearEvent(WeatherState.sunsetMillis, nowMillis)) hourly += "sunset"
    val timeOfDay = when (hour) {
        in 6..11 -> "morning"
        in 12..17 -> "afternoon"
        in 18..22 -> "evening"
        else -> "lateNight"
    }
    return World(timeOfDay, routines, WeatherState.conditions, events, hourly, setOf(moonCondition()))
}

private fun nearEvent(eventMillis: Long?, nowMillis: Long): Boolean {
    if (eventMillis == null) return false
    return kotlin.math.abs(nowMillis - eventMillis) <= 15 * 60 * 1000
}

fun moonCondition(nowMillis: Long = System.currentTimeMillis()): String {
    val days = (nowMillis - 947_182_440_000L) / 86_400_000.0
    val cycle = 29.53058867
    val mod = ((days % cycle) + cycle) % cycle
    val phase = mod / cycle
    return when (phase) {
        in 0.0625..<0.1875 -> "moonWaxingCrescent"
        in 0.1875..<0.3125 -> "moonFirstQuarter"
        in 0.3125..<0.4375 -> "moonWaxingGibbous"
        in 0.4375..<0.5625 -> "moonFull"
        in 0.5625..<0.6875 -> "moonWaningGibbous"
        in 0.6875..<0.8125 -> "moonLastQuarter"
        in 0.8125..<0.9375 -> "moonWaningCrescent"
        else -> "moonNew"
    }
}

class Relevancy {
    fun score(asset: Asset, world: World): Int? {
        if (!asset.valid) return null
        if (!dependenciesOk(asset, world)) return null
        if (!exclusionsOk(asset, world)) return null
        val relevancy = asset.relevancy ?: return 0
        var total = 0
        val info = relevancy.optJSONArray("info")
        if (info == null || info.length() == 0) {
            return dependencyBoost(asset, world)
        }
        var matched = false
        for (index in 0 until info.length()) {
            val clauses = info.optJSONObject(index) ?: continue
            if (matches(clauses, world)) {
                matched = true
                total = max(total, specificity(clauses))
            }
        }
        if (!matched) return null
        return total + dependencyBoost(asset, world)
    }

    private fun matches(clauses: JSONObject, world: World): Boolean {
        val keys = clauses.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = clauses.opt(key)
            val obj = value as? JSONObject ?: continue
            val fields = obj.keys()
            while (fields.hasNext()) {
                val field = fields.next()
                val expected = tokens(obj.opt(field), field)
                val ok = when (key) {
                    "weather" -> world.weather.union(world.moon).any { it in expected }
                    "timeOfDay" -> world.timeOfDay in expected
                    "routine" -> world.routines.any { it in expected }
                    "calendar" -> world.calendar.any { it in expected }
                    "hourlyEvent" -> world.hourly.any { it in expected }
                    "moonPhase", "moon" -> world.moon.any { it in expected }
                    else -> true
                }
                if (!ok) return false
            }
        }
        return true
    }

    private fun tokens(value: Any?, fallback: String): Set<String> {
        return when (value) {
            null, JSONObject.NULL -> emptySet()
            is String -> setOf(value)
            is JSONArray -> buildSet {
                for (index in 0 until value.length()) addAll(tokens(value.opt(index), fallback))
            }
            is JSONObject -> {
                if (value.length() == 0) return setOf(fallback)
                buildSet {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val nested = tokens(value.opt(key), key)
                        if (nested.isEmpty()) add(key) else addAll(nested)
                    }
                }
            }
            else -> emptySet()
        }
    }

    private fun specificity(clauses: JSONObject): Int {
        var total = 0
        val keys = clauses.keys()
        while (keys.hasNext()) {
            total += when (keys.next()) {
                "calendar" -> 80
                "hourlyEvent" -> 65
                "weather", "moonPhase", "moon" -> 50
                "timeOfDay" -> 30
                "routine" -> 25
                else -> 10
            }
        }
        return total
    }

    private fun dependenciesOk(asset: Asset, world: World): Boolean {
        val values = asset.relevancy?.optJSONArray("dependencies") ?: return true
        for (index in 0 until values.length()) {
            when (val item = values.opt(index)) {
                is String -> if (item !in world.fulfilled) return false
                is JSONObject -> {
                    val category = item.str("category")
                    if (category != null && category !in world.activeCategories) return false
                    val infos = item.optJSONArray("info")
                    if (infos != null && infos.length() > 0 && !anyMatch(infos, world)) return false
                }
            }
        }
        return true
    }

    private fun exclusionsOk(asset: Asset, world: World): Boolean {
        val values = asset.relevancy?.optJSONArray("exclusions") ?: return true
        for (index in 0 until values.length()) {
            when (val item = values.opt(index)) {
                is String -> if (item in world.excluded) return false
                is JSONObject -> {
                    val category = item.str("category")
                    if (category != null && category !in world.activeCategories) continue
                    val infos = item.optJSONArray("info")
                    if (infos != null && infos.length() > 0) {
                        if (anyMatch(infos, world)) return false
                        continue
                    }
                    if (category != null) return false
                }
            }
        }
        return true
    }

    private fun anyMatch(infos: JSONArray, world: World): Boolean {
        for (index in 0 until infos.length()) {
            val clause = infos.optJSONObject(index) ?: continue
            if (matches(clause, world)) return true
        }
        return false
    }

    private fun dependencyBoost(asset: Asset, world: World): Int {
        val values = asset.relevancy?.optJSONArray("dependencies") ?: return 0
        var boost = 0
        for (index in 0 until values.length()) {
            when (val item = values.opt(index)) {
                is String -> if (item in world.fulfilled) boost += 15
                is JSONObject -> {
                    val categoryOk = item.str("category")?.let { it in world.activeCategories } ?: true
                    val infos = item.optJSONArray("info")
                    val infoOk = infos == null || infos.length() == 0 || anyMatch(infos, world)
                    if (categoryOk && infoOk) boost += 15
                }
            }
        }
        return boost
    }
}

class SelectionMemory {
    val recent = HashMap<String, ArrayDeque<String>>()
    val last = HashMap<String, String>()
    val counts = HashMap<String, HashMap<String, Int>>()

    fun recentIds(pool: String, limit: Int): Set<String> = recent[pool].orEmpty().take(limit.coerceAtLeast(0)).toSet()

    fun playCount(id: String, pool: String): Int = counts[pool]?.get(id) ?: 0

    fun record(id: String, pool: String, limit: Int) {
        last[pool] = id
        val queue = recent.getOrPut(pool) { ArrayDeque() }
        queue.remove(id)
        queue.addFirst(id)
        while (queue.size > 20) queue.removeLast()
        while (queue.size > limit.coerceAtLeast(1) && queue.size > 1) {
            if (queue.size <= limit.coerceAtLeast(1)) break
            queue.removeLast()
        }
        val poolCounts = counts.getOrPut(pool) { HashMap() }
        poolCounts[id] = (poolCounts[id] ?: 0) + 1
    }

    fun toJson(): String {
        val root = JSONObject()
        val lastObj = JSONObject()
        last.forEach { (key, value) -> lastObj.put(key, value) }
        val recentObj = JSONObject()
        recent.forEach { (key, queue) -> recentObj.put(key, JSONArray(queue.toList())) }
        val countObj = JSONObject()
        counts.forEach { (pool, map) ->
            val inner = JSONObject()
            map.forEach { (id, count) -> inner.put(id, count) }
            countObj.put(pool, inner)
        }
        root.put("last", lastObj)
        root.put("recent", recentObj)
        root.put("counts", countObj)
        return root.toString()
    }

    companion object {
        fun fromJson(text: String?): SelectionMemory {
            val memory = SelectionMemory()
            if (text.isNullOrBlank()) return memory
            try {
                val root = JSONObject(text)
                val lastObj = root.optJSONObject("last")
                if (lastObj != null) {
                    val keys = lastObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        memory.last[key] = lastObj.optString(key)
                    }
                }
                val recentObj = root.optJSONObject("recent")
                if (recentObj != null) {
                    val keys = recentObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val queue = ArrayDeque<String>()
                        val array = recentObj.optJSONArray(key)
                        if (array != null) for (index in 0 until array.length()) queue.add(array.optString(index))
                        memory.recent[key] = queue
                    }
                }
                val countObj = root.optJSONObject("counts")
                if (countObj != null) {
                    val pools = countObj.keys()
                    while (pools.hasNext()) {
                        val pool = pools.next()
                        val inner = countObj.optJSONObject(pool) ?: continue
                        val map = HashMap<String, Int>()
                        val ids = inner.keys()
                        while (ids.hasNext()) {
                            val id = ids.next()
                            map[id] = inner.optInt(id)
                        }
                        memory.counts[pool] = map
                    }
                }
            } catch (_: Exception) {
            }
            return memory
        }
    }
}

class SelectionPolicy(private val relevancy: Relevancy = Relevancy()) {
    fun weighted(assets: List<Asset>, world: World, memory: SelectionMemory, pool: String): List<Pair<Asset, Int>> {
        val unique = LinkedHashMap<String, Asset>()
        assets.forEach { unique[it.id] = it }
        var candidates = unique.values.mapNotNull { asset ->
            val relevance = relevancy.score(asset, world) ?: return@mapNotNull null
            asset to relevanceWeight(relevance)
        }
        if (candidates.isEmpty()) {
            candidates = unique.values.filter { !it.hasConditions }.map { it to 1 }
        }
        val minimum = candidates.minOfOrNull { memory.playCount(it.first.id, pool) } ?: 0
        return candidates.map { (asset, weight) ->
            asset to historyWeight(weight, memory.playCount(asset.id, pool), minimum)
        }
    }

    fun relevanceWeight(relevance: Int): Int = when {
        relevance >= 65 -> 4
        relevance >= 25 -> 3
        relevance >= 1 -> 2
        else -> 1
    }

    fun recentLimit(pool: String, count: Int): Int {
        if (count <= 1) return 1
        val desired = when (pool) {
            "activeVideos", "idleScenes" -> 12
            "characterActions", "visitors" -> 10
            "palettes" -> 8
            "basePoses" -> 3
            else -> 5
        }
        return minOf(desired, count - 1)
    }

    private fun historyWeight(relevanceWeight: Int, playCount: Int, minimum: Int): Int {
        val factor = sqrt((max(0, minimum) + 1.0) / (max(0, playCount) + 1.0))
        return max(1, (max(1, relevanceWeight) * factor * 100).roundToInt())
    }
}

class SessionBag {
    private val used = HashMap<String, MutableSet<String>>()
    private val last = HashMap<String, String>()
    val durations = HashMap<AnimKind, Double>()
    var requiresBase = false
    val recentPairs = ArrayDeque<String>()

    fun choose(weighted: List<Pair<Asset, Int>>, pool: String, seed: ULong): Asset? {
        if (weighted.isEmpty()) return null
        val unique = LinkedHashMap<String, Pair<Asset, Int>>()
        for (entry in weighted) {
            val current = unique[entry.first.id]
            if (current == null || entry.second > current.second) unique[entry.first.id] = entry
        }
        val usedSet = used.getOrPut(pool) { mutableSetOf() }
        var candidates = unique.values.filter { it.first.id !in usedSet }
        if (candidates.isEmpty()) {
            usedSet.clear()
            candidates = unique.values.toList()
        }
        val previous = last[pool]
        if (previous != null && candidates.size > 1) candidates = candidates.filter { it.first.id != previous }
        if (candidates.isEmpty()) return null
        val sorted = candidates.sortedBy { it.first.id }
        val total = sorted.sumOf { it.second }
        if (total <= 0) return null
        var ticket = (mix(seed xor sorted.size.toULong()) % total.toULong()).toInt()
        var selected = sorted.last()
        for (entry in sorted) {
            if (ticket < entry.second) {
                selected = entry
                break
            }
            ticket -= entry.second
        }
        usedSet += selected.first.id
        last[pool] = selected.first.id
        return selected.first
    }

    fun ratio(kind: AnimKind): Double {
        val total = durations.values.sum()
        if (total <= 0.0) return 0.0
        return (durations[kind] ?: 0.0) / total
    }

    fun record(kind: AnimKind, seconds: Double) {
        durations[kind] = (durations[kind] ?: 0.0) + max(0.0, seconds)
        requiresBase = kind != AnimKind.BASE
    }

    fun resetMix() {
        durations.clear()
        requiresBase = false
    }

    fun nextKind(available: Set<AnimKind>, seed: ULong): AnimKind? {
        if (available.isEmpty()) return null
        if (requiresBase && AnimKind.BASE in available) return AnimKind.BASE
        if (durations.values.sum() == 0.0 && AnimKind.BASE in available) return AnimKind.BASE
        val ordered = AnimKind.entries.filter { it in available }
        val deficits = ordered.map { max(0.0, TARGETS.getValue(it) - ratio(it)) }
        val totalDeficit = deficits.sum()
        val weights = if (totalDeficit > 0) deficits else ordered.map { TARGETS.getValue(it) }
        val total = weights.sum()
        if (total <= 0) return ordered.first()
        val unit = (mix(seed) % 1_000_000u).toDouble() / 1_000_000.0
        val threshold = unit * total
        var accumulated = 0.0
        for ((kind, weight) in ordered.zip(weights)) {
            accumulated += weight
            if (threshold < accumulated) return kind
        }
        return ordered.last()
    }

    fun rememberPair(id: String) {
        recentPairs.remove(id)
        recentPairs.addFirst(id)
        while (recentPairs.size > 3) recentPairs.removeLast()
    }

    companion object {
        val TARGETS = mapOf(AnimKind.BASE to 0.71, AnimKind.EXTRA to 0.20, AnimKind.MOMENT to 0.09)
    }
}

class Graph(catalog: Catalog) {
    val byId = catalog.byId

    fun poseTransition(from: String, to: String): Asset? = byId.values.firstOrNull {
        it.kind == "characterPoseTransition" && it.startPose == from && it.endPose == to
    }

    fun reactionEnter(from: String): Asset? = byId.values.firstOrNull {
        it.kind == "characterReactionTransitionPose" && it.phaseKind == "enter" && it.phaseStartPose == from
    }

    fun reactionExit(to: String): Asset? = byId.values.firstOrNull {
        it.kind == "characterReactionTransitionPose" && it.phaseKind == "exit" && it.phaseEndPose == to
    }

    fun actions(kind: String, idle: Asset): List<Asset> {
        val excluded = when (kind) {
            "characterAdditionalPose" -> idle.idle?.excludedPoses.orEmpty()
            "characterMoment" -> idle.idle?.excludedMoments.orEmpty()
            else -> emptySet()
        }
        return byId.values.filter { asset ->
            asset.kind == kind && asset.id !in excluded &&
                (asset.parentIdleIds.isEmpty() || idle.id in asset.parentIdleIds)
        }
    }

    fun visitors(idle: Asset): List<Asset> {
        val excluded = idle.idle?.excludedVisitors.orEmpty()
        return byId.values.filter { it.kind == "idleSceneVisitor" && it.id !in excluded }
    }

    fun actionSequence(currentPose: String, target: Asset): List<Asset>? {
        if (target.kind != "characterAdditionalPose" && target.kind != "characterMoment") return null
        val start = target.startPose ?: return null
        val end = target.endPose ?: return null
        val base = byId[end] ?: return null
        if (base.kind != "characterBasePose") return null
        val sequence = mutableListOf<Asset>()
        if (start != currentPose) sequence += poseTransition(currentPose, start) ?: return null
        sequence += target
        sequence += base
        return sequence
    }
}

data class TransitionPick(
    val categoryId: String,
    val pairId: String,
    val hideId: String?,
    val revealId: String?,
    val hidePoses: List<String>,
    val revealPoses: List<String>,
    val preventsIdleChange: Boolean,
) {
    val family: String get() = (hideId ?: "none") + "|" + (revealId ?: "none")
}

fun mix(value: ULong): ULong {
    var mixed = value + 0x9E3779B97F4A7C15u
    mixed = (mixed xor (mixed shr 30)) * 0xBF58476D1CE4E5B9u
    mixed = (mixed xor (mixed shr 27)) * 0x94D049BB133111EBu
    return mixed xor (mixed shr 31)
}

fun basePoseLoops(seed: ULong): Int = 6 + (mix(seed xor 0x42415345504F5345u) % 5u).toInt()
fun extraPoseLoops(seed: ULong): Int = 2 + (mix(seed xor 0x414444504F5345u) % 3u).toInt()
fun sustainedPoseLoops(seed: ULong): Int = 4 + (mix(seed xor 0x5355535441494Eu) % 4u).toInt()
fun visitorSchedule(seed: ULong, duration: Double = 240.0): List<Double> {
    val count = 3
    val lower = 10.0
    val upper = duration - 10.0
    val slot = (upper - lower) / count
    val wiggle = max(0.0, slot - 45.0)
    return (0 until count).map { index ->
        val unit = (mix(seed + index.toULong()) % 1_000_000u).toDouble() / 1_000_000.0
        lower + index * slot + unit * wiggle
    }
}
