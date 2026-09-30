# KoHs Anchor's documentation

- [Project README](../README.md): what the mod does, the gallery, supported versions, building.
- [Changelog](../CHANGELOG.md).
- [KoHs Anchor's 0.4.0](research/anchors-0.4.0.md): Vanilla's tick shape and strict Grim, the ghost
  light, what the glow costs and how its light spreads, the settings screen's frame rate, how enemy
  anchors are told apart, and what KoHs Anchor's tells Herzium.
- [Anchors and Herzium 0.2.1](research/anchors-and-herzium-0.2.1.md): which item each click really
  gets next to Herzium's last-input hotbar order, with "use" on the period key; every misplaced
  block, 0.2.0 against 0.2.1, and what belongs to Herzium.
- [Advanced options 0.3.0](research/advanced-options-0.3.0.md): why Anchor Optimizer felt faster,
  the hidden detonated anchor, the no-wait chain and the instant detonation click, with the
  fair-play and anticheat analysis of each.
- [Server lab 0.2.0](research/server-lab-0.2.0.md): the real speed of place, charge and detonate
  against a Grim Anticheat server with 0 to 150 ms of added latency, Vanilla against 0.2.0, what was
  tried and rejected, with charts and screenshots.
- [Anchor input: 1.21.11 against 26.x](research/anchor-input-1.21.11-vs-26.md): the bytecode
  comparison behind the design, where clicks are lost and the fair-play boundary.
- [Modrinth description](modrinth.md): the project page's text.

The lab images under `images/` come from the lab: the charts are drawn by `charts.py` and the lab
screenshots by the lab client's `gallery.labscript`, both in
[KoHs Debug Tools](https://github.com/kerlycanelita/KoHs-Debug-Tools-for-KoHs-Mods/tree/main/anchors-debug).
The `gallery-*` images are 0.4.0 in a 26.2 development client at 1920 × 1080, GUI scale 3, with
default settings.

## Source map

| Package | Responsibility |
| --- | --- |
| `input` | The press journal, burst replay in pressed order and Vanilla's tick shape, the fresh raycast when the item changes, the hold for early clicks on a detonating anchor, merging double clicks, debounce and the glowstone guard, the anchor cycle. |
| `predict` | Instant detonation effects, the veil that draws a detonated anchor as gone (and its light), the deduplication of the server's explosion effects, explosion smoke, and the connection's latency. |
| `glow` | The anchor glow: emissive pixels, bloom geometry, the light on the surroundings, and the tracker that tells your anchors from the enemy's. |
| `skin` | The anchor's skin: reading the atlas, the colours and pixel paint of each layer, mipmaps. |
| `sound` | The replaced charge and explosion sounds. |
| `integration` | Herzium (hotbar order and communication) and KoHs Crystal Tweaks (colours), both by reflection or file reads, never a compile-time dependency. |
| `safety` | Where the not secure options may act. |
| `video` | The safe anchor clip, decoded with JCodec on a background thread. |
| `mixin` | The hooks: `handleKeybinds`, `KeyMapping.click`, `useItemOn`, `useWithoutItem`, `handleExplosion`, `setServerVerifiedBlockState`, `handleBlockChangedAck`, the block light engine, the chunk meshes and the level renderer. |
| `compat` | `Mc` and the classes that differ between eras (`src/compat/*` replaces them). |
| `config` | `config/kohs_anchors.json`. |
| `gui` | The Mod Menu screen: layout, theme, drawing primitives, widgets, the workshop, the windows and the 3D anchor preview. |

## Checks

- `gradlew build "-Pmc=<version>"` compiles and runs `checkLayout`.
- `python tools/verify_mixin_targets.py [versions]` checks every Mixin target and the call order
  the mod relies on against each version's Minecraft bytecode.
- `tools/build-all.ps1` does both for every version and fills `dist/<mod_version>/`.
