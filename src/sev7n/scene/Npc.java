package sev7n.scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Makes an entity a character with a mind of its own: it walks around walls to where it
 * wants to go, sees and hears things, remembers who did what, and follows a daily routine.
 *
 * What it does when nothing is happening is 'idle' (stand, wander, patrol or follow its
 * routine). What it does when it notices something tagged 'notices' is 'whenSeen'
 * (ignore, chase, flee or watch). Scripts can take over at any time with entity.moveTo(...).
 */
public final class Npc extends Component {

    public static final List<String> IDLE_MODES = List.of("Stand", "Wander", "Patrol", "Routine");
    public static final List<String> REACTIONS = List.of("Ignore", "Chase", "Flee", "Watch");

    // moving
    /** Walking speed, units per second. */
    public double speed = 2.5;
    /** Speed when chasing or fleeing. */
    public double runSpeed = 5;
    /** How fast it turns to face where it's going, degrees per second. */
    public double turnSpeed = 540;
    /** How close counts as "there". */
    public double stopDistance = 0.4;

    // when nothing is happening
    public String idle = "Stand";
    /** How far from its starting point it wanders. */
    public double wanderRadius = 6;
    /** Names of the entities it walks between, in order, separated by commas. */
    public String patrol = "";
    /** Seconds it waits at each patrol point or wander spot. */
    public double pause = 1.5;

    // senses
    /** It notices entities with this tag (e.g. "player"). Empty = notices nothing. */
    public String notices = "player";
    public String whenSeen = "Ignore";
    /** How far it can see. */
    public double sightRange = 12;
    /** How wide it can see, in degrees (360 = eyes in the back of its head). */
    public double sightAngle = 120;
    /** How far away it hears a noise of loudness 1 (see makeNoise). */
    public double hearing = 10;
    /** Seconds it keeps looking for something it lost sight of before giving up. */
    public double searchTime = 6;
    /** Seconds before it forgets someone it hasn't seen (0 = never forgets). */
    public double memory = 0;

    // animations (on the same entity's Animator or Sprite Animator), empty = don't change
    public String idleAnimation = "";
    public String walkAnimation = "";
    public String runAnimation = "";

    /** Its day: at each hour, it goes to a place (an entity's name) and does an activity there. */
    public final List<RoutineStep> routine = new ArrayList<>();

    /** One line of a routine, e.g. at 8:00 go to "Forge" and "work". */
    public static final class RoutineStep {
        public double hour;
        public String place;
        public String activity;

        public RoutineStep(double hour, String place, String activity) {
            this.hour = hour;
            this.place = place;
            this.activity = activity;
        }
    }

    /** The routine step that applies at this hour (the latest one that has started, wrapping past midnight), or null. */
    public RoutineStep stepAt(double hour) {
        RoutineStep best = null;
        RoutineStep latest = null;
        for (RoutineStep step : routine) {
            if (step.hour <= hour && (best == null || step.hour > best.hour)) best = step;
            if (latest == null || step.hour > latest.hour) latest = step;
        }
        return best != null ? best : latest;
    }

    @Override
    public String type() {
        return "Npc";
    }

    @Override
    public String displayName() {
        return "NPC";
    }

    @Override
    public void save(Map<String, Object> out) {
        out.put("speed", speed);
        out.put("runSpeed", runSpeed);
        out.put("turnSpeed", turnSpeed);
        out.put("stopDistance", stopDistance);
        out.put("idle", idle);
        out.put("wanderRadius", wanderRadius);
        out.put("patrol", patrol);
        out.put("pause", pause);
        out.put("notices", notices);
        out.put("whenSeen", whenSeen);
        out.put("sightRange", sightRange);
        out.put("sightAngle", sightAngle);
        out.put("hearing", hearing);
        out.put("searchTime", searchTime);
        out.put("memory", memory);
        out.put("idleAnimation", idleAnimation);
        out.put("walkAnimation", walkAnimation);
        out.put("runAnimation", runAnimation);
        List<Object> steps = new ArrayList<>();
        for (RoutineStep step : routine) steps.add(Map.of("hour", step.hour, "place", step.place, "activity", step.activity));
        out.put("routine", steps);
    }

    @Override
    public void load(Map<String, Object> in) {
        speed = Json.number(in, "speed", speed);
        runSpeed = Json.number(in, "runSpeed", runSpeed);
        turnSpeed = Json.number(in, "turnSpeed", turnSpeed);
        stopDistance = Json.number(in, "stopDistance", stopDistance);
        idle = Json.string(in, "idle", idle);
        wanderRadius = Json.number(in, "wanderRadius", wanderRadius);
        patrol = Json.string(in, "patrol", patrol);
        pause = Json.number(in, "pause", pause);
        notices = Json.string(in, "notices", notices);
        whenSeen = Json.string(in, "whenSeen", whenSeen);
        sightRange = Json.number(in, "sightRange", sightRange);
        sightAngle = Json.number(in, "sightAngle", sightAngle);
        hearing = Json.number(in, "hearing", hearing);
        searchTime = Json.number(in, "searchTime", searchTime);
        memory = Json.number(in, "memory", memory);
        idleAnimation = Json.string(in, "idleAnimation", idleAnimation);
        walkAnimation = Json.string(in, "walkAnimation", walkAnimation);
        runAnimation = Json.string(in, "runAnimation", runAnimation);
        routine.clear();
        for (Object item : Json.list(in, "routine")) {
            Map<String, Object> step = Json.object(item);
            routine.add(new RoutineStep(Json.number(step, "hour", 0), Json.string(step, "place", ""), Json.string(step, "activity", "")));
        }
    }
}
