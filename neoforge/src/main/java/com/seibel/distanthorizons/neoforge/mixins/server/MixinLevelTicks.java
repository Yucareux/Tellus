package com.seibel.distanthorizons.neoforge.mixins.server;

#if MC_VER < MC_1_21_4

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Entity.class)
public class MixinLevelTicks<T>
{
	// dummy mixin to make the loader happy
}

#else

import com.seibel.distanthorizons.api.DhApi;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelTicks.class) // available in 1.18.2+, but only needed in 1.21.4+
public class MixinLevelTicks<T>
{
	@Inject(method = "schedule", at = @At(value = "HEAD"), cancellable = true)
	private void onChunkSave(ScheduledTick<T> tick, CallbackInfo ci)
	{
		// In MC 1.21.4 an error check was added to log attempting to schedule ticks for unloaded chunks
		// this caused a lot of unnecessary errors when generating sand (FallingBlock.class).
		if (DhApi.isDhThread())
		{
			ci.cancel();
		}
	}
	
}

#endif
