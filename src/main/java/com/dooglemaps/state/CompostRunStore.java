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
 * The choices a compost-bin run turns on: what is banked to fill the guild's big bin, which
 * crops may be fed to a bin straight out of the harvest, and whether the finished supercompost
 * gets the volcanic-ash upgrade.
 *
 * <h2>Two sources, and they are different questions</h2>
 *
 * {@link #getFills()} is a <b>shopping list</b> — what to bring from a bank, in order. It applies
 * to the one bin with a bank in its own region, which is the Farming Guild's. The other seven sit
 * beside allotments with no bank near them, and hauling fifteen un-noted items across the map to
 * each was the whole of a reported complaint.
 *
 * <p>{@link #getFodderCrops()} is a <b>permission set</b> — which crops the player is willing to
 * put in a bin from what they have just picked, standing next to it. No order, because there is
 * no queue: it is answered against whatever the patch gave you. Asked for from play in exactly
 * those terms: <i>"some bins I'm happy to put all of my watermelons in, but I don't want to waste
 * my snape grass"</i>.
 *
 * <p>One list, shared by both sizes of bin. "Which crops count as compost fodder" is a fact about
 * what the player values, and it does not change because the bin is bigger.
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
	private static final String FODDER_KEY = "compostBinFodder";
	private static final String FODDER_CROPS_KEY = "compostBinFodderCrops";

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
	 * The items the bins get filled with, best first, or empty while nothing is chosen.
	 *
	 * <h2>Several, in the order they were picked — like the seed list</h2>
	 *
	 * One fill is rarely enough: fifteen un-noted items per bin means a run over eight bins
	 * wants a hundred and twenty of something, and almost nobody has that of one crop. Picking
	 * pineapples then watermelons says "fill with pineapples, and when they run out use
	 * watermelons", exactly as picking two seeds does for patches. Insertion order is the
	 * queue, so re-picking sends an item to the back — the same rule, and the same digit on
	 * the icon, as {@code SeedSelectorPanel}.
	 *
	 * <p><b>The spill is per bin, not within one.</b> A bin filled with a mixture that is not
	 * entirely supercompostable makes ordinary compost, so running out of pineapples half way
	 * through a bin and topping it up with potatoes would quietly cost the tier. The guide
	 * therefore fills each bin from a single item; see {@code CompostBinPlan}.
	 *
	 * <p>Validated against the tables on the way out rather than trusted: a stored id could be
	 * stale across a data regeneration, and one neither table vouches for would have the run
	 * filling bins with something the game simply refuses.
	 */
	public java.util.List<Integer> getFills()
	{
		String stored = configManager.getRSProfileConfiguration(
			DoogleMapsConfig.GROUP, FILL_KEY, String.class);
		if (stored == null || stored.isEmpty())
		{
			return java.util.Collections.emptyList();
		}

		java.util.List<Integer> fills = new ArrayList<>();
		// Comma-separated so a single stored id — which is what the one-pick version wrote —
		// still parses as a list of one, and an existing profile keeps its choice.
		for (String part : stored.split(","))
		{
			try
			{
				int itemId = Integer.parseInt(part.trim());
				if (Compostables.isCompostable(itemId) && !fills.contains(itemId))
				{
					fills.add(itemId);
				}
			}
			catch (NumberFormatException e)
			{
				log.debug("Ignoring unreadable bin fill entry \"{}\"", part);
			}
		}
		return fills;
	}

	/** The first fill to reach for, or {@link #NO_FILL} when none is picked. */
	public int getFillItem()
	{
		java.util.List<Integer> fills = getFills();
		return fills.isEmpty() ? NO_FILL : fills.get(0);
	}

	/** Whether any fill has been chosen — the gate on routing to an empty bin. */
	public boolean hasFill()
	{
		return !getFills().isEmpty();
	}

	/**
	 * This item's place in the fill queue, 1-based, or 0 when it is unpicked or alone.
	 *
	 * <p>Zero for a lone pick because the digit exists to show an <i>order</i>, and one item
	 * has none — the same rule the seed grid's priority number follows.
	 */
	public int priorityOf(int itemId)
	{
		java.util.List<Integer> fills = getFills();
		if (fills.size() < 2)
		{
			return 0;
		}
		int position = fills.indexOf(itemId);
		return position < 0 ? 0 : position + 1;
	}

	/**
	 * Adds a fill to the back of the queue, or drops it when it is already picked.
	 *
	 * <p>Either tier is storable. The panel offers both lists — an account with no pineapples
	 * still has potatoes, and ordinary compost is what most of a farm run gets treated with —
	 * so the store's job is only to refuse an id the game would not accept in a bin at all.
	 * Which tier a fill produces is not stored: the bin's own varbit says what is in it, so
	 * everything downstream reads the result rather than predicting it.
	 */
	public void toggleFill(int itemId)
	{
		java.util.List<Integer> fills = new ArrayList<>(getFills());
		if (fills.contains(itemId))
		{
			fills.remove(Integer.valueOf(itemId));
		}
		else if (!Compostables.isCompostable(itemId))
		{
			log.warn("Refusing to store {} as a bin fill - a bin will not take it", itemId);
			return;
		}
		else
		{
			fills.add(itemId);
		}
		store(fills);
	}

	private void store(java.util.List<Integer> fills)
	{
		if (fills.isEmpty())
		{
			configManager.unsetRSProfileConfiguration(DoogleMapsConfig.GROUP, FILL_KEY);
			changed();
			return;
		}

		StringBuilder joined = new StringBuilder();
		for (int itemId : fills)
		{
			if (joined.length() > 0)
			{
				joined.append(',');
			}
			joined.append(itemId);
		}
		configManager.setRSProfileConfiguration(
			DoogleMapsConfig.GROUP, FILL_KEY, joined.toString());
		changed();
	}

	/**
	 * Whether every picked fill makes supercompost.
	 *
	 * <p>Every, not any, and that is the honest reading: the bins are filled one item at a
	 * time in queue order, so a queue with an ordinary item anywhere in it will eventually
	 * fill a bin with ordinary compost. For the panel, which says so under the picks, and for
	 * the ash box, which upgrades supercompost and nothing else — an ordinary fill with the
	 * ash ticked is a combination worth being plain about rather than silently doing nothing.
	 */
	public boolean fillMakesSupercompost()
	{
		java.util.List<Integer> fills = getFills();
		if (fills.isEmpty())
		{
			return false;
		}
		for (int fill : fills)
		{
			if (!Compostables.isSuperCompostable(fill))
			{
				return false;
			}
		}
		return true;
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

	/**
	 * Whether a bin may be fed from the harvest you are standing in front of.
	 *
	 * <p>Off by default, and that is deliberate rather than cautious: switching it on means
	 * crops the player picked stop coming home, and nothing should start doing that because an
	 * update shipped. The crop list narrows it further — this flag only says "the idea is on".
	 */
	public boolean isFodderEnabled()
	{
		return Boolean.TRUE.equals(configManager.getRSProfileConfiguration(
			DoogleMapsConfig.GROUP, FODDER_KEY, boolean.class));
	}

	public void setFodderEnabled(boolean enabled)
	{
		if (enabled)
		{
			configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, FODDER_KEY, true);
		}
		else
		{
			configManager.unsetRSProfileConfiguration(DoogleMapsConfig.GROUP, FODDER_KEY);
		}
		changed();
	}

	/**
	 * The crops a bin may be fed from the pack, or empty while none is allowed.
	 *
	 * <p>Insertion-ordered so the panel's grid does not reshuffle as picks are made, but the
	 * order carries no meaning — unlike {@link #getFills()}, nothing reads position. What picks
	 * a crop when several qualify is the bin's own tier arithmetic; see {@code CompostBinPlan}.
	 *
	 * <p>Validated against {@link Compostables#allotmentFodder()} on the way out, which is
	 * narrower than the fills' check and deliberately so: a bin is only ever offered fodder at
	 * the stop it stands in, and what grows at that stop is the allotments. It also self-heals a
	 * selection made before the list narrowed — a stored pineapple simply stops being returned.
	 */
	public java.util.Set<Integer> getFodderCrops()
	{
		String stored = configManager.getRSProfileConfiguration(
			DoogleMapsConfig.GROUP, FODDER_CROPS_KEY, String.class);
		if (stored == null || stored.isEmpty())
		{
			return java.util.Collections.emptySet();
		}

		java.util.Set<Integer> crops = new java.util.LinkedHashSet<>();
		for (String part : stored.split(","))
		{
			try
			{
				int itemId = Integer.parseInt(part.trim());
				if (Compostables.isAllotmentFodder(itemId))
				{
					crops.add(itemId);
				}
			}
			catch (NumberFormatException e)
			{
				log.debug("Ignoring unreadable fodder entry \"{}\"", part);
			}
		}
		return crops;
	}

	/**
	 * Whether this crop may go in a bin out of the pack.
	 *
	 * <p>Both halves, so a caller cannot forget the toggle and act on a stale list: a player who
	 * switched fodder off keeps their picks, and nothing acts on them until it is on again.
	 */
	public boolean allowsFodder(int itemId)
	{
		return isFodderEnabled() && getFodderCrops().contains(itemId);
	}

	/** Adds or removes a crop from the allowed set. */
	public void toggleFodderCrop(int itemId)
	{
		java.util.Set<Integer> crops = new java.util.LinkedHashSet<>(getFodderCrops());
		if (!crops.remove(itemId))
		{
			if (!Compostables.isAllotmentFodder(itemId))
			{
				log.warn("Refusing to store {} as bin fodder - it is not an allotment crop, and "
					+ "a bin is only ever offered what grows at the stop it stands in", itemId);
				return;
			}
			crops.add(itemId);
		}

		if (crops.isEmpty())
		{
			configManager.unsetRSProfileConfiguration(DoogleMapsConfig.GROUP, FODDER_CROPS_KEY);
			changed();
			return;
		}

		StringBuilder joined = new StringBuilder();
		for (int crop : crops)
		{
			if (joined.length() > 0)
			{
				joined.append(',');
			}
			joined.append(crop);
		}
		configManager.setRSProfileConfiguration(
			DoogleMapsConfig.GROUP, FODDER_CROPS_KEY, joined.toString());
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
