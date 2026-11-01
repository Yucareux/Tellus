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

package com.seibel.distanthorizons.neoforge.mixins.client;

#if MC_VER < MC_1_19_4
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.util.math.DhVec3f;
import net.minecraft.client.renderer.RenderType;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;
import org.lwjgl.opengl.GL33;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
#elif MC_VER < MC_1_21_6
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import net.minecraft.client.renderer.RenderType;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
#elif MC_VER < MC_1_21_9
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
#elif MC_VER <= MC_1_21_11
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
#elif MC_VER <= MC_26_2_0
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.client.renderer.state.level.CameraRenderState;
#elif MC_VER <= MC_26_3_0
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
#endif


import com.mojang.blaze3d.systems.RenderSystem;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper;
import com.seibel.distanthorizons.common.wrappers.McObjectConverter;
import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

import com.seibel.distanthorizons.core.logging.DhLogger;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;



@Mixin(LevelRenderer.class)
public class MixinLevelRenderer
{
	@Unique
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	#if MC_VER <= MC_26_1_2
	@Shadow #if MC_VER >= MC_1_20_4 (remap = false) #endif
	private ClientLevel level;
	#endif
	
	
	
	//===========//
	// Pre MC 26 //
	//===========//
	//region
	#if MC_VER <= MC_1_21_11
	
	#if MC_VER < MC_1_21_6
	@Inject(at = @At("HEAD"), method = "renderSectionLayer")
	private void renderChunkLayer(RenderType renderType, double x, double y, double z, Matrix4f modelViewMatrix, Matrix4f projectionMatrix, CallbackInfo callback)
	#elif MC_VER < MC_1_21_9
	@Inject(at = @At("HEAD"), method = "renderLevel")
	private void onRenderLevel(
			GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker, 
			boolean renderBlockOutline, Camera camera, 
			Matrix4f positionMatrix, Matrix4f projectionMatrix, GpuBufferSlice gpuBufferSlice, 
			Vector4f skyColor, boolean thinFog, CallbackInfo callback)
	#else
	@Inject(at = @At("HEAD"), method = "renderLevel")
	private void renderLevel(
			GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker, 
			boolean renderBlockOutline, Camera camera, 
			Matrix4f positionMatrix, Matrix4f projectionMatrix, Matrix4f idkMatrix, GpuBufferSlice gpuBufferSlice, 
			Vector4f skyColor, boolean thinFog, CallbackInfo callback)
    #endif
	{
		#if MC_VER < MC_1_21_6
		// MC combined the model view and projection matricies
		ClientApi.RENDER_STATE.mcModelViewMatrix = McObjectConverter.convert(modelViewMatrix);
		ClientApi.RENDER_STATE.mcProjectionMatrix = McObjectConverter.convert(projectionMatrix);
		#else
		ClientApi.RENDER_STATE.mcProjectionMatrix = McObjectConverter.convert(projectionMatrix);
		#endif
		
		
		
		ClientApi.RENDER_STATE.partialTickTime = MinecraftRenderWrapper.INSTANCE.getPartialTickTime();
		ClientApi.RENDER_STATE.clientLevelWrapper = ClientLevelWrapper.getWrapperIfDifferent(ClientApi.RENDER_STATE.clientLevelWrapper, this.level);
		
		
		#if MC_VER < MC_1_21_6
		
		// only crash during development
		if (ModInfo.IS_DEV_BUILD)
		{
			ClientApi.RENDER_STATE.canRenderOrThrow();
		}
		
		// render LODs
		if (renderType.equals(RenderType.solid()))
		{
			ClientApi.INSTANCE.renderLods();
		} 
		else if (renderType.equals(RenderType.translucent())) 
		{
			ClientApi.INSTANCE.renderDeferredLodsForShaders();
		}
		
		// render fade
		// fade rendering needs to happen AFTER_ENTITIES and AFTER_TRANSLUCENT respectively (fabric names)
		// however since this method intjects at the beginning of the rendertype,
		// we need to trigger for the renderType after those passes are done
		if (renderType.equals(RenderType.cutout()))
		{
			ClientApi.INSTANCE.renderFadeOpaque();
		}
		else if (renderType.equals(RenderType.tripwire()))
		{
			ClientApi.INSTANCE.renderFadeTransparent();
		}
		#endif
	}
	
	
	#if MC_VER < MC_1_21_6
	
	// formerly handled in renderChunkLayer()
	
	#else
	@Inject(at = @At("HEAD"), method = "prepareChunkRenders")
	private void renderChunkLayer(Matrix4fc modelViewMatrix, double d, double e, double f, CallbackInfoReturnable<ChunkSectionsToRender> callback)
	{
		ClientApi.RENDER_STATE.mcModelViewMatrix = McObjectConverter.convert(modelViewMatrix);
		ClientApi.RENDER_STATE.clientLevelWrapper = ClientLevelWrapper.getWrapperIfDifferent(ClientApi.RENDER_STATE.clientLevelWrapper, this.level);
		
		// only crash during development
		if (ModInfo.IS_DEV_BUILD)
		{
			ClientApi.RENDER_STATE.canRenderOrThrow();
		}
		
		ClientApi.INSTANCE.renderLods();
	}
	
	#endif
	#endif
	//endregion
	
	
	
	//===========//
	// MC 26.1.2 //
	//===========//
	//region
	
	#if MC_VER <= MC_1_21_11
	// see code above
	#elif MC_VER <= MC_26_1_2
	@Inject(at = @At("HEAD"), method = "prepareChunkRenders")
	private void prepareChunkRenders(final Matrix4fc modelViewMatrix, CallbackInfoReturnable<ChunkSectionsToRender> callback)
	{
		ClientApi.RENDER_STATE.clientLevelWrapper = ClientLevelWrapper.getWrapperIfDifferent(ClientApi.RENDER_STATE.clientLevelWrapper, this.level);
	}
	
	
	@Inject(at = @At("HEAD"), method = "renderLevel")
	public void renderLevel(
		final GraphicsResourceAllocator resourceAllocator, final DeltaTracker deltaTracker,
		final boolean renderBlockOutline, final CameraRenderState camera,
		final Matrix4fc modelViewMatrix, final GpuBufferSlice terrainFog,
		final Vector4f fogColor, final boolean shouldRenderSky,
		final ChunkSectionsToRender chunkSectionsToRender,
		CallbackInfo callback)
	{
		ClientApi.RENDER_STATE.mcModelViewMatrix = McObjectConverter.convert(modelViewMatrix);
		
		ClientApi.RENDER_STATE.partialTickTime = MinecraftRenderWrapper.INSTANCE.getPartialTickTime();
		
	}
	
	#endif
	
	//endregion
	
	
	
	//==============//
	// MC 26.3 plus //
	//==============//
	//region
	
	#if MC_VER <= MC_26_2_0
	// handled in game renderer
	#else
	
	@Inject(at = @At("HEAD"), method = "prepareTranslucents")
	private void prepareTranslucents(
		CallbackInfo callback)
	
	{
		ClientApi.RENDER_STATE.mcModelViewMatrix = McObjectConverter.convert(RenderSystem.getModelViewStack());
		
		ClientApi.RENDER_STATE.canRenderOrThrow();
		ClientApi.INSTANCE.renderLods();
	}
	
	@Inject(at = @At("HEAD"), method = "executeOutline")
	private void executeOutline(
		final FeatureRenderDispatcher.PreparedFrame featureFrame,
		CallbackInfo callback)
	{
		ClientApi.INSTANCE.renderDeferredLodsForShaders();
		ClientApi.INSTANCE.renderFadeOpaque();
	}
	
	// improved transparency is necessary since the normal transparent pass is in the same renderPass
	// preventing us from adding our own render pass inbetween
	@Inject(at = @At("HEAD"), method = "executeOit")
	private void executeOit(
		final ChunkSectionsToRender chunkSectionsToRender, final FeatureRenderDispatcher.PreparedFrame featureFrame,
		CallbackInfo callback)
	{ ClientApi.INSTANCE.renderFadeTransparent(); }
	
	#endif
	
	//endregion
	
	
	
	
}
