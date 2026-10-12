# Exporting a game (v0.5 alpha)

Export turns your project into a game anyone can play, without Sev7n.

## How

1. **File → Export Game...**
2. Type the game's name.
3. Leave **Include Java** ticked (recommended).
4. Click **Export**. It takes a few seconds; the Console says when it's done.
5. Click **Show in Explorer** or **Play it**.

Exports go to `Sev7n\exports\GameName\`. Exporting again replaces the old export.

## What you get

| Include Java | Result | Size | Players need |
|--------------|--------|------|--------------|
| On | A folder with **GameName.exe** | about 85 MB plus your assets | Windows, nothing else |
| Off | A folder with **Play.bat** | a few MB | Java 21 installed |

To share it, zip the whole folder (right-click → **Compress to ZIP file**) and send the zip.
Players unzip it and double-click the .exe. Keep the folder together: the .exe needs the
files next to it.

## In the exported game

- It opens in a window titled with your game's name.
- It looks and runs as set in **Settings → Game graphics**, including the graphics API: with
  **Vulkan**, PCs that can't run Vulkan use OpenGL instead.
- **File → Play in Window** (Ctrl+F5) in the editor shows exactly this, without exporting.
- **F11** switches fullscreen on and off.
- If a script error stops the game, the window title says what went wrong.

## Good to know

- Export saves everything first, so the export matches what's in the editor.
- Only the scene, `scripts\`, `assets\` and the graphics settings (`game.json`) are included.
- Export works from the built Sev7n (the Desktop shortcut). "Include Java" needs a full JDK,
  which Sev7n's setup installs.
- Windows may warn about an unknown app the first time someone runs your game, because
  it isn't signed. They can click **More info → Run anyway**.
