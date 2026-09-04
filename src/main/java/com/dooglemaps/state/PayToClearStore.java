package com.dooglemaps.state;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.PayToClear;
import com.dooglemaps.data.Seed;
import com.dooglemaps.timer.PatchProjection;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Which crops the player would rather buy a gardener out of clearing than chop themselves.
 *
 * <h2>Per crop only, with no group and no off-set</h2>
 *
 * A magic tree's roots are worth the axe and a maple's logs are not, and that is true wherever
 * either tree happens to be planted — unlike {@link ProtectionSelectionStore}, whose group half
 * earns its place because the very same seed genuinely disagrees on disease risk between a
 * Weiss patch and an Ardougne one. Nothing analogous exists here: a gardener charges 200 coins
 * for a magic tree whichever group owns the patch it stands in. Dropping the group half also
 * drops the reason {@code ProtectionSelectionStore} needs an {@code OFF_KEY} and a
 * {@code retargetContract} at all — both exist to stop a split or a settled contract from
 * silently reviving or overriding an inherited answer, and with one flat set keyed on the seed
 * alone there is nothing to inherit and nothing scoped to a contract that can go stale. Absence
 * means "chop it yourself", which is exactly today's behaviour, so an account that has never
 * heard of this key behaves exactly as it always has.
 *
 * <h2>Keyed on the crop standing in the patch, never on the seed about to be planted</h2>
 *
 * The owner's own scenario is the reason this gets its own paragraph: "I want to clear a magic
 * tree myself even if I'm planting a maple in it next." A checked, standing magic tree is
 * {@code HARVESTABLE}, so it is a perfectly ordinary member of a seed allocation's plantable
 * list — the run may already have decided a maple sapling goes in that exact patch once the
 * magic comes out. {@code GuideTracker}'s {@code inGround} is computed for a different question
 * ("what will be growing here") and, for exactly this patch, prefers the allocation's chosen
 * seed over the one actually standing — so asking {@code inGround} whether to pay would read the
 * maple's tick-box to decide the fate of the magic tree next to it. {@link #isPayingFor} is
 * therefore the only lookup any caller should use for a standing crop: it takes the projection,
 * resolves the seed from {@link PatchProjection#getProduce()} alone, and never sees {@code
 * inGround} or an allocation's choice at all.
 */
@Slf4j
@Singleton
public class PayToClearStore
{
	private static final String KEY = "payToClear";

	private static final Type NAME_LIST_TYPE = new TypeToken<ArrayList<String>>()
	{
	}.getType();

	private final ConfigManager configManager;
	private final Gson gson;

	/** Seed names the player has said they would rather pay a gardener to clear than chop. */
	private final Set<String> paying = new LinkedHashSet<>();
	private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

	@Inject
	PayToClearStore(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	public void addChangeListener(Runnable listener)
	{
		changeListeners.add(listener);
	}

	public void removeChangeListener(Runnable listener)
	{
		changeListeners.remove(listener);
	}

	/** Whether the player would pay a gardener to clear this crop rather than chop it. */
	public synchronized boolean isPayingFor(@Nullable Seed seed)
	{
		return seed != null && paying.contains(seed.name());
	}

	/**
	 * The same question asked of a patch, which is the form the guide, the loadout and the seed
	 * selector actually want.
	 *
	 * <p>Resolved from the projection's own {@link PatchProjection#getProduce()} — the crop
	 * <b>standing</b> there — and never from an allocation's chosen seed. See the class note.
	 * Answers false whenever there is nothing to ask about: a null projection, an empty patch, a
	 * produce with no seed at all, or a patch type no gardener will clear.
	 */
	public boolean isPayingFor(@Nullable PatchProjection projection)
	{
		if (projection == null || projection.getProduce() == null)
		{
			return false;
		}
		if (!PayToClear.supports(projection.getPatch().getImplementation()))
		{
			return false;
		}
		Seed seed = Seed.forProduce(projection.getProduce());
		return isPayingFor(seed);
	}

	/** Sets whether this crop is bought out rather than chopped. Returns the new state. */
	public boolean setPayingFor(Seed seed, boolean pay)
	{
		synchronized (this)
		{
			boolean changed = pay ? paying.add(seed.name()) : paying.remove(seed.name());
			if (!changed)
			{
				return pay;
			}
			save();
		}

		log.debug("{} will {}be paid to clear", seed.name(), pay ? "" : "not ");
		for (Runnable listener : changeListeners)
		{
			listener.run();
		}
		return pay;
	}

	public void load()
	{
		synchronized (this)
		{
			paying.clear();
			readInto(KEY, paying);
		}

		for (Runnable listener : changeListeners)
		{
			listener.run();
		}
	}

	/** Reads the stored set, tolerating anything unreadable rather than throwing. */
	private void readInto(String key, Set<String> into)
	{
		String json = configManager.getRSProfileConfiguration(DoogleMapsConfig.GROUP, key);
		if (json == null || json.isEmpty())
		{
			return;
		}

		try
		{
			List<String> keys = gson.fromJson(json, NAME_LIST_TYPE);
			if (keys != null)
			{
				into.addAll(keys);
			}
		}
		catch (JsonSyntaxException e)
		{
			log.warn("Discarding unreadable pay-to-clear selection from {}", key, e);
		}
	}

	private synchronized void save()
	{
		configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, KEY,
			gson.toJson(new ArrayList<>(paying)));
	}
}
