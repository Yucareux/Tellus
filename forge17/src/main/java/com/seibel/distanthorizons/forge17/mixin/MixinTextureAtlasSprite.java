package com.seibel.distanthorizons.forge17.mixin;

import java.awt.image.BufferedImage;
import java.util.List;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.data.AnimationMetadataSection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.seibel.distanthorizons.common.wrappers.interfaces.IMixinTextureAtlasSprite;

@Mixin(TextureAtlasSprite.class)
public class MixinTextureAtlasSprite implements IMixinTextureAtlasSprite 
{
    @Shadow
    protected List<int[][]> framesTextureData;
	
	/** contains the individual pixel data in ARGB format */
    @Unique
    private int[] distanthorizons$spriteData;
	
	
	
	//=====================//
	// getters and setters //
	//=====================//
	//region
	
	@Inject(method = "loadSprite", at = @At("RETURN"))
	private void injectLoadSprite(BufferedImage[] bufferedImages, AnimationMetadataSection p_147964_2_, boolean p_147964_3_, CallbackInfo ci)
	{ this.distanthorizons$setSpriteData(); }
	
	public void distanthorizons$setSpriteData()
	{
		if (this.framesTextureData.isEmpty())
		{
			return;
		}
		
		int[][] frameData = this.framesTextureData.get(0); 
		if (frameData == null)
		{
			return;
		} 
		
		int[] data = frameData[0];
		if (data == null)
		{
			return;
		}
		
		this.distanthorizons$spriteData = data.clone();
	}
	
	@Override 
	public int[] distanthorizons$getSpriteData() { return this.distanthorizons$spriteData; }
	
	//endregion
	
	
	
}
