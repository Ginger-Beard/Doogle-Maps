package com.dooglemaps.guide;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The note step leads when the pack is full, because nothing else can go first.
 *
 * <h2>The reported dead end</h2>
 *
 * <i>"noting potato cactus step didn't highlight the potato cactus or the lep"</i> — with the
 * session log showing the note listed <b>second</b>, behind a harvest for another patch:
 * {@code 4922.7909=HARVEST; 4922.4775=NOTE_AT_LEPRECHAUN}. The overlays light the current step
 * only, so a listed-but-not-current note lights nothing — and at zero free slots the harvest in
 * front of it could not accept a single item anyway. The player followed the note by hand,
 * unlit; the order said one thing and the game permitted only the other.
 */
public class FullPackNoteOrderTest
{
	@Test
	public void aFullPackPutsTheNoteInFrontOfTheHarvest()
	{
		List<GuideStep> steps = list(harvest(), note());

		GuideTracker.noteLeadsWhenThePackIsFull(steps, 0);

		assertEquals(GuideAction.NOTE_AT_LEPRECHAUN, steps.get(0).getAction());
		assertEquals(GuideAction.HARVEST, steps.get(1).getAction());
	}

	/** Any room at all and the harvest can genuinely proceed, so the order stands. */
	@Test
	public void aPackWithRoomLeavesTheHarvestFirst()
	{
		List<GuideStep> steps = list(harvest(), note());

		GuideTracker.noteLeadsWhenThePackIsFull(steps, 1);

		assertEquals(GuideAction.HARVEST, steps.get(0).getAction());
	}

	/**
	 * A leading step that is not a harvest is left alone — paying, planting and bin work need
	 * no free slot, and their orderings are their own.
	 */
	@Test
	public void aLeadingNonHarvestIsNotDisplaced()
	{
		List<GuideStep> steps = list(
			GuideStep.of(GuideAction.PLANT, anyPatch(), "Plant."), note());

		GuideTracker.noteLeadsWhenThePackIsFull(steps, 0);

		assertEquals(GuideAction.PLANT, steps.get(0).getAction());
	}

	/** No note in the list means nothing to hoist, full pack or not. */
	@Test
	public void noNoteMeansNoChange()
	{
		List<GuideStep> steps = list(harvest(), harvest());

		GuideTracker.noteLeadsWhenThePackIsFull(steps, 0);

		assertEquals(GuideAction.HARVEST, steps.get(0).getAction());
		assertEquals(2, steps.size());
	}

	private static GuideStep harvest()
	{
		return GuideStep.of(GuideAction.HARVEST, anyPatch(), "Harvest.");
	}

	private static GuideStep note()
	{
		return GuideStep.atLeprechaun(GuideAction.NOTE_AT_LEPRECHAUN, anyPatch(), 1, null,
			"Note your things.");
	}

	private static FarmPatch anyPatch()
	{
		return FarmingWorldData.getPatches(PatchImplementation.HERB).get(0);
	}

	private static List<GuideStep> list(GuideStep... steps)
	{
		return new ArrayList<>(java.util.Arrays.asList(steps));
	}
}
