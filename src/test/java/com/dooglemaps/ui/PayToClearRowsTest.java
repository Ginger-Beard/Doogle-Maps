package com.dooglemaps.ui;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.ProduceState;
import com.dooglemaps.data.Seed;
import com.dooglemaps.state.AvailabilityProfile;
import com.dooglemaps.state.PatchStateStore;
import com.dooglemaps.state.PayToClearStore;
import com.dooglemaps.state.PlantableResolver;
import com.dooglemaps.state.PlantingGroups;
import com.dooglemaps.state.ProtectedPatches;
import com.dooglemaps.state.SeedInventoryStore;
import com.dooglemaps.timer.GrowthTimer;
import com.google.gson.Gson;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JCheckBox;
import javax.swing.SwingUtilities;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.AsyncBufferedImage;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The "pay to clear" checkbox actually reaches the sidebar, on the {@code ProtectedTabTest}
 * fixture model.
 *
 * <p>{@code RunLoadoutTest} covers the coin arithmetic the loadout builds from a standing,
 * paid-for crop; this covers the other half of the same feature - the row a player actually
 * ticks. Three things could each break independently: the row might never appear over a
 * genuinely standing tree, ticking it might not reach {@link PayToClearStore}, and a patch type
 * no gardener will clear (a calquat) might show a checkbox that does nothing.
 */
public class PayToClearRowsTest
{
	@Test
	public void aStandingMagicTreeOffersItsClearCheckbox() throws Exception
	{
		Fixture fixture = new Fixture();
		fixture.standingMagicTree(0);
		fixture.refreshAndSelect(PatchImplementation.TREE);

		JCheckBox box = fixture.checkboxStartingWith("Pay to clear magic");
		assertNotNull("a standing, payable magic tree should offer the checkbox", box);
	}

	@Test
	public void tickingTheCheckboxWritesTheStore() throws Exception
	{
		Fixture fixture = new Fixture();
		fixture.standingMagicTree(0);
		fixture.refreshAndSelect(PatchImplementation.TREE);

		JCheckBox box = fixture.checkboxStartingWith("Pay to clear magic");
		assertNotNull(box);
		assertFalse("nothing should be paid for until the box is ticked",
			fixture.payToClear.isPayingFor(Seed.MAGIC));

		SwingUtilities.invokeAndWait(box::doClick);

		assertTrue("ticking the row should tell the store to pay for magic",
			fixture.payToClear.isPayingFor(Seed.MAGIC));
	}

	/** No gardener stands by a calquat patch at all - see {@code PayToClear.supports}. */
	@Test
	public void aCalquatTabOffersNoClearingCheckboxAtAll() throws Exception
	{
		Fixture fixture = new Fixture();
		fixture.refreshAndSelect(PatchImplementation.CALQUAT);

		assertNull("no gardener will clear a calquat, so there is nothing to offer",
			fixture.checkboxStartingWith("Pay to clear"));
	}

	/** Two standing trees cost twice what one does, and the label says so. */
	@Test
	public void twoStandingMagicTreesCarryTheCountInTheLabel() throws Exception
	{
		Fixture fixture = new Fixture();
		fixture.standingMagicTree(0);
		fixture.standingMagicTree(1);
		fixture.refreshAndSelect(PatchImplementation.TREE);

		JCheckBox box = fixture.checkboxStartingWith("Pay to clear magic");
		assertNotNull(box);
		assertTrue("two standing trees should carry the multiplier: " + box.getText(),
			box.getText().contains("× 2"));
	}

	/**
	 * The owner's own scenario: a player might walk up to a patch holding a crop they never
	 * planned to plant. Nothing selected, nothing standing - every tree crop should still get a
	 * row, because the list is drawn from every payable seed of the tab's patch type rather than
	 * from the selection or from what happens to be standing right now.
	 */
	@Test
	public void aTreeTabWithNothingSelectedOrStandingListsEveryTreeCrop() throws Exception
	{
		Fixture fixture = new Fixture();
		fixture.refreshAndSelect(PatchImplementation.TREE);

		for (Seed seed : Seed.forPatchType(PatchImplementation.TREE))
		{
			JCheckBox box = fixture.checkboxStartingWith(
				"Pay to clear " + seed.getName().toLowerCase());
			assertNotNull("every tree crop should offer a row, missing " + seed, box);
		}
	}

	/**
	 * A row for a crop the player has neither selected nor grown yet still has to reach the
	 * store when ticked - the whole point of listing it is that it can be paid for in advance.
	 */
	@Test
	public void tickingAnUnselectedNonStandingCropWritesTheStore() throws Exception
	{
		Fixture fixture = new Fixture();
		fixture.refreshAndSelect(PatchImplementation.TREE);

		JCheckBox box = fixture.checkboxStartingWith("Pay to clear oak");
		assertNotNull(box);
		assertFalse("nothing should be paid for until the box is ticked",
			fixture.payToClear.isPayingFor(Seed.OAK));

		SwingUtilities.invokeAndWait(box::doClick);

		assertTrue("ticking the row should tell the store to pay for oak, though it was never "
			+ "selected or standing", fixture.payToClear.isPayingFor(Seed.OAK));
	}

	/** Everything the panel needs, on the {@code ProtectedTabTest} fixture model. */
	private static final class Fixture
	{
		private final DoogleMapsPanel panel;
		private final PatchStateStore patches;
		private final AvailabilityProfile availability;
		final PayToClearStore payToClear;

		Fixture() throws Exception
		{
			net.runelite.client.ui.laf.RuneLiteLAF.setup();

			ConfigManager configManager = Mockito.mock(ConfigManager.class);
			when(configManager.getRSProfileConfiguration(anyString(), anyString())).thenReturn(null);
			when(configManager.getRSProfileConfiguration(anyString(), anyString(), eq(int.class)))
				.thenReturn(null);

			// The layout store writes globally and reads back - see ProtectedTabTest's own note.
			Map<String, Object> global = new HashMap<>();
			when(configManager.getConfiguration(anyString(), anyString(), Mockito.<Class<?>>any()))
				.thenAnswer(invocation -> global.get(invocation.getArgument(1)));
			Mockito.doAnswer(invocation ->
			{
				global.put(invocation.getArgument(1), invocation.getArgument(2));
				return null;
			}).when(configManager).setConfiguration(anyString(), anyString(), Mockito.<Object>any());
			when(configManager.getRSProfileConfiguration(
				eq("dooglemaps"), eq("farmingLevel"), eq(int.class))).thenReturn(99);

			Gson gson = new Gson();
			patches = construct(PatchStateStore.class, configManager, gson);
			availability = construct(AvailabilityProfile.class, configManager, gson, patches);
			GrowthTimer timer = construct(GrowthTimer.class, configManager);

			ItemManager itemManager = Mockito.mock(ItemManager.class);
			ClientThread clientThread = Mockito.mock(ClientThread.class);
			when(itemManager.getImage(anyInt())).thenAnswer(i -> swatch(clientThread));
			when(itemManager.getImage(anyInt(), anyInt(), Mockito.anyBoolean()))
				.thenAnswer(i -> swatch(clientThread));

			SeedInventoryStore seeds = construct(SeedInventoryStore.class,
				Mockito.mock(net.runelite.api.Client.class), configManager, gson);
			PlantableResolver resolver = construct(PlantableResolver.class, seeds);
			com.dooglemaps.state.SeedSelectionStore selection =
				construct(com.dooglemaps.state.SeedSelectionStore.class, configManager, gson,
					construct(com.dooglemaps.state.ContractState.class, configManager));

			// Every patch-type toggle on, as the interface's own defaults would have it - a plain
			// mock answers false and would render a sidebar with no tabs at all.
			DoogleMapsConfig config = Mockito.mock(DoogleMapsConfig.class, invocation ->
			{
				net.runelite.client.config.ConfigItem item = invocation.getMethod()
					.getAnnotation(net.runelite.client.config.ConfigItem.class);
				return item != null
					&& ("patchTypes".equals(item.section()) || "locations".equals(item.section()))
					? Boolean.TRUE
					: Mockito.RETURNS_DEFAULTS.answer(invocation);
			});

			ProtectedPatches protectedPatches = construct(ProtectedPatches.class, configManager);
			PlantingGroups groups = construct(PlantingGroups.class, config, protectedPatches,
				availability, construct(com.dooglemaps.state.ContractState.class, configManager));

			com.dooglemaps.route.RunPlanner runPlanner = construct(
				com.dooglemaps.route.RunPlanner.class, availability,
				construct(com.dooglemaps.route.PatchLocationStore.class, configManager, gson),
				construct(com.dooglemaps.route.BankLocationStore.class, configManager, gson),
				selection, seeds, patches, timer,
				construct(com.dooglemaps.route.ShortestPathIntegration.class,
					Mockito.mock(net.runelite.client.eventbus.EventBus.class),
					Mockito.mock(ClientThread.class)),
				construct(com.dooglemaps.state.PlayerLocation.class,
					Mockito.mock(net.runelite.api.Client.class)),
				Mockito.mock(com.dooglemaps.bank.ToolNeeds.class),
				protectedPatches, groups,
				Mockito.mock(com.dooglemaps.state.ProtectionSelectionStore.class),
				Mockito.mock(com.dooglemaps.state.RunTypeStore.class),
				Mockito.mock(com.dooglemaps.state.CompostRunStore.class),
				Mockito.mock(DoogleMapsConfig.class),
				Mockito.mock(com.dooglemaps.bank.BankContents.class),
				Mockito.mock(com.dooglemaps.guide.CarriedItems.class));

			payToClear = construct(PayToClearStore.class, configManager, gson);

			panel = construct(DoogleMapsPanel.class, patches, availability, timer, itemManager,
				config, resolver, seeds, selection, runPlanner,
				construct(com.dooglemaps.state.FarmingBonusStore.class, configManager, patches,
					itemManager, Mockito.mock(net.runelite.api.Client.class)),
				construct(com.dooglemaps.state.RunTypeStore.class, configManager, gson),
				construct(com.dooglemaps.state.CompostSelectionStore.class, configManager, gson),
				construct(com.dooglemaps.validate.HarvestStatsStore.class, configManager, gson),
				Mockito.mock(com.dooglemaps.validate.HarvestHistory.class),
				Mockito.mock(com.dooglemaps.validate.DiseaseStatsStore.class),
				Mockito.mock(com.dooglemaps.data.ItemPrices.class),
				construct(PanelLayoutStore.class, configManager),
				groups,
				construct(com.dooglemaps.state.ProtectionSelectionStore.class, configManager, gson),
				construct(com.dooglemaps.bank.BankContents.class, configManager, gson),
				construct(com.dooglemaps.guide.CarriedItems.class,
					Mockito.mock(net.runelite.api.Client.class)),
				construct(com.dooglemaps.data.ItemNames.class),
				construct(com.dooglemaps.state.ContractState.class, configManager),
				Mockito.mock(com.dooglemaps.guide.GuideTracker.class),
				construct(com.dooglemaps.state.CompostRunStore.class,
					Mockito.mock(ConfigManager.class)),
				construct(com.dooglemaps.state.RunPresetStore.class,
					Mockito.mock(ConfigManager.class), gson),
				payToClear);
		}

		/**
		 * Records a checked, standing magic tree at this tree patch index.
		 *
		 * <p>Varbit 61 is the checked, choppable state - per {@code TreeStumpTest
		 * .magicTellsItsThreeEndStatesApart} and {@code RunLoadoutTest}'s own use of the same
		 * value.
		 */
		void standingMagicTree(int index)
		{
			FarmPatch tree = FarmingWorldData.getPatches(PatchImplementation.TREE).get(index);
			ProduceState decoded = tree.getImplementation().forVarbitValue(61);
			assertNotNull("varbit 61 no longer decodes for a tree patch", decoded);
			patches.recordVarbit(tree, 61, decoded);
			availability.setAvailable(tree, true);
		}

		/** Refreshes the panel and selects this type's tab, so its rows are actually built. */
		void refreshAndSelect(PatchImplementation type) throws Exception
		{
			panel.refresh();
			SwingUtilities.invokeAndWait(() ->
			{
			});

			MaterialTab tab = tabFor(type);
			assertNotNull("no tab for " + type, tab);
			SwingUtilities.invokeAndWait(() ->
				((MaterialTabGroup) tab.getParent()).select(tab));
		}

		/** The tab for a plain (unsplit, non-contract) type, matched on its tooltip text. */
		private MaterialTab tabFor(PatchImplementation type)
		{
			List<MaterialTab> found = new ArrayList<>();
			collectTabs(panel, found);
			for (MaterialTab tab : found)
			{
				String tip = tab.getToolTipText();
				if (tip != null && tip.equalsIgnoreCase(type.getDisplayName()))
				{
					return tab;
				}
			}
			return null;
		}

		private static void collectTabs(Container root, List<MaterialTab> out)
		{
			for (Component child : root.getComponents())
			{
				if (child instanceof MaterialTab)
				{
					out.add((MaterialTab) child);
				}
				if (child instanceof Container)
				{
					collectTabs((Container) child, out);
				}
			}
		}

		/**
		 * The first visible checkbox whose text starts with this prefix, or null.
		 *
		 * <p>Stops descending into an invisible container rather than checking each leaf's own
		 * {@code isVisible()} - a hidden {@code clearPanel}'s checkboxes still answer {@code true}
		 * to that on their own, since Swing tracks a component's own flag rather than its
		 * ancestors'. See {@code ProtectedTabTest.collectButtons} for the same rule.
		 */
		JCheckBox checkboxStartingWith(String prefix)
		{
			List<JCheckBox> found = new ArrayList<>();
			collectBoxes(panel, found);
			for (JCheckBox box : found)
			{
				if (box.getText() != null && box.getText().startsWith(prefix))
				{
					return box;
				}
			}
			return null;
		}

		private static void collectBoxes(Container root, List<JCheckBox> out)
		{
			for (Component child : root.getComponents())
			{
				if (!child.isVisible())
				{
					continue;
				}
				if (child instanceof JCheckBox)
				{
					out.add((JCheckBox) child);
				}
				if (child instanceof Container)
				{
					collectBoxes((Container) child, out);
				}
			}
		}
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
}
