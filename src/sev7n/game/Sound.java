package sev7n.game;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sound effects and music for a running game, with Java's own audio (no extra libraries):
 * .wav files (also .aiff and .au). Files are read once and kept, so playing the same sound
 * again is quick. Sounds that are still playing stop when the game stops.
 */
final class Sound {

    /** A sound file read into memory. */
    private record Loaded(AudioFormat format, byte[] data) { }

    private static final int MAX_PLAYING = 24; // more at once than this and the oldest is cut off

    private final Path projectFolder;
    private final Map<String, Loaded> loaded = new HashMap<>();
    private final List<Clip> playing = new ArrayList<>();
    private Clip music;
    private String musicPath = "";
    private double musicVolume = 1;
    private double masterVolume = 1;

    /** Every sound and the music are multiplied by this (a volume setting). */
    void setMasterVolume(double volume) {
        masterVolume = Math.max(0, Math.min(1, volume));
        if (music != null && music.isOpen()) setVolume(music, musicVolume * masterVolume);
    }

    double masterVolume() {
        return masterVolume;
    }

    Sound(Path projectFolder) {
        this.projectFolder = projectFolder;
    }

    /** Plays a sound once. 'volume' 1 = as recorded, 0.5 = quieter, 0 = silent. Throws with a readable message if the file can't be played. */
    void play(String path, double volume) {
        Clip clip = open(path, volume * masterVolume);
        clip.addLineListener(event -> {
            if (event.getType() == LineEvent.Type.STOP) clip.close();
        });
        synchronized (playing) {
            playing.removeIf(c -> !c.isOpen());
            if (playing.size() >= MAX_PLAYING) playing.remove(0).close();
            playing.add(clip);
        }
        clip.start();
    }

    /** Plays a sound over and over (background music), replacing any music already playing. The same music again keeps going. */
    void playMusic(String path, double volume) {
        musicVolume = volume;
        if (music != null && music.isOpen() && musicPath.equals(path)) {
            setVolume(music, volume * masterVolume);
            return;
        }
        stopMusic();
        music = open(path, volume * masterVolume);
        musicPath = path;
        music.loop(Clip.LOOP_CONTINUOUSLY);
    }

    void stopMusic() {
        if (music != null) music.close();
        music = null;
        musicPath = "";
    }

    /** Stops everything (the game ended). */
    void stopAll() {
        stopMusic();
        synchronized (playing) {
            playing.forEach(Clip::close);
            playing.clear();
        }
    }

    private Clip open(String path, double volume) {
        try {
            Loaded sound = loaded.computeIfAbsent(path, this::read);
            Clip clip = AudioSystem.getClip();
            clip.open(sound.format(), sound.data(), 0, sound.data().length);
            setVolume(clip, volume);
            return clip;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("can't play '" + path + "': " + e.getMessage());
        }
    }

    private Loaded read(String path) {
        Path file = projectFolder.resolve(path);
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("there's no sound file '" + path + "' in the project");
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(Files.readAllBytes(file)))) {
            AudioFormat format = in.getFormat();
            if (format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED && format.getEncoding() != AudioFormat.Encoding.PCM_UNSIGNED) {
                AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, format.getSampleRate(), 16,
                        format.getChannels(), format.getChannels() * 2, format.getSampleRate(), false);
                try (AudioInputStream converted = AudioSystem.getAudioInputStream(pcm, in)) {
                    return new Loaded(pcm, converted.readAllBytes());
                }
            }
            return new Loaded(format, in.readAllBytes());
        } catch (Exception e) {
            throw new IllegalArgumentException("can't read the sound '" + path + "' (use .wav files): " + e.getMessage());
        }
    }

    private static void setVolume(Clip clip, double volume) {
        if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) return;
        FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
        double decibels = volume <= 0.0001 ? gain.getMinimum() : 20 * Math.log10(volume);
        gain.setValue((float) Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), decibels)));
    }
}
