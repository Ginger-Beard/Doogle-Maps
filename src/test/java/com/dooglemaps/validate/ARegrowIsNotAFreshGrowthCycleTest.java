package com.dooglemaps.validate;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.timer.DiseaseRisk;
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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * A tree restocking itself is the same planting, not another one survived.
 *
 * <h2>The reported dead end</h2>
 *
 * {@link DiseaseStatsStore#observe} closed a growth cycle on any {@code GROWING -> HARVESTABLE}
 * transition, and the javadoc immediately above it said <i>"a bush going back to harvestable as it
 * regrows is the same cycle continuing, not a new one survived"</i>. The guard did not implement
 * its own comment: a regrowing bush, fruit tree or tree really does pass back through GROWING
 * every time it grows the next lot, so the test excluded nothing.
 *
 * <p>{@code Maple|ULTRACOMPOST} carries <b>37 "growth cycles"</b> in the store, from what is at
 * most one or two trees. Every one of those re-applied a whole plant-to-harvest survival
 * probability, inflating both the denominator and the prediction — and it is a third of the
 * account-wide "2 of 136 growth cycles caught something, against a predicted 50", a line that read
 * as the plugin's model being badly wrong about disease when it was the counting that was wrong.
 *
 * <p>A disease roll happens per growth stage between planting and ripeness, so the cycle the
 * published rate describes is one <b>planting</b>. That is what is counted now.
 */
public class ARegrowIsNotAFreshGrowthCycleTest
{
	private final Map<String, String> config = new HashMap<>();

	private ConfigManager configManager;
	private DiseaseStatsStore store;
	private FarmPatch patch;
	private Produce crop;

	@Before
	public void setUp()
	{
		configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString()))
			.thenAnswer((InvocationOnMock call) -> config.get(call.getArgument(1)));
		doAnswer((InvocationOnMock call) ->
		{
			// Both reads out on their own line: inlining the second lets Java infer char[] for
			// getArgument and pick String.valueOf(char[]), which fails at run time.
			String storeKey = call.getArgument(1);
			Object value = call.getArgument(2);
			config.put(storeKey, String.valueOf(value));
			return null;
		}).when(configManager).setRSProfileConfiguration(anyString(), anyString(), Mockito.any());

		store = construct(DiseaseStatsStore.class, configManager, new Gson());
		store.load();

		// A fruit tree, because the crop has to be one the patch can genuinely catch something on
		// - a certain survival is excluded from the denominator by design and would make every
		// count here zero - and it has to regrow. Jagex publishes a rate for herbs, fruit trees,
		// coral and two tree species; bushes have none, so a bush would count nothing at all.
		for (FarmPatch candidate : FarmingWorldData.getPatches(PatchImplementation.FRUIT_TREE))
		{
			if (DiseaseRisk.survivalChance(candidate, Produce.PAPAYA, CompostTier.NONE,
				false, false) < 1)
			{
				patch = candidate;
				crop = Produce.PAPAYA;
				break;
			}
		}
		assertTrue("fixture: a diseasable fruit-tree patch exists", patch != null);
		assertTrue("fixture: and the crop it holds regrows", crop.getRegrowTickrate() > 0);
	}

	@Test
	public void aFruitTreeRestockingFiveTimesIsStillOneGrowthCycle()
	{
		plant();
		ripen();
		assertEquals("planting through to ripe is one cycle", 1, store.getTotalCycles());

		for (int restock = 0; restock < 5; restock++)
		{
			// Picked to nothing and growing the next lot, then ripe again. The same plant, the
			// same planting, the same disease roll it already survived.
			store.observe(patch, at(CropState.HARVESTABLE), at(CropState.GROWING),
				CompostTier.NONE, false, false);
			ripen();
		}

		assertEquals("five restocks are not five more plant-to-harvest survivals",
			1, store.getTotalCycles());
	}

	/** Digging it up and planting again is genuinely a second cycle. */
	@Test
	public void clearingThePatchAndReplantingIsASecondCycle()
	{
		plant();
		ripen();

		store.observe(patch, at(CropState.HARVESTABLE), at(CropState.EMPTY), CompostTier.NONE,
			false, false);
		plant();
		ripen();

		assertEquals(2, store.getTotalCycles());
	}

	/**
	 * A crop that does not regrow is untouched by any of this.
	 *
	 * <p>The guard is asked of the planting standing in the patch rather than of whether the crop
	 * regrows, which is what makes it safe: a herb or an allotment reaches harvestable once per
	 * planting anyway, so the first time always counts and there is never a second to suppress.
	 */
	@Test
	public void aHerbPatchCountsEveryPlantingAsBefore()
	{
		FarmPatch herb = null;
		for (FarmPatch candidate : FarmingWorldData.getPatches(PatchImplementation.HERB))
		{
			if (DiseaseRisk.survivalChance(candidate, Produce.RANARR, CompostTier.NONE, false,
				false) < 1)
			{
				herb = candidate;
				break;
			}
		}
		assertTrue("fixture: a diseasable herb patch exists", herb != null);

		for (int cycle = 0; cycle < 3; cycle++)
		{
			store.observe(herb, ranarr(CropState.EMPTY), ranarr(CropState.GROWING),
				CompostTier.NONE, false, false);
			store.observe(herb, ranarr(CropState.GROWING), ranarr(CropState.HARVESTABLE),
				CompostTier.NONE, false, false);
			store.observe(herb, ranarr(CropState.HARVESTABLE), ranarr(CropState.EMPTY),
				CompostTier.NONE, false, false);
		}

		assertEquals("three plantings, three cycles", 3, store.getTotalCycles());
	}

	// ------------------------------------------------------------------- helpers

	private void plant()
	{
		store.observe(patch, at(CropState.EMPTY), at(CropState.GROWING), CompostTier.NONE, false,
			false);
	}

	private void ripen()
	{
		store.observe(patch, at(CropState.GROWING), at(CropState.HARVESTABLE), CompostTier.NONE,
			false, false);
	}

	private ProduceState at(CropState state)
	{
		return new ProduceState(state == CropState.EMPTY ? Produce.WEEDS : crop, state, 2);
	}

	private static ProduceState ranarr(CropState state)
	{
		return new ProduceState(state == CropState.EMPTY ? Produce.WEEDS : Produce.RANARR, state, 2);
	}
}
