package com.example.service

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import com.example.darken.ExtraDimDetector
import com.example.data.model.CalibrationConfig
import com.example.data.model.WallpaperAppState
import com.example.diagnostics.WallpaperDiagnosticsRecorder
import com.example.data.repository.WallpaperRepository
import com.example.render.EdgeOverScrollController
import com.example.render.EdgeOverScrollScaling
import com.example.render.GpuFrameRequest
import com.example.render.GpuWallpaperRenderer
import com.example.render.HorizontalMotionBlur
import com.example.render.OffsetEasing
import com.example.render.SwipeDirectionLock
import com.example.transition.TransitionRegistry
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp

private const val OFFSET_EPSILON = 0.00005f
private const val STATIC_OFFSET_TOLERANCE = 0.002f
private const val STATIC_OFFSET_SEGMENT_TOLERANCE_RATIO = 0.02f
private const val NANOS_PER_SECOND = 1_000_000_000L
private const val MOTION_BLUR_DOWNSAMPLE_FACTOR = 2
private const val MOTION_BLUR_SHUTTER_FRACTION = 1.0f
private const val MAX_MOTION_BLUR_FRAME_GAP_NANOS = 200_000_000L

class MultiPageWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return MultiPageEngine()
    }

    inner class MultiPageEngine : Engine() {

        private val repository by lazy { WallpaperRepository.getInstance(applicationContext) }
        private val mainHandler = Handler(Looper.getMainLooper())
        private var gpuRenderer: GpuWallpaperRenderer? = null
        private var gpuRenderingFailed = false
        private var engineDestroyed = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val motionBlur = HorizontalMotionBlur()
        private val motionBlurDestination = Rect()
        private val dimPaint = Paint()
        private val frameDebugColors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        private val frameDebugPaint = Paint()
        private val frameDebugBorderPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
        }
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
        private var renderedXOffset = currentXOffset
        private var offsetDirection = 0
        private val swipeDirectionLock = SwipeDirectionLock()
        private val edgeOverScrollController = EdgeOverScrollController()
        private val touchSlop by lazy { ViewConfiguration.get(applicationContext).scaledTouchSlop }
        private val edgeOverScrollSrcRect = Rect()
        private val edgeOverScrollDstRect = Rect()
        private var currentYOffset = 0.0f
        private var receivedXOffset: Float? = null
        private var receivedYOffset: Float? = null
        private var transitionPageA = 0
        private var transitionPageB = 0
        private var transitionProgress = 0f
        private var frameDebugSequenceIndex = 0
        private var frameDebugIndicatorEnabled = false
        private var frameDebugIndicatorDrawnForFrame = false
        private val offsetOverlayBounds = RectF()
        private var isVisible = false
        private var hasReceivedLauncherOffset = false
        private var lastLauncherXOffsetStep = 0f
        private var awaitingResumeOffset = false
        private var recoveringFromZeroOffset = false
        private var sawZeroOffsetWhileHidden = false
        private var resumeFadeAnimator: ValueAnimator? = null
        private var resumeFadeProgress = 1f
        private val choreographer by lazy { Choreographer.getInstance() }
        private var animationFramePosted = false
        private var lastAnimationFrameTimeNanos = 0L
        private var nextAnimationFrameDeadlineNanos = 0L
        private var preferredSurfaceFrameRate: Int? = null
        private var motionBlurBitmap: Bitmap? = null
        private var motionBlurCanvas: Canvas? = null
        private var lastMotionBlurPagePosition: Float? = null
        private var lastMotionBlurFrameTimeNanos = 0L
        private val animationFrameCallback = Choreographer.FrameCallback { frameTimeNanos ->
            animationFramePosted = false
            drawSmoothedOffsetFrame(frameTimeNanos)
        }

        private var extraDimObserver: ContentObserver? = null
        private var systemBroadcastReceiver: BroadcastReceiver? = null

        private val repoChangeListener: () -> Unit = {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                handleRepositoryChange()
            } else {
                mainHandler.post { handleRepositoryChange() }
            }
        }

        private fun handleRepositoryChange() {
            if (engineDestroyed) return
            // If the wallpaper is not currently visible or hasn't received active launcher scroll,
            // make sure we stay synchronized with the user's home page setting.
            if (!isVisible || !hasReceivedLauncherOffset) {
                currentXOffset = repository.getLastKnownOffsetOrHome()
                renderedXOffset = currentXOffset
                offsetDirection = swipeDirectionLock.reset()
                edgeOverScrollController.reset()
                stopOffsetAnimation()
            }
            if (!repository.appState.value.motionBlurEnabled) {
                releaseMotionBlurBuffer()
            }
            updateSurfaceFrameRate()
            if (isVisible && isOffsetAnimationNeeded()) {
                scheduleOffsetAnimationFrame()
            } else {
                drawFrame()
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            WallpaperDiagnosticsRecorder.initialize(applicationContext)
            val calib = repository.appState.value.calibration
            currentXOffset = repository.getLastKnownOffsetOrHome()
            renderedXOffset = currentXOffset
            offsetDirection = swipeDirectionLock.reset()
            awaitingResumeOffset = calib.homePageIndex != 0
            repository.addChangeListener(repoChangeListener)
            extraDimObserver = ExtraDimDetector.registerObserver(applicationContext) {
                drawFrame()
            }
            registerSystemReceivers()
        }

        override fun onDestroy() {
            engineDestroyed = true
            gpuRenderer?.release()
            gpuRenderer = null
            super.onDestroy()
            stopOffsetAnimation()
            releaseMotionBlurBuffer()
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
                        (sawZeroOffsetWhileHidden || !hasReceivedLauncherOffset ||
                            (currentXOffset <= 0.001f && lastLauncherXOffsetStep <= 0f))
                    ) {
                        currentXOffset = homeOffset
                        offsetDirection = swipeDirectionLock.reset()
                        repository.saveLastKnownOffset(currentXOffset)
                    }
                    renderedXOffset = currentXOffset
                    stopOffsetAnimation()
                    awaitingResumeOffset = calib.homePageIndex != 0
                    recoveringFromZeroOffset = sawZeroOffsetWhileHidden
                    sawZeroOffsetWhileHidden = false
                    startResumeFadeIfEnabled()
                }
                drawFrame()
            } else {
                edgeOverScrollController.reset()
                stopOffsetAnimation()
                releaseMotionBlurBuffer()
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

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            getGpuRenderer()?.onSurfaceChanged(holder.surface, surfaceWidth, surfaceHeight)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            getGpuRenderer()?.onSurfaceChanged(holder.surface, width, height)
            preferredSurfaceFrameRate = null
            updateSurfaceFrameRate()
            drawFrame()
            if (isVisible && isOffsetAnimationNeeded()) {
                scheduleOffsetAnimationFrame()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            stopOffsetAnimation()
            releaseMotionBlurBuffer()
            gpuRenderer?.onSurfaceDestroyed()
            super.onSurfaceDestroyed(holder)
        }

        private fun getGpuRenderer(): GpuWallpaperRenderer? {
            if (gpuRenderingFailed || engineDestroyed) return null
            return gpuRenderer ?: GpuWallpaperRenderer(repository).also { gpuRenderer = it }
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
            lastLauncherXOffsetStep = xOffsetStep
            val isZeroOffset = xOffset <= 0.001f
            // A real page-1 offset also has xOffset == 0, but a real launcher reports a
            // positive xOffsetStep. Only the zero-without-step combination is a dummy reset.
            val isDummyZeroOffset = isZeroOffset && xOffsetStep <= 0f

            if (calib.homePageIndex != 0 && !isVisible && isDummyZeroOffset) {
                sawZeroOffsetWhileHidden = true
                return
            }

            if (calib.homePageIndex != 0 && isVisible) {
                if (awaitingResumeOffset && isDummyZeroOffset) {
                    recoveringFromZeroOffset = true
                }

                if (recoveringFromZeroOffset) {
                    if (isDummyZeroOffset) {
                        drawFrame(fromOffsetUpdate = true)
                        return
                    }
                    // A real launcher offset means the user is actively swiping; never
                    // swallow offsets just because they are between page 1 and the home page.
                    recoveringFromZeroOffset = false
                }
                awaitingResumeOffset = false
            }

            // Detect dummy system resets:
            // When fullscreen apps (like YouTube / PiP) or lock screens are active,
            // Android WindowManager dispatches xOffset = 0.0f with xOffsetStep <= 0.0f.
            // When homePageIndex != 0, accepting this would wrongly snap to screen 1!
            val isDummySystemReset = isDummyZeroOffset && calib.homePageIndex != 0

            // Ignore system resets while the wallpaper is completely hidden behind an app
            if (!isVisible && isDummySystemReset) {
                return
            }

            if (isDummySystemReset) {
                // If the system dispatches dummy 0.0f while returning home, default to home page
                if (!hasReceivedLauncherOffset || !isVisible) {
                    currentXOffset = homeOffset
                    renderedXOffset = homeOffset
                    offsetDirection = swipeDirectionLock.reset()
                    currentYOffset = yOffset
                    stopOffsetAnimation()
                    recoveringFromZeroOffset = false
                    awaitingResumeOffset = false
                    if (isVisible) {
                        drawFrame(fromOffsetUpdate = true)
                    }
                    return
                }
            }

            // Real launcher offset from user swiping
            val previousXOffset = currentXOffset
            val nextXOffset = xOffset.coerceIn(0f, 1f)
            val offsetChanged = abs(nextXOffset - currentXOffset) > OFFSET_EPSILON
            val yOffsetChanged = currentYOffset != yOffset
            currentXOffset = nextXOffset
            currentYOffset = yOffset
            hasReceivedLauncherOffset = true

            // Persist the last valid offset so it survives service recreation
            repository.saveLastKnownOffset(currentXOffset)

            // While the finger is held down on an edge page, follow the touch signal exclusively
            // until release so misleading launcher edge offsets do not hijack the gesture.
            if (edgeOverScrollController.isTouchActive) {
                return
            }

            if (edgeOverScrollController.hasActiveFraction()) {
                val anchor = edgeOverScrollController.lockedEdgeOffset
                if (abs(nextXOffset - anchor) > abs(previousXOffset - anchor) + 0.01f) {
                    edgeOverScrollController.promoteToFastSnapBack()
                }
            }

            offsetDirection = swipeDirectionLock.onOffsetChanged(
                previousOffset = renderedXOffset,
                nextOffset = nextXOffset,
                isAtStaticPage = isAtStaticPageOffset(nextXOffset, calib)
            )

            if (isVisible) {
                if (isOffsetAnimationNeeded()) {
                    scheduleOffsetAnimationFrame()
                } else {
                    stopOffsetAnimation()
                    if (offsetChanged || yOffsetChanged) {
                        drawFrame(fromOffsetUpdate = true)
                    }
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent) {
            super.onTouchEvent(event)
            if (engineDestroyed) return
            val calib = repository.appState.value.calibration
            val wasTouchActive = edgeOverScrollController.isTouchActive
            val result = edgeOverScrollController.onTouchEvent(
                event = event,
                surfaceWidth = surfaceWidth,
                touchSlop = touchSlop,
                currentXOffset = currentXOffset,
                renderedXOffset = renderedXOffset,
                calibration = calib
            )
            if (edgeOverScrollController.isTouchActive) {
                if (edgeOverScrollController.isOverScrollLocked) {
                    renderedXOffset = edgeOverScrollController.lockedEdgeOffset
                    stopOffsetAnimation()
                    if (result.needsRedraw && isVisible) {
                        drawFrame(fromOffsetUpdate = true)
                    }
                }
            } else if (wasTouchActive) {
                if (!edgeOverScrollController.hasActiveFraction() &&
                    abs(currentXOffset - renderedXOffset) > OFFSET_EPSILON
                ) {
                    offsetDirection = swipeDirectionLock.onOffsetChanged(
                        previousOffset = renderedXOffset,
                        nextOffset = currentXOffset,
                        isAtStaticPage = isAtStaticPageOffset(currentXOffset, calib)
                    )
                }
                if (isVisible) {
                    if (isOffsetAnimationNeeded()) {
                        scheduleOffsetAnimationFrame()
                    } else if (result.needsRedraw) {
                        drawFrame(fromOffsetUpdate = true)
                    }
                }
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
                    renderedXOffset = currentXOffset
                    offsetDirection = swipeDirectionLock.reset()
                    edgeOverScrollController.reset()
                    stopOffsetAnimation()
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
                                    renderedXOffset = currentXOffset
                                    offsetDirection = swipeDirectionLock.reset()
                                    edgeOverScrollController.reset()
                                    stopOffsetAnimation()
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
                                if (!hasReceivedLauncherOffset ||
                                    (currentXOffset <= 0.001f && lastLauncherXOffsetStep <= 0f && calib.homePageIndex != 0)
                                ) {
                                    currentXOffset = calib.getHomeOffset()
                                    renderedXOffset = currentXOffset
                                    offsetDirection = swipeDirectionLock.reset()
                                    stopOffsetAnimation()
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

        private fun scheduleOffsetAnimationFrame() {
            if (!isVisible || animationFramePosted) return
            updateSurfaceFrameRate()
            animationFramePosted = true
            choreographer.postFrameCallback(animationFrameCallback)
        }

        private fun stopOffsetAnimation() {
            if (animationFramePosted) {
                choreographer.removeFrameCallback(animationFrameCallback)
            }
            animationFramePosted = false
            lastAnimationFrameTimeNanos = 0L
            nextAnimationFrameDeadlineNanos = 0L
            applySurfaceFrameRate(null)
        }

        private fun isOffsetAnimationNeeded(): Boolean =
            abs(currentXOffset - renderedXOffset) > OFFSET_EPSILON ||
                (!edgeOverScrollController.isTouchActive && edgeOverScrollController.hasActiveFraction())

        private fun drawSmoothedOffsetFrame(frameTimeNanos: Long) {
            if (!isVisible) {
                stopOffsetAnimation()
                return
            }

            val frameIntervalNanos =
                NANOS_PER_SECOND / repository.appState.value.animationFrameRate
            if (nextAnimationFrameDeadlineNanos == 0L) {
                nextAnimationFrameDeadlineNanos = frameTimeNanos
            }
            if (frameTimeNanos < nextAnimationFrameDeadlineNanos) {
                scheduleOffsetAnimationFrame()
                return
            }

            val elapsedNanos = if (lastAnimationFrameTimeNanos == 0L) {
                frameIntervalNanos
            } else {
                (frameTimeNanos - lastAnimationFrameTimeNanos).coerceAtLeast(1L)
            }
            val smoothingTimeNanos = maxOf(frameIntervalNanos, elapsedNanos) * 1.5
            val smoothingFraction = (
                1.0 - exp(-elapsedNanos.toDouble() / smoothingTimeNanos)
            ).toFloat()
            val wasResolvingEdgeOverScroll = !edgeOverScrollController.isTouchActive &&
                edgeOverScrollController.hasActiveFraction()
            if (wasResolvingEdgeOverScroll) {
                edgeOverScrollController.stepRelease(elapsedNanos, smoothingFraction)
            }
            // Follow the launcher offset in the same frame as the over-scroll release.
            // After a full reversal the outgoing page eases out via the fraction while the
            // incoming page starts entering immediately, so the two motions overlap instead
            // of leaving a still frame between them.
            if (!edgeOverScrollController.isTouchActive) {
                renderedXOffset += (currentXOffset - renderedXOffset) * smoothingFraction
                if (abs(currentXOffset - renderedXOffset) <= OFFSET_EPSILON) {
                    renderedXOffset = currentXOffset
                }
            }

            drawFrame(fromOffsetUpdate = true)
            lastAnimationFrameTimeNanos = frameTimeNanos
            val elapsedIntervals =
                (frameTimeNanos - nextAnimationFrameDeadlineNanos) / frameIntervalNanos + 1L
            nextAnimationFrameDeadlineNanos += elapsedIntervals * frameIntervalNanos

            if (isOffsetAnimationNeeded()) {
                scheduleOffsetAnimationFrame()
            } else {
                stopOffsetAnimation()
            }
        }

        private fun updateSurfaceFrameRate() {
            val frameRate = if (isVisible && isOffsetAnimationNeeded()) {
                repository.appState.value.animationFrameRate
            } else {
                null
            }
            applySurfaceFrameRate(frameRate)
        }

        private fun applySurfaceFrameRate(frameRate: Int?) {
            val surface = surfaceHolder?.surface ?: return
            if (!surface.isValid) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            val requestedFrameRate = frameRate ?: 0
            if (preferredSurfaceFrameRate == requestedFrameRate) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    requestedFrameRate.toFloat(),
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                )
            } else {
                surface.setFrameRate(
                    requestedFrameRate.toFloat(),
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
                )
            }
            preferredSurfaceFrameRate = requestedFrameRate
        }

        fun drawFrame(fromOffsetUpdate: Boolean = false) {
            if (engineDestroyed) return
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post { drawFrame(fromOffsetUpdate) }
                return
            }
            val holder = surfaceHolder ?: return
            val state = repository.appState.value
            val frameTimeNanos = System.nanoTime()
            val pagePosition = getPagePosition(renderedXOffset, state) +
                edgeOverScrollController.overScrollFraction * 0.5f
            val pageDelta = if (
                state.motionBlurEnabled &&
                isVisible &&
                fromOffsetUpdate &&
                lastMotionBlurPagePosition != null &&
                frameTimeNanos - lastMotionBlurFrameTimeNanos in 1..MAX_MOTION_BLUR_FRAME_GAP_NANOS
            ) {
                pagePosition - lastMotionBlurPagePosition!!
            } else {
                0f
            }
            val physicalBlurRadius = (
                abs(pageDelta) *
                    surfaceWidth *
                    MOTION_BLUR_SHUTTER_FRACTION *
                    state.motionBlurStrengthPercent.coerceIn(
                        0,
                        WallpaperAppState.MAX_MOTION_BLUR_STRENGTH_PERCENT
                    ) / 100f
                ).toInt().coerceAtMost(surfaceWidth / 4)
            val smearDirection = when {
                pageDelta > 0f -> 1
                pageDelta < 0f -> -1
                else -> 0
            }
            val shouldBlur = physicalBlurRadius > 0 && smearDirection != 0 &&
                state.motionBlurEnabled && isVisible && resumeFadeProgress >= 1f

            computePageTransition(renderedXOffset, state.calibration)
            val pageA = transitionPageA
            val pageB = transitionPageB
            val progress = mapOffsetProgress(transitionProgress, state)
            if (state.showFrameDebugIndicator && !frameDebugIndicatorEnabled) {
                frameDebugSequenceIndex = 0
            }
            frameDebugIndicatorEnabled = state.showFrameDebugIndicator
            frameDebugIndicatorDrawnForFrame = false
            val request = GpuFrameRequest(
                state = state,
                width = surfaceWidth,
                height = surfaceHeight,
                pageA = pageA,
                pageB = pageB,
                progress = progress,
                pagePosition = pagePosition,
                frameTimeNanos = frameTimeNanos,
                fromOffsetUpdate = fromOffsetUpdate,
                isVisible = isVisible,
                swipeForward = offsetDirection >= 0,
                resumeFadeProgress = resumeFadeProgress,
                motionBlurRadius = if (shouldBlur) physicalBlurRadius else 0,
                motionBlurDirection = if (shouldBlur) smearDirection else 0,
                dimAmount = calculateEffectiveDim(state, pageA, pageB, progress),
                receivedXOffset = receivedXOffset,
                receivedYOffset = receivedYOffset,
                density = resources.displayMetrics.density,
                frameDebugColor = frameDebugColors[frameDebugSequenceIndex],
                edgeOverScrollFraction = edgeOverScrollController.overScrollFraction
            )
            val renderer = getGpuRenderer()
            if (renderer != null && holder.surface.isValid) {
                renderer.render(
                    request = request,
                    onSubmitted = ::onGpuFrameSubmitted,
                    onFailure = ::onGpuRenderingFailed
                )
                return
            }
            drawSoftwareFrame(holder, request)
        }

        private fun drawSoftwareFrame(holder: SurfaceHolder, request: GpuFrameRequest) {
            val state = request.state
            var canvas: Canvas? = null
            var framePosted = false
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    if (request.resumeFadeProgress < 1f) {
                        canvas.drawColor(Color.BLACK)
                        val layer = canvas.saveLayerAlpha(
                            0f,
                            0f,
                            surfaceWidth.toFloat(),
                            surfaceHeight.toFloat(),
                            (request.resumeFadeProgress * 255).toInt().coerceIn(0, 255)
                        )
                        renderWallpaper(canvas, state)
                        canvas.restoreToCount(layer)
                    } else if (request.motionBlurRadius > 0) {
                        renderMotionBlurredWallpaper(
                            canvas = canvas,
                            state = state,
                            physicalBlurRadius = request.motionBlurRadius,
                            smearDirection = request.motionBlurDirection
                        )
                    } else {
                        renderWallpaper(canvas, state)
                    }
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
                if (framePosted) onFrameSubmitted(request, frameDebugIndicatorDrawnForFrame)
            }
        }

        private fun onGpuFrameSubmitted(request: GpuFrameRequest) {
            onFrameSubmitted(
                request,
                request.state.showFrameDebugIndicator && request.isVisible
            )
        }

        private fun onFrameSubmitted(request: GpuFrameRequest, debugIndicatorDrawn: Boolean) {
            if (engineDestroyed) return
            if (debugIndicatorDrawn) {
                frameDebugSequenceIndex = (frameDebugSequenceIndex + 1) % frameDebugColors.size
            }
            WallpaperDiagnosticsRecorder.recordFrameSubmitted(
                fromOffsetUpdate = request.fromOffsetUpdate,
                isVisible = request.isVisible
            )
            lastMotionBlurPagePosition = request.pagePosition
            lastMotionBlurFrameTimeNanos = request.frameTimeNanos
            if (!isOffsetAnimationNeeded()) {
                offsetDirection = swipeDirectionLock.onFrameSettled()
            }
        }

        private fun onGpuRenderingFailed() {
            if (engineDestroyed || gpuRenderingFailed) return
            gpuRenderingFailed = true
            gpuRenderer?.release()
            gpuRenderer = null
            drawFrame()
        }

        private fun renderMotionBlurredWallpaper(
            canvas: Canvas,
            state: WallpaperAppState,
            physicalBlurRadius: Int,
            smearDirection: Int
        ) {
            val bufferWidth = (surfaceWidth / MOTION_BLUR_DOWNSAMPLE_FACTOR).coerceAtLeast(1)
            val bufferHeight = (surfaceHeight / MOTION_BLUR_DOWNSAMPLE_FACTOR).coerceAtLeast(1)
            ensureMotionBlurBuffer(bufferWidth, bufferHeight)

            val bitmap = checkNotNull(motionBlurBitmap)
            val offscreenCanvas = checkNotNull(motionBlurCanvas)
            val saveCount = offscreenCanvas.save()
            try {
                offscreenCanvas.scale(
                    bufferWidth.toFloat() / surfaceWidth,
                    bufferHeight.toFloat() / surfaceHeight
                )
                renderWallpaper(offscreenCanvas, state, includeDebugIndicators = false)
            } finally {
                offscreenCanvas.restoreToCount(saveCount)
            }

            val scaledRadius = ceil(
                physicalBlurRadius.toFloat() * bufferWidth / surfaceWidth
            ).toInt().coerceAtLeast(1)
            motionBlur.apply(bitmap, scaledRadius, smearDirection)
            motionBlurDestination.set(0, 0, surfaceWidth, surfaceHeight)
            canvas.drawBitmap(bitmap, null, motionBlurDestination, paint)
            drawDebugIndicators(canvas, state)
        }

        private fun ensureMotionBlurBuffer(width: Int, height: Int) {
            if (motionBlurBitmap?.width == width && motionBlurBitmap?.height == height) return
            releaseMotionBlurBuffer()
            motionBlurBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            motionBlurCanvas = Canvas(checkNotNull(motionBlurBitmap))
        }

        private fun releaseMotionBlurBuffer() {
            motionBlurBitmap?.recycle()
            motionBlurBitmap = null
            motionBlurCanvas = null
            motionBlur.release()
            lastMotionBlurPagePosition = null
            lastMotionBlurFrameTimeNanos = 0L
        }

        private fun getPagePosition(offset: Float, state: WallpaperAppState): Float {
            val calibration = state.calibration
            val pageCount = calibration.pageCount.coerceAtLeast(1)
            if (pageCount <= 1) return 0f

            val anchors = calibration.customOffsets
            if (anchors.size != pageCount) {
                return offset.coerceIn(0f, 1f) * (pageCount - 1)
            }
            if (offset <= anchors.first()) return 0f
            if (offset >= anchors.last()) return (pageCount - 1).toFloat()

            for (index in 0 until pageCount - 1) {
                val start = anchors[index]
                val end = anchors[index + 1]
                if (offset <= end) {
                    val progress = ((offset - start) / (end - start).coerceAtLeast(0.0001f))
                        .coerceIn(0f, 1f)
                    return index + mapOffsetProgress(progress, state)
                }
            }
            return (pageCount - 1).toFloat()
        }

        private fun mapOffsetProgress(progress: Float, state: WallpaperAppState): Float =
            if (state.offsetEasingEnabled) {
                OffsetEasing.map(
                    progress,
                    state.offsetEasingLinearZonePercent,
                    state.offsetEasingStrength,
                    state.offsetEasingStyle
                )
            } else {
                progress
            }

        private fun renderWallpaper(
            canvas: Canvas,
            state: WallpaperAppState,
            includeDebugIndicators: Boolean = true
        ) {
            val calib = state.calibration
            val pageCount = calib.pageCount.coerceAtLeast(1)

            // Calculate page indices and transition progress based on calibrated anchors
            computePageTransition(renderedXOffset, calib)
            val pageA = transitionPageA
            val pageB = transitionPageB
            val progress = mapOffsetProgress(transitionProgress, state)

            val transitionSetting = state.transitions.getOrNull(pageA)
            val swipeForward = offsetDirection >= 0
            val transitionId = transitionSetting?.transitionId(swipeForward)
                ?: state.transitionType
            val renderer = TransitionRegistry.get(transitionId)
            val isEdgeOverScroll = pageA == pageB &&
                abs(edgeOverScrollController.overScrollFraction) > EdgeOverScrollController.FRACTION_EPSILON

            canvas.drawColor(android.graphics.Color.rgb(18, 22, 34))
            if (isEdgeOverScroll) {
                val edgeParallax = repository.getParallaxBitmap(pageA, surfaceWidth, surfaceHeight)
                val edgeBitmap = edgeParallax?.bitmap
                    ?: repository.getCroppedBitmap(pageA, surfaceWidth, surfaceHeight)
                drawEdgeOverScroll(
                    canvas = canvas,
                    bitmap = edgeBitmap,
                    leftTravelPx = edgeParallax?.leftTravelPx ?: 0,
                    rightTravelPx = edgeParallax?.rightTravelPx ?: 0,
                    fraction = edgeOverScrollController.overScrollFraction
                )
            } else {
                val usesParallax = renderer.supportsParallax
                val parallaxMotion = transitionSetting?.parallaxMotion(swipeForward)
                val revealRates = transitionSetting?.revealMotionRates(swipeForward)
                val slowImageMotionEnabled = if (renderer.supportsRevealImageMotion) {
                    false
                } else {
                    parallaxMotion?.slowImageMotionEnabled ?: false
                }
                val outgoingMotionRate = if (renderer.supportsRevealImageMotion) {
                    revealRates?.outgoingRate ?: 0f
                } else {
                    parallaxMotion?.outgoingMotionRate ?: 1f
                }
                val incomingMotionRate = if (renderer.supportsRevealImageMotion) {
                    revealRates?.incomingRate ?: 0f
                } else {
                    parallaxMotion?.incomingMotionRate ?: 1f
                }
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
                        slowImageMotionEnabled = slowImageMotionEnabled,
                        pageSwipeForward = swipeForward,
                        outgoingMotionRate = outgoingMotionRate,
                        incomingMotionRate = incomingMotionRate
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
            }

            val effectiveDim = calculateEffectiveDim(state, pageA, pageB, progress)
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

            if (includeDebugIndicators) drawDebugIndicators(canvas, state)
        }

        private fun drawEdgeOverScroll(
            canvas: Canvas,
            bitmap: Bitmap?,
            leftTravelPx: Int,
            rightTravelPx: Int,
            fraction: Float
        ) {
            if (bitmap == null) return
            edgeOverScrollDstRect.set(0, 0, surfaceWidth, surfaceHeight)
            if (bitmap.width <= surfaceWidth) {
                canvas.drawBitmap(bitmap, null, edgeOverScrollDstRect, paint)
                return
            }
            val sourceLeft = EdgeOverScrollScaling.computeSourceLeftPx(
                intensity = fraction,
                leftTravelPx = leftTravelPx,
                rightTravelPx = rightTravelPx,
                screenWidth = surfaceWidth
            ).toInt().coerceIn(0, (bitmap.width - surfaceWidth).coerceAtLeast(0))
            edgeOverScrollSrcRect.set(sourceLeft, 0, sourceLeft + surfaceWidth, bitmap.height)
            canvas.drawBitmap(bitmap, edgeOverScrollSrcRect, edgeOverScrollDstRect, paint)
        }

        private fun calculateEffectiveDim(
            state: WallpaperAppState,
            pageA: Int,
            pageB: Int,
            progress: Float
        ): Float {
            if (!ExtraDimDetector.isDimConditionActive(applicationContext, state.darken)) return 0f
            val isADark = state.pages.find { it.pageIndex == pageA }?.isAlreadyDark ?: false
            val isBDark = state.pages.find { it.pageIndex == pageB }?.isAlreadyDark ?: false
            return when {
                isADark && isBDark -> 0f
                isADark -> state.darken.dimFactor * progress
                isBDark -> state.darken.dimFactor * (1f - progress)
                else -> state.darken.dimFactor
            }
        }

        private fun drawDebugIndicators(canvas: Canvas, state: WallpaperAppState) {
            if (state.showOffsetDebugOverlay) {
                drawOffsetDebugOverlay(canvas)
            }
            if (state.showFrameDebugIndicator) {
                if (!frameDebugIndicatorEnabled) {
                    frameDebugSequenceIndex = 0
                }
                if (isVisible) {
                    drawFrameSequenceIndicator(canvas, frameDebugSequenceIndex)
                    frameDebugIndicatorDrawnForFrame = true
                }
            }
            frameDebugIndicatorEnabled = state.showFrameDebugIndicator
        }

        private fun drawFrameSequenceIndicator(canvas: android.graphics.Canvas, sequenceIndex: Int) {
            val density = resources.displayMetrics.density
            val size = 28f * density
            val margin = 20f * density
            val left = surfaceWidth - size - margin
            val top = margin
            val borderWidth = 2f * density
            frameDebugBorderPaint.strokeWidth = borderWidth
            canvas.drawRect(left, top, left + size, top + size, frameDebugBorderPaint)
            frameDebugPaint.color = frameDebugColors[sequenceIndex]
            canvas.drawRect(
                left + borderWidth,
                top + borderWidth,
                left + size - borderWidth,
                top + size - borderWidth,
                frameDebugPaint
            )
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

        private fun isAtStaticPageOffset(
            offset: Float,
            calib: CalibrationConfig
        ): Boolean {
            val count = calib.pageCount.coerceAtLeast(1)
            val anchors = if (calib.customOffsets.size >= count) {
                calib.customOffsets.take(count)
            } else {
                List(count) { index ->
                    if (count <= 1) 0f else index.toFloat() / (count - 1).toFloat()
                }
            }
            val segmentTolerance = anchors.zipWithNext()
                .minOfOrNull { (start, end) -> abs(end - start) }
                ?.let { it * STATIC_OFFSET_SEGMENT_TOLERANCE_RATIO }
                ?: 0f
            val tolerance = maxOf(STATIC_OFFSET_TOLERANCE, segmentTolerance)
            return anchors.any { abs(offset - it) <= tolerance }
        }

        private fun setPageTransition(pageA: Int, pageB: Int, progress: Float) {
            transitionPageA = pageA
            transitionPageB = pageB
            transitionProgress = progress
        }
    }
}
