package com.dooglemaps.validate;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropXp;
import com.dooglemaps.data.Seed;
import com.dooglemaps.timer.CropYieldModel;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.Produce;
import com.dooglemaps.timer.FarmingBonuses;
import com.dooglemaps.timer.YieldEstimate;
import lombok.Getter;

/**
 * One patch, harvested from start to finish, with what was predicted next to what happened.
 *
 * <p>The plugin claims a herb patch will give about nine herbs. Nothing has ever checked that
 * against a real harvest, and the arithmetic has several places where a wrong-but-plausible
 * reading of the wiki shifts the answer by a herb or so. This is the check: it costs nothing
 * to collect while playing normally, and a couple of dozen of these settle the question.
 *
 * <p>Deliberately records the <i>inputs</i> as well as the outcome. A record that disagrees
 * with its prediction is only useful if you can see whether the level, compost and bonuses it
 * assumed were the ones actually in play.
 */
@Getter
public class HarvestRecord
{
	private final FarmPatch patch;
	private final Produce produce;
	private final CompostTier compost;
	private final int farmingLevel;
	private final FarmingBonuses bonuses;
	private final long startedAt;

	/** Items seen arriving in the inventory. */
	private int itemsSeen;

	/**
	 * Picks the experience says happened, whether or not the item reached the inventory.
	 *
	 * <p>An open herb sack swallows grimy herbs the moment they are picked — they never touch
	 * the inventory, so there is no delta to count and a herb run recorded nothing at all.
	 * That is why the plugin had watermelon, limpwurt and snape grass data but not one herb.
	 *
	 * <p>Experience does not have that problem. It arrives per pick at a rate published for
	 * every crop, so dividing the gain by that rate counts picks the inventory cannot see.
	 * Fruit baskets and vegetable sacks would hide items the same way.
	 */
	private int itemsFromXp;

	/** Farming experience seen while this patch was being picked. */
	private double xpGained;

	/** Game ticks since the last item, so an abandoned patch can be told from a finished one. */
	private int ticksIdle;

	/** False when the patch was left standing rather than picked clean. */
	private boolean completed;

	HarvestRecord(FarmPatch patch, Produce produce, CompostTier compost, int farmingLevel,
		FarmingBonuses bonuses, long startedAt)
	{
		this.patch = patch;
		this.produce = produce;
		this.compost = compost == null ? CompostTier.NONE : compost;
		this.farmingLevel = farmingLevel;
		this.bonuses = bonuses;
		this.startedAt = startedAt;
	}

	void addItems(int count)
	{
		itemsSeen += count;
		ticksIdle = 0;
	}

	/**
	 * Records an experience drop, counting it as a pick if it looks like one.
	 *
	 * <p>Counted drop by drop rather than by dividing the total. Division looks tidier and is
	 * worse: it compounds every source of error over a long harvest, and it silently absorbs
	 * experience that was never a pick at all. One drop is one pick, so counting them is both
	 * more accurate and harder to be wrong about.
	 */
	void addXp(double xp)
	{
		xpGained += xp;
		ticksIdle = 0;

		if (isOnePick(xp, perPickXp()))
		{
			itemsFromXp++;
		}
	}

	/**
	 * What one pick of this crop pays <i>this player</i>.
	 *
	 * <p>The published rate is not what arrives. The Farmer's outfit multiplies Farming
	 * experience by up to 2.5%, so a ranarr's 30.5 turns up as 31.26 for anyone wearing the
	 * full set — and matching against the unboosted figure would fail for most farmers, which
	 * is to say most of the people this is for.
	 *
	 * @return 0 where the crop pays nothing per pick, e.g. a tree
	 */
	double perPickXp()
	{
		CropXp rates = CropXp.forProduce(produce);
		if (rates == null || rates.getHarvestXp() <= 0)
		{
			return 0;
		}
		return bonuses.applyOutfit(rates.getHarvestXp());
	}

	/**
	 * Whether an experience drop is one pick of a crop paying {@code perPick}.
	 *
	 * <p>Two allowances, for two different reasons. Half a point because {@code StatChanged}
	 * reports a whole-number total while rates are fractional, so the game's internal halves
	 * surface as gains alternating either side. And a proportional margin on top, because the
	 * outfit bonus is modelled rather than measured, and any small error in it scales with the
	 * crop.
	 *
	 * <p>Still tight enough to reject the neighbouring award: planting a potato pays 8 against
	 * its 9 to pick, and is not counted as a harvest.
	 */
	static boolean isOnePick(double gained, double perPick)
	{
		if (perPick <= 0)
		{
			return false;
		}
		double tolerance = Math.max(0.75, perPick * 0.05);
		return Math.abs(gained - perPick) <= tolerance;
	}

	/**
	 * How many items this patch gave.
	 *
	 * <p>The larger of what was seen and what the experience implies. Not the sum: the two
	 * count the same picks, and most of the time they agree exactly. They diverge when a
	 * container swallows the item — an open herb sack, a fruit basket — and then the
	 * experience is the one telling the truth.
	 */
	public int getItemsHarvested()
	{
		return Math.max(itemsSeen, itemsFromXp);
	}

	/** Whether any of this harvest was counted from experience rather than seen arriving. */
	public boolean isInferredFromXp()
	{
		return itemsFromXp > itemsSeen;
	}

	void tick()
	{
		ticksIdle++;
	}

	void markCompleted()
	{
		completed = true;
	}

	/** Lives the patch started with, which is also the guaranteed floor. */
	public int getLives()
	{
		return YieldEstimate.lives(compost);
	}

	/**
	 * What the plugin predicted for this patch.
	 *
	 * <p>Through {@link CropYieldModel}, not the chance-to-save table directly: only some crops
	 * use that mechanic, and asking the table alone reported "n/a" for every flower — the exact
	 * crops whose numbers most need checking.
	 */
	public double getPredictedYield()
	{
		Seed seed = Seed.forProduce(produce);
		return seed == null ? 0 : CropYieldModel.expected(seed, farmingLevel, compost, bonuses);
	}

	/**
	 * Experience the harvest alone should have paid.
	 *
	 * <p>Planting and check-health are excluded because neither happens during a harvest, so
	 * a mismatch here points at the per-pick figure specifically.
	 */
	public double getPredictedXp()
	{
		CropXp xp = CropXp.forProduce(produce);
		if (xp == null)
		{
			return 0;
		}
		// Flowers pay once for the patch however many items come out, so the count of items
		// picked is the wrong multiplier for them.
		return bonuses.applyOutfit(
			xp.getHarvestXp()
				* CropYieldModel.xpHarvestsFor(Seed.forProduce(produce), getItemsHarvested()));
	}
}
