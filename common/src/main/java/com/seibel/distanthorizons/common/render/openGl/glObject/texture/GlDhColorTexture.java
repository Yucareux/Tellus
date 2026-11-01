package com.seibel.distanthorizons.common.render.openGl.glObject.texture;

import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper;
import org.joml.Vector2i;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

import java.nio.ByteBuffer;

public class GlDhColorTexture
{
	private static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;
	
	
	private final EGlDhInternalTextureFormat internalFormat;
	private final EGlDhPixelFormat format;
	private final EGlDhPixelType type;
	private int width;
	private int height;

	private boolean isValid;
	/** AKA, the OpenGL name of this texture */
	private final int id;

	private static final ByteBuffer NULL_BUFFER = null;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public GlDhColorTexture(Builder builder)
	{
		this.isValid = true;
		
		this.internalFormat = builder.internalFormat;
		this.format = builder.format;
		this.type = builder.type;
		
		this.width = builder.width;
		this.height = builder.height;
		
		this.id = LWJGL.glGenTextures();
		
		boolean isPixelFormatInteger = builder.internalFormat.getPixelFormat().isInteger();
		this.setupTexture(this.id, builder.width, builder.height, !isPixelFormatInteger); // this binds the texture
		
		// Clean up after ourselves
		// This is strictly defensive to ensure that other buggy code doesn't tamper with our textures
		LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
	}
	
	
	
	//=========//
	// methods //
	//=========//
	
	private void setupTexture(int id, int width, int height, boolean allowsLinear)
	{
		this.resizeTexture(id, width, height);
		
		LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, allowsLinear ? GL11.GL_LINEAR : GL11.GL_NEAREST);
		LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, allowsLinear ? GL11.GL_LINEAR : GL11.GL_NEAREST);
		LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		
		// disable mip-mapping since DH is just going to draw straight to the screen
		LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
		LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
	}
	
	private void resizeTexture(int texture, int width, int height)
	{
		LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, texture);
		LWJGL.glTexImage2D(GL11.GL_TEXTURE_2D, 0, this.internalFormat.getGlFormat(), width, height, 0, this.format.getGlFormat(), this.type.getGlFormat(), NULL_BUFFER);
	}
	
	void resize(Vector2i textureScaleOverride) { this.resize(textureScaleOverride.x, textureScaleOverride.y); }
	
	// Package private, call CompositeRenderTargets#resizeIfNeeded instead.
	public void resize(int width, int height)
	{
		this.throwIfInvalid();
		
		this.width = width;
		this.height = height;
		
		this.resizeTexture(this.id, width, height);
	}
	
	public EGlDhInternalTextureFormat getInternalFormat() { return this.internalFormat; }
	
	public int getTextureId()
	{
		this.throwIfInvalid();
		return this.id;
	}
	
	public int getWidth() { return this.width; }
	
	public int getHeight() { return this.height; }
	
	public void destroy()
	{
		this.throwIfInvalid();
		this.isValid = false;
		
		GLMC.glDeleteTextures(this.id);
	}
	
	/** @throws IllegalStateException if the texture isn't valid */
	private void throwIfInvalid()
	{
		if (!this.isValid)
		{
			throw new IllegalStateException("Attempted to use a deleted composite render target");
		}
	}
	
	public static Builder builder() { return new Builder(); }
	
	
	
	//================//
	// helper classes //
	//================//
	
	public static class Builder
	{
		private EGlDhInternalTextureFormat internalFormat = EGlDhInternalTextureFormat.RGBA8;
		private int width = 0;
		private int height = 0;
		private EGlDhPixelFormat format = EGlDhPixelFormat.RGBA;
		private EGlDhPixelType type = EGlDhPixelType.UNSIGNED_BYTE;
		
		private Builder()
		{
			// No-op
		}
		
		public Builder setInternalFormat(EGlDhInternalTextureFormat format)
		{
			this.internalFormat = format;
			return this;
		}
		
		public Builder setDimensions(int width, int height)
		{
			if (width <= 0)
			{
				throw new IllegalArgumentException("Width must be greater than zero");
			}
			
			if (height <= 0)
			{
				throw new IllegalArgumentException("Height must be greater than zero");
			}
			
			this.width = width;
			this.height = height;
			
			return this;
		}
		
		public Builder setPixelFormat(EGlDhPixelFormat pixelFormat)
		{
			this.format = pixelFormat;
			return this;
		}
		
		public Builder setPixelType(EGlDhPixelType pixelType)
		{
			this.type = pixelType;
			return this;
		}
		
		public GlDhColorTexture build() { return new GlDhColorTexture(this); }
		
	}
}
