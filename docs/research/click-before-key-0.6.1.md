# The click that lands before its key (0.6.1)

A player recorded a session on a practice server with KoHs Anchor's 0.6.0, Herzium 1.12.0 and
Crystal Tweaks 2.6.1 (Minecraft 26.2) and said the anchor loop felt uncomfortable, and that glowstone
went down again while the anchor was in hand. Read frame by frame, the recording has 110 cycles of
anchor, glowstone, totem: **67 clean (61 %)**, 139 glowstones spent for 96 anchors. The mistakes come
in chains: a second glowstone on the charged anchor, a totem step that does not detonate, an anchor
click that detonates instead of placing, and a glowstone that lands on the floor as a block. In 20 of
the 27 double glowstones the totem's slot is selected exactly one tick after the second glowstone:
the totem's click came first.

## Cause

Two hands press a number key and its click together, and now and then the click lands a few
milliseconds before the key. Vanilla applies a tick's number keys before its clicks, so when both land
in the same tick the click still uses the key's item. KoHs Anchor's applies an anchor burst in the
order it was pressed (that is what makes "anchor, use, glowstone, use" inside one tick work), and so
it ran that click with the item of the step before. Herzium's order did the same. Either way, the
click did something with the old item and the key was left without one: the loop was one click late
until the player caught up.

When a tick falls between the click and its key, Vanilla runs the click alone too, with the old item.

## What 0.6.1 does

- **Same tick.** A burst that opens with a click followed within 30 ms by a number key is applied as
  "key, click" when the item in hand has already had its click, or cannot be used at all (a sword, a
  mace, a totem, an empty hand). That is Vanilla's result for those presses. The first click with an
  item that can be used is never moved.
- **Across a tick.** A click that is alone in its tick, pressed less than 30 ms before it, waits one
  tick when, with what is in hand, it could only be wasted: glowstone on a charged anchor, an anchor
  on an anchor without a charge, or, within a second of a click on an anchor, glowstone on another
  block or a totem, sword, mace or empty hand where no charged anchor is. On the next tick it runs
  after the number key pressed within 30 ms of it, or as it was pressed if there is none. A click on
  a chest or any block with a menu never waits, nor does glowstone placed away from anchors; a screen
  or a world change in between drops it, as Vanilla drops the clicks still queued.
- The settings' core note counts the clicks that went with their key.

What a server receives is Vanilla's tick: the slot change, then the click. The waiting click goes out
in the tick of its key, which is where a click pressed just after the key would have gone; nothing is
sent sooner than it was pressed, and no click is created or repeated.

## Measured

**Added latency: 0 ms**

| Build | own: cycles | own: click to use, median / p90 | slip: cycles | slips in the same tick | slips across ticks | fast: cycles | Grim alerts | Ticks Vanilla cannot send |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Vanilla | 40/40 | 25 / 44 ms | 91/120 | 31/31 | 1/19 | 89/120 (4.4/s) | none | 0 |
| Herzium 1.12.0 + KoHs Anchor's 0.6.0 (the recording) | 40/40 | 25 / 45 ms | 47/120 | 0/13 | 0/4 | 118/120 (5.0/s) | none | 0 |
| Herzium 1.12.1 + KoHs Anchor's 0.6.1 | 40/40 | 24 / 48 ms | 120/120 | 44/44 | 22/22 | 117/120 (5.0/s) | none | 0 |
| Herzium 1.12.1 alone | 40/40 | 24 / 45 ms | 85/120 | 37/37 | 0/15 | 120/120 (5.1/s) | none | 0 |

**Added latency: 100 ms**

| Build | own: cycles | own: click to use, median / p90 | slip: cycles | slips in the same tick | slips across ticks | fast: cycles | Grim alerts | Ticks Vanilla cannot send |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Vanilla | 38/40 | 29 / 45 ms | 98/120 | 32/32 | 0/18 | 29/120 (3.4/s) | AirLiquidPlace x14 | 0 |
| Herzium 1.12.0 + KoHs Anchor's 0.6.0 (the recording) | 40/40 | 28 / 47 ms | 81/120 | 0/15 | 3/6 | 103/120 (4.4/s) | AirLiquidPlace x4 | 0 |
| Herzium 1.12.1 + KoHs Anchor's 0.6.1 | 40/40 | 28 / 47 ms | 117/120 | 39/39 | 19/22 | 97/120 (4.1/s) | none | 0 |

- *cycles*: cycles whose anchor exploded, of the cycles played. "slip" and "fast" are three runs of
  40 cycles each, with the same presses; the runs differ in where the ticks fall.
- *click to use*: from the press of a click to the moment the game used the item, at the player's
  rhythm.
- *slips*: slips whose click used its key's item, counted only while the loop was in step.
- *Grim alerts* over the three rhythms; *ticks Vanilla cannot send*: the laboratory's own judge (a
  hotbar change after a click of the same tick, the same slot twice, three slots in one tick).

## Limits

- The 30 ms window is a choice. The recording shows that slips happen and how often, not how many
  milliseconds each one was.
- The wait across a tick is the one place where KoHs Anchor's forgives more than Vanilla. It only
  acts on a click that the drawn world says can do nothing for the loop; if that drawing is wrong,
  the click runs one tick late.
- In the fast rhythm with 100 ms of latency, every build loses cycles: the anchor the next click needs
  is not in the world yet. KoHs Anchor's holds those clicks for the server's anchor, so the rate of
  completed cycles depends on where the ticks fall from one run to the next.
