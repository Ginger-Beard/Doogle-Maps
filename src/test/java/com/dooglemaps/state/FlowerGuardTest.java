package com.dooglemaps.state;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.ProduceState;
import java.util.HashMap;
import java.util.Map;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The third allotment protection, the one the plugin was blind to: a grown flower.
 *
 * <p>Wiki-checked table — marigold guards potato/onion/tomato, rosemary cabbage, nasturtium
 * watermelon, white lily everything — and the state rule that only a <i>fully grown</i>
 * flower counts. A growing marigold guards nothing yet, which is exactly the case that
 * would otherwise inflate a survival estimate.
 */
public class FlowerGuardTest
{
	private PatchStateStore patches;

	@Before
	public void setUp() throws Exception
	{
		Map<String, String> stored = new HashMap<>();
		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString()))
			.thenAnswer(i -> stored.get(i.getArgument(0) + "." + i.getArgument(1)));
		patches = construct(PatchStateStore.class, configManager, new com.google.gson.Gson());
		patches.load();
	}

	@Test
	public void theWikiTableIsEncodedFaithfully()
	{
		assertTrue(FlowerGuard.guards(Produce.MARIGOLD, Produce.POTATO));
		assertTrue(FlowerGuard.guards(Produce.MARIGOLD, Produce.ONION));
		assertTrue(FlowerGuard.guards(Produce.MARIGOLD, Produce.TOMATO));
		assertFalse("marigolds do not guard cabbages - rosemary does",
			FlowerGuard.guards(Produce.MARIGOLD, Produce.CABBAGE));
		assertTrue(FlowerGuard.guards(Produce.ROSEMARY, Produce.CABBAGE));
		assertTrue(FlowerGuard.guards(Produce.NASTURTIUM, Produce.WATERMELON));
		assertTrue("the white lily guards every allotment crop",
			FlowerGuard.guards(Produce.WHITE_LILY, Produce.SWEETCORN));
		assertFalse("no flower at all guards nothing", FlowerGuard.guards(null, Produce.POTATO));
	}

	/** Grown guards; growing does not; and the allotment must share the flower's location. */
	@Test
	public void onlyAGrownFlowerInTheSameLocationGuards()
	{
		FarmPatch allotment = null;
		FarmPatch flower = null;
		for (FarmPatch candidate : FarmingWorldData.getPatches(PatchImplementation.ALLOTMENT))
		{
			for (FarmPatch f : FarmingWorldData.getPatches(PatchImplementation.FLOWER))
			{
				if (f.getRegion().getRegionId() == candidate.getRegion().getRegionId())
				{
					allotment = candidate;
					flower = f;
					break;
				}
			}
			if (allotment != null)
			{
				break;
			}
		}
		assertNotNull("no location pairs an allotment with a flower patch", allotment);

		assertFalse("an unseen flower patch is not a guard",
			FlowerGuard.guarding(patches, allotment, Produce.SWEETCORN));

		record(flower, com.dooglemaps.data.CropState.GROWING, Produce.WHITE_LILY);
		assertFalse("a growing lily guards nothing yet",
			FlowerGuard.guarding(patches, allotment, Produce.SWEETCORN));

		record(flower, com.dooglemaps.data.CropState.HARVESTABLE, Produce.WHITE_LILY);
		assertTrue("a grown lily guards the allotment beside it",
			FlowerGuard.guarding(patches, allotment, Produce.SWEETCORN));
	}

	/** Puts the flower patch into the first varbit state matching the crop and state wanted. */
	private void record(FarmPatch flower, com.dooglemaps.data.CropState state, Produce produce)
	{
		for (int value = 0; value < 256; value++)
		{
			ProduceState decoded = flower.getImplementation().forVarbitValue(value);
			if (decoded != null && decoded.getProduce() == produce
				&& decoded.getCropState() == state)
			{
				patches.recordVarbit(flower, value, decoded);
				return;
			}
		}
		throw new AssertionError("no varbit puts " + produce + " into " + state);
	}
}
