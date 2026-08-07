package com.dooglemaps.state;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PatchRequirements;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.RunOption;
import com.dooglemaps.data.Seed;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.timer.CropYieldModel;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Every crop that regrows can be run for its harvest, and nothing else can.
 *
 * <p>The harvest-only set was two types written by hand, and it was wrong: the cactus patch
 * regrows exactly like a bush and had no such line. Nothing could have caught that, because the
 * hand-written pair <i>was</i> the statement of what belonged in it. It is derived from the
 * produce data now, and this is what holds the derivation to the data.
 */
public class RunOptionCoverageTest
{
	private static PlantingGroups groups() throws Exception
	{
		DoogleMapsConfig config = Mockito.mock(DoogleMapsConfig.class);
		net.runelite.client.config.ConfigManager configManager =
			Mockito.mock(net.runelite.client.config.ConfigManager.class);

		Constructor<PlantingGroups> ctor =
			PlantingGroups.class.getDeclaredConstructor(DoogleMapsConfig.class,
				ProtectedPatches.class, AvailabilityProfile.class,
				com.dooglemaps.state.ContractState.class);
		ctor.setAccessible(true);

		Constructor<ProtectedPatches> pc =
			ProtectedPatches.class.getDeclaredConstructor(net.runelite.client.config.ConfigManager.class);
		pc.setAccessible(true);

		Constructor<com.dooglemaps.state.ContractState> cc =
			com.dooglemaps.state.ContractState.class.getDeclaredConstructor(
				net.runelite.client.config.ConfigManager.class);
		cc.setAccessible(true);

		return ctor.newInstance(config, pc.newInstance(configManager),
			Mockito.mock(AvailabilityProfile.class), cc.newInstance(configManager));
	}

	/** Which types the produce data says come back after picking. */
	private static Set<PatchImplementation> regrowingTypes()
	{
		Set<PatchImplementation> types = EnumSet.noneOf(PatchImplementation.class);
		for (Produce produce : Produce.values())
		{
			if (produce.getRegrowTickrate() > 0 && produce.getPatchImplementation() != null)
			{
				types.add(produce.getPatchImplementation());
			}
		}
		return types;
	}

	@Test
	public void everyRegrowingTypeOffersAHarvestOnlyRun() throws Exception
	{
		List<RunOption> options = groups().runOptions();

		List<PatchImplementation> missing = new ArrayList<>();
		for (PatchImplementation type : regrowingTypes())
		{
			if (!options.contains(RunOption.harvestOnly(
				com.dooglemaps.data.PlantingGroup.of(type))))
			{
				missing.add(type);
			}
		}

		assertTrue("these crops regrow but cannot be run for their harvest: " + missing,
			missing.isEmpty());
	}

	/**
	 * The paired types are last, so the two-column grid does not have to pad around them.
	 *
	 * <p>Asserted on the option order rather than on the rendered grid because this is where the
	 * decision is made — the layout pads whatever it is handed, so a regression here would show
	 * up only as an extra blank cell, which is easy to miss and easy to explain away.
	 */
	@Test
	public void typesWithAHarvestOnlyVariantComeLast() throws Exception
	{
		List<RunOption> options = groups().runOptions();
		Set<PatchImplementation> regrows = regrowingTypes();

		int firstPaired = -1;
		for (int i = 0; i < options.size(); i++)
		{
			boolean paired = regrows.contains(options.get(i).getType());
			if (paired && firstPaired < 0)
			{
				firstPaired = i;
			}
			assertTrue("an ordinary run option after the paired ones started: "
				+ options.get(i).getLabel(), paired || firstPaired < 0);
		}

		assertTrue("no paired types at all", firstPaired > 0);
	}

	/** And nothing that does not regrow offers one — harvesting a herb once is just a run. */
	@Test
	public void onlyRegrowingTypesOfferIt() throws Exception
	{
		Set<PatchImplementation> regrows = regrowingTypes();
		for (RunOption option : groups().runOptions())
		{
			if (option.isHarvestOnly())
			{
				assertTrue(option.getType() + " does not regrow, so harvest-only is meaningless",
					regrows.contains(option.getType()));
			}
		}
	}

	/**
	 * A harvest-only line is worthless without the experience data behind it.
	 *
	 * <p>Cactus was offered as a runnable type with no row in {@code crop-xp.tsv}, which prices
	 * the whole trip at zero — the exact complaint that prompted this. Offering the line and
	 * having nothing to say about it is worse than not offering it.
	 */
	@Test
	public void everyHarvestOnlyTypeHasExperienceData() throws Exception
	{
		List<String> blind = new ArrayList<>();
		for (RunOption option : groups().runOptions())
		{
			if (!option.isHarvestOnly())
			{
				continue;
			}
			for (Seed seed : Seed.forPatchType(option.getType()))
			{
				if (com.dooglemaps.data.CropXp.forSeed(seed) == null)
				{
					blind.add(seed.name());
				}
			}
		}

		assertTrue("these crops can be harvest-run but have no experience data, so the "
			+ "projection reads zero: " + blind, blind.isEmpty());
	}

	/**
	 * Anything a farmer can be paid to protect can be diseased, so compost helps it.
	 *
	 * <h2>The check that catches a hand-written list going stale</h2>
	 *
	 * {@code DiseaseRisk.CAN_BE_DISEASED} decides where the compost dropdown appears, and the first
	 * version of it was written out by hand from a wiki summary. It missed <b>calquat</b> and
	 * <b>redwood</b> — both of which the plugin's own {@code ProtectionPayment} table already knew
	 * about, because you pay the calquat gardener 8 poison ivy berries and the redwood one 6
	 * dragonfruit.
	 *
	 * <p>That is the tell: <b>nobody pays to prevent something that cannot happen.</b> A protection
	 * payment is proof of a disease risk, and the payment table is maintained from the wiki
	 * already — so it can check the disease list for free, in the direction that matters. The
	 * reverse does not hold and is not asserted: herbs and flowers can be diseased and no farmer
	 * will touch them.
	 */
	@Test
	public void everyPatchTypeWithAProtectionPaymentCanBeDiseased()
	{
		Set<PatchImplementation> paid = EnumSet.noneOf(PatchImplementation.class);
		for (com.dooglemaps.data.ProtectionPayment payment
			: com.dooglemaps.data.ProtectionPayment.values())
		{
			PatchImplementation type = payment.getProduce().getPatchImplementation();
			if (type != null)
			{
				paid.add(type);
			}
		}

		assertFalse("the payment table is empty, so this proves nothing", paid.isEmpty());

		for (PatchImplementation type : paid)
		{
			assertTrue(type + " has a gardener protection payment, so it can be diseased - and "
					+ "compost has to be offered for it",
				com.dooglemaps.timer.DiseaseRisk.canCatchDisease(type));
		}
	}

	/**
	 * The compost dropdown is offered wherever compost does something <b>in the game</b>.
	 *
	 * <h2>Which is not the same as "wherever it moves our number"</h2>
	 *
	 * This used to pin a set of seven, derived from: the yield, via the lives mechanic — herbs,
	 * allotments, hops, giant seaweed — or a <i>published</i> disease rate, which exists for herbs,
	 * fruit trees, two tree species and coral and nowhere else.
	 *
	 * <p>That second half was the wrong test and this test was pinning it in place. A flower patch
	 * can be diseased and compost cuts the chance exactly as it does anywhere — Jagex has simply
	 * never published the rate. Gating the control on our ability to <i>display</i> the effect
	 * meant a player could not ask for their flowers to be treated at all, so the run banked no
	 * buckets and the guide applied none. Reported from play.
	 *
	 * <p>So the rule is now "can this patch catch a disease, or does compost raise its yield", and
	 * the members below are asserted individually rather than as a frozen set — the interesting
	 * claims are what is in and what is out, not the count.
	 */
	@Test
	public void compostIsOfferedWhereverItDoesSomethingInGame()
	{
		// The lives mechanic: compost genuinely raises what these give.
		for (PatchImplementation type : EnumSet.of(PatchImplementation.HERB,
			PatchImplementation.ALLOTMENT, PatchImplementation.HOPS, PatchImplementation.SEAWEED))
		{
			assertTrue(type + " responds to compost by yield", CropYieldModel.compostMatters(type));
		}

		// Diseaseable, so compost protects them — whether or not the rate is published.
		for (PatchImplementation type : EnumSet.of(PatchImplementation.FLOWER,
			PatchImplementation.BUSH, PatchImplementation.TREE, PatchImplementation.FRUIT_TREE,
			PatchImplementation.HARDWOOD_TREE, PatchImplementation.CACTUS,
			PatchImplementation.MUSHROOM, PatchImplementation.BELLADONNA,
			PatchImplementation.CELASTRUS, PatchImplementation.CORAL))
		{
			assertTrue(type + " can be diseased, so treating it is a real choice",
				CropYieldModel.compostMatters(type));
		}

		// And still not everywhere. A compost bin takes buckets and weeds rather than seeds, and
		// offering to treat one would be nonsense.
		assertFalse("a compost bin cannot be composted",
			CropYieldModel.compostMatters(PatchImplementation.COMPOST));
	}

	/**
	 * The disease-only note appears exactly where the dropdown will not move the yield.
	 *
	 * <p>Asserted as a relationship rather than a list, because the list is now long and the thing
	 * worth guarding has never been its membership: a note with no dropdown under it is orphaned
	 * text, and a yield-raising type carrying the note would be telling the player the opposite of
	 * what the estimate is about to show them.
	 */
	@Test
	public void theDiseaseOnlyNoteAppearsExactlyWhereTheYieldWillNotMove()
	{
		for (PatchImplementation type : PatchImplementation.values())
		{
			if (!CropYieldModel.compostOnlyHelpsDisease(type))
			{
				continue;
			}

			assertTrue(type + " shows the note with no dropdown to sit under",
				CropYieldModel.compostMatters(type));

			for (com.dooglemaps.data.Seed seed : com.dooglemaps.data.Seed.forPatchType(type))
			{
				assertFalse(type + " says compost only helps disease, but " + seed
						+ " raises its yield too",
					CropYieldModel.respondsToCompost(seed));
			}
		}

		assertTrue("a flower is the case this was widened for",
			CropYieldModel.compostOnlyHelpsDisease(PatchImplementation.FLOWER));
		assertFalse("a herb's compost is bought for the yield, so the note would be wrong",
			CropYieldModel.compostOnlyHelpsDisease(PatchImplementation.HERB));
	}

	/**
	 * A type with a dropdown keeps its choice; one without cannot smuggle a stored tier through.
	 *
	 * <p>Trees are the case worth having: the choice has to reach the estimate, because that is
	 * where the disease discount it was bought for gets applied.
	 */
	@Test
	public void aStoredTierReachesTheEstimateOnlyWhereItIsOffered() throws Exception
	{
		Constructor<CompostSelectionStore> ctor =
			CompostSelectionStore.class.getDeclaredConstructor(
				net.runelite.client.config.ConfigManager.class, com.google.gson.Gson.class);
		ctor.setAccessible(true);
		CompostSelectionStore store = ctor.newInstance(
			Mockito.mock(net.runelite.client.config.ConfigManager.class), new com.google.gson.Gson());

		// Derived rather than named. This used to use a bush as the no-dropdown example, and a
		// bush has one now that the rule is "can it be diseased" — so the test failed for a
		// reason that had nothing to do with the invariant it exists to protect. The invariant is
		// about the *relationship* between the two, and it should not have to be revisited every
		// time the set moves.
		PatchImplementation withoutDropdown = null;
		for (PatchImplementation type : PatchImplementation.values())
		{
			if (!CropYieldModel.compostMatters(type))
			{
				withoutDropdown = type;
				break;
			}
		}
		assertNotNull("no type lacks a compost dropdown, so this can no longer be tested",
			withoutDropdown);

		store.set(PatchImplementation.TREE, com.dooglemaps.data.CompostTier.ULTRACOMPOST);
		store.set(withoutDropdown, com.dooglemaps.data.CompostTier.ULTRACOMPOST);

		assertEquals("a tree's compost buys disease protection and must reach the estimate",
			com.dooglemaps.data.CompostTier.ULTRACOMPOST, store.get(PatchImplementation.TREE));
		assertEquals(withoutDropdown + " has no dropdown, so a stored tier must not be assumed",
			com.dooglemaps.data.CompostTier.NONE, store.get(withoutDropdown));
	}

	/**
	 * Every input to the disease discount actually moves it.
	 *
	 * <p>Compost, paying a farmer, and the patch being disease-free for this account are three
	 * separate defences and the model takes all three. Asserted together because the failure that
	 * matters is one of them silently doing nothing.
	 */
	@Test
	public void compostProtectionAndImmunityAllRaiseSurvival()
	{
		FarmPatch ardougne = FarmingWorldData.getPatch("12083.4774");
		assertNotNull(ardougne);

		double bare = com.dooglemaps.timer.DiseaseRisk.survivalChance(
			ardougne, Produce.RANARR, com.dooglemaps.data.CompostTier.NONE, false, false);
		double composted = com.dooglemaps.timer.DiseaseRisk.survivalChance(
			ardougne, Produce.RANARR, com.dooglemaps.data.CompostTier.ULTRACOMPOST, false,
			false);
		double paid = com.dooglemaps.timer.DiseaseRisk.survivalChance(
			ardougne, Produce.RANARR, com.dooglemaps.data.CompostTier.NONE, true, false);
		double immune = com.dooglemaps.timer.DiseaseRisk.survivalChance(
			ardougne, Produce.RANARR, com.dooglemaps.data.CompostTier.NONE, false, true);

		assertTrue("an untreated herb patch is close to a coin flip: " + bare, bare < 0.7);
		assertTrue("compost should raise survival: " + bare + " -> " + composted, composted > bare);
		assertEquals("paying a farmer means it cannot die", 1, paid, 0.0001);
		assertEquals("a disease-free patch cannot die either", 1, immune, 0.0001);
	}

	/** The disease discount is real, which is the whole reason the dropdown stayed. */
	@Test
	public void compostActuallyRaisesSurvivalOnAFruitTree()
	{
		FarmPatch patch = FarmingWorldData.getPatch("11317.4771");
		assertNotNull("fixture fruit tree patch no longer exists", patch);

		double untreated = com.dooglemaps.timer.DiseaseRisk.survivalChance(
			patch, Produce.PAPAYA, com.dooglemaps.data.CompostTier.NONE, false);
		double ultra = com.dooglemaps.timer.DiseaseRisk.survivalChance(
			patch, Produce.PAPAYA, com.dooglemaps.data.CompostTier.ULTRACOMPOST, false);

		assertTrue("ultracompost should improve a fruit tree's odds: " + untreated + " -> " + ultra,
			ultra > untreated);
	}

	/**
	 * The Farming Guild's level gates name patches that exist.
	 *
	 * <p>Written by hand against generated patch keys, which is the arrangement that rots
	 * silently: a renamed key would simply stop gating anything and the tier would open to
	 * everyone.
	 */
	@Test
	public void everyGatedPatchKeyExists()
	{
		List<String> unknown = new ArrayList<>();
		for (String key : PatchRequirements.all().keySet())
		{
			if (FarmingWorldData.getPatch(key) == null)
			{
				unknown.add(key);
			}
		}
		assertTrue("these gated keys name no patch: " + unknown, unknown.isEmpty());
	}

	/** And every Farming Guild patch is gated, so none is silently reachable at level 1. */
	@Test
	public void everyFarmingGuildPatchIsGated()
	{
		List<String> ungated = new ArrayList<>();
		for (FarmPatch patch : FarmingWorldData.getAllPatches())
		{
			if (patch.getRegion().getName().contains("Farming Guild")
				&& PatchRequirements.levelFor(patch) == 0)
			{
				ungated.add(patch.getKey() + " (" + patch.getImplementation() + ")");
			}
		}
		assertTrue("these guild patches have no level requirement: " + ungated, ungated.isEmpty());
	}

	/** The tiers, checked at the boundaries that actually matter. */
	@Test
	public void theTiersOpenAtTheRightLevels()
	{
		FarmPatch cactus = FarmingWorldData.getPatch("4922.7904");
		FarmPatch herb = FarmingWorldData.getPatch("4922.4775");
		FarmPatch redwood = FarmingWorldData.getPatch("4922.7907");
		assertNotNull(cactus);
		assertNotNull(herb);
		assertNotNull(redwood);

		assertFalse("the guild door is 45", PatchRequirements.isReachable(cactus, 44));
		assertTrue(PatchRequirements.isReachable(cactus, 45));

		assertFalse("the western wing is 65", PatchRequirements.isReachable(herb, 64));
		assertTrue(PatchRequirements.isReachable(herb, 65));

		assertFalse("the northern wing is 85", PatchRequirements.isReachable(redwood, 84));
		assertTrue(PatchRequirements.isReachable(redwood, 85));

		// Unknown level shows everything rather than emptying the guild on a fresh profile.
		assertTrue(PatchRequirements.isReachable(redwood, 0));
	}

	/** Nothing outside the guild is gated; every other patch is a walk. */
	@Test
	public void nothingOutsideTheGuildIsGated()
	{
		for (FarmPatch patch : FarmingWorldData.getAllPatches())
		{
			if (!patch.getRegion().getName().contains("Farming Guild"))
			{
				assertEquals(patch.getKey() + " should not need a level to reach",
					0, PatchRequirements.levelFor(patch));
			}
		}
	}
}
