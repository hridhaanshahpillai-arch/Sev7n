package sev7n.audio;

import java.util.Random;

/**
 * Makes retro sound effects from a few settings, no recording needed: coins, jumps, lasers,
 * explosions, hits, power-ups, blips and clicks (in the spirit of the classic sfxr). The editor's
 * Sound Maker plays and saves these as .wav files.
 */
public final class SoundMaker {

    /** The basic tone. */
    public enum Wave {
        SQUARE("Square", "Buzzy and retro: classic game sounds"),
        TRIANGLE("Triangle", "Soft and round"),
        SAW("Saw", "Bright and harsh"),
        SINE("Sine", "Pure and smooth"),
        NOISE("Noise", "Hiss and crunch: explosions, hits, wind");

        public final String label;
        public final String description;

        Wave(String label, String description) {
            this.label = label;
            this.description = description;
        }
    }

    /** The starting points the editor offers. */
    public static final String[] PRESETS = {"Coin", "Jump", "Laser", "Explosion", "Hit", "Power-up", "Blip", "Click"};

    public Wave wave = Wave.SQUARE;
    /** Starting pitch in Hz (440 = the A above middle C). */
    public double frequency = 440;
    /** Pitch at the end, as a multiple of the start: 2 = an octave up, 0.5 = an octave down. */
    public double slide = 1;
    /** A sudden pitch change partway through (coin "ding-ding"): the multiple, and when (0..1 of the length). */
    public double jump = 1;
    public double jumpAt = 0.4;
    /** Seconds to fade in, hold, and fade out. */
    public double attack = 0;
    public double sustain = 0.1;
    public double decay = 0.2;
    /** Extra loudness right at the start, then settling (0..1). */
    public double punch = 0;
    /** Wobble: how much (0..1) and how fast (per second). */
    public double vibrato = 0;
    public double vibratoSpeed = 6;
    /** Square waves only: 0.5 = even, smaller = thinner, nasal. */
    public double duty = 0.5;
    /** How much of the high end to keep (1 = all, smaller = darker, muffled). */
    public double brightness = 1;
    public double volume = 0.6;

    public double seconds() {
        return attack + sustain + decay;
    }

    /** The sound as mono samples at AudioClip.RATE. */
    public float[] render() {
        int length = Math.max(1, (int) (seconds() * AudioClip.RATE));
        float[] out = new float[length];
        Random noise = new Random(1234);
        double phase = 0;
        double noiseValue = 0;
        double low = 0;
        double keep = 0.02 + 0.98 * Math.max(0, Math.min(1, brightness)) * Math.max(0, Math.min(1, brightness));
        for (int i = 0; i < length; i++) {
            double t = (double) i / AudioClip.RATE;
            double progress = (double) i / length;
            // pitch: slides from start to end (smoothly, in octaves), jumps once, wobbles
            double pitch = frequency * Math.pow(Math.max(0.01, slide), progress);
            if (jump != 1 && progress >= jumpAt) pitch *= jump;
            if (vibrato > 0) pitch *= 1 + Math.sin(t * vibratoSpeed * Math.PI * 2) * vibrato * 0.5;
            pitch = Math.max(10, Math.min(20000, pitch));
            double previous = phase;
            phase = (phase + pitch / AudioClip.RATE) % 1;
            double sample = switch (wave) {
                case SQUARE -> phase < duty ? 1 : -1;
                case TRIANGLE -> phase < 0.5 ? phase * 4 - 1 : 3 - phase * 4;
                case SAW -> phase * 2 - 1;
                case SINE -> Math.sin(phase * Math.PI * 2);
                case NOISE -> {
                    if (phase < previous) noiseValue = noise.nextDouble() * 2 - 1; // a new random value each cycle: pitch shapes the noise
                    yield noiseValue;
                }
            };
            low += (sample - low) * keep;
            out[i] = (float) Math.max(-1, Math.min(1, low * envelope(t) * volume)); // punch can't push it past full scale
        }
        return out;
    }

    /** How loud at time t: fade in, hold (with punch), fade out. */
    private double envelope(double t) {
        if (t < attack) return t / attack;
        t -= attack;
        if (t < sustain) return 1 + punch * (1 - t / Math.max(1e-6, sustain));
        t -= sustain;
        return decay <= 0 ? 0 : Math.max(0, 1 - t / decay);
    }

    /** One of the starting points (see PRESETS), with a little randomness so each one is a bit different. */
    public static SoundMaker preset(String name, long seed) {
        Random r = new Random(seed);
        SoundMaker s = new SoundMaker();
        switch (name) {
            case "Coin" -> {
                s.wave = Wave.SQUARE;
                s.frequency = 900 + r.nextDouble() * 500;
                s.jump = 1.3 + r.nextDouble() * 0.4;
                s.jumpAt = 0.25 + r.nextDouble() * 0.2;
                s.sustain = 0.05 + r.nextDouble() * 0.05;
                s.decay = 0.2 + r.nextDouble() * 0.15;
                s.punch = 0.3 + r.nextDouble() * 0.4;
                s.duty = 0.3 + r.nextDouble() * 0.2;
            }
            case "Jump" -> {
                s.wave = Wave.SQUARE;
                s.frequency = 250 + r.nextDouble() * 200;
                s.slide = 1.8 + r.nextDouble() * 1.2;
                s.sustain = 0.08 + r.nextDouble() * 0.08;
                s.decay = 0.12 + r.nextDouble() * 0.1;
                s.duty = 0.2 + r.nextDouble() * 0.3;
            }
            case "Laser" -> {
                s.wave = r.nextBoolean() ? Wave.SAW : Wave.SQUARE;
                s.frequency = 1200 + r.nextDouble() * 1200;
                s.slide = 0.12 + r.nextDouble() * 0.2;
                s.sustain = 0.05 + r.nextDouble() * 0.08;
                s.decay = 0.1 + r.nextDouble() * 0.12;
                s.duty = 0.15 + r.nextDouble() * 0.3;
            }
            case "Explosion" -> {
                s.wave = Wave.NOISE;
                s.frequency = 600 + r.nextDouble() * 800;
                s.slide = 0.15 + r.nextDouble() * 0.2;
                s.sustain = 0.15 + r.nextDouble() * 0.2;
                s.decay = 0.5 + r.nextDouble() * 0.5;
                s.punch = 0.4 + r.nextDouble() * 0.4;
                s.brightness = 0.5 + r.nextDouble() * 0.3;
                s.volume = 0.7;
            }
            case "Hit" -> {
                s.wave = r.nextBoolean() ? Wave.NOISE : Wave.SQUARE;
                s.frequency = s.wave == Wave.NOISE ? 1500 + r.nextDouble() * 1500 : 200 + r.nextDouble() * 200;
                s.slide = 0.3 + r.nextDouble() * 0.3;
                s.sustain = 0.02 + r.nextDouble() * 0.03;
                s.decay = 0.08 + r.nextDouble() * 0.1;
                s.punch = 0.5;
            }
            case "Power-up" -> {
                s.wave = r.nextBoolean() ? Wave.SQUARE : Wave.TRIANGLE;
                s.frequency = 300 + r.nextDouble() * 200;
                s.slide = 2.5 + r.nextDouble() * 1.5;
                s.sustain = 0.2 + r.nextDouble() * 0.15;
                s.decay = 0.2 + r.nextDouble() * 0.2;
                s.vibrato = 0.1 + r.nextDouble() * 0.15;
                s.vibratoSpeed = 10 + r.nextDouble() * 10;
            }
            case "Blip" -> {
                s.wave = r.nextBoolean() ? Wave.SQUARE : Wave.SINE;
                s.frequency = 500 + r.nextDouble() * 700;
                s.sustain = 0.03 + r.nextDouble() * 0.04;
                s.decay = 0.04 + r.nextDouble() * 0.05;
                s.duty = 0.5;
            }
            case "Click" -> {
                s.wave = Wave.NOISE;
                s.frequency = 4000 + r.nextDouble() * 4000;
                s.sustain = 0.005;
                s.decay = 0.02 + r.nextDouble() * 0.02;
                s.brightness = 0.7;
                s.volume = 0.5;
            }
            default -> throw new IllegalArgumentException("unknown sound preset '" + name + "'");
        }
        return s;
    }

    public SoundMaker copy() {
        SoundMaker c = new SoundMaker();
        c.wave = wave;
        c.frequency = frequency;
        c.slide = slide;
        c.jump = jump;
        c.jumpAt = jumpAt;
        c.attack = attack;
        c.sustain = sustain;
        c.decay = decay;
        c.punch = punch;
        c.vibrato = vibrato;
        c.vibratoSpeed = vibratoSpeed;
        c.duty = duty;
        c.brightness = brightness;
        c.volume = volume;
        return c;
    }
}
