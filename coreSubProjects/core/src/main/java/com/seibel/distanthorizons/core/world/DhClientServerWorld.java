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

package com.seibel.distanthorizons.core.world;

import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelUnloadEvent;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.enums.MinecraftTextFormat;
import com.seibel.distanthorizons.core.level.DhClientServerLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.util.TimerUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class DhClientServerWorld extends AbstractDhServerWorld<DhClientServerLevel> implements IDhClientWorld
{
	/**
	 * Having a set of level wrappers is done to handle an issue where the client 
	 * level would get unloaded when jumping back and forth between dimensions. <br><br>
	 * 
	 * We might have more than one {@link ILevelWrapper} pointing to the same {@link IDhLevel} 
	 * since they're not immediately unloaded, and we don't want to unload the {@link IDhLevel} 
	 * until all the {@link ILevelWrapper} for that {@link IDhLevel} have been unloaded. 
	 * Any stale {@link IDhLevel} references should disappear on their own after about 
	 * 30 seconds or so thanks to the automatic cleanup.
	 */
	private final Map<DhClientServerLevel, Set<ILevelWrapper>> clientLevelWrapperSetByDhLevel
		= Collections.synchronizedMap(new HashMap<>());
	
	private final Timer clientTickTimer = TimerUtil.CreateTimer("ClientTickTimer");
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public DhClientServerWorld()
	{
		super(EWorldEnvironment.CLIENT_SERVER);
		LOGGER.info("Started DhWorld of type [" + this.environment + "].");
		
		this.clientTickTimer.scheduleAtFixedRate(new TimerTask() 
		{
			@Override 
			public void run()
			{
				DhClientServerWorld.this.clientLevelWrapperSetByDhLevel.keySet().forEach(DhClientServerLevel::clientTick);
			}
		}, 0, IDhClientWorld.TICK_RATE_IN_MS);
	}
	
	
	
	//=========//
	// methods //
	//=========//
	
	@Override
	public DhClientServerLevel getOrLoadLevel(@NotNull ILevelWrapper wrapper)
	{
		if (wrapper instanceof IServerLevelWrapper)
		{
			return this.dhLevelByLevelWrapper.computeIfAbsent(wrapper, (levelWrapper) ->
			{
				try
				{
					DhClientServerLevel level = new DhClientServerLevel(this.saveStructure, (IServerLevelWrapper) levelWrapper, this.getServerPlayerStateManager());
					this.clientLevelWrapperSetByDhLevel.computeIfAbsent(level, (clientServerLevel) -> Collections.synchronizedSet(new HashSet<>()));
					ApiEventInjector.INSTANCE.fireAllEvents(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent.EventParam(wrapper));
					return level;
				}
				catch (Exception e)
				{
					LOGGER.fatal("Failed to load client-server level, error: ["+e.getMessage()+"].", e);
					
					String r = MinecraftTextFormat.RED;
					String y = MinecraftTextFormat.YELLOW;
					String cf = MinecraftTextFormat.CLEAR_FORMATTING;
					
					ClientApi.INSTANCE.queueSlowChatMessage(// red text		
						r + "Distant Horizons: ClientServer level loading failed." + cf + "\n" +
						"Unable to load level ["+y+levelWrapper.getDhIdentifier()+cf+"], LODs may not appear. See log for more information.\n" +
						"");
					
					return null;
				}
			});
		}
		else
		{
			if (wrapper instanceof IClientLevelWrapper)
			{
				((IClientLevelWrapper) wrapper).markAccessed();
			}
			
			return this.dhLevelByLevelWrapper.computeIfAbsent(wrapper, (levelWrapper) ->
			{
				if (!(levelWrapper instanceof IClientLevelWrapper))
				{
					LodUtil.assertNotReach("tryGetServerSideWrapper given a non-IClientLevelWrapper.");
				}
				
				IClientLevelWrapper clientLevelWrapper = (IClientLevelWrapper) levelWrapper;
				IServerLevelWrapper serverLevelWrapper = clientLevelWrapper.tryGetServerSideWrapper();
				LodUtil.assertTrue(serverLevelWrapper != null);
				
				if (!clientLevelWrapper.getDimensionType().equals(serverLevelWrapper.getDimensionType()))
				{
					LodUtil.assertNotReach("tryGetServerSideWrapper returned a level for a different dimension. ClientLevelWrapper dim: [" + clientLevelWrapper.getDhIdentifier() + "] ServerLevelWrapper dim: [" + serverLevelWrapper.getDhIdentifier() + "].");
				}
				
				
				DhClientServerLevel level = this.dhLevelByLevelWrapper.get(serverLevelWrapper);
				if (level == null)
				{
					return null;
				}
				
				level.startRenderer();
				clientLevelWrapper.setDhLevel(level);
				this.clientLevelWrapperSetByDhLevel.get(level).add(wrapper);
				return level;
			});
		}
	}
	
	@Override
	public boolean unloadLevel(@NotNull ILevelWrapper wrapper)
	{
		if (this.dhLevelByLevelWrapper.containsKey(wrapper))
		{
			if (wrapper instanceof IServerLevelWrapper)
			{
				LOGGER.info("Unloading level " + this.dhLevelByLevelWrapper.get(wrapper));
				wrapper.onUnload();
				
				DhClientServerLevel clientServerLevel = this.dhLevelByLevelWrapper.remove(wrapper);
				clientServerLevel.close();
				this.clientLevelWrapperSetByDhLevel.remove(clientServerLevel);
			}
			else
			{
				// If the level wrapper is a Client Level Wrapper, then that means the client side leaves the level,
				// but note that the server side still has the level loaded. So, we don't want to unload the level,
				// we just want to stop rendering it.
				DhClientServerLevel level = this.dhLevelByLevelWrapper.remove(wrapper); // Ignore resource warning. The level obj is referenced elsewhere.
				Set<ILevelWrapper> wrappers = clientLevelWrapperSetByDhLevel.get(level);
				if (wrappers != null)
				{
					wrappers.remove(wrapper);
				}
				
				if ((wrappers == null || wrappers.isEmpty()) 
					&& level.isRendering()) 
				{
					level.stopRenderer();
				}
				wrapper.onUnload(); // We still want to unload the wrapper though.
			}
			
			ApiEventInjector.INSTANCE.fireAllEvents(DhApiLevelUnloadEvent.class, new DhApiLevelUnloadEvent.EventParam(wrapper));
			return true;
		}
		
		return false;
	}
	
	
	
	//================//
	// base overrides //
	//================//
	
	/** synchronized to prevent a rare issue where the server tries closing the same world multiple times in rapid succession. */
	@Override
	public synchronized void close()
	{
		super.close();
		
		ArrayList<CompletableFuture<Void>> closeFutures = new ArrayList<>();
		
		synchronized (this.clientLevelWrapperSetByDhLevel)
		{
			// close each level
			for (DhClientServerLevel level : this.clientLevelWrapperSetByDhLevel.keySet())
			{
				// level wrapper shouldn't be null, but just in case
				IServerLevelWrapper serverLevelWrapper = level.getServerLevelWrapper();
				if (serverLevelWrapper != null)
				{
					serverLevelWrapper.onUnload();
					ApiEventInjector.INSTANCE.fireAllEvents(DhApiLevelUnloadEvent.class, new DhApiLevelUnloadEvent.EventParam(serverLevelWrapper));
				}
				
				IClientLevelWrapper clientLevelWrapper = level.getClientLevelWrapper();
				if (clientLevelWrapper != null)
				{
					clientLevelWrapper.onUnload();
					ApiEventInjector.INSTANCE.fireAllEvents(DhApiLevelUnloadEvent.class, new DhApiLevelUnloadEvent.EventParam(clientLevelWrapper));
				}
				
				// close levels asynchronously to speed up
				// shutdown on servers with a lot of levels
				CompletableFuture<Void> closeFuture = new CompletableFuture<>();
				Thread closeThread = new Thread(() -> 
				{
					level.close();
					closeFuture.complete(null);
				}, "level shutdown");
				closeThread.start();
				closeFutures.add(closeFuture);
			}
		}
		
		// wait for all the levels to finish closing
		for (CompletableFuture<Void> future : closeFutures)
		{
			future.join();
		}
		
		
		this.dhLevelByLevelWrapper.clear();
		this.clientTickTimer.cancel();
		LOGGER.info("Closed DhWorld of type [" + this.environment + "].");
	}
	
}
