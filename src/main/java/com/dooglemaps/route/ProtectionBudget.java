package com.dooglemaps.route;

import com.dooglemaps.data.ProtectionPayment;
import com.dooglemaps.data.Seed;
import java.util.HashMap;
import java.util.Map;

/**
 * How many patches of each crop the player can afford to protect, spent down as a run is planned.
 *
 * <p>The thing that makes seed selection actually smart. With magic and yew both picked, six
 * empty tree patches, plenty of both seeds and only 75 coconuts, the honest answer is <b>three
 * magics and three yews</b> — because 75 coconuts protects exactly three magic trees and the
 * fourth would go in unprotected, which is not what "protect these" meant.
 *
 * <p>Without this the estimate fills every patch with the best crop it has seeds for, and the
 * payment shortfall only shows up as a warning next to a plan that ignores it.
 *
 * <h2>Spent rather than merely checked</h2>
 *
 * Allocation is sequential — best crop first, then the next — so each seed has to see what is
 * <i>left</i> after the ones before it. Two crops can share a payment item, and even where they
 * do not, the same run should never promise the same coconut to two different patches.
 *
 * <p>Deliberately mutable and deliberately short-lived: one of these is built per estimate and
 * thrown away. It is a tally for a single calculation, not a store.
 */
public class ProtectionBudget
{
	/** Payments still unspent, by item id. */
	private final Map<Integer, Integer> remaining = new HashMap<>();

	/** Which crops the player asked to protect. Nothing else consumes any budget. */
	private final java.util.function.Predicate<Seed> wanted;

	public ProtectionBudget(Map<Integer, Integer> available,
		java.util.function.Predicate<Seed> wanted)
	{
		this.remaining.putAll(available);
		this.wanted = wanted;
	}

	/** Nothing is protected, and nothing is constrained. For callers that do not model it. */
	public static ProtectionBudget NONE =
		new ProtectionBudget(new HashMap<>(), seed -> false);

	/**
	 * How many patches of this crop can still be protected.
	 *
	 * <p>{@link Integer#MAX_VALUE} when protection was not asked for, or when the crop has no
	 * payment at all — an unprotected crop is not limited by anything, so it must not be capped
	 * to zero. Getting that backwards would stop the run planting anything it cannot pay for,
	 * which is the opposite of the intent.
	 */
	public int affordablePatches(Seed seed)
	{
		ProtectionPayment payment = paymentFor(seed);
		if (payment == null)
		{
			return Integer.MAX_VALUE;
		}
		return remaining.getOrDefault(payment.getItemID(), 0) / payment.getQuantity();
	}

	/** Whether this crop is being paid for, which is what makes its patches survive. */
	public boolean isProtecting(Seed seed)
	{
		return paymentFor(seed) != null;
	}

	/** Books the payments for some patches, so later crops see what is left. */
	public void spend(Seed seed, int patches)
	{
		ProtectionPayment payment = paymentFor(seed);
		if (payment == null || patches <= 0)
		{
			return;
		}

		int cost = payment.getQuantity() * patches;
		remaining.merge(payment.getItemID(), -cost, Integer::sum);
	}

	/** The payment this crop needs, or null when it is not being protected. */
	private ProtectionPayment paymentFor(Seed seed)
	{
		if (seed == null || !wanted.test(seed))
		{
			return null;
		}
		return ProtectionPayment.forSeed(seed);
	}
}
