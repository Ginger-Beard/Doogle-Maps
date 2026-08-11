package com.dooglemaps.guide;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Every wording Shortest Path can emit for a fairy ring hop, against the furniture matcher.
 *
 * <h2>Where the list comes from</h2>
 *
 * SP's own {@code fairy_rings.tsv} (the ground truth for hop wording), read row by row and
 * composed exactly as {@code ShortestPathIntegration.readTransports} does: a destination
 * row carries only display info — the bare code — while an origin row carries only
 * {@code objectInfo} ("Configure Fairy ring &lt;objectId&gt;"), which arrives id-stripped and
 * prefixed onto the code as {@code "Configure Fairy ring - C K Q"}. Which shape a given
 * plan produces depends on which edge the router used, which is why play kept seeing
 * "A L S works, C K Q doesn't" style inconsistency — the matcher has to take every shape.
 *
 * <p>The POH garden ring's own row (origin 2027,5700 — inside the reserved POH area —
 * objectInfo "Configure Fairy ring 29228") composes identically to the overworld rings, so
 * it is covered by the same shapes; its outline additionally never depends on wording at
 * all now, since {@code PlayerHouse} names it by that same object id.
 */
public class SpFairyWordingTest
{
	/**
	 * The composed hop wordings, one per distinct shape in the TSV.
	 *
	 * <p>Codes vary; shapes do not. A new code is the same shape as "A J P", so the matrix
	 * stays complete without listing all forty rings.
	 */
	private static final String[] FAIRY_HOPS = {
		// Destination rows: display info alone, objectInfo empty.
		"A J P",
		"A L S",
		"C K Q",
		"ZANARIS",
		"A I R - D L R - D J Q - A J S",
		// Origin rows: objectInfo, id stripped, prefixed by readTransports.
		"Configure Fairy ring - A J P",
		"Configure Fairy ring - C K Q",
		"Configure Fairy ring - ZANARIS",
		"Configure Fairy ring - A I R - D L R - D J Q - A J S",
	};

	@Test
	public void everyFairyWordingClaimsAPlainRing()
	{
		for (String hop : FAIRY_HOPS)
		{
			assertTrue("\"" + hop + "\" should name a plain Fairy ring",
				HouseTeleports.furnitureNamedByHop("Fairy ring", hop));
		}
	}

	@Test
	public void everyFairyWordingClaimsTheCombinedBuild()
	{
		for (String hop : FAIRY_HOPS)
		{
			assertTrue("\"" + hop + "\" should name the combined build's ring half",
				HouseTeleports.furnitureNamedByHop("Spirit tree & fairy ring", hop));
		}
	}

	/**
	 * The one ring the TSV enters through a portal ("Enter Portal 43119", varbit-gated)
	 * composes to a shape that names neither a ring nor a code — and must not claim the
	 * garden ring: that hop's click is a portal in the overworld, and whether the house
	 * serves the route is the origins' question, not this wording's.
	 */
	@Test
	public void thePortalEnteredRingDoesNotClaimTheGardenRing()
	{
		assertFalse(HouseTeleports.furnitureNamedByHop("Fairy ring", "Enter Portal - D I R"));
		assertFalse("nor through the combined name",
			HouseTeleports.furnitureNamedByHop("Spirit tree & fairy ring", "Enter Portal - D I R"));
	}

	/** No other furniture may claim any fairy shape - the codes must stay the ring's alone. */
	@Test
	public void noOtherFurnitureClaimsAFairyWording()
	{
		for (String hop : FAIRY_HOPS)
		{
			assertFalse("\"" + hop + "\" is nobody's but the ring's",
				HouseTeleports.furnitureNamedByHop("Portal Nexus", hop));
			assertFalse(HouseTeleports.furnitureNamedByHop("Ornate Jewellery Box", hop));
		}
	}
}
