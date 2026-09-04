package com.dooglemaps.state;

import com.dooglemaps.data.Produce;
import java.util.HashMap;
import java.util.Map;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * A contract that finished growing while you were logged out is still owed a hand-in.
 *
 * <h2>The reported loss</h2>
 *
 * Growth finishing offline sends no completion message, so two things happen at once and they
 * look like a contradiction: nothing captures the completion, and nothing clears Time Tracking's
 * key either — it goes on naming the crop through the harvest and the hand-in. The reconcile read
 * that key still holding the awaiting crop as proof no completion had ever fired and deleted the
 * record as corruption. It fired eight times in one session, and each time it took away the only
 * record that the reward was uncollected.
 *
 * <p>The record and the corruption differ in one place only, and it is not in config: the ground.
 * Corruption is a crop <i>standing there unfinished</i> — the health-checked bush checked before
 * the contract, which can never satisfy it. A patch that is empty, or holding the finished crop,
 * contradicts nothing. So the same-crop case now takes evidence, and says nothing without it.
 */
public class AContractGrownWhileLoggedOutIsStillOwedTest
{
	private static final String TIME_TRACKING = "timetracking";

	/** Config keyed as {@code group + "." + key}, so the two groups cannot collide. */
	private final Map<String, Object> stored = new HashMap<>();

	private ContractState contracts;

	@Before
	public void setUp()
	{
		ConfigManager configManager = Mockito.mock(ConfigManager.class);
		when(configManager.getRSProfileConfiguration(anyString(), anyString()))
			.thenAnswer(i -> stored.get(i.getArgument(0) + "." + i.getArgument(1)));
		Mockito.doAnswer(i -> stored.put(i.getArgument(0) + "." + i.getArgument(1),
				i.getArgument(2)))
			.when(configManager)
			.setRSProfileConfiguration(anyString(), anyString(), Mockito.<Object>any());
		Mockito.doAnswer(i -> stored.remove(i.getArgument(0) + "." + i.getArgument(1)))
			.when(configManager)
			.unsetRSProfileConfiguration(anyString(), anyString());

		contracts = construct(ContractState.class, configManager);
	}

	/** The reported case: the cadantine was picked, so there is nothing standing to judge. */
	@Test
	public void anEmptyPatchIsNoEvidenceAgainstTheRecord()
	{
		assignInTimeTracking(Produce.CADANTINE);
		contracts.recordCompleted();
		// What the offline case leaves behind: their key never cleared, because the message that
		// clears it was never sent.
		assignInTimeTracking(Produce.CADANTINE);

		contracts.reconcileAwaitingHandIn(ContractState.GroundEvidence.UNREAD);

		assertEquals("the reward is still sitting with Jane", Produce.CADANTINE,
			contracts.getAwaitingHandIn());
	}

	/**
	 * And the corruption the branch was written for still heals, because the ground shows it: the
	 * crop is standing in the patch and has not finished for the contract.
	 */
	@Test
	public void aCropStandingUnfinishedStillHealsTheRecord()
	{
		assignInTimeTracking(Produce.POISON_IVY);
		contracts.recordCompleted();
		assignInTimeTracking(Produce.POISON_IVY);

		contracts.reconcileAwaitingHandIn(ContractState.GroundEvidence.CROP_STANDS_UNFINISHED);

		assertNull("no completion can have fired for a crop still standing unfinished",
			contracts.getAwaitingHandIn());
		assertEquals("and the assignment survives - the contract is still to grow",
			Produce.POISON_IVY, contracts.getContract());
	}

	/** The differing-crop heal is untouched: Jane assigning proves the last one was settled. */
	@Test
	public void aDifferentAssignmentStillProvesTheHandInHappened()
	{
		assignInTimeTracking(Produce.POISON_IVY);
		contracts.recordCompleted();

		assignInTimeTracking(Produce.RANARR);
		contracts.reconcileAwaitingHandIn(ContractState.GroundEvidence.UNREAD);

		assertNull("the hand-in happened while nothing was watching",
			contracts.getAwaitingHandIn());
		assertEquals("and the new contract is the one that stands", Produce.RANARR,
			contracts.getContract());
	}

	/** Exactly what Time Tracking writes: the harvested item's id, as a string. */
	private void assignInTimeTracking(Produce produce)
	{
		stored.put(TIME_TRACKING + ".contract", String.valueOf(produce.getItemID()));
	}
}
