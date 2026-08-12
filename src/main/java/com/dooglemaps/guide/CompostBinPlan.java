package com.dooglemaps.guide;

import com.dooglemaps.data.CompostBin;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingTool;
import com.dooglemaps.data.Produce;
import com.dooglemaps.state.CompostRunStore;
import com.dooglemaps.state.LeprechaunStore;
import com.dooglemaps.state.PatchSnapshot;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.runelite.api.gameval.ItemID;

/**
 * Turns a compost bin into the list of clicks that would deal with it.
 *
 * <p>{@link GuidePlan}'s sibling rather than a branch inside it, because a bin shares nothing
 * with a patch but the varbit machinery: no seed, no compost <i>on</i> it, no farmer, no
 * disease. Same design, though — a <b>pure function of the bin's current state</b>, re-derived
 * every tick, so it can never insist on something already done. Each call returns the next
 * thing to do (with its prerequisite fetches in front of it) and lets the state change move
 * the answer on.
 *
 * <h2>The order is the owner's, and one line of it is money</h2>
 *
 * Ash first, while everything is still in the bin — 25 ash (50 in the big bin) upgrades the
 * whole thing, where a filled bucket costs 2 each. Then buckets from the leprechaun, empty the
 * bin, hand him the <b>full</b> buckets, fill with the chosen produce, close the lid. Every
 * click after the ash is inventory logistics; the ash's position is the one that cannot be
 * recovered once missed.
 *
 * <h2>Why this reads the snapshot rather than the projection</h2>
 *
 * The projection exists to move a crop forward through time, and in doing so it flattens the
 * two numbers a bin turns on: a {@code FILLING} bin's item count is not carried at all, and a
 * {@code HARVESTABLE} bin's bucket count survives only as {@code livesRemaining}. The snapshot
 * has both, as the raw decoded stage — and standing at the bin is exactly when the snapshot is
 * fresh, because arriving is what refreshes it. A {@code GROWING} snapshot (closed, composting)
 * gets silence even when the clock says it must have finished: the varbit corrects itself the
 * moment the player is near enough to act on it, which is also the only moment a step matters.
 */
public final class CompostBinPlan
{
	private CompostBinPlan()
	{
	}

	/** Everything still to do at one bin, next thing first. Empty when it wants nothing. */
	public static List<GuideStep> forBin(CompostBin bin, FarmPatch patch,
		@Nullable PatchSnapshot snapshot, CompostRunStore store, CarriedItems carried,
		LeprechaunStore leprechaun)
	{
		List<GuideStep> steps = new ArrayList<>();
		if (snapshot == null || snapshot.getCropState() == null)
		{
			return steps;
		}

		switch (snapshot.getCropState())
		{
			case HARVESTABLE:
				addEmptyingSteps(steps, bin, patch, snapshot, store, carried, leprechaun);
				return steps;
			case EMPTY:
				addDepositStep(steps, patch, carried);
				addFillStep(steps, bin, patch, bin.getCapacity(), store, carried);
				return steps;
			case FILLING:
				addFillingSteps(steps, bin, patch, snapshot, store, carried);
				return steps;
			default:
				// GROWING: closed and composting. Nothing to click, even when the clock says
				// it must be done — see the class note on snapshots.
				return steps;
		}
	}

	/**
	 * A ready bin: the ash first, then buckets in, compost out, buckets to the leprechaun.
	 *
	 * <p>Each return hands back one action and re-derives after it happens, which is what
	 * keeps the ash ahead of the first bucket by construction.
	 */
	private static void addEmptyingSteps(List<GuideStep> steps, CompostBin bin, FarmPatch patch,
		PatchSnapshot snapshot, CompostRunStore store, CarriedItems carried,
		LeprechaunStore leprechaun)
	{
		// The upgrade, before anything comes out. Gated on the ash actually being in the pack —
		// an instruction that cannot be followed is the loadout's failure to prevent, not a step
		// to display — and on the bin holding supercompost, which is the only tier ash improves.
		if (store.isAshing() && isSupercompost(snapshot.getProduce())
			&& carried.getInventoryCount(CompostBin.VOLCANIC_ASH) >= bin.ashNeeded())
		{
			steps.add(GuideStep.withItem(GuideAction.APPLY_ASH, patch, CompostBin.VOLCANIC_ASH,
				"Use " + bin.ashNeeded() + " volcanic ash on the bin - the whole bin upgrades "
					+ "to ultracompost, but only while the buckets are still in it."));
			return;
		}

		// A bottomless bucket swallows the whole bin into one slot, and what it holds is
		// usable straight from the bucket — so there is no leprechaun trip on this path at
		// all. Either id: 22994 is empty, 22997 holding something.
		if (carried.hasAny(ItemID.BOTTOMLESS_COMPOST_BUCKET,
			ItemID.BOTTOMLESS_COMPOST_BUCKET_FILLED))
		{
			steps.add(GuideStep.withItem(GuideAction.EMPTY_BIN, patch,
				ItemID.BOTTOMLESS_COMPOST_BUCKET_FILLED,
				"Empty the bin into your bottomless compost bucket."));
			return;
		}

		// Rotten tomatoes come out by hand — the one bin product that needs no bucket.
		if (isRottenTomatoes(snapshot.getProduce()))
		{
			steps.add(GuideStep.of(GuideAction.EMPTY_BIN, patch,
				"Take the rotten tomatoes out of the bin - this fill made no compost at all."));
			return;
		}

		// Ordinary buckets: fetch before emptying, and only as many as fit. remaining is the
		// snapshot's decoded count — stage 0 is the last bucket, see PatchRules.
		int remaining = snapshot.getStage() + 1;
		int empties = carried.getInventoryCount(ItemID.BUCKET_EMPTY);
		if (empties == 0 && leprechaun.has(FarmingTool.EMPTY_BUCKET))
		{
			int take = Math.max(1, Math.min(remaining, carried.getFreeSlots()));
			steps.add(GuideStep.atLeprechaun(GuideAction.WITHDRAW_TOOL, patch,
				ItemID.BUCKET_EMPTY, null,
				"Withdraw " + take + " empty bucket" + (take == 1 ? "" : "s")
					+ " from the tool leprechaun."));
		}
		steps.add(GuideStep.of(GuideAction.EMPTY_BIN, patch,
			"Empty the bin into your buckets - " + remaining + " of "
				+ snapshot.getProduce().getName().toLowerCase() + " left in it."));
	}

	/**
	 * The filled buckets go straight back to the leprechaun, before the bin is refilled.
	 *
	 * <p>Before, not after, because the fill needs the slots: a normal bin's fill is fifteen
	 * un-noted items, and a pack still holding fifteen full buckets has no room for them. He
	 * stores a thousand of each tier and stands beside every bin, so the compost comes back
	 * out of his store at whichever patch wants it — carrying it onward buys nothing.
	 */
	private static void addDepositStep(List<GuideStep> steps, FarmPatch patch,
		CarriedItems carried)
	{
		int held = 0;
		int shownItem = -1;
		for (int bucket : new int[]{ItemID.BUCKET_COMPOST, ItemID.BUCKET_SUPERCOMPOST,
			ItemID.BUCKET_ULTRACOMPOST})
		{
			int count = carried.getInventoryCount(bucket);
			if (count > 0 && shownItem == -1)
			{
				shownItem = bucket;
			}
			held += count;
		}

		if (held == 0)
		{
			return;
		}

		steps.add(GuideStep.atLeprechaun(GuideAction.DEPOSIT_COMPOST, patch, shownItem, null,
			"Store your " + held + " bucket" + (held == 1 ? "" : "s")
				+ " of compost with the tool leprechaun - withdraw them at any patch later."));
	}

	/**
	 * Filling an empty or part-filled bin with the chosen produce.
	 *
	 * <p>Quiet when none of it is in the pack. The supply leg is where the fifteen un-noted
	 * items come from — the bin takes no notes — and an instruction to fill with produce that
	 * is in a bank is one that cannot be followed from here. The idle report completes the
	 * stop, exactly as it does for a patch whose seed was left behind.
	 */
	private static void addFillStep(List<GuideStep> steps, CompostBin bin, FarmPatch patch,
		int wanted, CompostRunStore store, CarriedItems carried)
	{
		// The queue's first item that is actually in the pack. One item per bin, never a
		// mixture: a bin filled with anything that is not entirely supercompostable makes
		// ordinary compost, so running out of pineapples half way through and topping up with
		// potatoes would quietly cost the tier. Spilling to the next fill happens at the NEXT
		// bin, which is what the queue means. See CompostRunStore.getFills.
		//
		// Un-noted only, which is the whole reason this counts the pack rather than the bank:
		// a bin refuses notes, so noted produce in the inventory is no nearer to usable than
		// produce still in a bank.
		int fill = CompostRunStore.NO_FILL;
		int held = 0;
		for (int candidate : store.getFills())
		{
			int carrying = carried.getInventoryCount(candidate);
			if (carrying > 0)
			{
				fill = candidate;
				held = carrying;
				break;
			}
		}
		if (fill == CompostRunStore.NO_FILL || held == 0)
		{
			return;
		}

		// What this stop can actually put in, which is not always what the bin wants. Nothing
		// supercompostable stacks, so a full big bin is thirty slots against an inventory of
		// twenty-eight and cannot be done in one load however it is arranged. Saying "fill the
		// bin" to someone holding ten was an instruction that ended halfway through with no
		// word about what had gone wrong; a part-filled bin is a perfectly good place to stop,
		// and the count is what tells the player they are coming back.
		int canAdd = Math.min(held, wanted);
		String text;
		if (canAdd >= wanted)
		{
			text = wanted >= bin.getCapacity()
				? "Fill the bin - it takes " + bin.getCapacity() + " un-noted items."
				: "Add " + wanted + " more to the bin.";
		}
		else
		{
			text = "Add your " + canAdd + " to the bin - " + (wanted - canAdd) + " short of "
				+ (wanted >= bin.getCapacity() ? "a full bin" : "closing it")
				+ ", so it will want another load.";
		}

		steps.add(GuideStep.withItem(GuideAction.FILL_BIN, patch, fill, text));
	}

	/** A part-filled bin: full means close it, anything less means top it up. */
	private static void addFillingSteps(List<GuideStep> steps, CompostBin bin, FarmPatch patch,
		PatchSnapshot snapshot, CompostRunStore store, CarriedItems carried)
	{
		int itemsIn = snapshot.getStage() + 1;
		int remaining = bin.getCapacity() - itemsIn;
		if (remaining <= 0)
		{
			steps.add(GuideStep.of(GuideAction.CLOSE_BIN, patch,
				"Close the bin to start it composting - a full open bin composts nothing."));
			return;
		}

		addDepositStep(steps, patch, carried);
		addFillStep(steps, bin, patch, remaining, store, carried);
	}

	/** Whether ash has anything to improve — it upgrades supercompost and nothing else. */
	private static boolean isSupercompost(@Nullable Produce produce)
	{
		return produce == Produce.SUPERCOMPOST || produce == Produce.BIG_SUPERCOMPOST;
	}

	private static boolean isRottenTomatoes(@Nullable Produce produce)
	{
		return produce == Produce.ROTTEN_TOMATO || produce == Produce.BIG_ROTTEN_TOMATO;
	}
}
