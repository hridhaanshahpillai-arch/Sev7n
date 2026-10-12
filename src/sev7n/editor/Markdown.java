package sev7n.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Just enough Markdown for Sev7n's guides: headings, paragraphs, lists, code blocks,
 * tables, quotes and rules, with **bold**, `code` and [links](target) inside text.
 */
final class Markdown {

    // ---------------------------------------------------------------- blocks

    sealed interface Block permits Heading, Paragraph, ListItem, Code, Table, Quote, Rule { }

    record Heading(int level, String text) implements Block { }

    record Paragraph(String text) implements Block { }

    /** A bullet ("•") or numbered ("1.") list item; depth 0 is the outermost. */
    record ListItem(int depth, String marker, String text) implements Block { }

    record Code(String language, List<String> lines) implements Block { }

    record Table(List<String> header, List<List<String>> rows) implements Block { }

    record Quote(String text) implements Block { }

    record Rule() implements Block { }

    // ---------------------------------------------------------------- inline text

    enum Style { PLAIN, BOLD, CODE, LINK }

    /** A run of text in one style. Links carry their target. */
    record Span(String text, Style style, String target) { }

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d+)\\.\\s+(.*)$");
    private static final Pattern RULE = Pattern.compile("^\\s*-{3,}\\s*$");
    private static final Pattern TABLE_DIVIDER = Pattern.compile("^:?-+:?$");
    private static final Pattern INLINE = Pattern.compile(
            "\\*\\*(.+?)\\*\\*"            // **bold**
            + "|`([^`]+)`"                 // `code`
            + "|\\[([^\\]]+)\\]\\(([^)]+)\\)"); // [text](target)

    private Markdown() { }

    static List<Block> parse(String source) {
        List<String> lines = source.replace("﻿", "").replace("\r", "").lines().toList();
        List<Block> blocks = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            Matcher m;
            if (line.isBlank()) {
                i++;
            } else if (line.startsWith("```")) {
                String language = line.substring(3).trim();
                List<String> code = new ArrayList<>();
                i++;
                while (i < lines.size() && !lines.get(i).startsWith("```")) code.add(lines.get(i++));
                i++; // the closing fence
                blocks.add(new Code(language, code));
            } else if ((m = HEADING.matcher(line)).matches()) {
                blocks.add(new Heading(m.group(1).length(), m.group(2).trim()));
                i++;
            } else if (RULE.matcher(line).matches()) {
                blocks.add(new Rule());
                i++;
            } else if (line.startsWith(">")) {
                StringBuilder text = new StringBuilder();
                while (i < lines.size() && lines.get(i).startsWith(">")) {
                    append(text, lines.get(i++).substring(1));
                }
                blocks.add(new Quote(text.toString()));
            } else if (line.trim().startsWith("|")) {
                List<List<String>> rows = new ArrayList<>();
                while (i < lines.size() && lines.get(i).trim().startsWith("|")) {
                    List<String> cells = cells(lines.get(i++));
                    if (!cells.stream().allMatch(c -> TABLE_DIVIDER.matcher(c).matches())) rows.add(cells);
                }
                if (!rows.isEmpty()) blocks.add(new Table(rows.get(0), rows.subList(1, rows.size())));
            } else if ((m = BULLET.matcher(line)).matches() || (m = NUMBERED.matcher(line)).matches()) {
                boolean numbered = m.groupCount() == 3;
                StringBuilder text = new StringBuilder(m.group(numbered ? 3 : 2));
                int depth = m.group(1).length() / 2;
                i++;
                // indented lines that aren't new items continue this one
                while (i < lines.size() && isContinuation(lines.get(i))) append(text, lines.get(i++));
                blocks.add(new ListItem(depth, numbered ? m.group(2) + "." : "•", text.toString()));
            } else {
                StringBuilder text = new StringBuilder();
                while (i < lines.size() && !lines.get(i).isBlank() && !startsBlock(lines.get(i))) {
                    append(text, lines.get(i++));
                }
                blocks.add(new Paragraph(text.toString()));
            }
        }
        return blocks;
    }

    /** Splits text into bold, code, link and plain runs. */
    static List<Span> spans(String text) {
        List<Span> spans = new ArrayList<>();
        Matcher m = INLINE.matcher(text);
        int at = 0;
        while (m.find()) {
            if (m.start() > at) spans.add(plain(text.substring(at, m.start())));
            if (m.group(1) != null) spans.add(new Span(stripMarks(m.group(1)), Style.BOLD, null));
            else if (m.group(2) != null) spans.add(new Span(unescape(m.group(2)), Style.CODE, null));
            else spans.add(new Span(stripMarks(m.group(3)), Style.LINK, m.group(4)));
            at = m.end();
        }
        if (at < text.length()) spans.add(plain(text.substring(at)));
        return spans;
    }

    /** The text without any Markdown marks, for searching and the contents list. */
    static String plainText(String text) {
        StringBuilder out = new StringBuilder();
        for (Span span : spans(text)) out.append(span.text());
        return out.toString();
    }

    /** Everything searchable in a block. */
    static String plainText(Block block) {
        return switch (block) {
            case Heading h -> plainText(h.text());
            case Paragraph p -> plainText(p.text());
            case ListItem item -> plainText(item.text());
            case Quote q -> plainText(q.text());
            case Code c -> String.join("\n", c.lines());
            case Table t -> {
                StringBuilder all = new StringBuilder(String.join(" ", t.header()));
                for (List<String> row : t.rows()) all.append('\n').append(plainText(String.join(" ", row)));
                yield all.toString();
            }
            case Rule r -> "";
        };
    }

    // ---------------------------------------------------------------- helpers

    private static boolean startsBlock(String line) {
        return line.startsWith("```") || line.startsWith(">") || line.trim().startsWith("|")
                || HEADING.matcher(line).matches() || RULE.matcher(line).matches()
                || BULLET.matcher(line).matches() || NUMBERED.matcher(line).matches();
    }

    private static boolean isContinuation(String line) {
        return !line.isBlank() && Character.isWhitespace(line.charAt(0))
                && !BULLET.matcher(line).matches() && !NUMBERED.matcher(line).matches();
    }

    /** The cells of a "| a | b |" row. "\|" is a pipe inside a cell. */
    private static List<String> cells(String row) {
        String inner = row.trim();
        if (inner.startsWith("|")) inner = inner.substring(1);
        if (inner.endsWith("|") && !inner.endsWith("\\|")) inner = inner.substring(0, inner.length() - 1);
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '\\' && i + 1 < inner.length() && inner.charAt(i + 1) == '|') {
                cell.append("\\|");
                i++;
            } else if (c == '|') {
                cells.add(cell.toString().trim());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString().trim());
        return cells;
    }

    private static void append(StringBuilder text, String line) {
        if (!text.isEmpty()) text.append(' ');
        text.append(line.trim());
    }

    private static Span plain(String text) {
        return new Span(unescape(text), Style.PLAIN, null);
    }

    private static String stripMarks(String text) {
        return unescape(text.replace("`", "").replace("**", ""));
    }

    private static String unescape(String text) {
        return text.replace("\\|", "|");
    }
}
