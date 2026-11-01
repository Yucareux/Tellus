package com.seibel.distanthorizons.neoforge.mixins.client;

#if MC_VER <= MC_1_21_10
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Entity.class)
public class MixinSharedConstants
{ /* not present in older MC versions */ }
#else

import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.coreapi.ModInfo;
import net.minecraft.SharedConstants;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SharedConstants.class)
public abstract class MixinSharedConstants
{
	@Mutable
	@Shadow @Final public static boolean IS_RUNNING_IN_IDE;
	
	@Unique
	private static final DhLogger DH_LOGGER = new DhLoggerBuilder().name("SharedConstants").build();
	
	
	@Inject(method = "<clinit>", at = @At("TAIL"))
	private static void setIsRunningInIde(CallbackInfo ci) 
	{
		// setting IS_RUNNING_IN_IDE to true enables
		// additional validation on Mojang's side which
		// helps catch errors when developing for Blaze3D
		
		boolean brokenIrisVersionPresent = false;
		#if MC_VER <= MC_1_21_11
		#else
		try
		{
			// Iris has a bug for MC 26 and newer where it doesn't have
			// a "sampler1" bound, causing a renderer crash if
			// Blaze3D validation is enabled (which is enabled by if
			// IS_RUNNING_IN_IDE is true)
			ModInfo.class.getClassLoader().loadClass("net.irisshaders.iris.api.v0.IrisApi");
			brokenIrisVersionPresent = true;
		}
		catch (ClassNotFoundException ignore) { }
		#endif
		
		boolean wilderWildPresent = false;
		try
		{
			// Wilder Wild will refuse to launch if 
			// "IS_RUNNING_IN_IDE" is true
			// not sure why
			ModInfo.class.getClassLoader().loadClass("net.frozenblock.wilderwild.WilderWild");
			wilderWildPresent = true;
		}
		catch (ClassNotFoundException ignore) { }
		
		
		
		IS_RUNNING_IN_IDE = ModInfo.IS_DEV_BUILD 
			&& !brokenIrisVersionPresent
			&& !wilderWildPresent;
		DH_LOGGER.info("DH is setting Minecraft's SharedConstants.IS_RUNNING_IN_IDE to ["+IS_RUNNING_IN_IDE+"]");
	}
	
}
#endif
