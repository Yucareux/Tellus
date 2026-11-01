package com.seibel.distanthorizons.forge17.mixin;

import net.minecraft.world.chunk.Chunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.seibel.distanthorizons.forge17.ForgeClientProxy;

@Mixin(Chunk.class)
public class MixinChunk
{
    @Inject(method = "fillChunk", at = @At("RETURN"))
    private void distanthorizons$afterFillChunk(byte[] data, int hasStorageBits, int hasMsbStorageBits,
        boolean hasSkyLight, CallbackInfo ci) 
    { ForgeClientProxy.onClientChunkFilled((Chunk) (Object) this); }
	
	
	
}
