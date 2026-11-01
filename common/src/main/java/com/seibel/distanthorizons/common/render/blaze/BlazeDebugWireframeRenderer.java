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

package com.seibel.distanthorizons.common.render.blaze;

#if MC_VER <= MC_1_21_10
public class BlazeDebugWireframeRenderer {}

#else

#if MC_VER <= MC_26_2_0
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
#else
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.vertex.VertexFormat;
#endif

import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.common.render.blaze.util.BlazeDhVertexFormatUtil;
import com.seibel.distanthorizons.common.render.blaze.wrappers.BlazeVertexFormatBuilder;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPassWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPipelineBuilderWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.uniform.BlazeUniformBufferWrapper;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.render.renderer.AbstractDebugWireframeRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Handles rendering the wireframe particles 
 * that are used for seeing what the system's doing.
 */
public class BlazeDebugWireframeRenderer extends AbstractDebugWireframeRenderer
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final AbstractDhRenderApiDefinition RENDER_DEF = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
	
	private static final GpuDevice GPU_DEVICE = RenderSystem.getDevice();
	private static final CommandEncoder COMMAND_ENCODER = GPU_DEVICE.createCommandEncoder();
	
	public static BlazeDebugWireframeRenderer INSTANCE = new BlazeDebugWireframeRenderer();
	
	/** A box from 0,0,0 to 1,1,1 */
	private static final float[] BOX_VERTICES = {
		//region
		// Pos x y z
		0, 0, 0,
		1, 0, 0,
		1, 1, 0,
		0, 1, 0,
		0, 0, 1,
		1, 0, 1,
		1, 1, 1,
		0, 1, 1,
		//endregion
	};
	
	private static final int[] BOX_OUTLINE_INDICES = {
		//region
		0, 1,
		1, 2,
		2, 3,
		3, 0,
		
		4, 5,
		5, 6,
		6, 7,
		7, 4,
		
		0, 4,
		1, 5,
		2, 6,
		3, 7,
		//endregion
	};
	
	private static final int VERTICES_PER_BOX = 8; // BOX_VERTICES.length / 3
	private static final int INDICES_PER_BOX = BOX_OUTLINE_INDICES.length; // 24
	
	/**
	 * How many boxes a single batch can hold before it must be flushed.
	 * Chosen so the CPU/GPU buffers can be allocated once at init and reused every frame
	 * without ever needing to be resized. 
	 */
	private static final int MAX_BATCH_SIZE = 1000;
	
	/** vec3 world-space-relative-to-camera position + vec4 color */
	private static final int FLOATS_PER_VERTEX = 3 + 4;
	private static final int BYTES_PER_VERTEX = FLOATS_PER_VERTEX * Float.BYTES;
	
	
	
	// rendering setup
	private boolean init = false;
	
	private RenderPipeline pipeline;
	
	/** Static for the lifetime of the renderer: box N's indices always point at vertex slots [N*8, N*8+8). */
	private GpuBuffer batchIndexBuffer;
	/** Re-uploaded (partially) every flush; sized once for {@link #MAX_BATCH_SIZE} boxes. */
	private GpuBuffer batchVertexBuffer;
	
	/** Only holds the per-frame view-projection matrix now; color moved to a per-vertex attribute so batched boxes can each have their own color. */
	private final BlazeUniformBufferWrapper uniformBufferWrapper = new BlazeUniformBufferWrapper("debugWireframeUniformBlock");
	
	/** CPU-side staging buffer for the current batch, reused every flush to avoid allocations. */
	private final ByteBuffer batchVertexStagingBuffer = ByteBuffer
		.allocateDirect(MAX_BATCH_SIZE * VERTICES_PER_BOX * BYTES_PER_VERTEX)
		.order(ByteOrder.nativeOrder());
	/** How many boxes are currently sitting in {@link #batchVertexStagingBuffer}, waiting to be flushed. */
	private int batchedBoxCount = 0;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public BlazeDebugWireframeRenderer() { }
	
	public void init()
	{
		if (this.init)
		{
			return;
		}
		this.init = true;
		
		this.createPipelines();
		this.createBuffers();
		
	}
	private void createPipelines()
	{
		RenderPipelineBuilderWrapper pipelineBuilder = new RenderPipelineBuilderWrapper();
		{
			pipelineBuilder.withFaceCulling(false);
			pipelineBuilder.withDepthWrite(true);
			if (RENDER_DEF.getDepthDirection() == EDhApiDepthDirection.FORWARD_Z)
			{
				pipelineBuilder.withDepthTest(RenderPipelineBuilderWrapper.EDhDepthTest.LESS);
			}
			else
			{
				pipelineBuilder.withDepthTest(RenderPipelineBuilderWrapper.EDhDepthTest.GREATER);
			}
			pipelineBuilder.withColorWrite(true);
			pipelineBuilder.withoutBlend();
			pipelineBuilder.withPolygonMode(RenderPipelineBuilderWrapper.EDhPolygonMode.WIREFRAME);
			pipelineBuilder.withName("debug_wireframe_renderer");
			
			pipelineBuilder.withVertexShader("debug/blaze/vert");
			pipelineBuilder.withFragmentShader("debug/blaze/frag");
			
			pipelineBuilder.withUniformBuffer("uniformBlock");
			
			
			VertexFormat vertexFormat = new BlazeVertexFormatBuilder()
				.add("vPosition", BlazeDhVertexFormatUtil.FLOAT_XYZ_POS)
				.add("vColor", BlazeDhVertexFormatUtil.RGBA_FLOAT_COLOR)
				.build();
			pipelineBuilder.withVertexFormat(vertexFormat);
			pipelineBuilder.withVertexMode(RenderPipelineBuilderWrapper.EDhVertexMode.LINES);
		}
		this.pipeline = pipelineBuilder.build();
		
	}
	private void createBuffers()
	{
		// vertex buffer
		// sized once for a full batch, so the contents can be rewritten on every flush
		{
			int usage = GpuBuffer.USAGE_COPY_DST
				| GpuBuffer.USAGE_VERTEX;
			int capacityBytes = MAX_BATCH_SIZE * VERTICES_PER_BOX * BYTES_PER_VERTEX;
			this.batchVertexBuffer = GPU_DEVICE.createBuffer(() -> "distantHorizons:DebugWireframeVbo", usage, capacityBytes);
		}
		
		// index buffer
		// the index pattern never changes, so this only needs to be built once.
		{
			int totalIndices = MAX_BATCH_SIZE * INDICES_PER_BOX;
			int[] indices = new int[totalIndices];
			for (int box = 0; box < MAX_BATCH_SIZE; box++)
			{
				int vertexOffset = box * VERTICES_PER_BOX;
				int indexOffset = box * INDICES_PER_BOX;
				for (int i = 0; i < INDICES_PER_BOX; i++)
				{
					indices[indexOffset + i] = BOX_OUTLINE_INDICES[i] + vertexOffset;
				}
			}
			
			ByteBuffer buffer = ByteBuffer.allocateDirect(indices.length * Integer.BYTES);
			buffer.order(ByteOrder.nativeOrder());
			buffer.asIntBuffer().put(indices);
			buffer.rewind();
			
			int usage = GpuBuffer.USAGE_COPY_DST
				| GpuBuffer.USAGE_INDEX;
			this.batchIndexBuffer = GPU_DEVICE.createBuffer(() -> "distantHorizons:DebugWireframeIbo", usage, buffer.capacity());
			
			GpuBufferSlice bufferSlice = new GpuBufferSlice(this.batchIndexBuffer, /*offset*/ 0, buffer.capacity());
			COMMAND_ENCODER.writeToBuffer(bufferSlice, buffer);
		}
	}
	
	//endregion
	
	
	
	//===========//
	// rendering //
	//===========//
	//region
	
	@Override
	protected void beginRenderBatch()
	{
		this.init();
		
		this.batchVertexStagingBuffer.clear();
		this.batchedBoxCount = 0;
	}
	
	@Override
	protected void endRenderBatch() { this.flushBatchAndRender(); }
	
	@Override
	public void renderBox(Box box)
	{
		this.init();
		
		// shouldn't happen, but just in case
		if (box == null)
		{
			return;
		}
		
		
		if (this.batchedBoxCount >= MAX_BATCH_SIZE)
		{
			this.flushBatchAndRender();
		}
		
		this.addBoxToBatch(box);
	}
	private void addBoxToBatch(Box box)
	{
		float minX = box.minPos.x - this.camPosFloatThisFrame.x;
		float minY = box.minPos.y - this.camPosFloatThisFrame.y;
		float minZ = box.minPos.z - this.camPosFloatThisFrame.z;
		float sizeX = box.maxPos.x - box.minPos.x;
		float sizeY = box.maxPos.y - box.minPos.y;
		float sizeZ = box.maxPos.z - box.minPos.z;
		
		float r = box.color.getRed() / 255.0f;
		float g = box.color.getGreen() / 255.0f;
		float b = box.color.getBlue() / 255.0f;
		float a = box.color.getAlpha() / 255.0f;
		
		for (int i = 0; i < VERTICES_PER_BOX; i++)
		{
			float vertX = BOX_VERTICES[i * 3];
			float vertY = BOX_VERTICES[i * 3 + 1];
			float vertZ = BOX_VERTICES[i * 3 + 2];
			
			this.batchVertexStagingBuffer.putFloat(minX + vertX * sizeX);
			this.batchVertexStagingBuffer.putFloat(minY + vertY * sizeY);
			this.batchVertexStagingBuffer.putFloat(minZ + vertZ * sizeZ);
			
			this.batchVertexStagingBuffer.putFloat(r);
			this.batchVertexStagingBuffer.putFloat(g);
			this.batchVertexStagingBuffer.putFloat(b);
			this.batchVertexStagingBuffer.putFloat(a);
		}
		
		this.batchedBoxCount++;
	}
	
	/** 
	 * Upload the boxes currently queued,
	 * draws them in a single render pass, 
	 * then resets the batch. 
	 */
	private void flushBatchAndRender()
	{
		if (this.batchedBoxCount == 0)
		{
			return;
		}
		
		
		// upload only the part of the staging buffer that's actually used
		{
			this.batchVertexStagingBuffer.flip();
			int usedBytes = this.batchVertexStagingBuffer.remaining();
			
			GpuBufferSlice vertexSlice = new GpuBufferSlice(this.batchVertexBuffer, /*offset*/ 0, usedBytes);
			COMMAND_ENCODER.writeToBuffer(vertexSlice, this.batchVertexStagingBuffer);
		}
		
		
		// shared uniforms
		{
			this.uniformBufferWrapper
				.putMat4f(this.dhMvmProjMatrixThisFrame) // uViewProj
				.finishAndUpload()
			;
		}
		
		
		
		// render
		try (RenderPassWrapper renderPassWrapper = new RenderPassWrapper(
			this::getRenderPassName,
			BlazeDhMetaRenderer.INSTANCE.dhColorTextureWrapper, 
			BlazeDhMetaRenderer.INSTANCE.dhDepthTextureWrapper))
		{
			renderPassWrapper.setUniform("uniformBlock", this.uniformBufferWrapper);
			
			renderPassWrapper.setPipeline(this.pipeline);
			renderPassWrapper.setIndexBuffer(this.batchIndexBuffer);
			renderPassWrapper.setVertexBuffer(this.batchVertexBuffer);
			
			renderPassWrapper.drawIndexed(this.batchedBoxCount * INDICES_PER_BOX);
		}
		
		
		// clear batch for new boxes
		this.batchVertexStagingBuffer.clear();
		this.batchedBoxCount = 0;
	}
	private String getRenderPassName() { return "distantHorizons:DebugRenderer"; }
	
	//endregion
	
	
	
}
#endif
