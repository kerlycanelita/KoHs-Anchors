# KoHs Anchor's documentation

- [Project README](../README.md): what the mod does, supported versions, building.
- [Changelog](../CHANGELOG.md).
- [Entrada del nexo: 1.21.11 frente a 26.x](research/entrada-nexo-1.21.11-vs-26.md): the bytecode
  comparison behind the design, where clicks are lost, the fair-play boundary and what is still to
  be measured (in Spanish).

## Source map

| Package | Responsibility |
| --- | --- |
| `input` | The press journal, burst replay in pressed order and the fresh raycast between uses. |
| `predict` | Instant detonation and the deduplication of the server's explosion effects. |
| `mixin` | The hooks: `handleKeybinds`, `KeyMapping.click`, `useWithoutItem`, `handleExplosion`. |
| `compat` | `Mc`, the only class that differs between eras (`src/compat/*` replaces it). |
| `config` | `config/kohs_anchors.json`. |
| `gui` | The Mod Menu screen: layout, theme, drawing primitives and widgets. |

## Checks

- `gradlew build "-Pmc=<version>"` compiles and runs `checkLayout`.
- `python tools/verify_mixin_targets.py [versions]` checks every Mixin target and the call order
  the mod relies on against each version's Minecraft bytecode.
- `tools/build-all.ps1` does both for every version and fills `dist/<mod_version>/`.
