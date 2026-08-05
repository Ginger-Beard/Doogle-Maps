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
 * freeze investigation keeps circling. See {@code NOTES.md}.
 */
@Value
public class GuideStatus
{
	private static final GuideStatus IDLE =
		new GuideStatus(Collections.emptyList(), false, false, 0, Collections.emptyList(), null,
			null, null, Collections.emptyList());

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
}
