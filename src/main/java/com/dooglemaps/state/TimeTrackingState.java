package com.dooglemaps.state;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.FarmPatch;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * What RuneLite's own Time Tracking plugin already knows about a patch.
 *
 * <h2>Why read another plugin's config</h2>
 *
 * Two facts about a patch cannot be read from its varbit: whether it was composted, and whether a
 * farmer was paid to watch it. This plugin learns both by watching you do them —
 * {@code CompostCapture} and {@code ProtectionCapture} — which works and has one unavoidable hole:
 * it only knows what it saw. A patch paid for before this plugin was installed, or on a session
 * where it was switched off, reads as unprotected forever.
 *
 * <p>Time Tracking has been recording both for years, per RuneScape profile, in plain config. So
 * they can simply be asked for.
 *
 * <h2>Keys</h2>
 *
 * Group {@code timetracking}, key <code>&lt;regionId&gt;.&lt;varbit&gt;.protected</code> and
 * <code>.compost</code>. That patch key is <b>identical to ours</b> — both are
 * {@code regionId + "." + varbit}, because both are generated from the same RuneLite data — so no
 * mapping is needed.
 *
 * <p>The key layout was found in Quest Helper's {@code PaymentTracker}, which reads exactly this
 * to decide whether to route you past a farmer. Nothing was copied: a config key is a fact rather
 * than code. Credited in {@code ATTRIBUTION.md} anyway, since it is where the trick came from.
 *
 * <h2>It is a fallback, not a replacement</h2>
 *
 * Our own capture stays in front, because it is live — it knows within a tick of you paying, and
 * this only knows once Time Tracking has written it. This answers the question our capture cannot:
 * <i>what happened before we were watching</i>.
 *
 * <p>Everything here is defensive. Time Tracking can be switched off, and its format is not a
 * contract with us — so an absent or unreadable value simply means "no answer", and the caller
 * falls back to what it already believed.
 */
@Slf4j
@Singleton
public class TimeTrackingState
{
	/** Time Tracking's config group, which is stable and public in the client. */
	private static final String GROUP = "timetracking";

	private final ConfigManager configManager;

	@Inject
	TimeTrackingState(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	/**
	 * Whether Time Tracking recorded a farmer being paid for this patch.
	 *
	 * @return null when it has nothing to say, which is not the same as "no"
	 */
	@Nullable
	public Boolean isProtected(FarmPatch patch)
	{
		return patch == null ? null : read(patch, "protected", Boolean.class);
	}

	/**
	 * The compost Time Tracking recorded on this patch.
	 *
	 * <p>Stored as the enum name of its own {@code CompostState}, whose constants — COMPOST,
	 * SUPERCOMPOST, ULTRACOMPOST — match ours by name. Matched by name rather than ordinal
	 * deliberately: an ordinal would silently shift if either enum gained a member.
	 *
	 * @return null when it has nothing to say
	 */
	@Nullable
	public CompostTier compost(FarmPatch patch)
	{
		String name = patch == null ? null : read(patch, "compost", String.class);
		if (name == null)
		{
			return null;
		}

		try
		{
			return CompostTier.valueOf(name);
		}
		catch (IllegalArgumentException e)
		{
			// A tier Time Tracking knows and we do not, or a format change. Either way there is
			// nothing useful to report, and guessing would be worse than saying nothing.
			log.debug("Time Tracking reported compost \"{}\", which is not a tier we know", name);
			return null;
		}
	}

	@Nullable
	private <T> T read(FarmPatch patch, String suffix, Class<T> type)
	{
		try
		{
			return configManager.getRSProfileConfiguration(
				GROUP, patch.getKey() + "." + suffix, type);
		}
		catch (RuntimeException e)
		{
			// Another plugin's storage, so its shape is not a promise to us.
			log.debug("Could not read Time Tracking's {} for {}", suffix, patch.getKey(), e);
			return null;
		}
	}
}
