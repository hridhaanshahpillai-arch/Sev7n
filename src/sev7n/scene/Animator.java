package sev7n.scene;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plays the animations inside an animated 3D model (on the same entity's Mesh Renderer):
 * walking, running, attacking... Changing animation blends smoothly over 'fade' seconds.
 *
 * Scripts switch with entity.play("Run") or entity.play("Jump", 0.1). The model's animation
 * names (and lengths) are filled in by the renderer the first time it draws the model.
 */
public final class Animator extends Component {

    /** Plays when the game starts; empty means the model's first animation. */
    public String clip = "";
    /** 1 = normal speed, 2 = twice as fast, 0.5 = slow motion. */
    public double speed = 1;
    public boolean loop = true;
    public boolean playOnStart = true;
    /** Seconds to blend from one animation into the next. */
    public double fade = 0.2;

    // while running (not saved)
    private List<String> available = List.of();
    private final Map<String, Double> lengths = new HashMap<>();
    private String playing;
    private double time;
    private String previous;
    private double previousTime;
    private double fadeLength;
    private double fadeTime;

    /** The model's animations, as the renderer found them. */
    public List<String> available() {
        return available;
    }

    /** Called by the renderer with the model's animation names and lengths (seconds). */
    public void setClips(List<String> names, Map<String, Double> clipLengths) {
        available = List.copyOf(names);
        lengths.clear();
        lengths.putAll(clipLengths);
    }

    /** Starts an animation, blending in over the usual fade time. Playing the current one again does nothing. */
    public boolean play(String name) {
        return play(name, fade);
    }

    /** Starts an animation, blending in over 'fadeSeconds' (0 = switch instantly). Returns false if there's no such animation. */
    public boolean play(String name, double fadeSeconds) {
        if (!available.isEmpty() && !available.contains(name)) return false;
        if (name.equals(playing)) return true;
        if (playing != null && fadeSeconds > 0) {
            previous = playing;
            previousTime = time;
            fadeLength = fadeSeconds;
            fadeTime = 0;
        } else {
            previous = null;
        }
        playing = name;
        time = 0;
        return true;
    }

    /** Stops on the rest pose. */
    public void stop() {
        playing = null;
        previous = null;
    }

    /** Moves time forward. */
    public void update(double dt) {
        if (playing == null && playOnStart && !available.isEmpty()) {
            play(clip.isEmpty() || !available.contains(clip) ? available.get(0) : clip, 0);
        }
        double step = dt * speed;
        time += step;
        if (previous != null) {
            previousTime += step;
            fadeTime += dt;
            if (fadeTime >= fadeLength) previous = null;
        }
    }

    /** The animation playing now, or "" if none. */
    public String current() {
        return playing == null ? "" : playing;
    }

    /** True once a non-looping animation has reached its end. */
    public boolean done() {
        Double length = lengths.get(current());
        return !loop && length != null && time >= length;
    }

    /** True once the current animation has played to its end at least once (looping or not). */
    public boolean playedThrough() {
        Double length = lengths.get(current());
        return length != null && time >= length;
    }

    /** Where the current animation is, in seconds (wrapped when looping). */
    public double time() {
        return sampleTime(playing, time);
    }

    /** The animation being blended out (or null), and where it is. */
    public String previous() {
        return previous;
    }

    public double previousTime() {
        return sampleTime(previous, previousTime);
    }

    /** How much of the current animation shows while blending, 0..1 (1 once the fade is over). */
    public double blend() {
        return previous == null || fadeLength <= 0 ? 1 : Math.min(1, fadeTime / fadeLength);
    }

    private double sampleTime(String name, double t) {
        Double length = name == null ? null : lengths.get(name);
        if (length == null || length <= 0) return t;
        return loop ? t % length : Math.min(t, length);
    }

    @Override
    public String type() {
        return "Animator";
    }

    @Override
    public String displayName() {
        return "Animator";
    }

    @Override
    public void save(Map<String, Object> out) {
        out.put("clip", clip);
        out.put("speed", speed);
        out.put("loop", loop);
        out.put("playOnStart", playOnStart);
        out.put("fade", fade);
    }

    @Override
    public void load(Map<String, Object> in) {
        clip = Json.string(in, "clip", clip);
        speed = Json.number(in, "speed", speed);
        loop = Json.bool(in, "loop", loop);
        playOnStart = Json.bool(in, "playOnStart", playOnStart);
        fade = Json.number(in, "fade", fade);
    }
}
