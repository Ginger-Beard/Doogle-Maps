package com.dooglemaps.guide;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.state.SeedInventoryStore;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.gameval.NpcID;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

/**
 * The coral farmer charges per nursery, and paying the wrong one spends the payment.
 *
 * <h2>Why this is the one swap that prevents a mistake</h2>
 *
 * Every other swap in {@code GuideMenuSwap} saves a click. This one stops a loss: Chet offers a
 * Pay for each nursery, only one of them is the step's, and the menu opens on whichever the NPC
 * happens to list first. Given from play, in his own order: <b>Pay (East) / Talk-to / Pay (West)
 * / Trade</b> — so with a West step, the free click is the East payment.
 *
 * <p>Pinned with that exact menu because the reasoning has three separate places to go wrong and
 * none of them are visible from in front of the client: the entry array runs backwards (the
 * left-click is last), Chet's runtime id is a variant of the id the world data records, and the
 * match is on the patch's own name appearing in the option rather than on wording copied out of
 * one NPC's menu.
 */
public class ChetPaySwapTest
{
	private Client client;
	private Menu menu;
	private GuideTracker tracker;
	private GuideMenuSwap swap;
	private MenuEntry[] entries;

	@Before
	public void setUp()
	{
		client = Mockito.mock(Client.class);
		menu = Mockito.mock(Menu.class);
		tracker = Mockito.mock(GuideTracker.class);
		DoogleMapsConfig config = Mockito.mock(DoogleMapsConfig.class);

		when(client.getMenu()).thenReturn(menu);
		when(client.isMenuOpen()).thenReturn(false);
		when(config.guidedMode()).thenReturn(true);
		when(config.payLeftClick()).thenReturn(true);
		when(tracker.getStatus()).thenReturn(Mockito.mock(GuideStatus.class));

		swap = construct(GuideMenuSwap.class, client, tracker, config,
			Mockito.mock(SeedInventoryStore.class),
			Mockito.mock(com.dooglemaps.bank.RunLoadout.class));
	}

	/** Chet's own menu, in his own order, with the West patch wanted. */
	@Test
	public void theWestPaymentIsPutUnderTheLeftClick()
	{
		chetsMenu();
		// Built before the stub: constructing it does its own mocking, and Mockito
		// treats stubbing-inside-stubbing as an unfinished when().
		GuideStep step = payStepFor("West");
		when(tracker.getCurrentStep()).thenReturn(step);

		swap.onPostMenuSort(new PostMenuSort());

		assertEquals("the left-click is the last entry, and it should be the step's patch",
			"Pay (West)", leftClick());
	}

	/** And the East one when that is the patch, which is also the no-op case. */
	@Test
	public void theEastPaymentIsLeftWhereItAlreadyWas()
	{
		chetsMenu();
		// Built before the stub: constructing it does its own mocking, and Mockito
		// treats stubbing-inside-stubbing as an unfinished when().
		GuideStep step = payStepFor("East");
		when(tracker.getCurrentStep()).thenReturn(step);

		swap.onPostMenuSort(new PostMenuSort());

		assertEquals("Pay (East)", leftClick());
	}

	/** With no payment step current, Chet's menu is left exactly as the game built it. */
	@Test
	public void nothingMovesWithoutAPaymentStep()
	{
		chetsMenu();
		when(tracker.getCurrentStep()).thenReturn(null);

		swap.onPostMenuSort(new PostMenuSort());

		assertEquals("Pay (East)", leftClick());
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * The entries as the client holds them: the left-click option <b>last</b>.
	 *
	 * <p>So the displayed order top to bottom is the reverse — Pay (East), Talk-to, Pay (West),
	 * Trade, which is what was read off the client.
	 */
	private void chetsMenu()
	{
		entries = new MenuEntry[]{
			entry("Trade"), entry("Pay (West)"), entry("Talk-to"), entry("Pay (East)")};
		when(menu.getMenuEntries()).thenReturn(entries);
		Mockito.doAnswer(invocation ->
		{
			entries = invocation.getArgument(0);
			return null;
		}).when(menu).setMenuEntries(Mockito.any());
	}

	private String leftClick()
	{
		return entries[entries.length - 1].getOption();
	}

	/**
	 * Chet as the client reports him: {@code TORTUGAN_CORAL_FARMER_UNLOCKED}, id 15063, where
	 * the world data records the plain {@code TORTUGAN_CORAL_FARMER}. That gap is what
	 * {@code FarmerVariants} exists for, and getting it wrong would silently match nothing.
	 */
	private static MenuEntry entry(String option)
	{
		NPC npc = Mockito.mock(NPC.class);
		when(npc.getId()).thenReturn(NpcID.TORTUGAN_CORAL_FARMER_UNLOCKED);
		MenuEntry menuEntry = Mockito.mock(MenuEntry.class);
		when(menuEntry.getOption()).thenReturn(option);
		when(menuEntry.getNpc()).thenReturn(npc);
		return menuEntry;
	}

	private static GuideStep payStepFor(String patchName)
	{
		FarmPatch wanted = null;
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.CORAL))
		{
			if (patchName.equals(patch.getName()))
			{
				wanted = patch;
			}
		}
		List<String> names = new ArrayList<>();
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.CORAL))
		{
			names.add(patch.getName());
		}
		org.junit.Assert.assertNotNull(
			"fixture: no coral patch called " + patchName + ", only " + names, wanted);

		GuideStep step = Mockito.mock(GuideStep.class);
		when(step.getAction()).thenReturn(GuideAction.PAY_FARMER);
		when(step.getPatch()).thenReturn(wanted);
		return step;
	}
}
