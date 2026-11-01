package com.seibel.distanthorizons.core.generation.queues;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalTask;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;

/** Selects work without allocating a distance wrapper for every queued request. */
final class WorldGenerationTaskSelector
{
	static Selection findClosest(Iterable<DataSourceRetrievalTask> tasks,
		IDhApiWorldGenerator generator, DhBlockPos2D target)
	{
		DataSourceRetrievalTask closest = null;
		long closestDistance = Long.MAX_VALUE;
		byte closestAvailability = IDhApiWorldGenerator.GENERATION_WAIT;
		for (DataSourceRetrievalTask task : tasks)
		{
			if (task.future.isDone()) { continue; }
			byte sectionDetail = DhSectionPos.getDetailLevel(task.pos);
			long distance = Math.max(
				Math.abs((long) DhSectionPos.getCenterBlockPosX(task.pos) - target.x),
				Math.abs((long) DhSectionPos.getCenterBlockPosZ(task.pos) - target.z)) / sectionDetail;
			if (closest != null && distance >= closestDistance) { continue; }
			byte dataDetail = (byte) (sectionDetail - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
			byte availability;
			if (dataDetail > generator.getLargestDataDetailLevel())
			{
				availability = IDhApiWorldGenerator.GENERATION_SPLIT;
			}
			else
			{
				byte targetDetail = generator.getReturnType() == EDhApiWorldGeneratorReturnType.API_DATA_SOURCES
					? dataDetail : task.requestDetailLevel;
				availability = generator.getGenerationAvailability(
					DhSectionPos.getMinCornerBlockX(task.pos) >> 4,
					DhSectionPos.getMinCornerBlockZ(task.pos) >> 4, task.widthInChunks, targetDetail);
			}
			if (availability == IDhApiWorldGenerator.GENERATION_SPLIT
				&& sectionDetail <= DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL) { continue; }
			if (availability != IDhApiWorldGenerator.GENERATION_READY
				&& availability != IDhApiWorldGenerator.GENERATION_SPLIT) { continue; }
			closest = task;
			closestDistance = distance;
			closestAvailability = availability;
		}
		return closest == null ? null : new Selection(closest, closestAvailability);
	}

	static final class Selection
	{
		final DataSourceRetrievalTask task;
		final byte availability;
		Selection(DataSourceRetrievalTask task, byte availability)
		{
			this.task = task;
			this.availability = availability;
		}
	}
}
