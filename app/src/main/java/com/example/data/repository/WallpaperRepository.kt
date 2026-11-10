package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.AtomicFile
import android.util.Log
import com.example.darken.ExtraDimDetector
import com.example.data.model.CalibrationConfig
import com.example.data.model.CropFillMode
import com.example.data.model.DarkenConfig
import com.example.data.model.OriginalMedia
import com.example.data.model.PageItem
import com.example.data.model.PageTransitionSetting
import com.example.data.model.WallpaperPreset
import com.example.data.migration.PresetDataMigration
import com.example.data.model.WallpaperAppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class WallpaperRepository private constructor(private val context: Context) {

    data class ParallaxBitmap(
        val bitmap: Bitmap,
        val leftTravelPx: Int,
        val rightTravelPx: Int
    )

    private val originalsDir: File by lazy {
        File(context.filesDir, "originals").apply { if (!exists()) mkdirs() }
    }

    private val croppedDir: File by lazy {
        File(context.filesDir, "cropped").apply { if (!exists()) mkdirs() }
    }

    private val configFile: File by lazy {
        File(context.filesDir, "config.json")
    }

    private val originalsMetaFile: File by lazy {
        File(context.filesDir, "originals_meta.json")
    }

    private val _appState = MutableStateFlow(WallpaperAppState(emptyList(), CalibrationConfig()))
    val appState: StateFlow<WallpaperAppState> = _appState.asStateFlow()

    private val _originals = MutableStateFlow<List<OriginalMedia>>(emptyList())
    val originals: StateFlow<List<OriginalMedia>> = _originals.asStateFlow()

    private val prefs by lazy {
        context.getSharedPreferences("wallpaper_state", Context.MODE_PRIVATE)
    }
    private val backupMutex = Mutex()

    fun saveLastKnownOffset(offset: Float) {
        val clamped = offset.coerceIn(0f, 1f)
        prefs.edit().putFloat("last_known_offset", clamped).apply()
    }

    fun getLastKnownOffsetOrHome(): Float {
        val saved = prefs.getFloat("last_known_offset", -1f)
        return if (saved in 0.0f..1.0f) {
            saved
        } else {
            _appState.value.calibration.getHomeOffset()
        }
    }

    // Bitmap cache for fast Live Wallpaper Engine rendering
    private val bitmapCache = mutableMapOf<Triple<Int, Int, Int>, Bitmap>()
    private val parallaxBitmapCache =
        mutableMapOf<Triple<Int, Int, Int>, ParallaxBitmap>()

    // Listeners for Live Wallpaper to trigger instant redrawing
    private val listeners = mutableListOf<() -> Unit>()

    init {
        loadInitialData()
    }

    fun addChangeListener(listener: () -> Unit) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun removeChangeListener(listener: () -> Unit) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    private fun notifyListeners() {
        synchronized(listeners) {
            listeners.toList()
        }.forEach {
            try {
                it.invoke()
            } catch (e: Exception) {
                Log.e("WallpaperRepo", "Error notifying listener", e)
            }
        }
    }

    private fun loadInitialData() {
        // Load originals metadata
        val legacyMediaMetadata = loadOriginalsMeta()

        // Load config
        if (configFile.exists()) {
            try {
                val jsonStr = configFile.readText()
                val json = JSONObject(jsonStr)
                val loaded = WallpaperAppState.fromJson(json)
                val migrated = if (loaded.schemaVersion < 2) {
                    PresetDataMigration.migrateLegacy(loaded, legacyMediaMetadata)
                } else {
                    loaded
                }
                val migratedTransitions = PresetDataMigration.migrateTransitionStorage(migrated)
                val synchronized = synchronizeActivePreset(migratedTransitions)
                _appState.value = synchronized
                if (loaded.schemaVersion < PresetDataMigration.CURRENT_SCHEMA_VERSION || synchronized != loaded) {
                    if (saveConfigInternal(synchronized) && loaded.schemaVersion < 2) {
                        saveOriginalsMeta(_originals.value)
                    }
                }
            } catch (e: Exception) {
                Log.e("WallpaperRepo", "Error loading config", e)
                initDefaultState()
            }
        } else {
            initDefaultState()
        }

        // Check if starter wallpapers should be created
        CoroutineScope(Dispatchers.IO).launch {
            ensureStarterWallpapersExist()
            invalidateCache()
            notifyListeners()
        }
    }

    private fun initDefaultState() {
        val defaultCalib = CalibrationConfig(
            pageCount = 3,
            homePageIndex = 1,
            autoCalibrate = false,
            customOffsets = listOf(0.0f, 0.5f, 1.0f)
        )
        val defaultPages = (0 until 3).map { i ->
            PageItem(
                pageIndex = i,
                croppedFileName = "starter_page_$i.jpg",
                customLabel = if (i == 1) "Home (Page 2)" else "Page ${i + 1}"
            )
        }
        val defaultState = WallpaperAppState(
            pages = defaultPages,
            calibration = defaultCalib
        )
        _appState.value = defaultState
        saveConfigInternal(defaultState)
    }

    private fun loadOriginalsMeta(): Map<String, JSONObject> {
        if (originalsMetaFile.exists()) {
            try {
                val jsonStr = originalsMetaFile.readText()
                val arr = JSONArray(jsonStr)
                val list = mutableListOf<OriginalMedia>()
                val legacySettings = mutableMapOf<String, JSONObject>()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { mediaJson ->
                        val media = OriginalMedia.fromJson(mediaJson)
                        list.add(media)
                        legacySettings[media.id] = mediaJson
                    }
                }
                _originals.value = list
                return legacySettings
            } catch (e: Exception) {
                Log.e("WallpaperRepo", "Error loading originals meta", e)
            }
        }
        return emptyMap()
    }

    private fun saveOriginalsMeta(list: List<OriginalMedia>) {
        _originals.value = list
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            writeAtomically(originalsMetaFile, arr.toString())
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Error saving originals meta", e)
        }
    }

    private fun writeAtomically(file: File, contents: String): Boolean {
        val atomicFile = AtomicFile(file)
        var stream: FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(contents.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
            return true
        } catch (e: Exception) {
            try {
                stream?.let(atomicFile::failWrite)
            } catch (recoveryError: Exception) {
                e.addSuppressed(recoveryError)
            }
            Log.e("WallpaperRepo", "Error writing ${file.name}", e)
            return false
        }
    }

    private fun saveConfigInternal(state: WallpaperAppState): Boolean =
        writeAtomically(configFile, state.toJson().toString(2))

    private fun synchronizeActivePreset(state: WallpaperAppState): WallpaperAppState =
        state.copy(
            presets = state.presets.map { preset ->
                if (preset.id == state.activePresetId) {
                    preset.copy(pages = state.pages, transitions = state.transitions)
                } else {
                    preset
                }
            },
            presetNeedsSave = false,
            schemaVersion = PresetDataMigration.CURRENT_SCHEMA_VERSION
        )

    fun updateState(transform: (WallpaperAppState) -> WallpaperAppState) {
        val previousState = _appState.value
        val transformed = transform(previousState)
        val shouldUpdateActivePreset =
            transformed.pages != previousState.pages ||
                transformed.transitions != previousState.transitions ||
                transformed.activePresetId != previousState.activePresetId
        val newState = if (shouldUpdateActivePreset) {
            synchronizeActivePreset(transformed)
        } else {
            transformed.copy(
                presetNeedsSave = false,
                schemaVersion = PresetDataMigration.CURRENT_SCHEMA_VERSION
            )
        }
        _appState.value = newState
        saveConfigInternal(newState)
        invalidateCache()
        notifyListeners()

        if (newState.autoBackupEnabled && !newState.backupFolderPath.isNullOrEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                autoBackup(newState.backupFolderPath)
            }
        }
    }

    fun selectPreset(presetId: String, pageCount: Int) {
        val state = _appState.value
        val preset = state.presets.firstOrNull { it.id == presetId } ?: return
        val count = pageCount.coerceIn(2, 7)
        val resizedPages = preset.pages.take(count).toMutableList()
        while (resizedPages.size < count) {
            val index = resizedPages.size
            resizedPages.add(
                PageItem(
                    pageIndex = index,
                    customLabel = "Page ${index + 1}",
                    isBlank = true
                )
            )
        }
        val pages = resizedPages.mapIndexed { index, page ->
            page.copy(pageIndex = index)
        }
        val transitions = preset.transitions.take((count - 1).coerceAtLeast(0)).toMutableList()
        while (transitions.size < count - 1) {
            transitions.add(transitions.lastOrNull() ?: PageTransitionSetting())
        }
        val firstTransition = transitions.firstOrNull()?.universalTransitionId ?: "scroll"
        updateState {
            it.copy(
                pages = pages,
                transitions = transitions,
                transitionType = firstTransition,
                activePresetId = preset.id
            )
        }
    }

    fun duplicatePreset(presetId: String, name: String, pageCount: Int): String? {
        val cleanName = name.trim().take(40)
        if (cleanName.isEmpty()) return null
        val state = _appState.value
        val source = state.presets.firstOrNull { it.id == presetId } ?: return null
        val count = pageCount.coerceIn(2, 7)
        val pages = source.pages.take(count).toMutableList()
        while (pages.size < count) {
            val index = pages.size
            pages.add(
                PageItem(
                    pageIndex = index,
                    customLabel = "Page ${index + 1}",
                    isBlank = true
                )
            )
        }
        val transitions = source.transitions.take(count - 1).toMutableList()
        while (transitions.size < count - 1) {
            transitions.add(transitions.lastOrNull() ?: PageTransitionSetting())
        }
        val id = UUID.randomUUID().toString()
        val preset = WallpaperPreset(
            id = id,
            name = cleanName,
            pages = pages.mapIndexed { index, page -> page.copy(pageIndex = index) },
            transitions = transitions
        )
        updateState {
            it.copy(
                presets = it.presets + preset,
                pages = preset.pages,
                transitions = preset.transitions,
                transitionType = transitions.firstOrNull()?.universalTransitionId ?: "scroll",
                activePresetId = id
            )
        }
        return id
    }

    fun renamePreset(presetId: String, name: String): Boolean {
        val cleanName = name.trim().take(40)
        if (cleanName.isEmpty()) return false
        val state = _appState.value
        if (state.presets.none { it.id == presetId }) return false
        updateState {
            it.copy(
                presets = it.presets.map { preset ->
                    if (preset.id == presetId) preset.copy(name = cleanName) else preset
                }
            )
        }
        return true
    }

    fun deletePreset(presetId: String, pageCount: Int): Boolean {
        val state = _appState.value
        if (state.presets.size <= 1 || state.presets.none { it.id == presetId }) return false

        val remainingPresets = state.presets.filterNot { it.id == presetId }
        if (presetId != state.activePresetId) {
            updateState { it.copy(presets = remainingPresets) }
            return true
        }

        val fallback = remainingPresets.first()
        val count = pageCount.coerceIn(2, 7)
        val pages = fallback.pages.take(count).toMutableList()
        while (pages.size < count) {
            val index = pages.size
            pages.add(
                PageItem(
                    pageIndex = index,
                    customLabel = "Page ${index + 1}",
                    isBlank = true
                )
            )
        }
        val transitions = fallback.transitions.take(count - 1).toMutableList()
        while (transitions.size < count - 1) {
            transitions.add(transitions.lastOrNull() ?: PageTransitionSetting())
        }
        updateState {
            it.copy(
                presets = remainingPresets,
                pages = pages.mapIndexed { index, page -> page.copy(pageIndex = index) },
                transitions = transitions,
                transitionType = transitions.firstOrNull()?.universalTransitionId ?: "scroll",
                activePresetId = fallback.id
            )
        }
        return true
    }

    fun updatePageTransition(boundaryIndex: Int, setting: PageTransitionSetting) {
        val state = _appState.value
        if (boundaryIndex !in state.transitions.indices) return
        val transitions = state.transitions.toMutableList()
        transitions[boundaryIndex] = setting
        updateState {
            it.copy(
                transitions = transitions,
                transitionType = transitions.firstOrNull()?.universalTransitionId ?: "scroll"
            )
        }
    }

    fun setTransitionForAllBoundaries(transitionId: String) {
        val transitions = List((_appState.value.pages.size - 1).coerceAtLeast(0)) {
            PageTransitionSetting(transitionId, transitionId)
        }
        updateState {
            it.copy(
                transitionType = transitionId,
                transitions = transitions
            )
        }
    }

    fun invalidateCache() {
        synchronized(bitmapCache) {
            bitmapCache.values.forEach { it.recycle() }
            bitmapCache.clear()
        }
        synchronized(parallaxBitmapCache) {
            parallaxBitmapCache.values.forEach { it.bitmap.recycle() }
            parallaxBitmapCache.clear()
        }
    }

    fun getParallaxBitmap(pageIndex: Int, reqWidth: Int, reqHeight: Int): ParallaxBitmap? {
        if (reqWidth <= 0 || reqHeight <= 0) return null
        val key = Triple(pageIndex, reqWidth, reqHeight)
        synchronized(parallaxBitmapCache) {
            parallaxBitmapCache[key]?.let {
                if (!it.bitmap.isRecycled) return it
            }
        }
        fun getScreenSizedPage(): ParallaxBitmap? {
            val pageBitmap = getCroppedBitmap(pageIndex, reqWidth, reqHeight) ?: return null
            if (pageBitmap.width == reqWidth && pageBitmap.height == reqHeight) {
                return ParallaxBitmap(pageBitmap, 0, 0)
            }
            return try {
                Bitmap.createScaledBitmap(pageBitmap, reqWidth, reqHeight, true).let {
                    ParallaxBitmap(it, 0, 0).also { parallaxBitmap ->
                        synchronized(parallaxBitmapCache) {
                            parallaxBitmapCache[key] = parallaxBitmap
                        }
                    }
                }
            } catch (e: OutOfMemoryError) {
                Log.e("WallpaperRepo", "Not enough memory to scale page $pageIndex", e)
                null
            }
        }
        val page = _appState.value.pages.find { it.pageIndex == pageIndex }
            ?: return getScreenSizedPage()
        val media = page.originalId?.let { id -> _originals.value.find { it.id == id } }
            ?: return getScreenSizedPage()
        if (page.parallaxAmount <= 0f) return getScreenSizedPage()

        val rendered = renderMediaBitmap(
            media = media,
            page = page,
            targetWidth = reqWidth,
            targetHeight = reqHeight,
            requestedLeftTravelPx = reqWidth * 0.25f * page.parallaxAmount,
            requestedRightTravelPx = reqWidth * 0.25f * page.parallaxAmount
        ) ?: return getScreenSizedPage()
        synchronized(parallaxBitmapCache) {
            parallaxBitmapCache[key] = rendered
        }
        return rendered
    }

    fun getCroppedBitmap(pageIndex: Int, reqWidth: Int, reqHeight: Int): Bitmap? {
        if (reqWidth <= 0 || reqHeight <= 0) return null
        val key = Triple(pageIndex, reqWidth, reqHeight)
        synchronized(bitmapCache) {
            bitmapCache[key]?.let {
                if (!it.isRecycled) return it
            }
        }

        val page = _appState.value.pages.find { it.pageIndex == pageIndex }
        if (page?.isBlank == true) return null
        val media = page?.originalId?.let { id -> _originals.value.find { it.id == id } }
        if (page != null && media != null) {
            val rendered = renderMediaBitmap(
                media = media,
                page = page,
                targetWidth = reqWidth,
                targetHeight = reqHeight,
                requestedLeftTravelPx = 0f,
                requestedRightTravelPx = 0f
            )?.bitmap
            if (rendered != null) {
                synchronized(bitmapCache) { bitmapCache[key] = rendered }
                return rendered
            }
        }
        val fileName = page?.croppedFileName ?: "starter_page_$pageIndex.jpg"
        val file = File(croppedDir, fileName)

        if (!file.exists()) return null

        val bmp = try {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Failed to decode cropped file $fileName", e)
            null
        }

        if (bmp != null) {
            synchronized(bitmapCache) {
                bitmapCache[key] = bmp
            }
        }
        return bmp
    }

    suspend fun importFromUri(uri: Uri): OriginalMedia = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val destFile = File(originalsDir, "$id.jpg")

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalArgumentException("Cannot open stream for URI: $uri")

        // Analyze dimensions and luminance
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(destFile.absolutePath, opts)
        val width = opts.outWidth.coerceAtLeast(1)
        val height = opts.outHeight.coerceAtLeast(1)

        // Decode downsampled bitmap for luminance calculation
        val sampleSize = (Math.max(width, height) / 300).coerceAtLeast(1)
        val sampleOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val sampleBmp = BitmapFactory.decodeFile(destFile.absolutePath, sampleOpts)
        val luminance = if (sampleBmp != null) {
            val lum = ExtraDimDetector.calculateLuminance(sampleBmp)
            sampleBmp.recycle()
            lum
        } else {
            0.5f
        }

        val isDark = luminance < _appState.value.darken.luminanceThreshold

        val media = OriginalMedia(
            id = id,
            fileName = "$id.jpg",
            dateAdded = System.currentTimeMillis(),
            width = width,
            height = height,
            averageLuminance = luminance,
            isDarkDetected = isDark
        )

        val updatedList = _originals.value + media
        saveOriginalsMeta(updatedList)
        media
    }

    fun deleteOriginal(id: String) {
        val file = File(originalsDir, "$id.jpg")
        if (file.exists()) file.delete()

        val updatedList = _originals.value.filter { it.id != id }
        saveOriginalsMeta(updatedList)

        // Clear references from pages
        val updatedPages = _appState.value.pages.map {
            if (it.originalId == id) it.copy(originalId = null) else it
        }
        updateState { it.copy(pages = updatedPages) }
    }

    suspend fun updatePageSettings(
        pageIndex: Int,
        updatedPage: PageItem,
        targetWidth: Int,
        targetHeight: Int
    ): Boolean = withContext(Dispatchers.IO) {
        if (targetWidth <= 0 || targetHeight <= 0) return@withContext false
        val current = _appState.value.pages.find { it.pageIndex == pageIndex }
            ?: return@withContext false
        val mediaId = current.originalId ?: return@withContext false
        val media = _originals.value.find { it.id == mediaId }
            ?: return@withContext false
        val normalized = updatedPage.copy(
            pageIndex = pageIndex,
            originalId = mediaId,
            cropPanX = updatedPage.cropPanX.coerceIn(-1f, 1f),
            cropPanY = updatedPage.cropPanY.coerceIn(-1f, 1f),
            cropScale = updatedPage.cropScale.coerceIn(1f, 3f),
            blurAmount = updatedPage.blurAmount.coerceIn(0f, 1f),
            parallaxAmount = updatedPage.parallaxAmount.coerceIn(0f, 1f)
        )
        if (!renderMediaForPage(media, normalized, targetWidth, targetHeight)) return@withContext false
        val updatedPages = _appState.value.pages.map {
            if (it.pageIndex == pageIndex) normalized else it
        }
        updateState { it.copy(pages = updatedPages) }
        true
    }

    suspend fun duplicateOriginal(id: String): OriginalMedia? = withContext(Dispatchers.IO) {
        val source = _originals.value.find { it.id == id } ?: return@withContext null
        val sourceFile = File(originalsDir, source.fileName)
        if (!sourceFile.exists()) return@withContext null
        val duplicateId = UUID.randomUUID().toString()
        val duplicateFileName = "$duplicateId.jpg"
        sourceFile.copyTo(File(originalsDir, duplicateFileName))
        val duplicate = source.copy(
            id = duplicateId,
            fileName = duplicateFileName,
            dateAdded = System.currentTimeMillis()
        )
        saveOriginalsMeta(_originals.value + duplicate)
        duplicate
    }

    suspend fun assignOriginalToPage(
        originalId: String,
        pageIndex: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Boolean = withContext(Dispatchers.IO) {
        val media = _originals.value.find { it.id == originalId }
            ?: return@withContext false

        val newPage = PageItem(
            pageIndex = pageIndex,
            originalId = media.id,
            croppedFileName = "page_$pageIndex.jpg",
            isAlreadyDark = media.isDarkDetected,
            customLabel = if (pageIndex == _appState.value.calibration.homePageIndex) {
                "Home"
            } else {
                "Page ${pageIndex + 1}"
            }
        )
        if (!renderMediaForPage(media, newPage, targetWidth, targetHeight)) return@withContext false
        val pages = _appState.value.pages.toMutableList()
        val existingIndex = pages.indexOfFirst { it.pageIndex == pageIndex }
        if (existingIndex >= 0) pages[existingIndex] = newPage else pages.add(newPage)
        updateState { it.copy(pages = pages) }
        true
    }

    private fun renderMediaForPage(
        media: OriginalMedia,
        page: PageItem,
        targetWidth: Int,
        targetHeight: Int
    ): Boolean {
        if (targetWidth <= 0 || targetHeight <= 0) return false
        val outputBitmap = renderMediaBitmap(
            media = media,
            page = page,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            requestedLeftTravelPx = 0f,
            requestedRightTravelPx = 0f
        )?.bitmap ?: return false
        try {
            FileOutputStream(File(croppedDir, page.croppedFileName ?: "page_${page.pageIndex}.jpg")).use { out ->
                outputBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Failed to render media ${media.id} for page ${page.pageIndex}", e)
            return false
        } finally {
            outputBitmap.recycle()
        }
        return true
    }

    private fun renderMediaBitmap(
        media: OriginalMedia,
        page: PageItem,
        targetWidth: Int,
        targetHeight: Int,
        requestedLeftTravelPx: Float,
        requestedRightTravelPx: Float
    ): ParallaxBitmap? {
        if (targetWidth <= 0 || targetHeight <= 0) return null
        val originalFile = File(originalsDir, media.fileName)
        if (!originalFile.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(originalFile.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.e("WallpaperRepo", "Could not read dimensions for media ${media.id}")
            return null
        }
        val requestedMaxDimension = maxOf(targetWidth, targetHeight) * 1.5f
        val sampleSize = (
            maxOf(bounds.outWidth, bounds.outHeight) / requestedMaxDimension
        ).toInt().coerceAtLeast(1)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val fullBitmap = try {
            BitmapFactory.decodeFile(originalFile.absolutePath, decodeOptions)
        } catch (e: OutOfMemoryError) {
            Log.e("WallpaperRepo", "Not enough memory to decode media ${media.id}", e)
            return null
        } ?: return null
        var outputBitmap: Bitmap? = null
        var blurredBackground: Bitmap? = null

        try {
            val sourceWidth = fullBitmap.width
            val sourceHeight = fullBitmap.height
            val targetRatio = targetWidth.toFloat() / targetHeight
            val sourceRatio = sourceWidth.toFloat() / sourceHeight
            val scale = page.cropScale.coerceIn(1f, 3f)
            val panX = page.cropPanX.coerceIn(-1f, 1f)
            val panY = page.cropPanY.coerceIn(-1f, 1f)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
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
            val centerCrop = Rect(
                ((sourceWidth - baseCropWidth) / 2f).toInt().coerceIn(0, sourceWidth - 1),
                ((sourceHeight - baseCropHeight) / 2f).toInt().coerceIn(0, sourceHeight - 1),
                ((sourceWidth + baseCropWidth) / 2f).toInt().coerceIn(1, sourceWidth),
                ((sourceHeight + baseCropHeight) / 2f).toInt().coerceIn(1, sourceHeight)
            )
            val availableLeftTravelPx: Float
            val availableRightTravelPx: Float
            if (page.cropFillMode == CropFillMode.CROP) {
                val cropWidth = baseCropWidth / scale
                val cropHeight = baseCropHeight / scale
                val centerX = sourceWidth / 2f -
                    panX * ((sourceWidth - cropWidth) / 2f).coerceAtLeast(0f)
                val centerY = sourceHeight / 2f -
                    panY * ((sourceHeight - cropHeight) / 2f).coerceAtLeast(0f)
                val left = (centerX - cropWidth / 2f).coerceIn(0f, sourceWidth - cropWidth)
                val top = (centerY - cropHeight / 2f).coerceIn(0f, sourceHeight - cropHeight)
                availableLeftTravelPx = left * targetWidth / cropWidth
                availableRightTravelPx =
                    (sourceWidth - left - cropWidth) * targetWidth / cropWidth
            } else {
                val fitScale = minOf(
                    targetWidth.toFloat() / sourceWidth,
                    targetHeight.toFloat() / sourceHeight
                ) * scale
                val drawWidth = sourceWidth * fitScale
                val centerX = targetWidth / 2f +
                    panX * ((drawWidth - targetWidth) / 2f).coerceAtLeast(0f)
                val imageLeft = centerX - drawWidth / 2f
                availableLeftTravelPx = (-imageLeft).coerceAtLeast(0f)
                availableRightTravelPx =
                    (imageLeft + drawWidth - targetWidth).coerceAtLeast(0f)
            }
            val leftTravelPx = minOf(
                requestedLeftTravelPx.coerceAtLeast(0f),
                availableLeftTravelPx
            ).toInt()
            val rightTravelPx = minOf(
                requestedRightTravelPx.coerceAtLeast(0f),
                availableRightTravelPx
            ).toInt()
            val outputWidth = targetWidth + leftTravelPx + rightTravelPx
            outputBitmap = try {
                Bitmap.createBitmap(outputWidth, targetHeight, Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                Log.e("WallpaperRepo", "Not enough memory to render media ${media.id}", e)
                return null
            }
            val renderedBitmap = outputBitmap ?: return null
            val canvas = Canvas(renderedBitmap)

            if (page.cropFillMode != CropFillMode.CROP) {
                val sourcePerPixel = centerCrop.width().toFloat() / targetWidth
                val expandedBackground = Rect(
                    (centerCrop.left - leftTravelPx * sourcePerPixel)
                        .toInt().coerceAtLeast(0),
                    centerCrop.top,
                    (centerCrop.right + rightTravelPx * sourcePerPixel)
                        .toInt().coerceAtMost(sourceWidth),
                    centerCrop.bottom
                )
                val blurScale = 1f - page.blurAmount.coerceIn(0f, 1f) * 0.94f
                val backgroundWidth = (outputWidth * blurScale).toInt().coerceAtLeast(1)
                val backgroundHeight = (targetHeight * blurScale).toInt().coerceAtLeast(1)
                blurredBackground = Bitmap.createBitmap(
                    backgroundWidth,
                    backgroundHeight,
                    Bitmap.Config.ARGB_8888
                )
                Canvas(blurredBackground!!).drawBitmap(
                    fullBitmap,
                    expandedBackground,
                    Rect(0, 0, backgroundWidth, backgroundHeight),
                    paint
                )
                canvas.drawBitmap(
                    blurredBackground!!,
                    null,
                    Rect(0, 0, outputWidth, targetHeight),
                    paint
                )
                if (page.cropFillMode == CropFillMode.GLASS) {
                    canvas.drawColor(Color.argb(58, 24, 28, 38))
                }

                val fitScale = minOf(
                    targetWidth.toFloat() / sourceWidth,
                    targetHeight.toFloat() / sourceHeight
                ) * scale
                val drawWidth = sourceWidth * fitScale
                val drawHeight = sourceHeight * fitScale
                val centerX = leftTravelPx + targetWidth / 2f +
                    panX * ((drawWidth - targetWidth) / 2f).coerceAtLeast(0f)
                val centerY = targetHeight / 2f +
                    panY * ((drawHeight - targetHeight) / 2f).coerceAtLeast(0f)
                canvas.drawBitmap(
                    fullBitmap,
                    null,
                    RectF(
                        centerX - drawWidth / 2f,
                        centerY - drawHeight / 2f,
                        centerX + drawWidth / 2f,
                        centerY + drawHeight / 2f
                    ),
                    paint
                )
            } else {
                val cropWidth = baseCropWidth / scale
                val cropHeight = baseCropHeight / scale
                val centerX = sourceWidth / 2f -
                    panX * ((sourceWidth - cropWidth) / 2f).coerceAtLeast(0f)
                val centerY = sourceHeight / 2f -
                    panY * ((sourceHeight - cropHeight) / 2f).coerceAtLeast(0f)
                val left = (centerX - cropWidth / 2f).coerceIn(0f, sourceWidth - cropWidth)
                val top = (centerY - cropHeight / 2f).coerceIn(0f, sourceHeight - cropHeight)
                val sourcePerPixel = cropWidth / targetWidth
                val expandedSource = RectF(
                    left - leftTravelPx * sourcePerPixel,
                    top,
                    left + cropWidth + rightTravelPx * sourcePerPixel,
                    top + cropHeight
                )
                val sourceRect = Rect(
                    expandedSource.left.toInt().coerceAtLeast(0),
                    expandedSource.top.toInt().coerceAtLeast(0),
                    expandedSource.right.toInt().coerceAtMost(sourceWidth),
                    expandedSource.bottom.toInt().coerceAtMost(sourceHeight)
                )
                canvas.drawBitmap(
                    fullBitmap,
                    sourceRect,
                    RectF(0f, 0f, outputWidth.toFloat(), targetHeight.toFloat()),
                    paint
                )
            }
            return ParallaxBitmap(renderedBitmap, leftTravelPx, rightTravelPx)
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Failed to render media ${media.id}", e)
            outputBitmap?.recycle()
            return null
        } finally {
            blurredBackground?.recycle()
            fullBitmap.recycle()
        }
    }

    private fun ensureStarterWallpapersExist() {
        val targetWidth = 1080
        val targetHeight = 2400

        val starters = listOf(
            Triple(0, "starter_page_0.jpg", "Aurora Cyber"),
            Triple(1, "starter_page_1.jpg", "Sunset Nebula (Home)"),
            Triple(2, "starter_page_2.jpg", "Deep Cosmos")
        )

        for ((index, fileName, label) in starters) {
            val file = File(croppedDir, fileName)
            if (!file.exists()) {
                val bmp = generateStarterBitmap(index, targetWidth, targetHeight)
                FileOutputStream(file).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                bmp.recycle()
            }
        }
    }

    private fun generateStarterBitmap(type: Int, width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        when (type) {
            0 -> {
                // Aurora Cyber: Deep Indigo to vibrant Neon Cyan & Mint
                val shader = LinearGradient(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    intArrayOf(Color.rgb(15, 23, 42), Color.rgb(14, 116, 144), Color.rgb(16, 185, 129)),
                    floatArrayOf(0.0f, 0.65f, 1.0f),
                    Shader.TileMode.CLAMP
                )
                paint.shader = shader
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

                // Geometric glowing circles
                paint.shader = null
                paint.color = Color.argb(40, 255, 255, 255)
                canvas.drawCircle(width * 0.8f, height * 0.3f, width * 0.45f, paint)
                paint.color = Color.argb(30, 56, 189, 248)
                canvas.drawCircle(width * 0.2f, height * 0.7f, width * 0.6f, paint)
            }
            1 -> {
                // Sunset Nebula: Rich Violet to Warm Coral & Amber (Home Screen)
                val shader = LinearGradient(
                    width * 0.2f, 0f, width * 0.8f, height.toFloat(),
                    intArrayOf(Color.rgb(30, 27, 75), Color.rgb(147, 51, 234), Color.rgb(244, 63, 94), Color.rgb(245, 158, 11)),
                    floatArrayOf(0.0f, 0.4f, 0.75f, 1.0f),
                    Shader.TileMode.CLAMP
                )
                paint.shader = shader
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

                // Soft radial core
                val radial = RadialGradient(
                    width * 0.5f, height * 0.55f, width * 0.5f,
                    Color.argb(80, 254, 215, 170), Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )
                paint.shader = radial
                canvas.drawCircle(width * 0.5f, height * 0.55f, width * 0.5f, paint)
            }
            else -> {
                // Deep Cosmos: Midnight Blue to Emerald & Indigo
                val shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    intArrayOf(Color.rgb(3, 7, 18), Color.rgb(19, 78, 74), Color.rgb(6, 95, 70)),
                    floatArrayOf(0.0f, 0.5f, 1.0f),
                    Shader.TileMode.CLAMP
                )
                paint.shader = shader
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

                paint.shader = null
                paint.color = Color.argb(45, 110, 231, 183)
                canvas.drawCircle(width * 0.75f, height * 0.25f, width * 0.5f, paint)
                paint.color = Color.argb(25, 255, 255, 255)
                canvas.drawCircle(width * 0.15f, height * 0.85f, width * 0.4f, paint)
            }
        }

        return bmp
    }

    /**
     * Single-file export: creates paperpages_backup.zip containing config.json,
     * originals, and cropped images without any timestamp in the filename.
     */
    suspend fun exportToZip(outputStream: OutputStream): Boolean = withContext(Dispatchers.IO) {
        try {
            ZipOutputStream(BufferedOutputStream(outputStream)).use { zipOut ->
                // Write config.json
                if (!configFile.exists()) {
                    throw IllegalStateException("Configuration file does not exist")
                }
                zipOut.putNextEntry(ZipEntry("config.json"))
                FileInputStream(configFile).use { it.copyTo(zipOut) }
                zipOut.closeEntry()

                // Write originals_meta.json
                zipOut.putNextEntry(ZipEntry("originals_meta.json"))
                if (originalsMetaFile.exists()) {
                    FileInputStream(originalsMetaFile).use { it.copyTo(zipOut) }
                } else {
                    zipOut.write(JSONArray().toString().toByteArray(Charsets.UTF_8))
                }
                zipOut.closeEntry()

                // Write originals
                originalsDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        zipOut.putNextEntry(ZipEntry("originals/${file.name}"))
                        FileInputStream(file).use { it.copyTo(zipOut) }
                        zipOut.closeEntry()
                    }
                }

                // Write cropped
                croppedDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        zipOut.putNextEntry(ZipEntry("cropped/${file.name}"))
                        FileInputStream(file).use { it.copyTo(zipOut) }
                        zipOut.closeEntry()
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Failed to export ZIP", e)
            false
        }
    }

    suspend fun importFromZip(inputStream: InputStream): Boolean = withContext(Dispatchers.IO) {
        val stagingDir = File(context.filesDir, ".backup_restore_${UUID.randomUUID()}")
        var keepStaging = false
        try {
            val stagedOriginals = File(stagingDir, "originals").apply { mkdirs() }
            val stagedCropped = File(stagingDir, "cropped").apply { mkdirs() }
            var hasConfig = false
            var hasOriginalsMetadata = false
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalBytes = 0L

            ZipInputStream(BufferedInputStream(inputStream)).use { zipIn ->
                while (true) {
                    val entry = zipIn.nextEntry ?: break
                    if (entry.isDirectory) {
                        zipIn.closeEntry()
                        continue
                    }
                    val target = when {
                        entry.name == "config.json" -> {
                            if (hasConfig) throw IllegalArgumentException("Duplicate config.json")
                            hasConfig = true
                            File(stagingDir, "config.json")
                        }
                        entry.name == "originals_meta.json" -> {
                            if (hasOriginalsMetadata) {
                                throw IllegalArgumentException("Duplicate originals_meta.json")
                            }
                            hasOriginalsMetadata = true
                            File(stagingDir, "originals_meta.json")
                        }
                        entry.name.startsWith("originals/") ->
                            File(stagedOriginals, requireBackupFileName(entry.name, "originals/"))
                        entry.name.startsWith("cropped/") ->
                            File(stagedCropped, requireBackupFileName(entry.name, "cropped/"))
                        else -> throw IllegalArgumentException("Unsupported backup entry: ${entry.name}")
                    }
                    target.parentFile?.let { parent ->
                        if (!parent.exists() && !parent.mkdirs()) {
                            throw IllegalStateException("Could not create restore staging directory")
                        }
                    }
                    FileOutputStream(target).use { output ->
                        while (true) {
                            val bytesRead = zipIn.read(buffer)
                            if (bytesRead < 0) break
                            totalBytes += bytesRead
                            if (totalBytes > MAX_BACKUP_UNCOMPRESSED_BYTES) {
                                throw IllegalArgumentException("Backup is too large to restore")
                            }
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                    zipIn.closeEntry()
                }
            }

            if (!hasConfig) {
                throw IllegalArgumentException("Backup is missing config.json")
            }
            if (!hasOriginalsMetadata) {
                File(stagingDir, "originals_meta.json").writeText("[]")
            }
            val backupState = WallpaperAppState.fromJson(
                JSONObject(File(stagingDir, "config.json").readText())
            )
            val metadata = JSONArray(File(stagingDir, "originals_meta.json").readText())
            val legacyMetadata = mutableMapOf<String, JSONObject>()
            for (index in 0 until metadata.length()) {
                val item = metadata.optJSONObject(index)
                    ?: throw IllegalArgumentException("Invalid media metadata in backup")
                val original = OriginalMedia.fromJson(item)
                legacyMetadata[original.id] = item
            }
            val migratedLegacy = PresetDataMigration.migrateLegacy(backupState, legacyMetadata)
            PresetDataMigration.migrateTransitionStorage(migratedLegacy)

            installRestoredFiles(stagingDir)
            loadInitialData()
            invalidateCache()
            notifyListeners()
            true
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Failed to import ZIP", e)
            keepStaging = File(stagingDir, "rollback").listFiles()?.isNotEmpty() == true
            false
        } finally {
            if (!keepStaging) stagingDir.deleteRecursively()
        }
    }

    private fun requireBackupFileName(entryName: String, prefix: String): String {
        val fileName = entryName.removePrefix(prefix)
        if (fileName.isBlank() || fileName == "." || fileName == ".." ||
            fileName.contains('/') || fileName.contains('\\')
        ) {
            throw IllegalArgumentException("Invalid backup file path: $entryName")
        }
        return fileName
    }

    private fun installRestoredFiles(stagingDir: File) {
        val stagedFiles = listOf(
            File(stagingDir, "config.json") to configFile,
            File(stagingDir, "originals_meta.json") to originalsMetaFile,
            File(stagingDir, "originals") to originalsDir,
            File(stagingDir, "cropped") to croppedDir
        )
        val rollbackDir = File(stagingDir, "rollback").apply { mkdirs() }
        val movedExisting = mutableListOf<Pair<File, File>>()
        val installed = mutableListOf<File>()
        try {
            stagedFiles.forEachIndexed { index, (_, target) ->
                if (target.exists()) {
                    val backup = File(rollbackDir, "item_$index")
                    if (!target.renameTo(backup)) {
                        throw IllegalStateException("Could not stage existing ${target.name}")
                    }
                    movedExisting.add(target to backup)
                }
            }
            stagedFiles.forEach { (source, target) ->
                if (!source.renameTo(target)) {
                    throw IllegalStateException("Could not install restored ${target.name}")
                }
                installed.add(target)
            }
        } catch (e: Exception) {
            installed.asReversed().forEach { it.deleteRecursively() }
            movedExisting.asReversed().forEach { (target, backup) ->
                if (!backup.renameTo(target)) {
                    Log.e("WallpaperRepo", "Failed to roll back ${target.name}")
                }
            }
            throw e
        }
    }

    private fun findOrCreateBackupDocument(treeUri: Uri): Uri {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME)
        val cursor = context.contentResolver.query(
            childrenUri,
            projection,
            null,
            null,
            null
        ) ?: throw IllegalStateException("Could not list files in selected backup folder")
        val existingDocumentId = cursor.use {
            val idColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
            var found: String? = null
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumn) == BACKUP_FILE_NAME) {
                    found = cursor.getString(idColumn)
                    break
                }
            }
            found
        }
        if (existingDocumentId != null) {
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, existingDocumentId)
        }
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
        return DocumentsContract.createDocument(
            context.contentResolver,
            parentUri,
            "application/zip",
            BACKUP_FILE_NAME
        ) ?: throw IllegalStateException("Could not create backup file in selected folder")
    }

    suspend fun exportToFolder(treeUriString: String): Boolean = withContext(Dispatchers.IO) {
        backupMutex.withLock {
            try {
                val treeUri = Uri.parse(treeUriString)
                val targetUri = findOrCreateBackupDocument(treeUri)
                val output = context.contentResolver.openOutputStream(targetUri, "rwt")
                    ?: throw IllegalStateException("Could not open backup file for writing")
                val exported = exportToZip(output)
                if (exported) {
                    try {
                        deleteNumberedBackupCopies(treeUri)
                    } catch (e: Exception) {
                        Log.w("WallpaperRepo", "Backup saved, but old numbered copies could not be cleaned", e)
                    }
                }
                exported
            } catch (e: Exception) {
                Log.e("WallpaperRepo", "Failed to export backup to folder", e)
                false
            }
        }
    }

    private fun deleteNumberedBackupCopies(treeUri: Uri) {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
        val cursor = context.contentResolver.query(
            childrenUri,
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        ) ?: throw IllegalStateException("Could not list old backup copies")
        cursor.use {
            val idColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn)
                if (name.matches(Regex("""paperpages_backup \(\d+\)\.zip"""))) {
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        cursor.getString(idColumn)
                    )
                    if (!DocumentsContract.deleteDocument(context.contentResolver, documentUri)) {
                        Log.w("WallpaperRepo", "Could not delete old generated backup: $name")
                    }
                }
            }
        }
    }

    /**
     * Automatic backup to the user selected SAF directory.
     * Always writes strictly to "paperpages_backup.zip" (no timestamps, always overwriting).
     */
    private suspend fun autoBackup(treeUriString: String) = withContext(Dispatchers.IO) {
        try {
            if (!exportToFolder(treeUriString)) {
                Log.e("WallpaperRepo", "Automatic backup failed")
            }
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Auto backup error", e)
        }
    }

    companion object {
        private const val BACKUP_FILE_NAME = "paperpages_backup.zip"
        private const val MAX_BACKUP_UNCOMPRESSED_BYTES = 1024L * 1024L * 1024L

        @Volatile
        private var instance: WallpaperRepository? = null

        fun getInstance(context: Context): WallpaperRepository {
            return instance ?: synchronized(this) {
                instance ?: WallpaperRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
