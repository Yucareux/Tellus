package com.seibel.distanthorizons.common.render.openGl.glObject;

import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiFramebuffer;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

public class GlDhFramebuffer implements IDhApiFramebuffer
{
	private static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;
	
	private int id;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public GlDhFramebuffer() { this.id = LWJGL.glGenFramebuffers(); }

	/** For internal use by Iris, do not remove. */
	public GlDhFramebuffer(int id) { this.id = id; }
	
	//endregion
	
	
	
	//=========//
	// methods //
	//=========//
	//region
	
	@Override
	public void addDepthAttachment(int textureId, boolean isCombinedStencil) 
	{
		this.bind();
		
		int depthAttachment = isCombinedStencil ? GL30.GL_DEPTH_STENCIL_ATTACHMENT : GL30.GL_DEPTH_ATTACHMENT;
		LWJGL.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, depthAttachment, GL11.GL_TEXTURE_2D, textureId, 0);
	}
	
	@Override
	public void addColorAttachment(int textureIndex, int textureId)
	{
		this.bind();
		
		LWJGL.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + textureIndex, GL11.GL_TEXTURE_2D, textureId, 0);
	}

	@Override
	public void bind()
	{
		if (this.id == -1)
		{
			throw new IllegalStateException("Framebuffer does not exist!");
		} 
		GLMC.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.id);
	}
	
	@Override
	public void destroy()
	{
		LWJGL.glDeleteFramebuffers(this.id); 
		this.id = -1;
	}
	
	@Override
	public int getStatus()
	{
		this.bind(); 
		int status = LWJGL.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
		return status;
	}
	
	@Override
	public int getId() { return this.id; }
	
	//endregion
	
	
	
	//=============//
	// API methods //
	//=============//
	//region
	
	public boolean overrideThisFrame() { return true; }
	
	//endregion
	
	
	
}
