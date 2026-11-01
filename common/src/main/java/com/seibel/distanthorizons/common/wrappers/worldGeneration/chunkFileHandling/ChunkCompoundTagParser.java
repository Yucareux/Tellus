/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU GPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.common.wrappers.worldGeneration.chunkFileHandling;

#if MC_VER > MC_1_12_2
import com.mojang.serialization.Codec;
import com.seibel.distanthorizons.common.wrappers.McObjectConverter;
import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.level.IDhServerLevel;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.ChunkLightStorage;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;


import net.minecraft.core.Registry;
#if MC_VER >= MC_1_19_4
import net.minecraft.core.registries.Registries;
#endif

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;

#if MC_VER < MC_1_21_3
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
#else
#endif

#if MC_VER < MC_1_21_9
import net.minecraft.world.level.block.Blocks;
#else
#endif

import net.minecraft.world.level.levelgen.Heightmap;
#if MC_VER >= MC_1_18_2
#if MC_VER < MC_1_19_2
import net.minecraft.world.level.levelgen.feature.StructureFeature;
#endif
import net.minecraft.world.ticks.LevelChunkTicks;
#endif
#if MC_VER >= MC_1_18_2
import net.minecraft.core.Holder;
#if MC_VER < MC_1_19_2
import net.minecraft.world.level.levelgen.feature.ConfiguredStructureFeature;
#endif
#endif

#if MC_VER == MC_1_16_5 || MC_VER == MC_1_17_1
import net.minecraft.world.level.material.Fluids;
#endif

#if MC_VER == MC_1_20_6
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkType;
#elif MC_VER >= MC_1_21_1
import net.minecraft.world.level.chunk.status.ChunkStatus;
#endif

import net.minecraft.world.level.material.Fluid;


public class ChunkCompoundTagParser
{
	public static final DhLogger LOGGER = new DhLoggerBuilder()
		.name("LOD Chunk Reader")
		.fileLevelConfig(Config.Common.Logging.logWorldGenChunkLoadEventToFile)
		.build();
	
	public static final DhLogger RATE_LIMITED_LOGGER = new DhLoggerBuilder()
		.name("LOD Chunk Reader")
		.fileLevelConfig(Config.Common.Logging.logWorldGenChunkLoadEventToFile)
		.maxCountPerSecond(1)
		.build();
	
	private static final AtomicBoolean ZERO_CHUNK_POS_ERROR_LOGGED_REF = new AtomicBoolean(false);
	private static final ConcurrentHashMap<String, Object> LOGGED_ERROR_MESSAGE_MAP = new ConcurrentHashMap<>();
	
	private static boolean lightingSectionErrorLogged = false;
	
	
	
	
	//============//
	// read chunk //
	//============//
	
	public static ChunkWrapper createFromTag(
		WorldGenLevel mcWorldGenLevel, IDhServerLevel dhServerLevel, 
		ChunkPos chunkPos, CompoundTag chunkData)
	{
		#if MC_VER < MC_1_18_2
		CompoundTag tagLevel = chunkData.getCompound("Level");
		#else
		CompoundTag tagLevel = chunkData;
		#endif
		
		
		
		//=======================//
		// validate the chunkPos //
		//=======================//
		
		int chunkX = CompoundTagUtil.getInt(tagLevel,"xPos");
		int chunkZ = CompoundTagUtil.getInt(tagLevel, "zPos");
		ChunkPos actualChunkPos = new ChunkPos(chunkX, chunkZ);
		
		// confirm chunk pos is correct
		if (!Objects.equals(chunkPos, actualChunkPos))
		{
			if (chunkX == 0 && chunkZ == 0)
			{
				if (!ZERO_CHUNK_POS_ERROR_LOGGED_REF.getAndSet(true))
				{
					// explicit chunkPos toString is necessary otherwise the JDK 17 compiler breaks
					LOGGER.warn("Chunk file at ["+chunkPos.toString()+"] doesn't have a chunk pos. \n" +
						"This might happen if the world was created using an external program. \n" +
						"DH will attempt to parse the chunk anyway and won't log this message again.\n" +
						"If issues arise please try optimizing your world to fix this issue. \n" +
						"World optimization can be done from the singleplayer world selection screen." +
						" ");
				}
			}
			else
			{
				LOGGER.error("Chunk file at ["+chunkPos.toString()+"] is in the wrong location. \n" +
					"Please try optimizing your world to fix this issue. \n" +
					"World optimization can be done from the singleplayer world selection screen. \n" +
					"(Expected pos: ["+chunkPos.toString()+"], actual ["+actualChunkPos.toString()+"])" +
					" ");
				return null;
			}
		}
		
		
		
		//===========//
		// get ticks //
		//===========//
		
		#if MC_VER < MC_1_18_2
		ChunkBiomeContainer chunkBiomeContainer = new ChunkBiomeContainer(
				mcWorldGenLevel.getLevel().registryAccess().registryOrThrow(Registry.BIOME_REGISTRY), #if MC_VER >= MC_1_17_1 mcWorldGenLevel, #endif
				chunkPos, mcWorldGenLevel.getLevel().getChunkSource().getGenerator().getBiomeSource(),
				tagLevel.contains("Biomes", 11) ? tagLevel.getIntArray("Biomes") : null);
		
		String BLOCK_TICKS_TAG_PRE18 = "TileTicks";
		TickList<Block> blockTicks = tagLevel.contains(BLOCK_TICKS_TAG_PRE18, 9)
				? ChunkTickList.create(tagLevel.getList(BLOCK_TICKS_TAG_PRE18, 10), Registry.BLOCK::getKey, Registry.BLOCK::get)
				: new ProtoTickList<Block>(block -> (block == null || block.defaultBlockState().isAir()), chunkPos,
				tagLevel.getList("ToBeTicked", 9) #if MC_VER >= MC_1_17_1 , mcWorldGenLevel #endif );
		
		String FLUID_TICKS_TAG_PRE18 = "LiquidTicks";
		TickList<Fluid> fluidTicks = tagLevel.contains(FLUID_TICKS_TAG_PRE18, 9)
				? ChunkTickList.create(tagLevel.getList(FLUID_TICKS_TAG_PRE18, 10), Registry.FLUID::getKey, Registry.FLUID::get)
				: new ProtoTickList<Fluid>(fluid -> (fluid == null || fluid == Fluids.EMPTY), chunkPos,
				tagLevel.getList("LiquidsToBeTicked", 9) #if MC_VER >= MC_1_17_1 , mcWorldGenLevel #endif );
		#else
		// ticks shouldn't be needed so ignore them for MC versions after 1.18.2
		LevelChunkTicks<Block> blockTicks = new LevelChunkTicks<>();
		LevelChunkTicks<Fluid> fluidTicks = new LevelChunkTicks<>();
		#endif
		
		
		
		//=====================//
		// get misc properties //
		//=====================//
		
		int sectionYCount = #if MC_VER < MC_1_17_1 16; #else mcWorldGenLevel.getSectionsCount(); #endif
		LevelChunkSection[] chunkSections = new LevelChunkSection[sectionYCount];
		boolean hasBlocks = readAndPopulateSections(mcWorldGenLevel, chunkPos, tagLevel, chunkSections);
		if (!hasBlocks)
		{
			return null;
		}
		
		
		long inhabitedTime = CompoundTagUtil.getLong(tagLevel, "InhabitedTime");
		boolean isLightOn = CompoundTagUtil.getBoolean(tagLevel, "isLightOn");
		
		
		
		//============//
		// make chunk //
		//============//
		
		#if MC_VER < MC_1_18_2
		LevelChunk chunk = new LevelChunk((Level) mcWorldGenLevel.getLevel(), chunkPos, chunkBiomeContainer, UpgradeData.EMPTY, blockTicks,
				fluidTicks, inhabitedTime, chunkSections, null);
		#else
		LevelChunk chunk = new LevelChunk((Level) mcWorldGenLevel, chunkPos, UpgradeData.EMPTY, blockTicks,
				fluidTicks, inhabitedTime, chunkSections, null, null);
		#endif
		
		// Set some states after object creation
		chunk.setLightCorrect(isLightOn);
		boolean hasHeightmapData = readHeightmaps(chunk, chunkData);
		
		// chunk wrapper so we can pass along extra data more easily
		ChunkWrapper chunkWrapper = new ChunkWrapper(chunk, dhServerLevel.getServerLevelWrapper());
		chunkWrapper.createDhHeightMaps();
		
		
		
		//===========================//
		// check if chunk has blocks //
		//===========================//
		
		// in some MC versions all the NBT data will be there
		// but the chunk will be totally empty,
		// usually this means the chunk was only partially generated.
		// If that happens we should try to generate the chunk from scratch
		// otherwise we can end up with large empty holes in the world.
		
		// walking through the heightmap (recreated by DH if missing)
		// is a fast way to check if there are any blocks in the chunk
		boolean chunkHasBlocks = false;
		int serverMinHeight = dhServerLevel.getServerLevelWrapper().getMinHeight();
		for (int x = 0; x < 16 && !chunkHasBlocks; x++)
		{
			for (int z = 0; z < 16 && !chunkHasBlocks; z++)
			{
				int heightMap = Math.max(
					// max between both heightmaps just in case there's a discrepancy
					chunkWrapper.getLightBlockingHeightMapValue(x, z),
					chunkWrapper.getSolidHeightMapValue(x, z)
				);
				if (heightMap != serverMinHeight)
				{
					chunkHasBlocks = true;
				}
			}
		}
		
		
		if (chunkHasBlocks)
		{
			return chunkWrapper;
		}
		else
		{
			// no blocks detected, this chunk should be generated from scratch
			return null;
		}
	}
	
	
	
	//=================//
	// chunk sections  //
	// (Blocks/biomes) //
	//=================//
	
	/** handles both blocks and biomes */
	private static boolean readAndPopulateSections(
		LevelAccessor level, ChunkPos chunkPos, CompoundTag chunkData,
		LevelChunkSection[] chunkSections)
	{
		int sectionYCount = #if MC_VER < MC_1_17_1 16; #else level.getSectionsCount(); #endif
		
		ListTag tagSections = CompoundTagUtil.getListTag(chunkData, "Sections", 10);
		// try lower-case "sections" if capital "Sections" is missing
		if (tagSections == null 
			|| tagSections.isEmpty())
		{
			tagSections = CompoundTagUtil.getListTag(chunkData, "sections", 10);
		}
		
		
		boolean blocksFound = false;
		if (tagSections != null)
		{
			for (int i = 0; i < tagSections.size(); ++i)
			{
				CompoundTag tagSection = CompoundTagUtil.getCompoundTag(tagSections, i);
				if (tagSection == null)
				{
					continue;
				}
				
				final int sectionYPos = CompoundTagUtil.getByte(tagSection, "Y");
				
				
				
				//===================//
				// get blocks/biomes //
				//===================//
				
				#if MC_VER < MC_1_18_2
				if (tagSection.contains("Palette", 9) 
					&& tagSection.contains("BlockStates", 12))
				{
					LevelChunkSection levelChunkSection = new LevelChunkSection(sectionYPos << 4);
					levelChunkSection.getStates().read(tagSection.getList("Palette", 10), tagSection.getLongArray("BlockStates"));
					levelChunkSection.recalcBlockCounts();
					if (!levelChunkSection.isEmpty())
					{
						int sectionIndex;
						#if MC_VER < MC_1_17_1 
						sectionIndex = sectionYPos;
						#else 
						sectionIndex = level.getSectionIndexFromSectionY(sectionYPos); 
						#endif
						
						chunkSections[sectionIndex] = levelChunkSection;
					}
				}
				
				#else
				
				int sectionId = level.getSectionIndexFromSectionY(sectionYPos);
				if (sectionId >= 0 
					&& sectionId < chunkSections.length)
				{
					//========//
					// blocks //
					//========//
					
					PalettedContainer<BlockState> blockStateContainer;
					
					boolean containsBlockStates = CompoundTagUtil.contains(tagSection, "block_states", 10);
					if (containsBlockStates)
					{
						Codec<PalettedContainer<BlockState>> blockStateCodec = getBlockStateCodec(level);
						
						#if MC_VER < MC_1_20_6 
						blockStateContainer = blockStateCodec
							.parse(NbtOps.INSTANCE, CompoundTagUtil.getCompoundTag(tagSection, "block_states"))
							.promotePartial(string -> logBlockDeserializationWarning(chunkPos, sectionYPos, string))
							.getOrThrow(false, (message) -> logParsingWarningOnce(message));
						#else
						blockStateContainer = blockStateCodec
							.parse(NbtOps.INSTANCE, CompoundTagUtil.getCompoundTag(tagSection, "block_states"))
							.promotePartial(string -> logBlockDeserializationWarning(chunkPos, sectionYPos, string))
							.getOrThrow((message) -> logErrorAndReturnException(message));
						#endif
						
						blocksFound = true;
					}
					else
					{
						#if MC_VER < MC_1_21_9
						blockStateContainer = new PalettedContainer<BlockState>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
						#else
						blockStateContainer = PalettedContainerFactory.create(level.registryAccess()).createForBlockStates();
						#endif
					}
					
					
					
					//========//
					// biomes //
					//========//
					
					Registry<Biome> biomeRegistry = getBiomeRegistry(level);
					
					#if MC_VER < MC_1_19_2
					Codec<PalettedContainer<Biome>> biomeCodec;
					#else 
					Codec<PalettedContainer<Holder<Biome>>> biomeCodec;
					#endif
					biomeCodec = getBiomeCodec(level, biomeRegistry);
					
					#if MC_VER < MC_1_18_2
					PalettedContainer<Biome> biomeContainer;
					#else
					PalettedContainer<Holder<Biome>> biomeContainer;
					#endif
					
					#if MC_VER < MC_1_18_2
					biomeContainer = tagSection.contains("biomeRegistry", 10)
							? biomeCodec.parse(NbtOps.INSTANCE, tagSection.getCompound("biomeRegistry")).promotePartial(string -> logErrors(chunkPos, sectionYPos, string)).getOrThrow(false, (message) -> logWarningOnce(message))
							: new PalettedContainer<Biome>(biomeRegistry, biomeRegistry.getOrThrow(Biomes.PLAINS), PalettedContainer.Strategy.SECTION_BIOMES);
					#else
					{
						CompoundTag biomeTag = CompoundTagUtil.getCompoundTag(tagSection, "biomeRegistry");
						if (biomeTag == null)
						{
							biomeTag = CompoundTagUtil.getCompoundTag(tagSection, "biomes");
						}
						
						if (biomeTag != null
							&& !biomeTag.isEmpty())
						{
							#if MC_VER < MC_1_20_6
							biomeContainer = new PalettedContainer<Holder<Biome>>(
								biomeRegistry.asHolderIdMap(),
								biomeRegistry.getHolderOrThrow(Biomes.PLAINS), PalettedContainer.Strategy.SECTION_BIOMES);
							#else
							biomeContainer = biomeCodec.parse(NbtOps.INSTANCE, biomeTag)
								.promotePartial(string -> logBiomeDeserializationWarning(chunkPos, sectionYCount, (String) string))
								.getOrThrow((message) -> logErrorAndReturnException(message));
							#endif
						}
						else
						{
							// no biomes found, use the default (probably plains)
							
							#if MC_VER < MC_1_21_3
							biomeContainer = new PalettedContainer<Holder<Biome>>(
									biomeRegistry.asHolderIdMap(), 
									biomeRegistry.getHolderOrThrow(Biomes.PLAINS), PalettedContainer.Strategy.SECTION_BIOMES);
							#elif MC_VER < MC_1_21_9
							biomeContainer = new PalettedContainer<Holder<Biome>>(biomeRegistry.asHolderIdMap(),
									biomeRegistry.getOrThrow(Biomes.PLAINS),
									PalettedContainer.Strategy.SECTION_BIOMES);
							#else
							biomeContainer = PalettedContainerFactory.create(level.registryAccess()).createForBiomes();
							#endif
						}
					}
					#endif
					
					#if MC_VER < MC_1_20_1
					chunkSections[sectionId] = new LevelChunkSection(sectionYPos, blockStateContainer, biomeContainer);
					#else
					chunkSections[sectionId] = new LevelChunkSection(blockStateContainer, biomeContainer);
					#endif
				}
				#endif
				
			}	
		}
		
		return blocksFound;
	}
	
	private static Codec<PalettedContainer<BlockState>> getBlockStateCodec(LevelAccessor level)
	{
		#if MC_VER < MC_1_18_2
		return null; // unused for older MC versions
		#elif MC_VER < MC_1_19_2
		return PalettedContainer.codec(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
		#elif MC_VER <= MC_1_21_8
		return PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
		#else
		return PalettedContainerFactory.create(level.registryAccess()).blockStatesContainerCodec();
		#endif
	}
	
	private static Registry<Biome> getBiomeRegistry(LevelAccessor level)
	{
		#if MC_VER < MC_1_18_2
		// not needed
		return null;
		#elif MC_VER < MC_1_19_4
		return level.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY);
		#elif MC_VER < MC_1_21_3
		return level.registryAccess().registryOrThrow(Registries.BIOME);
		#else
		return level.registryAccess().lookupOrThrow(Registries.BIOME);
		#endif	
	}
	private static 
		#if MC_VER < MC_1_19_2 Codec<PalettedContainer<Biome>>
		#else Codec<PalettedContainer<Holder<Biome>>>
		#endif
		getBiomeCodec(LevelAccessor level, Registry<Biome> biomeRegistry)
	{
		#if MC_VER < MC_1_18_2
		return null; // unused for older MC versions
		#elif MC_VER < MC_1_19_2
		return PalettedContainer.codec(
				biomeRegistry, biomeRegistry.byNameCodec(), PalettedContainer.Strategy.SECTION_BIOMES, biomeRegistry.getOrThrow(Biomes.PLAINS));
		#elif MC_VER < MC_1_19_2
		return PalettedContainer.codec(
			biomeRegistry.asHolderIdMap(), biomeRegistry.holderByNameCodec(), PalettedContainer.Strategy.SECTION_BIOMES, biomeRegistry.getHolderOrThrow(Biomes.PLAINS));
		#elif MC_VER < MC_1_21_3
		return PalettedContainer.codecRW(
			biomeRegistry.asHolderIdMap(), biomeRegistry.holderByNameCodec(), PalettedContainer.Strategy.SECTION_BIOMES, biomeRegistry.getHolderOrThrow(Biomes.PLAINS));
		#elif MC_VER < MC_1_21_9
		return PalettedContainer.codecRW(
			biomeRegistry.asHolderIdMap(), biomeRegistry.holderByNameCodec(), PalettedContainer.Strategy.SECTION_BIOMES, biomeRegistry.getOrThrow(Biomes.PLAINS));
		#else
		return PalettedContainer.codecRW(
			biomeRegistry.holderByNameCodec(), PalettedContainerFactory.create(level.registryAccess()).biomeStrategy(), biomeRegistry.getOrThrow(Biomes.PLAINS));
		#endif
	}
	
	
	
	//============//
	// heightmaps //
	//============//
	
	private static boolean readHeightmaps(LevelChunk chunk, CompoundTag chunkData)
	{
		CompoundTag tagHeightmaps = CompoundTagUtil.getCompoundTag(chunkData, "Heightmaps");
		if (tagHeightmaps == null)
		{
			return false;
		}
		
		
		for (Heightmap.Types type : ChunkStatus.FULL.heightmapsAfter())
		{
			String heightmapKey = type.getSerializationKey();
			
			#if MC_VER < MC_1_21_5
			if (tagHeightmaps.contains(heightmapKey, 12))
			{
				chunk.setHeightmap(type, tagHeightmaps.getLongArray(heightmapKey));
			}
			#else
			if (tagHeightmaps.contains(heightmapKey))
			{
				Optional<long[]> optionalHeightmap = tagHeightmaps.getLongArray(heightmapKey);
				if (optionalHeightmap.isPresent())
				{
					chunk.setHeightmap(type, optionalHeightmap.get());
				}
			}
			#endif
		}
		
		Heightmap.primeHeightmaps(chunk, ChunkStatus.FULL.heightmapsAfter());
		return true;
	}
	
	
	
	//================//
	// chunk lighting //
	//================//
	
	/** source: https://minecraft.wiki/w/Chunk_format */
	public static CombinedChunkLightStorage readLight(ChunkAccess chunk, CompoundTag chunkData)
	{
		#if MC_VER <= MC_1_17_1
		// MC 1.16 and 1.17 doesn't have the necessary NBT info
		return null;
		#else
		
		CombinedChunkLightStorage combinedStorage = new CombinedChunkLightStorage(ChunkWrapper.getInclusiveMinBuildHeight(chunk), ChunkWrapper.getExclusiveMaxBuildHeight(chunk));
		ChunkLightStorage blockLightStorage = combinedStorage.blockLightStorage;
		ChunkLightStorage skyLightStorage = combinedStorage.skyLightStorage;
		
		boolean foundSkyLight = false;
		
		
		
		//===================//
		// get NBT tags info //
		//===================//
		
		Tag chunkSectionTags = chunkData.get("sections");
		if (chunkSectionTags == null)
		{
			if (!lightingSectionErrorLogged)
			{
				lightingSectionErrorLogged = true;
				LOGGER.error("No sections found for chunk at pos ["+chunk.getPos()+"] chunk data may be out of date.");
			}
			return null;
		}
		else if (!(chunkSectionTags instanceof ListTag))
		{
			if (!lightingSectionErrorLogged)
			{
				lightingSectionErrorLogged = true;
				LOGGER.error("Chunk section tag list have unexpected type ["+chunkSectionTags.getClass().getName()+"], expected ["+ListTag.class.getName()+"].");
			}
			return null;
		}
		ListTag chunkSectionListTag = (ListTag) chunkSectionTags;
		
		
		
		//===================//
		// get lighting info //
		//===================//
		
		for (int sectionIndex = 0; sectionIndex < chunkSectionListTag.size(); sectionIndex++)
		{
			Tag chunkSectionTag = chunkSectionListTag.get(sectionIndex);
			if (!(chunkSectionTag instanceof CompoundTag))
			{
				if (!lightingSectionErrorLogged)
				{
					lightingSectionErrorLogged = true;
					LOGGER.error("Chunk section tag has an unexpected type ["+chunkSectionTag.getClass().getName()+"], expected ["+CompoundTag.class.getName()+"].");
				}
				return null;
			}
			CompoundTag chunkSectionCompoundTag = (CompoundTag) chunkSectionTag;
			
			
			// if null all lights = 0
			byte[] blockLightNibbleArray = CompoundTagUtil.getByteArray(chunkSectionCompoundTag, "BlockLight");
			byte[] skyLightNibbleArray = CompoundTagUtil.getByteArray(chunkSectionCompoundTag, "SkyLight");
			
			if (blockLightNibbleArray != null 
				&& skyLightNibbleArray != null)
			{
				// if any sky light was found then all lights above will be max brightness
				if (skyLightNibbleArray.length != 0)
				{
					foundSkyLight = true;
				}
				
				for (int relX = 0; relX < LodUtil.CHUNK_WIDTH; relX++)
				{
					for (int relZ = 0; relZ < LodUtil.CHUNK_WIDTH; relZ++)
					{
						// chunk sections are also 16 blocks tall
						for (int relY = 0; relY < LodUtil.CHUNK_WIDTH; relY++)
						{
							int blockPosIndex = relY*16*16 + relZ*16 + relX;
							byte blockLight = (blockLightNibbleArray.length == 0) ? 0 : getNibbleAtIndex(blockLightNibbleArray, blockPosIndex);
							byte skyLight = (skyLightNibbleArray.length == 0) ? 0 : getNibbleAtIndex(skyLightNibbleArray, blockPosIndex);
							if (skyLightNibbleArray.length == 0 && foundSkyLight)
							{
								skyLight = LodUtil.MAX_MC_LIGHT;
							}
							
							int y = relY + (sectionIndex * LodUtil.CHUNK_WIDTH) + ChunkWrapper.getInclusiveMinBuildHeight(chunk);
							blockLightStorage.set(relX, y, relZ, blockLight);
							skyLightStorage.set(relX, y, relZ, skyLight);
						}
					}
				}
			}
		}
		
		return combinedStorage;
		#endif
	}
	/** source: https://minecraft.wiki/w/Chunk_format#Block_Format */
	private static byte getNibbleAtIndex(byte[] arr, int index)
	{
		if (index % 2 == 0)
		{
			return (byte)(arr[index/2] & 0x0F);
		}
		else
		{
			return (byte)((arr[index/2]>>4) & 0x0F);
		}
	}
	
	
	
	//=========//
	// logging //
	//=========//
	
	private static void logBlockDeserializationWarning(ChunkPos chunkPos, int sectionYIndex, String message)
	{
		LOGGED_ERROR_MESSAGE_MAP.computeIfAbsent(message, (newMessage) ->
		{
			DhChunkPos dhChunkPos = McObjectConverter.convert(chunkPos);
			
			RATE_LIMITED_LOGGER.warn(
				"Unable to deserialize blocks for chunk section [" + dhChunkPos.getX() + ", " + sectionYIndex + ", " + dhChunkPos.getZ() + "], error: ["+newMessage+"]. " +
				"This can probably be ignored, although if your world looks wrong, optimizing it via the single player menu then deleting your DH database(s) should fix the problem."
			);
			
			return newMessage;
		});
	}
	private static void logBiomeDeserializationWarning(ChunkPos chunkPos, int sectionYIndex, String message)
	{
		LOGGED_ERROR_MESSAGE_MAP.computeIfAbsent(message, (newMessage) -> 
		{
			DhChunkPos dhChunkPos = McObjectConverter.convert(chunkPos);
			
			LOGGER.warn("Unable to deserialize biomes for chunk section [" + dhChunkPos.getX() + ", " + sectionYIndex + ", " + dhChunkPos.getZ() + "], error: ["+newMessage+"]. " +
					"This can probably be ignored, although if your world looks wrong, optimizing it via the single player menu then deleting your DH database(s) should fix the problem.");
			
			return newMessage;
		});
	}
	
	private static void logParsingWarningOnce(String message) { logParsingWarningOnce(message, null); }
	private static void logParsingWarningOnce(String message, Exception e)
	{
		if (message == null)
		{
			return;
		}
		
		LOGGED_ERROR_MESSAGE_MAP.computeIfAbsent(message, (newMessage) ->
		{
			LOGGER.warn("Parsing error: ["+newMessage+"]. " +
					"This can probably be ignored, although if your world looks wrong, optimizing it via the single player menu then deleting your DH database(s) should fix the problem.",
					e);
			
			return newMessage;
		});
	}
	
	private static RuntimeException logErrorAndReturnException(String message)
	{
		LOGGED_ERROR_MESSAGE_MAP.computeIfAbsent(message, (newMessage) ->
		{
			LOGGER.warn("Parsing error: ["+newMessage+"]. " +
					"This can probably be ignored, although if your world looks wrong, optimizing it via the single player menu then deleting your DH database(s) should fix the problem.");
			
			return newMessage;
		});
		
		// Currently we want to ignore these errors, if returning null is a problem, we can change this later
		return null; //new RuntimeException(message);
	}
	
	
	
	//================//
	// helper classes //
	//================//
	
	public static class CombinedChunkLightStorage
	{
		public ChunkLightStorage blockLightStorage;
		public ChunkLightStorage skyLightStorage;
		
		public CombinedChunkLightStorage(int minY, int maxY)
		{
			this.blockLightStorage = ChunkLightStorage.createBlockLightStorage(minY, maxY);
			this.skyLightStorage = ChunkLightStorage.createSkyLightStorage(minY, maxY);
		}
	}
	
	
	
}
#endif
