package sev7n.render;

import org.joml.Vector3f;
import sev7n.scene.Tilemap;

/** Turns a tilemap's painted cells into one mesh of textured squares (in the entity's X/Y plane). */
final class TilemapMesh {

    private TilemapMesh() { }

    /**
     * @param textureWidth  the tileset's size in pixels, used to keep tiles from
     * @param textureHeight picking up a sliver of their neighbors at the edges
     */
    static MeshData build(Tilemap map, int textureWidth, int textureHeight) {
        MeshData.Builder b = new MeshData.Builder();
        float s = (float) map.tileSize;
        float insetU = (map.pixelArt ? 0.01f : 0.5f) / textureWidth;
        float insetV = (map.pixelArt ? 0.01f : 0.5f) / textureHeight;
        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);

        for (int[] cell : map.cells()) {
            int tile = Math.min(cell[2], map.columns * map.rows - 1);
            int column = tile % map.columns;
            int row = tile / map.columns;
            float u0 = (float) column / map.columns + insetU;
            float u1 = (float) (column + 1) / map.columns - insetU;
            float v0 = (float) row / map.rows + insetV;        // top of the tile (v = 0 is the picture's top)
            float v1 = (float) (row + 1) / map.rows - insetV;
            float x0 = cell[0] * s;
            float y0 = cell[1] * s;

            int first = b.vertex(x0, y0, 0, 0, 0, 1, u0, v1);
            b.vertex(x0 + s, y0, 0, 0, 0, 1, u1, v1);
            b.vertex(x0 + s, y0 + s, 0, 0, 0, 1, u1, v0);
            b.vertex(x0, y0 + s, 0, 0, 0, 1, u0, v0);
            b.triangle(first, first + 1, first + 2);
            b.triangle(first, first + 2, first + 3);
            min.min(new Vector3f(x0, y0, -0.01f));
            max.max(new Vector3f(x0 + s, y0 + s, 0.01f));
        }
        if (b.isEmpty()) {
            min.set(0);
            max.set(0);
        }
        return b.build(false, min, max);
    }
}
