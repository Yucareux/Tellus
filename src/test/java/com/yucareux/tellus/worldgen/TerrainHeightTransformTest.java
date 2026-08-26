package com.yucareux.tellus.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainHeightTransformTest {
   private static final double ONE_TO_ONE = 1.0;

   @Test
   void increaseHeightKeepsMatterhornExactAtItsMercatorScale() {
      double latitude = 45.9763;
      double expected = 4_478.0 * EarthProjection.heightScaleCorrectionAtLatitude(latitude);
      double actual = TerrainHeightTransform.scaledElevationBlocksAtLatitude(
         4_478.0, latitude, ONE_TO_ONE, 1.0, 1.0, true, true
      );

      assertEquals(expected, actual, 1.0E-9);
      assertTrue(actual > 6_400.0 && actual < 6_500.0);
   }

   @Test
   void increaseHeightLeavesEverestEffectivelyExact() {
      double exact = 8_848.0 * EarthProjection.heightScaleCorrectionAtLatitude(27.9881);
      double actual = TerrainHeightTransform.scaledElevationBlocksAtLatitude(
         8_848.0, 27.9881, ONE_TO_ONE, 1.0, 1.0, true, true
      );

      assertEquals(10_019.1, actual, 0.2);
      assertTrue(exact - actual < 1.0);
   }

   @Test
   void increaseHeightBoundsHighLatitudeMountainScaleWithoutFlatClipping() {
      double denali = TerrainHeightTransform.scaledElevationBlocksAtLatitude(
         6_190.0, 63.0695, ONE_TO_ONE, 1.0, 1.0, true, true
      );
      double k2 = TerrainHeightTransform.scaledElevationBlocksAtLatitude(
         8_611.0, 35.8808, ONE_TO_ONE, 1.0, 1.0, true, true
      );

      assertEquals(9_904.0, denali, 0.1);
      assertEquals(10_222.4, k2, 0.2);
      assertTrue(k2 < TerrainHeightTransform.EXPERIMENTAL_LAND_SOFT_CEILING_BLOCKS);
   }

   @Test
   void latitudeCapRelaxesAtLargerWorldScales() {
      double exact = EarthProjection.heightScaleCorrectionAtLatitude(63.0695);
      assertEquals(
         TerrainHeightTransform.EXPERIMENTAL_LATITUDE_CORRECTION_CAP_AT_SCALE_ONE,
         TerrainHeightTransform.heightScaleCorrectionAtLatitude(63.0695, 1.0, true, true),
         1.0E-12
      );
      assertEquals(exact, TerrainHeightTransform.heightScaleCorrectionAtLatitude(63.0695, 1.5, true, true), 1.0E-12);
   }

   @Test
   void oceanCompressionPreservesShallowsAndBoundsAbyssalDepth() {
      double shallow = TerrainHeightTransform.scaledElevationBlocksAtLatitude(
         -100.0, 0.0, ONE_TO_ONE, 1.0, 1.0, true, true
      );
      double deep = TerrainHeightTransform.scaledElevationBlocksAtLatitude(
         -11_034.0, 0.0, ONE_TO_ONE, 1.0, 1.0, true, true
      );

      assertEquals(-97.8, shallow, 0.2);
      assertTrue(deep >= -TerrainHeightTransform.EXPERIMENTAL_OCEAN_DEPTH_LIMIT_BLOCKS);
      assertEquals(-TerrainHeightTransform.EXPERIMENTAL_OCEAN_DEPTH_LIMIT_BLOCKS, deep, 1.0E-9);
   }

   @Test
   void normalWorldsRetainUnboundedExactMercatorFormula() {
      double actual = TerrainHeightTransform.scaledElevationBlocksAtLatitude(
         6_190.0, 63.0695, ONE_TO_ONE, 2.0, 0.5, false, true
      );
      double expected = 6_190.0 * 2.0 * EarthProjection.heightScaleCorrectionAtLatitude(63.0695);
      assertEquals(expected, actual, 1.0E-9);
   }

   @Test
   void disabledAutomaticScalingUsesUniformConfiguredHeightScale() {
      double actual = TerrainHeightTransform.scaledElevationBlocksAtLatitude(
         6_190.0, 63.0695, ONE_TO_ONE, 2.0, 0.5, false, false
      );

      assertEquals(12_380.0, actual, 1.0E-9);
      assertEquals(1.0, TerrainHeightTransform.heightScaleCorrectionAtLatitude(80.0, ONE_TO_ONE, true, false));
   }

   @Test
   void disabledAutomaticScalingUsesExpandedDeepOceanRange() {
      double deep = TerrainHeightTransform.scaledElevationBlocksAtLatitude(
         -11_034.0, 0.0, ONE_TO_ONE, 1.0, 1.0, true, false
      );

      assertTrue(deep < -1_500.0);
      assertEquals(-TerrainHeightTransform.EXPERIMENTAL_DEEP_OCEAN_DEPTH_LIMIT_BLOCKS, deep, 0.01);
   }

   @Test
   void blockAndLatitudeHeightPathsRemainEquivalent() {
      double[] latitudes = {-80.0, -63.0695, -27.9881, 0.0, 27.9881, 63.0695, 80.0};
      double[] elevations = {-11_034.0, -100.0, 0.0, 4_478.0, 8_848.0};
      WorldProjection projection = WorldProjection.global(ONE_TO_ONE);
      for (boolean automaticHeightScaling : new boolean[]{false, true}) {
         for (boolean experimental : new boolean[]{false, true}) {
            for (double latitude : latitudes) {
               double blockZ = projection.latToBlockZ(latitude);
               for (double elevation : elevations) {
                  assertEquals(
                     TerrainHeightTransform.scaledElevationBlocksAtLatitude(
                        elevation, latitude, ONE_TO_ONE, 1.0, 1.0, experimental, automaticHeightScaling
                     ),
                     TerrainHeightTransform.scaledElevationBlocks(
                        elevation, blockZ, projection, 1.0, 1.0, experimental, automaticHeightScaling
                     ),
                     2.0E-9
                  );
               }
            }
         }
      }
   }
}
