package com.dooglemaps.guide;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.data.Seed;
import com.dooglemaps.state.BarbarianFarming;
import com.dooglemaps.state.CompostSelectionStore;
import com.dooglemaps.state.LeprechaunStore;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.state.PayToClearStore;
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
import net.runelite.api.gameval.ItemID;
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
 * Paying a gardener to clear a checked, standing tree rather than swinging the axe — the guide
 * half of the pay-to-clear feature.
 *
 * <h2>Why this is a separate branch from the ordinary chop</h2>
 *
 * The owner's own scenario is the reason the crop being cleared can never be read off {@code
 * chosen}: "I want to clear a magic tree myself even if I'm planting a maple in it next." A
 * checked, standing magic tree is {@code HARVESTABLE}, so it sits in a seed allocation's plantable
 * list exactly like any other patch, and the run may already have picked a maple for the same
 * spot once the magic comes out — asking {@code chosen} would read the maple's own checkbox to
 * decide the magic tree's fate. {@link GuidePlan#forPatch} instead resolves the standing crop's
 * own answer from {@link PayToClearStore#isPayingFor(PatchProjection)}, which is keyed on {@code
 * projection.getProduce()} and never on the allocation.
 *
 * <p>Harness copied from {@code GuidePlanTest}, which owns the canonical version of this setup;
 * see also {@code TreeStumpTest} for where the magic tree's 60/61/62 varbit values come from.
 */
public class PayToClearGuidePlanTest
{
	private static final com.dooglemaps.data.ItemNames NAMES =
		com.dooglemaps.Construct.construct(com.dooglemaps.data.ItemNames.class);

	private PatchStateStore patches;
	private SeedInventoryStore seeds;
	private CompostSelectionStore compost;
	private GrowthTimer growthTimer;
	private CarriedItems carried;
	private LeprechaunStore leprechaun;
	private BarbarianFarming barbarian;
	private PayToClearStore payToClear;

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
		when(configManager.getRSProfileConfiguration(anyString(), anyString()))
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
		payToClear = construct(PayToClearStore.class, configManager, gson);
		payToClear.load();
	}

	/**
	 * Checked but not yet chopped is a health check, whatever the player's coins or the
	 * pay-to-clear selection say. Paying to clear is only ever offered to a tree the game will
	 * already let you chop, and the check has to happen first regardless — the branch's own
	 * ordering makes it impossible to reach this early, but this pins the observable behaviour
	 * rather than the ordering.
	 */
	@Test
	public void payToClearWaitsForTheHealthCheck()
	{
		FarmPatch patch = magicTreePatch(60);
		payToClear.setPayingFor(Seed.MAGIC, true);
		carrying(ItemID.COINS, 200);

		GuideStep step = firstStep(patch);
		assertEquals(GuideAction.CHECK_HEALTH, step.getAction());
	}

	/**
	 * The case the owner asked for by name: a checked magic tree, toggled to be paid for, with a
	 * <i>maple</i> chosen as the replacement seed. The payment step must still appear — it is
	 * keyed on the magic standing there, not on the maple about to go in.
	 */
	@Test
	public void payToClearIsKeyedOnTheStandingCropNotTheChosenSeed()
	{
		FarmPatch patch = magicTreePatch(61);
		payToClear.setPayingFor(Seed.MAGIC, true);
		carrying(ItemID.COINS, 200);

		List<GuideStep> steps = steps(patch, Seed.MAPLE);
		assertFalse(steps.isEmpty());
		assertEquals(GuideAction.PAY_TO_CLEAR, steps.get(0).getAction());
		assertEquals("the coins are what light up", ItemID.COINS, steps.get(0).getItemId());
		assertEquals("the gardener beside the patch is the target",
			patch.getFarmer(), steps.get(0).getNpcId());
	}

	/** With only the maple toggled — not the magic actually standing there — it is a plain chop. */
	@Test
	public void onlyTheReplacementSeedToggledStillChops()
	{
		FarmPatch patch = magicTreePatch(61);
		payToClear.setPayingFor(Seed.MAPLE, true);
		carrying(ItemID.COINS, 200);

		GuideStep step = firstStep(patch, Seed.MAPLE);
		assertEquals(GuideAction.CHOP, step.getAction());
	}

	/**
	 * Short of the price, the guide falls back to the axe rather than stall on an instruction
	 * the player cannot follow. The coins row in the loadout is what makes this non-blocking.
	 */
	@Test
	public void payToClearFallsBackToTheAxeWithoutEnoughCoins()
	{
		FarmPatch patch = magicTreePatch(61);
		payToClear.setPayingFor(Seed.MAGIC, true);

		carrying();
		assertEquals("nothing carried at all", GuideAction.CHOP, firstStep(patch).getAction());

		carrying(ItemID.COINS, 199);
		assertEquals("one short of the price", GuideAction.CHOP, firstStep(patch).getAction());

		carrying(ItemID.COINS, 200);
		assertEquals("exactly the price is enough",
			GuideAction.PAY_TO_CLEAR, firstStep(patch).getAction());
	}

	/**
	 * A harvest-only run never pays to clear — nothing here is being replanted, so there is
	 * nothing to buy the clearing for. The tree's logs are still worth taking (the ordinary
	 * "come back for the logs" harvest, {@code hasProduceToPick}), just never by paying a
	 * gardener to fell it.
	 */
	@Test
	public void payToClearIsSkippedOnAHarvestOnlyRun()
	{
		FarmPatch patch = magicTreePatch(61);
		payToClear.setPayingFor(Seed.MAGIC, true);
		carrying(ItemID.COINS, 200);

		PatchProjection projection = growthTimer.project(patch, patches.get(patch));
		assertNotNull(projection);
		List<GuideStep> steps = GuidePlan.forPatch(projection, patches.get(patch).getCompost(),
			group(patch), null, seeds, compost, payToClear, carried, leprechaun, barbarian,
			false, false, /* harvestOnly */ true, 1, false, NAMES);
		assertTrue("no pay-to-clear step on a harvest-only run", steps.stream()
			.noneMatch(step -> step.getAction() == GuideAction.PAY_TO_CLEAR));
	}

	/** A felled stump is still the spade's job — paying a gardener to fell it makes no sense. */
	@Test
	public void payToClearLeavesAStumpToTheSpade()
	{
		FarmPatch patch = magicTreePatch(62);
		payToClear.setPayingFor(Seed.MAGIC, true);
		carrying(ItemID.COINS, 200, com.dooglemaps.data.FarmingTool.SPADE.getItemID(), 1);

		GuideStep step = firstStep(patch);
		assertEquals(GuideAction.CLEAR, step.getAction());
		assertTrue(step.getText(), step.getText().toLowerCase().contains("stump"));
	}

	/** A fruit tree still laden is harvested first — paying to clear waits for it to be stripped. */
	@Test
	public void aLadenFruitTreeIsHarvestedBeforePayingToClear()
	{
		FarmPatch patch = fruitTreePatch(212);   // palm, checked, six coconuts on it
		payToClear.setPayingFor(Seed.PALM, true);
		carrying(ItemID.COINS, 200);
		stockInventory(Seed.PALM, 1);

		GuideStep step = firstStep(patch, Seed.PALM);
		assertEquals(GuideAction.HARVEST, step.getAction());
	}

	/** Picked clean, the same tree becomes payable — the coins clear it instead of the axe. */
	@Test
	public void aPickedCleanFruitTreeIsPaidToClear()
	{
		FarmPatch patch = fruitTreePatch(206);   // palm, checked, no coconuts left
		payToClear.setPayingFor(Seed.PALM, true);
		carrying(ItemID.COINS, 200);
		stockInventory(Seed.PALM, 1);

		GuideStep step = firstStep(patch, Seed.PALM);
		assertEquals(GuideAction.PAY_TO_CLEAR, step.getAction());
	}

	/**
	 * The dead redwood, retagged from {@code CLEAR} to {@code PAY_TO_CLEAR} — unconditional,
	 * because Alexandra's coins are the only route a redwood has at all, never merely the
	 * player's preferred one.
	 */
	@Test
	public void aDeadRedwoodIsPaidToClearAtAlexandra()
	{
		FarmPatch patch = deadPatch(PatchImplementation.REDWOOD);
		assertNotNull("no redwood patch has a dead fixture in the table", patch);

		GuideStep step = firstStep(patch);
		assertEquals(GuideAction.PAY_TO_CLEAR, step.getAction());
		assertEquals(ItemID.COINS, step.getItemId());
		assertEquals(patch.getFarmer(), step.getNpcId());
		assertTrue(step.getText(), step.getText().contains("2,000"));
	}

	// ------------------------------------------------------------------ helpers

	/** Any magic tree patch, put into the state the given varbit value decodes to. */
	private FarmPatch magicTreePatch(int varbitValue)
	{
		FarmPatch patch = FarmingWorldData.getPatches(PatchImplementation.TREE).get(0);
		assertNotNull("no tree patch in the generated world data", patch);
		ProduceState decoded = patch.getImplementation().forVarbitValue(varbitValue);
		assertNotNull("varbit " + varbitValue + " does not decode", decoded);
		assertEquals("fixture expects this value to be the magic tree",
			com.dooglemaps.data.Produce.MAGIC, decoded.getProduce());
		patches.recordVarbit(patch, varbitValue, decoded);
		return patch;
	}

	/** Any fruit tree patch, put into the state the given varbit value decodes to. */
	private FarmPatch fruitTreePatch(int varbitValue)
	{
		FarmPatch patch = FarmingWorldData.getPatches(PatchImplementation.FRUIT_TREE).get(0);
		assertNotNull("no fruit tree patch in the generated world data", patch);
		ProduceState decoded = patch.getImplementation().forVarbitValue(varbitValue);
		assertNotNull("varbit " + varbitValue + " does not decode", decoded);
		patches.recordVarbit(patch, varbitValue, decoded);
		return patch;
	}

	/** A patch of this type in a dead state, found by scanning the varbit table. */
	private FarmPatch deadPatch(PatchImplementation type)
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(type))
		{
			for (int value = 0; value < 256; value++)
			{
				ProduceState decoded = patch.getImplementation().forVarbitValue(value);
				if (decoded == null
					|| decoded.getCropState() != com.dooglemaps.data.CropState.DEAD)
				{
					continue;
				}
				patches.recordVarbit(patch, value, decoded);
				return patch;
			}
		}
		return null;
	}

	private static com.dooglemaps.data.PlantingGroup group(FarmPatch patch)
	{
		return com.dooglemaps.data.PlantingGroup.of(patch.getImplementation());
	}

	private List<GuideStep> steps(FarmPatch patch, Seed chosen)
	{
		PatchProjection projection = growthTimer.project(patch, patches.get(patch));
		assertNotNull("fixture patch has no projection", projection);
		return GuidePlan.forPatch(projection, patches.get(patch).getCompost(), group(patch),
			chosen, seeds, compost, payToClear, carried, leprechaun, barbarian,
			false, false, false, 1, false, NAMES);
	}

	private GuideStep firstStep(FarmPatch patch, Seed chosen)
	{
		List<GuideStep> steps = steps(patch, chosen);
		assertTrue("expected an instruction, got none", !steps.isEmpty());
		return steps.get(0);
	}

	private GuideStep firstStep(FarmPatch patch)
	{
		return firstStep(patch, null);
	}

	private void stockInventory(Seed seed, int quantity)
	{
		seeds.record(com.dooglemaps.state.SeedSource.INVENTORY.getContainerId(),
			containerOf(seed.getPlantedItemID(), quantity));
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
