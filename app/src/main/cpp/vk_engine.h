#pragma once

#include <android/native_window.h>
#include <jni.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include <array>
#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

namespace paperpages_vk {

constexpr int kMaxFramesInFlight = 2;
constexpr uint32_t kMaxDescriptorsPerFrame = 64;
constexpr uint32_t kFrameTraceCapacity = 2048;
constexpr uint64_t kTraceValueUnavailable = UINT64_MAX;

struct FrameTraceSample {
    uint64_t revision = 0;
    uint64_t frameId = 0;
    uint64_t requestTimeNs = 0;
    uint64_t fenceWaitNs = kTraceValueUnavailable;
    uint64_t acquireNs = kTraceValueUnavailable;
    uint64_t beginCpuNs = kTraceValueUnavailable;
    uint64_t submitNs = kTraceValueUnavailable;
    uint64_t presentNs = kTraceValueUnavailable;
    uint64_t endCpuNs = kTraceValueUnavailable;
    uint64_t gpuFrameNs = kTraceValueUnavailable;
    uint64_t gpuOffscreenNs = kTraceValueUnavailable;
    uint64_t gpuMainNs = kTraceValueUnavailable;
    uint64_t presentId = 0;
    uint64_t actualPresentTimeNs = 0;
    uint64_t presentMarginNs = kTraceValueUnavailable;
    uint64_t offscreenTargetSetupNs = 0;
    uint64_t flags = 0;
};

struct WallpaperPushConstants {
    float mvp[16];
    float color[4];
    float uvOffset[2];
    float uvScale[2];
    float darkMix;
    float pad[3];
};

struct MotionBlurPushConstants {
    float mvp[16];
    float params[4]; // x=radius, y=sampleStep, z=direction, w=0
};

struct GpuTexture {
    int32_t id = 0;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    uint32_t width = 0;
    uint32_t height = 0;
};

struct TextureUploadRegion {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceSize stagingOffset = 0;
    uint32_t width = 0;
    uint32_t height = 0;
};

struct PendingCpuTextureUpload {
    int32_t textureId = 0;
    VkImage image = VK_NULL_HANDLE;
    uint32_t width = 0;
    uint32_t height = 0;
    std::vector<uint8_t> pixels;
};

struct FrameSync {
    uint64_t submissionSerial = 0;
    uint32_t traceSlotIndex = UINT32_MAX;
    std::unordered_map<uint64_t, VkDescriptorSet> compositeDescriptors;
    VkDescriptorSet blurDescriptor = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    VkCommandBuffer uploadCommandBuffer = VK_NULL_HANDLE;
    VkBuffer uploadStagingBuffer = VK_NULL_HANDLE;
    VkDeviceMemory uploadStagingMemory = VK_NULL_HANDLE;
    VkDeviceSize uploadStagingCapacity = 0;
    VkDeviceSize uploadStagingUsed = 0;
    std::vector<TextureUploadRegion> pendingUploads;
    VkSemaphore imageAvailable = VK_NULL_HANDLE;
    VkSemaphore renderFinished = VK_NULL_HANDLE;
    VkFence inFlight = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkQueryPool timestampQueryPool = VK_NULL_HANDLE;
    bool timestampPending = false;
    bool timestampHasOffscreenPass = false;
};

struct DeferredTexture {
    GpuTexture texture;
    uint64_t retireAfterSerial = 0;
};

struct VulkanEngine {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue graphicsQueue = VK_NULL_HANDLE;
    uint32_t graphicsQueueFamily = 0;
    VkPhysicalDeviceMemoryProperties memoryProps{};
    uint32_t maxImageDimension2D = 4096;
    uint32_t timestampValidBits = 0;
    double timestampPeriodNs = 0.0;
    bool gpuTimestampsEnabled = false;
    bool displayTimingExtensionEnabled = false;
    uint64_t displayRefreshDurationNs = 0;
    uint32_t nextPresentId = 1;
    uint64_t lastActualPresentTimeNs = 0;
    PFN_vkGetRefreshCycleDurationGOOGLE pfnGetRefreshCycleDurationGOOGLE = nullptr;
    PFN_vkGetPastPresentationTimingGOOGLE pfnGetPastPresentationTimingGOOGLE = nullptr;

    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkPipelineCache pipelineCache = VK_NULL_HANDLE;

    VkDescriptorSetLayout compositeSetLayout = VK_NULL_HANDLE;
    VkDescriptorSetLayout blurSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout compositePipelineLayout = VK_NULL_HANDLE;
    VkPipelineLayout blurPipelineLayout = VK_NULL_HANDLE;

    VkRenderPass swapchainRenderPass = VK_NULL_HANDLE;
    VkRenderPass offscreenRenderPass = VK_NULL_HANDLE;
    VkPipeline compositePipeline = VK_NULL_HANDLE;
    VkPipeline offscreenCompositePipeline = VK_NULL_HANDLE;
    VkPipeline blurPipeline = VK_NULL_HANDLE;

    VkSampler linearSampler = VK_NULL_HANDLE;
    VkBuffer quadBuffer = VK_NULL_HANDLE;
    VkDeviceMemory quadMemory = VK_NULL_HANDLE;

    FrameSync frames[kMaxFramesInFlight]{};
    uint32_t currentFrame = 0;
    uint64_t submittedFrameSerial = 0;
    uint64_t completedFrameSerial = 0;
    std::vector<DeferredTexture> deferredTextures;
    std::vector<PendingCpuTextureUpload> pendingCpuTextureUploads;
    std::array<FrameTraceSample, kFrameTraceCapacity> frameTraceSamples{};
    uint32_t nextFrameTraceSlot = 0;
    uint64_t nextFrameTraceRevision = 1;
    uint32_t timingFenceWaitCount = 0;
    uint32_t timingAcquireCount = 0;
    uint32_t timingSubmitCount = 0;
    uint32_t timingPresentCount = 0;
    uint32_t timingUploadCount = 0;
    uint32_t timingGpuFrameCount = 0;
    uint32_t timingGpuOffscreenCount = 0;
    uint32_t timingGpuMainCount = 0;
    uint32_t timingDisplayIntervalCount = 0;
    uint32_t timingPresentMarginCount = 0;
    uint64_t timingFenceWaitNs[120]{};
    uint64_t timingAcquireNs[120]{};
    uint64_t timingSubmitNs[120]{};
    uint64_t timingPresentNs[120]{};
    uint64_t timingUploadNs[120]{};
    uint64_t timingGpuFrameNs[120]{};
    uint64_t timingGpuOffscreenNs[120]{};
    uint64_t timingGpuMainNs[120]{};
    uint64_t timingDisplayIntervalNs[120]{};
    uint64_t timingPresentMarginNs[120]{};

    // Window / swapchain (recreated on surface bind, independent of VkDevice & textures)
    ANativeWindow* window = nullptr;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkFormat swapchainFormat = VK_FORMAT_R8G8B8A8_UNORM;
    VkExtent2D swapchainExtent{0, 0};
    std::vector<VkImage> swapchainImages;
    std::vector<VkImageView> swapchainViews;
    std::vector<VkFramebuffer> swapchainFramebuffers;

    // Offscreen motion blur target
    VkImage blurImage = VK_NULL_HANDLE;
    VkDeviceMemory blurMemory = VK_NULL_HANDLE;
    VkImageView blurView = VK_NULL_HANDLE;
    VkFramebuffer blurFramebuffer = VK_NULL_HANDLE;
    uint32_t blurWidth = 0;
    uint32_t blurHeight = 0;

    // Persistent texture registry (survives swapchain destruction)
    int32_t nextTextureId = 1;
    int32_t whiteTextureId = 0;
    std::unordered_map<int32_t, GpuTexture> textures;

    // Active frame recording state
    bool recordingFrame = false;
    bool usingOffscreenPass = false;
    bool swapchainOutOfDate = false;
    uint32_t activeImageIndex = 0;
    VkExtent2D activeViewport{0, 0};

    bool initDevice();
    void destroyDevice();

    bool bindSurface(ANativeWindow* newWindow, uint32_t width, uint32_t height);
    void unbindSurface();

    int32_t uploadRgbaTexture(const void* pixels, uint32_t width, uint32_t height);
    void deleteTexture(int32_t textureId);
    void clearUserTextures();

    int32_t beginFrame(
        uint64_t traceFrameId,
        uint64_t requestTimeNs,
        bool useMotionBlur,
        uint32_t mbWidth,
        uint32_t mbHeight,
        float clearR,
        float clearG,
        float clearB
    );
    void publishFrameTrace(FrameTraceSample& sample);
    std::vector<FrameTraceSample> getFrameTraceUpdates(uint64_t afterRevision) const;
    void beginMainPass(float clearR, float clearG, float clearB);
    void drawQuad(
        int32_t textureId,
        int32_t darkTextureId,
        float darkMix,
        const float* mvp,
        float uvOffsetX,
        float uvOffsetY,
        float uvScaleX,
        float uvScaleY,
        float r,
        float g,
        float b,
        float a
    );
    void drawMotionBlurPass(const float* mvp, int32_t radius, float sampleStep, float direction);
    int32_t endFrame();

    uint32_t findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties) const;
    bool createPipelinesForFormat(VkFormat format);
    void destroySwapchainResources();
    void destroyOffscreenTarget();
    bool ensureOffscreenTarget(uint32_t width, uint32_t height);
    bool ensureFrameStagingBuffer(FrameSync& frame, VkDeviceSize size);
    bool stagePendingCpuUploads(FrameSync& frame);
    bool recordPendingTextureUploads(FrameSync& frame);
    void collectDeferredTextures();
    void recordTimingSample(const char* label, uint64_t elapsedNs);
    void collectGpuTimestamps(FrameSync& frame);
    void collectDisplayTimingSamples();
    void destroyTextureResources(const GpuTexture& texture);
    VkFormat preferredTextureFormat() const;
    bool isSrgbSwapchain() const;
    VkShaderModule createShaderModule(const uint32_t* code, size_t sizeBytes) const;
    void transitionImageLayout(
        VkCommandBuffer cmd,
        VkImage image,
        VkImageLayout oldLayout,
        VkImageLayout newLayout,
        VkPipelineStageFlags srcStage,
        VkPipelineStageFlags dstStage,
        VkAccessFlags srcAccess,
        VkAccessFlags dstAccess
    );
};

} // namespace paperpages_vk
