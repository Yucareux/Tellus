package com.seibel.distanthorizons.common.wrappers.worldGeneration.step;

#if MC_VER > MC_1_12_2
import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper;
import com.seibel.distanthorizons.common.wrappers.worldGeneration.params.ThreadWorldGenParams;
import com.seibel.distanthorizons.common.wrappers.worldGeneration.mimicObject.DhLitWorldGenRegion;
import com.seibel.distanthorizons.core.util.gridList.ArrayGridList;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;

import java.util.ArrayList;
import java.util.List;

#if MC_VER <= MC_1_20_4
import net.minecraft.world.level.chunk.ChunkStatus;
#else
import net.minecraft.world.level.chunk.status.ChunkStatus;
#endif

public abstract class AbstractWorldGenStep
{
	public abstract void generateGroup(
		ThreadWorldGenParams tParams, DhLitWorldGenRegion worldGenRegion,
		ArrayGridList<ChunkWrapper> chunkWrappers);
	
	public abstract ChunkStatus getChunkStatus();
	
	
	
	/** @return the list of chunks that have an earlier status and can be generated */
	protected ArrayList<ChunkWrapper> getChunkWrappersToGenerate(List<ChunkWrapper> chunkWrappers)
	{
		ArrayList<ChunkWrapper> chunkWrappersToGenerate = new ArrayList<>(chunkWrappers.size());
		
		for (ChunkWrapper chunkWrapper : chunkWrappers)
		{
			ChunkAccess chunk = chunkWrapper.getChunk();
			if (chunkWrapper.getStatus().isOrAfter(this.getChunkStatus()))
			{
				// this chunk has already been generated up to this step
				continue;
			}
			else if (chunk instanceof ProtoChunk)
			{
				chunkWrapper.trySetStatus(this.getChunkStatus());
				chunkWrappersToGenerate.add(chunkWrapper);
			}
		}
		
		return chunkWrappersToGenerate;
	}
	
	
	
}
#endif