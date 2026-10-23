package sev7n.lang.ast;

import sev7n.lang.Token;

import java.util.List;

/** Statements: pieces of code that do something. */
public sealed interface Stmt {

    record Expression(Expr expression) implements Stmt { }

    /** 'initializerText' is the initializer's code as written (tokens joined), so reloads can tell if it changed. */
    record Var(Token name, Expr initializer, String initializerText) implements Stmt { }

    record Block(List<Stmt> statements) implements Stmt { }

    record If(Expr condition, Stmt thenBranch, Stmt elseBranch) implements Stmt { }

    /** 'step' runs after each time round, even after 'continue' (null for a plain while; a C-style for's i++). */
    record While(Expr condition, Stmt body, Expr step) implements Stmt { }

    /** for item in list { }   or   for i in 0..10 { } */
    record For(Token variable, Expr iterable, Stmt body) implements Stmt { }

    /** 'defaults' has one entry per parameter: the value used when it's left out (func hurt(damage, from = vec3())), or null. */
    record Func(Token name, List<Token> params, List<Expr> defaults, List<Stmt> body) implements Stmt {
        /** How many arguments a call must give: the parameters before the first one with a default. */
        public int required() {
            int count = 0;
            while (count < defaults.size() && defaults.get(count) == null) count++;
            return count;
        }
    }

    record Return(Token keyword, Expr value) implements Stmt { }

    record Break(Token keyword) implements Stmt { }

    record Continue(Token keyword) implements Stmt { }

    /** behaviour PlayerMove { var speed = 5  func update(dt) { ... } } */
    record Behaviour(Token name, List<Var> fields, List<Func> methods) implements Stmt { }

    /** CSS-like block: style player { fill: #A78BFA; stroke-width: 2; } */
    record Style(Token name, List<StyleProperty> properties) implements Stmt { }

    record StyleProperty(String name, Expr value, int line) { }
}
