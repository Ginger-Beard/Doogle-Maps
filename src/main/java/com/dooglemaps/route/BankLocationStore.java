package com.dooglemaps.route;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;

/**
 * Banks this account can actually get to.
 *
 * <p>Half the banks in the game are gated behind a quest, a skill level or having built the
 * thing, and there is no reliable way to evaluate that from the outside without a quest-name
 * table that rots. So the plugin does what it does everywhere else and learns instead: every
 * bank you open is recorded, permanently, per profile.
 *
 * <p>{@link BankLocations} provides a floor of banks that need no unlock at all, so routing
 * works on a fresh install. Learned banks are added to that set, never replacing it — the
 * result can only ever grow, and can never contain somewhere you have not stood.
 */
@Slf4j
@Singleton
public class BankLocationStore extends com.dooglemaps.state.ProfileJsonStore
{
	private static final String BANKS_KEY = "banks";

	/**
	 * Two banks closer together than this are treated as the same one.
	 *
	 * <p>Bank interfaces open from any of several tiles and from several booths, so without
	 * this the store would fill with near-duplicates of wherever the player banks most.
	 */
	private static final int SAME_BANK_DISTANCE = 12;

	private static final Type LOCATION_LIST_TYPE = new TypeToken<ArrayList<int[]>>()
	{
	}.getType();

	/** Learned banks as {x, y, plane}. */
	private final List<int[]> learned = new ArrayList<>();

	@Inject
	BankLocationStore(ConfigManager configManager, Gson gson)
	{
		super(configManager, gson, BANKS_KEY);
	}

	/**
	 * Everywhere the player could reasonably bank.
	 *
	 * <p>Handed to the router as a target set, so it picks whichever is genuinely cheapest
	 * to reach rather than whichever looks closest on the map.
	 */
	public synchronized Set<WorldPoint> getUsableBanks()
	{
		Set<WorldPoint> banks = new LinkedHashSet<>();
		for (int[] point : learned)
		{
			banks.add(new WorldPoint(point[0], point[1], point[2]));
		}
		for (BankLocations.Bank bank : BankLocations.getSeeded())
		{
			banks.add(bank.location);
		}
		return banks;
	}

	/** Where the seed vault is. There is only one, in the Farming Guild. */
	public WorldPoint getSeedVault()
	{
		return BankLocations.SEED_VAULT;
	}

	/** Records a bank the player has just used. */
	public void record(WorldPoint location)
	{
		if (location == null)
		{
			return;
		}

		synchronized (this)
		{
			for (int[] existing : learned)
			{
				if (existing[2] == location.getPlane()
					&& Math.abs(existing[0] - location.getX()) <= SAME_BANK_DISTANCE
					&& Math.abs(existing[1] - location.getY()) <= SAME_BANK_DISTANCE)
				{
					return;
				}
			}

			learned.add(new int[]{location.getX(), location.getY(), location.getPlane()});
			save();
		}
		log.debug("Learned bank at {}", location);
	}

	public synchronized int getLearnedCount()
	{
		return learned.size();
	}

	/** Forgets every bank the player has opened, leaving only the unrestricted seeded set. */
	public synchronized void clear()
	{
		learned.clear();
		unsetStored();
	}

	@Override
	protected void resetForLoad()
	{
		learned.clear();
	}

	@Override
	protected void applyJson(String json)
	{
		List<int[]> loaded = gson.fromJson(json, LOCATION_LIST_TYPE);
		if (loaded != null)
		{
			for (int[] point : loaded)
			{
				if (point != null && point.length == 3)
				{
					learned.add(point);
				}
			}
		}
	}

	@Override
	protected Object serialized()
	{
		return learned;
	}
}
