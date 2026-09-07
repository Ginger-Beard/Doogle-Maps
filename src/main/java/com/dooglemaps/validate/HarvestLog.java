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
import com.dooglemaps.timer.FarmingBonuses;
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
	private final HarvestHistory history;
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

	/** The CSV's own column order, read from its header the first time it is appended to. */
	private java.util.List<String> columns;

	/** For the profile scoping of the file - see {@link HarvestFiles}. */
	private final net.runelite.client.config.ConfigManager configManager;

	@Inject
	HarvestLog(DoogleMapsConfig config, PatchStateStore patches,
		SeedInventoryStore seeds, FarmingBonusStore bonuses, HarvestStatsStore stats,
		HarvestHistory history, PatchLocationStore locations, Client client,
		net.runelite.client.config.ConfigManager configManager)
	{
		this.history = history;
		this.locations = locations;
		this.client = client;
		this.config = config;
		this.patches = patches;
		this.seeds = seeds;
		this.bonuses = bonuses;
		this.stats = stats;
		this.configManager = configManager;
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
		// The column order was learned from one profile's file, and a reset may mean a
		// different profile whose file was created by a different version.
		columns = null;
	}

	// ------------------------------------------------------------------- events

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.INV)
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

		if (previous < 0 || total <= previous)
		{
			return;
		}

		// A pick awards its item and its experience in the same tick, so the patch that just
		// took an item is the one that earned this.
		double gained = total - previous;

		// Before any attribution, and whether or not any is possible. A run is a sitting of
		// farming, and most of the experience in one is check-health on a tree that no harvest
		// record can ever see - see HarvestHistory.recordFarmingXp.
		history.recordFarmingXp(gained);

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
	 * Starts a record for a harvest seen only as experience.
	 *
	 * <p>Matched on the <b>exact</b> rate a ripe patch nearby pays — see
	 * {@link #looksLikeAHarvestOf}. Deliberately strict: requiring an exact match is what stops
	 * planting or check-health experience from being mistaken for a harvest.
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

			if (!looksLikeAHarvestOf(gained, snapshot.getProduce()))
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
	 * Whether an experience drop is this crop being harvested and not something else.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * This asked one question — is that a single pick? — and a single pick is not a shape every
	 * crop has. A flower patch pays for the whole patch in one drop: limpwurt's is 142, being its
	 * 120 to harvest plus the 21.5 it defers from planting, against the 1.004 outfit. The per-pick
	 * test allows 120.48 give or take 6.02, so <b>the only drop a limpwurt harvest ever makes was
	 * rejected</b>. Across all four of the 2026-08-13 client logs there are forty "opening ... from
	 * experience alone" lines and not one of them is a limpwurt, while the CSV holds eighty-one
	 * limpwurt patches picked clean, eighty of which record 0.0 against a predicted 120.5.
	 *
	 * <p>That mattered even though limpwurt roots do reach the inventory, because the award lands
	 * in the same tick as the roots but <i>ahead</i> of them — so at the moment it arrives there is
	 * no open record to take it and this is the only thing that could have opened one. That is not
	 * a guess about packet order: it is why "Opening Watermelon harvest ... from experience alone"
	 * is logged for allotments whose melons are plainly in the inventory a moment later.
	 *
	 * <p>So a crop that pays once for the patch is matched against that whole-patch award as well,
	 * with the planting figure {@link CropYieldModel#clearedPatchXp} says is bundled into it. Only
	 * for those crops, and the caller's guards are untouched — the patch still has to be ripe,
	 * within {@link #MAX_ATTRIBUTION_DISTANCE}, and not already being recorded — so this widens
	 * what one drop can match by exactly one value, for one flower patch, at the one moment a
	 * flower patch is being picked.
	 */
	private boolean looksLikeAHarvestOf(double gained, Produce produce)
	{
		CropXp rates = CropXp.forProduce(produce);
		if (rates == null)
		{
			return false;
		}

		// Against the boosted rate, not the published one: the Farmer's outfit adds up to
		// 2.5% to everything, so the unboosted figure is not what actually arrives.
		FarmingBonuses current = bonuses.current();
		double award = current.applyOutfit(rates.getHarvestXp());
		if (HarvestRecord.isOnePick(gained, award))
		{
			return true;
		}

		Seed seed = Seed.forProduce(produce);
		if (CropYieldModel.paysHarvestPerItem(seed))
		{
			return false;
		}
		return HarvestRecord.isOnePick(gained,
			current.applyOutfit(rates.getHarvestXp() + CropYieldModel.clearedPatchXp(seed)));
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
		HarvestRecord record = open.get(patch.getKey());
		if (record == null)
		{
			return;
		}

		// Still harvestable covers picking part of a stock: a palm at six fruit and the same
		// palm at three are both harvestable, and both are the same harvest.
		//
		// ...and a palm at ZERO fruit is not, which this missed. The paragraph above says a
		// harvest ends when there is nothing left to pick and calls out bushes, fruit trees and
		// cacti by name; the test below it only ever exercised the bush shape, where picking the
		// last berry moves the patch HARVESTABLE -> GROWING and the produce/state pair is enough.
		//
		// A fruit tree and a cactus do not do that. PatchRules maps a picked-clean palm to
		// (PALM, HARVESTABLE, stage 0) at varbit 206, and a bare cactus to (CACTUS, HARVESTABLE,
		// stage 0) at 15 — the same produce, the same state, an empty patch. So the record never
		// closed on a state change at all and survived to the idle timeout, absorbing every
		// experience drop that arrived in the intervening minute.
		//
		// Found in the harvest log rather than by reading: 25 of 25 cactus rows and 22 of 22 palm
		// rows carry completed=false, with totals of 12443, 3712, 1092 and 1011 against ~250
		// predictions. One palm record swallowed a calquat check-health 76 seconds after the last
		// coconut; a cactus record swallowed a yew check from Lumbridge.
		//
		// The stock is the missing half, and the decode already carries it: for a crop that
		// regrows, the harvest stage IS the count still on the plant — see GrowthTimer, which
		// reads the same number as livesRemaining. For everything else the stage counts remaining
		// lives and never reaches zero while harvestable, so the test is a no-op there.
		boolean anythingLeft = current.getProduce() == null
			|| current.getProduce().getRegrowTickrate() <= 0
			|| current.getStage() > 0;
		boolean stillPickable = current.getProduce() == record.getProduce()
			&& current.getCropState() == CropState.HARVESTABLE
			&& anythingLeft;
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
	 * same few ticks. When it happens the nearer is taken, and where they are genuinely the same
	 * distance away the one with no record open wins: a patch can only be picked clean once, so
	 * the second of a pair is the one still owed a record. Losing the tie costs a data point and
	 * cannot corrupt one.
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
			boolean nearer = distance < bestDistance;
			// Equal distances used to be settled by iteration order, which is how a north and a
			// south allotment - identical in every signal this has - funnelled into one record.
			boolean tiedAndUnclaimed = distance == bestDistance && best != null
				&& open.containsKey(best.getKey()) && !open.containsKey(patch.getKey());
			if (nearer || tiedAndUnclaimed)
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
	 * <h2>A real coordinate first, and that is the fix</h2>
	 *
	 * This used to answer <b>zero for every patch in the player's region</b> and only fall back to
	 * coordinates when the regions differed. The reasoning was that a region is exact where a
	 * position may be a guess — true when it was written, and it stopped being true once
	 * {@code MeasuredPatchLocations} shipped a tile for every patch. What it cost in the meantime
	 * is that <i>every allotment location has a north and a south patch in one region</i>: plant
	 * the same crop in both and both scored zero, {@link #findPatchHolding} took the first
	 * minimum, and all the produce from both patches landed in one record while the second patch
	 * never opened one at all.
	 *
	 * <p>The signature in the log is unmistakable and it is the whole of the remaining "luck"
	 * after the compost fix: {@code Watermelon Catherby South actual 77 predicted 22.93},
	 * {@code Strawberry Kourend SW actual 70}, {@code Snape grass Farming Guild N actual 72}.
	 * Seventy-seven melons out of six lives is a seven-sigma patch; two patches booked as one is
	 * an ordinary Tuesday. The experience on those rows matches the item count exactly, so the
	 * items were real — only the attribution was wrong.
	 *
	 * <p>So a patch whose tile is <i>known</i> — learned from watching its object spawn, or from
	 * the shipped table — is measured. The region has not been thrown away: it still guarantees
	 * that a patch you are standing among stays inside {@link #MAX_ATTRIBUTION_DISTANCE}, which is
	 * what the old rule was really buying. Some farming regions span more than one map square and
	 * the plugin mirrors only the canonical id, and the hespori's cave is instanced so a
	 * coordinate read there is meaningless — in both cases the region is the trustworthy signal
	 * and the coordinate is not.
	 */
	private int distanceTo(WorldPoint player, FarmPatch patch)
	{
		boolean sameRegion = player.getRegionID() == patch.getRegion().getRegionId();
		int measured = locations.isExact(patch)
			? separation(player, locations.getLocation(patch))
			: Integer.MAX_VALUE;

		if (sameRegion)
		{
			// A region match is a fact about the varbits and cannot be wrong; a coordinate can
			// be. The hespori's cave is instanced, so a position read there means nothing on the
			// world map, and a patch you are provably standing in must never be measured out of
			// range by one. So the region caps the distance at the attribution limit rather than
			// replacing it: within the limit the real separation still orders the patches, which
			// is the whole point, and beyond it the region keeps the patch in play exactly as it
			// used to.
			return Math.min(measured, MAX_ATTRIBUTION_DISTANCE);
		}

		return measured != Integer.MAX_VALUE
			? measured
			: separation(player, locations.getLocation(patch));
	}

	/** Tiles between two points, or unreachable when they are not on the same floor. */
	private static int separation(WorldPoint player, @Nullable WorldPoint location)
	{
		return location == null || location.getPlane() != player.getPlane()
			? Integer.MAX_VALUE
			: player.distanceTo(location);
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

	/**
	 * Files one finished patch.
	 *
	 * <h2>Recording is not optional; the commentary is</h2>
	 *
	 * Everything down to {@link #appendCsv} runs whenever the plugin is enabled. These are the
	 * player's own numbers — what the Stats tab is made of — and they accumulate over months, so
	 * a setting that switched them off would not cost you a feature you could switch back on. It
	 * would cost you the history you did not know you were not keeping.
	 *
	 * <p>That is what {@code logHarvests} used to do. It reads as a developer's CSV toggle and
	 * was gating every observation behind it, so turning it off silently emptied the whole tab.
	 * It now governs only the client-log chatter below, which is genuinely a diagnostic: a line
	 * per patch and two warnings about the capture itself.
	 */
	private void write(HarvestRecord record)
	{
		if (record.getItemsHarvested() <= 0)
		{
			return;
		}

		if (!isAHarvest(record))
		{
			return;
		}

		// Before anything is scored, because every figure below is derived from the tier.
		applyCompostWeMissed(record);

		// The rolled-up totals are the part that outlives this session and can be shown back
		// to the player; the CSV is the raw trail behind them, and since the runs and the level
		// curve are reconstructed from it, it is a store rather than a debug artifact.
		stats.record(record);
		// Folded in here as well as written, so the run clustering stays current without the
		// file being read again - and so it does not depend on the write having succeeded.
		history.record(record);
		appendCsv(record);

		if (!config.logHarvests())
		{
			return;
		}

		double predicted = record.getPredictedYield();
		warnIfCompostWasMissed(record, predicted);
		log.info("Harvest: {} x{} from {} — predicted {}, {} at level {}{}{}",
			record.getProduce().getName(),
			record.getItemsHarvested(),
			record.getPatch().getDisplayName(),
			predicted > 0 ? String.format("%.1f", predicted) : "n/a",
			record.getCompost().getDisplayName().toLowerCase(),
			record.getFarmingLevel(),
			describeBonuses(record),
			record.isCompleted() ? "" : " (left standing, not picked clean)");

		double predictedXp = record.getPredictedXp();
		// Skipped when the count came from the experience, because then the prediction is that
		// same experience divided and multiplied by the same constant. It would always agree,
		// and an assertion that cannot fail is worse than no assertion.
		if (!record.isInferredFromXp() && predictedXp > 0
			&& Math.abs(predictedXp - record.getXpGained()) > xpMismatchTolerance(predictedXp))
		{
			// Worth shouting about: it means the per-pick figure in CropXp is wrong, which is
			// a data bug rather than a modelling one.
			log.warn("Harvest XP mismatch for {}: predicted {} for {} picks, saw {}",
				record.getProduce().getName(), predictedXp, record.getItemsHarvested(),
				record.getXpGained());
		}
	}

	/**
	 * Whether what has been assembled here is a harvest at all.
	 *
	 * <h2>No farming experience means nothing was picked</h2>
	 *
	 * Picking pays, every time, for every crop that has a harvest award — so a record holding
	 * hundreds of items and <b>zero</b> experience did not come from a patch. It came from the
	 * inventory: {@link #IDLE_TICKS_BEFORE_ABANDON} is a hundred ticks, a full minute, and a
	 * record left open while its patch stands ripe will credit whatever arrives in that minute
	 * to the patch. A bank withdrawal at the Farming Guild is the shape that was found —
	 * <b>three limpwurt records of 908, 266 and 238 roots against a predicted 6.91, all with
	 * {@code xp:0.0}</b>, and between them 1,412 of the 1,965 items the panel was reporting as
	 * "left standing on patches you should go back to". The one actionable line on the tab was
	 * telling the player to go and re-pick their own bank.
	 *
	 * <p>Only where the crop <i>has</i> an award to pay, so a crop the plugin has no experience
	 * figure for is not silently thrown away.
	 *
	 * <h2>And a farmed tree is chopped, not harvested</h2>
	 *
	 * Teak, maple, yew, magic and camphor rows in the store carry farming xp of zero and one to
	 * thirty-eight items: they are <i>woodcutting</i> logs from felling the tree the patch grew.
	 * {@link CropYieldModel#hasMeaningfulYield} has always said a tree has no per-patch yield
	 * worth quoting; nothing asked it before writing, so those logs were scored against a
	 * predicted yield of 1 and contributed +144 to the account's "items over expectation".
	 */
	private boolean isAHarvest(HarvestRecord record)
	{
		Seed seed = Seed.forProduce(record.getProduce());
		if (seed != null && !CropYieldModel.hasMeaningfulYield(seed))
		{
			log.info("Not recording {} x{} at {}: a farmed tree is chopped for logs rather than "
					+ "harvested, so there is no patch yield here to score",
				record.getProduce().getName(), record.getItemsHarvested(),
				record.getPatch().getDisplayName());
			return false;
		}

		CropXp rates = CropXp.forProduce(record.getProduce());
		if (rates != null && rates.getHarvestXp() > 0 && record.getXpGained() <= 0)
		{
			log.info("Not recording {} x{} at {}: no farming experience arrived, and picking "
					+ "always pays {} an item - so these items were not picked here",
				record.getProduce().getName(), record.getItemsHarvested(),
				record.getPatch().getDisplayName(), rates.getHarvestXp());
			return false;
		}

		return true;
	}

	/**
	 * Tags a harvest with a compost tier the capture never saw, from whoever does know.
	 *
	 * <p>The record takes its tier from the patch snapshot when the first item lands, and the
	 * snapshot only holds a bucket this plugin watched go in. A patch composted in a previous
	 * session and ripe at login has nothing there — which is the ordinary shape of a herb run,
	 * and why {@link #warnIfCompostWasMissed} fired eighty times in the surviving client logs on
	 * exactly the crops whose "luck" then read 99th percentile.
	 *
	 * <p>Warning about it was the wrong response to knowing it. {@link PatchStateStore#knownCompost}
	 * can answer from Time Tracking, which has been recording the bucket for years and keeps its
	 * key until the patch is replanted — so the fact is available at the one moment the record
	 * needs it, and the prediction can be made against the patch that actually existed instead of
	 * against a three-life patch nobody planted.
	 *
	 * <p>Only ever NONE to a real tier, and only for crops where compost changes the yield: on
	 * everything else the tier is a label rather than an input, and re-labelling a finished
	 * harvest from another plugin's cache would buy nothing and could be wrong.
	 */
	private void applyCompostWeMissed(HarvestRecord record)
	{
		if (record.getCompost() != CompostTier.NONE)
		{
			return;
		}

		Seed seed = Seed.forProduce(record.getProduce());
		if (seed == null || !CropYieldModel.respondsToCompost(seed))
		{
			return;
		}

		CompostTier known = patches.knownCompost(record.getPatch());
		if (known == null || known == CompostTier.NONE)
		{
			return;
		}

		record.adoptCompost(known);
		log.info("Tagged the {} harvest at {} as {}: we never saw the bucket go in, but Time "
				+ "Tracking has it recorded, and the tier is what the yield is predicted from",
			record.getProduce().getName(), record.getPatch().getDisplayName(),
			known.getDisplayName().toLowerCase());
	}

	/**
	 * How far a harvest's experience may sit from its prediction before that is worth shouting.
	 *
	 * <p>Half a point used to be the bar and nothing could clear it, which made the warning
	 * useless in both directions: it fired on every harvest, so a genuinely wrong constant would
	 * have been indistinguishable from the noise.
	 *
	 * <p>{@code StatChanged} reports a whole-number total, so an observed drop is the difference
	 * of two floors and a fractional rate surfaces as gains alternating either side. A record
	 * that starts and ends part-way through a fraction inherits up to a point of that at each
	 * end — which is not theory: eight of the nine limpwurt patches logged on 2026-08-13 paid
	 * 142 and the ninth paid 141, because the ultracompost award immediately before it took the
	 * fraction and paid 37 instead of 36. Hence two points rather than one.
	 *
	 * <p>The proportional half is for the Farmer's outfit, which is modelled at 2.5% for a full
	 * set rather than measured, so any error in it scales with the harvest. Half a percent still
	 * catches a per-pick constant that is wrong by a whole pick, which is the data bug this is
	 * watching for.
	 */
	private static double xpMismatchTolerance(double predictedXp)
	{
		return Math.max(2.0, predictedXp * 0.005);
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
	 *
	 * <p>Now the <b>last</b> resort rather than the only response. {@link #applyCompostWeMissed}
	 * runs first and takes the tier from Time Tracking where it has one, which clears the record's
	 * NONE and so this with it. What is left here is the genuinely unknowable case — nobody saw
	 * the bucket, including the plugin that has been watching for years — and there the yield is
	 * still the evidence that one went in, which is worth saying out loud.
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
	 * <p>Written in <b>the file's own column order</b>, learned from its header the first time
	 * this session appends to it. That is what lets a file created by an older version keep
	 * being appended to rather than needing a migration: its header still describes it, and
	 * {@link HarvestCsv} lays each row out to match.
	 *
	 * <p>Failures are logged once and otherwise ignored. Being unable to write a validation
	 * file is not a reason to interfere with anyone's game.
	 */
	private void appendCsv(HarvestRecord record)
	{
		File dir = HarvestFiles.directory();
		File file = HarvestFiles.forProfile(configManager);
		// Under the shared lock because the history's initial read - and the trim it can
		// trigger, a whole-file rewrite - runs on the executor. See HarvestFiles.FILE_LOCK.
		synchronized (HarvestFiles.FILE_LOCK)
		{
			try
			{
				if (!dir.exists() && !dir.mkdirs())
				{
					return;
				}

				boolean needsHeader = !file.exists() || file.length() == 0;
				if (columns == null)
				{
					columns = needsHeader ? HarvestCsv.COLUMNS : HarvestCsv.columnsOf(file);
				}

				try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
					PrintWriter out = new PrintWriter(writer))
				{
					if (needsHeader)
					{
						out.println(String.join(",", columns));
					}
					out.println(HarvestCsv.line(columns,
						HarvestCsv.valuesOf(record, Instant.now().getEpochSecond())));
				}
			}
			catch (IOException e)
			{
				log.warn("Could not write {}", file, e);
			}
		}
	}

	/** Everything still being picked, for tests. */
	Map<String, HarvestRecord> getOpenHarvests()
	{
		return new LinkedHashMap<>(open);
	}
}
