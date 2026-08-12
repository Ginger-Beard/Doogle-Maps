package com.dooglemaps.ui;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.data.RunOption;
import com.dooglemaps.state.RunTypeStore;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JCheckBox;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * How the run list reads once every patch type is on it.
 *
 * <p>Long and mostly unticked for any one player — a tree rotation and a herb circuit are
 * different weeks — so which lines are in the run has to be visible without reading the boxes.
 * Asked for from play alongside the alphabetical ordering, and the two are the same complaint:
 * the list had outgrown being scanned.
 */
public class RunListAppearanceTest
{
	private RunTypeStore runTypes;
	private RunPanel panel;

	@Before
	public void setUp()
	{
		com.dooglemaps.state.PlantingGroups groups =
			Mockito.mock(com.dooglemaps.state.PlantingGroups.class);
		// Two ordinary lines, one ticked and one not, which is all the appearance rule needs.
		when(groups.runOptions()).thenReturn(Arrays.asList(
			RunOption.full(PlantingGroup.of(PatchImplementation.HERB)),
			RunOption.full(PlantingGroup.of(PatchImplementation.TREE)),
			// A pair, for the counterpart-dimming case below.
			RunOption.full(PlantingGroup.of(PatchImplementation.BUSH)),
			RunOption.harvestOnly(PlantingGroup.of(PatchImplementation.BUSH))));

		runTypes = Mockito.mock(RunTypeStore.class);
		when(runTypes.isSelected(RunOption.full(PlantingGroup.of(PatchImplementation.HERB))))
			.thenReturn(true);

		DoogleMapsConfig config = Mockito.mock(DoogleMapsConfig.class);
		when(config.guideHighlightColour()).thenReturn(Color.CYAN);
		// Every type on offer, so neither line is filtered out of the list.
		when(config.showHerb()).thenReturn(true);
		when(config.showTree()).thenReturn(true);
		when(config.showBush()).thenReturn(true);

		panel = construct(RunPanel.class,
			Mockito.mock(PanelLayoutStore.class),
			groups,
			Mockito.mock(com.dooglemaps.state.ProtectionSelectionStore.class),
			Mockito.mock(com.dooglemaps.bank.BankContents.class),
			Mockito.mock(com.dooglemaps.guide.CarriedItems.class),
			Mockito.mock(com.dooglemaps.route.RunPlanner.class),
			Mockito.mock(com.dooglemaps.state.SeedSelectionStore.class),
			Mockito.mock(com.dooglemaps.state.SeedInventoryStore.class),
			runTypes,
			Mockito.mock(com.dooglemaps.state.FarmingBonusStore.class),
			Mockito.mock(com.dooglemaps.state.CompostSelectionStore.class),
			config,
			Mockito.mock(com.dooglemaps.guide.GuideTracker.class),
			Mockito.mock(com.dooglemaps.state.CompostRunStore.class));
	}

	/**
	 * A line in the run is drawn brighter than one that is not.
	 *
	 * <p>Asserted as a relationship rather than against two hex values, because the point is
	 * the contrast: a later palette change should be free, and a change that flattens the two
	 * back together should not.
	 */
	@Test
	public void untickedLinesAreDimmerThanTickedOnes()
	{
		List<JCheckBox> boxes = boxesIn(panel);
		assertTrue("the lines should be built", boxes.size() >= 2);

		JCheckBox ticked = boxes.stream().filter(JCheckBox::isSelected).findFirst()
			.orElseThrow(() -> new AssertionError("no ticked line was built"));
		JCheckBox unticked = boxes.stream().filter(box -> !box.isSelected()).findFirst()
			.orElseThrow(() -> new AssertionError("no unticked line was built"));

		assertNotEquals("the two must not read the same",
			ticked.getForeground(), unticked.getForeground());
		assertTrue("the unticked line should be the dimmer of the two",
			brightness(unticked.getForeground()) < brightness(ticked.getForeground()));
	}

	/** Dimmed, not hidden: an unticked line is the thing you click to change the run. */
	@Test
	public void anUntickedLineStaysLegible()
	{
		JCheckBox unticked = boxesIn(panel).stream().filter(box -> !box.isSelected())
			.findFirst().orElseThrow(() -> new AssertionError("no unticked line"));

		assertTrue("too dark to read against the sidebar: " + unticked.getForeground(),
			brightness(unticked.getForeground()) > 100);
	}

	/**
	 * A line unticked automatically dims like one unticked by hand.
	 *
	 * <p>Ticking a full run unticks its harvest-only counterpart, and that is done with
	 * {@code setSelected} — which does not fire an ActionListener, so the counterpart never ran
	 * the handler that recolours it and stayed bright while unchecked. Reported from play as
	 * "Bush and Cactus always stay bright when unchecked": the paired types are the only ones
	 * this can happen to.
	 */
	@Test
	public void aCounterpartUntickedByThePanelDimsToo() throws Exception
	{
		JCheckBox harvestOnly = boxLabelled("Bush (H/O)");
		harvestOnly.setSelected(true);
		dim(harvestOnly);
		assertTrue("the fixture should start from a bright, ticked line",
			brightness(harvestOnly.getForeground()) > 200);

		// What ticking the full run does to its counterpart. Called directly rather than
		// through the checkbox's listener, which would drive a whole panel refresh and needs
		// run state this fixture has no reason to invent.
		java.lang.reflect.Method untick =
			RunPanel.class.getDeclaredMethod("untick", RunOption.class);
		untick.setAccessible(true);
		untick.invoke(panel, RunOption.harvestOnly(PlantingGroup.of(PatchImplementation.BUSH)));

		assertFalse("the pair is mutually exclusive", harvestOnly.isSelected());
		assertTrue("...and the one that lost its tick must go dim with it: "
				+ harvestOnly.getForeground(),
			brightness(harvestOnly.getForeground()) < 200);
	}

	/** Applies the panel's own colouring rule, so the fixture starts in a real state. */
	private static void dim(JCheckBox box) throws Exception
	{
		java.lang.reflect.Method method =
			RunPanel.class.getDeclaredMethod("dimIfUnticked", JCheckBox.class);
		method.setAccessible(true);
		method.invoke(null, box);
	}

	private JCheckBox boxLabelled(String label)
	{
		return boxesIn(panel).stream().filter(box -> label.equals(box.getText()))
			.findFirst().orElseThrow(() -> new AssertionError("no line called " + label));
	}

	private static int brightness(Color colour)
	{
		return (colour.getRed() + colour.getGreen() + colour.getBlue()) / 3;
	}

	private static List<JCheckBox> boxesIn(Container container)
	{
		List<JCheckBox> found = new ArrayList<>();
		collect(container, found);
		return found;
	}

	private static void collect(Container container, List<JCheckBox> found)
	{
		for (Component child : container.getComponents())
		{
			if (child instanceof JCheckBox)
			{
				found.add((JCheckBox) child);
			}
			else if (child instanceof Container)
			{
				collect((Container) child, found);
			}
		}
	}
}
