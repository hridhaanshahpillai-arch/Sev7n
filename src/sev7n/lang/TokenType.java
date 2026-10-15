package sev7n.lang;

/** Every kind of token the Seven lexer can produce. */
public enum TokenType {
    // Single-character punctuation
    LEFT_PAREN, RIGHT_PAREN, LEFT_BRACE, RIGHT_BRACE, LEFT_BRACKET, RIGHT_BRACKET,
    COMMA, DOT, COLON, SEMICOLON, QUESTION,

    // Operators
    PLUS, MINUS, STAR, SLASH, PERCENT,
    EQUAL, EQUAL_EQUAL, BANG, BANG_EQUAL,
    LESS, LESS_EQUAL, GREATER, GREATER_EQUAL,
    PLUS_EQUAL, MINUS_EQUAL, STAR_EQUAL, SLASH_EQUAL, PERCENT_EQUAL,
    DOT_DOT, QUESTION_QUESTION,

    // Literals ('FORMAT' is f"text with {expressions}", its literal the raw text between the quotes)
    IDENTIFIER, STRING, NUMBER, COLOR, FORMAT,

    // Keywords
    VAR, FUNC, RETURN, IF, ELSE, WHILE, FOR, IN, BREAK, CONTINUE,
    TRUE, FALSE, NONE, AND, OR, NOT, STYLE, BEHAVIOUR,

    EOF
}
