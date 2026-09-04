package com.dooglemaps.guide;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.state.SeedInventoryStore;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.events.PostMenuSort;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

/**
 * The per-patch Pay swap is keyed on the <b>step</b>, not merely on standing beside a farmer who
 * happens to offer one — {@code PAY_TO_CLEAR} promotes a gardener's lone Pay exactly the way
 * {@code PAY_FARMER} always has, and any other step at the same patch, in front of the same
 * gardener, promotes nothing.
 *
 * <p>Harness copied from {@code SquirrelPaySwapTest}, which owns the canonical version of it —
 * a single-patch farmer whose menu is a plain "Pay" with no patch name in it, so the exact
 * chathead is the whole disambiguation.
 */
public class PayMenuSwapIsKeyedOnTheStepTest
{
	private Client client;
	private Menu menu;
	private GuideTracker tracker;
	private GuideMenuSwap swap;
	private MenuEntry[] entries;
	private FarmPatch patch;

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
			Mockito.mock(com.dooglemaps.bank.RunLoadout.class), Mockito.mock(CarriedItems.class));

		patch = FarmingWorldData.getPatches(PatchImplementation.TREE).get(0);
	}

	/** A pay-to-clear step promotes the farmer's lone Pay, just as a protection payment would. */
	@Test
	public void aPayToClearStepPromotesTheLonePay()
	{
		gardenersMenu(patch.getFarmer());
		GuideStep step = stepFor(GuideAction.PAY_TO_CLEAR);
		when(tracker.getCurrentStep()).thenReturn(step);

		swap.onPostMenuSort(new PostMenuSort());

		assertEquals("the exact chathead has no sibling to disambiguate from, so a plain "
				+ "\"Pay\" is enough",
			"Pay", leftClick());
	}

	/** A CHECK_HEALTH step at the very same patch and gardener promotes nothing at all. */
	@Test
	public void aCheckHealthStepPromotesNothing()
	{
		gardenersMenu(patch.getFarmer());
		GuideStep step = stepFor(GuideAction.CHECK_HEALTH);
		when(tracker.getCurrentStep()).thenReturn(step);

		swap.onPostMenuSort(new PostMenuSort());

		assertEquals("only a pay step ever moves this menu",
			"Talk-to", leftClick());
	}

	// ------------------------------------------------------------------ helpers

	/** Talk-to left-click, plain Pay first - the shape a single-patch gardener's menu takes. */
	private void gardenersMenu(int chatheadId)
	{
		entries = new MenuEntry[]{entry("Pay", chatheadId), entry("Talk-to", chatheadId)};
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

	private static MenuEntry entry(String option, int npcId)
	{
		NPC npc = Mockito.mock(NPC.class);
		when(npc.getId()).thenReturn(npcId);
		MenuEntry menuEntry = Mockito.mock(MenuEntry.class);
		when(menuEntry.getOption()).thenReturn(option);
		when(menuEntry.getNpc()).thenReturn(npc);
		return menuEntry;
	}

	private GuideStep stepFor(GuideAction action)
	{
		GuideStep step = Mockito.mock(GuideStep.class);
		when(step.getAction()).thenReturn(action);
		when(step.getPatch()).thenReturn(patch);
		return step;
	}
}
