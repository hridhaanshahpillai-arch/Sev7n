package sev7n.editor;

import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import sev7n.render.Framebuffer;
import sev7n.render.RenderView;
import sev7n.render.SceneRenderer;
import sev7n.scene.Camera;
import sev7n.scene.Entity;
import sev7n.ui.Theme;

/**
 * What the player sees: the scene through its Main Camera.
 * While playing, this panel gets the keyboard and mouse when it's focused (click it).
 */
final class GameViewPanel {

    static final String TITLE = "Game";

    private final EditorContext ctx;
    private final Framebuffer framebuffer = new Framebuffer();
    private boolean focused = false;
    private boolean focusRequested = false;

    GameViewPanel(EditorContext ctx) {
        this.ctx = ctx;
    }

    /** True if the game should receive keyboard input. */
    boolean hasKeyboard() {
        return focused && ctx.playing();
    }

    void requestFocus() {
        focusRequested = true;
    }

    void draw() {
        if (focusRequested) {
            ImGui.setWindowFocus(TITLE);
            focusRequested = false;
        }
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        boolean visible = ImGui.begin(TITLE, ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse);
        ImGui.popStyleVar();
        boolean wasFocused = focused;
        focused = visible && ImGui.isWindowFocused();
        if (wasFocused && !focused) ctx.input.reset(); // don't leave keys stuck down

        if (visible) drawView();
        ImGui.end();
    }

    private void drawView() {
        ImVec2 origin = ImGui.getCursorScreenPos();
        float width = ImGui.getContentRegionAvailX();
        float height = ImGui.getContentRegionAvailY();
        if (width < 2 || height < 2) return;

        Entity cameraEntity = ctx.scene().mainCamera();
        if (cameraEntity == null) {
            ImGui.setCursorScreenPos(origin.x + 16, origin.y + 16);
            Widgets.hint("No camera in the scene. Add one with + > Camera in the Hierarchy.");
            return;
        }

        // render scale: fewer pixels than the panel, stretched (sharply when it's chunky retro pixels)
        double scale = ctx.graphics.renderScale;
        framebuffer.resize((int) Math.round(width * scale), (int) Math.round(height * scale),
                ctx.graphics.antiAliasing, scale < 1);
        framebuffer.bind();
        RenderView view = RenderView.fromCamera(cameraEntity, width / height);
        ctx.renderer.render(ctx.scene(), view, cameraEntity.get(Camera.class).background, SceneRenderer.Overlays.NONE);
        framebuffer.end();
        ImGui.image(framebuffer.texture(), width, height, 0, 1, 1, 0);
        boolean hovered = ImGui.isItemHovered();

        if (ctx.playing()) {
            ImVec2 mouse = ImGui.getMousePos();
            ctx.input.setViewSize(width, height);
            ctx.input.mouseMoved(mouse.x - origin.x, mouse.y - origin.y);
            ctx.input.mouseButton(focused && hovered && ImGui.isMouseDown(0));
            if (focused) {
                // a purple frame shows the game has the keyboard
                ImGui.getWindowDrawList().addRect(origin.x + 1, origin.y + 1, origin.x + width - 1, origin.y + height - 1,
                        EditorStyle.pack(Theme.ACCENT), 0, 0, 2);
            } else {
                drawBanner(origin, width, "Click here to control the game");
            }
        } else {
            drawBanner(origin, width, "Press Play (F5) to run the game");
        }
    }

    private void drawBanner(ImVec2 origin, float width, String text) {
        ImVec2 size = ImGui.calcTextSize(text);
        float x = origin.x + (width - size.x) / 2;
        float y = origin.y + 12;
        ImGui.getWindowDrawList().addRectFilled(x - 10, y - 4, x + size.x + 10, y + size.y + 4,
                EditorStyle.pack(EditorStyle.withAlpha(Theme.BLACK, 200)), 6);
        ImGui.getWindowDrawList().addText(x, y, EditorStyle.pack(Theme.TEXT_MUTED), text);
    }
}
