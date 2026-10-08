# Double anchor, ghost anchors and the black square (0.5.1)

What a player saw going faster than their usual pace on a real server (miamiprac, about 67 ms),
why, what changed, and the lab's measurements. Every change keeps KoHs Anchor's within what a
Vanilla client sends; the per-version legitimacy lab checks it packet by packet.

> **Released 0.5.1 (2026-10-08).** The three changes to the core's handling of the double anchor
> described below (the second click of the same tick, the pair that waits together, the click on
> an anchor with glowstone in flight) were withdrawn before the release, after a report that
> glowstone felt heavy in real play. The release keeps the ghost anchor fix, the black square fix
> and the server-side option. The measurements below were taken with those three changes in.

## What the player saw

- Past their usual speed, an anchor appeared **on top of the one that had just exploded**, or only
  the glowstone went down, as a block.
- For a moment after a detonation, **a black square** under the anchor.
- The **double anchor** (a double click with anchors on your charged anchor: the first click
  detonates it, the second puts the next anchor in its place, an old anchor PvP technique) worked
  only sometimes.

## Causes and changes

| What | Cause | Change |
| --- | --- | --- |
| Anchor on top of the exploded one, glowstone alone | A click the chain runs as a detonation, on an anchor drawn charged by glowstone the world has not seen yet: Vanilla's client-side use reads the world's *uncharged* anchor and predicts a placement on its face, a ghost anchor on top of (or beside) the one exploding. The next clicks aimed at the ghost: glowstone went down beside the spot and the cycle drifted away from it. | `ChainedPlacementMixin` also covers those clicks: nothing is placed in the client world, the server's answer is drawn. The packet is the same. |
| Double anchor in one tick | The second click with the same item in the same tick was taken for a double click that could only stack an anchor, and dropped. | When the click before it in that tick detonated the anchor at that block, the second is the double anchor's next anchor: it goes on to the chain, which draws it at once. A third click is a repeat again. |
| Double anchor faster than the round trip | Its two clicks waited for the server's anchor like the glowstone before them; the second was dropped as a repeat, and released, the first was dropped as "an anchor on an anchor". | The pair waits and goes together, glowstone first, then the detonation and the next anchor. |
| Black square | The hidden (detonated) anchor still blocked the client's light until the server's removal arrived, so the floor's face under it was lit by nothing. | The client's light engines read a hidden block as it is drawn (`LightEngineVeilMixin`): air. Server light engines are never touched. |

New option, **Anchors Server → Instant detonation click → Instant double anchor** (off by default,
only where the server's bridge allows instant clicks): the double anchor's second click is sent at
the press as well, right behind the detonation.

A click with anchors on an anchor that glowstone was just sent to (within the server's answer
time) is a detonation too, even if this client still shows the anchor empty: the server, which
has the charge by then, detonates it. Up to 0.5.0 that click was dropped as "an anchor on your own
uncharged anchor", which broke the double anchor faster than the round trip.

## Measurements

### Every version (KoHs-Debug-Tools `anchors-legit`, 2026-10-06)

Grim 2.3.74 on 1.21.11, 26.1.2 (which 26.1 and 26.1.1 join) and 26.2; 26.3 in singleplayer (no
Grim build yet). Eight scenarios per version, KoHs Anchor's on and switched off: anchor cycles,
double anchors (both clicks in one tick) at 0 and +70 ms, and one-tick bursts. Every packet the
client sent, cut into client ticks; screenshots before and after each scenario; the tester given
knockback resistance (and caged in 26.3, which ignored it), never moved.

| Version | Hotbar change after a click in the same tick | Clicks of one tick at two blocks | Grim alerts | Double anchor, next anchor back on the spot |
| --- | --- | --- | --- | --- |
| 1.21.11 | 0 | 0 | 0 | 10/10, +70 ms 10/10 |
| 26.1 | 0 | 0 | 0 | 10/10, +70 ms 10/10 |
| 26.1.1 | 0 | 0 | 0 | 10/10, +70 ms 10/10 |
| 26.1.2 | 0 | 0 | 0 | 10/10, +70 ms 10/10 |
| 26.2 | 0 | 0 | 0 | 10/10, +70 ms 10/10 |
| 26.3 | 0 | 0 | n/a | 10/10, +70 ms 10/10 |

The double anchor's packets are Vanilla's in every tick (hotbar change, two uses at the same block).

### The fight lab (26.2, `anchors-debug`: Grim, Herzium last input, the player's kit and keys)

| Double anchor | KoHs Anchor's | Vanilla |
| --- | --- | --- |
| A step every 100 ms, no added latency | 15/15 | 15/15 |
| A step every 100 ms, +70 ms | 15/15 | 15/15 |
| A step every 50 ms, no added latency | 15/15 | 15/15 |
| A step every 50–60 ms, +70 ms | 8–15/15 depending on the run | 12–15/15 |

Faster than the round trip the runs vary a lot (Ping Equalizer's delay alternated between about
36 and 86 ms each way): the case the charge-in-flight change targets went from 4/15 to 15/15 in two
runs, and a later full battery gave 8 and 9/15. Not settled; it is beyond the pace the player plays
at, where both are 15/15.

The black square: the same detonated anchor, hidden, before and after `LightEngineVeilMixin`, in
the development client: a black pit, then the pit lit as it will be once the server answers.
