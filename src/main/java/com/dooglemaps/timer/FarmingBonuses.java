package com.dooglemaps.timer;

import lombok.Value;

/**
 * The things a player can be carrying or have done that improve a harvest.
 *
 * <p>Kept as a plain value rather than read from the client, so {@link YieldEstimate} can be
 * tested against the wiki's published figures without a game running, and so the panel can
 * show what a harvest <i>would</i> be worth with a bonus the player does not have yet.
 */
@Value
public class FarmingBonuses
{
	public static final FarmingBonuses NONE = new FarmingBonuses(false, false, false, 0, 0);

	/** Magic secateurs, worn or merely carried - the game accepts either. */
	boolean magicSecateurs;

	/** Farming cape or max cape. Counts on herb patches and nowhere else. */
	boolean farmingCape;

	/** An attas plant growing in the anima patch, which helps every patch in the game. */
	boolean attas;

	/**
	 * The flat bonus a completed diary gives <i>this</i> patch, in 256ths.
	 *
	 * <p>Patch-specific rather than account-wide: 10, 17 or 25 for Catherby's herb patch with
	 * the medium, hard or elite Kandarin diary, and 10 for the Hosidius and Farming Guild herb
	 * patches with the hard Kourend &amp; Kebos diary. Everywhere else it is 0, which is why
	 * this is a number and not a set of booleans.
	 */
	int diaryBonus;

	/**
	 * The Farmer's outfit's experience multiplier, 0 to 0.025.
	 *
	 * <p>Kept apart from the rest because it acts at a different point in the sum. Everything
	 * else here changes what you harvest, and the experience follows from that; this changes
	 * only the experience. See {@link FarmingOutfit}.
	 */
	double outfitBonus;

	/** Everything but the outfit, for the callers that do not care about experience. */
	public FarmingBonuses(boolean magicSecateurs, boolean farmingCape, boolean attas, int diaryBonus)
	{
		this(magicSecateurs, farmingCape, attas, diaryBonus, 0);
	}

	public FarmingBonuses(boolean magicSecateurs, boolean farmingCape, boolean attas,
		int diaryBonus, double outfitBonus)
	{
		this.magicSecateurs = magicSecateurs;
		this.farmingCape = farmingCape;
		this.attas = attas;
		this.diaryBonus = diaryBonus;
		this.outfitBonus = outfitBonus;
	}

	/**
	 * Applies the outfit to an experience figure.
	 *
	 * <p>Deliberately its own step. Passing a <i>yield</i> through here would quietly inflate
	 * the harvest, which the outfit does not touch.
	 */
	public double applyOutfit(double xp)
	{
		return xp * (1 + outfitBonus);
	}

	public FarmingBonuses withOutfitBonus(double bonus)
	{
		return new FarmingBonuses(magicSecateurs, farmingCape, attas, diaryBonus, bonus);
	}

	public FarmingBonuses withDiaryBonus(int bonus)
	{
		return new FarmingBonuses(magicSecateurs, farmingCape, attas, bonus, outfitBonus);
	}
}
