package com.dooglemaps.guide;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.data.Seed;
import com.dooglemaps.data.FarmingTool;
import com.dooglemaps.state.BarbarianFarming;
import com.dooglemaps.state.CompostSelectionStore;
import com.dooglemaps.state.LeprechaunStore;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.state.SeedSource;
import com.dooglemaps.timer.GrowthTimer;
import com.dooglemaps.timer.PatchProjection;
import com.google.gson.Gson;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameTick;
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
 * Covers what guided mode tells you to do next.
 *
 * <p>The interesting property is that the answer is a <b>function of the patch's state</b>
 * rather than a position in a script. So these tests set up a world and ask, rather than
 * driving a sequence — which is also how the real thing behaves when a player does something
 * out of order.
 */
public class GuidePlanTest
{
	/** Falador's north allotment: known-good varbit fixtures, and it can be composted. */
	private static final String FALADOR_NORTH = "12083.4771";

	private PatchStateStore patches;
	private SeedInventoryStore seeds;
	private CompostSelectionStore compost;
	private GrowthTimer growthTimer;
	private CarriedItems carried;

	/** Empty by default: an unticked store holds nothing, so no tool step ever fires. */
	private LeprechaunStore leprechaun;

	/** Locked by default, so the dibber is asked for unless a test says otherwise. */
	private BarbarianFarming barbarian;

	@Before
	public void setUp() throws Exception
	{
		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(int.class)))
			.thenReturn(85);

		// A backing map for the flags that are written and read back — Barbarian Farming is
		// persisted, so a mock that forgets what it was told would make the unlock untestable.
		java.util.Map<String, Object> profile = new java.util.HashMap<>();
		Mockito.doAnswer(i -> profile.put(i.getArgument(0) + "." + i.getArgument(1), i.getArgument(2)))
			.when(configManager).setRSProfileConfiguration(anyString(), anyString(), Mockito.any());
		when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(boolean.class)))
			.thenAnswer(i -> profile.get(i.getArgument(0) + "." + i.getArgument(1)));

		Gson gson = new Gson();
		patches = construct(PatchStateStore.class, configManager, gson);
		patches.load();
		seeds = construct(SeedInventoryStore.class,
			Mockito.mock(net.runelite.api.Client.class), configManager, gson);
		compost = construct(CompostSelectionStore.class, configManager, gson);
		compost.load();
		growthTimer = construct(GrowthTimer.class, configManager);
		carried = construct(CarriedItems.class, Mockito.mock(net.runelite.api.Client.class));
		leprechaun = leprechaunHolding();
		barbarian = construct(BarbarianFarming.class, configManager,
			Mockito.mock(com.dooglemaps.DoogleMapsConfig.class));
	}

	@Test
	public void aRipePatchSaysHarvest()
	{
		FarmPatch patch = statePatch(10);   // potatoes, harvestable
		carrying();

		GuideStep step = firstStep(patch, Seed.POTATO);
		assertEquals(GuideAction.HARVEST, step.getAction());
		assertTrue(step.getText(), step.getText().startsWith("Harvest"));
		assertTrue("the patch is what you click", step.highlightsPatch());
	}

	/**
	 * A harvest is never interrupted by the seed box.
	 *
	 * <p>Two box steps lived here over time — a pre-harvest "fill the box" gated on the expected
	 * yield, then a plain fill-before-leaving — and both are gone by request. The box is not
	 * something the guide talks about at all now; it is handled entirely by the left-click swap
	 * in {@code GuideMenuSwap}, which reads the box rather than the current step.
	 */
	@Test
	public void aHarvestLeadsEvenWithTheBoxAndLooseSeeds()
	{
		FarmPatch patch = statePatch(10);      // potatoes, harvestable
		stockInventory(Seed.POTATO, 9);        // loose in the pack
		carrying(net.runelite.api.gameval.ItemID.SEED_BOX, 1);

		List<GuideStep> steps = GuidePlan.forPatch(
			growthTimer.project(patch, patches.get(patch)),
			patches.get(patch).getCompost(), group(patch), Seed.POTATO, seeds, compost, carried,
			leprechaun, barbarian, false, false, 1);

		assertEquals(GuideAction.HARVEST, steps.get(0).getAction());
		assertTrue("no box step anywhere before the pick", steps.stream().noneMatch(
			step -> step.getItemId() == net.runelite.api.gameval.ItemID.SEED_BOX));
	}

	/** Once the pack is actually full, noting is the fix and the box is not mentioned. */
	@Test
	public void aFullPackNotesRatherThanShufflesSeeds()
	{
		FarmPatch patch = statePatch(10);
		stockInventory(Seed.POTATO, 9);

		int[] items = new int[28 * 2];
		for (int i = 0; i < 27; i++)
		{
			items[i * 2] = 1000 + i;
			items[i * 2 + 1] = 1;
		}
		items[54] = net.runelite.api.gameval.ItemID.SEED_BOX;
		items[55] = 1;
		carrying(items);

		List<GuideStep> steps = GuidePlan.forPatch(
			growthTimer.project(patch, patches.get(patch)),
			patches.get(patch).getCompost(), group(patch), Seed.POTATO, seeds, compost, carried,
			leprechaun, barbarian, false, false, 1);

		assertEquals(GuideAction.NOTE_AT_LEPRECHAUN, steps.get(0).getAction());
		assertTrue("no seed-box step anywhere in the list", steps.stream().noneMatch(
			step -> step.getItemId() == net.runelite.api.gameval.ItemID.SEED_BOX));
	}

	/**
	 * A full inventory changes the instruction, not just adds to it.
	 *
	 * <p>Telling someone to keep harvesting into a pack with no room is the sort of guidance
	 * that gets a plugin turned off.
	 */
	@Test
	public void afullInventorySendsYouToTheLeprechaunFirst()
	{
		FarmPatch patch = statePatch(10);
		carryingFullPack();

		GuideStep step = firstStep(patch, Seed.POTATO);
		assertEquals(GuideAction.NOTE_AT_LEPRECHAUN, step.getAction());
		assertEquals("the crop is what you hand over",
			Produce.POTATO.getItemID(), step.getItemId());
		assertTrue("pointing at the patch would be pointing the wrong way",
			!step.highlightsPatch());

		// The bug this covers: the overlay asked "is this step at the leprechaun" and, being
		// told yes, went looking for a potato in his store. There is no potato slot in it, so it
		// drew nothing — he lit up and the crop you are supposed to click did not, which is most
		// of the instruction missing. The crop is in your pack; that is why you are visiting him.
		assertFalse("the crop is in the pack, not in his store", step.itemIsInStore());
	}

	/**
	 * Withdrawing is the one direction where the item really is in his store.
	 *
	 * <p>The pair to the test above. Both cases are steps "at the leprechaun" and they highlight
	 * in opposite places, which is exactly why where the click happens is the wrong question.
	 */
	@Test
	public void withdrawingCompostLooksInHisStore()
	{
		FarmPatch patch = statePatch(3);   // raked, empty
		compost.set(PatchImplementation.ALLOTMENT, CompostTier.ULTRACOMPOST);
		stockInventory(Seed.POTATO, 3);
		carrying();

		GuideStep step = firstStep(patch, Seed.POTATO);
		assertEquals(GuideAction.WITHDRAW_COMPOST, step.getAction());
		assertTrue("this one really does come out of his store", step.itemIsInStore());
	}

	/**
	 * A tool you are not carrying is fetched from him before the step that needs it.
	 *
	 * <p>Ordinarily silent, because the tool is already in the pack. The case it exists for is
	 * the one that otherwise wastes the stop: standing at a weedy patch with no rake, where every
	 * later instruction — treat, plant — is on ground the game will not accept them on.
	 */
	@Test
	public void aRakeIsFetchedFromHisStoreBeforeRaking() throws Exception
	{
		FarmPatch patch = statePatch(0);   // weeds
		carrying();
		leprechaun = leprechaunHolding(FarmingTool.RAKE);

		List<GuideStep> steps = steps(patch, Seed.POTATO);
		assertEquals("the rake comes first - you cannot rake without one",
			GuideAction.WITHDRAW_TOOL, steps.get(0).getAction());
		assertTrue("and it is in his store, so that is where to look",
			steps.get(0).itemIsInStore());
		assertEquals(GuideAction.CLEAR, steps.get(1).getAction());
	}

	/**
	 * A rake he does not have is not a step at the patch.
	 *
	 * <p>There is nothing useful to say at a weedy patch about a rake sitting in your bank. That
	 * belongs to the loadout, before you set off, and telling you here would be an instruction
	 * you cannot follow.
	 */
	@Test
	public void aRakeHeDoesNotHaveIsNotMentionedAtThePatch()
	{
		FarmPatch patch = statePatch(0);   // weeds
		carrying();

		assertEquals("straight to raking, with no errand he cannot fulfil",
			GuideAction.CLEAR, firstStep(patch, Seed.POTATO).getAction());
	}

	/** Carrying one already means no errand at all — the usual case, and it stays quiet. */
	@Test
	public void aRakeYouAreCarryingIsNotFetched() throws Exception
	{
		FarmPatch patch = statePatch(0);   // weeds
		carrying(net.runelite.api.gameval.ItemID.RAKE, 1);
		leprechaun = leprechaunHolding(FarmingTool.RAKE);

		assertEquals(GuideAction.CLEAR, firstStep(patch, Seed.POTATO).getAction());
	}

	/**
	 * Barbarian Farming means the dibber is never asked for.
	 *
	 * <p>Reported from play: an account with the unlock was told to fetch a seed dibber from the
	 * leprechaun. It has not needed one in years, and being sent for a tool you made obsolete is
	 * how a player learns to stop reading the guidance.
	 */
	@Test
	public void barbarianFarmingSkipsTheDibber() throws Exception
	{
		FarmPatch patch = statePatch(3);   // raked, empty
		compost.set(PatchImplementation.ALLOTMENT, CompostTier.NONE);
		stockInventory(Seed.POTATO, 3);
		carrying();
		leprechaun = leprechaunHolding(FarmingTool.SEED_DIBBER);

		assertEquals("without the unlock, the dibber is fetched first",
			GuideAction.WITHDRAW_TOOL, firstStep(patch, Seed.POTATO).getAction());

		barbarian.observePlantedWithoutDibber();

		assertEquals("with it, planting is the only step", GuideAction.PLANT,
			firstStep(patch, Seed.POTATO).getAction());
	}

	/**
	 * The setting says so without waiting to be watched.
	 *
	 * <p>The observation only fires once a planting has been seen, which is fine in principle and
	 * irritating for someone who has had the unlock for years and is being asked for a dibber in
	 * the meantime.
	 */
	@Test
	public void theSettingAloneSkipsTheDibber() throws Exception
	{
		FarmPatch patch = statePatch(3);   // raked, empty
		compost.set(PatchImplementation.ALLOTMENT, CompostTier.NONE);
		stockInventory(Seed.POTATO, 3);
		carrying();
		leprechaun = leprechaunHolding(FarmingTool.SEED_DIBBER);

		com.dooglemaps.DoogleMapsConfig config =
			Mockito.mock(com.dooglemaps.DoogleMapsConfig.class);
		when(config.barbarianFarmingOverride()).thenReturn(true);
		barbarian = construct(BarbarianFarming.class,
			Mockito.mock(ConfigManager.class), config);

		assertEquals(GuideAction.PLANT, firstStep(patch, Seed.POTATO).getAction());
	}

	/**
	 * One free slot is not full, and must not send you to the leprechaun.
	 *
	 * <p>Reported from play at 27 of 28: there was still room for another herb, and being told
	 * to go and note is a wasted trip a pick early.
	 */
	@Test
	public void oneFreeSlotIsStillRoomToKeepPicking()
	{
		FarmPatch patch = statePatch(10);
		carryingItems(CarriedItems.INVENTORY_SIZE - 1);

		assertEquals("there is room for one more", GuideAction.HARVEST,
			firstStep(patch, Seed.POTATO).getAction());
	}

	@Test
	public void aDeadCropSaysClearItFirst()
	{
		FarmPatch patch = statePatch(193);  // dead snape grass
		carrying();

		GuideStep step = firstStep(patch, Seed.POTATO);
		assertEquals(GuideAction.CLEAR, step.getAction());
		assertTrue("the dead crop is named, so it is obvious what is being cleared",
			step.getText().contains("dead"));
	}

	/** Compost has to go on before the seed: treating a planted patch does nothing. */
	@Test
	public void anEmptyPatchAsksForCompostBeforeSeed()
	{
		FarmPatch patch = statePatch(3);    // raked, empty
		compost.set(PatchImplementation.ALLOTMENT, CompostTier.ULTRACOMPOST);
		carrying(CompostTier.ULTRACOMPOST.getItemID(), 1);
		stockInventory(Seed.POTATO, 10);

		GuideStep step = firstStep(patch, Seed.POTATO);
		assertEquals(GuideAction.APPLY_COMPOST, step.getAction());
		assertEquals(CompostTier.ULTRACOMPOST.getItemID(), step.getItemId());
	}

	/** Nothing to apply it with means the withdrawal comes first. */
	@Test
	public void compostYouAreNotCarryingIsWithdrawnFirst()
	{
		FarmPatch patch = statePatch(3);
		compost.set(PatchImplementation.ALLOTMENT, CompostTier.ULTRACOMPOST);
		carrying();
		stockInventory(Seed.POTATO, 10);

		GuideStep step = firstStep(patch, Seed.POTATO);
		assertEquals(GuideAction.WITHDRAW_COMPOST, step.getAction());
	}

	/**
	 * Withdrawing and applying name the same bucket, so where they happen has to be explicit.
	 *
	 * <p>The overlay decided where to draw from the item id alone, which meant the
	 * leprechaun's slot stayed lit after the withdrawal was done — pointing at him while the
	 * instruction said to treat the patch.
	 */
	@Test
	public void withdrawingAndApplyingCompostHappenInDifferentPlaces()
	{
		FarmPatch patch = statePatch(3);
		compost.set(PatchImplementation.ALLOTMENT, CompostTier.ULTRACOMPOST);
		stockInventory(Seed.POTATO, 10);

		carrying();
		GuideStep withdraw = firstStep(patch, Seed.POTATO);
		assertEquals(GuideAction.WITHDRAW_COMPOST, withdraw.getAction());
		assertTrue("this one is at the leprechaun", withdraw.isAtLeprechaun());
		assertTrue("so the patch is not the target", !withdraw.highlightsPatch());

		carrying(CompostTier.ULTRACOMPOST.getItemID(), 4);
		GuideStep apply = firstStep(patch, Seed.POTATO);
		assertEquals(GuideAction.APPLY_COMPOST, apply.getAction());
		assertEquals("same bucket either way, which is what made this ambiguous",
			withdraw.getItemId(), apply.getItemId());
		assertTrue("but this one happens at the patch", !apply.isAtLeprechaun());
		assertTrue(apply.highlightsPatch());
	}

	/**
	 * A bottomless bucket is compost, so there is nothing to withdraw.
	 *
	 * <p>Both ids, and the <b>filled</b> one is the case that matters: 22994 is the empty
	 * bucket and 22997 the one actually holding compost. The original test passed only the
	 * empty id, so it went green while the real situation — a working bucket — still sent the
	 * player to the leprechaun.
	 */
	@Test
	public void aBottomlessBucketNeedsNoWithdrawal()
	{
		FarmPatch patch = statePatch(3);
		compost.set(PatchImplementation.ALLOTMENT, CompostTier.ULTRACOMPOST);
		stockInventory(Seed.POTATO, 10);

		carrying(net.runelite.api.gameval.ItemID.BOTTOMLESS_COMPOST_BUCKET, 1);
		assertEquals("the empty bucket still counts as owning one",
			GuideAction.APPLY_COMPOST, firstStep(patch, Seed.POTATO).getAction());

		carrying(net.runelite.api.gameval.ItemID.BOTTOMLESS_COMPOST_BUCKET_FILLED, 1);
		assertEquals("and the filled one is the whole point of it",
			GuideAction.APPLY_COMPOST, firstStep(patch, Seed.POTATO).getAction());
	}

	/**
	 * Holding one bucket when four patches need treating is not enough.
	 *
	 * <p>The check was "do you have any", so a single bucket silenced the withdrawal while the
	 * instruction still said to take four — then asked again after every patch. That is what
	 * made the bucket counting look wrong in play.
	 */
	@Test
	public void oneBucketIsNotEnoughForFourPatches()
	{
		FarmPatch patch = statePatch(3);
		compost.set(PatchImplementation.ALLOTMENT, CompostTier.ULTRACOMPOST);
		carrying(CompostTier.ULTRACOMPOST.getItemID(), 1);
		stockInventory(Seed.POTATO, 10);

		List<GuideStep> steps = GuidePlan.forPatch(
			growthTimer.project(patch, patches.get(patch)),
			patches.get(patch).getCompost(), group(patch), Seed.POTATO, seeds, compost, carried,
			leprechaun, barbarian, false, false, 4);

		assertEquals(GuideAction.WITHDRAW_COMPOST, steps.get(0).getAction());
		assertTrue("and it should ask for the three still missing, not all four: "
			+ steps.get(0).getText(), steps.get(0).getText().contains("3"));
	}

	/** Enough in hand means no trip at all. */
	@Test
	public void enoughBucketsMeansNoWithdrawal()
	{
		FarmPatch patch = statePatch(3);
		compost.set(PatchImplementation.ALLOTMENT, CompostTier.ULTRACOMPOST);
		carrying(CompostTier.ULTRACOMPOST.getItemID(), 4);
		stockInventory(Seed.POTATO, 10);

		List<GuideStep> steps = GuidePlan.forPatch(
			growthTimer.project(patch, patches.get(patch)),
			patches.get(patch).getCompost(), group(patch), Seed.POTATO, seeds, compost, carried,
			leprechaun, barbarian, false, false, 4);

		assertEquals(GuideAction.APPLY_COMPOST, steps.get(0).getAction());
	}

	/**
	 * A weedy patch is raked, not composted and planted.
	 *
	 * <p>{@code Produce.WEEDS} is not a crop, so {@code isEmpty()} is true for it and the patch
	 * fell straight through to "treat the patch" and "plant the seed" — neither of which the
	 * game allows on unraked ground. Never seen in play because autoweed was on.
	 */
	@Test
	public void aWeedyPatchIsRakedFirst()
	{
		FarmPatch patch = statePatch(0);   // weeds
		compost.set(PatchImplementation.ALLOTMENT, CompostTier.ULTRACOMPOST);
		carrying(CompostTier.ULTRACOMPOST.getItemID(), 4);
		stockInventory(Seed.POTATO, 10);

		GuideStep step = firstStep(patch, Seed.POTATO);
		assertEquals(GuideAction.CLEAR, step.getAction());
		assertTrue(step.getText(), step.getText().toLowerCase().contains("rake"));
	}

	/** The withdrawal says how many, so you take the right number in one go. */
	@Test
	public void theWithdrawalNamesAQuantity()
	{
		FarmPatch patch = statePatch(3);
		compost.set(PatchImplementation.ALLOTMENT, CompostTier.ULTRACOMPOST);
		carrying();
		stockInventory(Seed.POTATO, 10);

		List<GuideStep> steps = GuidePlan.forPatch(
			growthTimer.project(patch, patches.get(patch)),
			patches.get(patch).getCompost(), group(patch), Seed.POTATO, seeds, compost, carried,
			leprechaun, barbarian, false, false, 4);

		assertTrue(steps.get(0).getText(), steps.get(0).getText().contains("4"));
	}

	/** Once the patch is treated, the compost step drops out on its own. */
	@Test
	public void anAlreadyCompostedPatchMovesOnToPlanting()
	{
		FarmPatch patch = statePatch(3);
		compost.set(PatchImplementation.ALLOTMENT, CompostTier.ULTRACOMPOST);
		patches.recordCompost(patch, CompostTier.ULTRACOMPOST);
		stockInventory(Seed.POTATO, 10);
		carrying();

		GuideStep step = firstStep(patch, Seed.POTATO);
		assertEquals(GuideAction.PLANT, step.getAction());
		assertEquals(Seed.POTATO.getItemID(), step.getItemId());
		assertTrue("three per allotment should be said, not assumed",
			step.getText().contains("3"));
	}

	/**
	 * Seeds in the bank are not seeds on the trip, and the patch asks for nothing at all.
	 *
	 * <p>Reported from play at Prifddinas: "Plant 3 snape grass seeds" stood at the top of the
	 * list, unperformable, while the seeds sat in the bank — restarting the run and reselecting
	 * the seed changed nothing, because the allocation counts every place seeds are kept. It
	 * does so on purpose (it also feeds the loadout, where "go and get them" is the point), so
	 * the this-trip test lives at the patch. No compost step either: treating a patch that
	 * cannot be planted is preparation for nothing.
	 */
	@Test
	public void seedsInTheBankProduceNoStepsAtThePatch()
	{
		FarmPatch patch = statePatch(3);   // raked, empty
		compost.set(PatchImplementation.ALLOTMENT, CompostTier.ULTRACOMPOST);
		carrying(CompostTier.ULTRACOMPOST.getItemID(), 4);
		stock(SeedSource.BANK, Seed.POTATO, 20);

		assertTrue("an instruction that cannot be followed is worse than none",
			steps(patch, Seed.POTATO).isEmpty());
	}

	/**
	 * Seeds in the box plant like any other seeds: the guide says nothing about the box.
	 *
	 * <p>An empty-before-planting step used to come first, and it was removed by request along
	 * with its fill-before-leaving twin — the box is a container you touch twice a stop, and
	 * both steps were noise around the clicks that matter. It also hung on a patch, which is
	 * what lit an unrelated allotment while it was current. Emptying the box is a left-click
	 * away instead; see {@code GuideMenuSwap}.
	 */
	@Test
	public void boxedSeedsPlantWithNoWordAboutTheBox()
	{
		FarmPatch patch = statePatch(3);
		compost.set(PatchImplementation.ALLOTMENT, CompostTier.NONE);
		stock(SeedSource.SEED_BOX, Seed.POTATO, 20);
		carrying(net.runelite.api.gameval.ItemID.SEED_BOX, 1);

		List<GuideStep> steps = steps(patch, Seed.POTATO);
		assertEquals(GuideAction.PLANT, steps.get(steps.size() - 1).getAction());
		assertTrue("nothing points at the box", steps.stream().noneMatch(
			step -> step.getItemId() == net.runelite.api.gameval.ItemID.SEED_BOX));
	}

	/** A growing crop wants leaving alone, and guided mode should say nothing at all. */
	@Test
	public void aGrowingCropProducesNoInstruction()
	{
		FarmPatch patch = statePatch(6);    // potatoes, growing
		compost.set(PatchImplementation.ALLOTMENT, CompostTier.NONE);
		carrying();

		assertTrue("nothing to do here", steps(patch, Seed.POTATO).isEmpty());
	}

	/**
	 * A seed just sown into an untreated patch still gets its compost.
	 *
	 * <p>The wiki's own herb-run guide plants first and composts second, and the game allows
	 * it — so anyone following that order used to get silence and an untreated patch, because
	 * the compost step only existed while the patch was empty.
	 *
	 * <p>Deliberately limited to the first growth stage, which lasts one growth tick, so this
	 * cannot turn into nagging about a crop planted an hour ago.
	 */
	@Test
	public void aJustPlantedPatchIsStillOfferedItsCompost()
	{
		FarmPatch patch = statePatch(6);    // potatoes, first growth stage
		compost.set(PatchImplementation.ALLOTMENT, CompostTier.ULTRACOMPOST);
		carrying(CompostTier.ULTRACOMPOST.getItemID(), 1);

		GuideStep step = firstStep(patch, Seed.POTATO);
		assertEquals(GuideAction.APPLY_COMPOST, step.getAction());
	}

	/** Once it is treated, a growing crop goes quiet again. */
	@Test
	public void aJustPlantedPatchThatIsAlreadyTreatedSaysNothing()
	{
		FarmPatch patch = statePatch(6);
		compost.set(PatchImplementation.ALLOTMENT, CompostTier.ULTRACOMPOST);
		patches.recordCompost(patch, CompostTier.ULTRACOMPOST);
		carrying(CompostTier.ULTRACOMPOST.getItemID(), 1);

		assertTrue("treated and growing, so nothing to do",
			steps(patch, Seed.POTATO).isEmpty());
	}

	/** With no seed picked there is nothing to instruct, and inventing one would be worse. */
	@Test
	public void anEmptyPatchWithNoSeedChosenStaysQuiet()
	{
		FarmPatch patch = statePatch(3);
		carrying();

		assertTrue(steps(patch, null).isEmpty());
	}

	/**
	 * A tree is planted from the sapling, so that is what gets highlighted.
	 *
	 * <p>Highlighting the seed would send someone hunting for an acorn that will not go in the
	 * ground — see the sapling work in {@code PlantableResolverTest}.
	 */
	@Test
	public void aTreePatchHighlightsTheSaplingNotTheSeed()
	{
		FarmPatch patch = treePatch();
		compost.set(PatchImplementation.TREE, CompostTier.NONE);
		stockInventory(Seed.OAK, 1);
		carrying();

		GuideStep step = firstStep(patch, Seed.OAK);
		assertEquals(GuideAction.PLANT, step.getAction());
		assertEquals("the sapling is what goes in the ground",
			Seed.OAK.getSaplingItemID(), step.getItemId());
		assertTrue(step.getText(), step.getText().contains("sapling"));
	}

	// ------------------------------------------------------------------- helpers

	/**
	 * A leprechaun's store holding exactly these tools.
	 *
	 * <p>Driven through the real varbit read rather than by poking the map, so the test also
	 * covers the thing most likely to break: which varbits each tool is made of.
	 */
	private LeprechaunStore leprechaunHolding(FarmingTool... tools) throws Exception
	{
		Client client = Mockito.mock(Client.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		for (FarmingTool tool : tools)
		{
			for (int varbit : tool.getVarbits())
			{
				when(client.getVarbitValue(varbit)).thenReturn(1);
			}
		}

		LeprechaunStore store = construct(LeprechaunStore.class, client);
		store.onGameTick(new GameTick());
		return store;
	}

	/**
	 * A grown tree, bush or cactus is checked before anything else can happen to it.
	 *
	 * <h2>The state that had no instruction at all</h2>
	 *
	 * These crops finish growing into a state the game still calls {@code GROWING}, because only
	 * the player can make the transition to harvestable. {@code GrowthTimer} correctly declines to
	 * promote them — and every branch of {@code forPatch} then read the patch as "still growing,
	 * leave it alone", so it produced <b>no step and no highlight</b>.
	 *
	 * <p>Reported as a finished cactus contract being passed over for an avantoe two patches away.
	 * The almanac said ready and the planner had routed to it; the guide simply had no word for it.
	 *
	 * <p>Worth pinning on more than one type, because {@code healthCheckRequired} is generated data
	 * and the failure is per-type silence rather than an error.
	 */
	@Test
	public void aGrownCropIsCheckedBeforeItCanBeHarvested()
	{
		for (PatchImplementation type : new PatchImplementation[]{
			PatchImplementation.CACTUS, PatchImplementation.TREE, PatchImplementation.BUSH})
		{
			FarmPatch patch = grownUnchecked(type);
			assertNotNull(type + " has no grown-but-unchecked varbit in the data", patch);

            List<GuideStep> steps = steps(patch, null);
			assertFalse(type + " produced no instruction at all", steps.isEmpty());
			assertEquals(type + " should be checked first",
				GuideAction.CHECK_HEALTH, steps.get(0).getAction());
			assertTrue("the patch is what you click", steps.get(0).highlightsPatch());
		}
	}

	/**
	 * The three clicks a grown tree wants, in order, each said out loud.
	 *
	 * <h2>Two of them did not exist</h2>
	 *
	 * A tree patch is check, chop, dig — and the game gives those three states three varbit values.
	 * The last two decode identically, so the guide said <i>"harvest the magic"</i> at the standing
	 * tree, said it again at the stump, and went on saying it: nothing the player clicked changed
	 * the state being tested, the patch never came out, and the stop never finished.
	 *
	 * <p>Reported from play as a yew contract that could not be started, with a grown magic tree in
	 * the patch it wanted. The run had routed correctly and the withdraw list had the axe on it;
	 * the guide simply could not describe the two clicks between checking the tree and planting.
	 *
	 * <p>Driven as three separate questions rather than one sequence, because the answer is a
	 * function of the patch state — which is also what happens when a player does it out of order.
	 */
	@Test
	public void aGrownTreeIsChecked_thenChopped_thenDugOut()
	{
		FarmPatch patch = grownUnchecked(PatchImplementation.TREE);
		assertNotNull("no tree has a grown-but-unchecked fixture", patch);
		carrying(FarmingTool.SPADE.getItemID(), 1);

		// The value the fixture settled on is the crop's grown-but-unchecked one, so the two above
		// it are the tree still standing and the stump it leaves.
		int unchecked = patches.get(patch).getVarbitValue();

		assertEquals("a grown tree is checked first - it is where the experience is",
			GuideAction.CHECK_HEALTH, firstStepAt(patch, unchecked).getAction());

		GuideStep chop = firstStepAt(patch, unchecked + 1);
		assertEquals("a checked tree is chopped, not harvested", GuideAction.CHOP, chop.getAction());
		assertTrue(chop.getText(), chop.getText().toLowerCase().startsWith("chop down"));
		assertTrue("the patch is what you click", chop.highlightsPatch());

		GuideStep dig = firstStepAt(patch, unchecked + 2);
		assertEquals("what a felled tree leaves is a stump, and it has to come out",
			GuideAction.CLEAR, dig.getAction());
		assertTrue(dig.getText(), dig.getText().toLowerCase().contains("stump"));
	}

	/**
	 * A harvest-only tree run stops once the logs are in the pack.
	 *
	 * <p>"Come back and take the logs" does not include digging the stump out — that is clearing a
	 * patch the player has said they are not replanting. The stump therefore produces no step at
	 * all, and, because {@code hasProduceToPick} answers no for one, nothing keeps the stop open
	 * either. Getting only the first half of that right is what would leave a harvest-only run
	 * parked at a patch it had already finished.
	 */
	@Test
	public void aHarvestOnlyTreeRunLeavesTheStumpStanding()
	{
		FarmPatch patch = grownUnchecked(PatchImplementation.TREE);
		assertNotNull(patch);
		carrying(FarmingTool.SPADE.getItemID(), 1);

		int unchecked = patches.get(patch).getVarbitValue();
		recordValue(patch, unchecked + 2);

		PatchProjection projection = growthTimer.project(patch, patches.get(patch));
		assertNotNull(projection);
		assertTrue("the fixture is meant to be a stump", projection.isStump());
		assertFalse("a stump has nothing on it to pick", projection.hasProduceToPick());

		assertTrue("a harvest-only run is finished with this patch",
			GuidePlan.forPatch(projection, patches.get(patch).getCompost(), group(patch), null,
				seeds, compost, carried, leprechaun, barbarian,
				/* protecting */ false, /* harvestOnly */ true, 1).isEmpty());
	}

	/**
	 * A growing crop the player asked to protect says to pay the farmer.
	 *
	 * <p>This is the case {@code addProtectionStep}'s own doc names — the tree that has just
	 * gone in, nothing else about it wanting doing — and it was structurally unreachable:
	 * the tracker asked the protection question of the <i>allocation's</i> seed, which is null
	 * for any patch already occupied, so {@code protecting} arrived false for every crop in
	 * the ground. Reported from play, on a contract tree: sapling planted, payment in the
	 * pack, and neither the gardener nor the step anywhere in sight.
	 */
	@Test
	public void aGrowingProtectedCropAsksForThePayment()
	{
		FarmPatch patch = growingIn(PatchImplementation.TREE);
		assertNotNull("no tree patch has a growing fixture", patch);

		PatchProjection projection = growthTimer.project(patch, patches.get(patch));
		assertNotNull(projection);
		com.dooglemaps.data.ProtectionPayment payment =
			com.dooglemaps.data.ProtectionPayment.forProduce(projection.getProduce());
		assertNotNull("a tree crop has a protection payment", payment);
		carrying(payment.getItemID(), payment.getQuantity());

		List<GuideStep> steps = GuidePlan.forPatch(projection, patches.get(patch).getCompost(),
			group(patch), null, seeds, compost, carried, leprechaun, barbarian,
			/* protecting */ true, /* harvestOnly */ false, 1);
		assertFalse("the payment is the one thing this patch still wants", steps.isEmpty());
		assertEquals(GuideAction.PAY_FARMER, steps.get(0).getAction());
		assertEquals("the gardener is what gets highlighted",
			patch.getFarmer(), steps.get(0).getNpcId());
	}

	/** The first instruction for this patch once its varbit reads the given value. */
	private GuideStep firstStepAt(FarmPatch patch, int varbitValue)
	{
		recordValue(patch, varbitValue);
		List<GuideStep> steps = steps(patch, Seed.YEW);
		assertFalse("varbit " + varbitValue + " produced no instruction at all", steps.isEmpty());
		return steps.get(0);
	}

	private void recordValue(FarmPatch patch, int varbitValue)
	{
		ProduceState decoded = patch.getImplementation().forVarbitValue(varbitValue);
		assertNotNull("varbit " + varbitValue + " does not decode", decoded);
		patches.recordVarbit(patch, varbitValue, decoded);
	}

	/**
	 * A growing crop is left alone even when a contract wants its patch.
	 *
	 * <h2>Considered and rejected: digging it up</h2>
	 *
	 * The guild has one patch of each type, so a contract whose patch is occupied genuinely cannot
	 * be started — and the first fix for that was a step to dig the occupant out and get on with it.
	 * That trades a crop that is already days into growing for a contract that will still be there
	 * next run, and it is not the guide's trade to make. The run walks past, and says why: see
	 * {@code GuideTracker.contractNote}, which puts it in the chatbox as well as the panel.
	 */
	@Test
	public void aGrowingCropIsLeftAloneEvenForAContract()
	{
		FarmPatch patch = growingIn(PatchImplementation.TREE);
		assertNotNull("no tree patch has a growing fixture", patch);
		carrying(FarmingTool.SPADE.getItemID(), 1);

		for (GuideStep step : steps(patch, Seed.YEW,
			com.dooglemaps.data.PlantingGroup.contract(PatchImplementation.TREE)))
		{
			assertFalse("nothing here may throw a growing crop away: " + step.getText(),
				step.getAction() == GuideAction.CLEAR);
		}
	}

	/** A patch of this type part-way through growing something — not ready, not empty. */
	private FarmPatch growingIn(PatchImplementation type)
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(type))
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
		}
		return null;
	}

	/**
	 * A patch of this type sitting at its <b>last growth stage</b>, which is grown-but-unchecked.
	 *
	 * <p>Found by walking the varbit range rather than hardcoding a value: these differ per crop and
	 * are generated data, and the point of the test is that no type is silently missed.
	 *
	 * <p>The last stage is the whole trick, and it means no clock has to be faked. {@code
	 * projectDone} returns the tick the patch was last seen on once there are no stages left, so the
	 * done estimate is already in the past the moment it is recorded — which is exactly what
	 * separates "grown" from "still growing". An earlier version of this helper backdated
	 * {@code lastSeen} instead and then wrote the snapshot back, which quietly reset it to now,
	 * because the store hands out copies.
	 */
	private FarmPatch grownUnchecked(PatchImplementation type)
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(type))
		{
			for (int value = 0; value < 256; value++)
			{
				ProduceState decoded = patch.getImplementation().forVarbitValue(value);
				if (decoded == null || decoded.getProduce() == null
					|| !decoded.getProduce().isCrop()
					|| decoded.getCropState() != com.dooglemaps.data.CropState.GROWING
					|| decoded.getStage() != decoded.getProduce().getStages() - 1)
				{
					continue;
				}

				patches.recordVarbit(patch, value, decoded);
				PatchProjection projection = growthTimer.project(patch, patches.get(patch));
				if (projection != null && projection.needsHealthCheck())
				{
					return patch;
				}
			}
		}
		return null;
	}

	/**
	 * A patch in a diseased state, found by scanning the varbit table.
	 *
	 * <p>Scanned rather than listed so the fixture survives table regeneration; null when the
	 * type has no diseased state at all, which a test should treat as its answer.
	 */
	private FarmPatch diseased(PatchImplementation type)
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(type))
		{
			for (int value = 0; value < 256; value++)
			{
				ProduceState decoded = patch.getImplementation().forVarbitValue(value);
				if (decoded == null
					|| decoded.getCropState() != com.dooglemaps.data.CropState.DISEASED)
				{
					continue;
				}
				patches.recordVarbit(patch, value, decoded);
				return patch;
			}
		}
		return null;
	}

	/**
	 * The cure, at last: a diseased crop was routed to and then silently skipped.
	 *
	 * <p>{@code DISEASED} has always been actionable, so the run walked the player to their
	 * dying crop — and the plan then had no branch for the state, produced no step, and the
	 * nothing-to-do exemption crossed the patch off. The most urgent click on the run was the
	 * one with no word for it.
	 */
	@Test
	public void aDiseasedHerbAsksForAPlantCure()
	{
		FarmPatch patch = diseased(PatchImplementation.HERB);
		assertNotNull("no herb patch has a diseased state in the table", patch);

		List<GuideStep> steps = steps(patch, Seed.RANARR);
		assertEquals("cure before anything else", GuideAction.CURE, steps.get(0).getAction());
		assertTrue(steps.get(0).getText(), steps.get(0).getText().contains("plant cure"));
	}

	/** Trees are pruned, not dosed - the cure tool splits by family. */
	@Test
	public void aDiseasedTreeIsPrunedWithSecateurs()
	{
		FarmPatch patch = diseased(PatchImplementation.TREE);
		assertNotNull("no tree patch has a diseased state in the table", patch);

		List<GuideStep> steps = steps(patch, Seed.YEW);
		assertEquals(GuideAction.CURE, steps.get(0).getAction());
		assertTrue(steps.get(0).getText(), steps.get(0).getText().contains("secateurs"));
	}

	/**
	 * Harvest-only runs never see a cure, matching the routing: a diseased patch has nothing
	 * to pick, so a harvest-only run is never sent to one in the first place.
	 */
	@Test
	public void aHarvestOnlyRunIsNotAskedToCure()
	{
		FarmPatch patch = diseased(PatchImplementation.HERB);
		assertNotNull(patch);

		PatchProjection projection = growthTimer.project(patch, patches.get(patch));
		assertNotNull(projection);
		assertTrue("nothing to do here on a harvest-only visit",
			GuidePlan.forPatch(projection, patches.get(patch).getCompost(), group(patch),
				null, seeds, compost, carried, leprechaun, barbarian,
				false, /* harvestOnly */ true, 1).isEmpty());
	}

	/**
	 * The celastrus states the decode collapses, recovered by raw varbit.
	 *
	 * <p>Bark at 14–16, the stripped tree at 17, the stump at 28 — all decode HARVESTABLE, so
	 * before the raw value was carried the guide said "chop the bark" at a tree with none,
	 * forever. Values are upstream's own source comments, verified in the PatchRules audit.
	 */
	@Test
	public void aCelastrusWalksBarkThenChopThenDig()
	{
		FarmPatch patch = FarmingWorldData.getPatches(PatchImplementation.CELASTRUS).get(0);
		carrying(FarmingTool.SPADE.getItemID(), 1);

		recordValue(patch, 14);
		GuideStep bark = firstStep(patch, Seed.CELASTRUS);
		assertEquals("bark first", GuideAction.HARVEST, bark.getAction());
		assertTrue(bark.getText(), bark.getText().toLowerCase().contains("bark"));

		recordValue(patch, 17);
		GuideStep chop = firstStep(patch, Seed.CELASTRUS);
		assertEquals("a stripped tree is chopped down", GuideAction.CHOP, chop.getAction());

		recordValue(patch, 28);
		GuideStep dig = firstStep(patch, Seed.CELASTRUS);
		assertEquals("and its stump is dug", GuideAction.CLEAR, dig.getAction());
		assertTrue(dig.getText(), dig.getText().toLowerCase().contains("stump"));
	}

	/**
	 * The vinery's soil states, likewise: 0 untreated, 1 saltpetred, both decoding as empty.
	 */
	@Test
	public void aGrapePatchAsksForSaltpetreBeforeTheSeed()
	{
		FarmPatch patch = FarmingWorldData.getPatches(PatchImplementation.GRAPES).get(0);
		stockInventory(Seed.GRAPE, 10);

		recordValue(patch, 0);
		GuideStep treat = firstStep(patch, Seed.GRAPE);
		assertTrue("untreated soil wants saltpetre, not a seed",
			treat.getText().toLowerCase().contains("saltpetre"));

		recordValue(patch, 1);
		GuideStep plant = firstStep(patch, Seed.GRAPE);
		assertEquals("treated soil takes the seed", GuideAction.PLANT, plant.getAction());
	}

	/**
	 * A picked-clean bush on a replant run is dug up, not left alone.
	 *
	 * <p>A regrowing crop never empties — stripped, it sits {@code HARVESTABLE} with a stock
	 * of zero — so it fell through to the growing-leave-it-alone branch and the guide never
	 * asked for the dig the replant needs first. Reported from play.
	 */
	@Test
	public void aPickedCleanBushIsDugUpWhenReplanting()
	{
		FarmPatch patch = FarmingWorldData.getPatches(PatchImplementation.BUSH).get(0);
		stockInventory(Seed.REDBERRIES, 10);
		recordValue(patch, 10);   // redberries, harvestable, nothing left on it

		GuideStep step = firstStep(patch, Seed.REDBERRIES);
		assertEquals(GuideAction.CLEAR, step.getAction());
		assertTrue(step.getText(), step.getText().toLowerCase().contains("dig up"));
	}

	/** With berries still on it, the pick leads — the dig waits until the bush is stripped. */
	@Test
	public void aLadenBushIsPickedBeforeAnyDigging()
	{
		FarmPatch patch = FarmingWorldData.getPatches(PatchImplementation.BUSH).get(0);
		stockInventory(Seed.REDBERRIES, 10);
		recordValue(patch, 14);   // redberries, harvestable, four berries on it

		assertEquals(GuideAction.HARVEST, firstStep(patch, Seed.REDBERRIES).getAction());
	}

	/**
	 * Without the replacement seed at hand, a stripped bush is left standing.
	 *
	 * <p>Clearing a producing bush with nothing to put in its place is strictly worse than
	 * leaving it — the berries come back on their own.
	 */
	@Test
	public void aStrippedBushWithoutASeedIsLeftStanding()
	{
		FarmPatch patch = FarmingWorldData.getPatches(PatchImplementation.BUSH).get(0);
		recordValue(patch, 10);

		assertTrue("no seed at hand, so nothing to say",
			steps(patch, Seed.REDBERRIES).isEmpty());
	}

	private List<GuideStep> steps(FarmPatch patch, Seed chosen)
	{
		return steps(patch, chosen, group(patch));
	}

	private List<GuideStep> steps(FarmPatch patch, Seed chosen,
		com.dooglemaps.data.PlantingGroup group)
	{
		PatchProjection projection = growthTimer.project(patch, patches.get(patch));
		assertNotNull("fixture patch has no projection", projection);
		return GuidePlan.forPatch(projection,
			patches.get(patch) == null ? null : patches.get(patch).getCompost(),
			group, chosen, seeds, compost, carried, leprechaun, barbarian, false, false, 1);
	}

	private GuideStep firstStep(FarmPatch patch, Seed chosen)
	{
		List<GuideStep> steps = steps(patch, chosen);
		assertTrue("expected an instruction, got none", !steps.isEmpty());
		return steps.get(0);
	}

	/** Puts Falador's north allotment into the state the given varbit value decodes to. */
	/** The plain group for a patch, which is what every test here expects (nothing is split). */
	private static com.dooglemaps.data.PlantingGroup group(FarmPatch patch)
	{
		return com.dooglemaps.data.PlantingGroup.of(patch.getImplementation());
	}

	private FarmPatch statePatch(int varbitValue)
	{
		FarmPatch patch = FarmingWorldData.getPatch(FALADOR_NORTH);
		assertNotNull("fixture patch no longer exists", patch);
		ProduceState decoded = patch.getImplementation().forVarbitValue(varbitValue);
		assertNotNull("varbit " + varbitValue + " does not decode", decoded);
		patches.recordVarbit(patch, varbitValue, decoded);
		return patch;
	}

	/** Any empty tree patch, for the sapling case. */
	private FarmPatch treePatch()
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.TREE))
		{
			for (int value = 0; value < 256; value++)
			{
				ProduceState decoded = patch.getImplementation().forVarbitValue(value);
				// Stage 0, i.e. raked clean. A non-crop at a higher stage is weeds still
				// standing, which now correctly asks to be raked rather than planted.
				if (decoded != null && decoded.getProduce() != null
					&& !decoded.getProduce().isCrop() && decoded.getStage() == 0)
				{
					patches.recordVarbit(patch, value, decoded);
					return patch;
				}
			}
		}
		throw new IllegalStateException("no tree patch can be empty");
	}

	private void carrying(int... idThenQuantity)
	{
		carried.record(containerOf(idThenQuantity));
	}

	/** Twenty-eight distinct items, so there is no room for another. */
	private void carryingFullPack()
	{
		carryingItems(CarriedItems.INVENTORY_SIZE);
	}

	/** A pack holding this many distinct items, and so that many used slots. */
	private void carryingItems(int count)
	{
		int[] items = new int[count * 2];
		for (int i = 0; i < count; i++)
		{
			items[i * 2] = 1000 + i;
			items[i * 2 + 1] = 1;
		}
		carrying(items);
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

	private void stockInventory(Seed seed, int quantity)
	{
		stock(SeedSource.INVENTORY, seed, quantity);
	}

	private void stock(SeedSource source, Seed seed, int quantity)
	{
		seeds.record(source.getContainerId(),
			containerOf(seed.getPlantedItemID(), quantity));
	}
}
