# Stats tab — UX critique and redesign spec

Target: `src/main/java/com/dooglemaps/ui/HarvestStatsPanel.java` (1481 lines), plus
`DataTable.java` (214) and one new component. RuneLite sidebar, Swing, ~225px nominal
(`PluginPanel.PANEL_WIDTH`) and ~290px as the owner runs it. No charts library.

---

## 0. Ground truth gathered

House style, verified in source:

| Piece | Where | Notes |
|---|---|---|
| Collapsible header | `Controls.collapseLabel` (`Controls.java:69`) | `▾ `/`▸ ` prefix on a `JButton` + `Controls.styleButton` |
| Collapse persistence | `PanelLayoutStore` (global, not per-profile) | `isOpen(key, default)` / `setOpen(key, open)` |
| Wiring precedent | `CompostBinPanel.java:296-305`, `PatchTypePanel.java:135-151`, `RunPanel.java:914-930` | all three are `JButton` + `ActionListener` + `layout.setOpen` + `refresh()` |
| Table | `DataTable.java` | fixed 90px name column, grid of right-aligned value cells, header rule, zebra, total row |
| Table reuse | `RewardTable.java:29` — `new DataTable("Projected", "Est Yield", "Est XP")` | the "Projected" table; same widget, 2 value columns |
| Wrapping prose | `WrappedText` | **fixed** `WRAP_WIDTH = 195` |
| Tooltips | `Tooltips.html` (260px), `Tooltips.inPanel` (195px) | every tooltip on this tab already goes through `Tooltips.html` |
| Fonts | 27 call sites, **all** `FontManager.getRunescapeSmallFont()` | `getRunescapeFont()` and `getRunescapeBoldFont()` exist and are unused |

Colour constants, decompiled from `client-1.12.38.jar` so the contrast numbers below are real:

```
DARKER_GRAY_COLOR  30,30,30    #1E1E1E   DataTable.STRIPE
DARK_GRAY_COLOR    40,40,40    #282828   panel + DataTable.HEADER
MEDIUM_GRAY_COLOR  77,77,77    #4D4D4D   column-header TEXT + rules
LIGHT_GRAY_COLOR  165,165,165  #A5A5A5   WrappedText prose, total row
TEXT_COLOR        198,198,198  #C6C6C6   data cells, section headings
GRAND_EXCHANGE_PRICE 110,225,110         unused here
PROGRESS_COMPLETE  55,240,70 / PROGRESS_ERROR 230,30,30
```

---

## 1. UX critique

### 1.1 The tab has no top

Opening Stats gives you a paragraph. Not a number — a paragraph, in the dimmest text on the
tab, followed by a table, followed by more paragraph. There is nothing to *land on*. Every
other tab in this plugin opens on state you can read in a glance (patch rows, a Start run
button); this one opens on reading homework.

Five sections × (prose + table + prose) with no visual weight difference between the sentence
"Worth about 4.2M gp at today's prices" and the sentence "Assumes you always plant the best
experience per patch you can". Both are `WrappedText` at `LIGHT_GRAY_COLOR`, same size, same
colour, stacked. The reader has no way to know which of the ~14 prose blocks matter.

**~1350px of content, of which roughly 500px is prose and roughly 200px is a table printed
twice.** The owner is right that it needs a long scroll to reach anything; the deeper problem
is that scrolling doesn't help, because nothing at the end is more important than anything at
the start.

### 1.2 Lifetime and Validation are the same table

This is the single largest concrete finding and it is verifiable line by line:

```
rebuildLifetime,  HarvestStatsPanel.java:374-377
  lifetimeTable.addRow(crop.getCrop(), cropTooltip(crop, tiers.get(crop.getCrop())),
      String.valueOf(crop.getHarvests()),      // n
      String.valueOf(crop.getTotalItems()),    // items
      DataTable.shortNumber(crop.getTotalXp()));

rebuildValidation, HarvestStatsPanel.java:1193-1196
  table.addRow(crop.getCrop(), cropTooltip(crop, tiers.get(crop.getCrop())),
      String.valueOf(crop.getHarvests()),      // n      <- identical
      String.valueOf(crop.getTotalItems()),    // got    <- identical
      format(crop.getAverageYield()));
```

Same rows, same name column, **the same tooltip object**, same `n`, same item count. Only the
fourth column and the sort order differ — and the fourth column, `getAverageYield()`, is
`items / harvests`, i.e. two columns the reader is already looking at, divided. The Validation
table is the Lifetime table with a redundant column, printed ~700px further down, under a
different heading, sorted differently so it doesn't even look like a repeat until you read it.

The Luck table is the same rows a third time (`crop`, `getHarvests()`, then surplus and
percentile). **Three of the five tables are one per-crop table with different value columns.**

### 1.3 The column headers are, measurably, invisible

`DataTable.headerRow` paints the column names in `MEDIUM_GRAY_COLOR` (77,77,77) on
`DARK_GRAY_COLOR` (40,40,40) — `DataTable.java:120,123`.

Contrast ratio: **1.68:1.** WCAG AA for text of this size wants 4.5:1. This is not "dim", it is
about a third of the minimum. The owner's "column headers barely visible (`crop n items xp` in
dim text)" is an understatement — at this ratio the headers are decorative.

The 1px rules under the header and above the total are the same colour: **1.68:1** against the
row behind them, where WCAG 1.4.11 wants 3:1 for a meaningful graphical object. The zebra
striping (30,30,30 vs 40,40,40) separates rows at **1.17:1**, which is to say not at all. So
the table has, in practice, no header, no rule and no stripes: it is four columns of unlabelled
numbers floating on flat grey, and it is repeated five times.

The class comment at `HarvestStatsPanel.java:1408-1414` states the intent honestly — "the
hierarchy comes from brightness instead: headings in the full text colour, prose a shade down,
column names a shade below that". The idea is sound. The third step just fell off the bottom of
what a monitor renders. It is a three-tier scale where the third tier is below threshold.

### 1.4 The prose is where the answers went to hide

Twelve `WrappedText` blocks (`:142-214`). Every one of them is a full sentence or three, at
`LIGHT_GRAY_COLOR`, wrapped at a hardcoded 195px.

Two problems compound:

- **Nothing is a headline.** "21.2k xp a day, averaged over the whole history — the rate a
  skill on a growth timer actually runs at." The number `21.2k` is the answer; the other
  eighteen words are a footnote about methodology, set in the same type at the same weight, so
  the eye has to parse the sentence to find the figure. Every rate on this tab is shaped this
  way. The methodology is *good* — it is the most careful thing about the tab — and it is
  filed in exactly the wrong place.
- **`WRAP_WIDTH = 195` is hardcoded** (`WrappedText.java:29`, sized for a 225px sidebar). At
  the owner's ~290px the prose wraps at 195px and leaves ~85px of dead sidebar down the whole
  right-hand side of every paragraph, while the tables beside it stretch to full width. That
  ragged short column against full-width tables is a large part of why the tab reads as
  unfinished.

### 1.5 The luck column reads as an error state

`luckTable` shows `"-"` for every crop under 20 picked patches (`:464`). On a real account that
is most rows. A player scanning the column sees a table of dashes and one or two `"71st"`.

`"-"` is the wrong glyph twice over: in a column of signed numbers (`+/-` is the neighbouring
column) it reads as a minus sign, and a blank-looking cell reads as *broken* rather than as
*deliberately withheld*. The reasoning behind the blank is excellent and is written down at
`:661-675` — it is simply invisible unless you hover.

`"99th"` is also the wrong unit for the sidebar. A percentile is a second-order statistic; it
requires the reader to hold "where a distribution of totals would land" in their head. There is
no legend, no colour, and no indication whether high is good. The number that answers the same
question in one glance is already computed and already correct: `getAccuracy()`
(`CropHarvestStats.java:96`) is `items / predicted`, so `(accuracy − 1) × 100` is a signed
percentage against expectation that needs no variance, no normality and no sample floor to be
*true* — only a sample floor to be *stable*.

### 1.6 Mixed and inconsistent units

`shortNumber` is used well and consistently for xp and gp (`k` / `M`, `DataTable.java:192`).
The gap is counts: `String.valueOf(crop.getTotalItems())` prints `12043`, unseparated, in a
right-aligned 47px cell next to `412k`. Two number systems in adjacent columns, neither
labelled. Same in the run rows and the seed counts.

### 1.7 Section-by-section

| Section | Question it answers | Headline it should have | Noise |
|---|---|---|---|
| Lifetime | "What have I actually got?" | total xp | the "since" date (→ caption), the gp line's 20-word caveat (→ tooltip) |
| Luck | "Am I running hot or cold?" | account-wide `+/-` | the whole section — it is a column on the Lifetime table plus two notes |
| Runs | "How fast am I going, and when do I level?" | **runs to next level** | xp/day and xp/hour each buried in a 20-word sentence |
| Expected | "What should I plant, and what is the bank worth?" | xp from planting it all out | the "Assumes you always plant the best…" paragraph, the flat-level comparison sentence |
| Validation | "Are the plugin's numbers right?" | a verdict word | the entire crop table (duplicate); the level-band table is the only thing here that isn't said better elsewhere |

The `"+ 22 more"` / `"+ 61 more"` rows are the tab's one outright dead end: a row that looks
like a row, is not clickable, and tells you the thing you want is not available. On a mature
account the 12-row cap hides more than it shows.

`MAX_ROWS = 12` at `:76` is applied to the *visible* list but the truncation row is added
*after* the total row is computed from the full store, so `total` and the visible rows
disagree by design — correct, and completely unexplained on screen.

---

## 2. Redesign

### 2.1 Information architecture

Five sections → **four**, each one a collapsible card whose header carries a headline number
that is readable while the card is shut.

| New section | Key | Absorbs | Default |
|---|---|---|---|
| **Harvests** | `stats.harvests` | Lifetime + Luck's table + Validation's crop table | **open** |
| **Runs** | `stats.runs` | Runs | closed |
| **Bank** | `stats.bank` | Expected | closed |
| **Checks** | `stats.checks` | Validation's accuracy line, level bands, disease | closed |

"Luck" stops being a section. Its table is a column on Harvests; its account-wide surplus is
half the Harvests caption; its two notes (compost comparison, cadence) move inside the Harvests
body as one-line notes. Rationale: luck was never a separate *subject*, it was a separate
*view of the same rows*, which is exactly what a column is for.

"Validation" is renamed **Checks** and keeps only what the merged table cannot say: the
verdict line, the level-band table (the one genuinely distinct view — it checks the level
*scaling*, not the constants) and disease.

**Collapsed by default except the first.** With `PanelLayoutStore` the choice persists, and
because the store is global (see its class comment) it persists across accounts, which is
right for a preference about furniture.

Collapsed height per section ≈ 42px (header row 18 + caption 14 + padding 10). Four sections
plus one open Harvests body ≈ **520px against today's 1350px**, and the top 170px now carries
four numbers instead of one paragraph.

### 2.2 New component: `StatSection`

The header *is* the card. One clickable panel, no separate title-plus-card stack:

```
row 1:  ▾ Harvests                              4.2M
row 2:    1,204 patches since 4 Aug · +3% vs exp
```

- Line 1: `Controls.collapseLabel(title, open)` in `getRunescapeSmallFont()` at `TEXT_COLOR`,
  WEST; headline in **`FontManager.getRunescapeBoldFont()`** at `TEXT_COLOR`, EAST.
  `getRunescapeBoldFont()` is the same family RuneLite itself uses for panel titles, so this
  introduces weight hierarchy **without introducing a second font family** — which is the
  constraint the current class comment was protecting when it chose brightness instead. Weight
  is the axis that was missing; brightness had already run out of headroom (§1.3).
- Line 2: caption, plain `JLabel`, `LIGHT_GRAY_COLOR`, `getRunescapeSmallFont()`. **A plain
  `JLabel`, never `WrappedText`** — it sits inside a bordered panel and `WrappedText` clips its
  last line there (its own class comment, `WrappedText.java:19-24`). One short line, hard cap;
  anything longer goes in the tooltip.
- The whole header takes the section tooltip (the long explanation currently in `heading(...)`
  survives verbatim — it is good writing and belongs on hover).
- Click anywhere on the header toggles. Use a `JButton` styled by `Controls.styleButton` for
  line 1 exactly as `CompostBinPanel.wire` does, so the affordance matches the rest of the
  plugin, and put line 2 in a sibling label that shares the click via a `MouseListener`.

No "?" glyph. The caret already marks the header as interactive, and every header in this
plugin already carries its explanation on hover; adding a second hover target inside a 290px
header would be one affordance too many. The tooltip on the header *is* the "?".

### 2.3 The merged crop table

```java
private final DataTable cropTable = new DataTable("crop", "n", "got", "xp", "+/-");
```

Five value columns. At 290px: 278px usable − 90px name = 188px / 4 = 47px per column, ample
for `1,131` and `+4%` in RuneScape Small. At the 225px minimum: 213 − 90 = 123 / 4 = 31px,
tight but survivable because `shortNumber` caps xp at 5 characters and `+/-` at 4. Bump
`DataTable.COLUMN_WIDTH` from 34 to 38 so the *preferred* size is honest at four columns.

Column meanings, and what each one replaces:

| Col | Value | Was |
|---|---|---|
| `crop` | `crop.getCrop()` | all three tables |
| `n` | `crop.getHarvests()` | all three tables |
| `got` | `crop.getTotalItems()`, `%,d` | Lifetime `items` **and** Validation `got` |
| `xp` | `shortNumber(crop.getTotalXp())` | Lifetime `xp` |
| `+/-` | `(getAccuracy() − 1) × 100`, as `%+.0f%%` | Luck `+/-` **and** Luck `luck` **and** Validation `avg` |

Nothing is lost. `avg` is `got / n`, both on screen. `predicted` and `avg predicted` are
already in `cropTooltip` (`:1376`). The percentile and best/worst are already in `luckTooltip`.
Sort by `getTotalXp()` descending — the Lifetime order, which the existing comment at `:146-149`
already argues is the right one ("a farmer knows roughly how many watermelons they have picked
and does not know which crop has actually paid for the levels").

**Correctness caveat the implementer must not miss.** `got` is `getTotalItems()` = `items +
partialItems`, but `getAccuracy()` is `items / predicted` — partials excluded. So `got` and
`+/-` are computed over *different sets of patches*, deliberately (a half-picked patch is not a
low yield). Today the same asymmetry exists across two tables and is explained in
`accuracyTooltip()`. Merged into one row it becomes visible, so the `+/-` header tooltip must
carry that sentence, and `cropTooltip` must keep its "N more from patches left standing, not
counted in the average" line (`:1379-1385`).

### 2.4 The `+/-` column — reading, colour, and both outcomes

Rendering:

```
n >= CropHarvestStats.MIN_PATCHES_FOR_LUCK (20)   coloured: OVER if > 0, UNDER if < 0
n <  20                                            same number, LIGHT_GRAY, uncoloured
predicted <= 0                                     empty cell, tooltip says "not modelled"
```

The **sign carries the meaning and the colour only reinforces it**, so the column is legible to
a red-green colour-blind reader and in a screenshot. No `"-"` glyph anywhere in this column —
an empty cell plus a tooltip is the honest form of "not scored" and cannot be misread as minus.

Colours (both measured against `DARK_GRAY_COLOR`; the stripe rows are darker so ratios only
improve):

```java
/** 8.58:1 — RuneLite's own "good price" green. */
private static final Color OVER  = ColorScheme.GRAND_EXCHANGE_PRICE;      // 110,225,110
/** 5.08:1. PROGRESS_ERROR_COLOR is only 3.10:1 and fails AA at this size. */
private static final Color UNDER = new Color(235, 120, 120);
```

Do **not** use `PROGRESS_ERROR_COLOR` (230,30,30): it measures **3.10:1**, below the 4.5:1 this
text size needs, and saturated red on near-black is the worst case for the most common colour
deficiency.

Sample-size floor: reuse `CropHarvestStats.MIN_PATCHES_FOR_LUCK = 20` rather than inventing a
constant. Its javadoc justifies 20 on `√n` grounds ("the spread grows like √n while the total
grows like n") *and* separately on normality grounds. Only the second is percentile-specific.

**Designing for both outcomes on the percentile:**

- *If the percentile survives* — it appears nowhere in a column. It stays in `luckTooltip`
  (`:653-660`) exactly as written, as the third paragraph of the crop's hover. Nothing to do.
- *If the numbers agent finds it unsound* — delete `hasLuckPercentile()`'s branch in
  `luckTooltip` (`:653-675`) and the two static helpers `percentile` (`:736-739`) and `ordinal`
  (`:741-759`). That is ~30 lines in one method plus two dead statics. The table does not move,
  the colour floor does not move (the `√n` argument stands alone), and no layout changes.

That is the whole point of putting the percentile in the tooltip: its fate is a text edit, not
a redesign.

### 2.5 Table rules, applied to every table on the tab

1. **Visible headers.** Column-name text `MEDIUM_GRAY_COLOR` → `LIGHT_GRAY_COLOR`.
   1.68:1 → **5.78:1**. One-line change, `DataTable.java:120,123`.
2. **Visible rules.** Header and total rules → `new Color(120,120,120)`, **3.23:1**, clearing
   WCAG 1.4.11's 3:1 for a meaningful graphic. `DataTable.java:113,125`.
3. **Right-aligned numbers — already correct.** `addCell` uses `SwingConstants.RIGHT`
   (`DataTable.java:164`). No work.
4. **Zebra rows — leave alone.** 1.17:1 does effectively nothing, but raising it costs the flat
   look the rest of the sidebar has, and once (1) and (2) land the header rule does the
   separating. Do not spend a step here.
5. **Units, one rule per kind.** xp and gp via `shortNumber` (already). Counts via a new
   `DataTable.count(long)` = `String.format("%,d", n)`. Percentages `%+.0f%%`. Averages one
   decimal (existing `format`).
6. **Not sortable.** Each table has one order that answers its question and that order is
   already argued for in the source. Clicking a 15px-tall header cell in a 47px column is a
   precision task; no RuneLite panel establishes the convention; it would add arrow glyphs, hit
   targets and a stored preference to a table of ≤12 rows. The "my crop is missing" case is
   what "show all" is for.
7. **"Show all" replaces "+N more".** New `DataTable.addActionRow(text, tooltip, Runnable)`:
   a full-width row, `LIGHT_GRAY_COLOR`, hand cursor, `MouseListener`. Text
   `"▾ show all 34 crops"` / `"▴ show 12"`. The panel owns `boolean showAllCrops` /
   `showAllSeeds` and re-runs `refresh()`. Not persisted — a "show all" is a moment's curiosity,
   not furniture.

### 2.6 Prose budget

Hard rule: **one caption line per section header, plus at most two one-line notes inside a
section body.** Everything else moves to a tooltip.

| Today | Becomes |
|---|---|
| `lifetimeSummary` (2 lines) | Harvests caption |
| `lifetimeValue` (2-3 lines, gp + caveat) | Harvests body note, one line `≈ 4.2M gp at today's prices`; the "what it would fetch now, not what it made at the time" caveat → its tooltip |
| `luckSummary` (2-3 lines) | second half of Harvests caption (`· +3% vs exp`); the "N items came from patches left standing" line → `+/-` header tooltip |
| `luckNotes` (compost + cadence, 4-6 lines) | two Harvests body notes, one line each, full text on hover |
| `runsSummary` | Runs caption |
| `runsNotes` (3 × ~20-word sentences) | three one-line notes: `21.2k xp/day`, `74k xp/hour farming`, and **`runs to next level` promoted to the Runs headline** |
| `expectedSummary` (up to 3 sentences) | Bank caption = the xp total + `78 → 91`; the "99 arrives on N seeds" and "staying at level X" sentences → header tooltip |
| `expectedUnlocks` | keep as-is inside the Bank body — it is a list, not prose, and it is the one place the simulation's working is visible |
| `expectedNotes` (value/cost/net, locked crops, "Assumes…") | one line `2.1M gp produce, 340k costs → 1.8M net`; locked-crops one-liner; **"Assumes you always plant the best…" → Bank header tooltip** |
| `accuracy` | Checks headline is the verdict word; the ratio and caveats stay in `accuracyTooltip()` (unchanged, already good) |
| `diseaseNotes` | one line `Disease: 12 of 340 cycles, predicted 14`, full text on hover |
| `nothingYet` | unchanged — an empty state should be a paragraph |

`WrappedText` survives only for `nothingYet` and `expectedUnlocks`. That removes the 195px
dead-column problem (§1.4) everywhere it was visible, without touching `WrappedText` itself.

### 2.7 Headlines, per section

| Section | Headline | Caption | Fallback when thin |
|---|---|---|---|
| Harvests | `shortNumber(totalXp)` e.g. `4.2M` | `1,204 patches since 4 Aug · +3% vs exp` | drop `· +3% vs exp` when nothing is scored; drop `since` when `getFirstHarvest() <= 0` |
| Runs | **`9 runs to 87`** | `18.4k xp a run over 64 runs` | at 99, or with no `getFarmingXp()`, headline falls back to `shortNumber(xpPerRun)` + caption `xp a run` |
| Bank | `shortNumber(projection.getXp())` e.g. `2.1M` | `everything you hold · 78 → 91` | `not quite a level from 78` when `endLevel == startLevel`; `nothing you can plant yet` when lines are empty but crops are locked |
| Checks | `Matching` / `+8%` / `−12%` | `over 1,204 patches` | `not enough yet` when `getOverallAccuracy() <= 0` |

`9 runs to 87` as the Runs headline is the highest-value single change on the tab after the
merge. The source already knows it (`describeRunsToNextLevel`, `:856-881`, whose own comment
calls it "arguably the single most useful line on the tab") — and it is currently the *third*
sentence of the *third* prose block of the *third* section, below the fold.

### 2.8 Empty and low-data states

Every one of these already has a floor in the code; the change is that a floor should produce a
*caption*, not a *disappearance*, so the reader learns why a number is missing.

| Condition | Existing floor | New behaviour |
|---|---|---|
| Nothing at all | `:308` `recorded` | `nothingYet` unchanged |
| Crop with `n = 1..19` | `MIN_PATCHES_FOR_LUCK = 20` | `+/-` shown **uncoloured**; tooltip: "N more picked patches before this settles" (text already at `:661-667`) |
| Crop with `predicted <= 0` | `:444` | `+/-` cell empty; tooltip: "the spread of a single patch is not modelled for it" (text already at `:673-674`) |
| History < 1 week | `:628` `WEEK` | cadence note hidden (already); **Harvests caption drops `since`** and reads `1,204 patches · +3% vs exp` |
| Runs section, 1 run | `:784` | no `average` total row (already); caption reads `first run` |
| Disease < 50 cycles | `MIN_CYCLES_FOR_DISEASE` | note hidden (already); Checks body shows nothing rather than a zero |
| Level bands < 2 | `:1295` | table hidden (already) |
| Fresh install with seeds | `:331` | Bank is the only open section — **override the "Harvests open by default" rule when Harvests has nothing**; otherwise a new player opens the tab on a closed accordion |

That last row is the one genuinely new state and it matters: today `rebuildExpected` correctly
survives an empty history, but with collapse added, a fresh install would show four shut
headers and nothing else.

### 2.9 Accessibility summary

| Element | Now | After | AA (4.5:1 text / 3:1 graphic) |
|---|---|---|---|
| Column header text | `MEDIUM_GRAY` on `DARK_GRAY` — **1.68:1** | `LIGHT_GRAY` — **5.78:1** | fail → pass |
| Header / total rule | **1.68:1** | `(120,120,120)` — **3.23:1** | fail → pass |
| Data cells | `TEXT_COLOR` — **8.33:1** | unchanged | pass |
| Prose / captions | `LIGHT_GRAY` — **5.78:1** | unchanged | pass |
| Total row | `LIGHT_GRAY` — **5.78:1** | unchanged | pass |
| Zebra separation | **1.17:1** | unchanged (deliberate) | n/a |
| `+/-` over | — | `GRAND_EXCHANGE_PRICE` — **8.58:1** | pass |
| `+/-` under | — | `(235,120,120)` — **5.08:1** | pass |
| *(rejected)* `PROGRESS_ERROR_COLOR` | — | **3.10:1** | **fail — do not use** |

So: the current grey is far below the sidebar's other secondary text, and it is the only thing
on the tab that is. `LIGHT_GRAY_COLOR` is already the plugin's secondary-text colour in
`WrappedText`, `PatchRow`, `SeedSelectorPanel` and `DataTable`'s own total row — using it for
column headers is a return to house style, not a departure from it.

---

## 3. ASCII mockup

290px sidebar ≈ 49 characters of RuneScape Small. Default state on open, then variants.

```
 1  +-------------------------------------------------+
 2  |  [   Almanac   ] [    Stats    ]                 |
 3  +-------------------------------------------------+
 4  |                                                  |
 5  |  v Harvests                               4.2M   |   <- bold, TEXT_COLOR
 6  |    1,204 patches since 4 Aug  ·  +3% vs exp      |   <- LIGHT_GRAY, one line
 7  |                                                  |
 8  |    crop               n     got     xp     +/-   |   <- LIGHT_GRAY (was 1.68:1)
 9  |    ============================================= |   <- (120,120,120) rule
10  |    Ranarr weed      142   1,131    412k     +4%  |   green
11  |    Snapdragon        88     701    356k     -2%  |   red
12  |    Torstol           61     498    301k     +9%  |   green
13  |    Watermelon        54   1,940    288k          |   empty: not modelled
14  |    Dwarf weed        47     372    244k     -1%  |   red
15  |    Cadantine         44     349    198k     +2%  |   green
16  |    Lantadyme         39     305    171k     +6%  |   green
17  |    Kwuarm            36     291    154k     -4%  |   red
18  |    Avantoe           31     248    122k     +1%  |   green
19  |    Irit leaf         28     221     97k     -3%  |   red
20  |    Toadflax          22     174     81k     +7%  |   green
21  |    Harralander       19     149     62k     +5%  |   LIGHT_GRAY: n<20
22  |    v show all 34 crops                           |   <- clickable
23  |    --------------------------------------------- |
24  |    total          1,204  12,043    4.2M          |   <- LIGHT_GRAY
25  |                                                  |
26  |    ~ 4.2M gp at today's prices                   |   <- note, hover for caveat
27  |    Ultracompost: 8.4 a patch vs 6.1 super        |   <- note, hover for detail
28  |    1,204 patches over 31 weeks, ~39 a week       |   <- note
29  |                                                  |
30  |  > Runs                             9 runs to 87 |   <- shut, headline visible
31  |    18.4k xp a run over 64 runs                   |
32  |                                                  |
33  |  > Bank                                   2.1M   |
34  |    everything you hold  ·  78 -> 91              |
35  |                                                  |
36  |  > Checks                             Matching   |
37  |    over 1,204 patches                            |
38  |                                                  |
39  +-------------------------------------------------+
40
41   ---- Runs opened -------------------------------
42
43  |  v Runs                             9 runs to 87 |
44  |    18.4k xp a run over 64 runs                   |
45  |                                                  |
46  |    run                n   items      xp          |
47  |    ============================================= |
48  |    last               6      48    9.1k          |
49  |    best              14     121   24.6k          |
50  |    --------------------------------------------- |
51  |    average          9.4      74   18.4k          |
52  |                                                  |
53  |    21.2k xp a day                                |   <- hover: "averaged over
54  |    74k xp an hour while farming                  |      the whole history..."
55  |                                                  |
56
57   ---- Checks opened -----------------------------
58
59  |  v Checks                             Matching   |
60  |    over 1,204 patches                            |
```

Continuing past row 60, for reference:

```
61  |    level              n     got    pred          |
62  |    ============================================= |
63  |    50-59             84     6.1     5.9          |
64  |    60-69            201     6.8     6.7          |
65  |    70-79            512     7.9     7.6          |
66  |    80-89            407     8.4     8.4          |
67  |                                                  |
68  |    Disease: 12 of 340 cycles, predicted 14       |
69  |    Ultra lost 2% of cycles; super lost 6%        |
```

Fresh install (no history, seeds banked) — Bank auto-opens, §2.8:

```
    > Harvests                              --
      nothing harvested yet
    v Bank                                 640k
      everything you hold  ·  32 -> 58
      ...
```

---

## 4. Components to add or change

### New file — `src/main/java/com/dooglemaps/ui/StatSection.java` (~130 lines)

```java
class StatSection extends JPanel
{
    StatSection(PanelLayoutStore layout, String key, String title, String tooltip,
                boolean openByDefault, JComponent... body);
    void setHeadline(String value);      // bold font, right of the caret line
    void setCaption(String line);        // one JLabel, LIGHT_GRAY, hidden when empty
    void setOpen(boolean open);          // used for the fresh-install override, §2.8
}
```

Copy the wiring shape from `CompostBinPanel.java:296-305` verbatim: `JButton` +
`Controls.styleButton` + `Controls.collapseLabel` + `layout.setOpen(key, !isOpen)` + `refresh`.

### `DataTable.java` — +~85 / −6

| Anchor | Change |
|---|---|
| `:42` `COLUMN_WIDTH = 34` | → `38`, honest preferred width at 4 value columns |
| `:113` total rule colour | `MEDIUM_GRAY_COLOR` → new `RULE = new Color(120,120,120)` |
| `:120,123` header text colour | `MEDIUM_GRAY_COLOR` → `ColorScheme.LIGHT_GRAY_COLOR` |
| `:125` header rule colour | → `RULE` |
| after `:102` | `void addRow(String name, String tooltip, Color[] cellColours, String... values)` — per-cell colour for `+/-`; existing 3-arg `addRow` delegates with nulls (~20 lines) |
| after `:115` | `void addActionRow(String text, String tooltip, Runnable onClick)` — full-width, hand cursor, `MouseListener` (~30 lines) |
| after `:192` | `static String count(long n)` = `String.format("%,d", n)` (~4 lines) |

### `HarvestStatsPanel.java` — 1481 → ~1150 (−330 net: ~450 removed, ~120 added)

| Anchor | Change |
|---|---|
| `:76` `MAX_ROWS` | keep; add `showAllCrops` / `showAllSeeds` fields |
| `:107-111` name constants | `LIFETIME_TABLE` + `LUCK_TABLE` + `VALIDATION_TABLE` → one `CROP_TABLE`; keep the three old constants as aliases pointing at the same string for one release so tests move in a separate commit |
| `:136-140` five section panels | → four `StatSection` fields |
| `:142-214` fields | 12 `WrappedText` → 2 (`nothingYet`, `expectedUnlocks`) + ~8 note `JLabel`s; 6 `DataTable` → 4 (`cropTable`, `runsTable`, `expectedTable`, `levelTable`) |
| `:150` `lifetimeTable` | → `cropTable = new DataTable("crop","n","got","xp","+/-")` |
| `:163` `luckTable` | **delete** |
| `:199` `table` | **delete** |
| `:259-297` `fill(...)` calls | → four `StatSection` constructions; the four long `Tooltips.html` strings move onto the section headers **unchanged** |
| `:306-343` `refresh` | `+` set headlines/captions; `+` fresh-install open override |
| `:347-391` `rebuildLifetime` | → `rebuildCrops`, absorbing `:435-473` and `:1180-1206` |
| `:435-473` `rebuildLuck` | **delete** (surplus → caption via `describeLuck`'s arithmetic, table → column) |
| `:461-465` the `"-"` cell | **delete** — see §2.4 |
| `:736-759` `percentile`/`ordinal` | keep only if the percentile survives (§2.4) |
| `:768-797` `rebuildRuns` | headline from `describeRunsToNextLevel`; notes → one-line labels |
| `:824-854` `runNotes` | → three one-liners + three tooltips |
| `:893-938` `rebuildExpected` | headline/caption split; `"+ N more"` (`:924`) → `addActionRow` |
| `:1172-1211` `rebuildValidation` | → `rebuildChecks`: verdict headline, level bands, disease. Crop table gone. |
| `:1366-1405` `cropTooltip` | keep, extend with the `+/-` explanation |
| `:1415-1426` `heading(...)` | **delete** — `StatSection` owns it |
| `:1441-1457` `fill(...)` | **delete** — `StatSection` owns it |

### `HarvestStatsPanelTest.java` — +~20, ~8 assertions moved

`textOf` (`:993-1019`) **skips invisible components by design**. Collapsing sections therefore
breaks ~16 tests at step 4, not step 7. Two helper changes fix all of them and no assertion
needs rewriting:

1. In `textOf`, also append `((JComponent) child).getToolTipText()` — prose moved to a tooltip
   is still "text the panel shows", which is exactly what the helper's javadoc claims to
   collect.
2. Add `openAll(Container)`: walk for `AbstractButton`s whose text starts with `"▸"` and
   `doClick()` them. Call it from the `panel()` / `panelFor(...)` factories.

Do **not** add an `expandAll()` to production code for the tests' benefit; the button walk is
honest and tests the real affordance.

### `DoogleMapsPanel.java` — +2

`PanelLayoutStore layout` already exists as a field (`:93`, assigned `:180`). Pass it to the
`HarvestStatsPanel` constructor at `:190`.

---

## 5. Implementation plan — ordered, each step shippable

| # | Step | Files | Lines | Ships |
|---|---|---|---|---|
| **1** | **Contrast.** Header text → `LIGHT_GRAY`; rules → `(120,120,120)`. | `DataTable.java:113,120,123,125` | ~6 | Fixes the single worst defect (1.68:1 → 5.78:1) on **every** table in the plugin, including `RewardTable`. No behaviour change, no test change. Do this first and alone. |
| **2** | **Thousands separators.** `DataTable.count(long)`; use for every count cell. | `DataTable`, `HarvestStatsPanel` | ~20 | `12043` → `12,043`. Existing assertions (`"200"`, `"6"`, `"14"`, `"121"`) are all < 1000 — unaffected. |
| **3** | **`StatSection`.** New component, unwired. | new file | ~130 | Nothing visible; reviewable in isolation. |
| **4** | **Wrap the five existing sections in `StatSection`**, content untouched, headlines/captions from the existing summary strings' first line. Harvests open, rest shut. Test helpers per §4. | `HarvestStatsPanel`, `DoogleMapsPanel`, test | ~80 + ~20 test | **The scroll fix.** 1350px → ~520px. Biggest perceived win, and it lands before any content is rewritten, so a regression here is unambiguous. |
| **5** | **Merge the three crop tables into `cropTable`.** Add `DataTable` coloured-cell overload. Delete `luckTable`, `table`, `rebuildLuck`. | `DataTable`, `HarvestStatsPanel` | +70 / −180 | Removes the duplicate table and the `"99th"`/`"-"` column in one move. |
| **6** | **"Show all"** — `addActionRow`, replace both `"+ N more"` rows. | `DataTable`, `HarvestStatsPanel` | ~50 | Closes the dead end. |
| **7** | **Prose → tooltips and one-line notes.** The big text edit; §2.6 table is the checklist. | `HarvestStatsPanel` | +60 / −250 | Depends on step 4's test-helper change; do not attempt earlier. |
| **8** | **Percentile decision.** Keep in `luckTooltip`, or delete `:653-675` + `percentile` + `ordinal`. | `HarvestStatsPanel` | 0 or −35 | Gated on the numbers agent. Layout-neutral either way. |
| **9** | **Low-data states.** Uncoloured `+/-` below n=20, fresh-install Bank override, thin-history captions. | `HarvestStatsPanel` | ~40 | Polish; each has a floor already in the code. |

Net: `HarvestStatsPanel` 1481 → ~1150, `DataTable` 214 → ~295, one new ~130-line file, ~20
test lines. **About −270 lines overall for a tab that shows four numbers where it showed a
paragraph.**

Steps 1 and 2 are safe to land today with no design review. Step 4 is the one to demo.
