package com.dooglemaps.state;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Produce;
import com.dooglemaps.data.Seed;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Which crop Guildmaster Jane has asked for, and whether one is waiting to be handed in.
 *
 * <p>The reward — seed packs, and the Farming Guild reputation that unlocks the tiers — makes a
 * contract the single highest-value thing in a run, and it wants one specific patch. So the plugin
 * has to know about it for two separate reasons: to tell you to plant it, and to stop itself
 * filling the very patch it needs with something else.
 *
 * <h2>Time Tracking already watches for it, and that is most of the answer</h2>
 *
 * RuneLite's {@code FarmingContractManager} follows Jane's dialogue and stores the result in
 * ordinary config — group {@code timetracking}, key {@code contract}, the harvested item's id as a
 * string. A config read is a config read, so that needs no dependency on the plugin itself, and
 * specifically none of the injector trouble the bank filter ran into: {@code
 * FarmingContractManager} lives inside Time Tracking's own injector and must never be asked for
 * directly.
 *
 * <h2>What that key does not tell you, which is the part worth reading carefully</h2>
 *
 * It was expected to hold the contract until it was handed in, which would make this two states.
 * It does not. {@code TimeTrackingPlugin.onChatMessage} clears it on <b>"You've completed a Farming
 * Guild Contract. You should return to Guildmaster Jane."</b> — which is the message the game sends
 * when the <i>crop finishes growing</i>, not when you hand it in. So an absent key means one of two
 * opposite things:
 *
 * <ul>
 *   <li>there is no contract, or the last one is long since dealt with; or
 *   <li>a contract has just completed and the reward is sitting unclaimed — which is precisely the
 *       moment the guide most needs to say something.
 * </ul>
 *
 * <p>That is why this class captures as well as reads, rather than being the pure config read the
 * plan assumed. {@code ContractCapture} watches the same three lines Time Tracking does and records
 * them under this plugin's own keys, and the two sources are consulted in the order that makes each
 * one authoritative about the thing it actually knows:
 *
 * <ul>
 *   <li><b>Which crop is assigned</b> — Time Tracking's key first, ours only when it is silent.
 *       Theirs has been maintained for years and is written the moment the dialogue appears; ours
 *       exists so that switching Time Tracking off degrades to a second opinion rather than to
 *       nothing.
 *   <li><b>Whether it is grown and unclaimed</b> — ours alone. There is no config route to this at
 *       all, because the event that would tell you is the same event that wipes theirs.
 * </ul>
 *
 * <h2>It can still be wrong, and says so rather than guessing</h2>
 *
 * A contract that finishes while logged out sends no message, so nothing captures it — but nothing
 * clears Time Tracking's key either, so it stays holding the crop and completion is derivable from
 * the patch itself. The two failure modes are complementary, which is why both are kept. Where
 * neither can answer, this reports "no contract", which is the direction that stays quiet rather
 * than the direction that insists on planting something.
 */
@Slf4j
@Singleton
public class ContractState
{
	/** Time Tracking's config group, which is stable and public in the client. */
	private static final String TIME_TRACKING_GROUP = "timetracking";

	/** Its key for the assigned contract, holding the harvested item's id as a string. */
	private static final String TIME_TRACKING_CONTRACT_KEY = "contract";

	/**
	 * How RuneLite records whether a plugin is switched on: group {@code runelite}, key the
	 * plugin class's simple name in lower case. Absent means on, since Time Tracking is enabled
	 * by default.
	 */
	private static final String RUNELITE_GROUP = "runelite";
	private static final String TIME_TRACKING_PLUGIN_KEY = "timetrackingplugin";

	/** Our own fallback capture of the assignment, used only when Time Tracking is silent. */
	private static final String CAPTURED_CONTRACT_KEY = "contractCaptured";

	/** Our own record that a contract has grown and the reward has not been collected. */
	private static final String AWAITING_HAND_IN_KEY = "contractAwaitingHandIn";

	/**
	 * The crop of the last contract we watched being handed in, while Time Tracking's key is
	 * still naming it. See {@link #getContract()} for what it is for.
	 */
	private static final String SETTLED_KEY = "contractSettled";

	/** The Farming Guild, which is the only place a contract can be grown. */
	public static final int FARMING_GUILD_REGION = 4922;

	/** Guildmaster Jane, who assigns them and takes them back. */
	public static final int GUILDMASTER_JANE = net.runelite.api.gameval.NpcID.FARMING_GUILD_MASTER;

	/**
	 * Every id Jane wears — she is one person and three NPC ids, and which one you are looking
	 * at depends on where you are looking. {@code FARMING_GUILD_MASTER} (the constant above) is
	 * what her <b>dialogue chathead</b> reports; the NPC actually standing in the guild is one
	 * of the {@code _1OP}/{@code _2OP} variants, which is how the game varies her menu options.
	 * Conflating the two is exactly how the hand-in step outlined nobody: the world NPC was
	 * compared against the chathead's id and never matched. Anything matching Jane in the
	 * <i>scene or a menu</i> wants this set; only the chathead reader wants the single id.
	 */
	public static final java.util.Set<Integer> JANE_NPC_IDS = java.util.Set.of(
		net.runelite.api.gameval.NpcID.FARMING_GUILD_MASTER,
		net.runelite.api.gameval.NpcID.FARMING_GUILD_MASTER_1OP,
		net.runelite.api.gameval.NpcID.FARMING_GUILD_MASTER_2OP);

	private final ConfigManager configManager;

	private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

	@Inject
	ContractState(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	public void addChangeListener(Runnable listener)
	{
		changeListeners.add(listener);
	}

	public void removeChangeListener(Runnable listener)
	{
		changeListeners.remove(listener);
	}

	/**
	 * The crop Jane is currently asking for, or null when nothing is outstanding.
	 *
	 * <p>Deliberately does <b>not</b> include one that has grown and is waiting to be handed in:
	 * that crop no longer wants a patch, and treating it as assigned would have the run reserving
	 * the guild's herb patch for something already sitting in your pack. See
	 * {@link #getAwaitingHandIn()}, which is the other half of the question.
	 *
	 * <h2>Nor one that has already been handed back</h2>
	 *
	 * Time Tracking's key is cleared by the <i>completion message</i>, and a contract that ripened
	 * while you were logged out never sends one — so for that whole cycle its key goes on naming
	 * the crop, through the hand-in and afterwards. Read literally that says "a cadantine contract
	 * is assigned" to a player who has just given Jane the cadantine, which suppressed the one step
	 * that could move things on: {@code GuideTracker} offers TAKE_CONTRACT only while nothing is
	 * assigned, so it had never fired in any session on record.
	 *
	 * <p>{@link #recordHandedIn()} therefore writes down which crop it settled, and that record
	 * outranks Time Tracking's key for exactly as long as the key names the same crop. The moment
	 * it names a different one — the next assignment — the marker is stale and says nothing; see
	 * {@link #getSettledContract()}.
	 */
	@Nullable
	public Produce getContract()
	{
		Produce stored = fromTimeTracking();
		if (stored != null && stored != settledMarker())
		{
			return stored;
		}
		// Our own capture, either because Time Tracking is silent or because its answer is the
		// contract we have already handed back. Both are cleared by recordHandedIn, and a fresh
		// assignment seen by ContractCapture clears the marker, so the two cannot disagree here.
		return fromOurCapture();
	}

	/**
	 * The crop of the last contract handed in, while Jane has yet to hand out its replacement.
	 *
	 * <p>Null once Time Tracking names a <b>different</b> crop, because that is the new assignment
	 * arriving and the window closing. Its key merely going quiet is not: an online hand-in leaves
	 * it empty, and the take-a-new-one errand is owed just the same.
	 *
	 * <p>That window is the one moment the guide has to keep the guild stop alive with no contract
	 * to point at — see {@code GuideTracker.contractBusinessOutstanding}. Without it the run ended
	 * between the hand-in and the next contract, which is the middle of the chain.
	 */
	@Nullable
	public Produce getSettledContract()
	{
		Produce settled = settledMarker();
		if (settled == null)
		{
			return null;
		}
		Produce assigned = fromTimeTracking();
		return assigned == null || assigned == settled ? settled : null;
	}

	/**
	 * The marker as stored, without asking whether it still applies.
	 *
	 * <p>A plain read: {@link #getContract()} is called per patch inside loops over patches, so
	 * nothing here writes config or announces anything. Clearing a stale marker is
	 * {@link #reconcileAwaitingHandIn} and {@link #recordAssigned}'s business, at the two moments
	 * a new assignment can arrive.
	 */
	@Nullable
	private Produce settledMarker()
	{
		return produceFromName(read(DoogleMapsConfig.GROUP, SETTLED_KEY));
	}

	/**
	 * The crop that has finished growing and has not been handed back, or null.
	 *
	 * <p>Only ever set by our own capture of the completion message; see the class note on why
	 * there is no config route to this.
	 */
	@Nullable
	public Produce getAwaitingHandIn()
	{
		return produceFromName(read(DoogleMapsConfig.GROUP, AWAITING_HAND_IN_KEY));
	}

	/** Whether anything about contracts is worth saying right now. */
	public boolean hasContract()
	{
		return getContract() != null;
	}

	/** The patch type an assigned contract wants, or null when there is none. */
	@Nullable
	public PatchImplementation getContractType()
	{
		Produce contract = getContract();
		return contract == null ? null : contract.getPatchImplementation();
	}

	/**
	 * The patch type the contract has a hold on right now, assigned or merely unclaimed.
	 *
	 * <p>{@link #getContractType()} goes null the moment the crop finishes growing, because that is
	 * when the completion message clears both keys. For anything asking <i>"is this patch spoken
	 * for"</i> that is the wrong answer at the worst moment: the crop is standing in the ground
	 * waiting to be handed in, and the patch is more spoken for than it has ever been.
	 */
	@Nullable
	public PatchImplementation getActiveContractType()
	{
		PatchImplementation assigned = getContractType();
		if (assigned != null)
		{
			return assigned;
		}

		Produce awaiting = getAwaitingHandIn();
		return awaiting == null ? null : awaiting.getPatchImplementation();
	}

	/**
	 * The seed that grows the assigned contract, or null.
	 *
	 * <p>Null for a contract whose crop has no plantable seed — nothing in the current pool is
	 * like that, but the lookup is generated from two independently sourced tables and a null here
	 * is a great deal better than an exception in the middle of a run.
	 */
	@Nullable
	public Seed getContractSeed()
	{
		return Seed.forProduce(getContract());
	}

	/**
	 * Whether this patch is one an assigned contract has claimed.
	 *
	 * <p>Every Farming Guild patch of the contract's type, which is the same set RuneLite's own
	 * {@code handleContractState} scans when deciding whether a contract is done. For all but one
	 * type that is a single patch; the guild has two allotments, and both move together. Moving
	 * both is the stateless answer, and the alternative — picking one of them — would have a patch
	 * changing groups as its state changed, which is exactly what {@link PlantingGroups} must not
	 * do while a run is being planned around it.
	 */
	public boolean claims(@Nullable FarmPatch patch)
	{
		if (patch == null || patch.getRegion().getRegionId() != FARMING_GUILD_REGION)
		{
			return false;
		}
		return patch.getImplementation() == getContractType();
	}

	/**
	 * Whether the contract still has business with this patch, including one already grown.
	 *
	 * <h2>Why {@link #claims} is the wrong question for ordering</h2>
	 *
	 * {@code claims} is about the <b>assigned</b> contract, and that is right for grouping: a
	 * finished contract wants no seed, so it must stop reserving the patch for one — otherwise the
	 * run would bank a cactus seed for a cactus that is standing there done.
	 *
	 * <p>Ordering needs the broader question, and using the narrow one showed. The completion
	 * message clears both Time Tracking's key and ours, so the moment a contract finishes
	 * {@code claims} goes false — and the guild's contract patch quietly lost the priority that
	 * puts it first at that stop. Reported from play as being routed through the guild's herbs
	 * before the finished cactus, which is backwards: the cactus is what the trip is for, picking
	 * it is what unlocks the hand-in, and the hand-in is what unlocks the next contract.
	 *
	 * <p>So this stays true from assignment until the reward is collected, which is the span over
	 * which the patch is worth visiting first.
	 */
	public boolean claimsUntilHandedIn(@Nullable FarmPatch patch)
	{
		if (claims(patch))
		{
			return true;
		}
		if (patch == null || patch.getRegion().getRegionId() != FARMING_GUILD_REGION)
		{
			return false;
		}

		Produce awaiting = getAwaitingHandIn();
		return awaiting != null && patch.getImplementation() == awaiting.getPatchImplementation();
	}

	/**
	 * Whether Time Tracking is switched on.
	 *
	 * <p>Worth asking because when it is off the contract key simply stops updating, and the
	 * failure is silent: it looks exactly like "no contract". That is the safe direction — the
	 * plugin stays quiet rather than insisting on a crop — but it should be <i>said</i> rather
	 * than left to be inferred, the same way the bank filter names the reason it is off.
	 */
	public boolean isTimeTrackingEnabled()
	{
		String value = configManager.getConfiguration(RUNELITE_GROUP, TIME_TRACKING_PLUGIN_KEY);
		// Absent means on: RuneLite only writes this key once the plugin has been toggled, and
		// Time Tracking is enabled by default.
		return value == null || Boolean.parseBoolean(value);
	}

	/** Records a contract seen being assigned, for when Time Tracking is not watching. */
	public void recordAssigned(Produce contract)
	{
		if (contract == null)
		{
			return;
		}
		write(CAPTURED_CONTRACT_KEY, contract.name());
		// A new contract supersedes anything waiting: Jane does not hand one out until the last
		// has been settled, so a stale hand-in flag here is a record of something that has
		// already happened.
		clear(AWAITING_HAND_IN_KEY);
		// ...and it closes the hand-in window with it. The marker exists only to say "the crop
		// Time Tracking is still naming is one you have already given back"; a fresh assignment
		// is the answer to that, whichever source saw it.
		clear(SETTLED_KEY);
		// At INFO, like every other write in this class. These four methods are the only things
		// that change what the whole contract chain believes, they are driven by chat lines that
		// are gone the moment they scroll past, and the state they leave behind survives restarts
		// in config - so a wrong answer days later has no trail at all at DEBUG. Reported from
		// play: an awaiting-hand-in record naming a crop from a previous contract, with nothing in
		// client.log to say when or why it was written.
		log.info("Farming contract assigned: {} - the seed for it is now the run's to fetch, and "
			+ "any awaiting or settled record from the last one is superseded", contract.getName());
		fireChanged();
	}

	/**
	 * Records that a contract has grown and is waiting to be handed in.
	 *
	 * <p>Takes the crop from whatever is currently assigned, because the completion message names
	 * no crop. If nothing is assigned there is nothing useful to store — that happens when the
	 * plugin was installed mid-contract, and the honest answer is to stay quiet.
	 */
	public void recordCompleted()
	{
		Produce contract = getContract();
		if (contract == null)
		{
			log.info("A farming contract completed, but nothing was recorded as assigned - "
				+ "nothing is written down, so the hand-in will be read off the ground");
			return;
		}
		write(AWAITING_HAND_IN_KEY, contract.name());
		// Time Tracking clears its own key on this same message. Clearing ours keeps the two
		// agreeing about what is still growing, which is what "assigned" now means.
		clear(CAPTURED_CONTRACT_KEY);
		// A completion is proof a contract was live, so nothing can still be in the window
		// between a hand-in and the next assignment.
		clear(SETTLED_KEY);
		log.info("Farming contract completed: {} is waiting to be handed in, and the assignment "
			+ "has been cleared with it", contract.getName());
		fireChanged();
	}

	/**
	 * Records the reward being collected, which ends one cycle and opens the window before the
	 * next.
	 *
	 * <p>The crop is written down rather than merely forgotten, because Time Tracking's key can
	 * still be naming it — see {@link #getContract()}. Taken from whatever we believe was
	 * outstanding: the awaiting record for the ordinary case, and Time Tracking's own key for a
	 * contract that ripened while logged out, where no completion ever cleared it.
	 */
	public void recordHandedIn()
	{
		Produce settled = getAwaitingHandIn();
		if (settled == null)
		{
			settled = fromTimeTracking();
		}

		clear(AWAITING_HAND_IN_KEY);
		clear(CAPTURED_CONTRACT_KEY);
		Produce assigned = fromTimeTracking();
		if (settled != null && (assigned == null || assigned == settled))
		{
			// Not written where Time Tracking already names a different crop: the next contract
			// is out, nothing is owed, and a marker for a closed window is only there to go stale.
			write(SETTLED_KEY, settled.name());
		}
		log.info("Farming contract handed in: {} - the awaiting record is cleared and the window "
				+ "before the next contract is {}",
			settled == null ? "crop unknown" : settled.getName(),
			settled != null && (assigned == null || assigned == settled) ? "open" : "closed");
		fireChanged();
	}

	/**
	 * Squares the awaiting-hand-in record against a fresh assignment from Time Tracking.
	 *
	 * <p>The awaiting flag is cleared by exactly two dialogue captures — the REWARDED line and
	 * the next ASSIGNED line — and both are sampled once a tick, so spam-clicking through Jane
	 * can skip past both between samples. The flag then deadlocked: the hand-in step pointed
	 * at produce that no longer existed, the take-a-contract step was suppressed because
	 * something still read as awaiting, and the events that could clear it were precisely the
	 * ones being missed. Circular, and it survives restarts because it is config.
	 *
	 * <p>Time Tracking parses the same dialogue on its own schedule, and Jane does not assign
	 * while a reward is uncollected — so a Time Tracking assignment that <b>differs</b> from
	 * what we think is awaiting proves the old contract was settled, whether or not we saw it
	 * happen. The same reasoning {@link #recordAssigned} already applies to our own capture,
	 * pointed at the other source.
	 */
	public void reconcileAwaitingHandIn()
	{
		reconcileAwaitingHandIn(GroundEvidence.UNREAD);
	}

	/**
	 * As {@link #reconcileAwaitingHandIn()}, told what the contract's own patch looks like.
	 *
	 * @param evidence what is standing in the contract's ground, which only the guide can read
	 */
	public void reconcileAwaitingHandIn(GroundEvidence evidence)
	{
		Produce awaiting = getAwaitingHandIn();
		Produce assigned = fromTimeTracking();

		// A marker naming a crop Time Tracking no longer names is spent, whatever else is
		// decided below: the next contract has been handed out, and the window it stood for is
		// closed. Read-only paths ignore it already (see getSettledContract); this is where it
		// stops taking up room in config.
		Produce settled = settledMarker();
		if (settled != null && assigned != null && assigned != settled)
		{
			log.info("The just-handed-in marker still named {} while Time Tracking has {} "
				+ "assigned, so the window it stood for is closed - clearing it",
				settled.getName(), assigned.getName());
			clear(SETTLED_KEY);
		}

		if (awaiting == null)
		{
			return;
		}
		if (assigned != null && assigned != awaiting)
		{
			log.info("Time Tracking sees {} assigned while {} still read as awaiting hand-in - "
				+ "the hand-in happened unseen, so the stale record is being settled",
				assigned.getName(), awaiting.getName());
			recordHandedIn();
			return;
		}

		// The same crop still ASSIGNED there was read as the other contradiction, and on its own
		// it is not one. The reasoning was that the completion message clears Time Tracking's key
		// the moment it fires, so a key still holding the crop means no completion ever came — and
		// that is precisely false for the case this class's own note is about: a contract that
		// finishes growing while you are logged out sends no message at all, so the key is never
		// cleared AND the record is legitimate. Clearing on the same-crop case alone deleted a
		// real hand-in eight times in one session, which is what stranded the run at the guild
		// with the reward sitting uncollected.
		//
		// So it now takes evidence from the ground, which is the only place the difference shows:
		// the crop still standing in the contract's patch <b>unfinished</b> — mid-growth, or the
		// health-checked bush that was checked before the contract and can never satisfy it —
		// cannot have completed, and that is the corruption the old patch-evidence fallback
		// wrote. A patch that is empty, or holding the finished crop, says nothing against the
		// record and keeps it.
		//
		// Only judged while Time Tracking is actually on: switched off, its key merely goes
		// stale, and a stale key still holding the crop is exactly what a real completion looks
		// like from here.
		if (assigned == awaiting && evidence == GroundEvidence.CROP_STANDS_UNFINISHED
			&& isTimeTrackingEnabled())
		{
			log.info("Time Tracking still has {} assigned and it is standing unfinished in the "
				+ "guild, so no completion ever fired - clearing the awaiting-hand-in record as "
				+ "corruption", awaiting.getName());
			clear(AWAITING_HAND_IN_KEY);
			fireChanged();
		}
	}

	/**
	 * What the contract's own patch says about a record waiting to be handed in.
	 *
	 * <p>Read by the guide rather than here: this class is a config reader and the answer wants a
	 * projection of the patch's varbit, which is two packages away. See
	 * {@code GuideTracker.contractGroundEvidence}.
	 */
	public enum GroundEvidence
	{
		/** Nobody has looked, or what is standing there decides nothing either way. */
		UNREAD,

		/**
		 * The crop is in the contract's patch and has not finished for it — still growing, or a
		 * health-checked crop whose one check happened before the contract was taken. Either way
		 * no completion can have fired, so a record saying one did is corruption.
		 */
		CROP_STANDS_UNFINISHED
	}

	/** Announces the contract once at start-up, so a silent one is diagnosable after the fact. */
	public void logState()
	{
		Produce contract = getContract();
		Produce awaiting = getAwaitingHandIn();
		if (!isTimeTrackingEnabled())
		{
			log.info("Time Tracking is switched off, so the farming contract can only be read from "
				+ "what this plugin has seen itself. Contract: {}; awaiting hand-in: {}.",
				contract == null ? "unknown" : contract.getName(),
				awaiting == null ? "nothing" : awaiting.getName());
			return;
		}
		Produce settled = getSettledContract();
		log.info("Farming contract: {}; awaiting hand-in: {}; just handed in: {}.",
			contract == null ? "none" : contract.getName(),
			awaiting == null ? "nothing" : awaiting.getName(),
			settled == null ? "nothing" : settled.getName() + " (so a new one is owed)");
	}

	/**
	 * Time Tracking's answer.
	 *
	 * <p>Everything here is defensive. Another plugin's storage is not a contract with us, so an
	 * absent or unreadable value simply means "no answer".
	 */
	@Nullable
	private Produce fromTimeTracking()
	{
		String stored = read(TIME_TRACKING_GROUP, TIME_TRACKING_CONTRACT_KEY);
		if (stored == null || stored.isEmpty())
		{
			return null;
		}

		try
		{
			return Produce.getByItemID(Integer.parseInt(stored.trim()));
		}
		catch (NumberFormatException e)
		{
			log.debug("Time Tracking's contract key read \"{}\", which is not an item id", stored);
			return null;
		}
	}

	@Nullable
	private Produce fromOurCapture()
	{
		return produceFromName(read(DoogleMapsConfig.GROUP, CAPTURED_CONTRACT_KEY));
	}

	@Nullable
	private static Produce produceFromName(@Nullable String name)
	{
		if (name == null || name.isEmpty())
		{
			return null;
		}
		try
		{
			return Produce.valueOf(name);
		}
		catch (IllegalArgumentException e)
		{
			// A produce renamed since this was written. Dropped rather than thrown on.
			return null;
		}
	}

	@Nullable
	private String read(String group, String key)
	{
		try
		{
			return configManager.getRSProfileConfiguration(group, key);
		}
		catch (RuntimeException e)
		{
			log.debug("Could not read {}.{}", group, key, e);
			return null;
		}
	}

	private void write(String key, String value)
	{
		configManager.setRSProfileConfiguration(DoogleMapsConfig.GROUP, key, value);
	}

	private void clear(String key)
	{
		configManager.unsetRSProfileConfiguration(DoogleMapsConfig.GROUP, key);
	}

	private void fireChanged()
	{
		for (Runnable listener : changeListeners)
		{
			listener.run();
		}
	}
}
