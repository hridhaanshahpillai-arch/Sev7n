package sev7n.lang.ast;

import sev7n.lang.Token;

import java.util.List;

/** Expressions: pieces of code that produce a value. */
public sealed interface Expr {

    record Literal(Object value) implements Expr { }

    record Variable(Token name) implements Expr { }

    record Assign(Token name, Expr value) implements Expr { }

    record Unary(Token operator, Expr right) implements Expr { }

    record Binary(Expr left, Token operator, Expr right) implements Expr { }

    /** 'and' / 'or', kept separate because they short-circuit. */
    record Logical(Expr left, Token operator, Expr right) implements Expr { }

    record Call(Expr callee, Token paren, List<Expr> arguments) implements Expr { }

    /** Reading a property: player.fill */
    record Get(Expr object, Token name) implements Expr { }

    /** Writing a property: player.fill = #fff */
    record Set(Expr object, Token name, Expr value) implements Expr { }

    /** Reading a list item: items[0] */
    record Index(Expr object, Token bracket, Expr index) implements Expr { }

    /** Writing a list item: items[0] = 5 */
    record IndexSet(Expr object, Token bracket, Expr index, Expr value) implements Expr { }

    record ListLiteral(List<Expr> elements) implements Expr { }

    /** 0..10 (end is exclusive, like Python's range) */
    record Range(Expr from, Token operator, Expr to) implements Expr { }

    /** condition ? whenTrue : whenFalse */
    record Conditional(Expr condition, Token question, Expr whenTrue, Expr whenFalse) implements Expr { }

    /** func(x) { return x * 2 }: a function without a name, as a value */
    record Lambda(Stmt.Func function) implements Expr { }

    /** {name: "Bob", hp: 100}: keys are names or strings */
    record MapLiteral(Token brace, List<String> keys, List<Expr> values) implements Expr { }

    /** f"Score: {score}": each part is a String (as written) or an Expr (worked out and joined in) */
    record Format(Token token, List<Object> parts) implements Expr { }
}
