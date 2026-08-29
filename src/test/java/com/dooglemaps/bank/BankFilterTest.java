package com.dooglemaps.bank;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.route.RunPlanner;
import com.google.inject.Injector;
import java.util.Collections;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.banktags.BankTag;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.plugins.banktags.TagManager;
import net.runelite.client.plugins.banktags.tabs.Layout;
import net.runelite.client.plugins.banktags.tabs.LayoutManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the bank filter's lifecycle: when it may open, what it believes, and how it closes.
 *
 * <p>The two behaviours worth pinning hardest have both already gone wrong in the field:
 *
 * <ul>
 *   <li><b>Bank Tags is a soft dependency.</b> Without it the filter must degrade to nothing —
 *       {@code isFiltering()} stays false forever — because {@code BankHighlightOverlay} stands
 *       down on that answer, and a "yes" over an unfiltered bank would leave the player with
 *       neither filter nor highlights.</li>
 *   <li><b>Closing must be deferred.</b> {@code WidgetClosed} is posted from inside the
 *       client's own script execution, and {@code closeBankTag} ends in {@code runScript};
 *       calling it synchronously trips "scripts are not reentrant" and takes the client down.
 *       The test verifies the close happens inside the {@code invokeLater} runnable and not a
 *       moment before.</li>
 * </ul>
 *
 * <p>Bank Tags itself is played by a small recording stub — a real {@link Plugin} subclass
 * implementing {@link BankTagsService} — because {@code resolveBankTags} reaches it through
 * {@code Plugin.getInjector()}, which is final. What this file deliberately does <b>not</b>
 * cover is the layout interplay with real Bank Tags internals: {@code saveLayout} and
 * {@code relayoutIfBankChanged} run against {@code LayoutManager}, {@code BankSearch} and the
 * chatbox, whose behaviour a mock would only assert back at itself. Those paths are exercised
 * here only as far as "opening survives them"; their correctness is an in-client concern.
 */
public class BankFilterTest
{
	private Client client;
	private RunPlanner planner;
	private PluginManager pluginManager;
	private RunLoadout loadout;
	private DoogleMapsConfig config;
	private ClientThread clientThread;
	private BankContents bank;
	private RouteItem routeItem;
	private com.dooglemaps.guide.CarriedItems carried;

	/** Mocked inert: applies() answers false, so the filter behaves as on any ordinary run. */
	private InventorySetupsHandoff handoff;

	private RecordingBankTags bankTags;
	private BankFilter filter;

	@Before
	public void setUp()
	{
		client = Mockito.mock(Client.class);
		planner = Mockito.mock(RunPlanner.class);
		pluginManager = Mockito.mock(PluginManager.class);
		loadout = Mockito.mock(RunLoadout.class);
		config = Mockito.mock(DoogleMapsConfig.class);
		clientThread = Mockito.mock(ClientThread.class);
		bank = Mockito.mock(BankContents.class);
		routeItem = Mockito.mock(RouteItem.class);
		carried = Mockito.mock(com.dooglemaps.guide.CarriedItems.class);
		when(carried.getItemIds()).thenReturn(Collections.emptySet());
		handoff = Mockito.mock(InventorySetupsHandoff.class);

		bankTags = new RecordingBankTags();
		when(pluginManager.getPlugins())
			.thenReturn(Collections.<Plugin>singletonList(bankTags));

		filter = new BankFilter(client, planner, pluginManager, loadout, config,
			clientThread, bank, routeItem, carried, handoff);
	}

	@Test
	public void isFilteringIsFalseBeforeAnythingHasHappened()
	{
		assertFalse("a filter that has never opened must not claim the bank is narrowed",
			filter.isFiltering());
	}

	/**
	 * Without Bank Tags the filter is quietly absent, and stays absent.
	 *
	 * <p>Not just "false at startup": even a fully favourable tick — run active, bank open,
	 * setting on, items wanted — must not open anything, because there is nothing to open
	 * <i>with</i>. This is the soft-dependency promise, and getting it wrong is a
	 * NullPointerException in an event handler.
	 */
	@Test
	public void withoutBankTagsFilteringIsOffForever()
	{
		when(pluginManager.getPlugins())
			.thenReturn(Collections.<Plugin>singletonList(new Plugin()
			{
			}));

		filter.startUp();
		assertFalse(filter.isFiltering());

		aRunIsUnderWayAtAnOpenBank();
		filter.onGameTick(new GameTick());

		assertFalse("no Bank Tags, so the most favourable tick in the world opens nothing",
			filter.isFiltering());
		assertEquals(0, bankTags.openCalls);
	}

	/** Closing a filter that never opened must not reach into Bank Tags at all. */
	@Test
	public void closeWhenNotOpenIsANoOp()
	{
		filter.startUp();

		filter.close();

		assertEquals("nothing was open, so there was nothing to put back",
			0, bankTags.closeCalls);
		assertFalse(filter.isFiltering());
	}

	/**
	 * The happy path, pinned mostly so the deferred-close test below starts from something
	 * proven: a run under way at an open bank, with Bank Tags accepting the tag, filters.
	 */
	@Test
	public void aRunAtAnOpenBankOpensTheFilter()
	{
		filter.startUp();
		aRunIsUnderWayAtAnOpenBank();

		filter.onGameTick(new GameTick());

		assertTrue(filter.isFiltering());
		assertEquals("doogle-maps-run", bankTags.activeTag);
	}

	/**
	 * "We called it" and "it took" are different facts, and only the second is filtering.
	 *
	 * <p>{@code openBankTag} returns void and Bank Tags declines quietly in several places, so
	 * the filter asks {@code getActiveTag} back. Believing the call rather than the answer is
	 * how {@code BankHighlightOverlay} would be stood down over a bank that is not filtered.
	 */
	@Test
	public void anOpenBankTagsDeclinesIsNotBelieved()
	{
		bankTags.declineOpens = true;
		filter.startUp();
		aRunIsUnderWayAtAnOpenBank();

		filter.onGameTick(new GameTick());

		assertEquals("the open was attempted", 1, bankTags.openCalls);
		assertFalse("but Bank Tags did not take it, so we are not filtering",
			filter.isFiltering());
	}

	/**
	 * The bank closing schedules the tidy-up; it does not perform it.
	 *
	 * <p>The order is the whole test: at the moment {@code onWidgetClosed} returns, Bank Tags
	 * must not have been touched — the close has to happen inside the runnable handed to
	 * {@code invokeLater}, once the client is off its script stack. A synchronous call here is
	 * the "scripts are not reentrant" crash, reproducible by closing a filtered bank.
	 */
	@Test
	public void closingTheBankDefersTheCloseToTheClientThread()
	{
		filter.startUp();
		aRunIsUnderWayAtAnOpenBank();
		filter.onGameTick(new GameTick());
		assertTrue("fixture: the filter is open", filter.isFiltering());

		filter.onWidgetClosed(new WidgetClosed(InterfaceID.BANKMAIN, 0, false));

		assertEquals("Bank Tags untouched while still on the script stack",
			0, bankTags.closeCalls);

		ArgumentCaptor<Runnable> deferred = ArgumentCaptor.forClass(Runnable.class);
		verify(clientThread).invokeLater(deferred.capture());
		deferred.getValue().run();

		assertEquals("the runnable is where the close actually happens", 1, bankTags.closeCalls);
		assertFalse(filter.isFiltering());
	}

	/**
	 * The player clicking away from the filter is an answer, not a malfunction to correct.
	 *
	 * <h2>The jerk-back</h2>
	 *
	 * Their click on another tab already closed our view — Bank Tags deactivates the tag
	 * itself — but the filter kept believing it was open, and the next bank change re-opened
	 * the tag through the relayout while they were browsing. Reported from play as rude,
	 * accurately. Standing down means three things at once: we are no longer filtering, we do
	 * not close the view they chose out from under them, and we do not re-open ours for the
	 * rest of this bank.
	 */
	@Test
	public void navigatingAwayStandsTheFilterDownWithoutAFight()
	{
		filter.startUp();
		aRunIsUnderWayAtAnOpenBank();
		filter.onGameTick(new GameTick());
		assertTrue("fixture: the filter is open", filter.isFiltering());

		bankTags.activeTag = "the-players-own-tab";
		filter.onGameTick(new GameTick());

		assertFalse("their click already closed our view", filter.isFiltering());
		assertEquals("the view they chose instead is left exactly where they put it",
			0, bankTags.closeCalls);

		filter.onGameTick(new GameTick());
		filter.onGameTick(new GameTick());
		assertEquals("and nothing re-opens ours for the rest of this bank",
			1, bankTags.openCalls);
	}

	/** Leaving the filter is a statement about that bank, not the account: the next one filters. */
	@Test
	public void theNextBankOpensFilteredAgain()
	{
		filter.startUp();
		aRunIsUnderWayAtAnOpenBank();
		filter.onGameTick(new GameTick());
		bankTags.activeTag = "the-players-own-tab";
		filter.onGameTick(new GameTick());
		assertEquals("fixture: navigated away, one open so far", 1, bankTags.openCalls);

		when(client.getWidget(InterfaceID.Bankmain.ITEMS)).thenReturn(null);
		filter.onGameTick(new GameTick());

		aRunIsUnderWayAtAnOpenBank();
		filter.onGameTick(new GameTick());

		assertTrue(filter.isFiltering());
		assertEquals(2, bankTags.openCalls);
	}

	/** Every other interface closing is none of this class's business. */
	@Test
	public void someOtherWidgetClosingSchedulesNothing()
	{
		filter.startUp();

		filter.onWidgetClosed(new WidgetClosed(InterfaceID.BANKMAIN + 1, 0, false));

		verifyNoInteractions(clientThread);
	}

	// ------------------------------------------------------------------- helpers

	/** Everything {@code openIfWanted} checks, made true: bank on screen, run active, wants. */
	private void aRunIsUnderWayAtAnOpenBank()
	{
		Widget items = Mockito.mock(Widget.class);
		when(items.isHidden()).thenReturn(false);
		when(client.getWidget(InterfaceID.Bankmain.ITEMS)).thenReturn(items);

		when(planner.isActive()).thenReturn(true);
		when(config.filterBankToRun()).thenReturn(true);
		when(loadout.forRun(any())).thenReturn(Collections.singletonList(
			new LoadoutItem(5295, "Ranarr seed", LoadoutItem.Category.SEED,
				LoadoutItem.Need.WITHDRAW, 6, 6, "for the herb patches",
				LoadoutItem.From.BANK)));
	}

	/**
	 * Bank Tags, played by a real {@link Plugin} so {@code resolveBankTags} finds it the way
	 * it finds the real one: by interface, through the plugin's own injector.
	 *
	 * <p>A stub rather than a mock because {@code getInjector()} is final on {@code Plugin},
	 * and because recording plain fields keeps the ordering assertions above about the code
	 * under test rather than about verify() bookkeeping.
	 */
	private static final class RecordingBankTags extends Plugin implements BankTagsService
	{
		int openCalls;
		int closeCalls;
		String activeTag;

		/** When true, openBankTag is accepted silently and never becomes the active tag. */
		boolean declineOpens;

		RecordingBankTags()
		{
			Injector own = Mockito.mock(Injector.class);
			when(own.getInstance(TagManager.class)).thenReturn(Mockito.mock(TagManager.class));
			when(own.getInstance(LayoutManager.class))
				.thenReturn(Mockito.mock(LayoutManager.class));
			this.injector = own;
		}

		@Override
		public void openBankTag(String tag, int options)
		{
			openCalls++;
			if (!declineOpens)
			{
				activeTag = tag;
			}
		}

		@Override
		public void closeBankTag()
		{
			closeCalls++;
			activeTag = null;
		}

		@Override
		public String getActiveTag()
		{
			return activeTag;
		}

		@Override
		public BankTag getActiveBankTag()
		{
			return null;
		}

		@Override
		public Layout getActiveLayout()
		{
			return null;
		}
	}
}
