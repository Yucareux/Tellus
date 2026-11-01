package com.seibel.distanthorizons.common.wrappers.misc;

import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IMutableBlockPosWrapper;
#if MC_VER <= MC_1_7_10
import com.seibel.distanthorizons.common.backports.BlockPos;
#elif MC_VER <= MC_1_12_2
import net.minecraft.util.math.BlockPos;
#else
import net.minecraft.core.BlockPos;
#endif

public class MutableBlockPosWrapper implements IMutableBlockPosWrapper
{
	#if MC_VER <= MC_1_7_10
	public final BlockPos pos;
	#else
	public final BlockPos.MutableBlockPos pos;
	#endif
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public MutableBlockPosWrapper()
	{
		#if MC_VER <= MC_1_7_10
		this.pos = new BlockPos();
		#else
		this.pos = new BlockPos.MutableBlockPos(); 
		#endif
	}
	
	//endregion
	
	
	
	//===========//
	// overrides //
	//===========//
	//region
	
	@Override 
	public Object getWrappedMcObject() { return this.pos; }
	
	//endregion
	
	
	
}
