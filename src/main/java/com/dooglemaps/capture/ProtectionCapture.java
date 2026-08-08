package com.dooglemaps.capture;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmRegion;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.state.PatchStateStore;
import com.google.common.collect.ImmutableSet;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.ScriptID;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetModelType;
import net.runelite.client.eventbus.Subscribe;

/**
 * Captures farmer payment, which is what makes a patch disease-proof.
 *
 * <p>Like compost, protection is not in the patch varbit. The tell is the farmer's
 * acceptance line, and the chathead model identifies which farmer said it. Farmers who
 * tend several patches ask which one first, so we also follow the dialogue option the
 * player picked — by click, by hotkey, or by the "Pay (North)" style menu entry.
 */
@Slf4j
@Singleton
public class ProtectionCapture
{
	private static final Set<String> PAYMENT_ACCEPTED = ImmutableSet.of(
		"That'll do nicely, sir. Leave it with me - I'll make sure<br>that patch grows for you.",
		"That'll do nicely, madam. Leave it with me - I'll make<br>sure that patch grows for you.",
		"That'll do nicely, iknami. Leave it with me - I'll make<br>sure that patch grows for you."
	);

	private final Client client;
	private final PatchStateStore stateStore;

	/** Which of a multi-patch farmer's patches the player last chose. */
	private int lastSelectedOption;

	@Inject
	ProtectionCapture(Client client, PatchStateStore stateStore)
	{
		this.client = client;
		this.stateStore = stateStore;
	}

	public void reset()
	{
		lastSelectedOption = 0;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		Widget text = client.getWidget(ComponentID.DIALOG_NPC_TEXT);
		if (text == null || !PAYMENT_ACCEPTED.contains(text.getText()))
		{
			return;
		}

		Widget head = client.getWidget(ComponentID.DIALOG_NPC_HEAD_MODEL);
		if (head == null || head.getModelType() != WidgetModelType.NPC_CHATHEAD)
		{
			return;
		}

		FarmPatch patch = findPatchForNpc(head.getModelId());
		if (patch == null)
		{
			return;
		}

		stateStore.recordProtected(patch, true);
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		MenuAction action = event.getMenuAction();

		if (action == MenuAction.WIDGET_CONTINUE)
		{
			// Picking a patch from the farmer's dialogue list by clicking it.
			Widget widget = event.getWidget();
			if (widget != null && widget.getId() == ComponentID.DIALOG_OPTION_OPTIONS
				&& widget.getIndex() > -1 && isPatchOption(widget.getText()))
			{
				// Child 0 is the "Select an Option" header.
				lastSelectedOption = widget.getIndex() - 1;
			}
		}
		else if ((action == MenuAction.NPC_THIRD_OPTION || action == MenuAction.NPC_FOURTH_OPTION)
			&& event.getMenuOption().startsWith("Pay"))
		{
			// Some farmers expose their patches directly as right-click options instead.
			lastSelectedOption = action == MenuAction.NPC_THIRD_OPTION ? 0 : 1;
		}
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		// Picking a patch from the dialogue list with a number key.
		if (event.getScriptId() != ScriptID.CHATBOX_KEYINPUT_MATCHED)
		{
			return;
		}

		int[] intStack = client.getIntStack();
		int componentId = intStack[0];
		int subId = intStack[1];

		if (componentId != ComponentID.DIALOG_OPTION_OPTIONS || subId <= -1)
		{
			return;
		}

		Widget parent = client.getWidget(componentId);
		Widget option = parent == null ? null : parent.getChild(subId);
		if (option != null && isPatchOption(option.getText()))
		{
			lastSelectedOption = subId - 1;
		}
	}

	private static boolean isPatchOption(@Nullable String name)
	{
		return name != null && (name.contains("Patch") || name.contains("allotment"));
	}

	@Nullable
	private FarmPatch findPatchForNpc(int npcId)
	{
		if (client.getLocalPlayer() == null)
		{
			return null;
		}

		FarmPatch found = null;
		for (FarmRegion region : FarmingWorldData.getRegionsForLocation(client.getLocalPlayer().getWorldLocation()))
		{
			for (FarmPatch patch : region.getPatches())
			{
				if (patch.getFarmer() != npcId)
				{
					continue;
				}
				// patchNumber is only meaningful for farmers tending more than one patch;
				// for the rest, matching the NPC is enough.
				if (patch.getPatchNumber() == -1 || patch.getPatchNumber() == lastSelectedOption)
				{
					found = patch;
				}
			}
		}
		return found;
	}
}
