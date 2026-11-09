package com.example.service

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import android.view.animation.LinearInterpolator
import com.example.darken.ExtraDimDetector
import com.example.data.repository.WallpaperRepository
import com.example.transition.TransitionRegistry

class MultiPageWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return MultiPageEngine()
    }

    inner class MultiPageEngine : Engine() {

        private val repository by lazy { WallpaperRepository.getInstance(applicationContext) }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val dimPaint = Paint()

        private var surfaceWidth = 1080
        private var surfaceHeight = 2400
        private var currentXOffset = 0.5f
        private var currentYOffset = 0.0f
        private var isVisible = false
        private var hasReceivedLauncherOffset = false
        private var resumeFadeAnimator: ValueAnimator? = null
        private var resumeFadeProgress = 1f

        private var extraDimObserver: ContentObserver? = null
        private var systemBroadcastReceiver: BroadcastReceiver? = null

        private val repoChangeListener = {
            val calib = repository.appState.value.calibration
            // If the wallpaper is not currently visible or hasn't received active launcher scroll,
            // make sure we stay synchronized with the user's home page setting.
            if (!isVisible || !hasReceivedLauncherOffset) {
                currentXOffset = repository.getLastKnownOffsetOrHome()
            }
            drawFrame()
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            val calib = repository.appState.value.calibration
            currentXOffset = repository.getLastKnownOffsetOrHome()
            repository.addChangeListener(repoChangeListener)
            extraDimObserver = ExtraDimDetector.registerObserver(applicationContext) {
                drawFrame()
            }
            registerSystemReceivers()
        }

        override fun onDestroy() {
            super.onDestroy()
            resumeFadeAnimator?.cancel()
            resumeFadeAnimator = null
            repository.removeChangeListener(repoChangeListener)
            ExtraDimDetector.unregisterObserver(applicationContext, extraDimObserver)
            extraDimObserver = null
            unregisterSystemReceivers()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            val wasVisible = isVisible
            isVisible = visible
            if (visible) {
                val calib = repository.appState.value.calibration
                val homeOffset = calib.getHomeOffset()

                // If resuming from an app (e.g. YouTube, PiP) or screen sleep where Android
                // might have sent dummy 0.0f system offsets, restore the user's home offset
                // if the launcher's home page is not screen 1.
                if (!wasVisible) {
                    if (calib.homePageIndex != 0 && (!hasReceivedLauncherOffset || currentXOffset <= 0.001f)) {
                        currentXOffset = homeOffset
                        repository.saveLastKnownOffset(currentXOffset)
                    }
                    startResumeFadeIfEnabled()
                }
                drawFrame()
            } else {
                resumeFadeAnimator?.cancel()
                resumeFadeAnimator = null
                resumeFadeProgress = 1f
            }
        }

        private fun startResumeFadeIfEnabled() {
            resumeFadeAnimator?.cancel()
            resumeFadeAnimator = null
            resumeFadeProgress = 1f
            if (!repository.appState.value.resumeFadeEnabled) return

            resumeFadeProgress = 0f
            resumeFadeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 250L
                interpolator = LinearInterpolator()
                addUpdateListener {
                    resumeFadeProgress = it.animatedValue as Float
                    drawFrame()
                }
                start()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            drawFrame()
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset)
            val calib = repository.appState.value.calibration
            val homeOffset = calib.getHomeOffset()

            // Detect dummy system resets:
            // When fullscreen apps (like YouTube / PiP) or lock screens are active,
            // Android WindowManager dispatches xOffset = 0.0f with xOffsetStep <= 0.0f.
            // When homePageIndex != 0, accepting this would wrongly snap to screen 1!
            val isDummySystemReset = (xOffset == 0.0f && xOffsetStep <= 0.0f && calib.homePageIndex != 0)

            // Ignore system resets while the wallpaper is completely hidden behind an app
            if (!isVisible && isDummySystemReset) {
                return
            }

            if (isDummySystemReset) {
                // If the system dispatches dummy 0.0f while returning home, default to home page
                if (!hasReceivedLauncherOffset || !isVisible) {
                    currentXOffset = homeOffset
                    currentYOffset = yOffset
                    if (isVisible) {
                        drawFrame()
                    }
                    return
                }
            }

            // Real launcher offset from user swiping
            currentXOffset = xOffset.coerceIn(0f, 1f)
            currentYOffset = yOffset
            hasReceivedLauncherOffset = true

            // Persist the last valid offset so it survives service recreation
            repository.saveLastKnownOffset(currentXOffset)

            // Record observed raw offset to help user with calibration wizard (only if visible and real)
            if (isVisible && !isDummySystemReset) {
                repository.recordObservedRawOffset(xOffset)
            }

            if (isVisible) {
                drawFrame()
            }
        }

        override fun onCommand(
            action: String?,
            x: Int,
            y: Int,
            z: Int,
            extras: Bundle?,
            resultRequested: Boolean
        ): Bundle? {
            if (action == "android.home" || action == "android.wallpaper.waking_up") {
                val calib = repository.appState.value.calibration
                if (action == "android.home") {
                    // Swiped home or home button pressed
                    currentXOffset = calib.getHomeOffset()
                    repository.saveLastKnownOffset(currentXOffset)
                    if (isVisible) {
                        drawFrame()
                    }
                }
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        private fun registerSystemReceivers() {
            try {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        when (intent?.action) {
                            Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> {
                                val reason = intent.getStringExtra("reason")
                                if (reason == "homekey") {
                                    // User swiped or tapped home button to return to launcher
                                    val calib = repository.appState.value.calibration
                                    currentXOffset = calib.getHomeOffset()
                                    repository.saveLastKnownOffset(currentXOffset)
                                    if (isVisible) {
                                        drawFrame()
                                    }
                                }
                            }
                            Intent.ACTION_USER_PRESENT -> {
                                // Device unlocked
                                val calib = repository.appState.value.calibration
                                if (!hasReceivedLauncherOffset || (currentXOffset <= 0.001f && calib.homePageIndex != 0)) {
                                    currentXOffset = calib.getHomeOffset()
                                    repository.saveLastKnownOffset(currentXOffset)
                                }
                                if (isVisible) {
                                    drawFrame()
                                }
                            }
                        }
                    }
                }
                applicationContext.registerReceiver(receiver, filter)
                systemBroadcastReceiver = receiver
            } catch (e: Exception) {
                Log.w("MultiPageWallpaper", "Could not register system receivers", e)
            }
        }

        private fun unregisterSystemReceivers() {
            systemBroadcastReceiver?.let {
                try {
                    applicationContext.unregisterReceiver(it)
                } catch (e: Exception) {
                    Log.w("MultiPageWallpaper", "Could not unregister system receivers", e)
                }
                systemBroadcastReceiver = null
            }
        }

        fun drawFrame() {
            val holder = surfaceHolder ?: return
            var canvas: android.graphics.Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    if (resumeFadeProgress < 1f) {
                        canvas.drawColor(Color.BLACK)
                        val layer = canvas.saveLayerAlpha(
                            0f,
                            0f,
                            surfaceWidth.toFloat(),
                            surfaceHeight.toFloat(),
                            (resumeFadeProgress * 255).toInt().coerceIn(0, 255)
                        )
                        renderWallpaper(canvas)
                        canvas.restoreToCount(layer)
                        return
                    }
                    renderWallpaper(canvas)
                }
            } catch (e: Exception) {
                Log.e("MultiPageWallpaper", "Error drawing wallpaper frame", e)
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                        Log.e("MultiPageWallpaper", "Error unlocking canvas", e)
                    }
                }
            }
        }

        private fun renderWallpaper(canvas: android.graphics.Canvas) {
            val state = repository.appState.value
            val calib = state.calibration
            val pageCount = calib.pageCount.coerceAtLeast(1)

            // Calculate page indices and transition progress based on calibrated anchors
            val (pageA, pageB, progress) = computePageTransition(currentXOffset, calib)

            val pageABitmap = repository.getCroppedBitmap(pageA, surfaceWidth, surfaceHeight)
            val pageBBitmap = if (pageA != pageB) {
                repository.getCroppedBitmap(pageB, surfaceWidth, surfaceHeight)
            } else {
                null
            }

            // Draw transition
            val renderer = TransitionRegistry.get(state.transitionType)
            renderer.draw(
                canvas = canvas,
                pageABitmap = pageABitmap,
                pageBBitmap = pageBBitmap,
                progress = progress,
                screenWidth = surfaceWidth,
                screenHeight = surfaceHeight,
                paint = paint
            )

            // Evaluate Darken / Extra Dim condition
            val isDimCondition = ExtraDimDetector.isDimConditionActive(applicationContext, state.darken)
            if (isDimCondition) {
                val pageAItem = state.pages.find { it.pageIndex == pageA }
                val pageBItem = state.pages.find { it.pageIndex == pageB }

                val isADark = pageAItem?.isAlreadyDark ?: false
                val isBDark = pageBItem?.isAlreadyDark ?: false

                // If both are dark, no dimming needed. If one is dark, blend dim factor proportionally
                val effectiveDim = when {
                    isADark && isBDark -> 0f
                    isADark -> state.darken.dimFactor * progress
                    isBDark -> state.darken.dimFactor * (1f - progress)
                    else -> state.darken.dimFactor
                }

                if (effectiveDim > 0.01f) {
                    val alpha = (effectiveDim.coerceIn(0f, 1f) * 255).toInt()
                    dimPaint.color = Color.BLACK
                    dimPaint.alpha = alpha
                    canvas.drawRect(
                        0f, 0f,
                        surfaceWidth.toFloat(),
                        surfaceHeight.toFloat(),
                        dimPaint
                    )
                }
            }
        }

        /**
         * Maps raw xOffset (0.0 to 1.0) into Page A, Page B, and progress (0.0 to 1.0)
         * using the user's calibrated anchors.
         */
        private fun computePageTransition(
            xOffset: Float,
            calib: com.example.data.model.CalibrationConfig
        ): Triple<Int, Int, Float> {
            val count = calib.pageCount
            if (count <= 1) {
                return Triple(0, 0, 0f)
            }

            val anchors = calib.customOffsets
            if (anchors.size < count) {
                // Fallback to uniform distribution
                val step = 1.0f / (count - 1).toFloat()
                val pageIndex = (xOffset / step).toInt().coerceIn(0, count - 2)
                val p = ((xOffset - pageIndex * step) / step).coerceIn(0f, 1f)
                return Triple(pageIndex, pageIndex + 1, p)
            }

            // Find segment
            if (xOffset <= anchors.first()) {
                return Triple(0, 0, 0f)
            }
            if (xOffset >= anchors.last()) {
                return Triple(count - 1, count - 1, 0f)
            }

            for (i in 0 until count - 1) {
                val a0 = anchors[i]
                val a1 = anchors[i + 1]
                if (xOffset in a0..a1) {
                    val span = (a1 - a0).coerceAtLeast(0.0001f)
                    val progress = ((xOffset - a0) / span).coerceIn(0f, 1f)
                    return Triple(i, i + 1, progress)
                }
            }

            return Triple(0, 0, 0f)
        }
    }
}
