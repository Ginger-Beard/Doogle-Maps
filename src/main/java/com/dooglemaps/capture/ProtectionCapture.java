package com.dooglemaps.capture;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmRegion;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.state.PatchStateStore;
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
import net.runelite.api.gameval.InterfaceID;
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
	/**
	 * The acceptance line, matched by its shape rather than by its exact text.
	 *
	 * <h2>Why an exact set stopped working</h2>
	 *
	 * It was three whole strings, differing only in how the farmer addresses you — "sir",
	 * "madam", and the Tortugan "iknami". That is a list of the forms of address seen so far,
	 * dressed up as a list of acceptance lines, and it fails silently the first time a new
	 * farmer says the same thing to a new kind of customer: the payment is simply never
	 * recorded, the patch reads unprotected forever, and the run keeps sending the player back
	 * to a bank for payment items they have already spent. Reported from play at the coral
	 * nurseries, both patches paid for and both still reading {@code patchProtected: false}.
	 *
	 * <p>What does not vary is the sentence around the name, so that is what is matched: it
	 * opens with "That'll do nicely" and ends by promising the patch will grow. Still tight —
	 * no other farmer dialogue makes that promise — and no longer a list that has to be kept up
	 * to date by someone noticing it has not been.
	 *
	 * <p>The line wraps, and where it wraps moves with the length of the name, so the {@code
	 * <br>} lands mid-word between "make" and "sure" in two of the three known lines and after
	 * "sure" in the other. Normalising it away is what lets one test cover all of them; see
	 * {@code ProtectionCaptureTest}, which pins the three known lines against this shape so the
	 * evidence they represent is not lost with the set.
	 */
	private static final String ACCEPTED_OPENS = "that'll do nicely";
	private static final String ACCEPTED_PROMISE = "that patch grows for you";

	/** Whether this is a farmer agreeing to look after a patch. */
	private static boolean isPaymentAccepted(@Nullable String line)
	{
		if (line == null)
		{
			return false;
		}
		String flat = line.replace("<br>", " ").toLowerCase(java.util.Locale.ROOT);
		return flat.startsWith(ACCEPTED_OPENS) && flat.contains(ACCEPTED_PROMISE);
	}

	private final Client client;
	private final PatchStateStore stateStore;

	/** Which of a multi-patch farmer's patches the player last chose. */
	private int lastSelectedOption;

	/**
	 * The right-click option that chose the patch, when one did.
	 *
	 * <p>Preferred over {@link #lastSelectedOption} because it says which patch outright:
	 * "Pay (East)" carries the same word the patch is named after. Null whenever the choice
	 * came from a dialogue list, where the index is the honest answer and the text is not the
	 * option's own name.
	 */
	@Nullable
	private String lastSelectedText;

	/**
	 * The tick the selection was made on, so it cannot be redeemed by a different dialogue.
	 *
	 * <p>The acceptance line always follows the selection within the same conversation, so a
	 * selection more than a minute old is a leftover from some earlier farmer — and matching
	 * a *stale* one recorded the payment against the wrong patch of a pair, silently: one
	 * allotment modelled as protected that is not, and one paid for that still reads unpaid.
	 * When the selection is stale the multi-patch record is refused outright; a missed record
	 * shows up as "pay the farmer" reappearing, which is visible, where a wrong one never is.
	 */
	private int lastSelectedTick = -1000;

	/** Generous — a payment conversation takes seconds; a minute covers reading the options. */
	private static final int SELECTION_FRESH_TICKS = 100;

	/**
	 * The patch a "Protection recorded" line was last logged for, so the conversation says so
	 * once rather than once a tick for as long as the acceptance line stays on screen.
	 */
	@Nullable
	private String lastRecordedPatchKey;

	@Inject
	ProtectionCapture(Client client, PatchStateStore stateStore)
	{
		this.client = client;
		this.stateStore = stateStore;
	}

	public void reset()
	{
		lastSelectedOption = 0;
		lastSelectedText = null;
		lastSelectedTick = -1000;
		lastRecordedPatchKey = null;
		reportedUnmatched.clear();
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		Widget text = client.getWidget(InterfaceID.ChatLeft.TEXT);
		Widget head = client.getWidget(InterfaceID.ChatLeft.HEAD);
		if (text == null || head == null
			|| head.getModelType() != WidgetModelType.NPC_CHATHEAD)
		{
			// The dialogue closed - the next acceptance line, whenever it comes, is a new
			// conversation and earns its own log line.
			lastRecordedPatchKey = null;
			return;
		}

		if (!isPaymentAccepted(text.getText()))
		{
			reportUnmatched(head.getModelId(), text.getText());
			return;
		}

		FarmPatch patch = findPatchForNpc(head.getModelId());
		if (patch == null)
		{
			// Said out loud, because the alternative is what this cost last time: a payment
			// made, nothing recorded, and no way to tell from outside whether the line went
			// unrecognised, the chathead was an id the world data does not carry, or the
			// multi-patch choice went stale. All three land here and each needs a different fix.
			log.info("A farmer accepted a payment and no patch could be matched to it - "
					+ "chathead {}, last selection \"{}\" ({} ticks ago). The patch will keep "
					+ "reading unprotected until this id is recognised; see FarmerVariants.",
				head.getModelId(), lastSelectedText,
				client.getTickCount() - lastSelectedTick);
			return;
		}

		stateStore.recordProtected(patch, true);

		// The acceptance line stays on screen for several ticks with nothing changing about
		// it, and onGameTick fires every one of them - without this it was "Protection
		// recorded" once a tick for as long as the player left the dialogue open.
		if (!patch.getKey().equals(lastRecordedPatchKey))
		{
			lastRecordedPatchKey = patch.getKey();
			log.info("Protection recorded: {} paid for, from chathead {}",
				patch.getDisplayName(), head.getModelId());
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		MenuAction action = event.getMenuAction();

		if (action == MenuAction.WIDGET_CONTINUE)
		{
			// Picking a patch from the farmer's dialogue list by clicking it.
			Widget widget = event.getWidget();
			if (widget != null && widget.getId() == InterfaceID.Chatmenu.OPTIONS
				&& widget.getIndex() > -1 && isPatchOption(widget.getText()))
			{
				// Child 0 is the "Select an Option" header.
				lastSelectedOption = widget.getIndex() - 1;
				lastSelectedText = null;
				lastSelectedTick = client.getTickCount();
			}
		}
		else if (isNpcOption(action) && event.getMenuOption().startsWith("Pay"))
		{
			// Some farmers expose their patches directly as right-click options instead.
			//
			// The text is kept as well as the position, and preferred, because the position is
			// a guess that the coral farmer disproves. Chet's menu reads
			//
			//     Pay (East) / Talk-to / Pay (West) / Trade
			//
			// so his two payments are not the third and fourth options, are not adjacent, and
			// the one that IS third is West. Under the old rule "Pay (East)" was not recorded
			// at all and "Pay (West)" was recorded against East - a wrong protection state
			// rather than a missing one, which is the worse of the two. Reported from play.
			//
			// Every NPC option is accepted now for the same reason: which op a Pay lands on is
			// the NPC's business, and there is no reading of it that the position can be
			// trusted for.
			lastSelectedText = event.getMenuOption();
			lastSelectedOption = positionOf(action);
			lastSelectedTick = client.getTickCount();
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

		if (componentId != InterfaceID.Chatmenu.OPTIONS || subId <= -1)
		{
			return;
		}

		Widget parent = client.getWidget(componentId);
		Widget option = parent == null ? null : parent.getChild(subId);
		if (option != null && isPatchOption(option.getText()))
		{
			lastSelectedOption = subId - 1;
			lastSelectedText = null;
			lastSelectedTick = client.getTickCount();
		}
	}

	private static boolean isPatchOption(@Nullable String name)
	{
		return name != null && (name.contains("Patch") || name.contains("allotment"));
	}

	/**
	 * Lines already reported, so a farmer talking is not a wall of log.
	 *
	 * <p>Unbounded in principle and tiny in practice: it only grows for dialogue said by a
	 * chathead this location's farmers answer to, which is a handful of sentences per farmer.
	 */
	private final Set<String> reportedUnmatched = new java.util.HashSet<>();

	/**
	 * Says when a farmer said something this class did not understand.
	 *
	 * <p>Only for a chathead that belongs to a farmer of a patch you are standing at, so it
	 * cannot fire for shopkeepers or quest dialogue — and only once per distinct line. This is
	 * the diagnostic that was missing: when a payment is not recorded there is currently no way
	 * to tell from a log whether the wording was new, and "I paid and it did not take" is not
	 * something anyone can debug after the fact.
	 */
	private void reportUnmatched(int npcId, @Nullable String line)
	{
		if (line == null || line.isEmpty() || findPatchForNpc(npcId) == null)
		{
			return;
		}
		if (reportedUnmatched.add(line))
		{
			log.debug("Farmer chathead {} said something not recognised as a payment: \"{}\"",
				npcId, line);
		}
	}

	/** Whether this NPC option is one of the five a right-click can carry. */
	private static boolean isNpcOption(MenuAction action)
	{
		return action == MenuAction.NPC_FIRST_OPTION
			|| action == MenuAction.NPC_SECOND_OPTION
			|| action == MenuAction.NPC_THIRD_OPTION
			|| action == MenuAction.NPC_FOURTH_OPTION
			|| action == MenuAction.NPC_FIFTH_OPTION;
	}

	/**
	 * The patch index this option position used to imply, or -1 where it implies nothing.
	 *
	 * <p>Kept only as a fallback for farmers whose Pay options do not name a patch. Third and
	 * fourth are the pair the old rule recognised, so those two keep their meaning and every
	 * other position now says "no idea" rather than silently meaning East.
	 */
	private static int positionOf(MenuAction action)
	{
		if (action == MenuAction.NPC_THIRD_OPTION)
		{
			return 0;
		}
		return action == MenuAction.NPC_FOURTH_OPTION ? 1 : -1;
	}

	/**
	 * Whether the last selection was this patch.
	 *
	 * <p>The option's own words first: a patch's {@code name} is the disambiguator inside its
	 * region - "East", "North" - and a farmer who splits his payments into separate options
	 * spells the same word into them. That is a direct statement about which patch was paid
	 * for, where the position is an inference about menu layout.
	 *
	 * <p>Falls back to the position only when the text names <b>no</b> patch of this farmer's.
	 * Not when it merely fails to name <i>this</i> one: "Pay (East)" tells us the West patch
	 * was not chosen, and letting the position answer after that would be taking the weaker
	 * evidence over the stronger one and getting the opposite answer.
	 */
	private boolean chosen(FarmPatch patch)
	{
		if (namesAPatch(patch))
		{
			return names(patch);
		}
		return lastSelectedOption >= 0 && patch.getPatchNumber() == lastSelectedOption;
	}

	/** Whether the selected option names any patch this farmer tends. */
	private boolean namesAPatch(FarmPatch patch)
	{
		if (lastSelectedText == null || patch.getRegion() == null)
		{
			return false;
		}
		for (FarmPatch sibling : patch.getRegion().getPatches())
		{
			if (sibling.getFarmer() == patch.getFarmer() && names(sibling))
			{
				return true;
			}
		}
		return false;
	}

	private boolean names(FarmPatch patch)
	{
		String name = patch.getName();
		return lastSelectedText != null && !name.isEmpty()
			&& lastSelectedText.toLowerCase(java.util.Locale.ROOT)
				.contains(name.toLowerCase(java.util.Locale.ROOT));
	}

	@Nullable
	private FarmPatch findPatchForNpc(int npcId)
	{
		if (client.getLocalPlayer() == null)
		{
			return null;
		}

		// Two tiers, exact beating variant, rather than one running list where the last
		// candidate silently won. Fossil Island has three hardwood patches whose farmers are
		// three different NPCs that all happen to share the name "Squirrel" - FarmerVariants
		// treats them as three gardeners now, but before this a variant-table match on the name
		// fallback still made all three "the same farmer" as far as this method was concerned,
		// and whichever patch iterated last (West) kept overwriting `found`. Every payment at
		// Fossil Island was recorded against West. Reported from play, all three squirrels paid
		// and only one patch ever reading protected.
		java.util.List<FarmPatch> exact = new java.util.ArrayList<>();
		java.util.List<FarmPatch> variant = new java.util.ArrayList<>();
		for (FarmRegion region : FarmingWorldData.getRegionsForLocation(client.getLocalPlayer().getWorldLocation()))
		{
			for (FarmPatch patch : region.getPatches())
			{
				// Through the variant table, not an exact compare: a farmer can be several
				// ids and the world data holds only one. The coral farmer is 15061 there and
				// 15063 in the world once the nurseries are unlocked, so an exact compare
				// matched nothing and paying Chet was never recorded. See FarmerVariants.
				if (!com.dooglemaps.data.FarmerVariants.same(patch.getFarmer(), npcId))
				{
					continue;
				}
				// patchNumber is only meaningful for farmers tending more than one patch;
				// for the rest, matching the NPC is enough. The multi-patch match also
				// demands a fresh selection - see lastSelectedTick.
				int age = client.getTickCount() - lastSelectedTick;
				boolean fresh = age >= 0 && age <= SELECTION_FRESH_TICKS;
				if (patch.getPatchNumber() == -1 || (fresh && chosen(patch)))
				{
					(patch.getFarmer() == npcId ? exact : variant).add(patch);
				}
			}
		}

		// Exact id matches beat variant matches outright - the world data's own id is the
		// stronger claim, and letting it win is what tells Chet's exact-id coral entries apart
		// from a variant match that only shares a name.
		java.util.List<FarmPatch> winners = exact.isEmpty() ? variant : exact;
		if (winners.size() > 1)
		{
			// Two patches of equal strength both claim this chathead - the same failure mode
			// as before, just refused instead of resolved by iteration order. A missed record
			// shows up as "pay the farmer" reappearing, which is visible; a wrong one never is.
			log.info("Chathead {} matched {} patches at equal strength and none could be "
					+ "preferred over the others ({}) - refusing to guess which was paid. See "
					+ "FarmerVariants.",
				npcId, winners.size(), displayNames(winners));
			return null;
		}
		return winners.isEmpty() ? null : winners.get(0);
	}

	private static String displayNames(java.util.List<FarmPatch> patches)
	{
		StringBuilder names = new StringBuilder();
		for (FarmPatch patch : patches)
		{
			if (names.length() > 0)
			{
				names.append(", ");
			}
			names.append(patch.getDisplayName());
		}
		return names.toString();
	}
}
