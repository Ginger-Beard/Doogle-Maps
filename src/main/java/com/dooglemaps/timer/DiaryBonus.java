package com.dooglemaps.timer;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.PatchImplementation;
import lombok.Value;

/**
 * The diary rewards that improve a specific patch's yield.
 *
 * <p>Unlike every other bonus, these belong to a <i>place</i> rather than to the player. The
 * Kandarin diary makes Catherby's herb patch better and does nothing for Falador's, so this
 * cannot live on {@link FarmingBonuses} as a flag — it has to be looked up per patch.
 *
 * <p>They are also added to the chance-to-save constants rather than multiplying them, which
 * makes them worth more than they look: elite Kandarin's +25 beats the magic secateurs on a
 * herb patch, and the two stack.
 */
public final class DiaryBonus
{
	/** Region ids of the three patches any of this applies to. */
	private static final int CATHERBY = 11062;
	private static final int HOSIDIUS = 6967;
	private static final int FARMING_GUILD = 4922;

	/** Which diaries the player has finished. */
	@Value
	public static class Completed
	{
		public static final Completed NONE = new Completed(false, false, false, false);

		boolean kandarinMedium;
		boolean kandarinHard;
		boolean kandarinElite;
		boolean kourendHard;
	}

	private DiaryBonus()
	{
	}

	/**
	 * The bonus this patch gets, in the same 256ths the chance-to-save constants use.
	 *
	 * <p>Only herb patches, and only three of them. Everything else is zero, which is why this
	 * is worth stating as a lookup rather than sprinkling conditions through the yield maths.
	 */
	public static int forPatch(FarmPatch patch, Completed completed)
	{
		if (patch == null || completed == null
			|| patch.getImplementation() != PatchImplementation.HERB)
		{
			return 0;
		}

		int region = patch.getRegion().getRegionId();
		if (region == CATHERBY)
		{
			// Only the best one counts; they do not stack with each other.
			if (completed.isKandarinElite())
			{
				return 25;
			}
			if (completed.isKandarinHard())
			{
				return 17;
			}
			return completed.isKandarinMedium() ? 10 : 0;
		}

		if (region == HOSIDIUS || region == FARMING_GUILD)
		{
			return completed.isKourendHard() ? 10 : 0;
		}

		return 0;
	}

	/** Whether a patch could ever get a diary bonus, for explaining why one is missing. */
	public static boolean isEligible(FarmPatch patch)
	{
		if (patch == null || patch.getImplementation() != PatchImplementation.HERB)
		{
			return false;
		}
		int region = patch.getRegion().getRegionId();
		return region == CATHERBY || region == HOSIDIUS || region == FARMING_GUILD;
	}
}
