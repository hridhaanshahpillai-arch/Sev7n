# Physics (v0.5 alpha)

Physics makes things fall, land, roll, bounce and push each other without you writing the math.

## The pieces

| Component | Role |
|-----------|------|
| **Rigidbody** | Moves by physics: gravity pulls it, forces push it, it has a velocity and a mass |
| **Box Collider** | A box shape for collisions: crates, walls, floors, platformer characters |
| **Sphere Collider** | A ball: balls, bullets, round pickups. Rolls off edges and bounces the round way |
| **Capsule Collider** | A pill standing up: the usual shape for 3D characters, because it slides smoothly along walls and corners |
| any collider with **Trigger** ticked | Never solid; things pass through, but `onCollide` still happens (coins, checkpoints, danger zones) |

A collider without a Rigidbody is part of the world: a floor, a wall, a platform that never
moves. Solid tilemaps count as world too (see [2D games](2D.md)).

So a falling crate is a Box Collider plus a Rigidbody, and the floor it lands on is just a
Box Collider. Objects without a Rigidbody still move however your scripts move them.

## Rigidbody settings

| Setting | What it does |
|---------|--------------|
| **Mass** | How heavy it is. When two Rigidbodies bump, the heavier one pushes the lighter one aside; forces move heavy things less |
| **Gravity** | 1 = normal, 0 = floats (top-down games, space), 2 = falls twice as hard, -1 = falls up |
| **Bounciness** | 0 = stops dead when it lands, 1 = bounces back up just as fast |
| **Friction** | How quickly sliding stops: 0 = ice, 1 = rubber |
| **Drag** | Slows it down over time, like air resistance |
| **Kinematic** | Moved only by scripts: gravity and bumps don't move it, but it still pushes others. For moving platforms, doors and elevators |
| **Lock X / Y / Z** | Never moves along that axis. Lock Z for 2D games, lock Y for something that slides along the ground |
| **Start velocity** | How fast it's already moving when the game starts |

## Collider settings

| Setting | What it does |
|---------|--------------|
| **Size** (box), **Radius** (sphere, capsule), **Height** (capsule, ends included) | The shape, relative to the object's scale |
| **Offset** | Moves the shape, e.g. down to a character's feet |
| **Trigger** | Not solid (see above) |
| **Fit to shape** | Sizes the collider to the object's mesh or sprite |

Turn on **Show colliders** in Settings to see them in the Scene view (green solid, yellow triggers).

## In scripts

```seven
entity.velocity                 // how fast it's moving (a vec3 you can change)
entity.velocity.y = 7           // jump
entity.push(vec3(5, 0, 0))      // add to the velocity, whatever the mass
entity.addForce(vec3(0, 20, 0)) // a steady push, every frame (an engine, wind); heavier things speed up less
entity.addImpulse(vec3(8, 4, 0))// a sudden kick (a punch, an explosion); heavier things move less
entity.mass = 5                 // heavier
entity.kinematic = true         // only scripts move it now
entity.grounded                 // true while it stands on something

setGravity(vec3(0, -20, 0))     // stronger gravity for the whole game
gravity()                       // the current gravity
solidAt(vec3(2, -0.5, 0))       // is there something solid at this point? (ground ahead?)
```

### Raycasts: "what's in that direction?"

```seven
var hit = raycast(transform.position, forward(), 50, entity)   // from, direction, how far, ignore (yourself)
if hit != none {
    print(hit.entity.name)      // what it hit (none for a tile)
    hit.point                   // where
    hit.normal                  // which way that surface faces
    hit.distance                // how far away
}
```

Use them for shooting, line of sight ("can the enemy see me?"), checking the ground under
a character, or placing things on surfaces. Triggers are ignored.

```seven
var near = overlapSphere(transform.position, 3)   // every entity within 3 units (triggers too)
for thing in near { if thing.hasTag("enemy") { thing.addImpulse(vec3(0, 6, 0)) } }
```

`onCollide(other)` runs once when two colliders start touching, including when a
Rigidbody lands on the floor.

## How it works (and its limits)

Every frame is split into small steps (so fast objects don't skip through thin walls). In
each step every Rigidbody gets gravity, forces and drag, then moves:

- **Box bodies** move along X, then Y, then Z and stop exactly at anything solid. That keeps
  platformer characters crisp: no sliding off ledges, no bouncing on corners.
- **Sphere and capsule bodies** move, then get pushed out of whatever they overlap, so they
  roll off edges, slide along walls and corners, and rest properly on round things.

When a body runs into another Rigidbody, they share the bump by their masses, so
billiard balls pass their speed along and a heavy crate shoves a light one. Friction slows
sliding along whatever a body touches.

Boxes stay lined up with the X, Y and Z axes (a rotated box collider acts like the box
around it), and Rigidbodies don't spin. Ragdolls and joints aren't supported yet.
