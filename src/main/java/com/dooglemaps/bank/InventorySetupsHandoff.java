package com.dooglemaps.bank;

import com.dooglemaps.data.PatchImplementation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginMessage;

/**
 * Hands the hespori run's bank leg over to the player's own Inventory Setups loadout.
 *
 * <h2>Why the hespori's bank leg is not ours to run</h2>
 *
 * The hespori is a boss, and the run's whole loadout doctrine is farming supplies — seeds,
 * compost, a spade. Walking into its cave with that is walking in unarmed, and the plugin has
 * no business inventing a combat loadout: what to bring to a fight is gear, prayer, food and
 * preference, none of which it knows anything about. The player very likely already keeps the
 * answer in Inventory Setups. So on a hespori run this class stands the whole withdraw
 * apparatus down — the filter, the highlights, the loadout summary — and the bank leg becomes:
 * deposit everything, then load your own setup named <b>hespori</b>. See the entry this
 * settles in {@code docs/TODO.md}.
 *
 * <h2>How the two plugins talk</h2>
 *
 * Inventory Setups exposes an API over RuneLite's {@link PluginMessage} bus under the
 * {@code inventory-setups} namespace, the same arrangement {@code ShortestPathIntegration}
 * uses with Shortest Path — {@code InventorySetupsPluginMessageHandler} in its source is the
 * contract:
 *
 * <ul>
 *   <li>{@code get-setups} with a mutable {@code Collection<String>} under {@code setups} —
 *       filled synchronously, before {@code post()} returns, with the current setup names.</li>
 *   <li>{@code view} with {@code setup} — selects that setup exactly as clicking it in the
 *       panel would: the sidebar shows it, and its own bank filtering (a Bank Tags tag, like
 *       ours) applies whenever the bank is open.</li>
 *   <li>{@code clear} with {@code setup} — returns the panel to its overview, but only if
 *       that named setup is still the selected one, so a setup the player navigated to
 *       themselves is never yanked away.</li>
 *   <li>{@code setups-changed} — broadcast whenever the setup list changes, which is what
 *       lets a setup created or renamed mid-session be found without polling.</li>
 * </ul>
 *
 * <p>No plugin instance is ever held, and nothing here inspects Inventory Setups' classes —
 * every call is a message posted to the shared bus, a no-op if nobody is listening. If
 * Inventory Setups is not installed, {@code get-setups} simply returns no names, which reads
 * identically to "installed but no hespori setup exists": {@link #supplyLines} degrades to the
 * same "gear up by hand" line either way, so the two cases need no telling apart.
 *
 * <h2>The compliance line, and why {@code view} is inside it</h2>
 *
 * The TODO entry's open question was whether triggering a setup chains into a sequence of
 * withdrawals and equips, which would be a different category from every swap in
 * {@code docs/design-principles.md}. It does not: {@code view} performs no game action at all.
 * It changes what the sidebar and the bank <i>display</i> — the identical category to the
 * bank tag {@code BankFilter} already opens uninvited — and every withdrawal and equip that
 * follows is one click of the player's per item, exactly as before. One click is still one
 * game action.
 *
 * <h2>When the leg ends</h2>
 *
 * The farming leg ends when the withdraw list is empty, a fact the plugin can read. Whether
 * the player has finished gearing up is not readable the same way — the setup's contents
 * belong to another plugin, and matching the pack against them would be a lot of machinery to
 * answer a question the player answers by walking away. So the leg ends on the observable
 * version of that: the bank was open at the leg and has been closed. Reopening it changes
 * nothing; the run is moving by then, and {@code waiveBankLeg} remains the escape hatch it
 * always was.
 */
@Slf4j
@Singleton
public class InventorySetupsHandoff
{
	private static final String NAMESPACE = "inventory-setups";
	private static final String MESSAGE_GET_SETUPS = "get-setups";
	private static final String MESSAGE_VIEW = "view";
	private static final String MESSAGE_CLEAR = "clear";
	private static final String MESSAGE_SETUPS_CHANGED = "setups-changed";
	private static final String KEY_SETUPS = "setups";
	private static final String KEY_SETUP = "setup";

	/**
	 * The setup this looks for, matched case-insensitively against the player's list.
	 *
	 * <p>A convention rather than a config knob, deliberately: "name it hespori" is one fact
	 * to know, where a setting is one fact plus a place to have set it wrong. The player's
	 * own capitalisation is preserved in everything shown, because the matched name — not
	 * this constant — is what {@code view} and {@code clear} are sent.
	 */
	private static final String WANTED_SETUP = "hespori";

	/** How often to look again for a setup that was not there, in ticks — one minute. */
	private static final int RETRY_TICKS = 100;

	private final Client client;
	private final ClientThread clientThread;
	private final EventBus eventBus;
	private final com.dooglemaps.route.RunPlanner planner;

	/**
	 * The player's setup name as they spelled it, or null while no match has been found.
	 * Volatile because the panel thread asks {@link #supplyLines} while the client thread
	 * writes it.
	 */
	@Nullable
	private volatile String matched;

	/** The tick the list was last asked for, so a missing setup is not searched every tick. */
	private int lastSearch = Integer.MIN_VALUE;

	/** Whether the list has been asked for at all this run; cleared by {@link #reset}. */
	private boolean searched;

	/** Whether {@code view} has been sent this run, which is what {@code clear} undoes. */
	private boolean viewPosted;

	/**
	 * The name {@code view} was actually sent, kept apart from {@link #matched} because a
	 * {@code setups-changed} clears that for re-matching — and the {@code clear} must name
	 * what we selected, or a renamed list would have it un-selecting whatever the player has.
	 */
	@Nullable
	private volatile String viewedName;

	/** Whether the bank has been seen open during this run's supply leg. */
	private boolean bankSeen;

	/**
	 * Whether the gear stop is done — the bank was opened at the leg and closed again.
	 * Volatile for the same reason {@link #matched} is.
	 */
	private volatile boolean gearCollected;

	/** Whether the last tick was on a hespori run, so standing down happens exactly once. */
	private boolean wasRunning;

	@Inject
	InventorySetupsHandoff(Client client, ClientThread clientThread, EventBus eventBus,
		com.dooglemaps.route.RunPlanner planner)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.eventBus = eventBus;
		this.planner = planner;
	}

	/**
	 * Whether the run is in the phase this class owns.
	 *
	 * <p>The planner's gear phase, not "the run covers the hespori": the handoff owns the run
	 * only up to the hespori stop being done. After that the run is a farm run again — the
	 * planner arms the swap-back bank trip ({@code RunPlanner.reviewGearSwap}), this class
	 * stands down on the next tick, and the ordinary withdraw list, filter and highlights
	 * carry the second bank visit. A growing hespori under an everything-tick never starts
	 * the phase at all, so the tick can stay on permanently like every other line.
	 */
	public boolean applies()
	{
		return planner.isGearPhase();
	}

	/**
	 * Whether a run over these types would belong to this class — the pre-start form of
	 * {@link #applies}, for the moment the start button asks before the planner knows.
	 */
	public static boolean appliesTo(Set<PatchImplementation> types)
	{
		return types.contains(PatchImplementation.HESPORI);
	}

	/**
	 * Whether the gear stop still has to happen. The hespori run's replacement for the
	 * withdraw list's answer — see {@code GuideTracker}, which chooses between the two.
	 */
	public boolean gearOutstanding()
	{
		return !gearCollected;
	}

	/**
	 * The bank leg's instructions, standing in for {@code LoadoutSummary} on a hespori run.
	 */
	public List<String> supplyLines()
	{
		// Deliberately gentle. Earlier versions ordered deposits and marked the deposit
		// buttons, and every stricter reading of "did they gear up properly" was wrong for
		// somebody — what the player banks and carries on a fight trip is their call. The
		// leg's whole contract is said out loud instead: gear up however you like, and
		// closing the bank is what moves the run on.
		List<String> lines = new ArrayList<>();
		String name = matched;
		if (name != null)
		{
			lines.add("Load your \"" + name + "\" Inventory Setups loadout");
		}
		else
		{
			lines.add("No \"" + WANTED_SETUP + "\" setup found in Inventory Setups - "
				+ "gear up by hand");
		}
		lines.add("Close the bank when you are ready and the run moves on");
		return lines;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (!applies())
		{
			standDown();
			return;
		}
		wasRunning = true;

		if (matched == null
			&& (!searched || client.getTickCount() - lastSearch >= RETRY_TICKS))
		{
			// Re-asked on a slow clock rather than once, because "not found" has two causes
			// this cannot tell apart: the setup does not exist, and Inventory Setups had not
			// finished starting when the run did. The second heals by asking again.
			search();
		}
		if (matched != null && !viewPosted)
		{
			postView();
		}

		followBankVisit();
	}

	/**
	 * Re-finds the setup when the player's list changes — created mid-run counts, and so
	 * does a rename. Fired by Inventory Setups itself, so there is nothing to poll.
	 */
	@Subscribe
	public void onPluginMessage(PluginMessage message)
	{
		if (NAMESPACE.equals(message.getNamespace())
			&& MESSAGE_SETUPS_CHANGED.equals(message.getName()))
		{
			searched = false;
			matched = null;
		}
	}

	/**
	 * Puts everything back for the next run, telling Inventory Setups only if we had spoken.
	 *
	 * <p>Runs on the tick after the run ends, which is on the client thread — where the
	 * {@code clear} has to be posted from, since Inventory Setups acts on it there.
	 */
	private void standDown()
	{
		if (!wasRunning)
		{
			return;
		}
		String goodbye = viewPosted ? viewedName : null;
		if (goodbye != null)
		{
			postClear(goodbye);
		}
		reset();
	}

	private void reset()
	{
		wasRunning = false;
		searched = false;
		lastSearch = Integer.MIN_VALUE;
		matched = null;
		viewPosted = false;
		viewedName = null;
		bankSeen = false;
		gearCollected = false;
	}

	/**
	 * Called by the plugin's {@code shutDown}, which runs on the EDT — so the goodbye is
	 * handed to the client thread rather than posted here, and the state is cleared
	 * immediately either way. The name is captured before the reset that nulls it.
	 */
	public void shutDown()
	{
		String goodbye = viewPosted ? viewedName : null;
		reset();
		if (goodbye != null)
		{
			clientThread.invokeLater(() -> postClear(goodbye));
		}
	}

	/**
	 * Reads Inventory Setups' names over {@code get-setups} and takes the first
	 * case-insensitive {@code hespori}. A no-op post — Inventory Setups not installed —
	 * fills nothing, which reads the same as "installed but no match": see the class doc.
	 */
	private void search()
	{
		searched = true;
		lastSearch = client.getTickCount();

		List<String> names = new ArrayList<>();
		Map<String, Object> data = new HashMap<>();
		data.put(KEY_SETUPS, names);
		eventBus.post(new PluginMessage(NAMESPACE, MESSAGE_GET_SETUPS, data));

		for (String name : names)
		{
			if (WANTED_SETUP.equalsIgnoreCase(name.trim()))
			{
				matched = name;
				log.info("Inventory Setups has a \"{}\" loadout; the bank leg is its", name);
				return;
			}
		}
		log.info("Inventory Setups offered no \"{}\" setup among {} names; "
			+ "the bank leg will say so", WANTED_SETUP, names.size());
	}

	/**
	 * Selects the setup the moment the run starts rather than waiting for a bank, because
	 * selection is what arms its own filtering: Inventory Setups re-applies the selected
	 * setup's bank tag itself every time a bank opens.
	 */
	private void postView()
	{
		viewPosted = true;
		viewedName = matched;
		Map<String, Object> data = new HashMap<>();
		data.put(KEY_SETUP, matched);
		eventBus.post(new PluginMessage(NAMESPACE, MESSAGE_VIEW, data));
		log.debug("Asked Inventory Setups to view \"{}\"", matched);
	}

	/**
	 * Named, so only our own selection is cleared: a clear only lands while the named setup
	 * is still the selected one, which is exactly the case where the player has not
	 * navigated somewhere themselves.
	 */
	private void postClear(String name)
	{
		Map<String, Object> data = new HashMap<>();
		data.put(KEY_SETUP, name);
		eventBus.post(new PluginMessage(NAMESPACE, MESSAGE_CLEAR, data));
		log.debug("Asked Inventory Setups to put its overview back from \"{}\"", name);
	}

	/**
	 * Watches the bank open and close during the supply leg; the close is what ends it.
	 * The kept setup selection is deliberate — the run's last errand is re-banking the gear,
	 * and the setup's own filtering is the best view of that bank too.
	 */
	private void followBankVisit()
	{
		if (gearCollected || !planner.isAtBankLeg())
		{
			return;
		}
		if (bankIsOpen())
		{
			bankSeen = true;
		}
		else if (bankSeen)
		{
			gearCollected = true;
			log.info("The bank has been and gone; calling the hespori gear collected");
		}
	}

	private boolean bankIsOpen()
	{
		Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
		return items != null && !items.isHidden();
	}
}
