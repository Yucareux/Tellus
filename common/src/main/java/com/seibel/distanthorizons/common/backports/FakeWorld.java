package com.seibel.distanthorizons.common.backports;

#if MC_VER <= MC_1_7_10

import com.seibel.distanthorizons.common.wrappers.block.ClientBlockStateColorCache;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * Used by {@link ClientBlockStateColorCache}
 * in order to handle some world related operations.
 */
public class FakeWorld implements IBlockAccess
{
	private IBlockAccess realWorld;
	private BiomeGenBase biome;
	
	private int blockX;
	private int blockY;
	private int blockZ;
	
	private FakeBlockState blockState;
	
	
	
	//========//
	// update //
	//========//
	//region
	
	public void update(
		IBlockAccess realWorld, BiomeGenBase biome, 
		int blockX, int blockY, int blockZ, 
		FakeBlockState blockState)
	{
		this.realWorld = realWorld;
		this.biome = biome;
		
		this.blockX = blockX;
		this.blockY = blockY;
		this.blockZ = blockZ;
		
		this.blockState = blockState;
		if (this.biome == null)
		{
			this.biome = BiomeGenBase.plains; // Fallback to prevent null pointers
		}
	}
	
	//endregion
	
	
	
	//===========//
	// overrides //
	//===========//
	//region
	
	@Override
	public Block getBlock(int x, int y, int z)
	{
		if (x == this.blockX 
			&& y == this.blockY 
			&& z == this.blockZ)
		{
			return this.blockState.block;
		}
		
		return Blocks.air;
	}

	@Override
	public TileEntity getTileEntity(int x, int y, int z) { return null; }

	@Override
	public int getLightBrightnessForSkyBlocks(int x, int y, int z, int min) { return 0; }

	@Override
	public int getBlockMetadata(int x, int y, int z)
	{
		if (x == this.blockX 
			&& y == this.blockY 
			&& z == this.blockZ)
		{
			return this.blockState.meta;
		}
		else if (this.blockState.block instanceof BlockDoublePlant)
		{
			/*
			 * Double tall grass on 1.7.10 has
			 * top block = meta 8
			 * bottom block = meta 2
			 * To get the color of the top block, BlockDoublePlant uses the meta of the bottom block.
			 * Since this is a fake world we can't get the real meta for the bottom block.
			 * We default to 2 since this applies a biome tint, which is what we want in most cases.
			 */
			return 2;
		}
		
		return 0;
	}

	@Override
	public int isBlockProvidingPowerTo(int x, int y, int z, int directionIn) { return 0; }

	@Override
	public boolean isAirBlock(int x, int y, int z) { return false; }

	@Override
	public BiomeGenBase getBiomeGenForCoords(int x, int z)
	{
		// Not 100% accurate since grass samples the surrounding blocks, but good enough for now
		return this.biome;
	}

	@Override
	public int getHeight() { return this.realWorld.getHeight(); }

	@Override
	public boolean extendedLevelsInChunkCache() { return this.realWorld.extendedLevelsInChunkCache(); }

	@Override
	public boolean isSideSolid(int x, int y, int z, ForgeDirection side, boolean _default) { throw new UnsupportedOperationException("isSideSolid() isn't implemented"); }
	
	//endregion
	
	
	
}

#endif
