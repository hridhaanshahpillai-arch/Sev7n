package sev7n.game;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import sev7n.scene.BoxCollider;
import sev7n.scene.Entity;
import sev7n.scene.ParticleEmitter;
import sev7n.scene.Scene;
import sev7n.scene.Tilemap;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything solid in a scene at one moment (colliders that aren't triggers, and solid
 * tilemaps), gathered once so thousands of particles can quickly ask "is this point solid?".
 */
public final class Solids implements ParticleEmitter.Solid {

    private record TileGrid(Matrix4f toLocal, Tilemap map) { }

    private final List<Box> boxes = new ArrayList<>();
    private final List<TileGrid> grids = new ArrayList<>();
    private final Vector3f local = new Vector3f();

    private Solids() { }

    public static Solids of(Scene scene) {
        Solids solids = new Solids();
        for (Entity entity : scene.entities()) {
            if (!entity.active) continue;
            BoxCollider collider = entity.get(BoxCollider.class);
            if (collider != null && !collider.trigger) solids.boxes.add(Box.of(entity, collider));
            Tilemap map = entity.get(Tilemap.class);
            if (map != null && map.solid) solids.grids.add(new TileGrid(entity.transform.matrix(new Matrix4f()).invert(), map));
        }
        return solids;
    }

    public boolean isEmpty() {
        return boxes.isEmpty() && grids.isEmpty();
    }

    @Override
    public boolean at(double x, double y, double z) {
        for (Box b : boxes) {
            if (x >= b.minX() && x <= b.maxX() && y >= b.minY() && y <= b.maxY() && z >= b.minZ() && z <= b.maxZ()) return true;
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
