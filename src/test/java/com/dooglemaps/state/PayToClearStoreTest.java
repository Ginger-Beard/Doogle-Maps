package com.dooglemaps.state;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.data.Seed;
import com.dooglemaps.timer.GrowthTimer;
import com.dooglemaps.timer.PatchProjection;
import com.google.gson.Gson;
import java.util.HashMap;
import java.util.Map;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * The pay-to-clear toggle: a flat set keyed on the crop, and the lookup that keys it on the
 * crop <b>standing</b> in a patch rather than on whatever is about to be planted there.
 */
public class PayToClearStoreTest
{
	private final Map<String, String> stored = new HashMap<>();
	private PayToClearStore store;
	private PatchStateStore stateStore;
	private GrowthTimer timer;

	@Before
	public void setUp() throws Exception
	{
		Gson gson = new Gson();
		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString()))
			.thenAnswer((InvocationOnMock i) -> stored.get(i.<String>getArgument(1)));
		doAnswer(i ->
		{
			Object value = i.getArgument(2);
			stored.put(i.<String>getArgument(1), String.valueOf(value));
			return null;
		}).when(configManager).setRSProfileConfiguration(
			anyString(), anyString(), any());

		store = new PayToClearStore(configManager, gson);

		stateStore = construct(PatchStateStore.class, configManager, gson);
		timer = construct(GrowthTimer.class, configManager);
	}

	@Test
	public void aCropIsChoppedYourselfUntilYouSaySoOtherwise()
	{
		assertFalse(store.isPayingFor(Seed.MAGIC));
	}

	@Test
	public void aTickedCropSurvivesAReload()
	{
		store.setPayingFor(Seed.MAGIC, true);

		// A fresh store reading the same backing config, so the round trip is exercised through
		// the exact JSON save() wrote rather than through the live in-memory set.
		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString()))
			.thenAnswer((InvocationOnMock i) -> stored.get(i.<String>getArgument(1)));
		PayToClearStore reloaded = new PayToClearStore(configManager, new Gson());
		reloaded.load();

		assertTrue("the choice was saved, so a fresh store reading the same config sees it",
			reloaded.isPayingFor(Seed.MAGIC));
	}

	@Test
	public void togglingOffRemovesIt()
	{
		store.setPayingFor(Seed.MAGIC, true);
		store.setPayingFor(Seed.MAGIC, false);

		assertFalse(store.isPayingFor(Seed.MAGIC));
	}

	@Test
	public void aChangeFiresTheListeners()
	{
		int[] fired = {0};
		store.addChangeListener(() -> fired[0]++);

		store.setPayingFor(Seed.MAGIC, true);
		assertEquals(1, fired[0]);

		store.setPayingFor(Seed.MAGIC, false);
		assertEquals(2, fired[0]);
	}

	/** Setting the same answer twice is not a change, and must not write or notify again. */
	@Test
	public void settingTheSameAnswerTwiceDoesNothing()
	{
		store.setPayingFor(Seed.MAGIC, true);
		int[] fired = {0};
		store.addChangeListener(() -> fired[0]++);

		store.setPayingFor(Seed.MAGIC, true);

		assertEquals("nothing changed, so nothing fired", 0, fired[0]);
	}

	/**
	 * The owner's own scenario: a magic tree standing, a maple ticked for the choice, and the
	 * question resolved off the crop in the ground rather than the crop about to replace it.
	 */
	@Test
	public void theStandingCropDecidesAndNotTheSeedGoingIn()
	{
		FarmPatch tree = FarmingWorldData.getPatches(PatchImplementation.TREE).get(0);
		PatchProjection magicStanding = project(tree, 61);   // magic, checked, still standing
		assertNotNull("fixture: 61 should decode for a tree patch", magicStanding.getProduce());

		store.setPayingFor(Seed.MAGIC, true);
		assertTrue("magic is ticked, and magic is what is standing there",
			store.isPayingFor(magicStanding));

		store.setPayingFor(Seed.MAGIC, false);
		store.setPayingFor(Seed.MAPLE, true);
		assertFalse("only the maple going in next is ticked, not the magic actually standing",
			store.isPayingFor(magicStanding));
	}

	/**
	 * A stump still answers the seed's own toggle, deliberately.
	 *
	 * <p>The store only resolves "what crop is standing here, and is that crop ticked" — it is
	 * not the gate on whether there is anything left to buy. That gate belongs to whoever asks
	 * the question with a reason: {@code GuidePlan}'s branch is reached only when {@code
	 * isChoppable()} is true, which is already false for a stump, and {@code RunPlanner
	 * .clearableIn} excludes {@code isStump()} explicitly. Duplicating that guard here would be a
	 * second place for the two to disagree.
	 */
	@Test
	public void aStumpStillAnswersTheTickedSeed()
	{
		FarmPatch tree = FarmingWorldData.getPatches(PatchImplementation.TREE).get(0);
		PatchProjection stump = project(tree, 62);
		store.setPayingFor(Seed.MAGIC, true);

		assertTrue("the crop standing there (felled or not) is still magic, and magic is ticked",
			store.isPayingFor(stump));
	}

	/** A calquat gardener does not exist; ticking calquat must not fake one into being. */
	@Test
	public void anUnsupportedPatchTypeIsNeverPayable()
	{
		FarmPatch calquat = FarmingWorldData.getPatches(PatchImplementation.CALQUAT).get(0);
		PatchProjection standing = project(calquat, 12);   // a grown, harvestable calquat
		assertNotNull("fixture: 12 should decode for a calquat patch", standing.getProduce());

		store.setPayingFor(Seed.CALQUAT, true);

		assertFalse("no gardener clears a calquat, whatever is ticked",
			store.isPayingFor(standing));
	}

	@Test
	public void anEmptyPatchIsNeverPayable()
	{
		FarmPatch tree = FarmingWorldData.getPatches(PatchImplementation.TREE).get(0);
		PatchProjection weeds = project(tree, 0);
		store.setPayingFor(Seed.MAGIC, true);

		assertFalse("nothing is growing here at all", store.isPayingFor(weeds));
	}

	@Test
	public void aNullProjectionIsNeverPayable()
	{
		assertFalse(store.isPayingFor((PatchProjection) null));
	}

	@Test
	public void unreadableJsonIsDiscardedRatherThanThrown()
	{
		stored.put("payToClear", "not json");

		store.load();

		assertFalse(store.isPayingFor(Seed.MAGIC));
	}

	private PatchProjection project(FarmPatch patch, int varbitValue)
	{
		ProduceState decoded = patch.getImplementation().forVarbitValue(varbitValue);
		assertNotNull("varbit " + varbitValue + " does not decode for " + patch.getKey(), decoded);
		stateStore.recordVarbit(patch, varbitValue, decoded);
		PatchProjection projection = timer.project(patch, stateStore.get(patch));
		assertNotNull("fixture: the projection should build", projection);
		return projection;
	}
}
