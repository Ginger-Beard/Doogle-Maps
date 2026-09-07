package com.dooglemaps.ui;

import java.awt.Color;
import java.awt.Component;
import java.util.List;
import javax.swing.JLabel;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;

import static com.dooglemaps.ui.StatsPanels.NO_SEEDS;
import static com.dooglemaps.ui.StatsPanels.cellsFor;
import static com.dooglemaps.ui.StatsPanels.rowFor;
import static com.dooglemaps.ui.StatsPanels.storeOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The {@code +/-} column, in all four of the states it has.
 *
 * <p>It replaces two columns and a table. The old Luck section showed a raw surplus beside a
 * percentile, where the percentile was blank on most rows of a real account and the surplus had
 * no sample floor at all — so a crop with one patch carried a signed number and a crop with two
 * hundred showed a dash. A percentage against the same prediction says the same thing in a unit
 * a reader already has, and it degrades in one direction rather than two.
 *
 * <h2>The states, and the two different floors under them</h2>
 *
 * <ul>
 *   <li><b>Green</b> above expectation, from twenty picked patches on.</li>
 *   <li><b>Red</b> below it, at the same floor.</li>
 *   <li><b>Grey</b> between five patches and twenty — the figure is true, it is just not yet a
 *       finding.</li>
 *   <li><b>Blank</b> under five patches, where one roll would be reported as a tendency.</li>
 *   <li><b>Blank</b> where the crop has no published yield formula to be measured against, or
 *       where the recorded spread covers only part of its own total.</li>
 * </ul>
 *
 * <p>The sign carries the meaning and the colour only reinforces it, so every one of these reads
 * without colour vision and in a screenshot. There is no {@code "-"} anywhere in the column: in a
 * column of signed numbers a dash is a minus sign.
 */
public class TheCropTableCarriesASignedDifferenceTest
{
	/** RuneLite's own "good price" green, 8.58:1 against the row behind it. */
	private static final Color OVER = ColorScheme.GRAND_EXCHANGE_PRICE;

	/** 5.08:1. {@code PROGRESS_ERROR_COLOR} is 3.10:1 and fails AA at this size. */
	private static final Color UNDER = new Color(235, 120, 120);

	/** The column the difference is drawn in: crop, n, got, xp, then this. */
	private static final int DIFFERENCE = 4;

	/**
	 * Five crops, one per state, plus the state that matters most and is easiest to miss.
	 *
	 * <p>Guam is the fourth state's real shape: twenty-five patches, a spread recorded, and a
	 * variance that only covers twelve of them. The store keeps {@code variancePatches} for
	 * exactly this — a total whose variance describes a smaller set of patches than its mean does
	 * cannot be scored against, however many patches there are — and it is the guard the audit
	 * called the best-designed thing on the tab.
	 */
	private static final String HISTORY =
		"{\"Ranarr weed|ULTRACOMPOST\":{\"crop\":\"Ranarr weed\",\"compost\":\"ULTRACOMPOST\","
			+ "\"harvests\":25,\"items\":220,\"predicted\":200.0,\"predictedVariance\":87.5,"
			+ "\"variancePatches\":25,\"xp\":3000.0,\"best\":14,\"worst\":5},"
			+ "\"Snapdragon|ULTRACOMPOST\":{\"crop\":\"Snapdragon\",\"compost\":\"ULTRACOMPOST\","
			+ "\"harvests\":25,\"items\":180,\"predicted\":200.0,\"predictedVariance\":87.5,"
			+ "\"variancePatches\":25,\"xp\":2800.0,\"best\":12,\"worst\":4},"
			+ "\"Whiteberries|ULTRACOMPOST\":{\"crop\":\"Whiteberries\","
			+ "\"compost\":\"ULTRACOMPOST\",\"harvests\":30,\"items\":300,\"predicted\":0.0,"
			+ "\"xp\":2600.0,\"best\":16,\"worst\":8},"
			+ "\"Toadflax|ULTRACOMPOST\":{\"crop\":\"Toadflax\",\"compost\":\"ULTRACOMPOST\","
			+ "\"harvests\":10,\"items\":88,\"predicted\":80.0,\"predictedVariance\":35.0,"
			+ "\"variancePatches\":10,\"xp\":2400.0,\"best\":11,\"worst\":6},"
			+ "\"Guam|ULTRACOMPOST\":{\"crop\":\"Guam\",\"compost\":\"ULTRACOMPOST\","
			+ "\"harvests\":25,\"items\":220,\"predicted\":200.0,\"predictedVariance\":42.0,"
			+ "\"variancePatches\":12,\"xp\":2200.0,\"best\":13,\"worst\":5},"
			+ "\"Torstol|ULTRACOMPOST\":{\"crop\":\"Torstol\",\"compost\":\"ULTRACOMPOST\","
			+ "\"harvests\":2,\"items\":19,\"predicted\":16.0,\"predictedVariance\":7.0,"
			+ "\"variancePatches\":2,\"xp\":2000.0,\"best\":10,\"worst\":9}}";

	/** 220 against a predicted 200, over enough patches for it to mean something. */
	@Test
	public void aCropAboveExpectationIsGreen() throws Exception
	{
		assertDifference("Ranarr weed", "+10%", OVER);
	}

	/** And 180 against the same 200 is the same statement the other way. */
	@Test
	public void aCropBelowExpectationIsRed() throws Exception
	{
		assertDifference("Snapdragon", "-10%", UNDER);
	}

	/**
	 * Ten patches carry the number without the colour.
	 *
	 * <p>The floor is {@link com.dooglemaps.validate.CropHarvestStats#MIN_PATCHES_FOR_LUCK},
	 * reused rather than reinvented: the spread grows like the square root of the patch count
	 * while the total grows with it, so twenty is where the figure settles. The number is still
	 * shown because it is true; it is simply not dressed as a finding.
	 */
	@Test
	public void aShortHistoryKeepsTheNumberAndLosesTheColour() throws Exception
	{
		assertDifference("Toadflax", "+10%", ColorScheme.LIGHT_GRAY_COLOR);
	}

	/** And says how much longer, where a reader would otherwise read grey as broken. */
	@Test
	public void theGreyCellSaysHowManyMorePatchesItNeeds() throws Exception
	{
		String tooltip = StatsPanels.tooltipFor(panel(), HarvestStatsPanel.CROP_TABLE, "Toadflax");
		assertNotNull(tooltip);
		assertTrue("expected the countdown, got " + tooltip,
			tooltip.contains("10 more picked patches"));
	}

	/**
	 * A crop with no modelled yield gets an empty cell, not a zero and not a dash.
	 *
	 * <p>Whiteberries are the audit's example: the model falls through to a bush's <i>floor</i> of
	 * four berries and reports it as an expectation, so the crop showed as hundreds of items over
	 * expectation when the plugin simply had no formula for it. An empty cell is the honest form
	 * of "not scored" and, unlike a dash, cannot be misread as a minus sign.
	 */
	@Test
	public void aCropWithNoModelledYieldIsBlank() throws Exception
	{
		assertEquals("", rowFor(panel(), HarvestStatsPanel.CROP_TABLE, "Whiteberries")
			.get(DIFFERENCE));
	}

	/**
	 * And so is a crop whose variance covers only part of its own total.
	 *
	 * <p>Twenty-five patches of it, a recorded spread, and no difference — because twelve of
	 * those patches were recorded before the spread was, so the mean and the variance describe
	 * different sets of patches and comparing them would be wrong rather than merely noisy.
	 */
	@Test
	public void aPartlyScoredHistoryIsBlankHoweverManyPatchesItHas() throws Exception
	{
		assertEquals("", rowFor(panel(), HarvestStatsPanel.CROP_TABLE, "Guam").get(DIFFERENCE));
	}

	/**
	 * And a couple of patches gets no number at all, not even a grey one.
	 *
	 * <p>The grey floor says "true but unsettled"; below {@code MIN_PATCHES_FOR_SURPLUS} there is
	 * nothing to be unsettled about. A single herb patch scatters by three or four either way, so
	 * two of them quoting +19% would be reporting one roll as a tendency — which is exactly what
	 * the old surplus column did, with seaweed showing +15 off a single patch.
	 */
	@Test
	public void twoPatchesGetNoDifferenceAtAll() throws Exception
	{
		assertEquals("", rowFor(panel(), HarvestStatsPanel.CROP_TABLE, "Torstol").get(DIFFERENCE));

		String tooltip = StatsPanels.tooltipFor(panel(), HarvestStatsPanel.CROP_TABLE, "Torstol");
		assertNotNull(tooltip);
		assertTrue("the blank needs a reason, got " + tooltip,
			tooltip.contains("one roll and calling it a tendency"));
	}

	private static void assertDifference(String crop, String expected, Color colour)
		throws Exception
	{
		List<Component> cells = cellsFor(panel(), HarvestStatsPanel.CROP_TABLE, crop);
		JLabel cell = (JLabel) cells.get(DIFFERENCE);

		assertEquals(expected, cell.getText());
		assertEquals(crop + "'s difference should be " + colour, colour, cell.getForeground());
	}

	private static HarvestStatsPanel panel() throws Exception
	{
		return StatsPanels.panel(storeOf(HISTORY), NO_SEEDS);
	}
}
