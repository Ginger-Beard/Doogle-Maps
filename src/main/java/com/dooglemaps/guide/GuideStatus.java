package com.dooglemaps.guide;

import java.util.Collections;
import java.util.List;
import lombok.Value;

/**
 * Everything the on-screen panel needs, sampled once a tick.
 *
 * <p>One immutable snapshot rather than a handful of separate getters, because the overlay draws
 * every frame and the fields have to agree with each other. Read piecemeal, a redraw landing
 * mid-update could pair "travelling" with a step list from a moment ago and print a destination
 * next to an instruction for the patch you are stood on.
 *
 * <p>The reason any of this is cached at all is the same one that made the step list cached:
 * working it out walks {@link com.dooglemaps.route.RunPlanner}, which is synchronised, and doing
 * that fifty times a second from the render thread is exactly the cross-thread lock traffic the
 * freeze investigation keeps circling. See {@code docs/NOTES.md}.
 */
@Value
public class GuideStatus
{
	private static final GuideStatus IDLE =
		new GuideStatus(Collections.emptyList(), false, false, 0, Collections.emptyList(), null,
			null, Collections.emptyList(), null, null, Collections.emptyList(),
			Collections.emptyList(), null, Collections.emptyList(), Collections.emptySet(), null);

	/** Outstanding steps at the stop you are standing in. Empty while travelling. */
	List<GuideStep> steps;

	/** Whether a run is under way at all. */
	boolean running;

	/** Whether the current leg is the supply trip rather than a patch. */
	boolean atBankLeg;

	/** Stops still to service, so the panel can say how much of the run is left. */
	int stopsRemaining;

	/**
	 * What Shortest Path says the current path uses — fairy rings, spirit trees, teleports.
	 *
	 * <p>Its own words, not ours. The plugin knows nothing about teleport unlocks; this is
	 * reported back for the path actually being drawn, which is why it can be trusted to match
	 * what the player is looking at on the map.
	 */
	List<String> transports;

	/**
	 * The stop the current path leads to, or null when that cannot be said for certain.
	 *
	 * <p>Null is a real answer here rather than a failure. Shortest Path's destination may be the
	 * one target it settled on or every target it was handed, so this is only filled in when
	 * exactly one outstanding stop matches. Saying nothing beats naming the wrong place and
	 * sending someone across the map.
	 */
	@javax.annotation.Nullable
	String destination;

	/**
	 * What is waiting at {@link #destination}, in patch types, or null when that cannot be said.
	 *
	 * <h2>Why a place name is not enough</h2>
	 *
	 * While travelling there are no steps at all — the step list is what the stop you are
	 * <i>standing in</i> wants — so the panel had only the stop's name to show, and a name only
	 * tells you the patches if you already know the farm. "Rimmington" means the bush to somebody
	 * who has run it a hundred times and nothing to anybody else; "Falador" means four different
	 * things to everyone. Reported from play as travelling for a minute without being told what
	 * was at the other end.
	 *
	 * <p>Patch <b>types</b> rather than what you will do there, deliberately. What a patch wants
	 * depends on state that changes while you travel — a crop ripens, the clock promotes a bin —
	 * so a promise made at the start of a leg can be wrong by the end of it. What is planted
	 * cannot change under you, and it is the half the place name does not carry.
	 *
	 * <p>Separate from {@link #destination} rather than folded into it, because that string is
	 * matched on: {@code GuideTracker.travelHint} keys the nexus row and the jewellery box line
	 * off the destination's name, so appending to it would quietly stop those matching.
	 */
	@javax.annotation.Nullable
	String destinationPatches;

	/**
	 * The patches at {@link #destination}, for outlining them on the way in. Empty when not
	 * travelling, and empty on the supply leg.
	 *
	 * <p>The supply leg is a leg with no steps, so it looks like travel to the overlay and used to
	 * be drawn as travel — harmless wherever the bank is a region away from the patches, and the
	 * Farming Guild's is not: banking there lit every patch in the guild while the run had not
	 * started on them. See {@code GuideTracker.patchesAhead}.
	 *
	 * <h2>Why the overlay is handed these rather than fetching them</h2>
	 *
	 * The same reason it is handed everything else here: reaching the destination stop means
	 * walking {@code RunPlanner}, which is synchronised, and the overlay draws every frame. This
	 * is sampled once on the tick thread where the monitor already lives.
	 *
	 * <p>Separate from {@link #destinationPatches}, which is the same fact as a sentence. One is
	 * read, the other is drawn, and a string is no use to an outline renderer.
	 */
	List<com.dooglemaps.data.FarmPatch> patchesAhead;

	/**
	 * What to travel with, or null when there is nothing to say — not travelling, or nothing
	 * known reaches this stop.
	 */
	@javax.annotation.Nullable
	TravelHint travelHint;

	/**
	 * The stop you are standing in, or null while travelling.
	 *
	 * <p>Distinct from {@link #destination}, which is where you are going. Both can be set, and
	 * they are never the same place.
	 */
	@javax.annotation.Nullable
	String location;

	/**
	 * What to take out of the bank, in words, or empty when that is not the question.
	 *
	 * <p>Strings rather than the loadout itself, and built in {@code LoadoutSummary} rather than
	 * here or in the overlay. This used to be a block of text in the sidebar; it is on screen now,
	 * and the plan is a side-pane checklist that shows the same steps again. Wording written
	 * inside a renderer can only ever belong to that renderer.
	 */
	List<String> supplies;

	/**
	 * What the run still wants withdrawn, with counts, for the infobox tooltip. Empty when no
	 * run is on.
	 *
	 * <p>Distinct from {@link #supplies}: that is the supply <i>leg's</i> checklist and goes
	 * quiet once the leg ends, while this stands for the whole run — a fill left in the bank
	 * mid-run belongs here and not there. Teleports are on neither; see the builder's note.
	 *
	 * <p>Here for the same reason as everything else in the snapshot: the infobox recomputes on
	 * every store change, from whichever thread fired it, and building this walks the loadout
	 * and the planner — the cross-thread lock traffic {@code RunSnapshot} exists to remove, one
	 * surface over. Sampled on the tick, where the loadout's per-tick cache means the walk has
	 * already been paid for by {@code DoogleMapsPlugin.onGameTick}'s own
	 * {@code anythingLeftToWithdraw} call. The cost is the same one every field here carries:
	 * the list can be up to one tick behind a withdrawal.
	 */
	List<String> toWithdraw;

	/**
	 * Something about the farming contract that is worth saying but cannot be clicked, or null.
	 *
	 * <p>The only thing in the snapshot that is information rather than an instruction, and it is
	 * here rather than in the step list for exactly that reason: a step nobody can perform would
	 * leave the stop reading as unfinished for the rest of the run. What it covers is the contract
	 * this trip cannot plant — the patch is still occupied, or the run was never routed past it —
	 * which is a real answer and one the player would otherwise have to infer from silence.
	 */
	@javax.annotation.Nullable
	String contractNote;

	/**
	 * Patches at this stop the run is passing over, and why.
	 *
	 * <p>Information rather than an instruction, like {@link #contractNote} — there is nothing to
	 * click, which is the whole point of the line. A patch with no seed allocated cannot be planted
	 * however long you stand there, so the run moves on; saying so is what stops that reading as
	 * the plugin having skipped something at random.
	 */
	List<String> skipped;

	/**
	 * Where the supply leg is collecting from, or empty when it is not collecting.
	 *
	 * <h2>Why the overlay is told rather than deciding</h2>
	 *
	 * It used to outline every bank booth, chest and the seed vault together, whether or not the run
	 * wanted either — so a trip needing only the vault lit every booth beside it.
	 *
	 * <p>The planner already knows: {@code getSupplyTargets} is built from exactly this set, and
	 * routes to the vault, to the banks, or to both. Sharing the answer rather than guessing at it
	 * is what stops the highlight disagreeing with the route drawn on the map — which it did, for
	 * as long as {@code GuideOverlay.marks} tried to narrow a two-container errand down to one.
	 */
	java.util.Set<com.dooglemaps.state.SeedSource> supplySources;

	/**
	 * The item the route Shortest Path is drawing actually uses, by name, or null.
	 *
	 * <p>The router's own recommendation, surfaced and never required — see
	 * {@code bank.RouteItem}. Named here so the supply leg can say "and this is how you will
	 * leave" beside the withdraw list, in the same cyan the bank marks it with.
	 */
	@javax.annotation.Nullable
	String routeItem;

	/** Nothing happening: no run, or no client. */
	public static GuideStatus idle()
	{
		return IDLE;
	}

	/**
	 * Whether the player is between stops.
	 *
	 * <p>Derived rather than stored, so it cannot disagree with the step list it is derived from.
	 */
	public boolean isTravelling()
	{
		return running && steps.isEmpty();
	}

	/**
	 * Whether the guide is asking for empty buckets right now.
	 *
	 * <p>The "drop empty buckets" arrangement rests on a bucket being spent the instant it
	 * empties: composting a patch leaves one behind, it does nothing for the rest of the run, so
	 * it is lit red and its left-click is Drop from the moment the run starts. A compost bin is
	 * the one place that is false. Emptying a bin is <b>pouring compost into empty buckets</b> —
	 * they are the tool the step needs, and marking them as litter while the guide asks for them
	 * put Drop under the cursor on the very items the next click consumes. Reported from play.
	 *
	 * <p>The emptying sequence rather than every bin step: the ash immediately precedes the first
	 * bucket, and the withdrawal is the guide fetching the buckets itself. Filling and closing a
	 * bin want produce, not buckets, so those leave the arrangement alone.
	 *
	 * <p>Derived from the step list for the same reason {@link #isTravelling()} is. Both the
	 * inventory overlay's highlight and {@code GuideMenuSwap}'s left-click ask this, and they
	 * have to answer together — a bucket lit red whose left-click is not Drop, or the reverse,
	 * is worse than either behaviour on its own.
	 */
	public boolean wantsEmptyBuckets()
	{
		for (GuideStep step : steps)
		{
			if (step.getAction() == GuideAction.EMPTY_BIN
				|| step.getAction() == GuideAction.APPLY_ASH
				|| (step.getAction() == GuideAction.WITHDRAW_TOOL
					&& step.getItemId() == net.runelite.api.gameval.ItemID.BUCKET_EMPTY))
			{
				return true;
			}
		}
		return false;
	}
}
