package com.dooglemaps.ui;

import com.dooglemaps.data.CompostTier;

import com.formdev.flatlaf.FlatClientProperties;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import java.awt.Color;
import java.awt.Component;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

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
	 * <p>The tick is an explicit icon rather than a themed one — see {@link CheckIcon} for
	 * why the look and feel cannot be styled into doing it.
	 */
	static void styleCheckBox(JCheckBox box)
	{
		box.setForeground(TEXT);
		box.setIcon(CheckIcon.unchecked());
		box.setSelectedIcon(CheckIcon.checked());
		box.setDisabledIcon(CheckIcon.unchecked());
		box.setIconTextGap(6);
		style(box, NO_FOCUS_STYLE);
	}

	/**
	 * A dropdown that matches the sidebar rather than the platform.
	 *
	 * <p>Drawn rather than themed, for the same reason as the checkboxes and the scrollbar: the
	 * look and feel's shared delegates have not reliably reached components this panel creates,
	 * and a combo box that comes out in system colours is far more obvious than a checkbox
	 * doing the same.
	 */
	static void styleComboBox(JComboBox<?> box)
	{
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		box.setForeground(TEXT);
		box.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
		box.setFocusable(false);
		box.setRequestFocusEnabled(false);

		// The renderer is what the closed box and every list row are painted with, so styling
		// it covers both without touching the look and feel.
		box.setRenderer(new DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index,
				boolean selected, boolean focused)
			{
				super.getListCellRendererComponent(list, value, index, selected, focused);
				setFont(FontManager.getRunescapeSmallFont());
				setBackground(selected ? ColorScheme.MEDIUM_GRAY_COLOR : ColorScheme.DARKER_GRAY_COLOR);
				setForeground(TEXT);
				setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4));
				if (value instanceof CompostTier)
				{
					setText(((CompostTier) value).getDisplayName());
				}
				return this;
			}
		});
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
