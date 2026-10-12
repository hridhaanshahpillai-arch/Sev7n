package sev7n.render.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.glfw.GLFWVulkan.glfwVulkanSupported;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;

/**
 * The connection to the graphics card through Vulkan: the instance, the window's surface,
 * the chosen GPU and its queue, plus helpers for buffers, images and one-off GPU work.
 *
 * Up to FRAMES_IN_FLIGHT frames are worked on by the GPU at once, so something the game
 * stops using may still be needed by a frame in flight: later() destroys it once it's safe.
 */
final class VulkanDevice implements AutoCloseable {

    static final int FRAMES_IN_FLIGHT = 2;
    private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";

    VkInstance instance;
    long surface;
    VkPhysicalDevice physicalDevice;
    VkDevice device;
    VkQueue queue;
    int queueFamily;
    long commandPool;
    String gpuName;
    /** Vulkan version the GPU supports, e.g. "1.3". */
    String apiVersion;
    /** Whether triangles can be drawn as lines (the Wireframe shading). */
    boolean wireframe;
    /** The most anti-aliasing samples the GPU can draw with (1, 2, 4, 8...). */
    int maxSamples;
    int depthFormat;

    private VkPhysicalDeviceMemoryProperties memoryProperties;
    private long debugMessenger;
    private VkDebugUtilsMessengerCallbackEXT debugCallback;
    private final List<List<Runnable>> pendingDestroys = new ArrayList<>();
    private int frameSlot = 0;

    /** A buffer and its memory. */
    record Buffer(long handle, long memory, long size) { }

    /** An image, its memory and a view of it. */
    record Image(long handle, long memory, long view) { }

    /**
     * @param debug turns on Vulkan's validation messages if the Vulkan SDK is installed
     *              (set SEV7N_VULKAN_DEBUG=1), printed to the terminal
     */
    VulkanDevice(long window, boolean debug) {
        if (!glfwVulkanSupported()) throw new VulkanUnavailableException("no Vulkan driver found");
        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) pendingDestroys.add(new ArrayList<>());
        try (MemoryStack stack = stackPush()) {
            boolean validation = debug && hasLayer(stack, VALIDATION_LAYER);
            instance = createInstance(stack, validation);
            if (validation) createDebugMessenger(stack);
            LongBuffer handle = stack.mallocLong(1);
            check(glfwCreateWindowSurface(instance, window, null, handle), "connect Vulkan to the window");
            surface = handle.get(0);
            pickGpu(stack);
            createDevice(stack);
            VkCommandPoolCreateInfo pool = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(queueFamily);
            check(vkCreateCommandPool(device, pool, null, handle), "create a command pool");
            commandPool = handle.get(0);
        } catch (RuntimeException | LinkageError | OutOfMemoryError e) {
            close();
            throw e instanceof VulkanUnavailableException v ? v : new VulkanUnavailableException(String.valueOf(e.getMessage()), e);
        }
    }

    // ---------------------------------------------------------------- setup

    private static boolean hasLayer(MemoryStack stack, String name) {
        IntBuffer count = stack.mallocInt(1);
        vkEnumerateInstanceLayerProperties(count, null);
        VkLayerProperties.Buffer layers = VkLayerProperties.malloc(count.get(0), stack);
        vkEnumerateInstanceLayerProperties(count, layers);
        for (VkLayerProperties layer : layers) {
            if (layer.layerNameString().equals(name)) return true;
        }
        return false;
    }

    private static VkInstance createInstance(MemoryStack stack, boolean validation) {
        PointerBuffer required = glfwGetRequiredInstanceExtensions();
        if (required == null) throw new VulkanUnavailableException("Vulkan can't draw into windows on this PC");
        PointerBuffer extensions = stack.mallocPointer(required.remaining() + 1);
        extensions.put(required);
        if (validation) extensions.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));
        extensions.flip();

        VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default()
                .pApplicationName(stack.UTF8("Sev7n game")).applicationVersion(VK_MAKE_VERSION(0, 5, 0))
                .pEngineName(stack.UTF8("Sev7n")).engineVersion(VK_MAKE_VERSION(0, 5, 0))
                .apiVersion(VK_API_VERSION_1_0);
        VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack).sType$Default()
                .pApplicationInfo(app).ppEnabledExtensionNames(extensions);
        if (validation) info.ppEnabledLayerNames(stack.pointers(stack.UTF8(VALIDATION_LAYER)));
        PointerBuffer pointer = stack.mallocPointer(1);
        int result = vkCreateInstance(info, null, pointer);
        if (result != VK_SUCCESS) throw new VulkanUnavailableException("couldn't start Vulkan (" + Vk.name(result) + ")");
        return new VkInstance(pointer.get(0), info);
    }

    private void createDebugMessenger(MemoryStack stack) {
        debugCallback = VkDebugUtilsMessengerCallbackEXT.create((severity, types, data, user) -> {
            System.err.println("Vulkan: " + VkDebugUtilsMessengerCallbackDataEXT.create(data).pMessageString());
            return VK_FALSE;
        });
        VkDebugUtilsMessengerCreateInfoEXT info = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack).sType$Default()
                .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                        | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                .pfnUserCallback(debugCallback);
        LongBuffer handle = stack.mallocLong(1);
        check(vkCreateDebugUtilsMessengerEXT(instance, info, null, handle), "turn on validation messages");
        debugMessenger = handle.get(0);
    }

    /** Picks the best GPU that can draw into the window: a separate graphics card over a built-in one. */
    private void pickGpu(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkEnumeratePhysicalDevices(instance, count, null);
        if (count.get(0) == 0) throw new VulkanUnavailableException("no graphics card supports Vulkan");
        PointerBuffer devices = stack.mallocPointer(count.get(0));
        vkEnumeratePhysicalDevices(instance, count, devices);

        int bestScore = -1;
        VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
        for (int i = 0; i < devices.capacity(); i++) {
            VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), instance);
            int family = drawingQueueFamily(candidate, stack);
            if (family < 0 || !hasSwapchain(candidate, stack)) continue;
            vkGetPhysicalDeviceProperties(candidate, properties);
            int score = Vk.score(properties.deviceType());
            if (score <= bestScore) continue;
            bestScore = score;
            physicalDevice = candidate;
            queueFamily = family;
            gpuName = properties.deviceNameString();
            apiVersion = VK_VERSION_MAJOR(properties.apiVersion()) + "." + VK_VERSION_MINOR(properties.apiVersion());
            int counts = properties.limits().framebufferColorSampleCounts() & properties.limits().framebufferDepthSampleCounts();
            maxSamples = Integer.highestOneBit(counts & 0x7F);
        }
        if (physicalDevice == null) throw new VulkanUnavailableException("no graphics card can show Vulkan in a window");

        memoryProperties = VkPhysicalDeviceMemoryProperties.malloc();
        vkGetPhysicalDeviceMemoryProperties(physicalDevice, memoryProperties);
        depthFormat = firstSupported(stack, VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT,
                VK_FORMAT_D32_SFLOAT, VK_FORMAT_D24_UNORM_S8_UINT, VK_FORMAT_D32_SFLOAT_S8_UINT, VK_FORMAT_D16_UNORM);
        if (depthFormat < 0) throw new VulkanUnavailableException("the graphics card has no depth buffer format");
    }

    /** A queue family that can both draw and show pictures in the window, or -1. */
    private int drawingQueueFamily(VkPhysicalDevice candidate, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, families);
        IntBuffer presents = stack.mallocInt(1);
        for (int i = 0; i < families.capacity(); i++) {
            if ((families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) == 0) continue;
            vkGetPhysicalDeviceSurfaceSupportKHR(candidate, i, surface, presents);
            if (presents.get(0) == VK_TRUE) return i;
        }
        return -1;
    }

    private static boolean hasSwapchain(VkPhysicalDevice candidate, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkEnumerateDeviceExtensionProperties(candidate, (String) null, count, null);
        VkExtensionProperties.Buffer extensions = VkExtensionProperties.malloc(count.get(0), stack);
        vkEnumerateDeviceExtensionProperties(candidate, (String) null, count, extensions);
        for (VkExtensionProperties extension : extensions) {
            if (extension.extensionNameString().equals(VK_KHR_SWAPCHAIN_EXTENSION_NAME)) return true;
        }
        return false;
    }

    private void createDevice(MemoryStack stack) {
        VkPhysicalDeviceFeatures supported = VkPhysicalDeviceFeatures.malloc(stack);
        vkGetPhysicalDeviceFeatures(physicalDevice, supported);
        wireframe = supported.fillModeNonSolid();

        VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(1, stack).sType$Default()
                .queueFamilyIndex(queueFamily).pQueuePriorities(stack.floats(1f));
        VkDeviceCreateInfo info = VkDeviceCreateInfo.calloc(stack).sType$Default()
                .pQueueCreateInfos(queues)
                .pEnabledFeatures(VkPhysicalDeviceFeatures.calloc(stack).fillModeNonSolid(wireframe))
                .ppEnabledExtensionNames(stack.pointers(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME)));
        PointerBuffer pointer = stack.mallocPointer(1);
        check(vkCreateDevice(physicalDevice, info, null, pointer), "open the graphics card");
        device = new VkDevice(pointer.get(0), physicalDevice, info);
        vkGetDeviceQueue(device, queueFamily, 0, pointer);
        queue = new VkQueue(pointer.get(0), device);
    }

    /** The first format the GPU supports with these features (optimal tiling), or -1. */
    int firstSupported(MemoryStack stack, int features, int... formats) {
        VkFormatProperties properties = VkFormatProperties.malloc(stack);
        for (int format : formats) {
            vkGetPhysicalDeviceFormatProperties(physicalDevice, format, properties);
            if ((properties.optimalTilingFeatures() & features) == features) return format;
        }
        return -1;
    }

    boolean supports(int format, int features) {
        try (MemoryStack stack = stackPush()) {
            return firstSupported(stack, features, format) == format;
        }
    }

    // ---------------------------------------------------------------- buffers and images

    private int memoryType(int typeBits, int properties) {
        for (int i = 0; i < memoryProperties.memoryTypeCount(); i++) {
            boolean allowed = (typeBits & (1 << i)) != 0;
            if (allowed && (memoryProperties.memoryTypes(i).propertyFlags() & properties) == properties) return i;
        }
        throw new IllegalStateException("The graphics card has no suitable memory");
    }

    private long allocate(MemoryStack stack, VkMemoryRequirements requirements, int properties) {
        VkMemoryAllocateInfo info = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                .allocationSize(requirements.size())
                .memoryTypeIndex(memoryType(requirements.memoryTypeBits(), properties));
        LongBuffer memory = stack.mallocLong(1);
        check(vkAllocateMemory(device, info, null, memory), "allocate graphics memory");
        return memory.get(0);
    }

    Buffer buffer(long size, int usage, int memoryProperties) {
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer handle = stack.mallocLong(1);
            check(vkCreateBuffer(device, info, null, handle), "create a buffer");
            VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device, handle.get(0), requirements);
            long memory = allocate(stack, requirements, memoryProperties);
            vkBindBufferMemory(device, handle.get(0), memory, 0);
            return new Buffer(handle.get(0), memory, size);
        }
    }

    void destroy(Buffer buffer) {
        vkDestroyBuffer(device, buffer.handle(), null);
        vkFreeMemory(device, buffer.memory(), null);
    }

    Image image(int width, int height, int mipLevels, int samples, int format, int usage, int aspect) {
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D).format(format).mipLevels(mipLevels).arrayLayers(1)
                    .samples(samples).tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(width, height, 1);
            LongBuffer handle = stack.mallocLong(1);
            check(vkCreateImage(device, info, null, handle), "create an image");
            long image = handle.get(0);
            VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(device, image, requirements);
            long memory = allocate(stack, requirements, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            vkBindImageMemory(device, image, memory, 0);

            VkImageViewCreateInfo view = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
            view.subresourceRange().set(aspect, 0, mipLevels, 0, 1);
            check(vkCreateImageView(device, view, null, handle), "create an image view");
            return new Image(image, memory, handle.get(0));
        }
    }

    void destroy(Image image) {
        vkDestroyImageView(device, image.view(), null);
        vkDestroyImage(device, image.handle(), null);
        vkFreeMemory(device, image.memory(), null);
    }

    /** Records a layout change (and the waiting it needs) for some mip levels of a color or depth image. */
    static void barrier(VkCommandBuffer commands, long image, int aspect, int firstMip, int mipCount,
                        int oldLayout, int newLayout, int srcAccess, int dstAccess, int srcStage, int dstStage) {
        try (MemoryStack stack = stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .oldLayout(oldLayout).newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image).srcAccessMask(srcAccess).dstAccessMask(dstAccess);
            barrier.subresourceRange().set(aspect, firstMip, mipCount, 0, 1);
            vkCmdPipelineBarrier(commands, srcStage, dstStage, 0, null, null, barrier);
        }
    }

    /** Runs some GPU commands right away and waits for them (uploads). Fine outside or during a frame. */
    void runNow(Consumer<VkCommandBuffer> recorder) {
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo allocate = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            PointerBuffer pointer = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, allocate, pointer), "allocate commands");
            VkCommandBuffer commands = new VkCommandBuffer(pointer.get(0), device);
            vkBeginCommandBuffer(commands, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));
            recorder.accept(commands);
            vkEndCommandBuffer(commands);
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(pointer);
            check(vkQueueSubmit(queue, submit, VK_NULL_HANDLE), "send work to the graphics card");
            vkQueueWaitIdle(queue);
            vkFreeCommandBuffers(device, commandPool, pointer);
        }
    }

    // ---------------------------------------------------------------- frames in flight

    /** Destroys something once no frame in flight can still be using it. */
    void later(Runnable destroy) {
        pendingDestroys.get(frameSlot).add(destroy);
    }

    /** Called when a frame starts in this slot, after waiting for the GPU to finish the slot's last frame. */
    void startFrame(int slot) {
        frameSlot = slot;
        List<Runnable> due = pendingDestroys.get(slot);
        List<Runnable> now = new ArrayList<>(due);
        due.clear();
        now.forEach(Runnable::run);
    }

    /** Waits for the GPU to finish everything, then destroys everything waiting in later(). */
    void finishAll() {
        vkDeviceWaitIdle(device);
        for (List<Runnable> slot : pendingDestroys) {
            List<Runnable> now = new ArrayList<>(slot);
            slot.clear();
            now.forEach(Runnable::run);
        }
    }

    static void check(int result, String what) {
        if (result != VK_SUCCESS) throw new IllegalStateException("Vulkan couldn't " + what + " (" + Vk.name(result) + ")");
    }

    @Override
    public void close() {
        if (device != null) {
            finishAll();
            if (commandPool != 0) vkDestroyCommandPool(device, commandPool, null);
            vkDestroyDevice(device, null);
            device = null;
        }
        if (memoryProperties != null) {
            memoryProperties.free();
            memoryProperties = null;
        }
        if (instance != null) {
            if (surface != 0) vkDestroySurfaceKHR(instance, surface, null);
            if (debugMessenger != 0) vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, null);
            vkDestroyInstance(instance, null);
            instance = null;
        }
        if (debugCallback != null) {
            debugCallback.free();
            debugCallback = null;
        }
    }
}
