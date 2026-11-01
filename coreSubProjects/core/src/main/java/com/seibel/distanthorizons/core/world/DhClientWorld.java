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
import com.seibel.distanthorizons.core.api.internal.ClientPluginChannelApi;
import com.seibel.distanthorizons.core.enums.MinecraftTextFormat;
import com.seibel.distanthorizons.core.file.structure.ClientOnlySaveStructure;
import com.seibel.distanthorizons.core.level.DhClientLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.level.IServerKeyedClientLevel;
import com.seibel.distanthorizons.core.multiplayer.client.ClientNetworkState;
import com.seibel.distanthorizons.core.util.TimerUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class DhClientWorld extends AbstractDhWorld implements IDhClientWorld
{
	public final ClientOnlySaveStructure saveStructure;
	public final ClientNetworkState networkState = new ClientNetworkState();
	
	
	private final ConcurrentHashMap<String, DhClientLevel> clientLevelByDhId;
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
	private final Map<String, Set<IClientLevelWrapper>> clientLevelWrapperSetByDhId = new ConcurrentHashMap<>();
	
	private final Timer clientTickTimer = TimerUtil.CreateTimer("ClientTickTimer");
	
	public final ClientPluginChannelApi pluginChannelApi = new ClientPluginChannelApi();
	private static final long FIRST_LEVEL_LOAD_DELAY_IN_MS = 1_000;
	/** Delay loading the first level to give the server some time to respond with level to actually load */
	private long allowLoadingLevelsAfter = 0;
	private final Set</* ClientLevel */ Object> levelInitRequestedClientLevels = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
	
	
	//==============//
	// constructors //
	//==============//
	
	public DhClientWorld()
	{
		super(EWorldEnvironment.CLIENT_ONLY);
		
		this.saveStructure = new ClientOnlySaveStructure();
		this.clientLevelByDhId = new ConcurrentHashMap<>();
		
		LOGGER.info("Started DhWorld of type " + this.environment);
		
		this.pluginChannelApi.onJoinServer(networkState.getSession());
		this.networkState.sendConfigMessage();
		
		this.clientTickTimer.scheduleAtFixedRate(new TimerTask()
		{
			@Override
			public void run()
			{
				DhClientWorld.this.clientLevelByDhId.values().forEach(DhClientLevel::clientTick);
			}
		}, 0, IDhClientWorld.TICK_RATE_IN_MS);
	}
	
	
	
	//=========//
	// methods //
	//=========//
	
	@Override
	public DhClientLevel getOrLoadLevel(@NotNull ILevelWrapper wrapper)
	{
		if (!(wrapper instanceof IClientLevelWrapper))
		{
			return null;
		}
		
		IClientLevelWrapper clientLevelWrapper = (IClientLevelWrapper) wrapper;
		clientLevelWrapper.markAccessed();
		DhClientLevel storedLevel = this.clientLevelByDhId.computeIfAbsent(wrapper.getDhIdentifier(),
			(key) -> this.createClientLevel(clientLevelWrapper)
		);
		
		if (storedLevel != null 
			&& storedLevel.getClientLevelWrapper() != wrapper) 
		{
			this.unloadLevel(storedLevel.getLevelWrapper());
			storedLevel = this.createClientLevel(clientLevelWrapper);
			if (storedLevel != null)
			{
				this.clientLevelByDhId.put(wrapper.getDhIdentifier(), storedLevel);
			}
		}
		return storedLevel;
	}
	private DhClientLevel createClientLevel(@NotNull IClientLevelWrapper clientLevelWrapper)
	{
		try
		{
			if (!this.ensureLevelKeyWhenAvailable(clientLevelWrapper))
			{
				return null;
			}
			
			DhClientLevel level = new DhClientLevel(this.saveStructure, clientLevelWrapper, this.networkState);
			this.clientLevelWrapperSetByDhId.computeIfAbsent(clientLevelWrapper.getDhIdentifier(), (dhId) -> Collections.synchronizedSet(new HashSet<>())).add(clientLevelWrapper);
			
			ApiEventInjector.INSTANCE.fireAllEvents(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent.EventParam(clientLevelWrapper));
			ClientApi.INSTANCE.loadWaitingChunksForLevel(clientLevelWrapper);
			
			return level;
		}
		catch (Exception e)
		{
			LOGGER.fatal("Failed to load client level, error: ["+e.getMessage()+"].", e);
			
			String r = MinecraftTextFormat.RED;
			String y = MinecraftTextFormat.YELLOW;
			String cf = MinecraftTextFormat.CLEAR_FORMATTING;
			
			ClientApi.INSTANCE.queueSlowChatMessage(
				r + "Distant Horizons: Client level loading failed." + cf + "\n" +
				"Unable to load level ["+y+clientLevelWrapper.getDhIdentifier()+cf+"], LODs may not appear. See log for more information. \n" +
				"");
			
			return null;
		}
	}
	
	private boolean ensureLevelKeyWhenAvailable(@NotNull IClientLevelWrapper clientLevelWrapper)
	{
		if (!this.pluginChannelApi.allowLevelLoading(clientLevelWrapper))
		{
			LOGGER.debug("Client levels in this connection are managed by the server, skipping auto-load of: ["+clientLevelWrapper+"]");
			
			// Instead of attempting to load themselves, send the config and wait for a server provided level key
			this.sendLevelInitRequestIfNeed(clientLevelWrapper);
			return false;
		}
		
		if (clientLevelWrapper instanceof IServerKeyedClientLevel)
		{
			this.sendLevelInitRequestIfNeed(clientLevelWrapper);
		}
		
		// Make non-keyed levels wait some delay since first attempt to load anything,
		// so the server can reply to the level key request
		if (!(clientLevelWrapper instanceof IServerKeyedClientLevel))
		{
			this.sendLevelInitRequestIfNeed(clientLevelWrapper);
			
			if (this.allowLoadingLevelsAfter == 0)
			{
				this.allowLoadingLevelsAfter = System.currentTimeMillis() + FIRST_LEVEL_LOAD_DELAY_IN_MS;
			}
			
			return System.currentTimeMillis() >= this.allowLoadingLevelsAfter;
		}
		
		return true;
	}
	
	private void sendLevelInitRequestIfNeed(@NotNull IClientLevelWrapper clientLevelWrapper)
	{
		Object clientLevelObject = clientLevelWrapper.getWrappedMcObject();
		if (clientLevelObject != null
			&& this.levelInitRequestedClientLevels.add(clientLevelObject))
		{
			this.networkState.sendLevelInitRequest(clientLevelWrapper.getDimensionName());
		}
	}
	
	@Override
	public DhClientLevel getLevel(@NotNull ILevelWrapper wrapper)
	{
		if (!(wrapper instanceof IClientLevelWrapper))
		{
			return null;
		}
		
		return this.clientLevelByDhId.get(wrapper.getDhIdentifier());
	}
	
	@Override
	public Iterable<? extends IDhLevel> getAllLoadedLevels() { return this.clientLevelByDhId.values(); }
	@Override
	public int getLoadedLevelCount() { return this.clientLevelByDhId.size(); }
	
	@Override
	public boolean unloadLevel(@NotNull ILevelWrapper wrapper)
	{
		if (!(wrapper instanceof IClientLevelWrapper))
		{
			return false;
		}
		
		if (this.clientLevelByDhId.containsKey(wrapper.getDhIdentifier()))
		{
			LOGGER.info("Unloading level [" + this.clientLevelByDhId.get(wrapper.getDhIdentifier()) + "].");
			wrapper.onUnload();
			Set<IClientLevelWrapper> wrapperSet = this.clientLevelWrapperSetByDhId.get(wrapper.getDhIdentifier());
			wrapperSet.remove(wrapper);
			if (wrapperSet.isEmpty()) 
			{
				this.clientLevelByDhId.remove(wrapper.getDhIdentifier()).close();
			}
			
			ApiEventInjector.INSTANCE.fireAllEvents(DhApiLevelUnloadEvent.class, new DhApiLevelUnloadEvent.EventParam(wrapper));
			
			return true;
		}
		
		return false;
	}
	
	@Override
	public void addDebugMenuStringsToList(List<String> messageList)
	{
		super.addDebugMenuStringsToList(messageList);
		this.networkState.addDebugMenuStringsToList(messageList);
	}
	
	@Override
	public void close()
	{
		this.networkState.close();
		this.pluginChannelApi.reset();
		
		ArrayList<CompletableFuture<Void>> closeFutures = new ArrayList<>();
		for (DhClientLevel dhClientLevel : this.clientLevelByDhId.values())
		{
			// level wrapper shouldn't be null, but just in case
			IClientLevelWrapper clientLevelWrapper = dhClientLevel.getClientLevelWrapper();
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
				dhClientLevel.close();
				closeFuture.complete(null);
			}, "level shutdown");
			closeThread.start();
			closeFutures.add(closeFuture);
		}
		
		// wait for all the levels to finish closing
		for (CompletableFuture<Void> future : closeFutures)
		{
			future.join();
		}
		
		this.clientLevelByDhId.clear();
		this.clientLevelWrapperSetByDhId.clear();
		this.levelInitRequestedClientLevels.clear();
		this.clientTickTimer.cancel();
		LOGGER.info("Closed DhWorld of type [" + this.environment + "].");
	}
	
}
