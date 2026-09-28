# Changelog

## 0.2.1 — unreleased

Anchor bursts that went wrong next to Herzium's last-input hotbar order, reproduced with the
player's own setup (use on the period key, Herzium 1.10.5, the mctiers anchor hotbar) in the KoHs
Anchor lab. See [the write-up](docs/research/nexo-y-herzium-0.2.1.md).

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
Grim alert. See [the lab write-up](docs/research/laboratorio-servidor-0.2.0.md).

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
