package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.LensBlur
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Vignette
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.BlurRainbowStyle
import com.example.data.model.BlurStyle
import com.example.data.model.BlurTintMode
import com.example.data.model.CropFillMode
import com.example.data.model.DarkenConfig
import com.example.data.model.OriginalMedia
import com.example.data.model.PageItem
import com.example.data.repository.WallpaperRepository
import com.example.ui.blurRainbowStyleLabelRes
import com.example.ui.blurStyleDescRes
import com.example.ui.blurStyleShortLabelRes
import com.example.ui.blurTintModeLabelRes
import com.example.ui.cropFillModeLabelRes
import com.example.util.ImageProcessingUtils
import com.example.util.ParallaxBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

private enum class EditorPreviewMode {
    SINGLE_PAGE,
    FULL_IMAGE
}

private fun calculatePreviewTravel(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Float,
    targetHeight: Float,
    scale: Float,
    panX: Float,
    cropFillMode: CropFillMode,
    amount: Float,
    symmetricParallax: Boolean
): Pair<Float, Float> {
    val travel = ParallaxBounds.calculateTravel(
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        targetWidth = targetWidth,
        targetHeight = targetHeight,
        scale = scale,
        panX = panX,
        cropFillMode = cropFillMode,
        parallaxAmount = amount,
        symmetricParallax = symmetricParallax
    )
    return travel.leftTravelPx to travel.rightTravelPx
}

internal fun calculateFullImagePreviewPan(
    currentPanX: Float,
    currentPanY: Float,
    panDelta: Offset,
    canvasWidth: Float,
    canvasHeight: Float,
    sourceWidth: Int,
    sourceHeight: Int,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    zoom: Float,
    cropFillMode: CropFillMode
): Pair<Float, Float> {
    if (canvasWidth <= 0f || canvasHeight <= 0f ||
        sourceWidth <= 0 || sourceHeight <= 0 ||
        viewportWidthPx <= 0f || viewportHeightPx <= 0f
    ) {
        return currentPanX to currentPanY
    }
    val imageScale = minOf(canvasWidth / sourceWidth, canvasHeight / sourceHeight)
    if (imageScale <= 0f) return currentPanX to currentPanY

    val clampedZoom = zoom.coerceIn(1f, 3f)
    val deltaSourceX = panDelta.x / imageScale
    val deltaSourceY = panDelta.y / imageScale

    return if (cropFillMode == CropFillMode.CROP) {
        val targetRatio = viewportWidthPx / viewportHeightPx
        val sourceRatio = sourceWidth.toFloat() / sourceHeight
        val baseCropWidth = if (sourceRatio > targetRatio) {
            sourceHeight * targetRatio
        } else {
            sourceWidth.toFloat()
        }
        val baseCropHeight = if (sourceRatio > targetRatio) {
            sourceHeight.toFloat()
        } else {
            sourceWidth / targetRatio
        }
        val cropWidth = baseCropWidth / clampedZoom
        val cropHeight = baseCropHeight / clampedZoom
        val maxPanX = ((sourceWidth - cropWidth) / 2f).coerceAtLeast(0f)
        val maxPanY = ((sourceHeight - cropHeight) / 2f).coerceAtLeast(0f)
        val nextPanX = if (maxPanX > 0.0001f) {
            (currentPanX - deltaSourceX / maxPanX).coerceIn(-1f, 1f)
        } else {
            currentPanX
        }
        val nextPanY = if (maxPanY > 0.0001f) {
            (currentPanY - deltaSourceY / maxPanY).coerceIn(-1f, 1f)
        } else {
            currentPanY
        }
        nextPanX to nextPanY
    } else {
        val imageFitScale = minOf(
            viewportWidthPx / sourceWidth,
            viewportHeightPx / sourceHeight
        ) * clampedZoom
        val drawWidth = sourceWidth * imageFitScale
        val drawHeight = sourceHeight * imageFitScale
        val maxOverflowX = ((drawWidth - viewportWidthPx) / 2f).coerceAtLeast(0f)
        val maxOverflowY = ((drawHeight - viewportHeightPx) / 2f).coerceAtLeast(0f)
        val nextPanX = if (maxOverflowX > 0.0001f) {
            (currentPanX - deltaSourceX * imageFitScale / maxOverflowX).coerceIn(-1f, 1f)
        } else {
            currentPanX
        }
        val nextPanY = if (maxOverflowY > 0.0001f) {
            (currentPanY - deltaSourceY * imageFitScale / maxOverflowY).coerceIn(-1f, 1f)
        } else {
            currentPanY
        }
        nextPanX to nextPanY
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaEditorScreen(
    media: OriginalMedia,
    page: PageItem,
    pageCount: Int,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (PageItem) -> Unit,
    unifiedDimFactor: Float? = null
) {
    val context = LocalContext.current
    val repository = remember(context) { WallpaperRepository.getInstance(context) }
    val effectiveUnifiedDimFactor = (
        unifiedDimFactor ?: repository.appState.value.darken.dimFactor
        ).coerceIn(DarkenConfig.MIN_DIM_FACTOR, DarkenConfig.MAX_DIM_FACTOR)

    var bitmap by remember(media.id) { mutableStateOf<Bitmap?>(null) }
    var blurredBitmap by remember(media.id) { mutableStateOf<Bitmap?>(null) }
    var darkenedPreviewBitmap by remember(media.id) { mutableStateOf<Bitmap?>(null) }
    var darkenedBackdropBitmap by remember(media.id) { mutableStateOf<Bitmap?>(null) }
    var panX by remember(media.id, page.pageIndex) { mutableFloatStateOf(page.cropPanX) }
    var panY by remember(media.id, page.pageIndex) { mutableFloatStateOf(page.cropPanY) }
    var zoom by remember(media.id, page.pageIndex) { mutableFloatStateOf(page.cropScale) }
    var isAlreadyDark by remember(media.id, page.pageIndex) { mutableStateOf(page.isAlreadyDark) }
    var previewDarkened by remember(media.id, page.pageIndex) { mutableStateOf(false) }
    var cropFillMode by remember(media.id, page.pageIndex) { mutableStateOf(page.cropFillMode) }
    var blurAmount by remember(media.id, page.pageIndex) { mutableFloatStateOf(page.blurAmount) }
    var blurStyle by remember(media.id, page.pageIndex) { mutableStateOf(page.blurStyle) }
    var blurTintMode by remember(media.id, page.pageIndex) { mutableStateOf(page.blurTintMode) }
    var blurTintColor by remember(media.id, page.pageIndex) { mutableIntStateOf(page.blurTintColor) }
    var blurTintIntensity by remember(media.id, page.pageIndex) { mutableFloatStateOf(page.blurTintIntensity) }
    var blurRainbowStyle by remember(media.id, page.pageIndex) { mutableStateOf(page.blurRainbowStyle) }
    var parallaxAmount by remember(media.id, page.pageIndex) { mutableFloatStateOf(page.parallaxAmount) }
    var symmetricParallax by remember(media.id, page.pageIndex) { mutableStateOf(page.symmetricParallax) }
    var previewMode by remember(media.id, page.pageIndex, repository) {
        mutableStateOf(
            if (repository.getEditorDefaultFullPreview()) {
                EditorPreviewMode.FULL_IMAGE
            } else {
                EditorPreviewMode.SINGLE_PAGE
            }
        )
    }
    var splitFraction by remember(media.id, page.pageIndex, repository) {
        mutableFloatStateOf(repository.getEditorPreviewSplitFraction())
    }

    BackHandler(enabled = !isSaving) { onDismiss() }

    LaunchedEffect(media.id) {
        val loaded = withContext(Dispatchers.IO) {
            val file = File(File(context.filesDir, "originals"), media.fileName)
            if (file.exists()) {
                val sampleSize = (
                    maxOf(media.width, media.height) / 2048
                ).coerceAtLeast(1)
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                )
            } else {
                null
            }
        }
        bitmap = loaded
    }

    LaunchedEffect(
        media.id,
        bitmap,
        cropFillMode,
        blurAmount,
        blurStyle,
        blurTintMode,
        blurTintColor,
        blurTintIntensity,
        blurRainbowStyle
    ) {
        val source = bitmap ?: return@LaunchedEffect
        if (cropFillMode == CropFillMode.CROP) {
            blurredBitmap = null
            return@LaunchedEffect
        }
        if (blurredBitmap != null) {
            delay(40)
        }
        val preview = withContext(Dispatchers.Default) {
            ImageProcessingUtils.renderBackdropPreviewBitmap(
                srcBmp = source,
                targetWidth = 270,
                targetHeight = 585,
                blurAmount = blurAmount,
                blurStyle = blurStyle,
                blurTintMode = blurTintMode,
                blurTintColor = blurTintColor,
                blurTintIntensity = blurTintIntensity,
                blurRainbowStyle = blurRainbowStyle
            )
        }
        blurredBitmap = preview
    }

    LaunchedEffect(
        bitmap,
        blurredBitmap,
        previewDarkened,
        isAlreadyDark,
        effectiveUnifiedDimFactor
    ) {
        val showDark = previewDarkened && !isAlreadyDark
        if (!showDark) {
            darkenedPreviewBitmap = null
            darkenedBackdropBitmap = null
            return@LaunchedEffect
        }
        val src = bitmap
        val backdrop = blurredBitmap
        val (darkFg, darkBg) = withContext(Dispatchers.Default) {
            val fg = src?.let { ImageProcessingUtils.createDarkenedCopy(it, effectiveUnifiedDimFactor) }
            val bg = backdrop?.let { ImageProcessingUtils.createDarkenedCopy(it, effectiveUnifiedDimFactor) }
            fg to bg
        }
        darkenedPreviewBitmap = darkFg
        darkenedBackdropBitmap = darkBg
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.crop_edit_page_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.crop_edit_page_subtitle, media.width, media.height),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss, enabled = !isSaving, modifier = Modifier.testTag("crop_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = !isSaving,
                        modifier = Modifier.weight(1f).height(50.dp).testTag("crop_cancel_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Button(
                        onClick = {
                            onSave(
                                page.copy(
                                    isAlreadyDark = isAlreadyDark,
                                    dimFactor = effectiveUnifiedDimFactor,
                                    cropFillMode = cropFillMode,
                                    blurAmount = blurAmount,
                                    blurStyle = blurStyle,
                                    blurTintMode = blurTintMode,
                                    blurTintColor = blurTintColor,
                                    blurTintIntensity = blurTintIntensity,
                                    blurRainbowStyle = blurRainbowStyle,
                                    cropPanX = panX,
                                    cropPanY = panY,
                                    cropScale = zoom,
                                    parallaxAmount = parallaxAmount,
                                    symmetricParallax = symmetricParallax
                                )
                            )
                        },
                        enabled = !isSaving && bitmap != null,
                        modifier = Modifier.weight(1.5f).height(50.dp).testTag("crop_confirm_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(if (isSaving) R.string.crop_saving else R.string.crop_save_settings))
                    }
                }
            }
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(innerPadding)
        ) {
            val availableHeight = this.maxHeight
            val density = LocalDensity.current
            val dividerHeight = 36.dp
            val previewTopPadding = 8.dp
            val previewBottomPadding = 4.dp
            val minPreviewHeight = 120.dp
            val minSettingsHeight = 140.dp
            val maxPreviewHeight = (availableHeight - dividerHeight - minSettingsHeight)
                .coerceAtLeast(minPreviewHeight)
            val targetPreviewHeight = (availableHeight * splitFraction)
                .coerceIn(minPreviewHeight, maxPreviewHeight)
            val availableHeightPx = with(density) { availableHeight.toPx().coerceAtLeast(1f) }
            val minFraction = (with(density) { minPreviewHeight.toPx() } / availableHeightPx)
                .coerceIn(0.15f, 0.70f)
            val maxFraction = (with(density) { maxPreviewHeight.toPx() } / availableHeightPx)
                .coerceIn(minFraction, 0.80f)

            val viewportHeight = (targetPreviewHeight - previewTopPadding - previewBottomPadding)
                .coerceAtLeast(96.dp)
            val viewportWidth = viewportHeight * (9f / 19.5f)
            val viewportWidthPx = with(density) { viewportWidth.toPx() }
            val viewportHeightPx = with(density) { viewportHeight.toPx() }
            val previewTravel = bitmap?.let {
                calculatePreviewTravel(
                    sourceWidth = it.width,
                    sourceHeight = it.height,
                    targetWidth = viewportWidthPx,
                    targetHeight = viewportHeightPx,
                    scale = zoom,
                    panX = panX,
                    cropFillMode = cropFillMode,
                    amount = parallaxAmount,
                    symmetricParallax = symmetricParallax
                )
            } ?: (0f to 0f)
            val leftPreviewTravelPx = previewTravel.first
            val rightPreviewTravelPx = previewTravel.second

            val showDarkenedPreview = previewDarkened && !isAlreadyDark
            val displayBitmap = if (showDarkenedPreview) {
                darkenedPreviewBitmap ?: bitmap
            } else {
                bitmap
            }
            val displayBackdrop = if (showDarkenedPreview) {
                darkenedBackdropBitmap ?: blurredBitmap
            } else {
                blurredBitmap
            }
            val togglePreviewMode = {
                previewMode = if (previewMode == EditorPreviewMode.SINGLE_PAGE) {
                    EditorPreviewMode.FULL_IMAGE
                } else {
                    EditorPreviewMode.SINGLE_PAGE
                }
            }

            val currentDisplayBitmap by rememberUpdatedState(displayBitmap)
            val currentCropFillMode by rememberUpdatedState(cropFillMode)
            val currentViewportWidthPx by rememberUpdatedState(viewportWidthPx)
            val currentViewportHeightPx by rememberUpdatedState(viewportHeightPx)

            val touchHandleWidth = 76.dp
            val touchHandleHeight = 48.dp
            val dividerTouchOffsetYPx = with(density) {
                (targetPreviewHeight + dividerHeight / 2f - touchHandleHeight / 2f).roundToPx()
            }
            val dividerCd = stringResource(R.string.crop_split_divider_cd)

            Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Fixed top unified preview window
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(targetPreviewHeight)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                        .testTag("crop_unified_preview"),
                    contentAlignment = Alignment.Center
                ) {
                    if (previewMode == EditorPreviewMode.SINGLE_PAGE) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = previewTopPadding, bottom = previewBottomPadding),
                            contentAlignment = Alignment.Center
                        ) {
                        Box(
                            modifier = Modifier
                                .size(width = viewportWidth, height = viewportHeight)
                                .clip(RoundedCornerShape(18.dp))
                                .clipToBounds()
                                .background(Color.Black)
                                .border(
                                    2.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                                    RoundedCornerShape(18.dp)
                                )
                                .pointerInput(media.id, page.pageIndex) {
                                    detectTransformGestures { _, pan, zoomChange, _ ->
                                        if (currentDisplayBitmap == null) return@detectTransformGestures
                                        if (zoomChange != 1f) {
                                            zoom = (zoom * zoomChange).coerceIn(1f, 3f)
                                        }
                                        if (pan != Offset.Zero) {
                                            panX = (panX + pan.x / size.width * 2f).coerceIn(-1f, 1f)
                                            panY = (panY + pan.y / size.height * 2f).coerceIn(-1f, 1f)
                                        }
                                    }
                                }
                                .testTag("crop_single_page_preview"),
                            contentAlignment = Alignment.Center
                        ) {
                            val source = displayBitmap
                            if (source != null) {
                                if (cropFillMode != CropFillMode.CROP) {
                                    Image(
                                        bitmap = (displayBackdrop ?: source).asImageBitmap(),
                                        contentDescription = stringResource(R.string.crop_preview_single_label),
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .blur(if (displayBackdrop == null) (blurAmount * 20f).dp else 0.dp)
                                    )
                                }
                                Canvas(Modifier.fillMaxSize().clipToBounds()) {
                                    val targetWidth = size.width
                                    val targetHeight = size.height
                                    val sourceRatio = source.width.toFloat() / source.height
                                    val targetRatio = targetWidth / targetHeight
                                    val baseCropWidth = if (sourceRatio > targetRatio) {
                                        source.height * targetRatio
                                    } else {
                                        source.width.toFloat()
                                    }
                                    val baseCropHeight = if (sourceRatio > targetRatio) {
                                        source.height.toFloat()
                                    } else {
                                        source.width / targetRatio
                                    }
                                    val drawPaint = Paint(
                                        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
                                    )
                                    drawIntoCanvas { composeCanvas ->
                                        val nativeCanvas = composeCanvas.nativeCanvas
                                        if (cropFillMode == CropFillMode.CROP) {
                                            val cropWidth = baseCropWidth / zoom
                                            val cropHeight = baseCropHeight / zoom
                                            val centerX = source.width / 2f -
                                                panX * ((source.width - cropWidth) / 2f).coerceAtLeast(0f)
                                            val centerY = source.height / 2f -
                                                panY * ((source.height - cropHeight) / 2f).coerceAtLeast(0f)
                                            val left = (centerX - cropWidth / 2f)
                                                .coerceIn(0f, source.width - cropWidth)
                                            val top = (centerY - cropHeight / 2f)
                                                .coerceIn(0f, source.height - cropHeight)
                                            val sourceLeft = left.toInt().coerceIn(0, source.width - 1)
                                            val sourceTop = top.toInt().coerceIn(0, source.height - 1)
                                            nativeCanvas.drawBitmap(
                                                source,
                                                Rect(
                                                    sourceLeft,
                                                    sourceTop,
                                                    (left + cropWidth).toInt()
                                                        .coerceIn(sourceLeft + 1, source.width),
                                                    (top + cropHeight).toInt()
                                                        .coerceIn(sourceTop + 1, source.height)
                                                ),
                                                Rect(0, 0, targetWidth.toInt(), targetHeight.toInt()),
                                                drawPaint
                                            )
                                        } else {
                                            val fitScale = minOf(
                                                targetWidth / source.width,
                                                targetHeight / source.height
                                            ) * zoom
                                            val imageWidth = source.width * fitScale
                                            val imageHeight = source.height * fitScale
                                            val centerX = targetWidth / 2f +
                                                panX * ((imageWidth - targetWidth) / 2f).coerceAtLeast(0f)
                                            val centerY = targetHeight / 2f +
                                                panY * ((imageHeight - targetHeight) / 2f).coerceAtLeast(0f)
                                            val fgDstRect = RectF(
                                                centerX - imageWidth / 2f,
                                                centerY - imageHeight / 2f,
                                                centerX + imageWidth / 2f,
                                                centerY + imageHeight / 2f
                                            )
                                            ImageProcessingUtils.drawForegroundShadow(nativeCanvas, fgDstRect)
                                            nativeCanvas.drawBitmap(
                                                source,
                                                null,
                                                fgDstRect,
                                                drawPaint
                                            )
                                        }
                                    }
                                }
                            } else {
                                Text(stringResource(R.string.crop_unable_to_load), color = Color.White)
                            }
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(18.dp))
                            )
                        }
                        }
                    } else {
                        // Full image preview keeps sharp corners, unclipped 2dp reach/static strokes, and 1:1 frame drag & pinch zoom
                        val previewOutlineColor = MaterialTheme.colorScheme.outlineVariant
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = previewTopPadding,
                                    bottom = previewBottomPadding
                                )
                                .background(Color.Black)
                                .pointerInput(media.id, page.pageIndex) {
                                    detectTransformGestures { _, pan, zoomChange, _ ->
                                        val source = currentDisplayBitmap ?: return@detectTransformGestures
                                        val nextZoom = if (zoomChange != 1f) {
                                            (zoom * zoomChange).coerceIn(1f, 3f).also { zoom = it }
                                        } else {
                                            zoom
                                        }
                                        if (pan != Offset.Zero) {
                                            val (nextPanX, nextPanY) = calculateFullImagePreviewPan(
                                                currentPanX = panX,
                                                currentPanY = panY,
                                                panDelta = pan,
                                                canvasWidth = size.width.toFloat(),
                                                canvasHeight = size.height.toFloat(),
                                                sourceWidth = source.width,
                                                sourceHeight = source.height,
                                                viewportWidthPx = currentViewportWidthPx,
                                                viewportHeightPx = currentViewportHeightPx,
                                                zoom = nextZoom,
                                                cropFillMode = currentCropFillMode
                                            )
                                            panX = nextPanX
                                            panY = nextPanY
                                        }
                                    }
                                }
                                .testTag("crop_parallax_bounds_preview")
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val nativeCanvas = drawContext.canvas.nativeCanvas
                                val borderWidthPx = 1.dp.toPx()
                                val halfBorderPx = borderWidthPx / 2f
                                val borderPaint = Paint().apply {
                                    color = previewOutlineColor.toArgb()
                                    style = Paint.Style.STROKE
                                    strokeWidth = borderWidthPx
                                }
                                val source = displayBitmap
                                if (source == null) {
                                    nativeCanvas.drawRect(
                                        halfBorderPx,
                                        halfBorderPx,
                                        size.width - halfBorderPx,
                                        size.height - halfBorderPx,
                                        borderPaint
                                    )
                                    return@Canvas
                                }
                                val imageScale = minOf(size.width / source.width, size.height / source.height)
                                val imageWidth = source.width * imageScale
                                val imageHeight = source.height * imageScale
                                val imageLeft = (size.width - imageWidth) / 2f
                                val imageTop = (size.height - imageHeight) / 2f
                                val targetRatio = viewportWidthPx / viewportHeightPx
                                val sourceRatio = source.width.toFloat() / source.height
                                val baseCropWidth = if (sourceRatio > targetRatio) {
                                    source.height * targetRatio
                                } else {
                                    source.width.toFloat()
                                }
                                val baseCropHeight = if (sourceRatio > targetRatio) {
                                    source.height.toFloat()
                                } else {
                                    source.width / targetRatio
                                }
                                val frameLeft: Float
                                val frameRight: Float
                                val frameTop: Float
                                val frameBottom: Float
                                val sourcePerViewportPixel: Float
                                if (cropFillMode == CropFillMode.CROP) {
                                    val cropWidth = baseCropWidth / zoom
                                    val cropHeight = baseCropHeight / zoom
                                    val cropCenterX = source.width / 2f -
                                        panX * ((source.width - cropWidth) / 2f).coerceAtLeast(0f)
                                    val cropCenterY = source.height / 2f -
                                        panY * ((source.height - cropHeight) / 2f).coerceAtLeast(0f)
                                    frameLeft = (cropCenterX - cropWidth / 2f)
                                        .coerceIn(0f, source.width - cropWidth)
                                    frameRight = frameLeft + cropWidth
                                    frameTop = (cropCenterY - cropHeight / 2f)
                                        .coerceIn(0f, source.height - cropHeight)
                                    frameBottom = frameTop + cropHeight
                                    sourcePerViewportPixel = cropWidth / viewportWidthPx
                                } else {
                                    val imageFitScale = minOf(
                                        viewportWidthPx / source.width,
                                        viewportHeightPx / source.height
                                    ) * zoom
                                    val drawWidth = source.width * imageFitScale
                                    val drawHeight = source.height * imageFitScale
                                    val imageLeftInViewport = viewportWidthPx / 2f +
                                        panX * ((drawWidth - viewportWidthPx) / 2f).coerceAtLeast(0f) -
                                        drawWidth / 2f
                                    val imageTopInViewport = viewportHeightPx / 2f +
                                        panY * ((drawHeight - viewportHeightPx) / 2f).coerceAtLeast(0f) -
                                        drawHeight / 2f
                                    frameLeft = ((-imageLeftInViewport) / imageFitScale)
                                        .coerceIn(0f, source.width.toFloat())
                                    frameRight = ((viewportWidthPx - imageLeftInViewport) / imageFitScale)
                                        .coerceIn(frameLeft, source.width.toFloat())
                                    frameTop = ((-imageTopInViewport) / imageFitScale)
                                        .coerceIn(0f, source.height.toFloat())
                                    frameBottom = ((viewportHeightPx - imageTopInViewport) / imageFitScale)
                                        .coerceIn(frameTop, source.height.toFloat())
                                    sourcePerViewportPixel = 1f / imageFitScale
                                }
                                val leftSourceTravel = leftPreviewTravelPx * sourcePerViewportPixel
                                val rightSourceTravel = rightPreviewTravelPx * sourcePerViewportPixel
                                val reachLeft = (frameLeft - leftSourceTravel).coerceAtLeast(0f)
                                val reachRight = (frameRight + rightSourceTravel)
                                    .coerceAtMost(source.width.toFloat())
                                val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                                nativeCanvas.drawBitmap(
                                    source,
                                    null,
                                    RectF(imageLeft, imageTop, imageLeft + imageWidth, imageTop + imageHeight),
                                    imagePaint
                                )
                                nativeCanvas.drawRect(
                                    halfBorderPx,
                                    halfBorderPx,
                                    size.width - halfBorderPx,
                                    size.height - halfBorderPx,
                                    borderPaint
                                )

                                val mapX = { x: Float -> imageLeft + x * imageScale }
                                val mapY = { y: Float -> imageTop + y * imageScale }
                                val reachBounds = RectF(
                                    mapX(reachLeft),
                                    mapY(frameTop),
                                    mapX(reachRight),
                                    mapY(frameBottom)
                                )
                                val staticBounds = RectF(
                                    mapX(frameLeft),
                                    mapY(frameTop),
                                    mapX(frameRight),
                                    mapY(frameBottom)
                                )
                                val reachStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    color = android.graphics.Color.rgb(255, 180, 0)
                                    style = Paint.Style.STROKE
                                    strokeWidth = 2.dp.toPx()
                                }
                                val staticStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    color = android.graphics.Color.rgb(80, 220, 255)
                                    style = Paint.Style.STROKE
                                    strokeWidth = 2.dp.toPx()
                                }
                                nativeCanvas.drawRect(reachBounds, reachStroke)
                                nativeCanvas.drawRect(staticBounds, staticStroke)
                            }
                        }
                    }

                    if (showDarkenedPreview) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp)
                                .testTag("crop_darken_preview_badge"),
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.72f)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.crop_darkened_preview_badge,
                                    (effectiveUnifiedDimFactor * 100f).roundToInt()
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // Compact edge-to-edge split divider bar hosting bounds legend (left, Full Image only), shortened divider + drag pill (center), and mode pill (right)
                val dividerBarBorderColor = MaterialTheme.colorScheme.outlineVariant
                SplitDividerRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(dividerHeight)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .drawBehind {
                            val strokePx = 1.dp.toPx()
                            drawLine(
                                color = dividerBarBorderColor,
                                start = Offset(0f, strokePx / 2f),
                                end = Offset(size.width, strokePx / 2f),
                                strokeWidth = strokePx
                            )
                            drawLine(
                                color = dividerBarBorderColor,
                                start = Offset(0f, size.height - strokePx / 2f),
                                end = Offset(size.width, size.height - strokePx / 2f),
                                strokeWidth = strokePx
                            )
                        }
                        .testTag("crop_split_separator"),
                    showLegend = previewMode == EditorPreviewMode.FULL_IMAGE,
                    legendContent = {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.68f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                            modifier = Modifier.testTag("crop_parallax_legend_pill")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.crop_legend_static),
                                    color = Color(0xFF50DCFF),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        letterSpacing = 0.2.sp
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = stringResource(R.string.crop_legend_reach),
                                    color = Color(0xFFFFB400),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        letterSpacing = 0.2.sp
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                        }
                    },
                    dividerLineContent = {
                        Box(
                            modifier = Modifier
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                    },
                    centerHandleContent = {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            tonalElevation = 2.dp
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                                    .size(width = 36.dp, height = 4.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            )
                        }
                    },
                    modeBadgeContent = {
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = 48.dp, minHeight = 36.dp)
                                .clip(CircleShape)
                                .clickable(
                                    role = Role.Button,
                                    onClick = togglePreviewMode
                                )
                                .testTag("crop_preview_mode_badge"),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.68f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f))
                            ) {
                                Text(
                                    text = stringResource(
                                        if (previewMode == EditorPreviewMode.SINGLE_PAGE) {
                                            R.string.crop_preview_single_label
                                        } else {
                                            R.string.crop_preview_full_label
                                        }
                                    ),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        letterSpacing = 0.2.sp
                                    ),
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                )

                // Independently scrollable settings area
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
                        .testTag("crop_settings_scroll_area"),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(stringResource(R.string.crop_image_fit), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            CropFillMode.values().forEach { mode ->
                                FilterChip(
                                    selected = cropFillMode == mode,
                                    onClick = {
                                        cropFillMode = mode
                                        if (mode == CropFillMode.BLUR &&
                                            (blurStyle == BlurStyle.WIN7_AERO || blurStyle == BlurStyle.MODERN_ACRYLIC)
                                        ) {
                                            blurStyle = BlurStyle.SMOOTH_GAUSSIAN
                                        } else if (mode == CropFillMode.GLASS &&
                                            blurStyle == BlurStyle.SMOOTH_GAUSSIAN
                                        ) {
                                            blurStyle = BlurStyle.WIN7_AERO
                                        }
                                    },
                                    label = { Text(stringResource(cropFillModeLabelRes(mode)), style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("crop_fill_${mode.name.lowercase()}")
                                )
                            }
                        }
                        if (cropFillMode != CropFillMode.CROP) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(stringResource(R.string.crop_blur_style), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    BlurStyle.values().forEach { style ->
                                        FilterChip(
                                            selected = blurStyle == style,
                                            onClick = {
                                                blurStyle = style
                                                cropFillMode = if (
                                                    style == BlurStyle.WIN7_AERO ||
                                                    style == BlurStyle.MODERN_ACRYLIC ||
                                                    style == BlurStyle.PRISM_REFRACTION
                                                ) {
                                                    CropFillMode.GLASS
                                                } else {
                                                    CropFillMode.BLUR
                                                }
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = blurStyleIcon(style),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            },
                                            label = { Text(stringResource(blurStyleShortLabelRes(style)), style = MaterialTheme.typography.labelSmall) },
                                            modifier = Modifier.testTag("blur_style_${style.name.lowercase()}")
                                        )
                                    }
                                }
                                Text(
                                    text = stringResource(blurStyleDescRes(blurStyle)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(stringResource(R.string.crop_bg_blur, (blurAmount * 100).toInt()), style = MaterialTheme.typography.labelMedium)
                                Slider(
                                    value = blurAmount,
                                    onValueChange = { blurAmount = it },
                                    valueRange = 0f..1f,
                                    modifier = Modifier.testTag("crop_blur_slider")
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Palette,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        stringResource(R.string.crop_color_tinting),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    BlurTintMode.values().forEach { mode ->
                                        FilterChip(
                                            selected = blurTintMode == mode,
                                            onClick = { blurTintMode = mode },
                                            label = { Text(stringResource(blurTintModeLabelRes(mode)), style = MaterialTheme.typography.labelSmall) },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("tint_mode_${mode.name.lowercase()}")
                                        )
                                    }
                                }

                                when (blurTintMode) {
                                    BlurTintMode.NONE -> Unit

                                    BlurTintMode.SOLID -> {
                                        val hsv = remember(blurTintColor) {
                                            FloatArray(3).also {
                                                android.graphics.Color.colorToHSV(blurTintColor, it)
                                            }
                                        }
                                        val selectTintColorCd = stringResource(R.string.crop_tint_swatch_cd)
                                        val selectedTintColorCd = stringResource(R.string.crop_tint_selected_cd)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            TINT_SWATCH_COLORS.forEachIndexed { idx, swatchColor ->
                                                val selected = blurTintColor == swatchColor
                                                Box(
                                                    modifier = Modifier
                                                        .size(48.dp)
                                                        .clip(CircleShape)
                                                        .clickable(
                                                            role = Role.RadioButton,
                                                            onClickLabel = selectTintColorCd
                                                        ) { blurTintColor = swatchColor }
                                                        .testTag("tint_swatch_$idx"),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(34.dp)
                                                            .clip(CircleShape)
                                                            .background(Color(swatchColor))
                                                            .border(
                                                                width = if (selected) 2.5.dp else 1.dp,
                                                                color = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.55f),
                                                                shape = CircleShape
                                                            ),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        if (selected) {
                                                            Icon(
                                                                Icons.Default.Check,
                                                                contentDescription = selectedTintColorCd,
                                                                tint = if (Color(swatchColor).luminanceCompat() > 0.6f) Color.Black else Color.White,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(blurTintColor))
                                                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                            )
                                            Text(
                                                stringResource(R.string.crop_custom_hue),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(6.dp)
                                                    .clip(RoundedCornerShape(3.dp))
                                                    .background(
                                                        Brush.horizontalGradient(
                                                            listOf(
                                                                Color.Red,
                                                                Color.Yellow,
                                                                Color.Green,
                                                                Color.Cyan,
                                                                Color.Blue,
                                                                Color.Magenta,
                                                                Color.Red
                                                            )
                                                        )
                                                    )
                                            )
                                        }
                                        Slider(
                                            value = hsv[0],
                                            onValueChange = { hue ->
                                                val sat = hsv[1].coerceAtLeast(0.75f)
                                                val value = hsv[2].coerceAtLeast(0.88f)
                                                blurTintColor = android.graphics.Color.HSVToColor(
                                                    floatArrayOf(hue.coerceIn(0f, 360f), sat, value)
                                                )
                                            },
                                            valueRange = 0f..360f,
                                            modifier = Modifier.testTag("tint_hue_slider")
                                        )
                                        Text(
                                            stringResource(R.string.crop_tint_intensity, (blurTintIntensity * 100).toInt()),
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                        Slider(
                                            value = blurTintIntensity,
                                            onValueChange = { blurTintIntensity = it },
                                            valueRange = 0f..1f,
                                            modifier = Modifier.testTag("tint_intensity_slider")
                                        )
                                    }

                                    BlurTintMode.RAINBOW -> {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            BlurRainbowStyle.values().forEach { style ->
                                                FilterChip(
                                                    selected = blurRainbowStyle == style,
                                                    onClick = { blurRainbowStyle = style },
                                                    label = { Text(stringResource(blurRainbowStyleLabelRes(style)), style = MaterialTheme.typography.labelSmall) },
                                                    modifier = Modifier.testTag("rainbow_style_${style.name.lowercase()}")
                                                )
                                            }
                                        }
                                        Text(
                                            stringResource(R.string.crop_rainbow_intensity, (blurTintIntensity * 100).toInt()),
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                        Slider(
                                            value = blurTintIntensity,
                                            onValueChange = { blurTintIntensity = it },
                                            valueRange = 0f..1f,
                                            modifier = Modifier.testTag("tint_intensity_slider")
                                        )
                                    }
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ZoomIn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.crop_zoom_value, String.format(Locale.US, "%.1f", zoom)),
                                style = MaterialTheme.typography.labelMedium
                            )
                            Spacer(Modifier.width(12.dp))
                            Slider(
                                value = zoom,
                                onValueChange = { zoom = it },
                                valueRange = 1f..3f,
                                modifier = Modifier.weight(1f).testTag("crop_zoom_slider")
                            )
                        }
                        Text(
                            stringResource(
                                R.string.crop_parallax_value,
                                (parallaxAmount * 100).toInt(),
                                with(density) { leftPreviewTravelPx.toDp().value.roundToInt() },
                                with(density) { rightPreviewTravelPx.toDp().value.roundToInt() }
                            ),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Slider(
                            value = parallaxAmount,
                            onValueChange = {
                                parallaxAmount = it
                            },
                            valueRange = 0f..1f,
                            modifier = Modifier.testTag("crop_parallax_slider")
                        )
                        Text(
                            stringResource(R.string.crop_parallax_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Default.SwapHoriz,
                                    contentDescription = null,
                                    tint = if (symmetricParallax) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        stringResource(R.string.crop_symmetric_parallax),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        stringResource(R.string.crop_symmetric_parallax_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = symmetricParallax,
                                onCheckedChange = { symmetricParallax = it },
                                modifier = Modifier.testTag("toggle_symmetric_parallax")
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            OutlinedButton(
                                onClick = { panX = 0f; panY = 0f; zoom = 1f },
                                contentPadding = ButtonDefaults.TextButtonContentPadding
                            ) { Text(stringResource(R.string.crop_pos_center), style = MaterialTheme.typography.labelSmall) }
                            OutlinedButton(onClick = { panY = 1f }, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                                Text(stringResource(R.string.crop_pos_top), style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(onClick = { panY = -1f }, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                                Text(stringResource(R.string.crop_pos_bottom), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    if (isAlreadyDark) Icons.Default.DarkMode else Icons.Default.LightMode,
                                    contentDescription = null,
                                    tint = if (isAlreadyDark) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(stringResource(R.string.crop_already_dark), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text(
                                        stringResource(R.string.crop_already_dark_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = isAlreadyDark,
                                onCheckedChange = { isAlreadyDark = it },
                                modifier = Modifier.testTag("toggle_is_already_dark")
                            )
                        }
                        AnimatedVisibility(visible = !isAlreadyDark) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            Icons.Default.Visibility,
                                            contentDescription = null,
                                            tint = if (previewDarkened) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                stringResource(R.string.crop_preview_darkened),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                stringResource(R.string.crop_preview_darkened_desc),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Switch(
                                        checked = previewDarkened,
                                        onCheckedChange = { previewDarkened = it },
                                        modifier = Modifier.testTag("toggle_preview_darkened")
                                    )
                                }
                                Text(
                                    text = stringResource(
                                        R.string.crop_unified_darken_note,
                                        (effectiveUnifiedDimFactor * 100f).roundToInt()
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.testTag("crop_unified_darken_note")
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                }
            }

            // Exclusive 48dp-tall resize touch zone centered over the drag pill to prevent misclicks
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, dividerTouchOffsetYPx) }
                    .size(width = touchHandleWidth, height = touchHandleHeight)
                    .semantics { contentDescription = dividerCd }
                    .pointerInput(availableHeightPx, minFraction, maxFraction) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                val next = (splitFraction + dragAmount / availableHeightPx)
                                    .coerceIn(minFraction, maxFraction)
                                splitFraction = next
                            }
                        )
                    }
                    .testTag("crop_split_drag_handle")
            )
            }
        }
    }
}

@Composable
private fun SplitDividerRow(
    modifier: Modifier = Modifier,
    showLegend: Boolean,
    legendContent: @Composable () -> Unit,
    dividerLineContent: @Composable () -> Unit,
    centerHandleContent: @Composable () -> Unit,
    modeBadgeContent: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val edgePaddingPx = with(density) { 16.dp.roundToPx() }
    val pillToHandleGapPx = with(density) { 8.dp.roundToPx() }
    val pillToLineGapPx = with(density) { 10.dp.roundToPx() }
    val maxHalfLineWidthPx = with(density) { 84.dp.roundToPx() }

    Layout(
        modifier = modifier,
        content = {
            dividerLineContent()
            centerHandleContent()
            modeBadgeContent()
            if (showLegend) {
                legendContent()
            }
        }
    ) { measurables, constraints ->
        val totalWidth = constraints.maxWidth
        val totalHeight = constraints.maxHeight
        val centerX = totalWidth / 2

        val lineMeasurable = measurables[0]
        val centerHandleMeasurable = measurables[1]
        val modeBadgeMeasurable = measurables[2]
        val legendMeasurable = measurables.getOrNull(3)

        val relaxedConstraints = Constraints(
            minWidth = 0,
            maxWidth = totalWidth,
            minHeight = 0,
            maxHeight = totalHeight
        )
        val centerHandlePlaceable = centerHandleMeasurable.measure(relaxedConstraints)

        val maxSideWidth = ((totalWidth - centerHandlePlaceable.width) / 2 -
            edgePaddingPx - pillToHandleGapPx).coerceAtLeast(0)
        val sideConstraints = Constraints(
            minWidth = 0,
            maxWidth = maxSideWidth,
            minHeight = 0,
            maxHeight = totalHeight
        )
        val modeBadgePlaceable = modeBadgeMeasurable.measure(sideConstraints)
        val legendPlaceable = legendMeasurable?.measure(sideConstraints)

        val leftBoundary = (legendPlaceable?.let { edgePaddingPx + it.width } ?: edgePaddingPx) +
            pillToLineGapPx
        val rightBoundary = (totalWidth - edgePaddingPx - modeBadgePlaceable.width) -
            pillToLineGapPx
        val halfAvailableLeft = (centerX - leftBoundary).coerceAtLeast(0)
        val halfAvailableRight = (rightBoundary - centerX).coerceAtLeast(0)
        val halfLineWidth = minOf(halfAvailableLeft, halfAvailableRight, maxHalfLineWidthPx)
            .coerceAtLeast(0)
        val lineWidth = if (halfLineWidth * 2 > centerHandlePlaceable.width) {
            halfLineWidth * 2
        } else {
            0
        }
        val linePlaceable = lineMeasurable.measure(
            Constraints(
                minWidth = lineWidth,
                maxWidth = lineWidth,
                minHeight = 0,
                maxHeight = totalHeight
            )
        )

        layout(totalWidth, totalHeight) {
            if (lineWidth > 0) {
                linePlaceable.placeRelative(
                    x = centerX - linePlaceable.width / 2,
                    y = (totalHeight - linePlaceable.height) / 2
                )
            }
            centerHandlePlaceable.placeRelative(
                x = centerX - centerHandlePlaceable.width / 2,
                y = (totalHeight - centerHandlePlaceable.height) / 2
            )
            legendPlaceable?.placeRelative(
                x = edgePaddingPx,
                y = (totalHeight - legendPlaceable.height) / 2
            )
            modeBadgePlaceable.placeRelative(
                x = totalWidth - edgePaddingPx - modeBadgePlaceable.width,
                y = (totalHeight - modeBadgePlaceable.height) / 2
            )
        }
    }
}

private val TINT_SWATCH_COLORS = listOf(
    0xFF38BDF8.toInt(), // Ice Cyan (Win7 Aero signature)
    0xFF6366F1.toInt(), // Royal Indigo
    0xFF10B981.toInt(), // Emerald Mint
    0xFFF43F5E.toInt(), // Coral Rose
    0xFFF59E0B.toInt(), // Warm Amber
    0xFFA855F7.toInt(), // Orchid Violet
    0xFF1E293B.toInt(), // Deep Obsidian Slate
    0xFFF8FAFC.toInt()  // Pure Frost Pearl
)

private fun blurStyleIcon(style: BlurStyle): ImageVector = when (style) {
    BlurStyle.SMOOTH_GAUSSIAN -> Icons.Default.BlurOn
    BlurStyle.WIN7_AERO -> Icons.Default.AutoAwesome
    BlurStyle.MODERN_ACRYLIC -> Icons.Default.Grain
    BlurStyle.SILKY_VIGNETTE -> Icons.Default.Vignette
    BlurStyle.BOKEH_BLOOM -> Icons.Default.LensBlur
    BlurStyle.PRISM_REFRACTION -> Icons.Default.Flare
}

private fun Color.luminanceCompat(): Float =
    0.299f * red + 0.587f * green + 0.114f * blue

