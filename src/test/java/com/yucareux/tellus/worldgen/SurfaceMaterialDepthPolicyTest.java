package com.yucareux.tellus.worldgen;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceMaterialDepthPolicyTest {
   private static final long WORLD_SEED = 8675309L;

   @Test
   void naturalSurfaceDepthVariesAroundVanillaLikeNominalDepth() {
      Set<Integer> depths = new HashSet<>();
      for (int z = -96; z <= 96; z++) {
         for (int x = -96; x <= 96; x++) {
            int depth = SurfaceMaterialDepthPolicy.naturalDepth(4, x, z, WORLD_SEED);
            assertTrue(depth >= 3 && depth <= 5);
            depths.add(depth);
         }
      }

      assertEquals(Set.of(3, 4, 5), depths);
      assertEquals(
         SurfaceMaterialDepthPolicy.naturalDepth(4, -73, 29, WORLD_SEED),
         SurfaceMaterialDepthPolicy.naturalDepth(4, -73, 29, WORLD_SEED)
      );
   }

   @Test
   void fullSnowfieldsVaryAndCanBeDeeperThanOrdinarySurfaceMaterial() {
      Set<Integer> depths = new HashSet<>();
      for (int z = -128; z <= 128; z++) {
         for (int x = -128; x <= 128; x++) {
            int depth = SurfaceMaterialDepthPolicy.snowColumn(x, z, WORLD_SEED, true).depth();
            assertTrue(
               depth >= SurfaceMaterialDepthPolicy.MIN_FULL_SNOW_DEPTH
                  && depth <= SurfaceMaterialDepthPolicy.MAX_FULL_SNOW_DEPTH
            );
            depths.add(depth);
         }
      }

      assertTrue(depths.size() >= 4);
      assertTrue(depths.stream().anyMatch(depth -> depth > 5));
   }

   @Test
   void partialSlopeCoverageIsAlwaysExactlyOneSnowBlock() {
      boolean foundNormalSnow = false;
      boolean foundPowderSnow = false;
      for (int z = -96; z <= 96; z++) {
         for (int x = -96; x <= 96; x++) {
            SurfaceMaterialDepthPolicy.SnowColumn column = SurfaceMaterialDepthPolicy.snowColumn(
               x, z, WORLD_SEED, false
            );
            assertEquals(1, column.depth());
            foundPowderSnow |= column.powderSnow();
            foundNormalSnow |= !column.powderSnow();
         }
      }

      assertTrue(foundNormalSnow);
      assertTrue(foundPowderSnow);
   }

   @Test
   void powderSnowUsesSeededIrregularPatchesInsteadOfAlternatingColumns() {
      int powderColumns = 0;
      int normalColumns = 0;
      int matchingHorizontalNeighbors = 0;
      int horizontalNeighborPairs = 0;
      boolean seedChangesDistribution = false;
      for (int z = 0; z < 128; z++) {
         for (int x = 0; x < 128; x++) {
            boolean powder = SurfaceMaterialDepthPolicy.snowColumn(x, z, WORLD_SEED, true).powderSnow();
            powderColumns += powder ? 1 : 0;
            normalColumns += powder ? 0 : 1;
            seedChangesDistribution |= powder
               != SurfaceMaterialDepthPolicy.snowColumn(x, z, WORLD_SEED + 1L, true).powderSnow();
            if (x + 1 < 128) {
               boolean eastPowder = SurfaceMaterialDepthPolicy.snowColumn(x + 1, z, WORLD_SEED, true).powderSnow();
               matchingHorizontalNeighbors += powder == eastPowder ? 1 : 0;
               horizontalNeighborPairs++;
            }
         }
      }

      assertTrue(powderColumns > 0);
      assertTrue(normalColumns > 0);
      assertTrue(seedChangesDistribution);
      assertTrue((double)matchingHorizontalNeighbors / (double)horizontalNeighborPairs > 0.85);
      double powderRatio = (double)powderColumns / (double)(powderColumns + normalColumns);
      assertTrue(powderRatio >= 0.05 && powderRatio <= 0.35);
   }
}
