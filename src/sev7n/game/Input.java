package sev7n.game;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Keyboard and mouse state for the running game.
 *
 * "Held" means down right now. "Pressed" / "released" are true for exactly one
 * frame, which is what you want for jumping, shooting, menus...
 */
public final class Input {

    private static final Map<Integer, String> KEY_NAMES = new HashMap<>();
    private static final Map<String, String> ALIASES = Map.of("ESC", "ESCAPE", "RETURN", "ENTER", "CONTROL", "CTRL");

    static {
        for (int key = GLFW_KEY_A; key <= GLFW_KEY_Z; key++) KEY_NAMES.put(key, String.valueOf((char) key));
        for (int key = GLFW_KEY_0; key <= GLFW_KEY_9; key++) KEY_NAMES.put(key, String.valueOf((char) key));
        KEY_NAMES.put(GLFW_KEY_LEFT, "LEFT");
        KEY_NAMES.put(GLFW_KEY_RIGHT, "RIGHT");
        KEY_NAMES.put(GLFW_KEY_UP, "UP");
        KEY_NAMES.put(GLFW_KEY_DOWN, "DOWN");
        KEY_NAMES.put(GLFW_KEY_SPACE, "SPACE");
        KEY_NAMES.put(GLFW_KEY_ENTER, "ENTER");
        KEY_NAMES.put(GLFW_KEY_ESCAPE, "ESCAPE");
        KEY_NAMES.put(GLFW_KEY_TAB, "TAB");
        KEY_NAMES.put(GLFW_KEY_BACKSPACE, "BACKSPACE");
        KEY_NAMES.put(GLFW_KEY_LEFT_SHIFT, "SHIFT");
        KEY_NAMES.put(GLFW_KEY_RIGHT_SHIFT, "SHIFT");
        KEY_NAMES.put(GLFW_KEY_LEFT_CONTROL, "CTRL");
        KEY_NAMES.put(GLFW_KEY_RIGHT_CONTROL, "CTRL");
        KEY_NAMES.put(GLFW_KEY_LEFT_ALT, "ALT");
        KEY_NAMES.put(GLFW_KEY_RIGHT_ALT, "ALT");
    }

    private final Set<String> held = new HashSet<>();
    private final Set<String> pressed = new HashSet<>();
    private final Set<String> released = new HashSet<>();

    private double mouseX;
    private double mouseY;
    private boolean mouseDown;
    private boolean mouseClicked;

    // ---- fed by the window ----

    /** A GLFW key event (GLFW_PRESS, GLFW_RELEASE or GLFW_REPEAT). */
    public void keyEvent(int glfwKey, int action) {
        String name = KEY_NAMES.get(glfwKey);
        if (name == null) return;
        if (action == GLFW_PRESS && held.add(name)) pressed.add(name);
        if (action == GLFW_RELEASE && held.remove(name)) released.add(name);
    }

    /** Mouse position in game-view pixels, (0, 0) at the top-left. */
    private double viewWidth = 1280;
    private double viewHeight = 720;

    /** How big the game's view is in pixels (mouse positions are in the same pixels). */
    public void setViewSize(double width, double height) {
        if (width > 0 && height > 0) {
            viewWidth = width;
            viewHeight = height;
        }
    }

    double viewWidth() {
        return viewWidth;
    }

    double viewHeight() {
        return viewHeight;
    }

    public void mouseMoved(double x, double y) {
        mouseX = x;
        mouseY = y;
    }

    public void mouseButton(boolean down) {
        if (down && !mouseDown) mouseClicked = true;
        mouseDown = down;
    }

    /** Clears the one-frame states. Called after each game update. */
    public void endFrame() {
        pressed.clear();
        released.clear();
        mouseClicked = false;
    }

    /** Lets go of everything, e.g. when the game view loses focus. */
    public void reset() {
        for (String key : held) released.add(key);
        held.clear();
        mouseDown = false;
    }

    // ---- queried by scripts ----

    public boolean isHeld(String key) {
        return held.contains(normalize(key));
    }

    public boolean wasPressed(String key) {
        return pressed.contains(normalize(key));
    }

    public boolean wasReleased(String key) {
        return released.contains(normalize(key));
    }

    public double mouseX() {
        return mouseX;
    }

    public double mouseY() {
        return mouseY;
    }

    public boolean isMouseDown() {
        return mouseDown;
    }

    public boolean wasMouseClicked() {
        return mouseClicked;
    }

    private static String normalize(String key) {
        String upper = key.toUpperCase().replace(" ", "");
        return ALIASES.getOrDefault(upper, upper);
    }
}
