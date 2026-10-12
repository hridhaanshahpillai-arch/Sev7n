package sev7n.render.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSubpassDependency;
import org.lwjgl.vulkan.VkSubpassDescription;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * The off-screen picture a frame is drawn into, at the render scale's size and with
 * anti-aliasing, before it's stretched onto the window. Like render/Framebuffer for OpenGL.
 *
 * With anti-aliasing, drawing goes into a multisampled image that the render pass smooths
 * down ("resolves") into the finished picture at the end.
 */
final class RenderTarget implements AutoCloseable {

    /** The finished picture's format: 8-bit color, no gamma conversion (like OpenGL's RGBA8). */
    static final int[] COLOR_FORMATS = {VK_FORMAT_B8G8R8A8_UNORM, VK_FORMAT_R8G8B8A8_UNORM};

    private final VulkanDevice vk;
    final int colorFormat;
    long renderPass;
    /**
     * Goes up whenever the render pass is replaced. Pipelines are built for one render pass;
     * a new one can get the old one's handle back, so the handle alone can't tell them apart.
     */
    int renderPassVersion;
    int samples;
    int width;
    int height;
    private VulkanDevice.Image color;   // multisampled when anti-aliasing is on
    private VulkanDevice.Image resolved; // the finished picture when anti-aliasing is on, else null
    private VulkanDevice.Image depth;
    long framebuffer;

    RenderTarget(VulkanDevice vk, int colorFormat) {
        this.vk = vk;
        this.colorFormat = colorFormat;
    }

    /** The finished picture, left ready to copy from (TRANSFER_SRC_OPTIMAL) after the render pass. */
    long picture() {
        return resolved != null ? resolved.handle() : color.handle();
    }

    /** Makes sure the target has this size and anti-aliasing, recreating what's needed. Returns true if it changed. */
    boolean ensure(int newWidth, int newHeight, int newSamples) {
        newWidth = Math.max(1, newWidth);
        newHeight = Math.max(1, newHeight);
        if (framebuffer != 0 && newWidth == width && newHeight == height && newSamples == samples) return false;
        vkDeviceWaitIdle(vk.device); // the old images may still be in use by frames in flight
        destroyImages();
        if (newSamples != samples && renderPass != 0) {
            vkDestroyRenderPass(vk.device, renderPass, null);
            renderPass = 0;
        }
        width = newWidth;
        height = newHeight;
        samples = newSamples;
        if (renderPass == 0) {
            renderPass = createRenderPass();
            renderPassVersion++;
        }
        createImages();
        return true;
    }

    private long createRenderPass() {
        boolean multisampled = samples > 1;
        try (MemoryStack stack = stackPush()) {
            VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(multisampled ? 3 : 2, stack);
            attachments.get(0).format(colorFormat).samples(samples)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(multisampled ? VK_ATTACHMENT_STORE_OP_DONT_CARE : VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .finalLayout(multisampled ? VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL : VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            attachments.get(1).format(vk.depthFormat).samples(samples)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            if (multisampled) {
                attachments.get(2).format(colorFormat).samples(VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            }

            VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack)
                    .attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkAttachmentReference depthRef = VkAttachmentReference.calloc(stack)
                    .attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1).pColorAttachments(colorRef).pDepthStencilAttachment(depthRef);
            if (multisampled) {
                subpass.pResolveAttachments(VkAttachmentReference.calloc(1, stack)
                        .attachment(2).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL));
            }

            VkSubpassDependency.Buffer dependencies = VkSubpassDependency.calloc(2, stack);
            // wait for the previous frame (its drawing and its copy to the window) before drawing over the same images
            dependencies.get(0).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT
                            | VK_PIPELINE_STAGE_TRANSFER_BIT)
                    .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT
                            | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
            // the finished picture is copied to the window after drawing
            dependencies.get(1).srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_TRANSFER_BIT)
                    .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);

            VkRenderPassCreateInfo info = VkRenderPassCreateInfo.calloc(stack).sType$Default()
                    .pAttachments(attachments).pSubpasses(subpass).pDependencies(dependencies);
            LongBuffer handle = stack.mallocLong(1);
            VulkanDevice.check(vkCreateRenderPass(vk.device, info, null, handle), "create a render pass");
            return handle.get(0);
        }
    }

    private void createImages() {
        boolean multisampled = samples > 1;
        int copyable = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        color = vk.image(width, height, 1, samples, colorFormat,
                multisampled ? VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT : copyable, VK_IMAGE_ASPECT_COLOR_BIT);
        resolved = multisampled ? vk.image(width, height, 1, 1, colorFormat, copyable, VK_IMAGE_ASPECT_COLOR_BIT) : null;
        depth = vk.image(width, height, 1, samples, vk.depthFormat, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,
                VK_IMAGE_ASPECT_DEPTH_BIT);
        try (MemoryStack stack = stackPush()) {
            LongBuffer views = multisampled
                    ? stack.longs(color.view(), depth.view(), resolved.view())
                    : stack.longs(color.view(), depth.view());
            VkFramebufferCreateInfo info = VkFramebufferCreateInfo.calloc(stack).sType$Default()
                    .renderPass(renderPass).pAttachments(views).width(width).height(height).layers(1);
            LongBuffer handle = stack.mallocLong(1);
            VulkanDevice.check(vkCreateFramebuffer(vk.device, info, null, handle), "create a framebuffer");
            framebuffer = handle.get(0);
        }
    }

    private void destroyImages() {
        if (framebuffer == 0) return;
        vkDestroyFramebuffer(vk.device, framebuffer, null);
        vk.destroy(color);
        vk.destroy(depth);
        if (resolved != null) vk.destroy(resolved);
        framebuffer = 0;
        color = null;
        resolved = null;
        depth = null;
    }

    @Override
    public void close() {
        destroyImages();
        if (renderPass != 0) vkDestroyRenderPass(vk.device, renderPass, null);
        renderPass = 0;
    }
}
