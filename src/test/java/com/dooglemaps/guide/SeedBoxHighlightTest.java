package com.dooglemaps.guide;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.Seed;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.state.SeedSource;
import com.google.gson.Gson;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Pins the gate on the seed box highlight during a plant step.
 *
 * <p>Reported from play as "not showing at all, ever", which is exactly the report a silently
 * false condition produces — so the condition's three inputs (the step's seed resolves, the
 * box holds plantable stock of it, the pack holds less than a patch's worth) each get pinned
 * here, against a real store fed through the same container path the client uses.
 */
public class SeedBoxHighlightTest
{
	private SeedInventoryStore seeds;
	private CarriedItems carried;
	private GuideInventoryOverlay overlay;

	@Before
	public void setUp() throws Exception
	{
		seeds = construct(SeedInventoryStore.class,
			Mockito.mock(Client.class), Mockito.mock(ConfigManager.class), new Gson());
		carried = construct(CarriedItems.class, Mockito.mock(Client.class));
		overlay = overlayWith(seeds, carried);
	}

	@Test
	public void boxedSeedsAndAShortPackLightTheBox() throws Exception
	{
		box(Seed.SNAPE_GRASS, 12);

		assertTrue("the planting's seeds are in the box and the pack is short",
			boxHoldsNeededSeeds(Seed.SNAPE_GRASS.getPlantedItemID()));
	}

	@Test
	public void aPackAlreadyHoldingAPatchsWorthNeedsNoBoxTrip() throws Exception
	{
		box(Seed.SNAPE_GRASS, 12);
		carrying(Seed.SNAPE_GRASS.getPlantedItemID(), Seed.SNAPE_GRASS.getSeedsPerPatch());

		assertFalse("everything the patch needs is already loose",
			boxHoldsNeededSeeds(Seed.SNAPE_GRASS.getPlantedItemID()));
	}

	@Test
	public void anEmptyBoxLightsNothing() throws Exception
	{
		assertFalse(boxHoldsNeededSeeds(Seed.SNAPE_GRASS.getPlantedItemID()));
	}

	@Test
	public void aNonSeedItemLightsNothing() throws Exception
	{
		box(Seed.SNAPE_GRASS, 12);

		assertFalse("a plant step for something the seed table cannot name stays dark",
			boxHoldsNeededSeeds(net.runelite.api.gameval.ItemID.BUCKET_EMPTY));
	}

	// ------------------------------------------------------------------- helpers

	private boolean boxHoldsNeededSeeds(int plantedItemId) throws Exception
	{
		Method method = GuideInventoryOverlay.class.getDeclaredMethod(
			"boxHoldsNeededSeeds", int.class);
		method.setAccessible(true);
		try
		{
			return (Boolean) method.invoke(overlay, plantedItemId);
		}
		catch (java.lang.reflect.InvocationTargetException e)
		{
			throw new AssertionError(e.getCause());
		}
	}

	private void box(Seed seed, int quantity)
	{
		seeds.record(SeedSource.SEED_BOX.getContainerId(),
			containerOf(seed.getPlantedItemID(), quantity));
	}

	private void carrying(int... idThenQuantity)
	{
		ItemContainer container = containerOf(idThenQuantity);
		carried.record(container);
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

	private static GuideInventoryOverlay overlayWith(SeedInventoryStore seeds,
		CarriedItems carried) throws Exception
	{
		Constructor<?> constructor = GuideInventoryOverlay.class.getDeclaredConstructors()[0];
		constructor.setAccessible(true);

		Class<?>[] types = constructor.getParameterTypes();
		Object[] args = new Object[types.length];
		for (int i = 0; i < types.length; i++)
		{
			if (types[i] == SeedInventoryStore.class)
			{
				args[i] = seeds;
			}
			else if (types[i] == CarriedItems.class)
			{
				args[i] = carried;
			}
			else if (types[i] == DoogleMapsConfig.class)
			{
				args[i] = Mockito.mock(DoogleMapsConfig.class);
			}
			else
			{
				args[i] = Mockito.mock(types[i]);
			}
		}
		return (GuideInventoryOverlay) constructor.newInstance(args);
	}
}
