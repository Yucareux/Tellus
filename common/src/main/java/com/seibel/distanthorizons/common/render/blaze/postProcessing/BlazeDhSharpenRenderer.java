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
public class BlazeDhSharpenRenderer {}

#else

#if MC_VER <= MC_26_2_0
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
#else
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
#endif

import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.common.render.blaze.BlazeDhMetaRenderer;
import com.seibel.distanthorizons.common.render.blaze.util.BlazePostProcessUtil;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPassWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPipelineBuilderWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.texture.BlazeTextureWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.uniform.BlazeUniformBufferWrapper;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;

public class BlazeDhSharpenRenderer
{
	public static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	public static final BlazeDhSharpenRenderer INSTANCE = new BlazeDhSharpenRenderer();
	
	private static final AbstractDhRenderApiDefinition RENDER_DEF = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
	
	
	private RenderPipeline pipeline;
	private boolean init = false;
	
	private final BlazeUniformBufferWrapper fragUniformBufferWrapper = new BlazeUniformBufferWrapper("dh_sharpen_frag_uniform");
	private GpuBuffer vboGpuBuffer;
	
	private final BlazeTextureWrapper sharpenDepthTextureWrapper = BlazeTextureWrapper.createDepth("dh_sharpen_depth_texture");
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private BlazeDhSharpenRenderer() { }
	
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
			pipelineBuilder.withName("taa_render");
			
			pipelineBuilder.withVertexShader("antialias/blaze/vert"); // reuse the same fullscreen-quad vert as fog
			pipelineBuilder.withFragmentShader("antialias/blaze/sharpen");
			
			pipelineBuilder.withSampler("uCurrentColorSampler");
			
			pipelineBuilder.withUniformBuffer("fragUniformBlock");
			
			pipelineBuilder.withVertexFormat(BlazePostProcessUtil.createVertexFormat());
			pipelineBuilder.withVertexMode(RenderPipelineBuilderWrapper.EDhVertexMode.TRIANGLE_FAN);
		}
		this.pipeline = pipelineBuilder.build();
		
		this.vboGpuBuffer = BlazePostProcessUtil.createAndUploadScreenVertexData("DhSharpen");
	}
	
	//endregion
	
	
	
	//========//
	// render //
	//========//
	//region
	
	public void render(BlazeTextureWrapper aaTexture, RenderParams renderParams)
	{
		this.tryInit();
		
		
		
		// texture setup
		this.sharpenDepthTextureWrapper.tryCreateOrResizeToScreenSize();
		
		// frag uniforms
		{
			this.fragUniformBufferWrapper
				.putFloat(BlazeDhMetaRenderer.INSTANCE.dhColorTextureWrapper.getWidth()) // viewWidth
				.putFloat(BlazeDhMetaRenderer.INSTANCE.dhColorTextureWrapper.getHeight()) // viewHeight
				
				.putFloat(0.3f) // uCasAmount
				.putInt((RENDER_DEF.getDepthDirection() == EDhApiDepthDirection.REVERSE_Z) ? 1 : 0) // uIsReverseZDepth
				
				.finishAndUpload()
			;
		}
		
		
		
		try (RenderPassWrapper renderPassWrapper = new RenderPassWrapper(
			this::getRenderPassName,
			BlazeDhMetaRenderer.INSTANCE.dhColorTextureWrapper,
			this.sharpenDepthTextureWrapper
		))
		{
			renderPassWrapper.bindTexture("uCurrentColorSampler", aaTexture);
			
			renderPassWrapper.setUniform("fragUniformBlock", this.fragUniformBufferWrapper);
			renderPassWrapper.setVertexBuffer(this.vboGpuBuffer);
			renderPassWrapper.setPipeline(this.pipeline);
			renderPassWrapper.draw(4);
		}
	}
	private String getRenderPassName() { return "distantHorizons:SharpenRenderer"; }
	
	//endregion
	
	
	
}
#endif