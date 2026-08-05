package com.dooglemaps.state;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Produce;
import com.dooglemaps.timer.DiaryBonus;
import com.dooglemaps.timer.FarmingBonuses;
import com.dooglemaps.timer.FarmingOutfit;
import java.util.EnumSet;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Varbits;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;

/**
 * Whether the player is carrying the things that improve a harvest.
 *
 * <p>Follows the same discipline as {@link SeedInventoryStore}: containers are recorded when
 * the game hands them over and every read is answered from memory. The panel asks this while
 * repainting on the Swing thread, and reading an item container there would assert.
 *
 * <p>Secateurs and the cape persist per profile, because they are worn nearly every run and
 * a freshly loaded panel showing a worse yield than the player actually gets would be wrong
 * in the more annoying direction. Attas is not persisted — it is read live from the anima
 * patch, which the plugin already tracks.
 */
@Slf4j
@Singleton
public class FarmingBonusStore
{
	private static final String SECATEURS_KEY = "hasMagicSecateurs";
	private static final String CAPE_KEY = "hasFarmingCape";
	private static final String OUTFIT_KEY = "farmingOutfitBonus";
	private static final String DIARY_KEY = "diaries";

	private final ConfigManager configManager;
	private final PatchStateStore patches;
	private final ItemManager itemManager;
	private final Client client;

	@Inject
	private FarmingBonusStore(ConfigManager configManager, PatchStateStore patches,
		ItemManager itemManager, Client client)
	{
		this.configManager = configManager;
		this.patches = patches;
		this.itemManager = itemManager;
		this.client = client;
	}

	/**
	 * Notes what a container holds, if it is one that can carry a bonus.
	 *
	 * <p>Both the inventory and the equipment tab count, because magic secateurs work from
	 * either — the game checks for the item, not for a wield.
	 *
	 * @return true if this was a container worth reading
	 */
	public boolean record(int containerId, ItemContainer container)
	{
		if (container == null
			|| (containerId != InventoryID.INV && containerId != InventoryID.WORN))
		{
			return false;
		}

		// The cape only counts when worn, so the equipment tab is the only place worth paying
		// for a name lookup. Doing it for the inventory too meant an item-composition call per
		// slot on every pickup, for an answer that was then thrown away.
		boolean wornContainer = containerId == InventoryID.WORN;

		boolean secateurs = false;
		boolean cape = false;
		Set<FarmingOutfit> outfit = EnumSet.noneOf(FarmingOutfit.class);
		for (Item item : container.getItems())
		{
			if (item == null || item.getQuantity() <= 0)
			{
				continue;
			}
			secateurs |= item.getId() == ItemID.FAIRY_ENCHANTED_SECATEURS;
			cape |= wornContainer && isFarmingCape(item.getId());

			if (wornContainer)
			{
				FarmingOutfit piece = FarmingOutfit.forItemId(item.getId());
				if (piece != null)
				{
					outfit.add(piece);
				}
			}
		}

		// Only the container we just read can be spoken for. Secateurs sitting in the
		// inventory must not be forgotten because the equipment tab happened to change.
		if (containerId == InventoryID.INV)
		{
			setFlag(SECATEURS_KEY + ".inv", secateurs);
		}
		else
		{
			setFlag(SECATEURS_KEY + ".worn", secateurs);
			setFlag(CAPE_KEY, cape);
			// The outfit only counts worn, so the equipment tab is the whole story.
			setOutfitBonus(FarmingOutfit.bonusFor(outfit));
		}
		return true;
	}

	/**
	 * Farming cape, its trimmed form, and any max cape.
	 *
	 * <p>Matched on the item's name rather than a list of ids, because there are more than
	 * fifty cosmetic max capes — Saradomin, Dizana's, assembler, each with broken and trouver
	 * variants — and every one of them carries the perk. A list would be wrong the day the
	 * next one is released.
	 *
	 * <p>Safe here only because {@link #record} runs on the client thread, called from an
	 * item-container event.
	 */
	private boolean isFarmingCape(int itemId)
	{
		String name = itemManager.getItemComposition(itemId).getName();
		if (name == null)
		{
			return false;
		}
		String lower = name.toLowerCase();
		return lower.startsWith("farming cape") || lower.endsWith("max cape");
	}

	/**
	 * Records which of the yield-affecting diaries are finished.
	 *
	 * <p>Cached rather than read on demand: the panel asks for this while repainting, and
	 * varbits can only be read on the client thread. Diary completion changes about four times
	 * in an account's life, so a cache costs nothing.
	 *
	 * <p>Must be called on the client thread.
	 */
	public void recordDiaries()
	{
		int flags = (client.getVarbitValue(Varbits.DIARY_KANDARIN_MEDIUM) == 1 ? 1 : 0)
			| (client.getVarbitValue(Varbits.DIARY_KANDARIN_HARD) == 1 ? 2 : 0)
			| (client.getVarbitValue(Varbits.DIARY_KANDARIN_ELITE) == 1 ? 4 : 0)
			| (client.getVarbitValue(Varbits.DIARY_KOUREND_HARD) == 1 ? 8 : 0);

		Integer stored = configManager.getRSProfileConfiguration(
			DoogleMapsConfig.GROUP, DIARY_KEY, int.class);
		if (stored == null || stored != flags)
		{
			configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, DIARY_KEY, flags);
			log.debug("Diary flags = {}", flags);
		}
	}

	/** Which diaries are finished, from the cache. */
	public DiaryBonus.Completed getDiaries()
	{
		Integer flags = configManager.getRSProfileConfiguration(
			DoogleMapsConfig.GROUP, DIARY_KEY, int.class);
		int value = flags == null ? 0 : flags;
		return new DiaryBonus.Completed(
			(value & 1) != 0, (value & 2) != 0, (value & 4) != 0, (value & 8) != 0);
	}

	/**
	 * The bonuses in play for one particular patch.
	 *
	 * <p>The diary rewards belong to a place rather than to the player, so this is the only
	 * form that is correct for a yield estimate. {@link #current()} leaves the diary at zero
	 * and is right only where no patch is in view.
	 */
	public FarmingBonuses forPatch(com.dooglemaps.data.FarmPatch patch)
	{
		return current().withDiaryBonus(DiaryBonus.forPatch(patch, getDiaries()));
	}

	/**
	 * The bonuses currently in play, before any patch-specific diary bonus.
	 *
	 * <p>The diary is left at zero here because it is a property of <i>where</i> the patch is,
	 * not of the player — see {@link FarmingBonuses#getDiaryBonus()}.
	 */
	public FarmingBonuses current()
	{
		return new FarmingBonuses(
			flag(SECATEURS_KEY + ".inv") || flag(SECATEURS_KEY + ".worn"),
			flag(CAPE_KEY),
			isAttasGrowing(),
			0,
			getOutfitBonus());
	}

	/**
	 * Whether an attas plant is in the ground somewhere.
	 *
	 * <p>Attas helps every patch in the game, not just the one it is next to, so a single
	 * anima patch anywhere is enough. Read live rather than cached because the plugin already
	 * tracks that patch and a stale answer here would quietly skew every other row.
	 */
	public boolean isAttasGrowing()
	{
		for (FarmPatch patch : FarmingWorldData.getPatches(PatchImplementation.ANIMA))
		{
			PatchSnapshot snapshot = patches.get(patch);
			if (snapshot == null || snapshot.getProduce() != Produce.ATTAS)
			{
				continue;
			}
			// A dead anima plant does nothing; a growing one already does.
			if (snapshot.getCropState() != CropState.DEAD)
			{
				return true;
			}
		}
		return false;
	}

	/** Forgets what the player was carrying. Relearned the moment a container is opened. */
	public void clear()
	{
		for (String key : new String[]{
			SECATEURS_KEY + ".inv", SECATEURS_KEY + ".worn", CAPE_KEY, OUTFIT_KEY, DIARY_KEY})
		{
			configManager.unsetRSProfileConfiguration(DoogleMapsConfig.GROUP, key);
		}
	}

	/** The Farmer's outfit multiplier last seen worn, 0 to 0.025. */
	public double getOutfitBonus()
	{
		String stored = configManager.getRSProfileConfiguration(DoogleMapsConfig.GROUP, OUTFIT_KEY);
		if (stored == null || stored.isEmpty())
		{
			return 0;
		}
		try
		{
			return Double.parseDouble(stored);
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	private void setOutfitBonus(double bonus)
	{
		if (Math.abs(getOutfitBonus() - bonus) > 1e-9)
		{
			configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, OUTFIT_KEY, bonus);
			log.debug("Farmer's outfit bonus = {}", bonus);
		}
	}

	private boolean flag(String key)
	{
		Boolean stored = configManager.getRSProfileConfiguration(
			DoogleMapsConfig.GROUP, key, boolean.class);
		return stored != null && stored;
	}

	private void setFlag(String key, boolean value)
	{
		if (flag(key) != value)
		{
			configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, key, value);
			log.debug("{} = {}", key, value);
		}
	}
}
