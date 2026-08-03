package com.dooglemaps.ui;

import com.dooglemaps.DoogleMapsConfig;
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

	private int readyCount;
	private int problemCount;

	public ReadyInfoBox(BufferedImage image, Plugin plugin, DoogleMapsPanel panel, DoogleMapsConfig config)
	{
		super(image, plugin);
		this.panel = panel;
		this.config = config;
		setPriority(net.runelite.client.ui.overlay.infobox.InfoBoxPriority.LOW);
	}

	/** Recomputes counts and tooltip. Call from the client thread, not the EDT. */
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

		readyCount = ready.size();
		problemCount = problems.size();
		setTooltip(buildTooltip(ready, problems));
	}

	private String buildTooltip(List<String> ready, List<String> problems)
	{
		if (ready.isEmpty() && problems.isEmpty())
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
		return !config.readyInfoboxOnlyWhenReady() || readyCount > 0 || problemCount > 0;
	}
}
