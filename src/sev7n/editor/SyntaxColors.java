package sev7n.editor;

import sev7n.ui.Theme;

import java.awt.Color;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Works out the color of every character in a Seven script (Dracula Soft style).
 *
 * This is its own forgiving scanner rather than the real Lexer, because code being
 * typed is often half-finished (unclosed strings, etc.) and coloring must never fail.
 * Color literals like #bf9eee are shown in their own color.
 */
final class SyntaxColors {

    private static final Set<String> KEYWORDS = Set.of(
            "return", "if", "else", "while", "for", "in", "break", "continue", "and", "or", "not");
    private static final Set<String> DECLARATIONS = Set.of("var", "func", "style", "behaviour");
    private static final Set<String> CONSTANTS = Set.of("true", "false", "none", "PI");
    private static final Set<String> ENGINE_NAMES = Set.of("entity", "transform", "other");
    private static final String OPERATORS = "=+-*/%<>!&|";

    static final int PLAIN = EditorStyle.pack(Theme.SYNTAX_PLAIN);
    private static final int KEYWORD = EditorStyle.pack(Theme.SYNTAX_KEYWORD);
    private static final int OPERATOR = EditorStyle.pack(Theme.SYNTAX_OPERATOR);
    private static final int DECLARATION = EditorStyle.pack(Theme.SYNTAX_DECLARATION);
    private static final int BUILTIN = EditorStyle.pack(Theme.SYNTAX_BUILTIN);
    private static final int FUNCTION = EditorStyle.pack(Theme.SYNTAX_FUNCTION);
    private static final int STRING = EditorStyle.pack(Theme.SYNTAX_STRING);
    private static final int NUMBER = EditorStyle.pack(Theme.SYNTAX_NUMBER);
    private static final int COMMENT = EditorStyle.pack(Theme.SYNTAX_COMMENT);
    private static final int PROPERTY = EditorStyle.pack(Theme.SYNTAX_PROPERTY);

    private SyntaxColors() { }

    /** One color (packed for ImGui) per character, per line. */
    static int[][] colorize(List<StringBuilder> lines, Set<String> builtins) {
        String text = String.join("\n", lines);
        int[] colors = new int[text.length()];
        Arrays.fill(colors, PLAIN);

        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            int start = i;

            if (c == '/' && peek(text, i + 1) == '/') {
                i = text.indexOf('\n', i);
                if (i < 0) i = text.length();
                fill(colors, start, i, COMMENT);
            } else if (c == '/' && peek(text, i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? text.length() : end + 2;
                fill(colors, start, i, COMMENT);
            } else if (c == '"' || c == '\'') {
                i = skipString(text, i);
                fill(colors, start, i, STRING);
            } else if (c == '#') {
                i++;
                while (i < text.length() && Character.digit(text.charAt(i), 16) >= 0) i++;
                fill(colors, start, i, colorLiteral(text.substring(start + 1, i)));
            } else if (Character.isDigit(c)) {
                while (i < text.length() && (Character.isDigit(text.charAt(i)) || text.charAt(i) == '.')) i++;
                if (text.startsWith("px", i)) i += 2;
                fill(colors, start, i, NUMBER);
            } else if (Character.isLetter(c) || c == '_') {
                while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) i++;
                int propertyEnd = stylePropertyEnd(text, i);
                if (propertyEnd > 0) {
                    i = propertyEnd; // color all of "stroke-width", not just "stroke"
                    fill(colors, start, i, PROPERTY);
                } else {
                    fill(colors, start, i, classifyWord(text, text.substring(start, i), i, builtins));
                }
            } else if (c == '.' && peek(text, i + 1) == '.') {
                i += 2;
                fill(colors, start, i, OPERATOR);
            } else if (OPERATORS.indexOf(c) >= 0) {
                i++;
                fill(colors, start, i, OPERATOR);
            } else {
                i++;
            }
        }
        return splitIntoLines(lines, colors);
    }

    private static int classifyWord(String text, String word, int end, Set<String> builtins) {
        if (KEYWORDS.contains(word)) return KEYWORD;
        if (DECLARATIONS.contains(word)) return DECLARATION;
        if (CONSTANTS.contains(word)) return NUMBER;
        if (ENGINE_NAMES.contains(word)) return BUILTIN;
        if (peek(text, skipSpaces(text, end)) == '(') {
            return builtins.contains(word) ? BUILTIN : FUNCTION;
        }
        return PLAIN;
    }

    /** If the word ending at 'end' is a style property ("fill:" or "stroke-width:"), where it ends; else -1. */
    private static int stylePropertyEnd(String text, int end) {
        int j = end;
        while (j < text.length() && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '-')) j++;
        return peek(text, skipSpaces(text, j)) == ':' ? j : -1;
    }

    /** #rrggbb shows in its own color, as long as it's bright enough to read. */
    private static int colorLiteral(String hex) {
        if (hex.length() == 3) {
            hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
        }
        if (hex.length() != 6 && hex.length() != 8) return NUMBER;
        Color color = new Color(Integer.parseInt(hex.substring(0, 6), 16));
        double brightness = (0.299 * color.getRed() + 0.587 * color.getGreen() + 0.114 * color.getBlue()) / 255;
        return brightness > 0.3 ? EditorStyle.pack(color) : NUMBER;
    }

    private static int[][] splitIntoLines(List<StringBuilder> lines, int[] colors) {
        int[][] result = new int[lines.size()][];
        int offset = 0;
        for (int i = 0; i < lines.size(); i++) {
            int length = lines.get(i).length();
            result[i] = Arrays.copyOfRange(colors, offset, offset + length);
            offset += length + 1; // + the newline
        }
        return result;
    }

    private static int skipString(String text, int i) {
        char quote = text.charAt(i++);
        while (i < text.length() && text.charAt(i) != quote && text.charAt(i) != '\n') {
            if (text.charAt(i) == '\\') i++;
            i++;
        }
        return Math.min(i + 1, text.length());
    }

    private static int skipSpaces(String text, int i) {
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
        return i;
    }

    private static char peek(String text, int i) {
        return i < text.length() ? text.charAt(i) : '\0';
    }

    private static void fill(int[] colors, int from, int to, int color) {
        Arrays.fill(colors, from, Math.min(to, colors.length), color);
    }

}
