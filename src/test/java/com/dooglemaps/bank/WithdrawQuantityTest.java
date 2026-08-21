package com.dooglemaps.bank;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Picking a withdraw amount, which is arithmetic with one rule: never more than is wanted.
 *
 * <p>See {@code docs/withdraw-quantity-swap-spec.md}. The no-overshoot rule is the owner's, and it
 * is what makes the click and the panel unable to disagree.
 */
public class WithdrawQuantityTest
{
	/** A bank offers 1, 5 and 10; the leprechaun's store has no 10. */
	private static final List<Integer> BANK = Arrays.asList(1, 5, 10);
	private static final List<Integer> LEPRECHAUN = Arrays.asList(1, 5);

	/** The reported case: fifteen watermelons for a bin, in two clicks rather than a stack. */
	@Test
	public void theWorkedExampleFromTheSpec()
	{
		assertEquals("ten first", 10, WithdrawQuantity.choose(BANK, 15));
		assertEquals("then the five that is left", 5, WithdrawQuantity.choose(BANK, 5));
		assertEquals("and then it is done", 0, WithdrawQuantity.choose(BANK, 0));
	}

	/**
	 * Never rounds up, however tempting.
	 *
	 * <p>Four buckets is four clicks. That is the accepted cost of the rule rather than a hole in
	 * it — a spare bucket crowds out a harvest, which is the whole reason the feature exists.
	 */
	@Test
	public void neverTakesMoreThanIsWanted()
	{
		assertEquals("four is four ones, not a five", 1, WithdrawQuantity.choose(BANK, 4));
		assertEquals(5, WithdrawQuantity.choose(BANK, 9));
		assertEquals(1, WithdrawQuantity.choose(BANK, 1));
	}

	/** The leprechaun's ladder is shorter, which needs no special case — 10 simply is not there. */
	@Test
	public void theLeprechaunsShorterLadderIsHandledByAbsence()
	{
		assertEquals("no ten to choose", 5, WithdrawQuantity.choose(LEPRECHAUN, 15));
		assertEquals(5, WithdrawQuantity.choose(LEPRECHAUN, 5));
		assertEquals(1, WithdrawQuantity.choose(LEPRECHAUN, 4));
	}

	/** Nothing wanted, nothing to promote — the menu is left as the game built it. */
	@Test
	public void wantingNothingChoosesNothing()
	{
		assertEquals(0, WithdrawQuantity.choose(BANK, 0));
		assertEquals(0, WithdrawQuantity.choose(BANK, -3));
		assertEquals(0, WithdrawQuantity.choose(Collections.emptyList(), 15));
		assertEquals(0, WithdrawQuantity.choose(null, 15));
	}

	/** The three amounts it will ever name, read off an option. */
	@Test
	public void theLadderIsReadFromTheOptionText()
	{
		assertEquals(1, WithdrawQuantity.amountNamed("Withdraw-1"));
		assertEquals(5, WithdrawQuantity.amountNamed("Withdraw-5"));
		assertEquals(10, WithdrawQuantity.amountNamed("Withdraw-10"));
	}

	/**
	 * {@code Withdraw-X} is never a candidate, whatever number it is wearing.
	 *
	 * <h2>Why this is the load-bearing test in the file</h2>
	 *
	 * The game renders X as the amount the player last set, so a menu can carry "Withdraw-25" and
	 * the string alone cannot say whether that is a fixed rung or somebody's X. Choosing it would
	 * put an amount under the click that the plugin cannot know before it lands — the exact failure
	 * the whole feature exists to prevent, and the owner's own word on it was that it "sounds like
	 * a disaster in the making".
	 *
	 * <p>The whitelist is what makes that impossible rather than unlikely. A set X of 5 or 10 is
	 * indistinguishable from the rung and is fine either way: the amount is the amount.
	 */
	@Test
	public void anythingOffTheLadderIsNeverChosen()
	{
		assertEquals("someone's X", 0, WithdrawQuantity.amountNamed("Withdraw-25"));
		assertEquals(0, WithdrawQuantity.amountNamed("Withdraw-2"));
		assertEquals(0, WithdrawQuantity.amountNamed("Withdraw-1000"));
		assertEquals("the literal X, before one is set", 0,
			WithdrawQuantity.amountNamed("Withdraw-X"));
		assertEquals(0, WithdrawQuantity.amountNamed("Withdraw-All"));
		assertEquals(0, WithdrawQuantity.amountNamed("Withdraw-All-but-1"));
	}

	/** And nothing shaped like an option at all. */
	@Test
	public void rubbishNamesNothing()
	{
		assertEquals(0, WithdrawQuantity.amountNamed(null));
		assertEquals(0, WithdrawQuantity.amountNamed(""));
		assertEquals(0, WithdrawQuantity.amountNamed("Withdraw-"));
		assertEquals(0, WithdrawQuantity.amountNamed("Examine"));
		assertEquals(0, WithdrawQuantity.amountNamed("99999999999999999999"));
	}

	/**
	 * A choice is only ever made from what the menu actually offers.
	 *
	 * <p>The ladder is a preference order, not an assumption about what is on screen: a menu
	 * carrying only 1 and All gives ones, however many are wanted.
	 */
	@Test
	public void onlyWhatIsOfferedCanBeChosen()
	{
		assertEquals(1, WithdrawQuantity.choose(Collections.singletonList(1), 30));
		assertEquals(10, WithdrawQuantity.choose(Arrays.asList(10, 1), 12));
	}
}
