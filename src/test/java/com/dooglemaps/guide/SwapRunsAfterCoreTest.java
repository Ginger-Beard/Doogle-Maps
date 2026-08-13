package com.dooglemaps.guide;

import com.dooglemaps.DoogleMapsConfig;
import com.dooglemaps.state.SeedInventoryStore;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.events.PostMenuSort;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

/**
 * This plugin's swaps are the last word on the menu, not the first.
 *
 * <h2>The reported dead end</h2>
 *
 * The fairy ring swap did nothing — Last-destination stayed on the left click with the route
 * going through a ring, the setting on and the guard passing. Nothing in {@link GuideMenuSwap}
 * was wrong. Core's Menu Entry Swapper was doing the same job with a different answer and
 * winning, because {@code EventBus.register} breaks equal priorities on class name and
 * {@code com.dooglemaps...} sorts ahead of
 * {@code net.runelite.client.plugins.menuentryswapper...}. Our swap was made, then overwritten,
 * every single time. {@code swapFairyRing=LAST_DESTINATION} is core's default and was set in
 * every profile on the account.
 *
 * <p>Run against the real {@link EventBus} rather than against the annotation, because the
 * thing that broke was ordering and the sign of a priority is exactly the kind of detail that
 * reads correct while being backwards: a negative priority is <i>later</i>, since the
 * comparator is reversed.
 */
public class SwapRunsAfterCoreTest
{
	/**
	 * A stand-in for core's swapper: default priority, and a class name that sorts after ours.
	 *
	 * <p>{@code com.dooglemaps.guide.SwapRunsAfterCoreTest$StandInForCore} is past
	 * {@code com.dooglemaps.guide.GuideMenuSwap} on the same tie-break core's own
	 * {@code net.runelite...} wins on, so with equal priorities this would run second and
	 * clobber us — which is precisely the bug.
	 */
	public static class StandInForCore
	{
		private final List<String> order;

		StandInForCore(List<String> order)
		{
			this.order = order;
		}

		@Subscribe
		public void onPostMenuSort(PostMenuSort event)
		{
			order.add("core");
		}
	}

	@Test
	public void ourSwapRunsAfterASubscriberThatWouldOtherwiseClobberIt()
	{
		List<String> order = new ArrayList<>();

		Client client = Mockito.mock(Client.class);
		GuideTracker tracker = Mockito.mock(GuideTracker.class);
		DoogleMapsConfig config = Mockito.mock(DoogleMapsConfig.class);
		SeedInventoryStore seeds = Mockito.mock(SeedInventoryStore.class);

		when(config.guidedMode()).thenReturn(true);
		// The first thing the handler asks the client, so it records that we were reached. True
		// so it returns straight afterwards: this test is about when we run, not what we do.
		when(client.isMenuOpen()).thenAnswer(invocation ->
		{
			order.add("doogle");
			return true;
		});

		EventBus bus = new EventBus();
		bus.register(new GuideMenuSwap(client, tracker, config, seeds));
		bus.register(new StandInForCore(order));

		bus.post(new PostMenuSort());

		assertEquals("ours must be the last word on the menu", List.of("core", "doogle"), order);
	}
}
