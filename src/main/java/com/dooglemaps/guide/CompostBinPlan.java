package com.dooglemaps.guide;

import com.dooglemaps.data.CompostBin;
import com.dooglemaps.data.Compostables;
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
 * fresh, because arriving is what refreshes it.
 *
 * <h2>The clock is the one thing the snapshot cannot supply</h2>
 *
 * A closed bin used to get silence whenever its varbit still said {@code GROWING}, on the
 * reasoning that the varbit corrects itself the moment the player is near enough to act. It does
 * not, and the reason is two files away: the generated data marks both bins
 * <b>health-check-required</b>, so {@code GrowthTimer.project} deliberately declines to promote a
 * finished {@code GROWING} patch to {@code HARVESTABLE} — that promotion is reserved for crops
 * whose transition is a growth tick rather than a player action, and a bin was swept up in the
 * flag it shares with trees.
 *
 * <p>Everything else on the compost path already treats such a bin as ready.
 * {@code RunPlanner.binActionable} keeps its stop open on {@code isReady()}, and
 * {@code RunPlanner.binWork} budgets it a full bin of buckets and its ash. Only this file
 * disagreed — so the loadout packed fifty volcanic ash for the guild's big bin, the run routed the
 * player to it and then refused to let the stop finish, and arriving produced no step at all.
 * Reported from play. {@code clockReady} is that same {@code isReady()}, passed in rather than
 * re-derived, so the guide and the planner cannot answer the question differently again.
 */
public final class CompostBinPlan
{
	private CompostBinPlan()
	{
	}

	/**
	 * Everything still to do at one bin, next thing first. Empty when it wants nothing.
	 *
	 * @param clockReady the projection's {@code isReady()} — what promotes a closed bin whose
	 *                   varbit has not caught up yet; see the class note
	 */
	public static List<GuideStep> forBin(CompostBin bin, FarmPatch patch,
		@Nullable PatchSnapshot snapshot, CompostRunStore store, CarriedItems carried,
		LeprechaunStore leprechaun, boolean clockReady,
		java.util.Map<Integer, Integer> compostToKeep)
	{
		List<GuideStep> steps = new ArrayList<>();
		if (snapshot == null || snapshot.getCropState() == null)
		{
			return steps;
		}

		switch (snapshot.getCropState())
		{
			case HARVESTABLE:
				// The decoded stage is the bucket count, and stage 0 is the last bucket.
				addEmptyingSteps(steps, bin, patch, snapshot, store, carried, leprechaun,
					snapshot.getStage() + 1);
				return steps;
			case EMPTY:
				addFillAndDeposit(steps, bin, patch, bin.getCapacity(), store, carried,
					compostToKeep);
				return steps;
			case FILLING:
				addFillingSteps(steps, bin, patch, snapshot, store, carried, compostToKeep);
				return steps;
			default:
				// GROWING: closed and composting. Silent until the clock says it is done, and
				// then treated exactly as a ready bin — the same call the planner already makes.
				//
				// A whole bin, not the decoded stage: while GROWING that stage counts composting
				// progress (0..2), so reading it as a bucket count would say "1 of supercompost
				// left in it" about a bin holding thirty. RunPlanner.binWork makes the same
				// substitution for the same reason.
				if (clockReady)
				{
					addEmptyingSteps(steps, bin, patch, snapshot, store, carried, leprechaun,
						bin.getCapacity());
				}
				return steps;
		}
	}

	/**
	 * A ready bin: the ash first, then buckets in, compost out, buckets to the leprechaun.
	 *
	 * <p>Each return hands back one action and re-derives after it happens, which is what
	 * keeps the ash ahead of the first bucket by construction.
	 *
	 * @param remaining how much compost is still in the bin, in buckets. The caller's, because
	 *                  only it knows whether the snapshot's stage is a bucket count or a
	 *                  composting stage — see {@link #forBin}.
	 */
	private static void addEmptyingSteps(List<GuideStep> steps, CompostBin bin, FarmPatch patch,
		PatchSnapshot snapshot, CompostRunStore store, CarriedItems carried,
		LeprechaunStore leprechaun, int remaining)
	{
		// The upgrade, before anything comes out. Gated on the ash actually being in the pack —
		// an instruction that cannot be followed is the loadout's failure to prevent, not a step
		// to display — and on the bin holding supercompost, which is the only tier ash improves.
		if (store.isAshing() && isSupercompost(snapshot.getProduce())
			&& carried.getInventoryCount(CompostBin.VOLCANIC_ASH) >= bin.ashNeeded())
		{
			// ...but the buckets the emptying behind it will want are fetched first.
			//
			// Reported from play: "I've walked past the lep on my way to put volcanic ash in the
			// compost bin, why didn't I grab empty buckets first." The ash step used to return
			// here, so the bucket requirement was invisible until the ash was already in — and
			// the answer arrived as a walk back to a leprechaun the player had just passed.
			//
			// The follow-up was the interesting part: "depending on where you tele in, you might
			// not walk past the lep, then putting the ash on the bin first might make sense."
			// It does not, and this is the one ordering question at a bin that needs no geometry
			// to settle. Both orders have to END at the bin, so ash-first is always buckets-first
			// plus the detour d(entry,bin) + d(bin,lep) - d(entry,lep), which is never negative:
			// there are no teleports inside a stop, so the distances are metric and the triangle
			// inequality holds. Buckets-first is no worse from ANY tile you can arrive on.
			//
			// Where ash-first genuinely wins is when the leprechaun is not in the tour at all —
			// a bottomless bucket, rotten tomatoes, or buckets already in the pack. Those are
			// exactly the cases addBucketFetch declines to act on, so they cost nothing here.
			//
			// See docs/stop-planner-spec.md, which this is a narrow instance of. The general
			// version - ordering every errand at a stop by what it costs to walk and how many
			// leprechaun visits it takes - still wants doing; this one case happens to be
			// decidable without it.
			addBucketFetch(steps, patch, snapshot, carried, leprechaun, remaining);

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

		// Ordinary buckets: fetch before emptying, and only as many as fit.
		if (addBucketFetch(steps, patch, snapshot, carried, leprechaun, remaining))
		{
			return;
		}
		steps.add(GuideStep.of(GuideAction.EMPTY_BIN, patch,
			"Empty the bin into your buckets - " + remaining + " of "
				+ snapshot.getProduce().getName().toLowerCase() + " left in it."));
	}

	/**
	 * Fetches the empty buckets this bin will need, if it needs any and none are held.
	 *
	 * <p>Extracted so the ash branch and the emptying branch ask for them the same way — the
	 * whole point being that the fetch happens on whichever of those two the player reaches
	 * first, rather than only on the second. See the ash branch for why buckets-first is safe
	 * from any arrival tile.
	 *
	 * <p>Declines silently in every case where the leprechaun is not part of this bin's work at
	 * all: a bottomless bucket swallows the bin into one slot, rotten tomatoes come out by hand,
	 * buckets already in the pack need no trip, and a leprechaun with none to give has nothing to
	 * offer. That is what makes it safe to call ahead of the ash without inventing a trip.
	 *
	 * @return true if the caller should stop here, because a blocking errand was raised instead
	 */
	private static boolean addBucketFetch(List<GuideStep> steps, FarmPatch patch,
		PatchSnapshot snapshot, CarriedItems carried, LeprechaunStore leprechaun, int remaining)
	{
		if (carried.hasAny(ItemID.BOTTOMLESS_COMPOST_BUCKET,
				ItemID.BOTTOMLESS_COMPOST_BUCKET_FILLED)
			|| isRottenTomatoes(snapshot.getProduce()))
		{
			return false;
		}

		// Enough, not any.
		//
		// This declined the moment a single empty bucket was in the pack — "buckets already in
		// the pack need no trip" — which is right in spirit and wrong in degree: a bucket comes
		// out one compost at a time, so a bin holding thirty wants thirty. Withdraw one and the
		// step vanished, taking the highlight with it and leaving the player at a full bin with
		// nothing being asked of them. Reported from play at the guild's big bin, whose own
		// diagnostic line said it plainly: "so 30 bucket(s)".
		int held = carried.getInventoryCount(ItemID.BUCKET_EMPTY);
		if (held >= remaining)
		{
			return false;
		}

		// Out of room first, because with none there is nothing to fetch into and the
		// filled buckets in the pack ARE the room. He is standing here and stores a
		// thousand of each tier, so handing them over is the errand and the way to carry
		// on in one move.
		//
		// Without this a bin deadlocked whenever the pack was tight: fetch one bucket,
		// fill it, be told to fetch another with nowhere to put it, forever. A bin should
		// empty from one free slot, a bucket at a time - it is slow, not impossible.
		if (carried.getFreeSlots() == 0)
		{
			// Everything, keeping nothing back: with no free slot the run cannot proceed
			// at all, and a bucket held for a patch four steps away is worth less than
			// being able to take the next step. The leprechaun hands it straight back.
			int before = steps.size();
			addDepositStep(steps, patch, carried, java.util.Collections.emptyMap());
			if (steps.size() > before)
			{
				return true;
			}

			// No compost to hand over and no room to fetch into: there is no bucket
			// instruction that can be followed from here. This used to fall through to the
			// fetch below, whose floor asked for one bucket regardless — "withdraw 1 empty
			// bucket" into a pack with nowhere to put it. Reported from play as the free-slot
			// maths being off by one; the maths was right and the floor was wrong. The other
			// steps at the stop are what make room — the note step for a full harvest, the
			// fill step for a bin standing open — and the fetch comes back the tick a slot
			// does.
			return false;
		}

		if (leprechaun.has(FarmingTool.EMPTY_BUCKET))
		{
			// Less what is already in hand, so a part-fetched trip asks for the rest rather than
			// for the lot again. No floor: a free slot is guaranteed above, and min() already
			// answers 1 in the one-free-slot case — a bin empties a bucket at a time from a
			// single slot, slow but possible. The old max(1, ...) only ever changed the answer
			// at zero free slots, where it asked for a bucket that could not fit.
			int take = Math.min(remaining - held, carried.getFreeSlots());
			steps.add(GuideStep.atLeprechaun(GuideAction.WITHDRAW_TOOL, patch,
				ItemID.BUCKET_EMPTY, null,
				"Withdraw " + take + " empty bucket" + (take == 1 ? "" : "s")
					+ " from the tool leprechaun."));
		}
		return false;
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
		CarriedItems carried, java.util.Map<Integer, Integer> compostToKeep)
	{
		int held = 0;
		int shownItem = -1;
		for (int bucket : new int[]{ItemID.BUCKET_COMPOST, ItemID.BUCKET_SUPERCOMPOST,
			ItemID.BUCKET_ULTRACOMPOST})
		{
			// Only the surplus. Compost the patches at this stop are about to be treated with is
			// not clutter, and telling the player to store it sent them back to the leprechaun
			// for the same buckets minutes later. See GuideTracker.compostThisStopWillUse for the
			// report and the log that showed the order.
			int spare = carried.getInventoryCount(bucket)
				- compostToKeep.getOrDefault(bucket, 0);
			if (spare <= 0)
			{
				continue;
			}
			if (shownItem == -1)
			{
				shownItem = bucket;
			}
			held += spare;
		}

		if (held == 0)
		{
			return;
		}

		// No trailing "withdraw them at any patch later" - the same call the note step already
		// took, and for the same reason: a player who has used the leprechaun's store once knows
		// it is not a one-way trip, and reading it at every bin is noise. Removed by request.
		steps.add(GuideStep.atLeprechaun(GuideAction.DEPOSIT_COMPOST, patch, shownItem, null,
			"Store your " + held + " bucket" + (held == 1 ? "" : "s")
				+ " of compost with the tool leprechaun."));
	}

	/**
	 * The least a fodder fill is worth a step for, as a fraction of the bin.
	 *
	 * <p>Harvest-feeding means offering whatever the patch happened to give you, and a patch can
	 * give you two. "Put 2 watermelons in the bin" is a step that costs a click and a line on the
	 * panel to move a bin a fifteenth of the way, and the bin keeps its contents between runs, so
	 * there is no urgency to it. A third is enough to be worth saying and low enough that one
	 * decent allotment always clears it.
	 *
	 * <p>Only fodder is held to it. A bank fill was withdrawn on purpose and every last one of it
	 * should go in — leaving pineapples in the pack because there were only four is the opposite
	 * of helpful.
	 *
	 * <p><b>And it does not apply to a full pack.</b> The whole argument above is that a token
	 * fill is not worth the click, and that argument is about a pack with room in it. With no
	 * room the bin is not a tidy-up, it is the only way to carry on — eight watermelons into it
	 * is eight slots back and the harvest continues. Reported from play at the Farming Guild:
	 * pack full mid watermelon harvest, an empty big bin standing beside the allotment, and the
	 * guide asking for more watermelons. The big bin makes this sharper than the small ones,
	 * since a third of thirty is ten and one allotment rarely clears that.
	 */
	private static final int FODDER_MINIMUM_DIVISOR = 3;

	/**
	 * Filling an empty or part-filled bin, from the harvest in your pack or from the bank queue.
	 *
	 * <h2>Two sources, tried in that order</h2>
	 *
	 * <b>Fodder</b> is what you have just picked, at the bin standing beside the patch that gave
	 * it to you. It is the only source the seven bins beside the allotments have, and it is the
	 * cheap one everywhere: no bank trip, no pack space, no travel. Players were doing it by hand
	 * long before the plugin offered it.
	 *
	 * <p><b>The bank queue</b> is the fallback, and only the guild's big bin has it — the one bin
	 * with a bank in its own region. See {@code CompostRunStore} for why the two are different
	 * questions rather than one list used twice.
	 *
	 * <p>Quiet when neither can supply it. An instruction to fill with produce that is in a bank
	 * is one that cannot be followed from here, and the idle report completes the stop exactly as
	 * it does for a patch whose seed was left behind.
	 */
	private static void addFillStep(List<GuideStep> steps, CompostBin bin, FarmPatch patch,
		int wanted, CompostRunStore store, CarriedItems carried)
	{
		// One item per bin, never a mixture: a bin filled with anything that is not entirely
		// supercompostable makes ordinary compost, so running out of pineapples half way through
		// and topping up with potatoes would quietly cost the tier.
		//
		// Un-noted only, which is the whole reason this counts the pack rather than the bank: a
		// bin refuses notes, so noted produce in the inventory is no nearer to usable than
		// produce still in a bank.
		int fill = bestFodder(bin, wanted, store, carried);
		if (fill == CompostRunStore.NO_FILL && bin == CompostBin.BIG)
		{
			// The queue's first item that is actually in the pack. Spilling to the next fill
			// happens at the NEXT bin, which is what the queue means. See getFills.
			for (int candidate : store.getFills())
			{
				if (carried.getInventoryCount(candidate) > 0)
				{
					fill = candidate;
					break;
				}
			}
		}

		int held = fill == CompostRunStore.NO_FILL ? 0 : carried.getInventoryCount(fill);
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

	/**
	 * The best allowed crop in the pack to feed this bin, or {@link CompostRunStore#NO_FILL}.
	 *
	 * <h2>Tier first, then quantity</h2>
	 *
	 * A bin filled entirely with supercompostables makes supercompost; one grain of anything else
	 * in it and the whole bin is ordinary. So a pack holding twenty potatoes and fifteen
	 * watermelons should be offered the watermelons, even though the potatoes are the bigger
	 * stack — the tier is worth far more than the four items of headroom. Only when no
	 * supercompostable can fill the bin on its own does the largest stack win, because then the
	 * tier is already lost whatever goes in.
	 *
	 * <p>Both halves of {@code allowsFodder} matter: a player who switched fodder off keeps their
	 * picks, and nothing acts on them until it is switched back on.
	 */
	/**
	 * The crop this bin would take off you right now, or {@link CompostRunStore#NO_FILL}.
	 *
	 * <p>Public because two decisions outside the fill step turn on the same answer, and
	 * re-stating the rule in either of them is how they come to disagree: whether the
	 * leprechaun's "note this" should give way to the bin, and whether a bin waiting to be fed
	 * should jump the queue. Both mean "the bin will take this, now", which is this method.
	 */
	public static int fodderFor(CompostBin bin, int wanted, CompostRunStore store,
		CarriedItems carried)
	{
		return bestFodder(bin, wanted, store, carried);
	}

	private static int bestFodder(CompostBin bin, int wanted, CompostRunStore store,
		CarriedItems carried)
	{
		if (!store.isFodderEnabled())
		{
			return CompostRunStore.NO_FILL;
		}

		// The minimum is about whether a fill is worth *starting*. Two states where it is not the
		// question being asked, and in both it is waived:
		//
		//   - a full pack, because the bin is then the only way to carry on picking;
		//   - a bin already part filled, because that fill is under way and stopping halfway is
		//     worse than a token fill ever was.
		//
		// The second was reported from play the moment the first shipped, and the two are the
		// same event a second apart: the pack fills, the bin is offered, and putting the first
		// melon in frees a slot — which re-imposed the minimum, took the step away mid fill, and
		// sent the player off to another patch with the bin standing open. See
		// FODDER_MINIMUM_DIVISOR.
		//
		// "Part filled" is wanted < capacity, which is what addFillingSteps passes and what an
		// empty bin never does, so the caller has already answered it.
		boolean startingFromEmpty = wanted >= bin.getCapacity();
		int minimum = carried.getFreeSlots() > 0 && startingFromEmpty
			? Math.max(1, bin.getCapacity() / FODDER_MINIMUM_DIVISOR)
			: 1;
		int best = CompostRunStore.NO_FILL;
		int bestHeld = 0;
		boolean bestIsSuper = false;

		for (int crop : store.getFodderCrops())
		{
			int held = carried.getInventoryCount(crop);
			if (held < minimum)
			{
				continue;
			}
			boolean isSuper = Compostables.isSuperCompostable(crop) && held >= wanted;
			if (best == CompostRunStore.NO_FILL
				|| (isSuper && !bestIsSuper)
				|| (isSuper == bestIsSuper && held > bestHeld))
			{
				best = crop;
				bestHeld = held;
				bestIsSuper = isSuper;
			}
		}
		return best;
	}

	/** A part-filled bin: full means close it, anything less means top it up. */
	private static void addFillingSteps(List<GuideStep> steps, CompostBin bin, FarmPatch patch,
		PatchSnapshot snapshot, CompostRunStore store, CarriedItems carried,
		java.util.Map<Integer, Integer> compostToKeep)
	{
		int itemsIn = snapshot.getStage() + 1;
		int remaining = bin.getCapacity() - itemsIn;
		if (remaining <= 0)
		{
			steps.add(GuideStep.of(GuideAction.CLOSE_BIN, patch,
				"Close the bin to start it composting - a full open bin composts nothing."));
			return;
		}

		addFillAndDeposit(steps, bin, patch, remaining, store, carried, compostToKeep);
	}

	/**
	 * The two things an open bin offers, in the order that matches why you are standing at it.
	 *
	 * <h2>The deposit used to come first, always</h2>
	 *
	 * Handing your spare buckets to the leprechaun before filling the bin is the right order for
	 * a <b>banked</b> fill: you arrived carrying both, the buckets are clutter from the bank trip
	 * and the pineapples are the errand.
	 *
	 * <p>It is exactly wrong for a <b>fodder</b> fill. At the guild you are always carrying
	 * ultracompost — it is what you are treating the patches with — so the deposit step always
	 * fired, always came first, and always pointed at the leprechaun. The player harvesting
	 * watermelons into a full pack was told to store their compost, and the instruction they
	 * needed, "put these in the bin", sat behind it and never became current. Reported from play
	 * three times as the bin not being offered at all; the bin decision log finally showed it
	 * answering "fill with 5982" while the panel said something else entirely.
	 *
	 * <p>So a fodder fill goes first and the deposit follows it. The buckets are not clutter in
	 * that case — they are the compost for the patches being replanted at this same stop — and
	 * the crop in the pack is the thing with nowhere else to go.
	 */
	private static void addFillAndDeposit(List<GuideStep> steps, CompostBin bin, FarmPatch patch,
		int wanted, CompostRunStore store, CarriedItems carried,
		java.util.Map<Integer, Integer> compostToKeep)
	{
		if (bestFodder(bin, wanted, store, carried) != CompostRunStore.NO_FILL)
		{
			addFillStep(steps, bin, patch, wanted, store, carried);
			addDepositStep(steps, patch, carried, compostToKeep);
			return;
		}

		addDepositStep(steps, patch, carried, compostToKeep);
		addFillStep(steps, bin, patch, wanted, store, carried);
	}

	/** Whether ash has anything to improve — it upgrades supercompost and nothing else. */
	/**
	 * Whether emptying this bin now would throw away an ultracompost upgrade.
	 *
	 * <p>The decision behind {@code GuideTracker.noteMissingAsh}, here rather than there because
	 * it is a fact about a bin and a pack — the same pair every other question in this class is
	 * asked of — and because a pure function is the half worth pinning.
	 *
	 * <p>Ash upgrades supercompost and nothing else, and it has to go in before the first bucket
	 * comes out: 25 ash (50 in the big bin) upgrades the whole bin, where a filled bucket costs 2
	 * on its own. So "not enough" is measured against the bin's whole requirement, not against
	 * having some.
	 */
	static boolean upgradeWouldBeLost(CompostBin bin, @Nullable Produce inBin, boolean ashing,
		int ashHeld)
	{
		return ashing && isSupercompost(inBin) && ashHeld < bin.ashNeeded();
	}

	private static boolean isSupercompost(@Nullable Produce produce)
	{
		return produce == Produce.SUPERCOMPOST || produce == Produce.BIG_SUPERCOMPOST;
	}

	private static boolean isRottenTomatoes(@Nullable Produce produce)
	{
		return produce == Produce.ROTTEN_TOMATO || produce == Produce.BIG_ROTTEN_TOMATO;
	}
}
