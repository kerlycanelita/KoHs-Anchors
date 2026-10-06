# The immediate chain (0.5.0)

Why fast anchor spam still felt heavy, and what the core does about it now. Measured on 2026-10-06 in
the KoHs Anchor lab: a local 26.2 server with Grim Anticheat 2.3.74, the player's real setup (use on
".", the pvp kit, slots 4 2 1, Herzium in last-input order) and Ping Equalizer adding 0 to 150 ms.
Runs: `KoHs-Debug-Tools/anchors-debug/testlab/results/crater-0.5.0-core`, `crater-chain-3` and
`speed-chain`.

## What the video showed

A recorded fight on a 26.2 practice server, with the 0.5.0 build of the day before. At 28.9 s the
player places an anchor, charges it and detonates it: the anchor is drawn as gone at the press, comes
back 60 ms later and does not explode for the next three seconds, while the player keeps going
through anchor, glowstone and totem. The fight was in a crater of stone: every explosion broke the
ground, and the next anchor went into the hole.

## Why

Up to this build, a click on an anchor the client had just detonated waited on the client until the
server's removal arrived, and was then aimed again with Vanilla's raycast. The lab built its platform
of obsidian and rebuilt it before every cycle, so the second aim always found the floor where the old
anchor stood. On breakable ground it does not: aimed at the top of the old anchor from two blocks
away, the ray goes over the hole once the anchor is gone, or into a hole one block deeper, and the
click is lost. The clicks still waiting then run one per tick, out of step with the ones being
pressed: the next anchor lands on top of an uncharged one, glowstone goes where the sword should
detonate, and nothing explodes again until the player stops.

A Vanilla player is better off here: Vanilla sends the click at once, at the old anchor, and the
server, which has exploded it by then, puts the new anchor in its place. Hero's Anchor Optimizer,
which players remember making anchors faster in 1.21.8, does the same with a replaceable fake anchor
in the client world; it was published for 1.21.6 to 1.21.11, never for 26.x.

## What changed

- **Immediate chain.** A click on an exploding anchor goes out at once, at the old anchor, the packet
  a Vanilla player sends at the same moment. What it will do (the next anchor, its charge, its
  explosion) is drawn at once.
- **The anchor's block follows the server.** Vanilla keeps the player's own prediction at a block until
  the server acknowledges the click: after a charge, the old anchor stays in the client world for up to
  a server tick after the server's removal has arrived. A click then goes to a block that the server,
  and Grim, which follows what the client received, already have as air: Grim's `AirLiquidPlace`, which
  the 0.2.0 lab caught on Vanilla players spamming with +100 and +150 ms. At a block the chain is
  driving, the server's state is now shown the moment it arrives.
- **No ghost blocks.** A chained click places nothing in the client world (Vanilla would put the anchor
  beside the old one, or glowstone in the fire it left, where the server does not). A click whose ray
  passes through an anchor that is drawn but not in the world yet waits for the world to have it,
  including a click that hits nothing behind it.
- **No anchor on your fresh anchor.** An anchor clicked on the player's own uncharged anchor is dropped
  (sneaking still stacks): one such click put every following cycle one block higher.

## Crater bench

New in the lab: stone ground two blocks deep that each explosion breaks, nothing rebuilt between
cycles, the next anchor aimed at the top of what is left in the spot's column as the player sees it.
15 cycles each; the player presses at a constant rhythm and never waits.

| Bench | Build | +0 ms | +50 ms | +100 ms | +150 ms |
| --- | --- | --- | --- | --- | --- |
| 100 ms between actions, steady aim | 0.5.0 before | 93 % | 67 % | 13 % | 7 % |
| | **now** | **100 %** | **100 %** | **93 %** | **80 %** |
| | Vanilla | 100 % | — | 60 % | — |
| 60 ms between actions, 1.5° aim noise | 0.5.0 before | 60 % | 73 % | 40 % | 47 % |
| | **now** | **100 %** | **93 %** | **67 %** | **73 %** |

- **Grim:** 0 alerts in every bench of the run (the build before had `AirLiquidPlace` ×3 at +100 ms).
- What is still lost at +150 ms is the hole itself: the server's explosion breaks blocks the client
  does not know about yet, so an anchor aimed at the bottom can land one block lower than drawn.
  Vanilla loses the same cycles, and more.

## Flat benches

The benches of [speed under spam](speed-under-spam-0.5.0.md), on the obsidian platform: one action per
tick, spam 30 and 10 ms apart with 4 to 6 extra clicks, and whole cycles inside one tick stay at
**100 %** from +0 to +150 ms, with no click wasted and **0 Grim alerts**. The client sends the same
packets in the same ticks as before; the time to the explosion moves with the phase between the
client's and the server's ticks in each session (±50 ms). A robot's click every 20 ms, which no hand
keeps up, loses 60 to 90 % of its cycles as before.

## Fair play

Every packet is one press of the player, in Vanilla's order and tick shape; the immediate chain sends
what a Vanilla client sends when the player clicks an exploding anchor, at the same moment. The only
block the client world shows differently from Vanilla is a chained anchor's, and only as the server's
own state, sooner. Nothing is aimed, placed or repeated for the player.
