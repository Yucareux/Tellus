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

#if MC_VER > MC_1_12_2
import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;

import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper;
import com.seibel.distanthorizons.common.wrappers.worldGeneration.DhChunkGenerator;
import com.seibel.distanthorizons.common.wrappers.worldGeneration.params.ThreadWorldGenParams;

import com.seibel.distanthorizons.common.wrappers.worldGeneration.mimicObject.DhLitWorldGenRegion;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.util.gridList.ArrayGridList;
import net.minecraft.world.level.chunk.ChunkAccess;
import com.seibel.distanthorizons.core.logging.DhLogger;

#if MC_VER <= MC_1_20_4
import net.minecraft.world.level.chunk.ChunkStatus;
#else
import net.minecraft.world.level.chunk.status.ChunkStatus;
#endif


public final class StepStructureStart extends AbstractWorldGenStep
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	private static final ChunkStatus STATUS = ChunkStatus.STRUCTURE_STARTS;
	private static final ReentrantLock STRUCTURE_PLACEMENT_LOCK = new ReentrantLock();
	
	private final DhChunkGenerator dhChunkGen;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public StepStructureStart(DhChunkGenerator dhChunkGen) { this.dhChunkGen = dhChunkGen; }
	
	
	
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
		
		#if MC_VER < MC_1_19_2
		if (!this.dhChunkGen.globalParams.worldGenSettings.generateFeatures())
		#elif MC_VER < MC_1_19_4
		if (!this.dhChunkGen.globalParams.worldGenSettings.generateStructures()) 
		#else
		if (!this.dhChunkGen.globalParams.worldOptions.generateStructures())
		#endif
		{
			return;
		}
		
		
		
		for (ChunkWrapper chunkWrapper : chunksToGen)
		{
			ChunkAccess chunk = chunkWrapper.getChunk();
			
			// hopefully this shouldn't cause any performance issues (this step is generally quite quick so hopefully it should be fine)
			// and should prevent some concurrency issues
			STRUCTURE_PLACEMENT_LOCK.lock();
			
			#if MC_VER < MC_1_19_2
			this.dhChunkGen.globalParams.generator.createStructures(this.dhChunkGen.globalParams.registry, tParams.structFeatManager, chunk, this.dhChunkGen.globalParams.structures,
					this.dhChunkGen.globalParams.worldSeed);
			#elif MC_VER < MC_1_19_4
			this.dhChunkGen.globalParams.generator.createStructures(this.dhChunkGen.globalParams.registry, this.dhChunkGen.globalParams.randomState, tParams.structFeatManager, chunk, this.dhChunkGen.globalParams.structures,
					this.dhChunkGen.globalParams.worldSeed);
			#elif MC_VER <= MC_1_21_3
			this.dhChunkGen.globalParams.generator.createStructures(this.dhChunkGen.globalParams.registry,
					this.dhChunkGen.globalParams.mcServerLevel.getChunkSource().getGeneratorState(),
					tParams.structFeatManager, chunk, this.dhChunkGen.globalParams.structures);
			#else
			this.dhChunkGen.globalParams.generator.createStructures(this.dhChunkGen.globalParams.registry,
					this.dhChunkGen.globalParams.mcServerLevel.getChunkSource().getGeneratorState(),
					tParams.structFeatManager, chunk, this.dhChunkGen.globalParams.structures, 
					this.dhChunkGen.globalParams.mcServerLevel.dimension());
			#endif
			
			
			
			#if MC_VER >= MC_1_18_2
			try
			{
				tParams.structCheck.onStructureLoad(chunk.getPos(), chunk.getAllStarts());
			}
			catch (ArrayIndexOutOfBoundsException firstEx)
			{
				// There's a rare issue with StructStart where it throws ArrayIndexOutOfBounds
				// This means the structFeat is corrupted (For some reason) and I need to reset it.
				
				// reset the structureStart
				tParams.recreateStructureCheck();
				
				try
				{
					// try running the structure logic again
					tParams.structCheck.onStructureLoad(chunk.getPos(), chunk.getAllStarts());
				}
				catch (ArrayIndexOutOfBoundsException secondEx)
				{
					// the structure logic failed again, log it and move on
					LOGGER.error("Unable to create structure starts for " + chunk.getPos() + ". This is an error with MC's world generation. Ignoring and continuing generation. Error: " + secondEx.getMessage()); // don't log the full stack trace since it is long and will generally end up in MC's code
				}
			}
			
			#endif
			
			STRUCTURE_PLACEMENT_LOCK.unlock();
		}
	}
	
}
#endif