package com.yucareux.tellus.world.data.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.world.data.resolve.ResolveBiome;
import com.yucareux.tellus.world.data.resolve.ResolveEcoregion;
import com.yucareux.tellus.world.data.resolve.ResolveRealm;
import com.yucareux.tellus.worldgen.EarthProjection;
import com.yucareux.tellus.worldgen.WorldProjection;
import net.minecraft.world.level.biome.Biomes;
import org.junit.jupiter.api.Test;

class ResolveBiomeClassificationTest {
   @Test
   void fabric262ServiceLoadsTheResolveProvider() {
      double worldScale = 1.0;
      WorldProjection projection = WorldProjection.global(worldScale);
      int blockX = (int)Math.round(projection.lonToBlockX(-122.3321));
      int blockZ = (int)Math.round(projection.latToBlockZ(47.6062));

      assertEquals(
         Biomes.OLD_GROWTH_SPRUCE_TAIGA,
         BiomeClassificationProviders.findBiomeKey(10, "Cfb", blockX, blockZ, projection)
      );
   }

   @Test
   void temperateConiferContextCorrectsPacificNorthwestForest() {
      ResolveEcoregion pugetLowland = ecoregion(
         364,
         "Puget lowland forests",
         ResolveBiome.TEMPERATE_CONIFER_FORESTS
      );

      assertEquals(
         Biomes.OLD_GROWTH_SPRUCE_TAIGA,
         ResolveBiomeClassification.findBiomeKey(10, "Cfb", pugetLowland)
      );
      assertEquals(
         Biomes.OLD_GROWTH_BIRCH_FOREST,
         BiomeClassification.findBiomeKey(10, "Cfb"),
         "The legacy ESA + Köppen result remains available as the fallback"
      );
   }

   @Test
   void usesExactKoppenBeforePrefixAndWildcardRules() {
      ResolveEcoregion desert = ecoregion(
         427,
         "Central Mexican matorral",
         ResolveBiome.DESERTS_XERIC_SHRUBLANDS
      );

      assertEquals(Biomes.DESERT, ResolveBiomeClassification.findBiomeKey(20, "BWh", desert));
      assertEquals(Biomes.BADLANDS, ResolveBiomeClassification.findBiomeKey(20, "BWk", desert));
      assertEquals(Biomes.SAVANNA, ResolveBiomeClassification.findBiomeKey(20, "BSh", desert));
      assertEquals(Biomes.WINDSWEPT_SAVANNA, ResolveBiomeClassification.findBiomeKey(20, "Csa", desert));
   }

   @Test
   void resolveRegionalClassChangesSimilarClimateAndLandCover() {
      ResolveEcoregion broadleaf = ecoregion(
         663,
         "English Lowlands beech forests",
         ResolveBiome.TEMPERATE_BROADLEAF_MIXED_FORESTS
      );
      ResolveEcoregion conifer = ecoregion(
         364,
         "Puget lowland forests",
         ResolveBiome.TEMPERATE_CONIFER_FORESTS
      );

      assertEquals(Biomes.FOREST, ResolveBiomeClassification.findBiomeKey(10, "Cfb", broadleaf));
      assertEquals(Biomes.OLD_GROWTH_SPRUCE_TAIGA, ResolveBiomeClassification.findBiomeKey(10, "Cfb", conifer));
   }

   @Test
   void preservesLocalEsaLandCoverInsideRegionalBiome() {
      ResolveEcoregion moistForest = ecoregion(
         473,
         "Japurá-Solimões-Negro moist forests",
         ResolveBiome.TROPICAL_MOIST_BROADLEAF_FORESTS
      );

      assertEquals(Biomes.JUNGLE, ResolveBiomeClassification.findBiomeKey(10, "Af", moistForest));
      assertEquals(Biomes.SPARSE_JUNGLE, ResolveBiomeClassification.findBiomeKey(20, "Af", moistForest));
      assertEquals(Biomes.PLAINS, ResolveBiomeClassification.findBiomeKey(30, "Af", moistForest));
      assertEquals(Biomes.SWAMP, ResolveBiomeClassification.findBiomeKey(90, "Af", moistForest));
   }

   @Test
   void distinguishesResolveRockAndIceFromUnknownCoverage() {
      ResolveEcoregion rockAndIce = ecoregion(0, "Rock and Ice", ResolveBiome.ROCK_AND_ICE);

      assertEquals(Biomes.STONY_PEAKS, ResolveBiomeClassification.findBiomeKey(60, "EF", rockAndIce));
      assertEquals(Biomes.FROZEN_PEAKS, ResolveBiomeClassification.findBiomeKey(70, "EF", rockAndIce));
      assertNull(ResolveBiomeClassification.findBiomeKey(60, "EF", ResolveEcoregion.UNKNOWN));
   }

   @Test
   void exposesEveryOverrideBiomeToTheBiomeSourceRegistry() {
      assertTrue(ResolveBiomeClassification.allBiomeKeys().contains(Biomes.OLD_GROWTH_SPRUCE_TAIGA));
      assertTrue(ResolveBiomeClassification.allBiomeKeys().contains(Biomes.MANGROVE_SWAMP));
      assertTrue(ResolveBiomeClassification.allBiomeKeys().contains(Biomes.SNOWY_PLAINS));
   }

   private static ResolveEcoregion ecoregion(int ecoId, String name, ResolveBiome biome) {
      return new ResolveEcoregion(
         ecoId,
         name,
         biome,
         biome.displayName(),
         ResolveRealm.NEARCTIC,
         "Nearctic",
         "CC-BY 4.0"
      );
   }
}
