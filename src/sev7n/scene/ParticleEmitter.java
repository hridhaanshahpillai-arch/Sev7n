package sev7n.scene;

import sev7n.math.Vec3;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A particle effect: fire, smoke, sparks, explosions, rain, flying debris...
 *
 * It sends out particles (soft dots, pictures or 3D models) from a shape (cone, sphere, box...).
 * They fly, fall, spin, bounce off things, grow or shrink, and fade from one color to another
 * before they disappear. Particles live in the world, so moving the emitter leaves a trail.
 * Start from a preset (see PRESETS), then tweak.
 *
 * Particles aren't saved: they're made while the game runs (and while previewing in the editor).
 */
public final class ParticleEmitter extends Component {

    public static final List<String> PRESETS = List.of(
            "Fire", "Smoke", "Sparks", "Magic", "Explosion", "Fountain", "Debris", "Rain", "Snow", "Confetti");
    private static final double GRAVITY = 9.8;
    private static final double STOP_BOUNCING = 0.4; // slower bounces than this just stop

    /** Where particles start, and which way they fly. */
    public enum Shape {
        CONE("Cone", "From a circle (Radius), flying along Direction and fanning out by Spread"),
        SPHERE("Sphere", "From inside a ball (Radius), flying outward"),
        HEMISPHERE("Hemisphere", "From the top half of a ball (Radius), flying outward and up"),
        BOX("Box", "From anywhere inside a box (Box size), flying along Direction (rain, snow)"),
        CIRCLE("Circle", "From a flat ring (Radius), flying outward along the ground (shockwaves)"),
        LINE("Line", "From a line (Length) along the object's X, flying along Direction (a row of jets)");

        public final String label;
        public final String description;

        Shape(String label, String description) {
            this.label = label;
            this.description = description;
        }
    }

    /** What each particle looks like. */
    public enum Look {
        DOT("Dots", "Flat cards facing the camera: a soft dot, or the Picture"),
        MODEL("Models", "A 3D shape or imported model, lit like other objects (they shrink rather than fade)");

        public final String label;
        public final String description;

        Look(String label, String description) {
            this.label = label;
            this.description = description;
        }
    }

    /** "Is this point inside something solid?", for particles bouncing off things. */
    @FunctionalInterface
    public interface Solid {
        boolean at(double x, double y, double z);
    }

    // ---- main
    /** Starts by itself when the game starts. */
    public boolean playOnStart = true;
    /** Keeps going forever; otherwise it emits for 'duration' seconds and stops. */
    public boolean loop = true;
    public double duration = 1;
    public int maxParticles = 1000;
    /** Removes the whole entity once it has stopped and its last particle is gone (one-off effects). */
    public boolean destroyWhenDone = false;

    // ---- emission
    /** Particles per second while emitting. */
    public double rate = 20;
    /** Particles sent out all at once when it starts (explosions). */
    public int burst = 0;

    // ---- shape (in the emitter's own space: it turns with the entity)
    public Shape shape = Shape.CONE;
    public double radius = 0;
    public final Vec3 boxSize = new Vec3(1, 1, 1);
    public double length = 2;
    /** Which way particles fly (cone, box and line). */
    public final Vec3 direction = new Vec3(0, 1, 0);
    /** How far from their direction they may go, in degrees: 0 = straight, 180 = any way. */
    public double spread = 25;

    // ---- movement
    /** Seconds each particle lives, plus or minus 'lifetimeRandom' (0..1, a fraction). */
    public double lifetime = 1.5;
    public double lifetimeRandom = 0.3;
    public double speed = 2;
    public double speedRandom = 0.3;
    /** 1 = falls like normal things, 0 = floats, negative = rises (fire, smoke). */
    public double gravity = 0;
    /** Slows particles down over time, like air. */
    public double drag = 0;

    // ---- color & size over each particle's life
    public double startSize = 0.3;
    public double endSize = 0.1;
    /** Colors fade from start to end, see-through amount (alpha) included. */
    public Color startColor = new Color(255, 255, 255, 255);
    public Color endColor = new Color(255, 255, 255, 0);
    /** Every particle gets its own bright color (confetti). */
    public boolean randomColors = false;

    // ---- rotation
    /** Each particle starts turned a random way. */
    public boolean randomRotation = false;
    /** Degrees per second each particle spins, plus or minus 'spinRandom' (a fraction). */
    public double spin = 0;
    public double spinRandom = 0.5;

    // ---- collision
    /** Particles bounce off solid things: colliders that aren't triggers, solid tilemaps and the floor. */
    public boolean collide = false;
    /** Also bounce off a flat floor at 'floorHeight' (world height), even where there's no collider. */
    public boolean floor = true;
    public double floorHeight = 0;
    /** How much speed is kept after a bounce: 0 = stops dead, 1 = bounces right back. */
    public double bounce = 0.4;
    /** How much of its life a particle loses each time it hits something: 1 = it disappears. */
    public double lifeLoss = 0;

    // ---- renderer
    public Look look = Look.DOT;
    /** Glowing dots add light where they overlap (fire, sparks) instead of covering each other (smoke). */
    public boolean glow = false;
    /** A picture for each dot, or "" for a soft round dot. */
    public String texture = "";
    /** The model each particle is when look is MODEL: a built-in shape or an imported model path. */
    public String model = "cube";

    /** One particle, in world space. */
    public static final class Particle {
        public double x, y, z;
        double vx, vy, vz;
        double age;
        double life;
        /** Degrees it's turned, around its own spin axis. */
        public double angle;
        double spinSpeed;
        /** The axis a model particle spins around (length 1). */
        public double axisX, axisY = 1, axisZ;
        Color color; // its own color when randomColors is on, else null

        /** 0 when it's born, 1 when it disappears. */
        public double progress() {
            return Math.min(1, age / life);
        }
    }

    // ---- while running (not saved)
    private final List<Particle> particles = new ArrayList<>();
    private final Random random = new Random();
    private boolean started;
    private boolean emitting;
    private double emitTime;
    private double toEmit;

    public ParticleEmitter() { }

    /** Starts a new emitter from one of the PRESETS. */
    public static ParticleEmitter preset(String name) {
        ParticleEmitter emitter = new ParticleEmitter();
        emitter.applyPreset(name);
        return emitter;
    }

    // ---------------------------------------------------------------- playing

    public List<Particle> particles() {
        return Collections.unmodifiableList(particles);
    }

    /** Starts emitting from the beginning (a burst, then 'rate' per second). */
    public void play() {
        started = true;
        emitting = true;
        emitTime = 0;
        toEmit = burst;
    }

    /** Stops sending out new particles; the ones already out finish their lives. */
    public void stop() {
        started = true;
        emitting = false;
    }

    public boolean emitting() {
        return emitting;
    }

    /** True once it has stopped emitting and every particle is gone. */
    public boolean done() {
        return started && !emitting && particles.isEmpty();
    }

    /** Clears every particle and the started state, as if just loaded. */
    public void reset() {
        particles.clear();
        started = false;
        emitting = false;
    }

    /** Sends out some particles right now, from where the emitter is. */
    public void emit(int count, Transform transform) {
        for (int i = 0; i < count && particles.size() < maxParticles; i++) particles.add(spawn(transform));
    }

    /** Moves time forward without anything solid but the floor to bounce off. */
    public void update(double dt, Transform transform) {
        update(dt, transform, null);
    }

    /**
     * Moves time forward: emits new particles, moves the living ones (bouncing off 'solids' if
     * collision is on; null for nothing but the floor), and removes the old ones.
     */
    public void update(double dt, Transform transform, Solid solids) {
        if (!started && playOnStart) play();
        if (emitting) {
            emitTime += dt;
            toEmit += rate * dt;
            int whole = (int) toEmit;
            toEmit -= whole;
            emit(whole, transform);
            if (!loop && emitTime >= duration) emitting = false;
        }

        double fall = gravity * GRAVITY * dt;
        double slow = Math.max(0, 1 - drag * dt);
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.age += dt;
            if (p.age >= p.life) {
                particles.set(i, particles.get(particles.size() - 1)); // order doesn't matter: drawing sorts them
                particles.remove(particles.size() - 1);
                continue;
            }
            p.vy -= fall;
            p.vx *= slow;
            p.vy *= slow;
            p.vz *= slow;
            p.angle += p.spinSpeed * dt;
            if (collide) moveAndBounce(p, dt, solids);
            else {
                p.x += p.vx * dt;
                p.y += p.vy * dt;
                p.z += p.vz * dt;
            }
        }
    }

    /** Moves one axis at a time; an axis that would go into something solid bounces back instead. */
    private void moveAndBounce(Particle p, double dt, Solid solids) {
        boolean hit = false;
        double nx = p.x + p.vx * dt;
        if (solidAt(nx, p.y, p.z, solids)) {
            p.vx = -p.vx * bounce;
            hit = true;
        } else {
            p.x = nx;
        }
        double ny = p.y + p.vy * dt;
        if (solidAt(p.x, ny, p.z, solids)) {
            p.vy = Math.abs(p.vy) < STOP_BOUNCING ? 0 : -p.vy * bounce;
            p.vx *= 0.85; // a little friction on the ground
            p.vz *= 0.85;
            hit |= Math.abs(p.vy) > 0;
        } else {
            p.y = ny;
        }
        double nz = p.z + p.vz * dt;
        if (solidAt(p.x, p.y, nz, solids)) {
            p.vz = -p.vz * bounce;
            hit = true;
        } else {
            p.z = nz;
        }
        if (hit && lifeLoss > 0) p.age += p.life * lifeLoss;
    }

    private boolean solidAt(double x, double y, double z, Solid solids) {
        return (floor && y < floorHeight) || (solids != null && solids.at(x, y, z));
    }

    // ---------------------------------------------------------------- shapes

    private Particle spawn(Transform transform) {
        Particle p = new Particle();
        Vec3 aim = direction.length() == 0 ? new Vec3(0, 1, 0) : direction.normalized();
        Vec3 position; // where it starts, in the emitter's own space
        Vec3 heading;  // which way it flies, before spread
        switch (shape) {
            case SPHERE, HEMISPHERE -> {
                Vec3 ball = randomInBall();
                if (shape == Shape.HEMISPHERE) ball.y = Math.abs(ball.y);
                position = ball.times(radius);
                heading = ball.length() < 1e-6 ? randomInCone(new Vec3(0, 1, 0), Math.PI) : ball.normalized();
            }
            case BOX -> {
                position = new Vec3((random.nextDouble() - 0.5) * boxSize.x, (random.nextDouble() - 0.5) * boxSize.y,
                        (random.nextDouble() - 0.5) * boxSize.z);
                heading = aim;
            }
            case CIRCLE -> {
                double angle = random.nextDouble() * Math.PI * 2;
                double r = radius * Math.sqrt(random.nextDouble());
                position = new Vec3(Math.cos(angle) * r, 0, Math.sin(angle) * r);
                heading = new Vec3(Math.cos(angle), 0, Math.sin(angle));
            }
            case LINE -> {
                position = new Vec3((random.nextDouble() - 0.5) * length, 0, 0);
                heading = aim;
            }
            default -> { // CONE: from a circle facing the direction
                Vec3[] basis = basis(aim);
                double angle = random.nextDouble() * Math.PI * 2;
                double r = radius * Math.sqrt(random.nextDouble());
                position = basis[0].times(Math.cos(angle) * r).plus(basis[1].times(Math.sin(angle) * r));
                heading = aim;
            }
        }
        heading = randomInCone(heading, Math.toRadians(Math.min(180, Math.max(0, spread))));

        Vec3 world = transform.position.plus(transform.direction(position.x, position.y, position.z));
        Vec3 flying = transform.direction(heading.x, heading.y, heading.z);
        double velocity = speed * (1 + speedRandom * (random.nextDouble() * 2 - 1));
        p.x = world.x;
        p.y = world.y;
        p.z = world.z;
        p.vx = flying.x * velocity;
        p.vy = flying.y * velocity;
        p.vz = flying.z * velocity;
        p.life = Math.max(0.05, lifetime * (1 + lifetimeRandom * (random.nextDouble() * 2 - 1)));
        if (randomColors) p.color = Color.getHSBColor(random.nextFloat(), 0.65f, 1f);
        if (randomRotation) p.angle = random.nextDouble() * 360;
        p.spinSpeed = spin * (1 + spinRandom * (random.nextDouble() * 2 - 1)) * (random.nextBoolean() ? 1 : -1);
        Vec3 axis = randomInCone(new Vec3(0, 1, 0), Math.PI);
        p.axisX = axis.x;
        p.axisY = axis.y;
        p.axisZ = axis.z;
        return p;
    }

    private Vec3 randomInBall() {
        while (true) {
            Vec3 v = new Vec3(random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1);
            if (v.dot(v) <= 1) return v;
        }
    }

    /** Two directions at right angles to 'axis' and to each other. */
    private static Vec3[] basis(Vec3 axis) {
        Vec3 w = axis.normalized();
        Vec3 u = (Math.abs(w.x) > 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).cross(w).normalized();
        return new Vec3[] {u, w.cross(u)};
    }

    /** A random direction at most 'angle' radians away from 'axis' (spread evenly over that part of a sphere). */
    private Vec3 randomInCone(Vec3 axis, double angle) {
        double cosTheta = 1 - random.nextDouble() * (1 - Math.cos(angle));
        double sinTheta = Math.sqrt(Math.max(0, 1 - cosTheta * cosTheta));
        double phi = random.nextDouble() * Math.PI * 2;
        Vec3[] uv = basis(axis);
        return uv[0].times(Math.cos(phi) * sinTheta).plus(uv[1].times(Math.sin(phi) * sinTheta)).plus(axis.normalized().times(cosTheta));
    }

    // ---------------------------------------------------------------- how a particle looks right now

    public double size(Particle p) {
        return startSize + (endSize - startSize) * p.progress();
    }

    public Color color(Particle p) {
        double t = p.progress();
        Color start = p.color != null ? withAlpha(p.color, startColor.getAlpha()) : startColor;
        Color end = p.color != null ? withAlpha(p.color, endColor.getAlpha()) : endColor;
        return new Color(mix(start.getRed(), end.getRed(), t), mix(start.getGreen(), end.getGreen(), t),
                mix(start.getBlue(), end.getBlue(), t), mix(start.getAlpha(), end.getAlpha(), t));
    }

    private static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    private static int mix(int a, int b, double t) {
        return (int) Math.round(a + (b - a) * t);
    }

    // ---------------------------------------------------------------- presets

    /** Sets everything for a look; unknown names change nothing. */
    public void applyPreset(String name) {
        if (!PRESETS.contains(name)) return;
        copyFrom(new ParticleEmitter()); // start from the defaults
        switch (name) {
            case "Fire" -> set(70, 0.8, 1.8, 10, -0.2, 0.6, 0.75, 0.1, new Color(255, 210, 90, 255), new Color(230, 50, 30, 0), true, 0.25);
            case "Smoke" -> set(12, 3, 0.8, 15, -0.05, 0.3, 0.4, 1.6, new Color(130, 130, 140, 170), new Color(80, 80, 90, 0), false, 0.2);
            case "Sparks" -> {
                set(80, 0.7, 5, 45, 1, 0.2, 0.13, 0.04, new Color(255, 240, 160, 255), new Color(255, 120, 40, 0), true, 0);
                speedRandom = 0.4;
            }
            case "Magic" -> {
                set(30, 1.5, 0.6, 180, -0.05, 1, 0.25, 0, new Color(191, 158, 238, 255), new Color(151, 225, 241, 0), true, 0.5);
                shape = Shape.SPHERE;
            }
            case "Explosion" -> {
                set(0, 0.9, 6, 0, 0.3, 2.5, 0.7, 0.15, new Color(255, 230, 140, 255), new Color(200, 60, 40, 0), true, 0.1);
                shape = Shape.SPHERE;
                burst = 90;
                loop = false;
                duration = 0.1;
                speedRandom = 0.5;
                lifetimeRandom = 0.4;
            }
            case "Fountain" -> {
                set(80, 2.5, 7, 8, 1, 0, 0.12, 0.08, new Color(150, 200, 255, 230), new Color(150, 200, 255, 0), false, 0.1);
                collide = true;
                bounce = 0.3;
            }
            case "Debris" -> {
                set(0, 3, 6, 35, 1, 0.2, 0.22, 0.12, new Color(150, 150, 160, 255), new Color(110, 110, 120, 255), false, 0.2);
                shape = Shape.HEMISPHERE;
                look = Look.MODEL;
                burst = 40;
                loop = false;
                duration = 0.1;
                collide = true;
                randomRotation = true;
                spin = 360;
                speedRandom = 0.5;
            }
            case "Rain" -> {
                set(220, 1.2, 12, 2, 0, 0, 0.06, 0.06, new Color(150, 190, 255, 190), new Color(150, 190, 255, 140), false, 0);
                shape = Shape.BOX;
                boxSize.set(16, 0.2, 16);
                direction.set(0, -1, 0);
            }
            case "Snow" -> {
                set(40, 6, 0.6, 25, 0, 0, 0.12, 0.12, new Color(255, 255, 255, 235), new Color(255, 255, 255, 0), false, 0);
                shape = Shape.BOX;
                boxSize.set(16, 0.2, 16);
                direction.set(0, -1, 0);
            }
            case "Confetti" -> {
                set(0, 3, 6, 35, 0.6, 1.2, 0.15, 0.15, new Color(255, 255, 255, 255), new Color(255, 255, 255, 0), false, 0.1);
                burst = 120;
                loop = false;
                duration = 0.1;
                randomColors = true;
                randomRotation = true;
                spin = 300;
                speedRandom = 0.4;
            }
            default -> { }
        }
    }

    private void set(double rate, double lifetime, double speed, double spread, double gravity, double drag,
                     double startSize, double endSize, Color startColor, Color endColor, boolean glow, double radius) {
        this.rate = rate;
        this.lifetime = lifetime;
        this.speed = speed;
        this.spread = spread;
        this.gravity = gravity;
        this.drag = drag;
        this.startSize = startSize;
        this.endSize = endSize;
        this.startColor = startColor;
        this.endColor = endColor;
        this.glow = glow;
        this.radius = radius;
    }

    private void copyFrom(ParticleEmitter other) {
        Map<String, Object> data = new LinkedHashMap<>();
        other.save(data);
        load(data);
    }

    // ---------------------------------------------------------------- saving

    @Override
    public String type() {
        return "ParticleEmitter";
    }

    @Override
    public String displayName() {
        return "Particle Effect";
    }

    @Override
    public void save(Map<String, Object> out) {
        out.put("playOnStart", playOnStart);
        out.put("loop", loop);
        out.put("duration", duration);
        out.put("maxParticles", maxParticles);
        if (destroyWhenDone) out.put("destroyWhenDone", true);
        out.put("rate", rate);
        out.put("burst", burst);
        out.put("shape", shape.name());
        out.put("radius", radius);
        out.put("boxSize", Json.vec3(boxSize));
        out.put("length", length);
        out.put("direction", Json.vec3(direction));
        out.put("spread", spread);
        out.put("lifetime", lifetime);
        out.put("lifetimeRandom", lifetimeRandom);
        out.put("speed", speed);
        out.put("speedRandom", speedRandom);
        out.put("gravity", gravity);
        out.put("drag", drag);
        out.put("startSize", startSize);
        out.put("endSize", endSize);
        out.put("startColor", Json.color(startColor));
        out.put("endColor", Json.color(endColor));
        out.put("randomColors", randomColors);
        out.put("randomRotation", randomRotation);
        out.put("spin", spin);
        out.put("spinRandom", spinRandom);
        out.put("collide", collide);
        out.put("floor", floor);
        out.put("floorHeight", floorHeight);
        out.put("bounce", bounce);
        out.put("lifeLoss", lifeLoss);
        out.put("look", look.name());
        out.put("glow", glow);
        out.put("texture", texture);
        out.put("model", model);
    }

    @Override
    public void load(Map<String, Object> in) {
        playOnStart = Json.bool(in, "playOnStart", playOnStart);
        loop = Json.bool(in, "loop", loop);
        duration = Json.number(in, "duration", duration);
        maxParticles = (int) Json.number(in, "maxParticles", maxParticles);
        destroyWhenDone = Json.bool(in, "destroyWhenDone", destroyWhenDone);
        rate = Json.number(in, "rate", rate);
        burst = (int) Json.number(in, "burst", burst);
        shape = choice(Shape.class, Json.string(in, "shape", shape.name()), shape);
        radius = Json.number(in, "radius", radius);
        Json.vec3(in, "boxSize", boxSize);
        length = Json.number(in, "length", length);
        Json.vec3(in, "direction", direction);
        spread = Json.number(in, "spread", spread);
        lifetime = Json.number(in, "lifetime", lifetime);
        lifetimeRandom = Json.number(in, "lifetimeRandom", lifetimeRandom);
        speed = Json.number(in, "speed", speed);
        speedRandom = Json.number(in, "speedRandom", speedRandom);
        gravity = Json.number(in, "gravity", gravity);
        drag = Json.number(in, "drag", drag);
        startSize = Json.number(in, "startSize", startSize);
        endSize = Json.number(in, "endSize", endSize);
        startColor = Json.color(in, "startColor", startColor);
        endColor = Json.color(in, "endColor", endColor);
        randomColors = Json.bool(in, "randomColors", randomColors);
        randomRotation = Json.bool(in, "randomRotation", randomRotation);
        spin = Json.number(in, "spin", spin);
        spinRandom = Json.number(in, "spinRandom", spinRandom);
        collide = Json.bool(in, "collide", collide);
        floor = Json.bool(in, "floor", floor);
        floorHeight = Json.number(in, "floorHeight", floorHeight);
        bounce = Json.number(in, "bounce", bounce);
        lifeLoss = Json.number(in, "lifeLoss", lifeLoss);
        look = choice(Look.class, Json.string(in, "look", look.name()), look);
        glow = Json.bool(in, "glow", glow);
        texture = Json.string(in, "texture", texture);
        model = Json.string(in, "model", model);
    }

    /** An enum value by name; unknown names (from a newer version) keep the current value. */
    private static <E extends Enum<E>> E choice(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
