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

package com.seibel.distanthorizons.core.util.threading;

import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftSharedWrapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.*;

/**
 * Holds each thread pool the system uses.
 * 
 * @see ThreadUtil
 */
public class ThreadPoolUtil
{
	private static final IMinecraftSharedWrapper MC_SHARED = SingletonInjector.INSTANCE.get(IMinecraftSharedWrapper.class);
	
	
	
	//=========================//
	// standalone thread pools //
	//=========================//
	
	// standalone thread pools all handle independent systems
	// and don't interfere with any other pool
	
	private static PriorityTaskPicker taskPicker;
	
	private static PriorityTaskPicker.Executor fileHandlerThreadPool;
	@Nullable
	public static PriorityTaskPicker.Executor getFileHandlerExecutor() { return fileHandlerThreadPool; }
	
	private static PriorityTaskPicker.Executor renderSectionLoadThreadPool;
	@Nullable
	public static PriorityTaskPicker.Executor getRenderLoadingExecutor() { return renderSectionLoadThreadPool; }
	
	private static PriorityTaskPicker.Executor updatePropagatorThreadPool;
	@Nullable
	public static PriorityTaskPicker.Executor getUpdatePropagatorExecutor() { return updatePropagatorThreadPool; }
	
	private static PriorityTaskPicker.Executor worldGenThreadPool;
	@Nullable
	public static PriorityTaskPicker.Executor getWorldGenExecutor() { return worldGenThreadPool; }
	
	public static final String CLEANUP_THREAD_NAME = "Cleanup";
	private static final ThreadPoolExecutor cleanupThreadPool = ThreadUtil.makeSingleDaemonThreadPool(CLEANUP_THREAD_NAME);
	/** not null since cleanup always needs to be run even when DH has been shut down */
	@NotNull
	public static ThreadPoolExecutor getCleanupExecutor() { return cleanupThreadPool; }
	
	public static final String BEACON_CULLING_THREAD_NAME = "Beacon Culling";
	private static ThreadPoolExecutor beaconCullingThreadPool;
	@Nullable
	public static ThreadPoolExecutor getBeaconCullingExecutor() { return beaconCullingThreadPool; }
	
	// The main distinction between these thread pools is that one for compression has multiple threads and client handler is single-threaded
	private static PriorityTaskPicker.Executor networkCompressionThreadPool;
	@Nullable
	public static PriorityTaskPicker.Executor getNetworkCompressionExecutor() { return networkCompressionThreadPool; }
	
	private static ThreadPoolExecutor networkClientHandlerThreadPool;
	@Nullable
	public static ThreadPoolExecutor networkClientHandlerExecutor() { return networkClientHandlerThreadPool; }
	
	
	public static final String FULL_DATA_MIGRATION_THREAD_NAME = "Full Data Migration";
	private static ThreadPoolExecutor fullDataMigrationThreadPool;
	@Nullable
	public static ThreadPoolExecutor getFullDataMigrationExecutor() { return fullDataMigrationThreadPool; }
	
	
	private static PriorityTaskPicker.Executor chunkToLodBuilderThreadPool;
	@Nullable
	public static PriorityTaskPicker.Executor getChunkToLodBuilderExecutor() { return chunkToLodBuilderThreadPool; }
	
	
	
	//=================//
	// setup / cleanup //
	//=================//
	///region
	
	public static void setupThreadPools()
	{
		//==================//
		// main thread pool //
		//==================//
		
		if (taskPicker != null)
		{
			taskPicker.shutdownNow();
		}
		taskPicker = new PriorityTaskPicker();
		
		networkCompressionThreadPool = taskPicker.createExecutor("Network Compression");
		networkClientHandlerThreadPool = ThreadUtil.makeSingleThreadPool("Network Client Handler");
		fileHandlerThreadPool = taskPicker.createExecutor("IO");
		renderSectionLoadThreadPool = taskPicker.createExecutor("Render Loader", Thread.NORM_PRIORITY + 1, ThreadPoolUtil::renderLoaderThreadsCanRun) // higher priority since we want LODs to load in above all else to give the best impression of speed
			// but not all threads: data updates (world gen, chunk updates near a moving player) keep queuing reloads,
			// which would otherwise leave world gen and the update propagator without any threads
			.limitThreadShareWhileOthersWait(0.5);
		chunkToLodBuilderThreadPool = taskPicker.createExecutor("LOD Builder");
		updatePropagatorThreadPool = taskPicker.createExecutor("Update Propagator", Thread.NORM_PRIORITY, ThreadPoolUtil::updatePropagatorThreadsCanRun); // the update propagator isn't necessary when moving through the world, so we'll pause it along with the world generator when moving fast
		worldGenThreadPool = taskPicker.createExecutor("World Gen", Thread.NORM_PRIORITY, ThreadPoolUtil::worldGenThreadsCanRun);
		
		
		
		//=========================//
		// standalone thread pools //
		//=========================//
		
		if (beaconCullingThreadPool != null)
		{
			beaconCullingThreadPool.shutdown();
		}
		beaconCullingThreadPool = ThreadUtil.makeSingleThreadPool(BEACON_CULLING_THREAD_NAME);
		
		if (fullDataMigrationThreadPool != null)
		{
			fullDataMigrationThreadPool.shutdown();
		}
		fullDataMigrationThreadPool = ThreadUtil.makeSingleThreadPool(FULL_DATA_MIGRATION_THREAD_NAME);
		
	}
	
	public static void shutdownThreadPools()
	{
		// standalone threads
		networkClientHandlerThreadPool.shutdownNow();
		taskPicker.shutdownNow();
		beaconCullingThreadPool.shutdown();
		fullDataMigrationThreadPool.shutdown();
	}
	
	///endregion
	
	
	
	//================//
	// helper methods //
	//================//
	///region
	
	/**
	 * Some thread pools are very heavy (IE world gen)
	 * making LOD load times slower. Pausing those
	 * threads when the player is moving can provide a better experience. <br><br>
	 * 
	 * all speeds are measured in blocks per second 
	 * 
	 * @see LodUtil#WALKING_SPEED_IN_BLOCKS_PER_SEC
	 * @see LodUtil#SPRINTING_SPEED_IN_BLOCKS_PER_SEC
	 * @see LodUtil#ROCKET_ELYTRA_SPEED_IN_BLOCKS_PER_SEC
	 * @see LodUtil#MAX_SPECTATOR_SPEED_IN_BLOCKS_PER_SEC
	 */
	public static boolean worldGenThreadsCanRun()
	{
		if (cameraMovingFast())
		{
			return false;
		}
		
		if (chunkBuilderOverloaded())
		{
			return false;
		}
		
		if (Config.Common.WorldGenerator.limitIfServerUnhealthy.get()
			&& !MC_SHARED.isServerThreadHealthy())
		{
			return false;
		}
		
		return true;
	}
	
	public static boolean updatePropagatorThreadsCanRun()
	{
		if (cameraMovingFast())
		{
			return false;
		}
		
		if (chunkBuilderOverloaded())
		{
			return false;
		}
		
		PriorityTaskPicker.Executor lodBuilderExecutor = getChunkToLodBuilderExecutor();
		if (lodBuilderExecutor != null
			&& !lodBuilderExecutor.isTerminated())
		{
			// Don't run updates if there are chunks that need processing.
			// This is especially important when running with Chunky.
			int maxQueueSize = lodBuilderExecutor.getPoolSize() * 200;
			if (lodBuilderExecutor.getQueueSize() > maxQueueSize)
			{
				return false;
			}
		}
		
		return true;
	}
	
	
	
	/**
	 * Built render data waits for the render thread to upload it,
	 * which only uploads for part of each frame.
	 * Pausing here once that backlog is a pool's worth deep keeps the number of
	 * waiting staging buffers (and the memory they hold) bounded
	 * and leaves the threads to the other executors in the meantime.
	 */
	public static boolean renderLoaderThreadsCanRun()
	{
		return LodRenderSection.getPendingUploadCount() < Config.Common.MultiThreading.numberOfThreads.get();
	}
	
	private static boolean cameraMovingFast()
	{
		if (!Config.Common.WorldGenerator.pauseIfMovingQuickly.get())
		{
			return false;
		}
		
		double cameraSpeed = ClientApi.INSTANCE.getAvgCameraSpeed();
		// stop these threads if moving a little bit slower than max elytra speed
		double maxAllowedSpeed = (LodUtil.ROCKET_ELYTRA_SPEED_IN_BLOCKS_PER_SEC - 10.0);
		if (cameraSpeed > maxAllowedSpeed)
		{
			// pause if the user is moving too fast
			return true;
		}
		
		return false;
	}
	
	private static boolean chunkBuilderOverloaded()
	{
		// rarely the builder executor can queue up
		// thousands of tasks,
		// if this happens we want to limit other executors
		// so these tasks can be hanlded.
		PriorityTaskPicker.Executor ex = getChunkToLodBuilderExecutor();
		if (ex != null
			&& ex.getQueueSize() > 1000)
		{
			return true;
		}
		
		return false;
	}
	
	///endregion
	
	
	
}
