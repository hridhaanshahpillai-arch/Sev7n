package sev7n.game;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import sev7n.math.Vec3;
import sev7n.scene.Entity;
import sev7n.scene.Ragdoll;
import sev7n.scene.Rigidbody;
import sev7n.scene.Scene;
import sev7n.scene.Tilemap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/**
 * Moves rigidbodies. Each small step:
 *   1. gravity, forces and drag change the velocity
 *   2. the body moves and stops at (or bounces off) anything solid:
 *        box bodies move one axis at a time (X, then Y, then Z) and stop exactly at walls,
 *        which keeps platformer characters crisp;
 *        round bodies (sphere, capsule) move, then are pushed out of whatever they overlap,
 *        so they roll off edges and slide along slopes and corners
 *   3. friction slows sliding along what it touches
 * When a body runs into another rigidbody, they push each other by their masses.
 *
 * Solid things are colliders that aren't triggers (with or without a Rigidbody) and the
 * tiles of solid tilemaps. Big frames are split into small steps so fast objects don't
 * skip through thin walls.
 */
final class Physics {

    private static final double MAX_STEP = 1.0 / 120;
    private static final double EPSILON = 1e-4; // boxes are worked out in float, so allow for rounding
    private static final double STOP_BOUNCING = 0.5; // slower bounces than this just stop
    private static final double LEDGE_SLIP_SPEED = 1.5; // units per second a body slides off an edge it's overhanging
    private static final double GROUND_NORMAL = 0.6; // a surface this upward-facing (or more) counts as ground

    /** Units per second, per second. Scripts can change it with setGravity(). */
    final Vec3 gravity = new Vec3(0, -9.81, 0);

    /** A solid's box for box bodies, with who it belongs to (null for tiles). */
    private record Solid(Entity entity, Box box) { }

    // during a step: every solid collider, and where each entity's is in the list
    private List<WorldCollider> colliders = List.of();
    private List<Entity> stepTilemaps;
    private List<Entity> stepTerrains = List.of();

    /** Terrain: a body that sinks below the ground is put back on top of it, standing (and sliding less). */
    private void keepAboveGround(Entity entity, Rigidbody body, double h) {
        WorldCollider self = WorldCollider.of(entity);
        if (self == null) return;
        Vec3 p = entity.transform.position;
        Box b = self.bounds();
        double ground = Ground.heightAt(stepTerrains, (b.minX() + b.maxX()) / 2, (b.minZ() + b.maxZ()) / 2);
        if (Double.isNaN(ground)) return;
        double gap = b.minY() - ground;
        if (gap > 0.02) return;
        if (gap < 0) lockedAdd(p, new Vec3(0, -gap, 0), body);
        if (body.velocity.y < 0) {
            double bounced = -body.velocity.y * body.bounciness;
            body.velocity.y = bounced < STOP_BOUNCING ? 0 : bounced;
        }
        body.grounded = true;
        slowSliding(body, new Vec3(0, 1, 0), h);
    }
    private final Map<Entity, Integer> colliderIndex = new HashMap<>();

    /** Brings an entity's collider in this step's list up to date after it moved. */
    private void refresh(Entity entity) {
        Integer at = colliderIndex.get(entity);
        if (at == null) return;
        WorldCollider now = WorldCollider.of(entity);
        if (now != null) colliders.set(at, now);
    }

    void step(Scene scene, double dt) {
        List<Entity> bodies = new ArrayList<>();
        for (Entity entity : scene.entities()) {
            Rigidbody body = entity.get(Rigidbody.class);
            if (entity.active && body != null && !Ragdoll.limp(entity)) {
                bodies.add(entity);
                body.grounded = false;
            }
        }
        if (bodies.isEmpty()) return;

        int steps = (int) Math.ceil(dt / MAX_STEP);
        double h = dt / Math.max(1, steps);
        for (int i = 0; i < steps; i++) {
            // every collider once per step; a body's entry is brought up to date whenever it moves
            colliders = new ArrayList<>(WorldCollider.all(scene, false));
            stepTilemaps = solidTilemaps(scene);
            stepTerrains = Ground.terrains(scene);
            colliderIndex.clear();
            for (int c = 0; c < colliders.size(); c++) colliderIndex.put(colliders.get(c).entity(), c);
            for (Entity entity : bodies) {
                Rigidbody body = entity.get(Rigidbody.class);
                if (body.kinematic) continue; // scripts move it
                accelerate(body, h);
                Integer at = colliderIndex.get(entity);
                WorldCollider self = at == null ? null : colliders.get(at);
                if (self == null) self = WorldCollider.of(entity); // a trigger
                if (self == null || self.trigger()) fly(entity, body, h);
                else if (self.round() == null) moveBox(entity, body, self.bounds(), colliders, scene, h);
                else moveRound(entity, body, colliders, scene, h);
                if (!stepTerrains.isEmpty() && self != null && !self.trigger()) keepAboveGround(entity, body, h);
                refresh(entity);
            }
        }
        colliders = List.of();
        stepTilemaps = null;
        stepTerrains = List.of();
        for (Entity entity : bodies) entity.get(Rigidbody.class).force.set(0, 0, 0);
    }

    /** Gravity, forces (added by scripts) and drag. */
    private void accelerate(Rigidbody body, double h) {
        Vec3 v = body.velocity;
        double inverseMass = body.inverseMass();
        v.x += (gravity.x * body.gravityScale + body.force.x * inverseMass) * h;
        v.y += (gravity.y * body.gravityScale + body.force.y * inverseMass) * h;
        v.z += (gravity.z * body.gravityScale + body.force.z * inverseMass) * h;
        double keep = Math.max(0, 1 - body.drag * h);
        v.x *= keep;
        v.y *= keep;
        v.z *= keep;
        lock(body, v);
    }

    private static void lock(Rigidbody body, Vec3 v) {
        if (body.lockX) v.x = 0;
        if (body.lockY) v.y = 0;
        if (body.lockZ) v.z = 0;
    }

    /** Nothing to collide with: just move. */
    private static void fly(Entity entity, Rigidbody body, double h) {
        Vec3 p = entity.transform.position;
        Vec3 v = body.velocity;
        p.set(p.x + v.x * h, p.y + v.y * h, p.z + v.z * h);
    }

    // ---------------------------------------------------------------- box bodies

    private void moveBox(Entity entity, Rigidbody body, Box box, List<WorldCollider> colliders, Scene scene, double h) {
        Vec3 v = body.velocity;
        double reach = Math.abs(v.x * h) + Math.abs(v.y * h) + Math.abs(v.z * h) + 0.1;
        Box area = box.grown(reach);
        List<Solid> solids = new ArrayList<>();
        for (WorldCollider collider : colliders) {
            if (collider.entity() != entity && collider.bounds().touches(area, 0)) solids.add(new Solid(collider.entity(), collider.bounds()));
        }
        for (Box tile : tiles(scene, area)) solids.add(new Solid(null, tile));

        for (int axis = 0; axis < 3; axis++) {
            double wanted = component(v, axis) * h;
            if (wanted == 0) continue;
            Solid[] blocker = new Solid[1];
            double allowed = sweep(box, axis, wanted, solids, blocker);
            box = box.moved(axis == 0 ? allowed : 0, axis == 1 ? allowed : 0, axis == 2 ? allowed : 0);
            addToPosition(entity, axis, allowed);
            if (allowed == wanted) continue;
            if (axis == 1 && wanted < 0) body.grounded = true;
            Rigidbody other = blocker[0] == null || blocker[0].entity() == null ? null : blocker[0].entity().get(Rigidbody.class);
            if (other != null && !other.kinematic) {
                pushAlongAxis(body, other, axis); // two bodies bump: they share the movement by mass
            } else {
                double bounced = -component(v, axis) * body.bounciness;
                setComponent(v, axis, Math.abs(bounced) < STOP_BOUNCING ? 0 : bounced);
            }
            if (axis == 1) slowSliding(body, new Vec3(0, wanted < 0 ? 1 : -1, 0), h);
        }
    }

    /** How far the box can move along one axis before it hits a solid (at most 'wanted'). 'blocker' gets what stopped it. */
    private static double sweep(Box box, int axis, double wanted, List<Solid> solids, Solid[] blocker) {
        double allowed = wanted;
        for (Solid solid : solids) {
            Box other = solid.box();
            if (!overlapsOnOtherAxes(box, other, axis)) continue;
            if (wanted > 0 && box.max(axis) <= other.min(axis) + EPSILON) {
                double gap = other.min(axis) - box.max(axis);
                if (gap < allowed) {
                    allowed = gap;
                    blocker[0] = solid;
                }
            } else if (wanted < 0 && box.min(axis) >= other.max(axis) - EPSILON) {
                double gap = other.max(axis) - box.min(axis);
                if (gap > allowed) {
                    allowed = gap;
                    blocker[0] = solid;
                }
            }
            // already overlapping (e.g. spawned inside a wall): let it move out freely
        }
        return wanted > 0 ? Math.max(0, allowed) : Math.min(0, allowed);
    }

    private static boolean overlapsOnOtherAxes(Box a, Box b, int axis) {
        for (int other = 0; other < 3; other++) {
            if (other == axis) continue;
            if (a.min(other) >= b.max(other) - EPSILON || a.max(other) <= b.min(other) + EPSILON) return false;
        }
        return true;
    }

    /** A head-on bump along one axis between two bodies: momentum is kept, bounciness decides how much they spring apart. */
    private static void pushAlongAxis(Rigidbody a, Rigidbody b, int axis) {
        double ma = a.mass;
        double mb = b.mass;
        double ua = component(a.velocity, axis);
        double ub = component(b.velocity, axis);
        double e = Math.max(a.bounciness, b.bounciness);
        double total = ma * ua + mb * ub;
        double va = (total + mb * e * (ub - ua)) / (ma + mb);
        double vb = (total + ma * e * (ua - ub)) / (ma + mb);
        setComponent(a.velocity, axis, va);
        setComponent(b.velocity, axis, vb);
        lock(a, a.velocity);
        lock(b, b.velocity);
    }

    // ---------------------------------------------------------------- round bodies (sphere, capsule)

    private void moveRound(Entity entity, Rigidbody body, List<WorldCollider> colliders, Scene scene, double h) {
        // fast and small (a ball fired from a cannon): move in pieces no longer than half its radius, so it can't skip through a thin wall
        Round shape = WorldCollider.of(entity).round();
        double travel = body.velocity.length() * h;
        int pieces = shape == null || shape.radius() <= 0 ? 1 : (int) Math.min(16, Math.ceil(travel / (shape.radius() * 0.5)));
        Vec3 start = entity.transform.position.copy();
        for (int i = 0; i < Math.max(1, pieces); i++) moveRoundOnce(entity, body, colliders, scene, h / Math.max(1, pieces));
        roll(entity, body, shape, entity.transform.position.minus(start));
    }

    /** A ball on the ground turns as it goes, as if it were rolling (a sphere's collider doesn't mind which way it's turned). */
    private static void roll(Entity entity, Rigidbody body, Round shape, Vec3 moved) {
        if (!body.rolls || !body.grounded || shape == null || shape.radius() <= 0 || shape.a().distance(shape.b()) > 1e-6) return;
        Vec3 flat = new Vec3(moved.x, 0, moved.z);
        double distance = flat.length();
        if (distance < 1e-6) return;
        Vec3 axis = new Vec3(0, 1, 0).cross(flat).normalized();
        Matrix4f turned = new Matrix4f().rotate((float) (distance / shape.radius()), (float) axis.x, (float) axis.y, (float) axis.z)
                .mul(rotationOnly(entity.transform));
        entity.transform.setRotationFromMatrix(turned);
    }

    private static Matrix4f rotationOnly(sev7n.scene.Transform t) {
        return new Matrix4f().rotateZ((float) Math.toRadians(t.rotation.z)).rotateY((float) Math.toRadians(t.rotation.y))
                .rotateX((float) Math.toRadians(t.rotation.x));
    }

    private void moveRoundOnce(Entity entity, Rigidbody body, List<WorldCollider> colliders, Scene scene, double h) {
        Vec3 v = body.velocity;
        Vec3 p = entity.transform.position;
        p.set(p.x + v.x * h, p.y + v.y * h, p.z + v.z * h);

        Vec3 ground = null; // the most upward-facing surface it's standing on
        for (int iteration = 0; iteration < 3; iteration++) {
            WorldCollider self = WorldCollider.of(entity);
            Box area = self.bounds().grown(0.05);
            boolean pushed = false;
            for (WorldCollider other : colliders) {
                if (other.entity() == entity || !other.bounds().touches(area, 0)) continue;
                Geometry.Overlap overlap = self.overlap(other, 0);
                if (overlap == null || overlap.depth() <= 0) continue;
                if (stepUp(entity, body, self, other, overlap, colliders)) {
                    pushed = true;
                    self = WorldCollider.of(entity);
                    continue;
                }
                resolve(entity, body, other.entity(), other.dynamicBody(), overlap, h);
                if (overlap.normal().y > GROUND_NORMAL && (ground == null || overlap.normal().y > ground.y)) ground = overlap.normal();
                pushed = true;
                self = WorldCollider.of(entity);
            }
            for (Box tile : tiles(scene, area)) {
                Geometry.Overlap overlap = Geometry.overlap(self.round(), tile, 0);
                if (overlap == null || overlap.depth() <= 0) continue;
                resolve(entity, body, null, null, overlap, h);
                pushed = true;
                self = WorldCollider.of(entity);
            }
            if (!pushed) break;
        }
        slipOffLedge(entity, body, ground, h);
    }

    /**
     * Walking into something low (a stair, a curb, a chair seat): a standing capsule climbs on
     * top of it if it's no higher than the Rigidbody's step height and there's room up there.
     */
    private boolean stepUp(Entity entity, Rigidbody body, WorldCollider self, WorldCollider other, Geometry.Overlap overlap, List<WorldCollider> colliders) {
        if (body.stepHeight <= 0 || !upright(self) || Math.abs(overlap.normal().y) > GROUND_NORMAL || body.velocity.y > 1) return false;
        if (other.dynamicBody() != null && other.round() != null) return false; // other characters and balls are pushed, not climbed
        double rise = other.bounds().maxY() - self.bounds().minY();
        if (rise <= 0.001 || rise > body.stepHeight) return false;
        Vec3 into = new Vec3(-overlap.normal().x, 0, -overlap.normal().z);
        if (into.length() < 1e-6 || new Vec3(body.velocity.x, 0, body.velocity.z).dot(into.normalized()) < 0.1) return false; // only when walking into it
        Vec3 p = entity.transform.position;
        double y = p.y;
        p.y += rise + 0.005;
        WorldCollider lifted = WorldCollider.of(entity);
        for (WorldCollider c : colliders) {
            if (c.entity() == entity || !c.bounds().touches(lifted.bounds(), 0)) continue;
            Geometry.Overlap o = lifted.overlap(c, 0);
            if (o != null && o.depth() > 0.01) {
                p.y = y; // no room up there
                return false;
            }
        }
        // and there must be something to stand on up there (not just air beside an edge)
        Vec3 sunk = p.copy();
        p.y -= 0.03;
        boolean standsOnIt = WorldCollider.of(entity).touches(other, 0);
        p.set(sunk);
        if (!standsOnIt) {
            p.y = y;
            return false;
        }
        body.velocity.y = Math.max(0, body.velocity.y);
        body.grounded = true;
        return true;
    }

    /**
     * Standing on an edge with its middle past it (the ground pushes up at a slant): it slips
     * off, the way a body does when its weight is over the drop. Moves the position, so it
     * happens even when a script sets the velocity every frame.
     */
    private static void slipOffLedge(Entity entity, Rigidbody body, Vec3 ground, double h) {
        if (ground == null || ground.y > 0.985 || body.kinematic) return;
        WorldCollider self = WorldCollider.of(entity);
        if (self == null || !upright(self)) return;
        Vec3 out = new Vec3(ground.x, 0, ground.z);
        if (out.length() < 1e-6) return;
        lockedAdd(entity.transform.position, out.normalized().times(LEDGE_SLIP_SPEED * h), body);
    }

    /** A capsule standing up straight (not a sphere, not lying down). */
    private static boolean upright(WorldCollider collider) {
        Round r = collider.round();
        return r != null && r.b().y - r.a().y > 0.01 && Math.abs(r.b().x - r.a().x) < 0.01 && Math.abs(r.b().z - r.a().z) < 0.01;
    }

    /** Pushes a round body out of something it overlaps, then fixes its velocity (bounce or stop, friction, push the other). */
    private void resolve(Entity entity, Rigidbody body, Entity otherEntity, Rigidbody other, Geometry.Overlap overlap, double h) {
        Vec3 n = overlap.normal();
        double inverseA = body.inverseMass();
        double inverseB = other == null ? 0 : other.inverseMass();
        double total = inverseA + inverseB;
        if (total == 0) return;

        // separate them (the lighter one moves more), but never shove something standing on the ground into it
        boolean otherHeldUp = other != null && other.grounded && n.y > GROUND_NORMAL;
        double moveB = otherHeldUp ? 0 : inverseB;
        Vec3 correction = n.times(overlap.depth() / (inverseA + moveB));
        lockedAdd(entity.transform.position, correction.times(inverseA), body);
        if (other != null && moveB > 0) {
            lockedAdd(otherEntity.transform.position, correction.times(-moveB), other);
            refresh(otherEntity);
        }

        // stop them moving into each other, with bounce
        Vec3 otherVelocity = other == null ? new Vec3() : other.velocity;
        double closing = body.velocity.minus(otherVelocity).dot(n);
        if (closing < 0) {
            double e = Math.max(body.bounciness, other == null ? 0 : other.bounciness);
            if (-closing < STOP_BOUNCING) e = 0;
            double impulse = -(1 + e) * closing / total;
            body.velocity.set(body.velocity.plus(n.times(impulse * inverseA)));
            if (other != null) other.velocity.set(other.velocity.minus(n.times(impulse * inverseB)));
            lock(body, body.velocity);
            if (other != null) lock(other, other.velocity);
        }
        if (n.y > GROUND_NORMAL) body.grounded = true;
        slowSliding(body, n, h);
    }

    private static void lockedAdd(Vec3 position, Vec3 delta, Rigidbody body) {
        position.set(position.x + (body.lockX ? 0 : delta.x), position.y + (body.lockY ? 0 : delta.y), position.z + (body.lockZ ? 0 : delta.z));
    }

    /** Friction: the part of the velocity sliding along a surface (facing 'normal') slows down. */
    private void slowSliding(Rigidbody body, Vec3 normal, double h) {
        if (body.friction <= 0) return;
        Vec3 v = body.velocity;
        Vec3 along = v.minus(normal.times(v.dot(normal)));
        double speed = along.length();
        if (speed < 1e-9) return;
        double loss = body.friction * Math.abs(gravity.y) * Math.max(0.2, Math.abs(normal.y)) * h * 1.5;
        Vec3 slowed = along.times(Math.max(0, speed - loss) / speed);
        v.set(v.minus(along).plus(slowed));
    }

    // ---------------------------------------------------------------- tiles

    /** The boxes of solid tiles near 'area'. Each tile is as deep as it is wide, centered on the map's plane. */
    private List<Box> tiles(Scene scene, Box area) {
        return tiles(stepTilemaps != null ? stepTilemaps : solidTilemaps(scene), area);
    }

    /** The active, solid, non-empty tilemaps in a scene (usually none in a 3D game, so tile checks can be skipped). */
    static List<Entity> solidTilemaps(Scene scene) {
        List<Entity> maps = new ArrayList<>();
        for (Entity entity : scene.entities()) {
            Tilemap map = entity.get(Tilemap.class);
            if (entity.active && map != null && map.solid && !map.isEmpty()) maps.add(entity);
        }
        return maps;
    }

    private static List<Box> tiles(List<Entity> maps, Box area) {
        List<Box> boxes = new ArrayList<>();
        for (Entity entity : maps) {
            Tilemap map = entity.get(Tilemap.class);
            Matrix4f matrix = entity.transform.matrix(new Matrix4f());
            Matrix4f inverse = matrix.invert(new Matrix4f());
            if (!inverse.isFinite()) continue;
            Box local = Box.around(inverse, area.minX(), area.minY(), area.minZ(), area.maxX(), area.maxY(), area.maxZ());
            double s = map.tileSize;
            if (local.minZ() > s / 2 || local.maxZ() < -s / 2) continue; // not level with the map
            int x0 = (int) Math.floor(local.minX() / s);
            int x1 = (int) Math.floor(local.maxX() / s);
            int y0 = (int) Math.floor(local.minY() / s);
            int y1 = (int) Math.floor(local.maxY() / s);
            if ((long) (x1 - x0) * (y1 - y0) > 10_000) continue; // something huge or far away
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    if (map.tile(x, y) == Tilemap.EMPTY) continue;
                    boxes.add(Box.around(matrix, x * s, y * s, -s / 2, (x + 1) * s, (y + 1) * s, s / 2));
                }
            }
        }
        return boxes;
    }

    /** True if a point is inside a solid tile of any solid tilemap (for checks like "is there ground ahead?"). */
    static boolean solidTileAt(Scene scene, Vec3 point) {
        for (Entity entity : scene.entities()) {
            Tilemap map = entity.get(Tilemap.class);
            if (!entity.active || map == null || !map.solid) continue;
            Matrix4f inverse = entity.transform.matrix(new Matrix4f()).invert();
            Vector3f local = inverse.transformPosition(new Vector3f((float) point.x, (float) point.y, (float) point.z));
            int x = (int) Math.floor(local.x / map.tileSize);
            int y = (int) Math.floor(local.y / map.tileSize);
            if (Math.abs(local.z) <= map.tileSize / 2 && map.tile(x, y) != Tilemap.EMPTY) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- questions scripts can ask

    /** What a ray hit first: the entity (null for a tile), where, the surface direction, and how far. */
    record RayHit(Entity entity, Vec3 point, Vec3 normal, double distance) { }

    /**
     * The first solid thing along a ray, or null. Triggers are ignored, and so is 'ignore'
     * (usually whoever is casting the ray, so it doesn't hit itself).
     */
    static RayHit raycast(Scene scene, Vec3 origin, Vec3 direction, double maxDistance, Entity ignore) {
        Vec3 d = direction.normalized();
        if (d.length() == 0) return null;
        RayHit best = null;
        for (WorldCollider collider : WorldCollider.all(scene, false)) {
            if (collider.entity() == ignore) continue;
            Geometry.Hit hit = collider.ray(origin, d, best == null ? maxDistance : best.distance());
            if (hit != null && (best == null || hit.distance() < best.distance())) {
                best = new RayHit(collider.entity(), origin.plus(d.times(hit.distance())), hit.normal(), hit.distance());
            }
        }
        List<Entity> terrains = Ground.terrains(scene);
        double ground = Ground.rayDistance(terrains, origin, d, best == null ? maxDistance : best.distance());
        if (!Double.isNaN(ground)) {
            Vec3 at = origin.plus(d.times(ground));
            best = new RayHit(Ground.terrainAt(terrains, at.x, at.z), at, Ground.normalAt(terrains, at.x, at.z), ground);
        }
        Vec3 end = origin.plus(d.times(best == null ? maxDistance : best.distance()));
        Box along = new Box(Math.min(origin.x, end.x), Math.min(origin.y, end.y), Math.min(origin.z, end.z),
                Math.max(origin.x, end.x), Math.max(origin.y, end.y), Math.max(origin.z, end.z)).grown(0.01);
        for (Box tile : tiles(solidTilemaps(scene), along)) {
            Geometry.Hit hit = Geometry.ray(origin, d, tile, best == null ? maxDistance : best.distance());
            if (hit != null && (best == null || hit.distance() < best.distance())) {
                best = new RayHit(null, origin.plus(d.times(hit.distance())), hit.normal(), hit.distance());
            }
        }
        return best;
    }

    /** Every entity whose collider (triggers too) touches a ball. */
    static List<Entity> overlapSphere(Scene scene, Vec3 center, double radius) {
        WorldCollider ball = new WorldCollider(null, new Round(center, center.copy(), radius).bounds(),
                new Round(center, center.copy(), radius), true);
        List<Entity> found = new ArrayList<>();
        for (WorldCollider collider : WorldCollider.all(scene, true)) {
            if (collider.touches(ball, 0)) found.add(collider.entity());
        }
        return found;
    }

    /** True if a point is inside something solid: a non-trigger collider or a solid tile. */
    static boolean solidAt(Scene scene, Vec3 point) {
        WorldCollider dot = new WorldCollider(null, new Box(point.x, point.y, point.z, point.x, point.y, point.z),
                new Round(point, point.copy(), 0), false);
        for (WorldCollider collider : WorldCollider.all(scene, false)) {
            if (collider.touches(dot, 0)) return true;
        }
        double ground = Ground.heightAt(Ground.terrains(scene), point.x, point.z);
        if (!Double.isNaN(ground) && point.y <= ground) return true;
        return solidTileAt(scene, point);
    }

    // ---------------------------------------------------------------- vector helpers

    private static double component(Vec3 v, int axis) {
        return axis == 0 ? v.x : axis == 1 ? v.y : v.z;
    }

    private static void setComponent(Vec3 v, int axis, double value) {
        if (axis == 0) v.x = value;
        else if (axis == 1) v.y = value;
        else v.z = value;
    }

    private static void addToPosition(Entity entity, int axis, double amount) {
        Vec3 p = entity.transform.position;
        setComponent(p, axis, component(p, axis) + amount);
    }
}
