# Animation (v0.5 alpha)

Three ways to make things move without writing the math yourself:

| | For | How |
|-|-----|-----|
| **Animated 3D models** | characters that walk, run, swing swords | an **Animator** plays the animations inside a `.glb` / `.gltf` |
| **Tweens** | smooth moves, spins, grows, color fades | one line of script: `tween(...)` |
| **Sprite animation** | 2D characters from sprite sheets | a **Sprite Animator** (see [2D games](2D.md)) |

## Animated 3D models

Import a `.glb` or `.gltf` that has animations: from Mixamo, Blender, Quaternius' animation
libraries, Sketchfab... Drop it into the scene and it starts moving straight away, because
Sev7n adds an **Animator** for you. (Add one with **Add Component → Animator** otherwise.)

| Setting | What it does |
|---------|--------------|
| **Animation** | Which one plays when the game starts. Picking one here shows it straight away in the Scene view |
| **Speed** | 1 = normal, 2 = twice as fast, 0.5 = slow motion |
| **Loop** | Start again at the end |
| **Play on start** | Starts by itself |
| **Blend time** | Seconds to blend from one animation into the next, so switching never snaps |

Animations play in the Scene view while you edit, so you can see what you've picked.

In scripts:

```seven
behaviour Hero {
    func update(dt) {
        var moving = key("w") or key("s")
        if moving { entity.play("Walk") } else { entity.play("Idle") }   // blends smoothly
        if keyPressed("space") { entity.play("Jump", 0.05) }            // blend in fast
    }
}
```

| Code | What it does |
|------|--------------|
| `entity.play("Walk")` | Switches animation, blending over the Blend time (playing the current one again does nothing) |
| `entity.play("Jump", 0.05)` | ...blending over 0.05 seconds (0 = snap) |
| `entity.animation` | The animation playing now |
| `entity.animationDone` | True once a non-looping animation has reached its end |
| `entity.animationSpeed = 1.5` | Faster or slower |
| `entity.animations` | A list of every animation the model has |

Parts attached to a bone (a sword in a hand, a hat on a head) move with it.

### What's supported

Skinned characters with up to 4 bones per vertex (what every exporter makes), any number of
bones, moving, turning and scaling keyframes, and animated non-skinned parts. Smooth
"cubic spline" keyframes play as straight lines between keys, which looks the same at normal
frame rates. Morph targets (blend shapes, used for faces) aren't played yet.

## Tweens

A tween changes something smoothly over time:

```seven
tween(entity, "position", vec3(0, 3, 0), 1.5, "outBounce")    // drop onto a spot with a bounce
tween(entity, "rotation", vec3(0, 360, 0), 2)                 // spin around once
tween(entity, "scale", vec3(2, 2, 2), 0.3, "outBack")         // pop bigger, overshooting a little
tween(find("Door"), "color", #ff5555, 0.5)                     // fade to red
tween(entity, "position", target, 1, "inOutQuad", arrived)     // calls arrived() when it gets there
stopTweens(entity)                                             // stop where it is
```

`tween(entity, property, to, seconds, [easing], [whenDone])`

| Property | Needs |
|----------|-------|
| `position`, `rotation`, `scale` | anything |
| `color` | a Mesh Renderer |
| `tint`, `size` | a Sprite Renderer |

Starting a new tween of the same property replaces the old one, so you can call it again
whenever the target changes.

### Easings

How the movement speeds up and slows down. The default is `inOutQuad` (gentle at both ends).

| Easing | Feels like |
|--------|-----------|
| `linear` | constant speed, robotic |
| `inQuad` `inCubic`... | starts slowly, ends fast |
| `outQuad` `outCubic`... | starts fast, slows to a stop (great for UI and landing) |
| `inOutQuad` `inOutSine`... | gentle start and end |
| `outBack` | goes a little past, then settles (popping in) |
| `outElastic` | wobbles like a spring |
| `outBounce` | bounces like a dropped ball |

The strengths, gentle to strong: `Sine`, `Quad`, `Cubic`, `Quart`, `Expo`. `ease(name, t)` gives
any curve's value if you want to use one yourself.
