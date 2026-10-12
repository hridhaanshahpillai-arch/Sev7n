# Eights: visual scripting (v0.1 alpha)

Eights lets you make behaviours by **connecting nodes** instead of typing code,
like Unreal's Blueprints. An Eights graph (`.8s`) is a behaviour, just like a Seven script (`.7n`):
you attach it to objects the same way, and both kinds can be used in the same game.

Under the hood, each graph is turned into Seven code when you press Play.
Tick **Show Seven code** to see it. It's a good way to learn Seven.

---

## Making a graph

1. In the **Project** panel, click **+ Eights** and give it a name (like `EnemyBrain`).
2. It opens in the **Eights** tab with two starting nodes: **On Start** and **On Update**.
3. Drag the graph from the Project panel onto an object in the **Hierarchy** to attach it.
4. Press **Play**.

## Controls

| Do this                              | To                                              |
|--------------------------------------|-------------------------------------------------|
| Right-click the canvas               | add a node (type to search)                     |
| Drag from a pin to another pin       | connect them                                    |
| Drag a wire out and let go in space  | add a node that fits, already connected         |
| Drag from a connected input          | unplug the wire                                 |
| Click / drag a box                   | select nodes                                    |
| Delete                               | delete selected nodes and wires                 |
| Right-click a node                   | Duplicate / Delete                              |
| Middle-drag                          | move around                                     |
| Ctrl+S                               | save (Play saves too)                           |

## Wires and pins

- **White wires (triangle pins)** mean "then do this": they set the order things happen.
- **Colored wires (round pins)** carry values:

| Color  | Type   |
|--------|--------|
| green  | Number |
| red    | Bool (true / false) |
| pink   | Text   |
| yellow | Vec3 (a 3D position or direction) |
| lilac  | Color  |
| cyan   | Entity (an object) |
| grey   | Any    |

An input that isn't connected shows a small field for typing its value.
An unconnected **Target** means "this object".

## Nodes

| Category  | Nodes |
|-----------|-------|
| **Events** (red) | On Start · On Update (gives Delta Time) · On Collide (gives Other) · On Key Pressed · While Key Held |
| **Flow** (grey) | Branch (if / else) · Repeat (loop, gives Index) |
| **Actions** (cyan) | Print · Move · Rotate · Set Position · Look At · Set Color · Set Texture · Add Tag · Spawn · Destroy · Set Velocity · Push · Play Animation · Set Flip X · Set Tile |
| **Values** (green) | Self · Find · Get Position · Get Forward · Get Name · Has Tag · Is Key Held · Make Vec3 · Break Vec3 · Distance · Random · Time · Get Velocity · Is Grounded |
| **Math & Logic** (yellow) | Add · Subtract · Multiply · Divide · Greater Than · Less Than · Equals · And · Or · Not |
| **Variables** (purple) | Get / Set for each variable in the sidebar |

**Move** and **Rotate** work "per second", so put them after **On Update** or **While Key Held**.
**On Collide** needs a **Box Collider** on this object (and on the other one).
**Set Velocity**, **Push**, **Get Velocity** and **Is Grounded** need a **Rigidbody** ([physics](PHYSICS.md));
**Play Animation** needs a **Sprite Animator** ([2D games](2D.md)).

## Variables

Add variables in the sidebar (Number, Bool, Text, Vec3 or Color). Each object with the graph
gets its own copy, so two enemies each have their own `health`.

## Errors

If something goes wrong while playing, the Console shows the error. Click it, and Eights
opens the graph with the node that caused it outlined in red.

## Example: a coin that spins and gets collected

```
On Update ──▶ Rotate (Degrees / sec 0, 90, 0)

On Collide ──▶ Branch ──True──▶ Destroy (Target: Self)
     │            ▲
     └─ Other ─▶ Has Tag ("player")
```
