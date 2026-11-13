package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.R
import com.example.data.model.APP_ICON_WINDOW_COUNT
import com.example.data.model.AppIconFrame
import com.example.data.model.AppIconLibraryState
import com.example.data.model.AppIconPreset
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.max

data class AppIconImages(
    val single: Bitmap?,
    val windows: List<Bitmap?>
)

/**
 * Stores icon presets (framing + mode) as JSON and keeps each preset's source
 * images in its own folder so presets are fully independent of each other.
 */
class AppIconRepository(context: Context) {
    private val appContext = context.applicationContext
    private val stateFile = File(appContext.filesDir, STATE_FILE_NAME)
    private val imagesDir = File(appContext.filesDir, IMAGES_DIR_NAME)
    private val legacySourceFile = File(appContext.filesDir, LEGACY_SOURCE_NAME)
    private val legacyPrefs =
        appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun loadState(): AppIconLibraryState {
        val parsed = runCatching {
            if (!stateFile.isFile) null else AppIconLibraryState.fromJson(JSONObject(stateFile.readText()))
        }.getOrNull()
        if (parsed != null) return parsed
        val migrated = migrateLegacyConfiguration()
        saveState(migrated)
        return migrated
    }

    @Synchronized
    fun saveState(state: AppIconLibraryState) {
        writeAtomically(stateFile, state.toJson().toString(2))
    }

    fun loadImages(presetId: String): AppIconImages = AppIconImages(
        single = decodeFile(singleImageFile(presetId)),
        windows = List(APP_ICON_WINDOW_COUNT) { index ->
            decodeFile(windowImageFile(presetId, index))
        }
    )

    fun hasImage(presetId: String, windowIndex: Int?): Boolean =
        if (windowIndex == null) singleImageFile(presetId).isFile
        else windowImageFile(presetId, windowIndex).isFile

    fun singleImageFile(presetId: String): File =
        File(File(imagesDir, presetId), SINGLE_IMAGE_NAME)

    fun windowImageFile(presetId: String, index: Int): File =
        File(File(imagesDir, presetId), "window-$index.img")

    /**
     * Copies the picked image into the preset folder and returns a decoded copy
     * for immediate preview. [windowIndex] null means the shared single image.
     */
    fun importImage(presetId: String, windowIndex: Int?, uri: Uri): Bitmap {
        val destination = if (windowIndex == null) singleImageFile(presetId)
        else windowImageFile(presetId, windowIndex)
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        try {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: throw IOException("The selected image could not be opened.")
            input.use { stream ->
                FileOutputStream(temporary).use { output -> stream.copyTo(output) }
            }
            val decoded = decodeFile(temporary)
                ?: throw IOException("The selected file is not a readable image.")
            if (temporary.exists() && !temporary.renameTo(destination)) {
                destination.delete()
                if (!temporary.renameTo(destination)) {
                    throw IOException("The selected image could not be saved.")
                }
            }
            return decoded
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun deletePresetImages(presetId: String) {
        runCatching { File(imagesDir, presetId).deleteRecursively() }
    }

    fun copyPresetImages(sourcePresetId: String, targetPresetId: String) {
        val sourceDir = File(imagesDir, sourcePresetId)
        if (!sourceDir.isDirectory) return
        val targetDir = File(imagesDir, targetPresetId)
        if (!targetDir.exists() && !targetDir.mkdirs()) return
        sourceDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                runCatching { file.copyTo(File(targetDir, file.name), overwrite = true) }
            }
        }
    }

    fun defaultArtwork(): Bitmap? = runCatching {
        BitmapFactory.decodeResource(
            appContext.resources,
            R.drawable.wallpaper_icon,
            BitmapFactory.Options().apply {
                inSampleSize = 2
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }.getOrNull()

    private fun decodeFile(file: File?): Bitmap? {
        if (file == null || !file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = (max(bounds.outWidth, bounds.outHeight) / MAX_IMAGE_EDGE).coerceAtLeast(1)
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }

    private fun migrateLegacyConfiguration(): AppIconLibraryState {
        val preset = AppIconPreset(
            id = UUID.randomUUID().toString(),
            name = "Default",
            single = AppIconFrame(
                zoom = legacyPrefs.getFloat("zoom", 1f),
                panX = legacyPrefs.getFloat("pan_x", 0f),
                panY = legacyPrefs.getFloat("pan_y", 0f),
                flipped = legacyPrefs.getBoolean("flipped", false)
            ).sanitized()
        )
        runCatching {
            if (legacySourceFile.isFile) {
                val destination = singleImageFile(preset.id)
                destination.parentFile?.mkdirs()
                legacySourceFile.copyTo(destination, overwrite = true)
                legacySourceFile.delete()
            }
        }
        return AppIconLibraryState(presets = listOf(preset), activePresetId = preset.id)
    }

    private fun writeAtomically(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        try {
            temporary.writeText(content)
            if (temporary.exists() && !temporary.renameTo(target)) {
                target.delete()
                if (!temporary.renameTo(target)) {
                    throw IOException("The icon preset file could not be saved.")
                }
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private companion object {
        const val STATE_FILE_NAME = "app-icon-library.json"
        const val IMAGES_DIR_NAME = "app-icons"
        const val SINGLE_IMAGE_NAME = "single.img"
        const val LEGACY_SOURCE_NAME = "app-icon-source"
        const val LEGACY_PREFS_NAME = "app_icon_settings"
        const val MAX_IMAGE_EDGE = 1024
    }
}
