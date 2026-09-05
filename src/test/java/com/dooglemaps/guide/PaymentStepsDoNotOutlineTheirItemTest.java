package com.dooglemaps.guide;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.data.FarmPatch;
import com.dooglemaps.data.FarmingWorldData;
import com.dooglemaps.data.PatchImplementation;
import com.dooglemaps.data.Seed;
import com.dooglemaps.state.SeedInventoryStore;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static com.dooglemaps.Construct.construct;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

/**
 * The payment for protection and the payment to clear both leave the pack through an NPC's
 * dialogue — "Pay", then "Yes." — so nothing in the inventory is ever clicked for them. Outlining
 * the coins there anyway pointed at a second target for a one-click action, alongside the
 * dialogue's own title row getting the same treatment: see {@code ClearanceDialogueHighlightTest}
 * for that half of the same screenshot.
 *
 * <p>Exercised through {@link GuideInventoryOverlay#render}, the actual path that got this wrong
 * in play, rather than against {@link GuideStep#highlightsItemInPack()} in isolation.
 */
public class PaymentStepsDoNotOutlineTheirItemTest
{
	private static final FarmPatch PATCH =
		FarmingWorldData.getPatches(PatchImplementation.ALLOTMENT).get(0);

	private Client client;
	private GuideTracker tracker;
	private ItemManager itemManager;
	private GuideInventoryOverlay overlay;
	private Graphics2D graphics;
	private int imagesDrawn;

	@Before
	public void setUp()
	{
		client = Mockito.mock(Client.class);
		tracker = Mockito.mock(GuideTracker.class);
		DoogleMapsConfig config = Mockito.mock(DoogleMapsConfig.class);
		itemManager = Mockito.mock(ItemManager.class);
		CarriedItems carried = Mockito.mock(CarriedItems.class);
		SeedInventoryStore seeds = Mockito.mock(SeedInventoryStore.class);

		overlay = construct(GuideInventoryOverlay.class, client, tracker, config, itemManager,
			carried, seeds);

		when(config.guidedMode()).thenReturn(true);
		when(config.guideHighlightColour()).thenReturn(Color.CYAN);
		when(config.dropEmptyBuckets()).thenReturn(false);
		when(tracker.getStatus()).thenReturn(GuideStatus.idle());

		// Real, tiny images so ImageUtil.fillImage — a real static call, not mocked — has
		// something to work on rather than NPEing on a Mockito-default null.
		BufferedImage outline = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		AsyncBufferedImage sprite = new AsyncBufferedImage(
			Mockito.mock(ClientThread.class), 1, 1, BufferedImage.TYPE_INT_ARGB);
		when(itemManager.getItemOutline(Mockito.anyInt(), Mockito.anyInt(), Mockito.any()))
			.thenReturn(outline);
		when(itemManager.getImage(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
			.thenReturn(sprite);

		imagesDrawn = 0;
		graphics = Mockito.mock(Graphics2D.class);
		Mockito.doAnswer(invocation ->
		{
			imagesDrawn++;
			return true;
		}).when(graphics).drawImage(Mockito.any(Image.class), Mockito.anyInt(), Mockito.anyInt(),
			Mockito.isNull());
	}

	@Test
	public void payFarmerOutlinesNothingInThePack()
	{
		inventoryHolds(ItemID.COINS, 200);
		when(tracker.getCurrentStep()).thenReturn(
			GuideStep.atNpc(GuideAction.PAY_FARMER, PATCH, ItemID.COINS, 1234, "Pay the farmer"));

		overlay.render(graphics);

		assertEquals("the payment leaves through the dialogue, not a pack click", 0, imagesDrawn);
	}

	@Test
	public void payToClearOutlinesNothingInThePack()
	{
		inventoryHolds(ItemID.COINS, 200);
		when(tracker.getCurrentStep()).thenReturn(
			GuideStep.atNpc(GuideAction.PAY_TO_CLEAR, PATCH, ItemID.COINS, 1234, "Pay to clear"));

		overlay.render(graphics);

		assertEquals("the payment leaves through the dialogue, not a pack click", 0, imagesDrawn);
	}

	/** An ordinary step — planting a seed — is untouched: its item is still outlined. */
	@Test
	public void anOrdinaryPlantStepStillOutlinesItsItem()
	{
		int seedId = Seed.POTATO.getPlantedItemID();
		inventoryHolds(seedId, 3);
		when(tracker.getCurrentStep()).thenReturn(
			GuideStep.withItem(GuideAction.PLANT, PATCH, seedId, "Plant the potato"));

		overlay.render(graphics);

		// One outline plus one filled sprite, the two draws ItemHighlight.draw always makes.
		assertEquals("an ordinary step's item is still outlined in the pack", 2, imagesDrawn);
	}

	private void inventoryHolds(int itemId, int quantity)
	{
		Widget inventory = Mockito.mock(Widget.class);
		when(inventory.isHidden()).thenReturn(false);

		Widget item = Mockito.mock(Widget.class);
		when(item.getItemId()).thenReturn(itemId);
		when(item.getItemQuantity()).thenReturn(quantity);
		when(item.getBounds()).thenReturn(new Rectangle(0, 0, 32, 32));

		when(inventory.getDynamicChildren()).thenReturn(new Widget[] {item});
		when(client.getWidget(InterfaceID.Inventory.ITEMS)).thenReturn(inventory);
	}
}
