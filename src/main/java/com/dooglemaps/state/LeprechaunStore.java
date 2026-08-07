package com.dooglemaps.state;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.FarmingTool;
import java.util.EnumMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

/**
 * What the tool leprechaun is holding for you.
 *
 * <p>Read from varbits rather than from his interface, which is the whole reason this is worth
 * having: the store can be inspected while you are standing in a bank deciding what to withdraw,
 * rather than only once you have walked to a patch and clicked him. See {@link FarmingTool} for
 * the varbits themselves.
 *
 * <p>Every leprechaun shares one store, so there is no per-location bookkeeping here — what he
 * has at Falador he has at Catherby.
 *
 * <h2>Why this reads every tick</h2>
 *
 * The obvious alternative is {@code VarbitChanged}, and it was rejected on purpose. The tool
 * varbits are spread across at least two varps and the mapping is not published, so a
 * varp-filtered subscription would silently stop noticing whichever slot happens to live
 * somewhere unexpected — and the failure mode is a stale "he has a rake" long after it was taken
 * out. An unfiltered subscription would fire on every varbit in the game instead.
 *
 * <p>The read is thirteen masked array lookups against a cached composition, which is nothing
 * next to the per-tick work already being done to derive a guide step. Cheap and unconditionally
 * correct beats clever and conditionally correct here.
 *
 * <p>Read on the client thread, in the tick handler, and handed out as an immutable snapshot —
 * so the panel and the overlays can ask from any thread. It holds no reference to any other
 * store, which keeps it a leaf in the lock graph. See {@code docs/NOTES.md} on lock ordering.
 */
@Slf4j
@Singleton
public class LeprechaunStore
{
	private final Client client;

	/**
	 * Replaced wholesale rather than mutated, so a reader never sees a half-written map and no
	 * lock is needed on the reading side.
	 */
	private volatile Map<FarmingTool, Integer> held = new EnumMap<>(FarmingTool.class);

	private volatile boolean read;

	@Inject
	private LeprechaunStore(Client client)
	{
		this.client = client;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		Map<FarmingTool, Integer> fresh = new EnumMap<>(FarmingTool.class);
		for (FarmingTool tool : FarmingTool.values())
		{
			int total = 0;
			for (int varbit : tool.getVarbits())
			{
				total += Math.max(0, client.getVarbitValue(varbit));
			}
			fresh.put(tool, total);
			noteOverflow(tool, total);
		}

		held = fresh;
		read = true;
	}

	/**
	 * How many of a thing he is holding — <b>at least</b> this many.
	 *
	 * <p>Exact if the {@code EXTRA} varbits are additive storage and an underestimate if they
	 * turn out to be high bits of one number. Safe to compare against zero, which is all
	 * anything does with it today; see {@link FarmingTool}.
	 */
	public int getCount(FarmingTool tool)
	{
		return held.getOrDefault(tool, 0);
	}

	/** Whether he has any at all. Unaffected by the {@code EXTRA} ambiguity. */
	public boolean has(FarmingTool tool)
	{
		return getCount(tool) > 0;
	}

	/** Whether he holds the compost the player picked. False for {@link CompostTier#NONE}. */
	public boolean hasCompost(@Nullable CompostTier tier)
	{
		FarmingTool tool = tier == null ? null : FarmingTool.forCompost(tier);
		return tool != null && has(tool);
	}

	/**
	 * Whether the store has been read at all this session.
	 *
	 * <p>The same distinction the bank draws between missing and unknown, and for the same
	 * reason: before the first tick after login every slot reads as empty, and announcing that
	 * the leprechaun has none of your tools would be a false alarm every session. The difference
	 * is that this one resolves itself within 600ms of logging in rather than waiting for the
	 * player to open something.
	 */
	public boolean hasBeenRead()
	{
		return read;
	}

	/** Forgotten on logout, so another account never inherits this one's store. */
	public void reset()
	{
		held = new EnumMap<>(FarmingTool.class);
		read = false;
	}

	/**
	 * Says so, once, when a slot holds more than its base varbit could express on its own.
	 *
	 * <p>The one observation that would settle whether the {@code EXTRA} varbits are additive or
	 * high bits is someone's real store holding a large number. Rather than guess, this leaves a
	 * line in the log the first time a count goes past what looks like a base-only reading, in
	 * the same spirit as the harvest log and the Geomancy probe: the plugin announces the data it
	 * needs rather than waiting to be asked for it.
	 */
	private void noteOverflow(FarmingTool tool, int total)
	{
		if (tool.getVarbits().length < 2 || total <= OVERFLOW_HINT || loggedOverflow.contains(tool))
		{
			return;
		}

		loggedOverflow.add(tool);
		StringBuilder parts = new StringBuilder();
		for (int varbit : tool.getVarbits())
		{
			parts.append(varbit).append('=').append(client.getVarbitValue(varbit)).append(' ');
		}
		log.info("Leprechaun store: {} sums to {} across its varbits ({}). If the store screen "
			+ "shows a different number, the EXTRA varbits are high bits rather than extra "
			+ "storage - please say what it reads.", tool.getDisplayName(), total, parts.toString().trim());
	}

	/** Above this, a count is interesting enough to mention once. Nothing depends on the value. */
	private static final int OVERFLOW_HINT = 20;

	private final java.util.Set<FarmingTool> loggedOverflow =
		java.util.EnumSet.noneOf(FarmingTool.class);
}
