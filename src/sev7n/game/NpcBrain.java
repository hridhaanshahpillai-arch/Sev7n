package sev7n.game;

import sev7n.math.Vec3;
import sev7n.scene.Animator;
import sev7n.scene.Entity;
import sev7n.scene.Npc;
import sev7n.scene.Rigidbody;
import sev7n.scene.Scene;
import sev7n.scene.SpriteAnimator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The running mind of one NPC: what it sees and remembers, what it's doing, and the path it's
 * walking. Each frame it looks around, decides, then walks.
 *
 * Deciding, most important first:
 *   1. an order from a script (moveTo, follow, chase, flee, stay)
 *   2. reacting to something it sees (the Npc's 'whenSeen')
 *   3. searching where it last saw or heard something
 *   4. its idle behaviour (stand, wander, patrol, routine)
 *
 * Things worth telling scripts about (onSee, onArrive...) go to 'events'.
 */
final class NpcBrain {

    /** Sends a callback (e.g. "onSee", other) to the NPC's behaviours. */
    interface Events {
        void fire(Entity npc, String method, Object... args);
    }

    /** Everything it remembers about someone. */
    static final class Memory {
        Vec3 lastSeen;
        double lastSeenTime;
        double opinion;
        final Map<String, Double> events = new LinkedHashMap<>();
    }

    enum Order { NONE, MOVE, FOLLOW, CHASE, FLEE, STAY }

    private static final double REPATH_SECONDS = 0.6;
    private static final double LOOK_SECONDS = 0.15;

    final Entity self;
    final Npc npc;
    private final Vec3 home;
    private final Random random = new Random();

    final Map<Entity, Memory> memories = new LinkedHashMap<>();
    final Set<Entity> visible = new LinkedHashSet<>();
    private double lookTimer = 0;

    private Order order = Order.NONE;
    private Vec3 orderPoint;
    private Entity orderTarget;

    private String state = "idle";
    private Entity focus;
    private Vec3 searchPoint;
    private double searchUntil;
    private boolean reached;

    private Vec3 goal;
    private List<Vec3> path = new ArrayList<>();
    private double repathTimer;
    private boolean arrived = true;
    private double waitTimer;
    private int patrolIndex;
    private Npc.RoutineStep routineStep;
    private String activity = "";
    private final Vec3 stuckCheck = new Vec3();
    private double stuckTimer;

    NpcBrain(Entity self, Npc npc) {
        this.self = self;
        this.npc = npc;
        this.home = self.transform.position.copy();
    }

    // ---------------------------------------------------------------- orders from scripts

    void moveTo(Vec3 point) {
        give(Order.MOVE, point, null);
    }

    void moveTo(Entity target) {
        give(Order.MOVE, null, target);
    }

    void follow(Entity target) {
        give(Order.FOLLOW, null, target);
    }

    void chase(Entity target) {
        give(Order.CHASE, null, target);
    }

    void flee(Entity target) {
        give(Order.FLEE, null, target);
    }

    void stay() {
        give(Order.STAY, null, null);
    }

    /** Back to doing what its settings say. */
    void resume() {
        give(Order.NONE, null, null);
    }

    private void give(Order next, Vec3 point, Entity target) {
        order = next;
        orderPoint = point == null ? null : point.copy();
        orderTarget = target;
        reached = false;
        goal = null;
        arrived = next == Order.NONE || next == Order.STAY;
    }

    // ---------------------------------------------------------------- what scripts can ask

    String state() {
        return state;
    }

    String activity() {
        return activity;
    }

    Entity focus() {
        return focus;
    }

    boolean arrived() {
        return arrived;
    }

    Memory memoryOf(Entity other) {
        return memories.computeIfAbsent(other, e -> new Memory());
    }

    void remember(Entity other, String event, double opinionChange) {
        Memory memory = memoryOf(other);
        memory.events.merge(event, 1.0, Double::sum);
        memory.opinion = Math.max(-100, Math.min(100, memory.opinion + opinionChange));
    }

    void forget(Entity other) {
        memories.remove(other);
        visible.remove(other);
        if (focus == other) focus = null;
    }

    /** A noise: it turns to look (and goes to see, if it chases things), or runs away if it's a coward. */
    void hear(Vec3 position, Entity source, double time, Events events) {
        events.fire(self, "onHear", position, source);
        if (order != Order.NONE || focus != null && visible.contains(focus)) return;
        if (source != null && source != self && memories.containsKey(source)) {
            memories.get(source).lastSeen = position.copy();
        }
        if (npc.whenSeen.equals("Chase") || npc.whenSeen.equals("Watch")) {
            searchPoint = position.copy();
            searchUntil = time + Math.max(npc.searchTime, 1);
            goal = null;
        } else if (npc.whenSeen.equals("Flee")) {
            searchPoint = null;
            fleeFrom(position);
        }
        face(position.minus(self.transform.position), 360);
    }

    // ---------------------------------------------------------------- every frame

    void update(Scene scene, double dt, double time, double hour, Events events) {
        lookTimer -= dt;
        if (lookTimer <= 0) {
            lookTimer = LOOK_SECONDS;
            look(scene, time, events);
        }
        forgetOld(time);
        decide(scene, time, hour, events);
        walk(scene, dt, events);
    }

    /** Who it can see now: in range, inside its view, and not hidden behind a wall. */
    private void look(Scene scene, double time, Events events) {
        Set<Entity> now = new LinkedHashSet<>();
        if (!npc.notices.isBlank()) {
            for (Entity other : scene.entities()) {
                if (other != self && other.active && other.hasTag(npc.notices) && canSee(scene, other)) now.add(other);
            }
        }
        for (Entity other : now) {
            Memory memory = memoryOf(other);
            memory.lastSeen = other.transform.position.copy();
            memory.lastSeenTime = time;
        }
        for (Entity other : now) {
            if (!visible.contains(other)) events.fire(self, "onSee", other);
        }
        for (Entity other : visible) {
            if (!now.contains(other) && scene.entities().contains(other)) events.fire(self, "onLose", other);
        }
        visible.clear();
        visible.addAll(now);
    }

    boolean canSee(Scene scene, Entity other) {
        Vec3 eye = eye(self);
        Vec3 to = center(other).minus(eye);
        double distance = to.length();
        if (distance > npc.sightRange) return false;
        if (distance < 0.01) return true;
        if (npc.sightAngle < 360) {
            Vec3 flat = new Vec3(to.x, 0, to.z);
            Vec3 forward = self.transform.forward();
            forward.y = 0;
            if (flat.length() > 0.01 && forward.length() > 0.01 && flat.angle(forward) > npc.sightAngle / 2) return false;
        }
        Physics.RayHit hit = Physics.raycast(scene, eye, to, distance, self);
        return hit == null || hit.entity() == other || hit.distance() >= distance - 0.05;
    }

    private void forgetOld(double time) {
        if (npc.memory <= 0) return;
        memories.entrySet().removeIf(e -> !visible.contains(e.getKey()) && e.getValue().lastSeen != null
                && time - e.getValue().lastSeenTime > npc.memory);
    }

    private void decide(Scene scene, double time, double hour, Events events) {
        if (orderTarget != null && !scene.entities().contains(orderTarget)) resume();
        if (focus != null && !scene.entities().contains(focus)) focus = null;

        switch (order) {
            case MOVE -> {
                state = "moving";
                setGoal(orderTarget != null ? standing(orderTarget) : orderPoint, orderTarget != null);
                return;
            }
            case FOLLOW -> {
                state = "following";
                follow(orderTarget, false, events);
                return;
            }
            case CHASE -> {
                state = "chasing";
                focus = orderTarget;
                follow(orderTarget, true, events);
                return;
            }
            case FLEE -> {
                state = "fleeing";
                focus = orderTarget;
                keepFleeing(orderTarget.transform.position);
                return;
            }
            case STAY -> {
                state = "staying";
                stopWalking();
                return;
            }
            default -> { }
        }

        Entity seen = closestVisible();
        if (seen != null && !npc.whenSeen.equals("Ignore")) {
            focus = seen;
            searchPoint = null;
            switch (npc.whenSeen) {
                case "Chase" -> {
                    state = "chasing";
                    follow(seen, true, events);
                }
                case "Flee" -> {
                    state = "fleeing";
                    keepFleeing(seen.transform.position);
                }
                default -> {
                    state = "watching";
                    stopWalking();
                    face(seen.transform.position.minus(self.transform.position), npc.turnSpeed);
                }
            }
            return;
        }

        // lost sight of it: go and look where it was last seen
        if (focus != null && npc.whenSeen.equals("Chase") && memories.containsKey(focus) && searchPoint == null) {
            Memory memory = memories.get(focus);
            if (memory.lastSeen != null && time - memory.lastSeenTime < npc.searchTime) {
                searchPoint = memory.lastSeen.copy();
                searchUntil = memory.lastSeenTime + npc.searchTime;
                goal = null;
            }
        }
        if (searchPoint != null && time < searchUntil) {
            state = "searching";
            if (npc.whenSeen.equals("Watch")) {
                stopWalking();
                face(searchPoint.minus(self.transform.position), npc.turnSpeed);
            } else if (!arrived || goal == null) {
                setGoal(searchPoint, false);
            }
            return;
        }
        if (state.equals("fleeing") && focus != null && !arrived) return; // finish running away first
        if (searchPoint != null || focus != null) {
            searchPoint = null;
            focus = null;
            goal = null;
            waitTimer = npc.pause;
            routineStep = null; // walk back to wherever the routine says
            if (npc.idle.equals("Stand") && !npc.whenSeen.equals("Flee")) setGoal(home, false); // back to its post
        }

        idle(scene, hour, events);
    }

    private void idle(Scene scene, double hour, Events events) {
        state = "idle";
        switch (npc.idle) {
            case "Wander" -> {
                if (!arrived) return;
                if (waitTimer > 0) return;
                Vec3 spot = navigation(scene, null).randomSpot(home, npc.wanderRadius, random);
                if (spot != null) setGoal(spot, false);
                else waitTimer = npc.pause;
            }
            case "Patrol" -> {
                List<Entity> points = patrolPoints(scene);
                if (points.isEmpty()) return;
                if (goal == null) {
                    patrolIndex %= points.size();
                    setGoal(points.get(patrolIndex).transform.position, false);
                } else if (arrived && waitTimer <= 0) {
                    patrolIndex = (patrolIndex + 1) % points.size();
                    setGoal(points.get(patrolIndex).transform.position, false);
                }
            }
            case "Routine" -> {
                state = "routine";
                Npc.RoutineStep step = npc.stepAt(hour);
                if (step != routineStep) {
                    routineStep = step;
                    activity = "";
                    Entity place = step == null ? null : scene.find(step.place);
                    if (place != null) setGoal(place.transform.position, false);
                    else if (step != null) activity = step.activity;
                    if (step != null) events.fire(self, "onRoutine", step.activity, step.place);
                } else if (step != null && arrived) {
                    activity = step.activity;
                }
            }
            default -> { } // stand (finishing any walk it started)
        }
    }

    private void follow(Entity target, boolean run, Events events) {
        double near = npc.stopDistance + reach(target);
        double distance = flatDistance(self.transform.position, target.transform.position);
        boolean sameLevel = Math.abs(feet(self) - feet(target)) < 1; // not just underneath them on a lower floor
        if (distance <= near && sameLevel) {
            stopWalking();
            face(target.transform.position.minus(self.transform.position), npc.turnSpeed);
            if (run && !reached) {
                reached = true;
                events.fire(self, "onReach", target);
            }
            return;
        }
        if (distance > near + 0.75 || !sameLevel) reached = false;
        setGoal(standing(target), true);
        running = run;
    }

    private void keepFleeing(Vec3 threat) {
        running = true;
        if (flatDistance(self.transform.position, threat) > npc.sightRange * 1.25) {
            stopWalking();
            return;
        }
        if (arrived || goal == null || repathTimer <= 0 && flatDistance(goal, threat) < flatDistance(self.transform.position, threat)) {
            fleeFrom(threat);
        }
    }

    private void fleeFrom(Vec3 threat) {
        Vec3 away = self.transform.position.minus(threat);
        away.y = 0;
        if (away.length() < 0.01) away = new Vec3(random.nextDouble() - 0.5, 0, random.nextDouble() - 0.5);
        Vec3 target = self.transform.position.plus(away.normalized().times(6));
        goal = target;
        arrived = false;
        path = new ArrayList<>(List.of(target));
        repathTimer = 0; // the real path is found on the next step
        needsPath = true;
        running = true;
    }

    // ---------------------------------------------------------------- walking

    private boolean running;
    private boolean needsPath;
    private boolean moving;

    /** Sets where to walk. 'moves' means the goal is a moving thing, so the path is refreshed now and then. */
    private void setGoal(Vec3 point, boolean moves) {
        if (point == null) return;
        if (goal != null && flatDistance(goal, point) < (moves ? 0.75 : 0.05) && !(moves && repathTimer <= 0)) return;
        if (goal == null || flatDistance(goal, point) > 0.05) arrived = false;
        goal = point.copy();
        needsPath = true;
        running = state.equals("chasing") || state.equals("fleeing");
    }

    private void stopWalking() {
        path.clear();
        moving = false;
        walkVelocity.set(0, 0, 0); // told to stop: it stops
        Rigidbody body = self.get(Rigidbody.class);
        if (body != null && !body.kinematic) {
            body.velocity.x = 0;
            body.velocity.z = 0;
        }
    }

    private void walk(Scene scene, double dt, Events events) {
        repathTimer -= dt;
        waitTimer -= dt;
        Rigidbody body = self.get(Rigidbody.class);
        boolean physical = body != null && !body.kinematic;

        if (goal != null && !arrived && (needsPath || repathTimer <= 0)) {
            path = navigation(scene, orderTarget != null ? orderTarget : focus).path(self.transform.position, goal);
            repathTimer = REPATH_SECONDS;
            needsPath = false;
        }

        Vec3 position = self.transform.position;
        while (!path.isEmpty() && flatDistance(position, path.get(0)) < Math.max(0.15, path.size() == 1 ? npc.stopDistance : 0.3)) {
            path.remove(0);
        }
        if (goal != null && !arrived && path.isEmpty() && (flatDistance(position, goal) <= npc.stopDistance + 0.05 || !needsPath)) {
            arrived = true;
            waitTimer = npc.pause;
            if (order == Order.MOVE) {
                order = Order.NONE;
                home.set(position); // sent somewhere: that's its new spot to stand at and wander around
                state = "idle";
            }
            events.fire(self, "onArrive");
        }

        moving = !path.isEmpty();
        double speed = running ? npc.runSpeed : npc.speed;
        Vec3 wanted = new Vec3();
        if (moving) {
            Vec3 to = path.get(0).minus(position);
            to.y = 0;
            wanted = to.normalized().plus(avoidOthers(scene).times(0.8));
            if (sidestepTimer > 0) {
                sidestepTimer -= dt;
                wanted = wanted.plus(sidestep.times(1.2)); // stuck on something: edge round it
            }
            wanted.y = 0;
            // slower round sharp corners, so it doesn't run wide while it turns
            double facing = Math.toRadians(self.transform.rotation.y);
            Vec3 ahead = new Vec3(-Math.sin(facing), 0, -Math.cos(facing));
            double turnSlowdown = wanted.length() > 0 ? 0.45 + 0.55 * Math.max(0, ahead.dot(wanted.normalized())) : 1;
            wanted = wanted.length() > 0 ? wanted.normalized().times(speed * turnSlowdown) : wanted;
            face(wanted, npc.turnSpeed * dt);
            checkStuck(dt);
        }
        // speed up and slow down over a moment instead of all at once (no sliding starts and stops)
        double acceleration = Math.max(speed, 1) * 8;
        walkVelocity.set(walkVelocity.moveTowards(wanted, acceleration * dt));
        if (!moving && walkVelocity.length() < 0.05) walkVelocity.set(0, 0, 0);
        Vec3 velocity = walkVelocity.copy();
        if (physical) {
            body.velocity.x = velocity.x;
            body.velocity.z = velocity.z;
        } else {
            position.set(position.plus(velocity.times(dt)));
            if (moving) {
                // no physics to hold it up: follow the floors of the path (stairs, platforms)
                double aboveFeet = position.y - feet(self);
                position.y = new Vec3(0, position.y, 0).moveTowards(new Vec3(0, path.get(0).y + aboveFeet, 0), Math.max(speed, 2) * dt).y;
            }
        }
        animate();
    }

    /** A push away from other characters that are very close, so crowds don't walk through each other. */
    private Vec3 avoidOthers(Scene scene) {
        Vec3 push = new Vec3();
        for (Entity other : scene.entities()) {
            if (other == self || !other.active || !(other.has(Npc.class) || other.has(Rigidbody.class))) continue;
            Vec3 away = self.transform.position.minus(other.transform.position);
            away.y = 0;
            double distance = away.length();
            double personalSpace = 1.0;
            if (distance > 0.001 && distance < personalSpace && other != orderTarget && other != focus) {
                push = push.plus(away.normalized().times((personalSpace - distance) / personalSpace));
            }
        }
        return push;
    }

    /**
     * If it's barely moved for a while, it's stuck on something: find a new path. Stuck again
     * straight after (on another character, or a box that isn't in the map): step to one side for a moment.
     */
    private void checkStuck(double dt) {
        stuckTimer += dt;
        if (stuckTimer < 1) return;
        if (flatDistance(stuckCheck, self.transform.position) < 0.1) {
            needsPath = true;
            if (++stuckCount >= 2 && !path.isEmpty()) {
                Vec3 to = path.get(0).minus(self.transform.position);
                Vec3 side = new Vec3(-to.z, 0, to.x);
                if (side.length() > 1e-6) {
                    sidestep.set(side.normalized().times(Math.random() < 0.5 ? 1 : -1));
                    sidestepTimer = 0.7;
                }
            }
        } else {
            stuckCount = 0;
        }
        stuckCheck.set(self.transform.position);
        stuckTimer = 0;
    }

    private int stuckCount;
    private final Vec3 sidestep = new Vec3();
    private double sidestepTimer;
    private final Vec3 walkVelocity = new Vec3();

    /** Turns toward a direction (flat), by at most 'maxDegrees'. */
    private void face(Vec3 direction, double maxDegrees) {
        if (Math.hypot(direction.x, direction.z) < 0.001) return;
        double wanted = Math.toDegrees(Math.atan2(-direction.x, -direction.z));
        double current = self.transform.rotation.y;
        double difference = ((wanted - current) % 360 + 540) % 360 - 180;
        self.transform.rotation.y = current + Math.max(-maxDegrees, Math.min(maxDegrees, difference));
    }

    /** An animation a script started (getting up, attacking...): the NPC leaves it playing until it ends. */
    private String heldAnimation;

    void hold(String clip) {
        heldAnimation = clip;
    }

    private void animate() {
        Animator held = heldAnimation == null ? null : self.get(Animator.class);
        if (held != null && held.current().equals(heldAnimation) && !held.playedThrough()) return;
        heldAnimation = null;
        String clip = !moving ? npc.idleAnimation : running && !npc.runAnimation.isBlank() ? npc.runAnimation : npc.walkAnimation;
        if (clip.isBlank()) return;
        Animator model = self.get(Animator.class);
        if (model != null) {
            model.play(clip);
            return;
        }
        SpriteAnimator sprite = self.get(SpriteAnimator.class);
        if (sprite != null && !clip.equals(sprite.current())) sprite.play(clip);
    }

    // ---------------------------------------------------------------- helpers

    private Navigation navigation(Scene scene, Entity ignore) {
        return new Navigation(scene, self, ignore);
    }

    private Entity closestVisible() {
        Entity best = null;
        for (Entity other : visible) {
            if (best == null || flatDistance(self.transform.position, other.transform.position)
                    < flatDistance(self.transform.position, best.transform.position)) best = other;
        }
        return best;
    }

    private List<Entity> patrolPoints(Scene scene) {
        List<Entity> points = new ArrayList<>();
        for (String name : npc.patrol.split(",")) {
            Entity point = name.isBlank() ? null : scene.find(name.trim());
            if (point != null) points.add(point);
        }
        return points;
    }

    /** How far from someone's center it stops: their size plus its own. */
    private double reach(Entity other) {
        return halfWidth(self) + halfWidth(other);
    }

    private static double halfWidth(Entity e) {
        WorldCollider collider = WorldCollider.of(e);
        if (collider == null) return 0.4;
        Box b = collider.bounds();
        return Math.min(b.maxX() - b.minX(), b.maxZ() - b.minZ()) / 2;
    }

    private static Vec3 eye(Entity e) {
        WorldCollider collider = WorldCollider.of(e);
        if (collider == null) return e.transform.position.plus(new Vec3(0, 1.5, 0));
        Box b = collider.bounds();
        return new Vec3((b.minX() + b.maxX()) / 2, b.minY() + (b.maxY() - b.minY()) * 0.85, (b.minZ() + b.maxZ()) / 2);
    }

    /** The bottom of an entity's collider (its feet), or its position without one. */
    private static double feet(Entity e) {
        WorldCollider collider = WorldCollider.of(e);
        return collider == null ? e.transform.position.y : collider.bounds().minY();
    }

    /** Where an entity is standing: under its middle, at its feet. */
    private static Vec3 standing(Entity e) {
        return new Vec3(e.transform.position.x, feet(e), e.transform.position.z);
    }

    private static Vec3 center(Entity e) {
        WorldCollider collider = WorldCollider.of(e);
        if (collider == null) return e.transform.position.copy();
        Box b = collider.bounds();
        return new Vec3((b.minX() + b.maxX()) / 2, (b.minY() + b.maxY()) / 2, (b.minZ() + b.maxZ()) / 2);
    }

    static double flatDistance(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }
}
