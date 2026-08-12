package com.dooglemaps.state;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.Compostables;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * The two choices a compost-bin run turns on: what fills the bins, and whether the finished
 * supercompost gets the volcanic-ash upgrade.
 *
 * <p>Per RuneScape profile, like the seed selection — which item you can spare fifteen of is a
 * fact about an account's bank, not about the player. Read live rather than loaded, the way
 * {@link BarbarianFarming} reads its flag: two scalar keys are not worth a load step and a
 * cache that could go stale across profile switches.
 *
 * <p>Deliberately <b>not</b> cleared by {@link ProfileReset}: both values are choices the
 * player made by clicking, the same kind of thing as the seed selection and the run ticks,
 * which the reset's own doctrine keeps. Nothing observed lives here.
 *
 * <h2>Why the ash is a checkbox and not a tier choice</h2>
 *
 * Because in the bin there is only one upgrade and one moment for it. A bin filled with
 * supercompostables yields supercompost; 25 ash (50 in the big bin) turns the <b>whole bin</b>
 * to ultracompost, and that price only exists before the first bucket comes out — filled
 * buckets upgrade at 2 ash each, which for a full bin is more ash for the same compost. So the
 * decision is "upgrade or not", asked once, and the guide's step order enforces the moment.
 */
@Slf4j
@Singleton
public class CompostRunStore
{
	private static final String FILL_KEY = "compostBinFill";
	private static final String ASH_KEY = "compostBinAsh";

	/** The stored answer for "no fill chosen", also returned while nothing is stored. */
	public static final int NO_FILL = -1;

	private final ConfigManager configManager;

	/** Swing listeners, like every other store the sidebar draws from. */
	private final List<Runnable> listeners = new ArrayList<>();

	@Inject
	CompostRunStore(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	/**
	 * The item the bins get filled with, or {@link #NO_FILL} while none is chosen.
	 *
	 * <p>Validated against the table on the way out rather than trusted: the stored id could
	 * be stale across a data regeneration, and an id the table no longer vouches for would
	 * have the run filling bins with something that makes ordinary compost — or, filled with
	 * tomatoes, with nothing at all.
	 */
	public int getFillItem()
	{
		Integer stored = configManager.getRSProfileConfiguration(
			DoogleMapsConfig.GROUP, FILL_KEY, int.class);
		if (stored == null || !Compostables.isSuperCompostable(stored))
		{
			return NO_FILL;
		}
		return stored;
	}

	/** Whether a fill has been chosen at all — the gate on routing to an empty bin. */
	public boolean hasFill()
	{
		return getFillItem() != NO_FILL;
	}

	/**
	 * Picks the fill, or clears it when the current pick is clicked again.
	 *
	 * <p>Only items the table vouches for are storable, for the same reason the read side
	 * validates: everything downstream — the loadout's count, the guide's fill step — assumes
	 * the chosen item makes supercompost.
	 */
	public void setFillItem(int itemId)
	{
		if (itemId == getFillItem())
		{
			configManager.unsetRSProfileConfiguration(DoogleMapsConfig.GROUP, FILL_KEY);
			changed();
			return;
		}
		if (!Compostables.isSuperCompostable(itemId))
		{
			log.warn("Refusing to store {} as the bin fill - it is not supercompostable", itemId);
			return;
		}
		configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, FILL_KEY, itemId);
		changed();
	}

	/** Whether a ready bin of supercompost gets the 25-ash (50 in the big bin) upgrade. */
	public boolean isAshing()
	{
		return Boolean.TRUE.equals(configManager.getRSProfileConfiguration(
			DoogleMapsConfig.GROUP, ASH_KEY, boolean.class));
	}

	public void setAshing(boolean ashing)
	{
		if (ashing)
		{
			configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, ASH_KEY, true);
		}
		else
		{
			configManager.unsetRSProfileConfiguration(DoogleMapsConfig.GROUP, ASH_KEY);
		}
		changed();
	}

	public void addChangeListener(Runnable listener)
	{
		listeners.add(listener);
	}

	public void removeChangeListener(Runnable listener)
	{
		listeners.remove(listener);
	}

	private void changed()
	{
		for (Runnable listener : new ArrayList<>(listeners))
		{
			listener.run();
		}
	}
}
