package com.dooglemaps.capture;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
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
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.when;

/**
 * Kastori's calquat and fruit tree are two blank-named patches tended by two different
 * gardeners, and a payment from one gardener's chathead must land on that gardener's own
 * patch rather than the other's.
 *
 * <h2>The reported bug</h2>
 *
 * Both patches' {@code getDisplayName()} read plain "Kastori" - the region has no
 * disambiguating name for either - so the log line "Protection recorded: Kastori paid for"
 * could not tell a calquat payment from a fruit tree one. Reported from play: a calquat
 * payment (Tziuhtla, chathead {@link NpcID#FARMING_GARDENER_CALQUAT_2}) was read as the
 * fruit tree's. {@code findPatchForNpc} itself was never ambiguous here - each gardener is
 * the sole farmer of their own patch, so {@code patchNumber == -1} matches on the NPC id
 * alone - the failure was only in what got logged afterward. This test pins the underlying
 * match so a future change cannot reintroduce ambiguity between the two, alongside the
 * display-name fix in {@code FarmPatchDisplayNameTest}.
 */
public class EachKastoriGardenerPaysForItsOwnPatchTest
{
	private Client client;
	private ProtectionCapture capture;

	@Before
	public void setUp()
	{
		client = Mockito.mock(Client.class);
		when(client.getTickCount()).thenReturn(50);

		net.runelite.api.Player player = Mockito.mock(net.runelite.api.Player.class);
		// Somewhere ordinary inside Kastori's region (5423).
		when(player.getWorldLocation()).thenReturn(new WorldPoint(1350, 3010, 0));
		when(client.getLocalPlayer()).thenReturn(player);

		capture = construct(ProtectionCapture.class, client, Mockito.mock(PatchStateStore.class));
	}

	@Test
	public void tziuhtlaPaysTheCalquatOnly() throws Exception
	{
		FarmPatch paid = paidPatch(NpcID.FARMING_GARDENER_CALQUAT_2);

		assertEquals(kastoriPatch("5423.4771"), paid);
	}

	@Test
	public void ehecatlPaysTheFruitTreeOnly() throws Exception
	{
		FarmPatch paid = paidPatch(NpcID.FARMING_GARDENER_FRUIT_7);

		assertEquals(kastoriPatch("5423.4772"), paid);
	}

	/** Neither gardener's chathead is ambiguous - each is the sole farmer of their own patch. */
	@Test
	public void neitherGardenerIsAmbiguous() throws Exception
	{
		assertEquals(kastoriPatch("5423.4771").getFarmer(), NpcID.FARMING_GARDENER_CALQUAT_2);
		assertEquals(kastoriPatch("5423.4772").getFarmer(), NpcID.FARMING_GARDENER_FRUIT_7);
		assertEquals(-1, kastoriPatch("5423.4771").getPatchNumber());
		assertEquals(-1, kastoriPatch("5423.4772").getPatchNumber());
	}

	/** An id neither gardener answers to matches nothing at Kastori. */
	@Test
	public void anUnrelatedChatheadMatchesNothing() throws Exception
	{
		assertNull(paidPatch(NpcID.DANTAERA));
	}

	private FarmPatch paidPatch(int chatheadId) throws Exception
	{
		Method method = ProtectionCapture.class.getDeclaredMethod("findPatchForNpc", int.class);
		method.setAccessible(true);
		return (FarmPatch) method.invoke(capture, chatheadId);
	}

	private static FarmPatch kastoriPatch(String key)
	{
		FarmPatch patch = FarmingWorldData.getPatch(key);
		if (patch == null)
		{
			throw new AssertionError("no patch keyed " + key);
		}
		return patch;
	}
}
