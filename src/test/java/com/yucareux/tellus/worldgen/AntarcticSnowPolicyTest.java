package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.cover.TellusLandCoverSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AntarcticSnowPolicyTest {
   private static final double WORLD_SCALE = 30.0;

   @Test
   void appliesOnlyToNoDataSouthOfWorldCoverCoverage() {
      AntarcticSnowPolicy policy = AntarcticSnowPolicy.forWorldScale(WORLD_SCALE);

      assertFalse(policy.shouldUseSnowFallback(
         TellusLandCoverSource.NO_DATA_CLASS,
         EarthProjection.latToBlockZ(TellusLandCoverSource.WORLD_COVER_MIN_LATITUDE, WORLD_SCALE)
      ));
      assertTrue(policy.shouldUseSnowFallback(
         TellusLandCoverSource.NO_DATA_CLASS,
         EarthProjection.latToBlockZ(-75.0, WORLD_SCALE)
      ));
      assertFalse(policy.shouldUseSnowFallback(
         TellusLandCoverSource.NO_DATA_CLASS,
         EarthProjection.latToBlockZ(-45.0, WORLD_SCALE)
      ));
   }

   @Test
   void preservesValidLandCoverSouthOfCoverage() {
      AntarcticSnowPolicy policy = AntarcticSnowPolicy.forWorldScale(WORLD_SCALE);
      double antarcticZ = EarthProjection.latToBlockZ(-75.0, WORLD_SCALE);

      assertFalse(policy.shouldUseSnowFallback(30, antarcticZ));
      assertFalse(policy.shouldUseSnowFallback(70, antarcticZ));
      assertFalse(policy.shouldUseSnowFallback(80, antarcticZ));
   }

   @Test
   void invalidWorldScalesDisableTheFallback() {
      double antarcticZ = EarthProjection.latToBlockZ(-75.0, WORLD_SCALE);

      assertFalse(AntarcticSnowPolicy.forWorldScale(0.0).shouldUseSnowFallback(0, antarcticZ));
      assertFalse(AntarcticSnowPolicy.forWorldScale(Double.NaN).shouldUseSnowFallback(0, antarcticZ));
      assertFalse(AntarcticSnowPolicy.forWorldScale(Double.POSITIVE_INFINITY).shouldUseSnowFallback(0, antarcticZ));
   }

   @Test
   void followsWorldScaleWhenMappingTheCoverageBoundary() {
      for (double worldScale : new double[]{1.0, 30.0, 1000.0}) {
         AntarcticSnowPolicy policy = AntarcticSnowPolicy.forWorldScale(worldScale);
         assertTrue(policy.shouldUseSnowFallback(0, EarthProjection.latToBlockZ(-60.01, worldScale)));
         assertFalse(policy.shouldUseSnowFallback(0, EarthProjection.latToBlockZ(-59.99, worldScale)));
      }
   }
}
