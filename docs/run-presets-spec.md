# Spec: named presets for the run tickboxes

Drafted 2026-08-18, **built the same day**. Kept as the record of why it is shaped this way.

Built as specced: run types only (§6), apply-on-selection (§7.1), no starter presets (§7.2),
unknown keys kept in the preset and simply not ticked (§7.3). `RunPresetStore` holds it,
`RunPanel` shows it, `RunPresetStoreTest` covers the store. Build green, 1,166 tests.

---

## 1. The problem

The run list is a column of tickboxes and it has grown. `PlantingGroups.runOptions()` offers a
line per planting group plus harvest-only variants, and the list is alphabetical *because it
outgrew being read as a whole* — that comment is already in the code.

A player does not have one circuit, they have a few. A herb run is herbs, allotments and flowers;
a tree run is trees, fruit trees and hardwoods on a slower rotation; a bins trip is neither.
Switching between them today means finding and re-ticking six or eight boxes in an alphabetical
list, every time, and getting it slightly wrong is silent — you simply plan a run that misses a
patch type.

`RunTypeStore`'s own class note already states the goal this misses:

> Most people do the same circuit every time — herbs and allotments, or trees on a slower
> rotation — and re-ticking the same boxes before every run is exactly the sort of chore a
> farming plugin ought to be removing.

It removes the chore for *one* circuit, by persisting the last state. Presets remove it for all
of them.

## 2. What the owner asked for

> presets for checkboxes, just a simple drop down with a save/delete button will suffice that
> remembers the checkbox positions, needs to be a nameable preset. So you can quickly do a herb
> run, or tree run, without having to reconfigure each time

Read as: a named set of tick states, restored in one click. Deliberately simple — a dropdown and
two buttons, not a management screen.

## 3. What a preset holds

The tickboxes are already stored in exactly the right shape, which is most of why this is small.
`RunTypeStore` persists a `Set<String>` of **`RunOption` keys** under `runTypes`, per profile,
through `ProfileJsonStore`:

> Keys because a line in that list is no longer just a type: it can be a planting group —
> protected herbs — or a mode, such as harvesting a bush without replanting it.

So a preset is a **name and a list of option keys**, and restoring one is
`runTypes.setSelected(...)` with the keys mapped back to options. Nothing new has to be modelled.

Storing keys rather than resolved options also means a preset survives the option list changing
under it: a key that no longer exists is dropped on load, the same way the store already ignores
unknown entries.

## 4. Storage

A new `ProfileJsonStore` subclass — `RunPresetStore`, key `runPresets` — holding an ordered map of
name to key list.

Per profile, matching `runTypes` itself, for the same reason: which circuits you run is a fact
about the account, and RuneLite's per-profile config is what already handles multi-account (locked
decision #7).

Inheriting `ProfileJsonStore` is not just convenience. It brings the write guard — a store that
has never read cannot write, which is what stops an empty in-memory map replacing a saved set of
presets on a profile switch. That defect has been shipped once already, in `patchLocations`.

## 5. The control

Above the tickbox list, on the run panel:

```
[ Herb run            v ]  [ Save ]  [ Delete ]
```

- **Selecting a name** applies it immediately — ticks and unticks to match, and the run list
  refreshes. Applying is not saving; the player can then adjust boxes freely.
- **Save** writes the *current* tick state under the name in the box. Typing a new name and
  pressing Save creates one; pressing it with an existing name selected overwrites it. An editable
  combo box gives both behaviours with one control, which is what "simple" asks for.
- **Delete** removes the selected preset. Nothing else changes — the tickboxes stay as they are,
  because deleting a bookmark should not change where you are standing.

The dropdown shows a blank or `(unsaved)` entry when the current ticks match no preset, so the
control never claims you are on a preset you have edited away from.

## 6. The open question: what counts as "the checkboxes"

The one thing that genuinely needs deciding, because it changes the size of the feature.

The narrow reading is **run-type tickboxes only** — the list this document has described. That is
what "so you can quickly do a herb run, or tree run" needs, and it is a few hours' work.

But a herb run arguably also implies *which herbs*, and there are three other candidates:

| Candidate | Store | Argument for including | Argument against |
|---|---|---|---|
| Run types | `RunTypeStore` | the ask | — |
| Seed selections | `SeedSelectionStore` | "a herb run" implies ranarrs | seeds change constantly as stock runs out; a preset would be stale within a week |
| Compost tier per group | `CompostSelectionStore` | part of how you run herbs | rarely changes, so rarely worth switching |
| Patch availability | `AvailabilityProfile` | — | **no.** Locked decision #11 makes availability a global invariant; a preset that switched patches off would be a second source of truth for it |

`RunTypeStore`'s own note draws the line this table suggests: it is *"kept apart from
`SeedSelectionStore` deliberately. Seeds change constantly as stock runs out; which patch types
you bother with barely changes at all."* A preset that captured seeds would go stale in exactly
the way that comment predicts.

**Recommendation: run types only, first version.** If it turns out a preset wants to carry the
compost tier too, that is one more list in the same blob and no new UI.

## 7. Smaller open questions

**7.1 Does a preset apply on selection, or on an Apply button?** Applying on selection is fewer
clicks and matches how a dropdown normally behaves; it also means a mis-click silently rewrites
the tickboxes. Undo is re-selecting the previous preset, which only works if it was saved.

**7.2 Should there be starter presets?** "Herb run" and "Tree run" pre-made would show the feature
exists. Against: they would be wrong for anyone whose circuit differs, and the plugin does not
otherwise invent choices for the player.

**7.3 What happens when a preset references an option the account cannot use?** A key for a
planting group the player has not unlocked. Dropping it silently is consistent with how
`runTypes` already loads; saying so once is friendlier and is more code.

**7.4 Is the preset list shared across patch-type tabs?** The tickboxes live on the run panel,
which is one place, so probably moot — but worth confirming the control does not need to appear
per tab the way the collapse states do.

## 8. Must not regress

- **A profile with no presets behaves exactly as today.** The control is additive; the last-used
  tick state still persists on its own through `runTypes`.
- **Applying a preset goes through `RunTypeStore.setSelected`**, not around it — that method
  carries the contract-retargeting behaviour, and a preset that wrote the blob directly would
  bypass it.
- **A running run is not re-planned underneath the player.** Changing tickboxes mid-run already
  has defined behaviour; a preset must not become a second, different way to do it.
- **No preset can switch a patch off.** Availability stays the single global invariant (§6).
