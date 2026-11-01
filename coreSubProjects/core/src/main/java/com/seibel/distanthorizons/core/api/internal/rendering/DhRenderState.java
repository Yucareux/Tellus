package com.seibel.distanthorizons.core.api.internal.rendering;

import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;

/**
 * Used to track the rendering state for the current frame.
 * 
 * @see ClientApi
 */
public class DhRenderState
{
	public DhMat4f mcModelViewMatrix = new DhMat4f();
	public DhMat4f mcProjectionMatrix = new DhMat4f();
	/** 
	 * percentage of time into the current client tick. <br><br>
	 * 
	 * Can be converted to a millisecond frametime 
	 * (IE time between frames in milliseconds) using the formula: <br>
	 * <code>
	 * (partialTickTime/20*1000)
	 * </code> <br>
	 * IE 60 FPS = 16.6 MS <br>
	 * 
	 * @link https://fpstoms.com/
	 * @see IMinecraftRenderWrapper#getPartialTickTime()
	 */
	public float partialTickTime = -1; 
	public IClientLevelWrapper clientLevelWrapper = null;
	
	/**
	 * This will generally be true if the player is: <br>
	 * - blinded <br>
	 * - under lava/water <br>
	 * <br>
	 * In those cases some rendering logic may need to be changed
	 * to look correct.
	 */
	public boolean vanillaFogEnabled = false;
	
	
	
	//========//
	// checks //
	//========//
	//region
	
	public String unableToRenderBecause()
	{
		String errorReasons = "";
		
		// the matrix may be the identity matrix or and old/incorrect matrix
		// but we did set it at least once before this
		if (this.mcModelViewMatrix == null)
		{
			errorReasons += "no MVM Matrix, ";
		}
		
		if (this.mcProjectionMatrix == null)
		{
			errorReasons += "no Projection Matrix, ";
		}
		
		if (this.partialTickTime == -1)
		{
			errorReasons += "no Frame Time, ";
		}
		
		if (this.clientLevelWrapper == null)
		{
			errorReasons += "no Level Wrapper, ";
		}
		
		return errorReasons;
	}
	
	public void canRenderOrThrow() throws IllegalStateException
	{
		String errorReasons = this.unableToRenderBecause();
		if (!errorReasons.isEmpty())
		{
			throw new IllegalStateException(errorReasons);
		}
	}
	
	//endregion
	
	
	
}
