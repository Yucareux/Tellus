package com.seibel.distanthorizons.core.level;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.file.structure.ISaveStructure;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.multiplayer.server.FullDataSourceRequestHandler;
import com.seibel.distanthorizons.core.multiplayer.server.ServerPlayerState;
import com.seibel.distanthorizons.core.multiplayer.server.ServerPlayerStateManager;
import com.seibel.distanthorizons.core.network.exceptions.RequestOutOfRangeException;
import com.seibel.distanthorizons.core.network.exceptions.SectionRequiresSplittingException;
import com.seibel.distanthorizons.core.network.messages.AbstractNetworkMessage;
import com.seibel.distanthorizons.core.network.messages.AbstractTrackableMessage;
import com.seibel.distanthorizons.core.network.messages.ILevelRelatedMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataPartialUpdateMessage;
import com.seibel.distanthorizons.core.multiplayer.fullData.FullDataPayload;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.messages.requests.CancelMessage;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.WorldGenUtil;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import com.seibel.distanthorizons.core.logging.DhLogger;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.*;

public abstract class AbstractDhServerLevel extends AbstractDhLevel implements IDhServerLevel
{
	protected static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	public final ServerLevelModule serverside;
	protected final IServerLevelWrapper serverLevelWrapper;
	
	protected final ServerPlayerStateManager serverPlayerStateManager;
	
	/**
	 * This queue is used for ensuring fair generation speed for each player. <br>
	 * Every tick the first player gets used for centering generation, and then is immediately moved into the back of the queue.
	 */
	protected final ConcurrentLinkedQueue<IServerPlayerWrapper> worldGenPlayerCenteringQueue = new ConcurrentLinkedQueue<>();
	
	private final FullDataSourceRequestHandler requestHandler;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public AbstractDhServerLevel(
		ISaveStructure saveStructure, 
		IServerLevelWrapper serverLevelWrapper, 
		ServerPlayerStateManager serverPlayerStateManager
		) throws SQLException, IOException
	{ this(saveStructure, serverLevelWrapper, serverPlayerStateManager, true); }
	
	public AbstractDhServerLevel(
			ISaveStructure saveStructure,
			IServerLevelWrapper serverLevelWrapper,
			ServerPlayerStateManager serverPlayerStateManager,
			boolean runRepoReliantSetup
		) throws SQLException, IOException
	{
		File saveFolder = saveStructure.getSaveFolder(serverLevelWrapper);
		saveFolder.mkdirs();
		if (!saveFolder.exists())
		{
			throw new IOException("unable to create save folder at ["+saveFolder.getPath()+"]. If you're on Windows you may need to enable long file paths.");
		}
		this.serverLevelWrapper = serverLevelWrapper;
		this.serverside = new ServerLevelModule(this, saveStructure);
		this.createAndSetSupportingRepos(this.serverside.fullDataFileHandler.repo.databaseFile);
		if (runRepoReliantSetup)
		{
			this.runRepoReliantSetup();
		}
		
		LOGGER.info("Started "+this.getClass().getSimpleName()+" for ["+serverLevelWrapper+"] at ["+saveStructure+"].");
		
		this.serverPlayerStateManager = serverPlayerStateManager;
		this.requestHandler = new FullDataSourceRequestHandler(this);
	}
	
	
	
	//=======//
	// ticks //
	//=======//
	
	@Override
	public boolean shouldDoWorldGen()
	{ return Config.Common.WorldGenerator.generatorPlan.get().generationEnabled; }
	
	@Override
	public DhBlockPos2D getTargetPosForGeneration()
	{
		IServerPlayerWrapper firstPlayer = this.worldGenPlayerCenteringQueue.peek();
		if (firstPlayer == null)
		{
			return DhBlockPos2D.ZERO;
		}
		
		// Put first player in back before removing from front, so it can be removed by other thread without blocking
		// - if it gets removed, remove() below will remove the item we just put instead
		this.worldGenPlayerCenteringQueue.add(firstPlayer);
		this.worldGenPlayerCenteringQueue.remove(firstPlayer);
		
		DhVec3d position = firstPlayer.getPosition();
		return new DhBlockPos2D((int) position.x, (int) position.z);
	}
	
	
	
	//==================//
	// network handling //
	//==================//
	
	public void registerNetworkHandlers(ServerPlayerState serverPlayerState)
	{
		serverPlayerState.networkSession.registerHandler(FullDataSourceRequestMessage.class, (message) ->
		{
			if (!this.validatePlayerInCurrentLevel(message))
			{
				return;
			}
			
			DhVec3d playerPosition = serverPlayerState.getServerPlayer().getPosition();
			int distanceFromPlayer = DhSectionPos.getChebyshevSignedBlockDistance(message.sectionPos, new DhBlockPos2D((int) playerPosition.x, (int) playerPosition.z)) / 16;
			
			ServerPlayerState.RateLimiterSet rateLimiterSet = serverPlayerState.getRateLimiterSet(this);
			
			if (message.clientTimestamp == null)
			{
				if (distanceFromPlayer > Config.Server.maxGenerationRequestDistance.get())
				{
					message.sendResponse(new RequestOutOfRangeException("Distance too large: " + distanceFromPlayer + " > " + Config.Server.maxGenerationRequestDistance.get()));
					return;
				}
				
				boolean posInRange = WorldGenUtil.isPosInWorldGenRange(
					message.sectionPos,
					Config.Common.WorldGenerator.generationCenterChunkX.get(), Config.Common.WorldGenerator.generationCenterChunkZ.get(),
					Config.Common.WorldGenerator.generationMaxChunkRadius.get()
				);
				if (!posInRange)
				{
					message.sendResponse(new RequestOutOfRangeException("Section out of allowed bounds"));
					return;
				}

				if (!Config.Common.WorldGenerator.generatorPlan.get().surfaceGenEnabled
					&& DhSectionPos.getDetailLevel(message.sectionPos) > DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL)
				{
					message.sendResponse(new SectionRequiresSplittingException("Only full chunks are supported by the server generator plan"));
					return;
				}
				this.requestHandler.queueWorldGenForRequestMessage(serverPlayerState, message, rateLimiterSet);
			}
			else
			{
				if (distanceFromPlayer > Config.Server.maxSyncOnLoadRequestDistance.get())
				{
					message.sendResponse(new RequestOutOfRangeException("Distance too large: " + distanceFromPlayer + " > " + Config.Server.maxSyncOnLoadRequestDistance.get()));
					return;
				}
				this.requestHandler.queueLodSyncForRequestMessage(serverPlayerState, message, rateLimiterSet);
			}
		});
		
		
		serverPlayerState.networkSession.registerHandler(CancelMessage.class, msg ->
		{
			this.requestHandler.cancelRequest(msg.futureId);
		});
	}
	
	
	/** May send an error message in response if the message is a {@link AbstractTrackableMessage} */
	private <T extends AbstractNetworkMessage> boolean validatePlayerInCurrentLevel(T message)
	{
		if (!(message instanceof ILevelRelatedMessage))
		{
			LodUtil.assertNotReach("Received message ["+message+"] does not implement ["+ILevelRelatedMessage.class.getSimpleName()+"]");
		}
		
		// Only handle requests for this level
		if (!((ILevelRelatedMessage) message).isSameLevelAs(this.getServerLevelWrapper()))
		{
			return false;
		}
		
		LodUtil.assertTrue(message.getSession().serverPlayer != null);
		
		return true;
	}
	
	
	
	//===========//
	// world gen //
	//===========//
	
	@Override
	public void onWorldGenTaskComplete(long pos)
	{
		this.requestHandler.onWorldGenTaskComplete(pos);
	}
	
	
	
	//=================//
	// player handling //
	//=================//
	
	public void addPlayer(IServerPlayerWrapper serverPlayer) { this.worldGenPlayerCenteringQueue.add(serverPlayer); }
	public void removePlayer(IServerPlayerWrapper serverPlayer) { this.worldGenPlayerCenteringQueue.remove(serverPlayer); }
	
	@Override
	public CompletableFuture<Void> updateDataSourcesAsync(FullDataSourceV2 data)
	{
		return this.getFullDataProvider()
			.updateDataSourceAsync(data)
			.thenRun(() -> 
			{
				if (!Config.Server.enableRealTimeUpdates.get())
				{
					return;
				}
				
				LodUtil.assertTrue(this.beaconBeamRepo != null, "beaconBeamRepo should not be null");
				FullDataPayload payload = new FullDataPayload(data, this.beaconBeamRepo.getAllBeamsForPos(data.getPos()));
				for (ServerPlayerState serverPlayerState : this.serverPlayerStateManager.getReadyPlayers())
				{
					if (serverPlayerState.getServerPlayer().getLevel() != this.serverLevelWrapper)
					{
						continue;
					}
					
					if (!serverPlayerState.sessionConfig.isRealTimeUpdatesEnabled())
					{
						continue;
					}
					
					DhVec3d playerPosition = serverPlayerState.getServerPlayer().getPosition();
					int distanceFromPlayer = DhSectionPos.getChebyshevSignedBlockDistance(data.getPos(), new DhBlockPos2D((int) playerPosition.x, (int) playerPosition.z)) / 16;
					if (distanceFromPlayer <= serverPlayerState.sessionConfig.getMaxUpdateDistanceRadius())
					{
						serverPlayerState.fullDataPayloadSender.sendInChunks(payload, () ->
						{
							serverPlayerState.networkSession.sendMessage(new FullDataPartialUpdateMessage(this.serverLevelWrapper, payload));
						});
					}
				}
			});
	}
	
	
	
	//===========//
	// debugging //
	//===========//
	
	@Override
	public void addDebugMenuStringsToList(List<String> messageList)
	{
		this.serverside.fullDataFileHandler.addDebugMenuStringsToList(messageList);
		this.serverside.lodRequestModule.addDebugMenuStringsToList(messageList);
	}
	
	
	
	//=========//
	// getters //
	//=========//
	
	@Override
	public IServerLevelWrapper getServerLevelWrapper() { return this.serverLevelWrapper; }
	
	@Override
	@NotNull
	public ILevelWrapper getLevelWrapper() { return this.getServerLevelWrapper(); }
	
	@Override
	public FullDataSourceProviderV2 getFullDataProvider() { return this.serverside.fullDataFileHandler; }
	
	@Override
	public ISaveStructure getSaveStructure() { return this.serverside.saveStructure; }
	
	
	
	//==========//
	// shutdown //
	//==========//
	
	@Override
	public void close()
	{
		super.close();
		this.serverside.close();
		this.requestHandler.close();
		LOGGER.info("Closed DHLevel for [" + this.getLevelWrapper() + "].");
	}
	
	
	
}
