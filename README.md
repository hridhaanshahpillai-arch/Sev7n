# Sev7n

A 2D and 3D game engine written in Java, with a Unity-style editor, its own scripting language
(**Seven**) and a visual scripting system (**Eights**).

> **Status: 0.5 alpha.** Expect things to change.

**Download (Windows):** get `Sev7n-0.5-alpha-windows.zip` from the **Releases** page, unzip it anywhere,
and double-click **Sev7n.exe**. Java is included, so there's nothing else to install.

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

## What's in 0.5

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
  Directional Light, Box Collider, Rigidbody, Behaviour
- **Scenes saved as readable JSON**, scripts as plain `.7n` files, graphs as `.8s` files

## Running it from the source code

Needs **JDK 21+**. Double-click the **Sev7n** shortcut, or:

```bat
run.bat
```

`run.bat` builds the first time (downloading libraries into `lib\`), then opens the editor on
`projects\MyGame`. Other commands:

```bat
build.bat                                :: rebuild build\sev7n.jar (close Sev7n first)
powershell -File release.ps1             :: make the download zip in release\ (Sev7n can stay open)
run.bat projects\MyGame\scripts\X.7n     :: open a script in the editor
run.bat --play projects\MyGame           :: play the game in its own window, no editor
```

### Editor controls

| Where         | Controls                                                        |
|---------------|-----------------------------------------------------------------|
| Anywhere      | F5 play/stop · Ctrl+F5 play in a window · Ctrl+S save everything · Ctrl+D duplicate · Del delete · F1 guides |
| Scene view    | click select · right-drag orbit · middle-drag pan · wheel zoom · F focus |
| Scene view    | W move · E rotate · R scale (hold Ctrl to snap) · 2D button for the flat view |
| Painting tiles | click/drag paint · Shift erases · Esc stops                    |
| Game view     | click it while playing so the game gets the keyboard            |
| Hierarchy     | right-click for actions · drag to reorder                       |
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

- On-screen text and HUD (score, health bars, menus)
- Sound playback
- More model formats (.fbx without Blender)
- Eights: comment boxes, custom functions, more nodes
- Physics: slopes, rotating bodies
- Parent/child objects in the Hierarchy
- Undo/redo for scene edits
- Multiple scenes, prefabs

## License

Sev7n is free and open source under the [MIT License](LICENSE): use it, change it, share it, and
sell games made with it. Just keep the license notice with the engine's code.
The libraries it uses are open source too (LWJGL: BSD, JOML: MIT, imgui-java: MIT, shaderc: Apache 2.0).
