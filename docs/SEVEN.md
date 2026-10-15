# The Seven Language (v0.7.5 alpha)

Seven is the scripting language of the Sev7n engine. It mixes:

- **Java / C# / C++:** curly braces, `if` / `while` / `for`, functions
- **Unity-style behaviours:** scripts you attach to objects in the editor
- **CSS:** `style` blocks for how things look

Script files use the `.7n` extension and live in your project's `scripts/` folder.
Prefer connecting nodes to typing? See [Eights](EIGHTS.md): its graphs turn into Seven code.

---

## Behaviours

You build a scene in the editor, then attach **behaviours** to objects to make them do things.
A behaviour is a template: every object it's attached to gets its own copy with its own variables.

```seven
// scripts/Spinner.7n
behaviour Spinner {
    var speed = 90          // fields: each object has its own copy

    func start() {          // once, when Play starts
        print(entity.name, "is ready")
    }

    func update(dt) {       // every frame. dt = seconds since the last frame
        transform.rotation.y += speed * dt
    }

    func onCollide(other) { // when this object's Box Collider starts touching another (or lands on it)
        print("bumped into", other.name)
    }
}
```

To attach it, do one of these:

- drag `Spinner.7n` from the **Project** panel onto an object in the **Hierarchy**
- or select the object and click **Add Component → Behaviour → Spinner** in the **Inspector**

Inside a behaviour, two names are always available:

| Name        | What it is                                  |
|-------------|---------------------------------------------|
| `entity`    | the object this behaviour is attached to    |
| `transform` | that object's position, rotation and scale  |

`func update()` without the `dt` parameter is fine too. Any function can be left out.
Name the file after the behaviour (`Spinner.7n` holds `behaviour Spinner`) so the editor can list it.

---

## Basics

```seven
// line comment
/* block
   comment */

var name = "Sev7n"      // strings: "double" or 'single' quotes
var lives = 3           // numbers (always decimals under the hood)
var alive = true        // bools: true / false
var target = none       // none = "no value"
var tint = #bf9eee      // colors: #RGB, #RRGGBB, #RRGGBBAA
var spot = vec3(1, 2, 3)
```

Semicolons are optional. Variables must be declared with `var` before use. This catches typos.

### Operators

| Kind       | Operators                                  |
|------------|--------------------------------------------|
| Math       | `+ - * / %` (`%` is always positive: `-1 % 5 == 4`) |
| Assign     | `= += -= *= /= %=`                         |
| Compare    | `== != < <= > >=`                          |
| Logic      | `and or not` (also `&& \|\| !`)            |
| In         | `x in list`, `"key" in map`, `"ell" in "hello"`, `x in 0..10` |
| Choose     | `condition ? this : that` (`health < 30 ? #ff5555 : #50fa7b`) |
| Or else    | `value ?? fallback`: the value, or the fallback if it's `none` (`find("Boss") ?? entity`) |
| Range      | `0..10` → 0 up to 9; `10..0` → 10 down to 1 |

### Text with values in it: f-strings

Put `f` before a string and anything in `{braces}` is worked out and joined in:

```seven
text(f"Score: {score}   Lives: {lives}", 40, 40)
print(f"{entity.name} is at {round(transform.position.y, 1)} m")
print(f"Use \{ and \} for real braces")
```

`+` also joins strings: `"HP: " + hp`.

### Control flow

Parentheses around conditions are optional. Braces are required.

```seven
if hp <= 0 {
    entity.destroy()
} else if hp < 20 {
    entity.color = #ee6666
}

while enemies > 0 { enemies -= 1 }

for i in 0..5 { print(i) }          // 0 1 2 3 4
for item in inventory { print(item) }
```

`break` and `continue` work in all loops.

### Functions

```seven
func add(a, b) {
    return a + b
}
```

Functions can live inside a behaviour (they can use its fields) or at the top of a script file
(any behaviour can call them).

**Defaults:** a parameter can have a value it uses when it's left out. Once one has a default,
the ones after it need one too.

```seven
func hurt(damage, knockback = vec3(), sound = "assets/sounds/hit.wav") { ... }
hurt(10)                         // knockback = vec3(), the default sound
hurt(10, vec3(0, 3, 0))
```

**Functions as values:** `func(x) { ... }` without a name is a function you can keep in a variable
or hand to another function (callbacks, sorting, filtering):

```seven
var double = func(x) { return x * 2 }
print(double(4))                                              // 8
tween(entity, "scale", vec3(2), 0.5, "outBack", func() { print("grown!") })
var alive = filter(enemies, func(e) { return e.behaviour("Enemy").health > 0 })
```

### Lists

```seven
var items = [1, 2, 3]
push(items, 4)
print(items[0], items[-1], len(items))   // 1 4 4
items[1] = 20
var last = pop(items)
remove(items, 0)            // remove by index
contains(items, 20)         // true (or: 20 in items)
```

| Function | Does |
|----------|------|
| `push(list, x)`, `pop(list)`, `insert(list, i, x)` | add to the end, take off the end, put in at a position |
| `remove(list, i)`, `removeItem(list, x)`, `clear(list)` | take out by position, take out a matching item, empty it |
| `indexOf(list, x)` | where it is, or -1 |
| `slice(list, from, [to])` | a copy of part of it (negative numbers count from the end) |
| `sort(list)`, `sort(list, func(x) { return ... })` | in order, or in order of what the function gives |
| `reverse(list)`, `shuffle(list)`, `copy(list)` | backwards, mixed up, a separate copy |
| `filter(list, test)`, `map(list, change)` | the items the test says true for; a list of what the function gives for each |
| `first(list, test)`, `any(list, test)`, `all(list, test)` | the first match (or none); is any / is every item a match? |
| `choose(list)`, `len(list)` | a random item; how many |

### Maps

A map keeps values by name: an inventory, a character's stats, settings.

```seven
var stats = {hp: 100, speed: 5, "full name": "Bo"}
print(stats.hp, stats["full name"])
stats.hp -= 10              // change one
stats.armor = 3             // add one
if "armor" in stats { ... }
for key in stats { print(key, stats[key]) }
```

`keys(map)`, `values(map)`, `len(map)`, `has(map, key)`, `get(map, key, fallback)` (the fallback
when the key isn't there), `remove(map, key)`. Maps save with `saveGame()` like lists do.

### Text

`upper lower trim` · `replace(text, find, with)` · `split(text, ",")` → a list · `join(list, ", ")` → text ·
`startsWith endsWith` · `repeat("ab", 3)` · `slice(text, 1, -1)` · `indexOf(text, piece)` ·
`fixed(3.14159, 2)` → `"3.14"` (always that many decimals) · `fixed(7, 0, 2)` → `"07"` (for clocks)

---

## 3D vectors: `vec3`

Positions, rotations, scales and directions are `vec3`s.

```seven
var v = vec3(1, 2, 3)       // also vec3() = 0,0,0 and vec3(5) = 5,5,5
v.x = 10
var w = v + vec3(0, 1, 0)   // + and - between vec3s
var half = v * 0.5          // * and / with a number
var back = -v

v.length                    // how long it is
v.normalized()              // same direction, length 1
v.dot(w)   v.cross(w)   v.distance(w)   v.copy()
v.lerp(w, 0.5)              // halfway from v to w
v.moveTowards(w, 2 * dt)    // a step toward w, never past it
v.angle(w)                  // degrees between two directions
v.rotate(up(), 90)          // turned 90 degrees around an axis
v.reflect(normal)           // bounced off a surface (like a ball off a wall)
v.project(w)                // the part of v that points along w
v.clampLength(5)            // at most 5 long (a speed limit)
```

`vec2(x, y)` is `vec3(x, y, 0)` for 2D games, and `up() down() left() right() forward() back()`
are handy directions.

Moving is usually "direction × speed × dt":

```seven
transform.position += transform.forward * 5 * dt
```

---

## `entity` and `transform`

### transform

| Property / method          | Meaning                                         |
|----------------------------|-------------------------------------------------|
| `position`                 | where it is (vec3)                              |
| `rotation`                 | rotation in degrees around X, Y, Z (vec3)       |
| `scale`                    | size (vec3)                                     |
| `forward`, `right`, `up`   | the directions it's facing (read-only vec3s)    |
| `translate(v)`             | move by `v`                                     |
| `rotate(v)`                | turn by `v` degrees                             |
| `lookAt(point)`            | turn to face a point                            |

Y is up. An object with no rotation faces **-Z** ("forward").

### entity

| Property / method          | Meaning                                         |
|----------------------------|-------------------------------------------------|
| `name`                     | its name in the Hierarchy                       |
| `active`                   | `false` hides it and stops its behaviours       |
| `tags`                     | list of its tags (set them in the Inspector)    |
| `transform`, `position`    | shortcuts to its transform / position           |
| `color`, `mesh`            | its Mesh Renderer's color and shape (built-in name or model path) |
| `texture`                  | its picture, e.g. `"assets/textures/brick.png"` (sprite or mesh) |
| `style = someStyle`        | apply a CSS-like style (see below)              |
| `hasTag(t)` `addTag(t)` `removeTag(t)` | tags                                |
| `destroy()`                | removes it at the end of the frame              |
| `addBehaviour("Name")`     | attaches another behaviour while playing        |
| `behaviour("Name")`        | another behaviour on the same object, or `none` |
| `parent`                   | the object it follows, or `none`; `entity.parent = other` makes it follow `other` (`none` lets go) |
| `children`                 | a list of the objects that follow it            |

With the matching component on the object ([2D games](2D.md), [physics](PHYSICS.md)):

| Property / method          | Needs            | Meaning                                    |
|----------------------------|------------------|--------------------------------------------|
| `velocity`                 | Rigidbody        | how fast it's moving (vec3, changeable)    |
| `grounded`                 | Rigidbody        | true while standing on something           |
| `push(v)`                  | Rigidbody        | adds to its velocity                       |
| `addForce(v)`, `addImpulse(v)` | Rigidbody    | a steady push (every frame) or a sudden kick; heavier things move less |
| `mass`, `kinematic`        | Rigidbody        | how heavy it is; whether only scripts move it (changeable) |
| `play("Walk", [fade])`     | Animator         | switch a 3D model's animation, blending over `fade` seconds ([animation](ANIMATION.md)) |
| `animationSpeed`, `animations` | Animator     | how fast it plays (changeable); the model's animation names |
| `ragdoll([push])`, `getUp()`, `ragdolling` | Ragdoll | go limp like a real body, stand back up, whether it's limp ([ragdolls](RAGDOLLS.md)) |
| `moveTo(place)`, `chase(e)`, `flee(e)`, `remember(e, "event", n)`... | NPC | walk around walls, react, remember ([NPCs](NPC.md)) |
| `flipX`, `flipY`           | Sprite Renderer  | mirror the sprite                          |
| `frame`                    | Sprite Renderer  | which sprite sheet frame is showing        |
| `play("run")`              | Sprite Animator  | switch animation                           |
| `animation`, `animationDone` | Sprite Animator | the animation playing, and whether a non-looping one has finished |
| `camera`                   | Camera           | `.orthographic` `.size` `.fieldOfView` `.background` |
| `tile(x, y)`, `setTile(x, y, t)` | Tilemap    | read or paint a cell (`none` = empty)      |

Every object has one `entity`, so `other == find("Player")` works.

---

## Styles (the CSS part)

A `style` describes how something looks:

```seven
style gold {
    color: #e7ee98;
    mesh: sphere;           // bare words become strings: sphere == "sphere"
}

behaviour Coin {
    func start() {
        entity.style = gold
    }
}
```

Styles can change at runtime. Use camelCase for hyphenated names: `gold.someProperty = 3`.

---

## Built-in functions

### Input (keyboard reaches the game while the Game view is focused: click it)

| Function             | Returns                                       |
|----------------------|-----------------------------------------------|
| `key(name)`          | true while the key is held                    |
| `keyPressed(name)`   | true only on the frame it went down           |
| `keyReleased(name)`  | true only on the frame it came up             |
| `mouseX()`, `mouseY()` | mouse position in the Game view, in pixels  |
| `mouseDown()`        | true while the left button is held            |
| `mouseClicked()`     | true only on the frame it was clicked         |

Key names (any case): `left right up down space enter escape shift ctrl alt tab backspace`,
letters `a`–`z`, digits `0`–`9`.

### Scene

| Function                    | Does                                             |
|-----------------------------|--------------------------------------------------|
| `find(name)`                | the entity with that name, or `none`             |
| `findAll(tag)`              | a list of every entity with that tag             |
| `spawn(mesh, [position])`   | creates a new object (with a Box Collider)       |

Meshes: `cube`, `sphere`, `plane`, `cylinder`, or an imported model like `"assets/models/rock/rock.obj"`
(see [importing assets](ASSETS.md)).

### Physics

`gravity()` (a vec3), `setGravity(v)`, `solidAt(point)` (true if a solid collider or tile is there),
`raycast(from, direction, [maxDistance], [ignore])` (the first thing hit, or `none`),
`overlapSphere(center, radius)` (every entity there). See [physics](PHYSICS.md).

### Tweens and effects

`tween(entity, "position", vec3(0, 3, 0), 1.5, "outBounce", [whenDone])` moves smoothly; also
`"rotation"`, `"scale"`, `"color"`, `"tint"`, `"size"`. `stopTweens(entity)`. `spawnEffect("Explosion", position)`.
See [animation](ANIMATION.md) and [particle effects](EFFECTS.md).

### Math

| Function | Gives |
|----------|-------|
| `abs sqrt pow exp log sign floor ceil` | the usual; `log(x, base)` too |
| `round(x)`, `round(x, 2)` | whole number, or 2 decimal places |
| `sin cos tan asin acos atan atan2` | trigonometry, in radians; `deg(r)` and `rad(d)` convert |
| `min(...)`, `max(...)`, `sum(...)`, `average(...)` | several numbers, or one list |
| `clamp(v, lo, hi)` | v kept between lo and hi |
| `lerp(a, b, t)` | `t` of the way from a to b: numbers, vec3s or colors |
| `inverseLerp(a, b, v)`, `remap(v, a1, b1, a2, b2)` | where v is between a and b; v moved from one range to another |
| `smoothstep(a, b, x)` | 0 to 1 as x goes a to b, easing in and out |
| `moveTowards(current, target, step)` | a step closer, never past it (numbers or vec3s) |
| `deltaAngle(a, b)`, `lerpAngle(a, b, t)` | the shortest turn between two angles in degrees, and blending angles the short way |
| `wrap(x, min, max)`, `pingPong(t, length)` | goes round like a clock; goes back and forth |
| `ease("outBounce", t)` | an easing curve: `linear`, and `in`/`out`/`inOut` + `Sine Quad Cubic Quart Expo Back Elastic Bounce` |
| `noise(x, [y], [z])` | smooth randomness 0..1 (terrain, flicker, wandering) |
| `random()`, `random(max)`, `random(min, max)`, `randint(min, max)` | random numbers |
| `chance(0.25)`, `choose(list)`, `shuffle(list)`, `randomDirection([flat])` | true a quarter of the time; a random item; mixed up; a random direction |
| `seed(n)` | the same random numbers and noise every run |
| `distance(a, b)` | between two vec3s (or `x1, y1, x2, y2`) |
| `PI`, `TAU`, `E`, `INFINITY` | constants |

### Other

`time()` (seconds since Play), `print(...)` (to the Console), `str(v)`, `num(s)`, `type(v)`,
`len(list or string)`, `rgb(r, g, b, [a])`, `hsv(hue, saturation, brightness, [a])`, `vec3(...)`, `vec2(x, y)`

`"text " + anything` joins them into text: `print("at " + transform.position)`.

---

## Errors

An error stops the game and appears in the **Console** with its file and line,
like `PlayerMove.7n:12: Unknown variable 'spedd'. Did you mean 'speed'?`. Click it to jump to that line.
Misspelled properties get a suggestion too (`entity.positon` → did you mean `position`?).
A script that runs too long without finishing (an infinite loop, for example) is stopped automatically.
