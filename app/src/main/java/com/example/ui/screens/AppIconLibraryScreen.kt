package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

private const val APP_ICON_SHORTCUT_ID = "paperpages_custom_app_icon"
private const val APP_ICON_SIZE = 512
private const val APP_ICON_PREFS = "app_icon_settings"
private const val MIN_ICON_ZOOM = 0.1f
private const val MAX_ICON_ZOOM = 100f
private const val ICON_ZOOM_STEP = 1.2f

@Composable
fun AppIconLibraryScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sourceFile = remember(context) { File(context.filesDir, "app-icon-source") }
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var hasCustomImage by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var flipped by remember { mutableStateOf(false) }
    var isReady by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sourceFile) {
        val saved = withContext(Dispatchers.IO) {
            val preferences = context.getSharedPreferences(APP_ICON_PREFS, Context.MODE_PRIVATE)
            val customImage = sourceFile.isFile
            val bitmap = if (customImage) {
                decodeBitmap(sourceFile)
            } else {
                decodeDefaultArtwork(context)
            }
            IconEditorState(
                bitmap = bitmap,
                hasCustomImage = customImage,
                zoom = preferences.getFloat("zoom", 1f).coerceIn(MIN_ICON_ZOOM, MAX_ICON_ZOOM),
                panX = preferences.getFloat("pan_x", 0f),
                panY = preferences.getFloat("pan_y", 0f),
                flipped = preferences.getBoolean("flipped", false)
            )
        }
        source = saved.bitmap
        hasCustomImage = saved.hasCustomImage
        zoom = saved.zoom
        panX = saved.panX
        panY = saved.panY
        flipped = saved.flipped
        if (saved.bitmap == null) {
            error = "Could not read the saved icon image. Choose another image to continue."
        }
        isReady = true
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            isWorking = true
            error = null
            status = null
            scope.launch {
                try {
                    val loaded = withContext(Dispatchers.IO) {
                        copyAndDecodeImage(context, uri, sourceFile)
                    }
                    source = loaded
                    hasCustomImage = true
                    zoom = 1f
                    panX = 0f
                    panY = 0f
                    flipped = false
                    persistFraming(context, zoom, panX, panY, flipped)
                } catch (e: Exception) {
                    error = "Could not load this image: ${e.localizedMessage ?: "unknown error"}"
                } finally {
                    isWorking = false
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("App icon", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Choose a photo, then drag or pinch it freely behind the three windows.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Card(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .testTag("app_icon_preview_card"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                if (source != null) {
                    IconArtworkPreview(
                        bitmap = source!!,
                        zoom = zoom,
                        panX = panX,
                        panY = panY,
                        flipped = flipped,
                        onTransform = { centroidX, centroidY, scaleChange, deltaX, deltaY, width, height ->
                            val newZoom = (zoom * scaleChange).coerceIn(MIN_ICON_ZOOM, MAX_ICON_ZOOM)
                            val appliedScale = newZoom / zoom
                            panX = appliedScale * panX +
                                ((centroidX - width / 2f) * (1f - appliedScale) + deltaX) / width
                            panY = appliedScale * panY +
                                ((centroidY - height / 2f) * (1f - appliedScale) + deltaY) / height
                            zoom = newZoom
                            persistFraming(context, zoom, panX, panY, flipped)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("app_icon_preview")
                    )
                } else if (!isReady) {
                    CircularProgressIndicator()
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (hasCustomImage) "Custom image" else "PaperPages artwork",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "One image and its framing settings",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        enabled = isReady && !isWorking,
                        modifier = Modifier.testTag("choose_app_icon_image")
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                        Text("Choose")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Zoom ${(zoom * 100f).roundToInt()}%",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            zoom = (zoom / ICON_ZOOM_STEP).coerceAtLeast(MIN_ICON_ZOOM)
                            persistFraming(context, zoom, panX, panY, flipped)
                        },
                        enabled = isReady && source != null && zoom > MIN_ICON_ZOOM,
                        modifier = Modifier.testTag("app_icon_zoom_out")
                    ) {
                        Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out")
                    }
                    IconButton(
                        onClick = {
                            zoom = (zoom * ICON_ZOOM_STEP).coerceAtMost(MAX_ICON_ZOOM)
                            persistFraming(context, zoom, panX, panY, flipped)
                        },
                        enabled = isReady && source != null && zoom < MAX_ICON_ZOOM,
                        modifier = Modifier.testTag("app_icon_zoom_in")
                    ) {
                        Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in")
                    }
                    IconButton(
                        onClick = {
                            flipped = !flipped
                            persistFraming(context, zoom, panX, panY, flipped)
                        },
                        enabled = isReady && source != null,
                        modifier = Modifier.testTag("app_icon_flip")
                    ) {
                        Icon(
                            Icons.Default.Flip,
                            contentDescription = if (flipped) "Unflip image" else "Flip image",
                            tint = if (flipped) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                OutlinedButton(
                    onClick = {
                        zoom = 1f
                        panX = 0f
                        panY = 0f
                        flipped = false
                        persistFraming(context, zoom, panX, panY, flipped)
                    },
                    enabled = isReady && (zoom != 1f || panX != 0f || panY != 0f || flipped),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null)
                    Text("Reset framing")
                }
            }
        }

        if (error != null) {
            Text(
                error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("app_icon_error")
            )
        }
        if (status != null) {
            Text(
                status!!,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("app_icon_status")
            )
        }

        Button(
            onClick = {
                val currentSource = source ?: return@Button
                isWorking = true
                error = null
                status = null
                scope.launch {
                    try {
                        val icon = withContext(Dispatchers.IO) {
                            AppIconArtwork.render(currentSource, zoom, panX, panY, flipped)
                        }
                        status = applyHomeScreenIcon(context, icon)
                    } catch (e: Exception) {
                        error = "Could not apply the icon: ${e.localizedMessage ?: "unknown error"}"
                    } finally {
                        isWorking = false
                    }
                }
            },
            enabled = isReady && source != null && !isWorking,
            modifier = Modifier.fillMaxWidth().testTag("apply_app_icon_button")
        ) {
            if (isWorking) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
            }
            Text("Save & set home screen icon")
        }

        Text(
            "Android does not let apps replace their installed launcher icon at runtime. This creates or updates a PaperPages home-screen shortcut with your image.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private data class IconEditorState(
    val bitmap: Bitmap?,
    val hasCustomImage: Boolean,
    val zoom: Float,
    val panX: Float,
    val panY: Float,
    val flipped: Boolean
)

private fun persistFraming(context: Context, zoom: Float, panX: Float, panY: Float, flipped: Boolean) {
    context.getSharedPreferences(APP_ICON_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putFloat("zoom", zoom)
        .putFloat("pan_x", panX)
        .putFloat("pan_y", panY)
        .putBoolean("flipped", flipped)
        .apply()
}

@Composable
private fun IconArtworkPreview(
    bitmap: Bitmap,
    zoom: Float,
    panX: Float,
    panY: Float,
    flipped: Boolean,
    onTransform: (
        centroidX: Float,
        centroidY: Float,
        scaleChange: Float,
        deltaX: Float,
        deltaY: Float,
        width: Float,
        height: Float
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestOnTransform by rememberUpdatedState(onTransform)
    Canvas(
        modifier = modifier.pointerInput(bitmap) {
            detectTransformGestures { centroid, pan, zoomChange, _ ->
                latestOnTransform(
                    centroid.x,
                    centroid.y,
                    zoomChange,
                    pan.x,
                    pan.y,
                    size.width.toFloat(),
                    size.height.toFloat()
                )
            }
        }
    ) {
        drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            val sizePx = size.width
            val fit = max(sizePx / bitmap.width, size.height / bitmap.height) * zoom
            val width = bitmap.width * fit
            val height = bitmap.height * fit
            val left = (sizePx - width) / 2f + panX * sizePx
            val top = (size.height - height) / 2f + panY * size.height
            canvas.drawColor(android.graphics.Color.rgb(20, 24, 36))
            canvas.save()
            canvas.clipPath(AppIconArtwork.windowPath(sizePx))
            if (flipped) {
                canvas.scale(-1f, 1f, left + width / 2f, top + height / 2f)
            }
            canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        }
    }
}

private object AppIconArtwork {
    fun windowPath(size: Float): Path = Path().apply {
        val windowWidth = size * 0.18f
        val windowHeight = size * 0.62f
        val radius = size * 0.045f
        val left = size * 0.13f
        val middle = (size - windowWidth) / 2f
        val right = size * 0.69f
        addRoundRect(
            RectF(left, size * 0.27f, left + windowWidth, size * 0.27f + windowHeight),
            radius,
            radius,
            Path.Direction.CW
        )
        addRoundRect(
            RectF(middle, size * 0.19f, middle + windowWidth, size * 0.19f + windowHeight),
            radius,
            radius,
            Path.Direction.CW
        )
        addRoundRect(
            RectF(right, size * 0.11f, right + windowWidth, size * 0.11f + windowHeight),
            radius,
            radius,
            Path.Direction.CW
        )
    }

    fun render(source: Bitmap, zoom: Float, panX: Float, panY: Float, flipped: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(APP_ICON_SIZE, APP_ICON_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(20, 24, 36))
        canvas.save()
        canvas.clipPath(windowPath(APP_ICON_SIZE.toFloat()))
        val fit = max(
            APP_ICON_SIZE.toFloat() / source.width,
            APP_ICON_SIZE.toFloat() / source.height
        ) * zoom.coerceIn(MIN_ICON_ZOOM, MAX_ICON_ZOOM)
        val width = source.width * fit
        val height = source.height * fit
        val left = (APP_ICON_SIZE - width) / 2f + panX * APP_ICON_SIZE
        val top = (APP_ICON_SIZE - height) / 2f + panY * APP_ICON_SIZE
        if (flipped) {
            canvas.scale(-1f, 1f, left + width / 2f, top + height / 2f)
        }
        canvas.drawBitmap(
            source,
            null,
            RectF(left, top, left + width, top + height),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        canvas.restore()
        return bitmap
    }
}

private fun decodeBitmap(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val sample = (max(bounds.outWidth, bounds.outHeight) / 1024).coerceAtLeast(1)
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    )
}

private fun decodeDefaultArtwork(context: Context): Bitmap? =
    BitmapFactory.decodeResource(
        context.resources,
        R.drawable.wallpaper_icon,
        BitmapFactory.Options().apply {
            inSampleSize = 2
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    )

private fun copyAndDecodeImage(context: Context, uri: Uri, destination: File): Bitmap {
    val temporary = File(destination.parentFile, "${destination.name}.tmp")
    try {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("The selected image could not be opened.")
        input.use { stream ->
            FileOutputStream(temporary).use { output -> stream.copyTo(output) }
        }
        val decoded = decodeBitmap(temporary)
            ?: throw IOException("The selected file is not a readable image.")
        if (!temporary.renameTo(destination)) {
            throw IOException("The selected image could not be saved.")
        }
        return decoded
    } finally {
        if (temporary.exists() && !temporary.delete()) {
            throw IOException("The temporary image could not be removed.")
        }
    }
}

private fun applyHomeScreenIcon(context: Context, bitmap: Bitmap): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        throw IOException("Custom home-screen icons require Android 8.0 or later.")
    }
    val shortcutManager = context.getSystemService(ShortcutManager::class.java)
        ?: throw IOException("Home-screen shortcuts are not available.")
    val shortcut = ShortcutInfo.Builder(context, APP_ICON_SHORTCUT_ID)
        .setShortLabel("PaperPages")
        .setIntent(
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        .setIcon(Icon.createWithBitmap(bitmap))
        .build()

    if (shortcutManager.pinnedShortcuts.any { it.id == APP_ICON_SHORTCUT_ID }) {
        if (!shortcutManager.updateShortcuts(listOf(shortcut))) {
            throw IOException("The launcher did not accept the updated icon.")
        }
        return "Home screen icon updated."
    } else {
        if (!shortcutManager.isRequestPinShortcutSupported) {
            throw IOException("This launcher does not support adding home-screen shortcuts.")
        }
        if (!shortcutManager.requestPinShortcut(shortcut, null)) {
            throw IOException("The launcher could not add the home-screen icon.")
        }
        return "Follow your launcher prompt to add the home-screen icon."
    }
}
