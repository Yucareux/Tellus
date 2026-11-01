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

package com.seibel.distanthorizons.common.wrappers.world;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Objects;

import com.seibel.distanthorizons.core.wrapperInterfaces.world.IDimensionTypeWrapper;

#if MC_VER <= MC_1_7_10
// 1.7.10 has no DimensionType class — dimensions are identified by WorldProvider.dimensionId (int).
#elif MC_VER <= MC_1_12_2
import net.minecraft.world.DimensionType;
#else
import net.minecraft.world.level.dimension.DimensionType;
#endif

public class DimensionTypeWrapper implements IDimensionTypeWrapper
{
	private static final ConcurrentMap<String, DimensionTypeWrapper> DIMENSION_WRAPPER_BY_NAME = new ConcurrentHashMap<>();
	
	#if MC_VER <= MC_1_7_10
	private final int dimensionType;
	
	// on 1.7 these variables aren't stored anywhere
	// so we have to guess based on the dimension ID
	private final boolean hasCeiling;
	private final boolean hasSkyLight;
	private final double coordinateScale;
	#else
	private final DimensionType dimensionType;
	#endif
	
	private final String name;
	
	
	
	//=============//
	// Constructor //
	//=============//
	
	#if MC_VER <= MC_1_7_10
	public DimensionTypeWrapper(int dimensionType)
	#elif MC_VER <= MC_1_21_10
	public DimensionTypeWrapper(DimensionType dimensionType)
	#else
	public DimensionTypeWrapper(DimensionType dimensionType, String name)
	#endif
	{
		this.dimensionType = dimensionType;

		#if MC_VER <= MC_1_7_10
		this.name = determineName(dimensionType);
		this.hasCeiling = (dimensionType == LegacyDimensionInfo.NETHER);
		this.hasSkyLight = (dimensionType != LegacyDimensionInfo.NETHER);
		this.coordinateScale = (dimensionType == LegacyDimensionInfo.NETHER) ? 8.0 : 1.0;
		#elif MC_VER <= MC_1_21_10
		this.name = determineName(dimensionType);
		#else
		this.name = name;
		#endif
	}

	#if MC_VER <= MC_1_7_10
	public static DimensionTypeWrapper getDimensionTypeWrapper(int dimensionType)
	#elif MC_VER <= MC_1_21_10
	public static DimensionTypeWrapper getDimensionTypeWrapper(DimensionType dimensionType)
	#else
	public static DimensionTypeWrapper getDimensionTypeWrapper(DimensionType dimensionType, String name)
	#endif
	{
		#if MC_VER <= MC_1_7_10
		String dimName = LegacyDimensionInfo.fullName(dimensionType);
		#elif MC_VER <= MC_1_21_10
		String dimName = determineName(dimensionType);
		#else
		String dimName = name;
		#endif
		
		// check if the dimension has already been wrapped
		if (DIMENSION_WRAPPER_BY_NAME.containsKey(dimName) 
			&& DIMENSION_WRAPPER_BY_NAME.get(dimName) != null)
		{
			return DIMENSION_WRAPPER_BY_NAME.get(dimName);
		}
		
		
		// create the missing wrapper
		#if MC_VER <= MC_1_21_10
		DimensionTypeWrapper dimensionTypeWrapper = new DimensionTypeWrapper(dimensionType);
		#else
		DimensionTypeWrapper dimensionTypeWrapper = new DimensionTypeWrapper(dimensionType, dimName);
		#endif

		DIMENSION_WRAPPER_BY_NAME.put(dimName, dimensionTypeWrapper);
		return dimensionTypeWrapper;
	}
	#if MC_VER <= MC_1_7_10
	private static String determineName(int dimensionType)
	{
		return LegacyDimensionInfo.nameOf(dimensionType);
	}
	#else
	private static String determineName(DimensionType dimensionType)
	{
		#if MC_VER <= MC_1_12_2
		return dimensionType.getName();
		#elif MC_VER <= MC_1_16_5
		// effectsLocation() is marked as client only, so using the backing field directly
		return dimensionType.effectsLocation.getPath();
		#elif MC_VER <= MC_1_21_10
		return dimensionType.effectsLocation().getPath();
		#else
		throw new UnsupportedOperationException("As of MC 1.21.11 the dimension type no longer stores it's name and must be determined from the level.");
		#endif
	}
	#endif
	
	public static void clearMap() { DIMENSION_WRAPPER_BY_NAME.clear(); }
	
	
	
	//=================//
	// wrapper methods //
	//=================//
	
	@Override
	public String getName() { return this.name; }
	
	@Override
	public boolean hasCeiling()
	{
		#if MC_VER <= MC_1_7_10
		return this.hasCeiling;
		#elif MC_VER <= MC_1_12_2
		// 1.12.2 has no hasCeiling() - only the nether has a ceiling in vanilla
		return this.dimensionType.getId() == -1;
		#else
		return this.dimensionType.hasCeiling();
		#endif
	}

	@Override
	public boolean hasSkyLight()
	{
		#if MC_VER <= MC_1_7_10
		return this.hasSkyLight;
		#elif MC_VER <= MC_1_12_2
		// 1.12.2 DimensionType doesn't store hasSkyLight, it's in the WorldProvider instead
		return this.dimensionType != DimensionType.NETHER;
		#else
		return this.dimensionType.hasSkyLight();
		#endif
	}
	
	@Override
	public Object getWrappedMcObject() { return this.dimensionType; }
	
	@Override
	public boolean isTheEnd() { return this.getName().equalsIgnoreCase("the_end"); }
	
	@Override
	public double getCoordinateScale()
	{
		#if MC_VER <= MC_1_7_10
		return this.coordinateScale;
		#elif MC_VER <= MC_1_12_2
		// 1.12.2 DimensionType doesn't store coordinate scale, it's in the WorldProvider instead
		return this.dimensionType == net.minecraft.world.DimensionType.NETHER ? 8.0 : 1.0;
		#else
		return this.dimensionType.coordinateScale();
		#endif
	}
	
	
	
	//================//
	// base overrides //
	//================//
	
	@Override
	public boolean equals(Object obj)
	{
		if (this == obj)
		{
			return true;
		}
		else if (obj == null || obj.getClass() != DimensionTypeWrapper.class)
		{
			return false;
		}
		else
		{
			DimensionTypeWrapper other = (DimensionTypeWrapper) obj;
			return Objects.equals(other.getName(), this.getName());
		}
	}
	
	@Override
	public int hashCode() { return Objects.hash(this.getName()); }
	
	
}
