# Changelog

## 0.5.1 — 2026-10-08

The anchor skin on 1.21.11, and what a fast fight showed.

### Fixed

- **1.21.11: "The anchor textures could not be read".** On 1.21.11 the game gives each frame of the
  charged anchor's animated top a texture with one mip level more than it fills. With 16 pixel
  textures, the default, that level has no pixels: writing the skin into it failed, and the whole
  skin switched itself off. The workshop showed that message instead of the anchor, and in the world
  the anchor kept part of its Vanilla textures, so its top did not match the rest. The skin now
  writes only the levels a texture has pixels for. 26.1 to 26.3 never had the extra level.
- **The mascot holds still while the options scroll.** She stood on the lowest control of the
  window, and the option rows counted: with every notch of the wheel her floor and her free space
  changed, and she moved. Rows are never her floor now, and in a list that scrolls the whole list
  area counts as taken, wherever the list is. Everything else she does is unchanged.
- **No anchor on top of the one that just exploded.** A click the chain runs as a detonation, on an
  anchor that glowstone the world had not seen yet had charged, made Vanilla's client predict a
  placement on that anchor's face: a ghost anchor, which the next clicks aimed at. Nothing is placed
  in the client world for those clicks now; the packet is the same.
- **No black square under an exploded anchor.** The hidden anchor still blocked the client's light
  until the server removed it. The client's light engines now read a hidden block as it is drawn.

### Changed

- The menu's entrance, the anchor that charges and explodes, plays once per game session; the next
  times the menu opens at once.

### Anchors Server

- **Instant double anchor** (new, under the instant detonation click, off by default): the second
  click of a double anchor, a double click with anchors on your charged anchor, is sent at the press
  too, right behind the detonation. Only where the server's bridge allows instant clicks. The
  statistics count the double anchors it sent.

## 0.5.0 — 2026-10-06

The server bridge: the anchor chain only where a server allows it, and what a server can add.

### Anchors Server (new tab)

- **The server bridge.** On every server the tab checks for the
  [KoHs Anchor's Bridge](https://github.com/kerlycanelita/KoHs-Anchors-Bridge) plugin: an anchor falls
  into a window and, while the server is asked, rings scan it and packets run to a small server. With
  a bridge its glow turns green and it hops into the tab's column; without one everything turns red, a
  padlock falls on it and the window says which server does not allow the anchor chain and why. In
  singleplayer the bridge is built in. The bridge talks through Fabric API's networking.
- **The instant detonation click moves here**: it only acts where the server's bridge allows it. The
  per-server allowing and its two warnings are gone: only the server's admin can allow it. (The
  no-wait chain that stood beside it is part of the core now, made safe: see below.)
- **Better glow enemy anchors** (new): the server says who placed each anchor, so one of yours never
  shows as an enemy's, even when you share the same hole.
- **Real latency** (new): a ping to the bridge once a second, used by every wait that depends on the
  connection.
- Each option, switched on, plays what it does over the anchor column.

### Core: anchors that explode as fast as you press

- **The immediate chain.** A click on an anchor that is still exploding goes out at once, at the old
  anchor, the packet a Vanilla player sends; the server, which has exploded it by then, puts the next
  anchor in its place, charges it and detonates it. Up to now these clicks waited for the server's
  removal and were then aimed again: on the ground of a real fight, which each explosion breaks, that
  second aim went over the hole or into it, the waiting clicks fell out of step with the next ones,
  and the anchor stopped exploding at all (seen in a recorded fight on a 26.2 server). What each
  click will do, the next anchor, its charge, its explosion, is drawn the moment it is pressed.
- **The anchor's block follows the server.** Vanilla keeps the player's own prediction at a block
  until the server acknowledges the click, so after a charge the old anchor stays in the world while
  the server, and an anticheat that follows what the client received, already have air there: a
  click then is what Grim flags as `AirLiquidPlace`. At an anchor the chain is driving, the world now
  shows the server's state the moment it arrives, so no click ever goes to a block the server removed.
- **No ghost blocks.** A chained click places nothing in the client world: Vanilla would put the new
  anchor beside the old one, or glowstone in the fire it left, where the server does not, and the
  next clicks would aim at that ghost. A click whose raycast passes through an anchor that is drawn
  but not in the world yet waits until the world has it, even a click that hits nothing behind it.
- **No anchor on your fresh anchor.** An anchor clicked on your own anchor that is not charged yet
  would sit on top of it and put every cycle after one block higher: it is dropped (sneak to stack).
- **Hide detonated anchor** is part of the core: always on, corrected by the server's answer.
- **Hero's Anchor Optimizer**, which players remember from 1.21.8, does the first part of this with a
  replaceable fake anchor, and exists for 1.21.6 to 1.21.11 only; it keeps the old anchor until the
  acknowledgement, the moment Grim flags. KoHs Anchor's now does it on every version from 1.21.11 to
  26.3 without that window.
- Measured in a new **crater** bench of the anchor lab (stone that each explosion breaks, nothing
  rebuilt between cycles, the player's setup, Grim, 0 to 150 ms): at +100 ms 93 % of the anchors
  explode, against 13 % with the old core and 60 % for Vanilla; 100 % at 0 and +50 ms; no Grim alert
  in any bench. The flat-platform benches stay at 100 %.
  [The write-up](docs/research/core-chain-0.5.0.md).

### General

- **Anchor fade** (new, on by default): a detonated anchor goes away with an animation instead of
  vanishing in one frame, in one of five styles: **Ghost** (the default: it rises and turns to light,
  the same size all the way, gone in a third of a second), **Sink**, **Shatter** (eight pieces fly out,
  spin and fall), **Disintegrate** (a cut of light runs down it and burns it into embers) and
  **Glitch** (slices, split colours, then off like an old screen). Choosing a style plays it in the
  preview. Drawing only: the next anchor goes down in its place at once.
- **Safe anchor view** (new, off by default, with a note before it switches on): once your anchor is
  charged, a square of your colour blinks on the ground where one block would cover you from its
  blast. It counts what Minecraft counts (the explosion's rays to your hitbox, your armour, the
  difficulty) and marks nothing when the blast would barely touch you, such as an anchor under a
  block with you above it. A hint: you place the block.
- The preview stands in front of an **amethyst wall**: an explosion breaks a ragged crater into it,
  shards flying, and two seconds later the blocks pull themselves back in, the rim first.

### Interface

- **Living tab icons**: the respawn anchor charges and flares, the skin tab's anchor runs through
  colours, glowstone and shroomlight burn, a jukebox's music note bobs and throws more notes, ancient
  debris and gilded blackstone catch the light. Herzium and KoHs keep their own icons; the server tab
  shows the server list's signal bars, green on a server whose bridge approves the mod, sweeping while
  it is asked, red where it is missing, grey off a server.

### Entry

- Opening the settings, an anchor charges and explodes into anchors and glowstone. The first time, a
  window says what the mod is and links the [legitimacy audit](docs/audits/legitimacy.md) and the
  plugin, a second one says what the bridge adds and points at "Switch to enemy Anchor's", and the
  switch calls attention to itself once.

### Enemy anchors

- Now a switch, off by default. Off, their page is only the switch and their red anchor; switched on,
  the anchor flies into its column and the tabs and options come in behind it.
- **Their own skin**: the Skin tab (was Colours) is the workshop for the enemy's anchor, with its own
  colours, charge lights and pixel painting, saved apart from yours (`config/kohs_anchors/skin/enemy/`).
  Their anchors are drawn with it in the world, a hair over the block; "Use mine" starts from your
  colours. Their glow colour moved to the Glow tab.

### Advanced

- A normal tab in a deeper purple, with developer mode only.

## 0.4.0 — 2026-09-30

Your anchor, your light, and clicks in Vanilla's own tick shape. Measured on 26.2 against Grim, then
played on every version from 1.21.11 to 26.3 in the KoHs Anchor lab, in singleplayer and against a
dedicated server with 100 ms each way; see [the 0.4.0 notes](docs/research/anchors-0.4.0.md).

### Clicks and ticks

- **Vanilla's tick shape.** A burst in pressed order now goes out one slot change per tick, before
  that tick's clicks, as Vanilla sends it; the rest waits for the next tick, still in pressed order
  and in Vanilla's own counters. Nothing for Grim's `PacketOrderE` or `MultiPlace` to see: 100 % of
  anchors and 0 alerts next to Herzium against Grim in its strict configuration.
- Pressed order, fresh target, early clicks, no stacking and the instant detonation effects are
  always on and no longer options.
- **Chains on one hole with latency** (found by the server lab, 100 ms each way, cycles pressed
  every 150 ms):
  - clicks held for an anchor's removal that are released after the next cycle already put,
    charged and detonated another anchor in that hole now wait for that anchor too, instead of
    landing on it;
  - a click aimed at an exploding anchor when the wait is full (six clicks) is dropped instead of
    landing on it, where it put an anchor beside the hole or glowstone in it: pressing faster than
    the server removes anchors is what fills the wait;
  - a held click, released, still has to do what it was pressed for: an anchor never goes down on
    an anchor, glowstone never as a block;
  - when the server removes a detonated anchor while one of the player's own predictions at that
    block waits for its acknowledgement, Vanilla keeps drawing the anchor until the acknowledgement.
    The anchor now counts as detonating until then, so the waiting clicks are no longer taken for
    clicks on an anchor the server kept and dropped, the next anchor click no longer lands on it
    (putting an anchor beside the hole), and the veil no longer lifts early to show it again;
  - a hitch of the game (half a second in the lab) no longer hands a waiting burst to Vanilla's
    order, which had used the sword three times: the press journal is cleared when a screen or a
    world change releases the keys, and otherwise keeps its order for up to two seconds.
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
- Setting the spawn with a charged anchor in the Nether is no longer taken for a detonation. The rule
  "anchors work here" is an environment attribute the game does not sync, so on 1.21.11 the client
  read it as false even in the Nether: the use played an explosion's sound and flash, hid the anchor
  until the server answered, and dropped the clicks that waited for it. The Nether's own dimension
  type now says it too. Found by the lab's Nether test; wrong since 0.1.0.
- Without Sodium, the game log no longer warns that the Mixin for Sodium's chunk meshes could not
  load its target: a Mixin plugin leaves that Mixin out unless Sodium is installed.

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
- A label with no room left ends in "…" instead of being cut mid-word, and fields being typed in
  keep their end in sight. The enemy window's "Don't show again" widens for a longer translation
  (Spanish's "No volver a mostrar" was cut to "No volver a most").
- On 26.3 the settings screen answers to the mouse and keyboard again: Minecraft numbers keys, buttons
  and Ctrl with SDL's codes there (the left button is 1, Escape 41), and the screen compared GLFW's.
  Every key and button now comes from the game's own constants, so each jar carries its version's
  numbers.
- `checkLayout` also checks the tabs, the glowstone guard window and the explaining windows at every
  size.
- New icon. Every text in English and seven Spanish locales.

## 0.3.0 — 2026-09-28

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

## 0.2.1 — 2026-09-28

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

## 0.2.0 — released as part of 0.2.1

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

## 0.1.0 — 2026-09-27

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
