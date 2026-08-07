package com.dooglemaps.guide;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.route.PatchLocationStore;
import com.dooglemaps.state.PlayerHouse;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;
import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.Scene;
import net.runelite.api.TileObject;
import net.runelite.api.Tile;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.client.util.ColorUtil;

/**
 * Lights up the thing guided mode is asking you to click.
 *
 * <p>Follows Quest Helper's vocabulary deliberately, rather than inventing a second one for
 * the same job: an outline or click box on the target object, the same on the tool leprechaun,
 * and a filled outline on the inventory item you are meant to use. Anyone who has followed a
 * quest already knows how to read this, and a farming plugin that highlighted differently
 * would just look like a plugin that got it wrong.
 *
 * <p>World objects only — the patch and the leprechaun. The inventory item lives in
 * {@link GuideInventoryOverlay}, because widgets draw over this layer; see the note there.
 *
 * <p>Highlighting only. Nothing here clicks anything, and nothing changes a menu — the spec's
 * read-only rule is not negotiable, and being able to see what to press is the whole feature.
 */
@Slf4j
public class GuideOverlay extends Overlay
{
	/** Alpha for a filled click box, matching Quest Helper's. */
	private static final int FILL_ALPHA = 20;

	// There was an ITEM_FILL_ALPHA here, unused: this overlay draws world objects, not items.
	// It was the third copy of one constant, and the other two were the ones that mattered — see
	// ItemHighlight, which now owns it for both overlays that do mark items.

	private final Client client;
	private final GuideTracker tracker;
	private final DoogleMapsConfig config;
	private final ModelOutlineRenderer outlineRenderer;
	private final PatchLocationStore locations;
	private final PlayerHouse house;

	@Inject
	private GuideOverlay(Client client, GuideTracker tracker, DoogleMapsConfig config,
		ModelOutlineRenderer outlineRenderer, PatchLocationStore locations, PlayerHouse house)
	{
		this.house = house;
		this.locations = locations;
		this.client = client;
		this.tracker = tracker;
		this.config = config;
		this.outlineRenderer = outlineRenderer;

		setPosition(OverlayPosition.DYNAMIC);
		// Above the scene but below the interfaces, so an outline never draws over the bank.
		setLayer(OverlayLayer.ABOVE_SCENE);
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
			// No step means one of two quite different things, and only one of them is "nothing
			// to do here".
			//
			// The supply leg is a stop with an instruction — "collect your supplies" — and the
			// panel has been saying so all along while the scene stayed dark. The one thing you
			// actually have to click was the one thing never marked.
			if (tracker.getStatus().isAtBankLeg())
			{
				highlightSupplyPoints(graphics, colour);
			}

			// Otherwise, travelling. The teleport furniture in a player's house is the one thing
			// worth lighting up out here — everything else about a journey is Shortest Path's job.
			//
			// This was the hole in the travel highlighting: the nexus and the jewellery box were
			// marked once their *interface* was open, which is no help at all to someone who has
			// just teleported in and is looking at the room. You have to click the thing before
			// there is a menu to highlight.
			highlightHouseTeleports(graphics, colour);
			return null;
		}

		if (step.highlightsPatch())
		{
			highlightPatch(graphics, step.getPatch(), colour);
		}
		if (step.hasNpc())
		{
			// By id when the step names one. This used to fall through to the leprechaun search
			// for every step with an NPC on it, which meant paying a farmer outlined the
			// leprechaun instead — and Guildmaster Jane, who is nowhere near one, would have been
			// outlined as nobody at all.
			highlightNpcById(graphics, colour, step.getNpcId());
		}
		else if (step.isAtLeprechaun())
		{
			highlightLeprechaun(graphics, colour);
		}
		return null;
	}

	/**
	 * Outlines the patch object on the ground.
	 *
	 * <p>Found by varbit rather than by object id: a farming patch's id changes with what is
	 * growing in it — a herb patch is a different object empty, growing and ready — but the
	 * varbit it reports is the same one the plugin keys everything else on.
	 */
	private void highlightPatch(Graphics2D graphics, FarmPatch patch, Color colour)
	{
		DoogleMapsConfig.GuideHighlightStyle style = config.guideHighlightStyle();
		if (style == DoogleMapsConfig.GuideHighlightStyle.NONE)
		{
			return;
		}

		List<TileObject> objects = findPatchObjects(patch);
		if (objects.isEmpty())
		{
			// Nothing in the scene carries this patch's varbit. Rather than show nothing —
			// which is indistinguishable from the plugin being broken — fall back to the tile
			// the patch was learned at. Less pretty than an outline, never wrong about where
			// the patch is, and it cannot silently disappear.
			markTile(graphics, patch, colour);
			return;
		}

		// The ground the patch occupies, always. A model outline is invisible on an empty patch —
		// bare soil is a flat decal with no silhouette to trace — which is why crops highlighted
		// and cleared patches did not, with no "nothing found" warning because the objects were
		// there all along.
		//
		// Drawn as ONE shape rather than one per object. Outlining each tile separately drew a
		// cyan grid over the allotment — every internal edge stroked twice, once from each side —
		// which read as a chessboard laid on the patch rather than as a patch that was lit up.
		// Merging first means the only line drawn is the outside edge, which is the only edge
		// that means anything: it is where the patch stops.
		fillAndOutline(graphics, mergedTiles(objects), colour);

		if (style == DoogleMapsConfig.GuideHighlightStyle.OUTLINE)
		{
			// Then the models on top, where there is one to trace. Per object here, deliberately:
			// this traces the crops themselves, and the renderer takes one object at a time.
			for (TileObject object : objects)
			{
				outlineRenderer.drawOutline(object, config.guideOutlineThickness(), colour,
					config.guideOutlineFeathering());
			}
		}
		else
		{
			// Clickboxes merged for the same reason as the tiles.
			Area boxes = new Area();
			for (TileObject object : objects)
			{
				Shape clickbox = object.getClickbox();
				if (clickbox != null)
				{
					boxes.add(new Area(clickbox));
				}
			}
			fillAndOutline(graphics, boxes, colour);
		}
	}

	/**
	 * Every tile the patch covers, merged into one shape.
	 *
	 * <p>Adjacent tiles share their corner coordinates exactly — both are projected from the same
	 * scene geometry by the same call — so the union closes cleanly rather than leaving seams
	 * between them.
	 */
	private static Area mergedTiles(List<TileObject> objects)
	{
		Area merged = new Area();
		for (TileObject object : objects)
		{
			Polygon tile = object.getCanvasTilePoly();
			if (tile != null)
			{
				merged.add(new Area(tile));
			}
		}
		return merged;
	}

	/** Fills a shape and draws its outline, in the guide's colour. */
	private static void fillAndOutline(Graphics2D graphics, Area shape, Color colour)
	{
		if (shape.isEmpty())
		{
			return;
		}

		graphics.setColor(ColorUtil.colorWithAlpha(colour, FILL_ALPHA));
		graphics.fill(shape);
		graphics.setColor(colour);
		graphics.draw(shape);
	}

	/**
	 * Last scene scan: which patch was looked for, what was found, and when.
	 *
	 * <p>The scan is the expensive thing here — 104x104 tiles, every game object on each, an
	 * {@code getObjectDefinition} call apiece, which is tens of thousands of lookups. Render
	 * runs per <i>frame</i>; doing that fifty times a second was the difference between a free
	 * overlay and a visible stutter.
	 *
	 * <p>Once a tick is plenty: a patch does not move, and a tick is the rate at which anything
	 * in the game changes anyway. Keyed on the patch as well as the tick so that switching
	 * target re-scans immediately rather than pointing at the previous patch for up to 600ms.
	 *
	 * <p>Caching the {@code GameObject} itself for longer would also work — it is a live handle
	 * that keeps reporting its own position — but it would then have to be invalidated on every
	 * scene load, and a per-tick scan needs no such reasoning to be correct.
	 */
	private String scannedPatchKey;
	private List<TileObject> scannedObjects = Collections.emptyList();
	private int scannedTick = -1;

	private List<TileObject> findPatchObjects(FarmPatch patch)
	{
		int tick = client.getTickCount();
		if (tick == scannedTick && patch.getKey().equals(scannedPatchKey))
		{
			return scannedObjects;
		}

		scannedTick = tick;
		scannedPatchKey = patch.getKey();
		scannedObjects = scanForPatchObjects(patch);

		// An empty patch was reported as not highlighting, and there are two very different
		// reasons it might not: nothing in the scene carries its varbit, or something does and
		// the outline is invisible on flat soil. Guessing between them is how the melon fix
		// got made twice, so say which it is.
		if (scannedObjects.isEmpty() && !patch.getKey().equals(loggedMissKey))
		{
			loggedMissKey = patch.getKey();
			log.info("No scene object carries the varbit for {} - nothing to outline. If the "
				+ "patch is visible on screen, its object is not varbit-tagged in this state.",
				patch.getDisplayName());
		}
		return scannedObjects;
	}

	/** The last patch a miss was logged for, so it is said once rather than every tick. */
	private String loggedMissKey;

	/**
	 * Outlines the tile a patch was learned at.
	 *
	 * <p>The safety net for when the object scan finds nothing. Patch positions are learned by
	 * {@code PatchLocationCapture} from the objects that spawn there, so if this patch has ever
	 * been walked past its tile is known even when nothing in the current scene matches by
	 * varbit — and a marker in the right place beats a highlight that quietly fails to appear.
	 */
	private void markTile(Graphics2D graphics, FarmPatch patch, Color colour)
	{
		WorldPoint location = locations.getLocation(patch);
		if (location == null)
		{
			return;
		}

		LocalPoint local = LocalPoint.fromWorld(client.getTopLevelWorldView(), location);
		if (local == null)
		{
			return;
		}

		Polygon tile = Perspective.getCanvasTilePoly(client, local);
		if (tile != null)
		{
			OverlayUtil.renderPolygon(graphics, tile, colour,
				ColorUtil.colorWithAlpha(colour, FILL_ALPHA), graphics.getStroke());
		}
	}

	/**
	 * Every object making up the patch.
	 *
	 * <p><b>All of them, not the best one.</b> The previous attempt scored the matches and took
	 * the largest, on the assumption that a patch is one big object with decorative crops on
	 * top. That is not how the game draws it: an allotment is a scatter of one-tile crop
	 * objects, each carrying the patch's varbit, with no single large object to prefer. So
	 * "largest wins" just picked an arbitrary watermelon, exactly as before.
	 *
	 * <p>Marking every match instead lights up the whole patch, which is what was wanted in the
	 * first place — an allotment is big and you can click any part of it. It also handles the
	 * patches that <i>are</i> a single object, like Prifddinas's, with no special case: there is
	 * one match, so one outline.
	 */
	private List<TileObject> scanForPatchObjects(FarmPatch patch)
	{
		Scene scene = client.getTopLevelWorldView().getScene();
		Tile[][][] tiles = scene.getTiles();
		int plane = client.getTopLevelWorldView().getPlane();

		List<TileObject> found = new ArrayList<>();
		Set<Long> seen = new HashSet<>();

		for (Tile[] column : tiles[plane])
		{
			for (Tile tile : column)
			{
				if (tile == null)
				{
					continue;
				}
				// All four kinds, not just game objects. A crop standing up is a GameObject,
				// but bare soil is a GroundObject — which is why an emptied allotment silently
				// stopped highlighting the moment its melons were picked. They share the
				// TileObject interface, so nothing downstream cares which is which.
				for (GameObject object : tile.getGameObjects())
				{
					consider(object, patch, seen, found);
				}
				consider(tile.getGroundObject(), patch, seen, found);
				consider(tile.getDecorativeObject(), patch, seen, found);
				consider(tile.getWallObject(), patch, seen, found);
			}
		}
		return found;
	}

	/**
	 * Outlines the bank booths, chests and the seed vault while the run is collecting supplies.
	 *
	 * <h2>Only what the run is going to</h2>
	 *
	 * This marked every bank booth, chest and the vault unconditionally, which lit half the Farming
	 * Guild for a trip that wanted one container. It is now scoped to the planner's own answer —
	 * see {@link #marks}, which is where the interesting half of the rule lives and where a later
	 * attempt to narrow "both" down to one went wrong.
	 *
	 * <p>With nothing collected from either — the run knowing it needs a bank without yet knowing
	 * what for — banks are marked and the vault is not. The vault holds seeds and nothing else, so
	 * "we are not sure" is never a reason to point at it.
	 *
	 * <p>Matched on the object's own <b>actions and name</b> rather than on ids, the same way the
	 * house furniture and the leprechaun are. There are dozens of bank booths and chests across
	 * the game and they are added to constantly; anything you can click "Bank" on is a bank, which
	 * is a fact about the object rather than a list somebody has to maintain.
	 */
	private void highlightSupplyPoints(Graphics2D graphics, Color colour)
	{
		java.util.Set<com.dooglemaps.state.SeedSource> sources =
			tracker.getStatus().getSupplySources();

		for (TileObject object : scanForSupplyObjects())
		{
			if (!marks(sources, isSeedVault(object)))
			{
				continue;
			}

			Shape clickbox = object.getClickbox();
			if (clickbox != null)
			{
				OverlayUtil.renderPolygon(graphics, clickbox, colour,
					ColorUtil.colorWithAlpha(colour, FILL_ALPHA), graphics.getStroke());
			}

			if (config.guideHighlightStyle() == DoogleMapsConfig.GuideHighlightStyle.OUTLINE)
			{
				outlineRenderer.drawOutline(object, config.guideOutlineThickness(), colour,
					config.guideOutlineFeathering());
			}
		}
	}

	/** Supply objects found this tick, since the scene walk is the expensive part. */
	private List<TileObject> supplyObjects = new ArrayList<>();
	private int supplyScanTick = -1;

	private List<TileObject> scanForSupplyObjects()
	{
		int tick = client.getTickCount();
		if (tick == supplyScanTick)
		{
			return supplyObjects;
		}
		supplyScanTick = tick;

		List<TileObject> found = new ArrayList<>();
		Scene scene = client.getTopLevelWorldView().getScene();
		int plane = client.getTopLevelWorldView().getPlane();

		for (Tile[] column : scene.getTiles()[plane])
		{
			for (Tile tile : column)
			{
				if (tile == null)
				{
					continue;
				}
				for (GameObject object : tile.getGameObjects())
				{
					if (isSupplyPoint(object))
					{
						found.add(object);
					}
				}
				if (isSupplyPoint(tile.getWallObject()))
				{
					found.add(tile.getWallObject());
				}
				if (isSupplyPoint(tile.getDecorativeObject()))
				{
					found.add(tile.getDecorativeObject());
				}
			}
		}

		supplyObjects = found;
		return found;
	}

	/**
	 * Whether this object is somewhere the run can collect from.
	 *
	 * <p>An object whose id has impostors is asked for the impostor first — a bank booth's
	 * composition varies with what it is currently showing, and the base id carries neither the
	 * name nor the actions.
	 */
	private boolean isSupplyPoint(TileObject object)
	{
		if (object == null)
		{
			return false;
		}

		ObjectComposition composition = client.getObjectDefinition(object.getId());
		if (composition == null)
		{
			return false;
		}
		if (composition.getImpostorIds() != null && composition.getImpostor() != null)
		{
			composition = composition.getImpostor();
		}

		String name = composition.getName();
		if (name != null && name.toLowerCase().contains("seed vault"))
		{
			return true;
		}

		String[] actions = composition.getActions();
		if (actions == null)
		{
			return false;
		}
		for (String action : actions)
		{
			// "Bank" covers booths and chests. Deposit boxes are deliberately not included: they
			// only take things, and this leg is about getting things out.
			if ("Bank".equalsIgnoreCase(action) || "Collect".equalsIgnoreCase(action))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether a supply point of this kind is one the run is collecting from.
	 *
	 * <p>Separate from the drawing so the rule can be read and tested on its own. An empty set —
	 * the run knowing it wants a bank without knowing what for — means banks, because the vault
	 * holds seeds and nothing else.
	 *
	 * <h2>Both, when the trip needs both</h2>
	 *
	 * This was {@code isVault == sources.contains(SEED_VAULT)}: an exclusive rule, so the moment
	 * the vault was wanted every bank booth went dark. The justification was that the planner
	 * "routes to the vault or to the banks, never to both", and it was true when it was written.
	 *
	 * <p>It stopped being true. {@code RunPlanner.supplyTargetsFor} was deliberately changed to
	 * hand over <b>both</b>, because the two are separate errands and the leg does not finish until
	 * each is empty — and nothing here was changed with it. Shortest Path then did what it does
	 * with any target set and picked the cheapest to reach; in the Farming Guild that is the bank
	 * chest. So the line was drawn to a bank that was not outlined, while the vault was outlined
	 * and not routed to. Reported from play, and the exact disagreement the exclusive rule was
	 * introduced to prevent.
	 *
	 * <p>So the rule is now the honest one: mark what the run is actually collecting from. Two lit
	 * places is the correct answer to a trip that genuinely wants two containers, and it is what
	 * {@code LoadoutSummary} has said in words the whole time — one line for the bank, one for the
	 * vault, in either order.
	 */
	static boolean marks(java.util.Set<com.dooglemaps.state.SeedSource> sources, boolean isVault)
	{
		if (isVault)
		{
			return sources.contains(com.dooglemaps.state.SeedSource.SEED_VAULT);
		}
		return sources.isEmpty() || sources.contains(com.dooglemaps.state.SeedSource.BANK);
	}

	/**
	 * Whether this particular supply point is the seed vault rather than a bank.
	 *
	 * <p>Asked of objects {@link #isSupplyPoint} has already accepted, so it only has to tell the
	 * two apart rather than recognise either from scratch. Composition lookups are cheap but not
	 * free, and this runs per marked object per frame.
	 */
	private boolean isSeedVault(TileObject object)
	{
		ObjectComposition composition = client.getObjectDefinition(object.getId());
		if (composition == null)
		{
			return false;
		}
		if (composition.getImpostorIds() != null && composition.getImpostor() != null)
		{
			composition = composition.getImpostor();
		}

		String name = composition.getName();
		return name != null && name.toLowerCase().contains("seed vault");
	}

	/**
	 * Outlines the teleport furniture in a player-owned house.
	 *
	 * <p>Only while travelling, and only when there is somewhere to travel to — standing in your
	 * house between runs should light nothing up.
	 *
	 * <p>Matched by <b>name</b> rather than by object id, the same way the leprechaun is. A
	 * jewellery box comes in three tiers and a nexus can be built at three levels, each a
	 * different object; matching the word means the next tier added is covered without anyone
	 * noticing it needs to be. It also means this cannot claim a house has a nexus when it does
	 * not — an object that is not there cannot be found.
	 */
	private void highlightHouseTeleports(Graphics2D graphics, Color colour)
	{
		TravelHint hint = tracker.getStatus().getTravelHint();
		if (hint == null)
		{
			return;
		}

		// The one that reaches where you are going, not everything in the room. A house can hold
		// both a nexus and a jewellery box, and outlining both says "one of these two, you work
		// out which" — which is the question the player came here with.
		//
		// The box is preferred when it is known to reach the stop, because that is a fact from our
		// own table. The nexus otherwise: what it is attuned to is per-account and unknowable from
		// outside, so it is the honest default rather than a claim.
		List<TileObject> furniture =
			HouseTeleports.reachableByJewelleryBox(hint.getDestination())
				? house.getJewelleryBoxes()
				: house.getNexuses();

		// A house with only the other kind still gets its furniture marked: pointing at the one
		// teleport in the room beats pointing at nothing.
		if (furniture.isEmpty())
		{
			furniture = house.getTeleports();
		}

		for (TileObject object : furniture)
		{
			Shape clickbox = object.getClickbox();
			if (clickbox != null)
			{
				OverlayUtil.renderPolygon(graphics, clickbox, colour,
					ColorUtil.colorWithAlpha(colour, FILL_ALPHA), graphics.getStroke());
			}

			if (config.guideHighlightStyle() == DoogleMapsConfig.GuideHighlightStyle.OUTLINE)
			{
				outlineRenderer.drawOutline(object, config.guideOutlineThickness(), colour,
					config.guideOutlineFeathering());
			}
		}
	}

	/**
	 * Outlines a specific NPC, for the steps that name one.
	 *
	 * <p>By id rather than by name, which is the opposite of what the leprechaun search below
	 * does — and right for the opposite reason. There are eight leprechauns answering to one name,
	 * so a name match is what keeps that stable; a farmer and Guildmaster Jane are single, named
	 * individuals whose ids come straight out of the same generated data the patches do.
	 */
	private void highlightNpcById(Graphics2D graphics, Color colour, int npcId)
	{
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc == null || npc.getId() != npcId)
			{
				continue;
			}

			outlineNpc(graphics, colour, npc);
			return;
		}
	}

	/** Outlines the nearest tool leprechaun, for the noting and withdrawing steps. */
	private void highlightLeprechaun(Graphics2D graphics, Color colour)
	{
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc == null || npc.getName() == null)
			{
				continue;
			}
			// By name rather than by id: there are eight leprechaun ids across the game and
			// they all answer to the same thing. A name match cannot rot when one is added.
			if (!npc.getName().toLowerCase().contains("leprechaun"))
			{
				continue;
			}

			outlineNpc(graphics, colour, npc);
			return;
		}
	}

	/** Draws one NPC in whichever style the player chose. */
	private void outlineNpc(Graphics2D graphics, Color colour, NPC npc)
	{
		if (config.guideHighlightStyle() == DoogleMapsConfig.GuideHighlightStyle.OUTLINE)
		{
			outlineRenderer.drawOutline(npc, config.guideOutlineThickness(), colour,
				config.guideOutlineFeathering());
			return;
		}

		Shape hull = npc.getConvexHull();
		if (hull != null)
		{
			OverlayUtil.renderPolygon(graphics, hull, colour,
				ColorUtil.colorWithAlpha(colour, FILL_ALPHA), graphics.getStroke());
		}
	}

	/** Keeps an object if it carries this patch's varbit and has not already been counted. */
	private void consider(@Nullable TileObject object, FarmPatch patch, Set<Long> seen,
		List<TileObject> found)
	{
		if (object == null)
		{
			return;
		}

		ObjectComposition definition = client.getObjectDefinition(object.getId());
		if (definition == null || definition.getVarbitId() != patch.getVarbit())
		{
			return;
		}

		// An object spanning several tiles is reachable from each of them, so without this a
		// multi-tile patch gets outlined once per tile it covers, thickening the line.
		if (seen.add(object.getHash()))
		{
			found.add(object);
		}
	}
}
