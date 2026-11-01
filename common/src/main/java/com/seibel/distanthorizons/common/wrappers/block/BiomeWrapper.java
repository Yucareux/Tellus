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

package com.seibel.distanthorizons.common.wrappers.block;

import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;

#if MC_VER > MC_1_12_2
import net.minecraft.world.level.Level;
#endif

#if MC_VER <= MC_1_12_2
#elif MC_VER == MC_1_16_5 || MC_VER == MC_1_17_1
import net.minecraft.core.Registry;
#elif MC_VER == MC_1_18_2 || MC_VER == MC_1_19_2
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.data.BuiltinRegistries;
#else
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
#endif

#if MC_VER <= MC_1_7_10
#elif MC_VER <= MC_1_12_2
import net.minecraft.util.ResourceLocation;
#elif MC_VER <= MC_1_21_10
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
import net.minecraft.core.component.DataComponentMap;
#endif

#if MC_VER <= MC_1_7_10
import net.minecraft.world.biome.BiomeGenBase;
#elif MC_VER <= MC_1_12_2
import net.minecraft.world.biome.Biome;
#else
import net.minecraft.world.level.biome.Biome;
#endif


#if MC_VER >= MC_1_18_2
import net.minecraft.world.level.biome.Biomes;
#endif


/** This class wraps the minecraft BlockPos.Mutable (and BlockPos) class */
public class BiomeWrapper implements IBiomeWrapper
{
	// must be defined before AIR, otherwise a null pointer will be thrown
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	#if MC_VER <= MC_1_7_10
	private static final IBiomeHandler BIOME_HANDLER = SingletonInjector.INSTANCE.get(IBiomeHandler.class);
	#endif
	
	#if MC_VER <= MC_1_7_10
	public static final ConcurrentMap<BiomeGenBase, BiomeWrapper> WRAPPER_BY_BIOME = new ConcurrentHashMap<>();
	#elif MC_VER < MC_1_18_2
	public static final ConcurrentMap<Biome, BiomeWrapper> WRAPPER_BY_BIOME = new ConcurrentHashMap<>();
	#else
	public static final ConcurrentMap<Holder<Biome>, BiomeWrapper> WRAPPER_BY_BIOME = new ConcurrentHashMap<>();
    #endif
	
	public static final ConcurrentHashMap<String, BiomeWrapper> WRAPPER_BY_RESOURCE_LOCATION = new ConcurrentHashMap<>();
	
	public static final String EMPTY_BIOME_STRING = "EMPTY";
	public static final BiomeWrapper EMPTY_WRAPPER = new BiomeWrapper(null, null);
	
	public static final String PLAINS_RESOURCE_LOCATION_STRING = 
		#if MC_VER <= MC_1_7_10 "biome:Plains"
		#else "minecraft:plains" #endif;
	
	
	
	/** keep track of broken biomes so we don't log every time */
	private static final HashSet<String> brokenResourceLocationStrings = new HashSet<>();
	
	/** 
	 * Only display this warning once, otherwise the log may be spammed <br> 
	 * This is a known issue when joining Hypixel. 
	 */
	private static boolean emptyStringWarningLogged = false;
	private static boolean emptyLevelSerializeFailLogged = false; 
	
	
	
	// properties //
	
	#if MC_VER <= MC_1_7_10
	public final BiomeGenBase biome;
	#elif MC_VER < MC_1_18_2
	public final Biome biome;
	#else
	public final Holder<Biome> biome;
    #endif
	
	/** technically final, but since it requires a method call to generate it can't be marked as such */
	private String serialString;
	private final int hashCode;
	
	
	
	//==============//
	// constructors //
	//==============//
	//region
	
	#if MC_VER <= MC_1_7_10
	public static BiomeWrapper getBiomeWrapper(BiomeGenBase biome, ILevelWrapper levelWrapper)
	#elif MC_VER < MC_1_18_2
	public static BiomeWrapper getBiomeWrapper(Biome biome, ILevelWrapper levelWrapper)
	#else
	public static BiomeWrapper getBiomeWrapper(Holder<Biome> biome, ILevelWrapper levelWrapper)
	#endif
	{
		if (biome == null)
		{
			return EMPTY_WRAPPER;
		}
		
		
		BiomeWrapper biomeWrapper = WRAPPER_BY_BIOME.get(biome);
		if (biomeWrapper != null)
		{
			return biomeWrapper;
		}
		else
		{
			BiomeWrapper newWrapper = new BiomeWrapper(biome, levelWrapper);
			WRAPPER_BY_BIOME.put(biome, newWrapper);
			return newWrapper;
		}
	}
	
	#if MC_VER <= MC_1_7_10
	private BiomeWrapper(BiomeGenBase biome, ILevelWrapper levelWrapper)
	#elif MC_VER < MC_1_18_2
	private BiomeWrapper(Biome biome, ILevelWrapper levelWrapper)
	#else
	private BiomeWrapper(Holder<Biome> biome, ILevelWrapper levelWrapper)
	#endif
	{
		this.biome = biome;
		this.serialString = this.serialize(levelWrapper);
		this.hashCode = Objects.hash(this.serialString);
		
		//LOGGER.trace("Created BiomeWrapper ["+this.serialString+"] for ["+biome+"]");
	}
	
	//endregion
	
	
	
	//=========//
	// methods //
	//=========//
	//region
	
	@Override
	public String getName()
	{
		if (this == EMPTY_WRAPPER)
		{
			return EMPTY_BIOME_STRING;
		}
		
        #if MC_VER <= MC_1_7_10
		return this.biome.biomeName;
        #elif MC_VER < MC_1_18_2
		return this.biome.toString();
        #else
		return this.biome.unwrapKey().orElse(Biomes.THE_VOID).registry().toString();
        #endif
	}
	
	@Override
	public boolean isColdBiome()
	{
		// https://minecraft.wiki/w/Biome#Temperature
		#if MC_VER <= MC_1_7_10
		return this.biome.temperature < 0.1f;
		#elif MC_VER <= MC_1_12_2
		return this.biome.isSnowyBiome();
		#elif MC_VER < MC_1_18_2
		return this.biome.getBaseTemperature() < 0.1f;
		#else
		return this.biome.value().getBaseTemperature() < 0.1f;
		#endif
	}
	
	@Override
	public String getSerialString() { return this.serialString; }
	
	@Override
	public Object getWrappedMcObject() { return this.biome; }
	
	//endregion
	
	
	
	//=======================//
	// serialization methods //
	//=======================//
	//region
	
	public String serialize(ILevelWrapper levelWrapper)
	{
		if (this.biome == null)
		{
			return EMPTY_BIOME_STRING;
		}
		
		
		
		// we can't generate a serial string if the level is null
		if (levelWrapper == null)
		{
			if (!emptyLevelSerializeFailLogged)
			{
				emptyLevelSerializeFailLogged = true;
				LOGGER.warn("Unable to serialize biome: [" + this.biome + "] because the passed in level wrapper is null. Future errors of this type won't be logged.");
			}
			
			return EMPTY_BIOME_STRING;
		}
		
		
		
		// generate the serial string //
		
		#if MC_VER > MC_1_12_2
		Level level = (Level)levelWrapper.getWrappedMcObject();
		net.minecraft.core.RegistryAccess registryAccess = level.registryAccess();
		#endif
		
		#if MC_VER <= MC_1_7_10
		this.serialString = "biome:" + this.biome.biomeName;
		#else
		#if MC_VER <= MC_1_21_10
		ResourceLocation resourceLocation;
		#else
		Identifier resourceLocation;
		#endif
		
		#if MC_VER <= MC_1_12_2
		resourceLocation = biome.getRegistryName();
		#elif MC_VER <= MC_1_17_1
		resourceLocation = registryAccess.registryOrThrow(Registry.BIOME_REGISTRY).getKey(this.biome);
		#elif MC_VER <= MC_1_19_2
		resourceLocation = registryAccess.registryOrThrow(Registry.BIOME_REGISTRY).getKey(this.biome.value());
		#elif MC_VER <= MC_1_21_1
		resourceLocation = registryAccess.registryOrThrow(Registries.BIOME).getKey(this.biome.value());
		#else
		resourceLocation = registryAccess.lookupOrThrow(Registries.BIOME).getKey(this.biome.value());
		#endif
		
		
		if (resourceLocation == null)
		{
			String biomeName;
			#if MC_VER <= MC_1_17_1
			biomeName = this.biome.toString();
			#else
			biomeName = this.biome.value().toString();
			#endif
			
			LOGGER.warn("unable to serialize: " + biomeName);
			// shouldn't normally happen, but just in case
			this.serialString = "";
		}
		else
		{
			this.serialString = resourceLocation.getNamespace() + ":" + resourceLocation.getPath();
		}
		#endif
		
		return this.serialString;
	}
	
	public static IBiomeWrapper deserialize(String resourceLocationString, ILevelWrapper levelWrapper) throws IOException
	{
		// we need the final string for the concurrent hash map later
		final String finalResourceStateString = resourceLocationString;
		
		if (resourceLocationString.equals(EMPTY_BIOME_STRING))
		{
			if (!emptyStringWarningLogged)
			{
				emptyStringWarningLogged = true;
				LOGGER.warn("[" + EMPTY_BIOME_STRING + "] biome string deserialized. This may mean the level was null when a save was attempted, a file saving error, or a biome saving error. Future errors will not be logged.");
			}
			return EMPTY_WRAPPER;
		}
		else if (resourceLocationString.trim().isEmpty() || resourceLocationString.equals(""))
		{
			LOGGER.warn("Null biome string deserialized.");
			return EMPTY_WRAPPER;
		}
		
		if (WRAPPER_BY_RESOURCE_LOCATION.containsKey(finalResourceStateString))
		{
			return WRAPPER_BY_RESOURCE_LOCATION.get(finalResourceStateString);
		}
		
		
		
		// if no wrapper is found, default to the empty wrapper
		BiomeWrapper foundWrapper = EMPTY_WRAPPER;
		try
		{
			try
			{
				#if MC_VER > MC_1_12_2
				Level level = (Level) levelWrapper.getWrappedMcObject();
				net.minecraft.core.RegistryAccess registryAccess = level.registryAccess();
				#endif

				#if MC_VER <= MC_1_12_2
				BiomeDeserializeResult deserializeResult = deserializeBiome(resourceLocationString);
				#else
				BiomeDeserializeResult deserializeResult = deserializeBiome(resourceLocationString, registryAccess);
				#endif				
				
				
				if (!deserializeResult.success)
				{
					if (!brokenResourceLocationStrings.contains(resourceLocationString))
					{
						brokenResourceLocationStrings.add(resourceLocationString);
						LOGGER.warn("Unable to deserialize biome from string: [" + resourceLocationString + "]");
					}
					return EMPTY_WRAPPER;
				}
				
				
				foundWrapper = getBiomeWrapper(deserializeResult.biome, levelWrapper);
				return foundWrapper;
			}
			catch (Exception e)
			{
				throw new IOException("Failed to deserialize the string [" + finalResourceStateString + "] into a BiomeWrapper: " + e.getMessage(), e);
			}
		}
		finally
		{
			WRAPPER_BY_RESOURCE_LOCATION.putIfAbsent(finalResourceStateString, foundWrapper);
		}
	}
	
	#if MC_VER <= MC_1_12_2
	public static BiomeDeserializeResult deserializeBiome(String resourceLocationString) throws IOException
	#else
	public static BiomeDeserializeResult deserializeBiome(String resourceLocationString, net.minecraft.core.RegistryAccess registryAccess) throws IOException
	#endif
	{
		// parse the resource location
		int separatorIndex = resourceLocationString.indexOf(":");
		if (separatorIndex == -1)
		{
			throw new IOException("Unable to parse resource location string: [" + resourceLocationString + "].");
		}
		
		#if MC_VER <= MC_1_7_10
		String biomeName = resourceLocationString.substring(separatorIndex + 1);
		BiomeGenBase biome = BIOME_HANDLER.getBiomeByName(biomeName);
		boolean success = (biome != null);
		#else
		#if MC_VER < MC_1_21_11
		ResourceLocation resourceLocation;
		#else
		Identifier resourceLocation;
		#endif
		try
		{
			#if MC_VER <= MC_1_20_6
			resourceLocation = new ResourceLocation(resourceLocationString.substring(0, separatorIndex), resourceLocationString.substring(separatorIndex + 1));
			#elif MC_VER <= MC_1_21_10
			resourceLocation = ResourceLocation.fromNamespaceAndPath(resourceLocationString.substring(0, separatorIndex), resourceLocationString.substring(separatorIndex + 1));
			#else
			resourceLocation = Identifier.fromNamespaceAndPath(resourceLocationString.substring(0, separatorIndex), resourceLocationString.substring(separatorIndex + 1));
			#endif
		}
		catch (Exception e)
		{
			throw new IOException("No Resource Location found for the string: [" + resourceLocationString + "] Error: [" + e.getMessage() + "].");
		}
		
		
		boolean success;
		#if MC_VER <= MC_1_12_2
		Biome biome = Biome.REGISTRY.getObject(resourceLocation);
		success = (biome != null);
		#elif MC_VER <= MC_1_17_1
		Biome biome = registryAccess.registryOrThrow(Registry.BIOME_REGISTRY).get(resourceLocation);
		success = (biome != null);
		#elif MC_VER <= MC_1_19_2
		Biome unwrappedBiome = registryAccess.registryOrThrow(Registry.BIOME_REGISTRY).get(resourceLocation);
		success = (unwrappedBiome != null);
		Holder<Biome> biome = new Holder.Direct<>(unwrappedBiome);
		#elif MC_VER <= MC_1_21_1
		Biome unwrappedBiome = registryAccess.registryOrThrow(Registries.BIOME).get(resourceLocation);
		success = (unwrappedBiome != null);
		Holder<Biome> biome = new Holder.Direct<>(unwrappedBiome);
		#elif MC_VER <= MC_1_21_11
		Holder<Biome> biome;
		Optional<Holder.Reference<Biome>> optionalBiomeHolder = registryAccess.lookupOrThrow(Registries.BIOME).get(resourceLocation);
		if (optionalBiomeHolder.isPresent())
		{
			Biome unwrappedBiome = optionalBiomeHolder.get().value();
			success = (unwrappedBiome != null);
			biome = new Holder.Direct<>(unwrappedBiome);
		}
		else
		{
			success = false;
			biome = null;
		}
		#else
		Holder<Biome> biome;
		Optional<Holder.Reference<Biome>> optionalBiomeHolder = registryAccess.lookupOrThrow(Registries.BIOME).get(resourceLocation);
		if (optionalBiomeHolder.isPresent())
		{
			Biome unwrappedBiome = optionalBiomeHolder.get().value();
			success = (unwrappedBiome != null);
			biome = new Holder.Direct<>(unwrappedBiome, DataComponentMap.EMPTY);
		}
		else
		{
			success = false;
			biome = null;
		}
		#endif
		#endif
		
		return new BiomeDeserializeResult(success, biome);
	}
	
	//endregion
	
	
	
	//================//
	// helper classes //
	//================//
	//region
	
	public static class BiomeDeserializeResult
	{
		public final boolean success;
		
		#if MC_VER <= MC_1_7_10
		public final BiomeGenBase biome;
		#elif MC_VER < MC_1_18_2
		public final Biome biome;
		#else
		public final Holder<Biome> biome;
        #endif
		
		#if MC_VER <= MC_1_7_10
		public BiomeDeserializeResult(boolean success, BiomeGenBase biome)
		#elif MC_VER < MC_1_18_2
		public BiomeDeserializeResult(boolean success, Biome biome)
		#else
		public BiomeDeserializeResult(boolean success, Holder<Biome> biome)
		#endif
		{
			this.success = success;
			this.biome = biome;
		}
	}
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	@Override
	public boolean equals(Object obj)
	{
		if (this == obj)
		{
			return true;
		}
		else if (obj == null || this.getClass() != obj.getClass())
		{
			return false;
		}
		
		BiomeWrapper that = (BiomeWrapper) obj;
		// the serialized value is used so we can test the contents instead of the references
		return Objects.equals(this.getSerialString(), that.getSerialString());
	}
	
	@Override
	public int hashCode() { return this.hashCode; }
	
	@Override
	public String toString() { return this.getSerialString(); }
	
	//endregion
	
	
}
