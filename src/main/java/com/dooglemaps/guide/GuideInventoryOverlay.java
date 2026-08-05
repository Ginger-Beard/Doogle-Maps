package com.dooglemaps.guide;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.CompostTier;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;

/**
 * Marks the item the current step wants used, wherever it is on screen.
 *
 * <p>Two places, drawn differently because they are different shapes. The <b>inventory</b> gets
 * Quest Helper's filled outline on a 32px square. The <b>tool leprechaun's store</b> gets its
 * slot outlined instead: those are panels with a label, a count and a picture, several times
 * the size of an inventory square, and drawing an item sprite into one left a small stray
 * bucket in the corner rather than marking anything.
 *
 * <p>Separate from {@link GuideOverlay} purely because of <b>draw order</b>, which is not
 * obvious and cost a bug. World outlines belong on {@code ABOVE_SCENE}, so they never paint
 * over an open bank; but these are widgets, and anything on {@code ABOVE_SCENE} is drawn
 * <i>underneath</i> them. So the item highlight was being painted and then covered by the
 * inventory panel — the leprechaun lit up and the watermelon appeared not to, when in fact it
 * had been drawn and hidden.
 *
 * <p>One overlay cannot be on both layers, so there are two.
 */
public class GuideInventoryOverlay extends Overlay
{
	/** Alpha for the tint over a highlighted item, matching Quest Helper's. */
	private static final int ITEM_FILL_ALPHA = 65;

	/** Lighter than an item tint: a leprechaun slot is a big panel, not a 32px square. */
	private static final int SLOT_FILL_ALPHA = 40;

	/**
	 * Where each item lives inside the tool leprechaun's store.
	 *
	 * <p>A lookup rather than a scan, because that interface holds one named widget per thing
	 * it stores rather than a list of items. Only what guided mode ever asks for there.
	 */
	private static final Map<Integer, Integer> LEPRECHAUN_SLOTS = new HashMap<>();
	private static final Map<Integer, Integer> LEPRECHAUN_SIDE_SLOTS = new HashMap<>();

	static
	{
		// Keyed off CompostTier rather than off item constants, so the key is by construction
		// the same id GuidePlan puts in the step. Hardcoding them separately would work until
		// one of the two changed.
		slot(CompostTier.COMPOST,
			InterfaceID.FarmingTools.COMPOST, InterfaceID.FarmingToolsSide.COMPOST);
		slot(CompostTier.SUPERCOMPOST,
			InterfaceID.FarmingTools.SUPERCOMPOST, InterfaceID.FarmingToolsSide.SUPERCOMPOST);
		slot(CompostTier.ULTRACOMPOST,
			InterfaceID.FarmingTools.ULTRACOMPOST, InterfaceID.FarmingToolsSide.ULTRACOMPOST);

		// Where the empties go back. Same lookup, since his store is named slots rather than
		// an item list.
		LEPRECHAUN_SLOTS.put(ItemID.BUCKET_EMPTY, InterfaceID.FarmingTools.BUCKET);
		LEPRECHAUN_SIDE_SLOTS.put(ItemID.BUCKET_EMPTY, InterfaceID.FarmingToolsSide.BUCKET);
	}

	private static void slot(CompostTier tier, int full, int side)
	{
		LEPRECHAUN_SLOTS.put(tier.getItemID(), full);
		LEPRECHAUN_SIDE_SLOTS.put(tier.getItemID(), side);
	}

	private final Client client;
	private final GuideTracker tracker;
	private final DoogleMapsConfig config;
	private final ItemManager itemManager;

	@Inject
	private GuideInventoryOverlay(Client client, GuideTracker tracker, DoogleMapsConfig config,
		ItemManager itemManager)
	{
		this.client = client;
		this.tracker = tracker;
		this.config = config;
		this.itemManager = itemManager;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.guidedMode())
		{
			return null;
		}

		GuideStep step = tracker.getCurrentStep();
		if (step == null || !step.hasItem())
		{
			return null;
		}

		Color colour = config.guideHighlightColour();

		// One or the other, never both. The withdraw and apply steps name the same bucket, so
		// keying the store highlight off the item id alone left his slot lit after the
		// withdrawal was already done - and pointed at the leprechaun while the instruction
		// said to treat the patch.
		if (step.isAtLeprechaun())
		{
			highlightInLeprechaunStore(graphics, step.getItemId(), colour);
		}
		else
		{
			highlightInInventory(graphics, step.getItemId(), colour);
		}
		return null;
	}

	private void highlightInInventory(Graphics2D graphics, int itemId, Color colour)
	{
		Widget inventory = client.getWidget(InterfaceID.Inventory.ITEMS);
		if (inventory == null || inventory.isHidden() || inventory.getDynamicChildren() == null)
		{
			return;
		}

		for (Widget item : inventory.getDynamicChildren())
		{
			if (item != null && item.getItemId() == itemId)
			{
				drawItemHighlight(graphics, item.getBounds(), itemId, item.getItemQuantity(), colour);
			}
		}
	}

	/**
	 * Marks the slot inside the tool leprechaun's store.
	 *
	 * <p>Needs its own handling because that interface is not an item list. Each thing it holds
	 * has its own named widget — a compost slot, a supercompost slot — so there is nothing to
	 * scan for an item id and the slot has to be looked up instead.
	 *
	 * <p>Without it, telling someone to withdraw ultracompost lit up the leprechaun and then
	 * left them to find it among a dozen identical-looking buckets.
	 */
	private void highlightInLeprechaunStore(Graphics2D graphics, int itemId, Color colour)
	{
		Integer slot = LEPRECHAUN_SLOTS.get(itemId);
		if (slot == null)
		{
			return;
		}

		Widget widget = client.getWidget(slot);
		if (widget == null || widget.isHidden())
		{
			// The store has a full-screen form and a sidebar form, and only one is ever open.
			Integer sideSlot = LEPRECHAUN_SIDE_SLOTS.get(itemId);
			widget = sideSlot == null ? null : client.getWidget(sideSlot);
		}

		if (widget == null || widget.isHidden())
		{
			return;
		}

		// The slot is outlined, not filled with an item sprite. These are panels — a label, a
		// count and a picture — several times the size of an inventory square, so drawing a
		// 32px bucket into one put a small stray icon in its top-left corner rather than
		// marking anything. Outlining the panel is also just the clearer answer: the whole
		// thing is the click target.
		Rectangle bounds = widget.getBounds();
		graphics.setColor(ColorUtil.colorWithAlpha(colour, SLOT_FILL_ALPHA));
		graphics.fill(bounds);
		graphics.setColor(colour);
		graphics.setStroke(new BasicStroke(2f));
		graphics.draw(bounds);
	}

	private void drawItemHighlight(Graphics2D graphics, Rectangle bounds, int itemId,
		int quantity, Color colour)
	{
		BufferedImage outline = itemManager.getItemOutline(itemId, quantity, colour);
		graphics.drawImage(outline, (int) bounds.getX(), (int) bounds.getY(), null);
		graphics.drawImage(
			ImageUtil.fillImage(itemManager.getImage(itemId, quantity, false),
				ColorUtil.colorWithAlpha(colour, ITEM_FILL_ALPHA)),
			(int) bounds.getX(), (int) bounds.getY(), null);
	}
}
