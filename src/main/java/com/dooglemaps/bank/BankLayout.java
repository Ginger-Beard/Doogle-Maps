package com.dooglemaps.bank;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where each of the run's items sits in the bank, drawn as a map.
 *
 * <p>Filtering answers "only these". This answers "and in this order", which is the difference
 * between a bank you read and a bank you scan.
 *
 * <h2>The map is the setting</h2>
 *
 * One character per bank slot, rows separated by {@code /}. The default:
 *
 * <pre>
 *     A B C D E F G H
 *  1  T T T . S S S S      T  teleports
 *  2  T T T . S S S S      S  seeds
 *  3  T T T . S S S S      P  protection payments
 *  4  T T T . P P P P      G  gear: tools, outfit, storage, compost
 *  5  T T T . . . . .      .  left empty
 *  6  T T T . G G G G
 *  7  T T T . G G G G
 *  8  T T T . G G G G
 * </pre>
 *
 * <p>Regions rather than a flow, and that is the point of making it a map: the gaps are wherever
 * you draw them, seeds are always in the same place whether the run needs one or six, and changing
 * the shape is editing a string rather than editing this file. A blank row between groups is
 * simply a row of dots — no rule needed for it, because you can see it.
 *
 * <h2>The grid is not negotiable, but everything in it is</h2>
 *
 * {@code BankTagsPlugin.BANK_ITEMS_PER_ROW} is 8 and a {@link
 * net.runelite.client.plugins.banktags.tabs.Layout} is a flat array indexed
 * {@code row * 8 + column}, so the columns are A to H and there is no ninth. Rows past the eighth
 * are reachable only by scrolling, so the default uses all eight and no more.
 *
 * <p>It once stopped at seven, to keep the last row clear for overflow. That was unnecessary:
 * anything the map has no room for is placed by Bank Tags <i>after</i> the whole layout, under
 * {@code OPTION_ITEMS_NOT_IN_LAYOUT_AT_BOTTOM}, rather than in the first free slot — so reserving
 * a row bought nothing and cost eight slots of the only screen that matters.
 *
 * <p>A map that does not parse is <b>ignored with a warning</b> rather than half-applied. A
 * half-read layout would put items in places nobody asked for, and the failure would look like a
 * bug in the bank rather than a typo in a setting.
 */
public final class BankLayout
{
	/** The bank's own width. See {@code BankTagsPlugin.BANK_ITEMS_PER_ROW}. */
	public static final int COLUMNS = 8;

	/** Rows the bank shows without scrolling. */
	public static final int ROWS = 8;

	/**
	 * What separates one row from the next.
	 *
	 * <p>A newline, because the setting is a text area and being able to see the shape of the grid
	 * is the entire point of it being a map.
	 *
	 * <h2>And three other things, because the newline is not what the player sees</h2>
	 *
	 * RuneLite stores settings in a {@code .properties} file, and {@code Properties.store} escapes
	 * a real newline as the two characters {@code \n}. That is correct and it round-trips —
	 * {@code Properties.load} turns it back — but the escaped form is what appears anywhere the raw
	 * value is shown, so a player looking at this setting sees a backslash at the end of every row
	 * and reasonably concludes that is the separator. Reported exactly that way.
	 *
	 * <p>Typing what you were shown then produced a map that silently failed to parse: eight rows
	 * became one 71-character row, {@link #validate} rejected it, and the whole setting fell back
	 * to the default with only a log line to say so. A free-text setting that punishes the player
	 * for copying its own displayed form is a bad setting, so all of it is accepted:
	 *
	 * <ul>
	 *   <li>a real newline, which is what the text area produces;
	 *   <li>a literal {@code \n}, which is what the stored form looks like;
	 *   <li>a {@code /}, which the class comment above has always claimed and which is genuinely
	 *       convenient for writing a map on one line.
	 * </ul>
	 *
	 * <p>A trailing backslash on a row is stripped separately, in {@link #rowsOf}, so a map that is
	 * half one convention and half the other still reads.
	 */
	private static final String ROW_SEPARATOR = "\\\\n|\\r?\\n|/";

	/** An empty slot, in the map and in the array Bank Tags wants. */
	private static final char EMPTY = '.';
	private static final int NO_ITEM = -1;

	/**
	 * What each letter claims, and the order groups are filled in.
	 *
	 * <p>Gear is four categories under one letter because that is how it reads at a bank: the axe,
	 * the secateurs, the outfit and the seed box are one handful of things you either have or go
	 * and get. Compost is in with them because it usually comes from the leprechaun and only
	 * appears here at all when he is out of it.
	 */
	private static final Map<Character, Set<LoadoutItem.Category>> GROUPS = new LinkedHashMap<>();

	static
	{
		GROUPS.put('T', EnumSet.of(LoadoutItem.Category.TELEPORT));
		GROUPS.put('S', EnumSet.of(LoadoutItem.Category.SEED));
		GROUPS.put('P', EnumSet.of(LoadoutItem.Category.PAYMENT));
		GROUPS.put('G', EnumSet.of(LoadoutItem.Category.TOOL, LoadoutItem.Category.GEAR,
			LoadoutItem.Category.STORAGE, LoadoutItem.Category.COMPOST));
	}

	/**
	 * Teleports down the left, the run down the right, a clear gutter between them.
	 *
	 * <p>Seeds first because they are what the run is <i>for</i> and what you are most likely to be
	 * short of; gear last because it is the same axe and secateurs every trip, so it is the part
	 * you stop reading soonest.
	 */
	public static final String DEFAULT_MAP =
		"TTT.SSSS\n"
			+ "TTT.SSSS\n"
			+ "TTT.SSSS\n"
			+ "TTT.PPPP\n"
			+ "TTT.....\n"
			+ "TTT.GGGG\n"
			+ "TTT.GGGG\n"
			+ "TTT.GGGG";

	private BankLayout()
	{
	}

	/** Why a map was rejected, or null when it is usable. */
	public static String validate(String map)
	{
		if (map == null || map.trim().isEmpty())
		{
			return "the map is empty";
		}

		List<String> rows = rowsOf(map);
		if (rows.size() > ROWS)
		{
			return "the map has " + rows.size() + " rows; the bank shows " + ROWS
				+ " without scrolling";
		}

		for (int row = 0; row < rows.size(); row++)
		{
			String cells = rows.get(row);
			if (cells.length() != COLUMNS)
			{
				return "row " + (row + 1) + " has " + cells.length() + " cells; every row needs "
					+ COLUMNS + " (columns A to H)";
			}
			for (char cell : cells.toCharArray())
			{
				if (cell != EMPTY && !GROUPS.containsKey(Character.toUpperCase(cell)))
				{
					return "row " + (row + 1) + " contains '" + cell + "'; use "
						+ legend() + " or '" + EMPTY + "'";
				}
			}
		}
		return null;
	}

	/**
	 * The map's rows, blank lines discarded.
	 *
	 * <p>A text area collects trailing newlines the moment anyone edits it, and a blank line
	 * cannot mean anything here anyway — an empty row is eight dots, not nothing. Dropping them
	 * is what stops a stray keystroke invalidating a map that is otherwise fine.
	 */
	private static List<String> rowsOf(String map)
	{
		List<String> rows = new ArrayList<>();
		for (String row : map.trim().split(ROW_SEPARATOR))
		{
			// A trailing backslash is dropped before trimming. It is what is left over when a row
			// was separated by a real newline but written from the escaped form the settings file
			// shows — "TTT.SSSS\" and then a line break. Harmless to strip when it is not there.
			String cells = row.trim();
			while (cells.endsWith("\\"))
			{
				cells = cells.substring(0, cells.length() - 1).trim();
			}
			if (!cells.isEmpty())
			{
				rows.add(cells);
			}
		}
		return rows;
	}

	/** The letters a map may use, for error messages and the setting's description. */
	public static String legend()
	{
		List<String> letters = new ArrayList<>();
		for (char letter : GROUPS.keySet())
		{
			letters.add("'" + letter + "'");
		}
		return String.join(", ", letters);
	}

	/**
	 * The layout array for a run, as Bank Tags wants it: {@code -1} for an empty slot.
	 *
	 * <p>Each group fills its own region left to right, top to bottom, in the order
	 * {@code RunLoadout} produced it. Anything that does not fit its region is deliberately
	 * <b>left out</b> rather than spilled somewhere else — Bank Tags puts unplaced items after the
	 * layout when opened with {@code OPTION_ITEMS_NOT_IN_LAYOUT_AT_BOTTOM}, so an overflowing run
	 * still shows everything. It just stops being a tidy grid, which is the honest outcome when you
	 * own more than fits.
	 *
	 * @param map a map as described on this class; falls back to {@link #DEFAULT_MAP} if unusable
	 */
	public static int[] build(List<LoadoutItem> items, String map)
	{
		return build(items, map, null);
	}

	/**
	 * The layout array for a run, placing only items the bank actually holds.
	 *
	 * <p>The restriction is the point rather than an optimisation. A layout slot is a reservation,
	 * and Bank Tags fills a reservation it cannot satisfy with a faded stand-in — so laying out
	 * the whole loadout showed a dulled item for every seed the run wanted and you did not own,
	 * indistinguishable at a glance from one you did. See {@code BankFilter.saveLayout}.
	 *
	 * <p>Every {@link RunLoadout#bankFormsOf bank form} is considered, not just the id the loadout
	 * names: a loadout item is the planted form, and for a tree crop what is sitting in the bank is
	 * the seed. Both are placed when you hold both, because at that point you genuinely have two
	 * items and hiding either would be the same lie in the other direction.
	 *
	 * @param banked every item id the bank holds, or null to place everything regardless
	 */
	public static int[] build(List<LoadoutItem> items, String map, Set<Integer> banked)
	{
		int[] layout = new int[ROWS * COLUMNS];
		Arrays.fill(layout, NO_ITEM);

		Map<Character, List<Integer>> regions = regionsIn(map);
		for (Map.Entry<Character, Set<LoadoutItem.Category>> group : GROUPS.entrySet())
		{
			List<Integer> slots = regions.get(group.getKey());
			if (slots == null)
			{
				continue;
			}

			List<Integer> ids = itemsIn(items, group.getValue(), banked);
			for (int i = 0; i < ids.size() && i < slots.size(); i++)
			{
				layout[slots.get(i)] = ids.get(i);
			}
		}
		return layout;
	}

	/** Every slot each letter claims, in reading order. */
	private static Map<Character, List<Integer>> regionsIn(String map)
	{
		String usable = validate(map) == null ? map.trim() : DEFAULT_MAP;

		Map<Character, List<Integer>> regions = new LinkedHashMap<>();
		List<String> rows = rowsOf(usable);
		for (int row = 0; row < rows.size(); row++)
		{
			String cells = rows.get(row);
			for (int column = 0; column < cells.length(); column++)
			{
				char cell = Character.toUpperCase(cells.charAt(column));
				if (cell == EMPTY)
				{
					continue;
				}
				regions.computeIfAbsent(cell, k -> new ArrayList<>())
					.add(row * COLUMNS + column);
			}
		}
		return regions;
	}

	/**
	 * The item ids in these categories, in loadout order and without repeats.
	 *
	 * <p>Deduplicated because the same id in two slots is not a layout Bank Tags can honour — the
	 * second placement wins and the first becomes a hole.
	 *
	 * @param banked the bank's contents, or null to place every item regardless of whether it is
	 *               there; see {@link #build(List, String, Set)}
	 */
	private static List<Integer> itemsIn(List<LoadoutItem> items,
		Set<LoadoutItem.Category> categories, Set<Integer> banked)
	{
		Set<Integer> ids = new LinkedHashSet<>();
		for (LoadoutItem item : items)
		{
			if (!categories.contains(item.getCategory()))
			{
				continue;
			}

			if (banked == null)
			{
				ids.add(item.getItemId());
				continue;
			}

			for (int form : RunLoadout.bankFormsOf(item.getItemId()))
			{
				if (banked.contains(form))
				{
					ids.add(form);
				}
			}
		}
		return new ArrayList<>(ids);
	}
}
