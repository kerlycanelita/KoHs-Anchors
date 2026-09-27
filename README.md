# KoHs Anchor's

[![GitHub](https://img.shields.io/badge/GitHub-KoHs--Anchors-6f2cff?style=for-the-badge&logo=github)](https://github.com/kerlycanelita/KoHs-Anchors)
[![Issues](https://img.shields.io/badge/Report-Issues-a855f7?style=for-the-badge&logo=githubissues)](https://github.com/kerlycanelita/KoHs-Anchors/issues)
[![Discord](https://img.shields.io/badge/Join-Discord-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/9t2VxEF7UU)

<p align="center">
  <img src="src/main/resources/assets/kohs_anchors/icon.png" alt="KoHs Anchor's icon" width="160">
</p>

**Respawn anchor and glowstone clicks land in the order you pressed them, on the block you are
actually looking at.**

Minecraft only counts key presses between ticks. When a tick handles them it applies every
number key first and every use press afterwards, all aimed at the block the crosshair was on
before any of them. A fast *place anchor → glowstone → detonate* that fits in one tick can
therefore charge with the wrong item or aim the glowstone at the floor under the anchor you just
placed: the click is lost. KoHs Anchor's keeps the order and the target you actually gave it.

> **Status: 0.1.0, development build.** It compiles for every supported version and each Mixin
> target is checked against that version's bytecode, but it has not been played yet. Minecraft
> 26.2 is the development focus.

## What it changes

- **Pressed order.** When use and hotbar keys land in the same tick, and applying them in pressed
  order would put a different item in your hand for at least one use, they are applied in the
  order you pressed them.
- **Fresh target.** A second use in the same tick aims at what your crosshair hits after the
  first one, such as the anchor you just placed, using Minecraft's own raycast.
- **Instant detonation.** Using a charged anchor where anchors explode plays the explosion's
  sound and flash on that tick instead of a round trip later. When the server's explosion arrives
  for the same block its sound and flash are not repeated; its debris, knockback and block
  changes apply as always.
- **Anchor explosion debris.** Optional: hide the block-debris particles of anchor explosions to
  save frames in anchor fights. Other explosions keep theirs.

## What it never does

Every action is one press Minecraft already counted, applied in the same tick Vanilla would
apply it. The mod never creates, repeats or delays a press, never selects a slot you did not
press, never touches the repeat delay of a held key, never writes a packet of its own, never
extends reach and never changes a block in your world. Damage, knockback, blocks and the
explosion itself are decided by the server.

Reordering is only used for anchor play: an anchor or glowstone in hand or in a pressed slot, or
the crosshair on an anchor. Bursts that also open the inventory, swap hands, drop, attack or aim
at a container are left entirely to Vanilla.

## Settings

With [Mod Menu](https://modrinth.com/mod/modmenu) installed, open *Mods → KoHs Anchor's*. The
screen is a purple glass panel over a transparent purple veil, with a charging, detonating anchor
and this session's numbers: bursts kept in pressed order, uses that landed on the real target,
and detonations shown instantly and then confirmed by the server. Every option, and the screen's
own animations, can be turned off. Settings live in `config/kohs_anchors.json`.

## Supported versions

| Minecraft | Fabric Loader | Java |
| --- | --- | --- |
| 26.3 | 0.19.5 | 25 |
| **26.2** (focus) | 0.19.3 | 25 |
| 26.1.2, 26.1.1, 26.1 | 0.18.6 | 25 |
| 1.21.11 | 0.18.4 | 21 |

Client-side only. Fabric API is not required. Each jar declares exactly one Minecraft version.

## Building

```powershell
.\gradlew.bat build "-Pmc=26.2"
```

Quote the `-P` argument in PowerShell. The jar is written to
`build/libs/kohs-anchors-<minecraft>-<mod_version>.jar`; the one ending in `-sources.jar` is not
the playable build. `build` also runs `checkLayout`, which fits the settings screen to every GUI
size from 240 × 140 to 1600 × 1000 and fails on any overlap or clipped region.

```powershell
powershell -ExecutionPolicy Bypass -File tools\build-all.ps1
```

builds every version, checks every Mixin target against that version's Minecraft bytecode
(`tools/verify_mixin_targets.py`) and copies the jars with their checksums to
`dist/<mod_version>/`.

## Repository layout

| Location | Contents |
| --- | --- |
| `src/client/java/` | The mod, written against Minecraft 26.2. |
| `src/compat/classic/`, `src/compat/legacy/` | The few calls spelled differently on 26.1.x and 1.21.11. |
| `src/main/resources/` | Metadata, icon and translations (English and Spanish). |
| `src/layoutCheck/` | The settings screen's geometry check. |
| `gradle/versions.properties` | The build matrix. |
| `tools/` | Build-all script and the Mixin target verifier. |
| `docs/` | Research and design notes. |

Start with the [documentation index](docs/README.md).

## License

MIT. See [LICENSE](LICENSE).

## Credits

Made by **zymekoh**.
