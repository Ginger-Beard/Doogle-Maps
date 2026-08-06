package com.dooglemaps.bank;

import com.dooglemaps.data.FarmRegion;
import com.dooglemaps.data.FarmingWorldData;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;

/**
 * Which item gets you to which farming region.
 *
 * <h2>Why this table is allowed to exist</h2>
 * A previous note rejected a teleport table outright, as <i>"exactly the kind of
 * hand-maintained data the rest of this plugin has avoided, and it would be wrong for anyone
 * whose unlocks differ"</i>. That objection was right about the <b>prescriptive</b> form —
 * a table saying "to reach Ardougne, bring X" is advice, and advice is wrong for anyone whose
 * account does not match whoever wrote it.
 *
 * <p>This is the inverse, and it is not advice. <b>An Ardougne cloak teleports to Ardougne</b>
 * is a fact about the cloak. It does not vary by player and cannot be wrong about them. What
 * makes it useful is the intersection: cross it with the stops a run actually has, and with
 * what is actually in the bank, and the answer becomes "you own this and today's run goes
 * there" — which is personal, correct, and never tells an ironman to go and buy something.
 *
 * <p>Same principle the plugin already uses for secateurs, the cape and the outfit: detect
 * what is there, do not prescribe what ought to be.
 *
 * <h2>Scope</h2>
 * The herb-run entries are the OSRS Wiki's own per-patch recommendations. The rest are the
 * obvious ones for the other patch families. Deliberately incomplete rather than padded with
 * half-remembered ones: a missing entry costs a suggestion, a wrong entry sends someone to the
 * wrong side of the map.
 */
public final class TeleportItems
{
	/** One way of getting somewhere, and what it is called when offered. */
	public static final class Teleport
	{
		private final int itemId;
		private final String name;
		private final int regionId;
		private final boolean teleportsYou;

		Teleport(int itemId, String name, int regionId)
		{
			this(itemId, name, regionId, true);
		}

		Teleport(int itemId, String name, int regionId, boolean teleportsYou)
		{
			this.itemId = itemId;
			this.name = name;
			this.regionId = regionId;
			this.teleportsYou = teleportsYou;
		}

		/**
		 * Whether clicking this is the teleport, as opposed to merely allowing one.
		 *
		 * <p>The distinction only matters for the universal entries, and it is the difference
		 * between an instruction that works and one that cannot be followed. A house tablet is a
		 * thing to click; a Dramen staff is a thing to be holding when you click a fairy ring.
		 * Telling someone to "use your Dramen staff" to travel would be nonsense.
		 */
		public boolean teleportsYou()
		{
			return teleportsYou;
		}

		public int getItemId()
		{
			return itemId;
		}

		public String getName()
		{
			return name;
		}

		public int getRegionId()
		{
			return regionId;
		}
	}

	private static final List<Teleport> ALL = new ArrayList<>();

	/** Region id to the teleports that serve it. */
	private static final Map<Integer, List<Teleport>> BY_REGION = new LinkedHashMap<>();

	/**
	 * Registers a teleport against a region, and against every other region of the same place.
	 *
	 * <p>Seven farming locations span two map regions — Ardougne, Catherby, Falador, the
	 * Farming Guild, Kourend, Lumbridge and Morytania. Registering one id by hand covered
	 * whichever half happened to be typed, so a run stopping in the other got no teleport
	 * suggestion at all: five of the seven were wrong that way.
	 *
	 * <p>Deriving the pairs from {@link FarmingWorldData} rather than adding five more numbers
	 * means the next multi-region patch is covered without anyone noticing it needs to be.
	 */
	private static void add(int itemId, String name, int regionId)
	{
		add(new Teleport(itemId, name, regionId));
	}

	private static void add(Teleport teleport)
	{
		int regionId = teleport.getRegionId();
		ALL.add(teleport);

		for (int region : regionsSharingPlaceWith(regionId))
		{
			BY_REGION.computeIfAbsent(region, k -> new ArrayList<>()).add(teleport);
		}
	}

	/**
	 * Every region id belonging to the same named place, including the one given.
	 *
	 * <p>Matched on the region's <i>name</i>, which is what makes "Catherby" one place rather
	 * than two numbers. The universal entries use -1, which belongs to no place and is
	 * returned unchanged.
	 */
	private static Set<Integer> regionsSharingPlaceWith(int regionId)
	{
		Set<Integer> regions = new LinkedHashSet<>();
		regions.add(regionId);

		String name = null;
		for (FarmRegion region : FarmingWorldData.getRegions())
		{
			if (region.getRegionId() == regionId)
			{
				name = region.getName();
				break;
			}
		}

		if (name != null)
		{
			for (FarmRegion region : FarmingWorldData.getRegions())
			{
				if (name.equals(region.getName()))
				{
					regions.add(region.getRegionId());
				}
			}
		}
		return regions;
	}

	static
	{
		// --- Herb patches. These nine are the wiki's own recommendations, patch by patch.
		// Ardougne cloak teleports from the medium diary up; the easy one does not, so it is
		// deliberately absent rather than listed and qualified.
		add(ItemID.ARDY_CAPE_MEDIUM, "Ardougne cloak 2", 10548);
		add(ItemID.ARDY_CAPE_HARD, "Ardougne cloak 3", 10548);
		add(ItemID.ARDY_CAPE_ELITE, "Ardougne cloak 4", 10548);

		add(ItemID.LUNAR_TABLET_CATHERBY_TELEPORT, "Catherby teleport tablet", 11062);

		// Likewise the explorer's ring: ring 1 has no teleport.
		add(ItemID.LUMBRIDGE_RING_MEDIUM, "Explorer's ring 2", 12083);
		add(ItemID.LUMBRIDGE_RING_HARD, "Explorer's ring 3", 12083);
		add(ItemID.LUMBRIDGE_RING_ELITE, "Explorer's ring 4", 12083);

		add(ItemID.TELETAB_HARMONY, "Harmony Island teleport tablet", 15148);
		add(ItemID.XERIC_TALISMAN, "Xeric's talisman", 6967);
		add(ItemID.STRONGHOLD_TELEPORT_BASALT, "Stony basalt", 11321);
		add(ItemID.WEISS_TELEPORT_BASALT, "Icy basalt", 11325);
		add(ItemID.POH_TABLET_FORTISTELEPORT, "Civitas illa Fortis tablet", 6192);

		// The Farming cape is two things at once: the +5% herb bonus and a free teleport to
		// the guild. Worth taking on a herb run whether or not the guild is on it.
		add(ItemID.SKILLCAPE_FARMING, "Farming cape", 4922);
		add(ItemID.SKILLCAPE_FARMING_TRIMMED, "Farming cape (t)", 4922);
		for (int necklace : new int[]{
			ItemID.JEWL_NECKLACE_OF_SKILLS_6, ItemID.JEWL_NECKLACE_OF_SKILLS_5,
			ItemID.JEWL_NECKLACE_OF_SKILLS_4, ItemID.JEWL_NECKLACE_OF_SKILLS_3,
			ItemID.JEWL_NECKLACE_OF_SKILLS_2, ItemID.JEWL_NECKLACE_OF_SKILLS_1,
		})
		{
			add(necklace, "Skills necklace", 4922);
		}

		// --- Elsewhere, for the runs that are not herb runs.
		add(ItemID.ECTOPHIAL, "Ectophial", 14391);
		add(ItemID.MM2_ROYAL_SEED_POD, "Royal seed pod", 9781);
		add(ItemID.POH_TABLET_FALADORTELEPORT, "Falador teleport tablet", 11828);
		add(ItemID.POH_TABLET_VARROCKTELEPORT, "Varrock teleport tablet", 12854);
		add(ItemID.POH_TABLET_LUMBRIDGETELEPORT, "Lumbridge teleport tablet", 12851);
		add(ItemID.POH_TABLET_CAMELOTTELEPORT, "Camelot teleport tablet", 10551);
		add(ItemID.POH_TABLET_ARDOUGNETELEPORT, "Ardougne teleport tablet", 10290);
		add(ItemID.NZONE_TELETAB_TAVERLEY, "Taverley teleport tablet", 11573);
		add(ItemID.NZONE_TELETAB_RIMMINGTON, "Rimmington teleport tablet", 11570);

		// A fairy ring reaches a good many patches, but only with a staff in hand. Flagged as not
		// being the teleport itself: it is what lets you use one.
		for (int staff : new int[]{
			ItemID.DRAMEN_STAFF, ItemID.DRAMEN_STAFF_AIR,
			ItemID.DRAMEN_STAFF_FIRE, ItemID.DRAMEN_STAFF_WATER,
		})
		{
			add(new Teleport(staff, "Dramen staff (fairy rings)", -1, false));
		}

		// --- Your house, which is a destination in its own right on the way to somewhere else.
		//
		// It reaches no farming patch directly, which is why it took a report from play to notice
		// it was missing: the table is organised by what a teleport *lands next to*, and by that
		// rule the house belongs nowhere. But the portal nexus and the jewellery box are both in
		// it, both reach farming patches, and both are already highlighted once you are standing
		// there — so the tablet is the step before a chain that was otherwise built and
		// unreachable.
		//
		// Universal rather than per-region for the same reason the Dramen staff is: what it can
		// ultimately reach depends on the player's own nexus attunements and jewellery, which is
		// their business and not something to guess at.
		add(ItemID.POH_TABLET_TELEPORTTOHOUSE, "Teleport to house tablet", -1);
		add(ItemID.SKILLCAPE_CONSTRUCTION, "Construction cape", -1);
		add(ItemID.SKILLCAPE_CONSTRUCTION_TRIMMED, "Construction cape (t)", -1);
	}

	private TeleportItems()
	{
	}

	/**
	 * Teleports that reach this region.
	 *
	 * <p>Empty for a region nothing in the table serves, which is the honest answer for most
	 * of them — see the scope note on the class.
	 */
	public static List<Teleport> forRegion(int regionId)
	{
		return Collections.unmodifiableList(
			BY_REGION.getOrDefault(regionId, Collections.emptyList()));
	}

	/**
	 * Teleports not tied to a single place, so they are worth offering on any run.
	 *
	 * <p>Only the fairy-ring staff today. Recorded as a region of -1 rather than repeated
	 * against every region it can reach, because the ring codes are the player's business.
	 */
	public static List<Teleport> universal()
	{
		return forRegion(-1);
	}

	/**
	 * Every teleport this table knows, by name, as a comma-separated list.
	 *
	 * <p>The default for the teleport list setting. Derived rather than typed out, so the setting
	 * starts as an honest snapshot of what the plugin already suggests — and so a teleport added
	 * to the table below turns up in the default without anyone having to remember to add it
	 * twice.
	 *
	 * <p>Sorted, because this is read and edited by a person and the table's own order is by
	 * region, which is meaningless once the regions are stripped off.
	 */
	public static String defaultNames()
	{
		java.util.Set<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		for (Teleport teleport : ALL)
		{
			names.add(teleport.getName());
		}
		return String.join(", ", names);
	}

}
