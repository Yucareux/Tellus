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

package com.seibel.distanthorizons.core.generation.queues;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiGeneratorPlan;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.objects.data.DhApiChunk;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.generation.DhLightingEngine;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalResult;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalTask;
import com.seibel.distanthorizons.core.level.IDhServerLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.transformers.LodDataBuilder;
import com.seibel.distanthorizons.core.render.renderer.AbstractDebugWireframeRenderer;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.util.ExceptionUtil;
import com.seibel.distanthorizons.core.util.LodUtil.AssertFailureException;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import com.seibel.distanthorizons.core.util.objects.DataCorruptedException;
import com.seibel.distanthorizons.core.util.objects.RollingAverage;
import com.seibel.distanthorizons.core.util.objects.UncheckedInterruptedException;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.world.DhApiWorldProxy;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.logging.DhLogger;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class WorldGenerationQueue implements IFullDataSourceRetrievalQueue, IDebugRenderable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	private static final IWrapperFactory WRAPPER_FACTORY = SingletonInjector.INSTANCE.get(IWrapperFactory.class);
	private static final AbstractDebugWireframeRenderer DEBUG_RENDERER = SingletonInjector.INSTANCE.get(AbstractDebugWireframeRenderer.class);
	
	
	private final IDhApiWorldGenerator generator;
	private final IDhServerLevel level;
	
	/** contains the positions that need to be generated */
	private final ConcurrentHashMap<Long, DataSourceRetrievalTask> waitingTaskByPos = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Long, DataSourceRetrievalTask> inProgressGenTasksByLodPos = new ConcurrentHashMap<>();
	
	
	private boolean canRegenerate = false;
	
	/** If not null this generator is in the process of shutting down */
	private volatile CompletableFuture<Void> generatorClosingFuture = null;
	
	/** 
	 * having a single thread queue for world gen can cause a bottleneck,
	 * however that's usually only for extremely fast custom generators
	 * and doesn't generally come up in normal gameplay.
	 */
	private final ExecutorService queueingThread = ThreadUtil.makeSingleThreadPool("World Gen Queue");
	private volatile boolean generationQueueRunning = false;
	private volatile DhBlockPos2D generationTargetPos = DhBlockPos2D.ZERO;
		
	/** just used for rendering to the F3 menu */
	private long estimatedRemainingChunkCount = 0;
	
	private final RollingAverage rollingAverageChunkGenTimeInMs = new RollingAverage(Runtime.getRuntime().availableProcessors() * 500);
	@Override public RollingAverage getRollingAverageChunkGenTimeInMs() { return this.rollingAverageChunkGenTimeInMs; }
	
	
	
	//=============//
	// constructor //
	//=============//
	//region constructor
	
	public WorldGenerationQueue(IDhApiWorldGenerator generator, IDhServerLevel level)
	{
		LOGGER.info("Creating world gen queue");
		this.generator = generator;
		this.level = level;
		
		DEBUG_RENDERER.register(this, Config.Client.Advanced.Debugging.DebugWireframe.showWorldGenQueue);
		LOGGER.info("Created world gen queue");
	}
	
	//endregion constructor
	
	
	
	//===============//
	// task handling //
	//===============//
	//region task handling
	
	@Override
	public CompletableFuture<DataSourceRetrievalResult> submitRetrievalTask(long pos, byte requiredDataDetail)
	{
		// the generator is shutting down, don't add new tasks
		if (this.generatorClosingFuture != null)
		{
			CompletableFuture<DataSourceRetrievalResult> future = new CompletableFuture<>();
			future.completeExceptionally(new CancellationException());
			return future;
		}
		
		// use the existing task if present
		DataSourceRetrievalTask existingGenTask = this.waitingTaskByPos.get(pos);
		if (existingGenTask != null)
		{
			// if the same future is returned
			// the caller shouldn't close the datasource multiple times,
			// otherwise issues will occur (holes and/or out-of-place LOD data)
			return existingGenTask.future;
		}
		
		
		// make sure the generator can provide the requested position
		if (requiredDataDetail < this.generator.getSmallestDataDetailLevel())
		{
			throw new UnsupportedOperationException("Current generator does not meet requiredDataDetail level");
		}
		if (requiredDataDetail > this.generator.getLargestDataDetailLevel())
		{
			requiredDataDetail = this.generator.getLargestDataDetailLevel();
		}
		
		// the request should be at least chunk-sized
		LodUtil.assertTrue(DhSectionPos.getDetailLevel(pos) > requiredDataDetail + LodUtil.CHUNK_DETAIL_LEVEL);
		
		final byte finalRequiredDataDetail = requiredDataDetail;
		DataSourceRetrievalTask queuedTask = this.waitingTaskByPos.compute(pos, (Long newPos, DataSourceRetrievalTask existingTask) -> 
		{
			if (existingTask != null)
			{
				return existingTask;
			}
			
			return new DataSourceRetrievalTask(pos, finalRequiredDataDetail);
		});
		
		return queuedTask.future;
	}
	
	@Override
	public void removeRetrievalRequestIf(DhSectionPos.IPrimitiveLongConsumer removeIf)
	{
		this.waitingTaskByPos.forEachKey(100, (genPos) -> 
		{
			if (removeIf.accept(genPos))
			{
				DataSourceRetrievalTask removedTask = this.waitingTaskByPos.remove(genPos);
				if (removedTask != null)
				{
					// cancel tasks so any waiting future steps can be triggered
					removedTask.future.cancel(true);
				}
			}
		});
	}
	
	@Override
	public boolean requestPosExistsWhere(DhSectionPos.IPrimitiveLongConsumer returnTrueIf)
	{
		AtomicBoolean taskFound = new AtomicBoolean(false);
		
		this.waitingTaskByPos.forEachKey(100, (genPos) ->
		{
			if (returnTrueIf.accept(genPos))
			{
				taskFound.set(true);
			}
		});
		
		return taskFound.get();
	}
	
	@Override
	public boolean getCanRegenerate() { return this.canRegenerate; }
	@Override
	public void setCanRegenerate(boolean canRegen)
	{ this.canRegenerate = canRegen; }
	
	//endregion task handling
	
	
	
	//===============//
	// running tasks //
	//===============//
	
	@Override
	public void startAndSetTargetPos(DhBlockPos2D targetPos)
	{
		// update the target pos
		this.generationTargetPos = targetPos;
		
		// needs to be called at least once to start the queue
		this.tryQueueNewWorldGenRequestsAsync();
	}
	private synchronized void tryQueueNewWorldGenRequestsAsync()
	{
		if (!DhApiWorldProxy.INSTANCE.worldLoaded()
			|| DhApiWorldProxy.INSTANCE.tryGetReadOnly())
		{
			return;
		}
		
		if (this.generationQueueRunning)
		{
			return;
		}
		this.generationQueueRunning = true;
		
		
		
		// queue world generation tasks on its own thread since this process is very slow and would lag the server thread
		this.queueingThread.execute(() ->
		{
			try
			{
				this.generator.preGeneratorTaskStart();
			
				// queue generation tasks until the generator is full, or there are no more tasks to generate
				boolean taskStarted = true;
				while (!this.isGeneratorBusy()
						&& taskStarted)
				{
					taskStarted = this.tryStartNextWorldGenTask(this.generationTargetPos);
				}
			}
			catch (Exception e)
			{
				LOGGER.error("queueing exception: " + e.getMessage(), e);
			}
			finally
			{
				this.generationQueueRunning = false;
			}
		});
	}
	private boolean isGeneratorBusy()
	{
		PriorityTaskPicker.Executor executor = ThreadPoolUtil.getWorldGenExecutor();
		if (executor == null)
		{
			// shouldn't happen, but just in case, don't queue more tasks
			return true;
		}
		
		// queue more tasks if any of the threads are available
		int worldGenThreadCount = Math.max(Config.Common.MultiThreading.numberOfThreads.get(), 1);
		return this.inProgressGenTasksByLodPos.size() > worldGenThreadCount;
	}
	/**
	 * @param targetPos the position to center the generation around
	 * @return false if no tasks were found to generate
	 */
	private boolean tryStartNextWorldGenTask(DhBlockPos2D targetPos)
	{
		if (this.waitingTaskByPos.isEmpty())
		{
			return false;
		}
		
		
		WorldGenerationTaskSelector.Selection closestTaskPair = WorldGenerationTaskSelector.findClosest(
			this.waitingTaskByPos.values(), this.generator, targetPos);

		if (closestTaskPair == null)
		{
			// the waitingTasks was modified while this check was running
			return false;
		}
		DataSourceRetrievalTask closestTask = closestTaskPair.task;
		
		// remove the task we found, we are going to start it and don't want to run it multiple times
		if (!this.waitingTaskByPos.remove(closestTask.pos, closestTask) || closestTask.future.isDone())
		{
			return true;
		}
		
		// do we need to modify this task to generate it?
		if (closestTaskPair.availability == IDhApiWorldGenerator.GENERATION_READY
			&& this.canGenerateDetailLevel(DhSectionPos.getDetailLevel(closestTask.pos)))
		{
			// detail level is correct for generation, start generation
			
			DataSourceRetrievalTask existingTask = this.inProgressGenTasksByLodPos.get(closestTask.pos);
			if (existingTask == null)
			{
				// no task exists for this position, start one
				this.startWorldGenTaskGroup(closestTask);
			}
			else
			{
				// shouldn't normally happen, but if
				// we somehow queued the same task twice:
				// merge the two futures so they both complete
				
				existingTask.future.thenApply((DataSourceRetrievalResult result)->
				{
					if (result.dataSource != null)
					{
						result.dataSource.recordLastSeen();
					}
					
					closestTask.future.complete(result);
					return closestTask.future; // return value ignored
				});
				existingTask.future.exceptionally((Throwable throwable)->
				{
					closestTask.future.completeExceptionally(throwable);
					return null; // return value ignored
				});
			}
		}
		else
		{
			// detail level is too high (if the detail level was too low, the generator would've ignored the request),
			// split up the task
			
			closestTask.future.complete(DataSourceRetrievalResult.CreateSplit());
		}
		
		
		// a task has been started or queued,
		// queue another task
		return true;
	}
	private boolean canGenerateDetailLevel(byte taskDetailLevel)
	{
		byte requestedDetailLevel = (byte) (taskDetailLevel - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
		return (this.generator.getSmallestDataDetailLevel() <= requestedDetailLevel 
			&& requestedDetailLevel <= this.generator.getLargestDataDetailLevel());
	}
	private void startWorldGenTaskGroup(DataSourceRetrievalTask worldGenTask)
	{
		long taskPos = worldGenTask.pos;
		// commented out for the time being since detail level can change at any time
		//LodUtil.assertTrue(
		//	worldGenTask.requestDetailLevel >= this.highestDataDetail 
		//	&& worldGenTask.requestDetailLevel <= this.lowestDataDetail,
		//	"World gen task started that isn't within the range that the generator can create.");
		
		long generationStartMsTime = System.currentTimeMillis();
		CompletableFuture<FullDataSourceV2> generationFuture;
		try
		{
			generationFuture = this.startGenerationEvent(worldGenTask);
		}
		catch (Exception error)
		{
			this.inProgressGenTasksByLodPos.remove(taskPos, worldGenTask);
			worldGenTask.future.completeExceptionally(error);
			LOGGER.error("Unable to start world gen task at " + DhSectionPos.toString(taskPos), error);
			return;
		}
		
		// calculate generation speed
		generationFuture.thenRun(() -> 
		{
			long totalGenTimeInMs = System.currentTimeMillis() - generationStartMsTime;
			long chunkCount = (long) worldGenTask.widthInChunks * worldGenTask.widthInChunks;
			double timePerChunk = (double)totalGenTimeInMs / (double)chunkCount;
			this.rollingAverageChunkGenTimeInMs.add(timePerChunk);
		});
		
		generationFuture.handle((FullDataSourceV2 fullDataSource, Throwable exception) ->
		{
			try
			{
				if (exception != null)
				{
					// don't log the shutdown exceptions
					if (!ExceptionUtil.isInterruptOrReject(exception))
					{
						LOGGER.error("Error generating data for pos: " + DhSectionPos.toString(taskPos), exception);
					}
					
					LodUtil.assertTrue(fullDataSource == null);
					worldGenTask.future.completeExceptionally(exception);
				}
				else
				{
					fullDataSource.recordLastSeen();
					
					boolean taskRemoved = this.inProgressGenTasksByLodPos.remove(taskPos, worldGenTask);
					LodUtil.assertTrue(taskRemoved, "Unable to find in progress generator task with position ["+DhSectionPos.toString(taskPos)+"]");
					
					if (!worldGenTask.future.complete(DataSourceRetrievalResult.CreateSuccess(taskPos, fullDataSource)))
					{
						fullDataSource.close();
					}
				}
			}
			catch (Exception e)
			{
				LOGGER.error("Unexpected error completing world gen task at pos: ["+DhSectionPos.toString(taskPos)+"].", e);
				worldGenTask.future.completeExceptionally(e);
			}
			finally
			{
				// Failed generation must release its slot too, or retries eventually stall the queue.
				this.inProgressGenTasksByLodPos.remove(taskPos, worldGenTask);
				this.tryQueueNewWorldGenRequestsAsync();
			}
			
			return null;
		});
	}
	private CompletableFuture<FullDataSourceV2> startGenerationEvent(DataSourceRetrievalTask task)
	{
		this.inProgressGenTasksByLodPos.put(task.pos, task);
		
		DhChunkPos chunkPosMin = new DhChunkPos(new DhBlockPos2D(DhSectionPos.getMinCornerBlockX(task.pos), DhSectionPos.getMinCornerBlockZ(task.pos)));
		
		EDhApiDistantGeneratorMode generatorMode = Config.Common.WorldGenerator.chunkGeneratorMode.get();
		EDhApiWorldGeneratorReturnType returnType = this.generator.getReturnType();
		switch (returnType) 
		{
			case VANILLA_CHUNKS: 
			{	
				return this.startVanillaChunkGenerationEvent(task, chunkPosMin, generatorMode);
			}
			case API_CHUNKS: 
			{
				return this.startApiChunkGenerationEvent(task, chunkPosMin, generatorMode);
			}
			case API_DATA_SOURCES:
			{
				return this.startApiDataSourceGenerationEvent(task, chunkPosMin, generatorMode);
			}
			default: 
			{
				Config.Common.WorldGenerator.generatorPlan.set(EDhApiGeneratorPlan.DISABLED);
				throw new AssertFailureException("Unknown return type: [" + returnType + "].");
			}
		}
	}
	private CompletableFuture<FullDataSourceV2> startVanillaChunkGenerationEvent(
		DataSourceRetrievalTask task, DhChunkPos chunkPosMin, EDhApiDistantGeneratorMode generatorMode)
	{
		final CompletableFuture<FullDataSourceV2> returnFuture = new CompletableFuture<>();
		
		ArrayList<IChunkWrapper> generatedChunks = new ArrayList<>(task.widthInChunks * task.widthInChunks);
		
		CompletableFuture<Void> chunkGenFuture = this.generator.generateChunks(
			chunkPosMin.getX(), chunkPosMin.getZ(),
			task.widthInChunks,
			task.requestDetailLevel,
			generatorMode,
			ThreadPoolUtil.getWorldGenExecutor(),
			(Object[] generatedObjectArray) ->
			{
				try
				{
					IChunkWrapper chunkWrapper = WRAPPER_FACTORY.createChunkWrapper(generatedObjectArray);
					generatedChunks.add(chunkWrapper);
				}
				catch (ClassCastException e)
				{
					LOGGER.error("World generator return type incorrect. Error: [" + e.getMessage() + "]. World generator disabled.", e);
					Config.Common.WorldGenerator.generatorPlan.set(EDhApiGeneratorPlan.DISABLED);
				}
				catch (Exception e)
				{
					LOGGER.error("Unexpected world generator error. Error: [" + e.getMessage() + "]. World generator disabled.", e);
					Config.Common.WorldGenerator.generatorPlan.set(EDhApiGeneratorPlan.DISABLED);
				}
			}
		);
		
		chunkGenFuture.exceptionally((throwable) ->
		{
			returnFuture.completeExceptionally(throwable);
			return null;
		});
		chunkGenFuture.thenRun(() ->
		{
			FullDataSourceV2 requestedDataSource = FullDataSourceV2.createEmpty(task.pos);
			
			// process chunks //
			for (int i = 0; i < generatedChunks.size(); i++)
			{
				IChunkWrapper chunkWrapper = generatedChunks.get(i);
				
				// only light the chunk here if necessary,
				// lighting before this point is preferred but for legacy API use this
				// check should be done
				if (!chunkWrapper.isDhBlockLightingCorrect())
				{
					ArrayList<IChunkWrapper> nearbyChunkList = new ArrayList<>();
					nearbyChunkList.add(chunkWrapper);
					byte maxSkyLight = this.level.getLevelWrapper().hasSkyLight() ? LodUtil.MAX_MC_LIGHT : LodUtil.MIN_MC_LIGHT;
					DhLightingEngine.INSTANCE.bakeChunkBlockLighting(chunkWrapper, nearbyChunkList, maxSkyLight);
				}
				
				try (FullDataSourceV2 generatedDataSource = LodDataBuilder.createFromChunk(this.level.getLevelWrapper(), chunkWrapper))
				{
					LodUtil.assertTrue(generatedDataSource != null);
					requestedDataSource.updateFromDataSource(generatedDataSource);
				}
			}
			
			returnFuture.complete(requestedDataSource);
		});
		
		return returnFuture;
	}
	private CompletableFuture<FullDataSourceV2> startApiChunkGenerationEvent(
		DataSourceRetrievalTask task,  DhChunkPos chunkPosMin, EDhApiDistantGeneratorMode generatorMode)
	{
		final CompletableFuture<FullDataSourceV2> returnFuture = new CompletableFuture<>();
		
		ArrayList<DhApiChunk> generatedChunks = new ArrayList<>(task.widthInChunks * task.widthInChunks);
		
		CompletableFuture<Void> chunkGenFuture = this.generator.generateApiChunks(
			chunkPosMin.getX(), chunkPosMin.getZ(),
			task.widthInChunks,
			task.requestDetailLevel,
			generatorMode,
			ThreadPoolUtil.getWorldGenExecutor(),
			(DhApiChunk apiChunk) -> { generatedChunks.add(apiChunk); }
		);
		
		
		chunkGenFuture.exceptionally((throwable) ->
		{
			returnFuture.completeExceptionally(throwable);
			return null;
		});
		chunkGenFuture.thenRun(() ->
		{
			FullDataSourceV2 requestedDataSource = FullDataSourceV2.createEmpty(task.pos);
			
			for (int i = 0; i < generatedChunks.size(); i++)
			{
				DhApiChunk apiChunk = generatedChunks.get(i);
				
				try(FullDataSourceV2 generatedDataSource = LodDataBuilder.createFromApiChunkData(apiChunk, this.generator.runApiValidation()))
				{
					requestedDataSource.updateFromDataSource(generatedDataSource);
				}
				catch (DataCorruptedException | IllegalArgumentException e)
				{
					LOGGER.error("World generator returned a corrupt API chunk. Error: [" + e.getMessage() + "]. World generator disabled.", e);
					Config.Common.WorldGenerator.generatorPlan.set(EDhApiGeneratorPlan.DISABLED);
				}
			}
			
			returnFuture.complete(requestedDataSource);
		});
		
		return returnFuture;
	}
	private CompletableFuture<FullDataSourceV2> startApiDataSourceGenerationEvent(
		DataSourceRetrievalTask task, DhChunkPos chunkPosMin, EDhApiDistantGeneratorMode generatorMode)
	{
		final CompletableFuture<FullDataSourceV2> returnFuture = new CompletableFuture<>();
		
		
		// done to reduce GC overhead
		FullDataSourceV2 pooledDataSource = FullDataSourceV2.createEmpty(task.pos);
		// set here so the API user doesn't have to pass in this value anywhere themselves
		pooledDataSource.setRunApiSetterValidation(this.generator.runApiValidation());
		
		// only apply to children if we aren't at the bottom of the tree
		pooledDataSource.applyToChildren = DhSectionPos.getDetailLevel(pooledDataSource.getPos()) > DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL;
		// apply to parents up to the top of the tree
		pooledDataSource.applyToParent = DhSectionPos.getDetailLevel(pooledDataSource.getPos()) < FullDataSourceProviderV2.ROOT_SECTION_DETAIL_LEVEL;
		
		CompletableFuture<Void> lodGenFuture;
		try
		{
			lodGenFuture = this.generator.generateLod(
				chunkPosMin.getX(), chunkPosMin.getZ(),
				DhSectionPos.getX(task.pos), DhSectionPos.getZ(task.pos),
				(byte) (DhSectionPos.getDetailLevel(task.pos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL),
				pooledDataSource,
				generatorMode,
				ThreadPoolUtil.getWorldGenExecutor(),
				(IDhApiFullDataSource apiDataSource) -> { }
			);
		}
		catch (Exception error)
		{
			pooledDataSource.recordLastSeen();
			pooledDataSource.close();
			returnFuture.completeExceptionally(error);
			return returnFuture;
		}
		
		
		lodGenFuture.exceptionally((throwable) ->
		{
			returnFuture.completeExceptionally(throwable);
			pooledDataSource.recordLastSeen();
			pooledDataSource.close();
			return null;
		});
		lodGenFuture.thenRun(() ->
		{
			pooledDataSource.recordLastSeen();
			returnFuture.complete(pooledDataSource);
		});
		
		return returnFuture;
	}
	
	
	
	//===================//
	// getters / setters //
	//===================//
	//region getters/setters
	
	@Override public int getWaitingTaskCount() { return this.waitingTaskByPos.size(); }
	@Override public int getInProgressTaskCount() { return this.inProgressGenTasksByLodPos.size(); }
	
	@Override public byte lowestDataDetail() { return this.generator.getLargestDataDetailLevel(); }
	@Override public byte highestDataDetail() { return this.generator.getSmallestDataDetailLevel(); }
	
	@Override public String getRetrievalTypeName() { return "generating chunks"; }
	
	@Override public long getRetrievalEstimatedRemainingChunkCount() { return this.estimatedRemainingChunkCount; }
	@Override public void setRetrievalEstimatedRemainingChunkCount(long newEstimate) { this.estimatedRemainingChunkCount = newEstimate; }
	
	@Override 
	public void addDebugMenuStringsToList(List<String> messageList) { }
	
	@Override
	public int getQueuedChunkCount()
	{
		int chunkCount = 0;
		for (long pos : this.waitingTaskByPos.keySet())
		{
			int chunkWidth = DhSectionPos.getBlockWidth(pos) / LodUtil.CHUNK_WIDTH;
			chunkCount += (chunkWidth * chunkWidth);
		}
		
		return chunkCount;
	}
	
	//endregion getters/setters
	
	
	
	//=======//
	// debug //
	//=======//
	//region debug
	
	@Override
	public void debugRender(AbstractDebugWireframeRenderer renderer)
	{
		int levelMinY = this.level.getLevelWrapper().getMinHeight();
		int levelMaxY = this.level.getLevelWrapper().getMaxHeight();
		
		// show the wireframe a bit lower than world max height,
		// since most worlds don't render all the way up to the max height
		int levelHeightRange = (levelMaxY - levelMinY);
		int maxY = levelMaxY - (levelHeightRange / 2);
		
		
		// blue - queued
		this.waitingTaskByPos.keySet().forEach((Long pos) -> 
		{ 
			renderer.renderBox(
				new AbstractDebugWireframeRenderer.Box(pos, levelMinY, maxY, 0.05f, Color.blue)
			); 
		});
		
		// red - in progress
		this.inProgressGenTasksByLodPos.forEach((Long pos, DataSourceRetrievalTask task) -> 
		{ 
			renderer.renderBox(
				new AbstractDebugWireframeRenderer.Box(pos, levelMinY, maxY, 0.05f, Color.red)
			); 
		});
	}
	
	//endregion debug
	
	
	
	//==========//
	// shutdown //
	//==========//
	//region shutdown
	
	@Override
	public CompletableFuture<Void> startClosingAsync(boolean cancelCurrentGeneration, boolean alsoInterruptRunning)
	{
		LOGGER.info("Closing world gen queue");
		this.queueingThread.shutdownNow();
		
		
		// stop and remove any in progress tasks
		ArrayList<CompletableFuture<Void>> inProgressTasksCancelingFutures = new ArrayList<>(this.inProgressGenTasksByLodPos.size());
		this.inProgressGenTasksByLodPos.values().forEach((DataSourceRetrievalTask genTask) ->
		{
			CompletableFuture<DataSourceRetrievalResult> genFuture = genTask.future;
			
			if (cancelCurrentGeneration)
			{
				genFuture.cancel(alsoInterruptRunning);
			}
			
			inProgressTasksCancelingFutures.add(genFuture.handle((DataSourceRetrievalResult result, Throwable throwable) ->
			{
				if (throwable instanceof CompletionException)
				{
					throwable = throwable.getCause();
				}
				
				if (!UncheckedInterruptedException.isInterrupt(throwable)
					&& !(throwable instanceof CancellationException))
				{
					LOGGER.error("Error when terminating data generation for pos: ["+DhSectionPos.toString(genTask.pos)+"], error: ["+throwable.getMessage()+"].", throwable);
				}
				
				if (result != null 
					&& result.dataSource != null)
				{
					result.dataSource.close();
				}
				
				return null;
			}));
		});
		this.generatorClosingFuture = CompletableFuture.allOf(inProgressTasksCancelingFutures.toArray(new CompletableFuture[0]));
		
		return this.generatorClosingFuture;
	}
	
	@Override
	public void close()
	{
		LOGGER.info("Closing " + WorldGenerationQueue.class.getSimpleName() + "...");
		
		if (this.generatorClosingFuture == null)
		{
			this.startClosingAsync(true, true);
		}
		LodUtil.assertTrue(this.generatorClosingFuture != null);
		
		
		LOGGER.info("Shutting down world generator thread pool...");
		
		PriorityTaskPicker.Executor executor = ThreadPoolUtil.getWorldGenExecutor();
		if (executor != null)
		{
			int queueSize = executor.getQueueSize();
			executor.clearQueue();
			LOGGER.info("World generator thread pool shutdown with [" + queueSize + "] incomplete tasks.");
		}
		
		this.inProgressGenTasksByLodPos.values().forEach((inProgressWorldGenTaskGroup) -> inProgressWorldGenTaskGroup.future.cancel(true));
		this.waitingTaskByPos.values().forEach((worldGenTask) -> worldGenTask.future.cancel(true));
		
		
		this.generator.close();
		DEBUG_RENDERER.unregister(this, Config.Client.Advanced.Debugging.DebugWireframe.showWorldGenQueue);
		
		
		try
		{
			this.generatorClosingFuture.cancel(true);
		}
		catch (Throwable e)
		{
			LOGGER.warn("Failed to close generation queue: ", e);
		}
		
		
		LOGGER.info("Finished closing " + WorldGenerationQueue.class.getSimpleName());
	}
	
	//endregion shutdown
	
	
	
}
