package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.example.data.model.OriginalMedia
import com.example.ui.WallpaperViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaPickerScreen(
    pageIndex: Int,
    viewModel: WallpaperViewModel,
    isAssigning: Boolean,
    onBack: () -> Unit,
    onSelectMedia: (OriginalMedia) -> Unit,
    onOpenLibrary: () -> Unit
) {
    val context = LocalContext.current
    val originals by viewModel.originals.collectAsState()
    val thumbnails = remember { mutableStateMapOf<String, Bitmap>() }

    BackHandler(enabled = !isAssigning, onBack = onBack)

    LaunchedEffect(originals) {
        withContext(Dispatchers.IO) {
            val directory = File(context.filesDir, "originals")
            originals.forEach { media ->
                if (!thumbnails.containsKey(media.id)) {
                    val file = File(directory, media.fileName)
                    if (file.exists()) {
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = (maxOf(media.width, media.height) / 300).coerceAtLeast(1)
                        }
                        BitmapFactory.decodeFile(file.absolutePath, options)?.let {
                            thumbnails[media.id] = it
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Choose for Screen ${pageIndex + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isAssigning, modifier = Modifier.testTag("media_picker_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        if (originals.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("No media yet", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onOpenLibrary, enabled = !isAssigning) {
                        Text("Open Media Library")
                    }
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(originals, key = { it.id }) { media ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isAssigning) { onSelectMedia(media) }
                                .testTag("choose_media_${media.id}"),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(2.dp)
                        ) {
                            Column {
                                Box(
                                    modifier = Modifier.fillMaxWidth().aspectRatio(0.78f).background(Color.DarkGray),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val thumbnail = thumbnails[media.id]
                                    if (thumbnail == null) {
                                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                    } else {
                                        Image(
                                            bitmap = thumbnail.asImageBitmap(),
                                            contentDescription = "Select media",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "${media.width} × ${media.height}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
                if (isAssigning) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}
