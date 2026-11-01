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

package com.seibel.distanthorizons.common.wrappers.worldGeneration.mimicObject;

#if MC_VER > MC_1_12_2
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import com.seibel.distanthorizons.common.wrappers.McObjectConverter;
import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper;
import com.seibel.distanthorizons.common.wrappers.worldGeneration.DhChunkGenerator;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.util.LodUtil;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SpawnerBlock;
import com.seibel.distanthorizons.core.logging.DhLogger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Cursor3D;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ColorResolver;
#if MC_VER >= MC_1_17_1
import net.minecraft.world.level.LevelHeightAccessor;
#endif
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;

#if MC_VER <= MC_1_20_4
import net.minecraft.world.level.chunk.ChunkStatus;
#else
import net.minecraft.world.level.chunk.status.*;
#endif

#if MC_VER >= MC_1_21_1
import net.minecraft.util.StaticCache2D;
import com.google.common.collect.ImmutableList;
import net.minecraft.server.level.GenerationChunkHolder;
#endif

#if MC_VER >= MC_1_18_2
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;
#endif


public class DhLitWorldGenRegion extends WorldGenRegion
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static ChunkStatus debugTriggeredForStatus = null;
	
	
	public final ServerLevel serverLevel;
	public final DummyLightEngine lightEngine;
	public final DhChunkGenerator.IEmptyChunkRetrievalFunc generator;
	public final int writeRadius;
	public final int size;
	
	private final DhChunkPos firstPos;
	private final List<ChunkAccess> chunkCacheList;
	private final Long2ObjectOpenHashMap<ChunkAccess> chunkMap = new Long2ObjectOpenHashMap<ChunkAccess>();
	
	/**
	 * Present to reduce the chance that we accidentally break underlying MC code that isn't thread safe, 
	 * specifically: "it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap.getAndMoveToFirst()"
	 */
	private final ReentrantLock getChunkLock = new ReentrantLock();
	
	#if MC_VER < MC_1_18_2
	private ChunkPos overrideCenterPos = null;
	
	public void setOverrideCenter(ChunkPos pos) { overrideCenterPos = pos; }
	#if MC_VER < MC_1_17_1
	@Override
	public int getCenterX() 
	{
		return overrideCenterPos==null ? super.getCenterX() : overrideCenterPos.x;
	}
	@Override
	public int getCenterZ() 
	{
		return overrideCenterPos==null ? super.getCenterX() : overrideCenterPos.z;
	}
	#else
	@Override
	public ChunkPos getCenter()
	{
		return overrideCenterPos == null ? super.getCenter() : overrideCenterPos;
	}
	#endif
	#endif
	
	
	
	public DhLitWorldGenRegion(
			int centerChunkX, int centerChunkZ,
			ChunkAccess centerChunk,
			ServerLevel serverLevel, DummyLightEngine lightEngine,
			List<ChunkAccess> chunkList, ChunkStatus chunkStatus, int writeRadius,
			DhChunkGenerator.IEmptyChunkRetrievalFunc generator)
	{
		#if MC_VER == MC_1_16_5
		super(serverLevel, chunkList);
		#elif MC_VER < MC_1_21_1
		super(serverLevel, chunkList, chunkStatus, writeRadius);
		#else
		super(serverLevel,
			StaticCache2D.create(
				centerChunkX, centerChunkZ,
				writeRadius * 2, (x, z) -> new DhGenerationChunkHolder(new ChunkPos(x, z))),
			new ChunkStep(
				chunkStatus,
				// reverse is needed because MC uses the index of the chunkStatus to determine how many items are in the list instead of the actual list count
				new ChunkDependencies(ImmutableList.copyOf(ChunkStatus.getStatusList()).reverse()),
				new ChunkDependencies(ImmutableList.copyOf(ChunkStatus.getStatusList()).reverse()),
				// write radius is doubled here to prevent a warning on MC's end where it doesn't
				// want to write to one of our edge chunks (as of MC 26.2.0)
				writeRadius * 2,
				(WorldGenContext var1, ChunkStep var2, StaticCache2D<GenerationChunkHolder> var3, ChunkAccess var4) -> null),
			centerChunk);
		#endif
		
		this.firstPos = McObjectConverter.convert(chunkList.get(0).getPos());
		this.serverLevel = serverLevel;
		this.generator = generator;
		this.lightEngine = lightEngine;
		this.writeRadius = writeRadius;
		this.chunkCacheList = chunkList;
		this.size = Mth.floor(Math.sqrt(chunkList.size()));
	}
	
	
	
	#if MC_VER >= MC_1_17_1
	// Bypass BCLib mixin overrides.
	@Override
	public boolean ensureCanWrite(BlockPos blockPos)
	{
		DhChunkPos chunkPos = McObjectConverter.convert(this.getCenter());
		
		int sectionCoordX = SectionPos.blockToSectionCoord(blockPos.getX());
		int sectionCoordZ = SectionPos.blockToSectionCoord(blockPos.getZ());
		
		// TODO what do these "abs" positions mean?
		int absX = Math.abs(chunkPos.getX() - sectionCoordX);
		int absZ = Math.abs(chunkPos.getZ() - sectionCoordZ);
		if (absX > this.writeRadius
			|| absZ > this.writeRadius)
		{
			return false;
		}
		
		#if MC_VER >= MC_1_18_2
		ChunkAccess center = this.getChunk(chunkPos.getX(), chunkPos.getZ());
		if (center.isUpgrading())
		{
			LevelHeightAccessor levelHeightAccessor = center.getHeightAccessorForGeneration();
			
			int minY;
			int maxY;
			#if MC_VER < MC_1_21_3
			minY = levelHeightAccessor.getMinBuildHeight();
			maxY = levelHeightAccessor.getMaxBuildHeight();
			#else
			minY = levelHeightAccessor.getMinY();
			maxY = levelHeightAccessor.getMaxY();
			#endif
			
			if (blockPos.getY() < minY || blockPos.getY() >= maxY)
			{
				return false;
			}
		}
		#endif
		
		return true;
	}
	#endif
	
	#if MC_VER >= MC_1_18_2
	@Override
	@NotNull
	public LevelTickAccess<Block> getBlockTicks()
	{
		// DH world gen doesn't need ticking, so return the BlackholeTickAccess list (which causes all ticks to be ignored).
		// If this isn't done the server may attempt to tick chunks outside the vanilla render distance,
		// which can throw warnings or cause other issues
		return BlackholeTickAccess.emptyLevelList();
	}
	
	@Override
	@NotNull
	public LevelTickAccess<Fluid> getFluidTicks() { return BlackholeTickAccess.emptyLevelList(); }
	#endif
	
	// Skip updating the related tile entities
	@Override
	public boolean setBlock(BlockPos blockPos, BlockState blockState, int i, int j)
	{
		ChunkAccess chunkAccess = this.getChunk(blockPos);
		if (chunkAccess instanceof LevelChunk)
		{
			return true;
		}
		
		#if MC_VER < MC_1_21_5
		chunkAccess.setBlockState(blockPos, blockState, /*isBlockMoving*/false);
		#else
		chunkAccess.setBlockState(blockPos, blockState, /*flags*/0);
		#endif
		
		// This is for post ticking for water on gen and stuff like that. Not enabled
		// for now.
		// if (blockState.hasPostProcess(this, blockPos))
		// this.getChunk(blockPos).markPosForPostprocessing(blockPos);
		return true;
	}
	
	// Skip Dropping the item on destroy
	@Override
	public boolean destroyBlock(BlockPos blockPos, boolean bl, @Nullable Entity entity, int i)
	{
		BlockState blockState = this.getBlockState(blockPos);
		if (blockState.isAir())
		{
			return false;
		}
		return this.setBlock(blockPos, Blocks.AIR.defaultBlockState(), 3, i);
	}
	
	// Skip BlockEntity stuff. It aren't really needed
	@Override
	public BlockEntity getBlockEntity(BlockPos blockPos)
	{
		BlockState blockState = this.getBlockState(blockPos);
		
		// This is a bypass for the spawner block since MC complains about not having it
		#if MC_VER >= MC_1_17_1
		if (blockState.getBlock() instanceof SpawnerBlock)
		{
			return ((EntityBlock) blockState.getBlock()).newBlockEntity(blockPos, blockState);
		}
		else return null;
		#else
		if (blockState.getBlock() instanceof SpawnerBlock) {
			return ((EntityBlock) blockState.getBlock()).newBlockEntity(this);
		} else return null;
		#endif
	}
	
	/**
	 * This needs to be manually overridden to make sure Lithium 0.11.2 and lower
	 * don't try to get null chunks. <br><br>
	 *
	 * Problematic Lithium code was removed in 0.13.0 (MC 1.21.1) and higher: <br>
	 * https://github.com/CaffeineMC/lithium-fabric/commit/b7cfd53a1ed0197e1d13dea2799b898eb52ecab3
	 */
	@NotNull
	@Override
	public BlockState getBlockState(BlockPos blockPos)
	{
		int chunkX = SectionPos.blockToSectionCoord(blockPos.getX());
		int chunkZ = SectionPos.blockToSectionCoord(blockPos.getZ());
		
		// No one should be trying to a block pos 1 million blocks
		// above or below the world height.
		// If that happens that likely means a mod is assuming DH populated the
		// world/chunk in a certain way and that assumption isn't being met.
		if (blockPos.getY() < -1_000_000
			|| blockPos.getY() > 1_000_000)
		{
			// Throwing an exception here is done to break out of potential
			// infinite loops where a mod attempts to walk down the level to find a specific block type.
			// Example from WilderWild:
			// https://github.com/FrozenBlock/WilderWild/blob/1eaaaa2179d837aac6ece002ff795eed665cd7b7/src/main/java/net/frozenblock/wilderwild/worldgen/impl/feature/SnowBlanketFeature.java#L95
			throw new ArrayIndexOutOfBoundsException(
				"Attempted to getBlockState outside the DH defined Y bounds [-1_000_000, 1_000_000]: ["+blockPos+"]. " +
					"This is likely a mod compatibility issue, " +
					"there is no reason to try getting a block this far outside the world during world gen.");
		}
		
		return this.getChunk(chunkX, chunkZ).getBlockState(blockPos);
	}
	
	/** Skip BlockEntity stuff. They aren't needed for our use case. */
	@Override
	public boolean addFreshEntity(@NotNull Entity entity) { return true; }
	
	// Allays have empty chunks even if it's outside the worldGenRegion
	// @Override
	// public boolean hasChunk(int i, int j) {
	// return true;
	// }
	
	// Override to ensure no other mod mixins cause skipping the overrided
	// getChunk(...)
	@Override
	public @NotNull ChunkAccess getChunk(int chunkX, int chunkZ)
	{
		try
		{
			// lock is to prevent issues with underlying MC code that doesn't support multithreading
			this.getChunkLock.lock();
			return this.getChunk(chunkX, chunkZ, ChunkStatus.EMPTY);
		}
		finally
		{
			this.getChunkLock.unlock();
		}
	}
	
	// Override to ensure no other mod mixins cause skipping the overrided
	// getChunk(...)
	@Override
	public @NotNull ChunkAccess getChunk(int chunkX, int chunkZ, @NotNull ChunkStatus chunkStatus)
	{
		try
		{
			// lock is to prevent issues with underlying MC code that doesn't support multithreading
			this.getChunkLock.lock();
			
			ChunkAccess chunk = this.getChunk(chunkX, chunkZ, chunkStatus, true);
			if (chunk == null)
			{
				LodUtil.assertNotReach("getChunk shouldn't return null values");
			}
			return chunk;
		}
		finally
		{
			this.getChunkLock.unlock();
		}
	}
	
	/** Allows creating empty chunks even if they're outside the worldGenRegion */
	@Override
	@Nullable
	public ChunkAccess getChunk(int chunkX, int chunkZ, @NotNull ChunkStatus chunkStatus, boolean returnNonNull)
	{
		ChunkAccess chunk = this.getChunkAccess(chunkX, chunkZ, chunkStatus, returnNonNull);
		if (chunk instanceof LevelChunk)
		{
			chunk = new ImposterProtoChunk((LevelChunk) chunk #if MC_VER >= MC_1_18_2 ,/* allow writes */ false #endif );
		}
		return chunk;
	}
	
	/**
	 * @param returnNonNull if true this method will always return a non-null chunk,
	 *                      if false it will return null if no chunk exists at the given position with the given status 
	 */
	private ChunkAccess getChunkAccess(int chunkX, int chunkZ, ChunkStatus chunkStatus, boolean returnNonNull)
	{
		ChunkAccess chunk = null;
		if (this.dhHasChunk(chunkX, chunkZ))
		{
			chunk = this.dhGetChunk(chunkX, chunkZ);
		}
		
		if (chunk != null
			&& ChunkWrapper.getStatus(chunk).isOrAfter(chunkStatus))
		{
			return chunk;
		}
		else if (!returnNonNull)
		{
			// no chunk found with the necessary status and null return values are allowed
			return null;
		}
		
		
		// we need a non-null chunk
		if (chunk == null)
		{
			// check memory
			
			long chunkPosAsLong;
			#if MC_VER <= MC_1_21_11
			chunkPosAsLong = ChunkPos.asLong(chunkX, chunkZ);
			#else
			chunkPosAsLong = ChunkPos.pack(chunkX, chunkZ);
			#endif
			
			chunk = this.chunkMap.get(chunkPosAsLong);
			if (chunk == null)
			{
				// chunk isn't in memory, generate a new one
				chunk = this.generator.getChunk(chunkX, chunkZ);
				if (chunk == null)
				{
					throw new NullPointerException("The provided generator should not return null!");
				}
				this.chunkMap.put(chunkPosAsLong, chunk);
			}
		}
		
		if (chunkStatus != ChunkStatus.EMPTY
			&& chunkStatus != debugTriggeredForStatus)
		{
			// logger disabled since this doesn't seem to significantly harm anything
			// and it causes more confusion to end users when they see it in the log
			//LOGGER.info("WorldGen requiring [" + chunkStatus + "]"
			//		+ " is outside the expected range. Returning EMPTY chunk.");
			debugTriggeredForStatus = chunkStatus;
		}
		
		return chunk;
	}
	
	/** Use this instead of super.hasChunk() to bypass C2ME concurrency checks */
	public boolean dhHasChunk(int x, int z)
	{
		int xOffset = x - this.firstPos.getX();
		int zOffset = z - this.firstPos.getZ();
		return zOffset >= 0 && zOffset < this.size
			&& xOffset >= 0 && xOffset < this.size;
	}
	
	/** Use this instead of super.getChunk() to bypass C2ME concurrency checks */
	private ChunkAccess dhGetChunk(int x, int z)
	{
		int xOffset = x - this.firstPos.getX();
		int zOffset = z - this.firstPos.getZ();
		return this.chunkCacheList.get(xOffset + zOffset * this.size);
	}
	
	
	/** Overriding allows us to use our own lighting engine */
	@Override
	public @NotNull LevelLightEngine getLightEngine() { return this.lightEngine; }
	
	/** Overriding allows us to use our own lighting engine */
	@Override
	public int getBrightness(@NotNull LightLayer lightLayer, @NotNull BlockPos blockPos) { return 0; }
	
	/** Overriding allows us to use our own lighting engine */
	@Override
	public int getRawBrightness(@NotNull BlockPos blockPos, int i) { return 0; }
	
	/** Overriding allows us to use our own lighting engine */
	@Override
	public boolean canSeeSky(@NotNull BlockPos blockPos)
	{ return (this.getBrightness(LightLayer.SKY, blockPos) >= LodUtil.MAX_MC_LIGHT); }
	
	public int getBlockTint(@NotNull BlockPos blockPos, @NotNull ColorResolver colorResolver)
	{ return this.calculateBlockTint(blockPos, colorResolver); }
	
	private Biome _getBiome(BlockPos pos)
	{
		#if MC_VER >= MC_1_18_2
		return this.getBiome(pos).value();
		#else
		return this.getBiome(pos);
		#endif
	}
	
	public int calculateBlockTint(BlockPos blockPos, ColorResolver colorResolver)
	{
		#if MC_VER < MC_1_19_2
		int i = (Minecraft.getInstance()).options.biomeBlendRadius;
		#else
		int i = (Minecraft.getInstance()).options.biomeBlendRadius().get();
		#endif
		if (i == 0)
			return colorResolver.getColor((Biome) _getBiome(blockPos), blockPos.getX(), blockPos.getZ());
		int j = (i * 2 + 1) * (i * 2 + 1);
		int k = 0;
		int l = 0;
		int m = 0;
		Cursor3D cursor3D = new Cursor3D(blockPos.getX() - i, blockPos.getY(), blockPos.getZ() - i, blockPos.getX() + i, blockPos.getY(), blockPos.getZ() + i);
		BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();
		while (cursor3D.advance())
		{
			mutableBlockPos.set(cursor3D.nextX(), cursor3D.nextY(), cursor3D.nextZ());
			int n = colorResolver.getColor((Biome) _getBiome((BlockPos) mutableBlockPos), mutableBlockPos.getX(), mutableBlockPos.getZ());
			k += (n & 0xFF0000) >> 16;
			l += (n & 0xFF00) >> 8;
			m += n & 0xFF;
		}
		return (k / j & 0xFF) << 16 | (l / j & 0xFF) << 8 | m / j & 0xFF;
	}
	
}
#endif