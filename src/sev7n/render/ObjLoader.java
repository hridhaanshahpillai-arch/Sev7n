package sev7n.render;

import org.joml.Vector3f;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads Wavefront .obj models (what Blender, Maya and most tools can export).
 *
 * Supports positions, texture coords, normals and polygons of any size (split into
 * triangles). From the .mtl file it reads the first material's color (Kd) and
 * texture (map_Kd), so imported models arrive looking right.
 */
public final class ObjLoader {

    /** The geometry plus the look (see material()). */
    public record Model(MeshData mesh, Material material) { }

    private ObjLoader() { }

    public static Model load(Path file) throws IOException {
        FloatArray positions = new FloatArray();
        FloatArray uvs = new FloatArray();
        FloatArray normals = new FloatArray();
        FloatArray vertices = new FloatArray();
        IntArray indices = new IntArray();
        Map<String, Integer> seen = new HashMap<>();

        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+");
            switch (parts[0]) {
                case "v" -> positions.add(num(parts, 1), num(parts, 2), num(parts, 3));
                case "vt" -> uvs.add(num(parts, 1), 1 - num(parts, 2)); // .obj puts v = 0 at the bottom
                case "vn" -> normals.add(num(parts, 1), num(parts, 2), num(parts, 3));
                case "f" -> addFace(parts, positions, uvs, normals, vertices, indices, seen);
                default -> { } // materials are read separately; groups, smoothing, lines aren't needed
            }
        }
        if (indices.size == 0) throw new IOException("The model has no faces");

        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);
        for (int i = 0; i < vertices.size; i += MeshData.FLOATS_PER_VERTEX) {
            Vector3f p = new Vector3f(vertices.data[i], vertices.data[i + 1], vertices.data[i + 2]);
            min.min(p);
            max.max(p);
        }
        MeshData mesh = new MeshData(vertices.toArray(), indices.toArray(), false, min, max);
        return new Model(mesh, material(file));
    }

    /** Adds one polygon, split into a fan of triangles. */
    private static void addFace(String[] parts, FloatArray positions, FloatArray uvs, FloatArray normals,
                                FloatArray vertices, IntArray indices, Map<String, Integer> seen) {
        int corners = parts.length - 1;
        if (corners < 3) return;
        int[] face = new int[corners];
        boolean missingNormal = false;
        int[][] refs = new int[corners][];
        for (int c = 0; c < corners; c++) {
            refs[c] = parseCorner(parts[c + 1], positions.size / 3, uvs.size / 2, normals.size / 3);
            if (refs[c][2] < 0) missingNormal = true;
        }

        float[] flatNormal = missingNormal ? faceNormal(refs, positions) : null;
        for (int c = 0; c < corners; c++) {
            int[] r = refs[c];
            String key = r[0] + "/" + r[1] + "/" + r[2];
            Integer existing = missingNormal ? null : seen.get(key); // flat-shaded corners can't be shared
            if (existing != null) {
                face[c] = existing;
                continue;
            }
            int index = vertices.size / MeshData.FLOATS_PER_VERTEX;
            vertices.add(positions.data[r[0] * 3], positions.data[r[0] * 3 + 1], positions.data[r[0] * 3 + 2]);
            if (r[2] >= 0) vertices.add(normals.data[r[2] * 3], normals.data[r[2] * 3 + 1], normals.data[r[2] * 3 + 2]);
            else vertices.add(flatNormal[0], flatNormal[1], flatNormal[2]);
            if (r[1] >= 0) vertices.add(uvs.data[r[1] * 2], uvs.data[r[1] * 2 + 1]);
            else vertices.add(0, 0);
            if (!missingNormal) seen.put(key, index);
            face[c] = index;
        }
        for (int c = 1; c + 1 < corners; c++) indices.add(face[0], face[c], face[c + 1]);
    }

    /** "7/3/5", "7//5", "7/3" or "7" -> 0-based {position, uv or -1, normal or -1}. Negative numbers count from the end. */
    private static int[] parseCorner(String token, int positionCount, int uvCount, int normalCount) {
        String[] bits = token.split("/", -1);
        return new int[] {
                resolve(bits[0], positionCount),
                bits.length > 1 && !bits[1].isEmpty() ? resolve(bits[1], uvCount) : -1,
                bits.length > 2 && !bits[2].isEmpty() ? resolve(bits[2], normalCount) : -1
        };
    }

    private static int resolve(String number, int count) {
        int i = Integer.parseInt(number);
        return i > 0 ? i - 1 : count + i;
    }

    private static float[] faceNormal(int[][] refs, FloatArray p) {
        Vector3f a = point(p, refs[0][0]);
        Vector3f b = point(p, refs[1][0]);
        Vector3f c = point(p, refs[2][0]);
        Vector3f n = b.sub(a, new Vector3f()).cross(c.sub(a, new Vector3f())).normalize();
        if (!n.isFinite()) n.set(0, 1, 0);
        return new float[] {n.x, n.y, n.z};
    }

    private static Vector3f point(FloatArray p, int index) {
        return new Vector3f(p.data[index * 3], p.data[index * 3 + 1], p.data[index * 3 + 2]);
    }

    private static float num(String[] parts, int index) {
        return index < parts.length ? Float.parseFloat(parts[index]) : 0;
    }

    // ---------------------------------------------------------------- .mtl

    /** A model's look: its color texture file (or null) and diffuse color (or null). */
    public record Material(Path texture, Color color) { }

    /**
     * Reads just the material of a model, without loading its geometry. If the texture path
     * written in the .mtl doesn't exist (common after moving files), looks for the file name
     * next to the model instead.
     */
    public static Material material(Path obj) throws IOException {
        String library = null;
        String first = null;
        for (String raw : Files.readAllLines(obj, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (library == null && line.startsWith("mtllib ")) library = line.substring(7).strip();
            if (first == null && line.startsWith("usemtl ")) first = line.substring(7).strip();
            if (library != null && first != null) break;
        }
        if (library == null) return new Material(null, null);
        Path mtl = obj.resolveSibling(library);
        MaterialText found = readMaterial(mtl, first);
        if (found == null) return new Material(null, null);
        Path texture = null;
        if (found.texture() != null) {
            texture = mtl.getParent().resolve(found.texture()).normalize();
            if (!Files.isRegularFile(texture)) texture = obj.resolveSibling(Path.of(found.texture()).getFileName());
            if (!Files.isRegularFile(texture)) texture = null;
        }
        return new Material(texture, found.color());
    }

    private record MaterialText(String texture, Color color) { }

    /** Reads the named material (or the first one) from a .mtl file. */
    private static MaterialText readMaterial(Path mtl, String wanted) {
        if (!Files.exists(mtl)) return null;
        String current = null;
        String texture = null;
        Color color = null;
        boolean found = false;
        try {
            for (String raw : Files.readAllLines(mtl, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                String[] parts = line.split("\\s+");
                if (parts[0].equals("newmtl")) {
                    if (found) break; // finished the material we wanted
                    current = parts.length > 1 ? parts[1] : "";
                    found = wanted == null || current.equals(wanted);
                } else if (found && parts[0].equals("Kd") && parts.length >= 4) {
                    color = new Color(clamp(parts[1]), clamp(parts[2]), clamp(parts[3]));
                } else if (found && parts[0].equalsIgnoreCase("map_Kd") && parts.length >= 2) {
                    texture = parts[parts.length - 1].replace('\\', '/'); // the file name is last, after any options
                }
            }
        } catch (IOException e) {
            return null;
        }
        return found || texture != null ? new MaterialText(texture, color) : null;
    }

    private static float clamp(String value) {
        return Math.max(0, Math.min(1, Float.parseFloat(value)));
    }

    // ---------------------------------------------------------------- growable primitive arrays

    private static final class FloatArray {
        float[] data = new float[1024];
        int size;

        void add(float... values) {
            if (size + values.length > data.length) data = Arrays.copyOf(data, Math.max(data.length * 2, size + values.length));
            System.arraycopy(values, 0, data, size, values.length);
            size += values.length;
        }

        float[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }

    private static final class IntArray {
        int[] data = new int[1024];
        int size;

        void add(int... values) {
            if (size + values.length > data.length) data = Arrays.copyOf(data, Math.max(data.length * 2, size + values.length));
            System.arraycopy(values, 0, data, size, values.length);
            size += values.length;
        }

        int[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }
}
