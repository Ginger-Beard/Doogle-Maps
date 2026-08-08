package com.dooglemaps.capture;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmRegion;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.state.PatchStateStore;
import com.google.common.collect.ImmutableSet;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.ItemID;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;

/**
 * Captures which compost a patch was treated with.
 *
 * <p>Compost is not in the patch varbit, so it has to be inferred. The chat message says
 * <i>what</i> was applied but not <i>where</i> ("You treat the herb patch with
 * ultracompost"), so we pair it with the click that caused it: a compost bucket or
 * Fertile Soil used on a patch, or an Inspect that reports existing treatment. The click
 * is held briefly as pending and resolved by the next matching message, checking the
 * player is still standing beside that patch.
 */
@Slf4j
@Singleton
public class CompostCapture
{
	private static final Duration PENDING_TIMEOUT = Duration.ofSeconds(30);

	private static final Pattern COMPOST_USED_ON_PATCH = Pattern.compile(
		"You treat the .+ with (?<compostType>ultra|super|)compost\\.");
	private static final Pattern FERTILE_SOIL_CAST = Pattern.compile(
		"^The .+ has been treated with (?<compostType>ultra|super|)compost");
	private static final Pattern ALREADY_TREATED = Pattern.compile(
		"This .+ has already been (treated|fertilised) with (?<compostType>ultra|super|)compost"
			+ "(?: - the spell can't make it any more fertile)?\\.");
	private static final Pattern INSPECT_PATCH = Pattern.compile(
		"This is an? .+\\. The soil has been treated with (?<compostType>ultra|super|)compost\\..*");

	private static final Set<Integer> COMPOST_ITEMS = ImmutableSet.of(
		ItemID.COMPOST,
		ItemID.SUPERCOMPOST,
		ItemID.ULTRACOMPOST,
		ItemID.BOTTOMLESS_COMPOST_BUCKET_22997
	);

	@Value
	private static class PendingCompost
	{
		Instant timeout;
		WorldPoint patchLocation;
		FarmPatch patch;
	}

	private final Client client;
	private final PatchStateStore stateStore;

	private final Map<FarmPatch, PendingCompost> pending = new HashMap<>();

	@Inject
	CompostCapture(Client client, PatchStateStore stateStore)
	{
		this.client = client;
		this.stateStore = stateStore;
	}

	public void reset()
	{
		pending.clear();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN && event.getGameState() != GameState.LOADING)
		{
			pending.clear();
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!isCompostAction(event))
		{
			return;
		}

		ObjectComposition patchDef = client.getObjectDefinition(event.getId());
		if (patchDef == null)
		{
			return;
		}

		WorldPoint actionLocation = WorldPoint.fromScene(
			client, event.getParam0(), event.getParam1(), client.getPlane());

		FarmPatch target = findPatch(actionLocation, patchDef.getVarbitId());
		if (target == null)
		{
			return;
		}

		log.debug("Pending compost action on {}", target);
		pending.put(target, new PendingCompost(Instant.now().plus(PENDING_TIMEOUT), actionLocation, target));
	}

	@Nullable
	private FarmPatch findPatch(WorldPoint location, int varbitId)
	{
		for (FarmRegion region : FarmingWorldData.getRegionsForLocation(location))
		{
			for (FarmPatch patch : region.getPatches())
			{
				// Compost bins are treated *with* compost, never treated themselves.
				if (patch.getVarbit() == varbitId
					&& patch.getImplementation() != PatchImplementation.COMPOST
					&& patch.getImplementation() != PatchImplementation.BIG_COMPOST)
				{
					return patch;
				}
			}
		}
		return null;
	}

	private boolean isCompostAction(MenuOptionClicked event)
	{
		switch (event.getMenuAction())
		{
			case WIDGET_TARGET_ON_GAME_OBJECT:
				Widget selected = client.getSelectedWidget();
				return selected != null
					&& (COMPOST_ITEMS.contains(selected.getItemId())
					|| selected.getId() == ComponentID.SPELLBOOK_FERTILE_SOIL);

			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
				return "Inspect".equals(event.getMenuOption());

			default:
				return false;
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}

		CompostTier used = determineCompostUsed(event.getMessage());
		if (used == null)
		{
			return;
		}

		pending.values().removeIf(p -> Instant.now().isAfter(p.getTimeout()));

		pending.values().stream()
			.filter(this::playerIsBesidePatch)
			.findFirst()
			.ifPresent(p ->
			{
				stateStore.recordCompost(p.getPatch(), used);
				pending.remove(p.getPatch());
			});
	}

	/**
	 * Whether the player is adjacent to the patch the pending click targeted.
	 *
	 * <p>Guards against attributing a message to the wrong patch when several are in
	 * range, e.g. the two allotments that share a farmer.
	 */
	private boolean playerIsBesidePatch(PendingCompost pendingCompost)
	{
		if (client.getLocalPlayer() == null)
		{
			return false;
		}

		// The scene may have reloaded since the click, so re-resolve from the world point.
		LocalPoint localPatch = LocalPoint.fromWorld(client, pendingCompost.getPatchLocation());
		if (localPatch == null)
		{
			return false;
		}

		Tile tile = client.getScene().getTiles()[client.getPlane()][localPatch.getSceneX()][localPatch.getSceneY()];
		if (tile == null)
		{
			return false;
		}

		GameObject patchObject = null;
		for (GameObject object : tile.getGameObjects())
		{
			if (object == null)
			{
				continue;
			}
			ObjectComposition definition = client.getObjectDefinition(object.getId());
			if (definition != null && definition.getVarbitId() == pendingCompost.getPatch().getVarbit())
			{
				patchObject = object;
				break;
			}
		}
		if (patchObject == null)
		{
			return false;
		}

		WorldPoint player = client.getLocalPlayer().getWorldLocation();
		WorldPoint base = pendingCompost.getPatchLocation();
		int maxX = base.getX() + patchObject.sizeX() - 1;
		int maxY = base.getY() + patchObject.sizeY() - 1;

		return player.getX() >= base.getX() - 1 && player.getX() <= maxX + 1
			&& player.getY() >= base.getY() - 1 && player.getY() <= maxY + 1;
	}

	@Nullable
	static CompostTier determineCompostUsed(String chatMessage)
	{
		if (!chatMessage.contains("compost"))
		{
			return null;
		}

		Matcher matcher;
		if ((matcher = COMPOST_USED_ON_PATCH.matcher(chatMessage)).matches()
			|| (matcher = FERTILE_SOIL_CAST.matcher(chatMessage)).find()
			|| (matcher = ALREADY_TREATED.matcher(chatMessage)).matches()
			|| (matcher = INSPECT_PATCH.matcher(chatMessage)).matches())
		{
			switch (matcher.group("compostType"))
			{
				case "ultra":
					return CompostTier.ULTRACOMPOST;
				case "super":
					return CompostTier.SUPERCOMPOST;
				default:
					return CompostTier.COMPOST;
			}
		}

		return null;
	}
}
