package com.seibel.distanthorizons.forge17;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.seibel.distanthorizons.common.wrappers.block.IBiomeHandler;
import com.seibel.distanthorizons.forge17.mixin.MixinBiomeGenBase;
import net.minecraft.world.biome.BiomeGenBase;

/**
 * Tracks all the biomes
 * created in MC 1.7.10 so we
 * can (de)serialize them. <br><br>
 * 
 * This assumes no biomes are created after the initial startup.
 */
public class BiomeHandler implements IBiomeHandler
{
	public static final BiomeHandler INSTANCE = new BiomeHandler(); 
	
	private final List<BiomeGenBase> biomeList = new ArrayList<>();
	private ConcurrentHashMap<String, BiomeGenBase> biomeByName;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private BiomeHandler() {}
	
	//endregion
	
	
	
	//=========//
	// methods //
	//=========//
	//region
	
	/** 
	 * Called by {@link MixinBiomeGenBase} to
	 * populate all possible options.
	 */
	public void addBiome(BiomeGenBase biome) { this.biomeList.add(biome); }
	
	public BiomeGenBase getBiomeByName(String name)
	{
		// lazy initialization since we need to wait for BIOME_LIST
		// to finish populating before we can put together the map
		if (this.biomeByName == null)
		{
			// May be called from multiple threads, causing the
			// map to be created multiple times.
			// Not a big deal since the ending Concurrent Map should be the same either way.
			ConcurrentHashMap<String, BiomeGenBase> newBiomeByName = new ConcurrentHashMap<>();
			for (BiomeGenBase biome : biomeList)
			{
				newBiomeByName.put(biome.biomeName, biome);
			}
			this.biomeByName = newBiomeByName;
		}
		
		return this.biomeByName.get(name);
	}
	
	//endregion
	
	
	
}
