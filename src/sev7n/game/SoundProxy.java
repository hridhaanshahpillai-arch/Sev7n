package sev7n.game;

import sev7n.audio.AudioMixer;
import sev7n.lang.SevenError;
import sev7n.lang.runtime.NativeFunction;
import sev7n.lang.runtime.SevenObject;
import sev7n.lang.runtime.Values;
import sev7n.math.Vec3;

import java.util.List;

/**
 * A playing sound in scripts, from playSound / playSoundAt:
 *   var engine = playSound("assets/sounds/engine.ogg")
 *   engine.pitch = 1 + speed / 20     // change it while it plays
 *   engine.volume = 0.5
 *   engine.position = transform.position   (3D sounds: move it)
 *   engine.fadeOut(1)                 // fades away over 1 second, then stops
 *   engine.stop()  ·  engine.pause()  ·  engine.resume()  ·  engine.playing  ·  engine.time
 */
final class SoundProxy implements SevenObject {

    private static final List<String> PROPERTIES = List.of("playing", "volume", "pitch", "position", "time", "loop",
            "stop", "pause", "resume", "fadeOut", "fadeTo");

    private final AudioMixer.Voice voice;
    private Vec3 position;

    SoundProxy(AudioMixer.Voice voice, Vec3 position) {
        this.voice = voice;
        this.position = position;
    }

    @Override
    public String typeName() {
        return "sound";
    }

    @Override
    public java.util.Collection<String> propertyNames() {
        return PROPERTIES;
    }

    @Override
    public Object getProperty(String name, int line) {
        return switch (name) {
            case "playing" -> voice.playing();
            case "volume" -> voice.volume();
            case "pitch" -> voice.pitch();
            case "time" -> voice.time();
            case "loop" -> voice.loop();
            case "position" -> position == null ? null : position.copy();
            case "stop" -> new NativeFunction(name, 0, 0, args -> {
                voice.stop();
                return null;
            });
            case "pause" -> new NativeFunction(name, 0, 0, args -> {
                voice.setPaused(true);
                return null;
            });
            case "resume" -> new NativeFunction(name, 0, 0, args -> {
                voice.setPaused(false);
                return null;
            });
            // fadeOut(seconds): fades away, then stops
            case "fadeOut" -> new NativeFunction(name, 1, 1, args -> {
                voice.fade(0, args.number(0), true);
                return null;
            });
            // fadeTo(volume, seconds): glides to a volume and keeps playing
            case "fadeTo" -> new NativeFunction(name, 2, 2, args -> {
                voice.fade(args.number(0), args.number(1), false);
                return null;
            });
            default -> throw noSuchProperty(name, line);
        };
    }

    @Override
    public void setProperty(String name, Object value, int line) {
        switch (name) {
            case "volume" -> voice.setVolume(number(name, value, line));
            case "pitch" -> voice.setPitch(number(name, value, line));
            case "loop" -> voice.setLoop(Values.isTruthy(value));
            case "position" -> {
                if (position == null) throw new SevenError("only 3D sounds (from playSoundAt) have a position", line);
                if (!(value instanceof Vec3 v)) throw new SevenError("sound.position must be a vec3", line);
                position = v.copy();
                voice.setPosition(v.x, v.y, v.z);
            }
            default -> throw cantChange("sound." + name, name, line);
        }
    }

    private static double number(String name, Object value, int line) {
        if (value instanceof Double d) return d;
        throw new SevenError("sound." + name + " must be a number, not " + Values.typeName(value), line);
    }

    @Override
    public String toString() {
        return "<sound>";
    }
}
