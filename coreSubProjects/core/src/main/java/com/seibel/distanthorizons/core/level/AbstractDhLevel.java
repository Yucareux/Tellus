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

package com.seibel.distanthorizons.core.level;

import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiChunkModifiedEvent;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.render.renderer.CloudRenderHandler;
import com.seibel.distanthorizons.core.util.delayedSaveCache.DelayedBeaconSaveCache;
import com.seibel.distanthorizons.core.util.delayedSaveCache.DelayedDataSourceSaveCache;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.sql.dto.BeaconBeamDTO;
import com.seibel.distanthorizons.core.sql.dto.ChunkHashDTO;
import com.seibel.distanthorizons.core.sql.repo.AbstractDhRepo;
import com.seibel.distanthorizons.core.sql.repo.BeaconBeamRepo;
import com.seibel.distanthorizons.core.sql.repo.ChunkHashRepo;
import com.seibel.distanthorizons.core.util.KeyedLockContainer;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhGenericRenderer;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public abstract class AbstractDhLevel implements IDhLevel
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	/** if this is null then the other handler is probably null too, but just in case */
	@Nullable
	public ChunkHashRepo chunkHashRepo;
	/** if this is null then the other handler is probably null too, but just in case */
	@Nullable
	public BeaconBeamRepo beaconBeamRepo;
	
	protected final KeyedLockContainer<Long> beaconUpdateLockContainer = new KeyedLockContainer<>();
	protected final DelayedDataSourceSaveCache delayedFullDataSourceSaveCache = new DelayedDataSourceSaveCache(this::onDataSourceSaveAsync, 1_000);
	protected final DelayedBeaconSaveCache delayedBeaconSaveCache = new DelayedBeaconSaveCache(this::updateBeaconBeamsBetweenBlockPos, 1_000);
	/** contains the {@link DhChunkPos} for each {@link DhSectionPos} that are queued to save */
	protected final ConcurrentHashMap<Long, HashSet<DhChunkPos>> updatedChunkPosSetBySectionPos = new ConcurrentHashMap<>();
	protected final ConcurrentHashMap<DhChunkPos, Integer> updatedChunkHashesByChunkPos = new ConcurrentHashMap<>();
	
	/** Will be null if clouds shouldn't be rendered for this level. */
	@Nullable
	protected CloudRenderHandler cloudRenderHandler;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	protected AbstractDhLevel() { }
	
	/** 
	 * Creating the repos requires access to the level file, which isn't
	 * available at constructor time.
	 */
	protected void createAndSetSupportingRepos(File databaseFile)
	{
		// chunk hash
		ChunkHashRepo newChunkHashRepo = null;
		try
		{
			newChunkHashRepo = new ChunkHashRepo(AbstractDhRepo.DEFAULT_DATABASE_TYPE, databaseFile);
		}
		catch (SQLException | IOException e)
		{
			LOGGER.fatal("Unable to create ["+ChunkHashRepo.class.getSimpleName()+"], error: ["+e.getMessage()+"].", e);
		}
		this.chunkHashRepo = newChunkHashRepo;
		
		
		// beacon beam
		BeaconBeamRepo newBeaconBeamRepo = null;
		try
		{
			newBeaconBeamRepo = new BeaconBeamRepo(AbstractDhRepo.DEFAULT_DATABASE_TYPE, databaseFile);
		}
		catch (SQLException | IOException e)
		{
			LOGGER.error("Unable to create ["+BeaconBeamRepo.class.getSimpleName()+"], error: ["+e.getMessage()+"].", e);
		}
		this.beaconBeamRepo = newBeaconBeamRepo;
	}
	
	/** handles any setup that needs the repos to be created */
	protected void runRepoReliantSetup()
	{
		IDhGenericRenderer genericRenderer = this.getGenericRenderer();
		if (genericRenderer != null)
		{
			// only client levels can render clouds
			if (this instanceof IDhClientLevel)
			{
				// only add clouds for certain dimension types
				String enabledCloudDimensions = Config.Client.Advanced.Graphics.GenericRendering.dimensionEnabledCloudRenderingCsv.get();
				String dimName = this.getLevelWrapper().getDimensionType().getName();
				if (enabledCloudDimensions.contains(dimName))
				{
					this.cloudRenderHandler = new CloudRenderHandler((IDhClientLevel)this, genericRenderer);
				}
			}
		}
	}
	
	//endregion
	
	
	
	//=================//
	// default methods //
	//=================//
	//region
	
	@Override
	public void updateChunkAsync(IChunkWrapper chunkWrapper, int chunkHash)
	{
		try (FullDataSourceV2 dataSource = FullDataSourceV2.createFromChunk(this.getLevelWrapper(), chunkWrapper))
		{
			if (dataSource == null)
			{
				// This can happen if, among other reasons, a chunk save is superseded by a later event
				return;
			}
			
			
			this.updatedChunkPosSetBySectionPos.compute(dataSource.getPos(), (dataSourcePos, chunkPosSet) ->
			{
				if (chunkPosSet == null)
				{
					chunkPosSet = new HashSet<>();
				}
				chunkPosSet.add(chunkWrapper.getChunkPos());
				return chunkPosSet;
			});
			this.updatedChunkHashesByChunkPos.put(chunkWrapper.getChunkPos(), chunkHash);
			
			// merging writes together in memory significantly improves throughput, since most
			// chunk modifications will be right next to each other, IE effecting the same LODs
			this.delayedFullDataSourceSaveCache.writeToMemoryAndQueueSave(dataSource);
		}
	}
	
	private CompletableFuture<Void> onDataSourceSaveAsync(FullDataSourceV2 fullDataSource)
	{
		return this.updateDataSourcesAsync(fullDataSource)
			.thenRun(() -> 
			{
				// If we are processing a lot of chunks at once
				// (like with C2ME OpenCL)
				// then we can quickly become bottlenecked by the
				// chunk hash (due to being a lot of small disk operations).
				// Disabling the chunk hash saving can improve performance a moderate amount.
				boolean checkChunkHash = !Config.Common.LodBuilding.disableUnchangedChunkCheck.get();
				if (!checkChunkHash)
				{
					return;
				}
				
				try
				{
					HashSet<DhChunkPos> updatedChunkPosSet = this.updatedChunkPosSetBySectionPos.remove(fullDataSource.getPos());
					if (updatedChunkPosSet != null)
					{
						if (this.chunkHashRepo == null)
						{
							return;
						}
						
						Connection dbConnection = this.chunkHashRepo.getConnection();
						if (dbConnection == null)
						{
							return;
						}
						
						// insert all chunk hashes in a single transaction to speed things up
						boolean previousAutoCommit = dbConnection.getAutoCommit();
						dbConnection.setAutoCommit(false);
						
						try
						{
							for (DhChunkPos chunkPos : updatedChunkPosSet)
							{
								// save after the data source has been updated to prevent saving the hash without the associated datasource
								Integer chunkHash = this.updatedChunkHashesByChunkPos.remove(chunkPos);
								if (this.chunkHashRepo != null
									&& chunkHash != null)
								{
									this.chunkHashRepo.save(new ChunkHashDTO(chunkPos, chunkHash));
								}
								
								ApiEventInjector.INSTANCE.fireAllEvents(
									DhApiChunkModifiedEvent.class,
									new DhApiChunkModifiedEvent.EventParam(this.getLevelWrapper(), chunkPos.getX(), chunkPos.getZ()));
							}
							
							dbConnection.commit();
						}
						finally
						{
							dbConnection.rollback();
							dbConnection.setAutoCommit(previousAutoCommit);
						}
					}
				}
				catch (Exception e)
				{
					LOGGER.error("Unexpected issue after onDataSourceSaveAsync, error: ["+e.getMessage()+"].", e);
				}
			});
	}
	
	//endregion
	
	
	
	//=======//
	// repos //
	//=======//
	//region
	
	@Override
	public int getChunkHash(DhChunkPos pos)
	{
		if (this.chunkHashRepo == null)
		{
			return 0;
		}
		
		ChunkHashDTO dto = this.chunkHashRepo.getByKey(pos);
		return (dto != null) ? dto.chunkHash : 0;
	}
	
	//endregion
	
	
	
	//=================//
	// beacon handling //
	//=================//
	//region
	
	@Override
	public void updateBeaconBeamsForSectionPos(long sectionPos, List<BeaconBeamDTO> activeBeamList)
	{
		int minBlockX = DhSectionPos.getMinCornerBlockX(sectionPos);
		int minBlockZ = DhSectionPos.getMinCornerBlockZ(sectionPos);
		int maxBlockX = minBlockX + DhSectionPos.getBlockWidth(sectionPos);
		int maxBlockZ = minBlockZ + DhSectionPos.getBlockWidth(sectionPos);
		
		// no delayed cache needed since each hit will be at a different position
		// and this is generally called when LODs are received from the
		// server, so repeat hits to the same position are unlikely
		this.updateBeaconBeamsBetweenBlockPos(
				sectionPos,
				minBlockX, maxBlockX,
				minBlockZ, maxBlockZ,
				activeBeamList
		);
	}
	
	@Override
	public void updateBeaconBeamsForChunkPos(DhChunkPos chunkPos, List<BeaconBeamDTO> activeBeamList)
	{ 
		// a delayed cache is used to prevent lock contention
		// when flying through new chunks (or updating modified chunks)
		// that are right next to each other (requiring up to 16 hits to the same LOD position
		this.delayedBeaconSaveCache.queueBeaconBeamUpdatesForChunkPos(chunkPos, activeBeamList); 
	}
	
	private void updateBeaconBeamsBetweenBlockPos(
			long sectionPosForLock,
			int minBlockX, int maxBlockX,
			int minBlockZ, int maxBlockZ,
			List<BeaconBeamDTO> activeBeamList
		)
	{
		if (this.beaconBeamRepo == null)
		{
			return;
		}
		
		
		//LOGGER.info("beacons ["+activeBeamList.size()+"] at x["+minBlockX+"]-["+maxBlockX+"] z["+minBlockZ+"]-["+maxBlockZ+"].");
		
		
		// locked to prevent two threads from updating the same position at the same time
		ReentrantLock lock = this.beaconUpdateLockContainer.getLockForPos(sectionPosForLock);
		try
		{
			lock.lock();
			
			HashSet<DhBlockPos> allPosSet = new HashSet<>();
			
			// sort new beams
			HashMap<DhBlockPos, BeaconBeamDTO> activeBeamByPos = new HashMap<>(activeBeamList.size());
			for (BeaconBeamDTO beam : activeBeamList)
			{
				activeBeamByPos.put(beam.blockPos, beam);
				allPosSet.add(beam.blockPos);
			}
			
			// get existing beams
			List<BeaconBeamDTO> existingBeamList = this.beaconBeamRepo.getAllBeamsInBlockPosRange(
					minBlockX, maxBlockX,
					minBlockZ, maxBlockZ);
			HashMap<DhBlockPos, BeaconBeamDTO> existingBeamByPos = new HashMap<>(existingBeamList.size());
			for (BeaconBeamDTO beam : existingBeamList)
			{
				existingBeamByPos.put(beam.blockPos, beam);
				allPosSet.add(beam.blockPos);
			}
			
			
			
			
			for (DhBlockPos beaconPos : allPosSet)
			{
				if (minBlockX > beaconPos.getX() || beaconPos.getX() > maxBlockX
					|| minBlockZ > beaconPos.getZ() || beaconPos.getZ() > maxBlockZ)
				{
					// don't modify beacons outside the updated range
					continue;
				}
				
				
				BeaconBeamDTO existingBeam = existingBeamByPos.get(beaconPos);
				BeaconBeamDTO activeBeam = activeBeamByPos.get(beaconPos);
				if (activeBeam != null)
				{
					if (existingBeam == null)
					{
						// new beam found, add to DB
						this.beaconBeamRepo.save(activeBeam);
						
						//LOGGER.info("new beacon ["+activeBeam+"].");
					}
					else
					{
						// beam still exists in chunk
						if (!existingBeam.color.equals(activeBeam.color))
						{
							// beam colors were changed
							this.beaconBeamRepo.save(activeBeam);
							
							//LOGGER.info("change beacon ["+existingBeam+"] -> ["+activeBeam+"].");
						}
						else
						{
							//LOGGER.info("existing beacon ["+existingBeam+"].");
						}
					}
				}
				else if (existingBeam != null)
				{
					// beam no longer exists at position, remove from DB
					this.beaconBeamRepo.deleteWithKey(beaconPos);
					
					//LOGGER.info("remove beacon ["+existingBeam+"].");
				}
			}
		}
		finally
		{
			lock.unlock();
		}
	}
	
	@Override
	@Nullable
	public BeaconBeamRepo getBeaconBeamRepo() { return this.beaconBeamRepo; }
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	
	@Override
	public void close() 
	{ 
		if (this.chunkHashRepo != null)
		{
			this.chunkHashRepo.close();
		}
		if (this.beaconBeamRepo != null)
		{
			this.beaconBeamRepo.close();
		}
		
		
		
		this.delayedFullDataSourceSaveCache.close();
	}
	
	
	
}
