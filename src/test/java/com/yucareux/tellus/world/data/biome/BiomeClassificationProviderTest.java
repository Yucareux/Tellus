package com.yucareux.tellus.world.data.biome;

import com.yucareux.tellus.worldgen.WorldProjection;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings("deprecation")
class BiomeClassificationProviderTest {
   @Test
   void legacyProviderReceivesEquivalentGlobalCoordinatesForCenteredWorld() {
      AtomicInteger receivedX = new AtomicInteger();
      AtomicInteger receivedZ = new AtomicInteger();
      BiomeClassificationProvider legacyProvider = new BiomeClassificationProvider() {
         @Override
         public ResourceKey<Biome> findBiomeKey(
            int esaCode, String koppenCode, int blockX, int blockZ, double worldScale
         ) {
            receivedX.set(blockX);
            receivedZ.set(blockZ);
            return null;
         }
      };

      double latitude = 47.6062;
      double longitude = -122.3321;
      WorldProjection centered = WorldProjection.centeredOn(30.0, 37.7459, -119.5332);
      WorldProjection global = WorldProjection.global(30.0);
      int centeredX = (int)Math.floor(centered.lonToBlockX(longitude));
      int centeredZ = (int)Math.floor(centered.latToBlockZ(latitude));

      legacyProvider.findBiomeKey(10, "Cfb", centeredX, centeredZ, centered);

      assertEquals((int)Math.floor(global.lonToBlockX(centered.blockXToLon(centeredX))), receivedX.get());
      assertEquals((int)Math.floor(global.latToBlockZ(centered.blockZToLat(centeredZ))), receivedZ.get());
   }
}
