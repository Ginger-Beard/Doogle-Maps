package com.dooglemaps.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Pins {@link DataTable#shortNumber} band by band, because the method exists for width:
 * every band keeps a value at four characters or fewer in the 225px sidebar, and the
 * hand-offs between bands are exactly where rounding can quietly hand back a wider string.
 */
public class DataTableTest
{
	/** Below a thousand there is nothing to abbreviate. */
	@Test
	public void smallValuesStayRaw()
	{
		assertEquals("0", DataTable.shortNumber(0));
		assertEquals("999", DataTable.shortNumber(999));
	}

	/** One decimal in the low thousands, but a round figure drops its ".0" - "1k", not "1.0k". */
	@Test
	public void thousandsKeepOneDecimal()
	{
		assertEquals("1k", DataTable.shortNumber(1000));
		assertEquals("1.5k", DataTable.shortNumber(1500));
	}

	/** From six figures the decimal goes too; "999k" is as wide as the band ever gets. */
	@Test
	public void sixFiguresRoundToWholeThousands()
	{
		assertEquals("100k", DataTable.shortNumber(100_000));
		assertEquals("999k", DataTable.shortNumber(999_499));
	}

	/**
	 * 999,500 rounds up to a thousand thousands, and "1000k" is the exact five-character
	 * width the M band exists to avoid - so the hand-off goes by the rounded value.
	 */
	@Test
	public void valuesThatRoundToAThousandThousandsReadAsMillions()
	{
		assertEquals("1M", DataTable.shortNumber(999_500));
		assertEquals("1M", DataTable.shortNumber(1_000_000));
		assertEquals("7.3M", DataTable.shortNumber(7_279_000));
	}

	/** A loss reads like the matching gain behind a minus, never as a seven-digit raw number. */
	@Test
	public void negativesMirrorPositives()
	{
		assertEquals("-999", DataTable.shortNumber(-999));
		assertEquals("-1.5k", DataTable.shortNumber(-1500));
		assertEquals("-999k", DataTable.shortNumber(-999_499));
		assertEquals("-1M", DataTable.shortNumber(-999_500));
	}
}
