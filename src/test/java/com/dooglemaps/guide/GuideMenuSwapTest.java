package com.dooglemaps.guide;

import com.dooglemaps.data.SeedBox;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
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

	/**
	 * An empty box and an empty pack: Empty, and the overlay leaves the box unlit.
	 *
	 * <h2>Why this flipped</h2>
	 *
	 * It used to answer Fill, on the reasoning that an empty box has nothing to tip out. True,
	 * and not the point: {@code GuideInventoryOverlay} lights the box on
	 * {@code looseSeedTheBoxWouldTake}, so this was the one state where the two disagreed - the
	 * box sat unlit with Fill under the left click. Asked for from play as "only lit orange with
	 * the menu set to Fill if there are seeds in the player's inventory and free space in the
	 * seed box, else the menu should be Empty and unlit".
	 *
	 * <p>Both clicks do nothing here. The difference is that Empty matches what the box looks
	 * like, and Fill invited one on an item nothing was pointing at.
	 */
	@Test
	public void anEmptyBoxAndAnEmptyPackEmpties()
	{
		assertEquals("Empty", GuideMenuSwap.wantedBoxOption(seeds));
	}

	/**
	 * The menu and the highlight are the same question, so they answer together.
	 *
	 * <p>Pinned across all four states rather than left to the two methods happening to agree,
	 * because "the box is lit but the click is wrong" is not something a reader of either one
	 * would notice.
	 */
	@Test
	public void theMenuAgreesWithTheHighlightInEveryState()
	{
		assertEquals("empty box, empty pack", "Empty", GuideMenuSwap.wantedBoxOption(seeds));
		assertFalse(GuideMenuSwap.boxCanTakeLooseSeeds(seeds));

		stock(SeedSource.INVENTORY, Seed.POTATO, 30);
		assertEquals("empty box, loose seeds", "Fill", GuideMenuSwap.wantedBoxOption(seeds));
		assertTrue(GuideMenuSwap.boxCanTakeLooseSeeds(seeds));

		stock(SeedSource.SEED_BOX, Seed.ONION, 5);
		assertEquals("room and loose seeds", "Fill", GuideMenuSwap.wantedBoxOption(seeds));
		assertTrue(GuideMenuSwap.boxCanTakeLooseSeeds(seeds));

		// The fourth state — something in it, nothing loose — cannot be reached from here:
		// `stock` records cumulatively, so there is no way to take the potatoes back out of the
		// pack. It has its own test below, and the same pairing is asserted there.
	}

	/** The one state this test class cannot reach by adding: a stocked box and an empty pack. */
	@Test
	public void aStockedBoxWithAnEmptyPackIsUnlitAndEmpties()
	{
		stock(SeedSource.SEED_BOX, Seed.POTATO, 30);

		assertEquals("Empty", GuideMenuSwap.wantedBoxOption(seeds));
		assertFalse("and the overlay leaves it dark",
			GuideMenuSwap.boxCanTakeLooseSeeds(seeds));
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
	 * A tree <b>seed</b> takes one of the six slots, so a box holding one is that much fuller.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * Both the kind count and the loose-seed scan skipped every crop whose {@code isSapling()}
	 * was true, on the note that "the box will not take one, and for tree crops the count cannot
	 * tell an acorn from the sapling it became". Two different things. The <b>sapling</b> is what
	 * the box refuses; the <b>seed</b> goes in like any other.
	 *
	 * <p>So five ordinary seeds and a calquat seed counted as five kinds, the box read as having
	 * a slot free, and it lit to fill with a seventh kind loose in the pack that it would have
	 * refused. Reported from play three times as the box "staying orange" — and found by
	 * resolving the live box contents, where one of the six ids was a calquat seed.
	 */
	@Test
	public void aBoxedTreeSeedCountsTowardsTheSixKinds()
	{
		stock(SeedSource.SEED_BOX, Seed.POTATO, 10);
		stock(SeedSource.SEED_BOX, Seed.ONION, 10);
		stock(SeedSource.SEED_BOX, Seed.CABBAGE, 10);
		stock(SeedSource.SEED_BOX, Seed.TOMATO, 10);
		stock(SeedSource.SEED_BOX, Seed.SWEETCORN, 10);
		stockSeedItem(SeedSource.SEED_BOX, Seed.CALQUAT, 1);
		assertEquals("fixture: six kinds, one of them a tree seed",
			SeedBox.KINDS, GuideMenuSwap.kindsInTheBox(seeds));

		stock(SeedSource.INVENTORY, Seed.WATERMELON, 5);

		assertEquals("a seventh kind cannot go in a full box", "Empty",
			GuideMenuSwap.wantedBoxOption(seeds));
		assertFalse("and the box stays dark", GuideMenuSwap.boxCanTakeLooseSeeds(seeds));
	}

	/** A loose tree seed is something to fill with; the sapling it becomes is not. */
	@Test
	public void aLooseTreeSeedFillsAndItsSaplingDoesNot()
	{
		stockSeedItem(SeedSource.INVENTORY, Seed.CALQUAT, 1);
		assertEquals("a calquat seed goes in the box like any other seed", "Fill",
			GuideMenuSwap.wantedBoxOption(seeds));

		assertEquals("fixture: the sapling is a different item",
			false, Seed.CALQUAT.getItemID() == Seed.CALQUAT.getPlantedItemID());
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

	/**
	 * The herb-Use swap's data: grimy herbs are flagged, roots are not.
	 *
	 * <p>The swap promotes Use over Clean only for items in this set — everything else the
	 * leprechaun notes already defaults to Use, and promoting Use on a root would be a no-op
	 * worth not making. Pinned here because the set is hand-maintained beside the names table
	 * in {@code NotableHarvests}, and a herb added to one and not the other would silently
	 * miss the swap.
	 */
	@Test
	public void grimyHerbsAreFlaggedForTheUseSwapAndRootsAreNot()
	{
		assertEquals(true, com.dooglemaps.data.NotableHarvests.isGrimyHerb(
			net.runelite.api.gameval.ItemID.UNIDENTIFIED_RANARR));
		assertEquals("a root's left-click is already Use", false,
			com.dooglemaps.data.NotableHarvests.isGrimyHerb(
				net.runelite.api.gameval.ItemID.YEW_ROOTS));
		assertEquals("the flag never leaves the notable set", true,
			com.dooglemaps.data.NotableHarvests.isNotable(
				net.runelite.api.gameval.ItemID.UNIDENTIFIED_RANARR));
	}

	/**
	 * A banana's left-click is Eat, which takes it away from him rather than handing it over -
	 * the same fault the grimy herb swap was written for, just on an edible crop instead of a
	 * herb. While the note step is current, Use gets promoted the same way.
	 */
	@Test
	public void aBananaGetsUsePromotedWhileTheNoteStepIsCurrent()
	{
		assertLeprechaunNoteSwapPromotesUse(net.runelite.api.gameval.ItemID.BANANA, "Eat");
	}

	/** Same fault, a different crop: a papaya's left-click is Eat too. */
	@Test
	public void aPapayaGetsUsePromotedWhileTheNoteStepIsCurrent()
	{
		assertLeprechaunNoteSwapPromotesUse(net.runelite.api.gameval.ItemID.PAPAYA, "Eat");
	}

	/**
	 * Food with nothing to do with farming is left alone: a shark is not a notable harvest, so
	 * its own Eat stays the left click even with the note step current.
	 */
	@Test
	public void nonCropFoodIsLeftAloneEvenWithTheNoteStepCurrent()
	{
		net.runelite.api.MenuEntry use =
			entry("Use", "<col=ffff00>Shark", net.runelite.api.gameval.ItemID.SHARK);
		net.runelite.api.MenuEntry eat =
			entry("Eat", "<col=ffff00>Shark", net.runelite.api.gameval.ItemID.SHARK);
		net.runelite.api.MenuEntry[] entries = {use, eat};

		runNoteStepSwap(entries);

		assertEquals("a shark is not a notable harvest, so Eat stays put",
			eat, entries[entries.length - 1]);
	}

	/** With no note step current, a banana's Eat is left exactly where the game put it. */
	@Test
	public void aBananaIsLeftAloneWithNoNoteStepCurrent()
	{
		net.runelite.api.MenuEntry use =
			entry("Use", "<col=ffff00>Banana", net.runelite.api.gameval.ItemID.BANANA);
		net.runelite.api.MenuEntry eat =
			entry("Eat", "<col=ffff00>Banana", net.runelite.api.gameval.ItemID.BANANA);
		net.runelite.api.MenuEntry[] entries = {use, eat};

		runNoteStepSwap(entries, null);

		assertEquals("no note step, so nothing here touches the menu",
			eat, entries[entries.length - 1]);
	}

	/**
	 * Builds a two-entry menu (Use, then Eat as the default left click) for the given item,
	 * runs the swap with a {@code NOTE_AT_LEPRECHAUN} step current, and asserts Use is promoted.
	 */
	private void assertLeprechaunNoteSwapPromotesUse(int itemId, String defaultOption)
	{
		net.runelite.api.MenuEntry use = entry("Use", "<col=ffff00>Crop", itemId);
		net.runelite.api.MenuEntry defaultEntry =
			entry(defaultOption, "<col=ffff00>Crop", itemId);
		net.runelite.api.MenuEntry[] entries = {use, defaultEntry};

		runNoteStepSwap(entries);

		assertEquals("Use belongs under the left click, which is the last entry",
			use, entries[entries.length - 1]);
	}

	/** Runs {@code onPostMenuSort} with a {@code NOTE_AT_LEPRECHAUN} step current. */
	private void runNoteStepSwap(net.runelite.api.MenuEntry[] entries)
	{
		runNoteStepSwap(entries, GuideStep.of(GuideAction.NOTE_AT_LEPRECHAUN,
			com.dooglemaps.data.FarmingWorldData
				.getPatches(com.dooglemaps.data.PatchImplementation.FLOWER).get(0),
			"Note your crops with the tool leprechaun."));
	}

	/** As above, with an arbitrary current step (or none, for {@code null}). */
	private void runNoteStepSwap(net.runelite.api.MenuEntry[] entries, GuideStep currentStep)
	{
		net.runelite.api.Menu menu = Mockito.mock(net.runelite.api.Menu.class);
		when(menu.getMenuEntries()).thenReturn(entries);

		net.runelite.api.Client client = Mockito.mock(net.runelite.api.Client.class);
		when(client.getMenu()).thenReturn(menu);
		when(client.isMenuOpen()).thenReturn(false);

		com.dooglemaps.DoogleMapsConfig config =
			Mockito.mock(com.dooglemaps.DoogleMapsConfig.class);
		when(config.guidedMode()).thenReturn(true);
		when(config.herbUseLeftClick()).thenReturn(true);

		GuideTracker tracker = Mockito.mock(GuideTracker.class);
		when(tracker.getStatus()).thenReturn(statusWithHops());
		when(tracker.getCurrentStep()).thenReturn(currentStep);

		GuideMenuSwap swap = construct(GuideMenuSwap.class, client, tracker, config, seeds,
			Mockito.mock(com.dooglemaps.bank.RunLoadout.class), Mockito.mock(CarriedItems.class));
		swap.onPostMenuSort(new net.runelite.api.events.PostMenuSort());
	}

	/**
	 * Stocks the <b>seed</b> item rather than the planted one.
	 *
	 * <p>{@code stock} records {@code getPlantedItemID()}, which for a tree crop is the sapling —
	 * exactly the thing the box refuses. To put a calquat <i>seed</i> in a container the test has
	 * to say so, and the difference between the two is the whole of what this class now gets
	 * right.
	 */
	private void stockSeedItem(SeedSource source, Seed seed, int quantity)
	{
		java.util.List<Integer> items = new java.util.ArrayList<>();
		for (Seed known : Seed.values())
		{
			int held = seeds.getSeedCount(known, source);
			if (held > 0)
			{
				items.add(known.getItemID());
				items.add(held);
			}
		}
		items.add(seed.getItemID());
		items.add(quantity);

		int[] flat = new int[items.size()];
		for (int i = 0; i < flat.length; i++)
		{
			flat[i] = items.get(i);
		}
		seeds.record(source.getContainerId(), containerOf(flat));
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

	/**
	 * The fairy ring swap fires only while the drawn route actually goes through one.
	 *
	 * <h2>Why scoped to the route rather than the whole run</h2>
	 *
	 * A ring opens on Zanaris or on wherever you went last, and on a farm run it is neither — the
	 * code is in the hop Shortest Path reported, which the panel is already showing. Configure is
	 * the option that gets you there and the one the game buries.
	 *
	 * <p>Scoping it to the route naming a ring is the tighter half of the rule the other
	 * step-driven swaps follow: walk past a ring on an unrelated errand and its menu is the
	 * game's own. Core offers the same swap by name — {@code MenuEntrySwapper.swapFairyRing} —
	 * which is the precedent every swap here leans on.
	 */
	@Test
	public void theFairyRingSwapFollowsTheRoute()
	{
		assertTrue("Shortest Path's own wording for a ring hop",
			GuideMenuSwap.routeUsesAFairyRing(hops("Configure Fairy ring - C K Q")));
		assertTrue("case is not ours to rely on",
			GuideMenuSwap.routeUsesAFairyRing(hops("Enter FAIRY RING (BIP)")));
		assertFalse("a route with no ring leaves every ring alone",
			GuideMenuSwap.routeUsesAFairyRing(
				hops("Teleport to House", "Enter Catherby Portal")));
		assertFalse("and a route with no hops at all",
			GuideMenuSwap.routeUsesAFairyRing(hops()));
	}

	/**
	 * The menu target arrives coloured, so the match has to strip tags.
	 *
	 * <p>A plain {@code contains} would work or not depending on where the colour tag fell, which
	 * is the kind of thing that passes in a test written from a clean string and fails in front of
	 * a player.
	 */
	@Test
	public void aColouredMenuTargetStillNamesTheRing()
	{
		assertTrue(GuideMenuSwap.mentionsAFairyRing("<col=00ffff>Fairy ring"));
		assertTrue(GuideMenuSwap.mentionsAFairyRing("Fairy ring"));
		assertFalse(GuideMenuSwap.mentionsAFairyRing("<col=00ffff>Spirit tree"));
		assertFalse("null is not a ring", GuideMenuSwap.mentionsAFairyRing(null));
	}

	private static java.util.List<String> hops(String... hops)
	{
		return java.util.Arrays.asList(hops);
	}

	/**
	 * The garden ring: swapped off the live hop list, not off the tick snapshot.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"configure option not the default for fairy rings in POH"</i>. A house is an instance
	 * and Shortest Path cannot path from inside one, so {@code planner.getCurrentTransports()}
	 * — which is all {@link GuideStatus} carries — empties the moment the teleport lands. The
	 * swap asked the snapshot, got no hops, and concluded the route did not use a ring while the
	 * player stood in front of the one their route had picked.
	 *
	 * <p>The overlay was right the whole time, which is what made this confusing to watch: the
	 * ring was outlined and its left click was still Last-destination.
	 * {@code highlightHouseTeleports} already reads {@code GuideTracker.liveTransports}, which
	 * keeps the hop list that entered the house. Two callers, one question, two answers.
	 */
	@Test
	public void theGardenRingSwapsFromTheHopsThatEnteredTheHouse()
	{
		net.runelite.api.MenuEntry configure = entry("Configure", "<col=00ffff>Fairy ring");
		net.runelite.api.MenuEntry lastDestination =
			entry("Last-destination (A I S)", "<col=00ffff>Fairy ring");

		// The game's own order: the left click is the LAST entry, so Last-destination is on top.
		net.runelite.api.MenuEntry[] entries = {configure, lastDestination};
		net.runelite.api.Menu menu = Mockito.mock(net.runelite.api.Menu.class);
		when(menu.getMenuEntries()).thenReturn(entries);

		net.runelite.api.Client client = Mockito.mock(net.runelite.api.Client.class);
		when(client.getMenu()).thenReturn(menu);
		when(client.isMenuOpen()).thenReturn(false);

		com.dooglemaps.DoogleMapsConfig config =
			Mockito.mock(com.dooglemaps.DoogleMapsConfig.class);
		when(config.guidedMode()).thenReturn(true);
		when(config.fairyRingLeftClick()).thenReturn(true);

		GuideTracker tracker = Mockito.mock(GuideTracker.class);
		// Inside the house: the snapshot has no hops at all, exactly as the router leaves it...
		when(tracker.getStatus()).thenReturn(statusWithHops());
		// ...while the live list still knows what planned the trip in here.
		when(tracker.liveTransports())
			.thenReturn(hops("Teleport to House", "Configure Fairy ring - A I S"));

		GuideMenuSwap swap = construct(GuideMenuSwap.class, client, tracker, config, seeds,
			Mockito.mock(com.dooglemaps.bank.RunLoadout.class), Mockito.mock(CarriedItems.class));
		swap.onPostMenuSort(new net.runelite.api.events.PostMenuSort());

		assertEquals("Configure belongs under the left click, which is the last entry",
			configure, entries[entries.length - 1]);
	}

	/** A menu entry as the game builds one: an option, and a coloured target. */
	private static net.runelite.api.MenuEntry entry(String option, String target)
	{
		net.runelite.api.MenuEntry entry = Mockito.mock(net.runelite.api.MenuEntry.class);
		when(entry.getOption()).thenReturn(option);
		when(entry.getTarget()).thenReturn(target);
		return entry;
	}

	/** As above, for an inventory item - the swaps keyed on item id need one. */
	private static net.runelite.api.MenuEntry entry(String option, String target, int itemId)
	{
		net.runelite.api.MenuEntry entry = entry(option, target);
		when(entry.getItemId()).thenReturn(itemId);
		return entry;
	}

	private static GuideStatus statusWithHops(String... hops)
	{
		return new GuideStatus(java.util.Collections.emptyList(), true, false, 0,
			java.util.Arrays.asList(hops), null, null, java.util.Collections.emptyList(), null,
			null, java.util.Collections.emptyList(), java.util.Collections.emptyList(),
			null, java.util.Collections.emptyList(), java.util.Collections.emptySet(), null);
	}
}
