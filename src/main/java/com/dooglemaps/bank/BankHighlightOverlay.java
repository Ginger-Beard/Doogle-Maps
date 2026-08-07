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

	@Inject
	private BankHighlightOverlay(Client client, DoogleMapsConfig config, RunLoadout loadout,
		ItemManager itemManager, TooltipManager tooltips,
		com.dooglemaps.route.RunPlanner planner, BankFilter bankFilter)
	{
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

		// The bank only when it is showing everything.
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

		// The vault regardless. Nothing filters it — it is the game's own interface, and Bank Tags
		// does not reach it — so marking is the only thing pointing at the seeds in it.
		highlightVault(graphics, mouse);
		return null;
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
				drawWithdrawCount(graphics, item);
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
				if (need == null)
				{
					continue;
				}

				highlight(graphics, item, config.guideHighlightColour());
				drawWithdrawCount(graphics, item);

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
	 * Writes how many to take over a marked slot.
	 *
	 * <h2>Only where the number is an instruction</h2>
	 *
	 * Seeds and protection payments, and nothing else. Those are the two the run needs a
	 * <b>specific quantity</b> of, and the quantity is arithmetic nobody wants to do at a bank:
	 * six patches of ranarr is six seeds, but four magic trees is a hundred coconuts, and getting
	 * that wrong is discovered at the fourth tree having already travelled there.
	 *
	 * <p>Everything else on the list is "bring the thing" — one axe, one seed box, whichever
	 * teleport you own. A "1" over each would be four more numbers to read and none of them a
	 * decision, which is how a marker stops being worth looking at.
	 *
	 * <h2>Drawn like the game's own stack counts</h2>
	 *
	 * Yellow with a black offset shadow, top-left of the slot: the position and colour the game
	 * uses for a stack size, so it reads as a quantity without a legend. Deliberately <i>not</i>
	 * where the item's own stack number sits when the bank draws one — that is the same corner, so
	 * this is nudged down to sit under it rather than over it. The two say different things: the
	 * game's is what you own, this is what to take.
	 */
	private void drawWithdrawCount(Graphics2D graphics, Widget item)
	{
		Integer wanted = withdrawCounts().get(item.getItemId());
		if (wanted == null || wanted <= 1)
		{
			// One is not worth saying. "Take 1 ranarr" is what a highlight already means, and the
			// number would only be competing with the sprite for the same few pixels.
			return;
		}

		java.awt.Rectangle bounds = item.getBounds();
		String text = String.valueOf(wanted);
		int x = (int) bounds.getX() + 1;
		int y = (int) bounds.getY() + COUNT_BASELINE;

		graphics.setFont(net.runelite.client.ui.FontManager.getRunescapeSmallFont());
		graphics.setColor(java.awt.Color.BLACK);
		graphics.drawString(text, x + 1, y + 1);
		graphics.setColor(COUNT_COLOUR);
		graphics.drawString(text, x, y);
	}

	/**
	 * Where the count sits inside a 32px slot.
	 *
	 * <p>Below the game's own stack number rather than on top of it. A bank slot draws the amount
	 * you own in the top-left corner; this is a different fact and has to be legible beside it
	 * rather than fighting it for the same pixels.
	 */
	private static final int COUNT_BASELINE = 22;

	/** The game's own quantity yellow, so the number reads as a count without being explained. */
	private static final Color COUNT_COLOUR = new Color(0xFF, 0xFF, 0x00);

	/**
	 * How many of each marked item the run wants, rebuilt once a tick.
	 *
	 * <p>Keyed by every bank form, for the reason the filter is: the loadout names the planted
	 * form and a tree crop sits in the bank as a seed, so keying on the loadout's own id alone
	 * would silently put no number on exactly the items that are expensive enough to count.
	 */
	private Map<Integer, Integer> withdrawCounts()
	{
		int tick = client.getTickCount();
		if (tick != countsTick)
		{
			countsTick = tick;
			Map<Integer, Integer> counts = new java.util.HashMap<>();
			for (LoadoutItem item : loadoutThisTick())
			{
				if (item.getQuantity() <= 1 || !COUNTED.contains(item.getCategory()))
				{
					continue;
				}
				for (int form : RunLoadout.bankFormsOf(item.getItemId()))
				{
					// Summed rather than replaced. Two picked seeds can share a bank form only in
					// contrived cases, but a payment shared by two crops is ordinary — protecting
					// magic and yew both want coconuts, and the run needs the total.
					counts.merge(form, item.getQuantity(), Integer::sum);
				}
			}
			withdrawCounts = counts;
		}
		return withdrawCounts;
	}

	private Map<Integer, Integer> withdrawCounts = Collections.emptyMap();
	private int countsTick = -1;

	/** The categories whose quantity is a decision rather than "bring the one you own". */
	private static final Set<LoadoutItem.Category> COUNTED = java.util.EnumSet.of(
		LoadoutItem.Category.SEED, LoadoutItem.Category.PAYMENT);

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

	private void highlight(Graphics2D graphics, Widget item, Color colour)
	{
		// Shared with the inventory overlay rather than written twice. The two mark the same item
		// at two moments of one errand — take this out of the bank, then click this one — so they
		// have to look identical, and that is easier to guarantee than to remember.
		ItemHighlight.draw(graphics, itemManager, item.getBounds(),
			item.getItemId(), item.getItemQuantity(), colour);
	}
}
