# Tether

A one-touch endless climber for Android. You are falling up a shaft with a void
rising underneath you. **Hold** and a grapple fires at the nearest anchor above
and hauls you in. **Let go** and you fly. That is the entire control scheme.

Pure Kotlin on a `SurfaceView` canvas, procedurally synthesised audio, no
third-party runtime dependencies.

## How it plays

| Input | Action |
| --- | --- |
| Press | Fire the grapple at the nearest anchor above you and get reeled in. One grapple per press. |
| Release | Let go and fly on whatever momentum you built. Press again for the next anchor. |
| Back | Bail out to the title screen. |

Riding a rope all the way in gives you the most speed, but costs time — and the
void never stops rising. Letting go early keeps you moving but wastes the swing.
That tension is the game.

### Anchors

| Anchor | Behaviour |
| --- | --- |
| Amber | Ordinary. Reusable. |
| Violet | Drifts side to side — you have to lead it. |
| Teal (barred) | One shot. It burns out the moment you leave it. |
| Gold | Fires you upward hard on arrival, then burns out. |

**Sparks** are strung along the line between consecutive anchors. Collecting
them chains a multiplier up to ×10; letting one fall behind you breaks the
chain. They also mark the efficient route, so a good line and a big score are
the same thing.

## Why it's built this way

The design follows what the hyper-casual literature consistently identifies as
the engine of these games — a compulsion loop of *simple action → immediate
feedback → visible progress*, kept inside the flow channel between boredom and
anxiety, with restart friction close to zero
([Game Developer](https://www.gamedeveloper.com/design/admiring-the-game-design-in-hyper-casual-games),
[Flow theory in game design](https://www.kokutech.com/blog/gamedev/design-patterns/flow-state)):

- **One bit of input**, learnable in about two seconds, with a skill ceiling in
  release timing.
- **Immediate feedback** on every event: the combo climbs a pentatonic ladder in
  the audio, plus particles, hit-stop and haptics.
- **A visible, always-advancing threat.** The void gives every second of
  hesitation a cost, which is what stops the game from ever feeling idle.
- **Recoverable mistakes.** The camera drifts back down instead of killing you,
  so a botched swing costs altitude rather than the run — near-misses are the
  best part.
- **Instant retry** — death to playing again in one tap.

## The physics were tuned before they were written

The simulation in [`Physics.kt`](app/src/main/java/com/mikmy/tether/Physics.kt)
was prototyped in JavaScript and run headlessly thousands of times with a bot
playing, because "does this feel good" is not answerable by reading code. That
rig killed three designs before this one:

1. **A pure pendulum could not climb at all.** Grabbing a rope kills your radial
   velocity, so every grab is an energy loss. Measured climb over 45 seconds:
   zero.
2. **Reeling the rope in while hanging** deadlocked: come to rest directly below
   an anchor with no lateral speed and you hang there, motionless, until the
   void takes you. The bot did exactly this on the first run.
3. **Randomly scattered anchors** left the player stranded with nothing in range
   about 30% of the time — deaths that were not the player's fault. Anchors are
   now generated as a chain where each is placed within reach of the previous,
   so a route always exists.

The final model — the rope actively *hauls* you at the anchor — measured, over
12 seeds:

| | median run | climb rate | frames with nothing in reach |
| --- | --- | --- | --- |
| skilled bot | 15 s | 0.17–0.48 screens/s | 3–12% |
| sloppy bot | 4.5 s | | |

A 3.4× spread between careful and careless play, with deaths overwhelmingly
caused by the player rather than the generator. Spark radius was picked the same
way: `0.09` puts a bot's collection rate near 70%, so chains are achievable but
breaking one is a real event.

## The grapple fires on the press, not on the hold

A review pass put three policies through the same rig:

| policy | median run |
| --- | --- |
| press once, never release | 67s |
| mash the screen | 19s |
| time the releases | 13s |

Holding the finger down dominated, because `World.update` retried the grab every
frame while held — so holding acted as a magnet that latched every anchor by
itself, and a run could be finished **without ever using the release**. Half the
advertised control scheme was optional.

The grapple now reaches only for a **1.4s window** after each press, and the
reach indicator shows that window expiring rather than leaving a dead finger
down. Measured across the three policies:

| policy | before | after |
| --- | --- | --- |
| hold forever | 67.4s | 2.5s |
| plays properly | 13s | 13s |
| blind timed presses | 1.4s | 1.4s |

1.4s is the forgiving end of the range that still kills the degenerate strategy;
tightening it to 0.9s only moves hold-forever from 2.5s to 2.1s and costs
forgiveness elsewhere.

Only the degenerate strategy moves. A test asserts it directly: holding forever
no longer carries a run.

One caveat worth keeping in mind: in the same measurement a mindless masher
(19s) beat the bot that tries to play properly (13s), so that release heuristic
is not a credible model of a human and none of these numbers describe skilled
play. The change is justified by the *degenerate* strategy it removes, which the
rig does measure reliably — not by the absolute figures.

## Tests

`app/src/test` runs the real simulation on the JVM and asserts the properties
that make the game fair:

- every anchor is within grapple range of the previous one, over many seeds
- the chain always ascends, and stays inside the shaft even while drifting
- you cannot tether below yourself, to a spent anchor, or straight back onto the
  anchor you just released
- the rope never stretches past its maximum and speed is always capped
- **a competent player actually climbs** — the smoke test that the mechanic works
- doing nothing is fatal, and so is hanging on forever (no stalling the run)
- the same seed replays identically

```bash
gradle testDebugUnitTest
```

## Building

No wrapper JAR is checked in.

**Android Studio:** `File → Open` this folder, let it generate the wrapper, Run.

**Command line** (JDK 17 + Android SDK):

```bash
gradle wrapper && ./gradlew assembleDebug
```

**GitHub Actions:** pushing to `master` or `dev` runs the tests and lint, then
builds the debug APK, the R8-minified release APK and the Play-format AAB, and
uploads them as the `tether-apk` artifact.

## Layout

```
app/src/main/java/com/mikmy/tether/
  MainActivity.kt   immersive fullscreen host
  GameView.kt       SurfaceView + render thread (GPU canvas)
  Physics.kt        the entire simulation — no Android types, fully unit tested
  Game.kt           rendering, input, particles, HUD, screens
  Sfx.kt            procedural synth + software mixer over one AudioTrack
```

All tuning constants are in `Tune` at the top of `Physics.kt`.

- minSdk 26, targetSdk 35, portrait only.
