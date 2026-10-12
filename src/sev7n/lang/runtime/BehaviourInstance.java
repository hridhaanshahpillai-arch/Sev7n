package sev7n.lang.runtime;

/**
 * One copy of a behaviour, attached to one object. It owns its fields,
 * so two enemies with the same 'Health' behaviour each have their own health.
 */
public final class BehaviourInstance implements SevenObject {

    private BehaviourClass type;
    private Environment scope; // holds the fields, methods and engine bindings

    public BehaviourInstance(BehaviourClass type, Environment scope) {
        this.type = type;
        this.scope = scope;
    }

    /** Hot reload: the same instance (other scripts may hold it) now runs new code. */
    public void become(BehaviourClass newType, Environment newScope) {
        this.type = newType;
        this.scope = newScope;
    }

    public BehaviourClass type() {
        return type;
    }

    public SevenCallable method(String name) {
        return scope.getLocal(name) instanceof SevenCallable method ? method : null;
    }

    @Override
    public String typeName() {
        return type.name();
    }

    @Override
    public Object getProperty(String name, int line) {
        if (!scope.hasLocal(name)) throw noSuchProperty(name, line);
        return scope.getLocal(name);
    }

    @Override
    public void setProperty(String name, Object value, int line) {
        if (!scope.hasLocal(name)) throw noSuchProperty(name, line);
        scope.define(name, value);
    }

    @Override
    public String toString() {
        return "<" + type.name() + ">";
    }
}
