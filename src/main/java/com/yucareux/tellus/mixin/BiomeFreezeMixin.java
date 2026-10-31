package com.yucareux.tellus.mixin;

import com.yucareux.tellus.world.realtime.TellusRealtimeState;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({Biome.class})
public class BiomeFreezeMixin {
   @Inject(
      method = {"shouldFreeze(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Z)Z"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void tellus$freezeOnlyLakes(LevelReader level, BlockPos pos, boolean mustBeAtEdge, CallbackInfoReturnable<Boolean> cir) {
      if (level instanceof ServerLevel serverLevel && serverLevel.getChunkSource().getGenerator() instanceof EarthChunkGenerator generator) {
         // Tellus answers in full: its lakes use the temperate river biome, whose temperature vanilla would never
         // let freeze. The other checks are vanilla's: still water, dim block light, freezing from the edge inward.
         cir.setReturnValue(
            generator.settings().lakeFreezing()
               && TellusRealtimeState.canLakeFreeze(pos)
               && level.getFluidState(pos).is(Fluids.WATER)
               && level.getBlockState(pos).getBlock() instanceof LiquidBlock
               && (!mustBeAtEdge
                  || !level.isWaterAt(pos.west()) || !level.isWaterAt(pos.east())
                  || !level.isWaterAt(pos.north()) || !level.isWaterAt(pos.south()))
               && level.getBrightness(LightLayer.BLOCK, pos) < 10
               && !level.getBiome(pos).is(BiomeTags.IS_OCEAN)
               && generator.isLakeWater(pos.getX(), pos.getZ())
         );
      }
   }
}
