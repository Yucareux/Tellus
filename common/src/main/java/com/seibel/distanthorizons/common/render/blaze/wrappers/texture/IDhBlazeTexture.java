package com.seibel.distanthorizons.common.render.blaze.wrappers.texture;

#if MC_VER <= MC_1_21_10
public interface IDhBlazeTexture {}
#else

#if MC_VER <= MC_26_2_0
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
#else
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
#endif

public interface IDhBlazeTexture
{
	
	GpuTextureView getTextureView();
	GpuSampler getTextureSampler();
	
}
#endif