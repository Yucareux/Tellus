package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.HighYPackedCoordinateProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(Level.class)
public abstract class LevelHighYPackedCoordinateMixin {
   /**
    * @author Tellus
    * @reason Prevent commands and gameplay from entering coordinates that the dense global profile cannot encode.
    */
   @Overwrite
   private static boolean isInWorldBoundsHorizontal(BlockPos pos) {
      if (HighYPackedCoordinateProfile.isEnabled()) {
         return HighYPackedCoordinateProfile.containsHorizontal(pos.getX(), pos.getZ());
      }

      return pos.getX() >= -30_000_000 && pos.getZ() >= -30_000_000 && pos.getX() < 30_000_000 && pos.getZ() < 30_000_000;
   }

   /**
    * @author Tellus
    * @reason Prevent commands from creating positions outside the dense profile's packed Y safety range.
    */
   @Overwrite
   private static boolean isOutsideSpawnableHeight(int y) {
      if (HighYPackedCoordinateProfile.isEnabled()) {
         return y < HighYPackedCoordinateProfile.Y_MIN || y > HighYPackedCoordinateProfile.Y_MAX;
      }

      return y < -20_000_000 || y >= 20_000_000;
   }
}
