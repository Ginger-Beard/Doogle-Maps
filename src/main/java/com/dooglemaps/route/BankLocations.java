package com.dooglemaps.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

/**
 * Banks that every account can walk into.
 *
 * <p>Taken from the Shortest Path plugin's bank destination data, filtered to the ones with
 * no skill, quest or varbit requirement at all, one ground-floor tile each. See
 * ATTRIBUTION.md.
 *
 * <p>The filter is the point. Roughly half the banks in the game are gated — Canifis behind
 * <i>Priest in Peril</i>, the Crafting Guild behind 99 Crafting, the Sailing island chests
 * behind having built them — and working out which ones a given account has would mean
 * matching quest names against RuneLite's enum and keeping that in step forever. The spec
 * already rejected that approach for patch unlocks, for the same reason.
 *
 * <p>So this list is only the floor. {@link BankLocationStore} records every bank the player
 * actually opens, and those win: a gated bank becomes usable the moment you prove you can
 * use it, and the plugin can never route you somewhere you have not unlocked.
 */
final class BankLocations
{
	private static final List<Bank> SEEDED = new ArrayList<>();

	static
	{
		add("Al Kharid", 3269, 3164);
		add("Arceuus", 1624, 3741);
		add("Ardougne", 2655, 3280);
		add("Bank Boat", 2280, 2544);
		add("Barbarian Outpost", 2536, 3573);
		add("Blast Mine", 1479, 3857);
		add("Castle Wars", 2443, 3083);
		add("Catherby", 2807, 3441);
		add("Chambers of Xeric", 3333, 5194);
		add("Clan Hall", 1745, 5476);
		add("Daimon's Crater", 3420, 4058);
		add("Deepfin Point", 1935, 2754);
		add("Draynor Village", 3092, 3242);
		add("Edgeville", 3094, 3489);
		add("Emir's Arena", 3382, 3269);
		add("Falador", 3010, 3355);
		add("Farming Guild", 1253, 3741);
		add("Ferox Enclave", 3130, 3631);
		add("Fishing Guild", 2586, 3418);
		add("Grand Exchange", 3162, 3489);
		add("Hosidius", 1746, 3598);
		add("Hosidius Kitchen", 1674, 3615);
		add("Hosidius Sand Crabs", 1719, 3465);
		add("Hosidius Vinery", 1809, 3566);
		add("Land's End", 1512, 3421);
		add("Lovakengj", 1522, 3738);
		add("Mining Guild", 3013, 9718);
		add("Mor Ul Rek", 2543, 5141);
		add("Motherlode Mine", 3760, 5666);
		add("Mount Karuulm", 1324, 3824);
		add("Mount Quidamortem", 1254, 3571);
		add("Nardah", 3427, 2889);
		add("Ourania", 3013, 5625);
		add("Port Khazard", 2661, 3162);
		add("Port Piscarilius", 1796, 3790);
		add("Ruins of Unkah", 3156, 2835);
		add("Seers' Village", 2721, 3493);
		add("Shantay Pass", 3308, 3120);
		add("Shayzien", 1487, 3590);
		add("Shayzien Encampment", 1483, 3646);
		add("Soul Wars", 2212, 2859);
		add("Sulphur Mine", 1478, 3856);
		add("The Pandemonium", 3038, 3000);
		add("Varrock", 3251, 3420);
		add("Void Knights' Outpost", 2665, 2653);
		add("Warriors' Guild", 2843, 3543);
		add("Wintertodt Camp", 1640, 3944);
		add("Woodcutting Guild", 1592, 3476);
		add("Yanille", 2613, 3091);
	}

	/**
	 * The seed vault, which is the Farming Guild's and nowhere else's.
	 *
	 * <p>This is the bank chest's tile: the vault sits directly west of it, close enough
	 * that routing to one puts you at the other. Handy, because a trip for vault seeds also
	 * gets you a bank for payments.
	 */
	static final WorldPoint SEED_VAULT = new WorldPoint(1253, 3741, 0);

	private BankLocations()
	{
	}

	private static void add(String name, int x, int y)
	{
		SEEDED.add(new Bank(name, new WorldPoint(x, y, 0)));
	}

	static List<Bank> getSeeded()
	{
		return Collections.unmodifiableList(SEEDED);
	}

	/** A bank and where it is. */
	static final class Bank
	{
		final String name;
		final WorldPoint location;

		Bank(String name, WorldPoint location)
		{
			this.name = name;
			this.location = location;
		}
	}
}
