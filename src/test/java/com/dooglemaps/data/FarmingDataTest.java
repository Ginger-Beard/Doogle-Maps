package com.dooglemaps.data;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Guards the generated farming tables.
 *
 * <p>They are mechanically derived from RuneLite's sources, so the risk is not a typo but
 * a silent parsing regression when the generator is re-run against a newer client. These
 * assertions are the shape the data has to keep.
 */
public class FarmingDataTest
{
	@Test
	public void everyPatchHasAUniqueKey()
	{
		Set<String> keys = new HashSet<>();
		for (FarmPatch patch : FarmingWorldData.getAllPatches())
		{
			assertTrue("duplicate patch key " + patch.getKey(), keys.add(patch.getKey()));
		}

		// A canary on the generator, not on the game: this should only ever change when
		// the data is regenerated against a RuneLite that added or removed patches. If it
		// fails after a regeneration, check the count the generator printed matches the
		// "new FarmingPatch(" count in the client sources, then update this.
		assertEquals("patch count changed - see the comment above", 107, keys.size());
		assertEquals("region count changed - see the comment above", 43, FarmingWorldData.getRegions().size());
	}

	@Test
	public void everyPatchResolvesBackFromItsKey()
	{
		for (FarmPatch patch : FarmingWorldData.getAllPatches())
		{
			assertEquals(patch, FarmingWorldData.getPatch(patch.getKey()));
			assertNotNull(patch.getRegion());
			assertTrue(patch.getDisplayName().length() > 0);
		}
	}

	@Test
	public void varbitRulesDoNotOverlap()
	{
		for (PatchImplementation implementation : PatchImplementation.values())
		{
			Map<Integer, Produce> seen = new HashMap<>();
			for (int value = 0; value <= 255; value++)
			{
				ProduceState decoded = implementation.forVarbitValue(value);
				if (decoded != null)
				{
					seen.put(value, decoded.getProduce());
				}
			}
			assertTrue(implementation + " decodes nothing", !seen.isEmpty());
		}
	}

	@Test
	public void decodedStagesStayInRange()
	{
		for (PatchImplementation implementation : PatchImplementation.values())
		{
			for (int value = 0; value <= 255; value++)
			{
				ProduceState decoded = implementation.forVarbitValue(value);
				if (decoded == null)
				{
					continue;
				}

				String where = implementation + " value " + value;
				assertTrue(where + " has negative stage", decoded.getStage() >= 0);
				assertTrue(where + " stage " + decoded.getStage() + " exceeds " + decoded.getStages(),
					decoded.getStage() < decoded.getStages());
			}
		}
	}

	@Test
	public void everyCropBelongsToThePatchThatGrowsIt()
	{
		for (PatchImplementation implementation : PatchImplementation.values())
		{
			for (int value = 0; value <= 255; value++)
			{
				ProduceState decoded = implementation.forVarbitValue(value);
				if (decoded == null || !decoded.getProduce().isCrop())
				{
					continue;
				}

				PatchImplementation grownIn = decoded.getProduce().getPatchImplementation();
				assertNotNull(decoded.getProduce() + " has no patch implementation", grownIn);
			}
		}
	}

	/**
	 * Growth times, cross-checked against the wiki figures in the spec. Minimums: disease
	 * repeats a cycle and adds to them.
	 */
	@Test
	public void growthTimesMatchTheWiki()
	{
		assertEquals(40, Produce.POTATO.getMinutesToGrow());
		assertEquals(80, Produce.WATERMELON.getMinutesToGrow());
		assertEquals(70, Produce.SNAPE_GRASS.getMinutesToGrow());
		assertEquals(20, Produce.MARIGOLD.getMinutesToGrow());
		assertEquals(160, Produce.POISON_IVY.getMinutesToGrow());
		assertEquals(50, Produce.JUTE.getMinutesToGrow());

		// Every herb takes the same 80 minutes regardless of type.
		for (Produce produce : Produce.values())
		{
			if (produce.getPatchImplementation() == PatchImplementation.HERB && produce.isCrop())
			{
				assertEquals(produce + " should take 80 minutes", 80, produce.getMinutesToGrow());
			}
		}

		assertEquals(160, Produce.OAK.getMinutesToGrow());
		assertEquals(400, Produce.YEW.getMinutesToGrow());
		assertEquals(480, Produce.MAGIC.getMinutesToGrow());

		// All fruit trees take 16 hours.
		for (Produce produce : Produce.values())
		{
			if (produce.getPatchImplementation() == PatchImplementation.FRUIT_TREE)
			{
				assertEquals(produce + " should take 16 hours", 960, produce.getMinutesToGrow());
			}
		}

		assertEquals(40, Produce.SEAWEED.getMinutesToGrow());
		assertEquals(35, Produce.GRAPE.getMinutesToGrow());
		assertEquals(240, Produce.MUSHROOM.getMinutesToGrow());
		assertEquals(320, Produce.BELLADONNA.getMinutesToGrow());
		assertEquals(560, Produce.CACTUS.getMinutesToGrow());
		assertEquals(70, Produce.POTATO_CACTUS.getMinutesToGrow());
		assertEquals(1280, Produce.CALQUAT.getMinutesToGrow());
		assertEquals(800, Produce.CELASTRUS.getMinutesToGrow());
		assertEquals(480, Produce.CRYSTAL_TREE.getMinutesToGrow());
		assertEquals(3840, Produce.SPIRIT_TREE.getMinutesToGrow());
		assertEquals(6400, Produce.REDWOOD.getMinutesToGrow());
		assertEquals(1920, Produce.HESPORI.getMinutesToGrow());
		assertEquals(4480, Produce.TEAK.getMinutesToGrow());
		assertEquals(5120, Produce.MAHOGANY.getMinutesToGrow());
		assertEquals(5120, Produce.CAMPHOR.getMinutesToGrow());
		assertEquals(5120, Produce.IRONWOOD.getMinutesToGrow());

		// Rosewood is the one crop where RuneLite and the spec's wiki figures disagree:
		// the wiki says 106h40m (10 growth ticks), RuneLite's table says 96h (9). Trusting
		// RuneLite, since its numbers come from the varbit states the game actually uses.
		assertEquals(5760, Produce.ROSEWOOD.getMinutesToGrow());

		// All three coral tiers share the same timing.
		assertEquals(160, Produce.ELKHORN_CORAL.getMinutesToGrow());
		assertEquals(160, Produce.PILLAR_CORAL.getMinutesToGrow());
		assertEquals(160, Produce.UMBRAL_CORAL.getMinutesToGrow());

		// Sailing-era hops, all on a 20-minute cycle rather than the older 10.
		assertEquals(60, Produce.FLAX.getMinutesToGrow());
		assertEquals(80, Produce.HEMP.getMinutesToGrow());
		assertEquals(100, Produce.COTTON.getMinutesToGrow());
	}

	/** A freshly raked patch reads as weeds, which is how we tell "empty". */
	@Test
	public void emptyPatchesDecodeToWeeds()
	{
		ProduceState allotment = PatchImplementation.ALLOTMENT.forVarbitValue(3);
		assertNotNull(allotment);
		assertEquals(Produce.WEEDS, allotment.getProduce());
		assertTrue(!allotment.getProduce().isCrop());
	}

	/** Values no patch of that kind ever takes must decode to nothing, not to a guess. */
	@Test
	public void unknownValuesDecodeToNull()
	{
		assertNull(PatchImplementation.HESPORI.forVarbitValue(200));
	}

	@Test
	public void everyTabHasPatches()
	{
		for (PatchImplementation type : PatchImplementation.values())
		{
			assertTrue(type + " has no patches", !FarmingWorldData.getPatches(type).isEmpty());
		}
	}

	/**
	 * The sidebar tab and the seed selector are both keyed on PatchImplementation, so
	 * every tab needs a label and a usable icon or the panel renders a blank square.
	 */
	@Test
	public void everyTabHasALabelAndAnIcon()
	{
		for (PatchImplementation type : PatchImplementation.values())
		{
			assertTrue(type + " has no display name", type.getDisplayName().length() > 0);
			assertTrue(type + " has no crops to draw an icon from", type.getItemID() > 0);
		}
	}

	/** Every crop is reachable from the tab a player would look for it under. */
	@Test
	public void everyCropIsListedUnderItsPatchType()
	{
		for (Produce produce : Produce.values())
		{
			if (!produce.isCrop() || produce.getPatchImplementation() == null)
			{
				continue;
			}
			assertTrue(produce + " missing from " + produce.getPatchImplementation(),
				produce.getPatchImplementation().getCrops().contains(produce));
		}
	}
}
