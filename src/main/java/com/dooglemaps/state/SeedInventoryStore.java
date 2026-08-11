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
import net.runelite.api.Experience;
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
 * <p>The inventory is the exception — it is held in memory like the rest, but <b>never
 * persisted</b>: the client sends it on login and on every change, so config would be rewritten
 * on each item pickup for something that is always about to arrive anyway.
 *
 * <p>That distinction is load-bearing rather than pedantic, and {@link #load()} turns on it. This
 * once said the inventory was "never cached", which is a different claim and a false one — and
 * following it is how {@code load()} came to clear the whole map and restore only the persisted
 * half, throwing away an inventory nothing would re-send.
 */
@Slf4j
@Singleton
public class SeedInventoryStore
{
	private static final String SEEDS_KEY = "seeds";
	private static final String EVER_SEEN_KEY = "seedsEverSeen";
	private static final String FARMING_LEVEL_KEY = "farmingLevel";

	/**
	 * Total Farming experience, cached beside the level.
	 *
	 * <p>The level alone is not enough for anything that <i>adds</i> experience and asks what
	 * level that reaches — which is what the Stats tab's plant-out projection does. Starting
	 * from {@code getXpForLevel(level)} would silently discard up to a whole level of progress
	 * before the first patch is planted, and at the top of the table that is millions.
	 */
	private static final String FARMING_XP_KEY = "farmingXp";

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

	private static final Type EVER_SEEN_TYPE = new TypeToken<java.util.HashSet<Integer>>()
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

	/**
	 * Every seed item this account has ever been seen holding, anywhere, as raw item ids.
	 *
	 * <p>Counts go to zero and their entries disappear — that is what running out is — but the
	 * seed <b>selector</b> wants the longer memory: a seed you have grown before stays listed,
	 * dulled, so your pick order survives running out and the seed is one click from rejoining
	 * the run as stock comes back. Asked from play, in exactly those words. Persisted, grown
	 * only (nothing ever leaves except a profile reset), and cheap: it changes a handful of
	 * times per account lifetime.
	 */
	private final java.util.Set<Integer> everSeen = new java.util.HashSet<>();

	/** A Fill or Empty just clicked, waiting for the container change it caused. */
	private SeedBoxAction pendingSeedBoxAction;

	/** The tick the pending action was armed on. See {@link #applyPendingSeedBoxAction}. */
	private int pendingSeedBoxTick = -1000;

	/**
	 * How long a Fill or Empty click stays armed, in ticks.
	 *
	 * <p>The container change a successful click causes lands on the same tick or the next.
	 * A click that produced nothing inside this window was a no-op — Fill with no seeds
	 * loose, Empty into a full pack — and its action must not be cashed in by whatever
	 * container change happens along later. That is exactly what happened in play: a no-op
	 * Empty stayed armed through half a farm run, and the inventory change from harvesting
	 * a patch "confirmed" it, silently zeroing a box that still held the run's seeds.
	 */
	private static final int PENDING_BOX_ACTION_TICKS = 2;
	private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

	@Inject
	SeedInventoryStore(Client client, ConfigManager configManager, Gson gson)
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

		// Not the box's own container just after a Fill or Empty. The client's copy of the
		// box lags a step behind those actions - the whole reason the box is derived from
		// deltas instead of read - and a lagged box event landing right after the derivation
		// would overwrite the correct answer with the contents from before the move. The box
		// is skipped for the couple of ticks around an action; any later event is trusted.
		synchronized (this)
		{
			// Range-checked in both directions: the tick counter restarts on a new client
			// connection, so an unsigned "recent" test against a stamp from the old counter
			// would read as recent for thousands of ticks and freeze the box's reads.
			int sinceAction = client.getTickCount() - pendingSeedBoxTick;
			if (source == SeedSource.SEED_BOX
				&& sinceAction >= 0 && sinceAction <= PENDING_BOX_ACTION_TICKS)
			{
				return true;
			}
		}

		Map<Integer, Integer> counts = countSeeds(container);

		// Both containers a box action can land in: Fill draws from the inventory, and Empty
		// tips into the inventory - or straight into the bank when one is open, which is
		// where most Emptys actually happen.
		boolean boxChanged = false;
		if (source == SeedSource.INVENTORY || source == SeedSource.BANK)
		{
			boxChanged = applyPendingSeedBoxAction(source, counts);
		}

		// Only tell anyone if something actually moved. Opening a bank fires this with a
		// thousand items whose seed counts are, almost always, exactly what we already had -
		// and a change notification rebuilds the visible tab and rewrites the config, which
		// is what made opening a bank feel like it stuttered.
		if (store(source, counts) || boxChanged)
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
		pendingSeedBoxTick = client.getTickCount();
	}

	/**
	 * Moves seeds between a container and the box for a Fill or Empty we just saw.
	 *
	 * <h2>Both directions are deltas now</h2>
	 *
	 * Empty used to assert "the box is now provably empty" and wipe it. That is only true
	 * of an Empty that succeeded in full — the game moves what fits, so Empty into a pack
	 * with two free slots moves two stacks and keeps the rest, and Empty into a full pack
	 * moves nothing at all. Worse, a no-op Empty fires no container change, so the wipe
	 * waited around for the <i>next</i> inventory event of any kind — a harvest, mid-run —
	 * and destroyed the count of a box still holding seeds. Reported from play as
	 * "skipping prifddinas - no seed" with the seeds sitting right there in the box.
	 *
	 * <p>So Empty is now the mirror of Fill: whatever seeds <b>appeared</b> in the changed
	 * container came out of the box, and only those leave the box's count. A full Empty
	 * still zeroes it — via arithmetic that is also right the rest of the time.
	 *
	 * @param source   the container that just changed; INVENTORY or BANK
	 * @param incoming what it holds now; the cached copy is still the "before"
	 * @return whether the box's counts changed
	 */
	private boolean applyPendingSeedBoxAction(SeedSource source, Map<Integer, Integer> incoming)
	{
		final SeedBoxAction action;
		synchronized (this)
		{
			if (pendingSeedBoxAction == null)
			{
				return false;
			}

			// A click that produced no container change inside the window was a no-op, and
			// this later event is unrelated to it. See PENDING_BOX_ACTION_TICKS. The age is
			// range-checked both ways: a negative age means the tick counter restarted, and
			// a stamp from the old counter must expire, not count as fresh forever.
			int age = client.getTickCount() - pendingSeedBoxTick;
			if (age < 0 || age > PENDING_BOX_ACTION_TICKS)
			{
				pendingSeedBoxAction = null;
				return false;
			}

			// Fill only ever draws from the inventory, so a bank event cannot be the change
			// it caused. Leave it armed for the inventory event that is.
			if (pendingSeedBoxAction == SeedBoxAction.FILL && source != SeedSource.INVENTORY)
			{
				return false;
			}

			action = pendingSeedBoxAction;
		}

		Map<Integer, Integer> box;
		boolean moved = false;
		synchronized (this)
		{
			SourceCache previous = cached.get(source);
			if (previous == null)
			{
				// No "before" to diff against, so nothing can be derived. The box is left
				// alone; the next time it is opened, its own container corrects it.
				return false;
			}

			SourceCache boxCache = cached.get(SeedSource.SEED_BOX);
			box = boxCache == null ? new HashMap<>() : new HashMap<>(boxCache.counts);

			if (action == SeedBoxAction.FILL)
			{
				// Whatever left the inventory on a Fill went into the box.
				for (Map.Entry<Integer, Integer> entry : previous.counts.entrySet())
				{
					int delta = entry.getValue() - incoming.getOrDefault(entry.getKey(), 0);
					if (delta > 0)
					{
						box.merge(entry.getKey(), delta, Integer::sum);
						moved = true;
					}
				}
			}
			else
			{
				// Whatever appeared here on an Empty came out of the box.
				for (Map.Entry<Integer, Integer> entry : incoming.entrySet())
				{
					int delta = entry.getValue() - previous.counts.getOrDefault(entry.getKey(), 0);
					if (delta > 0)
					{
						box.merge(entry.getKey(), -delta, Integer::sum);
						moved = true;
					}
				}
				// Clamped rather than trusted below zero: a box count that was already
				// stale-low must not go negative and poison the totals.
				box.values().removeIf(count -> count <= 0);
			}

			// Consumed only by the event that carried its seeds. An Empty at a bank can be
			// followed by an unrelated inventory refresh in the same window, and consuming
			// the click against that zero delta left the REAL delta — the box's stacks
			// landing in the bank container a tick later — unclaimed. The box then kept
			// everything it had just poured out, exactly mirroring the new bank stacks.
			// Reported from play: four seeds at box == bank to the seed. A click that never
			// meets its delta still expires on schedule, which is also what feeds the
			// box-proved-empty heal in relearnInventoryFromClient.
			if (moved)
			{
				pendingSeedBoxAction = null;
			}
		}
		return moved && store(SeedSource.SEED_BOX, box);
	}

	/**
	 * Forgets the parts of this store that belong to the session rather than the profile.
	 *
	 * <p>Called when the account is about to change — login screen, profile switch. The
	 * persisted sources are per-profile and reload correctly; the in-memory inventory is not,
	 * and {@code load()} deliberately leaves it alone (see its note on the login race), so
	 * account A's pack of seeds survived into account B's session until something disturbed
	 * B's inventory. The pending box action goes with it: it described a click on the other
	 * account's box.
	 */
	public void forgetSession()
	{
		synchronized (this)
		{
			pendingSeedBoxAction = null;
			pendingSeedBoxTick = -1000;
			cached.remove(SeedSource.INVENTORY);
		}
		fireChanged();
	}

	/**
	 * Reconciles the inventory against the live container, once a tick.
	 *
	 * <p>The same backstop {@code CarriedItems} runs and for the same reason: events keep the
	 * count sharp, but a single missed read — a priming block that never ran, a profile switch
	 * — used to leave phantom seeds in the model until the pack happened to change. The bank,
	 * vault and box stay event-and-derivation driven; the inventory is the one container that
	 * is always available to check. Client thread only. No-op when not logged in.
	 */
	public void relearnInventoryFromClient()
	{
		// The reconcile's own pulse, for the run-planned diagnostic. "Inventory never read"
		// showed up in play with container events demonstrably flowing, which this method's
		// design says cannot happen — so it now counts its own progress, and the log line can
		// separate "never called" (registration/tick problem) from "called but blocked"
		// (game state, missing container) from "storing fine" (a reading bug downstream).
		reconcileCalls++;
		if (client.getGameState() != net.runelite.api.GameState.LOGGED_IN)
		{
			return;
		}
		reconcileLoggedIn++;

		// Hands off while a box action is in flight. This reconcile goes through record(),
		// which is also where a pending Fill/Empty gets cashed in — and on the click's own
		// tick the live container can still show the PRE-action contents, so the reconcile
		// consumed the pending action against a zero delta and the real container change
		// arrived a tick later to a store that had forgotten anything was owed. An Empty
		// "moved nothing", the box kept its phantom seeds, and the guide kept asking for an
		// empty that had already happened. The window is two ticks; the reconcile waits.
		boolean boxProvedEmpty = false;
		synchronized (this)
		{
			if (pendingSeedBoxAction != null)
			{
				int age = client.getTickCount() - pendingSeedBoxTick;
				if (age >= 0 && age <= PENDING_BOX_ACTION_TICKS)
				{
					return;
				}

				// The click expired unredeemed - no container changed because nothing moved.
				// For an Empty that is itself evidence: with room in the pack for seeds to
				// land in, an Empty that moved nothing means the box holds nothing, however
				// many phantom seeds the derived count had accumulated. This is the heal for
				// a corrupted box record: click Empty once and the model matches the box.
				boxProvedEmpty = pendingSeedBoxAction == SeedBoxAction.EMPTY
					&& inventoryHasAFreeSlot();
				pendingSeedBoxAction = null;
			}
		}
		if (boxProvedEmpty && store(SeedSource.SEED_BOX, new HashMap<>()))
		{
			log.debug("An Empty moved nothing with pack space free - the seed box is empty");
			fireChanged();
		}

		ItemContainer container = client.getItemContainer(SeedSource.INVENTORY.getContainerId());
		if (container != null)
		{
			record(SeedSource.INVENTORY.getContainerId(), container);
			reconcileStores++;
		}
	}

	/** See the counters in {@link #relearnInventoryFromClient}; volatile, read from the EDT. */
	private volatile int reconcileCalls;
	private volatile int reconcileLoggedIn;
	private volatile int reconcileStores;

	/** The reconcile's progress as words, for the run-planned line. */
	public String describeReconcile()
	{
		return reconcileCalls + " ticks, " + reconcileLoggedIn + " logged in, "
			+ reconcileStores + " stored";
	}

	/** Whether the live inventory has an open slot. Client thread only. */
	private boolean inventoryHasAFreeSlot()
	{
		ItemContainer container = client.getItemContainer(SeedSource.INVENTORY.getContainerId());
		if (container == null)
		{
			return false;
		}
		int used = 0;
		for (Item item : container.getItems())
		{
			if (item != null && item.getId() > 0 && item.getQuantity() > 0)
			{
				used++;
			}
		}
		return used < 28;
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
			rememberSeen(counts);
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

	/**
	 * Caches the Farming level, and the experience behind it, so both survive a logout.
	 *
	 * <p>Only a level change fires a refresh. Experience moves on every pick, and rebuilding
	 * the panel per herb would be a refresh a second on a run — the projection that reads it is
	 * on a tab you are not looking at while farming, and it catches up on the next redraw.
	 */
	public void recordFarmingLevel()
	{
		int xp = client.getSkillExperience(Skill.FARMING);
		Integer storedXp = configManager.getRSProfileConfiguration(
			DoogleMapsConfig.GROUP, FARMING_XP_KEY, int.class);
		if (storedXp == null || storedXp != xp)
		{
			configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, FARMING_XP_KEY, xp);
		}

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
	 * Total Farming experience, from the cache when logged out. 0 if never seen.
	 *
	 * <p>Falls back to the experience the cached <b>level</b> starts at, for accounts that have
	 * been running the plugin since before this was recorded. That understates by up to a level,
	 * which is the right way to be wrong: it makes a projection built on it conservative rather
	 * than optimistic, and it corrects itself the first time Farming experience is gained.
	 */
	public int getFarmingXp()
	{
		Integer stored = configManager.getRSProfileConfiguration(
			DoogleMapsConfig.GROUP, FARMING_XP_KEY, int.class);
		if (stored != null)
		{
			return stored;
		}

		int level = getFarmingLevel();
		return level <= 0 ? 0 : Experience.getXpForLevel(level);
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
	 * Whether this account has ever held this seed, in any form, anywhere.
	 *
	 * <p>Outlives the counts on purpose — see {@link #everSeen}. Both forms are checked the
	 * way {@link #getCount} counts them: the sapling is the same crop as its seed, and having
	 * grown a maple from either form means maples belong in your list.
	 */
	public synchronized boolean hasEverSeen(Seed seed)
	{
		return everSeen.contains(seed.getItemID())
			|| (seed.isSapling() && everSeen.contains(seed.getSaplingItemID()))
			|| everSeen.contains(seed.getPlantedItemID());
	}

	/** Grows the ever-seen set from freshly read counts. Caller holds the lock. */
	private void rememberSeen(Map<Integer, Integer> counts)
	{
		boolean grew = false;
		for (Map.Entry<Integer, Integer> entry : counts.entrySet())
		{
			if (entry.getValue() > 0 && everSeen.add(entry.getKey()))
			{
				grew = true;
			}
		}
		if (grew)
		{
			configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, EVER_SEEN_KEY,
				gson.toJson(everSeen));
		}
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

	/**
	 * Reads the persisted sources back, without disturbing the ones only the client can answer.
	 *
	 * <h2>Why this does not clear everything, which it used to</h2>
	 *
	 * The inventory is deliberately not written to disk — it changes on every item pickup, and the
	 * container is sent again on login, so persisting it would be constant config writes for
	 * nothing. That reasoning is sound and the implementation quietly broke it: this cleared the
	 * <i>whole</i> cache and then restored only what config held, so the inventory was thrown away
	 * and there was nothing to put back.
	 *
	 * <p>It is a race, which is why it came and went. The container event and this load are not
	 * ordered with respect to each other:
	 *
	 * <ol>
	 *   <li>you log in, and the client sends the inventory — three ranarr seeds, cached;
	 *   <li>the RuneScape profile resolves, {@code ProfileChanged} fires, and the plugin reloads;
	 *   <li>this ran, wiped the inventory, and restored only the bank, vault and box.
	 * </ol>
	 *
	 * <p>Nothing re-reads an inventory that has not changed, so the seeds stayed invisible until
	 * something moved them — which is exactly why banking them and taking them back out fixed it,
	 * and why standing still did not. Whether step 2 landed before or after step 1 decided whether
	 * anyone ever saw it.
	 *
	 * <p>So the rule is now the honest one: <b>config is the authority on persisted sources and has
	 * nothing to say about the rest</b>. Reading it must not discard live state it cannot replace.
	 * {@link #relearnFromClient()} then re-reads whatever the client is still holding, which covers
	 * the other half — a plugin switched on mid-session was never sent the inventory at all.
	 */
	public void load()
	{
		synchronized (this)
		{
			// Only what config is about to answer for. See the note above.
			cached.keySet().removeIf(SeedSource::isPersisted);

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

			// The long memory, cleared and re-read like the persisted counts — it is per
			// profile, so another account's list must not leak in. Then backfilled from
			// whatever counts just loaded: a profile from before this key existed starts with
			// everything it currently holds, rather than an empty history.
			everSeen.clear();
			String seen = configManager.getRSProfileConfiguration(DoogleMapsConfig.GROUP,
				EVER_SEEN_KEY);
			if (seen != null && !seen.isEmpty())
			{
				try
				{
					java.util.Set<Integer> loaded = gson.fromJson(seen, EVER_SEEN_TYPE);
					if (loaded != null)
					{
						loaded.forEach(id ->
						{
							if (id != null)
							{
								everSeen.add(id);
							}
						});
					}
				}
				catch (JsonSyntaxException e)
				{
					log.warn("Discarding unreadable ever-seen seed list", e);
				}
			}
			for (SourceCache entry : cached.values())
			{
				rememberSeen(entry.counts);
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
			everSeen.clear();
			configManager.unsetRSProfileConfiguration(DoogleMapsConfig.GROUP, SEEDS_KEY);
			configManager.unsetRSProfileConfiguration(DoogleMapsConfig.GROUP, EVER_SEEN_KEY);
			configManager.unsetRSProfileConfiguration(DoogleMapsConfig.GROUP, FARMING_LEVEL_KEY);
			configManager.unsetRSProfileConfiguration(DoogleMapsConfig.GROUP, FARMING_XP_KEY);
		}
		fireChanged();
	}
}
