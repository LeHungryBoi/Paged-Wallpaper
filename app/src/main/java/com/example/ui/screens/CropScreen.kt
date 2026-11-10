package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.CropFillMode
import com.example.data.model.OriginalMedia
import com.example.data.model.PageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private fun calculatePreviewTravel(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Float,
    targetHeight: Float,
    scale: Float,
    panX: Float,
    cropFillMode: CropFillMode,
    amount: Float
): Pair<Float, Float> {
    if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0f || targetHeight <= 0f) {
        return 0f to 0f
    }

    val sourceRatio = sourceWidth.toFloat() / sourceHeight
    val targetRatio = targetWidth / targetHeight
    val baseCropWidth = if (sourceRatio > targetRatio) sourceHeight * targetRatio else sourceWidth.toFloat()
    val cropWidth = if (cropFillMode == CropFillMode.CROP) {
        baseCropWidth / scale.coerceIn(1f, 3f)
    } else {
        baseCropWidth
    }
    val left = if (cropFillMode == CropFillMode.CROP) {
        val centerX = sourceWidth / 2f -
            panX.coerceIn(-1f, 1f) * ((sourceWidth - cropWidth) / 2f).coerceAtLeast(0f)
        (centerX - cropWidth / 2f).coerceIn(0f, sourceWidth - cropWidth)
    } else {
        val fitScale = minOf(targetWidth / sourceWidth, targetHeight / sourceHeight) *
            scale.coerceIn(1f, 3f)
        val drawWidth = sourceWidth * fitScale
        val centerX = targetWidth / 2f +
            panX.coerceIn(-1f, 1f) * ((drawWidth - targetWidth) / 2f).coerceAtLeast(0f)
        centerX - drawWidth / 2f
    }
    val availableLeftTravel: Float
    val availableRightTravel: Float
    if (cropFillMode == CropFillMode.CROP) {
        availableLeftTravel = left * targetWidth / cropWidth
        availableRightTravel = (sourceWidth - left - cropWidth) * targetWidth / cropWidth
    } else {
        availableLeftTravel = (-left).coerceAtLeast(0f)
        availableRightTravel = (left + sourceWidth * minOf(
            targetWidth / sourceWidth,
            targetHeight / sourceHeight
        ) * scale.coerceIn(1f, 3f) - targetWidth).coerceAtLeast(0f)
    }
    val requestedTravel = targetWidth * 0.25f * amount.coerceIn(0f, 1f)
    return minOf(requestedTravel, availableLeftTravel).coerceAtLeast(0f) to
        minOf(requestedTravel, availableRightTravel).coerceAtLeast(0f)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaEditorScreen(
    media: OriginalMedia,
    page: PageItem,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (PageItem) -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember(media.id) { mutableStateOf<Bitmap?>(null) }
    var blurredBitmap by remember(media.id) { mutableStateOf<Bitmap?>(null) }
    var panX by remember(media.id, page.pageIndex) { mutableFloatStateOf(page.cropPanX) }
    var panY by remember(media.id, page.pageIndex) { mutableFloatStateOf(page.cropPanY) }
    var zoom by remember(media.id, page.pageIndex) { mutableFloatStateOf(page.cropScale) }
    var isAlreadyDark by remember(media.id, page.pageIndex) { mutableStateOf(page.isAlreadyDark) }
    var cropFillMode by remember(media.id, page.pageIndex) { mutableStateOf(page.cropFillMode) }
    var blurAmount by remember(media.id, page.pageIndex) { mutableFloatStateOf(page.blurAmount) }
    var parallaxAmount by remember(media.id, page.pageIndex) { mutableFloatStateOf(page.parallaxAmount) }
    var expandedPreview by remember(media.id) { mutableStateOf<String?>(null) }

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

    LaunchedEffect(media.id, blurAmount, bitmap) {
        val source = bitmap ?: return@LaunchedEffect
        val blurScale = 1f - blurAmount.coerceIn(0f, 1f) * 0.94f
        val previewScale = minOf(blurScale, 512f / maxOf(source.width, source.height))
        val preview = withContext(Dispatchers.IO) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * previewScale).toInt().coerceAtLeast(1),
                (source.height * previewScale).toInt().coerceAtLeast(1),
                true
            )
        }
        blurredBitmap?.takeIf { it !== preview && it !== source && !it.isRecycled }?.recycle()
        blurredBitmap = preview
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Edit page", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Settings are saved with this preset · ${media.width} × ${media.height}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss, enabled = !isSaving, modifier = Modifier.testTag("crop_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            onSave(
                                page.copy(
                                    isAlreadyDark = isAlreadyDark,
                                    cropFillMode = cropFillMode,
                                    blurAmount = blurAmount,
                                    cropPanX = panX,
                                    cropPanY = panY,
                                    cropScale = zoom,
                                    parallaxAmount = parallaxAmount
                                )
                            )
                        },
                        enabled = !isSaving && bitmap != null,
                        modifier = Modifier.weight(1.5f).height(50.dp).testTag("crop_confirm_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isSaving) "Saving…" else "Save page settings")
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
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val defaultViewportHeight = (availableHeight * 0.40f).coerceIn(180.dp, 310.dp)
                val largeViewportHeight = (availableHeight * 0.58f).coerceIn(240.dp, 420.dp)
                val smallViewportHeight = (defaultViewportHeight * 0.62f).coerceAtLeast(120.dp)
                val viewportHeight by animateDpAsState(
                    targetValue = when (expandedPreview) {
                        "page" -> largeViewportHeight
                        "full" -> smallViewportHeight
                        else -> defaultViewportHeight
                    }
                )
                val fullImageHeight by animateDpAsState(
                    targetValue = when (expandedPreview) {
                        "full" -> (availableHeight * 0.42f).coerceIn(260.dp, 420.dp)
                        "page" -> 110.dp
                        else -> 190.dp
                    }
                )
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
                        amount = parallaxAmount
                    )
                } ?: (0f to 0f)
                val leftPreviewTravelPx = previewTravel.first
                val rightPreviewTravelPx = previewTravel.second
                Box(
                    modifier = Modifier
                        .animateContentSize()
                        .size(width = viewportWidth, height = viewportHeight)
                        .clip(RoundedCornerShape(18.dp))
                        .clipToBounds()
                        .background(Color.Black)
                        .border(
                            if (expandedPreview == "page") 3.dp else 2.dp,
                            if (expandedPreview == "page") MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            RoundedCornerShape(18.dp)
                        )
                        .clickable(
                            onClick = {
                                expandedPreview = if (expandedPreview == "page") null else "page"
                            }
                        )
                        .pointerInput(bitmap, cropFillMode, zoom) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                panX = (panX + dragAmount.x / size.width * 2f).coerceIn(-1f, 1f)
                                panY = (panY + dragAmount.y / size.height * 2f).coerceIn(-1f, 1f)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val source = bitmap
                    if (source != null) {
                        if (cropFillMode != CropFillMode.CROP) {
                            Image(
                                bitmap = (if (blurAmount == 0f) source else blurredBitmap ?: source).asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .blur(if (blurredBitmap == null) (blurAmount * 24f).dp else 0.dp)
                            )
                            if (cropFillMode == CropFillMode.GLASS) {
                                Box(Modifier.fillMaxSize().background(Color(0x5018202C)))
                            }
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
                            val paint = android.graphics.Paint(
                                android.graphics.Paint.ANTI_ALIAS_FLAG or
                                    android.graphics.Paint.FILTER_BITMAP_FLAG
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
                                        paint
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
                                    nativeCanvas.drawBitmap(
                                        source,
                                        null,
                                        RectF(
                                            centerX - imageWidth / 2f,
                                            centerY - imageHeight / 2f,
                                            centerX + imageWidth / 2f,
                                            centerY + imageHeight / 2f
                                        ),
                                        paint
                                    )
                                }
                            }
                        }
                    } else {
                        Text("Unable to load image", color = Color.White)
                    }
                    Box(Modifier.fillMaxSize().border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(18.dp)))
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "Full image: static page and parallax reach",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                val isFullPreviewExpanded = expandedPreview == "full"
                val fullPreviewBorder = if (isFullPreviewExpanded) 3.dp else 1.dp
                val fullPreviewShape = RoundedCornerShape(14.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                        .height(fullImageHeight + if (isFullPreviewExpanded) fullPreviewBorder * 2 else 0.dp)
                        .clip(fullPreviewShape)
                        .then(
                            if (isFullPreviewExpanded) {
                                Modifier.border(
                                    fullPreviewBorder,
                                    MaterialTheme.colorScheme.primary,
                                    fullPreviewShape
                                )
                            } else {
                                Modifier
                            }
                        )
                        .clickable(
                            onClick = {
                                expandedPreview = if (isFullPreviewExpanded) null else "full"
                            }
                        )
                        .testTag("crop_parallax_bounds_preview")
                ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(if (isFullPreviewExpanded) fullPreviewBorder else 0.dp)
                        .clip(RoundedCornerShape(if (isFullPreviewExpanded) 11.dp else 14.dp))
                        .background(Color.Black)
                        .then(
                            if (isFullPreviewExpanded) {
                                Modifier
                            } else {
                                Modifier.border(
                                    fullPreviewBorder,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    fullPreviewShape
                                )
                            }
                        )
                ) {
                    val source = bitmap ?: return@Canvas
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
                    val nativeCanvas = drawContext.canvas.nativeCanvas
                    val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    nativeCanvas.drawBitmap(
                        source,
                        null,
                        RectF(imageLeft, imageTop, imageLeft + imageWidth, imageTop + imageHeight),
                        imagePaint
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Static page",
                        color = Color(0xFF50DCFF),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        "Maximum parallax reach",
                        color = Color(0xFFFFB400),
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                Spacer(Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Image fit", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            CropFillMode.values().forEach { mode ->
                                FilterChip(
                                    selected = cropFillMode == mode,
                                    onClick = { cropFillMode = mode },
                                    label = { Text(mode.displayName, style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        if (cropFillMode != CropFillMode.CROP) {
                            Text("Background blur: ${(blurAmount * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                            Slider(
                                value = blurAmount,
                                onValueChange = { blurAmount = it },
                                valueRange = 0f..1f,
                                modifier = Modifier.testTag("crop_blur_slider")
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ZoomIn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Zoom: ${String.format("%.1fx", zoom)}", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(12.dp))
                            Slider(
                                value = zoom,
                                onValueChange = { zoom = it },
                                valueRange = 1f..3f,
                                modifier = Modifier.weight(1f).testTag("crop_zoom_slider")
                            )
                        }
                        Text(
                            "Parallax: ${(parallaxAmount * 100).toInt()}% · left ${with(density) { leftPreviewTravelPx.toDp().value.roundToInt() }} dp / right ${with(density) { rightPreviewTravelPx.toDp().value.roundToInt() }} dp",
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
                            "Requires the Parallax Scroll transition to be selected.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            OutlinedButton(
                                onClick = { panX = 0f; panY = 0f; zoom = 1f },
                                contentPadding = ButtonDefaults.TextButtonContentPadding
                            ) { Text("Center", style = MaterialTheme.typography.labelSmall) }
                            OutlinedButton(onClick = { panY = 1f }, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                                Text("Top", style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(onClick = { panY = -1f }, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                                Text("Bottom", style = MaterialTheme.typography.labelSmall)
                            }
                        }
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
                                    Text("Already dark", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text(
                                        "Skip automatic dimming for this media",
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
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}
