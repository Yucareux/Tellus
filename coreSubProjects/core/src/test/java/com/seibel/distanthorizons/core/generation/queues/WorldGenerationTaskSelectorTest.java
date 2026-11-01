package com.seibel.distanthorizons.core.generation.queues;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalTask;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Assert;
import org.junit.Test;

public class WorldGenerationTaskSelectorTest
{
	@Test
	public void waitingNearTerrainDoesNotStarveReadyTerrain()
	{
		DataSourceRetrievalTask near = task(6, 0, 0);
		DataSourceRetrievalTask far = task(6, 10, 0);
		Generator generator = new Generator() {
			@Override public byte getGenerationAvailability(int x, int z, int width, byte detail)
			{ return x == 0 ? GENERATION_WAIT : GENERATION_READY; }
		};
		WorldGenerationTaskSelector.Selection selected = WorldGenerationTaskSelector.findClosest(
			Arrays.asList(near, far), generator, DhBlockPos2D.ZERO);
		Assert.assertSame(far, selected.task);
		Assert.assertFalse(near.future.isDone());
	}

	@Test
	public void unavailableTerrainRemainsQueuedUntilReady()
	{
		DataSourceRetrievalTask task = task(8, -2, -3);
		Generator generator = new Generator();
		generator.availability = IDhApiWorldGenerator.GENERATION_WAIT;
		Assert.assertNull(WorldGenerationTaskSelector.findClosest(Collections.singleton(task), generator, DhBlockPos2D.ZERO));
		generator.availability = IDhApiWorldGenerator.GENERATION_READY;
		Assert.assertSame(task, WorldGenerationTaskSelector.findClosest(Collections.singleton(task), generator, DhBlockPos2D.ZERO).task);
		Assert.assertArrayEquals(new int[]{-32, -48, 16, 2}, generator.lastRequest);
	}

	@Test
	public void partiallyReadyLargeAreaSplitsButSmallestAreaWaits()
	{
		Generator generator = new Generator();
		generator.availability = IDhApiWorldGenerator.GENERATION_SPLIT;
		WorldGenerationTaskSelector.Selection split = WorldGenerationTaskSelector.findClosest(
			Collections.singleton(task(8, 0, 0)), generator, DhBlockPos2D.ZERO);
		Assert.assertEquals(IDhApiWorldGenerator.GENERATION_SPLIT, split.availability);
		Assert.assertNull(WorldGenerationTaskSelector.findClosest(Collections.singleton(task(6, 0, 0)), generator, DhBlockPos2D.ZERO));
	}

	@Test
	public void chunkGeneratorsSplitLargeRequestsBeforeCheckingAvailability()
	{
		Generator generator = new Generator();
		generator.maxDetail = 0;
		generator.availability = IDhApiWorldGenerator.GENERATION_WAIT;
		WorldGenerationTaskSelector.Selection split = WorldGenerationTaskSelector.findClosest(
			Collections.singleton(task(8, 0, 0)), generator, DhBlockPos2D.ZERO);
		Assert.assertEquals(IDhApiWorldGenerator.GENERATION_SPLIT, split.availability);
		Assert.assertNull(generator.lastRequest);
	}

	@Test
	public void cancelledRequestsAreNotGenerated()
	{
		DataSourceRetrievalTask cancelled = task(6, 0, 0);
		cancelled.future.cancel(false);
		Assert.assertNull(WorldGenerationTaskSelector.findClosest(Collections.singleton(cancelled), new Generator(), DhBlockPos2D.ZERO));
	}

	@Test
	public void nearestFirstPriorityStillFavorsCoarseTerrain()
	{
		DataSourceRetrievalTask near = task(6, 4, 0);
		DataSourceRetrievalTask coarse = task(9, 0, 0);
		Assert.assertSame(coarse, WorldGenerationTaskSelector.findClosest(
			Arrays.asList(near, coarse), new Generator(), DhBlockPos2D.ZERO).task);
	}

	@Test
	public void oldGeneratorsDefaultToReady()
	{
		IDhApiWorldGenerator stock = new IDhApiWorldGenerator() {
			@Override public void preGeneratorTaskStart() { }
			@Override public void close() { }
		};
		Assert.assertEquals(IDhApiWorldGenerator.GENERATION_READY, stock.getGenerationAvailability(0, 0, 4, (byte) 0));
	}

	private static DataSourceRetrievalTask task(int detail, int x, int z)
	{ return new DataSourceRetrievalTask(DhSectionPos.encode((byte) detail, x, z), (byte) 0); }

	private static class Generator implements IDhApiWorldGenerator
	{
		byte maxDetail = 24;
		byte availability = GENERATION_READY;
		int[] lastRequest;
		@Override public byte getLargestDataDetailLevel() { return maxDetail; }
		@Override public EDhApiWorldGeneratorReturnType getReturnType() { return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES; }
		@Override public byte getGenerationAvailability(int x, int z, int width, byte detail)
		{
			lastRequest = new int[]{x, z, width, detail};
			return availability;
		}
		@Override public void preGeneratorTaskStart() { }
		@Override public void close() { }
	}
}
