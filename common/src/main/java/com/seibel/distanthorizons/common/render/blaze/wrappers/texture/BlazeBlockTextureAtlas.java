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

package com.seibel.distanthorizons.common.render.blaze.wrappers.texture;

#if MC_VER <= MC_1_21_10
public class BlazeBlockTextureAtlas {}

#else

import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockTextureRegistry;
import com.seibel.distanthorizons.core.render.AbstractBlockTextureAtlas;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListPool;
import com.seibel.distanthorizons.coreapi.util.BitShiftUtil;
import com.seibel.distanthorizons.coreapi.util.TextureUtil;
import org.lwjgl.opengl.GL32;

import java.nio.ByteBuffer;

/**
 * The Blaze3D equivalent of the OpenGL block texture atlas. <br><br>
 *
 * Blaze3D doesn't support array textures yet, so unlike the OpenGL atlas
 * the tiles are packed into a 2D grid texture
 * with {@link BlazeBlockTextureAtlas#TILES_PER_ROW} tiles per row,
 * growing downward as more tiles are registered.
 * The fragment shader locates a tile via its id and the texture's size,
 * so no extra uniforms are needed.
 *
 * @see BlockTextureRegistry
 * @see com.seibel.distanthorizons.common.render.openGl.terrain.GlBlockTextureAtlas
 */
public class BlazeBlockTextureAtlas extends AbstractBlockTextureAtlas
{
	public static final BlazeBlockTextureAtlas INSTANCE = new BlazeBlockTextureAtlas();
	
	private final BlazeTextureWrapper textureWrapper = BlazeTextureWrapper.createTextureAtlas("BlockTextureAtlas");
	
	private static final PhantomArrayListPool ARRAY_LIST_POOL = new PhantomArrayListPool("BlazeTextureAtlas");
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private BlazeBlockTextureAtlas() { }
	
	//endregion
	
	
	
	//==================//
	// texture handling //
	//==================//
	//region
	
	@Override 
	protected void tryCreateOrResize(int width, int height) { this.textureWrapper.tryCreateOrResize(width, height); }
	
	public BlazeTextureWrapper getTextureWrapper() { return this.textureWrapper; }
	
	//endregion
	
	
	
	//===========//
	// uploading //
	//===========//
	//region
	
	@Override
	protected void beforeWriteToTexture() { /* no setup required */ }
	
	@Override
	protected void writeToTexture(ByteBuffer pixelBuffer, int destinationX, int destinationY, int tileWidth, int tileHeight)
	{
		if (tileWidth != tileHeight)
		{
			throw new IllegalArgumentException("Mip maps require textures be a square, width: ["+tileWidth+"], height: ["+tileHeight+"].");
		}
		
		
		int size = TextureUtil.TEXTURE_WIDTH_AND_HEIGHT;
		int mipLevel = 0;
		ByteBuffer currentLevelData = pixelBuffer;
		
		try (PhantomArrayListCheckout checkout = ARRAY_LIST_POOL.checkoutByteBuffers(1))
		{
			do
			{
				this.textureWrapper.writeToTexture(
					currentLevelData,
					BitShiftUtil.divideByPowerOfTwo(destinationX, mipLevel), // u, halved per mip level
					BitShiftUtil.divideByPowerOfTwo(destinationY, mipLevel), // v, halved per mip level
					mipLevel,
					size, // width
					size  // height
				);
				
				currentLevelData = this.generateNextMipLevel(currentLevelData, size, checkout);
				size /= 2;
				mipLevel++;
			}
			while (size >= 1);
		}
	}
	/**
	 * Generates the next mip level by averaging each 2x2 block of RGBA pixels
	 * from the source buffer into a single pixel in the destination buffer.
	 *
	 * @param sourceData source pixel buffer at the current mip size
	 * @param sourceSize width/height of the source mip level (square, power of two)
	 * @return a new ByteBuffer containing the downsampled (sourceSize / 2) mip level
	 */
	private ByteBuffer generateNextMipLevel(ByteBuffer sourceData, int sourceSize, PhantomArrayListCheckout checkout)
	{
		// mip maps require the texture to be a square
		int textureWidthHeight = sourceSize / 2;
		ByteBuffer mipData = checkout.getByteBuffer(0, textureWidthHeight * textureWidthHeight * 4);
		
		for (int v = 0; v < textureWidthHeight; v++)
		{
			for (int u = 0; u < textureWidthHeight; u++)
			{
				int sourceU = u * 2;
				int sourceV = v * 2;
				
				// get the average of the 4 adjacent pixels
				int index00 = ((sourceV * sourceSize) + sourceU) * 4;
				int index10 = ((sourceV * sourceSize) + (sourceU + 1)) * 4;
				int index01 = (((sourceV + 1) * sourceSize) + sourceU) * 4;
				int index11 = (((sourceV + 1) * sourceSize) + (sourceU + 1)) * 4;
				
				// Handle each ARGB channel separately.
				// We don't care about which channel we're averaging at the time,
				// they're all handled the same.
				for (int channelIndex = 0; channelIndex < 4; channelIndex++)
				{
					int sum =
						// & 0xFF is to convert from signed byte to unsigned for averaging
						(sourceData.get(index00 + channelIndex) & 0xFF) +
							(sourceData.get(index10 + channelIndex) & 0xFF) +
							(sourceData.get(index01 + channelIndex) & 0xFF) +
							(sourceData.get(index11 + channelIndex) & 0xFF);
					
					mipData.put((byte) (sum / 4));
				}
			}
		}
		
		mipData.flip();
		return mipData;
	}
	
	@Override
	protected void afterWriteToTexture() { /* no cleanup required */ }
	
	//endregion
	
	
	
}
#endif
