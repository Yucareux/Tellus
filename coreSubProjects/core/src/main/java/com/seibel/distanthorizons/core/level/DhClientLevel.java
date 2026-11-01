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

import com.google.common.cache.CacheBuilder;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.eventHandlers.IgnoredDimensionCsvHandler;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.enums.MinecraftTextFormat;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.file.fullDatafile.RemoteFullDataSourceProvider;
import com.seibel.distanthorizons.core.file.structure.ISaveStructure;
import com.seibel.distanthorizons.core.generation.queues.RemoteWorldRetrievalQueue;
import com.seibel.distanthorizons.core.generation.queues.AbstractLodRequestState;
import com.seibel.distanthorizons.core.generation.queues.LodRequestModule;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.multiplayer.client.ClientNetworkState;
import com.seibel.distanthorizons.core.multiplayer.client.SyncOnLoadRequestQueue;
import com.seibel.distanthorizons.core.network.event.ScopedNetworkEventSource;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataPartialUpdateMessage;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.render.RenderBufferHandler;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhGenericRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.annotation.CheckForNull;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** The level used when connected to a server */
public class DhClientLevel extends AbstractDhLevel implements IDhClientLevel
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	private static final DhLogger NETWORK_LOGGER = new DhLoggerBuilder()
			.fileLevelConfig(Config.Common.Logging.logNetworkEventToFile)
			.build();
	
	private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	
	public final ClientLevelModule clientside;
	public final IClientLevelWrapper levelWrapper;
	public final ISaveStructure saveStructure;
	public final RemoteFullDataSourceProvider remoteDataSourceProvider;
	
	@CheckForNull
	private final ClientNetworkState networkState;
	@Nullable
	private final ScopedNetworkEventSource networkEventSource;
	@Nullable
	private final Closeable sessionConfigListener;
	
	/** used when connected to a DH supported server so we don't process the same chunks multiple times */
	private final Set<DhChunkPos> loadedOnceChunks = Collections.newSetFromMap(
			CacheBuilder.newBuilder()
					.expireAfterWrite(10, TimeUnit.MINUTES)
					.<DhChunkPos, Boolean>build()
					.asMap()
	);
	
	public final LodRequestModule lodRequestModule;
	
	@Nullable
	private final SyncOnLoadRequestQueue syncOnLoadRequestQueue;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public DhClientLevel(
		ISaveStructure saveStructure, 
		IClientLevelWrapper clientLevelWrapper, 
		@Nullable ClientNetworkState networkState
		) throws SQLException, IOException
	{ this(saveStructure, clientLevelWrapper, null, networkState); }
	public DhClientLevel(
		ISaveStructure saveStructure, 
		IClientLevelWrapper clientLevelWrapper, 
		@Nullable File fullDataSaveDirOverride, 
		@Nullable ClientNetworkState networkState
		) throws SQLException, IOException
	{
		File saveFolder = saveStructure.getSaveFolder(clientLevelWrapper);
		File pre23Folder = saveStructure.getPre23SaveFolder(clientLevelWrapper);
		
		saveFolder.mkdirs();
		
		if (pre23Folder.exists())
		{
			if (!pre23Folder.renameTo(saveFolder))
			{
				throw new RuntimeException("Could not move old save data folder: [" + pre23Folder.getAbsolutePath() + "] to [" + saveFolder.getAbsolutePath() + "].");
			}
		}
		
		if (!saveFolder.exists())
		{
			throw new IOException("unable to create save folder at ["+saveFolder.getPath()+"]. If you're on Windows you may need to enable long file paths.");
		}
		
		this.levelWrapper = clientLevelWrapper;
		this.levelWrapper.setDhLevel(this);
		this.saveStructure = saveStructure;
		
		this.networkState = networkState;
		if (this.networkState != null)
		{
			this.networkEventSource = new ScopedNetworkEventSource(this.networkState.getSession());
			this.syncOnLoadRequestQueue = new SyncOnLoadRequestQueue(this, this.networkState);
			this.registerNetworkHandlers();
		}
		else
		{
			this.networkEventSource = null;
			this.syncOnLoadRequestQueue = null;
		}
		
		this.remoteDataSourceProvider = new RemoteFullDataSourceProvider(this, saveStructure, fullDataSaveDirOverride, this.syncOnLoadRequestQueue, this.networkState);
		this.lodRequestModule = new LodRequestModule(this,this, this.remoteDataSourceProvider, () -> new LodRequestState(this, networkState));
		
		this.clientside = new ClientLevelModule(this);
		this.sessionConfigListener = this.networkState != null
			? this.networkState.addSessionConfigListener(this.clientside::onGeneratorPlanChanged)
			: null;
		
		this.createAndSetSupportingRepos(this.remoteDataSourceProvider.repo.databaseFile);
		this.runRepoReliantSetup();
		
		this.clientside.startRenderer();
		LOGGER.info("Started DHLevel for " + this.levelWrapper + " with saves at " + this.saveStructure);
	}
	private void registerNetworkHandlers()
	{
		assert this.networkEventSource != null;
		assert this.networkState != null;
		
		this.networkEventSource.registerHandler(FullDataPartialUpdateMessage.class, message ->
		{
			if (MC_CLIENT.connectedToReplay())
			{
				return;
			}
			
			
			// Check this before decoding data to prevent errors if multiple client levels 
			// are receiving data at once (Immersive Portals compatibility).
			boolean isSameLevel = message.isSameLevelAs(this.levelWrapper);
			//NETWORK_LOGGER.debug("Buffer ["+message.payload.dtoBufferId+"] isSameLevel: ["+isSameLevel+"]");
			if (!isSameLevel)
			{
				return;
			}
			
			try (FullDataSourceV2DTO dataSourceDto = this.networkState.fullDataPayloadReceiver.decodeDataSource(message.payload))
			{
				
				Executor executor = ThreadPoolUtil.getFileHandlerExecutor();
				if (executor != null)
				{
					executor.execute(() ->
					{
						try
						{
							this.updateBeaconBeamsForSectionPos(dataSourceDto.pos, message.payload.beaconBeams);
						}
						catch (Exception e)
						{
							LOGGER.error("Unexpected erorr while updating full data source, error: ["+e.getMessage()+"].", e);
						}
					});
				}
				
				
				FullDataSourceV2 fullDataSource = dataSourceDto.createDataSource(this.levelWrapper, null);
				this.updateDataSourcesAsync(fullDataSource)
						.whenComplete((result, e) -> fullDataSource.close());
			}
			catch (Exception e)
			{
				LOGGER.error("Error while updating full data source", e);
			}
		});
	}
	
	//endregion
	
	
	
	//==============//
	// tick methods //
	//==============//
	//region
	
	@Override
	public void clientTick()
	{
		try
		{
			// only tick the level the player is currently in
			// (done to prevent ticking LodQuadTree's for levels that aren't rendering)
			IClientLevelWrapper clientLevelWrapper = MC_CLIENT.getWrappedClientLevel();
			if (clientLevelWrapper == null 
				|| clientLevelWrapper.getDhLevel() != this)
			{
				return;
			}
			
			this.clientside.clientTick();
			
			if (this.syncOnLoadRequestQueue != null)
			{
				this.syncOnLoadRequestQueue.tick(new DhBlockPos2D(MC_CLIENT.getPlayerBlockPos()));
			}
		}
		catch (Exception e)
		{
			LOGGER.error("Unexpected clientTick Exception: "+e.getMessage(), e);
		}
	}
	
	@Override
	public boolean shouldDoWorldGen()
	{
		ClientNetworkState networkState = this.networkState;
		
		boolean isClientUsable = false, isAllowedDimension = false;
		if (networkState != null)
		{
			isClientUsable = networkState.isReady();
			isAllowedDimension = MC_CLIENT.getWrappedClientLevel() == this.levelWrapper;
		}
		
		return isClientUsable
				&& networkState.sessionConfig.getGeneratorPlan().generationEnabled
				&& isAllowedDimension
				&& this.clientside.isRendering();
	}
	
	@Override
	public DhBlockPos2D getTargetPosForGeneration() { return new DhBlockPos2D(MC_CLIENT.getPlayerBlockPos()); }
	
	//endregion
	
	
	
	//===========//
	// world gen //
	//===========//
	//region
	
	@Override
	public void onWorldGenTaskComplete(long pos)
	{
		this.clientside.reloadPos(pos);
	}
	
	//endregion
	
	
	
	//=========//
	// getters //
	//=========//
	//region
	
	@Override
	public IClientLevelWrapper getClientLevelWrapper() { return this.levelWrapper; }
	
	@Override
	public void clearRenderCache() { this.clientside.clearRenderCache(); }
	
	@Override
	@NotNull
	public ILevelWrapper getLevelWrapper() { return this.levelWrapper; }
	
	@Override
	public CompletableFuture<Void> updateDataSourcesAsync(FullDataSourceV2 data) { return this.clientside.updateDataSourcesAsync(data); }
	
	@Override
	public FullDataSourceProviderV2 getFullDataProvider() { return this.remoteDataSourceProvider; }
	
	@Override
	public ISaveStructure getSaveStructure() { return this.saveStructure; }
	
	@Override
	public IDhGenericRenderer getGenericRenderer() { return this.clientside.genericRenderer; }
	@Override
	public RenderBufferHandler getRenderBufferHandler()
	{
		ClientLevelModule.ClientRenderState renderState = this.clientside.ClientRenderStateRef.get();
		return (renderState != null) ? renderState.renderBufferHandler : null;
	}
	
	@Override 
	public boolean isRendering() { return this.clientside.isRendering(); }
	
	public boolean shouldProcessChunkUpdate(DhChunkPos chunkPos)
	{
		if (this.networkState == null || !this.networkState.isReady())
		{
			return true;
		}
		
		return !this.networkState.sessionConfig.isRealTimeUpdatesEnabled() || this.loadedOnceChunks.add(chunkPos);
	}
	
	//endregion
	
	
	
	//===========//
	// debugging //
	//===========//
	//region
	
	@Override
	public void addDebugMenuStringsToList(List<String> messageList)
	{
		String o = MinecraftTextFormat.ORANGE;
		String y = MinecraftTextFormat.YELLOW;
		String g = MinecraftTextFormat.GREEN;
		String r = MinecraftTextFormat.RED;
		String cf = MinecraftTextFormat.CLEAR_FORMATTING;
		
		
		String dimName = this.levelWrapper.getDhIdentifier();
		boolean rendering = 
			this.clientside.isRendering() 
			&& !IgnoredDimensionCsvHandler.INSTANCE.dimensionNameShouldBeIgnored(dimName);
		String renderingString = rendering ? (g+"yes"+cf) : (r+"no"+cf);
		messageList.add("["+y+dimName+cf+"] rendering: "+renderingString);
		
		
		this.remoteDataSourceProvider.addDebugMenuStringsToList(messageList);
		
		
		// world gen
		this.lodRequestModule.addDebugMenuStringsToList(messageList);
		if (this.syncOnLoadRequestQueue != null)
		{
			assert this.networkState != null;
			if (this.networkState.sessionConfig.getSynchronizeOnLoad())
			{
				this.syncOnLoadRequestQueue.addDebugMenuStringsToList(messageList);
			}
		}
	}
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	@Override
	public String toString() { return "DhClientLevel{" + this.getClientLevelWrapper().getDhIdentifier() + "}"; }
	
	@Override
	public void close()
	{
		if (this.lodRequestModule != null)
		{
			this.lodRequestModule.close();
		}
		
		if (this.networkEventSource != null)
		{
			this.networkEventSource.close();
		}
		
		if (this.sessionConfigListener != null)
		{
			try
			{
				this.sessionConfigListener.close();
			}
			catch (Exception ignored) { }
		}
		
		this.levelWrapper.setDhLevel(null);
		this.clientside.close();
		super.close();
		this.remoteDataSourceProvider.close();
		LOGGER.info("Closed [" + DhClientLevel.class.getSimpleName() + "] for [" + this.levelWrapper + "]");
	}
	
	//endregion
	
	
	
	//================//
	// helper classes //
	//================//
	//region
	
	private static class LodRequestState extends AbstractLodRequestState
	{
		LodRequestState(DhClientLevel clientLevel, ClientNetworkState networkState)
		{ super(clientLevel, new RemoteWorldRetrievalQueue(networkState, clientLevel)); }
	}
	
	//endregion
	
	
	
}
