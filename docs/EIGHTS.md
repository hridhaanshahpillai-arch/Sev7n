# Eights: visual scripting (v0.7.6 alpha)

Eights lets you make behaviours by **connecting nodes** instead of typing code,
like Unreal's Blueprints. An Eights graph (`.8s`) is a behaviour, just like a Seven script (`.7n`):
you attach it to objects the same way, and both kinds can be used in the same game.

**Eights can do everything Seven can**: physics, NPCs, ragdolls, HUDs and menus, sound, scenes,
saves, effects. Over 260 nodes. When Seven gets a new feature, Eights gets a node for it.

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
| Right-click the canvas               | add a node (type to search: "jump", "timer", "health bar" all find something) |
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
| mint   | List   |
| grey   | Any    |

An input that isn't connected shows a small field for typing its value.
An unconnected **Target** means "this object".

## Nodes

| Category  | Some of the nodes |
|-----------|-------|
| **Events** | On Start · On Update · On Late Update · On Fixed Update · On Collide · On Key Pressed / Released · While Key Held · On Mouse Click · **Every N Seconds** · **On Message** · NPC events: On See · On Lose Sight · On Hear · On Reach · On Arrive · On Routine |
| **Flow** | Branch · Repeat · For Each · While · Sequence · **Do Once** · **Flip Flop** · **Cooldown** · **Delay** · Stop |
| **Objects** | Print · Move · Rotate · Translate · Set Position / Rotation / Scale · Look At · Set Color / Texture / Mesh · Set Active · Add / Remove Tag · Spawn · Destroy · **Send Message** · Add Behaviour · tiles and sprite frames |
| **Physics** | Set Velocity · Push · Add Force · Add Impulse · Set Kinematic / Mass · Set / Get Gravity · Is Grounded · **Raycast** + Break Hit · Overlap Sphere · Is Solid At |
| **Input** | Is Key Held · Was Key Pressed / Released · Mouse buttons · Mouse Position · Mouse Movement · Lock Mouse · **Move Input (WASD)** · Move Input 2D |
| **Characters & NPCs** | Play Animation · Animation Speed · Ragdoll · Get Up · NPC Move To / Go To / Follow / Chase / Flee / Stay / Resume · NPC memory and opinions · NPC State / Target / Can See · Make Noise |
| **HUD & UI** | Draw Text / Box / Bar / Picture / Line / Circle / Ring / Frame / Brackets / Segment Bar / Key Icon · Set Glow · World Canvas · Screen size · World To Screen · **Button** · Slider · Checkbox · Panel |
| **Sound** | Play Sound · Play Music · Stop Music · Volume |
| **Scenes & Saves** | Load Scene (with a fade) · Reload Scene · Scene Names · Set / Get Value · Save / Load / Delete Game · Has Save · Spawn Prefab · Restart · Quit · Time Scale |
| **World & Effects** | Spawn Effect · Emit / Play / Stop Effect · Tween (position, scale, color...) · the game clock · light brightness and color · camera zoom and sky color |
| **Values** | Self · Find · Find All With Tag · Is Valid · positions and directions · Get / Set Script Variable · Number · Text · Color · Make / Break Vec3 · Make Color · Distance · Random · Chance · Time |
| **Math & Logic** | + − × ÷ and Remainder · comparisons · And · Or · Not · **Select** · Abs · Round · Min · Max · Clamp · Lerp · Remap · Ease · Noise · vector math (Normalize, Length, Dot, Direction To...) |
| **Text & Lists** | Join Text · To Text · To Number · Format Number · Contains · Length · Split · Make List · Get / Set / Add / Remove Item · Random Item · Sort · Shuffle |
| **Variables** | Get · Set · Add To (for numbers), for each variable in the sidebar |

Hover over a node's title to see what it does.

**Move** and **Rotate** work "per second", so put them after **On Update** or **While Key Held**.
**On Collide** needs a collider on this object (and on the other one).
Physics nodes need a **Rigidbody** ([physics](PHYSICS.md)); NPC nodes need an **NPC** component ([NPCs](NPC.md)).
HUD and UI nodes draw for one frame, so run them from **On Update** every frame ([HUD](HUD.md)).

### Timing without code

- **Every N Seconds**: spawners, clocks, regenerating health.
- **Delay**: "wait 2 seconds, then open the door". While it's waiting it ignores being run again.
  Values from the event before it (like On Collide's **Other**) aren't there after the wait:
  put them in a variable first.
- **Cooldown**: lets the chain through at most once every so often (a gun's fire rate).
- **Do Once**: the first time only (an intro message).

### Talking to other objects and scripts

- **Send Message** calls a name on another object: an **On Message** node with that name in its
  graph, or a function with that name in its Seven script. Up to 3 values go with it.
  A script does the same with `other.send("onHit", 25)`.
- **Get / Set Script Variable** read and change another object's variables (`health` of its `Player` script).

## Variables

Add variables in the sidebar: Number, Bool, Text, Vec3, Color, Entity (an object, starts as none)
or List (starts empty). Each object with the graph gets its own copy, so two enemies each have
their own `health`. Scripts can read a graph's variables too: `find("Door").behaviour("DoorGraph").open`.

## Errors

If something goes wrong while playing, the Console shows the error. Click it, and Eights
opens the graph with the node that caused it outlined in red.

## Example: a coin that spins and gets collected

```
On Update ──▶ Rotate (Degrees / sec 0, 90, 0)

On Collide ──▶ Branch ──True──▶ Play Sound ──▶ Add To Variable (coins) ──▶ Destroy (Target: Self)
     │            ▲
     └─ Other ─▶ Has Tag ("player")
```

## Example: a player you move with WASD

```
On Update ──▶ Move (Target: Self, Speed 5)
                  ▲ Direction
          Move Input (WASD)

On Key Pressed ("space") ──▶ Branch ──True──▶ Push (Amount 0, 6, 0)
                               ▲
                     Is Grounded (Self)
```

(The player needs a Rigidbody and a collider, so it falls and can jump.)

## Example: a score on the screen

```
On Update ──▶ Draw Text (Text ◀── Join Text ("Score: ", Get score), X 40, Y 30)
```
