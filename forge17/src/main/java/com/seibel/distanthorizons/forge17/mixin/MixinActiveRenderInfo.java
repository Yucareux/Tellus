package com.seibel.distanthorizons.forge17.mixin;

import java.nio.FloatBuffer;

import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.seibel.distanthorizons.forge17.RenderHelper;

@Mixin(ActiveRenderInfo.class)
public class MixinActiveRenderInfo 
{
    @Shadow
    private static FloatBuffer modelview;
    @Shadow
    private static FloatBuffer projection;
	
	
	
    @Inject(method = "updateRenderInfo", at = @At(value = "TAIL"))
    private static void asini$onUpdateRenderInfo(EntityPlayer p_74583_0_, boolean p_74583_1_, CallbackInfo ci) 
    {
        RenderHelper.setProjectionMatrixFromBuffer(projection);
        RenderHelper.setModelViewMatrixFromBuffer(modelview);
    }
	
	
	
}
