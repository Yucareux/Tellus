package com.seibel.distanthorizons.core.generation.queues;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalResult;
import com.seibel.distanthorizons.core.generation.tasks.ERetrievalResultState;
import com.seibel.distanthorizons.core.level.DhClientLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.multiplayer.client.AbstractFullDataNetworkRequestQueue;
import com.seibel.distanthorizons.core.multiplayer.client.ClientNetworkState;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.WorldGenUtil;
import com.seibel.distanthorizons.core.util.objects.RollingAverage;
import com.seibel.distanthorizons.core.logging.DhLogger;

import java.util.concurrent.*;

public class RemoteWorldRetrievalQueue extends AbstractFullDataNetworkRequestQueue implements IFullDataSourceRetrievalQueue, IDebugRenderable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private long estimatedTotalChunkCount;
	
	private boolean canRegenerate = false;
	
	private final RollingAverage rollingAverageChunkGenTimeInMs = new RollingAverage(1_000);
	@Override public RollingAverage getRollingAverageChunkGenTimeInMs() { return this.rollingAverageChunkGenTimeInMs; }
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public RemoteWorldRetrievalQueue(ClientNetworkState networkState, DhClientLevel level)
	{ super(networkState, level, false, Config.Client.Advanced.Debugging.DebugWireframe.showWorldGenQueue); }
	
	
	
	//===========================//
	// retrieval queue overrides //
	//===========================//
	
	@Override
	public void startAndSetTargetPos(DhBlockPos2D targetPos) { super.tick(targetPos); }
	
	@Override
	public byte lowestDataDetail() { return FullDataSourceProviderV2.ROOT_SECTION_DETAIL_LEVEL; }
	@Override
	public byte highestDataDetail() { return LodUtil.BLOCK_DETAIL_LEVEL; }
	
	@Override 
	public String getRetrievalTypeName() { return "downloading LODs"; }
	
	@Override
	public CompletableFuture<DataSourceRetrievalResult> submitRetrievalTask(long sectionPos, byte requiredDataDetail)
	{
		long generationStartMsTime = System.currentTimeMillis();
		
		
		CompletableFuture<DataSourceRetrievalResult> future = super.submitRequest(sectionPos, /* client timestamp */null);
		future.thenAccept((DataSourceRetrievalResult result) -> 
		{
			if (result.state == ERetrievalResultState.SUCCESS)
			{
				long totalGenTimeInMs = System.currentTimeMillis() - generationStartMsTime;

				int chunkWidth = DhSectionPos.getChunkWidth(sectionPos);
				int chunkCount = chunkWidth * chunkWidth;
				double timePerChunk = (double) totalGenTimeInMs / (double) chunkCount;

				// only add the time on successes
				// it won't be a perfect estimate but fails will often come back faster, skewing the time faster
				this.rollingAverageChunkGenTimeInMs.add(timePerChunk);
			}
		});
		return future;
	}
	
	@Override
	public boolean getCanRegenerate() { return this.canRegenerate; }
	@Override
	public void setCanRegenerate(boolean canRegen)
	{ this.canRegenerate = canRegen; }
	
	@Override
	public CompletableFuture<Void> startClosingAsync(boolean cancelCurrentGeneration, boolean alsoInterruptRunning)
	{ return super.startClosingAsync(alsoInterruptRunning); }
	
	
	
	//=================================//
	// network request queue overrides //
	//=================================//
	
	@Override
	protected int getRequestRateLimit() { return this.networkState.sessionConfig.getGenerationRequestRateLimit(); }
	@Override
	protected boolean sectionInAllowedGenerationRadius(long sectionPos, DhBlockPos2D targetPos)
	{
		if (this.networkState.sessionConfig.getGenerationMaxChunkRadius() > 0)
		{
			boolean posInRange = WorldGenUtil.isPosInWorldGenRange(
				sectionPos,
				this.networkState.sessionConfig.getGenerationCenterChunkX(), this.networkState.sessionConfig.getGenerationCenterChunkZ(),
				this.networkState.sessionConfig.getGenerationMaxChunkRadius()
				);
			if (!posInRange)
			{
				return false;
			}
		}
		
		return DhSectionPos.getChebyshevSignedBlockDistance(sectionPos, targetPos) <= this.networkState.sessionConfig.getMaxGenerationRequestDistance() * 16;
	}
	@Override
	protected String getQueueName() { return "World Remote Generation Queue"; }
	
	
	
	//===============//
	// debug display //
	//===============//
	
	@Override
	public long getRetrievalEstimatedRemainingChunkCount() { return this.estimatedTotalChunkCount; }
	@Override
	public void setRetrievalEstimatedRemainingChunkCount(long newEstimate) { this.estimatedTotalChunkCount = newEstimate; }
	
	@Override 
	public int getQueuedChunkCount() { return 0; }
	
	
}