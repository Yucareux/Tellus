package com.seibel.distanthorizons.neoforge.wrappers;

import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import net.minecraft.client.Minecraft;
import com.seibel.distanthorizons.core.logging.DhLogger;

#if MC_VER < MC_1_21_9
#elif MC_VER <= MC_26_2_0
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
#else
import com.mojang.renderpearl.api.textures.GpuTexture;
#endif

public class NeoforgeMinecraftRenderWrapper extends MinecraftRenderWrapper
{
	public static final NeoforgeMinecraftRenderWrapper INSTANCE = new NeoforgeMinecraftRenderWrapper();
	
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	private static final Minecraft MC = Minecraft.getInstance();
	
	
	
	@Override
	public int getGlDepthTextureId()
	{
		#if MC_VER < MC_1_21_9
		// no special handling required,
		// both neo/fabric uses the same back end objects
		return super.getGlDepthTextureId();
		#else
		try
		{
			GpuTexture gpuTexture = this.getRenderTarget().getDepthTexture();
			int id = NeoforgeTextureUnwrapper.getGlTextureIdFromGpuTexture(gpuTexture);
			return id;
		}
		catch (Exception e)
		{
			// only log this error once per session
			if (!this.depthTextureCastFailLogged)
			{
				this.depthTextureCastFailLogged = true;
				LOGGER.error("Unable to cast render Target depth texture to GlTexture. MC or a rendering mod may have changed the object type.", e);
			}
			return -1;
		}
		#endif
	}
	
	@Override
	public int getGlColorTextureId()
	{
		#if MC_VER < MC_1_21_9
		// no special handling required,
		// both neo/fabric uses the same back end objects
		return super.getGlColorTextureId();
		#else
		try
		{
			GpuTexture gpuTexture = this.getRenderTarget().getColorTexture();
			int id = NeoforgeTextureUnwrapper.getGlTextureIdFromGpuTexture(gpuTexture);
			return id;
		}
		catch (Exception e)
		{
			// only log this error once per session
			if (!this.colorTextureCastFailLogged)
			{
				this.colorTextureCastFailLogged = true;
				LOGGER.error("Unable to cast render Target color texture to ValidationGpuTexture or GlTexture. MC, Neoforge, or a rendering mod may have changed the object type.", e);
			}
			return -1;
		}
		#endif
	}
	
	
	
}
