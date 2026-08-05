package com.dooglemaps.ui;

import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.Farmers;
import com.dooglemaps.data.PatchImplementation;
import java.awt.image.BufferedImage;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import javax.swing.ImageIcon;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers the bundled gardener portraits.
 *
 * <p>These are fetched by a script rather than written by hand, so what needs pinning is the
 * join between the three pieces: the farmers the world data actually refers to, the names
 * generated for them, and the PNGs on the classpath. Each is fine on its own and useless
 * without the other two, and a patch added by a future RuneLite update would introduce a
 * farmer that nothing here knows about.
 */
public class FarmerIconTest
{
	/**
	 * The one gardener with no portrait: the Tortugan who tends the coral patch.
	 *
	 * <p>Named rather than merely tolerated, so that if the wiki ever documents them the test
	 * fails and says so instead of quietly accepting the gap for good.
	 */
	private static final Set<Integer> KNOWN_MISSING = new LinkedHashSet<>(
		java.util.Collections.singletonList(NpcID.TORTUGAN_CORAL_FARMER));

	@Test
	public void everyFarmerThePluginCanShowHasAName()
	{
		Set<String> unnamed = new TreeSet<>();
		for (int farmer : farmersInUse())
		{
			if (Farmers.getName(farmer) == null && !KNOWN_MISSING.contains(farmer))
			{
				unnamed.add(String.valueOf(farmer));
			}
		}

		assertEquals("rerun tools/fetch_chatheads.py - these gardeners are new: " + unnamed,
			new TreeSet<String>(), unnamed);
	}

	@Test
	public void everyNamedFarmerHasAPortraitOnTheClasspath()
	{
		Set<String> missing = new TreeSet<>();
		for (Integer farmer : Farmers.getAll().keySet())
		{
			if (FarmerIcon.of(farmer, 18) == null)
			{
				missing.add(Farmers.getName(farmer));
			}
		}

		assertEquals("named but no sprite bundled: " + missing, new TreeSet<String>(), missing);
	}

	@Test
	public void theKnownGapIsStillAGap()
	{
		for (int farmer : KNOWN_MISSING)
		{
			assertNull("this gardener now has a portrait - drop them from KNOWN_MISSING",
				FarmerIcon.of(farmer, 18));
		}
	}

	/**
	 * The portraits are pictures, not error pages.
	 *
	 * <p>A failed download that wrote HTML, or a scaling bug, both produce an icon of exactly
	 * the right size that draws nothing — which looks identical to a patch with no farmer.
	 * Checking for ink is the only assertion that separates them.
	 */
	@Test
	public void aPortraitActuallyHasSomethingInIt()
	{
		assertHasInk("Elstan", NpcID.ELSTAN);
	}

	/**
	 * Guildmaster Jane, whose portrait is the farming contract's whole tab icon.
	 *
	 * <p>Held to the same check as a gardener's for a stronger reason. A gardener's face is a badge
	 * on a row that says plenty without it; hers <b>is</b> the tab, so a blank would leave the
	 * contract as an unlabelled gap in the strip with no way to tell what it was.
	 *
	 * <p>All three of her ids, because she is three NpcID constants for one person and which one
	 * the game reports is not something the build can find out — the sprite is bundled under each
	 * so the lookup works whichever turns up. A test naming only one would pass while the tab it
	 * covers stayed empty.
	 */
	@Test
	public void guildmasterJaneHasAFaceUnderEveryIdSheAnswersTo()
	{
		assertHasInk("Guildmaster Jane", NpcID.FARMING_GUILD_MASTER);
		assertHasInk("Guildmaster Jane", NpcID.FARMING_GUILD_MASTER_1OP);
		assertHasInk("Guildmaster Jane", NpcID.FARMING_GUILD_MASTER_2OP);
	}

	/** Asserts this NPC's portrait exists at badge size and actually draws something. */
	private static void assertHasInk(String who, int npcId)
	{
		ImageIcon icon = FarmerIcon.of(npcId, 18);
		assertNotNull(who + " should have a face", icon);
		assertEquals(18, icon.getIconWidth());
		assertEquals(18, icon.getIconHeight());

		BufferedImage image = new BufferedImage(18, 18, BufferedImage.TYPE_INT_ARGB);
		image.getGraphics().drawImage(icon.getImage(), 0, 0, null);

		int opaque = 0;
		for (int x = 0; x < 18; x++)
		{
			for (int y = 0; y < 18; y++)
			{
				if ((image.getRGB(x, y) >>> 24) > 0x40)
				{
					opaque++;
				}
			}
		}
		assertTrue(who + "'s face should cover a good part of its badge, got " + opaque + " pixels",
			opaque > 40);
	}

	/**
	 * Writes every portrait at badge size to {@code build/farmers.png}.
	 *
	 * <p>Not an assertion — whether a face is recognisable at eighteen pixels is a judgement,
	 * and this is the cheapest way to make it. Same reasoning as {@code PanelRenderTest}.
	 */
	@Test
	public void writesAContactSheet() throws Exception
	{
		int size = 18;
		int pad = 4;
		int columns = 12;
		int rows = (Farmers.getAll().size() + columns - 1) / columns;

		BufferedImage sheet = new BufferedImage(
			columns * (size + pad) + pad, rows * (size + pad) + pad, BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D g = sheet.createGraphics();
		g.setColor(new java.awt.Color(0x28, 0x28, 0x28));
		g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());

		int index = 0;
		for (Integer farmer : new TreeSet<>(Farmers.getAll().keySet()))
		{
			ImageIcon icon = FarmerIcon.of(farmer, size);
			if (icon != null)
			{
				g.drawImage(icon.getImage(),
					pad + (index % columns) * (size + pad),
					pad + (index / columns) * (size + pad), null);
			}
			index++;
		}
		g.dispose();

		java.io.File out = new java.io.File("build/farmers.png");
		out.getParentFile().mkdirs();
		javax.imageio.ImageIO.write(sheet, "png", out);
		System.out.println("wrote " + out.getAbsolutePath());
	}

	@Test
	public void aPatchWithNoFarmerAsksForNothing()
	{
		// Herb patches cannot be protected at all, so FarmPatch stores -1 for them.
		assertNull(FarmerIcon.of(-1, 18));
	}

	/** Every farmer referred to by a patch this account could ever see. */
	private static Set<Integer> farmersInUse()
	{
		Set<Integer> farmers = new LinkedHashSet<>();
		for (PatchImplementation type : PatchImplementation.values())
		{
			for (FarmPatch patch : FarmingWorldData.getPatches(type))
			{
				if (patch.getFarmer() != -1)
				{
					farmers.add(patch.getFarmer());
				}
			}
		}
		return farmers;
	}
}
