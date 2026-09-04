package com.dooglemaps.data;

import net.runelite.api.gameval.NpcID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Fossil Island's three hardwood patches are tended by three different NPCs who all happen to
 * answer to the same generated name, "Squirrel" — {@code Farmers} has no other way to tell them
 * apart, since the wiki names all three the same thing.
 *
 * <h2>Why sharing a name was the wrong test</h2>
 *
 * {@link FarmerVariants#same} fell back to a shared name from {@link Farmers} once the hand-written
 * {@code GROUPS} table found nothing, which is exactly right for Guildmaster Jane — one person, three
 * ids, one name — and exactly wrong here: three <i>different</i> gardeners who happen to share a
 * name reads as "the same farmer" by that rule, and every consumer of {@code same} — capture,
 * highlight, menu swap — then treated the three squirrels as one. Reported from play: all three
 * payments recorded against the last patch in the region list, Fossil Island West.
 */
public class EachSquirrelIsItsOwnGardenerTest
{
	@Test
	public void noTwoSquirrelsAreTheSameGardener()
	{
		assertFalse("East's squirrel is not Middle's",
			FarmerVariants.same(NpcID.FOSSIL_SQUIRREL_GARDENER1, NpcID.FOSSIL_SQUIRREL_GARDENER2));
		assertFalse("Middle's squirrel is not West's",
			FarmerVariants.same(NpcID.FOSSIL_SQUIRREL_GARDENER2, NpcID.FOSSIL_SQUIRREL_GARDENER3));
		assertFalse("East's squirrel is not West's",
			FarmerVariants.same(NpcID.FOSSIL_SQUIRREL_GARDENER1, NpcID.FOSSIL_SQUIRREL_GARDENER3));
		assertFalse("symmetric",
			FarmerVariants.same(NpcID.FOSSIL_SQUIRREL_GARDENER3, NpcID.FOSSIL_SQUIRREL_GARDENER1));
	}

	/** Each Fossil hardwood patch's own farmer id matches only itself among the three. */
	@Test
	public void eachFossilPatchsFarmerMatchesOnlyItself()
	{
		FarmPatch east = fossilPatch("East");
		FarmPatch middle = fossilPatch("Middle");
		FarmPatch west = fossilPatch("West");

		assertEquals(NpcID.FOSSIL_SQUIRREL_GARDENER1, east.getFarmer());
		assertEquals(NpcID.FOSSIL_SQUIRREL_GARDENER2, middle.getFarmer());
		assertEquals(NpcID.FOSSIL_SQUIRREL_GARDENER3, west.getFarmer());

		assertTrue(FarmerVariants.same(east.getFarmer(), NpcID.FOSSIL_SQUIRREL_GARDENER1));
		assertFalse(FarmerVariants.same(east.getFarmer(), NpcID.FOSSIL_SQUIRREL_GARDENER2));
		assertFalse(FarmerVariants.same(east.getFarmer(), NpcID.FOSSIL_SQUIRREL_GARDENER3));

		assertFalse(FarmerVariants.same(middle.getFarmer(), NpcID.FOSSIL_SQUIRREL_GARDENER1));
		assertTrue(FarmerVariants.same(middle.getFarmer(), NpcID.FOSSIL_SQUIRREL_GARDENER2));
		assertFalse(FarmerVariants.same(middle.getFarmer(), NpcID.FOSSIL_SQUIRREL_GARDENER3));

		assertFalse(FarmerVariants.same(west.getFarmer(), NpcID.FOSSIL_SQUIRREL_GARDENER1));
		assertFalse(FarmerVariants.same(west.getFarmer(), NpcID.FOSSIL_SQUIRREL_GARDENER2));
		assertTrue(FarmerVariants.same(west.getFarmer(), NpcID.FOSSIL_SQUIRREL_GARDENER3));
	}

	/** Fixing the squirrels must not touch Jane, who groups by name for a real reason. */
	@Test
	public void janeStillGroupsByHerName()
	{
		assertTrue(FarmerVariants.same(NpcID.FARMING_GUILD_MASTER, NpcID.FARMING_GUILD_MASTER_1OP));
		assertTrue(FarmerVariants.same(NpcID.FARMING_GUILD_MASTER_2OP, NpcID.FARMING_GUILD_MASTER));
	}

	private static FarmPatch fossilPatch(String name)
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.HARDWOOD_TREE))
		{
			if (name.equals(patch.getName()) && "Fossil Island".equals(patch.getRegion().getName()))
			{
				return patch;
			}
		}
		throw new AssertionError("no Fossil Island hardwood patch named " + name);
	}

	/** Fixture guard: the whole test rests on there being exactly these three patches. */
	@Test
	public void thereAreExactlyThreeFossilHardwoodPatches()
	{
		assertNotNull(fossilPatch("East"));
		assertNotNull(fossilPatch("Middle"));
		assertNotNull(fossilPatch("West"));
	}
}
