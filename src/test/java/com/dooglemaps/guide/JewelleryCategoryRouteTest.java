package com.dooglemaps.guide;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Which jewellery box button answers for which want — the route's row first, the stop's name
 * as the fallback it always was.
 *
 * <h2>The glory-instead-of-dueling report</h2>
 *
 * An Al Kharid cactus trip routes through the ring of dueling: Emir's Arena lands beside the
 * patch. The category table had no DUELING entry at all — its comment believed the ring's only
 * farming-adjacent destination was the Fortis Colosseum — so with nothing able to claim the
 * route's "1: Emir's Arena" hop, the destination fallback lit the <b>glory's</b> button off the
 * stop's bare name, and the highlight pointed at the palace end of town. Reported from play,
 * more than once.
 */
public class JewelleryCategoryRouteTest
{
	/** The route's own row name claims the dueling button, and only that button. */
	@Test
	public void theRoutesEmirsArenaRowBelongsToDueling()
	{
		assertTrue(HouseTeleports.JewelleryCategory.DUELING.reaches("1: Emir's Arena"));
		assertFalse("the glory has no claim on the route's row",
			HouseTeleports.JewelleryCategory.GLORY.reaches("1: Emir's Arena"));
	}

	/** The stop-name fallback keeps its old answer: bare "Al Kharid" is the glory's. */
	@Test
	public void theBareStopNameStillBelongsToGlory()
	{
		assertTrue(HouseTeleports.JewelleryCategory.GLORY.reaches("Al Kharid"));
		assertFalse("Emir's Arena is not 'Al Kharid' - the dueling button must not light "
				+ "for a trip that genuinely wants the palace",
			HouseTeleports.JewelleryCategory.DUELING.reaches("Al Kharid"));
	}
}
