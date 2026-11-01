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

package com.seibel.distanthorizons.core.generation.queues;

import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalResult;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.QuadTree.LodQuadTree;
import com.seibel.distanthorizons.core.util.objects.RollingAverage;

import java.io.Closeable;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Used to track what some of the full data sources the system currently
 * wants but doesn't have. <br>
 * IE, what sections should be generated via the world generator. <br><br>
 * 
 * Note: <br>
 * This won't contain every position that needs to be retrieved 
 * since that would cause issues when moving or with extreme
 * render distances. <br><br>
 * 
 * Used by both world gen and server networking.
 * 
 * @see LodQuadTree
 * @see AbstractLodRequestState
 * @see LodRequestModule
 */
public interface IFullDataSourceRetrievalQueue extends Closeable
{
	//=========//
	// getters //
	//=========//
	//region
	
	/** 
	 * The largest numerical detail level. <br>
	 * Detail level is absolute, not section;
	 * IE 0 = Block, 1 = 2x2 blocks, etc.
	 */
	byte lowestDataDetail();
	/** 
	 * The smallest numerical detail level. <br>
	 * Detail level is absolute, not section;
	 * IE 0 = Block, 1 = 2x2 blocks, etc.
	 */
	byte highestDataDetail();
	
	/** 
	 * Returns a value like "downloading" or "generating" depending on how the LODs are being retrieved.
	 * Used to make the progress message easier to understand.
	 */
	String getRetrievalTypeName();
	
	//endregion
	
	
	
	//=======//
	// setup //
	//=======//
	//region
	
	/** 
	 * Starts the retrieval process if not already running,
	 * and if running updates the target position.
	 * 
	 * @param targetPos the position that retrieval should be centered around, 
	 *                  generally this will be the player's position. 
	 * */
	void startAndSetTargetPos(DhBlockPos2D targetPos);
	
	//endregion
	
	
	
	//===============//
	// task handling //
	//===============//
	//region
	
	/**
	 * Used to make sure the low-quality LODs
	 * are generated first (if requested).
	 */
	boolean getCanRegenerate();
	void setCanRegenerate(boolean retrievingLowDetailLods);
	
	/** 
	 * Generally the retrieval queue should be fairly small, so its faster to iterate over the existing list
	 * and check if each one is valid vs dumbly attempting to remove every position that just went out of range.
	 */
	void removeRetrievalRequestIf(DhSectionPos.IPrimitiveLongConsumer removeIf);
	
	/**
	 * Can be used to check if any queued tasks
	 * meet a certain positional state
	 */
	boolean requestPosExistsWhere(DhSectionPos.IPrimitiveLongConsumer returnTrueIf);
	
	CompletableFuture<DataSourceRetrievalResult> submitRetrievalTask(long pos, byte requiredDataDetail);
	
	//endregion
	
	
	
	//==========//
	// shutdown //
	//==========//
	//region
	
	/** Can be used to let any lingering generation requests finish before fully shutting down the system */
	CompletableFuture<Void> startClosingAsync(boolean cancelCurrentGeneration, boolean alsoInterruptRunning);
	
	@Override
	void close();
	
	//endregion
	
	
	
	//===============//
	// debug display //
	//===============//
	//region
	
	int getWaitingTaskCount();
	int getInProgressTaskCount();
	
	/** returns how many chunks are currently queued for retrieval */
	int getQueuedChunkCount();
	
	/** used for displaying a progress update to the user */
	long getRetrievalEstimatedRemainingChunkCount();
	void setRetrievalEstimatedRemainingChunkCount(long newEstimate);

	void addDebugMenuStringsToList(List<String> messageList);
	
	/** Can be used to determine roughly how fast the world generator is running. */
	RollingAverage getRollingAverageChunkGenTimeInMs();
	
	//endregion
	
	
	
}
