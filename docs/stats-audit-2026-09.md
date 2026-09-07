# Doogle-Maps "Stats" tab — numerical audit
2026-09-06. Read-only. Sources: `~/.runelite/doogle-maps/harvests-G4NW-UgZ.csv` (1050 rows,
2026-08-04 → 2026-09-07), `$rsprofile--1.properties` keys `dooglemaps.rsprofile.G4NW-UgZ.{harvestStats,
diseaseStats,seeds,farmingLevel,farmingXp}`, and `~/.runelite/logs/client*.log`.

Every panel figure below was reproduced independently from the raw CSV/JSON with python
(scripts in this scratchpad: `load.py`, `decomposition.txt`). **The panel's arithmetic is
correct in almost every case — it faithfully computes the wrong quantity.** The defects are in
the *inputs* (capture) and in the *model families*, not in the formulas.

---

## Summary table

| # | Metric | Shown | Recomputed | Verdict | Fix |
|---|--------|-------|-----------|---------|-----|
| 1 | Luck percentiles (Straw/Melon/Avantoe/Ranarr/Snap 99th, Hemp 80th) | 99th ×5, 80th ×1 | 98 / 42 / 40 / 99 / 92 / 22 once patches recorded `compost=NONE` are scored at their real 6 lives | **Wrong — measures capture error, not luck** | Fix the compost tag (P1); until then hide percentiles for any crop with `NONE`-tagged patches |
| 2 | "1441 items over expectation" | 1441 | 1440.8 ✔ arithmetic; but +868 is wrong model family, +535 is missed compost, ~+250 is merged records, **genuine luck ≈ 0** | **Wrong (misleading)** | Exclude crops whose yield family is unmodelled; drop or re-base the total |
| 2b | "1965 items came from patches left standing" | 1965 | 1965 ✔ arithmetic; **1412 of them are 3 phantom Limpwurt records (238/266/908 roots, 0 xp) at the Farming Guild** | **Wrong** | Discard incomplete records with 0 xp and/or implausible counts |
| 3 | "Ultracompost gave you 7.3 limpwurt a patch against 6.0 untreated, over 89 patches" | 7.3 vs 6.0 | 7.33 (n=60, mean lvl 85.3) vs 6.03 (n=29, mean lvl 84.9) ✔ arithmetic | **Wrong — compost provably cannot affect limpwurt yield** (`LEVEL_ROLL_BASE`, wiki) | Restrict the note to `respondsToCompost(seed)` crops, require n≥20 per arm, print a CI |
| 4 | 63 runs, 16.6 patches, 11.3k xp each, 21.2k xp/day, 19.5k xp/h active, "34 more runs to 91" | as shown | 63 / 16.7 / 11.28k / 21.2k / 19.4k / 33 — **all reproduce exactly** | **Formulas correct, inputs 5× low.** Harvest log captures only **20 %** of farming xp (710k logged vs ~3.35M actually gained 4 Aug→6 Sep): tree/fruit-tree check-health is never recorded | Add check-health xp to the run clustering, or label every xp figure "harvest xp only". "34 runs to 91" should be **~7** |
| 5 | Expected: 45,824 seeds → 12.4M xp; 99 on 8768, other 37,035 | as shown | seeds owned 45,827 ✔; **8768 + 37035 = 45,803 ≠ 45,824** (table totals seeds *held*, the split totals seeds *planted*) | **Inconsistent**; also Belladonna 681k is ~6× too high | Derive both from the same counter; fix Belladonna yield |
| 5b | "91.9M gp of produce against 21.1M of seeds and compost, 70.8M net" | 91.9/21.1/70.8 | 91.9−21.1=70.8 ✔ internally; uses the same broken yields (Belladonna ×6.8, Calquat ×⅙, bushes ×⅓) and no protection cost | **Misleading** | Fix yields; say "produce" not "net", or charge protection |
| 6 | "You are getting 23% more than estimated" | 23 % | 7748/6307 = 1.2285 ✔ | **Correct arithmetic, wrong conclusion** — it is 23 % model error, mostly on crops the model has no formula for | Split into "crops with a published formula" vs "crops with a guess" |
| 6b | Level bands 80-89 n=907 got 13.4 pred 11.1; 90-99 n=5 | as shown | 907 / 13.43 / 11.14 exactly ✔ (n = *patches* picked clean with predicted>0, over the **whole CSV**, not the store window) | **Correct but uninterpretable** — a single band, mixing 34 crops, cannot show a level curve | Restrict to lives-family crops, one crop per line, or drop |
| 6c | "Disease: 2 of 136 growth cycles caught something, against a predicted 50. 2 died." | 2 / 136 / 50 / 2 | 136 cycles ✔, predicted diseased 50.13 ✔, 2 diseased ✔ (both Papaya). **93 of the 136 cycles are tagged `compost=NONE` and carry 47.9 of the 50.1** | **Wrong** — same missed-compost bug; plus regrow cycles double-counted (Maple 37 "cycles") | Fix compost tag (P1); exclude regrow HARVESTABLE re-entries |
| 7 | "618 patches over 3 weeks, about 184 a week" | 3 / 184 | span 3.357 weeks; 618/3.357 = 184.1 ✔ but 3 × 184 = 552 ≠ 618 | **Misleading rounding** | Round both from the same number, or say "3½ weeks" |
| 7b | Lifetime "618 patches, 9707 items, 383k xp, since 14 August" | as shown | store now 619/9713/383.4k ✔ (drift = harvests since the screenshot) | **Correct**, but items include the 1412 phantoms and xp is 25 % of real | see 2b / 4 |
| 7c | "Worth about 7.6M gp at today's prices" | 7.6M | not re-priced (GE cache offline) — prices the same inflated item totals | **Suspect** | recompute after 2b |
| 7d | Crops with n=1 in the luck table (Kwuarm, Torstol, Seaweed, Camphor, Magic) | shown with a `+/-` | correct as computed | **Noise** | hide `+/-` below ~5 patches, as the percentile already is |

---

## 1. Luck percentiles — the root cause

### Formula (correct)
- `CropHarvestStats.getSurplus()` = `items - predicted` — **src/main/java/com/dooglemaps/validate/CropHarvestStats.java:125**
- `CropHarvestStats.getLuckPercentile()` = `100·Φ(surplus / √predictedVariance)` — **CropHarvestStats.java:157**
- Gated by `hasLuckPercentile()`: `harvests ≥ 20 && variancePatches == harvests && predictedVariance > 0` — **CropHarvestStats.java:137**
- Clamped to [1,99] — **src/main/java/com/dooglemaps/ui/HarvestStatsPanel.java:736**
- Per-patch mean/variance are the negative binomial `r/(1−p)` and `r·p/(1−p)²` — **src/main/java/com/dooglemaps/timer/YieldEstimate.java:103,135**, with `p` from the published chance-to-save constants (**YieldEstimate.java:60**).

The statistics are textbook and the gate is well designed: it is exactly why Whiteberry (+449),
Limpwurt (+12), Calquat (+120), Palm (−25), Cactus (−47) and Poison ivy (+91) show `-` — those
crops have no `CropYield` entry, so `getPredictedVariance()` returns 0
(**src/main/java/com/dooglemaps/validate/HarvestRecord.java:255**) and `variancePatches` stays 0.
The gate is doing its job.

### Recomputation
I reimplemented `YieldEstimate.chanceToSave/expectedHarvest/harvestVariance` in python and
re-scored every completed CSV record from 2026-08-14 on. **My predictions equal the CSV's
`predicted` column to the tenth**, so the formula is not in question.

Split by the *recorded* compost tier:

```
crop         arm    n   mean actual   mean predicted
Avantoe      ULTRA  31      8.84           8.95      <- model is right
Avantoe      NONE   15      8.93           4.48      <- model is half
Watermelon   ULTRA  39     22.56          23.03
Watermelon   NONE   12     23.42          11.46
Strawberry   ULTRA  45     25.82          22.73
Strawberry   NONE   20     22.70          11.38
Hemp         ULTRA  16     21.19          22.23
Hemp         NONE    5     20.00          11.04
Ranarr       ULTRA  18      9.61           8.84
Ranarr       NONE    4     11.00           4.42
Snapdragon   ULTRA  16      9.50           9.02
Snapdragon   NONE    5     10.20           4.51
```

The two arms have **identical actual yields**. Avantoe's minimum over 46 patches is 6 — an
untreated (3-life) patch cannot exceed a low count often and can never be beaten below 3, and
6 is exactly the ultracomposted floor. Every `NONE` patch was in fact ultracomposted; the
plugin simply never saw the bucket.

**Re-scoring every lives-family patch at 6 lives:**

```
crop          n    got   panelPred  panelSurp   fixPred  fixSurp  panelPct  fixPct
Avantoe      46    408      344.6      +63.4     411.7     -3.7      100      40
Hemp         21    439      410.8      +28.2     466.0    -27.0       80      22
Watermelon   51   1161     1035.7     +125.3    1173.1    -12.1       99      42
Snapdragon   21    203      167.0      +36.0     189.5    +13.5      100      92
Ranarr       22    217      176.7      +40.3     194.3    +22.7      100      99
Strawberry   65   1616     1250.2     +365.8    1477.7   +138.3      100      98
Snape grass  15    546      486.2      +59.8     486.2    +59.8       90      90
TOTAL (16 lives-family crops)          +832.3             +250.0
```

Four of the six "99th" crops collapse to the middle of the distribution. **The percentile is
measuring how often the plugin failed to see a bucket of ultracompost.**

### Why the compost is missed (the actual bug)
`HarvestRecord`'s compost comes from `PatchSnapshot.getCompost()` at the moment the first item
lands (**src/main/java/com/dooglemaps/validate/HarvestLog.java:532**). That snapshot is only
populated when the plugin *watched* the bucket go in (`CompostCapture` →
`PatchStateStore.recordCompost`, **PatchStateStore.java:357**) or by the Time Tracking backfill —
and the backfill is gated:

```java
// src/main/java/com/dooglemaps/state/PatchStateStore.java:438
boolean protectable = snapshot.getCropState() == CropState.GROWING
    || snapshot.getCropState() == CropState.DISEASED;
...
CompostTier tier = timeTracking.compost(patch);
if (tier != null && protectable && snapshot.getCompost() == CompostTier.NONE ...)
```

A patch that was composted in a previous session and is **already HARVESTABLE when you log in**
never gets the fill — which is the normal herb-run shape. The plugin's own code disagrees with
itself here: `PatchStateStore.java:330-346` says compost must survive until the crop leaves,
but the backfill will not restore it once the crop is ripe.

The plugin has been telling you this for three weeks. `warnIfCompostWasMissed`
(**HarvestLog.java:752**) has fired **80 times** in the surviving client logs:

```
Watermelon 21, Avantoe 15, Strawberry 13, Hemp 9, Snape grass 5,
Snapdragon 5, Cotton 5, Ranarr 4, Yanillian 2, Flax 1
```

— i.e. precisely the crops that show a 99th percentile. It warns only when actual > 1.5 × predicted,
so 80 is a lower bound, and log rotation means it is a lower bound on a partial window.

### Second contamination: paired patches merge into one record
`HarvestLog.distanceTo` (**HarvestLog.java:617**) returns **0 for any patch in the same region**,
and `findPatchHolding` (**HarvestLog.java:562**) takes the first minimum. Every allotment location
has a North and a South patch in one region. Plant the same crop in both and *all* items from both
land in one record while the second patch never opens one. The signature is unmistakable:

```
Watermelon  Catherby South  ultra  actual 77  predicted 22.93  xp 4495
Strawberry  Kourend SW      none   actual 70  predicted 11.46  xp 2132
Snape grass Farming Guild N ultra  actual 72  predicted 32.68  xp 7717
Strawberry  Falador NW      ultra  actual 56  predicted 22.59  xp 1883
```

77 melons from 6 lives at p≈0.74 is z ≈ +7. The `actual_xp` matches the item count, so the items
are real — they just came from two patches booked as one. This is the whole of Strawberry's
remaining +138 and Snape grass's +60 after the compost fix, and it is why `best` reads 77.

### Verdict and fix
The percentile is **not salvageable as shown**. Options, in order of preference:

1. **Fix the input** (P1, below). After that the percentile is a legitimate statistic and the
   existing code needs no change.
2. Until then, **suppress the percentile for any crop whose completed patches include a
   `NONE`-tagged record on a compost-responsive crop.** That is one extra counter on
   `CropHarvestStats` (`untreatedPatches`) and one extra clause in `hasLuckPercentile()`
   (**CropHarvestStats.java:137**) — the same shape as the existing `variancePatches == harvests`
   guard, and the same rationale.
3. If percentiles are dropped, replace with **actual vs expected per crop with a 95 % interval**:
   `got 408, expected 412 (95% CI 372–452)` — same two numbers already stored, no distributional
   claim about the player, and it degrades honestly when the model is wrong (the interval simply
   fails to cover, which is a visible bug rather than a flattering one).

---

## 2. "Items over expectation" and "from patches left standing"

**Definitions in code.**
`describeLuck()` (**HarvestStatsPanel.java:476**) prints `stats.getTotalSurplus()` =
Σ over crops of `items − predicted`, restricted to crops with `predicted > 0`
(**HarvestStatsStore.java:215**), across `scored` = Σ `harvests` of those crops. Both correct.
`items`/`predicted` are **completed patches only**; `partialItems` is held separately
(**CropHarvestStats.java:72**).

**Arithmetic** — exact: my recompute gives 1440.8 against the shown 1441, over 619 patches
(shown 618 — one harvest since the screenshot), 1965 partial items.

**Full decomposition of the 1441** (all reproduced; totals to 1440.8):

| source | items |
|---|---|
| crops with the wrong yield *family* (Whiteberry +449, Calquat +120, Teak +101, Poison ivy +91, Jangerberry +34, Mushroom +30, Maple +24, Yew +17, Magic/Camphor +2) | **+868** |
| family errors the other way (Belladonna −87, Potato cactus −83, Cactus −47, Celastrus −26, Palm −25, Dragonfruit −5) | **−273** |
| lives-family crops, missed ultracompost | **+582** |
| lives-family crops, paired-patch merges | **≈ +250** |
| genuine luck | **≈ 0** |

The family errors are concrete and individually checkable:

- **Whiteberry / Poison ivy / Jangerberry (bushes)** — `CropYieldModel.expected` falls through to
  `fullStock()` = `harvestStages − 1` = 4 (**CropYieldModel.java:162**). The wiki: *"Herbs,
  allotments, hops, **bushes**, belladonna, **cacti**, limpwurt, celastrus and giant seaweed all
  produce varying amounts"* and a bush *"yields a minimum of four berries, with any extra berries
  depending on the player's Farming level."* 4 is the **floor**, not the mean. Observed 13.35/patch
  over 48 whiteberry — consistent with 4 lives and p ≈ 0.70. Compost correctly does nothing here
  (NONE arm 13.41, ULTRA arm 13.32 — the wiki confirms compost's yield half is allotments/hops/herbs
  only). `CropYield` (**src/main/java/com/dooglemaps/data/CropYield.java**) has no bush or cactus
  entries, so no chance-to-save constants exist to compute with.
- **Calquat** — predicted 1.0, observed **exactly 6 on all 24 patches**. `regrows()` is false for
  calquat so `fullStock` returns 1. Should be a fixed 6.
- **Mushroom** — predicted 1.0, observed exactly 6 on all 6.
- **Belladonna** — predicted 6.77 (`LEVEL_ROLL_BASE`, **CropYieldModel.java:70**), observed
  **exactly 1 on all 15 patches**, with 521 xp/patch which is one nightshade's harvest award. The
  Mod Ash quote in the javadoc is about limpwurt; extending it to belladonna is unevidenced and the
  data refutes it. (Limpwurt's own level-roll model is *excellent*: predicted 6.77, observed 6.91,
  max 11 at level 87 exactly as `3 + ⌊U(0,level−1)/10⌋` predicts. Keep it.)
- **Trees (Teak +101, Maple +24, Yew +17, Magic, Camphor)** — these records have **0 farming xp**
  and 1–38 items. They are **woodcutting logs from chopping the farmed tree**, counted as a farming
  harvest. `CropYieldModel.hasMeaningfulYield()` (**CropYieldModel.java:407**) already says TREE has
  no meaningful yield; `HarvestLog.write` (**HarvestLog.java:666**) never asks.
- **Cactus 10.0 / Potato cactus 17.5 / Celastrus 9.0** — the `EMPIRICAL` constants
  (**CropYieldModel.java:72-74**) are the wiki's averages *measured at level 99 with cape,
  secateurs and ultracompost*; this account is 82–90 without a cape, so they read 25–33 % high
  (observed 7.53 / 12.00 / 5.29). The code's own javadoc says exactly this. They should not be
  entering a surplus total at all.

### "1965 items came from patches left standing"
Arithmetic correct (`getTotalPartialItems`, **HarvestStatsStore.java:225**). The **content is not**:

```
Limpwurt  Farming Guild  ultra  actual 908  predicted 6.91  xp 0    <- incomplete
Limpwurt  Farming Guild  ultra  actual 266  predicted 6.91  xp 0    <- incomplete
Limpwurt  Farming Guild  ultra  actual 238  predicted 6.91  xp 0    <- incomplete
Snape gr. Farming Guild S ultra actual 56/42/42/28          xp 0    <- incomplete
```

**1412 of the 1965 are three Limpwurt records with zero farming experience.** No xp means no picks
happened: these are inventory arrivals (a bank withdrawal of limpwurt roots at the Farming Guild)
credited to a record left open by `IDLE_TICKS_BEFORE_ABANDON = 100` (**HarvestLog.java:86**, one
minute of game ticks) while the patch was still HARVESTABLE. Another ~170 are the same shape on
snape grass. The panel calls this "the one actionable line on the tab" and tells the player to go
back to patches that were picked clean.

**Fix:** in `HarvestLog.write` (**HarvestLog.java:666**), refuse to record an **incomplete** record
whose `xpGained == 0` for a crop where `CropXp.getHarvestXp() > 0` — no experience means no harvest.
Cheap, safe, and it also kills the tree-log records. Also worth capping `credit()`
(**HarvestLog.java:532**) to a single inventory delta of plausible size.

---

## 3. Compost comparison

**Code:** `describeCompost()` (**HarvestStatsPanel.java:551**) walks `tiersByCrop()`, picks the crop
with the **most patches farmed under more than one tier**, and reports its best-average tier
against its worst-average tier.

**Recomputed:** Limpwurt wins (89 patches over two tiers).
`ULTRACOMPOST n=60, mean 7.33, mean level 85.32` vs `NONE n=29, mean 6.03, mean level 84.9`. The
panel's 7.3 / 6.0 / 89 are all exact.

**Verdict: wrong.**
1. **Compost cannot change limpwurt yield.** Limpwurt is in `LEVEL_ROLL_BASE`
   (**CropYieldModel.java:69**) — *"Compost does nothing here"*, and `respondsToCompost(LIMPWURT)`
   returns false (**CropYieldModel.java:212**). The plugin's own model predicts 6.75 for the NONE
   arm and 6.78 for the ULTRA arm. The panel presents an impossible causal claim.
2. **Level is not the confounder** you would expect — the two arms are level 84.9 vs 85.3, so the
   level roll accounts for ~0.03 of the 1.30 gap.
3. The gap is 2.5σ (per-patch sd ≈ 2.29, combined SE ≈ 0.52). Either chance, or the `NONE` arm is a
   biased subset of patches/regions. It is not compost.
4. **"Untreated" is really "unknown"** everywhere on this tab — see §1. The NONE arm is not a control
   group.

**Fix:** in `describeCompost()`, (a) require `CropYieldModel.respondsToCompost(seed)` for the crop,
(b) require ≥20 completed patches in *each* arm, (c) print a confidence interval or nothing,
(d) rename the untreated arm to "not recorded" until the compost tag is trustworthy.

---

## 4. Runs

**Definitions.** A run is a timestamp cluster with a 30-minute gap (`RUN_GAP`,
**HarvestHistory.java:64**; `fold()`, **HarvestHistory.java:296**), built from the **whole CSV**
(4 Aug on) while every other section reads the store (14 Aug on). `FarmRun.getDuration()` is
first-patch-to-last-patch (**FarmRun.java:79**).

**Recomputed — every figure reproduces exactly:**

| shown | recomputed |
|---|---|
| 63 runs | 63 |
| 11.3k xp each | 11,277 |
| average 16.6 / 239 / 11.3k | 16.7 / 239.2 / 11,277 |
| best 54 / 746 / 56.2k | 54 / 746 / 56,202 (over 141 min) |
| 21.2k xp a day | 21,211 (span 33.49 d) |
| 19.5k xp/hour active | 19,402 over 36.5 h across 54 runs |
| 34 more runs to 91 | ⌈368,063 / 11,277⌉ = 33 |

So the code is right. **The input is 5× too small.**

- Farming level was 80 on 2026-08-04 (xp ≥ 1,986,068) and is 90 today (xp 5,534,768). Real farming
  xp gained over the log's span: **≈ 3.35M**.
- Total xp in the harvest log over the same span: **710,425**.
- **The harvest log captures 20 % of farming experience.** Everything else is tree and fruit-tree
  **check-health** (a yew check is 7,069 xp, mahogany 15,720, dragonfruit 17,335, palm 10,150) plus
  sapling planting — none of which opens a `HarvestRecord`. Confirmed directly: the Teak, Maple, Yew,
  Magic and Camphor rows in `harvestStats` carry `"xp":0.0`.

Consequences on the panel:
- "11.3k xp each" → real ≈ **53k**.
- "21.2k xp a day" → real ≈ **100k**.
- "19.5k xp an hour while you are actually farming" → real ≈ **92k/h** — and the denominator is
  first-pick-to-last-pick, which excludes banking and travel, so even that is optimistic.
- **"About 34 more runs to 91" is the worst line on the tab: the honest answer is about 7.**
  (368,063 xp to 91 ÷ 53.2k real xp per run.) `describeRunsToNextLevel()`
  (**HarvestStatsPanel.java:863**) divides a *real* xp requirement by a *partial* xp rate.

Secondary run issues:
- 9 of the 63 runs have zero duration (single patch, or all in one second). They are counted in
  `patches/run` and `xp/run` but excluded from the active-hour rate — three different denominators
  across three lines in the same section.
- The "best run" spans 141 minutes; a 30-minute gap does not separate a tree run from the herb run
  after it. Fine as a session, mislabelled as a run.
- `HarvestHistory.record()` (**HarvestHistory.java:283**) stamps live harvests with `Instant.now()`
  rather than the record's own start, so a long harvest clusters on its end time. Harmless at a
  30-minute gap.

**Fix (highest value on the tab):** subscribe to `StatChanged` for FARMING in `HarvestHistory` (or
have `HarvestLog` forward unattributed farming xp) and fold *all* farming xp into the run clusters,
not only what a `HarvestRecord` claimed. Until then, relabel every figure in the section
"harvest xp" and delete `describeRunsToNextLevel()` — a 5× wrong ETA is worse than none.

---

## 5. Expected / "planting it all out"

**Code:** `PlantOutEstimate.of()` (**src/main/java/com/dooglemaps/timer/PlantOutEstimate.java:173**)
— a forward simulation: fill every available patch type with the best xp-per-patch plantable crop,
bank the xp, recompute the level, repeat. Yield per patch is `CropYieldModel.expected`
(**PlantOutEstimate.java:260**); xp is `CropXp.totalFor(xpHarvestsFor(seed, yield))` with the outfit
applied last (**PlantOutEstimate.java:398**). Ordering is *best xp per patch first*
(**PlantOutEstimate.java:355**), and the table is sorted by xp per **seed** (**:433**).

**Recomputed / checked:**
- Seeds owned across BANK + SEED_VAULT + SEED_BOX = **45,827**; panel total 45,824. ✔ (the 3-seed
  gap is `stock()` dropping stacks that cannot fill a patch, **PlantOutEstimate.java:296**).
- Every table row is internally consistent: `n × each = xp` to the displayed precision
  (Camphor 6 × 18.4k = 110k, Calquat 40 × 12.6k = 504k, Belladonna 173 × 3.9k = 675k, …). ✔
- **`8768 + 37035 = 45,803`, but the table totals `45,824`.** `seedsToMaxLevel` /
  `seedsBeyondMaxLevel` come from `seedsPlanted` (**PlantOutEstimate.java:268**), which counts seeds
  *consumed by whole patches*; the table's total sums `line.getSeeds()` = seeds *held*
  (**HarvestStatsPanel.java:906**). 21 orphan seeds that cannot fill a patch appear in one total and
  not the other, in the same paragraph. **Bug.**
- Level-99 ordering: **not** highest-xp-per-seed first. The simulation plants highest **xp per
  patch** in each cycle (**PlantOutEstimate.java:355**), which is the right policy for maximising xp
  but is not what the table's ordering implies. Worth a sentence.
- "12.4M vs 12M staying at 90": `xpAtStartLevel` (**PlantOutEstimate.java:422**) scores at 0 any crop
  whose level requirement is above the start level. At level 90 almost nothing is locked, so the two
  figures differ only by the chance-to-save curve from 90→99 — 3 %, which is right. ✔
- **Yields are the same broken model as §2.** The material error is **Belladonna: 173 seeds ×
  ~3.9k = 681k xp**, built on a predicted yield of 6.77 nightshade per patch. Observed is 1.00 on
  15/15 patches at 521 xp/patch, so the honest figure is **≈ 107k** — the panel's 12.4M is
  **~575k too high** on this one crop. Calquat, mushrooms and bushes err the other way but by less
  (tree/fruit-tree lines are dominated by check-health xp, which is yield-independent and correct).

**gp figures.** `describeExpectedValue()` (**HarvestStatsPanel.java:1032**) prices
`prices.valueOf(seed.getProduce(), line.getItems())` against `seedCost + compostCost`, all from
`ItemPrices` (GE cache; **src/main/java/com/dooglemaps/ui/Prices.java:83-108**). Arithmetic checks
(91.9 − 21.1 = 70.8) and the price source is sound. But `line.getItems()` is the same inflated
yield, so Belladonna alone contributes ~1,176 imaginary nightshade; and protection payments are
excluded by design while disease is also unmodelled, which the note says. **Call it "produce", not
"net" — it is not a profit figure.**

---

## 6. Validation

**"You are getting 23% more than estimated"** — `getOverallAccuracy()`
(**HarvestStatsStore.java:243**) = Σ items / Σ predicted over completed patches of crops with
`predicted > 0`. Recomputed **7748 / 6307 = 1.2285 ✔**. The estimate is the **per-record prediction
made at the moment the first item landed**, using the level, compost and gear then in play — the
tooltip (**HarvestStatsPanel.java:1338**) says so and it is accurate.

The number itself is correct and the *conclusion* is not: 23 % is not "the chance-to-save formula is
23 % low". Split it:

| | patches | got | predicted | ratio |
|---|---|---|---|---|
| lives-family, compost recorded (treated arm) | 198 | 3,861 | 3,627.1 | **1.064** |
| lives-family, compost missed (`NONE` arm) | 64 | 1,165 | 566.6 | **2.056** |
| crops with no published formula (bushes, calquat, trees, cacti, belladonna, mushroom, …) | 360 | 2,740 | 2,131.5 | **1.285** |
| all | 622 | 7,766 | 6,325.2 | 1.228 |

The `NONE` arm is exactly 2.0× — the ratio of 6 lives to 3. The treated arm's residual 6.4 %
(+234 items) is almost entirely the paired-patch merges of §1 (Strawberry +139, Snape grass +60,
Flax +34, Yanillian +20, Ranarr +14 = +267). Per crop, where neither fault applies, the model is
exact: **Avantoe treated 8.84 observed vs 8.95 predicted, Watermelon 22.56 vs 23.03, Hemp 21.19 vs
22.23.** The chance-to-save arithmetic is right to about a percent; the panel buries that under a
23 % headline that is really a capture report.

**Level bands.** `fold()` (**HarvestHistory.java:317**) buckets by `min(90, level/10*10)` over
completed rows with `predicted > 0`, from the **whole CSV**. Recomputed: band 80 → n **907**,
got **13.43**, pred **11.14** — exact. So `n = 907 patches`. Two problems: (a) the section reads as
"the level curve" but it is one band of 907 patches against a band of 5, which says nothing about
level scaling; (b) it mixes 34 crops with four different yield models, so `got 13.4 / pred 11.1` is
just §2's model error re-plotted. Restrict to a single lives-family crop, or drop.

**Disease: "2 of 136 growth cycles caught something, against a predicted 50. 2 died."**
`diseaseNotes()` (**HarvestStatsPanel.java:1221**) prints `cycles − predictedSurvivals`.
Recomputed from `diseaseStats`: 136 cycles ✔, Σ predictedSurvivals 85.87 → **50.13 predicted ✔**,
2 diseased ✔ (Papaya|NONE, 3 cycles, 2 diseased, 2 died).

The predictor is the wiki's: `DiseaseRisk.survivalChance` (**src/main/java/com/dooglemaps/timer/DiseaseRisk.java:198**)
= `(1 − ⌊base × remainingRisk(compost)⌋/128)^(stages−2)`, herbs base 27/128. Untreated herb:
`0.789³ = 0.4913` — exactly the per-cycle figure stored for `Avantoe|NONE`. The formula is right.
Protected and immune patches are correctly excluded (`predicted >= 1` → return,
**DiseaseStatsStore.java:150**), so protection is *not* being under-credited.

**The 50 is the missed-compost bug again.** 93 of the 136 cycles are tagged `compost=NONE` and
carry **47.9 of the 50.1 predicted diseasings**:

```
Avantoe|NONE      48 cycles  survival 0.491/cycle  -> 24.4 predicted, 0 observed
Toadflax|NONE     19                               ->  9.7 predicted, 0 observed
Palm|NONE         14                               ->  7.4 predicted, 0 observed
Maple|NONE         7                               ->  3.7 predicted, 0 observed
Papaya|NONE        3                               ->  1.6 predicted, 2 observed
Dragonfruit|NONE   2                               ->  1.1 predicted, 0 observed
Avantoe|ULTRA      4  survival 0.954/cycle         ->  0.2 predicted, 0 observed
Maple|ULTRA       37                               ->  2.0 predicted, 0 observed
```

Score those 93 cycles at ultracompost and the prediction drops to roughly **5–6 against 2 observed**
— a normal result. A 37 % account-wide disease rate was never being predicted for the player's
actual play; it was predicted for a player who composts nothing.

**Second disease bug (independent).** `observe()` closes a cycle on any `GROWING → HARVESTABLE`
transition (**DiseaseStatsStore.java:118-126**). The javadoc immediately above says *"A bush going
back to harvestable as it regrows is the same cycle continuing, not a new one survived"* — but the
guard does not implement that, because a regrowing bush/fruit tree/tree really does pass through
GROWING. `Maple|ULTRACOMPOST` has **37 "growth cycles"** from what is at most one or two trees.
Every regrow re-applies a whole plant-to-harvest survival probability, inflating both the
denominator and the predicted count. Needs the same "is this crop regrowing / did the patch pass
through EMPTY" test that `HarvestLog.onPatchState` already carries (**HarvestLog.java:509**).

`compareTiersOnDisease()` (**HarvestStatsPanel.java:1254**) is currently silent (no tier reaches
`MIN_CYCLES_FOR_DISEASE = 50`), which is the correct behaviour; it would have printed nonsense had
Avantoe|NONE reached 50.

---

## 7. Everything else

- **"618 patches over 3 weeks, about 184 a week"** — `describeCadence()`
  (**HarvestStatsPanel.java:624**) computes `weeks = 3.357`, prints `Math.round(weeks) = 3` and
  `618/3.357 = 184`. 3 × 184 = 552. Print `⌈weeks⌉` in both, or say "3½ weeks", or drop the weeks.
- **Two different time bases in one tab.** Lifetime / Luck / Validation read `HarvestStatsStore`
  (since 14 Aug — the store was reset then); Runs and the level bands read the CSV (since 4 Aug).
  The tab says "Since 14 August" once and then reports 63 runs covering 33 days. Either scope the
  history read to the store's `getFirstHarvest()`, or say so in the Runs section.
- **Crops with n=1 carry a `+/-` in the luck table** (Kwuarm +0.1, Torstol +1.9, Seaweed +15.1,
  Camphor +1, Magic +1). The percentile has a 20-patch floor for exactly this reason; the surplus
  column has none. Suppress `+/-` below ~5 patches (**HarvestStatsPanel.java:435**).
- **`getWorst()` roll-up** (**HarvestStatsStore.java:159**) does
  `Math.min(total, Math.max(entry.getWorst(), 1))`, so a genuine 0 becomes 1. Cosmetic, but the
  tooltip's "best 77, worst 1" is then not quite a fact.
- **`describeLifetimeValue()`** (**HarvestStatsPanel.java:~400**) prices `getTotalItems()`, which
  includes the 1,412 phantom limpwurt roots and the tree logs. The 7.6M is overstated by whatever
  those price at (limpwurt roots alone are a few hundred k).
- **Lifetime "383k xp"** is harvest-record xp: ~25 % of the ~1.5M farming xp actually earned since
  14 August. It is labelled "xp" with no qualifier.
- **Papaya n=8 in the store, 11 in the CSV, all giving exactly 6** — the fruit-tree `fullStock`
  happens to be right here. Good.
- **`hasLuckPercentile`'s `variancePatches == harvests` guard is the best-designed thing on this
  tab.** Whatever else changes, keep it.

---

## Prioritised fix list

**P1 — kills the 99th percentiles, the "predicted 50" disease line and most of the 1441.**
`src/main/java/com/dooglemaps/state/PatchStateStore.java:438` — the Time Tracking compost backfill
is gated on `GROWING || DISEASED`. Compost survives until the crop leaves the patch (the same file
says so at :330-346), so allow the fill while `HARVESTABLE` as well. Keep the `protectable` guard
for **protection** only (a payment really is spent at harvest). One boolean, split in two.
*Verification: after the change, `Avantoe|NONE` should stop accruing patches; the existing
`warnIfCompostWasMissed` warning (HarvestLog.java:752) should stop firing.*

**P2 — kills the 1,412 phantom "left standing" items and the tree-log rows.**
`src/main/java/com/dooglemaps/validate/HarvestLog.java:666` (`write`) — refuse to record a harvest
with `xpGained == 0` when `CropXp.forProduce(produce).getHarvestXp() > 0`, and refuse trees outright
via the existing `CropYieldModel.hasMeaningfulYield()` (**CropYieldModel.java:407**). No farming
experience means no pick happened.

**P3 — stop crops with no yield model contributing to a "luck" total.**
`src/main/java/com/dooglemaps/validate/CropHarvestStats.java:125` (`getSurplus`) and
`HarvestStatsStore.java:215` (`getTotalSurplus`) — restrict the account-wide surplus to crops where
`predictedVariance > 0` (i.e. a real `CropYield` entry), the same population the percentile already
requires. That removes +868/−273 of unmodelled noise from the headline in one line.

**P4 — fix the three provably wrong yield entries.**
`src/main/java/com/dooglemaps/timer/CropYieldModel.java`
- :70 remove `Seed.BELLADONNA` from `LEVEL_ROLL_BASE`; belladonna is a fixed **1** (15/15 patches,
  521 xp each). Worth ~575k of the "12.4M planted out" as well.
- :162 `fullStock` — calquat is **6** and mushroom is **6** (24/24 and 6/6 patches, exactly).
- :72-74 the `EMPIRICAL` constants are level-99-with-cape figures; either scale them or exclude
  those crops from surplus/accuracy (P3 does the latter for free).
- Bushes and cacti are lives-family crops with no published constants — the honest move is to report
  their **floor** (4) as a floor, not as an expectation, and keep them out of every comparison until
  constants exist.

**P5 — stop paired patches merging.**
`src/main/java/com/dooglemaps/validate/HarvestLog.java:617` (`distanceTo`) returns 0 for every patch
in the region, so `findPatchHolding` (:562) funnels both allotments of a location into one record.
Prefer a learned coordinate over the region match when one exists, and prefer a patch with **no open
record** on a tie. Evidence: `Watermelon Catherby South actual 77`, `Strawberry Kourend SW actual 70`.

**P6 — the xp rates.**
`src/main/java/com/dooglemaps/validate/HarvestHistory.java:296` — runs are built only from harvest
records, which carry 20 % of farming xp. Fold all FARMING `StatChanged` gains into the run clusters
(check-health is the missing 80 %). Until then, delete `describeRunsToNextLevel()`
(**HarvestStatsPanel.java:863**) — "34 more runs to 91" should read ~7 — and label the others
"harvest xp".

**P7 — disease cycle counting.**
`src/main/java/com/dooglemaps/validate/DiseaseStatsStore.java:118` — a regrowing crop's
`GROWING → HARVESTABLE` is a regrow, not a new cycle (`Maple|ULTRACOMPOST` has 37). Require the patch
to have passed through EMPTY, mirroring `HarvestLog.onPatchState` (**HarvestLog.java:509**).

**P8 — panel copy and internal consistency.**
- `HarvestStatsPanel.java:551` `describeCompost` — require `respondsToCompost(seed)` and n≥20 per arm.
- `HarvestStatsPanel.java:940` `describeExpected` — derive the 8768/37035 split and the table total
  from the same counter (currently seeds-planted vs seeds-held; 45,803 vs 45,824).
- `HarvestStatsPanel.java:624` `describeCadence` — one rounding, not two.
- `HarvestStatsPanel.java:435` `rebuildLuck` — suppress `+/-` below ~5 patches.
- `HarvestStatsPanel.java:1292` `rebuildLevelBands` — one crop family, or drop.

---

## What to keep, fix, drop

**Keep as-is:** the negative-binomial mean/variance machinery (`YieldEstimate`), the
`variancePatches == harvests` gate, the limpwurt level-roll model (predicted 6.77 vs observed 6.91),
the per-tier storage in `CropHarvestStats`, `HarvestCsv`'s name-addressed columns, the run
clustering algorithm, the disease survival formula, `Prices`' client-thread discipline.

**Fix:** compost capture (P1), zero-xp records (P2), surplus population (P3), the three yield
constants (P4), patch attribution (P5), run xp (P6), disease cycles (P7).

**Drop until the inputs are fixed:** the luck percentile column, the "N items over expectation"
headline, the compost note, "About N more runs to 91", the level-band table, the "23 % more than
estimated" headline (replace with the three-way split in §6).
