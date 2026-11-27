#include "vk_engine.h"

#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cstring>

#define LOG_TAG "PaperPagesVK"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace paperpages_vk {

namespace {
using SteadyClock = std::chrono::steady_clock;
uint64_t elapsedNs(SteadyClock::time_point start) {
    return static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(SteadyClock::now() - start).count()
    );
}
}

bool VulkanEngine::isSrgbSwapchain() const {
    return swapchainFormat == VK_FORMAT_R8G8B8A8_SRGB ||
           swapchainFormat == VK_FORMAT_B8G8R8A8_SRGB;
}

VkFormat VulkanEngine::preferredTextureFormat() const {
    return isSrgbSwapchain() ? VK_FORMAT_R8G8B8A8_SRGB : VK_FORMAT_R8G8B8A8_UNORM;
}

void VulkanEngine::destroySwapchainResources() {
    for (VkFramebuffer fb : swapchainFramebuffers) {
        if (fb != VK_NULL_HANDLE) vkDestroyFramebuffer(device, fb, nullptr);
    }
    swapchainFramebuffers.clear();
    for (VkImageView v : swapchainViews) {
        if (v != VK_NULL_HANDLE) vkDestroyImageView(device, v, nullptr);
    }
    swapchainViews.clear();
    swapchainImages.clear();
    if (swapchain != VK_NULL_HANDLE) {
        vkDestroySwapchainKHR(device, swapchain, nullptr);
        swapchain = VK_NULL_HANDLE;
    }
}

void VulkanEngine::destroyOffscreenTarget() {
    if (device == VK_NULL_HANDLE) return;
    if (blurFramebuffer) vkDestroyFramebuffer(device, blurFramebuffer, nullptr);
    if (blurView) vkDestroyImageView(device, blurView, nullptr);
    if (blurImage) vkDestroyImage(device, blurImage, nullptr);
    if (blurMemory) vkFreeMemory(device, blurMemory, nullptr);
    blurFramebuffer = VK_NULL_HANDLE;
    blurView = VK_NULL_HANDLE;
    blurImage = VK_NULL_HANDLE;
    blurMemory = VK_NULL_HANDLE;
    blurWidth = 0;
    blurHeight = 0;
}

void VulkanEngine::unbindSurface() {
    if (device != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(device);
        destroySwapchainResources();
    }
    if (surface != VK_NULL_HANDLE && instance != VK_NULL_HANDLE) {
        vkDestroySurfaceKHR(instance, surface, nullptr);
        surface = VK_NULL_HANDLE;
    }
    if (window != nullptr) {
        ANativeWindow_release(window);
        window = nullptr;
    }
    swapchainExtent = {0, 0};
    recordingFrame = false;
}

bool VulkanEngine::bindSurface(ANativeWindow* newWindow, uint32_t width, uint32_t height) {
    if (device == VK_NULL_HANDLE || newWindow == nullptr || width == 0 || height == 0) {
        return false;
    }

    const bool sameWindow = (window == newWindow);
    if (!sameWindow) {
        unbindSurface();
        ANativeWindow_acquire(newWindow);
        window = newWindow;

        VkAndroidSurfaceCreateInfoKHR surfInfo{};
        surfInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
        surfInfo.window = window;
        if (vkCreateAndroidSurfaceKHR(instance, &surfInfo, nullptr, &surface) != VK_SUCCESS) {
            LOGE("vkCreateAndroidSurfaceKHR failed");
            unbindSurface();
            return false;
        }
        VkBool32 presentSupported = VK_FALSE;
        vkGetPhysicalDeviceSurfaceSupportKHR(physicalDevice, graphicsQueueFamily, surface, &presentSupported);
        if (!presentSupported) {
            LOGE("Graphics queue does not support surface present");
            unbindSurface();
            return false;
        }
    } else if (swapchain != VK_NULL_HANDLE && !swapchainOutOfDate &&
               swapchainExtent.width == width && swapchainExtent.height == height) {
        return true;
    } else {
        vkDeviceWaitIdle(device);
    }

    VkSurfaceCapabilitiesKHR caps{};
    if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, &caps) != VK_SUCCESS) {
        return false;
    }

    uint32_t fmtCount = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, &fmtCount, nullptr);
    if (fmtCount == 0) return false;
    std::vector<VkSurfaceFormatKHR> formats(fmtCount);
    vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, &fmtCount, formats.data());

    VkSurfaceFormatKHR chosen = formats[0];
    for (const auto& f : formats) {
        if (f.format == VK_FORMAT_R8G8B8A8_UNORM &&
            f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
            chosen = f;
            break;
        }
    }
    if (chosen.format != VK_FORMAT_R8G8B8A8_UNORM) {
        for (const auto& f : formats) {
            if (f.format == VK_FORMAT_R8G8B8A8_UNORM || f.format == VK_FORMAT_R8G8B8A8_SRGB) {
                chosen = f;
                break;
            }
        }
    }

    swapchainFormat = chosen.format;
    if (!createPipelinesForFormat(swapchainFormat)) {
        LOGE("createPipelinesForFormat failed");
        return false;
    }

    VkExtent2D extent{};
    if (caps.currentExtent.width != UINT32_MAX && caps.currentExtent.height != UINT32_MAX) {
        extent = caps.currentExtent;
    } else {
        extent.width = std::clamp(width, caps.minImageExtent.width, caps.maxImageExtent.width);
        extent.height = std::clamp(height, caps.minImageExtent.height, caps.maxImageExtent.height);
    }
    if (extent.width == 0 || extent.height == 0) return false;

    uint32_t imageCount = caps.minImageCount + 1;
    if (caps.maxImageCount > 0 && imageCount > caps.maxImageCount) {
        imageCount = caps.maxImageCount;
    }

    VkCompositeAlphaFlagBitsKHR compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    if (!(caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)) {
        compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    }

    VkSwapchainKHR oldSwapchain = swapchain;
    VkSwapchainCreateInfoKHR scInfo{};
    scInfo.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    scInfo.surface = surface;
    scInfo.minImageCount = imageCount;
    scInfo.imageFormat = chosen.format;
    scInfo.imageColorSpace = chosen.colorSpace;
    scInfo.imageExtent = extent;
    scInfo.imageArrayLayers = 1;
    scInfo.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    scInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    scInfo.preTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    if ((caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) == 0) {
        scInfo.preTransform = caps.currentTransform;
    }
    scInfo.compositeAlpha = compositeAlpha;
    scInfo.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    scInfo.clipped = VK_TRUE;
    scInfo.oldSwapchain = oldSwapchain;

    VkSwapchainKHR newSwapchain = VK_NULL_HANDLE;
    if (vkCreateSwapchainKHR(device, &scInfo, nullptr, &newSwapchain) != VK_SUCCESS) {
        LOGE("vkCreateSwapchainKHR failed");
        return false;
    }

    if (oldSwapchain != VK_NULL_HANDLE) {
        for (VkFramebuffer fb : swapchainFramebuffers) vkDestroyFramebuffer(device, fb, nullptr);
        for (VkImageView v : swapchainViews) vkDestroyImageView(device, v, nullptr);
        swapchainFramebuffers.clear();
        swapchainViews.clear();
        vkDestroySwapchainKHR(device, oldSwapchain, nullptr);
    }

    swapchain = newSwapchain;
    swapchainExtent = extent;
    swapchainOutOfDate = false;

    uint32_t scImgCount = 0;
    vkGetSwapchainImagesKHR(device, swapchain, &scImgCount, nullptr);
    swapchainImages.resize(scImgCount);
    vkGetSwapchainImagesKHR(device, swapchain, &scImgCount, swapchainImages.data());
    swapchainViews.resize(scImgCount, VK_NULL_HANDLE);
    swapchainFramebuffers.resize(scImgCount, VK_NULL_HANDLE);

    for (uint32_t i = 0; i < scImgCount; ++i) {
        VkImageViewCreateInfo viewInfo{};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = swapchainImages[i];
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = swapchainFormat;
        viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        if (vkCreateImageView(device, &viewInfo, nullptr, &swapchainViews[i]) != VK_SUCCESS) {
            return false;
        }
        VkFramebufferCreateInfo fbInfo{};
        fbInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        fbInfo.renderPass = swapchainRenderPass;
        fbInfo.attachmentCount = 1;
        fbInfo.pAttachments = &swapchainViews[i];
        fbInfo.width = extent.width;
        fbInfo.height = extent.height;
        fbInfo.layers = 1;
        if (vkCreateFramebuffer(device, &fbInfo, nullptr, &swapchainFramebuffers[i]) != VK_SUCCESS) {
            return false;
        }
    }
    return true;
}

bool VulkanEngine::ensureStagingBuffer(VkDeviceSize size) {
    if (stagingBuffer != VK_NULL_HANDLE && stagingCapacity >= size) return true;
    // uploadRgbaTexture waits for its transfer fence before returning, so the
    // shared staging allocation is never referenced by an in-flight transfer
    // when this function grows it. Rendering submissions do not use it.
    if (stagingBuffer) vkDestroyBuffer(device, stagingBuffer, nullptr);
    if (stagingMemory) vkFreeMemory(device, stagingMemory, nullptr);
    stagingBuffer = VK_NULL_HANDLE;
    stagingMemory = VK_NULL_HANDLE;
    stagingCapacity = 0;

    VkBufferCreateInfo bufInfo{};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = size;
    bufInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(device, &bufInfo, nullptr, &stagingBuffer) != VK_SUCCESS) return false;

    VkMemoryRequirements req{};
    vkGetBufferMemoryRequirements(device, stagingBuffer, &req);
    uint32_t memIdx = findMemoryType(
        req.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
    );
    if (memIdx == UINT32_MAX) return false;
    VkMemoryAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = memIdx;
    if (vkAllocateMemory(device, &alloc, nullptr, &stagingMemory) != VK_SUCCESS ||
        vkBindBufferMemory(device, stagingBuffer, stagingMemory, 0) != VK_SUCCESS) {
        return false;
    }
    stagingCapacity = size;
    return true;
}

void VulkanEngine::transitionImageLayout(
    VkCommandBuffer cmd,
    VkImage image,
    VkImageLayout oldLayout,
    VkImageLayout newLayout,
    VkPipelineStageFlags srcStage,
    VkPipelineStageFlags dstStage,
    VkAccessFlags srcAccess,
    VkAccessFlags dstAccess
) {
    VkImageMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.oldLayout = oldLayout;
    barrier.newLayout = newLayout;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    barrier.srcAccessMask = srcAccess;
    barrier.dstAccessMask = dstAccess;
    vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 1, &barrier);
}

int32_t VulkanEngine::uploadRgbaTexture(const void* pixels, uint32_t width, uint32_t height) {
    const auto uploadStartedAt = SteadyClock::now();
    if (device == VK_NULL_HANDLE || pixels == nullptr || width == 0 || height == 0) return 0;
    const VkDeviceSize byteSize = static_cast<VkDeviceSize>(width) * height * 4;
    if (!ensureStagingBuffer(byteSize)) return 0;

    void* mapped = nullptr;
    if (vkMapMemory(device, stagingMemory, 0, byteSize, 0, &mapped) != VK_SUCCESS) return 0;
    std::memcpy(mapped, pixels, byteSize);
    vkUnmapMemory(device, stagingMemory);

    VkFormat fmt = preferredTextureFormat();
    VkImageCreateInfo imgInfo{};
    imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imgInfo.imageType = VK_IMAGE_TYPE_2D;
    imgInfo.format = fmt;
    imgInfo.extent = {width, height, 1};
    imgInfo.mipLevels = 1;
    imgInfo.arrayLayers = 1;
    imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imgInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    imgInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    GpuTexture tex{};
    if (vkCreateImage(device, &imgInfo, nullptr, &tex.image) != VK_SUCCESS) return 0;

    VkMemoryRequirements memReq{};
    vkGetImageMemoryRequirements(device, tex.image, &memReq);
    uint32_t memIdx = findMemoryType(memReq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memIdx == UINT32_MAX) {
        vkDestroyImage(device, tex.image, nullptr);
        return 0;
    }
    VkMemoryAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.allocationSize = memReq.size;
    alloc.memoryTypeIndex = memIdx;
    if (vkAllocateMemory(device, &alloc, nullptr, &tex.memory) != VK_SUCCESS ||
        vkBindImageMemory(device, tex.image, tex.memory, 0) != VK_SUCCESS) {
        if (tex.memory) vkFreeMemory(device, tex.memory, nullptr);
        vkDestroyImage(device, tex.image, nullptr);
        return 0;
    }

    VkCommandBufferAllocateInfo cmdAlloc{};
    cmdAlloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdAlloc.commandPool = commandPool;
    cmdAlloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdAlloc.commandBufferCount = 1;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    vkAllocateCommandBuffers(device, &cmdAlloc, &cmd);

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd, &beginInfo);

    transitionImageLayout(
        cmd, tex.image,
        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
        0, VK_ACCESS_TRANSFER_WRITE_BIT
    );

    VkBufferImageCopy region{};
    region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.imageExtent = {width, height, 1};
    vkCmdCopyBufferToImage(cmd, stagingBuffer, tex.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);

    transitionImageLayout(
        cmd, tex.image,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
        VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT
    );
    vkEndCommandBuffer(cmd);

    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    VkFence fence = VK_NULL_HANDLE;
    VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, nullptr, 0};
    vkCreateFence(device, &fenceInfo, nullptr, &fence);
    vkQueueSubmit(graphicsQueue, 1, &submit, fence);
    vkWaitForFences(device, 1, &fence, VK_TRUE, UINT64_MAX);
    vkDestroyFence(device, fence, nullptr);
    vkFreeCommandBuffers(device, commandPool, 1, &cmd);

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = tex.image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = fmt;
    viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (vkCreateImageView(device, &viewInfo, nullptr, &tex.view) != VK_SUCCESS) {
        vkFreeMemory(device, tex.memory, nullptr);
        vkDestroyImage(device, tex.image, nullptr);
        return 0;
    }

    tex.id = nextTextureId++;
    tex.width = width;
    tex.height = height;
    textures.emplace(tex.id, tex);
    recordTimingSample("upload", elapsedNs(uploadStartedAt));
    return tex.id;
}

void VulkanEngine::recordTimingSample(const char* label, uint64_t elapsed) {
    uint64_t* samples = nullptr;
    uint32_t* count = nullptr;
    if (std::strcmp(label, "fence_wait") == 0) { samples = timingFenceWaitNs; count = &timingFenceWaitCount; }
    else if (std::strcmp(label, "acquire") == 0) { samples = timingAcquireNs; count = &timingAcquireCount; }
    else if (std::strcmp(label, "submit") == 0) { samples = timingSubmitNs; count = &timingSubmitCount; }
    else if (std::strcmp(label, "present") == 0) { samples = timingPresentNs; count = &timingPresentCount; }
    else if (std::strcmp(label, "upload") == 0) { samples = timingUploadNs; count = &timingUploadCount; }
    else if (std::strcmp(label, "gpu_frame") == 0) { samples = timingGpuFrameNs; count = &timingGpuFrameCount; }
    else if (std::strcmp(label, "gpu_offscreen") == 0) { samples = timingGpuOffscreenNs; count = &timingGpuOffscreenCount; }
    else if (std::strcmp(label, "gpu_main") == 0) { samples = timingGpuMainNs; count = &timingGpuMainCount; }
    if (samples == nullptr || count == nullptr) return;

    samples[*count] = elapsed;
    ++(*count);
    if (*count < 120) return;

    uint64_t sorted[120];
    std::copy(samples, samples + 120, sorted);
    std::sort(sorted, sorted + 120);
    LOGI("vk_timing %s_ms p50=%.3f p95=%.3f p99=%.3f",
         label,
         sorted[59] / 1e6,
         sorted[113] / 1e6,
         sorted[118] / 1e6);
    *count = 0;
}

void VulkanEngine::collectGpuTimestamps(FrameSync& frame) {
    if (!gpuTimestampsEnabled ||
        frame.timestampQueryPool == VK_NULL_HANDLE ||
        !frame.timestampPending) {
        return;
    }

    uint64_t timestamps[3]{};
    const VkResult result = vkGetQueryPoolResults(
        device,
        frame.timestampQueryPool,
        0,
        3,
        sizeof(timestamps),
        timestamps,
        sizeof(uint64_t),
        VK_QUERY_RESULT_64_BIT
    );
    frame.timestampPending = false;
    if (result != VK_SUCCESS) {
        if (result != VK_NOT_READY) {
            LOGE("vkGetQueryPoolResults failed: %d", result);
        }
        return;
    }

    const uint64_t timestampMask = timestampValidBits >= 64
        ? UINT64_MAX
        : ((uint64_t{1} << timestampValidBits) - 1u);
    const auto elapsedTicks = [timestampMask](uint64_t start, uint64_t end) {
        return (end - start) & timestampMask;
    };
    const auto ticksToNs = [this](uint64_t ticks) {
        return static_cast<uint64_t>(static_cast<double>(ticks) * timestampPeriodNs);
    };

    recordTimingSample("gpu_frame", ticksToNs(elapsedTicks(timestamps[0], timestamps[2])));
    if (frame.timestampHasOffscreenPass) {
        recordTimingSample("gpu_offscreen", ticksToNs(elapsedTicks(timestamps[0], timestamps[1])));
        recordTimingSample("gpu_main", ticksToNs(elapsedTicks(timestamps[1], timestamps[2])));
    } else {
        recordTimingSample("gpu_main", ticksToNs(elapsedTicks(timestamps[0], timestamps[2])));
    }
}

void VulkanEngine::destroyTextureResources(const GpuTexture& texture) {
    if (texture.view) vkDestroyImageView(device, texture.view, nullptr);
    if (texture.image) vkDestroyImage(device, texture.image, nullptr);
    if (texture.memory) vkFreeMemory(device, texture.memory, nullptr);
}

void VulkanEngine::collectDeferredTextures() {
    auto it = deferredTextures.begin();
    while (it != deferredTextures.end()) {
        if (it->retireAfterSerial <= completedFrameSerial) {
            destroyTextureResources(it->texture);
            it = deferredTextures.erase(it);
        } else {
            ++it;
        }
    }
}

void VulkanEngine::deleteTexture(int32_t textureId) {
    if (textureId == 0 || textureId == whiteTextureId || device == VK_NULL_HANDLE) return;
    auto it = textures.find(textureId);
    if (it == textures.end()) return;
    const uint64_t retireAfter = submittedFrameSerial + (recordingFrame ? 1u : 0u);
    deferredTextures.push_back({it->second, retireAfter});
    textures.erase(it);
}

void VulkanEngine::clearUserTextures() {
    if (device == VK_NULL_HANDLE) return;
    const uint64_t retireAfter = submittedFrameSerial + (recordingFrame ? 1u : 0u);
    for (auto it = textures.begin(); it != textures.end();) {
        if (it->first == whiteTextureId) {
            ++it;
        } else {
            deferredTextures.push_back({it->second, retireAfter});
            it = textures.erase(it);
        }
    }
}

bool VulkanEngine::ensureOffscreenTarget(uint32_t width, uint32_t height) {
    if (blurImage != VK_NULL_HANDLE && blurWidth == width && blurHeight == height) return true;
    vkDeviceWaitIdle(device);
    destroyOffscreenTarget();

    VkImageCreateInfo imgInfo{};
    imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imgInfo.imageType = VK_IMAGE_TYPE_2D;
    imgInfo.format = swapchainFormat;
    imgInfo.extent = {width, height, 1};
    imgInfo.mipLevels = 1;
    imgInfo.arrayLayers = 1;
    imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imgInfo.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    imgInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateImage(device, &imgInfo, nullptr, &blurImage) != VK_SUCCESS) return false;

    VkMemoryRequirements req{};
    vkGetImageMemoryRequirements(device, blurImage, &req);
    uint32_t memIdx = findMemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memIdx == UINT32_MAX) return false;
    VkMemoryAllocateInfo alloc{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, nullptr, req.size, memIdx};
    if (vkAllocateMemory(device, &alloc, nullptr, &blurMemory) != VK_SUCCESS ||
        vkBindImageMemory(device, blurImage, blurMemory, 0) != VK_SUCCESS) {
        return false;
    }

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = blurImage;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = swapchainFormat;
    viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (vkCreateImageView(device, &viewInfo, nullptr, &blurView) != VK_SUCCESS) return false;

    VkFramebufferCreateInfo fbInfo{};
    fbInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
    fbInfo.renderPass = offscreenRenderPass;
    fbInfo.attachmentCount = 1;
    fbInfo.pAttachments = &blurView;
    fbInfo.width = width;
    fbInfo.height = height;
    fbInfo.layers = 1;
    if (vkCreateFramebuffer(device, &fbInfo, nullptr, &blurFramebuffer) != VK_SUCCESS) return false;

    blurWidth = width;
    blurHeight = height;
    return true;
}

int32_t VulkanEngine::beginFrame(
    bool useMotionBlur,
    uint32_t mbWidth,
    uint32_t mbHeight,
    float clearR,
    float clearG,
    float clearB
) {
    if (device == VK_NULL_HANDLE || swapchain == VK_NULL_HANDLE) return -1;
    if (swapchainOutOfDate) return 1;

    FrameSync& frame = frames[currentFrame];
    const auto fenceWaitStartedAt = SteadyClock::now();
    vkWaitForFences(device, 1, &frame.inFlight, VK_TRUE, UINT64_MAX);
    recordTimingSample("fence_wait", elapsedNs(fenceWaitStartedAt));
    // The frame fence guarantees these timestamp results are ready. Never wait
    // on query availability separately; a missing result is simply skipped.
    collectGpuTimestamps(frame);

    const auto acquireStartedAt = SteadyClock::now();
    VkResult acq = vkAcquireNextImageKHR(
        device, swapchain, UINT64_MAX, frame.imageAvailable, VK_NULL_HANDLE, &activeImageIndex
    );
    recordTimingSample("acquire", elapsedNs(acquireStartedAt));
    if (acq == VK_ERROR_OUT_OF_DATE_KHR) {
        swapchainOutOfDate = true;
        return 1;
    }
    if (acq != VK_SUCCESS && acq != VK_SUBOPTIMAL_KHR) {
        return -1;
    }

    completedFrameSerial = std::max(completedFrameSerial, frame.submissionSerial);
    collectDeferredTextures();
    vkResetDescriptorPool(device, frame.descriptorPool, 0);
    frame.compositeDescriptors.clear();
    frame.blurDescriptor = VK_NULL_HANDLE;
    vkResetCommandBuffer(frame.commandBuffer, 0);

    if (useMotionBlur && mbWidth > 0 && mbHeight > 0) {
        if (!ensureOffscreenTarget(mbWidth, mbHeight)) return -1;
    }

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(frame.commandBuffer, &beginInfo) != VK_SUCCESS) return -1;

    recordingFrame = true;
    usingOffscreenPass = useMotionBlur && blurFramebuffer != VK_NULL_HANDLE;

    if (gpuTimestampsEnabled && frame.timestampQueryPool != VK_NULL_HANDLE) {
        vkCmdResetQueryPool(frame.commandBuffer, frame.timestampQueryPool, 0, 3);
        vkCmdWriteTimestamp(
            frame.commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, frame.timestampQueryPool, 0
        );
        frame.timestampHasOffscreenPass = usingOffscreenPass;
        // Without an offscreen pass, use the start marker as the main-pass
        // baseline so gpu_main reports the whole render workload.
        if (!usingOffscreenPass) {
            vkCmdWriteTimestamp(
                frame.commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, frame.timestampQueryPool, 1
            );
        }
    }

    VkClearValue clearVal{};
    clearVal.color = {{clearR, clearG, clearB, 1.0f}};

    VkRenderPassBeginInfo rpBegin{};
    rpBegin.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    if (usingOffscreenPass) {
        rpBegin.renderPass = offscreenRenderPass;
        rpBegin.framebuffer = blurFramebuffer;
        rpBegin.renderArea = {{0, 0}, {blurWidth, blurHeight}};
        activeViewport = {blurWidth, blurHeight};
    } else {
        rpBegin.renderPass = swapchainRenderPass;
        rpBegin.framebuffer = swapchainFramebuffers[activeImageIndex];
        rpBegin.renderArea = {{0, 0}, swapchainExtent};
        activeViewport = swapchainExtent;
    }
    rpBegin.clearValueCount = 1;
    rpBegin.pClearValues = &clearVal;

    vkCmdBeginRenderPass(frame.commandBuffer, &rpBegin, VK_SUBPASS_CONTENTS_INLINE);

    VkViewport vp{0.0f, 0.0f, static_cast<float>(activeViewport.width), static_cast<float>(activeViewport.height), 0.0f, 1.0f};
    VkRect2D sc{{0, 0}, activeViewport};
    vkCmdSetViewport(frame.commandBuffer, 0, 1, &vp);
    vkCmdSetScissor(frame.commandBuffer, 0, 1, &sc);

    VkDeviceSize offset = 0;
    vkCmdBindVertexBuffers(frame.commandBuffer, 0, 1, &quadBuffer, &offset);
    vkCmdBindPipeline(
        frame.commandBuffer,
        VK_PIPELINE_BIND_POINT_GRAPHICS,
        usingOffscreenPass ? offscreenCompositePipeline : compositePipeline
    );
    return 0;
}

void VulkanEngine::beginMainPass(float clearR, float clearG, float clearB) {
    if (!recordingFrame || !usingOffscreenPass) return;
    VkCommandBuffer cmd = frames[currentFrame].commandBuffer;
    vkCmdEndRenderPass(cmd);

    transitionImageLayout(
        cmd, blurImage,
        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
        VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT
    );

    if (gpuTimestampsEnabled && frames[currentFrame].timestampQueryPool != VK_NULL_HANDLE) {
        vkCmdWriteTimestamp(
            cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, frames[currentFrame].timestampQueryPool, 1
        );
    }

    VkClearValue clearVal{};
    clearVal.color = {{clearR, clearG, clearB, 1.0f}};
    VkRenderPassBeginInfo rpBegin{};
    rpBegin.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    rpBegin.renderPass = swapchainRenderPass;
    rpBegin.framebuffer = swapchainFramebuffers[activeImageIndex];
    rpBegin.renderArea = {{0, 0}, swapchainExtent};
    rpBegin.clearValueCount = 1;
    rpBegin.pClearValues = &clearVal;
    vkCmdBeginRenderPass(cmd, &rpBegin, VK_SUBPASS_CONTENTS_INLINE);

    activeViewport = swapchainExtent;
    usingOffscreenPass = false;
    VkViewport vp{0.0f, 0.0f, static_cast<float>(swapchainExtent.width), static_cast<float>(swapchainExtent.height), 0.0f, 1.0f};
    VkRect2D sc{{0, 0}, swapchainExtent};
    vkCmdSetViewport(cmd, 0, 1, &vp);
    vkCmdSetScissor(cmd, 0, 1, &sc);
    VkDeviceSize offset = 0;
    vkCmdBindVertexBuffers(cmd, 0, 1, &quadBuffer, &offset);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, compositePipeline);
}

void VulkanEngine::drawQuad(
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
) {
    if (!recordingFrame) return;
    auto primaryIt = textures.find(textureId != 0 ? textureId : whiteTextureId);
    if (primaryIt == textures.end()) return;
    auto darkIt = (darkTextureId != 0 && darkMix > 0.001f) ? textures.find(darkTextureId) : primaryIt;
    if (darkIt == textures.end()) darkIt = primaryIt;

    FrameSync& frame = frames[currentFrame];
    const int32_t primaryId = textureId != 0 ? textureId : whiteTextureId;
    const int32_t darkId = darkTextureId != 0 && darkMix > 0.001f ? darkTextureId : primaryId;
    const uint64_t descriptorKey =
        (static_cast<uint64_t>(static_cast<uint32_t>(primaryId)) << 32) |
        static_cast<uint32_t>(darkId);
    VkDescriptorSet descSet = VK_NULL_HANDLE;
    auto cachedDescriptor = frame.compositeDescriptors.find(descriptorKey);
    if (cachedDescriptor != frame.compositeDescriptors.end()) {
        descSet = cachedDescriptor->second;
    } else {
        VkDescriptorSetAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocInfo.descriptorPool = frame.descriptorPool;
        allocInfo.descriptorSetCount = 1;
        allocInfo.pSetLayouts = &compositeSetLayout;
        if (vkAllocateDescriptorSets(device, &allocInfo, &descSet) != VK_SUCCESS) return;

        VkDescriptorImageInfo imgInfos[2]{
            {linearSampler, primaryIt->second.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL},
            {linearSampler, darkIt->second.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL}
        };
        VkWriteDescriptorSet writes[2]{};
        writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[0].dstSet = descSet;
        writes[0].dstBinding = 0;
        writes[0].descriptorCount = 1;
        writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        writes[0].pImageInfo = &imgInfos[0];
        writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[1].dstSet = descSet;
        writes[1].dstBinding = 1;
        writes[1].descriptorCount = 1;
        writes[1].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        writes[1].pImageInfo = &imgInfos[1];
        vkUpdateDescriptorSets(device, 2, writes, 0, nullptr);
        frame.compositeDescriptors.emplace(descriptorKey, descSet);
    }

    WallpaperPushConstants pc{};
    std::memcpy(pc.mvp, mvp, sizeof(pc.mvp));
    pc.color[0] = r;
    pc.color[1] = g;
    pc.color[2] = b;
    pc.color[3] = a;
    pc.uvOffset[0] = uvOffsetX;
    pc.uvOffset[1] = uvOffsetY;
    pc.uvScale[0] = uvScaleX;
    pc.uvScale[1] = uvScaleY;
    pc.darkMix = (darkTextureId != 0 && darkMix > 0.001f) ? darkMix : 0.0f;

    vkCmdBindPipeline(
        frame.commandBuffer,
        VK_PIPELINE_BIND_POINT_GRAPHICS,
        usingOffscreenPass ? offscreenCompositePipeline : compositePipeline
    );
    vkCmdBindDescriptorSets(
        frame.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, compositePipelineLayout, 0, 1, &descSet, 0, nullptr
    );
    vkCmdPushConstants(
        frame.commandBuffer, compositePipelineLayout,
        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(pc), &pc
    );
    vkCmdDraw(frame.commandBuffer, 4, 1, 0, 0);
}

void VulkanEngine::drawMotionBlurPass(const float* mvp, int32_t radius, float sampleStep, float direction) {
    if (!recordingFrame || blurView == VK_NULL_HANDLE) return;
    FrameSync& frame = frames[currentFrame];
    VkDescriptorSet descSet = frame.blurDescriptor;
    if (descSet == VK_NULL_HANDLE) {
        VkDescriptorSetAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocInfo.descriptorPool = frame.descriptorPool;
        allocInfo.descriptorSetCount = 1;
        allocInfo.pSetLayouts = &blurSetLayout;
        if (vkAllocateDescriptorSets(device, &allocInfo, &descSet) != VK_SUCCESS) return;

        VkDescriptorImageInfo imgInfo{linearSampler, blurView, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        VkWriteDescriptorSet write{};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = descSet;
        write.dstBinding = 0;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        write.pImageInfo = &imgInfo;
        vkUpdateDescriptorSets(device, 1, &write, 0, nullptr);
        frame.blurDescriptor = descSet;
    }

    MotionBlurPushConstants pc{};
    std::memcpy(pc.mvp, mvp, sizeof(pc.mvp));
    pc.params[0] = static_cast<float>(radius);
    pc.params[1] = sampleStep;
    pc.params[2] = direction;
    pc.params[3] = 0.0f;

    vkCmdBindPipeline(frame.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, blurPipeline);
    vkCmdBindDescriptorSets(
        frame.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, blurPipelineLayout, 0, 1, &descSet, 0, nullptr
    );
    vkCmdPushConstants(
        frame.commandBuffer, blurPipelineLayout,
        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(pc), &pc
    );
    vkCmdDraw(frame.commandBuffer, 4, 1, 0, 0);
    vkCmdBindPipeline(frame.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, compositePipeline);
}

int32_t VulkanEngine::endFrame() {
    if (!recordingFrame) return -1;
    recordingFrame = false;
    FrameSync& frame = frames[currentFrame];
    const bool endingOffscreenPass = usingOffscreenPass;
    vkCmdEndRenderPass(frame.commandBuffer);
    if (gpuTimestampsEnabled && frame.timestampQueryPool != VK_NULL_HANDLE) {
        if (endingOffscreenPass) {
            // Covers the early-out path where the offscreen pass was recorded
            // but the caller did not transition to the swapchain pass.
            vkCmdWriteTimestamp(
                frame.commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, frame.timestampQueryPool, 1
            );
        }
        vkCmdWriteTimestamp(
            frame.commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, frame.timestampQueryPool, 2
        );
    }
    if (vkEndCommandBuffer(frame.commandBuffer) != VK_SUCCESS) return -1;

    VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.waitSemaphoreCount = 1;
    submit.pWaitSemaphores = &frame.imageAvailable;
    submit.pWaitDstStageMask = &waitStage;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &frame.commandBuffer;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &frame.renderFinished;
    // Keep the fence signaled while recording so early returns cannot strand
    // this frame slot in an unsignaled state.
    if (vkResetFences(device, 1, &frame.inFlight) != VK_SUCCESS) return -1;
    const auto submitStartedAt = SteadyClock::now();
    if (vkQueueSubmit(graphicsQueue, 1, &submit, frame.inFlight) != VK_SUCCESS) {
        VkFenceCreateInfo signaledFence{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, nullptr, VK_FENCE_CREATE_SIGNALED_BIT};
        vkDestroyFence(device, frame.inFlight, nullptr);
        frame.inFlight = VK_NULL_HANDLE;
        vkCreateFence(device, &signaledFence, nullptr, &frame.inFlight);
        return -1;
    }
    frame.submissionSerial = ++submittedFrameSerial;
    frame.timestampPending = gpuTimestampsEnabled && frame.timestampQueryPool != VK_NULL_HANDLE;
    recordTimingSample("submit", elapsedNs(submitStartedAt));

    VkPresentInfoKHR present{};
    present.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    present.waitSemaphoreCount = 1;
    present.pWaitSemaphores = &frame.renderFinished;
    present.swapchainCount = 1;
    present.pSwapchains = &swapchain;
    present.pImageIndices = &activeImageIndex;
    const auto presentStartedAt = SteadyClock::now();
    VkResult res = vkQueuePresentKHR(graphicsQueue, &present);
    recordTimingSample("present", elapsedNs(presentStartedAt));
    currentFrame = (currentFrame + 1) % kMaxFramesInFlight;

    if (res == VK_ERROR_OUT_OF_DATE_KHR || res == VK_SUBOPTIMAL_KHR) {
        swapchainOutOfDate = true;
        return res == VK_ERROR_OUT_OF_DATE_KHR ? 1 : 0;
    }
    return res == VK_SUCCESS ? 0 : -1;
}

} // namespace paperpages_vk
