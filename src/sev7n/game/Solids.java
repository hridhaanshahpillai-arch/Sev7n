package sev7n.game;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import sev7n.math.Vec3;
import sev7n.scene.Entity;
import sev7n.scene.ParticleEmitter;
import sev7n.scene.Scene;
import sev7n.scene.Tilemap;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything solid in a scene at one moment (box, sphere and capsule colliders that aren't triggers, and solid
 * tilemaps), gathered once so thousands of particles can quickly ask "is this point solid?".
 */
public final class Solids implements ParticleEmitter.Solid {

    private record TileGrid(Matrix4f toLocal, Tilemap map) { }

    private final List<Box> boxes = new ArrayList<>();
    private final List<Round> rounds = new ArrayList<>();
    private final List<TileGrid> grids = new ArrayList<>();
    private final Vector3f local = new Vector3f();

    private Solids() { }

    public static Solids of(Scene scene) {
        Solids solids = new Solids();
        for (WorldCollider collider : WorldCollider.all(scene, false)) {
            if (collider.round() != null) solids.rounds.add(collider.round());
            else solids.boxes.add(collider.bounds());
        }
        for (Entity entity : scene.entities()) {
            if (!entity.active) continue;
            Tilemap map = entity.get(Tilemap.class);
            if (map != null && map.solid) solids.grids.add(new TileGrid(entity.transform.matrix(new Matrix4f()).invert(), map));
        }
        return solids;
    }

    public boolean isEmpty() {
        return boxes.isEmpty() && rounds.isEmpty() && grids.isEmpty();
    }

    @Override
    public boolean at(double x, double y, double z) {
        for (Box b : boxes) {
            if (x >= b.minX() && x <= b.maxX() && y >= b.minY() && y <= b.maxY() && z >= b.minZ() && z <= b.maxZ()) return true;
        }
        Vec3 point = rounds.isEmpty() ? null : new Vec3(x, y, z);
        for (Round round : rounds) {
            if (round.closestTo(point).distance(point) <= round.radius()) return true;
        }
        for (TileGrid grid : grids) {
            grid.toLocal().transformPosition(local.set((float) x, (float) y, (float) z));
            Tilemap map = grid.map();
            int column = (int) Math.floor(local.x / map.tileSize);
            int row = (int) Math.floor(local.y / map.tileSize);
            if (Math.abs(local.z) <= map.tileSize / 2 && map.tile(column, row) != Tilemap.EMPTY) return true;
        }
        return false;
    }
}
