package com.dooglemaps.bank;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers the house teleports, which are in the table for a different reason from everything else.
 *
 * <p>Reported from play: the Teleport to house tablet was never highlighted. The table is
 * organised by what a teleport <i>lands next to</i>, and by that rule the house belongs nowhere —
 * it reaches no farming patch. But the portal nexus and the jewellery box are both inside it and
 * both were already being highlighted, so the tablet was the missing first step of a chain that
 * was otherwise built.
 */
public class HouseTeleportTest
{
	@Test
	public void theHouseTabletIsOffered()
	{
		assertTrue("the tablet is what gets you to the nexus and the jewellery box",
			universalNamed("Teleport to house tablet"));
	}

	/** The cape does the same job, so someone wearing one should not be sent for a tablet. */
	@Test
	public void theConstructionCapeCountsToo()
	{
		assertTrue(universalNamed("Construction cape"));
	}

	/**
	 * A house teleport is a thing to click; a Dramen staff is not.
	 *
	 * <p>Both are universal entries, and without the distinction the travel hint would happily
	 * say "use your Dramen staff" to get somewhere — an instruction that cannot be followed,
	 * because the staff is carried so that a fairy ring works rather than being the teleport.
	 */
	@Test
	public void onlyTheHouseTeleportsClaimToTeleportYou()
	{
		for (TeleportItems.Teleport teleport : TeleportItems.universal())
		{
			boolean isStaff = teleport.getName().toLowerCase().contains("dramen");
			assertFalse("a Dramen staff enables a teleport, it is not one",
				isStaff && teleport.teleportsYou());
		}

		assertTrue("but something universal has to be clickable, or the fallback is dead code",
			TeleportItems.universal().stream().anyMatch(TeleportItems.Teleport::teleportsYou));
	}

	/**
	 * The house must not be claimed to reach a farming region directly.
	 *
	 * <p>It is the thing that makes this entry different, and getting it wrong would put "use
	 * your house tablet" ahead of a real teleport that lands at the patch.
	 */
	@Test
	public void theHouseIsNotOfferedAsADirectTeleportAnywhere()
	{
		for (com.dooglemaps.data.FarmRegion region
			: com.dooglemaps.data.FarmingWorldData.getRegions())
		{
			for (TeleportItems.Teleport teleport
				: TeleportItems.forRegion(region.getRegionId()))
			{
				assertFalse(region.getName() + " should not be reachable by house teleport",
					teleport.getName().toLowerCase().contains("house"));
			}
		}
	}

	private static boolean universalNamed(String name)
	{
		return TeleportItems.universal().stream()
			.anyMatch(teleport -> name.equals(teleport.getName()));
	}
}
