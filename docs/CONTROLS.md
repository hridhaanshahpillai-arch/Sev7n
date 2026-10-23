# Cameras and Controls

## Third person camera

Select your camera, **Add Component > Third Person Camera**, and type the name of the object to follow
(e.g. `Player`). While playing, the camera stays behind it, orbits with the mouse (or a gamepad's right
stick), and moves in closer when a wall would get in the way.

| Setting | What it does |
|---|---|
| Follow | the name of the object to follow |
| Distance | how far behind the camera stays |
| Look height | the camera looks at a point this high above the target's feet |
| Mouse speed | how fast the mouse turns the camera |
| Smoothing | how quickly it catches up (0 = instantly) |
| Lowest / Highest angle | how far down and up it can look from |
| Capture mouse | hides the cursor while playing, so moving the mouse orbits |
| Avoid walls | moves in closer instead of going through walls |

Move your character relative to the camera, so W always means "away from the camera":

```seven
behaviour PlayerMove {
    var speed = 5

    func update(dt) {
        var input = vec3(0, 0, 0)
        if key("w") { input = input + cameraForward() }
        if key("s") { input = input - cameraForward() }
        if key("d") { input = input + cameraRight() }
        if key("a") { input = input - cameraRight() }
        input = input + cameraRight() * padAxis("LX") - cameraForward() * padAxis("LY")   // the left stick
        if input.length > 0.1 {
            entity.position = entity.position + input.normalized() * speed * dt
            entity.transform.rotation.y = deg(atan2(-input.x, -input.z))   // face where you're going
        }
    }
}
```

In Eights: **Camera Forward** and **Camera Right**.

## Gamepads

Xbox, PlayStation, Switch Pro and most other controllers work (the first one plugged in). Buttons use Xbox names:
`A B X Y`, `LB RB`, `BACK START`, `LS RS` (clicking the sticks), `UP DOWN LEFT RIGHT` (the d-pad).

| Function | What it gives |
|---|---|
| `padConnected()` | true while a gamepad is plugged in |
| `padButton("A")` | true while a button is held |
| `padPressed("A")` | true only on the frame it went down (jumping) |
| `padAxis("LX")` | the sticks `LX LY RX RY` from -1 to 1 (down is +1), the triggers `LT RT` from 0 to 1 |

```seven
if keyPressed("space") or padPressed("A") { jump() }
```

In Eights: **Is Gamepad Connected**, **Is Pad Button Held**, **Was Pad Button Pressed**, **Pad Stick / Trigger**
and **Move Input (Left Stick)**.

## The sky

Cameras show a sky behind everything (3D cameras; turn it off for a plain background colour). Select the camera:

| Setting | What it does |
|---|---|
| Sky top / Horizon / Ground | the colours from straight up, down to the horizon, and below it. The horizon is also the fog's colour, so faraway things melt into the sky |
| Sun | a glowing sun where the scene's Light shines from: turn the Light to move it |
| Day and night | the sky darkens at night and glows orange at sunrise and sunset, following the Light's angle |

A day-night cycle is just a script turning the Light:

```seven
behaviour DayNight {
    func update(dt) {
        entity.transform.rotation.x = -hour() / 24 * 360 + 90   // noon: straight down, midnight: from below
    }
}
```
