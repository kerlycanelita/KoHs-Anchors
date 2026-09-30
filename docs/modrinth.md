![Charged anchors glowing at night: yours in violet, an enemy's in red](https://raw.githubusercontent.com/kerlycanelita/KoHs-Anchors/main/docs/images/gallery-glow-world.jpg)

# KoHs Anchor's

**Respawn anchor and glowstone clicks land in the order you pressed them, on the block you are
actually looking at. And your anchors glow the way you want them to.**

Minecraft applies every number key of a tick before every use, all aimed where the crosshair was
before the first one. A fast *anchor → glowstone → detonate* can charge with the wrong item, aim the
glowstone at the floor under your new anchor, or spend a click on an anchor that is already
exploding. KoHs Anchor's keeps the order and the target you gave it, sends them in the shape Vanilla
sends them, and never adds a click of its own.

## Always on

- **Pressed order**: number keys and uses in one tick are applied in the order you pressed them.
- **Vanilla's tick shape**: one slot change per tick, before that tick's clicks, like Vanilla. 0
  alerts against Grim in its strict configuration.
- **Fresh target**: after you switch items, the next use aims at what your crosshair hits now.
- **Early clicks wait**: a click on an anchor you just detonated lands the moment the server frees
  the block, instead of being lost on the old anchor.
- **No stacked anchors**: a double click with the same item joins the first one.
- **Instant detonation effects**: the explosion's sound and flash the moment you press use.

![The anchor workshop](https://raw.githubusercontent.com/kerlycanelita/KoHs-Anchors/main/docs/images/gallery-anchor-workshop.png)

## Make it yours

- **Anchor custom**: colour the frame and the glow, give each charge light its own colour, or paint
  the anchor pixel by pixel. Syncs with **KoHs Crystal Tweaks** colours in one click.
- **Glow anchors**: charged anchors shine, bloom and light the floor, walls and pillars around them.
  Walls hide the light, and three qualities keep it cheap.
- **Enemy anchors**: anchors other players placed glow in their own colour, with a side of the
  settings of their own (and a battle between your anchors and theirs while its advanced options
  are coming).
- **Sounds**: pick the charge and explosion sounds, with volume and pitch.
- **Detonation**: hide the anchor you detonate at once (with its light), and choose the explosion's
  debris and smoke.
- **Debounce and glowstone guard**: stop double clicks from placing two anchors, and glowstone from
  going down as a block in a fight (off by default: it rules out the safe anchor).

![Herzium's orders explained](https://raw.githubusercontent.com/kerlycanelita/KoHs-Anchors/main/docs/images/gallery-herzium.png)

## Herzium

With [Herzium](https://modrinth.com/mod/herzium), its tab shows Herzium's own hotbar order and
recommends **last input** for anchors. *Better communication with Herzium orders* keeps Herzium's
hotbar preview on the item a burst is really using.

## Fair play

Every action is a press you made. The mod never creates, repeats or times a click for you, never
selects a slot you did not press, never writes a packet of its own, never extends reach and never
changes a block in your world. The server decides every explosion.

Measured in the KoHs Anchor lab against a Grim Anticheat server with up to +150 ms of added latency:
**100 % of anchors, no lost click and no Grim alert**. The write-ups are
[on GitHub](https://github.com/kerlycanelita/KoHs-Anchors/tree/main/docs).

### Advanced · not secure

Two options, off by default, change *when* your clicks reach the server: **No-wait chain** and
**Instant detonation click**. Anticheats may flag them and many servers ban them. They take two
warnings and only act in singleplayer, on your local network and on servers you allow one by one.
**Check your server's rules.**

## Compatibility

- Client-side only. Fabric API is not required. [Mod Menu](https://modrinth.com/mod/modmenu) opens
  the settings.
- Minecraft 1.21.11, 26.1, 26.1.1, 26.1.2, 26.2 and 26.3 (one jar per version). Every one was
  played in the KoHs Anchor lab before release, in singleplayer and against a dedicated server with
  added latency; the Grim Anticheat measurements are from 26.2.
- Works with Herzium, KoHs Crystal Tweaks and Sodium. Do not combine it with another mod that
  reorders or holds anchor clicks.

## Links

- [Source code and documentation](https://github.com/kerlycanelita/KoHs-Anchors)
- [Report a problem](https://github.com/kerlycanelita/KoHs-Anchors/issues)
- [Discord](https://discord.gg/9t2VxEF7UU)
- [KoHs Mod Suite](https://kerlycanelita.github.io/KoHs-Mod-Suite/)

Made by **Zymekoh** (a.k.a. Kohzemyora). KoHs on top.
