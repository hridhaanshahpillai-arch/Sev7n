# Particle effects (v0.5 alpha)

Particle effects are fire, smoke, sparks, magic, explosions, fountains, flying debris, blood, rain,
snow and confetti: lots of little particles that fly, fall, spin, bounce and fade out.
A particle can be a soft dot, your own picture, or a 3D model.

## Making one

Right-click in the **Hierarchy** → **New Effect** → pick a preset:

| Preset | Looks like |
|--------|-----------|
| **Fire** | A glowing flame that rises and turns from yellow to red |
| **Smoke** | Soft grey puffs that drift up and spread out |
| **Sparks** | Bright specks that shoot out and fall |
| **Magic** | Purple and blue glitter floating around a ball |
| **Explosion** | One big glowing burst outward |
| **Fountain** | Water that shoots up, falls and splashes on the floor |
| **Debris** | Little 3D cubes that burst out, tumble and bounce on the ground |
| **Blood** | A quick spray of dark red drops that splat on the ground (hits, knockdowns) |
| **Rain** | Blue streaks falling over a wide area |
| **Snow** | White flakes drifting down slowly |
| **Confetti** | A burst of colorful spinning bits |

It's also in the **Create** menu. Right-click an object first to make the effect right where that
object is. You can also add **Particle Effect** to any object with **Add Component**, for
example a torch that's on fire.

Effects play in the Scene view while you edit them, so you see every change straight away.
One-off effects (Explosion, Debris, Blood, Confetti) repeat in the editor so you can watch them.

## Settings

Pick a preset from **Preset → Start from...** to replace everything, then change what you like.
The settings are in sections you can fold away, like Unity's particle modules.

**Main**

| Setting | What it does |
|---------|--------------|
| **Play on start** | Starts by itself when the game starts |
| **Loop** | Keeps going. Off: emits for **Duration** seconds, then stops |
| **Lifetime** (+/-) | Seconds each particle lives; +/- adds randomness (0.3 = 30% either way) |
| **Max particles** | The most there can be at once |
| **Auto-remove** | Deletes the object once the effect has finished (for one-off effects) |

**Emission**: **Per second** (a steady stream) and **Burst** (all at once when it starts).

**Shape**: where particles start and which way they fly. It turns with the object.

| Shape | Particles start | and fly |
|-------|-----------------|---------|
| **Cone** | on a circle (**Radius**; 0 = one point) | along **Direction**, fanning out by **Spread** |
| **Sphere** | inside a ball (**Radius**) | outward |
| **Hemisphere** | in the top half of a ball | outward and up |
| **Box** | anywhere inside a box (**Box size**) | along **Direction** (rain, snow) |
| **Circle** | on a flat ring (**Radius**) | outward along the ground (shockwaves) |
| **Line** | along a line (**Length**) on the object's X | along **Direction** (a row of jets or flames) |

**Spread** is how far particles may stray from their direction: 0° straight, 180° any way.

**Movement**: **Speed** (+/-), **Gravity** (1 falls normally, 0 floats, below 0 rises like fire
and smoke) and **Drag** (slows them down, like air).

**Color & Size**: **Start / End size** (they grow or shrink), **Start / End color** (they
fade between them; an end alpha of 0 makes them vanish smoothly), **Random colors**.

**Rotation**: **Random start** turns each particle a random way; **Spin** (+/-) is how many
degrees per second they turn.

**Collision**: turn on **Bounce** and particles bounce off solid things: Box Colliders that
aren't triggers, solid tilemaps, and a flat **Floor** at **Floor height** (even where there's no
collider). **Bounciness** is how much speed they keep; **Life lost** makes them fade faster
each hit (1 = they disappear when they hit, like raindrops).

**Renderer**: what the particles are.

- **Dots**: flat cards facing the camera, a soft dot or your **Picture**. **Glow** makes them add
  light where they overlap (fire, sparks); off, they cover each other (smoke).
- **Models**: a built-in shape (cube, sphere...) or one of your imported models, lit like other
  objects. Models shrink rather than fade, so give them a smaller **End size** to make them disappear.

Particles stay where they were made, so moving the object (say a rocket with a Fire effect)
leaves a trail behind it.

## In scripts

```seven
behaviour Rocket {
    func onCollide(other) {
        spawnEffect("Explosion", transform.position)   // a one-off effect that removes itself
        spawnEffect("Debris", transform.position)
        entity.destroy()
    }
}
```

| Code | What it does |
|------|--------------|
| `spawnEffect("Explosion", position)` | Makes a one-off effect from a preset at a position; it removes itself when it's over |
| `entity.playEffect()` | Starts the entity's effect from the beginning |
| `entity.stopEffect()` | Stops sending out new particles (the ones already out finish) |
| `entity.emit(20)` | Sends out 20 particles right now |
| `entity.effectDone` | True once the effect has stopped and every particle is gone |

Effects work the same in OpenGL and Vulkan.
