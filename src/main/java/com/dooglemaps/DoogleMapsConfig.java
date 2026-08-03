package com.dooglemaps;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(DoogleMapsConfig.GROUP)
public interface DoogleMapsConfig extends Config
{
	String GROUP = "dooglemaps";

	@ConfigSection(
		name = "Overview",
		description = "What the sidebar shows",
		position = 0
	)
	String overviewSection = "overview";

	@ConfigSection(
		name = "In-game",
		description = "Overlays drawn on the game screen",
		position = 1
	)
	String inGameSection = "inGame";

	@ConfigItem(
		keyName = "showTimers",
		name = "Show time remaining",
		description = "Show estimated time until each patch is ready, on the progress bar and its tooltip",
		position = 1,
		section = overviewSection
	)
	default boolean showTimers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "absoluteTime",
		name = "Show clock times",
		description = "Show \"ready at 14:32\" instead of \"ready in 1h 12m\"",
		position = 2,
		section = overviewSection
	)
	default boolean absoluteTime()
	{
		return false;
	}

	@ConfigItem(
		keyName = "sortProblemsFirst",
		name = "Problems first",
		description = "Sort diseased and dead patches to the top of each tab",
		position = 3,
		section = overviewSection
	)
	default boolean sortProblemsFirst()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hideEmptyPatches",
		name = "Hide empty patches",
		description = "Leave patches with nothing planted out of the overview",
		position = 4,
		section = overviewSection
	)
	default boolean hideEmptyPatches()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showStaleness",
		name = "Show how stale each row is",
		description = "Show when a patch's state was last confirmed, so you know how much to trust it",
		position = 5,
		section = overviewSection
	)
	default boolean showStaleness()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showReadyInfobox",
		name = "Ready infobox",
		description = "Show an infobox counting patches ready to harvest, hover it for the list",
		position = 1,
		section = inGameSection
	)
	default boolean showReadyInfobox()
	{
		return true;
	}

	@ConfigItem(
		keyName = "readyInfoboxOnlyWhenReady",
		name = "Hide infobox when nothing is ready",
		description = "Only show the infobox when at least one patch is ready to harvest",
		position = 2,
		section = inGameSection
	)
	default boolean readyInfoboxOnlyWhenReady()
	{
		return true;
	}
}
