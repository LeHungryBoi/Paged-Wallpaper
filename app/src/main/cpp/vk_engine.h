#pragma once

#include <android/native_window.h>
#include <jni.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include <cstdint>
#include <unordered_map>
#include <vector>

namespace paperpages_vk {

constexpr int kMaxFramesInFlight = 2;
constexpr uint32_t kMaxDescriptorsPerFrame = 64;

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

struct FrameSync {
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    VkSemaphore imageAvailable = VK_NULL_HANDLE;
    VkSemaphore renderFinished = VK_NULL_HANDLE;
    VkFence inFlight = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
};

struct VulkanEngine {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue graphicsQueue = VK_NULL_HANDLE;
    uint32_t graphicsQueueFamily = 0;
    VkPhysicalDeviceMemoryProperties memoryProps{};
    uint32_t maxImageDimension2D = 4096;

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

    VkBuffer stagingBuffer = VK_NULL_HANDLE;
    VkDeviceMemory stagingMemory = VK_NULL_HANDLE;
    VkDeviceSize stagingCapacity = 0;

    FrameSync frames[kMaxFramesInFlight]{};
    uint32_t currentFrame = 0;

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

    int32_t beginFrame(bool useMotionBlur, uint32_t mbWidth, uint32_t mbHeight, float clearR, float clearG, float clearB);
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
    bool ensureStagingBuffer(VkDeviceSize size);
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
