package com.dooglemaps.guide;

import com.dooglemaps.data.Seed;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.state.SeedSource;
import com.google.gson.Gson;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Covers which of the seed box's two options ends up under the left click.
 *
 * <p>The rule replaced a step-driven swap, and the point of the replacement is that it is a
 * function of the box and the pack alone — no run state, no current step, nothing that has to
 * be in the right phase for the click to be the one wanted. So these tests set up a box and a
 * pack and ask, which is exactly what the swap does on every menu sort.
 */
public class GuideMenuSwapTest
{
	private SeedInventoryStore seeds;

	@Before
	public void setUp()
	{
		seeds = construct(SeedInventoryStore.class,
			Mockito.mock(net.runelite.api.Client.class),
			Mockito.mock(ConfigManager.class), new Gson());
	}

	/** Nothing in the box, so there is nothing to tip out. */
	@Test
	public void anEmptyBoxFills()
	{
		assertEquals("Fill", GuideMenuSwap.wantedBoxOption(seeds));
	}

	/** Empty box, loose seeds — the same answer, and the useful one. */
	@Test
	public void anEmptyBoxWithLooseSeedsFills()
	{
		stock(SeedSource.INVENTORY, Seed.POTATO, 30);

		assertEquals("Fill", GuideMenuSwap.wantedBoxOption(seeds));
	}

	/** Seeds in the box and nothing loose to add: the only move left is to tip it out. */
	@Test
	public void aStockedBoxWithAnEmptyPackEmpties()
	{
		stock(SeedSource.SEED_BOX, Seed.POTATO, 30);

		assertEquals("Empty", GuideMenuSwap.wantedBoxOption(seeds));
	}

	/** Part-full box, loose seeds in the pack — pocket them first. */
	@Test
	public void aStockedBoxWithLooseSeedsFills()
	{
		stock(SeedSource.SEED_BOX, Seed.POTATO, 30);
		stock(SeedSource.INVENTORY, Seed.ONION, 10);

		assertEquals("Fill", GuideMenuSwap.wantedBoxOption(seeds));
	}

	/**
	 * A full box refuses a seventh kind, so Fill would do nothing and Empty is what is wanted.
	 *
	 * <p>Six kinds is the box's real limit — one stack each — and it is the case the plain
	 * "any loose seeds?" test gets wrong: there are loose seeds, and none of them can go in.
	 */
	@Test
	public void aFullBoxEmptiesEvenWithLooseSeeds()
	{
		stock(SeedSource.SEED_BOX, Seed.POTATO, 10);
		stock(SeedSource.SEED_BOX, Seed.ONION, 10);
		stock(SeedSource.SEED_BOX, Seed.CABBAGE, 10);
		stock(SeedSource.SEED_BOX, Seed.TOMATO, 10);
		stock(SeedSource.SEED_BOX, Seed.SWEETCORN, 10);
		stock(SeedSource.SEED_BOX, Seed.STRAWBERRY, 10);
		stock(SeedSource.INVENTORY, Seed.WATERMELON, 5);

		assertEquals("Empty", GuideMenuSwap.wantedBoxOption(seeds));
	}

	/** A full box still takes more of a kind it already holds, so that one fills. */
	@Test
	public void aFullBoxStillFillsAKindItAlreadyHolds()
	{
		stock(SeedSource.SEED_BOX, Seed.POTATO, 10);
		stock(SeedSource.SEED_BOX, Seed.ONION, 10);
		stock(SeedSource.SEED_BOX, Seed.CABBAGE, 10);
		stock(SeedSource.SEED_BOX, Seed.TOMATO, 10);
		stock(SeedSource.SEED_BOX, Seed.SWEETCORN, 10);
		stock(SeedSource.SEED_BOX, Seed.STRAWBERRY, 10);
		stock(SeedSource.INVENTORY, Seed.POTATO, 5);

		assertEquals("Fill", GuideMenuSwap.wantedBoxOption(seeds));
	}

	/**
	 * Saplings are not seeds as far as the box is concerned.
	 *
	 * <p>The box will not take one, so a pack holding nothing else must not read as "there is
	 * something to fill with" and leave Fill under a click that would do nothing.
	 */
	@Test
	public void aPackOfSaplingsDoesNotCountAsSomethingToFillWith()
	{
		stock(SeedSource.SEED_BOX, Seed.POTATO, 30);
		stock(SeedSource.INVENTORY, Seed.OAK, 1);   // a sapling, and the box will not take it

		assertEquals("Empty", GuideMenuSwap.wantedBoxOption(seeds));
	}

	private void stock(SeedSource source, Seed seed, int quantity)
	{
		// Recorded cumulatively: a container event is the whole container, so each call has to
		// carry everything already put in that source or the earlier stock is forgotten.
		java.util.List<Integer> items = new java.util.ArrayList<>();
		for (Seed known : Seed.values())
		{
			int held = seeds.getCount(known, source);
			if (held > 0)
			{
				items.add(known.getPlantedItemID());
				items.add(held);
			}
		}
		items.add(seed.getPlantedItemID());
		items.add(quantity);

		int[] flat = new int[items.size()];
		for (int i = 0; i < flat.length; i++)
		{
			flat[i] = items.get(i);
		}
		seeds.record(source.getContainerId(), containerOf(flat));
	}

	private static ItemContainer containerOf(int... idThenQuantity)
	{
		Item[] items = new Item[idThenQuantity.length / 2];
		for (int i = 0; i < items.length; i++)
		{
			items[i] = new Item(idThenQuantity[i * 2], idThenQuantity[i * 2 + 1]);
		}
		ItemContainer container = Mockito.mock(ItemContainer.class);
		when(container.getItems()).thenReturn(items);
		return container;
	}
}
