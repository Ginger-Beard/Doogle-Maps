package com.dooglemaps.route;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Covers the one thing this plugin changes about Shortest Path's own words.
 *
 * <p>Its {@code objectInfo} column is {@code menuOption menuTarget objectId} — the values are
 * in the router's transport TSVs verbatim, which is where these fixtures come from — and the
 * id is for the router. It was reaching the panel: <i>"via Teleport Menu Fancy Jewellery Box
 * 37501 - J: Farming Guild"</i>. Nothing else about the value is touched, because the string
 * does not say where the menu option ends and the object's name begins.
 */
public class HopWordingTest
{
	@Test
	public void theObjectIdComesOffTheEnd()
	{
		assertEquals("Teleport Menu Fancy Jewellery Box",
			ShortestPathIntegration.objectName("Teleport Menu Fancy Jewellery Box 37501"));
		assertEquals("Travel Spirit tree",
			ShortestPathIntegration.objectName("Travel Spirit tree 37329"));
		assertEquals("Enter Annakarl Portal",
			ShortestPathIntegration.objectName("Enter Annakarl Portal 29341"));
		assertEquals("Edgeville Amulet of Glory",
			ShortestPathIntegration.objectName("Edgeville Amulet of Glory 13523"));
		// The POH garden ring's own row in fairy_rings.tsv - the id is the same one
		// PlayerHouse recognises the ring by, and SpFairyWordingTest takes the composed
		// wording from here.
		assertEquals("Configure Fairy ring",
			ShortestPathIntegration.objectName("Configure Fairy ring 29228"));
	}

	/**
	 * One trailing id, not every number in the value.
	 *
	 * <p>Numbers inside the wording are wording — a mushtree's menu row, a fairy ring's code —
	 * and a rule that hunted digits anywhere would eat them. The known limit of taking the last
	 * one is a value whose <i>name</i> ends in a number and carries no id; the router's own
	 * columns always end in the id, so that case does not arise in the transport files.
	 */
	@Test
	public void onlyTheLastNumberGoes()
	{
		assertEquals("Travel Magic Mushtree 3",
			ShortestPathIntegration.objectName("Travel Magic Mushtree 3 30920"));
		assertEquals("Ring of dueling(8)",
			ShortestPathIntegration.objectName("Ring of dueling(8) 2552"));
	}

	/** Values with no id, and empty ones, come back as they went in. */
	@Test
	public void anythingWithoutAnIdIsLeftAlone()
	{
		assertEquals("Spirit tree", ShortestPathIntegration.objectName("Spirit tree"));
		assertEquals("", ShortestPathIntegration.objectName(""));
	}
}
