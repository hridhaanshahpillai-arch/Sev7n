package sev7n.editor;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import sev7n.audio.AudioClip;
import sev7n.audio.AudioMixer;
import sev7n.audio.MixerSettings;
import sev7n.audio.SoundMaker;
import sev7n.ui.Theme;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Window > Audio: the game's sound in one place.
 *   Mixer        master and group volumes (Music, Effects, Ambient, Voice, UI), mute, live level meters
 *   Sound Maker  makes retro sound effects from presets and sliders, and saves them as .wav files
 */
final class AudioPanel {

    static final String TITLE = "Audio";

    private final EditorContext ctx;
    private final ImBoolean open = new ImBoolean(false);
    private final float[] slider = new float[1];
    private boolean focusRequested;
    private String tab = "Mixer";
    private String tabRequest;

    // the Sound Maker
    private SoundMaker maker = SoundMaker.preset("Coin", 7);
    private String preset = "Coin";
    private final Random random = new Random();
    private float[] rendered;
    private boolean autoPlay = true;
    private String saveName = "coin";
    private String savedMessage = "";

    AudioPanel(EditorContext ctx) {
        this.ctx = ctx;
    }

    /** Opens on a tab: "Mixer" or "Sound Maker". */
    void open(String which) {
        open.set(true);
        focusRequested = true;
        tabRequest = which;
    }

    void draw() {
        if (!open.get()) return;
        float scale = ImGui.getFontSize() / 15f;
        ImGui.setNextWindowSize(560 * scale, 520 * scale, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowPos(ImGui.getMainViewport().getCenterX(), ImGui.getMainViewport().getCenterY(),
                ImGuiCond.FirstUseEver, 0.5f, 0.5f);
        if (focusRequested) {
            ImGui.setNextWindowFocus();
            focusRequested = false;
        }
        if (!ImGui.begin(TITLE, open, ImGuiWindowFlags.NoCollapse)) {
            ImGui.end();
            return;
        }
        if (ImGui.beginTabBar("##audioTabs")) {
            for (String name : new String[] {"Mixer", "Sound Maker"}) {
                int flags = name.equals(tabRequest) ? imgui.flag.ImGuiTabItemFlags.SetSelected : 0;
                if (ImGui.beginTabItem(name, flags)) {
                    tab = name;
                    if (name.equals("Mixer")) drawMixer();
                    else drawSoundMaker();
                    ImGui.endTabItem();
                }
            }
            tabRequest = null;
            ImGui.endTabBar();
        }
        ImGui.end();
    }

    // ---------------------------------------------------------------- mixer

    private void drawMixer() {
        MixerSettings audio = ctx.audio;
        AudioMixer live = ctx.gameAudio();
        Widgets.hint("Every sound plays through a group. Saved with your project, so exported games sound the same. "
                + (live != null ? "The meters show your game's sound." : "Press Play to see the meters move."));
        ImGui.spacing();
        boolean changed = false;
        double master = busRow("Master", audio.master, null, live == null ? 0 : live.masterLevel());
        if (master != audio.master) {
            audio.master = master;
            changed = true;
        }
        ImGui.separator();
        for (String bus : AudioMixer.BUSES) {
            ImGui.pushID(bus);
            double volume = audio.volumes.getOrDefault(bus, 1.0);
            boolean muted = audio.muted.getOrDefault(bus, false);
            double newVolume = busRow(bus, volume, muted, live == null ? 0 : live.bus(bus).level());
            if (newVolume != volume) {
                audio.volumes.put(bus, newVolume);
                changed = true;
            }
            if (lastMuteClicked) {
                audio.muted.put(bus, !muted);
                changed = true;
            }
            ImGui.popID();
        }
        ImGui.spacing();
        Widgets.hint("Music: playMusic(). Effects: playSound(), playSoundAt() and Audio Sources (pick another group in an Audio Source). "
                + "Scripts can change these too: setBusVolume(\"Music\", 0.5), muteBus(\"Music\", true).");
        if (live != null) ImGui.textColored(EditorStyle.pack(Theme.TEXT_MUTED), live.playingCount() + " sounds playing");
        if (changed) ctx.saveAudio();
    }

    private boolean lastMuteClicked;

    /** A group: its name, a volume slider, a mute button (not for Master) and a level meter. Returns the volume. */
    private double busRow(String name, double volume, Boolean muted, float level) {
        lastMuteClicked = false;
        float scale = ImGui.getFontSize() / 15f;
        Widgets.label(name);
        float meterWidth = 90 * scale, buttonWidth = muted == null ? 0 : 52 * scale;
        float spacing = ImGui.getStyle().getItemSpacingX();
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() - meterWidth - buttonWidth - spacing * (muted == null ? 1 : 2));
        slider[0] = (float) (volume * 100);
        double result = volume;
        if (ImGui.sliderFloat("##volume", slider, 0, 100, "%.0f%%")) result = Math.max(0, Math.min(1, slider[0] / 100));
        if (muted != null) {
            ImGui.sameLine();
            if (muted) Widgets.pushAccent();
            if (ImGui.button(muted ? "Muted" : "Mute", buttonWidth, 0)) lastMuteClicked = true;
            if (muted) Widgets.popAccent();
        }
        ImGui.sameLine();
        meter(level, meterWidth, (muted != null && muted) || volume == 0);
        return result;
    }

    /** A level meter: green, yellow near the top, red when it's very loud. */
    private static void meter(float level, float width, boolean silenced) {
        ImVec2 at = ImGui.getCursorScreenPos();
        float height = ImGui.getFrameHeight();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(at.x, at.y, at.x + width, at.y + height, EditorStyle.pack(Theme.CURRENT_LINE), 3);
        float filled = Math.min(1, level) * width;
        java.awt.Color color = silenced ? Theme.TEXT_FAINT : level > 0.9f ? Theme.ERROR : level > 0.6f ? Theme.WARNING : Theme.SUCCESS;
        if (filled > 0.5f) draw.addRectFilled(at.x, at.y, at.x + filled, at.y + height, EditorStyle.pack(color), 3);
        ImGui.dummy(width, height);
    }

    // ---------------------------------------------------------------- Sound Maker

    private void drawSoundMaker() {
        Widgets.hint("Make sound effects without recording anything: pick a start, change it until it sounds right, save it into your project.");
        ImGui.spacing();
        for (String name : SoundMaker.PRESETS) {
            boolean active = name.equals(preset);
            if (active) Widgets.pushAccent();
            if (ImGui.button(name)) pickPreset(name);
            if (active) Widgets.popAccent();
            ImGui.sameLine();
            if (ImGui.getContentRegionAvailX() < ImGui.calcTextSize("Power-up").x + 24) ImGui.newLine();
        }
        ImGui.newLine();
        if (ImGui.button("Another " + preset)) pickPreset(preset);
        if (ImGui.isItemHovered()) ImGui.setTooltip("A new random take on the same kind of sound");
        ImGui.sameLine();
        if (ImGui.button("Mutate")) mutate();
        if (ImGui.isItemHovered()) ImGui.setTooltip("Nudge every setting a little");
        ImGui.sameLine();
        if (Widgets.primaryButtonInline("Play")) play();
        ImGui.sameLine();
        if (ImGui.checkbox("Play on change", autoPlay)) autoPlay = !autoPlay;
        ImGui.spacing();
        drawWaveform();
        ImGui.spacing();

        SoundMaker before = maker.copy();
        Widgets.label("Wave");
        if (ImGui.beginCombo("##wave", maker.wave.label)) {
            for (SoundMaker.Wave wave : SoundMaker.Wave.values()) {
                if (ImGui.selectable(wave.label, wave == maker.wave)) maker.wave = wave;
                if (ImGui.isItemHovered()) ImGui.setTooltip(wave.description);
            }
            ImGui.endCombo();
        }
        maker.frequency = logSlider("Pitch", maker.frequency, 40, 6000, "%.0f Hz", "How high the sound starts");
        maker.slide = logSlider("Slide", maker.slide, 0.05, 8, "x%.2f", "The pitch at the end: 2 = an octave up (jumps, power-ups), 0.5 = an octave down (lasers, falls)");
        maker.jump = logSlider("Jump", maker.jump, 0.25, 4, "x%.2f", "A sudden pitch change partway through: the coin's \"ding-ding\". 1 = none");
        if (maker.jump != 1) maker.jumpAt = linearSlider("Jump at", maker.jumpAt, 0, 1, "%.2f", "When the jump happens: 0 = start, 1 = end");
        maker.attack = linearSlider("Fade in", maker.attack, 0, 1, "%.2f s", "Seconds to get loud: 0 = straight away");
        maker.sustain = linearSlider("Hold", maker.sustain, 0, 2, "%.2f s", "Seconds at full volume");
        maker.decay = linearSlider("Fade out", maker.decay, 0, 2, "%.2f s", "Seconds to fade away");
        maker.punch = linearSlider("Punch", maker.punch, 0, 1, "%.2f", "Extra loud right at the start");
        maker.vibrato = linearSlider("Wobble", maker.vibrato, 0, 1, "%.2f", "How much the pitch wobbles");
        if (maker.vibrato > 0) maker.vibratoSpeed = linearSlider("Wobble speed", maker.vibratoSpeed, 0.5, 40, "%.1f /s", null);
        if (maker.wave == SoundMaker.Wave.SQUARE) maker.duty = linearSlider("Thinness", maker.duty, 0.05, 0.5, "%.2f", "0.5 = full and even; smaller = thinner, more nasal");
        maker.brightness = linearSlider("Brightness", maker.brightness, 0.05, 1, "%.2f", "Smaller = darker and more muffled");
        maker.volume = linearSlider("Volume", maker.volume, 0, 1, "%.2f", null);
        if (!same(before, maker)) {
            rendered = null;
            if (autoPlay && !ImGui.isMouseDown(0)) play();
            else pendingPlay = autoPlay;
        }
        if (pendingPlay && !ImGui.isMouseDown(0)) {
            pendingPlay = false;
            play(); // after a slider drag: play once it's let go
        }

        ImGui.spacing();
        ImGui.separator();
        saveName = Widgets.text("Name", saveName, "e.g. coin");
        String file = "assets/sounds/" + safeName(saveName) + ".wav";
        if (Widgets.primaryButton("Save as " + file)) save(file);
        if (!savedMessage.isEmpty()) Widgets.hint(savedMessage);
    }

    private boolean pendingPlay;

    private void pickPreset(String name) {
        preset = name;
        maker = SoundMaker.preset(name, random.nextLong());
        saveName = name.toLowerCase().replace("-", "");
        rendered = null;
        play();
    }

    private void mutate() {
        double n = 0.08;
        maker.frequency *= 1 + (random.nextDouble() * 2 - 1) * n * 2;
        maker.slide = Math.max(0.05, maker.slide * (1 + (random.nextDouble() * 2 - 1) * n));
        maker.sustain = Math.max(0, maker.sustain * (1 + (random.nextDouble() * 2 - 1) * n * 2));
        maker.decay = Math.max(0.01, maker.decay * (1 + (random.nextDouble() * 2 - 1) * n * 2));
        maker.punch = clamp(maker.punch + (random.nextDouble() * 2 - 1) * n, 0, 1);
        maker.duty = clamp(maker.duty + (random.nextDouble() * 2 - 1) * n * 0.5, 0.05, 0.5);
        if (maker.jump != 1) maker.jump = Math.max(0.25, maker.jump * (1 + (random.nextDouble() * 2 - 1) * n));
        rendered = null;
        play();
    }

    private float[] rendered() {
        if (rendered == null) rendered = maker.render();
        return rendered;
    }

    private void play() {
        ctx.previewAudio().play(AudioClip.ofMono(rendered()), "UI", 1, 1, false);
    }

    private void save(String file) {
        Path target = ctx.project.root().resolve(file);
        try {
            boolean replaced = Files.exists(target);
            AudioClip.ofMono(rendered()).writeWav(target);
            savedMessage = (replaced ? "Replaced " : "Saved ") + file + ". Play it with playSound(\"" + file + "\") or put it in an Audio Source.";
            ctx.log.info("Sound Maker saved " + file);
            ctx.assetsChanged();
        } catch (IOException e) {
            savedMessage = "Couldn't save: " + e.getMessage();
        }
    }

    /** The sound's shape: loudness over time, so you can see what the sliders did. */
    private void drawWaveform() {
        float[] samples = rendered();
        float scale = ImGui.getFontSize() / 15f;
        float width = ImGui.getContentRegionAvailX(), height = 60 * scale;
        ImVec2 at = ImGui.getCursorScreenPos();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(at.x, at.y, at.x + width, at.y + height, EditorStyle.pack(Theme.PANEL), 4);
        int columns = Math.max(1, (int) width);
        float mid = at.y + height / 2;
        for (int c = 0; c < columns; c++) {
            int from = (int) ((long) c * samples.length / columns), to = (int) ((long) (c + 1) * samples.length / columns);
            float peak = 0;
            for (int i = from; i < Math.min(samples.length, Math.max(to, from + 1)); i++) peak = Math.max(peak, Math.abs(samples[i]));
            float h = Math.min(1, peak) * (height / 2 - 3);
            if (h > 0.3f) draw.addLine(at.x + c, mid - h, at.x + c, mid + h, EditorStyle.pack(Theme.ACCENT));
        }
        String length = String.format("%.2f s", maker.seconds());
        draw.addText(at.x + width - ImGui.calcTextSize(length).x - 6, at.y + 4, EditorStyle.pack(Theme.TEXT_MUTED), length);
        ImGui.dummy(width, height);
    }

    /** A slider that moves evenly through octaves (for pitch and multiples). */
    private double logSlider(String label, double value, double min, double max, String format, String tooltip) {
        Widgets.label(label);
        double t = Math.log(Math.max(min, value) / min) / Math.log(max / min);
        slider[0] = (float) t;
        double result = value;
        if (ImGui.sliderFloat("##" + label, slider, 0, 1, String.format(format, value).replace("%", "%%"))) {
            result = min * Math.pow(max / min, slider[0]);
        }
        if (tooltip != null && ImGui.isItemHovered()) ImGui.setTooltip(tooltip);
        return result;
    }

    private double linearSlider(String label, double value, double min, double max, String format, String tooltip) {
        Widgets.label(label);
        slider[0] = (float) value;
        double result = value;
        if (ImGui.sliderFloat("##" + label, slider, (float) min, (float) max, format)) result = slider[0];
        if (tooltip != null && ImGui.isItemHovered()) ImGui.setTooltip(tooltip);
        return result;
    }

    private static boolean same(SoundMaker a, SoundMaker b) {
        return a.wave == b.wave && a.frequency == b.frequency && a.slide == b.slide && a.jump == b.jump && a.jumpAt == b.jumpAt
                && a.attack == b.attack && a.sustain == b.sustain && a.decay == b.decay && a.punch == b.punch
                && a.vibrato == b.vibrato && a.vibratoSpeed == b.vibratoSpeed && a.duty == b.duty
                && a.brightness == b.brightness && a.volume == b.volume;
    }

    /** "My Coin!" -> "my-coin": safe as a file name everywhere. */
    static String safeName(String name) {
        String safe = name.trim().toLowerCase().replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        return safe.isEmpty() ? "sound" : safe;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
