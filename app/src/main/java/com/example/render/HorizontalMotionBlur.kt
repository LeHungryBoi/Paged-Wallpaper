package com.example.render

import android.graphics.Bitmap

class HorizontalMotionBlur {
    private var pixels = IntArray(0)
    private var rowPixels = IntArray(0)
    private var alphaPrefix = IntArray(0)
    private var redPrefix = IntArray(0)
    private var greenPrefix = IntArray(0)
    private var bluePrefix = IntArray(0)

    fun apply(bitmap: Bitmap, radius: Int, motionDirection: Int) {
        if (radius <= 0 || motionDirection == 0) return

        val width = bitmap.width
        val height = bitmap.height
        if (width <= 1 || height <= 0) return
        ensureCapacity(width, width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val sampleCount = radius + 1
        for (y in 0 until height) {
            val rowStart = y * width
            System.arraycopy(pixels, rowStart, rowPixels, 0, width)
            buildPrefixes(width)

            for (x in 0 until width) {
                val color: Int
                val repeatedEdgePixels: Int
                val edgeColor: Int
                val prefixStart: Int
                val prefixEndExclusive: Int

                if (motionDirection > 0) {
                    val wantedStart = x - radius
                    prefixStart = wantedStart.coerceAtLeast(0)
                    prefixEndExclusive = x + 1
                    repeatedEdgePixels = (-wantedStart).coerceAtLeast(0)
                    edgeColor = rowPixels[0]
                } else {
                    val wantedEnd = x + radius
                    prefixStart = x
                    prefixEndExclusive = (wantedEnd + 1).coerceAtMost(width)
                    repeatedEdgePixels = (wantedEnd - width + 1).coerceAtLeast(0)
                    edgeColor = rowPixels[width - 1]
                }

                val alpha = (
                    alphaPrefix[prefixEndExclusive] - alphaPrefix[prefixStart] +
                        ((edgeColor ushr 24) and 0xff) * repeatedEdgePixels
                    ) / sampleCount
                val red = (
                    redPrefix[prefixEndExclusive] - redPrefix[prefixStart] +
                        ((edgeColor ushr 16) and 0xff) * repeatedEdgePixels
                    ) / sampleCount
                val green = (
                    greenPrefix[prefixEndExclusive] - greenPrefix[prefixStart] +
                        ((edgeColor ushr 8) and 0xff) * repeatedEdgePixels
                    ) / sampleCount
                val blue = (
                    bluePrefix[prefixEndExclusive] - bluePrefix[prefixStart] +
                        (edgeColor and 0xff) * repeatedEdgePixels
                    ) / sampleCount

                color =
                    (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                pixels[rowStart + x] = color
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    fun release() {
        pixels = IntArray(0)
        rowPixels = IntArray(0)
        alphaPrefix = IntArray(0)
        redPrefix = IntArray(0)
        greenPrefix = IntArray(0)
        bluePrefix = IntArray(0)
    }

    private fun ensureCapacity(width: Int, pixelCount: Int) {
        if (pixels.size < pixelCount) pixels = IntArray(pixelCount)
        if (rowPixels.size < width) rowPixels = IntArray(width)
        if (alphaPrefix.size < width + 1) {
            alphaPrefix = IntArray(width + 1)
            redPrefix = IntArray(width + 1)
            greenPrefix = IntArray(width + 1)
            bluePrefix = IntArray(width + 1)
        }
    }

    private fun buildPrefixes(width: Int) {
        alphaPrefix[0] = 0
        redPrefix[0] = 0
        greenPrefix[0] = 0
        bluePrefix[0] = 0

        for (x in 0 until width) {
            val color = rowPixels[x]
            alphaPrefix[x + 1] = alphaPrefix[x] + ((color ushr 24) and 0xff)
            redPrefix[x + 1] = redPrefix[x] + ((color ushr 16) and 0xff)
            greenPrefix[x + 1] = greenPrefix[x] + ((color ushr 8) and 0xff)
            bluePrefix[x + 1] = bluePrefix[x] + (color and 0xff)
        }
    }
}
