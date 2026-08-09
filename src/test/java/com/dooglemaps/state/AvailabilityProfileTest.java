package com.dooglemaps.state;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.google.gson.Gson;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The location filter is part of availability, not a display preference.
 *
 * <p>The Locations section began as row-hiding only, and the run kept navigating to a place
 * the player had switched off whole — while the per-patch switches that would have stopped it
 * were hidden along with the rows. Reported from play, at Harmony. The filter now sits inside
 * {@code isAvailable}, the one question every routing decision already asks, and this test
 * pins the two properties that matter: it wins over an explicit per-patch "on", and an
 * unwired profile behaves exactly as before.
 */
public class AvailabilityProfileTest
{
	/** Falador's herb patch: no unlock requirement, so only the toggles are in play. */
	private static final String FALADOR_HERB = "12083.4774";

	private AvailabilityProfile availability;
	private FarmPatch patch;

	@Before
	public void setUp() throws Exception
	{
		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		Gson gson = new Gson();
		PatchStateStore stateStore = construct(PatchStateStore.class, configManager, gson);
		availability = construct(AvailabilityProfile.class, configManager, gson, stateStore);
		patch = FarmingWorldData.getPatch(FALADOR_HERB);
		assertNotNull("fixture patch " + FALADOR_HERB + " no longer exists", patch);
	}

	@Test
	public void aHiddenLocationWinsOverAnExplicitPatchToggle()
	{
		availability.setAvailable(patch, true);
		assertTrue("explicitly on, location shown", availability.isAvailable(patch));

		availability.setLocationFilter(p -> false);
		assertFalse("hiding the location removes the patch however firmly it is ticked",
			availability.isAvailable(patch));

		availability.setLocationFilter(p -> true);
		assertTrue("showing the location again restores the explicit choice",
			availability.isAvailable(patch));
	}

	@Test
	public void anUnwiredFilterHidesNothing()
	{
		availability.setAvailable(patch, true);
		assertTrue("the default filter is everywhere, so tests and old callers are unchanged",
			availability.isAvailable(patch));
	}
}
