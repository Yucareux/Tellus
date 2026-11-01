
package com.seibel.distanthorizons.forge17.modAccessor;

import com.seibel.distanthorizons.common.wrappers.modAccessor.IHodgePodgeCommonAccessor;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;

import com.mitchej123.hodgepodge.SimulationDistanceHelper;

public class HodgePodgeAccessor implements IHodgePodgeCommonAccessor
{
	@Override
	public String getModName() { return "HodgePodge"; }
	
	
	
	@Override
	public void preventChunkSimulation(World world, int x, int z)
	{ SimulationDistanceHelper.preventChunkSimulation(world, ChunkCoordIntPair.chunkXZ2Int(x, z), true); }
	
	@Override
	public void allowChunkSimulation(World world, int x, int z) 
    { SimulationDistanceHelper.preventChunkSimulation(world, ChunkCoordIntPair.chunkXZ2Int(x, z), false); }
	
	
	
}
