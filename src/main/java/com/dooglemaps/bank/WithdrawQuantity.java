package com.dooglemaps.bank;

import java.util.Collection;

/**
 * Which of the game's fixed withdraw amounts to put under the left click.
 *
 * <p>The arithmetic half of the quantity swap, kept apart from the menu handling so it can be
 * reasoned about on its own. See {@code docs/withdraw-quantity-swap-spec.md}.
 *
 * <h2>The problem it exists for</h2>
 *
 * {@code Withdraw-X} cannot be set by a plugin — the amount behind it is the player's, stored
 * client-side. So the run knows exactly how many of something it wants and has no way to ask for
 * that number. What it has instead is a fixed menu: 1, 5, 10 and All at a bank, and 1, 5 and All
 * at the leprechaun's store.
 *
 * <p>The player's answer to that gap is All, because it is one click and always enough. It is also
 * the wrong click most of the time, and the cost is specific: fifteen empty buckets when the bin
 * run needed four leaves eleven slots of a harvest in the ground.
 *
 * <h2>Never more than is wanted</h2>
 *
 * The rule is the largest offered amount that does not exceed what is still needed, and it never
 * rounds up. Taking five for a wanted four was considered and rejected by the owner: a few extra
 * clicks are fine and a spare item is not.
 *
 * <p>That refusal is what buys the property the whole feature rests on — <b>the click never takes
 * more than the panel says</b>. The highlighted row and the option under the mouse cannot disagree,
 * in any case, ever, which is also why the feature never has to explain itself. An overshoot rule
 * would have made the two differ by design.
 *
 * <p>The cost is accepted rather than worked around: four buckets is four clicks, and at the
 * leprechaun — where the ladder is 1 and 5 only — awkward numbers cost more again.
 */
public final class WithdrawQuantity
{
	/**
	 * The amounts this will ever choose.
	 *
	 * <h2>A whitelist, and that is the point rather than a shortcut</h2>
	 *
	 * The bank also offers {@code Withdraw-X}, and the game renders it as <b>the number the player
	 * last set</b> — so a menu can carry "Withdraw-25" and nothing in the string says whether that
	 * is a fixed amount or somebody's X. Choosing it would put an amount under the click that this
	 * class cannot know before it lands, which is the exact failure the feature exists to prevent.
	 *
	 * <p>So only these three are ever eligible. Anything else on the menu is left where the game
	 * put it, {@code Withdraw-X} included, whatever number it happens to be wearing. The
	 * leprechaun's store offers no 10, which needs no special case: it simply never matches.
	 */
	private static final int[] LADDER = {10, 5, 1};

	private WithdrawQuantity()
	{
	}

	/**
	 * The amount to promote, or 0 to leave the menu exactly as the game built it.
	 *
	 * @param offered  the amounts actually on this menu, parsed from its options
	 * @param wanted   how many are still to be collected, live rather than from a tick-old plan
	 */
	public static int choose(Collection<Integer> offered, int wanted)
	{
		if (offered == null || wanted <= 0)
		{
			return 0;
		}

		for (int amount : LADDER)
		{
			if (amount <= wanted && offered.contains(amount))
			{
				return amount;
			}
		}
		return 0;
	}

	/**
	 * Whether a withdraw option is the All of its menu.
	 *
	 * <p>Exact after the dash, deliberately: {@code Withdraw-All-but-1} splits to "All-but-1",
	 * which is not "All" and must never be treated as it — that option empties the slot save
	 * one, which is a different promise entirely.
	 */
	public static boolean namesAll(String option)
	{
		if (option == null)
		{
			return false;
		}
		int dash = option.indexOf('-');
		return dash > 0 && "All".equalsIgnoreCase(option.substring(dash + 1).trim());
	}

	/**
	 * The amount a withdraw option names, or 0 if it names none this may choose.
	 *
	 * <p>Deliberately strict. It answers only for the three ladder amounts, so {@code Withdraw-All}
	 * and a {@code Withdraw-X} wearing any number both come back 0 and are never candidates. The
	 * option's own wording is not checked beyond its trailing number — the caller has already
	 * established it is a withdraw entry, and matching more of the string would break on the
	 * leprechaun's wording without buying anything.
	 */
	public static int amountNamed(String option)
	{
		if (option == null)
		{
			return 0;
		}

		// The FIRST dash, not the last, and a test earns this line: "Withdraw-All-but-1" ends in
		// "-1" and read as "withdraw one" — an option that empties the bank slot but one, promoted
		// as though it took a single item. Splitting at the first dash leaves "All-but-1", which
		// is not a number and is therefore not a candidate.
		int dash = option.indexOf('-');
		if (dash < 0 || dash == option.length() - 1)
		{
			return 0;
		}

		String tail = option.substring(dash + 1).trim();
		for (int i = 0; i < tail.length(); i++)
		{
			if (!Character.isDigit(tail.charAt(i)))
			{
				return 0;
			}
		}

		int amount;
		try
		{
			amount = Integer.parseInt(tail);
		}
		catch (NumberFormatException e)
		{
			// A number too long for an int, which is not an amount anybody set.
			return 0;
		}

		for (int rung : LADDER)
		{
			if (rung == amount)
			{
				return amount;
			}
		}
		return 0;
	}
}
