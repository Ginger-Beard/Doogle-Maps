package com.dooglemaps.timer;

import java.awt.Color;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * How much a cached timer can be trusted.
 *
 * <p>A cached "ready at" time really means "ready at, assuming it does not get diseased".
 * Two mechanics bound that risk: a crop cannot become diseased during its first growth
 * stage, and a fully grown crop cannot become diseased. Only the intermediate cycles are
 * at risk, and we cannot see a disease that happened after we last looked — so say so
 * rather than quietly presenting a guess as fact.
 */
@Getter
@RequiredArgsConstructor
public enum Confidence
{
	/**
	 * The crop is immune, protected, or in a disease-free patch, so the countdown is
	 * effectively guaranteed.
	 */
	CERTAIN(new Color(0x4C, 0xAF, 0x50)),

	/**
	 * Unprotected and past its first growth stage, so it may have diseased since we last
	 * saw it. The time shown is the best case.
	 */
	ESTIMATE(new Color(0xC8, 0xA2, 0x2D)),

	/** Diseased or dead: no timer, this one wants action. */
	NEEDS_ACTION(new Color(0xC4, 0x3B, 0x3B)),

	/** Nothing planted. */
	EMPTY(new Color(0x60, 0x60, 0x60));

	private final Color color;
}
