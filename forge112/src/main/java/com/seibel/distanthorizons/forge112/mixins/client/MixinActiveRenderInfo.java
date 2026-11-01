package com.seibel.distanthorizons.forge112.mixins.client;

import java.nio.FloatBuffer;

import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.seibel.distanthorizons.forge112.RenderHelper;

@Mixin(ActiveRenderInfo.class)
public class MixinActiveRenderInfo
{
	@Final @Shadow
	public static FloatBuffer MODELVIEW;
	@Final @Shadow
	public static FloatBuffer PROJECTION;
	
	
	
	@Inject(method = "updateRenderInfo(Lnet/minecraft/entity/Entity;Z)V", at = @At(value = "TAIL"))
	private static void updateRenderInfo(Entity entityplayerIn, boolean p_74583_1_, CallbackInfo ci)
	{
		RenderHelper.setProjectionMatrixFromBuffer(PROJECTION);
		RenderHelper.setModelViewMatrixFromBuffer(MODELVIEW);
	}
	
	
	
}
