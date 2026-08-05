package com.dooglemaps.ui;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JLabel;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Clicking a patch row switches the patch off.
 *
 * <p>The row is the control now — there is no second list of checkboxes — so if the click does not
 * land, a patch cannot be switched off at all. Reported from play as doing nothing, including to
 * the projections, which rules out its being a painting problem.
 */
public class PatchRowToggleTest
{
	private static PatchRow row()
	{
		FarmPatch patch = FarmingWorldData.getPatch("12083.4774");
		assertNotNull(patch);
		PatchRow row = new PatchRow(patch,
			Mockito.mock(net.runelite.client.game.ItemManager.class),
			Mockito.mock(com.dooglemaps.DoogleMapsConfig.class),
			Mockito.mock(com.dooglemaps.state.SeedInventoryStore.class),
			Mockito.mock(com.dooglemaps.state.FarmingBonusStore.class));
		// A row that was never laid out has no size, and the toggle checks the release landed
		// inside it - so an unsized row rejects everything.
		row.setSize(200, 40);
		return row;
	}

	/** A click straight on the row. */
	@Test
	public void clickingTheRowFiresTheToggle()
	{
		PatchRow row = row();
		AtomicInteger fired = new AtomicInteger();
		row.setOnToggle(fired::incrementAndGet);

		click(row);
		assertEquals(1, fired.get());
	}

	/**
	 * A click on any part of the row, including the parts that have tooltips.
	 *
	 * <p>This is the bug, and the cause is worth stating because it is not obvious: Swing sends a
	 * click to the deepest component that is listening, and walks up only when nothing below is.
	 * {@code setToolTipText} quietly registers {@code ToolTipManager} as a mouse listener, and
	 * {@code update} puts tooltips on the produce icon, the progress bar and the badges. So those
	 * children were listening — for their own reasons — and clicks stopped there.
	 *
	 * <p>Run after {@code update}, because that is when the tooltips are attached. Before it the
	 * row has none and the bug does not reproduce.
	 */
	@Test
	public void clickingAnyPartOfTheRowFiresIt()
	{
		PatchRow row = row();
		// Never-seen state, which still sets tooltips - that is all this needs.
		row.update(null, null);

		int tooltipped = 0;
		for (java.awt.Component child : descendants(row))
		{
			if (child instanceof javax.swing.JComponent
				&& ((javax.swing.JComponent) child).getToolTipText() != null)
			{
				tooltipped++;
			}
		}
		assertTrue("the row should have tooltipped children - that is what caused this",
			tooltipped > 0);

		for (java.awt.Component child : descendants(row))
		{
			AtomicInteger fired = new AtomicInteger();
			row.setOnToggle(fired::incrementAndGet);
			click(child);
			assertEquals("a click on " + describe(child) + " did not reach the row",
				1, fired.get());
		}
	}

	private static String describe(java.awt.Component component)
	{
		String text = component instanceof JLabel ? ((JLabel) component).getText() : null;
		return component.getClass().getSimpleName()
			+ (text == null || text.isEmpty() ? "" : " \"" + text + "\"");
	}

	/** Every component inside the row, at any depth. */
	private static java.util.List<java.awt.Component> descendants(java.awt.Container root)
	{
		java.util.List<java.awt.Component> found = new java.util.ArrayList<>();
		for (java.awt.Component child : root.getComponents())
		{
			found.add(child);
			if (child instanceof java.awt.Container)
			{
				found.addAll(descendants((java.awt.Container) child));
			}
		}
		return found;
	}

	/** Rows are cached and re-used, so the listener must not stack up across refreshes. */
	@Test
	public void reusingARowDoesNotStackListeners()
	{
		PatchRow row = row();
		AtomicInteger fired = new AtomicInteger();
		for (int refresh = 0; refresh < 5; refresh++)
		{
			row.setOnToggle(fired::incrementAndGet);
		}

		click(row);
		assertEquals("one click fired the toggle more than once", 1, fired.get());
	}

	/**
	 * Dispatches a click the way Swing does, to the component under the cursor.
	 *
	 * <p>Not {@code row.dispatchEvent} directly: that would test that the listener works when
	 * handed an event, which was never in doubt. The question is whether an event aimed at a
	 * child arrives.
	 */
	private static void click(java.awt.Component target)
	{
		target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_RELEASED,
			System.currentTimeMillis(), 0, 2, 2, 1, false, MouseEvent.BUTTON1));
	}

	/**
	 * A press and release a few pixels apart, which is what most real clicks are.
	 *
	 * <p>AWT only synthesises MOUSE_CLICKED when the pointer has not moved between the two, so
	 * listening for it swallowed every click with the slightest drift. Reported from play as
	 * clicks not registering when the mouse was "barely moving at all".
	 */
	@Test
	public void aClickThatDriftsAFewPixelsStillCounts()
	{
		PatchRow row = row();
		row.update(null, null);

		AtomicInteger fired = new AtomicInteger();
		row.setOnToggle(fired::incrementAndGet);

		row.dispatchEvent(new MouseEvent(row, MouseEvent.MOUSE_RELEASED,
			System.currentTimeMillis(), 0, 9, 11, 1, false, MouseEvent.BUTTON1));

		assertEquals("a click that moved a little did not register", 1, fired.get());
	}

	/** Releasing outside the row is an aborted drag, not a toggle. */
	@Test
	public void releasingAwayFromTheRowDoesNothing()
	{
		PatchRow row = row();
		row.update(null, null);

		AtomicInteger fired = new AtomicInteger();
		row.setOnToggle(fired::incrementAndGet);

		row.dispatchEvent(new MouseEvent(row, MouseEvent.MOUSE_RELEASED,
			System.currentTimeMillis(), 0, 400, 300, 1, false, MouseEvent.BUTTON1));

		assertEquals("a drag released off the row should not toggle it", 0, fired.get());
	}
}
