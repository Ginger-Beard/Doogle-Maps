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
import net.runelite.api.Player;
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
	private final DroppedProduce droppedProduce;

	/** Spores on the seabed, which are marked whether or not a run is under way. */
	private final SeaweedSpores seaweedSpores;

	@Inject
	GuideOverlay(Client client, GuideTracker tracker, DoogleMapsConfig config,
		ModelOutlineRenderer outlineRenderer, PatchLocationStore locations, PlayerHouse house,
		DroppedProduce droppedProduce, SeaweedSpores seaweedSpores)
	{
		this.seaweedSpores = seaweedSpores;
		this.droppedProduce = droppedProduce;
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

		// Nothing is drawn against a scene that is being rebuilt, and every cached object found
		// in the old one is dropped on the spot.
		//
		// Belt and braces beside isStillInScene, and the same fix PlayerHouse carries for the
		// same reason: the scans below run once a tick and this draws every frame, so between a
		// reload and the next tick the caches hold objects whose scene data has been freed and
		// reused. isStillInScene catches the ones that end up outside the new scene's bounds;
		// this catches the rest by not asking about them at all.
		if (client.getGameState() != net.runelite.api.GameState.LOGGED_IN)
		{
			forgetScannedObjects();
			return null;
		}

		Color colour = config.guideHighlightColour();

		// The way down to an underwater patch, whenever the run is heading for one and the
		// object is in the scene. Independent of the step for the same reason the spores are:
		// the steps are the last thing to click on a travel leg, and travel legs have no step.
		highlightUnderwaterApproach(graphics, colour);

		// Before the step, and regardless of whether there is one. A seaweed spore is an
		// interruption with a thirty-second clock on it rather than a piece of the run's
		// work — it can land while you are raking, while you are travelling, or with the run
		// finished and nothing else to say — so it is drawn on its own terms. See
		// SeaweedSpores.
		highlightSeaweedSpores(graphics);

		// The container the run has named, whenever it has named one.
		//
		// This lived inside the "no current step" branch below, alongside the bank leg, on the
		// reasoning that the supply leg is the stop with no patch work. That is true of the bank
		// leg and not of the other way a run sends you to a container: a contract taken from
		// Jane produces a FETCH_SEED step, and a step is exactly what makes the branch below
		// unreachable. So the sentence said "withdraw your irit seed from the seed vault here"
		// and the vault standing in front of the player stayed dark. Reported from play twice -
		// the second time after the sources set had been fixed, which only made the answer
		// available to a branch that was never reached.
		//
		// Guarded on the set being non-empty, which matters: marks() reads an empty set as "a
		// bank, we are not sure what for" and would light every booth in the room.
		java.util.Set<com.dooglemaps.state.SeedSource> named =
			tracker.getStatus().getSupplySources();
		if (!named.isEmpty())
		{
			highlightSupplyPoints(graphics, colour);
		}

		GuideStep step = tracker.getCurrentStep();
		if (step == null)
		{
			// No step means one of two quite different things, and only one of them is "nothing
			// to do here".
			//
			// The supply leg is a stop with an instruction — "collect your supplies" — and the
			// panel has been saying so all along while the scene stayed dark. The one thing you
			// actually have to click was the one thing never marked.
			// The named case is handled above; this is the bank leg that has not worked out
			// what it is collecting yet, where marks() falls back to every bank and no vault.
			if (tracker.getStatus().isAtBankLeg() && named.isEmpty())
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

			// Outside the house, the route's first hop can be a world object - the GE's
			// spirit tree, a fairy ring - and nothing marked it: the drawn line says where to
			// walk, but the thing to click at the end of the walk went unlit. Reported from
			// play at the GE spirit tree.
			if (!house.isInside())
			{
				highlightRouteObject(graphics, colour);
			}

			// And the patches you are travelling TO, once they come into view.
			//
			// Patch highlighting used to live entirely below this early return, so it began only
			// when the stop produced a step — which is to say, only once the run considered you
			// arrived. Walking the last stretch with the route drawn and every patch dark was
			// reported from play right after arrival stopped meaning "somewhere in the region";
			// the two are the same moment seen twice, and this is the half that was missing.
			//
			// Outline only, never the tile marker: the fallback exists so a patch you are stood
            // at cannot silently fail to highlight, and a marker for something still a hundred
			// tiles away would be drawn behind the wall of whatever is between you and it. Out of
			// scene means out of sight, and out of sight is nothing to draw.
			highlightPatchesAhead(graphics, tracker.getStatus().getPatchesAhead(), colour);
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
		else if (step.getAction() == GuideAction.PICK_UP_DROPS)
		{
			highlightDroppedProduce(graphics, colour);
		}
		return null;
	}

	/**
	 * Outlines every patch of the stop being travelled to that is currently in view.
	 *
	 * <h2>One scan for the lot, and why that is not an optimisation</h2>
	 *
	 * {@link #findPatchObjects} caches a single patch per tick — it was written for the current
	 * step, and there is only ever one of those. Calling it in a loop therefore misses on every
	 * patch after the first and rescans the whole 104x104 scene, four object kinds a tile, for
	 * each of them. Per frame, not per tick: at Falador that is four full scene walks fifty times
	 * a second, to draw an outline that has not moved.
	 *
	 * <p>So this walks the scene once and buckets what it finds. The cost is one scan a tick
	 * regardless of how many patches the stop has, which is what the single-patch cache was
	 * already paying.
	 *
	 * <p>Outline only, never {@link #markTile}: the tile fallback exists so a patch you are
	 * standing at cannot silently fail to highlight, and a marker for something a hundred tiles
	 * off would be drawn through whatever stands between. Out of scene is out of sight, and out
	 * of sight is nothing to draw.
	 */
	private void highlightPatchesAhead(Graphics2D graphics,
		List<com.dooglemaps.data.FarmPatch> ahead, Color colour)
	{
		if (ahead.isEmpty())
		{
			return;
		}

		int tick = client.getTickCount();
		if (tick != aheadTick)
		{
			aheadTick = tick;
			aheadObjects = new ArrayList<>();
			Set<Long> seen = new HashSet<>();
			Scene scene = client.getTopLevelWorldView().getScene();
			Tile[][][] tiles = scene.getTiles();
			int plane = client.getTopLevelWorldView().getPlane();
			for (Tile[] column : tiles[plane])
			{
				for (Tile tile : column)
				{
					if (tile == null)
					{
						continue;
					}
					for (com.dooglemaps.data.FarmPatch patch : ahead)
					{
						for (GameObject object : tile.getGameObjects())
						{
							consider(object, patch, seen, aheadObjects);
						}
						consider(tile.getGroundObject(), patch, seen, aheadObjects);
						consider(tile.getDecorativeObject(), patch, seen, aheadObjects);
						consider(tile.getWallObject(), patch, seen, aheadObjects);
					}
				}
			}
		}

		if (aheadObjects.isEmpty())
		{
			return;
		}

		fillAndOutline(graphics, mergedTiles(aheadObjects), colour);
		if (config.guideHighlightStyle() == DoogleMapsConfig.GuideHighlightStyle.OUTLINE)
		{
			for (TileObject object : aheadObjects)
			{
				outlineObject(object, colour);
			}
		}
	}

	/** The travel-leg scan's per-tick cache; see {@link #highlightPatchesAhead}. */
	private List<TileObject> aheadObjects = Collections.emptyList();
	private int aheadTick = -1;

	/**
	 * Outlines the steps down to an underwater patch the run is heading for.
	 *
	 * <p>The router is asked for the landward approach rather than the patch — nothing can
	 * path to the seabed, and asking got "destination unreachable" (see
	 * {@link com.dooglemaps.data.UnderwaterApproach}). That leaves the last click unmarked:
	 * the drawn line ends on a shore and the thing to click is a set of steps among the
	 * scenery. This is the same gap {@code highlightRouteObject} fills for a fairy ring.
	 *
	 * <p>By object id rather than by name, which is the difference from that method: the id is
	 * known exactly, read off the client at the spot, so there is no menu-wording to parse and
	 * nothing to get wrong. Scanning costs a scene walk, so it only happens while the run
	 * actually has an underwater stop left.
	 */
	private void highlightUnderwaterApproach(Graphics2D graphics, Color colour)
	{
		com.dooglemaps.data.UnderwaterApproach.Approach approach = tracker.underwaterApproach();
		if (approach == null)
		{
			return;
		}

		int tick = client.getTickCount();
		if (tick != approachTick || approachId != approach.getObjectId())
		{
			approachTick = tick;
			approachId = approach.getObjectId();
			approachObjects = scanForObjectId(approach.getObjectId());
		}

		for (TileObject object : approachObjects)
		{
			drawObject(graphics, object, colour);
		}
	}

	private int approachTick = -1;
	private int approachId = -1;
	private java.util.List<TileObject> approachObjects = Collections.emptyList();

	/** Every scene object with this exact id, impostors resolved like the name scan does. */
	private java.util.List<TileObject> scanForObjectId(int objectId)
	{
		java.util.List<TileObject> found = new java.util.ArrayList<>();
		java.util.Set<Long> seen = new java.util.HashSet<>();
		net.runelite.api.WorldView worldView = client.getTopLevelWorldView();
		net.runelite.api.Tile[][][] tiles = worldView.getScene().getTiles();
		for (net.runelite.api.Tile[] column : tiles[worldView.getPlane()])
		{
			for (net.runelite.api.Tile tile : column)
			{
				if (tile == null)
				{
					continue;
				}
				for (net.runelite.api.GameObject object : tile.getGameObjects())
				{
					if (object == null || !seen.add(object.getHash()))
					{
						continue;
					}
					if (object.getId() == objectId)
					{
						found.add(object);
					}
				}
			}
		}
		return found;
	}

	/**
	 * Marks the seabed tiles a seaweed spore is sitting on.
	 *
	 * <p>Its own colour rather than the guide's, and deliberately: everything else this
	 * overlay draws is "the thing the current step wants", and a spore is not that. It is a
	 * chance passing by, so it reads as a different kind of mark — the same green the game
	 * uses for its own ground-item highlights, which is the association a player already has.
	 *
	 * <p>Tiles rather than item models, exactly as the dropped-crop highlight does it: a
	 * ground item is a few pixels with no silhouette worth tracing, and the tile is the thing
	 * that gets clicked.
	 */
	private void highlightSeaweedSpores(Graphics2D graphics)
	{
		java.util.List<SeaweedSpores.Spore> spores = seaweedSpores.current();
		if (spores.isEmpty() || !config.notifySeaweedSpores())
		{
			return;
		}

		Area tiles = new Area();
		for (SeaweedSpores.Spore spore : spores)
		{
			LocalPoint local = LocalPoint.fromWorld(client.getTopLevelWorldView(),
				spore.getLocation());
			if (local == null)
			{
				continue;
			}
			Polygon tile = Perspective.getCanvasTilePoly(client, local);
			if (tile != null)
			{
				tiles.add(new Area(tile));
			}
		}
		fillAndOutline(graphics, tiles, SPORE_COLOUR);
	}

	/** The game's own ground-item green, so a spore reads as a pickup rather than a step. */
	private static final Color SPORE_COLOUR = new Color(0x3F, 0xC1, 0x5F);

	/**
	 * Marks the tiles holding the crops a full pack dropped.
	 *
	 * <p>Tiles rather than item models: a ground item is a handful of pixels with no
	 * silhouette worth tracing, and the tile is what gets clicked. The record is
	 * {@code DroppedProduce}'s — the step exists exactly while it has entries, so there is
	 * nothing to re-derive here. Merged into one shape for the same reason the patch tiles
	 * are: two stacks on adjacent squares should read as one place to go, not a grid.
	 */
	private void highlightDroppedProduce(Graphics2D graphics, Color colour)
	{
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}

		Area tiles = new Area();
		for (DroppedProduce.Drop drop : droppedProduce.near(player.getWorldLocation(), 32))
		{
			LocalPoint local = LocalPoint.fromWorld(client.getTopLevelWorldView(),
				drop.getLocation());
			if (local == null)
			{
				continue;
			}
			Polygon tile = Perspective.getCanvasTilePoly(client, local);
			if (tile != null)
			{
				tiles.add(new Area(tile));
			}
		}
		fillAndOutline(graphics, tiles, colour);
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
				outlineObject(object, colour);
			}
		}
		else
		{
			// Clickboxes merged for the same reason as the tiles.
			Area boxes = new Area();
			for (TileObject object : objects)
			{
				Shape clickbox = isStillInScene(object) ? clickboxOf(object) : null;
				if (clickbox != null)
				{
					boxes.add(new Area(clickbox));
				}
			}
			fillAndOutline(graphics, boxes, colour);
		}
	}

	/**
	 * Draws one object the way every highlight in here draws one: clickbox, then model.
	 *
	 * <p>Four call sites had this same eight lines copied out, which is how the guard below came
	 * to be missing from all of them at once.
	 */
	private void drawObject(Graphics2D graphics, TileObject object, Color colour)
	{
		if (!isStillInScene(object))
		{
			return;
		}

		Shape clickbox = clickboxOf(object);
		if (clickbox != null)
		{
			OverlayUtil.renderPolygon(graphics, clickbox, colour,
				ColorUtil.colorWithAlpha(colour, FILL_ALPHA), graphics.getStroke());
		}

		if (config.guideHighlightStyle() == DoogleMapsConfig.GuideHighlightStyle.OUTLINE)
		{
			outlineObject(object, colour);
		}
	}

	/**
	 * Whether this object is still standing in the scene we are about to draw.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"when a patch/tree/idk gets highlighted, ALL of the NPCs in the area start flickering
	 * with the same colour highlighting, rapidly, extremely rapidly. Including my own player
	 * character."</i>
	 *
	 * <p>The same stale object behind the overlay NPEs (§0f), on the frames where it does not
	 * have the decency to throw. Every object drawn here comes from a scan that ran on a
	 * <b>tick</b>, and this renders on a <b>frame</b>, so after a scene reload the caches hold
	 * objects whose scene data the client has already freed and reused. {@code getClickbox} walks
	 * that memory and raises a {@code NullPointerException} — the loud case, and the one that got
	 * noticed first. {@code ModelOutlineRenderer} walks it too, and it does not throw: it renders
	 * an outline from whatever now occupies those scene slots, which is the NPCs standing around
	 * you and your own player. Different symptom, one cause; the flicker is per-frame because
	 * what is in the reused memory changes every frame.
	 *
	 * <p>{@code LocalPoint.isInScene} is the cheap honest test — an object that no longer has a
	 * place in the current scene is one nothing should be asked about. Deliberately not a
	 * try/catch: the outline path was never throwing, so catching was never going to help it.
	 */
	private boolean isStillInScene(TileObject object)
	{
		try
		{
			return standsInTheScene(object);
		}
		catch (RuntimeException stale)
		{
			noteStaleObject(stale);
			return false;
		}
	}

	/** The test itself, without the logging, so it can be asked of a plain object in a test. */
	static boolean standsInTheScene(TileObject object)
	{
		LocalPoint at = object.getLocalLocation();
		return at != null && at.isInScene();
	}

	/**
	 * An object's clickbox, or null when the client cannot build one for it.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * Every object drawn here comes from a scan that ran on a tick, and this renders on a frame,
	 * so an object can outlive the scene it was found in by up to ~30 frames. Asking a stale one
	 * for its clickbox throws a {@code NullPointerException} <i>inside the client</i> — the
	 * traces bottom out in {@code fb.getClickbox} building a model against freed scene data, with
	 * nothing of ours null anywhere in them. So the {@code clickbox != null} check these call
	 * sites already had was dead weight: the failure is a throw, not a null.
	 *
	 * <p>And it cost far more than the one highlight. {@code OverlayRenderer.safeRender} catches
	 * per overlay, not per object, so one stale house portal took the route object and every
	 * patch ahead down with it for that frame — twenty-five bursts of it in a week of logs.
	 *
	 * <p>{@link com.dooglemaps.state.PlayerHouse} closes most of the window by dropping its
	 * furniture on {@code LOADING} rather than on the next tick. This is what remains: the scans
	 * in this class have the same one-tick staleness and no event to hang that on, and there is
	 * no API for asking a {@link TileObject} whether its scene is still there.
	 */
	@Nullable
	private Shape clickboxOf(TileObject object)
	{
		try
		{
			return object.getClickbox();
		}
		catch (RuntimeException stale)
		{
			noteStaleObject(stale);
			return null;
		}
	}

	/** {@link #clickboxOf}'s counterpart: the model outline walks the same freed data. */
	private void outlineObject(TileObject object, Color colour)
	{
		if (!isStillInScene(object))
		{
			return;
		}

		try
		{
			outlineRenderer.drawOutline(object, config.guideOutlineThickness(), colour,
				config.guideOutlineFeathering());
		}
		catch (RuntimeException stale)
		{
			noteStaleObject(stale);
		}
	}

	/**
	 * Says it once and then shuts up.
	 *
	 * <p>These arrive at frame rate in bursts — a scene reload produces one per object per frame
	 * until the next tick — so an unthrottled warn would bury the log it is meant to be visible
	 * in. Worth saying at all because a <i>persistent</i> version of this would mean the object
	 * is not stale but wrong, which is a bug of ours and would otherwise now be silent.
	 */
	private boolean staleReported;

	private void noteStaleObject(RuntimeException cause)
	{
		if (staleReported)
		{
			log.debug("Skipped a stale scene object", cause);
			return;
		}
		staleReported = true;
		log.warn("Skipped a scene object the client could no longer draw - it was found on an "
			+ "earlier tick and the scene has been rebuilt since. The highlight is missing for "
			+ "this frame only. Further occurrences log at debug.", cause);
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

		// Nothing on the seabed carries a patch varbit, so the ordinary scan finds a coral
		// nursery only by luck. Its objects are known by id instead — read off the client at
		// the spot — which is the same escape hatch the tile fallback below is, one step
		// earlier and far more precise. Reported from play as the nursery patches never
		// highlighting.
		if (scannedObjects.isEmpty())
		{
			List<TileObject> byId = new ArrayList<>();
			for (int objectId
				: com.dooglemaps.data.UnderwaterApproach.objectsFor(patch.getImplementation()))
			{
				byId.addAll(scanForObjectId(objectId));
			}

			// ...and then narrowed to the one the step actually names.
			//
			// This used to mark both nurseries, on the stated grounds that they "cannot be told
			// apart from the objects alone" — true when it was written, because a seabed patch
			// had no location and the two ids do not map one-to-one onto the two patches.
			// Reported from play as both being lit at once. They are five tiles apart and both
			// positions are now shipped (MeasuredPatchLocations 12581.4771 and .4772), so the
			// question the objects could not answer is answered by where they stand. Same shape
			// as objectIsThisPatch, which resolves the varbit collisions on dry land.
			//
			// Falling back to all of them when the position is unknown, because a nursery lit
			// twice is a smaller failure than one never lit at all — which is the bug this
			// whole branch exists to fix.
			List<TileObject> mine = nearestTo(byId,
				locations.isKnown(patch) ? locations.getLocation(patch) : null);
			if (!mine.isEmpty())
			{
				scannedObjects = mine;
				return scannedObjects;
			}
			if (!byId.isEmpty())
			{
				scannedObjects = byId;
				return scannedObjects;
			}
		}

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

	/**
	 * How close an object has to stand to a patch's own tile to be that patch's.
	 *
	 * <p>Two, because a coral nursery is 2x2 and the pair are five tiles apart — so this cannot
	 * reach the neighbour, and cannot miss its own. Deliberately not "whichever is nearest": with
	 * one nursery out of scene, nearest-wins would confidently outline the wrong one.
	 */
	private static final int OWN_OBJECT_TILES = 2;

	/**
	 * The objects standing at this patch, out of a set found by object id alone.
	 *
	 * <p>Empty when the position is unknown or nothing is close enough, which the caller reads as
	 * "no opinion" and falls back on marking all of them.
	 */
	private static List<TileObject> nearestTo(List<TileObject> objects, @Nullable WorldPoint at)
	{
		if (at == null)
		{
			return Collections.emptyList();
		}

		List<TileObject> mine = new ArrayList<>();
		for (TileObject object : objects)
		{
			WorldPoint where = object.getWorldLocation();
			if (where != null && where.getPlane() == at.getPlane()
				&& where.distanceTo(at) <= OWN_OBJECT_TILES)
			{
				mine.add(object);
			}
		}
		return mine;
	}

	/**
	 * Drops every cached scan, so nothing found in a freed scene is drawn against the next one.
	 *
	 * <p>The tick stamps are reset rather than only the lists: a stamp left matching would have
	 * the next scan on that tick skipped, and an empty list is the one answer worse than a stale
	 * one — it reads as "this patch has no object", which is a claim.
	 */
	private void forgetScannedObjects()
	{
		aheadObjects = Collections.emptyList();
		aheadTick = -1;
		approachObjects = Collections.emptyList();
		approachTick = -1;
		scannedObjects = Collections.emptyList();
		scannedTick = -1;
		scannedPatchKey = null;
		supplyObjects = new ArrayList<>();
		routeObjects = Collections.emptyList();
		routeObjectTick = -1;
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

			drawObject(graphics, object, colour);
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
	/** The destination a transport-vocabulary miss was last said for, once rather than per frame. */
	@Nullable
	private String loggedTransportMissFor;

	/**
	 * Says when Shortest Path's hops matched none of the furniture in the room.
	 *
	 * <p>The router's wording for house furniture is the one thing the name matching depends
	 * on and the one thing that cannot be checked from here — same spirit as the nexus-row
	 * logger in {@code GuideInventoryOverlay}: make the game announce the vocabulary rather
	 * than guessing at it.
	 */
	private void noteUnmatchedTransports(@Nullable String destination, List<String> transports)
	{
		// A hint can travel without a destination name now; the hops are still worth logging,
		// keyed under a stand-in so the once-per-destination dedup keeps working.
		String key = destination == null ? "(unnamed leg)" : destination;
		if (transports.isEmpty() || key.equals(loggedTransportMissFor))
		{
			return;
		}
		loggedTransportMissFor = key;

		// Both halves of the failed comparison, because the hops alone were not enough: the
		// fairy ring sat unmatched for two sessions while every guess about what the scan had
		// called it went unverifiable. name#id, from the same scan the matching ran over.
		log.info("None of Shortest Path's hops for \"{}\" mapped to the furniture here - "
			+ "hops: {}, furniture seen: {}. Nothing is outlined; if one of those hops should "
			+ "have picked a piece of furniture, its wording or the furniture's destination "
			+ "list needs matching.",
			key, transports, house.furnitureNames());
	}

	/** The scene objects matching the route's first hop, re-scanned once a tick. */
	private java.util.List<TileObject> routeObjects = java.util.Collections.emptyList();
	private int routeObjectTick = -1;
	@Nullable
	private String routeObjectName;

	/**
	 * Outlines the world object the route's first hop goes through.
	 *
	 * <p>Shortest Path names it in the transports message ({@code objectInfo}), and the scene
	 * is searched for objects wearing that name — the same treatment the house gives its
	 * furniture, extended to the overworld. Cached per tick like every other scene scan here.
	 */
	private void highlightRouteObject(Graphics2D graphics, Color colour)
	{
		String name = tracker.routeObjectName();
		if (name == null || name.length() < 4)
		{
			return;
		}

		int tick = client.getTickCount();
		if (tick != routeObjectTick || !name.equals(routeObjectName))
		{
			routeObjectTick = tick;
			routeObjectName = name;
			routeObjects = scanForObjects(name);
		}

		for (TileObject object : routeObjects)
		{
			drawObject(graphics, object, colour);
		}
	}

	/**
	 * How many words of menu option may sit in front of the object's own name.
	 *
	 * <h2>Why a word count is the rule</h2>
	 *
	 * Shortest Path's {@code objectInfo} column is <b>{@code menuOption menuTarget objectID}</b>
	 * with no delimiter between the first two, and the target is the object's name. So the hop
	 * arrives here (id already stripped) as "Travel Spirit tree", "Enter Annakarl Portal",
	 * "Teleport Menu Fancy Jewellery Box" — never as the bare name this scan used to demand with
	 * {@code equalsIgnoreCase}, which is why the overworld highlight has never lit anything. The
	 * spirit tree at the Grand Exchange, logged as fixed once, was fixed only in the panel's
	 * <i>wording</i>; the outline half was still comparing a menu row to an object name.
	 *
	 * <p>A plain suffix rule is the obvious repair and is not safe: "Enter Annakarl Portal" ends
	 * in "Portal", and a house is full of objects called exactly that. So the prefix has to be
	 * plausible as a menu option, and counting its words is what makes that checkable. Every
	 * {@code objectInfo} row across Shortest Path's twenty-four transport TSVs was read for this
	 * — the longest options in the data are two words ("Teleport Menu", "Look out", "Land's
	 * End", "The Pandemonium", "Draynor Village"), so two is the cap. "Enter Annakarl Portal"
	 * minus "Portal" leaves four words and is refused; minus "Annakarl Portal" it leaves one and
	 * is accepted. An option longer than two words simply fails to match, which is exactly where
	 * this started — no regression, and nothing lights that should not.
	 */
	private static final int MAX_MENU_OPTION_WORDS = 2;

	/**
	 * How much of the hop this object's name accounts for, or 0 when it is not the hop's object.
	 *
	 * <p>A length rather than a boolean so the scan can prefer the <b>longest</b> answer: with
	 * both "Annakarl Portal" and a plain "Portal" in the scene, the longer name is the one the
	 * router meant and the short one is a coincidence of the wording. Package-private so the
	 * matcher can be pinned without a client.
	 */
	static int routeObjectMatch(String hop, @Nullable String objectName)
	{
		if (objectName == null || objectName.isEmpty() || hop.length() < objectName.length())
		{
			return 0;
		}
		if (hop.equalsIgnoreCase(objectName))
		{
			return objectName.length();
		}

		// Same length and not equal above means it is simply a different name; the boundary
		// check below would read the character before the string.
		int start = hop.length() - objectName.length();
		if (start == 0 || hop.charAt(start - 1) != ' '
			|| !hop.regionMatches(true, start, objectName, 0, objectName.length()))
		{
			return 0;
		}

		String option = hop.substring(0, start).trim();
		if (option.isEmpty())
		{
			return 0;
		}

		// A one-word name may only follow a one-word option, which is what stops "Portal" from
		// answering for "Enter Annakarl Portal": both parses of that hop fit the two-word cap
		// ("Enter" + "Annakarl Portal", and "Enter Annakarl" + "Portal"), so the cap alone
		// cannot separate them, and the shorter parse is the one that lights every exit in a
		// house. Real one-word names arrive as the whole of a two-word hop — "Climb-up Ladder",
		// "Open Gate" — and those still match.
		int optionWords = option.split("\\s+").length;
		int allowed = objectName.indexOf(' ') < 0 ? 1 : MAX_MENU_OPTION_WORDS;
		return optionWords > allowed ? 0 : objectName.length();
	}

	/**
	 * Every object in the scene the route's hop names, impostors followed.
	 *
	 * <p>Wall, decorative and ground objects too, not only game objects: an agility shortcut
	 * is as likely to be a wall object (a broken wall, a crack, a window) as a game object,
	 * and a scan of game objects alone left those unlit however well the name matched.
	 */
	private java.util.List<TileObject> scanForObjects(String hop)
	{
		java.util.List<TileObject> found = new java.util.ArrayList<>();
		java.util.Set<Long> seen = new java.util.HashSet<>();
		int[] best = {0};
		net.runelite.api.WorldView worldView = client.getTopLevelWorldView();
		net.runelite.api.Tile[][][] tiles = worldView.getScene().getTiles();
		for (net.runelite.api.Tile[] column : tiles[worldView.getPlane()])
		{
			for (net.runelite.api.Tile tile : column)
			{
				if (tile == null)
				{
					continue;
				}
				for (net.runelite.api.GameObject object : tile.getGameObjects())
				{
					considerRouteObject(hop, object, seen, found, best);
				}
				considerRouteObject(hop, tile.getWallObject(), seen, found, best);
				considerRouteObject(hop, tile.getDecorativeObject(), seen, found, best);
				considerRouteObject(hop, tile.getGroundObject(), seen, found, best);
			}
		}
		return found;
	}

	/** One candidate for {@link #scanForObjects}; {@code best} is the longest match so far. */
	private void considerRouteObject(String hop, @Nullable TileObject object,
		java.util.Set<Long> seen, java.util.List<TileObject> found, int[] best)
	{
		if (object == null || !seen.add(object.getHash()))
		{
			return;
		}
		net.runelite.api.ObjectComposition definition =
			client.getObjectDefinition(object.getId());
		if (definition != null && definition.getImpostorIds() != null)
		{
			net.runelite.api.ObjectComposition impostor = definition.getImpostor();
			definition = impostor != null ? impostor : definition;
		}

		int match = definition == null
			? 0
			: routeObjectMatch(hop, definition.getName());
		if (match == 0 || match < best[0])
		{
			return;
		}
		// A longer name has turned up, so everything matched on a shorter one was a
		// coincidence of the wording — see routeObjectMatch.
		if (match > best[0])
		{
			best[0] = match;
			found.clear();
		}
		found.add(object);
	}

	private void highlightHouseTeleports(Graphics2D graphics, Color colour)
	{
		TravelHint hint = tracker.getStatus().getTravelHint();
		if (hint == null)
		{
			return;
		}

		// The one the route uses, not everything in the room. A house can hold a nexus, a
		// jewellery box and half a dozen portals, and outlining them all says "one of these,
		// you work out which" — which is the question the player came here with.
		//
		// Shortest Path decides, because it already did: it planned this leg with the player's
		// own transport settings, and its hop descriptions name places. A piece of furniture
		// whose wiki destination list covers a named hop — or whose own name carries it, as a
		// Varrock Portal does — is the route's choice, read back rather than guessed. Settled
		// with the owner: no per-stop table of this plugin's own, and without Shortest Path
		// there is no route, so nothing is outlined — the guide says where to go, not how.
		// Live rather than the tick snapshot: the router answers mid-tick, and the fallback
		// below must not judge "nothing serves the route" against hops that are a frame stale.
		// See GuideTracker.liveTransports.
		List<String> transports = tracker.liveTransports();
		List<TileObject> furniture = house.matchingFurniture(name ->
			transports.stream().anyMatch(hop -> HouseTeleports.furnitureServesHop(name, hop)
				// ...and, for a spirit tree, only to somewhere a tree has actually been grown.
				// Reported from play: the guild's tree was outlined for a hop through it while
				// its patch sat at weeds. See GuideTracker.spiritTreeUsableFor.
				//
				// Asked only OF a spirit tree, which it was not. The guard finds its place by
				// looking for a spirit-tree name anywhere in the hop, and a hop names its vehicle
				// as well as its destination — so it read "Fancy Jewellery Box - J: Farming
				// Guild" as a spirit tree question and answered no, because that patch is empty.
				// The jewellery box went dark with the route pointing straight at it. Reported
				// from play. See HouseTeleports.isSpiritTree.
				&& (!HouseTeleports.isSpiritTree(name) || tracker.spiritTreeUsableFor(hop))));

		// The destination gets a say before the way out does. The route's hops are Shortest
		// Path's plan, and its model has no nexus — so a destination the player's own
		// furniture genuinely reaches can be absent from every hop, and judging by hops alone
		// contradicted the row matcher: "Troll Stronghold" lit inside the open nexus while
		// the nexus itself stayed dark and the exit portal lit as the answer. Reported from
		// play, with Stony basalt sitting in the nexus. Furniture that reaches where this leg
		// is going is the instruction, whatever the router planned around not knowing it.
		if (furniture.isEmpty() && house.isInside() && hint.getDestination() != null)
		{
			String destination = hint.getDestination();
			furniture = house.matchingFurniture(name ->
				HouseTeleports.furnitureServesHop(name, destination)
					// Scoped to spirit trees for the same reason as above, and it matters more
					// here: this fallback is asked with the destination alone, which is the very
					// string the guard keys on.
					&& (!HouseTeleports.isSpiritTree(name)
						|| tracker.spiritTreeUsableFor(destination)));
		}

		// justEntered guards the arrival tick, where this overlay's live isInside() is a tick
		// ahead of the tracker's route — the stale hops matched no furniture and the exit
		// portal flashed lit for the ~600ms until the tracker retargeted. Reported from play.
		//
		// And never while the router's own origins say the plan goes THROUGH this house. The
		// exit portal is the answer when the route continues outside; a plan departing from
		// inside the POH area is house furniture by the router's word, and pointing at the
		// way out because no NAME matched walked the player past the garden ring their own
		// route had picked. Reported from play, Aldarin. When the furniture cannot be named,
		// nothing is lit and the unmatched hops are logged below instead.
		if (furniture.isEmpty() && house.isInside() && !house.justEntered()
			&& !tracker.routeAnswerPending() && !tracker.routeDepartsTheHouse())
		{
			// Nothing in the house serves the route, and you are standing in the house — so the
			// route continues outside it, and the way out is the exit portal. The common case is
			// a house whose front door is the destination's neighbourhood: teleport to house,
			// walk out, walk to the patches. The bare name "Portal" is deliberately unmatchable
			// as a route hop (see HouseTeleports.furnitureServesHop), so the exits could never be
			// chosen above; here they are the answer precisely because nothing else was.
			// Reported from play at a Prifddinas house: teleported in, and the guide lit nothing.
			//
			// ...and only once the router has actually answered. Arriving in the house clears
			// the transports and asks for a fresh route, and for the second that takes, "no
			// transports" is a pending question rather than a walking answer — the exit portal
			// lit during the wait and then flipped. Reported from play. Waiting shows nothing
			// for that second, which is honest: the guide does not know yet.
			furniture = house.exitPortals();
		}

		if (furniture.isEmpty())
		{
			// Hops were reported but none mapped to the furniture here - the one seam in this
			// design, so it announces itself with the router's exact words.
			noteUnmatchedTransports(hint.getDestination(), transports);
			return;
		}

		for (TileObject object : furniture)
		{
			drawObject(graphics, object, colour);
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
		// Two passes, exact id first. Fossil Island has three hardwood patches whose farmers
		// are three different NPCs that all happen to share the name "Squirrel" - a single
		// name-matching pass (what FarmerVariants.same falls back to) would outline whichever
		// squirrel came first in world order for all three steps. The exact id, which is the
		// one FarmingWorldData actually names for this step's patch, is the disambiguator; the
		// variant pass exists only for a farmer whose step-carried id is not the one the world
		// data recorded - Guildmaster Jane's _1OP/_2OP, the coral farmer's _LOCKED/_UNLOCKED.
		NPC variantMatch = null;
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc == null)
			{
				continue;
			}
			if (npc.getId() == npcId)
			{
				outlineNpc(graphics, colour, npc);
				loggedNpcMissId = -1;
				return;
			}
			if (variantMatch == null && com.dooglemaps.data.FarmerVariants.same(npcId, npc.getId()))
			{
				variantMatch = npc;
			}
		}

		if (variantMatch != null)
		{
			outlineNpc(graphics, colour, variantMatch);
			loggedNpcMissId = -1;
			return;
		}

		// Nothing in the scene matched, by either pass - said once per id rather than once a
		// frame, mirroring findPatchObjects' loggedMissKey. A variant-id mismatch like the
		// Savannah gardener's is otherwise invisible from outside: the step names an id, the
		// scene holds a different one for the same person, and nothing on screen says so.
		if (npcId != loggedNpcMissId)
		{
			loggedNpcMissId = npcId;
			String name = com.dooglemaps.data.Farmers.getName(npcId);
			StringBuilder sameName = new StringBuilder();
			if (name != null)
			{
				for (NPC npc : client.getTopLevelWorldView().npcs())
				{
					if (npc != null && name.equals(npc.getName()))
					{
						if (sameName.length() > 0)
						{
							sameName.append(", ");
						}
						sameName.append(npc.getId());
					}
				}
			}
			log.info("No scene NPC matches id {} ({}) by id or by variant - nothing to "
					+ "outline. {}",
				npcId, name, sameName.length() > 0
					? "Scene NPCs named \"" + name + "\": " + sameName
					: "No scene NPC shares that name either.");
		}
	}

	/** The last step NPC id a highlight miss was logged for; see {@link #highlightNpcById}. */
	private int loggedNpcMissId = -1;

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
	/**
	 * Whether a scene object is <b>this</b> patch: the right varbit, in the right region.
	 *
	 * <h2>A varbit id is not unique across the map</h2>
	 *
	 * The same numbers repeat everywhere — {@code FARMING_TRANSMIT_A} alone is shared by forty-one
	 * patches, so the Champions' Guild bush is {@code 12596.4771} and Lumbridge's hops patch is
	 * {@code 12851.4771}. Matching on the varbit alone lights whichever same-numbered patch happens
	 * to be in the loaded scene, which need not be the one the step is about.
	 *
	 * <p>Reported from play on a harvest-only bush run, and worse than cosmetic. At the Champions'
	 * Guild, with the step correctly reading <i>"harvest the cotton"</i> fifty tiles away in
	 * Lumbridge, the picked-clean <b>whiteberry bush</b> lit up instead. On a harvest-only run the
	 * click that bush invites is the one that clears it — destroying the plant the run exists to
	 * keep.
	 *
	 * <p>Newly reachable rather than newly wrong. {@code SharedStops} folds Lumbridge into the
	 * Champions' Guild stop, so for the first time a current step can name a patch that is not the
	 * one underfoot. The scan was always ambiguous; the merge is what walked into it.
	 *
	 * <p>The same disambiguation {@code PatchLocationCapture} makes when it learns a position:
	 * resolve the region first, match the varbit within it. The seabed needs no exception here —
	 * nothing on it carries a patch varbit at all, which is why {@link #findPatchObjects} falls
	 * through to {@code UnderwaterApproach.objectsFor}.
	 *
	 * <p>Static and parameterised so it can be tested without a scene, like
	 * {@link #routeObjectMatch}.
	 */
	static boolean objectIsThisPatch(int objectVarbitId, int objectRegionId, FarmPatch patch)
	{
		return objectVarbitId == patch.getVarbit()
			&& objectRegionId == patch.getRegion().getRegionId();
	}

	private void consider(@Nullable TileObject object, FarmPatch patch, Set<Long> seen,
		List<TileObject> found)
	{
		if (object == null)
		{
			return;
		}

		ObjectComposition definition = client.getObjectDefinition(object.getId());
		if (definition == null || !objectIsThisPatch(definition.getVarbitId(),
			object.getWorldLocation().getRegionID(), patch))
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
