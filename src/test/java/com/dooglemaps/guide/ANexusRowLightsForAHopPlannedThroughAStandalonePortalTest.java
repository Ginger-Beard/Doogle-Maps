package com.dooglemaps.guide;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Reported from play, 2026-09-06: stop "Taverley", route hops {@code ["Teleport to House",
 * "Enter Falador Portal - Falador Portal"]} — a standalone house portal to Falador, Taverley
 * being a walk beyond it. The player opened the Portal Nexus instead, whose row says only
 * "5: Falador". Nothing lit: {@code rowNames} wanted "Falador Portal" (the furniture's name,
 * not any row on screen) and then "Taverley" (a place the nexus has no row for at all), and
 * neither ever matched the "5: Falador" row actually on screen.
 *
 * <p>The fix teaches {@code rowNames} the same trick {@code HouseTeleports.furnitureServesHop}
 * already uses to match a portal's furniture to its hop: a hop's row name that ends in "portal"
 * also contributes the bare place, right after the hop's own name and still ahead of the
 * destination fallback.
 */
public class ANexusRowLightsForAHopPlannedThroughAStandalonePortalTest
{
	private GuideInventoryOverlay overlay;
	private Client client;
	private GuideTracker tracker;
	private com.dooglemaps.DoogleMapsConfig config;
	private Graphics2D graphics;
	private List<Rectangle> drawn;

	@Before
	public void setUp()
	{
		client = Mockito.mock(Client.class);
		tracker = Mockito.mock(GuideTracker.class);
		config = Mockito.mock(com.dooglemaps.DoogleMapsConfig.class);
		ItemManager itemManager = Mockito.mock(ItemManager.class);
		CarriedItems carried = Mockito.mock(CarriedItems.class);
		com.dooglemaps.state.SeedInventoryStore seeds =
			Mockito.mock(com.dooglemaps.state.SeedInventoryStore.class);

		overlay = construct(GuideInventoryOverlay.class, client, tracker, config, itemManager,
			carried, seeds);

		when(config.guidedMode()).thenReturn(true);
		when(config.guideHighlightColour()).thenReturn(Color.CYAN);

		GuideStatus status = Mockito.mock(GuideStatus.class);
		when(status.isRunning()).thenReturn(true);
		when(tracker.getStatus()).thenReturn(status);

		drawn = new ArrayList<>();
		graphics = Mockito.mock(Graphics2D.class);
		Mockito.doAnswer(invocation ->
		{
			Object shape = invocation.getArgument(0);
			if (shape instanceof Rectangle)
			{
				drawn.add((Rectangle) shape);
			}
			return null;
		}).when(graphics).draw(Mockito.any(Shape.class));
	}

	/**
	 * The owner's screenshot, reproduced: only the nexus's "5: Falador" row lights, not the
	 * "A: Varrock" row sitting beside it, and the "Taverley" destination fallback is never
	 * needed to find it.
	 */
	@Test
	public void onlyTheFaladorRowLights()
	{
		when(tracker.liveTransports()).thenReturn(Arrays.asList(
			"Teleport to House", "Enter Falador Portal - Falador Portal"));
		when(status().getTravelHint()).thenReturn(
			new TravelHint(-1, null, "Taverley", TravelHint.Where.UNOWNED));

		Rectangle faladorBounds = new Rectangle(10, 20, 200, 12);
		Rectangle varrockBounds = new Rectangle(10, 40, 200, 12);
		nexusUniverse(
			row("<col=ffffff>5</col> :  Falador", faladorBounds),
			row("<col=ffffff>A</col> :  Varrock", varrockBounds));

		overlay.render(graphics);

		assertEquals("exactly one row is outlined", 1, drawn.size());
		assertTrue("expected the Falador row to be outlined", drawn.contains(faladorBounds));
	}

	/**
	 * Regression guard: a hop planned through a jewellery-box row that already names the row
	 * ("1: Emir's Arena") still prefers that row over a same-panel row that merely shares the
	 * stop's destination name ("Al Kharid").
	 */
	@Test
	public void aHopsOwnRowStillBeatsTheDestination()
	{
		when(tracker.liveTransports()).thenReturn(Arrays.asList(
			"Teleport to House", "Teleport Menu Fancy Jewellery Box - 1: Emir's Arena"));
		when(status().getTravelHint()).thenReturn(
			new TravelHint(-1, null, "Al Kharid", TravelHint.Where.UNOWNED));

		Rectangle emirsBounds = new Rectangle(10, 20, 200, 12);
		Rectangle alKharidBounds = new Rectangle(10, 40, 200, 12);
		nexusUniverse(
			row("<col=ccccff>1:</col> Emir's Arena", emirsBounds),
			row("R: Al Kharid", alKharidBounds));

		overlay.render(graphics);

		assertEquals("exactly one row is outlined", 1, drawn.size());
		assertTrue("expected the hop's own row to be outlined", drawn.contains(emirsBounds));
	}

	// ------------------------------------------------------------------- helpers

	private GuideStatus status()
	{
		return tracker.getStatus();
	}

	/** Puts the given rows in the Portal Nexus's universe widget, the only list scanned. */
	private void nexusUniverse(Widget... rows)
	{
		Widget universe = Mockito.mock(Widget.class);
		when(universe.isHidden()).thenReturn(false);
		when(universe.getBounds()).thenReturn(new Rectangle(0, 0, 500, 500));
		when(universe.getDynamicChildren()).thenReturn(rows);
		when(client.getWidget(InterfaceID.TelenexusTeleport.UNIVERSE)).thenReturn(universe);
	}

	private static Widget row(String text, Rectangle bounds)
	{
		Widget row = Mockito.mock(Widget.class);
		when(row.isHidden()).thenReturn(false);
		when(row.getText()).thenReturn(text);
		when(row.getBounds()).thenReturn(bounds);
		when(row.getFont()).thenReturn(null);
		return row;
	}
}
