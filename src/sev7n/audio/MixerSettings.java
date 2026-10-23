package sev7n.audio;

import sev7n.scene.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Audio Mixer's volumes: master and each bus, and which buses are muted. Saved with the
 * project (audio.json), so exported games sound the same. Scripts can still change them while playing.
 */
public final class MixerSettings {

    public static final String FILE_NAME = "audio.json";

    public double master = 1;
    public final Map<String, Double> volumes = new LinkedHashMap<>();
    public final Map<String, Boolean> muted = new LinkedHashMap<>();

    public MixerSettings() {
        for (String bus : AudioMixer.BUSES) {
            volumes.put(bus, 1.0);
            muted.put(bus, false);
        }
    }

    /** Sets a mixer to these volumes. */
    public void applyTo(AudioMixer mixer) {
        mixer.setMaster(master);
        for (String bus : AudioMixer.BUSES) {
            mixer.setBusVolume(bus, volumes.getOrDefault(bus, 1.0));
            mixer.setBusMuted(bus, muted.getOrDefault(bus, false));
        }
    }

    public static MixerSettings load(Path file) {
        MixerSettings settings = new MixerSettings();
        if (!Files.exists(file)) return settings;
        try {
            Map<String, Object> in = Json.object(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
            settings.master = clamp(Json.number(in, "master", 1));
            for (String bus : AudioMixer.BUSES) {
                settings.volumes.put(bus, clamp(Json.number(in, bus.toLowerCase() + "Volume", 1)));
                settings.muted.put(bus, Json.bool(in, bus.toLowerCase() + "Muted", false));
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Couldn't read " + file + " (using full volume): " + e.getMessage());
        }
        return settings;
    }

    public void save(Path file) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("master", master);
        for (String bus : AudioMixer.BUSES) {
            out.put(bus.toLowerCase() + "Volume", volumes.getOrDefault(bus, 1.0));
            out.put(bus.toLowerCase() + "Muted", muted.getOrDefault(bus, false));
        }
        Files.writeString(file, Json.write(out), StandardCharsets.UTF_8);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
