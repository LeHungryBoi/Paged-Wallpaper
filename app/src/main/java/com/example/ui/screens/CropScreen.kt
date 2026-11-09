package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.OriginalMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(
    media: OriginalMedia,
    initialPageIndex: Int,
    pageCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (pageIndex: Int, panX: Float, panY: Float, scale: Float, isAlreadyDark: Boolean) -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    var selectedPageIndex by remember { mutableIntStateOf(initialPageIndex.coerceIn(0, pageCount - 1)) }
    var panX by remember { mutableFloatStateOf(0f) } // -1f to 1f
    var panY by remember { mutableFloatStateOf(0f) } // -1f to 1f
    var scale by remember { mutableFloatStateOf(1.0f) } // 1f to 3f
    var isAlreadyDark by remember { mutableStateOf(media.isDarkDetected) }

    BackHandler {
        onDismiss()
    }

    // Load full bitmap for cropping
    LaunchedEffect(media.id) {
        withContext(Dispatchers.IO) {
            val file = File(File(context.filesDir, "originals"), media.fileName)
            if (file.exists()) {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                bitmap = bmp
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Crop Wallpaper",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Assign to Screen ${selectedPageIndex + 1}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("crop_back_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            // Pinned Bottom Action Bar with proper navigation bar insets
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("crop_cancel_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            onConfirm(selectedPageIndex, panX, panY, scale, isAlreadyDark)
                        },
                        modifier = Modifier
                            .weight(1.5f)
                            .height(50.dp)
                            .testTag("crop_confirm_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Apply to Page ${selectedPageIndex + 1}")
                    }
                }
            }
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val totalHeight = maxHeight

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Dynamically size viewport based on available screen height so it NEVER exceeds bounds
                val viewportHeight = (totalHeight * 0.38f).coerceIn(180.dp, 310.dp)
                val viewportWidth = viewportHeight * (9f / 19.5f)

                // Strictly bounded and clipped viewport frame
                Box(
                    modifier = Modifier
                        .size(width = viewportWidth, height = viewportHeight)
                        .clip(RoundedCornerShape(18.dp))
                        .clipToBounds()
                        .background(Color.Black)
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp))
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                panX = (panX + (dragAmount.x / 140f)).coerceIn(-1f, 1f)
                                panY = (panY + (dragAmount.y / 180f)).coerceIn(-1f, 1f)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap!!.asImageBitmap(),
                            contentDescription = "Crop Preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clipToBounds()
                        )
                    } else {
                        Text(
                            text = "Loading preview...",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    // Framing overlay guide
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(18.dp))
                    )

                    // Center dot indicator
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(Color.White.copy(alpha = 0.6f), CircleShape)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Drag preview to pan • Adjust zoom and presets below",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Controls Container Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Zoom Slider
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.ZoomIn,
                                contentDescription = "Zoom",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Zoom: ${String.format("%.1fx", scale)}",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Slider(
                                value = scale,
                                onValueChange = { scale = it },
                                valueRange = 1.0f..3.0f,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("crop_zoom_slider")
                            )
                        }

                        // Presets
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            OutlinedButton(
                                onClick = { panX = 0f; panY = 0f; scale = 1.0f },
                                contentPadding = ButtonDefaults.TextButtonContentPadding
                            ) {
                                Text("Center", style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(
                                onClick = { panY = -1f },
                                contentPadding = ButtonDefaults.TextButtonContentPadding
                            ) {
                                Text("Top", style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(
                                onClick = { panY = 1f },
                                contentPadding = ButtonDefaults.TextButtonContentPadding
                            ) {
                                Text("Bottom", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        // Assign to Page chips
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Assign to Launcher Screen:",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                for (i in 0 until pageCount) {
                                    FilterChip(
                                        selected = selectedPageIndex == i,
                                        onClick = { selectedPageIndex = i },
                                        label = { Text("Page ${i + 1}") },
                                        leadingIcon = if (selectedPageIndex == i) {
                                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                        } else null,
                                        modifier = Modifier.testTag("chip_page_$i")
                                    )
                                }
                            }
                        }

                        // Already Dark Switch
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
                                    if (isAlreadyDark) Icons.Default.DarkMode else Icons.Default.LightMode,
                                    contentDescription = null,
                                    tint = if (isAlreadyDark) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Mark as Already Dark",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = if (isAlreadyDark) "Exempt from Extra Dim / night filter" else "Auto-dim applies during night / Extra Dim",
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

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}
