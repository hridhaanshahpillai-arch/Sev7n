package sev7n.editor;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiInputTextFlags;
import imgui.type.ImString;
import sev7n.math.Vec3;
import sev7n.project.AssetKind;
import sev7n.project.Blender;
import sev7n.project.Project;
import sev7n.render.Texture;
import sev7n.ui.Theme;

import java.awt.Color;
import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The project's files, as a browser with thumbnails (like Unity's Project window).
 *
 *   double-click a folder to open it · double-click a script or graph to edit it
 *   drag a picture or model into the Scene or Hierarchy · drag a script onto an object
 *   Import... or drop files from Explorer onto the window to bring in new assets
 */
final class ProjectPanel {

    static final String TITLE = "Project";
    private static final float TILE = 76;
    private static final long RELIST_EVERY_MS = 1500;

    private enum NewKind { SCRIPT, GRAPH }

    private final EditorContext ctx;
    private final ImString newName = new ImString(64);
    private NewKind newKind = NewKind.SCRIPT;
    private String createError = null;

    private Path folder;                         // the folder being shown
    private List<Path> entries = List.of();
    private Path listedFolder;
    private long listedAt;
    private int listedVersion = -1;
    private Path menuTarget;                     // the tile a right-click menu is for

    ProjectPanel(EditorContext ctx) {
        this.ctx = ctx;
        this.folder = ctx.project.root();
    }

    void draw() {
        if (!ImGui.begin(TITLE)) {
            ImGui.end();
            return;
        }
        drawToolbar();
        drawBreadcrumbs();
        ImGui.separator();

        ImGui.beginChild("##files");
        drawTiles();
        drawTileMenu(); // must be inside the same child as the tiles that open it
        ImGui.endChild();
        ImGui.end();
    }

    // ---------------------------------------------------------------- toolbar

    private void drawToolbar() {
        if (ImGui.button("+ Script")) startCreating(NewKind.SCRIPT);
        if (ImGui.isItemHovered()) ImGui.setTooltip("A behaviour written in Seven code");
        ImGui.sameLine();
        if (ImGui.button("+ Eights")) startCreating(NewKind.GRAPH);
        if (ImGui.isItemHovered()) ImGui.setTooltip("A behaviour made by connecting nodes (visual scripting)");
        ImGui.sameLine();
        if (ImGui.button("Import...")) importWithDialog(ctx);
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Pictures, .glb / .gltf / .obj models, .blend / .fbx files, .zip / .rar / .7z packs, folders.\n"
                    + "You can also drag files from Explorer onto the Sev7n window.");
        }
        ImGui.sameLine();
        if (ImGui.button("Show in Explorer")) showInExplorer(folder);
        if (ctx.importing()) {
            ImGui.sameLine();
            ImGui.textColored(EditorStyle.pack(Theme.ACCENT), "Importing...");
        }
        drawNewPopup();
    }

    /** Opens Windows' file dialog and imports whatever is picked. */
    static void importWithDialog(EditorContext ctx) {
        FilePicker.chooseFiles("Import into " + ctx.project.name(), files -> ctx.post(() -> ctx.importFiles(files)));
    }

    /** "MyGame / assets / textures": click a part to jump back up. */
    private void drawBreadcrumbs() {
        Path root = ctx.project.root();
        if (!folder.startsWith(root) || !Files.isDirectory(folder)) folder = root;
        List<Path> trail = new ArrayList<>();
        for (Path p = folder; p != null && p.startsWith(root); p = p.getParent()) trail.add(0, p);
        for (int i = 0; i < trail.size(); i++) {
            Path step = trail.get(i);
            if (i > 0) {
                ImGui.sameLine(0, 4);
                ImGui.textDisabled("/");
                ImGui.sameLine(0, 4);
            }
            String label = step.equals(root) ? ctx.project.name() : step.getFileName().toString();
            if (i == trail.size() - 1) ImGui.text(label);
            else if (ImGui.smallButton(label + "##crumb" + i)) folder = step;
        }
    }

    // ---------------------------------------------------------------- tiles

    private void drawTiles() {
        List<Path> files = listFolder();
        float scale = ImGui.getFontSize() / 15f;
        float tile = TILE * scale;
        float spacing = ImGui.getStyle().getItemSpacingX();
        int columns = Math.max(1, (int) ((ImGui.getContentRegionAvailX() + spacing) / (tile + spacing)));

        if (!folder.equals(ctx.project.root())) {
            files = new ArrayList<>(files);
            files.add(0, folder.getParent()); // ".." tile
        }
        for (int i = 0; i < files.size(); i++) {
            if (i % columns != 0) ImGui.sameLine();
            Path file = files.get(i);
            boolean up = i == 0 && !folder.equals(ctx.project.root());
            ImGui.pushID(file.toString() + i);
            drawTile(file, up, tile);
            ImGui.popID();
        }
        if (files.isEmpty()) Widgets.hint("This folder is empty. Click Import... or drop files onto the window.");
    }

    private void drawTile(Path file, boolean up, float size) {
        boolean directory = Files.isDirectory(file);
        String name = up ? ".." : file.getFileName().toString();
        float labelHeight = ImGui.getTextLineHeight() + 4;

        ImGui.invisibleButton("##tile", size, size + labelHeight);
        boolean hovered = ImGui.isItemHovered();
        ImVec2 min = ImGui.getItemRectMin();
        ImDrawList draw = ImGui.getWindowDrawList();
        if (hovered) draw.addRectFilled(min.x, min.y, min.x + size, min.y + size + labelHeight, EditorStyle.pack(Theme.SURFACE), 6);

        float pad = size * 0.14f;
        drawIcon(draw, file, directory, up, min.x + pad, min.y + pad, size - pad * 2);
        String label = fit(name, size - 6);
        float textWidth = ImGui.calcTextSize(label).x;
        draw.addText(min.x + (size - textWidth) / 2, min.y + size, EditorStyle.pack(Theme.TEXT), label);

        if (hovered) ImGui.setTooltip(up ? "Up one folder" : name + "\n" + (directory ? "Folder" : AssetKind.of(file).label));
        if (hovered && ImGui.isMouseDoubleClicked(0)) open(file, directory);
        if (!up && ImGui.isItemClicked(1)) {
            menuTarget = file;
            ImGui.openPopup("tile-menu");
        }
        if (!up && !directory) dragSource(file, name);
    }

    private void open(Path file, boolean directory) {
        if (directory) {
            folder = file;
            return;
        }
        switch (AssetKind.of(file)) {
            case SCRIPT -> ctx.openScript(file, 0);
            case TEXTURE, MODEL -> ctx.createFromAsset(ctx.project.relative(file), new Vec3());
            default -> showInExplorer(file);
        }
    }

    /** Scripts drag as behaviours; pictures and models drag as assets. */
    private void dragSource(Path file, String name) {
        AssetKind kind = AssetKind.of(file);
        if (kind != AssetKind.SCRIPT && kind != AssetKind.TEXTURE && kind != AssetKind.MODEL) return;
        if (!ImGui.beginDragDropSource()) return;
        if (kind == AssetKind.SCRIPT) {
            String behaviour = Project.behaviourName(file);
            ImGui.setDragDropPayload(DragTypes.SCRIPT, behaviour);
            ImGui.text("Attach " + behaviour);
        } else {
            ImGui.setDragDropPayload(DragTypes.ASSET, ctx.project.relative(file));
            ImGui.text(kind == AssetKind.TEXTURE ? "Picture: " + name : "Model: " + name);
        }
        ImGui.endDragDropSource();
    }

    private void drawTileMenu() {
        if (!ImGui.beginPopup("tile-menu")) return;
        Path file = menuTarget;
        if (file == null) {
            ImGui.closeCurrentPopup();
            ImGui.endPopup();
            return;
        }
        AssetKind kind = Files.isDirectory(file) ? AssetKind.OTHER : AssetKind.of(file);
        ImGui.textDisabled(file.getFileName().toString());
        if ((kind == AssetKind.TEXTURE || kind == AssetKind.MODEL) && ImGui.menuItem("Add to Scene")) {
            ctx.createFromAsset(ctx.project.relative(file), new Vec3());
        }
        if (kind.convertible() && ImGui.menuItem("Convert to .glb with Blender")) ctx.convertModel(file);
        if (kind.convertible() && Blender.find().isEmpty()) ImGui.textDisabled("(needs Blender: blender.org)");
        if (ImGui.menuItem("Show in Explorer")) showInExplorer(file);
        ImGui.separator();
        if (ImGui.menuItem("Move to Recycle Bin")) recycle(file);
        ImGui.endPopup();
    }

    private void recycle(Path file) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
            ctx.log.warning("This system can't use the Recycle Bin; delete " + file.getFileName() + " in Explorer instead");
            return;
        }
        if (Desktop.getDesktop().moveToTrash(file.toFile())) {
            ctx.log.system("Moved " + file.getFileName() + " to the Recycle Bin");
            listedFolder = null; // look again
        } else {
            ctx.log.warning("Couldn't move " + file.getFileName() + " to the Recycle Bin");
        }
    }

    // ---------------------------------------------------------------- icons

    private void drawIcon(ImDrawList draw, Path file, boolean directory, boolean up, float x, float y, float size) {
        if (up || directory) {
            int color = EditorStyle.pack(new Color(0xE7EE98));
            draw.addRectFilled(x, y + size * 0.12f, x + size * 0.45f, y + size * 0.3f, color, 3);
            draw.addRectFilled(x, y + size * 0.22f, x + size, y + size * 0.85f, color, 4);
            if (up) centeredText(draw, "..", x, y + size * 0.1f, size, Theme.BLACK);
            return;
        }
        AssetKind kind = AssetKind.of(file);
        if (kind == AssetKind.TEXTURE) {
            Texture texture = ctx.texture(ctx.project.relative(file), false, false);
            if (texture != null) {
                // keep the picture's shape inside the square
                float w = texture.aspect() >= 1 ? size : size * texture.aspect();
                float h = texture.aspect() >= 1 ? size / texture.aspect() : size;
                float ox = x + (size - w) / 2;
                float oy = y + (size - h) / 2;
                draw.addRectFilled(ox, oy, ox + w, oy + h, EditorStyle.pack(Theme.BLACK));
                draw.addImage(texture.id, ox, oy, ox + w, oy + h);
                return;
            }
        }
        Color color = switch (kind) {
            case MODEL, BLEND -> Theme.ACCENT;
            case SCRIPT -> AssetKind.extension(file).equals("8s") ? Theme.ACCENT : Theme.SYNTAX_FUNCTION;
            case SOUND -> new Color(0xF286C4);
            case ARCHIVE -> new Color(0xE7EE98);
            default -> file.getFileName().toString().endsWith(".scene") ? new Color(0x97E1F1) : Theme.TEXT_FAINT;
        };
        if (kind == AssetKind.MODEL || kind == AssetKind.BLEND) {
            drawCube(draw, x + size * 0.15f, y + size * 0.15f, size * 0.7f, EditorStyle.pack(color));
            if (kind == AssetKind.BLEND) centeredText(draw, ".blend", x, y + size * 0.82f, size, Theme.TEXT_MUTED);
            return;
        }
        // a page with the file's extension on it
        draw.addRectFilled(x + size * 0.15f, y, x + size * 0.85f, y + size, EditorStyle.pack(Theme.SURFACE_HI), 4);
        draw.addRectFilled(x + size * 0.15f, y, x + size * 0.85f, y + size * 0.08f, EditorStyle.pack(color), 4);
        String extension = file.getFileName().toString().endsWith(".scene") ? "scene" : AssetKind.extension(file);
        centeredText(draw, "." + extension, x, y + size * 0.42f, size, color);
    }

    /** A simple outlined cube, the "3D model" icon. */
    private static void drawCube(ImDrawList draw, float x, float y, float s, int color) {
        float d = s * 0.3f;
        float[][] front = {{x, y + d}, {x + s - d, y + d}, {x + s - d, y + s}, {x, y + s}};
        float[][] back = {{x + d, y}, {x + s, y}, {x + s, y + s - d}, {x + d, y + s - d}};
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            draw.addLine(front[i][0], front[i][1], front[j][0], front[j][1], color, 2);
            draw.addLine(back[i][0], back[i][1], back[j][0], back[j][1], color, 1.5f);
            draw.addLine(front[i][0], front[i][1], back[i][0], back[i][1], color, 1.5f);
        }
    }

    private static void centeredText(ImDrawList draw, String text, float x, float y, float width, Color color) {
        float w = ImGui.calcTextSize(text).x;
        draw.addText(x + (width - w) / 2, y, EditorStyle.pack(color), text);
    }

    /** Shortens a name with "..." so it fits under its tile. */
    private static String fit(String name, float width) {
        if (ImGui.calcTextSize(name).x <= width) return name;
        String shortened = name;
        while (shortened.length() > 1 && ImGui.calcTextSize(shortened + "...").x > width) {
            shortened = shortened.substring(0, shortened.length() - 1);
        }
        return shortened + "...";
    }

    // ---------------------------------------------------------------- folder listing

    /** The current folder's contents: folders first, then files, by name. Re-read now and then, not every frame. */
    private List<Path> listFolder() {
        long now = System.currentTimeMillis();
        boolean stale = !folder.equals(listedFolder) || now - listedAt > RELIST_EVERY_MS || listedVersion != ctx.assetsVersion();
        if (!stale) return entries;
        try (Stream<Path> list = Files.list(folder)) {
            boolean atRoot = folder.equals(ctx.project.root());
            entries = list
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !atRoot || Files.isDirectory(p) || p.toString().endsWith(".scene"))
                    .sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();
        } catch (IOException e) {
            entries = List.of();
        }
        listedFolder = folder;
        listedAt = now;
        listedVersion = ctx.assetsVersion();
        return entries;
    }

    // ---------------------------------------------------------------- new script / graph

    private void startCreating(NewKind kind) {
        newKind = kind;
        newName.set("");
        createError = null;
        ImGui.openPopup("new-behaviour");
    }

    private void drawNewPopup() {
        if (!ImGui.beginPopup("new-behaviour")) return;
        ImGui.text(newKind == NewKind.SCRIPT ? "New Seven script" : "New Eights graph");
        if (ImGui.isWindowAppearing()) ImGui.setKeyboardFocusHere();
        ImGui.setNextItemWidth(ImGui.getFontSize() * 14);
        String hint = newKind == NewKind.SCRIPT ? "PlayerMove" : "EnemyBrain";
        boolean submitted = ImGui.inputTextWithHint("##name", hint, newName, ImGuiInputTextFlags.EnterReturnsTrue);
        if (createError != null) ImGui.textColored(EditorStyle.pack(Theme.ERROR), createError);
        if (ImGui.button("Create") || submitted) {
            try {
                String name = newName.get().trim();
                Path file = newKind == NewKind.SCRIPT ? ctx.project.createScript(name) : ctx.project.createGraph(name);
                ctx.openScript(file, 0);
                listedFolder = null; // show the new file straight away
                ImGui.closeCurrentPopup();
            } catch (IOException e) {
                createError = e.getMessage();
            }
        }
        ImGui.endPopup();
    }

    static void showInExplorer(Path path) {
        try {
            new ProcessBuilder("explorer.exe", path.toString()).start();
        } catch (IOException ignored) {
            // nothing useful to do if Explorer won't start
        }
    }
}
