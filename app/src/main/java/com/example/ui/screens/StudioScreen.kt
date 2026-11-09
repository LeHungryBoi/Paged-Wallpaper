package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.transition.TransitionRegistry
import com.example.ui.WallpaperViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun StudioScreen(
    viewModel: WallpaperViewModel,
    onNavigateToMediaForPage: (Int) -> Unit,
    onNavigateToCalibration: () -> Unit
) {
    val context = LocalContext.current
    val appState by viewModel.appState.collectAsState()
    val isWallpaperActive by viewModel.isWallpaperActive.collectAsState()

    var testSliderOffset by remember { mutableFloatStateOf(0.5f) }
    val pageBitmaps = remember { mutableStateMapOf<Int, Bitmap>() }

    // Reload page thumbnails
    LaunchedEffect(appState.pages) {
        withContext(Dispatchers.IO) {
            val croppedDir = File(context.filesDir, "cropped")
            appState.pages.forEach { page ->
                val fileName = page.croppedFileName ?: "starter_page_${page.pageIndex}.jpg"
                val file = File(croppedDir, fileName)
                if (file.exists()) {
                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                    if (bmp != null) {
                        pageBitmaps[page.pageIndex] = bmp
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Hero Card: Live Wallpaper System Status & 1-Click Set
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("wallpaper_status_card"),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    )
                    .padding(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                if (isWallpaperActive) Icons.Filled.CheckCircle else Icons.Filled.Wallpaper,
                                contentDescription = null,
                                tint = if (isWallpaperActive) Color(0xFF10B981) else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = if (isWallpaperActive) "Active Live Wallpaper" else "Wallpaper Not Applied",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        // Status pill
                        Surface(
                            shape = CircleShape,
                            color = if (isWallpaperActive) Color(0xFF10B981).copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = if (isWallpaperActive) "RUNNING" else "STANDBY",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isWallpaperActive) Color(0xFF047857) else MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Text(
                        text = if (isWallpaperActive) {
                            "PaperPages is rendering your multi-page wallpapers seamlessly in the background as you swipe."
                        } else {
                            "Tap below to apply PaperPages as your Android dynamic wallpaper."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    )

                    Button(
                        onClick = { viewModel.applyWallpaperIntent(context) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("apply_wallpaper_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Filled.Wallpaper, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isWallpaperActive) "Re-Apply / Preview Settings" else "Apply as Live Wallpaper",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Section: Multi-Screen Carousel
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Launcher Pages (${appState.calibration.pageCount} Screens)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Custom wallpaper per screen • Tap page to crop / replace",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                FilledTonalButton(
                    onClick = onNavigateToCalibration,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("quick_calibrate_button")
                ) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Calibrate", style = MaterialTheme.typography.labelMedium)
                }
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                itemsIndexed(appState.pages) { index, page ->
                    val isHome = index == appState.calibration.homePageIndex
                    val bmp = pageBitmaps[index]

                    Card(
                        modifier = Modifier
                            .width(180.dp)
                            .testTag("page_card_$index"),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        ),
                        border = if (isHome) {
                            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        } else null
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            // Phone Screen Frame Thumbnail
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(9f / 17f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = "Page ${index + 1}",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Text(
                                        text = "Starter\nPage ${index + 1}",
                                        color = Color.White.copy(alpha = 0.6f),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                // Home badge
                                if (isHome) {
                                    Surface(
                                        shape = RoundedCornerShape(bottomEnd = 10.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.align(Alignment.TopStart)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(Icons.Filled.Home, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(12.dp))
                                            Text("HOME", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }

                                // Dark icon badge
                                if (page.isAlreadyDark) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color.Black.copy(alpha = 0.6f),
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(6.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.DarkMode,
                                            contentDescription = "Already Dark",
                                            tint = Color.Yellow,
                                            modifier = Modifier
                                                .padding(4.dp)
                                                .size(14.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "Screen ${index + 1}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                text = if (isHome) "Home Screen (Center)" else "Offset: ${String.format("%.2f", appState.calibration.customOffsets.getOrNull(index) ?: 0f)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = { onNavigateToMediaForPage(index) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("replace_page_${index}_button"),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Crop", style = MaterialTheme.typography.labelSmall)
                                }

                                if (!isHome) {
                                    OutlinedButton(
                                        onClick = { viewModel.setHomePage(index) },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.testTag("set_home_${index}_button")
                                    ) {
                                        Icon(Icons.Outlined.Home, contentDescription = "Set as Home", modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section: In-App Offset Transition Simulator
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("live_offset_simulator_card"),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Interactive Transition Simulator",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Simulate launcher swipe (${TransitionRegistry.get(appState.transitionType).displayName})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "Offset: ${String.format("%.2f", testSliderOffset)}",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                // Drag slider
                Slider(
                    value = testSliderOffset,
                    onValueChange = { testSliderOffset = it },
                    valueRange = 0.0f..1.0f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("offset_simulator_slider")
                )

                // Quick jump chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    for (i in 0 until appState.calibration.pageCount) {
                        val anchor = appState.calibration.customOffsets.getOrNull(i) ?: 0f
                        SuggestionChip(
                            onClick = { testSliderOffset = anchor },
                            label = { Text("P${i + 1} (${String.format("%.2f", anchor)})", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        }
    }
}
