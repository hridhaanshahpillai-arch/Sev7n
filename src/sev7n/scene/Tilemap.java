package sev7n.scene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A grid of tiles painted from a tileset picture: floors, walls and platforms for 2D games.
 *
 * The tileset is cut into a grid (columns x rows); tile numbers count left to right,
 * then top to bottom, from 0. Cells are in the entity's own X/Y plane: cell (0, 0)
 * covers 0..tileSize on X and Y, and +Y is up.
 */
public final class Tilemap extends Component {

    public static final int EMPTY = -1;

    public String tileset = "";
    public int columns = 4;
    public int rows = 4;
    public double tileSize = 1;
    /** Keep pixels sharp: right for pixel-art tilesets. */
    public boolean pixelArt = true;
    /** Rigidbodies can't pass through the tiles (walls and floors). */
    public boolean solid = true;

    private final Map<Long, Integer> cells = new HashMap<>();
    private int version = 0;

    /** The tile number at a cell, or EMPTY. */
    public int tile(int x, int y) {
        return cells.getOrDefault(key(x, y), EMPTY);
    }

    /** Puts a tile in a cell; EMPTY (or any negative number) clears it. */
    public void setTile(int x, int y, int tile) {
        Integer old = tile < 0 ? cells.remove(key(x, y)) : cells.put(key(x, y), tile);
        if (old == null ? tile >= 0 : old != tile) version++;
    }

    public void clear() {
        if (!cells.isEmpty()) version++;
        cells.clear();
    }

    /** Every painted cell as {x, y, tile}. */
    public List<int[]> cells() {
        List<int[]> list = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : cells.entrySet()) {
            list.add(new int[] {cellX(entry.getKey()), cellY(entry.getKey()), entry.getValue()});
        }
        return Collections.unmodifiableList(list);
    }

    public boolean isEmpty() {
        return cells.isEmpty();
    }

    /** Changes whenever a tile changes, so the renderer knows to rebuild its mesh. */
    public int version() {
        return version;
    }

    private static long key(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    private static int cellX(long key) {
        return (int) (key >> 32);
    }

    private static int cellY(long key) {
        return (int) key;
    }

    @Override
    public String type() {
        return "Tilemap";
    }

    @Override
    public String displayName() {
        return "Tilemap";
    }

    @Override
    public void save(Map<String, Object> out) {
        out.put("tileset", tileset);
        out.put("columns", columns);
        out.put("rows", rows);
        out.put("tileSize", tileSize);
        out.put("pixelArt", pixelArt);
        out.put("solid", solid);
        // x, y, tile, x, y, tile... on one line keeps scene files short
        List<Object> flat = new ArrayList<>();
        cells().stream()
                .sorted((a, b) -> a[1] != b[1] ? Integer.compare(b[1], a[1]) : Integer.compare(a[0], b[0]))
                .forEach(cell -> {
                    flat.add(cell[0]);
                    flat.add(cell[1]);
                    flat.add(cell[2]);
                });
        out.put("cells", flat);
    }

    @Override
    public void load(Map<String, Object> in) {
        tileset = Json.string(in, "tileset", tileset);
        columns = Math.max(1, (int) Json.number(in, "columns", columns));
        rows = Math.max(1, (int) Json.number(in, "rows", rows));
        tileSize = Json.number(in, "tileSize", tileSize);
        pixelArt = Json.bool(in, "pixelArt", pixelArt);
        solid = Json.bool(in, "solid", solid);
        clear();
        List<?> flat = Json.list(in, "cells");
        for (int i = 0; i + 2 < flat.size(); i += 3) {
            if (flat.get(i) instanceof Double x && flat.get(i + 1) instanceof Double y && flat.get(i + 2) instanceof Double t) {
                setTile(x.intValue(), y.intValue(), t.intValue());
            }
        }
    }
}
