package com.dooglemaps.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import javax.swing.JComponent;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import lombok.extern.slf4j.Slf4j;

/**
 * Walks the panel's Swing hierarchy and logs anything that could paint a light outline.
 *
 * <p>Swing gives no way to ask "what drew that line", and the offending component is
 * usually one the plugin never created — a scroll pane, a viewport, a look-and-feel
 * border. Rather than guess, this reports every component carrying a border that is not
 * empty, or a background light enough to show against the sidebar.
 */
@Slf4j
final class PanelDiagnostics
{
	/** Anything brighter than this stands out against the sidebar's dark grey. */
	private static final int LIGHT_THRESHOLD = 0x60;

	private PanelDiagnostics()
	{
	}

	static void report(Component root)
	{
		log.info("Doogle Maps panel diagnostics - components that could paint a light edge:");
		int found = walk(root, 0, 0);
		if (found == 0)
		{
			log.info("  (none found)");
		}
	}

	private static int walk(Component component, int depth, int found)
	{
		String indent = repeat(depth);

		if (component instanceof JComponent)
		{
			Border border = ((JComponent) component).getBorder();
			if (border != null && !(border instanceof EmptyBorder))
			{
				log.info("{}{} border={}", indent, describe(component), border.getClass().getName());
				found++;
			}
		}

		if (component.isOpaque() && isLight(component.getBackground()))
		{
			log.info("{}{} background={}", indent, describe(component), hex(component.getBackground()));
			found++;
		}

		if (component instanceof Container)
		{
			for (Component child : ((Container) component).getComponents())
			{
				found = walk(child, depth + 1, found);
			}
		}
		return found;
	}

	private static boolean isLight(Color color)
	{
		return color != null
			&& Math.max(Math.max(color.getRed(), color.getGreen()), color.getBlue()) > LIGHT_THRESHOLD;
	}

	private static String describe(Component component)
	{
		return component.getClass().getSimpleName() + "[" + component.getBounds().width
			+ "x" + component.getBounds().height + "]";
	}

	private static String hex(Color color)
	{
		return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
	}

	private static String repeat(int depth)
	{
		StringBuilder indent = new StringBuilder("  ");
		for (int i = 0; i < depth; i++)
		{
			indent.append("  ");
		}
		return indent.toString();
	}
}
