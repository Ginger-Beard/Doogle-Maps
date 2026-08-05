package com.dooglemaps.guide;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmingTool;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
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
@Slf4j
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
	 * it stores rather than a list of items.
	 *
	 * <p>Built from {@link FarmingTool}, which already has to carry these slot ids for the
	 * loadout to read his store. Keeping a second hand-written copy here worked right up until
	 * one of them gained a row: the withdraw-a-tool step would have highlighted him and then
	 * silently failed to say which slot, which is the shape of the bug this overlay just had.
	 */
	private static final Map<Integer, Integer> LEPRECHAUN_SLOTS = new HashMap<>();
	private static final Map<Integer, Integer> LEPRECHAUN_SIDE_SLOTS = new HashMap<>();

	static
	{
		for (FarmingTool tool : FarmingTool.values())
		{
			LEPRECHAUN_SLOTS.put(tool.getItemID(), tool.getStoreSlot());
			LEPRECHAUN_SIDE_SLOTS.put(tool.getItemID(), tool.getSideStoreSlot());
		}
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

		Color colour = config.guideHighlightColour();

		GuideStep step = tracker.getCurrentStep();
		if (step == null)
		{
			// Nothing to do at a patch means either travelling or nothing at all. Travelling has
			// its own item — the teleport — and it lives in the same three places a step's item
			// might, so it is highlighted the same way rather than by a parallel mechanism.
			highlightTravelItem(graphics, colour);
			return null;
		}

		if (!step.hasItem())
		{
			return null;
		}

		// Where the item *is*, not where the click happens. Those are different questions and
		// treating them as one meant that noting a full inventory highlighted nothing at all:
		// the step is at the leprechaun, so his store was searched for a watermelon, which has no
		// slot in it. The crop was in the pack the entire time, which is why he was being visited.
		//
		// Still one or the other, never both, and that part was right: withdrawing compost and
		// applying it name the same bucket, so keying off the item id alone left his slot lit
		// after the withdrawal was done.
		if (step.itemIsInStore())
		{
			highlightInLeprechaunStore(graphics, step.getItemId(), colour);
		}
		else
		{
			highlightInInventory(graphics, step.getItemId(), colour);
		}
		return null;
	}

	/**
	 * Marks the way to the next stop, wherever the player is looking when they go to travel.
	 *
	 * <p>Everything that applies is marked, rather than the first thing that does. An earlier
	 * version stopped as soon as it found an open menu, on the reasoning that a menu is what the
	 * player is looking at — but "is this interface open" turned out to be a question that can
	 * answer yes when nothing is visible, and the short-circuit then swallowed the inventory
	 * highlight entirely. The teleport tab stopped being outlined at all.
	 *
	 * <p>Marking both costs nothing: when a menu really is open it covers the inventory anyway, so
	 * the extra highlight is not visible, and when it is not open nothing is drawn for it. The
	 * version that cannot fail silently is worth more than the tidy one.
	 */
	private void highlightTravelItem(Graphics2D graphics, Color colour)
	{
		TravelHint hint = tracker.getStatus().getTravelHint();
		if (hint == null)
		{
			return;
		}

		highlightDestinationRow(graphics, hint.getDestination(), colour);

		// The category button is the *previous* screen once its menu is open. Marking both left
		// the whole amulet lit up next to the row you actually want, which reads as the plugin
		// pointing at the jewellery rather than at the destination.
		if (matchingRows(hint.getDestination()).isEmpty())
		{
			highlightJewelleryCategory(graphics, hint.getDestination(), colour);
		}

		// In the bank it is a withdrawal, marked there in the withdraw colour by
		// BankHighlightOverlay rather than here — this would draw a second, differently coloured
		// marker on the same slot.
		if (hint.hasItem() && hint.getWhere() == TravelHint.Where.CARRIED)
		{
			highlightInInventory(graphics, hint.getItemId(), colour);
		}
	}

	/**
	 * Outlines the single row naming the destination, in whichever list is open.
	 *
	 * <p>Reported from play: inside a jewellery box the whole panel was being outlined rather than
	 * the "J: Farming Guild" line, which is the one thing you actually need to find. The box opens
	 * a <b>lettered option menu</b> — a different interface from the category buttons — so marking
	 * the category was marking the wrong thing once you were past it.
	 *
	 * <p>Matched on the row's own text, which is the only thing available: which destinations a
	 * player has attuned or unlocked varies per account, so there is no fixed slot to look up the
	 * way the leprechaun's store has.
	 */
	private void highlightDestinationRow(Graphics2D graphics, String destination, Color colour)
	{
		for (Widget row : matchingRows(destination))
		{
			outline(graphics, row.getBounds(), colour);
		}
	}

	/**
	 * Rows naming the destination, rescanned once a tick.
	 *
	 * <p>Cached because the search is expensive and render is per frame: it walks up to six levels
	 * of children across five candidate interfaces, and doing that fifty times a second was enough
	 * to be felt as lag with a jewellery box open. Once a tick is plenty — a menu does not
	 * reshuffle between frames.
	 *
	 * <p>Same fix, same reason, as the patch scan in {@code GuideOverlay}. Worth noting that this
	 * is the second time a per-frame widget walk has had to be pulled back to per-tick: it is the
	 * default mistake in an overlay, because render is where the drawing goes and the searching
	 * ends up there with it.
	 */
	private java.util.List<Widget> matchingRows(String destination)
	{
		int tick = client.getTickCount();
		if (tick == scannedRowTick && destination.equals(scannedRowFor))
		{
			return scannedRows;
		}

		scannedRowTick = tick;
		scannedRowFor = destination;
		scannedRows = new java.util.ArrayList<>();

		java.util.List<String> seen = new java.util.ArrayList<>();
		for (int listId : HouseTeleports.DESTINATION_LISTS)
		{
			Widget list = client.getWidget(listId);
			if (list == null || list.isHidden())
			{
				continue;
			}

			for (Widget row : descendants(list, HouseTeleports.MAX_WIDGET_DEPTH))
			{
				String text = row.getText();
				if (text == null || text.trim().isEmpty())
				{
					continue;
				}

				seen.add(text);
				if (HouseTeleports.namesTheSamePlace(text, destination))
				{
					scannedRows.add(row);
				}
			}
		}

		if (!seen.isEmpty() && scannedRows.isEmpty())
		{
			noteUnmatched(destination, seen);
		}
		return scannedRows;
	}

	private java.util.List<Widget> scannedRows = new java.util.ArrayList<>();
	private String scannedRowFor = "";
	private int scannedRowTick = -1;

	/** Whether a destination menu is open, so the category button can stop competing with it. */
	private boolean destinationMenuOpen()
	{
		return !matchingRows(scannedRowFor).isEmpty();
	}

	/** The destination a miss was last reported for, so it is said once rather than every frame. */
	private String loggedUnmatchedFor;

	/**
	 * Says which rows were on screen when none of them matched.
	 *
	 * <p>The matching is loose on purpose and still cannot cover every case — the nexus calls the
	 * Troll Stronghold patch "Trollheim", and there is no way to know what else diverges without
	 * seeing it. Guessing at the vocabulary is what produced that alias in the first place; this
	 * makes the game announce the rest, in the same spirit as the Geomancy probe and the harvest
	 * log's storage message.
	 *
	 * <p>One line per destination, so it cannot become noise.
	 */
	private void noteUnmatched(String destination, java.util.List<String> seen)
	{
		if (destination.equals(loggedUnmatchedFor))
		{
			return;
		}
		loggedUnmatchedFor = destination;

		log.info("Nothing on this teleport menu matched \"{}\". Rows on screen: {}. If one of "
			+ "those is the right destination, it needs an alias.", destination, seen);
	}

	/**
	 * Outlines the jewellery box category holding the destination.
	 *
	 * <p>Still worth marking, but it is the <i>first</i> screen rather than the last: the box
	 * opens on six named buttons, and knowing to press Skills rather than hunting through all of
	 * them is most of the help. Once past it,
	 * {@link #highlightDestinationRow} marks the actual line.
	 */
	private void highlightJewelleryCategory(Graphics2D graphics, String destination, Color colour)
	{
		Widget frame = client.getWidget(InterfaceID.PohJewelleryBox.FRAME);
		if (frame == null || frame.isHidden())
		{
			return;
		}

		for (HouseTeleports.JewelleryCategory category : HouseTeleports.JewelleryCategory.values())
		{
			if (!category.reaches(destination))
			{
				continue;
			}

			Widget button = client.getWidget(category.getWidgetId());
			if (button != null && !button.isHidden())
			{
				outline(graphics, button.getBounds(), colour);
			}
		}
	}

	/** The immediate children of a container, in all three of the forms a widget can hold them. */
	private static java.util.List<Widget> allChildren(Widget parent)
	{
		java.util.List<Widget> found = new java.util.ArrayList<>();
		for (Widget[] group : new Widget[][]{
			parent.getDynamicChildren(), parent.getStaticChildren(), parent.getNestedChildren()})
		{
			if (group != null)
			{
				java.util.Collections.addAll(found, group);
			}
		}
		return found;
	}

	/**
	 * Every visible descendant, to a bounded depth.
	 *
	 * <p>Recursive because a one-level walk was not enough and failed quietly: the nexus keeps its
	 * destination rows several containers down, so looking only at the immediate children of the
	 * list found nothing at all while the jewellery box's flatter menu worked. Depth-bounded so a
	 * malformed tree cannot turn a per-frame scan into a hang.
	 */
	private static java.util.List<Widget> descendants(Widget parent, int depth)
	{
		java.util.List<Widget> found = new java.util.ArrayList<>();
		if (depth <= 0)
		{
			return found;
		}

		for (Widget child : allChildren(parent))
		{
			if (child == null || child.isHidden())
			{
				continue;
			}
			found.add(child);
			found.addAll(descendants(child, depth - 1));
		}
		return found;
	}

	/** An outline round a widget, for the things that are panels rather than 32px item squares. */
	private void outline(Graphics2D graphics, Rectangle bounds, Color colour)
	{
		graphics.setColor(ColorUtil.colorWithAlpha(colour, SLOT_FILL_ALPHA));
		graphics.fill(bounds);
		graphics.setColor(colour);
		graphics.setStroke(new BasicStroke(2f));
		graphics.draw(bounds);
	}

	/**
	 * Every equipment slot, in both the places the game draws worn items.
	 *
	 * <p>{@code Wornitems} is the equipment tab in the side panel; {@code Equipment} is the
	 * full worn-equipment screen. Only one is open at a time, and which one is the player's
	 * business, so both are checked.
	 *
	 * <p>Listed by constant rather than found by walking a parent, because these are named
	 * static widgets rather than a dynamic list — there is no single container whose children
	 * are the slots.
	 */
	private static final int[] WORN_SLOTS = {
		InterfaceID.Wornitems.SLOT0, InterfaceID.Wornitems.SLOT1, InterfaceID.Wornitems.SLOT2,
		InterfaceID.Wornitems.SLOT3, InterfaceID.Wornitems.SLOT4, InterfaceID.Wornitems.SLOT5,
		InterfaceID.Wornitems.SLOT7, InterfaceID.Wornitems.SLOT9, InterfaceID.Wornitems.SLOT10,
		InterfaceID.Wornitems.SLOT12, InterfaceID.Wornitems.SLOT13,
		InterfaceID.Equipment.SLOT0, InterfaceID.Equipment.SLOT1, InterfaceID.Equipment.SLOT2,
		InterfaceID.Equipment.SLOT3, InterfaceID.Equipment.SLOT4, InterfaceID.Equipment.SLOT5,
		InterfaceID.Equipment.SLOT7, InterfaceID.Equipment.SLOT9, InterfaceID.Equipment.SLOT10,
		InterfaceID.Equipment.SLOT12, InterfaceID.Equipment.SLOT13,
	};

	/**
	 * Marks an item on the player, whether it is in the pack or worn.
	 *
	 * <p>Both, because the plugin already counts both when deciding whether you <i>have</i>
	 * something — {@code CarriedItems.has} sums the inventory and the equipment — so checking only
	 * the inventory here meant a worn item was confidently reported as owned and then silently
	 * failed to highlight. An Ardougne cloak round your neck or a Construction cape on your back
	 * is the ordinary way to carry a teleport, so this was the common case rather than an edge.
	 *
	 * <p>Same failure shape as the seed box and the noted watermelon: the named item was real, it
	 * just was not on the surface being searched.
	 */
	private void highlightInInventory(Graphics2D graphics, int itemId, Color colour)
	{
		Widget inventory = client.getWidget(InterfaceID.Inventory.ITEMS);
		if (inventory != null && !inventory.isHidden() && inventory.getDynamicChildren() != null)
		{
			for (Widget item : inventory.getDynamicChildren())
			{
				if (item != null && item.getItemId() == itemId)
				{
					drawItemHighlight(graphics, item.getBounds(), itemId,
						item.getItemQuantity(), colour);
				}
			}
		}

		highlightWorn(graphics, itemId, colour);
	}

	/** Marks a worn item in whichever equipment view is open. */
	private void highlightWorn(Graphics2D graphics, int itemId, Color colour)
	{
		for (int slot : WORN_SLOTS)
		{
			Widget widget = client.getWidget(slot);
			if (widget == null || widget.isHidden())
			{
				continue;
			}

			// The slot itself may hold the item, or wrap a child that does — the two equipment
			// views are not built the same way, and assuming either one would silently miss half
			// the cases.
			if (widget.getItemId() == itemId)
			{
				drawItemHighlight(graphics, widget.getBounds(), itemId,
					widget.getItemQuantity(), colour);
				continue;
			}

			for (Widget child : allChildren(widget))
			{
				if (child != null && !child.isHidden() && child.getItemId() == itemId)
				{
					drawItemHighlight(graphics, child.getBounds(), itemId,
						child.getItemQuantity(), colour);
				}
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
