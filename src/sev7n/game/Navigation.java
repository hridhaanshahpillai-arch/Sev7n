package sev7n.game;

import sev7n.math.Vec3;
import sev7n.scene.Entity;
import sev7n.scene.Rigidbody;
import sev7n.scene.Scene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * Finds a way around walls and up stairs for a character: A* over a grid of small squares,
 * where each square can have several floors (the ground, a platform above it, a stair...).
 * Then it straightens the path so the character walks in lines instead of zig-zags.
 *
 * A floor is the top of a collider that the character's body fits on. From one square to the
 * next it can climb up to its step height (its Rigidbody's, so stairs and curbs work) and drop
 * down up to DROP (jumping off a ledge). Only the world counts (colliders without a Rigidbody, and
 * solid tiles): other characters move, so they're steered around while walking instead.
 * A character that starts with nothing under it (a top-down game, a flying thing) moves on a
 * single flat level at its own height.
 */
final class Navigation {

    static final double CELL = 0.5;
    private static final double DROP = 1.3;
    private static final int MAX_NODES = 8000;
    private static final int[][] NEIGHBOURS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** A place to stand: a square and which floor in it. */
    private record Spot(int x, int z, double floor) {
        long key() {
            long level = Math.round(floor / 0.02) & 0xFFFFFL;
            return ((long) x & 0xFFFFFL) << 40 | ((long) z & 0xFFFFFL) << 20 | level;
        }

        Vec3 point() {
            return new Vec3((x + 0.5) * CELL, floor, (z + 0.5) * CELL);
        }
    }

    private final Scene scene;
    private final List<WorldCollider> world = new ArrayList<>();
    private final double radius;
    private final double height;
    private final double feet;
    private final double climb;
    private final boolean flat;
    private final boolean hasTiles;
    private final List<Entity> terrains; // most 3D scenes have no solid tilemaps: skip checking them
    private final Map<Long, double[]> floors = new HashMap<>();

    /** A map for one character (its size and where its feet are), ignoring 'ignore' (usually whoever it's going to). */
    Navigation(Scene scene, Entity self, Entity ignore) {
        this.scene = scene;
        WorldCollider body = WorldCollider.of(self);
        Vec3 p = self.transform.position;
        Box bounds = body != null ? body.bounds() : new Box(p.x - 0.4, p.y, p.z - 0.4, p.x + 0.4, p.y + 1.8, p.z + 0.4);
        radius = Math.max(0.15, Math.min(bounds.maxX() - bounds.minX(), bounds.maxZ() - bounds.minZ()) / 2 * 0.9);
        height = Math.max(radius * 2, bounds.maxY() - bounds.minY());
        feet = bounds.minY();
        Rigidbody rigidbody = self.get(Rigidbody.class);
        climb = Math.max(0.05, Math.min(height * 0.4, rigidbody != null && rigidbody.stepHeight > 0 ? rigidbody.stepHeight : 0.35));
        for (WorldCollider collider : WorldCollider.all(scene, false)) {
            Entity e = collider.entity();
            if (e != self && e != ignore && !e.has(Rigidbody.class)) world.add(collider);
        }
        hasTiles = !Physics.solidTilemaps(scene).isEmpty();
        terrains = Ground.terrains(scene);
        flat = !hasFloorBelow(p.x, p.z);
    }

    /** Waypoints (standing on the floors) from 'from' to 'to', or as close as it can get if 'to' can't be reached. */
    List<Vec3> path(Vec3 from, Vec3 to) {
        Spot start = spotAt(from.x, from.z, feet);
        Spot goal = spotAt(to.x, to.z, to.y + 0.3);
        if (start == null) return new ArrayList<>(List.of(new Vec3(to.x, feet, to.z))); // stuck in something: just head there
        if (goal != null && arrivesOn(from, start.floor, to, goal.floor)) {
            return new ArrayList<>(List.of(new Vec3(to.x, goal.floor, to.z)));
        }
        int gx = cell(to.x), gz = cell(to.z);
        double goalFloor = goal != null ? goal.floor : to.y;

        Map<Long, Spot> cameFrom = new HashMap<>();
        Map<Long, Double> cost = new HashMap<>();
        Map<Long, Spot> spots = new HashMap<>();
        PriorityQueue<Object[]> open = new PriorityQueue<>((a, b) -> Double.compare((double) a[0], (double) b[0]));
        cost.put(start.key(), 0.0);
        spots.put(start.key(), start);
        open.add(new Object[] {estimate(start, gx, gz, goalFloor), start});
        Spot closest = start;
        double closestEstimate = estimate(start, gx, gz, goalFloor);
        int expanded = 0;

        while (!open.isEmpty() && expanded++ < MAX_NODES) {
            Object[] entry = open.poll();
            Spot here = (Spot) entry[1];
            long key = here.key();
            double h = estimate(here, gx, gz, goalFloor);
            if (goal != null && key == goal.key()) {
                closest = here;
                break;
            }
            if (h < closestEstimate) {
                closestEstimate = h;
                closest = here;
            }
            double sofar = cost.get(key);
            if ((double) entry[0] > sofar + h + 1e-9) continue; // a better way here was already found
            for (int[] d : NEIGHBOURS) {
                Spot next = step(here, here.x + d[0], here.z + d[1]);
                if (next == null) continue;
                if (d[0] != 0 && d[1] != 0 && (step(here, here.x + d[0], here.z) == null || step(here, here.x, here.z + d[1]) == null)) continue; // don't cut corners
                long nextKey = next.key();
                double nextCost = sofar + (d[0] != 0 && d[1] != 0 ? Math.sqrt(2) : 1) + Math.abs(next.floor - here.floor);
                if (nextCost < cost.getOrDefault(nextKey, Double.MAX_VALUE)) {
                    cost.put(nextKey, nextCost);
                    cameFrom.put(nextKey, here);
                    spots.put(nextKey, next);
                    open.add(new Object[] {nextCost + estimate(next, gx, gz, goalFloor), next});
                }
            }
        }

        List<Vec3> cells = new ArrayList<>();
        for (Spot at = closest; at.key() != start.key(); at = cameFrom.get(at.key())) cells.add(at.point());
        Collections.reverse(cells);
        if (goal != null && closest.key() == goal.key() && !cells.isEmpty()) cells.set(cells.size() - 1, new Vec3(to.x, goal.floor, to.z));
        return straighten(from, start.floor, cells);
    }

    /**
     * Walks a straight line from 'from' (standing on 'floor') toward 'to', over steps but not drops:
     * the floor it ends on, or null if something's in the way.
     */
    private Double walkStraight(Vec3 from, double floor, Vec3 to) {
        double dx = to.x - from.x, dz = to.z - from.z;
        int steps = Math.max(1, (int) Math.ceil(Math.hypot(dx, dz) / (CELL * 0.5)));
        double at = floor;
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            double x = from.x + dx * t, z = from.z + dz * t;
            Double next = nearestFloor(floorsAt(cell(x), cell(z)), at, climb + 0.01, climb + 0.01);
            if (next == null || !fits(x, z, next)) return null;
            at = next;
        }
        return at;
    }

    /** True if walking straight from 'from' on 'floor' gets to 'to' on the floor 'arrival'. */
    private boolean arrivesOn(Vec3 from, double floor, Vec3 to, double arrival) {
        Double end = walkStraight(from, floor, to);
        return end != null && Math.abs(end - arrival) < climb + 0.01;
    }
    /** A random spot within 'range' of a point, on a floor near that point's height, or null if none was found. */
    Vec3 randomSpot(Vec3 around, double range, Random random) {
        for (int tries = 0; tries < 20; tries++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = Math.sqrt(random.nextDouble()) * range;
            double x = around.x + Math.cos(angle) * distance, z = around.z + Math.sin(angle) * distance;
            Double floor = nearestFloor(floorsAt(cell(x), cell(z)), feet, 0.5, 0.5);
            if (floor != null) return new Vec3(x, floor, z);
        }
        return null;
    }

    /** Where the character stands at (x, z): the highest floor at or below 'below', or null. */
    private Spot spotAt(double x, double z, double below) {
        int cx = cell(x), cz = cell(z);
        for (int ring = 0; ring <= 2; ring++) { // standing right against a wall can put its square inside the wall
            Spot best = null;
            for (int ix = cx - ring; ix <= cx + ring; ix++) {
                for (int iz = cz - ring; iz <= cz + ring; iz++) {
                    if (Math.max(Math.abs(ix - cx), Math.abs(iz - cz)) != ring) continue;
                    for (double f : floorsAt(ix, iz)) {
                        if (f <= below + climb && (best == null || f > best.floor)) best = new Spot(ix, iz, f);
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    /** Moving from one spot to the square (x, z): the floor it ends up on (a step up, the same, or a drop), or null if it can't. */
    private Spot step(Spot from, int x, int z) {
        Double floor = nearestFloor(floorsAt(x, z), from.floor, climb + 0.01, DROP);
        return floor == null ? null : new Spot(x, z, floor);
    }

    /** The floor in 'options' closest to 'level', at most 'up' above it and 'down' below it, or null. */
    private static Double nearestFloor(double[] options, double level, double up, double down) {
        Double best = null;
        for (double f : options) {
            if (f > level + up || f < level - down) continue;
            if (best == null || Math.abs(f - level) < Math.abs(best - level)) best = f;
        }
        return best;
    }

    /** Every floor the character can stand on in a square, lowest first. */
    private double[] floorsAt(int x, int z) {
        long key = ((long) x << 32) | (z & 0xffffffffL);
        double[] cached = floors.get(key);
        if (cached != null) return cached;
        double cx = (x + 0.5) * CELL, cz = (z + 0.5) * CELL;
        List<Double> found = new ArrayList<>();
        if (flat) {
            if (fits(cx, cz, feet)) found.add(feet);
        } else {
            for (WorldCollider collider : world) {
                Box b = collider.bounds();
                if (cx < b.minX() || cx > b.maxX() || cz < b.minZ() || cz > b.maxZ()) continue;
                double top = b.maxY();
                if (top < feet - 50 || top > feet + 50 || found.stream().anyMatch(f -> Math.abs(f - top) < 0.05)) continue;
                if (!buried(cx, cz, top) && fits(cx, cz, top)) found.add(top);
            }
            double land = Ground.heightAt(terrains, cx, cz);
            if (!Double.isNaN(land) && Math.abs(land - feet) < 50 && found.stream().noneMatch(f -> Math.abs(f - land) < 0.05)
                    && !buried(cx, cz, land) && fits(cx, cz, land)) found.add(land);
            Collections.sort(found);
        }
        double[] result = found.stream().mapToDouble(Double::doubleValue).toArray();
        floors.put(key, result);
        return result;
    }

    /** True if the character's body fits standing on 'floor' at (x, z), stepping over anything lower than its step height. */
    private boolean fits(double x, double z, double floor) {
        double low = floor + climb + radius;
        double high = Math.max(low, floor + height - radius);
        Round body = new Round(new Vec3(x, low, z), new Vec3(x, high, z), radius);
        WorldCollider probe = new WorldCollider(null, body.bounds(), body, false);
        for (WorldCollider collider : world) {
            if (collider.touches(probe, 0)) return false;
        }
        return !hasTiles || !Physics.solidTileAt(scene, new Vec3(x, low, z));
    }

    /** True if something solid sits right on top of a floor at (x, z), like the ground under a stair. */
    private boolean buried(double x, double z, double floor) {
        Vec3 above = new Vec3(x, floor + 0.03, z);
        Round dot = new Round(above, above.copy(), 0.01);
        WorldCollider probe = new WorldCollider(null, dot.bounds(), dot, false);
        for (WorldCollider collider : world) {
            if (collider.touches(probe, 0)) return true;
        }
        return false;
    }

    private boolean hasFloorBelow(double x, double z) {
        double land = Ground.heightAt(terrains, x, z);
        if (!Double.isNaN(land) && land <= feet + 0.3 && land >= feet - 2) return true;
        for (WorldCollider collider : world) {
            Box b = collider.bounds();
            if (x >= b.minX() && x <= b.maxX() && z >= b.minZ() && z <= b.maxZ() && b.maxY() <= feet + 0.3 && b.maxY() >= feet - 2) return true;
        }
        return false;
    }

    /** Skips waypoints that can be reached in a straight line from an earlier one. */
    private List<Vec3> straighten(Vec3 from, double floor, List<Vec3> cells) {
        List<Vec3> result = new ArrayList<>();
        Vec3 at = from;
        double atFloor = floor;
        int i = 0;
        while (i < cells.size()) {
            int furthest = i;
            for (int j = cells.size() - 1; j > i; j--) {
                if (arrivesOn(at, atFloor, cells.get(j), cells.get(j).y)) {
                    furthest = j;
                    break;
                }
            }
            at = cells.get(furthest);
            atFloor = at.y;
            result.add(at);
            i = furthest + 1;
        }
        return result;
    }

    private static int cell(double v) {
        return (int) Math.floor(v / CELL);
    }

    /** Distance in squares if diagonal steps are allowed, plus the climb. */
    private static double estimate(Spot s, int gx, int gz, double goalFloor) {
        int dx = Math.abs(s.x - gx), dz = Math.abs(s.z - gz);
        return Math.max(dx, dz) + (Math.sqrt(2) - 1) * Math.min(dx, dz) + Math.abs(s.floor - goalFloor);
    }
}
