# NPCs and AI (v0.5 alpha)

An **NPC** component gives a character a mind of its own. It:

- **finds its way** around walls and other obstacles
- **sees** things in front of it (walls block its view) and **hears** noises
- **reacts**: chases, runs away, or turns to watch
- **searches** where it last saw or heard something
- **wanders**, **patrols**, or follows a **daily routine** on the game clock
- **remembers** who did what, and how it feels about them

Quickest start: **Create → NPC**. You get a purple pill that wanders around by itself. Press Play
and watch it. Give your player the tag `player`, set **When seen** to **Chase**, and it hunts them.

## Settings

### Moving

| Setting | What it does |
|---------|--------------|
| **Walk speed** / **Run speed** | Units per second. It runs when chasing or running away |
| **Turn speed** | How fast it turns to face where it's going, degrees per second |
| **Stop distance** | How close counts as "there" |

It walks with its Rigidbody (so it lands on floors, gets pushed and pushes others) or, without
one, slides along by itself. Give it a **Capsule Collider** so it knows how wide it is: paths keep
it that far from walls.

### When nothing is happening

| Idle | What it does |
|------|--------------|
| **Stand** | Stays where it is, and goes back there after a chase |
| **Wander** | Walks to random spots within **Wander radius** of where it started, pausing at each |
| **Patrol** | Walks between objects you name, in order, e.g. `PostA, PostB, PostC`. Empty objects make good markers |
| **Routine** | Follows its daily routine (below) |

### Senses

| Setting | What it does |
|---------|--------------|
| **Notices tag** | It sees and remembers objects with this tag (usually `player`) |
| **When seen** | **Ignore** (your scripts decide), **Chase**, **Flee** or **Watch** (stay and turn to face them) |
| **Sight range** / **Sight angle** | How far and how wide it sees. 360 = eyes in the back of its head |
| **Hearing** | How far away it hears a noise of loudness 1 |
| **Search time** | Seconds it keeps looking where it last saw or heard something before giving up |
| **Memory** | Seconds before it forgets someone it hasn't seen. 0 = never forgets |

When you select an NPC, the Scene view shows its view as a **purple wedge** and how far it
hears as a **faint circle**.

A chaser that loses sight of you goes to where it **last saw** you and looks around there. Hide
behind a wall and wait, and it gives up after the search time.

### Animations

If the NPC has an **Animator** (an animated 3D model) or a **Sprite Animator**, pick which animation to
play while it stands, walks and runs. It switches between them by itself.

## Daily routines

Pick **Idle → Routine**, then add steps: *at hour 8, go to Forge and do "work"*, then *at hour 20, go
to Bed and do "sleep"*. At each hour it walks to the object you named. Once it's there,
`entity.activity` is the word you gave ("work"), so a script can play the right animation.

The game clock starts at **8:00**, and a game day lasts **10 minutes**:

```seven
hour()               // the time of day, 0 to 24 (13.5 = half past one)
setHour(21)          // skip to 9 at night
setDayLength(1200)   // a day lasts 20 real minutes
```

## Scripts

A behaviour on an NPC gets these extra events:

```seven
behaviour Guard {
    func onSee(other)           { print("Halt!") }                       // someone came into view
    func onLose(other)          { print("Where did they go?") }          // ...and went out of view
    func onHear(position, source) { }                                    // a makeNoise() it could hear
    func onReach(other)         { other.destroy() }                      // caught who it was chasing
    func onArrive()             { }                                      // got where it was walking
    func onRoutine(activity, place) { print("Time to " + activity) }    // a routine step started
}
```

### Telling it what to do

These override its settings until it's done (moveTo) or you call `resume()`:

| Code | What it does |
|------|--------------|
| `entity.moveTo(vec3(4, 0, 2))` / `entity.moveTo(find("Door"))` | Walks there (around walls), then makes that spot its new place to stand |
| `entity.follow(other)` | Walks after someone and stays close |
| `entity.chase(other)` | Runs after someone (onReach when it catches them) |
| `entity.flee(other)` | Runs away from someone |
| `entity.stay()` | Stands still |
| `entity.resume()` | Back to its settings (idle, when seen...) |

### Asking it things

| Code | Gives |
|------|-------|
| `entity.state` | `"idle"`, `"routine"`, `"moving"`, `"following"`, `"chasing"`, `"fleeing"`, `"watching"`, `"searching"` or `"staying"` |
| `entity.target` | Who it's chasing, fleeing or watching (or none) |
| `entity.arrived` | True once it has got where it was going |
| `entity.activity` | What its routine has it doing now ("work", "sleep"...) |
| `entity.canSee(other)` | True if `other` is in view right now |
| `entity.seen` | A list of everyone it can see |

### Noises

```seven
makeNoise(transform.position)            // loudness 1: NPCs within their Hearing range hear it
makeNoise(transform.position, 3, entity) // 3 times as loud, and made by this entity
```

Chasers and watchers come to look (or turn toward it), runners flee from it, and everyone who
hears it gets `onHear`.

## Memory

Every NPC remembers who it has seen and when, plus anything you tell it. Its **opinion** of
someone runs from -100 (hates them) to 100 (loves them):

```seven
entity.remember(player, "helped", 20)     // remember an event; opinion +20
entity.remember(player, "stole", -50)     // opinion -50
entity.remembers(player, "stole")         // how many times: 1
entity.opinion(player)                    // -30
entity.setOpinion(player, 0)              // set it directly
entity.knows(player)                      // true if it has ever seen or heard about them
entity.lastSeen(player)                   // where it last saw them (or none)
entity.forget(player)                     // wipe them from its memory
```

So a shopkeeper can turn on you after you steal:

```seven
behaviour Shopkeeper {
    func onSee(other) {
        if entity.opinion(other) < -40 { entity.chase(other) }
        else if entity.remembers(other, "helped") > 0 { print("Welcome back, friend!") }
    }
}
```

## Limits for now

NPCs find their way across floors at different heights: they walk up stairs and onto anything
no higher than their Rigidbody's **Step height**, and drop down ledges up to about 1.3 high
(so they can reach you up on a platform, or come down after you). They don't climb ladders or jump
gaps, and they steer around other characters instead of planning around them. Very large levels
are searched up to about 20 units around the NPC at a time.
