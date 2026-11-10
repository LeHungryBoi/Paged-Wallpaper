package com.example.data.model

import org.json.JSONArray
import org.json.JSONObject

enum class DarkenTrigger(val displayName: String, val description: String) {
    OFF("Disabled", "No dimming applied"),
    EXTRA_DIM("Extra Dim (System)", "Dim wallpaper when Android's Reduce Bright Colors / Extra Dim is turned on"),
    DARK_THEME("Dark Theme", "Dim wallpaper when system dark theme is active"),
    SCHEDULED("Scheduled Time", "Dim wallpaper during scheduled night hours"),
    ALWAYS("Always Dim", "Keep dimming filter always active")
}

enum class CropFillMode(val displayName: String, val description: String) {
    CROP("Crop to fill", "Fill the screen by cropping the image edges"),
    BLUR("Blurred fill", "Keep the whole image over a softly blurred background"),
    GLASS("Frosted fill", "Keep the whole image over a tinted, blurred background");

    companion object {
        fun fromName(name: String): CropFillMode =
            values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: BLUR
    }
}

data class PageItem(
    val pageIndex: Int,
    val originalId: String? = null,
    val croppedFileName: String? = null,
    val isAlreadyDark: Boolean = false,
    val customLabel: String = "",
    val cropFillMode: CropFillMode = CropFillMode.BLUR,
    val blurAmount: Float = 0.65f,
    val cropPanX: Float = 0f,
    val cropPanY: Float = 0f,
    val cropScale: Float = 1f,
    val parallaxAmount: Float = 0f,
    val isBlank: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("pageIndex", pageIndex)
        put("originalId", originalId ?: "")
        put("croppedFileName", croppedFileName ?: "")
        put("isAlreadyDark", isAlreadyDark)
        put("customLabel", customLabel)
        put("cropFillMode", cropFillMode.name)
        put("blurAmount", blurAmount.toDouble())
        put("cropPanX", cropPanX.toDouble())
        put("cropPanY", cropPanY.toDouble())
        put("cropScale", cropScale.toDouble())
        put("parallaxAmount", parallaxAmount.toDouble())
        put("isBlank", isBlank)
    }

    companion object {
        fun fromJson(json: JSONObject): PageItem {
            return PageItem(
                pageIndex = json.optInt("pageIndex", 0),
                originalId = json.optString("originalId").takeIf { it.isNotEmpty() },
                croppedFileName = json.optString("croppedFileName").takeIf { it.isNotEmpty() },
                isAlreadyDark = json.optBoolean("isAlreadyDark", false),
                customLabel = json.optString("customLabel", ""),
                cropFillMode = CropFillMode.fromName(json.optString("cropFillMode", CropFillMode.BLUR.name)),
                blurAmount = json.optDouble("blurAmount", 0.65).toFloat().coerceIn(0f, 1f),
                cropPanX = json.optDouble("cropPanX", 0.0).toFloat().coerceIn(-1f, 1f),
                cropPanY = json.optDouble("cropPanY", 0.0).toFloat().coerceIn(-1f, 1f),
                cropScale = json.optDouble("cropScale", 1.0).toFloat().coerceIn(1f, 3f),
                parallaxAmount = json.optDouble("parallaxAmount", 0.0).toFloat().coerceIn(0f, 1f),
                isBlank = json.optBoolean("isBlank", false)
            )
        }
    }
}

data class PageTransitionSetting(
    val universalTransitionId: String = "scroll",
    val leftTransitionId: String = universalTransitionId,
    val rightTransitionId: String = universalTransitionId,
    val separateDirections: Boolean = false
) {
    fun transitionId(forward: Boolean): String =
        if (!separateDirections) universalTransitionId
        else if (forward) rightTransitionId else leftTransitionId

    fun toJson(): JSONObject = JSONObject().apply {
        put("universalTransitionId", universalTransitionId)
        put("leftTransitionId", leftTransitionId)
        put("rightTransitionId", rightTransitionId)
        put("separateDirections", separateDirections)
    }

    companion object {
        fun fromJson(json: JSONObject): PageTransitionSetting {
            val legacyForward = json.optString(
                "forwardTransitionId",
                json.optString("transitionId", "scroll")
            )
            val legacyReverse = json.optString("reverseTransitionId", legacyForward)
            return PageTransitionSetting(
                universalTransitionId = json.optString("universalTransitionId", legacyForward),
                leftTransitionId = json.optString("leftTransitionId", legacyReverse),
                rightTransitionId = json.optString("rightTransitionId", legacyForward),
                separateDirections = json.optBoolean("separateDirections", false)
            )
        }
    }
}

data class WallpaperPreset(
    val id: String,
    val name: String,
    val pages: List<PageItem>,
    val transitions: List<PageTransitionSetting>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("pages", JSONArray().apply { pages.forEach { put(it.toJson()) } })
        put("transitions", JSONArray().apply { transitions.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JSONObject): WallpaperPreset {
            val pages = mutableListOf<PageItem>()
            json.optJSONArray("pages")?.let { values ->
                for (i in 0 until values.length()) {
                    values.optJSONObject(i)?.let { pages.add(PageItem.fromJson(it)) }
                }
            }
            val transitions = mutableListOf<PageTransitionSetting>()
            json.optJSONArray("transitions")?.let { values ->
                for (i in 0 until values.length()) {
                    values.optJSONObject(i)?.let { transitions.add(PageTransitionSetting.fromJson(it)) }
                }
            }
            return WallpaperPreset(
                id = json.optString("id", "preset"),
                name = json.optString("name", "Preset"),
                pages = pages,
                transitions = transitions
            )
        }
    }
}

data class CalibrationConfig(
    val pageCount: Int = 3,
    val homePageIndex: Int = 1,
    val autoCalibrate: Boolean = false,
    val customOffsets: List<Float> = listOf(0.0f, 0.5f, 1.0f)
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("pageCount", pageCount)
        put("homePageIndex", homePageIndex)
        put("autoCalibrate", autoCalibrate)
        val offsetsArray = JSONArray()
        customOffsets.forEach { offsetsArray.put(it.toDouble()) }
        put("customOffsets", offsetsArray)
    }

    /**
     * Calculates the exact xOffset corresponding to the user's designated home page.
     * Uses custom calibrated anchors if present, or uniform distribution as fallback.
     */
    fun getHomeOffset(): Float {
        val count = pageCount.coerceAtLeast(1)
        val validHomeIndex = homePageIndex.coerceIn(0, count - 1)
        return if (customOffsets.size == count && validHomeIndex in customOffsets.indices) {
            customOffsets[validHomeIndex]
        } else if (count <= 1) {
            0.5f
        } else {
            validHomeIndex.toFloat() / (count - 1).toFloat()
        }
    }

    companion object {
        fun fromJson(json: JSONObject): CalibrationConfig {
            val pageCount = json.optInt("pageCount", 3).coerceIn(2, 7)
            val homeIndex = json.optInt("homePageIndex", 1).coerceIn(0, pageCount - 1)
            val autoCalibrate = json.optBoolean("autoCalibrate", false)
            val customOffsets = mutableListOf<Float>()
            json.optJSONArray("customOffsets")?.let { arr ->
                for (i in 0 until arr.length()) {
                    customOffsets.add(arr.optDouble(i, 0.0).toFloat())
                }
            }
            val finalOffsets = if (customOffsets.size == pageCount) {
                customOffsets
            } else {
                (0 until pageCount).map { i ->
                    if (pageCount <= 1) 0f else i.toFloat() / (pageCount - 1).toFloat()
                }
            }

            return CalibrationConfig(
                pageCount = pageCount,
                homePageIndex = homeIndex,
                autoCalibrate = autoCalibrate,
                customOffsets = finalOffsets
            )
        }
    }
}

data class DarkenConfig(
    val triggerMode: DarkenTrigger = DarkenTrigger.EXTRA_DIM,
    val autoDetectDark: Boolean = true,
    val luminanceThreshold: Float = 0.35f,
    val dimFactor: Float = 0.40f,
    val scheduleStartHour: Int = 20,
    val scheduleStartMinute: Int = 0,
    val scheduleEndHour: Int = 7,
    val scheduleEndMinute: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("triggerMode", triggerMode.name)
        put("autoDetectDark", autoDetectDark)
        put("luminanceThreshold", luminanceThreshold.toDouble())
        put("dimFactor", dimFactor.toDouble())
        put("scheduleStartHour", scheduleStartHour)
        put("scheduleStartMinute", scheduleStartMinute)
        put("scheduleEndHour", scheduleEndHour)
        put("scheduleEndMinute", scheduleEndMinute)
    }

    companion object {
        fun fromJson(json: JSONObject): DarkenConfig {
            val trigger = try {
                DarkenTrigger.valueOf(json.optString("triggerMode", DarkenTrigger.EXTRA_DIM.name))
            } catch (e: Exception) {
                DarkenTrigger.EXTRA_DIM
            }
            return DarkenConfig(
                triggerMode = trigger,
                autoDetectDark = json.optBoolean("autoDetectDark", true),
                luminanceThreshold = json.optDouble("luminanceThreshold", 0.35).toFloat(),
                dimFactor = json.optDouble("dimFactor", 0.40).toFloat().coerceIn(0.1f, 0.9f),
                scheduleStartHour = json.optInt("scheduleStartHour", 20).coerceIn(0, 23),
                scheduleStartMinute = json.optInt("scheduleStartMinute", 0).coerceIn(0, 59),
                scheduleEndHour = json.optInt("scheduleEndHour", 7).coerceIn(0, 23),
                scheduleEndMinute = json.optInt("scheduleEndMinute", 0).coerceIn(0, 59)
            )
        }
    }
}

data class OriginalMedia(
    val id: String,
    val fileName: String,
    val dateAdded: Long,
    val width: Int,
    val height: Int,
    val averageLuminance: Float,
    val isDarkDetected: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("fileName", fileName)
        put("dateAdded", dateAdded)
        put("width", width)
        put("height", height)
        put("averageLuminance", averageLuminance.toDouble())
        put("isDarkDetected", isDarkDetected)
    }

    companion object {
        fun fromJson(json: JSONObject): OriginalMedia {
            val isDarkDetected = json.optBoolean("isDarkDetected", false)
            return OriginalMedia(
                id = json.optString("id"),
                fileName = json.optString("fileName"),
                dateAdded = json.optLong("dateAdded", System.currentTimeMillis()),
                width = json.optInt("width", 1080),
                height = json.optInt("height", 2400),
                averageLuminance = json.optDouble("averageLuminance", 0.5).toFloat(),
                isDarkDetected = isDarkDetected
            )
        }
    }
}

data class WallpaperAppState(
    val pages: List<PageItem>,
    val calibration: CalibrationConfig,
    val transitionType: String = "scroll",
    val resumeFadeEnabled: Boolean = false,
    val darken: DarkenConfig = DarkenConfig(),
    val backupFolderPath: String? = null,
    val autoBackupEnabled: Boolean = false,
    val showOffsetDebugOverlay: Boolean = false,
    val presets: List<WallpaperPreset> = listOf(
        WallpaperPreset("default", "Default", pages, List((pages.size - 1).coerceAtLeast(0)) { PageTransitionSetting(transitionType, transitionType) })
    ),
    val activePresetId: String = presets.firstOrNull()?.id ?: "default",
    val transitions: List<PageTransitionSetting> = List(
        (pages.size - 1).coerceAtLeast(0)
    ) { PageTransitionSetting(transitionType, transitionType) },
    val presetNeedsSave: Boolean = false,
    val schemaVersion: Int = 3
) {
    fun toJson(): JSONObject = JSONObject().apply {
        val pagesArr = JSONArray()
        pages.forEach { pagesArr.put(it.toJson()) }
        put("pages", pagesArr)
        put("calibration", calibration.toJson())
        put("transitionType", transitionType)
        put("resumeFadeEnabled", resumeFadeEnabled)
        put("darken", darken.toJson())
        put("backupFolderPath", backupFolderPath ?: "")
        put("autoBackupEnabled", autoBackupEnabled)
        put("showOffsetDebugOverlay", showOffsetDebugOverlay)
        put("schemaVersion", 3)
        put("activePresetId", activePresetId)
        put("presetNeedsSave", presetNeedsSave)
        put("transitions", JSONArray().apply { transitions.forEach { put(it.toJson()) } })
        put("presets", JSONArray().apply { presets.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JSONObject): WallpaperAppState {
            val pagesList = mutableListOf<PageItem>()
            json.optJSONArray("pages")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { pagesList.add(PageItem.fromJson(it)) }
                }
            }
            val calib = json.optJSONObject("calibration")?.let { CalibrationConfig.fromJson(it) }
                ?: CalibrationConfig()
            val finalPages = if (pagesList.isEmpty()) {
                (0 until calib.pageCount).map { i ->
                    PageItem(
                        pageIndex = i,
                        croppedFileName = "starter_page_${i}.jpg",
                        customLabel = if (i == calib.homePageIndex) "Home" else "Page ${i + 1}"
                    )
                }
            } else {
                pagesList
            }

            val transitionType = json.optString("transitionType", "scroll")
            val transitions = mutableListOf<PageTransitionSetting>()
            json.optJSONArray("transitions")?.let { values ->
                for (i in 0 until values.length()) {
                    values.optJSONObject(i)?.let { transitions.add(PageTransitionSetting.fromJson(it)) }
                }
            }
            val presetValues = mutableListOf<WallpaperPreset>()
            json.optJSONArray("presets")?.let { values ->
                for (i in 0 until values.length()) {
                    values.optJSONObject(i)?.let { presetValues.add(WallpaperPreset.fromJson(it)) }
                }
            }
            val defaultTransitions = List((finalPages.size - 1).coerceAtLeast(0)) {
                PageTransitionSetting(transitionType, transitionType)
            }
            val activeId = json.optString("activePresetId", "default")
            val presets = if (presetValues.isEmpty()) {
                listOf(WallpaperPreset("default", "Default", finalPages, defaultTransitions))
            } else {
                presetValues
            }
            return WallpaperAppState(
                pages = finalPages,
                calibration = calib,
                transitionType = transitionType,
                resumeFadeEnabled = json.optBoolean("resumeFadeEnabled", false),
                darken = json.optJSONObject("darken")?.let { DarkenConfig.fromJson(it) }
                    ?: DarkenConfig(),
                backupFolderPath = json.optString("backupFolderPath").takeIf { it.isNotEmpty() },
                autoBackupEnabled = json.optBoolean("autoBackupEnabled", false),
                showOffsetDebugOverlay = json.optBoolean("showOffsetDebugOverlay", false),
                presets = presets,
                activePresetId = activeId.takeIf { id -> presets.any { it.id == id } } ?: presets.first().id,
                transitions = transitions.ifEmpty { defaultTransitions },
                presetNeedsSave = json.optBoolean("presetNeedsSave", false),
                schemaVersion = json.optInt("schemaVersion", 1)
            )
        }
    }
}
