package sev7n.lang;

/** Any error raised while lexing, parsing or running a Seven script. */
public class SevenError extends RuntimeException {

    private final int line;
    private String file;

    public SevenError(String message, int line) {
        super(message);
        this.line = line;
    }

    public int line() {
        return line;
    }

    /** The script file the error is in, or null if unknown. */
    public String file() {
        return file;
    }

    /**
     * Records which file the error happened in. Only the first call counts,
     * because the innermost code that catches the error knows the file best.
     */
    public SevenError inFile(String fileName) {
        if (file == null) file = fileName;
        return this;
    }

    /** A friendly message like "PlayerMove.7n:12: Unknown variable 'spd'". */
    public String pretty() {
        String where = file != null && line > 0 ? file + ":" + line + ": "
                : line > 0 ? "Line " + line + ": "
                : file != null ? file + ": "
                : "";
        return where + getMessage();
    }
}
