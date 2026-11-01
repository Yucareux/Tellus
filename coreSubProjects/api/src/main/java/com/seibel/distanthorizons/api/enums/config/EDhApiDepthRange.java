package com.seibel.distanthorizons.api.enums.config;

/**
 * ZERO_TO_POS_ONE, <br>
 * NEG_ONE_TO_POS_ONE, <br>
 *
 * @since API 7.2.0
 * @version 2026-09-19
 */
public enum EDhApiDepthRange
{
	/**
	 * [0, 1] <br>
	 * 0 can be close or far based on {@link EDhApiDepthDirection}
	 */
	ZERO_TO_POS_ONE,
	/**
	 * [-1, 1] <br>
	 * -1 can be close or far based on {@link EDhApiDepthDirection}
	 */
	NEG_ONE_TO_POS_ONE;
	
	
	
}
