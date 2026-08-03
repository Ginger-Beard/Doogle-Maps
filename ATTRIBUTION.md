# Attribution

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

Route planning is a planned feature and will delegate navigation to the
[Shortest Path](https://github.com/Skretzo/shortest-path) plugin as a soft dependency,
following the integration pattern used by Quest Helper and Shortest Clue. No pathfinding
of our own.

## OSRS Wiki

Crop growth times, seed level requirements and protection payments are cross-checked
against the OSRS Wiki's Farming pages.
