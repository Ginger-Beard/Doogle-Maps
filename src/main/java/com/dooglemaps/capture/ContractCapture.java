package com.dooglemaps.capture;

import com.dooglemaps.data.Produce;
import com.dooglemaps.state.ContractState;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Follows a farming contract through its three moments: assigned, grown, handed in.
 *
 * <h2>Why this exists at all, when Time Tracking already watches for it</h2>
 *
 * Because the one moment that matters most is the one its config key cannot express.
 * {@code TimeTrackingPlugin} clears {@code timetracking.contract} on the completion message —
 * which the game sends when the crop finishes <i>growing</i>, not when the reward is collected. So
 * from the outside, "grown and unclaimed" is indistinguishable from "nothing assigned", and that is
 * exactly the state the guide needs to speak up in: the patch looks done, the run moves on, and the
 * seed packs sit in the guild until you next happen to be there.
 *
 * <p>Duplicating another plugin's parsing is how two plugins end up disagreeing, so this is kept as
 * narrow as it can be. It watches the same three lines Time Tracking does and writes them to
 * <i>this</i> plugin's keys; {@link ContractState} decides which source answers which question, and
 * still prefers Time Tracking's for the one thing Time Tracking reliably knows.
 *
 * <p>The lines themselves are facts about the game rather than code, and they are the same ones
 * {@code FarmingContractManager} matches on — credited in {@code ATTRIBUTION.md} along with
 * everything else mirrored from Time Tracking.
 */
@Slf4j
@Singleton
public class ContractCapture
{
	/**
	 * What Jane says when she assigns one.
	 *
	 * <p>Two phrasings, and the crop is named in prose rather than by item — which is why
	 * {@code Produce} carries a {@code contractName} distinct from its ordinary name.
	 */
	private static final Pattern ASSIGNED = Pattern.compile(
		"(?:We need you to grow|Please could you grow) (?:some|a|an) ([a-zA-Z ]+)(?: for us\\?|\\.)");

	/** What she says when she takes it back. */
	private static final String REWARDED = "You'll be wanting a reward then. Here you go.";

	/** What the game says when the crop finishes growing, wherever you happen to be. */
	private static final String COMPLETED =
		"You've completed a Farming Guild Contract. You should return to Guildmaster Jane.";

	private final Client client;
	private final ContractState contracts;

	/**
	 * The dialogue line already acted on, so a line that sits on screen for many ticks is
	 * handled once.
	 *
	 * <p>Both writes are idempotent, so repeating them would not corrupt anything — but each one
	 * fires the listeners, and those rebuild the tab strip. A dialogue box left open would have
	 * the sidebar rebuilding itself every tick.
	 */
	@Nullable
	private String lastHandled;

	@Inject
	ContractCapture(Client client, ContractState contracts)
	{
		this.client = client;
		this.contracts = contracts;
	}

	public void reset()
	{
		lastHandled = null;
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE
			|| !COMPLETED.equals(event.getMessage()))
		{
			return;
		}

		// Deliberately not conditioned on being in the guild. The crop finishes on its own clock
		// and this arrives wherever you are standing, which is the whole reason it is worth
		// catching: it is the only notice you get that the reward is now collectable.
		contracts.recordCompleted();
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		Widget text = client.getWidget(InterfaceID.ChatLeft.TEXT);
		if (text == null)
		{
			lastHandled = null;
			return;
		}

		Widget head = client.getWidget(InterfaceID.ChatLeft.HEAD);
		if (head == null || head.getModelId() != ContractState.GUILDMASTER_JANE)
		{
			return;
		}

		String line = Text.removeTags(text.getText());
		if (line.equals(lastHandled))
		{
			return;
		}
		lastHandled = line;

		if (REWARDED.equals(line))
		{
			contracts.recordHandedIn();
			return;
		}

		Matcher matcher = ASSIGNED.matcher(line);
		if (!matcher.find())
		{
			return;
		}

		Produce assigned = byContractName(matcher.group(1));
		if (assigned == null)
		{
			// A crop this build's data does not know, which means a game update added one. Worth
			// a line: the tab and the run option would otherwise be silently missing, and that
			// looks identical to the feature not working.
			log.warn("Guildmaster Jane asked for \"{}\", which is not a crop this build knows",
				matcher.group(1));
			return;
		}

		contracts.recordAssigned(assigned);
	}

	/**
	 * The produce Jane's wording refers to.
	 *
	 * <p>Matched on {@code contractName} rather than {@code name} because they genuinely differ —
	 * she asks for "cadava berries" where the produce is "Cadavaberry" — and case-insensitively,
	 * which is what core does and what a line of dialogue warrants.
	 */
	@Nullable
	private static Produce byContractName(String name)
	{
		for (Produce produce : Produce.values())
		{
			if (produce.getContractName().equalsIgnoreCase(name))
			{
				return produce;
			}
		}
		return null;
	}
}
