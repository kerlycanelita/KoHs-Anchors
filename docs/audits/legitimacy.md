# KoHs Anchor's: legitimacy audit

For players and server admins: what every feature changes, what it sends to the server, and how
that was measured. The mod's source is all here, and in developer mode the settings show the code
and the Mixins behind every option.

## The rule

- **Every action is a press the player made.** The mod never aims, places, charges, detonates or
  repeats a click for the player, never sends one sooner than it was pressed, and never selects a
  slot that was not pressed. A click can wait: for the block it was aimed at, or one tick for the
  number key pressed with it.
- **Without a server's bridge, the server sees Vanilla.** The same packet types, one per press, in
  Vanilla's order and tick shape: one hotbar change per tick, before that tick's clicks. No reach,
  movement, rotation or combat change, and no packet of the mod's own.
- **Measured, not assumed.** In the KoHs Anchor lab (a 26.2 server with Grim Anticheat in its strict
  configuration, 0 to +150 ms of added latency), anchor bursts land 100 % of the time with
  **0 Grim alerts**. Every version from 1.21.11 to 26.3 was played in the lab before release. The
  write-ups: [anchors-0.4.0](../research/anchors-0.4.0.md),
  [anchors-and-herzium-0.2.1](../research/anchors-and-herzium-0.2.1.md),
  [server-lab-0.2.0](../research/server-lab-0.2.0.md).

A server's own rules still come first: some forbid every client mod. This audit is about what the mod
does, so that admins can judge it.

## Feature by feature

| Feature | What changes | What the server receives | Class |
| --- | --- | --- | --- |
| Pressed order | Number keys and uses in one tick are applied in the order they were pressed | Vanilla's packets, Vanilla's per-tick shape | Input fix |
| A click goes with its key | A click pressed up to 30 ms before a number key, with an item that has had its click or cannot be used, is applied after that key; with a tick between the two, a click that could only be wasted waits that one tick for the key | Vanilla's packets and tick shape: the slot change, then the click, in one tick. Nothing sooner, nothing added | Input fix |
| Fresh target | After a slot change, the next use aims at what the crosshair hits now | Vanilla's use packet | Input fix |
| Immediate chain | A click on an anchor that is still exploding goes out when pressed, at the old anchor, as Vanilla sends it; its outcome is drawn at once | Vanilla's packet, at Vanilla's moment | Input fix |
| Anchor block follows the server | The server's state of a chained anchor's block is shown when it arrives, not after Vanilla's acknowledgement | Nothing; no click goes to a block the server already removed | Client world sync |
| No ghost blocks | A chained click places nothing in the client world; a click through a drawn anchor the world does not have yet waits for it (at most 0.7 s) | Vanilla's packet, the same or later | Input fix |
| No stacked anchors | A double press with the same item joins the first; an anchor on your own uncharged anchor is dropped unless you sneak | Fewer packets | Input fix |
| Anchor and glowstone debounce, glowstone guard (off by default) | The player's own repeated or misplaced clicks are refused | Fewer packets | Input fix |
| Instant detonation effects | The explosion's sound and flash play at the press | Nothing | Cosmetic |
| Anchor fade (five styles), enemy skin, amethyst wall | Drawn on the client only | Nothing | Cosmetic |
| Safe anchor view (off by default) | A blinking square where one block would cover the player, worked out from what the client already shows | Nothing: the player places the block | Information, opt-in |
| Hide the detonated anchor, debris, smoke | Drawn on the client until the server confirms | Nothing | Cosmetic |
| Anchor skin, glow, enemy colours, sounds | Drawn and played on the client only | Nothing | Cosmetic |
| Herzium link | Talks to Herzium inside the same client | Nothing | Client only |
| Server bridge | Says hello on the `kohs_anchors:bridge` plugin channel, only if the server registered it | One hello, the features in use, a ping a second | Server-supported |
| Better glow enemy anchors | The server's bridge says whether each anchor is yours or someone else's | Nothing more | Server-supported |
| Instant detonation click | Changes **when** a detonation reaches the server | Vanilla's packet, between ticks | Server-supported, opt-in |

## A click goes with its key

Inside a tick, Vanilla applies the number keys before the clicks, so a click that lands a moment
before its key still uses the key's item. Pressed order took that away: the click ran with the item
of the step before. From 0.6.1 a click pressed up to 30 ms before a number key is applied after it
when the item in hand has already had its click, or cannot be used at all (a sword, a mace, a totem,
an empty hand), which is what Vanilla does with the same presses. The first click with an item that
can be used is never moved.

When a tick falls between the click and its key, Vanilla runs the click alone, with the old item.
Here that click waits one tick, and only when it could do nothing an anchor loop wants: glowstone on
an anchor that has a charge, an anchor on one that has none, or, within a second of a click on an
anchor, glowstone on another block or a totem, a sword, a mace or an empty hand where no charged
anchor is. Glowstone placed as a block away from anchors never waits. On the next tick it runs after
the key pressed within 30 ms of it, or as it was pressed. This is the one place where the mod is more
forgiving than Vanilla, and it never is faster: the click goes out in the tick of its key, where a
click pressed after the key would have gone.
[click-before-key-0.6.1](../research/click-before-key-0.6.1.md)

## The immediate chain is Vanilla's timing

A Vanilla player who keeps clicking an anchor that is exploding sends those clicks at once, at the old
anchor, and the server places the next anchor in its place. KoHs Anchor's sends the same clicks at the
same moment. What it changes is what the client shows, so that the next click is aimed right: the
old anchor's block follows the server as soon as its answer arrives, instead of keeping the player's
own prediction until Vanilla's acknowledgement. That window is where Vanilla players get Grim's
`AirLiquidPlace` (a click at a block the client has already been told is air); with the block
following the server it does not exist. Measured in the crater bench of the anchor lab (stone ground
that each explosion breaks, Grim, 0 to +150 ms): 0 Grim alerts in every bench.
[core-chain-0.5.0](../research/core-chain-0.5.0.md)

## The instant detonation click

It sends a detonation the moment it is pressed, between client ticks. That changes when the click
arrives, which an anticheat can read as an impossible packet order. So it **only acts where the
server's bridge allows it**:

- Off by default in the mod, and off by default in the bridge's `config.yml`
  (`anchor-chain.enabled: false`). The server's admin turns them on.
- Never on a server without the bridge: the player cannot allow a server, only its admin can.
- In singleplayer the world is the player's own and they are allowed.
- With Grim installed and it allowed, the bridge tells Grim through its API that it is allowed: a
  flag of the listed checks (by default `AirLiquidPlace`, `PacketOrderE`, `MultiPlace`) is let
  through only for a player using it, and only within 300 ms after that player acted on a
  respawn anchor. Every other check and every other moment stay Grim's. The admin can change or turn
  this off (`anchor-chain.grim-cooperation`).

## What the bridge never does

It never changes a block, an item, an attack, a movement or a rule of the game, and it never answers
for a server that does not have it. On a proxy (Velocity, BungeeCord) it only passes the channel
through and can turn options off for the whole network. The plugin's source:
[KoHs Anchor's Bridge](https://github.com/kerlycanelita/KoHs-Anchors-Bridge).
