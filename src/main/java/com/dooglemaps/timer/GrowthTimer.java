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
	private GrowthTimer(ConfigManager configManager)
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
		Integer precision = readOffsetSetting(FARM_TICK_OFFSET_PRECISION, OWN_OFFSET_PRECISION);
		Integer offsetMinutes = readOffsetSetting(FARM_TICK_OFFSET, OWN_OFFSET);

		if (precision == null || offsetMinutes == null)
		{
			return 0;
		}
		// An offset learned from a shorter cycle than the one being asked about does not
		// constrain it, unless it came from a cycle long enough to pin down all of them.
		if (precision < tickRate && precision < OFFSET_PRECISION_TRUSTED)
		{
			return 0;
		}
		return (long) (offsetMinutes % tickRate) * 60;
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
	}

	/** Whether the account's auto-weed is on, so weeds should not be shown regrowing. */
	public boolean isAutoweedEnabled()
	{
		Integer own = configManager.getRSProfileConfiguration(DoogleMapsConfig.GROUP, AUTOWEED, int.class);
		if (own != null)
		{
			return own == AUTOWEED_ON;
		}
		String core = configManager.getRSProfileConfiguration(TIMETRACKING_GROUP, AUTOWEED);
		return String.valueOf(AUTOWEED_ON).equals(core);
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
			snapshot.getLastSeen()
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
