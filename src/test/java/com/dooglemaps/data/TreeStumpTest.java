package com.dooglemaps.data;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Telling a felled stump apart from the tree that was standing there.
 *
 * <h2>The state the varbit table cannot express</h2>
 *
 * A tree patch takes three clicks and the game gives them three values: grown-but-unchecked,
 * checked-and-choppable, and stump. {@code PatchRules} decodes the last two identically — same
 * {@code Produce}, same {@code HARVESTABLE}, same stage 0 — because it is generated from RuneLite
 * core's tables and core does not need the difference.
 *
 * <p>The plugin does. Without it the guide said "harvest the magic" at a checked tree, said it
 * again at the stump, and went on saying it: nothing the player clicked changed the state being
 * tested, so the patch never finished and the stop never completed. Reported from play as a yew
 * contract that could not be started because the magic tree in front of it never came out.
 *
 * <p>{@link PatchImplementation#isStumpVarbitValue} recovers the difference from the shape of the
 * table rather than from an entry in it — a stump is a stage-0 harvestable value whose predecessor
 * is also a stage-0 harvestable value of the same crop. These tests pin that shape, on every crop
 * it applies to and on the families it must not be applied to.
 */
public class TreeStumpTest
{
	/**
	 * The case that was reported, spelled out.
	 *
	 * <p>Magic: 60 is grown and unchecked, 61 is the tree you can chop, 62 is the stump.
	 */
	@Test
	public void magicTellsItsThreeEndStatesApart()
	{
		assertFalse("60 is grown but unchecked - still GROWING",
			PatchImplementation.TREE.isStumpVarbitValue(60));
		assertFalse("61 is a checked tree, still standing",
			PatchImplementation.TREE.isStumpVarbitValue(61));
		assertTrue("62 is the stump", PatchImplementation.TREE.isStumpVarbitValue(62));
	}

	/**
	 * Every tree, not just the one that was reported.
	 *
	 * <p>Derived from the table rather than listed, so a sixth tree added upstream is covered the
	 * day it appears rather than the day somebody notices. One stump per crop, and the value below
	 * it is the tree still standing.
	 */
	@Test
	public void everyTreeHasExactlyOneStumpValue()
	{
		for (Produce produce : Produce.values())
		{
			if (produce.getPatchImplementation() != PatchImplementation.TREE)
			{
				continue;
			}

			int stumps = 0;
			for (int value = 0; value < 256; value++)
			{
				if (!PatchImplementation.TREE.isStumpVarbitValue(value))
				{
					continue;
				}
				ProduceState decoded = PatchImplementation.TREE.forVarbitValue(value);
				assertNotNull(decoded);
				if (decoded.getProduce() != produce)
				{
					continue;
				}
				stumps++;

				assertFalse(produce.getName() + ": the value below a stump is the tree, not another"
						+ " stump",
					PatchImplementation.TREE.isStumpVarbitValue(value - 1));
			}

			assertEquals(produce.getName() + " should have exactly one stump value", 1, stumps);
		}
	}

	/**
	 * The willow block that broke the first version of this rule, named so it stays broken-proof.
	 *
	 * <h2>Six consecutive harvestable values, none of them a stump</h2>
	 *
	 * {@code TREE} carries willow twice: 21-23 is the ordinary grown/checked/stump run, and
	 * <b>192 to 197</b> is a second block of six harvestable stage-0 values. Five of those six have
	 * a harvestable predecessor, so a rule that only looked one value back called all five stumps —
	 * and a willow patch in any of those states would have been told to dig itself up.
	 *
	 * <p>What excludes them is the third value in the window: a real stump is preceded by the
	 * checked tree, which is preceded by the <i>growing</i> one. 192 is preceded by weeds, and
	 * everything above it by another harvestable.
	 */
	@Test
	public void theSecondWillowBlockIsNotSixStumps()
	{
		for (int value = 192; value <= 197; value++)
		{
			ProduceState decoded = PatchImplementation.TREE.forVarbitValue(value);
			assertNotNull("fixture value " + value + " no longer decodes", decoded);
			assertEquals(Produce.WILLOW, decoded.getProduce());
			assertEquals(CropState.HARVESTABLE, decoded.getCropState());
			assertFalse(value + " is not a stump, whatever its neighbour is",
				PatchImplementation.TREE.isStumpVarbitValue(value));
		}

		assertTrue("the real willow stump is still found",
			PatchImplementation.TREE.isStumpVarbitValue(23));
	}

	/** Hardwoods have the same three-value shape, so they get the same treatment. */
	@Test
	public void hardwoodsAreToldApartToo()
	{
		// Teak: 15 unchecked, 16 choppable, 17 stump.
		assertFalse(PatchImplementation.HARDWOOD_TREE.isStumpVarbitValue(15));
		assertFalse(PatchImplementation.HARDWOOD_TREE.isStumpVarbitValue(16));
		assertTrue(PatchImplementation.HARDWOOD_TREE.isStumpVarbitValue(17));

		// Mahogany: 38, 39, 40.
		assertTrue(PatchImplementation.HARDWOOD_TREE.isStumpVarbitValue(40));
	}

	/**
	 * The families the rule must never be let loose on.
	 *
	 * <h2>Why "no tree there" is not enough of an answer</h2>
	 *
	 * A bush, a fruit tree and a celastrus all have <b>runs</b> of consecutive harvestable values,
	 * counting how much produce is left on the plant. The predecessor test would read the second
	 * berry of five as a stump, and the guide would tell you to dig up a bush you had picked once.
	 *
	 * <p>So the guard is on the patch implementation and it is load-bearing, not defensive. This
	 * walks every value of every excluded family and insists none of them is ever a stump.
	 */
	@Test
	public void nothingButTreesAndHardwoodsIsEverAStump()
	{
		for (PatchImplementation implementation : PatchImplementation.values())
		{
			if (implementation == PatchImplementation.TREE
				|| implementation == PatchImplementation.HARDWOOD_TREE)
			{
				continue;
			}

			for (int value = 0; value < 256; value++)
			{
				assertFalse(implementation + " must have no stump values, and " + value + " read as one",
					implementation.isStumpVarbitValue(value));
			}
		}
	}

	/**
	 * A fruit tree is the specific trap, so it is named rather than left to the sweep above.
	 *
	 * <p>Apple: 14 to 20 are harvestable, counting nought to six fruit. Every one of those but the
	 * first has a harvestable predecessor of the same crop, which is precisely the rule's shape —
	 * and the stage check is what saves it. If the guard on the implementation were ever relaxed,
	 * this is the assertion that should fail first and loudest.
	 */
	@Test
	public void aPickedFruitTreeIsNotAStump()
	{
		for (int value = 14; value <= 20; value++)
		{
			ProduceState decoded = PatchImplementation.FRUIT_TREE.forVarbitValue(value);
			assertNotNull("fixture value " + value + " no longer decodes", decoded);
			assertEquals(CropState.HARVESTABLE, decoded.getCropState());
			assertFalse("apple stock " + value + " is fruit left on the tree, not a stump",
				PatchImplementation.FRUIT_TREE.isStumpVarbitValue(value));
		}
	}

	/** A value the patch does not use answers false rather than throwing. */
	@Test
	public void anUnknownValueIsNotAStump()
	{
		assertFalse(PatchImplementation.TREE.isStumpVarbitValue(0));
		assertFalse(PatchImplementation.TREE.isStumpVarbitValue(-1));
		assertFalse(PatchImplementation.TREE.isStumpVarbitValue(255));
	}
}
