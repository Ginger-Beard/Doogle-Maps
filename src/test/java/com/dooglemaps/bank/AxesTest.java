package com.dooglemaps.bank;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Covers the axe table, which is hand-written and therefore the part most able to be quietly
 * incomplete.
 *
 * <p>The felling axes are the reason this exists. RuneLite names them {@code *_AXE_2H} with no
 * mention of "felling" anywhere, so they are easy to look straight past — they were in the
 * table under the wrong names and the class comment claimed they had been left out.
 */
public class AxesTest
{
	/**
	 * Every felling axe in the game, by id.
	 *
	 * <p>Written as literals rather than through {@code ItemID}, so this fails if a constant is
	 * ever renamed to point somewhere else. 28196 is the bronze felling axe, confirmed against
	 * the wiki, and the family runs contiguously from there.
	 */
	private static final int[] FELLING_AXE_IDS = {
		28196,   // bronze
		28199,   // iron
		28202,   // steel
		28205,   // black
		28208,   // mithril
		28211,   // adamant
		28214,   // rune
		28217,   // dragon
		28220,   // crystal
		28226,   // 3rd age
	};

	@Test
	public void everyFellingAxeIsInTheTable()
	{
		Set<Integer> known = new HashSet<>();
		for (Axes.Axe axe : Axes.byTier())
		{
			known.add(axe.getItemId());
		}

		Set<Integer> missing = new TreeSet<>();
		for (int id : FELLING_AXE_IDS)
		{
			if (!known.contains(id))
			{
				missing.add(id);
			}
		}

		assertEquals("felling axes missing from the table: " + missing,
			new TreeSet<Integer>(), missing);
	}

	/** They should read as felling axes too, or the loadout names something that is not a thing. */
	@Test
	public void fellingAxesAreNamedAsSuch()
	{
		List<String> misnamed = new ArrayList<>();
		Set<Integer> felling = new HashSet<>();
		for (int id : FELLING_AXE_IDS)
		{
			felling.add(id);
		}

		for (Axes.Axe axe : Axes.byTier())
		{
			if (felling.contains(axe.getItemId())
				&& !axe.getName().toLowerCase().contains("felling"))
			{
				misnamed.add(axe.getName());
			}
		}

		assertTrue("these are felling axes but are not called that: " + misnamed,
			misnamed.isEmpty());
	}

	/** Best first, because the loadout takes the first usable one it finds. */
	@Test
	public void theTableIsOrderedBestFirst()
	{
		int previous = Integer.MAX_VALUE;
		for (Axes.Axe axe : Axes.byTier())
		{
			assertTrue(axe.getName() + " is out of tier order",
				axe.getWoodcuttingLevel() <= previous);
			previous = axe.getWoodcuttingLevel();
		}
	}

	/** A duplicate id would mean one entry could never be reached. */
	@Test
	public void noAxeAppearsTwice()
	{
		Set<Integer> seen = new HashSet<>();
		for (Axes.Axe axe : Axes.byTier())
		{
			assertTrue(axe.getName() + " is listed twice", seen.add(axe.getItemId()));
		}
	}

	/**
	 * Both entry points of the tier ladder are covered.
	 *
	 * <p>A level-1 account has to get something, and a maxed one should not be handed a bronze
	 * axe because the top of the table was never filled in.
	 */
	@Test
	public void theTableSpansTheWholeLevelRange()
	{
		int lowest = Integer.MAX_VALUE;
		int highest = 0;
		for (Axes.Axe axe : Axes.byTier())
		{
			lowest = Math.min(lowest, axe.getWoodcuttingLevel());
			highest = Math.max(highest, axe.getWoodcuttingLevel());
		}

		assertEquals("something has to be usable at level 1", 1, lowest);
		assertEquals("crystal is the top tier", 71, highest);
	}

	/** The uncharged crystal axe cannot chop, so suggesting it would waste a bank trip. */
	@Test
	public void unusableFormsAreLeftOut()
	{
		for (Axes.Axe axe : Axes.byTier())
		{
			assertTrue("an uncharged crystal axe cannot be used",
				axe.getItemId() != ItemID.CRYSTAL_AXE_INACTIVE
					&& axe.getItemId() != ItemID.CRYSTAL_AXE_2H_INACTIVE);
		}
	}
}
