package com.dooglemaps.route;

import com.dooglemaps.bank.BankContents;
import com.dooglemaps.bank.LoadoutItem;
import com.dooglemaps.bank.RunLoadout;
import com.dooglemaps.bank.ToolNeeds;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PayToClear;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.data.Seed;
import com.dooglemaps.guide.CarriedItems;
import com.dooglemaps.guide.GuideAction;
import com.dooglemaps.guide.GuidePlan;
import com.dooglemaps.guide.GuideStep;
import com.dooglemaps.state.AvailabilityProfile;
import com.dooglemaps.state.CompostSelectionStore;
import com.dooglemaps.state.LeprechaunStore;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.state.PayToClearStore;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.state.SeedSelectionStore;
import com.dooglemaps.timer.GrowthTimer;
import com.dooglemaps.timer.PatchProjection;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Pins the loadout's clearing coins to the patches the guide will actually spend them at.
 *
 * <h2>The promise being kept</h2>
 *
 * The bank list promises coins for every patch the guide will ask the player to pay at, and
 * promises them for nothing else. Two different predicates make that one promise:
 * {@code RunLoadout.addClearingFees} counts {@code PayToClear.isClearable} standing crops off
 * {@code RunPlanner.clearableIn} at the bank, hours before the run; {@code GuidePlan}'s own 0.65
 * branch asks {@code projection.isChoppable()} at the patch. If the two drift apart the player is
 * left in one of exactly two bad places — short at the gardener with an axe they were told they
 * would not need, or carrying a cash stack across a whole run for nothing.
 *
 * <p>The one asymmetry is deliberate and is asserted as such: the loadout is the <b>wider</b>
 * side. It counts a grown-but-unchecked tree (the check has not happened yet, but it will have by
 * the time the run arrives) and a fruit tree still carrying fruit (the guide picks first and pays
 * after), so what it banks is a superset of what the guide can ask for at this instant and equal
 * to what the guide asks for once the run has done its checking and picking. Over-counting costs
 * an inventory slot; under-counting kills the feature at the patch, which is why the inequality
 * runs in this direction and never the other.
 *
 * <p>Modelled on {@code AllocationAgreementTest}, which does the same job for seeds and payments:
 * it compares the two sides' answers rather than their code, so a change to either that does not
 * change the other fails here rather than in play.
 */
public class PayToClearAgreementTest
{
	private static final com.dooglemaps.data.ItemNames GUIDE_NAMES =
		construct(com.dooglemaps.data.ItemNames.class);

	private static final Set<PatchImplementation> TYPES = EnumSet.of(
		PatchImplementation.TREE, PatchImplementation.FRUIT_TREE, PatchImplementation.REDWOOD);

	private final java.util.Map<String, Object> stored = new java.util.HashMap<>();

	private PatchStateStore patches;
	private AvailabilityProfile availability;
	private GrowthTimer growthTimer;
	private SeedInventoryStore seeds;
	private CompostSelectionStore compost;
	private CarriedItems carried;
	private LeprechaunStore leprechaun;
	private com.dooglemaps.state.BarbarianFarming barbarian;
	private PayToClearStore payToClear;
	private SeedSelectionStore selection;
	private RunLoadout loadout;
	private com.dooglemaps.state.PlantingGroups groups;
	private com.dooglemaps.state.RunTypeStore plannerRunOptions;

	/** The four patches this run's coins are banked for, named by hand rather than derived. */
	private FarmPatch paidMagic;
	private FarmPatch uncheckedMagic;
	private FarmPatch ladenPapaya;
	private FarmPatch deadRedwood;

	/** The two the coins must never cover, for the same hand-written reason. */
	private FarmPatch untoggledYew;
	private FarmPatch magicStump;

	@Before
	public void setUp() throws Exception
	{
		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(int.class)))
			.thenAnswer(i -> stored.get(i.getArgument(0) + "." + i.getArgument(1)));
		stored.put("dooglemaps.farmingLevel", 99);

		Gson gson = new Gson();
		patches = construct(PatchStateStore.class, configManager, gson);
		patches.load();
		availability = construct(AvailabilityProfile.class, configManager, gson, patches);
		availability.load();
		growthTimer = construct(GrowthTimer.class, configManager);

		seeds = construct(SeedInventoryStore.class, Mockito.mock(Client.class), configManager, gson);
		selection = construct(SeedSelectionStore.class, configManager, gson,
			construct(com.dooglemaps.state.ContractState.class, configManager));
		compost = construct(CompostSelectionStore.class, configManager, gson);
		compost.load();
		carried = construct(CarriedItems.class, Mockito.mock(Client.class));
		BankContents bank = construct(BankContents.class, configManager, gson);
		payToClear = construct(PayToClearStore.class, configManager, gson);
		barbarian = construct(com.dooglemaps.state.BarbarianFarming.class, configManager,
			Mockito.mock(com.dooglemaps.DoogleMapsConfig.class));

		com.dooglemaps.state.ProtectionSelectionStore protection =
			construct(com.dooglemaps.state.ProtectionSelectionStore.class, configManager, gson);
		com.dooglemaps.state.CompostRunStore compostRun =
			Mockito.mock(com.dooglemaps.state.CompostRunStore.class);

		RunPlanner planner = construct(RunPlanner.class, availability,
			construct(PatchLocationStore.class, configManager, gson),
			construct(BankLocationStore.class, configManager, gson),
			selection, seeds, patches, growthTimer,
			construct(ShortestPathIntegration.class, Mockito.mock(EventBus.class),
				Mockito.mock(net.runelite.client.callback.ClientThread.class)),
			construct(com.dooglemaps.state.PlayerLocation.class, Mockito.mock(Client.class)),
			Mockito.mock(ToolNeeds.class),
			Mockito.mock(com.dooglemaps.state.ProtectedPatches.class),
			groups = Mockito.mock(com.dooglemaps.state.PlantingGroups.class),
			protection,
			plannerRunOptions = Mockito.mock(com.dooglemaps.state.RunTypeStore.class),
			compostRun,
			Mockito.mock(com.dooglemaps.DoogleMapsConfig.class),
			bank, carried);

		Client leprechaunClient = Mockito.mock(Client.class);
		when(leprechaunClient.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
		leprechaun = construct(LeprechaunStore.class, leprechaunClient);
		leprechaun.onGameTick(new net.runelite.api.events.GameTick());

		ToolNeeds toolNeeds = construct(ToolNeeds.class, leprechaun, carried, bank, selection,
			growthTimer, barbarian, availability, patches);

		com.dooglemaps.DoogleMapsConfig config =
			Mockito.mock(com.dooglemaps.DoogleMapsConfig.class);
		when(config.teleportItems()).thenReturn("");

		com.dooglemaps.data.ItemNames itemNames =
			Mockito.mock(com.dooglemaps.data.ItemNames.class);
		when(itemNames.get(Mockito.anyInt(), Mockito.any())).thenAnswer(i -> i.getArgument(1));

		int[] tick = {0};
		Client loadoutClient = Mockito.mock(Client.class);
		when(loadoutClient.getTickCount()).thenAnswer(i -> tick[0]++);

		loadout = construct(RunLoadout.class, planner, selection, seeds, compost, carried, bank,
			toolNeeds, leprechaun, protection, payToClear, itemNames, config, loadoutClient,
			Mockito.mock(com.dooglemaps.state.RunTypeStore.class),
			Mockito.mock(com.dooglemaps.state.ContractState.class), compostRun,
			construct(com.dooglemaps.bank.BoatHolds.class, configManager, gson));

		buildTheWorld();
	}

	/**
	 * Six patches in six states, only four of which anyone should ever pay for.
	 *
	 * <p>Everything is derived from the varbit tables rather than spelled out, so a table that
	 * moves underneath this test fails the fixture assertions rather than quietly testing
	 * something else. See {@code TreeStumpTest} for the shape the tree values are read off.
	 */
	private void buildTheWorld() throws Exception
	{
		List<FarmPatch> trees = withAGardener(PatchImplementation.TREE);
		assertTrue("fixture: four gardener-tended tree patches are needed", trees.size() >= 4);

		int magicStumpValue = stumpValueOf(Produce.MAGIC);
		paidMagic = record(trees.get(0), magicStumpValue - 1);          // checked, still standing
		uncheckedMagic = record(trees.get(1), magicStumpValue - 2);     // grown, not yet checked
		magicStump = record(trees.get(2), magicStumpValue);             // already felled
		untoggledYew = record(trees.get(3), stumpValueOf(Produce.YEW) - 1);

		ladenPapaya = record(withAGardener(PatchImplementation.FRUIT_TREE).get(0),
			fruitValueOf(Produce.PAPAYA, true));
		deadRedwood = record(withAGardener(PatchImplementation.REDWOOD).get(0),
			deadValueOf(PatchImplementation.REDWOOD));

		assertEquals("fixture: the standing magic must be choppable, or the guide never asks",
			true, projectionOf(paidMagic).isChoppable());
		assertEquals("fixture: the second magic must be waiting for its health check",
			true, projectionOf(uncheckedMagic).needsHealthCheck());
		assertEquals("fixture: the papaya must still be carrying fruit",
			true, projectionOf(ladenPapaya).hasProduceToPick());
		assertEquals("fixture: the stump must read as a stump",
			true, projectionOf(magicStump).isStump());
		assertEquals("fixture: the redwood must be dead",
			CropState.DEAD, projectionOf(deadRedwood).getCropState());

		// Magic and papaya bought out; the yew chopped by hand, which is the whole point of the
		// toggle being per crop rather than per patch type.
		payToClear.setPayingFor(Seed.MAGIC, true);
		payToClear.setPayingFor(Seed.PAPAYA, true);

		// A papaya sapling picked and at hand, which both sides need for their own reason: the
		// guide will not offer to fell a fruit tree with nothing to put back (see the 0.7 chop
		// branch's extra condition, which 0.65 copies), and the planner stops routing to a
		// picked-clean fruit tree at all unless the run means to replant it - see
		// RunPlanner.wantsReplantClear. Without it the second phase below would be comparing the
		// two sides over a patch the run would never visit.
		selection.toggle(PlantingGroup.of(PatchImplementation.FRUIT_TREE), Seed.PAPAYA);
		seeds.record(com.dooglemaps.state.SeedSource.INVENTORY.getContainerId(),
			containerOf(Seed.PAPAYA.getPlantedItemID(), 1));

		// Enough to pay at every one of them, so nothing the guide declines to say is explained
		// by an empty pack rather than by the predicate under test.
		carrying(ItemID.COINS, 50_000, Seed.PAPAYA.getPlantedItemID(), 1);
	}

	/**
	 * The bank list's total is exactly the four patches, and every patch the guide asks about
	 * right now is one of them.
	 */
	@Test
	public void theCoinsBankedCoverEveryPatchTheGuideAsksToPayAt()
	{
		int expected = PayToClear.cost(PatchImplementation.TREE) * 2
			+ PayToClear.cost(PatchImplementation.FRUIT_TREE)
			+ PayToClear.cost(PatchImplementation.REDWOOD);

		LoadoutItem coins = clearingRow();
		assertNotNull("four payable patches and no coins row", coins);
		assertEquals("the standing magic, the unchecked magic, the papaya and the dead redwood",
			expected, coins.getQuantity());

		Set<FarmPatch> banked = new LinkedHashSet<>(
			java.util.Arrays.asList(paidMagic, uncheckedMagic, ladenPapaya, deadRedwood));
		assertTrue("the guide must never ask to pay at a patch the coins do not cover",
			banked.containsAll(guideWouldPayAt()));

		assertEquals("and right now it asks at the two that are ready to be paid for",
			new LinkedHashSet<>(java.util.Arrays.asList(paidMagic, deadRedwood)),
			guideWouldPayAt());
	}

	/**
	 * Once the run has done its checking and its picking, the guide asks at all four — the same
	 * four the coins were banked for, and still not one more.
	 *
	 * <p>This is the half that makes the wider loadout honest rather than merely wider. A
	 * grown-but-unchecked tree and a laden fruit tree are both counted at the bank precisely
	 * because the run will have moved them into the payable state by the time it stands in front
	 * of them, and this walks them there to prove it.
	 */
	@Test
	public void onceCheckedAndPickedTheGuideAsksAtEveryPatchTheCoinsWereBankedFor()
	{
		int before = clearingRow().getQuantity();

		record(uncheckedMagic, stumpValueOf(Produce.MAGIC) - 1);   // the health check happened
		record(ladenPapaya, fruitValueOf(Produce.PAPAYA, false));  // the fruit was picked

		assertEquals("neither click changes what the run costs to clear",
			before, clearingRow().getQuantity());
		assertEquals("every patch the coins were banked for now wants them",
			new LinkedHashSet<>(java.util.Arrays.asList(
				paidMagic, uncheckedMagic, ladenPapaya, deadRedwood)),
			guideWouldPayAt());
	}

	// ------------------------------------------------------------------ the two sides

	/** The loadout's side: the single coins row this run's clearing fees fold into. */
	private LoadoutItem clearingRow()
	{
		for (LoadoutItem item : loadout.forRun(TYPES))
		{
			if (item.getCategory() == LoadoutItem.Category.CLEARING)
			{
				return item;
			}
		}
		return null;
	}

	/**
	 * The guide's side: every patch in the world whose first instruction is "pay to clear".
	 *
	 * <p>A maple is handed in as the chosen seed at every tree patch, deliberately — it is the
	 * owner's own scenario ("I want to clear a magic tree myself even if I'm planting a maple in
	 * it next"), and a guide that read the choice off {@code chosen} rather than off the standing
	 * crop would answer differently here.
	 */
	private Set<FarmPatch> guideWouldPayAt()
	{
		Set<FarmPatch> paying = new LinkedHashSet<>();
		for (FarmPatch patch : java.util.Arrays.asList(paidMagic, uncheckedMagic, magicStump,
			untoggledYew, ladenPapaya, deadRedwood))
		{
			Seed chosen = patch.getImplementation() == PatchImplementation.FRUIT_TREE
				? Seed.PAPAYA
				: Seed.MAPLE;
			List<GuideStep> steps = GuidePlan.forPatch(projectionOf(patch),
				patches.get(patch).getCompost(),
				PlantingGroup.of(patch.getImplementation()), chosen, seeds, compost, payToClear,
				carried, leprechaun, barbarian, false, false, false, 1, false, GUIDE_NAMES);
			if (!steps.isEmpty() && steps.get(0).getAction() == GuideAction.PAY_TO_CLEAR)
			{
				paying.add(patch);
			}
		}
		return paying;
	}

	// ------------------------------------------------------------------ fixture helpers

	/**
	 * Patches of this type with a gardener standing at them, which the guide's branch requires:
	 * with no farmer there is nobody to pay, whatever the coins say.
	 */
	private static List<FarmPatch> withAGardener(PatchImplementation type)
	{
		List<FarmPatch> tended = new ArrayList<>();
		for (FarmPatch patch : FarmingWorldData.getPatches(type))
		{
			if (patch.getFarmer() != -1)
			{
				tended.add(patch);
			}
		}
		assertTrue("fixture: no gardener-tended " + type + " patch in the world data",
			!tended.isEmpty());
		return tended;
	}

	/**
	 * Puts a patch into the state a varbit decodes to, makes it available, and tells the planner
	 * its group.
	 *
	 * <p>The grouper has to answer for these specifically: {@code clearableIn} and {@code
	 * deadRedwoodsIn} require an exact group match with no null fallback, and once {@code
	 * groupFor} stops answering null the planner asks whether the group's run is ticked rather
	 * than taking its "nobody answered" shortcut. See {@code RunLoadoutTest.payableTreePatch}.
	 */
	private FarmPatch record(FarmPatch patch, int varbitValue)
	{
		ProduceState decoded = patch.getImplementation().forVarbitValue(varbitValue);
		assertNotNull("varbit " + varbitValue + " does not decode for " + patch.getKey(), decoded);
		patches.recordVarbit(patch, varbitValue, decoded);
		availability.setAvailable(patch, true);

		PlantingGroup group = PlantingGroup.of(patch.getImplementation());
		when(groups.groupFor(patch)).thenReturn(group);
		when(plannerRunOptions.isSelected(com.dooglemaps.data.RunOption.full(group)))
			.thenReturn(true);
		return patch;
	}

	private PatchProjection projectionOf(FarmPatch patch)
	{
		PatchProjection projection = growthTimer.project(patch, patches.get(patch));
		assertNotNull("fixture: " + patch.getKey() + " has no projection", projection);
		return projection;
	}

	/**
	 * The one value at which this tree is a felled stump. The tree still standing is the value
	 * below it and the grown, unchecked one the value below that — the three-value shape {@code
	 * TreeStumpTest} pins.
	 */
	private static int stumpValueOf(Produce produce)
	{
		for (int value = 0; value < 256; value++)
		{
			ProduceState decoded = PatchImplementation.TREE.forVarbitValue(value);
			if (decoded != null && decoded.getProduce() == produce
				&& PatchImplementation.TREE.isStumpVarbitValue(value))
			{
				return value;
			}
		}
		throw new AssertionError("no stump value for " + produce);
	}

	/**
	 * A fruit tree's fullest harvestable value, or its emptiest.
	 *
	 * <p>By the decoded <b>stage</b> rather than by the raw value: a fruit tree's harvestable
	 * stage is its fruit count, and papaya carries a second stage-0 harvestable value above its
	 * main block (188, beside the 169-175 run), so "the highest value" would have picked an empty
	 * tree and called it laden.
	 */
	private static int fruitValueOf(Produce produce, boolean laden)
	{
		int found = -1;
		int foundStage = -1;
		for (int value = 0; value < 256; value++)
		{
			ProduceState decoded = PatchImplementation.FRUIT_TREE.forVarbitValue(value);
			if (decoded == null || decoded.getProduce() != produce
				|| decoded.getCropState() != CropState.HARVESTABLE)
			{
				continue;
			}
			if (found == -1 || (laden ? decoded.getStage() > foundStage
				: decoded.getStage() < foundStage))
			{
				found = value;
				foundStage = decoded.getStage();
			}
		}
		if (found == -1)
		{
			throw new AssertionError("no harvestable value for " + produce);
		}
		return found;
	}

	/** Any value this family decodes as dead, which for the redwood is its only clearable state. */
	private static int deadValueOf(PatchImplementation type)
	{
		for (int value = 0; value < 256; value++)
		{
			ProduceState decoded = type.forVarbitValue(value);
			if (decoded != null && decoded.getCropState() == CropState.DEAD)
			{
				return value;
			}
		}
		throw new AssertionError("no dead value for " + type);
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
}
