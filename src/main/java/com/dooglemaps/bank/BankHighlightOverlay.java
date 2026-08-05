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

	@Inject
	private BankHighlightOverlay(Client client, DoogleMapsConfig config, RunLoadout loadout,
		RunTypeStore runTypes, ItemManager itemManager, TooltipManager tooltips)
	{
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

		Widget container = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (container == null || container.isHidden() || container.getDynamicChildren() == null)
		{
			return null;
		}

		Map<Integer, LoadoutItem.Need> marked = wantedItems();
		if (marked.isEmpty())
		{
			return null;
		}

		net.runelite.api.Point mouse = client.getMouseCanvasPosition();

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
			// information — nobody should have to guess why their compost is marked differently
			// from their seeds.
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
		return null;
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
