package com.dooglemaps.state;

import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Seed;
import com.google.gson.Gson;
import java.lang.reflect.Constructor;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Covers the order the seed grid draws seeds in.
 *
 * <p>Ordering used to depend on what you owned and could plant, which meant the grid
 * reshuffled as your stock changed and a seed was never twice in the same place. Level order
 * is fixed, so it can be learned.
 */
public class PlantableResolverTest
{
	private SeedInventoryStore seeds;
	private PlantableResolver resolver;

	@Before
	public void setUp() throws Exception
	{
		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(int.class)))
			.thenReturn(50);

		seeds = construct(SeedInventoryStore.class,
			Mockito.mock(Client.class), configManager, new Gson());
		resolver = construct(PlantableResolver.class, seeds);
	}

	/** Stocks the bank so the resolver has something to list. */
	private void own(Seed... owned)
	{
		Item[] items = new Item[owned.length];
		for (int i = 0; i < owned.length; i++)
		{
			items[i] = new Item(owned[i].getItemID(), 50);
		}
		ItemContainer bank = Mockito.mock(ItemContainer.class);
		when(bank.getItems()).thenReturn(items);
		seeds.record(SeedSource.BANK.getContainerId(), bank);
	}

	/** Stocks the bank with raw item ids, for the seed-versus-sapling distinction. */
	private void ownItems(int... idThenQuantity)
	{
		Item[] items = new Item[idThenQuantity.length / 2];
		for (int i = 0; i < items.length; i++)
		{
			items[i] = new Item(idThenQuantity[i * 2], idThenQuantity[i * 2 + 1]);
		}
		ItemContainer bank = Mockito.mock(ItemContainer.class);
		when(bank.getItems()).thenReturn(items);
		seeds.record(SeedSource.BANK.getContainerId(), bank);
	}

	/**
	 * A tree patch is planted with a sapling, so a bank full of saplings has to show up.
	 *
	 * <p>Reported as "saplings are missing from tree patch seed lists", and that is exactly
	 * what happened: {@code Seed.OAK} is an acorn, nothing knew an oak sapling was the same
	 * crop, and anyone stocked up for a tree run — which means saplings, since the seed will
	 * not go in the ground — saw an empty list.
	 */
	@Test
	public void aSaplingCountsAsHavingTheTree()
	{
		ownItems(Seed.WILLOW.getSaplingItemID(), 3);

		List<PlantableResolver.Plantable> trees =
			resolver.forPatchType(PatchImplementation.TREE, false);

		assertEquals(1, trees.size());
		PlantableResolver.Plantable willow = trees.get(0);
		assertEquals(Seed.WILLOW, willow.getSeed());
		assertEquals(3, willow.getOwned());
		assertTrue("three saplings is three tree patches", willow.isUsable());
	}

	/**
	 * Holding the seed is not the same as being able to plant it.
	 *
	 * <p>An acorn has to spend time in a filled plant pot first. Listing it is right — you do
	 * own the tree — but calling it usable would send someone to a patch with nothing to put
	 * in it.
	 */
	@Test
	public void aTreeSeedIsOwnedButNotYetPlantable()
	{
		ownItems(Seed.OAK.getItemID(), 4);

		PlantableResolver.Plantable oak =
			resolver.forPatchType(PatchImplementation.TREE, false).get(0);

		assertEquals(Seed.OAK, oak.getSeed());
		assertEquals("the acorns are still yours", 4, oak.getOwned());
		assertEquals("but none of them can go in the ground", 0, oak.getPlantable());
		assertFalse(oak.isUsable());
		assertTrue("and the panel should be able to say why", oak.needsPotting());
	}

	/** Seeds and saplings of the same tree are one crop, not two rows. */
	@Test
	public void seedsAndSaplingsOfOneTreeAreCountedTogether()
	{
		// Maple rather than yew: the fixture is level 50, and an unmet level would mask the
		// thing being tested behind a second reason not to be usable.
		ownItems(Seed.MAPLE.getItemID(), 5, Seed.MAPLE.getSaplingItemID(), 2);

		List<PlantableResolver.Plantable> trees =
			resolver.forPatchType(PatchImplementation.TREE, false);

		assertEquals("one row for maple, not one per item", 1, trees.size());
		assertEquals(7, trees.get(0).getOwned());
		assertEquals("only the saplings can be planted", 2, trees.get(0).getPlantable());
		assertTrue(trees.get(0).isUsable());
		assertFalse("there are saplings to plant, so nothing is blocked",
			trees.get(0).needsPotting());
	}

	/** Nothing outside the sapling patch types should have gained a second item id. */
	@Test
	public void onlyTreesArePlantedAsSaplings()
	{
		for (Seed seed : Seed.values())
		{
			boolean expected;
			switch (seed.getPatchType())
			{
				case TREE:
				case FRUIT_TREE:
				case HARDWOOD_TREE:
				case CALQUAT:
				case SPIRIT_TREE:
				case CELASTRUS:
				case REDWOOD:
				case CRYSTAL_TREE:
					expected = true;
					break;
				default:
					expected = false;
			}
			assertEquals(seed + " sapling handling", expected, seed.isSapling());
			assertEquals(seed + " plants the right item",
				expected ? seed.getSaplingItemID() : seed.getItemID(), seed.getPlantedItemID());
		}
	}

	@Test
	public void listsSeedsByFarmingLevelLowestFirst()
	{
		// Deliberately stocked out of order.
		own(Seed.TORSTOL, Seed.GUAM, Seed.RANARR, Seed.MARRENTILL);

		List<PlantableResolver.Plantable> herbs =
			resolver.forPatchType(PatchImplementation.HERB, false);

		assertEquals(4, herbs.size());
		int previous = 0;
		for (PlantableResolver.Plantable plantable : herbs)
		{
			int level = plantable.getSeed().getLevelRequirement();
			assertTrue(plantable.getSeed() + " is out of level order", level >= previous);
			previous = level;
		}
		assertEquals(Seed.GUAM, herbs.get(0).getSeed());
		assertEquals(Seed.TORSTOL, herbs.get(3).getSeed());
	}

	/**
	 * A seed you cannot plant keeps its place rather than being pushed to the end.
	 *
	 * <p>That is the whole point of the change: the row a seed sits in should not depend on
	 * your level or your stock, or it moves under the cursor between refreshes.
	 */
	@Test
	public void unusableSeedsStayInLevelOrder()
	{
		own(Seed.GUAM, Seed.TORSTOL, Seed.RANARR);

		List<PlantableResolver.Plantable> herbs =
			resolver.forPatchType(PatchImplementation.HERB, false);

		// At level 50: guam and ranarr are plantable, torstol (85) is not.
		assertTrue(herbs.get(0).isLevelMet());
		assertTrue(herbs.get(1).isLevelMet());
		assertFalse("torstol needs 85", herbs.get(2).isLevelMet());
		assertEquals("but it still sorts last on level, not on being unusable",
			Seed.TORSTOL, herbs.get(2).getSeed());
	}

	/** Owning more or fewer seeds must not move anything. */
	@Test
	public void theOrderDoesNotChangeWithStock()
	{
		own(Seed.GUAM, Seed.RANARR, Seed.TORSTOL);
		List<Seed> before = order(resolver.forPatchType(PatchImplementation.HERB, false));

		// Spend every ranarr, so it is owned but no longer usable.
		Item[] items = {
			new Item(Seed.GUAM.getItemID(), 50),
			new Item(Seed.RANARR.getItemID(), 1),
			new Item(Seed.TORSTOL.getItemID(), 50),
		};
		ItemContainer bank = Mockito.mock(ItemContainer.class);
		when(bank.getItems()).thenReturn(items);
		seeds.record(SeedSource.BANK.getContainerId(), bank);

		assertEquals("the grid reshuffled when stock changed",
			before, order(resolver.forPatchType(PatchImplementation.HERB, false)));
	}

	private static List<Seed> order(List<PlantableResolver.Plantable> plantables)
	{
		List<Seed> order = new java.util.ArrayList<>();
		plantables.forEach(p -> order.add(p.getSeed()));
		return order;
	}

	@SuppressWarnings("unchecked")
	private static <T> T construct(Class<T> type, Object... args) throws Exception
	{
		for (Constructor<?> candidate : type.getDeclaredConstructors())
		{
			if (candidate.getParameterCount() == args.length)
			{
				candidate.setAccessible(true);
				return (T) candidate.newInstance(args);
			}
		}
		throw new IllegalStateException("no constructor of arity " + args.length + " on " + type);
	}
}
