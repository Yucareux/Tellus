package com.seibel.distanthorizons.common.wrappers.interfaces;

/**
 * Only needed for MC 1.7.10
 */
public interface IMixinTextureAtlasSprite 
{
	/** packed in ARGB format */
    int[] distanthorizons$getSpriteData();
	
	/** set from the base texture */
    void distanthorizons$setSpriteData();
	
}
