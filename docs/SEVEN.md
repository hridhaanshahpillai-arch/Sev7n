# The Seven Language (v0.5 alpha)

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
| Assign     | `= += -= *= /=`                            |
| Compare    | `== != < <= > >=`                          |
| Logic      | `and or not` (also `&& \|\| !`)            |
| Range      | `0..10` → 0 up to 9; `10..0` → 10 down to 1 |

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

### Lists

```seven
var items = [1, 2, 3]
push(items, 4)
print(items[0], items[-1], len(items))   // 1 4 4
items[1] = 20
var last = pop(items)
remove(items, 0)            // remove by index
contains(items, 20)         // true
```

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
```

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

With the matching component on the object ([2D games](2D.md), [physics](PHYSICS.md)):

| Property / method          | Needs            | Meaning                                    |
|----------------------------|------------------|--------------------------------------------|
| `velocity`                 | Rigidbody        | how fast it's moving (vec3, changeable)    |
| `grounded`                 | Rigidbody        | true while standing on something           |
| `push(v)`                  | Rigidbody        | adds to its velocity                       |
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

`gravity()` (a vec3), `setGravity(v)`, `solidAt(point)` (true if a solid collider or tile is there)

### Time & math

`time()` (seconds since Play), `abs sqrt sin cos atan2 floor ceil round min max`,
`clamp(v, lo, hi)`, `lerp(a, b, t)`, `distance(a, b)`,
`random()` / `random(max)` / `random(min, max)`, `randint(min, max)`, `PI`

### Other

`print(...)` (to the Console), `str(v)`, `num(s)`, `type(v)`, `len(list or string)`, `rgb(r, g, b, [a])`, `vec3(...)`

---

## Errors

An error stops the game and appears in the **Console** with its file and line,
like `PlayerMove.7n:12: Unknown variable 'spedd'`. Click it to jump to that line.
A script that runs too long without finishing (an infinite loop, for example) is stopped automatically.
