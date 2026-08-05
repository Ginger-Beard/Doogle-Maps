package com.dooglemaps;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Launches RuneLite in developer mode with this plugin side-loaded. Not a test — it is the
 * entry point {@code run-client.sh} uses, and it lives here because that is where RuneLite's
 * plugin template puts it.
 */
public class DoogleMapsPluginTest
{
	// loadBuiltin takes a generic varargs array, which is unchecked at every call site in every
	// RuneLite plugin. Suppressed rather than left to print a note on each build: there is
	// nothing to fix here, and a warning nobody can act on is one that hides the ones you can.
	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(DoogleMapsPlugin.class);
		RuneLite.main(args);
	}
}
