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

package com.seibel.distanthorizons.forge112;

import com.seibel.distanthorizons.common.AbstractModInitializer;
import com.seibel.distanthorizons.common.commonMixins.MixinChunkMapCommon;
import com.seibel.distanthorizons.common.wrappers.misc.ServerPlayerWrapper;
import com.seibel.distanthorizons.common.util.threading.ServerThreadTaskHandler;
import com.seibel.distanthorizons.common.wrappers.world.ServerLevelWrapper;
import com.seibel.distanthorizons.core.api.internal.ServerApi;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IPluginPacketSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkDataEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;


public class ForgeServerProxy implements AbstractModInitializer.IEventProxy
{
	private static final ForgePluginPacketSender PACKET_SENDER = (ForgePluginPacketSender) SingletonInjector.INSTANCE.get(IPluginPacketSender.class);
	private static World GetEventLevel(WorldEvent e) { return e.getWorld(); }
	
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	private final ServerApi serverApi = ServerApi.INSTANCE;
	private final boolean isDedicated;
	

	
	@Override
	public void registerEvents()
	{
		MinecraftForge.EVENT_BUS.register(this);
		FMLCommonHandler.instance().bus().register(this);
		if (this.isDedicated)
		{
			PACKET_SENDER.setPacketHandler(ServerApi.INSTANCE::pluginMessageReceived);
		}
	}
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public ForgeServerProxy(boolean isDedicated) { this.isDedicated = isDedicated; }
	
	//endregion
	
	
	
	//========//
	// events //
	//========//
	//region

	@SubscribeEvent
	public void serverTickEvent(TickEvent.ServerTickEvent event)
	{
		if (event.phase == TickEvent.Phase.START)
		{
			ServerThreadTaskHandler.INSTANCE.onTickStart();
		}
		else
		{
			ServerThreadTaskHandler.INSTANCE.runTasks();
		}
	}
	
	// ServerLevelLoadEvent
	@SubscribeEvent
	public void serverLevelLoadEvent(WorldEvent.Load event)
	{
		if (GetEventLevel(event) instanceof WorldServer)
		{
			this.serverApi.serverLevelLoadEvent(getServerLevelWrapper((WorldServer) GetEventLevel(event)));
		}
	}
	
	// ServerLevelUnloadEvent
	@SubscribeEvent
	public void serverLevelUnloadEvent(WorldEvent.Unload event)
	{
		if (GetEventLevel(event) instanceof WorldServer)
		{
			this.serverApi.serverLevelUnloadEvent(getServerLevelWrapper((WorldServer) GetEventLevel(event)));
		}
	}
	
	@SubscribeEvent
	public void serverChunkLoadEvent(ChunkEvent.Load event)
	{
		if (event.getWorld() instanceof WorldServer)
		{
			WorldServer worldServer = (WorldServer) event.getWorld();
			MixinChunkMapCommon.onChunkSave(worldServer, event.getChunk());
		}
	}
	
	@SubscribeEvent
	public void serverChunkSaveEvent(ChunkDataEvent.Save event)
	{
		if (event.getWorld() instanceof WorldServer 
			&& event.getChunk().dirty)
		{
			WorldServer worldServer = (WorldServer) event.getWorld();
			MixinChunkMapCommon.onChunkSave(worldServer, event.getChunk());
		}
	}
	
	@SubscribeEvent
	public void playerLoggedInEvent(PlayerEvent.PlayerLoggedInEvent event)
	{ this.serverApi.serverPlayerJoinEvent(getServerPlayerWrapper(event)); }
	
	@SubscribeEvent
	public void playerLoggedOutEvent(PlayerEvent.PlayerLoggedOutEvent event)
	{ this.serverApi.serverPlayerDisconnectEvent(getServerPlayerWrapper(event)); }
	
	@SubscribeEvent
	public void playerChangedDimensionEvent(PlayerEvent.PlayerChangedDimensionEvent event)
	{
		this.serverApi.serverPlayerLevelChangeEvent(
			getServerPlayerWrapper(event),
			getServerLevelWrapper(event.fromDim, event),
			getServerLevelWrapper(event.toDim, event)
		);
	}
	
	//endregion
	
	
	
	//================//
	// helper methods //
	//================//
	//region
	
	private static ServerLevelWrapper getServerLevelWrapper(WorldServer level) { return ServerLevelWrapper.getWrapper(level); }
	
	private static ServerLevelWrapper getServerLevelWrapper(int dimensionId, PlayerEvent event)
	{
		MinecraftServer server = event.player.getServer();
		if (server == null)
		{
			LOGGER.error("getServerLevelWrapper: server is null for player {}", event.player.getName());
			return null;
		}
		return getServerLevelWrapper(server.getWorld(dimensionId));
	}
	
	private static ServerPlayerWrapper getServerPlayerWrapper(PlayerEvent event)
	{ return ServerPlayerWrapper.getWrapper((EntityPlayerMP) event.player); }
	
	//endregion
	
	
}
