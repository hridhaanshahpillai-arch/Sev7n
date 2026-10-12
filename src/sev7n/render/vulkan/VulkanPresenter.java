package sev7n.render.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import sev7n.render.GraphicsBackend;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;

import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Shows Vulkan frames in a game window. Each frame:
 *   beginFrame   wait for the GPU to free a frame slot, get the next window image
 *   (SceneRenderer draws through backend() into the off-screen RenderTarget)
 *   endFrame     stretch the picture onto the window image (sharp or smooth), and show it
 *
 * The window must be opened without an OpenGL context (AppWindow.openForVulkan).
 * Throws VulkanUnavailableException if Vulkan can't run here, so the game can use OpenGL instead.
 */
public final class VulkanPresenter implements AutoCloseable {

    private static final int FRAMES = VulkanDevice.FRAMES_IN_FLIGHT;

    private final long window;
    private final VulkanDevice vk;
    private final VulkanBackend backend;
    private final RenderTarget target;
    private final boolean smoothStretch; // whether the picture can be stretched smoothly (it always can on PCs)

    private long swapchain;
    private int swapchainFormat;
    private int swapWidth;
    private int swapHeight;
    private long[] swapImages = new long[0];
    private long[] renderFinished = new long[0]; // one per window image: signalled when its frame is ready to show
    private boolean vsync;
    private boolean swapchainStale;

    private final VkCommandBuffer[] commands = new VkCommandBuffer[FRAMES];
    private final long[] imageAvailable = new long[FRAMES];
    private final long[] inFlight = new long[FRAMES];
    private int frame = 0;
    private int imageIndex;

    public VulkanPresenter(long window, boolean vsync) {
        this.window = window;
        this.vsync = vsync;
        String debug = System.getenv("SEV7N_VULKAN_DEBUG");
        vk = new VulkanDevice(window, debug != null && !debug.equals("0"));
        try {
            int colorFormat = firstColorFormat();
            target = new RenderTarget(vk, colorFormat);
            smoothStretch = vk.supports(colorFormat, VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT);
            createSwapchain();
            createFrameObjects();
            backend = new VulkanBackend(vk);
        } catch (RuntimeException | LinkageError | OutOfMemoryError e) {
            vkDeviceWaitIdle(vk.device);
            destroyWindowObjects();
            vk.close();
            throw e instanceof VulkanUnavailableException v ? v : new VulkanUnavailableException(String.valueOf(e.getMessage()), e);
        }
    }

    /** The swapchain and the per-frame semaphores and fences (whichever exist). */
    private void destroyWindowObjects() {
        for (int i = 0; i < FRAMES; i++) {
            if (imageAvailable[i] != 0) vkDestroySemaphore(vk.device, imageAvailable[i], null);
            if (inFlight[i] != 0) vkDestroyFence(vk.device, inFlight[i], null);
        }
        for (long semaphore : renderFinished) vkDestroySemaphore(vk.device, semaphore, null);
        if (swapchain != 0) vkDestroySwapchainKHR(vk.device, swapchain, null);
    }

    private int firstColorFormat() {
        try (MemoryStack stack = stackPush()) {
            int features = VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT | VK_FORMAT_FEATURE_BLIT_SRC_BIT;
            int format = vk.firstSupported(stack, features, RenderTarget.COLOR_FORMATS);
            if (format < 0) throw new VulkanUnavailableException("the graphics card can't copy pictures to the window");
            return format;
        }
    }

    /** Draws with this. */
    public GraphicsBackend backend() {
        return backend;
    }

    /** VSync on: wait for the screen's refresh (no tearing). Off: show frames as soon as they're ready. */
    public void setVsync(boolean on) {
        if (on == vsync) return;
        vsync = on;
        swapchainStale = true;
    }

    // ---------------------------------------------------------------- swapchain (the window's images)

    private void createSwapchain() {
        try (MemoryStack stack = stackPush()) {
            VkSurfaceCapabilitiesKHR capabilities = VkSurfaceCapabilitiesKHR.malloc(stack);
            VulkanDevice.check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(vk.physicalDevice, vk.surface, capabilities),
                    "read the window's abilities");
            if ((capabilities.supportedUsageFlags() & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0) {
                throw new VulkanUnavailableException("the window can't receive copied pictures");
            }
            pickFormat(stack);
            int presentMode = pickPresentMode(stack);

            int width = capabilities.currentExtent().width();
            int height = capabilities.currentExtent().height();
            if (width == 0xFFFFFFFF) { // the window lets us choose: match its size
                int[] w = new int[1];
                int[] h = new int[1];
                glfwGetFramebufferSize(window, w, h);
                width = clamp(w[0], capabilities.minImageExtent().width(), capabilities.maxImageExtent().width());
                height = clamp(h[0], capabilities.minImageExtent().height(), capabilities.maxImageExtent().height());
            }
            int imageCount = capabilities.minImageCount() + 1;
            if (capabilities.maxImageCount() > 0) imageCount = Math.min(imageCount, capabilities.maxImageCount());
            int alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
            if ((capabilities.supportedCompositeAlpha() & alpha) == 0) alpha = Integer.lowestOneBit(capabilities.supportedCompositeAlpha());

            VkSwapchainCreateInfoKHR info = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default()
                    .surface(vk.surface).minImageCount(imageCount)
                    .imageFormat(swapchainFormat).imageColorSpace(VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)
                    .imageArrayLayers(1)
                    .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(capabilities.currentTransform()).compositeAlpha(alpha)
                    .presentMode(presentMode).clipped(true).oldSwapchain(swapchain);
            info.imageExtent().set(width, height);
            LongBuffer handle = stack.mallocLong(1);
            VulkanDevice.check(vkCreateSwapchainKHR(vk.device, info, null, handle), "create the window's images");
            if (swapchain != 0) vkDestroySwapchainKHR(vk.device, swapchain, null);
            swapchain = handle.get(0);
            swapWidth = width;
            swapHeight = height;

            IntBuffer count = stack.mallocInt(1);
            vkGetSwapchainImagesKHR(vk.device, swapchain, count, null);
            LongBuffer images = stack.mallocLong(count.get(0));
            vkGetSwapchainImagesKHR(vk.device, swapchain, count, images);
            swapImages = new long[count.get(0)];
            images.get(swapImages);

            for (long semaphore : renderFinished) vkDestroySemaphore(vk.device, semaphore, null);
            renderFinished = new long[swapImages.length];
            for (int i = 0; i < renderFinished.length; i++) renderFinished[i] = semaphore(stack);
        }
        swapchainStale = false;
    }

    /** Plain 8-bit color like the off-screen picture, so colors match OpenGL exactly. */
    private void pickFormat(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceSurfaceFormatsKHR(vk.physicalDevice, vk.surface, count, null);
        VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
        vkGetPhysicalDeviceSurfaceFormatsKHR(vk.physicalDevice, vk.surface, count, formats);
        swapchainFormat = -1;
        for (int wanted : RenderTarget.COLOR_FORMATS) {
            for (VkSurfaceFormatKHR format : formats) {
                if (swapchainFormat < 0 && format.format() == wanted && format.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                    swapchainFormat = wanted;
                }
            }
        }
        if (swapchainFormat < 0) throw new VulkanUnavailableException("the window has no 8-bit color format");
        if (!vk.supports(swapchainFormat, VK_FORMAT_FEATURE_BLIT_DST_BIT)) {
            throw new VulkanUnavailableException("the graphics card can't stretch pictures onto the window");
        }
    }

    /**
     * VSync waits for the screen (FIFO). Without it, frames are shown the moment they're ready
     * (IMMEDIATE, may tear), or else replace the waiting frame (MAILBOX; on laptops with two
     * graphics cards this still waits for the screen).
     */
    private int pickPresentMode(MemoryStack stack) {
        if (vsync) return VK_PRESENT_MODE_FIFO_KHR; // always available
        IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceSurfacePresentModesKHR(vk.physicalDevice, vk.surface, count, null);
        IntBuffer modes = stack.mallocInt(count.get(0));
        vkGetPhysicalDeviceSurfacePresentModesKHR(vk.physicalDevice, vk.surface, count, modes);
        boolean mailbox = false;
        boolean immediate = false;
        for (int i = 0; i < modes.capacity(); i++) {
            mailbox |= modes.get(i) == VK_PRESENT_MODE_MAILBOX_KHR;
            immediate |= modes.get(i) == VK_PRESENT_MODE_IMMEDIATE_KHR;
        }
        return immediate ? VK_PRESENT_MODE_IMMEDIATE_KHR : mailbox ? VK_PRESENT_MODE_MAILBOX_KHR : VK_PRESENT_MODE_FIFO_KHR;
    }

    private void createFrameObjects() {
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo allocate = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(vk.commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(FRAMES);
            PointerBuffer pointers = stack.mallocPointer(FRAMES);
            VulkanDevice.check(vkAllocateCommandBuffers(vk.device, allocate, pointers), "allocate commands");
            VkFenceCreateInfo fence = VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT);
            LongBuffer handle = stack.mallocLong(1);
            for (int i = 0; i < FRAMES; i++) {
                commands[i] = new VkCommandBuffer(pointers.get(i), vk.device);
                imageAvailable[i] = semaphore(stack);
                VulkanDevice.check(vkCreateFence(vk.device, fence, null, handle), "create a fence");
                inFlight[i] = handle.get(0);
            }
        }
    }

    private long semaphore(MemoryStack stack) {
        LongBuffer handle = stack.mallocLong(1);
        VulkanDevice.check(vkCreateSemaphore(vk.device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, handle),
                "create a semaphore");
        return handle.get(0);
    }

    private void recreateSwapchain() {
        vkDeviceWaitIdle(vk.device);
        createSwapchain();
    }

    // ---------------------------------------------------------------- frames

    /**
     * Starts a frame drawn at this size (the render scale) with this many anti-aliasing samples.
     * Returns false if there's nothing to draw into right now (minimized window): skip the frame.
     */
    public boolean beginFrame(int width, int height, int samples) {
        int[] w = new int[1];
        int[] h = new int[1];
        glfwGetFramebufferSize(window, w, h);
        if (w[0] == 0 || h[0] == 0) return false;
        if (swapchainStale || w[0] != swapWidth || h[0] != swapHeight) recreateSwapchain();

        try (MemoryStack stack = stackPush()) {
            vkWaitForFences(vk.device, inFlight[frame], true, Long.MAX_VALUE);
            vk.startFrame(frame);
            IntBuffer index = stack.mallocInt(1);
            int result = vkAcquireNextImageKHR(vk.device, swapchain, Long.MAX_VALUE, imageAvailable[frame], VK_NULL_HANDLE, index);
            if (result == VK_ERROR_OUT_OF_DATE_KHR) {
                swapchainStale = true;
                return false;
            }
            if (result == VK_SUBOPTIMAL_KHR) swapchainStale = true; // still fine to show this one
            else VulkanDevice.check(result, "get the next window image");
            imageIndex = index.get(0);
            vkResetFences(vk.device, inFlight[frame]);

            VkCommandBuffer cmd = commands[frame];
            vkResetCommandBuffer(cmd, 0);
            vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));
        }
        target.ensure(width, height, supportedSamples(samples));
        backend.startFrame(commands[frame], target, frame);
        return true;
    }

    /** The nearest anti-aliasing the GPU can do: 1 (off), 2, 4 or 8. */
    private int supportedSamples(int wanted) {
        int samples = Math.max(1, Integer.highestOneBit(Math.max(1, wanted)));
        return Math.min(samples, Math.max(1, vk.maxSamples));
    }

    /**
     * Shows the frame: the picture is stretched over the whole window, with sharp pixels if
     * 'pixelated'. If 'screenshot' isn't null, the picture is also saved there as a PNG.
     */
    public void endFrame(boolean pixelated, Path screenshot) {
        VkCommandBuffer cmd = commands[frame];
        boolean drew = backend.finishFrame();
        long swapImage = swapImages[imageIndex];
        int color = VK_IMAGE_ASPECT_COLOR_BIT;
        VulkanDevice.Buffer readback = null;

        try (MemoryStack stack = stackPush()) {
            VulkanDevice.barrier(cmd, swapImage, color, 0, 1, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
            if (drew) {
                if (screenshot != null) readback = recordReadback(cmd, stack);
                VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
                blit.srcSubresource().set(color, 0, 0, 1);
                blit.srcOffsets(1).set(target.width, target.height, 1);
                blit.dstSubresource().set(color, 0, 0, 1);
                blit.dstOffsets(1).set(swapWidth, swapHeight, 1);
                vkCmdBlitImage(cmd, target.picture(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, swapImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        blit, pixelated || !smoothStretch ? VK_FILTER_NEAREST : VK_FILTER_LINEAR);
            } else {
                // no camera: show black
                VkClearColorValue black = VkClearColorValue.calloc(stack).float32(3, 1);
                VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack).set(color, 0, 1, 0, 1);
                vkCmdClearColorImage(cmd, swapImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, black, range);
            }
            VulkanDevice.barrier(cmd, swapImage, color, 0, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                    VK_ACCESS_TRANSFER_WRITE_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
            VulkanDevice.check(vkEndCommandBuffer(cmd), "finish recording a frame");

            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default()
                    .waitSemaphoreCount(1).pWaitSemaphores(stack.longs(imageAvailable[frame]))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT))
                    .pCommandBuffers(stack.pointers(cmd))
                    .pSignalSemaphores(stack.longs(renderFinished[imageIndex]));
            VulkanDevice.check(vkQueueSubmit(vk.queue, submit, inFlight[frame]), "send a frame to the graphics card");

            VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack).sType$Default()
                    .pWaitSemaphores(stack.longs(renderFinished[imageIndex]))
                    .swapchainCount(1).pSwapchains(stack.longs(swapchain)).pImageIndices(stack.ints(imageIndex));
            int result = vkQueuePresentKHR(vk.queue, present);
            if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR) swapchainStale = true;
            else VulkanDevice.check(result, "show a frame");
        }
        if (readback != null) saveReadback(readback, screenshot);
        frame = (frame + 1) % FRAMES;
    }

    /** Copies the finished picture into memory the CPU can read, for screenshots. */
    private VulkanDevice.Buffer recordReadback(VkCommandBuffer cmd, MemoryStack stack) {
        VulkanDevice.Buffer buffer = vk.buffer((long) target.width * target.height * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.imageSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
        region.imageExtent().set(target.width, target.height, 1);
        vkCmdCopyImageToBuffer(cmd, target.picture(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer.handle(), region);
        VkMemoryBarrier.Buffer toHost = VkMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, toHost, null, null);
        return buffer;
    }

    private void saveReadback(VulkanDevice.Buffer buffer, Path file) {
        vkQueueWaitIdle(vk.queue);
        int width = target.width;
        int height = target.height;
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            VulkanDevice.check(vkMapMemory(vk.device, buffer.memory(), 0, buffer.size(), 0, pointer), "read the picture");
            ByteBuffer pixels = MemoryUtil.memByteBuffer(pointer.get(0), (int) buffer.size());
            boolean bgr = target.colorFormat == VK_FORMAT_B8G8R8A8_UNORM;
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = (y * width + x) * 4;
                    int first = pixels.get(i) & 255;
                    int third = pixels.get(i + 2) & 255;
                    int r = bgr ? third : first;
                    int b = bgr ? first : third;
                    image.setRGB(x, y, (r << 16) | ((pixels.get(i + 1) & 255) << 8) | b);
                }
            }
            vkUnmapMemory(vk.device, buffer.memory());
            ImageIO.write(image, "png", file.toFile());
        } catch (IOException e) {
            System.err.println("Screenshot failed: " + e.getMessage());
        } finally {
            vk.destroy(buffer);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Frees everything. Close the SceneRenderer (which closes the backend) first. */
    @Override
    public void close() {
        vk.finishAll();
        backend.destroy();
        target.close();
        destroyWindowObjects();
        vk.close();
    }
}
