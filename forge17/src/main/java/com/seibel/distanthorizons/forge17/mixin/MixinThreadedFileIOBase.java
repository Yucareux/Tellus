package com.seibel.distanthorizons.forge17.mixin;

import net.minecraft.world.storage.ThreadedFileIOBase;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ThreadedFileIOBase.class)
public class MixinThreadedFileIOBase 
{
    @Redirect(method = "processQueue", at = @At(value = "INVOKE", target = "Ljava/lang/Thread;sleep(J)V", remap = false))
    private void distanthorizons$reduceSleep(long millis) throws InterruptedException 
    {
		// reduce the timeout so we can generate chunks faster
		
        // 0ms between chunks, 5ms when idle
        Thread.sleep(millis == 25L ? 5L : 0L);
    }
	
}
