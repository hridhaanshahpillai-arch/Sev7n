package sev7n.scene;

import java.util.Map;

/**
 * Plays a keyframe animation (an .anim file from the Animation window) on its object: a door that
 * swings, a platform that glides, a light that pulses. Scripts: entity.playClip(), entity.stopClip().
 *
 * (Animated 3D characters, whose animations come inside the model, use the Animator instead.)
 */
public final class AnimationPlayer extends Component {

    /** The animation, e.g. "assets/animations/door.anim". */
    public String clip = "";
    public boolean playOnStart = true;
    /** 1 = normal speed, 2 = twice as fast, -1 = backwards. */
    public double speed = 1;

    // while the game runs (not saved)
    public transient AnimationClip loaded;
    public transient double time;
    public transient boolean playing;

    @Override
    public String type() {
        return "AnimationPlayer";
    }

    @Override
    public String displayName() {
        return "Animation Player";
    }

    @Override
    public void save(Map<String, Object> out) {
        out.put("clip", clip);
        out.put("playOnStart", playOnStart);
        out.put("speed", speed);
    }

    @Override
    public void load(Map<String, Object> in) {
        clip = Json.string(in, "clip", clip);
        playOnStart = Json.bool(in, "playOnStart", playOnStart);
        speed = Json.number(in, "speed", speed);
    }
}
