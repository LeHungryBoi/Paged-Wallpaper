#include "vk_engine.h"

#include <android/log.h>
#include <cstring>
#include <vector>

#define LOG_TAG "PaperPagesVK"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace paperpages_vk {

namespace {
const uint32_t kWallpaperVertSpv[] =
#include "wallpaper_vert_spv.inc"
;
const uint32_t kWallpaperFragSpv[] =
#include "wallpaper_frag_spv.inc"
;
const uint32_t kMotionBlurVertSpv[] =
#include "motion_blur_vert_spv.inc"
;
const uint32_t kMotionBlurFragSpv[] =
#include "motion_blur_frag_spv.inc"
;

constexpr float kQuadVertices[16] = {
    0.0f, 0.0f, 0.0f, 0.0f,
    1.0f, 0.0f, 1.0f, 0.0f,
    0.0f, 1.0f, 0.0f, 1.0f,
    1.0f, 1.0f, 1.0f, 1.0f
};
} // namespace

uint32_t VulkanEngine::findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties) const {
    for (uint32_t i = 0; i < memoryProps.memoryTypeCount; ++i) {
        if ((typeFilter & (1u << i)) &&
            (memoryProps.memoryTypes[i].propertyFlags & properties) == properties) {
            return i;
        }
    }
    return UINT32_MAX;
}

VkShaderModule VulkanEngine::createShaderModule(const uint32_t* code, size_t sizeBytes) const {
    VkShaderModuleCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    info.codeSize = sizeBytes;
    info.pCode = code;
    VkShaderModule mod = VK_NULL_HANDLE;
    if (vkCreateShaderModule(device, &info, nullptr, &mod) != VK_SUCCESS) {
        return VK_NULL_HANDLE;
    }
    return mod;
}

bool VulkanEngine::initDevice() {
    if (device != VK_NULL_HANDLE) return true;
    timestampValidBits = 0;
    timestampPeriodNs = 0.0;
    gpuTimestampsEnabled = false;

    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "PaperPages";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "PaperPagesVK";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_0;

    const char* extensions[] = {
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME
    };

    VkInstanceCreateInfo instInfo{};
    instInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instInfo.pApplicationInfo = &appInfo;
    instInfo.enabledExtensionCount = 2;
    instInfo.ppEnabledExtensionNames = extensions;

    if (vkCreateInstance(&instInfo, nullptr, &instance) != VK_SUCCESS) {
        LOGE("vkCreateInstance failed");
        return false;
    }

    uint32_t gpuCount = 0;
    vkEnumeratePhysicalDevices(instance, &gpuCount, nullptr);
    if (gpuCount == 0) {
        LOGE("No Vulkan physical devices available");
        destroyDevice();
        return false;
    }
    std::vector<VkPhysicalDevice> gpus(gpuCount);
    vkEnumeratePhysicalDevices(instance, &gpuCount, gpus.data());

    for (VkPhysicalDevice gpu : gpus) {
        uint32_t qCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(gpu, &qCount, nullptr);
        std::vector<VkQueueFamilyProperties> qProps(qCount);
        vkGetPhysicalDeviceQueueFamilyProperties(gpu, &qCount, qProps.data());
        for (uint32_t i = 0; i < qCount; ++i) {
            if ((qProps[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) == 0) continue;
            // Keep the first graphics queue as a fallback, but prefer one that
            // can provide timestamp queries for GPU-side frame diagnostics.
            if (physicalDevice == VK_NULL_HANDLE) {
                physicalDevice = gpu;
                graphicsQueueFamily = i;
                timestampValidBits = qProps[i].timestampValidBits;
            }
            if (qProps[i].timestampValidBits > 0) {
                physicalDevice = gpu;
                graphicsQueueFamily = i;
                timestampValidBits = qProps[i].timestampValidBits;
                break;
            }
        }
        if (timestampValidBits > 0) break;
    }

    if (physicalDevice == VK_NULL_HANDLE) {
        LOGE("No Vulkan graphics queue family found");
        destroyDevice();
        return false;
    }

    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(physicalDevice, &props);
    maxImageDimension2D = props.limits.maxImageDimension2D;
    timestampPeriodNs = static_cast<double>(props.limits.timestampPeriod);
    gpuTimestampsEnabled = timestampValidBits > 0 && timestampPeriodNs > 0.0;
    vkGetPhysicalDeviceMemoryProperties(physicalDevice, &memoryProps);

    float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = graphicsQueueFamily;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;

    const char* devExts[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    VkDeviceCreateInfo devInfo{};
    devInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    devInfo.queueCreateInfoCount = 1;
    devInfo.pQueueCreateInfos = &queueInfo;
    devInfo.enabledExtensionCount = 1;
    devInfo.ppEnabledExtensionNames = devExts;

    if (vkCreateDevice(physicalDevice, &devInfo, nullptr, &device) != VK_SUCCESS) {
        LOGE("vkCreateDevice failed");
        destroyDevice();
        return false;
    }
    vkGetDeviceQueue(device, graphicsQueueFamily, 0, &graphicsQueue);

    VkCommandPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.queueFamilyIndex = graphicsQueueFamily;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    if (vkCreateCommandPool(device, &poolInfo, nullptr, &commandPool) != VK_SUCCESS) {
        destroyDevice();
        return false;
    }

    VkPipelineCacheCreateInfo cacheInfo{};
    cacheInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
    vkCreatePipelineCache(device, &cacheInfo, nullptr, &pipelineCache);

    // Composite descriptor set layout (binding 0 = uTexture, binding 1 = uDarkTexture)
    VkDescriptorSetLayoutBinding compBindings[2]{};
    compBindings[0].binding = 0;
    compBindings[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    compBindings[0].descriptorCount = 1;
    compBindings[0].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    compBindings[1].binding = 1;
    compBindings[1].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    compBindings[1].descriptorCount = 1;
    compBindings[1].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

    VkDescriptorSetLayoutCreateInfo compLayoutInfo{};
    compLayoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    compLayoutInfo.bindingCount = 2;
    compLayoutInfo.pBindings = compBindings;
    if (vkCreateDescriptorSetLayout(device, &compLayoutInfo, nullptr, &compositeSetLayout) != VK_SUCCESS) {
        destroyDevice();
        return false;
    }

    // Blur descriptor set layout (binding 0 = uTexture)
    VkDescriptorSetLayoutBinding blurBinding{};
    blurBinding.binding = 0;
    blurBinding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    blurBinding.descriptorCount = 1;
    blurBinding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

    VkDescriptorSetLayoutCreateInfo blurLayoutInfo{};
    blurLayoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    blurLayoutInfo.bindingCount = 1;
    blurLayoutInfo.pBindings = &blurBinding;
    if (vkCreateDescriptorSetLayout(device, &blurLayoutInfo, nullptr, &blurSetLayout) != VK_SUCCESS) {
        destroyDevice();
        return false;
    }

    VkPushConstantRange compRange{};
    compRange.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
    compRange.offset = 0;
    compRange.size = sizeof(WallpaperPushConstants);

    VkPipelineLayoutCreateInfo compPipeLayoutInfo{};
    compPipeLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    compPipeLayoutInfo.setLayoutCount = 1;
    compPipeLayoutInfo.pSetLayouts = &compositeSetLayout;
    compPipeLayoutInfo.pushConstantRangeCount = 1;
    compPipeLayoutInfo.pPushConstantRanges = &compRange;
    if (vkCreatePipelineLayout(device, &compPipeLayoutInfo, nullptr, &compositePipelineLayout) != VK_SUCCESS) {
        destroyDevice();
        return false;
    }

    VkPushConstantRange blurRange{};
    blurRange.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
    blurRange.offset = 0;
    blurRange.size = sizeof(MotionBlurPushConstants);

    VkPipelineLayoutCreateInfo blurPipeLayoutInfo{};
    blurPipeLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    blurPipeLayoutInfo.setLayoutCount = 1;
    blurPipeLayoutInfo.pSetLayouts = &blurSetLayout;
    blurPipeLayoutInfo.pushConstantRangeCount = 1;
    blurPipeLayoutInfo.pPushConstantRanges = &blurRange;
    if (vkCreatePipelineLayout(device, &blurPipeLayoutInfo, nullptr, &blurPipelineLayout) != VK_SUCCESS) {
        destroyDevice();
        return false;
    }

    VkSamplerCreateInfo samplerInfo{};
    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
    samplerInfo.maxLod = 0.0f;
    if (vkCreateSampler(device, &samplerInfo, nullptr, &linearSampler) != VK_SUCCESS) {
        destroyDevice();
        return false;
    }

    // Create quad vertex buffer
    VkBufferCreateInfo bufInfo{};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = sizeof(kQuadVertices);
    bufInfo.usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(device, &bufInfo, nullptr, &quadBuffer) != VK_SUCCESS) {
        destroyDevice();
        return false;
    }
    VkMemoryRequirements memReq{};
    vkGetBufferMemoryRequirements(device, quadBuffer, &memReq);
    uint32_t memIdx = findMemoryType(
        memReq.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
    );
    if (memIdx == UINT32_MAX) {
        destroyDevice();
        return false;
    }
    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = memIdx;
    if (vkAllocateMemory(device, &allocInfo, nullptr, &quadMemory) != VK_SUCCESS ||
        vkBindBufferMemory(device, quadBuffer, quadMemory, 0) != VK_SUCCESS) {
        destroyDevice();
        return false;
    }
    void* mapped = nullptr;
    vkMapMemory(device, quadMemory, 0, sizeof(kQuadVertices), 0, &mapped);
    std::memcpy(mapped, kQuadVertices, sizeof(kQuadVertices));
    vkUnmapMemory(device, quadMemory);

    // Per-frame sync objects & descriptor pools
    for (int i = 0; i < kMaxFramesInFlight; ++i) {
        VkCommandBufferAllocateInfo cmdAlloc{};
        cmdAlloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        cmdAlloc.commandPool = commandPool;
        cmdAlloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cmdAlloc.commandBufferCount = 1;
        if (vkAllocateCommandBuffers(device, &cmdAlloc, &frames[i].commandBuffer) != VK_SUCCESS) {
            destroyDevice();
            return false;
        }
        VkSemaphoreCreateInfo semInfo{};
        semInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        VkFenceCreateInfo fenceInfo{};
        fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        if (vkCreateSemaphore(device, &semInfo, nullptr, &frames[i].imageAvailable) != VK_SUCCESS ||
            vkCreateSemaphore(device, &semInfo, nullptr, &frames[i].renderFinished) != VK_SUCCESS ||
            vkCreateFence(device, &fenceInfo, nullptr, &frames[i].inFlight) != VK_SUCCESS) {
            destroyDevice();
            return false;
        }
        VkDescriptorPoolSize poolSize{};
        poolSize.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        poolSize.descriptorCount = kMaxDescriptorsPerFrame * 2;
        VkDescriptorPoolCreateInfo descPoolInfo{};
        descPoolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        descPoolInfo.maxSets = kMaxDescriptorsPerFrame;
        descPoolInfo.poolSizeCount = 1;
        descPoolInfo.pPoolSizes = &poolSize;
        if (vkCreateDescriptorPool(device, &descPoolInfo, nullptr, &frames[i].descriptorPool) != VK_SUCCESS) {
            destroyDevice();
            return false;
        }
    }

    if (gpuTimestampsEnabled) {
        for (auto& frame : frames) {
            VkQueryPoolCreateInfo queryInfo{};
            queryInfo.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
            queryInfo.queryType = VK_QUERY_TYPE_TIMESTAMP;
            queryInfo.queryCount = 3;
            if (vkCreateQueryPool(device, &queryInfo, nullptr, &frame.timestampQueryPool) != VK_SUCCESS) {
                gpuTimestampsEnabled = false;
                for (auto& createdFrame : frames) {
                    if (createdFrame.timestampQueryPool != VK_NULL_HANDLE) {
                        vkDestroyQueryPool(device, createdFrame.timestampQueryPool, nullptr);
                        createdFrame.timestampQueryPool = VK_NULL_HANDLE;
                    }
                }
                LOGI("GPU timestamp queries disabled: query-pool creation failed");
                break;
            }
        }
    }
    if (gpuTimestampsEnabled) {
        LOGI("GPU timestamp queries enabled (queue=%u validBits=%u periodNs=%.3f)",
             graphicsQueueFamily, timestampValidBits, timestampPeriodNs);
    } else {
        LOGI("GPU timestamp queries unavailable (queue=%u validBits=%u)",
             graphicsQueueFamily, timestampValidBits);
    }

    const uint32_t whitePixel = 0xFFFFFFFFu;
    whiteTextureId = uploadRgbaTexture(&whitePixel, 1, 1);
    if (whiteTextureId == 0) {
        destroyDevice();
        return false;
    }

    LOGI("Initialized Vulkan device (%s, maxTex=%u, gpuTimestamps=%s)",
         props.deviceName, maxImageDimension2D, gpuTimestampsEnabled ? "yes" : "no");
    return true;
}

bool VulkanEngine::createPipelinesForFormat(VkFormat format) {
    if (swapchainRenderPass != VK_NULL_HANDLE && swapchainFormat == format &&
        compositePipeline != VK_NULL_HANDLE) {
        return true;
    }
    vkDeviceWaitIdle(device);

    if (compositePipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, compositePipeline, nullptr);
    if (offscreenCompositePipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, offscreenCompositePipeline, nullptr);
    if (blurPipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, blurPipeline, nullptr);
    if (swapchainRenderPass != VK_NULL_HANDLE) vkDestroyRenderPass(device, swapchainRenderPass, nullptr);
    if (offscreenRenderPass != VK_NULL_HANDLE) vkDestroyRenderPass(device, offscreenRenderPass, nullptr);
    compositePipeline = VK_NULL_HANDLE;
    offscreenCompositePipeline = VK_NULL_HANDLE;
    blurPipeline = VK_NULL_HANDLE;
    swapchainRenderPass = VK_NULL_HANDLE;
    offscreenRenderPass = VK_NULL_HANDLE;

    auto makeRenderPass = [&](VkImageLayout finalLayout) -> VkRenderPass {
        VkAttachmentDescription colorAtt{};
        colorAtt.format = format;
        colorAtt.samples = VK_SAMPLE_COUNT_1_BIT;
        colorAtt.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        colorAtt.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        colorAtt.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        colorAtt.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        colorAtt.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        colorAtt.finalLayout = finalLayout;

        VkAttachmentReference colorRef{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1;
        subpass.pColorAttachments = &colorRef;

        VkSubpassDependency dep{};
        dep.srcSubpass = VK_SUBPASS_EXTERNAL;
        dep.dstSubpass = 0;
        dep.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

        VkRenderPassCreateInfo rpInfo{};
        rpInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        rpInfo.attachmentCount = 1;
        rpInfo.pAttachments = &colorAtt;
        rpInfo.subpassCount = 1;
        rpInfo.pSubpasses = &subpass;
        rpInfo.dependencyCount = 1;
        rpInfo.pDependencies = &dep;

        VkRenderPass rp = VK_NULL_HANDLE;
        vkCreateRenderPass(device, &rpInfo, nullptr, &rp);
        return rp;
    };

    swapchainRenderPass = makeRenderPass(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
    offscreenRenderPass = makeRenderPass(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
    if (swapchainRenderPass == VK_NULL_HANDLE || offscreenRenderPass == VK_NULL_HANDLE) {
        return false;
    }

    VkShaderModule wVert = createShaderModule(kWallpaperVertSpv, sizeof(kWallpaperVertSpv));
    VkShaderModule wFrag = createShaderModule(kWallpaperFragSpv, sizeof(kWallpaperFragSpv));
    VkShaderModule bVert = createShaderModule(kMotionBlurVertSpv, sizeof(kMotionBlurVertSpv));
    VkShaderModule bFrag = createShaderModule(kMotionBlurFragSpv, sizeof(kMotionBlurFragSpv));
    if (!wVert || !wFrag || !bVert || !bFrag) {
        if (wVert) vkDestroyShaderModule(device, wVert, nullptr);
        if (wFrag) vkDestroyShaderModule(device, wFrag, nullptr);
        if (bVert) vkDestroyShaderModule(device, bVert, nullptr);
        if (bFrag) vkDestroyShaderModule(device, bFrag, nullptr);
        return false;
    }

    VkVertexInputBindingDescription binding{0, 16, VK_VERTEX_INPUT_RATE_VERTEX};
    VkVertexInputAttributeDescription attrs[2]{
        {0, 0, VK_FORMAT_R32G32_SFLOAT, 0},
        {1, 0, VK_FORMAT_R32G32_SFLOAT, 8}
    };
    VkPipelineVertexInputStateCreateInfo vertexInput{};
    vertexInput.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
    vertexInput.vertexBindingDescriptionCount = 1;
    vertexInput.pVertexBindingDescriptions = &binding;
    vertexInput.vertexAttributeDescriptionCount = 2;
    vertexInput.pVertexAttributeDescriptions = attrs;

    VkPipelineInputAssemblyStateCreateInfo inputAsm{};
    inputAsm.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
    inputAsm.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;

    VkDynamicState dynStates[2] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynState{};
    dynState.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
    dynState.dynamicStateCount = 2;
    dynState.pDynamicStates = dynStates;

    VkPipelineViewportStateCreateInfo viewportState{};
    viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
    viewportState.viewportCount = 1;
    viewportState.scissorCount = 1;

    VkPipelineRasterizationStateCreateInfo raster{};
    raster.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    raster.polygonMode = VK_POLYGON_MODE_FILL;
    raster.cullMode = VK_CULL_MODE_NONE;
    raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    raster.lineWidth = 1.0f;

    VkPipelineMultisampleStateCreateInfo msaa{};
    msaa.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    msaa.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    auto createPipe = [&](VkShaderModule vs, VkShaderModule fs, VkPipelineLayout layout, VkRenderPass rp, bool blend) -> VkPipeline {
        VkPipelineShaderStageCreateInfo stages[2]{};
        stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
        stages[0].module = vs;
        stages[0].pName = "main";
        stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        stages[1].module = fs;
        stages[1].pName = "main";

        VkPipelineColorBlendAttachmentState blendAtt{};
        blendAtt.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                                  VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
        blendAtt.blendEnable = blend ? VK_TRUE : VK_FALSE;
        blendAtt.srcColorBlendFactor = VK_BLEND_FACTOR_ONE;
        blendAtt.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        blendAtt.colorBlendOp = VK_BLEND_OP_ADD;
        blendAtt.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
        blendAtt.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        blendAtt.alphaBlendOp = VK_BLEND_OP_ADD;

        VkPipelineColorBlendStateCreateInfo blendState{};
        blendState.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        blendState.attachmentCount = 1;
        blendState.pAttachments = &blendAtt;

        VkGraphicsPipelineCreateInfo pInfo{};
        pInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        pInfo.stageCount = 2;
        pInfo.pStages = stages;
        pInfo.pVertexInputState = &vertexInput;
        pInfo.pInputAssemblyState = &inputAsm;
        pInfo.pViewportState = &viewportState;
        pInfo.pRasterizationState = &raster;
        pInfo.pMultisampleState = &msaa;
        pInfo.pColorBlendState = &blendState;
        pInfo.pDynamicState = &dynState;
        pInfo.layout = layout;
        pInfo.renderPass = rp;
        pInfo.subpass = 0;

        VkPipeline pipe = VK_NULL_HANDLE;
        vkCreateGraphicsPipelines(device, pipelineCache, 1, &pInfo, nullptr, &pipe);
        return pipe;
    };

    compositePipeline = createPipe(wVert, wFrag, compositePipelineLayout, swapchainRenderPass, true);
    offscreenCompositePipeline = createPipe(wVert, wFrag, compositePipelineLayout, offscreenRenderPass, true);
    blurPipeline = createPipe(bVert, bFrag, blurPipelineLayout, swapchainRenderPass, false);

    vkDestroyShaderModule(device, wVert, nullptr);
    vkDestroyShaderModule(device, wFrag, nullptr);
    vkDestroyShaderModule(device, bVert, nullptr);
    vkDestroyShaderModule(device, bFrag, nullptr);

    return compositePipeline != VK_NULL_HANDLE &&
           offscreenCompositePipeline != VK_NULL_HANDLE &&
           blurPipeline != VK_NULL_HANDLE;
}

void VulkanEngine::destroyDevice() {
    unbindSurface();
    if (device != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(device);
        for (auto& kv : textures) {
            if (kv.second.view) vkDestroyImageView(device, kv.second.view, nullptr);
            if (kv.second.image) vkDestroyImage(device, kv.second.image, nullptr);
            if (kv.second.memory) vkFreeMemory(device, kv.second.memory, nullptr);
        }
        textures.clear();
        for (const auto& deferred : deferredTextures) {
            if (deferred.texture.view) vkDestroyImageView(device, deferred.texture.view, nullptr);
            if (deferred.texture.image) vkDestroyImage(device, deferred.texture.image, nullptr);
            if (deferred.texture.memory) vkFreeMemory(device, deferred.texture.memory, nullptr);
        }
        deferredTextures.clear();
        whiteTextureId = 0;
        destroyOffscreenTarget();
        if (stagingBuffer) vkDestroyBuffer(device, stagingBuffer, nullptr);
        if (stagingMemory) vkFreeMemory(device, stagingMemory, nullptr);
        stagingBuffer = VK_NULL_HANDLE;
        stagingMemory = VK_NULL_HANDLE;
        stagingCapacity = 0;
        if (quadBuffer) vkDestroyBuffer(device, quadBuffer, nullptr);
        if (quadMemory) vkFreeMemory(device, quadMemory, nullptr);
        quadBuffer = VK_NULL_HANDLE;
        quadMemory = VK_NULL_HANDLE;
        if (linearSampler) vkDestroySampler(device, linearSampler, nullptr);
        linearSampler = VK_NULL_HANDLE;
        if (compositePipeline) vkDestroyPipeline(device, compositePipeline, nullptr);
        if (offscreenCompositePipeline) vkDestroyPipeline(device, offscreenCompositePipeline, nullptr);
        if (blurPipeline) vkDestroyPipeline(device, blurPipeline, nullptr);
        if (swapchainRenderPass) vkDestroyRenderPass(device, swapchainRenderPass, nullptr);
        if (offscreenRenderPass) vkDestroyRenderPass(device, offscreenRenderPass, nullptr);
        if (compositePipelineLayout) vkDestroyPipelineLayout(device, compositePipelineLayout, nullptr);
        if (blurPipelineLayout) vkDestroyPipelineLayout(device, blurPipelineLayout, nullptr);
        if (compositeSetLayout) vkDestroyDescriptorSetLayout(device, compositeSetLayout, nullptr);
        if (blurSetLayout) vkDestroyDescriptorSetLayout(device, blurSetLayout, nullptr);
        if (pipelineCache) vkDestroyPipelineCache(device, pipelineCache, nullptr);
        for (auto& f : frames) {
            if (f.descriptorPool) vkDestroyDescriptorPool(device, f.descriptorPool, nullptr);
            if (f.inFlight) vkDestroyFence(device, f.inFlight, nullptr);
            if (f.imageAvailable) vkDestroySemaphore(device, f.imageAvailable, nullptr);
            if (f.renderFinished) vkDestroySemaphore(device, f.renderFinished, nullptr);
            if (f.timestampQueryPool) vkDestroyQueryPool(device, f.timestampQueryPool, nullptr);
            f = FrameSync{};
        }
        if (commandPool) vkDestroyCommandPool(device, commandPool, nullptr);
        vkDestroyDevice(device, nullptr);
        device = VK_NULL_HANDLE;
    }
    if (instance != VK_NULL_HANDLE) {
        vkDestroyInstance(instance, nullptr);
        instance = VK_NULL_HANDLE;
    }
    physicalDevice = VK_NULL_HANDLE;
    timestampValidBits = 0;
    timestampPeriodNs = 0.0;
    gpuTimestampsEnabled = false;
}

} // namespace paperpages_vk
