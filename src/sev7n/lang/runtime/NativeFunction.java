package sev7n.lang.runtime;

import sev7n.lang.Interpreter;

import java.util.List;

/** A built-in function implemented in Java, like print() or rect(). */
public record NativeFunction(String name, int minArgs, int maxArgs, Body body) implements SevenCallable {

    @FunctionalInterface
    public interface Body {
        Object apply(Args args);
    }

    @Override
    public Object call(Interpreter interpreter, List<Object> args, int line) {
        return body.apply(new Args(name, args, line));
    }
}
