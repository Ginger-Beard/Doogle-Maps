package com.dooglemaps.state;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.ProduceState;
import com.google.gson.Gson;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * The compost on a patch that was already ripe when you logged in has to be recoverable.
 *
 * <h2>The reported dead end</h2>
 *
 * The Stats tab reported six crops at the 99th luck percentile and 1,441 "items over expectation".
 * None of it was luck. The plugin only knows a patch was composted if it watched the bucket go
 * in, and the Time Tracking backfill that fills that gap was gated on {@code GROWING || DISEASED}
 * — so a patch composted last session and <b>already harvestable at login</b> never got the fill.
 * That is the ordinary shape of a herb run: you log in to six ready patches.
 *
 * <p>Every yield the plugin then predicted for those patches assumed three harvest lives where
 * they had six, so it predicted exactly half. The audit's arms are unmistakable — over 46 avantoe
 * patches the treated arm averaged 8.84 actual against 8.95 predicted, and the "untreated" arm
 * 8.93 actual against <b>4.48</b> predicted. Identical yields, halved prediction, and the surplus
 * between them dressed up as luck. Avantoe's minimum over all 46 was 6, which is the
 * ultracomposted floor and impossible on three lives.
 *
 * <p>The plugin had been saying so for three weeks: {@code warnIfCompostWasMissed} fired eighty
 * times in the surviving client logs, on exactly the crops showing 99th percentiles.
 *
 * <p>The two facts in the backfill expire at different moments and needed splitting. A protection
 * payment really is spent when the crop ripens — the crop can no longer catch anything — so that
 * half keeps its guard. Compost is spent one life per <i>pick</i>, which is to say entirely after
 * the crop turns harvestable, which is the moment it was being withheld.
 */
public class ARipeAtLoginPatchStillLearnsItsCompostTest
{
	/** Falador's north allotment, whose varbit values are known good fixtures. */
	private static final String FALADOR_NORTH = "12083.4771";

	private ConfigManager configManager;
	private PatchStateStore store;

	@Before
	public void setUp()
	{
		configManager = Mockito.mock(ConfigManager.class);
		store = construct(PatchStateStore.class, configManager, new Gson());
		store.load();
	}

	@Test
	public void aPatchAlreadyRipeAtLoginTakesTheBucketTimeTrackingRemembers()
	{
		FarmPatch patch = ripe();

		assertEquals("fixture: our own capture never saw the bucket",
			CompostTier.NONE, store.get(patch).getCompost());

		store.backfillFrom(timeTrackingSaying(patch, CompostTier.ULTRACOMPOST));

		assertEquals("compost is spent a life per pick, so it is still on the patch while the "
				+ "crop is being picked",
			CompostTier.ULTRACOMPOST, store.get(patch).getCompost());
	}

	/**
	 * The other half of the split: a payment really is spent once the crop is ripe.
	 *
	 * <p>Pinned here beside the change rather than left implied, because the two facts were one
	 * boolean and separating them is the whole of the fix. Restoring a spent payment promises
	 * disease safety that no longer holds, which is the wrong direction to be wrong in — and it
	 * is the failure the shared guard was originally added to stop.
	 */
	@Test
	public void aProtectionPaymentIsStillTreatedAsSpentOnceTheCropIsRipe()
	{
		FarmPatch patch = ripe();

		TimeTrackingState timeTracking = Mockito.mock(TimeTrackingState.class);
		when(timeTracking.isProtected(patch)).thenReturn(Boolean.TRUE);
		store.backfillFrom(timeTracking);

		assertTrue("a ripe crop cannot catch anything, so the payment is behind us",
			!store.get(patch).isPatchProtected());
	}

	/** And an empty patch has no crop for a treatment to be working on. */
	@Test
	public void anEmptyPatchTakesNoCompostAtAll()
	{
		FarmPatch patch = FarmingWorldData.getPatch(FALADOR_NORTH);
		assertNotNull(patch);

		ProduceState weeds = patch.getImplementation().forVarbitValue(3);
		assertNotNull("fixture: varbit 3 is weeds", weeds);
		store.recordVarbit(patch, 3, weeds);

		store.backfillFrom(timeTrackingSaying(patch, CompostTier.ULTRACOMPOST));

		assertEquals("nothing is growing here for the bucket to be helping",
			CompostTier.NONE, store.get(patch).getCompost());
	}

	private static TimeTrackingState timeTrackingSaying(FarmPatch patch, CompostTier tier)
	{
		TimeTrackingState timeTracking = Mockito.mock(TimeTrackingState.class);
		when(timeTracking.compost(patch)).thenReturn(tier);
		return timeTracking;
	}

	/** Falador's north allotment holding a fully grown crop, as it reads on a fresh login. */
	private FarmPatch ripe()
	{
		FarmPatch patch = FarmingWorldData.getPatch(FALADOR_NORTH);
		assertNotNull("fixture patch no longer exists", patch);

		ProduceState ripe = patch.getImplementation().forVarbitValue(10);
		assertNotNull(ripe);
		assertEquals("fixture: varbit 10 is a ripe crop", CropState.HARVESTABLE, ripe.getCropState());
		store.recordVarbit(patch, 10, ripe);
		return patch;
	}
}
