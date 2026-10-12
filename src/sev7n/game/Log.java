package sev7n.game;

import sev7n.lang.SevenError;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Messages for the editor's Console: print() output, errors, and engine notes. */
public final class Log {

    public enum Level { INFO, WARNING, ERROR, SYSTEM }

    /** One console line. 'file' and 'line' point at a script when the message came from one. */
    public record Entry(Level level, String text, String file, int line) { }

    private static final int MAX_ENTRIES = 2000;

    private final List<Entry> entries = new ArrayList<>();
    private int version = 0; // bumps on every change, so the console knows when to scroll

    public void info(String text) {
        add(new Entry(Level.INFO, text, null, 0));
    }

    public void warning(String text) {
        add(new Entry(Level.WARNING, text, null, 0));
    }

    public void error(String text) {
        add(new Entry(Level.ERROR, text, null, 0));
    }

    public void error(SevenError e) {
        add(new Entry(Level.ERROR, e.pretty(), e.file(), e.line()));
    }

    /** Quiet engine messages, like "Saved scene". */
    public void system(String text) {
        add(new Entry(Level.SYSTEM, text, null, 0));
    }

    public void clear() {
        entries.clear();
        version++;
    }

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public int version() {
        return version;
    }

    private void add(Entry entry) {
        entries.add(entry);
        if (entries.size() > MAX_ENTRIES) entries.remove(0);
        version++;
    }
}
