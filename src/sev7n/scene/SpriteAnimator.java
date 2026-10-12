package sev7n.scene;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flips through a sprite sheet's frames to animate the Sprite Renderer on the same entity.
 *
 * Each animation is a named run of frames, e.g. "run" = frames 4 to 9 at 12 per second.
 * The first animation (or the chosen default) plays when the game starts; scripts
 * switch with entity.play("jump").
 */
public final class SpriteAnimator extends Component {

    /** One named animation: frames first..last (inclusive) of the sheet. */
    public static final class Clip {
        public String name;
        public int first;
        public int last;
        public double fps = 10;
        public boolean loop = true;

        public Clip(String name, int first, int last) {
            this.name = name;
            this.first = first;
            this.last = last;
        }
    }

    public final List<Clip> clips = new ArrayList<>();
    /** Plays when the game starts; empty means the first clip. */
    public String defaultClip = "";

    // while playing (not saved)
    private Clip playing;
    private double time;
    private boolean finished;

    public SpriteAnimator() {
        clips.add(new Clip("idle", 0, 0));
    }

    public Clip clip(String name) {
        for (Clip clip : clips) {
            if (clip.name.equals(name)) return clip;
        }
        return null;
    }

    /** Starts an animation from its first frame. Playing the current one again does nothing. Returns false if it doesn't exist. */
    public boolean play(String name) {
        Clip clip = clip(name);
        if (clip == null) return false;
        if (clip != playing) {
            playing = clip;
            time = 0;
            finished = false;
        }
        return true;
    }

    /** The animation playing now, or "" if none. */
    public String current() {
        return playing == null ? "" : playing.name;
    }

    /** True once a non-looping animation has reached its last frame. */
    public boolean finished() {
        return finished;
    }

    /** Moves time forward and shows the right frame on the sprite. */
    public void update(double dt, SpriteRenderer sprite) {
        if (playing == null && !play(defaultClip) && !clips.isEmpty()) play(clips.get(0).name);
        if (playing == null) return;
        time += dt;
        int length = Math.abs(playing.last - playing.first) + 1;
        int step = (int) Math.floor(time * Math.max(0.01, playing.fps));
        if (step >= length && !playing.loop) {
            step = length - 1;
            finished = true;
        }
        step %= length;
        int direction = playing.last >= playing.first ? 1 : -1; // last < first plays backwards
        sprite.frame = playing.first + step * direction;
    }

    @Override
    public String type() {
        return "SpriteAnimator";
    }

    @Override
    public String displayName() {
        return "Sprite Animator";
    }

    @Override
    public void save(Map<String, Object> out) {
        List<Object> list = new ArrayList<>();
        for (Clip clip : clips) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", clip.name);
            data.put("first", clip.first);
            data.put("last", clip.last);
            data.put("fps", clip.fps);
            data.put("loop", clip.loop);
            list.add(data);
        }
        out.put("clips", list);
        if (!defaultClip.isEmpty()) out.put("default", defaultClip);
    }

    @Override
    public void load(Map<String, Object> in) {
        if (in.containsKey("clips")) clips.clear();
        for (Object item : Json.list(in, "clips")) {
            Map<String, Object> data = Json.object(item);
            Clip clip = new Clip(Json.string(data, "name", "clip"),
                    (int) Json.number(data, "first", 0), (int) Json.number(data, "last", 0));
            clip.fps = Json.number(data, "fps", clip.fps);
            clip.loop = Json.bool(data, "loop", clip.loop);
            clips.add(clip);
        }
        defaultClip = Json.string(in, "default", defaultClip);
    }
}
