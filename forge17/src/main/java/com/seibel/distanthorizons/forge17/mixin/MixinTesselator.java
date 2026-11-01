package com.seibel.distanthorizons.forge17.mixin;

import net.minecraft.client.renderer.Tessellator;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.seibel.distanthorizons.forge17.RenderHelper;

@Mixin(Tessellator.class)
public class MixinTesselator 
{
    @Inject(method = "draw", at = @At(value = "HEAD"))
    void drawMixin(CallbackInfoReturnable<Integer> cir) 
    { RenderHelper.UnbindAfterTesselatorDraw(); }
	
	
	
}
