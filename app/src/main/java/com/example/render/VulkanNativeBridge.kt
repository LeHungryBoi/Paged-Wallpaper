package com.example.render

import android.graphics.Bitmap
import android.view.Surface

internal object VulkanNativeBridge {

    val isNativeLoaded: Boolean by lazy {
        try {
            System.loadLibrary("paperpages_vk")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    @JvmStatic
    external fun nativeCreate(): Long

    @JvmStatic
    external fun nativeDestroy(handle: Long)

    @JvmStatic
    external fun nativeGetMaxTextureSize(handle: Long): Int

    @JvmStatic
    external fun nativeGetWhiteTextureId(handle: Long): Int

    @JvmStatic
    external fun nativeOnSurfaceChanged(
        handle: Long,
        surface: Surface,
        width: Int,
        height: Int
    ): Boolean

    @JvmStatic
    external fun nativeOnSurfaceDestroyed(handle: Long)

    @JvmStatic
    external fun nativeUploadBitmap(handle: Long, bitmap: Bitmap): Int

    @JvmStatic
    external fun nativeDeleteTexture(handle: Long, textureId: Int)

    @JvmStatic
    external fun nativeClearUserTextures(handle: Long)

    @JvmStatic
    external fun nativeBeginFrame(
        handle: Long,
        traceFrameId: Long,
        requestTimeNanos: Long,
        useMotionBlur: Boolean,
        mbWidth: Int,
        mbHeight: Int,
        clearR: Float,
        clearG: Float,
        clearB: Float
    ): Int

    @JvmStatic
    external fun nativeBeginMainPass(
        handle: Long,
        clearR: Float,
        clearG: Float,
        clearB: Float
    )

    @JvmStatic
    external fun nativeDrawQuad(
        handle: Long,
        textureId: Int,
        darkTextureId: Int,
        darkMix: Float,
        mvpArray: FloatArray,
        uvOffsetX: Float,
        uvOffsetY: Float,
        uvScaleX: Float,
        uvScaleY: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float
    )

    @JvmStatic
    external fun nativeDrawMotionBlurPass(
        handle: Long,
        mvpArray: FloatArray,
        radius: Int,
        sampleStep: Float,
        direction: Float
    )

    @JvmStatic
    external fun nativeEndFrame(handle: Long): Int

    @JvmStatic
    external fun nativeGetFrameTrace(handle: Long, afterRevision: Long): LongArray
}
