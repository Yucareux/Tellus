package com.seibel.distanthorizons.core.util;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.file.fullDatafile.GeneratedFullDataSourceProvider;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataUpdatePropagatorV2;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;

public class WorldGenUtil
{
	
	/** will always return true if a generation max radius isn't set */
	public static boolean isPosInWorldGenRange(
		long requestedPos,
		int centerChunkX, int centerChunkZ,
		int maxChunkRadius)
	{
		if (Config.Common.WorldGenerator.generationMaxChunkRadius.get() <= 0)
		{
			return true;
		}
		
		
		DhBlockPos centerBlockPos = new DhChunkPos(centerChunkX, centerChunkZ).centerBlockPos();
		int blockDistanceFromCenter = DhSectionPos.getChebyshevSignedBlockDistance(requestedPos, centerBlockPos);
		int maxBlockRadius = maxChunkRadius * LodUtil.CHUNK_WIDTH;
		boolean requestInRadius = (blockDistanceFromCenter <= maxBlockRadius);
		return requestInRadius;
	}
	
	/** @return -1 for infinity */
	public static int getMaxRegenDistanceInBlocks()
	{
		int lodChunkDist = Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get();
		int lodBlockDist = lodChunkDist * LodUtil.CHUNK_WIDTH;
		
		double percent = Config.Common.WorldGenerator.surfaceRegenMaxDistancePercent.get();
		if (percent < 0.0f)
		{
			return -1;
		}
		
		return (int)(lodBlockDist * percent);
	}
	
	/** @see FullDataUpdatePropagatorV2 */
	public static boolean regenAllowed(FullDataSourceProviderV2 provider)
	{
		if (!(provider instanceof GeneratedFullDataSourceProvider))
		{
			// this provider doesn't allow generation
			return false;
		}
		
		if (!provider.getGeneratorPlan().chunkGenEnabled)
		{
			// chunk gen isn't allowed right now
			return false;
		}
		
		if (Config.Common.WorldGenerator.generatorPlan.get().surfaceGenEnabled
			&& Config.Common.WorldGenerator.chunkGeneratorMode.get() == EDhApiDistantGeneratorMode.PRE_EXISTING_ONLY)
		{
			// Don't try re-generating pre-existing chunks
			// since this will cause holes for missing/empty chunks.
			
			// In the future we may want to change this so it ignores empty chunks instead,
			// but that may cause weird/un-intended behavior for worlds that have intentionally
			// empty chunks.
			return false;
		}
		
		return true;
	}
	
	
	
}
