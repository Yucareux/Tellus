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
public class BlazeDhCopyRenderer {}

#else

#if MC_VER <= MC_26_2_0
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
#else
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.*;
#endif

import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPassWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPipelineBuilderWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.texture.BlazeTextureWrapper;
import com.seibel.distanthorizons.common.render.blaze.util.BlazePostProcessUtil;
import com.seibel.distanthorizons.common.render.blaze.wrappers.texture.IDhBlazeTexture;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;

/**
 * Blindly copies one texture into another.
 *
 * @see BlazeDhApplyRenderer
 */
public class BlazeDhCopyRenderer
{
	public static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final GpuDevice GPU_DEVICE = RenderSystem.getDevice();
	private static final CommandEncoder COMMAND_ENCODER = GPU_DEVICE.createCommandEncoder();
	
	public static final BlazeDhCopyRenderer INSTANCE = new BlazeDhCopyRenderer();
	
	private RenderPipeline pipeline;
	private boolean init = false;
	
	private GpuBuffer vboGpuBuffer;
	
	private BlazeTextureWrapper dummyDepthTextureWrapper;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private BlazeDhCopyRenderer() { }
	
	private void tryInit()
	{
		if (this.init)
		{
			return;
		}
		this.init = true;
		
		
		this.dummyDepthTextureWrapper = BlazeTextureWrapper.createDepth("dh_copy_depth_texture");
		
		RenderPipelineBuilderWrapper pipelineBuilder = new RenderPipelineBuilderWrapper();
		{
			pipelineBuilder.withFaceCulling(false);
			pipelineBuilder.withDepthWrite(false);
			pipelineBuilder.withDepthTest(RenderPipelineBuilderWrapper.EDhDepthTest.NONE);
			pipelineBuilder.withColorWrite(true);
			pipelineBuilder.withoutBlend();
			pipelineBuilder.withPolygonMode(RenderPipelineBuilderWrapper.EDhPolygonMode.FILL);
			pipelineBuilder.withName("copy");
			
			pipelineBuilder.withVertexShader("copy/blaze/vert");
			pipelineBuilder.withFragmentShader("copy/blaze/frag");
			
			pipelineBuilder.withSampler("uCopyTexture");
			
			pipelineBuilder.withVertexFormat(BlazePostProcessUtil.createVertexFormat());
			pipelineBuilder.withVertexMode(RenderPipelineBuilderWrapper.EDhVertexMode.TRIANGLE_FAN);
		}
		this.pipeline = pipelineBuilder.build();
		
		
		this.vboGpuBuffer = BlazePostProcessUtil.createAndUploadScreenVertexData("CopyRenderer");
		
	}
	
	//endregion
	
	
	
	//========//
	// render //
	//========//
	//region
	
	public void render(
		IDhBlazeTexture sourceColorTextureWrapper,
		IDhBlazeTexture destinationColorTextureWrapper)
	{
		this.tryInit();
		
		this.dummyDepthTextureWrapper.tryCreateOrResizeToScreenSize();
		
		try (RenderPassWrapper renderPassWrapper = new RenderPassWrapper(
			this::getRenderPassName,
			destinationColorTextureWrapper,
			this.dummyDepthTextureWrapper))
		{
			renderPassWrapper.bindTexture("uCopyTexture", sourceColorTextureWrapper);
			
			renderPassWrapper.setVertexBuffer(this.vboGpuBuffer); // vertex buffer can only be "0" lol
			
			renderPassWrapper.setPipeline(this.pipeline);
			renderPassWrapper.draw(4);
		}
	}
	
	private String getRenderPassName() { return "distantHorizons:CopyRenderer"; }
	
	//endregion
	
	
	
}
#endif