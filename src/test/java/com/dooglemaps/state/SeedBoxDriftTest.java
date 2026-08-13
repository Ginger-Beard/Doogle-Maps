package com.dooglemaps.state;

import com.dooglemaps.data.SeedBox;
import com.dooglemaps.data.Seed;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * The seed box is inferred rather than read, so it is the one source that can be quietly wrong.
 *
 * <h2>Why it is inferred at all</h2>
 *
 * The client's copy of the box lags a step behind a Fill or Empty, so reading it straight after
 * an action returns the contents from <i>before</i> the move — which is how filling made seeds
 * vanish and emptying made them double. {@code SeedInventoryStore} therefore derives the box from
 * the action plus the inventory delta, and ignores the box's own container event for a couple of
 * ticks around a click so the lagged copy cannot overwrite the good answer.
 *
 * <p>The cost of that is what this class pins. The suppressed event is, in practice, close to the
 * only time the box's real contents ever arrive, so a derivation that goes wrong <b>stays</b>
 * wrong — nothing reads the box again until the player happens to open it. Reported from play as
 * a box that took two Emptys and a check to come right, with the log showing the model holding
 * seven kinds while the game allows six.
 */
public class SeedBoxDriftTest
{
	private Client client;
	private SeedInventoryStore seeds;
	private int tick;

	@Before
	public void setUp()
	{
		client = Mockito.mock(Client.class);
		when(client.getTickCount()).thenAnswer(invocation -> tick);
		seeds = construct(SeedInventoryStore.class, client,
			Mockito.mock(ConfigManager.class), new Gson());
	}

	/**
	 * A lagged read is still ignored, which is the behaviour the window exists for.
	 *
	 * <p>Pinned first, because the fixes below narrow this window and must not open it.
	 *
	 * <p>The fixture is the box's <b>own contents from before the click</b>, and that matters:
	 * this used to feed an unrelated box (one cabbage where the model held potato and onion) and
	 * assert it was ignored. Nothing in the game produces that. A stale copy is stale, not
	 * different, and pinning the time window instead of the thing the window is for is what let
	 * a genuinely informative read be thrown away with it.
	 */
	@Test
	public void aBoxReadRightAfterAnActionIsStillIgnored()
	{
		boxHolds(Seed.POTATO, Seed.ONION);

		seeds.noteSeedBoxAction(SeedBoxAction.FILL);
		// The client's stale copy, arriving on the same tick as the click.
		record(SeedSource.SEED_BOX, containerOf(java.util.Arrays.asList(
			Seed.POTATO.getPlantedItemID(), 5, Seed.ONION.getPlantedItemID(), 5)));

		assertEquals("nothing was learned, so nothing changed", 2, kindsInBox());
		assertEquals(5, seeds.getCount(Seed.POTATO, SeedSource.SEED_BOX));
	}

	/**
	 * Once the window passes, a read is taken as truth — the ordinary healing path.
	 */
	@Test
	public void aBoxReadOutsideTheWindowIsTrusted()
	{
		boxHolds(Seed.POTATO, Seed.ONION);

		seeds.noteSeedBoxAction(SeedBoxAction.FILL);
		tick += 10;
		record(SeedSource.SEED_BOX, container(Seed.CABBAGE, 7));

		assertEquals(7, seeds.getCount(Seed.CABBAGE, SeedSource.SEED_BOX));
		assertEquals("replaced wholesale, not merged", 0,
			seeds.getCount(Seed.POTATO, SeedSource.SEED_BOX));
	}

	/**
	 * A model holding a seventh kind is not a disagreement, it is proof of drift.
	 *
	 * <p>So the suppression loses its teeth for exactly that case: the read wins even inside the
	 * window, because there is no reading of the game in which seven kinds is right. Without this
	 * the only way back was the player noticing and emptying the box twice.
	 */
	@Test
	public void anImpossibleBoxIsHealedByTheNextReadEvenInsideTheWindow()
	{
		boxHolds(Seed.POTATO, Seed.ONION, Seed.CABBAGE, Seed.TOMATO,
			Seed.SWEETCORN, Seed.STRAWBERRY, Seed.WATERMELON);
		assertEquals("fixture: the model is in the impossible state",
			SeedBox.KINDS + 1, kindsInBox());

		seeds.noteSeedBoxAction(SeedBoxAction.FILL);
		record(SeedSource.SEED_BOX, container(Seed.RANARR, 3));

		assertEquals("the game's own copy is taken over arithmetic that cannot be right",
			3, seeds.getCount(Seed.RANARR, SeedSource.SEED_BOX));
		assertEquals(1, kindsInBox());
	}

	/** Six kinds is the limit and not yet a fault, so a stale copy of a full box is still ignored. */
	@Test
	public void aFullButPossibleBoxIsNotTreatedAsDrift()
	{
		Seed[] full = {Seed.POTATO, Seed.ONION, Seed.CABBAGE, Seed.TOMATO,
			Seed.SWEETCORN, Seed.STRAWBERRY};
		boxHolds(full);
		assertEquals(SeedBox.KINDS, kindsInBox());

		seeds.noteSeedBoxAction(SeedBoxAction.FILL);
		java.util.List<Integer> same = new ArrayList<>();
		for (Seed seed : full)
		{
			same.add(seed.getPlantedItemID());
			same.add(5);
		}
		record(SeedSource.SEED_BOX, containerOf(same));

		assertEquals("a full box is legal, so nothing about it is proof of drift",
			SeedBox.KINDS, kindsInBox());
	}

	/**
	 * A read that is not the pre-click copy is the game's answer, whatever the window says.
	 *
	 * <p>Six kinds is legal, so nothing about the box is provably wrong — which is exactly the
	 * state the second report was stuck in. But a lagged read is by definition the contents from
	 * before the action, so a read that differs from those contents is not the lagged one.
	 */
	@Test
	public void aReadThatDiffersFromThePreClickBoxIsTakenAsTruth()
	{
		boxHolds(Seed.POTATO, Seed.ONION, Seed.CABBAGE, Seed.TOMATO,
			Seed.SWEETCORN, Seed.STRAWBERRY);
		assertEquals("fixture: a legal, full box", SeedBox.KINDS, kindsInBox());

		seeds.noteSeedBoxAction(SeedBoxAction.EMPTY);
		record(SeedSource.SEED_BOX, container(Seed.RANARR, 3));

		assertEquals("the game says ranarr, and the game was looking", 3,
			seeds.getCount(Seed.RANARR, SeedSource.SEED_BOX));
		assertEquals(1, kindsInBox());
	}

	/**
	 * A seed that leaves the pack near a Fill did not necessarily go in the box.
	 *
	 * <h2>Where the drift came from</h2>
	 *
	 * The Fill derivation credited the box with anything that left the inventory inside the
	 * two-tick window. Banking the rest of your seeds a moment after filling the box looks
	 * exactly like that, and so does planting one. The account this was found on had a single
	 * whiteberry seed in its modelled box and a hundred and forty in the bank.
	 *
	 * <p>Two things the box cannot have done, both hard rules rather than guesses: a Fill takes
	 * the <b>whole</b> loose stack of a kind it accepts, and it can never make a seventh kind.
	 */
	@Test
	public void aPartialDepartureIsNotCreditedToTheBox()
	{
		// A pack holding two kinds, one of which the player is about to bank half of.
		tick += 10;
		record(SeedSource.INVENTORY, containerOf(java.util.Arrays.asList(
			Seed.POTATO.getPlantedItemID(), 10,
			Seed.WHITEBERRIES.getPlantedItemID(), 8)));

		seeds.noteSeedBoxAction(SeedBoxAction.FILL);
		// The potatoes all went in; three whiteberries went to the bank, five are still loose.
		record(SeedSource.INVENTORY, containerOf(java.util.Arrays.asList(
			Seed.WHITEBERRIES.getPlantedItemID(), 5)));

		assertEquals("the whole potato stack is what a Fill takes", 10,
			seeds.getCount(Seed.POTATO, SeedSource.SEED_BOX));
		assertEquals("and the box did not take three of a stack it left five of", 0,
			seeds.getCount(Seed.WHITEBERRIES, SeedSource.SEED_BOX));
	}

	/**
	 * A Fill into a box the model already had full is recorded, and the model is what gives.
	 *
	 * <h2>This test used to assert the opposite, and the opposite lost 681 seeds</h2>
	 *
	 * It was called {@code aFillCannotAddASeventhKind} and pinned the seed being dropped, on the
	 * reasoning that a box holding six kinds cannot take a seventh — so a seed leaving the pack
	 * near a Fill must have gone somewhere else. That is true of the <b>box</b> and false of our
	 * <b>model</b> of it, and the two are only the same thing while the derivation is right.
	 *
	 * <p>Reported from play, with the log line: 681 limpwurt seeds filled into the box, refused
	 * here, and the run went on reporting {@code Limpwurt (inv 0, box 0, bank 0, vault 0)}. The
	 * flower patch at the Farming Guild then had nothing to plant, produced no step, and the stop
	 * completed under the player.
	 *
	 * <p>So the observation outranks the model. The model going to seven kinds is not a bug in
	 * this test's expectations — it is the drift signal {@link SeedBox#KINDS} documents itself as
	 * being, and it is what {@code boxCannotBeRight} exists to act on. Refusing the merge is what
	 * stopped that signal ever being recorded.
	 *
	 * <p>The rule this does <b>not</b> weaken is the partial-stack one above: a Fill takes the
	 * whole loose stack, so leftovers still prove the box did not take it. That is the guard
	 * which actually catches banking-next-to-a-Fill, and it is untouched.
	 */
	@Test
	public void aFillIntoAModelledFullBoxIsBelievedOverTheModel()
	{
		boxHolds(Seed.POTATO, Seed.ONION, Seed.CABBAGE, Seed.TOMATO,
			Seed.SWEETCORN, Seed.STRAWBERRY);
		tick += 10;
		record(SeedSource.INVENTORY, container(Seed.WHITEBERRIES, 681));

		seeds.noteSeedBoxAction(SeedBoxAction.FILL);
		// The whole stack leaves the pack, which is exactly what a Fill does.
		record(SeedSource.INVENTORY, containerOf(new ArrayList<>()));

		assertEquals("the seeds the game moved are recorded, not discarded", 681,
			seeds.getCount(Seed.WHITEBERRIES, SeedSource.SEED_BOX));
		assertEquals("and the model carries the proof that it had drifted",
			SeedBox.KINDS + 1, kindsInBox());
	}

	/**
	 * The Fill guard and the highlight count the box the same way.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * There were two implementations of "how many kinds does the box hold", and the log caught
	 * them disagreeing about the same box in the same second — the Fill guard counted raw map
	 * entries with {@code box.size()} and said six, while the overlay walked the seed table and
	 * said five. One gates whether a Fill is believed and the other gates whether the box
	 * lights, so a disagreement is two features acting on different beliefs about one container.
	 *
	 * <p>A calquat seed is the case that separated them, for the reason
	 * {@code getSeedCount} exists: it is a seed the box takes, on a crop whose
	 * {@code isSapling()} is true.
	 */
	@Test
	public void theBoxIsCountedTheSameWayEverywhere()
	{
		tick += 10;
		record(SeedSource.SEED_BOX, containerOf(java.util.Arrays.asList(
			Seed.POTATO.getPlantedItemID(), 10,
			Seed.ONION.getPlantedItemID(), 10,
			Seed.CALQUAT.getItemID(), 1)));

		// The swap and the overlay both delegate here now, so this is the single answer they
		// share - GuideMenuSwap.kindsInTheBox is a one-line call to it and is package-private,
		// which is why it cannot be named from this package and does not need to be.
		assertEquals("a calquat seed is one of the six", 3, seeds.kindsInTheSeedBox());
	}

	/** A calquat sapling is not a seed and does not spend a slot. */
	@Test
	public void aSaplingIsNotOneOfTheSixKinds()
	{
		tick += 10;
		record(SeedSource.SEED_BOX, containerOf(java.util.Arrays.asList(
			Seed.POTATO.getPlantedItemID(), 10,
			Seed.CALQUAT.getPlantedItemID(), 1)));

		assertEquals("the box will not hold a plant pot, so it costs no slot",
			1, seeds.kindsInTheSeedBox());
	}

	/**
	 * The box is read from the client every tick, so a wrong model heals by itself.
	 *
	 * <h2>The reported dead end, and the mistake underneath every earlier one</h2>
	 *
	 * The box was <b>only</b> ever derived — from a Fill or Empty plus the inventory delta — and
	 * read from its own container event, which the action window then discarded. Nothing ever
	 * asked the client what was in it. A container that is only inferred has no way back once an
	 * inference goes wrong, and every fix before this one was to a different inference: each
	 * correct, none of them able to heal what had already drifted.
	 *
	 * <p>Pinned with the contents written out from play: six ordinary herb seeds in the box, a
	 * seventh kind loose in the pack, and the box lighting to fill. No rule about kinds can
	 * produce that. Only a model that disagrees with the box can.
	 *
	 * <h2>It is read from the interface, because there is no container to read</h2>
	 *
	 * <p>This used to say the box "is a container you <i>carry</i>; {@code getItemContainer}
	 * answers for it exactly as it does for the pack", and the test mocked exactly that. Both
	 * were wrong. The live client answers <b>null</b> for {@code InventoryID.SEED_BOX} always —
	 * {@code box 0 read, 56 with no container} on one run and not one successful reconcile in any
	 * archived log — so the path was dead and this test was green over a capability that did not
	 * exist. A mock will happily impersonate an API the game never populates.
	 *
	 * <p>The contents are in the interface instead: while the box is open they are children of
	 * {@code HosidiusSeedbox.SEED_LAYER}, one per seed, each with an item id and a quantity.
	 * The derivation stays for the times the box is shut.
	 */
	@Test
	public void theBoxIsReadBackFromItsInterface()
	{
		// A model that has drifted to the wrong six kinds.
		boxHolds(Seed.POTATO, Seed.ONION, Seed.CABBAGE, Seed.TOMATO,
			Seed.SWEETCORN, Seed.STRAWBERRY);

		// What the box actually holds - the herb-seed case from the report.
		when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
		boxInterfaceShows(java.util.Arrays.asList(
			Seed.RANARR.getPlantedItemID(), 12,
			Seed.TOADFLAX.getPlantedItemID(), 338,
			Seed.IRIT.getPlantedItemID(), 281));

		tick += 10;
		seeds.relearnInventoryFromClient();

		assertEquals("what the interface shows is the box", 3, kindsInBox());
		assertEquals(338, seeds.getCount(Seed.TOADFLAX, SeedSource.SEED_BOX));
		assertEquals("and the phantoms are gone", 0,
			seeds.getCount(Seed.POTATO, SeedSource.SEED_BOX));
		assertTrue("and it counts as having been seen", seeds.hasSeenTheBoxThisSession());
	}

	/**
	 * The limpwurt case end to end: seeds the derivation could not place, healed by one opening.
	 *
	 * <p>The two halves of the repair meeting. A Fill the model could not account for is recorded
	 * rather than discarded, which stops the loss; opening the box then replaces the guess with
	 * the truth, which is what the discard was waiting for and never got.
	 */
	@Test
	public void openingTheBoxHealsAModelAFillHadPushedOverCapacity()
	{
		boxHolds(Seed.POTATO, Seed.ONION, Seed.CABBAGE, Seed.TOMATO,
			Seed.SWEETCORN, Seed.STRAWBERRY);
		tick += 10;
		record(SeedSource.INVENTORY, container(Seed.LIMPWURT, 681));
		seeds.noteSeedBoxAction(SeedBoxAction.FILL);
		record(SeedSource.INVENTORY, containerOf(new ArrayList<>()));
		assertEquals("fixture: the seeds are kept, and the model is over capacity",
			681, seeds.getCount(Seed.LIMPWURT, SeedSource.SEED_BOX));
		assertEquals(SeedBox.KINDS + 1, kindsInBox());

		// The player opens the box once.
		when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
		boxInterfaceShows(java.util.Arrays.asList(
			Seed.LIMPWURT.getPlantedItemID(), 681,
			Seed.POTATO.getPlantedItemID(), 5));
		tick += 10;
		seeds.relearnInventoryFromClient();

		assertEquals("the guess is replaced by what is actually in it", 2, kindsInBox());
		assertEquals(681, seeds.getCount(Seed.LIMPWURT, SeedSource.SEED_BOX));
	}

	/**
	 * An <b>open and empty</b> box really is empty, which the container poll could never say.
	 *
	 * <h2>Why the answer changed with the mechanism</h2>
	 *
	 * The old poll had to refuse an empty copy. A container the client had simply not populated
	 * looked exactly like an empty one, and wiping the model on it read downstream as no kinds
	 * in the box — so the fill highlight lit for every loose seed in the pack. That was a real
	 * reported regression, and the guard against it was right for what it was guarding.
	 *
	 * <p>An open interface carries no such ambiguity. The box is on screen and there is nothing
	 * in it. The old ambiguity moves to a place that can express it honestly: a <b>closed</b> box
	 * is null, which is "no news" and leaves the model alone — see the test below.
	 */
	@Test
	public void anOpenAndEmptyBoxIsAnEmptyBox()
	{
		boxHolds(Seed.POTATO, Seed.ONION);

		when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
		boxInterfaceShows(new ArrayList<>());

		tick += 10;
		seeds.relearnInventoryFromClient();

		assertEquals("the player is looking into an empty box", 0, kindsInBox());
	}

	/** And a real Empty still empties it, because that arrives as the box's own event. */
	@Test
	public void arealEmptyStillEmptiesTheBox()
	{
		boxHolds(Seed.POTATO, Seed.ONION);

		tick += 10;
		record(SeedSource.SEED_BOX, containerOf(new ArrayList<>()));

		assertEquals("the game said so, so it is so", 0, kindsInBox());
	}

	/**
	 * A closed box is not an empty one, and must not wipe the model.
	 *
	 * <p>The ordinary state, and the reason the derivation still exists: most of a run happens
	 * with the box shut, and what is remembered has to survive it.
	 */
	@Test
	public void aClosedBoxLeavesTheModelAlone()
	{
		boxHolds(Seed.POTATO, Seed.ONION);

		when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
		when(client.getWidget(
			net.runelite.api.gameval.InterfaceID.HosidiusSeedbox.SEED_LAYER)).thenReturn(null);

		tick += 10;
		seeds.relearnInventoryFromClient();

		assertEquals("closed is 'no news', not 'nothing in it'", 2, kindsInBox());
	}

	/**
	 * The game's own word for a movement beats the inventory arithmetic, and is not counted twice.
	 *
	 * <h2>Why both could fire for one click</h2>
	 *
	 * "Stored 6 x Ranarr seed in your seed box." describes a Fill — and a Fill is exactly what
	 * the derivation is watching the inventory for. The message is the better witness (it names
	 * the seed and the count; the derivation infers both and cannot tell a Fill from a bank
	 * deposit in the same two ticks), so it wins, and the derivation skips the seed it named.
	 *
	 * <p>Per seed rather than per click, because it is not known whether a Fill announces every
	 * kind it moves. Whatever the messages covered is exact; the rest is inferred as before.
	 */
	@Test
	public void aStatedMovementIsNotAlsoInferred()
	{
		tick += 10;
		record(SeedSource.INVENTORY, containerOf(java.util.Arrays.asList(
			Seed.RANARR.getPlantedItemID(), 6)));

		// The game says so, then the inventory change for the same click arrives.
		seeds.noteSeedBoxAction(SeedBoxAction.FILL);
		seeds.addToSeedBox(Seed.RANARR.getPlantedItemID(), 6);
		record(SeedSource.INVENTORY, containerOf(new ArrayList<>()));

		assertEquals("counted once, from the exact figure", 6,
			seeds.getCount(Seed.RANARR, SeedSource.SEED_BOX));
	}

	/** And the mirror: a stated Empty is not subtracted twice either. */
	@Test
	public void aStatedRemovalIsNotAlsoInferred()
	{
		boxHolds(Seed.RANARR);
		assertEquals("fixture", 5, seeds.getCount(Seed.RANARR, SeedSource.SEED_BOX));

		tick += 10;
		record(SeedSource.INVENTORY, containerOf(new ArrayList<>()));
		seeds.noteSeedBoxAction(SeedBoxAction.EMPTY);
		seeds.removeFromSeedBox(Seed.RANARR.getPlantedItemID(), 2);
		record(SeedSource.INVENTORY, containerOf(java.util.Arrays.asList(
			Seed.RANARR.getPlantedItemID(), 2)));

		assertEquals("five less the two the game named, not less four", 3,
			seeds.getCount(Seed.RANARR, SeedSource.SEED_BOX));
	}

	/** And the reconcile keeps its hands off while a Fill or Empty is still in flight. */
	@Test
	public void theReconcileWaitsForAClickToLand()
	{
		boxHolds(Seed.POTATO, Seed.ONION);

		when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
		ItemContainer real =
			containerOf(java.util.Arrays.asList(Seed.RANARR.getPlantedItemID(), 3));
		when(client.getItemContainer(SeedSource.SEED_BOX.getContainerId())).thenReturn(real);

		seeds.noteSeedBoxAction(SeedBoxAction.FILL);
		seeds.relearnInventoryFromClient();

		assertEquals("the click has not landed yet, so the read is not trusted",
			2, kindsInBox());
	}

	/**
	 * A Fill the model cannot account for makes it distrust itself.
	 *
	 * <h2>The reported dead end</h2>
	 *
	 * The seventh-kind guard exists so a seed banked during a Fill window is not credited to the
	 * box. But when it fires the seed has already left the inventory, so declining to add it to
	 * the box removes it from the model altogether — and if the model's six kinds are not the
	 * box's six, that is a disappearance rather than a correction.
	 *
	 * <p>Reported from play: six herb seeds recorded in the box, a mushroom spore filled into it,
	 * and the bank row asking for spores again the instant the Fill happened. The spore left the
	 * pack, the guard refused it, and nothing held it.
	 *
	 * <p>So the guard now says so about itself. A seed the game accepted that the model cannot
	 * place is evidence the model is wrong, and the next read of the box is taken over the
	 * derivation instead of being discarded by the action window.
	 *
	 * <h2>And the seed is kept, which that fix assumed rather than ensured</h2>
	 *
	 * <p>The paragraph above was the whole repair, and it left the spore on the floor: "the next
	 * read of the box" never comes. {@code getItemContainer(573)} has answered null on every
	 * attempt of every session on record, so a model marked suspect stayed suspect and stayed
	 * wrong. This test asserted the disappearance it describes — {@code assertEquals(0, ...)} —
	 * and passed.
	 *
	 * <p>Found when it cost a whole stop: 681 limpwurt seeds filled into a box the model had at
	 * six kinds, refused, and the Farming Guild's flower patch left unplantable with the run
	 * reporting {@code Limpwurt (inv 0, box 0, bank 0, vault 0)}. Both halves are needed — the
	 * seeds are recorded because the game moved them, and the read still wins because seven
	 * kinds is proof the model had drifted. Neither half is sufficient alone.
	 */
	@Test
	public void aFillItCannotAccountForIsKeptAndMakesTheNextReadWin()
	{
		boxHolds(Seed.POTATO, Seed.ONION, Seed.CABBAGE, Seed.TOMATO,
			Seed.SWEETCORN, Seed.STRAWBERRY);
		tick += 10;
		record(SeedSource.INVENTORY, container(Seed.MUSHROOM, 1));

		seeds.noteSeedBoxAction(SeedBoxAction.FILL);
		// The spore leaves the pack, into a box the model already had at six kinds.
		record(SeedSource.INVENTORY, containerOf(new ArrayList<>()));
		assertEquals("the game moved it, so the model records it rather than losing it",
			1, seeds.getCount(Seed.MUSHROOM, SeedSource.SEED_BOX));
		assertEquals("and carries the proof that it had drifted",
			SeedBox.KINDS + 1, kindsInBox());

		// And now the box's own copy arrives, inside the window that would normally discard it.
		record(SeedSource.SEED_BOX, container(Seed.MUSHROOM, 1));

		assertEquals("the game's copy wins over a model that just contradicted it",
			1, seeds.getCount(Seed.MUSHROOM, SeedSource.SEED_BOX));
		assertEquals(1, kindsInBox());
	}

	/** With nothing contradicted, the window still protects a good derivation. */
	@Test
	public void anUnremarkableFillLeavesTheWindowAlone()
	{
		boxHolds(Seed.POTATO, Seed.ONION);

		seeds.noteSeedBoxAction(SeedBoxAction.FILL);
		record(SeedSource.SEED_BOX, containerOf(java.util.Arrays.asList(
			Seed.POTATO.getPlantedItemID(), 5, Seed.ONION.getPlantedItemID(), 5)));

		assertEquals("nothing was learned, so nothing changed", 2, kindsInBox());
	}

	/**
	 * A sapling cannot get into the box's model, however it is read in.
	 *
	 * <h2>Why the model has to refuse it rather than everything downstream</h2>
	 *
	 * {@code countSeeds} accepts anything in the {@link Seed} table, and that table maps a tree
	 * crop's <b>sapling</b> id to the crop as well as its seed. So a sapling read into the box
	 * would sit there spending one of the six kinds forever, and every consumer would have to
	 * remember to discount it — which is exactly what they each did differently, and is the
	 * whole history of this class of bug. {@code SeedBox} answers it once, at the point the
	 * contents are recorded.
	 */
	@Test
	public void aSaplingCannotEnterTheBoxsModel()
	{
		tick += 10;
		record(SeedSource.SEED_BOX, containerOf(java.util.Arrays.asList(
			Seed.POTATO.getItemID(), 5,
			Seed.CALQUAT.getSaplingItemID(), 1)));

		assertEquals("the box will not hold a plant pot, so it costs no kind", 1, kindsInBox());
		assertEquals("and the seed beside it is untouched", 5,
			seeds.getCount(Seed.POTATO, SeedSource.SEED_BOX));
	}

	/** But the tree's seed is a seed like any other, and does spend a kind. */
	@Test
	public void aTreeSeedIsAcceptedLikeAnyOther()
	{
		tick += 10;
		record(SeedSource.SEED_BOX, containerOf(java.util.Arrays.asList(
			Seed.POTATO.getItemID(), 5,
			Seed.CALQUAT.getItemID(), 1)));

		assertEquals(2, kindsInBox());
		assertEquals(1, seeds.getSeedCount(Seed.CALQUAT, SeedSource.SEED_BOX));
	}

	// ------------------------------------------------------------------ helpers

	/** Puts these kinds in the box, one each, by reading them in outside any action window. */
	private void boxHolds(Seed... kinds)
	{
		List<Integer> flat = new ArrayList<>();
		for (Seed seed : kinds)
		{
			flat.add(seed.getPlantedItemID());
			flat.add(5);
		}
		tick += 10;
		record(SeedSource.SEED_BOX, containerOf(flat));
	}

	private int kindsInBox()
	{
		int kinds = 0;
		for (Seed seed : Seed.values())
		{
			if (seeds.getCount(seed, SeedSource.SEED_BOX) > 0)
			{
				kinds++;
			}
		}
		return kinds;
	}

	private void record(SeedSource source, ItemContainer container)
	{
		seeds.record(source.getContainerId(), container);
	}

	private static ItemContainer container(Seed seed, int quantity)
	{
		List<Integer> flat = new ArrayList<>();
		flat.add(seed.getPlantedItemID());
		flat.add(quantity);
		return containerOf(flat);
	}

	/**
	 * Puts the seed box's interface on screen holding these items.
	 *
	 * <p>The shape the live client actually offers: {@code HosidiusSeedbox.SEED_LAYER} with one
	 * child per seed, each carrying an item id and a quantity. Deliberately not an
	 * {@code ItemContainer} — that is the API the box does not have, and mocking it is how a
	 * dead read stayed green for months.
	 */
	private void boxInterfaceShows(List<Integer> idThenQuantity)
	{
		net.runelite.api.widgets.Widget[] children =
			new net.runelite.api.widgets.Widget[idThenQuantity.size() / 2];
		for (int i = 0; i < children.length; i++)
		{
			net.runelite.api.widgets.Widget item =
				Mockito.mock(net.runelite.api.widgets.Widget.class);
			when(item.getItemId()).thenReturn(idThenQuantity.get(i * 2));
			when(item.getItemQuantity()).thenReturn(idThenQuantity.get(i * 2 + 1));
			children[i] = item;
		}

		net.runelite.api.widgets.Widget layer =
			Mockito.mock(net.runelite.api.widgets.Widget.class);
		when(layer.isHidden()).thenReturn(false);
		when(layer.getChildren()).thenReturn(children);
		when(client.getWidget(
			net.runelite.api.gameval.InterfaceID.HosidiusSeedbox.SEED_LAYER)).thenReturn(layer);
	}

	private static ItemContainer containerOf(List<Integer> idThenQuantity)
	{
		Item[] items = new Item[idThenQuantity.size() / 2];
		for (int i = 0; i < items.length; i++)
		{
			items[i] = new Item(idThenQuantity.get(i * 2), idThenQuantity.get(i * 2 + 1));
		}
		ItemContainer container = Mockito.mock(ItemContainer.class);
		when(container.getItems()).thenReturn(items);
		return container;
	}
}
