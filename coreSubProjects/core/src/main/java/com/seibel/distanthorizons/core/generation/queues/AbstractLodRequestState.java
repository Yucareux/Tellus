package com.seibel.distanthorizons.core.generation.queues;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorProgressDisplayLocation;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.level.DhServerLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.util.FormatUtil;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import com.seibel.distanthorizons.core.util.objects.RollingAverage;
import com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

/** 
 * Handles the {@link IFullDataSourceRetrievalQueue} and any other necessary world gen information. 
 * 
 * @see LodRequestModule
 * @see IFullDataSourceRetrievalQueue
 */
public abstract class AbstractLodRequestState
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	/** static so we only send the disable message once per session */
	private static long firstProgressMessageSentMs = 0;
	
	
	
	public final IDhLevel dhLevel;
	public final IFullDataSourceRetrievalQueue retrievalQueue;
	
	private final ThreadPoolExecutor progressUpdaterThread = ThreadUtil.makeSingleDaemonThreadPool("World Gen Progress Updater");
	private boolean progressUpdateThreadRunning = false;
	
	
	
	//===========================//
	// request queue and logging //
	//===========================//
	//region
	
	public AbstractLodRequestState(IDhLevel dhLevel, IFullDataSourceRetrievalQueue retrievalQueue)
	{
		this.dhLevel = dhLevel;
		this.retrievalQueue = retrievalQueue;
	}
	
	//endregion
	
	
	
	//===========================//
	// request queue and logging //
	//===========================//
	//region
	
	/** @param targetPosForRequest the position that world generation should be centered around */
	public void startRequestQueueAndSetTargetPos(DhBlockPos2D targetPosForRequest)
	{
		this.retrievalQueue.startAndSetTargetPos(targetPosForRequest);
		this.startProgressUpdateThread();
	}
	private void startProgressUpdateThread()
	{
		// only start the thread once
		if (!this.progressUpdateThreadRunning)
		{
			this.progressUpdateThreadRunning = true;
			
			this.progressUpdaterThread.execute(() ->
			{
				while (this.progressUpdateThreadRunning)
				{
					try
					{
						this.sendRetrievalProgress();
						
						// sleep so we only see an update once in a while
						int sleepTimeInSec = Config.Common.WorldGenerator.generationProgressDisplayIntervalInSeconds.get();
						Thread.sleep(sleepTimeInSec * 1_000L);
					}
					catch (Exception e)
					{
						LOGGER.error("Unexpected issue displaying chunk retrieval progress [" + e.getMessage() + "].", e);
					}
				}
			});
		}
	}
	private void sendRetrievalProgress()
	{
		// format the remaining chunks
		long remainingChunkCount = this.retrievalQueue.getRetrievalEstimatedRemainingChunkCount();
		remainingChunkCount += this.retrievalQueue.getQueuedChunkCount();
		String remainingChunkCountStr = F3Screen.NUMBER_FORMAT.format(remainingChunkCount);
		
		String message = "DH is "+this.retrievalQueue.getRetrievalTypeName()+". ";
		if (this.dhLevel.getClass() == DhServerLevel.class)
		{
			// server levels can have multiple world generators running at once,
			// this helps us track of which queue is which
			message += "For " + this.dhLevel.getLevelWrapper().getDimensionName() + " ";
		}
		message += remainingChunkCountStr + " left.";
		
		// show a message about how to disable progress logging if requested
		int msToShowDisableInstructions = Config.Common.WorldGenerator.generationProgressDisableMessageDisplayTimeInSeconds.get() * 1_000;
		if (msToShowDisableInstructions > 0)
		{
			long timeSinceFirstMessageInMs = (System.currentTimeMillis() - firstProgressMessageSentMs);
			// always show this message for the first tick
			if (firstProgressMessageSentMs == 0
				// show this message if there is still time
				|| timeSinceFirstMessageInMs < msToShowDisableInstructions)
			{
				// append to the current message
				message += " This message can be hidden in the DH config.";
			}
		}
		
		// add the remaining time estimate if available
		double chunksPerSec = this.getEstimatedChunksPerSecond();
		if (chunksPerSec > 0)
		{
			long estimatedRemainingTime = (long) (remainingChunkCount / chunksPerSec);
			message += " ETA: " + FormatUtil.formatEta(Duration.ofSeconds(estimatedRemainingTime));
			
			if (Config.Common.WorldGenerator.generationProgressIncludeChunksPerSecond.get())
			{
				message += " at " + F3Screen.NUMBER_FORMAT.format(chunksPerSec) + " chunks/sec";
			}
		}
		
		// only log if there are chunks needing to be generated
		if (remainingChunkCount != 0)
		{
			// determine where to log
			EDhApiDistantGeneratorProgressDisplayLocation displayLocation = Config.Common.WorldGenerator.showGenerationProgress.get();
			if (displayLocation == EDhApiDistantGeneratorProgressDisplayLocation.OVERLAY)
			{
				ClientApi.INSTANCE.queueOverlayMessage(message);
			}
			else if (displayLocation == EDhApiDistantGeneratorProgressDisplayLocation.CHAT)
			{
				// fast chat queue since the messages can be sent very quickly
				ClientApi.INSTANCE.queueFastChatMessage(message);
			}
			else if (displayLocation == EDhApiDistantGeneratorProgressDisplayLocation.LOG)
			{
				LOGGER.info(message);
			}
			
			
			// mark when the first message was sent
			if (firstProgressMessageSentMs == 0)
			{
				firstProgressMessageSentMs = System.currentTimeMillis();
			}
		}
	}
	
	/** @return -1 if this method isn't supported or available */
	public double getEstimatedChunksPerSecond()
	{
		RollingAverage avg = this.retrievalQueue.getRollingAverageChunkGenTimeInMs();
		if (avg == null)
		{
			return -1;
		}
		
		
		PriorityTaskPicker.Executor executor = ThreadPoolUtil.getWorldGenExecutor();
		int threadCount = 1;
		if (executor != null)
		{
			threadCount = executor.getPoolSize();
		}
		
		// convert chunk generation time in milliseconds to chunks per second
		double chunksPerSecond = (1 / avg.getAverage()) * 1_000;
		// estimate the number of chunks that can be processed per second by all threads
		// Note: this is probably higher than the actual number, we might want to drop this by 1 or 2 to give a more realistic estimate
		chunksPerSecond = threadCount * chunksPerSecond;
		
		return chunksPerSecond;
	}
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	public CompletableFuture<Void> closeAsync(boolean doInterrupt)
	{
		// this should stop the updater thread
		this.progressUpdateThreadRunning = false;
		
		return this.retrievalQueue.startClosingAsync(true, doInterrupt)
			.exceptionally(e ->
				{
					LOGGER.error("Error during first stage of generation queue shutdown, Error: [" + e.getMessage() + "].", e);
					return null;
				}
			).thenRun(this.retrievalQueue::close)
			.exceptionally(e ->
			{
				LOGGER.error("Error during second stage of generation queue shutdown, Error: [" + e.getMessage() + "].", e);
				return null;
			});
	}
	
	//endregion
	
	
	
}
