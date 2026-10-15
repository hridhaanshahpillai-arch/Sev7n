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

    // worked out from the settings when they change (not saved)
    private String builtFor = "";
    private float[] heights;
    private List<double[]> treeSpots = List.of();

    /** What the land looks like now: a key that changes whenever a setting that shapes it changes. */
    public String shapeKey() {
        return size + "|" + resolution + "|" + height + "|" + roughness + "|" + seed + "|" + flatRadius + "|" + trees;
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
        heights = new float[(n + 1) * (n + 1)];
        for (int iz = 0; iz <= n; iz++) {
            for (int ix = 0; ix <= n; ix++) {
                double x = ((double) ix / n - 0.5) * size, z = ((double) iz / n - 0.5) * size;
                heights[iz * (n + 1) + ix] = (float) rawHeight(x, z);
            }
        }
        builtFor = key;
        treeSpots = List.of(); // place trees on the new land
        List<double[]> spots = new ArrayList<>();
        Random random = new Random(seed * 7919L + 13);
        for (int tries = 0; tries < trees * 6 && spots.size() < trees; tries++) {
            double x = (random.nextDouble() - 0.5) * size * 0.96, z = (random.nextDouble() - 0.5) * size * 0.96;
            if (Math.hypot(x, z) < flatRadius + 3) continue; // keep the start clear
            double h = heightAt(x, z);
            if (Double.isNaN(h) || h / height > 0.68 || h / height < 0.07 || steepness(x, z) > 0.38) continue;
            spots.add(new double[] {x, z, h, 0.8 + random.nextDouble() * 0.7});
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
    }
}
