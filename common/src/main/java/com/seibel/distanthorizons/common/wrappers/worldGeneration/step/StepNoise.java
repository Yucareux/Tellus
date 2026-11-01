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

#if MC_VER >= MC_1_18_2
import net.minecraft.world.level.levelgen.blending.Blender;
#endif

#if MC_VER <= MC_1_20_4
import net.minecraft.world.level.chunk.ChunkStatus;
#else
import net.minecraft.world.level.chunk.status.ChunkStatus;
#endif

public final class StepNoise extends AbstractWorldGenStep
{
	private static final ChunkStatus STATUS = ChunkStatus.NOISE;
	
	private final DhChunkGenerator dhChunkGen;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public StepNoise(DhChunkGenerator dhChunkGen) { this.dhChunkGen = dhChunkGen; }
	
	
	
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
			
			#if MC_VER < MC_1_17_1
			this.dhChunkGen.globalParams.generator.fillFromNoise(worldGenRegion, tParams.structFeatManager, chunk);
			#elif MC_VER < MC_1_18_2
			chunk = this.dhChunkGen.confirmFutureWasRunSynchronously(
						this.dhChunkGen.globalParams.generator.fillFromNoise(
							Runnable::run,
							tParams.structFeatManager.forWorldGenRegion(worldGenRegion), 
							chunk));
			#elif MC_VER < MC_1_19_2
			chunk = this.dhChunkGen.confirmFutureWasRunSynchronously(
						this.dhChunkGen.globalParams.generator.fillFromNoise(
							Runnable::run, 
							Blender.of(worldGenRegion),
							tParams.structFeatManager.forWorldGenRegion(worldGenRegion), 
							chunk));
			#elif MC_VER < MC_1_21_1
			chunk = this.dhChunkGen.confirmFutureWasRunSynchronously(
						this.dhChunkGen.globalParams.generator.fillFromNoise(
							Runnable::run, 
							Blender.of(worldGenRegion), 
							this.dhChunkGen.globalParams.randomState,
							tParams.structFeatManager.forWorldGenRegion(worldGenRegion), 
							chunk));
			#else
			chunk = this.dhChunkGen.confirmFutureWasRunSynchronously(
						this.dhChunkGen.globalParams.generator.fillFromNoise(
							Blender.of(worldGenRegion), 
							this.dhChunkGen.globalParams.randomState,
							tParams.structFeatManager.forWorldGenRegion(worldGenRegion), 
							chunk));
			#endif
		}
	}
	
}
#endif