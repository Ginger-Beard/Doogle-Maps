package com.dooglemaps.guide;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.route.PatchLocationStore;
import com.dooglemaps.state.PlayerHouse;
import java.awt.Color;
import java.awt.Graphics2D;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.runelite.api.Client;
import net.runelite.api.TileObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A hop that names its vehicle outright must win outright, over any other piece of furniture
 * that merely happens to reach the same place.
 *
 * <h2>The reported dead end, twice</h2>
 *
 * <i>"Digsite Digsite Pendant - 1: Digsite"</i> outlined the mounted pendant AND the portal
 * nexus, because the nexus can be attuned to the Digsite and so carries "digsite" on its own
 * destination list too — the same list {@link HouseTeleports#furnitureServesHop} checks
 * regardless of whether the hop actually named the nexus. A second report landed the same bug
 * through the jewellery box: <i>"Teleport Menu Ornate Jewellery Box - M: Falador Park"</i> lit
 * the box AND the nexus, since Falador is on both destination lists too. A third landed it three
 * at once: <i>"Teleport to House, Home Portal, Travel Spirit Tree - 4: Grand Exchange"</i> lit
 * the spirit tree, the nexus, AND a mounted glory, because the Grand Exchange is on every one of
 * their destination lists.
 *
 * <p>{@code GuideOverlay.highlightHouseTeleports} now asks in two tiers: furniture the hop
 * <b>names</b> ({@link HouseTeleports#furnitureNamedByHop}), and only when nothing was named,
 * furniture that merely <b>reaches</b> the same place. A route that names its vehicle has
 * already made the choice; a second piece of furniture that only coincides on the destination
 * is noise once that choice is on the table.
 */
public class ANamedVehicleOutshinesFurnitureThatMerelyReachesTheSamePlaceTest
{
	private static final List<String> DIGSITE_HOPS =
		Arrays.asList("Teleport to House", "Digsite Digsite Pendant - 1: Digsite");

	private static final List<String> FALADOR_PARK_HOPS = Arrays.asList("Teleport to House",
		"Teleport Menu Ornate Jewellery Box - M: Falador Park");

	private GuideTracker tracker;
	private PlayerHouse house;
	private DoogleMapsConfig config;
	private ModelOutlineRenderer outlineRenderer;
	private GuideOverlay overlay;

	/** Every piece of furniture standing in the room this test's house scans, by its own name. */
	private final Map<String, TileObject> room = new LinkedHashMap<>();

	@Before
	public void setUp()
	{
		tracker = mock(GuideTracker.class);
		house = mock(PlayerHouse.class);
		config = mock(DoogleMapsConfig.class);
		outlineRenderer = mock(ModelOutlineRenderer.class);

		when(config.guideHighlightStyle()).thenReturn(DoogleMapsConfig.GuideHighlightStyle.OUTLINE);

		// house.matchingFurniture is the real scan's contract: hand it a name test, get back the
		// scene objects whose resolved name passes. Standing in for the client-backed scan is
		// exactly this fixed room, tested against whatever predicate GuideOverlay builds.
		when(house.matchingFurniture(any())).thenAnswer(invocation ->
		{
			@SuppressWarnings("unchecked")
			Predicate<String> nameTest = invocation.getArgument(0);
			List<TileObject> found = new java.util.ArrayList<>();
			for (Map.Entry<String, TileObject> piece : room.entrySet())
			{
				if (nameTest.test(piece.getKey()))
				{
					found.add(piece.getValue());
				}
			}
			return found;
		});

		overlay = new GuideOverlay(mock(Client.class), tracker, config, outlineRenderer,
			mock(PatchLocationStore.class), house, mock(DroppedProduce.class),
			mock(SeaweedSpores.class));
	}

	/** The reported case itself: the pendant is named, so the nexus must stay dark. */
	@Test
	public void theNamedPendantOutshinesTheNexus()
	{
		TileObject nexus = furniturePiece("Portal Nexus");
		TileObject pendant = furniturePiece("Digsite Pendant");

		liveTransports(DIGSITE_HOPS);

		overlay.highlightHouseTeleports(mock(Graphics2D.class), Color.CYAN);

		verify(outlineRenderer).drawOutline(eq(pendant), anyInt(), eq(Color.CYAN), anyInt());
		verify(outlineRenderer, never())
			.drawOutline(eq(nexus), anyInt(), any(Color.class), anyInt());
	}

	/**
	 * The second report: the same fault through the jewellery box. "Falador Park" is on both
	 * the nexus's and the jewellery box's destination lists, but only the box's own name is in
	 * the hop, so {@link HouseTeleports#furnitureNamedByHop} — the named tier — picks the box
	 * alone and the fallback destination match never runs.
	 */
	@Test
	public void theNamedJewelleryBoxOutshinesTheNexus()
	{
		TileObject nexus = furniturePiece("Portal Nexus");
		TileObject box = furniturePiece("Ornate Jewellery Box");

		liveTransports(FALADOR_PARK_HOPS);

		overlay.highlightHouseTeleports(mock(Graphics2D.class), Color.CYAN);

		verify(outlineRenderer).drawOutline(eq(box), anyInt(), eq(Color.CYAN), anyInt());
		verify(outlineRenderer, never())
			.drawOutline(eq(nexus), anyInt(), any(Color.class), anyInt());
	}

	/**
	 * With no vehicle named in the hop text, the fallback survives: both pieces that actually
	 * reach the bare destination still light.
	 */
	@Test
	public void aBareDestinationStillFallsBackToBothThatReachIt()
	{
		TileObject nexus = furniturePiece("Portal Nexus");
		TileObject pendant = furniturePiece("Digsite Pendant");

		liveTransports(Arrays.asList("Teleport to House", "Digsite"));

		overlay.highlightHouseTeleports(mock(Graphics2D.class), Color.CYAN);

		verify(outlineRenderer).drawOutline(eq(nexus), anyInt(), eq(Color.CYAN), anyInt());
		verify(outlineRenderer).drawOutline(eq(pendant), anyInt(), eq(Color.CYAN), anyInt());
	}

	/**
	 * A jewellery box hop with a spirit tree also standing in the room, its guild patch empty.
	 * The box's own name is in the hop, so the named tier picks it alone; the spirit tree is
	 * never even asked about the grown-tree guard, because nothing named it.
	 */
	@Test
	public void aNamedJewelleryBoxOutshinesAnUngrownSpiritTree()
	{
		TileObject box = furniturePiece("Fancy Jewellery Box");
		TileObject spiritTree = furniturePiece("Spirit tree");
		when(tracker.spiritTreeUsableFor(Mockito.anyString())).thenReturn(false);

		liveTransports(Arrays.asList("Teleport to House",
			"Fancy Jewellery Box - J: Farming Guild"));

		overlay.highlightHouseTeleports(mock(Graphics2D.class), Color.CYAN);

		verify(outlineRenderer).drawOutline(eq(box), anyInt(), eq(Color.CYAN), anyInt());
		verify(outlineRenderer, never())
			.drawOutline(eq(spiritTree), anyInt(), any(Color.class), anyInt());
	}

	/**
	 * The third report: a spirit tree hop naming the network's Grand Exchange stop, with a
	 * nexus and a mounted glory both in the room and both able to reach the Grand Exchange too.
	 * "Home Portal" is the exit, named alongside the real hop the way Shortest Path always lists
	 * the house's own leg first - and the bare word "Portal" is deliberately unmatchable (see
	 * {@link HouseTeleports#furnitureNamedByHop}), so it cannot pull the exit into the named set
	 * either.
	 */
	@Test
	public void theNamedSpiritTreeOutshinesTheNexusAndTheGlory()
	{
		TileObject nexus = furniturePiece("Portal Nexus");
		TileObject spiritTree = furniturePiece("Spirit tree");
		TileObject glory = furniturePiece("Mounted Glory");

		// The Grand Exchange is one of the network's own stops, not a grown farm patch, so the
		// grown-tree guard passes it - see HouseTeleports.spiritTreePatchFor and
		// GuideTracker.spiritTreeUsableFor.
		when(tracker.spiritTreeUsableFor(Mockito.anyString())).thenReturn(true);

		liveTransports(Arrays.asList("Teleport to House", "Home Portal",
			"Travel Spirit Tree - 4: Grand Exchange"));

		overlay.highlightHouseTeleports(mock(Graphics2D.class), Color.CYAN);

		verify(outlineRenderer).drawOutline(eq(spiritTree), anyInt(), eq(Color.CYAN), anyInt());
		verify(outlineRenderer, never())
			.drawOutline(eq(nexus), anyInt(), any(Color.class), anyInt());
		verify(outlineRenderer, never())
			.drawOutline(eq(glory), anyInt(), any(Color.class), anyInt());

		// The exit's own bare name never enters the named tier, so a hop that merely mentions
		// "Portal" - the exit's name in every house - can never be read as naming it.
		org.junit.Assert.assertFalse("a bare \"Portal\" name is deliberately unmatchable",
			HouseTeleports.furnitureNamedByHop("Portal", "Home Portal"));
	}

	// ------------------------------------------------------------------- helpers

	/** A named piece of furniture, standing in the scene at a scanned, in-scene tile. */
	private TileObject furniturePiece(String name)
	{
		TileObject object = mock(TileObject.class);
		// A LocalPoint is in units of 1/128 of a tile; any in-scene tile does, per
		// PatchHighlightIdentityTest's own fixture.
		when(object.getLocalLocation()).thenReturn(new LocalPoint(20 * 128, 20 * 128));
		room.put(name, object);
		return object;
	}

	/** What the route currently reports, live - see GuideTracker.liveTransports. */
	private void liveTransports(List<String> hops)
	{
		when(tracker.liveTransports()).thenReturn(hops);

		GuideStatus status = mock(GuideStatus.class);
		when(status.getTravelHint())
			.thenReturn(new TravelHint(-1, null, "Digsite", TravelHint.Where.UNOWNED));
		when(tracker.getStatus()).thenReturn(status);
	}
}
