package com.dooglemaps.data;

import lombok.Value;

/**
 * One tickable line in the run's patch-type list.
 *
 * <p>Not simply a patch type, because two different questions turn out to live here. A
 * {@link PlantingGroup} says <i>which patches</i> — protected herbs are a different set from
 * ordinary ones. Harvest-only says <i>what to do with them</i>, and that is a mode rather than a
 * set: the same bush patches are involved either way.
 *
 * <h2>Why harvest-only earns its own line</h2>
 *
 * Bushes and fruit trees regrow. Once one is established you visit it to pick fruit and nothing
 * else — you are not clearing it, composting it or replanting it, and a run that offers to do
 * those things is offering to destroy a tree that took two days to grow. Ticking "Fruit tree"
 * today means the full cycle; this is the other half of what people actually do with them.
 *
 * <p>It is deliberately not the default for those types. Deciding for the player that they never
 * replant would be as wrong in the other direction, and a dead fruit tree does need clearing.
 */
@Value
public class RunOption
{
	PlantingGroup group;

	/**
	 * Whether the run only picks what is ready, leaving the patch alone otherwise.
	 *
	 * <p>Changes which patches count as worth visiting — only ripe ones — and stops the guide
	 * asking for compost or a seed once you are there.
	 */
	boolean harvestOnly;

	public static RunOption full(PlantingGroup group)
	{
		return new RunOption(group, false);
	}

	public static RunOption harvestOnly(PlantingGroup group)
	{
		return new RunOption(group, true);
	}

	/**
	 * The key this is stored under.
	 *
	 * <p>A full run over an unsplit type is the bare enum name, exactly as the run selection was
	 * stored before any of this existed — so an existing profile's ticked types load unchanged
	 * rather than being silently cleared.
	 */
	public String getKey()
	{
		return harvestOnly ? group.getKey() + "#harvest" : group.getKey();
	}

	/**
	 * The label on the checkbox.
	 *
	 * <p>Abbreviated because the list is two columns wide in a 225px sidebar, and "Fruit tree
	 * (harvest only)" does not fit in half of that — spelled out, it forced the whole list into a
	 * single column and made it twice as tall as it needed to be. What it means is in the tooltip,
	 * which the line needs anyway: "harvest only" is not self-explanatory either.
	 */
	public String getLabel()
	{
		return harvestOnly ? group.getDisplayName() + " (H/O)" : group.getDisplayName();
	}

	public PatchImplementation getType()
	{
		return group.getType();
	}
}
