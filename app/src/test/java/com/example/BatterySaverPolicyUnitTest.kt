package com.example

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.BatterySaverConfig
import com.example.data.model.BatterySaverTrigger
import com.example.data.model.CalibrationConfig
import com.example.data.model.WallpaperAppState
import com.example.power.BatterySaverMonitor
import com.example.power.BatterySaverPolicy
import com.example.power.PowerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BatterySaverPolicyUnitTest {

    @Test
    fun batterySaverConfigDefaultsAndSerializationRoundTrip() {
        val state = WallpaperAppState(
            pages = emptyList(),
            calibration = CalibrationConfig(pageCount = 3, homePageIndex = 1),
            animationFrameRate = 165,
            batterySaver = BatterySaverConfig(
                enabled = true,
                triggerMode = BatterySaverTrigger.EITHER,
                thresholdPercent = 15,
                reducedFrameRate = 30,
                pauseWhileCharging = false
            )
        )
        val restored = WallpaperAppState.fromJson(state.toJson())
        assertTrue(restored.batterySaver.enabled)
        assertEquals(BatterySaverTrigger.EITHER, restored.batterySaver.triggerMode)
        assertEquals(15, restored.batterySaver.thresholdPercent)
        assertEquals(30, restored.batterySaver.reducedFrameRate)
        assertFalse(restored.batterySaver.pauseWhileCharging)

        val defaults = WallpaperAppState.fromJson(
            state.toJson().apply { remove("batterySaver") }
        )
        assertTrue(defaults.batterySaver.enabled)
        assertEquals(BatterySaverTrigger.BATTERY_PERCENTAGE, defaults.batterySaver.triggerMode)
        assertEquals(20, defaults.batterySaver.thresholdPercent)
        assertEquals(60, defaults.batterySaver.reducedFrameRate)
        assertTrue(defaults.batterySaver.pauseWhileCharging)
    }

    @Test
    fun batterySaverPolicyEvaluatesThresholdSystemSaverAndCharging() {
        val thresholdConfig = BatterySaverConfig(
            enabled = true,
            triggerMode = BatterySaverTrigger.BATTERY_PERCENTAGE,
            thresholdPercent = 20,
            reducedFrameRate = 30,
            pauseWhileCharging = true
        )

        // Strictly below 20% triggers battery saving
        assertTrue(
            BatterySaverPolicy.isBatterySaverActive(
                thresholdConfig,
                PowerSnapshot(batteryPercent = 19, isCharging = false, isSystemPowerSaveMode = false)
            )
        )
        assertEquals(
            30,
            BatterySaverPolicy.effectiveFrameRate(
                165,
                thresholdConfig,
                PowerSnapshot(batteryPercent = 19, isCharging = false, isSystemPowerSaveMode = false)
            )
        )

        // Exactly 20% does not trigger "below 20%"
        assertFalse(
            BatterySaverPolicy.isBatterySaverActive(
                thresholdConfig,
                PowerSnapshot(batteryPercent = 20, isCharging = false, isSystemPowerSaveMode = false)
            )
        )
        assertEquals(
            165,
            BatterySaverPolicy.effectiveFrameRate(
                165,
                thresholdConfig,
                PowerSnapshot(batteryPercent = 20, isCharging = false, isSystemPowerSaveMode = false)
            )
        )

        // Charging pauses battery saving when pauseWhileCharging = true
        assertFalse(
            BatterySaverPolicy.isBatterySaverActive(
                thresholdConfig,
                PowerSnapshot(batteryPercent = 10, isCharging = true, isSystemPowerSaveMode = true)
            )
        )
        assertTrue(
            BatterySaverPolicy.isBatterySaverActive(
                thresholdConfig.copy(pauseWhileCharging = false),
                PowerSnapshot(batteryPercent = 10, isCharging = true, isSystemPowerSaveMode = false)
            )
        )

        // System Battery Saver mode
        val systemSaverConfig = BatterySaverConfig(
            enabled = true,
            triggerMode = BatterySaverTrigger.SYSTEM_BATTERY_SAVER,
            thresholdPercent = 20,
            reducedFrameRate = 60,
            pauseWhileCharging = true
        )
        assertFalse(
            BatterySaverPolicy.isBatterySaverActive(
                systemSaverConfig,
                PowerSnapshot(batteryPercent = 5, isCharging = false, isSystemPowerSaveMode = false)
            )
        )
        assertTrue(
            BatterySaverPolicy.isBatterySaverActive(
                systemSaverConfig,
                PowerSnapshot(batteryPercent = 85, isCharging = false, isSystemPowerSaveMode = true)
            )
        )
        assertEquals(
            60,
            BatterySaverPolicy.effectiveFrameRate(
                120,
                systemSaverConfig,
                PowerSnapshot(batteryPercent = 85, isCharging = false, isSystemPowerSaveMode = true)
            )
        )
    }

    @Test
    fun batterySaverMonitorReactsPassivelyToBatteryChangedBroadcasts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val updates = mutableListOf<PowerSnapshot>()
        val monitor = BatterySaverMonitor(context) { snapshot ->
            updates.add(snapshot)
        }
        monitor.start()
        try {
            val lowIntent = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
                putExtra(BatteryManager.EXTRA_LEVEL, 18)
                putExtra(BatteryManager.EXTRA_SCALE, 100)
                putExtra(BatteryManager.EXTRA_PLUGGED, 0)
                putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING)
            }
            context.sendBroadcast(lowIntent)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertTrue(updates.any { it.batteryPercent == 18 && !it.isCharging })

            val chargingIntent = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
                putExtra(BatteryManager.EXTRA_LEVEL, 18)
                putExtra(BatteryManager.EXTRA_SCALE, 100)
                putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_USB)
                putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING)
            }
            context.sendBroadcast(chargingIntent)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertTrue(updates.any { it.batteryPercent == 18 && it.isCharging })
        } finally {
            monitor.stop()
        }
    }
}
