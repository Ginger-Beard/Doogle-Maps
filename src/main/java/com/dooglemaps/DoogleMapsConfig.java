package com.dooglemaps;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

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

	@ConfigSection(
		name = "Patch types",
		description = "Which patch types get a tab in the sidebar. Turning one off hides its "
			+ "tab and its icon entirely, and leaves its patches out of the summary.",
		position = 2,
		closedByDefault = true
	)
	String patchTypeSection = "patchTypes";

	@ConfigSection(
		name = "Guided run",
		description = "Highlighting what to click at each patch while a run is under way",
		position = 3,
		closedByDefault = true
	)
	String guideSection = "guide";

	@ConfigSection(
		name = "Maintenance",
		description = "Clearing cached data, and development aids",
		position = 4,
		closedByDefault = true
	)
	String resetSection = "reset";

	/**
	 * How a highlighted object is drawn.
	 *
	 * <p>The same two choices Quest Helper offers for objects, and named the same way, because
	 * this is the interaction people already know and a second vocabulary for it would only be
	 * a thing to learn.
	 */
	enum GuideHighlightStyle
	{
		OUTLINE,
		CLICK_BOX,
		NONE
	}

	/**
	 * Keys for the two maintenance triggers.
	 *
	 * <p>Named constants because the plugin has to match on them in {@code onConfigChanged},
	 * and a key spelt in two places is a key that can be renamed in one. The failure would be
	 * silent: the switch would still flip, and nothing would happen.
	 */
	String RESET_PROFILE_KEY = "resetProfile";
	String CLEAR_HARVEST_STATS_KEY = "clearHarvestStats";

	/**
	 * A trigger dressed as a toggle.
	 *
	 * <p>RuneLite's config annotations have no button type, so an action has to be offered as
	 * something that can be switched on. The plugin performs the reset and immediately turns
	 * it back off, so it never sits enabled — {@code warning} makes the client ask first,
	 * which is what a one-click wipe deserves.
	 */
	@ConfigItem(
		keyName = RESET_PROFILE_KEY,
		name = "Reset this account's data",
		description = "Forget every cached patch, seed count, learned bank and patch position "
			+ "for the logged-in account. Kept: your settings, your harvest statistics, which "
			+ "patches you have shown or hidden, and the seeds picked for your run. Everything "
			+ "else comes back as you play.",
		warning = "This clears every patch state, seed count and learned location Doogle Maps "
			+ "has cached for this account.\n\nNOT affected: your settings, your harvest "
			+ "statistics, your shown/hidden patch toggles, and your run seed selection. The "
			+ "rest is relearned simply by visiting patches again.\n\nContinue?",
		position = 0,
		section = resetSection
	)
	default boolean resetProfile()
	{
		return false;
	}

	/**
	 * Kept apart from the profile reset on purpose.
	 *
	 * <p>{@code resetProfile} clears what the plugin <i>worked out</i>, all of which comes back
	 * by playing — which is what makes it safe to offer. The harvest history does not: nothing
	 * rebuilds it. Folding the two into one button would mean either losing the history to a
	 * click meant for stale patch state, or never being able to clear it at all.
	 */
	@ConfigItem(
		keyName = CLEAR_HARVEST_STATS_KEY,
		name = "Clear harvest history",
		description = "Delete the record of what your patches have actually given you - the "
			+ "totals, averages and prediction accuracy shown under the run section. Nothing "
			+ "else is touched, and unlike the rest of the plugin's data this does not come "
			+ "back by playing.",
		warning = "This deletes your whole harvest history for this account: every crop total, "
			+ "average and best/worst patch.\n\nUnlike a profile reset, none of this comes back "
			+ "by playing - it is a record of things that already happened.\n\nContinue?",
		position = 1,
		section = resetSection
	)
	default boolean clearHarvestStats()
	{
		return false;
	}

	@ConfigItem(
		keyName = "guidedMode",
		name = "Guide me through a run",
		description = "While a run is under way, highlight the patch, item or leprechaun to "
			+ "click next, one step at a time. The plugin never clicks anything itself.",
		position = 0,
		section = guideSection
	)
	default boolean guidedMode()
	{
		return true;
	}

	@ConfigItem(
		keyName = "highlightBankItems",
		name = "Highlight run items in the bank",
		description = "When the bank is open, mark the items this run still needs - seeds, "
			+ "protection payments, teleports you own, and your storage items.",
		position = 1,
		section = guideSection
	)
	default boolean highlightBankItems()
	{
		return true;
	}

	@ConfigItem(
		keyName = "filterBankToRun",
		name = "Filter the bank to this run",
		description = "Hide everything in the bank except what the run needs. Off by default and "
			+ "deliberately so: a wrong highlight is ignorable, but a wrong filter hides things "
			+ "and you cannot see what is missing. Highlighting works either way.",
		position = 2,
		section = guideSection
	)
	default boolean filterBankToRun()
	{
		return false;
	}

	@ConfigItem(
		keyName = "barbarianFarmingOverride",
		name = "Barbarian farming",
		description = "You have Otto Godblessed's Barbarian Farming, so seeds go in without a "
			+ "dibber. The plugin works this out by itself the first time it watches you plant "
			+ "without one - tick this to say so up front rather than waiting for that.",
		position = 2,
		section = guideSection
	)
	default boolean barbarianFarmingOverride()
	{
		return false;
	}

	@ConfigItem(
		keyName = "separateProtectedHerbs",
		name = "Separate protected herb patches",
		description = "List the herb patches that cannot catch a disease as their own category, "
			+ "so you can pick a different seed for them. Only the ones you have actually "
			+ "unlocked appear - Trollheim, Weiss, Hosidius and Harmony are each detected from "
			+ "the quest or diary that makes them safe.",
		position = 3,
		section = guideSection
	)
	default boolean separateProtectedHerbs()
	{
		return true;
	}

	@ConfigItem(
		keyName = "fortisColosseumChampion",
		name = "Colosseum Champion (Fortis herb patch)",
		description = "Champion status in the Fortis Colosseum, which needs 16,000 Glory, makes "
			+ "the Civitas illa Fortis herb patch disease-free. Unlike the other four this cannot "
			+ "be detected - the client exposes no varbit for it - so tick it yourself and the "
			+ "patch joins the protected list.",
		position = 4,
		section = guideSection
	)
	default boolean fortisColosseumChampion()
	{
		return false;
	}

	@ConfigItem(
		keyName = "guideHighlightStyle",
		name = "Highlight style",
		description = "How the patch and the leprechaun are marked",
		position = 2,
		section = guideSection
	)
	default GuideHighlightStyle guideHighlightStyle()
	{
		return GuideHighlightStyle.OUTLINE;
	}

	@ConfigItem(
		keyName = "guideHighlightColour",
		name = "Highlight colour",
		description = "The colour of the outline and of the inventory item marker",
		position = 3,
		section = guideSection
	)
	default java.awt.Color guideHighlightColour()
	{
		// Cyan rather than Quest Helper's own colour, so that running both at once does not
		// leave you unable to tell which plugin is asking for what.
		return new java.awt.Color(0x3F, 0xC1, 0xC9);
	}

	@ConfigItem(
		keyName = "guideLeprechaunColour",
		name = "Leprechaun item colour",
		description = "Colour for bank items the tool leprechaun already stores - compost and "
			+ "tools. Marked so you know not to take them, and to ask at the patch instead.",
		position = 4,
		section = guideSection
	)
	default java.awt.Color guideLeprechaunColour()
	{
		// Amber against the withdraw colour's cyan: the two mean opposite things, so they have
		// to be tellable apart at a glance rather than merely different.
		return new java.awt.Color(0xC8, 0xA2, 0x2D);
	}

	@Range(min = 0, max = 20)
	@ConfigItem(
		keyName = "guideOutlineThickness",
		name = "Outline thickness",
		description = "How heavy the outline around the target is",
		position = 5,
		section = guideSection
	)
	default int guideOutlineThickness()
	{
		return 2;
	}

	@Range(min = 0, max = 4)
	@ConfigItem(
		keyName = "guideOutlineFeathering",
		name = "Outline feathering",
		description = "How far the outline fades out at its edge",
		position = 6,
		section = guideSection
	)
	default int guideOutlineFeathering()
	{
		return 4;
	}

	@ConfigItem(
		keyName = "probeGeomancy",
		name = "Dump the Geomancy interface",
		description = "Development aid. When you cast Geomancy, write its whole interface — "
			+ "plus everything the plugin already knows about your patches — to "
			+ "~/.runelite/doogle-maps/geomancy-<time>.tsv, so the two can be matched up. "
			+ "Nothing is sent anywhere.",
		position = 2,
		section = resetSection
	)
	default boolean probeGeomancy()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showHerb",
		name = "Herb",
		description = "Show the herb tab in the sidebar",
		position = 0,
		section = patchTypeSection
	)
	default boolean showHerb()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showAllotment",
		name = "Allotment",
		description = "Show the allotment tab in the sidebar",
		position = 1,
		section = patchTypeSection
	)
	default boolean showAllotment()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showFlower",
		name = "Flower",
		description = "Show the flower tab in the sidebar",
		position = 2,
		section = patchTypeSection
	)
	default boolean showFlower()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showHops",
		name = "Hops",
		description = "Show the hops tab in the sidebar",
		position = 3,
		section = patchTypeSection
	)
	default boolean showHops()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showBush",
		name = "Bush",
		description = "Show the bush tab in the sidebar",
		position = 4,
		section = patchTypeSection
	)
	default boolean showBush()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showTree",
		name = "Tree",
		description = "Show the tree tab in the sidebar",
		position = 5,
		section = patchTypeSection
	)
	default boolean showTree()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showFruitTree",
		name = "Fruit tree",
		description = "Show the fruit tree tab in the sidebar",
		position = 6,
		section = patchTypeSection
	)
	default boolean showFruitTree()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showHardwoodTree",
		name = "Hardwood tree",
		description = "Show the hardwood tree tab in the sidebar",
		position = 7,
		section = patchTypeSection
	)
	default boolean showHardwoodTree()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showGrapes",
		name = "Grape",
		description = "Show the grape tab in the sidebar",
		position = 8,
		section = patchTypeSection
	)
	default boolean showGrapes()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showCactus",
		name = "Cactus",
		description = "Show the cactus tab in the sidebar",
		position = 9,
		section = patchTypeSection
	)
	default boolean showCactus()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showCalquat",
		name = "Calquat",
		description = "Show the calquat tab in the sidebar",
		position = 10,
		section = patchTypeSection
	)
	default boolean showCalquat()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showCelastrus",
		name = "Celastrus",
		description = "Show the celastrus tab in the sidebar",
		position = 11,
		section = patchTypeSection
	)
	default boolean showCelastrus()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showRedwood",
		name = "Redwood",
		description = "Show the redwood tab in the sidebar",
		position = 12,
		section = patchTypeSection
	)
	default boolean showRedwood()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showSpiritTree",
		name = "Spirit tree",
		description = "Show the spirit tree tab in the sidebar",
		position = 13,
		section = patchTypeSection
	)
	default boolean showSpiritTree()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showCrystalTree",
		name = "Crystal tree",
		description = "Show the crystal tree tab in the sidebar",
		position = 14,
		section = patchTypeSection
	)
	default boolean showCrystalTree()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showSeaweed",
		name = "Giant seaweed",
		description = "Show the giant seaweed tab in the sidebar",
		position = 15,
		section = patchTypeSection
	)
	default boolean showSeaweed()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showCoral",
		name = "Coral",
		description = "Show the coral tab in the sidebar",
		position = 16,
		section = patchTypeSection
	)
	default boolean showCoral()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showMushroom",
		name = "Mushroom",
		description = "Show the mushroom tab in the sidebar",
		position = 17,
		section = patchTypeSection
	)
	default boolean showMushroom()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showBelladonna",
		name = "Belladonna",
		description = "Show the belladonna tab in the sidebar",
		position = 18,
		section = patchTypeSection
	)
	default boolean showBelladonna()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showHespori",
		name = "Hespori",
		description = "Show the hespori tab in the sidebar",
		position = 19,
		section = patchTypeSection
	)
	default boolean showHespori()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showAnima",
		name = "Anima",
		description = "Show the anima tab in the sidebar",
		position = 20,
		section = patchTypeSection
	)
	default boolean showAnima()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showCompost",
		name = "Compost bin",
		description = "Show the compost bin tab in the sidebar",
		position = 21,
		section = patchTypeSection
	)
	default boolean showCompost()
	{
		return true;
	}

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

	@ConfigItem(
		keyName = "logHarvests",
		name = "Log harvests for validation",
		description = "Record each finished patch to the client log and to "
			+ "~/.runelite/doogle-maps/harvests.csv, with the predicted yield and experience "
			+ "beside what you actually got. Nothing is sent anywhere.",
		position = 3,
		section = inGameSection
	)
	default boolean logHarvests()
	{
		return true;
	}
}
