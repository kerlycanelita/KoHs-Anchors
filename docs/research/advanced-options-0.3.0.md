# Hidden anchor, no-wait chain and instant click (0.3.0)

Written on 28 September 2026. Not measured in the lab or in game yet: the player will test it. This
document explains why Anchor Optimizer felt faster, what each new option does, and the fair-play and
anticheat analysis of each.

## Why Anchor Optimizer felt faster

The jar from the player's instance (`AnchorOptimizer-26.3.jar`, "Anchor Optimizer" 1.0.6 by
cutebow) was read without running it:

- On detonation, before sending anything, it replaces the anchor in the local world with an
  **invisible barrier block**, without an outline if you enable `removeOutline`. For up to 40 ticks
  it ignores the server packets that would restore it.
- The next click goes out at once towards the old anchor's position. By the time it arrives, the
  server has already exploded it and places the new one there.
- KoHs Anchor's 0.2.1 left the anchor visible until the server removed it, and held the next click
  until that moment: one more round trip, and up to one tick.

That mod also adds the WestShop server to the multiplayer list, updates itself from Modrinth and is
licensed "All Rights Reserved".

## What 0.3.0 does

| Option | Tab | Default | What changes |
| --- | --- | --- | --- |
| Hide detonated anchor | Effects | On | The anchor disappears at once from the chunk drawing (Vanilla and Sodium) and loses its outline. The world, collision and packets do not change. |
| Instant detonation | Effects | On | The sound, the flash and the hiding happen the instant you press use (remapped key or mouse), not on the next tick. The use itself still goes out in the tick. |
| No-wait chain | Advanced · not secure | Off | Clicks on an exploding anchor are sent without waiting, and the next anchor, its charge and its explosion are drawn at once. |
| Instant detonation click | Advanced · not secure | Off | A detonating click is sent the instant you press it, between ticks (up to 50 ms sooner). |

The no-wait chain is Anchor Optimizer's idea, improved:

- **Without touching the world.** Only the drawing changes. Collision and the crosshair stay those
  of the real block, so there are no ghost barriers and no trouble in creative.
- **Every click is judged against what you see.** The next anchor appears in its place, with its
  charge lights, and its explosion shows at once.
- **No misplacements.** Glowstone that would land as a block where there is no anchor, and an
  anchor that would stack on one just placed, are dropped.
- **Clicks through a drawn anchor.** If the crosshair passes through an anchor you see but the world
  does not have yet, the click waits for the world to have it instead of landing on the floor
  behind it.
- **Crimson tab and warning.** Turning on an option in this tab goes through the "YOU ARE WARNED"
  warning, with 2.2 s of reading, and ends in the anchor ritual.

## Fair-play and anticheat analysis

Done with the `minecraft-fair-play-anti-cheat-compatibility-analyst` skill.

### Hide detonated anchor and instant detonation

- **Implementation:** client, drawing and sound only.
- **Does it change Vanilla behaviour?** No. No new packets, no different order, no change to the
  world.
- **Anticheat sensitive?** No. Category: COSMETIC_CLIENT_ONLY.
- **Server rule risk:** low; it is a visual prediction.
- **Confidence:** confirmed by design (only meshing, the outline and sound are hooked).

### No-wait chain

- **Implementation:** client. Every click is sent by Vanilla's `startUseItem`, at the block
  Vanilla's crosshair aims at: it is the same packet a Vanilla player sends when clicking the
  still-visible anchor.
- **Does it change Vanilla behaviour?** Little in the packets. What changes is that there is no wait
  any more and you see the result sooner, so you click sooner and more often.
- **Anticheat sensitive?** Probably. In the lab, Vanilla's early clicks on an exploding anchor
  caused Grim `AirLiquidPlace`: 6 alerts at +100 ms and 2 at +150 ms in 20 cycles. This option makes
  that pattern systematic. Category: INPUT_ASSISTANCE (timing).
- **Server rule risk:** many servers forbid "anchor optimizers".
- **Server-side alternative:** the server accepts, with lag compensation, a placement at the position
  of a just-exploded anchor; or an exemption configured by the server owner. It is not something the
  client should work around.
- **Confidence:** likely, from Vanilla's data; the option itself has not been measured.

### Instant detonation click

- **Implementation:** client; sends `ServerboundUseItemOnPacket` between client ticks.
- **Does it change Vanilla behaviour?** Yes, in the order and timing of packets. Vanilla only sends
  interactions inside the tick, before its movement packet; this option sends it after the previous
  tick's movement packet.
- **Anticheat sensitive?** Yes. Categories: PACKET_ORDER / TIMING. Anticheats with packet order
  checks may flag it; it was not verified against Grim's code.
- **Gain:** at most 50 ms (about 25 ms on average), and only on the detonation: the server still
  acts on its own tick.
- **Server rule risk:** high; it belongs to the same family as "hitreg" mods, which many servers
  forbid.
- **Confidence:** needs verification in the lab with Grim.

### Safeguards

- Both advanced options are off by default.
- Turning them on requires reading the warning for 2.2 s.
- Turning them off is immediate.
- They live alone in a crimson tab marked "not secure".
- Future improvement: only enable them on servers that announce them through a handshake with a
  server mod or plugin.

(0.4.0 added a second warning, the server lock and the per-connection question: see
[the 0.4.0 notes](anchors-0.4.0.md#advanced-options-server-lock).)

## Suggested test plan

In the KoHs Debug Tools lab (`anchors-debug`), with Grim and Ping Equalizer:

1. `bench triple` and `bench spam` with **Hide detonated anchor**. Expected: nothing changes on the
   server or in the alerts compared with 0.2.1.
2. The same benches with **No-wait chain** at +0, +50, +100 and +150 ms. Compare the time per anchor
   with 0.2.1 and count the `AirLiquidPlace` alerts.
3. **Instant detonation click**. Watch Grim's console for packet order alerts.
