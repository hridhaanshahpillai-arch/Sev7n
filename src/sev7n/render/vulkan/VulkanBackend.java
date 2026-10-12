package sev7n.render.vulkan;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import sev7n.render.GpuMesh;
import sev7n.render.GpuTexture;
import sev7n.render.GraphicsBackend;
import sev7n.render.MeshData;
import sev7n.render.RenderSettings;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Draws with Vulkan, for the game window when Vulkan is chosen in Settings. VulkanPresenter
 * owns the window side (swapchain, frames); this records the drawing for each frame.
 *
 * Pipelines (Vulkan's baked-in drawing states):
 *   lit      solid surfaces: depth tested and written         (+ a wireframe version)
 *   sprite   see-through pictures: blended                     (+ a wireframe version)
 *   lines    grid and outlines: blended, don't write depth     (+ an on-top version)
 */
public final class VulkanBackend implements GraphicsBackend {

    private static final int TEXTURE_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
    private static final int SETS_PER_POOL = 256;
    private static final int VERTEX_STRIDE = MeshData.FLOATS_PER_VERTEX * Float.BYTES;

    private final VulkanDevice vk;
    private final long frameSetLayout;
    private final long textureSetLayout;
    private final long pipelineLayout;
    private final long smoothSampler;
    private final long sharpSampler;
    private final boolean mipmaps;
    private final long[] shaders = new long[5]; // lit vertex, lit fragment, sprite fragment, lines vertex, lines fragment

    // one Frame uniform buffer (and its descriptor set) per frame in flight, always mapped
    private final VulkanDevice.Buffer[] frameBuffers = new VulkanDevice.Buffer[VulkanDevice.FRAMES_IN_FLIGHT];
    private final long[] frameAddresses = new long[VulkanDevice.FRAMES_IN_FLIGHT];
    private final long[] frameSets = new long[VulkanDevice.FRAMES_IN_FLIGHT];
    private final long framePool;

    /** Texture descriptor sets come from pools of SETS_PER_POOL; 'used' counts the live sets in each. */
    private final List<long[]> texturePools = new ArrayList<>(); // {pool, used}
    private final VkTexture white;

    // pipelines, built for the render target's render pass (rebuilt when anti-aliasing changes); 0 = none yet
    private int pipelineVersion;
    private long lit, litWire, sprite, spriteWire, lines, linesOnTop;

    // the frame being recorded (set by VulkanPresenter)
    private VkCommandBuffer commands;
    private RenderTarget target;
    private int slot;
    private boolean drew;
    private boolean wireframe;
    private long boundPipeline;
    private long boundTextureSet;

    VulkanBackend(VulkanDevice vk) {
        this.vk = vk;
        try (MemoryStack stack = stackPush()) {
            frameSetLayout = setLayout(stack, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
            textureSetLayout = setLayout(stack, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_FRAGMENT_BIT);
            VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(VulkanShaders.PUSH_SIZE);
            VkPipelineLayoutCreateInfo layout = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(frameSetLayout, textureSetLayout)).pPushConstantRanges(push);
            LongBuffer handle = stack.mallocLong(1);
            VulkanDevice.check(vkCreatePipelineLayout(vk.device, layout, null, handle), "create a pipeline layout");
            pipelineLayout = handle.get(0);

            smoothSampler = sampler(stack, false);
            sharpSampler = sampler(stack, true);
            mipmaps = vk.supports(TEXTURE_FORMAT, VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT
                    | VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT);

            framePool = descriptorPool(stack, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VulkanDevice.FRAMES_IN_FLIGHT, false);
            for (int i = 0; i < VulkanDevice.FRAMES_IN_FLIGHT; i++) createFrameBuffer(stack, i);
        }
        shaders[0] = shaderModule(VulkanShaders.LIT_VERTEX, false, "lit.vert");
        shaders[1] = shaderModule(VulkanShaders.LIT_FRAGMENT, true, "lit.frag");
        shaders[2] = shaderModule(VulkanShaders.SPRITE_FRAGMENT, true, "sprite.frag");
        shaders[3] = shaderModule(VulkanShaders.LINES_VERTEX, false, "lines.vert");
        shaders[4] = shaderModule(VulkanShaders.LINES_FRAGMENT, true, "lines.frag");
        BufferedImage pixel = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        pixel.setRGB(0, 0, 0xFFFFFFFF);
        white = (VkTexture) texture(pixel, true);
    }

    // ---------------------------------------------------------------- setup helpers

    private long setLayout(MemoryStack stack, int type, int stages) {
        VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, stack)
                .binding(0).descriptorType(type).descriptorCount(1).stageFlags(stages);
        VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binding);
        LongBuffer handle = stack.mallocLong(1);
        VulkanDevice.check(vkCreateDescriptorSetLayout(vk.device, info, null, handle), "create a descriptor layout");
        return handle.get(0);
    }

    /** Smooth pictures blend between pixels and use mipmaps far away; sharp ones keep every pixel square. */
    private long sampler(MemoryStack stack, boolean sharp) {
        int filter = sharp ? VK_FILTER_NEAREST : VK_FILTER_LINEAR;
        VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(filter).minFilter(filter)
                .mipmapMode(sharp ? VK_SAMPLER_MIPMAP_MODE_NEAREST : VK_SAMPLER_MIPMAP_MODE_LINEAR)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT).addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                .minLod(0).maxLod(sharp ? 0 : VK_LOD_CLAMP_NONE)
                .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK);
        LongBuffer handle = stack.mallocLong(1);
        VulkanDevice.check(vkCreateSampler(vk.device, info, null, handle), "create a sampler");
        return handle.get(0);
    }

    private long descriptorPool(MemoryStack stack, int type, int count, boolean freeable) {
        VkDescriptorPoolSize.Buffer size = VkDescriptorPoolSize.calloc(1, stack).type(type).descriptorCount(count);
        VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                .flags(freeable ? VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT : 0)
                .maxSets(count).pPoolSizes(size);
        LongBuffer handle = stack.mallocLong(1);
        VulkanDevice.check(vkCreateDescriptorPool(vk.device, info, null, handle), "create a descriptor pool");
        return handle.get(0);
    }

    private long allocateSet(MemoryStack stack, long pool, long layout) {
        VkDescriptorSetAllocateInfo info = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(pool).pSetLayouts(stack.longs(layout));
        LongBuffer handle = stack.mallocLong(1);
        VulkanDevice.check(vkAllocateDescriptorSets(vk.device, info, handle), "allocate a descriptor set");
        return handle.get(0);
    }

    private void createFrameBuffer(MemoryStack stack, int i) {
        frameBuffers[i] = vk.buffer(VulkanShaders.FRAME_SIZE, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        var pointer = stack.mallocPointer(1);
        VulkanDevice.check(vkMapMemory(vk.device, frameBuffers[i].memory(), 0, VulkanShaders.FRAME_SIZE, 0, pointer), "map memory");
        frameAddresses[i] = pointer.get(0);
        frameSets[i] = allocateSet(stack, framePool, frameSetLayout);
        VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(frameBuffers[i].handle()).offset(0).range(VulkanShaders.FRAME_SIZE);
        VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack).sType$Default()
                .dstSet(frameSets[i]).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .descriptorCount(1).pBufferInfo(bufferInfo);
        vkUpdateDescriptorSets(vk.device, write, null);
    }

    private long shaderModule(String source, boolean fragment, String name) {
        ByteBuffer spirv = VulkanShaders.compile(source, fragment, name);
        try (MemoryStack stack = stackPush()) {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
            LongBuffer handle = stack.mallocLong(1);
            VulkanDevice.check(vkCreateShaderModule(vk.device, info, null, handle), "load a shader");
            return handle.get(0);
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }

    // ---------------------------------------------------------------- pipelines

    private void buildPipelines() {
        destroyPipelines();
        pipelineVersion = target.renderPassVersion;
        int samples = target.samples;
        int line = vk.wireframe ? VK_POLYGON_MODE_LINE : VK_POLYGON_MODE_FILL;
        lit = pipeline(shaders[0], shaders[1], true, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, VK_POLYGON_MODE_FILL, false, true, true, samples);
        litWire = pipeline(shaders[0], shaders[1], true, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, line, false, true, true, samples);
        sprite = pipeline(shaders[0], shaders[2], true, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, VK_POLYGON_MODE_FILL, true, true, true, samples);
        spriteWire = pipeline(shaders[0], shaders[2], true, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, line, true, true, true, samples);
        lines = pipeline(shaders[3], shaders[4], false, VK_PRIMITIVE_TOPOLOGY_LINE_LIST, VK_POLYGON_MODE_FILL, true, true, false, samples);
        linesOnTop = pipeline(shaders[3], shaders[4], false, VK_PRIMITIVE_TOPOLOGY_LINE_LIST, VK_POLYGON_MODE_FILL, true, false, false, samples);
    }

    private long pipeline(long vertexShader, long fragmentShader, boolean fullVertex, int topology, int polygonMode,
                          boolean blend, boolean depthTest, boolean depthWrite, int samples) {
        try (MemoryStack stack = stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertexShader).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragmentShader).pName(stack.UTF8("main"));

            // every vertex is position (3 floats), normal (3), texture coords (2); lines only read the position
            VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack)
                    .binding(0).stride(VERTEX_STRIDE).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
            VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(fullVertex ? 3 : 1, stack);
            attributes.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);
            if (fullVertex) {
                attributes.get(1).location(1).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(3 * Float.BYTES);
                attributes.get(2).location(2).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(6 * Float.BYTES);
            }
            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
                    .pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attributes);

            VkPipelineInputAssemblyStateCreateInfo assembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                    .topology(topology);
            VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
                    .viewportCount(1).scissorCount(1);
            // no culling: planes and sprites are visible from both sides
            VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                    .polygonMode(polygonMode).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1f);
            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                    .rasterizationSamples(samples);
            VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
                    .depthTestEnable(depthTest).depthWriteEnable(depthWrite).depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL);
            VkPipelineColorBlendAttachmentState.Buffer blending = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                    .blendEnable(blend)
                    .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .colorBlendOp(VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .alphaBlendOp(VK_BLEND_OP_ADD);
            VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default()
                    .pAttachments(blending);
            VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                    .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

            VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType$Default()
                    .pStages(stages).pVertexInputState(vertexInput).pInputAssemblyState(assembly)
                    .pViewportState(viewport).pRasterizationState(raster).pMultisampleState(multisample)
                    .pDepthStencilState(depth).pColorBlendState(colorBlend).pDynamicState(dynamic)
                    .layout(pipelineLayout).renderPass(target.renderPass).subpass(0);
            LongBuffer handle = stack.mallocLong(1);
            VulkanDevice.check(vkCreateGraphicsPipelines(vk.device, VK_NULL_HANDLE, info, null, handle), "create a pipeline");
            return handle.get(0);
        }
    }

    private void destroyPipelines() {
        if (pipelineVersion == 0) return;
        long[] old = {lit, litWire, sprite, spriteWire, lines, linesOnTop};
        vk.later(() -> {
            for (long pipeline : old) vkDestroyPipeline(vk.device, pipeline, null);
        });
        pipelineVersion = 0;
    }

    // ---------------------------------------------------------------- meshes and textures

    /** Geometry in GPU memory: vertices first, then the indices. */
    private final class VkMesh implements GpuMesh {
        final VulkanDevice.Buffer buffer; // null for an empty mesh
        final long indexOffset;
        final int indexCount;
        private final Vector3f boundsMin;
        private final Vector3f boundsMax;

        VkMesh(MeshData data) {
            boundsMin = data.boundsMin();
            boundsMax = data.boundsMax();
            indexCount = data.indices().length;
            long vertexBytes = (long) data.vertices().length * Float.BYTES;
            indexOffset = vertexBytes;
            if (indexCount == 0) {
                buffer = null;
                return;
            }
            long size = vertexBytes + (long) indexCount * Integer.BYTES;
            ByteBuffer bytes = MemoryUtil.memAlloc((int) size);
            bytes.asFloatBuffer().put(data.vertices());
            bytes.position((int) vertexBytes);
            bytes.asIntBuffer().put(data.indices());
            bytes.position(0);
            buffer = upload(bytes, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
            MemoryUtil.memFree(bytes);
        }

        @Override
        public Vector3f boundsMin() {
            return boundsMin;
        }

        @Override
        public Vector3f boundsMax() {
            return boundsMax;
        }

        @Override
        public void close() {
            if (buffer != null) vk.later(() -> vk.destroy(buffer));
        }
    }

    /** A picture in GPU memory, with the descriptor set that lets shaders read it. */
    private final class VkTexture implements GpuTexture {
        final VulkanDevice.Image image;
        final long set;
        final long[] pool;
        final int width;
        final int height;

        VkTexture(VulkanDevice.Image image, long set, long[] pool, int width, int height) {
            this.image = image;
            this.set = set;
            this.pool = pool;
            this.width = width;
            this.height = height;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public void close() {
            vk.later(() -> {
                try (MemoryStack stack = stackPush()) {
                    vkFreeDescriptorSets(vk.device, pool[0], stack.longs(set));
                }
                pool[1]--;
                vk.destroy(image);
            });
        }
    }

    @Override
    public GpuMesh mesh(MeshData data) {
        return new VkMesh(data);
    }

    /** Copies bytes into a new GPU-only buffer (through a temporary buffer the CPU can write). */
    private VulkanDevice.Buffer upload(ByteBuffer bytes, int usage) {
        long size = bytes.remaining();
        VulkanDevice.Buffer staging = staging(bytes);
        VulkanDevice.Buffer buffer = vk.buffer(size, usage | VK_BUFFER_USAGE_TRANSFER_DST_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        vk.runNow(commands -> {
            try (MemoryStack stack = stackPush()) {
                vkCmdCopyBuffer(commands, staging.handle(), buffer.handle(), VkBufferCopy.calloc(1, stack).size(size));
            }
        });
        vk.destroy(staging);
        return buffer;
    }

    private VulkanDevice.Buffer staging(ByteBuffer bytes) {
        VulkanDevice.Buffer staging = vk.buffer(bytes.remaining(), VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        try (MemoryStack stack = stackPush()) {
            var pointer = stack.mallocPointer(1);
            VulkanDevice.check(vkMapMemory(vk.device, staging.memory(), 0, bytes.remaining(), 0, pointer), "map memory");
            MemoryUtil.memCopy(MemoryUtil.memAddress(bytes), pointer.get(0), bytes.remaining());
            vkUnmapMemory(vk.device, staging.memory());
        }
        return staging;
    }

    @Override
    public GpuTexture texture(BufferedImage picture, boolean pixelArt) {
        int width = picture.getWidth();
        int height = picture.getHeight();
        int[] argb = picture.getRGB(0, 0, width, height, null, 0, width);
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        for (int p : argb) pixels.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >> 24));
        pixels.flip();
        VulkanDevice.Buffer staging = staging(pixels);
        MemoryUtil.memFree(pixels);

        int levels = pixelArt || !mipmaps ? 1 : 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
        VulkanDevice.Image image = vk.image(width, height, levels, VK_SAMPLE_COUNT_1_BIT, TEXTURE_FORMAT,
                VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT);
        vk.runNow(commands -> recordTextureUpload(commands, staging, image.handle(), width, height, levels));
        vk.destroy(staging);

        try (MemoryStack stack = stackPush()) {
            long[] pool = texturePool(stack);
            long set = allocateSet(stack, pool[0], textureSetLayout);
            pool[1]++;
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(pixelArt ? sharpSampler : smoothSampler).imageView(image.view())
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack).sType$Default()
                    .dstSet(set).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).pImageInfo(imageInfo);
            vkUpdateDescriptorSets(vk.device, write, null);
            return new VkTexture(image, set, pool, width, height);
        }
    }

    /** Copies the pixels into the image, then shrinks each mip level from the one before. */
    private static void recordTextureUpload(VkCommandBuffer commands, VulkanDevice.Buffer staging, long image,
                                            int width, int height, int levels) {
        int color = VK_IMAGE_ASPECT_COLOR_BIT;
        VulkanDevice.barrier(commands, image, color, 0, levels, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
        try (MemoryStack stack = stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.imageSubresource().set(color, 0, 0, 1);
            region.imageExtent().set(width, height, 1);
            vkCmdCopyBufferToImage(commands, staging.handle(), image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

            int w = width;
            int h = height;
            for (int level = 1; level < levels; level++) {
                VulkanDevice.barrier(commands, image, color, level - 1, 1,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                int nextW = Math.max(1, w / 2);
                int nextH = Math.max(1, h / 2);
                VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
                blit.srcSubresource().set(color, level - 1, 0, 1);
                blit.srcOffsets(1).set(w, h, 1);
                blit.dstSubresource().set(color, level, 0, 1);
                blit.dstOffsets(1).set(nextW, nextH, 1);
                vkCmdBlitImage(commands, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        blit, VK_FILTER_LINEAR);
                VulkanDevice.barrier(commands, image, color, level - 1, 1,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
                w = nextW;
                h = nextH;
            }
        }
        VulkanDevice.barrier(commands, image, color, levels - 1, 1,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
    }

    /** A texture descriptor pool with room for one more set. */
    private long[] texturePool(MemoryStack stack) {
        for (long[] pool : texturePools) {
            if (pool[1] < SETS_PER_POOL) return pool;
        }
        long[] pool = {descriptorPool(stack, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, SETS_PER_POOL, true), 0};
        texturePools.add(pool);
        return pool;
    }

    // ---------------------------------------------------------------- frames (called by VulkanPresenter)

    void startFrame(VkCommandBuffer commands, RenderTarget target, int slot) {
        this.commands = commands;
        this.target = target;
        this.slot = slot;
        this.drew = false;
    }

    /** Whether a scene was drawn this frame (if not, the window just shows black). */
    boolean finishFrame() {
        commands = null;
        return drew;
    }

    // ---------------------------------------------------------------- GraphicsBackend

    @Override
    public void begin(Frame frame) {
        if (commands == null) throw new IllegalStateException("Vulkan can only draw during a frame (VulkanPresenter.beginFrame)");
        if (pipelineVersion != target.renderPassVersion) buildPipelines();
        RenderSettings settings = frame.settings();
        wireframe = settings.shading == RenderSettings.Shading.WIREFRAME;
        writeFrameUniforms(frame);

        try (MemoryStack stack = stackPush()) {
            Color background = frame.background();
            VkClearValue.Buffer clear = VkClearValue.calloc(2, stack);
            clear.get(0).color().float32(0, background.getRed() / 255f).float32(1, background.getGreen() / 255f)
                    .float32(2, background.getBlue() / 255f).float32(3, 1);
            clear.get(1).depthStencil().set(1, 0);
            VkRenderPassBeginInfo info = VkRenderPassBeginInfo.calloc(stack).sType$Default()
                    .renderPass(target.renderPass).framebuffer(target.framebuffer).pClearValues(clear);
            info.renderArea().extent().set(target.width, target.height);
            vkCmdBeginRenderPass(commands, info, VK_SUBPASS_CONTENTS_INLINE);

            VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                    .x(0).y(0).width(target.width).height(target.height).minDepth(0).maxDepth(1);
            vkCmdSetViewport(commands, 0, viewport);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.extent().set(target.width, target.height);
            vkCmdSetScissor(commands, 0, scissor);
            vkCmdBindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(frameSets[slot]), null);
        }
        boundPipeline = 0;
        boundTextureSet = 0;
    }

    private void writeFrameUniforms(Frame frame) {
        RenderSettings settings = frame.settings();
        ByteBuffer out = MemoryUtil.memByteBuffer(frameAddresses[slot], VulkanShaders.FRAME_SIZE);
        frame.view().view().get(0, out);
        frame.view().projection().get(64, out);
        Vector3f eye = frame.view().eye();
        putVec4(out, 128, eye.x, eye.y, eye.z, 1);
        Vector3f light = frame.lightDirection();
        putVec4(out, 144, light.x, light.y, light.z, 0);
        Vector3f lightColor = frame.lightColor();
        putVec4(out, 160, lightColor.x, lightColor.y, lightColor.z, frame.ambient());
        Color fog = frame.background();
        putVec4(out, 176, fog.getRed() / 255f, fog.getGreen() / 255f, fog.getBlue() / 255f, 1);
        int shading = switch (settings.shading) {
            case FLAT -> 1;
            case TOON -> 2;
            default -> 0;
        };
        putVec4(out, 192,
                settings.fog ? (float) settings.fogStart : 0f,
                settings.fog ? (float) Math.max(settings.fogEnd, settings.fogStart + 0.01) : 0f,
                (float) settings.brightness, shading);
    }

    private static void putVec4(ByteBuffer out, int offset, float x, float y, float z, float w) {
        out.putFloat(offset, x).putFloat(offset + 4, y).putFloat(offset + 8, z).putFloat(offset + 12, w);
    }

    @Override
    public void drawLit(GpuMesh mesh, GpuTexture texture, Matrix4f model, Color color) {
        draw(wireframe ? litWire : lit, (VkMesh) mesh, (VkTexture) texture, model,
                color.getRed() / 255f, color.getGreen() / 255f, color.getBlue() / 255f, 1, 0, 0, 1, 1, 0, 0);
    }

    @Override
    public void drawSprite(GpuMesh mesh, GpuTexture texture, Matrix4f model, Color tint, float u, float v, float width, float height) {
        draw(wireframe ? spriteWire : sprite, (VkMesh) mesh, (VkTexture) texture, model,
                tint.getRed() / 255f, tint.getGreen() / 255f, tint.getBlue() / 255f, tint.getAlpha() / 255f,
                u, v, width, height, 0, 0);
    }

    @Override
    public void drawLines(GpuMesh mesh, Matrix4f model, Color color, float alpha, float fadeStart, float fadeEnd, boolean onTop) {
        draw(onTop ? linesOnTop : lines, (VkMesh) mesh, null, model,
                color.getRed() / 255f, color.getGreen() / 255f, color.getBlue() / 255f, alpha, 0, 0, 1, 1, fadeStart, fadeEnd);
    }

    private void draw(long pipeline, VkMesh mesh, VkTexture texture, Matrix4f model,
                      float r, float g, float b, float a, float u, float v, float uvWidth, float uvHeight,
                      float fadeStart, float fadeEnd) {
        if (mesh.buffer == null) return;
        try (MemoryStack stack = stackPush()) {
            if (pipeline != boundPipeline) {
                vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
                boundPipeline = pipeline;
            }
            long set = (texture != null ? texture : white).set;
            if (set != boundTextureSet) {
                vkCmdBindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 1, stack.longs(set), null);
                boundTextureSet = set;
            }
            ByteBuffer push = stack.malloc(VulkanShaders.PUSH_SIZE);
            model.get(0, push);
            putVec4(push, 64, r, g, b, a);
            putVec4(push, 80, u, v, uvWidth, uvHeight);
            putVec4(push, 96, texture != null ? 1 : 0, 0, fadeStart, fadeEnd);
            vkCmdPushConstants(commands, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, push);
            vkCmdBindVertexBuffers(commands, 0, stack.longs(mesh.buffer.handle()), stack.longs(0));
            vkCmdBindIndexBuffer(commands, mesh.buffer.handle(), mesh.indexOffset, VK_INDEX_TYPE_UINT32);
            vkCmdDrawIndexed(commands, mesh.indexCount, 1, 0, 0, 0);
        }
    }

    @Override
    public void end() {
        vkCmdEndRenderPass(commands);
        drew = true;
    }

    @Override
    public String description() {
        return "Vulkan " + vk.apiVersion + " on " + vk.gpuName;
    }

    /** SceneRenderer is done with this backend. The GPU objects go in destroy(), once the GPU is idle. */
    @Override
    public void close() {
        white.close();
        destroyPipelines();
    }

    /** Frees everything. VulkanPresenter calls this after the GPU has finished all work and every later() has run. */
    void destroy() {
        for (long module : shaders) vkDestroyShaderModule(vk.device, module, null);
        for (VulkanDevice.Buffer buffer : frameBuffers) vk.destroy(buffer); // freeing the memory unmaps it
        vkDestroyDescriptorPool(vk.device, framePool, null);
        for (long[] pool : texturePools) vkDestroyDescriptorPool(vk.device, pool[0], null);
        vkDestroySampler(vk.device, smoothSampler, null);
        vkDestroySampler(vk.device, sharpSampler, null);
        vkDestroyPipelineLayout(vk.device, pipelineLayout, null);
        vkDestroyDescriptorSetLayout(vk.device, frameSetLayout, null);
        vkDestroyDescriptorSetLayout(vk.device, textureSetLayout, null);
    }
}
