package com.dooglemaps.data;

import lombok.Getter;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;

/**
 * What the tool leprechaun stores, and the varbits that say how much of each he is holding.
 *
 * <p>The useful discovery here is that <b>his store is varbits, not an interface</b>. Every slot
 * has its own player varbit, which means the contents can be read at any time from anywhere —
 * standing in a bank, on a boat, before the run starts. Nothing has to be opened and no capture
 * has to be waited for, which is the opposite of how the bank works and much better.
 *
 * <p>That turns a standing assumption into something checkable. The loadout used to say "the
 * leprechaun stores 1,000 of each compost, so this rarely needs banking" unconditionally, which
 * is true of a well-stocked account and a lie to anyone who has never deposited a bucket. Same
 * for the tools: telling a player their rake is on site is only helpful if it is.
 *
 * <h2>The EXTRA varbits</h2>
 *
 * Each thing has a base varbit and, for the ones whose limits were raised later, one or more
 * {@code EXTRA} varbits. Whether those are <i>additional</i> storage or the <i>high bits</i> of
 * one number is not documented anywhere and cannot be settled without an account holding more
 * than the base can express. {@link #getVarbits()} returns them all and the store sums them,
 * which is exact if they are additive and an <b>underestimate</b> if they are high bits.
 *
 * <p>That is the safe direction to be wrong in, and it does not affect any decision made today:
 * everything asked of this is "is there at least one", and both readings agree on zero.
 */
@Getter
public enum FarmingTool
{
	RAKE("Rake", ItemID.RAKE,
		InterfaceID.FarmingTools.RAKE, InterfaceID.FarmingToolsSide.RAKE,
		"Clears weeds - a patch cannot be treated or planted until it is raked",
		VarbitID.FARMING_TOOLS_RAKE, VarbitID.FARMING_TOOLS_EXTRARAKES),

	SEED_DIBBER("Seed dibber", ItemID.DIBBER,
		InterfaceID.FarmingTools.DIBBER, InterfaceID.FarmingToolsSide.DIBBER,
		"Plants seeds - without one, nothing goes in the ground",
		VarbitID.FARMING_TOOLS_DIBBER, VarbitID.FARMING_TOOLS_EXTRADIBBERS),

	SPADE("Spade", ItemID.SPADE,
		InterfaceID.FarmingTools.SPADE, InterfaceID.FarmingToolsSide.SPADE,
		"Digs out dead crops and tree stumps",
		VarbitID.FARMING_TOOLS_SPADE, VarbitID.FARMING_TOOLS_EXTRASPADES),

	SECATEURS("Secateurs", ItemID.SECATEURS,
		InterfaceID.FarmingTools.SECATEURS, InterfaceID.FarmingToolsSide.SECATEURS,
		"Prunes bushes and fruit trees",
		VarbitID.FARMING_TOOLS_SECATEURS, VarbitID.FARMING_TOOLS_EXTRASECATEURS),

	/**
	 * The magic pair, which the leprechaun stores like any other tool.
	 *
	 * <p>Worth its own entry rather than folding into {@link #SECATEURS} because the +10% only
	 * applies while they are on you. Knowing he has them turns "withdraw these from the bank"
	 * into "pick them up at the first patch", which is a different and much cheaper errand.
	 */
	MAGIC_SECATEURS("Magic secateurs", ItemID.FAIRY_ENCHANTED_SECATEURS,
		InterfaceID.FarmingTools.SECATEURS, InterfaceID.FarmingToolsSide.SECATEURS,
		"+10% yield, but only while they are on you - the leprechaun's pair does not count",
		VarbitID.FARMING_TOOLS_FAIRYSECATEURS),

	/**
	 * Doses, cans, or one of each — unknown, and deliberately not guessed at.
	 *
	 * <p>An ordinary can holds 8 doses and Gricoller's holds 1,000, so a single number could
	 * plausibly be either. Nothing depends on it yet; watering is not modelled at all (see
	 * {@code TODO.md}), and this is here so the reading exists when it is.
	 */
	WATERING_CAN("Watering can", ItemID.WATERING_CAN_8,
		InterfaceID.FarmingTools.WATERINGCAN, InterfaceID.FarmingToolsSide.WATERINGCAN,
		"Waters saplings in pots, and growing allotments to ward off disease",
		VarbitID.FARMING_TOOLS_WATERINGCAN),

	GARDENING_TROWEL("Gardening trowel", ItemID.GARDENING_TROWEL,
		InterfaceID.FarmingTools.TROWEL, InterfaceID.FarmingToolsSide.TROWEL,
		"Fills plant pots with soil - needed to turn a tree seed into a sapling",
		VarbitID.FARMING_TOOLS_TROWEL, VarbitID.FARMING_TOOLS_EXTRATROWELS),

	PLANT_CURE("Plant cure", ItemID.PLANT_CURE,
		InterfaceID.FarmingTools.PLANTCURE, InterfaceID.FarmingToolsSide.PLANTCURE,
		"Cures a diseased patch before it dies",
		VarbitID.FARMING_TOOLS_PLANTCURE),

	EMPTY_BUCKET("Bucket", ItemID.BUCKET_EMPTY,
		InterfaceID.FarmingTools.BUCKET, InterfaceID.FarmingToolsSide.BUCKET,
		"Where the empties go back",
		VarbitID.FARMING_TOOLS_BUCKETS, VarbitID.FARMING_TOOLS_EXTRABUCKETS,
		VarbitID.FARMING_TOOLS_EXTRA2BUCKETS),

	/**
	 * The three compost tiers, taking their item ids from {@link CompostTier} rather than
	 * repeating them.
	 *
	 * <p>Two enums naming the same bucket independently is the sort of duplication that stays
	 * correct until one of them is edited. The guide's highlight looks these up by the id the
	 * step carries, and that id comes from {@code CompostTier} — so they have to be the same
	 * number by construction, not by agreement.
	 */
	COMPOST("Compost", CompostTier.COMPOST.getItemID(),
		InterfaceID.FarmingTools.COMPOST, InterfaceID.FarmingToolsSide.COMPOST,
		"Treats a patch",
		VarbitID.FARMING_TOOLS_COMPOST, VarbitID.FARMING_TOOLS_EXTRACOMPOST),

	SUPERCOMPOST("Supercompost", CompostTier.SUPERCOMPOST.getItemID(),
		InterfaceID.FarmingTools.SUPERCOMPOST, InterfaceID.FarmingToolsSide.SUPERCOMPOST,
		"Treats a patch",
		VarbitID.FARMING_TOOLS_SUPERCOMPOST, VarbitID.FARMING_TOOLS_EXTRASUPERCOMPOST),

	ULTRACOMPOST("Ultracompost", CompostTier.ULTRACOMPOST.getItemID(),
		InterfaceID.FarmingTools.ULTRACOMPOST, InterfaceID.FarmingToolsSide.ULTRACOMPOST,
		"Treats a patch",
		VarbitID.FARMING_TOOLS_ULTRACOMPOST);

	private final String displayName;

	/** The item, for an icon and for asking whether one is already carried. */
	private final int itemID;

	/** The slot in his full-screen store, for the guide's highlight. */
	private final int storeSlot;

	/** The same slot in the sidebar form of the store. Only one of the two is ever open. */
	private final int sideStoreSlot;

	/** Why a run wants it, for the tooltip. */
	private final String reason;

	/** Base first, then any {@code EXTRA} varbits. Summed by the store; see the class note. */
	private final int[] varbits;

	FarmingTool(String displayName, int itemID, int storeSlot, int sideStoreSlot, String reason,
		int... varbits)
	{
		this.displayName = displayName;
		this.itemID = itemID;
		this.storeSlot = storeSlot;
		this.sideStoreSlot = sideStoreSlot;
		this.reason = reason;
		this.varbits = varbits;
	}

	/**
	 * The leprechaun's slot for a compost tier, or null for {@link CompostTier#NONE}.
	 *
	 * <p>So the loadout can ask about the compost the player actually chose without keeping a
	 * second mapping alongside {@code CompostTier}'s.
	 */
	public static FarmingTool forCompost(CompostTier tier)
	{
		switch (tier)
		{
			case COMPOST:
				return COMPOST;
			case SUPERCOMPOST:
				return SUPERCOMPOST;
			case ULTRACOMPOST:
				return ULTRACOMPOST;
			default:
				return null;
		}
	}
}
