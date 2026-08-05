package com.dooglemaps.ui;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.state.AvailabilityProfile;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.state.PlantableResolver;
import com.dooglemaps.state.PlantingGroups;
import com.dooglemaps.state.ProtectedPatches;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.timer.GrowthTimer;
import com.google.gson.Gson;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Icon;
import javax.swing.SwingUtilities;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.util.AsyncBufferedImage;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The protected herb tab actually reaches the sidebar.
 *
 * <p>{@link PanelRenderTest} cannot cover this: its config manager answers null to everything, so
 * no unlocks are known, nothing qualifies as protected, and the split never happens — the render
 * it guards is the unsplit sidebar. That left the second herb tab with no test at all, which is
 * why "it stopped appearing" could not be answered by running anything.
 *
 * <p>Asserted on the built strip rather than on {@link PlantingGroups} alone, because the two
 * failures look identical from the outside: deciding there is no second group, and deciding there
 * is one and then not drawing it.
 */
public class ProtectedTabTest
{
	/** Hosidius, whose herb patch is disease-free on the Kourend easy diary. */
	private static final String HOSIDIUS_HERB = "6967.4774";

	/** Ardougne, which is not protected by anything. */
	private static final String ARDOUGNE_HERB = "12083.4774";

	@Test
	public void bothHerbTabsAreBuiltWhenTheSplitApplies() throws Exception
	{
		Fixture fixture = new Fixture(true);

		assertTrue("the fixture account should qualify for the split",
			fixture.groups.isSplit(PatchImplementation.HERB));

		List<String> herbTabs = fixture.herbTabTooltips();
		assertEquals("both herb tabs should be on the strip: " + herbTabs, 2, herbTabs.size());
		assertEquals("the protected tab comes first - it is the shorter list and the more "
			+ "consequential choice", "Herb (protected)", herbTabs.get(0));
	}

	/**
	 * The two tabs must not look the same.
	 *
	 * <p>They necessarily share the herb sprite, so the badge is the whole distinction. A cache
	 * keyed only on item and size would hand the second tab whatever the first had, and the strip
	 * would show two identical icons — indistinguishable from the tab having gone missing, which
	 * is exactly what it was reported as.
	 */
	@Test
	public void theProtectedTabIsBadgedAndTheOrdinaryOneIsNot() throws Exception
	{
		Fixture fixture = new Fixture(true);
		List<MaterialTab> herbTabs = new ArrayList<>();
		for (MaterialTab tab : fixture.tabs())
		{
			if (tab.getToolTipText() != null && tab.getToolTipText().startsWith("Herb"))
			{
				herbTabs.add(tab);
			}
		}

		assertEquals(2, herbTabs.size());
		int badged = opaquePixels(herbTabs.get(0).getIcon());
		int plain = opaquePixels(herbTabs.get(1).getIcon());

		assertTrue("the protected tab renders nothing at all", badged > 0);
		assertTrue("the badge should add pixels, not replace the sprite: " + badged + " vs " + plain,
			badged > plain);
	}

	/**
	 * The run list offers a line for every tab, and gains and loses it with the setting.
	 *
	 * <p>These are built from the same {@code runOptions}, but at different times: the strip is
	 * rebuilt when the unlocks arrive and the run list used to be built once, in the constructor.
	 * So the protected tab appeared at the top of the page while the run list below had no line
	 * for it — a category you could see and could not run.
	 */
	@Test
	public void theRunListOffersALineForTheProtectedTab() throws Exception
	{
		assertTrue("split on: the run list should offer it",
			new Fixture(true).runOptionLabels().contains("Herb (protected)"));
		assertFalse("split off: it must not be offered either",
			new Fixture(false).runOptionLabels().contains("Herb (protected)"));
	}

	/**
	 * A rebuild is what actually makes it appear, since the unlocks arrive after the panel.
	 *
	 * <p>Built here from an account with no unlocks known yet, then told about them — which is the
	 * real sequence, not a contrivance: quest and diary varbits are not readable until after the
	 * sidebar exists.
	 */
	@Test
	public void aLateUnlockAddsTheRunLineToo() throws Exception
	{
		Fixture fixture = new Fixture(true, 0);
		assertFalse("nothing is unlocked yet",
			fixture.runOptionLabels().contains("Herb (protected)"));

		fixture.unlockEverything();
		SwingUtilities.invokeAndWait(fixture.panel::rebuildTabs);

		assertTrue("the run list should have caught up with the strip",
			fixture.runOptionLabels().contains("Herb (protected)"));
		assertEquals("and so should the strip", 2, fixture.herbTabTooltips().size());
	}

	/**
	 * The longest run list still fits the sidebar.
	 *
	 * <p>{@link PanelRenderTest} guards the width, but only for the unsplit sidebar — its config
	 * knows no unlocks, so it never sees the extra line. The split list is the widest the panel
	 * ever gets, and it is laid out two across, which means the widest single label decides
	 * whether any of it fits: a grid gives every cell the width of the longest.
	 *
	 * <p>Anything too wide is clipped rather than wrapped, silently, which is not visible to a
	 * test that only asks what the list contains.
	 */
	@Test
	public void theSplitRunListFitsTheSidebar() throws Exception
	{
		Fixture fixture = new Fixture(true);
		SwingUtilities.invokeAndWait(() ->
		{
			fixture.panel.getWrappedPanel().setSize(SIDEBAR_WIDTH, 1400);
			layout(fixture.panel.getWrappedPanel());
		});

		StringBuilder offenders = new StringBuilder();
		findTooWide(fixture.panel, "", offenders);
		assertTrue("these want more than the " + SIDEBAR_WIDTH + "px sidebar and will be clipped:"
			+ offenders, offenders.length() == 0);
	}

	/** The sidebar's usable width, matching {@link PanelRenderTest}. */
	private static final int SIDEBAR_WIDTH = 225;

	private static void layout(java.awt.Container container)
	{
		container.doLayout();
		for (java.awt.Component child : container.getComponents())
		{
			if (child instanceof java.awt.Container)
			{
				layout((java.awt.Container) child);
			}
		}
	}

	/** Names the narrowest component that is still too wide — the actual cause. */
	private static void findTooWide(java.awt.Container container, String path, StringBuilder out)
	{
		for (java.awt.Component child : container.getComponents())
		{
			if (child.getPreferredSize().width <= SIDEBAR_WIDTH)
			{
				continue;
			}

			String here = path + "/" + child.getClass().getSimpleName();
			boolean blamedAChild = false;
			if (child instanceof java.awt.Container)
			{
				int before = out.length();
				findTooWide((java.awt.Container) child, here, out);
				blamedAChild = out.length() > before;
			}

			if (!blamedAChild)
			{
				String text = child instanceof javax.swing.AbstractButton
					? " text=\"" + ((javax.swing.AbstractButton) child).getText() + "\""
					: child instanceof javax.swing.JLabel
						? " text=\"" + ((javax.swing.JLabel) child).getText() + "\""
						: "";
				out.append("\n  ").append(here)
					.append(" wants ").append(child.getPreferredSize().width).append("px")
					.append(text);
			}
		}
	}

	/** With the setting off there is one herb tab, exactly as before any of this existed. */
	@Test
	public void theSplitIsAbsentWhenTheSettingIsOff() throws Exception
	{
		Fixture fixture = new Fixture(false);

		assertFalse(fixture.groups.isSplit(PatchImplementation.HERB));
		assertEquals(1, fixture.herbTabTooltips().size());
		assertEquals("Herb", fixture.herbTabTooltips().get(0));
	}

	/**
	 * Rebuilding the strip must not leave the old tabs behind.
	 *
	 * <p>It is rebuilt on every login and on every settings toggle that changes which tabs exist.
	 * {@code MaterialTabGroup} keeps its own list and has no way to give a tab back, so emptying
	 * it with {@code Container.removeAll} took the tabs off the screen and left every one of them
	 * — and the {@code PatchTypePanel} each holds — alive in that list.
	 */
	@Test
	public void rebuildingTheStripDoesNotAccumulateTabs() throws Exception
	{
		Fixture fixture = new Fixture(true);
		int before = fixture.tabs().size();

		for (int i = 0; i < 3; i++)
		{
			SwingUtilities.invokeAndWait(fixture.panel::rebuildTabs);
		}

		assertEquals("the strip grew across rebuilds", before, fixture.tabs().size());
	}

	/** Everything the panel needs, with the account's unlocks answered from the config. */
	private static final class Fixture
	{
		private final PlantingGroups groups;
		private final DoogleMapsPanel panel;

		/** What the config would report for the unlock flags, so it can change mid-test. */
		private int unlocks;

		Fixture(boolean separateProtectedHerbs) throws Exception
		{
			this(separateProtectedHerbs, 0b1111);
		}

		Fixture(boolean separateProtectedHerbs, int unlocks) throws Exception
		{
			this.unlocks = unlocks;
			net.runelite.client.ui.laf.RuneLiteLAF.setup();

			ConfigManager configManager = Mockito.mock(ConfigManager.class);
			when(configManager.getRSProfileConfiguration(anyString(), anyString())).thenReturn(null);
			when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(int.class)))
				.thenReturn(null);
			when(configManager.getRSProfileConfiguration(
				eq("dooglemaps"), eq("protectedHerbRegions"), eq(int.class)))
				.thenAnswer(invocation -> this.unlocks);
			when(configManager.getRSProfileConfiguration(
				eq("dooglemaps"), eq("farmingLevel"), eq(int.class))).thenReturn(99);

			Gson gson = new Gson();
			PatchStateStore store = construct(PatchStateStore.class, configManager, gson);
			AvailabilityProfile availability =
				construct(AvailabilityProfile.class, configManager, gson, store);
			GrowthTimer timer = construct(GrowthTimer.class, configManager);

			// Both patches seen, so both are available - one protected, one not.
			seed(store, ARDOUGNE_HERB);
			seed(store, HOSIDIUS_HERB);

			ItemManager itemManager = Mockito.mock(ItemManager.class);
			ClientThread clientThread = Mockito.mock(ClientThread.class);
			when(itemManager.getImage(anyInt())).thenAnswer(i -> swatch(clientThread));
			when(itemManager.getImage(anyInt(), anyInt(), Mockito.anyBoolean()))
				.thenAnswer(i -> swatch(clientThread));

			SeedInventoryStore seeds = construct(SeedInventoryStore.class,
				Mockito.mock(net.runelite.api.Client.class), configManager, gson);
			PlantableResolver resolver = construct(PlantableResolver.class, seeds);
			com.dooglemaps.state.SeedSelectionStore selection =
				construct(com.dooglemaps.state.SeedSelectionStore.class, configManager, gson);

			// Every patch-type toggle on, as the interface's own defaults would have it; a plain
			// mock answers false and would render a sidebar with no tabs at all.
			DoogleMapsConfig config = Mockito.mock(DoogleMapsConfig.class, invocation ->
			{
				net.runelite.client.config.ConfigItem item =
					invocation.getMethod().getAnnotation(net.runelite.client.config.ConfigItem.class);
				return item != null && "patchTypes".equals(item.section())
					? Boolean.TRUE
					: Mockito.RETURNS_DEFAULTS.answer(invocation);
			});
			when(config.separateProtectedHerbs()).thenReturn(separateProtectedHerbs);

			ProtectedPatches protectedPatches = construct(ProtectedPatches.class, configManager);
			groups = construct(PlantingGroups.class, config, protectedPatches, availability);

			com.dooglemaps.route.RunPlanner runPlanner = construct(
				com.dooglemaps.route.RunPlanner.class, availability,
				construct(com.dooglemaps.route.PatchLocationStore.class, configManager, gson),
				construct(com.dooglemaps.route.BankLocationStore.class, configManager, gson),
				selection, seeds, store, timer,
				construct(com.dooglemaps.route.ShortestPathIntegration.class,
					Mockito.mock(net.runelite.client.eventbus.EventBus.class),
					Mockito.mock(ClientThread.class)),
				construct(com.dooglemaps.state.PlayerLocation.class,
					Mockito.mock(net.runelite.api.Client.class)),
				Mockito.mock(com.dooglemaps.bank.ToolNeeds.class),
				protectedPatches, groups,
				Mockito.mock(com.dooglemaps.state.ProtectionSelectionStore.class),
				Mockito.mock(com.dooglemaps.state.RunTypeStore.class));

			panel = construct(DoogleMapsPanel.class, store, availability, timer, itemManager,
				config, resolver, seeds, selection, runPlanner,
				construct(com.dooglemaps.state.FarmingBonusStore.class, configManager, store,
					itemManager, Mockito.mock(net.runelite.api.Client.class)),
				construct(com.dooglemaps.state.RunTypeStore.class, configManager, gson),
				construct(com.dooglemaps.state.CompostSelectionStore.class, configManager, gson),
				construct(com.dooglemaps.validate.HarvestStatsStore.class, configManager, gson),
				Mockito.mock(com.dooglemaps.bank.RunLoadout.class),
				construct(PanelLayoutStore.class, configManager),
				groups,
				construct(com.dooglemaps.state.ProtectionSelectionStore.class, configManager, gson),
				construct(com.dooglemaps.bank.BankContents.class),
				construct(com.dooglemaps.guide.CarriedItems.class),
				construct(com.dooglemaps.data.ItemNames.class));

			panel.refresh();
			SwingUtilities.invokeAndWait(() ->
			{
			});
		}

		/** Every patch-type tab on the strip, in the order it shows them. */
		List<MaterialTab> tabs()
		{
			List<MaterialTab> found = new ArrayList<>();
			collect(panel, found);
			// The Almanac / Stats strip is tabs too; those are the ones with text on them.
			found.removeIf(tab -> tab.getText() != null && !tab.getText().isEmpty());
			return found;
		}

		/** Makes every unlock read true, the way finishing a diary eventually does. */
		void unlockEverything()
		{
			unlocks = 0b1111;
		}

		/** The labels on the run list's checkboxes, which is the list the player ticks. */
		List<String> runOptionLabels()
		{
			List<String> labels = new ArrayList<>();
			collectCheckBoxes(panel, labels);
			return labels;
		}

		private static void collectCheckBoxes(java.awt.Container root, List<String> out)
		{
			for (java.awt.Component child : root.getComponents())
			{
				if (child instanceof javax.swing.JCheckBox)
				{
					out.add(((javax.swing.JCheckBox) child).getText());
				}
				if (child instanceof java.awt.Container)
				{
					collectCheckBoxes((java.awt.Container) child, out);
				}
			}
		}

		List<String> herbTabTooltips()
		{
			List<String> tips = new ArrayList<>();
			for (MaterialTab tab : tabs())
			{
				if (tab.getToolTipText() != null && tab.getToolTipText().startsWith("Herb"))
				{
					tips.add(tab.getToolTipText());
				}
			}
			return tips;
		}

		private static void collect(java.awt.Container root, List<MaterialTab> out)
		{
			for (java.awt.Component child : root.getComponents())
			{
				if (child instanceof MaterialTab)
				{
					out.add((MaterialTab) child);
				}
				if (child instanceof java.awt.Container)
				{
					collect((java.awt.Container) child, out);
				}
			}
		}
	}

	/** How much of an icon is actually painted, which is what "the tab is missing" looks like. */
	private static int opaquePixels(Icon icon)
	{
		if (icon == null)
		{
			return 0;
		}

		BufferedImage canvas = new BufferedImage(Math.max(1, icon.getIconWidth()),
			Math.max(1, icon.getIconHeight()), BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = canvas.createGraphics();
		icon.paintIcon(null, graphics, 0, 0);
		graphics.dispose();

		int opaque = 0;
		for (int x = 0; x < canvas.getWidth(); x++)
		{
			for (int y = 0; y < canvas.getHeight(); y++)
			{
				if ((canvas.getRGB(x, y) >>> 24) > 16)
				{
					opaque++;
				}
			}
		}
		return opaque;
	}

	/** A recognisable stand-in for an item sprite. */
	private static AsyncBufferedImage swatch(ClientThread clientThread)
	{
		AsyncBufferedImage image =
			new AsyncBufferedImage(clientThread, 36, 32, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(new Color(0x7F, 0xB2, 0x4A));
		graphics.fillOval(4, 2, 28, 28);
		graphics.dispose();
		return image;
	}

	/** Records a growing herb, which is enough to make the patch count as available. */
	private static void seed(PatchStateStore store, String key)
	{
		FarmPatch patch = FarmingWorldData.getPatch(key);
		assertNotNull("fixture patch " + key + " no longer exists", patch);

		ProduceState decoded = patch.getImplementation().forVarbitValue(33);
		assertNotNull("varbit 33 no longer decodes for " + key, decoded);
		store.recordVarbit(patch, 33, decoded);
	}

	@SuppressWarnings("unchecked")
	private static <T> T construct(Class<T> type, Object... args) throws Exception
	{
		for (Constructor<?> candidate : type.getDeclaredConstructors())
		{
			if (candidate.getParameterCount() == args.length)
			{
				candidate.setAccessible(true);
				return (T) candidate.newInstance(args);
			}
		}
		throw new IllegalStateException("no constructor of arity " + args.length + " on " + type);
	}
}
