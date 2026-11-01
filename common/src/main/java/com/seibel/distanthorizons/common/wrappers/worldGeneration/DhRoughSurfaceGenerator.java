package com.seibel.distanthorizons.common.wrappers.worldGeneration;

#if MC_VER <= MC_1_18_2

/** 
 * this generator only works for MC 1.19.2 and newer
 * since the older versions don't have the necessary 
 * "RandomState" and "DensityFunction" MC objects needed 
 */
public class DhRoughSurfaceGenerator { }

#else

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import com.seibel.distanthorizons.common.wrappers.block.BiomeWrapper;
import com.seibel.distanthorizons.common.wrappers.block.BlockStateWrapper;
import com.seibel.distanthorizons.common.wrappers.world.ServerLevelWrapper;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListPool;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.worldGeneration.IRoughGenerator;
import com.seibel.distanthorizons.coreapi.util.BitShiftUtil;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.*;

import org.jetbrains.annotations.Nullable;

import javax.annotation.WillNotClose;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

#if MC_VER <= MC_26_2_0
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.core.QuartPos;
#else
import net.minecraft.world.level.levelgen.densityfunction.*;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.util.context.ContextMap;
#endif

public class DhRoughSurfaceGenerator implements IRoughGenerator
{
	public static final DhLogger LOGGER = new DhLoggerBuilder()
			.name("LOD World Gen - Rough Surface")
			.fileLevelConfig(Config.Common.Logging.logWorldGenEventToFile)
			.build();
	
	
	
	private final IServerLevelWrapper serverLevelWrapper;
	
	private static final PhantomArrayListPool ARRAY_LIST_POOL = new PhantomArrayListPool("TestWorldGen");
	
	/**
	 * how far below the candidate height to check.
	 * By default MC samples noise on a 4x8x4 grid (source: BuilderB0y),
	 * so sampling 8 and 8x2 points down respectively should give us
	 * a pretty good guess if the datapoint is a small floating island or not. <br><br>
	 * 
	 * Should be sorted smallest to largest.
	 */
	private static final int[] SANITY_CHECK_DEPTHS = { 4, 8, 16 };
	private static final int[] SANITY_CHECK_HEIGHTS = { 4, 8, 16, 32 };
	
	/** when marching down the world, this is how many blocks we should step at a time */
	private static final int MARCH_STEP = 8;
	
	private static final long NO_HEIGHT_GENERATED = Long.MIN_VALUE;
	private static final int NO_WATER_HEIGHT = Integer.MIN_VALUE;
	
	/** measured in blocks */
	private static final int MAX_UNDERWATER_HEIGHT_DEVIATION = 8;
	
	
	// commonly used blocks cached for quick access
	private final IBlockStateWrapper waterBlock;
	private final IBlockStateWrapper iceBlock;
	private final IBlockStateWrapper snowBlock;
	
	private final GenParams genParams;
	
	/** 
	 * I'd be nice to have this static so we could re-use data across worlds.
	 * However, we don't know what settings each world will have, so
	 * this information may not be valid and should be re-generated.
	 */
	private final ConcurrentHashMap<IBiomeWrapper, BlockCountPair> biomeToBlockWrapper = new ConcurrentHashMap<>();
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public DhRoughSurfaceGenerator(IServerLevelWrapper serverLevelWrapper, DhChunkGenerator dhChunkGenerator)
	{
		this.serverLevelWrapper = serverLevelWrapper;
		
		this.waterBlock = BlockStateWrapper.getWaterBlockStateWrapper(this.serverLevelWrapper);
		this.iceBlock = BlockStateWrapper.getIceBlockStateWrapper(this.serverLevelWrapper);
		this.snowBlock = BlockStateWrapper.getSnowBlockStateWrapper(this.serverLevelWrapper);
		
		this.genParams = new GenParams(dhChunkGenerator, this.serverLevelWrapper);
	}
	
	//endregion
	
	
	
	//============//
	// generation //
	//============//
	//region
	
	@Override
	public void generateSurface(
		int chunkPosMinX, int chunkPosMinZ,
		int posX, int posZ, byte detailLevel,
		IDhApiFullDataSource pooledFullDataSource,
		EDhApiDistantGeneratorMode generatorMode,
		Consumer<IDhApiFullDataSource> resultConsumer)
	{
		ArrayList<DhApiTerrainDataPoint> apiDataPoints = new ArrayList<>();
		int width = pooledFullDataSource.getWidthInDataColumns();
		
		try(PhantomArrayListCheckout checkout = ARRAY_LIST_POOL.checkoutLongArrays(3))
		{
			// we could probably get away with an int or short array,
			// but the checkout didn't handle int arrays at the time of writing
			// and I wanted to make sure we didn't hit any issues
			LongArrayList heightmap = checkout.getLongArray(0, width * width);
			LongArrayList tempHeightmap = checkout.getLongArray(1, width * width);
			LongArrayList tempNeighborHeights = checkout.getLongArray(2, 8); // 3x3 minus the center
			
			
			
			//=====================//
			// find surface height //
			//=====================//
			//region
			
			// clear old height
			for (int x = 0; x < width; x++)
			{
				for (int z = 0; z < width; z++)
				{
					heightmap.set(x + width * z, NO_HEIGHT_GENERATED);
				}
			}
			
			// only generate heights for 1 in 4 columns
			// to significantly speed up the process
			// the in-between columns will be averaged from the generated data.
			for (int x = 0; x < width; x+=2)
			{
				for (int z = 0; z < width; z+=2)
				{
					// convert to block pos
					int blockX = chunkPosMinX * 16 + (x * BitShiftUtil.powerOfTwo(detailLevel));
					int blockZ = chunkPosMinZ * 16 + (z * BitShiftUtil.powerOfTwo(detailLevel));
					
					int maxHeight = findSurfaceHeight(this.genParams, this.serverLevelWrapper, blockX, blockZ);
					maxHeight -= this.serverLevelWrapper.getMinHeight(); // convert to level relative position
					
					heightmap.set(x + width * z, maxHeight);
				}
			}
			
			// necessary to clean up random water pockets on the surface
			smoothUnderwaterSpikes(width, 
				heightmap, 
				tempHeightmap, tempNeighborHeights,
				this.genParams.relativeSeaLevel,
				MAX_UNDERWATER_HEIGHT_DEVIATION);
			
			// interp heights
			for (int x = 0; x < width; x++)
			{
				for (int z = 0; z < width; z++)
				{
					long maxHeightLong = heightmap.getLong(x + width * z);
					if (maxHeightLong == NO_HEIGHT_GENERATED)
					{
						maxHeightLong = interpHeightFromAdjacentValues(x, z, width, heightmap);
						heightmap.set(x + width * z, maxHeightLong);
					}
				}
			}
			
			//endregion
			
			
			
			//=====================//
			// populate datasource //
			//=====================//
			//region
			
			for (int x = 0; x < width; x++)
			{
				for (int z = 0; z < width; z++)
				{
					// convert to block pos
					int blockX = chunkPosMinX * 16 + (x * BitShiftUtil.powerOfTwo(detailLevel));
					int blockZ = chunkPosMinZ * 16 + (z * BitShiftUtil.powerOfTwo(detailLevel));
					
					
					// surface height
					int surfaceHeight = (int) heightmap.getLong(x + width * z);
					
					// water height
					int waterHeight = NO_WATER_HEIGHT;
					if (surfaceHeight < this.genParams.relativeSeaLevel)
					{
						// if the surface is 
						waterHeight = this.genParams.relativeSeaLevel;
					}
					
					
					// biome
					IBiomeWrapper biomeWrapper = getBiomeAtBlockPos(
						this.genParams, this.serverLevelWrapper,
						blockX,
						surfaceHeight,
						blockZ);
					boolean isColdBiome = biomeWrapper.isColdBiome();
					
					
					// surface block
					IBlockStateWrapper surfaceBlock
						= this.getSurfaceBlockState(
							biomeWrapper,
							blockX, blockZ);
					
					
					// populate datasource
					this.populateApiDataPoints(
						pooledFullDataSource, apiDataPoints, 
						waterHeight, surfaceHeight, 
						isColdBiome, surfaceBlock, biomeWrapper, 
						this.genParams.relativeMaxHeight, 
						x, z);
				}
			}
		}
		
		//endregion
		
		resultConsumer.accept(pooledFullDataSource);
	}
	private void populateApiDataPoints(
		IDhApiFullDataSource pooledFullDataSource, ArrayList<DhApiTerrainDataPoint> apiDataPoints, 
		int waterHeight, int surfaceHeight, 
		boolean isColdBiome, 
		IBlockStateWrapper surfaceBlock, IBiomeWrapper biomeWrapper, 
		int relativeMaxHeight, 
		int x, int z)
	{
		// clear the pooled array before we start populating it
		apiDataPoints.clear();
		
		
		// surface light
		int surfaceSkyLight = LodUtil.MAX_MC_LIGHT;
		if (waterHeight != NO_WATER_HEIGHT)
		{
			surfaceSkyLight -= (waterHeight - surfaceHeight);
			if (surfaceSkyLight < LodUtil.MIN_MC_LIGHT)
			{
				surfaceSkyLight = LodUtil.MIN_MC_LIGHT;
			}
		}
		else
		{
			// uncovered surface blocks use snow in cold biomes
			if (isColdBiome)
			{
				surfaceBlock = this.snowBlock;
			}
		}
		
		
		// surface
		if (surfaceHeight != 0) // will be 0 the column is only air (ie The End)
		{
			apiDataPoints.add(DhApiTerrainDataPoint.create((byte) 0, surfaceBlock.getLightEmission(), surfaceSkyLight, 0, surfaceHeight,
				surfaceBlock, biomeWrapper));
		}
		
		
		// water
		if (waterHeight != NO_WATER_HEIGHT)
		{
			if (isColdBiome)
			{
				int waterHeightDiff = (waterHeight - surfaceHeight);
				if (waterHeightDiff >= 2)
				{
					// under-ice water
					apiDataPoints.add(DhApiTerrainDataPoint.create((byte) 0, 0, LodUtil.MAX_MC_LIGHT - 1, surfaceHeight, waterHeight - 1,
						this.waterBlock, biomeWrapper));
					
					// surface ice
					apiDataPoints.add(DhApiTerrainDataPoint.create((byte) 0, 0, LodUtil.MAX_MC_LIGHT, waterHeight - 1, waterHeight,
						this.iceBlock, biomeWrapper));
				}
				else if (waterHeightDiff == 1)
				{
					// ice 
					apiDataPoints.add(DhApiTerrainDataPoint.create((byte) 0, 0, LodUtil.MAX_MC_LIGHT, surfaceHeight, waterHeight,
						this.iceBlock, biomeWrapper));
				}
			}
			else
			{
				apiDataPoints.add(DhApiTerrainDataPoint.create((byte) 0, 0, LodUtil.MAX_MC_LIGHT, surfaceHeight, waterHeight,
					this.waterBlock, biomeWrapper));
			}
			
			
			// update the surface height for the air step
			surfaceHeight = waterHeight;
		}
		
		
		// air to world height
		apiDataPoints.add(DhApiTerrainDataPoint.create((byte) 0, 0, LodUtil.MAX_MC_LIGHT, surfaceHeight, relativeMaxHeight,
			BlockStateWrapper.AIR, biomeWrapper));
		
		pooledFullDataSource.setApiDataPointColumn(x, z, EDhApiWorldGenerationStep.SURFACE, apiDataPoints);
	}
	
	
	private static long interpHeightFromAdjacentValues(int x, int z, int width, LongArrayList heightmap)
	{
		long maxHeightLong;
		
		int x0 = (x / 2) * 2;
		int z0 = (z / 2) * 2;
		// clamp to width - 2 since that's the last known column/row
		int x1 = Math.min(x0 + 2, width - 2);
		int z1 = Math.min(z0 + 2, width - 2);
		
		long h00 = heightmap.getLong(x0 + width * z0);
		long h10 = heightmap.getLong(x1 + width * z0);
		long h01 = heightmap.getLong(x0 + width * z1);
		long h11 = heightmap.getLong(x1 + width * z1);
		
		double xLerp = (x1 != x0) ? (double) (x - x0) / (x1 - x0) : 0.0;
		double zLerp = (z1 != z0) ? (double) (z - z0) / (z1 - z0) : 0.0;
		
		double top = h00 + (h10 - h00) * xLerp;
		double bottom = h01 + (h11 - h01) * xLerp;
		double interpolated = top + (bottom - top) * zLerp;
		
		maxHeightLong = Math.round(interpolated);
		return maxHeightLong;
	}
	
	/**
	 * Raises datapoints that are below sealevel and are significantly
	 * different from the surrounding area. <Br>
	 * This is done to fix issues where there are singular pockets of
	 * water on the surface that don't match the actual terrain.
	 *
	 * @param maxDeviation how far in blocks a datapoint's height can differ from its
	 *                      neighbors' median before it's considered a spike
	 */
	private static void smoothUnderwaterSpikes(
		int width, 
		LongArrayList heightmap, 
		LongArrayList tempHeightmap, LongArrayList neighborHeights,
		int relativeSeaLevel, int maxDeviation)
	{
		// snapshot the original heights so we don't accidentally
		// sample against modified data
		LongArrayList originalHeightmap = tempHeightmap; // rename for clarity
		originalHeightmap.clear();
		originalHeightmap.addAll(0, heightmap);
		
		neighborHeights.clear();
		
		for (int x = 0; x < width; x+=2)
		{
			for (int z = 0; z < width; z+=2)
			{
				long centerHeight = originalHeightmap.getLong(x + width * z);
				if (centerHeight == NO_HEIGHT_GENERATED)
				{
					// shouldn't happen
					// ignore anything that isn't generated
					continue;
				}
				
				if (centerHeight > relativeSeaLevel)
				{
					// Only worry about datapoints that drop below sea level.
					// Smoothing out datapoints above sea level makes things look too smooth.
					// But not smoothing datapoints below sea level cause sampling issues
					// with caves/divots where the surface goes below sea level.
					continue;
				}
				
				
				
				// get neighbors //
				//region
				
				int neighborCounts = 0;
				for (int diffX = -2; diffX <= 2; diffX += 2)
				{
					for (int diffZ = -2; diffZ <= 2; diffZ += 2)
					{
						if (diffX == 0 
							&& diffZ == 0)
						{
							// ignore the center
							continue;
						}
						
						int neighborX = x + diffX;
						int neighborZ = z + diffZ;
						if (neighborX < 0 || neighborX >= width 
							|| neighborZ < 0 || neighborZ >= width)
						{
							// ignore points outside the heightmap
							continue;
						}
						
						long neighborVal = originalHeightmap.getLong(neighborX + width * neighborZ);
						if (neighborVal == NO_HEIGHT_GENERATED)
						{
							// ignore ungenerated points
							// shouldn't happen
							continue;
						}
						
						neighborHeights.add(neighborVal);
						neighborCounts++;
					}
				}
				
				//endregion
				
				
				if (neighborCounts == 0)
				{
					// shouldn't happen, but just in case
					continue;
				}
				
				neighborHeights.sort(Long::compare);
				long medianHeight = neighborHeights.getLong(neighborCounts / 2);
				
				boolean centerIsLower = centerHeight < medianHeight;
				if (!centerIsLower)
				{
					// only raise lower positions, don't drop higher ones
					continue;
				}
				
				
				
				// Only change the center height if it's significantly different
				// to the surrounding median.
				long centerDeviation = Math.abs(centerHeight - medianHeight);
				if (centerDeviation > maxDeviation)
				{
					heightmap.set(x + width * z, medianHeight);
				}
			}
		}
	}
	
	//endregion
	
	
	
	//=====================//
	// block getting logic //
	//=====================//
	//region
	
	/**
	 * Guesses which block is most likely to be 
	 * the surface for a given biome. <br>
	 * May change as more chunks are generated.
	 */
	private IBlockStateWrapper getSurfaceBlockState(
		IBiomeWrapper desiredBiomeWrapper,
		int blockX, int blockZ)
	{
		// use the existing mapping if available
		BlockCountPair existingBlockCountPair = this.biomeToBlockWrapper.get(desiredBiomeWrapper);
		if (existingBlockCountPair != null)
		{
			return existingBlockCountPair.blockStateWrapper;
		}
		
		
		
		//=======================//
		// generate chunks to    //
		// determine the surface //
		// block                 //
		//=======================//
		//region
		
		AtomicReference<IBlockStateWrapper> fallbackBlockRef = new AtomicReference<IBlockStateWrapper>(null);
		
		HashMap<IBiomeWrapper, HashMap<IBlockStateWrapper, Integer>> biomeBlockCounts = new HashMap<>();
		{
			DhBlockPos2D centerBlockPos = new DhBlockPos2D(blockX, blockZ);
			DhChunkPos centerChunkPos = new DhChunkPos(centerBlockPos);
			
			// subtract 2 from each chunk pos so the target chunk is near the center
			DhChunkPos genMinChunkPos = new DhChunkPos(
				centerChunkPos.getX() - 1,
				centerChunkPos.getZ() - 1);
			
			ChunkGenEvent genEvent = new ChunkGenEvent(
				genMinChunkPos,
				// 4 chunks wide mean we get 1 to 2 chunks of buffer around the target position
				// hopefully this is enough to get a decent example of what the target chunk looks like
				4,
				this.genParams.dhChunkGenerator,
				EDhApiDistantGeneratorMode.SURFACE, EDhApiWorldGenerationStep.SURFACE,
				/*loadChunksFromDisk*/ false,
				(IChunkWrapper chunkWrapper) ->
				{
					for (int relX = 0; relX < LodUtil.CHUNK_WIDTH; relX++)
					{
						for (int relZ = 0; relZ < LodUtil.CHUNK_WIDTH; relZ++)
						{
							int height = chunkWrapper.getSolidHeightMapValue(relX, relZ);
							
							IBiomeWrapper biome = chunkWrapper.getBiome(relX, height, relZ);
							IBlockStateWrapper block = chunkWrapper.getBlockState(relX, height, relZ);
							
							HashMap<IBlockStateWrapper, Integer> blockCounts = biomeBlockCounts.computeIfAbsent(biome, b -> new HashMap<>());
							blockCounts.merge(block, 1, Integer::sum);
							
							// fallback in the off chance that every biome the chunk generates is invalid
							if (fallbackBlockRef.get() == null
								&& chunkWrapper.getChunkPos().equals(centerChunkPos)
								&& chunkWrapper.getChunkPos().contains(centerBlockPos))
							{
								fallbackBlockRef.set(block);
							}
						}
					}
				});
			this.genParams.dhChunkGenerator.generateChunks(genEvent);
		}
		
		//endregion
		
		
		
		//===================//
		// process generated //
		// biome/block pairs //
		//===================//
		//region
		
		for (IBiomeWrapper biome : biomeBlockCounts.keySet())
		{
			BlockCountPair newPair = getMostCommonBlockForBiomeFromMap(biomeBlockCounts, biome);
			if (newPair == null)
			{
				continue;
			}
			
			// require a moderate number of surface blocks be found
			// to prevent tiny biomes skewing the data
			if (newPair.count < 32)
			{
				continue;
			}
			
			
			// add this biome/block
			if (!this.biomeToBlockWrapper.containsKey(biome))
			{
				this.biomeToBlockWrapper.put(biome, newPair);
			}
			else
			{
				// replace the pair if it has a higher count than the previous best
				this.biomeToBlockWrapper.compute(biome, (IBiomeWrapper existingBiome, BlockCountPair existingPair) ->
				{
					if (existingPair == null
						|| existingPair.count < newPair.count)
					{
						return newPair;
					}
					
					return existingPair;
				});
			}
		}
		
		existingBlockCountPair = this.biomeToBlockWrapper.get(desiredBiomeWrapper);
		if (existingBlockCountPair != null)
		{
			return existingBlockCountPair.blockStateWrapper;
		}
		
		//endregion
		
		
		
		BlockCountPair pair = getMostCommonBlockForBiomeFromMap(biomeBlockCounts, desiredBiomeWrapper);
		if (pair != null)
		{
			// if we didn't find enough blocks to normally consider this
			// biome as "found"
			// use whatever we did find as a base
			this.biomeToBlockWrapper.putIfAbsent(desiredBiomeWrapper, pair);
		}
		
		BlockCountPair foundBlockPair = this.biomeToBlockWrapper.get(desiredBiomeWrapper);
		if (foundBlockPair != null)
		{
			return foundBlockPair.blockStateWrapper;
		}
		
		// if nothing was found that likely means the biomes in the chunk are corrupted
		// and/or invalid, just return the block in the exact center of
		// the generated area
		if (fallbackBlockRef.get() != null)
		{
			pair = new BlockCountPair(fallbackBlockRef.get(), 1);
			this.biomeToBlockWrapper.putIfAbsent(desiredBiomeWrapper, pair);
			return pair.blockStateWrapper;
		}
		
		
		// if no blocks were found for this biome at all
		// (first off: how?)
		// use dirt as a sane base
		{
			BlockStateWrapper dirtBlock = BlockStateWrapper.getDirtBlockStateWrapper(this.serverLevelWrapper);
			pair = new BlockCountPair(dirtBlock, 0);
			this.biomeToBlockWrapper.putIfAbsent(desiredBiomeWrapper, pair);
			return pair.blockStateWrapper;
		}
	}
	
	@Nullable
	private static BlockCountPair getMostCommonBlockForBiomeFromMap(
		HashMap<IBiomeWrapper, HashMap<IBlockStateWrapper, Integer>> biomeBlockCounts, IBiomeWrapper biome)
	{
		HashMap<IBlockStateWrapper, Integer> blockCounts = biomeBlockCounts.get(biome);
		if (blockCounts == null || blockCounts.isEmpty())
		{
			return null;
		}
		
		IBlockStateWrapper mostCommonBlock = null;
		int highestCount = -1;
		
		for (HashMap.Entry<IBlockStateWrapper, Integer> entry : blockCounts.entrySet())
		{
			if (entry.getValue() > highestCount)
			{
				highestCount = entry.getValue();
				mostCommonBlock = entry.getKey();
			}
		}
		
		return new BlockCountPair(mostCommonBlock, highestCount);
	}
	
	//endregion
	
	
	
	//=============//
	// biome logic //
	//=============//
	//region
	
	private static IBiomeWrapper getBiomeAtBlockPos(
		GenParams genParams, IServerLevelWrapper serverLevelWrapper,
		int blockX, int blockY, int blockZ)
	{
		#if MC_VER <= MC_26_2_0
		Holder<Biome> biomeHolder = genParams.biomeSource.getNoiseBiome(
			QuartPos.fromBlock(blockX), // x
			QuartPos.fromBlock(blockY), // y
			QuartPos.fromBlock(blockZ), // z
			genParams.randomState.sampler()
		);
		IBiomeWrapper biomeWrapper = BiomeWrapper.getBiomeWrapper(biomeHolder, serverLevelWrapper);
		return biomeWrapper;
		#else
		Pair<BlockPos, Holder<Biome>> biomeHolderPair = genParams.biomeSource.findClosestBiome3d(
			new BlockPos(blockX, blockY, blockZ),
			1, // search Radius // only 1 should be necessary, but 
			1, // sampleResolutionHorizontal,
			1, // sampleResolutionVertical
			(testBiomeHolder) -> true, // return any biome the source can give us
			genParams.randomState,
			genParams.serverLevel
		);
		
		IBiomeWrapper biomeWrapper;
		if (biomeHolderPair != null
			&& biomeHolderPair.getSecond() != null)
		{
			biomeWrapper = BiomeWrapper.getBiomeWrapper(biomeHolderPair.getSecond(), serverLevelWrapper);
		}
		else
		{
			// if there's an issue we may want to return "plains" instead (that's MC's default biome)
			// but for now this should work
			biomeWrapper = BiomeWrapper.EMPTY_WRAPPER;
		}
		
		return biomeWrapper;
		#endif
	}
	
	//endregion
	
	
	
	//=====================//
	// noise surface logic //
	//=====================//
	//region
	
	private static int findSurfaceHeight(GenParams genParams, ILevelWrapper levelWrapper, int blockX, int blockZ)
	{
		// super flat generates differently and
		// must be handled separately
		if (genParams.isSuperFlatWorld)
		{
			return findSuperFlatHeight(genParams, blockX, blockZ);
		}
		
		
		// stat notes:
		// each are with 24 cores for DH
		// 128 render distance
		// terralith world
		// MC 26.2
		
		
		// 15.9 million // 37 sec
		// this is the most accurate but also the slowest (especially for extended height worlds)
		return findSurfaceHeightMarching(genParams, levelWrapper, blockX, blockZ);
		
		
		//// 3.3 million // 23 sec
		//// this is the fastest but most likely to have incorrect height if overhangs exist
		//return binarySearchSurfaceHeight(levelWrapper, blockX, blockZ);
		
		
		//// 5.3 million // 27 sec
		//// middle ground between binary search for best-case scenarios
		//// and marching for accuracy
		//// can have issues with large caverns
		//int candidate = binarySearchSurfaceHeight(levelWrapper, blockX, blockZ);
		//
		//if (sanityCheckSurface(levelWrapper, blockX, blockZ, candidate))
		//{
		//	return candidate;
		//}
		//
		//// fall back to the slower, anomaly-aware marching approach
		//return findSurfaceHeightMarching(genParams, levelWrapper, blockX, blockZ);
	}
	
	private static int findSuperFlatHeight(GenParams genParams, int blockX, int blockZ)
	{
		// if a height hasn't been found yet,
		// calculate it from a couple of actually generated chunks
		if (genParams.superFlatHeight == Integer.MIN_VALUE)
		{
			// this will fire on multiple threads the first time,
			// but that isn't a big deal
			
			AtomicInteger heightSumRef = new AtomicInteger(0);
			AtomicInteger heightCountRef = new AtomicInteger(0);
			
			// center pos shouldn't matter, but we'll do one at the requested position just in case
			DhChunkPos chunkPos = new DhChunkPos(new DhBlockPos2D(blockX, blockZ));
			ChunkGenEvent genEvent = new ChunkGenEvent(
				chunkPos,
				2,
				genParams.dhChunkGenerator,
				EDhApiDistantGeneratorMode.SURFACE, EDhApiWorldGenerationStep.SURFACE,
				/*loadChunksFromDisk*/ false,
				(IChunkWrapper chunkWrapper) ->
				{
					for (int x = 0; x < LodUtil.CHUNK_WIDTH; x++)
					{
						for (int z = 0; z < LodUtil.CHUNK_WIDTH; z++)
						{
							int height = chunkWrapper.getSolidHeightMapValue(x, z);
							heightSumRef.addAndGet(height);
							heightCountRef.incrementAndGet();
						}
					}
				});
			genParams.dhChunkGenerator.generateChunks(genEvent);
			
			int averageHeight = heightSumRef.get() / heightCountRef.get();
			genParams.superFlatHeight = averageHeight;
		}
		
		// using a cached value makes world gen very fast
		return genParams.superFlatHeight;
	}
	
	/** 
	 * May behave strangely if the world has very large overhangs. <br>
	 * But most don't so this is a very fast way to find the surface height.
	 */
	private static int binarySearchSurfaceHeight(
		GenParams genParams,
		ILevelWrapper levelWrapper,
		int blockX, int blockZ)
	{
		int nonSolidY = levelWrapper.getMaxHeight();
		int solidY = levelWrapper.getMinHeight() - 1;
		
		
		// just in case the max height is solid
		if (isNoiseSolidAtBlockPos(genParams, blockX, nonSolidY, blockZ))
		{
			return nonSolidY + 1;
		}
		
		
		// binary search
		while (nonSolidY - solidY > 1)
		{
			int mid = (int)((nonSolidY / 2.0) + (solidY / 2.0));
			if (isNoiseSolidAtBlockPos(genParams, blockX, mid, blockZ))
			{
				solidY = mid;
			}
			else
			{
				nonSolidY = mid;
			}
		}
		
		return solidY + 1;
	}
	
	
	private static int findSurfaceHeightMarching(
		GenParams genParams,
		ILevelWrapper levelWrapper,
		int blockX, int blockZ)
	{
		int top = levelWrapper.getMaxHeight();
		int bottom = levelWrapper.getMinHeight();
		int prevY = top;
		int y = (top - MARCH_STEP);
		
		while (y >= bottom)
		{
			if (isNoiseSolidAtBlockPos(genParams, blockX, y, blockZ))
			{
				return binaryFindSurfaceHeight(
					genParams,
					blockX, blockZ, 
					prevY, y) + 1;
			}
			else
			{
				prevY = y;
				y -= MARCH_STEP;
			}
		}
		
		// should only happen on empty worlds (ie the end)
		return levelWrapper.getMinHeight();
	}
	
	private static int binaryFindSurfaceHeight(
		GenParams genParams, 
		int blockX, int blockZ, 
		int highNonSolidY, int lowSolidY)
	{
		while (highNonSolidY - lowSolidY > 1)
		{
			int mid = (int)((highNonSolidY / 2.0) + (lowSolidY / 2.0));
			if (isNoiseSolidAtBlockPos(genParams, blockX, mid, blockZ))
			{
				lowSolidY = mid;
			}
			else
			{
				highNonSolidY = mid;
			}
		}
		
		return lowSolidY;
	}
	
	
	/**
	 * Checks a few positions around the given height
	 * to confirm it is the highest solid point. <Br><Br>
	 * 
	 * This is helpful for validating worlds with overhangs that would cause
	 * the binary search to find the wrong solid point.
	 */
	private static boolean sanityCheckSurface(
		GenParams genParams, ILevelWrapper levelWrapper,
		int blockX, int blockZ, int candidateSurfaceY)
	{
		int solidTopY = candidateSurfaceY - 1;
		
		// look below the point
		int levelMinY = levelWrapper.getMinHeight();
		for (int depth : SANITY_CHECK_DEPTHS)
		{
			int checkY = solidTopY - depth;
			if (checkY < levelMinY)
			{
				// no need to check below the world
				break;
			}
			
			if (!isNoiseSolidAtBlockPos(genParams, blockX, checkY, blockZ))
			{
				// there is empty space below us
				return false;
			}
		}
		
		
		// look above the point
		int levelMaxY = levelWrapper.getMaxHeight();
		for (int height : SANITY_CHECK_HEIGHTS)
		{
			int checkY = solidTopY + height;
			if (checkY >= levelMaxY)
			{
				// no need to check above the world
				break;
			}
			
			if (isNoiseSolidAtBlockPos(genParams, blockX, checkY, blockZ))
			{
				// there is something solid above us
				return false;
			}
		}
		
		return true;
	}
	
	
	private static boolean isNoiseSolidAtBlockPos(GenParams genParams, int blockX, int blockY, int blockZ)
	{
		#if MC_VER <= MC_26_2_0
		return genParams.density
			.compute(new DensityFunction.SinglePointContext(blockX, blockY, blockZ)) > 0.0;
		#else
		return genParams.density.sampleValue(blockX, blockY, blockZ) > 0.0;
		#endif
	}
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	@Override 
	public void close()
	{
		// nothing currently needed
	}
	
	//endregion
	
	
	
	//================//
	// helper classes //
	//================//
	//region
	
	private static class GenParams
	{
		/** needed to generate chunks surfaces to determine biome block mappings */
		@WillNotClose
		public final DhChunkGenerator dhChunkGenerator;
		
		public ServerLevel serverLevel;
		public RandomState randomState;
		public ChunkGenerator chunkGenerator;
		public BiomeSource biomeSource;
		
		public int relativeSeaLevel;
		public int relativeMaxHeight;
		
		#if MC_VER <= MC_26_2_0
		public DensityFunction density;
		#else
		public DensitySampler.Bound density;
		#endif
		
		public boolean isSuperFlatWorld;
		public int superFlatHeight = Integer.MIN_VALUE;
		
		
		
		public GenParams(DhChunkGenerator dhChunkGenerator, IServerLevelWrapper serverLevelWrapper)
		{
			this.dhChunkGenerator = dhChunkGenerator;
			
			this.serverLevel = ((ServerLevelWrapper)serverLevelWrapper).getWrappedMcObject();
			this.randomState = this.serverLevel.getChunkSource().randomState();
			this.chunkGenerator = this.serverLevel.getChunkSource().getGenerator();
			this.biomeSource = this.chunkGenerator.getBiomeSource();
			
			// ServerLevel has an incorrect seaLevel getter for MC 1.21.1, so we need to get it from the
			// chunk generator
			this.relativeSeaLevel = this.chunkGenerator.getSeaLevel() - serverLevelWrapper.getMinHeight();
			this.relativeMaxHeight = serverLevelWrapper.getMaxHeight() - serverLevelWrapper.getMinHeight();
			
			
			// superflat worlds don't have the noise functions we normally use
			this.isSuperFlatWorld = this.chunkGenerator
				.getClass()
				.equals(FlatLevelSource.class);
			
			// the nether can't have a surface generated 
			if (serverLevelWrapper.hasCeiling())
			{
				this.isSuperFlatWorld = true;
			}
			
			
			
			// density setup //
			//region
			
			#if MC_VER <= MC_26_2_0
			
			this.density = this.randomState.router().finalDensity();
			
			#else
			
			ContextMap samplerUserFields = ContextMap.builder()
				.set(Beardifier.CONTEXT_KEY, Beardifier.EMPTY)
				.build();
			
			DensitySamplerSet densitySamplers = this.randomState.samplersWithContext(
				SamplerContext.builder()
					.setUserFields(samplerUserFields)
					.build()
			);
			
			DensityFunction finalDensityFunc = this.randomState.router.finalDensity();
			this.density = densitySamplers.get(finalDensityFunc);
			
			#endif
			//endregion
			
		}
	}
	
	private static class BlockCountPair
	{
		public final IBlockStateWrapper blockStateWrapper;
		public final int count;
		
		public BlockCountPair(IBlockStateWrapper blockStateWrapper, int count)
		{
			this.blockStateWrapper = blockStateWrapper;
			this.count = count;
		}
		
		@Override
		public String toString()
		{
			// count first for easier reading with long block serials
			return this.count + " - " + this.blockStateWrapper.getSerialString();
		}
		
	}
	
	//endregion
	
	
	
}

#endif