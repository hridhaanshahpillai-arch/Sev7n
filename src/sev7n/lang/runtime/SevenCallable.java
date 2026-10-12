package sev7n.lang.runtime;

import sev7n.lang.Interpreter;

import java.util.List;

/** Anything that can be called with (): user functions and built-in functions. */
public interface SevenCallable {

    String name();

    int minArgs();

    /** Maximum number of arguments, or -1 for "any number". */
    int maxArgs();

    Object call(Interpreter interpreter, List<Object> args, int line);
}
