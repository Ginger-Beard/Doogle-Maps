package com.dooglemaps.bank;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.guide.ItemHighlight;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;

/**
 * Marks the bank slots holding what this run still needs.
 *
 * <p>Same filled-outline treatment guided mode uses on inventory items, which is in turn Quest
 * Helper's: the item's own silhouette in the highlight colour, then a translucent wash over the
 * sprite. Consistency is the point — one plugin should not have two ways of saying "this one".
 *
 * <p>It marks what to <b>take</b>, and only that. Anything already in your pack, and anything the
 * tool leprechaun is holding, is left alone — there is nothing to withdraw in either case, and a
 * busy bank tells you less than a sparse one.
 *
 * <p>Two containers, and they earn their marks differently. The bank is only marked when it is
 * showing everything; see {@link #render}. The seed vault is always marked, because nothing
 * filters it.
 *
 * <p>A seed is marked in <b>one</b> of them, never both. Which store the run means is the loadout's
 * answer — {@code LoadoutItem.From} — and ignoring it made a seed held in the bank and the vault
 * look like two separate things to fetch.
 */
@lombok.extern.slf4j.Slf4j
public class BankHighlightOverlay extends Overlay
{
	/** The wash over a marked item. Shared, so the two overlays cannot drift apart. */
	private static final int ITEM_FILL_ALPHA = com.dooglemaps.guide.ItemHighlight.FILL_ALPHA;

	private final Client client;
	private final DoogleMapsConfig config;
	private final RunLoadout loadout;

	/** Asked whether the bank is already narrowed to the run. See {@link #render}. */
	private final BankFilter bankFilter;

	/** Whether a run is actually under way; the highlighting means nothing otherwise. */
	private final com.dooglemaps.route.RunPlanner planner;
	private final ItemManager itemManager;
	private final TooltipManager tooltips;

	/**
	 * The withdraw list, rebuilt once a tick rather than once a frame.
	 *
	 * <p>Building it walks the run planner and both item stores, all synchronised, from the
	 * client thread while the panel walks the same ones from the EDT. Same reasoning as the
	 * guide overlay: a tick is as fresh as any of it gets.
	 */
	private Map<Integer, LoadoutItem.Need> wanted = Collections.emptyMap();

	/** The same, for the vault. Kept apart so a seed in both stores is not marked twice. */
	private Map<Integer, LoadoutItem.Need> fromVault = Collections.emptyMap();
	private int wantedTick = -1;

	/** The run's loadout, for the withdraw counts. Rebuilt at most once a tick. */
	private java.util.List<LoadoutItem> loadoutItems = java.util.Collections.emptyList();
	private int loadoutTick = -1;

	/** The run's loadout, rebuilt at most once a tick for the reason {@link #wantedItems} gives. */
	private java.util.List<LoadoutItem> loadoutThisTick()
	{
		int tick = client.getTickCount();
		if (tick != loadoutTick)
		{
			loadoutTick = tick;
			Set<PatchImplementation> types = planner.coveredTypes();
			loadoutItems = types.isEmpty()
				? java.util.Collections.emptyList()
				: loadout.forRun(types);
		}
		return loadoutItems;
	}

	/** The route's own item, marked in cyan wherever it sits. See {@link RouteItem}. */
	private final RouteItem routeItem;

	/** The contract's crop, which is the one noted stack that must never be deposited. */
	private final com.dooglemaps.state.ContractState contracts;

	/** The hespori run's bank leg, whose marks are the deposit buttons rather than items. */
	private final InventorySetupsHandoff handoff;

	@Inject
	BankHighlightOverlay(Client client, DoogleMapsConfig config, RunLoadout loadout,
		ItemManager itemManager, TooltipManager tooltips,
		com.dooglemaps.route.RunPlanner planner, BankFilter bankFilter, RouteItem routeItem,
		com.dooglemaps.state.ContractState contracts, InventorySetupsHandoff handoff)
	{
		this.handoff = handoff;
		this.contracts = contracts;
		this.routeItem = routeItem;
		this.bankFilter = bankFilter;
		this.planner = planner;
		this.tooltips = tooltips;
		this.client = client;
		this.config = config;
		this.loadout = loadout;
		this.itemManager = itemManager;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.highlightBankItems())
		{
			return null;
		}

		// Only while a run is under way. This asked nothing about the run, so it marked items for
		// whatever patch types were ticked whether or not you were doing anything — and Stop Run
		// left the bank still highlighted, through closing and reopening it, with no way to make
		// it stop short of clearing the ticks. "What this run needs" has to mean a run.
		if (!planner.isActive())
		{
			return null;
		}

		net.runelite.api.Point mouse = client.getMouseCanvasPosition();

		// The gear phase draws nothing at all. The wanted marks and the counts below are the
		// farming loadout's and the vault holds no armour — and the deposit-button marks this
		// branch used to draw are gone on purpose: they could not tell "arrived full of the
		// wrong things" from "just put the loadout on", and every attempt to police the
		// difference nagged the player about gear they had chosen to carry. What goes to the
		// bank and what comes out is the player's own business on this leg; the guide's one
		// instruction line says the errand, Inventory Setups shows the setup, and the leg
		// ends the moment the bank has been opened and closed. Reported from play, three
		// times, each stricter version worse than the last.
		if (handoff.applies())
		{
			return null;
		}

		com.dooglemaps.route.RunPlanner.BankLegReason legReason = planner.getBankLegReason();

		// The bank's outlines only when it is showing everything.
		//
		// Highlighting and filtering answer the same question two ways: "these ones" out of the
		// whole bank, and "only these". Run both at once and every slot on screen is already an
		// item the run wants, so marking them marks the lot — a wall of colour that distinguishes
		// nothing, over a bank that had already done the distinguishing. The highlight is what you
		// need when the bank is full of everything else, which is precisely when the filter is off.
		//
		// Asked of the filter rather than of the setting, because the setting being on is not the
		// same as the filter being applied: without Bank Tags it never opens, and the highlight is
		// then the only thing there is.
		if (!bankFilter.isFiltering())
		{
			Map<Integer, LoadoutItem.Need> marked = wantedItems();
			if (!marked.isEmpty())
			{
				highlightContainer(graphics, client.getWidget(InterfaceID.Bankmain.ITEMS), marked,
					mouse);
			}
		}
		else
		{
			// The counts, though, draw either way — and filtering on by default meant "either
			// way" is mostly this way. The reasoning above is about redundancy: a filtered bank
			// has already answered *which* items, so the wash adds nothing. It has said nothing
			// about *how many*, which no part of the filter conveys, and which is the entire
			// point of the number. Skipping the counts with the outlines was how the cyan figure
			// shipped and then never appeared on anyone's screen.
			countContainer(graphics, client.getWidget(InterfaceID.Bankmain.ITEMS), mouse);
		}

		// The vault regardless. Nothing filters it — it is the game's own interface, and Bank Tags
		// does not reach it — so marking is the only thing pointing at the seeds in it.
		highlightVault(graphics, mouse);

		// And the other direction: what should go *into* the bank while it is open anyway.
		highlightDeposits(graphics, mouse,
			legReason == com.dooglemaps.route.RunPlanner.BankLegReason.DEPOSIT);
		return null;
	}

	/**
	 * Cyan, by request from play. This started amber to keep the deposit errand visually apart
	 * from the withdraw marks, but at the bank the amber read as the game's own quantity yellow
	 * and looked like a warning rather than a suggestion. Deposits sit in the pack-side panel,
	 * withdrawals in the bank grid, so the position already carries the distinction.
	 */
	private static final Color DEPOSIT_COLOUR = new Color(0x00, 0xFF, 0xFF);

	/**
	 * Marks the noted crops in the pack that this bank visit should absorb.
	 *
	 * <p>A mid-run bank detour — seeds forgotten, an axe for a dead tree — arrives with the
	 * noted harvests of every stop so far, each costing a slot for the rest of the run. The
	 * bank is open, depositing them is close to free, and nothing pointed at them; requested
	 * from play alongside the Camelot seed detour.
	 *
	 * <p><b>Noted stacks only.</b> A noted crop in the pack has already been finished with —
	 * that is what the noting was for — so it is safely a deposit. Loose items are the
	 * player's business: an unnoted watermelon might be lunch.
	 *
	 * <p><b>What is never marked:</b> the run's own protection payments, whether or not they
	 * are noted — the farmer takes them noted, and depositing them un-plans the run — and the
	 * contract's crop in either direction (assigned or awaiting hand-in), because depositing
	 * that is how a finished contract gets left unclaimed for a growth cycle.
	 */
	private void highlightDeposits(Graphics2D graphics, net.runelite.api.Point mouse,
		boolean depositLeg)
	{
		Widget inventory = client.getWidget(InterfaceID.Bankside.ITEMS);
		if (inventory == null || inventory.isHidden() || inventory.getDynamicChildren() == null)
		{
			return;
		}

		java.util.Set<Integer> keep = keepInPack();
		for (Widget item : inventory.getDynamicChildren())
		{
			if (item == null || item.isSelfHidden() || item.getItemId() <= 0)
			{
				continue;
			}

			net.runelite.api.ItemComposition composition =
				itemManager.getItemComposition(item.getItemId());
			if (composition.getNote() == -1 && !depositLeg)
			{
				// Ordinarily noted stacks only - a loose item might be lunch. On the deposit
				// trip the pack is full and the errand is shedding it, and the bulk is logs,
				// which never note: loose produce counts there, with the payment and contract
				// protections below still standing.
				continue;
			}

			int crop = composition.getNote() == -1
				? item.getItemId()
				: composition.getLinkedNoteId();
			com.dooglemaps.data.Produce produce = com.dooglemaps.data.Produce.getByItemID(crop);
			boolean notable = produce != null && produce.isNotable()
				|| com.dooglemaps.data.NotableHarvests.isNotable(crop);
			if (!notable || keep.contains(crop))
			{
				continue;
			}

			highlight(graphics, item, DEPOSIT_COLOUR);
			if (mouse != null && item.getBounds().contains(mouse.getX(), mouse.getY()))
			{
				tooltips.add(new Tooltip("Finished crops - deposit them"));
			}
		}
	}

	/**
	 * Marks the two deposit buttons while the hespori's gear stop still owns something.
	 *
	 * <p>Buttons rather than items, because the errand is "everything": the farming supplies,
	 * the harvests, whatever is worn. Each button stops being marked the moment its container
	 * is empty, so the marks also read as progress — two, one, done. Withdraw-side marking is
	 * deliberately absent: which items make a hespori fight is the player's own setup's
	 * business, and Inventory Setups highlights its setup's rows itself.
	 *
	 * <p>Only at the supply leg, like the summary lines: a bank opened after the leg is done
	 * is the player's own errand, likely re-banking the gear, and marking Deposit-worn at
	 * someone standing in full combat kit mid-run would be advice to disarm.
	 */
	/**
	 * The crops a bank visit must leave in the pack, unnoted ids.
	 *
	 * <p>From the loadout's own payment rows rather than the whole payment table, so only the
	 * payments <i>this run</i> is actually making are protected — noted jangerberries from an
	 * unprotected run are still a deposit.
	 */
	private java.util.Set<Integer> keepInPack()
	{
		java.util.Set<Integer> keep = new java.util.HashSet<>();
		for (LoadoutItem item : loadoutThisTick())
		{
			if (item.getCategory() == LoadoutItem.Category.PAYMENT)
			{
				keep.add(item.getItemId());
			}
		}

		com.dooglemaps.data.Produce assigned = contracts.getContract();
		if (assigned != null)
		{
			keep.add(assigned.getItemID());
		}
		com.dooglemaps.data.Produce awaiting = contracts.getAwaitingHandIn();
		if (awaiting != null)
		{
			keep.add(awaiting.getItemID());
		}
		return keep;
	}

	/**
	 * The seed vault: everything the run still wants out of it, marked at once.
	 *
	 * <h2>It used to be one seed at a time, pointing at a category tab</h2>
	 *
	 * The reasoning was that the vault is divided by seed type and shows one category at a time, so
	 * marking everything would mostly mark things that are off screen — and that the honest answer
	 * for an off-screen seed was to outline the category holding it instead.
	 *
	 * <p>The category half never worked. Two attempts at finding the label failed to highlight
	 * anything, and neither could be verified from outside the client. Meanwhile the premise was
	 * doing real damage on its own: with one seed marked at a time, a vault you have to scroll
	 * through tells you nothing until the right seed happens to be in view, and nothing at all
	 * about how many more there are.
	 *
	 * <p>So it marks the lot. Scrolling now reveals marks rather than hiding the only one — which
	 * is what a player scrolling a long list actually wants, and it needs no guess about how the
	 * interface is laid out. Off-screen marks cost nothing: the clip below means they are simply
	 * not drawn.
	 */
	private void highlightVault(Graphics2D graphics, net.runelite.api.Point mouse)
	{
		Widget items = client.getWidget(InterfaceID.SeedVault.OBJ_LIST);
		if (items == null || items.isHidden() || items.getDynamicChildren() == null)
		{
			return;
		}

		Map<Integer, LoadoutItem.Need> marked = vaultItems();
		if (marked.isEmpty())
		{
			return;
		}

		// The left-hand category rows, so a seed scrolled out of sight still says where it is.
		highlightVaultCategories(graphics);

		// Clipped to the visible part of the list. A scrolling container reports bounds covering
		// everything it holds, so a mark on a scrolled-off row would otherwise paint over the rest
		// of the interface.
		java.awt.Shape previousClip = graphics.getClip();
		graphics.clip(visibleBounds(items));
		try
		{
			for (Widget item : items.getDynamicChildren())
			{
				if (item == null || item.isSelfHidden() || !marked.containsKey(item.getItemId()))
				{
					continue;
				}

				highlight(graphics, item, config.guideHighlightColour());
				// The same count the bank gets. A seed is a seed wherever it is stored, and the
				// vault is where the expensive ones live.
				drawWithdrawCount(graphics, item, vaultWithdrawCounts());
				if (mouse != null && items.getBounds().contains(mouse.getX(), mouse.getY())
					&& item.getBounds().contains(mouse.getX(), mouse.getY()))
				{
					describe(item.getItemId());
				}
			}
		}
		finally
		{
			graphics.setClip(previousClip);
		}
	}

	/**
	 * Outlines the vault's category rows that hold a seed the run still wants.
	 *
	 * <h2>Two earlier attempts failed, and this is why</h2>
	 *
	 * The note on {@link #highlightVault} records them: "the category half never worked. Two
	 * attempts at finding the label failed to highlight anything, and neither could be verified
	 * from outside the client." Both were looking in the wrong place. The rows live under
	 * {@code SeedVault.CATEGORY_LIST} — group 631, child 8 — one dynamic child each, read off the
	 * widget inspector and given from play. That is the missing verification, and it is why this
	 * attempt is being made at all rather than a third guess.
	 *
	 * <h2>No table of which seed is in which category</h2>
	 *
	 * The obvious implementation is a map from crop to category name, and it would be a fourth
	 * thing to keep in step with the game — wrong the day a category is renamed or a seed moves,
	 * and wrong silently. The vault already knows: {@code CATEGORY_HEADERS} labels the sections
	 * of the item list, and {@code OBJ_LIST} lays the items out beneath them. So a seed's
	 * category is simply the nearest header above it, which is the same thing the player reads
	 * off the screen.
	 *
	 * <p>That also means this degrades honestly. If the headers are not laid out the way this
	 * expects, nothing matches and nothing is drawn — no invented highlight on the wrong row —
	 * and {@link #noteVaultCategories} says what it saw so the next attempt starts from data.
	 */
	private void highlightVaultCategories(Graphics2D graphics)
	{
		Widget list = client.getWidget(InterfaceID.SeedVault.CATEGORY_LIST);
		if (list == null || list.isHidden())
		{
			return;
		}

		Set<String> wanted = vaultCategoriesWanted();
		java.util.List<Widget> rows = new java.util.ArrayList<>();
		java.util.List<String> seen = new java.util.ArrayList<>();
		for (Widget row : descendants(list))
		{
			String text = plainText(row);
			if (text.isEmpty())
			{
				continue;
			}
			seen.add(text);
			if (wanted.contains(text.toLowerCase(java.util.Locale.ROOT)))
			{
				rows.add(row);
			}
		}
		noteVaultCategories(wanted, seen);

		java.awt.Shape previousClip = graphics.getClip();
		graphics.clip(visibleBounds(list));
		try
		{
			for (Widget row : rows)
			{
				Rectangle bounds = row.getBounds();
				if (bounds == null || bounds.isEmpty())
				{
					continue;
				}
				graphics.setColor(config.guideHighlightColour());
				graphics.draw(bounds);
			}
		}
		finally
		{
			graphics.setClip(previousClip);
		}
	}

	/**
	 * The category names holding a seed this run still wants, lowercased for matching.
	 *
	 * <p>Derived from where the items actually sit rather than from a table: the nearest header
	 * above an item is its category. Headers and items are separate widget lists laid out over
	 * the same scroll, so the comparison is on the y they were laid out at, not on the screen —
	 * scrolling moves both together and must not change the answer.
	 */
	private Set<String> vaultCategoriesWanted()
	{
		Map<Integer, LoadoutItem.Need> marked = vaultItems();
		return categoriesHolding(client.getWidget(InterfaceID.SeedVault.CATEGORY_HEADERS),
			client.getWidget(InterfaceID.SeedVault.OBJ_LIST), marked.keySet());
	}

	/**
	 * Which category sections these items sit under, lowercased.
	 *
	 * <p>Static and given its two widgets rather than reaching for the client, so the rule can be
	 * pinned by a test without an interface open. The rule is the whole of the design: a seed's
	 * category is the nearest header above it, which is what the player reads off the screen, and
	 * needs no table of crop-to-category kept in step with the game by hand.
	 *
	 * <p>Compared on the y each widget was <b>laid out</b> at, not on the screen. Headers and
	 * items are separate lists over one scroll, so scrolling moves both together and must not
	 * change the answer.
	 */
	static Set<String> categoriesHolding(Widget headers, Widget items, Set<Integer> wantedItems)
	{
		if (headers == null || items == null || wantedItems.isEmpty())
		{
			return Collections.emptySet();
		}

		// Headers by the y they are laid out at, so "the nearest one above" is a lookup.
		java.util.NavigableMap<Integer, String> byHeight = new java.util.TreeMap<>();
		for (Widget header : descendants(headers))
		{
			String text = plainText(header);
			if (!text.isEmpty())
			{
				byHeight.put(header.getRelativeY(), text.toLowerCase(java.util.Locale.ROOT));
			}
		}
		if (byHeight.isEmpty())
		{
			return Collections.emptySet();
		}

		Set<String> wanted = new java.util.LinkedHashSet<>();
		for (Widget item : descendants(items))
		{
			if (item.getItemId() <= 0 || !wantedItems.contains(item.getItemId()))
			{
				continue;
			}
			Map.Entry<Integer, String> header = byHeight.floorEntry(item.getRelativeY());
			if (header != null)
			{
				wanted.add(header.getValue());
			}
		}
		return wanted;
	}

	/** A widget's text with the game's colour tags taken off, or empty. */
	private static String plainText(Widget widget)
	{
		String text = widget.getText();
		return text == null ? "" : net.runelite.client.util.Text.removeTags(text).trim();
	}

	/** Every child of this widget, of either kind, one level down and then their children. */
	private static java.util.List<Widget> descendants(Widget parent)
	{
		java.util.List<Widget> found = new java.util.ArrayList<>();
		java.util.Deque<Widget> queue = new java.util.ArrayDeque<>();
		queue.add(parent);
		int guard = 0;
		while (!queue.isEmpty() && guard++ < VAULT_WIDGET_LIMIT)
		{
			Widget widget = queue.poll();
			for (Widget[] children : new Widget[][]{
				widget.getDynamicChildren(), widget.getStaticChildren(),
				widget.getNestedChildren()})
			{
				if (children == null)
				{
					continue;
				}
				for (Widget child : children)
				{
					if (child != null)
					{
						found.add(child);
						queue.add(child);
					}
				}
			}
		}
		return found;
	}

	/**
	 * A walk of a widget tree wants a ceiling, and the vault's is generous.
	 *
	 * <p>Not a tuning number: it is there so a cycle or an unexpectedly deep tree cannot spin the
	 * render thread. The real lists are a few dozen rows.
	 */
	private static final int VAULT_WIDGET_LIMIT = 4000;

	/** The last category decision logged, so it is said once per distinct answer. */
	private String loggedVaultCategories;

	/**
	 * Says what the category highlight wanted and what the vault offered, once per answer.
	 *
	 * <p>The two previous attempts at this could not be verified from outside the client, which
	 * is the whole reason they stayed broken. This makes the next report one line: the categories
	 * derived from where the seeds sit, and every row title actually on screen.
	 */
	private void noteVaultCategories(Set<String> wanted, java.util.List<String> seen)
	{
		String key = wanted + "#" + seen;
		if (key.equals(loggedVaultCategories))
		{
			return;
		}
		loggedVaultCategories = key;
		log.info("Seed vault categories: wanting {}, rows on screen {}", wanted, seen);
	}

	/**
	 * The part of a list that is actually on screen.
	 *
	 * <p>A scrolling container's own bounds cover everything it holds, including the rows above and
	 * below the viewport — so a mark drawn on a scrolled-off row lands wherever those bounds happen
	 * to reach, which is over the chat box and out of the interface. Intersecting with the parent
	 * brings it back to the visible window, and is what makes marking every needed seed safe: the
	 * ones you have scrolled past are clipped away rather than drawn somewhere absurd.
	 */
	private static java.awt.Rectangle visibleBounds(Widget list)
	{
		java.awt.Rectangle bounds = list.getBounds();
		Widget parent = list.getParent();
		if (parent != null && parent.getBounds() != null)
		{
			return bounds.intersection(parent.getBounds());
		}
		return bounds;
	}

	/** Marks whatever this container is holding that the run wants. */
	private void highlightContainer(Graphics2D graphics, Widget container,
		Map<Integer, LoadoutItem.Need> marked, net.runelite.api.Point mouse)
	{
		if (container == null || container.isHidden() || container.getDynamicChildren() == null)
		{
			return;
		}

		// Clipped to the item area. A bank holds every item's widget whether or not it is scrolled
		// into view, and an off-screen one still reports canvas bounds — so marking it drew a
		// highlight floating over the chat box, which is what was reported. Clipping is better
		// than a contains() test because a row half-scrolled at the edge is genuinely half
		// visible, and should be drawn that way rather than dropped.
		java.awt.Shape previousClip = graphics.getClip();
		graphics.clip(container.getBounds());

		try
		{
			for (Widget item : container.getDynamicChildren())
			{
				if (item == null || item.isSelfHidden())
				{
					continue;
				}

				LoadoutItem.Need need = marked.get(item.getItemId());
				boolean route = isRouteItem(item.getItemId());
				if (need == null && !route)
				{
					continue;
				}

				// The route's own item in cyan - the router picked it, so it outranks the
				// run's ordinary mark. Everything else keeps the configured colour.
				highlight(graphics, item, route ? COUNT_COLOUR : config.guideHighlightColour());
				drawWithdrawCount(graphics, item, withdrawCounts());

				// Hovering says why. A mark tells you to take something; the reason it is on the
				// list — which patch, which teleport, what the payment is for — is the part you
				// would otherwise have to go back to the panel for.
				if (mouse != null && container.getBounds().contains(mouse.getX(), mouse.getY())
					&& item.getBounds().contains(mouse.getX(), mouse.getY()))
				{
					describe(item.getItemId());
				}
			}
		}
		finally
		{
			graphics.setClip(previousClip);
		}
	}

	/**
	 * The counts alone, for a bank the filter has already narrowed.
	 *
	 * <p>Same clip and same walk as {@link #highlightContainer}, minus the wash — the filter has
	 * done the marking, and the number and the hover reason are the two things it cannot say.
	 */
	private void countContainer(Graphics2D graphics, Widget container,
		net.runelite.api.Point mouse)
	{
		if (container == null || container.isHidden() || container.getDynamicChildren() == null)
		{
			return;
		}

		java.awt.Shape previousClip = graphics.getClip();
		graphics.clip(container.getBounds());
		try
		{
			for (Widget item : container.getDynamicChildren())
			{
				if (item == null || item.isSelfHidden())
				{
					continue;
				}

				// The one wash a filtered bank gets: the route's own item, in cyan. A single
				// slot, so the wall-of-colour reasoning against washing a filtered bank does
				// not apply to it.
				if (isRouteItem(item.getItemId()))
				{
					highlight(graphics, item, COUNT_COLOUR);
				}
				drawWithdrawCount(graphics, item, withdrawCounts());
				if (mouse != null && container.getBounds().contains(mouse.getX(), mouse.getY())
					&& item.getBounds().contains(mouse.getX(), mouse.getY()))
				{
					describe(item.getItemId());
				}
			}
		}
		finally
		{
			graphics.setClip(previousClip);
		}
	}

	/**
	 * Writes how many to take over a marked slot.
	 *
	 * <h2>Every withdrawal gets one</h2>
	 *
	 * The counted rows — seeds, payments, pots — show the arithmetic nobody wants to do at a
	 * bank: six patches of ranarr is six seeds, but four magic trees is a hundred coconuts,
	 * and getting that wrong is discovered at the fourth tree having already travelled there.
	 * The unit rows — an axe, a can, a seed box — show a plain {@code 1} until they are on
	 * you. That used to be suppressed as noise the highlight had already covered, which
	 * stopped being true the moment the filter replaced the highlight: a filtered-in slot
	 * with no number carried no mark at all. See {@link LoadoutItem#getWithdrawCount}.
	 *
	 * <h2>Its own corner, and its own colour</h2>
	 *
	 * <b>Top-right, in cyan.</b> The bank draws what you own in the top-<i>left</i> of the slot,
	 * in yellow. This is a different fact — what is still to come out — and the two must not be
	 * mistakable for one another, so it takes the corner the bank leaves empty and a colour the
	 * bank never uses. It was previously yellow and nudged down the left edge to sit under the
	 * game's number, which put two yellow figures in one column and left the reader to work out
	 * which was which.
	 *
	 * <h2>It counts down</h2>
	 *
	 * From {@link LoadoutItem#getOutstanding}, so withdrawing three of your thirty spines leaves
	 * twenty-seven on the icon, and putting them back puts it to thirty again — the pack is what
	 * it is measured against, and {@code CarriedItems} is watching that live.
	 */
	private void drawWithdrawCount(Graphics2D graphics, Widget item,
		Map<Integer, Integer> counts)
	{
		// Not on a placeholder. An empty slot has nothing to withdraw, so a count over it is
		// an instruction that cannot be followed - reported as "1"s over placeholder
		// teleports in the filtered bank.
		//
		// Two shapes of placeholder, provable from Bank Tags' own source: a Jagex placeholder
		// renders with the bank's count, which is zero, and a *layout* placeholder - the faded
		// stand-in for a laid-out item the bank does not hold - is set to Integer.MAX_VALUE
		// with quantity drawing suppressed. The first guard caught only the first shape,
		// which is why the report said "still".
		int quantity = item.getItemQuantity();
		if (quantity <= 0 || quantity == Integer.MAX_VALUE)
		{
			return;
		}

		Integer left = counts.get(item.getItemId());
		if (left == null || left <= 0)
		{
			return;
		}

		java.awt.Rectangle bounds = item.getBounds();
		String text = String.valueOf(left);

		graphics.setFont(net.runelite.client.ui.FontManager.getRunescapeSmallFont());
		// Measured rather than assumed, because it is right-aligned: a two-digit count and a
		// three-digit one have to end at the same edge, not start at the same one.
		int width = graphics.getFontMetrics().stringWidth(text);
		int x = (int) bounds.getMaxX() - width - COUNT_INSET;
		int y = (int) bounds.getY() + COUNT_BASELINE;

		graphics.setColor(java.awt.Color.BLACK);
		graphics.drawString(text, x + 1, y + 1);
		graphics.setColor(COUNT_COLOUR);
		graphics.drawString(text, x, y);
	}

	/**
	 * Where the count sits inside a 32px slot.
	 *
	 * <p>Level with the game's own stack number rather than under it, because it is no longer in
	 * the same corner and no longer has to get out of its way.
	 */
	private static final int COUNT_BASELINE = 10;

	/** Breathing room from the right edge, so the last digit is not against the slot border. */
	private static final int COUNT_INSET = 2;

	/**
	 * Cyan, and specifically not the game's quantity yellow.
	 *
	 * <p>The colour is carrying the distinction between "how many you own" and "how many to
	 * take". Reusing yellow made this read as a second opinion about the first number.
	 */
	private static final Color COUNT_COLOUR = new Color(0x00, 0xFF, 0xFF);

	/**
	 * How many of each marked item to take from the <b>bank</b>, rebuilt once a tick.
	 *
	 * <p>Keyed by every bank form, for the reason the filter is: the loadout names the planted
	 * form and a tree crop sits in the bank as a seed, so keying on the loadout's own id alone
	 * would silently put no number on exactly the items that are expensive enough to count.
	 *
	 * <p>Split by the row's own container, like the marks already were. One shared map painted
	 * a vault row's count onto the bank's slot: two magic saplings wanted, four of them in the
	 * seed vault, and the bank's single sapling wore a cyan "2" — an instruction the slot
	 * cannot satisfy. Reported from play. The vault's own list gets its own map below.
	 */
	private Map<Integer, Integer> withdrawCounts()
	{
		refreshCounts();
		return withdrawCounts;
	}

	/** The same, for the seed vault's rows. See {@link #withdrawCounts()}. */
	private Map<Integer, Integer> vaultWithdrawCounts()
	{
		refreshCounts();
		return vaultWithdrawCounts;
	}

	private void refreshCounts()
	{
		int tick = client.getTickCount();
		if (tick == countsTick)
		{
			return;
		}
		countsTick = tick;

		Map<Integer, Integer> bank = new java.util.HashMap<>();
		Map<Integer, Integer> vault = new java.util.HashMap<>();
		for (LoadoutItem item : loadoutThisTick())
		{
			// The one shared rule for whether a number is an instruction — see
			// LoadoutItem.getWithdrawCount, which the withdraw list uses too, so the slot
			// and the list can never disagree.
			int count = item.getWithdrawCount();
			if (count <= 0)
			{
				continue;
			}
			Map<Integer, Integer> target =
				item.getFrom() == LoadoutItem.From.SEED_VAULT ? vault : bank;
			for (int form : RunLoadout.bankFormsOf(item.getItemId()))
			{
				// Summed rather than replaced. Two picked seeds can share a bank form only in
				// contrived cases, but a payment shared by two crops is ordinary — protecting
				// magic and yew both want coconuts, and the run needs the total.
				target.merge(form, count, Integer::sum);
			}
		}
		withdrawCounts = bank;
		vaultWithdrawCounts = vault;
	}

	private Map<Integer, Integer> withdrawCounts = Collections.emptyMap();
	private Map<Integer, Integer> vaultWithdrawCounts = Collections.emptyMap();
	private int countsTick = -1;

	/**
	 * The hovered item's own entry, looked up at most once a tick.
	 *
	 * <p>{@code itemFor} runs the whole loadout, which walks the run planner — and
	 * {@code previewStops} is synchronised and replans from scratch, once per region. Called
	 * straight from {@code render} that was several synchronised replans <b>per frame</b> for
	 * as long as the mouse sat over a bank slot, contending with the Swing thread doing the
	 * same. The highlight set beside it was already throttled; this was not.
	 */
	private int describedItemId = -1;
	private int describedTick = -1;
	private LoadoutItem described;

	private void describe(int itemId)
	{
		int tick = client.getTickCount();
		if (tick != describedTick || itemId != describedItemId)
		{
			describedTick = tick;
			describedItemId = itemId;
			described = loadout.itemFor(planner.coveredTypes(), itemId);
		}

		LoadoutItem item = described;
		if (item == null)
		{
			return;
		}

		tooltips.add(new Tooltip("For your run - " + item.getReason()));
	}

	/**
	 * What to take out of the <b>bank</b>, rebuilt once a tick.
	 *
	 * <p>Split from the vault's list rather than shared with it, and that is a fix rather than
	 * tidiness. {@code Need.WITHDRAW} means "not on you", which is true of a seed sitting in either
	 * store — so a seed you hold in both was marked in the bank <i>and</i> queued in the vault, and
	 * the run appeared to want two lots of it. {@code LoadoutItem.From} is the loadout's own answer
	 * to which one it means, and it was being ignored here.
	 */
	private Map<Integer, LoadoutItem.Need> wantedItems()
	{
		refreshWanted();
		return wanted;
	}

	/** The same, for the seed vault. See {@link #wantedItems()}. */
	private Map<Integer, LoadoutItem.Need> vaultItems()
	{
		refreshWanted();
		return fromVault;
	}

	/**
	 * Rebuilds both lists together, once a tick.
	 *
	 * <p>Together because they are one pass over one loadout, and because two caches keyed on the
	 * same tick that could disagree is exactly the kind of thing that produces an item marked in
	 * neither place.
	 */
	private void refreshWanted()
	{
		int tick = client.getTickCount();
		if (tick == wantedTick)
		{
			return;
		}
		wantedTick = tick;

		Set<PatchImplementation> types = planner.coveredTypes();
		if (types.isEmpty())
		{
			wanted = Collections.emptyMap();
			fromVault = Collections.emptyMap();
			return;
		}

		Map<Integer, LoadoutItem.Need> bank = new java.util.HashMap<>();
		Map<Integer, LoadoutItem.Need> vault = new java.util.HashMap<>();
		for (LoadoutItem item : loadout.forRun(types))
		{
			if (item.getNeed() != LoadoutItem.Need.WITHDRAW)
			{
				continue;
			}

			Map<Integer, LoadoutItem.Need> target =
				item.getFrom() == LoadoutItem.From.SEED_VAULT ? vault : bank;
			// Every form it could be sitting as. A loadout item names the planted form, and a tree
			// crop is a seed in storage — the same expansion the filter does.
			for (int form : RunLoadout.bankFormsOf(item.getItemId()))
			{
				target.put(form, item.getNeed());
			}
		}

		wanted = bank;
		fromVault = vault;
	}

	/** Whether this slot holds the item the current route travels by, in any bank form. */
	private boolean isRouteItem(int itemId)
	{
		int route = routeItem.currentItemId();
		return route > 0 && RunLoadout.bankFormsOf(route).contains(itemId);
	}

	private void highlight(Graphics2D graphics, Widget item, Color colour)
	{
		// Shared with the inventory overlay rather than written twice. The two mark the same item
		// at two moments of one errand — take this out of the bank, then click this one — so they
		// have to look identical, and that is easier to guarantee than to remember.
		ItemHighlight.draw(graphics, itemManager, item.getBounds(),
			item.getItemId(), item.getItemQuantity(), colour);
	}
}
