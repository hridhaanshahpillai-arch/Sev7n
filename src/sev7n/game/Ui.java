package sev7n.game;

import sev7n.scene.Hud;

import java.awt.Color;

/**
 * Buttons, sliders and checkboxes on the HUD ("immediate mode", like Dear ImGui): a script calls
 * button(...) every frame it wants the button shown, and gets true back on the frame it's
 * clicked. Nothing is kept between frames except which control the mouse is holding, so a menu
 * is just an if around some calls.
 *
 * A click counts when the mouse goes down and comes back up on the same control (like every
 * desktop button), so sliding off a button cancels it. Controls are told apart by what they say
 * and where they are.
 */
final class Ui {

    static final Color PANEL = new Color(0x21, 0x22, 0x2C, 235);
    static final Color CONTROL = new Color(0x44475A);
    static final Color HOVER = new Color(0x5A5E78);
    static final Color ACCENT = new Color(0xBF9EEE);
    static final Color ACCENT_HOVER = new Color(0xD2B8F5);
    static final Color TEXT = new Color(0xF8F8F2);
    static final Color DARK_TEXT = new Color(0x282A36);
    static final Color MUTED = new Color(0x9A9AB4);
    private static final double ROUND = 10;

    private final Input input;
    private final Hud hud;
    private double mouseX;
    private double mouseY;
    private boolean pressed;   // went down this frame
    private boolean released;  // came up this frame
    private boolean wasDown;
    private String holding;    // the control the mouse went down on, while it's held
    private boolean overControl;

    Ui(Input input, Hud hud) {
        this.input = input;
        this.hud = hud;
    }

    /** Where the mouse is in screen units, and what it did this frame. */
    void beginFrame() {
        mouseX = input.mouseX() / input.viewWidth() * hud.width();
        mouseY = input.mouseY() / input.viewHeight() * Hud.HEIGHT;
        boolean down = input.isMouseDown();
        pressed = input.wasMouseClicked();
        released = wasDown && !down;
        wasDown = down;
        overControl = false;
    }

    void endFrame() {
        if (!input.isMouseDown()) holding = null;
    }

    double mouseX() {
        return mouseX;
    }

    double mouseY() {
        return mouseY;
    }

    /** True if the mouse is over any control drawn this frame (so a click there isn't also a punch). */
    boolean mouseOverUi() {
        return overControl;
    }

    boolean inside(double x, double y, double w, double h) {
        return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
    }

    /** A rounded box with a faint edge: the background of a menu or window. */
    void panel(double x, double y, double w, double h, Color color) {
        hud.add(new Hud.Box(x - 1.5, y - 1.5, w + 3, h + 3, new Color(255, 255, 255, 28), ROUND + 1.5));
        hud.add(new Hud.Box(x, y, w, h, color, ROUND));
        if (inside(x, y, w, h)) overControl = true;
    }

    /** A button with a label; true on the frame it's clicked. 'primary' ones are purple. */
    boolean button(String label, double x, double y, double w, double h, boolean primary) {
        String id = "button " + label + " " + Math.round(x) + "," + Math.round(y);
        boolean hovered = inside(x, y, w, h);
        if (hovered) overControl = true;
        if (hovered && pressed) holding = id;
        boolean held = id.equals(holding) && input.isMouseDown();
        boolean clicked = released && hovered && id.equals(holding);

        Color fill = primary ? (hovered ? ACCENT_HOVER : ACCENT) : (hovered ? HOVER : CONTROL);
        double sink = held && hovered ? 2 : 0; // pressed in a little while held
        if (!held) hud.add(new Hud.Box(x, y + 3, w, h, new Color(0, 0, 0, 70), ROUND)); // shadow
        hud.add(new Hud.Box(x, y + sink, w, h, fill, ROUND));
        double size = Math.min(h * 0.45, 30);
        hud.add(new Hud.Text(label, x + w / 2, y + sink + (h - Hud.textHeight(size)) / 2, size, primary ? DARK_TEXT : TEXT, "center", !primary));
        return clicked;
    }

    /** A slider from 'min' to 'max': drag it (or click the track); returns the new value. */
    double slider(String label, double x, double y, double w, double value, double min, double max) {
        String id = "slider " + label + " " + Math.round(x) + "," + Math.round(y);
        double h = 44;
        double trackY = y + 30;
        boolean hovered = inside(x - 8, y, w + 16, h);
        if (hovered) overControl = true;
        if (hovered && pressed) holding = id;
        if (id.equals(holding) && input.isMouseDown() && max > min) {
            value = min + Math.max(0, Math.min(1, (mouseX - x) / w)) * (max - min);
        }
        double amount = max > min ? Math.max(0, Math.min(1, (value - min) / (max - min))) : 0;

        hud.add(new Hud.Text(label, x, y - 4, 22, MUTED, "left"));
        hud.add(new Hud.Text(format(value), x + w, y - 4, 22, TEXT, "right"));
        hud.add(new Hud.Box(x, trackY, w, 8, CONTROL, 4));
        hud.add(new Hud.Box(x, trackY, w * amount, 8, ACCENT, 4));
        double knob = hovered || id.equals(holding) ? 22 : 18;
        hud.add(new Hud.Box(x + w * amount - knob / 2, trackY + 4 - knob / 2, knob, knob, TEXT, knob / 2));
        return value;
    }

    /** A box that ticks on and off when clicked (with its label); returns the new value. */
    boolean checkbox(String label, double x, double y, boolean value) {
        String id = "checkbox " + label + " " + Math.round(x) + "," + Math.round(y);
        double box = 28;
        double size = 24;
        double w = box + 12 + Hud.textWidth(label, size);
        boolean hovered = inside(x, y, w, box);
        if (hovered) overControl = true;
        if (hovered && pressed) holding = id;
        if (released && hovered && id.equals(holding)) value = !value;

        hud.add(new Hud.Box(x, y, box, box, value ? ACCENT : hovered ? HOVER : CONTROL, 7));
        if (value) hud.add(new Hud.Box(x + 8, y + 8, box - 16, box - 16, DARK_TEXT, 3)); // a dot in the middle when ticked
        hud.add(new Hud.Text(label, x + box + 12, y + (box - Hud.textHeight(size)) / 2, size, TEXT, "left"));
        return value;
    }

    private static String format(double value) {
        return Math.abs(value - Math.rint(value)) < 1e-9 ? String.valueOf((long) Math.rint(value)) : String.format("%.2f", value);
    }
}
