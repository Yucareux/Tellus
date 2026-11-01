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

package com.seibel.distanthorizons.common.render.openGl.postProcessing.fade;

import com.seibel.distanthorizons.common.render.openGl.GlDhMetaRenderer;
import com.seibel.distanthorizons.common.render.openGl.glObject.GLState;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhVanillaFadeRenderer;
import com.seibel.distanthorizons.core.logging.DhLogger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

import java.nio.ByteBuffer;

/**
 * Handles fading MC and DH together via {@link GlDhVanillaFadeShader} and {@link GlDhFarFadeApplyShader}. <br><br>
 * 
 * {@link GlDhVanillaFadeShader} - draws the Fade to a texture. <br>
 * {@link GlDhFarFadeApplyShader} - draws the Fade texture to MC's FrameBuffer. <br>
 */
public class GlVanillaFadeRenderer implements IDhVanillaFadeRenderer
{
	public static GlVanillaFadeRenderer INSTANCE = new GlVanillaFadeRenderer();
	
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	private static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;
	
	
	private boolean init = false;
	
	private int width = -1;
	private int height = -1;
	private int fadeFramebuffer = -1;
	
	private int fadeTexture = -1;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private GlVanillaFadeRenderer() { }
	
	public void init()
	{
		if (this.init) return;
		this.init = true;
		
		GlDhVanillaFadeShader.INSTANCE.init();
		GlDhFarFadeApplyShader.INSTANCE.init();
	}
	
	private void createFramebuffer(int width, int height)
	{
		if (this.fadeFramebuffer != -1)
		{
			LWJGL.glDeleteFramebuffers(this.fadeFramebuffer);
			this.fadeFramebuffer = -1;
		}
		
		this.fadeFramebuffer = LWJGL.glGenFramebuffers();
		GLMC.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.fadeFramebuffer);
		
		
		// Applying the fade texture is only needed if MC is drawing to their own frame buffer,
		// otherwise we can directly render to their texture
		if (MC_RENDER.mcRendersToFrameBuffer())
		{
			if (this.fadeTexture != -1)
			{
				GLMC.glDeleteTextures(this.fadeTexture);
				this.fadeTexture = -1;
			}
			
			this.fadeTexture = GL11.glGenTextures();
			GLMC.glBindTexture(this.fadeTexture);
			LWJGL.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA16, width, height, 0, GL11.GL_RGBA, GL12.GL_UNSIGNED_SHORT_4_4_4_4, (ByteBuffer) null);
			LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
			LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
			LWJGL.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, this.fadeTexture, 0);
		}
		else
		{
			LWJGL.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, MC_RENDER.getGlColorTextureId(), 0);
		}
	}
	
	//endregion
	
	
	
	//========//
	// render //
	//========//
	//region
	
	@Override
	public void render(RenderParams renderParams)
	{
		int depthTextureId = GlDhMetaRenderer.INSTANCE.getActiveDepthTextureId();
		if (depthTextureId == -1)
		{
			// the renderer hasn't been set up yet
			// trying to render fading may cause GL errors
			return;
		}
		
		
		
		IProfilerWrapper profiler = MC_CLIENT.getProfiler();
		try (IProfilerWrapper.IProfileBlock fade_profile = profiler.push("DH-Vanilla Fade");
			GLState mcState = new GLState())
		{
			this.init();
			
			// resize the framebuffer if necessary
			int width = MC_RENDER.getTargetFramebufferViewportWidth();
			int height = MC_RENDER.getTargetFramebufferViewportHeight();
			if (this.width != width || this.height != height)
			{
				this.width = width;
				this.height = height;
				this.createFramebuffer(width, height);
			}
			
			
			GlDhVanillaFadeShader.INSTANCE.frameBuffer = this.fadeFramebuffer;
			GlDhVanillaFadeShader.INSTANCE.setProjectionMatrix(renderParams);
			GlDhVanillaFadeShader.INSTANCE.setLevelMaxHeight(renderParams.clientLevelWrapper.getMaxHeight());
			GlDhVanillaFadeShader.INSTANCE.render(renderParams);
			
			// Applying the fade texture is only needed if MC is drawing to their own frame buffer,
			// otherwise we can directly render to their texture
			if (MC_RENDER.mcRendersToFrameBuffer())
			{
				GlDhFarFadeApplyShader.INSTANCE.fadeTexture = this.fadeTexture;
				GlDhFarFadeApplyShader.INSTANCE.readFramebuffer = GlDhVanillaFadeShader.INSTANCE.frameBuffer;
				GlDhFarFadeApplyShader.INSTANCE.drawFramebuffer = MC_RENDER.getTargetFramebuffer();
				GlDhFarFadeApplyShader.INSTANCE.render(renderParams);
			}
		}
		catch (Exception e)
		{
			LOGGER.error("Unexpected error during fade render, error: [" + e.getMessage() + "].", e);
		}
	}
	
	//endregion
	
	
	
	//================//
	// base overrides // 
	//================//
	//region
	
	public void free()
	{
		GlDhVanillaFadeShader.INSTANCE.free();
		GlDhFarFadeApplyShader.INSTANCE.free();
	}
	
	//endregion
	
	
	
}
