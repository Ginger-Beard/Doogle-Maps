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
import java.lang.reflect.Constructor;
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
		carried = construct(CarriedItems.class);
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
	 * The seed box is what gets clicked, not the seed inside it.
	 *
	 * <p>Reported from play as "seedbox is not highlighted", and the cause is the step naming the
	 * seed. The whole reason this step exists is that the seed is <i>in the box</i> and so not in
	 * the inventory — meaning there was nothing on screen for the outline to find, and it drew
	 * nothing at all rather than failing loudly.
	 */
	@Test
	public void theSeedBoxStepHighlightsTheBox()
	{
		FarmPatch patch = statePatch(3);   // raked, empty
		compost.set(PatchImplementation.ALLOTMENT, CompostTier.NONE);
		stock(SeedSource.SEED_BOX, Seed.POTATO, 20);
		carrying(net.runelite.api.gameval.ItemID.SEED_BOX, 1);

		GuideStep step = firstStep(patch, Seed.POTATO);
		assertEquals(GuideAction.WITHDRAW_SEEDS, step.getAction());
		assertEquals("the box is the thing you click, and the only one on screen",
			net.runelite.api.gameval.ItemID.SEED_BOX, step.getItemId());
	}

	/** An open box is a different item id, and it is the one that would be on screen. */
	@Test
	public void anOpenSeedBoxIsMatchedToo()
	{
		FarmPatch patch = statePatch(3);
		compost.set(PatchImplementation.ALLOTMENT, CompostTier.NONE);
		stock(SeedSource.SEED_BOX, Seed.POTATO, 20);
		carrying(net.runelite.api.gameval.ItemID.SEED_BOX_OPEN, 1);

		assertEquals(net.runelite.api.gameval.ItemID.SEED_BOX_OPEN,
			firstStep(patch, Seed.POTATO).getItemId());
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
	 * Seeds in the box rather than the pack get their own step.
	 *
	 * <p>Worth calling out because Empty is not the seed box's left-click option, so someone
	 * following along would otherwise be told to plant a seed they cannot reach.
	 */
	@Test
	public void seedsInTheBoxAreWithdrawnBeforePlanting()
	{
		FarmPatch patch = statePatch(3);
		compost.set(PatchImplementation.ALLOTMENT, CompostTier.NONE);
		stock(SeedSource.SEED_BOX, Seed.POTATO, 20);
		carrying();

		GuideStep step = firstStep(patch, Seed.POTATO);
		assertEquals(GuideAction.WITHDRAW_SEEDS, step.getAction());
		assertTrue(step.getText(), step.getText().contains("seed box"));
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

	private List<GuideStep> steps(FarmPatch patch, Seed chosen)
	{
		PatchProjection projection = growthTimer.project(patch, patches.get(patch));
		assertNotNull("fixture patch has no projection", projection);
		return GuidePlan.forPatch(projection,
			patches.get(patch) == null ? null : patches.get(patch).getCompost(),
			group(patch), chosen, seeds, compost, carried, leprechaun, barbarian, false, false, 1);
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
