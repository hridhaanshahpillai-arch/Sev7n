# Ragdolls (v0.5 alpha)

A **Ragdoll** lets an animated character go limp and fall like a real body: it tumbles down
stairs, crumples against walls, and keeps the speed it had when it went limp. Then it can get
back up.

## Setting one up

1. Use a **rigged character** (a `.glb` / `.gltf` with a skeleton: Mixamo, Quaternius,
   Blender...) on a **Mesh Renderer**, usually with an **Animator**.
2. **Add Component → Ragdoll**. Its Inspector lists the bones it found.
3. In a script, `entity.ragdoll()` makes it go limp and `entity.getUp()` stands it back up.

While playing, the Ragdoll's Inspector has a **Go limp / Get up** button to try it out.

Sev7n works out the body by itself: the hips are the bone where the spine and both legs meet, and
it simulates the spine, neck, head, arms and legs. Fingers, toes and helper bones just come along.

## Settings

| Setting | What it does |
|---------|--------------|
| **Stiffness** | 0 = floppy, 1 = the joints try to keep the pose it fell in |
| **Friction** | How quickly it stops sliding along the ground |
| **Bounciness** | 0 = lands with a thud, 1 = bounces |
| **Get up blend** | Seconds to blend from lying limp back into its animation |

## Scripts

```seven
entity.ragdoll()                        // go limp, keeping its speed
entity.ragdoll(vec3(0, 3, -6))          // ...knocked by a push (units per second)
entity.getUp()                          // stand up where its hips ended up
entity.ragdollResting                   // true once it has landed and stopped moving (a good time to get up)
entity.ragdolling                       // true while it's limp
```

A character knocked over by a punch, which gets up after 3 seconds with a get-up animation:

```seven
behaviour Fighter {
    var down = 0

    func hit(push) {
        entity.ragdoll(push)
        down = 3
    }

    func update(dt) {
        if entity.ragdolling {
            down -= dt
            if down <= 0 {
                entity.getUp()
                entity.play("LayToIdle", 0)   // a get-up animation, if the model has one
            }
        }
    }
}
```

**Getting up:** the character stands where its hips came to rest, facing from its head toward
its feet (the way get-up animations rise from lying on the back), then blends from the limp pose
into whatever animation plays next.

**While limp:** its own collider and Rigidbody are switched off and the body's bones collide
with the world instead. Its position follows the hips, so a camera following it keeps up. An NPC
stops thinking while it's limp and carries on when it gets up.

## Models that face backwards

Most `.glb` characters are made facing +Z, so they walk backwards in Sev7n. Set the Mesh
Renderer's **Model turn** to 180 to fix it.

## The example

`projects/MyGame` has a playground built with the free (CC0) **Universal Animation Library 2**
mannequin by Quaternius:

| Key | Does |
|-----|------|
| **WASD** | walk (hold **Shift** to run); the character turns smoothly toward where it's going |
| **Space** | jump |
| **C** | slide while moving: you keep your speed, slow down and stand back up; sliding into a zombie knocks it flying |
| **R** | go limp, **R** again to get up |
| **F** | punch: knocks zombies and crates over |

Two zombies wander the yard (NPCs that chase you). If one catches you, it knocks you over and
you get up after a moment. Punch them and they fall, then get back up. The scripts
(`PlayerController`, `Zombie`, `CameraFollow`) are short and commented, a good place to start
your own game.

That animation library has no plain walk or run, so the player walks with `Walk_Carry_Loop`.
Change the animation names at the top of `PlayerController.7n` to use another model's walk and run.

## Limits for now

Points and capsules, not full rigid bodies: a limp body doesn't push crates or other characters
around, two limp bodies pass through each other, and the head doesn't turn on its own when the
model has no bone above it. Knees only bend backward and elbows only forward, arms and legs never
pass through the body, and the whole length of every limb collides with the world.