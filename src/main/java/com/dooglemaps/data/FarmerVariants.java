package com.dooglemaps.data;

import net.runelite.api.gameval.NpcID;

/**
 * One gardener, several NPC ids.
 *
 * <h2>Why a patch's farmer id is not the one standing in front of you</h2>
 *
 * {@link FarmPatch#getFarmer()} comes from {@code FarmingWorldData}, mirrored from RuneLite
 * core, and it holds <b>one</b> id per farmer. The game does not: an NPC that gains or loses a
 * right-click option, or that is gated behind an unlock, is a different id wearing the same
 * face. Guildmaster Jane is three. The Tortugan coral farmer is three — {@code 15061} in the
 * world data, {@code _LOCKED} at 15062, and {@code _UNLOCKED} at <b>15063</b>, which is the one
 * a player who has unlocked the nurseries actually talks to.
 *
 * <p>Every comparison of "is this the farmer the step means" was an exact {@code ==}, so for the
 * coral patches it was comparing 15061 against 15063 and answering no. Nothing outlined Chet
 * when the guide said to pay him, and {@code ProtectionCapture} could not match a payment to a
 * patch, so paying for the nurseries was never recorded at all. Reported from play, with the id.
 *
 * <h2>Why this is not in {@link Farmers}</h2>
 *
 * {@code Farmers} is generated, and its aliasing is a side effect of two ids sharing a name —
 * which works for Jane and cannot work here, because the generator has never found a wiki page
 * for the coral farmer and so has no name to share. See {@code tools/fetch_chatheads.py}, whose
 * {@code EXTRA_NPC_GROUPS} now lists the three so the next regeneration can. Until it runs, the
 * name and the face are still missing and only the <i>identity</i> is fixed here — which is the
 * half that changes behaviour rather than wording.
 *
 * <p>Names are still honoured, so Jane keeps working exactly as she did without being listed.
 */
public final class FarmerVariants
{
	/**
	 * Ids the world data cannot tell apart, hand-written because nothing else knows.
	 *
	 * <p>Deliberately short. A group goes in when a specific id has been seen in play and the
	 * world data's own id has been seen to miss it — the same standard {@code UnderwaterApproach}
	 * holds its approaches to. Guessing that two adjacent ids are the same person is how a
	 * payment gets attributed to the wrong patch.
	 */
	private static final int[][] GROUPS = {
		{
			NpcID.TORTUGAN_CORAL_FARMER,
			NpcID.TORTUGAN_CORAL_FARMER_LOCKED,
			NpcID.TORTUGAN_CORAL_FARMER_UNLOCKED,
		},
	};

	private FarmerVariants()
	{
	}

	/**
	 * Whether these two NPC ids are the same gardener.
	 *
	 * <p>Three tests, cheapest first: the same id, a hand-written variant group, then a shared
	 * name from {@link Farmers}. The last is what {@code GuideOverlay} already did inline for
	 * Jane, kept so that adding this does not quietly change her.
	 */
	public static boolean same(int a, int b)
	{
		if (a == b)
		{
			return true;
		}

		for (int[] group : GROUPS)
		{
			if (contains(group, a) && contains(group, b))
			{
				return true;
			}
		}

		String name = Farmers.getName(a);
		return name != null && name.equals(Farmers.getName(b));
	}

	private static boolean contains(int[] group, int npcId)
	{
		for (int member : group)
		{
			if (member == npcId)
			{
				return true;
			}
		}
		return false;
	}
}
