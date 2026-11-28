package com.example.data.migration

import com.example.data.model.CalibrationConfig
import com.example.data.model.PageItem
import com.example.data.model.PageTransitionSetting
import com.example.data.model.ParallaxMotionSettings
import com.example.data.model.RevealMotionRates
import com.example.data.model.WallpaperAppState
import com.example.data.model.WallpaperPreset
import org.junit.Assert.assertEquals
import org.junit.Test

class PresetDataMigrationTest {

    @Test
    fun schemaSixRollbackRestoresFastRevealAndPreservesPageAndParallaxSettings() {
        val pages = listOf(
            PageItem(pageIndex = 0, parallaxAmount = 0.73f, symmetricParallax = true),
            PageItem(pageIndex = 1, parallaxAmount = 0.41f, symmetricParallax = false),
            PageItem(pageIndex = 2, parallaxAmount = 0.88f, symmetricParallax = true)
        )
        val calibration = CalibrationConfig(
            pageCount = 3,
            homePageIndex = 2,
            autoCalibrate = false,
            customOffsets = listOf(0.0f, 0.37f, 1.0f)
        )
        val setting = PageTransitionSetting(
            universalTransitionId = "swipe_reveal_slow",
            leftTransitionId = "swipe_reveal_slow",
            rightTransitionId = "swipe_reveal_slow",
            separateDirections = true,
            universalParallaxMotion = ParallaxMotionSettings(true, 0.31f, 0.82f),
            leftParallaxMotion = ParallaxMotionSettings(false, 0.22f, 0.64f),
            rightParallaxMotion = ParallaxMotionSettings(true, 0.45f, 0.91f),
            universalRevealMotionRates = RevealMotionRates(0.24f, 0.76f),
            leftRevealMotionRates = RevealMotionRates(0.19f, 0.63f),
            rightRevealMotionRates = RevealMotionRates(0.38f, 0.84f)
        )
        val preset = WallpaperPreset(
            id = "active",
            name = "My wide wallpaper",
            pages = pages,
            transitions = listOf(setting, setting)
        )
        val previousState = WallpaperAppState(
            pages = pages,
            calibration = calibration,
            transitionType = "swipe_reveal_slow",
            transitions = listOf(setting, setting),
            presets = listOf(preset),
            activePresetId = preset.id,
            schemaVersion = 6
        )

        val migrated = PresetDataMigration.migrateTransitionStorage(previousState)
        val restoredSetting = setting.copy(
            universalTransitionId = "swipe_reveal",
            leftTransitionId = "swipe_reveal",
            rightTransitionId = "swipe_reveal"
        )

        assertEquals(7, migrated.schemaVersion)
        assertEquals("swipe_reveal", migrated.transitionType)
        assertEquals(pages, migrated.pages)
        assertEquals(calibration, migrated.calibration)
        assertEquals(listOf(restoredSetting, restoredSetting), migrated.transitions)
        assertEquals(pages, migrated.presets.single().pages)
        assertEquals(listOf(restoredSetting, restoredSetting), migrated.presets.single().transitions)
        assertEquals(0.73f, migrated.pages[0].parallaxAmount)
        assertEquals(2, migrated.calibration.homePageIndex)
        assertEquals(listOf(0.0f, 0.37f, 1.0f), migrated.calibration.customOffsets)
    }

    @Test
    fun schemaFiveDoesNotAutomaticallyOptUsersIntoSlowSwitching() {
        val setting = PageTransitionSetting(universalTransitionId = "swipe_reveal")
        val state = WallpaperAppState(
            pages = listOf(PageItem(pageIndex = 0), PageItem(pageIndex = 1)),
            calibration = CalibrationConfig(pageCount = 2),
            transitionType = "swipe_reveal",
            transitions = listOf(setting),
            schemaVersion = 5
        )

        val migrated = PresetDataMigration.migrateTransitionStorage(state)

        assertEquals("swipe_reveal", migrated.transitionType)
        assertEquals("swipe_reveal", migrated.transitions.single().universalTransitionId)
    }
}
