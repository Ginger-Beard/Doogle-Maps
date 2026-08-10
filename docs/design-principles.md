# Design principles

The constraints the plugin is built inside. Everything here is either settled with the owner
and not to be re-opened, or a Plugin Hub rule that removal would follow from breaking.

Distilled from `doogle-maps-plugin-spec.md`, the original design document, which was written
before any code existed and has been deleted — the rest of it described a plugin that now
exists and can be read instead. `git log` has it if the archaeology is ever wanted. What
survives here is the part that is still a *constraint* rather than a description: a
constraint you cannot derive from the code, because the code is what it produced.

---

## Locked decisions

Settled with the owner. Treat as given.

1. **Works identically for an ironman and a main.** Never suggest buying anything on the
   Grand Exchange, anywhere. Every "you need this" is answered with drops, shops, crafting
   or the leprechaun's store. `RunLoadout.toolReason` names a farming shop for a missing
   tool for exactly this reason.
2. **The patch-state cache is populated by interactions**, not by Geomancy. Plant, compost,
   protect, harvest and check-health each update it the way the Geomancy interface would.
   Geomancy access was to be an optional bulk refresh and never a requirement. The refresh was
   researched and then **dropped** — see `DEVELOPMENT.md`, *Roads not taken* — so the constraint
   is now simply met: nothing reads Geomancy, and the plugin is fully useful without 65 Magic.
3. **Capture is sequential and mirrors Geomancy's own state machine**: harvest → empty;
   compost → empty + composted; plant → planted + composted; protect → + protected; then
   growth stages.
4. **Seed choices are per patch *type*, not per location.** People plant for experience or
   for a resource across every patch, not one or two at a time. Per-location was considered
   and deprioritised. (Planting *groups* — protected herbs, contracts — later split this
   finer, but along the type axis, not the location one.)
5. **Offline progression is accounted for.** Patches grow while logged out, so state is
   recomputed from elapsed wall-clock against the global tick schedule rather than from
   ticks observed.
6. **Routing is a soft dependency.** Shortest Path is used where present; the fallback is
   the world map pinned to the patch. The plugin never requires another plugin to work.
7. **Multi-account needs nothing built.** RuneLite's per-profile config already handles it.
8. **This replaces** the farming role of Time Tracking and Lazy Farming rather than
   companioning them.
9. **Within a stop, patches are ordered nearest-first and the working patch is sticky.**
   Settled after living with it: an efficiency ordering was considered and rejected, because
   the step must not change while the player weaves between close patches — Ardougne's
   layout has you passing half the farm on every trip to and from the leprechaun, and any
   re-targeting there reads as the plugin losing its place. Deferrals (the leprechaun
   errands, and anything like them) are the sanctioned way to move work later; re-sorting
   is not.
10. **Watering growing patches is not modelled**, and the wiki mechanics are why: it is
    disease-only, one cycle per watering, on patch types whose first cycle is immune anyway —
    structurally worthless for a farmer who leaves after planting, which is what a farm run
    is. The seedling's mandatory watering is modelled (potting supplies and the bank-leg
    advice); a can is asked for only there. See the closed section in `docs/TODO.md`.
11. **Availability is a global invariant.** Each patch has a manual on/off toggle that
   persists per profile. No feature — overview, plantable, gather, routing, guided mode —
   may surface, plant into, route to or highlight a patch that is switched off. Auto-detecting
   unlocks is an optional convenience that pre-fills the toggles, never a requirement.
   `AvailabilityProfile`'s class note states this as the invariant it enforces.

## The name

**Doogle Maps** is a play on the OSRS doogle leaves item, credited to the clan member who
suggested it — see `ATTRIBUTION.md`. Because the name does not say "farming", the Plugin Hub
*description* has to carry discoverability on its own: lead with what it is, not with the
joke. The config group and package are `dooglemaps`.

## Compliance — the line that must not be crossed

The goal is the Plugin Hub, so the rule is the Hub's: stay inside the Plugin Hub and Jagex
third-party client regulations, as actually written and as actually ruled on
(RuneLite's *Rejected or Rolled Back Features* wiki page is where the rulings live).

What that means in practice:

- **No input automation, ever.** No click or movement scripting, no acting on the player's
  behalf. One click from the player is always exactly one game action. This is the part
  with no judgement calls in it.
- **Reading and drawing are unrestricted territory.** Game state in, overlays out — the
  Quest Helper pattern. The bank and vault highlight and filter, the dialog-option
  outlines, and any reuse of the Geomancy interface all live here.
- **Menu reordering is a permitted category, used within its ruling.** The rulings allow
  left-click and shift-click swaps (core's own Menu Entry Swapper is the precedent) and
  forbid *conditional menu entry removing*. `GuideMenuSwap` therefore reorders only —
  never removes, never renames — is opt-in by setting, and is scoped to the guide's own
  items (the seed box, the empty bucket) and to Guildmaster Jane. The seed box and bucket
  swaps are now standing-while-the-run-is-on rather than step-driven, which is also the
  shape a reviewer objecting to step-driven swaps would ask for; only Jane's is still
  conditional on the current step, and the feature does not depend on that cleverness.

Submission and every update draw human and AI review. A single automation-shaped feature
gets the plugin removed, so the first bullet is not a rule to be clever about — and when a
feature sits near a category boundary, the rejected-features page is checked before it is
built, not after.
