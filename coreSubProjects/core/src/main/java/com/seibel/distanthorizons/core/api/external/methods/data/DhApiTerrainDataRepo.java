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

package com.seibel.distanthorizons.core.api.external.methods.data;

import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.data.DhApiRaycastResult;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3i;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.renderer.AbstractDebugWireframeRenderer;
import com.seibel.distanthorizons.core.util.DhApiTerrainDataPointUtil;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.RayCastUtil;
import com.seibel.distanthorizons.core.util.math.DhVec3f;
import com.seibel.distanthorizons.core.world.AbstractDhWorld;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.coreapi.util.BitShiftUtil;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.util.math.DhVec3i;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import com.seibel.distanthorizons.core.logging.DhLogger;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;


/**
 * Allows interfacing with the terrain data Distant Horizons has stored.
 */
public class DhApiTerrainDataRepo implements IDhApiTerrainDataRepo
{
	public static DhApiTerrainDataRepo INSTANCE = new DhApiTerrainDataRepo();
	
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final DhApiTerrainDataCache DEBUG_DATA_CACHE = new DhApiTerrainDataCache();
	
	
	// debugging values
	private static volatile boolean debugThreadRunning = false;
	private static long timeSinceLastDebugClear = 0L;
	private static boolean logFullColumnInDebug = false;
	private static DhApiVec3i currentDebugVec3i = new DhVec3i();
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private DhApiTerrainDataRepo()
	{
		
	}
	
	//endregion
	
	
	
	//================//
	// Getter Methods //
	//================//
	//region
	
	@Override
	public DhApiResult<DhApiTerrainDataPoint> getSingleDataPointAtBlockPos(IDhApiLevelWrapper levelWrapper, int blockPosX, int blockPosY, int blockPosZ, IDhApiTerrainDataCache dataCache)
	{ return getTerrainDataAtBlockYPos(levelWrapper, DhSectionPos.encode(LodUtil.BLOCK_DETAIL_LEVEL, blockPosX, blockPosZ), blockPosY, dataCache); }
	@Override
	public DhApiResult<DhApiTerrainDataPoint[]> getColumnDataAtBlockPos(IDhApiLevelWrapper levelWrapper, int blockPosX, int blockPosZ, IDhApiTerrainDataCache dataCache)
	{ return getTerrainDataColumnArray(levelWrapper, DhSectionPos.encode(LodUtil.BLOCK_DETAIL_LEVEL, blockPosX, blockPosZ), null, dataCache); }
	
	@Override
	public DhApiResult<DhApiTerrainDataPoint[][][]> getAllTerrainDataAtChunkPos(IDhApiLevelWrapper levelWrapper, int chunkPosX, int chunkPosZ, IDhApiTerrainDataCache dataCache)
	{ return getTerrainDataOverAreaForPositionDetailLevel(levelWrapper, DhSectionPos.encode(LodUtil.CHUNK_DETAIL_LEVEL, chunkPosX, chunkPosZ), dataCache); }
	
	@Override
	public DhApiResult<DhApiTerrainDataPoint[][][]> getAllTerrainDataAtRegionPos(IDhApiLevelWrapper levelWrapper, int regionPosX, int regionPosZ, IDhApiTerrainDataCache dataCache)
	{ return getTerrainDataOverAreaForPositionDetailLevel(levelWrapper, DhSectionPos.encode(LodUtil.REGION_DETAIL_LEVEL, regionPosX, regionPosZ), dataCache); }
	
	@Override
	public DhApiResult<DhApiTerrainDataPoint[][][]> getAllTerrainDataAtDetailLevelAndPos(IDhApiLevelWrapper levelWrapper, byte detailLevel, int posX, int posZ, IDhApiTerrainDataCache dataCache)
	{ return getTerrainDataOverAreaForPositionDetailLevel(levelWrapper, DhSectionPos.encode(detailLevel, posX, posZ), dataCache); }
	
	//endregion
	
	
	
	// private getters //
	//region
	
	/** Returns a single API terrain datapoint that contains the given Y block position */
	private static DhApiResult<DhApiTerrainDataPoint> getTerrainDataAtBlockYPos(IDhApiLevelWrapper levelWrapper, long requestedColumnPos, Integer blockYPos, IDhApiTerrainDataCache dataCache)
	{
		DhApiResult<DhApiTerrainDataPoint[]> result = getTerrainDataColumnArray(levelWrapper, requestedColumnPos, blockYPos, dataCache);
		if (result.success && result.payload.length > 0)
		{
			return DhApiResult.createSuccess(result.message, result.payload[0]);
		}
		else
		{
			return DhApiResult.createFail(result.message);
		}
	}
	
	/**
	 * Returns all the block columns represented by the given {@link DhSectionPos}. <br>
	 * IE, A position with the detail level: <br>
	 * 0 (block): will return a 1x1 matrix of data. (don't do this, we have a specific method for that.) <br>
	 * 1 (2 blocks): will return a 2x2 matrix of data. <br>
	 * 4 (chunk): will return a 16x16 matrix of data. <br> <br>
	 *
	 * will stop and return the in progress data if any errors are encountered.
	 */
	private static DhApiResult<DhApiTerrainDataPoint[][][]> getTerrainDataOverAreaForPositionDetailLevel(
			IDhApiLevelWrapper levelWrapper, long requestedAreaPos, 
			IDhApiTerrainDataCache dataCache)
	{
		byte requestedDetailLevel = DhSectionPos.getDetailLevel(requestedAreaPos);
		long startingBlockPos = DhSectionPos.encode(LodUtil.BLOCK_DETAIL_LEVEL,
			DhSectionPos.getX(requestedAreaPos) * BitShiftUtil.powerOfTwo(requestedDetailLevel - LodUtil.BLOCK_DETAIL_LEVEL),
			DhSectionPos.getZ(requestedAreaPos) * BitShiftUtil.powerOfTwo(requestedDetailLevel - LodUtil.BLOCK_DETAIL_LEVEL));
		int widthOfAreaInBlocks = BitShiftUtil.powerOfTwo(requestedDetailLevel);
		
		DhApiTerrainDataPoint[][][] returnArray = new DhApiTerrainDataPoint[widthOfAreaInBlocks][widthOfAreaInBlocks][];
		int dataColumnsReturned = 0;
		
		// get each column over the area
		for (int x = 0; x < widthOfAreaInBlocks; x++)
		{
			for (int z = 0; z < widthOfAreaInBlocks; z++)
			{
				long blockColumnPos = DhSectionPos.encode(LodUtil.BLOCK_DETAIL_LEVEL, DhSectionPos.getX(startingBlockPos) + x, DhSectionPos.getZ(startingBlockPos) + z);
				DhApiResult<DhApiTerrainDataPoint[]> result = getTerrainDataColumnArray(levelWrapper, blockColumnPos, null, dataCache);
				if (result.success)
				{
					returnArray[x][z] = result.payload;
					dataColumnsReturned++;
				}
				else
				{
					return DhApiResult.createFail(result.message, returnArray);
				}
			}
		}
		
		return dataColumnsReturned != 0 ? DhApiResult.createSuccess("[" + dataColumnsReturned + "] columns returned.", returnArray) : DhApiResult.createSuccess("No data found.", returnArray);
	}
	
	/**
	 * If nullableBlockYPos is null: returns every datapoint in the column defined by the DhLodPos. <br>
	 * If nullableBlockYPos is NOT null: returns a single datapoint in the column defined by the DhLodPos which contains the block Y position. <br><br>
	 *
	 * If the ApiResult is successful there will be an array of data. <br>
	 * The returned array will be empty if no data could be retrieved.
	 */
	private static DhApiResult<DhApiTerrainDataPoint[]> getTerrainDataColumnArray(
			IDhApiLevelWrapper levelWrapper, 
			long requestedColumnPos, Integer nullableBlockYPos, 
			IDhApiTerrainDataCache apiDataCache)
	{
		//============//
		// validation //
		//============//
		
		AbstractDhWorld currentWorld = SharedApi.getAbstractDhWorld();
		if (currentWorld == null)
		{
			return DhApiResult.createFail("Unable to get terrain data before the world has loaded.");
		}
		
		if (!(levelWrapper instanceof ILevelWrapper))
		{
			// custom level wrappers aren't supported,
			// the API user must get a level wrapper from our code somewhere
			return DhApiResult.createFail("Unsupported [" + IDhApiLevelWrapper.class.getSimpleName() + "] implementation, only the core class [" + IDhLevel.class.getSimpleName() + "] is a valid parameter.");
		}
		ILevelWrapper coreLevelWrapper = (ILevelWrapper) levelWrapper;
		
		
		// require a data cache to prevent horrible performance (especially on ray-casts)
		if (apiDataCache == null)
		{
			return DhApiResult.createFail("Missing [" + IDhApiTerrainDataCache.class.getSimpleName() + "], if a cache isn't provided your repo operations will be significantly slower.");
		}
		
		// the data cache must be our own implementation
		if (!(apiDataCache instanceof DhApiTerrainDataCache))
		{
			return DhApiResult.createFail("Unsupported [" + IDhApiTerrainDataCache.class.getSimpleName() + "] implementation, only the core class [" + DhApiTerrainDataCache.class.getSimpleName() + "] is a valid parameter.");
		}
		DhApiTerrainDataCache dataCache = (DhApiTerrainDataCache) apiDataCache;
		
		
		IDhLevel level = currentWorld.getLevel(coreLevelWrapper);
		if (level == null)
		{
			return DhApiResult.createFail("Unable to get terrain data before the world has loaded.");
		}
		
		// get the detail levels for this request
		byte requestedDetailLevel = DhSectionPos.getDetailLevel(requestedColumnPos);
		byte sectionDetailLevel = (byte) (requestedDetailLevel + DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
		
		// get the positions for this request
		long sectionPos = DhSectionPos.convertToDetailLevel(requestedColumnPos, sectionDetailLevel);
		long relativePos = DhSectionPos.getDhSectionRelativePositionForDetailLevel(requestedColumnPos, DhSectionPos.getDetailLevel(requestedColumnPos));
		
		
		
		//=====================//
		// get the data source //
		//=====================//
		
		FullDataSourceV2 dataSource = null;
		try
		{
			// try using the cached data if possible
			if (dataCache != null)
			{
				dataSource = dataCache.get(sectionPos);
			}
			
			if (dataSource == null)
			{
				// attempt to get/generate the data source for this section
				dataSource = level.getFullDataProvider().getAsync(sectionPos).get();
				if (dataSource == null)
				{
					return DhApiResult.createFail("Unable to find/generate any data at the " + DhSectionPos.class.getSimpleName() + " [" + DhSectionPos.toString(sectionPos) + "].");
				}
				
				// save to the cache if present
				if (dataCache != null)
				{
					dataCache.add(sectionPos, dataSource);
				}
			}
			
			
			
			//===============================//
			// get LOD data from data source //
			//===============================//
			
			FullDataPointIdMap mapping = dataSource.mapping;
			LongArrayList dataColumn = dataSource.getColumnAtRelPos(DhSectionPos.getX(relativePos), DhSectionPos.getZ(relativePos));
			if (dataColumn != null)
			{
				int dataColumnIndexCount = dataColumn.size();
				DhApiTerrainDataPoint[] returnArray = new DhApiTerrainDataPoint[dataColumnIndexCount];
				long dataPoint;
				
				boolean getSpecificYCoordinate = nullableBlockYPos != null;
				int levelMinimumHeight = levelWrapper.getMinHeight();
				
				
				// search for a datapoint that contains the block y position
				for (int i = 0; i < dataColumnIndexCount; i++)
				{
					dataPoint = dataColumn.getLong(i);
					
					if (!getSpecificYCoordinate)
					{
						// if we aren't look for a specific datapoint, add each datapoint to the return array
						returnArray[i] = DhApiTerrainDataPointUtil.createApiDatapoint(levelWrapper.getMinHeight(), mapping, requestedDetailLevel, dataPoint);
					}
					else
					{
						// we are looking for a specific datapoint,
						// don't look at null ones
						if (dataPoint != 0)
						{
							int requestedY = nullableBlockYPos;
							int bottomY = FullDataPointUtil.getBottomY(dataPoint) + levelMinimumHeight;
							int height = FullDataPointUtil.getHeight(dataPoint);
							int topY = bottomY + height;
							
							// does this datapoint contain the requested Y position? 
							if (bottomY <= requestedY && requestedY < topY) // blockPositions start from the bottom of the block, thus "<=" for bottomY, just "<" for topY
							{
								// this datapoint contains the requested block position, return it
								DhApiTerrainDataPoint apiTerrainData = DhApiTerrainDataPointUtil.createApiDatapoint(levelWrapper.getMinHeight(), mapping, requestedDetailLevel, dataPoint);
								return DhApiResult.createSuccess(new DhApiTerrainDataPoint[]{apiTerrainData});
							}
						}
					}
				}
				
				// return all collected data
				return DhApiResult.createSuccess(returnArray);
			}
			
			// the requested data wasn't present in this column (and/or the column wasn't able to be accessed/generated)
			return DhApiResult.createSuccess(new DhApiTerrainDataPoint[0]);
		}
		catch (InterruptedException | ExecutionException e)
		{
			// shouldn't normally happen, but just in case
			LOGGER.error("getTerrainDataColumnArray operation canceled. Error: [" + e.getMessage() + "]", e);
			return DhApiResult.createFail("Operation cancled before it could complete: [" + e.getMessage() + "].");
		}
		catch (Exception e)
		{
			// shouldn't normally happen, but just in case
			LOGGER.error("Unexpected exception in getTerrainDataColumnArray. Error: [" + e.getMessage() + "]", e);
			return DhApiResult.createFail("Unexpected exception: [" + e.getMessage() + "].");
		}
		finally
		{
			if (dataCache == null
				&& dataSource != null)
			{
				dataSource.close();
			}
		}
	}
	
	//endregion
	
	
	
	//====================//
	// raycasting methods //
	//====================//
	//region
	
	@Override
	public DhApiResult<DhApiRaycastResult> raycast(
			IDhApiLevelWrapper levelWrapper,
			double rayOriginX, double rayOriginY, double rayOriginZ,
			float rayDirectionX, float rayDirectionY, float rayDirectionZ,
			int maxRayBlockLength,
			@Nullable
			IDhApiTerrainDataCache dataCache)
	{
		return this.raycastLodData(levelWrapper, 
				new DhVec3d(rayOriginX, rayOriginY, rayOriginZ), 
				new DhVec3f(rayDirectionX, rayDirectionY, rayDirectionZ), 
				maxRayBlockLength, dataCache);
	}
	
	/**
	 * private since it uses non-API objects <br><br>
	 *
	 * Works by walking through the world and attempting to get the LOD <br>
	 * data present at each step.
	 */
	private DhApiResult<DhApiRaycastResult> raycastLodData(
			IDhApiLevelWrapper levelWrapper, 
			DhVec3d rayOrigin, DhVec3f rayDirection, 
			int maxRayBlockLength,
			@Nullable
			IDhApiTerrainDataCache dataCache)
	{
		rayDirection.normalize();
		
		int minLevelBlockHeight = levelWrapper.getMinHeight();
		int maxLevelBlockHeight = levelWrapper.getMaxHeight();
		
		
		
		// walk through the grid //
		
		int currentLength = 0;
		
		// the exact position of this step
		DhVec3d exactPos = new DhVec3d(rayOrigin.x, rayOrigin.y, rayOrigin.z);
		// the block position for this step
		DhVec3i blockPos = new DhVec3i((int) Math.round(rayOrigin.x), (int) Math.round(rayOrigin.y), (int) Math.round(rayOrigin.z));
		
		DhApiRaycastResult closetFoundDataPoint = null;
		
		
		while (blockPos.y >= minLevelBlockHeight 
				&& blockPos.y < maxLevelBlockHeight
				&& currentLength <= maxRayBlockLength)
		{
			// get the LOD columns around this position
			ArrayList<DhVec3i> columnPositions = getIntersectingColumnsAtPosition(blockPos, rayDirection);
			for (DhVec3i columnPos : columnPositions)
			{
				// check each column
				DhApiResult<DhApiTerrainDataPoint[]> result = this.getColumnDataAtBlockPos(levelWrapper, columnPos.x, columnPos.z, dataCache);
				if (!result.success)
				{
					// if there was an error, stop and return it
					return DhApiResult.createFail(result.message);
				}
				
				// is there a LOD at this position?
				for (DhApiTerrainDataPoint dataPoint : result.payload)
				{
					// is this LOD air?
					if (dataPoint.blockStateWrapper != null && !dataPoint.blockStateWrapper.isAir())
					{
						// does this LOD contain the given Y position?
						DhVec3i dataPointPos = new DhVec3i(columnPos.x, dataPoint.bottomYBlockPos, columnPos.z);
						if (exactPos.y >= dataPoint.bottomYBlockPos 
							&& exactPos.y <= dataPoint.topYBlockPos)
						{
							if (closetFoundDataPoint == null)
							{
								closetFoundDataPoint = new DhApiRaycastResult(dataPoint, dataPointPos);
							}
							else
							{
								// use the LOD closest to the ray's origin
								double previousDistanceSquared = Math.pow(rayOrigin.x - closetFoundDataPoint.pos.x, 2) + Math.pow(rayOrigin.y - closetFoundDataPoint.pos.y, 2) + Math.pow(rayOrigin.z - closetFoundDataPoint.pos.z, 2);
								double newDistanceSquared = Math.pow(rayOrigin.x - dataPointPos.x, 2) + Math.pow(rayOrigin.y - dataPointPos.y, 2) + Math.pow(rayOrigin.z - dataPointPos.z, 2);
								
								if (previousDistanceSquared > newDistanceSquared)
								{
									closetFoundDataPoint = new DhApiRaycastResult(dataPoint, dataPointPos);
								}
							}
						}
					}
				}
			}
			
			if (closetFoundDataPoint != null)
			{
				return DhApiResult.createSuccess(closetFoundDataPoint);
			}
			
			
			
			// take the next step in the ray //
			
			exactPos.x += rayDirection.x;
			exactPos.y += rayDirection.y;
			exactPos.z += rayDirection.z;
			
			blockPos.x = (int) Math.round(exactPos.x);
			blockPos.y = (int) Math.round(exactPos.y);
			blockPos.z = (int) Math.round(exactPos.z);
			
			// calculate the taxiCab Distance
			currentLength = (int) (Math.abs(rayOrigin.x - exactPos.x) + Math.abs(rayOrigin.y - exactPos.y) + Math.abs(rayOrigin.z - exactPos.z));
		}
		
		return DhApiResult.createSuccess(null);
	}
	
	/**
	 * checks the surrounding 3x3 block columns and returns those that intersect with the ray. <br><br>
	 *
	 * Used to make sure the raycast step doesn't accidentally walk over any adjacent data.
	 */
	private static ArrayList<DhVec3i> getIntersectingColumnsAtPosition(DhVec3i rayEndingPos, DhVec3f rayDirection)
	{
		ArrayList<DhVec3i> returnList = new ArrayList<>(9);
		
		for (int x = -1; x <= 1; x++)
		{
			for (int z = -1; z <= 1; z++)
			{
				DhVec3i pos = new DhVec3i(rayEndingPos.x + x, rayEndingPos.y, rayEndingPos.z + z);
				
				// check if this column is intersected by the ray
				if (RayCastUtil.rayIntersectsSquare(rayEndingPos.x, rayEndingPos.z, rayDirection.x, rayDirection.z, pos.x, pos.z, 1))
				{
					returnList.add(pos);
				}
			}
		}
		
		return returnList;
	}
	
	//endregion
	
	
	
	//================//
	// setter methods //
	//================//
	//region
	
	@Override
	public DhApiResult<Void> overwriteChunkDataAsync(IDhApiLevelWrapper levelWrapper, Object[] chunkObjectArray) throws ClassCastException
	{
		if (!(levelWrapper instanceof ILevelWrapper))
		{
			return DhApiResult.createFail("Level wrapper needs to be an instance of [" + IDhApiLevelWrapper.class.getSimpleName() + "].");
		}
		AbstractDhWorld dhWorld = SharedApi.getAbstractDhWorld();
		if (dhWorld == null)
		{
			return DhApiResult.createFail("No world loaded. This method can only be called while in a loaded world.");
		}
		
		IDhLevel dhLevel = dhWorld.getLevel((ILevelWrapper) levelWrapper);
		if (dhLevel == null)
		{
			return DhApiResult.createFail("No level exists for the given level wrapper. This either means the level hasn't been loaded yet, or was unloaded.");
		}
		
		
		// this will throw a cast exception if the chunk object array isn't correct
		IChunkWrapper chunk = SingletonInjector.INSTANCE.get(IWrapperFactory.class).createChunkWrapper(chunkObjectArray);
		SharedApi.INSTANCE.applyChunkUpdate(chunk, dhLevel.getLevelWrapper(), false);
		
		
		return DhApiResult.createSuccess();
	}
	
	//endregion
	
	
	
	//=============//
	// API helpers //
	//=============//
	//region
	
	@Override
	public IDhApiTerrainDataCache createSoftCache() { return new DhApiTerrainDataCache(); }
	
	//endregion
	
	
	
	//===============//
	// debug methods //
	//===============//
	//region
	
	/**
	 * This method is here for debugging the repo and isn't intended for normal use.
	 * The method need to be async because pausing the main thread with a breakpoint and hot swapping will crash the program
	 */
	public static void asyncDebugMethod(IDhApiLevelWrapper levelWrapper, int blockPosX, int blockPosY, int blockPosZ)
	{
		if (!debugThreadRunning)
		{
			debugThreadRunning = true;
			Thread thread = new Thread(() -> 
			{
				try
				{
					AbstractDebugWireframeRenderer debugRenderer = SingletonInjector.INSTANCE.get(AbstractDebugWireframeRenderer.class);
					
					long nowMs = System.currentTimeMillis();
					if (nowMs - timeSinceLastDebugClear > 5_000L)
					{
						timeSinceLastDebugClear = nowMs;
						DEBUG_DATA_CACHE.clear();
					}
					
					
					DhApiResult<DhApiTerrainDataPoint> single = getTerrainDataAtBlockYPos(levelWrapper, DhSectionPos.encode(LodUtil.BLOCK_DETAIL_LEVEL, blockPosX, blockPosZ), blockPosY, DEBUG_DATA_CACHE);
					DhApiResult<DhApiTerrainDataPoint[]> column = getTerrainDataColumnArray(levelWrapper, DhSectionPos.encode(LodUtil.BLOCK_DETAIL_LEVEL, blockPosX, blockPosZ), null, DEBUG_DATA_CACHE);
					
					long chunkPos = DhSectionPos.encodeContaining(LodUtil.CHUNK_DETAIL_LEVEL, new DhChunkPos(blockPosX, blockPosZ));
					DhApiResult<DhApiTerrainDataPoint[][][]> area = getTerrainDataOverAreaForPositionDetailLevel(levelWrapper, chunkPos, DEBUG_DATA_CACHE);
					
					
					IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
					DhApiResult<DhApiRaycastResult> rayCast = INSTANCE.raycastLodData(levelWrapper, MC_RENDER.getCameraExactPosition(), MC_RENDER.getLookAtVector(), 1000, DEBUG_DATA_CACHE);
					if (rayCast.payload != null 
						&& !rayCast.payload.pos.equals(currentDebugVec3i))
					{
						currentDebugVec3i = rayCast.payload.pos;
						
						// get a string for the block
						String blockString = "[NULL BLOCK]"; // shouldn't normally happen unless there is an issue with getting the terrain at the given position
						if (rayCast.payload.dataPoint.blockStateWrapper != null)
						{
							if (!rayCast.payload.dataPoint.blockStateWrapper.isAir())
							{
								blockString = rayCast.payload.dataPoint.blockStateWrapper.getSerialString();
							}
							else
							{
								blockString = "[AIR]";
							}
						}
						
						int skyLight = rayCast.payload.dataPoint.skyLightLevel;
						int blockLight = rayCast.payload.dataPoint.blockLightLevel;
						
						// can be enabled to debug a whole column
						if (logFullColumnInDebug)
						{
							DhApiTerrainDataPoint[] rayCastColumn = getTerrainDataColumnArray(levelWrapper,
								DhSectionPos.encode(LodUtil.BLOCK_DETAIL_LEVEL, currentDebugVec3i.x, currentDebugVec3i.z),
								null, DEBUG_DATA_CACHE).payload;
							
							StringBuilder str = new StringBuilder("\n");
							if (rayCastColumn != null)
							{
								for (DhApiTerrainDataPoint point : rayCastColumn)
								{
									str.append(point.topYBlockPos + " block: " + point.blockStateWrapper.getSerialString() + " SL: " + point.skyLightLevel + " BL: " + point.blockLightLevel + "\n");
								}
							}
							LOGGER.info(str.toString());
						}
						
						LOGGER.info("raycast: " + currentDebugVec3i + " block: " + blockString + " SL: " + skyLight + " BL: " + blockLight);
					}
					else if (rayCast.payload == null && currentDebugVec3i != null)
					{
						currentDebugVec3i = null;
						LOGGER.info("raycast: [INFINITY]");
					}
					
					// draw raycast position
					if (rayCast.success 
						&& rayCast.payload != null)
					{
						DhApiResult<DhApiTerrainDataPoint[]> raycastCol = getTerrainDataColumnArray(levelWrapper, DhSectionPos.encode((byte) 0, rayCast.payload.pos.x, rayCast.payload.pos.z), null, DEBUG_DATA_CACHE);
						
						if (debugRenderer != null)
						{
							debugRenderer.makeParticle(
								new AbstractDebugWireframeRenderer.BoxParticle(
									new AbstractDebugWireframeRenderer.Box(
										DhSectionPos.encode((byte) 0, rayCast.payload.pos.x, rayCast.payload.pos.z), 
											rayCast.payload.dataPoint.bottomYBlockPos, 
											rayCast.payload.dataPoint.topYBlockPos, 
											-0.1f, Color.RED),
								1.0, 0f
								)
							);
						}
					}
					
					
					int debugPoint = 0; // a place to put a debugger break point
				}
				catch (Exception e)
				{
					LOGGER.error("Test method Error: [" + e.getMessage() + "]", e);
				}
				finally
				{
					debugThreadRunning = false;
				}
			});
			thread.start();
		}
	}
	
	//endregion
	
	
	
}
