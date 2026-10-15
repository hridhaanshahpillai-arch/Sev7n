package sev7n.render;

import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads Wavefront .obj models (what Blender, Maya and most tools can export).
 *
 * Supports positions, texture coords, normals and polygons of any size (split into
 * triangles). From the .mtl file it reads each material's color (Kd) and texture (map_Kd),
 * so imported models arrive looking right.
 *
 * A model with one material is one mesh, and its look goes on the Mesh Renderer (see
 * material()), so you can change it there. A model with several materials (say a gun that's
 * black, grey and white) becomes one part per material, each with its own color and picture.
 */
public final class ObjLoader {

    /** The geometry plus the look (see material()). */
    public record Model(MeshData mesh, Material material) { }

    private ObjLoader() { }

    /** The faces drawn with one material, with their own vertices. */
    private static final class Group {
        final FloatArray vertices = new FloatArray();
        final IntArray indices = new IntArray();
        final Map<String, Integer> seen = new HashMap<>();

        MeshData mesh() {
            Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
            Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);
            for (int i = 0; i < vertices.size; i += MeshData.FLOATS_PER_VERTEX) {
                Vector3f p = new Vector3f(vertices.data[i], vertices.data[i + 1], vertices.data[i + 2]);
                min.min(p);
                max.max(p);
            }
            return new MeshData(vertices.toArray(), indices.toArray(), false, min, max);
        }
    }

    /** Every face as one mesh (the whole model in one color), plus the first material. */
    public static Model load(Path file) throws IOException {
        Map<String, Group> groups = read(file, false);
        return new Model(groups.values().iterator().next().mesh(), material(file));
    }

    /**
     * The model ready to draw: one white part if it has a single material (the Mesh Renderer
     * gives it its look), else one part per material in that material's color and picture.
     */
    public static ModelData loadParts(Path file) throws IOException {
        Map<String, Group> groups = read(file, true);
        if (groups.size() == 1) return new ModelData(List.of(new ModelData.Part(groups.values().iterator().next().mesh(), Color.WHITE, null)));
        Map<String, MaterialText> materials = readMaterials(file);
        Map<String, BufferedImage> pictures = new HashMap<>();
        List<ModelData.Part> parts = new ArrayList<>();
        for (Map.Entry<String, Group> group : groups.entrySet()) {
            MaterialText look = materials.get(group.getKey());
            Color color = look != null && look.color() != null ? look.color() : Color.WHITE;
            BufferedImage picture = null;
            if (look != null && look.texture() != null) {
                picture = pictures.computeIfAbsent(look.texture(), name -> readPicture(file, name));
                if (picture != null) color = Color.WHITE; // let the picture show its real colors
            }
            parts.add(new ModelData.Part(group.getValue().mesh(), color, picture));
        }
        return new ModelData(parts);
    }

    /** True if the model uses more than one material (so its parts keep their own colors). */
    public static boolean hasSeveralMaterials(Path file) throws IOException {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.startsWith("usemtl ") && names.add(line.substring(7).strip()) && names.size() > 1) return true;
        }
        return false;
    }

    /** The faces, grouped by material name (in the order they first appear) or all in one group. */
    private static Map<String, Group> read(Path file, boolean byMaterial) throws IOException {
        FloatArray positions = new FloatArray();
        FloatArray uvs = new FloatArray();
        FloatArray normals = new FloatArray();
        Map<String, Group> groups = new LinkedHashMap<>();
        String material = "";

        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+");
            switch (parts[0]) {
                case "v" -> positions.add(num(parts, 1), num(parts, 2), num(parts, 3));
                case "vt" -> uvs.add(num(parts, 1), 1 - num(parts, 2)); // .obj puts v = 0 at the bottom
                case "vn" -> normals.add(num(parts, 1), num(parts, 2), num(parts, 3));
                case "usemtl" -> { if (byMaterial) material = parts.length > 1 ? parts[1] : ""; }
                case "f" -> addFace(parts, positions, uvs, normals, groups.computeIfAbsent(material, name -> new Group()));
                default -> { } // groups, smoothing and lines aren't needed
            }
        }
        groups.values().removeIf(group -> group.indices.size == 0);
        if (groups.isEmpty()) throw new IOException("The model has no faces");
        return groups;
    }

    private static BufferedImage readPicture(Path obj, String name) {
        Path file = obj.resolveSibling(name).normalize();
        if (!Files.isRegularFile(file)) file = obj.resolveSibling(Path.of(name).getFileName());
        try {
            return Files.isRegularFile(file) ? ImageIO.read(file.toFile()) : null;
        } catch (IOException e) {
            return null; // drawn in its color instead
        }
    }

    /** Adds one polygon, split into a fan of triangles. */
    private static void addFace(String[] parts, FloatArray positions, FloatArray uvs, FloatArray normals, Group group) {
        FloatArray vertices = group.vertices;
        IntArray indices = group.indices;
        Map<String, Integer> seen = group.seen;
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
        Map<String, MaterialText> all = readMaterialFile(mtl);
        if (all.isEmpty()) return null;
        return wanted != null && all.containsKey(wanted) ? all.get(wanted) : wanted == null ? all.values().iterator().next() : null;
    }

    /** Every material of a model's .mtl file, by name (empty if it has none). */
    private static Map<String, MaterialText> readMaterials(Path obj) throws IOException {
        for (String raw : Files.readAllLines(obj, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.startsWith("mtllib ")) return readMaterialFile(obj.resolveSibling(line.substring(7).strip()));
        }
        return Map.of();
    }

    private static Map<String, MaterialText> readMaterialFile(Path mtl) {
        Map<String, MaterialText> materials = new LinkedHashMap<>();
        if (!Files.exists(mtl)) return materials;
        String current = null;
        String texture = null;
        Color color = null;
        try {
            for (String raw : Files.readAllLines(mtl, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                String[] parts = line.split("\\s+");
                if (parts[0].equals("newmtl")) {
                    if (current != null) materials.put(current, new MaterialText(texture, color));
                    current = parts.length > 1 ? parts[1] : "";
                    texture = null;
                    color = null;
                } else if (current != null && parts[0].equals("Kd") && parts.length >= 4) {
                    color = new Color(clamp(parts[1]), clamp(parts[2]), clamp(parts[3]));
                } else if (current != null && parts[0].equalsIgnoreCase("map_Kd") && parts.length >= 2) {
                    texture = parts[parts.length - 1].replace('\\', '/'); // the file name is last, after any options
                }
            }
        } catch (IOException e) {
            return materials;
        }
        if (current != null) materials.put(current, new MaterialText(texture, color));
        return materials;
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
