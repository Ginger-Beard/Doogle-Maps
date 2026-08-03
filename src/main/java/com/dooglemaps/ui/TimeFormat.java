package com.dooglemaps.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Short human phrasings of durations and instants, for the panel and tooltips. */
final class TimeFormat
{
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm")
		.withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter CLOCK_WITH_DAY = DateTimeFormatter.ofPattern("EEE HH:mm")
		.withZone(ZoneId.systemDefault());

	private static final long HOUR = 3600;
	private static final long DAY = 86400;

	private TimeFormat()
	{
	}

	/** e.g. "2h 15m", "45m", "3d 4h". Rounds down; sub-minute reads as "<1m". */
	static String duration(long seconds)
	{
		if (seconds < 60)
		{
			return "<1m";
		}
		if (seconds < HOUR)
		{
			return (seconds / 60) + "m";
		}
		if (seconds < DAY)
		{
			long hours = seconds / HOUR;
			long minutes = (seconds % HOUR) / 60;
			return minutes == 0 ? hours + "h" : hours + "h " + minutes + "m";
		}

		long days = seconds / DAY;
		long hours = (seconds % DAY) / HOUR;
		return hours == 0 ? days + "d" : days + "d " + hours + "h";
	}

	/** Clock time, gaining a weekday once it is more than a day out. */
	static String clock(long epochSeconds)
	{
		long now = Instant.now().getEpochSecond();
		DateTimeFormatter formatter = Math.abs(epochSeconds - now) >= DAY ? CLOCK_WITH_DAY : CLOCK;
		return formatter.format(Instant.ofEpochSecond(epochSeconds));
	}

	/** e.g. "3h 10m ago", or "just now" under a minute. */
	static String since(long epochSeconds)
	{
		long elapsed = Instant.now().getEpochSecond() - epochSeconds;
		if (elapsed < 60)
		{
			return "just now";
		}
		return duration(elapsed) + " ago";
	}
}
