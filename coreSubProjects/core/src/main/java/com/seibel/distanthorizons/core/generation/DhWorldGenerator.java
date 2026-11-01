/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2021 Tom Lee (TomTheFurry) & James Seibel (Original code)
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

package com.seibel.distanthorizons.core.generation;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiGeneratorPlan;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dataObjects.transformers.LodDataBuilder;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.level.IDhServerLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.WorldGenUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.worldGeneration.IChunkGenerator;
import com.seibel.distanthorizons.core.wrapperInterfaces.worldGeneration.IRoughGenerator;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IOverrideInjector;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.coreapi.util.BitShiftUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Formerly "BatchGenerator"
 * 
 * @author Leetom
 * @version 2022-12-10
 */
public class DhWorldGenerator implements IDhApiWorldGenerator
{
	private static final IWrapperFactory WRAPPER_FACTORY = SingletonInjector.INSTANCE.get(IWrapperFactory.class);
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	public final IServerLevelWrapper serverLevelWrapper;
	public final IDhServerLevel serverLevel;
	
	public final IChunkGenerator chunkGenerator;
	@Nullable
	public final IRoughGenerator roughGenerator;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public DhWorldGenerator(IDhServerLevel serverLevel)
	{
		this.serverLevel = serverLevel;
		this.serverLevelWrapper = serverLevel.getServerLevelWrapper();
		
		this.chunkGenerator = WRAPPER_FACTORY.createChunkGenerator(serverLevel);
		this.roughGenerator = WRAPPER_FACTORY.createRoughGenerator(serverLevel, this.chunkGenerator);
	}
	
	//endregion
	
	
	
	//=====================//
	// override parameters // 
	//=====================//
	//region
	
	@Override
	public int getPriority() { return IOverrideInjector.CORE_PRIORITY; }
	
	//endregion
	
	
	
	//======================//
	// generator parameters //
	//======================//
	//region
	
	@Override
	public byte getSmallestDataDetailLevel() { return LodUtil.BLOCK_DETAIL_LEVEL; }
	@Override
	public byte getLargestDataDetailLevel() 
	{ 
		if (Config.Common.WorldGenerator.generatorPlan.get().surfaceGenEnabled)
		{
			// we can generate any LOD detail level
			return (byte) (LodUtil.BLOCK_DETAIL_LEVEL + 12);
		}
		else
		{
			// we can only generate chunks
			return LodUtil.BLOCK_DETAIL_LEVEL;
		}
	}
	
	@Override
	public EDhApiWorldGeneratorReturnType getReturnType() { return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES; }
	
	@Override
	public boolean runApiValidation() { return ModInfo.IS_DEV_BUILD; }
	
	//endregion
	
	
	
	//===================//
	// generator methods //
	//===================//
	//region
	
	@Override
	public void preGeneratorTaskStart() { this.chunkGenerator.updateAllFutures(); }
	
	@Override
	public CompletableFuture<Void> generateLod(
		int chunkPosMinX, int chunkPosMinZ,
		int posX, int posZ, byte detailLevel,
		IDhApiFullDataSource pooledFullDataSource,
		EDhApiDistantGeneratorMode generatorMode, ExecutorService worldGeneratorThreadPool,
		Consumer<IDhApiFullDataSource> resultConsumer)
	{
		int widthInBlocks = BitShiftUtil.powerOfTwo(detailLevel + DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
		int widthInChunks = widthInBlocks / LodUtil.CHUNK_WIDTH;
		
		if (chunkGenAllowedForDetailLevel(detailLevel))
		{
			return this.generateChunksAsync(
				chunkPosMinX, chunkPosMinZ,
				widthInChunks,
				pooledFullDataSource,
				generatorMode,
				worldGeneratorThreadPool,
				resultConsumer);
		}
		
		if (this.roughGenerator == null)
		{
			throw new NullPointerException("No rough generator provided, this Minecraft version probably doesn't support rough surface generation.");
		}
		
		
		
		// we want something larger than a chunk,
		// estimate the surface
		return CompletableFuture.runAsync(() ->
			this.roughGenerator.generateSurface(
				chunkPosMinX, chunkPosMinZ,
				posX, posZ, detailLevel,
				pooledFullDataSource,
				generatorMode,
				resultConsumer
			),
		worldGeneratorThreadPool);
	}
	
	/** @see WorldGenUtil#regenAllowed(FullDataSourceProviderV2)  */
	private static boolean chunkGenAllowedForDetailLevel(byte detailLevel)
	{
		EDhApiGeneratorPlan genPlan = Config.Common.WorldGenerator.generatorPlan.get();
		EDhApiDistantGeneratorMode chunkGenMode = Config.Common.WorldGenerator.chunkGeneratorMode.get();
		
		if (!genPlan.chunkGenEnabled)
		{
			return false;
		}
		
		if (detailLevel > 0)
		{
			// chunks can only be generated at detail level 0
			return false;
		}
		
		if (genPlan.surfaceGenEnabled
			&& chunkGenMode == EDhApiDistantGeneratorMode.PRE_EXISTING_ONLY)
		{
			// If surface gen is enabled, ignore any
			// pre-existing chunks since empty/missing chunks will replace the surface
			// generated terrain.
			return false;
		}
		
		return true;
	}
	
	private @NotNull CompletableFuture<Void> generateChunksAsync(
		int chunkPosMinX, int chunkPosMinZ,
		int widthInChunks,
		IDhApiFullDataSource pooledApiDataSource, 
		EDhApiDistantGeneratorMode generatorMode, 
		ExecutorService worldGeneratorThreadPool, 
		Consumer<IDhApiFullDataSource> resultConsumer)
	{
		FullDataSourceV2 pooledFullDataSource = (FullDataSourceV2) pooledApiDataSource; 
		
		EDhApiWorldGenerationStep targetStep;
		switch (generatorMode)
		{
			case PRE_EXISTING_ONLY: // Only load in existing chunks.
				targetStep = EDhApiWorldGenerationStep.EMPTY; // special logic
				break;
			case SURFACE:
				targetStep = EDhApiWorldGenerationStep.SURFACE;
				break;
			case FEATURES:
				targetStep = EDhApiWorldGenerationStep.FEATURES;
				break;
			case INTERNAL_SERVER:
				targetStep = EDhApiWorldGenerationStep.LIGHT;
				break;
			
			default:
				throw new IllegalArgumentException("no target step defined for generator mode: ["+ generatorMode +"].");
		}
		
		
		ArrayList<IChunkWrapper> chunkList = new ArrayList<>(widthInChunks * widthInChunks);
		
		CompletableFuture<Void> genFuture = this.chunkGenerator.queueGenEvent(
			chunkPosMinX, chunkPosMinZ, widthInChunks,
			generatorMode, targetStep,
			worldGeneratorThreadPool, 
			(IChunkWrapper chunkWrapper) ->
			{
				if (chunkWrapper != null)
				{
					chunkList.add(chunkWrapper);
				}
			});
		
		// separate future necessary to make sure the chunks 
		// are processed before this event is
		// marked as completed, recycling the data source
		return genFuture.handle((voidObj, throwable) ->
			{
				for (int i = 0; i < chunkList.size(); i++)
				{
					IChunkWrapper chunkWrapper = chunkList.get(i);
					try (FullDataSourceV2 dataSource = LodDataBuilder.createFromChunk(this.serverLevelWrapper, chunkWrapper))
					{
						if (dataSource != null)
						{
							pooledFullDataSource.updateFromDataSource(dataSource);
						}
					}
				}
				pooledFullDataSource.recordLastSeen();
				resultConsumer.accept(pooledFullDataSource);
				
				return null; // result ignored
			});
	}
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	@Override
	public void close()
	{
		this.chunkGenerator.close();
		
		if (this.roughGenerator != null)
		{
			this.roughGenerator.close();
		}
	}
	
	//endregion
	
	
	
}
