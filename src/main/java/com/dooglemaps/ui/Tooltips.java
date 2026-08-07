package com.dooglemaps.ui;

/**
 * Tooltip text that wraps, instead of stretching off the side of the client.
 *
 * <h2>Why anything is needed</h2>
 *
 * Swing lays an HTML tooltip out at its <b>natural</b> width. There is no layout manager involved
 * and nothing to ask for a maximum, so a two-sentence tooltip becomes one line a thousand pixels
 * wide and runs past the edge of the window. A {@code width} on the body is the only thing that
 * makes it wrap.
 *
 * <p>Plain text has exactly the same problem and is the easier one to write by accident, so
 * {@link #text} exists to catch that case rather than leaving it as the one shape that still
 * misbehaves.
 *
 * <h2>Why it is shared rather than fixed per tooltip</h2>
 *
 * There were nine hand-rolled {@code "<html>…</html>"} builders across the panel and not one of
 * them set a width — because the omission is invisible in the source. Each looked complete. The
 * only way to see the bug was to hover it, and the only way to stop it coming back is for there to
 * be one place that decides.
 *
 * <p>Manual {@code <br>} is not a substitute: it fixes the sentence in front of you and breaks
 * again the moment someone lengthens it, which is how these grew to a thousand characters in the
 * first place.
 */
final class Tooltips
{
	/**
	 * Wide enough to read a sentence on, narrow enough to sit beside a 225px sidebar without
	 * covering the thing being described.
	 */
	private static final int WIDTH = 260;

	/**
	 * The width for HTML that has to sit <b>inside</b> the sidebar rather than float beside it.
	 *
	 * <p>A tooltip is a free-floating window, so it may be wider than the panel that raised it —
	 * that is the point of {@link #WIDTH}. A label in the panel has no such freedom: it is clipped
	 * to whatever the sidebar gives it, and an HTML body wider than that does not re-wrap, it just
	 * loses its right-hand edge. Reusing the tooltip width here cut the last character off every
	 * wrapped line.
	 *
	 * <p>Matches {@code WrappedText.WRAP_WIDTH}: 225px of sidebar, less the borders a row of text
	 * normally carries.
	 */
	private static final int SIDEBAR_WIDTH = 195;

	private Tooltips()
	{
	}

	/**
	 * Wraps plain text for a label that lives in the sidebar, rather than for a tooltip.
	 *
	 * <p>Separate from {@link #text} because the two have genuinely different budgets, and the
	 * difference is invisible at the call site — which is how the wrong one got used.
	 */
	static String inPanel(String plain)
	{
		return "<html><body width='" + SIDEBAR_WIDTH + "'>" + escape(plain) + "</body></html>";
	}

	/**
	 * Wraps an HTML fragment.
	 *
	 * @param body markup <b>without</b> the surrounding html and body tags; this adds them
	 */
	static String html(String body)
	{
		return "<html><body width='" + WIDTH + "'>" + body + "</body></html>";
	}

	/**
	 * Wraps plain text, escaping anything in it that would otherwise be read as markup.
	 *
	 * <p>Item and crop names reach tooltips from the game, so this cannot assume its input is
	 * inert — a name containing an angle bracket would silently swallow the rest of the line.
	 */
	static String text(String plain)
	{
		return html(escape(plain));
	}

	private static String escape(String plain)
	{
		return plain
			.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;");
	}
}
