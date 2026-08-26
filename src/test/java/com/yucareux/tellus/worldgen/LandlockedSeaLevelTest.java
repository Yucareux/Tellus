package com.yucareux.tellus.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LandlockedSeaLevelTest {
   @Test
   void appliesCaspianSurfaceBelowGlobalSeaLevel() {
      assertTrue(LandlockedSeaLevel.isCaspian(51.0, 42.0));
      assertEquals(-28.0, LandlockedSeaLevel.surfaceElevationMeters(51.0, 42.0));
   }

   @Test
   void leavesOtherOceansAndLandlockedWatersUnchanged() {
      assertNoOverride(35.0, 43.0);  // Black Sea
      assertNoOverride(-40.0, 30.0); // Atlantic Ocean
      assertNoOverride(60.0, 45.0);  // Aral Sea
      assertNoOverride(35.5, 31.5);  // Dead Sea
   }

   @Test
   void blockLookupUsesTheActiveEarthProjection() {
      double worldScale = 30.0;
      WorldProjection projection = WorldProjection.global(worldScale);
      int blockX = (int)Math.round(projection.lonToBlockX(51.0));
      int blockZ = (int)Math.round(projection.latToBlockZ(42.0));

      assertEquals(-28.0, LandlockedSeaLevel.surfaceElevationMetersAtBlock(blockX, blockZ, projection));
   }

   private static void assertNoOverride(double longitude, double latitude) {
      assertFalse(LandlockedSeaLevel.isCaspian(longitude, latitude));
      assertTrue(Double.isNaN(LandlockedSeaLevel.surfaceElevationMeters(longitude, latitude)));
   }
}
