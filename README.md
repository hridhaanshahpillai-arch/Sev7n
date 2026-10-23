# Sev7n

A 2D and 3D game engine written in Java, with a Unity-style editor, its own scripting language
(**Seven**) and a visual scripting system (**Eights**).

> **Status: 1.0 "Together".** Sev7n is complete: a full engine from 2D to multiplayer.

**Download** from the **Releases** page:

| Your computer | Download | Then |
|---|---|---|
| Windows | `Sev7n-1.0-windows.zip` | double-click **Sev7n.exe** |
| Mac with Apple chip (M1, M2, M3...) | `Sev7n-1.0-mac-apple-silicon.zip` | double-click **Sev7n.command** (the first time: right-click it > Open, because it isn't signed) |
| Mac with Intel chip | `Sev7n-1.0-mac-intel.zip` | the same |
| Linux | `Sev7n-1.0-linux.zip` (or `-linux-arm64`) | run `./sev7n.sh` |

**Nothing else to install:** every download comes with **Java 21** inside (Eclipse Temurin, GPL with the
Classpath Exception). On a Mac or Linux it unpacks itself the first time Sev7n starts.

Models: **.glb / .gltf** (best) and **.obj** work straight away. Made a model in Blender? Use
File > Export > glTF 2.0 (.glb). `.blend` and `.fbx` files are converted automatically if Blender is installed.

You build scenes in the editor, then attach **behaviours** to objects, written in Seven code or
made by connecting Eights nodes:

```seven
behaviour PlayerMove {
    var speed = 5

    func update(dt) {
        var move = vec3()
        if key("w") { move.z -= 1 }
        if key("s") { move.z += 1 }
        transform.position += move * speed * dt
    }

    func onCollide(other) {
        if other.hasTag("coin") { other.destroy() }
    }
}
```

## What's in 1.0 "Together"

- **Multiplayer you can test alone**: `connect()` hosts, or joins the game already running on your PC, so two windows (Play + File > Play in Window) play together. Shared values, messages, players joining and leaving. Try the **Multiplayer** project in the Hub. See [Multiplayer](docs/MULTIPLAYER.md)
- **Sky**: a gradient sky with the sun, sunsets and nights that follow the Light, and presets (Day, Sunset, Night, Overcast, Alien)
- **Third Person Camera** component: follows and orbits a character, comes in front of walls; `cameraForward()` / `cameraRight()`. See [Cameras and Controls](docs/CONTROLS.md)
- **Gamepads**: `padButton`, `padPressed`, `padAxis` (Xbox, PlayStation, Switch Pro...)
- Also since 0.7.6: shadows, terrain sculpting, the audio engine (mixer, 3D sound, Sound Maker) and the Animation window
- Eights has nodes for all of it

## What's in 0.7.6 "Refined"

- **Undo and redo** for everything you do in a scene (Ctrl+Z, Ctrl+Y)
- **Parent and child objects**: drag one onto another in the Hierarchy and it follows it (a sword in a hand,
  a turret on a tank); scripts use `entity.parent` and `entity.children`
- **Seven** grew up: f-strings (`f"Score: {score}"`), maps (`{hp: 100}`), `a ? b : c`, `x ?? fallback`,
  `x in list`, default parameters, functions as values, and errors that suggest a fix ("did you mean 'speed'?")
- **Eights** can do everything Seven can: 270+ nodes, timers, delays, messages, NPC events
- **Scenes** fade between each other (`loadScene("Level2", 0.5)`) and can be duplicated, renamed and deleted
- **Physics**: fast things don't pass through walls, balls roll, `onCollideEnd(other)` when things stop touching
- **HUD anchors**: `anchor("bottomRight")` pins a HUD to a corner on any screen shape
- **Sound in the world**: `playSoundAt(path, position)` is quieter far away and comes from the left or right
- **NPCs** speed up, slow down and take corners smoothly, and step round things they're stuck on
- **Ragdolls** say when they've landed (`entity.ragdollResting`), **exports** can have their own icon

## What's in 0.7.5

- **First-person** (new): mouse-look (`lockMouse`, `mouseMoveX/Y`), the right mouse button, `entity.send("onHit", ...)`
  for shots and any other message, (a ready first-person script with shooting and a crosshair is in the guide)
- **Open worlds** (new): a **Terrain** component (hills, valleys, rock and snow, scattered trees) that characters walk
  on, ragdolls land on, raycasts hit and NPCs cross; anything past the fog isn't drawn; a day and night cycle script in the guide; scripts can change lights (`entity.lightIntensity`)

## Also in 0.7

- **Scenes** (new): as many as you like (levels, menus); File > New Scene / Open Scene, `loadScene("Level2")`
  from scripts, `setValue`/`getValue` to carry the score and more between them
- **Saving and loading** (new): `saveGame()` / `loadGame()` bring back every object, every script's variables
  and your values; several save slots
- **Prefabs** (new): right-click > Save as Prefab, Create > Prefab, `spawnPrefab("Zombie", position)` from scripts
- **fixedUpdate(dt)** (new): runs exactly 60 times a second on every PC
- **Groundwork for 1.0 multiplayer**: whole-world snapshots, a steady tick and stable object ids

## Also in 0.6

- **Sev7n Hub** (new): Sev7n opens on a Hub like Unity Hub: all your projects (search, open, add a folder,
  show in Explorer, remove from the list), **New project** (just a name and where it goes), **Installs** (this version, Vulkan, Blender, export: what works on this PC, and where to get new
  versions) and **Learn** (every guide, by topic). File > Open Hub... goes back to it from the editor
- **HUD** (new): on-screen text, boxes, bars and pictures from scripts (`text("Score: " + points, 24, 20)`,
  `bar(...)` for health), the same in every window size, with OpenGL and Vulkan
- **Game UI** (new): buttons, sliders, checkboxes and rounded panels that react to the mouse, title
  screens and pause menus that freeze the world (`setTimeScale(0)`), `restart()`, `quit()`, `lateUpdate()`
- **UI in the world** (new): holographic panels floating in 3D (`beginWorld`), prompts and lock-on
  brackets that follow things (`worldToScreen`), glowing visor-style drawing, radars, rings, lines,
  segmented bars and key icons
- **Sound** (new): `playSound("assets/sounds/hit.wav")`, looping music with `playMusic()` (.wav files)
- **Example**: the ragdoll playground now has a holographic inventory (Tab), a visor HUD with radar and
  lock-on, prompts pinned to zombies, a title screen, a pause menu (Esc), a health bar, a knockdown counter, sounds and a slide (C)

## Also in 0.5 and since

- **2D games** (new): flat orthographic cameras that can switch to 3D at any time, a 2D Scene view,
  sprite sheets with frames, flipping and draw order, a **Sprite Animator**, and **tilemaps**
  painted straight in the Scene view
- **Physics** (new): Rigidbodies with gravity, bounciness and drag, solid colliders and tilemaps,
  triggers, `entity.velocity` / `entity.grounded`
- **Export** (new): File → Export Game makes a standalone `.exe` (Java included) anyone can play
- **Sprite Editor** (new): crop frames exactly with rectangles, polygons (diagonal edges too),
  grids or automatic detection; move, resize, reorder, trim, set pivots, preview the animation
- **Settings** (new, click the 7): **OpenGL or Vulkan**, Lit / Flat / Toon / Wireframe shading, render scale,
  anti-aliasing, pixel textures, fog and render distance, brightness, frame rate limits and VSync
- **Animated 3D models** (new): characters from `.glb` / `.gltf` walk, run and fight with their own animations
  (Mesh Renderer **Model turn** fixes ones that face backwards),
  blending smoothly between them (`entity.play("Run")`); **tweens** move, spin, scale or fade anything in one line
- **Ragdolls** (new): characters go limp and tumble like real bodies (`entity.ragdoll(push)`), then get back
  up (`entity.getUp()`), blending into a get-up animation. Works with any humanoid rig
- **Example**: `projects/MyGame` is a ragdoll playground: walk with WASD, C to slide, R to go limp, F to punch zombies
- **NPCs and AI** (new): Create → NPC for characters that find their way around walls, see (blocked by walls)
  and hear (`makeNoise()`), chase, flee or watch, search where they last saw you, wander, patrol, follow
  a daily routine on the game clock, and remember who did what (`entity.remember(player, "helped", 20)`)
- **Physics** (expanded): sphere and capsule colliders, mass and pushing, friction, forces and impulses,
  kinematic bodies, locked axes, `raycast()` and `overlapSphere()`
- **Math** (expanded): easing curves, smooth noise, remap/smoothstep/moveTowards, angle helpers, vector rotate/reflect/lerp and more
- **Particle effects** (new): right-click → New Effect for fire, smoke, sparks, magic, explosions, fountains,
  debris, rain, snow or confetti. Cone, sphere, box, circle and line shapes; dots, pictures or 3D models;
  spinning, bouncing off colliders; tweak them live in the Scene view; `spawnEffect("Explosion", position)` from scripts
- **Vulkan** (new): games can draw with Vulkan instead of OpenGL (same look), falling back to OpenGL on PCs
  that can't run it. **File → Play in Window** (Ctrl+F5) shows your game exactly as players will see it
- **Live Mode** (new, the LIVE button next to Play): edit your game while it plays. Changes are kept when you stop,
  scripts reload the moment you save them (keeping the game's state), and script errors pause instead of stopping
- **In-editor guides**: F1 shows these docs with search
- **Asset import**: pictures, **glTF** (`.glb` / `.gltf`) and `.obj` models with their materials and textures,
  `.blend` / `.fbx` files (converted by Blender),
  `.zip` / `.rar` / `.7z` packs and folders. Drag files onto the window or use Import.
  Textures on meshes, **sprites** with transparency, and a Project panel with thumbnails
- **Eights visual scripting**: Blueprint-style node graphs that compile to Seven, with events,
  branches, loops, variables, a searchable node menu, and errors that point at the node
- **Real-time 3D** with OpenGL or Vulkan: lit meshes (cube, sphere, plane, cylinder), directional light, cameras
- **Editor** with dockable panels: Hierarchy, Scene view, Game view, Inspector, Console, Project, Script, Eights
- **Scene view**: orbit camera or flat 2D, click to select, move/rotate/scale gizmos (Ctrl to snap)
- **Play mode**: runs on a copy of the scene, so Stop puts everything back (like Unity), unless Live Mode is on
- **Built-in code editor** with Dracula Soft colors, auto-indent, undo, Ctrl+/ comments
- **Components**: Transform, Mesh Renderer, Sprite Renderer, Sprite Animator, Tilemap, Camera,
  Directional Light, Terrain, Box / Sphere / Capsule Collider, Rigidbody, Animator, Particle Effect, NPC, Ragdoll, Behaviour
- **Scenes saved as readable JSON**, scripts as plain `.7n` files, graphs as `.8s` files

## Running it from the source code

Needs **JDK 21+**. Double-click the **Sev7n** shortcut, or:

```bat
run.bat
```

`run.bat` builds the first time (downloading libraries into `lib\`), then opens the Sev7n Hub, where
you pick or make a project. Other commands:

```bat
build.bat                                :: rebuild build\sev7n.jar (close Sev7n first)
powershell -File release.ps1             :: make the download zip in release\ (Sev7n can stay open)
run.bat projects\MyGame                  :: open a project straight in the editor (no Hub)
run.bat projects\MyGame\scripts\X.7n     :: open a script in the editor
run.bat --play projects\MyGame           :: play the game in its own window, no editor
```

On **macOS or Linux** (JDK 21+, and `curl`):

```sh
./run.sh                                 # build the first time, then open the Hub
./build.sh                               # rebuild build/sev7n.jar
./run.sh --play projects/MyGame          # play a game without the editor
```

One `sev7n.jar` runs on all three systems. Exported games are for the system you export on
(export on a Mac to get a Mac `.app`, on Linux for a Linux program).

### Editor controls

| Where         | Controls                                                        |
|---------------|-----------------------------------------------------------------|
| Anywhere      | F5 play/stop · Ctrl+F5 play in a window · Ctrl+S save everything · Ctrl+Z undo · Ctrl+Y redo · Ctrl+D duplicate · Del delete · F1 guides |
| Scene view    | click select · right-drag orbit · middle-drag pan · wheel zoom · F focus |
| Scene view    | W move · E rotate · R scale (hold Ctrl to snap) · 2D button for the flat view |
| Painting tiles | click/drag paint · Shift erases · Esc stops                    |
| Game view     | click it while playing so the game gets the keyboard            |
| Hierarchy     | right-click for actions · drag onto an object to make it a child · drag onto a row's top edge to reorder · drag to empty space to unparent · click ▸ to fold |
| Project       | double-click to open · drag scripts onto objects · drag pictures/models into the scene |
| Window        | drop files from Explorer onto Sev7n to import them              |
| Eights        | right-click add node · drag pin to pin connect · Del delete · middle-drag pan |
| Console       | click an error to jump to its line                              |
| Script        | Ctrl+Z/Y undo/redo · Tab/Shift+Tab indent · Ctrl+/ comment · Ctrl+D duplicate line |

### Windows setup

`setup-windows.ps1` (current user only, no admin needed) creates Desktop and Start menu shortcuts
and makes `.7n` files open in Sev7n:

```bat
powershell -ExecutionPolicy Bypass -File setup-windows.ps1
```

## Learn Seven

In the editor, press **F1** (or **Help → Guides**) to read these guides with search and a contents list:

- [docs/SEVEN.md](docs/SEVEN.md): the Seven language reference
- [docs/EIGHTS.md](docs/EIGHTS.md): Eights visual scripting
- [docs/ASSETS.md](docs/ASSETS.md): importing pictures, models and packs
- [docs/2D.md](docs/2D.md): 2D cameras, sprites, sprite animation and tilemaps
- [docs/PHYSICS.md](docs/PHYSICS.md): Rigidbodies, colliders and triggers
- [docs/ANIMATION.md](docs/ANIMATION.md): animated 3D models and tweens
- [docs/WORLDS.md](docs/WORLDS.md): first-person controls, shooting, terrain and open worlds
- [docs/SCENES.md](docs/SCENES.md): scenes, saving and loading, prefabs, fixedUpdate
- [docs/HUD.md](docs/HUD.md): on-screen text, health bars, buttons and menus, sound
- [docs/RAGDOLLS.md](docs/RAGDOLLS.md): ragdolls, and the example playground
- [docs/NPC.md](docs/NPC.md): NPCs: pathfinding, senses, memory and routines
- [docs/EFFECTS.md](docs/EFFECTS.md): particle effects
- [docs/EXPORT.md](docs/EXPORT.md): turning your project into a game others can play
- [docs/SETTINGS.md](docs/SETTINGS.md): graphics (OpenGL / Vulkan), frame rate, editor settings and Live Mode

## Project layout

```
src/sev7n/
  Sev7n.java          entry point
  lang/               the Seven language: lexer, parser, interpreter (no engine code)
  math/               Vec3, shared by the scene and scripts
  scene/              entities, components, scenes, JSON save/load
  render/             the scene renderer, asset cache and model loading; GlBackend draws with OpenGL
  render/vulkan/      VulkanBackend and VulkanPresenter: drawing with Vulkan in the game window
  game/               the runtime: behaviours, input, physics, collisions, the API scripts see
  eights/             Eights graphs: node types, wiring rules, .8s files, the graph-to-Seven compiler
  project/            project folders (main.scene, scripts/, assets/), the asset importer, the exporter
  editor/             the ImGui editor: panels, code editor, gizmos, guides, theme
  player/             standalone game window (--play and exported games)
  ui/                 Dracula Soft colors and the app icon
projects/MyGame/      your game: main.scene, scripts/ (.7n and .8s), assets/ (imported files)
exports/              exported games (File → Export Game)
lib/                  downloaded libraries (LWJGL, JOML, Dear ImGui), see get-libs.ps1
```

Layers depend downward only: `editor` → `game` / `render` → `project` / `eights` → `scene` → `lang` / `math`.

## Libraries

[LWJGL](https://www.lwjgl.org) (OpenGL, Vulkan, windowing, and shaderc to compile Vulkan shaders), [JOML](https://github.com/JOML-CI/JOML) (3D math),
[imgui-java](https://github.com/SpaiR/imgui-java) (Dear ImGui, ImGuizmo, imnodes). Downloaded from Maven Central
and checksum-verified by `get-libs.ps1`.

## Roadmap ideas

- **0.8: graphics**: shadows, sky, water, grass, bloom, terrain painted with your own pictures, a level builder
  (like ProBuilder), an Animator and Animation window, and an audio engine (mixer, effects)
- **1.0: multiplayer** (local and online), built on 0.7's snapshots, steady tick and stable ids
- UI: text boxes for typing, scrolling lists, gamepad menus
- Echo shaped by the room (acoustics)
- More model formats (.fbx without Blender)
- Eights: comment boxes, custom functions
- Physics: tumbling boxes, joints, active ragdolls (muscles that keep balance)
- Animation: morph targets (faces), animation events

## License

Sev7n is free and open source under the [MIT License](LICENSE): use it, change it, share it, and
sell games made with it. Just keep the license notice with the engine's code.
The libraries it uses are open source too (LWJGL: BSD, JOML: MIT, imgui-java: MIT, shaderc: Apache 2.0).
