package com.example.data.migration

import com.example.data.model.CropFillMode
import com.example.data.model.PageItem
import com.example.data.model.PageTransitionSetting
import com.example.data.model.WallpaperAppState
import com.example.data.model.WallpaperPreset
import org.json.JSONObject
import java.util.UUID

object PresetDataMigration {
    const val LEGACY_PRESET_SCHEMA_VERSION = 2
    const val CURRENT_SCHEMA_VERSION = 4

    fun migrateLegacy(
        state: WallpaperAppState,
        legacyMediaMetadata: Map<String, JSONObject>
    ): WallpaperAppState {
        if (state.schemaVersion >= LEGACY_PRESET_SCHEMA_VERSION) return state
        val migratedPages = state.pages.map { page ->
            val legacy = page.originalId?.let(legacyMediaMetadata::get) ?: return@map page
            page.copy(
                isAlreadyDark = legacy.optBoolean("isAlreadyDark", page.isAlreadyDark),
                cropFillMode = CropFillMode.fromName(
                    legacy.optString("cropFillMode", CropFillMode.BLUR.name)
                ),
                blurAmount = legacy.optDouble("blurAmount", 0.65).toFloat().coerceIn(0f, 1f),
                cropPanX = legacy.optDouble("cropPanX", 0.0).toFloat().coerceIn(-1f, 1f),
                cropPanY = legacy.optDouble("cropPanY", 0.0).toFloat().coerceIn(-1f, 1f),
                cropScale = legacy.optDouble("cropScale", 1.0).toFloat().coerceIn(1f, 3f),
                parallaxAmount = legacy.optDouble("parallaxAmount", 0.0).toFloat().coerceIn(0f, 1f)
            )
        }
        val transitionSettings = List((migratedPages.size - 1).coerceAtLeast(0)) {
            PageTransitionSetting(state.transitionType, state.transitionType)
        }
        val initialPreset = WallpaperPreset(
            id = UUID.randomUUID().toString(),
            name = "Migrated preset",
            pages = migratedPages,
            transitions = transitionSettings
        )
        return state.copy(
            pages = migratedPages,
            presets = listOf(initialPreset),
            activePresetId = initialPreset.id,
            transitions = transitionSettings,
            presetNeedsSave = false,
            schemaVersion = LEGACY_PRESET_SCHEMA_VERSION
        )
    }

    fun migrateTransitionStorage(state: WallpaperAppState): WallpaperAppState {
        if (state.schemaVersion >= CURRENT_SCHEMA_VERSION) return state
        return state.copy(schemaVersion = CURRENT_SCHEMA_VERSION)
    }
}
