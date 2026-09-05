package com.dooglemaps.guide;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.TileObject;
import net.runelite.api.coords.LocalPoint;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The highlight has to outline the patch the step is about, and no other.
 *
 * <h2>The reported dead end</h2>
 *
 * {@code GuideOverlay} found a patch's scene objects by comparing the object's varbit id against
 * the patch's, and a varbit id is not unique across the map — the same numbers repeat at every
 * farm. So the scan lit whichever same-numbered patch happened to be in the loaded scene.
 *
 * <p>Reported from play on a <b>harvest-only bush run</b> at the Champions' Guild: the step
 * correctly read "harvest the cotton", fifty tiles away in Lumbridge, and the picked-clean
 * whiteberry bush lit up instead. On a harvest-only run the click that bush invites is the one
 * that clears it, destroying the plant the run exists to keep.
 *
 * <p>Newly reachable rather than newly wrong — {@code SharedStops} folds Lumbridge into the
 * Champions' Guild stop, so a current step can now name a patch that is not the one underfoot.
 */
public class PatchHighlightIdentityTest
{
	/** The pair from the report: one varbit, two farms, fifty tiles apart. */
	private static final int CHAMPIONS_GUILD = 12596;
	private static final int LUMBRIDGE = 12851;

	/** The Great Conch farming ship: filed under one region, physically spanning thirteen. */
	private static final int GREAT_CONCH = 12581;
	/** Where the Great Conch's calquat actually stands, per {@code WikiPatchLocations}. */
	private static final int GREAT_CONCH_CALQUAT_REGION = 12325;

	/** Falador and Port Sarim: two {@code FarmRegion}s that both claim region 12083. */
	private static final int FALADOR = 12083;
	private static final int PORT_SARIM = 12082;

	@Test
	public void theRightPatchInTheRightRegionMatches()
	{
		FarmPatch bush = patchIn(PatchImplementation.BUSH, CHAMPIONS_GUILD);

		assertTrue(GuideOverlay.objectIsThisPatch(
			bush.getVarbit(), CHAMPIONS_GUILD, bush));
	}

	/**
	 * The reported case: the cotton's varbit, matched against the bush standing in front of you.
	 *
	 * <p>Both patches carry the same varbit, so only the region tells them apart.
	 */
	@Test
	public void aSameVarbitPatchInAnotherRegionDoesNotMatch()
	{
		FarmPatch bush = patchIn(PatchImplementation.BUSH, CHAMPIONS_GUILD);
		FarmPatch hops = patchIn(PatchImplementation.HOPS, LUMBRIDGE);
		assertEquals("fixture: the collision this is about", bush.getVarbit(), hops.getVarbit());

		// The step is about the hops. The object in the scene is the bush.
		assertFalse("the bush is not the cotton, however alike their varbits",
			GuideOverlay.objectIsThisPatch(hops.getVarbit(), CHAMPIONS_GUILD, hops));

		// And the converse, so this is a two-way test rather than an accident of argument order.
		assertFalse(GuideOverlay.objectIsThisPatch(bush.getVarbit(), LUMBRIDGE, bush));
	}

	/** A different varbit in the right region is still not this patch. */
	@Test
	public void theRegionAloneIsNotEnough()
	{
		FarmPatch bush = patchIn(PatchImplementation.BUSH, CHAMPIONS_GUILD);
		assertFalse(GuideOverlay.objectIsThisPatch(
			bush.getVarbit() + 1, CHAMPIONS_GUILD, bush));
	}

	/**
	 * The collision is the rule, not the exception, which is why the region test has to be global.
	 *
	 * <p>Counted rather than asserted about one pair: if a future data regeneration ever made
	 * varbits unique per patch this would go green for a real reason, and until then it records
	 * how much of the map the ambiguity covers.
	 */
	@Test
	public void mostPatchesShareTheirVarbitWithAnother()
	{
		Map<Integer, Integer> perVarbit = new HashMap<>();
		for (FarmPatch patch : FarmingWorldData.getAllPatches())
		{
			perVarbit.merge(patch.getVarbit(), 1, Integer::sum);
		}

		int shared = 0;
		for (Map.Entry<Integer, Integer> entry : perVarbit.entrySet())
		{
			if (entry.getValue() > 1)
			{
				shared += entry.getValue();
			}
		}

		assertTrue("a varbit id names a patch TYPE across the map, not one patch - so the "
				+ "highlight cannot identify a patch by varbit alone (" + shared + " patches "
				+ "share one with at least one other)",
			shared > FarmingWorldData.getAllPatches().size() / 2);
	}

	/**
	 * A region can span more than one 64x64 map square, and the object sits wherever the patch
	 * physically is — not necessarily in the square the region is filed under.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * Reported from play at the Great Conch: the step "check the health of the calquat" marked a
	 * bare tile at the ship's corner instead of outlining the tree. The Great Conch is filed
	 * under region 12581, but its calquat physically stands in region 12325 — one of the
	 * thirteen regions the ship spans. Requiring an exact region match on top of the varbit
	 * match failed even though the varbit was right, so the scan found no object and the
	 * highlight fell back to a marked tile.
	 */
	@Test
	public void aPatchMatchesAnObjectInAnyRegionItsShipSpans()
	{
		FarmPatch calquat = patchIn(PatchImplementation.CALQUAT, GREAT_CONCH);

		assertTrue("the region the calquat is filed under still matches",
			GuideOverlay.objectIsThisPatch(calquat.getVarbit(), GREAT_CONCH, calquat));

		assertTrue("the region the calquat physically stands in, one of the ship's thirteen, "
				+ "must match too",
			GuideOverlay.objectIsThisPatch(calquat.getVarbit(), GREAT_CONCH_CALQUAT_REGION, calquat));
	}

	/** The same varbit in a region the Great Conch's ship does not span is still not a match. */
	@Test
	public void aPatchDoesNotMatchARegionItsShipDoesNotSpan()
	{
		FarmPatch calquat = patchIn(PatchImplementation.CALQUAT, GREAT_CONCH);
		assertFalse("Falador is nowhere near the Great Conch's ship",
			GuideOverlay.objectIsThisPatch(calquat.getVarbit(), FALADOR, calquat));
	}

	/**
	 * Two {@code FarmRegion}s can claim the same region id — Port Sarim's spirit tree patch
	 * lists Falador's region (12083) as an extra, since both regions' varbits can be live there.
	 * But Falador's allotment patch owns 12083 outright and shares the spirit tree's varbit
	 * number, so an allotment object standing on its own canonical ground must not satisfy a
	 * search for the spirit tree.
	 */
	@Test
	public void anExtraRegionDoesNotOverrideAnotherPatchsCanonicalGround()
	{
		FarmPatch spiritTree = patchIn(PatchImplementation.SPIRIT_TREE, PORT_SARIM);
		FarmPatch allotment = patchIn(PatchImplementation.ALLOTMENT, FALADOR);
		assertEquals("fixture: the collision this is about",
			spiritTree.getVarbit(), allotment.getVarbit());

		assertFalse("Falador's own ground must not answer for Port Sarim's spirit tree",
			GuideOverlay.objectIsThisPatch(spiritTree.getVarbit(), FALADOR, spiritTree));
	}

	private static FarmPatch patchIn(PatchImplementation type, int regionId)
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(type))
		{
			if (patch.getRegion().getRegionId() == regionId)
			{
				return patch;
			}
		}
		throw new AssertionError("no " + type + " patch in region " + regionId);
	}

	/**
	 * An object that no longer stands in the scene is not asked to draw itself.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"when a patch/tree/idk gets highlighted, ALL of the NPCs in the area start flickering
	 * with the same colour highlighting, rapidly, extremely rapidly. Including my own player
	 * character."</i>
	 *
	 * <p>The same stale object behind the overlay NPEs, on the frames where it does not have the
	 * decency to throw. The scans run on a tick and the overlay draws every frame, so after a
	 * scene reload the caches hold objects whose scene data the client has freed and reused.
	 * {@code getClickbox} walks that memory and raises a {@code NullPointerException} — loud, and
	 * the half that got noticed first. {@code ModelOutlineRenderer} walks it too and does not
	 * throw: it draws an outline from whatever now occupies those slots, which is the NPCs
	 * standing around you and your own player. The flicker is per-frame because the reused memory
	 * changes per frame.
	 *
	 * <p>So a try/catch was never going to help this one — nothing was throwing. What it needed
	 * was the question nobody was asking: is this object still here?
	 */
	@Test
	public void anObjectOutsideTheSceneIsNotDrawn()
	{
		TileObject inScene = Mockito.mock(TileObject.class);
		// A LocalPoint is in units of 1/128 of a tile, so scene tile 20 is 20 * 128.
		when(inScene.getLocalLocation()).thenReturn(new LocalPoint(20 * 128, 20 * 128));
		assertTrue("an ordinary object in front of the player draws",
			GuideOverlay.standsInTheScene(inScene));

		// What a freed object looks like: it still answers, and its answer is off the map.
		TileObject stale = Mockito.mock(TileObject.class);
		when(stale.getLocalLocation()).thenReturn(new LocalPoint(-1_000_000, -1_000_000));
		assertFalse("an object the scene no longer holds is not handed to the renderer",
			GuideOverlay.standsInTheScene(stale));

		TileObject nowhere = Mockito.mock(TileObject.class);
		when(nowhere.getLocalLocation()).thenReturn(null);
		assertFalse("nor one with no position at all",
			GuideOverlay.standsInTheScene(nowhere));
	}
}
