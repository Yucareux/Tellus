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

package com.seibel.distanthorizons.core.api.internal;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiMcRenderingFadeMode;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiRenderPass;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.*;
import com.seibel.distanthorizons.core.api.internal.rendering.DhRenderState;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.enums.MinecraftTextFormat;
import com.seibel.distanthorizons.core.file.structure.ClientOnlySaveStructure;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.network.messages.MessageRegistry;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.render.CameraZoom;
import com.seibel.distanthorizons.core.render.DhApiRenderProxy;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.render.RenderThreadTaskHandler;
import com.seibel.distanthorizons.core.render.renderer.*;
import com.seibel.distanthorizons.core.util.KeyCodesUtil;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.util.objects.Pair;
import com.seibel.distanthorizons.core.util.objects.RollingAverage;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.world.IDhClientWorld;
import com.seibel.distanthorizons.core.wrapperInterfaces.IVersionConstants;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IImmersivePortalsAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhMetaRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhVanillaFadeRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhTestTriangleRenderer;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.network.messages.AbstractNetworkMessage;
import com.seibel.distanthorizons.core.network.session.NetworkSession;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiDebugRendering;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiRendererMode;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.world.AbstractDhWorld;
import com.seibel.distanthorizons.core.world.DhClientWorld;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.logging.DhLogger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * This holds the methods that should be called
 * by the host mod loader (Fabric, Forge, etc.).
 * Specifically for the client.
 */
public class ClientApi
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	private static final DhLogger RATE_LIMITED_LOGGER = new DhLoggerBuilder().maxCountPerSecond(1).build();
	
	public static final ClientApi INSTANCE = new ClientApi();
	
	private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	private static final IVersionConstants VERSION_CONSTANTS = SingletonInjector.INSTANCE.get(IVersionConstants.class);
	
	/** Delayed accessing is necessary since this object will be created before the mod accessors are bound. */
	private static class DelayedAccessors 
	{
		public static final IImmersivePortalsAccessor IMMERSIVE_PORTALS = ModAccessorInjector.INSTANCE.get(IImmersivePortalsAccessor.class);
		public static final IIrisAccessor IRIS = ModAccessorInjector.INSTANCE.get(IIrisAccessor.class);
	}
	
	/** this includes the is dev build message and low allocated memory warning */
	private static final int MS_BETWEEN_WARNING_MESSAGES = 3_000;
	
	/** 
	 * This isn't the cleanest way of storing variables before passing them to the LOD renderer, 
	 * but due to how mixins work and the inconsistency between MC versions,
	 * having a static object that stores a single frame's data
	 * is often the easiest solution. <br><br>
	 * 
	 * Only downside is making sure each variable is populated before rendering.
	 */
	public static final DhRenderState RENDER_STATE = new DhRenderState();
	/** 
	 * static variable so we don't have to re-create it each frame,
	 * reducing GC pressure.
	 */
	private static final RenderParams RENDER_PARAMS = new RenderParams();
	
	/**
	 * 50ms = 20 FPS
	 * @link https://fpstoms.com/ 
	 * @see ClientApi#cameraSpeedRollingAverage
	 */
	private static final long MIN_MS_BETWEEN_SPEED_CHECKS = 50;
	
	
	private boolean isDevBuildMessagePrinted = false;
	private boolean lowMemoryWarningPrinted = false;
	private boolean highVanillaRenderDistanceWarningPrinted = false;
	
	private long lastSlowChatMessageSentMsTime = 0L;
	
	private final Queue<String> slowChatMessageQueue = new LinkedBlockingQueue<>();
	private final Queue<String> fastChatMessageQueue = new LinkedBlockingQueue<>();
	private final Queue<String> overlayMessageQueue = new LinkedBlockingQueue<>();
	
	public boolean rendererDisabledBecauseOfExceptions = false;
	
	/** Holds any levels that were loaded before the {@link ClientApi#onClientOnlyConnected} was fired. */
	public final HashSet<IClientLevelWrapper> waitingClientLevels = new HashSet<>();
	/** Holds any chunks that were found before the client levels are loaded. */
	public final Map<Pair<IClientLevelWrapper, DhChunkPos>, IChunkWrapper> waitingChunkByClientLevelAndPos = new ConcurrentHashMap<>();
	
	/** publicly available so {@link F3Screen} can display the error */
	@Nullable
	public String lastRenderParamValidationMessage = null;
	
	
	/** 
	 * measured in blocks/second <br>
	 * 
	 * The number of points tracked here is related
	 * to the rate at which we check for speed.
	 * So if the ms_between is changed the number of points
	 * tracked should also be to keep the ratio roughly the same.
	 * @see ClientApi#MIN_MS_BETWEEN_SPEED_CHECKS
	 */
	private final RollingAverage cameraSpeedRollingAverage = new RollingAverage(40);
	private DhVec3d lastCameraPosForSpeedCheck = new DhVec3d();
	private long msSinceLastSpeedCheck = 0L;
	public double getAvgCameraSpeed() { return cameraSpeedRollingAverage.getAverage(); }
	
	/** 
	 * keeping track of this is necessary to fix
	 * out-of-date LODs from rendering when the shading
	 * is changed by Iris, causing LODs to often
	 * lack the side shading, which looks pretty bad
	 * when shaders are disabled.
	 */
	private boolean irisShadersEnabledLastFrame = false;
	
	
	
	//==============//
	// constructors //
	//==============//
	
	private ClientApi() { }
	
	
	
	//==============//
	// world events //
	//==============//
	//region world events
	
	/**
	 * May be fired slightly before or after the associated
	 * level is loaded
	 * depending on how the host mod loader functions. <br><br>
	 * 
	 * Synchronized shouldn't be necessary, but is present to match {@see onClientOnlyDisconnected} and prevent any unforeseen issues. 
	 */
	public synchronized void onClientOnlyConnected()
	{
		// only continue if the client is connected to a different server
		boolean connectedToServer = MC_CLIENT.clientConnectedToDedicatedServer();
		boolean connectedToReplay = MC_CLIENT.connectedToReplay();
		if (connectedToServer || connectedToReplay)
		{
			if (connectedToServer)
			{
				LOGGER.info("Client on ClientOnly mode connecting.");
			}
			else
			{
				LOGGER.info("Replay on ClientServer mode connecting.");
				
				if (Config.Common.Logging.Warning.showReplayWarningOnStartup.get())
				{
					MC_CLIENT.sendChatMessage(MinecraftTextFormat.ORANGE + "Distant Horizons: Replay detected." + MinecraftTextFormat.CLEAR_FORMATTING);
					MC_CLIENT.sendChatMessage("DH may behave strangely or have missing functionality.");
					MC_CLIENT.sendChatMessage("In order to use pre-generated LODs, put your DH database(s) in:");
					MC_CLIENT.sendChatMessage(MinecraftTextFormat.GRAY +".Minecraft" + File.separator + ClientOnlySaveStructure.SERVER_DATA_FOLDER_NAME + File.separator + ClientOnlySaveStructure.REPLAY_SERVER_FOLDER_NAME + File.separator + "DIMENSION_NAME"+ MinecraftTextFormat.CLEAR_FORMATTING);
					MC_CLIENT.sendChatMessage("This message can be disabled in DH's config under Advanced -> Logging.");
					MC_CLIENT.sendChatMessage("");
				}
			}
			
			
			DhClientWorld world = new DhClientWorld();
			SharedApi.setDhWorld(world);
		}
	}
	
	/** Synchronized to prevent a rare issue where multiple disconnect events are triggered on top of each other. */
	public synchronized void onClientOnlyDisconnected()
	{
		AbstractDhWorld world = SharedApi.getAbstractDhWorld();
		if (world != null)
		{
			LOGGER.info("Client on ClientOnly mode disconnecting.");
			
			world.close();
			SharedApi.setDhWorld(null);
		}
		
		// remove any waiting items
		this.waitingChunkByClientLevelAndPos.clear();
	}
	
	//endregion
	
	
	
	//==============//
	// level events //
	//==============//
	//region level events
	
	public void loadWaitingChunksForLevel(IClientLevelWrapper level)
	{
		HashSet<Pair<IClientLevelWrapper, DhChunkPos>> keysToRemove = new HashSet<>();
		String levelDimensionName = level.getDimensionName();
		for (Pair<IClientLevelWrapper, DhChunkPos> levelChunkPair : this.waitingChunkByClientLevelAndPos.keySet())
		{
			// only load chunks that came from this level
			IClientLevelWrapper levelWrapper = levelChunkPair.first;
			if (levelWrapper.equals(level)
				|| levelWrapper.getDimensionName().equals(levelDimensionName))
			{
				IChunkWrapper chunkWrapper = this.waitingChunkByClientLevelAndPos.get(levelChunkPair);
				SharedApi.INSTANCE.applyChunkUpdate(
					// the level reference is changed since it may not match the level
					// we're attempting to load now
					chunkWrapper.copyWithLevel(level), 
					level, 
					false);
				keysToRemove.add(levelChunkPair);
			}
		}
		LOGGER.info("Loaded [" + keysToRemove.size() + "] waiting chunk wrappers.");
		
		for (Pair<IClientLevelWrapper, DhChunkPos> keyToRemove : keysToRemove)
		{
			this.waitingChunkByClientLevelAndPos.remove(keyToRemove);
		}
	}
	
	//endregion
	
	
	
	//============//
	// networking //
	//============//
	//region networking
	
	/**
	 * Forwards a decoded message into the registered handlers.
	 *
	 * @see MessageRegistry
	 */
	public void pluginMessageReceived(@NotNull AbstractNetworkMessage message)
	{
		@Nullable ThreadPoolExecutor executor = ThreadPoolUtil.networkClientHandlerExecutor();
		if (executor == null)
		{
			LOGGER.warn("warn");
			return;
		}
		
		try
		{
			executor.execute(() ->
			{
				try
				{
					IDhClientWorld clientWorld = SharedApi.tryGetDhClientWorld();
					if (!(clientWorld instanceof DhClientWorld))
					{
						return;
					}
					
					DhClientWorld world = (DhClientWorld) clientWorld;
					NetworkSession networkSession = world.pluginChannelApi.networkSession;
					if (networkSession != null)
					{
						networkSession.tryHandleMessage(message);
					}
				}
				catch (Exception e)
				{
					LOGGER.warn("pluginMessageReceived unexpected error: ["+e.getMessage()+"]", e);
				}
			});
		}
		catch (RejectedExecutionException e)
		{
			LOGGER.warn("Plugin message executor rejected");
		}
	}
	
	//endregion
	
	
	
	//===============//
	// LOD rendering //
	//===============//
	//region lod rendering
	
	/** Should be called before {@link ClientApi#renderDeferredLodsForShaders} */
	public void renderLods() { this.renderLodLayer(false); }
	
	/** 
	 * Only necessary when Shaders are in use.
	 * Should be called after {@link ClientApi#renderLods} 
	 */
	public void renderDeferredLodsForShaders() { this.renderLodLayer(true); }
	
	private void renderLodLayer(boolean renderingDeferredLayer)
	{
		IProfilerWrapper profiler = MC_CLIENT.getProfiler();
		try (IProfilerWrapper.IProfileBlock dhRender_profile = profiler.push("DH-RenderLevel"))
		{
			
			
			
			//===========//
			// debugging //
			//===========//
			//region
			
			// only run these tasks once per frame
			if (!renderingDeferredLayer)
			{
				//DhApiTerrainDataRepo.asyncDebugMethod(
				//	RENDER_STATE.clientLevelWrapper,
				//	MC_CLIENT.getPlayerBlockPos().getX(),
				//	MC_CLIENT.getPlayerBlockPos().getY(),
				//	MC_CLIENT.getPlayerBlockPos().getZ()
				//);
			}
			
			//endregion
			
			
			
			//=====================//
			// render thread tasks //
			//=====================//
			//region
			
			// only run these tasks once per frame
			if (!renderingDeferredLayer)
			{
				try (IProfilerWrapper.IProfileBlock renderTask_profile = profiler.push("DH render thread tasks"))
				{
					//===============//
					// chat messages //
					//===============//
					
					this.sendQueuedChatMessages();
					
					
					
					//====================//
					// Render Thread jobs //
					//====================//
					//region

					try
					{
						// these tasks always need to be called, regardless of whether the renderer is enabled or not to prevent memory leaks
						RenderThreadTaskHandler.INSTANCE.runRenderThreadTasks();
					}
					catch (Exception e)
					{
						LOGGER.error("Unexpected issue running render thread tasks, error: [" + e.getMessage() + "].", e);
					}

					//endregion
					
					
					
					//==============//
					// camera speed //
					//==============//
					//region
					
					long nowMs = System.currentTimeMillis();
					if (this.msSinceLastSpeedCheck + MIN_MS_BETWEEN_SPEED_CHECKS < nowMs 
						// don't track camera speed for dimensions the player isn't in
						&& (DelayedAccessors.IMMERSIVE_PORTALS == null 
							|| !DelayedAccessors.IMMERSIVE_PORTALS.isRenderingPortal()))
					{
						// calc time since last check
						double secSinceLastCheck = (nowMs - this.msSinceLastSpeedCheck) / 1_000.0;
						this.msSinceLastSpeedCheck = nowMs;
						
						// get the distance traveled since last frame
						DhVec3d camPos = MC_RENDER.getCameraExactPosition();
						double distanceInBlocks = camPos.getDistance(this.lastCameraPosForSpeedCheck);
						double speed = distanceInBlocks / secSinceLastCheck;
						
						// record new values for next check
						this.cameraSpeedRollingAverage.add(speed);
						this.lastCameraPosForSpeedCheck = camPos;
					}
					
					//endregion
					
					
					
					//================================//
					// Iris LOD data re-build trigger //
					//================================//
					//region
					
					if (DelayedAccessors.IRIS != null)
					{
						boolean shadersActive = DelayedAccessors.IRIS.isShaderPackInUse();
						if (this.irisShadersEnabledLastFrame != shadersActive)
						{
							this.irisShadersEnabledLastFrame = shadersActive;
							DhApi.Delayed.renderProxy.clearRenderDataCache();
						}
					}
					
					//endregion
					
					
					
					//=============//
					// Camera Zoom //
					//=============//
					//region
					
					boolean updateCameraZoom = true;
					if (DelayedAccessors.IRIS != null
						&& DelayedAccessors.IRIS.isRenderingShadowPass())
					{
						// only update the zoom if we're rendering the player's camera
						// if we update the zoom during the shadow pass it will cause flickering
						// due to the conflicting zoom information.
						updateCameraZoom = false;
					}
					
					if (updateCameraZoom)
					{
						CameraZoom.INSTANCE.update(RENDER_STATE);
					}
					
					//endregion
					
					
					
					//===============================//
					// Immersive Portals Mixin Check //
					//===============================//
					//region
					
					if (DelayedAccessors.IMMERSIVE_PORTALS != null)
					{
						DelayedAccessors.IMMERSIVE_PORTALS.logWarningIfMixinNotRunRecently();
					}
					
					//endregion
					
				}
			}
			
			//endregion
			
			
			
			
			//=================//
			// parameter setup //
			//=================//
			//region
			
			EDhApiRenderPass renderPass;
			if (DhApiRenderProxy.INSTANCE.getDeferTransparentRendering())
			{
				if (renderingDeferredLayer)
				{
					renderPass = EDhApiRenderPass.TRANSPARENT;
				}
				else
				{
					renderPass = EDhApiRenderPass.OPAQUE;
				}
			}
			else
			{
				renderPass = EDhApiRenderPass.OPAQUE_AND_TRANSPARENT;
			}
			
			// A global render state variable is used since MC has split up their
			// render prep and actual rendering into different threads/methods
			// this is annoying since it's possible to start a render with only
			// partially complete info, but there isn't a better option at the moment
			RENDER_PARAMS.update(renderPass, RENDER_STATE);
			
			//endregion
			
			
			
			//============//
			// validation //
			//============//
			//region
			
			String validationMessage = RENDER_PARAMS.getValidationErrorMessage();
			if (validationMessage != null)
			{
				// store the error message so it can be seen on the F3 screen
				this.lastRenderParamValidationMessage = validationMessage;
				
				// Allow the debug triangle to render regardless of validation
				// since the triangle just renders directly to the screen
				// and doesn't care about any params
				if (Config.Client.Advanced.Debugging.rendererMode.get() != EDhApiRendererMode.DEBUG_TRIANGLE)
				{
					return;
				}
			}
			else
			{
				this.lastRenderParamValidationMessage = null;
			}
			
			if (this.rendererDisabledBecauseOfExceptions)
			{
				// re-enable rendering if the user toggles DH rendering
				if (!Config.Client.quickEnableRendering.get())
				{
					LOGGER.info("DH Renderer re-enabled after exception. Some rendering issues may occur. Please reboot Minecraft if you see any rendering issues.");
					this.rendererDisabledBecauseOfExceptions = false;
					Config.Client.quickEnableRendering.set(true);
				}
				
				return;
			}
			
			if (Config.Client.Advanced.Debugging.rendererMode.get() == EDhApiRendererMode.DISABLED)
			{
				return;
			}
			
			//endregion
			
			
			
			//===========//
			// rendering //
			//===========//
			//region
			
			try
			{
				// render pass //
				if (Config.Client.Advanced.Debugging.rendererMode.get() == EDhApiRendererMode.DEFAULT)
				{
					if (!renderingDeferredLayer)
					{
						// normal/opaque
						
						boolean renderingCancelled = ApiEventInjector.INSTANCE.fireAllEvents(DhApiBeforeRenderEvent.class, RENDER_PARAMS);
						if (!renderingCancelled)
						{
							LodRenderer.INSTANCE.render(RENDER_PARAMS, profiler);
						}
						
						if (!DhApi.Delayed.renderProxy.getDeferTransparentRendering())
						{
							ApiEventInjector.INSTANCE.fireAllEvents(DhApiAfterRenderEvent.class, null);
						}
					}
					else
					{
						// deferred
						
						boolean renderingCancelled = ApiEventInjector.INSTANCE.fireAllEvents(DhApiBeforeDeferredRenderEvent.class, RENDER_PARAMS);
						if (!renderingCancelled)
						{
							LodRenderer.INSTANCE.renderDeferred(RENDER_PARAMS, profiler);
						}
						
						
						if (DhApi.Delayed.renderProxy.getDeferTransparentRendering())
						{
							ApiEventInjector.INSTANCE.fireAllEvents(DhApiAfterRenderEvent.class, null);
						}
					}
				}
				else
				{
					if (!renderingDeferredLayer)
					{
						IDhMetaRenderer metaRenderer = SingletonInjector.INSTANCE.get(IDhMetaRenderer.class);
						IDhTestTriangleRenderer testRenderer = SingletonInjector.INSTANCE.get(IDhTestTriangleRenderer.class);
						if (testRenderer != null
							&& metaRenderer != null)
						{
							// meta renderer needed for render state/texture
							// for setup on some APIs (IE openGL)
							metaRenderer.runRenderPassSetup(RENDER_PARAMS);
							
							testRenderer.render(RENDER_PARAMS);
							
							metaRenderer.runRenderPassCleanup(RENDER_PARAMS);
						}
						else
						{
							RATE_LIMITED_LOGGER.warn("Unable to find singleton [" + IDhTestTriangleRenderer.class.getSimpleName() + "]");
						}
					}
				}
			}
			catch (Exception e)
			{
				this.rendererDisabledBecauseOfExceptions = true;
				LOGGER.error("Unexpected Renderer error in render pass [" + renderPass + "]. Error: " + e.getMessage(), e);
				
				MC_CLIENT.sendChatMessage(MinecraftTextFormat.DARK_RED + "" + MinecraftTextFormat.BOLD + "ERROR: Distant Horizons renderer has encountered an exception!" + MinecraftTextFormat.CLEAR_FORMATTING);
				MC_CLIENT.sendChatMessage(MinecraftTextFormat.DARK_RED + "Renderer disabled to try preventing GL state corruption." + MinecraftTextFormat.CLEAR_FORMATTING);
				MC_CLIENT.sendChatMessage(MinecraftTextFormat.DARK_RED + "Toggle DH rendering via the config UI to re-activate DH rendering." + MinecraftTextFormat.CLEAR_FORMATTING);
				MC_CLIENT.sendChatMessage(MinecraftTextFormat.DARK_RED + "Error: " + MinecraftTextFormat.CLEAR_FORMATTING + e);
			}
			
			//endregion
		}
	}
	
	//endregion
	
	
	
	//================//
	// fade rendering //
	//================//
	//region fade rendering
	
	/** 
	 * The first fade pass.
	 * Called after MC finishes rendering the opaque passes. 
	 */
	public void renderFadeOpaque()
	{
		IDhVanillaFadeRenderer fadeRenderer = SingletonInjector.INSTANCE.get(IDhVanillaFadeRenderer.class);
		if (fadeRenderer == null)
		{
			return;
		}
		
		// only fade when DH is rendering
		if (Config.Client.Advanced.Debugging.rendererMode.get() == EDhApiRendererMode.DEFAULT
			&&
			(
				// only fade when requested
				Config.Client.Advanced.Graphics.Quality.vanillaFadeMode.get() == EDhApiMcRenderingFadeMode.DOUBLE_PASS
				// or if LOD-only mode is enabled (fading is used to remove the MC render pass)
				|| Config.Client.Advanced.Debugging.lodOnlyMode.get()
			)
			&& shouldRenderFade())
		{
			RENDER_PARAMS.update(EDhApiRenderPass.OPAQUE, RENDER_STATE);
			fadeRenderer.render(RENDER_PARAMS);
		}
	}
	/** 
	 * The second fade pass.
	 * Called after MC finishes rendering both opaque
	 * and transparent passes. 
	 */
	public void renderFadeTransparent()
	{
		IDhVanillaFadeRenderer fadeRenderer = SingletonInjector.INSTANCE.get(IDhVanillaFadeRenderer.class);
		if (fadeRenderer == null)
		{
			return;
		}
		
		// only fade when DH is rendering
		if (Config.Client.Advanced.Debugging.rendererMode.get() == EDhApiRendererMode.DEFAULT)
		{
			boolean renderFade =
				(
					// only fade when requested
					Config.Client.Advanced.Graphics.Quality.vanillaFadeMode.get() != EDhApiMcRenderingFadeMode.NONE
					// or if LOD-only mode is enabled (fading is used to remove the MC render pass)
					|| Config.Client.Advanced.Debugging.lodOnlyMode.get()
				)
				&& shouldRenderFade();
			if (renderFade)
			{
				RENDER_PARAMS.update(EDhApiRenderPass.TRANSPARENT, RENDER_STATE);
				fadeRenderer.render(RENDER_PARAMS);
			}
		}
	}
	
	private static boolean shouldRenderFade()
	{
		// don't fade when Iris shaders are active, otherwise the rendering can get weird
		if (DelayedAccessors.IRIS != null
			&& DelayedAccessors.IRIS.isShaderPackInUse())
		{
			return false;
		}
		
		// Don't render fade through immersive portals, this causes the fade to apply incorrectly
		if (DelayedAccessors.IMMERSIVE_PORTALS != null 
			&& DelayedAccessors.IMMERSIVE_PORTALS.isRenderingPortal())
		{
			return false;
		}
		
		return true;
	}
	
	//endregion
	
	
	
	//==========//
	// keyboard //
	//==========//
	//region keyboard
	
	/** Trigger once on key press, with CLIENT PLAYER. */
	public void keyPressedEvent(int glfwKey)
	{
		if (!Config.Client.Advanced.Debugging.enableDebugKeybindings.get())
		{
			// keybindings are disabled
			return;
		}
		
		
		if (glfwKey == KeyCodesUtil.F6)
		{
			Config.Client.Advanced.Debugging.rendererMode.set(EDhApiRendererMode.next(Config.Client.Advanced.Debugging.rendererMode.get()));
			MC_CLIENT.sendChatMessage("F6: Set rendering to " + Config.Client.Advanced.Debugging.rendererMode.get());
		}
		else if (glfwKey == KeyCodesUtil.F7)
		{
			Config.Client.Advanced.Debugging.lodOnlyMode.set(!Config.Client.Advanced.Debugging.lodOnlyMode.get());
			MC_CLIENT.sendChatMessage("F7: Set LOD only mode to " + Config.Client.Advanced.Debugging.lodOnlyMode.get());
		}
		else if (glfwKey == KeyCodesUtil.F8)
		{
			Config.Client.Advanced.Debugging.debugRenderingColors.set(EDhApiDebugRendering.next(Config.Client.Advanced.Debugging.debugRenderingColors.get()));
			MC_CLIENT.sendChatMessage("F8: Set debug mode to " + Config.Client.Advanced.Debugging.debugRenderingColors.get());
		}
	}
	
	//endregion
	
	
	
	//======//
	// chat //
	//======//
	//region chat
	
	private void sendQueuedChatMessages()
	{
		// this includes if the current build is a dev build
		// and configuration warnings (IE Java memory amount and MC settings)
		this.detectAndSendBootTimeWarnings();
		
		
		// slow chat messages
		{
			// if for some reason we end up with a lot of messages in the queue (world gen)
			// show everything to prevent the queue from growing infinitely
			boolean chatQueueBackedUp = this.slowChatMessageQueue.size() > 25;
			
			// chat messages
			while (!this.slowChatMessageQueue.isEmpty())
			{
				// limit chat message rate so each one can be seen
				// before being pushed off-screen
				if (this.chatMessageSentRecently()
					// unless the queue is backed up
					&& !chatQueueBackedUp)
				{
					break;
				}
				
				// last chat time is only tracked for slow messages
				// to prevent ever sending any if a lot of fast messages are being sent
				this.lastSlowChatMessageSentMsTime = System.currentTimeMillis();
				
				String message = this.slowChatMessageQueue.poll();
				if (message == null)
				{
					// done to prevent potential null pointers
					message = "";
				}
				MC_CLIENT.sendChatMessage(message);
			}
		}
		
		// fast chat messages
		{
			// chat messages
			while (!this.fastChatMessageQueue.isEmpty())
			{
				String message = this.fastChatMessageQueue.poll();
				if (message == null)
				{
					// done to prevent potential null pointers
					message = "";
				}
				MC_CLIENT.sendChatMessage(message);
			}
		}
		
		// overlay messages
		while (!this.overlayMessageQueue.isEmpty())
		{
			String message = this.overlayMessageQueue.poll();
			if (message == null)
			{
				// done to prevent potential null pointers
				message = "";
			}
			MC_CLIENT.sendOverlayMessage(message);
		}
	}
	// TODO merge with AbstractModInitializer.logIncompatibilityWarnings
	//  probably put in a separate class
	private void detectAndSendBootTimeWarnings()
	{
		// dev build
		if (ModInfo.IS_DEV_BUILD 
			&& !this.isDevBuildMessagePrinted 
			&& MC_CLIENT.playerExists())
		{
			this.isDevBuildMessagePrinted = true;
			this.lastSlowChatMessageSentMsTime = System.currentTimeMillis();
			
			// remind the user that this is a development build
			String message =
					MinecraftTextFormat.DARK_GREEN + "Distant Horizons: nightly/unstable build, version: [" + ModInfo.VERSION+"]." + MinecraftTextFormat.CLEAR_FORMATTING + "\n" +
							"Issues may occur with this version.\n" +
							"Here be dragons!\n";
			MC_CLIENT.sendChatMessage(message);
		}
		
		
		// memory
		if (this.chatMessageSentRecently()) return;
		if (!this.lowMemoryWarningPrinted 
			&& Config.Common.Logging.Warning.showLowMemoryWarningOnStartup.get())
		{
			this.lowMemoryWarningPrinted = true;
			this.lastSlowChatMessageSentMsTime = System.currentTimeMillis();
			
			// 4 GB
			long minimumRecommendedMemoryInBytes = 4L * 1_000_000_000L;
			
			// Java returned 17,171,480,576 for 16 GB so it might be slightly off what you'd expect
			long maxMemoryInBytes = Runtime.getRuntime().maxMemory();
			if (maxMemoryInBytes < minimumRecommendedMemoryInBytes)
			{
				String message =
						// orange text		
						MinecraftTextFormat.ORANGE + "Distant Horizons: Low memory detected." + MinecraftTextFormat.CLEAR_FORMATTING + "\n" +
						"Stuttering or low FPS may occur. \n" +
						"Please increase Minecraft's available memory to 4 GB or more. \n" +
						"This warning can be disabled in DH's config under Advanced -> Logging. \n";
				MC_CLIENT.sendChatMessage(message);
			}
		}
		
		
		// high vanilla render distance
		if (this.chatMessageSentRecently()) return;
		if (!this.highVanillaRenderDistanceWarningPrinted 
			&& Config.Common.Logging.Warning.showHighVanillaRenderDistanceWarning.get())
		{
			this.highVanillaRenderDistanceWarningPrinted = true;
			
			// DH generally doesn't need a vanilla render distance above 12 
			if (MC_RENDER.getRenderDistance() > 12)
			{
				this.lastSlowChatMessageSentMsTime = System.currentTimeMillis();
				
				String message =
					MinecraftTextFormat.YELLOW + "Distant Horizons: High vanilla render distance detected." + MinecraftTextFormat.CLEAR_FORMATTING + "\n" +
					"Using a high vanilla render distance uses a lot of CPU power \n" +
					"and doesn't improve graphics much after about 12.\n" +
					"Lowering your vanilla render distance will give you better FPS\n" +
					"and reduce stuttering at a similar visual quality.\n" +
					MinecraftTextFormat.GRAY + "A vanilla render distance of 8 is recommended." + MinecraftTextFormat.CLEAR_FORMATTING + "\n" +
					"This message can be disabled in DH's config under Advanced -> Logging.\n";
				MC_CLIENT.sendChatMessage(message);
			}
		}
	}
	/** done to prevent sending a bunch of chat messages all at once, causing some to be missed. */
	private boolean chatMessageSentRecently()
	{
		if (this.lastSlowChatMessageSentMsTime == 0)
		{
			// no static message has ever been sent
			return false;
		}
		
		long timeSinceLastMessage = System.currentTimeMillis() - this.lastSlowChatMessageSentMsTime; 
		return timeSinceLastMessage <= MS_BETWEEN_WARNING_MESSAGES;
	}
	
	
	/** 
	 * Queues the given message to appear in chat the next valid frame.
	 * Useful for queueing up messages that may be triggered before the user has loaded into the world. 
	 */
	public void queueSlowChatMessage(String chatMessage) { this.slowChatMessageQueue.add(chatMessage); }
	
	/** 
	 * Similar to {@link ClientApi#queueSlowChatMessage(String)}
	 * however any chat messages queued here will be shown immediately
	 * instead of having a delay between messages. <br><br>
	 * 
	 * This is good for logging or alerts.
	 */
	public void queueFastChatMessage(String chatMessage) { this.fastChatMessageQueue.add(chatMessage); }
	
	/**
	 * Similar to {@link ClientApi#queueSlowChatMessage(String)} but appears above the toolbar.
	 */
	public void queueOverlayMessage(String message) { this.overlayMessageQueue.add(message); }
	
	//endregion
	
	
	
}
