package com.dooglemaps.guide;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.Seed;
import com.dooglemaps.state.CompostSelectionStore;
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
		Seed chosen, SeedInventoryStore seeds, CompostSelectionStore compostChoice,
		CarriedItems carried, int patchesToTreat)
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
			steps.add(GuideStep.of(GuideAction.CLEAR, patch, "Rake the weeds."));
			return steps;
		}

		// 1. Take what is on it. A regrowing crop counts as harvestable while it still holds
		//    fruit, which is why this is a state test rather than "is the patch finished".
		if (projection.getCropState() == CropState.HARVESTABLE)
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

		// 2. Anything left in the ground has to come out before anything goes in. Dead crops
		//    and weeds are the same job from the player's side, so they read the same.
		if (projection.getCropState() == CropState.DEAD)
		{
			steps.add(GuideStep.of(GuideAction.CLEAR, patch,
				"Clear the dead " + projection.getProduce().getName().toLowerCase() + "."));
			return steps;
		}

		// A crop still growing, or diseased and curable, wants leaving alone - guided mode is
		// about the patches a run actually services.
		if (!projection.isEmpty())
		{
			// One exception: a seed that has just gone in, on a patch that was never treated.
			// Compost works just as well applied after planting — the wiki's own herb-run guide
			// sows first and composts second — so someone following that order used to get
			// silence here, and an untreated patch, precisely because they did it the other
			// way round. Limited to the first growth stage so it cannot start nagging about a
			// crop planted days ago.
			CompostTier wantedAfterPlanting = compostChoice.get(patch.getImplementation());
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
		CompostTier wanted = compostChoice.get(patch.getImplementation());
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
			steps.add(GuideStep.withItem(GuideAction.WITHDRAW_SEEDS, patch,
				chosen.getPlantedItemID(),
				"Empty your seed box to get the " + chosen.getName().toLowerCase() + "."));
		}

		steps.add(GuideStep.withItem(GuideAction.PLANT, patch, chosen.getPlantedItemID(),
			plantText(chosen, perPatch)));
		return steps;
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

	/**
	 * The seed the run will put in this patch, or null if none was picked for it.
	 *
	 * <p>Best of what was selected for that patch type and is actually plantable — a tree seed
	 * that has not been potted cannot be the answer, however many you own.
	 */
	@Nullable
	public static Seed seedFor(PatchImplementation type, SeedSelectionStore selection,
		SeedInventoryStore seeds)
	{
		Seed best = null;
		for (Seed seed : selection.getSelectedFor(type))
		{
			if (seeds.getOwnedPlantable(seed) < seed.getSeedsPerPatch())
			{
				continue;
			}
			if (best == null || seed.getLevelRequirement() > best.getLevelRequirement())
			{
				best = seed;
			}
		}
		return best;
	}
}
