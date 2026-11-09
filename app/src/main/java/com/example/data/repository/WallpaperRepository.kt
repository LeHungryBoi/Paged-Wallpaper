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
import android.graphics.Shader
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.example.darken.ExtraDimDetector
import com.example.data.model.CalibrationConfig
import com.example.data.model.CropFillMode
import com.example.data.model.DarkenConfig
import com.example.data.model.OriginalMedia
import com.example.data.model.PageItem
import com.example.data.model.WallpaperAppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class WallpaperRepository private constructor(private val context: Context) {

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
    private val bitmapCache = mutableMapOf<Int, Bitmap>()

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
        loadOriginalsMeta()

        // Load config
        if (configFile.exists()) {
            try {
                val jsonStr = configFile.readText()
                val loaded = WallpaperAppState.fromJson(JSONObject(jsonStr))
                _appState.value = loaded
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

    private fun loadOriginalsMeta() {
        if (originalsMetaFile.exists()) {
            try {
                val jsonStr = originalsMetaFile.readText()
                val arr = JSONArray(jsonStr)
                val list = mutableListOf<OriginalMedia>()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { list.add(OriginalMedia.fromJson(it)) }
                }
                _originals.value = list
            } catch (e: Exception) {
                Log.e("WallpaperRepo", "Error loading originals meta", e)
            }
        }
    }

    private fun saveOriginalsMeta(list: List<OriginalMedia>) {
        _originals.value = list
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            originalsMetaFile.writeText(arr.toString())
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Error saving originals meta", e)
        }
    }

    private fun saveConfigInternal(state: WallpaperAppState) {
        try {
            configFile.writeText(state.toJson().toString(2))
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Error writing config", e)
        }
    }

    fun updateState(transform: (WallpaperAppState) -> WallpaperAppState) {
        val newState = transform(_appState.value)
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

    fun invalidateCache() {
        synchronized(bitmapCache) {
            bitmapCache.values.forEach { it.recycle() }
            bitmapCache.clear()
        }
    }

    fun getCroppedBitmap(pageIndex: Int, reqWidth: Int, reqHeight: Int): Bitmap? {
        synchronized(bitmapCache) {
            bitmapCache[pageIndex]?.let {
                if (!it.isRecycled) return it
            }
        }

        val page = _appState.value.pages.find { it.pageIndex == pageIndex }
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
                bitmapCache[pageIndex] = bmp
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

    suspend fun updateOriginalMedia(
        updatedMedia: OriginalMedia,
        targetWidth: Int,
        targetHeight: Int
    ): Boolean = withContext(Dispatchers.IO) {
        if (targetWidth <= 0 || targetHeight <= 0) return@withContext false
        val existing = _originals.value.find { it.id == updatedMedia.id }
            ?: return@withContext false
        val normalized = updatedMedia.copy(
            blurAmount = updatedMedia.blurAmount.coerceIn(0f, 1f),
            cropPanX = updatedMedia.cropPanX.coerceIn(-1f, 1f),
            cropPanY = updatedMedia.cropPanY.coerceIn(-1f, 1f),
            cropScale = updatedMedia.cropScale.coerceIn(1f, 3f)
        )
        val assignedPages = _appState.value.pages.filter { it.originalId == existing.id }
        for (page in assignedPages) {
            if (!renderMediaForPage(normalized, page.pageIndex, targetWidth, targetHeight)) {
                return@withContext false
            }
        }

        saveOriginalsMeta(
            _originals.value.map { if (it.id == normalized.id) normalized else it }
        )
        if (assignedPages.isNotEmpty()) {
            val updatedPages = _appState.value.pages.map { page ->
                if (page.originalId == normalized.id) {
                    page.copy(isAlreadyDark = normalized.isAlreadyDark)
                } else {
                    page
                }
            }
            updateState { it.copy(pages = updatedPages) }
        }
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
        if (!renderMediaForPage(media, pageIndex, targetWidth, targetHeight)) {
            return@withContext false
        }

        val newPage = PageItem(
            pageIndex = pageIndex,
            originalId = media.id,
            croppedFileName = "page_$pageIndex.jpg",
            isAlreadyDark = media.isAlreadyDark,
            customLabel = if (pageIndex == _appState.value.calibration.homePageIndex) {
                "Home"
            } else {
                "Page ${pageIndex + 1}"
            }
        )
        val pages = _appState.value.pages.toMutableList()
        val existingIndex = pages.indexOfFirst { it.pageIndex == pageIndex }
        if (existingIndex >= 0) pages[existingIndex] = newPage else pages.add(newPage)
        updateState { it.copy(pages = pages) }
        true
    }

    private fun renderMediaForPage(
        media: OriginalMedia,
        pageIndex: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Boolean {
        if (targetWidth <= 0 || targetHeight <= 0) return false
        val originalFile = File(originalsDir, media.fileName)
        if (!originalFile.exists()) return false
        val fullBitmap = BitmapFactory.decodeFile(originalFile.absolutePath) ?: return false
        val outputBitmap = try {
            Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            fullBitmap.recycle()
            Log.e("WallpaperRepo", "Not enough memory to render media ${media.id}", e)
            return false
        }
        var blurredBackground: Bitmap? = null

        try {
            val canvas = Canvas(outputBitmap)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            val destination = Rect(0, 0, targetWidth, targetHeight)
            val sourceWidth = fullBitmap.width
            val sourceHeight = fullBitmap.height
            val targetRatio = targetWidth.toFloat() / targetHeight
            val sourceRatio = sourceWidth.toFloat() / sourceHeight
            val centerCropWidth = if (sourceRatio > targetRatio) {
                (sourceHeight * targetRatio).toInt().coerceIn(1, sourceWidth)
            } else {
                sourceWidth
            }
            val centerCropHeight = if (sourceRatio > targetRatio) {
                sourceHeight
            } else {
                (sourceWidth / targetRatio).toInt().coerceIn(1, sourceHeight)
            }
            val centerCrop = Rect(
                (sourceWidth - centerCropWidth) / 2,
                (sourceHeight - centerCropHeight) / 2,
                (sourceWidth + centerCropWidth) / 2,
                (sourceHeight + centerCropHeight) / 2
            )
            val scale = media.cropScale.coerceIn(1f, 3f)
            val panX = media.cropPanX.coerceIn(-1f, 1f)
            val panY = media.cropPanY.coerceIn(-1f, 1f)

            if (media.cropFillMode != CropFillMode.CROP) {
                val downsampleScale = 1f - media.blurAmount.coerceIn(0f, 1f) * 0.94f
                val backgroundWidth = (targetWidth * downsampleScale).toInt().coerceAtLeast(1)
                val backgroundHeight = (targetHeight * downsampleScale).toInt().coerceAtLeast(1)
                blurredBackground = Bitmap.createBitmap(
                    backgroundWidth,
                    backgroundHeight,
                    Bitmap.Config.ARGB_8888
                )
                Canvas(blurredBackground!!).drawBitmap(
                    fullBitmap,
                    centerCrop,
                    Rect(0, 0, backgroundWidth, backgroundHeight),
                    paint
                )
                canvas.drawBitmap(blurredBackground!!, null, destination, paint)
                if (media.cropFillMode == CropFillMode.GLASS) {
                    canvas.drawColor(Color.argb(58, 24, 28, 38))
                }

                val fitScale = minOf(
                    targetWidth.toFloat() / sourceWidth,
                    targetHeight.toFloat() / sourceHeight
                ) * scale
                val drawWidth = sourceWidth * fitScale
                val drawHeight = sourceHeight * fitScale
                val centerX = targetWidth / 2f +
                    panX * ((drawWidth - targetWidth) / 2f).coerceAtLeast(0f)
                val centerY = targetHeight / 2f +
                    panY * ((drawHeight - targetHeight) / 2f).coerceAtLeast(0f)
                val foreground = Rect(
                    (centerX - drawWidth / 2f).toInt(),
                    (centerY - drawHeight / 2f).toInt(),
                    (centerX + drawWidth / 2f).toInt(),
                    (centerY + drawHeight / 2f).toInt()
                )
                canvas.drawBitmap(fullBitmap, null, foreground, paint)
            } else {
                val cropWidth = if (sourceRatio > targetRatio) {
                    sourceHeight * targetRatio
                } else {
                    sourceWidth.toFloat()
                }
                val cropHeight = if (sourceRatio > targetRatio) {
                    sourceHeight.toFloat()
                } else {
                    sourceWidth / targetRatio
                }
                val effectiveCropWidth = (cropWidth / scale).coerceAtLeast(1f)
                val effectiveCropHeight = (cropHeight / scale).coerceAtLeast(1f)
                val centerX = sourceWidth / 2f +
                    panX * ((sourceWidth - effectiveCropWidth) / 2f).coerceAtLeast(0f)
                val centerY = sourceHeight / 2f +
                    panY * ((sourceHeight - effectiveCropHeight) / 2f).coerceAtLeast(0f)
                val sourceRect = Rect(
                    (centerX - effectiveCropWidth / 2f).toInt().coerceIn(0, sourceWidth - 1),
                    (centerY - effectiveCropHeight / 2f).toInt().coerceIn(0, sourceHeight - 1),
                    (centerX + effectiveCropWidth / 2f).toInt().coerceIn(1, sourceWidth),
                    (centerY + effectiveCropHeight / 2f).toInt().coerceIn(1, sourceHeight)
                )
                canvas.drawBitmap(fullBitmap, sourceRect, destination, paint)
            }

            FileOutputStream(File(croppedDir, "page_$pageIndex.jpg")).use { out ->
                outputBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Failed to render media ${media.id} for page $pageIndex", e)
            return false
        } finally {
            blurredBackground?.recycle()
            outputBitmap.recycle()
            fullBitmap.recycle()
        }
        return true
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
    suspend fun exportToZip(outputStream: java.io.OutputStream): Boolean = withContext(Dispatchers.IO) {
        try {
            ZipOutputStream(BufferedOutputStream(outputStream)).use { zipOut ->
                // Write config.json
                if (configFile.exists()) {
                    zipOut.putNextEntry(ZipEntry("config.json"))
                    FileInputStream(configFile).use { it.copyTo(zipOut) }
                    zipOut.closeEntry()
                }

                // Write originals_meta.json
                if (originalsMetaFile.exists()) {
                    zipOut.putNextEntry(ZipEntry("originals_meta.json"))
                    FileInputStream(originalsMetaFile).use { it.copyTo(zipOut) }
                    zipOut.closeEntry()
                }

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
        try {
            ZipInputStream(BufferedInputStream(inputStream)).use { zipIn ->
                var entry: ZipEntry? = zipIn.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    when {
                        entryName == "config.json" -> {
                            FileOutputStream(configFile).use { zipIn.copyTo(it) }
                        }
                        entryName == "originals_meta.json" -> {
                            FileOutputStream(originalsMetaFile).use { zipIn.copyTo(it) }
                        }
                        entryName.startsWith("originals/") -> {
                            val fileName = entryName.removePrefix("originals/")
                            if (fileName.isNotEmpty()) {
                                val dest = File(originalsDir, fileName)
                                FileOutputStream(dest).use { zipIn.copyTo(it) }
                            }
                        }
                        entryName.startsWith("cropped/") -> {
                            val fileName = entryName.removePrefix("cropped/")
                            if (fileName.isNotEmpty()) {
                                val dest = File(croppedDir, fileName)
                                FileOutputStream(dest).use { zipIn.copyTo(it) }
                            }
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }

            // Reload state
            loadInitialData()
            invalidateCache()
            notifyListeners()
            true
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Failed to import ZIP", e)
            false
        }
    }

    /**
     * Automatic backup to the user selected SAF directory.
     * Always writes strictly to "paperpages_backup.zip" (no timestamps, always overwriting).
     */
    private suspend fun autoBackup(treeUriString: String) = withContext(Dispatchers.IO) {
        try {
            val treeUri = Uri.parse(treeUriString)
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            val targetDocUri = DocumentsContract.createDocument(
                context.contentResolver,
                parentDocUri,
                "application/zip",
                "paperpages_backup.zip"
            )
            if (targetDocUri != null) {
                context.contentResolver.openOutputStream(targetDocUri, "rwt")?.use { out ->
                    exportToZip(out)
                }
            }
        } catch (e: Exception) {
            Log.e("WallpaperRepo", "Auto backup error", e)
        }
    }

    companion object {
        @Volatile
        private var instance: WallpaperRepository? = null

        fun getInstance(context: Context): WallpaperRepository {
            return instance ?: synchronized(this) {
                instance ?: WallpaperRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
