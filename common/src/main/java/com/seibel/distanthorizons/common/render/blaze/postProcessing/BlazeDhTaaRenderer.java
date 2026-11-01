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
public class BlazeDhTaaRenderer {}

#else

#if MC_VER <= MC_26_2_0
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
#else
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
#endif

import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
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
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhAntiAliasRenderer;

public class BlazeDhTaaRenderer implements IDhAntiAliasRenderer
{
	public static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	public static final BlazeDhTaaRenderer INSTANCE = new BlazeDhTaaRenderer();
	
	private static final AbstractDhRenderApiDefinition RENDER_DEF = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
	
	
	private RenderPipeline pipeline;
	private boolean init = false;
	
	private final BlazeUniformBufferWrapper fragUniformBufferWrapper = new BlazeUniformBufferWrapper("dh_taa_frag_uniform");
	private GpuBuffer vboGpuBuffer;
	
	
	private final BlazeTextureWrapper colorTextureA = BlazeTextureWrapper.createColor("dh_taa_color_a");
	private final BlazeTextureWrapper colorTextureB = BlazeTextureWrapper.createColor("dh_taa_color_b");
	/** flips each frame */
	private boolean textureAIsHistory = true;
	
	private final BlazeTextureWrapper taaDepthTextureWrapper = BlazeTextureWrapper.createDepth("dh_taa_depth_texture");
	
	// Previous-frame state, captured at the END of render() for use NEXT frame.
	private final DhMat4f previousDhProjMvmMatrix = new DhMat4f();
	private final DhVec3d previousCameraPos = new DhVec3d(0,0,0);
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private BlazeDhTaaRenderer() { }
	
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
			
			pipelineBuilder.withVertexShader("antialias/blaze/vert");
			pipelineBuilder.withFragmentShader("antialias/blaze/taa");
			
			pipelineBuilder.withSampler("uCurrentColorSampler");
			pipelineBuilder.withSampler("uCurrentDepthSampler");
			pipelineBuilder.withSampler("uHistoryColorSampler");
			
			pipelineBuilder.withUniformBuffer("fragUniformBlock");
			
			pipelineBuilder.withVertexFormat(BlazePostProcessUtil.createVertexFormat());
			pipelineBuilder.withVertexMode(RenderPipelineBuilderWrapper.EDhVertexMode.TRIANGLE_FAN);
		}
		this.pipeline = pipelineBuilder.build();
		
		this.vboGpuBuffer = BlazePostProcessUtil.createAndUploadScreenVertexData("DhTaa");
	}
	
	//endregion
	
	
	
	//========//
	// render //
	//========//
	//region
	
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
		this.colorTextureA.tryCreateOrResizeToScreenSize();
		this.colorTextureB.tryCreateOrResizeToScreenSize();
		
		this.taaDepthTextureWrapper.tryCreateOrResizeToScreenSize();
		
		
		BlazeTextureWrapper historyTexture;
		BlazeTextureWrapper outputTexture;
		if (this.textureAIsHistory)
		{
			historyTexture = this.colorTextureA;
			outputTexture = this.colorTextureB;
		}
		else
		{
			historyTexture = this.colorTextureB;
			outputTexture = this.colorTextureA;
		}
		
		
		// frag uniforms 
		{
			DhMat4f dhProjectionInverse = new DhMat4f(renderParams.dhProjectionMatrix);
			dhProjectionInverse.invert();
			
			DhMat4f dhModelViewInverse = new DhMat4f(renderParams.dhModelViewMatrix);
			dhModelViewInverse.invert();
			
			double cameraOffsetX = renderParams.exactCameraPosition.x - this.previousCameraPos.x;
			double cameraOffsetY = renderParams.exactCameraPosition.y - this.previousCameraPos.y;
			double cameraOffsetZ = renderParams.exactCameraPosition.z - this.previousCameraPos.z;
			
			this.fragUniformBufferWrapper
				.putMat4f(dhProjectionInverse) // uDhProjectionInverse
				.putMat4f(dhModelViewInverse) // uDhModelViewInverse
				.putMat4f(renderParams.dhInverseMvmProjectionMatrix) // uDhInvMvmProj
				.putMat4f(this.previousDhProjMvmMatrix) // uDhPrevProjMvm
				
				// individual camera items because vec3 byte alignment is cursed and breaks
				.putFloat((float) cameraOffsetX) // uCameraOffsetX
				.putFloat((float) cameraOffsetY) // uCameraOffsetY
				.putFloat((float) cameraOffsetZ) // uCameraOffsetZ
				
				.putFloat(BlazeDhMetaRenderer.INSTANCE.dhColorTextureWrapper.getWidth()) // viewWidth
				.putFloat(BlazeDhMetaRenderer.INSTANCE.dhColorTextureWrapper.getHeight()) // viewHeight
				
				.putInt(((RENDER_DEF.getDepthRange() == EDhApiDepthRange.ZERO_TO_POS_ONE) ? 1 : 0)) // uDepthIsZeroToPositiveOne
				
				.finishAndUpload()
			;
		}
		
		
		try (RenderPassWrapper renderPassWrapper = new RenderPassWrapper(
			this::getRenderPassName,
			outputTexture,
			this.taaDepthTextureWrapper
		))
		{
			renderPassWrapper.bindTexture("uCurrentColorSampler", BlazeDhMetaRenderer.INSTANCE.dhColorTextureWrapper);
			renderPassWrapper.bindTexture("uCurrentDepthSampler", BlazeDhMetaRenderer.INSTANCE.dhDepthTextureWrapper);
			renderPassWrapper.bindTexture("uHistoryColorSampler", historyTexture);
			
			renderPassWrapper.setUniform("fragUniformBlock", this.fragUniformBufferWrapper);
			renderPassWrapper.setVertexBuffer(this.vboGpuBuffer);
			renderPassWrapper.setPipeline(this.pipeline);
			renderPassWrapper.draw(4);
		}
		
		
		
		// historical data save
		{
			// Save this frame's data for next frame's reprojection
			
			this.textureAIsHistory = !this.textureAIsHistory;
			
			this.previousDhProjMvmMatrix.set(renderParams.dhProjectionMatrix);
			this.previousDhProjMvmMatrix.multiply(renderParams.dhModelViewMatrix);
			
			this.previousCameraPos.set(renderParams.exactCameraPosition);
		}
		
		
		
		// This also applies the texture to DH's color texture
		BlazeDhSharpenRenderer.INSTANCE.render(outputTexture, renderParams);
	}
	private String getRenderPassName() { return "distantHorizons:TaaRenderer"; }
	
	//endregion
	
	
	
}
#endif