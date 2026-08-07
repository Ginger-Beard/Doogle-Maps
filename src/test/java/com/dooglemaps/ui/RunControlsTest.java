package com.dooglemaps.ui;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.guide.GuideAction;
import com.dooglemaps.guide.GuideStep;
import com.dooglemaps.guide.GuideTracker;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import java.awt.Color;
import java.awt.Component;
import java.awt.GridBagLayout;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * The run controls, which are two different shapes and cannot be seen from a still render.
 *
 * <p>{@link PanelRenderTest} paints the sidebar, but only ever with no run under way — so the
 * layout that appears <i>during</i> one is exactly the part no picture covers. A weighted
 * {@code GridBagLayout} is also the kind of Swing arrangement that fails silently: a wrong
 * constraint gives a button the whole row or none of it, and nothing throws.
 */
public class RunControlsTest
{
	private RunPanel panel;
	private GuideTracker tracker;
	private DoogleMapsConfig config;
	private com.dooglemaps.route.RunPlanner planner;

	private static final Color CYAN = new Color(0x3F, 0xC1, 0xC9);

	@Before
	public void setUp() throws Exception
	{
		tracker = Mockito.mock(GuideTracker.class);
		config = Mockito.mock(DoogleMapsConfig.class);
		when(config.guideHighlightColour()).thenReturn(CYAN);

		com.dooglemaps.state.PlantingGroups groups =
			Mockito.mock(com.dooglemaps.state.PlantingGroups.class);
		when(groups.runOptions()).thenReturn(Collections.emptyList());

		planner = Mockito.mock(com.dooglemaps.route.RunPlanner.class);

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
			tracker);
	}

	/** Before a run there is one decision, so the button takes the whole row. */
	@Test
	public void idleShowsOneFullWidthStartButton() throws Exception
	{
		setRunning(false);

		JPanel controls = controls();
		assertEquals("one control before a run", 1, controls.getComponentCount());
		assertEquals("Start run", ((JButton) controls.getComponent(0)).getText());
		assertEquals("the whole row", 1.0,
			weightOf(controls, controls.getComponent(0)), 0.001);
	}

	/**
	 * During a run: skip on the left at 60%, stop on the right at 40% and red.
	 *
	 * <p>The weights are the point rather than decoration. Skipping is routine and stopping throws
	 * the run away, so the ordinary action gets the room and the destructive one gets the colour —
	 * splitting evenly would let the dangerous button look like the one beside it.
	 */
	@Test
	public void runningSplitsSkipAndStopSixtyForty() throws Exception
	{
		when(tracker.getCurrentStep()).thenReturn(step("Rake the weeds."));
		setRunning(true);

		JPanel controls = controls();
		assertEquals("two controls during a run", 2, controls.getComponentCount());

		JButton skip = (JButton) controls.getComponent(0);
		JButton stop = (JButton) controls.getComponent(1);

		assertEquals("Skip step", skip.getText());
		assertEquals("Stop run", stop.getText());

		assertEquals("skip takes the left 60%", 0.6, weightOf(controls, skip), 0.001);
		assertEquals("stop takes the right 40%", 0.4, weightOf(controls, stop), 0.001);

		assertEquals("stopping is the destructive one, so it is red",
			new Color(0xC4, 0x3B, 0x3B), stop.getForeground());
	}

	/**
	 * And there is a gap between them.
	 *
	 * <p>Same size, same shape, same dark background: touching, the pair read as one control with a
	 * line down the middle, and the right-hand half throws the run away. Colour was doing all the
	 * work of separating them, which it cannot do for someone who has recoloured the sidebar or
	 * cannot easily tell the two apart.
	 */
	@Test
	public void theRunButtonsDoNotTouch() throws Exception
	{
		when(tracker.getCurrentStep()).thenReturn(step("Rake the weeds."));
		setRunning(true);

		JPanel controls = controls();
		GridBagLayout layout = (GridBagLayout) controls.getLayout();

		int gap = layout.getConstraints(controls.getComponent(0)).insets.right
			+ layout.getConstraints(controls.getComponent(1)).insets.left;

		assertTrue("skip and stop are flush against each other", gap > 0);
	}

	/** And the step itself is named underneath, in the guide's own colour. */
	@Test
	public void theCurrentStepIsNamedInTheHighlightColour() throws Exception
	{
		when(tracker.getCurrentStep()).thenReturn(step("Plant the ranarr seed."));
		setRunning(true);

		JLabel label = currentStep();
		assertTrue("shown while there is a step", label.isVisible());
		assertEquals("the same cyan the on-screen step uses", CYAN, label.getForeground());
		assertTrue("names the step: " + label.getText(),
			label.getText().contains("Plant the ranarr seed."));
		assertTrue("and says which line it is", label.getText().contains("Current step:"));
	}

	/**
	 * With nothing being asked for, there is nothing to skip and nothing to say.
	 *
	 * <p>Between stops the guide has no step. A "Current step:" label with nothing after it, left
	 * on screen for the whole walk, is a line that teaches you to stop reading it — and a skip
	 * button that does nothing is worse than one that is plainly unavailable.
	 */
	@Test
	public void betweenStopsThereIsNothingToSkip() throws Exception
	{
		when(tracker.getCurrentStep()).thenReturn(null);
		setRunning(true);

		assertFalse("nothing to skip", ((JButton) controls().getComponent(0)).isEnabled());
		assertFalse("and nothing to say", currentStep().isVisible());
	}

	/**
	 * The line wraps inside the sidebar rather than at the tooltip's width.
	 *
	 * <p>A tooltip is its own floating window and may be wider than the panel that raised it. A
	 * label is not: it is clipped to the sidebar, and an HTML body wider than that does not
	 * re-wrap, it loses its right-hand edge — which showed up in game as the last letter of
	 * "potato" missing off the end of a wrapped line.
	 */
	@Test
	public void theStepWrapsToTheSidebarNotTheTooltipWidth() throws Exception
	{
		when(tracker.getCurrentStep()).thenReturn(
			step("Check the health of the potato cactus patch for Guild Master Jane."));
		setRunning(true);

		String html = currentStep().getText();

		java.util.regex.Matcher width =
			java.util.regex.Pattern.compile("width='(\\d+)'").matcher(html);
		assertTrue("wrapped at some width, or it would run off the side: " + html, width.find());

		int declared = Integer.parseInt(width.group(1));
		assertTrue("wraps at " + declared + "px inside a 225px sidebar", declared <= 195);
	}

	/**
	 * And it can be redrawn on its own, without the rest of the panel.
	 *
	 * <p>The reason there is a separate entry point at all: the guide re-derives its step every
	 * game tick, but the sidebar is rebuilt from a 20-second timer, so the line sat up to twenty
	 * seconds behind the on-screen panel. Going through the full refresh on the tick would rebuild
	 * the reward table and stop list a hundred times a minute, so the tick calls this instead.
	 */
	@Test
	public void theStepCanBeRedrawnOnItsOwn() throws Exception
	{
		when(tracker.getCurrentStep()).thenReturn(step("Rake the weeds."));
		setRunning(true);
		assertTrue(currentStep().getText().contains("Rake the weeds."));

		// What a tick does: the guide has moved on, and nothing else about the panel has.
		when(tracker.getCurrentStep()).thenReturn(step("Plant the ranarr seed."));
		SwingUtilities.invokeAndWait(panel::refreshCurrentStep);

		assertTrue("kept up with the guide: " + currentStep().getText(),
			currentStep().getText().contains("Plant the ranarr seed."));
	}

	// ------------------------------------------------------------------ plumbing

	private void setRunning(boolean running) throws Exception
	{
		// The step line asks the planner rather than trusting a passed-in flag, because it is now
		// also driven from the game tick, where there is no such flag to pass.
		when(planner.isActive()).thenReturn(running);

		Method update = RunPanel.class.getDeclaredMethod("updateRunControls", boolean.class);
		update.setAccessible(true);
		// On the EDT, like every other component change — and invokeAndWait so the assertions
		// below see the finished state rather than racing it.
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				update.invoke(panel, running);
			}
			catch (ReflectiveOperationException e)
			{
				throw new IllegalStateException(e);
			}
		});
	}

	private JPanel controls() throws Exception
	{
		return (JPanel) field("controls");
	}

	private JLabel currentStep() throws Exception
	{
		return (JLabel) field("currentStep");
	}

	private Object field(String name) throws Exception
	{
		java.lang.reflect.Field f = RunPanel.class.getDeclaredField(name);
		f.setAccessible(true);
		Object value = f.get(panel);
		assertNotNull(name + " is missing", value);
		return value;
	}

	/** The horizontal weight the layout actually gave a component. */
	private static double weightOf(JPanel parent, Component child)
	{
		GridBagLayout layout = (GridBagLayout) parent.getLayout();
		return layout.getConstraints(child).weightx;
	}

	/**
	 * A step, built through the constructor because the factories are package-private to
	 * {@code com.dooglemaps.guide} and this test lives in {@code ui}. {@code @Value} also makes the
	 * class final, so it cannot be mocked without an inline mock maker.
	 */
	private static GuideStep step(String text) throws Exception
	{
		FarmPatch patch = FarmingWorldData.getPatch("12083.4774");
		assertNotNull("fixture patch no longer exists", patch);

		Constructor<GuideStep> ctor = GuideStep.class.getDeclaredConstructor(
			GuideAction.class, FarmPatch.class, int.class, int.class, String.class);
		ctor.setAccessible(true);
		return ctor.newInstance(GuideAction.PLANT, patch, -1, -1, text);
	}

	@SuppressWarnings("unchecked")
	private static <T> T construct(Class<T> type, Object... args) throws Exception
	{
		for (Constructor<?> candidate : type.getDeclaredConstructors())
		{
			if (candidate.getParameterCount() == args.length)
			{
				candidate.setAccessible(true);
				return (T) candidate.newInstance(args);
			}
		}
		throw new IllegalStateException("no constructor of arity " + args.length + " on " + type);
	}
}
