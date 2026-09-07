package com.dooglemaps.ui;

import java.util.List;
import javax.swing.AbstractButton;
import org.junit.Test;

import static com.dooglemaps.ui.StatsPanels.NO_SEEDS;
import static com.dooglemaps.ui.StatsPanels.cropOrder;
import static com.dooglemaps.ui.StatsPanels.storeOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The row that used to say <i>"+ 22 more"</i> now shows them.
 *
 * <p>That row was the tab's one outright dead end: it looked like a row, was not clickable, and
 * its entire content was the news that the thing the reader was looking for had been left out.
 * On a mature account the twelve-row cap hides more crops than it shows, so the dead end was the
 * common case rather than the edge one.
 *
 * <p>The cap itself stays — beyond a dozen rows the table stops being readable, which is what it
 * was for. What changes is that the reader can lift it, and put it back.
 *
 * <p>Not remembered between sessions, unlike the collapsed cards: opening a card is furniture and
 * asking to see the other twenty-two crops is a moment's curiosity.
 */
public class ShowAllExpandsTheCropTableTest
{
	/** Crops shown before the cut. Mirrors the panel's own {@code MAX_ROWS}. */
	private static final int SHOWN = 12;

	private static final int CROPS = 15;

	/** Twelve of the fifteen, and a row that names the three it is not showing. */
	@Test
	public void theTableIsCutAtTwelveAndSaysHowManyThereAre() throws Exception
	{
		HarvestStatsPanel panel = panel();

		assertEquals("twelve crops and the total row", SHOWN + 1,
			cropOrder(panel, HarvestStatsPanel.CROP_TABLE).size());
		assertEquals("▾ show all " + CROPS + " crops", showAll(panel).getText());
	}

	/** Clicking it shows every crop, and offers the way back. */
	@Test
	public void clickingItShowsThemAll() throws Exception
	{
		HarvestStatsPanel panel = panel();
		showAll(panel).doClick(0);

		List<String> crops = cropOrder(panel, HarvestStatsPanel.CROP_TABLE);
		assertEquals("fifteen crops and the total row", CROPS + 1, crops.size());
		assertTrue("the crop that was cut off should be here now, " + crops,
			crops.contains("Crop 15"));
		assertEquals("▴ show " + SHOWN, showAll(panel).getText());
	}

	/** And clicking it again puts the table back, rather than being a one-way door. */
	@Test
	public void clickingItAgainCutsTheListBack() throws Exception
	{
		HarvestStatsPanel panel = panel();
		showAll(panel).doClick(0);
		showAll(panel).doClick(0);

		assertEquals(SHOWN + 1, cropOrder(panel, HarvestStatsPanel.CROP_TABLE).size());
	}

	/**
	 * The total row counts every crop either way, which the old cut row never explained.
	 *
	 * <p>{@code MAX_ROWS} was applied to the visible list while the total came from the whole
	 * store, so the rows and the total disagreed by design and nothing on screen said so. They
	 * still do — that is the right behaviour — and now the row that cuts the list carries the
	 * explanation on hover.
	 */
	@Test
	public void theTotalCountsEveryCropWhicheverWayTheListIsCut() throws Exception
	{
		HarvestStatsPanel panel = panel();

		// 15 crops of 10 patches each.
		assertEquals("150", StatsPanels.rowFor(panel, HarvestStatsPanel.CROP_TABLE, "total").get(1));
		assertTrue("the cut needs explaining where it happens",
			showAll(panel).getToolTipText().contains("The total below counts every crop"));
	}

	private static AbstractButton showAll(HarvestStatsPanel panel)
	{
		for (AbstractButton button
			: StatsPanels.buttons(StatsPanels.tableNamed(panel, HarvestStatsPanel.CROP_TABLE)))
		{
			if (button.getText() != null && button.getText().contains("show"))
			{
				return button;
			}
		}
		throw new AssertionError("no show-all row in the crop table");
	}

	private static HarvestStatsPanel panel() throws Exception
	{
		return StatsPanels.panel(storeOf(history()), NO_SEEDS);
	}

	/** Fifteen crops with descending experience, so the cut lands in a known place. */
	private static String history()
	{
		StringBuilder json = new StringBuilder("{");
		for (int crop = 1; crop <= CROPS; crop++)
		{
			if (crop > 1)
			{
				json.append(',');
			}
			String name = String.format("Crop %02d", crop);
			json.append('"').append(name).append("|ULTRACOMPOST\":{\"crop\":\"").append(name)
				.append("\",\"compost\":\"ULTRACOMPOST\",\"harvests\":10,\"items\":80,")
				.append("\"predicted\":80.0,\"xp\":").append((CROPS - crop + 1) * 1000)
				.append(".0,\"best\":12,\"worst\":4}");
		}
		return json.append('}').toString();
	}
}
