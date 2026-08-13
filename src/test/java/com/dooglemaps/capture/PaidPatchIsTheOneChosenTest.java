package com.dooglemaps.capture;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.state.PatchStateStore;
import java.lang.reflect.Method;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.NpcID;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Which patch a payment is recorded against, when the farmer charges for each.
 *
 * <h2>Why the menu position could not answer it</h2>
 *
 * The rule was positional: a "Pay" on the third right-click option meant patch 0, on the fourth
 * meant patch 1, and any other position meant nothing at all. Chet, who tends the two coral
 * nurseries, disproves every part of that. His menu reads
 *
 * <pre>Pay (East) / Talk-to / Pay (West) / Trade</pre>
 *
 * so his payments are not adjacent, neither sits where the rule expected, and the one that IS
 * third is <b>West</b>. Paying east recorded nothing; paying west recorded east. A wrong
 * protection state rather than a missing one, and the wrong direction to fail in — a missed
 * record shows up as "pay the farmer" asking again, where a wrong one never shows up at all.
 *
 * <p>So the option's own words decide it now. A patch's {@code name} is its disambiguator
 * within a region — "East", "North" — and a farmer who splits his payments into separate
 * options spells that same word into them.
 */
public class PaidPatchIsTheOneChosenTest
{
	private Client client;
	private ProtectionCapture capture;

	private static final FarmPatch EAST = coral("East");
	private static final FarmPatch WEST = coral("West");

	@Before
	public void setUp()
	{
		client = Mockito.mock(Client.class);
		when(client.getTickCount()).thenReturn(50);

		net.runelite.api.Player player = Mockito.mock(net.runelite.api.Player.class);
		// Standing among the nurseries, which is where the Great Conch's varbits are live.
		when(player.getWorldLocation()).thenReturn(new WorldPoint(3272, 8850, 0));
		when(client.getLocalPlayer()).thenReturn(player);

		capture = construct(ProtectionCapture.class, client,
			Mockito.mock(PatchStateStore.class));
	}

	/** East is his first option, which the positional rule never even looked at. */
	@Test
	public void payingEastRecordsEast() throws Exception
	{
		clicked(MenuAction.NPC_FIRST_OPTION, "Pay (East)");

		assertEquals(EAST, paidPatch());
	}

	/** West is his third, which the positional rule read as East. */
	@Test
	public void payingWestRecordsWestRatherThanEast() throws Exception
	{
		clicked(MenuAction.NPC_THIRD_OPTION, "Pay (West)");

		assertEquals("the third option is not automatically patch zero", WEST, paidPatch());
	}

	/**
	 * A stale selection still records nothing, which the name match must not undo.
	 *
	 * <p>The acceptance line follows the selection within one conversation; a selection a
	 * minute old belongs to some earlier farmer, and redeeming it marks a patch protected that
	 * is not. Refusing outright is the visible failure and the right one.
	 */
	@Test
	public void aStaleSelectionIsNotRedeemed() throws Exception
	{
		clicked(MenuAction.NPC_FIRST_OPTION, "Pay (East)");
		when(client.getTickCount()).thenReturn(50 + 1000);

		assertNull(paidPatch());
	}

	/** An option naming neither patch falls back to the position, as it always did. */
	@Test
	public void anUnnamedPayFallsBackToThePosition() throws Exception
	{
		clicked(MenuAction.NPC_THIRD_OPTION, "Pay");

		assertEquals(EAST, paidPatch());
	}

	/**
	 * And an unnamed Pay in a position that means nothing records nothing.
	 *
	 * <p>Every position used to mean patch 0 or patch 1 and nothing else, so a Pay on the first
	 * option was silently a vote for patch 0. Only the two the old rule actually recognised
	 * keep a meaning; the rest now say "no idea" out loud.
	 */
	@Test
	public void anUnnamedPayInAnUnknownPositionRecordsNothing() throws Exception
	{
		clicked(MenuAction.NPC_FIRST_OPTION, "Pay");

		assertNull(paidPatch());
	}

	private void clicked(MenuAction action, String option)
	{
		MenuOptionClicked event = Mockito.mock(MenuOptionClicked.class);
		when(event.getMenuAction()).thenReturn(action);
		when(event.getMenuOption()).thenReturn(option);
		capture.onMenuOptionClicked(event);
	}

	/** The patch the capture would credit, for the id the player actually meets. */
	private FarmPatch paidPatch() throws Exception
	{
		Method method = ProtectionCapture.class.getDeclaredMethod("findPatchForNpc", int.class);
		method.setAccessible(true);
		return (FarmPatch) method.invoke(capture, NpcID.TORTUGAN_CORAL_FARMER_UNLOCKED);
	}

	private static FarmPatch coral(String name)
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.CORAL))
		{
			if (name.equals(patch.getName()))
			{
				return patch;
			}
		}
		throw new AssertionError("no coral patch named " + name);
	}

	/**
	 * The three acceptance lines the exact-match set used to hold still match.
	 *
	 * <p>The set was a list of forms of address — "sir", "madam", the Tortugan "iknami" —
	 * pretending to be a list of acceptance lines, and it failed silently the first time a
	 * farmer greeted the player some other way: the payment was never recorded, the patch read
	 * unprotected forever, and the run kept asking for payment items already spent. The lines
	 * are kept here rather than lost with it, because they are the evidence the shape is drawn
	 * from.
	 *
	 * <p>Note the wrap: {@code <br>} lands between "make" and "sure" in two of them and after
	 * "sure" in the third, which is exactly the sort of thing an exact match trips over.
	 */
	@Test
	public void theKnownAcceptanceLinesStillCount() throws Exception
	{
		String[] known = {
			"That'll do nicely, sir. Leave it with me - I'll make sure<br>that patch grows for you.",
			"That'll do nicely, madam. Leave it with me - I'll make<br>sure that patch grows for you.",
			"That'll do nicely, iknami. Leave it with me - I'll make<br>sure that patch grows for you.",
		};
		for (String line : known)
		{
			assertTrue(line, accepted(line));
		}
	}

	/** A form of address nobody has written down yet counts too, which is the whole point. */
	@Test
	public void anUnseenFormOfAddressStillCounts() throws Exception
	{
		assertTrue(accepted("That'll do nicely, captain. Leave it with me - I'll make<br>"
			+ "sure that patch grows for you."));
	}

	/** Ordinary farmer chatter does not. */
	@Test
	public void otherDialogueIsNotAPayment() throws Exception
	{
		assertFalse(accepted("That'll do nicely, sir."));
		assertFalse(accepted("I'll make sure that patch grows for you."));
		assertFalse(accepted("Sorry, I can't look after that patch."));
		assertFalse(accepted(null));
	}

	private static boolean accepted(String line) throws Exception
	{
		Method method = ProtectionCapture.class
			.getDeclaredMethod("isPaymentAccepted", String.class);
		method.setAccessible(true);
		return (boolean) method.invoke(null, line);
	}

	/** Fixture guard: the whole test rests on the two nurseries being named East and West. */
	@Test
	public void theNurseriesAreNamedAfterTheOptionsTheyAnswerTo()
	{
		assertNotNull(EAST);
		assertNotNull(WEST);
		assertEquals(0, EAST.getPatchNumber());
		assertEquals(1, WEST.getPatchNumber());
	}
}
