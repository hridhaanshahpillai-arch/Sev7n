package sev7n.render;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import sev7n.scene.Ragdoll;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Connects a model's skeleton to a ragdoll: picks the bones worth simulating (hips, spine,
 * neck, head, arms, legs; not fingers, toes or helper bones), and later turns the model's
 * bones to follow the simulated ones.
 *
 * Hands and feet get one more point at their tip (the furthest fingertip or toe).
 * Bones are chosen by shape, so it works for any humanoid (Mixamo, Unreal-style, Quaternius...):
 * the hips are the highest bone that splits three ways (spine and two legs), and a bone is kept
 * if it's far enough from the last kept one. The chest is where the spine splits three ways.
 */
final class RagdollPose {

    private static final String[] SKIPPED = {"thumb", "index", "middle", "ring", "pinky", "finger",
            "toe", "ball", "leaf", "end", "twist", "roll", "ik", "jaw", "eye", "tongue", "teeth", "corrective", "helper"};

    /** How many solid pieces a body has: the hips and the chest. */
    static final int GROUPS = 2;

    final Rig rig;
    final Ragdoll.Skeleton skeleton;
    /** The rig node of each simulated bone. */
    final int[] boneNode;
    /** For each rig node: the simulated bone that carries it (bones carry themselves; -1 = above the hips, carried by the hips). */
    private final int[] owner;
    private final List<List<Integer>> children = new ArrayList<>();

    private RagdollPose(Rig rig, List<Integer> bones, int[] boneParent, double[] radius, double[] thickness, int[] group, boolean[] trunk) {
        this.rig = rig;
        int count = bones.size();
        boneNode = bones.stream().mapToInt(Integer::intValue).toArray();
        String[] names = new String[count];
        for (int b = 0; b < count; b++) {
            names[b] = rig.names[boneNode[b]];
            children.add(new ArrayList<>());
        }
        for (int b = 1; b < count; b++) children.get(boneParent[b]).add(b);
        skeleton = new Ragdoll.Skeleton(names, boneParent, radius, thickness, group, trunk);

        owner = new int[rig.nodeCount()];
        int[] boneOf = new int[rig.nodeCount()];
        java.util.Arrays.fill(boneOf, -1);
        for (int b = 0; b < count; b++) boneOf[boneNode[b]] = b;
        for (int node : rig.order) {
            owner[node] = boneOf[node] >= 0 ? boneOf[node] : rig.parent[node] >= 0 ? owner[rig.parent[node]] : -1;
        }
    }

    /** The ragdoll bones of a rig, or null if it doesn't look like a body (fewer than 3 usable bones). */
    static RagdollPose of(Rig rig) {
        rig.poseNodes(null, 0, null, 0, 0);
        Matrix4f[] rest = rig.nodes();
        int n = rig.nodeCount();
        Vector3f[] at = new Vector3f[n];
        for (int i = 0; i < n; i++) at[i] = rest[i].getTranslation(new Vector3f());

        Set<Integer> candidates = new HashSet<>();
        for (int joint : rig.jointNodes) if (usable(rig.names[joint])) candidates.add(joint);
        if (candidates.size() < 3) return null;
        float low = Float.MAX_VALUE, high = -Float.MAX_VALUE;
        for (int c : candidates) {
            low = Math.min(low, at[c].y);
            high = Math.max(high, at[c].y);
        }
        double height = Math.max(0.01, high - low);

        int hips = findHips(rig, candidates);
        if (hips < 0) return null;

        // keep bones from the hips down, skipping ones too close to the last kept bone
        List<Integer> bones = new ArrayList<>(List.of(hips));
        List<Integer> parents = new ArrayList<>(List.of(-1));
        int[] keptAbove = new int[n]; // index (in 'bones') of the nearest kept bone at or above each node
        java.util.Arrays.fill(keptAbove, -1);
        keptAbove[hips] = 0;
        for (int node : rig.order) {
            if (node == hips || rig.parent[node] < 0 || keptAbove[rig.parent[node]] < 0) continue;
            int above = keptAbove[rig.parent[node]];
            keptAbove[node] = above;
            if (!candidates.contains(node)) continue;
            if (at[node].distance(at[bones.get(above)]) < 0.04 * height) continue;
            bones.add(node);
            parents.add(above);
            keptAbove[node] = bones.size() - 1;
        }
        if (bones.size() < 3) return null;

        // hands and feet end at a tip (the furthest fingertip or toe), so they turn and collide too
        boolean[] hasKid = new boolean[bones.size()];
        for (int b = 1; b < bones.size(); b++) hasKid[parents.get(b)] = true;
        Set<Integer> tips = new HashSet<>();
        for (int b = 0, ends = bones.size(); b < ends; b++) {
            if (hasKid[b]) continue;
            int tip = furthestJointBelow(rig, bones.get(b), at);
            if (tip >= 0 && at[tip].distance(at[bones.get(b)]) >= 0.04 * height) {
                bones.add(tip);
                parents.add(b);
                tips.add(bones.size() - 1);
            }
        }
        int count = bones.size();
        int[] parent = parents.stream().mapToInt(Integer::intValue).toArray();

        List<List<Integer>> kids = new ArrayList<>();
        for (int b = 0; b < count; b++) kids.add(new ArrayList<>());
        for (int b = 1; b < count; b++) kids.get(parent[b]).add(b);
        int[] subtree = new int[count];
        for (int b = count - 1; b >= 0; b--) {
            subtree[b] += 1;
            if (parent[b] >= 0) subtree[parent[b]] += subtree[b];
        }

        // two solid pieces: the hips (with the tops of the legs) and the chest (with the shoulders),
        // joined by the spine, which bends a little; together they're the trunk
        int[] group = new int[count];
        java.util.Arrays.fill(group, -1);
        boolean[] trunk = new boolean[count];
        int spine = biggest(kids.get(0), subtree);
        group[0] = 0;
        trunk[0] = true;
        for (int kid : kids.get(0)) {
            if (kid == spine) continue;
            group[kid] = 0;
            trunk[kid] = true;
        }
        List<Integer> chain = new ArrayList<>();
        int previous = 0;
        int chest = -1;
        for (int at2 = spine; at2 >= 0; at2 = biggest(kids.get(at2), subtree)) {
            trunk[at2] = true;
            if (kids.get(at2).size() >= 3) {
                chest = at2;
                break;
            }
            chain.add(at2);
            previous = at2;
        }
        if (chest >= 0) {
            // the chest: shoulders are part of it, the neck (straight on up the spine) isn't
            group[chest] = 1;
            Vector3f up = new Vector3f(at[bones.get(chest)]).sub(at[bones.get(previous)]).normalize();
            int neck = -1;
            float best = -2;
            for (int kid : kids.get(chest)) {
                float along = new Vector3f(at[bones.get(kid)]).sub(at[bones.get(chest)]).normalize().dot(up);
                if (along > best) {
                    best = along;
                    neck = kid;
                }
            }
            for (int kid : kids.get(chest)) {
                if (kid == neck) continue;
                group[kid] = 1;
                trunk[kid] = true;
                // the shoulder joints too: they give the chest its width, so it can't spin around the spine
                for (int shoulder : kids.get(kid)) group[shoulder] = 1;
            }
        } else {
            for (int b : chain) group[b] = 0; // no chest found: the spine is part of the hips
        }
        // sizes: balls at the joints, and how thick each bone is (thinner further from the trunk)
        double[] radius = new double[count];
        double[] thickness = new double[count];
        for (int b = 0; b < count; b++) {
            radius[b] = height * (trunk[b] ? 0.065 : tips.contains(b) ? 0.025 : kids.get(b).isEmpty() ? 0.07 : 0.04);
            if (b == 0) continue;
            int fromTrunk = 0;
            for (int p = b; p >= 0 && !trunk[p]; p = parent[p]) fromTrunk++;
            thickness[b] = height * (trunk[b] && trunk[parent[b]] ? 0.06 : Math.max(0.025, 0.055 - 0.01 * fromTrunk));
        }
        return new RagdollPose(rig, bones, parent, radius, thickness, group, trunk);
    }

    /** The joint furthest from 'node' among everything hanging below it (a fingertip, a toe), or -1. */
    private static int furthestJointBelow(Rig rig, int node, Vector3f[] at) {
        Set<Integer> joints = new HashSet<>();
        for (int j : rig.jointNodes) joints.add(j);
        int best = -1;
        for (int candidate : rig.order) {
            if (!joints.contains(candidate) || candidate == node) continue;
            boolean below = false;
            for (int p = rig.parent[candidate]; p >= 0 && !below; p = rig.parent[p]) below = p == node;
            if (below && (best < 0 || at[candidate].distance(at[node]) > at[best].distance(at[node]))) best = candidate;
        }
        return best;
    }

    private static boolean usable(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String word : SKIPPED) if (lower.contains(word)) return false;
        return true;
    }

    /** The highest candidate bone with three or more branches below it (spine and two legs). */
    private static int findHips(Rig rig, Set<Integer> candidates) {
        int best = -1;
        int bestDepth = Integer.MAX_VALUE;
        for (int c : candidates) {
            int branches = 0;
            for (int other : candidates) if (other != c && nearestCandidateAbove(rig, other, candidates) == c) branches++;
            int depth = 0;
            for (int p = rig.parent[c]; p >= 0; p = rig.parent[p]) depth++;
            if (branches >= 3 && depth < bestDepth) {
                best = c;
                bestDepth = depth;
            }
        }
        return best;
    }

    private static int nearestCandidateAbove(Rig rig, int node, Set<Integer> candidates) {
        for (int p = rig.parent[node]; p >= 0; p = rig.parent[p]) if (candidates.contains(p)) return p;
        return -1;
    }

    private static int biggest(List<Integer> bones, int[] subtree) {
        int best = -1;
        for (int b : bones) if (best < 0 || subtree[b] > subtree[best]) best = b;
        return best;
    }

    // ---------------------------------------------------------------- following the simulation

    /** Writes where each simulated bone is in the world, given the model's nodes and where the model is. */
    void capture(Matrix4f[] nodes, Matrix4f modelToWorld, double[] out) {
        Vector3f p = new Vector3f();
        for (int b = 0; b < boneNode.length; b++) {
            nodes[boneNode[b]].getTranslation(p);
            modelToWorld.transformPosition(p);
            out[b * 3] = p.x;
            out[b * 3 + 1] = p.y;
            out[b * 3 + 2] = p.z;
        }
    }

    /**
     * Poses every node of the model ('out') so the simulated bones are where 'points' (world
     * positions) say, starting from 'captured' (the nodes when it went limp). The hips and the
     * chest turn the way their whole piece turned (best fit); every other bone (thighs and upper
     * arms too) turns to point at its child, keeping its parent's twist; fingers, toes and the rest
     * follow the bone that carries them.
     * 'groupTurns' (one per solid piece) carry each piece's turn from one frame to the next.
     */
    void apply(Matrix4f[] captured, double[] points, Matrix4f worldToModel, Quaternionf[] groupTurns, Matrix4f[] out) {
        int count = boneNode.length;
        Vector3f[] was = new Vector3f[count];
        Vector3f[] now = new Vector3f[count];
        for (int b = 0; b < count; b++) {
            was[b] = captured[boneNode[b]].getTranslation(new Vector3f());
            now[b] = worldToModel.transformPosition(new Vector3f((float) points[b * 3], (float) points[b * 3 + 1], (float) points[b * 3 + 2]));
        }
        for (int g = 0; g < GROUPS; g++) bestFitTurn(g, was, now, groupTurns[g]);
        Quaternionf[] turn = new Quaternionf[count];
        Matrix4f[] move = new Matrix4f[count];
        for (int b = 0; b < count; b++) {
            int group = skeleton.group()[b];
            int parent = skeleton.parent()[b];
            if (group >= 0 && (parent < 0 || skeleton.group()[parent] != group)) {
                turn[b] = new Quaternionf(groupTurns[group]); // the hips or the chest: the whole piece's turn
            } else {
                Quaternionf parentTurn = turn[skeleton.parent()[b]];
                List<Integer> kids = children.get(b);
                if (kids.isEmpty()) {
                    turn[b] = new Quaternionf(parentTurn);
                } else {
                    int kid = kids.get(0);
                    Vector3f from = parentTurn.transform(new Vector3f(was[kid]).sub(was[b]));
                    Vector3f to = new Vector3f(now[kid]).sub(now[b]);
                    turn[b] = from.lengthSquared() < 1e-12f || to.lengthSquared() < 1e-12f
                            ? new Quaternionf(parentTurn)
                            : new Quaternionf().rotationTo(from.normalize(), to.normalize()).mul(parentTurn);
                }
            }
            // move = translate(now) * turn * translate(-was)
            move[b] = new Matrix4f().translation(now[b]).rotate(turn[b]).translate(-was[b].x, -was[b].y, -was[b].z);
        }
        for (int node = 0; node < out.length; node++) {
            int b = owner[node] >= 0 ? owner[node] : 0;
            move[b].mul(captured[node], out[node]);
        }
    }

    /**
     * The turn that best lines up one solid piece's bones' old positions with their new ones (shape matching).
     * Starts from (and updates) 'q': the last frame's answer, since the turn barely changes between frames.
     */
    private void bestFitTurn(int group, Vector3f[] was, Vector3f[] now, Quaternionf q) {
        Vector3f wasCenter = new Vector3f();
        Vector3f nowCenter = new Vector3f();
        int members = 0;
        for (int b = 0; b < was.length; b++) {
            if (skeleton.group()[b] != group) continue;
            wasCenter.add(was[b]);
            nowCenter.add(now[b]);
            members++;
        }
        if (members == 0) return;
        wasCenter.div(members);
        nowCenter.div(members);
        // A = sum of (now - nowCenter) (was - wasCenter)^T, as three columns
        Vector3f[] a = {new Vector3f(), new Vector3f(), new Vector3f()};
        for (int b = 0; b < was.length; b++) {
            if (skeleton.group()[b] != group) continue;
            Vector3f p = new Vector3f(now[b]).sub(nowCenter);
            Vector3f w = new Vector3f(was[b]).sub(wasCenter);
            a[0].fma(w.x, p);
            a[1].fma(w.y, p);
            a[2].fma(w.z, p);
        }
        // Müller et al., "A Robust Method to Extract the Rotational Part of Deformations"
        Vector3f[] r = {new Vector3f(), new Vector3f(), new Vector3f()};
        for (int iteration = 0; iteration < 30; iteration++) {
            q.transform(r[0].set(1, 0, 0));
            q.transform(r[1].set(0, 1, 0));
            q.transform(r[2].set(0, 0, 1));
            Vector3f omega = new Vector3f(r[0]).cross(a[0]).add(new Vector3f(r[1]).cross(a[1])).add(new Vector3f(r[2]).cross(a[2]));
            float denominator = Math.abs(r[0].dot(a[0]) + r[1].dot(a[1]) + r[2].dot(a[2])) + 1e-9f;
            omega.div(denominator);
            float angle = omega.length();
            if (angle < 1e-7f) break;
            q.premul(new Quaternionf().fromAxisAngleRad(omega.x / angle, omega.y / angle, omega.z / angle, angle)).normalize();
        }
    }
}
