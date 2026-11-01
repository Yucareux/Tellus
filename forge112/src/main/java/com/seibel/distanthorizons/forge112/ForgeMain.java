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

package com.seibel.distanthorizons.forge112;

import cofh.thermaldynamics.block.BlockDuct;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBlockColorOverrideEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBlockStateWrapperCreatedEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBlockTextureOverrideEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.core.wrapperInterfaces.modLoader.IForgeMain;
import com.seibel.distanthorizons.forge112.modAccessor.IrisAccessor;
import com.seibel.distanthorizons.forge112.modAccessor.ModChecker;
import com.seibel.distanthorizons.forge112.modCompat.quark.Quark;
import com.seibel.distanthorizons.forge112.modCompat.sereneseasons.SereneSeasons;
import com.seibel.distanthorizons.forge112.modCompat.thermaldynamics.ThermalDynamics;
import com.seibel.distanthorizons.common.AbstractModInitializer;
import com.seibel.distanthorizons.common.commands.CommandInitializer;
import com.seibel.distanthorizons.common.wrappers.block.ClientBlockStateColorCache;
import com.seibel.distanthorizons.core.api.internal.ServerApi;
import com.seibel.distanthorizons.common.util.threading.ServerThreadTaskHandler;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IPluginPacketSender;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IModChecker;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.coreapi.util.ColorUtil;
import net.minecraft.block.*;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.*;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import vazkii.quark.base.module.ModuleLoader;
import vazkii.quark.client.feature.GreenerGrass;


import java.util.List;
import java.util.function.Consumer;

import static com.seibel.distanthorizons.common.wrappers.block.ClientBlockStateColorCache.calculateColorFromTexture;

/**
 * Initialize and setup the Mod. <br>
 * If you are looking for the real start of the mod
 * check out the ClientProxy.
 */
@Mod(modid = ModInfo.ID, name = ModInfo.NAME, version = ModInfo.VERSION, acceptableRemoteVersions = "*", dependencies = "required-after:mixinbooter@[11.8,);")
public class ForgeMain extends AbstractModInitializer implements IForgeMain
{
	public static IIrisAccessor IRIS_ACCESSOR;
	
	public static final boolean IS_QUARK_LOADED = Loader.isModLoaded("quark");
	public static final boolean IS_FURENIKUSROADS_LOADED = Loader.isModLoaded("furenikusroads");
	public static final boolean IS_IMMERSIVERAILRAODING_LOADED = Loader.isModLoaded("immersiverailroading");
	public static final boolean IS_BOTANIA_LOADED = Loader.isModLoaded("botania");
	public static final boolean IS_LUCRAFT_LOADED = Loader.isModLoaded("lucraftcore");
	public static final boolean IS_THERMAL_DYNAMICS_LOADED = Loader.isModLoaded("thermaldynamics");
	public static final boolean IS_SERENE_SEASONS_LOADED = Loader.isModLoaded("sereneseasons");
	
	@Mod.Instance
	public static ForgeMain instance;
	
	public ForgeMain()
	{
		SingletonInjector.INSTANCE.bind(IForgeMain.class, this);
	}
	
	@Mod.EventHandler
	public void preinit(FMLPreInitializationEvent event)
	{
		Configurator.setLevel("org.sqlite", Level.INFO);
		
	}
	
	@Mod.EventHandler
	public void init(FMLInitializationEvent event)
	{
		if (FMLCommonHandler.instance().getEffectiveSide().isClient())
		{
			this.onInitializeClient();
		}
		else
		{
			this.onInitializeServer();
		}
		
		DhApi.events.bind(DhApiBlockStateWrapperCreatedEvent.class, new BlockWrapperCreated());
		DhApi.events.bind(DhApiBlockColorOverrideEvent.class, new BlockColorOverrider());
		DhApi.events.bind(DhApiBlockTextureOverrideEvent.class, new BlockTextureOverrider());
		
		ForgeChunkManager.setForcedChunkLoadingCallback(
			instance,
			(List<ForgeChunkManager.Ticket> tickets, World world) -> chunkLoadedCallback());
	}
	
	private void chunkLoadedCallback() { }
	
	@Override
	protected void createInitialSharedBindings()
	{
		SingletonInjector.INSTANCE.bind(IModChecker.class, ModChecker.INSTANCE);
		SingletonInjector.INSTANCE.bind(IPluginPacketSender.class, new ForgePluginPacketSender());
	}
	@Override
	protected void createInitialClientBindings() { /* no additional setup needed currently */ }
	
	@Override
	protected IEventProxy createClientProxy() { return new ForgeClientProxy(); }
	
	@Override
	protected IEventProxy createServerProxy(boolean isDedicated) { return new ForgeServerProxy(isDedicated); }
	
	@Override
	protected void initializeModCompat()
	{
		this.tryCreateModCompatAccessor("actinium", IIrisAccessor.class, IrisAccessor::new);
		IRIS_ACCESSOR = ModAccessorInjector.INSTANCE.get(IIrisAccessor.class);
	}
	
/*	@Override
	protected void subscribeRegisterCommandsEvent(Consumer<CommandDispatcher<CommandSourceStack>> eventHandler)
	{ MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> { eventHandler.accept(e.getDispatcher()); }); }*/
	
	@Override
	protected void subscribeClientStartedEvent(Runnable eventHandler)
	{
		// Just run the event handler, since there are no proper ClientLifecycleEvent for the client 
		// to signify readiness other than FmlClientSetupEvent
		eventHandler.run();
	}
	
	@Mod.EventHandler
	public void onServerStarting(FMLServerStartingEvent event)
	{
		event.registerServerCommand(CommandInitializer.initCommands());
	}
	
	@Mod.EventHandler
	public void onServerAboutToStart(FMLServerAboutToStartEvent event)
	{
		ServerThreadTaskHandler.INSTANCE.reset();

		if (eventHandlerStartServer != null)
		{
			eventHandlerStartServer.accept(event.getServer());
		}
		
		ServerApi.INSTANCE.serverLoadEvent(event.getServer().isDedicatedServer());
	}
	
	Consumer<MinecraftServer> eventHandlerStartServer;
	
	@Override
	protected void subscribeServerStartingEvent(Consumer<MinecraftServer> eventHandler)
	{
		eventHandlerStartServer = eventHandler;
	}
	
	@Override
	protected void runDelayedSetup() { SingletonInjector.INSTANCE.runDelayedSetup(); }
	
	// ServerWorldUnloadEvent
	@Mod.EventHandler
	public void serverWorldUnloadEvent(FMLServerStoppingEvent event)
	{
		ServerThreadTaskHandler.INSTANCE.cancelPendingTasks();
		ServerApi.INSTANCE.serverUnloadEvent();
	}
	
	
	
	public static class BlockColorOverrider extends DhApiBlockColorOverrideEvent
	{
		@Override
		public void onBlockColorOverridden(DhApiEventParam<EventParam> event)
		{
			IBlockState blockState = (IBlockState) event.value.getBlockStateWrapper().getWrappedMcObject();
			Block block = blockState.getBlock();
			
			int tintColor = event.value.getTintColorAsInt();
			
			if (block instanceof BlockGrass || block instanceof BlockBush)
			{
				if (IS_SERENE_SEASONS_LOADED)
				{
					tintColor = SereneSeasons.applySereneSeasonsGrassTint((Biome) event.value.getBiomeWrapper().getWrappedMcObject(), tintColor);
				}
				if (IS_QUARK_LOADED && ModuleLoader.isFeatureEnabled(GreenerGrass.class))
				{
					tintColor = Quark.applyQuarksGreenerGrassFoliageTint(tintColor);
				}
				
				int finalReturnColor = ColorUtil.multiplyARGBwithRGB(event.value.getBaseColorAsInt(), tintColor);
				event.value.setColor(ColorUtil.getRed(finalReturnColor), ColorUtil.getGreen(finalReturnColor), ColorUtil.getBlue(finalReturnColor));
			}
			else if (block instanceof BlockLeaves)
			{
				if (IS_SERENE_SEASONS_LOADED)
				{
					tintColor = SereneSeasons.applySereneSeasonsFoliageTint((Biome) event.value.getBiomeWrapper().getWrappedMcObject(), tintColor);
				}
				if (IS_QUARK_LOADED && ModuleLoader.isFeatureEnabled(GreenerGrass.class) && GreenerGrass.affectFoliage)
				{
					tintColor = Quark.applyQuarksGreenerGrassFoliageTint(tintColor);
				}
				
				int finalReturnColor = ColorUtil.multiplyARGBwithRGB(event.value.getBaseColorAsInt(), tintColor);
				event.value.setColor(ColorUtil.getRed(finalReturnColor), ColorUtil.getGreen(finalReturnColor), ColorUtil.getBlue(finalReturnColor));
			}
			else if (IS_THERMAL_DYNAMICS_LOADED && block instanceof BlockDuct)
			{
				int finalReturnColor = calculateColorFromTexture(ThermalDynamics.getThermalDynamicDuctTexture(blockState), ClientBlockStateColorCache.EColorMode.Default);
				event.value.setColor(ColorUtil.getRed(finalReturnColor), ColorUtil.getGreen(finalReturnColor), ColorUtil.getBlue(finalReturnColor));
			}
			else if (IS_IMMERSIVERAILRAODING_LOADED && (blockState.toString().equals("immersiverailroading:block_rail") || blockState.toString().equals("immersiverailroading:block_rail_gag")))
			{
				IBlockState plankState = Blocks.PLANKS.getDefaultState().withProperty(BlockPlanks.VARIANT, BlockPlanks.EnumType.DARK_OAK);
				int finalReturnColor = calculateColorFromTexture(Minecraft.getMinecraft().getBlockRendererDispatcher().getBlockModelShapes().getTexture(plankState), ClientBlockStateColorCache.EColorMode.Default);
				event.value.setColor(ColorUtil.getRed(finalReturnColor), ColorUtil.getGreen(finalReturnColor), ColorUtil.getBlue(finalReturnColor));
			}
			
		}
		
	}
	
	public static class BlockWrapperCreated extends DhApiBlockStateWrapperCreatedEvent
	{
		@Override
		public void blockStateWrapperCreated(DhApiEventParam<EventParam> event)
		{
			IBlockState blockState = (IBlockState) event.value.getBlockStateWrapper().getWrappedMcObject();
			Block block = blockState.getBlock();
			if (block instanceof BlockGrass || block instanceof BlockBush)
			{
				if ((IS_QUARK_LOADED && ModuleLoader.isFeatureEnabled(GreenerGrass.class)) || (IS_SERENE_SEASONS_LOADED))
				{
					event.value.setAllowApiColorOverride(true);
				}
			}
			else if (block instanceof BlockLeaves)
			{
				if ((IS_QUARK_LOADED && ModuleLoader.isFeatureEnabled(GreenerGrass.class) && GreenerGrass.affectFoliage) || (IS_SERENE_SEASONS_LOADED))
				{
					event.value.setAllowApiColorOverride(true);
				}
			}
			else if (IS_THERMAL_DYNAMICS_LOADED && block instanceof BlockDuct)
			{
				event.value.setAllowApiColorOverride(true);
			}
			
		}
		
	}
	
	public static class BlockTextureOverrider extends DhApiBlockTextureOverrideEvent
	{
		@Override
		public void onBlockTextureOverridden(DhApiEventParam<EventParam> event)
		{
			EventParam param = event.value;
			IBlockState blockState = (IBlockState) param.getBlockStateWrapper().getWrappedMcObject();
			Block block = blockState.getBlock();
			
			if (IS_THERMAL_DYNAMICS_LOADED && block instanceof BlockDuct)
			{
				TextureAtlasSprite sprite = ThermalDynamics.getThermalDynamicDuctTexture(blockState);
				copySpriteIntoEventParam(sprite, param);
			}
		}
		
		private static void copySpriteIntoEventParam(TextureAtlasSprite sprite, EventParam param)
		{
			if (sprite == null)
			{
				return;
			}
			
			int destWidth = param.getWidth();
			int destHeight = param.getHeight();
			int spriteWidth = sprite.getIconWidth();
			int spriteHeight = sprite.getIconHeight();
			if (spriteWidth <= 0 || spriteHeight <= 0)
			{
				return;
			}
			
			int[][] frameData = sprite.getFrameTextureData(0);
			int[] pixels = frameData[0];
			
			for (int u = 0; u < destWidth; u++)
			{
				for (int v = 0; v < destHeight; v++)
				{
					int texelX = (u * spriteWidth) / destWidth;
					int texelY = (v * spriteHeight) / destHeight;
					
					int packed = pixels[(texelY * spriteWidth) + texelX];
					
					int a = (packed >>> 24) & 0xFF;
					int r = packed & 0xFF;
					int g = (packed >>> 8) & 0xFF;
					int b = (packed >>> 16) & 0xFF;
					
					param.setColor(u, v, a, r, g, b);
				}
			}
		}
		
	}
	
}
