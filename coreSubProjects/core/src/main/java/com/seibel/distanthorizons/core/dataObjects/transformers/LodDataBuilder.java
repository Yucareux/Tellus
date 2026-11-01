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

package com.seibel.distanthorizons.core.dataObjects.transformers;

import java.util.Collections;
import java.util.List;

import com.seibel.distanthorizons.api.enums.config.EDhApiWorldCompressionMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiChunkProcessingEvent;
import com.seibel.distanthorizons.api.objects.data.DhApiChunk;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.RenderDataPointUtil;
import com.seibel.distanthorizons.core.util.objects.DataCorruptedException;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IMutableBlockPosWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import com.seibel.distanthorizons.core.logging.DhLogger;
import org.jetbrains.annotations.Nullable;

public class LodDataBuilder
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	private static final IWrapperFactory WRAPPER_FACTORY = SingletonInjector.INSTANCE.get(IWrapperFactory.class);
	private static final IBlockStateWrapper AIR = WRAPPER_FACTORY.getAirBlockStateWrapper();
	
	private static boolean getTopErrorLogged = false;
	
	
	
	//============//
	// converters //
	//============//
	
	public static FullDataSourceV2 createFromChunk(ILevelWrapper levelWrapper, IChunkWrapper chunkWrapper)
	{
		// only block lighting is needed here, sky lighting is populated at the data source stage
		LodUtil.assertTrue(chunkWrapper.isDhBlockLightingCorrect(), "Provided chunk's DH Block lighting hasn't been baked.");
		
		int chunkPosX = chunkWrapper.getChunkPos().getX();
		int chunkPosZ = chunkWrapper.getChunkPos().getZ();
		
		int sectionPosX = getXOrZSectionPosFromChunkPos(chunkWrapper.getChunkPos().getX());
		int sectionPosZ = getXOrZSectionPosFromChunkPos(chunkWrapper.getChunkPos().getZ());
		long pos = DhSectionPos.encode(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, sectionPosX, sectionPosZ);
		
		FullDataSourceV2 dataSource = FullDataSourceV2.createEmpty(pos);
		dataSource.isEmpty = false; // this will be set to "true" if any blocks are found
		// chunk updates always propagate up
		dataSource.applyToParent = true;
		
		
		
		// compute the chunk dataSource offset
		// this offset is used to determine where in the dataSource this chunk's data should go
		
		// expected offset positions:
		// chunkPos -> offset
		//  5 -> 1
		//  4 -> 0 ---
		//  3 -> 3
		//  2 -> 2
		//  1 -> 1
		//  0 -> 0 ===
		// -1 -> 3
		// -2 -> 2
		// -3 -> 1
		// -4 -> 0 ---
		// -5 -> 3
		
		// Fast modulo calculation using bitwise AND since NUMB_OF_CHUNKS_WIDE is a power of 2 (4)
		// For any number n: n & (2^k - 1) is equivalent to Math.floorMod(n, 2^k)
		// Original: Math.floorMod(x, 4) - Handles negative numbers, gives non-negative result in range [0,3]
		// Bitwise: x & (4-1) - Also gives non-negative result in range [0,3]
		// Example: -5 & 3 = 3, which equals Math.floorMod(-5, 4) = 3
		int chunkOffsetX = chunkWrapper.getChunkPos().getX() & (FullDataSourceV2.NUMB_OF_CHUNKS_WIDE - 1);
		int chunkOffsetZ = chunkWrapper.getChunkPos().getZ() & (FullDataSourceV2.NUMB_OF_CHUNKS_WIDE - 1);
		
		// Convert from chunk coordinates to block coordinates
		chunkOffsetX *= LodUtil.CHUNK_WIDTH;
		chunkOffsetZ *= LodUtil.CHUNK_WIDTH;
		
		
		
		//==========================//
		// populate the data source //
		//==========================//
		
		EDhApiWorldCompressionMode worldCompressionMode = Config.Common.LodBuilding.worldCompression.get();
		
		try
		{
			IMutableBlockPosWrapper mcBlockPos = chunkWrapper.getMutableBlockPosWrapper();
			IBlockStateWrapper previousBlockState = null;
			
			DhApiChunkProcessingEvent.EventParam mutableChunkProcessedEventParam 
					= new DhApiChunkProcessingEvent.EventParam(levelWrapper, chunkPosX, chunkPosZ);
			
			int minBuildHeight = chunkWrapper.getMinNonEmptyHeight();
			int exclusiveMaxBuildHeight = chunkWrapper.getExclusiveMaxBuildHeight();
			int inclusiveMinBuildHeight = chunkWrapper.getInclusiveMinBuildHeight();
			int dataCapacity = chunkWrapper.getHeight() / 4;
			
			for (int relBlockX = 0; relBlockX < LodUtil.CHUNK_WIDTH; relBlockX++)
			{
				for (int relBlockZ = 0; relBlockZ < LodUtil.CHUNK_WIDTH; relBlockZ++)
				{
					// Calculate column position
					int columnX = relBlockX + chunkOffsetX;
					int columnZ = relBlockZ + chunkOffsetZ;
					
					// Get column data
					LongArrayList longs = dataSource.getColumnAtRelPos(columnX, columnZ);
					if (longs == null)
					{
						longs = new LongArrayList(dataCapacity);
					}
					else
					{
						longs.clear();
					}
					
					int lastY = exclusiveMaxBuildHeight;
					IBiomeWrapper currentBiome = chunkWrapper.getBiome(relBlockX, lastY, relBlockZ);
					IBlockStateWrapper currentBlockState = AIR;
					int mappedId = dataSource.mapping.addIfNotPresentAndGetId(currentBiome, currentBlockState);
					
					// Determine lighting (we are at the height limit. There are no torches here, and sky is not obscured.)
					byte blockLight = LodUtil.MIN_MC_LIGHT;
					byte skyLight = LodUtil.MAX_MC_LIGHT;
					
					// Get the maximum height from both heightmaps
					int y = Math.max(
							// max between both heightmaps to account for solid invisible blocks (glass)
							// and non-solid opaque blocks (at one point this was stairs, not sure what would fit this now)
							chunkWrapper.getLightBlockingHeightMapValue(relBlockX, relBlockZ),
							chunkWrapper.getSolidHeightMapValue(relBlockX, relBlockZ)
					);
					
					// Go up until we reach open air or the world limit
					IBlockStateWrapper topBlockState = previousBlockState = chunkWrapper.getBlockState(relBlockX, y, relBlockZ, mcBlockPos, previousBlockState);
					while (!topBlockState.isAir() 
							&& y < exclusiveMaxBuildHeight)
					{
						try
						{
							// This is necessary in some edge cases with snow layers and some other blocks that may not appear in the height map but do block light.
							// Interestingly this doesn't appear to be the case in the DhLightingEngine, if this same logic is added there the lighting breaks for the affected blocks.
							y++;
							topBlockState = previousBlockState = chunkWrapper.getBlockState(relBlockX, y, relBlockZ, mcBlockPos, previousBlockState);
						}
						catch (Exception e)
						{
							if (!getTopErrorLogged)
							{
								LOGGER.warn("Unexpected issue in LodDataBuilder, future errors won't be logged. Chunk [" + chunkWrapper.getChunkPos() + "] with max height: [" + exclusiveMaxBuildHeight + "] had issue getting block at pos [" + relBlockX + "," + y + "," + relBlockZ + "] error: " + e.getMessage(), e);
								getTopErrorLogged = true;
							}
							
							y--;
							break;
						}
					}
					
					// Process blocks from top to bottom
					boolean forceSingleBlock = false;
					for (; y >= minBuildHeight; y--)
					{
						IBiomeWrapper newBiome = chunkWrapper.getBiome(relBlockX, y, relBlockZ);
						IBlockStateWrapper newBlockState = previousBlockState = chunkWrapper.getBlockState(relBlockX, y, relBlockZ, mcBlockPos, previousBlockState);
						byte newBlockLight = (byte) chunkWrapper.getDhBlockLight(relBlockX, y + 1, relBlockZ);
						byte newSkyLight = (byte) chunkWrapper.getDhSkyLight(relBlockX, y + 1, relBlockZ);
						
						// Save the biome/block change if different from previous
						if (!newBiome.equals(currentBiome) 
							|| !newBlockState.equals(currentBlockState) 
							|| forceSingleBlock)
						{
							// if the previous block potentially colors this block
							// make this block a single entry, aka add the next block even if it is the same
							// this is done to allow fire, snow, flowers, etc. to properly color the top of columns vs the whole column
							forceSingleBlock = 
									!currentBlockState.isAir()
									&& !currentBlockState.isSolid()
									&& !currentBlockState.isLiquid()
									&& currentBlockState.getOpacity() != LodUtil.BLOCK_FULLY_OPAQUE;
							
							
							// check for API overrides
							{
								mutableChunkProcessedEventParam.updateForPosition(relBlockX, y, relBlockZ, newBlockState, newBiome);
								ApiEventInjector.INSTANCE.fireAllEvents(DhApiChunkProcessingEvent.class, mutableChunkProcessedEventParam);
								
								// did the API user override this block?
								if (mutableChunkProcessedEventParam.getBlockOverride() != null)
								{
									// API users shouldn't be creating their own IBlockStateWrapper objects
									newBlockState = (IBlockStateWrapper)mutableChunkProcessedEventParam.getBlockOverride();
								}
								
								// did the API user override this biome?
								if (mutableChunkProcessedEventParam.getBiomeOverride() != null)
								{
									// API users shouldn't be creating their own IBlockStateWrapper objects
									newBiome = (IBiomeWrapper) mutableChunkProcessedEventParam.getBiomeOverride();
								}
							}
							
							
							longs.add(FullDataPointUtil.encode(mappedId, lastY - y, y + 1 - inclusiveMinBuildHeight, blockLight, skyLight));
							currentBiome = newBiome;
							currentBlockState = newBlockState;
							
							mappedId = dataSource.mapping.addIfNotPresentAndGetId(currentBiome, currentBlockState);
							blockLight = newBlockLight;
							skyLight = newSkyLight;
							lastY = y;
							
							
							// mark the data source as non-empty if we find any non-air blocks
							if (dataSource.isEmpty
								&& newBlockState != null 
								&& !newBlockState.isAir())
							{
								dataSource.isEmpty = false;
							}
						}
					}
					
					// Add the final data point
					longs.add(FullDataPointUtil.encode(mappedId, lastY - y, y + 1 - inclusiveMinBuildHeight, blockLight, skyLight));
					
					// Set the column in the data source
					dataSource.setSingleColumn(longs, columnX, columnZ, EDhApiWorldGenerationStep.LIGHT, worldCompressionMode);
				}
			}
		}
		catch (DataCorruptedException e)
		{
			LOGGER.error("Unable to convert chunk at pos ["+chunkWrapper.getChunkPos()+"] to an LOD. Error: "+e.getMessage(), e);
			return null;
		}
		
		return dataSource;
	}
	
	
	
	/** @throws ClassCastException if an API user returns the wrong object type(s) */
	public static FullDataSourceV2 createFromApiChunkData(DhApiChunk apiChunk, boolean runAdditionalValidation) throws ClassCastException, DataCorruptedException, IllegalArgumentException
	{
		// get the section position
		int sectionPosX = getXOrZSectionPosFromChunkPos(apiChunk.chunkPosX);
		int sectionPosZ = getXOrZSectionPosFromChunkPos(apiChunk.chunkPosZ);
		long pos = DhSectionPos.encode(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, sectionPosX, sectionPosZ);
		
		// Fast modulo calculation using bitwise AND since NUMB_OF_CHUNKS_WIDE is a power of 2 (4)
		// For any number n: n & (2^k - 1) is equivalent to Math.floorMod(n, 2^k)
		// Original: Math.floorMod(x, 4) - Handles negative numbers, gives non-negative result in range [0,3]
		// Bitwise: x & (4-1) - Also gives non-negative result in range [0,3]
		// Example: -5 & 3 = 3, which equals Math.floorMod(-5, 4) = 3
		int relSourceBlockX = (apiChunk.chunkPosX & (FullDataSourceV2.NUMB_OF_CHUNKS_WIDE - 1)) * LodUtil.CHUNK_WIDTH;
		int relSourceBlockZ = (apiChunk.chunkPosZ & (FullDataSourceV2.NUMB_OF_CHUNKS_WIDE - 1)) * LodUtil.CHUNK_WIDTH;
		
		FullDataSourceV2 dataSource = FullDataSourceV2.createEmpty(pos);
		for (int relBlockZ = 0; relBlockZ < LodUtil.CHUNK_WIDTH; relBlockZ++)
		{
			for (int relBlockX = 0; relBlockX < LodUtil.CHUNK_WIDTH; relBlockX++)
			{
				List<DhApiTerrainDataPoint> columnDataPoints = apiChunk.getDataPoints(relBlockX, relBlockZ);
				
				// mark the data source as non-empty if we find any non-air blocks
				if (dataSource.isEmpty)
				{
					for (int i = 0; i < columnDataPoints.size(); i++)
					{
						DhApiTerrainDataPoint dataPoint = columnDataPoints.get(i);
						if (dataPoint.blockStateWrapper != null
							&& !dataPoint.blockStateWrapper.isAir())
						{
							dataSource.isEmpty = false;
							break;
						}
					}
				}
				
				LodDataBuilder.putListInTopDownOrder(columnDataPoints);
				if (runAdditionalValidation)
				{
					validateOrThrowApiDataColumn(columnDataPoints);
				}
				
				LongArrayList packedDataPoints = convertApiDataPointListToPackedLongArray(columnDataPoints, dataSource, apiChunk.bottomYBlockPos, runAdditionalValidation);
				
				dataSource.setSingleColumn(
						packedDataPoints,
						relBlockX + relSourceBlockX, relBlockZ + relSourceBlockZ,
						EDhApiWorldGenerationStep.LIGHT, EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS);
			}
		}
		return dataSource;
	}
	
	
	
	//================//
	// public helpers //
	//================//
	
	/** @see FullDataPointUtil */
	public static LongArrayList convertApiDataPointListToPackedLongArray(
			@Nullable List<DhApiTerrainDataPoint> topDownColumnDataPoints, FullDataSourceV2 dataSource,
			int bottomYBlockPos, boolean runAdditionalValidation) throws DataCorruptedException
	{
		if (topDownColumnDataPoints == null 
			|| topDownColumnDataPoints.size() == 0)
		{
			return new LongArrayList(0);
		}
		
		
		
		// array to store data
		int size = topDownColumnDataPoints.size();
		LongArrayList packedDataPoints = new LongArrayList(size);
		packedDataPoints.clear();
		
		
		if (runAdditionalValidation)
		{
			// check for missing data
			int lastTopY = Integer.MAX_VALUE;
			for (int i = 0; i < size; i++)
			{
				DhApiTerrainDataPoint apiDataPoint = topDownColumnDataPoints.get(i);
				
				if (lastTopY != apiDataPoint.topYBlockPos
					// the first index won't have a lastTopY value
					&& i != 0)
				{
					throw new DataCorruptedException("LOD data has a gap between ["+lastTopY+"] and ["+apiDataPoint.bottomYBlockPos+"]. Empty areas should be filled with air datapoints so light propagates correctly.");
				}
				lastTopY = apiDataPoint.bottomYBlockPos;
			}
		}
		
		
		// go through data from top down
		long lastDataPoint = FullDataPointUtil.EMPTY_DATA_POINT;
		for (int i = 0; i < size; i++)
		{
			DhApiTerrainDataPoint apiDataPoint = topDownColumnDataPoints.get(i);
			
			int thisId = dataSource.mapping.addIfNotPresentAndGetId(
					(IBiomeWrapper) (apiDataPoint.biomeWrapper),
					(IBlockStateWrapper) (apiDataPoint.blockStateWrapper)
			);
			int thisHeight = (apiDataPoint.topYBlockPos - apiDataPoint.bottomYBlockPos);
			
			int lastId = FullDataPointUtil.getId(lastDataPoint);
			byte lastBlockLight = (byte)FullDataPointUtil.getBlockLight(lastDataPoint);
			byte lastSkyLight = (byte)FullDataPointUtil.getSkyLight(lastDataPoint);
			
			
			// if the ID and light are the same, merge the height
			if (thisId == lastId
				&& apiDataPoint.blockLightLevel == lastBlockLight
				&& apiDataPoint.skyLightLevel == lastSkyLight
				// the first index should always be added to the list
				&& i != 0 )
			{
				// add adjacent height
				int lastHeight = FullDataPointUtil.getHeight(lastDataPoint);
				int newHeight = (lastHeight + thisHeight);
				lastDataPoint = FullDataPointUtil.setHeight(lastDataPoint, newHeight);
				
				// subtract bottom Y
				int lastBottomY = FullDataPointUtil.getBottomY(lastDataPoint);
				int newBottomY = lastBottomY - thisHeight;
				lastDataPoint = FullDataPointUtil.setBottomY(lastDataPoint, newBottomY);
				 
				packedDataPoints.set(packedDataPoints.size()-1, lastDataPoint);
			}
			else
			{
				// data changed, create a new datapoint
				long dataPoint = FullDataPointUtil.encode(
					thisId,
					thisHeight,
					apiDataPoint.bottomYBlockPos - bottomYBlockPos,
					(byte) (apiDataPoint.blockLightLevel),
					(byte) (apiDataPoint.skyLightLevel)
				);
				lastDataPoint = dataPoint;
				packedDataPoints.add(dataPoint);
			}
		}
		
		return packedDataPoints;
	}
	
	public static void putListInTopDownOrder(List<DhApiTerrainDataPoint> dataPoints)
	{
		// order doesn't need to be checked if there is 0 or 1 items
		if (dataPoints.size() > 1)
		{
			// DH expects datapoints to be in a top-down order
			DhApiTerrainDataPoint first = dataPoints.get(0);
			DhApiTerrainDataPoint last = dataPoints.get(dataPoints.size() - 1);
			if (first.bottomYBlockPos < last.bottomYBlockPos)
			{
				// flip the array if it's in bottom-up order
				Collections.reverse(dataPoints);
			}
			
		}
	}
	
	public static void validateOrThrowApiDataColumn(List<DhApiTerrainDataPoint> dataPoints) throws IllegalArgumentException
	{
		// check that each datapoint is valid
		int lastBottomYPos = Integer.MIN_VALUE;
		for (int i = 0; i < dataPoints.size(); i++) // standard for-loop used instead of an enhanced for-loop to slightly reduce GC overhead due to iterator allocation
		{
			DhApiTerrainDataPoint dataPoint = dataPoints.get(i);
			
			if (dataPoint == null)
			{
				throw new IllegalArgumentException("Datapoint: ["+i+"] is null DhApiTerrainDataPoints are not allowed. If you want to represent empty terrain, please use AIR.");
			}
			
			if (dataPoint.detailLevel != 0)
			{
				throw new IllegalArgumentException("Datapoint: ["+i+"]-["+dataPoint+"] has the wrong detail level ["+dataPoint.detailLevel+"], all data points must be block sized; IE their detail level must be [0].");
			}
			
			
			
			int bottomYPos = dataPoint.bottomYBlockPos;
			int topYPos = dataPoint.topYBlockPos;
			int height = (dataPoint.topYBlockPos - dataPoint.bottomYBlockPos);
			
			// is the datapoint right side up?
			if (bottomYPos > topYPos)
			{
				throw new IllegalArgumentException("Datapoint: ["+i+"]-["+dataPoint+"] is upside down. Top Pos: ["+topYPos+"], bottom pos: ["+bottomYPos+"].");
			}
			// valid height?
			if (height <= 0 || height >= RenderDataPointUtil.MAX_WORLD_Y_SIZE)
			{
				throw new IllegalArgumentException("Datapoint: ["+i+"]-["+dataPoint+"] has an invalid height ["+height+"]. Height must be in the range [1 - "+RenderDataPointUtil.MAX_WORLD_Y_SIZE+"] (inclusive).");
			}
			
			// is this datapoint overlapping the last one?
			if (lastBottomYPos > topYPos)
			{
				throw new IllegalArgumentException("DhApiTerrainDataPoint ["+i+"]-["+dataPoint+"] is overlapping with the last datapoint, this top Y: ["+topYPos+"], lastBottomYPos: ["+lastBottomYPos+"].");
			}
			// is there a gap between the last datapoint?
			if (topYPos != lastBottomYPos
					&& lastBottomYPos != Integer.MIN_VALUE)
			{
				throw new IllegalArgumentException("DhApiTerrainDataPoint ["+i+"]-["+dataPoint+"] has a gap between it and index ["+(i-1)+"]. Empty spaces should be filled by air, otherwise DH's downsampling won't calculate lighting correctly.");
			}
			
			
			lastBottomYPos = bottomYPos;
		}
		
	}
	
	
	
	//==================//
	// internal helpers //
	//==================//
	
	private static int getXOrZSectionPosFromChunkPos(int chunkXOrZPos)
	{
		// get the section position
		int sectionPos = chunkXOrZPos;
		// negative positions start at -1 so the logic there is slightly different
		sectionPos = (sectionPos < 0) ? ((sectionPos + 1) / FullDataSourceV2.NUMB_OF_CHUNKS_WIDE) - 1 : (sectionPos / FullDataSourceV2.NUMB_OF_CHUNKS_WIDE);
		return sectionPos;
	}
	
	
	
}
