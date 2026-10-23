package sev7n.editor;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiMouseCursor;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiTableColumnFlags;
import imgui.flag.ImGuiTableFlags;
import imgui.flag.ImGuiTableRowFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImString;
import sev7n.Sev7n;
import sev7n.editor.Markdown.Block;
import sev7n.editor.Markdown.Span;
import sev7n.ui.Theme;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The guides (docs\*.md and the README), shown inside the editor.
 *
 * A sidebar lists the guides, the sections of the open one, and search results
 * across all of them. Links between guides open in place; web links open the browser.
 */
final class GuidePanel {

    static final String TITLE = "Guides";

    /** A guide: its title in the sidebar and its file under the Sev7n folder. */
    record Guide(String title, Path file) { }

    private record SearchHit(Guide guide, int block, String section, String snippet) { }

    static final List<Guide> GUIDES = List.of(
            new Guide("Getting Started", Path.of("README.md")),
            new Guide("Seven Language", Path.of("docs", "SEVEN.md")),
            new Guide("Eights Visual Scripting", Path.of("docs", "EIGHTS.md")),
            new Guide("Importing Assets", Path.of("docs", "ASSETS.md")),
            new Guide("2D Games", Path.of("docs", "2D.md")),
            new Guide("Physics", Path.of("docs", "PHYSICS.md")),
            new Guide("Animation", Path.of("docs", "ANIMATION.md")),
            new Guide("First-person and Open Worlds", Path.of("docs", "WORLDS.md")),
            new Guide("Scenes, Saves and Prefabs", Path.of("docs", "SCENES.md")),
            new Guide("HUD, Menus and Sound", Path.of("docs", "HUD.md")),
            new Guide("Sound and Music", Path.of("docs", "AUDIO.md")),
            new Guide("Cameras and Controls", Path.of("docs", "CONTROLS.md")),
            new Guide("Multiplayer", Path.of("docs", "MULTIPLAYER.md")),
            new Guide("Ragdolls", Path.of("docs", "RAGDOLLS.md")),
            new Guide("NPCs and AI", Path.of("docs", "NPC.md")),
            new Guide("Particle Effects", Path.of("docs", "EFFECTS.md")),
            new Guide("Exporting a Game", Path.of("docs", "EXPORT.md")),
            new Guide("Settings", Path.of("docs", "SETTINGS.md")));

    private static final float READING_WIDTH = 760; // long lines are hard to read

    private final Set<String> builtins;
    private final ImBoolean open = new ImBoolean(false);
    private final ImString search = new ImString(64);
    private boolean focusRequested;

    private final Map<Guide, List<Block>> loaded = new HashMap<>();
    private final Map<Guide, Long> loadedTime = new HashMap<>();
    private Guide current = GUIDES.get(0);
    private int scrollToBlock = -1;
    private boolean scrollToTop;

    // caches so each frame only lays text out, never re-parses it
    private final Map<String, List<Span>> spanCache = new HashMap<>();
    private final Map<Markdown.Code, int[][]> codeColors = new IdentityHashMap<>();
    private final Map<Integer, float[]> blockHeights = new HashMap<>(); // block -> {width, height}

    GuidePanel(Set<String> builtins) {
        this.builtins = builtins;
    }

    /** Shows the guide stored in this file (e.g. "SEVEN.md"), or just the window if null. */
    void open(String fileName) {
        open.set(true);
        focusRequested = true;
        if (fileName != null) {
            Guide guide = byFileName(fileName);
            if (guide != null) show(guide, -1);
        }
    }

    void toggle() {
        if (open.get()) open.set(false);
        else open(null);
    }

    void draw() {
        if (!open.get()) return;
        ImGui.setNextWindowSize(980, 680, ImGuiCond.FirstUseEver);
        ImVec2 center = ImGui.getMainViewport().getCenter();
        ImGui.setNextWindowPos(center.x, center.y, ImGuiCond.FirstUseEver, 0.5f, 0.5f);
        if (focusRequested) {
            ImGui.setNextWindowFocus();
            focusRequested = false;
        }
        if (!ImGui.begin(TITLE, open, ImGuiWindowFlags.NoCollapse)) {
            ImGui.end();
            return;
        }
        float scale = ImGui.getFontSize() / 15f;
        ImGui.beginChild("##sidebar", 220 * scale, 0, true);
        drawSidebar();
        ImGui.endChild();
        ImGui.sameLine();
        drawDocument(scale);
        ImGui.end();
    }

    // ---------------------------------------------------------------- sidebar

    private void drawSidebar() {
        ImGui.setNextItemWidth(-1);
        ImGui.inputTextWithHint("##search", "Search guides...", search);
        ImGui.spacing();

        String query = search.get().trim().toLowerCase(Locale.ROOT);
        if (!query.isEmpty()) {
            drawSearchResults(query);
            return;
        }

        sidebarLabel("GUIDES");
        for (Guide guide : GUIDES) {
            if (ImGui.selectable(guide.title(), guide == current)) show(guide, -1);
        }
        ImGui.spacing();
        sidebarLabel("ON THIS PAGE");
        List<Block> blocks = blocks(current);
        for (int i = 0; i < blocks.size(); i++) {
            if (!(blocks.get(i) instanceof Markdown.Heading h) || h.level() < 2 || h.level() > 3) continue;
            ImGui.pushID(i);
            if (h.level() == 3) ImGui.indent();
            ImGui.pushStyleColor(ImGuiCol.Text, EditorStyle.pack(h.level() == 2 ? Theme.TEXT_MUTED : Theme.TEXT_FAINT));
            if (ImGui.selectable(Markdown.plainText(h.text()))) scrollToBlock = i;
            ImGui.popStyleColor();
            if (h.level() == 3) ImGui.unindent();
            ImGui.popID();
        }
    }

    private void drawSearchResults(String query) {
        List<SearchHit> hits = search(query);
        sidebarLabel(hits.isEmpty() ? "NO MATCHES" : hits.size() + (hits.size() == 1 ? " MATCH" : " MATCHES"));
        for (int i = 0; i < hits.size(); i++) {
            SearchHit hit = hits.get(i);
            ImGui.pushID(i);
            if (ImGui.selectable("##hit", false, 0, 0, ImGui.getTextLineHeight() * 2 + 2)) show(hit.guide(), hit.block());
            ImGui.sameLine(ImGui.getStyle().getItemInnerSpacingX());
            ImGui.beginGroup();
            ImGui.textUnformatted(hit.section());
            ImGui.pushStyleColor(ImGuiCol.Text, EditorStyle.pack(Theme.TEXT_FAINT));
            ImGui.textUnformatted(hit.guide().title() + "  ·  " + hit.snippet());
            ImGui.popStyleColor();
            ImGui.endGroup();
            ImGui.popID();
        }
    }

    /** Every section (of every guide) that mentions the query, best matches (in headings) first. */
    private List<SearchHit> search(String query) {
        List<SearchHit> inHeadings = new ArrayList<>();
        List<SearchHit> inText = new ArrayList<>();
        for (Guide guide : GUIDES) {
            List<Block> blocks = blocks(guide);
            int section = -1;
            String sectionName = guide.title();
            boolean sectionHit = false;
            for (int i = 0; i < blocks.size(); i++) {
                Block block = blocks.get(i);
                if (block instanceof Markdown.Heading h) {
                    section = i;
                    sectionName = Markdown.plainText(h.text());
                    sectionHit = false;
                }
                if (sectionHit) continue;
                String text = Markdown.plainText(block);
                int at = text.toLowerCase(Locale.ROOT).indexOf(query);
                if (at < 0) continue;
                sectionHit = true;
                SearchHit hit = new SearchHit(guide, Math.max(section, 0), sectionName, snippet(text, at));
                (block instanceof Markdown.Heading ? inHeadings : inText).add(hit);
            }
        }
        inHeadings.addAll(inText);
        return inHeadings.subList(0, Math.min(inHeadings.size(), 60));
    }

    private static String snippet(String text, int at) {
        int start = Math.max(0, at - 12);
        String part = text.substring(start, Math.min(text.length(), start + 40)).replace('\n', ' ').trim();
        return (start > 0 ? "…" : "") + part;
    }

    private static void sidebarLabel(String text) {
        ImGui.pushStyleColor(ImGuiCol.Text, EditorStyle.pack(Theme.TEXT_FAINT));
        ImGui.textUnformatted(text);
        ImGui.popStyleColor();
    }

    // ---------------------------------------------------------------- the document

    private void drawDocument(float scale) {
        ImGui.pushStyleColor(ImGuiCol.ChildBg, EditorStyle.pack(Theme.EDITOR));
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 28 * scale, 18 * scale);
        ImGui.beginChild("##doc", 0, 0, false, ImGuiWindowFlags.AlwaysUseWindowPadding);
        ImGui.popStyleVar();
        if (scrollToTop) {
            ImGui.setScrollY(0);
            scrollToTop = false;
        }

        float width = Math.min(ImGui.getContentRegionAvailX(), READING_WIDTH * scale);
        List<Block> blocks = blocks(current);
        if (blocks.isEmpty()) {
            ImGui.textDisabled("Couldn't read " + Sev7n.home().resolve(current.file()));
        }
        float viewTop = ImGui.getScrollY();
        float viewBottom = viewTop + ImGui.getWindowHeight();
        for (int i = 0; i < blocks.size(); i++) {
            if (i == scrollToBlock) {
                ImGui.setScrollHereY(0);
                scrollToBlock = -1;
            }
            // blocks well out of view are skipped, using their height from last time
            float y = ImGui.getCursorPosY();
            float[] known = blockHeights.get(i);
            if (known != null && known[0] == width && (y + known[1] < viewTop - 200 || y > viewBottom + 200)) {
                ImGui.dummy(width, known[1]);
                continue;
            }
            ImGui.pushID(i);
            drawBlock(blocks.get(i), i, width, scale);
            ImGui.popID();
            blockHeights.put(i, new float[] { width, ImGui.getCursorPosY() - y });
        }
        ImGui.dummy(0, 40 * scale);
        ImGui.endChild();
        ImGui.popStyleColor();
    }

    private void drawBlock(Block block, int index, float width, float scale) {
        float gap = 8 * scale;
        switch (block) {
            case Markdown.Heading h -> drawHeading(h, index, width, scale);
            case Markdown.Paragraph p -> {
                flow(p.text(), EditorStyle.uiFont, Theme.TEXT, width);
                ImGui.dummy(0, gap);
            }
            case Markdown.ListItem item -> drawListItem(item, width, scale, gap);
            case Markdown.Code code -> {
                drawCode(code, width, scale);
                ImGui.dummy(0, gap);
            }
            case Markdown.Table table -> {
                drawTable(table, index, width, scale);
                ImGui.dummy(0, gap);
            }
            case Markdown.Quote quote -> {
                drawQuote(quote, width, scale);
                ImGui.dummy(0, gap);
            }
            case Markdown.Rule rule -> {
                ImGui.dummy(0, gap);
                ImVec2 at = ImGui.getCursorScreenPos();
                ImGui.getWindowDrawList().addLine(at.x, at.y, at.x + width, at.y, EditorStyle.pack(Theme.SELECTION));
                ImGui.dummy(0, gap * 2);
            }
        }
    }

    private void drawHeading(Markdown.Heading h, int index, float width, float scale) {
        String text = Markdown.plainText(h.text());
        if (h.level() == 1) {
            text(text, EditorStyle.titleFont, Theme.TEXT);
            ImGui.dummy(0, 6 * scale);
        } else if (h.level() == 2) {
            if (index > 0) ImGui.dummy(0, 14 * scale);
            text(text, EditorStyle.headingFont, Theme.ACCENT);
            ImVec2 at = ImGui.getCursorScreenPos();
            ImGui.getWindowDrawList().addLine(at.x, at.y, at.x + width, at.y, EditorStyle.pack(Theme.SELECTION));
            ImGui.dummy(0, 6 * scale);
        } else {
            ImGui.dummy(0, 6 * scale);
            text(text, EditorStyle.boldFont, Theme.TEXT);
            ImGui.dummy(0, 2 * scale);
        }
    }

    private void drawListItem(Markdown.ListItem item, float width, float scale, float gap) {
        float indent = (item.depth() * 18 + 6) * scale;
        float markerWidth = 18 * scale;
        float x = ImGui.getCursorPosX();
        ImGui.setCursorPosX(x + indent);
        ImVec2 at = ImGui.getCursorScreenPos();
        float lineHeight = ImGui.getTextLineHeight();
        if (item.marker().equals("•")) {
            ImGui.getWindowDrawList().addCircleFilled(at.x + 4 * scale, at.y + lineHeight / 2, 2.5f * scale,
                    EditorStyle.pack(Theme.ACCENT));
        } else {
            text(item.marker(), EditorStyle.boldFont, Theme.ACCENT);
            ImGui.sameLine();
        }
        ImGui.setCursorScreenPos(at.x + markerWidth, at.y);
        flow(item.text(), EditorStyle.uiFont, Theme.TEXT, width - indent - markerWidth);
        ImGui.dummy(0, gap / 3);
    }

    private void drawQuote(Markdown.Quote quote, float width, float scale) {
        ImVec2 top = ImGui.getCursorScreenPos();
        float pad = 10 * scale;
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.channelsSplit(2);
        draw.channelsSetCurrent(1);
        ImGui.setCursorScreenPos(top.x + pad + 4 * scale, top.y + pad / 2);
        flow(quote.text(), EditorStyle.uiFont, Theme.TEXT_MUTED, width - pad * 2);
        float bottom = ImGui.getCursorScreenPos().y + pad / 2 - ImGui.getStyle().getItemSpacingY();
        draw.channelsSetCurrent(0);
        draw.addRectFilled(top.x, top.y, top.x + width, bottom, EditorStyle.pack(Theme.CURRENT_LINE), 4 * scale);
        draw.addRectFilled(top.x, top.y, top.x + 3 * scale, bottom, EditorStyle.pack(Theme.ACCENT));
        draw.channelsMerge();
        ImGui.setCursorScreenPos(top.x, bottom + ImGui.getStyle().getItemSpacingY());
    }

    private void drawCode(Markdown.Code code, float width, float scale) {
        ImGui.pushFont(EditorStyle.codeFont);
        float pad = 10 * scale;
        float step = ImGui.getTextLineHeightWithSpacing();
        float widest = 0;
        for (String line : code.lines()) widest = Math.max(widest, ImGui.calcTextSize(line).x);
        boolean scrolls = widest + pad * 2 > width;
        float height = code.lines().size() * step - ImGui.getStyle().getItemSpacingY() + pad * 2
                + (scrolls ? ImGui.getStyle().getScrollbarSize() : 0);
        ImGui.popFont(); // fonts must be pushed and popped inside the same window

        ImGui.pushStyleColor(ImGuiCol.ChildBg, EditorStyle.pack(Theme.PANEL));
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, pad, pad);
        int flags = ImGuiWindowFlags.AlwaysUseWindowPadding | ImGuiWindowFlags.NoScrollWithMouse
                | (scrolls ? ImGuiWindowFlags.HorizontalScrollbar : ImGuiWindowFlags.NoScrollbar);
        ImGui.beginChild("##code", width, height, false, flags);
        ImGui.pushFont(EditorStyle.codeFont);
        int[][] colors = codeColors.computeIfAbsent(code, this::colorize);
        for (int line = 0; line < code.lines().size(); line++) {
            drawColoredLine(code.lines().get(line), colors[line]);
        }
        ImGui.popFont();

        // copy button in the top-right corner. It's inside the box: a button drawn over the box from
        // outside never gets clicked, because the box takes the mouse. It follows the scroll, so it stays put.
        String label = "Copy";
        float buttonWidth = ImGui.calcTextSize(label).x + ImGui.getStyle().getFramePaddingX() * 2;
        ImGui.setCursorPos(ImGui.getScrollX() + width - buttonWidth - pad / 2, pad / 2);
        if (ImGui.smallButton(label)) ImGui.setClipboardText(String.join("\n", code.lines()));
        ImGui.endChild();
        ImGui.popStyleVar();
        ImGui.popStyleColor();
    }

    /** Seven code gets syntax colors; anything else is shown plain. */
    private int[][] colorize(Markdown.Code code) {
        List<StringBuilder> lines = code.lines().stream().map(StringBuilder::new).toList();
        if (code.language().equals("seven")) return SyntaxColors.colorize(lines, builtins);
        int[][] plain = new int[lines.size()][];
        int muted = EditorStyle.pack(Theme.TEXT_MUTED);
        for (int i = 0; i < plain.length; i++) {
            plain[i] = new int[lines.get(i).length()];
            Arrays.fill(plain[i], muted);
        }
        return plain;
    }

    private static void drawColoredLine(String line, int[] colors) {
        if (line.isEmpty()) {
            ImGui.textUnformatted("");
            return;
        }
        int start = 0;
        while (start < line.length()) {
            int end = start + 1;
            while (end < line.length() && colors[end] == colors[start]) end++;
            if (start > 0) ImGui.sameLine(0, 0);
            ImGui.pushStyleColor(ImGuiCol.Text, colors[start]);
            ImGui.textUnformatted(line.substring(start, end));
            ImGui.popStyleColor();
            start = end;
        }
    }

    private void drawTable(Markdown.Table table, int index, float width, float scale) {
        int columns = table.header().size();
        ImGui.pushStyleVar(ImGuiStyleVar.CellPadding, 8 * scale, 5 * scale);
        ImGui.pushStyleColor(ImGuiCol.TableHeaderBg, EditorStyle.pack(Theme.PANEL));
        ImGui.pushStyleColor(ImGuiCol.TableBorderLight, EditorStyle.pack(Theme.SELECTION));
        ImGui.pushStyleColor(ImGuiCol.TableBorderStrong, EditorStyle.pack(Theme.SELECTION));
        int flags = ImGuiTableFlags.BordersInnerH | ImGuiTableFlags.BordersOuter | ImGuiTableFlags.SizingStretchProp;
        if (ImGui.beginTable("##table" + index, columns, flags, width, 0)) {
            for (int c = 0; c < columns; c++) {
                ImGui.tableSetupColumn("##c" + c, ImGuiTableColumnFlags.WidthStretch, columnWeight(table, c));
            }
            ImGui.tableNextRow(ImGuiTableRowFlags.Headers);
            for (int c = 0; c < columns; c++) {
                ImGui.tableSetColumnIndex(c);
                flow(table.header().get(c), EditorStyle.boldFont, Theme.TEXT, ImGui.getContentRegionAvailX());
            }
            for (List<String> row : table.rows()) {
                ImGui.tableNextRow();
                for (int c = 0; c < columns && c < row.size(); c++) {
                    ImGui.tableSetColumnIndex(c);
                    flow(row.get(c), EditorStyle.uiFont, Theme.TEXT, ImGui.getContentRegionAvailX());
                }
            }
            ImGui.endTable();
        }
        ImGui.popStyleColor(3);
        ImGui.popStyleVar();
    }

    /** Wider columns for longer text, within limits so no column gets squashed. */
    private static float columnWeight(Markdown.Table table, int column) {
        int longest = Markdown.plainText(table.header().get(column)).length();
        for (List<String> row : table.rows()) {
            if (column < row.size()) longest = Math.max(longest, Markdown.plainText(row.get(column)).length());
        }
        return Math.max(6, Math.min(longest, 60));
    }

    // ---------------------------------------------------------------- flowing text

    /**
     * Lays out Markdown text word by word, wrapping at the given width, with bold,
     * `code` (on a little background) and clickable links mixed in.
     */
    private void flow(String markdown, ImFont font, Color color, float width) {
        float left = ImGui.getCursorPosX();
        float right = left + width;
        float codePad = 3;
        boolean lineStart = true;
        boolean drewAnything = false;
        float gapBefore = 0;

        for (Span span : spanCache.computeIfAbsent(markdown, Markdown::spans)) {
            ImFont spanFont = switch (span.style()) {
                case BOLD -> EditorStyle.boldFont;
                case CODE -> EditorStyle.codeFont;
                default -> font;
            };
            ImGui.pushFont(spanFont);
            for (String word : span.style() == Markdown.Style.CODE ? List.of(span.text()) : words(span.text())) {
                String shown = word;
                float wordWidth = ImGui.calcTextSize(word.stripTrailing()).x + (span.style() == Markdown.Style.CODE ? codePad * 2 : 0);
                boolean fits = lineStart || ImGui.getItemRectMax().x - ImGui.getWindowPos().x + ImGui.getScrollX() + gapBefore + wordWidth <= right;
                if (lineStart) {
                    ImGui.setCursorPosX(left);
                } else if (fits) {
                    ImGui.sameLine(0, gapBefore);
                } else {
                    ImGui.setCursorPosX(left); // wrap: the next item already starts a new line
                    if (word.isBlank()) {
                        lineStart = true;
                        continue;
                    }
                    shown = word.stripLeading();
                }
                lineStart = false;
                drewAnything = true;
                gapBefore = 0;
                drawWord(shown, span, spanFont == font ? color : null, codePad);
                if (span.style() == Markdown.Style.CODE) gapBefore = codePad;
            }
            ImGui.popFont();
        }
        if (!drewAnything) ImGui.textUnformatted(""); // empty text still takes a line
    }

    private void drawWord(String word, Span span, Color baseColor, float codePad) {
        switch (span.style()) {
            case CODE -> {
                ImVec2 at = ImGui.getCursorScreenPos();
                float w = ImGui.calcTextSize(word).x;
                ImGui.getWindowDrawList().addRectFilled(at.x, at.y - 1, at.x + w + codePad * 2,
                        at.y + ImGui.getTextLineHeight() + 1, EditorStyle.pack(Theme.SURFACE), 3);
                ImGui.setCursorScreenPos(at.x + codePad, at.y);
                text(word, null, Theme.INLINE_CODE);
            }
            case LINK -> {
                text(word, null, Theme.LINK);
                if (ImGui.isItemHovered()) {
                    ImGui.setMouseCursor(ImGuiMouseCursor.Hand);
                    ImVec2 min = ImGui.getItemRectMin();
                    ImVec2 max = ImGui.getItemRectMax();
                    float trailing = ImGui.calcTextSize(word).x - ImGui.calcTextSize(word.stripTrailing()).x;
                    ImGui.getWindowDrawList().addLine(min.x, max.y, max.x - trailing, max.y, EditorStyle.pack(Theme.LINK));
                    ImGui.setTooltip(span.target());
                }
                if (ImGui.isItemClicked()) followLink(span.target());
            }
            default -> text(word, null, baseColor != null ? baseColor : Theme.TEXT);
        }
    }

    /** Splits text into words, each keeping the spaces after it (leading spaces are their own word). */
    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int start = i;
            if (text.charAt(i) == ' ') {
                while (i < text.length() && text.charAt(i) == ' ') i++;
            } else {
                while (i < text.length() && text.charAt(i) != ' ') i++;
                while (i < text.length() && text.charAt(i) == ' ') i++;
            }
            words.add(text.substring(start, i));
        }
        return words;
    }

    private static void text(String text, ImFont font, Color color) {
        if (font != null) ImGui.pushFont(font);
        ImGui.pushStyleColor(ImGuiCol.Text, EditorStyle.pack(color));
        ImGui.textUnformatted(text);
        ImGui.popStyleColor();
        if (font != null) ImGui.popFont();
    }

    // ---------------------------------------------------------------- navigation

    private void followLink(String target) {
        if (target.startsWith("http://") || target.startsWith("https://")) {
            try {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", target).start(); // the default browser
            } catch (IOException ignored) {
                // no browser: nothing useful to do
            }
            return;
        }
        Guide guide = byFileName(Path.of(target.replace('/', '\\')).getFileName().toString());
        if (guide != null) show(guide, -1);
    }

    private void show(Guide guide, int block) {
        if (guide != current) blockHeights.clear();
        current = guide;
        scrollToBlock = block;
        scrollToTop = block < 0;
    }

    private static Guide byFileName(String fileName) {
        for (Guide guide : GUIDES) {
            if (guide.file().getFileName().toString().equalsIgnoreCase(fileName)) return guide;
        }
        return null;
    }

    /** A guide's blocks, re-read whenever its file changes on disk. */
    private List<Block> blocks(Guide guide) {
        Path file = Sev7n.home().resolve(guide.file());
        long modified;
        try {
            modified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return List.of();
        }
        if (!loadedTime.containsKey(guide) || loadedTime.get(guide) != modified) {
            try {
                loaded.put(guide, Markdown.parse(Files.readString(file)));
            } catch (IOException e) {
                loaded.put(guide, List.of());
            }
            loadedTime.put(guide, modified);
            if (guide == current) blockHeights.clear();
        }
        return loaded.get(guide);
    }
}
