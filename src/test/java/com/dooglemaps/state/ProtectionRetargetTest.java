package com.dooglemaps.state;

import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.data.Seed;
import com.google.gson.Gson;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Protection choices scoped to a contract die with the contract.
 *
 * <p>They are answers about one crop — 25 coconuts for a magic tree is not an answer about a
 * ranarr — so unlike the run tick, which is renamed to follow the contract, a stale entry is
 * dropped and the next contract starts from the type's own standing answer via the inheritance
 * in {@code isProtecting}. The reported case was a {@code CACTUS#contract|POTATO_CACTUS} found
 * weeks old in a live profile, waiting to resurrect an old explicit choice the moment another
 * potato cactus contract arrived; a stale <i>off</i> is sharper still, silently overriding the
 * inheritance forever.
 */
public class ProtectionRetargetTest
{
	private static final PlantingGroup CACTUS_CONTRACT =
		PlantingGroup.contract(PatchImplementation.CACTUS);
	private static final PlantingGroup PLAIN_CACTUS =
		PlantingGroup.of(PatchImplementation.CACTUS);
	private static final PlantingGroup PLAIN_TREE = PlantingGroup.of(PatchImplementation.TREE);

	private ProtectionSelectionStore store;

	@Before
	public void setUp()
	{
		store = new ProtectionSelectionStore(Mockito.mock(ConfigManager.class), new Gson());
	}

	/** A cactus contract's choice does not survive the contract moving on to a herb. */
	@Test
	public void aSettledContractsChoiceIsDropped()
	{
		store.setProtecting(CACTUS_CONTRACT, Seed.POTATO_CACTUS, true);
		store.setProtecting(PLAIN_TREE, Seed.MAGIC, true);

		// A herb contract has no protection payment, so nothing about it is live.
		store.retargetContract(null);

		assertFalse("the old contract's choice must die with it",
			store.isProtecting(CACTUS_CONTRACT, Seed.POTATO_CACTUS));
		assertTrue("ordinary choices are none of this method's business",
			store.isProtecting(PLAIN_TREE, Seed.MAGIC));
	}

	/** A stale off is the sharper failure: left alone it overrides the inheritance forever. */
	@Test
	public void aSettledContractsOffIsDroppedToo()
	{
		store.setProtecting(PLAIN_CACTUS, Seed.POTATO_CACTUS, true);
		store.setProtecting(CACTUS_CONTRACT, Seed.POTATO_CACTUS, false);

		store.retargetContract(null);

		assertTrue("with the off gone, the next cactus contract inherits the type's answer",
			store.isProtecting(CACTUS_CONTRACT, Seed.POTATO_CACTUS));
	}

	/** The contract that is live right now keeps its choice. */
	@Test
	public void theLiveContractsChoiceSurvives()
	{
		store.setProtecting(CACTUS_CONTRACT, Seed.POTATO_CACTUS, true);

		store.retargetContract(
			ProtectionSelectionStore.contractKey(CACTUS_CONTRACT, Seed.POTATO_CACTUS));

		assertTrue(store.isProtecting(CACTUS_CONTRACT, Seed.POTATO_CACTUS));
	}
}
