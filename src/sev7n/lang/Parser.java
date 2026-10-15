package sev7n.lang;

import sev7n.lang.ast.Expr;
import sev7n.lang.ast.Stmt;

import java.util.ArrayList;
import java.util.List;

import static sev7n.lang.TokenType.*;

/**
 * Recursive-descent parser: turns tokens into a syntax tree.
 *
 * Each grammar rule is one method, ordered from lowest to highest precedence:
 *   assignment -> range -> or -> and -> equality -> comparison
 *   -> term (+ -) -> factor (* / %) -> unary -> call -> primary
 */
public class Parser {

    private final List<Token> tokens;
    private int current = 0;

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public List<Stmt> parse() {
        List<Stmt> statements = new ArrayList<>();
        try {
            while (!isAtEnd()) {
                statements.add(declaration());
            }
        } catch (SevenError e) {
            throw e.inFile(peek().file());
        }
        return statements;
    }

    // ---------------------------------------------------------------- declarations

    private Stmt declaration() {
        if (check(FUNC) && checkNext(IDENTIFIER)) {
            advance();
            return funcDeclaration();
        }
        if (match(VAR)) return varDeclaration();
        if (match(STYLE)) return styleDeclaration();
        if (match(BEHAVIOUR)) return behaviourDeclaration();
        return statement();
    }

    /**
     * behaviour Name { var ... func ... }
     * A template for scripts you attach to objects. Only fields and functions go inside.
     */
    private Stmt behaviourDeclaration() {
        Token name = consume(IDENTIFIER, "Expected a behaviour name after 'behaviour'");
        consume(LEFT_BRACE, "Expected '{' after the behaviour name");
        List<Stmt.Var> fields = new ArrayList<>();
        List<Stmt.Func> methods = new ArrayList<>();
        while (!check(RIGHT_BRACE) && !isAtEnd()) {
            if (match(VAR)) fields.add(varDeclaration());
            else if (match(FUNC)) methods.add(funcDeclaration());
            else throw new SevenError("Only 'var' and 'func' can go directly inside a behaviour", peek().line());
        }
        consume(RIGHT_BRACE, "Expected '}' to close the behaviour");
        return new Stmt.Behaviour(name, fields, methods);
    }

    private Stmt.Func funcDeclaration() {
        Token name = consume(IDENTIFIER, "Expected a function name after 'func'");
        consume(LEFT_PAREN, "Expected '(' after the function name");
        return functionRest(name);
    }

    /** The parameters and body of a function, after its opening '('. Parameters may have defaults: func f(a, b = 2). */
    private Stmt.Func functionRest(Token name) {
        List<Token> params = new ArrayList<>();
        List<Expr> defaults = new ArrayList<>();
        if (!check(RIGHT_PAREN)) {
            do {
                Token param = consume(IDENTIFIER, "Expected a parameter name");
                Expr fallback = match(EQUAL) ? expression() : null;
                if (fallback == null && !defaults.isEmpty() && defaults.get(defaults.size() - 1) != null) {
                    throw new SevenError("'" + param.text() + "' needs a default too: once a parameter has one, the ones after it do as well", param.line());
                }
                params.add(param);
                defaults.add(fallback);
            } while (match(COMMA));
        }
        consume(RIGHT_PAREN, "Expected ')' after the parameters");
        consume(LEFT_BRACE, "Expected '{' to start the function body");
        return new Stmt.Func(name, params, defaults, block());
    }

    private Stmt.Var varDeclaration() {
        Token name = consume(IDENTIFIER, "Expected a variable name after 'var'");
        int start = current;
        Expr initializer = match(EQUAL) ? expression() : null;
        StringBuilder text = new StringBuilder();
        for (int i = start; i < current; i++) text.append(tokens.get(i).text()).append(' ');
        match(SEMICOLON);
        return new Stmt.Var(name, initializer, text.toString().trim());
    }

    /** style name { property-name: value; ... } */
    private Stmt styleDeclaration() {
        Token name = consume(IDENTIFIER, "Expected a style name after 'style'");
        consume(LEFT_BRACE, "Expected '{' after the style name");
        List<Stmt.StyleProperty> properties = new ArrayList<>();
        while (!check(RIGHT_BRACE) && !isAtEnd()) {
            Token first = consume(IDENTIFIER, "Expected a style property like 'fill'");
            StringBuilder propName = new StringBuilder(first.text());
            // Allow CSS-style hyphenated names: stroke-width, font-size
            while (match(MINUS)) {
                propName.append('-').append(consume(IDENTIFIER, "Expected the rest of the property name").text());
            }
            consume(COLON, "Expected ':' after '" + propName + "'");
            properties.add(new Stmt.StyleProperty(propName.toString(), expression(), first.line()));
            match(SEMICOLON);
        }
        consume(RIGHT_BRACE, "Expected '}' to close the style");
        return new Stmt.Style(name, properties);
    }

    // ---------------------------------------------------------------- statements

    private Stmt statement() {
        if (match(IF)) return ifStatement();
        if (match(WHILE)) return whileStatement();
        if (match(FOR)) return forStatement();
        if (match(RETURN)) return returnStatement();
        if (match(BREAK)) return endWithOptionalSemicolon(new Stmt.Break(previous()));
        if (match(CONTINUE)) return endWithOptionalSemicolon(new Stmt.Continue(previous()));
        if (match(LEFT_BRACE)) return new Stmt.Block(block());
        return endWithOptionalSemicolon(new Stmt.Expression(expression()));
    }

    private Stmt ifStatement() {
        Expr condition = expression();
        Stmt thenBranch = blockStatement("if");
        Stmt elseBranch = null;
        if (match(ELSE)) {
            elseBranch = match(IF) ? ifStatement() : blockStatement("else");
        }
        return new Stmt.If(condition, thenBranch, elseBranch);
    }

    private Stmt whileStatement() {
        Expr condition = expression();
        return new Stmt.While(condition, blockStatement("while"));
    }

    private Stmt forStatement() {
        Token variable = consume(IDENTIFIER, "Expected a loop variable, like 'for i in 0..10'");
        consume(IN, "Expected 'in' after the loop variable");
        Expr iterable = expression();
        return new Stmt.For(variable, iterable, blockStatement("for"));
    }

    private Stmt returnStatement() {
        Token keyword = previous();
        Expr value = null;
        if (!check(RIGHT_BRACE) && !check(SEMICOLON) && !isAtEnd()) {
            value = expression();
        }
        return endWithOptionalSemicolon(new Stmt.Return(keyword, value));
    }

    private Stmt blockStatement(String after) {
        consume(LEFT_BRACE, "Expected '{' after " + after);
        return new Stmt.Block(block());
    }

    /** Reads statements until the closing '}'. The opening '{' is already consumed. */
    private List<Stmt> block() {
        List<Stmt> statements = new ArrayList<>();
        while (!check(RIGHT_BRACE) && !isAtEnd()) {
            statements.add(declaration());
        }
        consume(RIGHT_BRACE, "Expected '}' to close the block");
        return statements;
    }

    private Stmt endWithOptionalSemicolon(Stmt stmt) {
        match(SEMICOLON);
        return stmt;
    }

    // ---------------------------------------------------------------- expressions

    private Expr expression() {
        return assignment();
    }

    private Expr assignment() {
        Expr target = conditional();

        if (match(EQUAL, PLUS_EQUAL, MINUS_EQUAL, STAR_EQUAL, SLASH_EQUAL, PERCENT_EQUAL)) {
            Token operator = previous();
            Expr value = assignment();
            if (operator.type() != EQUAL) {
                // x += 1  becomes  x = x + 1
                value = new Expr.Binary(target, compoundToBinary(operator), value);
            }
            return switch (target) {
                case Expr.Variable v -> new Expr.Assign(v.name(), value);
                case Expr.Get g -> new Expr.Set(g.object(), g.name(), value);
                case Expr.Index i -> new Expr.IndexSet(i.object(), i.bracket(), i.index(), value);
                default -> throw new SevenError("You can only assign to a variable, property or list item", operator.line());
            };
        }
        return target;
    }

    private Token compoundToBinary(Token op) {
        TokenType type = switch (op.type()) {
            case PLUS_EQUAL -> PLUS;
            case MINUS_EQUAL -> MINUS;
            case STAR_EQUAL -> STAR;
            case PERCENT_EQUAL -> PERCENT;
            default -> SLASH;
        };
        return new Token(type, op.text().substring(0, 1), null, op.line(), op.file());
    }

    /** condition ? whenTrue : whenFalse  (e.g. var color = health < 30 ? #ff5555 : #50fa7b) */
    private Expr conditional() {
        Expr condition = orElse();
        if (match(QUESTION)) {
            Token question = previous();
            Expr whenTrue = conditional();
            consume(COLON, "Expected ':' in 'condition ? this : that'");
            return new Expr.Conditional(condition, question, whenTrue, conditional());
        }
        return condition;
    }

    /** value ?? fallback: the value, or the fallback if the value is none (find("Boss") ?? entity) */
    private Expr orElse() {
        Expr expr = range();
        while (match(QUESTION_QUESTION)) {
            Token operator = previous();
            expr = new Expr.Logical(expr, operator, range());
        }
        return expr;
    }

    private Expr range() {
        Expr from = or();
        if (match(DOT_DOT)) {
            Token operator = previous();
            return new Expr.Range(from, operator, or());
        }
        return from;
    }

    private Expr or() {
        Expr expr = and();
        while (match(OR)) {
            Token operator = previous();
            expr = new Expr.Logical(expr, operator, and());
        }
        return expr;
    }

    private Expr and() {
        Expr expr = equality();
        while (match(AND)) {
            Token operator = previous();
            expr = new Expr.Logical(expr, operator, equality());
        }
        return expr;
    }

    private Expr equality() {
        Expr expr = comparison();
        while (match(EQUAL_EQUAL, BANG_EQUAL)) {
            Token operator = previous();
            expr = new Expr.Binary(expr, operator, comparison());
        }
        return expr;
    }

    /** < <= > >= and 'in' (is it in a list, a key of a map, part of a string?) */
    private Expr comparison() {
        Expr expr = term();
        while (match(LESS, LESS_EQUAL, GREATER, GREATER_EQUAL, IN)) {
            Token operator = previous();
            Expr right = term();
            // 'x in 0..10' means x in the range, not (x in 0)..10
            if (operator.type() == IN && match(DOT_DOT)) right = new Expr.Range(right, previous(), term());
            expr = new Expr.Binary(expr, operator, right);
        }
        return expr;
    }

    private Expr term() {
        Expr expr = factor();
        while (match(PLUS, MINUS)) {
            Token operator = previous();
            expr = new Expr.Binary(expr, operator, factor());
        }
        return expr;
    }

    private Expr factor() {
        Expr expr = unary();
        while (match(STAR, SLASH, PERCENT)) {
            Token operator = previous();
            expr = new Expr.Binary(expr, operator, unary());
        }
        return expr;
    }

    private Expr unary() {
        if (match(BANG, NOT, MINUS)) {
            Token operator = previous();
            return new Expr.Unary(operator, unary());
        }
        return call();
    }

    private Expr call() {
        Expr expr = primary();
        while (true) {
            if (match(LEFT_PAREN)) {
                expr = finishCall(expr);
            } else if (match(DOT)) {
                expr = new Expr.Get(expr, propertyName());
            } else if (match(LEFT_BRACKET)) {
                Token bracket = previous();
                Expr index = expression();
                consume(RIGHT_BRACKET, "Expected ']' after the index");
                expr = new Expr.Index(expr, bracket, index);
            } else {
                return expr;
            }
        }
    }

    private Expr finishCall(Expr callee) {
        List<Expr> arguments = new ArrayList<>();
        if (!check(RIGHT_PAREN)) {
            do {
                arguments.add(expression());
            } while (match(COMMA));
        }
        Token paren = consume(RIGHT_PAREN, "Expected ')' after the arguments");
        return new Expr.Call(callee, paren, arguments);
    }

    private Expr primary() {
        if (match(TRUE)) return new Expr.Literal(true);
        if (match(FALSE)) return new Expr.Literal(false);
        if (match(NONE)) return new Expr.Literal(null);
        if (match(NUMBER, STRING, COLOR)) return new Expr.Literal(previous().literal());
        if (match(IDENTIFIER)) return new Expr.Variable(previous());
        if (match(FORMAT)) return format(previous());

        // func(x) { ... }: a function as a value (for callbacks, filter(), sort()...)
        if (match(FUNC)) {
            Token keyword = previous();
            consume(LEFT_PAREN, "Expected '(' after 'func'");
            return new Expr.Lambda(functionRest(new Token(IDENTIFIER, "function", null, keyword.line(), keyword.file())));
        }

        // {key: value, ...}: a map
        if (match(LEFT_BRACE)) {
            Token brace = previous();
            List<String> keys = new ArrayList<>();
            List<Expr> values = new ArrayList<>();
            if (!check(RIGHT_BRACE)) {
                do {
                    if (check(RIGHT_BRACE)) break; // a comma after the last entry is fine
                    Token key = peek();
                    if (key.type() == STRING) keys.add((String) advance().literal());
                    else keys.add(propertyName().text());
                    if (keys.subList(0, keys.size() - 1).contains(keys.get(keys.size() - 1))) {
                        throw new SevenError("The key '" + keys.get(keys.size() - 1) + "' is in this map twice", key.line());
                    }
                    consume(COLON, "Expected ':' after the key '" + keys.get(keys.size() - 1) + "'");
                    values.add(expression());
                } while (match(COMMA));
            }
            consume(RIGHT_BRACE, "Expected '}' to close the map");
            return new Expr.MapLiteral(brace, keys, values);
        }

        if (match(LEFT_PAREN)) {
            Expr expr = expression();
            consume(RIGHT_PAREN, "Expected ')' to close the parentheses");
            return expr;
        }

        if (match(LEFT_BRACKET)) {
            List<Expr> elements = new ArrayList<>();
            if (!check(RIGHT_BRACKET)) {
                do {
                    elements.add(expression());
                } while (match(COMMA));
            }
            consume(RIGHT_BRACKET, "Expected ']' to close the list");
            return new Expr.ListLiteral(elements);
        }

        Token token = peek();
        String found = token.type() == EOF ? "the end of the file" : "'" + token.text() + "'";
        throw new SevenError("Unexpected " + found, token.line());
    }

    /** Splits f"HP: {hp} / {max}" into text and expressions. \{ and \} are plain braces. */
    private Expr format(Token token) {
        String raw = (String) token.literal();
        List<Object> parts = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length() && (raw.charAt(i + 1) == '{' || raw.charAt(i + 1) == '}')) {
                text.append(raw.charAt(i + 1));
                i += 2;
            } else if (c == '{') {
                int depth = 1;
                int end = i + 1;
                char quote = 0;
                while (end < raw.length() && depth > 0) {
                    char d = raw.charAt(end);
                    if (quote != 0) { if (d == quote) quote = 0; }
                    else if (d == '"' || d == '\'') quote = d;
                    else if (d == '{') depth++;
                    else if (d == '}') depth--;
                    if (depth > 0) end++;
                }
                if (depth > 0) throw new SevenError("A '{' in this f\"...\" string is never closed with '}'", token.line());
                String code = raw.substring(i + 1, end);
                if (code.isBlank()) throw new SevenError("Empty {} in an f\"...\" string: put something inside, like {score}", token.line());
                if (!text.isEmpty()) parts.add(text.toString());
                text.setLength(0);
                Expr expr;
                try {
                    Parser inner = new Parser(new Lexer(code, token.file(), token.line()).tokenize());
                    expr = inner.expression();
                    if (!inner.isAtEnd()) throw new SevenError("Unexpected '" + inner.peek().text() + "'", token.line());
                } catch (SevenError e) {
                    throw new SevenError("Inside {" + code + "} of an f\"...\" string: " + e.getMessage(), token.line());
                }
                parts.add(expr);
                i = end + 1;
            } else if (c == '}') {
                throw new SevenError("A '}' in this f\"...\" string has no '{'. Write \\} for a plain brace", token.line());
            } else {
                text.append(c);
                i++;
            }
        }
        if (!text.isEmpty() || parts.isEmpty()) parts.add(text.toString());
        return new Expr.Format(token, parts);
    }

    // ---------------------------------------------------------------- helpers

    private boolean checkNext(TokenType type) {
        return current + 1 < tokens.size() && tokens.get(current + 1).type() == type;
    }

    private boolean match(TokenType... types) {
        for (TokenType type : types) {
            if (check(type)) {
                advance();
                return true;
            }
        }
        return false;
    }

    /** The name after a dot. Keywords are fine there (entity.behaviour("Name")), since a name can't mean anything else. */
    private Token propertyName() {
        Token name = peek();
        if (name.type() != IDENTIFIER && (name.text() == null || !name.text().matches("[A-Za-z_]\\w*"))) {
            throw new SevenError("Expected a property name after '.'", name.line());
        }
        advance();
        return name.type() == IDENTIFIER ? name : new Token(IDENTIFIER, name.text(), null, name.line(), name.file());
    }

    private Token consume(TokenType type, String message) {
        if (check(type)) return advance();
        throw new SevenError(message, peek().line());
    }

    private boolean check(TokenType type) {
        return peek().type() == type;
    }

    private Token advance() {
        if (!isAtEnd()) current++;
        return previous();
    }

    private boolean isAtEnd() {
        return peek().type() == EOF;
    }

    private Token peek() {
        return tokens.get(current);
    }

    private Token previous() {
        return tokens.get(current - 1);
    }
}
