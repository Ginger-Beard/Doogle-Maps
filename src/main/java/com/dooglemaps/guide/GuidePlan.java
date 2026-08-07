package com.dooglemaps.guide;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingTool;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.ProtectionPayment;
import com.dooglemaps.timer.DiseaseRisk;
import com.dooglemaps.data.Seed;
import com.dooglemaps.state.BarbarianFarming;
import com.dooglemaps.state.CompostSelectionStore;
import com.dooglemaps.state.LeprechaunStore;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.state.SeedSelectionStore;
import com.dooglemaps.state.SeedSource;
import com.dooglemaps.timer.PatchProjection;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.runelite.api.gameval.ItemID;

/**
 * Turns a patch into the list of clicks that would deal with it.
 *
 * <p>The whole of guided mode's judgement lives here, and it is deliberately a <b>pure
 * function of the patch's current state</b> — no progress counter, no "which step am I on"
 * stored anywhere. Ask again after every change and the answer moves on by itself.
 *
 * <p>That matters more than it sounds. A stored step index has to be kept in step with a
 * player who does things out of order, walks away, or gets the compost on before you told
 * them to — and every one of those is a chance to end up insisting on something already done.
 * Deriving it means the guidance is simply never wrong about what has happened; the worst it
 * can do is be a tick behind.
 *
 * <p>Order within a patch is the spec's (§13.7), and matches what the OSRS Wiki's own farm-run
 * guide recommends: finish a patch before moving to the next one. Harvest, note what you cannot
 * carry, clear what is left, then compost, then seed — with the noting of everything else left
 * until the location is done, which is also the wiki's advice.
 */
public final class GuidePlan
{
	/**
	 * Free slots left before "go and note this" becomes the next thing to do.
	 *
	 * <p>Zero: the pack has to be <b>full</b>. It was one, which fired at 27 of 28 — one pick
	 * early, when there was still room for another herb. A guide that stops you a pick short
	 * of full is wrong in the direction that wastes a trip to the leprechaun.
	 */
	private static final int FULL_INVENTORY_SLACK = 0;

	private GuidePlan()
	{
	}

	/**
	 * Everything still to do at one patch, next thing first.
	 *
	 * <p>Empty when the patch wants nothing — either it is growing, or it has been dealt with.
	 *
	 * @param carried        what is in the pack, for deciding when to note and what to withdraw
	 * @param patchesToTreat how many patches at this stop want this patch's compost, so the
	 *                       withdrawal can name a number rather than leaving you guessing
	 */
	public static List<GuideStep> forPatch(PatchProjection projection, CompostTier applied,
		PlantingGroup group, Seed chosen, SeedInventoryStore seeds,
		CompostSelectionStore compostChoice,
		CarriedItems carried, LeprechaunStore leprechaun, BarbarianFarming barbarianFarming,
		boolean protecting, boolean harvestOnly, int patchesToTreat)
	{
		List<GuideStep> steps = new ArrayList<>();
		if (projection == null)
		{
			return steps;
		}

		FarmPatch patch = projection.getPatch();

		// 0. Weeds, before anything else — including before the harvest check, because a fully
		//    weedy patch is promoted to HARVESTABLE by the projection (last stage reached, time
		//    elapsed) and the guide cheerfully said "harvest the weeds".
		//
		// The stage is what separates this from an ordinary empty patch,
		// and it runs backwards: the rule is stage = 3 - varbit, so a fully raked patch is
		// WEEDS at stage 0 and anything above that has weeds left in it.
		//
		// Worth spelling out because "is it weeds" alone is not the test — a clean patch is
		// also weeds, which is exactly how the game encodes an empty one, and why isEmpty()
		// deliberately treats WEEDS as empty. Only the un-raked case needs an instruction, and
		// without it the guide said "treat the patch" and "plant the seed" on ground the game
		// would not accept either on. Never seen in play because autoweed was on.
		if (projection.getProduce() == Produce.WEEDS && projection.getStage() > 0)
		{
			addToolStep(steps, patch, FarmingTool.RAKE, carried, leprechaun);
			steps.add(GuideStep.of(GuideAction.CLEAR, patch, "Rake the weeds."));
			return steps;
		}

		// 0.5 Check it, for the crops that will not let you touch them until you have.
		//
		//     A tree, bush, cactus, calquat, celastrus or redwood finishes growing into a state the
		//     game still calls GROWING, and nothing about it is clickable except the check. Every
		//     branch below this one therefore declined to say anything: the patch fell through to
		//     "still growing, leave it alone", produced no step, and was never highlighted.
		//
		//     Reported as a finished cactus contract being skipped in favour of an avantoe two
		//     patches away. The almanac said ready and the planner had routed to it correctly —
		//     the guide simply had no word for the state it was in.
		//
		//     Before the harvest branch because it has to be: hasProduceToPick is false until the
		//     check happens, so the two can never both fire. It is also where the experience is —
		//     a magic tree pays over 13,000 for this click and almost nothing for the logs.
		if (projection.needsHealthCheck())
		{
			steps.add(GuideStep.of(GuideAction.CHECK_HEALTH, patch,
				"Check the health of the " + projection.getProduce().getName().toLowerCase() + "."));
			return steps;
		}

		// 0.6 A felled stump, which is not an empty patch and not something you can pick.
		//
		//     Before the harvest branch because the game gives a stump the same crop and the same
		//     HARVESTABLE state as the tree that was standing there a moment ago — so without this,
		//     branch 1 fires, says "harvest the magic" at a stump, and returns. It said it forever:
		//     nothing the player could click would change the state it was testing, so the patch
		//     never finished and the stop never completed. Reported from play as a yew contract
		//     that could not be started because the magic tree in front of it never came out.
		//
		//     A spade, not an axe. This is the one step in the sequence the leprechaun can help
		//     with, which is why it goes through addToolStep like the dead-crop clear below.
		//     Gated on the full run. "Come back and take the logs" does not include digging the
		//     stump out, and a harvest-only tree run is finished the moment the tree is down —
		//     which is why hasProduceToPick answers no for a stump, so the branch below lets it
		//     fall through to the harvest-only gate rather than looping on it.
		if (projection.isStump() && !harvestOnly)
		{
			addToolStep(steps, patch, FarmingTool.SPADE, carried, leprechaun);
			steps.add(GuideStep.of(GuideAction.CLEAR, patch,
				"Dig up the " + projection.getProduce().getName().toLowerCase() + " stump."));
			return steps;
		}

		// 0.7 A checked tree still standing. "Harvest" is the wrong word and the wrong expectation:
		//     you chop it, it does not empty the patch, and there is a stump behind it — which is
		//     what 0.6 above is for. Said separately so the player knows two clicks are coming
		//     rather than wondering why the patch is still occupied after the first.
		//
		//     No axe step. The leprechaun stores every farming tool except an axe, so there is
		//     nothing to withdraw here; carrying one is a bank-leg problem and RunLoadout says so.
		if (projection.isChoppable())
		{
			steps.add(GuideStep.of(GuideAction.CHOP, patch,
				"Chop down the " + projection.getProduce().getName().toLowerCase() + "."));
			return steps;
		}

		// 1. Take what is on it — if there is anything on it.
		//
		//    hasProduceToPick rather than the raw state. "Harvestable" for a regrowing crop means
		//    grown, not laden: a picked-clean fruit tree still reports HARVESTABLE, so this branch
		//    fired forever and said "harvest the papaya" at a tree with no papayas. It also
		//    returns, so the patch produced that one impossible step and nothing else — which is
		//    what left harvest-only stops unable to finish. See PatchProjection.hasProduceToPick.
		if (projection.hasProduceToPick())
		{
			if (carried.getFreeSlots() <= FULL_INVENTORY_SLACK)
			{
				steps.add(GuideStep.atLeprechaun(GuideAction.NOTE_AT_LEPRECHAUN, patch,
					projection.getProduce().getItemID(), null,
					"Your inventory is full - note the "
						+ projection.getProduce().getName().toLowerCase()
						+ " with the tool leprechaun."));
			}
			steps.add(GuideStep.of(GuideAction.HARVEST, patch,
				"Harvest the " + projection.getProduce().getName().toLowerCase() + "."));
			return steps;
		}

		// Harvest-only stops here. Everything below clears, treats or replants, and on a bush or
		// a fruit tree that means digging up something that took two days to grow — which is the
		// opposite of what "come back and pick the fruit" asked for.
		if (harvestOnly)
		{
			return steps;
		}

		// 2. Anything left in the ground has to come out before anything goes in. Dead crops
		//    and weeds are the same job from the player's side, so they read the same.
		if (projection.getCropState() == CropState.DEAD)
		{
			addToolStep(steps, patch, FarmingTool.SPADE, carried, leprechaun);
			steps.add(GuideStep.of(GuideAction.CLEAR, patch,
				"Clear the dead " + projection.getProduce().getName().toLowerCase() + "."));
			return steps;
		}

		// A crop still growing, or diseased and curable, wants leaving alone - guided mode is
		// about the patches a run actually services.
		if (!projection.isEmpty())
		{
			addProtectionStep(steps, projection, carried, protecting);

			// One exception: a seed that has just gone in, on a patch that was never treated.
			// Compost works just as well applied after planting — the wiki's own herb-run guide
			// sows first and composts second — so someone following that order used to get
			// silence here, and an untreated patch, precisely because they did it the other
			// way round. Limited to the first growth stage so it cannot start nagging about a
			// crop planted days ago.
			CompostTier wantedAfterPlanting = compostChoice.get(group);
			if (projection.getStage() == 0 && wantedAfterPlanting != CompostTier.NONE
				&& applied != wantedAfterPlanting
				&& projection.getCropState() == CropState.GROWING)
			{
				addCompostSteps(steps, patch, wantedAfterPlanting, carried, patchesToTreat);
			}
			return steps;
		}

		if (chosen == null)
		{
			return steps;
		}

		// 3. Compost, then the seed. Preferred in that order because it is one fewer thing to
		//    remember once a crop is in the ground — but not required: see the just-planted
		//    case above, which catches anyone doing it the wiki's way round.
		CompostTier wanted = compostChoice.get(group);
		if (wanted != CompostTier.NONE && applied != wanted)
		{
			addCompostSteps(steps, patch, wanted, carried, patchesToTreat);
			return steps;
		}

		// 4. Sow. If the seeds are in the box rather than the pack, that comes first - and the
		//    box is worth naming because Empty is not its left-click option by default.
		int perPatch = chosen.getSeedsPerPatch();
		if (seeds.getCount(chosen, SeedSource.INVENTORY) < perPatch
			&& seeds.getCount(chosen, SeedSource.SEED_BOX) >= perPatch)
		{
			// The box, not the seed. Highlighting the seed was the bug: the whole reason this step
			// exists is that the seed is *inside the box* and therefore not in the inventory, so
			// there was nothing on screen for the outline to land on and it silently drew nothing.
			// The thing to click is the box.
			steps.add(GuideStep.withItem(GuideAction.WITHDRAW_SEEDS, patch, seedBoxCarried(carried),
				"Empty your seed box to get the " + chosen.getName().toLowerCase() + "."));
		}

		// A dibber for anything sown from a seed. Saplings go in by hand, so a tree patch is
		// deliberately silent here rather than asking for a tool it does not use — and so is
		// every patch once Barbarian Farming has been seen, since it removes the requirement.
		if (!chosen.isSapling() && !barbarianFarming.isUnlocked())
		{
			addToolStep(steps, patch, FarmingTool.SEED_DIBBER, carried, leprechaun);
		}

		steps.add(GuideStep.withItem(GuideAction.PLANT, patch, chosen.getPlantedItemID(),
			plantText(chosen, perPatch)));
		return steps;
	}

	/**
	 * Paying the farmer, for a crop already in the ground.
	 *
	 * <p>Separate from the planting sequence because it applies to a <b>growing</b> patch, which
	 * every other branch above deliberately leaves alone. A tree you planted last night is
	 * exactly the case: nothing else about it wants doing, and the payment is the one thing still
	 * outstanding.
	 *
	 * <p>Silent unless the player asked for this group to be protected, the patch can actually be
	 * protected, and the payment is in the pack. The last of those is the interesting one — being
	 * told to pay with fruit you did not bring is an instruction you cannot follow, and the
	 * loadout is where that should have been caught.
	 */
	private static void addProtectionStep(List<GuideStep> steps, PatchProjection projection,
		CarriedItems carried, boolean protecting)
	{
		FarmPatch patch = projection.getPatch();
		if (!protecting || projection.getProduce() == null
			|| !DiseaseRisk.isProtectable(patch))
		{
			return;
		}

		ProtectionPayment payment = ProtectionPayment.forProduce(projection.getProduce());
		if (payment == null || carried.getCount(payment.getItemID()) < payment.getQuantity())
		{
			return;
		}

		steps.add(GuideStep.atNpc(GuideAction.PAY_FARMER, patch, payment.getItemID(),
			patch.getFarmer(),
			"Pay the farmer " + payment.getQuantity() + " "
				+ payment.getProduce().getName().toLowerCase()
				+ " to protect the " + projection.getProduce().getName().toLowerCase() + "."));
	}

	/**
	 * Asks for a tool the next step cannot be done without, when the leprechaun has one.
	 *
	 * <p>Inserted <i>before</i> the step that needs it, so the order reads the way the clicks go:
	 * fetch the rake, then rake the weeds. Ordinarily this adds nothing at all, because the tool
	 * is already in the pack — which is exactly the right amount of noise for something that is
	 * usually a non-issue and occasionally the whole reason a stop cannot be finished.
	 *
	 * <p>Silent when he has none either. There is nothing useful to say at a patch about a rake
	 * that is in your bank; that belongs to the loadout, before you set off, and
	 * {@code ToolNeeds} puts it there.
	 */
	private static void addToolStep(List<GuideStep> steps, FarmPatch patch, FarmingTool tool,
		CarriedItems carried, LeprechaunStore leprechaun)
	{
		if (carried.has(tool.getItemID()) || !leprechaun.has(tool))
		{
			return;
		}

		steps.add(GuideStep.atLeprechaun(GuideAction.WITHDRAW_TOOL, patch, tool.getItemID(), null,
			"Get your " + tool.getDisplayName().toLowerCase()
				+ " from the tool leprechaun - you are not carrying one."));
	}

	/**
	 * Whichever form of the seed box the player is carrying.
	 *
	 * <p>Two ids for one item, and the difference is only whether it is open. Defaulting to the
	 * closed one when neither is found is harmless: the step is only reached when the seeds are
	 * known to be in a box, so one of them is there.
	 */
	private static int seedBoxCarried(CarriedItems carried)
	{
		return carried.has(ItemID.SEED_BOX_OPEN) ? ItemID.SEED_BOX_OPEN : ItemID.SEED_BOX;
	}

	/**
	 * Getting compost onto a patch: withdraw it if needed, then apply it.
	 *
	 * <p>Shared, because this happens at two different moments — before the seed goes in, which
	 * is the order this guide prefers, and after it, which is the order the wiki's herb-run
	 * guide gives. Both work in game, so both have to work here.
	 */
	private static void addCompostSteps(List<GuideStep> steps, FarmPatch patch, CompostTier wanted,
		CarriedItems carried, int patchesToTreat)
	{
		// A bottomless bucket is compost, whatever tier it happens to hold — we cannot see
		// inside it, and the player knows what they filled it with. Both ids, because 22994 is
		// the *empty* one and 22997 the one holding compost, i.e. the only state in which it
		// is any use.
		boolean bottomless = carried.hasAny(ItemID.BOTTOMLESS_COMPOST_BUCKET,
			ItemID.BOTTOMLESS_COMPOST_BUCKET_FILLED);

		// Enough for the patches here, not merely some. A >0 check told you nothing while you
		// held one bucket and needed four, then asked again after each patch — which is what
		// made the counting look wrong.
		int held = carried.getCount(wanted.getItemID());
		if (!bottomless && held < Math.max(1, patchesToTreat))
		{
			steps.add(GuideStep.atLeprechaun(GuideAction.WITHDRAW_COMPOST, patch,
				wanted.getItemID(), null,
				withdrawText(wanted, Math.max(1, patchesToTreat - held))));
		}
		steps.add(GuideStep.withItem(GuideAction.APPLY_COMPOST, patch, wanted.getItemID(),
			"Treat the patch with " + wanted.getDisplayName().toLowerCase() + "."));
	}

	/**
	 * How much compost to take, and why that number.
	 *
	 * <p>Naming a quantity is the difference between a useful instruction and a vague one. It
	 * is deliberately only what <b>this stop</b> needs: every farming area has its own
	 * leprechaun holding the same thousand buckets, so carrying compost onward buys nothing and
	 * costs an inventory slot at the exact moment the next harvest wants it.
	 */
	private static String withdrawText(CompostTier tier, int patchesToTreat)
	{
		String what = tier.getDisplayName().toLowerCase();
		if (patchesToTreat <= 1)
		{
			return "Withdraw 1 " + what + " from the tool leprechaun.";
		}
		return "Withdraw " + patchesToTreat + " " + what + " from the tool leprechaun - "
			+ "one for each patch here.";
	}

	private static String plantText(Seed seed, int perPatch)
	{
		String noun = seed.isSapling() ? "sapling" : "seed";
		return perPatch == 1
			? "Plant the " + seed.getName().toLowerCase() + " " + noun + "."
			: "Plant " + perPatch + " " + seed.getName().toLowerCase() + " " + noun + "s.";
	}

}
