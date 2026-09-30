# Changelog

## 0.4.0 — 30 September 2026

Your anchor, your light, and clicks in Vanilla's own tick shape. Measured on 26.2; see
[the 0.4.0 notes](docs/research/anchors-0.4.0.md).

### Clicks and ticks

- **Vanilla's tick shape.** A burst in pressed order now goes out one slot change per tick, before
  that tick's clicks, as Vanilla sends it; the rest waits for the next tick, still in pressed order
  and in Vanilla's own counters. Nothing for Grim's `PacketOrderE` or `MultiPlace` to see: 100 % of
  anchors and 0 alerts next to Herzium against Grim in its strict configuration.
- Pressed order, fresh target, early clicks, no stacking and the instant detonation effects are
  always on and no longer options.
- **Anchor debounce** and **glowstone debounce** (new, off by default): a second anchor or glowstone
  with the same slot sooner than the chosen time is refused before anything is predicted or sent.
  Switching slots ends the wait; detonations are never refused.
- **Glowstone guard** (new, off by default): in an anchor fight glowstone only charges anchors; a
  click that would put it down as a block is dropped. It rules out the safe anchor, so turning it on
  shows a safe anchor clip first (H.264 at 60 fps, decoded with JCodec on a background thread) and
  then an animated charge with the respawn anchor's sounds.

### Detonation

- The hidden detonated anchor also takes its light with it: the client's light engine sees the
  drawn air, so no ghost light is left for a round trip. It comes back if the server keeps the anchor.
- The veil lifts after a wait that follows the connection (three round trips and a quarter second)
  instead of a fixed time.
- **Explosion smoke** (new): Vanilla, one light puff, or none. Other explosions keep theirs.

### Anchor custom (new tab)

- A workshop with the 3D anchor: colour the frame and the glow layers while keeping their texture,
  give each charge light its own colour, or paint pixel by pixel with brush, eraser, eyedropper,
  fill and undo. It reads the resource pack's anchor texture, and the skin reaches every anchor in the
  world at once.
- **KoHs Crystal Tweaks colours**: one click translates your crystal colours to the anchor (frame,
  glow, charge lights, light and enemy colour) and keeps them in step. `crystal_tweaks.json` is only
  read.

### Glow anchors (new tab)

- Charged anchors give off light: emissive pixels, a bloom shaped by them, and light on the blocks
  around that spreads through the air like the game's own, round corners and down a pillar, with
  smooth corners. Walls hide it.
- Colour from the texture or a custom one; power, bloom, light on the surroundings, emissive pixels,
  breathing and growth with the charge.
- Three qualities (performance, balanced, quality). Anchors out of view and faces turned away are
  never drawn, the bloom steps down with distance, only the nearest anchors light their
  surroundings, and a vertex budget caps the frame: 64 charged anchors in view went from 353
  thousand vertices a frame to a fraction of that.
- **Enemy anchors**: the ones that appear where you placed none glow in their own colour. Your
  placements are told apart by the server's acknowledgement of each click, so another player reusing
  your hole right after you still counts as an enemy. The header's switch turns the whole screen to
  their side, in crimson, with three tabs of their own: **Glow** (whether they glow in their own
  colour), **Colours** (that colour) and **Advanced**, which is still being made: "Coming soon" over
  an endless battle, your anchors against theirs in three layers, throwing glowstone until the fifth
  one sets an anchor off (a click throws one from your side). Switching plays an animation (your
  anchor rises, the screen turns red, theirs takes its place, and back to the tab you left), and the
  first time a window says what they are, with "Don't show again".

### Sounds (new tab)

- Replace the anchor charge and explosion sounds with any sound the game knows, with volume, pitch
  and a preview.

### Herzium (new tab)

- Herzium's hotbar order moves here. It is read from Herzium's own config and changed through
  Herzium, which saves it; last input is marked as the recommended order. The first time, a window
  explains both mods and Herzium's three orders.
- **Better communication with Herzium orders** (new, on by default): the hotbar keys a burst applies
  are reported to Herzium, and Herzium's hotbar preview is dropped when a burst is about to use
  another item. Measured with Herzium 1.10.7: without it the hotbar showed the sword for a tick while
  the anchor was placed; with it, the anchor.
- Without Herzium the tab is dimmed and opens a window with Modrinth's turning logo and a link to
  Herzium's page.

### KoHs (new tab)

- Zymekoh (a.k.a. Kohzemyora), who makes KoHs Anchor's, drawn as on the KoHs Mod Suite site and
  animated layer by layer, with her aka, what she does, links to Discord, the site and Modrinth, and
  "KoHs on top".

### Advanced options and safety

- The not secure options take a second, held warning, and only act in singleplayer, on the local
  network and on servers allowed one by one; anywhere else they are suspended and the player is asked
  once per connection. Allowed servers are kept in memory only, never saved or shown.
- **Developer mode** (new): names become the code behind them, and an inspector shows what every
  part of the screen is (F12 hides it).

### Interface

- Seven tabs, three on the enemy's side; the anchor cycle time (last and best) in the session
  numbers.
- The header's mark is a small Nether portal turning on itself, drawn pixel by pixel at the game's
  own twenty frames a second, with the four charge lights riding its ring; crimson on the enemy's
  side.
- Faster settings screen: shapes drawn from many spans get a GUI stratum of their own, which had cost
  the screen half its frame rate.
- `checkLayout` also checks the tabs, the glowstone guard window and the explaining windows at every
  size.
- New icon. Every text in English and seven Spanish locales.

## 0.3.0 — unreleased

Why Anchor Optimizer felt faster, and the answer, with a new settings screen. See
[the write-up](docs/research/advanced-options-0.3.0.md). Not measured yet.

- **Hide detonated anchor** (new, on by default): a detonated anchor disappears from the chunk
  mesh (Vanilla and Sodium) and its outline the moment it is used. Drawing only: the block, its
  collision, the crosshair's raycast and every packet are unchanged, so nothing new reaches the
  server.
- Instant detonation now shows the explosion's sound and flash, and hides the anchor, the moment
  "use" is pressed (remapped key or mouse), not on the next tick. The use itself still runs in
  the tick.
- New tab **Advanced · not secure**, crimson, with two options off by default. Switching one on
  opens a warning ("YOU ARE WARNED") that must be read for 2.2 s, then plays the anchor ritual.
  - **No-wait chain**: clicks on an exploding anchor are sent at once instead of waiting for the
    server, and the next anchor, its charge and its explosion are drawn at once. Glowstone that
    would land as a block and anchors that would stack are dropped; a click whose crosshair passes
    through a drawn anchor the world does not have yet waits for it.
  - **Instant detonation click**: a detonating click is sent the moment it is pressed, between
    client ticks. Up to 50 ms sooner; Vanilla never sends a click between ticks.
- The settings screen is redone in the Zymekoh style: four tabs, a turning anchor sigil, the 3D
  anchor inside a ritual circle that lights its charges, blade-cut transitions, crimson for
  danger. Session numbers change with the tab.
- `verify_mixin_targets.py` checks the new targets on every version.

## 0.2.1 — unreleased

Anchor bursts that went wrong next to Herzium's last-input hotbar order, reproduced with the
player's own setup (use on the period key, Herzium 1.10.5, the mctiers anchor hotbar) in the KoHs
Anchor lab. See [the write-up](docs/research/anchors-and-herzium-0.2.1.md).

- Pressed order covers every burst that mixes number keys and uses, unless all its number keys
  come before its uses and are the same slot. 0.2.0 only checked whether a use got a different
  item under Vanilla's highest-slot rule, so "anchors, use, glowstone" and "glowstone, use,
  sword" inside one tick were left to the hotbar pass: under Herzium's last-input order the
  anchor click placed a glowstone block and the charge click hit the anchor with the sword; under
  Vanilla's order the key pressed after the use was lost.
- Early clicks: a repeat click with the same item on the same exploding anchor joins the one
  already waiting instead of stacking a second anchor on the first, and waiting clicks whose
  anchor the server never removes are dropped instead of landing on it.
- Fresh target only re-aims a use when the item changed since the previous use of the tick. A
  double click with anchors in hand no longer aims the second click at the anchor the first one
  just placed, which stacked a second anchor on it.
- New option, on by default: **No stacked anchors**. In anchor play a second use with the same
  item in the same tick joins the first; on the fire an explosion leaves it used to stack a second
  anchor on the first, in Vanilla too. Glowstone on an anchor is not limited.
- The key "use" is bound to makes no difference: the period key and the right mouse button give
  the same results.

## 0.2.0 — unreleased

Measured on a local Minecraft 26.2 server with Grim Anticheat and 0 to 150 ms of added latency:
152–165 ms per anchor when clicking every 50 ms, every anchor exploded, no wasted click and no
Grim alert. See [the lab write-up](docs/research/server-lab-0.2.0.md).

- Early clicks wait: a click on an anchor this client just detonated waits until the server's
  removal arrives, then lands on the free block with the item it was pressed with. At most six
  clicks wait, for at most 0.7 s. New option, on by default, and a session counter.
- Instant detonation no longer touches the world: it plays the sound and flash and remembers the
  anchor as detonating. Removing the anchor on the client was tried and dropped, because Grim
  flags and cancels the next click (`AirLiquidPlace`).
- The settings preview is now a real 3D respawn anchor drawn by the game's block renderer: drag
  to turn, scroll to zoom, click to charge; it turns on its own again after a few idle seconds.
- New icon.
- `verify_mixin_targets.py` also checks `ClientLevel.setServerVerifiedBlockState` and that block
  and section updates still arrive through it.
- `build-all.ps1` ignores an inherited `JAVA_HOME` that is not JDK 25 or newer.

## 0.1.0 — unreleased

First build of the new KoHs Anchor's. It replaces the archived `KoHs-Anchors-26` and
`koks-anchors-1` projects and shares no code with them.

- Pressed order: use and hotbar presses that land in the same tick are applied in the order they
  were pressed whenever Vanilla's order would put a different item behind a use.
- Fresh target: second and later uses in a tick re-read the crosshair with Vanilla's raycast.
- Instant detonation: a charged anchor's explosion sound and flash play on the tick of the use;
  the server's copy of the same explosion does not repeat them.
- Optional removal of block-debris particles for anchor explosions only.
- Mod Menu settings screen: purple glass over a transparent purple veil, animated anchor, live
  session numbers, reduced-motion option, English and Spanish.
- Builds for Minecraft 1.21.11, 26.1, 26.1.1, 26.1.2, 26.2 and 26.3 from one source tree.
