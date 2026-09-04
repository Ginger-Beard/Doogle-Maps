package com.dooglemaps.data;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The four families a gardener will take coins to clear, and what each one costs.
 *
 * <p>Wiki-checked: 200 coins for an ordinary tree, fruit tree or hardwood; 2,000 for the
 * redwood, which is also the only way a redwood is ever cleared. Everything else — the calquat,
 * celastrus, crystal and spirit trees included — is cleared by the player's own hand, and no
 * gardener stands by any of their patches at all.
 */
public class PayToClearTest
{
	@Test
	public void everyPayableFamilyHasAPriceAndEveryOtherHasNone()
	{
		for (PatchImplementation type : PatchImplementation.values())
		{
			boolean shouldSupport = type == PatchImplementation.TREE
				|| type == PatchImplementation.FRUIT_TREE
				|| type == PatchImplementation.HARDWOOD_TREE
				|| type == PatchImplementation.REDWOOD;

			assertEquals(type + " support disagrees with the wiki", shouldSupport,
				PayToClear.supports(type));
			assertEquals(type + " cost disagrees with the wiki", shouldSupport,
				PayToClear.cost(type) > 0);
		}
	}

	@Test
	public void ordinaryGardenersChargeTwoHundred()
	{
		assertEquals(200, PayToClear.cost(PatchImplementation.TREE));
		assertEquals(200, PayToClear.cost(PatchImplementation.FRUIT_TREE));
		assertEquals(200, PayToClear.cost(PatchImplementation.HARDWOOD_TREE));
	}

	/** Alexandra is dearer, and hers is the only way a redwood is ever cleared. */
	@Test
	public void theRedwoodCostsTenTimesAsMuch()
	{
		assertEquals(2000, PayToClear.cost(PatchImplementation.REDWOOD));
	}

	@Test
	public void theTreeSoloistsHaveNoGardener()
	{
		assertFalse("a calquat is cleared by hand",
			PayToClear.supports(PatchImplementation.CALQUAT));
		assertFalse("celastrus is cleared by hand",
			PayToClear.supports(PatchImplementation.CELASTRUS));
		assertFalse("the crystal tree is cleared by hand",
			PayToClear.supports(PatchImplementation.CRYSTAL_TREE));
		assertFalse("the spirit tree is cleared by hand",
			PayToClear.supports(PatchImplementation.SPIRIT_TREE));
	}

	@Test
	public void aSeedIsSupportedThroughItsPatchType()
	{
		assertTrue(PayToClear.supports(Seed.MAGIC));
		assertTrue(PayToClear.supports(Seed.PAPAYA));
		assertTrue(PayToClear.supports(Seed.TEAK));
		assertTrue(PayToClear.supports(Seed.REDWOOD));
		assertFalse(PayToClear.supports(Seed.CALQUAT));
		assertFalse(PayToClear.supports(Seed.RANARR));
	}

	/**
	 * Alexandra takes coins, but there is nothing to tick a box about.
	 *
	 * <p>No standing redwood is ever offered for payment — {@code isClearable} leaves the family
	 * out, because its varbit table cannot name a finished tree — and the one state that can be
	 * named, dead, is cleared by her unconditionally, a spade being unable to touch it. A redwood
	 * tick-box in the seed selector would therefore be a control nothing downstream ever reads,
	 * which is exactly the shape of a setting a player changes and then wonders about.
	 */
	@Test
	public void theRedwoodTakesCoinsButOffersNoChoice()
	{
		assertTrue(PayToClear.supports(PatchImplementation.REDWOOD));
		assertFalse("Alexandra is the only route, so there is nothing to choose",
			PayToClear.offersAChoice(PatchImplementation.REDWOOD));

		assertTrue(PayToClear.offersAChoice(PatchImplementation.TREE));
		assertTrue(PayToClear.offersAChoice(PatchImplementation.FRUIT_TREE));
		assertTrue(PayToClear.offersAChoice(PatchImplementation.HARDWOOD_TREE));
		assertFalse(PayToClear.offersAChoice(PatchImplementation.HERB));
		assertFalse(PayToClear.offersAChoice(null));
	}

	@Test
	public void unsupportedTypesAndNullAnswerNoCost()
	{
		assertEquals(0, PayToClear.cost(PatchImplementation.CALQUAT));
		assertEquals(0, PayToClear.cost(null));
		assertFalse(PayToClear.supports((PatchImplementation) null));
		assertFalse(PayToClear.supports((Seed) null));
	}
}
