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

package com.seibel.distanthorizons.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.seibel.distanthorizons.common.AbstractModInitializer;
import com.seibel.distanthorizons.common.wrappers.gui.GetConfigScreen;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.api.internal.ServerApi;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.network.messages.AbstractNetworkMessage;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IPluginPacketSender;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.*;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.neoforge.wrappers.modAccessor.*;
import com.seibel.distanthorizons.neoforge.wrappers.NeoforgeMinecraftRenderWrapper;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.function.Consumer;

#if MC_VER < MC_1_20_6
import net.neoforged.neoforge.client.ConfigScreenHandler;
#elif MC_VER < MC_1_21_8
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.jetbrains.annotations.NotNull;
#else
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.jetbrains.annotations.NotNull;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
#endif

/**
 * Initialize and setup the Mod. <br>
 * If you are looking for the real start of the mod
 * check out the ClientProxy.
 */
@Mod(ModInfo.ID)
@SuppressWarnings("unused")
public class NeoforgeMain extends AbstractModInitializer
{
	public NeoforgeMain(IEventBus eventBus)
	{
		// handles singleplayer, LAN, and connecting to a server
		eventBus.addListener((FMLClientSetupEvent e) ->
		{
			this.onInitializeClient();
			eventBus.addListener(this::registerNetworkingClientServer);
			
			#if MC_VER < MC_1_21_8
			#else
			eventBus.addListener(this::registerClientPayloadEvent);
			#endif
		});
		
		// handles dedicated servers
		eventBus.addListener((FMLDedicatedServerSetupEvent e) ->
		{
			this.onInitializeServer();
			eventBus.addListener(this::registerNetworkingServer);
		});
	}
	
	
	
	//============//
	// networking //
	//============//
	
	public void registerNetworkingClientServer(RegisterPayloadHandlersEvent event)
	{
		NeoforgePluginPacketSender.setPacketHandler(event, (IServerPlayerWrapper player, @NotNull AbstractNetworkMessage message) ->
		{
			ClientApi.INSTANCE.pluginMessageReceived(message);
			ServerApi.INSTANCE.pluginMessageReceived(player, message);
		});
	}
	public void registerNetworkingServer(RegisterPayloadHandlersEvent event)
	{ NeoforgePluginPacketSender.setPacketHandler(event, ServerApi.INSTANCE::pluginMessageReceived); }
	
	#if MC_VER < MC_1_21_8
	#else
	public void registerClientPayloadEvent(RegisterClientPayloadHandlersEvent event)
	{ NeoforgePluginPacketSender.registerClientPacketHandler(event); }
	#endif
	
	
	
	
	
	@Override
	protected IEventProxy createServerProxy(boolean isDedicated) { return new NeoforgeServerProxy(isDedicated); }
	
	@Override
	protected void createInitialSharedBindings()
	{
		SingletonInjector.INSTANCE.bind(IModChecker.class, ModChecker.INSTANCE);
		SingletonInjector.INSTANCE.bind(IPluginPacketSender.class, new NeoforgePluginPacketSender());
	}
	@Override
	protected void createInitialClientBindings()
	{
		// replace MC RenderWrapper with the more specific neoforge version
		SingletonInjector.INSTANCE.replaceBinding(IMinecraftRenderWrapper.class, NeoforgeMinecraftRenderWrapper.INSTANCE);
	}
	
	@Override
	protected IEventProxy createClientProxy() { return new NeoforgeClientProxy(); }
	
	@Override
	protected void initializeModCompat()
	{
		this.tryCreateModCompatAccessor("optifine", IOptifineAccessor.class, OptifineAccessor::new);
		this.tryCreateModCompatAccessor("c2me", IC2meAccessor.class, C2meAccessor::new);
		this.tryCreateModCompatAccessor(IImmersivePortalsAccessor.MOD_ID_ARRAY, IImmersivePortalsAccessor.class, ImmersivePortalsAccessorNeoForge::new);
		this.tryCreateModCompatAccessor("chunky", IChunkyAccessor.class, ChunkyAccessor::new);
		
		#if MC_VER >= MC_1_20_6
		// 1.20.6 is the lowest version Iris supports Neoforge
		this.tryCreateModCompatAccessor("iris", IIrisAccessor.class, IrisAccessor::new);
		#endif
		
		#if MC_VER < MC_1_20_6
		ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
				() -> new ConfigScreenHandler.ConfigScreenFactory((client, parent) -> GetConfigScreen.getScreen(parent)));
		#else
		ModLoadingContext.get().registerExtensionPoint(IConfigScreenFactory.class,
			() -> (client, parent) -> GetConfigScreen.getScreen(parent));
		#endif
		
	}
	
	@Override
	protected void subscribeRegisterCommandsEvent(Consumer<CommandDispatcher<CommandSourceStack>> eventHandler)
	{
		NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) ->  { eventHandler.accept(e.getDispatcher()); });
	}
	
	@Override
	protected void subscribeClientStartedEvent(Runnable eventHandler)
	{
		// Just run the event handler, since there are no proper ClientLifecycleEvent for the client 
		// to signify readiness other than FmlClientSetupEvent
		eventHandler.run();
	}
	
	@Override
	protected void subscribeServerStartingEvent(Consumer<MinecraftServer> eventHandler)
	{
		NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, (ServerAboutToStartEvent e) -> { eventHandler.accept(e.getServer()); });
	}
	
	@Override
	protected void runDelayedSetup() { SingletonInjector.INSTANCE.runDelayedSetup(); }
	
}
