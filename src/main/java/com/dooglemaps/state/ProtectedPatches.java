package com.dooglemaps.state;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.PatchImplementation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.QuestState;
import net.runelite.api.Quest;
import net.runelite.api.Varbits;
import net.runelite.client.config.ConfigManager;

/**
 * Which herb patches this account cannot get a disease in.
 *
 * <p>Herbs only, deliberately. Hosidius's allotment and flower patches are disease-free on the
 * same unlock, and nobody plans a run around it — the crops are cheap, the loss is a few
 * potatoes, and adding them would double the size of every list here to describe something no
 * player thinks about. Herbs are where a dead patch actually costs you.
 *
 * <h2>Detected, not asked</h2>
 *
 * Each of these has an unlock that the client can be asked about directly, so none of it is
 * guesswork:
 *
 * <ul>
 *   <li><b>Trollheim</b> — <i>My Arm's Big Adventure</i></li>
 *   <li><b>Weiss</b> — <i>Making Friends with My Arm</i></li>
 *   <li><b>Hosidius</b> — Kourend &amp; Kebos <b>easy</b> diary, which is a low bar most
 *       accounts clear</li>
 *   <li><b>Harmony</b> — Morytania <b>elite</b> diary</li>
 * </ul>
 *
 * <p>{@code DiseaseRisk} previously covered only the first two, and for a different reason: it
 * inferred them from the patch being switched on at all, since neither is reachable without its
 * quest. That inference is sound but it does not extend — you can stand in Hosidius or Harmony
 * without the diary, which is exactly why they were excluded there. Asking the client removes the
 * need to infer anything.
 *
 * <h2>Civitas illa Fortis is missing on purpose</h2>
 *
 * Its herb patch is disease-free with Fortis Colosseum Champion status (16,000 Glory), and no
 * varbit for that is exposed by the API. Left out rather than guessed at: being wrong here means
 * promising a patch cannot die when it can, and the run would plant the expensive seed there.
 * Absence costs only a discount that turns out to be pessimistic.
 *
 * <h2>Why it is cached</h2>
 *
 * Quest state and diary varbits are client-thread reads, and this is asked from the panel and the
 * yield estimate, which are not on it. So it is sampled when the client is available and stored
 * per profile — the same shape as {@code FarmingBonusStore}, and for the same reason. An unlock
 * is permanent, so a stale answer is only ever out of date in the safe direction: it can miss a
 * diary finished five minutes ago, never invent one.
 */
@Slf4j
@Singleton
public class ProtectedPatches
{
	/** What makes one region's herb patch disease-free. */
	private interface Unlock
	{
		boolean check(Client client);
	}

	/**
	 * The unlocks, in bit order, each with the name it is logged under.
	 *
	 * <p>Named because "0 of 4 unlocked" is not a diagnosis. When the protected tab does not
	 * appear the question is always <i>which</i> of these read false, and whether that is the
	 * account or the read — a diary varbit sampled a moment too early looks exactly like a diary
	 * that was never done.
	 */
	private static final Map<Integer, String> HERB_UNLOCK_NAMES = new LinkedHashMap<>();

	private static final Map<Integer, Unlock> HERB_UNLOCKS = new LinkedHashMap<>();

	private static void unlock(int region, String name, Unlock check)
	{
		HERB_UNLOCK_NAMES.put(region, name);
		HERB_UNLOCKS.put(region, check);
	}

	static
	{
		unlock(11321, "Trollheim (My Arm's Big Adventure)", client ->
			Quest.MY_ARMS_BIG_ADVENTURE.getState(client) == QuestState.FINISHED);
		unlock(11325, "Weiss (Making Friends with My Arm)", client ->
			Quest.MAKING_FRIENDS_WITH_MY_ARM.getState(client) == QuestState.FINISHED);
		// Hosidius. Easy tier, not hard — worth being exact, because assuming the harder tier
		// would hide the patch from most of the accounts that actually have it.
		unlock(6967, "Hosidius (Kourend easy diary)", client ->
			client.getVarbitValue(Varbits.DIARY_KOUREND_EASY) == 1);
		unlock(15148, "Harmony (Morytania elite diary)", client ->
			client.getVarbitValue(Varbits.DIARY_MORYTANIA_ELITE) == 1);
	}

	private static final String KEY = "protectedHerbRegions";

	private final ConfigManager configManager;

	/**
	 * Told when the unlocks are first learned.
	 *
	 * <p>Needed because the panel builds its tabs when the plugin starts, and this cannot be
	 * sampled until the client thread is available and the player is logged in — which is
	 * strictly later. Without a signal the sidebar is built from "no unlocks known", decides
	 * there is nothing to split, and never revisits it: the protected tab would appear only
	 * after a manual toggle, or never.
	 */
	private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

	/** Whether the unlocks have been read at all this session. See {@link #refresh}. */
	private volatile boolean sampled;

	/**
	 * The last flags computed, so an unchanged answer costs nothing.
	 *
	 * <p>{@link #refresh} is called every tick, and without this each one would read the config —
	 * which is what the "sample once at login" version was avoiding, and the reason it was wrong.
	 * -1 is "never computed", which no real flag value can be.
	 */
	private int lastFlags = -1;

	/**
	 * Ticks since the last login, which is what paces the re-read.
	 *
	 * <h2>Why this is paced at all</h2>
	 *
	 * {@code Quest.getState} is not a varbit read — it calls {@code client.runScript}. This plugin
	 * is otherwise strictly read-only, every other client call being a getter, and running a
	 * script four times a second forever is not a thing a read-only plugin should be doing.
	 *
	 * <p>So: every tick through the window where a login is still settling, which is the whole
	 * reason the once-at-login sample was wrong, then occasionally, so a diary finished mid-session
	 * is still noticed within about a minute. The varbit reads would be cheap enough to leave
	 * running, but keeping both on one cadence is simpler than explaining why they differ.
	 */
	private int ticks;

	/** Ticks after a login during which the answer is still expected to change. */
	private static final int SETTLING_TICKS = 30;

	/** How often to look after that. 100 ticks is a minute. */
	private static final int SLOW_INTERVAL = 100;

	@Inject
	ProtectedPatches(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	/**
	 * Forgets this session's sampling, so the next login gets a fresh settling window.
	 *
	 * <p>The unlocks themselves are per profile and stay in the config; what is dropped is only
	 * the belief that they have been read, which is a fact about this session and not about the
	 * account. A different account may log in next, and its answer is certainly different.
	 */
	public void reset()
	{
		ticks = 0;
		sampled = false;
		lastFlags = -1;
	}

	public void addChangeListener(Runnable listener)
	{
		changeListeners.add(listener);
	}

	public void removeChangeListener(Runnable listener)
	{
		changeListeners.remove(listener);
	}

	/**
	 * Samples the unlocks. Must be called on the client thread.
	 *
	 * <h2>Every tick, not once at login</h2>
	 *
	 * This used to be sampled a single time, from the plugin's load, and it was wrong in a way
	 * that only showed on a real client: the load runs the instant {@code LOGGED_IN} fires, and
	 * quest and diary varbits are not all synced by then. A sample taken a second early reads
	 * zero for everything, and because it latched, the whole session then believed the account
	 * had no protected patches — the protected herb tab simply never appeared, and toggling the
	 * setting could not bring it back because the setting was never the thing that was false.
	 *
	 * <p>So it re-reads. Two quest lookups and two varbit reads a tick, and it leaves early on
	 * the overwhelmingly common case where nothing has changed, so the config is not touched and
	 * no listener is run. An unlock is permanent, so this only ever moves in one direction.
	 *
	 * <p>Writes only when the answer changes, because config writes hit the disk.
	 */
	public void refresh(Client client)
	{
		int tick = ticks++;
		if (tick >= SETTLING_TICKS && tick % SLOW_INTERVAL != 0)
		{
			return;
		}

		int flags = 0;
		int bit = 1;
		for (Map.Entry<Integer, Unlock> entry : HERB_UNLOCKS.entrySet())
		{
			if (entry.getValue().check(client))
			{
				flags |= bit;
			}
			bit <<= 1;
		}

		if (sampled && flags == lastFlags)
		{
			return;
		}
		lastFlags = flags;

		Integer stored = configManager.getRSProfileConfiguration(
			DoogleMapsConfig.GROUP, KEY, int.class);
		boolean changed = stored == null || stored != flags;
		if (changed)
		{
			configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, KEY, flags);
		}

		// Notified on the first sample of a session even when the answer has not changed. The
		// sidebar is built before this can run — the client thread and a logged-in player are
		// both needed — so on every session after the first it would otherwise have been built
		// from a store nothing had read yet, and never told the answer had arrived.
		if (changed || !sampled)
		{
			sampled = true;
			log.info("Protected herb patches: {} of {} unlocked{}",
				Integer.bitCount(flags), HERB_UNLOCKS.size(), describe(flags));

			for (Runnable listener : changeListeners)
			{
				listener.run();
			}
		}
	}

	/** Names each unlock and whether it read true, because a count alone diagnoses nothing. */
	private static String describe(int flags)
	{
		StringBuilder detail = new StringBuilder();
		int bit = 1;
		for (String name : HERB_UNLOCK_NAMES.values())
		{
			detail.append("\n  ").append((flags & bit) != 0 ? "yes  " : "no   ").append(name);
			bit <<= 1;
		}
		return detail.toString();
	}

	/**
	 * Whether this patch is a herb patch that cannot catch anything.
	 *
	 * <p>False for every other patch type, and false before the unlocks have been sampled —
	 * which errs towards treating a safe patch as risky, so an estimate is pessimistic rather
	 * than a promise that does not hold.
	 */
	public boolean isProtected(FarmPatch patch)
	{
		if (patch == null || patch.getImplementation() != PatchImplementation.HERB)
		{
			return false;
		}

		Integer stored = configManager.getRSProfileConfiguration(
			DoogleMapsConfig.GROUP, KEY, int.class);
		if (stored == null)
		{
			return false;
		}

		int bit = 1;
		for (Integer region : HERB_UNLOCKS.keySet())
		{
			if (region == patch.getRegion().getRegionId())
			{
				return (stored & bit) != 0;
			}
			bit <<= 1;
		}
		return false;
	}

	/** How many are unlocked, so the panel can say whether the category is worth showing. */
	public int count()
	{
		Integer stored = configManager.getRSProfileConfiguration(
			DoogleMapsConfig.GROUP, KEY, int.class);
		return stored == null ? 0 : Integer.bitCount(stored);
	}
}
