package com.dooglemaps.bank;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.state.RunTypeStore;
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
 * <p>Two colours, meaning opposite things. One says <b>take this</b>; the other says the tool
 * leprechaun already has it, so leave it and ask when you reach the patch. Anything already in
 * your pack is left unmarked, because there is nothing to do about it and a busy bank tells you
 * less than a sparse one.
 */
public class BankHighlightOverlay extends Overlay
{
	private static final int ITEM_FILL_ALPHA = 65;

	private final Client client;
	private final DoogleMapsConfig config;
	private final RunLoadout loadout;
	private final RunTypeStore runTypes;

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
	private int wantedTick = -1;

	/** The same snapshot as {@link #wanted}, kept in order for the vault's step sequence. */
	private java.util.List<LoadoutItem> loadoutItems = java.util.Collections.emptyList();
	private int loadoutTick = -1;

	/** The run's loadout, rebuilt at most once a tick for the reason {@link #wantedItems} gives. */
	private java.util.List<LoadoutItem> loadoutThisTick()
	{
		int tick = client.getTickCount();
		if (tick != loadoutTick)
		{
			loadoutTick = tick;
			Set<PatchImplementation> types = runTypes.getSelected();
			loadoutItems = types.isEmpty()
				? java.util.Collections.emptyList()
				: loadout.forRun(types);
		}
		return loadoutItems;
	}

	@Inject
	private BankHighlightOverlay(Client client, DoogleMapsConfig config, RunLoadout loadout,
		RunTypeStore runTypes, ItemManager itemManager, TooltipManager tooltips,
		com.dooglemaps.route.RunPlanner planner)
	{
		this.planner = planner;
		this.tooltips = tooltips;
		this.client = client;
		this.config = config;
		this.loadout = loadout;
		this.runTypes = runTypes;
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

		Map<Integer, LoadoutItem.Need> marked = wantedItems();
		if (marked.isEmpty())
		{
			return null;
		}

		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		highlightContainer(graphics, client.getWidget(InterfaceID.Bankmain.ITEMS), marked, mouse);
		highlightVault(graphics, mouse);
		return null;
	}

	/**
	 * The seed vault, one step at a time rather than all at once.
	 *
	 * <p>The bank is marked wholesale because you can see the whole thing and pick your way
	 * through it. The vault is not like that: it is divided by seed type, and a category shows its
	 * own seeds and hides the rest — so lighting up everything the run needs would mostly be
	 * lighting up things that are not on screen.
	 *
	 * <p>So it is a sequence. The next seed you have not withdrawn is either <b>in front of you</b>,
	 * in which case it is outlined, or it is <b>in another category</b>, in which case that category
	 * is outlined instead. Withdraw it and the loadout stops asking for it, so the next one becomes
	 * the step — group, seed, next group, next seed, with nothing to keep your place in.
	 *
	 * <h2>Inferred rather than tracked, which is what makes it robust</h2>
	 *
	 * Nothing here records which category is open, and it does not need to: <i>can I see the seed
	 * right now</i> answers the same question and cannot fall out of step with the interface. It is
	 * also why this works whether the vault filters by category or merely groups by it — if the
	 * seed is on screen it gets outlined either way.
	 */
	private void highlightVault(Graphics2D graphics, net.runelite.api.Point mouse)
	{
		Widget items = client.getWidget(InterfaceID.SeedVault.OBJ_LIST);
		if (items == null || items.isHidden() || items.getDynamicChildren() == null)
		{
			return;
		}

		LoadoutItem next = nextSeedToWithdraw();
		if (next == null)
		{
			return;
		}

		java.awt.Shape previousClip = graphics.getClip();
		graphics.clip(visibleBounds(items));
		try
		{
			for (Widget item : items.getDynamicChildren())
			{
				if (item == null || item.isSelfHidden() || item.getItemId() != next.getItemId())
				{
					continue;
				}

				highlight(graphics, item, colourFor(next.getNeed()));
				if (mouse != null && items.getBounds().contains(mouse.getX(), mouse.getY())
					&& item.getBounds().contains(mouse.getX(), mouse.getY()))
				{
					describe(item.getItemId(), next.getNeed());
				}
				return;
			}
		}
		finally
		{
			graphics.setClip(previousClip);
		}

		// Not on screen, so the step is the category rather than the seed.
		highlightCategoryFor(graphics, next);
	}

	/**
	 * Outlines the category holding a seed we cannot currently see.
	 *
	 * <p>Matched on the category's own label against the patch type's name, rather than on an index
	 * — the vault's categories are the game's taxonomy and ours is generated from RuneLite's, so
	 * the two agree on words like "Herb" and "Fruit tree" but nothing guarantees they agree on
	 * order. A label match that finds nothing simply highlights nothing, which is the right failure
	 * for something that is only ever a convenience.
	 *
	 * <p>Both list widgets are searched because the constants do not say which one carries the
	 * clickable entries, and looking in the wrong one is indistinguishable from the feature being
	 * broken.
	 */
	private void highlightCategoryFor(Graphics2D graphics, LoadoutItem next)
	{
		com.dooglemaps.data.Seed seed = com.dooglemaps.data.Seed.forItemId(next.getItemId());
		if (seed == null || seed.getPatchType() == null)
		{
			return;
		}

		String wanted = seed.getPatchType().getDisplayName();
		for (int listId : CATEGORY_LISTS)
		{
			Widget list = client.getWidget(listId);
			if (list == null || list.isHidden())
			{
				continue;
			}

			for (Widget entry : allChildren(list))
			{
				if (entry == null || entry.isSelfHidden() || entry.getText() == null)
				{
					continue;
				}
				if (matches(entry.getText(), wanted))
				{
					outlineWidget(graphics, list, entry, config.guideHighlightColour());
					return;
				}
			}
		}
	}

	/**
	 * Draws a box around a widget that is not an item.
	 *
	 * <p>{@link #highlight} cannot do this: it asks {@code ItemManager} for the item's outline and
	 * a category tab has no item id, so there was no sprite to trace and nothing was drawn at all
	 * — which is exactly how "the tabs aren't highlighting" looked. A tab is a label, so the thing
	 * to draw round it is its own bounds.
	 *
	 * <p>Clipped to the list <b>and its parent</b>. A scrolling list reports bounds for its whole
	 * contents rather than for the part you can see, so clipping to the list alone still let an
	 * entry scrolled out of view paint over the rest of the interface. The parent is the viewport,
	 * and the intersection of the two is the region actually on screen.
	 */
	private void outlineWidget(Graphics2D graphics, Widget list, Widget entry, Color colour)
	{
		java.awt.Rectangle bounds = entry.getBounds();
		if (bounds == null || bounds.isEmpty())
		{
			return;
		}

		java.awt.Shape previousClip = graphics.getClip();
		graphics.clip(visibleBounds(list));
		try
		{
			graphics.setColor(ColorUtil.colorWithAlpha(colour, ITEM_FILL_ALPHA));
			graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
			graphics.setColor(colour);
			graphics.drawRect(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1);
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
	 * below the viewport — so an outline drawn on a scrolled-off row lands wherever those bounds
	 * happen to reach, which is over the chat box and out of the interface. Intersecting with the
	 * parent brings it back to the visible window.
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

	/** The vault's left-hand lists, either of which may carry the clickable categories. */
	private static final int[] CATEGORY_LISTS = {
		InterfaceID.SeedVault.CATEGORY_LIST,
		InterfaceID.SeedVault.LEFT_LIST,
	};

	/**
	 * Whether a category label names this patch type.
	 *
	 * <p>Loose on purpose: the vault says "Allotments" where the patch type says "Allotment", and
	 * a strict match would silently never fire. Tags are stripped because interface text carries
	 * colour markup.
	 */
	private static boolean matches(String label, String patchType)
	{
		String cleaned = net.runelite.client.util.Text.removeTags(label).trim().toLowerCase();
		String wanted = patchType.trim().toLowerCase();
		return cleaned.equals(wanted) || cleaned.equals(wanted + "s")
			|| cleaned.startsWith(wanted);
	}

	/** A widget's children, whichever of the three lists the interface put them in. */
	private static java.util.List<Widget> allChildren(Widget widget)
	{
		java.util.List<Widget> children = new java.util.ArrayList<>();
		for (Widget[] group : new Widget[][]{
			widget.getDynamicChildren(), widget.getStaticChildren(), widget.getNestedChildren()})
		{
			if (group != null)
			{
				children.addAll(java.util.Arrays.asList(group));
			}
		}
		return children;
	}

	/**
	 * The next seed the run still wants out of storage, or null when there are none left.
	 *
	 * <p>In loadout order, which is grouped by planting group — so the sequence walks the run the
	 * way the run is organised rather than the way the vault happens to be sorted.
	 */
	private LoadoutItem nextSeedToWithdraw()
	{
		for (LoadoutItem item : loadoutThisTick())
		{
			if (item.getCategory() == LoadoutItem.Category.SEED
				&& item.getNeed() == LoadoutItem.Need.WITHDRAW)
			{
				return item;
			}
		}
		return null;
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

				highlight(graphics, item, colourFor(need));

				// Hovering explains the colour. Without it a second colour is a puzzle rather than
				// information — nobody should have to guess why their compost is marked
				// differently from their seeds.
				if (mouse != null && container.getBounds().contains(mouse.getX(), mouse.getY())
					&& item.getBounds().contains(mouse.getX(), mouse.getY()))
				{
					describe(item.getItemId(), need);
				}
			}
		}
		finally
		{
			graphics.setClip(previousClip);
		}
	}

	/**
	 * Take it, or leave it and ask the leprechaun.
	 *
	 * <p>Two colours because they mean opposite things. Marking the leprechaun's items in the
	 * withdraw colour would have you banking compost you have a thousand of on site; leaving
	 * them unmarked would read as the plugin having forgotten about compost entirely.
	 */
	private Color colourFor(LoadoutItem.Need need)
	{
		return need == LoadoutItem.Need.AT_LEPRECHAUN
			? config.guideLeprechaunColour()
			: config.guideHighlightColour();
	}

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

	private void describe(int itemId, LoadoutItem.Need need)
	{
		int tick = client.getTickCount();
		if (tick != describedTick || itemId != describedItemId)
		{
			describedTick = tick;
			describedItemId = itemId;
			described = loadout.itemFor(runTypes.getSelected(), itemId);
		}

		LoadoutItem item = described;
		if (item == null)
		{
			return;
		}

		String lead = need == LoadoutItem.Need.AT_LEPRECHAUN
			? "No need to take this - "
			: "For your run - ";
		tooltips.add(new Tooltip(lead + item.getReason()));
	}

	private Map<Integer, LoadoutItem.Need> wantedItems()
	{
		int tick = client.getTickCount();
		if (tick != wantedTick)
		{
			wantedTick = tick;
			Set<PatchImplementation> types = runTypes.getSelected();
			wanted = types.isEmpty() ? Collections.emptyMap() : loadout.highlights(types);
		}
		return wanted;
	}

	private void highlight(Graphics2D graphics, Widget item, Color colour)
	{
		Rectangle bounds = item.getBounds();
		BufferedImage outline =
			itemManager.getItemOutline(item.getItemId(), item.getItemQuantity(), colour);
		graphics.drawImage(outline, (int) bounds.getX(), (int) bounds.getY(), null);
		graphics.drawImage(
			ImageUtil.fillImage(
				itemManager.getImage(item.getItemId(), item.getItemQuantity(), false),
				ColorUtil.colorWithAlpha(colour, ITEM_FILL_ALPHA)),
			(int) bounds.getX(), (int) bounds.getY(), null);
	}
}
