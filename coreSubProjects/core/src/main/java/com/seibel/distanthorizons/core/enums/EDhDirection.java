/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.enums;

import com.seibel.distanthorizons.api.enums.rendering.EDhApiDirection;
import com.seibel.distanthorizons.core.util.math.DhVec3i;

/**
 * Up <Br>
 * Down <Br>
 * North <Br>
 * South <Br>
 * East <Br>
 * West <Br>
 */
public enum EDhDirection
{
	/** negative Y */
	DOWN("down", EDhDirection.AxisDirection.NEGATIVE, EDhDirection.Axis.Y, new DhVec3i(0, -1, 0), -1, 0),
	/** positive Y */
	UP("up", EDhDirection.AxisDirection.POSITIVE, EDhDirection.Axis.Y, new DhVec3i(0, 1, 0), -1, 1),
	/** negative Z */
	NORTH("north", EDhDirection.AxisDirection.NEGATIVE, EDhDirection.Axis.Z, new DhVec3i(0, 0, -1), 0, 2),
	/** positive Z */
	SOUTH("south", EDhDirection.AxisDirection.POSITIVE, EDhDirection.Axis.Z, new DhVec3i(0, 0, 1), 1, 3),
	/** negative X */
	WEST("west", EDhDirection.AxisDirection.NEGATIVE, EDhDirection.Axis.X, new DhVec3i(-1, 0, 0), 2, 4),
	/** positive X */
	EAST("east", EDhDirection.AxisDirection.POSITIVE, EDhDirection.Axis.X, new DhVec3i(1, 0, 0), 3, 5);
	
	
	/** Up, Down, West, East, North, South */
	public static final EDhDirection[] ALL = new EDhDirection[] {
			EDhDirection.UP,
			EDhDirection.DOWN,
			EDhDirection.WEST,
			EDhDirection.EAST,
			EDhDirection.NORTH,
			EDhDirection.SOUTH
	};
	
	/** North, South, East, West */
	public static final EDhDirection[] CARDINAL_COMPASS = new EDhDirection[] {
			EDhDirection.EAST,
			EDhDirection.WEST,
			EDhDirection.SOUTH,
			EDhDirection.NORTH
	};
	
	
	
	public final String name;
	public final EDhDirection.Axis axis;
	public final EDhDirection.AxisDirection axisDirection;
	public final DhVec3i normal;
	/** -1 if not a {@link EDhDirection#CARDINAL_COMPASS} direction */
	public final int compassIndex;
	/** used for textures and LOD geometry faces */
	public final int faceIndex;
	
	
	
	//=============//
	// constructor //
	//=============//
		
	EDhDirection(String name, EDhDirection.AxisDirection axisDirection, EDhDirection.Axis axis, DhVec3i normal, int compassIndex, int faceIndex)
	{
		this.name = name;
		this.axis = axis;
		this.axisDirection = axisDirection;
		this.normal = normal;
		this.compassIndex = compassIndex;
		this.faceIndex = faceIndex;
	}
	
	
	
	//=========//
	// methods //
	//=========//
	
	public EDhDirection opposite()
	{ 
		switch(this)
		{
			case UP:
				return EDhDirection.DOWN;
			case DOWN:
				return EDhDirection.UP;
				
			case NORTH:
				return EDhDirection.SOUTH;
			case SOUTH:
				return EDhDirection.NORTH;
				
			case EAST:
				return EDhDirection.WEST;
			case WEST:
				return EDhDirection.EAST;
				
			default:
				throw new IllegalArgumentException();
		}
	}
	
	public EDhApiDirection getApiVersion()
	{
		switch (this)
		{
			case UP:
				return EDhApiDirection.UP;
			case DOWN:
				return EDhApiDirection.DOWN;
			case EAST:
				return EDhApiDirection.EAST;
			case WEST:
				return EDhApiDirection.WEST;
			case NORTH:
				return EDhApiDirection.NORTH;
			case SOUTH:
				return EDhApiDirection.SOUTH;
				
			default:
				throw new IllegalStateException("No API direction found for ["+this+"]");
		}
	}
	
	public static EDhDirection fromApiVersion(EDhApiDirection apiDirection)
	{
		switch (apiDirection)
		{
			case UP:
				return EDhDirection.UP;
			case DOWN:
				return EDhDirection.DOWN;
			case EAST:
				return EDhDirection.EAST;
			case WEST:
				return EDhDirection.WEST;
			case NORTH:
				return EDhDirection.NORTH;
			case SOUTH:
				return EDhDirection.SOUTH;
				
			default:
				throw new IllegalStateException("No Core direction found for ["+apiDirection+"]");
		}
	}
	
	@Override
	public String toString() { return this.name; }
	
	
	
	//================//
	// helper classes //
	//================//
	
	/**
	 * X <br>
	 * Y <br>
	 * Z <br>
	 */
	public enum Axis
	{
		X("x"),
		Y("y"),
		Z("z");
		
		public final String name;
		
		
		//=============//
		// constructor //
		//=============//
		
		Axis(String name) { this.name = name; }
		
		
		
		//=========//
		// methods //
		//=========//
		
		public boolean isVertical() { return this == Y; }
		public boolean isHorizontal() { return this == X || this == Z; }
		
		@Override
		public String toString() { return this.name; }
		
	}
	
	/**
	 * POSITIVE <br>
	 * NEGATIVE <br>
	 */
	public enum AxisDirection
	{
		POSITIVE(1, "Towards positive"),
		NEGATIVE(-1, "Towards negative");
		
		public final int step;
		public final String name;
		
		
		
		//=============//
		// constructor //
		//=============//
		
		AxisDirection(int newStep, String newName)
		{
			this.step = newStep;
			this.name = newName;
		}
		
		
		
		//=========//
		// methods //
		//=========//
		
		public EDhDirection.AxisDirection opposite() 
		{ return (this == POSITIVE) ? NEGATIVE : POSITIVE; }
		
		@Override
		public String toString() { return this.name; }
		
		
		
	}
	
}
