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

package com.seibel.distanthorizons.core.util;

import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.coreapi.util.ColorUtil;


/**
 * A helper class that is used to access the data from a long
 * formatted as a render data point. <br><br>
 *
 * To access data from a long formatted as a full data point see: {@link FullDataPointUtil}
 *
 * <strong>DataPoint Format: </strong><br>
 * <code>
 * BM: block material id <br>
 * A: color alpha <br>
 * R: color red <br>
 * G: color green <br>
 * B: color blue <br>
 * H: column max Y <br>
 * D: column min Y (what Y value the column starts at) <br>
 * BL: block light <br>
 * SL: sky light <br>
 *
 * =======Bit layout (high to low)======= <br>
 * BM: 4, A: 4, R: 7, G: 7, B: 6, H: 14, D: 14, BL: 4, SL: 4 <br>
 * </code>
 *
 * @see FullDataPointUtil
 */
public class RenderDataPointUtil
{
	// Reminder: bytes have range of [-128, 127].
	// When converting to or from an int a 128 should be added or removed.
	// If there is a bug with color then it's probably caused by this.
	
	public static final boolean RUN_VALIDATION = ModInfo.IS_DEV_BUILD;
	
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	
	public final static int EMPTY_DATA = 0;

	public final static int Y_WIDTH = 14;
	public final static int RED_WIDTH = 7;
	public final static int GREEN_WIDTH = 7;
	public final static int BLUE_WIDTH = 6;

	// Four color bits are reassigned to the two vertical fields so render-data
	// points can represent worlds up to 16,383 blocks tall.
	public final static int MAX_WORLD_Y_SIZE = 1 << Y_WIDTH;
	
	public final static int ALPHA_DOWNSIZE_SHIFT = 4;
	
	
	public final static int IRIS_BLOCK_MATERIAL_ID_SHIFT = 60;
	
	public final static int BLUE_SHIFT = 36;
	public final static int GREEN_SHIFT = BLUE_SHIFT + BLUE_WIDTH;
	public final static int RED_SHIFT = GREEN_SHIFT + GREEN_WIDTH;
	public final static int ALPHA_SHIFT = RED_SHIFT + RED_WIDTH;
	public final static int COLOR_SHIFT = BLUE_SHIFT;
	
	public final static int MAX_Y_SHIFT = 22;
	public final static int MIN_Y_SHIFT = 8;
	public final static int BLOCK_LIGHT_SHIFT = 4;
	public final static int SKY_LIGHT_SHIFT = 0;
	
	public final static long ALPHA_MASK = 0xF;
	public final static long RED_MASK = (1L << RED_WIDTH) - 1L;
	public final static long GREEN_MASK = (1L << GREEN_WIDTH) - 1L;
	public final static long BLUE_MASK = (1L << BLUE_WIDTH) - 1L;
	public final static long COLOR_MASK = (1L << (RED_WIDTH + GREEN_WIDTH + BLUE_WIDTH)) - 1L;
	public final static long MAX_Y_MASK = (1L << Y_WIDTH) - 1L;
	public final static long MIN_Y_MASK = (1L << Y_WIDTH) - 1L;
	public final static long MIN_MAX_Y_MASK = (1L << (Y_WIDTH * 2)) - 1L;
	public final static long BLOCK_LIGHT_MASK = 0xF;
	public final static long SKY_LIGHT_MASK = 0xF;
	public final static long IRIS_BLOCK_MATERIAL_ID_MASK = 0xF;
	public final static long COMPARE_SHIFT = 0b111;
	
	public final static long MAX_Y_SHIFTED_MASK = MAX_Y_MASK << MAX_Y_SHIFT;
	public final static long MIN_Y_SHIFTED_MASK = MIN_Y_MASK << MIN_Y_SHIFT;
	
	public final static long VOID_SETTER = MAX_Y_SHIFTED_MASK | MIN_Y_SHIFTED_MASK;
	
	
	
	//========================//
	// datapoint manipulation //
	//========================//
	
	public static long createDataPoint(int maxY, int minY, int color, int lightSky, int lightBlock, int irisBlockMaterialId)
	{
		return createDataPoint(
				ColorUtil.getAlpha(color),
				ColorUtil.getRed(color),
				ColorUtil.getGreen(color),
				ColorUtil.getBlue(color),
				maxY, minY, lightSky, lightBlock, irisBlockMaterialId);
	}
	
	public static long createDataPoint(int alpha, int red, int green, int blue, int maxY, int minY, int lightSky, int lightBlock, int irisBlockMaterialId)
	{
		if (RUN_VALIDATION)
		{
			// assertions are inside if-blocks to prevent unnecessary string concatenations
			if (maxY < 0 || maxY >= MAX_WORLD_Y_SIZE)
			{
				LodUtil.assertNotReach("Trying to create datapoint with max Y [" + maxY + "] out of range!");
			}
			if (minY < 0 || minY >= MAX_WORLD_Y_SIZE)
			{
				LodUtil.assertNotReach("Trying to create datapoint with min Y [" + minY + "] out of range!");
			}
			
			if (lightSky < 0 || lightSky >= 16)
			{
				LodUtil.assertNotReach("Trying to create datapoint with lightSky [" + lightSky + "] out of range!");
			}
			if (lightBlock < 0 || lightBlock >= 16)
			{
				LodUtil.assertNotReach("Trying to create datapoint with lightBlock [" + lightBlock + "] out of range!");
			}
			
			if (irisBlockMaterialId < 0 || irisBlockMaterialId > IRIS_BLOCK_MATERIAL_ID_MASK)
			{
				LodUtil.assertNotReach("Trying to create datapoint with irisBlockMaterialId [" + irisBlockMaterialId + "] out of range!");
			}
			
			if (alpha < 0 || alpha >= 256)
			{
				LodUtil.assertNotReach("Trying to create datapoint with alpha [" + alpha + "] out of range!");
			}
			if (red < 0 || red >= 256)
			{
				LodUtil.assertNotReach("Trying to create datapoint with red[ " + red + "] out of range!");
			}
			if (green < 0 || green >= 256)
			{
				LodUtil.assertNotReach("Trying to create datapoint with green [" + green + "] out of range!");
			}
			if (blue < 0 || blue >= 256)
			{
				LodUtil.assertNotReach("Trying to create datapoint with blue [" + blue + "] out of range!");
			}
			
			
			if (minY > maxY)
			{
				LodUtil.assertNotReach("Trying to create datapoint with min Y [" + minY + "] greater than max Y [" + maxY + "]!");
			}
		}
		
		
		long out = (long) (alpha >>> ALPHA_DOWNSIZE_SHIFT) << ALPHA_SHIFT
				| (long) packRed(red) << RED_SHIFT
				| (long) packGreen(green) << GREEN_SHIFT
				| (long) packBlue(blue) << BLUE_SHIFT
				| (maxY & MAX_Y_MASK) << MAX_Y_SHIFT
				| (minY & MIN_Y_MASK) << MIN_Y_SHIFT
				| (lightBlock & BLOCK_LIGHT_MASK) << BLOCK_LIGHT_SHIFT
				| (lightSky & SKY_LIGHT_MASK) << SKY_LIGHT_SHIFT
				| (irisBlockMaterialId & IRIS_BLOCK_MATERIAL_ID_MASK) << IRIS_BLOCK_MATERIAL_ID_SHIFT
				;
		
		return out;
	}
	
	//public static long shiftHeightAndDepth(long dataPoint, short offset)
	//{
	//	long maxY = (dataPoint + ((long) offset << MAX_Y_SHIFT)) & MAX_Y_SHIFTED_MASK;
	//	long minY = (dataPoint + (offset << MIN_Y_SHIFT)) & MIN_Y_SHIFTED_MASK;
	//	
	//	return dataPoint & ~(MAX_Y_SHIFTED_MASK | MIN_Y_SHIFTED_MASK) | maxY | minY;
	//}
	
	/** AKA the ending/top/highest Y value above {@link ILevelWrapper#getMinHeight()} ()} */
	public static short getYMax(long dataPoint) { return (short) ((dataPoint >>> MAX_Y_SHIFT) & MAX_Y_MASK); }
	/** AKA the starting/bottom/lowest Y value above {@link ILevelWrapper#getMinHeight()} */
	public static short getYMin(long dataPoint) { return (short) ((dataPoint >>> MIN_Y_SHIFT) & MIN_Y_MASK); }
	public static long setYMin(long dataPoint, int depth) { return (dataPoint & ~(MIN_Y_MASK << MIN_Y_SHIFT)) | ((long) depth & MIN_Y_MASK) << MIN_Y_SHIFT; }
	public static long setYMax(long dataPoint, int height) { return (dataPoint & ~(MAX_Y_MASK << MAX_Y_SHIFT)) | ((long) height & MAX_Y_MASK) << MAX_Y_SHIFT; }
	
	public static short getAlpha(long dataPoint) { return (short) ((((dataPoint >>> ALPHA_SHIFT) & ALPHA_MASK) << ALPHA_DOWNSIZE_SHIFT) | 0b1111); }
	public static short getRed(long dataPoint) { return (short) unpackColor((dataPoint >>> RED_SHIFT) & RED_MASK, RED_WIDTH); }
	public static short getGreen(long dataPoint) { return (short) unpackColor((dataPoint >>> GREEN_SHIFT) & GREEN_MASK, GREEN_WIDTH); }
	public static short getBlue(long dataPoint) { return (short) unpackColor((dataPoint >>> BLUE_SHIFT) & BLUE_MASK, BLUE_WIDTH); }
	public static long setRed(long dataPoint, int red) { return (dataPoint & ~(RED_MASK << RED_SHIFT)) | (long) packRed(red) << RED_SHIFT; }
	public static long setGreen(long dataPoint, int green) { return (dataPoint & ~(GREEN_MASK << GREEN_SHIFT)) | (long) packGreen(green) << GREEN_SHIFT; }
	public static long setBlue(long dataPoint, int blue) { return (dataPoint & ~(BLUE_MASK << BLUE_SHIFT)) | (long) packBlue(blue) << BLUE_SHIFT; }
	public static long setAlpha(long dataPoint, int alpha) { return (dataPoint & ~(ALPHA_MASK << ALPHA_SHIFT)) | ((long) (alpha >>> ALPHA_DOWNSIZE_SHIFT) & ALPHA_MASK) << ALPHA_SHIFT; }
	
	public static byte getLightSky(long dataPoint) { return (byte) ((dataPoint >>> SKY_LIGHT_SHIFT) & SKY_LIGHT_MASK); }
	public static byte getLightBlock(long dataPoint) { return (byte) ((dataPoint >>> BLOCK_LIGHT_SHIFT) & BLOCK_LIGHT_MASK); }
	
	public static byte getBlockMaterialId(long dataPoint) { return (byte) ((dataPoint >>> IRIS_BLOCK_MATERIAL_ID_SHIFT) & IRIS_BLOCK_MATERIAL_ID_MASK); }
	
	
	public static boolean hasZeroHeight(long dataPoint) { return (((dataPoint >>> MIN_Y_SHIFT) & MIN_MAX_Y_MASK) == 0); }
	
	public static boolean doesDataPointExist(long dataPoint) { return dataPoint != EMPTY_DATA; }
	
	public static int getColor(long dataPoint)
	{
		return getAlpha(dataPoint) << 24 | getRed(dataPoint) << 16 | getGreen(dataPoint) << 8 | getBlue(dataPoint);
	}

	public static int packRed(int red) { return packColor(red, RED_WIDTH); }
	public static int packGreen(int green) { return packColor(green, GREEN_WIDTH); }
	public static int packBlue(int blue) { return packColor(blue, BLUE_WIDTH); }

	private static int packColor(int color, int width) { return (color & 0xFF) >>> (8 - width); }
	private static int unpackColor(long color, int width)
	{
		int value = (int) color;
		int shift = 8 - width;
		return (value << shift) | (value >>> (width - shift));
	}
	
	/** Return (>0) if dataA should replace dataB, (0) if equal, (<0) if dataB should replace dataA */
	public static int compareDatapointPriority(long dataA, long dataB) { return (int) ((dataA >> COMPARE_SHIFT) - (dataB >> COMPARE_SHIFT)); }
	
	/** This is used to convert a dataPoint to string (useful for the print function) */
	@SuppressWarnings("unused")
	public static String toString(long dataPoint)
	{
		if (!doesDataPointExist(dataPoint))
		{
			return "null";
		}
		else if (hasZeroHeight(dataPoint))
		{
			return "void";
		}
		else
		{
			return "Y+:" + getYMax(dataPoint) +
					" Y-:" + getYMin(dataPoint) +
					" argb:" + getAlpha(dataPoint) + "," + getRed(dataPoint) + "," + getGreen(dataPoint) + "," + getBlue(dataPoint) +
					" BL:" + getLightBlock(dataPoint) +
					" SL:" + getLightSky(dataPoint) +
					" MAT:" + getBlockMaterialId(dataPoint) + "["+ EDhApiBlockMaterial.getFromIndex(getBlockMaterialId(dataPoint))+"]";
		}
	}
	
	
	
}
