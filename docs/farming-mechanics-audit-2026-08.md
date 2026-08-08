# Guidance logic vs the wiki — mechanics audit, August 2026

The Farming category (461 pages; the ~45 mechanics and patch-type pages digested in full) read
against `GuidePlan`, `GuideTracker`, `RunLoadout`, `DiseaseRisk`, `ToolNeeds` and the generated
tables. Every claim below was verified against the wiki page and the current source, not
remembered. Item pages were skipped — their data arrives generated from RuneLite core.

## Confirmed gaps, ranked

### 1. Diseased crops are routed to and then silently skipped — there is no cure step

`RunPlanner.isActionable` counts `DISEASED` as actionable, so the run travels to a dying crop —
and `GuidePlan` then has no branch for it: the growing-crop branch deliberately "leaves it
alone", the patch produces no steps, and the nothing-to-do exemption crosses it off. The player
watches their ranarr die from inside a plugin that routed them to it.

The wiki: a diseased crop stops growing until cured, then resumes. Cures split by family —
**secateurs** (either kind) prune trees, fruit trees, spirit trees and bushes (~75%/attempt);
**plant cure** does herbs, flowers, allotments, hops, hardwood, celastrus, belladonna, cactus,
mushroom; the Lunar **Cure Plant** spell does it inventory-free. The tool leprechaun stores
**1,000 plant cures** — the step is nearly always "grab a cure from the leprechaun you are
standing beside".

Wanted: a `CURE` step in `GuidePlan` (tool by family, leprechaun withdrawal step first, exactly
like the rake), and plant cure in `ToolNeeds`. Never for DEAD (cure fails on dead), never for
mature (cannot disease).

### 2. The special trees have no guidance of their own — and two would be guided wrongly

`grep REDWOOD|CELASTRUS|CRYSTAL` over `guide/` and `PatchProjection` returns nothing; all three
fall through the generic branches.

- **Celastrus** (85, Guild): check-health (14,130 XP) → **chop bark with an axe** (min 3, bark
  never regrows) → clear with spade. No chop-bark guidance exists; the axe table omitted it
  (fixed with this audit — including that harvest-only celastrus still needs the axe, the one
  type whose harvest is the axe).
- **Redwood** (90, Guild): **cannot be dug up with a spade** — clearing is paying Alexandra
  **2,000 coins**, the only tree with no self-removal. A chopped redwood regrows for
  re-chopping. Generic clear-with-spade guidance is an instruction the game refuses.
- **Crystal tree** (74, Prifddinas): diseaseless, no payment, secateurs do nothing, and harvest
  **removes the tree in one action — no stump, no clear step**. Generic guidance would wait for
  a stump that never exists. (Whether the chop needs an axe wants an in-client check; the wiki
  is silent.)

### 3. Grape vines: saltpetre is unmodelled

The vinery patch must be **treated with saltpetre before every planting**. No mention in the
tree — no loadout row, no pre-plant step — so grape guidance would instruct a plant the patch
will not accept. Vines are also disease-free, die when picked clean, and secateurs *do* boost
them.

### 4. Flower↔allotment protection is unmodelled

Growing allotment protection has three forms and the plugin models two (payment, compost). The
third — a **fully grown adjacent flower** (marigold/rosemary/nasturtium per crop; **white lily
covers everything**) — is invisible to `DiseaseRisk`, so allotment survival estimates ignore a
protection players actually run, and the guide neither suggests planting the flower first nor
knows that **picking the flower ends the protection**.

### 5. Spirit tree simultaneous-plant cap

1 at 83, 2 at 88, 3 at 93, 4 at 96, unlimited at 99. Nothing in the planner or allocation knows
the cap, so a run can cheerfully instruct planting a fifth spirit tree at 96.

### 6. Belladonna wants gloves at harvest

Harvesting bare-handed deals damage; cosmetic gloves do not count. No loadout row, no step
wording. One line each.

### 7. Small estimate-accuracy notes

- Magic secateurs boost herbs, allotments, hops, grape vines, bushes, limpwurt, celastrus,
  coral — **not** cacti, fruit trees, calquat, most flowers, crystal, seaweed.
  `CropYieldModel` already scopes to one family; extendings should honour the exclusion list.
- Disease immunity in the first growth stage and at maturity; the just-planted compost nag in
  `GuidePlan` and `DiseaseStats` cycle counting both look consistent with this, but nothing
  pins it.
- Potato cactus grows on 10-minute stages vs cactus's 80 — generated tick rates should already
  carry this; worth one glance at the generated table.

## What checked out — verified correct, no action

- **Payments**: per-patch multiplication, noted acceptance (shipped this session), the exact
  container items (sacks/baskets) named by id, seaweed's 200 numulite, and the coral tiers
  already present in the generated table.
- **Unprotectable set** (herb, flower, mushroom, belladonna) matches the wiki exactly, and the
  disease-free split — Trollheim/Weiss provable-by-presence, Hosidius/Harmony/Falador/Civitas
  behind the per-player flag — is the right shape for the heterogeneous gating.
- **Magic secateurs detected from inventory or worn** — correct post-Feb-2023 behaviour many
  plugins still get wrong.
- **Contract completion** is captured from chat messages, which is robust to the per-type
  completion moments (check-health for trees/fruit/cactus/special, full harvest for
  herbs/allotments/flowers/bushes/hops). The known fragility — wording drift — is already in
  `docs/TODO.md`.
- **Seedling/potting flow** (filled pot, water, minutes to sapling, grows in the bank) matches
  what the potting-supplies feature shipped this session.
- **Watering** as a run feature remains the open decision in `docs/TODO.md`; the wiki confirms
  its scope is exactly allotment/flower/hops, disease-only, one cycle per watering — which
  supports the "is it worth modelling at all" framing.
- **Axe rule** after this audit's fix: replant runs over tree/fruit/hardwood/redwood ask;
  calquat never does (spade-only clear); celastrus always does (the bark IS the axe);
  harvest-only otherwise never does; contracts ask because they are never harvest-only.

## Sources

Digested in full: Farming, Farming runs, Disease, Compost, Seedling, Sapling, Plant cure, Tool
Leprechaun, Amulet of nature, Bottomless compost bucket, Grand seed pod, Magic secateurs, all
twelve patch-type pages, Farming contract, Farming Guild, Hespori, Celastrus, Crystal tree,
Anima, Spirit tree, Mushroom, Belladonna, Seaweed, Coral nursery, Compost bins, Farmer's
outfit, Farming cape, patch locations, and the five farming-relevant diaries.

## Addendum: the PatchRules audit (same day)

The 705-line generated table got its first full review pass, against upstream and the wiki
both. Verdict: **byte-identical to RuneLite master for every (implementation, value) pair**,
so every oddity is a faithful mirror of core, never a generator bug. What core never encoded
— and the guide now recovers by raw varbit, the validated `isStumpVarbitValue` pattern — is
the action level: celastrus 14–16 bark / **17 stripped-chop** / **28 stump-clear**, and
grapes **0 untreated / 1 saltpetred** (both of which also masquerade as weeds; grape patches
are never raked and are now excluded from the rake branch). Redwood's sixteen post-maturity
states are genuinely uniform Clear-only values; crystal trees genuinely end with no stump.
The tree/hardwood stump-shape rule matched upstream's commented object ids in every case.
