package com.example.ui

import android.app.Application
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.darken.ExtraDimDetector
import com.example.data.model.CalibrationConfig
import com.example.data.model.DarkenConfig
import com.example.data.model.OriginalMedia
import com.example.data.model.PageItem
import com.example.data.model.PageTransitionSetting
import com.example.data.model.WallpaperAppState
import com.example.data.repository.WallpaperRepository
import com.example.service.MultiPageWallpaperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.InputStream

class WallpaperViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = WallpaperRepository.getInstance(application)

    val appState: StateFlow<WallpaperAppState> = repository.appState
    val originals: StateFlow<List<OriginalMedia>> = repository.originals

    fun loadPagePreviewBitmap(pageIndex: Int, width: Int, height: Int): Bitmap? =
        try {
            repository.getCroppedBitmap(pageIndex, width, height)
                ?.takeUnless { it.isRecycled }
                ?.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: OutOfMemoryError) {
            Log.e("WallpaperViewModel", "Failed to create preview for page $pageIndex", e)
            null
        }

    private val _isWallpaperActive = MutableStateFlow(false)
    val isWallpaperActive: StateFlow<Boolean> = _isWallpaperActive.asStateFlow()

    private val _isExtraDimDetected = MutableStateFlow(false)
    val isExtraDimDetected: StateFlow<Boolean> = _isExtraDimDetected.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    init {
        checkStatus()
    }

    fun checkStatus() {
        val wm = WallpaperManager.getInstance(getApplication())
        val info = wm.wallpaperInfo
        val myService = MultiPageWallpaperService::class.java.name
        _isWallpaperActive.value = info?.serviceInfo?.name == myService
        _isExtraDimDetected.value = ExtraDimDetector.isExtraDimActive(getApplication())
    }

    fun importMedia(uri: Uri, onComplete: (OriginalMedia?) -> Unit = {}) {
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val media = repository.importFromUri(uri)
                onComplete(media)
            } catch (e: Exception) {
                onComplete(null)
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun deleteOriginal(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteOriginal(id)
        }
    }

    fun duplicateOriginal(id: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            try {
                onDone(repository.duplicateOriginal(id) != null)
            } catch (e: Exception) {
                Log.e("WallpaperViewModel", "Failed to duplicate media $id", e)
                onDone(false)
            }
        }
    }

    fun assignOriginalToPage(
        originalId: String,
        pageIndex: Int,
        targetWidth: Int,
        targetHeight: Int,
        onDone: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            try {
                onDone(
                    repository.assignOriginalToPage(
                        originalId = originalId,
                        pageIndex = pageIndex,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight
                    )
                )
            } catch (e: Exception) {
                Log.e("WallpaperViewModel", "Failed to assign media $originalId", e)
                onDone(false)
            }
        }
    }

    fun setPageCount(count: Int) {
        val validCount = count.coerceIn(2, 7)
        val currentCalib = appState.value.calibration
        val currentPages = appState.value.pages.toMutableList()

        // Generate offsets
        val uniformOffsets = (0 until validCount).map { i ->
            if (validCount <= 1) 0f else i.toFloat() / (validCount - 1).toFloat()
        }

        // Adjust pages list
        while (currentPages.size < validCount) {
            val idx = currentPages.size
            currentPages.add(
                PageItem(
                    pageIndex = idx,
                    customLabel = "Page ${idx + 1}",
                    isBlank = true
                )
            )
        }
        val trimmedPages = currentPages.take(validCount).mapIndexed { idx, item ->
            item.copy(
                pageIndex = idx,
                customLabel = if (idx == currentCalib.homePageIndex.coerceIn(0, validCount - 1)) "Home" else "Page ${idx + 1}"
            )
        }

        val newCalib = currentCalib.copy(
            pageCount = validCount,
            homePageIndex = currentCalib.homePageIndex.coerceIn(0, validCount - 1),
            customOffsets = uniformOffsets
        )

        val transitions = appState.value.transitions.take((validCount - 1).coerceAtLeast(0)).toMutableList()
        while (transitions.size < validCount - 1) {
            transitions.add(transitions.lastOrNull() ?: PageTransitionSetting())
        }
        repository.updateState {
            it.copy(
                calibration = newCalib,
                pages = trimmedPages,
                transitions = transitions,
                transitionType = transitions.firstOrNull()?.forwardTransitionId ?: it.transitionType
            )
        }
    }

    fun setHomePage(index: Int) {
        val currentCalib = appState.value.calibration
        val validIndex = index.coerceIn(0, currentCalib.pageCount - 1)
        val newCalib = currentCalib.copy(homePageIndex = validIndex)

        val updatedPages = appState.value.pages.map {
            it.copy(customLabel = if (it.pageIndex == validIndex) "Home" else "Page ${it.pageIndex + 1}")
        }

        // Immediately update saved offset to the newly designated home page
        repository.saveLastKnownOffset(newCalib.getHomeOffset())

        repository.updateState {
            it.copy(calibration = newCalib, pages = updatedPages)
        }
    }

    fun updateCustomOffsets(newOffsets: List<Float>) {
        val currentCalib = appState.value.calibration
        val newCalib = currentCalib.copy(customOffsets = newOffsets)
        repository.updateState { it.copy(calibration = newCalib) }
    }

    fun resetOffsetsToUniform() {
        val count = appState.value.calibration.pageCount
        val uniformOffsets = (0 until count).map { i ->
            if (count <= 1) 0f else i.toFloat() / (count - 1).toFloat()
        }
        updateCustomOffsets(uniformOffsets)
    }

    fun setTransition(transitionId: String) {
        repository.setTransitionForAllBoundaries(transitionId)
    }

    fun updatePageTransition(boundaryIndex: Int, setting: PageTransitionSetting) {
        repository.updatePageTransition(boundaryIndex, setting)
    }

    fun selectPreset(presetId: String) {
        repository.selectPreset(presetId, appState.value.calibration.pageCount)
    }

    fun duplicatePreset(presetId: String, name: String): String? =
        repository.duplicatePreset(presetId, name, appState.value.calibration.pageCount)

    fun deletePreset(presetId: String): Boolean =
        repository.deletePreset(presetId, appState.value.calibration.pageCount)

    fun renamePreset(presetId: String, name: String): Boolean =
        repository.renamePreset(presetId, name)

    fun updatePageSettings(
        pageIndex: Int,
        page: PageItem,
        targetWidth: Int,
        targetHeight: Int,
        onDone: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            try {
                onDone(repository.updatePageSettings(pageIndex, page, targetWidth, targetHeight))
            } catch (e: Exception) {
                Log.e("WallpaperViewModel", "Failed to update page $pageIndex", e)
                onDone(false)
            }
        }
    }

    fun toggleResumeFade(enabled: Boolean) {
        repository.updateState { it.copy(resumeFadeEnabled = enabled) }
    }

    fun toggleOffsetDebugOverlay(enabled: Boolean) {
        repository.updateState { it.copy(showOffsetDebugOverlay = enabled) }
    }

    fun updateDarkenConfig(darkenConfig: DarkenConfig) {
        repository.updateState { it.copy(darken = darkenConfig) }
    }

    fun togglePageAlreadyDark(pageIndex: Int, isDark: Boolean) {
        val updated = appState.value.pages.map {
            if (it.pageIndex == pageIndex) it.copy(isAlreadyDark = isDark) else it
        }
        repository.updateState { it.copy(pages = updated) }
    }

    fun exportBackupToFolder(folderUri: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            onResult(repository.exportToFolder(folderUri))
        }
    }

    fun importBackup(input: InputStream, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = try {
                input.use { repository.importFromZip(it) }
            } catch (e: Exception) {
                Log.e("WallpaperViewModel", "Failed to restore backup", e)
                false
            }
            if (success) checkStatus()
            onResult(success)
        }
    }

    fun setAutoBackupFolder(folderUri: String?, enabled: Boolean) {
        repository.updateState {
            it.copy(backupFolderPath = folderUri, autoBackupEnabled = enabled)
        }
    }

    fun applyWallpaperIntent(context: Context) {
        try {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(context, MultiPageWallpaperService::class.java)
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback to standard wallpaper chooser if specific component action fails
            try {
                val fallbackIntent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (ex: Exception) {
                // Ignore
            }
        }
    }
}
