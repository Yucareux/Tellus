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

package com.seibel.distanthorizons.common.wrappers.minecraft;

import java.io.File;

#if MC_VER > MC_1_12_2
import com.mojang.blaze3d.platform.Window;
#endif
import com.seibel.distanthorizons.common.wrappers.gui.NativeDialogUtil;
import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper;
import com.seibel.distanthorizons.common.wrappers.world.ServerLevelWrapper;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.file.structure.ClientOnlySaveStructure;
import com.seibel.distanthorizons.core.render.RenderThreadTaskHandler;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IImmersivePortalsAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftSharedWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.logging.DhLogger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
#if MC_VER <= MC_1_12_2
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.crash.CrashReport;
import net.minecraft.profiler.Profiler;
#if MC_VER <= MC_1_7_10
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;
#else
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.DimensionType;
#endif
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
#else
import net.minecraft.CrashReport;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
#endif

import org.jetbrains.annotations.Nullable;

#if MC_VER < MC_1_19_2 && MC_VER > MC_1_12_2
import net.minecraft.network.chat.TextComponent;
#endif

#if MC_VER < MC_1_21_3
#else
import net.minecraft.util.profiling.Profiler;
#endif

#if MC_VER <= MC_1_21_10 && MC_VER > MC_1_12_2
import net.minecraft.client.GraphicsStatus;
#else
#endif

#if  MC_VER <= MC_1_12_2
import net.minecraft.util.ResourceLocation;
#elif  MC_VER <= MC_1_21_10
#else
import net.minecraft.resources.Identifier;
#endif

#if  MC_VER > MC_1_19_2
import net.minecraft.core.registries.Registries;
#elif MC_VER > MC_1_12_2
#endif


/**
 * A singleton that wraps the Minecraft object.
 *
 * @author James Seibel
 */
public class MinecraftClientWrapper extends AbstractMinecraftSharedWrapper implements IMinecraftClientWrapper
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	#if MC_VER <= MC_1_12_2
	private static final Minecraft MINECRAFT = Minecraft.getMinecraft();
	#else
	private static final Minecraft MINECRAFT = Minecraft.getInstance();
	#endif
	
	public static final MinecraftClientWrapper INSTANCE = new MinecraftClientWrapper();
	
	
	private ProfilerWrapper profilerWrapper;
	
	/** Delayed accessing is necessary since this object will be created before the mod accessors are bound. */
	private static class DelayedAccessors 
	{
		public static final IImmersivePortalsAccessor IMMERSIVE_PORTALS = ModAccessorInjector.INSTANCE.get(IImmersivePortalsAccessor.class);
	}
	
	
	
	//======================//
	// multiplayer handling //
	//======================//
	//region
	
	@Override
	public boolean hasSinglePlayerServer() 
	{
		#if MC_VER <= MC_1_12_2
		return MINECRAFT.isSingleplayer();
		#else
		return MINECRAFT.hasSingleplayerServer();
		#endif 
	}
	@Override
	public boolean clientConnectedToDedicatedServer()
	{
		return this.hasServerConnection()
			&& !this.hasSinglePlayerServer();
	}
	@Override
	public boolean connectedToReplay()
	{
		return !this.hasServerConnection()
			&& !this.hasSinglePlayerServer() ;
	}
	
	private boolean hasServerConnection()
	{
		#if MC_VER <= MC_1_12_2
		return MINECRAFT.getCurrentServerData() != null;
		#else
		return MINECRAFT.getCurrentServer() != null; 
		#endif
	}

	
	@Override
	public String getCurrentServerName() 
	{
		if (this.connectedToReplay())
		{
			return ClientOnlySaveStructure.REPLAY_SERVER_FOLDER_NAME;
		}
		else
		{
			#if MC_VER <= MC_1_7_10
			ServerData server = MINECRAFT.getCurrentServerData();
			return (server != null && server.serverName != null) ? server.serverName : "NULL";
			#elif MC_VER <= MC_1_12_2
			ServerData server = MINECRAFT.getCurrentServerData();
			return (server != null) ? server.serverName : "NULL";
			#else
			ServerData server = MINECRAFT.getCurrentServer();
			return (server != null) ? server.name : "NULL";
			#endif
		}
	}
	@Override
	public String getCurrentServerIp() 
	{
		if (this.connectedToReplay())
		{
			return "";
		}
		else
		{
			ServerData server = getCurrentServerData();
			return getServerIp(server);
		}
	}
	@Override
	public String getCurrentServerVersion()
	{
		ServerData server = getCurrentServerData();
		return getServerVersion(server);
	}
	
	private ServerData getCurrentServerData()
	{
		#if MC_VER <= MC_1_12_2
		return MINECRAFT.getCurrentServerData();
		#else
		return MINECRAFT.getCurrentServer();
		#endif
	}
	private String getServerIp(ServerData server)
	{
		if (server == null) { return "NA"; }
		
		#if MC_VER <= MC_1_12_2
		return server.serverIP;
		#else
		return server.ip;
		#endif		
	}
	private String getServerVersion(ServerData server)
	{
		if (server == null) { return "UNKOWN"; }
		
		#if MC_VER <= MC_1_12_2
		return server.gameVersion;
		#else
		return server.version.getString();
		#endif
	}	
	
	//endregion
	
	
	
	//=================//
	// player handling //
	//=================//
	//region
	
	#if MC_VER <= MC_1_7_10
	@Nullable
	public EntityPlayerSP getPlayer() { return MINECRAFT.thePlayer; }
	#elif MC_VER <= MC_1_12_2
	@Nullable
	public EntityPlayerSP getPlayer() { return MINECRAFT.player; }
	#else
	@Nullable
	public LocalPlayer getPlayer() { return MINECRAFT.player; }
	#endif
	
	@Override
	public boolean playerExists() { return this.getPlayer() != null; }
	
	@Override
	public DhBlockPos getPlayerBlockPos()
	{
		#if MC_VER <= MC_1_12_2
		EntityPlayerSP player = this.getPlayer();
		#else
		LocalPlayer player = this.getPlayer();
		#endif
		if (player == null)
		{
			return new DhBlockPos(0, 0, 0);	
		}
		
		if (DelayedAccessors.IMMERSIVE_PORTALS != null)
		{
			DhBlockPos pos = DelayedAccessors.IMMERSIVE_PORTALS.getActualPlayerBlockPos();
			if (pos != null)
			{
				return pos;
			}
		}
		
		#if MC_VER <= MC_1_7_10
		return new DhBlockPos(MathHelper.floor_double(player.posX), MathHelper.floor_double(player.posY), MathHelper.floor_double(player.posZ));
		#elif MC_VER <= MC_1_12_2
		BlockPos playerPos = player.getPosition();
		return new DhBlockPos(playerPos.getX(), playerPos.getY(), playerPos.getZ());
		#else
		BlockPos playerPos = player.blockPosition();
		return new DhBlockPos(playerPos.getX(), playerPos.getY(), playerPos.getZ());
		#endif
	}
	
	@Override
	public DhChunkPos getPlayerChunkPos()
	{
		#if MC_VER <= MC_1_12_2
		EntityPlayerSP player = this.getPlayer();
		#else
		LocalPlayer player = this.getPlayer();
		#endif
		if (player == null)
		{
			return new DhChunkPos(0, 0);
		}
		
		if (DelayedAccessors.IMMERSIVE_PORTALS != null)
		{
			DhChunkPos pos = DelayedAccessors.IMMERSIVE_PORTALS.getActualPlayerChunkPos();
			if (pos != null)
			{
				return pos;
			}
		}
		
		#if MC_VER <= MC_1_7_10
		return new DhChunkPos(player.chunkCoordX, player.chunkCoordZ);
		#elif MC_VER <= MC_1_12_2
		ChunkPos playerPos = new ChunkPos(player.getPosition());
		return new DhChunkPos(playerPos.x, playerPos.z);
        #elif MC_VER < MC_1_17_1
        ChunkPos playerPos = new ChunkPos(player.blockPosition());
        return new DhChunkPos(playerPos.x, playerPos.z);
        #elif MC_VER <= MC_1_21_11
		ChunkPos playerPos = player.chunkPosition();
		return new DhChunkPos(playerPos.x, playerPos.z);
		#else
		ChunkPos playerPos = player.chunkPosition();
		return new DhChunkPos(playerPos.x(), playerPos.z());
        #endif
	}
	
	//endregion
	
	
	
	//================//
	// level handling //
	//================//
	//region
	
	@Nullable
	@Override
	public IClientLevelWrapper getWrappedClientLevel() { return this.getWrappedClientLevel(false); }
	
	@Override
	@Nullable
	public IClientLevelWrapper getWrappedClientLevel(boolean bypassLevelKeyManager)
	{
		if (!bypassLevelKeyManager 
			&& DelayedAccessors.IMMERSIVE_PORTALS != null)
		{
			IClientLevelWrapper level = DelayedAccessors.IMMERSIVE_PORTALS.getActualClientLevelWrapper();
			if (level != null)
			{
				return level;
			}
		}
		
		#if MC_VER <= MC_1_7_10
		WorldClient level = MINECRAFT.theWorld;
		#elif MC_VER <= MC_1_12_2
		WorldClient level = MINECRAFT.world;
		#else
		ClientLevel level = MINECRAFT.level;
		#endif
		if (level == null)
		{
			return null;
		}
		
		return ClientLevelWrapper.getWrapper(level, bypassLevelKeyManager);
	}
	
	//endregion
	
	
	
	//===========//
	// messaging //
	//===========//
	//region
	
	@Override
	public void sendChatMessage(String string)
	{
		#if MC_VER <= MC_1_12_2
		EntityPlayerSP player = this.getPlayer();
		#else
		LocalPlayer player = this.getPlayer();
		#endif
		if (player == null)
		{
			return;
		}
		
		#if MC_VER <= MC_1_7_10
		String[] lines = string.split("\n");
		for (String line : lines) {
			player.addChatMessage(new ChatComponentText(line));
		}
		#elif MC_VER <= MC_1_12_2
		player.sendMessage(new TextComponentString(string));
        #elif MC_VER < MC_1_19_2
		player.sendMessage(new TextComponent(string), getPlayer().getUUID());
        #elif MC_VER < MC_1_21_9
		player.displayClientMessage(net.minecraft.network.chat.Component.translatable(string), /*isOverlay*/false);
		#else
		
		RenderThreadTaskHandler.INSTANCE.queueRunningOnRenderThread("MinecraftClientWrapper sendChatMessage", () -> 
		{
			#if MC_VER <= MC_1_21_11
			player.displayClientMessage(net.minecraft.network.chat.Component.translatable(string), /*isOverlay*/false);
			#else
			player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(string));
			#endif
		});
        #endif
	}
	
	@Override
	public void sendOverlayMessage(String string)
	{
		#if MC_VER <= MC_1_12_2
		EntityPlayerSP player = this.getPlayer();
		#else
		LocalPlayer player = this.getPlayer();
		#endif
		if (player == null)
		{
			return;
		}
		
		#if MC_VER <= MC_1_7_10
		player.addChatMessage(new ChatComponentText(string));
		#elif MC_VER <= MC_1_12_2
		MINECRAFT.ingameGUI.setOverlayMessage(string, /*animateColor*/false);
        #elif MC_VER < MC_1_19_2
		player.displayClientMessage(new TextComponent(string), /*isOverlay*/true);
		#elif MC_VER <= MC_1_21_11
		player.displayClientMessage(net.minecraft.network.chat.Component.translatable(string), /*isOverlay*/true);
        #else
		player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable(string));
        #endif
	}
	
	//endregion
	
	
	
	//==========================//
	// vanilla option overrides //
	//==========================//
	//region
	
	public void disableVanillaClouds()
	{
		LOGGER.info("Disabling vanilla clouds... This is done to prevent vanilla clouds from rendering on top of Distant Horizons LODs.");
		
		#if MC_VER <= MC_1_7_10
		MINECRAFT.gameSettings.clouds = false;
		#elif MC_VER <= MC_1_12_2
		MINECRAFT.gameSettings.clouds = 0;
		#elif MC_VER <= MC_1_18_2
		MINECRAFT.options.renderClouds = CloudStatus.OFF;
		#else
		MINECRAFT.options.cloudStatus().set(CloudStatus.OFF);
		#endif
	}
	
	public void disableVanillaChunkFadeIn()
	{
		String message = "Disabling vanilla chunk fade in... This is done to prevent vanilla chunks from flashing on the Distant Horizons boarder when moving (which is distracting).";
		
		#if MC_VER <= MC_1_21_10
		// chunk fade in was added MC 1.21.11
		#else
		LOGGER.info(message);
		
		MINECRAFT.options.chunkSectionFadeInTime().set(0.0);
		#endif
	}
	
	public void disableFabulousTransparency()
	{
		String reasoning = "This is done to fix vanilla chunks (specifically water blocks) not fading into Distant Horizons LODs when DH's 'Vanilla Fade' option is enabled.";
		
		#if MC_VER <= MC_1_12_2
		// fabulous graphics was added in MC 1.16
		#elif MC_VER <= MC_1_18_2
		LOGGER.info("Disabling fabulous graphics... "+reasoning);
		
		GraphicsStatus oldGraphicsStatus = MINECRAFT.options.graphicsMode;
		if (oldGraphicsStatus == GraphicsStatus.FABULOUS)
		{
			MINECRAFT.options.graphicsMode = GraphicsStatus.FANCY;
		}
		#elif MC_VER <= MC_1_21_10
		LOGGER.info("Disabling fabulous graphics... "+reasoning);
		
		GraphicsStatus oldGraphicsStatus = MINECRAFT.options.graphicsMode().get();
		if (oldGraphicsStatus == GraphicsStatus.FABULOUS)
		{
			MINECRAFT.options.graphicsMode().set(GraphicsStatus.FANCY);
		}
		#else
		LOGGER.info("Disabling improved transparency... "+reasoning);
			
		MINECRAFT.options.improvedTransparency().set(false);
		#endif
	}
	
	//endregion
	
	
	
	//======//
	// misc //
	//======//
	//region
	
	/** 
	 * no override and not included in {@link IMinecraftClientWrapper}
	 * since this would only be used in common/client, not core.
	 */
	#if MC_VER > MC_1_12_2
	public 
		#if MC_VER < MC_1_21_9 long
		#else Window 
		#endif
		getGlfwWindowId()
	{
		#if MC_VER < MC_1_21_9
		long glfwWindowId = MINECRAFT.getWindow().getWindow();
		return glfwWindowId;
		#else
		return MINECRAFT.getWindow();
		#endif
	}
	#endif
	
	@Override
	public IProfilerWrapper getProfiler()
	{
		#if MC_VER <= MC_1_12_2
		Profiler profiler;
		#else
		ProfilerFiller profiler;
		#endif
		
		#if MC_VER <= MC_1_7_10
		profiler = MINECRAFT.mcProfiler;
		#elif MC_VER <= MC_1_12_2
		profiler = MINECRAFT.profiler;
		#elif MC_VER < MC_1_21_3
		profiler = MINECRAFT.getProfiler();
		#else
		profiler = Profiler.get();
		#endif
		
		if (this.profilerWrapper == null)
		{
			this.profilerWrapper = new ProfilerWrapper(profiler);
		}
		else if (profiler != this.profilerWrapper.profiler)
		{
			this.profilerWrapper.profiler = profiler;
		}
		
		return this.profilerWrapper;
	}
	
	@Override
	public void crashMinecraft(String errorMessage, Throwable exception)
	{
		LOGGER.fatal(ModInfo.READABLE_NAME + " had the following error: [" + errorMessage + "]. Crashing Minecraft...", exception);
		
		// Only crash once the renderer has been set up.
		// If the renderer hasn't been set up yet crashing MC will
		// cause a Blaze3D/UI error instead of the error we're trying to send.
		executeOnRenderThread(() -> 
		{
			#if MC_VER <= MC_1_7_10
			throw new RuntimeException(exception);
			#elif MC_VER <= MC_1_12_2
			CrashReport report = new CrashReport(errorMessage, exception);
			MINECRAFT.crashed(report);
			#elif MC_VER < MC_1_20_4
			CrashReport report = new CrashReport(errorMessage, exception);
			Minecraft.crash(report);
			#else
			CrashReport report = new CrashReport(errorMessage, exception);
			MINECRAFT.delayCrash(report);
			#endif
		});
	}
	
	@Override
	public void executeOnRenderThread(Runnable runnable)
	{
		#if MC_VER <= MC_1_12_2
		MINECRAFT.addScheduledTask(runnable); 
		#else
		MINECRAFT.execute(runnable); 
		#endif
	}
	
	@Override
	public void showDialog(String title, String message, String dialogType, String iconType)
	{ NativeDialogUtil.showDialog(title, message, dialogType, iconType); }
	
	@Override
	public boolean dialogNeedsAwtHeadless() { return NativeDialogUtil.needsAwtHeadless(); }
	
	//endregion
	
	
	
	//=============//
	// mod support //
	//=============//
	//region
	
	@Override
	public Object getOptionsObject()
	{
		#if MC_VER <= MC_1_12_2
		return MINECRAFT.gameSettings;
		#else
		return MINECRAFT.options;
		#endif
	}
	
	//endregion
	
	
	
	//========//
	// shared //
	//========//
	//region
	
	@Override
	public boolean isDedicatedServer() { return false; }
	
	@Override
	public File getInstallationDirectory()
	{
		#if MC_VER <= MC_1_7_10
		return MINECRAFT.mcDataDir;
		#elif MC_VER <= MC_1_12_2
		return MINECRAFT.gameDir;
		#else
		return MINECRAFT.gameDirectory;
		#endif
	}
	
	@Override
	public int getPlayerCount()
	{
		// can be null if the server hasn't finished booting up yet
		#if MC_VER <= MC_1_12_2
		if (MINECRAFT.getIntegratedServer() == null)
		#else
		if (MINECRAFT.getSingleplayerServer() == null)
		#endif
		{
			return 1;
		}
		else
		{
			#if MC_VER <= MC_1_12_2
			return MINECRAFT.getIntegratedServer().getCurrentPlayerCount();
			#else
			return MINECRAFT.getSingleplayerServer().getPlayerCount();
			#endif
		}
	}
	
	@Nullable
	@Override
	public IServerLevelWrapper getLevelWrapper(String dimensionResourceLocation)
	{
		if (!this.hasSinglePlayerServer())
		{
			return null;
		}
		
		
		#if MC_VER <= MC_1_12_2
		WorldServer mcLevel;
		{
			Integer dimensionKey = this.deserializeDimensionResourceKey(dimensionResourceLocation);
			if (dimensionKey == null || MINECRAFT.getIntegratedServer() == null)
			{
				return null;
			}
			
			#if MC_VER <= MC_1_7_10
			mcLevel = DimensionManager.getWorld(dimensionKey);
			#else
			mcLevel = MINECRAFT.getIntegratedServer().getWorld(dimensionKey);
			#endif
		}
		#else
		ResourceKey<Level> dimensionKey = this.deserializeDimensionResourceKey(dimensionResourceLocation);
		ServerLevel mcLevel = MINECRAFT.getSingleplayerServer().getLevel(dimensionKey);
		#endif
		
		return ServerLevelWrapper.getWrapper(mcLevel);
	}
	
	//endregion
	
	
	
}
