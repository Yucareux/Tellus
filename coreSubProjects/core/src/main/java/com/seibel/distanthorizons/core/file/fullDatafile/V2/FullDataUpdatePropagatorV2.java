package com.seibel.distanthorizons.core.file.fullDatafile.V2;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.file.fullDatafile.GeneratedFullDataSourceProvider;
import com.seibel.distanthorizons.core.generation.queues.IFullDataSourceRetrievalQueue;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalResult;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.render.renderer.AbstractDebugWireframeRenderer;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.sql.DbConnectionClosedException;
import com.seibel.distanthorizons.core.util.ExceptionUtil;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import com.seibel.distanthorizons.core.util.WorldGenUtil;
import com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftSharedWrapper;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.awt.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

public class FullDataUpdatePropagatorV2 implements IDebugRenderable, AutoCloseable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	private static final IMinecraftSharedWrapper MC_SHARED = SingletonInjector.INSTANCE.get(IMinecraftSharedWrapper.class);
	
	/** indicates how long the update queue thread should wait between queuing ticks */
	protected static final int PROPAGATE_QUEUE_THREAD_DELAY_IN_MS = 250;
	
	public static final int NUMBER_OF_PARENT_UPDATE_TASKS_PER_THREAD = 10;
	
	/**
	 * Don't downsample extremely large LODs
	 * since they'll take a long time and take up a lot of disk space
	 * that may not be needed.
	 * If they are needed at a future time, they can be generated.
	 */
	public static final byte HIGHEST_DOWNSAMPLE_DETAIL_LEVEL = DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL + 6;
	
	/** how many parent update tasks can be in the queue at once */
	public static int getMaxPropagateTaskCount() { return NUMBER_OF_PARENT_UPDATE_TASKS_PER_THREAD * Config.Common.MultiThreading.numberOfThreads.get(); }
	
	private final ExecutorService regenQueueingThread;
	private boolean generationQueueRunning = false;
	
	
	
	/**
	 * Tracks which positions are currently being updated
	 * to prevent duplicate concurrent updates.
	 */
	private final Set<Long> updatingPosSet = ConcurrentHashMap.newKeySet();
	private final Set<Long> generatingPosSet = ConcurrentHashMap.newKeySet();
	
	/**
	 * It'd be better if we could be told when changes are available,
	 * then run the update thread, but having a constantly running background
	 * thread is simpler to deal with and gets the job done.
	 */
	public final ThreadPoolExecutor updateQueueProcessor;
	
	private final IDhLevel dhLevel;
	
	
	private final FullDataSourceProviderV2 provider;
	private final FullDataUpdaterV2 dataUpdater;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public FullDataUpdatePropagatorV2(FullDataSourceProviderV2 provider, FullDataUpdaterV2 dataUpdater, IDhLevel dhLevel)
	{
		this.provider = provider;
		this.dataUpdater = dataUpdater;
		
		this.dhLevel = dhLevel;
		
		// update propagation doesn't need to be run on the server since only the highest detail level is needed
		this.updateQueueProcessor = ThreadUtil.makeSingleThreadPool("Update Propagate Queue [" + dhLevel.getLevelWrapper().getDhIdentifier() + "]");
		this.updateQueueProcessor.execute(this::runUpdateQueue);
		
		this.regenQueueingThread = ThreadUtil.makeSingleThreadPool("Regen Queue [" + dhLevel.getLevelWrapper().getDhIdentifier() + "]");
	}
	
	//endregion
	
	
	
	//================//
	// queue handling //
	//================//
	//region
	
	private void runUpdateQueue()
	{
		while (!Thread.interrupted())
		{
			try
			{
				Thread.sleep(PROPAGATE_QUEUE_THREAD_DELAY_IN_MS);
				
				PriorityTaskPicker.Executor executor = ThreadPoolUtil.getUpdatePropagatorExecutor();
				if (executor == null 
					|| executor.isTerminated())
				{
					continue;
				}
				
				// update positions closest to the player (if not on a server)
				// to make world gen appear faster
				DhBlockPos targetBlockPos = DhBlockPos.ZERO;
				if (MC_CLIENT != null 
					&& MC_CLIENT.playerExists())
				{
					targetBlockPos = MC_CLIENT.getPlayerBlockPos();
				}
				
				this.runParentUpdates(executor, targetBlockPos);
				
				this.runChildUpdates(executor, targetBlockPos);
				
				this.tryQueueRegeneration(targetBlockPos);
			}
			catch (InterruptedException ignored)
			{
				Thread.currentThread().interrupt();
			}
			catch (Exception e)
			{
				if (!ExceptionUtil.isInterruptOrReject(e)
					&& !DbConnectionClosedException.isClosedException(e))
				{
					LOGGER.error("Unexpected error in the parent update queue thread. Error: " + e.getMessage(), e);
				}
			}
		}
	}
	
	
	
	//================//
	// parent updates //
	//================//
	//region
	
	/** apply update bottom -> up, from child to parent */
	private void runParentUpdates(PriorityTaskPicker.Executor executor, DhBlockPos targetBlockPos)
	{
		int maxUpdateTaskCount = getMaxPropagateTaskCount();
		
		// queue parent updates
		if (executor.getQueueSize() > maxUpdateTaskCount
			|| this.updatingPosSet.size() > maxUpdateTaskCount)
		{
			return;
		}
		
		
		// get the positions that need to be applied to their parents
		LongArrayList outputChildPosList = this.provider.repo.getParentPositionsToUpdate(targetBlockPos.getX(), targetBlockPos.getZ(), maxUpdateTaskCount);
		
		// combine updates together based on their parent
		HashMap<Long, HashSet<Long>> outputChildPosByInputParentPos = new HashMap<>();
		for (Long outputChildPos : outputChildPosList)
		{
			outputChildPosByInputParentPos.compute(DhSectionPos.getParentPos(outputChildPos), (parentPos, outputChildPosSet) ->
			{
				if (outputChildPosSet == null)
				{
					outputChildPosSet = new HashSet<>();
				}
				outputChildPosSet.add(outputChildPos);
				return outputChildPosSet;
			});
		}
		
		// queue the updates
		for (Long parentInputPos : outputChildPosByInputParentPos.keySet())
		{
			// stop if there are already a bunch of updates queued
			if (this.updatingPosSet.size() > maxUpdateTaskCount
				|| executor.getQueueSize() > maxUpdateTaskCount)
			{
				break;
			}
			
			// skip any already-queued positions
			if (!this.updatingPosSet.add(parentInputPos))
			{
				continue;
			}
			
			try
			{
				executor.execute(() ->
				{
					ReentrantLock parentWriteLock = this.dataUpdater.updateLockProvider.getLock(parentInputPos);
					boolean parentLocked = false;
					try
					{
						// Locking the parent before the children should prevent deadlocks.
						// TryLock is used instead of lock so this thread can handle a different update.
						if (!parentWriteLock.tryLock())
						{
							return;
						}
						
						parentLocked = true;
						this.dataUpdater.lockedPosSet.add(parentInputPos);
						
						try (FullDataSourceV2 parentInputDataSource = this.provider.get(parentInputPos))
						{
							// will return null if the file handler is shutting down
							if (parentInputDataSource == null)
							{
								return;
							}
							
							boolean parentUpdated = false;
							
							// apply each child pos to the parent
							for (Long childOutputPos : outputChildPosByInputParentPos.get(parentInputPos))
							{
								ReentrantLock childReadLock = this.dataUpdater.updateLockProvider.getLock(childOutputPos);
								try
								{
									childReadLock.lock();
									this.dataUpdater.lockedPosSet.add(childOutputPos);
									
									try (FullDataSourceV2 childOutputDataSource = this.provider.get(childOutputPos))
									{
										// can return null when the file handler is being shut down
										if (childOutputDataSource != null)
										{
											parentUpdated = parentInputDataSource.updateFromDataSource(childOutputDataSource) 
												| parentUpdated;
										}
									}
								}
								catch (Exception e)
								{
									if (!ExceptionUtil.isInterruptOrReject(e)
										&& !DbConnectionClosedException.isClosedException(e))
									{
										LOGGER.error("Unexpected in parent update propagation for parent pos: [" + DhSectionPos.toString(parentInputPos) + "], child pos: [" + DhSectionPos.toString(parentInputPos) + "], Error: [" + e.getMessage() + "].", e);
									}
								}
								finally
								{
									this.provider.repo.setApplyToParent(childOutputPos, false);
									
									childReadLock.unlock();
									this.dataUpdater.lockedPosSet.remove(childOutputPos);
								}
							}
							
							
							// if nothing was changed we don't want to propagate up any further
							// since the further up nodes won't need any changes either
							if (parentUpdated)
							{
								// propagating up, parent will need changes
								parentInputDataSource.applyToParent = 
									(DhSectionPos.getDetailLevel(parentInputDataSource.getPos()) < FullDataSourceProviderV2.ROOT_SECTION_DETAIL_LEVEL);
								
								
								// only leaf nodes will ever need regenerating
								parentInputDataSource.regenerateLeaf = false;
								
								// don't modify other update propagator flags
								{
									parentInputDataSource.applyToChildren = null;
								}
								
								this.dataUpdater.updateDataSource(parentInputDataSource);
							}
						}
					}
					finally
					{
						if (parentLocked)
						{
							parentWriteLock.unlock();
							this.dataUpdater.lockedPosSet.remove(parentInputPos);
						}
						
						this.updatingPosSet.remove(parentInputPos);
					}
				});
			}
			catch (RejectedExecutionException ignore)
			{ /* the executor was shut down, it should be back up shortly and able to accept new jobs */ }
			catch (Exception e)
			{
				this.updatingPosSet.remove(parentInputPos);
				throw e;
			}
		}
	}
	
	//endregion
	
	
	
	//===============//
	// child updates //
	//===============//
	//region
	
	/** apply update top -> down, from parent to child */
	private void runChildUpdates(PriorityTaskPicker.Executor executor, DhBlockPos targetBlockPos)
	{
		int maxUpdateTaskCount = getMaxPropagateTaskCount();
		
		// queue child updates
		if (executor.getQueueSize() < maxUpdateTaskCount
			&& this.updatingPosSet.size() < maxUpdateTaskCount)
		{
			// get the positions that need to be applied to their children
			LongArrayList outputParentPosList = this.provider.repo.getChildPositionsToUpdate(targetBlockPos.getX(), targetBlockPos.getZ(), maxUpdateTaskCount);
			
			// queue the updates
			for (long parentOutputPos : outputParentPosList)
			{
				// stop if there are already a bunch of updates queued
				if (this.updatingPosSet.size() > maxUpdateTaskCount
					|| executor.getQueueSize() > maxUpdateTaskCount)
				{
					break;
				}
				
				// skip already updating positions
				if (!this.updatingPosSet.add(parentOutputPos))
				{
					continue;
				}
				
				
				try
				{
					executor.execute(() ->
					{
						// Don't downsample extremely large LODs
						// since they'll take a long time and take up a lot of disk space
						// that may not be needed.
						// If they are needed at a future time, they can be generated.
						if (DhSectionPos.getDetailLevel(parentOutputPos) >= HIGHEST_DOWNSAMPLE_DETAIL_LEVEL)
						{
							this.provider.repo.setApplyToChild(parentOutputPos, false);
							this.updatingPosSet.remove(parentOutputPos);
							return;
						}
						
						ReentrantLock parentReadLock = this.dataUpdater.updateLockProvider.getLock(parentOutputPos);
						boolean parentLocked = false;
						try
						{
							// Locking the parent before the children should prevent deadlocks.
							// TryLock is used instead of lock so this thread can handle a different update.
							if (!parentReadLock.tryLock())
							{
								return;
							}
							
							
							parentLocked = true;
							this.dataUpdater.lockedPosSet.add(parentOutputPos);
							
							try (FullDataSourceV2 parentOutputDataSource = this.provider.get(parentOutputPos))
							{
								// will return null if the file handler is shutting down
								if (parentOutputDataSource == null)
								{
									return;
								}
								
								
								
								// apply parent to each child
								for (int i = 0; i < 4; i++)
								{
									long childInputPos = DhSectionPos.getChildByIndex(parentOutputPos, i);
									
									ReentrantLock childWriteLock = this.dataUpdater.updateLockProvider.getLock(childInputPos);
									try
									{
										childWriteLock.lock();
										this.dataUpdater.lockedPosSet.add(childInputPos);
										
										try (FullDataSourceV2 childInputDataSource = this.provider.get(childInputPos))
										{
											// will return null if the file handler is shutting down
											if (childInputDataSource == null)
											{
												continue;
											}
											
											boolean childUpdated = childInputDataSource.updateFromDataSource(parentOutputDataSource);
											// don't propagate down if the child didn't change
											// if that happens that probably means the child was already
											// higher detail
											if (childUpdated)
											{
												// propagating down, children will need changes
												if (DhSectionPos.getDetailLevel(childInputPos) > FullDataSourceProviderV2.LEAF_SECTION_DETAIL_LEVEL)
												{
													// downsample non-leaf nodes
													childInputDataSource.applyToChildren = true;
													childInputDataSource.regenerateLeaf = false;
												}
												else
												{
													// generate leaf nodes
													childInputDataSource.applyToChildren = false;
													childInputDataSource.regenerateLeaf = true;
												}
												
												// don't modify other propagator flags
												{
													childInputDataSource.applyToParent = null;
												}
												
												
												this.dataUpdater.updateDataSource(childInputDataSource);
											}
										}
									}
									catch (Exception e)
									{
										if (!ExceptionUtil.isInterruptOrReject(e)
											&& !DbConnectionClosedException.isClosedException(e))
										{
											LOGGER.error("Unexpected in child update propagation for parent pos: [" + DhSectionPos.toString(parentOutputPos) + "], child pos: [" + DhSectionPos.toString(childInputPos) + "], Error: [" + e.getMessage() + "].", e);
										}
									}
									finally
									{
										this.provider.repo.setApplyToChild(parentOutputPos, false);
										
										childWriteLock.unlock();
										this.dataUpdater.lockedPosSet.remove(childInputPos);
									}
								}
							}
						}
						finally
						{
							if (parentLocked)
							{
								parentReadLock.unlock();
								this.dataUpdater.lockedPosSet.remove(parentOutputPos);
							}
							
							this.updatingPosSet.remove(parentOutputPos);
						}
					});
				}
				catch (RejectedExecutionException ignore)
				{ /* the executor was shut down, it should be back up shortly and able to accept new jobs */ }
				catch (Exception e)
				{
					this.updatingPosSet.remove(parentOutputPos);
					throw e;
				}
			}
		}
	}
	
	//endregion
	
	
	
	//=============//
	// regen queue //
	//=============//
	//region
	
	/** does nothing if the queue thread is already running */
	private void tryQueueRegeneration(DhBlockPos targetBlockPos)
	{
		boolean canQueueRegen = false;
		if (MC_SHARED.isDedicatedServer())
		{
			// dedicated servers can always generate chunks
			canQueueRegen = true;
		}
		else if (this.dhLevel instanceof IDhClientLevel)
		{
			// singleplayer should only generate in levels that are actively being rendered
			canQueueRegen = ((IDhClientLevel)this.dhLevel).isRendering();
		}
		
		if (!canQueueRegen)
		{
			return;
		}
		
		if (!WorldGenUtil.regenAllowed(this.provider))
		{
			return;
		}
		
		
		// only let one queue thread run at a time
		if (this.generationQueueRunning)
		{
			return;
		}
		this.generationQueueRunning = true;
		
		// queue world generation tasks on its own thread since this process is very slow and would lag the server thread
		this.regenQueueingThread.execute(() ->
		{
			try
			{
				GeneratedFullDataSourceProvider genProvider = (GeneratedFullDataSourceProvider)this.provider;
				
				
				// queue generation tasks until the generator is full, or there are no more tasks to generate
				boolean taskStarted = true;
				while (genProvider.canQueueRetrievalNow()
					&& taskStarted)
				{
					taskStarted = this.queueRegeneration(genProvider, targetBlockPos);
				}
			}
			catch (Exception e)
			{
				if (!ExceptionUtil.isInterruptOrReject(e)
					&& !DbConnectionClosedException.isClosedException(e))
				{
					LOGGER.error("Regen queueing exception: " + e.getMessage(), e);
				}
			}
			finally
			{
				this.generationQueueRunning = false;
			}
		});
	}
	
	/** @return true if a task was queued */
	private boolean queueRegeneration(GeneratedFullDataSourceProvider genProvider, DhBlockPos targetBlockPos)
	{
		IFullDataSourceRetrievalQueue retrievalQueue = genProvider.worldGenQueueRef.get();
		if (retrievalQueue == null)
		{
			// no retrieval can be done right now
			// (generation may be disabled)
			return false;
		}
		
		if (!retrievalQueue.getCanRegenerate())
		{
			// low-quality LODs are being retrieved,
			// wait till those are done before we try
			// generating the high-quality LODs
			return false;
		}
		
		// check if any low-detail LOD tasks are already queued
		boolean lowDetailPosQueued = retrievalQueue.requestPosExistsWhere(
			(long queuedPos) -> (DhSectionPos.getDetailLevel(queuedPos) > DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL));
		if (lowDetailPosQueued)
		{
			return false;
		}
		
		
		
		// get the positions that need to be regenerated
		int maxRegenBlockDistance = WorldGenUtil.getMaxRegenDistanceInBlocks();
		if (MC_CLIENT == null
			|| !MC_CLIENT.playerExists())
		{
			// dedicated server should get regen tasks from everywhere
			// since we don't have a specific player we're targeting
			maxRegenBlockDistance = -1;
		}
		int maxRegenTaskCount = GeneratedFullDataSourceProvider.getMaxRetrievalQueueCount();
		
		LongArrayList updatePosList = this.provider.repo.getChildPositionsToRegen(
			targetBlockPos.getX(), targetBlockPos.getZ(), 
			maxRegenBlockDistance, maxRegenTaskCount);
		if (updatePosList.size() == 0)
		{
			// no regen needed
			return false;
		}
		
		// queue the updates
		for (int i = 0; i < updatePosList.size(); i++)
		{
			long updatePos = updatePosList.getLong(i);
			
			boolean tasksCanBeQueued = this.tryQueueWorldGenTask(genProvider, updatePos);
			if (!tasksCanBeQueued)
			{
				return false;
			}
		}
		
		// assume at least one task was queued
		return true;
	}
	/** 
	 * @return true if we should continue looking for tasks, 
	 *          false if there's a critical issue and we should stop 
	 */
	private boolean tryQueueWorldGenTask(GeneratedFullDataSourceProvider genProvider, long updatePos)
	{
		// also handles task count limiting
		if (!this.provider.canQueueRetrievalNow())
		{
			return false;
		}
		
		if (this.generatingPosSet.contains(updatePos))
		{
			// just because this position is queued doesn't mean 
			// the next one will also be,
			// keep looking.
			// (Returning true here significantly improves queuing speed)
			return true;
		}
		
		
		// just generate highest detail
		LongArrayList posToGen = genProvider.getPositionsToRetrieve(updatePos, DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL);
		if (posToGen == null)
		{
			return true;
		}
		
		if (posToGen.size() == 0)
		{
			// position is already generated
			this.provider.repo.setRegenerate(updatePos, false);
			return true;
		}
		
		
		
		if (!this.generatingPosSet.add(updatePos))
		{
			// position already queued
			return true;
		}
		
		CompletableFuture<DataSourceRetrievalResult>[] futureArray = new CompletableFuture[posToGen.size()];
		for (int i = 0; i < posToGen.size(); i++)
		{
			long genPos = posToGen.getLong(i);
			futureArray[i] = genProvider.queuePositionForRetrieval(genPos); // this can cause the same positions to be queued multiple times, we handle that sanely down stream although it's a bit strange that it happens at all
		}
		
		CompletableFuture.allOf(futureArray)
			.handle((voidObj, throwable) -> 
			{
				this.generatingPosSet.remove(updatePos);
				
				if (throwable != null 
					&& !ExceptionUtil.isShutdownException(throwable)
					&& !DbConnectionClosedException.isClosedException(throwable))
				{
					LOGGER.error("Unexpected error on Update gen future: ["+throwable.getMessage()+"].", throwable);
				}
				
				
				boolean finishedGenerating = true;
				for (int i = 0; i < futureArray.length; i++)
				{
					try
					{
						DataSourceRetrievalResult result = futureArray[i].getNow(null);
						if (result == null
							|| result.dataSource == null)
						{
							finishedGenerating = false;
							break;
						}
					}
					catch (Exception e)
					{
						// completed exceptionally
						finishedGenerating = false;
						break;
					}
				}
				
				if (finishedGenerating)
				{
					this.provider.repo.setRegenerate(updatePos, false);
				}
				
				return null;
			});
		
		return true;
	}
	
	//endregion
	//endregion
	
	
	
	//===========//
	// overrides //
	//===========//
	//region
	
	@Override
	public void debugRender(AbstractDebugWireframeRenderer renderer)
	{
		this.updatingPosSet
				.forEach((pos) -> { renderer.renderBox(new AbstractDebugWireframeRenderer.Box(pos, -32f, 80f, 0.20f, Color.MAGENTA)); });
		
		this.generatingPosSet
				.forEach((pos) -> { renderer.renderBox(new AbstractDebugWireframeRenderer.Box(pos, -32f, 80f, 0.20f, Color.CYAN)); });
	}
	
	@Override
	public void close()
	{
		if (this.updateQueueProcessor != null)
		{
			this.updateQueueProcessor.shutdownNow();
		}
		
		if (this.regenQueueingThread != null)
		{
			this.regenQueueingThread.shutdownNow();
		}
	}
	
	//endregion
	
	
	
}
