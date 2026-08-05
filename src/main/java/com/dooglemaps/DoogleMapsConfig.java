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
		name = "Locations",
		description = "Which places show their patches. A coarser cut than the per-patch switches "
			+ "on the rows themselves: turn off somewhere you never farm and it stops appearing "
			+ "on every tab at once, rather than being switched off a patch at a time.",
		position = 3,
		closedByDefault = true
	)
	String locationSection = "locations";

	@ConfigSection(
		name = "Guided run",
		description = "Highlighting what to click at each patch while a run is under way",
		position = 4,
		closedByDefault = true
	)
	String guideSection = "guide";

	@ConfigSection(
		name = "Maintenance",
		description = "Clearing cached data, and development aids",
		position = 5,
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

	/**
	 * Farming contracts, which are a suggestion rather than a rule.
	 *
	 * <p>On by default, because the reward is the highest-value thing in a run and the failure it
	 * prevents is expensive — planting something else in the one patch the contract needs costs a
	 * full growth cycle. Off is still a real answer: somebody may simply not want to do them, and
	 * the plugin should not insist.
	 */
	@ConfigItem(
		keyName = "guideFarmingContracts",
		name = "Include the farming contract",
		description = "Plant Guildmaster Jane's contract first at the Farming Guild, and offer to "
			+ "hand a finished one in and take the next before you leave. Turning this off leaves "
			+ "the guild's patches to the ordinary run.",
		position = 1,
		section = guideSection
	)
	default boolean guideFarmingContracts()
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
		keyName = "showLocationAlKharid",
		name = "Al Kharid",
		description = "Show the patches at Al Kharid",
		position = 0,
		section = locationSection
	)
	default boolean showLocationAlKharid()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationAldarin",
		name = "Aldarin",
		description = "Show the patches at Aldarin",
		position = 1,
		section = locationSection
	)
	default boolean showLocationAldarin()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationAnglersRetreat",
		name = "Anglers' Retreat",
		description = "Show the patches at Anglers' Retreat",
		position = 2,
		section = locationSection
	)
	default boolean showLocationAnglersRetreat()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationArdougne",
		name = "Ardougne",
		description = "Show the patches at Ardougne",
		position = 3,
		section = locationSection
	)
	default boolean showLocationArdougne()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationAuburnvale",
		name = "Auburnvale",
		description = "Show the patches at Auburnvale",
		position = 4,
		section = locationSection
	)
	default boolean showLocationAuburnvale()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationAviumSavannah",
		name = "Avium Savannah",
		description = "Show the patches at Avium Savannah",
		position = 5,
		section = locationSection
	)
	default boolean showLocationAviumSavannah()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationBrimhaven",
		name = "Brimhaven",
		description = "Show the patches at Brimhaven",
		position = 6,
		section = locationSection
	)
	default boolean showLocationBrimhaven()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationCatherby",
		name = "Catherby",
		description = "Show the patches at Catherby",
		position = 7,
		section = locationSection
	)
	default boolean showLocationCatherby()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationChampionsGuild",
		name = "Champions' Guild",
		description = "Show the patches at Champions' Guild",
		position = 8,
		section = locationSection
	)
	default boolean showLocationChampionsGuild()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationCivitasIllaFortis",
		name = "Civitas illa Fortis",
		description = "Show the patches at Civitas illa Fortis",
		position = 9,
		section = locationSection
	)
	default boolean showLocationCivitasIllaFortis()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationDraynorManor",
		name = "Draynor Manor",
		description = "Show the patches at Draynor Manor",
		position = 10,
		section = locationSection
	)
	default boolean showLocationDraynorManor()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationEntrana",
		name = "Entrana",
		description = "Show the patches at Entrana",
		position = 11,
		section = locationSection
	)
	default boolean showLocationEntrana()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationEtceteria",
		name = "Etceteria",
		description = "Show the patches at Etceteria",
		position = 12,
		section = locationSection
	)
	default boolean showLocationEtceteria()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationFalador",
		name = "Falador",
		description = "Show the patches at Falador",
		position = 13,
		section = locationSection
	)
	default boolean showLocationFalador()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationFarmingGuild",
		name = "Farming Guild",
		description = "Show the patches at Farming Guild",
		position = 14,
		section = locationSection
	)
	default boolean showLocationFarmingGuild()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationFossilIsland",
		name = "Fossil Island",
		description = "Show the patches at Fossil Island",
		position = 15,
		section = locationSection
	)
	default boolean showLocationFossilIsland()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationGnomeStronghold",
		name = "Gnome Stronghold",
		description = "Show the patches at Gnome Stronghold",
		position = 16,
		section = locationSection
	)
	default boolean showLocationGnomeStronghold()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationGreatConch",
		name = "Great Conch",
		description = "Show the patches at Great Conch",
		position = 17,
		section = locationSection
	)
	default boolean showLocationGreatConch()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationHarmony",
		name = "Harmony",
		description = "Show the patches at Harmony",
		position = 18,
		section = locationSection
	)
	default boolean showLocationHarmony()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationKastori",
		name = "Kastori",
		description = "Show the patches at Kastori",
		position = 19,
		section = locationSection
	)
	default boolean showLocationKastori()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationKourend",
		name = "Kourend",
		description = "Show the patches at Kourend",
		position = 20,
		section = locationSection
	)
	default boolean showLocationKourend()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationLletya",
		name = "Lletya",
		description = "Show the patches at Lletya",
		position = 21,
		section = locationSection
	)
	default boolean showLocationLletya()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationLumbridge",
		name = "Lumbridge",
		description = "Show the patches at Lumbridge",
		position = 22,
		section = locationSection
	)
	default boolean showLocationLumbridge()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationMorytania",
		name = "Morytania",
		description = "Show the patches at Morytania",
		position = 23,
		section = locationSection
	)
	default boolean showLocationMorytania()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationPortSarim",
		name = "Port Sarim",
		description = "Show the patches at Port Sarim",
		position = 24,
		section = locationSection
	)
	default boolean showLocationPortSarim()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationPrifddinas",
		name = "Prifddinas",
		description = "Show the patches at Prifddinas",
		position = 25,
		section = locationSection
	)
	default boolean showLocationPrifddinas()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationRimmington",
		name = "Rimmington",
		description = "Show the patches at Rimmington",
		position = 26,
		section = locationSection
	)
	default boolean showLocationRimmington()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationSeaweed",
		name = "Seaweed",
		description = "Show the patches at Seaweed",
		position = 27,
		section = locationSection
	)
	default boolean showLocationSeaweed()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationSeersVillage",
		name = "Seers' Village",
		description = "Show the patches at Seers' Village",
		position = 28,
		section = locationSection
	)
	default boolean showLocationSeersVillage()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationTaiBwoWannai",
		name = "Tai Bwo Wannai",
		description = "Show the patches at Tai Bwo Wannai",
		position = 29,
		section = locationSection
	)
	default boolean showLocationTaiBwoWannai()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationTaverley",
		name = "Taverley",
		description = "Show the patches at Taverley",
		position = 30,
		section = locationSection
	)
	default boolean showLocationTaverley()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationTreeGnomeVillage",
		name = "Tree Gnome Village",
		description = "Show the patches at Tree Gnome Village",
		position = 31,
		section = locationSection
	)
	default boolean showLocationTreeGnomeVillage()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationTrollStronghold",
		name = "Troll Stronghold",
		description = "Show the patches at Troll Stronghold",
		position = 32,
		section = locationSection
	)
	default boolean showLocationTrollStronghold()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationVarrock",
		name = "Varrock",
		description = "Show the patches at Varrock",
		position = 33,
		section = locationSection
	)
	default boolean showLocationVarrock()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationWeiss",
		name = "Weiss",
		description = "Show the patches at Weiss",
		position = 34,
		section = locationSection
	)
	default boolean showLocationWeiss()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLocationYanille",
		name = "Yanille",
		description = "Show the patches at Yanille",
		position = 35,
		section = locationSection
	)
	default boolean showLocationYanille()
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
