# Attribution

## The name

"Doogle Maps" was coined by **Sitta mango**, from the author's clan — a play on the OSRS
*doogle leaves*.

## RuneLite core — Time Tracking (farming)

The farming data spine in `com.dooglemaps.data` is mirrored from RuneLite core's
`net.runelite.client.plugins.timetracking.farming` package: the patch locations and state
varbits (`FarmingWorld`, `FarmingRegion`, `FarmingPatch`), the varbit-to-crop decoding
(`PatchImplementation`), and the per-crop growth timings (`Produce`). The growth-tick grid
arithmetic in `GrowthTimer` follows the same approach as `FarmingTracker`.

Those classes are package-private, so an external Plugin Hub plugin cannot call them; the
data is carried here instead. `tools/generate_farming_data.py` regenerates it from the
client sources jar rather than transcribing it by hand, so a RuneLite update can be picked
up without a chance of typos.

The farming contract (`ContractState`, `ContractCapture`) follows the same package's
`FarmingContractManager`. Its config location — group `timetracking`, key `contract`,
holding the harvested item's id — is read rather than duplicated, the same arrangement and
for the same reason as the compost and payment backfill below. What *is* mirrored is the
three lines the game uses to announce a contract: the assignment pattern, the reward line,
and `TimeTrackingPlugin`'s completion message. Those are facts about the game rather than
code, and they are matched here because the config key alone cannot express the state
between a contract completing and its reward being collected — Time Tracking clears the key
on the completion message, so a grown contract is otherwise indistinguishable from none.
Guildmaster Jane's NPC id and the Farming Guild's region id come from the same class.

Original work is Copyright (c) 2018 Abex and the RuneLite contributors, licensed under the
BSD 2-Clause Licence:

```
Redistribution and use in source and binary forms, with or without modification, are
permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of
   conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice, this list of
   conditions and the following disclaimer in the documentation and/or other materials
   provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR
TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## Shortest Path

Route planning delegates all navigation to the
[Shortest Path](https://github.com/Skretzo/shortest-path) plugin (Runemoro, Skretzo,
FIrgolitsch, wvanderp), as a soft dependency. No pathfinding of our own.

Integration is over its documented `PluginMessage` API in the `shortestpath` namespace —
`path` with a target set, `clear`, per-request config overrides, and the `transports` event
it posts back. Handing it a *set* of targets and letting it pick the cheapest is what
supplies the visiting order for a run.

`BankLocations` is derived from that project's
`src/main/resources/destinations/game_features/bank.tsv`, filtered to the banks with no
skill, quest or varbit requirement. Licensed BSD 2-Clause.

## OSRS Wiki

Crop growth times, seed level requirements and protection payments are cross-checked
against the OSRS Wiki's Farming pages.

Two tables are scraped from it outright and kept in `tools/` alongside the code that turns
them into Java:

- `tools/crop-xp.tsv` — Farming experience per seed, from the `<Type> patch/Seeds` subpages.
- `tools/crop-yield.tsv` — chance-to-save constants, from the `{{Farming yield calculator}}`
  template on each seed's page, which in turn cites figures Mod Easty gave the wiki and
  which are reproduced on [Talk:Farming](https://oldschool.runescape.wiki/w/Talk:Farming).
  Huasca is the one crop with no such page; its constants come from the wiki's
  `Module:Herb Farming calculator`.

The yield formula in `YieldEstimate` follows the Farming article's "Variable crop yield"
section, including the ordering of boosts that Mod Ash confirmed on Twitter.

Wiki content is available under CC BY-NC-SA 3.0.

### Gardener chatheads

`src/main/resources/com/dooglemaps/chatheads/` holds one portrait per gardener who protects
a farming patch, so a protected patch can show the face of whoever was actually paid. They
are fetched by `tools/fetch_chatheads.py`, which resolves each NPC id against the wiki and
records what it took in `tools/chatheads.tsv` — nothing here is hand-maintained.

These are bundled rather than rendered because they cannot be rendered: chatheads are 3D
model renders, and while the RuneLite API exposes `NPCComposition.getChatheadModels()` and
`Client.loadModel`, it has nothing that rasterises a `Model` to an image.

The underlying artwork is Jagex's, hosted by the wiki. Forty-eight of the forty-nine
gardeners have one; the Tortugan who tends the coral patch has no wiki page yet, and the
plugin falls back to a plain shield for them.

## Quest Helper

[Quest Helper](https://github.com/Zoinkwiz/quest-helper) by Zoinkwiz, BSD 2-Clause.

No source is copied. What was taken is a **fact**: its `PaymentTracker` reads whether a farmer has
been paid from RuneLite's own Time Tracking config, at
`timetracking` / `<regionId>.<varbit>.protected`. That key — and the matching `.compost` one — is
what `TimeTrackingState` reads, and it answers the one question this plugin's own capture cannot,
namely what happened before it was installed.

Credited because that is where the approach came from, not because the licence compels it for a
config key. The run helpers themselves were looked at and deliberately not reused: they are built
on Quest Helper's own step and requirement framework, and their patch routing is a fixed region
order, which is the design this plugin explicitly rejected in favour of handing every outstanding
target to Shortest Path.
