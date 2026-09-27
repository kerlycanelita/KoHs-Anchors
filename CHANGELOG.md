# Changelog

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
