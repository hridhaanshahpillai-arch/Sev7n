# Multiplayer

One game **hosts** and the others **join** it, over the internet or on the same PC. Player 1 is always the
host. You don't need friends to make (or test) a multiplayer game: open your game twice and the two windows
play together.

## Try it on your own

1. Put this script on an object in your scene (an empty "Network" object is fine):

```seven
behaviour Network {
    func start() {
        connect()            // hosts, or joins if this PC already hosts
    }
}
```

2. Press **Play** in the editor. This window hosts (player 1).
3. Press **File > Play in Window** (Ctrl+F5). This window finds the first one and joins (player 2).
4. Exported games work the same way: open the game twice.

## Seeing each other: shared values

Each player **shares** values (their position, health, which animation is playing...) and reads everyone else's.
Shared values are sent up to 30 times a second.

```seven
behaviour Player {
    func update(dt) {
        // ... move with WASD as usual, then tell everyone where you are
        share("position", entity.position)
        share("turn", entity.transform.rotation.y)
    }
}

behaviour Others {
    var ghosts = {}                       // player number -> the object showing them

    func onPlayerJoined(player) {
        ghosts[str(player)] = spawnPrefab("Ghost")
    }

    func onPlayerLeft(player) {
        ghosts[str(player)].destroy()
    }

    func update(dt) {
        for p in players() {
            if p == playerId() { continue }
            var ghost = ghosts[str(p)]
            var where = shared(p, "position")
            if ghost != none and where != none {
                ghost.position = ghost.position + (where - ghost.position) * min(1, 15 * dt)   // smooth between updates
            }
        }
    }
}
```

Players who join late see everything already shared, so put the "ghost" spawning in `onConnected` too if you
want to see players who were there first (`players()` lists them).

## Events: messages

For things that happen once (a shot, a door opening, a chat line), send a message. Every **other** player's
scripts get `onMessage`:

```seven
func update(dt) {
    if mouseClicked() { sendMessage("shoot", entity.position) }
}

func onMessage(name, value, from) {
    if name == "shoot" { spawnEffect("Sparks", value) }
}
```

## Everything

| Function | What it does |
|---|---|
| `connect([port])` | host, or join if this PC already hosts. True when connected |
| `host([port])` / `join([address], [port])` | choose: host, or join an address (`"localhost"` = this PC, or another PC's IP address) |
| `disconnect()` | leave the game |
| `isConnected()`, `isHost()`, `playerId()` | are we playing, are we the host, our number (1 = host) |
| `players()` | everyone's numbers, including yours |
| `share(key, value)` / `shared(player, key, [default])` | your values for everyone / someone's value |
| `sendMessage(name, [value])` | everyone else gets `onMessage(name, value, from)` |

Callbacks (put them in any behaviour): `onConnected(player)`, `onPlayerJoined(player)`, `onPlayerLeft(player)`,
`onMessage(name, value, from)`, `onDisconnected()`.

Values that can be shared and sent: numbers, text, true/false, vectors, lists and maps of those.

In Eights: **Connect**, **Send Message**, **Share Value**, **Shared Value**, **My Player Number**, **Players**,
**Is Host**, and the events **On Player Joined**, **On Player Left** and **On Network Message**.

## Playing over the internet

The default port is **7777**. On a home network, the host tells the others their PC's IP address and they
use `join("192.168.1.20")`. Over the internet the host's router has to forward port 7777 to their PC.
When the host closes the game, everyone else is disconnected (`onDisconnected`).
