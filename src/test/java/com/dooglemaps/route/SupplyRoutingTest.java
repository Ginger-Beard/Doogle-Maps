package com.dooglemaps.route;

import com.dooglemaps.data.Seed;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.state.SeedSelectionStore;
import com.dooglemaps.state.SeedSource;
import com.google.gson.Gson;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Covers where a run goes to pick its seeds up.
 *
 * <p>The seed vault is the awkward case: there is exactly one, in the Farming Guild, and
 * Shortest Path routes to the <i>cheapest reachable</i> member of a target set. So offering it
 * alongside the banks meant the nearest bank always won and the vault seeds were never
 * collected — the run confidently walked to a bank that did not have them.
 */
public class SupplyRoutingTest
{
	private final Map<String, String> stored = new HashMap<>();

	private SeedInventoryStore seeds;
	private SeedSelectionStore selection;
	private RunPlanner planner;

	@Before
	public void setUp() throws Exception
	{
		Gson gson = new Gson();
		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString()))
			.thenAnswer((InvocationOnMock i) -> stored.get(i.getArgument(0) + "." + i.getArgument(1)));
		when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(int.class)))
			.thenReturn(99);
		doAnswer((InvocationOnMock i) ->
		{
			String key = i.getArgument(0) + "." + i.getArgument(1);
			Object value = i.getArgument(2);
			stored.put(key, String.valueOf(value));
			return null;
		}).when(configManager).setRSProfileConfiguration(anyString(), anyString(), Mockito.any());

		Client client = Mockito.mock(Client.class);
		seeds = construct(SeedInventoryStore.class, client, configManager, gson);
		selection = construct(SeedSelectionStore.class, configManager, gson,
			construct(com.dooglemaps.state.ContractState.class, configManager));

		com.dooglemaps.state.PlayerLocation playerLocation =
			construct(com.dooglemaps.state.PlayerLocation.class,
				Mockito.mock(net.runelite.api.Client.class));
		planner = construct(RunPlanner.class,
			construct(com.dooglemaps.state.AvailabilityProfile.class, configManager, gson,
				construct(com.dooglemaps.state.PatchStateStore.class, configManager, gson)),
			construct(PatchLocationStore.class, configManager, gson),
			construct(BankLocationStore.class, configManager, gson),
			selection,
			seeds,
			construct(com.dooglemaps.state.PatchStateStore.class, configManager, gson),
			Mockito.mock(com.dooglemaps.timer.GrowthTimer.class),
			Mockito.mock(ShortestPathIntegration.class),
			playerLocation, Mockito.mock(com.dooglemaps.bank.ToolNeeds.class),
			Mockito.mock(com.dooglemaps.state.ProtectedPatches.class),
			Mockito.mock(com.dooglemaps.state.PlantingGroups.class),
			Mockito.mock(com.dooglemaps.state.ProtectionSelectionStore.class),
			Mockito.mock(com.dooglemaps.state.RunTypeStore.class));
	}

	private void stock(SeedSource source, Seed seed, int quantity)
	{
		ItemContainer container = Mockito.mock(ItemContainer.class);
		when(container.getItems()).thenReturn(new Item[]{new Item(seed.getItemID(), quantity)});
		seeds.record(source.getContainerId(), container);
	}

	/** The reported bug: guam only in the vault, and the run set off for a bank. */
	@Test
	public void seedsOnlyInTheVaultRouteToTheVault()
	{
		selection.toggle(Seed.GUAM);
		stock(SeedSource.SEED_VAULT, Seed.GUAM, 100);

		assertEquals(Set.of(SeedSource.SEED_VAULT), planner.getSupplySources());
		assertEquals("the run must end up at the vault, not at whichever bank is nearest",
			Set.of(BankLocations.SEED_VAULT), supplyTargets());
	}

	/**
	 * A single seed in the bank must not outrank a full patch's worth in the vault.
	 *
	 * <p>The old check was "is it in the bank at all", so one stray seed sent the player to a
	 * bank that could not supply the run.
	 */
	@Test
	public void aPartialBankStackDoesNotBeatTheVault()
	{
		selection.toggle(Seed.WATERMELON);          // an allotment: three seeds per patch
		stock(SeedSource.BANK, Seed.WATERMELON, 1);
		stock(SeedSource.SEED_VAULT, Seed.WATERMELON, 50);

		assertEquals(Set.of(SeedSource.SEED_VAULT), planner.getSupplySources());
		assertEquals(Set.of(BankLocations.SEED_VAULT), supplyTargets());
	}

	/**
	 * When both are needed, the vault still wins — because it is also a bank.
	 *
	 * <p>The Farming Guild has a bank chest beside the vault, so one stop covers both. Offering
	 * the banks as alternatives would send the player to a bank and strand the vault seeds.
	 */
	@Test
	public void needingBothStillRoutesToTheGuild()
	{
		selection.toggle(Seed.GUAM);
		selection.toggle(Seed.RANARR);
		stock(SeedSource.BANK, Seed.GUAM, 100);
		stock(SeedSource.SEED_VAULT, Seed.RANARR, 100);

		assertEquals(Set.of(SeedSource.BANK, SeedSource.SEED_VAULT), planner.getSupplySources());
		assertEquals("the Farming Guild covers both", Set.of(BankLocations.SEED_VAULT),
			supplyTargets());
	}

	/** Nothing to fetch means no supply trip at all. */
	@Test
	public void seedsAlreadyCarriedNeedNoTrip()
	{
		selection.toggle(Seed.GUAM);
		stock(SeedSource.INVENTORY, Seed.GUAM, 10);

		assertTrue(planner.getSupplySources().isEmpty());
	}

	/** Ordinary case: everything is banked, so any bank will do. */
	@Test
	public void bankedSeedsRouteToTheBanks()
	{
		selection.toggle(Seed.GUAM);
		stock(SeedSource.BANK, Seed.GUAM, 100);

		assertEquals(Set.of(SeedSource.BANK), planner.getSupplySources());
		assertFalse("a bank run should not be sent to the Farming Guild specifically",
			supplyTargets().equals(Set.of(BankLocations.SEED_VAULT)));
		assertTrue(supplyTargets().size() > 1);
	}

	@SuppressWarnings("unchecked")
	private Set<WorldPoint> supplyTargets()
	{
		try
		{
			java.lang.reflect.Method method = RunPlanner.class.getDeclaredMethod("getSupplyTargets");
			method.setAccessible(true);
			return (Set<WorldPoint>) method.invoke(planner);
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException(e);
		}
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
