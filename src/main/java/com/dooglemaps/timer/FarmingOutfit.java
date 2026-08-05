package com.dooglemaps.timer;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.ItemID;

/**
 * The Farmer's outfit, which boosts experience but not yield.
 *
 * <p>Worth its own class because it behaves unlike every other bonus here. The rest —
 * secateurs, the cape, attas, compost — change how much you <i>harvest</i>, and experience
 * follows from that. The outfit changes nothing about the harvest and multiplies the
 * experience afterwards, so it has to be applied at a different point in the sum.
 *
 * <p>It is also unlike most skilling outfits, which give a flat share per piece. These do not:
 * the jacket is worth four times the boots. Assuming the usual 0.4% each would be wrong for
 * three of the four pieces.
 *
 * <table>
 *   <caption>Per-piece experience bonus</caption>
 *   <tr><td>Strawhat</td><td>0.4%</td></tr>
 *   <tr><td>Jacket or shirt</td><td>0.8%</td></tr>
 *   <tr><td>Boro trousers</td><td>0.6%</td></tr>
 *   <tr><td>Boots</td><td>0.2%</td></tr>
 *   <tr><td>All four</td><td>+0.5%, for 2.5% in total</td></tr>
 * </table>
 */
@Getter
@RequiredArgsConstructor
public enum FarmingOutfit
{
	HAT(0.004, ItemID.TITHE_REWARD_HAT_MALE, ItemID.TITHE_REWARD_HAT_FEMALE),
	TORSO(0.008, ItemID.TITHE_REWARD_TORSO_MALE, ItemID.TITHE_REWARD_TORSO_FEMALE),
	LEGS(0.006, ItemID.TITHE_REWARD_LEGS_MALE, ItemID.TITHE_REWARD_LEGS_FEMALE),
	BOOTS(0.002, ItemID.TITHE_REWARD_FEET_MALE, ItemID.TITHE_REWARD_FEET_FEMALE);

	/** Awarded for wearing all four. */
	public static final double SET_BONUS = 0.005;

	/** The most the outfit can give, which is 2.5%. */
	public static final double FULL_SET = 0.004 + 0.008 + 0.006 + 0.002 + SET_BONUS;

	private final double bonus;
	private final int maleItemId;
	private final int femaleItemId;

	public boolean matches(int itemId)
	{
		return itemId == maleItemId || itemId == femaleItemId;
	}

	/** The piece a worn item is, or null if it is not part of the outfit. */
	public static FarmingOutfit forItemId(int itemId)
	{
		for (FarmingOutfit piece : values())
		{
			if (piece.matches(itemId))
			{
				return piece;
			}
		}
		return null;
	}

	/**
	 * The experience multiplier a set of worn pieces is worth.
	 *
	 * @return 0 for nothing worn, up to {@link #FULL_SET} for all four
	 */
	public static double bonusFor(Collection<FarmingOutfit> worn)
	{
		Set<FarmingOutfit> pieces = worn.isEmpty()
			? EnumSet.noneOf(FarmingOutfit.class)
			: EnumSet.copyOf(worn);

		double total = 0;
		for (FarmingOutfit piece : pieces)
		{
			total += piece.getBonus();
		}
		if (pieces.size() == values().length)
		{
			total += SET_BONUS;
		}
		return total;
	}
}
