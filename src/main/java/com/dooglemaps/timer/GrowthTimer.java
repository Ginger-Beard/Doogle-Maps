package com.dooglemaps.timer;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.Produce;
import com.dooglemaps.state.PatchSnapshot;
import java.time.Instant;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Turns a cached snapshot into "where is this patch now, and when is it done".
 *
 * <h2>Why this is not a countdown</h2>
 * Crops do not tick on their own schedule. The game runs a shared grid of growth ticks —
 * every 5 minutes for flowers, 10 for allotments, 20 for herbs, and so on up to 640 for
 * redwoods — and a crop advances only when a tick of its own cycle length lands. So the
 * first stage after planting is usually short (you planted partway through a cycle), and
 * finish times line up with the grid rather than with when you planted.
 *
 * <p>The grid is also shifted by a fixed per-account offset of up to 30 minutes, always
 * negative, persisting across logins. It can only be learned by watching: see a crop
 * advance, and the offset is the distance from that moment back to the grid. Core Time
 * Tracking already does this and stores the answer, so we read its value when it is there
 * and observe our own otherwise.
 *
 * <p>Because it is all grid arithmetic rather than a running clock, offline growth is
 * free: projecting from a two-day-old snapshot uses exactly the same code path as
 * projecting from a two-minute-old one.
 */
@Slf4j
@Singleton
public class GrowthTimer
{
	/** Core Time Tracking's config group and keys, read-only. */
	private static final String TIMETRACKING_GROUP = "timetracking";
	private static final String FARM_TICK_OFFSET = "farmTickOffset";
	private static final String FARM_TICK_OFFSET_PRECISION = "farmTickOffsetPrecision";

	/** Our own copies, used when core Time Tracking is disabled. */
	private static final String OWN_OFFSET = "farmTickOffset";
	private static final String OWN_OFFSET_PRECISION = "farmTickOffsetPrecision";

	private static final String AUTOWEED = "autoweed";

	/**
	 * An offset observed from a short cycle only pins down that cycle's phase. Core treats
	 * anything at or above a 40-minute cycle as good enough for every cycle length.
	 */
	private static final int OFFSET_PRECISION_TRUSTED = 40;

	/**
	 * A yew's first growth stage takes 5 minutes rather than the 40 its later stages do.
	 * It is the only crop that breaks its own cycle length this way.
	 */
	private static final int YEW_FIRST_STAGE_MINUTES = 5;

	private final ConfigManager configManager;

	@Inject
	GrowthTimer(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	// ---------------------------------------------------------- the tick grid

	/**
	 * When a growth tick happens, relative to a moment in time.
	 *
	 * @param tickRate      cycle length in minutes
	 * @param ticks         how many ticks ahead to look; 0 gives the most recent tick at
	 *                      or before {@code requestedTime}
	 * @param requestedTime epoch seconds to measure from
	 * @return epoch seconds of that tick
	 */
	public long getTickTime(int tickRate, int ticks, long requestedTime)
	{
		if (tickRate <= 0)
		{
			return 0;
		}

		long offsetSeconds = getOffsetSeconds(tickRate);

		// Shift into "grid time", land on the tick, then shift back to real time.
		long shiftedNow = requestedTime + offsetSeconds;
		long currentTick = shiftedNow - Math.floorMod(shiftedNow, (long) tickRate * 60);
		long goalTick = currentTick + ((long) ticks * tickRate * 60);
		return goalTick - offsetSeconds;
	}

	public long getTickTime(int tickRate, int ticks)
	{
		return getTickTime(tickRate, ticks, Instant.now().getEpochSecond());
	}

	/**
	 * The per-account grid shift for a given cycle length, in seconds.
	 *
	 * <p>Offsets are always negative but stored as a positive magnitude. Returns 0 when
	 * nothing has observed one yet, which just means times snap to the unshifted grid and
	 * can be up to 30 minutes late.
	 */
	private long getOffsetSeconds(int tickRate)
	{
		Offset offset = offset();
		if (offset == null)
		{
			return 0;
		}
		// An offset learned from a shorter cycle than the one being asked about does not
		// constrain it, unless it came from a cycle long enough to pin down all of them.
		if (offset.precision < tickRate && offset.precision < OFFSET_PRECISION_TRUSTED)
		{
			return 0;
		}
		return (long) (offset.minutes % tickRate) * 60;
	}

	/**
	 * The account's observed grid offset, read from config at most once between invalidations.
	 *
	 * <h2>Why this is cached rather than read each time</h2>
	 *
	 * {@link #project} used to reach config seven to fourteen times <b>per patch</b>: once for
	 * auto-weed, then two reads for every {@link #readOffsetSetting} inside every
	 * {@link #getOffsetSeconds}, inside each of the three or four {@link #getTickTime} calls a
	 * projection makes. The panel projects every available patch, twice a refresh — the summary
	 * line and the ready infobox each walk the whole list — so a refresh was on the order of
	 * <b>2,800 config lookups</b>.
	 *
	 * <p>The guide is worse, and it is the one that matters: {@code GuideTracker.computeStepsHere}
	 * runs on every game tick, and its {@code patchesWanting} loops the stop's patches inside a
	 * loop over the same patches, projecting each time. At the Farming Guild's eleven patches that
	 * is 121 projections a tick, on the client thread, in the frame budget.
	 *
	 * <p>None of it could change between those calls. The farm tick offset is a per-account
	 * constant learned once by watching a crop advance; auto-weed changes when you buy the unlock.
	 * So this is the case {@code docs/NOTES.md} describes as putting the cache on the thing being
	 * computed rather than on each caller — the four per-tick caches dotted around the overlays
	 * are all working around this method.
	 *
	 * <p>Volatile and immutable rather than synchronised: it is read from the client thread and
	 * the Swing thread, and a racing pair of readers both filling it in is harmless because they
	 * compute the same answer.
	 */
	@Nullable
	private Offset offset()
	{
		Offset cached = offset;
		if (cached != null)
		{
			return cached == Offset.NONE ? null : cached;
		}

		Integer precision = readOffsetSetting(FARM_TICK_OFFSET_PRECISION, OWN_OFFSET_PRECISION);
		Integer minutes = readOffsetSetting(FARM_TICK_OFFSET, OWN_OFFSET);

		Offset resolved = precision == null || minutes == null
			? Offset.NONE
			: new Offset(precision, minutes);
		offset = resolved;
		return resolved == Offset.NONE ? null : resolved;
	}

	/** The observed offset, or {@link Offset#NONE} for "config has nothing to say". */
	private volatile Offset offset;

	/** Auto-weed, cached beside the offset and invalidated with it. */
	private volatile Boolean autoweed;

	private static final class Offset
	{
		/** Stands in for "read, and there was nothing there", so absence is cached too. */
		private static final Offset NONE = new Offset(0, 0);

		private final int precision;
		private final int minutes;

		private Offset(int precision, int minutes)
		{
			this.precision = precision;
			this.minutes = minutes;
		}
	}

	/**
	 * Drops the cached offset and auto-weed flag.
	 *
	 * <p>Called whenever the values behind them could have moved: a profile load, which may be a
	 * different account entirely, and the two observations that write them. Cheap to call
	 * spuriously — the cost of being wrong here is one config read, and the cost of <i>not</i>
	 * calling it is a projection using another account's grid.
	 */
	public void invalidate()
	{
		offset = null;
		autoweed = null;
	}

	/** Prefers core Time Tracking's observation, falling back to our own. */
	@Nullable
	private Integer readOffsetSetting(String coreKey, String ownKey)
	{
		Integer core = configManager.getRSProfileConfiguration(TIMETRACKING_GROUP, coreKey, int.class);
		if (core != null)
		{
			return core;
		}
		return configManager.getRSProfileConfiguration(DoogleMapsConfig.GROUP, ownKey, int.class);
	}

	/**
	 * Records a growth tick we just watched happen, which pins the grid's phase.
	 *
	 * <p>Only useful when core Time Tracking is off; when it is on, it observes the same
	 * transitions and we read its answer. A longer cycle gives a more precise offset, so a
	 * new observation only wins if its cycle is at least as long as the stored one's.
	 *
	 * @param tickRate the cycle length of the crop that just advanced, in minutes
	 */
	public void observeGrowthTick(int tickRate)
	{
		if (tickRate <= 0)
		{
			return;
		}

		Integer storedPrecision = configManager.getRSProfileConfiguration(
			DoogleMapsConfig.GROUP, OWN_OFFSET_PRECISION, int.class);
		if (storedPrecision != null && tickRate < storedPrecision)
		{
			return;
		}

		// The tick just landed, so the distance back to the unshifted grid is the offset.
		long nowMinutes = Instant.now().getEpochSecond() / 60;
		int offsetMinutes = (int) Math.abs((nowMinutes % tickRate) - tickRate);

		log.debug("Observed a {}-minute growth tick, offset {} minutes", tickRate, offsetMinutes);
		configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, OWN_OFFSET_PRECISION, tickRate);
		configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, OWN_OFFSET, offsetMinutes);
		// The value the cache holds has just changed underneath it. This is the whole reason
		// invalidate() exists as a method rather than the cache being filled once at load.
		invalidate();
	}

	/**
	 * Whether the account's auto-weed is on, so weeds should not be shown regrowing.
	 *
	 * <p>Cached for the reason {@link #offset()} gives — {@link #project} asks this for every
	 * patch it projects, and the answer changes when you buy the unlock rather than between two
	 * patches in one loop.
	 */
	public boolean isAutoweedEnabled()
	{
		Boolean cached = autoweed;
		if (cached != null)
		{
			return cached;
		}

		Integer own = configManager.getRSProfileConfiguration(DoogleMapsConfig.GROUP, AUTOWEED, int.class);
		boolean enabled = own != null
			? own == AUTOWEED_ON
			: String.valueOf(AUTOWEED_ON).equals(
				configManager.getRSProfileConfiguration(TIMETRACKING_GROUP, AUTOWEED));

		autoweed = enabled;
		return enabled;
	}

	/** Value of {@code Varbits.AUTOWEED} meaning auto-weed is unlocked and switched on. */
	public static final int AUTOWEED_ON = 2;

	/**
	 * Caches the auto-weed setting so weed regrowth can be projected while logged out.
	 *
	 * <p>Called every tick, so it only writes when the value actually changes — config
	 * writes are persisted to disk and are not free.
	 */
	public void setAutoweed(int varbitValue)
	{
		Integer stored = configManager.getRSProfileConfiguration(DoogleMapsConfig.GROUP, AUTOWEED, int.class);
		if (stored == null || stored != varbitValue)
		{
			configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, AUTOWEED, varbitValue);
			invalidate();
		}
	}

	// ------------------------------------------------------------ projection

	/**
	 * Brings a snapshot up to the present.
	 *
	 * @return the projection, or null if we have never seen this patch
	 */
	@Nullable
	public PatchProjection project(FarmPatch patch, @Nullable PatchSnapshot snapshot)
	{
		if (snapshot == null || snapshot.getProduce() == null || snapshot.getCropState() == null
			|| snapshot.getLastSeen() <= 0)
		{
			return null;
		}

		Produce produce = snapshot.getProduce();
		CropState cropState = snapshot.getCropState();

		// The varbit's stage means different things in different phases: while growing it
		// counts growth stages, once harvestable it counts the crop's remaining lives. Both
		// are projected the same way, but only the growth one is what the progress bar
		// shows — see below.
		int phaseStage = snapshot.getStage();
		int phaseStages = stagesFor(produce, cropState);
		int tickRate = tickRateFor(produce, cropState);

		// With auto-weed on, weeds never come back, so there is nothing to count down.
		if (isAutoweedEnabled() && produce == Produce.WEEDS)
		{
			phaseStage = 0;
			phaseStages = 1;
			tickRate = 0;
		}

		long now = Instant.now().getEpochSecond();
		long doneEstimate = 0;

		if (tickRate > 0 && phaseStages > 1)
		{
			long lastSeenTick = getTickTime(tickRate, 0, snapshot.getLastSeen());
			long currentTick = getTickTime(tickRate, 0, now);
			int elapsedTicks = (int) ((currentTick - lastSeenTick) / ((long) tickRate * 60));

			doneEstimate = projectDone(produce, phaseStage, phaseStages, tickRate, lastSeenTick);

			phaseStage = Math.min(phaseStage + elapsedTicks, phaseStages - 1);
		}

		// A crop that reached its last growth stage while we were away is now harvestable,
		// unless it is one that must be checked for health first — that needs the player.
		if (cropState == CropState.GROWING && phaseStage >= phaseStages - 1
			&& doneEstimate > 0 && doneEstimate <= now
			&& !patch.getImplementation().isHealthCheckRequired())
		{
			cropState = CropState.HARVESTABLE;
		}

		// Report growth progress, always, so the bar means one thing for the whole life of
		// the crop. Reporting the raw phase stage instead would make two herb patches of
		// the same crop show different segment counts purely because one had ripened —
		// five growth stages next to three remaining lives, which reads as a bug.
		int growthStages = produce.getStages();
		int stage = cropState == CropState.GROWING
			? Math.min(phaseStage, growthStages - 1)
			: growthStages - 1;

		// A harvestable crop's phase stage counts its remaining harvests, and for crops that
		// regrow it climbs back up over time. Worth surfacing: someone farming coconuts for
		// magic tree payments wants to know when the next lot is back, not to clear the tree.
		int livesRemaining = 0;
		long regrowEstimate = 0;
		if (cropState == CropState.HARVESTABLE)
		{
			// The harvest stage counts states, and the two crop families number them
			// differently. A fruit tree holds at most six fruit but has seven states, because
			// "no fruit on the tree" is one of them - so the stage IS the stock. A herb patch
			// has three states and three lives, and is never harvestable with zero left, so
			// there the stage is one below the count. Verified for fruit trees against the
			// wiki: "to a maximum of six".
			boolean regrows = produce.getRegrowTickrate() > 0;
			int maximum = regrows ? produce.getHarvestStages() - 1 : produce.getHarvestStages();

			livesRemaining = Math.max(0, Math.min(regrows ? phaseStage : phaseStage + 1, maximum));

			if (regrows && livesRemaining < maximum)
			{
				regrowEstimate = getTickTime(produce.getRegrowTickrate(), 1, now);
			}
		}

		boolean stale = snapshot.getLastSeen() < now - 60;

		return new PatchProjection(
			patch,
			produce,
			cropState,
			stage,
			growthStages,
			doneEstimate,
			livesRemaining,
			regrowEstimate,
			confidenceFor(patch, snapshot, produce, cropState, stage, growthStages),
			stale,
			snapshot.getLastSeen(),
			// From the raw varbit, which goes no further than here. A checked tree and the stump it
			// leaves decode identically, so this is the only place the difference survives.
			patch.getImplementation().isStumpVarbitValue(snapshot.getVarbitValue())
		);
	}

	/**
	 * Epoch seconds when the crop finishes, from the tick it was last seen on.
	 *
	 * <p>Normally that is just "however many stages are left, times the cycle length,
	 * snapped to the grid". Yews are the exception: their first stage runs on a 5-minute
	 * cycle before the usual 40-minute one takes over.
	 */
	private long projectDone(Produce produce, int stage, int stages, int tickRate, long fromTick)
	{
		int remaining = stages - 1 - stage;
		if (remaining <= 0)
		{
			return fromTick;
		}

		if (produce == Produce.YEW && stage == 0)
		{
			long afterFirst = getTickTime(YEW_FIRST_STAGE_MINUTES, 1, fromTick);
			return getTickTime(tickRate, remaining - 1, afterFirst);
		}

		return getTickTime(tickRate, remaining, fromTick);
	}

	private static int stagesFor(Produce produce, CropState cropState)
	{
		return cropState == CropState.HARVESTABLE || cropState == CropState.FILLING
			? produce.getHarvestStages()
			: produce.getStages();
	}

	private static int tickRateFor(Produce produce, CropState cropState)
	{
		switch (cropState)
		{
			case HARVESTABLE:
				return produce.getRegrowTickrate();
			case GROWING:
				return produce.getTickrate();
			default:
				return 0;
		}
	}

	private static Confidence confidenceFor(FarmPatch patch, PatchSnapshot snapshot, Produce produce,
		CropState cropState, int stage, int stages)
	{
		if (cropState == CropState.DISEASED || cropState == CropState.DEAD)
		{
			return Confidence.NEEDS_ACTION;
		}
		if (produce == null || !produce.isCrop() || cropState == CropState.EMPTY)
		{
			return Confidence.EMPTY;
		}
		// Fully grown crops cannot become diseased, so there is nothing left to be unsure
		// about.
		if (cropState == CropState.HARVESTABLE || stage >= stages - 1)
		{
			return Confidence.CERTAIN;
		}
		return DiseaseRisk.isAtRisk(patch, produce, snapshot.isPatchProtected(), stage)
			? Confidence.ESTIMATE
			: Confidence.CERTAIN;
	}
}
