package com.dooglemaps.guide;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.route.PatchLocationStore;
import com.dooglemaps.state.PlayerHouse;
import java.awt.Color;
import java.awt.Graphics2D;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Farming Guild has one tool leprechaun per wing. {@link GuideOverlay#highlightLeprechaun}
 * used to outline the first one it found in scene order, which at the guild was whichever wing
 * happened to load first — the redwood wing's, in a screenshot from play, while the player stood
 * at the fountain wing's leprechaun instead. Fixed to pick the one nearest the player.
 */
public class TheNearestLeprechaunIsOutlinedTest
{
	private static final WorldPoint PLAYER_LOCATION = new WorldPoint(3700, 3800, 0);

	private Client client;
	private GuideOverlay overlay;
	private NPC far;
	private NPC near;
	private NPC middle;

	private void setUp(WorldPoint playerLocation)
	{
		client = Mockito.mock(Client.class);
		WorldView worldView = Mockito.mock(WorldView.class);
		when(client.getTopLevelWorldView()).thenReturn(worldView);

		far = leprechaun(30);
		near = leprechaun(3);
		middle = leprechaun(12);

		// Listed far-first, so a first-match rule would pick the wrong one.
		Mockito.doReturn(indexedSet(far, middle, near)).when(worldView).npcs();

		if (playerLocation != null)
		{
			Player player = Mockito.mock(Player.class);
			when(player.getWorldLocation()).thenReturn(playerLocation);
			when(client.getLocalPlayer()).thenReturn(player);
		}

		DoogleMapsConfig config = Mockito.mock(DoogleMapsConfig.class);
		overlay = construct(GuideOverlay.class, client, Mockito.mock(GuideTracker.class), config,
			Mockito.mock(ModelOutlineRenderer.class), Mockito.mock(PatchLocationStore.class),
			Mockito.mock(PlayerHouse.class), Mockito.mock(DroppedProduce.class),
			Mockito.mock(SeaweedSpores.class));
	}

	@Test
	public void theNearestLeprechaunIsOutlinedOnly() throws Exception
	{
		setUp(PLAYER_LOCATION);

		highlightLeprechaun();

		verify(near, times(1)).getConvexHull();
		verify(far, never()).getConvexHull();
		verify(middle, never()).getConvexHull();
	}

	@Test
	public void aCloserLeprechaunOnAnotherPlaneIsNotChosen() throws Exception
	{
		setUp(PLAYER_LOCATION);

		// Two tiles away in x/y, but on a different plane - closer than every leprechaun above
		// ground if plane were ignored, and must not be picked over the real nearest.
		NPC otherPlane = leprechaun(1);
		when(otherPlane.getWorldLocation()).thenReturn(
			new WorldPoint(PLAYER_LOCATION.getX() + 2, PLAYER_LOCATION.getY(), 1));
		WorldView worldView = client.getTopLevelWorldView();
		Mockito.doReturn(indexedSet(far, otherPlane, middle, near)).when(worldView).npcs();

		highlightLeprechaun();

		verify(near, times(1)).getConvexHull();
		verify(otherPlane, never()).getConvexHull();
		verify(far, never()).getConvexHull();
		verify(middle, never()).getConvexHull();
	}

	@Test
	public void withNoLocalPlayerTheFirstIsOutlined() throws Exception
	{
		setUp(null);

		highlightLeprechaun();

		verify(far, times(1)).getConvexHull();
		verify(near, never()).getConvexHull();
		verify(middle, never()).getConvexHull();
	}

	private void highlightLeprechaun() throws Exception
	{
		Method method = GuideOverlay.class.getDeclaredMethod(
			"highlightLeprechaun", Graphics2D.class, Color.class);
		method.setAccessible(true);
		method.invoke(overlay, Mockito.mock(Graphics2D.class), Color.RED);
	}

	private static NPC leprechaun(int distance)
	{
		NPC npc = Mockito.mock(NPC.class);
		when(npc.getName()).thenReturn("Tool Leprechaun");
		when(npc.getWorldLocation()).thenReturn(
			new WorldPoint(PLAYER_LOCATION.getX() + distance, PLAYER_LOCATION.getY(), 0));
		return npc;
	}

	/** The minimal {@link IndexedObjectSet} the overlay's plain for-each actually needs. */
	private static IndexedObjectSet<NPC> indexedSet(NPC... npcs)
	{
		List<NPC> list = Arrays.asList(npcs);
		return new IndexedObjectSet<NPC>()
		{
			@Override
			public NPC byIndex(int index)
			{
				return list.get(index);
			}

			@Override
			public Iterator<NPC> iterator()
			{
				return list.iterator();
			}
		};
	}
}
