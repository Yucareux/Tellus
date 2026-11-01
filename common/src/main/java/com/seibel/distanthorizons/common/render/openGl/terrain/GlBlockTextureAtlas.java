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

package com.seibel.distanthorizons.common.render.openGl.terrain;

import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper;
import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockTextureRegistry;
import com.seibel.distanthorizons.core.render.AbstractBlockTextureAtlas;
import com.seibel.distanthorizons.core.render.DhApiRenderProxy;
import com.seibel.distanthorizons.coreapi.util.TextureUtil;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.nio.ByteBuffer;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * The GPU side of the {@link BlockTextureRegistry},
 * a texture array with one layer per block face tile. <br><br>
 *
 * Layer {@link BlockTextureRegistry#UNTEXTURED_ID} is a uniform 1.0 color multiplier
 * so vertices without a texture render exactly like flat-colored LODs.
 *
 * @see BlockTextureRegistry
 */
public class GlBlockTextureAtlas extends AbstractBlockTextureAtlas
{
	public static final GlBlockTextureAtlas INSTANCE = new GlBlockTextureAtlas();
	
	private static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;
	
	/** the texture unit the atlas is bound to while LODs render, the lightmap uses unit 0 */
	public static final int GL_BOUND_INDEX = 1;
	
	
	private final PixelUploadGlState uploadGlState = new PixelUploadGlState();
	
	private int textureId = 0;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private GlBlockTextureAtlas() {}
	
	//endregion
	
	
	
	//==================//
	// texture handling //
	//==================//
	//region
	
	@Override
	protected void tryCreateOrResize(int width, int height)
	{
		int previousTexture = LWJGL.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		if (previousTexture == this.textureId) { previousTexture = 0; }
		DhApiRenderProxy.getDhBlockRatioAtlasTextureGlId = -1;
		try
		{
			if (this.textureId != 0)
			{
				LWJGL.glDeleteTextures(this.textureId);
			}

			this.textureId = LWJGL.glGenTextures();
			LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, this.textureId);
			LWJGL.glTexImage2D(
				GL11.GL_TEXTURE_2D, 0,
				GL11.GL_RGBA8,
				width, height,
				0,
				GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
				(ByteBuffer) null
			);

			// nearest filtering keeps the blocky look and prevents
			// texels bleeding between unrelated tiles on adjacent layers
			LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST_MIPMAP_LINEAR);
			LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
			LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
			LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
			DhApiRenderProxy.getDhBlockRatioAtlasTextureGlId = this.textureId;
		}
		finally
		{
			// Allocation must not replace the lightmap or an Iris sampler binding.
			LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
		}
	}
	
	public int getTextureId() { return this.textureId; }
	
	//endregion
	
	
	
	//=========//
	// binding //
	//=========//
	//region
	
	public void bind()
	{
		LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + GL_BOUND_INDEX);
		LWJGL.glBindSampler(GL_BOUND_INDEX, 0); // MC's sampler is probably LINEAR instead of NEAREST, which causes the textures to render blurry
		LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, this.textureId);
		LWJGL.glActiveTexture(GL13.GL_TEXTURE0);
	}
	
	public void unbind()
	{
		LWJGL.glActiveTexture(GL13.GL_TEXTURE0 + GL_BOUND_INDEX);
		LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, 0);
		LWJGL.glActiveTexture(GL13.GL_TEXTURE0);
	}
	
	//endregion
	
	
	
	//===========//
	// uploading //
	//===========//
	//region
	
	@Override
	protected void beforeWriteToTexture()
	{
		this.uploadGlState.saveState();
		
		LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, this.textureId);
		
		LWJGL.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, TextureUtil.TEXTURE_WIDTH_AND_HEIGHT);
		LWJGL.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
		LWJGL.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
		LWJGL.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
	}
	
	@Override
	protected void writeToTexture(ByteBuffer pixelBuffer, int destinationX, int destinationY, int tileWidth, int tileHeight)
	{
		LWJGL.glTexSubImage2D(
			GL11.GL_TEXTURE_2D, 0,
			destinationX, destinationY,
			tileWidth, tileHeight,
			GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixelBuffer
		);
	}
	
	@Override
	protected void afterWriteToTexture()
	{
		LWJGL.glGenerateMipmap(GL11.GL_TEXTURE_2D);
		uploadGlState.close();
	}
	
	//endregion
	
	
	
	//================//
	// helper classes //
	//================//
	//region
	
	private static class PixelUploadGlState implements AutoCloseable
	{
		private int unpackRowLength = 0;
		private int unpackSkipPixels = 0;
		private int unpackSkipRows = 0;
		private int unpackAlignment = 0;
		
		private int textureBinding = 0;
		private int activeTexture = GL13.GL_TEXTURE0;



		public void saveState()
		{
			this.activeTexture = LWJGL.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
			this.unpackRowLength = LWJGL.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
			this.unpackSkipPixels = LWJGL.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
			this.unpackSkipRows = LWJGL.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
			this.unpackAlignment = LWJGL.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
			
			GLMC.glActiveTexture(GL13.GL_TEXTURE0);
			this.textureBinding = LWJGL.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		}
		
		@Override
		public void close()
		{
			LWJGL.glBindTexture(GL11.GL_TEXTURE_2D, this.textureBinding);
			
			LWJGL.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, this.unpackRowLength);
			LWJGL.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, this.unpackSkipPixels);
			LWJGL.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, this.unpackSkipRows);
			LWJGL.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, this.unpackAlignment);
			GLMC.glActiveTexture(this.activeTexture);
		}
		
	}
	
	//endregion
	
	
	
}
