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

package com.seibel.distanthorizons.fabric.mixins.client;

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
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;
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
	#if MC_VER <= MC_26_1_2
    @Shadow
    private ClientLevel level;
    #else
	#endif
	
	@Unique
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	
	
	//===========//
	// Pre MC 26 //
	//===========//
	//region
	#if MC_VER <= MC_1_21_11
	
	#if MC_VER < MC_1_17_1
    @Inject(at = @At("HEAD"),
			method = "renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDD)V",
			cancellable = true)
	private void renderChunkLayer(RenderType renderType, PoseStack matrixStackIn, double xIn, double yIn, double zIn, CallbackInfo callback)
	#elif MC_VER < MC_1_19_4
    @Inject(at = @At("HEAD"),
            method = "renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLcom/mojang/math/Matrix4f;)V",
            cancellable = true)
    private void renderChunkLayer(RenderType renderType, PoseStack modelViewMatrixStack, double cameraXBlockPos, double cameraYBlockPos, double cameraZBlockPos, Matrix4f projectionMatrix, CallbackInfo callback)
	#elif MC_VER < MC_1_20_2
    @Inject(at = @At("HEAD"),
            method = "renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
            cancellable = true)
    private void renderChunkLayer(RenderType renderType, PoseStack modelViewMatrixStack, double cameraXBlockPos, double cameraYBlockPos, double cameraZBlockPos, Matrix4f projectionMatrix, CallbackInfo callback)
    #elif MC_VER < MC_1_20_6
	@Inject(at = @At("HEAD"),
			method = "Lnet/minecraft/client/renderer/LevelRenderer;renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
			cancellable = true)
	private void renderChunkLayer(RenderType renderType, PoseStack modelViewMatrixStack, double camX, double camY, double camZ, Matrix4f projectionMatrix, CallbackInfo callback)
	#elif MC_VER < MC_1_21_6
	@Inject(at = @At("HEAD"),
			method = "Lnet/minecraft/client/renderer/LevelRenderer;renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
			cancellable = true)
	private void renderChunkLayer(RenderType renderType, double x, double y, double z, Matrix4f projectionMatrix, Matrix4f frustumMatrix, CallbackInfo callback)
	#elif MC_VER < MC_1_21_9
	@Inject(at = @At("HEAD"), method = "prepareChunkRenders", cancellable = true)
	private void prepareChunkRenders(Matrix4fc projectionMatrix, double d, double e, double f, CallbackInfoReturnable<ChunkSectionsToRender> callback)
	#else
	@Inject(at = @At("HEAD"), method = "renderLevel")
	private void renderLevel(
			GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker,
			boolean renderBlockOutline, Camera camera,
			Matrix4f positionMatrix, Matrix4f projectionMatrix, Matrix4f idkMatrix, GpuBufferSlice gpuBufferSlice,
			Vector4f skyColor, boolean thinFog, CallbackInfo callback)
    #endif
    {
		#if MC_VER <= MC_1_16_5
	    // get the matrices from the OpenGL fixed pipeline
	    float[] mcProjMatrixRaw = new float[16];
	    GL33.glGetFloatv(GL33.GL_PROJECTION_MATRIX, mcProjMatrixRaw);
	    ClientApi.RENDER_STATE.mcProjectionMatrix = new DhMat4f(mcProjMatrixRaw);
	    ClientApi.RENDER_STATE.mcProjectionMatrix.transpose();
	    
	    ClientApi.RENDER_STATE.mcModelViewMatrix = McObjectConverter.convert(matrixStackIn.last().pose());
		
		#elif MC_VER <= MC_1_20_4
		// get the matrices directly from MC
		ClientApi.RENDER_STATE.mcModelViewMatrix = McObjectConverter.convert(modelViewMatrixStack.last().pose());
		ClientApi.RENDER_STATE.mcProjectionMatrix = McObjectConverter.convert(projectionMatrix);
		#elif MC_VER < MC_1_21_9
		// MC combined the model view and projection matricies
	    ClientApi.RENDER_STATE.mcModelViewMatrix = McObjectConverter.convert(projectionMatrix);
	    ClientApi.RENDER_STATE.mcProjectionMatrix = new DhMat4f();
	    ClientApi.RENDER_STATE.mcProjectionMatrix.setIdentity();
		#else
		ClientApi.RENDER_STATE.mcModelViewMatrix = McObjectConverter.convert(positionMatrix);
		ClientApi.RENDER_STATE.mcProjectionMatrix = McObjectConverter.convert(projectionMatrix);
		#endif
		
		ClientApi.RENDER_STATE.partialTickTime = MinecraftRenderWrapper.INSTANCE.getPartialTickTime();
		ClientApi.RENDER_STATE.clientLevelWrapper = ClientLevelWrapper.getWrapperIfDifferent(ClientApi.RENDER_STATE.clientLevelWrapper, this.level);
	    
	    #if MC_VER < MC_1_21_6
	    if (renderType.equals(RenderType.translucent())) 
		{
		    ClientApi.INSTANCE.renderDeferredLodsForShaders();
	    }
		#elif MC_VER < MC_1_21_9
	    // rendering handled via Fabric Api render event
		#else
		// handled here and in MixinChunkSectionsToRender
	    #endif
    }
	
	
	
	#if MC_VER < MC_1_21_9
	// rendering handled via Fabric Api render event
	#else
	@Inject(at = @At("HEAD"), method = "prepareChunkRenders")
	private void prepareChunkRenders(Matrix4fc modelViewMatrix, double d, double e, double f, CallbackInfoReturnable<ChunkSectionsToRender> callback)
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
	
	@Unique
	private static boolean firstTimeSetupComplete = false;
	@Unique
	private static IIrisAccessor irisAccessor;
	
	
	@Inject(at = @At("HEAD"), method = "prepareTranslucents")
	private void prepareTranslucents(
		CallbackInfo callback)
	
	{
		if (!firstTimeSetupComplete)
		{
			irisAccessor = ModAccessorInjector.INSTANCE.get(IIrisAccessor.class);
			firstTimeSetupComplete = true;
		}
		
		
		
		ClientApi.RENDER_STATE.mcModelViewMatrix = McObjectConverter.convert(RenderSystem.getModelViewStack());
		
		// if shaders are active then we need to render in Sodium's render pass
		// if blaze3D is active we need to render here
		if (irisAccessor != null
			&& irisAccessor.isShaderPackInUse())
		{
			return;
		}
		
		ClientApi.RENDER_STATE.canRenderOrThrow();
		ClientApi.INSTANCE.renderLods();
	}
	
	@Inject(at = @At("HEAD"), method = "executeOutline")
	private void executeOutline(
		final FeatureRenderDispatcher.PreparedFrame featureFrame,
		CallbackInfo callback)
	{
		if (irisAccessor != null
			&& irisAccessor.isShaderPackInUse())
		{
			return;
		}
		
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
