package com.example.service

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import android.view.animation.LinearInterpolator
import com.example.darken.ExtraDimDetector
import com.example.diagnostics.WallpaperDiagnosticsRecorder
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
        private val offsetOverlayBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 22, 28, 40)
        }
        private val offsetOverlayLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.LTGRAY
        }
        private val offsetOverlayTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 210, 220, 235)
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        }
        private val offsetOverlayXDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(80, 220, 255)
        }
        private val offsetOverlayYDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 180, 0)
        }

        private var surfaceWidth = 1080
        private var surfaceHeight = 2400
        private var currentXOffset = 0.5f
        private var offsetDirection = 0
        private var currentYOffset = 0.0f
        private var receivedXOffset: Float? = null
        private var receivedYOffset: Float? = null
        private var transitionPageA = 0
        private var transitionPageB = 0
        private var transitionProgress = 0f
        private val offsetOverlayBounds = RectF()
        private var isVisible = false
        private var hasReceivedLauncherOffset = false
        private var awaitingResumeOffset = false
        private var recoveringFromZeroOffset = false
        private var sawZeroOffsetWhileHidden = false
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
            WallpaperDiagnosticsRecorder.initialize(applicationContext)
            val calib = repository.appState.value.calibration
            currentXOffset = repository.getLastKnownOffsetOrHome()
            awaitingResumeOffset = calib.homePageIndex != 0
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
            WallpaperDiagnosticsRecorder.recordVisibilityChanged(visible)
            if (visible) {
                val calib = repository.appState.value.calibration
                val homeOffset = calib.getHomeOffset()

                // If resuming from an app (e.g. YouTube, PiP) or screen sleep where Android
                // might have sent dummy 0.0f system offsets, restore the user's home offset
                // if the launcher's home page is not screen 1.
                if (!wasVisible) {
                    if (calib.homePageIndex != 0 &&
                        (sawZeroOffsetWhileHidden || !hasReceivedLauncherOffset || currentXOffset <= 0.001f)
                    ) {
                        currentXOffset = homeOffset
                        repository.saveLastKnownOffset(currentXOffset)
                    }
                    awaitingResumeOffset = calib.homePageIndex != 0
                    recoveringFromZeroOffset = sawZeroOffsetWhileHidden
                    sawZeroOffsetWhileHidden = false
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
            WallpaperDiagnosticsRecorder.recordOffsetUpdate(xOffset, isVisible)
            receivedXOffset = xOffset
            receivedYOffset = yOffset
            val calib = repository.appState.value.calibration
            val homeOffset = calib.getHomeOffset()
            val isZeroOffset = xOffset <= 0.001f

            if (calib.homePageIndex != 0 && !isVisible && isZeroOffset) {
                sawZeroOffsetWhileHidden = true
                return
            }

            if (calib.homePageIndex != 0 && isVisible) {
                if (awaitingResumeOffset && isZeroOffset) {
                    recoveringFromZeroOffset = true
                }

                if (recoveringFromZeroOffset) {
                    val homeTolerance = maxOf(0.02f, kotlin.math.abs(xOffsetStep) * 0.05f)
                    if (kotlin.math.abs(xOffset - homeOffset) > homeTolerance) {
                        drawFrame(fromOffsetUpdate = true)
                        return
                    }
                    recoveringFromZeroOffset = false
                }
                awaitingResumeOffset = false
            }

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
                    recoveringFromZeroOffset = false
                    awaitingResumeOffset = false
                    if (isVisible) {
                        drawFrame(fromOffsetUpdate = true)
                    }
                    return
                }
            }

            // Real launcher offset from user swiping
            val nextXOffset = xOffset.coerceIn(0f, 1f)
            if (nextXOffset > currentXOffset + 0.0001f) {
                offsetDirection = 1
            } else if (nextXOffset < currentXOffset - 0.0001f) {
                offsetDirection = -1
            }
            currentXOffset = nextXOffset
            currentYOffset = yOffset
            hasReceivedLauncherOffset = true

            // Persist the last valid offset so it survives service recreation
            repository.saveLastKnownOffset(currentXOffset)

            if (isVisible) {
                drawFrame(fromOffsetUpdate = true)
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
                    awaitingResumeOffset = calib.homePageIndex != 0
                    recoveringFromZeroOffset = false
                    sawZeroOffsetWhileHidden = false
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
                                    awaitingResumeOffset = calib.homePageIndex != 0
                                    recoveringFromZeroOffset = false
                                    sawZeroOffsetWhileHidden = false
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

        fun drawFrame(fromOffsetUpdate: Boolean = false) {
            val holder = surfaceHolder ?: return
            var canvas: android.graphics.Canvas? = null
            var framePosted = false
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
                        framePosted = true
                    } catch (e: Exception) {
                        Log.e("MultiPageWallpaper", "Error unlocking canvas", e)
                    }
                }
                if (framePosted) {
                    WallpaperDiagnosticsRecorder.recordFrameSubmitted(
                        fromOffsetUpdate = fromOffsetUpdate,
                        isVisible = isVisible
                    )
                }
            }
        }

        private fun renderWallpaper(canvas: android.graphics.Canvas) {
            val state = repository.appState.value
            val calib = state.calibration
            val pageCount = calib.pageCount.coerceAtLeast(1)

            // Calculate page indices and transition progress based on calibrated anchors
            computePageTransition(currentXOffset, calib)
            val pageA = transitionPageA
            val pageB = transitionPageB
            val progress = transitionProgress

            val transitionSetting = state.transitions.getOrNull(pageA)
            val transitionId = transitionSetting?.transitionId(offsetDirection >= 0)
                ?: state.transitionType
            val renderer = TransitionRegistry.get(transitionId)
            val usesParallax = renderer.supportsParallax
            val slowImageSpeed = transitionSetting
                ?.parallaxMotion(offsetDirection >= 0)
                ?.takeIf { it.slowImageMotionEnabled }
                ?.imageSpeed
                ?.coerceIn(0f, 1f)
            val pageAParallax = if (usesParallax) {
                repository.getParallaxBitmap(pageA, surfaceWidth, surfaceHeight)
            } else {
                null
            }
            val pageBParallax = if (usesParallax && pageA != pageB) {
                repository.getParallaxBitmap(pageB, surfaceWidth, surfaceHeight)
            } else {
                null
            }
            val pageABitmap = pageAParallax?.bitmap
                ?: if (usesParallax) null
                else repository.getCroppedBitmap(pageA, surfaceWidth, surfaceHeight)
            val pageBBitmap = if (pageA != pageB) {
                pageBParallax?.bitmap
                    ?: if (usesParallax) null
                    else repository.getCroppedBitmap(pageB, surfaceWidth, surfaceHeight)
            } else {
                null
            }

            // Draw transition
            canvas.drawColor(android.graphics.Color.rgb(18, 22, 34))
            if (usesParallax) {
                renderer.drawWithParallax(
                    canvas = canvas,
                    pageABitmap = pageABitmap,
                    pageBBitmap = pageBBitmap,
                    progress = progress,
                    screenWidth = surfaceWidth,
                    screenHeight = surfaceHeight,
                    paint = paint,
                    pageALeftTravelPx = pageAParallax?.leftTravelPx ?: 0,
                    pageARightTravelPx = pageAParallax?.rightTravelPx ?: 0,
                    pageBLeftTravelPx = pageBParallax?.leftTravelPx ?: 0,
                    pageBRightTravelPx = pageBParallax?.rightTravelPx ?: 0,
                    pageASlowImageSpeed = slowImageSpeed,
                    pageBSlowImageSpeed = slowImageSpeed
                )
            } else {
                renderer.draw(
                    canvas = canvas,
                    pageABitmap = pageABitmap,
                    pageBBitmap = pageBBitmap,
                    progress = progress,
                    screenWidth = surfaceWidth,
                    screenHeight = surfaceHeight,
                    paint = paint
                )
            }

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

            if (state.showOffsetDebugOverlay) {
                drawOffsetDebugOverlay(canvas)
            }
        }

        private fun drawOffsetDebugOverlay(canvas: android.graphics.Canvas) {
            val density = resources.displayMetrics.density
            val cardWidth = 240f * density
            val cardHeight = 82f * density
            val left = (surfaceWidth - cardWidth) / 2f
            val top = 20f * density
            offsetOverlayBounds.set(left, top, left + cardWidth, top + cardHeight)

            offsetOverlayLabelPaint.textSize = 11f * density
            offsetOverlayLabelPaint.textAlign = Paint.Align.CENTER
            offsetOverlayTrackPaint.strokeWidth = 3f * density
            canvas.drawRoundRect(
                offsetOverlayBounds,
                12f * density,
                12f * density,
                offsetOverlayBackgroundPaint
            )
            canvas.drawText(
                "Launcher offset received",
                surfaceWidth / 2f,
                top + 19f * density,
                offsetOverlayLabelPaint
            )

            val trackStart = left + 42f * density
            val trackEnd = left + cardWidth - 16f * density
            val trackRadius = 5f * density
            val xY = top + 41f * density
            val yY = top + 64f * density
            offsetOverlayLabelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("X", left + 16f * density, xY + 4f * density, offsetOverlayLabelPaint)
            canvas.drawText("Y", left + 16f * density, yY + 4f * density, offsetOverlayLabelPaint)

            canvas.drawLine(trackStart, xY, trackEnd, xY, offsetOverlayTrackPaint)
            canvas.drawLine(trackStart, yY, trackEnd, yY, offsetOverlayTrackPaint)
            receivedXOffset?.let { offset ->
                canvas.drawCircle(
                    trackStart + (trackEnd - trackStart) * offset.coerceIn(0f, 1f),
                    xY,
                    trackRadius,
                    offsetOverlayXDotPaint
                )
            }
            receivedYOffset?.let { offset ->
                canvas.drawCircle(
                    trackStart + (trackEnd - trackStart) * offset.coerceIn(0f, 1f),
                    yY,
                    trackRadius,
                    offsetOverlayYDotPaint
                )
            }
        }

        /**
         * Maps raw xOffset (0.0 to 1.0) into Page A, Page B, and progress (0.0 to 1.0)
         * using the user's calibrated anchors.
         */
        private fun computePageTransition(
            xOffset: Float,
            calib: com.example.data.model.CalibrationConfig
        ) {
            val count = calib.pageCount
            if (count <= 1) {
                setPageTransition(0, 0, 0f)
                return
            }

            val anchors = calib.customOffsets
            if (anchors.size < count) {
                // Fallback to uniform distribution
                val step = 1.0f / (count - 1).toFloat()
                val pageIndex = (xOffset / step).toInt().coerceIn(0, count - 2)
                val p = ((xOffset - pageIndex * step) / step).coerceIn(0f, 1f)
                setPageTransition(pageIndex, pageIndex + 1, p)
                return
            }

            // Find segment
            if (xOffset <= anchors.first()) {
                setPageTransition(0, 0, 0f)
                return
            }
            if (xOffset >= anchors.last()) {
                setPageTransition(count - 1, count - 1, 0f)
                return
            }

            for (i in 0 until count - 1) {
                val a0 = anchors[i]
                val a1 = anchors[i + 1]
                if (xOffset in a0..a1) {
                    val span = (a1 - a0).coerceAtLeast(0.0001f)
                    val progress = ((xOffset - a0) / span).coerceIn(0f, 1f)
                    setPageTransition(i, i + 1, progress)
                    return
                }
            }

            setPageTransition(0, 0, 0f)
        }

        private fun setPageTransition(pageA: Int, pageB: Int, progress: Float) {
            transitionPageA = pageA
            transitionPageB = pageB
            transitionProgress = progress
        }
    }
}
