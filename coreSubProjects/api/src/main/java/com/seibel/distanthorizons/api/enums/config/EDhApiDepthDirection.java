package com.seibel.distanthorizons.api.enums.config;

/**
 * FORWARD_Z, <br>
 * REVERSE_Z, <br>
 *
 * @since API 7.2.0
 * @version 2026-09-19
 */
public enum EDhApiDepthDirection
{
	/**
	 * AKA Zero to One <br>
	 * MC 26.1.2 and older (OpenGL) = false (near = 0.0f, far = 1.0f) 
	 */
	FORWARD_Z(0.0f, 1.0f),
	/** 
	 * AKA One to Zero <br>
	 * MC 26.2.0 and newer (Vulkan/GL) = true (near = 1.0f, far = 0.0f) 
	 */
	REVERSE_Z(1.0f, 0.0f);
	
	
	public final float nearDepth;
	public final float farDepth;
	
	EDhApiDepthDirection(float nearDepth, float farDepth)
	{
		this.nearDepth = nearDepth;
		this.farDepth = farDepth;
	}
	
	
	
}
