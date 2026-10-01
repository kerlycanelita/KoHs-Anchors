# KoHs Anchor's: legitimacy audit

For players and server admins: what every feature changes, what it sends to the server, and how
that was measured. The mod's source is all here, and in developer mode the settings show the code
and the Mixins behind every option.

## The rule

- **Every action is a press the player made.** The mod never aims, places, charges, detonates,
  repeats or times a click for the player. It never selects a slot that was not pressed.
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
| Fresh target | After a slot change, the next use aims at what the crosshair hits now | Vanilla's use packet | Input fix |
| Early clicks wait | A click on an anchor that just exploded is held until the server frees the block | Vanilla's packet, later, never sooner | Input fix |
| No stacked anchors | A double press with the same item joins the first | Fewer packets | Input fix |
| Anchor and glowstone debounce, glowstone guard (off by default) | The player's own repeated or misplaced clicks are refused | Fewer packets | Input fix |
| Instant detonation effects | The explosion's sound and flash play at the press | Nothing | Cosmetic |
| Hide the detonated anchor, debris, smoke | Drawn on the client until the server confirms | Nothing | Cosmetic |
| Anchor skin, glow, enemy colours, sounds | Drawn and played on the client only | Nothing | Cosmetic |
| Herzium link | Talks to Herzium inside the same client | Nothing | Client only |
| Server bridge | Says hello on the `kohs_anchors:bridge` plugin channel, only if the server registered it | One hello, the features in use, a ping a second | Server-supported |
| Better glow enemy anchors | The server's bridge says whether each anchor is yours or someone else's | Nothing more | Server-supported |
| Anchor chain: no-wait chain, instant detonation click | Changes **when** clicks reach the server | Vanilla's packets, sooner or between ticks | Server-supported, opt-in |

## The anchor chain

The no-wait chain sends clicks on an exploding anchor without waiting for the server to remove it;
the instant detonation click sends a detonation the moment it is pressed. Both change when clicks
arrive, which an anticheat can read as an impossible placement or packet order. So they **only act
where the server's bridge allows them**:

- Off by default in the mod, and off by default in the bridge's `config.yml`
  (`anchor-chain.enabled: false`). The server's admin turns them on.
- Never on a server without the bridge: the player cannot allow a server, only its admin can.
- In singleplayer the world is the player's own and they are allowed.
- With Grim installed and the chain allowed, the bridge tells Grim through its API that the chain is
  allowed: a flag of the listed checks (by default `AirLiquidPlace`, `PacketOrderE`, `MultiPlace`) is
  let through only for a player using the chain, and only within 300 ms after that player acted on a
  respawn anchor. Every other check and every other moment stay Grim's. The admin can change or turn
  this off (`anchor-chain.grim-cooperation`).

## What the bridge never does

It never changes a block, an item, an attack, a movement or a rule of the game, and it never answers
for a server that does not have it. On a proxy (Velocity, BungeeCord) it only passes the channel
through and can turn options off for the whole network. The plugin's source:
[KoHs Anchor's Bridge](https://github.com/kerlycanelita/KoHs-Anchors-Bridge).
