package com.dooglemaps.bank;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginMessage;

/**
 * A stand-in for Inventory Setups, speaking only its published {@code PluginMessage} contract.
 *
 * <p>Built from {@code InventorySetupsPluginMessageHandler} in its source — {@code get-setups}
 * filling a caller's mutable collection synchronously, {@code view} selecting a known setup,
 * {@code clear} letting go only of a named setup that is still the selected one. That contract
 * is the entire surface {@link InventorySetupsHandoff} depends on, so a fake that honours it is
 * a complete stand-in: no class names, packages or member signatures have to match anything,
 * which is what the previous reflective transport needed and what made its fakes so fragile.
 *
 * <p>Registering this on the same {@link net.runelite.client.eventbus.EventBus} as the handoff
 * stands in for Inventory Setups being installed; leaving it unregistered stands in for it
 * being absent, where every message is a silent no-op exactly as against a real, missing
 * plugin. Both are worth a test and both are cheap now.
 *
 * <p>{@link #addSetup} deliberately does <b>not</b> broadcast {@code setups-changed}, though
 * the real plugin does: the retry clock and the broadcast are two independent roads to finding
 * a setup that appeared late, and a fake that always took the second one would leave the first
 * untested. A test that wants the broadcast posts it itself.
 */
class FakeInventorySetups
{
	private static final String NAMESPACE = "inventory-setups";
	private static final String MESSAGE_GET_SETUPS = "get-setups";
	private static final String MESSAGE_VIEW = "view";
	private static final String MESSAGE_CLEAR = "clear";
	private static final String KEY_SETUPS = "setups";
	private static final String KEY_SETUP = "setup";

	private final List<String> setupNames = new ArrayList<>();

	@Nullable
	private String currentSelectedSetup;

	/** Adds a setup to the player's list, as they spelled it. */
	void addSetup(String name)
	{
		setupNames.add(name);
	}

	/** The setup the sidebar is showing, or null for the overview. */
	@Nullable
	String getCurrentSelectedSetup()
	{
		return currentSelectedSetup;
	}

	/** The player clicking a setup themselves, which a named {@code clear} must not undo. */
	void selectByHand(String name)
	{
		currentSelectedSetup = name;
	}

	@Subscribe
	public void onPluginMessage(PluginMessage message)
	{
		if (!NAMESPACE.equals(message.getNamespace()))
		{
			return;
		}

		switch (message.getName())
		{
			case MESSAGE_GET_SETUPS:
			{
				Object container = message.getData().get(KEY_SETUPS);
				if (container instanceof Collection)
				{
					// Synchronously, before the caller's post() returns — the whole reason
					// the handoff can read the list straight after asking for it.
					@SuppressWarnings("unchecked")
					Collection<String> setups = (Collection<String>) container;
					setups.addAll(setupNames);
				}
				break;
			}
			case MESSAGE_VIEW:
			{
				Object name = message.getData().get(KEY_SETUP);
				// An unknown name is ignored rather than selected, as the real handler does.
				if (name instanceof String && setupNames.contains(name))
				{
					currentSelectedSetup = (String) name;
				}
				break;
			}
			case MESSAGE_CLEAR:
			{
				Object name = message.getData().get(KEY_SETUP);
				if (name == null || name.equals(currentSelectedSetup))
				{
					currentSelectedSetup = null;
				}
				break;
			}
			default:
			{
				break;
			}
		}
	}
}
