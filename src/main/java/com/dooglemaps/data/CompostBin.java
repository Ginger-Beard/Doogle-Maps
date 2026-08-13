package com.dooglemaps.data;

import net.runelite.api.gameval.ItemID;

/**
 * The numbers a compost bin runs on, per bin size.
 *
 * <p>Two sizes: the ordinary bin beside every allotment, and the Farming Guild's big one, which
 * is double in every respect but one — see {@link #ashNeeded()}.
 *
 * <p>Kept apart from {@link PatchImplementation}, which is generated, and stated as a table
 * rather than as constants scattered through the guide because the ash figures are the whole
 * reason the order of operations matters. See {@link #ashNeeded()}.
 */
public enum CompostBin
{
	/** Beside the allotments, at eight locations. */
	NORMAL(PatchImplementation.COMPOST, 15, 25),

	/** The Farming Guild's, which holds thirty. */
	BIG(PatchImplementation.BIG_COMPOST, 30, 50);

	private final PatchImplementation type;
	private final int capacity;
	private final int ash;

	CompostBin(PatchImplementation type, int capacity, int ash)
	{
		this.type = type;
		this.capacity = capacity;
		this.ash = ash;
	}

	public PatchImplementation getType()
	{
		return type;
	}

	/** How many un-noted items fill it, and therefore how many compost come out. */
	public int getCapacity()
	{
		return capacity;
	}

	/**
	 * Whether one inventory can hold a full fill for this bin.
	 *
	 * <h2>The big bin cannot be filled in a single trip, and that is a game fact</h2>
	 *
	 * A bin takes its items <b>un-noted</b>, and nothing on the supercompostable list stacks —
	 * fifteen pineapples is fifteen slots. The big bin wants thirty, against an inventory of
	 * twenty-eight, so a supercompost fill for it is two bank loads however it is arranged.
	 * (The wiki's one-trip trick, six baskets of apples, only makes ORDINARY compost, so it
	 * does not help anyone filling for supercompost.)
	 *
	 * <p>Worth a method rather than a comment because the loadout has to say so before the
	 * player walks to the guild, and the guide has to stay sane when a bin is left part-filled
	 * as a result.
	 */
	public boolean fitsOneInventory()
	{
		return capacity <= com.dooglemaps.guide.CarriedItems.INVENTORY_SIZE;
	}

	/**
	 * Volcanic ash to turn a finished bin of supercompost into ultracompost — all of it at once.
	 *
	 * <h2>Why this is not simply two per bucket</h2>
	 *
	 * Because the bin is cheaper, and only if it is done in the right order. Ash applied to the
	 * <b>bin</b> costs 25 for fifteen ultracompost; applied to buckets after they are filled it
	 * is two each, so the same fifteen cost 30. The big bin keeps the ratio: 50 for thirty
	 * against 60 done by hand.
	 *
	 * <p>So the saving is lost the moment a bucket is filled, which is what makes "ash first,
	 * buckets second" a real instruction rather than a preference.
	 */
	public int ashNeeded()
	{
		return ash;
	}

	/**
	 * The patch types a "compost bin" tick actually covers: <b>the guild's big bin, and only it</b>.
	 *
	 * <h2>The tick used to mean all nine bins, and that was the wrong unit</h2>
	 *
	 * It widened {@code COMPOST} to add {@code BIG_COMPOST}, so one line meant every bin in the
	 * game. That made the run responsible for hauling fifteen un-noted items to each of the seven
	 * beside the allotments — and there is no bank near any of them. Measured against
	 * {@code BankLocations}: Catherby ~23 tiles, Kourend ~44, Falador ~65, Ardougne ~96, and
	 * Civitas, Canifis and Prifddinas have no seeded bank at all. Only the Farming Guild has one
	 * in its own region, which is why {@code RunPlanner.supplyPointIsHere} fires there and
	 * nowhere else.
	 *
	 * <p>So the tick now covers the one bin a bank can supply. The other seven are fed from the
	 * harvest standing next to them — every one shares a stop with the allotments that produce
	 * it — and are serviced wherever the run already goes rather than being a reason to travel.
	 * See {@code CompostRunStore.getFodderCrops}.
	 *
	 * <p><b>The stored key stays {@code COMPOST}.</b> Profiles hold {@code runTypes=["COMPOST"]}
	 * and re-keying the group would silently untick the line for everyone who had it on. The
	 * line's meaning narrowed; its identity did not.
	 *
	 * <p>Kept as a fold here rather than in either caller for the reason the original note gave,
	 * which still holds: two copies of a rule like this drift, and the failure when they do is
	 * silent. <b>The Farming Guild has no ordinary bin</b> — its only one is the big one — so a
	 * caller that forgot the fold planned over a type with no patches anywhere and reported
	 * nothing to do while the player stood at the guild looking at the bin. Reported from play.
	 */
	public static java.util.Set<PatchImplementation> coveredByTheBinTick(
		java.util.Set<PatchImplementation> types)
	{
		if (!types.contains(PatchImplementation.COMPOST))
		{
			return types;
		}
		java.util.Set<PatchImplementation> covered = java.util.EnumSet.copyOf(types);
		covered.remove(PatchImplementation.COMPOST);
		covered.add(PatchImplementation.BIG_COMPOST);
		return covered;
	}

	/** Which bin a patch type is, or null if it is not a bin at all. */
	public static CompostBin forType(PatchImplementation type)
	{
		for (CompostBin bin : values())
		{
			if (bin.type == type)
			{
				return bin;
			}
		}
		return null;
	}

	/**
	 * One dose turns a whole finished bin of compost into supercompost, whatever its size.
	 *
	 * <p>The one figure that does <b>not</b> double for the big bin, which is why it is a
	 * constant rather than a column: a single dose does thirty as readily as fifteen.
	 */
	public static final int COMPOST_POTION_DOSES = 1;

	/**
	 * What the ash is.
	 *
	 * <p>{@code FOSSIL_VOLCANIC_ASH} in the cache's naming, after the island it is mined on;
	 * named here so a search for "volcanic ash" finds it.
	 */
	public static final int VOLCANIC_ASH = ItemID.FOSSIL_VOLCANIC_ASH;

	/** What the compost comes out into, one per compost. The leprechaun stores these. */
	public static final int EMPTY_BUCKET = ItemID.BUCKET_EMPTY;
}
