package com.yucareux.tellus.world.data.biome;

import com.yucareux.tellus.worldgen.WorldProjection;
import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

/**
 * Optional version-specific biome-classification input.
 *
 * <p>Providers return {@code null} when their data is unavailable or when no
 * regional override applies, allowing the existing ESA + Köppen classifier to
 * remain the fallback.</p>
 */
public interface BiomeClassificationProvider {
   /**
    * Historical provider SPI retained for binary compatibility. New providers should also override the
    * projection-aware overload so centered worlds are classified at the correct geographic coordinate.
    */
   @Deprecated
   ResourceKey<Biome> findBiomeKey(int esaCode, String koppenCode, int blockX, int blockZ, double worldScale);

   default ResourceKey<Biome> findBiomeKey(
      int esaCode, String koppenCode, int blockX, int blockZ, WorldProjection projection
   ) {
      if (!projection.isCentered()) {
         return this.findBiomeKey(esaCode, koppenCode, blockX, blockZ, projection.worldScale());
      }

      WorldProjection global = WorldProjection.global(projection.worldScale());
      int globalBlockX = (int)Math.floor(global.lonToBlockX(projection.blockXToLon(blockX)));
      int globalBlockZ = (int)Math.floor(global.latToBlockZ(projection.blockZToLat(blockZ)));
      return this.findBiomeKey(esaCode, koppenCode, globalBlockX, globalBlockZ, projection.worldScale());
   }

   default Set<ResourceKey<Biome>> allBiomeKeys() {
      return Set.of();
   }
}
