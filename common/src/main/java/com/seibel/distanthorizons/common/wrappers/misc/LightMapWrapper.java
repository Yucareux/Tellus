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

package com.seibel.distanthorizons.common.wrappers.misc;

#if MC_VER > MC_1_12_2
import com.mojang.blaze3d.platform.NativeImage;
import com.seibel.distanthorizons.common.render.blaze.wrappers.texture.BlazeTextureViewWrapper;
#endif
#if MC_VER <= MC_1_7_10
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
#endif
import com.seibel.distanthorizons.common.render.blaze.wrappers.texture.BlazeTextureViewWrapper;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.ILightMapWrapper;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IRpleAccessor;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;
#if MC_VER < MC_1_21_3
#else
#endif

#if MC_VER <= MC_1_21_10
#elif MC_VER <= MC_26_2_0
import com.mojang.blaze3d.textures.GpuTexture;
#else
import com.mojang.renderpearl.api.textures.GpuTexture;
#endif


public class LightMapWrapper implements ILightMapWrapper
{
	private static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final IRpleAccessor RPLE_ACCESSOR = ModAccessorInjector.INSTANCE.get(IRpleAccessor.class);
	
	/**
	 * which texture index IE 0,1,2... the lightmap will be bound to. <Br> 
	 * Related to but different from {@link org.lwjgl.opengl.GL13#GL_TEXTURE0}.
	 */
	public static final int GL_DH_BOUND_INDEX = 0;
	/**
	 * Only needed for MC 26.1.2+, using for older MC versions 
	 * will corrupt shader rendering. <br>
	 * If missing for MC 26.1.2+ Iris rendering won't have a lightmap for DH LODs.
	 */
	public static final int GL_IRIS_BOUND_INDEX = 2;
	
	private int textureId = 0;
	
	#if MC_VER <= MC_1_21_10
	#else
	private GpuTexture gpuTexture = null;
	#endif
	
	#if MC_VER <= MC_1_21_10
	#else
	private final BlazeTextureViewWrapper lightmapTextureWrapper = new BlazeTextureViewWrapper();
	#endif
	
	
	
	//==============//
	// constructors //
	//==============//
	//region
	
	public LightMapWrapper() { }
	
	//endregion
	
	
	
	//==================//
	// lightmap syncing //
	//==================//
	//region
	
	#if MC_VER > MC_1_12_2
	public void uploadLightmap(NativeImage image)
	{
		#if MC_VER < MC_1_21_3
		int currentTexture = GLMC.getActiveTexture();
		if (this.textureId == 0)
		{
			this.createLightmap(image);
		}
		else
		{
			GLMC.glBindTexture(this.textureId);
		}
		image.upload(0, 0, 0, false);
		
		// getActiveTexture() may return textures that aren't valid and attempting to bind them will
		// throw a GL error in MC 1.21.1
		if (LWJGL.glIsTexture(currentTexture))
		{
			GLMC.glBindTexture(currentTexture);
		}
		#else 
		throw new UnsupportedOperationException("setLightmapId should be used for MC versions after 1.21.3");
		#endif
	}
	private void createLightmap(NativeImage image)
	{
		#if MC_VER < MC_1_21_3
		this.textureId = GLMC.glGenTextures();
		GLMC.glBindTexture(this.textureId);
		LWJGL.glTexImage2D(GL11.GL_TEXTURE_2D, 0, image.format().glFormat(), image.getWidth(), image.getHeight(),
				0, image.format().glFormat(), GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
		#else
		throw new UnsupportedOperationException("setLightmapId should be used for MC versions after 1.21.3");
		#endif
	}
	#endif
	
	public void setLightmapId(int minecraftLightmapTextureId)
	{
		// just use the MC texture ID
		this.textureId = minecraftLightmapTextureId;
	}
	
	#if MC_VER <= MC_1_21_10
	#else
	public void setLightmapGpuTexture(GpuTexture gpuTexture)
	{
		this.gpuTexture = gpuTexture;
		this.lightmapTextureWrapper.tryWrap(this.gpuTexture);
	}
	#endif
	
	
	//endregion
	
	
	
	//==============//
	// lightmap use //
	//==============//
	//region
	
	#if MC_VER <= MC_1_21_10
	#else
	public BlazeTextureViewWrapper getTextureViewWrapper() { return this.lightmapTextureWrapper; }
	#endif

	#if MC_VER <= MC_1_7_10
	public int getOpenGlId()
	{
		// On 1.7.10 nothing wires up setLightmapId(), so query MC directly each time.
		
		// RPLE (the colored-lighting mod) replaces the vanilla lightmap, so check that first.
		if (RPLE_ACCESSOR != null)
		{
			return RPLE_ACCESSOR.getLightmapTextureId();
		}
		
		DynamicTexture lightmapTexture = Minecraft.getMinecraft().entityRenderer.lightmapTexture;
		return lightmapTexture.getGlTextureId();
	}
	#else
	public int getOpenGlId() { return this.textureId; }
	#endif
	
	//endregion
	
	
	
}

