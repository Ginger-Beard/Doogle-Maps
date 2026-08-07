package com.dooglemaps;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Guards the shape of the settings page, which nothing else can see.
 *
 * <h2>Why a duplicate position does not fail on its own</h2>
 *
 * RuneLite orders a section's settings by {@code position} and breaks ties on the setting's
 * <b>display name</b> — {@code ConfigPanel.rebuild} sorts with
 * {@code ComparisonChain.start().compare(position).compare(name)}. So two settings sharing a
 * position do not error, do not warn, and do not look wrong from either declaration. They quietly
 * sort alphabetically.
 *
 * <p>That is how the guided-run section came to have <b>thirteen settings across seven
 * positions</b>, with four collisions covering nine of them. The visible result was that the four
 * highlight settings — style, colour, thickness, feathering — were scattered across four separate
 * positions with unrelated settings between them, and the bank layout map was separated from the
 * filter its own description tells you to switch on first.
 *
 * <p>Nobody introduced that. Each setting was added with a position that was correct against the
 * ones its author was looking at; only the whole section is wrong, and only a test that sees the
 * whole section can say so.
 */
public class ConfigLayoutTest
{
	/**
	 * Two settings in one section must not claim the same position.
	 *
	 * <p>The failure message names the section and the clashing keys, because the fix is to pick a
	 * position and the useful information is which ones are already taken.
	 */
	@Test
	public void everySettingHasItsOwnPositionWithinItsSection()
	{
		List<String> clashes = new ArrayList<>();

		bySection().forEach((section, items) ->
		{
			Map<Integer, List<String>> byPosition = new LinkedHashMap<>();
			items.forEach((key, position) ->
				byPosition.computeIfAbsent(position, p -> new ArrayList<>()).add(key));

			byPosition.forEach((position, keys) ->
			{
				if (keys.size() > 1)
				{
					clashes.add(section + " position " + position + ": " + String.join(", ", keys));
				}
			});
		});

		assertEquals("settings sharing a position sort alphabetically rather than as written - "
			+ clashes, 0, clashes.size());
	}

	/**
	 * Every section a setting names has to exist.
	 *
	 * <p>{@code section} is a bare string on the annotation, so a typo puts the setting at the top
	 * level of the page rather than failing. That is hard to notice when the section it should be
	 * in is closed by default, which all the long ones are.
	 */
	@Test
	public void everySettingNamesASectionThatExists()
	{
		List<String> sections = new ArrayList<>();
		for (java.lang.reflect.Field field : DoogleMapsConfig.class.getDeclaredFields())
		{
			if (field.getAnnotation(ConfigSection.class) != null)
			{
				try
				{
					sections.add(String.valueOf(field.get(null)));
				}
				catch (IllegalAccessException e)
				{
					throw new AssertionError("could not read section constant " + field.getName(), e);
				}
			}
		}

		for (Method method : DoogleMapsConfig.class.getMethods())
		{
			ConfigItem item = method.getAnnotation(ConfigItem.class);
			if (item == null || item.section().isEmpty())
			{
				continue;
			}
			assertTrue(item.keyName() + " is in section \"" + item.section()
					+ "\", which is not one of " + sections,
				sections.contains(item.section()));
		}
	}

	/** Key name to position, grouped by the section the setting sits in. */
	private static Map<String, Map<String, Integer>> bySection()
	{
		Map<String, Map<String, Integer>> sections = new LinkedHashMap<>();
		for (Method method : DoogleMapsConfig.class.getMethods())
		{
			ConfigItem item = method.getAnnotation(ConfigItem.class);
			if (item == null)
			{
				continue;
			}
			sections
				.computeIfAbsent(item.section().isEmpty() ? "(top level)" : item.section(),
					s -> new LinkedHashMap<>())
				.put(item.keyName(), item.position());
		}
		return sections;
	}
}
