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

package com.seibel.distanthorizons.common.wrappers.worldGeneration.step;

#if MC_VER <= MC_26_2_0
 // StepSurface used instead
#else
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;

import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper;
import com.seibel.distanthorizons.common.wrappers.worldGeneration.DhChunkGenerator;
import com.seibel.distanthorizons.common.wrappers.worldGeneration.params.ThreadWorldGenParams;

import com.seibel.distanthorizons.common.wrappers.worldGeneration.mimicObject.DhLitWorldGenRegion;
import com.seibel.distanthorizons.core.util.gridList.ArrayGridList;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;

#if MC_VER <= MC_1_20_4
import net.minecraft.world.level.chunk.ChunkStatus;
#else
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.blending.Blender;
#endif


public final class StepTerrain extends AbstractWorldGenStep
{
	private static final ChunkStatus STATUS = ChunkStatus.TERRAIN;
	
	private final DhChunkGenerator dhChunkGen;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public StepTerrain(DhChunkGenerator dhChunkGen) { this.dhChunkGen = dhChunkGen; }
	
	
	
	//==================//
	// abstract methods //
	//==================//
	
	@Override
	public ChunkStatus getChunkStatus() { return STATUS; }
	
	@Override
	public void generateGroup(
			ThreadWorldGenParams tParams, DhLitWorldGenRegion worldGenRegion,
			ArrayGridList<ChunkWrapper> chunkWrappers)
	{
		ArrayList<ChunkWrapper> chunksToGen = this.getChunkWrappersToGenerate(chunkWrappers);
		for (ChunkWrapper chunkWrapper : chunksToGen)
		{
			ChunkAccess chunk = chunkWrapper.getChunk();
			
			// get biomes
			Set<Holder<Biome>> possibleBiomes = new ObjectArraySet<>();
			{
				for(LevelChunkSection section : chunk.getSections()) 
				{
					PalettedContainerRO<Holder<Biome>> biomePallet = section.getBiomes();
					biomePallet.getAll(possibleBiomes::add);
				}
			}
			
			
			this.dhChunkGen.globalParams.generator.buildTerrain(
				chunk, 
				Blender.of(worldGenRegion),
				this.dhChunkGen.globalParams.randomState,
				tParams.structFeatManager.forWorldGenRegion(worldGenRegion),
				// the biome manager must be pulled from the region, not the server level,
				// otherwise this can cause the thread to get stuck waiting on requests that will never finish.
				worldGenRegion.getBiomeManager(),
				worldGenRegion,
				possibleBiomes
			);
		}
	}
	
	
	
}
#endif