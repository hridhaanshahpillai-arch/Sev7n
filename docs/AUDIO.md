# Sound and Music (v0.8 alpha)

Sev7n has its own audio engine: every sound is mixed together, smoothly, with real 3D sound,
groups with their own volumes, and a Sound Maker for making effects without recording anything.

Sound files can be **.wav** or **.ogg** (.mp3 doesn't work yet: convert it with a free tool like
Audacity). Import them like any other file, or put them in your project's `assets/sounds` folder.

## Playing sounds from scripts

```seven
playSound("assets/sounds/coin.wav")                 // once
playSound("assets/sounds/coin.wav", 0.5)            // quieter (1 = as recorded)
playSound("assets/sounds/step.wav", 1, random(0.9, 1.1))   // a pitch: no two steps sound the same
playMusic("assets/sounds/theme.ogg")                // loops until stopMusic() or other music
playMusic("assets/sounds/boss.ogg", 0.6, 2)         // crossfades to new music over 2 seconds
stopMusic(3)                                        // fades the music out over 3 seconds
```

`playSound` gives you the sound back, so you can change it while it plays:

```seven
var engine = none

func start() {
    engine = playSound("assets/sounds/engine.ogg")
    engine.loop = true
}

func update(dt) {
    engine.pitch = 0.8 + speed / 20       // revs up as you go faster
}

func onCrash() {
    engine.fadeOut(0.5)                   // fades away, then stops
}
```

| On a sound | What it does |
|------------|--------------|
| `volume`, `pitch`, `loop` | read or change them while it plays |
| `playing`, `time` | is it still going, how many seconds in |
| `stop()`, `pause()`, `resume()` | |
| `fadeOut(seconds)` | fades away, then stops |
| `fadeTo(volume, seconds)` | glides to a volume and keeps playing |
| `position` | where a 3D sound is (move it to follow something) |

Music keeps playing when a script loads another scene, so it doesn't restart at every door.
Everything stops when the game stops.

## 3D sound

`playSoundAt` plays a sound at a place in the world, heard from the main camera: louder near it,
from the left or right, a little quieter and muffled behind, and silent past its range.

```seven
playSoundAt("assets/sounds/growl.wav", transform.position)       // heard up to 30 units away
playSoundAt("assets/sounds/boom.ogg", where, 1, 80)              // volume 1, heard up to 80 away
var buzz = playSoundAt("assets/sounds/fly.wav", transform.position)
buzz.position = transform.position                                // (every frame) it moves with the fly
```

## Audio Source: sounds that belong to objects

For a campfire's crackle, a radio, a waterfall or an engine: **Add Component > Audio Source**
on the object, pick a sound, and it plays from there when the game starts (and moves with it).

| Setting | What it does |
|---------|--------------|
| **Sound** | the .wav or .ogg file (drag one in from the Project panel) |
| **Volume**, **Pitch** | 1 = as recorded; pitch 2 = an octave higher and faster |
| **Loop** | plays over and over |
| **Play on start** | starts by itself when the game starts |
| **3D** + **Range** | heard from where the object is, up to Range away. Off: the same everywhere (music, a voice on the radio) |
| **Group** | which Audio Mixer group it plays through |
| **Preview** | hear it in the editor (changes to Volume and Pitch are heard straight away) |

From scripts: `entity.playAudio()` (from the start; `entity.playAudio("assets/sounds/other.wav")`
switches sound), `entity.stopAudio()`, `entity.audioPlaying`. Hidden objects go quiet, and an
object's sound stops when it's destroyed.

## Audio Mixer

**Window > Audio Mixer**: every sound plays through a group, and each group has its own volume and
a mute button. Level meters move while the game plays.

| Group | Used by |
|-------|---------|
| **Music** | `playMusic` |
| **Effects** | `playSound`, `playSoundAt`, Audio Sources (the default) |
| **Ambient** | wind, rain, crowds: pick it in an Audio Source |
| **Voice** | dialogue |
| **UI** | clicks and menu sounds |

Master is everything. The volumes are saved with your project (`audio.json`), so exported games
sound the same. Scripts can change them too, for an options menu:

```seven
setBusVolume("Music", 0.4)
var v = busVolume("Effects")
muteBus("Music", true)
setVolume(0.8)                 // the master volume
```

Too many sounds at once (more than 64)? The quietest ones stop. Very loud moments are squashed
smoothly instead of crackling.

## Sound Maker

**Window > Sound Maker** makes retro sound effects without recording anything:

1. Pick a start: **Coin**, **Jump**, **Laser**, **Explosion**, **Hit**, **Power-up**, **Blip** or **Click**.
   **Another ...** gives a new take on the same kind of sound; **Mutate** nudges every setting a little.
2. Change it with the sliders: **Pitch**, **Slide** (up for jumps, down for lasers), **Jump** (the
   coin's "ding-ding"), **Fade in / Hold / Fade out**, **Punch**, **Wobble**, **Thinness**,
   **Brightness**. With **Play on change** you hear every change.
3. Name it and **Save**: it goes into `assets/sounds/` as a .wav, ready for `playSound` or an Audio Source.

## In Eights

Sound nodes: **Play Sound**, **Play Sound (Pitch)**, **Play Sound At**, **Play Music**,
**Crossfade Music**, **Stop Music**, **Fade Out Music**, **Set Volume**, **Set Bus Volume**,
**Bus Volume**, **Mute Bus**, **Play Audio Source**, **Stop Audio Source**, **Is Audio Playing**.
