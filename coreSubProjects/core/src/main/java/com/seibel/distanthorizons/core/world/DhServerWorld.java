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
import com.seibel.distanthorizons.core.generation.PregenManager;
import com.seibel.distanthorizons.core.level.DhServerLevel;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

public class DhServerWorld extends AbstractDhServerWorld<DhServerLevel>
{
	private final PregenManager pregenManager = new PregenManager();
	public PregenManager getPregenManager() { return this.pregenManager; }
	
	
	//==============//
	// constructors //
	//==============//
	
	public DhServerWorld()
	{
		super(EWorldEnvironment.SERVER_ONLY);
		LOGGER.info("Started ["+DhServerWorld.class.getSimpleName()+"] of type ["+this.environment+"].");
	}
	
	
	
	//================//
	// level handling //
	//================//
	
	@Override
	public DhServerLevel getOrLoadLevel(@NotNull ILevelWrapper wrapper)
	{
		if (!(wrapper instanceof IServerLevelWrapper))
		{
			return null;
		}
		
		return this.dhLevelByLevelWrapper.computeIfAbsent(wrapper, 
			(serverLevelWrapper) ->
			{
				try
				{
					DhServerLevel level = new DhServerLevel(this.saveStructure, (IServerLevelWrapper) serverLevelWrapper, this.getServerPlayerStateManager());
					ApiEventInjector.INSTANCE.fireAllEvents(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent.EventParam(wrapper));
					return level;
				}
				catch (Exception e)
				{
					LOGGER.fatal("Failed to load server level, error: ["+e.getMessage()+"].", e);
					
					String r = MinecraftTextFormat.RED;
					String y = MinecraftTextFormat.YELLOW;
					String cf = MinecraftTextFormat.CLEAR_FORMATTING;
					
					ClientApi.INSTANCE.queueSlowChatMessage(
						r + "Distant Horizons: Server level loading failed." + cf + "\n" +
						"Unable to load level ["+y+serverLevelWrapper.getDhIdentifier()+cf+"], LODs may not appear. See log for more information.\n" +
						"");
					
					return null;
				}
			});
	}
	
	@Override
	public boolean unloadLevel(@NotNull ILevelWrapper wrapper)
	{
		if (!(wrapper instanceof IServerLevelWrapper))
		{
			return false;
		}
		
		if (this.dhLevelByLevelWrapper.containsKey(wrapper))
		{
			wrapper.onUnload();
			this.dhLevelByLevelWrapper.remove(wrapper).close();
			ApiEventInjector.INSTANCE.fireAllEvents(DhApiLevelUnloadEvent.class, new DhApiLevelUnloadEvent.EventParam(wrapper));
			return true;
		}
		
		return false;
	}
	
	@Override
	public void close()
	{
		CompletableFuture<Void> runningPregen = this.pregenManager.getRunningPregen();
		if (runningPregen != null)
		{
			LOGGER.info("Stopping the running pregen task.");
			runningPregen.cancel(true);
		}
		
		super.close();
	}
	
}
