package com.dooglemaps.state;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.PlantingGroup;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.Seed;
import com.google.gson.Gson;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Covers reading Guildmaster Jane's contract, and the patch move that reserves its patch.
 *
 * <p>Most of what is guarded here is the thing the plan got wrong. Time Tracking's config key was
 * expected to hold a contract until it was handed in; it is actually cleared the moment the crop
 * finishes growing, so "no key" means either "nothing assigned" or "your reward is waiting" — and
 * treating those as one state would have the guide going silent at exactly the moment it has
 * something to say. The tests below pin both readings apart.
 */
public class ContractStateTest
{
	private static final String TIME_TRACKING = "timetracking";

	/** Config keyed as {@code group + "." + key}, so the two groups cannot collide. */
	private final Map<String, Object> stored = new HashMap<>();

	private ConfigManager configManager;
	private ContractState contracts;

	@Before
	public void setUp() throws Exception
	{
		configManager = Mockito.mock(ConfigManager.class);
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

	/** The whole integration: an item id in another plugin's config, read as a crop. */
	@Test
	public void theContractIsReadFromTimeTrackingsConfig()
	{
		assignInTimeTracking(Produce.RANARR);

		assertEquals(Produce.RANARR, contracts.getContract());
		assertEquals(PatchImplementation.HERB, contracts.getContractType());
		assertEquals(Seed.RANARR, contracts.getContractSeed());
	}

	/**
	 * An unreadable value is no answer rather than an exception.
	 *
	 * <p>Another plugin's storage is not a contract with us, and the safe direction is silence:
	 * "no contract" stops the plugin insisting on a crop, where a throw would take the sidebar
	 * down with it.
	 */
	@Test
	public void nonsenseInTheConfigKeyReadsAsNoContract()
	{
		stored.put(TIME_TRACKING + ".contract", "not an item id");
		assertNull(contracts.getContract());
		assertFalse(contracts.hasContract());
	}

	/** Only the guild's patches of the contract's own type are claimed. */
	@Test
	public void theContractClaimsTheGuildPatchOfItsType()
	{
		assignInTimeTracking(Produce.RANARR);

		assertTrue("the guild's herb patch is the one it wants",
			contracts.claims(guildPatch(PatchImplementation.HERB)));
		assertFalse("a herb patch elsewhere is not in the guild",
			contracts.claims(herbPatchOutsideTheGuild()));
		assertFalse("another type in the guild is not the contract's",
			contracts.claims(guildPatch(PatchImplementation.BUSH)));
	}

	/**
	 * The completion message leaves the contract findable, not forgotten.
	 *
	 * <p>This is the case the config route cannot express. Time Tracking clears its own key on the
	 * same message, so without our capture a grown contract would read as no contract at all and
	 * the reward would sit unclaimed.
	 */
	@Test
	public void aCompletedContractIsRememberedAfterTimeTrackingClearsItsKey()
	{
		assignInTimeTracking(Produce.RANARR);

		contracts.recordCompleted();
		// What TimeTrackingPlugin does on the very same message.
		stored.remove(TIME_TRACKING + ".contract");

		assertNull("it no longer wants a patch", contracts.getContract());
		assertEquals("but the reward is still owed", Produce.RANARR, contracts.getAwaitingHandIn());
	}

	/** Collecting the reward ends the cycle, so the guide stops asking. */
	@Test
	public void handingInClearsTheHandInFlag()
	{
		assignInTimeTracking(Produce.RANARR);
		contracts.recordCompleted();
		stored.remove(TIME_TRACKING + ".contract");

		contracts.recordHandedIn();

		assertNull(contracts.getAwaitingHandIn());
		assertNull(contracts.getContract());
	}

	/**
	 * Our own capture answers when Time Tracking is switched off.
	 *
	 * <p>Its key simply stops updating then, and the failure is silent — it looks exactly like
	 * having no contract. This is what makes that degrade to a second opinion rather than nothing.
	 */
	@Test
	public void ourOwnCaptureAnswersWhenTimeTrackingSaysNothing()
	{
		contracts.recordAssigned(Produce.SNAPDRAGON);

		assertEquals(Produce.SNAPDRAGON, contracts.getContract());
	}

	/** Theirs wins where both have an opinion, because theirs is written first and maintained. */
	@Test
	public void timeTrackingIsPreferredOverOurOwnCapture()
	{
		contracts.recordAssigned(Produce.SNAPDRAGON);
		assignInTimeTracking(Produce.RANARR);

		assertEquals(Produce.RANARR, contracts.getContract());
	}

	/** A new assignment supersedes a hand-in nobody told us about. */
	@Test
	public void takingANewContractClearsAStaleHandIn()
	{
		contracts.recordAssigned(Produce.RANARR);
		contracts.recordCompleted();
		assertEquals(Produce.RANARR, contracts.getAwaitingHandIn());

		contracts.recordAssigned(Produce.SNAPDRAGON);

		assertNull("Jane hands none out until the last is settled",
			contracts.getAwaitingHandIn());
		assertEquals(Produce.SNAPDRAGON, contracts.getContract());
	}

	/** Absent means on: RuneLite only writes the key once a plugin has been toggled. */
	@Test
	public void timeTrackingReadsAsOnUntilItIsTurnedOff()
	{
		assertTrue(contracts.isTimeTrackingEnabled());

		when(configManager.getConfiguration("runelite", "timetrackingplugin"))
			.thenReturn("false");

		assertFalse(contracts.isTimeTrackingEnabled());
	}

	/**
	 * The patch moves rather than being listed twice — which is the whole reservation mechanism.
	 *
	 * <p>Two groups claiming one patch is precisely the arrangement that has the estimate
	 * promising a snapdragon in ground the contract has already spoken for.
	 */
	@Test
	public void aClaimedPatchLeavesItsOrdinaryGroup() throws Exception
	{
		AvailabilityProfile availability = construct(AvailabilityProfile.class, configManager,
			new Gson(), construct(PatchStateStore.class, configManager, new Gson()));
		FarmPatch guildHerb = guildPatch(PatchImplementation.HERB);
		FarmPatch elsewhere = herbPatchOutsideTheGuild();
		availability.setAvailable(guildHerb, true);
		availability.setAvailable(elsewhere, true);

		DoogleMapsConfig config = Mockito.mock(DoogleMapsConfig.class);
		PlantingGroups groups = construct(PlantingGroups.class, config,
			construct(ProtectedPatches.class, configManager), availability, contracts);

		assertEquals("no contract, so it is an ordinary herb patch",
			PlantingGroup.of(PatchImplementation.HERB), groups.groupFor(guildHerb));

		assignInTimeTracking(Produce.RANARR);

		assertEquals(PlantingGroup.contract(PatchImplementation.HERB), groups.groupFor(guildHerb));
		assertEquals("everything else is untouched",
			PlantingGroup.of(PatchImplementation.HERB), groups.groupFor(elsewhere));
		assertTrue("and the ordinary group no longer counts it",
			groups.patchesIn(PlantingGroup.of(PatchImplementation.HERB)).contains(elsewhere)
				&& !groups.patchesIn(PlantingGroup.of(PatchImplementation.HERB))
					.contains(guildHerb));
	}

	/** Storage keys, which are the compatibility contract for everything already saved. */
	@Test
	public void aContractGroupKeysOnItsOwnType()
	{
		assertEquals("HERB#contract", PlantingGroup.contract(PatchImplementation.HERB).getKey());
		assertEquals("BUSH#contract", PlantingGroup.contract(PatchImplementation.BUSH).getKey());
		assertEquals("HERB", PlantingGroup.of(PatchImplementation.HERB).getKey());
	}

	/** The one seed a contract tab can offer, derived rather than written to the store. */
	@Test
	public void theContractSeedIsDerivedAndCannotBeToggledOff() throws Exception
	{
		assignInTimeTracking(Produce.RANARR);
		SeedSelectionStore seeds = construct(SeedSelectionStore.class, configManager, new Gson(),
			contracts);
		seeds.load();

		PlantingGroup group = PlantingGroup.contract(PatchImplementation.HERB);
		assertEquals(java.util.Collections.singleton(Seed.RANARR), seeds.getSelectedFor(group));

		seeds.toggle(group, Seed.RANARR);

		assertEquals("there is no other answer to offer",
			java.util.Collections.singleton(Seed.RANARR), seeds.getSelectedFor(group));
		assertNull("and nothing the player never chose was persisted",
			stored.get(DoogleMapsConfig.GROUP + ".runSeedsByGroup"));
	}

	/**
	 * The run line is pinned last and named for the job, not the patch.
	 *
	 * <p>Both were reported from play against a cactus contract. Sitting with its own type, the
	 * contract line landed between {@code Cactus} and {@code Cactus (H/O)} and split the pair the
	 * two-column layout works to keep side by side; and reading "Cactus (contract)" invites the
	 * answer "no, I am not doing cactus today", when the decision is whether to do the contract.
	 */
	@Test
	public void theContractRunLineIsLastAndNamedForTheJob() throws Exception
	{
		AvailabilityProfile availability = construct(AvailabilityProfile.class, configManager,
			new Gson(), construct(PatchStateStore.class, configManager, new Gson()));
		FarmPatch guildCactus = guildPatch(PatchImplementation.CACTUS);
		availability.setAvailable(guildCactus, true);

		DoogleMapsConfig config = Mockito.mock(DoogleMapsConfig.class);
		PlantingGroups groups = construct(PlantingGroups.class, config,
			construct(ProtectedPatches.class, configManager), availability, contracts);

		assignInTimeTracking(Produce.CACTUS);

		java.util.List<com.dooglemaps.data.RunOption> options = groups.runOptions();
		com.dooglemaps.data.RunOption contract =
			com.dooglemaps.data.RunOption.full(PlantingGroup.contract(PatchImplementation.CACTUS));

		assertEquals("named for the job rather than this week's crop",
			"Farming Contract", contract.getLabel());
		assertEquals("pinned last, where it cannot come between a run and its harvest-only half",
			options.size() - 1, options.indexOf(contract));

		// The pair it used to split must still be adjacent, which is what the layout relies on.
		int full = options.indexOf(
			com.dooglemaps.data.RunOption.full(PlantingGroup.of(PatchImplementation.CACTUS)));
		int harvest = options.indexOf(
			com.dooglemaps.data.RunOption.harvestOnly(PlantingGroup.of(PatchImplementation.CACTUS)));
		assertEquals("the full run and its harvest-only half stay together",
			full + 1, harvest);
	}

	/**
	 * A split group inherits the type's protection rather than starting unprotected.
	 *
	 * <p>Reported from play: a cactus contract arrived as a brand-new group, so a player who
	 * protects their cactus everywhere found the one patch that pays a seed pack silently
	 * unprotected. Not made automatic — protection costs items — but inherited, which is the same
	 * mechanism compost has had since the protected-herb split.
	 */
	@Test
	public void protectionIsInheritedFromTheTypeUntilTheGroupIsGivenAnAnswer() throws Exception
	{
		ProtectionSelectionStore protection =
			construct(ProtectionSelectionStore.class, configManager, new Gson());
		PlantingGroup plain = PlantingGroup.of(PatchImplementation.CACTUS);
		PlantingGroup contract = PlantingGroup.contract(PatchImplementation.CACTUS);

		assertFalse("nothing chosen anywhere stays off",
			protection.isProtecting(contract, Seed.CACTUS));

		protection.setProtecting(plain, Seed.CACTUS, true);
		assertTrue("the contract inherits what cactus already answers",
			protection.isProtecting(contract, Seed.CACTUS));

		protection.setProtecting(contract, Seed.CACTUS, false);
		assertFalse("and an explicit no on the contract tab sticks",
			protection.isProtecting(contract, Seed.CACTUS));
		assertTrue("without disturbing the type it inherited from",
			protection.isProtecting(plain, Seed.CACTUS));
	}

	/** An explicit no survives a reload, or the fallback would switch it back on. */
	@Test
	public void anExplicitRefusalSurvivesAReload() throws Exception
	{
		ProtectionSelectionStore protection =
			construct(ProtectionSelectionStore.class, configManager, new Gson());
		PlantingGroup contract = PlantingGroup.contract(PatchImplementation.CACTUS);

		protection.setProtecting(PlantingGroup.of(PatchImplementation.CACTUS), Seed.CACTUS, true);
		protection.setProtecting(contract, Seed.CACTUS, false);

		ProtectionSelectionStore reloaded =
			construct(ProtectionSelectionStore.class, configManager, new Gson());
		reloaded.load();

		assertFalse(reloaded.isProtecting(contract, Seed.CACTUS));
		assertTrue(reloaded.isProtecting(PlantingGroup.of(PatchImplementation.CACTUS), Seed.CACTUS));
	}

	/** Exactly what Time Tracking writes: the harvested item's id, as a string. */
	private void assignInTimeTracking(Produce produce)
	{
		stored.put(TIME_TRACKING + ".contract", String.valueOf(produce.getItemID()));
	}

	/**
	 * What the game does when a contract finishes growing.
	 *
	 * <p>Time Tracking clears its own key on the completion message, which is the whole reason
	 * {@code ContractState} captures the event separately — see its class note. Our
	 * {@code recordCompleted} cannot do this for it: another plugin's key is not ours to write.
	 */
	private void clearTimeTrackingContract()
	{
		stored.remove(TIME_TRACKING + ".contract");
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
		throw new AssertionError("no " + type + " patch in the Farming Guild");
	}

	private static FarmPatch herbPatchOutsideTheGuild()
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.HERB))
		{
			if (patch.getRegion().getRegionId() != ContractState.FARMING_GUILD_REGION)
			{
				return patch;
			}
		}
		throw new AssertionError("no herb patch outside the Farming Guild");
	}

	@SuppressWarnings("unchecked")
	private static <T> T construct(Class<T> type, Object... args) throws Exception
	{
		for (Constructor<?> candidate : type.getDeclaredConstructors())
		{
			if (candidate.getParameterCount() == args.length)
			{
				candidate.setAccessible(true);
				return (T) candidate.newInstance(args);
			}
		}
		throw new IllegalStateException("no constructor of arity " + args.length + " on " + type);
	}
	/**
	 * A grown contract keeps its patch out of the ordinary group until the reward is collected.
	 *
	 * <h2>The most expensive mistake the plugin could make</h2>
	 *
	 * Reported from play: a limpwurt contract standing grown in the guild's flower patch, and the
	 * run offering to compost it and plant an ordinary limpwurt on top. Doing that locks the
	 * contract out until the replacement finishes growing.
	 *
	 * <p>The cause is that a contract's completion message clears <b>both</b> config keys — Time
	 * Tracking's and ours — so {@code claims} goes false at the exact moment the crop is standing
	 * there waiting to be handed in. The patch fell straight back into its ordinary group, and the
	 * ordinary group plants things.
	 *
	 * <p>So the grouping question is "is this ground spoken for", which runs from assignment to
	 * reward. Whether to ask for a <i>seed</i> over that span is a different question with a
	 * different answer, and lives in {@code RunLoadout}.
	 */
	@Test
	public void aGrownContractStillOwnsItsPatch() throws Exception
	{
		FarmPatch guildFlower = guildPatch(PatchImplementation.FLOWER);

		assignInTimeTracking(Produce.LIMPWURT);
		assertTrue("assigned, so it is claimed", contracts.claims(guildFlower));
		assertTrue(contracts.claimsUntilHandedIn(guildFlower));

		// The crop finishes. Our capture records it; Time Tracking clears its own key off the same
		// chat message, which is what leaves nothing "assigned".
		contracts.recordCompleted();
		clearTimeTrackingContract();

		assertFalse("nothing is assigned any more, which is true and not the useful question",
			contracts.claims(guildFlower));
		assertTrue("but the ground is still spoken for until the reward is collected",
			contracts.claimsUntilHandedIn(guildFlower));
		assertEquals("and the type it holds is still known",
			PatchImplementation.FLOWER, contracts.getActiveContractType());

		contracts.recordHandedIn();

		assertFalse("collected, so the patch goes back to being an ordinary flower patch",
			contracts.claimsUntilHandedIn(guildFlower));
		assertNull(contracts.getActiveContractType());
	}
}
