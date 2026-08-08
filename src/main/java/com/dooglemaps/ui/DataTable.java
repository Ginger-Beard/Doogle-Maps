package com.dooglemaps.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * A small table: a named left column, a header rule, striped rows and an optional total.
 *
 * <p>Extracted because two different things wanted the same table and were about to be two
 * near-copies of it — what a run is worth per crop, and what your patches have actually given
 * you per crop. The layout is the fiddly part rather than the contents: a plain grid gives a
 * crop name exactly as much room as a number, which left "Snapdragon" truncated while the
 * numbers had space going spare.
 *
 * <p>Rows are added rather than passed in as a model. The two callers disagree about
 * everything except the shape — column count, formatting, which rows are worth a tooltip — so
 * a model interface would have been a wrapper around "here is a row".
 */
class DataTable extends JPanel
{
	/** Width reserved for the name, which needs more room than a number does. */
	private static final int LABEL_WIDTH = 90;

	/**
	 * Width a value column asks for.
	 *
	 * <p>Only used to report an honest preferred size. A row will still stretch to whatever
	 * width it is given — but a table that asks for nothing gets nothing from a layout that
	 * respects preferred widths, which is how this first went out drawn zero pixels wide.
	 */
	private static final int COLUMN_WIDTH = 34;

	/** Alternating row backgrounds, a shade either side of the panel. */
	private static final Color STRIPE = ColorScheme.DARKER_GRAY_COLOR;
	private static final Color HEADER = ColorScheme.DARK_GRAY_COLOR;

	private static final int ROW_HEIGHT = 15;

	private final int columns;
	private final JPanel rows = new JPanel();

	/** Built once and re-added on every clear, so it cannot drift from the column count. */
	private final JPanel headingRow;

	/** Alternates as rows are added, so the caller never has to track it. */
	private boolean striped;

	DataTable(String nameHeading, String... columnNames)
	{
		this.columns = columnNames.length;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		rows.setBackground(getBackground());
		add(rows, BorderLayout.CENTER);

		headingRow = headerRow(nameHeading, columnNames);
		clearRows();
	}

	/** Empties the table, leaving the header in place. */
	void clearRows()
	{
		rows.removeAll();
		rows.add(headingRow);
		striped = false;
	}

	/**
	 * Adds one row of data.
	 *
	 * @param tooltip shown on hover, or null for none — this is where the detail that will not
	 *                fit in three narrow columns belongs
	 */
	void addRow(String name, @Nullable String tooltip, String... values)
	{
		JPanel row = row(striped ? STRIPE : getBackground());
		addName(row, name, ColorScheme.TEXT_COLOR);
		for (String value : values)
		{
			addCell(row, value, ColorScheme.TEXT_COLOR);
		}
		if (tooltip != null)
		{
			row.setToolTipText(tooltip);
		}
		rows.add(row);
		striped = !striped;
	}

	/** Adds the summing row, ruled off from the data above it. */
	void addTotalRow(String name, String... values)
	{
		JPanel row = row(HEADER);
		addName(row, name, ColorScheme.LIGHT_GRAY_COLOR);
		for (String value : values)
		{
			addCell(row, value, ColorScheme.LIGHT_GRAY_COLOR);
		}
		row.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, ColorScheme.MEDIUM_GRAY_COLOR));
		rows.add(row);
	}

	private JPanel headerRow(String nameHeading, String[] columnNames)
	{
		JPanel row = row(HEADER);
		addName(row, nameHeading, ColorScheme.MEDIUM_GRAY_COLOR);
		for (String name : columnNames)
		{
			addCell(row, name, ColorScheme.MEDIUM_GRAY_COLOR);
		}
		row.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR));
		return row;
	}

	/**
	 * One row: a name on the left, then a cell per column.
	 *
	 * <p>Not a plain grid — see the class comment. The name gets a fixed width and the columns
	 * share what is left.
	 */
	private JPanel row(Color background)
	{
		JPanel row = new JPanel(new BorderLayout(2, 0));
		row.setBackground(background);
		row.setOpaque(true);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		// A fixed height keeps BoxLayout from stretching the last row to fill the panel; the
		// unbounded width lets it fill one that is wider than the columns need.
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT));
		row.setPreferredSize(new Dimension(LABEL_WIDTH + columns * COLUMN_WIDTH, ROW_HEIGHT));

		JPanel cells = new JPanel(new GridLayout(1, columns, 0, 0));
		cells.setOpaque(false);
		row.add(cells, BorderLayout.CENTER);
		return row;
	}

	/** Puts the name in the reserved left column. */
	private static void addName(JPanel row, String text, Color colour)
	{
		JLabel label = label(text, colour, SwingConstants.LEFT);
		label.setPreferredSize(new Dimension(LABEL_WIDTH, ROW_HEIGHT));
		row.add(label, BorderLayout.WEST);
	}

	/** Adds a value cell to the grid that fills the rest of the row. */
	private static void addCell(JPanel row, String text, Color colour)
	{
		((JPanel) ((BorderLayout) row.getLayout()).getLayoutComponent(BorderLayout.CENTER))
			.add(label(text, colour, SwingConstants.RIGHT));
	}

	private static JLabel label(String text, Color colour, int alignment)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(colour);
		label.setHorizontalAlignment(alignment);
		return label;
	}

	/**
	 * Numbers abbreviated, so a long run or a long history does not widen a column.
	 *
	 * <p>Lives here because several tables show experience totals and all of them are squeezed
	 * into the same 225px sidebar.
	 *
	 * <p>Millions get their own step. A run's projection never reaches seven figures, but the
	 * plant-out one does the moment anyone banks a serious number of seeds — and "7279k" is both
	 * wider than the column and harder to read at a glance than "7.3M".
	 */
	static String shortNumber(double value)
	{
		long rounded = Math.round(value);
		if (rounded >= 1_000_000)
		{
			return String.format("%.1fM", value / 1_000_000).replace(".0M", "M");
		}
		if (rounded >= 100_000)
		{
			return Math.round(value / 1000) + "k";
		}
		if (rounded >= 1_000)
		{
			return String.format("%.1fk", value / 1000).replace(".0k", "k");
		}
		return String.valueOf(rounded);
	}
}
