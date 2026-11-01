package com.seibel.distanthorizons.forge17.mixin;

import net.minecraft.block.Block;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraftforge.common.util.ForgeDirection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;

@Mixin(value = Block.class)
public class MixinBlock_SideFacingUnloadedChunk 
{
	/**
	 * This is necessary for under-water chunks to look
	 * correct on the faded LODs. <br><br>
	 * 
	 * If not included the water chunks will render walls when moving
	 * which looks bad.
	 */
    @ModifyReturnValue(method = "shouldSideBeRendered", at = @At("TAIL"))
    private boolean shouldSideBeRenderedFix(
		boolean original, 
	    @Local(argsOnly = true) IBlockAccess blockAccess,
        @Local(argsOnly = true, ordinal = 0) int x, @Local(argsOnly = true, ordinal = 2) int z,
        @Local(argsOnly = true, ordinal = 3) int side) 
    {
        // Skip invalid orientations, and early exit for UP/DOWN
        if (side < 2 
	        || side >= 6) 
		{
            return original;
        }
        ForgeDirection direction = ForgeDirection.VALID_DIRECTIONS[side];

        // Subtract to get original block pos, our position is the block the new side faces already!
        int originalChunkX = (x - direction.offsetX) >> 4;
        int originalChunkZ = (z - direction.offsetZ) >> 4;

        x >>= 4;
        z >>= 4;

        if (originalChunkX == x 
	        && originalChunkZ == z)
		{
            return original;
        }

        if (blockAccess instanceof ChunkCache) 
		{
            IMixinChunkCache_SideFacingUnloaded cache = (IMixinChunkCache_SideFacingUnloaded) blockAccess;
            Chunk[][] chunks = cache.getChunkArray();

            x -= cache.getChunkX();
            z -= cache.getChunkZ();

            if (x < 0 || x >= chunks.length 
	            || z < 0 || z >= chunks[x].length) 
			{
                return false;
            } 
			else if (chunks[x][z] instanceof EmptyChunk) 
			{
                return false;
            }
        }
		
        return original;
    }
	
	
	
}
