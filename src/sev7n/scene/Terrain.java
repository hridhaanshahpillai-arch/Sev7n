package sev7n.scene;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Land for open worlds: hills and valleys made from noise (the same settings always make the
 * same land), coloured by height and steepness (sand, grass, rock, snow), with trees scattered
 * over it. Characters walk on it, ragdolls land on it, raycasts hit it and NPCs find their way
 * across it.
 *
 * The land is 'size' wide and deep, centred on the entity's position; heights go from the
 * entity's height up to 'height' above it. A flat patch in the middle ('flatRadius') is somewhere
 * to start.
 */
public final class Terrain extends Component {

    /** How wide and deep the land is (world units). */
    public double size = 200;
    /** How many squares across the land is made of: more = finer detail. */
    public int resolution = 128;
    /** How high the hills go. */
    public double height = 24;
    /** How big the hills are: smaller = wider, gentler hills. */
    public double roughness = 0.012;
    /** Different numbers make different land. */
    public int seed = 1;
    /** A flat circle this wide in the middle (a place to start), 0 = none. */
    public double flatRadius = 12;
    /** How many trees to scatter (on gentle slopes, below the snow). */
    public int trees = 250;

    public Color sand = new Color(0xC9B98A);
    public Color grass = new Color(0x5C9A4C);
    public Color rock = new Color(0x7D7C88);
    public Color snow = new Color(0xF0F0F4);

    /** How the editor's sculpting brushes change the land. */
    public enum Brush {
        RAISE("Raise", "Builds the land up: hills and mountains"),
        LOWER("Lower", "Digs the land down: valleys, lakes and pits"),
        SMOOTH("Smooth", "Softens bumps and sharp edges"),
        FLATTEN("Flatten", "Levels the land to the height where you started the stroke: paths, building spots");

        public final String label;
        public final String description;

        Brush(String label, String description) {
            this.label = label;
            this.description = description;
        }
    }

    /** Changes to sculpting anywhere (every terrain), so each change gets its own shape key. */
    private static final java.util.concurrent.atomic.AtomicInteger SCULPT_CHANGES = new java.util.concurrent.atomic.AtomicInteger();

    // hand-sculpted heights added on top of the generated land, one per grid corner ((sculptDetail+1) squared); null = none
    private float[] sculpt;
    private int sculptDetail;
    private int sculptVersion;

    // worked out from the settings when they change (not saved)
    private String builtFor = "";
    private String baseFor = "";
    private float[] base;      // the generated land alone
    private float[] heights;   // with the sculpting added
    private List<double[]> treeSpots = List.of();

    /** What the land looks like now: a key that changes whenever a setting (or sculpting) that shapes it changes. */
    public String shapeKey() {
        return baseKey() + "|" + trees + "|" + sculptVersion;
    }

    private String baseKey() {
        return size + "|" + resolution + "|" + height + "|" + roughness + "|" + seed + "|" + flatRadius;
    }

    // ---------------------------------------------------------------- sculpting

    /** True if the land has been sculpted by hand. */
    public synchronized boolean sculpted() {
        return sculpt != null;
    }

    /** Takes away all the hand sculpting: back to the generated land. */
    public synchronized void clearSculpt() {
        sculpt = null;
        sculptVersion = SCULPT_CHANGES.incrementAndGet();
    }

    /**
     * One dab of a brush centred on (x, z) from the middle of the land, 'radius' wide, softer
     * toward its edge. 'amount' at the centre: units up or down for Raise and Lower, how far to
     * go (0..1) for Smooth and Flatten. Flatten levels toward 'target' (a height above the entity).
     * Returns true if anything changed.
     */
    public synchronized boolean sculpt(Brush brush, double x, double z, double radius, double amount, double target) {
        build();
        int n = Math.max(2, resolution);
        if (sculpt == null) {
            sculpt = new float[(n + 1) * (n + 1)];
            sculptDetail = n;
        }
        double cell = size / n;
        int x0 = (int) Math.floor((x - radius) / cell + n / 2.0), x1 = (int) Math.ceil((x + radius) / cell + n / 2.0);
        int z0 = (int) Math.floor((z - radius) / cell + n / 2.0), z1 = (int) Math.ceil((z + radius) / cell + n / 2.0);
        x0 = Math.max(0, x0);
        z0 = Math.max(0, z0);
        x1 = Math.min(n, x1);
        z1 = Math.min(n, z1);
        if (x0 > x1 || z0 > z1 || radius <= 0) return false;
        float[] before = heights.clone(); // smoothing reads the land as it was before this dab
        boolean changed = false;
        for (int iz = z0; iz <= z1; iz++) {
            for (int ix = x0; ix <= x1; ix++) {
                double px = ((double) ix / n - 0.5) * size, pz = ((double) iz / n - 0.5) * size;
                double d = Math.hypot(px - x, pz - z) / radius;
                if (d >= 1) continue;
                double falloff = smooth(1 - d);
                int i = iz * (n + 1) + ix;
                double now = before[i];
                double change = switch (brush) {
                    case RAISE -> amount * falloff;
                    case LOWER -> -amount * falloff;
                    case SMOOTH -> (neighbourAverage(before, n, ix, iz) - now) * Math.min(1, amount * falloff);
                    case FLATTEN -> (target - now) * Math.min(1, amount * falloff);
                };
                if (change == 0) continue;
                sculpt[i] += (float) change;
                heights[i] += (float) change;
                changed = true;
            }
        }
        if (changed) {
            sculptVersion = SCULPT_CHANGES.incrementAndGet();
            builtFor = shapeKey(); // the heights are already up to date; only the trees need placing again
            placeTrees();
        }
        return changed;
    }

    private static double neighbourAverage(float[] h, int n, int ix, int iz) {
        double total = 0;
        int count = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int x = ix + dx, z = iz + dz;
                if (x < 0 || z < 0 || x > n || z > n) continue;
                total += h[z * (n + 1) + x];
                count++;
            }
        }
        return total / count;
    }

    /** The sculpting stretched to a new detail level (so changing Detail keeps what was sculpted). */
    private static float[] resample(float[] from, int fromN, int toN) {
        float[] to = new float[(toN + 1) * (toN + 1)];
        for (int iz = 0; iz <= toN; iz++) {
            for (int ix = 0; ix <= toN; ix++) {
                double gx = (double) ix / toN * fromN, gz = (double) iz / toN * fromN;
                int x0 = Math.min(fromN - 1, (int) gx), z0 = Math.min(fromN - 1, (int) gz);
                double fx = gx - x0, fz = gz - z0;
                double top = from[z0 * (fromN + 1) + x0] * (1 - fx) + from[z0 * (fromN + 1) + x0 + 1] * fx;
                double bottom = from[(z0 + 1) * (fromN + 1) + x0] * (1 - fx) + from[(z0 + 1) * (fromN + 1) + x0 + 1] * fx;
                to[iz * (toN + 1) + ix] = (float) (top * (1 - fz) + bottom * fz);
            }
        }
        return to;
    }

    /** Heights at the grid's corners ((resolution+1) squared, row by row from -z to +z, -x to +x), above the entity. */
    public synchronized float[] heights() {
        build();
        return heights;
    }

    /** Where the trees stand: x, z (from the middle), height of the ground there, and how big the tree is. */
    public synchronized List<double[]> trees() {
        build();
        return treeSpots;
    }

    /** The ground's height above the entity at (x, z) from the middle of the land, or NaN off the edge. */
    public synchronized double heightAt(double x, double z) {
        build();
        int n = Math.max(2, resolution);
        double gx = (x / size + 0.5) * n, gz = (z / size + 0.5) * n;
        if (gx < 0 || gz < 0 || gx > n || gz > n) return Double.NaN;
        int ix = Math.min(n - 1, (int) gx), iz = Math.min(n - 1, (int) gz);
        double fx = gx - ix, fz = gz - iz;
        double h00 = heights[iz * (n + 1) + ix], h10 = heights[iz * (n + 1) + ix + 1];
        double h01 = heights[(iz + 1) * (n + 1) + ix], h11 = heights[(iz + 1) * (n + 1) + ix + 1];
        // the same two triangles per square the mesh uses, so the ground is exactly where it's drawn
        if (fx + fz <= 1) return h00 + (h10 - h00) * fx + (h01 - h00) * fz;
        return h11 + (h01 - h11) * (1 - fx) + (h10 - h11) * (1 - fz);
    }

    /** How steep the ground is at (x, z): 0 = flat, 1 = a cliff. */
    public double steepness(double x, double z) {
        double step = size / Math.max(2, resolution);
        double dx = heightAt(x + step, z) - heightAt(x - step, z);
        double dz = heightAt(x, z + step) - heightAt(x, z - step);
        double slope = Math.hypot(dx, dz) / (2 * step);
        return Double.isNaN(slope) ? 0 : Math.min(1, slope);
    }

    /** The colour of the ground at a height (0..1 of 'height') and steepness. */
    public Color colorAt(double heightFraction, double steepness) {
        Color base = heightFraction < 0.06 ? sand : heightFraction > 0.78 ? snow : grass;
        if (steepness > 0.55 && base != snow) base = rock;
        else if (steepness > 0.4 && base != snow) base = mix(base, rock, (steepness - 0.4) / 0.15);
        return base;
    }

    private void build() {
        String key = shapeKey();
        if (key.equals(builtFor) && heights != null) return;
        int n = Math.max(2, resolution);
        if (!baseKey().equals(baseFor) || base == null) {
            base = new float[(n + 1) * (n + 1)];
            for (int iz = 0; iz <= n; iz++) {
                for (int ix = 0; ix <= n; ix++) {
                    double x = ((double) ix / n - 0.5) * size, z = ((double) iz / n - 0.5) * size;
                    base[iz * (n + 1) + ix] = (float) rawHeight(x, z);
                }
            }
            baseFor = baseKey();
        }
        if (sculpt != null && sculptDetail != n) {
            sculpt = resample(sculpt, sculptDetail, n);
            sculptDetail = n;
        }
        heights = base.clone();
        if (sculpt != null) for (int i = 0; i < heights.length; i++) heights[i] += sculpt[i];
        builtFor = key;
        placeTrees();
    }

    /**
     * Scatters trees on gentle slopes below the snow. Every try uses the same random numbers
     * whether or not a tree grows there, so sculpting one spot doesn't move trees everywhere else.
     */
    private void placeTrees() {
        List<double[]> spots = new ArrayList<>();
        Random random = new Random(seed * 7919L + 13);
        for (int tries = 0; tries < trees * 6 && spots.size() < trees; tries++) {
            double x = (random.nextDouble() - 0.5) * size * 0.96, z = (random.nextDouble() - 0.5) * size * 0.96;
            double scale = 0.8 + random.nextDouble() * 0.7;
            if (Math.hypot(x, z) < flatRadius + 3) continue; // keep the start clear
            double h = heightAt(x, z);
            if (Double.isNaN(h) || h / height > 0.68 || h / height < 0.07 || steepness(x, z) > 0.38) continue;
            spots.add(new double[] {x, z, h, scale});
        }
        treeSpots = List.copyOf(spots);
    }

    /** Layers of smooth noise (big hills plus smaller bumps), flattened in the middle. */
    private double rawHeight(double x, double z) {
        double total = 0, amplitude = 1, frequency = roughness, range = 0;
        for (int octave = 0; octave < 5; octave++) {
            total += amplitude * valueNoise(x * frequency, z * frequency, seed + octave * 101);
            range += amplitude;
            amplitude *= 0.5;
            frequency *= 2.03;
        }
        double h = Math.pow(Math.max(0, total / range), 1.6) * height; // valleys wider than peaks
        if (flatRadius > 0) {
            double centre = Math.pow(Math.max(0, fbmAtCentre() / range), 1.6) * height;
            double d = Math.hypot(x, z);
            double blend = smooth(Math.max(0, Math.min(1, (d - flatRadius) / (flatRadius * 1.5))));
            h = centre + (h - centre) * blend;
        }
        return h;
    }

    private double fbmAtCentre() {
        double total = 0, amplitude = 1, frequency = roughness;
        for (int octave = 0; octave < 5; octave++) {
            total += amplitude * valueNoise(0, 0, seed + octave * 101);
            amplitude *= 0.5;
            frequency *= 2.03;
        }
        return total;
    }

    /** Smooth noise between 0 and 1. */
    private static double valueNoise(double x, double z, int salt) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        double fx = smooth(x - x0), fz = smooth(z - z0);
        double a = lattice(x0, z0, salt), b = lattice(x0 + 1, z0, salt);
        double c = lattice(x0, z0 + 1, salt), d = lattice(x0 + 1, z0 + 1, salt);
        return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fz;
    }

    private static double lattice(int x, int z, int salt) {
        long h = x * 374761393L + z * 668265263L + salt * 2147483647L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (double) 0xFFFFFF;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static Color mix(Color a, Color b, double t) {
        t = Math.max(0, Math.min(1, t));
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t), (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    @Override
    public String type() {
        return "Terrain";
    }

    @Override
    public String displayName() {
        return "Terrain";
    }

    @Override
    public void save(Map<String, Object> out) {
        out.put("size", size);
        out.put("resolution", (double) resolution);
        out.put("height", height);
        out.put("roughness", roughness);
        out.put("seed", (double) seed);
        out.put("flatRadius", flatRadius);
        out.put("trees", (double) trees);
        out.put("sand", Json.color(sand));
        out.put("grass", Json.color(grass));
        out.put("rock", Json.color(rock));
        out.put("snow", Json.color(snow));
        synchronized (this) {
            if (sculpt != null) {
                // the hand sculpting, as little-endian floats in base64 (a few dozen KB for normal detail)
                java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(sculpt.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                for (float f : sculpt) bytes.putFloat(f);
                out.put("sculptDetail", (double) sculptDetail);
                out.put("sculpt", java.util.Base64.getEncoder().encodeToString(bytes.array()));
            }
        }
    }

    @Override
    public void load(Map<String, Object> in) {
        size = Json.number(in, "size", size);
        resolution = (int) Json.number(in, "resolution", resolution);
        height = Json.number(in, "height", height);
        roughness = Json.number(in, "roughness", roughness);
        seed = (int) Json.number(in, "seed", seed);
        flatRadius = Json.number(in, "flatRadius", flatRadius);
        trees = (int) Json.number(in, "trees", trees);
        sand = Json.color(in, "sand", sand);
        grass = Json.color(in, "grass", grass);
        rock = Json.color(in, "rock", rock);
        snow = Json.color(in, "snow", snow);
        synchronized (this) {
            sculpt = null;
            String saved = Json.string(in, "sculpt", "");
            int detail = (int) Json.number(in, "sculptDetail", 0);
            if (!saved.isEmpty() && detail >= 2) {
                try {
                    java.nio.ByteBuffer bytes = java.nio.ByteBuffer.wrap(java.util.Base64.getDecoder().decode(saved)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    if (bytes.remaining() == (detail + 1) * (detail + 1) * 4) {
                        sculpt = new float[(detail + 1) * (detail + 1)];
                        for (int i = 0; i < sculpt.length; i++) sculpt[i] = bytes.getFloat();
                        sculptDetail = detail;
                    }
                } catch (IllegalArgumentException ignored) {
                    // damaged sculpting: keep the generated land
                }
            }
            sculptVersion = SCULPT_CHANGES.incrementAndGet();
        }
    }
}
