package com.dooglemaps.ui;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.state.PatchSnapshot;
import com.dooglemaps.timer.Confidence;
import com.dooglemaps.timer.DiseaseRisk;
import com.dooglemaps.timer.PatchProjection;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.time.Instant;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * One patch in the overview: what is growing, how protected it is, and how far along.
 *
 * <p>There is deliberately no "done" checkbox. The row <i>is</i> the live state of the
 * patch, so ticking things off would just be a second, staler copy of the same fact.
 */
class PatchRow extends JPanel
{
	private static final int ICON_SIZE = 20;
	private static final int BADGE_SIZE = 14;

	private static final Color IMMUNE_SHIELD = new Color(0x5A, 0x9B, 0xD5);
	private static final Color PAID_SHIELD = new Color(0x4C, 0xAF, 0x50);

	private final FarmPatch patch;
	private final ItemManager itemManager;
	private final DoogleMapsConfig config;

	private final JLabel produceIcon = new JLabel();
	private final JLabel nameLabel = new JLabel();
	private final JLabel statusLabel = new JLabel();
	private final JLabel shieldBadge = new JLabel();
	private final JLabel compostBadge = new JLabel();
	private final StagedProgressBar progressBar = new StagedProgressBar();

	PatchRow(FarmPatch patch, ItemManager itemManager, DoogleMapsConfig config)
	{
		this.patch = patch;
		this.itemManager = itemManager;
		this.config = config;

		setLayout(new BorderLayout(4, 2));
		setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
		setBackground(ColorScheme.DARKER_GRAY_COLOR);

		produceIcon.setPreferredSize(new Dimension(ICON_SIZE, ICON_SIZE));
		produceIcon.setHorizontalAlignment(SwingConstants.CENTER);

		nameLabel.setFont(FontManager.getRunescapeSmallFont());
		nameLabel.setForeground(ColorScheme.TEXT_COLOR);

		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setHorizontalAlignment(SwingConstants.RIGHT);

		JPanel badges = new JPanel();
		badges.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 2, 0));
		badges.setBackground(getBackground());
		badges.add(compostBadge);
		badges.add(shieldBadge);

		JPanel top = new JPanel(new BorderLayout(4, 0));
		top.setBackground(getBackground());
		top.add(nameLabel, BorderLayout.CENTER);
		top.add(badges, BorderLayout.EAST);

		JPanel bottom = new JPanel(new BorderLayout(4, 0));
		bottom.setBackground(getBackground());
		bottom.add(progressBar, BorderLayout.CENTER);
		bottom.add(statusLabel, BorderLayout.EAST);

		JPanel text = new JPanel(new BorderLayout(0, 3));
		text.setBackground(getBackground());
		text.add(top, BorderLayout.NORTH);
		text.add(bottom, BorderLayout.CENTER);

		add(produceIcon, BorderLayout.WEST);
		add(text, BorderLayout.CENTER);
	}

	/**
	 * Repaints the row for the current projection.
	 *
	 * @param projection the patch brought up to date, or null if never seen
	 * @param snapshot   the raw cached state, for compost and protection
	 */
	void update(@Nullable PatchProjection projection, @Nullable PatchSnapshot snapshot)
	{
		nameLabel.setText(patch.getDisplayName());

		if (projection == null)
		{
			produceIcon.setIcon(null);
			produceIcon.setToolTipText(null);
			statusLabel.setText("?");
			statusLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
			progressBar.setStage(0);
			progressBar.setStages(1);
			progressBar.setComplete(false);
			progressBar.setFillColor(ColorScheme.MEDIUM_GRAY_COLOR);
			progressBar.setToolTipText("Not seen yet - visit this patch, or cast Geomancy, to fill it in.");
			setToolTipText("Not seen yet.");
			shieldBadge.setIcon(null);
			compostBadge.setIcon(null);
			repaint();
			return;
		}

		updateProduceIcon(projection);
		updateBadges(projection, snapshot);
		updateProgress(projection);
		updateStatus(projection);

		repaint();
	}

	private void updateProduceIcon(PatchProjection projection)
	{
		int itemId = projection.getProduce().getItemID();
		// Anima seeds and Hespori have no real item icon; fall back to the tab's.
		int drawn = itemId > 0 ? itemId : patch.getType().getItemID();
		Icons.setScaled(produceIcon, itemManager.getImage(drawn), ICON_SIZE);
		produceIcon.setToolTipText(projection.isEmpty() ? "Empty" : projection.getProduce().getName());
	}

	private void updateBadges(PatchProjection projection, @Nullable PatchSnapshot snapshot)
	{
		boolean immune = DiseaseRisk.isInherentlySafe(patch, projection.getProduce());
		boolean paid = snapshot != null && snapshot.isPatchProtected();

		if (projection.isEmpty())
		{
			shieldBadge.setIcon(null);
			shieldBadge.setToolTipText(null);
		}
		else if (immune)
		{
			shieldBadge.setIcon(new ImageIcon(ShieldIcon.create(BADGE_SIZE, IMMUNE_SHIELD)));
			shieldBadge.setToolTipText("Cannot be diseased");
		}
		else if (paid)
		{
			shieldBadge.setIcon(new ImageIcon(ShieldIcon.create(BADGE_SIZE, PAID_SHIELD)));
			shieldBadge.setToolTipText("Protected - farmer paid");
		}
		else
		{
			shieldBadge.setIcon(null);
			shieldBadge.setToolTipText(DiseaseRisk.isProtectable(patch)
				? "Not protected"
				: "Cannot be protected");
		}

		CompostTier compost = snapshot == null ? CompostTier.NONE : snapshot.getCompost();
		if (compost == null || compost == CompostTier.NONE)
		{
			compostBadge.setIcon(null);
			compostBadge.setToolTipText(null);
		}
		else
		{
			Icons.setScaled(compostBadge, itemManager.getImage(compost.getItemID()), BADGE_SIZE);
			compostBadge.setToolTipText("Treated with " + compost.getDisplayName().toLowerCase());
		}
	}

	private void updateProgress(PatchProjection projection)
	{
		Confidence confidence = projection.getConfidence();
		progressBar.setFillColor(confidence.getColor());
		progressBar.setStage(projection.getStage());
		progressBar.setStages(projection.getStages());
		progressBar.setComplete(projection.getCropState() == CropState.HARVESTABLE
			|| projection.getCropState() == CropState.DEAD);
		progressBar.setToolTipText(buildTooltip(projection));
	}

	private void updateStatus(PatchProjection projection)
	{
		CropState state = projection.getCropState();
		Confidence confidence = projection.getConfidence();

		if (projection.isEmpty())
		{
			statusLabel.setText("empty");
			statusLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
			return;
		}

		switch (state)
		{
			case DEAD:
				statusLabel.setText("dead");
				statusLabel.setForeground(confidence.getColor());
				return;
			case DISEASED:
				statusLabel.setText("diseased");
				statusLabel.setForeground(confidence.getColor());
				return;
			case HARVESTABLE:
				statusLabel.setText("ready");
				statusLabel.setForeground(confidence.getColor());
				return;
			default:
				break;
		}

		if (!config.showTimers() || projection.getDoneEstimate() <= 0)
		{
			statusLabel.setText(projection.getStage() + 1 + "/" + projection.getStages());
			statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			return;
		}

		long remaining = projection.getDoneEstimate() - Instant.now().getEpochSecond();
		if (remaining <= 0)
		{
			statusLabel.setText("ready?");
			statusLabel.setForeground(confidence.getColor());
			return;
		}

		statusLabel.setText(config.absoluteTime()
			? TimeFormat.clock(projection.getDoneEstimate())
			: TimeFormat.duration(remaining));
		statusLabel.setForeground(confidence.getColor());
	}

	/** The hover text on the progress bar, where the honest caveats live. */
	private String buildTooltip(PatchProjection projection)
	{
		StringBuilder text = new StringBuilder("<html>");
		text.append("<b>").append(patch.getDisplayName()).append("</b><br>");

		if (projection.isEmpty())
		{
			text.append("Nothing planted.");
		}
		else
		{
			text.append(projection.getProduce().getName())
				.append(" - stage ").append(projection.getStage() + 1)
				.append(" of ").append(projection.getStages())
				.append("<br>");

			switch (projection.getCropState())
			{
				case DEAD:
					text.append("Dead. Needs digging up.");
					break;
				case DISEASED:
					text.append("Diseased. Cure it or it dies at the end of this cycle.");
					break;
				case HARVESTABLE:
					text.append("Ready to harvest.");
					break;
				default:
					appendTimer(text, projection);
					break;
			}
		}

		if (config.showStaleness())
		{
			text.append("<br><i>Last seen ").append(TimeFormat.since(projection.getLastSeen())).append("</i>");
		}

		return text.append("</html>").toString();
	}

	private void appendTimer(StringBuilder text, PatchProjection projection)
	{
		if (projection.getDoneEstimate() <= 0)
		{
			text.append("Not growing.");
			return;
		}

		long remaining = projection.getDoneEstimate() - Instant.now().getEpochSecond();
		if (remaining <= 0)
		{
			text.append("Should be ready by now (")
				.append(TimeFormat.clock(projection.getDoneEstimate()))
				.append(").");
		}
		else
		{
			text.append("Ready in ").append(TimeFormat.duration(remaining))
				.append(", around ").append(TimeFormat.clock(projection.getDoneEstimate()))
				.append('.');
		}

		if (projection.getConfidence() == Confidence.ESTIMATE)
		{
			text.append("<br>Unprotected, so this is a best case - it may have caught a disease "
				+ "since you last saw it.");
		}
	}
}
