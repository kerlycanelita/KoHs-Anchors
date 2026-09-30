# Anchor input: 1.21.11 against 26.x

Checked on 27 September 2026 with `javap -c -p` on the jars Loom downloads (`minecraft-clientonly`
/ `minecraft-common` with Mojang's names) for 1.21.11, 26.1, 26.1.1, 26.1.2, 26.2 and 26.3.
`tools/verify_mixin_targets.py` repeats the part the mod depends on in every build.

The starting question: anchors feel better on 1.21.11 and earlier. What changed in 26.x?

## What is the same (the click's path)

| Step | Result |
| --- | --- |
| `Minecraft.handleKeybinds` | Same order on 1.21.11 and 26.2: hotbar keys (at most **one press per key per tick**, slots 1 to 9), inventory, quick actions, offhand, drop, and last **every** use (`while (keyUse.consumeClick()) startUseItem()`), then the held-key repeat (`rightClickDelay == 0`). On 26.2 chat, command, advancements, social and HUD moved to `Gui.handleKeybinds`, before the inventory. |
| `Minecraft.startUseItem` | Identical for blocks. 26.2 only merged `interactAt` and `interact` for entities. Sets `rightClickDelay = 4` on every use. |
| `MultiPlayerGameMode` | `useItemOn`, `performUseItemOn`, `ensureHasSentCarriedItem`, `startPrediction` and `tick` identical. |
| `RespawnAnchorBlock` | Identical apart from compiler details. On the client, `useWithoutItem` returns `CONSUME` with a charge above 0 in **any** dimension: whether it explodes (`canSetSpawn`) is only decided on the server. |
| `ItemInHandRenderer.tick` | Identical: the item-switch animation did not change. |
| Crosshair raycast (`pick`) | 1.21.11 has a public `GameRenderer.pick(float)`; 26.x a private `Minecraft.pick(float)`. Same logic, every frame and at the start of every tick with `1.0F`. On 26.2 `gameMode.tick()` runs before `pick`; on 1.21.11, after it. |

Conclusion: **the way the client turns clicks into anchor actions did not change.** What gets lost
(see below) gets lost the same way on 1.21.11 and on 26.x.

## What did change: chunk meshing

1.21.11 recompiles dirty sections and uploads their meshes in the same pass
(`LevelRenderer.compileSections` calls `SectionRenderDispatcher.uploadAllPendingUploads`).

26.x split the process: `LevelExtractor.extract` collects the changes in
`LevelRenderState.sectionUpdateRenderStates` while the frame is extracted, `compileSections`
compiles them (`compileSync` / `compileAsync`), and the upload moved to the "uber buffers"
(`SectionRenderDispatcher.uploadTerrainBuffersToGpu`).

With the default *Chunk updates: none*, placing, charging or breaking an anchor is compiled in the
background on both versions. **Hypothesis:** on 26.x those changes take one or more extra frames to
show. **Not measured.** Before touching rendering, the frames between the block change and the
visible mesh have to be measured on both versions, with the same scene.

The explosion changed too: since 1.21.9 its packet carries block debris particles
(`trackExplosionEffects`), which can cost frames in an anchor fight.

## Where clicks get lost (on every version)

1. **Order.** "use, 2, use" inside one tick is applied as "2, use, use": the anchor you meant to
   place is tried with glowstone in hand. And several number keys in the same tick win by slot
   number, not by which was pressed last.
2. **Stale target.** Every use of a tick aims at the raycast made before the first one. The
   glowstone aims at the floor where you just placed the anchor, not at the anchor, and the click
   fails.
3. **Feedback.** The explosion is only seen and heard when the server's packet arrives, a round trip
   after the click.

## What 0.1.0 does, and its fair-play boundary

| Feature | Category | Does the server see it? | Risk |
| --- | --- | --- | --- |
| Pressed order | Input (order) | Yes: `slot → use → slot → use` in one tick instead of `slot → use → use` | Low. Every packet is a real press, none extra, same tick. Tested against Grim 2.3.74 with up to +150 ms of lag: 0 alerts ([lab](server-lab-0.2.0.md)). |
| Fresh target | Input (precision) | Yes: the use aims at the new block | Low. Vanilla's raycast, reach untouched; it is what Vanilla would do one frame later. |
| Instant detonation | Cosmetic | No | None. It removes no block and applies no damage. |
| Anchor debris | Cosmetic, quality/performance | No | None. Stated as a visual quality reduction. |

Ruled out on purpose:

- removing the repeat delay of a held click (that is *fast place*);
- running uses outside the tick, in the input callback: the packet would arrive with a rotation the
  server has not received yet, at a moment Vanilla never uses;
- choosing slots, charging or detonating for the player;
- turning the anchor into air in the client's world (it desyncs collision and movement). A 0.2.0
  draft tried it in the lab: Grim flags and cancels the next click (`AirLiquidPlace`). 0.2.0 holds
  the early click instead.

## Still to do

- Done in the [server lab](server-lab-0.2.0.md): the sequence `anchor → use → 2 → use → 3 → use`
  inside one tick and at rhythm, on 26.2, through Minecraft's keyboard and mouse handlers, against
  Grim and with up to +150 ms of lag. Still to test with a remapped key, the offhand and human
  hands.
- Measure the anchor's meshing latency on 1.21.11 and 26.2 (the hypothesis above).
