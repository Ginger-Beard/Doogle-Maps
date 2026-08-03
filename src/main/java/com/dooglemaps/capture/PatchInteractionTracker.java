package com.dooglemaps.capture;

import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmRegion;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.timer.GrowthTimer;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Varbits;
import net.runelite.api.WidgetNode;
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

	/** Last raw varbit value seen per patch key, to spot transitions. */
	private final Map<String, Integer> lastVarbitValues = new HashMap<>();

	private Collection<FarmRegion> lastRegions;
	private boolean newRegionLoaded;
	private int ticksSinceModalClose;
	private boolean modalWasOpen;

	@Inject
	private PatchInteractionTracker(Client client, PatchStateStore stateStore, GrowthTimer growthTimer)
	{
		this.client = client;
		this.stateStore = stateStore;
		this.growthTimer = growthTimer;
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

		growthTimer.setAutoweed(client.getVarbitValue(Varbits.AUTOWEED));

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
			maybeObserveGrowthTick(patch, previousValue, decoded);
		}

		stateStore.recordVarbit(patch, varbitValue, decoded);
	}

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
