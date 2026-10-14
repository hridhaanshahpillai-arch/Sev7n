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
 * and base color texture (embedded or separate files), and for animated models the
 * skeleton, skin weights and animations (see Rig; an Animator component plays them).
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
        if (!list(json, "skins").isEmpty() || !list(json, "animations").isEmpty()) return readAnimated();
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

    // ---------------------------------------------------------------- animated models

    /** One material's vertices before posing, gathered from every primitive that uses it. */
    private static final class RawPart {
        final Floats positions = new Floats();
        final Floats normals = new Floats();
        final Floats uvs = new Floats();
        final Ints joints = new Ints();
        final Floats weights = new Floats();
        final Ints indices = new Ints();

        int vertexCount() {
            return positions.size / 3;
        }
    }

    /**
     * A model with a skeleton or animations. Every vertex follows joints: skinned meshes use
     * their own bones; other meshes follow their node like a single bone (so a sword held in
     * an animated hand moves with it). The rest pose becomes the normal mesh.
     */
    private ModelData readAnimated() throws IOException {
        List<Object> nodes = list(json, "nodes");
        int n = nodes.size();
        int[] parent = new int[n];
        Arrays.fill(parent, -1);
        Vector3f[] restT = new Vector3f[n];
        Quaternionf[] restR = new Quaternionf[n];
        Vector3f[] restS = new Vector3f[n];
        String[] names = new String[n];
        for (int i = 0; i < n; i++) {
            Map<String, Object> node = Json.object(nodes.get(i));
            names[i] = Json.string(node, "name", "node" + i);
            for (Object child : list(node, "children")) {
                int c = ((Double) child).intValue();
                if (c >= 0 && c < n) parent[c] = i;
            }
            Matrix4f local = localMatrix(node);
            restT[i] = local.getTranslation(new Vector3f());
            restR[i] = local.getUnnormalizedRotation(new Quaternionf()).normalize();
            restS[i] = local.getScale(new Vector3f());
        }

        // the joint table: every skin's bones, then one joint per plain mesh node (added as found)
        List<Integer> jointNodes = new ArrayList<>();
        List<Matrix4f> inverseBinds = new ArrayList<>();
        List<Integer> skinStart = new ArrayList<>();
        for (Object item : list(json, "skins")) {
            Map<String, Object> skin = Json.object(item);
            skinStart.add(jointNodes.size());
            List<Object> joints = list(skin, "joints");
            float[] binds = skin.containsKey("inverseBindMatrices") ? accessorFloats(integer(skin, "inverseBindMatrices")) : new float[0];
            for (int j = 0; j < joints.size(); j++) {
                jointNodes.add(((Double) joints.get(j)).intValue());
                inverseBinds.add(binds.length >= (j + 1) * 16 ? new Matrix4f().set(Arrays.copyOfRange(binds, j * 16, j * 16 + 16)) : new Matrix4f());
            }
        }
        Map<Integer, Integer> nodeJoint = new HashMap<>();

        Map<Integer, RawPart> byMaterial = new LinkedHashMap<>();
        Matrix4f[] world = new Matrix4f[n];
        for (int root : roots(nodes)) place(root, new Matrix4f(), nodes, world);
        for (int i = 0; i < n; i++) {
            Map<String, Object> node = Json.object(nodes.get(i));
            if (!node.containsKey("mesh") || world[i] == null) continue;
            int skin = node.containsKey("skin") ? integer(node, "skin") : -1;
            int rigid = -1;
            if (skin < 0 || skin >= skinStart.size()) {
                int nodeIndex = i;
                rigid = nodeJoint.computeIfAbsent(nodeIndex, k -> {
                    jointNodes.add(nodeIndex);
                    inverseBinds.add(new Matrix4f());
                    return jointNodes.size() - 1;
                });
            }
            Map<String, Object> mesh = Json.object(list(json, "meshes").get(integer(node, "mesh")));
            for (Object primitive : list(mesh, "primitives")) {
                addRawPrimitive(Json.object(primitive), skin >= 0 && rigid < 0 ? skinStart.get(skin) : -1, rigid, byMaterial);
            }
        }
        if (byMaterial.isEmpty()) throw new IOException("the model has no triangles");

        Rig rig = new Rig(names, parent, restT, restR, restS, jointNodes.stream().mapToInt(Integer::intValue).toArray(),
                inverseBinds.toArray(new Matrix4f[0]), readClips(n));
        Matrix4f[] rest = new Matrix4f[rig.jointCount()];
        for (int j = 0; j < rest.length; j++) rest[j] = new Matrix4f();
        rig.pose(null, 0, null, 0, 0, rest);

        List<ModelData.Part> parts = new ArrayList<>();
        for (Map.Entry<Integer, RawPart> entry : byMaterial.entrySet()) {
            RawPart raw = entry.getValue();
            ModelData.Skin skin = new ModelData.Skin(raw.positions.array(), raw.normals.array(), raw.joints.array(), raw.weights.array());
            MeshData.Builder builder = new MeshData.Builder();
            float[] uv = raw.uvs.array();
            for (int v = 0; v < raw.vertexCount(); v++) builder.vertex(0, 0, 0, 0, 1, 0, uv[v * 2], uv[v * 2 + 1]);
            int[] indices = raw.indices.array();
            for (int i = 0; i + 2 < indices.length; i += 3) builder.triangle(indices[i], indices[i + 1], indices[i + 2]);
            MeshData shape = builder.build(false, new Vector3f(), new Vector3f());
            Skinning.apply(skin, rest, shape.vertices()); // the rest pose
            MeshData mesh = new MeshData(shape.vertices(), shape.indices(), false, boundsMin(shape.vertices()), boundsMax(shape.vertices()));
            parts.add(new ModelData.Part(mesh, baseColor(entry.getKey()), baseTexture(entry.getKey()), skin));
        }
        return new ModelData(parts, rig);
    }

    /** A primitive's vertices before posing. 'skinOffset' >= 0 for skinned meshes (its first joint), else 'rigidJoint' moves it all. */
    private void addRawPrimitive(Map<String, Object> primitive, int skinOffset, int rigidJoint, Map<Integer, RawPart> byMaterial) {
        if (integer(primitive, "mode", TRIANGLES) != TRIANGLES) return;
        Map<String, Object> attributes = Json.child(primitive, "attributes");
        if (!attributes.containsKey("POSITION")) return;
        float[] positions = accessorFloats(integer(attributes, "POSITION"));
        int count = positions.length / 3;
        int[] indices = primitive.containsKey("indices") ? accessorInts(integer(primitive, "indices")) : sequence(count);
        float[] normals = attributes.containsKey("NORMAL") ? accessorFloats(integer(attributes, "NORMAL")) : smoothNormals(positions, indices);
        float[] uvs = attributes.containsKey("TEXCOORD_0") ? accessorFloats(integer(attributes, "TEXCOORD_0")) : new float[count * 2];
        boolean skinned = skinOffset >= 0 && attributes.containsKey("JOINTS_0") && attributes.containsKey("WEIGHTS_0");
        float[] joints = skinned ? accessorFloats(integer(attributes, "JOINTS_0")) : null;
        float[] weights = skinned ? accessorFloats(integer(attributes, "WEIGHTS_0")) : null;
        if (!skinned && rigidJoint < 0) rigidJoint = Math.max(0, skinOffset); // a skinned mesh without weights: follow its first bone

        RawPart part = byMaterial.computeIfAbsent(integer(primitive, "material", -1), m -> new RawPart());
        int first = part.vertexCount();
        for (int i = 0; i < count; i++) {
            part.positions.add(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]);
            float nx = normals[i * 3], ny = normals[i * 3 + 1], nz = normals[i * 3 + 2];
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length > 0) part.normals.add(nx / length, ny / length, nz / length);
            else part.normals.add(0, 1, 0);
            part.uvs.add(uvs[i * 2], uvs[i * 2 + 1]);
            if (skinned) {
                float total = 0;
                for (int k = 0; k < 4; k++) total += weights[i * 4 + k];
                for (int k = 0; k < 4; k++) {
                    part.joints.add(skinOffset + (int) joints[i * 4 + k]);
                    part.weights.add(total > 0 ? weights[i * 4 + k] / total : (k == 0 ? 1 : 0));
                }
            } else {
                part.joints.add(rigidJoint, rigidJoint, rigidJoint, rigidJoint);
                part.weights.add(1, 0, 0, 0);
            }
        }
        for (int index : indices) part.indices.add(first + index);
    }

    /** Every animation's channels (moving, turning and scaling nodes; morph weights are skipped). */
    private List<Rig.Clip> readClips(int nodeCount) {
        List<Rig.Clip> clips = new ArrayList<>();
        List<Object> animations = list(json, "animations");
        for (int a = 0; a < animations.size(); a++) {
            Map<String, Object> animation = Json.object(animations.get(a));
            List<Object> samplers = list(animation, "samplers");
            List<Rig.Channel> channels = new ArrayList<>();
            float duration = 0;
            for (Object item : list(animation, "channels")) {
                Map<String, Object> channel = Json.object(item);
                Map<String, Object> target = Json.child(channel, "target");
                if (!target.containsKey("node")) continue;
                int node = integer(target, "node");
                Rig.Path path = switch (Json.string(target, "path", "")) {
                    case "translation" -> Rig.Path.TRANSLATION;
                    case "rotation" -> Rig.Path.ROTATION;
                    case "scale" -> Rig.Path.SCALE;
                    default -> null;
                };
                int samplerIndex = integer(channel, "sampler");
                if (path == null || node >= nodeCount || samplerIndex >= samplers.size()) continue;
                Map<String, Object> sampler = Json.object(samplers.get(samplerIndex));
                float[] times = accessorFloats(integer(sampler, "input"));
                float[] values = accessorFloats(integer(sampler, "output"));
                if (times.length == 0) continue;
                String interpolation = Json.string(sampler, "interpolation", "LINEAR");
                int width = path == Rig.Path.ROTATION ? 4 : 3;
                if (interpolation.equals("CUBICSPLINE")) values = middleValues(values, times.length, width);
                if (values.length < times.length * width) continue;
                channels.add(new Rig.Channel(node, path, times, values, interpolation.equals("STEP")));
                duration = Math.max(duration, times[times.length - 1]);
            }
            clips.add(new Rig.Clip(Json.string(animation, "name", "Animation " + (a + 1)), duration, channels));
        }
        return clips;
    }

    /** Cubic spline keys store (in-tangent, value, out-tangent); this keeps just the values (played as straight lines). */
    private static float[] middleValues(float[] values, int keys, int width) {
        float[] out = new float[keys * width];
        for (int k = 0; k < keys && (k * 3 + 2) * width <= values.length; k++) {
            System.arraycopy(values, (k * 3 + 1) * width, out, k * width, width);
        }
        return out;
    }

    private static Vector3f boundsMin(float[] vertices) {
        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
        for (int i = 0; i < vertices.length; i += MeshData.FLOATS_PER_VERTEX) min.min(new Vector3f(vertices[i], vertices[i + 1], vertices[i + 2]));
        return min;
    }

    private static Vector3f boundsMax(float[] vertices) {
        Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);
        for (int i = 0; i < vertices.length; i += MeshData.FLOATS_PER_VERTEX) max.max(new Vector3f(vertices[i], vertices[i + 1], vertices[i + 2]));
        return max;
    }

    /** A growable list of floats. */
    private static final class Floats {
        float[] data = new float[256];
        int size;

        void add(float... values) {
            if (size + values.length > data.length) data = Arrays.copyOf(data, Math.max(data.length * 2, size + values.length));
            System.arraycopy(values, 0, data, size, values.length);
            size += values.length;
        }

        float[] array() {
            return Arrays.copyOf(data, size);
        }
    }

    /** A growable list of ints. */
    private static final class Ints {
        int[] data = new int[256];
        int size;

        void add(int... values) {
            if (size + values.length > data.length) data = Arrays.copyOf(data, Math.max(data.length * 2, size + values.length));
            System.arraycopy(values, 0, data, size, values.length);
            size += values.length;
        }

        int[] array() {
            return Arrays.copyOf(data, size);
        }
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
