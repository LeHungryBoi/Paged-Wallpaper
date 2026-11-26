package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.R
import com.example.darken.ExtraDimDetector
import com.example.data.model.OriginalMedia
import java.io.File
import java.io.FileOutputStream

internal object BundledSampleSeeder {

    const val SAMPLE_TALL_ID = "sample_warrior_tall"
    const val SAMPLE_WIDE_ID = "sample_warrior_wide"
    const val SAMPLE_SQUARE_ID = "sample_warrior_square"

    data class BundledSampleSpec(
        val id: String,
        val fileName: String,
        val drawableResId: Int,
        val defaultPageIndex: Int
    )

    fun defaultSpecs(): List<BundledSampleSpec> = listOf(
        BundledSampleSpec(
            id = SAMPLE_TALL_ID,
            fileName = "sample_warrior_tall.jpg",
            drawableResId = R.drawable.img_sample_warrior_tall,
            defaultPageIndex = 0
        ),
        BundledSampleSpec(
            id = SAMPLE_WIDE_ID,
            fileName = "sample_warrior_wide.jpg",
            drawableResId = R.drawable.img_sample_warrior_wide,
            defaultPageIndex = 1
        ),
        BundledSampleSpec(
            id = SAMPLE_SQUARE_ID,
            fileName = "sample_warrior_square.jpg",
            drawableResId = R.drawable.img_sample_warrior_square,
            defaultPageIndex = 2
        )
    )

    fun materializeSamples(
        context: Context,
        originalsDir: File,
        existingOriginals: List<OriginalMedia>,
        luminanceThreshold: Float
    ): List<OriginalMedia> {
        if (!originalsDir.exists()) {
            originalsDir.mkdirs()
        }
        val existingIds = existingOriginals.mapTo(mutableSetOf()) { it.id }
        val baseTimestamp = System.currentTimeMillis()
        val added = mutableListOf<OriginalMedia>()

        defaultSpecs().forEachIndexed { index, spec ->
            if (spec.id in existingIds) return@forEachIndexed
            val destFile = File(originalsDir, spec.fileName)
            if (!isValidImageFile(destFile) && !writeDrawableToFile(context, spec.drawableResId, destFile)) {
                return@forEachIndexed
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(destFile.absolutePath, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) {
                Log.w("BundledSampleSeeder", "Invalid dimensions for bundled sample ${spec.id}")
                return@forEachIndexed
            }

            val sampleSize = (maxOf(width, height) / 300).coerceAtLeast(1)
            val sampleOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val sampleBmp = BitmapFactory.decodeFile(destFile.absolutePath, sampleOpts)
            val luminance = if (sampleBmp != null) {
                val value = ExtraDimDetector.calculateLuminance(sampleBmp)
                sampleBmp.recycle()
                value
            } else {
                0.5f
            }

            added.add(
                OriginalMedia(
                    id = spec.id,
                    fileName = spec.fileName,
                    dateAdded = baseTimestamp + index,
                    width = width,
                    height = height,
                    averageLuminance = luminance,
                    isDarkDetected = luminance < luminanceThreshold
                )
            )
        }

        return added
    }

    private fun isValidImageFile(file: File): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    private fun writeDrawableToFile(
        context: Context,
        drawableResId: Int,
        destFile: File
    ): Boolean {
        val rawCopied = try {
            context.resources.openRawResource(drawableResId).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            isValidImageFile(destFile)
        } catch (e: Exception) {
            false
        }
        if (rawCopied) return true

        return try {
            val opts = BitmapFactory.Options().apply { inScaled = false }
            val bitmap = BitmapFactory.decodeResource(context.resources, drawableResId, opts)
                ?: return false
            try {
                FileOutputStream(destFile).use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
                }
                isValidImageFile(destFile)
            } finally {
                bitmap.recycle()
            }
        } catch (fallbackError: Exception) {
            Log.e("BundledSampleSeeder", "Failed to write bundled drawable $drawableResId", fallbackError)
            false
        }
    }
}
