package com.dooglemaps.guide;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.data.Seed;
import com.dooglemaps.route.RunPlanner;
import com.dooglemaps.state.AvailabilityProfile;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.state.PlayerLocation;
import com.dooglemaps.state.SeedSource;
import com.dooglemaps.timer.GrowthTimer;
import com.google.gson.Gson;
import java.lang.reflect.Constructor;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * The fruit tree run that had a palm sapling follow the player round Gielinor, as a fixture.
 *
 * <h2>What happened, in the log's own order</h2>
 *
 * Seeds picked in click order palm, dragonfruit, papaya; palm ticked to protect, at fifteen
 * papayas a patch. At the bank the account had thirteen papayas, so palm was unaffordable, the
 * allocation skipped it, and the run set off with seven papaya saplings and twenty-eight palms
 * still in the seed vault — all correct. Then the first tree was picked for six more papayas.
 * Nineteen covers a palm, so on the next tick the allocation — rebuilt every tick against
 * everything the account owns <i>anywhere</i> — gave a patch to a sapling in the vault, and gave
 * it to whichever patch the player was standing at, because that patch has first claim. Lletya and
 * then the Gnome Stronghold each answered "Skipping - the palm sapling is in the seed vault" with
 * six papaya saplings sitting in the pack.
 *
 * <h2>Why it is a fixture rather than four copies of a setUp</h2>
 *
 * Four tests want this exact run at different moments of it — before the supply leg and after,
 * with the payments short and with them made up, with a papaya to plant and without. The
 * interesting line in each of them should be the two or three calls that set that moment up, not
 * two hundred lines of wiring they share. The numbers themselves are deliberately the reported
 * ones, so a test that fails here fails about the run that was actually played.
 *
 * <p>Not a test itself: no {@code @Test} method, so the runner walks past it.
 */
class FruitTreeRun
{
	/** Lletya's fruit tree, and the patch the player was standing at when palm jumped in. */
	static final String LLETYA = "9265.4771";

	/** The next stop on that run, which the palm then followed them to. */
	static final String GNOME_STRONGHOLD = "9781.4772";

	static final WorldPoint AT_LLETYA = new WorldPoint(2330, 3170, 0);
	static final WorldPoint AT_GNOME_STRONGHOLD = new WorldPoint(2475, 3445, 0);

	/** Varrock, which is on no fruit tree run: somewhere a supply leg can properly begin. */
	static final WorldPoint AWAY_FROM_THE_PATCHES = new WorldPoint(3210, 3424, 0);

	/** Weeds at stage zero: a patch raked out and waiting for a sapling. */
	private static final int RAKED_AND_EMPTY = 3;

	/** Fifteen papayas a palm — {@code ProtectionPayment.PALM}, spelled out for the reader. */
	static final int PAPAYAS_PER_PALM = 15;

	final PatchStateStore stateStore;
	final AvailabilityProfile availability;
	final PlayerLocation playerLocation;
	final RunPlanner planner;
	final GuideTracker tracker;

	final com.dooglemaps.state.SeedInventoryStore seeds;
	final com.dooglemaps.state.SeedSelectionStore selection;
	final com.dooglemaps.state.ProtectionSelectionStore protection;
	final com.dooglemaps.bank.BankContents bank;
	final CarriedItems carried;

	private final net.runelite.api.Player player;

	FruitTreeRun() throws Exception
	{
		Map<String, String> stored = new HashMap<>();
		Gson gson = new Gson();

		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString()))
			.thenAnswer(i -> stored.get(i.getArgument(0) + "." + i.getArgument(1)));
		when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(int.class)))
			.thenAnswer(i -> null);
		doAnswer(i ->
		{
			stored.put(i.getArgument(0) + "." + i.getArgument(1),
				String.valueOf((Object) i.getArgument(2)));
			return null;
		}).when(configManager).setRSProfileConfiguration(anyString(), anyString(), any());

		net.runelite.client.callback.ClientThread clientThread =
			Mockito.mock(net.runelite.client.callback.ClientThread.class);
		doAnswer(i ->
		{
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));

		stateStore = construct(PatchStateStore.class, configManager, gson);
		availability = construct(AvailabilityProfile.class, configManager, gson, stateStore);
		com.dooglemaps.route.PatchLocationStore locations =
			construct(com.dooglemaps.route.PatchLocationStore.class, configManager, gson);
		com.dooglemaps.route.BankLocationStore banks =
			construct(com.dooglemaps.route.BankLocationStore.class, configManager, gson);
		GrowthTimer timer = construct(GrowthTimer.class, configManager);
		com.dooglemaps.route.ShortestPathIntegration router =
			construct(com.dooglemaps.route.ShortestPathIntegration.class,
				Mockito.mock(EventBus.class), clientThread);

		stateStore.load();
		availability.load();
		locations.load();
		banks.load();

		net.runelite.api.Client client = Mockito.mock(net.runelite.api.Client.class);
		player = Mockito.mock(net.runelite.api.Player.class);
		when(client.getLocalPlayer()).thenReturn(player);
		playerLocation = construct(PlayerLocation.class, client);

		seeds = Mockito.mock(com.dooglemaps.state.SeedInventoryStore.class);
		when(seeds.getFarmingLevel()).thenReturn(99);

		// The click order the run was picked in, which is the ranking the allocation applies.
		selection = Mockito.mock(com.dooglemaps.state.SeedSelectionStore.class);
		LinkedHashSet<Seed> picked = new LinkedHashSet<>();
		picked.add(Seed.PALM);
		picked.add(Seed.DRAGONFRUIT);
		picked.add(Seed.PAPAYA);
		when(selection.getSelectedFor(any(PlantingGroup.class))).thenReturn(picked);
		when(selection.getSelected()).thenReturn(picked);

		// Only the palm is being paid for, which is what makes the payment budget bite.
		protection = Mockito.mock(com.dooglemaps.state.ProtectionSelectionStore.class);
		when(protection.isProtecting(any(PlantingGroup.class), eq(Seed.PALM))).thenReturn(true);

		bank = Mockito.mock(com.dooglemaps.bank.BankContents.class);
		carried = Mockito.mock(CarriedItems.class);
		// Room in the pack, so nothing here reads as a run that needs to go and deposit.
		when(carried.getFreeSlots()).thenReturn(10);

		com.dooglemaps.state.PlantingGroups groups =
			Mockito.mock(com.dooglemaps.state.PlantingGroups.class);
		PlantingGroup fruit = Mockito.mock(PlantingGroup.class);
		when(fruit.getKey()).thenReturn("fruit_tree");
		when(fruit.getType()).thenReturn(PatchImplementation.FRUIT_TREE);
		when(groups.groupFor(any())).thenReturn(fruit);
		when(groups.patchesIn(any()))
			.thenReturn(java.util.Arrays.asList(patch(LLETYA), patch(GNOME_STRONGHOLD)));

		com.dooglemaps.state.CompostSelectionStore compost =
			Mockito.mock(com.dooglemaps.state.CompostSelectionStore.class);
		when(compost.get(any(PlantingGroup.class)))
			.thenReturn(com.dooglemaps.data.CompostTier.NONE);

		com.dooglemaps.state.RunTypeStore runOptions =
			Mockito.mock(com.dooglemaps.state.RunTypeStore.class);
		when(runOptions.isSelected(any())).thenReturn(true);

		planner = construct(RunPlanner.class, availability, locations, banks, selection,
			seeds, stateStore, timer, router, playerLocation,
			Mockito.mock(com.dooglemaps.bank.ToolNeeds.class),
			Mockito.mock(com.dooglemaps.state.ProtectedPatches.class),
			groups, protection, runOptions,
			Mockito.mock(com.dooglemaps.state.CompostRunStore.class),
			Mockito.mock(com.dooglemaps.DoogleMapsConfig.class),
			bank, carried);

		tracker = trackerWith(planner, stateStore, timer, playerLocation, selection, seeds,
			groups, compost, runOptions, protection, bank, carried, client);
	}

	// ------------------------------------------------------------------ arranging

	/**
	 * Where this crop's saplings are, and every count that follows from it.
	 *
	 * <p>Saplings throughout, never the unpotted seed, because that is what the reported run
	 * held — twenty-eight palm <i>saplings</i> in the vault. Keeping the two forms identical here
	 * means no test accidentally turns on the potting errand, which is a different story.
	 */
	void saplings(Seed seed, int inThePack, int inTheBank, int inTheVault)
	{
		when(seeds.getPlantable(seed, SeedSource.INVENTORY)).thenReturn(inThePack);
		when(seeds.getPlantable(seed, SeedSource.SEED_BOX)).thenReturn(0);
		when(seeds.getPlantable(seed, SeedSource.BANK)).thenReturn(inTheBank);
		when(seeds.getPlantable(seed, SeedSource.SEED_VAULT)).thenReturn(inTheVault);

		when(seeds.getCount(seed, SeedSource.INVENTORY)).thenReturn(inThePack);
		when(seeds.getCount(seed, SeedSource.SEED_BOX)).thenReturn(0);
		when(seeds.getCount(seed, SeedSource.BANK)).thenReturn(inTheBank);
		when(seeds.getCount(seed, SeedSource.SEED_VAULT)).thenReturn(inTheVault);

		when(seeds.getPlantableOnHand(seed)).thenReturn(inThePack);
		when(seeds.getOwnedPlantable(seed)).thenReturn(inThePack + inTheBank + inTheVault);
		when(seeds.getOwned(seed)).thenReturn(inThePack + inTheBank + inTheVault);
	}

	/** Papayas to pay the farmer with, split the way the budget counts them. */
	void papayasToPayWith(int inTheBank, int inThePack)
	{
		when(bank.getCount(net.runelite.api.gameval.ItemID.PAPAYA)).thenReturn(inTheBank);
		when(carried.getCountIncludingNoted(net.runelite.api.gameval.ItemID.PAPAYA))
			.thenReturn(inThePack);
	}

	/** Both fruit tree patches raked out and waiting, and on this account's map. */
	void bothPatchesAreEmpty()
	{
		for (String key : new String[]{LLETYA, GNOME_STRONGHOLD})
		{
			FarmPatch p = patch(key);
			ProduceState decoded = p.getImplementation().forVarbitValue(RAKED_AND_EMPTY);
			assertNotNull("varbit " + RAKED_AND_EMPTY + " does not decode for " + key, decoded);
			stateStore.recordVarbit(p, RAKED_AND_EMPTY, decoded);
			availability.setAvailable(p, true);
		}
	}

	void standAt(WorldPoint tile)
	{
		when(player.getWorldLocation()).thenReturn(tile);
		playerLocation.onGameTick(null);
	}

	/**
	 * Starts the run where the player is standing, which defers any shopping.
	 *
	 * <p>Standing on work beats going shopping — {@code RunPlanner.start} says so in those words —
	 * so a run begun at a patch owes itself a supply trip and has not armed one. That is the state
	 * in which the guide still speaks about the stop <i>and</i> the supply leg is still to come,
	 * which is the only way to watch the allocation from the near side of the rule.
	 */
	void startWhereYouAre()
	{
		planner.start(EnumSet.of(PatchImplementation.FRUIT_TREE));
	}

	/**
	 * Sets off from a bank with whatever was taken, and puts the run past its supply leg.
	 *
	 * <p>Started away from every patch, so the opening leg arms properly rather than being
	 * deferred, and then waived — which is the player pressing skip at the bank, and a no-op on a
	 * run that had nothing to collect. Either way the run is now travelling on what it carries,
	 * and the assertion is what makes that a fixture step rather than a hope.
	 */
	void setOffFromTheBank()
	{
		standAt(AWAY_FROM_THE_PATCHES);
		planner.start(EnumSet.of(PatchImplementation.FRUIT_TREE));
		planner.waiveBankLeg();
		tracker.onGameTick(null);
		assertTrue("fixture: the run should now be past its supply leg",
			tracker.isSupplyLegDone());
	}

	/** Walks to a tile and lets the guide have its tick there. */
	void arriveAt(WorldPoint tile)
	{
		standAt(tile);
		tracker.onGameTick(null);
	}

	// -------------------------------------------------------------------- reading

	List<GuideStep> steps()
	{
		return tracker.stepsHere();
	}

	List<String> skips()
	{
		return tracker.getStatus().getSkipped();
	}

	/** The step this tick offers for one patch, or null if the guide has nothing to say about it. */
	GuideStep stepFor(String patchKey)
	{
		for (GuideStep step : steps())
		{
			if (step.getPatch() != null && patchKey.equals(step.getPatch().getKey()))
			{
				return step;
			}
		}
		return null;
	}

	static FarmPatch patch(String key)
	{
		FarmPatch patch = FarmingWorldData.getPatch(key);
		assertNotNull("fixture patch " + key + " no longer exists", patch);
		return patch;
	}

	/** The real collaborators injected by type; everything else a mock. */
	private static GuideTracker trackerWith(Object... real) throws Exception
	{
		Constructor<?> constructor = GuideTracker.class.getDeclaredConstructors()[0];
		constructor.setAccessible(true);

		Class<?>[] types = constructor.getParameterTypes();
		Object[] args = new Object[types.length];
		for (int i = 0; i < types.length; i++)
		{
			for (Object candidate : real)
			{
				if (types[i].isInstance(candidate))
				{
					args[i] = candidate;
					break;
				}
			}
			if (args[i] == null)
			{
				args[i] = Mockito.mock(types[i]);
			}
		}
		return (GuideTracker) constructor.newInstance(args);
	}
}
