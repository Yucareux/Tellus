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

package com.seibel.distanthorizons.core.api.internal;

import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiWorldLoadEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiWorldUnloadEvent;
import com.seibel.distanthorizons.core.api.internal.chunkUpdating.ChunkUpdateData;
import com.seibel.distanthorizons.core.api.internal.chunkUpdating.ChunkUpdateQueueManager;
import com.seibel.distanthorizons.core.api.internal.chunkUpdating.WorldChunkUpdateManager;
import com.seibel.distanthorizons.core.config.eventHandlers.IgnoredDimensionCsvHandler;
import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockTextureRegistry;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.level.DhClientLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.render.RenderThreadTaskHandler;
import com.seibel.distanthorizons.core.render.renderer.AbstractDebugWireframeRenderer;
import com.seibel.distanthorizons.core.sql.repo.AbstractDhRepo;
import com.seibel.distanthorizons.core.util.objects.Pair;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.world.*;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IChunkyAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/** Contains code and variables used by both {@link ClientApi} and {@link ServerApi} */
public class SharedApi
{
	public static final SharedApi INSTANCE = new SharedApi();
	
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	/** will be null on the server-side */
	@Nullable
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	
	public static final WorldChunkUpdateManager WORLD_CHUNK_UPDATE_MANAGER = WorldChunkUpdateManager.INSTANCE; // local fariable for quick access
	
	/** Delayed accessing is necessary since this object will be created before the mod accessors are bound. */
	private static class DelayedAccessors
	{
		public static final IChunkyAccessor CHUNKY = ModAccessorInjector.INSTANCE.get(IChunkyAccessor.class);
	}
	
	
	@Nullable
	private static AbstractDhWorld currentWorld;
	private static final Object worldLockObject = new Object();
	
	
	
	//=============//
	// constructor //
	//=============//
	//region constructor
	
	private SharedApi() { }
	
	//endregion
	
	
	
	//===============//
	// world methods //
	//===============//
	//region world methods
	
	public static EWorldEnvironment getEnvironment() { return (currentWorld == null) ? null : currentWorld.environment; }
	
	public static void setDhWorld(AbstractDhWorld newWorld)
	{
		synchronized (worldLockObject)
		{
			AbstractDhWorld oldWorld = currentWorld;
			if (oldWorld != null)
			{
				oldWorld.close();
			}
			currentWorld = newWorld;
			
			// starting and stopping the DataRenderTransformer is necessary to prevent attempting to
			// access the MC level at inappropriate times, which can cause exceptions
			if (currentWorld != null)
			{
				ThreadPoolUtil.setupThreadPools();
				
				ApiEventInjector.INSTANCE.fireAllEvents(DhApiWorldLoadEvent.class, new DhApiWorldLoadEvent.EventParam());
			}
			else
			{
				ThreadPoolUtil.shutdownThreadPools();
				
				// delayed get because SharedApi will be created before the singleton has been bound 
				AbstractDebugWireframeRenderer debugWireframeRenderer = SingletonInjector.INSTANCE.get(AbstractDebugWireframeRenderer.class);
				debugWireframeRenderer.clearRenderables();
				
				if (MC_RENDER != null)
				{
					MC_RENDER.clearTargetFrameBuffer();
				}
				
				// shouldn't be necessary, but if we missed closing one of the connections this should make sure they're all closed
				AbstractDhRepo.closeAllConnections();
				// needs to be closed on world shutdown to clear out un-processed chunks
				WORLD_CHUNK_UPDATE_MANAGER.clear();
				
				RenderThreadTaskHandler.INSTANCE.clearDebugStats();
				BlockTextureRegistry.INSTANCE.clear();
				
				// recommend that the garbage collector cleans up any objects from the old world and thread pools
				System.gc();
				
				ApiEventInjector.INSTANCE.fireAllEvents(DhApiWorldUnloadEvent.class, new DhApiWorldUnloadEvent.EventParam());
				
				// fired after the unload event so API users can't change the read-only for any new worlds
				DhApiWorldProxy.INSTANCE.setReadOnly(false, false);
			}
		}
	}
	
	@Nullable
	public static AbstractDhWorld getAbstractDhWorld() { return currentWorld; }
	
	/** returns null if the {@link SharedApi#currentWorld} isn't a {@link DhClientWorld} or {@link DhClientServerWorld} */
	@Nullable
	public static IDhClientWorld tryGetDhClientWorld() { return (currentWorld instanceof IDhClientWorld) ? (IDhClientWorld) currentWorld : null; }
	
	/** returns null if the {@link SharedApi#currentWorld} isn't a {@link DhServerWorld} or {@link DhClientServerWorld} */
	@Nullable
	public static IDhServerWorld tryGetDhServerWorld() { return (currentWorld instanceof IDhServerWorld) ? (IDhServerWorld) currentWorld : null; }
	
	//endregion
	
	
	
	//==============//
	// chunk update //
	//==============//
	//region chunk update
	
	/** 
	 * Used to prevent getting a full chunk from MC if it isn't necessary. <br>
	 * This is important since asking MC for a chunk is slow and may block the render thread.
	 */
	public static boolean isChunkAtBlockPosAlreadyUpdating(ILevelWrapper levelWrapper, int blockPosX, int blockPosZ)
	{
		ChunkUpdateQueueManager manager = WORLD_CHUNK_UPDATE_MANAGER.getByLevelWrapper(levelWrapper);
		if (manager == null)
		{
			return true;
		}
		
		return manager.contains(new DhChunkPos(new DhBlockPos2D(blockPosX, blockPosZ))); 
	}
	
	public static boolean isChunkAtChunkPosAlreadyUpdating(ILevelWrapper levelWrapper, int chunkPosX, int chunkPosZ)
	{
		ChunkUpdateQueueManager manager = WORLD_CHUNK_UPDATE_MANAGER.getByLevelWrapper(levelWrapper);
		if (manager == null)
		{
			return true;
		}
		
		return manager.contains(new DhChunkPos(chunkPosX, chunkPosZ)); 
	}
	
	
	
	public void applyChunkUpdate(IChunkWrapper chunkWrapper, ILevelWrapper levelWrapper, boolean waitForLoadedWorld)
	{
		// Chunky may not finish it's first time startup until quite late in
		// the level life cycle, but once we start receiving chunks,
		// that means the level is fully up and running, and
		// chunky should be available to run it's first-time setup.
		if (DelayedAccessors.CHUNKY != null)
		{
			DelayedAccessors.CHUNKY.tryRunFirstTimeSetup();
		}
		
		
		
		//===================//
		// validation checks //
		//===================//
		
		if (chunkWrapper == null)
		{
			// shouldn't happen, but just in case
			return;
		}
		
		AbstractDhWorld dhWorld = SharedApi.getAbstractDhWorld();
		if (dhWorld == null)
		{
			if (levelWrapper instanceof IClientLevelWrapper)
			{
				// If the client world isn't loaded yet, keep track of which chunks were loaded so we can use them later.
				// This may happen if the client world and client level load events happen out of order
				IClientLevelWrapper clientLevel = (IClientLevelWrapper) levelWrapper;
				ClientApi.INSTANCE.waitingChunkByClientLevelAndPos.put(new Pair<>(clientLevel, chunkWrapper.getChunkPos()), chunkWrapper);
			}
			
			return;
		}
		
		// ignore updates if the world is read-only
		if (DhApiWorldProxy.INSTANCE.tryGetReadOnly())
		{
			return;
		}
		
		
		IDhLevel dhLevel;
		if (waitForLoadedWorld)
		{
			// only continue if the level is loaded
			dhLevel = dhWorld.getLevel(levelWrapper);
			if (dhLevel == null)
			{
				if (levelWrapper instanceof IClientLevelWrapper)
				{
					// the client level isn't loaded yet
					IClientLevelWrapper clientLevel = (IClientLevelWrapper) levelWrapper;
					ClientApi.INSTANCE.waitingChunkByClientLevelAndPos.put(new Pair<>(clientLevel, chunkWrapper.getChunkPos()), chunkWrapper);
				}
				
				return;
			}
		}
		else
		{
			// we want to apply the chunk to this level regardless of if the world/level is loaded, 
			// this path may happen (and is needed) if the chunk is applied during level loading 
			// before the world has access to it
			dhLevel = levelWrapper.getDhLevel();
			if (dhLevel == null)
			{
				// shouldn't happen, but just in case
				LOGGER.warn("No DH level provided by the ["+levelWrapper.getClass()+"], some chunks may not update properly.");
				return;
			}
		}
		
		// ignore chunk updates if the network should handle them
		if (dhLevel instanceof DhClientLevel)
		{
			if (!((DhClientLevel) dhLevel).shouldProcessChunkUpdate(chunkWrapper.getChunkPos()))
			{
				return;
			}
		}
		
		// ignore chunk updates for non-rendered levels
		String dimName = dhLevel.getLevelWrapper().getDimensionName();
		if (IgnoredDimensionCsvHandler.INSTANCE.dimensionNameShouldBeIgnored(dimName))
		{
			return;
		}
		
		ChunkUpdateQueueManager chunkManager = WORLD_CHUNK_UPDATE_MANAGER.getByLevelWrapper(levelWrapper);
		// ignore the wrong level wrapper type or
		// if the chunk is already queued for handling
		if (chunkManager == null 
			|| chunkManager.contains(chunkWrapper.getChunkPos()))
		{
			return;
		}
		
		
		queueChunkUpdate(chunkManager, chunkWrapper, dhLevel);
	}
	
	private static void queueChunkUpdate(ChunkUpdateQueueManager chunkManager, IChunkWrapper chunkWrapper, IDhLevel dhLevel)
	{
		// add chunk update data to preUpdate queue
		ChunkUpdateData updateData = new ChunkUpdateData(chunkWrapper, dhLevel);
		chunkManager.addItemToPreUpdateQueue(chunkWrapper.getChunkPos(), updateData);
	}
	
	//endregion
	
	
	
	//=========//
	// F3 Menu //
	//=========//
	//region F3 Menu
	
	public ArrayList<String> getDebugMenuString() { return WORLD_CHUNK_UPDATE_MANAGER.getDebugMenuString(); }
	
	//endregion 
	
	
	
}
