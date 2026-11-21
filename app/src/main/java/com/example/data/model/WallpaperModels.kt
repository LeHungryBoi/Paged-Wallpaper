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

enum class OffsetEasingStyle(val displayName: String) {
    SYMMETRIC("Symmetric ease in/out"),
    FAST_START_SLOW_END("Fast start, slow end");

    companion object {
        fun fromName(name: String): OffsetEasingStyle =
            values().firstOrNull { it.name == name } ?: SYMMETRIC
    }
}

enum class CropFillMode(val displayName: String, val description: String) {
    CROP("Crop to fill", "Fill the screen by cropping the image edges"),
    BLUR("Blurred fill", "Keep the whole image over a softly blurred background"),
    GLASS("Frosted fill", "Keep the whole image over a stylized glass backdrop");

    companion object {
        fun fromName(name: String): CropFillMode =
            values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: BLUR
    }
}

enum class BlurStyle(
    val displayName: String,
    val shortLabel: String,
    val description: String
) {
    SMOOTH_GAUSSIAN(
        "Smooth Gaussian",
        "Gaussian",
        "Pure, silky large-radius optical blur with natural color fidelity"
    ),
    WIN7_AERO(
        "Win7 Aero Glass",
        "Win7 Aero",
        "Classic translucent glass with diagonal specular reflections and icy sheen"
    ),
    MODERN_ACRYLIC(
        "Modern Acrylic",
        "Acrylic",
        "Vibrant frosted material with subtle micro-grain noise and soft veil"
    ),
    SILKY_VIGNETTE(
        "Silky Vignette",
        "Vignette",
        "Luminous soft-blur center with velvety darkened edge falloff"
    ),
    BOKEH_BLOOM(
        "Bokeh Bloom",
        "Bokeh",
        "Creamy optical blur with glowing highlight bloom"
    ),
    PRISM_REFRACTION(
        "Prism Refraction",
        "Prism",
        "Subtle chromatic glass dispersion with soft spectral shimmer"
    );

    companion object {
        fun fromName(name: String, fallback: BlurStyle = SMOOTH_GAUSSIAN): BlurStyle =
            values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: fallback
    }
}

enum class BlurTintMode(val displayName: String) {
    NONE("Natural"),
    SOLID("Single color"),
    RAINBOW("Rainbow");

    companion object {
        fun fromName(name: String): BlurTintMode =
            values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NONE
    }
}

enum class BlurRainbowStyle(val displayName: String) {
    DIAGONAL_PRISM("Diagonal Prism"),
    VERTICAL_AURA("Vertical Aura"),
    RADIAL_IRIDESCENCE("Radial Glow"),
    NEON_SUNSET("Aurora Wave");

    companion object {
        fun fromName(name: String): BlurRainbowStyle =
            values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DIAGONAL_PRISM
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
    val blurStyle: BlurStyle = BlurStyle.SMOOTH_GAUSSIAN,
    val blurTintMode: BlurTintMode = BlurTintMode.NONE,
    val blurTintColor: Int = DEFAULT_BLUR_TINT_COLOR,
    val blurTintIntensity: Float = 0.42f,
    val blurRainbowStyle: BlurRainbowStyle = BlurRainbowStyle.DIAGONAL_PRISM,
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
        put("blurStyle", blurStyle.name)
        put("blurTintMode", blurTintMode.name)
        put("blurTintColor", blurTintColor)
        put("blurTintIntensity", blurTintIntensity.toDouble())
        put("blurRainbowStyle", blurRainbowStyle.name)
        put("cropPanX", cropPanX.toDouble())
        put("cropPanY", cropPanY.toDouble())
        put("cropScale", cropScale.toDouble())
        put("parallaxAmount", parallaxAmount.toDouble())
        put("isBlank", isBlank)
    }

    companion object {
        const val DEFAULT_BLUR_TINT_COLOR = 0xFF38BDF8.toInt()

        fun fromJson(json: JSONObject): PageItem {
            val fillMode = CropFillMode.fromName(
                json.optString("cropFillMode", CropFillMode.BLUR.name)
            )
            val fallbackBlurStyle = if (fillMode == CropFillMode.GLASS) {
                BlurStyle.WIN7_AERO
            } else {
                BlurStyle.SMOOTH_GAUSSIAN
            }
            return PageItem(
                pageIndex = json.optInt("pageIndex", 0),
                originalId = json.optString("originalId").takeIf { it.isNotEmpty() },
                croppedFileName = json.optString("croppedFileName").takeIf { it.isNotEmpty() },
                isAlreadyDark = json.optBoolean("isAlreadyDark", false),
                customLabel = json.optString("customLabel", ""),
                cropFillMode = fillMode,
                blurAmount = json.optDouble("blurAmount", 0.65).toFloat().coerceIn(0f, 1f),
                blurStyle = BlurStyle.fromName(
                    json.optString("blurStyle", ""),
                    fallback = fallbackBlurStyle
                ),
                blurTintMode = BlurTintMode.fromName(
                    json.optString("blurTintMode", BlurTintMode.NONE.name)
                ),
                blurTintColor = json.optInt("blurTintColor", DEFAULT_BLUR_TINT_COLOR),
                blurTintIntensity = json.optDouble("blurTintIntensity", 0.42).toFloat().coerceIn(0f, 1f),
                blurRainbowStyle = BlurRainbowStyle.fromName(
                    json.optString("blurRainbowStyle", BlurRainbowStyle.DIAGONAL_PRISM.name)
                ),
                cropPanX = json.optDouble("cropPanX", 0.0).toFloat().coerceIn(-1f, 1f),
                cropPanY = json.optDouble("cropPanY", 0.0).toFloat().coerceIn(-1f, 1f),
                cropScale = json.optDouble("cropScale", 1.0).toFloat().coerceIn(1f, 3f),
                parallaxAmount = json.optDouble("parallaxAmount", 0.0).toFloat().coerceIn(0f, 1f),
                isBlank = json.optBoolean("isBlank", false)
            )
        }
    }
}

data class RevealMotionRates(
    val outgoingRate: Float = 0f,
    val incomingRate: Float = 0f
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("outgoingRate", outgoingRate.coerceIn(0f, 1f).toDouble())
        put("incomingRate", incomingRate.coerceIn(0f, 1f).toDouble())
    }

    companion object {
        fun fromJson(
            json: JSONObject?,
            legacyPercent: Double = 0.0
        ): RevealMotionRates {
            val fallback = legacyPercent.toFloat().coerceIn(0f, 1f)
            return if (json != null) {
                RevealMotionRates(
                    outgoingRate = json.optDouble("outgoingRate", fallback.toDouble())
                        .toFloat()
                        .coerceIn(0f, 1f),
                    incomingRate = json.optDouble("incomingRate", fallback.toDouble())
                        .toFloat()
                        .coerceIn(0f, 1f)
                )
            } else {
                RevealMotionRates(outgoingRate = fallback, incomingRate = fallback)
            }
        }
    }
}

data class ParallaxMotionSettings(
    val slowImageMotionEnabled: Boolean = false,
    val outgoingMotionRate: Float = 1f,
    val incomingMotionRate: Float = 1f
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("slowImageMotionEnabled", slowImageMotionEnabled)
        put("outgoingMotionRate", outgoingMotionRate.coerceIn(0f, 1f).toDouble())
        put("incomingMotionRate", incomingMotionRate.coerceIn(0f, 1f).toDouble())
    }

    companion object {
        fun fromJson(json: JSONObject?): ParallaxMotionSettings =
            json?.let {
                val slowEnabled = it.optBoolean("slowImageMotionEnabled", false)
                val hasMotionRate = it.has("outgoingMotionRate") || it.has("incomingMotionRate")
                val hasPrevRevealRate =
                    it.has("outgoingRevealMotionRate") || it.has("incomingRevealMotionRate")
                val hasLegacySpeed =
                    it.has("outgoingImageSpeed") || it.has("incomingImageSpeed") || it.has("imageSpeed")

                val defaultOutgoing = when {
                    hasMotionRate -> it.optDouble("outgoingMotionRate", 1.0)
                    hasPrevRevealRate -> it.optDouble("outgoingRevealMotionRate", 1.0)
                    slowEnabled && hasLegacySpeed -> {
                        val speed = it.optDouble(
                            "outgoingImageSpeed",
                            it.optDouble("imageSpeed", 0.5)
                        ).toFloat().coerceIn(0f, 1f)
                        (1f - speed).toDouble()
                    }
                    else -> 1.0
                }
                val defaultIncoming = when {
                    hasMotionRate -> it.optDouble("incomingMotionRate", 1.0)
                    hasPrevRevealRate -> it.optDouble("incomingRevealMotionRate", 1.0)
                    slowEnabled && hasLegacySpeed -> {
                        val speed = it.optDouble(
                            "incomingImageSpeed",
                            it.optDouble("imageSpeed", 0.5)
                        ).toFloat().coerceIn(0f, 1f)
                        (1f - speed).toDouble()
                    }
                    else -> 1.0
                }
                ParallaxMotionSettings(
                    slowImageMotionEnabled = slowEnabled,
                    outgoingMotionRate = defaultOutgoing.toFloat().coerceIn(0f, 1f),
                    incomingMotionRate = defaultIncoming.toFloat().coerceIn(0f, 1f)
                )
            } ?: ParallaxMotionSettings()
    }
}

data class PageTransitionSetting(
    val universalTransitionId: String = "scroll",
    val leftTransitionId: String = universalTransitionId,
    val rightTransitionId: String = universalTransitionId,
    val separateDirections: Boolean = false,
    val universalParallaxMotion: ParallaxMotionSettings = ParallaxMotionSettings(),
    val leftParallaxMotion: ParallaxMotionSettings = ParallaxMotionSettings(),
    val rightParallaxMotion: ParallaxMotionSettings = ParallaxMotionSettings(),
    val universalRevealMotionRates: RevealMotionRates = RevealMotionRates(),
    val leftRevealMotionRates: RevealMotionRates = universalRevealMotionRates,
    val rightRevealMotionRates: RevealMotionRates = universalRevealMotionRates
) {
    fun transitionId(forward: Boolean): String =
        if (!separateDirections) universalTransitionId
        else if (forward) rightTransitionId else leftTransitionId

    fun parallaxMotion(forward: Boolean): ParallaxMotionSettings =
        if (!separateDirections) universalParallaxMotion
        else if (forward) rightParallaxMotion else leftParallaxMotion

    fun revealMotionRates(forward: Boolean): RevealMotionRates =
        (if (!separateDirections) universalRevealMotionRates
        else if (forward) rightRevealMotionRates else leftRevealMotionRates).let {
            RevealMotionRates(
                outgoingRate = it.outgoingRate.coerceIn(0f, 1f),
                incomingRate = it.incomingRate.coerceIn(0f, 1f)
            )
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("universalTransitionId", universalTransitionId)
        put("leftTransitionId", leftTransitionId)
        put("rightTransitionId", rightTransitionId)
        put("separateDirections", separateDirections)
        put("universalParallaxMotion", universalParallaxMotion.toJson())
        put("leftParallaxMotion", leftParallaxMotion.toJson())
        put("rightParallaxMotion", rightParallaxMotion.toJson())
        put("universalRevealMotionRates", universalRevealMotionRates.toJson())
        put("leftRevealMotionRates", leftRevealMotionRates.toJson())
        put("rightRevealMotionRates", rightRevealMotionRates.toJson())
    }

    companion object {
        fun fromJson(json: JSONObject): PageTransitionSetting {
            val legacyForward = json.optString(
                "forwardTransitionId",
                json.optString("transitionId", "scroll")
            )
            val legacyReverse = json.optString("reverseTransitionId", legacyForward)
            val legacyUniversalReveal = json.optDouble("universalRevealImageMotionPercent", 0.0)
            val legacyLeftReveal = json.optDouble(
                "leftRevealImageMotionPercent",
                legacyUniversalReveal
            )
            val legacyRightReveal = json.optDouble(
                "rightRevealImageMotionPercent",
                legacyUniversalReveal
            )
            val universalRates = RevealMotionRates.fromJson(
                json.optJSONObject("universalRevealMotionRates"),
                legacyUniversalReveal
            )
            val leftRates = if (json.has("leftRevealMotionRates")) {
                RevealMotionRates.fromJson(
                    json.optJSONObject("leftRevealMotionRates"),
                    legacyLeftReveal
                )
            } else if (json.has("leftRevealImageMotionPercent")) {
                RevealMotionRates.fromJson(null, legacyLeftReveal)
            } else {
                universalRates
            }
            val rightRates = if (json.has("rightRevealMotionRates")) {
                RevealMotionRates.fromJson(
                    json.optJSONObject("rightRevealMotionRates"),
                    legacyRightReveal
                )
            } else if (json.has("rightRevealImageMotionPercent")) {
                RevealMotionRates.fromJson(null, legacyRightReveal)
            } else {
                universalRates
            }
            return PageTransitionSetting(
                universalTransitionId = json.optString("universalTransitionId", legacyForward),
                leftTransitionId = json.optString("leftTransitionId", legacyReverse),
                rightTransitionId = json.optString("rightTransitionId", legacyForward),
                separateDirections = json.optBoolean("separateDirections", false),
                universalParallaxMotion = ParallaxMotionSettings.fromJson(
                    json.optJSONObject("universalParallaxMotion")
                ),
                leftParallaxMotion = ParallaxMotionSettings.fromJson(
                    json.optJSONObject("leftParallaxMotion")
                ),
                rightParallaxMotion = ParallaxMotionSettings.fromJson(
                    json.optJSONObject("rightParallaxMotion")
                ),
                universalRevealMotionRates = universalRates,
                leftRevealMotionRates = leftRates,
                rightRevealMotionRates = rightRates
            )
        }
    }
}

private fun parsePageItems(
    jsonPages: JSONArray?
): Pair<List<PageItem>, List<ParallaxMotionSettings?>> {
    val pages = mutableListOf<PageItem>()
    val legacyMotionSettings = mutableListOf<ParallaxMotionSettings?>()
    jsonPages?.let { values ->
        for (index in 0 until values.length()) {
            val pageJson = values.optJSONObject(index) ?: continue
            pages.add(PageItem.fromJson(pageJson))
            legacyMotionSettings.add(
                if (pageJson.optBoolean("slowParallax", false)) {
                    val legacySpeed = pageJson.optDouble("parallaxImageSpeed", 0.5)
                        .toFloat()
                        .coerceIn(0f, 1f)
                    val migratedRate = (1f - legacySpeed).coerceIn(0f, 1f)
                    ParallaxMotionSettings(
                        slowImageMotionEnabled = true,
                        outgoingMotionRate = migratedRate,
                        incomingMotionRate = migratedRate
                    )
                } else {
                    null
                }
            )
        }
    }
    return pages to legacyMotionSettings
}

private fun migrateLegacyParallaxSettings(
    transitions: List<PageTransitionSetting>,
    legacyMotionSettings: List<ParallaxMotionSettings?>
): List<PageTransitionSetting> =
    transitions.mapIndexed { boundaryIndex, setting ->
        val configuredLegacyMotions = listOfNotNull(
            legacyMotionSettings.getOrNull(boundaryIndex),
            legacyMotionSettings.getOrNull(boundaryIndex + 1)
        ).filter { it.slowImageMotionEnabled }
        if (configuredLegacyMotions.isEmpty()) {
            setting
        } else {
            val motion = ParallaxMotionSettings(
                slowImageMotionEnabled = true,
                outgoingMotionRate = configuredLegacyMotions.map { it.outgoingMotionRate }.average().toFloat(),
                incomingMotionRate = configuredLegacyMotions.map { it.incomingMotionRate }.average().toFloat()
            )
            setting.copy(
                universalParallaxMotion = motion,
                leftParallaxMotion = motion,
                rightParallaxMotion = motion
            )
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
            val (pages, legacyMotionSettings) = parsePageItems(json.optJSONArray("pages"))
            val transitions = mutableListOf<PageTransitionSetting>()
            json.optJSONArray("transitions")?.let { values ->
                for (i in 0 until values.length()) {
                    values.optJSONObject(i)?.let { transitions.add(PageTransitionSetting.fromJson(it)) }
                }
            }
            val defaultTransitions = List((pages.size - 1).coerceAtLeast(0)) {
                PageTransitionSetting()
            }
            val transitionsToMigrate = if (
                transitions.isEmpty() && legacyMotionSettings.any { it?.slowImageMotionEnabled == true }
            ) {
                defaultTransitions
            } else {
                transitions
            }
            return WallpaperPreset(
                id = json.optString("id", "preset"),
                name = json.optString("name", "Preset"),
                pages = pages,
                transitions = migrateLegacyParallaxSettings(
                    transitionsToMigrate,
                    legacyMotionSettings
                )
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
    val showFrameDebugIndicator: Boolean = false,
    val motionBlurEnabled: Boolean = true,
    val motionBlurStrengthPercent: Int = DEFAULT_MOTION_BLUR_STRENGTH_PERCENT,
    val offsetEasingEnabled: Boolean = false,
    val offsetEasingStyle: OffsetEasingStyle = OffsetEasingStyle.SYMMETRIC,
    val offsetEasingLinearZonePercent: Int = 60,
    val offsetEasingStrength: Int = 50,
    val animationFrameRate: Int = DEFAULT_ANIMATION_FRAME_RATE,
    val presets: List<WallpaperPreset> = listOf(
        WallpaperPreset("default", "Default", pages, List((pages.size - 1).coerceAtLeast(0)) { PageTransitionSetting(transitionType, transitionType) })
    ),
    val activePresetId: String = presets.firstOrNull()?.id ?: "default",
    val transitions: List<PageTransitionSetting> = List(
        (pages.size - 1).coerceAtLeast(0)
    ) { PageTransitionSetting(transitionType, transitionType) },
    val presetNeedsSave: Boolean = false,
    val schemaVersion: Int = 5
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
        put("showFrameDebugIndicator", showFrameDebugIndicator)
        put("motionBlurEnabled", motionBlurEnabled)
        put("motionBlurStrengthPercent", motionBlurStrengthPercent)
        put("offsetEasingEnabled", offsetEasingEnabled)
        put("offsetEasingStyle", offsetEasingStyle.name)
        put("offsetEasingLinearZonePercent", offsetEasingLinearZonePercent)
        put("offsetEasingStrength", offsetEasingStrength)
        put("animationFrameRate", animationFrameRate)
        put("schemaVersion", 5)
        put("activePresetId", activePresetId)
        put("presetNeedsSave", presetNeedsSave)
        put("transitions", JSONArray().apply { transitions.forEach { put(it.toJson()) } })
        put("presets", JSONArray().apply { presets.forEach { put(it.toJson()) } })
    }

    companion object {
        const val DEFAULT_ANIMATION_FRAME_RATE = 60
        const val DEFAULT_MOTION_BLUR_STRENGTH_PERCENT = 100
        const val MAX_MOTION_BLUR_STRENGTH_PERCENT = 200
        val supportedAnimationFrameRates = listOf(60, 90, 120, 144, 165)

        fun fromJson(json: JSONObject): WallpaperAppState {
            val (pagesList, legacyMotionSettings) = parsePageItems(json.optJSONArray("pages"))
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
            val migratedTransitions = migrateLegacyParallaxSettings(
                transitions.ifEmpty { defaultTransitions },
                legacyMotionSettings
            )
            val activeId = json.optString("activePresetId", "default")
            val animationFrameRate = json.optInt(
                "animationFrameRate",
                DEFAULT_ANIMATION_FRAME_RATE
            ).takeIf { it in supportedAnimationFrameRates } ?: DEFAULT_ANIMATION_FRAME_RATE
            val presets = if (presetValues.isEmpty()) {
                listOf(WallpaperPreset("default", "Default", finalPages, migratedTransitions))
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
                showFrameDebugIndicator = json.optBoolean("showFrameDebugIndicator", false),
                motionBlurEnabled = json.optBoolean("motionBlurEnabled", true),
                motionBlurStrengthPercent = json.optInt(
                    "motionBlurStrengthPercent",
                    DEFAULT_MOTION_BLUR_STRENGTH_PERCENT
                ).coerceIn(0, MAX_MOTION_BLUR_STRENGTH_PERCENT),
                offsetEasingEnabled = json.optBoolean("offsetEasingEnabled", false),
                offsetEasingStyle = OffsetEasingStyle.fromName(
                    json.optString("offsetEasingStyle", OffsetEasingStyle.SYMMETRIC.name)
                ),
                offsetEasingLinearZonePercent = json.optInt(
                    "offsetEasingLinearZonePercent",
                    60
                ).coerceIn(0, 100),
                offsetEasingStrength = json.optInt("offsetEasingStrength", 50).coerceIn(0, 100),
                animationFrameRate = animationFrameRate,
                presets = presets,
                activePresetId = activeId.takeIf { id -> presets.any { it.id == id } } ?: presets.first().id,
                transitions = migratedTransitions,
                presetNeedsSave = json.optBoolean("presetNeedsSave", false),
                schemaVersion = json.optInt("schemaVersion", 1)
            )
        }
    }
}
