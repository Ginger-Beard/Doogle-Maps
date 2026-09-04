package com.dooglemaps;

import com.dooglemaps.capture.CompostCapture;
import com.dooglemaps.capture.PatchInteractionTracker;
import com.dooglemaps.capture.BankCapture;
import com.dooglemaps.capture.PatchLocationCapture;
import com.dooglemaps.capture.ContractCapture;
import com.dooglemaps.capture.ProtectionCapture;
import com.dooglemaps.capture.SeedCapture;
import com.dooglemaps.capture.TeleportChargeCapture;
import com.dooglemaps.bank.BankContents;
import com.dooglemaps.bank.BankFilter;
import com.dooglemaps.bank.BankHighlightOverlay;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.guide.CarriedItems;
import com.dooglemaps.guide.DroppedProduce;
import com.dooglemaps.guide.GuideInventoryOverlay;
import com.dooglemaps.guide.GuideOverlay;
import com.dooglemaps.guide.GuideStepOverlay;
import com.dooglemaps.state.AvailabilityProfile;
import com.dooglemaps.state.CompostSelectionStore;
import com.dooglemaps.state.FarmingBonusStore;
import com.dooglemaps.state.LeprechaunStore;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.state.PlayerHouse;
import com.dooglemaps.state.PlayerLocation;
import com.dooglemaps.state.ProtectedPatches;
import com.dooglemaps.state.ProtectionSelectionStore;
import com.dooglemaps.state.ProfileReset;
import com.dooglemaps.route.BankLocationStore;
import com.dooglemaps.route.PatchLocationStore;
import com.dooglemaps.route.RunPlanner;
import com.dooglemaps.route.ShortestPathIntegration;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.state.RunTypeStore;
import com.dooglemaps.state.SeedSelectionStore;
import com.dooglemaps.ui.DoogleMapsPanel;
import com.dooglemaps.validate.GeomancyProbe;
import com.dooglemaps.validate.HarvestLog;
import com.dooglemaps.validate.DiseaseStatsStore;
import com.dooglemaps.validate.HarvestHistory;
import com.dooglemaps.validate.HarvestStatsStore;
import com.dooglemaps.ui.PluginIcon;
import com.dooglemaps.ui.ReadyInfoBox;
import com.google.inject.Provides;
import java.time.Duration;
import java.time.Instant;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.eventbus.Subscribe;

/**
 * Farming overview and, in time, a guided farm-run helper.
 *
 * <p>Caches the state of every farming patch as you interact with it — plant, compost,
 * protect, harvest, check health — and shows the lot in a Geomancy-style sidebar, so you
 * can see your whole farm without visiting it. Geomancy fills everything in at once for
 * players who have it, but is not needed: the cache fills in patch by patch as you go.
 *
 * <p>Read-only and display-only throughout. It highlights and it tells you things; every
 * click is still yours.
 */
@Slf4j
@PluginDescriptor(
	name = "Doogle Maps",
	description = "Farming overview and guided farm-run helper - see every patch and plan your run",
	tags = {"farming", "farm", "run", "herb", "tree", "patch", "geomancy", "time", "tracking"}
)
public class DoogleMapsPlugin extends Plugin
{
	/**
	 * How often the panel redraws while nothing is happening, so timers tick down and
	 * "ready in 3m" becomes "ready" without needing an event.
	 */
	private static final Duration IDLE_REFRESH_INTERVAL = Duration.ofSeconds(20);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	/**
	 * RuneLite's shared background executor, for file work the client thread must not pay
	 * for. Single-threaded, so tasks queued here also serialize against each other.
	 */
	@Inject
	private java.util.concurrent.ScheduledExecutorService executor;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private InfoBoxManager infoBoxManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private CarriedItems carriedItems;

	@Inject
	private PlayerLocation playerLocation;

	@Inject
	private GuideOverlay guideOverlay;

	@Inject
	private GuideInventoryOverlay guideInventoryOverlay;

	@Inject
	private GuideStepOverlay guideStepOverlay;

	@Inject
	private LeprechaunStore leprechaunStore;

	@Inject
	private PlayerHouse playerHouse;

	@Inject
	private DroppedProduce droppedProduce;

	@Inject
	private com.dooglemaps.guide.SeaweedSpores seaweedSpores;

	@Inject
	private com.dooglemaps.guide.GuideMenuSwap guideMenuSwap;

	@Inject
	private ProtectedPatches protectedPatches;

	@Inject
	private com.dooglemaps.state.TimeTrackingState timeTracking;

	@Inject
	private com.dooglemaps.data.ItemNames itemNames;

	@Inject
	private net.runelite.client.game.ItemManager itemManager;

	@Inject
	private ProtectionSelectionStore protectionSelection;

	@Inject
	private BankFilter bankFilter;

	@Inject
	private com.dooglemaps.bank.InventorySetupsHandoff inventorySetupsHandoff;

	@Inject
	private com.dooglemaps.guide.GuideTracker guideTracker;

	@Inject
	private BankContents bankContents;

	@Inject
	private com.dooglemaps.bank.BoatHolds boatHolds;

	@Inject
	private BankHighlightOverlay bankHighlightOverlay;

	@Inject
	private EventBus eventBus;

	@Inject
	private ConfigManager configManager;

	@Inject
	private DoogleMapsConfig config;

	@Inject
	private PatchStateStore stateStore;

	/** Only ever told to drop its cached offset when a profile loads. See {@link #load()}. */
	@Inject
	private com.dooglemaps.timer.GrowthTimer growthTimer;

	@Inject
	private AvailabilityProfile availability;

	@Inject
	private PatchInteractionTracker interactionTracker;

	@Inject
	private CompostCapture compostCapture;

	@Inject
	private ProtectionCapture protectionCapture;

	@Inject
	private ContractCapture contractCapture;

	@Inject
	private com.dooglemaps.state.ContractState contracts;

	@Inject
	private SeedCapture seedCapture;

	@Inject
	private TeleportChargeCapture teleportChargeCapture;

	@Inject
	private SeedInventoryStore seedStore;

	@Inject
	private PatchLocationCapture locationCapture;

	@Inject
	private PatchLocationStore patchLocations;

	@Inject
	private ShortestPathIntegration router;

	@Inject
	private BankCapture bankCapture;

	@Inject
	private BankLocationStore bankLocations;

	@Inject
	private RunPlanner runPlanner;

	@Inject
	private com.dooglemaps.bank.RunLoadout runLoadout;

	@Inject
	private SeedSelectionStore seedSelection;

	@Inject
	private RunTypeStore runTypes;

	/** Named tickbox sets, loaded beside runTypes because it is the same choice, saved. */
	@Inject
	private com.dooglemaps.state.RunPresetStore runPresets;

	@Inject
	private CompostSelectionStore compostSelection;

	@Inject
	private HarvestLog harvestLog;

	@Inject
	private HarvestStatsStore harvestStats;

	@Inject
	private HarvestHistory harvestHistory;

	@Inject
	private DiseaseStatsStore diseaseStats;

	@Inject
	private com.dooglemaps.data.ItemPrices itemPrices;

	@Inject
	private FarmingBonusStore bonusStore;

	@Inject
	private ProfileReset profileReset;

	@Inject
	private GeomancyProbe geomancyProbe;

	@Inject
	private DoogleMapsPanel panel;

	private NavigationButton navigationButton;
	private ReadyInfoBox readyInfoBox;

	private final Runnable onStateChanged = this::refresh;

	/** The protected tab can only be built once the unlocks are known, which is after startUp. */
	private final Runnable onProtectionChanged = () -> panel.structureChanged();

	/**
	 * Keeps the stored "Farming Contract" tick pointed at whatever Jane has assigned.
	 *
	 * <p>Registered before {@code onProtectionChanged} on the contract store, deliberately: the
	 * key has to be renamed before the panel rebuilds its checkboxes off it, or the fresh
	 * contract's line draws unticked once and flickers. See {@code RunTypeStore.retargetContract}.
	 */
	private final Runnable onContractChanged = this::retargetContractTick;

	private void retargetContractTick()
	{
		com.dooglemaps.data.Produce contract = contracts.getContract();
		if (contract != null)
		{
			runTypes.retargetContract(contract.getPatchImplementation());

			// And the saved profiles, in lockstep, or applying one after a hand-in unticks
			// the contract — the stored key still names the previous assignment's type.
			runPresets.retargetContract(contract.getPatchImplementation());

			// The protection store gets the opposite treatment — stale contract choices are
			// dropped, not renamed — see its retargetContract for why the two differ.
			com.dooglemaps.data.Seed seed = contracts.getContractSeed();
			protectionSelection.retargetContract(seed == null ? null
				: com.dooglemaps.state.ProtectionSelectionStore.contractKey(
					com.dooglemaps.data.PlantingGroup.contract(contract.getPatchImplementation()),
					seed));
		}
	}
	private Instant lastIdleRefresh = Instant.EPOCH;

	/**
	 * Whether the cache has been read for the current profile.
	 *
	 * <p>Loading is deferred until RuneLite resolves a RuneScape profile, and the events
	 * that say it has are not wholly dependable — an account with two stored profiles logs
	 * "switching to already-active profile", which may mean no ProfileChanged at all. So
	 * rather than trust the event, the idle tick retries until it works.
	 */
	private boolean loaded;

	@Provides
	DoogleMapsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(DoogleMapsConfig.class);
	}

	@Override
	protected void startUp()
	{
		// The capture components subscribe to game events themselves rather than being
		// funnelled through this class, so they need registering by hand.
		eventBus.register(interactionTracker);
		eventBus.register(compostCapture);
		eventBus.register(protectionCapture);
		eventBus.register(contractCapture);
		eventBus.register(seedCapture);
		eventBus.register(teleportChargeCapture);
		eventBus.register(locationCapture);
		eventBus.register(bankCapture);
		eventBus.register(router);
		eventBus.register(harvestLog);
		eventBus.register(geomancyProbe);
		eventBus.register(carriedItems);
		eventBus.register(playerLocation);
		eventBus.register(guideTracker);
		eventBus.register(bankContents);
		eventBus.register(boatHolds);
		eventBus.register(leprechaunStore);
		eventBus.register(playerHouse);
		eventBus.register(droppedProduce);
		eventBus.register(seaweedSpores);
		eventBus.register(guideMenuSwap);
		eventBus.register(bankFilter);
		eventBus.register(inventorySetupsHandoff);
		// For its cache invalidation alone - registered before the capture classes run
		// (the bus orders same-priority subscribers by class name, and bank.* precedes
		// capture.*), so a withdrawal's same-tick readers see a fresh loadout.
		eventBus.register(runLoadout);
		bankFilter.startUp();

		// Anything drawn from the bank — the protection rows' "you have 8 of the 24 this run
		// needs", the loadout's withdraw marks — is stale the moment a bank is opened, and
		// nothing else was asking the panel to look again.
		bankContents.addChangeListener(onStateChanged);
		protectedPatches.addChangeListener(onProtectionChanged);
		// A contract appearing or being handed in adds or removes a whole planting group, so the
		// tab strip and the run list both have to be rebuilt rather than merely repainted.
		// The retarget first — see its field doc for why the order matters.
		contracts.addChangeListener(onContractChanged);
		contracts.addChangeListener(onProtectionChanged);
		stateStore.addChangeListener(onStateChanged);
		availability.addChangeListener(onStateChanged);
		seedStore.addChangeListener(onStateChanged);
		seedSelection.addChangeListener(onStateChanged);
		// Which patch types the run covers decides what the ready count filters to, so it has to
		// be told as well. Without this, applying a run preset left the in-game number counting
		// the previous selection until the idle refresh. See RunTypeStore.addChangeListener.
		runTypes.addChangeListener(onStateChanged);

		navigationButton = NavigationButton.builder()
			.tooltip("Doogle Maps")
			.icon(PluginIcon.create())
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navigationButton);

		readyInfoBox = new ReadyInfoBox(PluginIcon.create(), this, panel, config,
			runPlanner, guideTracker);
		infoBoxManager.addInfoBox(readyInfoBox);

		overlayManager.add(guideOverlay);
		overlayManager.add(guideInventoryOverlay);
		overlayManager.add(guideStepOverlay);
		overlayManager.add(bankHighlightOverlay);

		load();

		log.info("Doogle Maps started");
	}

	@Override
	protected void shutDown()
	{
		log.info("Doogle Maps stopped");

		eventBus.unregister(interactionTracker);
		eventBus.unregister(compostCapture);
		eventBus.unregister(protectionCapture);
		eventBus.unregister(contractCapture);
		eventBus.unregister(seedCapture);
		eventBus.unregister(teleportChargeCapture);
		eventBus.unregister(locationCapture);
		eventBus.unregister(bankCapture);
		eventBus.unregister(router);
		eventBus.unregister(harvestLog);
		eventBus.unregister(geomancyProbe);
		eventBus.unregister(carriedItems);
		eventBus.unregister(playerLocation);
		eventBus.unregister(guideTracker);
		eventBus.unregister(bankContents);
		eventBus.unregister(boatHolds);
		eventBus.unregister(leprechaunStore);
		eventBus.unregister(playerHouse);
		eventBus.unregister(droppedProduce);
		eventBus.unregister(seaweedSpores);
		eventBus.unregister(guideMenuSwap);
		eventBus.unregister(bankFilter);
		eventBus.unregister(inventorySetupsHandoff);
		eventBus.unregister(runLoadout);

		bankContents.removeChangeListener(onStateChanged);
		protectedPatches.removeChangeListener(onProtectionChanged);
		contracts.removeChangeListener(onContractChanged);
		contracts.removeChangeListener(onProtectionChanged);
		stateStore.removeChangeListener(onStateChanged);
		availability.removeChangeListener(onStateChanged);
		seedStore.removeChangeListener(onStateChanged);
		seedSelection.removeChangeListener(onStateChanged);
		runTypes.removeChangeListener(onStateChanged);

		overlayManager.remove(guideOverlay);
		overlayManager.remove(guideInventoryOverlay);
		overlayManager.remove(guideStepOverlay);
		overlayManager.remove(bankHighlightOverlay);

		infoBoxManager.removeInfoBox(readyInfoBox);
		readyInfoBox = null;

		clientToolbar.removeNavigation(navigationButton);
		navigationButton = null;
		loaded = false;

		// After the UI teardown above, deliberately. This is the one shutdown step that talks
		// to another plugin, which makes it the one most able to fail — and when it threw, it
		// took the infobox and overlay removals below it down too. Last, so whatever it does,
		// nothing of ours is left on screen.
		bankFilter.shutDown();

		// The other talker, kept beside the first for the same reason — and safe on the EDT,
		// because its goodbye is handed to the client thread rather than posted here.
		inventorySetupsHandoff.shutDown();

		interactionTracker.reset();
		compostCapture.reset();
		harvestLog.reset();
		protectionCapture.reset();
		contractCapture.reset();
		teleportChargeCapture.reset();
		carriedItems.reset();
		playerLocation.reset();
		guideTracker.reset();
		bankContents.reset();
		leprechaunStore.reset();
		playerHouse.reset();
		droppedProduce.reset();
		seaweedSpores.reset();
		protectedPatches.reset();
		runPlanner.stop();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			// Only once per session. LOGGED_IN is not "the player logged in" - it fires again
			// every time the world finishes loading, which includes every teleport. Reloading
			// there threw away the in-memory stores and re-read config, so a seed or compost
			// choice made since the last write vanished on the next hop.
			//
			// A real login still reloads: LOGIN_SCREEN clears the flag on the way out.
			if (!loaded)
			{
				load();
			}
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			// A different account may log in next, so the cache has to be read again.
			loaded = false;
			resetSessionState();
		}
	}

	/**
	 * Forgets everything scoped to the account that just left.
	 *
	 * <p>Every one of these was reset only on plugin shutdown, which made them silently
	 * <b>session</b>-scoped across account switches: account A's herb-patch unlocks memoised
	 * so account B's were never written (the protected-herb tab simply absent for B all
	 * session), A's leprechaun stores and house-portal answer inherited by B, a payment-patch
	 * selection from A's dialogue redeemable by B's. Cleared on the way out — at the login
	 * screen and on a profile change — so the next account starts from "never looked" rather
	 * than from someone else's answers. Everything here rebuilds by playing, most of it
	 * within a tick of logging in.
	 */
	private void resetSessionState()
	{
		protectedPatches.reset();
		leprechaunStore.reset();
		playerHouse.reset();
		protectionCapture.reset();
		teleportChargeCapture.reset();
		seedStore.forgetSession();
	}

	/** Time Tracking's group and contract key, watched for the reason {@code onConfigChanged} gives. */
	private static final String CONTRACT_CONFIG_GROUP = "timetracking";
	private static final String CONTRACT_CONFIG_KEY = "contract";

	/**
	 * The keys that are actually settings — the ones {@code onConfigChanged} should rebuild for.
	 *
	 * <p>The plugin's own stores persist state into the same config group: farming xp, seed
	 * counts, harvest stats, growth timers. Every one of those writes posts a {@code
	 * ConfigChanged} just like a settings toggle does, and treating them alike put a full panel
	 * refresh behind every write. Farming experience was the one that hurt — it is written per
	 * xp drop, several times a game tick while picking, so the Stats page was being torn down
	 * and rebuilt continuously and visibly jerked around. The intended cadence is the 20-second
	 * idle refresh, roughly two orders of magnitude slower.
	 *
	 * <p>State writes do not need this listener at all: the stores that own them fire their own
	 * change listeners when something worth repainting happens, on their own judgement of what
	 * is worth it — that is what {@code onStateChanged} is wired to.
	 */
	private static final java.util.Set<String> SETTING_KEYS = settingKeys();

	private static java.util.Set<String> settingKeys()
	{
		java.util.Set<String> keys = new java.util.HashSet<>();
		for (java.lang.reflect.Method method : DoogleMapsConfig.class.getMethods())
		{
			net.runelite.client.config.ConfigItem item =
				method.getAnnotation(net.runelite.client.config.ConfigItem.class);
			if (item != null)
			{
				keys.add(item.keyName());
			}
		}
		return keys;
	}

	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		// Config is per RuneScape profile, so switching accounts swaps the whole cache —
		// and the in-memory session state has to go with it; see resetSessionState.
		resetSessionState();
		load();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		// Time Tracking's contract key is another plugin's storage, and it changing means a whole
		// planting group has appeared or gone away — so the tab strip and the run list are
		// rebuilt, not merely repainted. Listened for rather than polled because the contract is
		// read on every group lookup, and a stale strip beside a fresh lookup is the arrangement
		// that has a tab and its contents disagreeing.
		if (CONTRACT_CONFIG_GROUP.equals(event.getGroup())
			&& CONTRACT_CONFIG_KEY.equals(event.getKey()))
		{
			// A fresh assignment from Time Tracking is proof the previous contract was
			// settled, seen or not - the trigger reconcileAwaitingHandIn was written for. What
			// the guild's ground shows goes with it, because the other half of that judgment -
			// whether the SAME crop still assigned there is corruption - cannot be made from
			// config alone. See GuideTracker.contractGroundEvidence.
			contracts.reconcileAwaitingHandIn(guideTracker.contractGroundEvidence());
			panel.structureChanged();
			refresh();
			return;
		}

		if (!DoogleMapsConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		if (DoogleMapsConfig.RESET_PROFILE_KEY.equals(event.getKey()))
		{
			handleResetRequest();
			return;
		}

		if (DoogleMapsConfig.CLEAR_HARVEST_STATS_KEY.equals(event.getKey()))
		{
			handleClearStatsRequest();
			return;
		}

		// The plugin's own persisted state shares the group; see SETTING_KEYS for why it
		// must not land here.
		if (!SETTING_KEYS.contains(event.getKey()))
		{
			return;
		}

		// Turning a patch type off removes its tab outright rather than just changing what
		// is drawn on it, so the panel decides whether this key needs the strip rebuilding.
		panel.configChanged(event.getKey());
		refresh();
	}

	/**
	 * Deletes the harvest history, then puts the switch back.
	 *
	 * <p>Separate from {@link #handleResetRequest()} and deliberately so. A profile reset
	 * throws away what the plugin worked out, every bit of which returns by playing; this
	 * throws away a record of things that already happened, and nothing rebuilds it. Sharing
	 * a button would mean either losing the history to a click meant for stale patch state or
	 * never being able to clear it.
	 */
	private void handleClearStatsRequest()
	{
		if (!config.clearHarvestStats())
		{
			return;
		}

		configManager.setConfiguration(DoogleMapsConfig.GROUP, DoogleMapsConfig.CLEAR_HARVEST_STATS_KEY, false);

		if (configManager.getRSProfileKey() == null)
		{
			log.warn("Not clearing harvest stats: no RuneScape profile is active, so there is "
				+ "nothing scoped to this account to clear. Log in first.");
			return;
		}

		harvestStats.clear();
		harvestHistory.clear();
		diseaseStats.clear();
		log.info("Doogle Maps harvest history cleared");
		refresh();
	}

	/**
	 * Performs a reset asked for from the settings, then puts the switch back.
	 *
	 * <p>The config item is a trigger rather than a setting, so it must not stay on: leaving
	 * it enabled would wipe the cache again on the next restart, which is nobody's intent.
	 * Turning it off fires another ConfigChanged, which lands here and does nothing because
	 * the value is now false.
	 */
	private void handleResetRequest()
	{
		if (!config.resetProfile())
		{
			return;
		}

		configManager.setConfiguration(DoogleMapsConfig.GROUP, DoogleMapsConfig.RESET_PROFILE_KEY, false);

		if (configManager.getRSProfileKey() == null)
		{
			log.warn("Not resetting: no RuneScape profile is active, so there is nothing scoped "
				+ "to this account to clear. Log in first.");
			return;
		}

		profileReset.reset();
		interactionTracker.reset();
		compostCapture.reset();
		harvestLog.reset();
		runPlanner.stop();

		// A reset should land on the fresh-install state, not an empty one. Reloading runs
		// the same backfill a first run does, so core Time Tracking's own cache of these
		// varbits repopulates the overview straight away.
		load();

		// And relearn what the client can still tell us without the player doing anything:
		// the Farming level, plus every seed container it is currently holding. Both are
		// client reads, so they go through the client thread — re-queued until logged in,
		// same as load()'s priming block and for the same reason.
		clientThread.invoke(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				return false;
			}
			seedStore.recordFarmingLevel();
			seedStore.recordWoodcuttingLevel();
			seedStore.relearnFromClient();
			carriedItems.relearnFromClient();
			bonusStore.relearnFromClient();
			return true;
		});
	}

	/**
	 * Says what the run loadout resolved to, the first time each bank is opened.
	 *
	 * <p>Once per opening rather than per tick: the answer only changes when you act on it, and a
	 * line a frame would be useless. See {@code RunLoadout.logState} for why it is worth saying at
	 * all — nothing showing in the bank has five causes that look identical from in front of it.
	 */
	@Subscribe
	public void onWidgetLoaded(net.runelite.api.events.WidgetLoaded event)
	{
		if (event.getGroupId() == net.runelite.api.gameval.InterfaceID.BANKMAIN)
		{
			runLoadout.logState(runPlanner.coveredTypes(), runPlanner.isActive());
		}
	}

	/**
	 * Learns the game's name for whatever lands on the player.
	 *
	 * <p>The counterpart of {@code BankCapture.recordNames}, for the same consumer: the
	 * teleport list matches by name against the bank <i>and the pack</i>, and an item bought
	 * or withdrawn mid-session has a name the bank read never saw. Client-thread event,
	 * cached lookups, so an unchanged pack costs a set intersection and nothing more.
	 */
	@Subscribe
	public void onItemContainerChanged(net.runelite.api.events.ItemContainerChanged event)
	{
		if (event.getContainerId() != net.runelite.api.gameval.InventoryID.INV
			&& event.getContainerId() != net.runelite.api.gameval.InventoryID.WORN)
		{
			return;
		}

		java.util.List<Integer> ids = new java.util.ArrayList<>();
		if (event.getItemContainer() != null)
		{
			for (net.runelite.api.Item item : event.getItemContainer().getItems())
			{
				if (item != null && item.getId() > 0)
				{
					ids.add(item.getId());
				}
			}
		}
		itemNames.record(itemManager, ids);
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// Catch a load that the profile events never triggered. Cheap: one null check per
		// tick once loaded, and it means an unreliable event cannot leave the panel blank
		// for a whole session.
		if (!loaded)
		{
			load();
		}

		// The supply leg ends when there is nothing left to collect, and that can become true
		// without a bank event: withdrawing the last seed from the *vault* fires nothing the bank
		// capture listens for. The flag is refreshed first so the answer is this tick's, not the
		// guide's last push; cheap either way — the loadout build is cached per tick. The guide
		// owns the answer because it is not always the loadout's: a hespori run's leg is a gear
		// stop. See GuideTracker.supplyLegOutstanding.
		if (runPlanner.isActive())
		{
			runPlanner.setWithdrawOutstanding(guideTracker.supplyLegOutstanding());
		}
		runPlanner.leaveBank();

		// And a stop can finish without a varbit transition to announce it — a patch never seen
		// before this session has no previous value to differ from, so arriving at one is silent.
		// Polling is what stops the run waiting for news that will never come; completion is
		// derived, so asking again is always correct.
		runPlanner.reviewProgress();

		// And a contract taken from Jane mid-stop brings a patch the run was never planned around.
		// Ordered after reviewProgress deliberately: adopting the patch is what stops the guild
		// being written off as finished, so it must not be undone by the review that runs next.
		runPlanner.reviewContract();

		// And the allotment bins, for the same reason and by the same means. Their stops are
		// planned once at the start, so switching "fill bins from your harvest" on mid-run - or a
		// bin finishing its compost an hour in - would otherwise be invisible for the rest of the
		// trip. They still only ever join a stop the run is already making.
		runPlanner.reviewBins();

		// And a tool can leave your pack mid-run - deposited by accident is the reported way -
		// which nothing used to notice, because whether the run needed a bank was decided once
		// at the start. Silent unless the leprechaun has none either, since his copy is the
		// cheaper trip and the guide's own tool step already offers it at the patch.
		runPlanner.reviewSupplies();

		// And supplies that became outstanding mid-run are collected where they are cheap:
		// a finished stop with a supply point in its own region goes to the chest before the
		// next teleport, instead of leaving the errand for the end of the run. The guild's
		// emptied big bin is the case - its fill is in the bank fourteen tiles from the bin.
		runPlanner.reviewNearbySupplies();

		// Which herb patches cannot be diseased, re-read rather than sampled once at login.
		// The load fires the instant LOGGED_IN does, and the quest and diary varbits are not
		// all synced by then — a sample taken a second early reads nothing and used to latch,
		// which is exactly how the protected herb tab went missing for a whole session. This
		// is on the client thread already, and the read leaves early when nothing has changed.
		protectedPatches.refresh(client);

		// The sidebar's current-step line, which the guide re-derives every tick. It used to wait
		// for the idle refresh below and so ran up to twenty seconds behind the on-screen panel —
		// long enough to still be naming a patch you had finished and walked away from. Cheap: one
		// label and one button, not the tab rebuild that timer exists to space out.
		panel.refreshLive();

		Instant now = Instant.now();
		if (Duration.between(lastIdleRefresh, now).compareTo(IDLE_REFRESH_INTERVAL) >= 0)
		{
			lastIdleRefresh = now;
			refresh();
		}
	}

	private void load()
	{
		// Everything we persist is scoped to a RuneScape profile, and that profile is not
		// resolved until after login. Loading before then reads nothing and wipes the
		// in-memory state — which is how switched-off patches came back on every login.
		if (configManager.getRSProfileKey() == null)
		{
			log.debug("No RuneScape profile yet; deferring load");
			return;
		}

		// Before anything reads a projection. The growth timer caches the account's farm-tick
		// offset and auto-weed flag, and a load may be a different account entirely — a stale
		// offset puts every "ready in" on the wrong grid, silently, by up to half an hour.
		growthTimer.invalidate();

		stateStore.load();
		// Anything Time Tracking recorded that we never watched happen. After the load, because
		// it fills gaps in what was just read rather than replacing it.
		stateStore.backfillFrom(timeTracking);
		// Wired before the load, so the very first availability question already knows the level.
		// The Farming Guild's tiers are doors rather than preferences; see PatchRequirements.
		availability.setFarmingLevel(seedStore::getFarmingLevel);
		// Locations the player switched off are unavailable, not merely hidden — see Locations.
		availability.setLocationFilter(patch ->
			com.dooglemaps.ui.Locations.isEnabled(config, patch));
		// What the house actually holds, so the router stops imagining portals - see PlayerHouse.
		router.setHousePortalKnowledge(playerHouse::hasPortalRoomPortals);
		// Whether the player is instanced, so the router's own in-instance recomputes - made
		// from template coordinates that mean nothing - are not read as the route. Instances
		// live at x >= 6400; the region id carries x in its top byte in units of 64 tiles.
		router.setInstanceKnowledge(() ->
		{
			int region = playerLocation.getRegionId();
			return region >= 0 && (region >>> 8) >= 100;
		});
		// The house's front door, so a retarget made while standing in the POH can route from
		// where the journey out actually begins instead of going silent - see RunPlanner.retarget.
		runPlanner.setHouseKnowledge(() ->
			playerHouse.isInside() ? playerHouse.frontDoor() : null);
		availability.load();
		seedStore.load();
		// The remembered bank, so the loadout, the withdraw list and the filter start informed
		// rather than waiting for the first bank open of the session. See BankContents.
		bankContents.load();
		boatHolds.load();
		// The Farming level is only otherwise learned from a Farming XP drop, which may not
		// come for hours. Without it every yield estimate stays hidden, so it is read
		// outright whenever we load.
		//
		// invoke(BooleanSupplier), not invokeLater(Runnable). The queue drains on every
		// client frame regardless of game state, so an invokeLater posted while the world
		// was still LOADING - or from a load at the login screen - evaluated its guard
		// once, no-opped, and was gone; loaded was already true, so the onGameTick and
		// LOGGED_IN retries both declined, and the whole priming block was silently lost
		// for the session. Item prices never recorded, the pack's seeds invisible, worn
		// gear unowned. A false-returning invoke is re-queued by RuneLite until it says
		// true, which turns "primed" from a hope into a fact.
		clientThread.invoke(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				seedStore.recordFarmingLevel();
				seedStore.recordWoodcuttingLevel();
				bonusStore.recordDiaries();

				// Every seed container the client is still holding, which is at minimum the
				// inventory. Two cases need it and neither sends an event we could wait for: a
				// plugin switched on mid-session was never told the inventory in the first place,
				// and a profile change hands us a different account's pack without anything
				// having moved in it. An inventory nobody has touched is never re-sent, so
				// without this the seeds in it stay invisible until something disturbs them.
				seedStore.relearnFromClient();

				// The same read, for the same reason, over the pack and the worn items rather
				// than the seed containers. Without it every teleport, cloak and ring you are
				// already carrying reads as missing and goes on the withdraw list.
				carriedItems.relearnFromClient();

				// And the bonuses, whose staleness is worse: they are persisted, so a cape
				// taken off while the plugin was not looking stayed "+5%" across restarts.
				bonusStore.relearnFromClient();

				// Protection payment names, read here because getItemComposition is a client
				// thread call and the sidebar needs them on Swing. A fixed set, read once.
				java.util.List<Integer> paymentItems = new java.util.ArrayList<>();
				for (com.dooglemaps.data.ProtectionPayment payment
					: com.dooglemaps.data.ProtectionPayment.values())
				{
					paymentItems.add(payment.getItemID());
				}
				itemNames.record(itemManager, paymentItems);

				// Names for everything the remembered bank holds, for the same reason and on
				// the same thread. BankCapture does this on every bank open; doing it here is
				// what lets the teleport list match by name before the first open of the
				// session, now the bank's contents survive one. Cached, so it is only
				// expensive the first time.
				itemNames.record(itemManager,
					new java.util.ArrayList<>(bankContents.getItemIds()));

				// And for everything on the player, because the teleport list matches the
				// pack too - a house tab you always carry may exist in the bank only as a
				// placeholder, which is not contents. After relearnFromClient above, so the
				// pack has actually been read.
				itemNames.record(itemManager,
					new java.util.ArrayList<>(carriedItems.getItemIds()));

				// Prices, for the Stats tab's coin figures. Client thread only: getItemPrice
				// resolves the canonical item through getItemComposition, which asserts - and it
				// throws an AssertionError rather than an exception, so calling it from the
				// panel unwound the whole refresh rather than losing one number.
				itemPrices.record(itemManager);
				// Prices land after the panel's first draw, so the coin figures would otherwise
				// wait for the idle timer to come round.
				refresh();
			}
			// True consumes the invoke; false has RuneLite re-queue it for the next frame.
			return client.getGameState() == GameState.LOGGED_IN;
		});
		seedSelection.load();
		runTypes.load();
		runPresets.load();
		compostSelection.load();
		protectionSelection.load();
		patchLocations.load();
		bankLocations.load();
		harvestStats.load();
		diseaseStats.load();
		// Reads the harvest CSV, once — off the client thread, because it can be fifty
		// thousand rows and can trigger a whole-file trim, and this method runs from
		// client-thread event handlers at the exact moment of login. Everything derived from
		// it is held in memory afterwards, so no panel refresh ever touches the file. The
		// stats summary and a refresh follow it so the log describes the full picture and
		// the tab fills in when the read lands.
		harvestHistory.beginLoad();
		executor.execute(() ->
		{
			harvestHistory.load(
				com.dooglemaps.validate.HarvestFiles.forProfile(configManager));
			logStatsState();
			refresh();
		});
		interactionTracker.reset();
		// The awaiting-hand-in record is squared against Time Tracking before anything reads
		// it. This call was documented into existence by the self-healing pass and never
		// actually wired anywhere - the deadlock heal it carries ran zero times. Here, and on
		// every Time Tracking key change (onConfigChanged), which are the two moments its
		// evidence can newly contradict ours.
		contracts.reconcileAwaitingHandIn(guideTracker.contractGroundEvidence());
		// Said outright, once, because a contract that never appears has three possible causes —
		// Time Tracking switched off, nothing assigned, or one already handed in — and from the
		// sidebar all three look identical to the feature not working.
		contracts.logState();
		// The contract may have changed type while the plugin was off - a session with Time
		// Tracking alone, or another machine. Pointing the stored tick at whatever is assigned
		// now is what keeps "Farming Contract" a once-per-account decision; see retargetContract.
		retargetContractTick();
		refresh();

		loaded = true;
		log.info("Doogle Maps loaded - tracking {} of {} patches across {} regions",
			availability.getAllAvailablePatches().size(),
			FarmingWorldData.getAllPatches().size(),
			FarmingWorldData.getRegions().size());
	}

	/**
	 * Says what each of the Stats tab's four sources actually holds.
	 *
	 * <p>Same reasoning as {@code contracts.logState()} above: an empty Stats tab has several
	 * quite different causes and every one of them looks identical from the sidebar. It can mean
	 * no harvest has been recorded, or that the harvest log on disk is empty, or that no seed
	 * has been seen in the bank, or that no patch is switched on for the projection to plant
	 * into — and the tab can only say "nothing here yet" to all four.
	 *
	 * <p>One line, once per load, so the answer is in {@code client.log} rather than in a
	 * back-and-forth.
	 */
	private void logStatsState()
	{
		int seedTypes = 0;
		for (com.dooglemaps.data.Seed seed : com.dooglemaps.data.Seed.values())
		{
			if (seedStore.getOwned(seed) > 0)
			{
				seedTypes++;
			}
		}

		log.info("Doogle Maps stats: {} patches harvested ({} items), {} runs in the log, "
				+ "{} disease cycles; bank holds {} seed types, {} patches switched on, "
				+ "Farming {} ({} xp)",
			harvestStats.getTotalHarvests(), harvestStats.getTotalItems(),
			harvestHistory.getRuns().size(), diseaseStats.getTotalCycles(), seedTypes,
			availability.getAllAvailablePatches().size(), seedStore.getFarmingLevel(),
			seedStore.getFarmingXp());
	}

	private void refresh()
	{
		panel.refresh();
		if (readyInfoBox != null)
		{
			readyInfoBox.update();
		}
	}
}
