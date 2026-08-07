package com.dooglemaps.guide;

import com.dooglemaps.state.SeedSource;
import java.util.Collections;
import java.util.EnumSet;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Which supply point the scene lights up while the run is collecting.
 *
 * <h2>The rule this replaces</h2>
 *
 * Every bank booth, chest and the seed vault were outlined together, on the reasoning that one trip
 * can want both and they stand a few steps apart in the Farming Guild. On screen that is two places
 * lit at once with no way to tell which one the run means — and the planner is not guessing between
 * them either: {@code getSupplyTargets} routes to the vault or to the banks, never to both.
 *
 * <p>Reported from play as starting a run and finding both marked.
 */
public class SupplyHighlightTest
{
	@Test
	public void seedsInTheVaultMarkTheVaultAndNotTheBanks()
	{
		assertTrue("the vault is where the run is going",
			GuideOverlay.marks(EnumSet.of(SeedSource.SEED_VAULT), true));
		assertFalse("so a bank booth beside it is noise",
			GuideOverlay.marks(EnumSet.of(SeedSource.SEED_VAULT), false));
	}

	@Test
	public void seedsInTheBankMarkTheBanksAndNotTheVault()
	{
		assertTrue(GuideOverlay.marks(EnumSet.of(SeedSource.BANK), false));
		assertFalse(GuideOverlay.marks(EnumSet.of(SeedSource.BANK), true));
	}

	/**
	 * Not knowing what the trip needs means a bank, never the vault.
	 *
	 * <p>The run opens at a bank when it cannot tell what it wants — having no seed picked for
	 * anything looks exactly like this from the planner's side. The vault holds seeds and nothing
	 * else, so uncertainty is never a reason to point at it.
	 */
	@Test
	public void notKnowingWhatIsNeededMeansABank()
	{
		assertTrue(GuideOverlay.marks(Collections.emptySet(), false));
		assertFalse(GuideOverlay.marks(Collections.emptySet(), true));
	}

	/**
	 * Wanting both marks both.
	 *
	 * <h2>This test asserted the opposite, and the opposite was a bug</h2>
	 *
	 * It read "two lit places is the thing being fixed, so both cannot mean both", and marked only
	 * the vault. That followed from a promise the planner had stopped keeping:
	 * {@code supplyTargetsFor} was changed to hand Shortest Path the vault <i>and</i> every usable
	 * bank, so the router picked the cheapest — the Farming Guild's bank chest — and drew the line
	 * to a booth this rule had just darkened.
	 *
	 * <p>A highlight that disagrees with the line on the map is worse than two highlights, which is
	 * the reasoning the exclusive rule was built on; it was simply applied to the wrong one of the
	 * two. The leg does not end until both containers are empty, so both are genuinely wanted, and
	 * {@code LoadoutSummary} already lists them as two lines.
	 */
	@Test
	public void wantingBothMarksBoth()
	{
		assertTrue("the vault is one of the two errands",
			GuideOverlay.marks(EnumSet.of(SeedSource.BANK, SeedSource.SEED_VAULT), true));
		assertTrue("and the bank is the other, and may well be where the line points",
			GuideOverlay.marks(EnumSet.of(SeedSource.BANK, SeedSource.SEED_VAULT), false));
	}
}
