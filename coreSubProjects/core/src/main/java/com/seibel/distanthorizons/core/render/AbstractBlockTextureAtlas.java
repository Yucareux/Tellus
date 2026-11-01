package com.seibel.distanthorizons.core.render;

import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockTextureRegistry;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListPool;
import com.seibel.distanthorizons.coreapi.util.TextureUtil;

import java.nio.ByteBuffer;

public abstract class AbstractBlockTextureAtlas
{
	/**
	 * Must match the tile lookup in the Blaze LOD fragment shader. <br>
	 * 256 tiles * 16 pixels = a constant 4096 pixel wide texture,
	 * holding every possible tile id at 4096 pixels tall.
	 */
	public static final int TILES_PER_ROW = 256;
	
	/** how many tile rows the atlas starts with and grows by, 1MB of texture per step */
	protected static final int ALLOCATION_ROW_COUNT = 16;
	
	protected static final PhantomArrayListPool ARRAY_LIST_POOL = new PhantomArrayListPool("BlockTextureAtlas");
	
	
	protected int allocatedTileCount = 0;
	protected int uploadedTileCount = 0;
	
	protected boolean atlasCreated = false;
	
	
	
	//==================//
	// abstract methods //
	//==================//
	//region
	
	protected abstract void tryCreateOrResize(int width, int height);
	
	protected abstract void writeToTexture(
		ByteBuffer pixelBuffer,
		int destinationX, int destinationY,
		int tileWidth, int tileHeight);
	
	
	
	// optional //
	
	/** can be implemented for pre-upload setup */
	protected abstract void beforeWriteToTexture();
	/** can be implemented for post-upload cleanup */
	protected abstract void afterWriteToTexture();
	
	//endregion
	
	
	
	//===========//
	// uploading //
	//===========//
	//region
	
	/**
	 * Uploads any newly registered tiles to the GPU,
	 * must be called on the render thread outside an active render pass.
	 */
	public void uploadPendingTiles()
	{
		// make sure we have an atlas of at least 1 item 
		// so we can bind the texture during rendering
		if (!this.atlasCreated)
		{
			this.atlasCreated = true;
			this.growAtlas(1);
		}
		
		
		
		int totalTileCount = BlockTextureRegistry.INSTANCE.getTileCount();
		if (totalTileCount == this.uploadedTileCount)
		{
			// no new tiles need uploading
			return;
		}
		
		if (totalTileCount > this.allocatedTileCount)
		{
			// the atlas isn't big enough to store these new tiles
			this.growAtlas(totalTileCount);
		}
		
		BlockTextureRegistry.PendingTiles pendingTiles = BlockTextureRegistry.INSTANCE.getAndClearPendingUploadTiles();
		if (pendingTiles == null)
		{
			// no new tiles need to be uploaded
			// this shouldn't happen, but just in case
			return;
		}
		
		try(PhantomArrayListCheckout checkout = ARRAY_LIST_POOL.checkoutByteBuffers(1))
		{
			this.beforeWriteToTexture();
			
			ByteBuffer pixelBuffer = checkout.getByteBuffer(0, BlockTextureRegistry.TILE_BYTE_COUNT);
			for (int i = 0; i < pendingTiles.tilePixels.length; i++)
			{
				byte[] tilePixels = pendingTiles.tilePixels[i];
				if (tilePixels.length == 0)
				{
					LodUtil.assertNotReach("Tiles should never have zero pixels, a static setup may be happening in the wrong order.");
				}
				
				pixelBuffer.clear();
				pixelBuffer.put(tilePixels);
				pixelBuffer.flip();
				
				int tileId = pendingTiles.firstTileId + i;
				int destinationX = (tileId % TILES_PER_ROW) * TextureUtil.TEXTURE_WIDTH_AND_HEIGHT;
				int destinationY = (tileId / TILES_PER_ROW) * TextureUtil.TEXTURE_WIDTH_AND_HEIGHT;
				
				this.writeToTexture(
					pixelBuffer,
					destinationX,
					destinationY,
					TextureUtil.TEXTURE_WIDTH_AND_HEIGHT, // width
					TextureUtil.TEXTURE_WIDTH_AND_HEIGHT  // height
				);
			}
			
			this.afterWriteToTexture();
		}
		this.uploadedTileCount = pendingTiles.firstTileId + pendingTiles.tilePixels.length;
	}
	
	private void growAtlas(int minTileCount)
	{
		int newRowCount = Math.max(this.allocatedTileCount / TILES_PER_ROW, ALLOCATION_ROW_COUNT);
		while (newRowCount * TILES_PER_ROW < minTileCount)
		{
			newRowCount *= 2;
		}
		
		int width = TILES_PER_ROW * TextureUtil.TEXTURE_WIDTH_AND_HEIGHT;
		int height = newRowCount * TextureUtil.TEXTURE_WIDTH_AND_HEIGHT;
		this.tryCreateOrResize(width, height);
		
		this.allocatedTileCount = newRowCount * TILES_PER_ROW;
		// all tiles need to be re-uploaded into the new texture
		this.uploadedTileCount = 0;
		BlockTextureRegistry.INSTANCE.resetPendingUploads();
	}
	
	//endregion
	
	
	
}
