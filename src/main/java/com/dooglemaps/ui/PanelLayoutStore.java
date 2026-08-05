package com.dooglemaps.ui;

import com.dooglemaps.DoogleMapsConfig;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

/**
 * Remembers which collapsible sections the player left open.
 *
 * <p>Small, but the thing it prevents is not: a section that reopens every session is one the
 * player closes every session, and the closing is what they were trying to avoid. Any panel with
 * more than one or two of these needs to remember them or the collapsing is decoration.
 *
 * <h2>Stored globally rather than per RuneScape profile</h2>
 *
 * Everything else this plugin keeps is per profile, because it is <i>about</i> that account —
 * what is planted, what is owned, which patches are reachable. This is not: it is about the
 * person, and someone who wants the seed list open wants it open on their ironman too. Storing it
 * per profile would make them arrange the panel once per account for no reason.
 *
 * <p>It is also why nothing here is cleared by a profile reset. A reset throws away cached facts
 * about a character; it should not rearrange the furniture.
 */
@Singleton
public class PanelLayoutStore
{
	private static final String PREFIX = "open.";

	private final ConfigManager configManager;

	@Inject
	private PanelLayoutStore(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	/**
	 * Whether a section is open, falling back to the default the first time it is asked.
	 *
	 * @param key            identifies the section, and is qualified by patch type where there is
	 *                       one panel per type — herb seeds and tree seeds are different questions
	 * @param openByDefault  what to do before the player has expressed a preference
	 */
	public boolean isOpen(String key, boolean openByDefault)
	{
		Boolean stored = configManager.getConfiguration(
			DoogleMapsConfig.GROUP, PREFIX + key, Boolean.class);
		return stored == null ? openByDefault : stored;
	}

	/**
	 * Records a section's state.
	 *
	 * <p>Written on every toggle, which is fine: a config write is cheap and a toggle is a
	 * deliberate act rather than something that fires on a timer.
	 */
	public void setOpen(String key, boolean open)
	{
		configManager.setConfiguration(DoogleMapsConfig.GROUP, PREFIX + key, open);
	}
}
