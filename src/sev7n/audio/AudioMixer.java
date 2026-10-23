package sev7n.audio;

import sev7n.math.Vec3;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sev7n's audio engine: mixes every playing sound into one stereo stream and sends it to the
 * speakers on its own thread.
 *
 *   voices   each playing sound: volume, pitch, pan, looping, fading; 3D ones are placed in the
 *            world and heard from the listener (louder near, panned left/right, muffled far away
 *            and behind), updated as things move
 *   buses    groups with their own volume and mute (Music, Effects, Ambient, Voice, UI), all
 *            through the master volume, with level meters for the Audio Mixer
 *
 * Without a sound card (or in tests) it still runs, silently, so sounds start and finish on time.
 */
public final class AudioMixer implements AutoCloseable {

    /** The groups sounds play through, besides the master. */
    public static final List<String> BUSES = List.of("Music", "Effects", "Ambient", "Voice", "UI");
    /** More voices than this at once and the quietest one stops. */
    public static final int MAX_VOICES = 64;
    private static final int BLOCK = 512; // frames mixed at a time (~12 ms)

    /** A group of sounds with one volume. */
    public static final class Bus {
        public final String name;
        volatile double volume = 1;
        volatile boolean muted;
        volatile float level; // loudest sample in the last block (for meters)

        Bus(String name) {
            this.name = name;
        }

        public double volume() {
            return volume;
        }

        public boolean muted() {
            return muted;
        }

        /** How loud it is right now, 0..1. */
        public float level() {
            return level;
        }

        float gain(double master) {
            return muted ? 0 : (float) (volume * master);
        }
    }

    /** One playing sound. Changes from the game apply smoothly in the next block. */
    public final class Voice {
        final AudioClip clip;
        final Bus bus;
        volatile double volume;
        volatile double pitch;
        volatile double pan;          // -1 left .. 1 right (flat sounds)
        volatile boolean loop;
        volatile boolean paused;
        // 3D
        final boolean spatial;
        volatile double x, y, z;
        volatile double range;
        // playing state (mixer thread)
        double position;              // in frames, with a fraction
        float gainLeft = -1, gainRight;
        float muffle;                 // low-pass state, 0..1 of how much is cut
        float lowLeft, lowRight;
        double fadeFrom = -1, fadeTo, fadeTime, fadeLength; // a fade in progress (fadeFrom < 0: none)
        boolean stopAfterFade;
        volatile boolean done;
        final long started = System.nanoTime();

        Voice(AudioClip clip, Bus bus, double volume, double pitch, boolean loop, boolean spatial) {
            this.clip = clip;
            this.bus = bus;
            this.volume = volume;
            this.pitch = pitch;
            this.loop = loop;
            this.spatial = spatial;
        }

        public void stop() {
            done = true;
        }

        /** Fades to 'target' volume over 'seconds' (and stops at the end if 'thenStop'). */
        public void fade(double target, double seconds, boolean thenStop) {
            synchronized (AudioMixer.this) {
                fadeFrom = volume;
                fadeTo = Math.max(0, target);
                fadeTime = 0;
                fadeLength = Math.max(0.001, seconds);
                stopAfterFade = thenStop;
            }
        }

        public boolean playing() {
            return !done;
        }

        public double volume() {
            return volume;
        }

        public void setVolume(double v) {
            volume = Math.max(0, v);
        }

        public double pitch() {
            return pitch;
        }

        public void setPitch(double p) {
            pitch = Math.max(0.05, Math.min(8, p));
        }

        public void setPan(double p) {
            pan = Math.max(-1, Math.min(1, p));
        }

        public boolean loop() {
            return loop;
        }

        public void setLoop(boolean l) {
            loop = l;
        }

        public void setPaused(boolean p) {
            paused = p;
        }

        public void setPosition(double px, double py, double pz) {
            x = px;
            y = py;
            z = pz;
        }

        public void setRange(double r) {
            range = Math.max(0.1, r);
        }

        /** How far through the sound it is, in seconds. */
        public double time() {
            return position / AudioClip.RATE;
        }

        public String bus() {
            return bus.name;
        }
    }

    private final Map<String, Bus> buses = new LinkedHashMap<>();
    private final List<Voice> voices = new ArrayList<>();
    private volatile double master = 1;
    private volatile float masterLevel;
    private float[][] busMix = new float[0][0]; // (mixer thread) each bus's mono mix this block
    // the listener (the main camera): where it is and which way is right and forward
    private volatile double lx, ly, lz, rx = 1, ry, rz, fx, fy, fz = -1;

    private Thread thread;
    private volatile boolean running;
    private SourceDataLine line;
    private volatile boolean silent = true;

    public AudioMixer() {
        for (String name : BUSES) buses.put(name, new Bus(name));
    }

    // ---------------------------------------------------------------- output

    /** Starts sending sound to the speakers (silently, at the same pace, if there are none). */
    public synchronized AudioMixer start() {
        if (running) return this;
        running = true;
        try {
            // -Dsev7n.audio.silent=true: run without the sound card (automated tests shouldn't make noise)
            if (Boolean.getBoolean("sev7n.audio.silent")) throw new IllegalStateException("silent");
            AudioFormat format = new AudioFormat(AudioClip.RATE, 16, 2, true, false);
            line = AudioSystem.getSourceDataLine(format);
            line.open(format, BLOCK * 4 * 6); // ~70 ms of sound queued: low delay, no stutter
            line.start();
            silent = false;
        } catch (Exception | LinkageError e) {
            line = null;
            silent = true; // no sound card: keep time anyway
        }
        thread = new Thread(this::run, "Sev7n audio");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
        return this;
    }

    /** True when there's no sound card to play through (sounds still start and finish). */
    public boolean silent() {
        return silent;
    }

    private void run() {
        float[] mixed = new float[BLOCK * 2];
        byte[] bytes = new byte[BLOCK * 4];
        long next = System.nanoTime();
        while (running) {
            mix(mixed, BLOCK);
            if (line != null) {
                for (int i = 0; i < mixed.length; i++) {
                    int s = (int) (mixed[i] * 32767);
                    bytes[i * 2] = (byte) s;
                    bytes[i * 2 + 1] = (byte) (s >> 8);
                }
                line.write(bytes, 0, bytes.length); // waits while the line is full: that's what paces us
            } else {
                next += BLOCK * 1_000_000_000L / AudioClip.RATE;
                long wait = next - System.nanoTime();
                if (wait > 0) {
                    try {
                        Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }
    }

    @Override
    public void close() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (line != null) {
            line.stop();
            line.close();
            line = null;
        }
        synchronized (this) {
            voices.clear();
        }
    }

    // ---------------------------------------------------------------- playing

    /** Plays a clip through a bus ("Effects" if unknown). Flat (not 3D). */
    public Voice play(AudioClip clip, String bus, double volume, double pitch, boolean loop) {
        return add(new Voice(clip, bus(bus), Math.max(0, volume), Math.max(0.05, pitch), loop, false));
    }

    /** Plays a clip at a place in the world, heard from the listener; silent past 'range'. */
    public Voice playAt(AudioClip clip, String bus, Vec3 at, double range, double volume, double pitch, boolean loop) {
        Voice voice = new Voice(clip, bus(bus), Math.max(0, volume), Math.max(0.05, pitch), loop, true);
        voice.setPosition(at.x, at.y, at.z);
        voice.setRange(range);
        return add(voice);
    }

    private synchronized Voice add(Voice voice) {
        voices.removeIf(v -> v.done);
        if (voices.size() >= MAX_VOICES) {
            // make room: the quietest sound that isn't music goes
            Voice quietest = null;
            for (Voice v : voices) {
                if (v.bus.name.equals("Music")) continue;
                if (quietest == null || loudness(v) < loudness(quietest)) quietest = v;
            }
            if (quietest == null) quietest = voices.get(0);
            quietest.done = true;
            voices.remove(quietest);
        }
        voices.add(voice);
        return voice;
    }

    private float loudness(Voice v) {
        return v.gainLeft < 0 ? (float) v.volume : Math.max(v.gainLeft, v.gainRight);
    }

    /** Stops every sound (the game stopped). */
    public synchronized void stopAll() {
        for (Voice v : voices) v.done = true;
        voices.clear();
    }

    /** The sounds playing now. */
    public synchronized List<Voice> voices() {
        return List.copyOf(voices);
    }

    public synchronized int playingCount() {
        int count = 0;
        for (Voice v : voices) if (!v.done) count++;
        return count;
    }

    // ---------------------------------------------------------------- buses and listener

    public Bus bus(String name) {
        Bus bus = buses.get(name);
        return bus != null ? bus : buses.get("Effects");
    }

    public boolean hasBus(String name) {
        return buses.containsKey(name);
    }

    public List<Bus> buses() {
        return List.copyOf(buses.values());
    }

    public void setBusVolume(String name, double volume) {
        bus(name).volume = Math.max(0, Math.min(1, volume));
    }

    public void setBusMuted(String name, boolean muted) {
        bus(name).muted = muted;
    }

    public double master() {
        return master;
    }

    public void setMaster(double volume) {
        master = Math.max(0, Math.min(1, volume));
    }

    public float masterLevel() {
        return masterLevel;
    }

    /** Where the ears are (the main camera) and which way its right and forward point. */
    public void setListener(Vec3 position, Vec3 right, Vec3 forward) {
        lx = position.x;
        ly = position.y;
        lz = position.z;
        rx = right.x;
        ry = right.y;
        rz = right.z;
        fx = forward.x;
        fy = forward.y;
        fz = forward.z;
    }

    // ---------------------------------------------------------------- mixing

    /** Mixes the next 'frames' frames of every voice into 'out' (stereo). Called by the audio thread (and tests). */
    public synchronized void mix(float[] out, int frames) {
        java.util.Arrays.fill(out, 0, frames * 2, 0);
        double masterNow = master;
        // each bus's own mix (mono, for its meter): how loud the whole group is, not just its loudest sound
        if (busMix.length == 0 || busMix[0].length < frames) busMix = new float[BUSES.size()][frames];
        for (float[] b : busMix) java.util.Arrays.fill(b, 0, frames, 0);
        float peak = 0;
        for (int vi = voices.size() - 1; vi >= 0; vi--) {
            Voice v = voices.get(vi);
            if (v.done) {
                voices.remove(vi);
                continue;
            }
            if (v.paused) continue;
            // where the voice wants to be this block: its volume through its bus, placed in 3D if it's spatial
            double volume = v.volume;
            if (v.fadeFrom >= 0) {
                v.fadeTime += (double) frames / AudioClip.RATE;
                double t = Math.min(1, v.fadeTime / v.fadeLength);
                volume = v.fadeFrom + (v.fadeTo - v.fadeFrom) * t;
                v.volume = volume;
                if (t >= 1) {
                    v.fadeFrom = -1;
                    if (v.stopAfterFade) v.done = true;
                }
            }
            double pan = v.pan;
            float muffleGoal = 0;
            if (v.spatial) {
                double[] placed = place(v);
                volume *= placed[0];
                pan = placed[1];
                muffleGoal = (float) placed[2];
            }
            double busGain = v.bus.gain(masterNow);
            // equal-power pan: as loud in the middle as at either side
            double angle = (pan + 1) * Math.PI / 4;
            float targetLeft = (float) (volume * busGain * Math.cos(angle) * Math.sqrt(2));
            float targetRight = (float) (volume * busGain * Math.sin(angle) * Math.sqrt(2));
            if (v.gainLeft < 0) {
                v.gainLeft = targetLeft; // a new voice starts at its volume (no fade in)
                v.gainRight = targetRight;
                v.muffle = muffleGoal;
            }
            float startLeft = v.gainLeft, startRight = v.gainRight;
            float startMuffle = v.muffle;

            float[] s = v.clip.samples;
            int clipFrames = v.clip.frames;
            double step = v.pitch;
            int busIndex = BUSES.indexOf(v.bus.name);
            float[] meter = busIndex >= 0 ? busMix[busIndex] : null;
            for (int f = 0; f < frames; f++) {
                int i = (int) v.position;
                if (i >= clipFrames) {
                    if (v.loop && clipFrames > 0) {
                        v.position -= clipFrames;
                        i = (int) v.position;
                    } else {
                        v.done = true;
                        break;
                    }
                }
                double t = v.position - i;
                int j = i + 1 < clipFrames ? i + 1 : v.loop ? 0 : i;
                float left = (float) (s[i * 2] + (s[j * 2] - s[i * 2]) * t);
                float right = (float) (s[i * 2 + 1] + (s[j * 2 + 1] - s[i * 2 + 1]) * t);
                // gains and muffling glide across the block, so changes never click
                float k = (float) f / frames;
                float muffle = startMuffle + (muffleGoal - startMuffle) * k;
                if (muffle > 0.001f) {
                    float keep = 1 - muffle * 0.92f; // a simple low-pass: far and behind sounds lose their highs
                    v.lowLeft += (left - v.lowLeft) * keep;
                    v.lowRight += (right - v.lowRight) * keep;
                    left = v.lowLeft;
                    right = v.lowRight;
                } else {
                    v.lowLeft = left;
                    v.lowRight = right;
                }
                float gl = startLeft + (targetLeft - startLeft) * k;
                float gr = startRight + (targetRight - startRight) * k;
                float ol = left * gl, or = right * gr;
                out[f * 2] += ol;
                out[f * 2 + 1] += or;
                if (meter != null) meter[f] += (ol + or) * 0.5f;
                v.position += step;
            }
            v.gainLeft = targetLeft;
            v.gainRight = targetRight;
            v.muffle = muffleGoal;
            if (v.done) voices.remove(vi);
        }
        // a soft limiter: loud moments squash smoothly instead of crackling
        for (int i = 0; i < frames * 2; i++) {
            float x = out[i];
            float a = Math.abs(x);
            if (a > 0.8f) x = Math.copySign(0.8f + 0.2f * (float) Math.tanh((a - 0.8f) / 0.2f), x);
            out[i] = x;
            peak = Math.max(peak, Math.abs(x));
        }
        for (int b = 0; b < BUSES.size(); b++) {
            float busPeak = 0;
            for (int f = 0; f < frames; f++) busPeak = Math.max(busPeak, Math.abs(busMix[b][f]));
            Bus bus = buses.get(BUSES.get(b));
            bus.level = Math.max(Math.min(1, busPeak), bus.level * 0.85f); // meters fall back gently
        }
        masterLevel = Math.max(peak, masterLevel * 0.85f);
    }

    /**
     * A 3D voice heard from the listener: {volume 0..1, pan -1..1, muffle 0..1}. Full volume up
     * close, fading to nothing at its range; panned by which side it's on; a little quieter and
     * more muffled behind and far away.
     */
    double[] place(Voice v) {
        double dx = v.x - lx, dy = v.y - ly, dz = v.z - lz;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double range = Math.max(0.1, v.range);
        double near = Math.min(2, range * 0.1);
        double fade = distance <= near ? 1 : Math.max(0, 1 - (distance - near) / Math.max(0.001, range - near));
        double volume = fade * fade;
        if (distance < 0.001) return new double[] {volume, 0, 0};
        double side = (dx * rx + dy * ry + dz * rz) / distance;
        double front = (dx * fx + dy * fy + dz * fz) / distance;
        double pan = Math.max(-1, Math.min(1, side)) * Math.min(1, distance / near) * 0.85;
        double behind = Math.max(0, -front);
        volume *= 1 - behind * 0.25;
        double muffle = Math.min(1, behind * 0.5 + Math.max(0, distance - near) / range * 0.6);
        return new double[] {volume, pan, muffle};
    }
}
