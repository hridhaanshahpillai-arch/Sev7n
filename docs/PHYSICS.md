# Physics (v0.5 alpha)

Physics makes things fall, land and bump into walls without you writing the math.

## The three pieces

| Component | Role |
|-----------|------|
| **Rigidbody** | Moves by physics: gravity pulls it, it has a velocity |
| **Box Collider** | Its shape for collisions. Without a Rigidbody, it's part of the world: a floor, a wall, a platform that never moves |
| **Box Collider** with **Trigger** ticked | Never solid; things pass through, but `onCollide` still happens (coins, checkpoints, danger zones) |

Solid tilemaps count as world too (see [2D games](2D.md)).

So a falling crate is a Box Collider plus a Rigidbody, and the floor it lands on is just a
Box Collider. Objects without a Rigidbody still move however your scripts move them,
exactly as before.

## Rigidbody settings

| Setting | What it does |
|---------|--------------|
| **Gravity** | 1 = normal, 0 = floats (top-down games, space), 2 = falls twice as hard, -1 = falls up |
| **Bounciness** | 0 = stops dead when it lands, 1 = bounces back up just as fast |
| **Drag** | Slows it down over time, like air resistance |
| **Start velocity** | How fast it's already moving when the game starts |

## Box Collider settings

| Setting | What it does |
|---------|--------------|
| **Size** | The box, relative to the object's scale (1, 1, 1 matches a cube) |
| **Offset** | Moves the box, e.g. down to a character's feet |
| **Trigger** | Not solid (see above) |
| **Fit to shape** | Sizes the box to the object's mesh or sprite |

## In scripts

```seven
entity.velocity                 // how fast it's moving (a vec3 you can change)
entity.velocity.y = 7           // jump
entity.push(vec3(5, 0, 0))      // add to the velocity: a knock-back, an explosion
entity.grounded                 // true while it stands on something

setGravity(vec3(0, -20, 0))     // stronger gravity for the whole game
gravity()                       // the current gravity
solidAt(vec3(2, -0.5, 0))       // is there something solid at this point? (ground ahead?)
```

`onCollide(other)` runs once when two colliders start touching, including when a
Rigidbody lands on the floor.

## How it works (and its limits)

Every frame, each Rigidbody's velocity gets gravity and drag, then it moves along X, then
Y, then Z, stopping at anything solid in the way. Big frames are split into small steps,
so fast objects don't skip through thin walls.

Boxes are always lined up with the X, Y and Z axes, so a rotated collider acts like the
box around it, and Rigidbodies don't spin. That keeps things predictable, which is what
platformers, top-down games and most arcade games want. Ramps, rolling balls and
ragdolls aren't supported yet.
