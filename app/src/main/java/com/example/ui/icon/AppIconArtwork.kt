package com.example.ui.icon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.example.data.model.APP_ICON_WINDOW_COUNT
import com.example.data.model.AppIconFrame
import com.example.data.model.AppIconGeometry
import com.example.data.model.AppIconMode
import kotlin.math.max
import kotlin.math.min

/**
 * Renders the three-window icon artwork. Geometry comes from [AppIconGeometry]
 * so the editor previews and the exported 512px shortcut icon always match.
 */
object AppIconArtwork {
    val BACKGROUND_COLOR: Int = android.graphics.Color.rgb(20, 24, 36)

    private fun bitmapPaint() = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun windowRect(size: Float, index: Int): RectF = RectF(
        size * AppIconGeometry.left(index),
        size * AppIconGeometry.top(index),
        size * AppIconGeometry.right(index),
        size * AppIconGeometry.bottom(index)
    )

    fun windowPath(size: Float, index: Int): Path = Path().apply {
        val radius = size * AppIconGeometry.WINDOW_CORNER_RADIUS
        addRoundRect(windowRect(size, index), radius, radius, Path.Direction.CW)
    }

    fun windowsPath(size: Float): Path = Path().apply {
        val radius = size * AppIconGeometry.WINDOW_CORNER_RADIUS
        repeat(APP_ICON_WINDOW_COUNT) { index ->
            addRoundRect(windowRect(size, index), radius, radius, Path.Direction.CW)
        }
    }

    /** Target rect used by a single-window preview, centred in the preview box. */
    fun windowPreviewRect(boxWidth: Float, boxHeight: Float, inset: Float): RectF {
        val usableWidth = (boxWidth - inset * 2f).coerceAtLeast(1f)
        val usableHeight = (boxHeight - inset * 2f).coerceAtLeast(1f)
        val scale = min(
            usableWidth / AppIconGeometry.WINDOW_WIDTH,
            usableHeight / AppIconGeometry.WINDOW_HEIGHT
        )
        val width = AppIconGeometry.WINDOW_WIDTH * scale
        val height = AppIconGeometry.WINDOW_HEIGHT * scale
        return RectF(
            (boxWidth - width) / 2f,
            (boxHeight - height) / 2f,
            (boxWidth + width) / 2f,
            (boxHeight + height) / 2f
        )
    }

    fun windowPreviewPath(rect: RectF): Path = Path().apply {
        val radius = rect.width() / AppIconGeometry.WINDOW_WIDTH * AppIconGeometry.WINDOW_CORNER_RADIUS
        addRoundRect(rect, radius, radius, Path.Direction.CW)
    }

    /**
     * Draws [bitmap] fitted/zoomed/panned inside [target], clipped to [clipPath].
     * Pan offsets are fractions of the target rect, which keeps gesture math
     * identical for the whole-icon preview and per-window previews.
     */
    fun drawFramed(
        canvas: Canvas,
        bitmap: Bitmap,
        frame: AppIconFrame,
        target: RectF,
        clipPath: Path
    ) {
        if (bitmap.isRecycled || target.width() <= 0f || target.height() <= 0f) return
        val sanitized = frame.sanitized()
        val fit = max(
            target.width() / bitmap.width,
            target.height() / bitmap.height
        ) * sanitized.zoom
        val width = bitmap.width * fit
        val height = bitmap.height * fit
        val left = target.left + (target.width() - width) / 2f + sanitized.panX * target.width()
        val top = target.top + (target.height() - height) / 2f + sanitized.panY * target.height()
        canvas.save()
        canvas.clipPath(clipPath)
        if (sanitized.flipped) {
            canvas.scale(-1f, 1f, left + width / 2f, top + height / 2f)
        }
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), bitmapPaint())
        canvas.restore()
    }

    fun drawMosaic(
        canvas: Canvas,
        bitmap: Bitmap,
        frame: AppIconFrame,
        size: Float,
        originLeft: Float = 0f,
        originTop: Float = 0f
    ) {
        canvas.save()
        canvas.translate(originLeft, originTop)
        drawFramed(
            canvas,
            bitmap,
            frame,
            RectF(0f, 0f, size, size),
            windowsPath(size)
        )
        canvas.restore()
    }

    fun drawWindow(
        canvas: Canvas,
        bitmap: Bitmap,
        frame: AppIconFrame,
        size: Float,
        index: Int
    ) {
        drawFramed(canvas, bitmap, frame, windowRect(size, index), windowPath(size, index))
    }

    /**
     * Exports the icon. [fallback] is the bundled artwork used for windows that
     * do not have a custom image yet.
     */
    fun compose(
        size: Int,
        mode: AppIconMode,
        singleBitmap: Bitmap?,
        singleFrame: AppIconFrame,
        windowBitmaps: List<Bitmap?>,
        windowFrames: List<AppIconFrame>,
        fallback: Bitmap
    ): Bitmap {
        val export = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(export)
        canvas.drawColor(BACKGROUND_COLOR)
        when (mode) {
            AppIconMode.SINGLE -> drawMosaic(
                canvas = canvas,
                bitmap = singleBitmap ?: fallback,
                frame = singleFrame,
                size = size.toFloat()
            )

            AppIconMode.PER_WINDOW -> repeat(APP_ICON_WINDOW_COUNT) { index ->
                drawWindow(
                    canvas = canvas,
                    bitmap = windowBitmaps.getOrNull(index) ?: fallback,
                    frame = windowFrames.getOrNull(index) ?: AppIconFrame(),
                    size = size.toFloat(),
                    index = index
                )
            }
        }
        return export
    }
}
