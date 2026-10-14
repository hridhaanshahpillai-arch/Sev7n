# Particle effects (v0.5 alpha)

Particle effects are fire, smoke, sparks, magic, explosions, rain, snow and confetti: lots of
little dots that fly, fall, grow or shrink, and fade out.

## Making one

Right-click in the **Hierarchy** → **New Effect** → pick a preset:

| Preset | Looks like |
|--------|-----------|
| **Fire** | A glowing flame that rises and turns from yellow to red |
| **Smoke** | Soft grey puffs that drift up and spread out |
| **Sparks** | Bright specks that shoot out and fall |
| **Magic** | Purple and blue glitter floating around |
| **Explosion** | One big glowing burst |
| **Rain** | Blue streaks falling over a wide area |
| **Snow** | White flakes drifting down slowly |
| **Confetti** | A burst of colorful bits |

It's also in the **Create** menu. Right-click an object first to make the effect right where that
object is. You can also add **Particle Effect** to any object with **Add Component**, for
example a torch that's on fire.

Effects play in the Scene view while you edit them, so you see every change straight away.
One-off effects (Explosion, Confetti) repeat in the editor so you can watch them.

## Settings

Pick a preset from **Preset → Start from...** to replace everything, then change what you like.

| Setting | What it does |
|---------|--------------|
| **Play on start** | Starts by itself when the game starts |
| **Loop** | Keeps going. Off: emits for **Duration** seconds, then stops |
| **Per second** | How many particles come out every second |
| **Burst** | Particles that come out all at once when it starts |
| **Start radius** | Particles start anywhere in a ball this big (0 = from one point) |
| **Lifetime** (+/-) | Seconds each particle lives; +/- adds randomness (0.3 = 30% either way) |
| **Speed** (+/-) | How fast they fly out |
| **Direction** / **Spread** | Which way they fly, and how far from that they may go (0° stream, 180° every way). Turns with the object |
| **Gravity** | 1 falls normally, 0 floats, below 0 rises (fire and smoke) |
| **Drag** | Slows them down, like air |
| **Start / End size** | They grow or shrink over their life |
| **Start / End color** | They fade between these colors. The alpha (see-through amount) fades too, so an end alpha of 0 makes them vanish smoothly |
| **Random colors** | Every particle gets its own bright color |
| **Glow** | Particles add light where they overlap (fire, sparks). Off: they cover each other (smoke) |
| **Picture** | Use your own picture for each particle instead of a soft dot |
| **Auto-remove** | Deletes the object once the effect has finished (for one-off effects) |

Particles stay where they were made, so moving the object (say a rocket with a Fire effect)
leaves a trail behind it.

## In scripts

```seven
behaviour Rocket {
    func onCollide(other) {
        spawnEffect("Explosion", transform.position)   // a one-off effect that removes itself
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
