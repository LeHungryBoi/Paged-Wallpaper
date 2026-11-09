package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.WallpaperViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun MediaLibraryScreen(
    viewModel: WallpaperViewModel
) {
    val context = LocalContext.current
    val originals by viewModel.originals.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    val thumbnails = remember { mutableStateMapOf<String, Bitmap>() }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.importMedia(uri)
        }
    }

    LaunchedEffect(originals) {
        withContext(Dispatchers.IO) {
            val directory = File(context.filesDir, "originals")
            originals.forEach { media ->
                if (!thumbnails.containsKey(media.id)) {
                    val file = File(directory, media.fileName)
                    if (file.exists()) {
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = (maxOf(media.width, media.height) / 200).coerceAtLeast(1)
                        }
                        BitmapFactory.decodeFile(file.absolutePath, options)?.let {
                            thumbnails[media.id] = it
                        }
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Media Library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Text("${originals.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Original image copies are kept here. Cropping, fill, and parallax settings belong to each preset page.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isImporting) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Importing…", modifier = Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall)
                }
            }

            if (originals.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(
                            Icons.Default.AddPhotoAlternate,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text("Media Library is empty", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Button(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier.testTag("empty_state_import_button")
                        ) {
                            Text("Import media")
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(originals, key = { it.id }) { media ->
                        val thumbnail = thumbnails[media.id]
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("media_card_${media.id}"),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(2.dp)
                        ) {
                            Column {
                                Box(
                                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color.DarkGray),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (thumbnail != null) {
                                        Image(
                                            bitmap = thumbnail.asImageBitmap(),
                                            contentDescription = "Media preview",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(bottomEnd = 10.dp),
                                        color = Color.Black.copy(alpha = 0.65f),
                                        modifier = Modifier.align(Alignment.TopStart)
                                    ) {
                                        Icon(
                                            if (media.isDarkDetected) Icons.Default.DarkMode else Icons.Default.LightMode,
                                            contentDescription = if (media.isDarkDetected) "Dark original" else "Original image",
                                            tint = if (media.isDarkDetected) Color.Yellow else Color.White,
                                            modifier = Modifier.padding(6.dp).size(16.dp)
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "${media.width} × ${media.height}",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            "Original image copy",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.deleteOriginal(media.id) },
                                        modifier = Modifier.size(36.dp).testTag("delete_media_${media.id}")
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete media", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }
        }

        if (originals.isNotEmpty()) {
            FloatingActionButton(
                onClick = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp).testTag("import_fab"),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Import media")
            }
        }
    }
}
