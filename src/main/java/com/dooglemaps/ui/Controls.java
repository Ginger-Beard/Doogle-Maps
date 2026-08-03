package com.dooglemaps.ui;

import com.formdev.flatlaf.FlatClientProperties;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import java.awt.Color;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;

/** Shared setup for the interactive bits of the sidebar. */
@Slf4j
final class Controls
{
	private static final String NO_FOCUS_STYLE = "focusWidth: 0; innerFocusWidth: 0";

	/** Slightly brighter than the LAF's default text, to lift it off the dark sidebar. */
	private static final Color TEXT = new Color(0xDC, 0xDC, 0xDC);

	private Controls()
	{
	}

	/**
	 * Stops a control taking keyboard focus, and stops the look and feel drawing a focus
	 * ring around it.
	 *
	 * <p>Both matter in a RuneLite side panel. A focusable Swing control swallows key
	 * presses that should be going to the game — click a checkbox here and your movement
	 * keys stop working until you click back into the client. The focus ring is the
	 * visible symptom: FlatLaf draws it outside the component's bounds, so on a control
	 * that fills the panel width it reads as an outline around the whole panel.
	 *
	 * <p>{@code setFocusPainted(false)} is not enough — FlatLaf ignores it and uses its own
	 * focus properties.
	 */
	/** Dark, flat, non-focusable button that matches the rest of the sidebar. */
	static void styleButton(AbstractButton button)
	{
		makeNonFocusable(button);
		button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		button.setForeground(ColorScheme.TEXT_COLOR);
		button.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		button.setContentAreaFilled(true);
		button.setOpaque(true);
	}

	static void makeNonFocusable(JComponent component)
	{
		style(component, NO_FOCUS_STYLE);
	}

	/**
	 * A checkbox that is actually visible against the sidebar.
	 *
	 * <p>RuneLite's look and feel paints checkboxes with {@code icon.background} at
	 * #1E1E1E and {@code icon.borderColor} at #171717 — near-black on the near-black
	 * sidebar, so the boxes all but disappear. This lifts the box and its border, and
	 * puts a plain white tick in it: white has the most contrast against the dark box, and
	 * a tinted tick just muddies it.
	 */
	static void styleCheckBox(JCheckBox box)
	{
		box.setForeground(TEXT);
		style(box, NO_FOCUS_STYLE
			+ "; icon.background: #3C3C3C"
			+ "; icon.borderColor: #6E6E6E"
			+ "; icon.selectedBackground: #3C3C3C"
			+ "; icon.selectedBorderColor: #8E8E8E"
			+ "; icon.checkmarkColor: #FFFFFF");
	}

	/**
	 * Applies a FlatLaf style string, tolerating a key the current FlatLaf does not know.
	 *
	 * <p>FlatLaf throws on an unrecognised style key, and a cosmetic tweak must never take
	 * the panel down with it if a future version renames one.
	 */
	private static void style(JComponent component, String style)
	{
		component.setFocusable(false);
		component.setRequestFocusEnabled(false);

		if (component instanceof AbstractButton)
		{
			((AbstractButton) component).setFocusPainted(false);
		}

		try
		{
			component.putClientProperty(FlatClientProperties.STYLE, style);
		}
		catch (RuntimeException e)
		{
			log.debug("Could not apply panel styling", e);
		}
	}
}
