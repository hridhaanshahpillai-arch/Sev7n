package sev7n.game;

import sev7n.audio.AudioClip;
import sev7n.audio.AudioMixer;
import sev7n.audio.MixerSettings;
import sev7n.math.Vec3;
import sev7n.scene.AudioSource;
import sev7n.scene.Entity;
import sev7n.scene.Scene;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Sound for a running game, through Sev7n's audio engine (sev7n.audio.AudioMixer): effects,
 * music with crossfades, 3D sounds heard from the main camera, Audio Source components, and
 * the Audio Mixer's volumes (the project's audio.json). Files (.wav or .ogg) are read once and
 * kept. The engine only starts on the first sound, so silent games never open the sound card.
 */
final class Sound {

    private final Path projectFolder;
    private final AudioMixer mixer = new AudioMixer();
    private final Map<String, AudioClip> loaded = new HashMap<>();
    private final Map<Entity, AudioMixer.Voice> sources = new IdentityHashMap<>();
    private boolean started;
    private AudioMixer.Voice music;
    private String musicPath = "";

    Sound(Path projectFolder) {
        this.projectFolder = projectFolder;
        MixerSettings.load(projectFolder.resolve(MixerSettings.FILE_NAME)).applyTo(mixer);
    }

    AudioMixer mixer() {
        return mixer;
    }

    private AudioMixer started() {
        if (!started) {
            mixer.start();
            started = true;
        }
        return mixer;
    }

    /** A sound file read into memory (once). Throws IllegalArgumentException with a readable message. */
    AudioClip clip(String path) {
        AudioClip clip = loaded.get(path);
        if (clip == null) {
            if (path == null || path.isBlank()) throw new IllegalArgumentException("no sound file given");
            clip = AudioClip.read(projectFolder.resolve(path));
            loaded.put(path, clip);
        }
        return clip;
    }

    // ---------------------------------------------------------------- effects

    /** Plays a sound once. 'volume' 1 = as recorded, 0.5 = quieter, 0 = silent. */
    AudioMixer.Voice play(String path, double volume) {
        return play(path, volume, 1, "Effects");
    }

    AudioMixer.Voice play(String path, double volume, double pitch, String bus) {
        AudioClip clip = clip(path);
        return started().play(clip, bus, volume, pitch, false);
    }

    /** Plays a sound at a place in the world, heard from the main camera; silent past 'range'. */
    AudioMixer.Voice playAt(String path, Vec3 at, double volume, double range, double pitch) {
        AudioClip clip = clip(path);
        return started().playAt(clip, "Effects", at, range, volume, pitch, false);
    }

    // ---------------------------------------------------------------- music

    /**
     * Plays music on repeat through the Music bus. Different music crossfades over 'fade'
     * seconds (0 = cut straight over); the same music again just changes its volume.
     */
    void playMusic(String path, double volume, double fade) {
        if (music != null && music.playing() && musicPath.equals(path)) {
            if (fade > 0) music.fade(volume, fade, false);
            else music.setVolume(volume);
            return;
        }
        AudioClip clip = clip(path);
        stopMusic(fade);
        music = started().play(clip, "Music", fade > 0 ? 0 : volume, 1, true);
        if (fade > 0) music.fade(volume, fade, false);
        musicPath = path;
    }

    void stopMusic(double fade) {
        if (music != null) {
            if (fade > 0) music.fade(0, fade, true);
            else music.stop();
        }
        music = null;
        musicPath = "";
    }

    // ---------------------------------------------------------------- volumes

    /** Every sound and the music are multiplied by this (a volume setting). */
    void setMasterVolume(double volume) {
        mixer.setMaster(volume);
    }

    double masterVolume() {
        return mixer.master();
    }

    // ---------------------------------------------------------------- Audio Sources

    /** Plays an object's Audio Source (from the start). Throws IllegalArgumentException if its sound can't play. */
    void playSource(Entity entity) {
        AudioSource source = entity.get(AudioSource.class);
        stopSource(entity);
        AudioClip clip = clip(source.clip);
        Vec3 p = entity.transform.position;
        AudioMixer.Voice voice = source.spatial
                ? started().playAt(clip, source.bus, p, source.range, source.volume, source.pitch, source.loop)
                : started().play(clip, source.bus, source.volume, source.pitch, source.loop);
        sources.put(entity, voice);
        source.voice = voice;
    }

    void stopSource(Entity entity) {
        AudioMixer.Voice voice = sources.remove(entity);
        if (voice != null) voice.stop();
        AudioSource source = entity.get(AudioSource.class);
        if (source != null) source.voice = null;
    }

    boolean sourcePlaying(Entity entity) {
        AudioMixer.Voice voice = sources.get(entity);
        return voice != null && voice.playing();
    }

    /**
     * Every frame: the ears follow the main camera, Audio Sources follow their objects (and pick up
     * changes to their settings), and sources of objects that are gone stop. Hidden objects are paused.
     */
    void update(Scene scene) {
        Entity camera = scene.mainCamera(); // set even before the first sound, so it's heard from the right place
        if (camera != null) mixer.setListener(camera.transform.position, camera.transform.right(), camera.transform.forward());
        if (!started) return;
        sources.entrySet().removeIf(entry -> {
            Entity entity = entry.getKey();
            AudioMixer.Voice voice = entry.getValue();
            AudioSource source = entity.get(AudioSource.class);
            if (!scene.contains(entity) || source == null || !voice.playing()) {
                voice.stop();
                if (source != null) source.voice = null;
                return true;
            }
            Vec3 p = entity.transform.position;
            voice.setPosition(p.x, p.y, p.z);
            voice.setVolume(source.volume);
            voice.setPitch(source.pitch);
            voice.setLoop(source.loop);
            voice.setRange(source.range);
            voice.setPaused(!entity.active);
            return false;
        });
    }

    /**
     * Another scene is taking over: its sounds stop, but the music keeps playing into the new
     * scene (and the volumes stay), so music doesn't restart at every door.
     */
    Sound handOver() {
        sources.values().forEach(AudioMixer.Voice::stop);
        sources.clear();
        AudioMixer.Voice keep = music;
        if (started) {
            for (AudioMixer.Voice v : mixer.voices()) if (v != keep) v.stop();
        }
        return this;
    }

    /** Stops everything (the game ended) and lets go of the sound card. */
    void stopAll() {
        sources.clear();
        music = null;
        musicPath = "";
        mixer.close();
        started = false;
    }

    /**
     * How loud and where (left/right) a sound at 'source' is for a listener at 'ear' facing with
     * 'right' to its right: full volume up close, fading to nothing at 'range'.
     */
    static double[] placed(Vec3 source, Vec3 ear, Vec3 right, double range) {
        Vec3 toSound = source.minus(ear);
        double distance = toSound.length();
        double near = Math.min(2, range * 0.1); // inside this it's full volume
        double fade = distance <= near ? 1 : Math.max(0, 1 - (distance - near) / Math.max(0.001, range - near));
        double volume = fade * fade; // falls off quickly, then trails away, like real sound
        double pan = distance < 0.001 ? 0 : Math.max(-1, Math.min(1, toSound.times(1 / distance).dot(right))) * Math.min(1, distance / near) * 0.8;
        return new double[] {volume, pan};
    }
}
