# HUD, Menus and Sound (v0.7.5 alpha)

## On-screen text and HUD

Score, health bars, timers, messages: draw them from a script's `update()`. The HUD is drawn
again every frame on top of the game, so whatever you draw this frame is what shows.

```seven
behaviour Score {
    var points = 0
    var health = 100

    func update(dt) {
        text("Score: " + points, 24, 20)                          // top left
        bar(24, 64, 240, 18, health / 100, #ff5555)                // a health bar
        text("PAUSED", screenWidth() / 2, 300, 72, #bf9eee, "center")
    }
}
```

| Code | Draws |
|------|-------|
| `text(value, x, y, [size], [color], [align])` | text (any value: numbers too). `size` is its height (32 if left out); `align` is `"left"`, `"center"` or `"right"`: which part of the text is at x |
| `rect(x, y, width, height, [color])` | a filled box (a color with see-through, like `#14151c90`, darkens the game behind a menu) |
| `bar(x, y, width, height, amount, [color], [background])` | a bar filled `amount` of the way (0 to 1): health, stamina, loading |
| `image(path, x, y, width, height, [tint])` | a picture from the project, like `"assets/textures/heart.png"` |
| `panel(x, y, width, height, [color])` | a rounded window background with a faint edge |
| `screenWidth()`, `screenHeight()` | the screen's size in HUD units |
| `textWidth(text, [size])` | how wide text will be, for lining things up |

**Where things go:** (0, 0) is the top left corner. The screen is always **720** units tall and as
wide as its shape (1280 for a widescreen window), however big the window really is. So
`screenWidth() / 2` is the middle, and things stay in the same place in every window size. Things
drawn later go on top of things drawn earlier.

The HUD shows in the Game view and in the game window, not in the Scene view.

### Anchors: a HUD that fits every screen

Screens come in different shapes (a laptop, an ultrawide monitor), so things on the right edge
should be measured from the right. `anchor(name)` moves where (0, 0) is, for everything drawn
after it this frame (buttons and `uiMouseX()` too):

```seven
anchor("bottomRight")
rect(-230, -100, 200, 70, #00000070)       // always 30 in from the bottom right corner
text(ammo, -50, -95, 48, #ffffff, "right")
anchor("center")
circle(0, 0, 3)                            // a crosshair in the middle
anchor("topLeft")                          // back to normal
```

The anchors: `topLeft` `top` `topRight` `left` `center` `right` `bottomLeft` `bottom` `bottomRight`.
Each frame starts at `topLeft`. On a world canvas (below), anchors don't apply.

## Buttons, sliders and menus

Controls work the same way: call them every frame you want them shown, and they tell you what
the player did.

```seven
behaviour PauseMenu {
    var paused = false
    var volume = 1
    var music = true

    func lateUpdate(dt) {                         // after every update, so it's drawn on top
        if keyPressed("escape") {
            paused = not paused
            if paused { setTimeScale(0) } else { setTimeScale(1) }   // freeze the world
        }
        if not paused { return }
        panel(430, 140, 420, 400)
        text("Paused", 640, 160, 44, #f8f8f2, "center")
        if button("Resume", 470, 240, 340, 54, true) {
            paused = false
            setTimeScale(1)
        }
        volume = slider("Volume", 470, 330, 340, volume, 0, 1)
        setVolume(volume)
        music = checkbox("Music", 470, 400, music)
        if button("Quit", 470, 460, 340, 46) { quit() }
    }
}
```

| Code | Does |
|------|------|
| `button(label, x, y, width, height, [primary])` | a button; **true** on the frame it's clicked. `primary` makes it purple (the main choice) |
| `slider(label, x, y, width, value, [min], [max])` | a slider (0 to 1 without min and max); returns the new value, so keep it in a variable |
| `checkbox(label, x, y, value)` | a box that ticks on and off; returns the new value |
| `uiMouseX()`, `uiMouseY()` | the mouse in HUD units |
| `mouseOverUi()` | true when the mouse is over a control or panel (so a click there isn't also an attack) |

Buttons light up under the mouse and sink while pressed. A click counts when the mouse goes down
and back up on the same button (slide off it to cancel), like everywhere else.

### Pausing, slow motion, restarting

| Code | Does |
|------|------|
| `setTimeScale(0)` | freezes the world (physics, animation, NPCs, particles) while scripts keep running: pause menus |
| `setTimeScale(0.3)` | slow motion. `timeScale()` says what it is now |
| `restart()` | starts the game again from the scene file |
| `quit()` | closes the game (in the editor: stops playing) |

While frozen, `update(dt)` still runs with `dt` = 0, so check `timeScale() == 0` to ignore the
player's keys while a menu is open.

`lateUpdate(dt)` is like `update(dt)`, but runs after every object's update: good for menus
(drawn on top of everything else) and cameras that follow something.

## UI in the world: holograms, prompts, visors

UI doesn't have to sit flat on the screen. It can float in the world, stick to things, glow and
track targets, like the inventory in Dead Space, the "Jump across" prompts in Tomb Raider or the
visor in Metroid Prime.

### Panels floating in the world

Between `beginWorld(...)` and `endWorld()`, everything is drawn on a canvas in the world instead
of the screen: same units, same commands. It's hidden behind things in front of it.

```seven
// a sign above a door, facing the camera; 0.004 world units per HUD unit (400 units = 1.6 m)
beginWorld(find("Door").position + vec3(-0.6, 2.4, 0), none, 0.004)
panel(0, 0, 300, 80)
text("EXIT", 150, 16, 40, #8be9fd, "center")
endWorld()
```

`beginWorld(position, [yaw], [scale])`: `position` is the canvas's top left corner. Leave `yaw` out
(or pass `none`) and the canvas faces the camera square on; give a number and it stands upright,
turned that many degrees (a screen on a wall). `scale` is world units per HUD unit (0.0025 if left out).

### Pinning UI to things

`worldToScreen(point)` gives where something in the world is on the screen (`.x`, `.y`), or `none`
when it's behind the camera. Draw there and the UI follows it:

```seven
var spot = worldToScreen(enemy.position + vec3(0, 2, 0))       // above its head
if spot != none {
    brackets(spot.x - 40, spot.y, 80, 120, #bf9eee)             // lock-on corners
    keyIcon("F", spot.x + 50, spot.y)                           // a key prompt next to it
    text("PUNCH", spot.x + 96, spot.y + 2, 26)
}
```

### Glow and sci-fi shapes

`setGlow(true)` makes what's drawn next add its light instead of covering what's behind (holograms,
visor readouts, neon). `setGlow(false)` goes back to normal.

| Code | Draws |
|------|-------|
| `line(x1, y1, x2, y2, [thickness], [color])` | a line at any angle |
| `circle(x, y, radius, [color])` | a filled circle (radar backgrounds, dots) |
| `ring(x, y, radius, [thickness], [color])` | a ring (radars, reticles) |
| `frame(x, y, w, h, [color], [thickness])` | just the outline of a box |
| `brackets(x, y, w, h, [color], [length], [thickness])` | only the corners of a box: targets, selected slots |
| `segmentBar(x, y, w, h, amount, segments, [color], [background])` | a bar in blocks (energy, ammo) |
| `keyIcon(key, x, y, [size])` | a keyboard key with its letter; returns its width, so text can follow it |

Controls (buttons, sliders, checkboxes) are for the screen; on a world canvas, use keys (like the
example's inventory).

## Sound

```seven
playSound("assets/sounds/punch.wav")          // once
playSound("assets/sounds/coin.wav", 0.5)      // quieter (1 = as recorded)
playMusic("assets/sounds/theme.wav")          // loops until stopMusic() or other music
stopMusic()
setVolume(0.5)                                // every sound and the music at half volume
```

Sound files are **.wav** (also .aiff and .au). Import them like any other file, or put them in
your project's `assets/sounds` folder. Up to 24 sounds play at once. Everything stops when the
game stops.

### Sounds in the world

`playSoundAt` plays a sound at a place: it's quieter the further it is from the camera (silent
past its range) and comes from the left or right, so players can hear where an enemy is.

```seven
playSoundAt("assets/sounds/growl.wav", transform.position)             // heard up to 30 units away
playSoundAt("assets/sounds/explosion.wav", where, 1, 80)               // volume 1, heard up to 80 away
```

## In the example

`projects/MyGame` starts on a **title screen** (Play / Quit) and has a **pause menu** on Esc
(Resume, Restart, a volume slider, Show controls, Slow motion, Quit), in `GameMenu.7n`.

`VisorHud.7n` draws a glowing **visor**: your energy as a segmented bar, how many zombies you've
knocked down, a **radar** with a sweeping line and the zombies as red dots, a **lock-on reticle**
that follows the nearest zombie with its name and health, and **prompts pinned to the world**
("[F] PUNCH" next to a zombie in reach, "[R] GET UP" while you lie limp).

`Inventory.7n`: **Tab** (or I) projects a **holographic inventory** beside you, a glowing grid of
items with their pictures and counts and a details panel. WASD or the arrow keys pick a slot and
E uses it: med packs heal, a stasis pack slows the world for a few seconds.

Zombies have health now: each punch or slide tackle takes a third, and at 0 they stay down longer,
then get back up with full health (they're zombies). Zombies that catch you take 25 of your health
(at 0 you're knocked out for 5 seconds), and punches, slides and falls make sounds from `assets/sounds`.
