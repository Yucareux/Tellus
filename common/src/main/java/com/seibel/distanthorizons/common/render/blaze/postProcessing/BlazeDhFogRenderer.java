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
public class BlazeDhFogRenderer {}

#else

#if MC_VER <= MC_26_2_0
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
#else
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
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

import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiHeightFogMixMode;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiFogRenderParam;
import com.seibel.distanthorizons.common.render.blaze.BlazeDhMetaRenderer;
import com.seibel.distanthorizons.common.render.blaze.apply.BlazeDhApplyRenderer;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPassWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPipelineBuilderWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.texture.BlazeTextureWrapper;
import com.seibel.distanthorizons.common.render.blaze.util.BlazePostProcessUtil;
import com.seibel.distanthorizons.common.render.blaze.wrappers.uniform.BlazeUniformBufferWrapper;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhFogRenderer;

import java.awt.*;

/**
 * Renders fog onto the LODs.
 */
public class BlazeDhFogRenderer implements IDhFogRenderer
{
	public static final DhLogger LOGGER = new DhLoggerBuilder().build(); 
	
	private static final AbstractDhRenderApiDefinition RENDER_DEF = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
	
	private static final GpuDevice GPU_DEVICE = RenderSystem.getDevice();
	private static final CommandEncoder COMMAND_ENCODER = GPU_DEVICE.createCommandEncoder();
	
	public static final BlazeDhFogRenderer INSTANCE = new BlazeDhFogRenderer();
	
	
	private BlazeDhApplyRenderer applyRenderer;
	
	private RenderPipeline pipeline;
	private boolean init = false;
	
	private final BlazeUniformBufferWrapper fragUniformBufferWrapper = new BlazeUniformBufferWrapper("dh_fog_frag_uniform");
	
	private GpuBuffer vboGpuBuffer;
	
	private final BlazeTextureWrapper fogColorTextureWrapper = BlazeTextureWrapper.createColor("dh_fog_color_texture");
	/** We don't want to actually write any depth data, but blaze3D complains if we don't bind a depth texture. */
	private final BlazeTextureWrapper fogDepthTextureWrapper = BlazeTextureWrapper.createDepth("dh_fog_depth_texture");
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private BlazeDhFogRenderer() { }
	
	private void tryInit()
	{
		if (this.init)
		{
			return;
		}
		this.init = true;
		
		
		BlendFunction blendFunc;
		#if MC_VER <= MC_26_1_2
		blendFunc = new BlendFunction(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA, SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_ALPHA);
		#else
		blendFunc = new BlendFunction(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA);
		#endif
		
		this.applyRenderer = new BlazeDhApplyRenderer(
			"fog_apply_to_dh",
			blendFunc,
			"apply/blaze/vert", "apply/blaze/frag"
		);
		
		RenderPipelineBuilderWrapper pipelineBuilder = new RenderPipelineBuilderWrapper();
		{
			pipelineBuilder.withFaceCulling(false);
			pipelineBuilder.withDepthWrite(false);
			pipelineBuilder.withDepthTest(RenderPipelineBuilderWrapper.EDhDepthTest.NONE);
			pipelineBuilder.withColorWrite(true);
			pipelineBuilder.withoutBlend();
			pipelineBuilder.withPolygonMode(RenderPipelineBuilderWrapper.EDhPolygonMode.FILL);
			pipelineBuilder.withName("fog_render");
			
			pipelineBuilder.withVertexShader("fog/blaze/vert");
			pipelineBuilder.withFragmentShader("fog/blaze/frag");
			
			pipelineBuilder.withSampler("uDhDepthTexture");
			
			pipelineBuilder.withUniformBuffer("fragUniformBlock");
			
			pipelineBuilder.withVertexFormat(BlazePostProcessUtil.createVertexFormat());
			pipelineBuilder.withVertexMode(RenderPipelineBuilderWrapper.EDhVertexMode.TRIANGLE_FAN);
		}
		this.pipeline = pipelineBuilder.build();
		
		
		this.vboGpuBuffer = BlazePostProcessUtil.createAndUploadScreenVertexData("FogRenderer");
	}
	
	//endregion
	
	
	
	//========//
	// render //
	//========//
	//region
	
	@Override
	public void render(RenderParams renderParams, DhApiFogRenderParam fogRenderParams)
	{
		this.tryInit();
		
		
		if (BlazeDhMetaRenderer.INSTANCE.dhDepthTextureWrapper.isEmpty()
			|| BlazeDhMetaRenderer.INSTANCE.dhColorTextureWrapper.isEmpty())
		{
			return;	
		}
		
		
		
		this.fogColorTextureWrapper.tryCreateOrResizeToScreenSize();
		this.fogDepthTextureWrapper.tryCreateOrResizeToScreenSize();
		
		{
			// create data //
			
			int lodDrawDistance = Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get() * LodUtil.CHUNK_WIDTH;
			
			DhMat4f inverseMvmProjMatrix = new DhMat4f(renderParams.dhMvmProjMatrix);
			inverseMvmProjMatrix.invert();
			
			EDhApiHeightFogMixMode heightFogMixingMode = fogRenderParams.getHeightFogMixingMode();
			boolean heightFogEnabled = 
				heightFogMixingMode != EDhApiHeightFogMixMode.SPHERICAL 
				&& heightFogMixingMode != EDhApiHeightFogMixMode.CYLINDRICAL;
			boolean useSphericalFog = heightFogMixingMode == EDhApiHeightFogMixMode.SPHERICAL;
			
			Color fogColor = fogRenderParams.getFogColor();
			
			
			
			// upload data //
			
			this.fragUniformBufferWrapper
				// fog uniforms
				.putVec4f(
					fogColor.getRed() / 255.0f, 
					fogColor.getGreen() / 255.0f, 
					fogColor.getBlue() / 255.0f, 
					fogColor.getAlpha() / 255.0f) // uFogColor
				.putFloat(1.f / lodDrawDistance) //uFogScale
				.putFloat(1.f / renderParams.clientLevelWrapper.getMaxHeight()) //uFogVerticalScale
				.putInt(0) //uFogDebugMode  // 0 = normal // 1 = render everything with fog color // 7 = use debug rendering
				.putInt(fogRenderParams.getFarFogFalloff().value) //uFogFalloffType
				
				// fog config
				.putFloat(fogRenderParams.getFarFogStartPercent()) // uFarFogStart
				.putFloat(fogRenderParams.getFarFogEndPercent() - fogRenderParams.getFarFogStartPercent()) // uFarFogLength
				.putFloat(fogRenderParams.getFarFogMinThickness()) // uFarFogMin
				.putFloat(fogRenderParams.getFarFogMaxThickness() - fogRenderParams.getFarFogMinThickness()) // uFarFogRange 
				.putFloat(fogRenderParams.getFarFogDensity()) // uFarFogDensity
				
				// height fog config
				.putFloat(fogRenderParams.getHeightFogStartPercent()) // uHeightFogStart
				.putFloat(fogRenderParams.getHeightFogEndPercent() - fogRenderParams.getHeightFogStartPercent()) // uHeightFogLength
				.putFloat(fogRenderParams.getHeightFogMinThickness()) // uHeightFogMin
				.putFloat(fogRenderParams.getHeightFogMaxThickness() - fogRenderParams.getHeightFogMinThickness()) // uHeightFogRange
				.putFloat(fogRenderParams.getHeightFogDensity()) // uHeightFogDensity
				
				// ??
				.putInt(heightFogEnabled ? 1 : 0) // uHeightFogEnabled
				.putInt(fogRenderParams.getHeightFogFalloff().value) // uHeightFogFalloffType
				.putInt(fogRenderParams.getHeightFogDirection().basedOnCamera ? 1 : 0) // uHeightBasedOnCamera
				.putFloat(fogRenderParams.getHeightFogBaseHeight()) // uHeightFogBaseHeight
				.putInt(fogRenderParams.getHeightFogDirection().fogAppliesUp ? 1 : 0) // uHeightFogAppliesUp
				.putInt(fogRenderParams.getHeightFogDirection().fogAppliesDown ? 1 : 0) // uHeightFogAppliesDown
				.putInt(useSphericalFog ? 1 : 0) // uUseSphericalFog
				.putInt(heightFogMixingMode.value) // uHeightFogMixingMode
				.putFloat((float)renderParams.exactCameraPosition.y) // uCameraBlockYPos
				
				.putMat4f(inverseMvmProjMatrix) // uInvMvmProj
				
				.putInt((RENDER_DEF.getDepthDirection() == EDhApiDepthDirection.REVERSE_Z) ? 1 : 0) // uIsReverseZDepth
				.putInt((RENDER_DEF.getDepthRange() == EDhApiDepthRange.ZERO_TO_POS_ONE) ? 1 : 0) // uDepthIsZeroToPositiveOne
				
				.finishAndUpload()
			;
		}
		
		
		this.renderFogToTexture();
		this.applyRenderer.render(this.fogColorTextureWrapper.getTexture(), BlazeDhMetaRenderer.INSTANCE.dhDepthTextureWrapper.getTexture(), BlazeDhMetaRenderer.INSTANCE.dhColorTextureWrapper.getTexture());
		
	}
	
	
	
	private void renderFogToTexture()
	{
		try (RenderPassWrapper renderPassWrapper = new RenderPassWrapper(
			this::getRenderPassName,
			this.fogColorTextureWrapper, 
			this.fogDepthTextureWrapper))
		{
			renderPassWrapper.bindTexture("uDhDepthTexture", BlazeDhMetaRenderer.INSTANCE.dhDepthTextureWrapper);
			
			renderPassWrapper.setUniform("fragUniformBlock", this.fragUniformBufferWrapper);
			
			renderPassWrapper.setVertexBuffer(this.vboGpuBuffer); // vertex buffer can only be "0" lol
			renderPassWrapper.setPipeline(this.pipeline);
			
			renderPassWrapper.draw(/*indexCount*/ 4);
		}
	}
	private String getRenderPassName() { return "distantHorizons:FogRenderer"; }
	
	
	//endregion
	
	
	
}
#endif