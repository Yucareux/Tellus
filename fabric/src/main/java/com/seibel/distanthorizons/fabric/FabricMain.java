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

package com.seibel.distanthorizons.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.seibel.distanthorizons.common.AbstractModInitializer;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.common.wrappers.gui.NativeDialogUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IPluginPacketSender;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.*;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.fabric.wrappers.modAccessor.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import com.seibel.distanthorizons.core.logging.DhLogger;

#if MC_VER >= MC_1_19_2
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
#else // < 1.19.2
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
#endif

#if MC_VER <= MC_1_21_10
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif

import java.util.function.Consumer;

/**
 * Initialize and setup the Mod. <br>
 * If you are looking for the real start of the mod
 * check out the ClientProxy.
 */
public class FabricMain extends AbstractModInitializer implements ClientModInitializer, DedicatedServerModInitializer
{
	#if MC_VER <= MC_1_20_6
	private static final ResourceLocation INITIAL_PHASE = new ResourceLocation(ModInfo.RESOURCE_NAMESPACE, ModInfo.DEDICATED_SERVER_INITIAL_PATH);
	#elif MC_VER <= MC_1_21_10
	private static final ResourceLocation INITIAL_PHASE = ResourceLocation.fromNamespaceAndPath(ModInfo.RESOURCE_NAMESPACE, ModInfo.DEDICATED_SERVER_INITIAL_PATH);
	#else
	private static final Identifier INITIAL_PHASE = Identifier.fromNamespaceAndPath(ModInfo.RESOURCE_NAMESPACE, ModInfo.DEDICATED_SERVER_INITIAL_PATH);
	#endif
	
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	
	
	@Override
	protected void createInitialSharedBindings()
	{
		SingletonInjector.INSTANCE.bind(IModChecker.class, ModChecker.INSTANCE);
		SingletonInjector.INSTANCE.bind(IPluginPacketSender.class, new FabricPluginPacketSender());
	}
	@Override
	protected void createInitialClientBindings() { /* no additional setup needed currently */ }
	
	
	@Override
	protected IEventProxy createClientProxy() { return new FabricClientProxy(); }
	
	@Override
	protected IEventProxy createServerProxy(boolean isDedicated) { return new FabricServerProxy(isDedicated); }
	
	@Override
	protected void initializeModCompat()
	{
		IModChecker modChecker = SingletonInjector.INSTANCE.get(IModChecker.class);
		if (modChecker.isModLoaded("sodium"))
		{
			ModAccessorInjector.INSTANCE.bind(ISodiumAccessor.class, new SodiumAccessor());
			
			// If sodium is installed Indium is also necessary for versions 0.5 and less in order to use the Fabric rendering API
			if (!modChecker.isModLoaded("indium") && SodiumAccessor.isSodiumV5OrLess)
			{
				String indiumMissingMessage = ModInfo.READABLE_NAME + " needs Indium to work with Sodium.\nPlease download Indium from https://modrinth.com/mod/indium";
				LOGGER.fatal(indiumMissingMessage);
				
				NativeDialogUtil.showDialog(ModInfo.READABLE_NAME, indiumMissingMessage, "ok", "error");
				
				IMinecraftClientWrapper mc = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
				String errorMessage = "loading Distant Horizons. Distant Horizons requires Indium in order to run with Sodium.";
				String exceptionError = "Distant Horizons conditional mod Exception";
				mc.crashMinecraft(errorMessage, new Exception(exceptionError));
			}
		}
		
		this.tryCreateModCompatAccessor("starlight", IStarlightAccessor.class, StarlightAccessor::new);
		this.tryCreateModCompatAccessor("optifine", IOptifineAccessor.class, OptifineAccessor::new);
		this.tryCreateModCompatAccessor("bclib", IBCLibAccessor.class, BCLibAccessor::new);
		this.tryCreateModCompatAccessor("c2me", IC2meAccessor.class, C2meAccessor::new);
		this.tryCreateModCompatAccessor(IImmersivePortalsAccessor.MOD_ID_ARRAY, IImmersivePortalsAccessor.class, ImmersivePortalsAccessorFabric::new);
		this.tryCreateModCompatAccessor("chunky", IChunkyAccessor.class, ChunkyAccessor::new);
		
		#if MC_VER >= MC_1_20_1
		// 1.20.1 is the lowest version Iris supports DH
		this.tryCreateModCompatAccessor("iris", IIrisAccessor.class, IrisAccessor::new);
		#endif
	}
	
	@Override
	protected void subscribeRegisterCommandsEvent(Consumer<CommandDispatcher<CommandSourceStack>> eventHandler)
	{
		CommandRegistrationCallback.EVENT.register(
			(dispatcher, registryAccess #if MC_VER >= MC_1_19_2 , environment #endif ) ->
			{
				eventHandler.accept(dispatcher);
			}
		);
	}
	
	@Override
	protected void subscribeClientStartedEvent(Runnable eventHandler)
	{ ClientLifecycleEvents.CLIENT_STARTED.register((mc) -> eventHandler.run()); }
	
	@Override
	protected void subscribeServerStartingEvent(Consumer<MinecraftServer> eventHandler)
	{
		ServerLifecycleEvents.SERVER_STARTING.addPhaseOrdering(INITIAL_PHASE, Event.DEFAULT_PHASE);
		ServerLifecycleEvents.SERVER_STARTING.register(INITIAL_PHASE, eventHandler::accept);
	}
	
	@Override
	protected void runDelayedSetup()
	{
		SingletonInjector.INSTANCE.runDelayedSetup();
		
		if (!Config.Client.Advanced.Graphics.Fog.enableVanillaFog.get() 
			&& SingletonInjector.INSTANCE.get(IModChecker.class).isModLoaded("bclib"))
		{
			ModAccessorInjector.INSTANCE.get(IBCLibAccessor.class).setRenderCustomFog(false); // Remove BCLib's fog
		}
	}
	
}
