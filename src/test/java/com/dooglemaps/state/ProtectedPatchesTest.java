package com.dooglemaps.state;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.Client;
import net.runelite.api.Varbits;
import net.runelite.client.config.ConfigManager;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The unlocks are re-read rather than sampled once.
 *
 * <p>This is the bug that made the protected herb tab disappear for a whole session. It was
 * sampled a single time, from the plugin's load, which runs the instant {@code LOGGED_IN} fires
 * — before the quest and diary varbits have all synced. The sample read zero, latched, and every
 * later question got that answer. It looked exactly like the tab strip being broken, and the
 * setting could not fix it because the setting was never the thing that was false.
 */
public class ProtectedPatchesTest
{
	/** Hosidius, whose herb patch is disease-free on the Kourend easy diary. */
	private static final String HOSIDIUS_HERB = "6967.4774";

	@Test
	public void anUnlockThatSyncsAfterTheFirstReadIsStillFound() throws Exception
	{
		Harness harness = new Harness();

		// The first tick after login: nothing has synced yet.
		harness.patches.refresh(harness.client);
		assertFalse("nothing is unlocked yet", harness.patches.isProtected(hosidius()));
		assertEquals(0, harness.patches.count());

		// The diary varbit arrives a moment later, as it does in a real client.
		harness.kourendEasy(1);
		harness.patches.refresh(harness.client);

		assertTrue("the diary was done; the read was simply early",
			harness.patches.isProtected(hosidius()));
		assertEquals(1, harness.patches.count());
	}

	/**
	 * And the panel is told, because it has already built its tabs by then.
	 *
	 * <p>Finding the unlock is only half of it — the sidebar was built from the empty answer and
	 * has no reason to look again unless something says so.
	 */
	@Test
	public void theListenerFiresWhenTheAnswerArrives() throws Exception
	{
		Harness harness = new Harness();
		AtomicInteger notified = new AtomicInteger();
		harness.patches.addChangeListener(notified::incrementAndGet);

		harness.patches.refresh(harness.client);
		assertEquals("the first sample of a session always notifies", 1, notified.get());

		harness.kourendEasy(1);
		harness.patches.refresh(harness.client);
		assertEquals("the answer changed, so the sidebar has to be told", 2, notified.get());
	}

	/**
	 * A tick where nothing changed must cost nothing.
	 *
	 * <p>This runs every tick now, so the quiet path has to stay off the config and off the
	 * listeners — otherwise the fix trades a missing tab for a config write four times a second.
	 */
	@Test
	public void anUnchangedAnswerDoesNotWriteOrNotify() throws Exception
	{
		Harness harness = new Harness();
		harness.kourendEasy(1);

		AtomicInteger notified = new AtomicInteger();
		harness.patches.addChangeListener(notified::incrementAndGet);
		harness.patches.refresh(harness.client);

		Mockito.clearInvocations(harness.configManager);
		for (int tick = 0; tick < 50; tick++)
		{
			harness.patches.refresh(harness.client);
		}

		assertEquals("only the first sample should have notified", 1, notified.get());
		Mockito.verify(harness.configManager, Mockito.never())
			.setRSProfileConfiguration(anyString(), anyString(), Mockito.any());
		Mockito.verify(harness.configManager, Mockito.never())
			.getRSProfileConfiguration(anyString(), anyString(), Mockito.<Class<?>>any());
	}

	/**
	 * The quest read is a script call, and it must not run every tick forever.
	 *
	 * <p>{@code Quest.getState} is not a varbit read — it calls {@code client.runScript}. It is
	 * the only thing in this plugin that asks the client to <i>do</i> something rather than
	 * answer a question, and it got here by fixing the sampling. Every tick through the window
	 * where a login is still settling, then occasionally; not four times a second for a session.
	 */
	@Test
	public void theQuestScriptIsNotRunEveryTickForever() throws Exception
	{
		Harness harness = new Harness();

		for (int tick = 0; tick < 30; tick++)
		{
			harness.patches.refresh(harness.client);
		}
		// Two quests, read on each of the first thirty ticks.
		Mockito.verify(harness.client, Mockito.times(60)).runScript(Mockito.any(), Mockito.any());

		Mockito.clearInvocations(harness.client);
		for (int tick = 0; tick < 300; tick++)
		{
			harness.patches.refresh(harness.client);
		}

		// Three of the next three hundred ticks, rather than all of them.
		Mockito.verify(harness.client, Mockito.times(6)).runScript(Mockito.any(), Mockito.any());
	}

	/** A new login gets the settling window back, because the account may be a different one. */
	@Test
	public void aResetRestoresTheSettlingWindow() throws Exception
	{
		Harness harness = new Harness();
		for (int tick = 0; tick < 200; tick++)
		{
			harness.patches.refresh(harness.client);
		}

		harness.patches.reset();
		Mockito.clearInvocations(harness.client);
		harness.patches.refresh(harness.client);

		Mockito.verify(harness.client, Mockito.times(2)).runScript(Mockito.any(), Mockito.any());
	}

	private static FarmPatch hosidius()
	{
		FarmPatch patch = FarmingWorldData.getPatch(HOSIDIUS_HERB);
		assertNotNull("fixture patch " + HOSIDIUS_HERB + " no longer exists", patch);
		return patch;
	}

	/** A client whose varbits can be made to arrive late, over a config that remembers. */
	private static final class Harness
	{
		private final Client client = Mockito.mock(Client.class);
		private final ConfigManager configManager = Mockito.mock(ConfigManager.class);
		private final ProtectedPatches patches;

		/** What the config would have persisted, so a read after a write sees the write. */
		private Integer stored;

		Harness() throws Exception
		{
			when(client.getVarbitValue(anyInt())).thenReturn(0);
			// Quest state is read off the script stack, so it needs one to read from. Left at
			// zero throughout: the two quest unlocks are not what this is about.
			when(client.getIntStack()).thenReturn(new int[]{0, 0, 0, 0});
			when(client.getIntStackSize()).thenReturn(1);

			when(configManager.getRSProfileConfiguration(
				anyString(), eq("protectedHerbRegions"), eq(int.class)))
				.thenAnswer(invocation -> stored);
			Mockito.doAnswer(invocation ->
			{
				stored = (Integer) invocation.getArgument(2);
				return null;
			}).when(configManager).setRSProfileConfiguration(
				anyString(), eq("protectedHerbRegions"), Mockito.any());

			patches = construct(ProtectedPatches.class, configManager);
		}

		void kourendEasy(int value)
		{
			when(client.getVarbitValue(Varbits.DIARY_KOUREND_EASY)).thenReturn(value);
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> T construct(Class<T> type, Object... args) throws Exception
	{
		for (Constructor<?> candidate : type.getDeclaredConstructors())
		{
			if (candidate.getParameterCount() == args.length)
			{
				candidate.setAccessible(true);
				return (T) candidate.newInstance(args);
			}
		}
		throw new IllegalStateException("no constructor of arity " + args.length + " on " + type);
	}
}
