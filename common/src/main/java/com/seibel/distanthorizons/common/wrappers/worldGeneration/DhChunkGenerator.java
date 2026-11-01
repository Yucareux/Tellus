/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2021  Tom Lee (TomTheFurry)
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

package com.seibel.distanthorizons.common.wrappers.worldGeneration;

import com.google.common.collect.ImmutableMap;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiGeneratorPlan;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.common.wrappers.McObjectConverter;
import com.seibel.distanthorizons.common.wrappers.world.ServerLevelWrapper;
#if MC_VER > MC_1_12_2
import com.seibel.distanthorizons.common.wrappers.worldGeneration.chunkFileHandling.ChunkFileReader;
import com.seibel.distanthorizons.common.wrappers.worldGeneration.mimicObject.*;
#endif
import com.seibel.distanthorizons.common.wrappers.worldGeneration.params.GlobalWorldGenParams;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.api.internal.chunkUpdating.ChunkUpdateQueueManager;
import com.seibel.distanthorizons.core.api.internal.chunkUpdating.WorldChunkUpdateManager;
import com.seibel.distanthorizons.core.generation.DhLightingEngine;
import com.seibel.distanthorizons.core.level.IDhServerLevel;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.sql.dto.BeaconBeamDTO;
import com.seibel.distanthorizons.core.util.ExceptionUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.TimerUtil;
import com.seibel.distanthorizons.core.util.gridList.ArrayGridList;
import com.seibel.distanthorizons.core.util.objects.UncheckedInterruptedException;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.ChunkLightStorage;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.worldGeneration.IChunkGenerator;
import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

import com.seibel.distanthorizons.coreapi.ModInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

#if MC_VER <= MC_1_12_2
#elif MC_VER <= MC_26_2_0
import com.seibel.distanthorizons.common.wrappers.worldGeneration.step.StepBiomes;
import com.seibel.distanthorizons.common.wrappers.worldGeneration.step.StepFeatures;
import com.seibel.distanthorizons.common.wrappers.worldGeneration.step.StepNoise;
import com.seibel.distanthorizons.common.wrappers.worldGeneration.step.StepStructureReference;
import com.seibel.distanthorizons.common.wrappers.worldGeneration.step.StepStructureStart;
import com.seibel.distanthorizons.common.wrappers.worldGeneration.step.StepSurface;
#else
import com.seibel.distanthorizons.common.wrappers.worldGeneration.step.StepBiomes;
import com.seibel.distanthorizons.common.wrappers.worldGeneration.step.StepFeatures;
import com.seibel.distanthorizons.common.wrappers.worldGeneration.step.StepStructureReference;
import com.seibel.distanthorizons.common.wrappers.worldGeneration.step.StepStructureStart;
import com.seibel.distanthorizons.common.wrappers.worldGeneration.step.StepTerrain;
#endif 

#if MC_VER <= MC_1_12_2
#else
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.DebugLevelSource;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
#endif


#if MC_VER <= MC_1_17_1
#elif MC_VER <= MC_1_19_2
import net.minecraft.core.Registry;
#elif MC_VER <= MC_1_19_4
import net.minecraft.core.registries.Registries;
#elif MC_VER < MC_1_21_9
import net.minecraft.core.registries.Registries;
#else
#endif

#if MC_VER <= MC_1_12_2
#elif MC_VER <= MC_1_20_4
import net.minecraft.world.level.chunk.ChunkStatus;
#else
import net.minecraft.world.level.chunk.status.ChunkStatus;
#endif


public final class DhChunkGenerator implements IChunkGenerator
{
	public static final DhLogger LOGGER = new DhLoggerBuilder()
		.name("LOD World Gen")
		.fileLevelConfig(Config.Common.Logging.logWorldGenEventToFile)
		.build();
	
	public static final DhLogger RATE_LIMITED_LOGGER = new DhLoggerBuilder()
		.name("LOD World Gen")
		.maxCountPerSecond(1)
		.build();
	
	@NotNull
	public static final ImmutableMap<EDhApiWorldGenerationStep, Integer> WORLD_GEN_CHUNK_BORDER_NEEDED_BY_GEN_STEP;
	public static final int MAX_WORLD_GEN_CHUNK_BORDER_NEEDED;
	
	public static final long EXCEPTION_TIMER_RESET_TIME = TimeUnit.NANOSECONDS.convert(1, TimeUnit.SECONDS);
	public static final int EXCEPTION_COUNTER_TRIGGER = 20;
	
	/**
	 * Used to revert the ignore logic in {@link SharedApi} so
	 * that a given chunk pos can be handled again.
	 * A timer is used so we don't have to inject into MC's code and it works sell enough
	 * most of the time.
	 * If a chunk does get through due the timeout not being long enough that isn't the end of the world.
	 */
	private static final int MS_TO_IGNORE_CHUNK_AFTER_COMPLETION = 5_000;
	
	
	
	private final IDhServerLevel dhServerLevel;
	@Nullable
	private final ChunkUpdateQueueManager updateManager;
	
	public final DhInternalServerGenerator internalServerGenerator;
	
	#if MC_VER > MC_1_12_2
	public final ChunkFileReader chunkFileReader;
	#endif
	
	private final Timer chunkSaveIgnoreTimer = TimerUtil.CreateTimer("ChunkSaveIgnoreTimer");
	
	
	
	public final LinkedBlockingQueue<ChunkGenEvent> generationEventQueue = new LinkedBlockingQueue<>();
	public final GlobalWorldGenParams globalParams;
	
	#if MC_VER <= MC_1_12_2
	#elif MC_VER <= MC_26_2_0
	public final StepStructureStart stepStructureStart = new StepStructureStart(this);
	public final StepStructureReference stepStructureReference = new StepStructureReference(this);
	public final StepBiomes stepBiomes = new StepBiomes(this);
	public final StepNoise stepNoise = new StepNoise(this);
	public final StepSurface stepSurface = new StepSurface(this);
	public final StepFeatures stepFeatures = new StepFeatures(this);
	#else 
	public final StepStructureStart stepStructureStart = new StepStructureStart(this);
	public final StepStructureReference stepStructureReference = new StepStructureReference(this);
	public final StepBiomes stepBiomes = new StepBiomes(this);
	public final StepTerrain stepTerrain = new StepTerrain(this);
	public final StepFeatures stepFeatures = new StepFeatures(this);
	#endif
	
	public boolean unsafeThreadingRecorded = false;
	public boolean generatedChunkWithoutBiomeWarningLogged = false;
	public int unknownExceptionCount = 0;
	public long lastExceptionTriggerTime = 0;
	
	
	
	//==============//
	// constructors //
	//==============//
	
	static
	{
		ImmutableMap.Builder<EDhApiWorldGenerationStep, Integer> builder = ImmutableMap.builder();
		builder.put(EDhApiWorldGenerationStep.EMPTY, 1);
		builder.put(EDhApiWorldGenerationStep.STRUCTURE_START, 0);
		builder.put(EDhApiWorldGenerationStep.STRUCTURE_REFERENCE, 0);
		builder.put(EDhApiWorldGenerationStep.BIOMES, 0);
		builder.put(EDhApiWorldGenerationStep.NOISE, 0);
		builder.put(EDhApiWorldGenerationStep.SURFACE, 0);
		builder.put(EDhApiWorldGenerationStep.CARVERS, 0);
		builder.put(EDhApiWorldGenerationStep.LIQUID_CARVERS, 0);
		builder.put(EDhApiWorldGenerationStep.FEATURES, 0);
		builder.put(EDhApiWorldGenerationStep.TERRAIN, 0);
		builder.put(EDhApiWorldGenerationStep.LIGHT, 0);
		WORLD_GEN_CHUNK_BORDER_NEEDED_BY_GEN_STEP = builder.build();
		
		// validate that all gen steps are populated
		for (EDhApiWorldGenerationStep step : EDhApiWorldGenerationStep.values())
		{
			if (step.value < 0
				|| step == EDhApiWorldGenerationStep.DOWN_SAMPLED)
			{
				// only check world gen steps that can be generated
				continue;
			}
			
			if (!WORLD_GEN_CHUNK_BORDER_NEEDED_BY_GEN_STEP.containsKey(step))
			{
				throw new IllegalStateException("World gen step ["+step+"] doesn't have a chunk border defined.");
			}
		}
		
		// in James' testing as of 2025-09-13 a border here of 2
		// and a getChunkPosToGenerateStream() radius of 14 provided more accurate
		// structure generation, however it also caused extreme server lag
		// a border of 0 here and a getChunkPosToGenerateStream() radius of 8 provided 
		// good-enough structure generation while not lagging the server
		MAX_WORLD_GEN_CHUNK_BORDER_NEEDED = 0;
	}
	
	public DhChunkGenerator(IDhServerLevel dhServerLevel)
	{
		this.dhServerLevel = dhServerLevel;
		this.updateManager = WorldChunkUpdateManager.INSTANCE.getByLevelWrapper(this.dhServerLevel.getServerLevelWrapper());
		this.globalParams = new GlobalWorldGenParams(dhServerLevel);
		this.internalServerGenerator = new DhInternalServerGenerator(this.globalParams, this.dhServerLevel);
		
		#if MC_VER > MC_1_12_2
		this.chunkFileReader = new ChunkFileReader(this.globalParams);
		
		ChunkGenerator generator = ((ServerLevelWrapper) (dhServerLevel.getServerLevelWrapper())).getLevel().getChunkSource().getGenerator();
		boolean isMcGenerator = 
				generator instanceof NoiseBasedChunkGenerator
				|| generator instanceof DebugLevelSource
				|| generator instanceof FlatLevelSource;
		if (!isMcGenerator)
		{
			if (generator.getClass().toString().equals("class com.terraforged.mod.chunk.TFChunkGenerator"))
			{
				LOGGER.info("TerraForge Chunk Generator detected: [" + generator.getClass() + "], Distant Generation will try its best to support it.");
				LOGGER.info("If it does crash, turn Distant Generation off or set it to to [" + EDhApiDistantGeneratorMode.PRE_EXISTING_ONLY + "].");
			}
			else
			{
				LOGGER.warn("Unknown Chunk Generator detected: [" + generator.getClass() + "], Distant Generation May Fail!");
				LOGGER.warn("If it does crash, disable Distant Generation or set the Generation Mode to [" + EDhApiDistantGeneratorMode.PRE_EXISTING_ONLY + "].");
			}
		}
		#endif
		
	}
	
	
	
	//=================//
	// synchronization //
	//=================//
	
	/**
	 * This method checks to make sure that all world gen is being 
	 * run on DH threads instead of leaking out to other MC threads.
	 * This is done to prevent putting undue stress on MC threads
	 * and prevent potential issues with concurrent processing.
	 */
	public <T> T confirmFutureWasRunSynchronously(CompletableFuture<T> future)
	{
		// this operation should be done since DH wants the
		// operation to be done synchronously
		if (!this.unsafeThreadingRecorded && !future.isDone())
		{
			LOGGER.warn(
				"Unsafe MultiThreading in Distant Horizons Chunk Generator. \n" +
					"This can happen if world generation is run on one of Minecraft's thread pools " +
					"instead of the thread DH provided. \n" +
					"This can likely be ignored, however if world generator crashes occur " +
					"setting DH's world generation thread count to 1 may improve stability. ",
				new RuntimeException("Incorrect thread pool use"));
			this.unsafeThreadingRecorded = true;
		}
		
		// if the future wasn't done synchronously,
		// wait for it to finish so we can continue the world gen 
		// lifecycle like normal
		return future.join();
	}
	
	public void updateAllFutures()
	{
		if (this.unknownExceptionCount > 0)
		{
			if (System.nanoTime() - this.lastExceptionTriggerTime >= EXCEPTION_TIMER_RESET_TIME)
			{
				this.unknownExceptionCount = 0;
			}
		}
		
		
		// Update all current out standing jobs
		Iterator<ChunkGenEvent> iter = this.generationEventQueue.iterator();
		while (iter.hasNext())
		{
			ChunkGenEvent event = iter.next();
			if (event.future.isDone())
			{
				if (event.future.isCompletedExceptionally() && !event.future.isCancelled())
				{
					try
					{
						event.future.get(); // Should throw exception
						LodUtil.assertNotReach("Exceptionally completed world gen Future should have thrown an exception.");
					}
					catch (Exception e)
					{
						this.unknownExceptionCount++;
						this.lastExceptionTriggerTime = System.nanoTime();
						LOGGER.error("Batching World Generator event ["+event+"] threw an exception: "+e.getMessage(), e);
					}
				}
				
				iter.remove();
			}
		}
		
		if (this.unknownExceptionCount > EXCEPTION_COUNTER_TRIGGER)
		{
			LOGGER.error("Too many exceptions in Batching World Generator! Disabling the generator.");
			this.unknownExceptionCount = 0;
			Config.Common.WorldGenerator.generatorPlan.set(EDhApiGeneratorPlan.DISABLED);
		}
	}
	
	
	
	//==================//
	// world generation //
	//==================//
	
	/** @throws RejectedExecutionException if the given {@link Executor} is cancelled. */
	public void generateChunks(ChunkGenEvent genEvent) throws RejectedExecutionException
	{
		// Minecraft's generation events expect odd chunk width areas (3x3, 7x7, or 11x11),
		// but DH submits square generation events (4x4).
		// We handle this later, although that handling would need to change if the gen size ever changes.
		LodUtil.assertTrue(genEvent.widthInChunks % 2 == 0, "Generation events are expected to be an evan number of chunks wide.");
		
		if (!DhApi.isDhThread()
			&& ModInfo.IS_DEV_BUILD)
		{
			throw new IllegalStateException("Batch world generation should be called from one of DH's world gen thread. Current thread: ["+Thread.currentThread().getName()+"]");
		}
		
		
		
		//================//
		// variable setup //
		//================//
		
		#if MC_VER <= MC_1_12_2
		// MC 1.12 can only run world gen on the main thread
		this.internalServerGenerator.generateChunksViaInternalServer(genEvent);
		
		#else
		int borderSize = MAX_WORLD_GEN_CHUNK_BORDER_NEEDED;
		// genEvent.size - 1 converts the even width size to an odd number for MC compatability
		int refSize = (genEvent.widthInChunks - 1) + (borderSize * 2);
		int refPosX = genEvent.minChunkPos.getX() - borderSize;
		int refPosZ = genEvent.minChunkPos.getZ() - borderSize;
		
		LightGetterAdaptor lightGetterAdaptor = new LightGetterAdaptor(this.globalParams.mcServerLevel);
		DummyLightEngine dummyLightEngine = new DummyLightEngine(lightGetterAdaptor);
		
		// reused data between each offset
		Map<DhChunkPos, ChunkLightStorage> chunkSkyLightingByDhPos = Collections.synchronizedMap(new HashMap<>());
		Map<DhChunkPos, ChunkLightStorage> chunkBlockLightingByDhPos = Collections.synchronizedMap(new HashMap<>());
		Map<DhChunkPos, ChunkWrapper> chunkWrappersByDhPos = Collections.synchronizedMap(new HashMap<>());
		
		
		
		//================================//
		// read existing chunks from file //
		//================================//
		
		HashMap<DhChunkPos, CompletableFuture<ChunkWrapper>> readFutureByDhChunkPos = new HashMap<>();
		
		Iterator<ChunkPos> existingChunkPosIterator = ChunkPosGenStream.getIterator(
			genEvent.minChunkPos.getX(), genEvent.minChunkPos.getZ(),
			genEvent.widthInChunks,
			// 0 radius -> only pull existing chunks from disk
			0);
		while (existingChunkPosIterator.hasNext())
		{
			ChunkPos chunkPos = existingChunkPosIterator.next();
			DhChunkPos dhChunkPos = McObjectConverter.convert(chunkPos);
			
			CompletableFuture<ChunkWrapper> getExistingChunkFuture
				// running async allows file IO to run in parallel when C2ME is present
				= this.chunkFileReader.createEmptyOrPreExistingChunkWrapperAsync(
					dhChunkPos.getX(), dhChunkPos.getZ(), genEvent.loadChunksFromDisk,
					chunkSkyLightingByDhPos, chunkBlockLightingByDhPos, chunkWrappersByDhPos);
			
			readFutureByDhChunkPos.put(dhChunkPos, getExistingChunkFuture);
		}
		
		// normally DH will handle each of these futures serially
		// but if C2ME is present these will be completed in parallel
		for (CompletableFuture<ChunkWrapper> readChunkFuture : readFutureByDhChunkPos.values())
		{
			readChunkFuture.join();
		}
		
		
		
		//===================================//
		// create empty chunks for world gen //
		//===================================//
		
		Iterator<ChunkPos> emptyChunkPosIterator = ChunkPosGenStream.getIterator(
			genEvent.minChunkPos.getX(), genEvent.minChunkPos.getZ(), 
			genEvent.widthInChunks,
			// the extra radius of 8 is to account for structure references which need a chunk radius of 8
			8);
		while (emptyChunkPosIterator.hasNext())
		{
			ChunkPos chunkPos = emptyChunkPosIterator.next();
			DhChunkPos dhChunkPos = McObjectConverter.convert(chunkPos);
			
			// create empty chunks outside the generation radius
			if (!readFutureByDhChunkPos.containsKey(dhChunkPos))
			{
				ChunkWrapper chunkWrapper = this.chunkFileReader.CreateProtoChunkWrapper(this.globalParams.mcServerLevel, chunkPos);
				chunkWrappersByDhPos.put(dhChunkPos, chunkWrapper);
			}
		}
		
		
		
		//=================//
		// generate chunks //
		//=================//
		
		try
		{
			// offset 1 chunk in both X and Z direction so we can generate an even number of chunks wide
			// while still submitting an odd number width to MC's internal generators
			for (int xOffset = 0; xOffset < 2; xOffset++)
			{
				// final is so the offset can be used in lambdas
				final int xOffsetFinal = xOffset;
				for (int zOffset = 0; zOffset < 2; zOffset++)
				{
					final int zOffsetFinal = zOffset;
					
					
					
					//================//
					// variable setup //
					//================//
					
					int radius = refSize / 2;
					int centerX = refPosX + radius + xOffset;
					int centerZ = refPosZ + radius + zOffset;
					
					// get/create the list of chunks we're going to generate
					IEmptyChunkRetrievalFunc fallbackChunkGetterFunc =
						(chunkPosX, chunkPosZ) -> Objects.requireNonNull(
							chunkWrappersByDhPos.get(new DhChunkPos(chunkPosX, chunkPosZ)).getChunk(),
							() -> String.format("Requested chunk [%d, %d] unavailable during world generation", chunkPosX, chunkPosZ));
					
					ArrayGridList<ChunkAccess> regionChunks = new ArrayGridList<>(
							refSize,
							(relX, relZ) -> fallbackChunkGetterFunc.getChunk(
									relX + refPosX + xOffsetFinal,
									relZ + refPosZ + zOffsetFinal));
					
					ChunkAccess centerChunk = regionChunks.stream()
							#if MC_VER <= MC_1_21_11	
							.filter((chunk) -> chunk.getPos().x == centerX && chunk.getPos().z == centerZ)
							#else
							.filter((chunk) -> chunk.getPos().x() == centerX && chunk.getPos().z() == centerZ)	
							#endif
							.findFirst()
							.orElseGet(() -> regionChunks.getFirst());
					
					DhLitWorldGenRegion region = new DhLitWorldGenRegion(
							centerX, centerZ,
							centerChunk,
							this.globalParams.mcServerLevel, dummyLightEngine, regionChunks,
							ChunkStatus.STRUCTURE_STARTS, radius,
							// this method shouldn't be necessary since we're passing in a pre-populated
							// list of chunks, but just in case
							fallbackChunkGetterFunc
					);
					lightGetterAdaptor.setRegion(region);
					genEvent.threadedParam.makeStructFeatManager(region, this.globalParams);
					
					
					
					//=========================//
					// process existing chunks //
					//=========================//
					
					ArrayGridList<ChunkWrapper> chunkWrapperList = new ArrayGridList<>(regionChunks.gridSize);
					regionChunks.forEachPos((relX, relZ) ->
					{
						// ArrayGridList's use relative positions and don't have a center position
						// so we need to use the offsetFinal to select the correct position
						DhChunkPos chunkPos = new DhChunkPos(relX + refPosX + xOffsetFinal, relZ + refPosZ + zOffsetFinal);
						ChunkAccess chunk = regionChunks.get(relX, relZ);
						
						if (chunkWrappersByDhPos.containsKey(chunkPos))
						{
							chunkWrapperList.set(relX, relZ, chunkWrappersByDhPos.get(chunkPos));
						}
						else if (chunk != null)
						{
							ChunkWrapper chunkWrapper = new ChunkWrapper(chunk, this.dhServerLevel.getLevelWrapper());
							chunkWrapper.createDhHeightMaps();
							chunkWrapperList.set(relX, relZ, chunkWrapper);
							
							// try setting the wrapper's lighting
							if (chunkBlockLightingByDhPos.containsKey(chunkWrapper.getChunkPos()))
							{
								// block
								ChunkLightStorage blockLightStorage = chunkBlockLightingByDhPos.get(chunkWrapper.getChunkPos());
								// if the light storage is empty then we should try generating the lighting
								// ourselves, the light data is probably missing
								if (blockLightStorage != null
									&& !blockLightStorage.isEmpty())
								{
									chunkWrapper.setBlockLightStorage(blockLightStorage);
									chunkWrapper.setIsDhBlockLightCorrect(true);
								}
								
								// sky
								ChunkLightStorage skyLightStorage = chunkSkyLightingByDhPos.get(chunkWrapper.getChunkPos());
								if (skyLightStorage != null
									&& !skyLightStorage.isEmpty())
								{
									chunkWrapper.setSkyLightStorage(skyLightStorage);
									chunkWrapper.setIsDhSkyLightCorrect(true);
								}
							}
							
							chunkWrappersByDhPos.put(chunkPos, chunkWrapper);
						}
						else //if (chunk == null)
						{
							LodUtil.assertNotReach("Programmer Error: No chunk found in grid list, position offset is likely wrong.");
						}
					});
					
					
					
					//=================//
					// generate chunks //
					//=================//
					
					try
					{
						this.generateDirect(genEvent, chunkWrapperList, region);
					}
					catch (InterruptedException e)
					{
						throw new CompletionException(e);
					}
				}
			}
			
			
			
			//=========================//
			// submit generated chunks //
			//=========================//
			
			Iterator<ChunkPos> iterator = ChunkPosGenStream.getIterator(genEvent.minChunkPos.getX(), genEvent.minChunkPos.getZ(), genEvent.widthInChunks, 0);
			while (iterator.hasNext())
			{
				ChunkPos chunkPos = iterator.next();
				DhChunkPos dhChunkPos = McObjectConverter.convert(chunkPos);
				ChunkWrapper wrappedChunk = chunkWrappersByDhPos.get(dhChunkPos);
				
				// only pass along chunks that have been generated up to BIOMES
				// this is to prevent issues with generating existing
				if (wrappedChunk.getStatus().isOrAfter(ChunkStatus.BIOMES))
				{
					genEvent.resultConsumer.accept(wrappedChunk);
				}
				else
				{
					// this shouldn't happen, but if it does log it
					if (!this.generatedChunkWithoutBiomeWarningLogged)
					{
						this.generatedChunkWithoutBiomeWarningLogged = true;
						LOGGER.warn("Chunk [" + dhChunkPos + "] wasn't generated up to BIOMES, world gen may appear empty.");
					}
				}
			}
		}
		catch (CompletionException | UncheckedInterruptedException e)
		{
			// interrupts mean the world generator is being shut down, no need to log that
			boolean isShutdownException = ExceptionUtil.isShutdownException(e);
			if (!isShutdownException)
			{
				LOGGER.error("Completion error during world gen for min chunk pos ["+genEvent.minChunkPos +"], error: ["+e.getMessage()+"].", e);
			}
		}
		catch (Exception e)
		{
			LOGGER.error("Unexpected error during world gen for min chunk pos ["+genEvent.minChunkPos +"], error: ["+e.getMessage()+"].", e);
		}
		#endif
	}
	
	
	
	// direct generation //
	
	#if MC_VER > MC_1_12_2
	public void generateDirect(
			ChunkGenEvent genEvent, ArrayGridList<ChunkWrapper> chunkWrappersToGenerate,
			DhLitWorldGenRegion region) throws InterruptedException
	{
		if (Thread.interrupted())
		{
			return;
		}
		
		try
		{
			chunkWrappersToGenerate.forEach((chunkWrapper) ->
			{
				ChunkAccess chunk = chunkWrapper.getChunk();
				if (chunk instanceof ProtoChunk)
				{
					ProtoChunk protoChunk = ((ProtoChunk) chunk);
					protoChunk.setLightEngine(region.getLightEngine());
				}
				
				// usually ignoring the chunk's position is unnecessary,
				// but this improves performance if a chunk update event does sneak through 
				if (this.updateManager != null)
				{
					this.updateManager.addPosToIgnore(chunkWrapper.getChunkPos());
				}
				
			});
			
			
			// if we're only working with pre-existing chunks,
			// biomes need to be initialized but no other steps should be done
			if (genEvent.generatorMode == EDhApiDistantGeneratorMode.PRE_EXISTING_ONLY)
			{
				this.stepBiomes.generateGroup(genEvent.threadedParam, region, GetCutoutFrom(chunkWrappersToGenerate, EDhApiWorldGenerationStep.BIOMES));
				return;
			}
			
			
			EDhApiWorldGenerationStep step = genEvent.targetGenerationStep;
			if (step == EDhApiWorldGenerationStep.EMPTY)
			{
				// shouldn't normally happen but is here for consistency with the other world gen steps
				return;
			}
			
			throwIfThreadInterrupted();
			this.stepStructureStart.generateGroup(genEvent.threadedParam, region, GetCutoutFrom(chunkWrappersToGenerate, EDhApiWorldGenerationStep.STRUCTURE_START));
			if (step == EDhApiWorldGenerationStep.STRUCTURE_START)
			{
				return;
			}
			
			throwIfThreadInterrupted();
			this.stepStructureReference.generateGroup(genEvent.threadedParam, region, GetCutoutFrom(chunkWrappersToGenerate, EDhApiWorldGenerationStep.STRUCTURE_REFERENCE));
			if (step == EDhApiWorldGenerationStep.STRUCTURE_REFERENCE)
			{
				return;
			}
			
			throwIfThreadInterrupted();
			this.stepBiomes.generateGroup(genEvent.threadedParam, region, GetCutoutFrom(chunkWrappersToGenerate, EDhApiWorldGenerationStep.BIOMES));
			if (step == EDhApiWorldGenerationStep.BIOMES)
			{
				return;
			}
			
			#if MC_VER <= MC_26_2_0
			
			throwIfThreadInterrupted();
			this.stepNoise.generateGroup(genEvent.threadedParam, region, GetCutoutFrom(chunkWrappersToGenerate, EDhApiWorldGenerationStep.NOISE));
			if (step == EDhApiWorldGenerationStep.NOISE)
			{
				return;
			}
			
			throwIfThreadInterrupted();
			this.stepSurface.generateGroup(genEvent.threadedParam, region, GetCutoutFrom(chunkWrappersToGenerate, EDhApiWorldGenerationStep.SURFACE));
			if (step == EDhApiWorldGenerationStep.SURFACE)
			{
				return;
			}
			
			// carvers heightmaps are needed by some world gen mods,
			// otherwise unexpected issues may occur
			// (like trying to use an empty heightmap and walking out the bottom of
			// the world looking for a valid snow location).
			throwIfThreadInterrupted();
			for (ChunkWrapper chunkWrapper : chunkWrappersToGenerate)
			{
				ChunkAccess chunk = chunkWrapper.getChunk();
				Heightmap.primeHeightmaps(chunk, ChunkStatus.CARVERS.heightmapsAfter());
			}
			
			#else
			// Surface and carvers were replaced with "terrain" 
			
			throwIfThreadInterrupted();
			this.stepTerrain.generateGroup(genEvent.threadedParam, region, GetCutoutFrom(chunkWrappersToGenerate, EDhApiWorldGenerationStep.TERRAIN));
			// terrain covers every step it replaced,
			// so a request for any of them (IE the SURFACE generator mode) is complete here
			if (step == EDhApiWorldGenerationStep.NOISE
				|| step == EDhApiWorldGenerationStep.SURFACE
				|| step == EDhApiWorldGenerationStep.CARVERS
				|| step == EDhApiWorldGenerationStep.LIQUID_CARVERS
				|| step == EDhApiWorldGenerationStep.TERRAIN)
			{
				return;
			}
			
			#endif
			
			throwIfThreadInterrupted();
			this.stepFeatures.generateGroup(genEvent.threadedParam, region, GetCutoutFrom(chunkWrappersToGenerate, EDhApiWorldGenerationStep.FEATURES));
		}
		finally
		{
			// generate lighting using DH's lighting engine
				
			int maxSkyLight = this.dhServerLevel.getServerLevelWrapper().hasSkyLight() ? 15 : 0;
			
			// only light generated chunks,
			// attempting to light un-generated chunks will cause lighting issues on bordering generated chunks
			ArrayList<IChunkWrapper> iChunkWrapperList = new ArrayList<>();
			for (int i = 0; i < chunkWrappersToGenerate.size(); i++) // regular for loop since enhanced for loops increase GC pressure slightly
			{
				ChunkWrapper chunkWrapper = chunkWrappersToGenerate.get(i);
				if (chunkWrapper.getStatus() != ChunkStatus.EMPTY)
				{
					iChunkWrapperList.add(chunkWrapper);
				}
			}
			
			// light each chunk in the list
			for (int i = 0; i < iChunkWrapperList.size(); i++)
			{
				ChunkWrapper centerChunkWrapper = (ChunkWrapper) iChunkWrapperList.get(i);
				if (centerChunkWrapper == null)
				{
					continue;
				}
				
				throwIfThreadInterrupted();
				
				// not always necessary, but sometimes MC heightmap is wrong
				// and can cause LODs to generate incorrectly
				centerChunkWrapper.createDhHeightMaps();
				
				// pre-generated chunks should have lighting but new ones won't
				if (!centerChunkWrapper.isDhBlockLightingCorrect())
				{
					DhLightingEngine.INSTANCE.bakeChunkBlockLighting(centerChunkWrapper, iChunkWrapperList, maxSkyLight);
				}
				
				List<BeaconBeamDTO> activeBeamList = centerChunkWrapper.getAllActiveBeacons(iChunkWrapperList);
				if (!activeBeamList.isEmpty())
				{
					this.dhServerLevel.updateBeaconBeamsForChunkPos(centerChunkWrapper.getChunkPos(), activeBeamList);
				}
			}
			
			
			for (int i = 0; i < iChunkWrapperList.size(); i++)
			{
				ChunkWrapper chunkWrapper = (ChunkWrapper) iChunkWrapperList.get(i);
				if (chunkWrapper == null)
				{
					continue;
				}
				
				// give MC a few seconds to save the chunk before
				// we can process update events there again
				this.chunkSaveIgnoreTimer.schedule(new TimerTask()
				{
					@Override
					public void run() 
					{
						if (DhChunkGenerator.this.updateManager != null)
						{
							DhChunkGenerator.this.updateManager.removePosToIgnore(chunkWrapper.getChunkPos());
						}
					}
				}, MS_TO_IGNORE_CHUNK_AFTER_COMPLETION);
			}
		}
	}
	private static <T> ArrayGridList<T> GetCutoutFrom(ArrayGridList<T> total, int border) { return new ArrayGridList<>(total, border, total.gridSize - border); }
	private static <T> ArrayGridList<T> GetCutoutFrom(ArrayGridList<T> total, EDhApiWorldGenerationStep step) { return GetCutoutFrom(total, WORLD_GEN_CHUNK_BORDER_NEEDED_BY_GEN_STEP.get(step)); }
	#endif
	
	
	// queue task //
	
	@Override
	public CompletableFuture<Void> queueGenEvent(
		int chunkPosMinX, int chunkPosMinZ, int chunkWidthCount,
		EDhApiDistantGeneratorMode generatorMode, EDhApiWorldGenerationStep targetStep,
		ExecutorService worldGeneratorThreadPool, Consumer<IChunkWrapper> resultConsumer)
	{
		ChunkGenEvent genEvent = ChunkGenEvent.start(
				new DhChunkPos(chunkPosMinX, chunkPosMinZ), chunkWidthCount, this,
				generatorMode, targetStep, resultConsumer, 
				worldGeneratorThreadPool);
		this.generationEventQueue.add(genEvent);
		return genEvent.future;
	}
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	@Override
	public void close()
	{
		LOGGER.info("Closing [" + DhChunkGenerator.class.getSimpleName() + "]");
		
		
		// cancel in-progress tasks
		Iterator<ChunkGenEvent> genEventIter = this.generationEventQueue.iterator();
		while (genEventIter.hasNext())
		{
			ChunkGenEvent event = genEventIter.next();
			event.future.cancel(true);
			genEventIter.remove();
		}
		
		
		#if MC_VER > MC_1_12_2
		this.chunkFileReader.close();
		#endif
	}
	
	//endregion
	
	
	
	//================//
	// helper methods //
	//================//
	//region
	
	/**
	 * Called before code that may run for an extended period of time. <br>
	 * This is necessary to allow canceling world gen since waiting
	 * for some world gen requests to finish can take a while.
	 */
	public static void throwIfThreadInterrupted() throws InterruptedException
	{
		if (Thread.interrupted())
		{
			throw new InterruptedException("["+ DhChunkGenerator.class.getSimpleName()+"] task interrupted.");
		}
	}
	
	//endregion
	
	
	
	//================//
	// helper classes //
	//================//
	//region
	
	#if MC_VER > MC_1_12_2
	@FunctionalInterface
	public interface IEmptyChunkRetrievalFunc
	{
		ChunkAccess getChunk(int chunkPosX, int chunkPosZ);
	}
	#endif
	
	//endregion
	
	
	
}
