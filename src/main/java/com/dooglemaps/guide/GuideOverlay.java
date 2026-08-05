package com.dooglemaps.guide;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.route.PatchLocationStore;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;
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

	/** Alpha for the tint over a highlighted inventory item. */
	private static final int ITEM_FILL_ALPHA = 65;

	private final Client client;
	private final GuideTracker tracker;
	private final DoogleMapsConfig config;
	private final ModelOutlineRenderer outlineRenderer;
	private final PatchLocationStore locations;

	@Inject
	private GuideOverlay(Client client, GuideTracker tracker, DoogleMapsConfig config,
		ModelOutlineRenderer outlineRenderer, PatchLocationStore locations)
	{
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

		GuideStep step = tracker.getCurrentStep();
		if (step == null)
		{
			return null;
		}

		Color colour = config.guideHighlightColour();

		if (step.highlightsPatch())
		{
			highlightPatch(graphics, step.getPatch(), colour);
		}
		if (step.hasNpc() || step.isAtLeprechaun())
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

		for (TileObject object : objects)
		{
			// The ground the patch occupies, always. A model outline is invisible on an empty
			// patch — bare soil is a flat decal with no silhouette to trace — which is why
			// crops highlighted and cleared patches did not, with no "nothing found" warning
			// because the objects were there all along.
			//
			// It also answers the earlier ask: an allotment now reads as one lit patch rather
			// than a dozen lit watermelons, because the tiles join up where the models do not.
			Polygon tile = object.getCanvasTilePoly();
			if (tile != null)
			{
				OverlayUtil.renderPolygon(graphics, tile, colour,
					ColorUtil.colorWithAlpha(colour, FILL_ALPHA), graphics.getStroke());
			}

			// Then the model on top, where there is one to trace.
			if (style == DoogleMapsConfig.GuideHighlightStyle.OUTLINE)
			{
				outlineRenderer.drawOutline(object, config.guideOutlineThickness(), colour,
					config.guideOutlineFeathering());
			}
			else
			{
				Shape clickbox = object.getClickbox();
				if (clickbox != null)
				{
					OverlayUtil.renderPolygon(graphics, clickbox, colour,
						ColorUtil.colorWithAlpha(colour, FILL_ALPHA), graphics.getStroke());
				}
			}
		}
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

			if (config.guideHighlightStyle() == DoogleMapsConfig.GuideHighlightStyle.OUTLINE)
			{
				outlineRenderer.drawOutline(npc, config.guideOutlineThickness(), colour,
					config.guideOutlineFeathering());
			}
			else
			{
				Shape hull = npc.getConvexHull();
				if (hull != null)
				{
					OverlayUtil.renderPolygon(graphics, hull, colour,
						ColorUtil.colorWithAlpha(colour, FILL_ALPHA), graphics.getStroke());
				}
			}
			return;
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
