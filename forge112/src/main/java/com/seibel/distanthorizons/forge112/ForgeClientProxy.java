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
import com.seibel.distanthorizons.common.util.ProxyUtil;
import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.api.internal.ServerApi;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.enums.MinecraftTextFormat;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.network.messages.AbstractNetworkMessage;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IPluginPacketSender;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.opengl.GL30;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

import java.util.Arrays;
import java.util.concurrent.AbstractExecutorService;

public class ForgeClientProxy implements AbstractModInitializer.IEventProxy
{
	private static final IMinecraftClientWrapper MC = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	private static final ForgePluginPacketSender PACKET_SENDER = (ForgePluginPacketSender) SingletonInjector.INSTANCE.get(IPluginPacketSender.class);
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static World GetEventLevel(WorldEvent e) { return e.getWorld(); }
	
	
	@Override
	public void registerEvents()
	{
		MinecraftForge.EVENT_BUS.register(this);
		MinecraftForge.EVENT_BUS.register(FMLCommonHandler.instance());
		
		// handles singleplayer, LAN, and connecting to a server
		PACKET_SENDER.setPacketHandler((IServerPlayerWrapper player, @NotNull AbstractNetworkMessage message) ->
		{
			ClientApi.INSTANCE.pluginMessageReceived(message);
			ServerApi.INSTANCE.pluginMessageReceived(player, message);
		});
	}
	
	
	//==============//
	// chunk events //
	//==============//
	//region
	
	@SubscribeEvent
	public void rightClickBlockEvent(PlayerInteractEvent.RightClickBlock event)
	{
		if (MC.clientConnectedToDedicatedServer())
		{
			World level = event.getWorld();
			
			ILevelWrapper wrappedLevel = ProxyUtil.getLevelWrapper(level);
			if (SharedApi.isChunkAtBlockPosAlreadyUpdating(wrappedLevel, event.getPos().getX(), event.getPos().getZ()))
			{
				return;
			}
			
			AbstractExecutorService executor = ThreadPoolUtil.getFileHandlerExecutor();
			if (executor != null)
			{
				executor.execute(() ->
				{
					Chunk chunk = level.getChunk(event.getPos());
					SharedApi.INSTANCE.applyChunkUpdate(
						new ChunkWrapper(chunk, wrappedLevel),
						wrappedLevel,
						true
					);
				});
			}
		}
	}
	@SubscribeEvent
	public void leftClickBlockEvent(PlayerInteractEvent.LeftClickBlock event)
	{
		if (MC.clientConnectedToDedicatedServer())
		{
			World level = event.getWorld();
			
			ILevelWrapper wrappedLevel = ProxyUtil.getLevelWrapper(level);
			if (SharedApi.isChunkAtBlockPosAlreadyUpdating(wrappedLevel, event.getPos().getX(), event.getPos().getZ()))
			{
				return;
			}
			
			AbstractExecutorService executor = ThreadPoolUtil.getFileHandlerExecutor();
			if (executor != null)
			{
				executor.execute(() ->
				{
					Chunk chunk = level.getChunk(event.getPos());
					SharedApi.INSTANCE.applyChunkUpdate(
						new ChunkWrapper(chunk, wrappedLevel),
						wrappedLevel,
						true
					);
				});
			}
		}
	}
	
	//Handled in MixinNetHandlerPlayClient
/*	@SubscribeEvent
	public void clientChunkLoadEvent(ChunkEvent.Load event)
	{
		if (!event.getWorld().isRemote || !MC.clientConnectedToDedicatedServer())
		{
			return;
		}
		
		ILevelWrapper wrappedLevel = ProxyUtil.getLevelWrapper(event.getWorld());
		SharedApi.INSTANCE.applyChunkUpdate(
			new ChunkWrapper(event.getChunk(), wrappedLevel),
			wrappedLevel,
			true
		);
	}*/
	
	//endregion
	
	
	
	//==============//
	// key bindings //
	//==============//
	//region
	
	@SubscribeEvent
	public void registerKeyBindings(InputEvent.KeyInputEvent event)
	{
	/*	if (Minecraft.getMinecraft().player == null)
		{
			return;
		}
		if (event.getAction() != GLFW.GLFW_PRESS)
		{
			return;
		}
		
		ClientApi.INSTANCE.keyPressedEvent(event.getKey());*/
	}
	
	//endregion
	
	
	
	//===========//
	// rendering //
	//===========//
	//region
	
	@SubscribeEvent
	public void afterLevelRenderEvent(TickEvent.RenderTickEvent event)
	{
		if (event.type.equals(TickEvent.RenderTickEvent.Type.RENDER))
		{
			boolean framebufferMixinWasEnabled = MixinFlags.framebufferMixinEnabled;
			MixinFlags.framebufferMixinEnabled = true;
			try
			{
				if (!framebufferMixinWasEnabled)
				{
					// The splash screen may have created MC's main framebuffer before our redirect was enabled.
					// Rebuild it once after the first real render tick so the depth attachment becomes a texture.
					Framebuffer framebuffer = Minecraft.getMinecraft().getFramebuffer();
					if (framebuffer != null)
					{
						framebuffer.createBindFramebuffer(framebuffer.framebufferWidth, framebuffer.framebufferHeight);
					}
				}
			}
			catch (Exception | Error e)
			{
				LOGGER.error("Unexpected error in afterLevelRenderEvent: " + e.getMessage(), e);
			}
		}
	}
	
	@SubscribeEvent
	public void onRenderOverlay(RenderGameOverlayEvent.Text event)
	{
		Minecraft mc = Minecraft.getMinecraft();
		if (event.isCanceled()
			|| !mc.gameSettings.showDebugInfo)
		{
			return;
		}
		
		F3Screen.addStringToDisplay(event.getRight());
	}
	
	//endregion
}