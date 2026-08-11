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
	/** The wash over a marked item. Shared, so the two overlays cannot drift apart. */
	private static final int ITEM_FILL_ALPHA = ItemHighlight.FILL_ALPHA;

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

	/**
	 * The drop-when-convenient red for ambient empty buckets — deliberately not the guide
	 * colour, so it cannot be mistaken for the current step. The same red the sidebar uses
	 * for shortfalls, so the plugin keeps one meaning per hue.
	 */
	private static final Color DROP_BUCKET_RED = new Color(0xC4, 0x3B, 0x3B);

	/** Both forms of the seed box, the same pair {@code SeedCapture} watches. Not SEEDBOX —
	 * that gameval name is the Seed pack, and the packs lit up. See SeedCapture.isSeedBox. */
	private static final int[] SEED_BOX_IDS = {
		net.runelite.api.gameval.ItemID.SEED_BOX,
		net.runelite.api.gameval.ItemID.SEED_BOX_OPEN,
	};

	private final Client client;
	private final GuideTracker tracker;
	private final DoogleMapsConfig config;
	private final ItemManager itemManager;
	private final CarriedItems carried;
	private final com.dooglemaps.state.SeedInventoryStore seeds;

	@Inject
	GuideInventoryOverlay(Client client, GuideTracker tracker, DoogleMapsConfig config,
		ItemManager itemManager, CarriedItems carried,
		com.dooglemaps.state.SeedInventoryStore seeds)
	{
		this.client = client;
		this.tracker = tracker;
		this.config = config;
		this.itemManager = itemManager;
		this.carried = carried;
		this.seeds = seeds;

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

		// With "drop empty buckets" on, the buckets are ambient work rather than a step:
		// always lit while a run is under way, Drop always their left-click (GuideMenuSwap),
		// and no place in the step list. Drawn before the step handling so a bucket stays
		// marked whatever else the guide is saying.
		//
		// In its own red, not the guide colour. Painted like the step's item it READ as the
		// step — the required next click — when it is the one highlight in the plugin that
		// means "whenever you like". Asked from play: a non-required look for a non-required
		// action. The wash is already transparent (ItemHighlight.FILL_ALPHA); only the hue
		// changes.
		if (config.dropEmptyBuckets() && tracker.getStatus().isRunning())
		{
			highlightInInventory(graphics, net.runelite.api.gameval.ItemID.BUCKET_EMPTY,
				DROP_BUCKET_RED);
		}

		GuideStep step = tracker.getCurrentStep();
		if (step == null)
		{
			// Nothing to do at a patch means either travelling or nothing at all. Travelling has
			// its own item — the teleport — and it lives in the same three places a step's item
			// might, so it is highlighted the same way rather than by a parallel mechanism.
			highlightTravelItem(graphics, colour);
			return null;
		}

		// A payment's dialogue is part of the step. The farmer asks which patch and then for a
		// yes, and neither line was marked anywhere — the gardener was outlined, the fruit was
		// outlined, and the two clicks between them were unlit. Reported from play, on a
		// contract tree. Drawn alongside the item highlight below, not instead of it.
		if (step.getAction() == GuideAction.PAY_FARMER)
		{
			highlightPayOptions(graphics, colour);
		}

		// The hand-in's dialogue rows too, and for the same reason the payment got them: Jane
		// is outlined, the produce is outlined, and the clicks between them were unlit.
		if (step.getAction() == GuideAction.HAND_IN_CONTRACT)
		{
			highlightContractOptions(graphics, colour);
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
			// Until his interface is open there is no slot to mark, and nothing else is marked
			// in its place. This used to fall back to lighting the item in the pack so a bucket
			// return showed *something* before the store opened — but a lit bucket in the
			// inventory reads as "click this here", which is exactly the wrong instruction when
			// the click is on him. Reported from play, at Prifddinas. The leprechaun himself is
			// outlined by GuideOverlay, and that is the whole of the "click him first" cue.
			highlightInLeprechaunStore(graphics, step.getItemId(), colour,
				step.itemIsOnYourSideOfTheStore());
		}
		else
		{
			highlightInInventory(graphics, step.getItemId(), colour,
				step.getAction() == GuideAction.PAY_FARMER);

			// The box, when it is where the planting's seeds actually are. A plant step names
			// the seed, the seed is in the box, and nothing in the pack lit up — the player
			// stood at the patch with the instruction pointing at an item they could not see.
			// Re-added by request as a highlight only: no step, no patch outline, and the
			// left-click swap (GuideMenuSwap, its own toggle) is untouched — it reads the box,
			// not this. Drawn alongside the seed's own highlight, not instead: with a partial
			// stack loose in the pack, both are true and both light up.
			if (step.getAction() == GuideAction.PLANT && boxHoldsNeededSeeds(step.getItemId()))
			{
				for (int boxId : SEED_BOX_IDS)
				{
					highlightInInventory(graphics, boxId, colour);
				}
			}
		}
		return null;
	}

	/**
	 * Whether the current planting still needs seeds that are sitting in the seed box.
	 *
	 * <p>Both halves matter: the box holding some, and the pack holding too few — a pack
	 * already carrying a patch's worth needs no box trip, however full the box is. Tree
	 * saplings can never be boxed, so this is quietly false for every tree step.
	 */
	private boolean boxHoldsNeededSeeds(int plantedItemId)
	{
		com.dooglemaps.data.Seed seed = com.dooglemaps.data.Seed.forItemId(plantedItemId);
		if (seed == null)
		{
			return false;
		}
		return seeds.getPlantable(seed, com.dooglemaps.state.SeedSource.SEED_BOX) > 0
			&& carried.getInventoryCount(plantedItemId) < seed.getSeedsPerPatch();
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

		// The route travels by spell: the click is in the spellbook, or on the magic tab
		// stone that opens it. Nothing in the pack to mark either way.
		if (hint.isSpell())
		{
			highlightSpell(graphics, hint.getSpellComponent(), colour);
			return;
		}

		// In the bank it is a withdrawal, marked there in the withdraw colour by
		// BankHighlightOverlay rather than here — this would draw a second, differently coloured
		// marker on the same slot.
		if (hint.hasItem() && hint.getWhere() == TravelHint.Where.CARRIED)
		{
			highlightInInventory(graphics, hint.getItemId(), colour);
			highlightTabStoneFor(graphics, hint.getItemId(), colour);
		}
	}

	/**
	 * The side-stone tabs that reveal each place a travel click can live, across the three
	 * interface layouts. Only one layout's widgets exist at a time, so all three are listed
	 * and the hidden ones cost nothing.
	 *
	 * <p>STONE3/4/6 are inventory, worn equipment and magic — verified against the legacy
	 * {@code ComponentID} tab constants, whose values these are.
	 */
	private static final int[] INVENTORY_STONES = {
		InterfaceID.Toplevel.STONE3,
		InterfaceID.ToplevelOsrsStretch.STONE3,
		InterfaceID.ToplevelPreEoc.STONE3,
	};
	private static final int[] EQUIPMENT_STONES = {
		InterfaceID.Toplevel.STONE4,
		InterfaceID.ToplevelOsrsStretch.STONE4,
		InterfaceID.ToplevelPreEoc.STONE4,
	};
	private static final int[] MAGIC_STONES = {
		InterfaceID.Toplevel.STONE6,
		InterfaceID.ToplevelOsrsStretch.STONE6,
		InterfaceID.ToplevelPreEoc.STONE6,
	};

	/**
	 * Marks a teleport spell: the spell itself when it is on screen, the magic tab when not.
	 *
	 * <p>Two steps for the price of one visibility test. Every spell is a child of the one
	 * spellbook interface and the game hides the books that are not current, so the spell's
	 * widget is visible exactly when it can be clicked — and when it is not, the magic stone
	 * is the click that gets there. (On the wrong spellbook the stone stays lit with the tab
	 * already open; there is genuinely nothing better to point at, since swapping books is a
	 * trip this overlay cannot make for anyone.)
	 */
	private void highlightSpell(Graphics2D graphics, int component, Color colour)
	{
		Widget spell = client.getWidget(component);
		if (spell != null && !spell.isHidden())
		{
			outline(graphics, spell.getBounds(), colour);
			return;
		}
		outlineStones(graphics, MAGIC_STONES, colour);
	}

	/**
	 * Marks the tab stone that reveals a carried travel item, when its panel is closed.
	 *
	 * <p>The item highlight only lands on a visible widget, so with the inventory covered or
	 * another tab open the instruction used to vanish entirely — the panel named the teleport
	 * and nothing on screen showed the way to it. The stone is the missing first click:
	 * inventory stone for something in the pack, worn-equipment stone for something being
	 * worn, and nothing at all once the right panel is already open, because then the item
	 * itself is lit.
	 */
	private void highlightTabStoneFor(Graphics2D graphics, int itemId, Color colour)
	{
		if (carried.getInventoryCount(itemId) > 0)
		{
			Widget inventory = client.getWidget(InterfaceID.Inventory.ITEMS);
			if (inventory == null || inventory.isHidden())
			{
				outlineStones(graphics, INVENTORY_STONES, colour);
			}
			return;
		}

		// Not in the pack but carried — worn, then. Equipped teleports are clicked on the
		// equipment tab, so that stone is the way to them.
		if (carried.has(itemId) && !wornViewOpen())
		{
			outlineStones(graphics, EQUIPMENT_STONES, colour);
		}
	}

	/** Whether either worn-equipment view is on screen. */
	private boolean wornViewOpen()
	{
		for (int slot : WORN_SLOTS)
		{
			Widget widget = client.getWidget(slot);
			if (widget != null && !widget.isHidden())
			{
				return true;
			}
		}
		return false;
	}

	/** Outlines whichever layout's copy of a tab stone is actually on screen. */
	private void outlineStones(Graphics2D graphics, int[] stones, Color colour)
	{
		for (int stone : stones)
		{
			Widget widget = client.getWidget(stone);
			if (widget != null && !widget.isHidden())
			{
				outline(graphics, widget.getBounds(), colour);
			}
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
		for (Rectangle row : matchingRows(destination))
		{
			outline(graphics, row, colour);
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
	private java.util.List<Rectangle> matchingRows(String destination)
	{
		int tick = client.getTickCount();
		if (tick != scannedRowTick || !destination.equals(scannedRowFor))
		{
			scannedRowTick = tick;
			scannedRowFor = destination;
			scannedRows = scanRows(destination);
		}

		// The search is once a tick; the MEASURING is every frame. These lists scroll, and a
		// rectangle captured at scan time trailed the moving row by up to 600ms — reported
		// from play as the nexus highlight lagging the scroll. Finding the row is the
		// expensive half; measuring the one or two matched widgets is not, so the box now
		// rides the widget's live bounds.
		//
		// Clipped to the list each row was found in, because a scrolling list draws only a
		// window of itself while the rows keep their full laid-out bounds — so the outline for
		// a row near the edge spilled past the nexus's frame onto the interface around it.
		// Reported from play, as cosmetic, which is exactly what an unclipped rectangle looks
		// like. A row reachable from two scanned containers — the nexus universe and its rows
		// layer overlap by construction — keeps the tightest rectangle it got.
		Map<Widget, Rectangle> merged = new HashMap<>();
		for (MatchedRow match : scannedRows)
		{
			if (match.row.isHidden())
			{
				continue;
			}
			Rectangle clipped = textBounds(match.row).intersection(match.list.getBounds());
			Rectangle already = merged.get(match.row);
			merged.put(match.row, already == null ? clipped : already.intersection(clipped));
		}

		java.util.List<Rectangle> rows = new java.util.ArrayList<>();
		for (Rectangle bounds : merged.values())
		{
			if (!bounds.isEmpty())
			{
				rows.add(bounds);
			}
		}
		return rows;
	}

	/**
	 * Finds the widgets whose rows name the destination, in whichever list is open.
	 *
	 * <p>Direct matches and aliased ones are kept apart, and the direct rows win when any
	 * exist. The alias is a stand-in for the row that lands at the destination, not a
	 * second answer: a nexus can hold both "Troll Stronghold" (beside the patch) and
	 * "Trollheim" (up the mountain), and lighting both told the player the plugin could
	 * not choose. Only when no direct row exists does the aliased one carry the highlight.
	 */
	private java.util.List<MatchedRow> scanRows(String destination)
	{
		java.util.List<MatchedRow> found = new java.util.ArrayList<>();
		java.util.List<MatchedRow> aliased = new java.util.ArrayList<>();
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
				boolean direct = HouseTeleports.namesTheSamePlaceDirectly(text, destination);
				if (direct || HouseTeleports.namesTheSamePlace(text, destination))
				{
					(direct ? found : aliased).add(new MatchedRow(row, list));
				}
			}
		}

		if (found.isEmpty())
		{
			found = aliased;
		}
		if (!seen.isEmpty() && found.isEmpty())
		{
			noteUnmatched(destination, seen);
		}
		return found;
	}

	/** A destination row and the list it was found in, which its outline is clipped to. */
	private static final class MatchedRow
	{
		final Widget row;
		final Widget list;

		MatchedRow(Widget row, Widget list)
		{
			this.row = row;
			this.list = list;
		}
	}

	private java.util.List<MatchedRow> scannedRows = new java.util.ArrayList<>();
	private String scannedRowFor = "";
	private int scannedRowTick = -1;

	/** Room past the last letter, so the box does not sit against the glyphs. */
	private static final int ROW_TEXT_PADDING = 3;

	/**
	 * A row's outline, no wider than its words.
	 *
	 * <p>A destination row's widget is as wide as the list lays it out — often the full
	 * interface — so outlining its bounds drew a box sailing far past "Weiss" into empty
	 * panel. Reported from play as the highlight pushing past the interface. The text is
	 * what the player is looking for, so the box now ends just after the last letter,
	 * measured with the row's own game font and placed the way the widget aligns its text.
	 *
	 * <p>Falls back to the full bounds when there is nothing to measure with — a missing
	 * font, or text wider than the row, where clamping would cut letters off.
	 */
	static Rectangle textBounds(Widget row)
	{
		Rectangle bounds = row.getBounds();
		net.runelite.api.FontTypeFace font = row.getFont();
		String text = net.runelite.client.util.Text.removeTags(row.getText());
		if (font == null || text == null || text.isEmpty())
		{
			return bounds;
		}

		int width = font.getTextWidth(text) + ROW_TEXT_PADDING * 2;
		if (width >= bounds.width)
		{
			return bounds;
		}

		int x;
		switch (row.getXTextAlignment())
		{
			case net.runelite.api.widgets.WidgetTextAlignment.CENTER:
				x = bounds.x + (bounds.width - width) / 2;
				break;
			case net.runelite.api.widgets.WidgetTextAlignment.RIGHT:
				x = bounds.x + bounds.width - width;
				break;
			default:
				x = bounds.x;
				break;
		}
		return new Rectangle(x, bounds.y, width, bounds.height);
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
		highlightInInventory(graphics, itemId, colour, false);
	}

	/**
	 * As above, optionally counting the item's bank-note form as the item.
	 *
	 * <p>Opt-in per step, because the two callers mean opposite things by a note. Protection
	 * payments travel noted — the farmer takes them noted, the loadout counts them noted — so
	 * the pay step names an item whose only presence in the pack may be its note, and the note
	 * is the thing to click. A noting step is the reverse: the loose pile is what gets handed
	 * to the leprechaun, and lighting an already-noted stack of the same crop beside it said
	 * "note these again". Reported from play.
	 */
	private void highlightInInventory(Graphics2D graphics, int itemId, Color colour,
		boolean includeNoted)
	{
		Widget inventory = client.getWidget(InterfaceID.Inventory.ITEMS);
		if (inventory != null && !inventory.isHidden() && inventory.getDynamicChildren() != null)
		{
			for (Widget item : inventory.getDynamicChildren())
			{
				if (item != null
					&& (item.getItemId() == itemId
						|| includeNoted && isNotedFormOf(item.getItemId(), itemId)))
				{
					drawItemHighlight(graphics, item.getBounds(), itemId,
						item.getItemQuantity(), colour);
				}
			}
		}

		highlightWorn(graphics, itemId, colour);
	}

	/** Whether the carried item is the bank-note form of the wanted one. */
	private boolean isNotedFormOf(int carriedId, int wantedId)
	{
		if (carriedId <= 0)
		{
			return false;
		}
		net.runelite.api.ItemComposition composition = itemManager.getItemComposition(carriedId);
		return composition.getNote() != -1 && composition.getLinkedNoteId() == wantedId;
	}

	/**
	 * Marks the farmer's payment dialogue: the "Pay (north)"-style line that picks a patch, the
	 * patch-name lines a multi-patch farmer offers instead, and the "Yes" that confirms.
	 *
	 * <p>Matched the same way {@code ProtectionCapture} recognises the selection afterwards, so
	 * what is lit and what is recorded cannot drift apart. Only ever drawn while the current
	 * step is {@code PAY_FARMER}, so a "Yes" in some unrelated dialogue is not at risk — there
	 * is no payment conversation it could belong to.
	 */
	private void highlightPayOptions(Graphics2D graphics, Color colour)
	{
		Widget list = client.getWidget(InterfaceID.Chatmenu.OPTIONS);
		if (list == null || list.isHidden() || list.getDynamicChildren() == null)
		{
			return;
		}

		for (Widget row : list.getDynamicChildren())
		{
			if (row == null || row.getText() == null)
			{
				continue;
			}

			String text = net.runelite.client.util.Text.removeTags(row.getText()).trim();
			if (text.startsWith("Pay") || text.startsWith("Yes")
				|| text.contains("Patch") || text.contains("allotment"))
			{
				Rectangle bounds = textBounds(row).intersection(list.getBounds());
				if (!bounds.isEmpty())
				{
					outline(graphics, bounds, colour);
				}
			}
		}
	}

	/**
	 * Marks the hand-in conversation's rows at Guildmaster Jane.
	 *
	 * <p>Same arrangement as {@link #highlightPayOptions}: only ever drawn while the current
	 * step is {@code HAND_IN_CONTRACT}, so the step is the gate and a similar row in some
	 * unrelated dialogue is never at risk.
	 *
	 * <p>A denylist rather than an allowlist, deliberately. The pay dialogue's exact lines are
	 * pinned by {@code ProtectionCapture}, so the pay highlighter can name them; the hand-in
	 * conversation's option strings are not recorded anywhere in this codebase, and an
	 * allowlist built from guesses fails silently — the player sees nothing lit, which is the
	 * failure that prompted this. So every option is marked except the header row and the
	 * obvious declines; when the real wording has been captured in play, tighten this to a
	 * prefix list and record the strings beside {@code ContractCapture}'s patterns.
	 */
	private void highlightContractOptions(Graphics2D graphics, Color colour)
	{
		Widget list = client.getWidget(InterfaceID.Chatmenu.OPTIONS);
		if (list == null || list.isHidden() || list.getDynamicChildren() == null)
		{
			return;
		}

		for (Widget row : list.getDynamicChildren())
		{
			if (row == null || row.getText() == null)
			{
				continue;
			}

			String text = net.runelite.client.util.Text.removeTags(row.getText()).trim();
			// The header ("Select an Option") and the ways of saying no.
			if (text.isEmpty() || text.startsWith("Select an Option")
				|| text.startsWith("No") || text.startsWith("Nothing")
				|| text.startsWith("I'll come back"))
			{
				continue;
			}

			Rectangle bounds = textBounds(row).intersection(list.getBounds());
			if (!bounds.isEmpty())
			{
				outline(graphics, bounds, colour);
			}
		}
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
	private boolean highlightInLeprechaunStore(Graphics2D graphics, int itemId, Color colour,
		boolean yourSide)
	{
		// Whose column to point at, not which layout happens to be up.
		//
		// This used to try his pane and fall back to the side one "because only one is ever open",
		// which is simply not true: opening his store shows his contents in one pane *and* a
		// second pane over your inventory holding yours. His slot is therefore always present, the
		// fallback never fired, and a bucket return pointed at the thousand he already has.
		Integer preferred = (yourSide ? LEPRECHAUN_SIDE_SLOTS : LEPRECHAUN_SLOTS).get(itemId);
		Integer other = (yourSide ? LEPRECHAUN_SLOTS : LEPRECHAUN_SIDE_SLOTS).get(itemId);

		Widget widget = preferred == null ? null : client.getWidget(preferred);
		if (widget == null || widget.isHidden())
		{
			// Only when the right pane genuinely is not there — a layout we have not seen, rather
			// than a guess about which is open.
			widget = other == null ? null : client.getWidget(other);
		}

		if (widget == null || widget.isHidden())
		{
			return false;
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
		return true;
	}

	private void drawItemHighlight(Graphics2D graphics, Rectangle bounds, int itemId,
		int quantity, Color colour)
	{
		// Shared with the bank overlay; see ItemHighlight for why the two must not diverge.
		ItemHighlight.draw(graphics, itemManager, bounds, itemId, quantity, colour);
	}
}
