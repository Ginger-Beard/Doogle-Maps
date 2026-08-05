package com.dooglemaps.guide;

import com.dooglemaps.DoogleMapsConfig;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.api.MenuAction;

/**
 * The instruction, on screen, where you are looking.
 *
 * <p>This used to be a block of rows in the sidebar, above the stop list. That was the wrong
 * place for it and play made it obvious: following a run means watching the patch, not the
 * sidebar, and an instruction you have to look away to read is one you stop reading. Quest
 * Helper puts its step on the game screen for the same reason, and this deliberately borrows the
 * shape — a small titled panel, draggable, that says the next thing and what follows it.
 *
 * <p>The sidebar keeps what it is good at: where the run goes, what it is worth, what to take
 * from the bank. Those are things you read while standing still. The step is not.
 *
 * <h2>What it shows</h2>
 *
 * <b>At a patch</b>: the current step in full, then the rest of the work at this stop, dimmed.
 * The follow-ups are what stop it feeling like being led one click at a time with no idea how
 * much is left — the same reason the sidebar version listed them.
 *
 * <p><b>Between stops</b>: how much of the run is left, and what Shortest Path says the route it
 * is drawing uses. Travelling is the longest part of a run and this panel used to go blank for
 * all of it, which meant the map had a line on it and the words were somewhere else entirely.
 *
 * <p>It draws nothing at all when no run is under way. An empty panel parked on the screen would
 * be one more thing to move out of the way.
 */
public class GuideStepOverlay extends OverlayPanel
{
	/**
	 * Widest the panel is allowed to get. It sizes to its content below this.
	 *
	 * <p>Fixed width was wasteful: "Travel to Catherby." is half the panel, and the empty half
	 * still covered the game. The cap matters because the longest lines here are instructions
	 * that genuinely want wrapping, and letting those set the width would put a very wide panel
	 * on screen for the sake of one sentence.
	 */
	private static final int MAX_WIDTH = 210;

	/** Below this the panel looks like a mistake rather than a label. */
	private static final int MIN_WIDTH = 120;

	/**
	 * Follow-up steps listed under the current one.
	 *
	 * <p>Three, which is enough to see the shape of the stop — note, buckets back, move on —
	 * without turning the panel into the full checklist it is meant to replace.
	 */
	private static final int MAX_FOLLOWING = 3;

	private final GuideTracker tracker;
	private final DoogleMapsConfig config;

	@Inject
	private GuideStepOverlay(GuideTracker tracker, DoogleMapsConfig config)
	{
		this.tracker = tracker;
		this.config = config;

		setPosition(OverlayPosition.TOP_LEFT);

		// The standard overlay menu, so it can be dragged, snapped and hidden like any other.
		// Worth having rather than fixing the position: where this sits depends on where the
		// player keeps their minimap and chat, and that is not a thing to have an opinion about.
		getMenuEntries().add(new OverlayMenuEntry(MenuAction.RUNELITE_OVERLAY_CONFIG,
			"Farm run step", "Doogle Maps"));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.guidedMode())
		{
			return null;
		}

		GuideStatus status = tracker.getStatus();
		if (!status.isRunning())
		{
			return null;
		}

		// The stop is named in the title rather than repeated in every line. While you are working
		// a patch the instruction says what to do but not where you are, which matters once a run
		// has visited three places and you are deciding whether this one is finished.
		rendered.clear();
		String title = title(status);
		rendered.add(title);
		panelComponent.getChildren().add(TitleComponent.builder()
			.text(title)
			.color(config.guideHighlightColour())
			.build());

		if (status.isTravelling())
		{
			appendTravel(status);
		}
		else
		{
			appendSteps(status.getSteps());
		}

		sizeToContent(graphics);
		return super.render(graphics);
	}

	/**
	 * "Farm run", plus where you are when that is known.
	 *
	 * <p>Only while at a stop. Between them the location is the destination, which the first line
	 * already gives — saying it twice would just be noise.
	 */
	private static String title(GuideStatus status)
	{
		return status.getLocation() == null
			? "Farm run"
			: "Farm run (" + status.getLocation() + ")";
	}

	/**
	 * Sizes the panel to its widest line, within bounds.
	 *
	 * <p>Measured against the graphics context's own font rather than assumed, because the panel
	 * is drawn in whatever RuneLite is using and a guess would be wrong on one of the two.
	 */
	private void sizeToContent(Graphics2D graphics)
	{
		int widest = MIN_WIDTH;
		java.awt.FontMetrics metrics = graphics.getFontMetrics();

		// Measured from the strings this frame rather than from the components, which do not
		// expose their text. Collected as they are added, so the two cannot drift.
		for (String text : rendered)
		{
			// Padding for the panel's own border and the gap a wrapped word needs.
			widest = Math.max(widest, metrics.stringWidth(text) + 14);
		}

		panelComponent.setPreferredSize(new Dimension(Math.min(widest, MAX_WIDTH), 0));
	}

	/** Every string added this frame, for {@link #sizeToContent}. */
	private final java.util.List<String> rendered = new java.util.ArrayList<>();

	/** Adds a line and remembers its text, so the panel can size itself to the widest. */
	private void line(String text, java.awt.Color colour)
	{
		rendered.add(text);
		panelComponent.getChildren().add(LineComponent.builder()
			.left(text)
			.leftColor(colour)
			.build());
	}

	/** The instruction for the patch in front of you, and what else is left here. */
	private void appendSteps(List<GuideStep> steps)
	{
		line(steps.get(0).getText(), java.awt.Color.WHITE);

		for (int i = 1; i < steps.size() && i <= MAX_FOLLOWING; i++)
		{
			line("then " + uncapitalise(steps.get(i).getText()), java.awt.Color.LIGHT_GRAY);
		}

		int hidden = steps.size() - 1 - MAX_FOLLOWING;
		if (hidden > 0)
		{
			line("+ " + hidden + " more here", java.awt.Color.GRAY);
		}
	}

	/**
	 * What is happening between stops, which is when this panel used to go blank.
	 *
	 * <p>Travelling is the longest part of a run and it was the one moment with nothing on
	 * screen — the map had a line on it and the words were in the sidebar, so following the route
	 * meant looking in a third place. Shortest Path already reports which transports the path it
	 * is drawing uses, so the panel can say it.
	 *
	 * <h2>Naming the destination</h2>
	 *
	 * The planner hands Shortest Path <b>every</b> outstanding stop and lets it route to whichever
	 * is cheapest — that is what removes the need for any tour ordering of our own — so which one
	 * it picked is decided inside another plugin. It turns out to report that back, so the
	 * destination can be named after all.
	 *
	 * <p>Only when it is unambiguous, though. When it is not, this falls back to "the next
	 * patches", because a confidently wrong place name sends someone across the map and is far
	 * worse than no name at all.
	 */
	private void appendTravel(GuideStatus status)
	{
		if (status.isAtBankLeg())
		{
			line("Collect your supplies.", java.awt.Color.WHITE);
		}
		else if (status.getDestination() != null)
		{
			line("Travel to " + status.getDestination() + ".", java.awt.Color.WHITE);
		}
		else
		{
			line("Travel to the next patches.", java.awt.Color.WHITE);
		}

		appendTravelItem(status.getTravelHint());

		line(status.getStopsRemaining() == 1
			? "1 stop left"
			: status.getStopsRemaining() + " stops left", java.awt.Color.LIGHT_GRAY);

		// Shortest Path's own wording for the route it is drawing. Absent when it is not
		// installed, or before it has found a path — both of which are ordinary, so there is no
		// message for either.
		List<String> transports = status.getTransports();
		for (int i = 0; i < transports.size() && i < MAX_FOLLOWING; i++)
		{
			line("via " + transports.get(i), config.guideHighlightColour());
		}

		if (transports.size() > MAX_FOLLOWING)
		{
			line("+ " + (transports.size() - MAX_FOLLOWING) + " more hops", java.awt.Color.GRAY);
		}
	}

	/**
	 * What to travel with, said in words next to the thing being outlined.
	 *
	 * <p>Silent when nothing owned reaches the destination — that is the ordinary case for most
	 * stops, since the teleport table is deliberately short, and "we have no suggestion" is not
	 * worth a line. The outline still has the portal nexus and the jewellery box to point at.
	 */
	private void appendTravelItem(TravelHint hint)
	{
		if (hint == null || !hint.hasItem())
		{
			return;
		}

		line(hint.getWhere() == TravelHint.Where.BANK
				? "Bank: " + hint.getItemName()
				: "Use your " + hint.getItemName().toLowerCase(),
			hint.getWhere() == TravelHint.Where.BANK
				? java.awt.Color.LIGHT_GRAY
				: config.guideHighlightColour());
	}

	/** Lower-cases a leading capital so "then" can continue a sentence rather than restart it. */
	private static String uncapitalise(String text)
	{
		if (text.length() < 2 || Character.isUpperCase(text.charAt(1)))
		{
			return text;
		}
		return Character.toLowerCase(text.charAt(0)) + text.substring(1);
	}
}
