package sev7n.lang;

import sev7n.lang.ast.Expr;
import sev7n.lang.ast.Stmt;
import sev7n.lang.runtime.BehaviourClass;
import sev7n.lang.runtime.BehaviourInstance;
import sev7n.lang.runtime.Environment;
import sev7n.lang.runtime.NativeFunction;
import sev7n.lang.runtime.SevenCallable;
import sev7n.lang.runtime.SevenFunction;
import sev7n.lang.runtime.SevenObject;
import sev7n.lang.runtime.SevenRange;
import sev7n.lang.runtime.Signals;
import sev7n.lang.runtime.Style;
import sev7n.lang.runtime.Values;
import sev7n.lang.runtime.Vectors;
import sev7n.math.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Runs Seven code by walking the syntax tree.
 *
 * Typical use by the engine:
 *   Interpreter seven = new Interpreter(log::print);
 *   seven.run(source, "PlayerMove.7n");          // defines the behaviour
 *   var instance = seven.instantiate(type, Map.of("entity", ...));
 *   seven.callMethod(instance, "update", dt);    // every frame
 */
public class Interpreter {

    /** How many statements a single call may run before we assume an infinite loop. */
    private static final long STEP_LIMIT = 20_000_000;

    private final Environment globals = new Environment();
    private final Consumer<String> output;
    private Environment environment = globals;
    private String currentFile;
    private long steps = 0;

    public Interpreter(Consumer<String> output) {
        this.output = output;
        CoreLibrary.install(this);
    }

    // ---------------------------------------------------------------- public API

    /** Runs a script's top-level code (usually just 'behaviour' declarations). */
    public void run(String source, String file) {
        List<Token> tokens = new Lexer(source, file).tokenize();
        List<Stmt> program = new Parser(tokens).parse();
        steps = 0;
        String previousFile = currentFile;
        currentFile = file;
        try {
            for (Stmt stmt : program) {
                execute(stmt);
            }
        } catch (SevenError e) {
            throw e.inFile(file);
        } finally {
            currentFile = previousFile;
        }
    }

    /**
     * Creates a fresh copy of a behaviour. 'bindings' become variables its code can use
     * directly, such as 'entity' and 'transform'. Field initializers run here.
     */
    public BehaviourInstance instantiate(BehaviourClass type, Map<String, Object> bindings) {
        Environment scope = new Environment(type.closure());
        bindings.forEach(scope::define);
        steps = 0;
        String previousFile = currentFile;
        currentFile = type.file();
        try {
            executeBlock(new ArrayList<>(type.declaration().fields()), scope);
        } catch (SevenError e) {
            throw e.inFile(type.file());
        } finally {
            currentFile = previousFile;
        }
        for (Stmt.Func method : type.declaration().methods()) {
            scope.define(method.name().text(), new SevenFunction(method, scope));
        }
        return new BehaviourInstance(type, scope);
    }

    /** Reads a script without running it, so syntax errors show up before anything changes. */
    public void check(String source, String file) {
        new Parser(new Lexer(source, file).tokenize()).parse();
    }

    /**
     * Hot reload: switches a running instance to a new version of its behaviour's code.
     * Fields whose initializer is unchanged keep their current values (the game's state);
     * new fields, and fields whose initializer was edited, start from the new code.
     */
    public void upgrade(BehaviourInstance instance, BehaviourClass newType, Map<String, Object> bindings) {
        Map<String, String> oldInitializers = new java.util.HashMap<>();
        for (Stmt.Var field : instance.type().declaration().fields()) {
            oldInitializers.put(field.name().text(), field.initializerText());
        }
        Environment scope = new Environment(newType.closure());
        bindings.forEach(scope::define);
        List<Stmt> fresh = new ArrayList<>();
        for (Stmt.Var field : newType.declaration().fields()) {
            String name = field.name().text();
            if (field.initializerText().equals(oldInitializers.get(name))) scope.define(name, instance.getProperty(name, 0));
            else fresh.add(field);
        }
        steps = 0;
        String previousFile = currentFile;
        currentFile = newType.file();
        try {
            executeBlock(fresh, scope);
        } catch (SevenError e) {
            throw e.inFile(newType.file());
        } finally {
            currentFile = previousFile;
        }
        for (Stmt.Func method : newType.declaration().methods()) {
            scope.define(method.name().text(), new SevenFunction(method, scope));
        }
        instance.become(newType, scope);
    }

    /**
     * Calls a behaviour's method if it has one. Extra arguments are dropped, so a script
     * may write either 'func update(dt)' or just 'func update()'.
     */
    public Object callMethod(BehaviourInstance instance, String name, Object... args) {
        SevenCallable method = instance.method(name);
        if (method == null) return null;
        List<Object> argList = new ArrayList<>(List.of(args));
        if (method.maxArgs() >= 0 && argList.size() > method.maxArgs()) {
            argList = argList.subList(0, method.maxArgs());
        }
        steps = 0;
        return invoke(method, argList, 0);
    }

    /** Calls a function value from a script (e.g. a callback). Extra arguments are dropped, like callMethod. */
    public Object callFunction(SevenCallable function, Object... args) {
        List<Object> argList = new ArrayList<>(List.of(args));
        if (function.maxArgs() >= 0 && argList.size() > function.maxArgs()) argList = argList.subList(0, function.maxArgs());
        steps = 0;
        return invoke(function, argList, 0);
    }

    /**
     * Calls a function value from inside a built-in that a script called (filter, sort...). Unlike
     * callFunction it doesn't restart the infinite-loop counter, so a runaway loop is still caught.
     */
    public Object callNested(SevenCallable function, Object... args) {
        List<Object> argList = new ArrayList<>(List.of(args));
        if (function.maxArgs() >= 0 && argList.size() > function.maxArgs()) argList = argList.subList(0, function.maxArgs());
        return invoke(function, argList, 0);
    }

    public Object lookupGlobal(String name) {
        return globals.lookup(name);
    }

    /** Registers a built-in function. maxArgs = -1 means "any number". */
    public void defineNative(String name, int minArgs, int maxArgs, NativeFunction.Body body) {
        globals.define(name, new NativeFunction(name, minArgs, maxArgs, body));
    }

    public void defineGlobal(String name, Object value) {
        globals.define(name, value);
    }

    public void print(String text) {
        output.accept(text);
    }

    /** Every global name: built-ins plus anything the script has defined so far. */
    public Set<String> globalNames() {
        return globals.names();
    }

    // ---------------------------------------------------------------- statements

    public void executeBlock(List<Stmt> statements, Environment scope) {
        Environment previous = this.environment;
        try {
            this.environment = scope;
            for (Stmt stmt : statements) {
                execute(stmt);
            }
        } finally {
            this.environment = previous;
        }
    }

    private void execute(Stmt stmt) {
        if (++steps > STEP_LIMIT) {
            throw new SevenError("Script is taking too long. Is there an infinite loop?", lineOf(stmt));
        }

        switch (stmt) {
            case Stmt.Expression s -> evaluate(s.expression());
            case Stmt.Var s -> environment.define(s.name().text(),
                    s.initializer() == null ? null : evaluate(s.initializer()));
            case Stmt.Block s -> executeBlock(s.statements(), new Environment(environment));
            case Stmt.If s -> {
                if (Values.isTruthy(evaluate(s.condition()))) execute(s.thenBranch());
                else if (s.elseBranch() != null) execute(s.elseBranch());
            }
            case Stmt.While s -> {
                while (Values.isTruthy(evaluate(s.condition()))) {
                    try {
                        execute(s.body());
                    } catch (Signals.Break b) {
                        break;
                    } catch (Signals.Continue c) {
                        // next iteration
                    }
                    if (s.step() != null) evaluate(s.step());
                }
            }
            case Stmt.For s -> executeFor(s);
            case Stmt.Func s -> environment.define(s.name().text(), new SevenFunction(s, environment));
            case Stmt.Return s -> throw new Signals.Return(s.value() == null ? null : evaluate(s.value()));
            case Stmt.Break s -> throw Signals.Break.INSTANCE;
            case Stmt.Continue s -> throw Signals.Continue.INSTANCE;
            case Stmt.Style s -> {
                Style style = new Style(s.name().text());
                for (Stmt.StyleProperty property : s.properties()) {
                    style.set(property.name(), evaluateStyleValue(property.value()));
                }
                environment.define(s.name().text(), style);
            }
            case Stmt.Behaviour s -> environment.define(s.name().text(), new BehaviourClass(s, environment));
        }
    }

    /** CSS-style keywords: in 'align: center', an unknown bare word is just the string "center". */
    private Object evaluateStyleValue(Expr value) {
        if (value instanceof Expr.Variable v && !environment.isDefined(v.name().text())) {
            return v.name().text();
        }
        return evaluate(value);
    }

    private void executeFor(Stmt.For loop) {
        Object iterable = evaluate(loop.iterable());
        switch (iterable) {
            case SevenRange range -> {
                double step = range.from() <= range.to() ? 1 : -1;
                for (double i = range.from(); step > 0 ? i < range.to() : i > range.to(); i += step) {
                    if (!runLoopBody(loop, i)) return;
                }
            }
            case List<?> list -> {
                // Loop over a copy, so the body is free to add or remove items.
                for (Object item : new ArrayList<>(list)) {
                    if (!runLoopBody(loop, item)) return;
                }
            }
            case Map<?, ?> map -> {
                // a map's keys (look the value up with map[key])
                for (Object key : new ArrayList<>(map.keySet())) {
                    if (!runLoopBody(loop, key)) return;
                }
            }
            case String s -> {
                for (char c : s.toCharArray()) {
                    if (!runLoopBody(loop, String.valueOf(c))) return;
                }
            }
            case null, default -> throw new SevenError("Can't loop over a " + Values.typeName(iterable), loop.variable().line());
        }
    }

    /** Runs one iteration. Returns false if the loop hit 'break'. */
    private boolean runLoopBody(Stmt.For loop, Object value) {
        Environment scope = new Environment(environment);
        scope.define(loop.variable().text(), value);
        try {
            executeBlock(List.of(loop.body()), scope);
        } catch (Signals.Break b) {
            return false;
        } catch (Signals.Continue c) {
            // next iteration
        }
        return true;
    }

    // ---------------------------------------------------------------- expressions

    private Object evaluate(Expr expr) {
        return switch (expr) {
            case Expr.Literal e -> e.value();
            case Expr.Variable e -> environment.get(e.name());
            case Expr.Assign e -> {
                Object value = evaluate(e.value());
                environment.assign(e.name(), value);
                yield value;
            }
            case Expr.Unary e -> evaluateUnary(e);
            case Expr.Binary e -> evaluateBinary(e);
            case Expr.Logical e -> {
                Object left = evaluate(e.left());
                if (e.operator().type() == TokenType.QUESTION_QUESTION) yield left != null ? left : evaluate(e.right());
                boolean isOr = e.operator().type() == TokenType.OR;
                if (isOr == Values.isTruthy(left)) yield left;
                yield evaluate(e.right());
            }
            case Expr.Call e -> evaluateCall(e);
            case Expr.Get e -> evaluateGet(e);
            case Expr.Set e -> evaluateSet(e);
            case Expr.Index e -> evaluateIndex(e);
            case Expr.IndexSet e -> evaluateIndexSet(e);
            case Expr.ListLiteral e -> {
                List<Object> list = new ArrayList<>();
                for (Expr element : e.elements()) list.add(evaluate(element));
                yield list;
            }
            case Expr.Range e -> new SevenRange(
                    number(evaluate(e.from()), e.operator()),
                    number(evaluate(e.to()), e.operator()));
            case Expr.Conditional e -> Values.isTruthy(evaluate(e.condition())) ? evaluate(e.whenTrue()) : evaluate(e.whenFalse());
            case Expr.Lambda e -> new SevenFunction(e.function(), environment);
            case Expr.MapLiteral e -> {
                Map<String, Object> map = new LinkedHashMap<>();
                for (int i = 0; i < e.keys().size(); i++) map.put(e.keys().get(i), evaluate(e.values().get(i)));
                yield map;
            }
            case Expr.Format e -> {
                StringBuilder text = new StringBuilder();
                for (Object part : e.parts()) text.append(part instanceof Expr x ? Values.stringify(evaluate(x)) : (String) part);
                yield text.toString();
            }
        };
    }

    /** Works out an expression in a given scope (used for parameter defaults). */
    public Object evaluateIn(Expr expr, Environment scope) {
        Environment previous = environment;
        environment = scope;
        try {
            return evaluate(expr);
        } finally {
            environment = previous;
        }
    }

    private Object evaluateUnary(Expr.Unary e) {
        Object right = evaluate(e.right());
        return switch (e.operator().type()) {
            case MINUS -> right instanceof Vec3 v ? v.negated() : -number(right, e.operator());
            default -> !Values.isTruthy(right); // ! and not
        };
    }

    private Object evaluateBinary(Expr.Binary e) {
        Object left = evaluate(e.left());
        Object right = evaluate(e.right());
        Token op = e.operator();

        boolean arithmetic = switch (op.type()) {
            case PLUS, MINUS, STAR, SLASH -> true;
            default -> false;
        };
        boolean joiningText = op.type() == TokenType.PLUS && (left instanceof String || right instanceof String);
        if (arithmetic && !joiningText && (left instanceof Vec3 || right instanceof Vec3)) {
            return vectorMath(left, right, op);
        }

        return switch (op.type()) {
            case PLUS -> add(left, right, op);
            case MINUS -> number(left, op) - number(right, op);
            case STAR -> number(left, op) * number(right, op);
            case SLASH -> {
                double divisor = number(right, op);
                if (divisor == 0) throw new SevenError("Division by zero", op.line());
                yield number(left, op) / divisor;
            }
            case PERCENT -> {
                // Always positive, so (x % width) is handy for screen wrapping.
                double m = number(right, op);
                if (m == 0) throw new SevenError("Modulo by zero", op.line());
                yield ((number(left, op) % m) + m) % m;
            }
            case EQUAL_EQUAL -> Values.isEqual(left, right);
            case BANG_EQUAL -> !Values.isEqual(left, right);
            case LESS -> compare(left, right, op) < 0;
            case LESS_EQUAL -> compare(left, right, op) <= 0;
            case GREATER -> compare(left, right, op) > 0;
            case GREATER_EQUAL -> compare(left, right, op) >= 0;
            case IN -> contains(right, left, op);
            default -> throw new SevenError("Unknown operator '" + op.text() + "'", op.line());
        };
    }

    /** 'item in container': an item of a list, a key of a map, a piece of a string, a number in a range. */
    private boolean contains(Object container, Object item, Token op) {
        return switch (container) {
            case List<?> list -> list.stream().anyMatch(x -> Values.isEqual(x, item));
            case Map<?, ?> map -> map.containsKey(Values.stringify(item));
            case String s -> s.contains(Values.stringify(item));
            case SevenRange r -> item instanceof Double d && (r.from() <= r.to() ? d >= r.from() && d < r.to() : d <= r.from() && d > r.to());
            case null, default -> throw new SevenError("Can't look 'in' a " + Values.typeName(container) + ": use a list, map or string", op.line());
        };
    }

    private Object add(Object left, Object right, Token op) {
        if (left instanceof Double a && right instanceof Double b) return a + b;
        if (left instanceof String || right instanceof String) {
            return Values.stringify(left) + Values.stringify(right);
        }
        if (left instanceof List<?> a && right instanceof List<?> b) {
            List<Object> joined = new ArrayList<>(a);
            joined.addAll(b);
            return joined;
        }
        throw new SevenError("Can't add a " + Values.typeName(left) + " and a " + Values.typeName(right), op.line());
    }

    /** vec3 + vec3, vec3 - vec3, vec3 * number, number * vec3, vec3 / number */
    private Object vectorMath(Object left, Object right, Token op) {
        return switch (op.type()) {
            case PLUS -> vector(left, op).plus(vector(right, op));
            case MINUS -> vector(left, op).minus(vector(right, op));
            case STAR -> left instanceof Vec3 v ? v.times(number(right, op)) : vector(right, op).times(number(left, op));
            case SLASH -> {
                double divisor = number(right, op);
                if (divisor == 0) throw new SevenError("Division by zero", op.line());
                yield vector(left, op).times(1 / divisor);
            }
            default -> throw new SevenError("Can't use '" + op.text() + "' on a vec3", op.line());
        };
    }

    private Vec3 vector(Object value, Token where) {
        if (value instanceof Vec3 v) return v;
        throw new SevenError("Expected a vec3 but got " + Values.typeName(value), where.line());
    }

    private int compare(Object left, Object right, Token op) {
        if (left instanceof Double a && right instanceof Double b) return Double.compare(a, b);
        if (left instanceof String a && right instanceof String b) return a.compareTo(b);
        throw new SevenError("Can't compare a " + Values.typeName(left) + " with a " + Values.typeName(right), op.line());
    }

    private Object evaluateCall(Expr.Call e) {
        Object callee = evaluate(e.callee());
        List<Object> args = new ArrayList<>();
        for (Expr arg : e.arguments()) args.add(evaluate(arg));

        if (!(callee instanceof SevenCallable function)) {
            throw new SevenError("A " + Values.typeName(callee) + " can't be called like a function", e.paren().line());
        }
        return invoke(function, args, e.paren().line());
    }

    private Object invoke(SevenCallable function, List<Object> args, int line) {
        int min = function.minArgs();
        int max = function.maxArgs();
        if (args.size() < min || (max >= 0 && args.size() > max)) {
            String expected = min == max ? String.valueOf(min)
                    : max < 0 ? "at least " + min
                    : min + " to " + max;
            throw new SevenError(function.name() + "() expects " + expected
                    + " argument(s) but got " + args.size(), line);
        }
        if (!(function instanceof SevenFunction scriptFunction)) {
            return function.call(this, args, line);
        }

        // Remember which file we're in, so errors can say "PlayerMove.7n:12"
        String previousFile = currentFile;
        currentFile = scriptFunction.file();
        try {
            return function.call(this, args, line);
        } catch (SevenError e) {
            throw e.inFile(currentFile);
        } finally {
            currentFile = previousFile;
        }
    }

    private Object evaluateGet(Expr.Get e) {
        Object object = evaluate(e.object());
        String name = e.name().text();
        int line = e.name().line();
        return switch (object) {
            case SevenObject o -> o.getProperty(name, line);
            case Vec3 v -> Vectors.get(v, name, line);
            case Map<?, ?> map -> mapValue(map, name, line);
            case List<?> list when name.equals("length") -> (double) list.size();
            case String s when name.equals("length") -> (double) s.length();
            case null -> throw new SevenError("Can't read '" + name + "' of none: the value is empty (was something not found, or destroyed?)", line);
            default -> throw new SevenError("A " + Values.typeName(object) + " has no property '" + name + "'", line);
        };
    }

    /** map.key and map["key"]: a missing key is an error that lists the keys there are. */
    private static Object mapValue(Map<?, ?> map, String key, int line) {
        if (map.containsKey(key)) return map.get(key);
        String hint = Suggestions.closest(key, map.keySet().stream().map(String::valueOf).toList());
        throw new SevenError("The map has no key '" + key + "'"
                + (hint != null ? ". Did you mean '" + hint + "'?" : map.isEmpty() ? " (it's empty)" : " (it has " + map.keySet() + ")")
                + " Check first with: if \"" + key + "\" in map", line);
    }

    @SuppressWarnings("unchecked")
    private Object evaluateSet(Expr.Set e) {
        Object object = evaluate(e.object());
        Object value = evaluate(e.value());
        String name = e.name().text();
        int line = e.name().line();
        switch (object) {
            case SevenObject o -> o.setProperty(name, value, line);
            case Vec3 v -> Vectors.set(v, name, value, line);
            case Map<?, ?> map -> ((Map<String, Object>) map).put(name, value);
            case null -> throw new SevenError("Can't set '" + name + "' on none: the value is empty (was something not found, or destroyed?)", line);
            default -> throw new SevenError("Can't set '" + name + "' on a " + Values.typeName(object), line);
        }
        return value;
    }

    private Object evaluateIndex(Expr.Index e) {
        Object object = evaluate(e.object());
        Object index = evaluate(e.index());
        return switch (object) {
            case List<?> list -> list.get(checkIndex(index, list.size(), e.bracket()));
            case String s -> String.valueOf(s.charAt(checkIndex(index, s.length(), e.bracket())));
            case Map<?, ?> map -> mapValue(map, Values.stringify(index), e.bracket().line());
            case null, default -> throw new SevenError("Can't use [ ] on a " + Values.typeName(object), e.bracket().line());
        };
    }

    @SuppressWarnings("unchecked")
    private Object evaluateIndexSet(Expr.IndexSet e) {
        Object object = evaluate(e.object());
        if (object instanceof Map<?, ?> map) {
            Object value = evaluate(e.value());
            ((Map<String, Object>) map).put(Values.stringify(evaluate(e.index())), value);
            return value;
        }
        if (!(object instanceof List<?> list)) {
            throw new SevenError("Can only set items on a list or map", e.bracket().line());
        }
        int i = checkIndex(evaluate(e.index()), list.size(), e.bracket());
        Object value = evaluate(e.value());
        ((List<Object>) list).set(i, value);
        return value;
    }

    /** Accepts negative indexes too: items[-1] is the last item. */
    private int checkIndex(Object index, int size, Token bracket) {
        int i = (int) number(index, bracket);
        if (i < 0) i += size;
        if (i < 0 || i >= size) {
            throw new SevenError("Index " + Values.stringify(index) + " is out of range (size is " + size + ")", bracket.line());
        }
        return i;
    }

    private double number(Object value, Token where) {
        if (value instanceof Double d) return d;
        throw new SevenError("Expected a number but got " + Values.typeName(value)
                + " (" + Values.stringify(value) + ")", where.line());
    }

    private int lineOf(Stmt stmt) {
        return switch (stmt) {
            case Stmt.Var s -> s.name().line();
            case Stmt.Func s -> s.name().line();
            case Stmt.Return s -> s.keyword().line();
            case Stmt.For s -> s.variable().line();
            default -> 0;
        };
    }
}
