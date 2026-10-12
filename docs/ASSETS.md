# Importing assets (v0.5 alpha)

Bring pictures, 3D models and asset packs into your project, then use them in your scenes.

## How to import

- **Drag files from Explorer onto the Sev7n window**, or
- click **Import...** in the **Project** panel (or **File → Import Assets...**) and pick files.

Importing happens in the background; the **Console** says what happened to each file.
Files are copied into your project, so the originals are never changed. Importing something
twice keeps both (the second becomes `name (1).png`).

## What you can import

| File | What happens | Goes to |
|------|--------------|---------|
| Pictures: `.png` `.jpg` `.jpeg` `.bmp` `.gif` | ready to use as textures and sprites | `assets/textures/` |
| Models: **`.glb` `.gltf`** (best) | ready, with their materials, colors and textures | `assets/models/Name/` |
| Models: `.obj` | copied with its `.mtl` material and textures | `assets/models/Name/` |
| `.blend` and `.fbx` | converted to `.glb` by Blender (see below) | `assets/models/Name/` |
| Packs: `.zip` | unpacked | `assets/Name/` |
| Packs: `.rar` `.7z` `.tar` `.gz` | unpacked with 7-Zip if installed, otherwise Windows' built-in tar | `assets/Name/` |
| Folders | copied as they are | `assets/Name/` |
| Scripts: `.7n` `.8s` | ready to attach | `scripts/` |
| Sounds: `.wav` `.ogg` `.mp3` | stored (playing sounds comes in a later version) | `assets/sounds/` |
| `.dae` `.3ds` `.stl` `.ply` | stored, with a note: export as `.glb` | `assets/models/` |

### Which model format?

Use **glTF** (`.glb`, or `.gltf` with its files) whenever you can. It's the open standard for
3D models, it keeps everything in one file (mesh, materials, colors and textures), and almost
every tool and asset site offers it: Blender, Sketchfab, Kenney, Poly Pizza, Mixamo, itch.io.
A model with several materials keeps all of them.

Rigged (animated) characters show in their rest pose; playing their animations comes later.
Models compressed with Draco need exporting again without compression.

### Blender and FBX files

`.blend` is Blender's own working format and `.fbx` is a closed format, so Sev7n asks Blender
to convert them to `.glb`. Install Blender (free, [blender.org](https://www.blender.org/download/));
Sev7n finds it automatically (Program Files, Steam, or PATH). Then import again, or right-click
the file in the Project panel and choose **Convert to .glb with Blender**.

Packs often include the same model as `.glb` too; Sev7n uses that and skips converting.
No Blender? Open the file in Blender on another PC and use **File → Export → glTF 2.0 (.glb)**.

## Using assets

| Do this | To |
|---------|----|
| Drag a picture or model into the **Scene** view | place it where the mouse points on the ground |
| Drag it into empty space in the **Hierarchy** | place it at the center |
| Drag a picture onto an object in the Hierarchy | put the picture on it (texture or sprite) |
| Drag a model onto an object | swap its shape for the model |
| Double-click a picture or model in the Project panel | add it to the scene |
| Inspector → **Mesh** | pick a built-in shape or an imported model |
| Inspector → **Texture** / **Picture** | pick a picture (or drop one on the field) |

Models arrive with their own materials, colors and textures. A Mesh Renderer's **Color** tints
the whole model, and its **Texture** (if set) replaces the model's own pictures.

### Sprites

A picture placed in the scene becomes a **Sprite Renderer**: a flat card the shape of the picture,
with see-through parts kept see-through. Settings:

- **Tint**: multiplies the picture's colors (white = unchanged)
- **Size**: height in world units
- **Billboard**: always face the camera
- **Pixel art**: keep pixels sharp instead of smoothing them

Add an empty one with **Create → Sprite**.

## In scripts

```seven
entity.texture = "assets/textures/hurt.png"            // change a picture
var rock = spawn("assets/models/rock/rock.glb", vec3(0, 0, -5))
```

In Eights, use the **Set Texture** node.

## Good to know

- If you edit a picture or model on disk (in Paint, Blender...), Sev7n reloads it automatically.
- The Project panel's right-click menu has **Show in Explorer** and **Move to Recycle Bin**.
