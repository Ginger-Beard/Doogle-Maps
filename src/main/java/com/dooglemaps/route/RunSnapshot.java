package com.dooglemaps.route;

import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.data.Produce;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Value;

/**
 * Everything the run panel prices a trip with, sampled once a tick on the client thread.
 *
 * <p>The same pattern {@code GuideStatus} set for the on-screen panel, applied to the sidebar:
 * one immutable snapshot rather than five synchronized queries. The panel used to call
 * {@code previewStops}, both actionable counts, {@code ripeProduceIn} and {@code survivalIn}
 * straight from the EDT on every refresh — each one takes the planner's monitor, and
 * {@code previewStops} replans from scratch, all while the client thread walks the same
 * methods every tick. That cross-thread lock traffic is the pattern the freeze investigation
 * kept circling, and a snapshot removes it structurally rather than carefully.
 *
 * <h2>Keyed on the selection it was built for</h2>
 *
 * These are answers about <i>a particular set of tickboxes</i>, so the snapshot records which.
 * The panel uses it only when the selection still matches; in the one tick after a checkbox
 * changes — and before the first tick of a session — it falls back to asking the planner live,
 * which is exactly the old behaviour in exactly the rare moments it was acceptable.
 */
@Value
public class RunSnapshot
{
	/** The tickbox selection the answers below were computed for. */
	Set<PatchImplementation> types;

	/** The stops a run over {@link #types} would visit. */
	List<RunStop> previewStops;

	/** Actionable patch counts per type, for the reward table's gate. */
	Map<PatchImplementation, Integer> actionable;

	/** The same, per planting group — the estimate prices each group against its own seeds. */
	Map<PlantingGroup, Integer> actionableByGroup;

	/** What is ripe in each group, for pricing the harvest-only ones. */
	Map<PlantingGroup, Map<Produce, Integer>> ripeProduce;

	/** Survival odds per group, which the split exists to keep separate. */
	Map<PlantingGroup, RunEstimate.Survival> survival;
}
