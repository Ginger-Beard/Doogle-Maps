package com.dooglemaps.capture;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.state.PatchStateStore;
import java.lang.reflect.Method;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.NpcID;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.when;

/**
 * Fossil Island's three hardwood patches each have their own farmer, and a payment from one
 * squirrel's chathead must land on that squirrel's own patch.
 *
 * <h2>The reported bug</h2>
 *
 * Before the {@code FarmerVariants} guard, all three squirrels shared the generated name
 * "Squirrel" and {@code same} fell back to it, so {@code ProtectionCapture.findPatchForNpc}
 * matched every one of the three Fossil Island patches to every one of the three squirrel
 * chatheads. Its loop kept overwriting a single {@code found} variable, so whichever patch came
 * last in the region's list — West — won every time, regardless of which squirrel actually
 * accepted the payment. Reported from play: paying all three recorded as "Fossil Island West"
 * three times over, and East and Middle never read protected.
 */
public class EachSquirrelPaysItsOwnPatchTest
{
	private Client client;
	private ProtectionCapture capture;

	@Before
	public void setUp()
	{
		client = Mockito.mock(Client.class);
		when(client.getTickCount()).thenReturn(50);

		net.runelite.api.Player player = Mockito.mock(net.runelite.api.Player.class);
		// Somewhere ordinary on Fossil Island's hardwood plane, well clear of the stair tiles
		// RegionBounds.forRegion(14651) special-cases.
		when(player.getWorldLocation()).thenReturn(new WorldPoint(3650, 3778, 0));
		when(client.getLocalPlayer()).thenReturn(player);

		capture = construct(ProtectionCapture.class, client, Mockito.mock(PatchStateStore.class));
	}

	@Test
	public void gardener1PaysEast() throws Exception
	{
		assertEquals(fossilPatch("East"), paidPatch(NpcID.FOSSIL_SQUIRREL_GARDENER1));
	}

	@Test
	public void gardener2PaysMiddle() throws Exception
	{
		assertEquals(fossilPatch("Middle"), paidPatch(NpcID.FOSSIL_SQUIRREL_GARDENER2));
	}

	@Test
	public void gardener3PaysWest() throws Exception
	{
		assertEquals(fossilPatch("West"), paidPatch(NpcID.FOSSIL_SQUIRREL_GARDENER3));
	}

	private FarmPatch paidPatch(int chatheadId) throws Exception
	{
		Method method = ProtectionCapture.class.getDeclaredMethod("findPatchForNpc", int.class);
		method.setAccessible(true);
		return (FarmPatch) method.invoke(capture, chatheadId);
	}

	private static FarmPatch fossilPatch(String name)
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.HARDWOOD_TREE))
		{
			if (name.equals(patch.getName()) && "Fossil Island".equals(patch.getRegion().getName()))
			{
				return patch;
			}
		}
		throw new AssertionError("no Fossil Island hardwood patch named " + name);
	}

	/** Fixture guard: this test rests on the three squirrel ids naming East/Middle/West in order. */
	@Test
	public void theFixtureIdsMatchTheWorldData()
	{
		assertNotNull(fossilPatch("East"));
		assertEquals(NpcID.FOSSIL_SQUIRREL_GARDENER1, fossilPatch("East").getFarmer());
		assertEquals(NpcID.FOSSIL_SQUIRREL_GARDENER2, fossilPatch("Middle").getFarmer());
		assertEquals(NpcID.FOSSIL_SQUIRREL_GARDENER3, fossilPatch("West").getFarmer());
	}
}
