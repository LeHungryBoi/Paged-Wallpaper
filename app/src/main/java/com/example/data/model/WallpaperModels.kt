package com.example.data.model

import org.json.JSONArray
import org.json.JSONObject

enum class BatterySaverTrigger(val displayName: String, val description: String) {
    BATTERY_PERCENTAGE(
        "Battery percentage",
        "Reduce refresh rate when battery drops below your configured level"
    ),
    SYSTEM_BATTERY_SAVER(
        "System Battery Saver",
        "Follow Android's system Battery Saver toggle"
    ),
    EITHER(
        "Percentage or System Saver",
        "Activate when battery is below threshold or system Battery Saver is on"
    );

    companion object {
        fun fromName(name: String): BatterySaverTrigger =
            values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: BATTERY_PERCENTAGE
    }
}

data class BatterySaverConfig(
    val enabled: Boolean = true,
    val triggerMode: BatterySaverTrigger = BatterySaverTrigger.BATTERY_PERCENTAGE,
    val thresholdPercent: Int = DEFAULT_THRESHOLD_PERCENT,
    val reducedFrameRate: Int = DEFAULT_REDUCED_FRAME_RATE,
    val pauseWhileCharging: Boolean = true
) {
    fun normalized(): BatterySaverConfig = copy(
        thresholdPercent = thresholdPercent.coerceIn(MIN_THRESHOLD_PERCENT, MAX_THRESHOLD_PERCENT),
        reducedFrameRate = reducedFrameRate.takeIf { it in supportedReducedFrameRates }
            ?: DEFAULT_REDUCED_FRAME_RATE
    )

    fun toJson(): JSONObject = JSONObject().apply {
        val safe = normalized()
        put("enabled", safe.enabled)
        put("triggerMode", safe.triggerMode.name)
        put("thresholdPercent", safe.thresholdPercent)
        put("reducedFrameRate", safe.reducedFrameRate)
        put("pauseWhileCharging", safe.pauseWhileCharging)
    }

    companion object {
        const val DEFAULT_THRESHOLD_PERCENT = 20
        const val MIN_THRESHOLD_PERCENT = 5
        const val MAX_THRESHOLD_PERCENT = 50
        const val DEFAULT_REDUCED_FRAME_RATE = 60
        val supportedReducedFrameRates = listOf(30, 60)

        fun fromJson(json: JSONObject): BatterySaverConfig = BatterySaverConfig(
            enabled = json.optBoolean("enabled", true),
            triggerMode = BatterySaverTrigger.fromName(
                json.optString("triggerMode", BatterySaverTrigger.BATTERY_PERCENTAGE.name)
            ),
            thresholdPercent = json.optInt("thresholdPercent", DEFAULT_THRESHOLD_PERCENT)
                .coerceIn(MIN_THRESHOLD_PERCENT, MAX_THRESHOLD_PERCENT),
            reducedFrameRate = json.optInt("reducedFrameRate", DEFAULT_REDUCED_FRAME_RATE)
                .takeIf { it in supportedReducedFrameRates } ?: DEFAULT_REDUCED_FRAME_RATE,
            pauseWhileCharging = json.optBoolean("pauseWhileCharging", true)
        )
    }
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
    val symmetricParallax: Boolean = false,
    val isBlank: Boolean = false,
    val dimFactor: Float = DEFAULT_DIM_FACTOR
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
        put("symmetricParallax", symmetricParallax)
        put("isBlank", isBlank)
        put("dimFactor", dimFactor.coerceIn(MIN_DIM_FACTOR, MAX_DIM_FACTOR).toDouble())
    }

    companion object {
        const val DEFAULT_BLUR_TINT_COLOR = 0xFF38BDF8.toInt()
        const val DEFAULT_DIM_FACTOR = 0.40f
        const val MIN_DIM_FACTOR = 0.10f
        const val MAX_DIM_FACTOR = 0.90f

        fun fromJson(
            json: JSONObject,
            defaultDimFactor: Float = DEFAULT_DIM_FACTOR
        ): PageItem {
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
                symmetricParallax = json.optBoolean("symmetricParallax", false),
                isBlank = json.optBoolean("isBlank", false),
                dimFactor = json.optDouble(
                    "dimFactor",
                    defaultDimFactor.coerceIn(MIN_DIM_FACTOR, MAX_DIM_FACTOR).toDouble()
                ).toFloat().coerceIn(MIN_DIM_FACTOR, MAX_DIM_FACTOR)
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
    jsonPages: JSONArray?,
    defaultDimFactor: Float = PageItem.DEFAULT_DIM_FACTOR
): Pair<List<PageItem>, List<ParallaxMotionSettings?>> {
    val pages = mutableListOf<PageItem>()
    val legacyMotionSettings = mutableListOf<ParallaxMotionSettings?>()
    jsonPages?.let { values ->
        for (index in 0 until values.length()) {
            val pageJson = values.optJSONObject(index) ?: continue
            pages.add(PageItem.fromJson(pageJson, defaultDimFactor))
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
        fun fromJson(
            json: JSONObject,
            defaultDimFactor: Float = PageItem.DEFAULT_DIM_FACTOR
        ): WallpaperPreset {
            val (pages, legacyMotionSettings) = parsePageItems(
                json.optJSONArray("pages"),
                defaultDimFactor = defaultDimFactor
            )
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

enum class DarkenTriggerMode(
    val displayName: String,
    val description: String
) {
    SCHEDULE_ONLY(
        "Scheduled Time Only",
        "Activate during your custom daily schedule"
    ),
    DARK_MODE_ONLY(
        "System Dark Mode Only",
        "Activate when Android Dark Mode is enabled"
    ),
    BOTH_OR(
        "Both (Either Triggers)",
        "Activate when Dark Mode is on or inside your schedule"
    ),
    BATTERY_SAVER_ONLY(
        "Battery Saver Only",
        "Activate when Battery Saver or low-battery threshold is active"
    );

    val usesSchedule: Boolean
        get() = this == SCHEDULE_ONLY || this == BOTH_OR

    val usesDarkMode: Boolean
        get() = this == DARK_MODE_ONLY || this == BOTH_OR

    val usesBatterySaver: Boolean
        get() = this == BATTERY_SAVER_ONLY

    companion object {
        fun fromName(name: String): DarkenTriggerMode =
            values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DARK_MODE_ONLY
    }
}

data class DarkenConfig(
    val enabled: Boolean = false,
    val triggerMode: DarkenTriggerMode = DarkenTriggerMode.DARK_MODE_ONLY,
    val autoDetectDark: Boolean = true,
    val luminanceThreshold: Float = 0.35f,
    val dimFactor: Float = DEFAULT_DIM_FACTOR,
    val scheduleStartHour: Int = 20,
    val scheduleStartMinute: Int = 0,
    val scheduleEndHour: Int = 7,
    val scheduleEndMinute: Int = 0,
    val darkenOnBatterySaver: Boolean = false
) {
    val isEnabled: Boolean
        get() = enabled

    val useDarkMode: Boolean
        get() = enabled && triggerMode.usesDarkMode

    val useSchedule: Boolean
        get() = enabled && triggerMode.usesSchedule

    val useBatterySaver: Boolean
        get() = enabled && (triggerMode.usesBatterySaver || darkenOnBatterySaver)

    fun normalized(): DarkenConfig = copy(
        luminanceThreshold = luminanceThreshold.coerceIn(0.05f, 0.95f),
        dimFactor = dimFactor.coerceIn(MIN_DIM_FACTOR, MAX_DIM_FACTOR),
        scheduleStartHour = scheduleStartHour.coerceIn(0, 23),
        scheduleStartMinute = scheduleStartMinute.coerceIn(0, 59),
        scheduleEndHour = scheduleEndHour.coerceIn(0, 23),
        scheduleEndMinute = scheduleEndMinute.coerceIn(0, 59)
    )

    fun toJson(): JSONObject = JSONObject().apply {
        val safe = normalized()
        put("enabled", safe.enabled)
        put("triggerMode", safe.triggerMode.name)
        put("autoDetectDark", safe.autoDetectDark)
        put("luminanceThreshold", safe.luminanceThreshold.toDouble())
        put("dimFactor", safe.dimFactor.toDouble())
        put("scheduleStartHour", safe.scheduleStartHour)
        put("scheduleStartMinute", safe.scheduleStartMinute)
        put("scheduleEndHour", safe.scheduleEndHour)
        put("scheduleEndMinute", safe.scheduleEndMinute)
        put("darkenOnBatterySaver", safe.darkenOnBatterySaver)
    }

    companion object {
        const val DEFAULT_DIM_FACTOR = PageItem.DEFAULT_DIM_FACTOR
        const val MIN_DIM_FACTOR = PageItem.MIN_DIM_FACTOR
        const val MAX_DIM_FACTOR = PageItem.MAX_DIM_FACTOR

        fun fromJson(json: JSONObject): DarkenConfig {
            val (enabled, triggerMode) = when {
                json.has("enabled") -> {
                    val mode = DarkenTriggerMode.fromName(
                        json.optString("triggerMode", DarkenTriggerMode.DARK_MODE_ONLY.name)
                    )
                    json.optBoolean("enabled", false) to mode
                }
                json.has("useDarkMode") || json.has("useSchedule") -> {
                    val dark = json.optBoolean("useDarkMode", false)
                    val sched = json.optBoolean("useSchedule", false)
                    when {
                        dark && sched -> true to DarkenTriggerMode.BOTH_OR
                        sched -> true to DarkenTriggerMode.SCHEDULE_ONLY
                        dark -> true to DarkenTriggerMode.DARK_MODE_ONLY
                        else -> false to DarkenTriggerMode.DARK_MODE_ONLY
                    }
                }
                else -> {
                    when (json.optString("triggerMode", "").uppercase()) {
                        "DARK_THEME", "DARK_MODE_ONLY" -> true to DarkenTriggerMode.DARK_MODE_ONLY
                        "SCHEDULED", "SCHEDULE_ONLY" -> true to DarkenTriggerMode.SCHEDULE_ONLY
                        "ALWAYS", "BOTH_OR" -> true to DarkenTriggerMode.BOTH_OR
                        else -> false to DarkenTriggerMode.DARK_MODE_ONLY
                    }
                }
            }
            return DarkenConfig(
                enabled = enabled,
                triggerMode = triggerMode,
                autoDetectDark = json.optBoolean("autoDetectDark", true),
                luminanceThreshold = json.optDouble("luminanceThreshold", 0.35).toFloat()
                    .coerceIn(0.05f, 0.95f),
                dimFactor = json.optDouble("dimFactor", DEFAULT_DIM_FACTOR.toDouble()).toFloat()
                    .coerceIn(MIN_DIM_FACTOR, MAX_DIM_FACTOR),
                scheduleStartHour = json.optInt("scheduleStartHour", 20).coerceIn(0, 23),
                scheduleStartMinute = json.optInt("scheduleStartMinute", 0).coerceIn(0, 59),
                scheduleEndHour = json.optInt("scheduleEndHour", 7).coerceIn(0, 23),
                scheduleEndMinute = json.optInt("scheduleEndMinute", 0).coerceIn(0, 59),
                darkenOnBatterySaver = json.optBoolean("darkenOnBatterySaver", false)
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
    val resumeFadeEnabled: Boolean = DEFAULT_ENTRY_ANIMATION_ENABLED,
    val entryZoomEnabled: Boolean = true,
    val entryZoomStartScale: Float = DEFAULT_ENTRY_ZOOM_START_SCALE,
    val entryBlackFadeEnabled: Boolean = true,
    val entryBlackFadeMaxAlpha: Float = DEFAULT_ENTRY_BLACK_FADE_MAX_ALPHA,
    val entryBlurEnabled: Boolean = true,
    val entryBlurStrength: Float = DEFAULT_ENTRY_BLUR_STRENGTH,
    val entryDurationMs: Int = DEFAULT_ENTRY_DURATION_MS,
    val darken: DarkenConfig = DarkenConfig(),
    val backupFolderPath: String? = null,
    val autoBackupEnabled: Boolean = false,
    val showOffsetDebugOverlay: Boolean = false,
    val showFrameDebugIndicator: Boolean = false,
    val showSignalDebugOverlay: Boolean = false,
    val motionBlurEnabled: Boolean = true,
    val motionBlurStrengthPercent: Int = DEFAULT_MOTION_BLUR_STRENGTH_PERCENT,
    val offsetEasingEnabled: Boolean = false,
    val offsetEasingStyle: OffsetEasingStyle = OffsetEasingStyle.SYMMETRIC,
    val offsetEasingLinearZonePercent: Int = 60,
    val offsetEasingStrength: Int = 50,
    val animationFrameRate: Int = DEFAULT_ANIMATION_FRAME_RATE,
    val batterySaver: BatterySaverConfig = BatterySaverConfig(),
    val presets: List<WallpaperPreset> = listOf(
        WallpaperPreset("default", "Default", pages, List((pages.size - 1).coerceAtLeast(0)) { PageTransitionSetting(transitionType, transitionType) })
    ),
    val activePresetId: String = presets.firstOrNull()?.id ?: "default",
    val transitions: List<PageTransitionSetting> = List(
        (pages.size - 1).coerceAtLeast(0)
    ) { PageTransitionSetting(transitionType, transitionType) },
    val presetNeedsSave: Boolean = false,
    val schemaVersion: Int = 7,
    val appLanguage: String = DEFAULT_APP_LANGUAGE
) {
    fun toJson(): JSONObject = JSONObject().apply {
        val pagesArr = JSONArray()
        pages.forEach { pagesArr.put(it.toJson()) }
        put("pages", pagesArr)
        put("calibration", calibration.toJson())
        put("transitionType", transitionType)
        put("resumeFadeEnabled", resumeFadeEnabled)
        put("entryZoomEnabled", entryZoomEnabled)
        put("entryZoomStartScale", entryZoomStartScale.toDouble())
        put("entryBlackFadeEnabled", entryBlackFadeEnabled)
        put("entryBlackFadeMaxAlpha", entryBlackFadeMaxAlpha.toDouble())
        put("entryBlurEnabled", entryBlurEnabled)
        put("entryBlurStrength", entryBlurStrength.toDouble())
        put("entryDurationMs", entryDurationMs)
        put("darken", darken.toJson())
        put("backupFolderPath", backupFolderPath ?: "")
        put("autoBackupEnabled", autoBackupEnabled)
        put("showOffsetDebugOverlay", showOffsetDebugOverlay)
        put("showFrameDebugIndicator", showFrameDebugIndicator)
        put("showSignalDebugOverlay", showSignalDebugOverlay)
        put("motionBlurEnabled", motionBlurEnabled)
        put("motionBlurStrengthPercent", motionBlurStrengthPercent)
        put("offsetEasingEnabled", offsetEasingEnabled)
        put("offsetEasingStyle", offsetEasingStyle.name)
        put("offsetEasingLinearZonePercent", offsetEasingLinearZonePercent)
        put("offsetEasingStrength", offsetEasingStrength)
        put("animationFrameRate", animationFrameRate)
        put("batterySaver", batterySaver.toJson())
        put("schemaVersion", 7)
        put("activePresetId", activePresetId)
        put("presetNeedsSave", presetNeedsSave)
        put("transitions", JSONArray().apply { transitions.forEach { put(it.toJson()) } })
        put("presets", JSONArray().apply { presets.forEach { put(it.toJson()) } })
        put("appLanguage", appLanguage)
    }

    companion object {
        const val DEFAULT_APP_LANGUAGE = "system"
        const val DEFAULT_ANIMATION_FRAME_RATE = 165
        const val DEFAULT_MOTION_BLUR_STRENGTH_PERCENT = 100
        const val MAX_MOTION_BLUR_STRENGTH_PERCENT = 200
        const val DEFAULT_ENTRY_ANIMATION_ENABLED = true
        const val DEFAULT_ENTRY_ZOOM_START_SCALE = 1.35f
        const val MIN_ENTRY_ZOOM_START_SCALE = 1.02f
        const val MAX_ENTRY_ZOOM_START_SCALE = 1.50f
        const val DEFAULT_ENTRY_BLACK_FADE_MAX_ALPHA = 1.0f
        const val DEFAULT_ENTRY_BLUR_STRENGTH = 1.0f
        const val DEFAULT_ENTRY_DURATION_MS = 390
        const val MIN_ENTRY_DURATION_MS = 150
        const val MAX_ENTRY_DURATION_MS = 1200
        val supportedAnimationFrameRates = listOf(60, 90, 120, 144, 165)

        fun fromJson(json: JSONObject): WallpaperAppState {
            val darkenConfig = json.optJSONObject("darken")?.let { DarkenConfig.fromJson(it) }
                ?: DarkenConfig()
            val (pagesList, legacyMotionSettings) = parsePageItems(
                json.optJSONArray("pages"),
                defaultDimFactor = darkenConfig.dimFactor
            )
            val calib = json.optJSONObject("calibration")?.let { CalibrationConfig.fromJson(it) }
                ?: CalibrationConfig()
            val finalPages = if (pagesList.isEmpty()) {
                (0 until calib.pageCount).map { i ->
                    PageItem(
                        pageIndex = i,
                        croppedFileName = "starter_page_${i}.jpg",
                        customLabel = if (i == calib.homePageIndex) "Home" else "Page ${i + 1}",
                        dimFactor = darkenConfig.dimFactor
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
                    values.optJSONObject(i)?.let {
                        presetValues.add(WallpaperPreset.fromJson(it, defaultDimFactor = darkenConfig.dimFactor))
                    }
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
            val rawZoomStartScale = json.optDouble(
                "entryZoomStartScale",
                DEFAULT_ENTRY_ZOOM_START_SCALE.toDouble()
            ).toFloat()
            val normalizedZoomStartScale = if (rawZoomStartScale in 0.4f..0.999f) {
                1f + (1f - rawZoomStartScale)
            } else {
                rawZoomStartScale
            }.coerceIn(MIN_ENTRY_ZOOM_START_SCALE, MAX_ENTRY_ZOOM_START_SCALE)

            return WallpaperAppState(
                pages = finalPages,
                calibration = calib,
                transitionType = transitionType,
                resumeFadeEnabled = json.optBoolean(
                    "resumeFadeEnabled",
                    DEFAULT_ENTRY_ANIMATION_ENABLED
                ),
                entryZoomEnabled = json.optBoolean("entryZoomEnabled", true),
                entryZoomStartScale = normalizedZoomStartScale,
                entryBlackFadeEnabled = json.optBoolean("entryBlackFadeEnabled", true),
                entryBlackFadeMaxAlpha = json.optDouble(
                    "entryBlackFadeMaxAlpha",
                    DEFAULT_ENTRY_BLACK_FADE_MAX_ALPHA.toDouble()
                ).toFloat().coerceIn(0.1f, 1f),
                entryBlurEnabled = json.optBoolean("entryBlurEnabled", true),
                entryBlurStrength = json.optDouble(
                    "entryBlurStrength",
                    DEFAULT_ENTRY_BLUR_STRENGTH.toDouble()
                ).toFloat().coerceIn(0.1f, 1f),
                entryDurationMs = json.optInt(
                    "entryDurationMs",
                    DEFAULT_ENTRY_DURATION_MS
                ).coerceIn(MIN_ENTRY_DURATION_MS, MAX_ENTRY_DURATION_MS),
                darken = darkenConfig,
                backupFolderPath = json.optString("backupFolderPath").takeIf { it.isNotEmpty() },
                autoBackupEnabled = json.optBoolean("autoBackupEnabled", false),
                showOffsetDebugOverlay = json.optBoolean("showOffsetDebugOverlay", false),
                showFrameDebugIndicator = json.optBoolean("showFrameDebugIndicator", false),
                showSignalDebugOverlay = json.optBoolean("showSignalDebugOverlay", false),
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
                batterySaver = json.optJSONObject("batterySaver")?.let { BatterySaverConfig.fromJson(it) }
                    ?: BatterySaverConfig(),
                presets = presets,
                activePresetId = activeId.takeIf { id -> presets.any { it.id == id } } ?: presets.first().id,
                transitions = migratedTransitions,
                presetNeedsSave = json.optBoolean("presetNeedsSave", false),
                schemaVersion = json.optInt("schemaVersion", 1),
                appLanguage = json.optString("appLanguage", DEFAULT_APP_LANGUAGE)
                    .ifEmpty { DEFAULT_APP_LANGUAGE }
            )
        }
    }
}
