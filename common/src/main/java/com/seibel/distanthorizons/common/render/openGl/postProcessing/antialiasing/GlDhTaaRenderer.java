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

package com.seibel.distanthorizons.common.render.openGl.postProcessing.antialiasing;

import com.seibel.distanthorizons.common.render.openGl.glObject.GLState;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhAntiAliasRenderer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * Handles adding SSAO via {@link GlDhTaaShader} and {@link GlDhTaaSharpenShader}. <br><br>
 * 
 * {@link GlDhTaaShader} - draws the AntiAliasing to a texture. <br>
 * {@link GlDhTaaSharpenShader} - draws the AntiAliasing texture to DH's FrameBuffer. <br>
 */
public class GlDhTaaRenderer implements IDhAntiAliasRenderer
{
	public static GlDhTaaRenderer INSTANCE = new GlDhTaaRenderer();
	
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	private static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;
	
	
	private boolean init = false;
	
	private int width = -1;
	private int height = -1;
	private int framebufferA = -1;
	private int framebufferB = -1;
	
	private int colorTextureA = -1;
	private int colorTextureB = -1;
	
	/** flips each frame */
	private boolean textureAIsHistory = true;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private GlDhTaaRenderer() { }
	
	public void init()
	{
		if (this.init)
		{
			return;
		}
		this.init = true;
		
		GlDhTaaShader.INSTANCE.init();
		GlDhTaaSharpenShader.INSTANCE.init();
	}
	
	private void createFramebuffer(int width, int height)
	{
		if (this.framebufferA != -1)
		{
			LWJGL.glDeleteFramebuffers(this.framebufferA);
			LWJGL.glDeleteFramebuffers(this.framebufferB);
			this.framebufferA = -1;
			this.framebufferB = -1;
		}
		
		if (this.colorTextureA != -1)
		{
			GLMC.glDeleteTextures(this.colorTextureA);
			GLMC.glDeleteTextures(this.colorTextureB);
			this.colorTextureA = -1;
			this.colorTextureB = -1;
		}
		
		this.framebufferA = LWJGL.glGenFramebuffers();
		GLMC.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.framebufferA);
		this.colorTextureA = genTexture(width, height);
		
		this.framebufferB = LWJGL.glGenFramebuffers();
		GLMC.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.framebufferB);
		this.colorTextureB = genTexture(width, height);
	}
	private static int genTexture(int width, int height)
	{
		int id = GLMC.glGenTextures();
		{
			GLMC.glBindTexture(id);
			LWJGL.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA16, width, height, 0, GL11.GL_RGBA, GL12.GL_UNSIGNED_SHORT_4_4_4_4, (ByteBuffer) null);
			LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
			LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
			LWJGL.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, id, 0);
			
			// disable mip-mapping since DH is just going to draw straight to the screen
			LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
			LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
		}
		
		return id;
	}
	
	//endregion
	
	
	
	//========//
	// render //
	//========//
	//region
	
	@Override
	public void render(RenderParams renderParams)
	{
		// GLState needed in MC 1.16.5 probably due to MC not manually setting each GL state they need before the next rendering step
		try (GLState state = new GLState())
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
			
			int historyTextureId;
			int outputTextureId;
			int framebufferId;
			if (this.textureAIsHistory)
			{
				historyTextureId = this.colorTextureA;
				
				outputTextureId = this.colorTextureB;
				framebufferId = this.framebufferB;
			}
			else
			{
				historyTextureId = this.colorTextureB;
				
				outputTextureId = this.colorTextureA;
				framebufferId = this.framebufferA;
			}
			
			
			GlDhTaaShader.INSTANCE.renderPrep(
				framebufferId,
				historyTextureId, outputTextureId
			);
			GlDhTaaShader.INSTANCE.render(renderParams);
			
			GlDhTaaSharpenShader.INSTANCE.render(renderParams);
			
			// post-render
			GlDhTaaShader.INSTANCE.savePostRenderUniformObjects(renderParams);
			this.textureAIsHistory = !this.textureAIsHistory;
			
		}
	}
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	public void free()
	{
		GlDhTaaShader.INSTANCE.free();
		GlDhTaaSharpenShader.INSTANCE.free();
	}
	
	//endregion
	
	
	
}
