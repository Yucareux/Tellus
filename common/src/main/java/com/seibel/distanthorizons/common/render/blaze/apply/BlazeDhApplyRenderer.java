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

package com.seibel.distanthorizons.common.render.blaze.apply;

#if MC_VER <= MC_1_21_10
public class BlazeDhApplyRenderer {}

#else

#if MC_VER <= MC_26_2_0
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.*;
import com.mojang.blaze3d.vertex.VertexFormat;
#else
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.*;
import com.mojang.renderpearl.api.vertex.VertexFormat;
#endif

import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.common.render.blaze.util.BlazeDhVertexFormatUtil;
import com.seibel.distanthorizons.common.render.blaze.wrappers.BlazeVertexFormatBuilder;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPassWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPipelineBuilderWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.texture.BlazeTextureViewWrapper;
import com.seibel.distanthorizons.common.render.blaze.util.BlazePostProcessUtil;
import com.seibel.distanthorizons.common.render.blaze.wrappers.texture.BlazeTextureWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.uniform.BlazeUniformBufferWrapper;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import com.seibel.distanthorizons.coreapi.ModInfo;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Copies the given color texture
 * where the depth (or another attribute) is valid.
 * Often used to apply post processing effects or
 * the DH texture to MC's color texture. <br><br>
 * 
 * @see BlazeDhCopyRenderer
 */
public class BlazeDhApplyRenderer
{
	public static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final AbstractDhRenderApiDefinition RENDER_DEF = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
	
	private static final GpuDevice GPU_DEVICE = RenderSystem.getDevice();
	private static final CommandEncoder COMMAND_ENCODER = GPU_DEVICE.createCommandEncoder();
	
	private RenderPipeline pipeline;
	
	private final BlazeUniformBufferWrapper fragUniformBufferWrapper = new BlazeUniformBufferWrapper("baseFragUniformBlock");
	
	protected GpuBuffer vboGpuBuffer;
	
	protected final String name;
	protected final String identifierName;
	public String getIdentifierName() { return this.identifierName; }
	
	@Nullable
	private final BlendFunction blendFunction;
	private final String vertexShaderPath;
	private final String fragmentShaderPath;
	
	private final BlazeTextureViewWrapper sourceColorTextureViewWrapper = new BlazeTextureViewWrapper();
	private final BlazeTextureViewWrapper sourceDepthTextureViewWrapper = new BlazeTextureViewWrapper();
	
	private final BlazeTextureViewWrapper destinationColorTextureViewWrapper = new BlazeTextureViewWrapper();
	/** We don't want to actually write any depth data, but blaze3D complains if we don't bind a depth texture. */
	private final BlazeTextureWrapper dummyDepthTextureWrapper = BlazeTextureWrapper.createDepth("apply_dummy_depth");
	
	
	
	/** 
	 * Can be set for special application shaders that need 
	 * extra information. <br><br>
	 * 
	 * will be an empty array if unneeded 
	 */
	private final String[] uniformNames;
	/** will be an empty array if unneeded */
	private final BlazeUniformBufferWrapper[] uniformBufferWrappers;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	public BlazeDhApplyRenderer(
		String name,
		@Nullable BlendFunction blendFunction,
		String vertexShaderPath, String fragmentShaderPath
		)
	{
		this(
			name,
			blendFunction,
			vertexShaderPath, fragmentShaderPath,
			new String[0] // no extra uniforms
		);
	}
	public BlazeDhApplyRenderer(
		String name,
		@Nullable BlendFunction blendFunction,
		String vertexShaderPath, String fragmentShaderPath,
		String[] uniformNames
		)
	{
		this.name = name;
		this.identifierName = "distanthorizons:"+this.name;
		this.blendFunction = blendFunction;
		
		this.vertexShaderPath = vertexShaderPath;
		this.fragmentShaderPath = fragmentShaderPath;
		
		this.uniformNames = uniformNames;
		this.uniformBufferWrappers = new BlazeUniformBufferWrapper[this.uniformNames.length];
	}
	
	private void tryInit(
		GpuTexture sourceColorTexture,
		GpuTexture sourceDepthTexture,
		GpuTexture destinationColorTexture)
	{
		// one-time setup
		if (this.pipeline == null)
		{
			this.createPipeline();
			this.vboGpuBuffer = BlazePostProcessUtil.createAndUploadScreenVertexData(this.name);
		}
		
		this.sourceColorTextureViewWrapper.tryWrap(sourceColorTexture);
		this.sourceDepthTextureViewWrapper.tryWrap(sourceDepthTexture);
		
		this.destinationColorTextureViewWrapper.tryWrap(destinationColorTexture);
		
	}
	private void createPipeline()
	{
		RenderPipelineBuilderWrapper pipelineBuilder = new RenderPipelineBuilderWrapper();
		{
			pipelineBuilder.withFaceCulling(false);
			pipelineBuilder.withDepthWrite(false);
			pipelineBuilder.withDepthTest(RenderPipelineBuilderWrapper.EDhDepthTest.NONE);
			pipelineBuilder.withColorWrite(true);
			
			if (this.blendFunction != null)
			{
				pipelineBuilder.withBlend(this.blendFunction);
			}
			else
			{
				pipelineBuilder.withoutBlend();
			}
			
			pipelineBuilder.withPolygonMode(RenderPipelineBuilderWrapper.EDhPolygonMode.FILL);
			pipelineBuilder.withName(this.name);
			
			pipelineBuilder.withVertexShader(this.vertexShaderPath);
			pipelineBuilder.withFragmentShader(this.fragmentShaderPath);
			
			for (int i = 0; i < this.uniformNames.length; i++)
			{
				String uniformName = this.uniformNames[i];
				pipelineBuilder.withUniformBuffer(uniformName);
			}
			
			pipelineBuilder.withUniformBuffer("baseFragUniformBlock");
			
			pipelineBuilder.withSampler("uSourceColorTexture");
			pipelineBuilder.withSampler("uSourceDepthTexture");
			
			VertexFormat vertexFormat = new BlazeVertexFormatBuilder()
				.add("vPosition", BlazeDhVertexFormatUtil.SCREEN_POS)
				.build();
			pipelineBuilder.withVertexFormat(vertexFormat);
			pipelineBuilder.withVertexMode(RenderPipelineBuilderWrapper.EDhVertexMode.TRIANGLE_FAN);
		}
		this.pipeline = pipelineBuilder.build();
	}
	
	//endregion
	
	
	
	//========//
	// render //
	//========//
	//region
	
	public void setUniform(String uniformName, BlazeUniformBufferWrapper uniformBufferWrapper)
	{
		// the uniform array should be short enough (less than 10 items)
		// where a sequential search should be plenty fast
		for (int i = 0; i < this.uniformNames.length; i++)
		{
			String nameAtIndex = this.uniformNames[i];
			if (nameAtIndex.equals(uniformName))
			{
				this.uniformBufferWrappers[i] = uniformBufferWrapper;
				break;
			}
		}
	}
	
	public void render(
		GpuTexture sourceColorTexture,
		GpuTexture sourceDepthTexture,
		GpuTexture destinationColorTexture)
	{
		this.tryInit(sourceColorTexture, sourceDepthTexture, destinationColorTexture);
		
		this.dummyDepthTextureWrapper.tryCreateOrResizeToScreenSize();
		
		
		this.fragUniformBufferWrapper
			.putInt((RENDER_DEF.getDepthDirection() == EDhApiDepthDirection.REVERSE_Z) ? 1 : 0) // uIsReverseZDepth
			.finishAndUpload();
		;
		
		try (RenderPassWrapper renderPassWrapper = new RenderPassWrapper(
			this::getIdentifierName,
			this.destinationColorTextureViewWrapper,
			this.dummyDepthTextureWrapper))
		{
			renderPassWrapper.bindTexture("uSourceColorTexture", this.sourceColorTextureViewWrapper);
			renderPassWrapper.bindTexture("uSourceDepthTexture", this.sourceDepthTextureViewWrapper);
			
			for (int i = 0; i < this.uniformNames.length; i++)
			{
				String uniformName = this.uniformNames[i];
				BlazeUniformBufferWrapper uniformBuffer = this.uniformBufferWrappers[i];
				if (uniformBuffer == null)
				{
					throw new IllegalStateException("Missing uniform ["+uniformName+"], please set the uniform before rendering.");	
				}
				
				renderPassWrapper.setUniform(uniformName, uniformBuffer);
			}
			
			renderPassWrapper.setUniform("baseFragUniformBlock", this.fragUniformBufferWrapper);
			
			renderPassWrapper.setVertexBuffer(this.vboGpuBuffer);
			renderPassWrapper.setPipeline(this.pipeline);
			
			renderPassWrapper.draw(4);
		}
		
		
		// clear the uniforms after rendering
		// so we can check if they're missing during next frame's rendering
		if (ModInfo.IS_DEV_BUILD)
		{
			Arrays.fill(this.uniformBufferWrappers, null);
		}
	}
	
	//endregion
	
	
	
}
#endif