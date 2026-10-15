package sev7n.game;

import sev7n.math.Vec3;
import sev7n.scene.Entity;
import sev7n.scene.Scene;
import sev7n.scene.Terrain;

import java.util.ArrayList;
import java.util.List;

/**
 * The land under things: the terrains in a scene, asked "how high is the ground here?".
 * Physics keeps bodies and ragdolls on top of it, raycasts hit it, NPCs walk over it.
 */
final class Ground {

    private Ground() { }

    /** The active terrains in a scene (none in most scenes, so checks can be skipped). */
    static List<Entity> terrains(Scene scene) {
        List<Entity> found = new ArrayList<>();
        for (Entity entity : scene.entities()) {
            if (entity.active && entity.has(Terrain.class)) found.add(entity);
        }
        return found;
    }

    /** The highest ground at (x, z) among the terrains, or NaN where there's none. */
    static double heightAt(List<Entity> terrains, double x, double z) {
        double best = Double.NaN;
        for (Entity entity : terrains) {
            Vec3 p = entity.transform.position;
            double h = entity.get(Terrain.class).heightAt(x - p.x, z - p.z);
            if (!Double.isNaN(h) && (Double.isNaN(best) || p.y + h > best)) best = p.y + h;
        }
        return best;
    }

    /** Which way the ground faces at (x, z) (straight up on flat land). */
    static Vec3 normalAt(List<Entity> terrains, double x, double z) {
        double step = 0.5;
        double dx = heightAt(terrains, x + step, z) - heightAt(terrains, x - step, z);
        double dz = heightAt(terrains, x, z + step) - heightAt(terrains, x, z - step);
        if (Double.isNaN(dx) || Double.isNaN(dz)) return new Vec3(0, 1, 0);
        return new Vec3(-dx, 2 * step, -dz).normalized();
    }

    /** The terrain whose ground is highest at (x, z), or null. */
    static Entity terrainAt(List<Entity> terrains, double x, double z) {
        Entity best = null;
        double bestHeight = Double.NEGATIVE_INFINITY;
        for (Entity entity : terrains) {
            Vec3 p = entity.transform.position;
            double h = entity.get(Terrain.class).heightAt(x - p.x, z - p.z);
            if (!Double.isNaN(h) && p.y + h > bestHeight) {
                bestHeight = p.y + h;
                best = entity;
            }
        }
        return best;
    }

    /** Where a ray first goes into the ground (distance along it), or NaN if it doesn't within maxDistance. */
    static double rayDistance(List<Entity> terrains, Vec3 origin, Vec3 direction, double maxDistance) {
        if (terrains.isEmpty()) return Double.NaN;
        Vec3 d = direction.normalized();
        double step = 0.4;
        double previous = 0;
        for (double t = 0; t <= maxDistance; t += step) {
            Vec3 at = origin.plus(d.times(t));
            double ground = heightAt(terrains, at.x, at.z);
            if (!Double.isNaN(ground) && at.y <= ground) {
                // went under between the last step and this one: narrow it down
                double low = previous, high = t;
                for (int i = 0; i < 12; i++) {
                    double mid = (low + high) / 2;
                    Vec3 m = origin.plus(d.times(mid));
                    double g = heightAt(terrains, m.x, m.z);
                    if (!Double.isNaN(g) && m.y <= g) high = mid;
                    else low = mid;
                }
                return high;
            }
            previous = t;
        }
        return Double.NaN;
    }
}
