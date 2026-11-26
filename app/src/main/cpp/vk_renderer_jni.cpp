#include "vk_engine.h"

#include <android/bitmap.h>
#include <android/native_window_jni.h>
#include <cstring>
#include <vector>

using paperpages_vk::VulkanEngine;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_render_VulkanNativeBridge_nativeCreate(JNIEnv*, jclass) {
    auto* engine = new VulkanEngine();
    if (!engine->initDevice()) {
        delete engine;
        return 0L;
    }
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_example_render_VulkanNativeBridge_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    auto* engine = reinterpret_cast<VulkanEngine*>(handle);
    if (!engine) return;
    engine->destroyDevice();
    delete engine;
}

JNIEXPORT jint JNICALL
Java_com_example_render_VulkanNativeBridge_nativeGetMaxTextureSize(JNIEnv*, jclass, jlong handle) {
    auto* engine = reinterpret_cast<VulkanEngine*>(handle);
    return engine ? static_cast<jint>(engine->maxImageDimension2D) : 4096;
}

JNIEXPORT jint JNICALL
Java_com_example_render_VulkanNativeBridge_nativeGetWhiteTextureId(JNIEnv*, jclass, jlong handle) {
    auto* engine = reinterpret_cast<VulkanEngine*>(handle);
    return engine ? static_cast<jint>(engine->whiteTextureId) : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_example_render_VulkanNativeBridge_nativeOnSurfaceChanged(
    JNIEnv* env, jclass, jlong handle, jobject surface, jint width, jint height
) {
    auto* engine = reinterpret_cast<VulkanEngine*>(handle);
    if (!engine || !surface || width <= 0 || height <= 0) return JNI_FALSE;
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return JNI_FALSE;
    const bool ok = engine->bindSurface(window, static_cast<uint32_t>(width), static_cast<uint32_t>(height));
    ANativeWindow_release(window);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_render_VulkanNativeBridge_nativeOnSurfaceDestroyed(JNIEnv*, jclass, jlong handle) {
    auto* engine = reinterpret_cast<VulkanEngine*>(handle);
    if (engine) engine->unbindSurface();
}

JNIEXPORT jint JNICALL
Java_com_example_render_VulkanNativeBridge_nativeUploadBitmap(
    JNIEnv* env, jclass, jlong handle, jobject bitmap
) {
    auto* engine = reinterpret_cast<VulkanEngine*>(handle);
    if (!engine || !bitmap) return 0;

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return 0;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0) return 0;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) {
        return 0;
    }

    int32_t texId = 0;
    const uint32_t rowBytes = info.width * 4;
    if (info.stride == rowBytes) {
        texId = engine->uploadRgbaTexture(pixels, info.width, info.height);
    } else {
        std::vector<uint8_t> packed(static_cast<size_t>(rowBytes) * info.height);
        const auto* src = static_cast<const uint8_t*>(pixels);
        for (uint32_t y = 0; y < info.height; ++y) {
            std::memcpy(packed.data() + static_cast<size_t>(y) * rowBytes, src + static_cast<size_t>(y) * info.stride, rowBytes);
        }
        texId = engine->uploadRgbaTexture(packed.data(), info.width, info.height);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return texId;
}

JNIEXPORT void JNICALL
Java_com_example_render_VulkanNativeBridge_nativeDeleteTexture(JNIEnv*, jclass, jlong handle, jint textureId) {
    auto* engine = reinterpret_cast<VulkanEngine*>(handle);
    if (engine) engine->deleteTexture(textureId);
}

JNIEXPORT void JNICALL
Java_com_example_render_VulkanNativeBridge_nativeClearUserTextures(JNIEnv*, jclass, jlong handle) {
    auto* engine = reinterpret_cast<VulkanEngine*>(handle);
    if (engine) engine->clearUserTextures();
}

JNIEXPORT jint JNICALL
Java_com_example_render_VulkanNativeBridge_nativeBeginFrame(
    JNIEnv*, jclass, jlong handle, jboolean useMotionBlur, jint mbWidth, jint mbHeight,
    jfloat clearR, jfloat clearG, jfloat clearB
) {
    auto* engine = reinterpret_cast<VulkanEngine*>(handle);
    if (!engine) return -1;
    return engine->beginFrame(
        useMotionBlur == JNI_TRUE,
        static_cast<uint32_t>(mbWidth > 0 ? mbWidth : 0),
        static_cast<uint32_t>(mbHeight > 0 ? mbHeight : 0),
        clearR, clearG, clearB
    );
}

JNIEXPORT void JNICALL
Java_com_example_render_VulkanNativeBridge_nativeBeginMainPass(
    JNIEnv*, jclass, jlong handle, jfloat clearR, jfloat clearG, jfloat clearB
) {
    auto* engine = reinterpret_cast<VulkanEngine*>(handle);
    if (engine) engine->beginMainPass(clearR, clearG, clearB);
}

JNIEXPORT void JNICALL
Java_com_example_render_VulkanNativeBridge_nativeDrawQuad(
    JNIEnv* env, jclass, jlong handle,
    jint textureId, jint darkTextureId, jfloat darkMix,
    jfloatArray mvpArray,
    jfloat uvOffsetX, jfloat uvOffsetY, jfloat uvScaleX, jfloat uvScaleY,
    jfloat r, jfloat g, jfloat b, jfloat a
) {
    auto* engine = reinterpret_cast<VulkanEngine*>(handle);
    if (!engine || !mvpArray) return;
    float mvp[16];
    env->GetFloatArrayRegion(mvpArray, 0, 16, mvp);
    engine->drawQuad(
        textureId, darkTextureId, darkMix, mvp,
        uvOffsetX, uvOffsetY, uvScaleX, uvScaleY,
        r, g, b, a
    );
}

JNIEXPORT void JNICALL
Java_com_example_render_VulkanNativeBridge_nativeDrawMotionBlurPass(
    JNIEnv* env, jclass, jlong handle,
    jfloatArray mvpArray, jint radius, jfloat sampleStep, jfloat direction
) {
    auto* engine = reinterpret_cast<VulkanEngine*>(handle);
    if (!engine || !mvpArray) return;
    float mvp[16];
    env->GetFloatArrayRegion(mvpArray, 0, 16, mvp);
    engine->drawMotionBlurPass(mvp, radius, sampleStep, direction);
}

JNIEXPORT jint JNICALL
Java_com_example_render_VulkanNativeBridge_nativeEndFrame(JNIEnv*, jclass, jlong handle) {
    auto* engine = reinterpret_cast<VulkanEngine*>(handle);
    return engine ? engine->endFrame() : -1;
}

} // extern "C"
