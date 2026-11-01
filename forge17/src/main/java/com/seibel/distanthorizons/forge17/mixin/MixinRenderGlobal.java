package com.seibel.distanthorizons.forge17.mixin;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.EntityLivingBase;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.seibel.distanthorizons.forge17.RenderHelper;

@Mixin(RenderGlobal.class)
public class MixinRenderGlobal 
{
	@Inject(method = "sortAndRender", at = @At("HEAD"))
	void renderLods(
		EntityLivingBase p_72719_1_, int renderPass, double p_72719_3_,
		CallbackInfoReturnable<Integer> cir)
	{
		// Solid render pass
		if (renderPass == 0)
		{
			RenderHelper.renderLods();
		}
		
		// Translucent render pass
		if (renderPass == 1)
		{
			RenderHelper.beforeWater();
		}
	}
	
	@Inject(method = "sortAndRender", at = @At("TAIL"))
	void renderLodsFade(
		EntityLivingBase p_72719_1_, int renderPass, double p_72719_3_,
		CallbackInfoReturnable<Integer> cir)
	{
		// Solid render pass
		if (renderPass == 0)
		{
			RenderHelper.renderFade(false);
		}
		
		// Translucent render pass
		if (renderPass == 1)
		{
			RenderHelper.renderFade(true);
		}
	}
	
	@Inject(
		method = "sortAndRender",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/EntityRenderer;enableLightmap(D)V",
			shift = At.Shift.AFTER))
    void renderDeferredLodsDuringTranslucentSetup(
	    EntityLivingBase p_72719_1_, int renderPass, double p_72719_3_,
	    CallbackInfoReturnable<Integer> cir)
    {
	    // Translucent render pass
	    if (renderPass == 1)
	    {
		    RenderHelper.renderDeferredLods();
	    }
    }

}
