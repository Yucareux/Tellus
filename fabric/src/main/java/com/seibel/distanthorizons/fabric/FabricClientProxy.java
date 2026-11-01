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

package com.seibel.distanthorizons.fabric;

import com.seibel.distanthorizons.common.AbstractModInitializer;
import com.seibel.distanthorizons.common.AbstractPluginPacketSender;
import com.seibel.distanthorizons.common.wrappers.McObjectConverter;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftClientWrapper;
import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper;

import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IPluginPacketSender;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.ISodiumAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.fabric.wrappers.modAccessor.SodiumAccessor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

#if MC_VER >= MC_1_20_6
import com.seibel.distanthorizons.common.CommonPacketPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
#else
import com.seibel.distanthorizons.core.network.messages.AbstractNetworkMessage;
#endif

#if MC_VER < MC_1_19_4
import java.nio.FloatBuffer;
#endif
import java.util.HashSet;
import java.util.concurrent.AbstractExecutorService;

#if MC_VER < MC_1_21_9
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
#endif

#if MC_VER <= MC_1_21_11
#else
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelTerrainRenderContext;
#endif

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.HitResult;

#if MC_VER <= MC_26_2_0
import org.lwjgl.glfw.GLFW;
#else
#endif

/**
 * This handles all events sent to the client,
 * and is the starting point for most of the mod.
 * 
 * @author coolGi
 * @author Ran
 * @version 2023-7-27
 */
@Environment(EnvType.CLIENT)
public class FabricClientProxy implements AbstractModInitializer.IEventProxy
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final MinecraftClientWrapper MC = MinecraftClientWrapper.INSTANCE;
	private static final AbstractPluginPacketSender PACKET_SENDER = (AbstractPluginPacketSender) SingletonInjector.INSTANCE.get(IPluginPacketSender.class);
	
	HashSet<Integer> previouslyPressKeyCodes = new HashSet<>();
	
	
	
	/**
	 * Registers Fabric Events
	 * @author Ran
	 */
	@Override
	public void registerEvents()
	{
		LOGGER.info("Registering Fabric Client Events");
		
		
		
		//==============//
		// chunk events //
		//==============//
		//region
		
		// ClientChunkLoadEvent
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) ->
		{
			if (MC.clientConnectedToDedicatedServer())
			{
				// executor to prevent locking up the render/event thread
				AbstractExecutorService executor = ThreadPoolUtil.getFileHandlerExecutor();
				if (executor != null)
				{
					executor.execute(() ->
					{
						IClientLevelWrapper wrappedLevel = ClientLevelWrapper.getWrapper(level);
						SharedApi.INSTANCE.applyChunkUpdate(new ChunkWrapper(chunk, wrappedLevel), wrappedLevel, true);
					});
				}
			}
		});
		
		// (kinda) block break event
		// Since fabric doesn't have a client-side break-block API event, this is the next best thing
		AttackBlockCallback.EVENT.register((player, level, interactionHand, blockPos, direction) ->
		{
			// if we have access to the server, use the chunk save event instead 
			if (MC.clientConnectedToDedicatedServer())
			{
				IClientLevelWrapper wrappedLevel = ClientLevelWrapper.getWrapper((ClientLevel) level);
				
				if (SharedApi.isChunkAtBlockPosAlreadyUpdating(wrappedLevel, blockPos.getX(), blockPos.getZ()))
				{
					// executor to prevent locking up the render/event thread
					AbstractExecutorService executor = ThreadPoolUtil.getFileHandlerExecutor();
					if (executor != null)
					{
						executor.execute(() ->
						{
							ChunkAccess chunk = level.getChunk(blockPos);
							if (chunk != null)
							{
								//LOGGER.trace("attack block at blockPos: " + blockPos);
								
								SharedApi.INSTANCE.applyChunkUpdate(
									new ChunkWrapper(chunk, wrappedLevel),
									wrappedLevel,
									true
								);
							}
						});
					}
				}
			}
			
			// don't stop the callback
			return InteractionResult.PASS;
		});
		
		// (kinda) block place event
		// Since fabric doesn't have a client-side place-block API event, this is the next best thing
		UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> 
		{
			// if we have access to the server, use the chunk save event instead 
			if (MC.clientConnectedToDedicatedServer())
			{
				if (hitResult.getType() == HitResult.Type.BLOCK
						&& !hitResult.isInside())
				{
					IClientLevelWrapper wrappedLevel = ClientLevelWrapper.getWrapper((ClientLevel) level);
					
					if (SharedApi.isChunkAtBlockPosAlreadyUpdating(wrappedLevel, hitResult.getBlockPos().getX(), hitResult.getBlockPos().getZ()))
					{
						// executor to prevent locking up the render/event thread
						AbstractExecutorService executor = ThreadPoolUtil.getFileHandlerExecutor();
						if (executor != null)
						{
							executor.execute(() ->
							{
								ChunkAccess chunk = level.getChunk(hitResult.getBlockPos());
								if (chunk != null)
								{
									//LOGGER.trace("use block at blockPos: " + hitResult.getBlockPos());
									
									SharedApi.INSTANCE.applyChunkUpdate(
										new ChunkWrapper(chunk, wrappedLevel),
										wrappedLevel,
										true
									);
								}
							});
						}
					}
				}
			}
			
			// don't stop the callback
			return InteractionResult.PASS;
		});
		
		//endregion
		
		
		
		//==============//
		// render event //
		//==============//
		//region
		
		#if MC_VER < MC_1_21_9
		WorldRenderEvents.AFTER_SETUP.register((renderContext) ->
		{
			ClientApi.RENDER_STATE.mcProjectionMatrix = McObjectConverter.convert(renderContext.projectionMatrix());
			
			#if MC_VER < MC_1_20_6
			ClientApi.RENDER_STATE.mcModelViewMatrix = McObjectConverter.convert(renderContext.matrixStack().last().pose());
			#else
			ClientApi.RENDER_STATE.mcModelViewMatrix = McObjectConverter.convert(renderContext.positionMatrix());
			#endif
			
			#if MC_VER < MC_1_21_1
			ClientApi.RENDER_STATE.partialTickTime = renderContext.tickDelta();
			#else
			ClientApi.RENDER_STATE.partialTickTime = renderContext.tickCounter().getGameTimeDeltaTicks();
			#endif
			
			ClientApi.RENDER_STATE.clientLevelWrapper = ClientLevelWrapper.getWrapperIfDifferent(ClientApi.RENDER_STATE.clientLevelWrapper, renderContext.world());
			
			
			ClientApi.INSTANCE.renderLods();
		});
		
		
		WorldRenderEvents.AFTER_ENTITIES.register((renderContext) ->
		{
			ClientApi.RENDER_STATE.mcProjectionMatrix = McObjectConverter.convert(renderContext.projectionMatrix());
			
			#if MC_VER < MC_1_20_6
			ClientApi.RENDER_STATE.mcModelViewMatrix = McObjectConverter.convert(renderContext.matrixStack().last().pose());
			#else
			ClientApi.RENDER_STATE.mcModelViewMatrix = McObjectConverter.convert(renderContext.positionMatrix());
			#endif
			
			#if MC_VER < MC_1_21_1
			ClientApi.RENDER_STATE.partialTickTime = renderContext.tickDelta();
			#else
			ClientApi.RENDER_STATE.partialTickTime = renderContext.tickCounter().getGameTimeDeltaTicks();
			#endif
			
			ClientApi.RENDER_STATE.clientLevelWrapper = ClientLevelWrapper.getWrapperIfDifferent(ClientApi.RENDER_STATE.clientLevelWrapper, renderContext.world());
			
			
			ClientApi.INSTANCE.renderFadeOpaque();
		});
		
		WorldRenderEvents.AFTER_TRANSLUCENT.register((renderContext) ->
		{
			ClientApi.RENDER_STATE.mcProjectionMatrix = McObjectConverter.convert(renderContext.projectionMatrix());
			
			#if MC_VER < MC_1_20_6
			ClientApi.RENDER_STATE.mcModelViewMatrix = McObjectConverter.convert(renderContext.matrixStack().last().pose());
			#else
			ClientApi.RENDER_STATE.mcModelViewMatrix = McObjectConverter.convert(renderContext.positionMatrix());
			#endif
			
			#if MC_VER < MC_1_21_1
			ClientApi.RENDER_STATE.partialTickTime = renderContext.tickDelta();
			#else
			ClientApi.RENDER_STATE.partialTickTime = renderContext.tickCounter().getGameTimeDeltaTicks();
			#endif
			
			ClientApi.RENDER_STATE.clientLevelWrapper = ClientLevelWrapper.getWrapperIfDifferent(ClientApi.RENDER_STATE.clientLevelWrapper, renderContext.world());
			
			
			
			
			#if MC_VER < MC_1_21_6
			// rendered in MixinLevelRenderer
			#else
			ClientApi.INSTANCE.renderDeferredLodsForShaders();
			#endif
			
			ClientApi.INSTANCE.renderFadeTransparent();
		});
		#endif
		
		#if MC_VER <= MC_1_21_11
		#elif MC_VER <= MC_26_2_0
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register((LevelRenderContext levelRenderContext) ->
		{
			ClientApi.INSTANCE.renderFadeTransparent();
		});
		
		LevelRenderEvents.AFTER_OPAQUE_TERRAIN.register((LevelTerrainRenderContext levelTerrainRenderContext) ->
		{
			ClientApi.INSTANCE.renderFadeOpaque();
		});
		#else
		// fade transparency handled in MixinLevelRenderer
		#endif
		
		//endregion
		
		
		
		//=================//
		// keyboard events //
		//=================//
		//region
		
		// Debug keyboard event
		// FIXME: Use better hooks so it doesn't trigger key press events in text boxes
		ClientTickEvents.END_CLIENT_TICK.register(client -> 
		{
			// TODO
			//if (client.player != null && !(Minecraft.getInstance().screen instanceof TitleScreen))
			//{
			//	this.onKeyInput();
			//}
		});
		
		//endregion
		
		
		
		//==================//
		// networking event //
		//==================//
		//region
		
		#if MC_VER < MC_1_20_6
		ClientPlayNetworking.registerGlobalReceiver(AbstractPluginPacketSender.WRAPPER_PACKET_RESOURCE, (client, handler, buffer, packetSender) ->
		{
			AbstractNetworkMessage message = PACKET_SENDER.decodeMessage(buffer);
			if (message != null)
			{
				ClientApi.INSTANCE.pluginMessageReceived(message);
			}
		});
		#elif MC_VER <= MC_1_21_11
		PayloadTypeRegistry.playS2C().register(CommonPacketPayload.TYPE, new CommonPacketPayload.Codec());
		ClientPlayNetworking.registerGlobalReceiver(CommonPacketPayload.TYPE, (payload, context) ->
		{
			if (payload.message() == null)
			{
				return;
			}
			ClientApi.INSTANCE.pluginMessageReceived(payload.message());
		});
		#else
		PayloadTypeRegistry.clientboundPlay().register(CommonPacketPayload.TYPE, new CommonPacketPayload.Codec());
		ClientPlayNetworking.registerGlobalReceiver(CommonPacketPayload.TYPE, (payload, context) ->
		{
			if (payload.message() == null)
			{
				return;
			}
			ClientApi.INSTANCE.pluginMessageReceived(payload.message());
		});
		#endif
		
		//endregion
		
		
		
	}
	
	public void onKeyInput()
	{
		HashSet<Integer> currentKeyDown = new HashSet<>();
		
		#if MC_VER <= MC_26_2_0
		// Note: Minecraft's InputConstants are the same as GLFW Key values
		for (int keyCode = GLFW.GLFW_KEY_0; keyCode <= GLFW.GLFW_KEY_LAST; keyCode++)
		{
			if (InputConstants.isKeyDown(MC.getGlfwWindowId(), keyCode))
			{
				currentKeyDown.add(keyCode);
			}
		}
		#else
		// Note: Minecraft's InputConstants are NOT the same as SDL Key values
		for (int keyCode = InputConstants.RELEASE; keyCode <= InputConstants.MOD_ALT; keyCode++)
		{
			if (InputConstants.isKeyDown(keyCode))
			{
				currentKeyDown.add(keyCode);
			}
		}
		#endif
		
		// Diff and trigger events
		for (int keyCode : currentKeyDown)
		{
			if (!this.previouslyPressKeyCodes.contains(keyCode))
			{
				ClientApi.INSTANCE.keyPressedEvent(keyCode);
			}
		}
		
		// Update the set
		this.previouslyPressKeyCodes = currentKeyDown;
	}
	
}
