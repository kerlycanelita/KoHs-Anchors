# KoHs Anchor's documentation

- [Project README](../README.md): what the mod does, supported versions, building.
- [Changelog](../CHANGELOG.md).
- [Entrada del nexo: 1.21.11 frente a 26.x](research/entrada-nexo-1.21.11-vs-26.md): the bytecode
  comparison behind the design, where clicks are lost and the fair-play boundary (in Spanish).
- [Laboratorio de servidor 0.2.0](research/laboratorio-servidor-0.2.0.md): the real speed of
  place, charge and detonate against a Grim Anticheat server with 0 to 150 ms of added latency,
  Vanilla against 0.2.0, what was tried and rejected, with charts and screenshots (in Spanish).
- [Nexo y Herzium 0.2.1](research/nexo-y-herzium-0.2.1.md): which item each click really gets next
  to Herzium's last-input hotbar order, with "use" on the period key; every misplaced block, 0.2.0
  against 0.2.1, and what belongs to Herzium (in Spanish).

The images under `images/` come from the lab: the charts are drawn by `charts.py` and the
screenshots by the lab client's `gallery.labscript`, both in
[KoHs Debug Tools](https://github.com/kerlycanelita/KoHs-Debug-Tools-for-KoHs-Mods/tree/main/anchors-debug).

## Source map

| Package | Responsibility |
| --- | --- |
| `input` | The press journal, burst replay in pressed order, the fresh raycast when the item changes, the hold for early clicks on a detonating anchor, and merging double clicks so no anchor is stacked. |
| `predict` | Instant detonation, the deduplication of the server's explosion effects, and which anchors are still detonating until the server's state arrives. |
| `mixin` | The hooks: `handleKeybinds`, `KeyMapping.click`, `useWithoutItem`, `handleExplosion`, `setServerVerifiedBlockState`. |
| `compat` | `Mc` and `AnchorBlockPreview`, the only classes that differ between eras (`src/compat/*` replaces them). |
| `config` | `config/kohs_anchors.json`. |
| `gui` | The Mod Menu screen: layout, theme, drawing primitives, widgets and the 3D anchor preview. |

## Checks

- `gradlew build "-Pmc=<version>"` compiles and runs `checkLayout`.
- `python tools/verify_mixin_targets.py [versions]` checks every Mixin target and the call order
  the mod relies on against each version's Minecraft bytecode.
- `tools/build-all.ps1` does both for every version and fills `dist/<mod_version>/`.
