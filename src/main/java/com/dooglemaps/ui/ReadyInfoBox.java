package com.dooglemaps.ui;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.bank.LoadoutItem;
import com.dooglemaps.bank.RunLoadout;
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
	private final RunLoadout loadout;
	private final RunPlanner planner;

	private int readyCount;
	private int problemCount;
	private int withdrawCount;

	public ReadyInfoBox(BufferedImage image, Plugin plugin, DoogleMapsPanel panel,
		DoogleMapsConfig config, RunLoadout loadout, RunPlanner planner)
	{
		super(image, plugin);
		this.panel = panel;
		this.config = config;
		this.loadout = loadout;
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

			String label = projection.getPatch().getDisplayName() + " - " + projection.getProduce().getName();
			if (projection.getConfidence() == Confidence.NEEDS_ACTION)
			{
				problems.add(label + " (" + projection.getCropState().name().toLowerCase() + ")");
			}
			else if (projection.isReady())
			{
				ready.add(label);
			}
		}

		List<String> withdraw = toWithdraw();

		readyCount = ready.size();
		problemCount = problems.size();
		withdrawCount = withdraw.size();
		setTooltip(buildTooltip(ready, problems, withdraw));
	}

	/**
	 * What the run still wants out of the bank, with counts.
	 *
	 * <p>Answers the question you have while standing at the bank, which the sidebar could
	 * already answer and the in-game display could not: not "is anything ready" but <i>how many
	 * of what do I take</i>. The counts are {@code outstanding} rather than the run's total, so
	 * the list shortens as you withdraw and comes back if you put something down.
	 *
	 * <p>Safe from any thread, which is this class's whole constraint: {@code forRun} reads the
	 * stores through their own locks and touches the client only for the tick count it caches
	 * on. Nothing here asserts a thread.
	 */
	private List<String> toWithdraw()
	{
		List<String> items = new ArrayList<>();
		for (LoadoutItem item : loadout.forRun(planner.coveredTypes()))
		{
			if (item.getNeed() != LoadoutItem.Need.WITHDRAW)
			{
				continue;
			}
			// A count only where one is a decision. "Bronze axe x1" is worse than "Bronze axe".
			items.add(item.getOutstanding() > 1
				? item.getName() + " x" + item.getOutstanding()
				: item.getName());
		}
		return items;
	}

	private String buildTooltip(List<String> ready, List<String> problems, List<String> withdraw)
	{
		if (ready.isEmpty() && problems.isEmpty() && withdraw.isEmpty())
		{
			return "Doogle Maps</br>Nothing ready.";
		}

		StringBuilder text = new StringBuilder("Doogle Maps");

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
		// Having something to fetch counts as worth showing. The setting means "do not sit there
		// at zero all week", and the moment before a run is exactly when nothing is ready and the
		// box has the most to say - hiding it there would take the list away at the bank.
		return !config.readyInfoboxOnlyWhenReady()
			|| readyCount > 0 || problemCount > 0 || withdrawCount > 0;
	}
}
