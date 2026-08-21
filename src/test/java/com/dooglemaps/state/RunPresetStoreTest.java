package com.dooglemaps.state;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Named sets of run tickboxes: saving, applying, and surviving a round trip through config.
 *
 * <p>The store holds {@code RunOption} keys rather than resolved options, which is what lets a
 * preset outlive the offered list changing under it — see the class note.
 */
public class RunPresetStoreTest
{
	private ConfigManager configManager;
	private RunPresetStore store;

	@Before
	public void setUp()
	{
		configManager = Mockito.mock(ConfigManager.class);
		store = construct(RunPresetStore.class, configManager, new Gson());
		store.load();
	}

	private static Set<String> keys(String... values)
	{
		return new LinkedHashSet<>(Arrays.asList(values));
	}

	@Test
	public void aSavedPresetComesBackByName()
	{
		store.save("Herb run", keys("HERB", "ALLOTMENT", "FLOWER"));

		assertEquals(Collections.singletonList("Herb run"), store.names());
		assertEquals(keys("HERB", "ALLOTMENT", "FLOWER"), store.keysOf("Herb run"));
	}

	/** Saving over a name replaces it, which is what an editable dropdown implies. */
	@Test
	public void savingTwiceUnderOneNameReplacesRatherThanDuplicates()
	{
		store.save("Herb run", keys("HERB"));
		store.save("Herb run", keys("HERB", "ALLOTMENT"));

		assertEquals("one preset, not two", 1, store.names().size());
		assertEquals(keys("HERB", "ALLOTMENT"), store.keysOf("Herb run"));
	}

	/** Presets list in the order they were made, so the dropdown does not reshuffle. */
	@Test
	public void namesKeepTheirInsertionOrder()
	{
		store.save("Tree run", keys("TREE"));
		store.save("Herb run", keys("HERB"));
		store.save("Bins", keys("COMPOST"));

		assertEquals(Arrays.asList("Tree run", "Herb run", "Bins"), store.names());
	}

	/**
	 * {@code matches} is what lets the panel decide whether the dropdown may still claim a name,
	 * and it compares sets — two presets differing only in tick order are the same run.
	 */
	@Test
	public void matchingIgnoresTheOrderTheBoxesWereTicked()
	{
		store.save("Herb run", keys("HERB", "ALLOTMENT"));

		assertTrue(store.matches("Herb run", keys("ALLOTMENT", "HERB")));
		assertFalse("a different set is a different run",
			store.matches("Herb run", keys("HERB")));
		assertFalse("and an unknown name matches nothing", store.matches("Nope", keys("HERB")));
	}

	/**
	 * A nameless or empty preset is refused quietly.
	 *
	 * <p>Neither is worth storing — one cannot be selected again, the other is a run that visits
	 * nothing — and neither is worth an error dialog mid-click either.
	 */
	@Test
	public void blankNamesAndEmptySelectionsAreNotStored()
	{
		assertNull(store.save("   ", keys("HERB")));
		assertNull(store.save("Herb run", keys()));
		assertNull(store.save(null, keys("HERB")));

		assertTrue(store.names().isEmpty());
	}

	/** Names are trimmed, so " Herb run " and "Herb run" are not two presets. */
	@Test
	public void namesAreTrimmed()
	{
		assertEquals("Herb run", store.save("  Herb run  ", keys("HERB")));
		assertEquals(Collections.singletonList("Herb run"), store.names());
	}

	@Test
	public void deletingForgetsOnlyThatPreset()
	{
		store.save("Herb run", keys("HERB"));
		store.save("Tree run", keys("TREE"));

		store.delete("Herb run");

		assertEquals(Collections.singletonList("Tree run"), store.names());
		assertNull(store.keysOf("Herb run"));
	}

	/** Deleting something that was never there is silent rather than an error. */
	@Test
	public void deletingAnUnknownPresetDoesNothing()
	{
		store.save("Herb run", keys("HERB"));
		store.delete("Nope");

		assertEquals(Collections.singletonList("Herb run"), store.names());
	}

	/**
	 * The preset the player is on survives a restart, so the dropdown does not re-derive a label.
	 *
	 * <p>The ticks persist on their own through {@code RunTypeStore}, so this changes nothing about
	 * what a run does — only what the control says it is. Without it, two presets covering the same
	 * lines make the name flip between sessions for no reason the player did.
	 */
	@Test
	public void theLastUsedPresetIsRemembered()
	{
		store.save("Herb run", keys("HERB"));
		store.save("Tree run", keys("TREE"));
		store.setLastUsed("Tree run");

		assertEquals("Tree run", store.lastUsed());

		ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
		Mockito.verify(configManager, Mockito.atLeastOnce())
			.setRSProfileConfiguration(Mockito.anyString(), Mockito.anyString(), json.capture());

		ConfigManager reader = Mockito.mock(ConfigManager.class);
		Mockito.when(reader.getRSProfileConfiguration(Mockito.anyString(), Mockito.anyString()))
			.thenReturn(json.getValue());

		RunPresetStore reloaded = construct(RunPresetStore.class, reader, new Gson());
		reloaded.load();

		assertEquals("Tree run", reloaded.lastUsed());
		assertEquals("and it is not mistaken for a preset of its own",
			Arrays.asList("Herb run", "Tree run"), reloaded.names());
	}

	/** Naming something that is not a preset is refused, so the label cannot point at nothing. */
	@Test
	public void anUnknownNameIsNotRemembered()
	{
		store.save("Herb run", keys("HERB"));
		store.setLastUsed("Nope");

		assertNull(store.lastUsed());
	}

	/** And deleting the preset forgets it, rather than leaving a pointer to something gone. */
	@Test
	public void deletingThePresetForgetsThatItWasTheLastUsed()
	{
		store.save("Herb run", keys("HERB"));
		store.setLastUsed("Herb run");

		store.delete("Herb run");

		assertNull(store.lastUsed());
	}

	/**
	 * The round trip through config, which is the thing a store exists to get right.
	 *
	 * <p>Written as JSON under one profile-scoped key by {@code ProfileJsonStore}, and read back
	 * into an equivalent store — the same path a client restart takes.
	 */
	@Test
	public void presetsSurviveAWriteAndReadBack()
	{
		store.save("Herb run", keys("HERB", "ALLOTMENT"));
		store.save("Tree run", keys("TREE", "FRUIT_TREE"));

		ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
		Mockito.verify(configManager, Mockito.atLeastOnce())
			.setRSProfileConfiguration(Mockito.anyString(), Mockito.anyString(),
				json.capture());

		ConfigManager reader = Mockito.mock(ConfigManager.class);
		Mockito.when(reader.getRSProfileConfiguration(Mockito.anyString(), Mockito.anyString()))
			.thenReturn(json.getValue());

		RunPresetStore reloaded = construct(RunPresetStore.class, reader, new Gson());
		reloaded.load();

		assertEquals(Arrays.asList("Herb run", "Tree run"), reloaded.names());
		assertEquals(keys("HERB", "ALLOTMENT"), reloaded.keysOf("Herb run"));
		assertEquals(keys("TREE", "FRUIT_TREE"), reloaded.keysOf("Tree run"));
	}

	/**
	 * Stored data survives version changes, so nothing about its shape is guaranteed — every
	 * store here validates on the way in rather than trusting the blob.
	 */
	@Test
	public void rubbishInTheStoredBlobIsDroppedRatherThanLoaded()
	{
		ConfigManager reader = Mockito.mock(ConfigManager.class);
		Mockito.when(reader.getRSProfileConfiguration(Mockito.anyString(), Mockito.anyString()))
			.thenReturn("{\"\":[\"HERB\"],\"Empty\":[],\"Good\":[\"HERB\",\"\"],\"Null\":null}");

		RunPresetStore reloaded = construct(RunPresetStore.class, reader, new Gson());
		reloaded.load();

		assertEquals("only the one usable entry survives",
			Collections.singletonList("Good"), reloaded.names());
		assertEquals("and its blank key is dropped with it",
			keys("HERB"), reloaded.keysOf("Good"));
	}
}
