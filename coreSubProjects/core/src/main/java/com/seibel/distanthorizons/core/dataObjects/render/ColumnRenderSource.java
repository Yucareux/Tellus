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

package com.seibel.distanthorizons.core.dataObjects.render;

import com.seibel.distanthorizons.api.enums.config.EDhApiVerticalQuality;
import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockTextureRegistry;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.util.RenderDataPointReducingList;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.AbstractPhantomArrayList;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListPool;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.dataObjects.render.columnViews.ColumnRenderView;
import com.seibel.distanthorizons.core.util.RenderDataPointUtil;
import com.seibel.distanthorizons.core.config.Config;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import com.seibel.distanthorizons.core.logging.DhLogger;

/**
 * Stores the render data used to generate OpenGL buffers.
 *
 * @see RenderDataPointUtil
 */
public class ColumnRenderSource extends AbstractPhantomArrayList
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	/** measured in data columns */
	public static final int WIDTH = 64;
	
	/** 
	 * Texture palettes are indexed by unsigned bytes. <Br>
	 * This limitation is also done so a single LOD doesn't take
	 * up every possible texture ID available to the world.
	 */
	public static final int MAX_TEXTURE_PALETTE_SIZE = 256;
	
	public static final PhantomArrayListPool ARRAY_LIST_POOL = new PhantomArrayListPool("Render Source");
	
	
	
	/** 
	 * will be zero if an empty data source was created 
	 * @see EDhApiVerticalQuality#calculateMaxNumberOfVerticalSlicesAtDetailLevel(byte) 
	 */
	public int maxVerticalSliceCount;
	public long pos;
	public int yOffset;
	
	public final LongArrayList renderDataContainer;
	/**
	 * void data is a 2D array
	 * that represents the air render data below
	 * LODs that aren't right up against the minimum world height.
	 * <br><br>
	 * This is necessary so sky/block lighting is correct.
	 * Otherwise those air LODs will be optimized away by
	 * the {@link RenderDataPointReducingList}.
	 */
	public final LongArrayList voidRenderDataContainer;
	
	/**
	 * Parallel to {@link ColumnRenderSource#renderDataContainer},
	 * each byte is an index into {@link ColumnRenderSource#texturePalette}. <br>
	 * Empty when textured LODs are disabled or this section's
	 * detail level is too low to show textures.
	 */
	public final ByteArrayList textureSetPaletteIndices;
	/**
	 * Palette index -> {@link BlockTextureRegistry} set id. <br>
	 * Index {@link BlockTextureRegistry#UNTEXTURED_ID} is always the flat "no texture" set.
	 */
	public final ShortArrayList texturePalette = new ShortArrayList();
	
	private boolean isEmpty = true;
	
	
	
	//==============//
	// constructors //
	//==============//
	//region
	
	public static ColumnRenderSource createEmpty(long pos, int maxVertSliceCount, int yOffset)
	{ return new ColumnRenderSource(pos, maxVertSliceCount, yOffset); }
	/**
	 * Creates an empty ColumnRenderSource.
	 *
	 * @param pos the relative position of the container
	 * @param maxVertSliceCount the maximum vertical size of the container
	 */
	private ColumnRenderSource(long pos, int maxVertSliceCount, int yOffset)
	{
		super(ARRAY_LIST_POOL, 1, 0, 2, 0, 0);
		
		this.pos = pos;
		this.yOffset = yOffset;
		
		this.maxVerticalSliceCount = maxVertSliceCount;
		
		int maxDatapointCount = WIDTH * WIDTH * this.maxVerticalSliceCount;
		this.renderDataContainer = this.pooledArraysCheckout.getLongArray(0, maxDatapointCount);
		this.voidRenderDataContainer = this.pooledArraysCheckout.getLongArray(1, WIDTH * WIDTH);
		
		// texture ids are only stored for high detail sections
		int textureIndexCount = texturedLodsEnabledAtDetailLevel(this.getDataDetailLevel()) ? maxDatapointCount : 0;
		this.textureSetPaletteIndices = this.pooledArraysCheckout.getByteArray(0, textureIndexCount);
		this.texturePalette.add(BlockTextureRegistry.UNTEXTURED_ID);
	}
	private static boolean texturedLodsEnabledAtDetailLevel(byte dataDetailLevel)
	{
		return Config.Client.Advanced.Graphics.Texture.enableTexturedLods.get()
			&& dataDetailLevel <= Config.Client.Advanced.Graphics.Texture.maxTexturedLodDetailLevel.get();
	}
	
	//endregion
	
	
	
	//========================//
	// datapoint manipulation //
	//========================//
	//region
	
	public long getDataPoint(int posX, int posZ, int verticalIndex) 
	{ return this.renderDataContainer.getLong(this.getDatapointIndex(posX, posZ, verticalIndex)); }
	
	public void populateColumnView(ColumnRenderView view, int posX, int posZ) throws IllegalArgumentException
	{
		int dataOffset = this.getDatapointIndex(posX, posZ, 0);
		int voidDataOffset = this.getVoidDatapointIndex(posX, posZ);
		
		// don't allow returning views that are outside this render source's bounds
		if (posX < 0 || posX >= WIDTH
			|| posZ < 0 || posZ >= WIDTH)
		{
			throw new IllegalArgumentException("Column View pos outside valid range ["+posX+","+posZ+"].");
		}
		else if (dataOffset >= this.renderDataContainer.size())
		{
			throw new IllegalArgumentException("Column Data View offset ["+dataOffset+"] greater than parent render data container ["+DhSectionPos.toString(this.pos)+"] size ["+this.renderDataContainer.size()+"].");
		}
		else if (voidDataOffset >= this.voidRenderDataContainer.size())
		{
			throw new IllegalArgumentException("Column Void View offset ["+voidDataOffset+"] greater than parent render data container ["+DhSectionPos.toString(this.pos)+"] size ["+this.voidRenderDataContainer.size()+"].");
		}
		
		view.populate(
			this.renderDataContainer, this.voidRenderDataContainer, 
			this.maxVerticalSliceCount,
			dataOffset, voidDataOffset, 
			this.maxVerticalSliceCount);
	}
	
	//endregion
	
	
	
	//==================//
	// texture handling //
	//==================//
	//region
	
	/** @return true if this section is storing texture ids */
	public boolean hasTextureSetIds() { return !this.textureSetPaletteIndices.isEmpty(); }
	
	/**
	 * @return the {@link BlockTextureRegistry}
	 *          set id for the given data point, the flat set id if this section isn't storing textures
	 */
	public short getTextureSetId(int posX, int posZ, int verticalIndex)
	{
		if (!this.hasTextureSetIds())
		{
			return BlockTextureRegistry.UNTEXTURED_ID;
		}
		
		int paletteIndex = this.textureSetPaletteIndices.getByte(this.getDatapointIndex(posX, posZ, verticalIndex)) 
			& 0xFF; // convert from signed byte to unsigned int
		return this.texturePalette.getShort(paletteIndex);
	}
	
	/**
	 * Stores the texture set id for the given data point,
	 * does nothing if this section isn't storing textures.
	 */
	public void setTextureSetId(int posX, int posZ, int verticalIndex, short textureSetId)
	{
		if (!this.hasTextureSetIds())
		{
			return;
		}
		
		this.textureSetPaletteIndices.set(
			this.getDatapointIndex(posX, posZ, verticalIndex),
			this.getOrAddPaletteIndex(textureSetId));
	}
	
	private byte getOrAddPaletteIndex(short textureSetId)
	{
		// linear search is fine, palettes hold the few dozen
		// distinct block appearances of a single 64x64 section
		// and most lookups hit the first few entries
		// TODO: Still see if we can improve this
		int paletteSize = this.texturePalette.size();
		for (int i = 0; i < paletteSize; i++)
		{
			if (this.texturePalette.getShort(i) == textureSetId)
			{
				return (byte) i;
			}
		}
		
		if (paletteSize >= MAX_TEXTURE_PALETTE_SIZE)
		{
			// sections with too many distinct blocks
			// gracefully lose textures so they can't take up every possible ID
			return BlockTextureRegistry.UNTEXTURED_ID;
		}
		
		this.texturePalette.add(textureSetId);
		return (byte) paletteSize;
	}
	
	//endregion
	
	
	
	//=====================//
	// data helper methods //
	//=====================//
	//region
	
	public byte getDataDetailLevel() { return (byte) (DhSectionPos.getDetailLevel(this.pos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL); }
	
	public boolean isEmpty() { return this.isEmpty; }
	public void markNotEmpty() { this.isEmpty = false; }
	
	/** can be used when debugging */
	public boolean hasNonVoidDataPoints()
	{
		if (this.isEmpty)
		{
			return false;
		}
		
		try (ColumnRenderView columnView = ColumnRenderView.getPooled())
		{
			for (int x = 0; x < WIDTH; x++)
			{
				for (int z = 0; z < WIDTH; z++)
				{
					this.populateColumnView(columnView, x, z);
					for (int i = 0; i < columnView.size; i++)
					{
						long dataPoint = columnView.get(i);
						if (!RenderDataPointUtil.hasZeroHeight(dataPoint))
						{
							return true;
						}
					}
				}
			}
		}
		
		return false;
	}
	
	private int getDatapointIndex(int posX, int posZ, int verticalIndex)
	{
		return (posX * WIDTH * this.maxVerticalSliceCount)
			+ (posZ * this.maxVerticalSliceCount)
			+ verticalIndex;
	}
	
	private int getVoidDatapointIndex(int posX, int posZ)
	{
		return (posX * WIDTH)
			+ posZ;
	}
	
	//endregion
	
	
	
	//==============//
	// base methods //
	//==============//
	//region
	
	@Override
	public String toString()
	{
		String LINE_DELIMITER = "\n";
		String DATA_DELIMITER = " ";
		String SUBDATA_DELIMITER = ",";
		StringBuilder stringBuilder = new StringBuilder();
		
		stringBuilder.append(DhSectionPos.toString(this.pos));
		stringBuilder.append(LINE_DELIMITER);
		
		int size = 1;
		for (int z = 0; z < size; z++)
		{
			for (int x = 0; x < size; x++)
			{
				for (int y = 0; y < this.maxVerticalSliceCount; y++)
				{
					//Converting the dataToHex
					stringBuilder.append(Long.toHexString(this.getDataPoint(x, z, y)));
					if (y != this.maxVerticalSliceCount - 1)
						stringBuilder.append(SUBDATA_DELIMITER);
				}
				
				if (x != size - 1)
					stringBuilder.append(DATA_DELIMITER);
			}
			
			if (z != size - 1)
				stringBuilder.append(LINE_DELIMITER);
		}
		return stringBuilder.toString();
	}
	
	//endregion
	
	
	
}
