package com.yucareux.tellus.world.data.biome;

import com.yucareux.tellus.world.data.resolve.ResolveEcoregion;
import com.yucareux.tellus.world.data.resolve.TellusResolveSource;
import com.yucareux.tellus.worldgen.WorldProjection;
import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

/** Activation point for the bundled RESOLVE classifier. */
public final class ResolveBiomeClassificationProvider implements BiomeClassificationProvider {
   private final TellusResolveSource resolveSource = TellusResolveSource.shared();

   @Override
   @Deprecated
   public ResourceKey<Biome> findBiomeKey(
      int esaCode, String koppenCode, int blockX, int blockZ, double worldScale
   ) {
      return this.findBiomeKey(
         esaCode, koppenCode, blockX, blockZ, WorldProjection.global(worldScale)
      );
   }

   @Override
   public ResourceKey<Biome> findBiomeKey(
      int esaCode,
      String koppenCode,
      int blockX,
      int blockZ,
      WorldProjection projection
   ) {
      ResolveEcoregion ecoregion = this.resolveSource.sampleEcoregion(blockX, blockZ, projection);
      return ResolveBiomeClassification.findBiomeKey(esaCode, koppenCode, ecoregion);
   }

   @Override
   public Set<ResourceKey<Biome>> allBiomeKeys() {
      return ResolveBiomeClassification.allBiomeKeys();
   }
}
