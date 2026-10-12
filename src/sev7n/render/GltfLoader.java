package sev7n.render;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import sev7n.scene.Json;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads glTF models: .glb (one binary file) and .gltf (JSON plus .bin and picture files).
 * glTF is the open standard for 3D models; Blender, Sketchfab, Mixamo and most tools export it.
 *
 * Reads meshes, the node tree (positions, rotations, scales), each material's base color
 * and base color texture (embedded or separate files). Rigged characters are shown in
 * their rest pose; animations aren't played yet.
 */
public final class GltfLoader {

    private static final int GLB_MAGIC = 0x46546C67;   // "glTF"
    private static final int CHUNK_JSON = 0x4E4F534A;
    private static final int CHUNK_BIN = 0x004E4942;
    private static final int TRIANGLES = 4;

    private final Path file;
    private final Map<String, Object> json;
    private final List<ByteBuffer> buffers = new ArrayList<>();
    private final Map<Integer, BufferedImage> images = new HashMap<>();

    private GltfLoader(Path file, Map<String, Object> json, ByteBuffer glbBinary) throws IOException {
        this.file = file;
        this.json = json;
        for (Object item : list(json, "buffers")) {
            Map<String, Object> buffer = Json.object(item);
            String uri = Json.string(buffer, "uri", null);
            if (uri == null) {
                if (glbBinary == null) throw new IOException("a buffer has no data");
                buffers.add(glbBinary);
            } else {
                buffers.add(ByteBuffer.wrap(readUri(uri)));
            }
        }
    }

    public static ModelData load(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        Map<String, Object> json;
        ByteBuffer binary = null;
        if (bytes.length >= 12 && data.getInt(0) == GLB_MAGIC) {
            // .glb: a header, then a JSON chunk and usually a binary chunk
            json = null;
            int at = 12;
            while (at + 8 <= bytes.length) {
                int length = data.getInt(at);
                int type = data.getInt(at + 4);
                if (type == CHUNK_JSON) {
                    json = Json.object(Json.parse(new String(bytes, at + 8, length, StandardCharsets.UTF_8)));
                } else if (type == CHUNK_BIN) {
                    binary = ByteBuffer.wrap(bytes, at + 8, length).slice();
                }
                at += 8 + length;
            }
            if (json == null) throw new IOException("not a valid .glb file (no JSON part)");
        } else {
            json = Json.object(Json.parse(new String(bytes, StandardCharsets.UTF_8)));
        }
        for (Object extension : list(json, "extensionsRequired")) {
            if (String.valueOf(extension).contains("draco") || String.valueOf(extension).contains("meshopt")) {
                throw new IOException("the model is compressed (" + extension + "). Export it again without compression");
            }
        }
        return new GltfLoader(file, json, binary).read();
    }

    // ---------------------------------------------------------------- the node tree

    private ModelData read() throws IOException {
        List<Object> nodes = list(json, "nodes");
        Matrix4f[] world = new Matrix4f[nodes.size()];
        for (int root : roots(nodes)) place(root, new Matrix4f(), nodes, world);

        // one part per material, so a model with 3 materials is 3 draw calls however many meshes it has
        Map<Integer, MeshData.Builder> byMaterial = new LinkedHashMap<>();
        Map<Integer, float[]> bounds = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            Map<String, Object> node = Json.object(nodes.get(i));
            if (!node.containsKey("mesh") || world[i] == null) continue;
            Matrix4f transform = skinTransform(node, world).orElse(world[i]);
            Map<String, Object> mesh = Json.object(list(json, "meshes").get(integer(node, "mesh")));
            for (Object primitive : list(mesh, "primitives")) {
                addPrimitive(Json.object(primitive), transform, byMaterial, bounds);
            }
        }
        if (byMaterial.isEmpty()) throw new IOException("the model has no triangles");

        List<ModelData.Part> parts = new ArrayList<>();
        for (Map.Entry<Integer, MeshData.Builder> entry : byMaterial.entrySet()) {
            float[] b = bounds.get(entry.getKey());
            MeshData mesh = entry.getValue().build(false, new Vector3f(b[0], b[1], b[2]), new Vector3f(b[3], b[4], b[5]));
            parts.add(new ModelData.Part(mesh, baseColor(entry.getKey()), baseTexture(entry.getKey())));
        }
        return new ModelData(parts);
    }

    /** The scene's top nodes (or every node nobody lists as a child). */
    private List<Integer> roots(List<Object> nodes) {
        List<Object> scenes = list(json, "scenes");
        if (!scenes.isEmpty()) {
            Map<String, Object> scene = Json.object(scenes.get(Math.min(integer(json, "scene", 0), scenes.size() - 1)));
            return list(scene, "nodes").stream().map(n -> ((Double) n).intValue()).toList();
        }
        boolean[] isChild = new boolean[nodes.size()];
        for (Object node : nodes) {
            for (Object child : list(Json.object(node), "children")) isChild[((Double) child).intValue()] = true;
        }
        List<Integer> roots = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) if (!isChild[i]) roots.add(i);
        return roots;
    }

    private void place(int index, Matrix4f parent, List<Object> nodes, Matrix4f[] world) {
        if (index < 0 || index >= nodes.size() || world[index] != null) return;
        Map<String, Object> node = Json.object(nodes.get(index));
        world[index] = new Matrix4f(parent).mul(localMatrix(node));
        for (Object child : list(node, "children")) place(((Double) child).intValue(), world[index], nodes, world);
    }

    private static Matrix4f localMatrix(Map<String, Object> node) {
        float[] m = floats(node, "matrix");
        if (m.length == 16) return new Matrix4f().set(m); // column-major, like OpenGL
        float[] t = floats(node, "translation");
        float[] r = floats(node, "rotation");
        float[] s = floats(node, "scale");
        return new Matrix4f().translationRotateScale(
                t.length == 3 ? new Vector3f(t[0], t[1], t[2]) : new Vector3f(),
                r.length == 4 ? new Quaternionf(r[0], r[1], r[2], r[3]) : new Quaternionf(),
                s.length == 3 ? new Vector3f(s[0], s[1], s[2]) : new Vector3f(1));
    }

    /**
     * A rigged mesh's vertices are placed by its skeleton, not its own node. In the rest
     * pose every bone gives the same answer, so the first bone tells us where the mesh goes.
     */
    private Optional<Matrix4f> skinTransform(Map<String, Object> node, Matrix4f[] world) {
        if (!node.containsKey("skin")) return Optional.empty();
        Map<String, Object> skin = Json.object(list(json, "skins").get(integer(node, "skin")));
        List<Object> joints = list(skin, "joints");
        if (joints.isEmpty() || !skin.containsKey("inverseBindMatrices")) return Optional.empty();
        int joint = ((Double) joints.get(0)).intValue();
        if (joint >= world.length || world[joint] == null) return Optional.empty();
        float[] inverseBind = accessorFloats(integer(skin, "inverseBindMatrices"));
        Matrix4f bind = new Matrix4f().set(Arrays.copyOf(inverseBind, 16));
        return Optional.of(new Matrix4f(world[joint]).mul(bind));
    }

    // ---------------------------------------------------------------- geometry

    private void addPrimitive(Map<String, Object> primitive, Matrix4f transform,
                              Map<Integer, MeshData.Builder> byMaterial, Map<Integer, float[]> bounds) throws IOException {
        if (integer(primitive, "mode", TRIANGLES) != TRIANGLES) return; // lines and points aren't drawn
        Map<String, Object> attributes = Json.child(primitive, "attributes");
        if (!attributes.containsKey("POSITION")) return;
        float[] positions = accessorFloats(integer(attributes, "POSITION"));
        int count = positions.length / 3;
        float[] normals = attributes.containsKey("NORMAL") ? accessorFloats(integer(attributes, "NORMAL")) : null;
        float[] uvs = attributes.containsKey("TEXCOORD_0") ? accessorFloats(integer(attributes, "TEXCOORD_0")) : null;
        int[] indices = primitive.containsKey("indices") ? accessorInts(integer(primitive, "indices")) : sequence(count);
        if (normals == null) normals = smoothNormals(positions, indices);

        int material = integer(primitive, "material", -1);
        MeshData.Builder builder = byMaterial.computeIfAbsent(material, m -> new MeshData.Builder());
        float[] box = bounds.computeIfAbsent(material, m -> new float[] {
                Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY});
        Matrix3f normalMatrix = transform.normal(new Matrix3f());
        Vector3f p = new Vector3f();
        Vector3f n = new Vector3f();

        int first = -1;
        for (int i = 0; i < count; i++) {
            transform.transformPosition(p.set(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]));
            normalMatrix.transform(n.set(normals[i * 3], normals[i * 3 + 1], normals[i * 3 + 2]));
            if (n.lengthSquared() > 0) n.normalize();
            float u = uvs != null ? uvs[i * 2] : 0;
            float v = uvs != null ? uvs[i * 2 + 1] : 0; // glTF puts v = 0 at the top, like Sev7n
            int index = builder.vertex(p.x, p.y, p.z, n.x, n.y, n.z, u, v);
            if (first < 0) first = index;
            box[0] = Math.min(box[0], p.x);
            box[1] = Math.min(box[1], p.y);
            box[2] = Math.min(box[2], p.z);
            box[3] = Math.max(box[3], p.x);
            box[4] = Math.max(box[4], p.y);
            box[5] = Math.max(box[5], p.z);
        }
        for (int i = 0; i + 2 < indices.length; i += 3) {
            builder.triangle(first + indices[i], first + indices[i + 1], first + indices[i + 2]);
        }
    }

    /** Normals for models that don't include them: each corner averages its triangles' directions. */
    private static float[] smoothNormals(float[] positions, int[] indices) {
        float[] normals = new float[positions.length];
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        Vector3f c = new Vector3f();
        for (int i = 0; i + 2 < indices.length; i += 3) {
            int ia = indices[i], ib = indices[i + 1], ic = indices[i + 2];
            a.set(positions[ia * 3], positions[ia * 3 + 1], positions[ia * 3 + 2]);
            b.set(positions[ib * 3], positions[ib * 3 + 1], positions[ib * 3 + 2]);
            c.set(positions[ic * 3], positions[ic * 3 + 1], positions[ic * 3 + 2]);
            Vector3f face = b.sub(a).cross(c.sub(a));
            for (int corner : new int[] {ia, ib, ic}) {
                normals[corner * 3] += face.x;
                normals[corner * 3 + 1] += face.y;
                normals[corner * 3 + 2] += face.z;
            }
        }
        return normals;
    }

    private static int[] sequence(int count) {
        int[] indices = new int[count];
        for (int i = 0; i < count; i++) indices[i] = i;
        return indices;
    }

    // ---------------------------------------------------------------- materials

    private Color baseColor(int material) {
        float[] factor = floats(pbr(material), "baseColorFactor");
        if (factor.length < 3) return Color.WHITE;
        // glTF colors are linear; the renderer works in screen (sRGB) colors
        return new Color(toScreen(factor[0]), toScreen(factor[1]), toScreen(factor[2]));
    }

    private BufferedImage baseTexture(int material) throws IOException {
        Map<String, Object> texture = Json.child(pbr(material), "baseColorTexture");
        if (!texture.containsKey("index")) return null;
        List<Object> textures = list(json, "textures");
        int textureIndex = integer(texture, "index");
        if (textureIndex >= textures.size()) return null;
        Map<String, Object> info = Json.object(textures.get(textureIndex));
        if (!info.containsKey("source")) return null;
        int image = integer(info, "source");
        if (!images.containsKey(image)) images.put(image, readImage(image));
        return images.get(image);
    }

    private Map<String, Object> pbr(int material) {
        List<Object> materials = list(json, "materials");
        if (material < 0 || material >= materials.size()) return Map.of();
        return Json.child(Json.object(materials.get(material)), "pbrMetallicRoughness");
    }

    /** A picture from a file next to the model, a data: URI, or bytes inside the .glb. Null if Java can't read it (e.g. WebP). */
    private BufferedImage readImage(int index) throws IOException {
        Map<String, Object> image = Json.object(list(json, "images").get(index));
        byte[] bytes;
        if (image.containsKey("bufferView")) {
            ByteBuffer view = bufferView(integer(image, "bufferView"));
            bytes = new byte[view.remaining()];
            view.get(bytes);
        } else {
            bytes = readUri(Json.string(image, "uri", ""));
        }
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private static float toScreen(float linear) {
        return (float) Math.pow(Math.max(0, Math.min(1, linear)), 1 / 2.2);
    }

    // ---------------------------------------------------------------- accessors (typed views of the binary data)

    private float[] accessorFloats(int index) {
        Map<String, Object> accessor = Json.object(list(json, "accessors").get(index));
        int count = integer(accessor, "count");
        int components = components(Json.string(accessor, "type", "SCALAR"));
        float[] out = new float[count * components];
        if (!accessor.containsKey("bufferView")) return out; // all zeros
        int type = integer(accessor, "componentType");
        boolean normalized = Json.bool(accessor, "normalized", false);
        Map<String, Object> view = Json.object(list(json, "bufferViews").get(integer(accessor, "bufferView")));
        ByteBuffer data = buffer(view);
        int size = componentSize(type);
        int stride = integer(view, "byteStride", components * size);
        int base = integer(accessor, "byteOffset", 0);
        for (int i = 0; i < count; i++) {
            for (int c = 0; c < components; c++) {
                out[i * components + c] = component(data, base + i * stride + c * size, type, normalized);
            }
        }
        return out;
    }

    private int[] accessorInts(int index) {
        Map<String, Object> accessor = Json.object(list(json, "accessors").get(index));
        int count = integer(accessor, "count");
        int type = integer(accessor, "componentType");
        Map<String, Object> view = Json.object(list(json, "bufferViews").get(integer(accessor, "bufferView")));
        ByteBuffer data = buffer(view);
        int size = componentSize(type);
        int stride = integer(view, "byteStride", size);
        int base = integer(accessor, "byteOffset", 0);
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            int at = base + i * stride;
            out[i] = switch (type) {
                case 5121 -> data.get(at) & 0xFF;
                case 5123 -> data.getShort(at) & 0xFFFF;
                default -> data.getInt(at);
            };
        }
        return out;
    }

    private static float component(ByteBuffer data, int at, int type, boolean normalized) {
        return switch (type) {
            case 5126 -> data.getFloat(at);
            case 5121 -> normalized ? (data.get(at) & 0xFF) / 255f : data.get(at) & 0xFF;
            case 5120 -> normalized ? Math.max(data.get(at) / 127f, -1) : data.get(at);
            case 5123 -> normalized ? (data.getShort(at) & 0xFFFF) / 65535f : data.getShort(at) & 0xFFFF;
            case 5122 -> normalized ? Math.max(data.getShort(at) / 32767f, -1) : data.getShort(at);
            default -> data.getInt(at) & 0xFFFFFFFFL;
        };
    }

    /** The bytes of a buffer view, starting at position 0 of the returned buffer. */
    private ByteBuffer buffer(Map<String, Object> view) {
        ByteBuffer whole = buffers.get(integer(view, "buffer")).duplicate().order(ByteOrder.LITTLE_ENDIAN);
        whole.position(integer(view, "byteOffset", 0));
        return whole.slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    private ByteBuffer bufferView(int index) {
        Map<String, Object> view = Json.object(list(json, "bufferViews").get(index));
        ByteBuffer data = buffer(view);
        data.limit(integer(view, "byteLength"));
        return data;
    }

    private byte[] readUri(String uri) throws IOException {
        if (uri.startsWith("data:")) {
            int comma = uri.indexOf(',');
            return Base64.getDecoder().decode(uri.substring(comma + 1));
        }
        Path target = file.resolveSibling(URLDecoder.decode(uri, StandardCharsets.UTF_8));
        if (!Files.isRegularFile(target)) throw new IOException("missing " + uri + " (keep it next to " + file.getFileName() + ")");
        return Files.readAllBytes(target);
    }

    private static int components(String type) {
        return switch (type) {
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            case "VEC4", "MAT2" -> 4;
            case "MAT3" -> 9;
            case "MAT4" -> 16;
            default -> 1;
        };
    }

    private static int componentSize(int type) {
        return switch (type) {
            case 5120, 5121 -> 1;
            case 5122, 5123 -> 2;
            default -> 4;
        };
    }

    // ---------------------------------------------------------------- JSON helpers

    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> obj, String key) {
        return (List<Object>) Json.list(obj, key);
    }

    private static int integer(Map<String, Object> obj, String key) {
        return (int) Json.number(obj, key, 0);
    }

    private static int integer(Map<String, Object> obj, String key, int fallback) {
        return (int) Json.number(obj, key, fallback);
    }

    private static float[] floats(Map<String, Object> obj, String key) {
        List<?> values = Json.list(obj, key);
        float[] out = new float[values.size()];
        for (int i = 0; i < out.length; i++) out[i] = values.get(i) instanceof Double d ? d.floatValue() : 0;
        return out;
    }
}
