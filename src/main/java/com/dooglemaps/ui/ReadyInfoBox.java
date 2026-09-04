package com.dooglemaps.ui;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.route.RunPlanner;
import com.dooglemaps.timer.Confidence;
import com.dooglemaps.timer.PatchProjection;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.infobox.InfoBox;

/**
 * A small in-game counter of patches ready to harvest, hover for the list.
 *
 * <p>The point is to notice a finished farm run without opening the sidebar, so the count
 * is the whole display and the detail lives in the tooltip.
 */
public class ReadyInfoBox extends InfoBox
{
	/** Patches listed by name before the tooltip switches to a bare count. */
	private static final int MAX_LISTED = 12;

	private final DoogleMapsPanel panel;
	private final DoogleMapsConfig config;
	private final RunPlanner planner;

	/** Whether the run is in its hespori gear phase, sampled by {@link #update()}. */
	private volatile boolean gearPhase;

	/** Told when the run has fallen back to weaker compost than was picked. */
	private final com.dooglemaps.guide.GuideTracker guideTracker;

	private int readyCount;
	private int problemCount;
	private int withdrawCount;

	public ReadyInfoBox(BufferedImage image, Plugin plugin, DoogleMapsPanel panel,
		DoogleMapsConfig config, RunPlanner planner,
		com.dooglemaps.guide.GuideTracker guideTracker)
	{
		super(image, plugin);
		this.guideTracker = guideTracker;
		this.panel = panel;
		this.config = config;
		this.planner = planner;
		setPriority(net.runelite.client.ui.overlay.infobox.InfoBoxPriority.LOW);
	}

	/**
	 * Recomputes the counts and the tooltip.
	 *
	 * <p>Called from <b>every</b> thread the plugin has: the client thread on the idle tick, the
	 * Swing thread whenever a seed is picked — {@code SeedSelectorPanel} toggles the selection
	 * straight off a mouse listener and the store fires its change listeners synchronously — and
	 * whichever thread happens to run a profile load.
	 *
	 * <p>So nothing here may touch an API that asserts a thread. Everything it reads goes through
	 * the stores' own locks, and {@code GrowthTimer.project} is deliberately pure arithmetic over a
	 * snapshot. This used to claim the client thread only, which was never true of its own caller —
	 * and a comment that is wrong is worse than none, because it is the one someone trusts when
	 * they add a {@code client.getVarbitValue} to the projection.
	 */
	public void update()
	{
		List<String> ready = new ArrayList<>();
		List<String> problems = new ArrayList<>();

		for (PatchProjection projection : panel.projectAvailable())
		{
			if (projection.isEmpty())
			{
				continue;
			}
			// Only what a run would actually visit: the count exists to say "a farm run is
			// worth starting", and a patch whose type is unticked — or a bush the run is
			// deliberately letting refill — is not a reason to go. Reported from play as the
			// counter showing every enabled patch. The sidebar's own summary deliberately
			// stays account-wide; this is the glanceable in-game number, and it should agree
			// with what Start run would do.
			//
			// Which includes the compost bins beside the allotments, and they are the case
			// where "would a run do this" is not the same question as "is it ticked": the
			// planner answers both halves for us here. Reported from play as a tree-only run
			// promising supercompost at Ardougne, where no stop would be made.
			if (!planner.selectedForRuns(projection.getPatch()))
			{
				continue;
			}

			String label = projection.getPatch().getDisplayName() + " - " + projection.getProduce().getName();
			if (projection.getConfidence() == Confidence.NEEDS_ACTION)
			{
				problems.add(label + " (" + projection.getCropState().name().toLowerCase() + ")");
			}
			else if (projection.isReady() && stillWorthAVisit(projection))
			{
				ready.add(label);
			}
		}

		List<String> withdraw = toWithdraw();

		readyCount = ready.size();
		problemCount = problems.size();
		withdrawCount = withdraw.size();
		// Sampled here rather than asked in render(), like everything else this box shows:
		// render() runs per frame on the paint thread, and isGearPhase walks the planner and
		// the growth stores. Volatile field, same as the counts.
		gearPhase = planner.isGearPhase();
		setTooltip(buildTooltip(ready, problems, withdraw));
	}

	/**
	 * Whether a grown patch still has anything on it, which is not the same as being grown.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"I'm all out of patches to do in this run and the infobox is saying I have the following
	 * ready (I don't, I just did them), all fruit tree patches, etceteria and rimmington berry
	 * patches"</i> — every one of them a fruit tree or a bush.
	 *
	 * <p>{@link PatchProjection#isReady()} answers "has it finished growing", and for a crop that
	 * <b>regrows</b> that stays true forever once it has: picking a fruit tree does not un-grow it.
	 * So a stripped bush and a bare fruit tree read as ready for the rest of their lives, and the
	 * count said a farm run was worth starting when there was nothing on any of them.
	 *
	 * <p>{@code hasProduceToPick} is the question that was wanted and it already existed —
	 * {@code RunPlanner.isActionable} has asked it of exactly this family since the picked-clean
	 * bushes were fixed. The infobox never got it.
	 *
	 * <p>Reported on a <b>harvest-only</b> run, which is where it is least defensible: there,
	 * picking is the only reason to go at all, so a patch with nothing on it is not merely
	 * mislabelled but the whole of the wrong answer. {@code isActionable} already says as much for
	 * that case — {@code hasProduceToPick() || needsHealthCheck()} is its harvest-only branch, word
	 * for word what this returns.
	 *
	 * <p>Scoped to the regrowing families rather than replacing the readiness test outright,
	 * because a crop that does not regrow cannot be in this state: pick a herb patch and it is
	 * empty, and empty is already skipped above. Anything else grown and un-picked — a tree
	 * waiting to be chopped, a stump waiting for a spade — keeps its place in a count that exists
	 * to say whether the trip is worth making. A health check counts for the same reason: it is
	 * the click the patch is waiting for.
	 */
	private static boolean stillWorthAVisit(PatchProjection projection)
	{
		if (!projection.regrows())
		{
			return true;
		}
		return projection.hasProduceToPick() || projection.needsHealthCheck();
	}

	/**
	 * Says on the infobox that the run is treating with weaker compost than was picked.
	 *
	 * <p>First, above the ready and withdraw lists, because it is the one line that says the
	 * run is not doing what the player configured. The chatbox says it once when it happens;
	 * this is the standing reminder for anyone who was not watching the chat at the time, and
	 * it names the fix rather than only the symptom.
	 */
	private void appendDowngrade(StringBuilder text)
	{
		com.dooglemaps.data.CompostTier using = guideTracker.compostDowngrade();
		if (using != null)
		{
			text.append("</br>Fallen back to ").append(using.getDisplayName().toLowerCase())
				.append("</br>Worth a compost bin run.");
		}
	}

	/**
	 * What the run still wants out of the bank, with counts.
	 *
	 * <p>Answers the question you have while standing at the bank, which the sidebar could
	 * already answer and the in-game display could not: not "is anything ready" but <i>how many
	 * of what do I take</i>. The counts are {@code outstanding} rather than the run's total, so
	 * the list shortens as you withdraw and comes back if you put something down.
	 *
	 * <p>Read off the published status rather than built here, which is the change review round
	 * d asked for: this used to call {@code loadout.forRun} itself, and {@code update()} runs on
	 * every store change from whichever thread fired it — so the one class built for a glance was
	 * taking the loadout's monitor and walking the planner from the EDT, the exact traffic
	 * {@code RunSnapshot} exists to remove one surface over. The status is sampled once a tick on
	 * the client thread, where the loadout build has already been paid for; the gating (active
	 * runs only, no teleports) moved with the building. See
	 * {@code GuideTracker.withdrawLines}.
	 */
	private List<String> toWithdraw()
	{
		return guideTracker.getStatus().getToWithdraw();
	}

	private String buildTooltip(List<String> ready, List<String> problems, List<String> withdraw)
	{
		if (ready.isEmpty() && problems.isEmpty() && withdraw.isEmpty())
		{
			StringBuilder idle = new StringBuilder("Doogle Maps");
			appendDowngrade(idle);
			return idle.length() > "Doogle Maps".length()
				? idle.toString()
				: "Doogle Maps</br>Nothing ready.";
		}

		StringBuilder text = new StringBuilder("Doogle Maps");
		appendDowngrade(text);

		if (!ready.isEmpty())
		{
			text.append("</br>Ready:");
			appendList(text, ready);
		}
		if (!problems.isEmpty())
		{
			text.append("</br>Needs attention:");
			appendList(text, problems);
		}
		// Last, because it is the only section that is about what to do next rather than about
		// what the patches are doing - and it is only ever non-empty while a run is being got
		// ready, so it does not push the other two down the rest of the time.
		if (!withdraw.isEmpty())
		{
			text.append("</br>To withdraw:");
			appendList(text, withdraw);
		}

		return text.toString();
	}

	private void appendList(StringBuilder text, List<String> entries)
	{
		int shown = Math.min(entries.size(), MAX_LISTED);
		for (int i = 0; i < shown; i++)
		{
			text.append("</br>  ").append(entries.get(i));
		}
		if (entries.size() > shown)
		{
			text.append("</br>  ...and ").append(entries.size() - shown).append(" more");
		}
	}

	@Override
	public String getText()
	{
		return problemCount > 0 ? readyCount + "!" : String.valueOf(readyCount);
	}

	@Override
	public Color getTextColor()
	{
		if (problemCount > 0)
		{
			return Confidence.NEEDS_ACTION.getColor();
		}
		return readyCount > 0 ? Confidence.CERTAIN.getColor() : Color.WHITE;
	}

	@Override
	public boolean render()
	{
		if (!config.showReadyInfobox())
		{
			return false;
		}
		// Hidden for the whole of the hespori engagement — gearing up, walking in, the fight
		// itself. The box is a farming glance ("what is ready, what to withdraw"), and every
		// word of it is about the run the player has deliberately set aside until the boss is
		// dead; a ready-count over a fight is noise at best and a misclick at worst. Requested
		// from play. The kill ends the phase, and the box comes back exactly when its withdraw
		// list becomes the swap-back leg's orders.
		if (gearPhase)
		{
			return false;
		}
		// Having something to fetch counts as worth showing. The setting means "do not sit there
		// at zero all week", and the moment before a run is exactly when nothing is ready and the
		// box has the most to say - hiding it there would take the list away at the bank.
		return !config.readyInfoboxOnlyWhenReady()
			|| readyCount > 0 || problemCount > 0 || withdrawCount > 0;
	}
}
