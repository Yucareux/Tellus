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

package com.seibel.distanthorizons.common.wrappers.minecraft;

#if MC_VER <= MC_1_7_10
#elif MC_VER <= MC_1_12_2
import net.minecraft.client.renderer.GlStateManager;
#elif MC_VER < MC_1_21_5
import com.mojang.blaze3d.platform.GlStateManager;
#elif MC_VER <= MC_26_2_0
import com.mojang.blaze3d.opengl.GlStateManager;
#else
import com.mojang.renderpearl.backend.opengl.GlStateManager;
#endif

import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;

import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;
import org.lwjgl.opengl.GL11;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;


/**
 * <b>Why does DH often call GL methods twice? </b><br> 
 * Once using the base {@link GL11} function and a second time using
 * Minecraft's {@link GlStateManager}?<br><br>
 *
 * <b>Answer: </b><br>
 * Compatibility and robustness<br>
 * In general all MC rendering should go through MC's {@link GlStateManager},
 * however that isn't always the case.
 * So, to prevent issues if a mod (or MC itself) calls a direct GL function
 * instead of the {@link GlStateManager} wrapper, we need to be sure about what the actual
 * set value is (whether setting or getting) and that MC knows what DH has done.
 * This way whether a mod (or MC) is using the {@link GlStateManager} or direct GL calls,
 * they should always have the correct value for anything DH has modified.
 * <br><br>
 * This may slow down some low end GPUs that are driver limited,
 * however James would rather have slow correct rendering vs fast broken rendering.
 */
public class MinecraftGLWrapper
{
	public static final MinecraftGLWrapper INSTANCE = new MinecraftGLWrapper();
	
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final IIrisAccessor IRIS_ACCESSOR = ModAccessorInjector.INSTANCE.get(IIrisAccessor.class);
	
	/** 
	 * on certain MC versions Iris is more controlling over the GL state
	 * (ie canceling state updates when certain shaders are active).
	 * For those versions, just let Iris handle stuff and assume they know
	 * what they're doing.
	 */
	private static boolean runDirectGlCall()
	{
		#if MC_VER <= MC_26_1_2
		return true;
		#else
		if (IRIS_ACCESSOR == null)
		{
			return true;
		}
		
		// when shaders are active, assume Iris has full control of the GLStateManager and knows what they're doing
		return !IRIS_ACCESSOR.isShaderPackInUse();
		#endif
	}
	
	
	
	// scissor //
	//region
	
	/** @see GL11#GL_SCISSOR_TEST */
	public void enableScissorTest()
	{
		if (runDirectGlCall())
		{
			LWJGL.glEnable(GL11.GL_SCISSOR_TEST);
		}
		
		#if MC_VER <= MC_1_7_10
		#elif MC_VER <= MC_1_12_2
		#else
		GlStateManager._enableScissorTest();
		#endif
	}
	/** @see GL11#GL_SCISSOR_TEST */
	public void disableScissorTest()
	{
		if (runDirectGlCall())
		{
			LWJGL.glDisable(GL11.GL_SCISSOR_TEST);
		}
		
		#if MC_VER <= MC_1_7_10
	    #elif MC_VER <= MC_1_12_2
	    #else
		GlStateManager._disableScissorTest();
		#endif
	}
	
	//endregion
	
	
	
	// depth //
	//region
	
	/** @see GL11#GL_DEPTH_TEST */
	public void enableDepthTest()
	{
		if (runDirectGlCall())
		{
			LWJGL.glEnable(GL11.GL_DEPTH_TEST);
		}
		
		#if MC_VER <= MC_1_7_10
	    #elif MC_VER <= MC_1_12_2
	    GlStateManager.enableDepth();
	    #else
		GlStateManager._enableDepthTest();
		#endif
	}
	/** @see GL11#GL_DEPTH_TEST */
	public void disableDepthTest()
	{
		if (runDirectGlCall())
		{
			LWJGL.glDisable(GL11.GL_DEPTH_TEST);
		}
		
		#if MC_VER <= MC_1_7_10
	    #elif MC_VER <= MC_1_12_2
	    GlStateManager.disableDepth();
	    #else
		GlStateManager._disableDepthTest();
		#endif
	}

	/** @see GL11#glDepthFunc(int)  */
	public void glDepthFunc(int func)
	{
		if (runDirectGlCall())
		{
			LWJGL.glDepthFunc(func);
		}
		
		#if MC_VER <= MC_1_7_10
	    #elif MC_VER <= MC_1_12_2
	    GlStateManager.depthFunc(func);
	    #else
		GlStateManager._depthFunc(func);
		#endif
	}
	public int getActiveDepthFunc() { return LWJGL.glGetInteger(GL11.GL_DEPTH_FUNC); }
	
	/** @see GL11#glDepthMask(boolean) */
	public void enableDepthMask()
	{
		if (runDirectGlCall())
		{
			LWJGL.glDepthMask(true);
		}
		
		#if MC_VER <= MC_1_7_10
	    #elif MC_VER <= MC_1_12_2
	    GlStateManager.depthMask(true);
	    #else
		GlStateManager._depthMask(true);
		#endif
	}
	/** @see GL11#glDepthMask(boolean) */
	public void disableDepthMask()
	{
		if (runDirectGlCall())
		{
			LWJGL.glDepthMask(false);
		}
		
		#if MC_VER <= MC_1_7_10
		#elif MC_VER <= MC_1_12_2
		GlStateManager.depthMask(false);
		#else
		GlStateManager._depthMask(false); 
		#endif
	}
	
	//endregion
	
	
	
	// blending //
	//region
	
	/** @see GL11#GL_BLEND */
	public void enableBlend()
	{
		#if MC_VER <= MC_1_7_10
		LWJGL.glEnable(GL11.GL_BLEND);
		#elif MC_VER <= MC_1_12_2
		GlStateManager.enableBlend();
		if (runDirectGlCall())
		{
			LWJGL.glEnable(GL11.GL_BLEND);
		}
		#elif MC_VER <= MC_26_1_2
		GlStateManager._enableBlend();
		if (runDirectGlCall())
		{
			LWJGL.glEnable(GL11.GL_BLEND);
		}
		#else
		// MC has 8 blend indicies that can be set
		for (int i = 0; i < 8; i++)
		{
			GlStateManager._enableBlend(i);
		}
		
		if (runDirectGlCall())
		{
			for (int i = 0; i < 8; i++)
			{
				LWJGL.glEnablei(GL11.GL_BLEND, i);
			}
		}
		#endif
	}
	/** @see GL11#GL_BLEND */
	public void disableBlend()
	{
		#if MC_VER <= MC_1_7_10
		LWJGL.glDisable(GL11.GL_BLEND);
		#elif MC_VER <= MC_1_12_2
		GlStateManager.disableBlend();
		LWJGL.glDisable(GL11.GL_BLEND);
		#elif MC_VER <= MC_26_1_2
		GlStateManager._disableBlend();
		LWJGL.glDisable(GL11.GL_BLEND);
		#else
		// MC has 8 blend indicies that can be set
		for (int i = 0; i < 8; i++)
		{
			GlStateManager._disableBlend(i);
		}
		
		if (runDirectGlCall())
		{
			for (int i = 0; i < 8; i++)
			{
				LWJGL.glDisablei(GL11.GL_BLEND, i);
			}
		}
		
		#endif
	}
	
	/** @see GL11#glBlendFunc */
	public void glBlendFunc(int sfactor, int dfactor)
	{
		if (runDirectGlCall())
		{
			LWJGL.glBlendFunc(sfactor, dfactor);
		}
		
		#if MC_VER <= MC_1_7_10
		#elif MC_VER <= MC_1_12_2
		GlStateManager.blendFunc(sfactor, dfactor);
		#elif MC_VER < MC_1_21_5
		GlStateManager._blendFunc(sfactor, dfactor);
		#endif
	}
	/** @see org.lwjgl.opengl.GL14#glBlendFuncSeparate */
	public void glBlendFuncSeparate(int sfactorRGB, int dfactorRGB, int sfactorAlpha, int dfactorAlpha)
	{
		if (runDirectGlCall())
		{
			LWJGL.glBlendFuncSeparate(sfactorRGB, dfactorRGB, sfactorAlpha, dfactorAlpha);
		}
		
		#if MC_VER <= MC_1_7_10
		#elif MC_VER <= MC_1_12_2
		GlStateManager.tryBlendFuncSeparate(sfactorRGB, dfactorRGB, sfactorAlpha, dfactorAlpha);
		#else
		GlStateManager._blendFuncSeparate(sfactorRGB, dfactorRGB, sfactorAlpha, dfactorAlpha);
		#endif
	}
	
	//endregion
	
	
	
	// frame buffers //
	//region
	
	/** @see org.lwjgl.opengl.GL30#glBindFramebuffer */
	public void glBindFramebuffer(int target, int framebuffer)
	{
		if (runDirectGlCall())
		{
			LWJGL.glBindFramebuffer(target, framebuffer);
		}
		
		#if MC_VER > MC_1_12_2
		GlStateManager._glBindFramebuffer(target, framebuffer);
		#endif
	}
	
	//endregion
	
	
	
	// buffers //
	//region
	
	/** @see org.lwjgl.opengl.GL15#glGenBuffers() */
	public int glGenBuffers()
	{ return LWJGL.glGenBuffers(); }
	
	/** @see org.lwjgl.opengl.GL15#glDeleteBuffers(int) */
	public void glDeleteBuffers(int buffer)
	{
		LWJGL.glDeleteBuffers(buffer);
		
		// MC's implementation has a bug where it will throw:
		// GL_INVALID_OPERATION in glBufferData(immutable)
		// when attempting to delete Storage Buffers
		// So we need to manually delete the buffers ourselves
		//GlStateManager._glDeleteBuffers(buffer); 
	}
	
	//endregion
	
	
	
	// culling //
	//region
	
	/** @see GL11#GL_CULL_FACE */
	public void enableFaceCulling()
	{
		if (runDirectGlCall())
		{
			LWJGL.glEnable(GL11.GL_CULL_FACE);
		}
		
		#if MC_VER <= MC_1_7_10
		#elif MC_VER <= MC_1_12_2
		GlStateManager.enableCull();
		#else
		GlStateManager._enableCull();
		#endif
	}
	/** @see GL11#GL_CULL_FACE */
	public void disableFaceCulling()
	{
		if (runDirectGlCall())
		{
			LWJGL.glDisable(GL11.GL_CULL_FACE);
		}
		
		#if MC_VER <= MC_1_7_10
		#elif MC_VER <= MC_1_12_2
		GlStateManager.disableCull();
		#else
		GlStateManager._disableCull();
		#endif
	}
	
	//endregion
	
	
	
	// textures //
	//region
	
	/** @see GL11#glGenTextures() */
	public int glGenTextures()
	{
		#if MC_VER <= MC_1_7_10
		return LWJGL.glGenTextures();
		#elif MC_VER <= MC_1_12_2
		return GlStateManager.generateTexture();
		#else
		return GlStateManager._genTexture();
		#endif
	}
	/** @see GL11#glDeleteTextures(int) */
	public void glDeleteTextures(int texture)
	{
		#if MC_VER <= MC_1_7_10
		LWJGL.glDeleteTextures(texture);
		#elif MC_VER <= MC_1_12_2
		GlStateManager.deleteTexture(texture);
		#else
		GlStateManager._deleteTexture(texture);
		#endif
	}
	
	/** @see org.lwjgl.opengl.GL13#glActiveTexture(int) */
	public void glActiveTexture(int textureId)
	{
		if (runDirectGlCall())
		{
			LWJGL.glActiveTexture(textureId);
		}
		
		#if MC_VER <= MC_1_7_10
		#elif MC_VER <= MC_1_12_2
		GlStateManager.setActiveTexture(textureId);
		#else
		GlStateManager._activeTexture(textureId);
		#endif
	}
	public int getActiveTexture() { return LWJGL.glGetInteger(GL11.GL_TEXTURE_BINDING_2D); }
	
	/**
	 * Always binds to {@link GL11#GL_TEXTURE_2D}
	 * @see GL11#glBindTexture(int, int)
	 */
	public void glBindTexture(int texture)
	{
		if (runDirectGlCall())
		{
			LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, texture);
		}
		
		#if MC_VER <= MC_1_7_10
		#elif MC_VER <= MC_1_12_2
		GlStateManager.bindTexture(texture);
		#else
		GlStateManager._bindTexture(texture);
		#endif
	}
	
	//endregion
	
	
	
	// viewport //
	//region
	
	/** @see GL11#glViewport(int, int, int, int) */
	public void glViewport(int x, int y, int viewportWidth, int viewportHeight)
	{
		#if MC_VER <= MC_1_7_10
		LWJGL.glViewport(x, y, viewportWidth, viewportHeight);
		#elif MC_VER <= MC_1_12_2
		GlStateManager.viewport(x, y, viewportWidth, viewportHeight);
		#else
		GlStateManager._viewport(x,y, viewportWidth, viewportHeight);
		#endif
	}
	
	//endregion
	
	
	
}
