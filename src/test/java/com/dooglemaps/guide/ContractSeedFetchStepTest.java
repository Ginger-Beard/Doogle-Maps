package com.dooglemaps.guide;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.RunOption;
import com.dooglemaps.data.Seed;
import com.dooglemaps.route.BankLocationStore;
import com.dooglemaps.route.RunStop;
import com.dooglemaps.state.ContractState;
import com.dooglemaps.state.PlantingGroups;
import com.dooglemaps.state.SeedSource;
import com.dooglemaps.timer.Confidence;
import com.dooglemaps.timer.GrowthTimer;
import com.dooglemaps.timer.PatchProjection;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A contract seed sitting in storage <i>at this stop</i> is a step, not a footnote.
 *
 * <p>It was only ever a grey note — "withdraw it to plant the contract this trip" — which could
 * not be the current instruction and could not be skipped, while the player stood a short walk
 * from the guild's own bank chest and the run offered clicks at lesser patches instead. The
 * fetch is now a front-of-queue step wherever a usable bank shares the stop's region, and the
 * note survives only for stops that cannot act on it. Asked for from play, at a cadantine
 * contract whose seed sat in the guild bank.
 */
public class ContractSeedFetchStepTest
{
	private static final WorldPoint GUILD_BANK = new WorldPoint(1253, 3741, 0);

	private GuideTracker tracker;
	private ContractState contracts;
	private PlantingGroups groups;
	private GrowthTimer growthTimer;
	private com.dooglemaps.state.RunTypeStore runTypes;
	private com.dooglemaps.state.SeedInventoryStore seeds;
	private BankLocationStore bankLocations;
	private FarmPatch herb;
	private final PlantingGroup contractGroup = PlantingGroup.contract(PatchImplementation.HERB);

	@Before
	public void setUp() throws Exception
	{
		contracts = Mockito.mock(ContractState.class);
		groups = Mockito.mock(PlantingGroups.class);
		growthTimer = Mockito.mock(GrowthTimer.class);
		runTypes = Mockito.mock(com.dooglemaps.state.RunTypeStore.class);
		seeds = Mockito.mock(com.dooglemaps.state.SeedInventoryStore.class);
		bankLocations = Mockito.mock(BankLocationStore.class);

		DoogleMapsConfig config = Mockito.mock(DoogleMapsConfig.class);
		when(config.guideFarmingContracts()).thenReturn(true);
		when(config.contractSeedAdvice())
			.thenReturn(DoogleMapsConfig.ContractSeedAdvice.ASK_FOR_EASIER);

		when(groups.patchesIn(any())).thenReturn(Collections.emptyList());

		tracker = trackerWith(config);

		herb = guildPatch(PatchImplementation.HERB);
		assertNotNull("the Farming Guild has no herb patch in the data", herb);

		// A fresh cadantine contract, its patch empty, its seed owned but banked.
		when(contracts.getContract()).thenReturn(Produce.CADANTINE);
		when(contracts.hasContract()).thenReturn(true);
		when(contracts.getAwaitingHandIn()).thenReturn(null);
		when(contracts.getContractSeed()).thenReturn(Seed.CADANTINE);
		when(groups.groupFor(herb)).thenReturn(contractGroup);
		when(groups.patchesIn(contractGroup)).thenReturn(Collections.singletonList(herb));
		when(runTypes.isSelected(RunOption.full(contractGroup))).thenReturn(true);
		empty(herb);

		when(seeds.getFarmingLevel()).thenReturn(99);
		when(seeds.getOwned(Seed.CADANTINE)).thenReturn(1);
		when(seeds.getPlantable(Seed.CADANTINE, SeedSource.BANK)).thenReturn(1);

		when(bankLocations.getUsableBanks())
			.thenReturn(Collections.singleton(GUILD_BANK));
		when(bankLocations.getSeedVault()).thenReturn(GUILD_BANK);
	}

	/** The reported case: seed in the bank, bank in the guild — a step, at the very front. */
	@Test
	public void aBankedSeedAtThisStopIsAFrontOfQueueStep() throws Exception
	{
		List<GuideStep> steps = errands();

		assertFalse("the fetch should be on offer", steps.isEmpty());
		assertEquals(GuideAction.FETCH_SEED, steps.get(0).getAction());
		assertEquals("Withdraw your cadantine seed from the bank here.", steps.get(0).getText());
	}

	/** And the note stands down — step and note saying it together was the duplicate. */
	@Test
	public void theNoteGoesQuietWhileTheStepShows() throws Exception
	{
		assertNull("the step already says it", note());
	}

	/** A seed already at hand needs no fetching; the plant step takes over from here. */
	@Test
	public void noStepOnceTheSeedIsAtHand() throws Exception
	{
		when(seeds.getPlantable(Seed.CADANTINE, SeedSource.SEED_BOX)).thenReturn(1);

		assertFalse(has(errands(), GuideAction.FETCH_SEED));
	}

	/** No usable storage in this region: the step's "here" would be a lie, so the note speaks. */
	@Test
	public void aStopWithoutABankKeepsTheNoteInstead() throws Exception
	{
		when(bankLocations.getUsableBanks()).thenReturn(Collections.emptySet());
		when(bankLocations.getSeedVault()).thenReturn(new WorldPoint(3200, 3200, 0));

		assertFalse(has(errands(), GuideAction.FETCH_SEED));

		String note = note();
		assertNotNull("where the stop cannot act, saying where the seed is is all there is", note);
		assertTrue(note, note.contains("in your bank"));
	}

	/** The vault is storage too, and is named as itself. */
	@Test
	public void aVaultedSeedIsFetchedFromTheVault() throws Exception
	{
		when(seeds.getPlantable(Seed.CADANTINE, SeedSource.BANK)).thenReturn(0);
		when(seeds.getPlantable(Seed.CADANTINE, SeedSource.SEED_VAULT)).thenReturn(1);

		List<GuideStep> steps = errands();
		assertEquals(GuideAction.FETCH_SEED, steps.get(0).getAction());
		assertTrue(steps.get(0).getText(), steps.get(0).getText().contains("seed vault"));
	}

	/** Something else mid-growth in the patch: the blocked note's business, not a shopping trip. */
	@Test
	public void aGrowingOccupantStillBlocksTheFetch() throws Exception
	{
		growing(herb, Produce.RANARR);

		assertFalse(has(errands(), GuideAction.FETCH_SEED));
	}

	/**
	 * A dead contract crop is fetched for <b>before</b> the walk over, not after the clear.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * <i>"if we know our farming contract is dead, don't have me walk all the way over to clear
	 * it, before getting the seed from the bank/vault"</i>
	 *
	 * <p>A dead crop answered all three of this step's tests the wrong way at once: it has
	 * outstanding clicks (the clear), its produce is the contract's, and it is not empty ground.
	 * So the fetch stood down, the run sent the player across the guild to clear the patch, and
	 * only once the ground was bare did the seed in the bank behind them become a step. Two
	 * crossings of the guild to plant one seed.
	 *
	 * <p>Dead ground is ground the run is already going to clear — the same argument the
	 * spade-cleared allowance makes for a stripped bush — so the seed is wanted on this trip
	 * either way and the fetch belongs in front.
	 */
	@Test
	public void aDeadContractCropIsFetchedForBeforeTheWalkOver() throws Exception
	{
		dead(herb, Produce.CADANTINE);

		List<GuideStep> steps = errands();
		assertFalse("a dead contract still needs its seed this trip", steps.isEmpty());
		assertEquals("and needs it before the walk, so it leads", 
			GuideAction.FETCH_SEED, steps.get(0).getAction());
		assertEquals("Withdraw your cadantine seed from the bank here.", steps.get(0).getText());
	}

	/**
	 * A dead crop of some <i>other</i> produce is fetched for too — it is the ground that
	 * decides this, not what died in it.
	 */
	@Test
	public void aDeadCropOfAnotherProduceIsAlsoClearedForThisTrip() throws Exception
	{
		dead(herb, Produce.RANARR);

		assertTrue(has(errands(), GuideAction.FETCH_SEED));
	}

	/**
	 * And with no seed owned anywhere, the note speaks rather than going quiet.
	 *
	 * <p>{@code contractIsInTheGround} counted a dead crop as the contract planted, so
	 * {@code missingContractSeed} treated the seed as already spent and said nothing at all —
	 * silence at the one moment the player is about to walk to a patch they cannot refill.
	 */
	@Test
	public void aDeadContractWithNoSeedOwnedStillSaysSo() throws Exception
	{
		dead(herb, Produce.CADANTINE);
		when(seeds.getOwned(Seed.CADANTINE)).thenReturn(0);
		when(seeds.getPlantable(Seed.CADANTINE, SeedSource.BANK)).thenReturn(0);

		String note = note();
		assertNotNull("a dead contract with no seed left is exactly what the note is for", note);
		assertTrue(note, note.toLowerCase().contains("cadantine"));
	}

	/**
	 * The fetch step also lights the container it is pointing at.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * {@code GuideOverlay.highlightSupplyPoints} outlines the bank booths and the seed vault from
	 * {@code GuideStatus.getSupplySources}, and that set was populated <b>only on the bank leg</b>.
	 * A contract is taken from Jane in the middle of a run, nowhere near it — so the step said
	 * "withdraw your irit seed from the seed vault here" and nothing in the room lit up. Reported
	 * from play: "after getting a new contract from Jane and I need to get the seed from
	 * vault/bank, neither get highlighted".
	 *
	 * <p>Derived from the step on the list rather than from the contract state, so the outline
	 * cannot appear for a fetch the guide has decided not to ask for.
	 */
	@Test
	public void theFetchLightsTheContainerItNames() throws Exception
	{
		assertTrue("fixture: the fetch is being asked for", has(errands(), GuideAction.FETCH_SEED));

		assertEquals("the bank holds the seed, so the bank is what to walk to",
			Collections.singleton(SeedSource.BANK), fetchSources(errands()));
	}

	/** And the vault when that is where it is, so the outline follows the wording. */
	@Test
	public void aVaultedFetchLightsTheVault() throws Exception
	{
		when(seeds.getPlantable(Seed.CADANTINE, SeedSource.BANK)).thenReturn(0);
		when(seeds.getPlantable(Seed.CADANTINE, SeedSource.SEED_VAULT)).thenReturn(1);

		List<GuideStep> steps = errands();
		assertTrue(steps.get(0).getText(), steps.get(0).getText().contains("seed vault"));
		assertEquals(Collections.singleton(SeedSource.SEED_VAULT), fetchSources(steps));
	}

	/** With no fetch on the list, nothing is lit — the outline follows the instruction. */
	@Test
	public void nothingIsLitWithoutAFetchStep() throws Exception
	{
		assertTrue(fetchSources(new ArrayList<>()).isEmpty());
	}

	/** And nothing is lit when the seed is in neither container at this stop. */
	@Test
	public void nothingIsLitWhenTheSeedIsNotStoredHere() throws Exception
	{
		List<GuideStep> steps = errands();
		assertTrue("fixture: the fetch is being asked for", has(steps, GuideAction.FETCH_SEED));

		// Neither container here holds it any more - the same stores the step reads.
		when(seeds.getPlantable(Seed.CADANTINE, SeedSource.BANK)).thenReturn(0);
		when(seeds.getPlantable(Seed.CADANTINE, SeedSource.SEED_VAULT)).thenReturn(0);

		assertTrue("nothing here holds it, so there is nothing to point at",
			fetchSources(steps).isEmpty());
	}

	/**
	 * The overlay reads the named container outside the "no current step" branch.
	 *
	 * <h2>Why this is asserted about the overlay's source and not just the tracker's</h2>
	 *
	 * Populating {@code GuideStatus.getSupplySources} for a contract fetch was the first half,
	 * and on its own it changed nothing: {@code GuideOverlay} only called
	 * {@code highlightSupplyPoints} when there was <b>no current step</b>, and a contract fetch
	 * is a step. Reported from play a second time, after the set had been fixed.
	 *
	 * <p>So the condition the overlay actually branches on is pinned here in words rather than
	 * left to a reading of the render method: a non-empty set means "light exactly these", and
	 * an empty one is the bank leg's own fallback, which {@code marks} reads as every bank and
	 * no vault. Getting those two round the wrong way lights the room.
	 */
	@Test
	public void aNamedContainerIsNotTheSameAsTheBankLegsFallback() throws Exception
	{
		java.util.Set<SeedSource> named = fetchSources(errands());

		assertFalse("a named container is a positive answer, not an absence", named.isEmpty());
		assertEquals(Collections.singleton(SeedSource.BANK), named);

		// And the fallback is genuinely different: empty is what the bank leg hands over when
		// it knows it needs a bank without knowing what for.
		assertTrue(fetchSources(new ArrayList<>()).isEmpty());
	}

	private java.util.Set<SeedSource> fetchSources(List<GuideStep> steps) throws Exception
	{
		Method method = GuideTracker.class.getDeclaredMethod(
			"contractFetchSources", List.class, RunStop.class);
		method.setAccessible(true);
		try
		{
			@SuppressWarnings("unchecked")
			java.util.Set<SeedSource> sources =
				(java.util.Set<SeedSource>) method.invoke(tracker, steps, guildStop());
			return sources;
		}
		catch (java.lang.reflect.InvocationTargetException e)
		{
			throw new AssertionError(e.getCause());
		}
	}

	// ------------------------------------------------------------------- helpers

	private List<GuideStep> errands() throws Exception
	{
		Method method = GuideTracker.class.getDeclaredMethod(
			"appendContractErrands", List.class, RunStop.class);
		method.setAccessible(true);

		List<GuideStep> steps = new ArrayList<>();
		try
		{
			method.invoke(tracker, steps, guildStop());
		}
		catch (java.lang.reflect.InvocationTargetException e)
		{
			throw new AssertionError(e.getCause());
		}
		return steps;
	}

	@Nullable
	private String note() throws Exception
	{
		Method method = GuideTracker.class.getDeclaredMethod("contractNote", RunStop.class);
		method.setAccessible(true);
		try
		{
			return (String) method.invoke(tracker, guildStop());
		}
		catch (java.lang.reflect.InvocationTargetException e)
		{
			throw new AssertionError(e.getCause());
		}
	}

	private static boolean has(List<GuideStep> steps, GuideAction action)
	{
		return steps.stream().anyMatch(step -> step.getAction() == action);
	}

	private RunStop guildStop()
	{
		RunStop stop = Mockito.mock(RunStop.class);
		when(stop.getName()).thenReturn("Farming Guild");
		when(stop.getRegion()).thenReturn(herb.getRegion());
		when(stop.getPatches()).thenReturn(Collections.singletonList(herb));
		when(stop.getServiced()).thenReturn(new java.util.HashSet<>());
		return stop;
	}

	private void empty(FarmPatch patch) throws Exception
	{
		when(growthTimer.project(Mockito.eq(patch), any()))
			.thenReturn(projection(patch, null, CropState.EMPTY, 0, 0, false));
	}

	private void dead(FarmPatch patch, Produce produce) throws Exception
	{
		when(growthTimer.project(Mockito.eq(patch), any()))
			.thenReturn(projection(patch, produce, CropState.DEAD, 1,
				produce.getStages(), false));
	}

	private void growing(FarmPatch patch, Produce produce) throws Exception
	{
		when(growthTimer.project(Mockito.eq(patch), any()))
			.thenReturn(projection(patch, produce, CropState.GROWING, 1,
				produce.getStages(), false));
	}

	private static PatchProjection projection(FarmPatch patch, @Nullable Produce produce,
		CropState state, int stage, int stages, boolean done) throws Exception
	{
		long now = java.time.Instant.now().getEpochSecond();

		Constructor<PatchProjection> ctor = PatchProjection.class.getDeclaredConstructor(
			FarmPatch.class, Produce.class, CropState.class, int.class, int.class,
			long.class, int.class, long.class, Confidence.class, boolean.class, long.class,
			boolean.class, int.class);
		ctor.setAccessible(true);

		return ctor.newInstance(patch, produce, state, stage, stages,
			done ? now - 60 : now + 3600, 0, 0L, Confidence.CERTAIN, false, now, false, -1);
	}

	private static FarmPatch guildPatch(PatchImplementation type)
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(type))
		{
			if (patch.getRegion().getRegionId() == ContractState.FARMING_GUILD_REGION)
			{
				return patch;
			}
		}
		return null;
	}

	/** A tracker whose collaborators are this test's mocks, everything else auto-mocked. */
	private GuideTracker trackerWith(DoogleMapsConfig config) throws Exception
	{
		Constructor<?> constructor = GuideTracker.class.getDeclaredConstructors()[0];
		constructor.setAccessible(true);

		Class<?>[] types = constructor.getParameterTypes();
		Object[] args = new Object[types.length];
		for (int i = 0; i < types.length; i++)
		{
			Class<?> type = types[i];
			if (type == ContractState.class)
			{
				args[i] = contracts;
			}
			else if (type == PlantingGroups.class)
			{
				args[i] = groups;
			}
			else if (type == GrowthTimer.class)
			{
				args[i] = growthTimer;
			}
			else if (type == com.dooglemaps.state.RunTypeStore.class)
			{
				args[i] = runTypes;
			}
			else if (type == com.dooglemaps.state.SeedInventoryStore.class)
			{
				args[i] = seeds;
			}
			else if (type == BankLocationStore.class)
			{
				args[i] = bankLocations;
			}
			else if (type == DoogleMapsConfig.class)
			{
				args[i] = config;
			}
			else
			{
				args[i] = Mockito.mock(type);
				if (type == com.dooglemaps.state.SeedSelectionStore.class)
				{
					when(((com.dooglemaps.state.SeedSelectionStore) args[i])
						.getSelectedFor(any(PlantingGroup.class)))
						.thenReturn(new LinkedHashSet<>());
				}
				else if (type == com.dooglemaps.state.CompostSelectionStore.class)
				{
					when(((com.dooglemaps.state.CompostSelectionStore) args[i])
						.get(any(PlantingGroup.class)))
						.thenReturn(CompostTier.NONE);
				}
			}
		}
		return (GuideTracker) constructor.newInstance(args);
	}
}
