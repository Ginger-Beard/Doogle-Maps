package com.dooglemaps.state;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

/**
 * Where the player is, readable from any thread.
 *
 * <p>{@code Player.getWorldLocation()} asserts it is on the client thread and throws outright
 * if it is not — and unlike most of the client API, that assertion is on the <i>Player</i>
 * rather than on {@code getLocalPlayer()}, so holding a player reference proves nothing about
 * where you may ask it questions. Pressing Start run from the sidebar did exactly that and
 * threw on the Swing thread.
 *
 * <p>So it is sampled once a tick, on the client thread, and everything else reads the sample.
 * A tick-old position is no worse than a fresh one for anything here — deciding which farming
 * region you are standing in does not turn on a few hundred milliseconds — and it removes a
 * whole class of "which thread am I on" question from every caller.
 */
@Singleton
public class PlayerLocation
{
	private final Client client;

	private volatile WorldPoint location;

	@Inject
	PlayerLocation(Client client)
	{
		this.client = client;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		location = client.getLocalPlayer() == null
			? null
			: client.getLocalPlayer().getWorldLocation();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// Logged out or hopping, so the last position is about to be meaningless. Better to
		// answer "unknown" than to answer with wherever they were before.
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			location = null;
		}
	}

	/**
	 * The player's tile as of the last game tick, or null if unknown.
	 *
	 * <p>Null before the first tick after login, and while logged out. Callers have to handle
	 * that rather than assume — it is the same answer they would have got from a client with no
	 * local player.
	 */
	@Nullable
	public WorldPoint get()
	{
		return location;
	}

	/** The map region the player is in, or -1 when the position is unknown. */
	public int getRegionId()
	{
		WorldPoint where = location;
		return where == null ? -1 : where.getRegionID();
	}

	public void reset()
	{
		location = null;
	}
}
