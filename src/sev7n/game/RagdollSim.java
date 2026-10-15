package sev7n.game;

import sev7n.math.Vec3;
import sev7n.scene.Entity;
import sev7n.scene.Ragdoll;
import sev7n.scene.Scene;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A limp body ("Verlet" physics, the classic way games do ragdolls): a point at every joint,
 * joined by sticks, with every bone a capsule as thick as the limb. Each step the points fly on
 * with their speed and gravity, then, a few times over:
 *
 *   - sticks pull the body back into shape:
 *       each bone to its parent (bones never stretch)
 *       every pair in a solid piece (the hips with the tops of the legs, the chest with the
 *       shoulders); between the two pieces a range lets the chest lean and twist, but only so far
 *       each bone to its grandparent, with a range: the spine bends a little, wrists and ankles
 *       some, elbows and knees a lot (but not folded flat)
 *   - knees only bend backward and elbows only forward (hinges)
 *   - bones push each other apart, so arms and legs never pass through the body or each other
 *   - the world pushes bones out of walls and floors, along their whole length
 *
 * Points touching the world lose speed into it (bouncing back a little) and slide less (friction).
 */
final class RagdollSim {

    private static final double STEP = 1 / 120.0;
    private static final int ITERATIONS = 8;
    private static final double DAMPING = 0.998;
    private static final double MAX_SPEED = 40;

    /** Keeps two points between 'min' and 'max' apart, pulling 'pull' of the way toward 'rest' when in between. */
    private record Stick(int a, int b, double min, double max, double rest, double pull) { }

    /** A bone: the capsule from point a to point b. */
    private record Bone(int a, int b, double radius) { }

    /** Two bones that must stay at least 'min' apart. */
    private record Apart(Bone first, Bone second, double min) { }

    /** A knee or elbow 'joint' between 'upper' and 'lower': it must stay on 'side' (1 = front, -1 = back) of the limb by 'least'. */
    private record Hinge(int upper, int joint, int lower, int piece, int side, double least) { }

    /** How to find which way a solid piece faces: across from 'left' to 'right', up from 'below' to 'above'. */
    private record Facing(int left, int right, int below, int above, double sign) { }

    private final int count;
    private final double[] p;     // positions, x y z per point
    private final double[] q;     // positions one step ago (speed = p - q)
    private final double[] radius;
    private final double[] pushed; // how far the world pushed each point this step (for friction)
    private final double[][] normal;
    private final List<Stick> sticks = new ArrayList<>();
    private final List<Bone> bones = new ArrayList<>();
    private final List<Apart> aparts = new ArrayList<>();
    private final List<Hinge> hinges = new ArrayList<>();
    private final Facing[] facings = new Facing[2];
    private final Ragdoll settings;

    /** Starts limp in 'pose' (world positions), facing 'forward', moving at 'velocity'. */
    RagdollSim(Ragdoll.Skeleton skeleton, double[] pose, Vec3 forward, Vec3 velocity, Ragdoll settings) {
        this.settings = settings;
        count = skeleton.size();
        p = pose.clone();
        q = new double[count * 3];
        radius = skeleton.radius().clone();
        pushed = new double[count];
        normal = new double[count][3];
        for (int i = 0; i < count; i++) {
            q[i * 3] = p[i * 3] - velocity.x * STEP;
            q[i * 3 + 1] = p[i * 3 + 1] - velocity.y * STEP;
            q[i * 3 + 2] = p[i * 3 + 2] - velocity.z * STEP;
        }

        int[] parent = skeleton.parent();
        int[] piece = skeleton.group();
        boolean[] trunk = skeleton.trunk();
        List<List<Integer>> kids = new ArrayList<>();
        for (int i = 0; i < count; i++) kids.add(new ArrayList<>());
        for (int i = 1; i < count; i++) kids.get(parent[i]).add(i);

        boolean[][] joined = new boolean[count][count];
        for (int i = 1; i < count; i++) {
            fixed(i, parent[i]);
            joined[i][parent[i]] = joined[parent[i]][i] = true;
        }
        for (int i = 0; i < count; i++) {
            for (int j = i + 1; j < count; j++) {
                if (joined[i][j] || piece[i] < 0 || piece[j] < 0) continue;
                if (piece[i] == piece[j]) fixed(i, j);
                else sticks.add(new Stick(i, j, distance(i, j) * 0.7, distance(i, j) * 1.03, distance(i, j), 0));
                joined[i][j] = joined[j][i] = true;
            }
        }
        double pull = Math.max(0, Math.min(1, settings.stiffness)) * 0.08;
        for (int i = 1; i < count; i++) {
            int grandparent = parent[parent[i]];
            if (grandparent < 0 || joined[i][grandparent]) continue;
            double rest = distance(i, grandparent);
            double straight = distance(i, parent[i]) + distance(parent[i], grandparent);
            double least = trunk[i] ? rest * 0.9 : kids.get(i).isEmpty() ? Math.min(rest, straight * 0.8) : Math.min(rest, straight * 0.35);
            double most = trunk[i] ? Math.max(rest, straight * 0.98) : straight;
            sticks.add(new Stick(i, grandparent, least, most, rest, pull));
            joined[i][grandparent] = joined[grandparent][i] = true;
        }

        for (int i = 1; i < count; i++) bones.add(new Bone(parent[i], i, skeleton.thickness()[i]));
        for (int i = 0; i < bones.size(); i++) {
            for (int j = i + 1; j < bones.size(); j++) {
                Bone a = bones.get(i), b = bones.get(j);
                if (a.a() == b.a() || a.a() == b.b() || a.b() == b.a() || a.b() == b.b()) continue; // they meet at a joint
                if (trunk[a.a()] && trunk[a.b()] && trunk[b.a()] && trunk[b.b()]) continue;    // both in the trunk: sticks keep them apart
                double now = boneGap(a, b);
                aparts.add(new Apart(a, b, Math.min(a.radius() + b.radius(), now * 0.98)));
            }
        }

        for (int g = 0; g < 2; g++) facings[g] = facing(g, parent, piece, trunk, kids, forward);
        for (int j = 1; j < count; j++) {
            int upper = parent[j];
            int g = piece[upper];
            if (piece[j] >= 0 || g < 0 || facings[g] == null || kids.get(j).size() != 1) continue;
            if (parent[upper] < 0 || piece[parent[upper]] != g) continue; // the piece's own root (hips, chest) isn't a limb
            int side = g == 0 ? 1 : -1; // knees forward, elbows back
            Hinge probe = new Hinge(upper, j, kids.get(j).get(0), g, side, 0);
            hinges.add(new Hinge(upper, j, probe.lower(), g, side, Math.min(0, bend(probe)) - 0.02));
        }
    }

    /** How a solid piece's facing is measured: across its two widest points, up its spine. Null if it can't be. */
    private Facing facing(int g, int[] parent, int[] piece, boolean[] trunk, List<List<Integer>> kids, Vec3 forward) {
        int root = -1;
        for (int i = 0; i < count; i++) if (piece[i] == g && (parent[i] < 0 || piece[parent[i]] != g)) root = i;
        if (root < 0) return null;
        int left = -1, right = -1;
        double widest = 0;
        for (int i = 0; i < count; i++) {
            for (int j = i + 1; j < count; j++) {
                if (piece[i] != g || piece[j] != g || distance(i, j) <= widest) continue;
                widest = distance(i, j);
                left = i;
                right = j;
            }
        }
        int below, above;
        if (g == 0) {
            above = -1;
            for (int kid : kids.get(root)) if (trunk[kid] && piece[kid] != 0) above = kid;
            below = root;
        } else {
            below = parent[root];
            above = root;
        }
        if (left < 0 || above < 0 || below < 0 || widest < 1e-4) return null;
        Facing unsigned = new Facing(left, right, below, above, 1);
        Vec3 f = forward(unsigned);
        return new Facing(left, right, below, above, f.dot(forward) >= 0 ? 1 : -1);
    }

    private Vec3 forward(Facing facing) {
        Vec3 across = position(facing.right()).minus(position(facing.left()));
        Vec3 up = position(facing.above()).minus(position(facing.below()));
        return across.cross(up).normalized().times(facing.sign());
    }

    /** How far a hinge's joint is on its proper side of the line from upper to lower (negative = bent the wrong way). */
    private double bend(Hinge hinge) {
        Vec3 middle = position(hinge.upper()).plus(position(hinge.lower())).times(0.5);
        return position(hinge.joint()).minus(middle).dot(forward(facings[hinge.piece()])) * hinge.side();
    }

    private void fixed(int a, int b) {
        double d = distance(a, b);
        sticks.add(new Stick(a, b, d, d, d, 0));
    }

    /** Moves the body on by 'dt' seconds. 'self' is the ragdoll's own entity (its colliders are ignored). */
    void step(Scene scene, double dt, Vec3 gravity, Entity self) {
        List<WorldCollider> world = nearby(scene, self, dt);
        terrains = Ground.terrains(scene);
        int steps = Math.max(1, (int) Math.ceil(dt / STEP - 1e-9));
        double h = dt / steps;
        for (int s = 0; s < steps; s++) {
            integrate(gravity, h);
            Arrays.fill(pushed, 0);
            for (double[] n : normal) Arrays.fill(n, 0);
            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                for (Stick stick : sticks) satisfy(stick);
                for (Hinge hinge : hinges) straighten(hinge);
                if (iteration % 2 == 0) for (Apart apart : aparts) keepApart(apart);
                collide(world);
            }
            touchResponse();
        }
        // still for a moment = resting (lying on the ground, ready to get up)
        double fastest = 0;
        for (int i = 0; i < count * 3; i++) fastest = Math.max(fastest, Math.abs(p[i] - q[i]));
        double speed = fastest / h;
        stillFor = speed < 0.25 ? stillFor + dt : 0;
    }

    private double stillFor;

    /** True once the body has stopped moving (for a moment), e.g. lying on the floor. */
    boolean resting() {
        return stillFor >= 0.4;
    }

    /** The world's colliders near the body (anything it could reach this frame). */
    private List<WorldCollider> nearby(Scene scene, Entity self, double dt) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            minX = Math.min(minX, p[i * 3]);
            minY = Math.min(minY, p[i * 3 + 1]);
            minZ = Math.min(minZ, p[i * 3 + 2]);
            maxX = Math.max(maxX, p[i * 3]);
            maxY = Math.max(maxY, p[i * 3 + 1]);
            maxZ = Math.max(maxZ, p[i * 3 + 2]);
        }
        Box around = new Box(minX, minY, minZ, maxX, maxY, maxZ).grown(0.5 + MAX_SPEED * dt);
        List<WorldCollider> near = new ArrayList<>();
        for (WorldCollider collider : WorldCollider.all(scene, false)) {
            if (collider.entity() != self && collider.bounds().touches(around, 0)) near.add(collider);
        }
        return near;
    }

    /** The points fly on: speed (slightly damped) plus gravity. */
    private void integrate(Vec3 gravity, double h) {
        double maxMove = MAX_SPEED * h;
        for (int i = 0; i < count; i++) {
            for (int k = 0; k < 3; k++) {
                int at = i * 3 + k;
                double move = (p[at] - q[at]) * DAMPING;
                move = Math.max(-maxMove, Math.min(maxMove, move));
                double g = k == 0 ? gravity.x : k == 1 ? gravity.y : gravity.z;
                q[at] = p[at];
                p[at] += move + g * h * h;
            }
        }
    }

    private void satisfy(Stick stick) {
        int a = stick.a() * 3, b = stick.b() * 3;
        double dx = p[b] - p[a], dy = p[b + 1] - p[a + 1], dz = p[b + 2] - p[a + 2];
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d < 1e-9) return;
        double target;
        if (d < stick.min()) target = stick.min();
        else if (d > stick.max()) target = stick.max();
        else if (stick.pull() > 0) target = d + (stick.rest() - d) * stick.pull();
        else return;
        double move = (d - target) / d * 0.5;
        p[a] += dx * move;
        p[a + 1] += dy * move;
        p[a + 2] += dz * move;
        p[b] -= dx * move;
        p[b + 1] -= dy * move;
        p[b + 2] -= dz * move;
    }

    /** A knee or elbow bent the wrong way is pushed back through: the joint one way, the ends the other. */
    private void straighten(Hinge hinge) {
        double wrong = hinge.least() - bend(hinge);
        if (wrong <= 0) return;
        Vec3 f = forward(facings[hinge.piece()]).times(hinge.side() * wrong);
        add(hinge.joint(), f.times(2.0 / 3));
        add(hinge.upper(), f.times(-1.0 / 3));
        add(hinge.lower(), f.times(-1.0 / 3));
    }

    /** Two bones too close (an arm in the chest, one leg in the other) are pushed apart where they're closest. */
    private void keepApart(Apart apart) {
        Bone a = apart.first(), b = apart.second();
        double[] st = closest(position(a.a()), position(a.b()), position(b.a()), position(b.b()));
        Vec3 onA = position(a.a()).lerp(position(a.b()), st[0]);
        Vec3 onB = position(b.a()).lerp(position(b.b()), st[1]);
        Vec3 between = onA.minus(onB);
        double d = between.length();
        if (d >= apart.min()) return;
        Vec3 direction = d > 1e-9 ? between.times(1 / d) : new Vec3(0, 1, 0);
        Vec3 push = direction.times((apart.min() - d) * 0.5);
        spread(a, st[0], push);
        spread(b, st[1], push.negated());
    }

    private List<Entity> terrains = List.of();

    /** Pushes points and bones out of anything solid they've sunk into (and up out of the ground). */
    private void collide(List<WorldCollider> world) {
        if (!terrains.isEmpty()) {
            for (int i = 0; i < count; i++) {
                double ground = Ground.heightAt(terrains, p[i * 3], p[i * 3 + 2]);
                double sunk = Double.isNaN(ground) ? 0 : ground + radius[i] - p[i * 3 + 1];
                if (sunk <= 0) continue;
                Vec3 up = new Vec3(0, sunk, 0);
                add(i, up);
                touched(i, up, 1);
            }
        }
        for (int i = 0; i < count; i++) {
            Vec3 at = position(i);
            Vec3 out = pushOut(at, radius[i], world);
            if (out == null) continue;
            add(i, out);
            touched(i, out, 1);
        }
        for (Bone bone : bones) {
            Vec3 a = position(bone.a()), b = position(bone.b());
            int samples = Math.max(1, (int) Math.ceil(a.distance(b) / (bone.radius() * 1.5)));
            for (int k = 1; k < samples; k++) {
                double t = (double) k / samples;
                Vec3 out = pushOut(position(bone.a()).lerp(position(bone.b()), t), bone.radius(), world);
                if (out == null) continue;
                spread(bone, t, out);
                touched(bone.a(), out, 1 - t);
                touched(bone.b(), out, t);
            }
        }
    }

    /** How far a ball at 'at' must move to get out of everything it overlaps, or null if it's free. */
    private static Vec3 pushOut(Vec3 at, double r, List<WorldCollider> world) {
        Vec3 total = null;
        Vec3 here = at.copy();
        for (WorldCollider collider : world) {
            Round ball = new Round(here, here.copy(), r);
            if (!collider.bounds().touches(ball.bounds(), 0)) continue;
            Geometry.Overlap overlap = new WorldCollider(null, ball.bounds(), ball, false).overlap(collider, 0);
            if (overlap == null || overlap.depth() <= 0) continue;
            Vec3 move = overlap.normal().times(overlap.depth());
            here = here.plus(move);
            total = total == null ? move : total.plus(move);
        }
        return total;
    }

    /** Moves a bone so the point 't' of the way along it moves by 'move', sharing it between its two ends. */
    private void spread(Bone bone, double t, Vec3 move) {
        double wa = 1 - t, wb = t;
        double scale = 1 / (wa * wa + wb * wb);
        add(bone.a(), move.times(wa * scale));
        add(bone.b(), move.times(wb * scale));
    }

    private void touched(int i, Vec3 push, double share) {
        double amount = push.length() * share;
        if (amount <= 0) return;
        pushed[i] += amount;
        normal[i][0] += push.x * share;
        normal[i][1] += push.y * share;
        normal[i][2] += push.z * share;
    }

    /** Points touching something lose speed into it (bouncing back a little) and slide less (friction). */
    private void touchResponse() {
        for (int i = 0; i < count; i++) {
            if (pushed[i] <= 0) continue;
            double nx = normal[i][0], ny = normal[i][1], nz = normal[i][2];
            double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length < 1e-12) continue;
            nx /= length;
            ny /= length;
            nz /= length;
            int at = i * 3;
            double vx = p[at] - q[at], vy = p[at + 1] - q[at + 1], vz = p[at + 2] - q[at + 2];
            double into = vx * nx + vy * ny + vz * nz;
            double nvx = nx * into, nvy = ny * into, nvz = nz * into;
            double tvx = vx - nvx, tvy = vy - nvy, tvz = vz - nvz;
            if (into < 0) {
                double bounce = -Math.max(0, Math.min(1, settings.bounciness));
                nvx *= bounce;
                nvy *= bounce;
                nvz *= bounce;
            }
            double slide = Math.sqrt(tvx * tvx + tvy * tvy + tvz * tvz);
            double keep = slide <= 0 ? 0 : Math.max(0, slide - settings.friction * pushed[i] * 2) / slide;
            q[at] = p[at] - (nvx + tvx * keep);
            q[at + 1] = p[at + 1] - (nvy + tvy * keep);
            q[at + 2] = p[at + 2] - (nvz + tvz * keep);
        }
    }

    /** Where along two segments (0..1 each) they come closest. */
    private static double[] closest(Vec3 p1, Vec3 q1, Vec3 p2, Vec3 q2) {
        Vec3 d1 = q1.minus(p1), d2 = q2.minus(p2), r = p1.minus(p2);
        double a = d1.dot(d1), e = d2.dot(d2), f = d2.dot(r);
        double s, t;
        if (a < 1e-12 && e < 1e-12) return new double[] {0, 0};
        if (a < 1e-12) {
            s = 0;
            t = clamp(f / e);
        } else {
            double c = d1.dot(r);
            if (e < 1e-12) {
                t = 0;
                s = clamp(-c / a);
            } else {
                double b = d1.dot(d2);
                double denominator = a * e - b * b;
                s = denominator > 1e-12 ? clamp((b * f - c * e) / denominator) : 0;
                t = (b * s + f) / e;
                if (t < 0) {
                    t = 0;
                    s = clamp(-c / a);
                } else if (t > 1) {
                    t = 1;
                    s = clamp((b - c) / a);
                }
            }
        }
        return new double[] {s, t};
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private double boneGap(Bone a, Bone b) {
        double[] st = closest(position(a.a()), position(a.b()), position(b.a()), position(b.b()));
        return position(a.a()).lerp(position(a.b()), st[0]).distance(position(b.a()).lerp(position(b.b()), st[1]));
    }

    private void add(int i, Vec3 move) {
        p[i * 3] += move.x;
        p[i * 3 + 1] += move.y;
        p[i * 3 + 2] += move.z;
    }

    /** Where every point is now (x, y, z per bone). */
    double[] positions() {
        return p.clone();
    }

    /** Where bone 'i' is now. */
    Vec3 position(int i) {
        return new Vec3(p[i * 3], p[i * 3 + 1], p[i * 3 + 2]);
    }

    /** The lowest point of the body (bottom of the lowest ball). */
    double lowest() {
        double low = Double.MAX_VALUE;
        for (int i = 0; i < count; i++) low = Math.min(low, p[i * 3 + 1] - radius[i]);
        return low;
    }

    private double distance(int a, int b) {
        double dx = p[b * 3] - p[a * 3], dy = p[b * 3 + 1] - p[a * 3 + 1], dz = p[b * 3 + 2] - p[a * 3 + 2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
