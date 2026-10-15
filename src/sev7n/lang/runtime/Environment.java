package sev7n.lang.runtime;

import sev7n.lang.SevenError;
import sev7n.lang.Suggestions;
import sev7n.lang.Token;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** A scope that holds variables. Scopes nest: a function body sees its enclosing scope. */
public class Environment {

    private final Environment enclosing;
    private final Map<String, Object> values = new HashMap<>();

    public Environment() {
        this(null);
    }

    public Environment(Environment enclosing) {
        this.enclosing = enclosing;
    }

    public void define(String name, Object value) {
        values.put(name, value);
    }

    public Object get(Token name) {
        Environment scope = find(name.text());
        if (scope == null) {
            throw new SevenError("Unknown variable '" + name.text() + "'" + didYouMean(name.text()), name.line());
        }
        return scope.values.get(name.text());
    }

    /** ". Did you mean 'speed'?" when a name is a typo of one that exists, else "". */
    private String didYouMean(String name) {
        // nearer scopes first, so your own 'speed' beats the built-in 'seed'
        java.util.List<java.util.Collection<String>> known = new java.util.ArrayList<>();
        for (Environment scope = this; scope != null; scope = scope.enclosing) known.add(scope.values.keySet());
        String hint = Suggestions.closest(name, known);
        return hint == null ? "" : ". Did you mean '" + hint + "'?";
    }

    /** True if the name is defined directly in this scope (ignoring enclosing scopes). */
    public boolean hasLocal(String name) {
        return values.containsKey(name);
    }

    public Object getLocal(String name) {
        return values.get(name);
    }

    /** Names defined directly in this scope (not enclosing ones). */
    public Set<String> names() {
        return Set.copyOf(values.keySet());
    }

    public boolean isDefined(String name) {
        return find(name) != null;
    }

    /** Looks a name up without throwing. Returns null if it doesn't exist. */
    public Object lookup(String name) {
        Environment scope = find(name);
        return scope == null ? null : scope.values.get(name);
    }

    public void assign(Token name, Object value) {
        Environment scope = find(name.text());
        if (scope == null) {
            String hint = didYouMean(name.text());
            throw new SevenError("Unknown variable '" + name.text() + "'"
                    + (hint.isEmpty() ? ". Declare it first with: var " + name.text() + " = ..." : hint), name.line());
        }
        scope.values.put(name.text(), value);
    }

    private Environment find(String name) {
        for (Environment scope = this; scope != null; scope = scope.enclosing) {
            if (scope.values.containsKey(name)) return scope;
        }
        return null;
    }
}
