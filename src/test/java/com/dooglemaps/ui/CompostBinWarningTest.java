package com.dooglemaps.ui;

import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.route.RunPlanner;
import com.dooglemaps.state.CompostRunStore;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import javax.swing.JComponent;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * The run panel's "nothing will be planted" line, asked about a compost bin.
 *
 * <h2>What it looked like</h2>
 *
 * The line walks every ticked type and asks the <b>seed</b> selection what will go in it. A
 * compost bin has no seeds at all — its equivalent is the fill item, which lives in
 * {@code CompostRunStore} — so the answer was always "none" and the panel read
 * <i>"No seed picked for: compost bin"</i> with a fill plainly selected on the tab above.
 * Reported from play.
 *
 * <p>The replacement is not simply silence: a bin the run could fill, with nothing chosen to
 * fill it with, is worth saying — the run still routes there and the guide then has nothing
 * to offer. But emptying finished compost is a complete bin run on its own, so the warning is
 * gated on there actually being a fillable bin.
 */
public class CompostBinWarningTest
{
	private static final Set<PatchImplementation> BINS =
		EnumSet.of(PatchImplementation.COMPOST);

	private RunPanel panel;
	private CompostRunStore compostRun;
	private RunPlanner planner;

	@Before
	public void setUp() throws Exception
	{
		com.dooglemaps.state.PlantingGroups groups =
			Mockito.mock(com.dooglemaps.state.PlantingGroups.class);
		when(groups.runOptions()).thenReturn(Collections.emptyList());

		planner = Mockito.mock(RunPlanner.class);
		compostRun = Mockito.mock(CompostRunStore.class);

		com.dooglemaps.DoogleMapsConfig config =
			Mockito.mock(com.dooglemaps.DoogleMapsConfig.class);
		when(config.guideHighlightColour()).thenReturn(java.awt.Color.CYAN);

		panel = construct(RunPanel.class,
			Mockito.mock(PanelLayoutStore.class),
			groups,
			Mockito.mock(com.dooglemaps.state.ProtectionSelectionStore.class),
			Mockito.mock(com.dooglemaps.bank.BankContents.class),
			Mockito.mock(com.dooglemaps.guide.CarriedItems.class),
			planner,
			Mockito.mock(com.dooglemaps.state.SeedSelectionStore.class),
			Mockito.mock(com.dooglemaps.state.SeedInventoryStore.class),
			Mockito.mock(com.dooglemaps.state.RunTypeStore.class),
			Mockito.mock(com.dooglemaps.state.FarmingBonusStore.class),
			Mockito.mock(com.dooglemaps.state.CompostSelectionStore.class),
			config,
			Mockito.mock(com.dooglemaps.guide.GuideTracker.class),
			compostRun,
			Mockito.mock(com.dooglemaps.state.RunPresetStore.class));
	}

	/** The reported bug: a chosen fill, and the line still cried "no seed". */
	@Test
	public void aChosenFillSilencesTheWarning() throws Exception
	{
		when(compostRun.hasFill()).thenReturn(true);
		snapshot(1);

		assertFalse("a bin has no seed to pick, so the line must not ask for one",
			warningShown());
	}

	/** A fillable bin with nothing picked is worth saying — in the bin's own words. */
	@Test
	public void anUnpickedFillIsWarnedAboutAsAFill() throws Exception
	{
		when(compostRun.hasFill()).thenReturn(false);
		snapshot(1);

		assertTrue(warningShown());
		assertTrue("worded for a bin, not a seed: " + warningText(),
			warningText().contains("No fill picked for the compost bins."));
		assertFalse("and never as a seed", warningText().contains("No seed picked"));
	}

	/**
	 * Bins that are all full of finished compost want no fill, so nothing is said.
	 *
	 * <p>Collecting is a complete bin run by itself — the buckets come from the leprechaun and
	 * no produce is involved — and a permanent warning about a fill that trip never wanted is
	 * the shape of nag that gets a whole warning line ignored.
	 */
	@Test
	public void binsWithNothingToFillAreNotNaggedAbout() throws Exception
	{
		when(compostRun.hasFill()).thenReturn(false);
		snapshot(0);

		assertFalse(warningShown());
	}

	/**
	 * The count comes off the snapshot, and the planner's monitor is never taken from here.
	 *
	 * <h2>What this pins</h2>
	 *
	 * This line used to call {@code RunPlanner.binWork} directly — synchronised, walking every
	 * bin of every ticked type — from the Swing thread, on every refresh, while the client
	 * thread walked the same methods every tick. It was the last query in this panel still
	 * doing that; the other five moved to {@code RunSnapshot} when it was introduced and this
	 * one arrived afterwards, so there was no field for it. The {@code never()} is the point of
	 * the test: a future edit that reaches for the planner again fails here rather than in a
	 * freeze report.
	 *
	 * <p>Both bin sizes answering to the one tick is still tested, in
	 * {@code RunPlannerTest.fillableBinsInFoldsTheTickToTheGuildsBigBin} — the fold moved into
	 * the planner with the call it guards.
	 */
	@Test
	public void theCountComesFromTheSnapshotAndNotThePlanner() throws Exception
	{
		when(compostRun.hasFill()).thenReturn(false);
		snapshot(1);

		assertTrue("a fillable bin still earns the line", warningShown());
		Mockito.verify(planner, Mockito.never()).binWork(Mockito.any());
	}

	/**
	 * Publishes a snapshot keyed on the selection under test, which is what makes the panel
	 * read it rather than fall back to asking the planner live.
	 */
	private void snapshot(int fillableBins)
	{
		when(planner.getSnapshot()).thenReturn(new com.dooglemaps.route.RunSnapshot(
			BINS,
			Collections.emptyList(),
			Collections.emptyMap(),
			Collections.emptyMap(),
			Collections.emptyMap(),
			Collections.emptyMap(),
			fillableBins));
	}

	private boolean warningShown() throws Exception
	{
		update();
		return noSeeds().isVisible();
	}

	private String warningText() throws Exception
	{
		update();
		return String.valueOf(noSeeds().getClass()
			.getMethod("getText").invoke(noSeeds()));
	}

	private void update() throws Exception
	{
		Method method = RunPanel.class.getDeclaredMethod("updateNoSeeds", Set.class);
		method.setAccessible(true);
		method.invoke(panel, BINS);
	}

	private JComponent noSeeds() throws Exception
	{
		java.lang.reflect.Field field = RunPanel.class.getDeclaredField("noSeeds");
		field.setAccessible(true);
		return (JComponent) field.get(panel);
	}
}
