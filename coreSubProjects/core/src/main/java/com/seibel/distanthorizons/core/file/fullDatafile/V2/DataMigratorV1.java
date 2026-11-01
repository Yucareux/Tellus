package com.seibel.distanthorizons.core.file.fullDatafile.V2;

import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV1;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.fullDatafile.V1.FullDataSourceProviderV1;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class DataMigratorV1 implements AutoCloseable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	/** how many data sources should be pulled down for migration at once */
	private static final int MIGRATION_BATCH_COUNT = FullDataUpdatePropagatorV2.NUMBER_OF_PARENT_UPDATE_TASKS_PER_THREAD;
	/**
	 * 5 minutes <br>
	 * This should be much longer than any update should take. This is just
	 * to make sure the thread doesn't get stuck.
	 */
	private static final int MIGRATION_MAX_UPDATE_TIMEOUT_IN_MS = 5 * 60 * 1_000;
	
	
	
	private final FullDataUpdaterV2 dataUpdater;
	
	
	private boolean migrationStartMessageQueued = false;
	
	private long legacyDeletionCount = -1;
	private long migrationCount = -1;
	private boolean migrationStoppedWithError = false;
	
	/**
	 * Interrupting the migration thread pool doesn't work well and may corrupt the database
	 * vs gracefully shutting down the thread ourselves. 
	 */
	public final AtomicBoolean migrationThreadRunning = new AtomicBoolean(true);
	private final FullDataSourceProviderV1<IDhLevel> v1DataSourceProvider;
	
	private final String levelId;
	private final File saveDir;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public DataMigratorV1(
			FullDataUpdaterV2 dataUpdater,
			IDhLevel level, String levelId, File saveDir
		) throws SQLException, IOException
	{
		this.dataUpdater = dataUpdater;
		this.saveDir = saveDir;
		this.v1DataSourceProvider = new FullDataSourceProviderV1<>(level, saveDir);
		
		this.levelId = levelId;
		
		
		// start migrating any legacy data sources present in the background
		ThreadPoolExecutor executor = ThreadPoolUtil.getFullDataMigrationExecutor();
		if (executor != null)
		{
			executor.execute(this::convertLegacyDataSources);
		}
		else
		{
			// shouldn't happen, but just in case
			LOGGER.error("Unable to start migration for level: ["+this.levelId+"] due to missing executor.");
		}
		
	}
	
	
	//=======================//
	// data source migration //
	//=======================//
	
	private void convertLegacyDataSources()
	{
		try
		{
			LOGGER.debug("Attempting to migrate data sources for: [" + this.levelId + "]-[" + this.saveDir + "]...");
			this.migrationThreadRunning.set(true);
			
			
			
			//============================//
			// delete unused data sources //
			//============================//
			
			// this could be done all at once via SQL, 
			// but doing it in chunks prevents locking the database for long periods of time 
			long unusedCount = 0;
			long totalDeleteCount = this.v1DataSourceProvider.repo.getUnusedDataSourceCount();
			if (totalDeleteCount != 0)
			{
				// this should only be shown once per session but should be shown during 
				// either when the deletion or migration phases start
				this.showMigrationStartMessage();
				
				
				LOGGER.info("deleting [" + this.levelId + "] - [" + totalDeleteCount + "] unused data sources...");
				this.legacyDeletionCount = totalDeleteCount;
				
				ArrayList<String> unusedDataPosList = this.v1DataSourceProvider.repo.getUnusedDataSourcePositionStringList(50);
				while (unusedDataPosList.size() != 0)
				{
					unusedCount += unusedDataPosList.size();
					this.legacyDeletionCount -= unusedDataPosList.size();
					
					
					long startTime = System.currentTimeMillis();
					
					// delete batch and get next batch 
					this.v1DataSourceProvider.repo.deleteUnusedLegacyData(unusedDataPosList);
					unusedDataPosList = this.v1DataSourceProvider.repo.getUnusedDataSourcePositionStringList(50);
					
					long endStart = System.currentTimeMillis();
					long deleteTime = endStart - startTime;
					LOGGER.info("Deleting [" + this.levelId + "] - [" + unusedCount + "/" + totalDeleteCount + "] in [" + deleteTime + "]ms ...");
					
					
					// a slight delay is added to prevent accidentally locking the database when deleting a lot of rows
					// (that shouldn't be the case since we're using WAL journaling, but just in case)
					try
					{
						// use the delete time so we don't make powerful computers wait super long
						// and weak computers wait no time at all
						Thread.sleep(deleteTime / 2);
					}
					catch (InterruptedException ignore)
					{
					}
				}
				LOGGER.info("Done deleting [" + this.levelId + "] - [" + totalDeleteCount + "] unused data sources.");
				
			}
			
			
			
			//===========//
			// migration //
			//===========//
			
			long totalMigrationCount = this.v1DataSourceProvider.getDataSourceMigrationCount();
			this.migrationCount = totalMigrationCount;
			LOGGER.debug("Found [" + totalMigrationCount + "] data sources that need migration.");
			
			ArrayList<FullDataSourceV1> legacyDataSourceList = this.v1DataSourceProvider.getDataSourcesToMigrate(MIGRATION_BATCH_COUNT);
			if (!legacyDataSourceList.isEmpty())
			{
				this.showMigrationStartMessage();
				
				try
				{
					// keep going until every data source has been migrated
					int progressCount = 0;
					while (!legacyDataSourceList.isEmpty() && this.migrationThreadRunning.get())
					{
						NumberFormat numFormat = F3Screen.NUMBER_FORMAT;
						LOGGER.info("Migrating [" + this.levelId + "] - [" + numFormat.format(progressCount) + "/" + numFormat.format(totalMigrationCount) + "]...");
						
						ArrayList<CompletableFuture<Void>> updateFutureList = new ArrayList<>();
						for (int i = 0; i < legacyDataSourceList.size() && this.migrationThreadRunning.get(); i++)
						{
							FullDataSourceV1 legacyDataSource = legacyDataSourceList.get(i);
							
							try
							{
								// convert the legacy data source to the new format,
								// this is a relatively cheap operation
								FullDataSourceV2 newDataSource = FullDataSourceV2.createFromLegacyDataSourceV1(legacyDataSource);
								newDataSource.applyToParent = true;
								
								// the actual update process can be moderately expensive due to having to update
								// the render data along with the full data, so running it async on the update threads gains us a good bit of speed
								CompletableFuture<Void> future = this.dataUpdater.updateDataSourceAsync(newDataSource);
								updateFutureList.add(future);
								future.thenRun(() ->
								{
									// after the update finishes the legacy data source can be safely deleted
									this.v1DataSourceProvider.repo.deleteWithKey(legacyDataSource.getPos());
									newDataSource.close();
								});
							}
							catch (Exception e)
							{
								long migrationPos = legacyDataSource.getPos();
								LOGGER.warn("Unexpected issue migrating data source at pos [" + DhSectionPos.toString(migrationPos) + "]. Error: " + e.getMessage(), e);
								this.v1DataSourceProvider.markMigrationFailed(migrationPos);
							}
						}
						
						
						try
						{
							// wait for each thread to finish updating
							CompletableFuture<Void> combinedFutures = CompletableFuture.allOf(updateFutureList.toArray(new CompletableFuture[0]));
							combinedFutures.get(MIGRATION_MAX_UPDATE_TIMEOUT_IN_MS, TimeUnit.MILLISECONDS);
						}
						catch (InterruptedException | TimeoutException e)
						{
							LOGGER.warn("Migration update timed out after [" + MIGRATION_MAX_UPDATE_TIMEOUT_IN_MS + "] milliseconds. Migration will re-try the same positions again in a moment.", e);
						}
						catch (ExecutionException e)
						{
							LOGGER.warn("Migration update failed. Migration will re-try the same positions again. Error:" + e.getMessage(), e);
						}
						
						legacyDataSourceList = this.v1DataSourceProvider.getDataSourcesToMigrate(MIGRATION_BATCH_COUNT);
						
						progressCount += legacyDataSourceList.size();
						this.migrationCount -= legacyDataSourceList.size();
					}
				}
				catch (Exception e)
				{
					LOGGER.info("migration stopped due to error for: [" + this.levelId + "]-[" + this.saveDir + "], error: [" + e.getMessage() + "].", e);
					this.showMigrationEndMessage(false);
					this.migrationStoppedWithError = true;
				}
				finally
				{
					if (this.migrationThreadRunning.get())
					{
						LOGGER.info("migration complete for: [" + this.levelId + "]-[" + this.saveDir + "].");
						this.showMigrationEndMessage(true);
						this.migrationCount = 0;
					}
					else
					{
						LOGGER.info("migration stopped for: [" + this.levelId + "]-[" + this.saveDir + "].");
						this.showMigrationEndMessage(false);
						this.migrationStoppedWithError = true;
					}
				}
			}
			else
			{
				LOGGER.info("No migration necessary.");
			}
		}
		finally
		{
			this.migrationThreadRunning.set(false);
		}
	}
	
	
	private void showMigrationStartMessage()
	{
		if (this.migrationStartMessageQueued)
		{
			return;
		}
		this.migrationStartMessageQueued = true;
		
		ClientApi.INSTANCE.queueSlowChatMessage(
				"Old Distant Horizons data is being migrated for ["+this.levelId+"]. \n" +
				"While migrating LODs may load slowly \n" +
				"and DH world gen will be disabled. \n" +
				"You can see migration progress in the F3 menu."
		);
	}
	
	private void showMigrationEndMessage(boolean success)
	{
		if (success)
		{
			ClientApi.INSTANCE.queueSlowChatMessage("Distant Horizons data migration for ["+this.levelId+"] completed.");
		}
		else
		{
			ClientApi.INSTANCE.queueSlowChatMessage(
					"Distant Horizons data migration for ["+this.levelId+"] stopped. \n" +
					"Some data may not have been migrated."
			);
		}
	}
	
	
	
	//===========//
	// debugging //
	//===========//
	
	public void addDebugMenuStringsToList(List<String> messageList)
	{
		// migration
		boolean migrationErrored = this.migrationStoppedWithError;
		if (!migrationErrored)
		{
			long legacyDeletionCount = this.legacyDeletionCount;
			if (legacyDeletionCount > 0)
			{
				messageList.add("  Migrating - Deleting #: " + F3Screen.NUMBER_FORMAT.format(legacyDeletionCount));
			}
			
			long migrationCount = this.migrationCount;
			if (migrationCount > 0)
			{
				messageList.add("  Migrating - Conversion #: " + F3Screen.NUMBER_FORMAT.format(migrationCount));
			}
		}
		else
		{
			messageList.add("  Migration Failed");
		}
	}
	
	
	
	//===========//
	// overrides //
	//===========//
	
	@Override
	public void close()
	{
		//LOGGER.info("Closing [" + this.getClass().getSimpleName() + "] for level: [" + this.levelId + "].");
	}
	
	
	
}
