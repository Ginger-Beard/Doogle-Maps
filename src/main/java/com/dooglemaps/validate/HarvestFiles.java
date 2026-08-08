package com.dooglemaps.validate;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;

/**
 * Where the harvest log lives: one file per RuneScape profile.
 *
 * <p>Every other store already scopes per profile through
 * {@code ConfigManager.getRSProfileConfiguration}; the CSV was the one thing shared, so on a
 * machine with two accounts both of their harvests landed in one file and both Stats tabs
 * showed the pool. A file cannot go through the config, so the profile goes into the filename
 * instead: {@code harvests-<profile>.csv}, with the profile taken from the same key the config
 * scoping uses.
 *
 * <h2>The shared file is adopted, once</h2>
 *
 * The first profile to load after this change finds {@code harvests.csv} and no scoped file,
 * and takes the old file as its own — a rename, so nothing is copied or lost. That is the
 * right answer for the ordinary case of one account per machine, and for a shared machine it
 * is no worse than what the shared file already was: data attributed to whoever is looking.
 * A second account simply starts a fresh file.
 */
@Slf4j
public final class HarvestFiles
{
	/**
	 * Serializes every touch of the log file.
	 *
	 * <p>Needed since the history's initial read moved off the client thread: the read (and
	 * the trim it can trigger, a whole-file rewrite through a temp file and a move) now runs
	 * on the executor while {@code HarvestLog} appends from the client thread. An append that
	 * lands between the trim's read and its move would be written to the old file and dropped
	 * by the rename. Both sides hold this before touching the file, so neither can tear the
	 * other.
	 */
	public static final Object FILE_LOCK = new Object();

	private HarvestFiles()
	{
	}

	/** The directory everything this plugin writes lives under. */
	public static File directory()
	{
		return new File(RuneLite.RUNELITE_DIR, "doogle-maps");
	}

	/**
	 * This profile's harvest log, adopting the old shared file if it is still there.
	 *
	 * <p>Falls back to the shared name when no profile is resolved yet — callers only read
	 * and append after login, when there always is one, so the fallback exists for safety
	 * rather than as a path anything is expected to take.
	 */
	public static File forProfile(ConfigManager configManager)
	{
		File dir = directory();
		File legacy = new File(dir, "harvests.csv");

		String key = configManager.getRSProfileKey();
		if (key == null)
		{
			return legacy;
		}

		// The key is "rsprofile.<id>"; only the id distinguishes profiles, and it is held to
		// filename-safe characters rather than trusted to be.
		String id = key.substring(key.lastIndexOf('.') + 1).replaceAll("[^A-Za-z0-9_-]", "");
		if (id.isEmpty())
		{
			return legacy;
		}

		File scoped = new File(dir, "harvests-" + id + ".csv");
		synchronized (FILE_LOCK)
		{
			if (!scoped.exists() && legacy.exists())
			{
				try
				{
					Files.move(legacy.toPath(), scoped.toPath());
					log.info("Adopted {} as {} - the harvest log is per profile now",
						legacy, scoped);
				}
				catch (IOException e)
				{
					log.warn("Could not adopt {} for this profile; keeping the shared file",
						legacy, e);
					return legacy;
				}
			}
		}
		return scoped;
	}
}
