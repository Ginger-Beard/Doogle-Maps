package com.dooglemaps;

import java.util.ArrayList; // REMOVE BEFORE PUBLISHING
import java.util.List; // REMOVE BEFORE PUBLISHING
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.plugins.Plugin; // REMOVE BEFORE PUBLISHING

/**
 * Launches RuneLite in developer mode with this plugin side-loaded. Not a test — it is the
 * entry point {@code run-client.sh} uses, and it lives here because that is where RuneLite's
 * plugin template puts it.
 *
 * <p>The sibling resource-monitor plugin is loaded too, when its sources were beside this
 * repo at build time — see the source-set note in {@code build.gradle}. By name rather than
 * by import, so this launcher compiles and runs identically on a machine that has never
 * heard of it: absent classes mean a single-plugin client, not a broken one. The tagged
 * lines go once both plugins are published — see docs/TODO.md "Before publishing to the
 * Hub" — leaving the plain {@code loadBuiltin(DoogleMapsPlugin.class)} launcher behind.
 */
public class DoogleMapsPluginTest
{
	// loadBuiltin takes a generic varargs array, which is unchecked at every call site in every
	// RuneLite plugin. Suppressed rather than left to print a note on each build: there is
	// nothing to fix here, and a warning nobody can act on is one that hides the ones you can.
	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception
	{
		List<Class<?>> plugins = new ArrayList<>(); // REMOVE BEFORE PUBLISHING
		plugins.add(DoogleMapsPlugin.class); // REMOVE BEFORE PUBLISHING
		try // REMOVE BEFORE PUBLISHING
		{ // REMOVE BEFORE PUBLISHING
			plugins.add(Class.forName("com.resourcemonitor.ResourceMonitorPlugin")); // REMOVE BEFORE PUBLISHING
		} // REMOVE BEFORE PUBLISHING
		catch (ClassNotFoundException absent) // REMOVE BEFORE PUBLISHING
		{ // REMOVE BEFORE PUBLISHING
			// Not checked out beside this repo; the client runs with this plugin alone.
		} // REMOVE BEFORE PUBLISHING

		// REMOVE BEFORE PUBLISHING: revert to ExternalPluginManager.loadBuiltin(DoogleMapsPlugin.class)
		ExternalPluginManager.loadBuiltin(
			(Class<? extends Plugin>[]) plugins.toArray(new Class[0]));
		RuneLite.main(args);
	}
}
