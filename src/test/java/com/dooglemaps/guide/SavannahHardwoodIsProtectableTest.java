package com.dooglemaps.guide;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.data.ProtectionPayment;
import com.dooglemaps.state.BarbarianFarming;
import com.dooglemaps.state.CompostSelectionStore;
import com.dooglemaps.state.LeprechaunStore;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.timer.GrowthTimer;
import com.dooglemaps.timer.PatchProjection;
import com.google.gson.Gson;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The Avium Savannah hardwood patch, Marcellus's, has to be protectable exactly like every other
 * hardwood tree, and paying him has to be reachable from a step.
 *
 * <p>Its farmer, {@code NpcID.FROG_QUEST_MARCELLUS}, is a quest NPC first and a gardener second —
 * the same shape as the coral farmer, whose runtime chathead can be a variant the world data does
 * not carry. Since {@code FarmerVariants} now groups his three ids ({@code FROG_QUEST_MARCELLUS},
 * {@code _FARMER}, {@code _NORMAL}), the patch has to actually behave like a protectable hardwood
 * tree rather than silently falling through as "no farmer" the way an ungrouped quest NPC would.
 *
 * <p>Harness copied from {@code GuidePlanTest}, which owns the canonical version of this setup.
 */
public class SavannahHardwoodIsProtectableTest
{
	private PatchStateStore patches;
	private SeedInventoryStore seeds;
	private CompostSelectionStore compost;
	private GrowthTimer growthTimer;
	private CarriedItems carried;
	private LeprechaunStore leprechaun;
	private BarbarianFarming barbarian;

	private static final com.dooglemaps.data.ItemNames NAMES =
		com.dooglemaps.Construct.construct(com.dooglemaps.data.ItemNames.class);

	@Before
	public void setUp() throws Exception
	{
		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(int.class)))
			.thenReturn(85);

		java.util.Map<String, Object> profile = new java.util.HashMap<>();
		Mockito.doAnswer(i -> profile.put(i.getArgument(0) + "." + i.getArgument(1), i.getArgument(2)))
			.when(configManager).setRSProfileConfiguration(anyString(), anyString(), Mockito.any());
		when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(boolean.class)))
			.thenAnswer(i -> profile.get(i.getArgument(0) + "." + i.getArgument(1)));

		Gson gson = new Gson();
		patches = construct(PatchStateStore.class, configManager, gson);
		patches.load();
		seeds = construct(SeedInventoryStore.class, Mockito.mock(Client.class), configManager, gson);
		compost = construct(CompostSelectionStore.class, configManager, gson);
		compost.load();
		growthTimer = construct(GrowthTimer.class, configManager);
		carried = construct(CarriedItems.class, Mockito.mock(Client.class));
		leprechaun = leprechaunHolding();
		barbarian = construct(BarbarianFarming.class, configManager,
			Mockito.mock(com.dooglemaps.DoogleMapsConfig.class));
	}

	private static FarmPatch savannahPatch()
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.HARDWOOD_TREE))
		{
			if ("Avium Savannah".equals(patch.getRegion().getName()))
			{
				return patch;
			}
		}
		throw new AssertionError("no Avium Savannah hardwood patch in the world data");
	}

	/** The patch exists, is hardwood, and names Marcellus as its farmer. */
	@Test
	public void thePatchExistsAndIsProtectable()
	{
		FarmPatch patch = savannahPatch();
		assertEquals(NpcID.FROG_QUEST_MARCELLUS, patch.getFarmer());
		assertTrue("a patch with a farmer id must be protectable", patch.isProtectable());
	}

	/** Its produce's protection payment is the camphor payment: 10 white berries. */
	@Test
	public void itsPaymentIsTenWhiteBerries()
	{
		FarmPatch patch = savannahPatch();
		ProtectionPayment payment = ProtectionPayment.forProduce(
			com.dooglemaps.data.Produce.CAMPHOR);
		assertNotNull("camphor has no protection payment on record", payment);
		assertEquals(net.runelite.api.gameval.ItemID.WHITE_BERRIES, payment.getItemID());
		assertEquals(10, payment.getQuantity());
	}

	/** Protecting a growing sapling with the payment carried asks to pay Marcellus. */
	@Test
	public void protectingItAsksToPayMarcellus() throws Exception
	{
		FarmPatch patch = growingAt(savannahPatch());
		assertNotNull("no growing fixture found for the Savannah hardwood patch", patch);

		PatchProjection projection = growthTimer.project(patch, patches.get(patch));
		assertNotNull(projection);
		ProtectionPayment payment = ProtectionPayment.forProduce(projection.getProduce());
		assertNotNull("a hardwood crop has a protection payment", payment);
		carrying(payment.getItemID(), payment.getQuantity());

		List<GuideStep> steps = GuidePlan.forPatch(projection, patches.get(patch).getCompost(),
			com.dooglemaps.data.PlantingGroup.of(patch.getImplementation()), null, seeds, compost,
			carried, leprechaun, barbarian,
			/* protecting */ true, /* paidToProtect */ true, /* harvestOnly */ false, 1, false,
			NAMES);

		assertFalse("the payment is the one thing this patch still wants", steps.isEmpty());
		assertEquals(GuideAction.PAY_FARMER, steps.get(0).getAction());
		assertEquals("the gardener highlighted is Marcellus's own id",
			patch.getFarmer(), steps.get(0).getNpcId());
	}

	/** A patch of this type part-way through growing something — not ready, not empty. */
	private FarmPatch growingAt(FarmPatch patch)
	{
		for (int value = 0; value < 256; value++)
		{
			ProduceState decoded = patch.getImplementation().forVarbitValue(value);
			if (decoded == null || decoded.getProduce() == null
				|| !decoded.getProduce().isCrop()
				|| decoded.getCropState() != com.dooglemaps.data.CropState.GROWING
				|| decoded.getStage() != 0)
			{
				continue;
			}

			patches.recordVarbit(patch, value, decoded);
			PatchProjection projection = growthTimer.project(patch, patches.get(patch));
			if (projection != null && !projection.isEmpty() && !projection.isReady())
			{
				return patch;
			}
		}
		return null;
	}

	private void carrying(int... idThenQuantity)
	{
		carried.record(containerOf(idThenQuantity));
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

	private LeprechaunStore leprechaunHolding() throws Exception
	{
		Client client = Mockito.mock(Client.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		LeprechaunStore store = construct(LeprechaunStore.class, client);
		store.onGameTick(new GameTick());
		return store;
	}
}
