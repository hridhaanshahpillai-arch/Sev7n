package sev7n.lang;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns Seven source text into a list of tokens.
 *
 * Newlines are treated like any other whitespace and semicolons are optional,
 * so both of these are fine:
 *   var x = 1; var y = 2;
 *   var x = 1
 *   var y = 2
 */
public class Lexer {

    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
            Map.entry("var", TokenType.VAR),
            Map.entry("func", TokenType.FUNC),
            Map.entry("return", TokenType.RETURN),
            Map.entry("if", TokenType.IF),
            Map.entry("else", TokenType.ELSE),
            Map.entry("while", TokenType.WHILE),
            Map.entry("for", TokenType.FOR),
            Map.entry("in", TokenType.IN),
            Map.entry("break", TokenType.BREAK),
            Map.entry("continue", TokenType.CONTINUE),
            Map.entry("true", TokenType.TRUE),
            Map.entry("false", TokenType.FALSE),
            Map.entry("none", TokenType.NONE),
            Map.entry("and", TokenType.AND),
            Map.entry("or", TokenType.OR),
            Map.entry("not", TokenType.NOT),
            Map.entry("style", TokenType.STYLE),
            Map.entry("behaviour", TokenType.BEHAVIOUR)
    );

    private final String source;
    private final String file;
    private final List<Token> tokens = new ArrayList<>();
    private int start = 0;
    private int current = 0;
    private int line = 1;

    public Lexer(String source, String file) {
        this(source, file, 1);
    }

    /** Starts counting lines at 'firstLine' (for code inside a format string, which sits on some line of a file). */
    public Lexer(String source, String file, int firstLine) {
        this.source = source;
        this.file = file;
        this.line = firstLine;
    }

    public List<Token> tokenize() {
        while (!isAtEnd()) {
            start = current;
            scanToken();
        }
        tokens.add(new Token(TokenType.EOF, "", null, line, file));
        return tokens;
    }

    private void scanToken() {
        char c = advance();
        switch (c) {
            case ' ', '\t', '\r', '﻿' -> { } // ﻿: byte-order mark some editors add
            case '\n' -> line++;
            case '(' -> add(TokenType.LEFT_PAREN);
            case ')' -> add(TokenType.RIGHT_PAREN);
            case '{' -> add(TokenType.LEFT_BRACE);
            case '}' -> add(TokenType.RIGHT_BRACE);
            case '[' -> add(TokenType.LEFT_BRACKET);
            case ']' -> add(TokenType.RIGHT_BRACKET);
            case ',' -> add(TokenType.COMMA);
            case ':' -> add(TokenType.COLON);
            case ';' -> add(TokenType.SEMICOLON);
            case '?' -> add(match('?') ? TokenType.QUESTION_QUESTION : TokenType.QUESTION);
            case '%' -> add(match('=') ? TokenType.PERCENT_EQUAL : TokenType.PERCENT);
            case '.' -> add(match('.') ? TokenType.DOT_DOT : TokenType.DOT);
            case '+' -> add(match('=') ? TokenType.PLUS_EQUAL : TokenType.PLUS);
            case '-' -> add(match('=') ? TokenType.MINUS_EQUAL : TokenType.MINUS);
            case '*' -> add(match('=') ? TokenType.STAR_EQUAL : TokenType.STAR);
            case '=' -> add(match('=') ? TokenType.EQUAL_EQUAL : TokenType.EQUAL);
            case '<' -> add(match('=') ? TokenType.LESS_EQUAL : TokenType.LESS);
            case '>' -> add(match('=') ? TokenType.GREATER_EQUAL : TokenType.GREATER);
            case '!' -> add(match('=') ? TokenType.BANG_EQUAL : TokenType.BANG);
            case '&' -> {
                if (!match('&')) throw error("Did you mean '&&' (or 'and')?");
                add(TokenType.AND);
            }
            case '|' -> {
                if (!match('|')) throw error("Did you mean '||' (or 'or')?");
                add(TokenType.OR);
            }
            case '/' -> slashOrComment();
            case '"', '\'' -> string(c);
            case '#' -> color();
            default -> {
                if (isDigit(c)) number();
                else if (isAlpha(c)) identifier();
                else throw error("Unexpected character '" + c + "'");
            }
        }
    }

    private void slashOrComment() {
        if (match('/')) {
            while (peek() != '\n' && !isAtEnd()) advance();
        } else if (match('*')) {
            while (!isAtEnd() && !(peek() == '*' && peekNext() == '/')) {
                if (advance() == '\n') line++;
            }
            if (isAtEnd()) throw error("This /* comment */ is never closed");
            current += 2;
        } else {
            add(match('=') ? TokenType.SLASH_EQUAL : TokenType.SLASH);
        }
    }

    private void string(char quote) {
        add(TokenType.STRING, readString(quote, false));
    }

    /**
     * The text up to the closing quote, with escapes like \n turned into what they mean.
     * In a format string (f"..."), \{ is kept as \{ so the parser knows it's a real brace.
     */
    private String readString(char quote, boolean format) {
        StringBuilder value = new StringBuilder();
        int depth = 0; // inside { } of a format string, where quotes start strings of their own
        while ((peek() != quote || depth > 0) && !isAtEnd()) {
            char c = advance();
            if (c == '\n') line++;
            if (format && depth > 0) {
                // code: copied as written, for the parser to read later
                value.append(c);
                if (c == '{') depth++;
                else if (c == '}') depth--;
                else if (c == '"' || c == '\'') {
                    while (peek() != c && !isAtEnd()) {
                        char inner = advance();
                        value.append(inner);
                        if (inner == '\\' && !isAtEnd()) value.append(advance());
                    }
                    if (!isAtEnd()) value.append(advance());
                }
                continue;
            }
            if (format && c == '{') depth++;
            if (c == '\\' && !isAtEnd()) {
                char escaped = advance();
                if (format && (escaped == '{' || escaped == '}')) value.append('\\');
                value.append(switch (escaped) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    default -> escaped;
                });
            } else {
                value.append(c);
            }
        }
        if (isAtEnd()) throw error("This string is never closed");
        advance(); // closing quote
        return value.toString();
    }

    /** CSS-style colors: #RGB, #RGBA, #RRGGBB or #RRGGBBAA. */
    private void color() {
        while (isHexDigit(peek())) advance();
        String hex = source.substring(start + 1, current);
        add(TokenType.COLOR, parseHexColor(hex));
    }

    private Color parseHexColor(String hex) {
        if (hex.length() == 3 || hex.length() == 4) {
            StringBuilder expanded = new StringBuilder();
            for (char ch : hex.toCharArray()) expanded.append(ch).append(ch);
            hex = expanded.toString();
        }
        return switch (hex.length()) {
            case 6 -> new Color(Integer.parseInt(hex, 16));
            case 8 -> {
                long rgba = Long.parseLong(hex, 16);
                yield new Color((int) (rgba >> 24) & 0xFF, (int) (rgba >> 16) & 0xFF,
                        (int) (rgba >> 8) & 0xFF, (int) rgba & 0xFF);
            }
            default -> throw error("'#" + hex + "' is not a valid color. Use #RGB, #RRGGBB or #RRGGBBAA");
        };
    }

    private void number() {
        while (isDigit(peek())) advance();
        if (peek() == '.' && isDigit(peekNext())) {
            advance();
            while (isDigit(peek())) advance();
        }
        double value = Double.parseDouble(source.substring(start, current));
        // CSS habit: allow "20px". The unit is purely decorative.
        if (peek() == 'p' && peekNext() == 'x') current += 2;
        add(TokenType.NUMBER, value);
    }

    private void identifier() {
        while (isAlphaNumeric(peek())) advance();
        String text = source.substring(start, current);
        // f"Score: {score}" is a format string: the parts in braces are worked out and joined in
        if (text.equals("f") && (peek() == '"' || peek() == '\'')) {
            add(TokenType.FORMAT, readString(advance(), true));
            return;
        }
        add(KEYWORDS.getOrDefault(text, TokenType.IDENTIFIER));
    }

    // ---- helpers ----

    private boolean isAtEnd() {
        return current >= source.length();
    }

    private char advance() {
        return source.charAt(current++);
    }

    private boolean match(char expected) {
        if (isAtEnd() || source.charAt(current) != expected) return false;
        current++;
        return true;
    }

    private char peek() {
        return isAtEnd() ? '\0' : source.charAt(current);
    }

    private char peekNext() {
        return current + 1 >= source.length() ? '\0' : source.charAt(current + 1);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHexDigit(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isAlphaNumeric(char c) {
        return isAlpha(c) || isDigit(c);
    }

    private void add(TokenType type) {
        add(type, null);
    }

    private void add(TokenType type, Object literal) {
        tokens.add(new Token(type, source.substring(start, current), literal, line, file));
    }

    private SevenError error(String message) {
        return new SevenError(message, line).inFile(file);
    }
}
