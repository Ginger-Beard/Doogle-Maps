package com.dooglemaps.state;

import com.dooglemaps.data.CompostTier;
import com.dooglemaps.data.CropState;
import com.dooglemaps.data.Produce;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The last confirmed state of one patch.
 *
 * <p>"Confirmed" means we actually saw it: the player was near enough for the game to
 * send that patch's varbit, or they acted on it. Nothing here is projected forward —
 * that is {@code GrowthTimer}'s job, which reads {@link #lastSeen} and works out where
 * the crop must have got to since.
 *
 * <p>Fields are mutable and the no-arg constructor is present because instances are
 * round-tripped through Gson.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PatchSnapshot
{
	/** The owning patch's {@code regionId.varbit} key. */
	private String patchKey;

	/** Raw varbit value, kept so a data update can re-decode without a revisit. */
	private int varbitValue;

	private Produce produce;
	private CropState cropState;
	private int stage;

	/**
	 * Compost applied to this patch, tracked separately because the patch varbit does not
	 * encode it. Reset to NONE whenever the patch empties out.
	 */
	private CompostTier compost = CompostTier.NONE;

	/** Whether a farmer has been paid to protect the current crop. */
	private boolean patchProtected;

	/** Epoch seconds when this state was last confirmed. */
	private long lastSeen;

	public Instant getLastSeenInstant()
	{
		return Instant.ofEpochSecond(lastSeen);
	}

	/** Weeds and scarecrows mean the patch is effectively free to plant in. */
	public boolean isEmpty()
	{
		return produce == null || !produce.isCrop();
	}

	public boolean needsAttention()
	{
		return cropState == CropState.DISEASED || cropState == CropState.DEAD;
	}
}
