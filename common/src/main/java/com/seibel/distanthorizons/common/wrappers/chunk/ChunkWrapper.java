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
package com.seibel.distanthorizons.common.wrappers.chunk;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.common.wrappers.block.BiomeWrapper;
import com.seibel.distanthorizons.common.wrappers.block.BlockStateWrapper;
import com.seibel.distanthorizons.common.wrappers.misc.MutableBlockPosWrapper;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.ChunkLightStorage;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IMutableBlockPosWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
#if MC_VER <= MC_1_7_10
import net.minecraft.block.Block;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;
import com.seibel.distanthorizons.common.backports.FakeBlockState;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
#elif MC_VER <= MC_1_12_2
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
#else
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
#endif

import com.seibel.distanthorizons.core.logging.DhLogger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

#if MC_VER >= MC_1_17_1
import net.minecraft.core.QuartPos;
#endif

#if MC_VER == MC_1_16_5
import net.minecraft.world.level.chunk.LevelChunkSection;
#endif

#if MC_VER == MC_1_17_1
import net.minecraft.world.level.chunk.LevelChunkSection;
#endif

#if MC_VER == MC_1_18_2
import net.minecraft.world.level.chunk.LevelChunkSection;
#endif

#if MC_VER == MC_1_19_2 || MC_VER == MC_1_19_4
import net.minecraft.world.level.chunk.LevelChunkSection;
#endif

#if MC_VER >= MC_1_20_1
import net.minecraft.world.level.chunk.LevelChunkSection;
#endif

#if MC_VER <= MC_1_12_2
#elif MC_VER <= MC_1_20_4
import net.minecraft.world.level.chunk.ChunkStatus;
#elif MC_VER > MC_1_12_2
import net.minecraft.world.level.chunk.status.ChunkStatus;
#endif


public class ChunkWrapper implements IChunkWrapper
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	/** can be used for interactions with the underlying chunk where creating new BlockPos objects could cause issues for the garbage collector. */
	#if MC_VER <= MC_1_7_10
	#else
	private static final ThreadLocal<BlockPos.MutableBlockPos> MUTABLE_BLOCK_POS_REF = ThreadLocal.withInitial(() -> new BlockPos.MutableBlockPos());
	#endif
	private static final ThreadLocal<MutableBlockPosWrapper> MUTABLE_BLOCK_POS_WRAPPER_REF = ThreadLocal.withInitial(() -> new MutableBlockPosWrapper());
	
	public static final Set<String> LOGGED_BLOCK_GET_ERRORS = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
	
	private static boolean heightmapThreadWarningLogged = false;
	
	#if MC_VER <= MC_1_12_2
	private final Chunk chunk;
	#else
	private final ChunkAccess chunk;
	#endif
	
	private final DhChunkPos chunkPos;
	private final ILevelWrapper wrappedLevel;
	
	private boolean isDhBlockLightCorrect = false;
	private boolean isDhSkyLightCorrect = false;
	
	private ChunkLightStorage blockLightStorage;
	private ChunkLightStorage skyLightStorage;
	
	private ArrayList<DhBlockPos> blockLightPosList = null;
	
	private int minNonEmptyHeight = Integer.MIN_VALUE;
	private int maxNonEmptyHeight = Integer.MAX_VALUE;
	
	/** will be null if we are using MC heightmaps */
	private int[][] solidHeightMap = null;
	/** will be null if we are using MC heightmaps */
	private int[][] lightBlockingHeightMap = null;
	
	#if MC_VER <= MC_1_7_10
	private final BiomeGenBase[] biomeList;
	#endif
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	/**
	 * Note: this constructor should be very
	 * fast since it will be called frequently on the MC
	 * server thread and a slow method will cause server lag.
	 */
	#if MC_VER <= MC_1_12_2
	public ChunkWrapper(Chunk chunk, ILevelWrapper wrappedLevel)
	#else
	public ChunkWrapper(ChunkAccess chunk, ILevelWrapper wrappedLevel)
	#endif
	{
		this.chunk = chunk;
		this.wrappedLevel = wrappedLevel;
		
		#if MC_VER <= MC_1_7_10
		this.chunkPos = new DhChunkPos(chunk.xPosition, chunk.zPosition);
		this.biomeList = fillBiomeMap(chunk);
		#elif MC_VER <= MC_1_21_11
		this.chunkPos = new DhChunkPos(chunk.getPos().x, chunk.getPos().z);
		#else
		this.chunkPos = new DhChunkPos(chunk.getPos().x(), chunk.getPos().z());
		#endif
	}
	
	#if MC_VER <= MC_1_7_10
	/** used for copying */
	private ChunkWrapper(ChunkWrapper that, ILevelWrapper wrappedLevel)
	{
		this.chunk = that.chunk;
		this.wrappedLevel = wrappedLevel;
		this.chunkPos = new DhChunkPos(that.chunkPos.getX(), that.chunkPos.getZ());
		this.biomeList = that.biomeList;
	}
	#endif
	
	
	
	// copiers //
	//region
	
	@Override
	public ChunkWrapper copy()
	{
		#if MC_VER <= MC_1_7_10
		return new ChunkWrapper(this, this.wrappedLevel);
		#else
		return new ChunkWrapper(this.chunk, this.wrappedLevel);
		#endif
	}

	@Override
	public ChunkWrapper copyWithLevel(ILevelWrapper levelWrapper)
	{
		#if MC_VER <= MC_1_7_10
		return new ChunkWrapper(this, levelWrapper);
		#else
		return new ChunkWrapper(this.chunk, levelWrapper);
		#endif
	}
	
	//endregion
	
	
	
	// constructor helpers //
	//region
	
	#if MC_VER <= MC_1_7_10
	private static BiomeGenBase[] fillBiomeMap(Chunk chunk)
	{
		// We must use World.getBiomeGenForCoords since mods like LOTR override those functions. Getting it from the chunk is incorrect.
		BiomeGenBase[] biomeArray = new BiomeGenBase[LodUtil.CHUNK_WIDTH * LodUtil.CHUNK_WIDTH];
		for (int x = 0; x < LodUtil.CHUNK_WIDTH; x++)
		{
			for (int z = 0; z < LodUtil.CHUNK_WIDTH; z++)
			{
				biomeArray[x * LodUtil.CHUNK_WIDTH + z] = chunk.worldObj.getBiomeGenForCoords(
					(chunk.xPosition << 4) + x, 
					(chunk.zPosition << 4) + z);
			}
		}
		return biomeArray;
	}
	#endif
	
	//endregion
	//endregion
	
	
	
	//=========//
	// getters //
	//=========//
	//region
	
	@Override
	public int getHeight() { return getHeight(this.chunk); }
	#if MC_VER <= MC_1_12_2
	public static int getHeight(Chunk chunk)
	#else
	public static int getHeight(ChunkAccess chunk)
	#endif
	{
		#if MC_VER < MC_1_17_1
		return 255;
		#else
		return chunk.getHeight();
		#endif
	}
	
	@Override
	public int getInclusiveMinBuildHeight() { return getInclusiveMinBuildHeight(this.chunk); }
	#if MC_VER <= MC_1_12_2
	public static int getInclusiveMinBuildHeight(Chunk chunk)
	#else
	public static int getInclusiveMinBuildHeight(ChunkAccess chunk)
	#endif
	{
		#if MC_VER < MC_1_17_1
		return 0;
		#elif MC_VER < MC_1_21_3
		return chunk.getMinBuildHeight();
		#else
		return chunk.getMinY();
		#endif
	}
	
	@Override
	public int getExclusiveMaxBuildHeight() { return getExclusiveMaxBuildHeight(this.chunk); }
	#if MC_VER <= MC_1_12_2
	public static int getExclusiveMaxBuildHeight(Chunk chunk) 
	#else
	public static int getExclusiveMaxBuildHeight(ChunkAccess chunk) 
	#endif
	{
		#if MC_VER <= MC_1_12_2
		return 256;
		#elif MC_VER < MC_1_21_3
		return chunk.getMaxBuildHeight();
		#else
		// +1 since Minecraft made the max value inclusive
		return chunk.getMaxY() + 1;
		#endif
	}
	
	@Override
	public int getMinNonEmptyHeight()
	{
		if (this.minNonEmptyHeight != Integer.MIN_VALUE)
		{
			return this.minNonEmptyHeight;
		}
		
		
		// default if every section is empty or missing
		this.minNonEmptyHeight = this.getInclusiveMinBuildHeight();

		// determine the lowest empty section (bottom up)
		#if MC_VER <= MC_1_12_2
		ExtendedBlockStorage[] sections = this.chunk.getBlockStorageArray();
		#else
		LevelChunkSection[] sections = this.chunk.getSections();
		#endif
		for (int index = 0; index < sections.length; index++)
		{
			if (sections[index] == null)
			{
				continue;
			}

			if (!isChunkSectionEmpty(sections[index]))
			{
				this.minNonEmptyHeight = this.getChunkSectionMinHeight(index);
				break;
			}
		}

		return this.minNonEmptyHeight;
	}
	
	
	@Override
	public int getMaxNonEmptyHeight()
	{
		if (this.maxNonEmptyHeight != Integer.MAX_VALUE)
		{
			return this.maxNonEmptyHeight;
		}
		
		
		// default if every section is empty or missing
		this.maxNonEmptyHeight = this.getExclusiveMaxBuildHeight();

		// determine the highest empty section (top down)
		#if MC_VER <= MC_1_12_2
		ExtendedBlockStorage[] sections = this.chunk.getBlockStorageArray();
		#else
		LevelChunkSection[] sections = this.chunk.getSections();
		#endif
		for (int index = sections.length-1; index >= 0; index--)
		{
			// update at each position to fix using the max height if the chunk is empty
			this.maxNonEmptyHeight = this.getChunkSectionMinHeight(index) + 16;

			if (sections[index] == null)
			{
				continue;
			}

			if (!isChunkSectionEmpty(sections[index]))
			{
				// non-empty section found
				break;
			}
		}

		return this.maxNonEmptyHeight;
	}
	#if MC_VER <= MC_1_12_2
	private static boolean isChunkSectionEmpty(ExtendedBlockStorage section)
	#else
	private static boolean isChunkSectionEmpty(LevelChunkSection section)
	#endif
	{
		#if MC_VER <= MC_1_17_1
		return section.isEmpty();
		#else
		return section.hasOnlyAir();
		#endif
	}
	private int getChunkSectionMinHeight(int index) { return (index * 16) + this.getInclusiveMinBuildHeight(); }
	
	@Override
	public void createDhHeightMaps()
	{
		if (!heightmapThreadWarningLogged
			&& !DhApi.isDhThread())
		{
			heightmapThreadWarningLogged = true;
			LOGGER.warn("ChunkWrapper Height maps created on non-DH thread ["+Thread.currentThread().getName()+"]. This may cause stuttering.");
		}
		
		
		
		this.solidHeightMap = new int[LodUtil.CHUNK_WIDTH][LodUtil.CHUNK_WIDTH];
		this.lightBlockingHeightMap = new int[LodUtil.CHUNK_WIDTH][LodUtil.CHUNK_WIDTH];
		
		for (int x = 0; x < LodUtil.CHUNK_WIDTH; x++)
		{
			for (int z = 0; z < LodUtil.CHUNK_WIDTH; z++)
			{
				int minInclusiveBuildHeight = this.getMinNonEmptyHeight();
				// if no blocks are found the height map will be at the bottom of the world
				int solidHeight = minInclusiveBuildHeight;
				int lightBlockingHeight = minInclusiveBuildHeight;
				
				
				int y = this.getMaxNonEmptyHeight(); //this.getExclusiveMaxBuildHeight();
				IBlockStateWrapper block = this.getBlockState(x, y, z);
				while (// go down until we reach the minimum build height
						y > minInclusiveBuildHeight
						// keep going until we find both height map values
						&& 
						(
							solidHeight == minInclusiveBuildHeight 
							|| lightBlockingHeight == minInclusiveBuildHeight
						)
					)
				{
					// is this block solid?
					if (solidHeight == minInclusiveBuildHeight
						&& block.isSolid())
					{
						solidHeight = y;
					}
					
					// is this block light blocking?
					if (lightBlockingHeight == minInclusiveBuildHeight
						&& block.getOpacity() != LodUtil.BLOCK_FULLY_TRANSPARENT)
					{
						lightBlockingHeight = y;
					}
					
					// get the next block down
					y--;
					block = this.getBlockState(x, y, z);
				}
				
				this.solidHeightMap[x][z] = solidHeight;
				this.lightBlockingHeightMap[x][z] = lightBlockingHeight;
			}
		}
	}
	
	@Override
	public int getSolidHeightMapValue(int xRel, int zRel) 
	{ 
		this.throwIndexOutOfBoundsIfRelativePosOutsideChunkBounds(xRel, zRel);
		
		// will be null if we want to use MC heightmaps
		if (this.solidHeightMap == null)
		{
			#if MC_VER <= MC_1_7_10
			return 255; // assume max height so we don't miss anything
			#elif MC_VER <= MC_1_12_2
			return this.chunk.getHeightValue(xRel, zRel);
			#else
			return this.chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE).getFirstAvailable(xRel, zRel);	
			#endif
		}
		else
		{
			return this.solidHeightMap[xRel][zRel];
		}
	}
	
	@Override
	public int getLightBlockingHeightMapValue(int xRel, int zRel) 
	{
		this.throwIndexOutOfBoundsIfRelativePosOutsideChunkBounds(xRel, zRel);
		
		if (this.lightBlockingHeightMap == null)
		{
			#if MC_VER <= MC_1_12_2
			return this.chunk.getHeightValue(xRel, zRel);
			#else
			return this.chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING).getFirstAvailable(xRel, zRel);
			#endif
		}
		else
		{
			return this.lightBlockingHeightMap[xRel][zRel];
		}
	}
	
	
	@Override
	public IBiomeWrapper getBiome(int relX, int relY, int relZ)
	{
		#if MC_VER <= MC_1_7_10
		BiomeGenBase biome = this.biomeList[(relX * LodUtil.CHUNK_WIDTH) + relZ];
		return BiomeWrapper.getBiomeWrapper(biome, this.wrappedLevel);
		#elif MC_VER <= MC_1_12_2
		BlockPos.MutableBlockPos blockPos = MUTABLE_BLOCK_POS_REF.get();
		blockPos.setPos(relX, relY, relZ);
		
		World world = (World) this.wrappedLevel.getWrappedMcObject();
		
		return BiomeWrapper.getBiomeWrapper(this.chunk.getBiome(blockPos, world.getBiomeProvider()), wrappedLevel);
		#elif MC_VER < MC_1_17_1
		return BiomeWrapper.getBiomeWrapper(this.chunk.getBiomes().getNoiseBiome(
				relX >> 2, relY >> 2, relZ >> 2),
				this.wrappedLevel);
		#elif MC_VER < MC_1_18_2
		return BiomeWrapper.getBiomeWrapper(this.chunk.getBiomes().getNoiseBiome(
				QuartPos.fromBlock(relX), QuartPos.fromBlock(relY), QuartPos.fromBlock(relZ)),
				this.wrappedLevel);
		#else 
		//Now returns a Holder<Biome> instead of Biome
		return BiomeWrapper.getBiomeWrapper(this.chunk.getNoiseBiome(
				QuartPos.fromBlock(relX), QuartPos.fromBlock(relY), QuartPos.fromBlock(relZ)),
				this.wrappedLevel);
		#endif
	}
	
	@Override
	public IBlockStateWrapper getBlockState(int relX, int relY, int relZ)
	{
		this.throwIndexOutOfBoundsIfRelativePosOutsideChunkBounds(relX, relY, relZ);
		
		#if MC_VER <= MC_1_7_10
		try
		{
			final Block block = this.chunk.getBlock(relX, relY, relZ);
			final int meta = this.chunk.getBlockMetadata(relX, relY, relZ);
			return BlockStateWrapper.fromBlockState(block, meta, this.wrappedLevel);
		}
		catch (Exception e)
		{
			if (LOGGED_BLOCK_GET_ERRORS.add(e.getMessage()))
			{
				LOGGER.warn("Failed to get block from chunk ["+this.chunkPos+"] at relative block pos ["+relX+","+relY+","+relZ+"], air will be used instead. This error message will only be logged once. error: ["+e.getMessage()+"].", e);
			}
			
			return BlockStateWrapper.AIR;
		}
		#else
		BlockPos.MutableBlockPos blockPos = MUTABLE_BLOCK_POS_REF.get();
		
		#if MC_VER <= MC_1_12_2
		blockPos.setPos(relX, relY, relZ);
		#else
		blockPos.setX(relX);
		blockPos.setY(relY);
		blockPos.setZ(relZ);
		#endif
		
		try
		{
			return BlockStateWrapper.fromBlockState(this.chunk.getBlockState(blockPos), this.wrappedLevel);
		}
		catch (Exception e)
		{
			if (LOGGED_BLOCK_GET_ERRORS.add(e.getMessage()))
			{
				LOGGER.warn("Failed to get block from chunk ["+this.chunkPos+"] at relative block pos ["+relX+","+relY+","+relZ+"], air will be used instead. This error message will only be logged once. error: ["+e.getMessage()+"].", e);
			}
			
			return BlockStateWrapper.AIR;
		}
		#endif
	}
	
	@Override
	public IBlockStateWrapper getBlockState(int relX, int relY, int relZ, IMutableBlockPosWrapper mcBlockPos, IBlockStateWrapper guess)
	{
		this.throwIndexOutOfBoundsIfRelativePosOutsideChunkBounds(relX, relY, relZ);
		
		#if MC_VER <= MC_1_7_10
		try
		{
			final Block block = this.chunk.getBlock(relX, relY, relZ);
			final int meta = this.chunk.getBlockMetadata(relX, relY, relZ);
			return BlockStateWrapper.fromBlockState(block, meta, this.wrappedLevel, guess);
		}
		catch (Exception e)
		{
			if (LOGGED_BLOCK_GET_ERRORS.add(e.getMessage()))
			{
				LOGGER.warn("Failed to get block from chunk ["+this.chunkPos+"] at relative block pos ["+relX+","+relY+","+relZ+"], air will be used instead. This error message will only be logged once. error: ["+e.getMessage()+"].", e);
			}
			
			return BlockStateWrapper.AIR;
		}
		#else
		BlockPos.MutableBlockPos pos = (BlockPos.MutableBlockPos)mcBlockPos.getWrappedMcObject();
		#if MC_VER <= MC_1_12_2
		pos.setPos(relX, relY, relZ);
		#else
		pos.setX(relX);
		pos.setY(relY);
		pos.setZ(relZ);
		#endif
	
		try
		{
			return BlockStateWrapper.fromBlockState(this.chunk.getBlockState(pos), this.wrappedLevel, guess);
		}
			catch (Exception e)
		{
			if (LOGGED_BLOCK_GET_ERRORS.add(e.getMessage()))
			{
				LOGGER.warn("Failed to get block from chunk ["+this.chunkPos+"] at relative block pos ["+relX+","+relY+","+relZ+"], air will be used instead. This error message will only be logged once. error: ["+e.getMessage()+"].", e);
			}
			
			return BlockStateWrapper.AIR;
		}
		#endif
	}
	
	@Override
	public IMutableBlockPosWrapper getMutableBlockPosWrapper() { return MUTABLE_BLOCK_POS_WRAPPER_REF.get(); }
	
	@Override
	public DhChunkPos getChunkPos() { return this.chunkPos; }
	
	#if MC_VER <= MC_1_12_2
	public Chunk getChunk()
	#else
	public ChunkAccess getChunk()
	#endif
	{ return this.chunk; }
	
	#if MC_VER > MC_1_12_2
	public void trySetStatus(ChunkStatus status) { trySetStatus(this.getChunk(), status); }
	/** does nothing if the chunk object doesn't support setting it's status */
	public static void trySetStatus(ChunkAccess chunk, ChunkStatus status)
	{
		if (chunk instanceof ProtoChunk)
		{
			#if MC_VER < MC_1_21_1
			((ProtoChunk) chunk).setStatus(status);
			#else
			((ProtoChunk) chunk).setPersistedStatus(status);
			#endif
		}
	}
	
	public ChunkStatus getStatus() { return getStatus(this.getChunk()); }
	public static ChunkStatus getStatus(ChunkAccess chunk)
	{
		#if MC_VER < MC_1_21_1 
		return chunk.getStatus();
		#else
		return chunk.getPersistedStatus(); 
		#endif
	}
	#endif
	
	@Override
	public int getMinBlockX() { return this.chunkPos.getMinBlockX(); }
	@Override
	public int getMinBlockZ() { return this.chunkPos.getMinBlockZ(); }
	
	
	#if MC_VER <= MC_1_7_10
	/** 
	 * Returns true if the chunk has
	 * been populated far enough for us to
	 * convert it to an LOD. <br><br>
	 * 
	 * This method is only necessary for 1.7.10 since we handle
	 * this logic in the save mixin for newer MC versions.
	 */
	public static boolean canSaveChunk(Chunk chunk)
	{
		return chunk != null
			&& chunk.isTerrainPopulated
			&& chunk.isLightPopulated;
	}
	#endif
	
	//endregion
	
	
	
	//==========//
	// lighting //
	//==========//
	//region
	
	@Override 
	public void setIsDhSkyLightCorrect(boolean isDhLightCorrect) { this.isDhSkyLightCorrect = isDhLightCorrect; }
	@Override 
	public void setIsDhBlockLightCorrect(boolean isDhLightCorrect) { this.isDhBlockLightCorrect = isDhLightCorrect; }
	
	@Override
	public boolean isDhBlockLightingCorrect() { return this.isDhBlockLightCorrect; }
	@Override
	public boolean isDhSkyLightCorrect() { return this.isDhSkyLightCorrect; }
	
	
	@Override
	public int getDhBlockLight(int relX, int y, int relZ)
	{
		this.throwIndexOutOfBoundsIfRelativePosOutsideChunkBounds(relX, y, relZ);
		return this.getBlockLightStorage().get(relX, y, relZ);
	}
	@Override
	public void setDhBlockLight(int relX, int y, int relZ, int lightValue)
	{
		this.throwIndexOutOfBoundsIfRelativePosOutsideChunkBounds(relX, y, relZ);
		this.getBlockLightStorage().set(relX, y, relZ, lightValue);
	}
	
	private ChunkLightStorage getBlockLightStorage()
	{
		if (this.blockLightStorage == null)
		{
			this.blockLightStorage = ChunkLightStorage.createBlockLightStorage(this);
		}
		return this.blockLightStorage;
	}
	public void setBlockLightStorage(ChunkLightStorage lightStorage) { this.blockLightStorage = lightStorage; }
	@Override
	public void clearDhBlockLighting() { this.getBlockLightStorage().clear(); }
	
	
	@Override
	public int getDhSkyLight(int relX, int y, int relZ)
	{
		this.throwIndexOutOfBoundsIfRelativePosOutsideChunkBounds(relX, y, relZ);
		return this.getSkyLightStorage().get(relX, y, relZ);
	}
	@Override
	public void setDhSkyLight(int relX, int y, int relZ, int lightValue)
	{
		this.throwIndexOutOfBoundsIfRelativePosOutsideChunkBounds(relX, y, relZ);
		this.getSkyLightStorage().set(relX, y, relZ, lightValue);
	}
	@Override
	public void clearDhSkyLighting() { this.getSkyLightStorage().clear(); }
	
	private ChunkLightStorage getSkyLightStorage()
	{
		if (this.skyLightStorage == null)
		{
			this.skyLightStorage = ChunkLightStorage.createSkyLightStorage(this);
		}
		return this.skyLightStorage;
	}
	public void setSkyLightStorage(ChunkLightStorage lightStorage) { this.skyLightStorage = lightStorage; }
	
	
	/** 
	 * FIXME synchronized is necessary for a rare issue where this method is called from two separate threads at the same time
	 *  before the list has finished populating.
	 */
	@Override
	public synchronized ArrayList<DhBlockPos> getWorldBlockLightPosList()
	{
		// only populate the list once
		if (this.blockLightPosList == null)
		{
			this.blockLightPosList = new ArrayList<>();
			
			// 1.12.2 and older doesn't store light blocks, so we have to bruteforce it
			#if MC_VER <= MC_1_12_2
			for (ExtendedBlockStorage section : this.chunk.getBlockStorageArray())
			{
				if (section == null || section.isEmpty())
				{
					continue;
				}
				
				int baseY = section.getYLocation();
				
				for (int x = 0; x < 16; x++)
				{
					for (int z = 0; z < 16; z++)
					{
						for (int y = 0; y < 16; y++)
						{
							#if MC_VER <= MC_1_7_10
							int lightValue = FakeBlockState.getLightEmission(section.getBlockByExtId(x, y, z), section.getExtBlockMetadata(x, y, z));
							#else
							int lightValue = section.get(x, y, z).getLightValue();
							#endif
							if (lightValue > 0)
							{
								this.blockLightPosList.add(new DhBlockPos(this.chunkPos.getMinBlockX() + x, baseY + y, this.chunkPos.getMinBlockZ() + z));
							}
						}
					}
				}
			}
			#elif MC_VER < MC_1_20_1
			this.chunk.getLights().forEach((blockPos) ->
			{
				this.blockLightPosList.add(new DhBlockPos(blockPos.getX(), blockPos.getY(), blockPos.getZ()));
			});
			#else
			this.chunk.findBlockLightSources((blockPos, blockState) ->
			{
				DhBlockPos pos = new DhBlockPos(blockPos.getX(), blockPos.getY(), blockPos.getZ());
				
				// this can be uncommented if MC decides to return relative block positions in the future instead of world positions
				//pos.mutateToChunkRelativePos(pos);
				//pos.mutateOffset(this.chunkPos.getMinBlockX(), 0, this.chunkPos.getMinBlockZ(), pos);
				
				this.blockLightPosList.add(pos);
			});
			#endif
		}
		
		return this.blockLightPosList;
	}
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	@Override
	public String toString() { return this.chunk.getClass().getSimpleName() + this.chunkPos; }
	
	// commented out since we don't currently need
	// any special hashing logic
	//@Override 
	//public int hashCode()
	//{
	//	if (this.blockBiomeHashCode == 0)
	//	{
	//		this.blockBiomeHashCode = this.getBlockBiomeHashCode();
	//	}
	//	
	//	return this.blockBiomeHashCode;
	//}
	
	//endregion
	
	
	
}
