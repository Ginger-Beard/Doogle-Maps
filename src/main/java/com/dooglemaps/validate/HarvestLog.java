package com.dooglemaps.validate;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropXp;
import com.dooglemaps.data.CropYield;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.data.Seed;
import com.dooglemaps.state.FarmingBonusStore;
import com.dooglemaps.state.PatchSnapshot;
import com.dooglemaps.route.PatchLocationStore;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.timer.CropYieldModel;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.RuneLite;
import net.runelite.client.eventbus.Subscribe;

/**
 * Watches real harvests so the plugin's own predictions can be checked against them.
 *
 * <p>Every yield figure in the panel is arithmetic derived from wiki constants. The formula
 * reproduces every published percentage, but "reproduces the wiki" and "matches the game" are
 * different claims, and only one of them is the one that matters. This collects the evidence
 * for the second while you play normally.
 *
 * <p>How a harvest is pieced together, since none of it arrives labelled:
 *
 * <ul>
 *   <li>The <b>crop</b> comes from the item that lands in the inventory. A ranarr weed can
 *       only have come from a herb patch that was holding a ranarr, so the item identifies
 *       the patch far more reliably than proximity does.</li>
 *   <li>The <b>count</b> is the inventory delta, not the varbit. A composted patch has more
 *       lives than the varbit has states, so the varbit genuinely cannot count picks.</li>
 *   <li>The <b>experience</b> is attributed to whichever patch last produced an item, which
 *       is right because a player picks one patch at a time.</li>
 *   <li>The harvest <b>ends</b> when the patch stops being harvestable — picked clean for a
 *       herb, picked to nothing for a bush that will regrow. A patch left standing is written
 *       out too, flagged incomplete, so a partial run cannot masquerade as a low yield.</li>
 * </ul>
 *
 * <p>Records go to {@code ~/.runelite/doogle-maps/harvests.csv} as well as the client log,
 * because the interesting question is the average over a few dozen harvests, not any one of
 * them, and a CSV can be averaged.
 */
@Slf4j
@Singleton
public class HarvestLog
{
	/**
	 * Ticks without an item before a patch is treated as abandoned rather than being picked.
	 *
	 * <p>Generous on purpose: it is normal to be interrupted mid-patch, and closing a record
	 * early would invent a short harvest that never happened.
	 */
	private static final int IDLE_TICKS_BEFORE_ABANDON = 100;

	/**
	 * How far from a patch you can be and still be credited with picking it.
	 *
	 * <p>You have to stand next to a patch to harvest it, and the inventory event arrives on
	 * the same tick, so anything beyond a screen away did not come from there — it was bought,
	 * traded, or picked somewhere the plugin does not track. A generous limit rather than a
	 * tight one because a patch whose position has never been learned falls back to its
	 * region's centre, which can be half a region out.
	 */
	private static final int MAX_ATTRIBUTION_DISTANCE = 64;

	private static final String CSV_HEADER =
		"time,patch,crop,level,compost,secateurs,cape,attas,lives,predicted,actual,"
			+ "predicted_xp,actual_xp,completed";

	/** Produce keyed by the item it drops, for the crops we make claims about. */
	private static final Map<Integer, Produce> BY_ITEM = new HashMap<>();

	static
	{
		// Only crops the plugin actually predicts something for. Building this from every
		// Produce would fold in the placeholders — ANYHERB shares an item id with guam — and
		// misattribute picks.
		for (CropYield yield : CropYield.values())
		{
			BY_ITEM.put(yield.getSeed().getProduce().getItemID(), yield.getSeed().getProduce());
		}
		for (CropXp xp : CropXp.values())
		{
			Seed seed = xp.getSeed();
			BY_ITEM.putIfAbsent(seed.getProduce().getItemID(), seed.getProduce());
		}
	}

	private final DoogleMapsConfig config;
	private final PatchStateStore patches;
	private final SeedInventoryStore seeds;
	private final FarmingBonusStore bonuses;
	private final HarvestStatsStore stats;
	private final PatchLocationStore locations;
	private final Client client;

	/** Live harvests, keyed by patch. Several can be open at once on a mixed run. */
	private final Map<String, HarvestRecord> open = new LinkedHashMap<>();

	/**
	 * Records whose patch has emptied but whose tick has not finished.
	 *
	 * <p>They stay here until the next game tick so that experience arriving after the varbit
	 * change still reaches them. See {@link #onPatchState}.
	 */
	private final List<HarvestRecord> finished = new ArrayList<>();

	/** Last inventory contents, to turn container events into per-item deltas. */
	private Map<Integer, Integer> lastInventory = new HashMap<>();

	/**
	 * Whether an inventory has been seen yet this session.
	 *
	 * <p>The first container event after login describes what you were already carrying, so
	 * every item in it looks freshly gained. Without this, logging in beside a ripe patch
	 * while holding herbs would invent a harvest that never happened.
	 */
	private boolean inventoryPrimed;

	private int lastFarmingXp = -1;
	private String lastActivePatch;
	private boolean headerWritten;

	@Inject
	private HarvestLog(DoogleMapsConfig config, PatchStateStore patches,
		SeedInventoryStore seeds, FarmingBonusStore bonuses, HarvestStatsStore stats,
		PatchLocationStore locations, Client client)
	{
		this.locations = locations;
		this.client = client;
		this.config = config;
		this.patches = patches;
		this.seeds = seeds;
		this.bonuses = bonuses;
		this.stats = stats;
	}

	/** Drops everything in flight. Nothing half-observed survives a logout or a hop. */
	public void reset()
	{
		open.clear();
		finished.clear();
		lastInventory = new HashMap<>();
		inventoryPrimed = false;
		lastFarmingXp = -1;
		lastActivePatch = null;
	}

	// ------------------------------------------------------------------- events

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (!config.logHarvests() || event.getContainerId() != InventoryID.INV)
		{
			return;
		}

		Map<Integer, Integer> current = countItems(event.getItemContainer());
		if (!inventoryPrimed)
		{
			inventoryPrimed = true;
			lastInventory = current;
			return;
		}

		for (Map.Entry<Integer, Integer> entry : current.entrySet())
		{
			int gained = entry.getValue() - lastInventory.getOrDefault(entry.getKey(), 0);
			Produce produce = gained > 0 ? BY_ITEM.get(entry.getKey()) : null;
			if (produce != null)
			{
				credit(produce, gained);
			}
		}
		lastInventory = current;
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (event.getSkill() != Skill.FARMING)
		{
			return;
		}

		int total = event.getXp();
		int previous = lastFarmingXp;
		lastFarmingXp = total;

		if (!config.logHarvests() || previous < 0 || total <= previous)
		{
			return;
		}

		// A pick awards its item and its experience in the same tick, so the patch that just
		// took an item is the one that earned this.
		double gained = total - previous;
		HarvestRecord active = activeRecord();
		if (active == null)
		{
			// No item arrived, so nothing opened a record — which is exactly what an open herb
			// sack does: the grimy herb never touches the inventory. The experience still
			// arrives, and at a rate that names the crop, so the harvest can be opened from it.
			active = openFromExperience(gained);
		}

		if (active != null)
		{
			active.addXp(gained);
		}
	}

	/**
	 * Starts a record for a pick seen only as experience.
	 *
	 * <p>Matched on the <b>exact</b> per-pick rate of a ripe patch nearby. Deliberately strict:
	 * picks arrive one at a time, so a single pick's worth is what to expect, and requiring an
	 * exact match stops planting or check-health experience from being mistaken for a harvest.
	 */
	@Nullable
	private HarvestRecord openFromExperience(double gained)
	{
		WorldPoint player = client.getLocalPlayer() == null
			? null
			: client.getLocalPlayer().getWorldLocation();
		if (player == null)
		{
			return null;
		}

		FarmPatch best = null;
		Produce bestProduce = null;
		int bestDistance = Integer.MAX_VALUE;

		for (PatchSnapshot snapshot : patches.getAll())
		{
			if (snapshot.getCropState() != CropState.HARVESTABLE || snapshot.getProduce() == null)
			{
				continue;
			}

			CropXp rates = CropXp.forProduce(snapshot.getProduce());
			// Against the boosted rate, not the published one: the Farmer's outfit adds up to
			// 2.5% to everything, so the unboosted figure is not what actually arrives.
			if (rates == null || !HarvestRecord.isOnePick(gained,
				bonuses.current().applyOutfit(rates.getHarvestXp())))
			{
				continue;
			}

			FarmPatch patch = FarmingWorldData.getPatch(snapshot.getPatchKey());
			if (patch == null || open.containsKey(patch.getKey()))
			{
				continue;
			}

			int distance = distanceTo(player, patch);
			if (distance < bestDistance)
			{
				bestDistance = distance;
				best = patch;
				bestProduce = snapshot.getProduce();
			}
		}

		if (best == null || bestDistance > MAX_ATTRIBUTION_DISTANCE)
		{
			return null;
		}

		log.debug("Opening {} harvest at {} from experience alone - the item never reached the "
			+ "inventory, so something is swallowing it", bestProduce.getName(), best.getDisplayName());

		PatchSnapshot snapshot = patches.get(best);
		HarvestRecord record = new HarvestRecord(best, bestProduce,
			snapshot == null ? CompostTier.NONE : snapshot.getCompost(),
			seeds.getFarmingLevel(), bonuses.current(), Instant.now().getEpochSecond());
		open.put(best.getKey(), record);
		lastActivePatch = best.getKey();
		return record;
	}

	/**
	 * The record the last picked item went to, open or just finished.
	 *
	 * <p>The "just finished" half matters: the pick that empties a patch closes its record and
	 * pays its experience in the same tick, and the varbit arrives first. Looking only at open
	 * records dropped that last award on the floor.
	 */
	@Nullable
	private HarvestRecord activeRecord()
	{
		if (lastActivePatch == null)
		{
			return null;
		}

		HarvestRecord record = open.get(lastActivePatch);
		if (record != null)
		{
			return record;
		}

		for (HarvestRecord candidate : finished)
		{
			if (lastActivePatch.equals(candidate.getPatch().getKey()))
			{
				return candidate;
			}
		}
		return null;
	}

	/**
	 * Learns what the game says when a container swallows a harvested item.
	 *
	 * <p>An open herb sack takes a grimy herb before it reaches the inventory, so there is no
	 * delta to count. There is no container to read and no varbit either — core RuneLite's own
	 * loot tracker resorts to a regex over chat spam for the herbiboar version of this, which
	 * is about as clear a signal as there is that nothing better exists.
	 *
	 * <p>So chat is the exact signal, and the experience inference in {@link HarvestRecord} is
	 * the approximate one. This does not parse anything yet — it records the wording while a
	 * harvest is actually in flight, so the pattern can be written from an observation rather
	 * than from a guess. Same approach as the Geomancy vocabulary catalogue, which worked.
	 */
	@Subscribe
	public void onChatMessage(net.runelite.api.events.ChatMessage event)
	{
		if (!config.logHarvests() || open.isEmpty())
		{
			return;
		}
		if (event.getType() != net.runelite.api.ChatMessageType.SPAM
			&& event.getType() != net.runelite.api.ChatMessageType.GAMEMESSAGE)
		{
			return;
		}

		String message = event.getMessage();
		String lower = message.toLowerCase();
		if (lower.contains("sack") || lower.contains("basket") || lower.contains("box"))
		{
			log.info("Harvest storage message seen: \"{}\" - if items are going somewhere the "
				+ "inventory cannot see, this is the wording to match on", message);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// Records that finished during the tick just gone. Everything for that tick — the last
		// item, the varbit change and the experience — has now been delivered, so the record is
		// complete in a way it was not at the moment the patch emptied.
		for (HarvestRecord record : finished)
		{
			write(record);
		}
		finished.clear();

		for (Iterator<Map.Entry<String, HarvestRecord>> it = open.entrySet().iterator(); it.hasNext(); )
		{
			HarvestRecord record = it.next().getValue();
			record.tick();
			if (record.getTicksIdle() > IDLE_TICKS_BEFORE_ABANDON)
			{
				it.remove();
				write(record);
			}
		}
	}

	/**
	 * Told by the patch scanner whenever a patch's decoded state changes.
	 *
	 * <p>A harvest ends when there is nothing left on the patch to pick. The varbit is
	 * authoritative for <i>that</i>, even though it cannot count the picks.
	 *
	 * <p>"Nothing left to pick" rather than "the patch is empty", which is the same thing for
	 * a herb or an allotment and not at all the same for a bush, a fruit tree or a cactus:
	 * those regrow, so the patch goes straight back to holding the crop and never empties.
	 * Waiting for empty meant <b>no regrowing crop ever completed a record</b> — each one sat
	 * open until the idle timer abandoned it, so every berry ever picked landed in the
	 * "left standing" pile and jangerberry reported 0 harvests against 7 items.
	 */
	public void onPatchState(FarmPatch patch, @Nullable ProduceState previous, ProduceState current)
	{
		if (!config.logHarvests())
		{
			return;
		}

		HarvestRecord record = open.get(patch.getKey());
		if (record == null)
		{
			return;
		}

		// Still harvestable covers picking part of a stock: a palm at six fruit and the same
		// palm at three are both harvestable, and both are the same harvest.
		boolean stillPickable = current.getProduce() == record.getProduce()
			&& current.getCropState() == CropState.HARVESTABLE;
		if (stillPickable)
		{
			return;
		}

		open.remove(patch.getKey());
		record.markCompleted();

		// Held rather than written, because the experience for the pick that emptied the patch
		// has not arrived yet. Item, varbit and experience all land in the same tick and the
		// varbit gets there first, so writing here recorded a harvest with the last pick's
		// experience missing — and for a flower patch, which empties on the *only* pick, that
		// meant all of it: a limpwurt patch logged 0 experience against a predicted 120.
		finished.add(record);
	}

	// ------------------------------------------------------------------ internals

	/** Attributes picked items to the patch they must have come from. */
	private void credit(Produce produce, int count)
	{
		FarmPatch patch = findPatchHolding(produce);
		if (patch == null)
		{
			// The crop is one we know, but no tracked patch is holding it — bought, traded,
			// or picked somewhere the plugin has not scanned. Not a harvest we can score.
			return;
		}

		HarvestRecord record = open.computeIfAbsent(patch.getKey(), key ->
		{
			PatchSnapshot snapshot = patches.get(patch);
			return new HarvestRecord(patch, produce,
				snapshot == null ? CompostTier.NONE : snapshot.getCompost(),
				seeds.getFarmingLevel(), bonuses.current(), Instant.now().getEpochSecond());
		});

		record.addItems(count);
		lastActivePatch = patch.getKey();
	}

	/**
	 * The patch currently holding this crop, ready to pick, and near enough to be picking it.
	 *
	 * <p>Ambiguity is real but rare — two ranarr patches both ripe, both being picked in the
	 * same few ticks. When it happens the first is taken and the second's record simply never
	 * opens, which costs a data point and cannot corrupt one.
	 */
	@Nullable
	private FarmPatch findPatchHolding(Produce produce)
	{
		WorldPoint player = client.getLocalPlayer() == null
			? null
			: client.getLocalPlayer().getWorldLocation();
		if (player == null)
		{
			return null;
		}

		FarmPatch best = null;
		int bestDistance = Integer.MAX_VALUE;

		for (PatchSnapshot snapshot : patches.getAll())
		{
			if (snapshot.getProduce() != produce
				|| snapshot.getCropState() != CropState.HARVESTABLE)
			{
				continue;
			}

			FarmPatch patch = FarmingWorldData.getPatch(snapshot.getPatchKey());
			if (patch == null)
			{
				continue;
			}

			int distance = distanceTo(player, patch);
			if (distance < bestDistance)
			{
				bestDistance = distance;
				best = patch;
			}
		}

		// Nothing near enough to be the source. Better to lose the observation than to credit
		// it to a patch on the other side of the map — which is exactly what happened when the
		// only tie-break was "prefer a record that is already open": every watermelon on a run
		// funnelled into whichever allotment was picked first, and one record claimed 110
		// against a predicted 11.
		return bestDistance > MAX_ATTRIBUTION_DISTANCE ? null : best;
	}

	/**
	 * How far the player is from a patch, for attributing a pick.
	 *
	 * <p>Matching regions counts as zero, and is checked first because it is the more
	 * trustworthy signal: a patch's region is the one whose varbits carry it, which is exact,
	 * whereas its coordinates may be a learned position or merely the region's centre.
	 *
	 * <p>Falling back to real coordinates matters because a region match is not guaranteed
	 * even when standing at the patch — some farming regions span more than one map square,
	 * and the plugin mirrors only the canonical id. Without a second signal those patches
	 * always tied, and the tie-break was what funnelled a whole run into one record.
	 */
	private int distanceTo(WorldPoint player, FarmPatch patch)
	{
		if (player.getRegionID() == patch.getRegion().getRegionId())
		{
			return 0;
		}

		WorldPoint location = locations.getLocation(patch);
		if (location == null || location.getPlane() != player.getPlane())
		{
			return Integer.MAX_VALUE;
		}
		return player.distanceTo(location);
	}

	private static Map<Integer, Integer> countItems(@Nullable ItemContainer container)
	{
		Map<Integer, Integer> counts = new HashMap<>();
		if (container == null)
		{
			return counts;
		}
		for (Item item : container.getItems())
		{
			if (item != null && item.getQuantity() > 0)
			{
				counts.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
		return counts;
	}

	// -------------------------------------------------------------------- output

	private void write(HarvestRecord record)
	{
		if (record.getItemsHarvested() <= 0)
		{
			return;
		}

		double predicted = record.getPredictedYield();
		log.info("Harvest: {} x{} from {} — predicted {}, {} at level {}{}{}",
			record.getProduce().getName(),
			record.getItemsHarvested(),
			record.getPatch().getDisplayName(),
			predicted > 0 ? String.format("%.1f", predicted) : "n/a",
			record.getCompost().getDisplayName().toLowerCase(),
			record.getFarmingLevel(),
			describeBonuses(record),
			record.isCompleted() ? "" : " (left standing, not picked clean)");

		warnIfCompostWasMissed(record, predicted);

		double predictedXp = record.getPredictedXp();
		// Skipped when the count came from the experience, because then the prediction is that
		// same experience divided and multiplied by the same constant. It would always agree,
		// and an assertion that cannot fail is worse than no assertion.
		if (!record.isInferredFromXp() && predictedXp > 0
			&& Math.abs(predictedXp - record.getXpGained()) > 0.5)
		{
			// Worth shouting about: it means the per-pick figure in CropXp is wrong, which is
			// a data bug rather than a modelling one.
			log.warn("Harvest XP mismatch for {}: predicted {} for {} picks, saw {}",
				record.getProduce().getName(), predictedXp, record.getItemsHarvested(),
				record.getXpGained());
		}

		// The rolled-up totals are the part that outlives this session and can be shown back
		// to the player; the CSV is the raw trail behind them.
		stats.record(record);
		appendCsv(record);
	}

	/**
	 * Says so when a harvest can only be explained by compost we did not see.
	 *
	 * <p>Yield scales with harvest lives, and compost is the only thing that adds any: three
	 * untreated, up to six with ultra. So a patch recorded as untreated that gives more than
	 * about one and a half times its prediction was almost certainly composted — a Prifddinas
	 * watermelon logged 22 against a predicted 11.13, which is the six-lives figure exactly.
	 *
	 * <p>Worth a warning rather than a quiet CSV row, because it is a <i>capture</i> failure
	 * and not a modelling one: the arithmetic was right for the inputs it was given. Only for
	 * crops that respond to compost, and only when we recorded none, so a lucky roll on an
	 * ultracomposted patch cannot trip it.
	 */
	private static void warnIfCompostWasMissed(HarvestRecord record, double predicted)
	{
		if (record.getCompost() != CompostTier.NONE || predicted <= 0 || !record.isCompleted())
		{
			return;
		}

		Seed seed = Seed.forProduce(record.getProduce());
		if (seed == null || !CropYieldModel.respondsToCompost(seed))
		{
			return;
		}

		// Ultracompost doubles the lives, so anything past halfway there is beyond what an
		// untreated patch can plausibly roll.
		if (record.getItemsHarvested() > predicted * 1.5)
		{
			log.warn("Harvest of {} at {} gave {} against a predicted {} for an untreated patch. "
					+ "That patch was probably composted and we did not see it - the treatment is "
					+ "only learnable from the chat message when it is applied, or from Inspect.",
				record.getProduce().getName(), record.getPatch().getDisplayName(),
				record.getItemsHarvested(), String.format("%.1f", predicted));
		}
	}

	private static String describeBonuses(HarvestRecord record)
	{
		List<String> parts = new ArrayList<>();
		if (record.getBonuses().isMagicSecateurs())
		{
			parts.add("secateurs");
		}
		if (record.getBonuses().isFarmingCape())
		{
			parts.add("cape");
		}
		if (record.getBonuses().isAttas())
		{
			parts.add("attas");
		}
		return parts.isEmpty() ? "" : " with " + String.join(", ", parts);
	}

	/**
	 * Appends one row to the CSV, creating it on first use.
	 *
	 * <p>Failures are logged once and otherwise ignored. Being unable to write a validation
	 * file is not a reason to interfere with anyone's game.
	 */
	private void appendCsv(HarvestRecord record)
	{
		File dir = new File(RuneLite.RUNELITE_DIR, "doogle-maps");
		File file = new File(dir, "harvests.csv");
		try
		{
			if (!dir.exists() && !dir.mkdirs())
			{
				return;
			}

			boolean needsHeader = !headerWritten && !file.exists();
			try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				PrintWriter out = new PrintWriter(writer))
			{
				if (needsHeader)
				{
					out.println(CSV_HEADER);
				}
				out.printf("%s,%s,%s,%d,%s,%b,%b,%b,%d,%.2f,%d,%.1f,%.1f,%b%n",
					Instant.now(),
					record.getPatch().getDisplayName().replace(',', ' '),
					record.getProduce().getName().replace(',', ' '),
					record.getFarmingLevel(),
					record.getCompost().name(),
					record.getBonuses().isMagicSecateurs(),
					record.getBonuses().isFarmingCape(),
					record.getBonuses().isAttas(),
					record.getLives(),
					record.getPredictedYield(),
					record.getItemsHarvested(),
					record.getPredictedXp(),
					record.getXpGained(),
					record.isCompleted());
			}
			headerWritten = true;
		}
		catch (IOException e)
		{
			log.warn("Could not write {}", file, e);
		}
	}

	/** Everything still being picked, for tests. */
	Map<String, HarvestRecord> getOpenHarvests()
	{
		return new LinkedHashMap<>(open);
	}
}
