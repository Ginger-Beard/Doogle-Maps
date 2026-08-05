package com.dooglemaps.ui;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.CropXp;
import com.dooglemaps.data.Seed;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.Farmers;
import com.dooglemaps.state.FarmingBonusStore;
import com.dooglemaps.state.PatchSnapshot;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.timer.FarmingBonuses;
import com.dooglemaps.timer.CropYieldModel;
import com.dooglemaps.timer.DiaryBonus;
import com.dooglemaps.timer.YieldEstimate;
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

	/**
	 * A little larger than the other badges, because a face needs the pixels.
	 *
	 * <p>A drawn shield reads fine at 14; a chathead at 14 is a smudge. Two more pixels is
	 * the difference between recognising Elstan and seeing a blob, and the row is laid out
	 * around the 20px produce icon anyway, so it costs no height.
	 */
	private static final int FACE_SIZE = 18;

	private static final Color IMMUNE_SHIELD = new Color(0x5A, 0x9B, 0xD5);
	private static final Color PAID_SHIELD = new Color(0x4C, 0xAF, 0x50);

	private final FarmPatch patch;
	private final ItemManager itemManager;
	private final DoogleMapsConfig config;
	private final SeedInventoryStore seeds;
	private final FarmingBonusStore bonuses;

	private final JLabel produceIcon = new JLabel();
	private final JLabel nameLabel = new JLabel();
	private final JLabel statusLabel = new JLabel();
	private final JLabel shieldBadge = new JLabel();
	private final JLabel compostBadge = new JLabel();
	private final StagedProgressBar progressBar = new StagedProgressBar();

	PatchRow(FarmPatch patch, ItemManager itemManager, DoogleMapsConfig config,
		SeedInventoryStore seeds, FarmingBonusStore bonuses)
	{
		this.patch = patch;
		this.itemManager = itemManager;
		this.config = config;
		this.seeds = seeds;
		this.bonuses = bonuses;

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
	 * Whether this patch is switched off, which the row shows rather than hides.
	 *
	 * <p>A switched-off patch used to disappear from here and reappear as a checkbox in a second
	 * list underneath. Two lists of the same patches, one to read and one to edit, and you had to
	 * open the second to discover why something was missing from the first.
	 */
	private boolean off;

	/** Told when the row is clicked, so the panel can flip the patch. */
	private Runnable onToggle;

	void setOnToggle(Runnable onToggle)
	{
		if (this.onToggle == null)
		{
			// The listener is added once, not per refresh. Rows are cached and reused, and adding
			// one each time meant a single click firing as many times as the row had been drawn.
			listenForClicks(this);
			setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		}
		this.onToggle = onToggle;
	}

	/**
	 * Listens on the row <b>and every part of it</b>.
	 *
	 * <h2>Why the row alone is not enough</h2>
	 *
	 * Swing delivers a click to the deepest component under the cursor that is listening, and
	 * walks up to a parent only when nothing below it is. That is usually enough — plain labels
	 * listen to nothing, so a click on the text reaches the panel behind it.
	 *
	 * <p>These labels are not plain. {@link javax.swing.JComponent#setToolTipText} quietly
	 * registers {@code ToolTipManager} as a mouse listener on the component, and {@link #update}
	 * puts a tooltip on the produce icon, the progress bar and both badges — which between them
	 * cover nearly the whole row. So every click landed on something that was listening for its
	 * own reasons and went no further, and the row's own listener fired only on the few pixels of
	 * bare panel in the gaps. From play that is indistinguishable from the click doing nothing,
	 * which is exactly how it was reported.
	 *
	 * <p>Attaching to the descendants sidesteps the question rather than reasoning about which of
	 * them happens to have a tooltip this frame. Only one fires per click — the deepest — so the
	 * toggle still runs exactly once.
	 */
	private void listenForClicks(java.awt.Component component)
	{
		component.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseReleased(java.awt.event.MouseEvent e)
			{
				// Released, not clicked. AWT only synthesises MOUSE_CLICKED when the pointer has
				// not moved between press and release — one pixel of drift makes it a drag
				// instead, and the click is never delivered. On a trackpad, or any mouse held
				// loosely, that is most clicks, which is how this read as "sometimes nothing
				// happens".
				//
				// Still bounded by the row: pressing here and releasing somewhere else is a drag
				// the player aborted, and should not toggle anything.
				java.awt.Point onRow = javax.swing.SwingUtilities.convertPoint(
					e.getComponent(), e.getPoint(), PatchRow.this);
				if (contains(onRow) && onToggle != null)
				{
					onToggle.run();
				}
			}
		});

		if (component instanceof java.awt.Container)
		{
			for (java.awt.Component child : ((java.awt.Container) component).getComponents())
			{
				listenForClicks(child);
			}
		}
	}

	void setOff(boolean off)
	{
		this.off = off;
		repaint();
	}

	/**
	 * Washes the row red when the patch is switched off.
	 *
	 * <p>Painted over the finished row rather than by restyling its parts. The row has a dozen
	 * coloured pieces — the progress bar, the status text, two badges — and grey-out would have
	 * meant a disabled variant of each, which is both a lot of code and a worse result: the point
	 * is that you can still read the patch's state while seeing it is not in your run.
	 */
	@Override
	protected void paintChildren(java.awt.Graphics graphics)
	{
		super.paintChildren(graphics);
		if (!off)
		{
			return;
		}

		java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
		try
		{
			g.setColor(OFF_WASH);
			g.fillRect(0, 0, getWidth(), getHeight());
		}
		finally
		{
			g.dispose();
		}
	}

	/** Red enough to read as "excluded", light enough to leave the row legible underneath. */
	private static final Color OFF_WASH = new Color(0xC0, 0x39, 0x2B, 0x4D);

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
		updateProgress(projection, snapshot);
		updateStatus(projection, snapshot);

		repaint();
	}

	private void updateProduceIcon(PatchProjection projection)
	{
		int itemId = projection.getProduce().getItemID();
		// Anima seeds and Hespori have no real item icon; fall back to the tab's.
		int drawn = itemId > 0 ? itemId : patch.getType().getItemID();
		Icons.setScaled(produceIcon, drawn, itemManager.getImage(drawn), ICON_SIZE);
		produceIcon.setToolTipText(projection.isEmpty() ? "Empty" : projection.getProduce().getName());
	}

	/**
	 * Names the gardener who was paid, where we know them.
	 *
	 * <p>Worth saying rather than "farmer paid": which gardener it is tells you who to look
	 * for when you get there, and it is the one thing the badge cannot spell out.
	 */
	private String describeProtection()
	{
		String farmer = Farmers.getName(patch.getFarmer());
		return farmer == null ? "Protected - farmer paid" : "Protected - " + farmer + " paid";
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
			// The face of whoever was actually paid, rather than the same shield on all
			// forty-nine patches - the plugin has always known which gardener it is. Falls
			// back to the shield for the one farmer with no portrait, and for any added later.
			ImageIcon farmer = FarmerIcon.of(patch.getFarmer(), FACE_SIZE);
			shieldBadge.setIcon(farmer != null
				? farmer
				: new ImageIcon(ShieldIcon.create(BADGE_SIZE, PAID_SHIELD)));
			shieldBadge.setToolTipText(describeProtection());
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
			Icons.setScaled(compostBadge, compost.getItemID(), itemManager.getImage(compost.getItemID()), BADGE_SIZE);
			compostBadge.setToolTipText("Treated with " + compost.getDisplayName().toLowerCase());
		}
	}

	private void updateProgress(PatchProjection projection, @Nullable PatchSnapshot snapshot)
	{
		Confidence confidence = projection.getConfidence();
		progressBar.setFillColor(confidence.getColor());
		progressBar.setStage(projection.getStage());
		progressBar.setStages(projection.getStages());
		progressBar.setComplete(projection.getCropState() == CropState.HARVESTABLE
			|| projection.getCropState() == CropState.DEAD);
		progressBar.setToolTipText(buildTooltip(projection, snapshot));
	}

	private void updateStatus(PatchProjection projection, @Nullable PatchSnapshot snapshot)
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
				// A grown tree that has been picked clean is not "ready" for anything — say
				// when the next fruit arrives instead.
				if (projection.regrows() && projection.getLivesRemaining() == 0)
				{
					long untilFruit = projection.getRegrowEstimate() - Instant.now().getEpochSecond();
					statusLabel.setText(untilFruit > 0 ? TimeFormat.duration(untilFruit) : "soon");
					statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
					return;
				}

				// Two different numbers, and they must not be confused. For a crop that
				// regrows, the count is literally how much fruit is hanging on the plant, so
				// "x4" is a fact. For a herb or an allotment there is no such count — what
				// you get is a distribution, and the honest figure is the expectation, so it
				// is shown as an approximation and only once the level is known.
				Double expected = expectedYield(projection, snapshot);
				if (projection.regrows() && projection.getLivesRemaining() > 1)
				{
					statusLabel.setText("ready x" + projection.getLivesRemaining());
				}
				else if (expected != null)
				{
					statusLabel.setText("ready ~" + Math.round(expected));
				}
				else
				{
					statusLabel.setText("ready");
				}
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
	private String buildTooltip(PatchProjection projection, @Nullable PatchSnapshot snapshot)
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
					appendHarvestDetail(text, projection, snapshot);
					break;
				default:
					appendTimer(text, projection);
					break;
			}

			if (projection.getCropState() != CropState.DEAD)
			{
				appendExperience(text, projection, snapshot);
			}
		}

		if (config.showStaleness())
		{
			text.append("<br><i>Last seen ").append(TimeFormat.since(projection.getLastSeen())).append("</i>");
		}

		return text.append("</html>").toString();
	}

	/**
	 * What "ready" is worth, which differs sharply by crop.
	 *
	 * <p>A regrowing crop has a real count: six coconuts on a palm are six coconuts. A herb
	 * patch does not — its three "lives" are a floor, and compost, magic secateurs and level
	 * routinely take the actual harvest well past it. Saying "3 harvests left" there would be
	 * wrong in the direction that matters.
	 */
	private void appendHarvestDetail(StringBuilder text, PatchProjection projection,
		@Nullable PatchSnapshot snapshot)
	{
		if (projection.regrows())
		{
			int stock = projection.getLivesRemaining();
			text.append("<br>").append(stock == 0 ? "Nothing to pick yet." : stock + " ready to pick now.");

			if (projection.isRegrowing())
			{
				long until = projection.getRegrowEstimate() - Instant.now().getEpochSecond();
				text.append("<br>Another grows back in ")
					.append(TimeFormat.duration(Math.max(until, 0)))
					.append(" — leave the patch to keep it producing.");
			}
			else
			{
				text.append("<br>Fully stocked — nothing more will grow until you pick some.");
			}
			return;
		}

		Double expected = expectedYield(projection, snapshot);
		if (expected == null)
		{
			int lives = projection.getLivesRemaining();
			if (lives > 1)
			{
				text.append("<br>At least ").append(lives)
					.append(" harvests; compost, magic secateurs and your Farming level all add more.");
			}
			return;
		}

		int lives = YieldEstimate.lives(compostOf(snapshot));
		// One decimal, because whole numbers hide differences that matter. An 8.7 and a 9.2
		// both round to 9, which made a Hosidius patch with the Kourend diary look identical
		// to a Weiss patch without it - the diary is worth about half a herb at level 80.
		text.append("<br>Expect about <b>").append(String.format("%.1f", expected))
			.append("</b>, ").append(lives).append(" guaranteed.")
			// There is genuinely no ceiling - a run of saved lives can go on indefinitely -
			// so a "max" would be a fiction. Say what drives it instead.
			.append("<br><i>").append(bonusSummary()).append("</i>");
	}

	/**
	 * How many items this patch should actually give, or null if that cannot be said.
	 *
	 * <p>Only the lives-based crops have published constants, and only while the patch is
	 * standing there fully grown does the answer mean anything. A Farming level of 0 means
	 * the plugin has never seen the player logged in, and guessing one would be worse than
	 * staying quiet.
	 */
	@Nullable
	private Double expectedYield(PatchProjection projection, @Nullable PatchSnapshot snapshot)
	{
		Seed seed = Seed.forProduce(projection.getProduce());
		int level = seeds.getFarmingLevel();
		if (seed == null || level <= 0 || !CropYieldModel.hasMeaningfulYield(seed))
		{
			return null;
		}
		return CropYieldModel.expected(seed, level, compostOf(snapshot), currentBonuses());
	}

	private static CompostTier compostOf(@Nullable PatchSnapshot snapshot)
	{
		return snapshot == null || snapshot.getCompost() == null
			? CompostTier.NONE
			: snapshot.getCompost();
	}

	/**
	 * The bonuses in play for <i>this</i> patch.
	 *
	 * <p>Includes the diary reward for this patch, which is why it is looked up per row.
	 */
	private FarmingBonuses currentBonuses()
	{
		// Per patch, not per player: the Kandarin and Kourend diaries improve three specific
		// herb patches and nothing else, so a global figure would be wrong for exactly the
		// patches people care most about.
		return bonuses.forPatch(patch);
	}

	/** Names what the estimate assumed, so a surprising number can be traced. */
	private String bonusSummary()
	{
		FarmingBonuses current = currentBonuses();
		StringBuilder parts = new StringBuilder("At level ").append(seeds.getFarmingLevel());
		if (current.isMagicSecateurs())
		{
			parts.append(", magic secateurs");
		}
		if (current.isFarmingCape())
		{
			parts.append(", Farming cape");
		}
		if (current.isAttas())
		{
			parts.append(", attas");
		}

		// Named whether it applied or not. This is the only bonus tied to where the patch is,
		// so "why is this one no better than the other" is a question only this can answer.
		if (DiaryBonus.isEligible(patch))
		{
			parts.append(current.getDiaryBonus() > 0
				? ", diary +" + current.getDiaryBonus()
				: ", no diary here yet");
		}
		return parts.toString();
	}

	/**
	 * What this crop pays in Farming experience.
	 *
	 * <p>Reported per award rather than as one total, because the awards behave nothing
	 * alike and a single figure would hide the difference. A tree's entire payout is the
	 * one check-health click — over 13,000 for a magic tree, and its logs give Woodcutting
	 * afterwards, not Farming. A herb's arrives per pick, so its total depends on how many
	 * picks you get, which is a yield we cannot compute honestly yet. Stating the rate is
	 * both useful and true; stating a total would be neither.
	 */
	private void appendExperience(StringBuilder text, PatchProjection projection,
		@Nullable PatchSnapshot snapshot)
	{
		CropXp xp = CropXp.forProduce(projection.getProduce());
		if (xp == null)
		{
			// Fruit trees land here: the wiki gives them one unlabelled number that could be
			// the check award or a total, and a wrong guess is worse than no line at all.
			return;
		}

		if (xp.getCheckXp() > 0)
		{
			text.append("<br>Check health: ").append(experience(xp.getCheckXp())).append(" xp");
			if (xp.getHarvestXp() > 0)
			{
				text.append(", then ").append(experience(xp.getHarvestXp())).append(" xp each");
			}
		}
		else if (xp.getHarvestXp() > 0)
		{
			text.append("<br>").append(experience(xp.getHarvestXp())).append(" xp per harvest");
		}

		// The figure a player actually wants: what this patch is worth in total. Only offered
		// where the harvest count is known - either because the crop pays nothing per pick, or
		// because its yield can be estimated. Anywhere else a "total" would be a guess dressed
		// up as arithmetic.
		Double expected = xp.getHarvestXp() > 0 ? expectedYield(projection, snapshot) : Double.valueOf(0);
		if (expected != null)
		{
			text.append("<br><b>~")
				.append(experience(currentBonuses().applyOutfit(xp.totalFor(expected))))
				.append(" xp</b> for the patch");
			if (xp.getHarvestXp() > 0)
			{
				text.append(", at ~").append(Math.round(expected)).append(" harvested");
			}
		}
	}

	/** Experience with a thousands separator, and no trailing {@code .0} on whole numbers. */
	private static String experience(double xp)
	{
		return xp == Math.rint(xp)
			? String.format("%,d", (long) xp)
			: String.format("%,.1f", xp);
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
