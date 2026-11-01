package com.seibel.distanthorizons.common.backports;

#if MC_VER <= MC_1_7_10
import net.minecraft.world.ChunkPosition;
import org.joml.Vector3i;

public class BlockPos extends Vector3i
{
	
	public BlockPos()
	{
		super();
	}
	
	public BlockPos(int x, int y, int z)
	{
		super(x, y, z);
	}
	
	public BlockPos(ChunkPosition chunkPosition)
	{
		super(chunkPosition.chunkPosX, chunkPosition.chunkPosY, chunkPosition.chunkPosZ);
	}
	
	public int getX()
	{
		return this.x;
	}
	
	public int getY()
	{
		return this.y;
	}
	
	public int getZ()
	{
		return this.z;
	}
	
}
#endif