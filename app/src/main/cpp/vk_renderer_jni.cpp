#include "vk_engine.h"

#include <android/bitmap.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <cstdint>
#include <cstring>
#include <vector>

namespace {

inline paperpages_vk::VulkanEngine* fromHandle(jlong handle) {
    return reinterpret_cast<paperpages_vk::VulkanEngine*>(handle);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_render_VulkanNativeBridge_nativeCreate(JNIEnv* /*env*/, jclass /*clazz*/) {
    auto* engine = new paperpages_vk::VulkanEngine();
    if (!engine->initDevice()) {
        delete engine;
        return 0;
    }
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_example_render_VulkanNativeBridge_nativeDestroy(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr) return;
    engine->destroyDevice();
    delete engine;
}

JNIEXPORT jint JNICALL
Java_com_example_render_VulkanNativeBridge_nativeGetMaxTextureSize(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr) return 4096;
    return static_cast<jint>(engine->maxImageDimension2D);
}

JNIEXPORT jint JNICALL
Java_com_example_render_VulkanNativeBridge_nativeGetWhiteTextureId(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr) return 0;
    return static_cast<jint>(engine->whiteTextureId);
}

JNIEXPORT jboolean JNICALL
Java_com_example_render_VulkanNativeBridge_nativeOnSurfaceChanged(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jobject surface,
    jint width,
    jint height
) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr || surface == nullptr || width <= 0 || height <= 0) {
        return JNI_FALSE;
    }
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        return JNI_FALSE;
    }
    const bool ok = engine->bindSurface(window, static_cast<uint32_t>(width), static_cast<uint32_t>(height));
    ANativeWindow_release(window);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_render_VulkanNativeBridge_nativeOnSurfaceDestroyed(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr) return;
    engine->unbindSurface();
}

JNIEXPORT jint JNICALL
Java_com_example_render_VulkanNativeBridge_nativeUploadBitmap(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jobject bitmap
) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr || bitmap == nullptr) return 0;

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return 0;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0) {
        return 0;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == nullptr) {
        return 0;
    }

    int32_t texId = 0;
    const uint32_t tightStride = info.width * 4u;
    if (info.stride == tightStride) {
        texId = engine->uploadRgbaTexture(pixels, info.width, info.height);
    } else {
        std::vector<uint8_t> tightPixels(static_cast<size_t>(tightStride) * info.height);
        const auto* src = static_cast<const uint8_t*>(pixels);
        for (uint32_t y = 0; y < info.height; ++y) {
            std::memcpy(tightPixels.data() + static_cast<size_t>(y) * tightStride,
                        src + static_cast<size_t>(y) * info.stride,
                        tightStride);
        }
        texId = engine->uploadRgbaTexture(tightPixels.data(), info.width, info.height);
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return static_cast<jint>(texId);
}

JNIEXPORT void JNICALL
Java_com_example_render_VulkanNativeBridge_nativeDeleteTexture(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint textureId
) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr) return;
    engine->deleteTexture(static_cast<int32_t>(textureId));
}

JNIEXPORT void JNICALL
Java_com_example_render_VulkanNativeBridge_nativeClearUserTextures(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr) return;
    engine->clearUserTextures();
}

JNIEXPORT jint JNICALL
Java_com_example_render_VulkanNativeBridge_nativeBeginFrame(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jlong traceFrameId,
    jlong requestTimeNanos,
    jboolean useMotionBlur,
    jint mbWidth,
    jint mbHeight,
    jfloat clearR,
    jfloat clearG,
    jfloat clearB
) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr) return -1;
    const uint32_t w = mbWidth > 0 ? static_cast<uint32_t>(mbWidth) : 0u;
    const uint32_t h = mbHeight > 0 ? static_cast<uint32_t>(mbHeight) : 0u;
    return static_cast<jint>(engine->beginFrame(useMotionBlur == JNI_TRUE, w, h, clearR, clearG, clearB));
}

JNIEXPORT void JNICALL
Java_com_example_render_VulkanNativeBridge_nativeBeginMainPass(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jfloat clearR,
    jfloat clearG,
    jfloat clearB
) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr) return;
    engine->beginMainPass(clearR, clearG, clearB);
}

JNIEXPORT void JNICALL
Java_com_example_render_VulkanNativeBridge_nativeDrawQuad(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jint textureId,
    jint darkTextureId,
    jfloat darkMix,
    jfloatArray mvpArray,
    jfloat uvOffsetX,
    jfloat uvOffsetY,
    jfloat uvScaleX,
    jfloat uvScaleY,
    jfloat r,
    jfloat g,
    jfloat b,
    jfloat a
) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr || mvpArray == nullptr || env->GetArrayLength(mvpArray) < 16) return;
    jfloat mvp[16]{};
    env->GetFloatArrayRegion(mvpArray, 0, 16, mvp);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }
    engine->drawQuad(
        static_cast<int32_t>(textureId),
        static_cast<int32_t>(darkTextureId),
        darkMix,
        mvp,
        uvOffsetX,
        uvOffsetY,
        uvScaleX,
        uvScaleY,
        r,
        g,
        b,
        a
    );
}

JNIEXPORT void JNICALL
Java_com_example_render_VulkanNativeBridge_nativeDrawMotionBlurPass(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jfloatArray mvpArray,
    jint radius,
    jfloat sampleStep,
    jfloat direction
) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr || mvpArray == nullptr || env->GetArrayLength(mvpArray) < 16) return;
    jfloat mvp[16]{};
    env->GetFloatArrayRegion(mvpArray, 0, 16, mvp);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }
    engine->drawMotionBlurPass(mvp, static_cast<int32_t>(radius), sampleStep, direction);
}

JNIEXPORT jint JNICALL
Java_com_example_render_VulkanNativeBridge_nativeEndFrame(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr) return -1;
    return static_cast<jint>(engine->endFrame());
}

JNIEXPORT jlongArray JNICALL
Java_com_example_render_VulkanNativeBridge_nativeGetFrameTrace(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jlong afterRevision
) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr) return env->NewLongArray(0);

    const auto samples = engine->getFrameTraceUpdates(static_cast<uint64_t>(afterRevision));
    constexpr jsize kFieldsPerSample = 17;
    const jsize length = static_cast<jsize>(samples.size() * kFieldsPerSample);
    jlongArray result = env->NewLongArray(length);
    if (result == nullptr || length == 0) return result;

    std::vector<jlong> values;
    values.reserve(static_cast<size_t>(length));
    for (const auto& sample : samples) {
        values.push_back(static_cast<jlong>(sample.revision));
        values.push_back(static_cast<jlong>(sample.frameId));
        values.push_back(static_cast<jlong>(sample.requestTimeNs));
        values.push_back(static_cast<jlong>(sample.fenceWaitNs));
        values.push_back(static_cast<jlong>(sample.acquireNs));
        values.push_back(static_cast<jlong>(sample.beginCpuNs));
        values.push_back(static_cast<jlong>(sample.submitNs));
        values.push_back(static_cast<jlong>(sample.presentNs));
        values.push_back(static_cast<jlong>(sample.endCpuNs));
        values.push_back(static_cast<jlong>(sample.gpuFrameNs));
        values.push_back(static_cast<jlong>(sample.gpuOffscreenNs));
        values.push_back(static_cast<jlong>(sample.gpuMainNs));
        values.push_back(static_cast<jlong>(sample.presentId));
        values.push_back(static_cast<jlong>(sample.actualPresentTimeNs));
        values.push_back(static_cast<jlong>(sample.presentMarginNs));
        values.push_back(static_cast<jlong>(sample.offscreenTargetSetupNs));
        values.push_back(static_cast<jlong>(sample.flags));
    }
    env->SetLongArrayRegion(result, 0, length, values.data());
    return result;
}

} // extern "C"
