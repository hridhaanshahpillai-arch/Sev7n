package sev7n.render.vulkan;

import static org.lwjgl.vulkan.KHRSurface.VK_ERROR_SURFACE_LOST_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_SUBOPTIMAL_KHR;
import static org.lwjgl.vulkan.VK10.*;

/** Small Vulkan helpers shared by this package. */
final class Vk {

    private Vk() { }

    /** A readable name for a Vulkan result code, for error messages. */
    static String name(int result) {
        return switch (result) {
            case VK_SUCCESS -> "success";
            case VK_NOT_READY -> "not ready";
            case VK_TIMEOUT -> "timed out";
            case VK_INCOMPLETE -> "incomplete";
            case VK_ERROR_OUT_OF_HOST_MEMORY -> "out of memory";
            case VK_ERROR_OUT_OF_DEVICE_MEMORY -> "out of graphics memory";
            case VK_ERROR_INITIALIZATION_FAILED -> "initialization failed";
            case VK_ERROR_DEVICE_LOST -> "graphics card lost";
            case VK_ERROR_LAYER_NOT_PRESENT -> "layer not present";
            case VK_ERROR_EXTENSION_NOT_PRESENT -> "extension not present";
            case VK_ERROR_FEATURE_NOT_PRESENT -> "feature not present";
            case VK_ERROR_INCOMPATIBLE_DRIVER -> "driver doesn't support Vulkan";
            case VK_ERROR_FORMAT_NOT_SUPPORTED -> "format not supported";
            case VK_ERROR_SURFACE_LOST_KHR -> "window surface lost";
            case VK_ERROR_OUT_OF_DATE_KHR -> "window changed";
            case VK_SUBOPTIMAL_KHR -> "suboptimal";
            default -> "error " + result;
        };
    }

    /** How much we'd like to use a kind of GPU: a separate graphics card beats a built-in one. */
    static int score(int deviceType) {
        return switch (deviceType) {
            case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> 4;
            case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> 3;
            case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> 2;
            case VK_PHYSICAL_DEVICE_TYPE_CPU -> 1;
            default -> 0;
        };
    }
}
