package com.dooglemaps.capture;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmRegion;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.guide.CarriedItems;
import com.dooglemaps.route.RunPlanner;
import com.dooglemaps.state.BarbarianFarming;
import com.dooglemaps.state.PatchSnapshot;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.validate.HarvestLog;
import com.dooglemaps.timer.GrowthTimer;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.WidgetNode;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.WidgetModalMode;
import net.runelite.client.eventbus.Subscribe;

/**
 * The primary capture path: watches patch varbits and writes what it sees to the cache.
 *
 * <p>The game sends a region's patch varbits whenever the player is in or near it, so
 * simply walking past a patch refreshes it, and acting on one (rake, compost, plant, pay,
 * harvest) walks its varbit through the states we record. No Geomancy needed — that is a
 * bulk shortcut, not a requirement.
 *
 * <p>Two things make this less trivial than "read the varbit every tick":
 * <ul>
 *   <li>Varbits are not sent while a modal interface is open, so values read then are
 *       stale. We skip those ticks entirely.</li>
 *   <li>Some regions overlap or leak values from an upper floor, handled by
 *       {@code RegionBounds}.</li>
 * </ul>
 */
@Slf4j
@Singleton
public class PatchInteractionTracker
{
	/**
	 * Ticks to wait after a modal interface closes before trusting a varbit change as a
	 * real growth tick. Closing an interface flushes queued varbits all at once, which
	 * would otherwise look like a crop advancing at that exact moment and poison the
	 * offset calculation.
	 */
	private static final int MODAL_SETTLE_TICKS = 1;

	private final Client client;
	private final PatchStateStore stateStore;
	private final GrowthTimer growthTimer;
	private final RunPlanner runPlanner;
	private final HarvestLog harvestLog;
	private final BarbarianFarming barbarianFarming;
	private final CarriedItems carried;
	private final com.dooglemaps.validate.DiseaseStatsStore diseaseStats;
	private final com.dooglemaps.state.ProtectedPatches protectedPatches;

	/** Last raw varbit value seen per patch key, to spot transitions. */
	private final Map<String, Integer> lastVarbitValues = new HashMap<>();

	private Collection<FarmRegion> lastRegions;
	private boolean newRegionLoaded;
	private int ticksSinceModalClose;
	private boolean modalWasOpen;

	@Inject
	PatchInteractionTracker(Client client, PatchStateStore stateStore,
		GrowthTimer growthTimer, RunPlanner runPlanner, HarvestLog harvestLog,
		BarbarianFarming barbarianFarming, CarriedItems carried,
		com.dooglemaps.validate.DiseaseStatsStore diseaseStats,
		com.dooglemaps.state.ProtectedPatches protectedPatches)
	{
		this.diseaseStats = diseaseStats;
		this.protectedPatches = protectedPatches;
		this.barbarianFarming = barbarianFarming;
		this.carried = carried;
		this.client = client;
		this.stateStore = stateStore;
		this.growthTimer = growthTimer;
		this.runPlanner = runPlanner;
		this.harvestLog = harvestLog;
	}

	public void reset()
	{
		lastVarbitValues.clear();
		lastRegions = null;
		newRegionLoaded = false;
		ticksSinceModalClose = 0;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			reset();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			return;
		}

		if (isModalOpen())
		{
			modalWasOpen = true;
			ticksSinceModalClose = 0;
			return;
		}

		if (modalWasOpen)
		{
			modalWasOpen = false;
			ticksSinceModalClose = 0;
		}
		else if (ticksSinceModalClose <= MODAL_SETTLE_TICKS)
		{
			ticksSinceModalClose++;
		}

		// VarbitID rather than the deprecated Varbits class. Same varbit, 5557 either way -
		// constant names move between RuneLite versions, the underlying numbers do not.
		growthTimer.setAutoweed(client.getVarbitValue(VarbitID.FARMING_BLOCKWEEDS));

		scan(client.getLocalPlayer().getWorldLocation());
	}

	/**
	 * Varbits do not arrive while a modal interface is open, so anything read during one
	 * describes the world as it was before it opened.
	 */
	private boolean isModalOpen()
	{
		for (WidgetNode node : client.getComponentTable())
		{
			if (node.getModalMode() != WidgetModalMode.NON_MODAL)
			{
				return true;
			}
		}
		return false;
	}

	private void scan(WorldPoint location)
	{
		Collection<FarmRegion> regions = FarmingWorldData.getRegionsForLocation(location);
		if (regions.isEmpty())
		{
			lastRegions = regions;
			return;
		}

		if (!regions.equals(lastRegions))
		{
			// The first scan after arriving somewhere sees a whole region's varbits change
			// at once. That is us catching up, not crops growing.
			newRegionLoaded = true;
			log.debug("Entered farming region(s) {}", regions);
		}

		for (FarmRegion region : regions)
		{
			for (FarmPatch patch : region.getPatches())
			{
				capture(patch);
			}
		}

		newRegionLoaded = false;
		lastRegions = regions;
	}

	private void capture(FarmPatch patch)
	{
		int varbitValue = client.getVarbitValue(patch.getVarbit());
		ProduceState decoded = patch.getImplementation().forVarbitValue(varbitValue);
		if (decoded == null)
		{
			// A value this patch kind has no rule for; usually means our mirrored table
			// predates a game update. Leave the cached state alone rather than wiping it.
			log.debug("Unmapped varbit value {} for {}", varbitValue, patch);
			return;
		}

		Integer previousValue = lastVarbitValues.put(patch.getKey(), varbitValue);
		if (previousValue != null && previousValue != varbitValue)
		{
			ProduceState previous = patch.getImplementation().forVarbitValue(previousValue);
			maybeObserveGrowthTick(patch, previousValue, decoded);
			// A patch that stops holding its crop has finished being harvested, which is what
			// closes a validation record.
			harvestLog.onPatchState(patch, previous, decoded);
			maybeObserveBarbarianFarming(patch, previous, decoded);
			observeDisease(patch, previous, decoded);
		}

		boolean changed = stateStore.recordVarbit(patch, varbitValue, decoded);

		// Any change at all is reported; the planner decides whether it finished a stop.
		//
		// This used to filter on !isActionable(decoded) — "has the player planted something" — and
		// that was the capture layer answering a question it is not in a position to answer. A run
		// finishes with a patch for reasons this class cannot see: a harvest-only trip never plants
		// anything, a patch with no seed allocated has nothing to plant, and a picked-clean bush is
		// still "harvestable" by any local test. Every one of those left the run unable to move on.
		//
		// Reporting the event and letting RunPlanner judge it costs a cheap check per varbit change
		// and puts the decision where the run's own definition of "done" lives.
		if (changed && previousValue != null && previousValue != varbitValue)
		{
			runPlanner.onPatchChanged(patch);
		}
	}

	/**
	 * The patch types whose seeds go in with a dibber, and so can prove the unlock.
	 *
	 * <p>Deliberately the four obvious ones rather than everything that is not a sapling. A
	 * conservative list only delays noticing the unlock until the next herb or allotment goes in,
	 * which on a farm run is minutes; a list that wrongly included a patch planted some other way
	 * would record the unlock for someone who does not have it, and then never ask for the dibber
	 * they actually need. Otto's own task names these as the example, which is a fair hint that
	 * they are the uncontroversial set.
	 */
	private static final java.util.Set<com.dooglemaps.data.PatchImplementation> DIBBER_PLANTED =
		java.util.EnumSet.of(
			com.dooglemaps.data.PatchImplementation.HERB,
			com.dooglemaps.data.PatchImplementation.ALLOTMENT,
			com.dooglemaps.data.PatchImplementation.FLOWER,
			com.dooglemaps.data.PatchImplementation.HOPS);

	/**
	 * Watches for a seed going into the ground with no dibber in the pack.
	 *
	 * <p>Which can only mean Barbarian Farming — see {@link BarbarianFarming} for why that is
	 * observed here rather than read from a varbit.
	 *
	 * <p>The transition is the narrow part: an empty patch becoming a crop at stage 0. Weeds
	 * growing back, a crop advancing, and the burst of varbits that arrives on entering a region
	 * all look different from that, and the last of those is why {@code newRegionLoaded} is
	 * checked — catching up on a patch someone else planted days ago is not a planting we saw.
	 */
	private void maybeObserveBarbarianFarming(FarmPatch patch, @Nullable ProduceState previous,
		ProduceState current)
	{
		if (newRegionLoaded
			|| barbarianFarming.isUnlocked()
			|| !DIBBER_PLANTED.contains(patch.getImplementation())
			|| previous == null)
		{
			return;
		}

		boolean wasEmpty = previous.getProduce() == Produce.WEEDS;
		boolean nowPlanted = current.getProduce() != Produce.WEEDS
			&& current.getProduce().isCrop()
			&& current.getCropState() == CropState.GROWING
			&& current.getStage() == 0;

		if (!wasEmpty || !nowPlanted)
		{
			return;
		}

		if (!carried.has(ItemID.DIBBER))
		{
			barbarianFarming.observePlantedWithoutDibber();
			return;
		}

		// Says so once per session when a planting *was* seen and a dibber was in the pack. The
		// detection is silent by design, and silence has two very different causes — nothing was
		// watched, or something was watched and the player simply had a dibber. Without this line
		// the two are indistinguishable from the log, and "it still asks me for a dibber" cannot
		// be diagnosed without guessing.
		if (!loggedPlantWithDibber)
		{
			loggedPlantWithDibber = true;
			log.info("Watched {} planted with a dibber carried, so nothing to learn about "
				+ "Barbarian Farming. If you have the unlock, tick 'Barbarian farming' "
				+ "in the settings.", current.getProduce().getName());
		}
	}

	/** So the planted-with-a-dibber note is said once a session rather than every planting. */
	private boolean loggedPlantWithDibber;

	/**
	 * Learns the growth-tick grid's phase from a transition we just watched.
	 *
	 * <p>Only transitions the game itself made count. Player actions, weeds, and the
	 * catch-up burst on entering a region all change varbits without a tick having landed.
	 */
	private void maybeObserveGrowthTick(FarmPatch patch, int previousValue, ProduceState current)
	{
		if (newRegionLoaded || ticksSinceModalClose <= MODAL_SETTLE_TICKS)
		{
			return;
		}

		ProduceState previous = patch.getImplementation().forVarbitValue(previousValue);
		if (previous == null)
		{
			return;
		}

		if (isGrowthTick(patch, previous, current))
		{
			growthTimer.observeGrowthTick(previous.getTickRate());
		}
	}

	/**
	 * Reports a state change to the disease record.
	 *
	 * <p>The snapshot is read <b>before</b> {@code recordVarbit} overwrites it, because the
	 * compost and protection that mattered are the ones this cycle was grown under. Reading them
	 * afterwards is still right today — neither is cleared by a state change — but it would stop
	 * being right the moment one of them was, and silently.
	 */
	private void observeDisease(FarmPatch patch, @Nullable ProduceState previous,
		ProduceState current)
	{
		PatchSnapshot snapshot = stateStore.get(patch);
		diseaseStats.observe(patch, previous, current,
			snapshot == null ? CompostTier.NONE : snapshot.getCompost(),
			snapshot != null && snapshot.isPatchProtected(),
			protectedPatches.isProtected(patch));
	}

	/** Whether a transition can only have been caused by a growth tick landing. */
	private static boolean isGrowthTick(FarmPatch patch, ProduceState previous, ProduceState current)
	{
		if (previous.getProduce() == Produce.WEEDS
			|| current.getProduce() == Produce.WEEDS
			|| current.getProduce() != previous.getProduce()
			|| previous.getTickRate() <= 0)
		{
			return false;
		}

		if (previous.getCropState() == CropState.GROWING)
		{
			if (current.getCropState() == CropState.GROWING
				&& current.getStage() - previous.getStage() == 1)
			{
				return true;
			}
			if (current.getCropState() == CropState.DISEASED)
			{
				return true;
			}
			// Reaching harvestable is a growth tick only when the crop ripens on its own.
			// For trees and bushes the player triggers it by checking health.
			if (current.getCropState() == CropState.HARVESTABLE
				&& !patch.getImplementation().isHealthCheckRequired())
			{
				return true;
			}
		}

		return previous.getCropState() == CropState.DISEASED && current.getCropState() == CropState.DEAD;
	}

}
