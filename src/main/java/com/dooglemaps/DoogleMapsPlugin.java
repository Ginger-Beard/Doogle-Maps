package com.dooglemaps;

import com.dooglemaps.capture.CompostCapture;
import com.dooglemaps.capture.PatchInteractionTracker;
import com.dooglemaps.capture.BankCapture;
import com.dooglemaps.capture.PatchLocationCapture;
import com.dooglemaps.capture.ProtectionCapture;
import com.dooglemaps.capture.SeedCapture;
import com.dooglemaps.bank.BankContents;
import com.dooglemaps.bank.BankFilter;
import com.dooglemaps.bank.BankHighlightOverlay;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.guide.CarriedItems;
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
	private ProtectedPatches protectedPatches;

	@Inject
	private com.dooglemaps.data.ItemNames itemNames;

	@Inject
	private net.runelite.client.game.ItemManager itemManager;

	@Inject
	private ProtectionSelectionStore protectionSelection;

	@Inject
	private BankFilter bankFilter;

	@Inject
	private com.dooglemaps.guide.GuideTracker guideTracker;

	@Inject
	private BankContents bankContents;

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

	@Inject
	private AvailabilityProfile availability;

	@Inject
	private PatchInteractionTracker interactionTracker;

	@Inject
	private CompostCapture compostCapture;

	@Inject
	private ProtectionCapture protectionCapture;

	@Inject
	private SeedCapture seedCapture;

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
	private SeedSelectionStore seedSelection;

	@Inject
	private RunTypeStore runTypes;

	@Inject
	private CompostSelectionStore compostSelection;

	@Inject
	private HarvestLog harvestLog;

	@Inject
	private HarvestStatsStore harvestStats;

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
		eventBus.register(seedCapture);
		eventBus.register(locationCapture);
		eventBus.register(bankCapture);
		eventBus.register(router);
		eventBus.register(harvestLog);
		eventBus.register(geomancyProbe);
		eventBus.register(carriedItems);
		eventBus.register(playerLocation);
		eventBus.register(guideTracker);
		eventBus.register(bankContents);
		eventBus.register(leprechaunStore);
		eventBus.register(playerHouse);
		eventBus.register(bankFilter);
		bankFilter.startUp();

		protectedPatches.addChangeListener(onProtectionChanged);
		stateStore.addChangeListener(onStateChanged);
		availability.addChangeListener(onStateChanged);
		seedStore.addChangeListener(onStateChanged);
		seedSelection.addChangeListener(onStateChanged);

		navigationButton = NavigationButton.builder()
			.tooltip("Doogle Maps")
			.icon(PluginIcon.create())
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navigationButton);

		readyInfoBox = new ReadyInfoBox(PluginIcon.create(), this, panel, config);
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
		eventBus.unregister(seedCapture);
		eventBus.unregister(locationCapture);
		eventBus.unregister(bankCapture);
		eventBus.unregister(router);
		eventBus.unregister(harvestLog);
		eventBus.unregister(geomancyProbe);
		eventBus.unregister(carriedItems);
		eventBus.unregister(playerLocation);
		eventBus.unregister(guideTracker);
		eventBus.unregister(bankContents);
		eventBus.unregister(leprechaunStore);
		eventBus.unregister(playerHouse);
		eventBus.unregister(bankFilter);
		bankFilter.shutDown();

		protectedPatches.removeChangeListener(onProtectionChanged);
		stateStore.removeChangeListener(onStateChanged);
		availability.removeChangeListener(onStateChanged);
		seedStore.removeChangeListener(onStateChanged);
		seedSelection.removeChangeListener(onStateChanged);

		overlayManager.remove(guideOverlay);
		overlayManager.remove(guideInventoryOverlay);
		overlayManager.remove(guideStepOverlay);
		overlayManager.remove(bankHighlightOverlay);

		infoBoxManager.removeInfoBox(readyInfoBox);
		readyInfoBox = null;

		clientToolbar.removeNavigation(navigationButton);
		navigationButton = null;
		loaded = false;

		interactionTracker.reset();
		compostCapture.reset();
		harvestLog.reset();
		protectionCapture.reset();
		carriedItems.reset();
		playerLocation.reset();
		guideTracker.reset();
		bankContents.reset();
		leprechaunStore.reset();
		playerHouse.reset();
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
		}
	}

	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		// Config is per RuneScape profile, so switching accounts swaps the whole cache.
		load();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
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
		// client reads, so they go through the client thread.
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				seedStore.recordFarmingLevel();
				seedStore.recordWoodcuttingLevel();
				seedStore.relearnFromClient();
			}
		});
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

		// Which herb patches cannot be diseased, re-read rather than sampled once at login.
		// The load fires the instant LOGGED_IN does, and the quest and diary varbits are not
		// all synced by then — a sample taken a second early reads nothing and used to latch,
		// which is exactly how the protected herb tab went missing for a whole session. This
		// is on the client thread already, and the read leaves early when nothing has changed.
		protectedPatches.refresh(client);

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

		stateStore.load();
		availability.load();
		seedStore.load();
		// The Farming level is only otherwise learned from a Farming XP drop, which may not
		// come for hours. Without it every yield estimate stays hidden, so it is read
		// outright whenever we load.
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				seedStore.recordFarmingLevel();
				seedStore.recordWoodcuttingLevel();
				bonusStore.recordDiaries();

				// Protection payment names, read here because getItemComposition is a client
				// thread call and the sidebar needs them on Swing. A fixed set, read once.
				java.util.List<Integer> paymentItems = new java.util.ArrayList<>();
				for (com.dooglemaps.data.ProtectionPayment payment
					: com.dooglemaps.data.ProtectionPayment.values())
				{
					paymentItems.add(payment.getItemID());
				}
				itemNames.record(itemManager, paymentItems);
			}
		});
		seedSelection.load();
		runTypes.load();
		compostSelection.load();
		protectionSelection.load();
		patchLocations.load();
		bankLocations.load();
		harvestStats.load();
		interactionTracker.reset();
		refresh();

		loaded = true;
		log.info("Doogle Maps loaded - tracking {} of {} patches across {} regions",
			availability.getAllAvailablePatches().size(),
			FarmingWorldData.getAllPatches().size(),
			FarmingWorldData.getRegions().size());
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
