package com.example.data.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

const val APP_ICON_WINDOW_COUNT = 3
const val APP_ICON_PRESET_NAME_LIMIT = 40
const val APP_ICON_MIN_ZOOM = 0.1f
const val APP_ICON_MAX_ZOOM = 100f
const val APP_ICON_ZOOM_STEP = 1.2f
const val APP_ICON_SCHEMA_VERSION = 1

private const val PAN_LIMIT = 4f

/**
 * Icon-space geometry shared by the editor previews and the exported bitmap.
 * All values are fractions of the icon side, so previews and the 512px export
 * stay pixel-consistent.
 */
object AppIconGeometry {
    const val WINDOW_COUNT = APP_ICON_WINDOW_COUNT
    const val WINDOW_WIDTH = 0.2267f
    const val WINDOW_HEIGHT = 0.66f
    const val WINDOW_CORNER_RADIUS = 0.045f
    const val WINDOW_GAP = 0.06f
    const val EDGE_MARGIN = 0.10f

    private val TOP_OFFSETS = floatArrayOf(0.24f, 0.165f, 0.09f)

    fun left(index: Int): Float = EDGE_MARGIN + index * (WINDOW_WIDTH + WINDOW_GAP)

    fun top(index: Int): Float = TOP_OFFSETS[index.coerceIn(0, WINDOW_COUNT - 1)]

    fun right(index: Int): Float = left(index) + WINDOW_WIDTH

    fun bottom(index: Int): Float = top(index) + WINDOW_HEIGHT
}

enum class AppIconMode {
    SINGLE,
    PER_WINDOW;

    companion object {
        fun fromStorage(value: String?): AppIconMode =
            entries.firstOrNull { it.name == value } ?: SINGLE
    }
}

data class AppIconFrame(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val flipped: Boolean = false
) {
    fun sanitized(): AppIconFrame = copy(
        zoom = if (zoom.isFinite()) zoom.coerceIn(APP_ICON_MIN_ZOOM, APP_ICON_MAX_ZOOM) else 1f,
        panX = if (panX.isFinite()) panX.coerceIn(-PAN_LIMIT, PAN_LIMIT) else 0f,
        panY = if (panY.isFinite()) panY.coerceIn(-PAN_LIMIT, PAN_LIMIT) else 0f
    )

    fun isDefault(): Boolean = zoom == 1f && panX == 0f && panY == 0f && !flipped

    fun withZoom(value: Float): AppIconFrame =
        copy(zoom = if (value.isFinite()) value.coerceIn(APP_ICON_MIN_ZOOM, APP_ICON_MAX_ZOOM) else zoom)

    /**
     * Pinch-zoom + drag update. [centroidX]/[centroidY] are pointer positions
     * relative to the dragged area (0..1), deltas are also normalized, so the
     * same math serves the icon preview and each individual window preview.
     */
    fun transformed(
        centroidX: Float,
        centroidY: Float,
        scaleChange: Float,
        deltaX: Float,
        deltaY: Float
    ): AppIconFrame {
        if (!scaleChange.isFinite() || scaleChange <= 0f || zoom <= 0f) {
            return sanitized()
        }
        val newZoom = (zoom * scaleChange).coerceIn(APP_ICON_MIN_ZOOM, APP_ICON_MAX_ZOOM)
        val appliedScale = newZoom / zoom
        return copy(
            zoom = newZoom,
            panX = (appliedScale * panX +
                (centroidX - 0.5f) * (1f - appliedScale) + deltaX).coerceIn(-PAN_LIMIT, PAN_LIMIT),
            panY = (appliedScale * panY +
                (centroidY - 0.5f) * (1f - appliedScale) + deltaY).coerceIn(-PAN_LIMIT, PAN_LIMIT)
        ).sanitized()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("zoom", zoom.toDouble())
        put("panX", panX.toDouble())
        put("panY", panY.toDouble())
        put("flipped", flipped)
    }

    companion object {
        fun fromJson(json: JSONObject?): AppIconFrame {
            if (json == null) return AppIconFrame()
            return AppIconFrame(
                zoom = json.optDouble("zoom", 1.0).toFloat(),
                panX = json.optDouble("panX", 0.0).toFloat(),
                panY = json.optDouble("panY", 0.0).toFloat(),
                flipped = json.optBoolean("flipped", false)
            ).sanitized()
        }
    }
}

fun defaultAppIconWindowFrames(): List<AppIconFrame> =
    List(APP_ICON_WINDOW_COUNT) { AppIconFrame() }

data class AppIconPreset(
    val id: String,
    val name: String,
    val mode: AppIconMode = AppIconMode.SINGLE,
    val single: AppIconFrame = AppIconFrame(),
    val windows: List<AppIconFrame> = defaultAppIconWindowFrames()
) {
    fun window(index: Int): AppIconFrame =
        windows.getOrNull(index) ?: AppIconFrame()

    fun withWindow(index: Int, frame: AppIconFrame): AppIconPreset {
        if (index !in 0 until APP_ICON_WINDOW_COUNT) return this
        val updated = windows.toMutableList()
        while (updated.size < APP_ICON_WINDOW_COUNT) updated += AppIconFrame()
        updated[index] = frame
        return copy(windows = updated.take(APP_ICON_WINDOW_COUNT))
    }

    fun normalizedWindows(): List<AppIconFrame> =
        List(APP_ICON_WINDOW_COUNT) { window(it) }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("mode", mode.name)
        put("single", single.toJson())
        put("windows", JSONArray().apply { normalizedWindows().forEach { put(it.toJson()) } })
    }

    companion object {
        fun create(name: String, mode: AppIconMode = AppIconMode.SINGLE): AppIconPreset =
            AppIconPreset(
                id = UUID.randomUUID().toString(),
                name = sanitizeName(name, "Icon preset"),
                mode = mode
            )

        fun sanitizeName(value: String, fallback: String): String =
            value.trim().take(APP_ICON_PRESET_NAME_LIMIT).ifBlank { fallback }

        fun fromJson(json: JSONObject): AppIconPreset? {
            val id = json.optString("id").ifBlank { UUID.randomUUID().toString() }
            val name = json.optString("name").ifBlank { "Icon preset" }
            val windowsJson = json.optJSONArray("windows")
            val windows = buildList {
                if (windowsJson != null) {
                    for (index in 0 until windowsJson.length()) {
                        add(AppIconFrame.fromJson(windowsJson.optJSONObject(index)))
                    }
                }
            }
            return AppIconPreset(
                id = id,
                name = name.take(APP_ICON_PRESET_NAME_LIMIT),
                mode = AppIconMode.fromStorage(json.optString("mode")),
                single = AppIconFrame.fromJson(json.optJSONObject("single")),
                windows = windows.ifEmpty { defaultAppIconWindowFrames() }
            )
        }
    }
}

data class AppIconLibraryState(
    val presets: List<AppIconPreset> = emptyList(),
    val activePresetId: String = ""
) {
    val activePreset: AppIconPreset?
        get() = presets.firstOrNull { it.id == activePresetId } ?: presets.firstOrNull()

    fun updatedActive(transform: (AppIconPreset) -> AppIconPreset): AppIconLibraryState {
        val active = activePreset ?: return this
        return copy(
            presets = presets.map { if (it.id == active.id) transform(it) else it },
            activePresetId = active.id
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", APP_ICON_SCHEMA_VERSION)
        put("activePresetId", activePresetId)
        put("presets", JSONArray().apply { presets.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JSONObject?): AppIconLibraryState? {
            val source = json ?: return null
            val presetsJson = source.optJSONArray("presets") ?: return null
            val presets = buildList {
                for (index in 0 until presetsJson.length()) {
                    presetsJson.optJSONObject(index)?.let { AppIconPreset.fromJson(it) }?.let { add(it) }
                }
            }
            if (presets.isEmpty()) return null
            val requestedId = source.optString("activePresetId")
            val activeId = presets.firstOrNull { it.id == requestedId }?.id ?: presets.first().id
            return AppIconLibraryState(presets = presets, activePresetId = activeId)
        }
    }
}
