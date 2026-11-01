package com.seibel.distanthorizons.common.render.blaze.util;

#if MC_VER <= MC_1_21_10
public class BlazePostProcessUtil {}

#else

#if MC_VER <= MC_26_2_0
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
#else
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.*;
import com.mojang.renderpearl.api.vertex.VertexFormat;
#endif

import com.seibel.distanthorizons.common.render.blaze.wrappers.BlazeVertexFormatBuilder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Supplier;

/** Contains code that's used by all post-processing effects. */
public class BlazePostProcessUtil
{
	
	private static final GpuDevice GPU_DEVICE = RenderSystem.getDevice();
	private static final CommandEncoder COMMAND_ENCODER = GPU_DEVICE.createCommandEncoder();
	
	// vertices for a full-screen quad
	private static final float[] VERTICES = new float[]
		{
			// PosX,Y,
			-1f, -1f,
			1f, -1f,
			1f,  1f,
			-1f,  1f,
		};
	
	
	
	//=========//
	// methods //
	//=========//
	//region
	
	public static GpuBuffer createAndUploadScreenVertexData(String name)
	{
		Supplier<String> labelSupplier = () -> "distantHorizons:"+name;
		
		int usage = GpuBuffer.USAGE_COPY_DST
			| GpuBuffer.USAGE_VERTEX;
		int size = VERTICES.length * Float.BYTES;
		GpuBuffer vboGpuBuffer = GPU_DEVICE.createBuffer(labelSupplier, usage, size);
		
		{
			int length = VERTICES.length * Float.BYTES;
			GpuBufferSlice bufferSlice = new GpuBufferSlice(vboGpuBuffer, /*offset*/ 0, length);
			
			ByteBuffer byteBuffer = ByteBuffer.allocateDirect(VERTICES.length * Float.BYTES);
			// Fill buffer with vertices.
			byteBuffer.order(ByteOrder.nativeOrder());
			byteBuffer.asFloatBuffer().put(VERTICES);
			byteBuffer.rewind();
			
			COMMAND_ENCODER.writeToBuffer(bufferSlice, byteBuffer);
		}
		
		return vboGpuBuffer;
	}
	
	public static VertexFormat createVertexFormat()
	{
		VertexFormat vertexFormat = new BlazeVertexFormatBuilder()
			.add("vPosition", BlazeDhVertexFormatUtil.SCREEN_POS)
			.build();
		return vertexFormat;
	}
	
	//endregion
	
	
	
}
#endif