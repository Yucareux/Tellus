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

package com.seibel.distanthorizons.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi;
import com.seibel.distanthorizons.common.AbstractModInitializer;
import com.seibel.distanthorizons.common.util.ProxyUtil;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper;
import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;

import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import net.minecraft.world.level.LevelAccessor;

import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.minecraft.world.level.chunk.ChunkAccess;

import com.seibel.distanthorizons.core.logging.DhLogger;

import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.bus.api.SubscribeEvent;
import org.lwjgl.opengl.GL33;

import java.util.concurrent.AbstractExecutorService;

#if MC_VER <= MC_26_2_0
import org.lwjgl.glfw.GLFW;
#else
#endif

public class NeoforgeClientProxy implements AbstractModInitializer.IEventProxy
{
	private static final IMinecraftClientWrapper MC = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private AbstractDhRenderApiDefinition renderDef = null;
	/** delayed get due to this class being created before the {@link AbstractDhRenderApiDefinition} is bound */
	private AbstractDhRenderApiDefinition getRenderDef()
	{
		if (this.renderDef == null)
		{
			this.renderDef = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
		}
		
		return this.renderDef;
	}
	
	
	
	@Override
	public void registerEvents() { NeoForge.EVENT_BUS.register(this); }
	
	
	
	//==============//
	// chunk events //
	//==============//
	//region
	
	@SubscribeEvent
	public void rightClickBlockEvent(PlayerInteractEvent.RightClickBlock event)
	{
		if (MC.clientConnectedToDedicatedServer())
		{
			LevelAccessor level = event.getLevel();
			ILevelWrapper wrappedLevel = ProxyUtil.getLevelWrapper(level);
			
			if (SharedApi.isChunkAtBlockPosAlreadyUpdating(wrappedLevel, event.getPos().getX(), event.getPos().getZ()))
			{
				return;
			}
			
			// executor to prevent locking up the render/event thread
			AbstractExecutorService executor = ThreadPoolUtil.getFileHandlerExecutor();
			if (executor != null)
			{
				executor.execute(() ->
				{
					//LOGGER.trace("interact or block place event at blockPos: " + event.getPos());
					
					ChunkAccess chunk = level.getChunk(event.getPos());
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
			LevelAccessor level = event.getLevel();
			ILevelWrapper wrappedLevel = ProxyUtil.getLevelWrapper(level);
			
			if (SharedApi.isChunkAtBlockPosAlreadyUpdating(wrappedLevel, event.getPos().getX(), event.getPos().getZ()))
			{
				return;
			}
			
			// executor to prevent locking up the render/event thread
			AbstractExecutorService executor = ThreadPoolUtil.getFileHandlerExecutor();
			if (executor != null)
			{
				executor.execute(() ->
				{
					//LOGGER.trace("break or block attack at blockPos: " + event.getPos());
					
					ChunkAccess chunk = level.getChunk(event.getPos());
					this.onBlockChangeEvent(level, chunk);
				});
			}
		}
	}
	private void onBlockChangeEvent(LevelAccessor level, ChunkAccess chunk)
	{
		ILevelWrapper wrappedLevel = ProxyUtil.getLevelWrapper(level);
		SharedApi.INSTANCE.applyChunkUpdate(
			new ChunkWrapper(chunk, wrappedLevel), 
			wrappedLevel, 
			true
		);
	}
	
	//endregion
	
	
	
	//==============//
	// key bindings //
	//==============//
	//region
	
	@SubscribeEvent
	public void registerKeyBindings(InputEvent.Key event)
	{
		if (Minecraft.getInstance().player == null)
		{
			return;
		}
		
		#if MC_VER <= MC_26_2_0
		if (event.getAction() != GLFW.GLFW_PRESS)
		{
			return;
		}
		#else
		if (event.getAction() != InputConstants.PRESS)
		{
			return;
		}
		#endif
		
		ClientApi.INSTANCE.keyPressedEvent(event.getKey());
	}
	
	//endregion
	
	
	
	//===========//
	// rendering //
	//===========//
	//region
	
	#if MC_VER < MC_1_21_6
	@SubscribeEvent
	public void afterLevelRenderEvent(RenderLevelStageEvent event)
	{
		if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL)
		{
			try
			{
				// should generally only need to be set once per game session
				// allows DH to render directly to Optifine's level frame buffer,
				// allowing better shader support
				MinecraftRenderWrapper.INSTANCE.finalLevelFrameBufferId = GL33.glGetInteger(GL33.GL_FRAMEBUFFER_BINDING);
			}
			catch (Exception | Error e)
			{
				LOGGER.error("Unexpected error in afterLevelRenderEvent: "+e.getMessage(), e);
			}
		}
	}
	#else
	
	
	#if MC_VER <= MC_1_21_11
	@SubscribeEvent
	public void afterLevelEntityRenderEvent(RenderLevelStageEvent.AfterEntities event)
	#else
	@SubscribeEvent
	public void afterLevelEntityRenderEvent(RenderLevelStageEvent.AfterOpaqueFeatures event)
	#endif
	{
		#if MC_VER < MC_1_21_9
		ClientApi.RENDER_STATE.clientLevelWrapper = ClientLevelWrapper.getWrapperIfDifferent(ClientApi.RENDER_STATE.clientLevelWrapper, (ClientLevel)event.getLevel());
		#elif MC_VER <= MC_1_21_11
		ClientApi.RENDER_STATE.clientLevelWrapper = ClientLevelWrapper.getWrapperIfDifferent(ClientApi.RENDER_STATE.clientLevelWrapper, event.getLevelRenderer().level);
		#else
		// handled via the same mixin as fabric for consistency
		#endif
		
		#if MC_VER <= MC_26_2_0
		ClientApi.INSTANCE.renderFadeOpaque();
		#else
		// needs to be handled via a mixin to allow for Blaze3D render passes to function correctly
		// handled in MixinLevelRenderer
		#endif
	}
	
	
	@SubscribeEvent
	public void afterLevelTranslucentRenderEvent(RenderLevelStageEvent.AfterTranslucentBlocks event)
	{
		#if MC_VER < MC_1_21_9
		ClientApi.RENDER_STATE.clientLevelWrapper = ClientLevelWrapper.getWrapperIfDifferent(ClientApi.RENDER_STATE.clientLevelWrapper, (ClientLevel)event.getLevel());
		#elif MC_VER <= MC_1_21_11
		ClientApi.RENDER_STATE.clientLevelWrapper = ClientLevelWrapper.getWrapperIfDifferent(ClientApi.RENDER_STATE.clientLevelWrapper, event.getLevelRenderer().level);
		#else
		// handled via the same mixin as fabric for consistency
		#endif
	}
	
	@SubscribeEvent
	public void afterLevelRenderEvent(RenderLevelStageEvent.AfterLevel event)
	{
		#if MC_VER < MC_1_21_9
		ClientApi.RENDER_STATE.clientLevelWrapper = ClientLevelWrapper.getWrapperIfDifferent(ClientApi.RENDER_STATE.clientLevelWrapper, (ClientLevel)event.getLevel());
		#elif MC_VER <= MC_1_21_11
		ClientApi.RENDER_STATE.clientLevelWrapper = ClientLevelWrapper.getWrapperIfDifferent(ClientApi.RENDER_STATE.clientLevelWrapper, event.getLevelRenderer().level);
		#else
		// handled via the same mixin as fabric for consistency
		#endif
		
		try
		{
			if (this.getRenderDef().getRenderApi() == EDhApiRenderingApi.OPEN_GL)
			{
				// should generally only need to be set once per game session
				// allows DH to render directly to Optifine's level frame buffer,
				// allowing better shader support
				MinecraftRenderWrapper.INSTANCE.finalLevelFrameBufferId = GL33.glGetInteger(GL33.GL_FRAMEBUFFER_BINDING);
			}
		}
		catch (Exception | Error e)
		{
			LOGGER.error("Unexpected error in afterLevelRenderEvent: "+e.getMessage(), e);
		}
		
		
		
		#if MC_VER <= MC_26_2_0
		ClientApi.INSTANCE.renderFadeTransparent();
		#else
		// needs to be handled via a mixin to allow for Blaze3D render passes to function correctly
		// handled in MixinLevelRenderer
		#endif
	}
	
	#endif
	
	//endregion
	
	
	
}
