package sev7n.scene;

import java.util.Map;

/**
 * A sound that belongs to an object: a campfire's crackle, a radio's music, an engine's hum.
 * 3D sources are heard from where the object is (louder near the camera, from the left or
 * right) and follow it as it moves. Plays by itself when the game starts, or from scripts
 * (entity.playAudio() / entity.stopAudio()).
 */
public final class AudioSource extends Component {

    /** The sound file, e.g. "assets/sounds/fire.ogg" (.wav or .ogg). */
    public String clip = "";
    /** 1 = as recorded, 0.5 = quieter. */
    public double volume = 1;
    /** 1 = normal, 2 = an octave higher and twice as fast, 0.5 = lower and slower. */
    public double pitch = 1;
    public boolean loop = true;
    public boolean playOnStart = true;
    /** Heard from where the object is (3D), or the same everywhere (flat: music, voices over the radio). */
    public boolean spatial = true;
    /** How far away it can be heard (3D only). */
    public double range = 25;
    /** Which Audio Mixer group it plays through. */
    public String bus = "Effects";

    /** The playing sound while the game runs (an AudioMixer.Voice; not saved). */
    public transient Object voice;

    @Override
    public String type() {
        return "AudioSource";
    }

    @Override
    public String displayName() {
        return "Audio Source";
    }

    @Override
    public void save(Map<String, Object> out) {
        out.put("clip", clip);
        out.put("volume", volume);
        out.put("pitch", pitch);
        out.put("loop", loop);
        out.put("playOnStart", playOnStart);
        out.put("spatial", spatial);
        out.put("range", range);
        out.put("bus", bus);
    }

    @Override
    public void load(Map<String, Object> in) {
        clip = Json.string(in, "clip", clip);
        volume = Math.max(0, Json.number(in, "volume", volume));
        pitch = Math.max(0.05, Math.min(8, Json.number(in, "pitch", pitch)));
        loop = Json.bool(in, "loop", loop);
        playOnStart = Json.bool(in, "playOnStart", playOnStart);
        spatial = Json.bool(in, "spatial", spatial);
        range = Math.max(0.1, Json.number(in, "range", range));
        bus = Json.string(in, "bus", bus);
    }
}
