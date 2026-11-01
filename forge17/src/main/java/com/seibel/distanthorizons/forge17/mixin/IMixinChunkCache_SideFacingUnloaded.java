package com.seibel.distanthorizons.forge17.mixin;

import net.minecraft.world.ChunkCache;
import net.minecraft.world.chunk.Chunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkCache.class)
public interface IMixinChunkCache_SideFacingUnloaded 
{
    @Accessor
    Chunk[][] getChunkArray();

    @Accessor
    int getChunkX();

    @Accessor
    int getChunkZ();
	
	
	
}
