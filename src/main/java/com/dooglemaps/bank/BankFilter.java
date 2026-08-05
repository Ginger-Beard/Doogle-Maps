package com.dooglemaps.bank;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.state.RunTypeStore;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.plugins.banktags.TagManager;

/**
 * Filters the bank down to what the run actually needs.
 *
 * <p>The other half of {@link BankHighlightOverlay}. Highlighting says "these ones"; filtering
 * says "only these", which is faster to act on and much worse to get wrong — which is why this
 * arrived long after the highlighting and is <b>off by default</b>.
 *
 * <h2>Why off by default, still</h2>
 *
 * A wrong highlight is ignorable: you see a marked item, you disagree, you move on. A wrong
 * filter <i>hides</i> things, and you cannot see what is missing, because hiding is what a filter
 * does. Someone whose seed selection does not match what they are about to plant notices with
 * highlighting and arrives short with filtering. So this is opt-in, and it never hides anything
 * without the player having asked it to.
 *
 * <h2>How it is done</h2>
 *
 * A <b>virtual</b> tag, registered with {@link TagManager#registerTag}, whose membership is a
 * live question rather than a saved list of item ids. That matters: the run's contents change as
 * seeds are picked and patches ripen, and a real tag would have to be rewritten to keep up — and
 * would leave a stray tag behind in the player's own bank tag list if anything went wrong.
 * Nothing here is persisted and nothing survives the plugin being switched off.
 *
 * <p>Soft dependency, like the routing. Bank Tags is part of the client rather than the Hub, so
 * the classes are always present, but if the plugin is switched off the tag simply never opens
 * and the highlighting carries on alone.
 */
@Slf4j
@Singleton
public class BankFilter
{
	/**
	 * The tag's name, which the player never sees.
	 *
	 * <p>Opened with {@code OPTION_HIDE_TAG_NAME}, so this is an internal key rather than a
	 * label. Prefixed to make a collision with a tag someone made by hand effectively impossible.
	 */
	private static final String TAG = "doogle-maps-run";

	/**
	 * Bank Tags, fetched from its own plugin rather than injected.
	 *
	 * <h2>Why injection cannot work here, in either form</h2>
	 *
	 * {@code BankTagsPlugin.configure} binds {@code BankTagsService} to itself, and a plugin's
	 * module is installed into <b>that plugin's</b> injector. Ours is a sibling, not a child, so
	 * the binding is not visible to us at all.
	 *
	 * <p>Asking for it as a constructor parameter therefore fails outright with "No implementation
	 * was bound", and a plugin whose injector cannot be created does not load — which is exactly
	 * what happened. {@code com.google.inject.Inject(optional = true)} stops that being fatal, but
	 * it does not make the binding visible: the field is simply left null, every use is skipped,
	 * and the feature is silently dead while the log line says so once at startup and is never
	 * read again. That is the worse failure of the two, because it looks like it works.
	 *
	 * <p>{@code TagManager} is worse still. It has no binding anywhere, so our injector would
	 * happily construct a <i>second</i> one — and a tag registered on an instance Bank Tags has
	 * never heard of does nothing at all, with no error to show for it.
	 *
	 * <p>So both come from the Bank Tags plugin instance: it implements {@code BankTagsService}
	 * directly, and its own injector holds the one {@code TagManager} that matters.
	 */
	private BankTagsService bankTags;

	private TagManager tagManager;

	private final Client client;

	/** Whether a run is under way; the filter is meaningless otherwise. */
	private final com.dooglemaps.route.RunPlanner planner;
	private final PluginManager pluginManager;
	private final RunLoadout loadout;
	private final RunTypeStore runTypes;
	private final DoogleMapsConfig config;

	/**
	 * The item ids the run wants, rebuilt once a tick while the bank is open.
	 *
	 * <p>Cached because {@code contains} is called by the bank for every item on every redraw,
	 * and building the loadout walks the planner and both item stores. Volatile rather than
	 * synchronised: the bank asks from the client thread and this is written from the same one,
	 * but the panel can rebuild the loadout on the Swing thread and a torn read would flicker.
	 */
	private volatile Set<Integer> wanted = new HashSet<>();

	private boolean registered;
	private boolean open;

	@Inject
	private BankFilter(Client client, com.dooglemaps.route.RunPlanner planner,
		PluginManager pluginManager, RunLoadout loadout, RunTypeStore runTypes,
		DoogleMapsConfig config)
	{
		this.planner = planner;
		this.client = client;
		this.pluginManager = pluginManager;
		this.loadout = loadout;
		this.runTypes = runTypes;
		this.config = config;
	}

	public void startUp()
	{
		if (!resolveBankTags())
		{
			log.info("Bank Tags is unavailable, so run filtering is off. Highlighting is "
				+ "unaffected.");
			return;
		}

		try
		{
			tagManager.registerTag(TAG, itemId -> wanted.contains(itemId));
			registered = true;
			log.info("Bank filtering is available ({})", bankTags.getClass().getSimpleName());
		}
		catch (RuntimeException e)
		{
			// Bank Tags is part of the client, so this should not happen — but a filter is a
			// convenience and must never be the reason the plugin fails to start.
			log.warn("Could not register the bank filter; filtering will be unavailable", e);
		}
	}

	/**
	 * Finds the running Bank Tags plugin and takes its service and tag manager.
	 *
	 * <p>Matched on the interface rather than the class name, so it does not care where the
	 * plugin lives or what it is called. Whether it is <i>enabled</i> is not checked: the plugin
	 * being present is what makes the binding real, and a tag registered while it is off simply
	 * does nothing until it comes back on — which is the behaviour you want anyway.
	 */
	private boolean resolveBankTags()
	{
		for (Plugin plugin : pluginManager.getPlugins())
		{
			if (!(plugin instanceof BankTagsService))
			{
				continue;
			}

			try
			{
				// The one TagManager Bank Tags itself uses. Constructing our own would register
				// the tag on an object nothing ever asks.
				tagManager = plugin.getInjector().getInstance(TagManager.class);
				bankTags = (BankTagsService) plugin;
				return true;
			}
			catch (RuntimeException e)
			{
				log.warn("Found Bank Tags but could not reach its tag manager", e);
				return false;
			}
		}
		return false;
	}

	public void shutDown()
	{
		close();
		if (registered && tagManager != null)
		{
			tagManager.unregisterTag(TAG);
			registered = false;
		}
	}

	/**
	 * Whether the bank is on screen, asked of the widget rather than of an event.
	 *
	 * <p>This used to open the tag from {@code WidgetLoaded}, and that is a worse hook than it
	 * looks. Bank Tags listens to the same event and does its own setup from it, so which of the
	 * two runs first is a matter of subscriber order — and a tag opened before Bank Tags has
	 * built the bank is a tag it then builds over. Nothing errors; the filter simply does not
	 * appear, which is exactly the symptom.
	 *
	 * <p>The highlighting has always polled this widget, and it has always worked. Doing the same
	 * puts the open a tick after the bank exists rather than in the middle of it being made.
	 */
	private boolean bankIsOpen()
	{
		Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
		return items != null && !items.isHidden();
	}

	/**
	 * Puts the bank back when it closes.
	 *
	 * <p>Not optional tidying. Opening a bank tag puts the client into bank-search input mode,
	 * and that input is the <b>chatbox</b> — the same widget that otherwise reads "Press Enter to
	 * Chat...". Bank Tags restores it when the tag is closed properly; leave the tag open and the
	 * chatbox is stranded showing a bare cursor, in every scene, until something else happens to
	 * reset it. There is no bank on screen at that point to suggest what caused it.
	 */
	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			close();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (!open)
		{
			if (bankIsOpen())
			{
				// Rebuilt before opening rather than on a timer, so the first thing shown is
				// right. A filter that is briefly wrong is worse than one that appears a moment
				// later.
				refresh();
				openIfWanted();
			}
			return;
		}

		if (!bankIsOpen() || !planner.isActive())
		{
			// Stopping a run puts the bank back immediately, rather than leaving it filtered
			// until something else happens to close the tag.
			close();
			return;
		}

		if (!config.filterBankToRun())
		{
			// Switched off while the bank is open. Closing immediately is the whole point of a
			// filter's off switch — being told to reopen the bank would be a poor answer.
			close();
			return;
		}
		refresh();
	}

	/** Puts the bank back the way it was. */
	public void close()
	{
		if (!open || bankTags == null)
		{
			return;
		}
		open = false;

		try
		{
			bankTags.closeBankTag();
		}
		catch (RuntimeException e)
		{
			log.debug("Could not close the bank filter", e);
		}
	}

	/**
	 * Opens the filter, or says why it did not.
	 *
	 * <p>Four things have to be true and every one of them used to fail silently — two of them at
	 * {@code debug}, which in practice means invisibly. A feature that is off for a reason nobody
	 * can see is indistinguishable from a feature that is broken, and this one spent a long time
	 * being reported as the second when it was the first.
	 *
	 * <p>Logged once per bank rather than per tick, and only when something is actually stopping
	 * it — the working case is silent.
	 */
	private void openIfWanted()
	{
		if (!planner.isActive())
		{
			// Same rule as the highlighting: a filter for "this run" means nothing without one.
			logOnce("No run is under way, so there is nothing to filter the bank to - press "
				+ "Start run first");
			return;
		}
		if (!registered)
		{
			logOnce("Bank Tags did not accept the filter tag, so filtering is unavailable");
			return;
		}
		if (!config.filterBankToRun())
		{
			logOnce("Bank filtering is switched off in the settings (Guided run > Filter the "
				+ "bank to this run)");
			return;
		}
		if (wanted.isEmpty())
		{
			logOnce("The run needs nothing from the bank, so there is nothing to filter to - "
				+ "pick a run type and a seed first");
			return;
		}
		open();
	}

	/** The last thing said about why the filter is off, so it is said once and not every bank. */
	private String lastComplaint;

	private void logOnce(String message)
	{
		if (!message.equals(lastComplaint))
		{
			lastComplaint = message;
			log.info("{}", message);
		}
	}

	private void open()
	{
		try
		{
			// No layout, and the tag name hidden: this is a view of the run rather than a tab the
			// player owns, and letting it be laid out or renamed would make it look like one.
			bankTags.openBankTag(TAG,
				BankTagsService.OPTION_HIDE_TAG_NAME | BankTagsService.OPTION_NO_LAYOUT);
			open = true;
			lastComplaint = null;

			// Asked back rather than assumed. openBankTag returns void and Bank Tags declines
			// quietly in several places — an unregistered tag, a bank it does not think is ready
			// — so "we called it" and "it took" are different facts, and only the second one is
			// the feature working. This is the line that says which.
			String active = bankTags.getActiveTag();
			if (TAG.equals(active))
			{
				log.debug("Bank filtered to {} items", wanted.size());
			}
			else
			{
				log.warn("Bank Tags did not take the filter: asked for \"{}\", active tag is "
					+ "\"{}\". Filtering will not appear.", TAG, active);
				open = false;
			}
		}
		catch (RuntimeException e)
		{
			// Not debug. This is the call that either works or does not, and burying its failure
			// is how the feature came to look like it was working when it had never run.
			log.warn("Could not open the bank filter", e);
		}
	}

	private void refresh()
	{
		Set<Integer> items = new HashSet<>();
		for (LoadoutItem item : loadout.forRun(runTypes.getSelected()))
		{
			// Everything the run touches, not only what is missing. An item you already have is
			// not in the bank to be hidden, and one the leprechaun holds is worth seeing so you
			// know to leave it — filtering it out would read as "you do not own this".
			items.add(item.getItemId());
		}
		wanted = items;
	}
}
