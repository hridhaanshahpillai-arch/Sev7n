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

    // how far the mouse moved since the last game update (for mouse-look), and whether a script wants it captured
    private double moveX;
    private double moveY;
    private boolean hasPosition;
    private boolean mouseLocked;
    private boolean rightDown;
    private boolean rightClicked;

    /** Mouse position in game-view pixels, (0, 0) at the top-left. */
    public void mouseMoved(double x, double y) {
        if (hasPosition) {
            moveX += x - mouseX;
            moveY += y - mouseY;
        }
        hasPosition = true;
        mouseX = x;
        mouseY = y;
    }

    public void mouseButton(boolean down) {
        if (down && !mouseDown) mouseClicked = true;
        mouseDown = down;
    }

    public void rightMouseButton(boolean down) {
        if (down && !rightDown) rightClicked = true;
        rightDown = down;
    }

    /** True while a script wants the cursor hidden and held in place (mouse-look). The window does the hiding. */
    public boolean mouseLocked() {
        return mouseLocked;
    }

    public void setMouseLocked(boolean locked) {
        mouseLocked = locked;
    }

    /** Movement measured by the window itself (the editor keeps a captured cursor in the middle of the Game view). */
    public void addMouseMove(double dx, double dy) {
        moveX += dx;
        moveY += dy;
    }

    /** The cursor jumped (captured or let go): don't count the jump as movement. */
    public void forgetMousePosition() {
        hasPosition = false;
    }

    public double mouseMoveX() {
        return moveX;
    }

    public double mouseMoveY() {
        return moveY;
    }

    public boolean isRightMouseDown() {
        return rightDown;
    }

    public boolean wasRightMouseClicked() {
        return rightClicked;
    }

    // ---- gamepad (the first one plugged in; Xbox names: A B X Y, LB RB, BACK START, LS RS, UP DOWN LEFT RIGHT) ----

    private static final String[] PAD_BUTTONS = {"A", "B", "X", "Y", "LB", "RB", "BACK", "START", "GUIDE", "LS", "RS", "UP", "RIGHT", "DOWN", "LEFT"};
    private static final String[] PAD_AXES = {"LX", "LY", "RX", "RY", "LT", "RT"};
    private final Set<String> padHeld = new HashSet<>();
    private final Set<String> padPressed = new HashSet<>();
    private final Map<String, Double> padAxes = new HashMap<>();
    private boolean padConnected;
    private boolean padUnavailable; // no GLFW here (tests, headless): stop asking

    /** Reads the gamepad. The window calls this once per frame, before the game updates. */
    public void pollGamepad() {
        if (padUnavailable) return;
        try {
            int pad = -1;
            for (int j = GLFW_JOYSTICK_1; j <= GLFW_JOYSTICK_LAST && pad < 0; j++) if (glfwJoystickIsGamepad(j)) pad = j;
            padConnected = pad >= 0;
            if (!padConnected) {
                padHeld.clear();
                padAxes.clear();
                return;
            }
            try (org.lwjgl.glfw.GLFWGamepadState state = org.lwjgl.glfw.GLFWGamepadState.malloc()) {
                if (!glfwGetGamepadState(pad, state)) return;
                for (int b = 0; b < PAD_BUTTONS.length; b++) {
                    boolean down = state.buttons(b) == GLFW_PRESS;
                    if (down && padHeld.add(PAD_BUTTONS[b])) padPressed.add(PAD_BUTTONS[b]);
                    if (!down) padHeld.remove(PAD_BUTTONS[b]);
                }
                for (int a = 0; a < PAD_AXES.length; a++) {
                    double v = state.axes(a);
                    if (a >= 4) v = (v + 1) / 2;                   // triggers: 0 (let go) .. 1 (pulled all the way)
                    else if (Math.abs(v) < 0.15) v = 0;            // sticks: a little dead zone in the middle
                    padAxes.put(PAD_AXES[a], v);
                }
            }
        } catch (Throwable notHere) {
            padUnavailable = true;
        }
    }

    public boolean padConnected() {
        return padConnected;
    }

    public boolean padHeld(String button) {
        return padHeld.contains(button.toUpperCase());
    }

    public boolean padPressed(String button) {
        return padPressed.contains(button.toUpperCase());
    }

    /** A stick (LX, LY, RX, RY: -1..1, down is +1 on Y) or trigger (LT, RT: 0..1). */
    public double padAxis(String axis) {
        return padAxes.getOrDefault(axis.toUpperCase(), 0.0);
    }

    /** For tests: pretend a gamepad button is down / up, or a stick is pushed. */
    public void setPadButton(String button, boolean down) {
        padConnected = true;
        String name = button.toUpperCase();
        if (down && padHeld.add(name)) padPressed.add(name);
        if (!down) padHeld.remove(name);
    }

    public void setPadAxis(String axis, double value) {
        padConnected = true;
        padAxes.put(axis.toUpperCase(), value);
    }

    /** Clears the one-frame states. Called after each game update. */
    public void endFrame() {
        pressed.clear();
        released.clear();
        padPressed.clear();
        mouseClicked = false;
        rightClicked = false;
        moveX = 0;
        moveY = 0;
    }

    /** Lets go of everything, e.g. when the game view loses focus. */
    public void reset() {
        for (String key : held) released.add(key);
        held.clear();
        mouseDown = false;
        rightDown = false;
        mouseLocked = false;
        hasPosition = false;
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
