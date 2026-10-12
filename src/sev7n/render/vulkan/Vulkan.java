package sev7n.render.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Whether Vulkan works on this PC, for the Settings window. */
public final class Vulkan {

    private Vulkan() { }

    /** 'detail' is the graphics card and Vulkan version, or why Vulkan can't run. */
    public record Support(boolean available, String detail) { }

    private static Support cached;

    /** Asks the driver once (takes a moment, so call it off the UI thread), then remembers the answer. */
    public static synchronized Support check() {
        if (cached == null) cached = ask();
        return cached;
    }

    private static Support ask() {
        try (MemoryStack stack = stackPush()) {
            VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default()
                    .pApplicationName(stack.UTF8("Sev7n")).apiVersion(VK_API_VERSION_1_0);
            VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app);
            PointerBuffer pointer = stack.mallocPointer(1);
            int result = vkCreateInstance(info, null, pointer);
            if (result != VK_SUCCESS) return new Support(false, "No Vulkan driver (" + Vk.name(result) + ")");
            VkInstance instance = new VkInstance(pointer.get(0), info);
            try {
                IntBuffer count = stack.mallocInt(1);
                vkEnumeratePhysicalDevices(instance, count, null);
                if (count.get(0) == 0) return new Support(false, "No graphics card supports Vulkan");
                PointerBuffer devices = stack.mallocPointer(count.get(0));
                vkEnumeratePhysicalDevices(instance, count, devices);
                VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
                String best = null;
                int bestScore = -1;
                for (int i = 0; i < devices.capacity(); i++) {
                    vkGetPhysicalDeviceProperties(new VkPhysicalDevice(devices.get(i), instance), properties);
                    int score = Vk.score(properties.deviceType());
                    if (score <= bestScore) continue;
                    bestScore = score;
                    best = properties.deviceNameString() + ", Vulkan "
                            + VK_VERSION_MAJOR(properties.apiVersion()) + "." + VK_VERSION_MINOR(properties.apiVersion());
                }
                return new Support(true, best);
            } finally {
                vkDestroyInstance(instance, null);
            }
        } catch (RuntimeException | LinkageError e) {
            return new Support(false, "Vulkan isn't installed (" + e.getMessage() + ")");
        }
    }
}
