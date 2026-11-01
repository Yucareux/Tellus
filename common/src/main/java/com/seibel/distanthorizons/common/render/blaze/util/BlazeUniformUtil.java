package com.seibel.distanthorizons.common.render.blaze.util;

#if MC_VER <= MC_1_21_10
public class BlazeUniformUtil {}

#else

#if MC_VER <= MC_26_2_0
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
#else
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
#endif

import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;

public class BlazeUniformUtil
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final GpuDevice GPU_DEVICE = RenderSystem.getDevice();
	private static final CommandEncoder COMMAND_ENCODER = GPU_DEVICE.createCommandEncoder();
	
	
	public static GpuBuffer createBuffer(String uniformName, int size, GpuBuffer vboGpuBuffer)
	{
		// create VBO if needed
		if (vboGpuBuffer == null
			|| vboGpuBuffer.size() < size)
		{
			if (vboGpuBuffer != null)
			{
				vboGpuBuffer.close();
			}
			
			int usage = GpuBuffer.USAGE_COPY_DST 
				| GpuBuffer.USAGE_VERTEX
				| GpuBuffer.USAGE_UNIFORM;
			vboGpuBuffer = GPU_DEVICE.createBuffer(() -> uniformName, usage, size);
		}
		
		return vboGpuBuffer;
	}
	
	
	
}
#endif