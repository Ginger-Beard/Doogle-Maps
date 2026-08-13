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

	/**
	 * Whether the model of the box has been caught contradicting the game.
	 *
	 * <p>Set when a Fill moves a seed into a box the model already had at its six kinds. The
	 * seeds are recorded anyway — see {@link #applyPendingSeedBoxAction} for why the observation
	 * outranks the model — so this does not gate the count. It marks the model as known-wrong,
	 * which is what makes a real read of the box beat the suppression window.
	 *
	 * <h2>Nothing clears this today, and that is a finding rather than a design</h2>
	 *
	 * It is cleared by a {@code SEED_BOX} container arriving, and one never has:
	 * {@code getItemContainer(573)} has answered null on every attempt of every session on
	 * record ({@code box 0 read, 56 with no container} in the run log, and zero successful
	 * reconciles across every archived log). So the recovery this flag names is unreachable,
	 * and the guard that used to rely on it was silently discarding seeds for good.
	 *
	 * <p>The suspicion is therefore surfaced rather than merely recorded — the Fill logs at WARN
	 * telling the player to open the box, and {@link #hasSeenTheBoxThisSession()} already makes
	 * {@code GuideTracker.whereSeedsAre} say the box's contents are remembered rather than seen.
	 * Whether opening the box makes the container arrive at all is unverified; see the note on
	 * {@link #relearnInventoryFromClient()}.
	 */
	private boolean boxSuspect;

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
				&& sinceAction >= 0 && sinceAction <= PENDING_BOX_ACTION_TICKS
				&& !boxCannotBeRight() && !boxSuspect
				&& boxReadIsLagged(countSeeds(container)))
			{
				return true;
			}
			if (source == SeedSource.SEED_BOX)
			{
				// Whatever it was, this read settles it.
				boxSuspect = false;
				// And it is the game showing us inside the box, which is the only thing that
				// makes the model current rather than remembered. See hasSeenTheBoxThisSession.
				boxSeenThisSession = true;
			}

			// Taking a real read of the box means the action's delta must not then be added on
			// top of it: the read already includes whatever moved. Disarming here is what keeps
			// the two paths from double-counting the same Fill.
			if (source == SeedSource.SEED_BOX)
			{
				pendingSeedBoxAction = null;
			}
		}

		Map<Integer, Integer> counts = countSeeds(container);

		if (source == SeedSource.SEED_BOX)
		{
			// Only what a box can hold. countSeeds accepts anything in the Seed table, and that
			// table maps a tree crop's SAPLING id to the crop as well as its seed - so without
			// this a sapling read into the box's model would spend one of its six kinds
			// forever. See com.dooglemaps.data.SeedBox for why this question now has exactly
			// one home.
			counts = com.dooglemaps.data.SeedBox.onlyWhatItHolds(counts);

			// Before it is stored, so the comparison is against what the deltas had made of it.
			reportBoxDrift(counts);
		}

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
	 * Whether the derived box is provably wrong, so a real read must be taken over it.
	 *
	 * <p>The box holds {@link com.dooglemaps.data.SeedBox#KINDS} kinds and no more, so a model
	 * holding a seventh is not a disagreement about counts — it is arithmetic that has drifted,
	 * and there is no reading of the game in which it is right.
	 *
	 * <p>It matters because of what the suppression above costs. The box is derived from deltas
	 * rather than read (the client's copy lags a step behind a Fill or Empty, which is the whole
	 * reason), and its own container event is ignored for a couple of ticks around an action —
	 * which is, in practice, the only time that event ever fires. So a derivation that goes wrong
	 * stays wrong: nothing reads the box again until the player happens to open it, and the
	 * suppression can eat that too. Reported from play as a box that took <i>two</i> Emptys and
	 * a check to come right, with the log showing the model at seven kinds while the game
	 * allows six.
	 *
	 * <p>So the window keeps its purpose — a lagged read must not overwrite a good derivation —
	 * and loses its teeth in the one case where the derivation cannot be good. Deliberately
	 * narrow: it is a proof, not a heuristic, and it never fires while the model is merely
	 * uncertain.
	 */
	private boolean boxCannotBeRight()
	{
		SourceCache box = cached.get(SeedSource.SEED_BOX);
		if (box == null || kindsIn(box.counts) <= com.dooglemaps.data.SeedBox.KINDS)
		{
			return false;
		}
		log.info("The seed box model holds {} kinds and the box holds {} - taking the game's "
				+ "own copy over it. Contents were {}",
			kindsIn(box.counts), com.dooglemaps.data.SeedBox.KINDS, box.counts);
		return true;
	}

	/**
	 * Whether this read of the box is the stale copy the window exists to reject.
	 *
	 * <h2>Why the window alone was not enough</h2>
	 *
	 * A lagged read is the box's contents from <b>before</b> the action — that is what "lagged"
	 * means here, and it is stated in {@link #noteSeedBoxAction}'s own comment. So the exact
	 * test is whether the read matches what the box was believed to hold at the click, and the
	 * couple of ticks was only ever a proxy for it.
	 *
	 * <p>The proxy is expensive, because a box read almost only ever arrives inside that window:
	 * a container event fires when the container changes, and a Fill or an Empty is what changes
	 * it. So the window discarded very nearly every real read the game offered, and a derivation
	 * that had gone wrong had nothing left to correct it. Reported from play twice — a box that
	 * took two Emptys and a check to come right, and then a box stuck reading full.
	 *
	 * <p>Reading it this way, a disagreement is informative rather than ambiguous. Either the
	 * read is the post-action truth, or it is the lagged copy and the model of the box before
	 * the action was already wrong. Both are the game telling us something we did not know, and
	 * in both the read is an observation where the model is an inference.
	 */
	private boolean boxReadIsLagged(Map<Integer, Integer> read)
	{
		if (boxBeforeAction == null)
		{
			// Nothing to compare against, so fall back to the window's original behaviour.
			return true;
		}
		if (read.equals(boxBeforeAction))
		{
			return true;
		}
		log.info("The seed box read {} inside the action window, where the model had {} before "
				+ "the click - taking the read, since a lagged copy would have matched.",
			read, boxBeforeAction);
		return false;
	}

	/**
	 * Says what a real read of the box disagreed with, which is the diagnostic that was missing.
	 *
	 * <p>The box is the one source that is inferred rather than observed, so it is the one that
	 * can be quietly wrong — and until now nothing compared the inference against the truth on
	 * the rare occasions the truth arrived. "I had to empty it twice and check it" is not
	 * diagnosable after the fact without this line.
	 *
	 * <p>Only on a real disagreement, so an ordinary read of an accurate box says nothing.
	 */
	private void reportBoxDrift(Map<Integer, Integer> actual)
	{
		SourceCache box;
		synchronized (this)
		{
			box = cached.get(SeedSource.SEED_BOX);
		}
		if (box == null || box.counts.equals(actual))
		{
			return;
		}
		log.info("Seed box read disagrees with what was derived from Fill/Empty deltas - "
				+ "derived {}, actually {}. The read wins; the difference is the drift.",
			box.counts, actual);
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

		SourceCache box = cached.get(SeedSource.SEED_BOX);
		boxBeforeAction = box == null
			? new HashMap<>() : new HashMap<>(box.counts);
	}

	/**
	 * What the box was believed to hold when the Fill or Empty was clicked.
	 *
	 * <p>The suppression window rejects a lagged read, and a lagged read is <i>by definition</i>
	 * the box's contents from before the action. So this is the thing the window is actually
	 * testing for, and holding it turns a guess about timing into a comparison. See
	 * {@link #boxReadIsLagged}.
	 */
	private Map<Integer, Integer> boxBeforeAction;

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
				// Whatever left the inventory on a Fill went into the box - with two things
				// the box itself cannot have done, because a Fill is not the only way a seed
				// leaves the pack inside a two-tick window. Depositing the rest of your seeds
				// at the bank a moment after filling the box, or planting one, both look
				// identical to this arithmetic, and both were being credited to the box.
				//
				// Symptom: a kind in the box with a count of one that had never been in it.
				// Once there it is nearly permanent, since a sixth kind makes the box read
				// full and nothing about a full box is provably wrong.
				for (Map.Entry<Integer, Integer> entry : previous.counts.entrySet())
				{
					int delta = entry.getValue() - incoming.getOrDefault(entry.getKey(), 0);
					if (delta <= 0)
					{
						continue;
					}

					// Already counted, exactly, by the game's own word for this same click.
					// See moveInSeedBox.
					if (statedRecently(entry.getKey()))
					{
						continue;
					}

					// A Fill takes the whole loose stack of any kind it accepts - there is no
					// per-kind limit in the box - so leftovers of that kind still in the pack
					// mean the box did not take it and something else moved it.
					if (incoming.getOrDefault(entry.getKey(), 0) > 0)
					{
						log.info("A Fill left {} of seed {} in the pack, so the {} that went "
								+ "somewhere did not go in the box.",
							incoming.get(entry.getKey()), entry.getKey(), delta);
						continue;
					}

					// A seventh kind is impossible in the BOX. It is not impossible in the
					// MODEL, and the difference is the whole of this branch.
					//
					// This used to `continue` here, dropping the seed. The reasoning was that a
					// box holding six kinds cannot take a seventh, so the seeds must have gone
					// somewhere else - which is true of the box and false of our copy of it.
					// When the model's six kinds are not the box's six, the game has just told
					// us so, and the reply was to discard the message.
					//
					// Reported from play, and it cost a whole stop: 681 limpwurt seeds filled
					// into the box, refused here, and the model went on reporting
					// `Limpwurt (inv 0, box 0, bank 0, vault 0)`. The flower run then had no
					// seed to plant, the Farming Guild's flower patch produced no step, the
					// stop completed under the player and the run routed on to Catherby.
					//
					// So the observation wins. A Fill is something the game did and we watched;
					// the model is a reconstruction from deltas. Where a reconstruction
					// contradicts an observation, it is the reconstruction that is wrong, and
					// the honest response is to record what happened and mark the model rather
					// than to keep the model and lose the seeds.
					//
					// Letting the count go over capacity is deliberate, and is the shape the
					// design already expects: SeedBox.KINDS calls itself an invariant whose
					// breach "is proof its arithmetic has drifted, which is what
					// boxCannotBeRight acts on". Refusing the merge is what stopped that proof
					// ever being recorded, so nothing downstream could act on it.
					//
					// The direction of the remaining error is the one worth having. Over-report
					// and the run plans a plant, arrives, finds no seed at hand and says where
					// it thinks they are - visible, and the player can correct it. Under-report
					// and a patch is skipped in silence. Same call docs/TODO.md makes about the
					// bottomless bucket: wrongly denying what the player has is worse than the
					// blind spot.
					if (!box.containsKey(entry.getKey())
						&& kindsIn(box) >= com.dooglemaps.data.SeedBox.KINDS)
					{
						// WARN rather than INFO: unlike the two cases above, this one says the
						// stored model is provably wrong and stays wrong until the box is read.
						log.warn("A Fill put {} of seed {} into a box this model already had at "
								+ "{} kinds - the model is wrong, not the Fill, so the seeds are "
								+ "recorded and the box is marked suspect. Open the seed box once "
								+ "to resync it.",
							delta, entry.getKey(), kindsIn(box));
						boxSuspect = true;
					}

					box.merge(entry.getKey(), delta, Integer::sum);
					moved = true;
				}
			}
			else
			{
				// Whatever appeared here on an Empty came out of the box - unless the game
				// already said so by name, in which case that figure is the exact one and this
				// would subtract it twice. See moveInSeedBox.
				for (Map.Entry<Integer, Integer> entry : incoming.entrySet())
				{
					int delta = entry.getValue() - previous.counts.getOrDefault(entry.getKey(), 0);
					if (delta > 0 && !statedRecently(entry.getKey()))
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
	 * — used to leave phantom seeds in the model until the pack happened to change.
	 *
	 * <h2>The box is reconciled here too — and the client has never once answered</h2>
	 *
	 * <p><b>This paragraph used to claim the opposite, and the claim was wrong.</b> It said the
	 * box "is a container you carry, {@code getItemContainer} answers for it exactly as it does
	 * for the pack". It does not. {@code getItemContainer(573)} —
	 * {@code InventoryID.SEED_BOX} — has returned null on every attempt, in every session on
	 * record: the run log's own counter reads {@code box 0 read, 56 with no container}, and no
	 * archived log contains a single {@code Seed box reconciled from the client} line.
	 *
	 * <p>That matters far beyond a stale comment, because two repairs were built on top of it.
	 * {@link #boxSuspect} promises the model will be corrected by "the next read of the box",
	 * and the Fill guard used to <i>discard seeds</i> on the strength of that promise. Neither
	 * can happen while the container never arrives, so the discard was permanent. See
	 * {@link #applyPendingSeedBoxAction}.
	 *
	 * <p>Why the container is absent is not yet known, and is deliberately not guessed at here.
	 * The likeliest reading is that the server sends it only while the box interface is open,
	 * and this account never opens it — {@code GuideMenuSwap.seedBoxLeftClick} puts Fill or
	 * Empty under the left click precisely so the player never has to. If that is right, the
	 * plugin's own convenience is what starves its only means of correction, which would want
	 * fixing at the source rather than here. Settling it needs one observation in the client:
	 * open the box and see whether a read lands.
	 *
	 * <p>That mistake is the whole of the seed box's history of bugs. A container the model only
	 * ever <i>derives</i> has no way back once a derivation goes wrong — and every fix so far has
	 * been to one derivation or another, each correct and none of them able to heal what had
	 * already drifted. Reported repeatedly and finally pinned with the contents written out: six
	 * ordinary herb seeds in the box, a seventh kind loose in the pack, and the box lighting to
	 * fill. No rule about kinds can produce that; only a model that disagrees with the box can.
	 *
	 * <p>So the box is read from the client every tick, like the pack. The derivation stays for
	 * the click itself, where the client's copy really does lag, and the window above is what
	 * keeps the reconcile out of the way while it does.
	 *
	 * <p>Client thread only. No-op when not logged in.
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

		// And the box - from the interface it draws, not from a container.
		//
		// This asked getItemContainer(SEED_BOX) for months and the answer was always null. The
		// counter it kept is what proved it: `box 0 read, 56 with no container` on one run, and
		// not one reconcile line in any archived log. The seed box simply has no item container,
		// so the whole path was dead and the box was only ever derived - which is what let a
		// derivation lose 681 limpwurt seeds and never get them back.
		//
		// The interface is the way in. While the box is open its contents are laid out as
		// children of HosidiusSeedbox.SEED_LAYER, one per seed, each carrying an item id and a
		// quantity - the same reading every bank-style interface gets. Found by reading how
		// Dude Where's My Stuff does it, which uses this widget and no container either; the
		// component itself is named in RuneLite's own gameval, so nothing is borrowed but the
		// idea. Credited in ATTRIBUTION.md.
		Map<Integer, Integer> now = readSeedBoxWidget();
		if (now == null)
		{
			// Closed, which is the ordinary state and not a fault: the deltas carry the box
			// between openings. Said once, because "no line in the log" is ambiguous between a
			// box that is never open and a box whose contents already match.
			boxMissing++;
			if (boxMissing == 1)
			{
				log.info("The seed box interface has not been open yet, so the box stays "
					+ "derived from its Fill, Empty and chat deltas until it is.");
			}
		}
		else
		{
			boxReads++;
			Map<Integer, Integer> before;
			synchronized (this)
			{
				SourceCache cache = cached.get(SeedSource.SEED_BOX);
				before = cache == null ? new HashMap<>() : new HashMap<>(cache.counts);
			}

			// An empty copy is not proof of an empty box, and this reconcile is a backstop
			// rather than an authority. It polls whatever the client happens to be holding; the
			// container events are the real observations. So it may correct the model and it may
			// not empty it — a tick where the client's copy has not been populated would
			// otherwise wipe the box to nothing, which reads downstream as "no kinds in it" and
			// lights the fill highlight for every loose seed in the pack. That is the
			// highlighting bug, reintroduced by the fix for the staleness bug.
			//
			// An Empty that really happened still empties it: the box's own container event
			// arrives saying so and goes through record() as before. This only declines to
			// invent one from a poll.
			if (!before.equals(now))
			{
				boxCorrections++;
				// Said out loud because this is the line that will explain the next report:
				// if the model and the box disagree, the disagreement now has a timestamp and
				// both sides written out, instead of being a state nobody can reconstruct.
				log.info("Seed box read from its interface - was {}, is {}", before, now);
			}

			// A full replacement, and an empty read is a real answer here where it was not
			// before. The old poll had to refuse an empty copy, because a container the client
			// had simply not populated is indistinguishable from an empty one and wiping the
			// model on it lit the fill highlight for every loose seed. An open interface has no
			// such ambiguity: the box is in front of the player and nothing is in it.
			//
			// The derivation is disarmed with it. Whatever a pending Fill or Empty was about to
			// infer, the box has just been looked at, so the inference can only add to a number
			// that is already right.
			synchronized (this)
			{
				pendingSeedBoxAction = null;
				boxSuspect = false;
				boxSeenThisSession = true;
			}
			if (store(SeedSource.SEED_BOX, now))
			{
				fireChanged();
			}
		}
	}

	/**
	 * The seed box's contents as its open interface shows them, or null while it is closed.
	 *
	 * <p>Null and empty are different answers and both are real: null is "not open, I cannot
	 * see", empty is "open, and there is nothing in it". Conflating them is what made the old
	 * container poll unable to trust an empty read at all.
	 *
	 * <p>Quantities come from the widget rather than being assumed, because a box slot holds a
	 * stack — the wiki's own per-stack figure is 2,147,483,647 — so the count is the whole
	 * point of reading it. Ids the box cannot hold are dropped by {@code onlyWhatItHolds} on the
	 * way into the model, the same as any other read.
	 */
	@javax.annotation.Nullable
	private Map<Integer, Integer> readSeedBoxWidget()
	{
		net.runelite.api.widgets.Widget layer =
			client.getWidget(net.runelite.api.gameval.InterfaceID.HosidiusSeedbox.SEED_LAYER);
		if (layer == null || layer.isHidden())
		{
			return null;
		}

		Map<Integer, Integer> read = new HashMap<>();
		net.runelite.api.widgets.Widget[] children = layer.getChildren();
		if (children == null)
		{
			// The layer exists but has not been populated this frame. Not an empty box.
			return null;
		}

		for (net.runelite.api.widgets.Widget child : children)
		{
			if (child == null || child.getItemId() <= 0)
			{
				continue;
			}
			// Max(1) because an interface draws a single item with no quantity text at all,
			// which reads back as zero rather than one.
			read.merge(child.getItemId(), Math.max(1, child.getItemQuantity()), Integer::sum);
		}
		return com.dooglemaps.data.SeedBox.onlyWhatItHolds(read);
	}

	/** See the counters in {@link #relearnInventoryFromClient}; volatile, read from the EDT. */
	private volatile int reconcileCalls;
	private volatile int reconcileLoggedIn;
	private volatile int reconcileStores;

	/**
	 * The box half of the same, counted separately because it can fail on its own.
	 *
	 * <p>These are the counters that caught the dead container read: {@code box 0 read} beside a
	 * large {@code with no container} is what a path that can never work looks like, and it took
	 * an absence being counted rather than merely unlogged to see it. Worth keeping pointed at
	 * the new mechanism for the same reason.
	 *
	 * <p>{@code boxIgnoredEmpty} went with that path. It counted empty container copies refused
	 * on the grounds that they might be unpopulated rather than empty — an ambiguity an open
	 * interface does not have, so there is nothing left to refuse.
	 */
	private volatile int boxReads;
	private volatile int boxMissing;
	private volatile int boxCorrections;

	/** The reconcile's progress as words, for the run-planned line. */
	public String describeReconcile()
	{
		return reconcileCalls + " ticks, " + reconcileLoggedIn + " logged in, "
			+ reconcileStores + " stored; box " + boxReads + " read from its interface, "
			+ boxMissing + " ticks with it closed, " + boxCorrections + " corrections";
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
		moveInSeedBox(itemId, quantity);
	}

	/**
	 * Takes seeds back out of the box, for the message that says the game just did.
	 *
	 * <p>The mirror of {@link #addToSeedBox}, and the reason it exists separately from the Empty
	 * derivation: <i>"Emptied 4 x Ranarr seed to your inventory."</i> names the seed and the
	 * count outright, where the derivation has to infer both from what appeared in a container
	 * and cannot tell an Empty from anything else that filled the pack in the same two ticks.
	 */
	public void removeFromSeedBox(int itemId, int quantity)
	{
		moveInSeedBox(itemId, -quantity);
	}

	/**
	 * Applies an exact, game-stated change to the box.
	 *
	 * <h2>Why this marks the seed as spoken for</h2>
	 *
	 * Some of these messages describe the very same click the Fill and Empty derivation is
	 * watching for — <i>"Stored 6 x Ranarr seed in your seed box."</i> is a Fill, and the seeds
	 * leaving the inventory is the delta {@link #applyPendingSeedBoxAction} would credit. Both
	 * firing would count them twice.
	 *
	 * <p>So the message wins for the seed it names and says so, and the derivation skips that
	 * seed for the couple of ticks the click stays armed. Per seed rather than per click,
	 * deliberately: it is not known whether a Fill announces every kind it moved or only some,
	 * and this way whatever the messages covered is exact while the rest is still inferred
	 * exactly as before. Guessing that answer either way would have been a new bug.
	 */
	private void moveInSeedBox(int itemId, int delta)
	{
		if (delta == 0 || Seed.forItemId(itemId) == null)
		{
			return;
		}

		Map<Integer, Integer> box;
		synchronized (this)
		{
			SourceCache entry = cached.get(SeedSource.SEED_BOX);
			box = entry == null ? new HashMap<>() : new HashMap<>(entry.counts);
			box.merge(itemId, delta, Integer::sum);
			// Clamped rather than trusted below zero, like the Empty derivation: a count that
			// was already stale-low must not go negative and poison the totals.
			box.values().removeIf(count -> count <= 0);
			statedByMessage.put(itemId, client.getTickCount());
		}
		if (store(SeedSource.SEED_BOX, box))
		{
			fireChanged();
		}
	}

	/**
	 * Seeds whose movement the game stated outright, by the tick it said so.
	 *
	 * <p>Read by {@link #applyPendingSeedBoxAction} so an exact figure is never added to a
	 * derived one for the same click. See {@link #moveInSeedBox}.
	 */
	private final Map<Integer, Integer> statedByMessage = new HashMap<>();

	/** Whether the game named this seed's movement inside the window the click is armed for. */
	private boolean statedRecently(int itemId)
	{
		Integer when = statedByMessage.get(itemId);
		if (when == null)
		{
			return false;
		}
		int age = client.getTickCount() - when;
		if (age < 0 || age > PENDING_BOX_ACTION_TICKS)
		{
			statedByMessage.remove(itemId);
			return false;
		}
		return true;
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
			long now = Instant.now().getEpochSecond();
			entry.lastSeen = now;
			rememberSeen(counts);
			// Saved when the counts moved, and also when the freshness stamp has drifted far
			// enough from the stored one to be worth a write on its own.
			//
			// `changed` alone was the whole condition, and it threw away the one field that
			// changes on EVERY read. Open a bank whose seed counts happen to match what was
			// stored and lastSeen is updated in memory and never persisted; restart, and the
			// store reloads the older stamp. Reported from play as "bank 2h ago" on a run
			// planned eleven minutes after the bank was open - the stamp on disk was from the
			// last time the counts themselves moved, two and a half hours earlier.
			//
			// It reads as cosmetic and is not: whereSeedsAre and the run-planned line quote
			// this to say how much to trust an answer, so a bad stamp discredits a good count
			// and lends credit to a bad one. It bites hardest during development, where the
			// plugin restarts every few minutes and in-memory stamps rarely survive.
			//
			// Throttled rather than unconditional, because the reason for the original guard is
			// still real: opening a bank fires this with a thousand items whose seed counts are
			// almost always identical, and writing config on each one is what made banking feel
			// like it stuttered. Note that the expensive half - fireChanged, which rebuilds the
			// visible tab - is gated separately by the caller on this method's return value, so
			// it is unaffected either way.
			if (source.isPersisted() && (changed || now - lastSaved >= SAVE_STAMP_SECONDS))
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

	/**
	 * Whether the game has shown us inside the seed box since the client started.
	 *
	 * <h2>Why anything has to ask</h2>
	 *
	 * The box's contents are persisted and derived from Fill and Empty deltas in between, and
	 * the client holds <b>no container for it at all</b> until the box has been opened — the
	 * reconcile says so out loud, {@code "no seed box container to reconcile against (id 573)"},
	 * and that line is what settled a night of chasing this. So the model can be a session or
	 * more old, and nothing downstream could tell the difference between "I know it is not in
	 * there" and "I have not looked since last time".
	 *
	 * <p>Reported from play as being sent away from Falador with the watermelon seeds sitting in
	 * the box. The plugin was wrong, and worse, it was wrong <i>confidently</i>. This is what
	 * lets the wording separate the two.
	 */
	public synchronized boolean hasSeenTheBoxThisSession()
	{
		return boxSeenThisSession;
	}

	/** Set by any real read of the box — its own container event, or a successful reconcile. */
	private boolean boxSeenThisSession;

	/**
	 * How many of the box's six kinds are in use.
	 *
	 * <h2>One question, one owner</h2>
	 *
	 * There were two implementations of this and the log caught them disagreeing about the same
	 * box in the same second: the Fill guard counted raw map entries with {@code box.size()} and
	 * said six, while the overlay walked the {@link Seed} table and said five. One of them
	 * gates whether a Fill is believed and the other gates whether the box lights, so a
	 * disagreement is two features acting on different beliefs about one container.
	 *
	 * <p>Counted here because this class owns the box's contents, and by the <b>seed item</b>
	 * rather than by the crop — see {@link #getSeedCount} for why {@code Seed.isSapling()}
	 * cannot answer it.
	 */
	public synchronized int kindsInTheSeedBox()
	{
		SourceCache entry = cached.get(SeedSource.SEED_BOX);
		return entry == null ? 0 : kindsIn(entry.counts);
	}

	/**
	 * The same count over a box map that has not been stored yet.
	 *
	 * <p>Only ids in the seed table count. Anything else in there is not a seed, and a box that
	 * cannot hold it has not spent a slot on it — treating an unrecognised id as a kind would
	 * close the box off for a reason nobody could look up.
	 */
	private static int kindsIn(Map<Integer, Integer> box)
	{
		return com.dooglemaps.data.SeedBox.kindsIn(
			com.dooglemaps.data.SeedBox.onlyWhatItHolds(box).keySet());
	}

	/**
	 * How many of the <b>seed</b> itself are here — never the sapling it becomes.
	 *
	 * <h2>The third question, and the one the seed box actually asks</h2>
	 *
	 * {@link #getCount} sums both forms because it answers "do I own this crop", and
	 * {@link #getPlantable} counts only the form that goes in the ground. Neither is the
	 * question a seed box has: a box takes <i>seeds</i>, including a calquat seed or an acorn,
	 * and refuses a plant pot however much of the crop it represents.
	 *
	 * <p>Without this, the box's own rules used {@code isSapling()} to skip tree crops
	 * altogether — and {@code isSapling()} is a fact about the <i>crop</i>, not about the item
	 * in front of you. A calquat seed in the box was therefore not counted as one of its six
	 * kinds, the box read as having a slot free when it did not, and the fill highlight stayed
	 * on with a seventh kind loose in the pack. Reported from play three times.
	 */
	public synchronized int getSeedCount(Seed seed, SeedSource source)
	{
		SourceCache entry = cached.get(source);
		return entry == null ? 0 : entry.counts.getOrDefault(seed.getItemID(), 0);
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

	/**
	 * How stale the persisted freshness stamp may get before a read is worth saving for it
	 * alone. See the throttle in {@link #store}.
	 */
	private static final long SAVE_STAMP_SECONDS = 60;

	/**
	 * When the blob was last written, so an unchanged read can still refresh the stamp on disk
	 * without writing config on every container event. Set in {@link #save()} rather than at
	 * the call sites, so every path that saves counts against the throttle.
	 */
	private long lastSaved;

	private synchronized void save()
	{
		lastSaved = Instant.now().getEpochSecond();
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
