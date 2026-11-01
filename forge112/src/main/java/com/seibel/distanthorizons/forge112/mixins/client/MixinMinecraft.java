package com.seibel.distanthorizons.forge112.mixins.client;

import com.seibel.distanthorizons.common.commonMixins.DhUpdateScreenBase;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.jar.updater.SelfUpdater;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftSharedWrapper;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft
{
	
	@Inject(method = "init", at = @At("TAIL"))
	private void onInit(CallbackInfo ci)
	{
		// Singletons will be null if FML errored (for example other mod missing dependency)
		IMinecraftSharedWrapper mcShared = SingletonInjector.INSTANCE.get(IMinecraftSharedWrapper.class);
		if (mcShared == null)
		{
			return;
		}
		
		if (Config.Client.Advanced.AutoUpdater.enableAutoUpdater.get())
		{
			DhUpdateScreenBase.tryShowUpdateScreenAndRunAutoUpdateStartup(null);
		}
	}
	
	@Inject(method = "shutdownMinecraftApplet", at = @At("HEAD"))
	private void onShutdownMinecraftApplet(CallbackInfo ci)
	{
		if(Config.Client.Advanced.AutoUpdater.enableAutoUpdater.get())
		{
			SelfUpdater.onClose();
		}
	}
}
