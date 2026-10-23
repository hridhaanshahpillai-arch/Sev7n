# First-person and Open Worlds (v0.8 alpha)

Everything you need for first-person games and big open worlds: mouse-look, shooting, and land
to explore. This guide has two ready-to-use scripts to copy into your project.

## A first-person player in a minute

1. Make an object called **Player** with a **Capsule Collider** (Radius 0.35, Height 1.8,
   Offset Y 0.9, so its feet are at its position) and a **Rigidbody** (Friction 0).
2. Make a script called **FpsController** (Project panel > + Script), paste the script below in,
   and add it to the Player (drag it onto the Player in the Hierarchy).
3. Keep the **Main Camera** in the scene: the script moves it to the player's eyes.
4. Press Play and click the Game view.

```seven
// First-person controls.
//   Mouse        look around (click the game to capture the mouse, Esc to let it go)
//   WASD         move (hold Shift to run)
//   Space        jump
//   Left click   shoot: whatever you hit gets onHit(damage, point, direction)
behaviour FpsController {
    var walkSpeed = 5
    var runSpeed = 9
    var jumpSpeed = 5.5
    var sensitivity = 0.12     // degrees of turn per pixel the mouse moves
    var eyeHeight = 1.6
    var range = 120
    var damage = 25

    var camera = none
    var pitch = 0              // looking up (+) or down (-)
    var kick = 0               // the gun's recoil, settling back

    func start() {
        camera = find("Main Camera")
        lockMouse(true)
    }

    func update(dt) {
        if keyPressed("escape") { lockMouse(false) }
        if not mouseLocked() {
            if mouseClicked() { lockMouse(true) }
            entity.velocity = vec3(0, entity.velocity.y, 0)
            return
        }

        // the mouse turns you left and right, and tilts the camera up and down
        transform.rotation.y -= mouseMoveX() * sensitivity
        pitch = clamp(pitch - mouseMoveY() * sensitivity, -85, 85)

        // move the way you're facing
        var move = vec3()
        if key("w") { move += transform.forward }
        if key("s") { move -= transform.forward }
        if key("d") { move += transform.right }
        if key("a") { move -= transform.right }
        var speed = walkSpeed
        if key("shift") { speed = runSpeed }
        if move.length > 0 { move = move.normalized() * speed }
        entity.velocity = vec3(move.x, entity.velocity.y, move.z)
        if keyPressed("space") and entity.grounded {
            entity.velocity = vec3(move.x, jumpSpeed, move.z)
        }

        if mouseClicked() { shoot() }
        kick = max(0, kick - dt * 25)
    }

    // after everything has moved: the camera sits at your eyes, and the crosshair is drawn
    func lateUpdate(dt) {
        if camera == none { return }
        camera.position = transform.position + vec3(0, eyeHeight, 0)
        camera.transform.rotation.x = pitch + kick
        camera.transform.rotation.y = transform.rotation.y
        camera.transform.rotation.z = 0
        crosshair()
    }

    func shoot() {
        var eye = camera.position
        var aim = camera.transform.forward
        kick = 2.5
        var hit = raycast(eye, aim, range, entity)
        if hit == none { return }
        spawnEffect("Sparks", hit.point)
        if hit.entity != none { hit.entity.send("onHit", damage, hit.point, aim) }
    }

    func crosshair() {
        var x = screenWidth() / 2
        var y = screenHeight() / 2
        var gap = 5 + kick * 3
        setGlow(true)
        line(x - gap - 10, y, x - gap, y, 2, #f8f8f2)
        line(x + gap, y, x + gap + 10, y, 2, #f8f8f2)
        line(x, y - gap - 10, x, y - gap, 2, #f8f8f2)
        line(x, y + gap, x, y + gap + 10, 2, #f8f8f2)
        circle(x, y, 1.5, #f8f8f2)
        setGlow(false)
        if not mouseLocked() {
            text("Click to play  -  Esc frees the mouse", x, y + 40, 22, #c8c8d8, "center")
        }
    }
}
```

## First-person controls

| Key | Does |
|-----|------|
| Mouse | look around (click the game first to capture the mouse) |
| WASD | move, **Shift** to run |
| Space | jump |
| Left click | shoot |
| Esc | let the mouse go |

### The mouse in scripts

```seven
lockMouse(true)                     // hide the cursor and keep it in the game (mouse-look)
lockMouse(false)                    // give it back (menus)
mouseLocked()                       // is it captured?
mouseMoveX(), mouseMoveY()          // how far the mouse moved this frame (pixels)
mouseDown(1), mouseClicked(1)       // the right button (0 or nothing = left)
```

Turning with the mouse:

```seven
transform.rotation.y -= mouseMoveX() * 0.12                       // left and right
pitch = clamp(pitch - mouseMoveY() * 0.12, -85, 85)               // up and down (the camera)
```

### Shooting

A shot is a raycast from the camera, straight ahead:

```seven
var hit = raycast(camera.position, camera.transform.forward, 120, entity)
if hit != none and hit.entity != none {
    hit.entity.send("onHit", 25, hit.point, camera.transform.forward)
}
```

`entity.send("onHit", ...)` calls `onHit` on every behaviour of whatever was hit, with those
values, so anything can react in its own way:

```seven
behaviour Target {
    var health = 100
    func onHit(damage, point, direction) {
        health -= damage
        entity.addImpulse(direction * 4)
        if health <= 0 { entity.destroy() }
    }
}
```

`send` works for anything, not just shooting: `door.send("open")`, `player.send("heal", 20)`.

## Open worlds

### Terrain

**Create > Terrain** (or Add Component > Terrain) makes land from noise. The same settings always
make the same land.

| Setting | What it does |
|---------|--------------|
| **Size** | how wide and deep it is |
| **Detail** | how many squares across: more means finer hills |
| **Height** | how high the hills go |
| **Hill size** | smaller = wide, gentle hills; bigger = lots of bumps |
| **Seed** / **New land** | different numbers make different land |
| **Flat middle** | a flat circle in the middle to start on |
| **Trees** | how many trees to scatter (on gentle slopes, below the snow) |
| **Sand / Grass / Rock / Snow** | the colours, by height and steepness |

Characters walk on terrain (up hills and down valleys), ragdolls land on it, raycasts hit it,
`solidAt` knows it's there, and NPCs find their way across it.

### Sculpting

Shape the land by hand: select the terrain, click **Sculpt** in the Inspector, pick a brush and
drag over the land in the Scene view. A circle shows where the brush is.

| Brush | What it does |
|-------|--------------|
| **Raise** | builds the land up: hills, mountains, ridges |
| **Lower** | digs it down: valleys, lakes, pits |
| **Smooth** | softens bumps and sharp edges |
| **Flatten** | levels the land to the height where the stroke started: paths, building spots |

| Key | While sculpting |
|-----|-----------------|
| **Shift** + drag | lower, whichever brush is picked |
| **Ctrl** + drag | smooth |
| **[** / **]** | brush smaller / bigger |
| **Ctrl+Z** | undo the last stroke |
| **Esc** | stop sculpting |

**Brush size** and **Strength** are in the Inspector. Sculpting goes on top of the generated land,
so you can still change Height, Hill size or Detail afterwards and keep what you sculpted.
**Clear sculpting** takes it all away. It's saved with the scene.

Scripts can change the land while the game runs, like a crater where a bomb went off:

```seven
func onExplode(at) {
    sculpt("lower", at, 4, 2)        // brush, position, radius, [amount]
}

var y = groundHeight(x, z)           // how high the land is there (none off the land)
```

`sculpt` takes `"raise"`, `"lower"`, `"smooth"` or `"flatten"` (which levels to the position's
height). In Eights: **Sculpt Terrain** and **Ground Height** (World). NPCs plan their paths when the
game starts, so they won't know about land a script changes later.

### Seeing far

Turn on **fog** in Settings (click the 7): it fades the distance into the sky colour, and anything
past the fog's end isn't drawn at all, so big worlds stay fast. For a 400-wide terrain, fog from 70 to 190
units looks good.

### Day and night

Put this script on the **Sun** (an object with a Directional Light): it turns the sun with the game
clock (`hour()`), dims it at night, warms its light at sunrise and sunset, and changes the sky colour.

```seven
// Turns the sun with the game clock: sunrise, noon, sunset, and a dim blue night.
behaviour DayNight {
    var dayLengthSeconds = 240     // a whole day and night, in real seconds
    var startHour = 9
    var sky = none

    func start() {
        setDayLength(dayLengthSeconds)
        setHour(startHour)
        sky = find("Main Camera")
    }

    func update(dt) {
        var h = hour()
        // 6:00 = sunrise (flat), 12:00 = overhead, 18:00 = sunset
        var height = sin((h - 6) / 12 * PI)
        transform.rotation.x = -(h - 6) / 12 * 180
        transform.rotation.y = 30
        var day = clamp(height * 3, 0, 1)
        entity.lightIntensity = 0.05 + day * 0.95
        entity.lightAmbient = 0.12 + day * 0.2
        var dusk = clamp(1 - abs(height) * 4, 0, 1)    // warmer light near the horizon
        entity.lightColor = rgb(255, 255 - dusk * 70, 255 - dusk * 120)
        if sky != none {
            sky.camera.background = rgb(20 + day * 120, 24 + day * 150, 40 + day * 190)
        }
    }
}
```

Scripts can change any light:

```seven
entity.lightIntensity = 0.5      // on an object with a Directional Light
entity.lightColor = #ffd9a0
entity.lightAmbient = 0.2
entity.lightShadows = true       // shadows on or off (how sharp: Settings > Shadows)
entity.lightShadowStrength = 0.8 // how dark shadows are, 0 to 1
```

The sun casts shadows from the land, trees and every object, and they move with it as the day
goes by. Long evening shadows come free with the script above.

## Limits for now

One terrain is one piece of land (no streaming of endless worlds yet); terrain can't have caves or
overhangs; trees are simple shapes (a trunk and a round top) and don't block movement.
