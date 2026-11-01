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

package com.seibel.distanthorizons.core;

import com.github.luben.zstd.ZstdOutputStream;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiCustomRenderObjectFactory;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.eventHandlers.IgnoredDimensionCsvHandler;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.enums.MinecraftTextFormat;
import com.seibel.distanthorizons.core.jar.EPlatform;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.sql.DatabaseUpdater;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import com.seibel.distanthorizons.core.world.DhApiWorldProxy;
import com.seibel.distanthorizons.core.api.external.methods.config.DhApiConfig;
import com.seibel.distanthorizons.core.api.external.methods.data.DhApiTerrainDataRepo;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.core.render.DhApiRenderProxy;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftSharedWrapper;
import net.jpountz.lz4.LZ4FrameOutputStream;
import com.seibel.distanthorizons.core.logging.DhLogger;
import org.sqlite.SQLiteJDBCLoader;
import org.tukaani.xz.XZOutputStream;

import java.awt.*;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.List;

/** Handles first time Core setup. */
public class Initializer
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	private static final IMinecraftSharedWrapper MC_SHARED = SingletonInjector.INSTANCE.get(IMinecraftSharedWrapper.class);
	
	
	
	public static void preConfigInit()
	{
		//============================//
		// check referenced libraries //
		//============================//
		//region
		
		LOGGER.info("Running library validation...");
		
		// confirm that all referenced libraries are available to use
		try
		{
			// if any library isn't present in the jar its class
			// will throw an error (not an exception)
			Class<?> lz4Compressor = LZ4FrameOutputStream.class;
			Class<?> zstdCompressor = ZstdOutputStream.class;
			
			{
				byte[] testCompressByteArray = new byte[1024];
				for (int i = 0; i < testCompressByteArray.length; i++)
				{
					testCompressByteArray[i] = (byte) (i % 126);
				}
				byte[] compressedBytes = com.github.luben.zstd.Zstd.compress(testCompressByteArray);
				com.github.luben.zstd.Zstd.decompress(compressedBytes);
			}
			
			Class<?> lzmaCompressor = XZOutputStream.class;
			//Class<?> networking = ByteBuf.class;
			Class<?> config = com.electronwill.nightconfig.core.Config.class;
			Class<?> oldFastUtil = it.unimi.dsi.fastutil.longs.LongArrayList.class; // available in 8.2.1
			//Class<?> newFastUtil = it.unimi.dsi.fastutil.ints.IntUnaryOperator.class; // available in 8.5.13
			Class<?> sqliteJava = org.sqlite.SQLiteConnection.class;
			Class<?> sqliteNative = org.sqlite.core.NativeDB.class;
			
			boolean sqliteLoaded = SQLiteJDBCLoader.initialize();
			if (!sqliteLoaded)
			{
				throw new RuntimeException("Failed to load SQLite native library. Hopefully SQLite logged a reason for this failure.");
			}
		}
		catch (Throwable e)
		{
			MC_CLIENT.crashMinecraft("Distant Horizons critical setup error: One or more libraries are either in-accessible, corrupted, or overwritten by another mod. Error: [" + e.getMessage() + "].", e);
		}
		
		//endregion
		
		
		
		//==========================//
		// check resource directory //
		//==========================//
		//region
		
		try
		{
			int scriptCount = DatabaseUpdater.getAutoUpdateScriptCount();
			if (scriptCount == 0)
			{
				throw new NullPointerException("No auto update scripts found, but no error thrown. This might mean the script list file is corrupted or empty.");
			}
		}
		catch (Exception e)
		{
			MC_CLIENT.crashMinecraft("Critical programmer error: Can't read SQL Scripts resource folder is either missing or malformed. Error: [" + e.getMessage() + "].", e);
		}
		
		//endregion
		
		
		
		//===========================//
		// Java AWT Headless setting // 
		//===========================//
		//region
		
		// if the client is null then we are headless
		if (MC_CLIENT != null)
		{
			// AWT headless is necessary for Java Swing dialogs
			boolean setAwtHeadless = MC_CLIENT.dialogNeedsAwtHeadless();
			
			if (EPlatform.get() == EPlatform.MACOS)
			{
				// setting AWT headless to "true" on Mac causes a lock up
				// due to a bug with Java.awt texture loading
				setAwtHeadless = false;
			}
			
			if (setAwtHeadless)
			{
				System.setProperty("java.awt.headless", "false");
				if (GraphicsEnvironment.isHeadless())
				{
					LOGGER.warn("Java.awt.headless is false. This means Distant Horizons can't display error and info dialog windows.");
				}
				else
				{
					LOGGER.info("Java.awt.headless set to true. Distant Horizons can correctly display error and info dialog windows.");
				}
			}
		}
		
		//endregion
		
		
		
		//===================//
		// API delayed setup //
		//===================//
		//region
		
		// link Core's config to the API
		DhApi.Delayed.configs = DhApiConfig.INSTANCE;
		DhApi.Delayed.terrainRepo = DhApiTerrainDataRepo.INSTANCE;
		DhApi.Delayed.worldProxy = DhApiWorldProxy.INSTANCE;
		DhApi.Delayed.renderProxy = DhApiRenderProxy.INSTANCE;
		DhApi.Delayed.wrapperFactory = SingletonInjector.INSTANCE.get(IWrapperFactory.class);
		if (DhApi.Delayed.wrapperFactory == null)
		{
			MC_CLIENT.crashMinecraft("Programmer Error: No ["+IWrapperFactory.class.getSimpleName()+"] assigned to the DhApi.", new Exception());
		}
		
		DhApi.Delayed.customRenderObjectFactory = SingletonInjector.INSTANCE.get(IDhApiCustomRenderObjectFactory.class);
		if (DhApi.Delayed.customRenderObjectFactory == null)
		{
			MC_CLIENT.crashMinecraft("Programmer Error: No ["+IDhApiCustomRenderObjectFactory.class.getSimpleName()+"] assigned to the DhApi.", new Exception());
		}
		
		DhApi.events.bind(DhApiBeforeRenderEvent.class, IgnoredDimensionCsvHandler.INSTANCE);
		
		//endregion
		
	}
	
	/** fired after DH's config has been set up */
	public static void postConfigInit()
	{
		
		
		//==============================//
		// G1 Garbage collector warning //
		//==============================//
		//region
		
		// log a warning if G1GC is being used
		// (this garbage collector is known to cause stuttering)
		{
			boolean g1GcInUse = false;
			
			StringBuilder garbageCollectorNames = new StringBuilder();
			List<GarbageCollectorMXBean> gcMxBeans = ManagementFactory.getGarbageCollectorMXBeans();
			for (GarbageCollectorMXBean gcMxBean : gcMxBeans)
			{
				if (!garbageCollectorNames.toString().isEmpty())
				{
					garbageCollectorNames.append(", ");
				}
				garbageCollectorNames.append(gcMxBean.getName());
				
				// "G1 Young Generation" // "G1 Concurrent GC" // "G1 Old Generation"
				if (gcMxBean.getName().toLowerCase().contains("g1 "))
				{
					g1GcInUse = true;
				}
			}
			LOGGER.info("Garbage collectors: ["+garbageCollectorNames+"]");
			
			
			if (g1GcInUse)
			{
				String warningMessageHeader = "Distant Horizons: G1 Garbage collector detected.";
				String warningMessageBody = 
					"This can cause FPS stuttering. \n" +
					"It's recommended to use a concurrent garbage collector \n" +
					"like ZGC (Java 21+) or Shenandoah (Java 8 through 17) \n" +
					"for a smoother experience. \n" +
					"This warning can be disabled in the DH config."
					;
				
				if (Config.Common.Logging.Warning.logGarbageCollectorWarning.get())
				{
					LOGGER.warn(
						warningMessageHeader + "\n" +
						warningMessageBody +
						"");
				}
				
				if (Config.Common.Logging.Warning.showGarbageCollectorWarning.get())
				{
					ClientApi.INSTANCE.queueSlowChatMessage(
						MinecraftTextFormat.ORANGE + warningMessageHeader + MinecraftTextFormat.CLEAR_FORMATTING + "\n" +
						warningMessageBody +
						"");
				}
			}
		}
		
		//endregion
		
		
		
		//==============================//
		// Explicit GC disabled warning //
		//==============================//
		//region
		
		// disabling explicit GC can cause
		// out of memory crashes
		{
			boolean explicitGcDisabled = false;
			
			RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
			List<String> jvmArgs = runtimeMxBean.getInputArguments();
			for (String arg : jvmArgs)
			{
				// "-XX:+DisableExplicitGC"
				if (arg.toLowerCase().contains("DisableExplicitGC".toLowerCase()))
				{
					explicitGcDisabled = true;
				}
			}
			LOGGER.info("Explicit Garbage Collection: ["+(explicitGcDisabled ? "Disabled" : "Enabled")+"]");
			
			
			if (explicitGcDisabled)
			{
				String warningMessageHeader = "Distant Horizons: Explicit Garbage Collection Disabled.";
				String warningMessageBody = 
					"This can cause out of memory crashes. \n" +
					"The reason explicit GC would be disabled is to prevent \n" +
					"stuttering, which is better fixed by using a concurrent \n" +
					"garbage collector like \n" +
					"ZGC (Java 21+) or Shenandoah (Java 8 through 17). \n" +
					"This warning can be disabled in the DH config."
					;
				
				if (Config.Common.Logging.Warning.logExplicitGcDisabledWarning.get())
				{
					LOGGER.warn(
						warningMessageHeader + "\n" +
						warningMessageBody +
						"");
				}
				
				if (Config.Common.Logging.Warning.showExplicitGcDisabledWarning.get())
				{
					ClientApi.INSTANCE.queueSlowChatMessage(
						MinecraftTextFormat.ORANGE + warningMessageHeader + MinecraftTextFormat.CLEAR_FORMATTING + "\n" +
						warningMessageBody +
						"");
				}
			}
		}
		
		//endregion
		
	}
	
	
}
