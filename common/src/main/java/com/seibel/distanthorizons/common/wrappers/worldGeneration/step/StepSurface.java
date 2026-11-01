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

#if MC_VER <= MC_1_12_2

#elif MC_VER <= MC_26_2_0 // replaced by StepTerrain
import java.util.ArrayList;

import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper;
import com.seibel.distanthorizons.common.wrappers.worldGeneration.DhChunkGenerator;
import com.seibel.distanthorizons.common.wrappers.worldGeneration.params.ThreadWorldGenParams;

import com.seibel.distanthorizons.common.wrappers.worldGeneration.mimicObject.DhLitWorldGenRegion;
import com.seibel.distanthorizons.core.util.gridList.ArrayGridList;
import net.minecraft.world.level.chunk.ChunkAccess;

#if MC_VER <= MC_1_20_4
import net.minecraft.world.level.chunk.ChunkStatus;
#else
import net.minecraft.world.level.chunk.status.ChunkStatus;
#endif


public final class StepSurface extends AbstractWorldGenStep
{
	private static final ChunkStatus STATUS = ChunkStatus.SURFACE;
	
	private final DhChunkGenerator dhChunkGen;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public StepSurface(DhChunkGenerator dhChunkGen) { this.dhChunkGen = dhChunkGen; }
	
	
	
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
			
			#if MC_VER < MC_1_18_2
			this.dhChunkGen.globalParams.generator.buildSurfaceAndBedrock(worldGenRegion, chunk);
			#elif MC_VER < MC_1_19_2
			this.dhChunkGen.globalParams.generator.buildSurface(worldGenRegion, tParams.structFeatManager.forWorldGenRegion(worldGenRegion), chunk);
			#else
			this.dhChunkGen.globalParams.generator.buildSurface(worldGenRegion, tParams.structFeatManager.forWorldGenRegion(worldGenRegion), this.dhChunkGen.globalParams.randomState, chunk);
			#endif
		}
	}
	
	
	
}
#endif