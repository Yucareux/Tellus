package com.seibel.distanthorizons.forge17;

/**
 * Simple flags class with no dependencies, safe to load from any thread
 * including the FML splash thread.
 */
public class MixinFlags 
{
	/** will be set to true if the mixin is applied */
    public static volatile boolean framebufferMixinEnabled = false;
}
