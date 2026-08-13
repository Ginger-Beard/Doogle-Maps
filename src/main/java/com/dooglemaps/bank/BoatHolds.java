package com.dooglemaps.bank;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;

/**
 * What is stowed in your boats' cargo holds — for the two items that live there.
 *
 * <h2>Why this exists at all</h2>
 *
 * The loadout said <i>"skipping fishbowl helmet, diving apparatus - you have none"</i> to a
 * player whose diving gear was on their boat. It was looking in the bank and the pack, which
 * for those two items is the one place they are least likely to be: a cargo hold stores diving
 * gear <b>taking no space at all</b>, and the wiki is explicit that it is then "accessible from
 * all boats when stored in any of them" — so a hold is where it naturally ends up and stays.
 *
 * <p>A December 2025 update makes the point on Jagex's side too: "the fishbowl helmet and
 * diving apparatus are no longer deleted from the bank when deposited into the cargo hold".
 * Stowing them is a supported, ordinary thing to do.
 *
 * <h2>Deliberately only about what is stored, not how much</h2>
 *
 * A set of ids rather than counts, because the question this answers is "do you own the diving
 * suit" and both pieces are single items. The rest of a hold — salvage, repair kits, cannonballs
 * — is nothing a farm run has an opinion about, so it is recorded and ignored rather than
 * modelled.
 *
 * <p>Persisted per profile like {@link BankContents}, and for the same reason: a hold is only
 * sent to the client when it is opened, so without a memory the answer would be "not looked"
 * on every login and the false alarm would come straight back.
 */
@Slf4j
@Singleton
public class BoatHolds extends com.dooglemaps.state.ProfileJsonStore
{
	private static final String CONTENTS_KEY = "boatHolds";

	private static final Type ID_SET_TYPE = new TypeToken<HashSet<Integer>>()
	{
	}.getType();

	/**
	 * Every cargo hold the game will send, and there are five because a player can own five
	 * boats.
	 *
	 * <p>All of them together, not one per boat, because the items this class is for are
	 * shared: diving gear stowed in <i>any</i> hold is reachable from every boat, so which one
	 * it is in is not a question worth being able to answer.
	 */
	private static final int[] HOLDS = {
		InventoryID.SAILING_BOAT_1_CARGOHOLD,
		InventoryID.SAILING_BOAT_2_CARGOHOLD,
		InventoryID.SAILING_BOAT_3_CARGOHOLD,
		InventoryID.SAILING_BOAT_4_CARGOHOLD,
		InventoryID.SAILING_BOAT_5_CARGOHOLD,
	};

	private final Set<Integer> stowed = new LinkedHashSet<>();
	private boolean seen;

	@Inject
	BoatHolds(ConfigManager configManager, Gson gson)
	{
		super(configManager, gson, CONTENTS_KEY);
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		for (int hold : HOLDS)
		{
			if (event.getContainerId() == hold)
			{
				record(event.getItemContainer());
				return;
			}
		}
	}

	/**
	 * Folds one hold's contents into what is known, rather than replacing it.
	 *
	 * <p>Additive on purpose, and it is the one place this differs from the bank. There are
	 * five holds and the game sends one at a time, so replacing wholesale would have opening
	 * boat two forget everything boat one was carrying. Nothing here is a count, so nothing
	 * goes stale in a way that matters: the worst case is remembering gear that has since been
	 * taken out, which the next read of that hold corrects.
	 */
	void record(@Nullable ItemContainer container)
	{
		synchronized (this)
		{
			seen = true;
			if (container == null)
			{
				return;
			}
			for (Item item : container.getItems())
			{
				if (item != null && item.getId() > 0 && item.getQuantity() > 0)
				{
					stowed.add(item.getId());
				}
			}
		}

		// Outside the monitor. ProfileJsonStore.save's javadoc forbids the other order by name,
		// and documents the deadlock it caused: a save posts ConfigChanged synchronously into
		// every subscriber, so holding this lock across it runs arbitrary plugin code under it.
		// Same miss as BankContents — the sweep that fixed five other stores did not reach here.
		save();
	}

	/** Whether this item is stowed in a hold we have read. */
	public synchronized boolean has(int itemId)
	{
		return stowed.contains(itemId);
	}

	/**
	 * Whether any hold has ever been read.
	 *
	 * <p>The same distinction the bank draws, and it decides the wording rather than the
	 * answer: with no hold ever opened, "you have none" is a claim about a place nobody has
	 * looked in.
	 */
	public synchronized boolean hasBeenSeen()
	{
		return seen;
	}

	@Override
	protected synchronized void resetForLoad()
	{
		stowed.clear();
		seen = false;
	}

	@Override
	protected synchronized void applyJson(String json)
	{
		Set<Integer> loaded = gson.fromJson(json, ID_SET_TYPE);
		if (loaded != null)
		{
			for (Integer id : loaded)
			{
				if (id != null && id > 0)
				{
					stowed.add(id);
				}
			}
		}
		// A stored blob at all — even an empty "[]" — means a hold was read at some point,
		// which is half of what this class answers.
		seen = true;
	}

	@Override
	protected synchronized Object serialized()
	{
		return new HashSet<>(stowed);
	}

	/** Forgets everything, for a profile reset. Rebuilt by opening a hold. */
	public void clear()
	{
		synchronized (this)
		{
			stowed.clear();
			seen = false;
		}
		save();
	}
}
