package com.dooglemaps.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Cursor;
import java.awt.GridLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
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
	/**
	 * Width reserved for the name, which needs more room than a number does.
	 *
	 * <p>Was 90, which was generous — "Ranarr weed" and "Potato cactus" both draw inside 70px in
	 * RuneScape Small. Eight pixels came off it when the crop table grew a fifth column: at the
	 * 225px minimum the four value columns share whatever the name leaves, and 90 left each of
	 * them exactly wide enough for "+10%" and not a pixel more, so the columns ran into each
	 * other. See the gutter in {@code addCell}.
	 */
	private static final int LABEL_WIDTH = 82;

	/**
	 * Width a value column asks for.
	 *
	 * <p>Only used to report an honest preferred size. A row will still stretch to whatever
	 * width it is given — but a table that asks for nothing gets nothing from a layout that
	 * respects preferred widths, which is how this first went out drawn zero pixels wide.
	 */
	private static final int COLUMN_WIDTH = 34;

	/**
	 * The widest a row ever asks to be, however many columns it has.
	 *
	 * <p>The same budget {@link WrappedText} uses — the 225px sidebar less the borders it sits
	 * inside. Without it a five-column table asks for {@code 90 + 4 x 34 = 226px} and pushes the
	 * whole tab past the edge of the sidebar, which is a worse failure than a narrow column: the
	 * columns share whatever width the row is actually given, so capping the <i>request</i> costs
	 * nothing at a width where the honest figure would have fitted anyway.
	 */
	private static final int MAX_WIDTH = 195;

	/** Alternating row backgrounds, a shade either side of the panel. */
	private static final Color STRIPE = ColorScheme.DARKER_GRAY_COLOR;
	private static final Color HEADER = ColorScheme.DARK_GRAY_COLOR;

	/**
	 * Header text and rules, measured against {@link #HEADER} (40,40,40).
	 *
	 * <p>{@code MEDIUM_GRAY_COLOR} (77,77,77) used to do both jobs at 1.68:1 — below WCAG AA's
	 * 4.5:1 for text and below 1.4.11's 3:1 for a meaningful graphic, i.e. the column names and
	 * the rules under them were, in practice, invisible. {@code LIGHT_GRAY_COLOR} is already the
	 * plugin's secondary-text colour (the total row, {@code WrappedText}) and measures 5.78:1
	 * here, so using it for headers is a return to house style rather than a new one. The rule
	 * only needs 3:1, so it gets a colour of its own rather than borrowing text's: 3.23:1.
	 */
	private static final Color RULE = new Color(120, 120, 120);

	private static final int ROW_HEIGHT = 15;

	private final int columns;
	private final JPanel rows = new JPanel();

	/** Built once and re-added on every clear, so it cannot drift from the column count. */
	private final JPanel headingRow;

	/**
	 * The header cell per column name, so a column can be explained without a legend.
	 *
	 * <p>A column of signed percentages needs a sentence about its sample floor that will not fit
	 * anywhere on screen. Hanging it off the header is the one place a reader looks when a column
	 * puzzles them.
	 */
	private final Map<String, JLabel> headerCells = new LinkedHashMap<>();

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
		addRow(name, tooltip, null, values);
	}

	/**
	 * Adds one row of data, colouring individual cells.
	 *
	 * <p>Per cell rather than per row because the one column that carries a colour is a signed
	 * difference, and colouring the whole row for it would say the crop was good or bad rather
	 * than that one number was above or below expectation.
	 *
	 * @param cellColours one entry per value, null for the ordinary text colour; the array itself
	 *                    may be null, and may be shorter than {@code values}
	 */
	void addRow(String name, @Nullable String tooltip, @Nullable Color[] cellColours,
		String... values)
	{
		JPanel row = row(striped ? STRIPE : getBackground());
		addName(row, name, ColorScheme.TEXT_COLOR);
		for (int i = 0; i < values.length; i++)
		{
			Color colour = cellColours != null && i < cellColours.length && cellColours[i] != null
				? cellColours[i]
				: ColorScheme.TEXT_COLOR;
			addCell(row, values[i], colour);
		}
		if (tooltip != null)
		{
			row.setToolTipText(tooltip);
		}
		rows.add(row);
		striped = !striped;
	}

	/**
	 * A full-width row that does something when it is clicked.
	 *
	 * <p>Replaces the {@code "+ 22 more"} row, which looked like a row, was not clickable, and
	 * whose only content was the news that the thing you were looking for had been left out. A
	 * row that can act on that is the same pixels doing the opposite job.
	 *
	 * <p>A button rather than a labelled panel with a mouse listener: it is a button, and the one
	 * built out of a panel would have to reimplement the keyboard, the cursor and the hit target
	 * to be one anyway. Styled flat so it still reads as a row of the table it ends.
	 */
	void addActionRow(String text, @Nullable String tooltip, Runnable onClick)
	{
		JButton row = new JButton(text);
		row.setFont(FontManager.getRunescapeSmallFont());
		row.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		row.setHorizontalAlignment(SwingConstants.LEFT);
		row.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
		row.setContentAreaFilled(false);
		row.setOpaque(false);
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT));
		row.setPreferredSize(new Dimension(rowWidth(), ROW_HEIGHT));
		Controls.makeNonFocusable(row);
		if (tooltip != null)
		{
			row.setToolTipText(tooltip);
		}
		row.addActionListener(e -> onClick.run());
		rows.add(row);
		striped = !striped;
	}

	/** Explains one column, on the header cell that names it. */
	void setColumnTooltip(String columnName, String tooltip)
	{
		JLabel cell = headerCells.get(columnName);
		if (cell != null)
		{
			cell.setToolTipText(tooltip);
		}
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
		row.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, RULE));
		rows.add(row);
	}

	private JPanel headerRow(String nameHeading, String[] columnNames)
	{
		JPanel row = row(HEADER);
		addName(row, nameHeading, ColorScheme.LIGHT_GRAY_COLOR);
		for (String name : columnNames)
		{
			headerCells.put(name, addCell(row, name, ColorScheme.LIGHT_GRAY_COLOR));
		}
		row.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, RULE));
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
		row.setPreferredSize(new Dimension(rowWidth(), ROW_HEIGHT));

		JPanel cells = new JPanel(new GridLayout(1, columns, 0, 0));
		cells.setOpaque(false);
		row.add(cells, BorderLayout.CENTER);
		return row;
	}

	private int rowWidth()
	{
		return Math.min(LABEL_WIDTH + columns * COLUMN_WIDTH, MAX_WIDTH);
	}

	/** Puts the name in the reserved left column. */
	private static void addName(JPanel row, String text, Color colour)
	{
		JLabel label = label(text, colour, SwingConstants.LEFT);
		label.setPreferredSize(new Dimension(LABEL_WIDTH, ROW_HEIGHT));
		row.add(label, BorderLayout.WEST);
	}

	/** Adds a value cell to the grid that fills the rest of the row. */
	private static JLabel addCell(JPanel row, String text, Color colour)
	{
		JLabel cell = label(text, colour, SwingConstants.RIGHT);
		// A gutter, so two full columns do not run into each other. At the 225px minimum a
		// five-column row gives each value about 30px, which "3k" and "+10%" both fill — and
		// right-aligned text with no inset puts the end of one hard against the start of the
		// next, which reads as one number.
		cell.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 2));
		((JPanel) ((BorderLayout) row.getLayout()).getLayoutComponent(BorderLayout.CENTER))
			.add(cell);
		return cell;
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
	 *
	 * <p>The hand-off to millions goes by the rounded thousands, not the raw value: 999,500
	 * rounds up to "1000k", which is exactly the five-character width the M step exists to
	 * avoid, so it reads "1M" instead. And negatives — a costed projection can be a loss —
	 * format their absolute value through the same steps behind a minus, rather than falling
	 * through to a seven-digit raw number.
	 */
	static String shortNumber(double value)
	{
		if (value < 0)
		{
			return "-" + shortNumber(-value);
		}
		long thousands = Math.round(value / 1000);
		if (thousands >= 1000)
		{
			return String.format("%.1fM", value / 1_000_000).replace(".0M", "M");
		}
		long rounded = Math.round(value);
		if (rounded >= 100_000)
		{
			return thousands + "k";
		}
		if (rounded >= 1_000)
		{
			return String.format("%.1fk", value / 1000).replace(".0k", "k");
		}
		return String.valueOf(rounded);
	}

	/**
	 * A count, thousands-separated: {@code 12043} reads as "12043" until the eye lands on it
	 * character by character, next to an xp column that abbreviates the same magnitude to
	 * "12k". {@code shortNumber} is wrong for counts — losing the last three digits of an item
	 * total is losing real information a farmer would want — so this keeps every digit and
	 * only adds the separator.
	 */
	static String count(long n)
	{
		return String.format("%,d", n);
	}
}
