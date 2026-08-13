package com.dooglemaps.data;

import net.runelite.api.gameval.NpcID;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * One gardener wearing several NPC ids.
 *
 * <p>{@code FarmingWorldData} holds one id per farmer and the game does not, so every exact
 * {@code ==} against a patch's farmer was a comparison that could answer no while the player
 * stood in front of the right person. For the coral nurseries it always did: 15061 in the world
 * data against 15063 in the world, so nothing outlined Chet when the guide said to pay him and
 * no payment was ever attributed to a patch. Reported from play, with the id.
 */
public class FarmerVariantsTest
{
	/** The three the coral farmer answers to, one of which is the one you actually meet. */
	@Test
	public void theCoralFarmersUnlockedIdIsTheSamePerson()
	{
		assertTrue("the id the world data carries, and the id in the world",
			FarmerVariants.same(NpcID.TORTUGAN_CORAL_FARMER,
				NpcID.TORTUGAN_CORAL_FARMER_UNLOCKED));
		assertTrue("and before the nurseries are unlocked",
			FarmerVariants.same(NpcID.TORTUGAN_CORAL_FARMER,
				NpcID.TORTUGAN_CORAL_FARMER_LOCKED));
		assertTrue("the grouping is symmetric",
			FarmerVariants.same(NpcID.TORTUGAN_CORAL_FARMER_UNLOCKED,
				NpcID.TORTUGAN_CORAL_FARMER));
	}

	/** The coral patches point at the id that needs the grouping, which is the whole premise. */
	@Test
	public void theCoralPatchesAreFiledUnderTheIdThatNeedsIt()
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.CORAL))
		{
			assertTrue("a coral patch whose farmer is not the Tortugan: " + patch.getFarmer(),
				FarmerVariants.same(patch.getFarmer(),
					NpcID.TORTUGAN_CORAL_FARMER_UNLOCKED));
		}
	}

	/**
	 * Jane still groups by name, which is what she did before this class existed.
	 *
	 * <p>Her three ids are not listed here — they do not need to be, because the generated
	 * {@code Farmers} table names all three. Pinned so that folding the overlay's inline
	 * name-matching into one place cannot quietly drop her.
	 */
	@Test
	public void janeStillGroupsByHerName()
	{
		assertTrue(FarmerVariants.same(NpcID.FARMING_GUILD_MASTER,
			NpcID.FARMING_GUILD_MASTER_1OP));
		assertTrue(FarmerVariants.same(NpcID.FARMING_GUILD_MASTER_2OP,
			NpcID.FARMING_GUILD_MASTER));
	}

	/** Two different gardeners are not one, however close their ids happen to sit. */
	@Test
	public void differentGardenersStayDifferent()
	{
		assertFalse("the Great Conch's calquat gardener is not its coral farmer",
			FarmerVariants.same(NpcID.TORTUGAN_CORAL_FARMER,
				NpcID.FARMING_GARDENER_CALQUAT_3));
		assertFalse(FarmerVariants.same(NpcID.ELSTAN, NpcID.DANTAERA));
	}

	/**
	 * Nameless and unknown ids do not collapse into each other.
	 *
	 * <p>The name path returns null for anything the generator has not seen, and two nulls
	 * comparing equal would make every unknown NPC the same gardener — which is exactly the
	 * failure that would attribute a payment to whatever patch was nearest.
	 */
	@Test
	public void twoUnknownsAreNotTheSameFarmer()
	{
		assertFalse(FarmerVariants.same(999_001, 999_002));
	}
}
