package com.seibel.distanthorizons.common.backports;

#if MC_VER <= MC_1_7_10
import com.seibel.distanthorizons.common.backports.BlockPos;

/**
 * Recreation of MC's 1.12+ ChunkPos object
 * so 1.7.10 can use similar code to 1.12.
 */
public class ChunkPos 
{
	#if MC_VER <= MC_1_7_10
    private static final long INT_MASK = (1L << Integer.SIZE) - 1;
	
    public final int x;
    public final int z;
	
	
	
	//==============//
	// constructors //
	//==============//
	//region
	
    public ChunkPos(int x, int z) 
    {
        this.x = x;
        this.z = z;
    }
	
	public ChunkPos(BlockPos pos)
	{
		this.x = pos.getX() >> 4;
		this.z = pos.getZ() >> 4;
	}
	
	public ChunkPos(long pos)
	{
		this.x = (int) pos;
		this.z = (int) (pos >> 32);
	}
	
	//endregion
	
	
	
	//=========//
	// getters //
	//=========//
	//region
	
	public static int getPackedX(long pos) { return (int) pos; }
	public static int getPackedZ(long pos) { return (int) (pos >>> 32); }
	
	public long toLong() { return toLong(this.x, this.z); }
	public static long toLong(int x, int z) { return (long) x & INT_MASK | ((long) z & INT_MASK) << 32; }
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	@Override
	public int hashCode()
	{
		final int i = 1664525 * this.x + 1013904223;
		final int j = 1664525 * (this.z ^ -559038737) + 1013904223;
		return i ^ j;
	}
	
	@Override
	public boolean equals(Object object)
	{
		if (this == object)
		{
			return true;
		}
		else if (object instanceof ChunkPos)
		{
			ChunkPos pos = (ChunkPos)object;
			return this.x == pos.x 
				&& this.z == pos.z;
		}
		else
		{
			return false;
		}
	}
	
	//endregion
	
	
	
    #endif
}
#endif