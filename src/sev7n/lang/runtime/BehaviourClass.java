package sev7n.lang.runtime;

import sev7n.lang.ast.Stmt;

/**
 * The result of a 'behaviour Name { ... }' declaration: a template.
 * The engine creates one BehaviourInstance from it for every object it's attached to.
 */
public record BehaviourClass(Stmt.Behaviour declaration, Environment closure) {

    public String name() {
        return declaration.name().text();
    }

    public String file() {
        return declaration.name().file();
    }

    @Override
    public String toString() {
        return "<behaviour " + name() + ">";
    }
}
