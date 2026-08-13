package com.dooglemaps.state;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.ProduceState;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * The cached state of every farming patch, and the only thing that writes it.
 *
 * <p>Capture components hand states in here; the panel and timers read them back out.
 * Everything is persisted per RuneScape profile, so multiple accounts need no special
 * handling.
 */
@Slf4j
@Singleton
public class PatchStateStore extends ProfileJsonStore
{
	/** Config key holding the serialised snapshot map. */
	private static final String PATCHES_KEY = "patches";

	/** Core Time Tracking's config group, which we read once to seed a new install. */
	private static final String TIMETRACKING_GROUP = "timetracking";
	private static final String TIMETRACKING_COMPOST_SUFFIX = ".compost";
	private static final String TIMETRACKING_PROTECTED_SUFFIX = ".protected";

	private static final Type SNAPSHOT_MAP_TYPE = new TypeToken<HashMap<String, PatchSnapshot>>()
	{
	}.getType();

	/** Keyed by {@link FarmPatch#getKey()}. */
	private final Map<String, PatchSnapshot> snapshots = new HashMap<>();

	private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

	/**
	 * Depth of open {@link #asOneWrite} calls. Above zero, a change is noted and held for the
	 * flush rather than written where it happened. A depth rather than a flag so a nested
	 * batch cannot flush its caller's work early.
	 */
	private int batchDepth;

	/** Whether anything actually changed inside the open batch, so the flush knows to write. */
	private boolean batchDirty;

	@Inject
	PatchStateStore(ConfigManager configManager, Gson gson)
	{
		super(configManager, gson, PATCHES_KEY);
	}

	/** Registers a callback fired after any change, used to repaint the panel. */
	public void addChangeListener(Runnable listener)
	{
		changeListeners.add(listener);
	}

	public void removeChangeListener(Runnable listener)
	{
		changeListeners.remove(listener);
	}

	/**
	 * Notifies listeners. Must never be called while holding this store's monitor:
	 * listeners read back through {@link AvailabilityProfile}, which takes its own lock
	 * and then ours, so firing under the lock would invert that order and can deadlock.
	 */
	private void fireChanged()
	{
		for (Runnable listener : changeListeners)
		{
			listener.run();
		}
	}

	// ------------------------------------------------------------------ reads

	/**
	 * A patch's last known state, or null if we have never seen it.
	 *
	 * <h2>A copy, and the lock alone is not why</h2>
	 *
	 * This used to hand back the stored object. {@code synchronized} then protected the map
	 * lookup and nothing that mattered: {@link PatchSnapshot} is mutable, {@link #applyVarbit}
	 * rewrites seven of its fields in place, and once the reference has escaped every reader is
	 * reading those fields with no lock at all.
	 *
	 * <p>The readers are on other threads and there are a lot of them — the sidebar rows and the
	 * summary on Swing, the run planner and the guide on the client thread, all of them going
	 * through {@code GrowthTimer.project}, which reads produce, crop state, stage and last-seen as
	 * four separate loads. Nothing made those four agree. A projection could pair the previous
	 * crop with the new stage, and a repaint landing mid-write could draw a patch that had briefly
	 * lost its compost, because {@code applyVarbit} clears compost and protection before it
	 * returns.
	 *
	 * <p>Nothing threw, which is what made it the wrong kind of bug: it produced a wrong number in
	 * the almanac, rarely, and never the same way twice. Copying under the lock is what makes the
	 * four fields a snapshot rather than a running commentary.
	 */
	@Nullable
	public synchronized PatchSnapshot get(FarmPatch patch)
	{
		return copyOf(snapshots.get(patch.getKey()));
	}

	@Nullable
	public synchronized PatchSnapshot get(String patchKey)
	{
		return copyOf(snapshots.get(patchKey));
	}

	/**
	 * Whether this patch has ever been seen, without building a snapshot to find out.
	 *
	 * <p>Exists because {@link #get(FarmPatch)} now copies, and {@code AvailabilityProfile} asked
	 * it only to compare against null — once per patch, inside a loop that runs on every panel
	 * refresh. Copying a snapshot to throw it away is the one place the copy would have cost
	 * something worth measuring.
	 */
	public synchronized boolean hasSeen(FarmPatch patch)
	{
		return snapshots.containsKey(patch.getKey());
	}

	/** Every patch we have ever seen. Copies, for the reason {@link #get(FarmPatch)} gives. */
	public synchronized Collection<PatchSnapshot> getAll()
	{
		List<PatchSnapshot> copies = new ArrayList<>(snapshots.size());
		for (PatchSnapshot snapshot : snapshots.values())
		{
			copies.add(copyOf(snapshot));
		}
		return copies;
	}

	/**
	 * One snapshot, detached from the cache.
	 *
	 * <p>Field by field rather than through a copy constructor, so adding a field to
	 * {@link PatchSnapshot} without adding it here is a compile-time argument-count error rather
	 * than a silently dropped value.
	 */
	@Nullable
	private static PatchSnapshot copyOf(@Nullable PatchSnapshot snapshot)
	{
		if (snapshot == null)
		{
			return null;
		}
		return new PatchSnapshot(
			snapshot.getPatchKey(),
			snapshot.getVarbitValue(),
			snapshot.getProduce(),
			snapshot.getCropState(),
			snapshot.getStage(),
			snapshot.getCompost(),
			snapshot.isPatchProtected(),
			snapshot.getLastSeen());
	}

	// ----------------------------------------------------------------- writes

	/**
	 * Runs a burst of recording and persists all of it with a single write at the end.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * A session's config log held 93 writes of the {@code patches} blob, 18.5KB apiece — 85% of
	 * every serialised byte this plugin produced — arriving in bursts on the second: ten writes
	 * at 22:41:42, immediately after "Entered farming region(s) [Kourend]", nine more at
	 * 22:42:08, nine at 22:42:09. That is {@code PatchInteractionTracker}'s region scan. It walks
	 * every patch of every loaded region, and the first scan after arriving somewhere finds all
	 * of them changed at once because it is catching up on days of growth — a fact that class
	 * already recognises for growth-tick purposes and did not act on for writes.
	 *
	 * <p>The cost is not the bytes. {@link ConfigManager} posts {@code ConfigChanged}
	 * <b>synchronously</b> into every subscriber in the client, so ten changed patches meant ten
	 * trips through every other installed plugin's config handling, on the client thread, inside
	 * one tick — the fan-out {@code ProfileJsonStore.save} exists to keep off a held monitor.
	 * One blob describing ten changed patches says everything ten blobs said.
	 *
	 * <p>{@code work} runs <b>outside</b> this store's monitor, and the flush is in a
	 * {@code finally}: a scan that returns early or throws part-way still persists what it
	 * managed to record. Silently losing a region's capture to one unmapped varbit would be a
	 * worse bug than the one this fixes.
	 *
	 * <p>Nothing is required to use this. A {@link #recordVarbit} outside a batch still writes
	 * before it returns, which is what its many callers rely on.
	 */
	public void asOneWrite(Runnable work)
	{
		openBatch();
		try
		{
			work.run();
		}
		finally
		{
			closeBatch();
		}
	}

	private synchronized void openBatch()
	{
		batchDepth++;
	}

	/** Closes one batch, and writes once if it was the outermost and anything moved. */
	private void closeBatch()
	{
		boolean flush;
		synchronized (this)
		{
			flush = --batchDepth == 0 && batchDirty;
			if (flush)
			{
				batchDirty = false;
			}
		}

		if (flush)
		{
			// Outside the monitor, for the reason persistChange gives.
			save();
			fireChanged();
		}
	}

	/**
	 * Persists and announces a change — now, or once at the end of the open batch.
	 *
	 * <p>Called outside {@code applyVarbit}'s monitor, deliberately, like fireChanged always
	 * was. The save posts ConfigChanged through the EventBus, and posting into arbitrary
	 * subscriber code with this store's monitor held deadlocked the client against a Swing panel
	 * refresh holding AvailabilityProfile — see ProfileJsonStore.save.
	 */
	private void persistChange()
	{
		if (heldForBatch())
		{
			return;
		}

		save();
		fireChanged();
	}

	/**
	 * Marks the open batch dirty and reports whether there is one.
	 *
	 * <p>Split out so it is the only lock {@link #persistChange} takes: the save that follows a
	 * false answer is then provably not under this monitor, which is the whole rule here.
	 */
	private synchronized boolean heldForBatch()
	{
		if (batchDepth == 0)
		{
			return false;
		}

		batchDirty = true;
		return true;
	}

	/**
	 * Records a patch's contents as decoded from its varbit.
	 *
	 * <p>This is the sequential state machine of the spec: as the player harvests,
	 * composts, plants and pays, the varbit walks through those states and we follow it.
	 * Compost and protection are tracked separately (the varbit does not carry them) and
	 * are cleared here whenever the patch reaches a state that discards them.
	 *
	 * <p>A change is on disk by the time this returns, unless an {@link #asOneWrite} batch is
	 * open on this store, in which case it is on disk by the time that batch closes. Note the
	 * wording: the batch is store-global, not per caller, so a write made while <i>someone
	 * else</i> holds one is deferred to that batch's close too.
	 *
	 * @return true if anything actually changed
	 */
	public boolean recordVarbit(FarmPatch patch, int varbitValue, ProduceState decoded)
	{
		boolean changed = applyVarbit(patch, varbitValue, decoded);
		if (changed)
		{
			persistChange();
		}
		return changed;
	}

	private synchronized boolean applyVarbit(FarmPatch patch, int varbitValue, ProduceState decoded)
	{
		PatchSnapshot snapshot = snapshots.computeIfAbsent(patch.getKey(), PatchStateStore::blank);
		Produce previousProduce = snapshot.getProduce();

		boolean changed = snapshot.getVarbitValue() != varbitValue
			|| snapshot.getProduce() != decoded.getProduce()
			|| snapshot.getCropState() != decoded.getCropState()
			|| snapshot.getStage() != decoded.getStage();

		snapshot.setVarbitValue(varbitValue);
		snapshot.setProduce(decoded.getProduce());
		snapshot.setCropState(decoded.getCropState());
		snapshot.setStage(decoded.getStage());
		snapshot.setLastSeen(Instant.now().getEpochSecond());

		// Compost and protection expire at different moments, and conflating them loses real
		// information.
		//
		// Protection buys immunity from disease, which is a growing-phase concern. Once the
		// crop is ripe it can no longer catch anything, so the payment is spent.
		//
		// Compost is not spent then. Its main effect is extra harvest lives, and those are
		// used one per pick — that is, entirely *after* the crop turns harvestable. Clearing
		// it at that point drops the treatment exactly when the yield estimate needs it, and
		// understates an ultracomposted patch by three items.
		//
		// So compost survives until the crop itself is gone. That cannot be spotted from the
		// state alone, because an emptied allotment reads as weeds and a freshly raked one
		// does too — and composting before planting is the normal order of play, so treating
		// weeds as "forget the treatment" would wipe it a tick after it was applied. It is
		// the *transition* from holding a crop to not holding one that ends the cycle.
		boolean cropJustLeft = previousProduce != null && previousProduce.isCrop()
			&& !decoded.getProduce().isCrop();

		if (cropJustLeft)
		{
			changed |= snapshot.getCompost() != CompostTier.NONE;
			snapshot.setCompost(CompostTier.NONE);
		}

		if (cropJustLeft || decoded.getCropState() == CropState.HARVESTABLE)
		{
			changed |= snapshot.isPatchProtected();
			snapshot.setPatchProtected(false);
		}

		return changed;
	}

	public void recordCompost(FarmPatch patch, CompostTier tier)
	{
		if (applyCompost(patch, tier))
		{
			// Same shape as recordVarbit, same reason: never save under the monitor.
			persistChange();
		}
	}

	private synchronized boolean applyCompost(FarmPatch patch, CompostTier tier)
	{
		PatchSnapshot snapshot = snapshots.computeIfAbsent(patch.getKey(), PatchStateStore::blank);
		if (snapshot.getCompost() == tier)
		{
			return false;
		}

		log.debug("Compost {} recorded for {}", tier, patch);
		snapshot.setCompost(tier);
		snapshot.setLastSeen(Instant.now().getEpochSecond());
		return true;
	}

	/**
	 * Fills in protection and compost we never saw, from Time Tracking's own record.
	 *
	 * <p>Our capture only knows what it watched happen, so a patch paid for or composted before
	 * this plugin was installed reads as bare forever. Time Tracking has been recording both for
	 * years and stores them per profile. See {@link TimeTrackingState}.
	 *
	 * <p><b>Only fills gaps.</b> Anything we have observed ourselves wins, because ours is live
	 * and theirs is whatever was last written — so this never overwrites a fact, only supplies a
	 * missing one.
	 *
	 * <h2>Not only on load, and that was the bug</h2>
	 *
	 * This ran once, at login, on the reasoning that it "answers a question about the past, and
	 * the past does not change". The premise is right and the conclusion does not follow: what
	 * changes is <i>our</i> answer. Reported from play at the Great Conch — the guide asked for a
	 * protection payment on a coral patch that had not even been harvested, and asked for it on
	 * both nurseries at once.
	 *
	 * <p>The session's own records settle who was wrong. Time Tracking held
	 * {@code 12581.4771.protected=true} and {@code .4772.protected=true} throughout, while this
	 * store flipped both to false in a single write at 12:15:56 — with the varbit unchanged at
	 * 4 (GROWING), no coral harvest anywhere in the log, and the player standing in Ardougne at
	 * the time. Our flag was wrong and the game's record proved it, but nothing ever asked again.
	 *
	 * <p>So the reconciliation runs on region entry as well. It is a read of the client's own
	 * config and it can only ever restore a payment the player genuinely made, which makes it
	 * safe to repeat: the failure it heals costs real money, and the failure it could cause —
	 * believing a patch is protected when it is not — is one it is structurally incapable of,
	 * because it never sets false.
	 *
	 * <p>It does <b>not</b> explain how the flag was lost. Nothing in this plugin sets protection
	 * false except {@link #applyVarbit}'s spent-payment rule, which needs the crop to leave or
	 * turn HARVESTABLE — neither happened — and {@code applyProtected}, which only ever receives
	 * true from {@code ProtectionCapture}. That is still open; this stops it costing anything.
	 */
	public void backfillFrom(TimeTrackingState timeTracking)
	{
		int filled = 0;
		synchronized (this)
		{
			for (FarmPatch patch : FarmingWorldData.getAllPatches())
			{
				PatchSnapshot snapshot = snapshots.get(patch.getKey());
				if (snapshot == null)
				{
					// Never seen the patch at all. Recording compost for somewhere we have no
					// state for would invent a patch we cannot say anything else about.
					continue;
				}

				// Only while a crop is standing in a state the fact could still be true for.
				// "Fills gaps" used to mean any absence — but an absence is also exactly what
				// our own expiry produces: protection clears when the crop leaves or turns
				// HARVESTABLE (the payment is spent), compost clears with the crop. Time
				// Tracking's key lifecycle is not ours to guarantee, so an unconditional fill
				// resurrected spent protection on every reload — a promise of disease safety
				// that no longer held, which is the wrong direction to be wrong in.
				boolean protectable = snapshot.getCropState() == com.dooglemaps.data.CropState.GROWING
					|| snapshot.getCropState() == com.dooglemaps.data.CropState.DISEASED;

				Boolean paid = timeTracking.isProtected(patch);
				if (paid != null && paid && protectable && !snapshot.isPatchProtected())
				{
					snapshot.setPatchProtected(true);
					filled++;
				}

				CompostTier tier = timeTracking.compost(patch);
				if (tier != null && protectable && snapshot.getCompost() == CompostTier.NONE
					&& tier != CompostTier.NONE)
				{
					snapshot.setCompost(tier);
					filled++;
				}
			}

		}

		if (filled > 0)
		{
			save();
			log.info("Filled in {} compost and protection facts from Time Tracking", filled);
			fireChanged();
		}
	}

	public void recordProtected(FarmPatch patch, boolean isProtected)
	{
		if (applyProtected(patch, isProtected))
		{
			persistChange();
		}
	}

	private synchronized boolean applyProtected(FarmPatch patch, boolean isProtected)
	{
		PatchSnapshot snapshot = snapshots.computeIfAbsent(patch.getKey(), PatchStateStore::blank);
		if (snapshot.isPatchProtected() == isProtected)
		{
			return false;
		}

		log.debug("Protection {} recorded for {}", isProtected, patch);
		snapshot.setPatchProtected(isProtected);
		snapshot.setLastSeen(Instant.now().getEpochSecond());
		return true;
	}

	private static PatchSnapshot blank(String patchKey)
	{
		PatchSnapshot snapshot = new PatchSnapshot();
		snapshot.setPatchKey(patchKey);
		snapshot.setCompost(CompostTier.NONE);
		return snapshot;
	}

	// ----------------------------------------------------------- persistence

	@Override
	protected void resetForLoad()
	{
		snapshots.clear();
	}

	@Override
	protected void applyJson(String json)
	{
		Map<String, PatchSnapshot> loaded = gson.fromJson(json, SNAPSHOT_MAP_TYPE);
		if (loaded != null)
		{
			// Drop keys for patches that no longer exist, e.g. after a data update
			// moved a patch to a different region.
			loaded.forEach((key, snapshot) ->
			{
				if (FarmingWorldData.getPatch(key) != null && snapshot != null)
				{
					snapshot.setPatchKey(key);
					if (snapshot.getCompost() == null)
					{
						snapshot.setCompost(CompostTier.NONE);
					}
					snapshots.put(key, snapshot);
				}
			});
		}
	}

	/** The backfill runs blob or no blob - a fresh install is its whole reason to exist. */
	@Override
	protected void afterLoad()
	{
		int backfilled = backfillFromTimeTracking();
		if (backfilled > 0)
		{
			log.debug("Backfilled {} patches from core Time Tracking", backfilled);
			save();
		}
	}

	@Override
	protected Object serialized()
	{
		return snapshots;
	}

	@Override
	protected void loaded()
	{
		fireChanged();
	}

	/**
	 * Seeds patches we have never seen from core Time Tracking's own cache.
	 *
	 * <p>Time Tracking is on by default and has usually been quietly recording the same
	 * varbits for as long as the account has existed, so a fresh install of this plugin
	 * can start with a populated overview instead of an empty one. Read-only, and only
	 * ever fills gaps — anything we have captured ourselves wins.
	 *
	 * @return how many patches were seeded
	 */
	private int backfillFromTimeTracking()
	{
		int seeded = 0;
		for (FarmPatch patch : FarmingWorldData.getAllPatches())
		{
			if (snapshots.containsKey(patch.getKey()))
			{
				continue;
			}

			String stored = configManager.getRSProfileConfiguration(TIMETRACKING_GROUP, patch.getKey());
			if (stored == null)
			{
				continue;
			}

			// Stored as "<varbitValue>:<unixSeconds>".
			String[] parts = stored.split(":");
			if (parts.length != 2)
			{
				continue;
			}

			final int varbitValue;
			final long lastSeen;
			try
			{
				varbitValue = Integer.parseInt(parts[0]);
				lastSeen = Long.parseLong(parts[1]);
			}
			catch (NumberFormatException e)
			{
				continue;
			}

			ProduceState decoded = patch.getImplementation().forVarbitValue(varbitValue);
			if (decoded == null || lastSeen <= 0)
			{
				continue;
			}

			PatchSnapshot snapshot = blank(patch.getKey());
			snapshot.setVarbitValue(varbitValue);
			snapshot.setProduce(decoded.getProduce());
			snapshot.setCropState(decoded.getCropState());
			snapshot.setStage(decoded.getStage());
			snapshot.setLastSeen(lastSeen);
			snapshot.setCompost(readTimeTrackingCompost(patch));
			snapshot.setPatchProtected(Boolean.TRUE.equals(configManager.getRSProfileConfiguration(
				TIMETRACKING_GROUP, patch.getKey() + TIMETRACKING_PROTECTED_SUFFIX, Boolean.class)));

			snapshots.put(patch.getKey(), snapshot);
			seeded++;
		}
		return seeded;
	}

	private CompostTier readTimeTrackingCompost(FarmPatch patch)
	{
		String value = configManager.getRSProfileConfiguration(
			TIMETRACKING_GROUP, patch.getKey() + TIMETRACKING_COMPOST_SUFFIX);
		if (value == null)
		{
			return CompostTier.NONE;
		}

		// Core stores CompostState's enum name: COMPOST, SUPERCOMPOST or ULTRACOMPOST.
		try
		{
			return CompostTier.valueOf(value.toUpperCase());
		}
		catch (IllegalArgumentException e)
		{
			return CompostTier.NONE;
		}
	}

	public void clear()
	{
		synchronized (this)
		{
			snapshots.clear();
		}
		unsetStored();
		fireChanged();
	}
}
