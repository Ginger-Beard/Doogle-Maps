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
