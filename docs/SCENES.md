# Scenes, Saves and Prefabs (v0.7.5 alpha)

## Scenes

A game can have as many scenes as you like: levels, a title screen, a shop, a game-over screen.
`main.scene` is where the game starts; the others live in the project's `scenes` folder.

- **File > New Scene...** makes one (with a camera) and opens it.
- **File > Open Scene** switches between them (the one you leave is saved first if it changed).
  Double-clicking a scene in the Project panel opens it too.
- **File > Duplicate Scene...** copies the open scene under a new name (make Level3 from Level2).
- **File > Rename Scene...** and **Delete Scene...** (deleted scenes go to the Recycle Bin).
  Main keeps its name and can't be deleted: it's where the game starts.
- The menu bar shows the project and the scene you're editing: `MyGame / Level2`.
- **Play** plays the scene you're editing.

Scripts switch scenes:

```seven
func onCollide(other) {
    if other.hasTag("player") { loadScene("Level2") }     // at the end of this frame
}
```

Add a number to **fade to black** first, and the new scene fades in:

```seven
loadScene("Level2", 0.6)      // a 0.6 second fade out, then a 0.6 second fade in
reloadScene(0.4)              // this scene again: retry after dying (setValue values are kept)
```

Fades run on real time, so they work from a paused menu too. `sceneName()` says which scene is
playing, and `sceneNames()` lists every scene (for a level select). Exported games include every scene.

### Keeping things between scenes

Objects and their scripts belong to their scene, so they start fresh in each one. Keep
anything that should carry on (score, health, unlocked doors, settings) with `setValue`:

```seven
setValue("score", score)                 // before loadScene
var score = getValue("score", 0)         // in the next scene: 0 if it was never set
```

## Saving and loading

```seven
saveGame()                // everything about the game right now
loadGame()                // back to exactly that (false if there's no save)
saveGame("slot2")         // more than one save: give each a name
hasSave("slot2")          // is there one?
deleteSave("slot2")
```

A save holds every object (where it is, its components), every script's variables (numbers,
text, true/false, vectors, colors, and lists and maps of them), the values from `setValue`, which scene it
was in, the time of day and the time scale. Loading builds the game back from it at the end of the
frame, so a pause menu can have Save and Load buttons:

```seven
if button("Save", 470, 300, 340, 50) { saveGame() }
if hasSave() and button("Load", 470, 360, 340, 50) { loadGame() }
```

Saves are kept in `%APPDATA%\Sev7n\saves\` under the game's name, not in the project, so
players' saves survive updates to the game.

Not saved yet: what NPCs remember, ragdolls lying limp (they stand back up), sounds playing,
and variables holding functions or other objects (they get what their `start()` gives them).

## Prefabs

A prefab is an object saved to use again: an enemy, a coin, a door, with all its components and
scripts.

- **Right-click an object in the Hierarchy > Save as Prefab** saves it to `assets/prefabs`.
- **Create > Prefab** places a copy in the scene (or double-click it in the Project panel).
- Scripts make copies while the game runs, scripts and all:

```seven
var zombie = spawnPrefab("Zombie", vec3(5, 0, -8))    // returns the new object
zombie.addTag("wave2")
```

Each copy is its own object (its own health, its own position).

## fixedUpdate

`update(dt)` runs once a frame, so how often depends on the PC (30, 60, 144 times a second).
`fixedUpdate(dt)` runs **exactly 60 times a second** whatever the frame rate, with `dt` always
1/60. Use it for things that must behave the same on every PC: forces, timers that must be exact,
and (in 1.0) anything shared over the network.

```seven
func fixedUpdate(dt) {
    entity.addForce(vec3(0, 0, -20))     // the same push on a slow laptop and a fast PC
}
```

## Coming in 1.0: multiplayer

0.7 lays the groundwork:

- **Snapshots**: saving a game turns the whole world into data (every object by its id, every
  script's variables). A multiplayer server sends the same kind of snapshot to every player, so
  they all see the same world.
- **fixedUpdate**: networked games step the world at a steady rate so every machine agrees.
- **Stable ids**: every object keeps its id in scenes, saves and prefab copies, so the server and
  the players can tell which object is which.
