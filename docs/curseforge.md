<!-- CurseForge summary (191/256): Respawn anchor and glowstone clicks land in the order you pressed them, on the block you are looking at, in Vanilla's own tick shape. Plus anchor skins, glow, enemy anchor colours and sounds. -->
<!-- Class: Mods · Main category: Utility & QoL · Additional: Cosmetic · License: MIT · Socials: GitHub, Discord -->

# KoHs Anchor's

**Your anchor clicks land in the order you pressed them, on the block you are looking at, and your anchors look, glow and sound the way you want.**

[GitHub](https://github.com/kerlycanelita/KoHs-Anchors) · [Report issues](https://github.com/kerlycanelita/KoHs-Anchors/issues) · [Discord](https://discord.gg/9t2VxEF7UU)

Minecraft applies every number key of a tick before every use, all aimed where the crosshair was before the first one. A fast *anchor → glowstone → detonate* can charge with the wrong item, aim the glowstone at the floor under your new anchor, or spend a click on an anchor that is already exploding. KoHs Anchor's keeps the order and the target you gave it, sends them in the shape Vanilla sends them, and never adds a click of its own.

## Always on

- **Pressed order**: number keys and uses in one tick are applied in the order you pressed them.
- **Vanilla's tick shape**: one slot change per tick, before that tick's clicks, like Vanilla. 0 alerts against Grim in its strict configuration in our lab.
- **Fresh target**: after you switch items, the next use aims at what your crosshair hits now.
- **Instant chain**: a click on the anchor you just detonated goes out at once, as Vanilla sends it, and your world follows the server at that block, so no click is lost and no ghost block is left behind, whatever your ping.
- **No stacked anchors**: a double click with the same item joins the first one.
- **Instant detonation effects**: the explosion's sound and flash play the moment you press.

## Detonation

- **Anchor fade, 5 styles**: the anchor you detonate leaves with an animation instead of vanishing in one frame. *Ghost* rises into light, *Sink* shrinks into the ground, *Shatter* breaks into flying pieces, *Disintegrate* is burned away by a cut of light, *Glitch* tears and shuts off like an old screen. The menu's preview plays each one.
- **Explosion smoke**: Vanilla, Light (one small puff) or None, so the smoke stops hiding the other player in a chain. Debris particles can be turned off too.
- **Safe anchor view** (off by default): when your anchor is charged, a square in the colour you choose blinks on the ground where one block would cover you from its blast. It uses Vanilla's own explosion rays, your armour and the difficulty, and marks nothing when a block would not help. It only draws a hint: you still place the block yourself.

## Look and feel

- **Glow**: charged anchors give off light in your skin's own colours and light up the blocks around them. Quality presets keep it light on FPS.
- **Skins**: recolour the anchor's two layers or paint it pixel by pixel in the workshop.
- **Enemy anchors**: anchors other players placed get their own glow colour and skin, so you always know whose anchor is whose.
- **Sounds**: pick any charge and explosion sound the game knows, with its own volume and pitch.

## A menu with a mascot

- **Options that unfold**: switch an option on and its own settings come out of it; switch it off and they fold away.
- Every option explains itself, and the ones with a trade-off show a short window first.
- **Zymekoh**, a little cat from the KoHs mods, lives on the menu's floor: pet her, carry her, throw her. She only eats the preview's anchor (it comes back), and now and then she blows up an anchor of her own.

## Works with

- **Herzium**: reads and changes Herzium's hotbar order and talks to it so both agree on every key.
- **KoHs Crystal Tweaks**: one click takes its colours and glow for your anchors.
- **KoHs Anchors Bridge** (optional server plugin): server-side options that only turn on where the server's admin allows them.
- **Mod Menu**: opens the settings.

## Fair play

Every action is your own click: nothing is automated, nothing is aimed for you, and no packet is sent that Vanilla would not send. Each server's own rules still come first.

## Versions

Fabric, client only: Minecraft 1.21.11, 26.1, 26.1.1, 26.1.2, 26.2 and 26.3. Fabric API, Mod Menu, Herzium and KoHs Crystal Tweaks are optional. Open source under the MIT license.
