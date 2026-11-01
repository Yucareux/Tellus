package com.seibel.distanthorizons.api.enums.rendering;

/**
 * DOWN, <br>
 * UP, <br>
 * NORTH, <br>
 * SOUTH, <br>
 * WEST, <br>
 * EAST, <br>
 * 
 * @author James Seibel
 * @since API 7.1.0
 * @version 2026-09-03
 */
public enum EDhApiDirection
{
	/** negative Y */
	DOWN(0, -1, 0),
	/** positive Y */
	UP(0, 1, 0),
	/** negative Z */
	NORTH(0, 0, -1),
	/** positive Z */
	SOUTH(0, 0, 1),
	/** negative X */
	WEST(-1, 0, 0),
	/** positive X */
	EAST(1, 0, 0);
	
	
	
	public final int x;
	public final int y;
	public final int z;
	
	EDhApiDirection(int x, int y, int z)
	{
		this.x = x;
		this.y = y;
		this.z = z;
	}
	
}
