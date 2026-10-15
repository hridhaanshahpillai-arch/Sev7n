package sev7n.lang.runtime;

import sev7n.lang.Interpreter;
import sev7n.lang.ast.Stmt;

import java.util.List;

/** A function written in Seven with 'func'. */
public class SevenFunction implements SevenCallable {

    private final Stmt.Func declaration;
    private final Environment closure;

    public SevenFunction(Stmt.Func declaration, Environment closure) {
        this.declaration = declaration;
        this.closure = closure;
    }

    @Override
    public String name() {
        return declaration.name().text();
    }

    /** The script file this function was written in. */
    public String file() {
        return declaration.name().file();
    }

    @Override
    public int minArgs() {
        return declaration.required();
    }

    @Override
    public int maxArgs() {
        return declaration.params().size();
    }

    @Override
    public Object call(Interpreter interpreter, List<Object> args, int line) {
        Environment scope = new Environment(closure);
        for (int i = 0; i < declaration.params().size(); i++) {
            // a left-out argument gets its default, worked out fresh each call (so vec3() is a new vector every time)
            Object value = i < args.size() ? args.get(i) : interpreter.evaluateIn(declaration.defaults().get(i), scope);
            scope.define(declaration.params().get(i).text(), value);
        }
        try {
            interpreter.executeBlock(declaration.body(), scope);
        } catch (Signals.Return signal) {
            return signal.value;
        }
        return null;
    }
}
