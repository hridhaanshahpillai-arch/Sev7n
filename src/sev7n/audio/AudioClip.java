package sev7n.audio;

import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCStdlib;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * A sound in memory, ready to mix: stereo float samples (-1..1) at the mixer's rate, whatever
 * the file was (mono or stereo, any sample rate). Reads .wav, .aiff and .au (Java's own audio)
 * and .ogg (stb_vorbis).
 */
public final class AudioClip {

    /** Samples per second everything is mixed at. */
    public static final int RATE = 44100;

    /** Left, right, left, right... */
    final float[] samples;
    final int frames;

    AudioClip(float[] samples) {
        this.samples = samples;
        this.frames = samples.length / 2;
    }

    /** A clip from stereo samples (left, right, ...) at RATE, e.g. from the Sound Maker. */
    public static AudioClip ofStereo(float[] samples) {
        return new AudioClip(samples.clone());
    }

    /** A clip from mono samples at RATE. */
    public static AudioClip ofMono(float[] samples) {
        float[] stereo = new float[samples.length * 2];
        for (int i = 0; i < samples.length; i++) stereo[i * 2] = stereo[i * 2 + 1] = samples[i];
        return new AudioClip(stereo);
    }

    public double seconds() {
        return (double) frames / RATE;
    }

    /** Reads a sound file. Throws IllegalArgumentException with a message a beginner can act on. */
    public static AudioClip read(Path file) {
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("there's no sound file '" + file.getFileName() + "'");
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (name.endsWith(".ogg")) return readOgg(bytes, file.getFileName().toString());
            if (name.endsWith(".mp3") || name.endsWith(".flac")) {
                throw new IllegalArgumentException("can't play '" + file.getFileName() + "': use .wav or .ogg (convert it with a free tool like Audacity)");
            }
            return readJavaSound(bytes, file.getFileName().toString());
        } catch (IOException e) {
            throw new IllegalArgumentException("can't read the sound '" + file.getFileName() + "': " + e.getMessage());
        }
    }

    private static AudioClip readJavaSound(byte[] bytes, String name) {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(bytes))) {
            AudioFormat source = in.getFormat();
            // everything to 16-bit signed little-endian, keeping channels and rate
            AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, source.getSampleRate(), 16,
                    source.getChannels(), source.getChannels() * 2, source.getSampleRate(), false);
            try (AudioInputStream converted = AudioSystem.getAudioInputStream(pcm, in)) {
                byte[] data = converted.readAllBytes();
                short[] shorts = new short[data.length / 2];
                for (int i = 0; i < shorts.length; i++) shorts[i] = (short) ((data[i * 2] & 0xFF) | (data[i * 2 + 1] << 8));
                return fromShorts(shorts, pcm.getChannels(), Math.round(pcm.getSampleRate()));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("can't read the sound '" + name + "' (use .wav or .ogg): " + e.getMessage());
        }
    }

    private static AudioClip readOgg(byte[] bytes, String name) {
        ByteBuffer data = MemoryUtil.memAlloc(bytes.length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            data.put(bytes).flip();
            IntBuffer channels = stack.mallocInt(1);
            IntBuffer rate = stack.mallocInt(1);
            ShortBuffer decoded = STBVorbis.stb_vorbis_decode_memory(data, channels, rate);
            if (decoded == null) throw new IllegalArgumentException("can't read the sound '" + name + "': it isn't a working .ogg (Vorbis) file");
            try {
                short[] shorts = new short[decoded.remaining()];
                decoded.get(shorts);
                return fromShorts(shorts, channels.get(0), rate.get(0));
            } finally {
                LibCStdlib.free(decoded);
            }
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    /** Interleaved 16-bit samples with any number of channels and any rate, to stereo floats at RATE. */
    static AudioClip fromShorts(short[] shorts, int channels, int rate) {
        channels = Math.max(1, channels);
        int frames = shorts.length / channels;
        float[] stereo = new float[frames * 2];
        for (int f = 0; f < frames; f++) {
            float left = shorts[f * channels] / 32768f;
            float right = channels > 1 ? shorts[f * channels + 1] / 32768f : left;
            stereo[f * 2] = left;
            stereo[f * 2 + 1] = right;
        }
        return new AudioClip(resample(stereo, rate));
    }

    /** Stereo samples at 'rate' to RATE (straight lines between samples: clean enough for game sounds). */
    static float[] resample(float[] stereo, int rate) {
        if (rate == RATE || rate <= 0) return stereo;
        int frames = stereo.length / 2;
        int outFrames = (int) ((long) frames * RATE / rate);
        float[] out = new float[outFrames * 2];
        double step = (double) rate / RATE;
        for (int f = 0; f < outFrames; f++) {
            double at = f * step;
            int i = (int) at;
            double t = at - i;
            int j = Math.min(frames - 1, i + 1);
            out[f * 2] = (float) (stereo[i * 2] * (1 - t) + stereo[j * 2] * t);
            out[f * 2 + 1] = (float) (stereo[i * 2 + 1] * (1 - t) + stereo[j * 2 + 1] * t);
        }
        return out;
    }

    /** Saves as a 16-bit stereo .wav (the Sound Maker's output). */
    public void writeWav(Path file) throws IOException {
        byte[] pcm = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            int s = (int) Math.round(Math.max(-1, Math.min(1, samples[i])) * 32767);
            pcm[i * 2] = (byte) s;
            pcm[i * 2 + 1] = (byte) (s >> 8);
        }
        AudioFormat format = new AudioFormat(RATE, 16, 2, true, false);
        try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(pcm), format, frames)) {
            Files.createDirectories(file.toAbsolutePath().getParent());
            AudioSystem.write(in, javax.sound.sampled.AudioFileFormat.Type.WAVE, file.toFile());
        }
    }
}
