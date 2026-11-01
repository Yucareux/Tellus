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

package com.seibel.distanthorizons.common.render.blaze.postProcessing;

#if MC_VER <= MC_1_21_10
public class BlazeDhSsaoRenderer {}

#else

import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.common.render.blaze.BlazeDhMetaRenderer;
import com.seibel.distanthorizons.common.render.blaze.apply.BlazeDhApplyRenderer;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPassWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPipelineBuilderWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.texture.BlazeTextureWrapper;
import com.seibel.distanthorizons.common.render.blaze.util.BlazePostProcessUtil;
import com.seibel.distanthorizons.common.render.blaze.wrappers.uniform.BlazeUniformBufferWrapper;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.RenderUtil;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhSsaoRenderer;

#if MC_VER <= MC_26_2_0
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
#else
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
#endif

#if MC_VER <= MC_26_1_2
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
#elif MC_VER <= MC_26_2_0
import com.mojang.blaze3d.platform.BlendFactor;
#else
import com.mojang.renderpearl.api.pipeline.BlendFactor;
#endif

/** Renders SSAO to the DH LODs. */
public class BlazeDhSsaoRenderer implements IDhSsaoRenderer
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build(); 
	
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	private static final AbstractDhRenderApiDefinition RENDER_DEF = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
	
	private static final GpuDevice GPU_DEVICE = RenderSystem.getDevice();
	private static final CommandEncoder COMMAND_ENCODER = GPU_DEVICE.createCommandEncoder();
	
	public static final BlazeDhSsaoRenderer INSTANCE = new BlazeDhSsaoRenderer();
	
	
	private BlazeDhApplyRenderer applyRenderer;
	
	private RenderPipeline pipeline;
	private boolean init = false;
	
	private final BlazeUniformBufferWrapper fragUniformBufferWrapper = new BlazeUniformBufferWrapper("fragUniformBlock");
	private final BlazeUniformBufferWrapper applyFragUniformBufferWrapper = new BlazeUniformBufferWrapper("applyFragUniformBlock");
	
	private GpuBuffer vboGpuBuffer;
	
	private final BlazeTextureWrapper ssaoColorTextureWrapper = BlazeTextureWrapper.createColor("dh_ssao_color_texture");
	/** We don't want to actually write any depth data, but blaze3D complains if we don't bind a depth texture. */
	private final BlazeTextureWrapper ssaoDepthTextureWrapper = BlazeTextureWrapper.createDepth("dh_ssao_depth_texture");
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private BlazeDhSsaoRenderer() { }
	
	private void tryInit()
	{
		if (this.init)
		{
			return;
		}
		this.init = true;
		
		
		
		BlendFunction blendFunc;
		#if MC_VER <= MC_26_1_2
		blendFunc = new BlendFunction(SourceFactor.ZERO, DestFactor.SRC_ALPHA, SourceFactor.ZERO, DestFactor.ONE);
		#else
		blendFunc = new BlendFunction(BlendFactor.ZERO, BlendFactor.SRC_ALPHA, BlendFactor.ZERO, BlendFactor.ONE);
		#endif
		
		this.applyRenderer = new BlazeDhApplyRenderer(
			"ssao_apply_to_dh",
			blendFunc,
			"apply/blaze/vert", "ssao/blaze/apply",
			/*uniforms*/ new String[] { "applyFragUniformBlock" }
		);
		
		RenderPipelineBuilderWrapper pipelineBuilder = new RenderPipelineBuilderWrapper();
		{
			pipelineBuilder.withFaceCulling(false);
			pipelineBuilder.withDepthWrite(false);
			pipelineBuilder.withDepthTest(RenderPipelineBuilderWrapper.EDhDepthTest.NONE);
			pipelineBuilder.withColorWrite(true);
			pipelineBuilder.withoutBlend();
			pipelineBuilder.withPolygonMode(RenderPipelineBuilderWrapper.EDhPolygonMode.FILL);
			pipelineBuilder.withName("ssao_render");
			
			pipelineBuilder.withVertexShader("ssao/blaze/vert");
			pipelineBuilder.withFragmentShader("ssao/blaze/frag");
			
			pipelineBuilder.withSampler("uDhDepthTexture");
			
			pipelineBuilder.withUniformBuffer("fragUniformBlock");
			
			pipelineBuilder.withVertexFormat(BlazePostProcessUtil.createVertexFormat());
			pipelineBuilder.withVertexMode(RenderPipelineBuilderWrapper.EDhVertexMode.TRIANGLE_FAN);
		}
		this.pipeline = pipelineBuilder.build();
		
		
		this.vboGpuBuffer = BlazePostProcessUtil.createAndUploadScreenVertexData("DhSsao");
	}
	
	//endregion
	
	
	
	//========//
	// render //
	//========//
	//region
	
	@Override
	public void render(RenderParams renderParams)
	{
		this.tryInit();
		
		// shouldn't happen, but just in case
		if (BlazeDhMetaRenderer.INSTANCE.dhDepthTextureWrapper.isEmpty()
			|| BlazeDhMetaRenderer.INSTANCE.dhColorTextureWrapper.isEmpty())
		{
			return;	
		}
		
		
		
		// textures
		this.ssaoColorTextureWrapper.tryCreateOrResizeToScreenSize();
		this.ssaoDepthTextureWrapper.tryCreateOrResizeToScreenSize();
		
		// frag uniforms
		{
			// create data //
			DhMat4f projMatrix = new DhMat4f(renderParams.dhProjectionMatrix);
			DhMat4f invertedProjMatrix = new DhMat4f(renderParams.dhProjectionMatrix);
			invertedProjMatrix.invert();
			
			
			// upload data //
			this.fragUniformBufferWrapper
				.putInt(6) // uSampleCount
				
				.putFloat(4.0f) // uRadius
				.putFloat(0.2f) // uStrength
				.putFloat(0.25f) // uMinLight
				.putFloat(0.02f) // uBias
				.putFloat(1_600.0f) // uFadeDistanceInBlocks
				
				.putMat4f(invertedProjMatrix)
				.putMat4f(projMatrix)
				
				.putInt((RENDER_DEF.getDepthDirection() == EDhApiDepthDirection.REVERSE_Z) ? 1 : 0) // uIsReverseZDepth
				.putInt((RENDER_DEF.getDepthRange() == EDhApiDepthRange.ZERO_TO_POS_ONE) ? 1 : 0) // uDepthIsZeroToPositiveOne
				.finishAndUpload()
			;
		}
		
		// apply frag uniforms
		{
			// create data //
			
			float viewWidth = (float)MC_RENDER.getTargetFramebufferViewportWidth();
			float viewHeight = (float)MC_RENDER.getTargetFramebufferViewportHeight();
			
			float nearClipPlane = RenderUtil.getNearClipPlaneInBlocks();
			float farClipPlane = RenderUtil.getFarClipPlaneDistanceInBlocks();
			
			
			// upload data //
			this.applyFragUniformBufferWrapper
				.putVec2f(viewWidth, viewHeight) // uViewSize
				.putInt(2) // uBlurRadius
				.putFloat(nearClipPlane) // uNearClipPlane
				.putFloat(farClipPlane) // uFarClipPlane
				.putInt((RENDER_DEF.getDepthDirection() == EDhApiDepthDirection.REVERSE_Z) ? 1 : 0) // uIsReverseZDepth
				.finishAndUpload()
			;
		}
		
		
		this.renderSsaoToTexture();
		
		this.applyRenderer.setUniform("applyFragUniformBlock", this.applyFragUniformBufferWrapper);
		this.applyRenderer.render(this.ssaoColorTextureWrapper.getTexture(), BlazeDhMetaRenderer.INSTANCE.dhDepthTextureWrapper.getTexture(), BlazeDhMetaRenderer.INSTANCE.dhColorTextureWrapper.getTexture());
		
	}
	
	private void renderSsaoToTexture()
	{
		try (RenderPassWrapper renderPassWrapper = new RenderPassWrapper(
			this::getRenderPassName,
			this.ssaoColorTextureWrapper,
			this.ssaoDepthTextureWrapper))
		{
			renderPassWrapper.bindTexture("uDhDepthTexture", BlazeDhMetaRenderer.INSTANCE.dhDepthTextureWrapper);
			
			renderPassWrapper.setUniform("fragUniformBlock", this.fragUniformBufferWrapper);
			
			renderPassWrapper.setVertexBuffer(this.vboGpuBuffer);
			
			renderPassWrapper.setPipeline(this.pipeline);
			renderPassWrapper.draw(4);
		}
	}
	private String getRenderPassName() { return "distantHorizons:SsaoRenderer"; }
	
	
	//endregion
	
	
	
}
#endif