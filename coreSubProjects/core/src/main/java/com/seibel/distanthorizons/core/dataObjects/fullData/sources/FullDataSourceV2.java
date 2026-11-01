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

package com.seibel.distanthorizons.core.dataObjects.fullData.sources;

import com.seibel.distanthorizons.api.enums.config.EDhApiWorldCompressionMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.dataObjects.transformers.FullDataOcclusionCuller;
import com.seibel.distanthorizons.core.dataObjects.transformers.LodDataBuilder;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.AbstractPhantomArrayList;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListPool;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.sql.dto.util.FullDataMinMaxPosUtil;
import com.seibel.distanthorizons.core.util.*;
import com.seibel.distanthorizons.core.util.objects.DataCorruptedException;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.coreapi.ModInfo;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * This data source contains every datapoint over its given {@link DhSectionPos}. <br><br>
 * 
 * @see FullDataPointUtil
 * @see FullDataSourceV1
 */
public class FullDataSourceV2 
		extends AbstractPhantomArrayList
		implements IDhApiFullDataSource
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	/** useful for debugging, but can slow down update operations quite a bit due to being called so often. */
	private static final boolean RUN_UPDATE_DEV_VALIDATION = false;
	/** 
	 * If the data column order isn't correct
	 * block lighting may appear broken 
	 * and/or certain detail level LODs may not appear at all. 
	 */
	private static final boolean RUN_DATA_ORDER_VALIDATION = ModInfo.IS_DEV_BUILD;
	
	/** measured in data columns */
	public static final int WIDTH = 64;
	/** how many chunks wide this datasource is at detail level 0. */
	public static final int NUMB_OF_CHUNKS_WIDE = WIDTH / LodUtil.CHUNK_WIDTH;
	
	private static final PhantomArrayListPool ARRAY_LIST_POOL = new PhantomArrayListPool("FullDataV2");
	
	private static final ThreadLocal<FullDataPointIdMap> DATA_MAP_FOR_REMAPPING_REF = ThreadLocal.withInitial(() -> new FullDataPointIdMap(DhSectionPos.encode((byte)0, 0, 0)));
	
	
	
	private int cachedHashCode = 0;
	
	private final long pos;
	
	
	public final FullDataPointIdMap mapping;
	
	
	public long lastModifiedUnixDateTime;
	public long createdUnixDateTime;
	
	/** 
	 * stores how far each column has been generated should start with {@link EDhApiWorldGenerationStep#EMPTY}
	 *
	 * @see EDhApiWorldGenerationStep
	 */
	public final ByteArrayList columnGenerationSteps;
	/** 
	 * stores what world compression was used for each column.
	 *
	 * @see EDhApiWorldCompressionMode
	 */
	public final ByteArrayList columnWorldCompressionMode;
	
	/** 
	 * stored x/z, y <br>
	 * The y data should be sorted from top to bottom
	 */
	public final LongArrayList[] dataPoints;
	
	public boolean isEmpty;
	/** Will be null if we don't want to update this value in the DB */
	@Nullable
	public Boolean applyToParent = null;
	/** Will be null if we don't want to update this value in the DB */
	@Nullable
	public Boolean applyToChildren = null;
	/** Will be null if we don't want to update this value in the DB */
	@Nullable
	public Boolean regenerateLeaf = null;
	
	/** should only be used by methods exposed via the DH API */
	private boolean runApiSetterValidation = false;
	
	
	
	//==============//
	// constructors //
	//==============//
	//region constructors
	
	public static FullDataSourceV2 createFromChunk(ILevelWrapper levelWrapper, IChunkWrapper chunkWrapper) { return LodDataBuilder.createFromChunk(levelWrapper, chunkWrapper); }
	
	public static FullDataSourceV2 createFromLegacyDataSourceV1(FullDataSourceV1 legacyData)
	{
		if (FullDataSourceV1.WIDTH != WIDTH)
		{
			throw new UnsupportedOperationException(
					"Unable to convert ["+FullDataSourceV1.class.getSimpleName()+"] into ["+FullDataSourceV2.class.getSimpleName()+"]. " +
					"Data sources have different data point widths and no converter is present. " +
					"input width ["+ FullDataSourceV1.WIDTH+"], recipient width ["+WIDTH+"].");
		}
		
		
		// Note: this logic only works if the data point data is the same between both versions
		byte[] columnGenerationSteps = new byte[WIDTH * WIDTH];
		byte[] columnWorldCompressionMode = new byte[WIDTH * WIDTH];
		LongArrayList[] dataPoints = new LongArrayList[WIDTH * WIDTH];
		for (int x = 0; x < WIDTH; x++)
		{
			for (int z = 0; z < WIDTH; z++)
			{
				long[] legacyDataColumn = legacyData.get(x, z);
				if (legacyDataColumn != null && legacyDataColumn.length != 0)
				{
					int index = relativePosToIndex(x, z);
					LongArrayList newDataColumn = new LongArrayList(legacyDataColumn);
					
					
					// convert the data point format
					boolean columnHasNonAirBlock = false;
					for (int i = 0; i < legacyDataColumn.length; i++)
					{
						long dataPoint = legacyDataColumn[i];
						
						boolean isAir = legacyData.mapping.getBlockStateWrapper(FullDataPointUtil.getId(dataPoint)).isAir();
						byte blockLight = (byte) FullDataPointUtil.getBlockLight(dataPoint);
						
						if (isAir)
						{
							// air shouldn't have any light, otherwise down sampling will look weird
							blockLight = 0;
						}
						
						dataPoint = FullDataPointUtil.setBlockLight(dataPoint, blockLight);
						newDataColumn.set(i, dataPoint);
						
						
						// check if this datapoint is air
						if (!columnHasNonAirBlock && !isAir)
						{
							columnHasNonAirBlock = true;
						}
					}
					
					
					// save the converted data point
					ensureDataColumnOrder(newDataColumn);
					dataPoints[index] = newDataColumn;
					
					// the old data sources didn't have a generation step written down
					// if the column has any data points, assume it's fully generated, otherwise assume it's empty
					columnGenerationSteps[index] = (columnHasNonAirBlock ? EDhApiWorldGenerationStep.LIGHT.value : EDhApiWorldGenerationStep.EMPTY.value);
					columnWorldCompressionMode[index] = EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS.value;
				}
			}
		}
		
		FullDataSourceV2 fullDataSource = FullDataSourceV2.createWithData(legacyData.getPos(), legacyData.mapping, dataPoints, columnGenerationSteps, columnWorldCompressionMode);
		return fullDataSource;
	}
	
	public static FullDataSourceV2 createEmpty(long pos)
	{
		FullDataPointIdMap map = new FullDataPointIdMap(pos);
		return new FullDataSourceV2(
				pos, map,
				// data points, genSteps, and columnCompression are all null since
				// nothing has been generated yet.
				// Using the default value of all 0's is adequate
				null,
				null, null,
				true);
	}
	
	public static FullDataSourceV2 createWithData(long pos, FullDataPointIdMap mapping, LongArrayList[] data, byte[] columnGenerationStep, byte[] columnWorldCompressionMode)
	{ return new FullDataSourceV2(pos, mapping, data, columnGenerationStep, columnWorldCompressionMode, false); }
	
	private FullDataSourceV2(
			long pos,
			FullDataPointIdMap mapping, @Nullable LongArrayList[] data,
			byte @Nullable [] columnGenerationSteps, byte @Nullable [] columnWorldCompressionMode,
			boolean empty)
	{
		super(ARRAY_LIST_POOL, 2, 0, WIDTH * WIDTH, 0, 0);
		
		LodUtil.assertTrue(data == null || data.length == WIDTH * WIDTH);
		
		
		
		this.pos = pos;
		this.mapping = mapping;
		this.isEmpty = empty;
		
		
		// pooled data arrays
		this.dataPoints = new LongArrayList[WIDTH * WIDTH];
		for (int i = 0; i < WIDTH * WIDTH; i++)
		{
			// size defaulting to 0 since we don't know how many datapoints
			// will be in this column yet
			this.dataPoints[i] = this.pooledArraysCheckout.getLongArray(i, 0);
		}
		
		// use incoming data if present
		if (data != null)
		{
			for (int i = 0; i < WIDTH * WIDTH; i++)
			{
				this.dataPoints[i].addAll(data[i]);
			}
		}
		
		// pooled generation step array
		this.columnGenerationSteps = this.pooledArraysCheckout.getByteArray(0, 0); // initial size is 0 so we can simply add the existing array if present
		if (columnGenerationSteps != null)
		{
			this.columnGenerationSteps.addElements(0, columnGenerationSteps);
		}
		else
		{
			ListUtil.clearAndSetSize(this.columnGenerationSteps, WIDTH * WIDTH);
		}
		
		// pooled column compression array
		this.columnWorldCompressionMode = this.pooledArraysCheckout.getByteArray(1, 0);
		if (columnWorldCompressionMode != null)
		{
			this.columnWorldCompressionMode.addElements(0, columnWorldCompressionMode);
		}
		else
		{
			ListUtil.clearAndSetSize(this.columnWorldCompressionMode, WIDTH * WIDTH);
		}
	}
	
	//endregion
	
	
	
	//=========//
	// getters //
	//=========//
	//region getters
	
	public LongArrayList getColumnAtRelPos(int relX, int relZ) throws IndexOutOfBoundsException 
	{ return this.dataPoints[relativePosToIndex(relX, relZ)]; }
	
	@Nullable
	public LongArrayList tryGetColumnAtRelPos(int relX, int relZ)
	{
		int index = tryGetRelativePosToIndex(relX, relZ);
		if (index == -1)
		{
			return null;
		}
		
		return this.dataPoints[index];
	}
	
	/** 
	 * returns {@link FullDataPointUtil#EMPTY_DATA_POINT} if the given {@link DhBlockPos}
	 * is outside this data source's boundaries.
	 */
	public long getDataPointAtBlockPos(int blockPosX, int blockPosY, int blockPosZ, int levelMinY)
	{
		long requestedPos = DhSectionPos.encode(LodUtil.BLOCK_DETAIL_LEVEL, blockPosX, blockPosZ);
		
		// stop if the requested blockPos is outside this datasource
		{
			long sectionPos =  DhSectionPos.encodeContaining(DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL, new DhBlockPos(blockPosX, blockPosY, blockPosZ));
			if (!DhSectionPos.contains(this.pos, sectionPos))
			{
				return FullDataPointUtil.EMPTY_DATA_POINT;
			}
		}
		
		
		// get the relative data source position
		byte requestDetailLevel = (byte) (DhSectionPos.getDetailLevel(this.pos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
		long relativePos = DhSectionPos.getDhSectionRelativePositionForDetailLevel(requestedPos, requestDetailLevel);
		
		// get the data column
		LongArrayList dataColumn = this.getColumnAtRelPos(DhSectionPos.getX(relativePos), DhSectionPos.getZ(relativePos));
		if (dataColumn == null)
		{
			return FullDataPointUtil.EMPTY_DATA_POINT;
		}
		
		
		// search for a datapoint that contains the given block y position
		int relBlockPosY = blockPosY - levelMinY;
		long dataPoint;
		for (int i = 0; i < dataColumn.size(); i++)
		{
			dataPoint = dataColumn.getLong(i);
			
			// we are looking for a specific datapoint,
			// don't look at null ones
			if (dataPoint == FullDataPointUtil.EMPTY_DATA_POINT)
			{
				continue;
			}
			
			
			
			int bottomY = FullDataPointUtil.getBottomY(dataPoint);
			int height = FullDataPointUtil.getHeight(dataPoint);
			int topY = bottomY + height;
			
			// does this datapoint contain the requested Y position? 
			if (bottomY <= relBlockPosY
				&& relBlockPosY < topY) // blockPositions start from the bottom of the block, thus "<=" for bottomY, just "<" for topY
			{
				return dataPoint;
			}
		}
		
		return FullDataPointUtil.EMPTY_DATA_POINT;
	}
	
	//endregion
	
	
	
	//==========//
	// updating //
	//==========//
	//region updating
	
	public boolean updateFromDataSource(@NotNull FullDataSourceV2 inputDataSource)
	{
		// don't try updating if the input is empty
		if (inputDataSource.mapping.isEmpty())
		{
			return false;
		}
		
		
		byte thisDetailLevel = DhSectionPos.getDetailLevel(this.pos);
		byte inputDetailLevel = DhSectionPos.getDetailLevel(inputDataSource.pos);
		
		
		// determine the mapping changes necessary for the input to map onto this datasource
		int[] remappedIds = this.mapping.mergeAndReturnRemappedEntityIds(inputDataSource.mapping);
		
		boolean dataChanged;
		if (inputDetailLevel == thisDetailLevel) // same level update
		{
			dataChanged = this.updateFromSameDetailLevel(inputDataSource, remappedIds);
			
			// same detail level, propagate parent/children update flags from input
			if (this.applyToParent != null || inputDataSource.applyToParent != null)
			{
				this.applyToParent =
						// copy over application flag if either are set to continue propagating
						(BoolUtil.falseIfNull(this.applyToParent) || BoolUtil.falseIfNull(inputDataSource.applyToParent))
						// don't propagate past the top of the tree
						&& (DhSectionPos.getDetailLevel(this.pos) < FullDataSourceProviderV2.ROOT_SECTION_DETAIL_LEVEL);
			}
			
			// null check to prevent setting a flag we don't want to save in the DB
			if (this.applyToChildren != null || inputDataSource.applyToChildren != null)
			{
				this.applyToChildren =
						(BoolUtil.falseIfNull(this.applyToChildren) || BoolUtil.falseIfNull(inputDataSource.applyToChildren))
						// don't propagate past the bottom of the tree
						&& (DhSectionPos.getDetailLevel(this.pos) > FullDataSourceProviderV2.LEAF_SECTION_DETAIL_LEVEL);
			}
			
			// null check to prevent setting a flag we don't want to save in the DB
			if (this.regenerateLeaf != null 
				|| inputDataSource.regenerateLeaf != null)
			{
				this.regenerateLeaf =
						(BoolUtil.falseIfNull(this.regenerateLeaf) || BoolUtil.falseIfNull(inputDataSource.regenerateLeaf));
			}
		}
		else if (inputDetailLevel + 1 == thisDetailLevel) // applying to parent
		{
			dataChanged = this.updateFromOneBelowDetailLevel(inputDataSource, remappedIds);
			
			// apply/regen fields are set in the UpdatePropagator
			// otherwise keep whatever the original flags are
		}
		else if (inputDetailLevel - 1 == thisDetailLevel) // downsampling to child
		{
			dataChanged = this.downsampleFromOneAboveDetailLevel(inputDataSource, remappedIds);
			
			// apply/regen fields are set in the UpdatePropagator
			// otherwise keep whatever the original flags are
		}
		else
		{
			// other detail levels aren't supported since it would be more difficult to maintain
			// and would lead to edge cases that don't necessarily need to be supported 
			// (IE what do you do when the input is smaller than a single datapoint in the receiving data source?)
			// instead it's better to just percolate the updates up
			throw new UnsupportedOperationException("Unsupported data source update. Expected input detail level of ["+(thisDetailLevel-1)+"], ["+thisDetailLevel+"], or ["+(thisDetailLevel+1)+"], received detail level ["+inputDetailLevel+"].");
		}
		
		
		// needed to prevent infinite mapped ID growth
		this.removeUnusedIdsAndRemap();
		
		
		
		if (dataChanged)
		{
			EDhApiWorldCompressionMode worldCompressionMode = Config.Common.LodBuilding.worldCompression.get();
			boolean cullHiddenBlocks = (worldCompressionMode != EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS);
			if (cullHiddenBlocks)
			{
				for (int x = 0; x < WIDTH; x++)
				{
					for (int z = 0; z < WIDTH; z++)
					{
						LongArrayList dataColumn = this.getColumnAtRelPos(x, z);
						if (dataColumn != null
							&& dataColumn.size() > 1)
						{
							FullDataOcclusionCuller.cullHiddenDatapointsInColumn(this, x, z);
						}
					}
				}
			}
			
			
			// update the hash code
			this.generateHashCode();
		}
		
		return dataChanged;
	}
	
	private boolean updateFromSameDetailLevel(FullDataSourceV2 inputDataSource, int[] remappedIds)
	{
		// both data sources should have the same detail level
		if (DhSectionPos.getDetailLevel(inputDataSource.pos) != DhSectionPos.getDetailLevel(this.pos))
		{
			throw new IllegalArgumentException("Both data sources must have the same detail level. Expected ["+ DhSectionPos.getDetailLevel(this.pos)+"], received ["+ DhSectionPos.getDetailLevel(inputDataSource.pos)+"].");
		}
		
		// copy over everything from the input data source into this one
		// provided there is data to copy and the world generation step is the same or more complete
		boolean dataChanged = false;
		for (int x = 0; x < WIDTH; x++)
		{
			for (int z = 0; z < WIDTH; z++)
			{
				int recipientIndex = relativePosToIndex(x, z);
				LongArrayList inputDataArray = inputDataSource.dataPoints[recipientIndex];
				if (inputDataArray == null)
				{
					continue;
				}
				
				
				
				byte thisGenState = this.columnGenerationSteps.getByte(recipientIndex);
				byte inputGenState = inputDataSource.columnGenerationSteps.getByte(recipientIndex);
				
				
				// determine if this column should be updated
				boolean genStateAllowsUpdating = false;
				// if the input is downsampled, we only want to replace empty or downsampled values
				if (inputGenState == EDhApiWorldGenerationStep.DOWN_SAMPLED.value
					&&
					(
						thisGenState == EDhApiWorldGenerationStep.EMPTY.value
						|| thisGenState == EDhApiWorldGenerationStep.DOWN_SAMPLED.value
					))
				{
					genStateAllowsUpdating = true;
				}
				// if the input is any other non-empty value,
				// replace anything that is less-complete
				else if (inputGenState != EDhApiWorldGenerationStep.EMPTY.value
					&& thisGenState <= inputGenState)
				{
					// don't apply less-complete generation data
					genStateAllowsUpdating = true;
				}
				
				if (!genStateAllowsUpdating)
				{
					continue;
				}
				
				
				
				// check if the data changed
				if (this.dataPoints[recipientIndex] == null)
				{
					// no data was present previously
					this.dataPoints[recipientIndex] = new LongArrayList(inputDataArray);
					dataChanged = true;
				}
				else if (this.dataPoints[recipientIndex].size() != inputDataArray.size())
				{
					// data is present, but the size is different
					dataChanged = true;
				}
				
				if (!dataChanged)
				{
					// some old data existed with the same length,
					// we'll have to compare each datapoint.
					// Doing a hash check has too high a chance of showing
					// different data as the same.
					LongArrayList oldDataList = this.dataPoints[recipientIndex];
					for (int i = 0; i < oldDataList.size(); i++)
					{
						long oldDatapoint = oldDataList.getLong(i);
						long newDatapoint = inputDataArray.getLong(i);
						if (oldDatapoint != newDatapoint)
						{
							dataChanged = true;
							break;
						}
					}
				}
				
				// copy over the new data, this is necessary to prevent remapping issues
				this.dataPoints[recipientIndex].clear();
				this.dataPoints[recipientIndex].addAll(inputDataArray);
				this.remapDataColumn(recipientIndex, remappedIds);
				
				if (RUN_DATA_ORDER_VALIDATION)
				{
					throwIfDataColumnInWrongOrder(inputDataSource.pos, this.dataPoints[recipientIndex]);
				}
				
				
				this.columnGenerationSteps.set(recipientIndex, inputGenState);
				// always overwrite the compression mode since we're replacing this column
				this.columnWorldCompressionMode.set(recipientIndex, inputDataSource.columnWorldCompressionMode.getByte(recipientIndex));
				this.isEmpty = false;
			}
		}
		
		return dataChanged;
	}
	
	private boolean updateFromOneBelowDetailLevel(FullDataSourceV2 inputDataSource, int[] remappedIds)
	{
		if (DhSectionPos.getDetailLevel(inputDataSource.pos) + 1 != DhSectionPos.getDetailLevel(this.pos))
		{
			throw new IllegalArgumentException("Input data source must be exactly 1 detail level below this data source. Expected [" + (DhSectionPos.getDetailLevel(this.pos) - 1) + "], received [" + DhSectionPos.getDetailLevel(inputDataSource.pos) + "].");
		}
		
		// input is one detail level lower (higher detail)
		// so 2x2 input data points will be converted into 1 recipient data point
		
		
		// determine where in the input data source should be written to
		// since the input is one detail level below it will be one of this position's 4 children
		int minChildXPos = DhSectionPos.getX(DhSectionPos.getChildByIndex(this.pos, 0));
		int recipientOffsetX = (DhSectionPos.getX(inputDataSource.pos) == minChildXPos) ? 0 : (WIDTH / 2);
		int minChildZPos = DhSectionPos.getZ(DhSectionPos.getChildByIndex(this.pos, 0));
		int recipientOffsetZ = (DhSectionPos.getZ(inputDataSource.pos) == minChildZPos) ? 0 : (WIDTH / 2);
		
		
		
		// merge the input's data points
		// into this data source's
		boolean dataChanged = false;
		for (int x = 0; x < WIDTH; x += 2)
		{
			for (int z = 0; z < WIDTH; z += 2)
			{
				int recipientX = (x / 2) + recipientOffsetX;
				int recipientZ = (z / 2) + recipientOffsetZ;
				int recipientIndex = relativePosToIndex(recipientX, recipientZ);
				
				
				// world gen //
				byte inputGenStep = determineMinWorldGenStepForTwoByTwoColumn(inputDataSource.columnGenerationSteps, x, z);
				this.columnGenerationSteps.set(recipientIndex, inputGenStep);
				
				
				// world compression //
				byte worldCompressionMode = determineHighestWorldCompressionForTwoByTwoColumn(inputDataSource.columnWorldCompressionMode, x, z);
				this.columnWorldCompressionMode.set(recipientIndex, worldCompressionMode);
				
				
				
				// data points //
				LongArrayList mergedInputDataArray = mergeInputTwoByTwoDataColumn(inputDataSource, x, z);
				
				// check if the data changed
				if (this.dataPoints[recipientIndex] == null)
				{
					// no data was present previously
					dataChanged = true;
				}
				else if (this.dataPoints[recipientIndex].size() != mergedInputDataArray.size())
				{
					// data is present, but the size is different
					dataChanged = true;
				}
				
				if (!dataChanged)
				{
					// some old data existed with the same length,
					// we'll have to compare each datapoint.
					// Doing a hash check has too high a chance of showing
					// different data as the same.
					LongArrayList oldDataList = this.dataPoints[recipientIndex];
					for (int i = 0; i < oldDataList.size(); i++)
					{
						long oldDatapoint = oldDataList.getLong(i);
						long newDatapoint = mergedInputDataArray.getLong(i);
						if (oldDatapoint != newDatapoint)
						{
							dataChanged = true;
							break;
						}
					}
				}
				
				this.dataPoints[recipientIndex] = mergedInputDataArray;
				// copy over the new data, this is necessary to prevent remapping issues
				this.remapDataColumn(recipientIndex, remappedIds);
				
				if (RUN_DATA_ORDER_VALIDATION)
				{
					throwIfDataColumnInWrongOrder(inputDataSource.pos, this.dataPoints[recipientIndex]);
				}
				
				
				
				this.isEmpty = false;
			}
		}
		
		return dataChanged;
	}
	
	/** 
	 * The minimum value is used because we don't want to accidentally record that
	 * something was generated when it wasn't.
	 */
	private static byte determineMinWorldGenStepForTwoByTwoColumn(ByteArrayList columnGenerationSteps, int relX, int relZ)
	{
		byte minWorldGenStepValue = Byte.MAX_VALUE;
		for (int x = 0; x < 2; x++)
		{
			for (int z = 0; z < 2; z++)
			{
				int index = relativePosToIndex(x + relX, z + relZ);
				byte worldGenStepValue = columnGenerationSteps.getByte(index);
				minWorldGenStepValue = (byte) Math.min(minWorldGenStepValue, worldGenStepValue);
			}
		}
		return minWorldGenStepValue;
	}
	/** 
	 * The minimum value is used because we don't want to accidentally record that
	 * something was generated when it wasn't.
	 */
	private static byte determineHighestWorldCompressionForTwoByTwoColumn(ByteArrayList columnCompressionMode, int relX, int relZ)
	{
		byte minWorldGenStepValue = Byte.MIN_VALUE;
		for (int x = 0; x < 2; x++)
		{
			for (int z = 0; z < 2; z++)
			{
				int index = relativePosToIndex(x + relX, z + relZ);
				byte worldGenStepValue = columnCompressionMode.getByte(index);
				minWorldGenStepValue = (byte) Math.max(minWorldGenStepValue, worldGenStepValue);
			}
		}
		return minWorldGenStepValue;
	}
	private static LongArrayList mergeInputTwoByTwoDataColumn(FullDataSourceV2 inputDataSource, int x, int z)
	{
		LongArrayList newColumnList = new LongArrayList();
		
		
		//=========================//
		// get the 4 input columns //
		//=========================//
		
		LongArrayList[] inputColumns = new LongArrayList[4];
		int colIndex = 0;
		for (int inputX = x; inputX < x + 2; inputX++)
		{
			for (int inputZ = z; inputZ < z + 2; inputZ++, colIndex++)
			{
				inputColumns[colIndex] = inputDataSource.dataPoints[relativePosToIndex(inputX, inputZ)];
				if (inputColumns[colIndex] != null 
					&& RUN_DATA_ORDER_VALIDATION)
				{
					throwIfDataColumnInWrongOrder(inputDataSource.pos, inputColumns[colIndex]);
				}
			}
		}
		
		
		
		//========================================//
		// find all y levels where changes happen //
		//========================================//
		
		IntArrayList yTransitions = new IntArrayList();
		for (int i = 0; i < 4; i++)
		{
			if (inputColumns[i] == null 
				|| inputColumns[i].isEmpty())
			{
				continue;
			}
			
			for (int j = 0; j < inputColumns[i].size(); j++)
			{
				long datapoint = inputColumns[i].getLong(j);
				int minY = FullDataPointUtil.getBottomY(datapoint);
				int maxY = minY + FullDataPointUtil.getHeight(datapoint);
				
				if (!yTransitions.contains(minY))
				{
					yTransitions.add(minY);
				}
				
				if (!yTransitions.contains(maxY))
				{
					yTransitions.add(maxY);
				}
			}
		}
		
		// can happen if the columns are empty
		if (yTransitions.isEmpty())
		{
			return newColumnList;
		}
		
		// sort the transitions from bottom to top
		yTransitions.sort(null);
		
		// create index trackers for each column, 
		// starting with the top-most datapoint
		int[] currentIndices = new int[4];
		for (int i = 0; i < 4; i++)
		{
			if (inputColumns[i] != null 
				&& !inputColumns[i].isEmpty())
			{
				currentIndices[i] = inputColumns[i].size() - 1;
			}
			else
			{
				currentIndices[i] = -1;
			}
		}
		
		
		
		//=======================//
		// process each Y change //
		//=======================//
		
		int lastId = 0;
		byte lastBlockLight = 0;
		byte lastSkyLight = 0;
		int currentMinY = yTransitions.getInt(0);
		int accumulatedHeight = 0;
		
		int[] mergeIds = new int[4];
		int[] mergeBlockLights = new int[4];
		int[] mergeSkyLights = new int[4];
		
		for (int yIndex = 0; yIndex < yTransitions.size() - 1; yIndex++)
		{
			int sliceMinY = yTransitions.getInt(yIndex);
			int sliceMaxY = yTransitions.getInt(yIndex + 1);
			int sliceHeight = sliceMaxY - sliceMinY;
			
			// Sample at the midpoint of this slice
			int sampleY = sliceMinY + (sliceHeight / 2);
			
			// Get data from each column at this Y level
			Arrays.fill(mergeIds, 0);
			Arrays.fill(mergeBlockLights, 0);
			Arrays.fill(mergeSkyLights, 0);
			
			for (int i = 0; i < 4; i++)
			{
				// skip columns that are empty or where we have already reached the bottom
				if (currentIndices[i] == -1)
				{
					continue;
				}
				
				
				LongArrayList column = inputColumns[i];
				if (column == null)
				{
					continue;
				}
				
				// move the index down if we've passed the current datapoint
				while (currentIndices[i] >= 0)
				{
					long datapoint = column.getLong(currentIndices[i]);
					int inputMinY = FullDataPointUtil.getBottomY(datapoint);
					int inputMaxY = inputMinY + FullDataPointUtil.getHeight(datapoint);
					
					if (sampleY >= inputMaxY)
					{
						// Sample point is above this datapoint, move to next (lower) one
						currentIndices[i]--;
					}
					else if (sampleY >= inputMinY 
							&& sampleY < inputMaxY)
					{
						// Sample point is within this datapoint
						mergeIds[i] = FullDataPointUtil.getId(datapoint);
						mergeBlockLights[i] = FullDataPointUtil.getBlockLight(datapoint);
						mergeSkyLights[i] = FullDataPointUtil.getSkyLight(datapoint);
						break;
					}
					else
					{
						// Sample point is below this datapoint
						break;
					}
				}
			}
			
			
			
			// Determine merged values for this slice
			int id = determineMostCommonValueInColumnSlice(mergeIds, inputDataSource.mapping);
			
			// Only average light from sub-columns that match the winning ID.
			// Otherwise light from an outvoted block (ie: surface water) can bleed
			// into the merged datapoint (ie: sand under the water),
			// causing incorrect bright spots.
			byte blockLight = (byte) determineAverageValueInColumnSliceWithId(mergeBlockLights, mergeIds, id);
			byte skyLight = (byte) determineAverageValueInColumnSliceWithId(mergeSkyLights, mergeIds, id);
			
			
			// Check if we need to start a new datapoint
			if (accumulatedHeight == 0)
			{
				// first datapoint
				lastId = id;
				lastBlockLight = blockLight;
				lastSkyLight = skyLight;
				currentMinY = sliceMinY;
				accumulatedHeight = sliceHeight;
			}
			else if (id != lastId 
					|| blockLight != lastBlockLight 
					|| skyLight != lastSkyLight)
			{
				// the data changed, create a new datapoint
				try
				{
					long datapoint = FullDataPointUtil.encode(lastId, accumulatedHeight, currentMinY, lastBlockLight, lastSkyLight);
					newColumnList.add(datapoint);
				}
				catch (DataCorruptedException e)
				{
					LOGGER.warn("Skipping corrupt datapoint for pos ["+DhSectionPos.toString(inputDataSource.pos)+"] at relative position ["+x+","+z+"] with data: ID["+lastId+"], Height["+accumulatedHeight+"], minY["+currentMinY+"], lastBlockLight["+lastBlockLight+"], lastSkyLight["+lastSkyLight+"].");
				}
				
				// start the next datapoint
				lastId = id;
				lastBlockLight = blockLight;
				lastSkyLight = skyLight;
				currentMinY = sliceMinY;
				accumulatedHeight = sliceHeight;
			}
			else
			{
				// this datapoint is the same as the last one, 
				// just extend it's height
				accumulatedHeight += sliceHeight;
			}
		}
		
		
		// add the final datapoint if needed
		if (accumulatedHeight > 0)
		{
			try
			{
				newColumnList.add(FullDataPointUtil.encode(lastId, accumulatedHeight, currentMinY, lastBlockLight, lastSkyLight));
			}
			catch (DataCorruptedException e)
			{
				LOGGER.warn("Skipping corrupt datapoint for pos ["+DhSectionPos.toString(inputDataSource.pos)+"] at relative position ["+x+","+z+"] with data: ID["+lastId+"], Height["+accumulatedHeight+"], minY["+currentMinY+"], lastBlockLight["+lastBlockLight+"], lastSkyLight["+lastSkyLight+"].");
			}
		}
		
		
		// confirm the array is in the correct order
		ensureDataColumnOrder(newColumnList);
		
		return newColumnList;
	}
	/**
	 * Only update the ID once it's been added to this data source.
	 * Updating the incoming data source will cause issues if it is applied 
	 * to anything else due to multiple remapping.
	 */
	private void remapDataColumn(int dataPointIndex, int[] remappedIds)
	{
		LongArrayList dataColumn = this.dataPoints[dataPointIndex];
		for (int i = 0; i < dataColumn.size(); i++)
		{
			dataColumn.set(i, FullDataPointUtil.remap(remappedIds, dataColumn.getLong(i)));
		}
	}
	/** @param mapping can be included to ignore air ID's, otherwise all 4 values are treated equally */
	private static int determineMostCommonValueInColumnSlice(int[] sliceArray, @Nullable FullDataPointIdMap mapping)
	{
		if (RUN_UPDATE_DEV_VALIDATION)
		{
			LodUtil.assertTrue(sliceArray.length == 4, "Column Slice should only contain 4 values.");
		}
		
		int value0 = sliceArray[0];
		int count0 = 0;
		int value1 = sliceArray[1];
		int count1 = 0;
		int value2 = sliceArray[2];
		int count2 = 0;
		int value3 = sliceArray[3];
		int count3 = 0; 
		
		// count the occurrences of each value
		for (int i = 0; i < 4; i++)
		{
			int value = sliceArray[i];
			if (mapping != null && mapping.getBlockStateWrapper(value).isAir())
			{
				// always overwrite air to prevent holes in hollow structures
				continue;
			}
			
			if (value == value0)
			{
				count0++;
			}
			else if (value == value1)
			{
				count1++;
			}
			else if (value == value2)
			{
				count2++;
			}
			else
			{
				count3++;
			}
		}
		
		// return the most common occurrence
		int maxCount = Math.max(count0, Math.max(count1, Math.max(count2, count3)));
		if (maxCount == count0)
		// if the max count is 1 then we'll just go with the first column
		{
			return value0;
		}
		else if (maxCount == count1)
		{
			return value1;
		}
		else if (maxCount == count2)
		{
			return value2;
		}
		else
		{
			return value3;
		}
	}
	
	/**
	 * We need to average based on the winning ID vs 
	 * all datapoints equally to prevent light bleeding
	 * into underwater blocks.
	 * If we average everything equally we may accidentally pick the water surface
	 * as a light value, which would have a much brighter value than
	 * the underwater blocks, causing a bright spot.
	 */
	private static int determineAverageValueInColumnSliceWithId(int[] sliceArray, int[] mergeIds, int winningId)
	{
		if (RUN_UPDATE_DEV_VALIDATION)
		{
			LodUtil.assertTrue(sliceArray.length == 4, "Column Slice should only contain 4 values.");
			LodUtil.assertTrue(mergeIds.length == 4, "Column Slice should only contain 4 values.");
		}
		
		
		int sum = 0;
		int count = 0;
		for (int i = 0; i < 4; i++)
		{
			if (mergeIds[i] == winningId)
			{
				sum += sliceArray[i];
				count++;
			}
		}
		
		if (count == 0)
		{
			// if all 4 blocks are different, averaging all four
			// datapoints should be safe and provides us
			// with smoother lighting
			return determineAverageValueInColumnSlice(sliceArray);
		}
		
		return sum / count;
	}
	private static int determineAverageValueInColumnSlice(int[] sliceArray)
	{
		if (RUN_UPDATE_DEV_VALIDATION)
		{
			LodUtil.assertTrue(sliceArray.length == 4, "Column Slice should only contain 4 values.");
		}
		
		
		int value = 0;
		for (int i = 0; i < 4; i++)
		{
			value += sliceArray[i];
		}
		
		value /= 4;
		return value;
	}
	
	/** 
	 * Only downsamples into a given column if this data source doesn't
	 * already contain data in that column.
	 * This is done to prevent accidentally downsampling onto already present higher-detail data.
	 */
	public boolean downsampleFromOneAboveDetailLevel(FullDataSourceV2 inputDataSource, int[] remappedIds)
	{
		if (DhSectionPos.getDetailLevel(inputDataSource.pos) - 1 != DhSectionPos.getDetailLevel(this.pos))
		{
			throw new IllegalArgumentException("Input data source must be exactly 1 detail level above this data source. Expected [" + (DhSectionPos.getDetailLevel(this.pos) - 1) + "], received [" + DhSectionPos.getDetailLevel(inputDataSource.pos) + "].");
		}
		
		// input is one detail level higher (lower detail)
		// so 1x1 input data points will be converted into 2x2 recipient data point
		
		
		// determine where in this data source should be read from
		// since the input is one detail level above this will be one of input position's 4 children
		int minParentXPos = DhSectionPos.getX(DhSectionPos.getChildByIndex(inputDataSource.pos, 0));
		int inputOffsetX = (DhSectionPos.getX(this.pos) == minParentXPos) ? 0 : (WIDTH / 2);
		int minParentZPos = DhSectionPos.getZ(DhSectionPos.getChildByIndex(inputDataSource.pos, 0));
		int inputOffsetZ = (DhSectionPos.getZ(this.pos) == minParentZPos) ? 0 : (WIDTH / 2);
		
		
		
		// merge the input's data points
		// into this data source's
		boolean dataChanged = false;
		for (int x = 0; x < WIDTH; x++)
		{
			for (int z = 0; z < WIDTH; z++)
			{
				// recipient index is 1-to-1
				int recipientIndex = relativePosToIndex(x, z);
				
				int inputX = (x / 2) + inputOffsetX;
				int inputZ = (z / 2) + inputOffsetZ;
				int inputIndex = relativePosToIndex(inputX, inputZ);
				
				
				// data points //
				
				// check if this column should be downsampled
				boolean downSampleColumn;
				if (this.dataPoints[recipientIndex] == null)
				{
					downSampleColumn = true;
				}
				else
				{
					downSampleColumn = true; // assume empty until we find non-empty data
					for (long dataPoint : this.dataPoints[recipientIndex])
					{
						if (dataPoint != FullDataPointUtil.EMPTY_DATA_POINT)
						{
							downSampleColumn = false;
							break;
						}
					}
				}
				
				if (downSampleColumn)
				{
					// Mark only columns that actually receive lower-detail data.
					// Existing generated child columns must retain their metadata.
					this.columnGenerationSteps.set(recipientIndex, EDhApiWorldGenerationStep.DOWN_SAMPLED.value);
					this.columnWorldCompressionMode.set(
						recipientIndex,
						inputDataSource.columnWorldCompressionMode.getByte(inputIndex));

					LongArrayList inputDataArray = inputDataSource.dataPoints[inputIndex];
					// Parent and child data sources use independently pooled columns.
					// Sharing this reference lets ID remapping mutate the parent and
					// leaves child data pointing at storage that the parent later releases.
					this.dataPoints[recipientIndex].clear();
					this.dataPoints[recipientIndex].addAll(inputDataArray);
					this.remapDataColumn(recipientIndex, remappedIds);
					
					if (RUN_DATA_ORDER_VALIDATION)
					{
						throwIfDataColumnInWrongOrder(inputDataSource.pos, this.dataPoints[recipientIndex]);
					}
					
					dataChanged = true;
				}
				
				this.isEmpty = false;
			}
		}
		
		return dataChanged;
	}
	
	/**
	 * Should be run at the end of {@link FullDataSourceV2#updateFromDataSource}
	 * so the {@link FullDataPointIdMap} will only contain ID's that are actively in use. <br><br>
	 * 
	 * This prevents the {@link FullDataPointIdMap} from growing infinitely when merged.
	 * 
	 * @see FullDataPointIdMap#mergeAndReturnRemappedEntityIds(FullDataPointIdMap) 
	 */
	private void removeUnusedIdsAndRemap()
	{
		Int2IntOpenHashMap newIdByOldId = new Int2IntOpenHashMap();
		FullDataPointIdMap newMap = DATA_MAP_FOR_REMAPPING_REF.get();
		newMap.clear(this.pos);
		
		
		// find all the IDs that are currently in use
		for (int x = 0; x < WIDTH; x++)
		{
			for (int z = 0; z < WIDTH; z++)
			{
				int index = relativePosToIndex(x, z);
				LongArrayList dataColumn = this.dataPoints[index];
				for (int i = 0; i < dataColumn.size(); i++)
				{
					long dataPoint = dataColumn.getLong(i);
					int oldId = FullDataPointUtil.getId(dataPoint);
					
					int newId = newMap.addIfNotPresentAndGetId(
						this.mapping.getBiomeWrapper(oldId),
						this.mapping.getBlockStateWrapper(oldId));
					
					newIdByOldId.put(oldId, newId);
				}
			}
		}
		
		
		// replace the old entries to remove any unneeded ones
		this.mapping.clear(this.pos);
		this.mapping.addAll(newMap);
		
		
		// remap the data
		for (int x = 0; x < WIDTH; x++)
		{
			for (int z = 0; z < WIDTH; z++)
			{
				int index = relativePosToIndex(x, z);
				LongArrayList dataColumn = this.dataPoints[index];
				for (int i = 0; i < dataColumn.size(); i++)
				{
					long oldDataPoint = dataColumn.getLong(i);
					int oldId = FullDataPointUtil.getId(oldDataPoint);
					
					int newId = newIdByOldId.get(oldId);
					long newDataPoint = FullDataPointUtil.setId(oldDataPoint, newId);
					dataColumn.set(i, newDataPoint);
				}
			}
		}
	}
	
	//endregion
	
	
	
	//===================//
	// adjacent clearing //
	//===================//
	//region adjacent clearing
	
	/** Removes any non-adjacent data from the given direction. */
	public void clearAllNonAdjData(EDhDirection direction)
	{
		long encodedMinMaxPos = FullDataMinMaxPosUtil.getEncodedMinMaxPos(direction);
		int minX = FullDataMinMaxPosUtil.getAdjMinX(encodedMinMaxPos);
		int maxX = FullDataMinMaxPosUtil.getAdjMaxX(encodedMinMaxPos);
		int minZ = FullDataMinMaxPosUtil.getAdjMinZ(encodedMinMaxPos);
		int maxZ = FullDataMinMaxPosUtil.getAdjMaxZ(encodedMinMaxPos);
		
		for (int relX = 0; relX < FullDataSourceV2.WIDTH; relX++)
		{
			for (int relZ = 0; relZ < FullDataSourceV2.WIDTH; relZ++)
			{
				// skip non-adjacent data
				if (relX >= minX && relX < maxX
						&& relZ >= minZ && relZ < maxZ)
				{
					continue;
				}
				
				LongArrayList dataColumn = this.getColumnAtRelPos(relX, relZ);
				dataColumn.clear();
				dataColumn.add(FullDataPointUtil.EMPTY_DATA_POINT);
			}
		}
	}
	
	//endregion
	
	
	
	//================//
	// helper methods //
	//================//
	//region helper methods
	
	/** 
	 * Usually this should just be used internally, but there may be instances
	 * where the raw data arrays are available without the data source object.
	 * 	 
	 * @return -1 if given an out-of-bounds relative position 
	 */
	public static int tryGetRelativePosToIndex(int relX, int relZ)
	{
		if (relX < 0 || relZ < 0
				|| relX >= WIDTH || relZ >= WIDTH)
		{
			return -1;
		}
		
		return (relX * WIDTH) + relZ;
	}
	
	/** 
	 * Usually this should just be used internally, but there may be instances
	 * where the raw data arrays are available without the data source object.
	 */
	public static int relativePosToIndex(int relX, int relZ) throws IndexOutOfBoundsException
	{ 
		int index = tryGetRelativePosToIndex(relX, relZ);
		if (index < 0)
		{
			throw new IndexOutOfBoundsException("Relative data source positions must be between [0] (inclusive) and ["+WIDTH+"] (exclusive) the relative pos: ["+relX+","+relZ+"] is outside those boundaries.");
		}
		
		return index; 
	}
	
	/** 
	 * Throws an exception if the given
	 * full data column array is in the wrong order
	 * IE if the first data point is the lowest and the last data point is the highest.
	 * Data columns should be in reverse order, IE the first data point should be the highest data point.
	 * 
	 * @see FullDataSourceV2#dataPoints
	 */
	public static void throwIfDataColumnInWrongOrder(long pos, LongArrayList dataArray) throws IllegalStateException
	{
		if (dataArray.size() < 2)
		{
			return;
		}
		
		long firstDataPoint = dataArray.getLong(0);
		int firstBottomY = FullDataPointUtil.getBottomY(firstDataPoint);
		
		long lastDataPoint = dataArray.getLong(dataArray.size() - 1);
		int lastBottomY = FullDataPointUtil.getBottomY(lastDataPoint);
		
		if (firstBottomY < lastBottomY)
		{
			throw new IllegalStateException("Incorrect data point order at pos: ["+ DhSectionPos.toString(pos)+"], first datapoint bottom Y ["+firstBottomY+"], last datapoint bottom Y ["+lastBottomY+"].");
		}
	}
	
	/**
	 * Ensures the given data column is in the correct Y order, specifically
	 * top-to-bottom.
	 */
	private static void ensureDataColumnOrder(LongArrayList dataColumn)
	{
		if (dataColumn.size() < 2)
		{
			return;
		}
		
		long firstDataPoint = dataColumn.getLong(0);
		int firstBottomY = FullDataPointUtil.getBottomY(firstDataPoint);
		
		long lastDataPoint = dataColumn.getLong(dataColumn.size() - 1);
		int lastBottomY = FullDataPointUtil.getBottomY(lastDataPoint);
		
		if (firstBottomY < lastBottomY)
		{
			// reverse the array so index 0 is the highest,
			// this is necessary for later logic
			// source: https://stackoverflow.com/questions/2137755/how-do-i-reverse-an-int-array-in-java
			for(int i = 0; i < dataColumn.size() / 2; i++)
			{
				long temp = dataColumn.getLong(i);
				dataColumn.set(i, dataColumn.getLong(dataColumn.size() - i - 1));
				dataColumn.set(dataColumn.size() - i - 1, temp);
			}
		}
	}
	
	//endregion
	
	
	
	//=====================//
	// setters and getters //
	//=====================//
	//region setters and getters
	
	public long getPos() { return this.pos; }
	
	public byte getDataDetailLevel() { return (byte) (DhSectionPos.getDetailLevel(this.pos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL); }
	
	public void setSingleColumn(LongArrayList longArray, int relX, int relZ, EDhApiWorldGenerationStep worldGenStep, EDhApiWorldCompressionMode worldCompressionMode)
	{
		int index = relativePosToIndex(relX, relZ);
		this.dataPoints[index] = longArray;
		this.columnGenerationSteps.set(index, worldGenStep.value);
		this.columnWorldCompressionMode.set(index, worldCompressionMode.value);
		
		
		if (RUN_UPDATE_DEV_VALIDATION)
		{
			// validate the incoming ID's
			int maxValidId = this.mapping.getMaxValidId();
			for (int i = 0; i < longArray.size(); i++)
			{
				long dataPoint = longArray.getLong(i);
				int id = FullDataPointUtil.getId(dataPoint);
				if (id > maxValidId)
				{
					LodUtil.assertNotReach("Column set with higher than possible ID. ID [" + id + "], max valid ID [" + maxValidId + "].");
				}
			}
		}
	}
	
	//endregion
	
	
	
	//=============//
	// API methods //
	//=============//
	//region API methods
	
	public void setRunApiSetterValidation(boolean runValidation) { this.runApiSetterValidation = runValidation; }
	
	@Override
	public int getWidthInDataColumns() { return WIDTH; }
	
	@Override
	public List<DhApiTerrainDataPoint> setApiDataPointColumn(int relX, int relZ, EDhApiWorldGenerationStep worldGenStep, List<DhApiTerrainDataPoint> columnDataPoints) 
				throws IndexOutOfBoundsException, IllegalArgumentException
	{
		try
		{
			LodDataBuilder.putListInTopDownOrder(columnDataPoints);
			if (this.runApiSetterValidation)
			{
				LodDataBuilder.validateOrThrowApiDataColumn(columnDataPoints);
			}
			
			LongArrayList packedDataPoints = LodDataBuilder.convertApiDataPointListToPackedLongArray(columnDataPoints, this, 0, true);
			
			this.setSingleColumn(packedDataPoints, relX, relZ, worldGenStep, EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS);
			
			return columnDataPoints;
		}
		catch (DataCorruptedException e)
		{
			throw new IllegalArgumentException(e.getMessage(), e);
		}
	}
	
	@Override 
	public List<DhApiTerrainDataPoint> getApiDataPointColumn(int relX, int relZ) throws IndexOutOfBoundsException
	{
		LongArrayList dataColumn = this.getColumnAtRelPos(relX, relZ);
		
		ArrayList<DhApiTerrainDataPoint> apiList = new ArrayList<>();
		for (int i = 0; i < dataColumn.size(); i++)
		{
			long datapoint = dataColumn.getLong(i);
			
			DhApiTerrainDataPoint apiDataPoint = DhApiTerrainDataPointUtil.createApiDatapoint(0, this.mapping, DhSectionPos.getDetailLevel(this.pos), datapoint);
			apiList.add(apiDataPoint);
		}
		
		return apiList;
	}
	
	//endregion
	
	
	
	//============//
	// unit tests //
	//============//
	//region unit tests
	
	public PhantomArrayListCheckout getPhantomArrayCheckoutForUnitTesting()
	{ return this.pooledArraysCheckout; }
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region base overrides
	
	@Override
	public String toString() { return DhSectionPos.toString(this.pos); }
	
	/** Only includes the base data in this object, not the mapping */
	@Override 
	public int hashCode()
	{
		if (this.cachedHashCode == 0)
		{
			this.generateHashCode();
		}
		return this.cachedHashCode;
	}
	private void generateHashCode()
	{
		int result = DhSectionPos.hashCode(this.pos);
		result = 31 * result + Arrays.deepHashCode(this.dataPoints);
		result = 17 * result + this.columnGenerationSteps.hashCode();
		result = 43 * result + this.columnWorldCompressionMode.hashCode();
		
		this.cachedHashCode = result;
	}
	
	@Override 
	public boolean equals(Object obj)
	{
		if (!(obj instanceof FullDataSourceV2))
		{
			return false;
		}
		FullDataSourceV2 other = (FullDataSourceV2) obj;
		
		if (other.pos != this.pos)
		{
			return false;
		}
		else
		{
			// the positions are the same, use the hash as a quick method
			// to determine if the data inside is the same.
			// Note: this isn't perfect, but should work well enough for our use case.
			return other.hashCode() == this.hashCode();
		}
	}
	
	//endregion
	
	
	
}
