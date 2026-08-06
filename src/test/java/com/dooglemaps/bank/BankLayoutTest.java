package com.dooglemaps.bank;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Covers the bank layout map: the grid arithmetic, and what happens to a map that is wrong.
 *
 * <p>Worth testing without a client because it is pure arithmetic over a string, and because the
 * failure mode is silent — a layout that is off by one column does not throw, it just puts your
 * seeds somewhere you did not ask for.
 */
public class BankLayoutTest
{
	/** Arbitrary ids; only their placement matters. */
	private static final int TELE_A = 8007;
	private static final int TELE_B = 8008;
	private static final int SEED_A = 5295;
	private static final int SEED_B = 5300;
	private static final int PAYMENT = 5974;
	private static final int GEAR = 5343;

	/** The default map puts each group where the class javadoc says it does. */
	@Test
	public void theDefaultMapPlacesEachGroupInItsOwnBlock()
	{
		int[] layout = BankLayout.build(loadout(), BankLayout.DEFAULT_MAP);

		assertEquals("teleports start at A1", TELE_A, layout[at('A', 1)]);
		assertEquals("and fill left to right", TELE_B, layout[at('B', 1)]);
		assertEquals("seeds start at E1", SEED_A, layout[at('E', 1)]);
		assertEquals(SEED_B, layout[at('F', 1)]);
		assertEquals("payments at E4", PAYMENT, layout[at('E', 4)]);
		assertEquals("gear at E6", GEAR, layout[at('E', 6)]);

		assertEquals("column D is the gutter and stays clear", -1, layout[at('D', 1)]);
		assertEquals("and so does the blank row between seeds and payments",
			-1, layout[at('E', 3)]);
	}

	/**
	 * A map is a set of regions, so a group's position does not move with the run's size.
	 *
	 * <p>This is the whole reason it is a map rather than a flow: seeds are in the same place
	 * whether the run needs one or six, so you learn where to look once.
	 */
	@Test
	public void aGroupsPositionDoesNotDependOnHowManyOtherItemsThereAre()
	{
		List<LoadoutItem> justOneSeed = new ArrayList<>();
		justOneSeed.add(item(SEED_A, LoadoutItem.Category.SEED));
		justOneSeed.add(item(GEAR, LoadoutItem.Category.GEAR));

		int[] layout = BankLayout.build(justOneSeed, BankLayout.DEFAULT_MAP);

		assertEquals(SEED_A, layout[at('E', 1)]);
		assertEquals("gear is still at E6 with only one seed above it",
			GEAR, layout[at('E', 6)]);
	}

	/** Editing the map moves things, which is the point of it being a setting. */
	@Test
	public void aCustomMapIsHonoured()
	{
		String swapped =
			"SSSS.TTT\n"
				+ "........";

		int[] layout = BankLayout.build(loadout(), swapped);

		assertEquals("seeds now start top left", SEED_A, layout[at('A', 1)]);
		assertEquals("and teleports start at F", TELE_A, layout[at('F', 1)]);
	}

	/** Every category the loadout can produce has a letter, or its items would vanish. */
	@Test
	public void everyLoadoutCategoryHasSomewhereToGo()
	{
		String allOneGroup = "GGGGGGGG\n........";
		for (LoadoutItem.Category category : LoadoutItem.Category.values())
		{
			List<LoadoutItem> one = new ArrayList<>();
			one.add(item(4151, category));

			// Placed by *some* map, which is what proves the category is mapped at all. Teleports,
			// seeds and payments have their own letters and are covered above.
			int[] wide = BankLayout.build(one,
				"TTSSPPGG\n" + "........");
			boolean placed = false;
			for (int slot : wide)
			{
				placed |= slot == 4151;
			}
			assertEquals("no letter claims " + category + ", so its items would disappear",
				true, placed);
		}
		assertNull(BankLayout.validate(allOneGroup));
	}

	@Test
	public void aMapWithAShortRowIsRejectedWithAReason()
	{
		String message = BankLayout.validate("TTT.SSS\n........");
		assertNotNull("seven cells in row one is not a bank row", message);
		assertEquals(true, message.contains("row 1"));
	}

	@Test
	public void aMapWithAnUnknownLetterIsRejectedWithAReason()
	{
		String message = BankLayout.validate("TTT.SSSX\n........");
		assertNotNull(message);
		assertEquals(true, message.contains("'X'"));
	}

	@Test
	public void aMapTallerThanTheBankIsRejected()
	{
		StringBuilder tall = new StringBuilder();
		for (int row = 0; row < BankLayout.ROWS + 1; row++)
		{
			tall.append(row > 0 ? "\n" : "").append("........");
		}
		assertNotNull(BankLayout.validate(tall.toString()));
	}

	/** An unusable map falls back rather than throwing or half-applying. */
	@Test
	public void anUnusableMapFallsBackToTheDefault()
	{
		int[] fallback = BankLayout.build(loadout(), "nonsense");
		assertEquals("the default's placement, not an empty grid",
			SEED_A, fallback[at('E', 1)]);
	}

	/** The default map is itself valid, which is easy to break by editing it. */
	@Test
	public void theDefaultMapIsValid()
	{
		assertNull(BankLayout.validate(BankLayout.DEFAULT_MAP));
	}

	/**
	 * Trailing newlines do not invalidate a map.
	 *
	 * <p>A text area collects them the moment anyone edits it, and rejecting the map for a stray
	 * keystroke would be the setting fighting the person using it.
	 */
	@Test
	public void blankLinesAreIgnored()
	{
		assertNull(BankLayout.validate(BankLayout.DEFAULT_MAP + "\n\n"));
		assertNull(BankLayout.validate("\n" + BankLayout.DEFAULT_MAP));
	}

	/** Spreadsheet addressing: column letter and 1-based row, to a flat index. */
	private static int at(char column, int row)
	{
		return (row - 1) * BankLayout.COLUMNS + (column - 'A');
	}

	private static List<LoadoutItem> loadout()
	{
		List<LoadoutItem> items = new ArrayList<>();
		items.add(item(TELE_A, LoadoutItem.Category.TELEPORT));
		items.add(item(TELE_B, LoadoutItem.Category.TELEPORT));
		items.add(item(SEED_A, LoadoutItem.Category.SEED));
		items.add(item(SEED_B, LoadoutItem.Category.SEED));
		items.add(item(PAYMENT, LoadoutItem.Category.PAYMENT));
		items.add(item(GEAR, LoadoutItem.Category.GEAR));
		return items;
	}

	private static LoadoutItem item(int itemId, LoadoutItem.Category category)
	{
		return new LoadoutItem(itemId, "item " + itemId, category, LoadoutItem.Need.WITHDRAW, 1,
			"because");
	}
}
