package sev7n.scene;

import java.util.Map;

/**
 * Attaches a Seven behaviour to an entity. 'script' is the behaviour's name,
 * e.g. "PlayerMove" for 'behaviour PlayerMove { ... }' in scripts/PlayerMove.7n.
 */
public final class Behaviour extends Component {

    public String script = "";

    public Behaviour() { }

    public Behaviour(String script) {
        this.script = script;
    }

    @Override
    public String type() {
        return "Behaviour";
    }

    @Override
    public String displayName() {
        return script.isEmpty() ? "Behaviour" : script;
    }

    @Override
    public void save(Map<String, Object> out) {
        out.put("script", script);
    }

    @Override
    public void load(Map<String, Object> in) {
        script = Json.string(in, "script", script);
    }
}
