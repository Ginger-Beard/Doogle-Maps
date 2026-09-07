package com.dooglemaps.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.AbstractButton;
import org.junit.Test;

import static com.dooglemaps.ui.StatsPanels.HISTORY;
import static com.dooglemaps.ui.StatsPanels.diseaseOf;
import static com.dooglemaps.ui.StatsPanels.fullBank;
import static com.dooglemaps.ui.StatsPanels.layoutStore;
import static com.dooglemaps.ui.StatsPanels.pricesOf;
import static com.dooglemaps.ui.StatsPanels.someRuns;
import static com.dooglemaps.ui.StatsPanels.storeOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The tab opens on four headers with four numbers on them, not on a paragraph.
 *
 * <p>This is the redesign's whole argument in one test. The tab it replaces was about 1350px of
 * five sections, roughly 500px of which was prose, opening on a two-sentence summary set in the
 * dimmest text on the page — so there was nothing to land on and scrolling did not help, because
 * nothing at the end was more important than anything at the start.
 *
 * <p>Four cards, one open. The collapsed state persists through {@link PanelLayoutStore}, which
 * stores globally rather than per RuneScape profile — arranging the furniture once per account
 * would be the collapsing costing more than it saves.
 */
public class TheStatsTabOpensOnFourCardsTest
{
	private static final String HARVESTS = "Harvests";
	private static final String RUNS = "Runs";
	private static final String BANK = "Bank";
	private static final String CHECKS = "Checks";

	/** Every question the tab answers has a header, and there are only four of them. */
	@Test
	public void theTabIsFourCards() throws Exception
	{
		HarvestStatsPanel panel = everything(layoutStore());

		List<String> titles = new ArrayList<>();
		for (AbstractButton button : StatsPanels.buttons(panel))
		{
			String text = button.getText();
			// The caret carries the card's name; the show-all rows are the other buttons here.
			if (text != null && (text.startsWith("▾ ") || text.startsWith("▸ ")))
			{
				titles.add(text.substring(2));
			}
		}

		assertEquals("Lifetime, Luck, Runs, Expected and Validation became four cards: " + titles,
			Arrays.asList(HARVESTS, RUNS, BANK, CHECKS), titles);
	}

	/**
	 * Harvests is open and the other three are shut.
	 *
	 * <p>One open card rather than none, because an accordion that opens entirely closed reads as
	 * a tab that failed to load; and rather than all four, because four open cards is the 1350px
	 * scroll this replaced.
	 */
	@Test
	public void onlyHarvestsIsExpandedToBeginWith() throws Exception
	{
		HarvestStatsPanel panel = everything(layoutStore());

		assertOpen(panel, HARVESTS, true);
		assertOpen(panel, RUNS, false);
		assertOpen(panel, BANK, false);
		assertOpen(panel, CHECKS, false);
	}

	/**
	 * And what the player opens stays open.
	 *
	 * <p>A section that reopens shut every session is one the player opens every session, and the
	 * opening is what they were trying to avoid. The state goes through the shared store, so a
	 * rebuilt panel — which is what a login or a settings change produces — reads it back.
	 */
	@Test
	public void anOpenedCardIsStillOpenOnTheNextPanel() throws Exception
	{
		PanelLayoutStore layout = layoutStore();

		StatsPanels.caret(everything(layout), RUNS).doClick(0);

		HarvestStatsPanel rebuilt = everything(layout);
		assertOpen(rebuilt, RUNS, true);
		assertOpen(rebuilt, BANK, false);
	}

	/** And so does what they shut, which is the direction a default alone cannot express. */
	@Test
	public void aClosedCardIsStillClosedOnTheNextPanel() throws Exception
	{
		PanelLayoutStore layout = layoutStore();

		StatsPanels.caret(everything(layout), HARVESTS).doClick(0);

		assertOpen(everything(layout), HARVESTS, false);
	}

	private static void assertOpen(HarvestStatsPanel panel, String title, boolean open)
	{
		String caret = StatsPanels.caret(panel, title).getText();
		assertTrue(title + " should be " + (open ? "open" : "shut") + ", reads " + caret,
			caret.startsWith(open ? "▾" : "▸"));
	}

	/** A history, a bank, a run log and a disease record, so all four cards are drawn. */
	private static HarvestStatsPanel everything(PanelLayoutStore layout) throws Exception
	{
		return StatsPanels.collapsed(layout, storeOf(HISTORY), fullBank(), 32, someRuns(),
			diseaseOf(214, 19, 7, 198.0), pricesOf(6_000));
	}
}
