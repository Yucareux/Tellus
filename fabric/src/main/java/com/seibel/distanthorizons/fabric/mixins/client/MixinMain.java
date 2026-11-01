package com.seibel.distanthorizons.fabric.mixins.client;

import com.seibel.distanthorizons.common.commonMixins.MixinMainCommon;
import net.minecraft.client.main.Main;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Main.class)
public class MixinMain
{
	@Inject(method = "Lnet/minecraft/client/main/Main;main([Ljava/lang/String;)V", at = @At("HEAD") )
	private static void start(final String[] args, CallbackInfo ci)
	{
		#if INJECT_RENDER_DOC && DEV_BUILD
		MixinMainCommon.onMainStart();
		#endif
	}
	
}
