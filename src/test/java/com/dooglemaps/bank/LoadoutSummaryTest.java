package com.dooglemaps.bank;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The withdraw list: one row per item, with the count that is still to come out.
 *
 * <h2>The bug this pins down</h2>
 *
 * The list used to fold everything into prose — <i>"From the bank: yew sapling, cactus
 * spine."</i> — which named the errand and withheld the only part anyone gets wrong: how many.
 * The count also has to be the <b>live</b> one, {@code LoadoutItem.getWithdrawCount}, so the row
 * reads {@code x2} once one of three saplings is already in the pack — the same figure the cyan
 * number on the bank slot shows, from the same rule, so the two can never disagree.
 */
public class LoadoutSummaryTest
{
	@Test
	public void eachItemIsItsOwnRowWithItsCount()
	{
		List<String> lines = LoadoutSummary.forItems(Arrays.asList(
			withdraw("Yew sapling", LoadoutItem.Category.SEED, 3, 3),
			withdraw("Cactus spine", LoadoutItem.Category.PAYMENT, 30, 30)));

		assertEquals(Arrays.asList(
			"From the bank:",
			"- Yew sapling x3",
			"- Cactus spine x30"), lines);
	}

	/** The count is what is still missing from the pack, not the run's fixed total. */
	@Test
	public void theCountIsTheOutstandingOneSoItFollowsTheWithdrawing()
	{
		List<String> lines = LoadoutSummary.forItems(Arrays.asList(
			withdraw("Yew sapling", LoadoutItem.Category.SEED, 3, 2)));

		assertEquals("one of three is already in the pack",
			Arrays.asList("From the bank:", "- Yew sapling x2"), lines);
	}

	/**
	 * A unit item still to fetch reads "x1" - reported as a felling axe sitting markless in a
	 * filtered bank, where the number is the only mark a slot gets.
	 */
	@Test
	public void aUnitItemStillToFetchCountsAsOne()
	{
		List<String> lines = LoadoutSummary.forItems(Arrays.asList(
			withdraw("Rune axe", LoadoutItem.Category.TOOL, 0, 0)));

		assertEquals(Arrays.asList("From the bank:", "- Rune axe x1"), lines);
	}

	/** Compost is the one exception: its true bucket count is not computed, so no number. */
	@Test
	public void compostCarriesNoCountRatherThanAWrongOne()
	{
		List<String> lines = LoadoutSummary.forItems(Arrays.asList(
			withdraw("Ultracompost", LoadoutItem.Category.COMPOST, 0, 0)));

		assertEquals(Arrays.asList("From the bank:", "- Ultracompost"), lines);
	}

	/** One sapling for one tree patch still gets its number - see getWithdrawCount. */
	@Test
	public void aCountOfOneIsShownNotSuppressed()
	{
		List<String> lines = LoadoutSummary.forItems(Arrays.asList(
			withdraw("Yew sapling", LoadoutItem.Category.SEED, 1, 1)));

		assertEquals(Arrays.asList("From the bank:", "- Yew sapling x1"), lines);
	}

	/**
	 * Teleports are marked in the bank and nothing more.
	 *
	 * <p>There can be a dozen of them, they are a convenience the player may mean to walk
	 * past, and a list that long buries the seeds and payments the trip cannot start without.
	 */
	@Test
	public void teleportsAreNotListed()
	{
		List<String> lines = LoadoutSummary.forItems(Arrays.asList(
			withdraw("Ectophial", LoadoutItem.Category.TELEPORT, 0, 0),
			withdraw("Yew sapling", LoadoutItem.Category.SEED, 1, 1)));

		assertEquals(Arrays.asList("From the bank:", "- Yew sapling x1"), lines);
	}

	/** The vault is its own errand, under its own heading — there is exactly one vault. */
	@Test
	public void vaultSeedsSitUnderTheirOwnHeading()
	{
		LoadoutItem vaultSeed = new LoadoutItem(1, "Ranarr seed", LoadoutItem.Category.SEED,
			LoadoutItem.Need.WITHDRAW, 6, 6, "for the herb patches",
			LoadoutItem.From.SEED_VAULT);

		List<String> lines = LoadoutSummary.forItems(Arrays.asList(
			withdraw("Cactus spine", LoadoutItem.Category.PAYMENT, 30, 30), vaultSeed));

		assertEquals(Arrays.asList(
			"From the bank:",
			"- Cactus spine x30",
			"From the seed vault:",
			"- Ranarr seed x6"), lines);
	}

	/** A dozen rows fold, so the on-screen panel cannot be outgrown by a big run. */
	@Test
	public void aLongListFoldsItsTail()
	{
		List<LoadoutItem> items = new ArrayList<>();
		for (int i = 0; i < 12; i++)
		{
			items.add(withdraw("Seed " + i, LoadoutItem.Category.SEED, 2, 2));
		}

		List<String> lines = LoadoutSummary.forItems(items);

		assertEquals("heading, ten rows, one fold", 12, lines.size());
		assertEquals("+ 2 more", lines.get(lines.size() - 1));
	}

	/**
	 * A missing item is never given a count.
	 *
	 * <p>Its arithmetic still exists — the run knows it wanted thirty — but "you have none"
	 * followed by "x30" reads as an instruction to take thirty of a thing the run just said it
	 * cannot find. The need gate in {@code getWithdrawCount} is what keeps those apart.
	 */
	@Test
	public void missingItemsAreSkippedInWordsNotCounted()
	{
		LoadoutItem missing = new LoadoutItem(1, "Potato seed", LoadoutItem.Category.SEED,
			LoadoutItem.Need.MISSING, 9, 9, "for the allotments", LoadoutItem.From.BANK);

		List<String> lines = LoadoutSummary.forItems(Arrays.asList(missing));

		assertEquals(Arrays.asList("Skipping potato seed - you have none."), lines);
		assertFalse("no count anywhere for a thing that is not there",
			lines.stream().anyMatch(line -> line.contains("x9")));
	}

	@Test
	public void anUnreadBankIsSaidOnlyWhenThereIsSomethingToWithdraw()
	{
		LoadoutItem unknown = new LoadoutItem(1, "Magic secateurs", LoadoutItem.Category.GEAR,
			LoadoutItem.Need.UNKNOWN, 1, 0, "chance to save", LoadoutItem.From.BANK);

		assertTrue("nothing to withdraw, so nothing worth hedging",
			LoadoutSummary.forItems(Arrays.asList(unknown)).isEmpty());

		List<String> lines = LoadoutSummary.forItems(Arrays.asList(
			withdraw("Yew sapling", LoadoutItem.Category.SEED, 3, 3), unknown));
		assertEquals("Some of your bank has not been read yet.", lines.get(lines.size() - 1));
	}

	private static LoadoutItem withdraw(String name, LoadoutItem.Category category, int quantity,
		int outstanding)
	{
		return new LoadoutItem(1, name, category, LoadoutItem.Need.WITHDRAW, quantity,
			outstanding, "for the run", LoadoutItem.From.BANK);
	}
}
