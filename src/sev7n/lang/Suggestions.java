package sev7n.lang;

import java.util.Collection;

/** "Did you mean ...?" for misspelled names: the closest known name, if it's close enough to be a typo. */
public final class Suggestions {

    private Suggestions() { }

    /** The candidate most like 'name' (ignoring case), or null if none is within a couple of typos. */
    public static String closest(String name, Collection<String> candidates) {
        return closest(name, java.util.List.of(candidates));
    }

    /** Like closest(name, candidates), but on a tie an earlier group wins (e.g. your own names over built-in ones). */
    public static String closest(String name, java.util.List<? extends Collection<String>> groups) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        int allowed = name.length() <= 3 ? 1 : name.length() <= 6 ? 2 : 3;
        for (Collection<String> group : groups) {
            for (String candidate : group) {
                if (candidate.equals(name) || candidate.startsWith("_")) continue;
                int d = distance(name.toLowerCase(), candidate.toLowerCase());
                // equally close: the one that starts more like it (people mistype the end of a word more than the start)
                boolean better = d < bestDistance || d == bestDistance && best != null && groupOf(groups, best) == group
                        && sharedStart(name, candidate) > sharedStart(name, best);
                if (better) {
                    best = candidate;
                    bestDistance = d;
                }
            }
        }
        return bestDistance <= allowed ? best : null;
    }

    private static Collection<String> groupOf(java.util.List<? extends Collection<String>> groups, String name) {
        for (Collection<String> group : groups) if (group.contains(name)) return group;
        return null;
    }

    private static int sharedStart(String a, String b) {
        int n = 0;
        while (n < a.length() && n < b.length() && Character.toLowerCase(a.charAt(n)) == Character.toLowerCase(b.charAt(n))) n++;
        return n;
    }

    /** How many single-letter edits (add, remove, change, swap two neighbours) turn a into b. */
    static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }
}
