package com.dooglemaps.guide;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingTool;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PayToClear;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.ProtectionPayment;
import com.dooglemaps.data.SpadeClearedCrops;
import com.dooglemaps.timer.DiseaseRisk;
import com.dooglemaps.data.Seed;
import com.dooglemaps.state.BarbarianFarming;
import com.dooglemaps.state.CompostSelectionStore;
import com.dooglemaps.state.LeprechaunStore;
import com.dooglemaps.state.PayToClearStore;
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

	/**
	 * The families whose <b>harvest</b> takes a spade — the game refuses the pick without one.
	 *
	 * <p>Herbs are wiki-stated ("a spade to harvest herbs") and were the report: the guide
	 * said "harvest" at a patch the game would not let the player touch, spades in the bank
	 * and the leprechaun's store both. Allotment crops come out of the ground the same way.
	 * Flowers, hops and every regrowing family pick by hand and are deliberately absent —
	 * extend this on evidence, not symmetry, since a wrong entry costs a pointless
	 * leprechaun errand.
	 */
	private static final java.util.Set<PatchImplementation> SPADE_HARVESTED =
		java.util.EnumSet.of(PatchImplementation.HERB, PatchImplementation.ALLOTMENT);

	private GuidePlan()
	{
	}

	/**
	 * An English plural for a crop or patch-type name, for the step wording.
	 *
	 * <h2>The -y rule is why this is here rather than inlined</h2>
	 *
	 * It lived in {@code GuideTracker}, where every caller passed a patch-type name — "herb",
	 * "allotment" — and none of them ended in a consonant and a y, so adding an s was always
	 * right. The shortened note step passes <b>produce</b> names, and the first one anybody would
	 * read it on is the strawberry: "note your strawberrys" is the kind of wrong that makes a
	 * plugin look unfinished.
	 *
	 * <p>Vowel then y keeps the s — a "storey" is "storeys" — which is the rule rather than an
	 * exception to it.
	 */
	static String plural(String name)
	{
		if (name == null || name.isEmpty())
		{
			return name;
		}
		if (name.endsWith("s") || name.endsWith("x") || name.endsWith("z")
			|| name.endsWith("ch") || name.endsWith("sh"))
		{
			return name + "es";
		}
		if (name.endsWith("y") && name.length() > 1
			&& "aeiou".indexOf(name.charAt(name.length() - 2)) < 0)
		{
			return name.substring(0, name.length() - 1) + "ies";
		}
		return name + "s";
	}

	/**
	 * Everything still to do at one patch, next thing first.
	 *
	 * <p>Empty when the patch wants nothing — either it is growing, or it has been dealt with.
	 *
	 * @param carried        what is in the pack, for deciding when to note and what to withdraw
	 * @param protecting     whether a payment step is still owed — false once the farmer has
	 *                       taken it, which is why it cannot answer the compost question
	 * @param paidToProtect  whether this crop is or will be protected by payment, paid or not.
	 *                       Only the compost branches want this: a bucket on a protected seed
	 *                       with no lives mechanic buys nothing, and "the farmer has already
	 *                       been paid" is the moment {@code protecting} goes false while the
	 *                       crop is at its most protected. See
	 *                       {@code CropYieldModel.compostWastedOnProtected}.
	 * @param compostChoice  the run's compost tier preference per group
	 * @param payToClear     which crops the player would rather buy a gardener out of clearing
	 *                       than chop themselves. Resolved from the projection's own standing
	 *                       produce inside this method — see the 0.65 branch below — never from
	 *                       {@code chosen} or an allocation's pick.
	 * @param patchesToTreat how many patches at this stop want this patch's compost, so the
	 *                       withdrawal can name a number rather than leaving you guessing
	 */
	public static List<GuideStep> forPatch(PatchProjection projection, CompostTier applied,
		PlantingGroup group, Seed chosen, SeedInventoryStore seeds,
		CompostSelectionStore compostChoice, @Nullable PayToClearStore payToClear,
		CarriedItems carried, LeprechaunStore leprechaun, BarbarianFarming barbarianFarming,
		boolean protecting, boolean paidToProtect, boolean harvestOnly, int patchesToTreat,
		boolean binWantsThisCrop,
		com.dooglemaps.data.ItemNames itemNames)
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
		// The vinery and the coral nurseries are excluded for the same reason: their empty
		// states merely DECODE as weeds. Core's own menu comments are the provenance - an
		// empty nursery is "Coral nursery[Inspect,Guide]", no Rake option at all - so the
		// step would name a click the menu does not offer, forever. The state falls through
		// to isEmpty below, which is what it really is.
		if (projection.getProduce() == Produce.WEEDS && projection.getStage() > 0
			&& patch.getImplementation() != PatchImplementation.GRAPES
			&& patch.getImplementation() != PatchImplementation.CORAL)
		{
			addToolStep(steps, patch, FarmingTool.RAKE, carried, leprechaun);
			steps.add(GuideStep.of(GuideAction.CLEAR, patch, "Rake the weeds."));
			return steps;
		}

		// 0.2 The vinery, whose empty states the decode disguises as weeds — a grape patch is
		//     never raked, which is why it is excluded from the branch above. Varbit 0 is
		//     untreated soil and 1 is saltpetred; upstream's source comments are the provenance
		//     ("Empty, empty+fertilizer"), verified in the PatchRules audit. Without this the
		//     guide said "rake the weeds" and then "plant the grape seed" at soil the game
		//     refuses both on. State-driven: treat it, the varbit moves to 1, and the next
		//     tick offers the planting.
		if (patch.getImplementation() == PatchImplementation.GRAPES
			&& projection.getVarbitValue() == 0
			&& !harvestOnly && chosen != null)
		{
			steps.add(GuideStep.withItem(GuideAction.CLEAR, patch, ItemID.HOSIDIUS_SALTPETRE,
				"Treat the soil with saltpetre before anything can be planted."));
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

		// 0.65 Paying a gardener to fell a checked tree, standing in for the chop below.
		//
		//      Sits here rather than above the health check or the stump branch, and both of
		//      those placements would be wrong for a reason rather than by accident. The check
		//      has already returned by the time this line is reached — its own branch above
		//      returns unconditionally — so paying before the health check happens is impossible
		//      by construction, and no check-health experience is ever at risk. A stump is
		//      already the player's own doing (they, or someone, already chopped it); there is
		//      nothing left to pay a gardener to fell, so it stays the spade's job untouched.
		//      Fruit trees pick first, above this branch entirely, because isChoppable() already
		//      excludes a fruit tree still carrying fruit — the same test the chop branch below
		//      makes, so a laden tree is never offered for payment either.
		//
		//      Resolved from the standing crop via {@code payToClear.isPayingFor(projection)},
		//      never from {@code chosen} — see PayToClearStore's own class note. A checked,
		//      standing tree is HARVESTABLE, so it is an ordinary member of a seed allocation's
		//      plantable list, and the run may already have picked a different seed for this
		//      exact patch once this one comes out; asking `chosen` would read the replacement's
		//      checkbox to decide the fate of the tree next to it.
		//
		//      Short of the coins, this falls through to the ordinary chop below rather than
		//      stall on an instruction the player cannot follow — the coins row is what makes
		//      this non-blocking. See RunLoadout.addClearingFees.
		boolean paying = payToClear != null && payToClear.isPayingFor(projection);
		if (projection.isChoppable() && !harvestOnly && paying
			&& patch.getFarmer() != -1
			&& carried.getCount(ItemID.COINS) >= PayToClear.cost(patch.getImplementation())
			&& (patch.getImplementation() != PatchImplementation.FRUIT_TREE
				|| (chosen != null && seedAtHand(chosen, seeds))))
		{
			int cost = PayToClear.cost(patch.getImplementation());
			steps.add(GuideStep.atNpc(GuideAction.PAY_TO_CLEAR, patch, ItemID.COINS,
				patch.getFarmer(),
				"Pay " + com.dooglemaps.data.Farmers.getName(patch.getFarmer()) + " "
					+ String.format(java.util.Locale.ROOT, "%,d", cost)
					+ " coins to clear the " + projection.getProduce().getName().toLowerCase()
					+ "."));
			return steps;
		}

		// 0.7 A checked tree still standing. "Harvest" is the wrong word and the wrong expectation:
		//     you chop it, it does not empty the patch, and there is a stump behind it — which is
		//     what 0.6 above is for. Said separately so the player knows two clicks are coming
		//     rather than wondering why the patch is still occupied after the first.
		//
		//     No axe step. The leprechaun stores every farming tool except an axe, so there is
		//     nothing to withdraw here; carrying one is a bank-leg problem and RunLoadout says so.
		//
		//     Not on a harvest-only run, and that is a wiki fact rather than a modelling choice:
		//     the health check above is where a tree patch's value is, farmed trees do not
		//     regrow for re-chopping, and the chop only clears the patch for a replant the
		//     player has said they are not making. A checked tree on a harvest-only run is a
		//     finished one.
		//     One extra condition for a fruit tree, and it is the difference between clearing
		//     ground and destroying it. A farmed tree does not come back, so chopping one is
		//     simply how the patch is finished; a fruit tree regrows its fruit forever, so
		//     felling one with nothing to put in its place costs the player the tree. It is
		//     therefore only offered where the run actually has a sapling for that patch — the
		//     same test the picked-clean bush below makes, for the same reason.
		if (projection.isChoppable() && !harvestOnly
			&& (patch.getImplementation() != PatchImplementation.FRUIT_TREE
				|| (chosen != null && seedAtHand(chosen, seeds))))
		{
			steps.add(GuideStep.of(GuideAction.CHOP, patch,
				patch.getImplementation() == PatchImplementation.CRYSTAL_TREE
					// Not "and then dig the stump": there is not one, and the shards are the
					// point of the click rather than a by-product of clearing.
					? "Chop down the crystal tree for its shards - the patch is left empty."
					: "Chop down the " + projection.getProduce().getName().toLowerCase() + "."));
			return steps;
		}

		// 0.8 A spent anima plant. Its whole life is one GROWING run of nine stages — core's own
		//     comments walk "Attas plant" to "Withering Attas plant" to "Dead Attas plant[Clear]"
		//     and decode every one of them as GROWING — so the dead state reached no branch here
		//     at all: not the DEAD one below (the crop state never says so), not the harvest one
		//     (there is nothing to pick), and the growing-leave-it-alone fallback swallowed it.
		//
		//     The patch was correctly routed to, and the guide had nothing to say about the one
		//     thing it wanted. That matters more than a single patch usually would: an anima's
		//     value is the buff it puts on every OTHER patch you own — attas +5% harvest saves,
		//     iasor −80% disease — so a dead one is a quiet loss on the whole account until it
		//     is noticed. The last stage is the dead one; a withering plant is still working, so
		//     it is deliberately left alone.
		if (patch.getImplementation() == PatchImplementation.ANIMA
			&& !projection.isEmpty()
			&& projection.getStage() >= projection.getStages() - 1)
		{
			addToolStep(steps, patch, FarmingTool.SPADE, carried, leprechaun);
			steps.add(GuideStep.of(GuideAction.CLEAR, patch,
				"Dig up the spent " + projection.getProduce().getName().toLowerCase()
					+ " plant - its bonus has stopped applying to your other patches."));
			return steps;
		}

		// 1. Take what is on it — if there is anything on it.
		//
		//    hasProduceToPick rather than the raw state. "Harvestable" for a regrowing crop means
		//    grown, not laden: a picked-clean fruit tree still reports HARVESTABLE, so this branch
		//    fired forever and said "harvest the papaya" at a tree with no papayas. It also
		//    returns, so the patch produced that one impossible step and nothing else — which is
		//    what left harvest-only stops unable to finish. See PatchProjection.hasProduceToPick.
		// Weeds are never a harvest, wherever the projection has promoted them to. Ordinarily
		// the rake branch above returns first, so the promotion hazard its comment describes
		// stays invisible - but the coral nursery skips that branch (its "weeds" are the empty
		// state), and its fully-weedy decode arrived here as "harvest the weeds". The guard is
		// global because the sentence is absurd everywhere, not only underwater.
		if (projection.hasProduceToPick() && projection.getProduce() != Produce.WEEDS)
		{
			// Gated on what he will take, not merely on the pack being full: a tree patch's
			// Produce item is its LOGS, and he refuses every kind of log (wiki), so a full pack
			// at a magic tree sent the player to him with nothing he would accept. A full pack
			// is still a full pack — but the honest answer there is silence, not a trip that
			// ends in "the leprechaun refuses". See Produce.isLeprechaunNotable.
			// And not while a bin at this stop is waiting for exactly this crop. Noting it is
			// what makes it useless to the bin - a bin refuses notes - so the two instructions
			// are in direct conflict and the leprechaun's is the one that loses. Emptying
			// fifteen into the bin frees the same slots and is the errand the player came for.
			// Without this the harvest-fed bins were unfeedable in practice: the pack fills mid
			// harvest, the note fires the moment it does, and the bin then finds nothing
			// un-noted left to take.
			//
			// And only when you are actually carrying some. This named the crop growing in
			// the patch in front of you rather than anything in your pack, so a full pack at
			// a ripe irit said "note the irit" with the irit still in the ground - an
			// instruction with nothing to perform it on. Reported from play mid compost-bin
			// emptying, where the pack was full of buckets and the herb patch beside the bin
			// supplied the wording.
			//
			// Un-noted only: a stack that is already noted is not something he can note
			// again, and counting it would bring the same empty instruction back one step
			// later. Silence when you hold none is the right answer here - the pack being
			// full is real, but this branch's job is to name what to hand over, and the
			// leaving errands (GuideTracker.appendNoteBeforeLeaving) name the biggest stack
			// you are genuinely carrying once the patch work is done.
			if (carried.getFreeSlots() <= FULL_INVENTORY_SLACK
				&& projection.getProduce().isLeprechaunNotable()
				&& carried.getInventoryCount(projection.getProduce().getItemID()) > 0
				&& !binWantsThisCrop)
			{
				// Short, and the same words as the leaving errand's version of this. Neither the
				// reason ("your inventory is full") nor the place ("with the tool leprechaun")
				// tells the player anything they cannot see: the pack is in front of them and the
				// step highlights him. Shortened by request, with the leaving errand.
				steps.add(GuideStep.atLeprechaun(GuideAction.NOTE_AT_LEPRECHAUN, patch,
					projection.getProduce().getItemID(), null,
					"Note your " + plural(projection.getProduce().getName().toLowerCase()) + "."));
			}
			// The harvest itself takes a spade for these families — wiki-checked ("a spade
			// to harvest herbs"), and the game refuses the pick outright without one. The
			// guide said "harvest the herb" regardless, at a stop where the player's spades
			// sat in the bank and the leprechaun's store. Reported from play. After the
			// note, before the harvest: noting frees the slots, and the fetch is the same
			// trip to him.
			if (SPADE_HARVESTED.contains(patch.getImplementation()))
			{
				addToolStep(steps, patch, FarmingTool.SPADE, carried, leprechaun);
			}
			// A "fill the box before this harvest" nudge lived here, gated on the expected
			// yield outgrowing the pack. Removed by request as overthought: with the box on a
			// plain empty-before-planting, fill-before-leaving rhythm (see the sow branch
			// below and GuideTracker.appendFillSeedBoxBeforeLeaving), a pre-harvest fill was
			// the third box step at a stop and fought the empty that followed it.
			steps.add(GuideStep.of(GuideAction.HARVEST, patch, harvestText(projection)));
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
			// Except the redwood, which a spade cannot touch - the only tree with no
			// self-removal. Clearing it is paying Alexandra 2,000 coins, wiki-checked, and it is
			// PAY_TO_CLEAR rather than CLEAR: it is the same click as the 0.65 branch above pays
			// for, just unconditional rather than opt-in - there is no player alternative to
			// weigh it against, so there is no toggle and no coins-short fallback either. A
			// living redwood gets no equivalent step in v1: PatchRules gives it fifteen
			// indistinguishable HARVESTABLE values and no stump, so nothing in the decode can
			// name "checked and finished" the way a tree's grown/choppable/stump triple does.
			if (patch.getImplementation() == PatchImplementation.REDWOOD)
			{
				steps.add(GuideStep.atNpc(GuideAction.PAY_TO_CLEAR, patch, ItemID.COINS,
					patch.getFarmer(),
					"Pay Alexandra 2,000 coins to remove the dead redwood - a spade cannot."));
				return steps;
			}
			addToolStep(steps, patch, FarmingTool.SPADE, carried, leprechaun);
			steps.add(GuideStep.of(GuideAction.CLEAR, patch,
				"Clear the dead " + projection.getProduce().getName().toLowerCase() + "."));
			return steps;
		}

		// 2.5 Diseased is the state with a clock on it: the crop has stopped growing and the
		//     next cycle can kill it, so curing is the most urgent click on the whole run —
		//     and it used to be the one the guide had no word for. The patch was actionable,
		//     the run routed to it, and this fell through to "growing, leave it alone": the
		//     player was walked to their dying ranarr and told nothing.
		//
		//     Never for DEAD - a cure fails on a dead crop, which is why the branch above
		//     returns first - and never for a mature crop, which cannot be diseased at all.
		if (projection.getCropState() == CropState.DISEASED)
		{
			addCureSteps(steps, projection, carried, leprechaun);
			return steps;
		}

		// 2.7 A bush or cactus picked clean, on a run that replants it. A regrowing crop never
		//     empties — picked clean it sits HARVESTABLE with a stock of zero — so it fell
		//     through to the growing-leave-it-alone branch below and the guide never said the
		//     one thing a replant needs first: dig it out. Reported from play, at bushes.
		//
		//     Only the families a spade takes straight out. A fruit tree in the same state is
		//     chop-then-stump, which the guide does not model, and digging up a healthy fruit
		//     tree is exactly what the old behaviour existed to avoid — those still read as
		//     finished. Gated on the replant actually being possible this trip (a seed chosen
		//     and at hand), because clearing a producing bush with nothing to put in its place
		//     is strictly worse than leaving it.
		if (SpadeClearedCrops.isSpadeCleared(patch.getImplementation())
			&& projection.getCropState() == CropState.HARVESTABLE
			&& chosen != null && seedAtHand(chosen, seeds))
		{
			addToolStep(steps, patch, FarmingTool.SPADE, carried, leprechaun);
			steps.add(GuideStep.of(GuideAction.CLEAR, patch,
				"Dig up the picked-clean " + projection.getProduce().getName().toLowerCase()
					+ " so a new one can be planted."));
			return steps;
		}

		// A crop still growing, or diseased and curable, wants leaving alone - guided mode is
		// about the patches a run actually services.
		if (!projection.isEmpty())
		{
			addProtectionStep(steps, projection, carried, protecting, itemNames);

			// One exception: a seed that has just gone in, on a patch that was never treated.
			// Compost works just as well applied after planting — the wiki's own herb-run guide
			// sows first and composts second — so someone following that order used to get
			// silence here, and an untreated patch, precisely because they did it the other
			// way round. Limited to the first growth stage so it cannot start nagging about a
			// crop planted days ago.
			// Asked of the crop actually standing there rather than of `chosen`, which is null
			// once the ground is occupied. Same seed the payment above is being made for, so the
			// two answers cannot disagree about what is in the patch.
			CompostTier wantedAfterPlanting = com.dooglemaps.timer.CropYieldModel
				.compostWastedOnProtected(Seed.forProduce(projection.getProduce()), paidToProtect)
				? CompostTier.NONE
				: usableCompost(compostChoice.get(group), carried, leprechaun);
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

		// The allocation counts everywhere seeds are kept — the bank included — because the
		// loadout it also feeds is the list of what to go and get. Standing at the patch, the
		// pack and the seed box are all there is, and "plant 3 snape grass seeds" about seeds
		// sitting in a bank is an instruction that cannot be followed; it sat at the top of
		// the list unperformable. Reported from play, at Prifddinas. Same treatment as no
		// seed at all — no steps, including the compost, which would be preparation for a
		// planting this trip cannot make. The tracker words the skip, the stop can complete,
		// and a deferred supply trip or the next run picks the patch up; the moment the seeds
		// are withdrawn it starts asking for its steps again by itself.
		if (!seedAtHand(chosen, seeds))
		{
			// Except when the seed IS at hand and simply is not a sapling yet. A tree, fruit
			// tree or calquat seed goes into a plant pot of soil, is watered, and only then can
			// be planted - and none of that can be done at the patch, because a plant pot is a
			// bank errand. So the silence above is the wrong answer here: the player is standing
			// in front of the patch holding the thing they came for and being told nothing.
			// Reported from play, at a calquat: "got sent to a calquat patch without turning
			// into a sapling first".
			//
			// The bank leg already carries "needs potting into a sapling first" as a reason on
			// its row (see RunLoadout), which is where it is cheapest to act on. This is what it
			// says when that was missed, and it names the patch so the run does not merely go
			// quiet at it.
			if (chosen.isSapling() && unpottedInPack(chosen, seeds) > 0)
			{
				steps.add(GuideStep.withItem(GuideAction.POT_SEED, patch, chosen.getItemID(),
					"The " + chosen.getName().toLowerCase() + " seed has to go in a plant pot "
						+ "of soil and be watered before it can be planted - that is a bank "
						+ "errand, not one you can do here."));
			}
			return steps;
		}

		// 3. Compost, then the seed. Preferred in that order because it is one fewer thing to
		//    remember once a crop is in the ground — but not required: see the just-planted
		//    case above, which catches anyone doing it the wiki's way round.
		//
		//    Unless the farmer is being paid for this one. A payment is immunity outright, so on
		//    a seed with no lives mechanic the bucket has nothing left to buy — the player was
		//    being asked to spend an ultracompost and a click preparing ground for a sapling that
		//    was about to be made disease-proof anyway. Asked of the seed, not the patch type:
		//    a protected ranarr still wants its compost, because there the bucket is buying herbs
		//    rather than survival. See CropYieldModel.compostWastedOnProtected.
		CompostTier wanted = com.dooglemaps.timer.CropYieldModel
			.compostWastedOnProtected(chosen, paidToProtect)
			? CompostTier.NONE
			: usableCompost(compostChoice.get(group), carried, leprechaun);
		if (wanted != CompostTier.NONE && applied != wanted)
		{
			addCompostSteps(steps, patch, wanted, carried, patchesToTreat);
			return steps;
		}

		// 4. Sow. The seed box gets no step of its own — not here and not on the way out. Two
		//    box steps were tried, empty-before-planting and fill-before-leaving, and both were
		//    removed by request: the box is a container you touch twice a stop, and being told
		//    to is noise around the clicks that matter. What is left is the left-click swap,
		//    which is standing and reads the box's contents rather than the current step; see
		//    {@code GuideMenuSwap}. The step also lit the patch it was hung on, which is how a
		//    stage-one snape grass allotment came to be outlined by a seed box instruction.
		int perPatch = chosen.getSeedsPerPatch();

		// The tool the sowing itself takes, and which one that is depends on what goes in the
		// ground.
		//
		// A sapling takes a SPADE. "Saplings go in by hand" stood here as the reason a tree
		// patch was deliberately silent, and it is wrong — wiki, on both the tree and fruit tree
		// patch articles in the same words: "players must have a spade in their inventory when
		// planting and removing the stump of a tree". Only the stump half was ever modelled, so
		// a run whose spade was in the leprechaun's store reached a tree patch, said "plant the
		// yew sapling", and the game refused. Reported from play, with the player working out
		// the cause: "maybe because it's a sapling? spade is needed to plant those".
		//
		// Barbarian Farming does not get you out of this one — it removes the seed DIBBER
		// requirement, which is why the unlock is only consulted on that branch.
		if (chosen.isSapling())
		{
			addToolStep(steps, patch, FarmingTool.SPADE, carried, leprechaun);
		}
		// A coral frag is PLACED on the nursery, not dibbed in - "one frag can be placed on
		// each nursery", and the nursery has none of the ordinary patch tooling (no rake, no
		// spade to harvest). Asking for a dibber would send someone to fetch a tool the click
		// never uses.
		else if (!barbarianFarming.isUnlocked()
			&& patch.getImplementation() != PatchImplementation.CORAL)
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
		CarriedItems carried, boolean protecting, com.dooglemaps.data.ItemNames itemNames)
	{
		FarmPatch patch = projection.getPatch();
		if (!protecting || projection.getProduce() == null
			|| !DiseaseRisk.isProtectable(patch))
		{
			return;
		}

		// Including noted, which is how payments travel: the farmer takes them noted, the
		// loadout counts them noted (see RunLoadout), and counting only loose ones here
		// suppressed the step for the player who had brought exactly what was asked.
		ProtectionPayment payment = ProtectionPayment.forProduce(projection.getProduce());
		if (payment == null
			|| carried.getCountIncludingNoted(payment.getItemID()) < payment.getQuantity())
		{
			return;
		}

		// The item, not the crop. This read payment.getProduce(), which is the crop being
		// protected — so every sentence on this branch paid for a thing with itself. Reported
		// from play at the coral: "Pay the farmer 5 elkhorn to protect the elkhorn", when what
		// Chet wants is five giant seaweed. Wrong everywhere, but only visible here: the
		// planting sequence words its own payment step, and this branch fires only when you
		// come back to a patch that is already in the ground and still unpaid.
		//
		// ItemNames rather than a hand-written label, which is the case its own class comment
		// argues: half these payments are baskets and sacks, where the constant reads "basket
		// tomato 5" and the game says "Basket of tomatoes". Every ProtectionPayment item id is
		// read into it at login, so the fallback is for tests rather than for play.
		String paying = itemNames == null
			? null : itemNames.get(payment.getItemID());
		steps.add(GuideStep.atNpc(GuideAction.PAY_FARMER, patch, payment.getItemID(),
			patch.getFarmer(),
			"Pay the farmer " + payment.getQuantity() + " "
				+ (paying == null ? "of the payment item" : paying.toLowerCase())
				+ " to protect the " + projection.getProduce().getName().toLowerCase() + "."));
	}

	/**
	 * The families whose diseased crop is pruned back to health with secateurs.
	 *
	 * <p>Wiki-checked: trees, fruit trees, spirit trees, bushes and calquats are pruned
	 * (either kind of secateurs; it can take a few attempts). Coral too, and the provenance
	 * there is core's own menu comments — "Diseased elkhorn coral[Prune,Inspect,Guide]" —
	 * so telling its grower to use a plant cure named a click the menu does not offer.
	 * Everything else takes a plant cure — herbs, flowers, allotments, hops, hardwoods,
	 * celastrus, belladonna, cactus, mushroom, seaweed ("Diseased seaweed[Cure,...]") — and
	 * the Lunar Cure Plant spell stands in for either.
	 */
	private static final java.util.Set<PatchImplementation> PRUNED_HEALTHY = java.util.EnumSet.of(
		PatchImplementation.TREE,
		PatchImplementation.FRUIT_TREE,
		PatchImplementation.SPIRIT_TREE,
		PatchImplementation.BUSH,
		PatchImplementation.CALQUAT,
		PatchImplementation.CORAL);

	/**
	 * The cure, with the fetch for its tool in front of it when the leprechaun holds one.
	 *
	 * <p>The step is emitted even with nothing in hand: the instruction is still true, the
	 * patch is still the thing to deal with, and the player has answers the plugin cannot see
	 * — the Cure Plant spell, or the farming shop beside most patch areas.
	 */
	private static void addCureSteps(List<GuideStep> steps, PatchProjection projection,
		CarriedItems carried, LeprechaunStore leprechaun)
	{
		FarmPatch patch = projection.getPatch();
		String crop = projection.getProduce().getName().toLowerCase();

		if (PRUNED_HEALTHY.contains(patch.getImplementation()))
		{
			if (!carried.hasAny(ItemID.SECATEURS, ItemID.FAIRY_ENCHANTED_SECATEURS)
				&& (leprechaun.has(FarmingTool.SECATEURS)
					|| leprechaun.has(FarmingTool.MAGIC_SECATEURS)))
			{
				steps.add(GuideStep.atLeprechaun(GuideAction.WITHDRAW_TOOL, patch,
					ItemID.SECATEURS, null,
					"Get your secateurs from the tool leprechaun - you are not carrying any."));
			}
			steps.add(GuideStep.of(GuideAction.CURE, patch,
				"Prune the diseased " + crop + " with secateurs before it dies."));
			return;
		}

		if (!carried.has(ItemID.PLANT_CURE) && leprechaun.has(FarmingTool.PLANT_CURE))
		{
			steps.add(GuideStep.atLeprechaun(GuideAction.WITHDRAW_TOOL, patch,
				ItemID.PLANT_CURE, null,
				"Get a plant cure from the tool leprechaun."));
		}
		steps.add(GuideStep.of(GuideAction.CURE, patch,
			"Use a plant cure on the diseased " + crop + " before it dies."));
	}

	/**
	 * What picking this patch is called, which is not always "harvest".
	 *
	 * <p>Celastrus bark comes off with an axe, so saying "harvest" undersells what the click
	 * needs; belladonna burns bare hands, and the one moment to say so is the instruction to
	 * touch it. Both wiki-checked.
	 */
	private static String harvestText(PatchProjection projection)
	{
		String crop = projection.getProduce().getName().toLowerCase();
		switch (projection.getPatch().getImplementation())
		{
			case CELASTRUS:
				return "Chop the bark from the celastrus tree - it takes an axe.";
			case BELLADONNA:
				return "Harvest the belladonna wearing gloves - bare hands take damage.";
			case CORAL:
				// The menu's own word - "Elkhorn coral[Collect,...]" - and no tool at all:
				// the wiki is explicit that no spade is needed here.
				return "Collect the " + crop + " from the nursery.";
			default:
				return "Harvest the " + crop + ".";
		}
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
	 * Whether this seed can go in the ground on <b>this trip</b> — in the pack or the seed
	 * box, as opposed to merely owned somewhere.
	 *
	 * <p>Plantable counts, not raw counts, so a pocketed acorn does not pass for the sapling
	 * a tree patch actually takes. Also the tracker's test for wording the skip, so the two
	 * cannot drift: the guide goes silent about a patch exactly when the panel explains why.
	 */
	/**
	 * How many raw, unpotted seeds of this crop are on the player.
	 *
	 * <p>{@code getCount} counts both forms and {@code getPlantable} counts only the sapling, so
	 * the difference is what is still in seed form — which is the state the patch cannot use and
	 * the bank can fix.
	 */
	private static int unpottedInPack(Seed seed, SeedInventoryStore seeds)
	{
		int both = seeds.getCount(seed, SeedSource.INVENTORY)
			+ seeds.getCount(seed, SeedSource.SEED_BOX);
		return both - (seeds.getPlantable(seed, SeedSource.INVENTORY)
			+ seeds.getPlantable(seed, SeedSource.SEED_BOX));
	}

	static boolean seedAtHand(Seed seed, SeedInventoryStore seeds)
	{
		return seeds.getPlantable(seed, SeedSource.INVENTORY)
			+ seeds.getPlantable(seed, SeedSource.SEED_BOX) >= seed.getSeedsPerPatch();
	}

	/**
	 * The tier this patch will actually be treated with, after any fallback.
	 *
	 * <p>Unconditional here, and that is deliberate: the <b>setting</b> gates whether the
	 * player is told about a downgrade and whether the run plans around one, and it is asked
	 * where the notifying happens ({@code GuideTracker}). What the step says has to match what
	 * the player can do — an instruction to apply ultracompost they do not have is not made
	 * better by a switch being off.
	 *
	 * <p>Reach means carried or in the leprechaun's store, because at a patch those are the
	 * same thing: he is standing there and the withdraw step is one click. A tier that exists
	 * only in a bank is not reachable from a patch and does not count.
	 */
	static CompostTier usableCompost(CompostTier wanted, CarriedItems carried,
		LeprechaunStore leprechaun)
	{
		// Null tolerated as well as NONE: a group with no stored choice reads as null from the
		// selection store, and this is called for every patch of every group.
		if (wanted == null || wanted == CompostTier.NONE)
		{
			return CompostTier.NONE;
		}

		CompostTier best = wanted.bestAvailableAtOrBelow(tier ->
			carried.getCount(tier.getItemID()) > 0
				|| carried.hasAny(ItemID.BOTTOMLESS_COMPOST_BUCKET_FILLED)
				|| leprechaun.hasCompost(tier));

		// Nothing at all in reach is NOT a downgrade to nothing. The chosen tier's own step
		// still stands — "withdraw ultracompost from the leprechaun" is exactly the right
		// instruction when his store has not been read yet, and turning a real instruction
		// into silence because the answer is currently unknown is the worse failure of the
		// two. Only a tier actually seen to be there displaces the one that was asked for.
		return best == CompostTier.NONE ? wanted : best;
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
		// A frag is placed, not planted - the nursery has no dibber, no hole, no "seed".
		// Worded from the wiki's own sentence: "one frag can be placed on each nursery".
		Produce produce = seed.getProduce();
		if (produce != null && produce.getPatchImplementation() == PatchImplementation.CORAL)
		{
			return "Place the " + seed.getName().toLowerCase() + " frag on the nursery.";
		}

		String noun = seed.isSapling() ? "sapling" : "seed";
		return perPatch == 1
			? "Plant the " + seed.getName().toLowerCase() + " " + noun + "."
			: "Plant " + perPatch + " " + seed.getName().toLowerCase() + " " + noun + "s.";
	}

}
