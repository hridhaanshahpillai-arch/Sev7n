package sev7n.editor;

import imgui.ImFont;
import imgui.ImFontConfig;
import imgui.ImFontGlyphRangesBuilder;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImGuiStyle;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiDir;
import sev7n.ui.Theme;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The editor's look: Dracula Soft colors (from sev7n.ui.Theme), compact spacing,
 * and Windows' Segoe UI / Cascadia Mono fonts when available.
 */
final class EditorStyle {

    private static final Path FONTS = Path.of(System.getenv().getOrDefault("WINDIR", "C:\\Windows"), "Fonts");

    static ImFont uiFont;
    static ImFont codeFont;
    static ImFont boldFont;     // the guides' bold text and small headings
    static ImFont titleFont;    // a guide's big title
    static ImFont headingFont;  // a guide's section headings

    private EditorStyle() { }

    /** Loads fonts. Must run before the OpenGL backend builds the font texture. */
    static void loadFonts(ImGuiIO io, float scale) {
        short[] glyphs = glyphRanges(io);
        uiFont = addFont(io, 15 * scale, glyphs, "segoeui.ttf");
        codeFont = addFont(io, 15 * scale, glyphs, "CascadiaMono.ttf", "consola.ttf");
        boldFont = addFont(io, 15 * scale, glyphs, "segoeuib.ttf", "segoeui.ttf");
        titleFont = addFont(io, 26 * scale, glyphs, "segoeuib.ttf", "segoeui.ttf");
        headingFont = addFont(io, 19 * scale, glyphs, "segoeuib.ttf", "segoeui.ttf");
        if (uiFont != null) io.setFontDefault(uiFont);
    }

    /** Latin plus the punctuation, arrows and box-drawing characters the guides use (— → · ─ ▶). */
    private static short[] glyphRanges(ImGuiIO io) {
        ImFontGlyphRangesBuilder builder = new ImFontGlyphRangesBuilder();
        builder.addRanges(io.getFonts().getGlyphRangesDefault());
        builder.addRanges(new short[] {
                0x2000, 0x206F,                  // general punctuation
                0x2190, 0x21FF,                  // arrows
                0x2500, 0x25FF,                  // box drawing, shapes
                0 });
        return builder.buildRanges();
    }

    private static ImFont addFont(ImGuiIO io, float size, short[] glyphs, String... candidates) {
        for (String name : candidates) {
            Path file = FONTS.resolve(name);
            if (Files.exists(file)) return io.getFonts().addFontFromFileTTF(file.toString(), size, new ImFontConfig(), glyphs);
        }
        return io.getFonts().addFontDefault();
    }

    static void apply(float scale) {
        ImGuiStyle style = ImGui.getStyle();

        // compact spacing: more room for features
        style.setWindowPadding(8, 6);
        style.setFramePadding(6, 3);
        style.setItemSpacing(6, 4);
        style.setItemInnerSpacing(4, 4);
        style.setCellPadding(4, 2);
        style.setIndentSpacing(14);
        style.setScrollbarSize(10);

        style.setWindowRounding(0);
        style.setChildRounding(4);
        style.setFrameRounding(4);
        style.setPopupRounding(6);
        style.setScrollbarRounding(6);
        style.setGrabRounding(4);
        style.setTabRounding(4);

        style.setWindowBorderSize(1);
        style.setFrameBorderSize(0);
        style.setTabBorderSize(0);
        style.setSeparatorTextBorderSize(1);
        style.setWindowMenuButtonPosition(ImGuiDir.None);
        style.scaleAllSizes(scale);

        set(ImGuiCol.Text, Theme.TEXT);
        set(ImGuiCol.TextDisabled, Theme.TEXT_FAINT);
        set(ImGuiCol.WindowBg, Theme.PANEL);
        set(ImGuiCol.ChildBg, Theme.PANEL);
        set(ImGuiCol.PopupBg, Theme.PANEL);
        set(ImGuiCol.Border, Theme.BLACK);
        set(ImGuiCol.BorderShadow, transparent());
        set(ImGuiCol.FrameBg, Theme.EDITOR);
        set(ImGuiCol.FrameBgHovered, Theme.SURFACE);
        set(ImGuiCol.FrameBgActive, Theme.SURFACE_HI);
        set(ImGuiCol.TitleBg, Theme.BLACK);
        set(ImGuiCol.TitleBgActive, Theme.BLACK);
        set(ImGuiCol.TitleBgCollapsed, Theme.BLACK);
        set(ImGuiCol.MenuBarBg, Theme.BLACK);
        set(ImGuiCol.ScrollbarBg, transparent());
        set(ImGuiCol.ScrollbarGrab, Theme.SELECTION);
        set(ImGuiCol.ScrollbarGrabHovered, Theme.SURFACE_HI);
        set(ImGuiCol.ScrollbarGrabActive, Theme.TEXT_FAINT);
        set(ImGuiCol.CheckMark, Theme.ACCENT);
        set(ImGuiCol.SliderGrab, Theme.ACCENT);
        set(ImGuiCol.SliderGrabActive, Theme.ACCENT_HOVER);
        set(ImGuiCol.Button, Theme.SURFACE);
        set(ImGuiCol.ButtonHovered, Theme.SELECTION);
        set(ImGuiCol.ButtonActive, Theme.SURFACE_HI);
        set(ImGuiCol.Header, Theme.SURFACE);
        set(ImGuiCol.HeaderHovered, Theme.SELECTION);
        set(ImGuiCol.HeaderActive, Theme.SURFACE_HI);
        set(ImGuiCol.Separator, Theme.BLACK);
        set(ImGuiCol.SeparatorHovered, Theme.ACCENT);
        set(ImGuiCol.SeparatorActive, Theme.ACCENT_HOVER);
        set(ImGuiCol.ResizeGrip, transparent());
        set(ImGuiCol.ResizeGripHovered, Theme.ACCENT);
        set(ImGuiCol.ResizeGripActive, Theme.ACCENT_HOVER);
        set(ImGuiCol.Tab, Theme.BLACK);
        set(ImGuiCol.TabHovered, Theme.SURFACE);
        set(ImGuiCol.TabActive, Theme.PANEL);
        set(ImGuiCol.TabUnfocused, Theme.BLACK);
        set(ImGuiCol.TabUnfocusedActive, Theme.PANEL);
        set(ImGuiCol.DockingPreview, withAlpha(Theme.ACCENT, 120));
        set(ImGuiCol.DockingEmptyBg, Theme.BLACK);
        set(ImGuiCol.TableHeaderBg, Theme.BLACK);
        set(ImGuiCol.TableBorderStrong, Theme.BLACK);
        set(ImGuiCol.TableBorderLight, Theme.BLACK);
        set(ImGuiCol.TextSelectedBg, withAlpha(Theme.ACCENT, 90));
        set(ImGuiCol.DragDropTarget, Theme.ACCENT);
        set(ImGuiCol.NavHighlight, Theme.ACCENT);
        set(ImGuiCol.ModalWindowDimBg, new Color(0, 0, 0, 120));
    }

    // ---------------------------------------------------------------- color helpers

    /** ImGui packs colors as 0xAABBGGRR. */
    static int pack(Color c) {
        return (c.getAlpha() << 24) | (c.getBlue() << 16) | (c.getGreen() << 8) | c.getRed();
    }

    static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    private static Color transparent() {
        return new Color(0, 0, 0, 0);
    }

    private static void set(int slot, Color c) {
        ImGui.getStyle().setColor(slot, c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha());
    }
}
