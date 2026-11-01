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

package com.seibel.distanthorizons.common.wrappers.worldGeneration.params;

import com.seibel.distanthorizons.common.wrappers.world.ServerLevelWrapper;
import com.seibel.distanthorizons.core.level.IDhServerLevel;


// TODO these preprocessors are a mess
#if MC_VER <= MC_1_12_2

#if MC_VER <= MC_1_7_10
import net.minecraft.world.WorldServer;
#else
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.IChunkGenerator;

import java.util.concurrent.CompletableFuture;
#endif

#else
import com.mojang.datafixers.DataFixer;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;

#if MC_VER >= MC_1_18_2
import net.minecraft.world.level.chunk.storage.ChunkScanAccess;
#endif

#if MC_VER < MC_1_19_2
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureManager;
#elif MC_VER < MC_1_19_2
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureManager;
#else
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
#endif
import net.minecraft.world.level.storage.WorldData;

#if MC_VER < MC_1_19_4
#elif MC_VER < MC_1_21_3
import net.minecraft.core.registries.Registries;
#else
import net.minecraft.core.registries.Registries;
#endif

#if MC_VER < MC_1_19_4
import net.minecraft.world.level.levelgen.WorldGenSettings;
#else
import net.minecraft.world.level.levelgen.WorldOptions;
#endif
#endif

#if MC_VER > MC_1_12_2
/**
 * Handles parameters that are relevant for the entire MC world.
 * 
 * @see ThreadWorldGenParams
 */
#endif

public final class GlobalWorldGenParams
{
	public final IDhServerLevel dhServerLevel;
	
	#if MC_VER <= MC_1_7_10
	public final WorldServer mcServerLevel;
	#elif MC_VER <= MC_1_12_2
	public final IChunkGenerator generator;
	public final WorldServer mcServerLevel;
	#else
	public final ChunkGenerator generator;
	public final ServerLevel mcServerLevel;
	#endif
	
	#if MC_VER > MC_1_12_2
	public final Registry<Biome> biomes;
	public final RegistryAccess registry;
	#endif
	
	public final long worldSeed;
	#if MC_VER <= MC_1_7_10
	#else
	public final DataFixer dataFixer;
	#endif
	
	#if MC_VER <= MC_1_12_2
	#elif MC_VER < MC_1_19_2
	public final StructureManager structures;
	#else
	public final StructureTemplateManager structures;
	public final RandomState randomState;
	#endif
	
	#if MC_VER <= MC_1_12_2
	#elif MC_VER < MC_1_19_4
	public final WorldGenSettings worldGenSettings;
	#else
	public final WorldOptions worldOptions;
	#endif
	
	#if MC_VER >= MC_1_18_2
	public final ChunkScanAccess chunkScanner;
	#endif
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public GlobalWorldGenParams(IDhServerLevel dhServerLevel)
	{
		this.dhServerLevel = dhServerLevel;
		this.mcServerLevel = ((ServerLevelWrapper) dhServerLevel.getServerLevelWrapper()).getWrappedMcObject();
		
		#if MC_VER <= MC_1_7_10
		#elif MC_VER <= MC_1_12_2
		MinecraftServer server = this.mcServerLevel.getMinecraftServer();
		#else
		MinecraftServer server = this.mcServerLevel.getServer();
		#endif
		
		#if MC_VER > MC_1_12_2
		WorldData worldData = server.getWorldData();
		this.registry = server.registryAccess();
		#endif
		
		#if MC_VER <= MC_1_12_2
		this.worldSeed = mcServerLevel.getSeed();
		#elif MC_VER < MC_1_19_4
		this.worldGenSettings = worldData.worldGenSettings();
		this.biomes = registry.registryOrThrow(Registry.BIOME_REGISTRY);
		this.worldSeed = worldGenSettings.seed();
		#elif MC_VER < MC_1_21_3
		this.worldOptions = worldData.worldGenOptions();
		this.biomes = registry.registryOrThrow(Registries.BIOME);
		this.worldSeed = worldOptions.seed();
		#elif MC_VER <= MC_1_21_11
		this.worldOptions = worldData.worldGenOptions();
		this.biomes = this.registry.lookupOrThrow(Registries.BIOME);
		this.worldSeed = this.worldOptions.seed();
		#else
		this.worldOptions = server.getWorldGenSettings().options();
		this.biomes = this.registry.lookupOrThrow(Registries.BIOME);
		this.worldSeed = this.worldOptions.seed();
		#endif
		
		
		#if MC_VER >= MC_1_18_2
		this.chunkScanner = this.mcServerLevel.getChunkSource().chunkScanner();
		#endif
		
		#if MC_VER <= MC_1_7_10
		#elif MC_VER <= MC_1_12_2
		this.generator = this.mcServerLevel.getChunkProvider().chunkGenerator;
		#elif MC_VER <= MC_26_2_0
		this.structures = server.getStructureManager();
		this.generator = this.mcServerLevel.getChunkSource().getGenerator();
		#else
		this.structures = server.getStructureTemplateManager();
		this.generator = this.mcServerLevel.getChunkSource().getGenerator();
		#endif
		
		#if MC_VER <= MC_1_7_10
		#elif MC_VER <= MC_1_12_2
		this.dataFixer = server != null ? server.getDataFixer() : null;
		#else
		this.dataFixer = server.getFixerUpper();
		#endif
		#if MC_VER >= MC_1_19_2
		this.randomState = this.mcServerLevel.getChunkSource().randomState();
		#endif
	}
	
	
	
}