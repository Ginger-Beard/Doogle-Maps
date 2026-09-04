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
import net.runelite.api.gameval.NpcID;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Each Fossil Island squirrel is the sole farmer of its own hardwood patch and offers a single
 * plain "Pay" with no patch name in it — unlike Chet, whose two Pay options each spell out which
 * nursery they belong to. The name-contains rule {@code GuideMenuSwap} used for every payment
 * swap could never match a plain "Pay", so paying at Fossil Island never promoted anything.
 *
 * <p>The fix promotes any "Pay" on the exact chathead id when that farmer has no sibling patch to
 * disambiguate from — which also means it must NOT fire for one squirrel's "Pay" while the guide
 * is pointing at a <i>different</i> squirrel's patch, even though both are Fossil Island hardwood.
 */
public class SquirrelPaySwapTest
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
			Mockito.mock(com.dooglemaps.bank.RunLoadout.class), Mockito.mock(CarriedItems.class));
	}

	/** East's own squirrel, own plain Pay, East's own step: promoted. */
	@Test
	public void theSquirrelsPlainPayIsPromotedForItsOwnPatch()
	{
		squirrelsMenu(NpcID.FOSSIL_SQUIRREL_GARDENER1);
		GuideStep step = payStepFor(fossilPatch("East"));
		when(tracker.getCurrentStep()).thenReturn(step);

		swap.onPostMenuSort(new PostMenuSort());

		assertEquals("the exact chathead has no sibling to disambiguate from, so a plain "
				+ "\"Pay\" is enough",
			"Pay", leftClick());
	}

	/** The same squirrel's Pay must not jump the queue for a step about a different patch. */
	@Test
	public void theSquirrelsPlainPayIsNotPromotedForASiblingPatch()
	{
		squirrelsMenu(NpcID.FOSSIL_SQUIRREL_GARDENER1);
		// Middle's patch is a different squirrel entirely - the step names a farmer id this
		// menu's chathead does not carry.
		GuideStep step = payStepFor(fossilPatch("Middle"));
		when(tracker.getCurrentStep()).thenReturn(step);

		swap.onPostMenuSort(new PostMenuSort());

		assertEquals("nothing here belongs to Middle's squirrel, so nothing should move",
			"Talk-to", leftClick());
	}

	// ------------------------------------------------------------------ helpers

	/** Talk-to left-click, plain Pay first - the shape a squirrel's own menu takes. */
	private void squirrelsMenu(int chatheadId)
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

	private static GuideStep payStepFor(FarmPatch patch)
	{
		GuideStep step = Mockito.mock(GuideStep.class);
		when(step.getAction()).thenReturn(GuideAction.PAY_FARMER);
		when(step.getPatch()).thenReturn(patch);
		return step;
	}
}
