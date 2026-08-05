package com.dooglemaps.bank;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.ItemID;

/**
 * Woodcutting axes, best first, with the level each one needs.
 *
 * <p>A tree run needs one and the leprechaun does not store it. Once a tree is grown you check
 * its health for the experience, chop it for the logs, and only then can the stump be dug up
 * and the patch replanted — so turning up to a tree run without an axe means the patch cannot
 * be cleared at all. The same is true of hardwoods and redwoods.
 *
 * <p>Ordered by tier so "the best you can use" is a scan rather than a comparison. The level
 * matters as much as the tier: a dragon axe from a drop is useless at 30 Woodcutting, and
 * telling someone to bring it would be worse than saying nothing.
 *
 * <p>Both families are here: the ordinary one-handed axes and the two-handed <b>felling</b>
 * axes from Forestry, which cut at the same tiers. RuneLite names the felling axes
 * {@code *_AXE_2H} rather than anything with "felling" in it, which is worth knowing before
 * concluding they are missing — {@code BRONZE_AXE_2H} is item 28196, the bronze felling axe.
 *
 * <p>Left out: noted forms, which cannot be swung, and uncharged crystal axes, which cannot
 * either. The leagues and Gauntlet variants are not things anyone brings on a farm run.
 */
public final class Axes
{
	/** One axe: what it is, what it is called, and what it takes to swing it. */
	public static final class Axe
	{
		private final int itemId;
		private final String name;
		private final int woodcuttingLevel;

		Axe(int itemId, String name, int woodcuttingLevel)
		{
			this.itemId = itemId;
			this.name = name;
			this.woodcuttingLevel = woodcuttingLevel;
		}

		public int getItemId()
		{
			return itemId;
		}

		public String getName()
		{
			return name;
		}

		public int getWoodcuttingLevel()
		{
			return woodcuttingLevel;
		}
	}

	/**
	 * Best first, so the first usable one found is the answer.
	 *
	 * <p>Crystal above dragon, and the cosmetic dragon-tier variants alongside it, because at
	 * equal tier it makes no difference which is carried.
	 */
	private static final List<Axe> BY_TIER = Arrays.asList(
		// 71 — crystal, in both forms.
		new Axe(ItemID.CRYSTAL_AXE, "Crystal axe", 71),
		new Axe(ItemID.CRYSTAL_AXE_2H, "Crystal felling axe", 71),

		// 61 — dragon tier, and everything that chops at it.
		new Axe(ItemID.DRAGON_AXE, "Dragon axe", 61),
		new Axe(ItemID.DRAGON_AXE_2H, "Dragon felling axe", 61),
		new Axe(ItemID.INFERNAL_AXE, "Infernal axe", 61),
		// Out of charges it stops burning logs but still cuts as a dragon axe, so it is still
		// the right thing to take.
		new Axe(ItemID.INFERNAL_AXE_EMPTY, "Infernal axe (uncharged)", 61),
		new Axe(ItemID._3A_AXE, "3rd age axe", 61),
		new Axe(ItemID._3A_AXE_2H, "3rd age felling axe", 61),
		new Axe(ItemID.TRAILBLAZER_AXE, "Trailblazer axe", 61),
		new Axe(ItemID.TRAILBLAZER_RELOADED_AXE, "Trailblazer reloaded axe", 61),

		// 41 — rune tier.
		new Axe(ItemID.TRAIL_GILDED_AXE, "Gilded axe", 41),
		new Axe(ItemID.RUNE_AXE, "Rune axe", 41),
		new Axe(ItemID.RUNE_AXE_2H, "Rune felling axe", 41),

		new Axe(ItemID.ADAMANT_AXE, "Adamant axe", 31),
		new Axe(ItemID.ADAMANT_AXE_2H, "Adamant felling axe", 31),
		new Axe(ItemID.MITHRIL_AXE, "Mithril axe", 21),
		new Axe(ItemID.MITHRIL_AXE_2H, "Mithril felling axe", 21),
		new Axe(ItemID.BLACK_AXE, "Black axe", 11),
		new Axe(ItemID.BLACK_AXE_2H, "Black felling axe", 11),
		new Axe(ItemID.STEEL_AXE, "Steel axe", 6),
		new Axe(ItemID.STEEL_AXE_2H, "Steel felling axe", 6),
		new Axe(ItemID.IRON_AXE, "Iron axe", 1),
		new Axe(ItemID.IRON_AXE_2H, "Iron felling axe", 1),
		new Axe(ItemID.BRONZE_AXE, "Bronze axe", 1),
		new Axe(ItemID.BRONZE_AXE_2H, "Bronze felling axe", 1));

	private Axes()
	{
	}

	/** Every axe, best first. */
	public static List<Axe> byTier()
	{
		return Collections.unmodifiableList(BY_TIER);
	}
}
