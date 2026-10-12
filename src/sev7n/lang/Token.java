package sev7n.lang;

/**
 * A single token from Seven source code.
 *
 * @param type    what kind of token this is
 * @param text    the exact source text
 * @param literal the parsed value for literals (Double, String, Color), otherwise null
 * @param line    1-based line number, used in error messages
 * @param file    the script file this came from (e.g. "PlayerMove.7n"), used in error messages
 */
public record Token(TokenType type, String text, Object literal, int line, String file) {

    @Override
    public String toString() {
        return type + " '" + text + "' (" + file + ":" + line + ")";
    }
}
