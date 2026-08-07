package com.dooglemaps.guide;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.Produce;
import com.dooglemaps.route.RunStop;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Covers the order of the errands bundled into a leprechaun visit.
 *
 * <p>Reported from play: went to him for ultracompost carrying four limpwurts and was never told
 * to note them. The required order is <b>note, deposit, withdraw</b> — and it is not arbitrary.
 * Noting and depositing both <i>free</i> inventory slots and withdrawing compost <i>fills</i>
 * them, so the other way round can leave you taking four buckets into a pack that still holds
 * four limpwurts.
 *
 * <p>Worth a test rather than trusting the reading, because the order is three lines of code that
 * would regress silently — the steps would all still be present and only their sequence wrong.
 */
public class LeprechaunErrandOrderTest
{
	private GuideTracker tracker;
	private CarriedItems carried;
	private com.dooglemaps.timer.GrowthTimer growthTimer;

	@Before
	public void setUp() throws Exception
	{
		carried = construct(CarriedItems.class, Mockito.mock(net.runelite.api.Client.class));
		growthTimer = Mockito.mock(com.dooglemaps.timer.GrowthTimer.class);
		tracker = trackerWith(carried, growthTimer);
	}

	/** The whole point: everything handed over before anything is taken out. */
	@Test
	public void noteThenDepositThenWithdraw() throws Exception
	{
		carrying(Produce.LIMPWURT.getItemID(), 4, ItemID.BUCKET_EMPTY, 3);

		List<GuideStep> steps = new ArrayList<>();
		steps.add(GuideStep.atLeprechaun(GuideAction.WITHDRAW_COMPOST, somePatch(),
			ItemID.BUCKET_ULTRACOMPOST, null, "Withdraw ultracompost."));

		bundle(steps);

		assertEquals("note first - it frees the slots the buckets are about to fill",
			GuideAction.NOTE_AT_LEPRECHAUN, steps.get(0).getAction());
		assertEquals("then hand the empties back", GuideAction.RETURN_BUCKETS,
			steps.get(1).getAction());
		assertEquals("and only then take anything out", GuideAction.WITHDRAW_COMPOST,
			steps.get(2).getAction());
	}

	/**
	 * Crops in the pack are noted even when it is nowhere near full.
	 *
	 * <p>Four limpwurts in a 28-slot pack raises no step of its own; the whole reason this
	 * bundling exists is that the walk to him has already been paid for by something else.
	 */
	@Test
	public void aPartlyFullPackStillGetsNoted() throws Exception
	{
		carrying(Produce.LIMPWURT.getItemID(), 4);

		List<GuideStep> steps = new ArrayList<>();
		steps.add(GuideStep.atLeprechaun(GuideAction.WITHDRAW_COMPOST, somePatch(),
			ItemID.BUCKET_ULTRACOMPOST, null, "Withdraw ultracompost."));

		bundle(steps);

		assertEquals(GuideAction.NOTE_AT_LEPRECHAUN, steps.get(0).getAction());
	}

	/**
	 * A leprechaun visit further down the list still bundles the errands.
	 *
	 * <p>The reported bug. Mid-harvest with a compost withdrawal a couple of steps away, the visit
	 * is already certain — but the errands were only hoisted when the leprechaun step happened to
	 * be the <i>current</i> one, so they went to the bottom of the list where the panel's
	 * four-line window never showed them.
	 *
	 * <p>They belong at the visit, not at the top: the harvest in front of you finishes first,
	 * and the noting appears as you set off for him.
	 */
	@Test
	public void aLaterLeprechaunVisitStillBundles() throws Exception
	{
		carrying(Produce.LIMPWURT.getItemID(), 4, ItemID.BUCKET_EMPTY, 2);

		List<GuideStep> steps = new ArrayList<>();
		steps.add(GuideStep.of(GuideAction.HARVEST, somePatch(), "Harvest the limpwurt."));
		steps.add(GuideStep.atLeprechaun(GuideAction.WITHDRAW_COMPOST, somePatch(),
			ItemID.BUCKET_ULTRACOMPOST, null, "Withdraw ultracompost."));

		bundle(steps);

		assertEquals("the patch in front of you is not interrupted",
			GuideAction.HARVEST, steps.get(0).getAction());
		assertEquals("then note, on the way to him", GuideAction.NOTE_AT_LEPRECHAUN,
			steps.get(1).getAction());
		assertEquals("then deposit", GuideAction.RETURN_BUCKETS, steps.get(2).getAction());
		assertEquals("and only then withdraw", GuideAction.WITHDRAW_COMPOST,
			steps.get(3).getAction());
	}

	/**
	 * Nothing sending you to him means the errands stay at the end, where they were.
	 *
	 * <p>Interrupting a harvest to tidy up is still worse than the tidying is worth — the change
	 * was only ever about visits that were happening anyway.
	 */
	@Test
	public void errandsStayAtTheEndWhenYouAreNotAtHim() throws Exception
	{
		carrying(Produce.LIMPWURT.getItemID(), 4);

		List<GuideStep> steps = new ArrayList<>();
		steps.add(GuideStep.of(GuideAction.HARVEST, somePatch(), "Harvest the limpwurt."));

		bundle(steps);

		assertEquals("harvesting comes first", GuideAction.HARVEST, steps.get(0).getAction());
		assertEquals("noting waits until the patch work is done",
			GuideAction.NOTE_AT_LEPRECHAUN, steps.get(1).getAction());
	}

	/** A full pack already raises its own note step; bundling must not add a second. */
	@Test
	public void theNoteIsNotAddedTwice() throws Exception
	{
		carrying(Produce.LIMPWURT.getItemID(), 4);

		List<GuideStep> steps = new ArrayList<>();
		steps.add(GuideStep.atLeprechaun(GuideAction.NOTE_AT_LEPRECHAUN, somePatch(),
			Produce.LIMPWURT.getItemID(), null, "Your inventory is full - note the limpwurt."));

		bundle(steps);

		long notes = steps.stream()
			.filter(step -> step.getAction() == GuideAction.NOTE_AT_LEPRECHAUN)
			.count();
		assertEquals("one trip, one instruction", 1, notes);
	}

	/**
	 * Which pane of the leprechaun's interface each step is clicked on.
	 *
	 * <h2>This assertion has now been wrong three times, and the reason is always the same</h2>
	 *
	 * Each attempt reasoned from a model of the interface rather than from what is on screen:
	 *
	 * <ol>
	 *   <li><b>By direction</b> — handing over versus taking out. Put returning buckets with noting
	 *       a crop, which is wrong because noting happens with his interface shut.</li>
	 *   <li><b>By which interface is open</b> — "his store opens, so it is a store click". Lit up
	 *       <i>his</i> bucket slot, showing the thousand he already holds, at the moment the
	 *       instruction is "give him yours".</li>
	 *   <li><b>By store versus inventory</b> — so a bucket return became an ordinary inventory
	 *       click. Lit nothing at all, because his interface covers the inventory.</li>
	 * </ol>
	 *
	 * <p>What is actually there, reported from play: <b>two panes at once</b>. His store in a pane
	 * of its own, and a second pane laid over your inventory listing the same categories — bucket,
	 * composts, tools, watering can — holding <b>your</b> items. So both withdrawing and depositing
	 * are slot lookups inside one interface, and the only question is whose column.
	 *
	 * <p>Noting a crop remains the odd one out and always was: it happens with his interface shut,
	 * so it is a genuine inventory click.
	 */
	@Test
	public void withdrawingUsesHisPaneAndReturningUsesYours() throws Exception
	{
		carrying(Produce.LIMPWURT.getItemID(), 4, ItemID.BUCKET_EMPTY, 3);

		List<GuideStep> steps = new ArrayList<>();
		GuideStep withdraw = GuideStep.atLeprechaun(GuideAction.WITHDRAW_COMPOST, somePatch(),
			ItemID.BUCKET_ULTRACOMPOST, null, "Withdraw ultracompost.");
		steps.add(withdraw);
		bundle(steps);

		GuideStep note = stepWith(steps, GuideAction.NOTE_AT_LEPRECHAUN);
		GuideStep buckets = stepWith(steps, GuideAction.RETURN_BUCKETS);

		assertEquals("his interface is shut, so the crop is used from the pack",
			false, note.itemIsInStore());

		assertEquals("both sides of his interface are slot lookups, not inventory scans",
			true, withdraw.itemIsInStore());
		assertEquals(true, buckets.itemIsInStore());

		assertEquals("you take out of his column",
			false, withdraw.itemIsOnYourSideOfTheStore());
		assertEquals("and put into your own",
			true, buckets.itemIsOnYourSideOfTheStore());

		assertEquals("and it is the empty bucket that gets pointed at",
			ItemID.BUCKET_EMPTY, buckets.getItemId());
		assertEquals("all three still happen at him, which is the separate question",
			true, note.isAtLeprechaun() && buckets.isAtLeprechaun() && withdraw.isAtLeprechaun());
	}

	/**
	 * A patch waiting to be checked keeps the tidying-up errands away.
	 *
	 * <h2>What it looked like</h2>
	 *
	 * "Check the health of the magic tree", and directly beneath it "note your crops with the
	 * leprechaun <i>before moving on</i>" — at a patch not yet started. Checking is never the last
	 * thing you do at a tree, bush or cactus: a harvest and a clear follow it, and neither exists as
	 * a step until the check happens, so the errands had nothing to sit behind.
	 *
	 * <p>Reported from play on a mid-run tree contract.
	 */
	@Test
	public void tidyingUpWaitsForAPatchStillToBeChecked() throws Exception
	{
		carrying(Produce.LIMPWURT.getItemID(), 4);
		checkPending();

		List<GuideStep> steps = new ArrayList<>();
		steps.add(GuideStep.of(GuideAction.CHECK_HEALTH, somePatch(),
			"Check the health of the magic tree."));

		bundle(steps);

		assertEquals("nothing may come between the check and the work behind it",
			1, steps.size());
	}

	/**
	 * Unless something is taking you to him anyway, in which case bundling still wins.
	 *
	 * <p>A compost withdrawal makes the visit certain whatever else is outstanding, and folding the
	 * errands into a trip already happening is the whole reason this method exists.
	 */
	@Test
	public void acertainVisitStillBundlesDespiteAPendingCheck() throws Exception
	{
		carrying(Produce.LIMPWURT.getItemID(), 4, ItemID.BUCKET_EMPTY, 2);
		checkPending();

		List<GuideStep> steps = new ArrayList<>();
		steps.add(GuideStep.of(GuideAction.CHECK_HEALTH, somePatch(),
			"Check the health of the magic tree."));
		steps.add(GuideStep.atLeprechaun(GuideAction.WITHDRAW_COMPOST, somePatch(),
			ItemID.BUCKET_ULTRACOMPOST, null, "Withdraw ultracompost."));

		bundle(steps);

		assertEquals("the check is still what you do first",
			GuideAction.CHECK_HEALTH, steps.get(0).getAction());
		assertEquals("and the errands fold into the trip that was happening anyway",
			GuideAction.NOTE_AT_LEPRECHAUN, steps.get(1).getAction());
	}

	/** Puts the stop's patch into the grown-but-unchecked state. */
	private void checkPending()
	{
		com.dooglemaps.timer.PatchProjection projection =
			Mockito.mock(com.dooglemaps.timer.PatchProjection.class, Mockito.RETURNS_DEEP_STUBS);
		when(projection.needsHealthCheck()).thenReturn(true);
		when(growthTimer.project(Mockito.any(), Mockito.any())).thenReturn(projection);
	}

	private static GuideStep stepWith(List<GuideStep> steps, GuideAction action)
	{
		return steps.stream()
			.filter(step -> step.getAction() == action)
			.findFirst()
			.orElseThrow(() -> new AssertionError("no " + action + " step was produced"));
	}

	// ------------------------------------------------------------------- helpers

	private void bundle(List<GuideStep> steps) throws Exception
	{
		Method method = GuideTracker.class.getDeclaredMethod(
			"appendLeprechaunErrands", List.class, RunStop.class);
		method.setAccessible(true);
		method.invoke(tracker, steps, stopHolding(somePatch()));
	}

	/** Pairs of item id and quantity, put into the inventory container. */
	private void carrying(int... idThenQuantity)
	{
		Item[] items = new Item[idThenQuantity.length / 2];
		for (int i = 0; i < items.length; i++)
		{
			items[i] = new Item(idThenQuantity[i * 2], idThenQuantity[i * 2 + 1]);
		}

		ItemContainer container = Mockito.mock(ItemContainer.class);
		when(container.getItems()).thenReturn(items);
		carried.record(container);
	}

	private static FarmPatch somePatch()
	{
		return FarmingWorldData.getPatches(
			com.dooglemaps.data.PatchImplementation.FLOWER).get(0);
	}

	private static RunStop stopHolding(FarmPatch patch)
	{
		RunStop stop = Mockito.mock(RunStop.class);
		when(stop.getName()).thenReturn("Falador");
		when(stop.getPatches()).thenReturn(Collections.singletonList(patch));
		when(stop.getServiced()).thenReturn(new java.util.HashSet<>());
		return stop;
	}

	/**
	 * A tracker with everything mocked except what the errands actually read.
	 *
	 * <p>Only {@code CarriedItems} matters here — the errand list is a function of the pack and
	 * nothing else — so the rest are mocks rather than fixtures.
	 */
	private static GuideTracker trackerWith(CarriedItems carried,
		com.dooglemaps.timer.GrowthTimer growthTimer) throws Exception
	{
		Constructor<?> constructor = GuideTracker.class.getDeclaredConstructors()[0];
		constructor.setAccessible(true);

		Class<?>[] types = constructor.getParameterTypes();
		Object[] args = new Object[types.length];
		for (int i = 0; i < types.length; i++)
		{
			if (types[i] == CarriedItems.class)
			{
				args[i] = carried;
			}
			else if (types[i] == com.dooglemaps.timer.GrowthTimer.class)
			{
				args[i] = growthTimer;
			}
			else
			{
				args[i] = Mockito.mock(types[i]);
			}
		}
		return (GuideTracker) constructor.newInstance(args);
	}

	@SuppressWarnings("unchecked")
	private static <T> T construct(Class<T> type, Object... args) throws Exception
	{
		Constructor<?> constructor = type.getDeclaredConstructors()[0];
		constructor.setAccessible(true);
		return (T) constructor.newInstance(args);
	}
}
