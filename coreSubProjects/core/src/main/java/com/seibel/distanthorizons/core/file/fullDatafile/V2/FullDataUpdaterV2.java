package com.seibel.distanthorizons.core.file.fullDatafile.V2;

import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.fullDatafile.IDataSourceUpdateListenerFunc;
import com.seibel.distanthorizons.core.generation.DhLightingEngine;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.renderer.AbstractDebugWireframeRenderer;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.threading.PositionalLockProvider;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class FullDataUpdaterV2 implements IDebugRenderable, AutoCloseable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	protected final PositionalLockProvider updateLockProvider = new PositionalLockProvider();
	/**
	 * generally just used for debugging,
	 * keeps track of which positions are currently locked.
	 */
	public final Set<Long> lockedPosSet = ConcurrentHashMap.newKeySet();
	private final ConcurrentHashMap<Long, AtomicInteger> queuedUpdateCountsByPos = new ConcurrentHashMap<>();
	
	private final Set<IDataSourceUpdateListenerFunc<FullDataSourceV2>> dateSourceUpdateListenerSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
	
	private final String levelId;
	private final AtomicBoolean isShutdownRef = new AtomicBoolean(false);
	
	private final FullDataSourceProviderV2 provider;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public FullDataUpdaterV2(FullDataSourceProviderV2 provider, String levelId)
	{
		this.provider = provider;
		this.levelId = levelId;
		
	}
	
	//endregion
	
	
	
	//===============//
	// data updating //
	//===============//
	//region
	
	/**
	 * Can be used if you don't want to lock the current thread
	 * Otherwise the sync version {@link FullDataUpdaterV2#updateDataSource(FullDataSourceV2)} may be a better choice.
	 */
	public CompletableFuture<Void> updateDataSourceAsync(@NotNull FullDataSourceV2 inputDataSource)
	{
		if (this.isShutdownRef.get())
		{
			return CompletableFuture.completedFuture(null);
		}
		
		AbstractExecutorService executor = ThreadPoolUtil.getChunkToLodBuilderExecutor();
		if (executor == null || executor.isTerminated())
		{
			return CompletableFuture.completedFuture(null);
		}
		
		
		try
		{
			this.markUpdateStart(inputDataSource.getPos());
			return CompletableFuture.runAsync(() ->
			{
				try
				{
					this.updateDataSource(inputDataSource);
				}
				catch (Exception e)
				{
					LOGGER.error("Unexpected error in async data source update at pos: ["+ DhSectionPos.toString(inputDataSource.getPos())+"], error: ["+e.getMessage()+"].", e);
				}
				finally
				{
					this.markUpdateEnd(inputDataSource.getPos());
				}
			}, executor);
		}
		catch (RejectedExecutionException ignore)
		{
			// can happen if the executor was shutdown while this task was queued
			this.markUpdateEnd(inputDataSource.getPos());
			return CompletableFuture.completedFuture(null);
		}
	}
	
	/** After this method returns the inputData will be written to file. */
	public void updateDataSource(@NotNull FullDataSourceV2 inputData)
	{
		if (this.isShutdownRef.get())
		{
			return;
		}
		
		
		long updatePos = inputData.getPos();
		
		// a lock is necessary to prevent two threads from writing to the same position at once,
		// if that happens only the second update will apply and the LOD will end up with hole(s)
		ReentrantLock updateLock = this.updateLockProvider.getLock(updatePos);
		
		try
		{
			updateLock.lock();
			this.lockedPosSet.add(updatePos);
			
			
			// get or create the data source
			try (FullDataSourceV2 recipientDataSource = this.provider.get(updatePos))
			{
				if (recipientDataSource != null) // will be null if the repo was shut down
				{
					boolean dataModified = recipientDataSource.updateFromDataSource(inputData);
					if (dataModified)
					{
						// Only update sky lighting at the lowest level,
						// everything above that gets that generated lighting for free
						// via downsampling.
						if (DhSectionPos.getDetailLevel(updatePos) == DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL)
						{
							// Block lights should have been populated at the chunkWrapper stage.
							// Sky lights must be re-calculated at this stage in order to prevent an issue
							// where overhangs (specifically floating islands) have 0 sky light when they shouldn't.
							DhLightingEngine.INSTANCE.bakeDataSourceSkyLight(recipientDataSource, this.provider.levelWrapper.hasSkyLight() ? LodUtil.MAX_MC_LIGHT : LodUtil.MIN_MC_LIGHT);
						}
						
						
						// save the updated data to the database
						try (FullDataSourceV2DTO dto = this.createDtoFromDataSource(recipientDataSource))
						{
							if (dto != null)
							{
								this.provider.repo.save(dto);
							}
						}
						
						
						for (IDataSourceUpdateListenerFunc<FullDataSourceV2> listener : this.dateSourceUpdateListenerSet)
						{
							if (listener != null)
							{
								listener.OnDataSourceUpdated(recipientDataSource);
							}
						}
					}
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.error("Error updating pos ["+DhSectionPos.toString(updatePos)+"], error: "+e.getMessage(), e);
		}
		finally
		{
			updateLock.unlock();
			this.lockedPosSet.remove(updatePos);
		}
	}
	
	private FullDataSourceV2DTO createDtoFromDataSource(FullDataSourceV2 dataSource)
	{
		try
		{
			// when creating new data use the compressor currently selected in the config
			EDhApiDataCompressionMode compressionModeEnum = Config.Common.LodBuilding.dataCompression.get();
			return FullDataSourceV2DTO.CreateFromDataSource(dataSource, compressionModeEnum);
		}
		catch (IOException e)
		{
			LOGGER.warn("Unable to create DTO, error: ["+e.getMessage() + "].", e);
			return null;
		}
	}
	
	//endregion
	
	
	
	//========//
	// events //
	//========//
	//region
	
	public void addDataSourceUpdateListener(IDataSourceUpdateListenerFunc<FullDataSourceV2> listener)
	{ this.dateSourceUpdateListenerSet.add(listener); }
	public void removeDataSourceUpdateListener(IDataSourceUpdateListenerFunc<FullDataSourceV2> listener)
	{ this.dateSourceUpdateListenerSet.remove(listener); }
	
	//endregion
	
	
	
	//==================//
	// debugger methods //
	//==================//
	//region
	
	/** used for debugging to track which positions are queued for updating */
	private void markUpdateStart(long dataSourcePos)
	{
		this.queuedUpdateCountsByPos.compute(dataSourcePos, (pos, atomicCount) ->
		{
			if (atomicCount == null)
			{
				atomicCount = new AtomicInteger(0);
			}
			atomicCount.incrementAndGet();
			return atomicCount;
		});
	}
	/** used for debugging to track which positions are queued for updating */
	private void markUpdateEnd(long dataSourcePos)
	{
		this.queuedUpdateCountsByPos.compute(dataSourcePos, (pos, atomicCount) ->
		{
			if (atomicCount != null && atomicCount.decrementAndGet() <= 0)
			{
				atomicCount = null;
			}
			return atomicCount;
		});
	}
	
	//endregion
	
	
	
	//===========//
	// overrides //
	//===========//
	//region
	
	@Override
	public void debugRender(AbstractDebugWireframeRenderer renderer)
	{
		this.lockedPosSet
				.forEach((pos) -> { renderer.renderBox(new AbstractDebugWireframeRenderer.Box(pos, -32f, 74f, 0.15f, Color.PINK)); });
		
		this.queuedUpdateCountsByPos
				.forEach((pos, updateCountRef) -> { renderer.renderBox(new AbstractDebugWireframeRenderer.Box(pos, -32f, 80f + (updateCountRef.get() * 16f), 0.20f, Color.WHITE)); });
	}
	
	@Override
	public void close() { this.isShutdownRef.set(true); }
	
	//endregion
	
	
	
}
