/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 */

package com.seibel.distanthorizons.common.wrappers.world;

#if MC_VER <= MC_1_7_10

/**
 * Helpers for the int-based dimension IDs used in MC 1.7.10, where
 * {@code DimensionType} doesn't exist and dimensions are identified by
 * {@code WorldProvider.dimensionId}.
 *
 * <p>Centralising this means {@link DimensionTypeWrapper}, {@link ClientLevelWrapper}
 * and {@link ServerLevelWrapper} all derive dimension names from a single source
 * of truth, and stay consistent with the {@code "{name}:{id}"} format the rest
 * of DH uses on later MC versions.</p>
 */
public final class LegacyDimensionInfo
{
	public static final int OVERWORLD = 0;
	public static final int NETHER = -1;
	public static final int THE_END = 1;

	
	
	private LegacyDimensionInfo() {}

	
	
	/** Maps a vanilla-style dimension id to the same canonical name modern MC uses. */
	public static String nameOf(int dimensionId)
	{
		switch (dimensionId)
		{
			case OVERWORLD: return "overworld";
			case NETHER:    return "nether";
			case THE_END:   return "the_end";
			
			default:        return "DIM" + dimensionId;
		}
	}

	/**
	 * Canonical {@code "{name}:{id}"} string DH uses as the dimension identifier on later MC.
	 * Equivalent shape to the {@code MC_VER > MC_1_12_2} branches' {@code dimension().location().toString()}.
	 */
	public static String fullName(int dimensionId) { return nameOf(dimensionId) + ":" + dimensionId; }
	
	
	
}

#endif
