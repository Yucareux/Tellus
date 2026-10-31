package com.yucareux.tellus.mixin;

import com.yucareux.tellus.world.realtime.TellusRealtimeState;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Thaws lake ice with the real-world temperature; vanilla's melting by block light is unchanged. */
@Mixin(IceBlock.class)
public abstract class IceBlockMeltMixin {
   @Shadow
   protected abstract void melt(BlockState state, Level level, BlockPos pos);

   @Inject(
      method = "randomTick(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V",
      at = @At("HEAD"),
      cancellable = true
   )
   private void tellus$thawLakeIce(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
      if (level.getChunkSource().getGenerator() instanceof EarthChunkGenerator generator
         && generator.settings().lakeFreezing()
         && TellusRealtimeState.canLakeIceMelt(pos)
         && generator.isLakeWater(pos.getX(), pos.getZ())) {
         this.melt(state, level, pos);
         ci.cancel();
      }
   }
}
