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
public class BlazeVanillaFadeRenderer {}

#else

#if MC_VER <= MC_26_2_0
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
#else
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
#endif

import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.common.render.blaze.BlazeDhMetaRenderer;
import com.seibel.distanthorizons.common.render.blaze.apply.BlazeDhCopyRenderer;
import com.seibel.distanthorizons.common.render.blaze.util.BlazePostProcessUtil;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPassWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPipelineBuilderWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.texture.BlazeTextureViewWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.texture.BlazeTextureWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.uniform.BlazeUniformBufferWrapper;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.RenderUtil;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhVanillaFadeRenderer;

/**
 * Fades the vanilla chunks
 * into DH's LODs.
 */
public class BlazeVanillaFadeRenderer implements IDhVanillaFadeRenderer
{
	public static final DhLogger LOGGER = new DhLoggerBuilder().build(); 
	
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	private static final AbstractDhRenderApiDefinition RENDER_DEF = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
	
	private static final GpuDevice GPU_DEVICE = RenderSystem.getDevice();
	private static final CommandEncoder COMMAND_ENCODER = GPU_DEVICE.createCommandEncoder();
	
	public static final BlazeVanillaFadeRenderer INSTANCE = new BlazeVanillaFadeRenderer();
	
	
	private RenderPipeline pipeline;
	private boolean init = false;
	
	private final BlazeUniformBufferWrapper fragUniformBufferWrapper = new BlazeUniformBufferWrapper("fragUniformBlock");
	
	private GpuBuffer vboGpuBuffer;
	
	public final BlazeTextureWrapper fadeColorTextureWrapper = BlazeTextureWrapper.createColor("DhVanillaFadeColorTexture");
	/** We don't want to actually write any depth data, but blaze3D complains if we don't bind a depth texture. */
	private final BlazeTextureWrapper fadeDepthTextureWrapper = BlazeTextureWrapper.createDepth("DhVanillaFadeDepthTexture");
	
	
	public final BlazeTextureViewWrapper mcDepthTextureWrapper = new BlazeTextureViewWrapper();
	public final BlazeTextureViewWrapper mcColorTextureWrapper = new BlazeTextureViewWrapper();
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private BlazeVanillaFadeRenderer() { }
	
	private void tryInit()
	{
		if (this.init)
		{
			return;
		}
		this.init = true;
		
		
		
		RenderPipelineBuilderWrapper pipelineBuilder = new RenderPipelineBuilderWrapper();
		{
			pipelineBuilder.withFaceCulling(false);
			pipelineBuilder.withDepthWrite(false);
			pipelineBuilder.withDepthTest(RenderPipelineBuilderWrapper.EDhDepthTest.NONE);
			pipelineBuilder.withColorWrite(true);
			pipelineBuilder.withoutBlend();
			pipelineBuilder.withPolygonMode(RenderPipelineBuilderWrapper.EDhPolygonMode.FILL);
			pipelineBuilder.withName("vanilla_fade");
			
			pipelineBuilder.withVertexShader("fade/blaze/vert");
			pipelineBuilder.withFragmentShader("fade/blaze/vanilla_fade");
			
			pipelineBuilder.withSampler("uMcDepthTexture");
			pipelineBuilder.withSampler("uCombinedMcDhColorTexture");
			
			pipelineBuilder.withSampler("uDhDepthTexture");
			pipelineBuilder.withSampler("uDhColorTexture");
			
			pipelineBuilder.withUniformBuffer("fragUniformBlock");
			
			pipelineBuilder.withVertexFormat(BlazePostProcessUtil.createVertexFormat());
			pipelineBuilder.withVertexMode(RenderPipelineBuilderWrapper.EDhVertexMode.TRIANGLE_FAN);
		}
		this.pipeline = pipelineBuilder.build();
		
		
		this.vboGpuBuffer = BlazePostProcessUtil.createAndUploadScreenVertexData("VanillaFadeRenderer");
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
		
		if (BlazeDhMetaRenderer.INSTANCE.dhDepthTextureWrapper.isEmpty()
			|| BlazeDhMetaRenderer.INSTANCE.dhColorTextureWrapper.isEmpty())
		{
			return;	
		}
		
		
		// textures
		this.fadeColorTextureWrapper.tryCreateOrResizeToScreenSize();
		this.fadeDepthTextureWrapper.tryCreateOrResizeToScreenSize();
		
		this.mcDepthTextureWrapper.tryWrap(MinecraftRenderWrapper.INSTANCE.getRenderTarget().getDepthTexture());
		this.mcColorTextureWrapper.tryWrap(MinecraftRenderWrapper.INSTANCE.getRenderTarget().getColorTexture());
		
		
		{
			// create data //
			
			float dhNearClipDistance = RenderUtil.getNearClipPlaneInBlocks();
			// this added value prevents the near clip plane and discard circle from touching, which looks bad
			dhNearClipDistance += 16f;
			
			// measured in blocks
			// these multipliers in James' tests should provide a fairly smooth transition
			// without having underdraw issues
			float fadeStartDistance = dhNearClipDistance * 1.5f;
			float fadeEndDistance = dhNearClipDistance * 1.9f;
			
			
			DhMat4f inverseMcModelViewProjectionMatrix = new DhMat4f(renderParams.mcProjectionMatrix);
			inverseMcModelViewProjectionMatrix.multiply(renderParams.mcModelViewMatrix);
			inverseMcModelViewProjectionMatrix.invert();
			DhMat4f inverseMcMvmProjMatrix = inverseMcModelViewProjectionMatrix;
			
			
			DhMat4f inverseDhModelViewProjectionMatrix = new DhMat4f(renderParams.dhProjectionMatrix);
			inverseDhModelViewProjectionMatrix.multiply(renderParams.dhModelViewMatrix);
			inverseDhModelViewProjectionMatrix.invert();
			DhMat4f inverseDhMvmProjMatrix = inverseDhModelViewProjectionMatrix;
			
			
			
			// upload data //
			this.fragUniformBufferWrapper
				.putInt(Config.Client.Advanced.Debugging.lodOnlyMode.get() ? 1 : 0) // uOnlyRenderLods
				.putFloat(fadeStartDistance) // uStartFadeBlockDistance
				.putFloat(fadeEndDistance) // uEndFadeBlockDistance
				.putFloat(renderParams.clientLevelWrapper.getMaxHeight()) // uMaxLevelHeight
				.putMat4f(inverseDhMvmProjMatrix) // uDhInvMvmProj
				.putMat4f(inverseMcMvmProjMatrix) // uMcInvMvmProj
				.putInt((MC_RENDER.getMcDepthDirection() == EDhApiDepthDirection.REVERSE_Z) ? 1 : 0) // uIsMcReverseZDepth
				.putInt((RENDER_DEF.getDepthRange() == EDhApiDepthRange.ZERO_TO_POS_ONE) ? 1 : 0) // uDepthIsZeroToPositiveOne
				.finishAndUpload()
			;
		}
		
		
		this.renderFadeToTexture();
		BlazeDhCopyRenderer.INSTANCE.render(this.fadeColorTextureWrapper, this.mcColorTextureWrapper);
		
	}
	
	private void renderFadeToTexture()
	{
		try (RenderPassWrapper renderPassWrapper = new RenderPassWrapper(
			this::getRenderPassName,
			this.fadeColorTextureWrapper,
			this.fadeDepthTextureWrapper))
		{
			renderPassWrapper.bindTexture("uMcDepthTexture", this.mcDepthTextureWrapper);
			renderPassWrapper.bindTexture("uCombinedMcDhColorTexture", this.mcColorTextureWrapper);
			
			renderPassWrapper.bindTexture("uDhDepthTexture", BlazeDhMetaRenderer.INSTANCE.dhDepthTextureWrapper);
			renderPassWrapper.bindTexture("uDhColorTexture", BlazeDhMetaRenderer.INSTANCE.dhColorTextureWrapper);
			
			renderPassWrapper.setUniform("fragUniformBlock", this.fragUniformBufferWrapper);
			
			renderPassWrapper.setVertexBuffer(this.vboGpuBuffer);
			
			renderPassWrapper.setPipeline(this.pipeline);
			renderPassWrapper.draw(/*indexCount*/ 4);
		}
	}
	private String getRenderPassName() { return "distantHorizons:VanillaFadeRenderer"; }
	
	//endregion
	
	
	
}
#endif