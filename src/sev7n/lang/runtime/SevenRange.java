package sev7n.lang.runtime;

/** The value of 'a..b'. Counts from 'from' up to (but not including) 'to'. Counts down if from > to. */
public record SevenRange(double from, double to) {

    @Override
    public String toString() {
        return Values.stringify(from) + ".." + Values.stringify(to);
    }
}
