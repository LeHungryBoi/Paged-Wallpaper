package com.example.service

import android.app.KeyguardManager
import android.app.WallpaperColors
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.ViewConfiguration
import androidx.annotation.RequiresApi
import com.example.darken.ExtraDimDetector
import com.example.data.model.CalibrationConfig
import com.example.data.model.WallpaperAppState
import com.example.diagnostics.WallpaperDiagnosticsRecorder
import com.example.diagnostics.WallpaperSignalOverlayWindow
import com.example.diagnostics.WallpaperSignalTracker
import com.example.data.repository.WallpaperRepository
import com.example.power.BatterySaverMonitor
import com.example.power.BatterySaverPolicy
import com.example.power.PowerSnapshot
import com.example.render.DarkenTransitionController
import com.example.render.EdgeOverScrollController
import com.example.render.EdgeOverScrollScaling
import com.example.render.EntryLifecycleGuard
import com.example.render.EntryTransitionController
import com.example.render.EntryTriggerDecision
import com.example.render.FrameSmoothing
import com.example.render.RenderFrameGate
import com.example.render.GpuFrameRequest
import com.example.render.HorizontalMotionBlur
import com.example.render.OffsetEasing
import com.example.render.SwipeDirectionLock
import com.example.render.WallpaperRenderBackend
import com.example.render.WallpaperRenderCoordinator
import com.example.transition.TransitionRegistry
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs
import kotlin.math.ceil

private const val OFFSET_EPSILON = 0.00005f
private const val STATIC_OFFSET_TOLERANCE = 0.002f
private const val STATIC_OFFSET_SEGMENT_TOLERANCE_RATIO = 0.02f
private const val NANOS_PER_SECOND = 1_000_000_000L
private const val MAX_ENTRY_STEP_NANOS = 50_000_000L
private const val MOTION_BLUR_DOWNSAMPLE_FACTOR = 2
private const val MOTION_BLUR_SHUTTER_FRACTION = 1.0f
private const val MAX_MOTION_BLUR_FRAME_GAP_NANOS = 200_000_000L

class MultiPageWallpaperService : WallpaperService() {

    private val activeEngines = CopyOnWriteArraySet<MultiPageEngine>()

    override fun onCreateEngine(): Engine {
        return MultiPageEngine()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isDarkMode = ExtraDimDetector.isSystemDarkThemeActive(newConfig)
        activeEngines.forEach { it.onSystemConfigurationChanged(isDarkMode) }
    }

    inner class MultiPageEngine : Engine() {

        private val repository by lazy { WallpaperRepository.getInstance(applicationContext) }
        private val mainHandler = Handler(Looper.getMainLooper())
        private var gpuRenderer: WallpaperRenderBackend? = null
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
        private var suppressedForRecents = false
        private val entryTransitionController = EntryTransitionController()
        private val signalTracker = WallpaperSignalTracker(
            zoomSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        )
        private val signalOverlayWindow by lazy {
            WallpaperSignalOverlayWindow(applicationContext)
        }
        private val choreographer by lazy { Choreographer.getInstance() }
        private var animationFramePosted = false
        private val renderFrameGate = RenderFrameGate()
        private var animationWaitingForRender = false
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

        private val darkenTransitionController = DarkenTransitionController()
        private var lastKnownDarkMode: Boolean? = null
        private var systemBroadcastReceiver: BroadcastReceiver? = null
        private var currentPowerSnapshot = PowerSnapshot()
        private var batterySaverMonitor: BatterySaverMonitor? = null
        private val entryLifecycleGuard = EntryLifecycleGuard()
        private var lastNotifiedColorKey: WallpaperColorHelper.ColorStateKey? = null

        private val repoChangeListener: () -> Unit = {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                handleRepositoryChange()
            } else {
                mainHandler.post { handleRepositoryChange() }
            }
        }

        private fun syncDarkenState(
            state: WallpaperAppState,
            animateIfVisible: Boolean,
            isDarkModeOverride: Boolean? = lastKnownDarkMode
        ) {
            val isBatterySaverActive = BatterySaverPolicy.isBatterySaverActive(
                config = state.batterySaver,
                power = currentPowerSnapshot
            )
            val shouldDarken = ExtraDimDetector.isDimConditionActive(
                context = applicationContext,
                config = state.darken,
                isDarkModeOverride = isDarkModeOverride,
                isBatterySaverActive = isBatterySaverActive
            )
            if (animateIfVisible && isVisible && surfaceWidth > 0 && surfaceHeight > 0) {
                darkenTransitionController.transitionTo(shouldDarken)
            } else {
                darkenTransitionController.snapTo(shouldDarken)
            }
        }

        fun onSystemConfigurationChanged(isDarkMode: Boolean) {
            if (engineDestroyed) return
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post { onSystemConfigurationChanged(isDarkMode) }
                return
            }
            lastKnownDarkMode = isDarkMode
            val state = repository.appState.value
            val prevTarget = darkenTransitionController.targetMix
            val prevMix = darkenTransitionController.currentMix
            syncDarkenState(
                state = state,
                animateIfVisible = isVisible,
                isDarkModeOverride = isDarkMode
            )
            val changed = abs(darkenTransitionController.targetMix - prevTarget) > 0.001f ||
                abs(darkenTransitionController.currentMix - prevMix) > 0.001f
            if (changed) {
                maybeNotifyWallpaperColorsChanged()
            }
            if (changed && isVisible) {
                drawFrame()
                if (isOffsetAnimationNeeded()) {
                    scheduleOffsetAnimationFrame()
                }
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
            }
            val state = repository.appState.value
            if (!state.resumeFadeEnabled) {
                entryTransitionController.reset()
                entryLifecycleGuard.onEntryDisabled()
            }
            syncDarkenState(state = state, animateIfVisible = isVisible)
            if (!isOffsetAnimationNeeded()) {
                stopOffsetAnimation()
            }
            if (!state.motionBlurEnabled) {
                releaseMotionBlurBuffer()
            }
            syncSignalOverlayWindow()
            nextAnimationFrameDeadlineNanos = 0L
            updateSurfaceFrameRate()
            maybeNotifyWallpaperColorsChanged()
            if (isVisible && isOffsetAnimationNeeded()) {
                scheduleOffsetAnimationFrame()
            } else {
                drawFrame()
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            activeEngines.add(this)
            setTouchEventsEnabled(true)
            WallpaperDiagnosticsRecorder.initialize(applicationContext)
            val state = repository.appState.value
            val calib = state.calibration
            currentXOffset = repository.getLastKnownOffsetOrHome()
            renderedXOffset = currentXOffset
            offsetDirection = swipeDirectionLock.reset()
            awaitingResumeOffset = calib.homePageIndex != 0
            lastKnownDarkMode = ExtraDimDetector.isSystemDarkThemeActive(applicationContext)
            syncDarkenState(state = state, animateIfVisible = false)
            repository.addChangeListener(repoChangeListener)
            batterySaverMonitor = BatterySaverMonitor(applicationContext) { snapshot ->
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    handlePowerSnapshotChanged(snapshot)
                } else {
                    mainHandler.post { handlePowerSnapshotChanged(snapshot) }
                }
            }.also { it.start() }
            registerSystemReceivers()
        }

        override fun onDestroy() {
            engineDestroyed = true
            renderFrameGate.cancel()
            animationWaitingForRender = false
            activeEngines.remove(this)
            gpuRenderer?.release()
            gpuRenderer = null
            super.onDestroy()
            stopOffsetAnimation()
            releaseMotionBlurBuffer()
            entryTransitionController.reset()
            darkenTransitionController.reset()
            entryLifecycleGuard.reset()
            signalOverlayWindow.detach()
            repository.removeChangeListener(repoChangeListener)
            batterySaverMonitor?.stop()
            batterySaverMonitor = null
            unregisterSystemReceivers()
        }

        private fun handlePowerSnapshotChanged(snapshot: PowerSnapshot) {
            if (engineDestroyed) return
            currentPowerSnapshot = snapshot
            nextAnimationFrameDeadlineNanos = 0L
            updateSurfaceFrameRate()
            val state = repository.appState.value
            if (state.darken.useBatterySaver) {
                val prevTarget = darkenTransitionController.targetMix
                syncDarkenState(state = state, animateIfVisible = isVisible)
                if (abs(darkenTransitionController.targetMix - prevTarget) > 0.001f) {
                    maybeNotifyWallpaperColorsChanged()
                    if (isVisible) {
                        drawFrame()
                        if (isOffsetAnimationNeeded()) {
                            scheduleOffsetAnimationFrame()
                        }
                    }
                }
            }
        }

        private fun getEffectiveAnimationFrameRate(
            state: WallpaperAppState = repository.appState.value
        ): Int = BatterySaverPolicy.effectiveFrameRate(
            normalFrameRate = state.animationFrameRate,
            config = state.batterySaver,
            power = currentPowerSnapshot
        )

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            val wasVisible = isVisible
            isVisible = visible
            val keyguardLocked = isKeyguardLockedOrNull()
            val screenInteractive = isScreenInteractiveOrNull()
            WallpaperDiagnosticsRecorder.recordVisibilityChanged(visible)
            signalTracker.recordVisibility(
                visible = visible,
                keyguardLocked = keyguardLocked,
                screenInteractive = screenInteractive
            )
            if (visible) {
                val state = repository.appState.value
                val calib = state.calibration
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
                    lastKnownDarkMode = ExtraDimDetector.isSystemDarkThemeActive(applicationContext)
                    syncDarkenState(state = state, animateIfVisible = false)
                    if (state.darken.isEnabled) {
                        repository.prewarmDarkenedCache(surfaceWidth, surfaceHeight)
                    }
                    val decision = entryLifecycleGuard.onVisibilityChanged(
                        visible = true,
                        wasVisible = false,
                        entryEnabled = state.resumeFadeEnabled,
                        surfaceReady = isSurfaceReadyForEntry(),
                        keyguardLocked = keyguardLocked,
                        screenInteractive = screenInteractive,
                        nowNanos = System.nanoTime()
                    )
                    applyEntryDecision(decision, state)
                } else {
                    syncDarkenState(state = state, animateIfVisible = true)
                }
                syncSignalOverlayWindow()
                maybeNotifyWallpaperColorsChanged()
                drawFrame()
                if (isOffsetAnimationNeeded()) {
                    scheduleOffsetAnimationFrame()
                }
            } else {
                suppressedForRecents = false
                edgeOverScrollController.reset()
                stopOffsetAnimation()
                releaseMotionBlurBuffer()
                entryLifecycleGuard.onVisibilityChanged(
                    visible = false,
                    wasVisible = wasVisible,
                    entryEnabled = false,
                    surfaceReady = false,
                    keyguardLocked = keyguardLocked,
                    screenInteractive = screenInteractive,
                    nowNanos = System.nanoTime()
                )
                entryTransitionController.reset()
                darkenTransitionController.snapTo(darkenTransitionController.targetMix >= 0.5f)
                syncSignalOverlayWindow()
            }
        }

        override fun onZoomChanged(zoom: Float) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                super.onZoomChanged(zoom)
            }
            val changed = signalTracker.recordZoom(zoom)
            if (changed) {
                syncSignalOverlayWindow()
                if (isVisible && repository.appState.value.showSignalDebugOverlay && !signalOverlayWindow.isAttached) {
                    drawFrame()
                }
            }
        }

        private fun isSurfaceReadyForEntry(): Boolean =
            surfaceHolder?.surface?.isValid == true && surfaceWidth > 0 && surfaceHeight > 0

        private fun applyEntryDecision(decision: EntryTriggerDecision, state: WallpaperAppState) {
            when (decision) {
                EntryTriggerDecision.TRIGGER_NOW -> triggerEntryTransitionNow(state)
                EntryTriggerDecision.RESET -> entryTransitionController.reset()
                EntryTriggerDecision.DEFER_UNTIL_SURFACE_READY,
                EntryTriggerDecision.NONE -> Unit
            }
        }

        private fun triggerEntryTransitionNow(state: WallpaperAppState) {
            if (!state.resumeFadeEnabled) {
                entryTransitionController.reset()
                entryLifecycleGuard.onEntryDisabled()
                return
            }
            entryTransitionController.trigger(state.entryDurationMs)
            lastAnimationFrameTimeNanos = 0L
            nextAnimationFrameDeadlineNanos = 0L
        }

        private fun syncSignalOverlayWindow(precomputedLines: List<String>? = null) {
            if (engineDestroyed) return
            val state = repository.appState.value
            if (!state.showSignalDebugOverlay) {
                signalOverlayWindow.detach()
                return
            }
            val lines = precomputedLines ?: run {
                val entryState = entryTransitionController.evaluate(
                    enabled = state.resumeFadeEnabled,
                    zoomEnabled = state.entryZoomEnabled,
                    zoomStartScale = state.entryZoomStartScale,
                    blackFadeEnabled = state.entryBlackFadeEnabled,
                    blackFadeMaxAlpha = state.entryBlackFadeMaxAlpha,
                    blurEnabled = state.entryBlurEnabled,
                    blurMaxMix = state.entryBlurStrength
                )
                signalTracker.buildOverlayLines(entryState.progress, entryState.isActive)
            }
            signalOverlayWindow.update(true, lines)
        }

        private fun isKeyguardLockedOrNull(): Boolean? = try {
            (applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
                ?.isKeyguardLocked
        } catch (_: Exception) {
            null
        }

        private fun isScreenInteractiveOrNull(): Boolean? = try {
            (applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.isInteractive
        } catch (_: Exception) {
            null
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            getGpuRenderer()?.onSurfaceChanged(holder.surface, surfaceWidth, surfaceHeight)
            val state = repository.appState.value
            if (isSurfaceReadyForEntry() && entryLifecycleGuard.onSurfaceReady(isVisible, state.resumeFadeEnabled)) {
                triggerEntryTransitionNow(state)
            }
            if (isVisible && surfaceWidth > 0 && surfaceHeight > 0) {
                drawFrame()
                if (isOffsetAnimationNeeded()) {
                    scheduleOffsetAnimationFrame()
                }
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            repository.updateRenderDimensions(width, height)
            getGpuRenderer()?.onSurfaceChanged(holder.surface, width, height)
            val state = repository.appState.value
            if (isSurfaceReadyForEntry() && entryLifecycleGuard.onSurfaceReady(isVisible, state.resumeFadeEnabled)) {
                triggerEntryTransitionNow(state)
            }
            preferredSurfaceFrameRate = null
            updateSurfaceFrameRate()
            drawFrame()
            if (isVisible && isOffsetAnimationNeeded()) {
                scheduleOffsetAnimationFrame()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            stopOffsetAnimation()
            renderFrameGate.cancel()
            animationWaitingForRender = false
            releaseMotionBlurBuffer()
            gpuRenderer?.onSurfaceDestroyed()
            super.onSurfaceDestroyed(holder)
        }

        @RequiresApi(Build.VERSION_CODES.O_MR1)
        override fun onComputeColors(): WallpaperColors? {
            if (engineDestroyed) return null
            val state = repository.appState.value
            val pageIndex = WallpaperColorHelper.nearestPageIndex(renderedXOffset, state.calibration)
            val page = state.pages.find { it.pageIndex == pageIndex }
            val isDarkened = darkenTransitionController.targetMix >= 0.5f &&
                repository.shouldDarkenPage(page, state.darken)
            val reqW = surfaceWidth.coerceAtLeast(1)
            val reqH = surfaceHeight.coerceAtLeast(1)
            val bmp = repository.getCroppedBitmap(pageIndex, reqW, reqH, darkened = isDarkened)
            return WallpaperColorHelper.computeWallpaperColors(
                bitmap = bmp,
                page = page,
                isDarkened = isDarkened
            )
        }

        private fun maybeNotifyWallpaperColorsChanged() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1 || engineDestroyed) return
            val state = repository.appState.value
            val isSettled = !isOffsetAnimationNeeded() &&
                isAtStaticPageOffset(renderedXOffset, state.calibration)
            if (!isSettled) return
            val pageIndex = WallpaperColorHelper.nearestPageIndex(renderedXOffset, state.calibration)
            val page = state.pages.find { it.pageIndex == pageIndex }
            val isDarkened = darkenTransitionController.targetMix >= 0.5f &&
                repository.shouldDarkenPage(page, state.darken)
            val key = WallpaperColorHelper.ColorStateKey(
                pageIndex = pageIndex,
                isAlreadyDark = page?.isAlreadyDark == true,
                isDarkened = isDarkened,
                bitmapGeneration = repository.bitmapGeneration,
                darkenedGeneration = repository.darkenedBitmapGeneration
            )
            if (key != lastNotifiedColorKey) {
                lastNotifiedColorKey = key
                try {
                    notifyColorsChanged()
                } catch (e: Exception) {
                    Log.w("MultiPageWallpaper", "Failed to notify wallpaper colors", e)
                }
            }
        }

        private fun getGpuRenderer(): WallpaperRenderBackend? {
            if (gpuRenderingFailed || engineDestroyed) return null
            return gpuRenderer ?: WallpaperRenderCoordinator(repository).also { gpuRenderer = it }
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
            val loggedOffsetSignal = signalTracker.recordOffsetSignal(xOffset, xOffsetStep, isVisible)
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

            // While edge overscroll is locked, ignore misleading launcher offsets so they
            // cannot pull the neighboring page in during overscroll or upon release.
            if (edgeOverScrollController.isOverScrollLocked) {
                return
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

            if (edgeOverScrollController.hasActiveFraction()) {
                val anchor = edgeOverScrollController.lockedEdgeOffset
                val switchConfirmThreshold = (edgeSegmentSpan(anchor, calib) * 0.25f)
                    .coerceAtLeast(0.05f)
                val isConfirmedMultiStepSwitch = abs(previousXOffset - anchor) > 0.01f &&
                    abs(nextXOffset - anchor) > abs(previousXOffset - anchor) + 0.01f &&
                    abs(nextXOffset - anchor) >= switchConfirmThreshold
                if (isConfirmedMultiStepSwitch) {
                    edgeOverScrollController.promoteToFastSnapBack()
                } else if (edgeOverScrollController.snapBackMode == EdgeOverScrollController.SnapBackMode.SLOW) {
                    currentXOffset = anchor
                    if (isVisible && isOffsetAnimationNeeded()) {
                        scheduleOffsetAnimationFrame()
                    }
                    return
                }
            }

            updateSwipeDirectionLock(
                previousOffset = previousXOffset,
                nextOffset = nextXOffset,
                calibration = calib,
                state = repository.appState.value
            )

            if (offsetChanged && xOffsetStep > 0f) {
                suppressedForRecents = false
                entryLifecycleGuard.onUnlockedHomeInteraction(
                    isVisible = isVisible,
                    keyguardLocked = isKeyguardLockedOrNull(),
                    screenInteractive = isScreenInteractiveOrNull()
                )
                if (isVisible && entryTransitionController.isActive) {
                    entryTransitionController.interrupt()
                }
            }

            if (loggedOffsetSignal) {
                syncSignalOverlayWindow()
            }

            if (isVisible) {
                if (isOffsetAnimationNeeded()) {
                    scheduleOffsetAnimationFrame()
                } else {
                    stopOffsetAnimation()
                    if (offsetChanged || yOffsetChanged ||
                        (loggedOffsetSignal && repository.appState.value.showSignalDebugOverlay && !signalOverlayWindow.isAttached)
                    ) {
                        drawFrame(fromOffsetUpdate = offsetChanged || yOffsetChanged)
                    }
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent) {
            super.onTouchEvent(event)
            WallpaperDiagnosticsRecorder.recordTouchEvent(
                action = event.actionMasked,
                x = event.x,
                y = event.y,
                xOffset = currentXOffset,
                edgeOverScrollFraction = edgeOverScrollController.overScrollFraction
            )
            if (engineDestroyed || !isVisible) return
            if (suppressedForRecents) {
                if (edgeOverScrollController.isTouchActive || edgeOverScrollController.hasActiveFraction()) {
                    edgeOverScrollController.reset()
                    drawFrame()
                }
                return
            }
            entryLifecycleGuard.onUnlockedHomeInteraction(
                isVisible = isVisible,
                keyguardLocked = isKeyguardLockedOrNull(),
                screenInteractive = isScreenInteractiveOrNull()
            )
            val calib = repository.appState.value.calibration
            val wasTouchActive = edgeOverScrollController.isTouchActive
            val result = edgeOverScrollController.onTouchEvent(
                event = event,
                surfaceWidth = surfaceWidth,
                touchSlop = touchSlop,
                currentXOffset = currentXOffset,
                renderedXOffset = renderedXOffset,
                calibration = calib,
                surfaceHeight = surfaceHeight
            )
            if (edgeOverScrollController.isTouchActive) {
                if (edgeOverScrollController.isOverScrollLocked) {
                    currentXOffset = edgeOverScrollController.lockedEdgeOffset
                    renderedXOffset = edgeOverScrollController.lockedEdgeOffset
                    if (entryTransitionController.isActive) {
                        entryTransitionController.interrupt()
                        scheduleOffsetAnimationFrame()
                    } else {
                        stopOffsetAnimation()
                    }
                    if (result.needsRedraw && isVisible) {
                        drawFrame(fromOffsetUpdate = true)
                    }
                }
            } else if (wasTouchActive) {
                val keepEdgeLocked = edgeOverScrollController.hasActiveFraction() &&
                    edgeOverScrollController.snapBackMode == EdgeOverScrollController.SnapBackMode.SLOW
                if (keepEdgeLocked) {
                    currentXOffset = edgeOverScrollController.lockedEdgeOffset
                    renderedXOffset = edgeOverScrollController.lockedEdgeOffset
                } else if (abs(currentXOffset - renderedXOffset) > OFFSET_EPSILON) {
                    if (entryTransitionController.isActive) {
                        entryTransitionController.interrupt()
                    }
                    updateSwipeDirectionLock(
                        previousOffset = renderedXOffset,
                        nextOffset = currentXOffset,
                        calibration = calib,
                        state = repository.appState.value
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
            val extrasSummary = extras?.keySet()
                ?.takeIf { it.isNotEmpty() }
                ?.take(3)
                ?.joinToString(",") { key -> "$key=${extras.get(key)}" }
            signalTracker.recordCommand(action, extrasSummary)
            if (action == "android.home") {
                suppressedForRecents = false
                entryLifecycleGuard.onUnlockedHomeInteraction(
                    isVisible = isVisible,
                    keyguardLocked = isKeyguardLockedOrNull(),
                    screenInteractive = isScreenInteractiveOrNull()
                )
                val calib = repository.appState.value.calibration
                currentXOffset = calib.getHomeOffset()
                renderedXOffset = currentXOffset
                offsetDirection = swipeDirectionLock.reset()
                edgeOverScrollController.reset()
                if (!entryTransitionController.isActive && !darkenTransitionController.isActive) {
                    stopOffsetAnimation()
                }
                awaitingResumeOffset = calib.homePageIndex != 0
                recoveringFromZeroOffset = false
                sawZeroOffsetWhileHidden = false
                repository.saveLastKnownOffset(currentXOffset)
                syncSignalOverlayWindow()
                if (isVisible) {
                    drawFrame()
                    if (isOffsetAnimationNeeded()) {
                        scheduleOffsetAnimationFrame()
                    }
                }
                return super.onCommand(action, x, y, z, extras, resultRequested)
            }
            syncSignalOverlayWindow()
            if (isVisible && repository.appState.value.showSignalDebugOverlay && !signalOverlayWindow.isAttached) {
                drawFrame()
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        private fun registerSystemReceivers() {
            try {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                    addAction(Intent.ACTION_USER_PRESENT)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_TIME_TICK)
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                    addAction(Intent.ACTION_CONFIGURATION_CHANGED)
                }
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        when (intent?.action) {
                            Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> {
                                val reason = intent.getStringExtra("reason")
                                signalTracker.recordSystemDialogClose(reason)
                                if (reason != null && reason.contains("recent", ignoreCase = true)) {
                                    suppressedForRecents = true
                                    edgeOverScrollController.reset()
                                }
                                if (reason == "homekey") {
                                    suppressedForRecents = false
                                    entryLifecycleGuard.onUnlockedHomeInteraction(
                                        isVisible = isVisible,
                                        keyguardLocked = isKeyguardLockedOrNull(),
                                        screenInteractive = isScreenInteractiveOrNull()
                                    )
                                    // User swiped or tapped home button to return to launcher
                                    val calib = repository.appState.value.calibration
                                    currentXOffset = calib.getHomeOffset()
                                    renderedXOffset = currentXOffset
                                    offsetDirection = swipeDirectionLock.reset()
                                    edgeOverScrollController.reset()
                                    if (!entryTransitionController.isActive && !darkenTransitionController.isActive) {
                                        stopOffsetAnimation()
                                    }
                                    awaitingResumeOffset = calib.homePageIndex != 0
                                    recoveringFromZeroOffset = false
                                    sawZeroOffsetWhileHidden = false
                                    repository.saveLastKnownOffset(currentXOffset)
                                    syncSignalOverlayWindow()
                                    if (isVisible) {
                                        drawFrame()
                                        if (isOffsetAnimationNeeded()) {
                                            scheduleOffsetAnimationFrame()
                                        }
                                    }
                                } else {
                                    syncSignalOverlayWindow()
                                    if (isVisible && repository.appState.value.showSignalDebugOverlay && !signalOverlayWindow.isAttached) {
                                        drawFrame()
                                    }
                                }
                            }
                            Intent.ACTION_USER_PRESENT -> {
                                // Device unlocked
                                suppressedForRecents = false
                                signalTracker.recordUserPresent(isKeyguardLockedOrNull())
                                val state = repository.appState.value
                                val calib = state.calibration
                                if (!hasReceivedLauncherOffset ||
                                    (currentXOffset <= 0.001f && lastLauncherXOffsetStep <= 0f && calib.homePageIndex != 0)
                                ) {
                                    currentXOffset = calib.getHomeOffset()
                                    renderedXOffset = currentXOffset
                                    offsetDirection = swipeDirectionLock.reset()
                                    if (!entryTransitionController.isActive && !darkenTransitionController.isActive) {
                                        stopOffsetAnimation()
                                    }
                                    repository.saveLastKnownOffset(currentXOffset)
                                }
                                val decision = entryLifecycleGuard.onUserPresent(
                                    isVisible = isVisible,
                                    entryEnabled = state.resumeFadeEnabled,
                                    surfaceReady = isSurfaceReadyForEntry(),
                                    keyguardLocked = isKeyguardLockedOrNull(),
                                    screenInteractive = isScreenInteractiveOrNull()
                                )
                                applyEntryDecision(decision, state)
                                syncDarkenState(state = state, animateIfVisible = false)
                                syncSignalOverlayWindow()
                                if (isVisible) {
                                    drawFrame()
                                    if (isOffsetAnimationNeeded()) {
                                        scheduleOffsetAnimationFrame()
                                    }
                                }
                            }
                            Intent.ACTION_SCREEN_ON -> {
                                val keyguardLocked = isKeyguardLockedOrNull()
                                signalTracker.recordScreenPower(true, keyguardLocked)
                                val state = repository.appState.value
                                val prevDarkMix = darkenTransitionController.currentMix
                                lastKnownDarkMode = ExtraDimDetector.isSystemDarkThemeActive(applicationContext)
                                syncDarkenState(state = state, animateIfVisible = false)
                                val darkChanged = abs(darkenTransitionController.currentMix - prevDarkMix) > 0.001f
                                val decision = entryLifecycleGuard.onScreenOn(
                                    isVisible = isVisible,
                                    entryEnabled = state.resumeFadeEnabled,
                                    surfaceReady = isSurfaceReadyForEntry(),
                                    keyguardLocked = keyguardLocked,
                                    nowNanos = System.nanoTime()
                                )
                                applyEntryDecision(decision, state)
                                syncSignalOverlayWindow()
                                if (isVisible) {
                                    if (entryTransitionController.isActive || darkChanged) {
                                        drawFrame()
                                        if (isOffsetAnimationNeeded()) {
                                            scheduleOffsetAnimationFrame()
                                        }
                                    } else if (state.showSignalDebugOverlay && !signalOverlayWindow.isAttached) {
                                        drawFrame()
                                    }
                                }
                            }
                            Intent.ACTION_SCREEN_OFF -> {
                                entryLifecycleGuard.onScreenOff()
                                signalTracker.recordScreenPower(false, isKeyguardLockedOrNull())
                                syncSignalOverlayWindow()
                            }
                            Intent.ACTION_TIME_TICK,
                            Intent.ACTION_TIME_CHANGED,
                            Intent.ACTION_TIMEZONE_CHANGED -> {
                                val state = repository.appState.value
                                if (state.darken.useSchedule) {
                                    val prevTarget = darkenTransitionController.targetMix
                                    syncDarkenState(state = state, animateIfVisible = isVisible)
                                    if (isVisible && abs(darkenTransitionController.targetMix - prevTarget) > 0.001f) {
                                        drawFrame()
                                        if (isOffsetAnimationNeeded()) {
                                            scheduleOffsetAnimationFrame()
                                        }
                                    }
                                }
                            }
                            Intent.ACTION_CONFIGURATION_CHANGED -> {
                                onSystemConfigurationChanged(
                                    ExtraDimDetector.isSystemDarkThemeActive(applicationContext)
                                )
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
                (!edgeOverScrollController.isTouchActive && edgeOverScrollController.hasActiveFraction()) ||
                (entryTransitionController.isActive && repository.appState.value.resumeFadeEnabled) ||
                darkenTransitionController.isActive

        private fun drawSmoothedOffsetFrame(frameTimeNanos: Long) {
            if (!isVisible) {
                stopOffsetAnimation()
                return
            }
            if (renderFrameGate.isInFlight) {
                animationWaitingForRender = true
                return
            }

            val frameIntervalNanos =
                NANOS_PER_SECOND / getEffectiveAnimationFrameRate()
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
            val smoothingFraction = FrameSmoothing.fraction(elapsedNanos)
            val wasResolvingEdgeOverScroll = !edgeOverScrollController.isTouchActive &&
                edgeOverScrollController.hasActiveFraction()
            if (wasResolvingEdgeOverScroll) {
                edgeOverScrollController.stepRelease(elapsedNanos, smoothingFraction)
            }
            var offsetChangedInStep = false
            val holdEdgeAnchor = edgeOverScrollController.hasActiveFraction() &&
                edgeOverScrollController.snapBackMode == EdgeOverScrollController.SnapBackMode.SLOW
            if (holdEdgeAnchor) {
                // Without a full reversal, keep renderedXOffset pinned on the edge page
                // so the neighboring page never enters while overscroll eases back.
                renderedXOffset = edgeOverScrollController.lockedEdgeOffset
            } else if (!edgeOverScrollController.isTouchActive) {
                // Advance renderedXOffset in the same frame so outgoing overscroll recovery
                // overlaps with the incoming page transition instead of pausing at the edge.
                val prevRendered = renderedXOffset
                renderedXOffset += (currentXOffset - renderedXOffset) * smoothingFraction
                if (abs(currentXOffset - renderedXOffset) <= OFFSET_EPSILON) {
                    renderedXOffset = currentXOffset
                }
                offsetChangedInStep = abs(renderedXOffset - prevRendered) > 0f
            }

            WallpaperDiagnosticsRecorder.recordSmoothingStep(
                targetXOffset = currentXOffset,
                renderedXOffset = renderedXOffset,
                smoothingFraction = smoothingFraction,
                elapsedNanos = elapsedNanos
            )

            if (entryTransitionController.isActive) {
                if (repository.appState.value.resumeFadeEnabled) {
                    val clampedStepNanos = elapsedNanos.coerceAtMost(MAX_ENTRY_STEP_NANOS)
                    entryTransitionController.step(clampedStepNanos)
                } else {
                    entryTransitionController.reset()
                    entryLifecycleGuard.onEntryDisabled()
                }
            }

            if (darkenTransitionController.isActive) {
                val clampedStepNanos = elapsedNanos.coerceAtMost(MAX_ENTRY_STEP_NANOS)
                darkenTransitionController.step(clampedStepNanos)
            }

            drawFrame(fromOffsetUpdate = offsetChangedInStep || wasResolvingEdgeOverScroll)
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
                getEffectiveAnimationFrameRate()
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
            if (!holder.surface.isValid) return
            val frameId = renderFrameGate.beginFrame(fromOffsetUpdate)
            if (frameId == null) {
                WallpaperDiagnosticsRecorder.recordFrameCoalesced(fromOffsetUpdate)
                return
            }
            val state = repository.appState.value
            val frameTimeNanos = System.nanoTime()
            val continuousPagePosition = getPagePosition(renderedXOffset, state)
            val pagePosition = continuousPagePosition +
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
                state.motionBlurEnabled && isVisible

            val entryState = entryTransitionController.evaluate(
                enabled = state.resumeFadeEnabled,
                zoomEnabled = state.entryZoomEnabled,
                zoomStartScale = state.entryZoomStartScale,
                blackFadeEnabled = state.entryBlackFadeEnabled,
                blackFadeMaxAlpha = state.entryBlackFadeMaxAlpha,
                blurEnabled = state.entryBlurEnabled,
                blurMaxMix = state.entryBlurStrength
            )
            val signalLines = if (state.showSignalDebugOverlay) {
                signalTracker.buildOverlayLines(entryState.progress, entryState.isActive)
            } else {
                emptyList()
            }
            syncSignalOverlayWindow(signalLines)
            val wallpaperSignalLines = if (state.showSignalDebugOverlay && !signalOverlayWindow.isAttached) {
                signalLines
            } else {
                emptyList()
            }

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
                continuousPagePosition = continuousPagePosition,
                frameId = frameId,
                frameTimeNanos = frameTimeNanos,
                fromOffsetUpdate = fromOffsetUpdate,
                isVisible = isVisible,
                swipeForward = offsetDirection >= 0,
                entryScale = entryState.scale,
                entryBlackFadeAlpha = entryState.blackFadeAlpha,
                entryBlurMix = entryState.blurMix,
                motionBlurRadius = if (shouldBlur) physicalBlurRadius else 0,
                motionBlurDirection = if (shouldBlur) smearDirection else 0,
                darkenMix = darkenTransitionController.currentMix,
                receivedXOffset = receivedXOffset,
                receivedYOffset = receivedYOffset,
                signalOverlayLines = wallpaperSignalLines,
                density = resources.displayMetrics.density,
                frameDebugColor = frameDebugColors[frameDebugSequenceIndex],
                edgeOverScrollFraction = edgeOverScrollController.overScrollFraction
            )
            WallpaperDiagnosticsRecorder.recordFrameRequested(
                frameId = frameId,
                requestTimeNanos = frameTimeNanos,
                fromOffsetUpdate = fromOffsetUpdate,
                xOffset = renderedXOffset,
                pageA = pageA,
                pageB = pageB,
                progress = progress,
                edgeOverScrollFraction = edgeOverScrollController.overScrollFraction,
                motionBlurRadius = request.motionBlurRadius
            )
            val renderer = getGpuRenderer()
            if (renderer != null) {
                try {
                    renderer.render(
                        request = request,
                        onSubmitted = { submittedRequest ->
                            onGpuFrameSubmitted(submittedRequest, frameId)
                        },
                        onFailure = { onGpuRenderingFailed(frameId) },
                        onRetryNeeded = { retryRenderFrame(frameId, fromOffsetUpdate) }
                    )
                } catch (error: Throwable) {
                    Log.e("MultiPageWallpaper", "Unable to enqueue GPU frame", error)
                    onGpuRenderingFailed(frameId)
                }
                return
            }
            drawSoftwareFrame(holder, request, frameId)
        }

        private fun drawSoftwareFrame(holder: SurfaceHolder, request: GpuFrameRequest, frameId: Long) {
            val state = request.state
            var canvas: Canvas? = null
            var framePosted = false
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    if (request.motionBlurRadius > 0) {
                        renderMotionBlurredWallpaper(
                            canvas = canvas,
                            state = state,
                            physicalBlurRadius = request.motionBlurRadius,
                            smearDirection = request.motionBlurDirection,
                            request = request
                        )
                    } else {
                        renderWallpaper(canvas, state, request = request)
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
                if (framePosted) {
                    onFrameSubmitted(request, frameDebugIndicatorDrawnForFrame, frameId)
                } else {
                    finishRenderFrame(frameId)
                }
            }
        }

        private fun onGpuFrameSubmitted(request: GpuFrameRequest, frameId: Long) {
            onFrameSubmitted(
                request,
                request.state.showFrameDebugIndicator && request.isVisible,
                frameId
            )
        }

        private fun onFrameSubmitted(
            request: GpuFrameRequest,
            debugIndicatorDrawn: Boolean,
            frameId: Long
        ) {
            if (engineDestroyed || !renderFrameGate.isCurrent(frameId)) return
            if (debugIndicatorDrawn) {
                frameDebugSequenceIndex = (frameDebugSequenceIndex + 1) % frameDebugColors.size
            }
            WallpaperDiagnosticsRecorder.recordFrameSubmitted(
                fromOffsetUpdate = request.fromOffsetUpdate,
                isVisible = request.isVisible,
                frameId = frameId
            )
            lastMotionBlurPagePosition = request.pagePosition
            lastMotionBlurFrameTimeNanos = request.frameTimeNanos
            if (!isOffsetAnimationNeeded()) {
                offsetDirection = swipeDirectionLock.onFrameSettled()
                maybeNotifyWallpaperColorsChanged()
            }
            finishRenderFrame(frameId)
        }

        private fun finishRenderFrame(frameId: Long) {
            if (engineDestroyed) return
            val completion = renderFrameGate.completeFrame(frameId) ?: return
            val deferred = completion.deferredRedraw
            if (deferred != null) {
                drawFrame(fromOffsetUpdate = deferred.fromOffsetUpdate)
            } else if (animationWaitingForRender) {
                animationWaitingForRender = false
                if (isVisible && isOffsetAnimationNeeded()) {
                    scheduleOffsetAnimationFrame()
                }
            }
        }

        private fun retryRenderFrame(frameId: Long, fromOffsetUpdate: Boolean) {
            if (engineDestroyed) return
            val completion = renderFrameGate.completeFrame(frameId)
            val deferredOffsetUpdate = completion?.deferredRedraw?.fromOffsetUpdate == true
            if (completion != null) {
                if (completion.deferredRedraw != null || isVisible) {
                    drawFrame(fromOffsetUpdate || deferredOffsetUpdate)
                } else if (animationWaitingForRender) {
                    animationWaitingForRender = false
                    if (isVisible && isOffsetAnimationNeeded()) scheduleOffsetAnimationFrame()
                }
            } else if (isVisible) {
                // This can be a delayed asset retry from an already-submitted frame. If another
                // frame is active, drawFrame coalesces this into its deferred redraw instead.
                drawFrame(fromOffsetUpdate)
            }
        }

        private fun onGpuRenderingFailed(frameId: Long) {
            WallpaperDiagnosticsRecorder.recordFrameFailure(frameId, "gpu_renderer_failed")
            if (engineDestroyed || gpuRenderingFailed || !renderFrameGate.isCurrent(frameId)) return
            gpuRenderingFailed = true
            gpuRenderer?.release()
            gpuRenderer = null
            val completion = renderFrameGate.completeFrame(frameId) ?: return
            drawFrame(completion.deferredRedraw?.fromOffsetUpdate ?: false)
        }

        private fun renderMotionBlurredWallpaper(
            canvas: Canvas,
            state: WallpaperAppState,
            physicalBlurRadius: Int,
            smearDirection: Int,
            request: GpuFrameRequest? = null
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
                renderWallpaper(
                    offscreenCanvas,
                    state,
                    includeDebugIndicators = false,
                    request = request?.copy(entryBlackFadeAlpha = 0f)
                )
            } finally {
                offscreenCanvas.restoreToCount(saveCount)
            }

            val scaledRadius = ceil(
                physicalBlurRadius.toFloat() * bufferWidth / surfaceWidth
            ).toInt().coerceAtLeast(1)
            motionBlur.apply(bitmap, scaledRadius, smearDirection)
            motionBlurDestination.set(0, 0, surfaceWidth, surfaceHeight)
            canvas.drawBitmap(bitmap, null, motionBlurDestination, paint)
            if (request != null && request.entryBlackFadeAlpha > 0.004f) {
                dimPaint.color = Color.BLACK
                dimPaint.alpha = (request.entryBlackFadeAlpha.coerceIn(0f, 1f) * 255).toInt()
                canvas.drawRect(0f, 0f, surfaceWidth.toFloat(), surfaceHeight.toFloat(), dimPaint)
            }
            drawDebugIndicators(canvas, state, request?.signalOverlayLines ?: emptyList())
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
            includeDebugIndicators: Boolean = true,
            request: GpuFrameRequest? = null
        ) {
            val calib = state.calibration
            val pageCount = calib.pageCount.coerceAtLeast(1)

            // Calculate page indices and transition progress based on calibrated anchors
            computePageTransition(renderedXOffset, calib)
            val pageA = transitionPageA
            val pageB = transitionPageB
            val progress = mapOffsetProgress(transitionProgress, state)

            val entryScale = (request?.entryScale ?: 1f).coerceIn(1f, 2f)
            val isZoomedEntry = entryScale > 1.0005f
            canvas.drawColor(Color.rgb(18, 22, 34))
            val sceneSaveCount = if (isZoomedEntry) {
                canvas.save().also {
                    canvas.scale(entryScale, entryScale, surfaceWidth / 2f, surfaceHeight / 2f)
                }
            } else {
                null
            }

            val darkenMix = (request?.darkenMix ?: darkenTransitionController.currentMix).coerceIn(0f, 1f)
            val pageACanDarken = repository.shouldDarkenPage(
                state.pages.find { it.pageIndex == pageA },
                state.darken
            )
            val pageBCanDarken = pageA != pageB && repository.shouldDarkenPage(
                state.pages.find { it.pageIndex == pageB },
                state.darken
            )
            val anyDarkenablePage = pageACanDarken || pageBCanDarken

            when {
                darkenMix <= 0.001f || !anyDarkenablePage -> {
                    renderWallpaperScene(
                        canvas = canvas,
                        state = state,
                        pageCount = pageCount,
                        pageA = pageA,
                        pageB = pageB,
                        progress = progress,
                        darkened = false
                    )
                }
                darkenMix >= 0.999f -> {
                    renderWallpaperScene(
                        canvas = canvas,
                        state = state,
                        pageCount = pageCount,
                        pageA = pageA,
                        pageB = pageB,
                        progress = progress,
                        darkened = true
                    )
                }
                else -> {
                    renderWallpaperScene(
                        canvas = canvas,
                        state = state,
                        pageCount = pageCount,
                        pageA = pageA,
                        pageB = pageB,
                        progress = progress,
                        darkened = false
                    )
                    val blendAlpha = (darkenMix * 255f).toInt().coerceIn(0, 255)
                    if (blendAlpha > 0) {
                        val blendSave = canvas.saveLayerAlpha(
                            0f,
                            0f,
                            surfaceWidth.toFloat(),
                            surfaceHeight.toFloat(),
                            blendAlpha
                        )
                        try {
                            renderWallpaperScene(
                                canvas = canvas,
                                state = state,
                                pageCount = pageCount,
                                pageA = pageA,
                                pageB = pageB,
                                progress = progress,
                                darkened = true
                            )
                        } finally {
                            canvas.restoreToCount(blendSave)
                        }
                    }
                }
            }

            if (sceneSaveCount != null) {
                canvas.restoreToCount(sceneSaveCount)
            }

            val entryBlackFadeAlpha = request?.entryBlackFadeAlpha ?: 0f
            if (entryBlackFadeAlpha > 0.004f) {
                dimPaint.color = Color.BLACK
                dimPaint.alpha = (entryBlackFadeAlpha.coerceIn(0f, 1f) * 255).toInt()
                canvas.drawRect(0f, 0f, surfaceWidth.toFloat(), surfaceHeight.toFloat(), dimPaint)
            }

            if (includeDebugIndicators) {
                drawDebugIndicators(canvas, state, request?.signalOverlayLines ?: emptyList())
            }
        }

        private fun renderWallpaperScene(
            canvas: Canvas,
            state: WallpaperAppState,
            pageCount: Int,
            pageA: Int,
            pageB: Int,
            progress: Float,
            darkened: Boolean
        ) {
            val transitionSetting = state.transitions.getOrNull(pageA)
            val swipeForward = offsetDirection >= 0
            val transitionId = transitionSetting?.transitionId(swipeForward)
                ?: state.transitionType
            val renderer = TransitionRegistry.get(transitionId)
            val isEdgeOverScroll = pageA == pageB &&
                abs(edgeOverScrollController.overScrollFraction) > EdgeOverScrollController.FRACTION_EPSILON

            if (isEdgeOverScroll) {
                val edgeParallax = repository.getParallaxBitmap(
                    pageIndex = pageA,
                    reqWidth = surfaceWidth,
                    reqHeight = surfaceHeight,
                    darkened = darkened
                )
                val edgeBitmap = edgeParallax?.bitmap
                    ?: repository.getCroppedBitmap(
                        pageIndex = pageA,
                        reqWidth = surfaceWidth,
                        reqHeight = surfaceHeight,
                        darkened = darkened
                    )
                drawEdgeOverScroll(
                    canvas = canvas,
                    bitmap = edgeBitmap,
                    leftTravelPx = edgeParallax?.leftTravelPx ?: 0,
                    rightTravelPx = edgeParallax?.rightTravelPx ?: 0,
                    fraction = edgeOverScrollController.overScrollFraction
                )
                return
            }

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
                repository.getParallaxBitmap(pageA, surfaceWidth, surfaceHeight, darkened = darkened)
            } else {
                null
            }
            val pageBParallax = if (usesParallax && pageA != pageB) {
                repository.getParallaxBitmap(pageB, surfaceWidth, surfaceHeight, darkened = darkened)
            } else {
                null
            }
            val pageABitmap = pageAParallax?.bitmap
                ?: if (usesParallax) null
                else repository.getCroppedBitmap(pageA, surfaceWidth, surfaceHeight, darkened = darkened)
            val pageBBitmap = if (pageA != pageB) {
                pageBParallax?.bitmap
                    ?: if (usesParallax) null
                    else repository.getCroppedBitmap(pageB, surfaceWidth, surfaceHeight, darkened = darkened)
            } else {
                null
            }

            val residualFraction = edgeOverScrollController.overScrollFraction
            val lastPageIndex = (pageCount - 1).coerceAtLeast(0)
            val pageAEdgeResidualPx = if (
                pageA == 0 &&
                residualFraction < -EdgeOverScrollController.FRACTION_EPSILON &&
                (pageAParallax?.leftTravelPx ?: 0) > 0
            ) {
                -EdgeOverScrollScaling.computeTravelPx(
                    absIntensity = -residualFraction,
                    availableTravelPx = pageAParallax?.leftTravelPx ?: 0,
                    screenWidth = surfaceWidth
                )
            } else {
                0f
            }
            val pageBEdgeResidualPx = if (
                pageB == lastPageIndex &&
                residualFraction > EdgeOverScrollController.FRACTION_EPSILON &&
                (pageBParallax?.rightTravelPx ?: 0) > 0
            ) {
                EdgeOverScrollScaling.computeTravelPx(
                    absIntensity = residualFraction,
                    availableTravelPx = pageBParallax?.rightTravelPx ?: 0,
                    screenWidth = surfaceWidth
                )
            } else {
                0f
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
                    incomingMotionRate = incomingMotionRate,
                    pageAEdgeResidualPx = pageAEdgeResidualPx,
                    pageBEdgeResidualPx = pageBEdgeResidualPx
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

        private fun drawDebugIndicators(
            canvas: Canvas,
            state: WallpaperAppState,
            signalLines: List<String> = emptyList()
        ) {
            if (state.showOffsetDebugOverlay) {
                drawOffsetDebugOverlay(canvas)
            }
            if (state.showSignalDebugOverlay && signalLines.isNotEmpty()) {
                drawSignalDebugOverlay(canvas, state, signalLines)
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

        private fun drawSignalDebugOverlay(
            canvas: Canvas,
            state: WallpaperAppState,
            lines: List<String>
        ) {
            val density = resources.displayMetrics.density
            val cardWidth = minOf(352f * density, (surfaceWidth - 20f * density).coerceAtLeast(180f))
            val lineHeightPx = 15f * density
            val headerHeightPx = 22f * density
            val padPx = 12f * density
            val cardHeight = padPx * 2f + headerHeightPx + lines.size * lineHeightPx
            val left = ((surfaceWidth - cardWidth) / 2f).coerceAtLeast(8f * density)
            val top = if (state.showOffsetDebugOverlay) 186f * density else 96f * density
            offsetOverlayBounds.set(left, top, left + cardWidth, top + cardHeight)

            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(228, 15, 23, 42)
            }
            canvas.drawRoundRect(offsetOverlayBounds, 14f * density, 14f * density, bgPaint)
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(125, 211, 252)
                textSize = 11.5f * density
                isFakeBoldText = true
            }
            canvas.drawText("Live Signal Diagnostics", left + padPx, top + padPx + 11.5f * density, titlePaint)
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 10.5f * density
                typeface = Typeface.MONOSPACE
            }
            var y = top + padPx + headerHeightPx + 10.5f * density
            lines.forEachIndexed { index, line ->
                linePaint.color = when {
                    index < 2 -> Color.rgb(167, 243, 208)
                    index == 2 && line.startsWith("CMD:") -> Color.rgb(253, 230, 138)
                    else -> Color.rgb(226, 232, 240)
                }
                canvas.drawText(line, left + padPx, y, linePaint)
                y += lineHeightPx
            }
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

        private fun updateSwipeDirectionLock(
            previousOffset: Float,
            nextOffset: Float,
            calibration: CalibrationConfig,
            state: WallpaperAppState
        ) {
            val isAtStaticPage = isAtDirectionBoundaryOffset(nextOffset, calibration)
            val crossedStaticPage = hasCrossedDirectionBoundary(
                previousOffset,
                nextOffset,
                calibration
            )
            val delta = nextOffset - previousOffset
            val newTransitionDirection = if (
                crossedStaticPage && abs(delta) > OFFSET_EPSILON
            ) {
                if (delta > 0f) 1 else -1
            } else {
                swipeDirectionLock.previewDirection(previousOffset, nextOffset)
            }
            val boundaryProbeOffset = if (isAtStaticPage) previousOffset else nextOffset
            val boundaryIndex = transitionBoundaryIndex(
                boundaryProbeOffset,
                newTransitionDirection,
                calibration
            )
            val enteringTransitionId = state.transitions.getOrNull(boundaryIndex)
                ?.transitionId(forward = newTransitionDirection >= 0)
                ?: state.transitionType

            offsetDirection = swipeDirectionLock.onOffsetChanged(
                previousOffset = previousOffset,
                nextOffset = nextOffset,
                isAtStaticPage = isAtStaticPage,
                crossedStaticPage = crossedStaticPage,
                newTransitionUnlockImmediately = enteringTransitionId == "swipe_reveal"
            )
        }

        private fun pageOffsetAnchor(
            index: Int,
            calibration: CalibrationConfig,
            pageCount: Int
        ): Float = if (calibration.customOffsets.size >= pageCount) {
            calibration.customOffsets[index]
        } else if (pageCount <= 1) {
            0f
        } else {
            index.toFloat() / (pageCount - 1).toFloat()
        }

        private fun isAtDirectionBoundaryOffset(
            offset: Float,
            calibration: CalibrationConfig
        ): Boolean {
            val count = calibration.pageCount.coerceAtLeast(1)
            for (index in 0 until count) {
                if (abs(offset - pageOffsetAnchor(index, calibration, count)) <= STATIC_OFFSET_TOLERANCE) {
                    return true
                }
            }
            return false
        }

        private fun hasCrossedDirectionBoundary(
            previousOffset: Float,
            nextOffset: Float,
            calibration: CalibrationConfig
        ): Boolean {
            if (abs(nextOffset - previousOffset) <= OFFSET_EPSILON) return false
            val count = calibration.pageCount.coerceAtLeast(1)
            for (index in 0 until count) {
                val anchor = pageOffsetAnchor(index, calibration, count)
                val before = previousOffset - anchor
                val after = nextOffset - anchor
                if ((before < -STATIC_OFFSET_TOLERANCE && after > STATIC_OFFSET_TOLERANCE) ||
                    (before > STATIC_OFFSET_TOLERANCE && after < -STATIC_OFFSET_TOLERANCE) ||
                    (abs(before) <= STATIC_OFFSET_TOLERANCE &&
                        abs(after) > STATIC_OFFSET_TOLERANCE)
                ) {
                    return true
                }
            }
            return false
        }

        private fun transitionBoundaryIndex(
            offset: Float,
            direction: Int,
            calibration: CalibrationConfig
        ): Int {
            val count = calibration.pageCount.coerceAtLeast(1)
            if (count <= 1) return 0

            var nearestAnchorIndex = 0
            var nearestDistance = Float.MAX_VALUE
            for (index in 0 until count) {
                val distance = abs(offset - pageOffsetAnchor(index, calibration, count))
                if (distance < nearestDistance) {
                    nearestDistance = distance
                    nearestAnchorIndex = index
                }
            }
            if (nearestDistance <= STATIC_OFFSET_TOLERANCE) {
                return if (direction >= 0) {
                    nearestAnchorIndex.coerceAtMost(count - 2)
                } else {
                    (nearestAnchorIndex - 1).coerceAtLeast(0)
                }
            }

            for (index in 0 until count - 1) {
                if (offset < pageOffsetAnchor(index + 1, calibration, count)) return index
            }
            return count - 2
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

        private fun edgeSegmentSpan(anchor: Float, calib: CalibrationConfig): Float {
            val count = calib.pageCount.coerceAtLeast(1)
            if (count <= 1) return 1f
            val anchors = if (calib.customOffsets.size >= count) {
                calib.customOffsets.take(count)
            } else {
                List(count) { index ->
                    index.toFloat() / (count - 1).toFloat()
                }
            }
            return if (abs(anchor - anchors.first()) <= abs(anchor - anchors.last())) {
                abs(anchors[1] - anchors[0]).coerceAtLeast(0.05f)
            } else {
                abs(anchors[count - 1] - anchors[count - 2]).coerceAtLeast(0.05f)
            }
        }

        private fun setPageTransition(pageA: Int, pageB: Int, progress: Float) {
            transitionPageA = pageA
            transitionPageB = pageB
            transitionProgress = progress
        }
    }
}
