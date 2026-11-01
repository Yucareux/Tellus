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

package com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding;

import java.nio.ByteBuffer;
import java.util.*;

import com.seibel.distanthorizons.api.enums.config.EDhApiGrassSideRendering;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiDebugRendering;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.ByteBufferCheckoutWrapper;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListPool;
import com.seibel.distanthorizons.coreapi.util.ColorUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import org.jetbrains.annotations.Nullable;

/**
 * Used to create the quads before they are converted to render-able buffers. <br><br>
 *
 * Note: the magic number 6 you see throughout this method represents the number of sides on a cube.
 */
public class LodQuadBuilder implements AutoCloseable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	
	/** ThreadLocal is the simplest way to allow each LOD loading thread to have their own builder */
	private static final ThreadLocal<LodQuadBuilder> THREAD_LOCAL = ThreadLocal.withInitial(LodQuadBuilder::new);
	
	public static final PhantomArrayListPool ARRAY_LIST_POOL = new PhantomArrayListPool("LodQuadBuilder");
	
	/** the number of bytes for a single vertex */
	public static final int BYTES_PER_VERTEX = 16;
	public static final int BYTES_PER_QUAD = BYTES_PER_VERTEX * 4;
	
	public static final int[][][] DIRECTION_VERTEX_IBO_QUAD = new int[][][]
	//region
		{
			// X,Z //
			{ // UP
				{1, 0}, // 0
				{1, 1}, // 1
				{0, 1}, // 2
				{0, 0}, // 3
			},
			{ // DOWN
				{0, 0}, // 0
				{0, 1}, // 1
				{1, 1}, // 2
				{1, 0}, // 3
			},
			
			// X,Y //
			{ // NORTH
				{0, 0}, // 0
				{0, 1}, // 1
				{1, 1}, // 2
				
				{1, 0}, // 3
			},
			{ // SOUTH
				{1, 0}, // 0
				{1, 1}, // 1
				{0, 1}, // 2
				
				{0, 0}, // 3
			},
			
			// Z,Y //
			{ // WEST
				{0, 0}, // 0
				{1, 0}, // 1
				{1, 1}, // 2
				
				{0, 1}, // 3
			},
			{ // EAST
				{0, 1}, // 0
				{1, 1}, // 1
				{1, 0}, // 2
				
				{0, 0}, // 3
			},
		};
	//endregion
	
	
	
	@SuppressWarnings("unchecked")
	private final ArrayList<BufferQuad>[] opaqueQuads = (ArrayList<BufferQuad>[]) new ArrayList[6];
	@SuppressWarnings("unchecked")
	private final ArrayList<BufferQuad>[] transparentQuads = (ArrayList<BufferQuad>[]) new ArrayList[6];
	
	/** 
	 * Caching the BufferQuad objects reduces overhead slightly. <br>
	 * Caching is handled per builder (vs globally in {@link BufferQuad} itself) 
	 * to prevent concurrency overhead.
	 */
	private final ArrayList<BufferQuad> bufferQuadCacheList = new ArrayList<>();
	
	private boolean doTransparency;
	private IClientLevelWrapper clientLevelWrapper;
	
	private EDhApiDebugRendering debugRenderingMode;
	private EDhApiGrassSideRendering grassSideRenderingMode;
	
	private int premergeCount = 0;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private LodQuadBuilder() 
	{
		for (int i = 0; i < 6; i++)
		{
			this.opaqueQuads[i] = new ArrayList<>();
			this.transparentQuads[i] = new ArrayList<>();
		}
	}
	
	public static LodQuadBuilder getBuilder(boolean doTransparency, IClientLevelWrapper clientLevelWrapper)
	{
		LodQuadBuilder builder = THREAD_LOCAL.get();
		builder.set(doTransparency, clientLevelWrapper);
		return builder;
	}
	private void set(boolean doTransparency, IClientLevelWrapper clientLevelWrapper)
	{
		this.doTransparency = doTransparency;
		
		this.clientLevelWrapper = clientLevelWrapper;
		
		this.debugRenderingMode = Config.Client.Advanced.Debugging.debugRenderingColors.get();
		this.grassSideRenderingMode = Config.Client.Advanced.Graphics.Quality.grassSideRendering.get();
		
		this.premergeCount = 0;
	}
	
	//endregion
	
	
	
	//===========//
	// add quads //
	//===========//
	//region
	
	public void addQuadAdj(
		EDhDirection dir, 
		short x, short y, short z,
		short width, short height,
		int color, short faceTextureId,
		byte irisBlockMaterialId, byte skyLight, byte blockLight)
	{
		if (dir == EDhDirection.DOWN)
		{
			throw new IllegalArgumentException("addQuadAdj() is only for adj direction! Not UP or Down!");
		}
		
		
		ArrayList<BufferQuad> quadList;
		if (this.doTransparency && ColorUtil.getAlpha(color) < 255)
		{
			quadList = this.transparentQuads[dir.faceIndex];
		}
		else
		{
			quadList = this.opaqueQuads[dir.faceIndex]; 
		}
		
		BufferQuad quad = this.getOrCreateBufferQuad();
		quad.set(x, y, z, width, height, color, faceTextureId, irisBlockMaterialId, skyLight, blockLight, dir);
		if (!quadList.isEmpty()
			&& (
				quadList.get(quadList.size() - 1).tryMerge(quad, BufferMergeDirectionEnum.EastWest)
				|| quadList.get(quadList.size() - 1).tryMerge(quad, BufferMergeDirectionEnum.NorthSouthOrUpDown))
			)
		{
			this.premergeCount++;
			return;
		}
		
		quadList.add(quad);
	}
	
	// XZ
	public void addQuadUp(
		short minX, short maxY, short minZ, 
		short blockWidth, 
		int color, short faceTextureId,
		byte irisBlockMaterialId, 
		byte skylight, byte blocklight)
	{
		boolean isTransparent = (this.doTransparency && ColorUtil.getAlpha(color) < 255);
		ArrayList<BufferQuad> quadList = isTransparent 
				? this.transparentQuads[EDhDirection.UP.faceIndex] 
				: this.opaqueQuads[EDhDirection.UP.faceIndex];
		
		BufferQuad quad = this.getOrCreateBufferQuad();
		quad.set(minX, maxY, minZ, blockWidth, blockWidth, color, faceTextureId, irisBlockMaterialId, skylight, blocklight, EDhDirection.UP);
		quadList.add(quad);
	}
	
	public void addQuadDown(
		short x, short y, short z, 
		short blockWidth, 
		int color, short faceTextureId,
		byte irisBlockMaterialId, 
		byte skylight, byte blocklight)
	{
		ArrayList<BufferQuad> quadArray = (this.doTransparency && ColorUtil.getAlpha(color) < 255)
				? this.transparentQuads[EDhDirection.DOWN.faceIndex]
				: this.opaqueQuads[EDhDirection.DOWN.faceIndex];
		
		BufferQuad quad = this.getOrCreateBufferQuad();
		quad.set(x, y, z, blockWidth, blockWidth, color, faceTextureId, irisBlockMaterialId, skylight, blocklight, EDhDirection.DOWN);
		quadArray.add(quad);
	}
	
	//endregion
	
	
	
	//=================//
	// data finalizing //
	//=================//
	//region
	
	/** Uses Greedy meshing to merge this builder's Quads. */
	public void mergeQuads()
	{
		long mergeCount = 0; // can be used for debugging
		long preQuadsCount = this.getCurrentOpaqueQuadsCount() + this.getCurrentTransparentQuadsCount();
		if (preQuadsCount <= 1)
		{
			return;
		}
		
		for (int directionIndex = 0; directionIndex < 6; directionIndex++)
		{
			mergeCount += mergeQuadsInternal(this.opaqueQuads, directionIndex, BufferMergeDirectionEnum.EastWest);
			if (this.doTransparency)
			{
				mergeCount += mergeQuadsInternal(this.transparentQuads, directionIndex, BufferMergeDirectionEnum.EastWest);
			}
			
			
			// only run the second merge if the face is the top or bottom
			if (directionIndex == EDhDirection.UP.faceIndex 
				|| directionIndex == EDhDirection.DOWN.faceIndex)
			{
				mergeCount += mergeQuadsInternal(this.opaqueQuads, directionIndex, BufferMergeDirectionEnum.NorthSouthOrUpDown);
				if (this.doTransparency)
				{
					mergeCount += mergeQuadsInternal(this.transparentQuads, directionIndex, BufferMergeDirectionEnum.NorthSouthOrUpDown);
				}
			}
		}
		
		//long postQuadsCount = this.getCurrentOpaqueQuadsCount() + this.getCurrentTransparentQuadsCount();
		//LOGGER.trace("Merged "+mergeCount+"/"+preQuadsCount+"("+(mergeCount / (double) preQuadsCount)+") quads");
	}
	
	/** Merges all of this builder's quads for the given directionIndex (up, down, left, etc.) in the given direction */
	private static long mergeQuadsInternal(ArrayList<BufferQuad>[] list, int directionIndex, BufferMergeDirectionEnum mergeDirection)
	{
		if (list[directionIndex].size() <= 1)
		{
			return 0;
		}
		
		list[directionIndex].sort((objOne, objTwo) -> objOne.compare(objTwo, mergeDirection));
		
		long mergeCount = 0;
		ListIterator<BufferQuad> iter = list[directionIndex].listIterator();
		BufferQuad currentQuad = iter.next();
		while (iter.hasNext())
		{
			BufferQuad nextQuad = iter.next();
			
			if (currentQuad.tryMerge(nextQuad, mergeDirection))
			{
				// merge successful, attempt to merge the next quad
				mergeCount++;
				iter.set(null);
			}
			else
			{
				// merge fail, move on to the next quad
				currentQuad = nextQuad;
			}
		}
		list[directionIndex].removeIf(Objects::isNull);
		return mergeCount;
	}
	
	//endregion
	
	
	
	//==============//
	// buffer setup //
	//==============//
	//region
	
	public ArrayList<ByteBuffer> makeOpaqueVertexBuffers(PhantomArrayListCheckout checkout) { return this.makeVertexBuffers(checkout, this.opaqueQuads); }
	public ArrayList<ByteBuffer> makeTransparentVertexBuffers(PhantomArrayListCheckout checkout) { return this.makeVertexBuffers(checkout, this.transparentQuads); }
	private ArrayList<ByteBuffer> makeVertexBuffers(PhantomArrayListCheckout checkout, ArrayList<BufferQuad>[] quadList)
	{
		ArrayList<ByteBuffer> byteBufferList = new ArrayList<>(3);
		
		int byteBufferSize = getMaxBufferByteSize();
		
		ByteBuffer buffer = null;
		for (int directionIndex = 0; directionIndex < 6; directionIndex++)
		{
			// ignore empty directions
			if (quadList[directionIndex].isEmpty())
			{
				continue;
			}
			
			// put all the quads in this direction into the buffer
			for (int quadIndex = 0; quadIndex < quadList[directionIndex].size(); quadIndex++)
			{
				// if this is the first iteration or the buffer is full, 
				// create a new buffer
				if (buffer == null 
					|| buffer.remaining() < BYTES_PER_QUAD)
				{
					if ((byteBufferList.size() + 1) > checkout.getByteBufferWrapperCount())
					{
						ByteBufferCheckoutWrapper wrapper = new ByteBufferCheckoutWrapper(byteBufferSize);
						checkout.addByteBufferWrapper(wrapper);
						buffer = wrapper.buffer;
					}
					else
					{
						buffer = checkout.getByteBuffer(byteBufferList.size(), byteBufferSize);
					}
					
					byteBufferList.add(buffer);
				}
				
				this.putQuad(buffer, quadList[directionIndex].get(quadIndex));
			}
		}
		
		// rewind all the buffers so they can be read from
		for (int i = 0; i < byteBufferList.size(); i++)
		{
			buffer = byteBufferList.get(i);
			buffer.limit(buffer.position());
			buffer.rewind();
		}
		
		return byteBufferList;
	}
	private void putQuad(ByteBuffer bb, BufferQuad quad)
	{
		int[][] quadBase = DIRECTION_VERTEX_IBO_QUAD[quad.direction.faceIndex];
		short widthEastWest = quad.widthEastWest;
		short widthNorthSouth = quad.widthNorthSouthOrHeight;
		byte normalIndex = (byte) quad.direction.faceIndex;
		EDhDirection.Axis axis = quad.direction.axis;
		for (int i = 0; i < quadBase.length; i++)
		{
			short dx, dy, dz;
			int mx, my, mz;
			switch (axis)
			{
				case X: // ZY
					dx = 0;
					dy = quadBase[i][1] == 1 ? widthNorthSouth : 0;
					dz = quadBase[i][0] == 1 ? widthEastWest : 0;
					mx = 0;
					my = quadBase[i][1] == 1 ? 1 : -1;
					mz = quadBase[i][0] == 1 ? 1 : -1;
					break;
				case Y: // XZ
					dx = quadBase[i][0] == 1 ? widthEastWest : 0;
					dy = 0;
					dz = quadBase[i][1] == 1 ? widthNorthSouth : 0;
					mx = quadBase[i][0] == 1 ? 1 : -1;
					my = 0;
					mz = quadBase[i][1] == 1 ? 1 : -1;
					break;
				case Z: // XY
					dx = quadBase[i][0] == 1 ? widthEastWest : 0;
					dy = quadBase[i][1] == 1 ? widthNorthSouth : 0;
					dz = 0;
					mx = quadBase[i][0] == 1 ? 1 : -1;
					my = quadBase[i][1] == 1 ? 1 : -1;
					mz = 0;
					break;
				default:
					throw new IllegalArgumentException("Invalid Axis enum: " + axis);
			}
			
			
			int color = quad.color;
			
			// use custom side color logic for grass blocks
			if (quad.irisBlockMaterialId == EDhApiBlockMaterial.GRASS.index)
			{
				// only use dirt colors if debug rendering is disabled
				if (this.debugRenderingMode == EDhApiDebugRendering.OFF)
				{
					// determine if any custom coloring logic should be used
					if (this.grassSideRenderingMode != EDhApiGrassSideRendering.AS_GRASS)
					{
						// only change the vertex color if it's on the side or bottom
						if (quad.direction.axis.isHorizontal() || quad.direction == EDhDirection.DOWN)
						{
							if (this.grassSideRenderingMode == EDhApiGrassSideRendering.AS_DIRT
								// if we want the color to fade, only apply the dirt color to the bottom vertices
								|| (this.grassSideRenderingMode == EDhApiGrassSideRendering.FADE_TO_DIRT && quadBase[i][1] == 0)
								// always render the bottom as dirt
								|| quad.direction == EDhDirection.DOWN)
							{
								// for horizontal and bottom faces of grass blocks, use the  dirt color to
								// prevent green cliff walls
								color = this.clientLevelWrapper.getDirtBlockColor();
								color = ColorUtil.applyShade(color, this.clientLevelWrapper.getShade(quad.direction));
							}
						}
					}
				}
			}
			
			
			this.putVertex(bb, (short) (quad.x + dx), (short) (quad.y + dy), (short) (quad.z + dz),
					quad.hasError ? ColorUtil.RED : color,
					quad.hasError ? 0 : normalIndex,
					quad.hasError ? 0 : quad.irisBlockMaterialId,
					quad.hasError ? 15 : quad.skyLight,
					quad.hasError ? 15 : quad.blockLight,
					quad.hasError ? 0 : quad.textureTileId,
					mx, my, mz);
		}
	}
	private void putVertex(
		ByteBuffer bb, 
		short x, short y, short z, 
		int color, 
		byte normalIndex, byte irisBlockMaterialId, 
		byte skylight, byte blocklight,
		short textureTileId, 
		int mx, int my, int mz)
	{
		bb.putShort(x);
		bb.putShort(y);
		bb.putShort(z);
		
		short meta = 0;
		{
			skylight %= 16;
			blocklight %= 16;
			meta |= (short) (skylight | (blocklight << 4));
			
			byte mircoOffset = 0;
			// mirco offset which is a xyz 2bit value
			// 0b00 = no offset
			// 0b01 = positive offset
			// 0b11 = negative offset
			// format is: 0b00zzyyxx
			if (mx != 0) { mircoOffset |= (byte) (mx > 0 ? 0b01 : 0b11); }
			if (my != 0) { mircoOffset |= (byte) (my > 0 ? 0b0100 : 0b1100); }
			if (mz != 0) { mircoOffset |= (byte) (mz > 0 ? 0b010000 : 0b110000); }
			meta |= (short) (mircoOffset << 8);
		}
		bb.putShort(meta);
		
		byte r = (byte) ColorUtil.getRed(color);
		byte g = (byte) ColorUtil.getGreen(color);
		byte b = (byte) ColorUtil.getBlue(color);
		byte a = this.doTransparency ? (byte) ColorUtil.getAlpha(color) : (byte) 255;
		bb.put(r);
		bb.put(g);
		bb.put(b);
		bb.put(a);
		
		// Block ID and normal index are used by the Iris format
		bb.put(irisBlockMaterialId);
		bb.put(normalIndex);
		
		// These two bytes were originally padding to keep the vertex a multiple of 4
		// and are treated as padding by Iris's mirror of this format, 
		// so the tile id is invisible to shader packs.
		bb.putShort(textureTileId);
	}
	
	//endregion
	
	
	
	//=========//
	// getters //
	//=========//
	//region
	
	public int getCurrentOpaqueQuadsCount()
	{
		int i = 0;
		for (ArrayList<BufferQuad> quadList : this.opaqueQuads)
		{
			i += quadList.size();
		}
		
		return i;
	}
	public int getCurrentTransparentQuadsCount()
	{
		if (!this.doTransparency)
		{
			return 0;
		}
		
		int i = 0;
		for (ArrayList<BufferQuad> quadList : this.transparentQuads)
		{
			i += quadList.size();
		}
		
		return i;
	}
	
	private static int maxBufferByteSize = -1;
	/** 
	 * The max number of bytes we allow for a single Vertex buffer.
	 * If an LOD has more data than this it will be split
	 * up into multiple buffers.
	 */
	public static int getMaxBufferByteSize()
	{
		if (maxBufferByteSize != -1)
		{
			return maxBufferByteSize;
		}
		
		// 2 MB
		// note: this is relatively small (10 MB was the previous max) to reduce stuttering
		// during the upload process by having smaller upload steps
		int maxVboByteSize = 2 * 1024 * 1024; 
		
		int maxQuadsPerBuffer = maxVboByteSize / BYTES_PER_QUAD;
		// integer truncation to remove decimal component
		int fullSizedBuffer = maxQuadsPerBuffer * BYTES_PER_QUAD;
		
		maxBufferByteSize = fullSizedBuffer;
		
		return fullSizedBuffer;
	}
	
	//endregion
	
	
	
	//=====================//
	// buffer quad pooling //
	//=====================//
	//region
	
	private BufferQuad getOrCreateBufferQuad()
	{
		// start from the back of the list so we don't have
		// to move the array around
		int index = bufferQuadCacheList.size() - 1;
		if (index < 0)
		{
			// cache empty, create a new object
			return new BufferQuad();
		}
		
		BufferQuad quad = bufferQuadCacheList.remove(index);
		if (quad != null) // shouldn't happen, but just in case
		{
			return quad;
		}
		
		return new BufferQuad();
	}
	
	private static void returnQuadsToCache(ArrayList<BufferQuad> quadCache, ArrayList<BufferQuad>[] quadsToReturn)
	{
		for (int i = 0; i < quadsToReturn.length; i++)
		{
			// manual add and loop to reduce GC pressure due to addAll() doing unnecessary
			// array copies
			for (int j = 0; j < quadsToReturn[i].size(); j++)
			{
				quadCache.add(quadsToReturn[i].get(j));
			}
			
			quadsToReturn[i].clear();
		}
	}
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	// can be used/closed multiple times
	@Override 
	public void close()
	{
		returnQuadsToCache(this.bufferQuadCacheList, this.opaqueQuads);
		returnQuadsToCache(this.bufferQuadCacheList, this.transparentQuads);
	}
	
	//endregion
	
	
	
}
