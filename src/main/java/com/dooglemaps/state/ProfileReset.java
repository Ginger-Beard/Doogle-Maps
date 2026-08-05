package com.dooglemaps.state;

import com.dooglemaps.route.BankLocationStore;
import com.dooglemaps.route.PatchLocationStore;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Throws away everything the plugin has learned about this account.
 *
 * <p>Everything here is <i>observed</i> state — patch contents, seed counts, learned bank and
 * patch positions, what the player was carrying. All of it is rebuilt simply by playing:
 * walk past a patch and it comes back. So resetting costs nothing permanent, which is what
 * makes it a reasonable thing to offer at all.
 *
 * <p>What is left alone is the whole point of this class existing rather than the config
 * group simply being wiped. The line is between what the plugin <i>worked out</i> and what
 * the player <i>told</i> it:
 *
 * <ul>
 *   <li><b>Settings</b> — every {@code @ConfigItem} lives in the same config group as the
 *       cached data. Wiping the group would reset the player's preferences too, so keys are
 *       unset one store at a time instead.</li>
 *   <li><b>Harvest statistics</b> — a lifetime record of what patches actually gave you.
 *       That is earned history, not a cache; nothing rebuilds it, and losing it to a button
 *       meant for clearing stale patch state would be the one irreversible thing here.</li>
 *   <li><b>Which patches are shown, and the seeds picked for the run</b> — both are choices
 *       the player made by clicking, no different in kind from a setting. They also happen
 *       to be tedious to redo across a hundred-odd patches, and trivial to change back if
 *       someone did want them gone.</li>
 * </ul>
 */
@Slf4j
@Singleton
public class ProfileReset
{
	private final PatchStateStore patches;
	private final SeedInventoryStore seeds;
	private final FarmingBonusStore bonuses;
	private final PatchLocationStore patchLocations;
	private final BankLocationStore bankLocations;

	@Inject
	private ProfileReset(PatchStateStore patches, SeedInventoryStore seeds,
		FarmingBonusStore bonuses, PatchLocationStore patchLocations,
		BankLocationStore bankLocations)
	{
		this.patches = patches;
		this.seeds = seeds;
		this.bonuses = bonuses;
		this.patchLocations = patchLocations;
		this.bankLocations = bankLocations;
	}

	/**
	 * Clears the cache for the current RuneScape profile.
	 *
	 * <p>Note what is <i>not</i> in this list: the harvest statistics, the shown/hidden patch
	 * toggles, and the run's seed selection. Each absence is intentional and covered by a
	 * test, so that wiring another store in here later cannot quietly sweep them up too.
	 *
	 * <p>{@code ContractState} is deliberately absent for a different reason, and it is the one
	 * worth stating because it looks like cached data and is not. Everything cleared here comes
	 * back by playing; a contract recorded as grown-and-unclaimed does not. Nothing re-derives it
	 * once the patch has been picked, so wiping it would silently cost the reward. It clears itself
	 * when the next contract is assigned, which is the only moment it is genuinely stale.
	 */
	public void reset()
	{
		patches.clear();
		seeds.clear();
		bonuses.clear();
		patchLocations.clear();
		bankLocations.clear();

		log.info("Doogle Maps profile reset - patches, seeds and learned locations cleared; "
			+ "settings, harvest stats, patch toggles and seed selection kept");
	}
}
