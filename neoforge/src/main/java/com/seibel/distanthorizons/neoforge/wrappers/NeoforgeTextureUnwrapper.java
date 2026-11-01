package com.seibel.distanthorizons.neoforge.wrappers;

#if MC_VER < MC_1_21_9
public class NeoforgeTextureUnwrapper
{ /* not needed for older MC versions */ }
#else

#if MC_VER < MC_1_21_9
#elif MC_VER <= MC_26_2_0
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
#else
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlTexture;
#endif

#if MC_VER <= MC_1_21_11
import net.neoforged.neoforge.client.blaze3d.validation.ValidationGpuTexture;
#else
#endif

public class NeoforgeTextureUnwrapper
{
	/**
	 * if Neoforge texture validation is enabled the GlTexture object will be wrapped with a
	 * Neoforge specific ValidationGpuTexture object.
	 * This helper allows us to get the underlying OpenGL texture ID
	 * regardless of what Neoforge returns.
	 */
	public static int getGlTextureIdFromGpuTexture(GpuTexture gpuTexture) throws ClassCastException
	{
		GlTexture glTexture;
		
		#if MC_VER <= MC_1_21_11
		if (gpuTexture instanceof ValidationGpuTexture)
		{
			ValidationGpuTexture validationTexture = (ValidationGpuTexture) gpuTexture;
			glTexture = (GlTexture)validationTexture.getRealTexture();
		}
		else
		{
			glTexture = (GlTexture) gpuTexture;
		}
		#else
		glTexture = (GlTexture) gpuTexture;
		#endif
		
		int id = glTexture.glId();
		return id;
	}
	
}
#endif
