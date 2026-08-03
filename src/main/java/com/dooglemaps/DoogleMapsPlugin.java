package com.dooglemaps;

import com.dooglemaps.capture.CompostCapture;
import com.dooglemaps.capture.PatchInteractionTracker;
import com.dooglemaps.capture.ProtectionCapture;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.state.AvailabilityProfile;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.ui.DoogleMapsPanel;
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
	private EventBus eventBus;

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
	private DoogleMapsPanel panel;

	private NavigationButton navigationButton;
	private ReadyInfoBox readyInfoBox;

	private final Runnable onStateChanged = this::refresh;
	private Instant lastIdleRefresh = Instant.EPOCH;

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

		stateStore.addChangeListener(onStateChanged);
		availability.addChangeListener(onStateChanged);

		navigationButton = NavigationButton.builder()
			.tooltip("Doogle Maps")
			.icon(PluginIcon.create())
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navigationButton);

		readyInfoBox = new ReadyInfoBox(PluginIcon.create(), this, panel, config);
		infoBoxManager.addInfoBox(readyInfoBox);

		load();

		log.info("Doogle Maps started - tracking {} of {} patches across {} regions",
			availability.getAllAvailablePatches().size(),
			FarmingWorldData.getAllPatches().size(),
			FarmingWorldData.getRegions().size());
	}

	@Override
	protected void shutDown()
	{
		log.info("Doogle Maps stopped");

		eventBus.unregister(interactionTracker);
		eventBus.unregister(compostCapture);
		eventBus.unregister(protectionCapture);

		stateStore.removeChangeListener(onStateChanged);
		availability.removeChangeListener(onStateChanged);

		infoBoxManager.removeInfoBox(readyInfoBox);
		readyInfoBox = null;

		clientToolbar.removeNavigation(navigationButton);
		navigationButton = null;

		interactionTracker.reset();
		compostCapture.reset();
		protectionCapture.reset();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			// Patches keep growing while you are away, so the overview is already stale by
			// the time you log in; reloading projects every one of them forward.
			load();
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
		if (DoogleMapsConfig.GROUP.equals(event.getGroup()))
		{
			refresh();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		Instant now = Instant.now();
		if (Duration.between(lastIdleRefresh, now).compareTo(IDLE_REFRESH_INTERVAL) >= 0)
		{
			lastIdleRefresh = now;
			refresh();
		}
	}

	private void load()
	{
		stateStore.load();
		availability.load();
		interactionTracker.reset();
		refresh();
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
