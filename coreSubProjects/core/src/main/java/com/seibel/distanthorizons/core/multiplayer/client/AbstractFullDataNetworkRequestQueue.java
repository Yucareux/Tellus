package com.seibel.distanthorizons.core.multiplayer.client;

import com.google.common.base.Stopwatch;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalResult;
import com.seibel.distanthorizons.core.level.DhClientLevel;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.network.exceptions.RateLimitedException;
import com.seibel.distanthorizons.core.network.exceptions.RequestOutOfRangeException;
import com.seibel.distanthorizons.core.network.exceptions.RequestRejectedException;
import com.seibel.distanthorizons.core.network.exceptions.SectionRequiresSplittingException;
import com.seibel.distanthorizons.core.network.session.SessionClosedException;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceResponseMessage;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.renderer.AbstractDebugWireframeRenderer;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.ratelimiting.SupplierBasedRateLimiter;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.world.DhApiWorldProxy;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class AbstractFullDataNetworkRequestQueue implements IDebugRenderable, AutoCloseable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder()
			.fileLevelConfig(Config.Common.Logging.logNetworkEventToFile)
			.maxCountPerSecond(3)
			.build();
	
	private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	private static final AbstractDebugWireframeRenderer DEBUG_RENDERER = SingletonInjector.INSTANCE.get(AbstractDebugWireframeRenderer.class);
	
	private static final int MAX_RETRY_ATTEMPTS = 3;
	
	protected static final long SHUTDOWN_TIMEOUT_SECONDS = 5;
	
	
	
	public final ClientNetworkState networkState;
	protected final DhClientLevel level;
	private final boolean changedOnly;
	
	private volatile CompletableFuture<Void> closingFuture = null;
	
	protected final ConcurrentHashMap<Long, NetRequestTask> waitingTasksBySectionPos = new ConcurrentHashMap<>();
	/**
	 * This semaphore prevents a given thread from accidentally locking on the same group
	 * multiple times, as the semaphore is tied to the given thread. <br>
	 * Reentrant Lock isn't used since it would allow the thread to lock on the same group. <br>
	 * the Short.MAX_VALUE is just a very large number that should be larger than the number of
	 * threads we'll have.
	 */
	private final Semaphore pendingTasksSemaphore = new Semaphore(Short.MAX_VALUE, true);
	
	private final AtomicInteger finishedRequests = new AtomicInteger();
	private final AtomicInteger failedRequests = new AtomicInteger();
	private final ConfigEntry<Boolean> showDebugWireframeConfig;
	
	private final SupplierBasedRateLimiter<Void> rateLimiter = new SupplierBasedRateLimiter<>(this::getRequestRateLimit);
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public AbstractFullDataNetworkRequestQueue(
			ClientNetworkState networkState, DhClientLevel level,
			boolean changedOnly, ConfigEntry<Boolean> showDebugWireframeConfig)
	{
		this.networkState = networkState;
		this.level = level;
		this.changedOnly = changedOnly;
		this.showDebugWireframeConfig = showDebugWireframeConfig;
		DEBUG_RENDERER.register(this, this.showDebugWireframeConfig);
	}
	
	
	
	//==================//
	// abstract methods //
	//==================//
	
	protected abstract int getRequestRateLimit();
	protected abstract boolean sectionInAllowedGenerationRadius(long sectionPos, DhBlockPos2D targetPos);
	
	protected abstract String getQueueName();
	
	
	
	//====================//
	// request submitting //
	//====================//
	
	public CompletableFuture<DataSourceRetrievalResult> submitRequest(long sectionPos, @Nullable Long clientTimestamp)
	{
		NetRequestTask requestEntry = this.waitingTasksBySectionPos.compute(sectionPos, (Long pos, NetRequestTask existingNetTask) ->
		{
			// ignore already queued tasks
			if (existingNetTask != null)
			{
				return existingNetTask;
			}
			
			
			NetRequestTask newRequestEntry = new NetRequestTask(pos, clientTimestamp);
			newRequestEntry.future.whenComplete((DataSourceRetrievalResult requestResult, Throwable throwable) ->
			{
				this.waitingTasksBySectionPos.remove(pos);
				
				if (throwable != null)
				{
					if (!(throwable instanceof CancellationException))
					{
						this.failedRequests.incrementAndGet();
					}
					return;
				}
				
				switch (requestResult.state)
				{
					case SUCCESS:
						this.finishedRequests.incrementAndGet();
						break;
					case REQUIRES_SPLITTING:
						break;
				}
			});
			
			return newRequestEntry;
		});
		
		return requestEntry.future;
	}
	
	public synchronized boolean tick(DhBlockPos2D targetPos)
	{
		if (DhApiWorldProxy.INSTANCE.tryGetReadOnly())
		{
			return false;
		}
		
		if (this.closingFuture != null 
			|| !this.networkState.isReady())
		{
			return false;
		}
		
		// queue requests until the queue is full
		while (this.getInProgressTaskCount() < this.getWaitingTaskCount()
				&& this.getInProgressTaskCount() < this.getRequestRateLimit()
				&& this.pendingTasksSemaphore.tryAcquire())
		{
			if (!this.rateLimiter.tryAcquire())
			{
				this.pendingTasksSemaphore.release();
				break;
			}
			
			this.sendNextRequest(targetPos);
		}
		
		return true;
	}
	private void sendNextRequest(DhBlockPos2D targetPos)
	{
		Map.Entry<Long, NetRequestTask> nearestMapEntry = this.waitingTasksBySectionPos
			.entrySet().stream()
			.filter(task -> task.getValue().networkDataSourceFuture == null)
			.min(Comparator.comparingInt(mapEntry -> DhSectionPos.getChebyshevSignedBlockDistance(mapEntry.getKey(), targetPos)))
			.orElse(null);
		
		if (nearestMapEntry == null)
		{
			this.pendingTasksSemaphore.release();
			return;
		}
		
		long requestPos = nearestMapEntry.getKey();
		NetRequestTask requestTask = nearestMapEntry.getValue();
		
		if (!this.sectionInAllowedGenerationRadius(requestPos, targetPos))
		{
			requestTask.future.cancel(false);
			this.pendingTasksSemaphore.release();
			return;
		}
		
		Long offsetEntryTimestamp = requestTask.updateTimestamp != null
				? requestTask.updateTimestamp + this.networkState.getServerTimeOffset()
				: null;
		
		CompletableFuture<FullDataSourceResponseMessage> dataSourceNetworkFuture = this.networkState.getSession().sendRequest(
				new FullDataSourceRequestMessage(this.level.getLevelWrapper(), requestPos, offsetEntryTimestamp),
				FullDataSourceResponseMessage.class
		);
		requestTask.networkDataSourceFuture = dataSourceNetworkFuture;
		
		Executor networkCompressionExecutor = ThreadPoolUtil.getNetworkCompressionExecutor();
		if (networkCompressionExecutor == null)
		{
			return;
		}
		
		dataSourceNetworkFuture.handleAsync((FullDataSourceResponseMessage response, Throwable throwable) ->
		{
			this.handleNetResponse(requestTask, response, throwable);
			return null;
		}, networkCompressionExecutor);
	}
	private void handleNetResponse(NetRequestTask requestTask, FullDataSourceResponseMessage response, Throwable throwable)
	{
		this.pendingTasksSemaphore.release();
		
		try
		{
			if (throwable != null)
			{
				throw throwable;
			}
			
			if (response.payload == null)
			{
				LodUtil.assertTrue(this.changedOnly, "Received empty data source response for not changes-only request");
				requestTask.future.cancel(false);
				return;
			}
			
			
			try(FullDataSourceV2DTO dataSourceDto = this.networkState.fullDataPayloadReceiver.decodeDataSource(response.payload))
			{
				// set application flags based on the received detail level,
				// this is needed so the data sources propagate correctly
				dataSourceDto.applyToChildren = DhSectionPos.getDetailLevel(dataSourceDto.pos) > DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL;
				dataSourceDto.applyToParent = DhSectionPos.getDetailLevel(dataSourceDto.pos) < DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL + 12;
				
				
				this.level.updateBeaconBeamsForSectionPos(dataSourceDto.pos, response.payload.beaconBeams);
				
				FullDataSourceV2 fullDataSource = dataSourceDto.createDataSource(this.level.getLevelWrapper(), null);
				requestTask.future.complete(DataSourceRetrievalResult.CreateSuccess(dataSourceDto.pos, fullDataSource));
			}
		}
		catch (SectionRequiresSplittingException ignored)
		{
			requestTask.future.complete(DataSourceRetrievalResult.CreateSplit());
		}
		catch (SessionClosedException | CancellationException ignored)
		{
			requestTask.future.cancel(false);
		}
		catch (RequestRejectedException e)
		{
			LOGGER.info("Request rejected by the server, message: [" + e.getMessage() + "].");
			requestTask.future.completeExceptionally(e);
		}
		catch (RateLimitedException e)
		{
			LOGGER.info("Rate limited by server, re-queueing task [" + DhSectionPos.toString(requestTask.pos) + "], message: [" + e.getMessage() + "].");
			
			// Skip all requests for 1 second
			this.rateLimiter.acquireAll();
			
			requestTask.networkDataSourceFuture = null;
		}
		catch (RequestOutOfRangeException e)
		{
			LOGGER.debug("Out of range, re-queueing task [" + DhSectionPos.toString(requestTask.pos) + "], message: [" + e.getMessage() + "].");
			
			requestTask.networkDataSourceFuture = null;
		}
		catch (Throwable e)
		{
			requestTask.retryAttempts--;
			LOGGER.error("Unexpected error: ["+e.getMessage()+"] while fetching full data source, attempts left: ["+requestTask.retryAttempts+"] / ["+MAX_RETRY_ATTEMPTS+"]", e);
			
			// Retry logic
			if (requestTask.retryAttempts > 0)
			{
				requestTask.networkDataSourceFuture = null;
			}
			else
			{
				requestTask.future.completeExceptionally(e);
			}
		}
	}
	
	
	
	//=========================================//
	// IFullDataSourceRetrievalQueue overrides //
	//=========================================//
	
	public void removeRetrievalRequestIf(DhSectionPos.IPrimitiveLongConsumer removeIf)
	{
		// remove tasks furthest
		Iterator<Map.Entry<Long, NetRequestTask>> farestTaskIterator = this.waitingTasksBySectionPos
			.entrySet().stream()
			.sorted(Comparator.comparingInt((Map.Entry<Long, NetRequestTask> entry) ->
			{
				Long pos = entry.getKey();
				DhBlockPos2D targetPos = this.level.getTargetPosForGeneration();
				return DhSectionPos.getChebyshevSignedBlockDistance(pos, targetPos);
			}).reversed())
			.iterator();
		
		while (farestTaskIterator.hasNext())
		{
			Map.Entry<Long, NetRequestTask> mapEntry = farestTaskIterator.next();
			long pos = mapEntry.getKey();
			NetRequestTask entry = mapEntry.getValue();
			
			if (removeIf.accept(pos))
			{
				if (entry.networkDataSourceFuture != null)
				{
					entry.networkDataSourceFuture.cancel(false);
				}
				entry.future.cancel(false);
			}
		}
	}
	
	public boolean requestPosExistsWhere(DhSectionPos.IPrimitiveLongConsumer returnTrueIf)
	{
		AtomicBoolean taskFound = new AtomicBoolean(false);
		
		this.waitingTasksBySectionPos.forEachKey(100, (genPos) ->
		{
			if (returnTrueIf.accept(genPos))
			{
				taskFound.set(true);
			}
		});
		
		return taskFound.get();
	}
	
	public void addDebugMenuStringsToList(List<String> messageList)
	{
		messageList.add(this.getQueueName() + " [" + this.level.getClientLevelWrapper().getDhIdentifier() + "]");
		messageList.add("Requests: " + this.finishedRequests + " / " + (this.getWaitingTaskCount() + this.finishedRequests.get()) + " (failed: " + this.failedRequests + ", rate limit: " + this.getRequestRateLimit() + ")");
	}
	
	public int getWaitingTaskCount() { return this.waitingTasksBySectionPos.size(); }
	public int getInProgressTaskCount() { return Short.MAX_VALUE - this.pendingTasksSemaphore.availablePermits(); }
	
	
	
	//==========//
	// shutdown //
	//==========//
	
	
	public CompletableFuture<Void> startClosingAsync(boolean alsoInterruptRunning)
	{
		return this.closingFuture = CompletableFuture.runAsync(() -> {
			Stopwatch stopwatch = Stopwatch.createStarted();
			
			do
			{
				for (NetRequestTask entry : this.waitingTasksBySectionPos.values())
				{
					entry.future.cancel(alsoInterruptRunning);
					if (entry.networkDataSourceFuture != null && entry.networkDataSourceFuture.cancel(alsoInterruptRunning))
					{
						this.pendingTasksSemaphore.release();
					}
				}
			}
			while (!this.pendingTasksSemaphore.tryAcquire(Short.MAX_VALUE) && stopwatch.elapsed(TimeUnit.SECONDS) < SHUTDOWN_TIMEOUT_SECONDS);
			
			if (stopwatch.elapsed(TimeUnit.SECONDS) >= SHUTDOWN_TIMEOUT_SECONDS)
			{
				LOGGER.warn("The request queue [" + this.getQueueName() + "] for level [" + this.level.getLevelWrapper() + "] did not shutdown in [" + SHUTDOWN_TIMEOUT_SECONDS + "] seconds. Some unfinished tasks might be left hanging.");
			}
		});
	}
	
	@Override
	public void close() { DEBUG_RENDERER.unregister(this, this.showDebugWireframeConfig); }
	
	
	
	//===========//
	// debugging //
	//===========//
	
	@Override
	public void debugRender(AbstractDebugWireframeRenderer renderer)
	{
		if (MC_CLIENT.getWrappedClientLevel() != this.level.getClientLevelWrapper())
		{
			return;
		}
		
		DhBlockPos2D targetPos = this.level.getTargetPosForGeneration();
		for (Map.Entry<Long, NetRequestTask> mapEntry : this.waitingTasksBySectionPos.entrySet())
		{
			long pos = mapEntry.getKey();
			NetRequestTask task = mapEntry.getValue();
			
			Color color;
			if (task.networkDataSourceFuture != null)
			{
				color = Color.RED;
			}
			else
			{
				boolean taskInAllowedGenRadius = this.sectionInAllowedGenerationRadius(pos, targetPos);
				if (taskInAllowedGenRadius)
				{
					color = Color.GRAY;
				}
				else
				{
					color = Color.DARK_GRAY;
				}
			}
			
			renderer.renderBox(new AbstractDebugWireframeRenderer.Box(pos, -32f, 64f, 0.05f, color));
		}
	}
	
	
	
	//================//
	// helper classes //
	//================//
	
	protected static class NetRequestTask
	{
		public final long pos;
		
		/** encapsulates the entire request, including client side queuing and the actual server request */
		public final CompletableFuture<DataSourceRetrievalResult> future = new CompletableFuture<>();
		/** will be null if we want to retrieve the LOD regardless of when it was last updated */
		@Nullable
		public final Long updateTimestamp;
		
		
		/** Will be null until the request has been sent to the server */
		@CheckForNull
		public CompletableFuture<FullDataSourceResponseMessage> networkDataSourceFuture;
		
		/** when this reaches zero then the request will be canceled. */
		public int retryAttempts = MAX_RETRY_ATTEMPTS;
		
		
		
		//=============//
		// constructor //
		//=============//
		
		public NetRequestTask(long pos, @Nullable Long updateTimestamp)
		{
			this.pos = pos;
			this.updateTimestamp = updateTimestamp;
		}
		
	}
	
	
	
}