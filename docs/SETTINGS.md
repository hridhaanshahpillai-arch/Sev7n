# Settings (v0.8 alpha)

Click the purple **7** in the menu bar (or **Window → Settings...**, or **Ctrl+,**).
Everything applies straight away.

## Game graphics

How your game looks. These are saved with your project (`game.json`), so exported games
look exactly the same. The **Game** view shows the result; the Scene view shows the shading
and fog too.

| Setting | What it does |
|---------|--------------|
| **Preset** | **Fancy** (sharpest, smoothest), **Fast** (for slower PCs), **Retro** (chunky pixels, flat colors) |
| **Graphics API** | What draws the game window: **OpenGL** or **Vulkan** (see below) |
| **Shading** | **Lit**: normal light and shine. **Flat**: no lighting, full colors. **Toon**: cartoon look with hard bands of light. **Wireframe**: just the edges of every triangle |
| **Render scale** | How many pixels the game draws compared to the window. Below 100% gives chunky retro pixels (and runs faster); above 100% is extra smooth |
| **Anti-aliasing** | Smooths jagged edges: Off, 2x, 4x or 8x |
| **Pixel textures** | Every picture and texture keeps sharp pixels instead of being smoothed |
| **Brightness** | Moody, Normal or Bright |
| **Shadows** | Shadows from the Directional Light: **Off**, **Low** (soft, fast), **Medium** (the default), **High** (crisp) |
| **Shadow distance** | How far from the camera shadows are drawn. Shorter is sharper; past it they fade out |
| **Fog** + **Render distance** | Far things fade into the camera's background color; the distance is how far you can see |

## Shadows

The scene's **Directional Light** (the Sun) casts shadows from every Mesh Renderer, terrain and
tree, in the Scene view, the Game view and the game window. Shadows follow the camera: everything
within the **Shadow distance** gets them, and they fade out gently past it.

| Where | Setting | What it does |
|-------|---------|--------------|
| Settings | **Shadows** | How sharp: Off, Low, Medium or High (High looks best, Low is fastest) |
| Settings | **Shadow distance** | 60 by default. A small level looks sharper at 30; a big open world may want 120 |
| Directional Light | **Shadows** | Turn this light's shadows off |
| Directional Light | **Strength** (under Shadows) | How dark shadows are: 0 = invisible, 1 = only the Ambient light gets in |
| Mesh Renderer | **Cast shadows** | Turn off for glass, ghosts, or far scenery that doesn't need them |

Textured models with see-through parts (leaves, fences) let light through those parts.
**Flat** and **Wireframe** shading have no lighting, so no shadows; **Toon** gets hard-edged ones.

From scripts:

```seven
entity.lightShadows = false          // on the Sun: no shadows (inside a cave, say)
entity.lightShadowStrength = 0.4     // softer, lighter shadows
find("Ghost").castShadows = false
```

In Eights: **Set Light Shadows**, **Set Shadow Strength** and **Set Casts Shadows** (World).

## Graphics API: OpenGL or Vulkan

A graphics API is how a program talks to the graphics card.

- **OpenGL** works on every PC. It's the default.
- **Vulkan** is the newer, lower-level API that big games use. The CPU does less work per
  frame, and on laptops with two graphics cards it picks the faster one by itself.

Settings shows whether Vulkan works on your PC and which graphics card it would use.
Your game looks the same either way.

Vulkan is used by the **game window**: **File → Play in Window** (**Ctrl+F5**), `run.bat --play`,
and exported games. The editor itself (and its Game view) always draws with OpenGL.

If a player's PC can't run Vulkan, the game says why in the terminal and uses OpenGL
instead, so choosing Vulkan never stops your game from starting.

For engine developers: set the environment variable `SEV7N_VULKAN_DEBUG=1` to print
Vulkan's validation messages (needs the Vulkan SDK installed).

## Frame rate

| Setting | What it does |
|---------|--------------|
| **Max FPS** (your game) | Locks the game's frame rate: 30, 60, 120, 144, 240 or Unlimited |
| **VSync** (your game) | Waits for the screen to refresh: no tearing, usually locks to the screen's rate |
| **Max FPS** / **VSync** (the editor) | The same for the editor. A lower limit keeps your PC cooler and quieter while you build |
| **Show FPS** | The frame rate in the menu bar |

The game's frame rate settings are used when it runs on its own (Play in Window, exported, or `run.bat --play`).

## Editor

Saved on this PC only.

| Setting | What it does |
|---------|--------------|
| **UI size** | Make the editor's text and controls bigger or smaller |
| **Scene grid** | The floor grid in the Scene view |
| **Show colliders** | Draws every Box Collider in the Scene view: green for solid, yellow for triggers |
| **Live Mode** | Edit your game while it plays (also the **LIVE** button next to Play, see below) |

## Live Mode

With **LIVE** on (the pill next to Play/Pause, purple when on), you can build your game while
it's running:

- **Changes you make while playing are kept** when you press Stop: move things, change
  colors, add or delete objects, edit sprite frames, rename things. Only what *you* changed is
  kept. If a script is moving the player and you change its color, the saved player gets the
  new color but stays where you put it.
- **Scripts reload as soon as you save them** (in Sev7n or any other editor), without
  restarting the game. Variables keep their current values, unless you changed the starting
  value in the code: change `var speed = 5` to `var speed = 10`, save, and the new speed is
  used straight away.
- **A script error pauses the game instead of stopping it.** Fix the script, save, and the
  game carries on from where it was.
- Behaviours you add in the Inspector while playing start straight away.
- **Ctrl+S** saves the scene even while playing.

With **LIVE** off, Play works like Unity: everything goes back to how it was when you press Stop.
