package com.seibel.distanthorizons.coreapi.util;

public class TextureUtil
{
	/** measured in pixels */
	public static int TEXTURE_WIDTH_AND_HEIGHT = 16;
	
	/** (0,0) is the top left pixel. */
	public static int getPixelIndex(int u, int v) throws ArrayIndexOutOfBoundsException 
	{ 
		if (u < 0 || u > TEXTURE_WIDTH_AND_HEIGHT)
		{
			throw new ArrayIndexOutOfBoundsException("U ["+u+"] should be within the bounds [0] and ["+TEXTURE_WIDTH_AND_HEIGHT+"] (inclusive)");
		}
		if (v < 0 || v > TEXTURE_WIDTH_AND_HEIGHT)
		{
			throw new ArrayIndexOutOfBoundsException("V ["+v+"] should be within the bounds [0] and ["+TEXTURE_WIDTH_AND_HEIGHT+"] (inclusive)");
		}
		
		return (v * TEXTURE_WIDTH_AND_HEIGHT) + u; 
	}
	
	
	
}
