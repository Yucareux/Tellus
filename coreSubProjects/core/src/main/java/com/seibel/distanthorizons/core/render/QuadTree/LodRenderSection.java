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

package com.seibel.distanthorizons.core.render.QuadTree;

import com.seibel.distanthorizons.api.enums.rendering.EDhApiTransparency;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.ColumnRenderBufferBuilder;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodQuadBuilder;
import com.seibel.distanthorizons.core.dataObjects.transformers.FullDataToRenderDataTransformer;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.renderer.AbstractDebugWireframeRenderer;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.util.ExceptionUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout;
import com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import org.jetbrains.annotations.Nullable;

import javax.annotation.WillNotClose;
import java.awt.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A render section represents an area that could be rendered.
 * For more information see {@link LodQuadTree}.
 */
public class LodRenderSection implements IDebugRenderable, AutoCloseable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	private static final IMinecraftClientWrapper MC = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	private static final AbstractDebugWireframeRenderer DEBUG_RENDERER = SingletonInjector.INSTANCE.get(AbstractDebugWireframeRenderer.class);
	
	/** render data built and waiting for the render thread to upload it */
	private static final AtomicInteger PENDING_UPLOAD_COUNT = new AtomicInteger(0);
	public static int getPendingUploadCount() { return PENDING_UPLOAD_COUNT.get(); }
	
	
	
	public final long pos;
	
	private final IDhClientLevel clientLevel;
	private final IClientLevelWrapper clientLevelWrapper;
	@WillNotClose
	private final FullDataSourceProviderV2 fullDataSourceProvider;
	private final LodQuadTree quadTree;
	
	
	private boolean renderingEnabled = false;
	/** 
	 * Used when a node goes out of render distance
	 * but isn't removed from the underlying quad tree structure. <br><br>
	 * 
	 * In those cases we should act as if the node was removed
	 * for cached render data caching purposes, but not
	 * for re-creating missing nodes.
	 */
	public boolean renderDataDirty = false;
	public boolean queuedMissingSectionsForRetrieval = false;
	
	/** this reference is necessary so we can determine what VBO to render */
	public LodBufferContainer renderBufferContainer; 
	
	/**
	 * True when the uploaded render data was built from LOD data covering this section's whole area. <br>
	 * Sections that haven't been generated yet (or were only partially filled from their children)
	 * still upload, but with nothing (or only part of the area) to draw. {@link LodQuadTree} uses
	 * this to keep drawing a neighboring detail level over those areas instead of leaving a hole.
	 */
	private volatile boolean renderDataComplete = false;
	/** set by {@link #getRenderSourceForPos} while building, applied when that build's buffers are uploaded */
	private boolean builtDataComplete = false;
	
	
	/** 
	 * Encapsulates everything between pulling data from the database (including neighbors)
	 * up to the point when geometry data is uploaded to the GPU.
	 */
	private final AtomicReference<CompletableFuture<Void>> getAndBuildRenderDataFutureRef = new AtomicReference<>(null);
	
	/** 
	 * used alongside {@link LodRenderSection#getAndBuildRenderDataFutureRef} so we can remove
	 * unnecessary tasks from the executor.
	 */
	private Runnable getAndBuildRenderDataRunnable = null;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region constructor
	
	public LodRenderSection(
			long pos, 
			LodQuadTree quadTree, 
			IDhClientLevel clientLevel, FullDataSourceProviderV2 fullDataSourceProvider)
	{
		this.pos = pos;
		this.quadTree = quadTree;
		this.clientLevel = clientLevel;
		this.clientLevelWrapper = clientLevel.getClientLevelWrapper();
		this.fullDataSourceProvider = fullDataSourceProvider;
		
		DEBUG_RENDERER.register(this, Config.Client.Advanced.Debugging.DebugWireframe.showRenderSectionStatus);
	}
	
	//endregion constructor
	
	
	
	//======================================//
	// render data generation and uploading //
	//======================================//
	//region render data uploading
	
	/** @return true if the upload started, false if it wasn't able to for any reason */
	public synchronized boolean uploadRenderDataToGpuAsync()
	{
		if (this.getAndBuildRenderDataFutureRef.get() != null)
		{
			// don't accidentally queue multiple uploads at the same time
			return false;
		}
		
		PriorityTaskPicker.Executor executor = ThreadPoolUtil.getRenderLoadingExecutor();
		if (executor == null || executor.isTerminated())
		{
			return false;
		}
		
		CompletableFuture<Void> future = new CompletableFuture<>();
		future.handle((voidObj, throwable) ->
		{
			// the task is done, we don't need to track these anymore
			this.getAndBuildRenderDataFutureRef.compareAndSet(future, null);
			this.getAndBuildRenderDataRunnable = null;
			
			return null;
		});
		
		try
		{
			// shouldn't happen since this method is synchronized, but just in case
			// make sure we only ever start one upload task
			if (!this.getAndBuildRenderDataFutureRef.compareAndSet(null, future))
			{
				CompletableFuture<Void> oldFuture = this.getAndBuildRenderDataFutureRef.get();
				LodUtil.assertTrue(oldFuture != null, "Concurrency error");
				return true;
			}
			
			
			this.getAndBuildRenderDataRunnable = () ->
			{
				try
				{
					// build LOD data on a DH thread
					try (LodQuadBuilder lodQuadBuilder = this.getAndBuildRenderData())
					{
						if (lodQuadBuilder == null)
						{
							future.complete(null);
							return;
						}
						boolean dataComplete = this.builtDataComplete;
						
						// 3 because that's generally the max needed
						PhantomArrayListCheckout opaqueCheckout = LodQuadBuilder.ARRAY_LIST_POOL.checkoutByteBuffers(3);
						PhantomArrayListCheckout transparentCheckout = LodQuadBuilder.ARRAY_LIST_POOL.checkoutByteBuffers(3);
						
						// create CPU vertex buffers
						ArrayList<ByteBuffer> opaqueBuffers = lodQuadBuilder.makeOpaqueVertexBuffers(opaqueCheckout);
						ArrayList<ByteBuffer> transparentBuffers = lodQuadBuilder.makeTransparentVertexBuffers(transparentCheckout);
						
						// Uploading will primarily happen on the render thread.
						// This thread doesn't wait for it: the render thread only uploads for part of each frame,
						// so at low frame rates every loader thread sat waiting here and world generation
						// and the update propagator got no threads at all.
						// Queuing up potentially thousands of upload tasks (each requiring their own staging buffer)
						// is prevented by pausing the render loader instead while too many uploads are waiting,
						// see ThreadPoolUtil#renderLoaderThreadsCanRun.
						PENDING_UPLOAD_COUNT.incrementAndGet();
						CompletableFuture<LodBufferContainer> uploadFuture;
						try
						{
							uploadFuture = this.uploadToGpuAsync(future, opaqueBuffers, transparentBuffers, dataComplete);
						}
						catch (RuntimeException e)
						{
							PENDING_UPLOAD_COUNT.decrementAndGet();
							opaqueCheckout.close();
							transparentCheckout.close();
							throw e;
						}
							
						uploadFuture.whenComplete((bufferContainer, throwable) ->
						{
							PENDING_UPLOAD_COUNT.decrementAndGet();
							opaqueCheckout.close();
							transparentCheckout.close();
							
							// the future is passed in separately (IE not using the local var) 
							// to prevent any possible race condition null pointers
							if (throwable != null)
							{
								future.completeExceptionally(throwable);
							}
							else
							{
								future.complete(null);
							}
							
							// render loading may have been paused waiting for this upload
							PriorityTaskPicker.Executor renderLoadExecutor = ThreadPoolUtil.getRenderLoadingExecutor();
							if (renderLoadExecutor != null)
							{
								renderLoadExecutor.wakeUp();
							}
						});
					}
				}
				catch (Exception e)
				{
					LOGGER.error("Unexpected issue creating render data for pos: ["+DhSectionPos.toString(this.pos)+"], error: ["+e.getMessage()+"].", e);
					future.completeExceptionally(e);
				}
			};
			executor.execute(this.getAndBuildRenderDataRunnable);
			
			return true;
		}
		catch (RejectedExecutionException ignore)
		{
			future.complete(null);
			
			/* the thread pool was probably shut down because it's size is being changed, just wait a sec and it should be back */
			return false;
		}
	}
	
	
	//=======================//
	// Get LOD ID data       //
	// and build render data //
	//=======================//
	//region
	
	@Nullable
	private synchronized LodQuadBuilder getAndBuildRenderData()
	{
		try (ColumnRenderSource thisRenderSource = this.getRenderSourceForPos(this.pos, null))
		{
			if (thisRenderSource == null)
			{
				// nothing needs to be rendered
				return null;
			}
			
			
			boolean enableTransparency = Config.Client.Advanced.Graphics.Quality.transparency.get() == EDhApiTransparency.COMPLETE;
			LodQuadBuilder lodQuadBuilder = LodQuadBuilder.getBuilder(enableTransparency, this.clientLevelWrapper);
			
			
			// get the adjacent positions
			try (ColumnRenderSource northRenderSource = this.getRenderSourceForPos(this.pos, EDhDirection.NORTH);
				ColumnRenderSource southRenderSource = this.getRenderSourceForPos(this.pos, EDhDirection.SOUTH);
				ColumnRenderSource eastRenderSource = this.getRenderSourceForPos(this.pos, EDhDirection.EAST);
				ColumnRenderSource westRenderSource = this.getRenderSourceForPos(this.pos, EDhDirection.WEST))
			{
				ColumnRenderSource[] adjacentRenderSections = new ColumnRenderSource[EDhDirection.CARDINAL_COMPASS.length];
				adjacentRenderSections[EDhDirection.NORTH.compassIndex] = northRenderSource;
				adjacentRenderSections[EDhDirection.SOUTH.compassIndex] = southRenderSource;
				adjacentRenderSections[EDhDirection.EAST.compassIndex] = eastRenderSource;
				adjacentRenderSections[EDhDirection.WEST.compassIndex] = westRenderSource;

				boolean[] adjIsSameDetailLevel = new boolean[EDhDirection.CARDINAL_COMPASS.length];
				adjIsSameDetailLevel[EDhDirection.NORTH.compassIndex] = this.isAdjacentPosSameDetailLevel(EDhDirection.NORTH);
				adjIsSameDetailLevel[EDhDirection.SOUTH.compassIndex] = this.isAdjacentPosSameDetailLevel(EDhDirection.SOUTH);
				adjIsSameDetailLevel[EDhDirection.EAST.compassIndex] = this.isAdjacentPosSameDetailLevel(EDhDirection.EAST);
				adjIsSameDetailLevel[EDhDirection.WEST.compassIndex] = this.isAdjacentPosSameDetailLevel(EDhDirection.WEST);

				// the render sources are only needed by this synchronous method,
				// then they can be closed
				ColumnRenderBufferBuilder.makeLodRenderData(lodQuadBuilder, thisRenderSource, this.clientLevelWrapper, adjacentRenderSections, adjIsSameDetailLevel);
				return lodQuadBuilder;
			}
			catch (Exception e)
			{
				LOGGER.error("Unexpected error while loading LodRenderSection [" + DhSectionPos.toString(this.pos) + "] adjacent data, Error: [" + e.getMessage() + "].", e);
			}
		}
		catch (Exception e)
		{
			LOGGER.error("Unexpected error while loading LodRenderSection ["+DhSectionPos.toString(this.pos)+"], Error: [" + e.getMessage() + "].", e);
		}
		
		return null;
	}
	/** 
	 * async is done so each thread can run without waiting on others 
	 * @param direction the direction to load relative to the given position, null will return the given position
	 */
	private ColumnRenderSource getRenderSourceForPos(long pos, @Nullable EDhDirection direction) 
	{
		if (direction != null)
		{
			pos = DhSectionPos.getAdjacentPos(pos, direction);
		}
		final long finalPos = pos;
		
		
		try (FullDataSourceV2 fullDataSource =
			// no direction means get the center LOD		
			(direction == null)
				? this.fullDataSourceProvider.get(finalPos)
				: this.fullDataSourceProvider.getAdjForDirection(finalPos, direction.opposite()))
		{
			if (direction == null)
			{
				this.builtDataComplete = coversWholeSection(fullDataSource);
			}
			
			ColumnRenderSource columnRenderSource = FullDataToRenderDataTransformer.transformFullDataToRenderSource(fullDataSource, this.clientLevelWrapper);
			return columnRenderSource;
		}
		catch (Exception e)
		{
			LOGGER.error("Unexpected issue creating render data for pos: ["+DhSectionPos.toString(finalPos)+"], error: ["+e.getMessage()+"].", e);
			return null;
		}
	}
	/** @return true if every column has LOD data, generated at this detail level or down sampled from finer ones */
	private static boolean coversWholeSection(@Nullable FullDataSourceV2 fullDataSource)
	{
		if (fullDataSource == null || fullDataSource.isEmpty)
		{
			return false;
		}
		
		ByteArrayList columnGenerationSteps = fullDataSource.columnGenerationSteps;
		for (int i = 0; i < columnGenerationSteps.size(); i++)
		{
			if (columnGenerationSteps.getByte(i) == EDhApiWorldGenerationStep.EMPTY.value)
			{
				return false;
			}
		}
		return true;
	}
	private boolean isAdjacentPosSameDetailLevel(EDhDirection direction)
	{
		long adjPos = DhSectionPos.getAdjacentPos(this.pos, direction);
		byte detailLevel = this.quadTree.calcExpectedDetailLevel(new DhBlockPos2D(MC.getPlayerBlockPos()), adjPos);
		detailLevel += DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
		return detailLevel == DhSectionPos.getDetailLevel(this.pos);
	}
	
	//endregion
	
	
	private synchronized CompletableFuture<LodBufferContainer> uploadToGpuAsync(
		CompletableFuture<Void> parentFuture,
		ArrayList<ByteBuffer> opaqueBuffers,
		ArrayList<ByteBuffer> transparentBuffers,
		boolean dataComplete)
	{
		CompletableFuture<LodBufferContainer> uploadFuture = LodBufferContainer.tryMakeAndUploadBuffersAsync(
			this.pos, this.clientLevelWrapper, 
			opaqueBuffers, 
			transparentBuffers);
		// returning the chained future lets callers act once the new buffers are in place
		return uploadFuture.whenComplete((bufferContainer, e) ->
		{
			try
			{
				// handle errors and early shutdown
				if (e != null)
				{
					if (!ExceptionUtil.isShutdownException(e))
					{
						LOGGER.error("Unexpected issue uploading buffers for pos: [" + DhSectionPos.toString(this.pos) + "], error: [" + e.getMessage() + "].", e);
					}
					
					if (bufferContainer != null)
					{
						// shouldn't happen, but just in case
						bufferContainer.close();
					}
					return;
				}
				
				// close the old container
				LodBufferContainer oldContainer = this.renderBufferContainer;
				this.renderDataComplete = dataComplete;
				this.renderBufferContainer = bufferContainer.buffersUploaded ? bufferContainer : null;
				if (oldContainer != null)
				{
					oldContainer.close();
				}
				
				// upload complete
				this.renderDataDirty = false;
				
				
				if (parentFuture.isCancelled())
				{
					// if the parent future was canceled that likely means
					// this LodRenderSection was closed before this point,
					// meaning this buffer will become homeless, 
					// so we need to clean it up here
					bufferContainer.close();
				}
			}
			catch (Exception finishEx)
			{
				LOGGER.error("Unexpected buffer finish exception: ["+finishEx.getMessage()+"]", finishEx);
			}
		});
	}
	
	//endregion render data uploading
	
	
	
	//=================//
	// rendering state //
	//=================//
	//region
	
	public boolean canRender() { return this.renderBufferContainer != null; }
	/** @return true if this section can render and its render data covers its whole area */
	public boolean canRenderWholeArea() { return this.renderDataComplete && this.canRender(); }
	public boolean gpuUploadComplete() 
	{ 
		return this.renderBufferContainer != null
			// render dirty is here so we can trigger new GPU uploads
			// even if the render data is present
			&& !this.renderDataDirty; 
	}
	
	public boolean getRenderingEnabled() { return this.renderingEnabled; }
	public void setRenderingEnabled(boolean enabled) { this.renderingEnabled = enabled;}
	
	public boolean gpuUploadInProgress() { return this.getAndBuildRenderDataFutureRef.get() != null; }
	
	//endregion
	
	
	
	//==============//
	// base methods //
	//==============//
	//region base methods
	
	@Override
	public void debugRender(AbstractDebugWireframeRenderer debugRenderer)
	{
		Color color = Color.red;
		if (this.renderingEnabled)
		{
			//color = Color.green;
			return;
		}
		else if (this.getAndBuildRenderDataFutureRef.get() != null)
		{
			color = Color.yellow;
		}
		else if (this.canRender())
		{
			//color = Color.cyan;
			return;
		}
		
		int levelMinY = this.clientLevelWrapper.getMinHeight();
		int levelMaxY = this.clientLevelWrapper.getMaxHeight();
		
		// show the wireframe a bit lower than world max height,
		// since most worlds don't render all the way up to the max height
		int levelHeightRange = (levelMaxY - levelMinY);
		int maxY = levelMaxY - (levelHeightRange / 2);
		
		debugRenderer.renderBox(new AbstractDebugWireframeRenderer.Box(this.pos, levelMinY, maxY, 0.01f, color));
	}
	
	@Override
	public String toString()
	{
		return  "pos=[" + DhSectionPos.toString(this.pos) + "] " +
				"enabled=[" + this.getRenderingEnabled() + "] " +
				"canRender=[" + this.canRender() + "] " +	
				"uploading=[" + this.gpuUploadInProgress() + "] "
				;
	}
	
	@Override
	public void close()
	{
		DEBUG_RENDERER.unregister(this, Config.Client.Advanced.Debugging.DebugWireframe.showRenderSectionStatus);
		
		if (Config.Client.Advanced.Debugging.DebugWireframe.showRenderSectionStatus.get())
		{
			// show a particle for the closed section
			DEBUG_RENDERER.makeParticle(
				new AbstractDebugWireframeRenderer.BoxParticle(
					new AbstractDebugWireframeRenderer.Box(this.pos, 128f, 156f, 0.09f, Color.RED.darker()),
					0.5, 32f
				)
			);
		}
		
		
		// render loading is no longer needed
		CompletableFuture<Void> buildFuture = this.getAndBuildRenderDataFutureRef.get();
		if (buildFuture != null)
		{
			// remove the task from our executor if present
			// note: don't cancel the task since that prevents cleanup, we just don't want it to run
			PriorityTaskPicker.Executor renderLoaderExecutor = ThreadPoolUtil.getRenderLoadingExecutor();
			if (renderLoaderExecutor != null 
				&& !renderLoaderExecutor.isTerminated())
			{
				Runnable runnable = this.getAndBuildRenderDataRunnable;
				if (runnable != null)
				{
					renderLoaderExecutor.remove(runnable);
				}
			}
			
			// cancel the future after removing the runnable
			// to make sure the runnable is properly removed
			buildFuture.cancel(true);
		}
		
		
		this.setRenderingEnabled(false);
		if (this.renderBufferContainer != null)
		{
			this.renderBufferContainer.close();
		}
		
	}
	
	//endregion base methods
	
	
	
}
