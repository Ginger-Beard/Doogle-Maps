package com.dooglemaps.validate;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropXp;
import com.dooglemaps.data.CropYield;
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

	/**
	 * What the patch was treated with, as far as anyone knows at the time of writing.
	 *
	 * <p>Not final, and that is the whole of the compost capture fix. It is read from the patch
	 * snapshot when the first item lands, which is the earliest moment it can be — but the
	 * snapshot only holds a bucket the plugin <i>watched</i> go in, and a patch composted last
	 * session and already ripe at login has nothing there. See {@link #adoptCompost}.
	 */
	private CompostTier compost;
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

	/**
	 * Whether the drop that empties the patch has already been recognised.
	 *
	 * <p>A patch empties once, so the bundled drop can only happen once, and saying so is what
	 * keeps {@link #isTheLastPick} safe on snape grass — it pays 82 to pick and 82 to plant, so
	 * its emptying drop of 164 is arithmetically indistinguishable from two ordinary picks
	 * landing on the same tick. Without this, a second such drop would be read as a second
	 * emptying and invent a pick that never happened.
	 */
	private boolean clearAwardCounted;

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

	/**
	 * Takes a compost tier learned after the harvest started.
	 *
	 * <p>Only ever upwards, from NONE to a real tier: this supplies a fact that was missing, it
	 * never overrules one that was observed. Everything the record derives — the predicted yield,
	 * the lives, the variance — is computed on demand from this field, so adopting it re-scores
	 * the harvest rather than leaving a prediction that was made against the wrong patch.
	 *
	 * <p>The alternative was to edit the observation instead, and that is the wrong half to
	 * change: {@code itemsHarvested} is what actually came out of the ground. The compost is an
	 * <i>input</i> to the model, and getting a late answer to "what was this patch treated with"
	 * is not the same kind of thing as revising what was picked.
	 */
	void adoptCompost(CompostTier tier)
	{
		if (tier != null && tier != CompostTier.NONE && compost == CompostTier.NONE)
		{
			compost = tier;
		}
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
	 * experience that was never a pick at all. Counting drops is both more accurate and harder
	 * to be wrong about.
	 *
	 * <p>One drop is one pick everywhere but the end of a harvest, where the pick that empties
	 * the patch arrives bundled with the award for clearing it — see {@link #isTheLastPick}.
	 */
	void addXp(double xp)
	{
		xpGained += xp;
		ticksIdle = 0;

		if (isOnePick(xp, perPickXp()))
		{
			itemsFromXp++;
		}
		else if (isTheLastPick(xp))
		{
			clearAwardCounted = true;
			itemsFromXp++;
		}
	}

	/**
	 * Whether this drop is the pick that emptied the patch, which arrives bundled.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * The last pick of an allotment, hops or herb patch does not pay a pick's worth. It pays a
	 * pick <i>plus</i> the award {@link CropYieldModel#clearedPatchXp} describes, in one drop:
	 * avantoe pays 62, 62, 62 and then 116, which is its 61.5 to pick and its 54.5 to plant.
	 * {@link #isOnePick} rejects that, so the last pick of every harvest counted from experience
	 * was silently lost.
	 *
	 * <p>Which is the whole herb run, because a herb goes into the sack rather than the inventory
	 * and experience is the only thing counting it. Every ranarr row in the harvest CSV is a herb
	 * short, and the residual it leaves is the giveaway: 56.2 to 57.6 across thirty-five patches,
	 * against a ranarr's 27 + 30.5 = 57.5. The same for avantoe at 115.0 to 116.8 against 116, and
	 * cadantine at 226.6 against 226.5. A crop whose items <i>are</i> visible - watermelon, snape
	 * grass - was unharmed, because {@link #getItemsHarvested} takes the larger of the two counts
	 * and the inventory had it right all along. That is why this reads as an experience bug and
	 * not a yield one, and why it hid for so long.
	 *
	 * <p>Only for crops that pay per item. A flower's single drop is the whole patch and not a
	 * pick of anything, so counting it as one would claim a limpwurt patch gave one root.
	 */
	private boolean isTheLastPick(double xp)
	{
		Seed seed = Seed.forProduce(produce);
		if (clearAwardCounted || perPickXp() <= 0 || !CropYieldModel.paysHarvestPerItem(seed))
		{
			return false;
		}

		double cleared = bonuses.applyOutfit(CropYieldModel.clearedPatchXp(seed));
		return cleared > 0 && isOnePick(xp, perPickXp() + cleared);
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
	 * How widely this patch could have scattered around its prediction, as a variance.
	 *
	 * <p>Recorded alongside the prediction because the two together are what make a lifetime
	 * total interpretable: summed over a season's patches they give the mean <i>and</i> the
	 * spread, and "you are eighteen herbs up" only means something against the second.
	 *
	 * <p>Asked of {@link CropYield} rather than {@link CropYieldModel}, and zero where there is
	 * none. Only the harvest-lives family has a distribution the plugin can name; a limpwurt's
	 * level roll and the wiki's measured averages have spreads that are respectively unmodelled
	 * and unknown, and inventing one for them would put a confident percentile on a number that
	 * cannot support it. The store keeps its own count of the patches this answered for, so a
	 * crop that cannot be scored simply is not.
	 */
	public double getPredictedVariance()
	{
		CropYield yield = CropYield.forProduce(produce);
		return yield == null
			? 0
			: YieldEstimate.harvestVariance(yield, farmingLevel, getLives(), bonuses);
	}

	/**
	 * Experience the harvest alone should have paid.
	 *
	 * <p>Check-health is excluded because it does not happen during a harvest, so a mismatch here
	 * points at the per-pick figure specifically.
	 *
	 * <h2>Planting is not excluded, because the game does not exclude it</h2>
	 *
	 * It used to be, on the reasonable-sounding grounds that planting is not harvesting. The
	 * player's own experience drops say otherwise: an allotment, hops, herb or flower patch pays
	 * nothing when the seed goes in and pays the planting figure at the moment it is picked clean.
	 * See {@link CropYieldModel#clearedPatchXp} for the four families and the drops behind them.
	 * Not modelling it made nearly every replanted patch report a mismatch — watermelon by 48.5,
	 * snape grass by 82, cotton by 72 — with the residual sitting on the planting constant to a
	 * tenth, over seventy patches for watermelon alone.
	 *
	 * <h2>Why the prediction moved and not the record</h2>
	 *
	 * The alternative was to keep predicting the picks and stop {@link #addXp} from attributing
	 * that award to the harvest. That is the wrong half to change twice over. {@code xpGained} is
	 * the <i>observation</i> — it is what the CSV means by actual_xp, and the experience really
	 * did arrive, on that tick, for that patch, because that patch was harvested. Editing an
	 * observation to agree with a model is how a validation log stops being evidence. And the
	 * award only exists at all because the patch was cleared, so the prediction can say exactly
	 * when to expect it: {@link #completed} is the same fact, already recorded.
	 *
	 * <p>Which is also why a patch left standing predicts none of it, and that is the control
	 * group rather than an assumption: the eighteen incomplete watermelon rows in the CSV sit
	 * within a point of their prediction, fourteen of them within a tenth.
	 */
	public double getPredictedXp()
	{
		CropXp xp = CropXp.forProduce(produce);
		if (xp == null)
		{
			return 0;
		}

		Seed seed = Seed.forProduce(produce);
		// Flowers pay once for the patch however many items come out, so the count of items
		// picked is the wrong multiplier for them.
		double harvest = xp.getHarvestXp()
			* CropYieldModel.xpHarvestsFor(seed, getItemsHarvested());
		double cleared = completed ? CropYieldModel.clearedPatchXp(seed) : 0;
		return bonuses.applyOutfit(harvest + cleared);
	}
}
