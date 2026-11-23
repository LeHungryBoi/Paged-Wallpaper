package com.example.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import com.example.data.model.BatterySaverConfig
import com.example.data.model.BatterySaverTrigger

data class PowerSnapshot(
    val batteryPercent: Int? = null,
    val isCharging: Boolean = false,
    val isSystemPowerSaveMode: Boolean = false
)

object BatterySaverPolicy {

    fun isBatterySaverActive(
        config: BatterySaverConfig,
        power: PowerSnapshot
    ): Boolean {
        if (!config.enabled) return false
        if (config.pauseWhileCharging && power.isCharging) return false
        val belowThreshold = power.batteryPercent != null &&
            power.batteryPercent < config.thresholdPercent.coerceIn(
                BatterySaverConfig.MIN_THRESHOLD_PERCENT,
                BatterySaverConfig.MAX_THRESHOLD_PERCENT
            )
        return when (config.triggerMode) {
            BatterySaverTrigger.BATTERY_PERCENTAGE -> belowThreshold
            BatterySaverTrigger.SYSTEM_BATTERY_SAVER -> power.isSystemPowerSaveMode
            BatterySaverTrigger.EITHER -> belowThreshold || power.isSystemPowerSaveMode
        }
    }

    fun effectiveFrameRate(
        normalFrameRate: Int,
        config: BatterySaverConfig,
        power: PowerSnapshot
    ): Int {
        if (!isBatterySaverActive(config, power)) return normalFrameRate
        val safeReduced = config.reducedFrameRate
            .takeIf { it in BatterySaverConfig.supportedReducedFrameRates }
            ?: BatterySaverConfig.DEFAULT_REDUCED_FRAME_RATE
        return minOf(normalFrameRate, safeReduced)
    }

    fun parseBatteryChangedIntent(
        intent: Intent?,
        currentSystemPowerSaveMode: Boolean
    ): PowerSnapshot {
        if (intent == null) {
            return PowerSnapshot(isSystemPowerSaveMode = currentSystemPowerSaveMode)
        }
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) {
            ((level * 100f) / scale).toInt().coerceIn(0, 100)
        } else {
            null
        }
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val isCharging = plugged != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return PowerSnapshot(
            batteryPercent = percent,
            isCharging = isCharging,
            isSystemPowerSaveMode = currentSystemPowerSaveMode
        )
    }
}

/**
 * Passively observes device battery level, charging state, and Android System Battery Saver
 * via system broadcast callbacks without polling.
 */
class BatterySaverMonitor(
    private val context: Context,
    private val onPowerChanged: (PowerSnapshot) -> Unit
) {
    private val appContext = context.applicationContext
    private var receiver: BroadcastReceiver? = null
    private var latestSnapshot: PowerSnapshot? = null

    fun start() {
        if (receiver != null) return
        val systemSaverActive = readSystemPowerSaveMode()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        val powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val systemSaver = readSystemPowerSaveMode()
                val next = when (intent?.action) {
                    Intent.ACTION_BATTERY_CHANGED -> {
                        BatterySaverPolicy.parseBatteryChangedIntent(intent, systemSaver)
                    }
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                        val base = latestSnapshot ?: PowerSnapshot()
                        base.copy(isSystemPowerSaveMode = systemSaver)
                    }
                    else -> return
                }
                emitIfChanged(next)
            }
        }
        try {
            val stickyBatteryIntent = appContext.registerReceiver(powerReceiver, filter)
            receiver = powerReceiver
            val initial = BatterySaverPolicy.parseBatteryChangedIntent(
                stickyBatteryIntent,
                systemSaverActive
            )
            emitIfChanged(initial)
        } catch (e: Exception) {
            Log.w("BatterySaverMonitor", "Could not register battery broadcast receiver", e)
        }
    }

    fun stop() {
        receiver?.let {
            try {
                appContext.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w("BatterySaverMonitor", "Could not unregister battery broadcast receiver", e)
            }
            receiver = null
        }
    }

    private fun readSystemPowerSaveMode(): Boolean = try {
        (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
    } catch (_: Exception) {
        false
    }

    private fun emitIfChanged(snapshot: PowerSnapshot) {
        if (latestSnapshot == snapshot) return
        latestSnapshot = snapshot
        onPowerChanged(snapshot)
    }
}
