package com.dooglemaps.guide;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.bank.RunLoadout;
import com.dooglemaps.state.SeedInventoryStore;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.PostMenuSort;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

/**
 * The withdraw amount nearest what the run still wants, put under the left click.
 *
 * <p>See {@code docs/withdraw-quantity-swap-spec.md}. {@code WithdrawQuantityTest} covers the
 * arithmetic; this covers the half that reads a menu and moves an entry.
 *
 * <p>Pinned against a bank menu as the client holds it — <b>left-click last</b>, the same
 * backwards array {@code ChetPaySwapTest} exists to keep honest — because that ordering is not
 * visible from in front of the client and is the easiest thing here to get backwards.
 */
public class WithdrawAmountSwapTest
{
	/** Any item id; the swap only ever compares it for equality. */
	private static final int WATERMELON = 5982;

	private Client client;
	private Menu menu;
	private RunLoadout loadout;
	private GuideMenuSwap swap;
	private MenuEntry[] entries;

	@Before
	public void setUp()
	{
		client = Mockito.mock(Client.class);
		menu = Mockito.mock(Menu.class);
		loadout = Mockito.mock(RunLoadout.class);

		GuideTracker tracker = Mockito.mock(GuideTracker.class);
		GuideStatus status = Mockito.mock(GuideStatus.class);
		when(status.isRunning()).thenReturn(true);
		when(tracker.getStatus()).thenReturn(status);

		DoogleMapsConfig config = Mockito.mock(DoogleMapsConfig.class);
		when(config.guidedMode()).thenReturn(true);
		when(config.withdrawAmountSwap()).thenReturn(true);

		when(client.getMenu()).thenReturn(menu);
		when(client.isMenuOpen()).thenReturn(false);

		swap = construct(GuideMenuSwap.class, client, tracker, config,
			Mockito.mock(SeedInventoryStore.class), loadout);
	}

	/** Fifteen wanted takes the ten, which is the spec's own worked example. */
	@Test
	public void theLargestAmountThatDoesNotOvershootWins()
	{
		bankMenu();
		when(loadout.stillWantedNow(WATERMELON)).thenReturn(15);

		swap.onPostMenuSort(new PostMenuSort());

		assertEquals("Withdraw-10", leftClick());
	}

	/** Five left takes the five. */
	@Test
	public void theLadderComesDownAsThePackFills()
	{
		bankMenu();
		when(loadout.stillWantedNow(WATERMELON)).thenReturn(5);

		swap.onPostMenuSort(new PostMenuSort());

		assertEquals("Withdraw-5", leftClick());
	}

	/**
	 * Four wanted takes ones, and never the five.
	 *
	 * <p>The accepted cost of the no-overshoot rule: four buckets is four clicks. Asserted rather
	 * than merely documented, because rounding up is the tempting change somebody makes later.
	 */
	@Test
	public void itNeverRoundsUp()
	{
		bankMenu();
		when(loadout.stillWantedNow(WATERMELON)).thenReturn(4);

		swap.onPostMenuSort(new PostMenuSort());

		assertEquals("Withdraw-1", leftClick());
	}

	/**
	 * Nothing wanted leaves the menu exactly as the game built it.
	 *
	 * <p>Which is also the whole behaviour for a seed, a teleport, or anything else
	 * {@code stillWantedNow} refuses to size — they all arrive here as zero.
	 */
	@Test
	public void nothingWantedMovesNothing()
	{
		bankMenu();
		when(loadout.stillWantedNow(WATERMELON)).thenReturn(0);

		swap.onPostMenuSort(new PostMenuSort());

		assertEquals("Withdraw-All", leftClick());
	}

	/**
	 * {@code Withdraw-X} is never promoted, whatever number it is wearing.
	 *
	 * <h2>Why this menu is worth pinning</h2>
	 *
	 * The game renders X as the amount the player last set, so a real menu can carry
	 * "Withdraw-25". Here the run wants thirty — more than the 10 — and the 25 would be the
	 * largest amount that does not overshoot if it were ever a candidate. It is not, so the ten
	 * wins and the player's own X is left where the game put it.
	 */
	@Test
	public void anXWearingANumberIsStillNeverChosen()
	{
		entries = new MenuEntry[]{
			entry("Withdraw-1"), entry("Withdraw-5"), entry("Withdraw-10"),
			entry("Withdraw-25"), entry("Withdraw-All")};
		wireMenu();
		when(loadout.stillWantedNow(WATERMELON)).thenReturn(30);

		swap.onPostMenuSort(new PostMenuSort());

		assertEquals("Withdraw-10", leftClick());
	}

	/**
	 * His store says "Remove", not "Withdraw", and carries the item id on the widget rather than
	 * on the entry.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"nope, doesn't work at the lep"</i>. Two differences, and only one of them mattered.
	 *
	 * <p>The wording is <i>"Remove-1"</i>, <i>"Remove-5"</i> — confirmed in play — and that was
	 * always fine: the amount is read as the number after the first dash, whatever verb precedes
	 * it. What actually failed is that {@code MenuEntry.getItemId} is not populated for his
	 * interface, so the swap could not tell which item was hovered and stood down. The widget knows
	 * regardless.
	 *
	 * <p>Both halves are pinned here, because a later reader would reasonably assume the verb was
	 * the problem — it is the visible difference — and "fix" it by matching on wording.
	 */
	@Test
	public void hisStoreRemovesRatherThanWithdrawsAndNamesTheItemOnTheWidget()
	{
		entries = new MenuEntry[]{
			widgetEntry("Remove-1"), widgetEntry("Remove-5"), widgetEntry("Remove-All")};
		wireMenu();
		when(loadout.stillWantedNow(WATERMELON)).thenReturn(7);

		swap.onPostMenuSort(new PostMenuSort());

		assertEquals("five is the largest of his that does not overshoot seven",
			"Remove-5", leftClick());
	}

	/**
	 * A deposit menu is never sized, however much the run still wants of the item.
	 *
	 * <h2>The reviewed dead end</h2>
	 *
	 * The scan matched by amount alone, on the reasoning that the verb was cosmetic — that is
	 * what makes the leprechaun's "Remove" work. But the bank's <b>inventory side</b> offers
	 * {@code Deposit-1/-5/-10} on every hovered item, those parse as amounts, and
	 * {@code stillWantedNow} is non-zero for exactly the items the run is mid-collecting. So the
	 * swap promoted {@code Deposit-10} on the very buckets it had just said to withdraw: one
	 * misclick put the supplies back, re-aimed by the feature that exists to make clicks safe.
	 *
	 * <p>Hence the verb whitelist — Withdraw and Remove, the two collection verbs confirmed in
	 * play, and nothing else. The spec's §7 settled deposits as not this feature's business.
	 */
	@Test
	public void aDepositMenuIsNeverSized()
	{
		entries = new MenuEntry[]{
			entry("Deposit-1"), entry("Deposit-5"), entry("Deposit-10"), entry("Deposit-All")};
		wireMenu();
		when(loadout.stillWantedNow(WATERMELON)).thenReturn(11);

		swap.onPostMenuSort(new PostMenuSort());

		assertEquals("the game's own order stands", "Deposit-All", leftClick());
	}

	/**
	 * One entry nobody can identify stands the whole swap down.
	 *
	 * <p>It used to be absorbed as "matches anything", which made the one-item guard weaker than
	 * its own promise: an unidentified amount was attributed to whichever item the identified
	 * entries named. Guessing is exactly what the guard exists to refuse.
	 */
	@Test
	public void anUnidentifiableEntryStandsTheWholeSwapDown()
	{
		MenuEntry mystery = Mockito.mock(MenuEntry.class);
		when(mystery.getOption()).thenReturn("Withdraw-10");
		when(mystery.getItemId()).thenReturn(-1);

		entries = new MenuEntry[]{
			entry("Withdraw-1"), entry("Withdraw-5"), mystery, entry("Withdraw-All")};
		wireMenu();
		when(loadout.stillWantedNow(WATERMELON)).thenReturn(15);

		swap.onPostMenuSort(new PostMenuSort());

		assertEquals("Withdraw-All", leftClick());
	}

	/**
	 * His store, as it actually is: no item id anywhere, only the name on the entry's target.
	 *
	 * <h2>The reported dead end, round two</h2>
	 *
	 * <i>"the swapping at the lep never worked for this anyways."</i> The first fix taught
	 * {@code itemIdOf} to ask the widget, and the test above pinned a mocked widget carrying the
	 * id — which is how the fix held in the suite and not in play, where neither the entry nor
	 * the widget carries one at his store. What every item op does carry is the item's name in
	 * the target, because that is the text the menu shows; the loadout's rows carry the same
	 * names, so the sizing goes by name when the id is not there to go by.
	 */
	@Test
	public void hisStoreIsSizedByNameWhenNothingCarriesTheItemId()
	{
		entries = new MenuEntry[]{
			namedEntry("Remove-1", "<col=ff9040>Empty bucket</col>"),
			namedEntry("Remove-5", "<col=ff9040>Empty bucket</col>"),
			namedEntry("Remove-All", "<col=ff9040>Empty bucket</col>")};
		wireMenu();
		when(loadout.stillWantedNow("Empty bucket")).thenReturn(7);

		swap.onPostMenuSort(new PostMenuSort());

		assertEquals("five is the largest that does not overshoot seven, sized by name",
			"Remove-5", leftClick());
	}

	/** An entry as his store actually reports it: no id on the entry or any widget, name in the target. */
	private static MenuEntry namedEntry(String option, String target)
	{
		MenuEntry menuEntry = Mockito.mock(MenuEntry.class);
		when(menuEntry.getOption()).thenReturn(option);
		when(menuEntry.getTarget()).thenReturn(target);
		when(menuEntry.getItemId()).thenReturn(-1);
		return menuEntry;
	}

	/**
	 * An id that names no row falls back to the name on the entry.
	 *
	 * <h2>The reported dead end, round three</h2>
	 *
	 * <i>"leprechaun empty buckets menu swapping logic still not working"</i> — with the name
	 * fallback already in and its test green, and {@code client.log} carrying not one stand-down
	 * line from a session at his store. That silence is the clue: the only paths that say
	 * nothing are "no candidates" and "wanted came back zero", and the way wanted comes back
	 * zero with a bucket row plainly outstanding is an id that is <b>positive but wrong</b> —
	 * an interface handing back junk instead of {@code -1}, which sails past the "no id" test
	 * and sizes a row that does not exist.
	 *
	 * <p>So the id is only trusted as far as it finds a row. The fallback cannot invent a
	 * number: a genuine id and the printed name find the same row, so a real "nothing wanted"
	 * stays zero by either path. Only a junk id leaves the name to find the right row.
	 */
	@Test
	public void aJunkItemIdFallsBackToTheName()
	{
		int junk = 999_999;
		MenuEntry one = namedEntry("Remove-1", "<col=ff9040>Empty bucket</col>");
		when(one.getItemId()).thenReturn(junk);
		MenuEntry five = namedEntry("Remove-5", "<col=ff9040>Empty bucket</col>");
		when(five.getItemId()).thenReturn(junk);
		MenuEntry all = namedEntry("Remove-All", "<col=ff9040>Empty bucket</col>");
		when(all.getItemId()).thenReturn(junk);

		entries = new MenuEntry[]{one, five, all};
		wireMenu();
		when(loadout.stillWantedNow(junk)).thenReturn(0);
		when(loadout.stillWantedNow("Empty bucket")).thenReturn(26);

		swap.onPostMenuSort(new PostMenuSort());

		assertEquals("sized by the row the name finds, not the row the junk id misses",
			"Remove-5", leftClick());
	}

	/**
	 * A run wanting at least a whole pack gets All, not the top of the ladder.
	 *
	 * <p>The owner's rule: "when withdraw count is equal or more than free inv space, just use
	 * the All option." For an unstackable, All takes what fits and stops, so with wanted at or
	 * past the pack it cannot overshoot — and thirty melons stops being three clicks that all
	 * mean "fill the pack". {@code RunLoadout.fillsThePack} owns the guard rails; here it is
	 * mocked true, and every other test in this class leaves it false, which pins that the
	 * ladder is undisturbed everywhere else.
	 */
	@Test
	public void aPackFillingWithdrawalTakesAll()
	{
		bankMenu();
		when(loadout.stillWantedNow(WATERMELON)).thenReturn(30);
		when(loadout.fillsThePack(WATERMELON)).thenReturn(true);

		swap.onPostMenuSort(new PostMenuSort());

		assertEquals("Withdraw-All", leftClick());
	}

	/** ...and at his store, identified by name like everything else there. */
	@Test
	public void hisStoreTakesAllByName()
	{
		entries = new MenuEntry[]{
			namedEntry("Remove-1", "<col=ff9040>Empty bucket</col>"),
			namedEntry("Remove-5", "<col=ff9040>Empty bucket</col>"),
			namedEntry("Remove-All", "<col=ff9040>Empty bucket</col>")};
		// The game's own order: All is not the left click until the swap makes it one.
		MenuEntry first = entries[2];
		entries[2] = entries[0];
		entries[0] = first;
		wireMenu();
		when(loadout.fillsThePack("Empty bucket")).thenReturn(true);

		swap.onPostMenuSort(new PostMenuSort());

		assertEquals("Remove-All", leftClick());
	}

	/**
	 * A collected count puts Examine under the click, so a stray click takes nothing extra.
	 *
	 * <p>The owner's rule: "swap to examine when we're done withdrawing." With the count
	 * satisfied, the game's own default — Withdraw-1, or whatever the quantity toggle says — is
	 * the one remaining way to overshoot, sitting under the very click the player has been
	 * spamming. Examine is the option that does nothing.
	 *
	 * <p>{@code doneWithdrawing} is mocked true here and false everywhere else in this class —
	 * which pins the safety half: zero wanted alone (a seed, a stranger's item) never earns the
	 * Examine swap. See {@code nothingWantedMovesNothing}.
	 */
	@Test
	public void aCollectedCountSwapsToExamine()
	{
		entries = new MenuEntry[]{
			entry("Examine"), entry("Withdraw-1"), entry("Withdraw-5"),
			entry("Withdraw-10"), entry("Withdraw-All")};
		wireMenu();
		when(loadout.stillWantedNow(WATERMELON)).thenReturn(0);
		when(loadout.doneWithdrawing(WATERMELON)).thenReturn(true);

		swap.onPostMenuSort(new PostMenuSort());

		assertEquals("Examine", leftClick());
	}

	/** ...and at his store, identified by name like everything else there. */
	@Test
	public void hisStoreSwapsToExamineByName()
	{
		entries = new MenuEntry[]{
			namedEntry("Examine", "<col=ff9040>Empty bucket</col>"),
			namedEntry("Remove-1", "<col=ff9040>Empty bucket</col>"),
			namedEntry("Remove-5", "<col=ff9040>Empty bucket</col>"),
			namedEntry("Remove-All", "<col=ff9040>Empty bucket</col>")};
		wireMenu();
		when(loadout.doneWithdrawing("Empty bucket")).thenReturn(true);

		swap.onPostMenuSort(new PostMenuSort());

		assertEquals("Examine", leftClick());
	}

	/** With the setting off, the game's own order stands. */
	@Test
	public void theSettingTurnsItOff()
	{
		DoogleMapsConfig off = Mockito.mock(DoogleMapsConfig.class);
		when(off.guidedMode()).thenReturn(true);
		when(off.withdrawAmountSwap()).thenReturn(false);

		GuideTracker tracker = Mockito.mock(GuideTracker.class);
		GuideStatus status = Mockito.mock(GuideStatus.class);
		when(status.isRunning()).thenReturn(true);
		when(tracker.getStatus()).thenReturn(status);

		GuideMenuSwap disabled = construct(GuideMenuSwap.class, client, tracker, off,
			Mockito.mock(SeedInventoryStore.class), loadout);

		bankMenu();
		when(loadout.stillWantedNow(WATERMELON)).thenReturn(15);

		disabled.onPostMenuSort(new PostMenuSort());

		assertEquals("Withdraw-All", leftClick());
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * A bank item's menu as the client holds it, left-click <b>last</b>.
	 *
	 * <p>So the displayed order top to bottom is the reverse: Withdraw-All, Withdraw-10,
	 * Withdraw-5, Withdraw-1.
	 */
	private void bankMenu()
	{
		entries = new MenuEntry[]{
			entry("Withdraw-1"), entry("Withdraw-5"), entry("Withdraw-10"), entry("Withdraw-All")};
		wireMenu();
	}

	private void wireMenu()
	{
		when(menu.getMenuEntries()).thenReturn(entries);
		Mockito.doAnswer(invocation ->
		{
			entries = invocation.getArgument(0);
			return null;
		}).when(menu).setMenuEntries(Mockito.any());
	}

	private String leftClick()
	{
		return entries[entries.length - 1].getOption();
	}

	/**
	 * An entry as an interface item op arrives: nothing on the entry, the item on the widget.
	 *
	 * <p>Which is how the leprechaun's store reports it, and why the swap asks the widget when the
	 * entry answers nothing.
	 */
	private static MenuEntry widgetEntry(String option)
	{
		net.runelite.api.widgets.Widget widget =
			Mockito.mock(net.runelite.api.widgets.Widget.class);
		when(widget.getItemId()).thenReturn(WATERMELON);

		MenuEntry menuEntry = Mockito.mock(MenuEntry.class);
		when(menuEntry.getOption()).thenReturn(option);
		when(menuEntry.getItemId()).thenReturn(-1);
		when(menuEntry.getWidget()).thenReturn(widget);
		return menuEntry;
	}

	private static MenuEntry entry(String option)
	{
		MenuEntry menuEntry = Mockito.mock(MenuEntry.class);
		when(menuEntry.getOption()).thenReturn(option);
		when(menuEntry.getItemId()).thenReturn(WATERMELON);
		return menuEntry;
	}
}
