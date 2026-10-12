package sev7n.render;

import static org.lwjgl.glfw.GLFW.glfwGetTime;

/** Caps the frame rate ("Max FPS"), for the editor and for games. */
public final class FrameLimiter {

    private FrameLimiter() { }

    /**
     * Waits until the next frame is due.
     *
     * @param maxFps     frames per second, or 0 for no limit
     * @param frameStart glfwGetTime() when this frame started
     */
    public static void waitForNextFrame(int maxFps, double frameStart) {
        if (maxFps <= 0) return;
        double due = frameStart + 1.0 / maxFps;
        while (true) {
            double left = due - glfwGetTime();
            if (left <= 0) return;
            if (left > 0.002) {
                try {
                    Thread.sleep((long) ((left - 0.001) * 1000)); // sleep most of the way...
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } else {
                Thread.onSpinWait(); // ...then wait the last moment precisely
            }
        }
    }
}
