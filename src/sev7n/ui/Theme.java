package sev7n.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Set;

/**
 * Sev7n's look, based on the Dracula Soft palette: blue-grey darks,
 * soft white text, and a purple accent.
 *
 * Every color in the app should come from here so the theme stays consistent.
 */
public final class Theme {

    private Theme() { }

    // ---- Dracula Soft palette ----
    private static final Color PINK = new Color(0xF286C4);
    private static final Color PURPLE = new Color(0xBF9EEE);
    private static final Color CYAN = new Color(0x97E1F1);
    private static final Color GREEN = new Color(0x62E884);
    private static final Color YELLOW = new Color(0xE7EE98);
    private static final Color RED = new Color(0xEE6666);

    // ---- surfaces, darkest to lightest ----
    public static final Color BLACK = new Color(0x191A21);       // game letterbox, dividers, status bar
    public static final Color PANEL = new Color(0x21222C);       // toolbar, console, headers
    public static final Color EDITOR = new Color(0x282A36);      // code editor background
    public static final Color SURFACE = new Color(0x343746);     // buttons, hover states
    public static final Color SURFACE_HI = new Color(0x424558);  // pressed states
    public static final Color BORDER = new Color(0x191A21);      // hairlines between panels

    // ---- text ----
    public static final Color TEXT = new Color(0xF6F6F4);
    public static final Color TEXT_MUTED = new Color(0xA9ABB6);
    public static final Color TEXT_FAINT = new Color(0x7B7F8B);

    // ---- accents & states ----
    public static final Color ACCENT = PURPLE;
    public static final Color ACCENT_HOVER = new Color(0xD0B8F4);
    public static final Color ERROR = RED;
    public static final Color SUCCESS = GREEN;

    // ---- editor ----
    public static final Color SELECTION = new Color(0x44475A);
    public static final Color CURRENT_LINE = new Color(0x333540);
    public static final Color ERROR_LINE = new Color(0x45303C);
    public static final Color INDENT_GUIDE = new Color(0x3B3D4A);
    public static final Color CARET = TEXT;

    // ---- syntax highlighting ----
    public static final Color SYNTAX_KEYWORD = PINK;      // if, else, while, return, and, or...
    public static final Color SYNTAX_OPERATOR = PINK;     // = + - * / < > ! & |
    public static final Color SYNTAX_DECLARATION = CYAN;  // var, func, style (italic)
    public static final Color SYNTAX_BUILTIN = CYAN;      // rect(), print(), key()...
    public static final Color SYNTAX_FUNCTION = GREEN;    // your own functions
    public static final Color SYNTAX_STRING = YELLOW;
    public static final Color SYNTAX_NUMBER = PURPLE;     // numbers, true, false, none, PI
    public static final Color SYNTAX_COMMENT = TEXT_FAINT;
    public static final Color SYNTAX_PROPERTY = CYAN;     // style properties: fill, radius...
    public static final Color SYNTAX_PLAIN = TEXT;

    // ---- guides ----
    public static final Color LINK = CYAN;
    public static final Color INLINE_CODE = GREEN;

    /** A UI font for drawing outside the editor, e.g. the app icon. */
    public static Font uiFont(int size, boolean bold) {
        return new Font(firstInstalled("Segoe UI", "Inter", "Helvetica Neue", Font.SANS_SERIF),
                bold ? Font.BOLD : Font.PLAIN, size);
    }

    private static String firstInstalled(String... families) {
        Set<String> installed = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String family : families) {
            if (installed.contains(family)) return family;
        }
        return families[families.length - 1];
    }
}
