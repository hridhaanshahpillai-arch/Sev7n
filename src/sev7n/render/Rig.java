package sev7n.render;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * A model's skeleton and animations: everything needed to pose it at any moment.
 *
 *   nodes    the model's node tree (bones, and the parts hanging off them), each with a rest
 *            position, rotation and scale relative to its parent
 *   joints   what vertices follow: a node plus its "inverse bind" matrix (where the bone
 *            was when the mesh was attached to it)
 *   clips    animations: keyframes that move, turn and scale nodes over time
 *
 * pose() works out where every joint is at some point in a clip (optionally blending two
 * clips, for smooth changes from walking to running); Skinning then moves the vertices.
 */
public final class Rig {

    /** Which property a channel animates. */
    enum Path { TRANSLATION, ROTATION, SCALE }

    /** Keyframes for one property of one node. 'values' has 3 (or 4 for rotations) numbers per key. */
    record Channel(int node, Path path, float[] times, float[] values, boolean step) { }

    /** One animation, e.g. "Walk". */
    public record Clip(String name, float duration, List<Channel> channels) { }

    final String[] names;
    final int[] parent;
    final int[] order; // every node, parents before children
    final Vector3f[] restTranslation;
    final Quaternionf[] restRotation;
    final Vector3f[] restScale;
    final int[] jointNodes;
    final Matrix4f[] inverseBinds;
    public final List<Clip> clips;

    // working space for pose(), reused every frame
    private final Vector3f[] translation;
    private final Quaternionf[] rotation;
    private final Vector3f[] scale;
    private final Vector3f[] translationB;
    private final Quaternionf[] rotationB;
    private final Vector3f[] scaleB;
    private final Matrix4f[] global;
    private final Matrix4f local = new Matrix4f();

    Rig(String[] names, int[] parent, Vector3f[] restTranslation, Quaternionf[] restRotation, Vector3f[] restScale,
        int[] jointNodes, Matrix4f[] inverseBinds, List<Clip> clips) {
        int n = parent.length;
        this.names = names;
        this.parent = parent;
        this.restTranslation = restTranslation;
        this.restRotation = restRotation;
        this.restScale = restScale;
        this.jointNodes = jointNodes;
        this.inverseBinds = inverseBinds;
        this.clips = List.copyOf(clips);
        this.order = parentsFirst(parent);
        translation = new Vector3f[n];
        rotation = new Quaternionf[n];
        scale = new Vector3f[n];
        translationB = new Vector3f[n];
        rotationB = new Quaternionf[n];
        scaleB = new Vector3f[n];
        global = new Matrix4f[n];
        for (int i = 0; i < n; i++) {
            translation[i] = new Vector3f();
            rotation[i] = new Quaternionf();
            scale[i] = new Vector3f();
            translationB[i] = new Vector3f();
            rotationB[i] = new Quaternionf();
            scaleB[i] = new Vector3f();
            global[i] = new Matrix4f();
        }
    }

    public int jointCount() {
        return jointNodes.length;
    }

    public Clip clip(String name) {
        for (Clip clip : clips) if (clip.name().equals(name)) return clip;
        return null;
    }

    public List<String> clipNames() {
        return clips.stream().map(Clip::name).toList();
    }

    /**
     * Fills 'out' (one matrix per joint) with the pose of clip 'a' at time 'timeA', blended
     * 'blend' of the way (0..1) toward clip 'b' at 'timeB'. Null clips mean the rest pose.
     */
    public void pose(Clip a, float timeA, Clip b, float timeB, float blend, Matrix4f[] out) {
        poseNodes(a, timeA, b, timeB, blend);
        skin(global, out);
    }

    /** Like pose(), but leaves the result as every node's place in the model (see nodes()) instead of joint matrices. */
    public void poseNodes(Clip a, float timeA, Clip b, float timeB, float blend) {
        sample(a, timeA, translation, rotation, scale);
        if (b != null && blend > 0) {
            sample(b, timeB, translationB, rotationB, scaleB);
            for (int i = 0; i < parent.length; i++) {
                translation[i].lerp(translationB[i], blend);
                rotation[i].slerp(rotationB[i], blend);
                scale[i].lerp(scaleB[i], blend);
            }
        }
        for (int node : order) {
            local.translationRotateScale(translation[node], rotation[node], scale[node]);
            if (parent[node] >= 0) global[parent[node]].mul(local, global[node]);
            else global[node].set(local);
        }
    }

    /** Every node's place in the model after the last poseNodes() (or pose()). Don't change them. */
    Matrix4f[] nodes() {
        return global;
    }

    int nodeCount() {
        return parent.length;
    }

    /** Joint matrices (for Skinning) from where every node is. */
    void skin(Matrix4f[] nodes, Matrix4f[] out) {
        for (int j = 0; j < jointNodes.length; j++) out[j].set(nodes[jointNodes[j]]).mul(inverseBinds[j]);
    }

    /** The rest pose, then every channel of the clip at 'time'. */
    private void sample(Clip clip, float time, Vector3f[] t, Quaternionf[] r, Vector3f[] s) {
        for (int i = 0; i < parent.length; i++) {
            t[i].set(restTranslation[i]);
            r[i].set(restRotation[i]);
            s[i].set(restScale[i]);
        }
        if (clip == null) return;
        for (Channel channel : clip.channels()) {
            float[] times = channel.times();
            float[] v = channel.values();
            int width = channel.path() == Path.ROTATION ? 4 : 3;
            int key = keyBefore(times, time);
            int next = Math.min(key + 1, times.length - 1);
            float f = next == key || channel.step() ? 0 : (time - times[key]) / (times[next] - times[key]);
            f = Math.max(0, Math.min(1, f));
            int a = key * width;
            int b = next * width;
            switch (channel.path()) {
                case TRANSLATION -> t[channel.node()].set(v[a], v[a + 1], v[a + 2])
                        .lerp(new Vector3f(v[b], v[b + 1], v[b + 2]), f);
                case SCALE -> s[channel.node()].set(v[a], v[a + 1], v[a + 2])
                        .lerp(new Vector3f(v[b], v[b + 1], v[b + 2]), f);
                case ROTATION -> r[channel.node()].set(v[a], v[a + 1], v[a + 2], v[a + 3])
                        .slerp(new Quaternionf(v[b], v[b + 1], v[b + 2], v[b + 3]), f);
            }
        }
    }

    /** The last keyframe at or before 'time' (0 before the first). */
    private static int keyBefore(float[] times, float time) {
        int low = 0;
        int high = times.length - 1;
        if (time <= times[0]) return 0;
        if (time >= times[high]) return high;
        while (low < high - 1) {
            int middle = (low + high) >>> 1;
            if (times[middle] <= time) low = middle;
            else high = middle;
        }
        return low;
    }

    private static int[] parentsFirst(int[] parent) {
        List<Integer> order = new ArrayList<>();
        boolean[] placed = new boolean[parent.length];
        for (int pass = 0; order.size() < parent.length && pass <= parent.length; pass++) {
            for (int i = 0; i < parent.length; i++) {
                if (!placed[i] && (parent[i] < 0 || placed[parent[i]])) {
                    placed[i] = true;
                    order.add(i);
                }
            }
        }
        return order.stream().mapToInt(Integer::intValue).toArray();
    }
}
