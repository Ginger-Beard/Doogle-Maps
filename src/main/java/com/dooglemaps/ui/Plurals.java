package com.dooglemaps.ui;

/**
 * "1 patch" and "2 patches", in one place.
 *
 * <p>Written inline seven times before this existed, six of them the same {@code patch}/
 * {@code patches} pair — and the seventh got it wrong. {@code GuideStepOverlay} rendered
 * <i>"+ 1 more hops"</i> whenever exactly one transport was over the limit, because that one line
 * was the only count in the codebase without a guard beside it.
 *
 * <p>That is the argument for a helper rather than for care: the mistake is invisible at the call
 * site, since the expression reads fine until the number happens to be one, and the number is only
 * one in the case nobody screenshots.
 */
final class Plurals
{
	private Plurals()
	{
	}

	/**
	 * The count and its noun, agreeing.
	 *
	 * <p>Both forms spelled out rather than a suffix, because English does not only add an "s" —
	 * this file would otherwise be the reason something says "1 patches" or "2 patchs" later.
	 */
	static String of(long count, String singular, String plural)
	{
		return count + " " + (count == 1 ? singular : plural);
	}

	/** The noun alone, for callers that have already written the number. */
	static String pick(long count, String singular, String plural)
	{
		return count == 1 ? singular : plural;
	}
}
