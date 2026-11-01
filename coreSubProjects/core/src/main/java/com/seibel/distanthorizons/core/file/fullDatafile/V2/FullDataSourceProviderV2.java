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

package com.seibel.distanthorizons.core.file.fullDatafile.V2;

import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiGeneratorPlan;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.file.fullDatafile.IDataSourceUpdateListenerFunc;
import com.seibel.distanthorizons.core.file.structure.ISaveStructure;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalResult;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.renderer.AbstractDebugWireframeRenderer;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.sql.repo.AbstractDhRepo;
import com.seibel.distanthorizons.core.sql.repo.FullDataSourceV2Repo;
import com.seibel.distanthorizons.core.util.objects.DataCorruptedException;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles reading/writing {@link FullDataSourceV2} 
 * to and from the database.
 */
public class FullDataSourceProviderV2 implements IDebugRenderable, AutoCloseable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final AbstractDebugWireframeRenderer DEBUG_WIREFRAME_RENDERER = SingletonInjector.INSTANCE.get(AbstractDebugWireframeRenderer.class);
	
	private static final Set<String> CORRUPT_DATA_ERRORS_LOGGED = Collections.newSetFromMap(new ConcurrentHashMap<>());
	
	/**
	 * The highest numerical detail level possible. 
	 * Used when determining which positions to update. 
	 *
	 * @see FullDataSourceProviderV2#LEAF_SECTION_DETAIL_LEVEL
	 */
	public static final byte ROOT_SECTION_DETAIL_LEVEL
		= DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL
		// We might be able to get away with 9,
		// but James would like to keep a little safe buffer, just in case.
		// At 10+ the LOD application process breaks down and
		// we start to get weird anomolies that look bad.
		+ 8;
	
	/**
	 * The lowest numerical detail level possible. 
	 *
	 * @see FullDataSourceProviderV2#ROOT_SECTION_DETAIL_LEVEL
	 */
	public static final byte LEAF_SECTION_DETAIL_LEVEL = DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
	
	
	public final FullDataSourceV2Repo repo;
	
	
	protected final AtomicBoolean isShutdownRef = new AtomicBoolean(false);
	protected final File saveDir;
	protected final IDhLevel level;
	protected final ILevelWrapper levelWrapper;
	protected final String levelId;
	
	
	protected final FullDataUpdaterV2 dataUpdater;
	protected final FullDataUpdatePropagatorV2 updatePropagator;
	protected final DataMigratorV1 dataMigratorV1;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public FullDataSourceProviderV2(IDhLevel level, ISaveStructure saveStructure) throws SQLException, IOException { this(level, saveStructure, null); }
	public FullDataSourceProviderV2(IDhLevel level, ISaveStructure saveStructure, @Nullable File saveDirOverride) throws SQLException, IOException
	{
		this.saveDir = (saveDirOverride == null) ? saveStructure.getSaveFolder(level.getLevelWrapper()) : saveDirOverride;
		this.repo = new FullDataSourceV2Repo(AbstractDhRepo.DEFAULT_DATABASE_TYPE, new File(this.saveDir.getPath() + File.separator + ISaveStructure.DATABASE_NAME));
		this.level = level;
		this.levelWrapper = level.getLevelWrapper();
		
		this.levelId = this.levelWrapper.getDhIdentifier();
		
		this.dataUpdater = new FullDataUpdaterV2(this, this.levelId);
		this.updatePropagator = new FullDataUpdatePropagatorV2(this, this.dataUpdater, this.level);
		this.dataMigratorV1 = new DataMigratorV1(this.dataUpdater, this.level, this.levelId, this.saveDir);
		
		DEBUG_WIREFRAME_RENDERER.register(this, Config.Client.Advanced.Debugging.DebugWireframe.showFullDataUpdateStatus);
		
	}
	
	//endregion
	
	
	
	//=================//
	// event listeners //
	//=================//
	//region
	
	public void addDataSourceUpdateListener(IDataSourceUpdateListenerFunc<FullDataSourceV2> listener)
	{ this.dataUpdater.addDataSourceUpdateListener(listener); }
	public void removeDataSourceUpdateListener(IDataSourceUpdateListenerFunc<FullDataSourceV2> listener)
	{ this.dataUpdater.removeDataSourceUpdateListener(listener); }
	
	//endregion
	
	
	
	//================//
	// DTO converters //
	//================//
	//region
	
	protected FullDataSourceV2 createDataSourceFromDto(FullDataSourceV2DTO dto) throws InterruptedException, IOException, DataCorruptedException
	{ return dto.createDataSource(this.level.getLevelWrapper(), null); }
	protected FullDataSourceV2 createAdjDataSourceFromDto(FullDataSourceV2DTO dto, EDhDirection direction) throws InterruptedException, IOException, DataCorruptedException
	{ return dto.createDataSource(this.level.getLevelWrapper(), direction); }
	
	//endregion
	
	
	
	//=========================//
	// basic DataSource getter //
	//=========================//
	//region
	
	/**
	 * Returns the {@link FullDataSourceV2} for the given section position. <Br>
	 * The returned data source may be null if repo is in the process of shutting down. <Br> <Br>
	 *
	 * This call is concurrent. I.e. it supports being called by multiple threads at the same time.
	 */
	public CompletableFuture<FullDataSourceV2> getAsync(long pos)
	{
		if (this.isShutdownRef.get())
		{
			return CompletableFuture.completedFuture(null);
		}
		
		AbstractExecutorService executor = ThreadPoolUtil.getFileHandlerExecutor();
		if (executor == null || executor.isTerminated())
		{
			return CompletableFuture.completedFuture(null);
		}
		
		
		try
		{
			return CompletableFuture.supplyAsync(() -> this.get(pos), executor);
		}
		catch (RejectedExecutionException ignore)
		{
			// the thread pool was probably shut down because it's size is being changed, just wait a sec and it should be back
			return CompletableFuture.completedFuture(null);
		}
	}
	/**
	 * Should only be used in internal file handler methods where we are already running on a file handler thread.
	 * Can return null if the repo is in the process of being shut down
	 * @see FullDataSourceProviderV2#getAsync(long)
	 */
	@Nullable
	public FullDataSourceV2 get(long pos)
	{
		if (this.isShutdownRef.get())
		{
			return null;
		}
		
		try(FullDataSourceV2DTO dto = this.repo.getByKey(pos))
		{
			if (dto == null)
			{
				return FullDataSourceV2.createEmpty(pos);
			}
			
			FullDataSourceV2 dataSource = null;
			try
			{
				dataSource = this.createDataSourceFromDto(dto);
				
				// automatically create and save adjacent data if missing
				if (dto.dataFormatVersion == FullDataSourceV2DTO.DATA_FORMAT.V1_NO_ADJACENT_DATA)
				{
					EDhApiDataCompressionMode compressionMode = Config.Common.LodBuilding.dataCompression.get();
					try(FullDataSourceV2DTO updatedDto = FullDataSourceV2DTO.CreateFromDataSource(dataSource, compressionMode))
					{
						this.repo.save(updatedDto);
					}
				}
				
				return dataSource; 
			}
			catch (DataCorruptedException e)
			{
				this.tryLogCorruptedDataError(DhSectionPos.toString(pos), e);
				this.repo.deleteWithKey(pos);
			}
			catch (Exception e)
			{
				if (dataSource != null)
				{
					dataSource.close();
				}
				
				throw e;
			}
		}
		catch (InterruptedException ignore) { }
		catch (IOException e)
		{
			String message = e.getMessage();
			if (CORRUPT_DATA_ERRORS_LOGGED.add(message))
			{
				LOGGER.warn("File read Error for pos [" + DhSectionPos.toString(pos) + "], this error message will only be logged once, error: [" + message + "].", e);
			}
		}
		catch (IllegalStateException e)
		{	
			String message = e.getMessage();
			if (CORRUPT_DATA_ERRORS_LOGGED.add(message))
			{
				LOGGER.warn("Incorrectly formatted data for: [" + DhSectionPos.toString(pos) + "], this error message will only be logged once, error: [" + message + "].", e);
			}
		}
		catch (Exception e)
		{
			String message = e.getMessage();
			if (message == null)
			{
				message = "NULL";
			}
			
			if (CORRUPT_DATA_ERRORS_LOGGED.add(message))
			{
				LOGGER.warn("Unexpected error getting: [" + DhSectionPos.toString(pos) + "], this error message will only be logged once, error: [" + message + "].", e);
			}
		}
		
		// an error occurred
		return null;
	}
	
	protected void tryLogCorruptedDataError(String whereClause, Exception e)
	{
		// there's a rare issue where the exception doesn't
		// have a message, which can cause problems
		String message = (e.getMessage() == null) ? e.getMessage() : "No Error message for exception ["+e.getClass().getSimpleName()+"]";
		
		// Only log each message type once.
		// This is done to prevent logging "No compression mode with the value [2]" 10,000 times 
		// if the user is migrating from a nightly build and used ZStd.
		if (CORRUPT_DATA_ERRORS_LOGGED.add(message))
		{
			LOGGER.warn("Corrupted data found at [" + whereClause + "]. Data at will be deleted so it can be re-generated to prevent issues. Future errors with this same message won't be logged. Error: [" + message + "].", e);
		}
	}
	
	//endregion
	
	
	
	//=================//
	// partial getters //
	//=================//
	//region
	
	/** 
	 * Only returns the data row/column for the given compass-cardinal
	 * direction. <br>
	 * This is generally used for generating LOD render data
	 * where we only need the adjacent data, not the full thing.
	 */
	public FullDataSourceV2 getAdjForDirection(long pos, EDhDirection direction)
	{
		if (this.isShutdownRef.get())
		{
			return null;
		}
		
		try(FullDataSourceV2DTO dto = this.repo.getAdjByPosAndDirection(pos, direction))
		{
			if (dto == null)
			{
				return FullDataSourceV2.createEmpty(pos);
			}
			
			// migrate to the V2 format first if needed
			if (dto.dataFormatVersion == FullDataSourceV2DTO.DATA_FORMAT.V1_NO_ADJACENT_DATA)
			{
				// get automatically converts from V1 to V2
				FullDataSourceV2 migratedDataSource = this.get(pos);
				if (migratedDataSource != null)
				{
					migratedDataSource.clearAllNonAdjData(direction);
				}
				
				return migratedDataSource;
			}
			
			try
			{
				// load from database
				return this.createAdjDataSourceFromDto(dto, direction);
			}
			catch (DataCorruptedException e)
			{
				this.tryLogCorruptedDataError(DhSectionPos.toString(pos), e);
				this.repo.deleteWithKey(pos);
			}
		}
		catch (InterruptedException ignore) { }
		catch (IOException e)
		{
			LOGGER.warn("File read Error for pos ["+DhSectionPos.toString(pos)+"], error: "+e.getMessage(), e);
		}
		
		// an error occurred
		return null;
	}
	
	//endregion
	
	
	
	//=======================//
	// retrieval (world gen) //
	//=======================//
	//region

	/** Returns the generation plan applicable to this provider. */
	public EDhApiGeneratorPlan getGeneratorPlan() { return Config.Common.WorldGenerator.generatorPlan.get(); }
	
	/**
	 * Returns true if this provider can generate or retrieve
	 * {@link FullDataSourceV2}'s that aren't currently in the database.
	 */
	public boolean canRetrieveMissingDataSources() 
	{ 
		// the base handler just handles basic reading/writing
		// to the database and as such can't retrieve anything else.
		return false; 
	}
	
	/**
	 * Returns false if this provider isn't accepting new requests,
	 * this can be due to having a full queue or some other
	 * limiting factor. <br><br>
	 * 
	 * Note: when overriding make sure to add: <br>
	 * <code>
	 * if (!super.canQueueRetrieval()) <br>
	 * { <br>
	 *      return false; <br>
	 * } <br>
	 * </code>
	 * to the beginning of your override.
	 * Otherwise, parent retrieval limits will be ignored.
	 */
	public boolean canQueueRetrievalNow()
	{
		// Retrieval shouldn't happen while an unknown number of
		// legacy data sources are present.
		// If retrieval was allowed we might run into concurrency issues.
		return !this.dataMigratorV1.migrationThreadRunning.get();
	}
	
	/**
	 * @return null if this provider can't generate any positions or
	 * an empty array if all positions were generated 
	 */
	@Nullable
	public LongArrayList getPositionsToRetrieve(long pos, byte generatorDetailLevel) { return null; }	
	
	/** @return null if the position couldn't be queued */
	@Nullable
	public CompletableFuture<DataSourceRetrievalResult> queuePositionForRetrieval(Long genPos) { return null; }
	
	/** does nothing if the given position isn't present in the queue */
	public void removeRetrievalRequestIf(DhSectionPos.IPrimitiveLongConsumer removeIf) { }
	
	public void clearRetrievalQueue() { }
	
	/** Can be used to display how many total chunk retrieval requests should be available. */
	public void setEstimatedRemainingRetrievalChunkCount(long newCount) { }
	public void setCanRegenerate(boolean canRegen) {}
	
	//endregion
	
	
	
	//=============//
	// data update //
	//=============//
	//region
	
	public CompletableFuture<Void> updateDataSourceAsync(@NotNull FullDataSourceV2 inputData)
	{ return this.dataUpdater.updateDataSourceAsync(inputData); }
	
	//endregion
	
	
	
	//========================//
	// multiplayer networking //
	//========================//
	//region
	
	@Nullable
	public Long getTimestampForPos(long pos)
	{
		if (this.isShutdownRef.get())
		{
			return null;
		}
		
		return this.repo.getTimestampForPos(pos); 
	}
	
	//endregion
	
	
	
	//===========//
	// debugging //
	//===========//
	//region
	
	public void addDebugMenuStringsToList(List<String> messageList)
	{
		this.dataMigratorV1.addDebugMenuStringsToList(messageList);
	}
	
	//endregion
	
	
	
	//===========//
	// overrides //
	//===========//
	//region
	
	@Override
	public void debugRender(AbstractDebugWireframeRenderer renderer)
	{
		this.dataUpdater.debugRender(renderer);
		this.updatePropagator.debugRender(renderer);
	}
	
	@Override 
	public void close()
	{
		LOGGER.debug("Closing [" + this.getClass().getSimpleName() + "] for level: [" + this.levelId + "].");
		
		this.isShutdownRef.set(true);
		
		this.dataUpdater.close();
		this.updatePropagator.close();
		this.dataMigratorV1.close();
		
		DEBUG_WIREFRAME_RENDERER.unregister(this, Config.Client.Advanced.Debugging.DebugWireframe.showFullDataUpdateStatus);
		
		this.repo.close();
	}
	
	//endregion
	
	
	
}
