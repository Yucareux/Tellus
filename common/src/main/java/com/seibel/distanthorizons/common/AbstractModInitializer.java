package com.seibel.distanthorizons.common;

import com.seibel.distanthorizons.api.enums.config.EDhApiMcRenderingFadeMode;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingEngine;
import com.seibel.distanthorizons.api.enums.config.quickOptions.EDhApiThreadPreset;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiTransparency;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiGeneratorPlan;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterDhInitEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeDhInitEvent;
import com.seibel.distanthorizons.common.commands.CommandInitializer;
import com.seibel.distanthorizons.common.wrappers.DependencySetup;
import com.seibel.distanthorizons.common.wrappers.gui.DhDebugScreenEntry;
import com.seibel.distanthorizons.common.wrappers.minecraft.AbstractMinecraftSharedWrapper;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftClientWrapper;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftServerWrapper;
import com.seibel.distanthorizons.core.Initializer;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.ConfigHandler;
import com.seibel.distanthorizons.core.config.eventHandlers.presets.ThreadPresetConfigEventHandler;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.enums.MinecraftTextFormat;
import com.seibel.distanthorizons.core.jar.ModJarInfo;
import com.seibel.distanthorizons.core.jar.updater.SelfUpdater;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.render.renderer.AbstractDebugWireframeRenderer;
import com.seibel.distanthorizons.core.render.renderer.StubDebugWireframeRenderer;
import com.seibel.distanthorizons.common.wrappers.gui.NativeDialogUtil;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.IVersionConstants;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IC2meAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IModAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IModChecker;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.coreapi.util.MathUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import com.seibel.distanthorizons.core.logging.DhLogger;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

#if MC_VER > MC_1_12_2
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
#else
import net.minecraft.command.ServerCommandManager;
#endif

/**
 * Base for all mod loader initializers 
 * and handles most setup. 
 */
public abstract class AbstractModInitializer
{
	protected static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	#if MC_VER <= MC_1_12_2
	#else
	private CommandInitializer commandInitializer;
	#endif
	
	
	
	//==================//
	// abstract methods //
	//==================//
	//region abstract methods
	
	protected abstract void createInitialSharedBindings();
	protected abstract void createInitialClientBindings();
	protected abstract IEventProxy createClientProxy();
	protected abstract IEventProxy createServerProxy(boolean isDedicated);
	protected abstract void initializeModCompat();
	
	#if MC_VER > MC_1_12_2
	protected abstract void subscribeRegisterCommandsEvent(Consumer<CommandDispatcher<CommandSourceStack>> eventHandler);
	#endif
	
	protected abstract void subscribeClientStartedEvent(Runnable eventHandler);
	protected abstract void subscribeServerStartingEvent(Consumer<MinecraftServer> eventHandler);
	protected abstract void runDelayedSetup();
	
	//endregion
	
	
	
	//===================//
	// initialize events //
	//===================//
	//region initialize events
	
	public void onInitializeClient()
	{
		DependencySetup.createClientBindings();
		this.createInitialClientBindings();
		
		LOGGER.info("Initializing " + ModInfo.READABLE_NAME + " client, firing DhApiBeforeDhInitEvent...");
		ApiEventInjector.INSTANCE.fireAllEvents(DhApiBeforeDhInitEvent.class, null);
		
		this.startup();
		this.logBuildInfo();
		
		this.createClientProxy().registerEvents();
		this.createServerProxy(false).registerEvents();
		
		this.initializeModCompat();
		
		// Client uses config for auto-updater, so it's initialized here instead of post-init stage
		this.initConfig();
		logIncompatibilityWarnings(); // needs to be called after config loading
		setDisabledDhConfigBasedOnMods();
		setUnsupportedConfigsBasedOnMcVersion();
		Initializer.postConfigInit();
		
		LOGGER.info(ModInfo.READABLE_NAME + " client Initialized.");
		
		#if MC_VER < MC_1_21_9
		// debug screen rendering handled via a mixin
		#else
		DhDebugScreenEntry.register();
		#endif
		
		this.subscribeClientStartedEvent(this::postInit);
		this.subscribeClientStartedEvent(this::postClientInit);
	}
	
	public void onInitializeServer()
	{
		DependencySetup.createServerBindings();
		
		LOGGER.info("Initializing " + ModInfo.READABLE_NAME + " server, firing DhApiBeforeDhInitEvent event...");
		ApiEventInjector.INSTANCE.fireAllEvents(DhApiBeforeDhInitEvent.class, null);
		
		this.startup();
		this.logBuildInfo();
		
		// This prevents returning uninitialized Config values,
		// resulting from a circular reference mid-initialization in a static class
		// noinspection ResultOfMethodCallIgnored
		ThreadPresetConfigEventHandler.INSTANCE.toString();
		
		this.createServerProxy(true).registerEvents();
		
		this.initializeModCompat();
		
		LOGGER.info(ModInfo.READABLE_NAME + " server Initialized, adding event subscribers...");
		#if MC_VER <= MC_1_12_2
		#else
		this.commandInitializer = new CommandInitializer();
		this.subscribeRegisterCommandsEvent(dispatcher -> { this.commandInitializer.initCommands(dispatcher); });
		#endif
		
		this.subscribeServerStartingEvent(server -> 
		{
			MinecraftServerWrapper.INSTANCE.dedicatedServer = (DedicatedServer)server;
			
			this.initConfig();
			setUnsupportedConfigsBasedOnMcVersion();
			Initializer.postConfigInit();
			this.postInit();
			this.postServerInit();
			
			#if MC_VER <= MC_1_12_2
			((ServerCommandManager) server.getCommandManager()).registerCommand(CommandInitializer.initCommands());
			#else
			this.commandInitializer.onServerReady();
			#endif
			
			this.checkForUpdates();
			
			String serverFolderPath;
			#if MC_VER <= MC_1_7_10
			serverFolderPath = "."; // equivalent to new MC's "server.getDataDirectory()"
			#elif MC_VER <= MC_1_12_2
			serverFolderPath = server.getDataDirectory() + "";
			#else
			serverFolderPath = server.getServerDirectory() + "";
			#endif
			
			LOGGER.info(ModInfo.READABLE_NAME + " server Initialized at " + serverFolderPath);
		});
	}
	
	//endregion
	
	
	
	//===========================//
	// inner initializer methods //
	//===========================//
	//region inner initializer methods
	
	private void startup()
	{
		DependencySetup.createSharedBindings();
		Initializer.preConfigInit();
		this.createInitialSharedBindings();
	}
	
	private void logBuildInfo()
	{
		LOGGER.info(ModInfo.READABLE_NAME + ", Version: " + ModInfo.VERSION);
		
		// if the build is stable the branch/commit/etc shouldn't be needed
		if (ModInfo.IS_DEV_BUILD)
		{
			LOGGER.info("DH Branch: " + ModJarInfo.Git_Branch);
			LOGGER.info("DH Commit: " + ModJarInfo.Git_Commit);
			LOGGER.info("DH Jar Build Source: " + ModJarInfo.Build_Source);
		}
	}
	
	/** 
	 * Adds the accessor if any of the given IDs are found. <br>
	 * Can be used when a mod has multiple potential IDs. 
	 */
	protected <T extends IModAccessor> void tryCreateModCompatAccessor(String[] modIdArray, Class<? super T> accessorClass, Supplier<T> accessorConstructor)
	{
		IModChecker modChecker = SingletonInjector.INSTANCE.get(IModChecker.class);
		if (modChecker.isModLoaded(modIdArray))
		{
			//noinspection unchecked
			ModAccessorInjector.INSTANCE.bind((Class<? extends IModAccessor>) accessorClass, accessorConstructor.get());
		}
		else
		{
			LOGGER.debug("Skipping mod compatibility accessor for: ["+ String.join(", ", modIdArray)+"]");
		}
	}
	protected <T extends IModAccessor> void tryCreateModCompatAccessor(String modId, Class<? super T> accessorClass, Supplier<T> accessorConstructor)
	{
		IModChecker modChecker = SingletonInjector.INSTANCE.get(IModChecker.class);
		if (modChecker.isModLoaded(modId))
		{
			//noinspection unchecked
			ModAccessorInjector.INSTANCE.bind((Class<? extends IModAccessor>) accessorClass, accessorConstructor.get());
		}
		else
		{
			LOGGER.debug("Skipping mod compatibility accessor for: ["+modId+"]");
		}
	}
	@SuppressWarnings("unchecked")
	protected <T extends IModAccessor> void addModCompatAccessor(Class<? super T> accessorClass, Supplier<T> accessorConstructor)
	{
		// logging is done internally
		ModAccessorInjector.INSTANCE.bind((Class<? extends IModAccessor>) accessorClass, accessorConstructor.get());
	}
	
	private void initConfig()
	{
		ConfigHandler.tryRunFirstTimeSetup();
		Config.completeDelayedSetup();
		DhLogger.runDelayedConfigSetup();
	}
	
	private void checkForUpdates()
	{
		if (Config.Client.Advanced.AutoUpdater.enableAutoUpdater.get())
		{
			if (Config.Client.Advanced.AutoUpdater.enableSilentUpdates.get())
			{
				LOGGER.info("Silent updates are not allowed for dedicated servers; force disabling.");
				Config.Client.Advanced.AutoUpdater.enableSilentUpdates.set(false);
			}
			
			SelfUpdater.onStart();
		}
	}
	
	private void postInit()
	{
		LOGGER.info("Running Delayed setup...");
		this.runDelayedSetup();
		
		if (ConfigHandler.INSTANCE == null)
		{
			throw new IllegalStateException("Config was not initialized. Make sure to call LodCommonMain.initConfig() before calling this method.");
		}
		
		LOGGER.info("Delayed setup complete, firing DhApiAfterDhInitEvent event...");
		
		// should be fired after all delayed setup so singletons and config can be accessed
		ApiEventInjector.INSTANCE.fireAllEvents(DhApiAfterDhInitEvent.class, null);
	}
	
	private void postClientInit() 
	{
		CompletableFuture<Void> future = new CompletableFuture<>();
		
		// This method may be called from either the render thread,
		// or some other random setup thread depending on the mod loader.
		// In order to avoid confusion/inconsistent problems, we're always going 
		// to run setup on our own thread.
		Thread dhSetupThread = new Thread(() -> 
		{
			try
			{
				DependencySetup.setRenderingApiBindings();
			}
			catch (Exception e)
			{
				NativeDialogUtil.showDialog(ModInfo.READABLE_NAME, e.getMessage(), "ok", "error");
				MinecraftClientWrapper.INSTANCE.crashMinecraft(e.getMessage(), e);
				future.completeExceptionally(e);
			}
			finally
			{
				future.complete(null);
			}
		});
		dhSetupThread.setName(ThreadUtil.THREAD_NAME_PREFIX + "PostClientInit Thread");
		dhSetupThread.start();
		
		future.join();
	}
	private void postServerInit() { SingletonInjector.INSTANCE.bind(AbstractDebugWireframeRenderer.class, new StubDebugWireframeRenderer()); }
	
	//endregion
	
	
	
	//======================//
	// compatibility checks //
	//======================//
	//region compatibility checks
	
	// TODO merge with ClientApi.detectAndSendBootTimeWarnings
	//  probably put in a separate class
	
	/** 
	 * Some mods will work with a few tweaks
	 * or will partially work but have some known issues we can't solve.
	 * This method will log (and display to chat if enabled)
	 * these warnings and potential fixes.
	 */
	private static void logIncompatibilityWarnings()
	{
		boolean showChatWarnings = Config.Common.Logging.Warning.showModCompatibilityWarningsOnStartup.get();
		IModChecker modChecker = SingletonInjector.INSTANCE.get(IModChecker.class);
		IVersionConstants versionConstants = SingletonInjector.INSTANCE.get(IVersionConstants.class);
		
		String startingString = "Partially Incompatible Distant Horizons mod detected: ";
		
		
		
		//==============//
		// Alex's caves //
		//==============//
		//region
		if (modChecker.isModLoaded("alexscaves"))
		{
			// There've been a few reports about this mod breaking DH at a few different points in time
			// the fixes for said breakage changes depending on the version so unfortunately
			// all we can do is log a warning so the user can handle it.
			
			if (showChatWarnings)
			{
				String message =
					MinecraftTextFormat.ORANGE + "Distant Horizons: Alex's Cave detected." + MinecraftTextFormat.CLEAR_FORMATTING +
								"You may have to change Alex's config for DH to render. ";
				ClientApi.INSTANCE.queueSlowChatMessage(message);
			}
			
			LOGGER.warn(startingString + "[Alex's Caves] may require some config changes in order to render Distant Horizons correctly.");
		}
		//endregion
		
		
		
		//======//
		// WWOO //
		//======//
		// William Wythers' Overhauled Overworld (WWOO)
		
		//region
		if (modChecker.isModLoaded("wwoo"))
		{
			// WWOO has a bug with it's world gen that can't be fixed by DH or WWOO
			// (at least that is what James learned after talking with WWOO)
			// WWOO will cause grid lines to appear in the world when DH generates the chunks
			// this might be due to how WWOO uses features for everything when generating
			// and said features don't always get to the edge of said chunks.
			
			String wwooWarning = "LODs generated by DH may have grid lines between sections. Disabling either WWOO or DH's distant generator will fix the problem.";
			
			if (showChatWarnings)
			{
				String message =
					MinecraftTextFormat.ORANGE + "Distant Horizons: WWOO detected." + MinecraftTextFormat.CLEAR_FORMATTING + "\n" +
								wwooWarning;
				ClientApi.INSTANCE.queueSlowChatMessage(message);
			}
			
			LOGGER.warn(startingString + "[WWOO] "+ wwooWarning);
		}
		//endregion
		
		
		
		//========//
		// Chunky //
		//========//
		//region
		
		boolean chunkyPresent = false;
		try
		{
			Class.forName("org.popcraft.chunky.api.ChunkyAPI");
			chunkyPresent = true;
		}
		catch (ClassNotFoundException ignore) { }
		
		if (chunkyPresent)
		{
			// Chunky can generate chunks faster than DH can process them,
			// causing holes in the LODs.
			// Generally it's better and faster to use DH's world generator.
			
			String chunkyWarning = "Chunky can cause DH LODs to have holes " +
					"since Chunky can generate chunks faster than DH can process them. \n" +
					"Using DH's distant generator instead of chunky or increasing DH's CPU thread count can resolve the issue.";
			
			if (showChatWarnings)
			{
				String message =
					MinecraftTextFormat.ORANGE + "Distant Horizons: Chunky detected." + MinecraftTextFormat.CLEAR_FORMATTING + "\n" +
								chunkyWarning;
				ClientApi.INSTANCE.queueSlowChatMessage(message);
			}
			
			LOGGER.warn(startingString + "[Chunky] "+ chunkyWarning);
			
			// don't allow for the possibility of DH and chunky to generate chunks at the same time
			Config.Common.LodBuilding.disableUnchangedChunkCheck.setApiValue(true, ModInfo.READABLE_NAME + " / Chunky");
		}
		
		//endregion
		
		
		
		//======//
		// iris //
		//======//
		//region
		
		IIrisAccessor iris = ModAccessorInjector.INSTANCE.get(IIrisAccessor.class);
		if (iris != null)
		{
			// get the currently selected rendering API
			EDhApiRenderingEngine renderEngine = Config.Client.Advanced.Graphics.Experimental.renderingEngine.get();
			
			// Iris only supports native OpenGL
			if (renderEngine == EDhApiRenderingEngine.BLAZE_3D)
			{
				String irisUnsupportedMessage = "Iris doesn't support DH when using the ["+ EDhApiRenderingEngine.BLAZE_3D+"] rendering engine, this will need to be fixed on Iris end. As a temporary fix please change the rendering engine to ["+ EDhApiRenderingEngine.OPEN_GL+"] or ["+ EDhApiRenderingEngine.AUTO+"] in the DH config file.";
				LOGGER.fatal(irisUnsupportedMessage);
				NativeDialogUtil.showDialog(ModInfo.READABLE_NAME, irisUnsupportedMessage, "ok", "error");
				
				IMinecraftClientWrapper mc = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
				String errorMessage = "loading Distant Horizons. "+irisUnsupportedMessage;
				String exceptionError = "Distant Horizons conditional mod config Exception";
				mc.crashMinecraft(errorMessage, new Exception(exceptionError));
			}
			else if (renderEngine == EDhApiRenderingEngine.AUTO)
			{
				Config.Client.Advanced.Graphics.Experimental.renderingEngine.setApiValue(EDhApiRenderingEngine.OPEN_GL, ModInfo.READABLE_NAME + " / " + IIrisAccessor.READABLE_NAME);
				
				EDhApiRenderingEngine recommendedEngine = versionConstants.getDefaultRenderingEngine();
				if (recommendedEngine != EDhApiRenderingEngine.OPEN_GL)
				{
					LOGGER.warn("Changing Distant Horizons' rendering engine to [" + EDhApiRenderingEngine.OPEN_GL + "] to allow for Iris rendering. This renderer will be unavailable once Minecraft moves to Vulkan and must be fixed on Iris' end.");
				}
			}
		}
		
		//endregion
		
		
		
		//======//
		// C2ME //
		//======//
		//region
		
		IC2meAccessor c2me = ModAccessorInjector.INSTANCE.get(IC2meAccessor.class);
		if (c2me != null)
		{
			// find how many C2ME worker threads are active (they should be created by this point)
			int numberOfC2meThreads = 0;
			Set<Thread> threads = Thread.getAllStackTraces().keySet();
			
			
			// check for the worker threads first 
			// (there may be other threads for things like OpenCL or file IO)
			for (Thread thread : threads)
			{
				if (thread.getName().toLowerCase().contains("c2me-worker"))
				{
					numberOfC2meThreads++;
				}
			}
			
			// if C2ME changes how their threads are named, cast our net a little wider
			if (numberOfC2meThreads == 0)
			{
				for (Thread thread : threads)
				{
					if (thread.getName().toLowerCase().contains("c2me"))
					{
						numberOfC2meThreads++;
					}
				}
			}
			
			
			int cpuThreadCount = Runtime.getRuntime().availableProcessors();
			int expectedC2meThreadCount = Math.max(cpuThreadCount / 2, 1); // if no C2ME threads were found, default to 50%, C2ME's default
			int newDhThreadCount = MathUtil.clamp(expectedC2meThreadCount, numberOfC2meThreads, cpuThreadCount);
			
			LOGGER.info("Found ["+numberOfC2meThreads+"] C2ME threads. DH needs to use at least the same number of threads as C2ME to prevent issues with Chunky.");
			
			if (chunkyPresent)
			{
				Config.Common.MultiThreading.numberOfThreads.setApiValue(numberOfC2meThreads, "Chunky");
				Config.Common.MultiThreading.threadRunTimeRatio.setApiValue(1.0, "Chunky"); // C2ME threads have 100% uptime, so should we
				Config.Client.threadPresetSetting.setApiValue(EDhApiThreadPreset.CUSTOM, "Chunky");
				
				LOGGER.info("Set DH thread count to: ["+newDhThreadCount+"] to match C2ME.");
			}
		}
		
		//endregion
		
	}
	
	/**
	 * Some Minecraft versions don't support all
	 * DH options.
	 * In that case we need to override what options are available.
	 */
	private static void setUnsupportedConfigsBasedOnMcVersion()
	{
		// graphics/rendering
		#if MC_VER <= MC_1_12_2
		Config.Client.Advanced.Graphics.Experimental.renderingEngine.setMcVersionOverrideValue(EDhApiRenderingEngine.OPEN_GL);
		Config.Common.WorldGenerator.chunkGeneratorMode.setMcVersionOverrideValue(EDhApiDistantGeneratorMode.INTERNAL_SERVER);
		
		// Disabled since it prevents the JVM from exiting in 1.7.10
		Config.Client.Advanced.Debugging.OpenGl.overrideVanillaGLLogger.setMcVersionOverrideValue(false);
		#elif MC_VER <= MC_1_21_10
		Config.Client.Advanced.Graphics.Experimental.renderingEngine.setMcVersionOverrideValue(EDhApiRenderingEngine.OPEN_GL);
		#else
		#endif
		
		// worldgen
		#if MC_VER <= MC_1_18_2
		AbstractMinecraftSharedWrapper.supportsSurfaceGeneration = false;
		EDhApiGeneratorPlan genPlan = Config.Common.WorldGenerator.generatorPlan.getTrueValue();
		if (genPlan.surfaceGenEnabled)
		{
			Config.Common.WorldGenerator.generatorPlan.set(EDhApiGeneratorPlan.CHUNKS_ONLY);
		}
		#else
		AbstractMinecraftSharedWrapper.supportsSurfaceGeneration = true;
		#endif
	}
	
	/**
	 * Some DH configs should be disabled if a given
	 * mod is present.
	 */
	private static void setDisabledDhConfigBasedOnMods()
	{
		IIrisAccessor irisAccessor = ModAccessorInjector.INSTANCE.get(IIrisAccessor.class);
		if (irisAccessor != null)
		{
			// Transparency is required when Iris shaders are present, otherwise
			// rendering will not work properly.
			// Note: this fix doesn't prevent disabling transparency
			// due to a lack of vertical LOD slices, so that may still cause issues.
			Config.Client.Advanced.Graphics.Quality.transparency.setApiValue(EDhApiTransparency.COMPLETE, ModInfo.READABLE_NAME + " / " + IIrisAccessor.READABLE_NAME);
		}
	}
	
	//endregion
	
	
	
	//================//
	// helper classes //
	//================//
	//region helper classes
	
	public interface IEventProxy
	{
		void registerEvents();
	}
	
	//endregion
	
	
	
}
