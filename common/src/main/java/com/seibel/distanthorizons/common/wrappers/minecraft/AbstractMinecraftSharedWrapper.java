package com.seibel.distanthorizons.common.wrappers.minecraft;

import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftSharedWrapper;
import java.util.Arrays;
import org.jetbrains.annotations.Nullable;

#if MC_VER > MC_1_12_2
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.client.Minecraft;
#endif

#if MC_VER <= MC_1_7_10
import net.minecraft.server.MinecraftServer;
import cpw.mods.fml.common.FMLCommonHandler;
#elif MC_VER <= MC_1_12_2
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
#elif MC_VER <= MC_1_21_10
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif

#if MC_VER > MC_1_19_2
import net.minecraft.core.registries.Registries;
#elif MC_VER > MC_1_12_2
import net.minecraft.core.Registry;
#endif

public abstract class AbstractMinecraftSharedWrapper implements IMinecraftSharedWrapper
{
	public static boolean supportsSurfaceGeneration = false;
	
	
	
	@Nullable
	#if MC_VER <= MC_1_12_2
	protected Integer deserializeDimensionResourceKey(String dimensionResourceLocation)
	#else
	protected ResourceKey<Level> deserializeDimensionResourceKey(String dimensionResourceLocation)
	#endif
	{
		#if  MC_VER <= MC_1_12_2
		try
		{
			return Integer.parseInt(dimensionResourceLocation.substring(dimensionResourceLocation.indexOf(":")+1));
		}
		catch (NumberFormatException ignored)
		{
			return null;
		}
		#else
			#if  MC_VER <= MC_1_21_10
			ResourceLocation dimResourceLocation = ResourceLocation.tryParse(dimensionResourceLocation);
			#else
			Identifier dimResourceLocation = Identifier.tryParse(dimensionResourceLocation);
			#endif
			if (dimResourceLocation == null)
			{
				return null;
			}
			
			#if  MC_VER > MC_1_19_2
			ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, dimResourceLocation);
			#else
			ResourceKey<Level> dimensionKey = ResourceKey.create(Registry.DIMENSION_REGISTRY, dimResourceLocation);
			#endif
			
		return dimensionKey;
		#endif
	}
	
	@Override
	public boolean isServerThreadHealthy()
	{
		long[] nanoTicks = this.getServerTickTimesNano();
		if (nanoTicks == null)
		{
			// assume the server is healthy if
			// we can't get the ticks
			return true;
		}
		
		long[] sortedNanoTicks = nanoTicks.clone();
		Arrays.sort(sortedNanoTicks);
		
		// 99 percentile
		int p99Index = (int)Math.ceil(0.99 * sortedNanoTicks.length) - 1;
		double p99Ms = sortedNanoTicks[Math.max(0, p99Index)] * 1e-6; // convert from Nano to Milli
		
		double avgMs = Arrays.stream(sortedNanoTicks).average().orElse(0) * 1e-6; // convert from Nano to Milli
		
		return avgMs < 25.0 // 50 ms is standard tick rate
			&& p99Ms < 50.0;
	}
	private long @Nullable [] getServerTickTimesNano()
	{
		// currently this logic is only implemented for singleplayer servers (except 1.12.2 and lower)
		#if MC_VER > MC_1_12_2
		if (this.isDedicatedServer())
		{
			return null;
		}
		#endif
		
		#if MC_VER <= MC_1_12_2
		MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
		if (server == null)
		{
			return null;
		}
		return server.tickTimeArray;
		#elif MC_VER <= MC_1_20_2

		if (Minecraft.getInstance().getSingleplayerServer() == null)
		{
			return null;
		}
		return Minecraft.getInstance().getSingleplayerServer().tickTimes;
		#else
		
		if (Minecraft.getInstance().getSingleplayerServer() == null)
		{
			return null;
		}
		return Minecraft.getInstance().getSingleplayerServer().getTickTimesNanos();
		#endif
	}
	
	
	@Override
	public boolean supportsSurfaceGeneration() { return supportsSurfaceGeneration; }
	
	
	
}
