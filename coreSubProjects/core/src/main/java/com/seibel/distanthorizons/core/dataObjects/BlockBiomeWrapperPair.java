package com.seibel.distanthorizons.core.dataObjects;

import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentHashMap;

/**
 * A pooled compound key between the biome and blockState. <br>
 * These objects are pooled since we will need this compound key
 * many times.
 * 
 * @see FullDataPointIdMap
 * @see IBlockStateWrapper
 * @see IBiomeWrapper
 */
public class BlockBiomeWrapperPair
{
	private static final IWrapperFactory WRAPPER_FACTORY = SingletonInjector.INSTANCE.get(IWrapperFactory.class);
	
	/** two levels are present so we don't need to use a key object */
	private static final ConcurrentHashMap<IBlockStateWrapper, ConcurrentHashMap<IBiomeWrapper, BlockBiomeWrapperPair>> CACHED_PAIR_BY_BIOME_BY_BLOCK = new ConcurrentHashMap<>();
	
	public final IBiomeWrapper biome;
	public final IBlockStateWrapper blockState;
	
	private int hashCode = 0;
	private boolean hashGenerated = false;
	private String serialString = null;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public static BlockBiomeWrapperPair get(IBlockStateWrapper blockState, IBiomeWrapper biome)
	{
		// nulls shouldn't happen, but just in case
		if (blockState == null)
		{
			throw new NullPointerException("Block state shouldn't be null.");
		}
		if (biome == null)
		{
			throw new NullPointerException("Biome wrapper shouldn't be null.");
		}
		
		
		// check for existing entry
		ConcurrentHashMap<IBiomeWrapper, BlockBiomeWrapperPair> pairByBiomeWrapper = CACHED_PAIR_BY_BIOME_BY_BLOCK.get(blockState);
		if (pairByBiomeWrapper != null)
		{
			BlockBiomeWrapperPair pair = pairByBiomeWrapper.get(biome);
			if (pair != null)
			{
				return pair;
			}
		}
		
		// Lazily create the inner map and new BlockBiomeWrapperPair
		return CACHED_PAIR_BY_BIOME_BY_BLOCK
				.computeIfAbsent(blockState, newBlockState -> new ConcurrentHashMap<>())
				.computeIfAbsent(biome, newBiome -> new BlockBiomeWrapperPair(biome, blockState));
	}
	private BlockBiomeWrapperPair(IBiomeWrapper biome, IBlockStateWrapper blockState)
	{
		this.biome = biome;
		this.blockState = blockState;
	}
	
	
	
	//===========//
	// overrides //
	//===========//
	
	/** 
	 * Reminder: this hash code won't always be unique, collisions can occur;
	 * because of that this hash shouldn't be the only unique identifier for this object.
	 */
	@Override
	public int hashCode()
	{
		// cache the hash code to improve speed
		if (!this.hashGenerated)
		{
			this.hashCode = generateHashCode(this);
			this.hashGenerated = true;
		}
		
		return this.hashCode;
	}
	private static int generateHashCode(BlockBiomeWrapperPair pair) { return generateHashCode(pair.biome, pair.blockState); }
	private static int generateHashCode(IBiomeWrapper biome, IBlockStateWrapper blockState)
	{
		final int prime = 31;
		
		int result = 1;
		// the biome and blockstate hashcode should be already calculated by the time
		// we get here, so this operation should be very fast
		result = prime * result + (biome == null ? 0 : biome.hashCode());
		result = prime * result + (blockState == null ? 0 : blockState.hashCode());
		return result;
	}
	
	@Override
	public boolean equals(Object otherObj)
	{
		if (otherObj == this)
		{
			return true;
		}
		
		if (!(otherObj instanceof BlockBiomeWrapperPair))
		{
			return false;
		}
		
		BlockBiomeWrapperPair other = (BlockBiomeWrapperPair) otherObj;
		return other.biome.getSerialString().equals(this.biome.getSerialString())
				&& other.blockState.getSerialString().equals(this.blockState.getSerialString());
	}
	
	@Override
	public String toString() { return this.serialize(); }
	
	
	
	//=============//
	// serializing //
	//=============//
	
	public String serialize() 
	{
		if (this.serialString == null)
		{
			this.serialString = this.biome.getSerialString() + FullDataPointIdMap.BLOCK_STATE_SEPARATOR_STRING + this.blockState.getSerialString();
		}
		
		return this.serialString;
	}
	
	
	
}