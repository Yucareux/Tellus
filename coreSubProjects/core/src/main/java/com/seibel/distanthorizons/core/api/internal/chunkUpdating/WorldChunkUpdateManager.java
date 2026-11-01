package com.seibel.distanthorizons.core.api.internal.chunkUpdating;

import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.world.AbstractDhWorld;
import com.seibel.distanthorizons.core.world.EWorldEnvironment;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds all the {@link ChunkUpdateQueueManager} for a loaded world.
 * Different queues are needed for each level to prevent
 * chunks from bleeding between levels (IE a nether chunk applied to the overworld).
 * 
 * @see ChunkUpdateQueueManager
 */
public class WorldChunkUpdateManager
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	/** singleton since we only expect to have one world loaded at a time */
	public static final WorldChunkUpdateManager INSTANCE = new WorldChunkUpdateManager();
	
	
	/** 
	 * Queues are only removed during world shutdown.
	 * The assumption is that there will be a limited number of {@link ILevelWrapper}'s
	 * for a given world.
	 */
	private final ConcurrentHashMap<ILevelWrapper, ChunkUpdateQueueManager> updateQueueByLevelWrapper = new ConcurrentHashMap<>();
	
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private WorldChunkUpdateManager() {  }
	
	//endregion
	
	
	
	//=================//
	// manager methods //
	//=================//
	//region
	
	/**
	 * @return null if the world is unloaded or the given level wrapper is the wrong type 
	 */
	@Nullable
	public ChunkUpdateQueueManager getByLevelWrapper(ILevelWrapper levelWrapper)
	{
		AbstractDhWorld world = SharedApi.getAbstractDhWorld();
		if (world == null)
		{
			// world isn't loaded, no warnings need to be logged
			return null;
		}
		
		// we only want to load chunks for certain level wrappers
		// this is done specifically on a local-server to prevent
		// loading both the server and client level wrappers
		if (world.environment == EWorldEnvironment.CLIENT_ONLY
			// when connected to a server we should only ever load client wrappers anyway
			// but this check confirms it
			&& !(levelWrapper instanceof IClientLevelWrapper))
		{
			// how did we get a server level wrapper on the client?
			// this shouldn't happen, but just in case
			return null;
		}
		else if (
			(world.environment == EWorldEnvironment.SERVER_ONLY
			|| world.environment == EWorldEnvironment.CLIENT_SERVER)
				// when hosting a server we only care about the server wrappers
				&& !(levelWrapper instanceof IServerLevelWrapper))
		{
			// ignore client updates on the server
			return null;
		}
		
		
		ChunkUpdateQueueManager queueManager = this.updateQueueByLevelWrapper.get(levelWrapper);
		if (queueManager != null)
		{
			return queueManager;
		}
		
		return this.updateQueueByLevelWrapper.compute(levelWrapper,
			(ILevelWrapper newLevelWrapper, ChunkUpdateQueueManager oldQueueManager) ->
			{
				if (oldQueueManager != null)
				{
					return oldQueueManager;
				}
				
				oldQueueManager = new ChunkUpdateQueueManager(newLevelWrapper.getDhIdentifier());
				return oldQueueManager;
			});
	}
	
	public void processEachQueue()
	{
		this.updateQueueByLevelWrapper.forEach(
			(ILevelWrapper levelWrapper, ChunkUpdateQueueManager updateManager) -> 
		{
			updateManager.processQueue();
		});
	}
	
	public int getTotalQueuedCount()
	{
		AtomicInteger queueCountRef = new AtomicInteger(0);
		
		this.updateQueueByLevelWrapper.forEach(
			(ILevelWrapper levelWrapper, ChunkUpdateQueueManager updateManager) ->
			{
				queueCountRef.addAndGet(updateManager.getQueuedCount());
			});
		
		return queueCountRef.get();
	}
	
	public void clear() 
	{
		this.updateQueueByLevelWrapper.forEach((levelWrapper, updateManager) -> updateManager.close());
		this.updateQueueByLevelWrapper.clear();
	}
	
	//endregion
	
	
	
	//=========//
	// F3 Menu //
	//=========//
	//region
	
	public ArrayList<String> getDebugMenuString()
	{
		ArrayList<String> stringList = new ArrayList<>();
		stringList.add("");// placeholder for the total count
		
		// add each queue to the list
		AtomicInteger totalQueueCountRef = new AtomicInteger(0);
		AtomicInteger activeQueueCountRef = new AtomicInteger(0);
		this.updateQueueByLevelWrapper.forEach(
			(ILevelWrapper levelWrapper, ChunkUpdateQueueManager updateManager) ->
			{
				// is this queue active?
				if (!updateManager.updateQueuesEmpty())
				{
					updateManager.lastMsTimeShownActiveInF3Screen = System.currentTimeMillis();
					activeQueueCountRef.incrementAndGet();
				}
				
				// show this queue if it hasn't been empty long enough
				// (done to prevent flickering on the F3 screen when the queue rapidly fills/empties)
				long timeSinceQueueLastShownActiveMs = System.currentTimeMillis() - updateManager.lastMsTimeShownActiveInF3Screen;
				if (timeSinceQueueLastShownActiveMs < 4_000)
				{
					stringList.add(levelWrapper.getDimensionName() + ": " + updateManager.getDebugMenuString());
				}
				
				totalQueueCountRef.incrementAndGet();
			});
		
		// replace the first line with the number of total/active queues
		// (helpful if we need to diagnose a leak due to a massive number of queue level wrappers)
		stringList.set(0, "Chunk Update Queues: "+activeQueueCountRef.get()+"/"+totalQueueCountRef.get());
		
		return stringList;
	}
	
	//endregion
	
	
	
}
