package com.seibel.distanthorizons.common.render.blaze.wrappers.texture;

#if MC_VER <= MC_1_21_10
public class BlazeTextureViewWrapper {}

#else

#if MC_VER <= MC_26_2_0
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.*;
#else
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.*;
#endif

import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;

import java.util.OptionalDouble;

/**
 * Used to wrap around a MC created texture.
 */
public class BlazeTextureViewWrapper implements IDhBlazeTexture
{
	public static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final GpuDevice GPU_DEVICE = RenderSystem.getDevice();
	private static final CommandEncoder COMMAND_ENCODER = GPU_DEVICE.createCommandEncoder();
	
	
	private GpuTextureView textureView = null;
	public GpuTextureView getTextureView() { return this.textureView; }
	
	private GpuSampler textureSampler = null;
	public GpuSampler getTextureSampler() { return this.textureSampler; }
	
	
	
	//=======//
	// setup //
	//=======//
	//region
	
	/** does nothing if the texture is already wrapped */
	public void tryWrap(GpuTexture texture)
	{
		this.tryRecreateTextureView(texture);
		this.tryCreateSampler();
	}
	private void tryRecreateTextureView(GpuTexture texture)
	{
		if (this.textureView == null
			|| this.textureView.texture() != texture)
		{
			if (this.textureView != null)
			{
				this.textureView.close();
			}
			
			this.textureView = GPU_DEVICE.createTextureView(texture);
		}
	}
	private void tryCreateSampler()
	{
		if (this.textureSampler == null)
		{
			this.textureSampler = GPU_DEVICE.createSampler(
				AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, // U,V
				FilterMode.LINEAR, FilterMode.LINEAR, // minFilter, magFilter
				1, // maxAnisotropy 
				OptionalDouble.empty() // maxLod
			);
		}
	}
	
	//endregion
	
	
	
}
#endif