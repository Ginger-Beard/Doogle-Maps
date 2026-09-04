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
import net.runelite.api.WorldView;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fossil Island has three hardwood patches, tended by three different squirrels who share a
 * generated name. The step for the East patch has to outline East's own squirrel, whichever
 * order the scene happens to list them in.
 *
 * <h2>The bug this pins</h2>
 *
 * {@link GuideOverlay#highlightNpcById} used to scan the scene for the first NPC matching by
 * {@code FarmerVariants.same(stepNpcId, npc.getId())} and stop there. Once the shared-name
 * fallback treated all three squirrels as one gardener, "first in world order" — not "the one
 * the step actually names" — decided which squirrel got outlined, for every one of the three
 * steps. Fixed at two levels: {@code FarmerVariants} no longer folds the three together, and
 * this method now tries an exact id match before ever falling back to a variant one.
 */
public class EachSquirrelIsOutlinedForItsOwnPatchTest
{
	private Client client;
	private GuideOverlay overlay;
	private NPC east;
	private NPC middle;
	private NPC west;

	@Before
	public void setUp()
	{
		client = Mockito.mock(Client.class);
		WorldView worldView = Mockito.mock(WorldView.class);
		when(client.getTopLevelWorldView()).thenReturn(worldView);

		east = squirrel(NpcID.FOSSIL_SQUIRREL_GARDENER1);
		middle = squirrel(NpcID.FOSSIL_SQUIRREL_GARDENER2);
		west = squirrel(NpcID.FOSSIL_SQUIRREL_GARDENER3);

		// The wrong ones come first - exactly the order that made the old first-match rule
		// outline the wrong squirrel for every step but the last.
		//
		// doReturn rather than when(...).thenReturn(...): npcs() returns
		// IndexedObjectSet<? extends NPC>, and the wildcard capture defeats thenReturn's own
		// generic inference for an IndexedObjectSet<NPC> built here.
		Mockito.doReturn(indexedSet(middle, west, east)).when(worldView).npcs();

		DoogleMapsConfig config = Mockito.mock(DoogleMapsConfig.class);
		overlay = construct(GuideOverlay.class, client, Mockito.mock(GuideTracker.class), config,
			Mockito.mock(ModelOutlineRenderer.class), Mockito.mock(PatchLocationStore.class),
			Mockito.mock(PlayerHouse.class), Mockito.mock(DroppedProduce.class),
			Mockito.mock(SeaweedSpores.class));
	}

	@Test
	public void theEastStepOutlinesEastsSquirrelOnly() throws Exception
	{
		highlightNpcById(NpcID.FOSSIL_SQUIRREL_GARDENER1);

		// outlineNpc always reads the NPC's convex hull, whichever branch the config style
		// takes - so which NPC was chosen is exactly which NPC that call landed on.
		verify(east, times(1)).getConvexHull();
		verify(middle, never()).getConvexHull();
		verify(west, never()).getConvexHull();
	}

	@Test
	public void theMiddleStepOutlinesMiddlesSquirrelOnly() throws Exception
	{
		highlightNpcById(NpcID.FOSSIL_SQUIRREL_GARDENER2);

		verify(middle, times(1)).getConvexHull();
		verify(east, never()).getConvexHull();
		verify(west, never()).getConvexHull();
	}

	@Test
	public void theWestStepOutlinesWestsSquirrelOnly() throws Exception
	{
		highlightNpcById(NpcID.FOSSIL_SQUIRREL_GARDENER3);

		verify(west, times(1)).getConvexHull();
		verify(east, never()).getConvexHull();
		verify(middle, never()).getConvexHull();
	}

	private void highlightNpcById(int npcId) throws Exception
	{
		Method method = GuideOverlay.class.getDeclaredMethod(
			"highlightNpcById", Graphics2D.class, Color.class, int.class);
		method.setAccessible(true);
		method.invoke(overlay, Mockito.mock(Graphics2D.class), Color.RED, npcId);
	}

	private static NPC squirrel(int npcId)
	{
		NPC npc = Mockito.mock(NPC.class);
		when(npc.getId()).thenReturn(npcId);
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
