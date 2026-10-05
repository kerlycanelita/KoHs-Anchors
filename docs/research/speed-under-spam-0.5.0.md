# Speed under spam (0.5.0)

Does anything stick when anchors are placed, charged and detonated faster than the server answers?
Measured on 2026-10-05 in the KoHs Anchor lab: a local 26.2 server with Grim Anticheat 2.3.74, the
player's real setup (use on ".", the pvp kit, slots 4 2 1, Herzium 1.10.5 in last-input order) and
Ping Equalizer adding 0 to 150 ms. 20 cycles per bench. Runs: `KoHs-Debug-Tools/anchors-debug/testlab/results/speed-0.5.0-core`
and, for comparison, `speed-baseline-0.4.0`.

| Bench | What it presses | 0 ms | 50 ms | 100 ms | 150 ms |
| --- | --- | --- | --- | --- | --- |
| triple 150 | one action per client tick: item key, use, next item key | 100 % | 100 % | 100 % | — |
| spam 30 + 4 | place, charge, detonate 30 ms apart, then 4 clicks on the exploding anchor | 100 % | 100 % | 100 % | 100 % |
| spam 10 + 6 | the same 10 ms apart, 6 extra clicks | 100 % | 100 % | 100 % | 100 % |
| adaptive 0 | place, charge and detonate inside one client tick | 100 % | 100 % | 100 % | — |
| fixed 20 | a click every 20 ms, whatever happens | 50 % | 35 % | 30 % | 30 % |

- No click wasted and no Grim alert in any human-paced bench, at any latency.
- A click every 20 ms (50 presses a second, sustained) is faster than one slot change per tick can
  follow: cycles get out of step and some uses go out with the wrong item. 0.4.0 does the same
  (45 %, 35 %, 30 %): it is the limit of Vanilla's tick, not something the new code added.
- At 0 ms the spam benches put 2 to 7 of 40 anchors off the spot (0.4.0: 2 and 7, 0.5.0: 3 and 5);
  from 50 ms on, none. Worth a look on a LAN; on a real server the removal never arrives inside the
  same tick.
- The server sees the same timings as 0.4.0 (time to explosion within a few ms); what changed is on
  the screen: a held click's anchor or charge is drawn when it is pressed.
