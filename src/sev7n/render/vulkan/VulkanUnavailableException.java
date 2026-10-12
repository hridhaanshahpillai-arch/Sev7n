package sev7n.render.vulkan;

/** Vulkan can't run on this PC (no driver, no suitable graphics card...). Games fall back to OpenGL. */
public final class VulkanUnavailableException extends RuntimeException {

    public VulkanUnavailableException(String message) {
        super(message);
    }

    public VulkanUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
