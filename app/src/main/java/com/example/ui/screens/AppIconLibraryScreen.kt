package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.MainActivity
import com.example.data.model.APP_ICON_MAX_ZOOM
import com.example.data.model.APP_ICON_MIN_ZOOM
import com.example.data.model.APP_ICON_PRESET_NAME_LIMIT
import com.example.data.model.APP_ICON_WINDOW_COUNT
import com.example.data.model.APP_ICON_ZOOM_STEP
import com.example.data.model.AppIconFrame
import com.example.data.model.AppIconLibraryState
import com.example.data.model.AppIconMode
import com.example.data.model.AppIconPreset
import com.example.data.repository.AppIconRepository
import com.example.ui.icon.AppIconArtwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt

private const val APP_ICON_SHORTCUT_ID = "paperpages_custom_app_icon"
private const val APP_ICON_EXPORT_SIZE = 512
private const val PREVIEW_INSET_FRACTION = 0.06f

private sealed interface IconPickTarget {
    data object Single : IconPickTarget
    data class Window(val index: Int) : IconPickTarget
}

private sealed interface PresetNameDialog {
    data object Create : PresetNameDialog
    data class Rename(val preset: AppIconPreset) : PresetNameDialog
}

@Composable
fun AppIconLibraryScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) { AppIconRepository(context) }

    var library by remember { mutableStateOf<AppIconLibraryState?>(null) }
    var singleBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var windowBitmaps by remember { mutableStateOf(List<Bitmap?>(APP_ICON_WINDOW_COUNT) { null }) }
    var defaultArtwork by remember { mutableStateOf<Bitmap?>(null) }
    var isReady by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var pickTarget by remember { mutableStateOf<IconPickTarget?>(null) }
    var nameDialog by remember { mutableStateOf<PresetNameDialog?>(null) }
    var nameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<AppIconPreset?>(null) }

    fun persist(current: AppIconLibraryState) {
        scope.launch(Dispatchers.IO) { repository.saveState(current) }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val target = pickTarget
        val presetId = library?.activePreset?.id
        val presetName = library?.activePreset?.name ?: "this preset"
        pickTarget = null
        if (uri == null || target == null || presetId == null) return@rememberLauncherForActivityResult
        isWorking = true
        error = null
        status = null
        scope.launch {
            try {
                val windowIndex = (target as? IconPickTarget.Window)?.index
                val loaded = withContext(Dispatchers.IO) {
                    repository.importImage(presetId, windowIndex, uri)
                }
                if (windowIndex == null) {
                    singleBitmap = loaded
                    library = library?.updatedActive { it.copy(single = AppIconFrame()) }
                } else {
                    windowBitmaps = windowBitmaps.toMutableList().also { it[windowIndex] = loaded }
                    library = library?.updatedActive { it.withWindow(windowIndex, AppIconFrame()) }
                }
                library?.let { persist(it) }
                status = "Image saved to \"$presetName\"."
            } catch (e: Exception) {
                error = "Could not load this image: ${e.localizedMessage ?: "unknown error"}"
            } finally {
                isWorking = false
            }
        }
    }

    fun updateActivePreset(transform: (AppIconPreset) -> AppIconPreset) {
        library = library?.updatedActive(transform)
    }

    fun chooseImage(target: IconPickTarget) {
        pickTarget = target
        imagePicker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    fun selectPreset(preset: AppIconPreset) {
        val current = library ?: return
        if (current.activePresetId != preset.id) {
            val updated = current.copy(activePresetId = preset.id)
            library = updated
            persist(updated)
        }
    }

    fun createPreset(rawName: String) {
        val current = library ?: return
        val preset = AppIconPreset.create(rawName)
        val updated = current.copy(presets = current.presets + preset, activePresetId = preset.id)
        library = updated
        persist(updated)
        singleBitmap = null
        windowBitmaps = List<Bitmap?>(APP_ICON_WINDOW_COUNT) { null }
        nameDialog = null
        status = "Created \"${preset.name}\". Choose images for it."
    }

    fun duplicatePreset(preset: AppIconPreset) {
        val copy = AppIconPreset(
            id = UUID.randomUUID().toString(),
            name = AppIconPreset.sanitizeName("${preset.name} copy", "Icon preset copy"),
            mode = preset.mode,
            single = preset.single,
            windows = preset.normalizedWindows()
        )
        scope.launch {
            withContext(Dispatchers.IO) { repository.copyPresetImages(preset.id, copy.id) }
            val current = library ?: return@launch
            val updated = current.copy(presets = current.presets + copy, activePresetId = copy.id)
            library = updated
            persist(updated)
            status = "Duplicated as \"${copy.name}\"."
        }
    }

    fun renamePreset(preset: AppIconPreset, rawName: String) {
        val current = library ?: return
        val name = AppIconPreset.sanitizeName(rawName, preset.name)
        val updated = current.copy(
            presets = current.presets.map { if (it.id == preset.id) it.copy(name = name) else it }
        )
        library = updated
        persist(updated)
        nameDialog = null
    }

    fun deletePreset(preset: AppIconPreset) {
        val current = library ?: return
        if (current.presets.size <= 1) {
            deleteTarget = null
            return
        }
        val remaining = current.presets.filterNot { it.id == preset.id }
        val nextActive = if (current.activePresetId == preset.id) {
            remaining.first().id
        } else {
            current.activePresetId
        }
        val updated = current.copy(presets = remaining, activePresetId = nextActive)
        library = updated
        persist(updated)
        deleteTarget = null
        scope.launch(Dispatchers.IO) { repository.deletePresetImages(preset.id) }
    }

    LaunchedEffect(Unit) {
        val (loadedState, artwork) = withContext(Dispatchers.IO) {
            repository.loadState() to repository.defaultArtwork()
        }
        library = loadedState
        defaultArtwork = artwork
        isReady = true
        if (artwork == null) {
            error = "The bundled artwork could not be loaded. Choose an image to continue."
        }
    }

    LaunchedEffect(library?.activePresetId) {
        val presetId = library?.activePresetId ?: return@LaunchedEffect
        val images = withContext(Dispatchers.IO) { repository.loadImages(presetId) }
        singleBitmap = images.single
        windowBitmaps = images.windows
    }

    LaunchedEffect(library) {
        val state = library ?: return@LaunchedEffect
        delay(250)
        withContext(Dispatchers.IO) { repository.saveState(state) }
    }

    DisposableEffect(Unit) {
        onDispose {
            library?.let { repository.saveState(it) }
        }
    }

    val state = library
    val preset = state?.activePreset

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
                "Design your shortcut icon with one image or one image per window. Every preset keeps its own images.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (state == null || preset == null) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            PresetsCard(
                presets = state.presets,
                activePresetId = state.activePresetId,
                canDelete = state.presets.size > 1,
                onSelect = { selectPreset(it) },
                onNew = {
                    nameText = "Icon preset ${state.presets.size + 1}"
                    nameDialog = PresetNameDialog.Create
                },
                onDuplicate = { duplicatePreset(it) },
                onRename = { target ->
                    nameText = target.name
                    nameDialog = PresetNameDialog.Rename(target)
                },
                onDelete = { target -> deleteTarget = target }
            )

            ModeCard(
                mode = preset.mode,
                onModeChange = { mode ->
                    val current = library
                    if (current != null && current.activePreset?.mode != mode) {
                        val updated = current.updatedActive { it.copy(mode = mode) }
                        library = updated
                        persist(updated)
                    }
                }
            )

            if (preset.mode == AppIconMode.SINGLE) {
                SingleImageSection(
                    bitmap = singleBitmap,
                    fallback = defaultArtwork,
                    frame = preset.single,
                    enabled = isReady && !isWorking,
                    onChoose = { chooseImage(IconPickTarget.Single) },
                    onFrameChange = { frame ->
                        updateActivePreset { it.copy(single = frame) }
                    }
                )
            } else {
                PerWindowSection(
                    frames = preset.normalizedWindows(),
                    bitmaps = windowBitmaps,
                    fallback = defaultArtwork,
                    enabled = isReady && !isWorking,
                    onChoose = { index -> chooseImage(IconPickTarget.Window(index)) },
                    onFrameChange = { index, frame ->
                        updateActivePreset { it.withWindow(index, frame) }
                    }
                )
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
                    val currentPreset = library?.activePreset ?: return@Button
                    val fallback = defaultArtwork ?: return@Button
                    isWorking = true
                    error = null
                    status = null
                    scope.launch {
                        try {
                            val icon = withContext(Dispatchers.IO) {
                                AppIconArtwork.compose(
                                    size = APP_ICON_EXPORT_SIZE,
                                    mode = currentPreset.mode,
                                    singleBitmap = singleBitmap,
                                    singleFrame = currentPreset.single,
                                    windowBitmaps = windowBitmaps,
                                    windowFrames = currentPreset.normalizedWindows(),
                                    fallback = fallback
                                )
                            }
                            status = applyHomeScreenIcon(context, icon)
                        } catch (e: Exception) {
                            error = "Could not apply the icon: ${e.localizedMessage ?: "unknown error"}"
                        } finally {
                            isWorking = false
                        }
                    }
                },
                enabled = isReady && !isWorking && defaultArtwork != null,
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

    nameDialog?.let { dialog ->
        val isRename = dialog is PresetNameDialog.Rename
        AlertDialog(
            onDismissRequest = { nameDialog = null },
            title = { Text(if (isRename) "Rename icon preset" else "New icon preset") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (isRename) {
                            "The preset keeps its images and framing."
                        } else {
                            "A new preset starts with the default artwork. Choose its images next."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = nameText,
                        onValueChange = { nameText = it.take(APP_ICON_PRESET_NAME_LIMIT) },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("icon_preset_name_field")
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (dialog) {
                            is PresetNameDialog.Create -> createPreset(nameText)
                            is PresetNameDialog.Rename -> renamePreset(dialog.preset, nameText)
                        }
                    },
                    enabled = nameText.isNotBlank(),
                    modifier = Modifier.testTag("confirm_icon_preset_name")
                ) {
                    Text(if (isRename) "Save" else "Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { nameDialog = null }) { Text("Cancel") }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete preset?") },
            text = {
                Text(
                    if (target.id == library?.activePresetId) {
                        "\"${target.name}\" and its images will be deleted. Another preset becomes active."
                    } else {
                        "\"${target.name}\" and its images will be deleted."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { deletePreset(target) },
                    modifier = Modifier.testTag("confirm_delete_icon_preset")
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PresetsCard(
    presets: List<AppIconPreset>,
    activePresetId: String,
    canDelete: Boolean,
    onSelect: (AppIconPreset) -> Unit,
    onNew: () -> Unit,
    onDuplicate: (AppIconPreset) -> Unit,
    onRename: (AppIconPreset) -> Unit,
    onDelete: (AppIconPreset) -> Unit
) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Icon presets",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Images and framing are saved to the selected preset.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = onNew,
                    modifier = Modifier.testTag("new_app_icon_preset")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("New")
                }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { preset ->
                    var menuExpanded by remember(preset.id) { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = preset.id == activePresetId,
                            onClick = { if (preset.id != activePresetId) onSelect(preset) },
                            label = { Text(preset.name) },
                            modifier = Modifier.testTag("icon_preset_${preset.id}")
                        )
                        Box {
                            IconButton(
                                onClick = { menuExpanded = true },
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag("icon_preset_menu_${preset.id}")
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = "${preset.name} actions"
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Duplicate") },
                                    leadingIcon = {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onDuplicate(preset)
                                    },
                                    modifier = Modifier
                                        .testTag("duplicate_icon_preset_${preset.id}")
                                )
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onRename(preset)
                                    },
                                    modifier = Modifier.testTag("rename_icon_preset_${preset.id}")
                                )
                                if (canDelete) {
                                    DropdownMenuItem(
                                        text = { Text("Delete") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            onDelete(preset)
                                        },
                                        modifier = Modifier.testTag("delete_icon_preset_${preset.id}")
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeCard(mode: AppIconMode, onModeChange: (AppIconMode) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Window layout",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == AppIconMode.SINGLE,
                    onClick = { onModeChange(AppIconMode.SINGLE) },
                    label = { Text("One image") },
                    modifier = Modifier.testTag("icon_mode_single")
                )
                FilterChip(
                    selected = mode == AppIconMode.PER_WINDOW,
                    onClick = { onModeChange(AppIconMode.PER_WINDOW) },
                    label = { Text("Per-window") },
                    modifier = Modifier.testTag("icon_mode_per_window")
                )
            }
            Text(
                if (mode == AppIconMode.SINGLE) {
                    "One photo spans all three windows. Drag or pinch the preview to frame it."
                } else {
                    "Each window can use its own photo, position and zoom. Tap a window preview to adjust it."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SingleImageSection(
    bitmap: Bitmap?,
    fallback: Bitmap?,
    frame: AppIconFrame,
    enabled: Boolean,
    onChoose: () -> Unit,
    onFrameChange: (AppIconFrame) -> Unit
) {
    val preview = bitmap ?: fallback
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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
                if (preview != null) {
                    IconMosaicPreview(
                        bitmap = preview,
                        frame = frame,
                        onFrameChange = onFrameChange,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("app_icon_preview")
                    )
                } else {
                    Text("Choose an image to preview.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (bitmap != null) "Custom image" else "PaperPages artwork",
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
                        onClick = onChoose,
                        enabled = enabled,
                        modifier = Modifier.testTag("choose_app_icon_image")
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("Choose")
                    }
                }
                FrameControls(
                    frame = frame,
                    enabled = enabled,
                    testTagPrefix = "app_icon",
                    onFrameChange = onFrameChange
                )
            }
        }
    }
}

@Composable
private fun PerWindowSection(
    frames: List<AppIconFrame>,
    bitmaps: List<Bitmap?>,
    fallback: Bitmap?,
    enabled: Boolean,
    onChoose: (Int) -> Unit,
    onFrameChange: (Int, AppIconFrame) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .testTag("app_icon_mosaic_preview_card"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                if (fallback != null || bitmaps.any { it != null }) {
                    MosaicOverview(
                        frames = frames,
                        bitmaps = bitmaps,
                        fallback = fallback,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("app_icon_mosaic_preview")
                    )
                } else {
                    Text("Choose an image to preview.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        repeat(APP_ICON_WINDOW_COUNT) { index ->
            WindowEditorCard(
                index = index,
                bitmap = bitmaps.getOrNull(index) ?: fallback,
                hasCustomImage = bitmaps.getOrNull(index) != null,
                frame = frames.getOrNull(index) ?: AppIconFrame(),
                enabled = enabled,
                onChoose = { onChoose(index) },
                onFrameChange = { frame -> onFrameChange(index, frame) }
            )
        }
    }
}

@Composable
private fun WindowEditorCard(
    index: Int,
    bitmap: Bitmap?,
    hasCustomImage: Boolean,
    frame: AppIconFrame,
    enabled: Boolean,
    onChoose: () -> Unit,
    onFrameChange: (AppIconFrame) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (bitmap != null) {
                    IconWindowPreview(
                        bitmap = bitmap,
                        frame = frame,
                        onFrameChange = onFrameChange,
                        modifier = Modifier
                            .size(78.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .testTag("icon_window_${index}_preview")
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(78.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                    }
                }
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Window ${index + 1}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (hasCustomImage) "Custom image" else "Default artwork",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = onChoose,
                        enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("choose_icon_window_$index")
                    ) {
                        Icon(
                            Icons.Default.AddPhotoAlternate,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.size(6.dp))
                        Text("Choose image")
                    }
                }
            }
            FrameControls(
                frame = frame,
                enabled = enabled,
                testTagPrefix = "icon_window_$index",
                onFrameChange = onFrameChange
            )
        }
    }
}

@Composable
private fun FrameControls(
    frame: AppIconFrame,
    enabled: Boolean,
    testTagPrefix: String,
    onFrameChange: (AppIconFrame) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Zoom", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            ZoomPercentField(
                zoom = frame.zoom,
                enabled = enabled,
                testTag = "${testTagPrefix}_zoom_field",
                onZoomChange = { value -> onFrameChange(frame.withZoom(value)) }
            )
            IconButton(
                onClick = { onFrameChange(frame.withZoom(frame.zoom / APP_ICON_ZOOM_STEP)) },
                enabled = enabled && frame.zoom > APP_ICON_MIN_ZOOM,
                modifier = Modifier.testTag("${testTagPrefix}_zoom_out")
            ) {
                Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out")
            }
            IconButton(
                onClick = { onFrameChange(frame.withZoom(frame.zoom * APP_ICON_ZOOM_STEP)) },
                enabled = enabled && frame.zoom < APP_ICON_MAX_ZOOM,
                modifier = Modifier.testTag("${testTagPrefix}_zoom_in")
            ) {
                Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { onFrameChange(frame.copy(flipped = !frame.flipped)) },
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("${testTagPrefix}_flip")
            ) {
                Icon(
                    Icons.Default.Flip,
                    contentDescription = null,
                    tint = if (frame.flipped) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        LocalContentColor.current
                    }
                )
                Spacer(Modifier.size(6.dp))
                Text(if (frame.flipped) "Flipped" else "Flip")
            }
            OutlinedButton(
                onClick = { onFrameChange(AppIconFrame()) },
                enabled = enabled && !frame.isDefault(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("${testTagPrefix}_reset")
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("Reset")
            }
        }
    }
}

@Composable
private fun ZoomPercentField(
    zoom: Float,
    enabled: Boolean,
    testTag: String,
    onZoomChange: (Float) -> Unit
) {
    val focusManager = LocalFocusManager.current
    var text by remember { mutableStateOf(formatZoomPercent(zoom)) }
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(zoom, focused) {
        if (!focused) text = formatZoomPercent(zoom)
    }

    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(4)
            text = digits
            digits.toIntOrNull()?.takeIf { it > 0 }?.let { percent ->
                onZoomChange(
                    (percent / 100f).coerceIn(APP_ICON_MIN_ZOOM, APP_ICON_MAX_ZOOM)
                )
            }
        },
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.labelLarge,
        suffix = { Text("%", style = MaterialTheme.typography.labelLarge) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = {
            text = formatZoomPercent(zoom)
            focusManager.clearFocus()
        }),
        modifier = Modifier
            .width(112.dp)
            .onFocusChanged { focused = it.isFocused }
            .testTag(testTag)
    )
}

@Composable
private fun IconMosaicPreview(
    bitmap: Bitmap,
    frame: AppIconFrame,
    onFrameChange: (AppIconFrame) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestFrame by rememberUpdatedState(frame)
    val latestOnFrameChange by rememberUpdatedState(onFrameChange)
    Canvas(
        modifier = modifier.pointerInput(bitmap) {
            detectTransformGestures { centroid, pan, zoomChange, _ ->
                val side = min(size.width, size.height).toFloat()
                if (side <= 0f) return@detectTransformGestures
                val originX = (size.width - side) / 2f
                val originY = (size.height - side) / 2f
                latestOnFrameChange(
                    latestFrame.transformed(
                        centroidX = (centroid.x - originX) / side,
                        centroidY = (centroid.y - originY) / side,
                        scaleChange = zoomChange,
                        deltaX = pan.x / side,
                        deltaY = pan.y / side
                    )
                )
            }
        }
    ) {
        drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            canvas.drawColor(AppIconArtwork.BACKGROUND_COLOR)
            val side = size.minDimension
            AppIconArtwork.drawMosaic(
                canvas = canvas,
                bitmap = bitmap,
                frame = frame,
                size = side,
                originLeft = (size.width - side) / 2f,
                originTop = (size.height - side) / 2f
            )
        }
    }
}

@Composable
private fun IconWindowPreview(
    bitmap: Bitmap,
    frame: AppIconFrame,
    onFrameChange: (AppIconFrame) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestFrame by rememberUpdatedState(frame)
    val latestOnFrameChange by rememberUpdatedState(onFrameChange)
    Canvas(
        modifier = modifier.pointerInput(bitmap) {
            detectTransformGestures { centroid, pan, zoomChange, _ ->
                val inset = min(size.width, size.height) * PREVIEW_INSET_FRACTION
                val target = AppIconArtwork.windowPreviewRect(
                    size.width.toFloat(),
                    size.height.toFloat(),
                    inset
                )
                if (target.width() <= 0f || target.height() <= 0f) {
                    return@detectTransformGestures
                }
                latestOnFrameChange(
                    latestFrame.transformed(
                        centroidX = (centroid.x - target.left) / target.width(),
                        centroidY = (centroid.y - target.top) / target.height(),
                        scaleChange = zoomChange,
                        deltaX = pan.x / target.width(),
                        deltaY = pan.y / target.height()
                    )
                )
            }
        }
    ) {
        drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            canvas.drawColor(AppIconArtwork.BACKGROUND_COLOR)
            val inset = size.minDimension * PREVIEW_INSET_FRACTION
            val target = AppIconArtwork.windowPreviewRect(size.width, size.height, inset)
            AppIconArtwork.drawFramed(
                canvas = canvas,
                bitmap = bitmap,
                frame = frame,
                target = target,
                clipPath = AppIconArtwork.windowPreviewPath(target)
            )
        }
    }
}

@Composable
private fun MosaicOverview(
    frames: List<AppIconFrame>,
    bitmaps: List<Bitmap?>,
    fallback: Bitmap?,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            canvas.drawColor(AppIconArtwork.BACKGROUND_COLOR)
            val side = size.minDimension
            val originX = (size.width - side) / 2f
            val originY = (size.height - side) / 2f
            canvas.save()
            canvas.translate(originX, originY)
            repeat(APP_ICON_WINDOW_COUNT) { index ->
                val bitmap = bitmaps.getOrNull(index) ?: fallback ?: return@repeat
                AppIconArtwork.drawWindow(
                    canvas = canvas,
                    bitmap = bitmap,
                    frame = frames.getOrNull(index) ?: AppIconFrame(),
                    size = side,
                    index = index
                )
            }
            canvas.restore()
        }
    }
}

private fun formatZoomPercent(zoom: Float): String =
    (zoom * 100f).roundToInt().toString()

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
