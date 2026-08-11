package com.dooglemaps.state;

import com.dooglemaps.DoogleMapsConfig;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * The one daily allowance nothing in the client can count.
 *
 * <p>Shortest Path gates the Ardougne cloak's farm teleport on varbit 6069, which is a single
 * bit of varplayer 635 — so the gate is always true and the router keeps planning through a
 * cloak that has nothing left. The game saying so outright is the only fact available; these
 * pin what is done with it. See {@link DailyTeleports}.
 */
public class DailyTeleportsTest
{
	private static final String KEY = "dailyTeleportSpent.ardougneCloakFarm";

	private Map<String, String> stored;
	private DailyTeleports dailyTeleports;

	@Before
	public void setUp()
	{
		stored = new HashMap<>();

		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		Mockito.when(configManager.getRSProfileConfiguration(
			eq(DoogleMapsConfig.GROUP), anyString(), eq(String.class)))
			.thenAnswer(i -> stored.get(i.<String>getArgument(1)));
		Mockito.doAnswer(i ->
		{
			stored.put(i.getArgument(1), String.valueOf(i.<Object>getArgument(2)));
			return null;
		}).when(configManager).setRSProfileConfiguration(
			eq(DoogleMapsConfig.GROUP), anyString(), any());
		Mockito.doAnswer(i ->
		{
			stored.remove(i.<String>getArgument(1));
			return null;
		}).when(configManager).unsetRSProfileConfiguration(
			eq(DoogleMapsConfig.GROUP), anyString());

		dailyTeleports = construct(DailyTeleports.class, configManager);
	}

	@Test
	public void nothingIsSpentUntilTheGameSaysSo()
	{
		assertFalse(dailyTeleports.isSpent(DailyTeleports.Teleport.ARDOUGNE_CLOAK_FARM));
		assertFalse(dailyTeleports.isHopSpent("Ardougne cloak: Ardougne Farm"));
	}

	@Test
	public void theRefusalIsRememberedForTheRestOfTheDay()
	{
		dailyTeleports.observeExhausted(DailyTeleports.Teleport.ARDOUGNE_CLOAK_FARM);

		assertTrue(dailyTeleports.isSpent(DailyTeleports.Teleport.ARDOUGNE_CLOAK_FARM));
		assertTrue("the router's own wording for the hop",
			dailyTeleports.isHopSpent("Ardougne cloak: Ardougne Farm"));
		assertTrue("and the item, for the travel hint's table",
			dailyTeleports.isItemSpent(net.runelite.api.gameval.ItemID.ARDY_CAPE_HARD));
	}

	/**
	 * The Monastery teleport is unlimited on every tier and must survive the farm one running
	 * out — they are two hops off one cloak, and only one of them has an allowance.
	 */
	@Test
	public void theUnlimitedMonasteryHopOffTheSameCloakIsUntouched()
	{
		dailyTeleports.observeExhausted(DailyTeleports.Teleport.ARDOUGNE_CLOAK_FARM);

		assertFalse(dailyTeleports.isHopSpent("Ardougne cloak: Kandarin Monastery"));
	}

	/** The elite cloak's farm teleport is unlimited, so the item is never one of these. */
	@Test
	public void theEliteCloakIsNotInTheTable()
	{
		dailyTeleports.observeExhausted(DailyTeleports.Teleport.ARDOUGNE_CLOAK_FARM);

		assertFalse(dailyTeleports.isItemSpent(
			net.runelite.api.gameval.ItemID.ARDY_CAPE_ELITE));
	}

	/**
	 * The latch is a date rather than a flag, so the game's own reset clears it with nothing
	 * having to notice the moment it happens — which matters for a client left logged in
	 * through midnight UTC.
	 */
	@Test
	public void aLatchFromYesterdayHasExpired()
	{
		stored.put(KEY, LocalDate.now(ZoneOffset.UTC).minusDays(1).toString());

		assertFalse(dailyTeleports.isSpent(DailyTeleports.Teleport.ARDOUGNE_CLOAK_FARM));
	}

	@Test
	public void aResetForgetsTheLatch()
	{
		dailyTeleports.observeExhausted(DailyTeleports.Teleport.ARDOUGNE_CLOAK_FARM);
		dailyTeleports.clear();

		assertFalse(dailyTeleports.isSpent(DailyTeleports.Teleport.ARDOUGNE_CLOAK_FARM));
	}
}
