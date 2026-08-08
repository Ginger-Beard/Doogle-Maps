package com.dooglemaps.state;

import com.dooglemaps.DoogleMapsConfig;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Whether this account can plant seeds without a seed dibber.
 *
 * <p>Barbarian Farming — Otto Godblessed's section of the Barbarian Training miniquest — removes
 * the dibber requirement outright. Someone who has it and is told to fetch one is being sent for
 * a tool they have not needed in years, which is the fastest way to make a player stop reading
 * the guidance.
 *
 * <h2>Why this is observed rather than read</h2>
 *
 * The obvious answer is a varbit, and the obvious varbit does not exist: {@code VarbitID} has no
 * Barbarian Training entry, and {@code Quest.BARBARIAN_TRAINING} answers about the <b>whole</b>
 * miniquest. That is not the same question. The sections are independent — the farming one can
 * be done on its own, and players who did any part before the 2024 rework had it granted
 * retroactively — so "miniquest finished" would miss exactly the people this is for.
 *
 * <p>So it is learned from play instead, the way patch positions and bank locations already are:
 * <b>a seed going into the ground while no dibber is carried can only mean the unlock.</b> There
 * is no other way for that to happen, so the signal cannot produce a false positive, and once
 * seen it is true forever and stored per profile.
 *
 * <p>The cost of that design is honest and small: until the first planting is watched, someone
 * with the unlock is told once to fetch a dibber they do not need. One wrong instruction, once
 * ever, that corrects itself the moment they ignore it and plant anyway — against a varbit guess
 * that could be wrong in the other direction, where the player is never told about a tool they
 * genuinely cannot plant without.
 */
@Slf4j
@Singleton
public class BarbarianFarming
{
	private static final String KEY = "barbarianFarming";

	private final ConfigManager configManager;
	private final DoogleMapsConfig config;

	@Inject
	BarbarianFarming(ConfigManager configManager, DoogleMapsConfig config)
	{
		this.configManager = configManager;
		this.config = config;
	}

	/**
	 * Whether seeds can go in without a dibber — because it was watched happening, or said so.
	 *
	 * <p>The setting exists because waiting to be observed is fine in principle and irritating in
	 * practice: until the first planting lands, someone who has had this unlock for years is
	 * still being told to fetch a dibber, and there is no way for them to say otherwise. It only
	 * ever forces the answer <i>on</i>; the observation is what turns it on by itself, and neither
	 * can turn it off, because the unlock is permanent.
	 */
	public boolean isUnlocked()
	{
		return config.barbarianFarmingOverride()
			|| Boolean.TRUE.equals(
				configManager.getRSProfileConfiguration(DoogleMapsConfig.GROUP, KEY, boolean.class));
	}

	/**
	 * Records the unlock, having just watched a seed go in with no dibber carried.
	 *
	 * <p>Written once and never unwritten — the unlock is permanent, so a later planting that
	 * happens to be done with a dibber in the pack proves nothing and must not clear it.
	 */
	public void observePlantedWithoutDibber()
	{
		if (isUnlocked())
		{
			return;
		}

		configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, KEY, true);
		log.info("Seed planted with no dibber carried - this account has Barbarian Farming, so "
			+ "a dibber will not be asked for again.");
	}
}
