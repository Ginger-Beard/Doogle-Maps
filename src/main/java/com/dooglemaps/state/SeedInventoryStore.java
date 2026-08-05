package com.dooglemaps.state;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.Seed;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;

/**
 * How many of each seed the account owns, across every place seeds live.
 *
 * <p>The client only holds a bank, vault or seed box while that interface is open, so the
 * counts are cached and persisted: open the plugin on a fresh RuneLite, miles from a bank,
 * and it still knows what you have. Every visit overwrites that source with what is
 * actually there, so a stale count only survives until you next look.
 *
 * <p>The inventory is the exception — the client always has it, so it is read live and
 * never cached. Persisting it would also mean writing config on every item pickup.
 */
@Slf4j
@Singleton
public class SeedInventoryStore
{
	private static final String SEEDS_KEY = "seeds";
	private static final String FARMING_LEVEL_KEY = "farmingLevel";

	/**
	 * Woodcutting, cached the same way and for the same reason.
	 *
	 * <p>Not a farming stat, but a tree run cannot clear a grown patch without an axe, and
	 * which axe is usable depends on this. Read here rather than from the client on demand
	 * because the panel asks while repainting on the Swing thread.
	 */
	private static final String WOODCUTTING_LEVEL_KEY = "woodcuttingLevel";

	private static final Type CACHE_TYPE = new TypeToken<HashMap<String, SourceCache>>()
	{
	}.getType();

	/** One remembered container. */
	private static class SourceCache
	{
		Map<Integer, Integer> counts = new HashMap<>();
		long lastSeen;
	}

	private final Client client;
	private final ConfigManager configManager;
	private final Gson gson;

	private final Map<SeedSource, SourceCache> cached = new EnumMap<>(SeedSource.class);

	/** A Fill or Empty seen this tick, waiting for the inventory change it caused. */
	private SeedBoxAction pendingSeedBoxAction;
	private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

	@Inject
	private SeedInventoryStore(Client client, ConfigManager configManager, Gson gson)
	{
		this.client = client;
		this.configManager = configManager;
		this.gson = gson;
	}

	public void addChangeListener(Runnable listener)
	{
		changeListeners.add(listener);
	}

	public void removeChangeListener(Runnable listener)
	{
		changeListeners.remove(listener);
	}

	private void fireChanged()
	{
		for (Runnable listener : changeListeners)
		{
			listener.run();
		}
	}

	// ------------------------------------------------------------------ capture

	/**
	 * Records the contents of a container we can currently see.
	 *
	 * @return true if this was a container we care about
	 */
	public boolean record(int containerId, ItemContainer container)
	{
		SeedSource source = sourceFor(containerId);
		if (source == null || container == null)
		{
			return false;
		}

		Map<Integer, Integer> counts = countSeeds(container);
		if (source == SeedSource.INVENTORY)
		{
			applyPendingSeedBoxAction(counts);
		}

		// Only tell anyone if something actually moved. Opening a bank fires this with a
		// thousand items whose seed counts are, almost always, exactly what we already had -
		// and a change notification rebuilds the visible tab and rewrites the config, which
		// is what made opening a bank feel like it stuttered.
		if (store(source, counts))
		{
			fireChanged();
		}
		return true;
	}

	/**
	 * Notes that the player has just filled or emptied their seed box.
	 *
	 * <p>The seed box cannot simply be read. The client's copy of that container lags a step
	 * behind the action, so asking it after a Fill returns the contents from <i>before</i> the
	 * fill — which is how filling made seeds vanish and emptying made them double.
	 *
	 * <p>What the two actions do, though, is exact, and the inventory is always live. So the
	 * box is derived from the action plus the inventory delta instead of being read at all.
	 */
	public synchronized void noteSeedBoxAction(SeedBoxAction action)
	{
		pendingSeedBoxAction = action;
	}

	/**
	 * Moves seeds between the inventory and the box for a Fill or Empty we just saw.
	 *
	 * @param incoming what the inventory holds now; the cached copy is still the "before"
	 */
	private void applyPendingSeedBoxAction(Map<Integer, Integer> incoming)
	{
		final SeedBoxAction action;
		synchronized (this)
		{
			action = pendingSeedBoxAction;
			pendingSeedBoxAction = null;
		}

		if (action == null)
		{
			return;
		}

		if (action == SeedBoxAction.EMPTY)
		{
			// Empty tips the whole box into the inventory, so the box is now provably empty -
			// no arithmetic needed, and nothing left to get wrong.
			store(SeedSource.SEED_BOX, new HashMap<>());
			return;
		}

		Map<Integer, Integer> box;
		synchronized (this)
		{
			SourceCache previousInventory = cached.get(SeedSource.INVENTORY);
			SourceCache boxCache = cached.get(SeedSource.SEED_BOX);
			box = boxCache == null ? new HashMap<>() : new HashMap<>(boxCache.counts);

			if (previousInventory != null)
			{
				// Whatever left the inventory on a Fill went into the box.
				previousInventory.counts.forEach((itemId, before) ->
				{
					int moved = before - incoming.getOrDefault(itemId, 0);
					if (moved > 0)
					{
						box.merge(itemId, moved, Integer::sum);
					}
				});
			}
		}
		store(SeedSource.SEED_BOX, box);
	}

	/**
	 * Re-reads every seed container the client is still holding.
	 *
	 * <p>For use after a profile reset, where waiting for the next container event would leave
	 * the seed list blank until the player happened to open a bank. The inventory is always
	 * available; the bank, vault and box are too if they have been opened this session.
	 *
	 * <p>Deliberately <i>not</i> used during ordinary capture. The client's copy of a
	 * container lags a step behind an action that changed it, so re-reading siblings on every
	 * change reported the state from before the move — filling the seed box made seeds vanish
	 * and emptying it made them double. Here there is no action in flight to lag behind.
	 *
	 * <p>Must be called on the client thread.
	 */
	public void relearnFromClient()
	{
		for (SeedSource source : SeedSource.values())
		{
			ItemContainer container = client.getItemContainer(source.getContainerId());
			if (container != null)
			{
				store(source, countSeeds(container));
			}
		}
		fireChanged();
	}

	/**
	 * Adds seeds that went straight into the box without passing through the inventory.
	 *
	 * <p>Pickpocketing a Master Farmer with the box in your pack does exactly that: the seeds
	 * are never in the inventory, so there is no delta to derive them from, and the box's own
	 * container is not open to report them. The game says so in the chat box instead, which is
	 * the only evidence there is - and it is the same evidence core's loot tracker uses.
	 */
	public void addToSeedBox(int itemId, int quantity)
	{
		if (quantity <= 0 || Seed.forItemId(itemId) == null)
		{
			return;
		}

		Map<Integer, Integer> box;
		synchronized (this)
		{
			SourceCache entry = cached.get(SeedSource.SEED_BOX);
			box = entry == null ? new HashMap<>() : new HashMap<>(entry.counts);
			box.merge(itemId, quantity, Integer::sum);
		}
		if (store(SeedSource.SEED_BOX, box))
		{
			fireChanged();
		}
	}

	private static SeedSource sourceFor(int containerId)
	{
		for (SeedSource source : SeedSource.values())
		{
			if (source.getContainerId() == containerId)
			{
				return source;
			}
		}
		return null;
	}

	private static Map<Integer, Integer> countSeeds(ItemContainer container)
	{
		Map<Integer, Integer> counts = new HashMap<>();
		for (Item item : container.getItems())
		{
			if (item == null || item.getQuantity() <= 0)
			{
				continue;
			}
			if (Seed.forItemId(item.getId()) != null)
			{
				counts.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
		return counts;
	}

	/**
	 * Replaces one source's counts.
	 *
	 * <p>The timestamp is always refreshed - "seen just now" is true even when the contents
	 * are identical, and the tooltip says so. Only a real change is worth serialising and
	 * repainting for.
	 *
	 * @return true if the counts differ from what was already held
	 */
	private boolean store(SeedSource source, Map<Integer, Integer> counts)
	{
		boolean changed;
		synchronized (this)
		{
			SourceCache entry = cached.computeIfAbsent(source, k -> new SourceCache());
			changed = !entry.counts.equals(counts);
			entry.counts = counts;
			entry.lastSeen = Instant.now().getEpochSecond();
			if (changed && source.isPersisted())
			{
				save();
			}
		}

		if (changed)
		{
			log.debug("Cached {} seed types from {}", counts.size(), source);
		}
		return changed;
	}

	/** Caches the Farming level so the plugin can filter seeds while logged out. */
	public void recordFarmingLevel()
	{
		int level = client.getRealSkillLevel(Skill.FARMING);
		Integer stored = configManager.getRSProfileConfiguration(
			DoogleMapsConfig.GROUP, FARMING_LEVEL_KEY, int.class);
		if (stored == null || stored != level)
		{
			configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, FARMING_LEVEL_KEY, level);
			fireChanged();
		}
	}

	/** Records the Woodcutting level. Client thread only, same as the Farming one. */
	public void recordWoodcuttingLevel()
	{
		int level = client.getRealSkillLevel(Skill.WOODCUTTING);
		Integer stored = configManager.getRSProfileConfiguration(
			DoogleMapsConfig.GROUP, WOODCUTTING_LEVEL_KEY, int.class);
		if (stored == null || stored != level)
		{
			configManager.setRSProfileConfiguration(
				DoogleMapsConfig.GROUP, WOODCUTTING_LEVEL_KEY, level);
			fireChanged();
		}
	}

	// -------------------------------------------------------------------- reads

	/** Woodcutting level, for deciding which axe a tree run can actually use. 0 if unknown. */
	public int getWoodcuttingLevel()
	{
		Integer stored = configManager.getRSProfileConfiguration(
			DoogleMapsConfig.GROUP, WOODCUTTING_LEVEL_KEY, int.class);
		return stored == null ? 0 : stored;
	}

	/** Farming level, from the cache when logged out. 0 if never seen. */
	public int getFarmingLevel()
	{
		Integer stored = configManager.getRSProfileConfiguration(
			DoogleMapsConfig.GROUP, FARMING_LEVEL_KEY, int.class);
		return stored == null ? 0 : stored;
	}

	/**
	 * How many of a crop are in one place, counting seeds and saplings alike.
	 *
	 * <p>Answered from memory, never from the client. The panel calls this while repainting
	 * on the Swing thread, and {@code Client.getItemContainer} asserts it is on the client
	 * thread — reading through would throw on every tab click.
	 *
	 * <p>Both forms count as owning a tree, which is the question this answers. Whether they
	 * can be put in the ground <i>today</i> is {@link #getPlantable}: a tree seed has to spend
	 * time in a plant pot first.
	 */
	public synchronized int getCount(Seed seed, SeedSource source)
	{
		SourceCache entry = cached.get(source);
		if (entry == null)
		{
			return 0;
		}
		return entry.counts.getOrDefault(seed.getItemID(), 0)
			+ (seed.isSapling() ? entry.counts.getOrDefault(seed.getSaplingItemID(), 0) : 0);
	}

	/**
	 * How many can actually go in the ground.
	 *
	 * <p>The same as {@link #getCount} for everything but trees, where only the sapling is
	 * plantable — a shed full of acorns is not a tree run. Kept apart rather than replacing
	 * the total, because owning the seed is still worth showing: it is what you take to a
	 * plant pot.
	 */
	public synchronized int getPlantable(Seed seed, SeedSource source)
	{
		SourceCache entry = cached.get(source);
		return entry == null ? 0 : entry.counts.getOrDefault(seed.getPlantedItemID(), 0);
	}

	/** How many of a crop the account owns in total, seeds and saplings together. */
	public int getOwned(Seed seed)
	{
		int total = 0;
		for (SeedSource source : SeedSource.values())
		{
			total += getCount(seed, source);
		}
		return total;
	}

	/** How many the account could plant right now, across every place it keeps them. */
	public int getOwnedPlantable(Seed seed)
	{
		int total = 0;
		for (SeedSource source : SeedSource.values())
		{
			total += getPlantable(seed, source);
		}
		return total;
	}

	/** When a source was last read, or 0 if never. */
	public synchronized long getLastSeen(SeedSource source)
	{
		SourceCache entry = cached.get(source);
		return entry == null ? 0 : entry.lastSeen;
	}

	/**
	 * Whether any storage has ever been read.
	 *
	 * <p>Drives the panel's first-run prompt: with nothing cached we cannot tell "you own
	 * no seeds" from "we have never looked", and saying the former would be a lie.
	 */
	public synchronized boolean hasEverBeenPopulated()
	{
		for (SeedSource source : SeedSource.values())
		{
			// Deliberately only the stored sources: an inventory holding one seed does not
			// mean we know what the account owns.
			if (source.isPersisted() && cached.containsKey(source))
			{
				return true;
			}
		}
		return false;
	}

	// -------------------------------------------------------------- persistence

	public void load()
	{
		synchronized (this)
		{
			cached.clear();

			String json = configManager.getRSProfileConfiguration(DoogleMapsConfig.GROUP, SEEDS_KEY);
			if (json != null && !json.isEmpty())
			{
				try
				{
					Map<String, SourceCache> loaded = gson.fromJson(json, CACHE_TYPE);
					if (loaded != null)
					{
						loaded.forEach((name, entry) ->
						{
							try
							{
								SeedSource source = SeedSource.valueOf(name);
								if (source.isPersisted() && entry != null && entry.counts != null)
								{
									cached.put(source, entry);
								}
							}
							catch (IllegalArgumentException e)
							{
								// A source that no longer exists; drop it.
							}
						});
					}
				}
				catch (JsonSyntaxException e)
				{
					log.warn("Discarding unreadable seed cache", e);
				}
			}
		}
		fireChanged();
	}

	private synchronized void save()
	{
		Map<String, SourceCache> out = new HashMap<>();
		cached.forEach((source, entry) ->
		{
			if (source.isPersisted())
			{
				out.put(source.name(), entry);
			}
		});
		configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, SEEDS_KEY, gson.toJson(out));
	}

	public void clear()
	{
		synchronized (this)
		{
			cached.clear();
			configManager.unsetRSProfileConfiguration(DoogleMapsConfig.GROUP, SEEDS_KEY);
			configManager.unsetRSProfileConfiguration(DoogleMapsConfig.GROUP, FARMING_LEVEL_KEY);
		}
		fireChanged();
	}
}
