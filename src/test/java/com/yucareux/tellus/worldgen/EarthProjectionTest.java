package com.yucareux.tellus.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EarthProjectionTest {
   @Test
   void mercatorHeightScaleIsSecantOfLatitude() {
      assertEquals(1.0, EarthProjection.heightScaleCorrectionAtLatitude(0.0), 1.0E-12);
      assertEquals(Math.sqrt(2.0), EarthProjection.heightScaleCorrectionAtLatitude(-45.0), 1.0E-12);
      assertEquals(2.0, EarthProjection.heightScaleCorrectionAtLatitude(60.0), 1.0E-12);
   }

   @Test
   void globalProjectionKeepsHistoricalMercatorCoordinates() {
      double[] worldScales = {1.0, 30.0, 1000.0};
      double[] latitudes = {-80.0, -63.0695, -27.9881, 0.0, 27.9881, 63.0695, 80.0};
      double[] longitudes = {-180.0, -119.5332, -105.2253, 0.0, 86.925, 180.0};

      for (double worldScale : worldScales) {
         WorldProjection projection = WorldProjection.global(worldScale);
         for (double longitude : longitudes) {
            double expectedX = longitude * EarthProjection.METERS_PER_DEGREE / worldScale;
            assertEquals(expectedX, projection.lonToBlockX(longitude), 1.0E-9);
            assertEquals(longitude, projection.blockXToLon(expectedX), 1.0E-12);
         }

         for (double latitude : latitudes) {
            double latitudeRadians = Math.toRadians(latitude);
            double expectedZ = -EarthProjection.EARTH_RADIUS_METERS
               * Math.log(Math.tan(Math.PI * 0.25 + latitudeRadians * 0.5))
               / worldScale;
            assertEquals(expectedZ, projection.latToBlockZ(latitude), 1.0E-8);
            assertEquals(latitude, projection.blockZToLat(expectedZ), 2.0E-12);
            assertEquals(
               EarthProjection.heightScaleCorrectionAtLatitude(latitude),
               projection.heightScaleCorrection(expectedZ),
               2.0E-9
            );
         }
      }
   }

   @Test
   void blockCorrectionIsInverseOfLocalMercatorGroundScale() {
      WorldProjection projection = WorldProjection.global(30.0);
      double blockZ = projection.latToBlockZ(60.0);
      double localGroundScale = projection.groundMetersPerBlockZ(blockZ);

      assertEquals(15.0, localGroundScale, 1.0E-9);
      assertEquals(projection.worldScale() / localGroundScale, projection.heightScaleCorrection(blockZ), 1.0E-12);
      assertEquals(localGroundScale, projection.groundMetersPerBlockX(blockZ), 1.0E-12);
   }

   @Test
   void centeredProjectionMapsSpawnToOriginAndRoundTrips() {
      double spawnLatitude = 37.7459;
      double spawnLongitude = -119.5332;
      WorldProjection projection = WorldProjection.centeredOn(30.0, spawnLatitude, spawnLongitude);

      assertTrue(projection.isCentered());
      assertEquals(0.0, projection.lonToBlockX(spawnLongitude), 1.0E-9);
      assertEquals(0.0, projection.latToBlockZ(spawnLatitude), 1.0E-9);
      assertEquals(spawnLongitude, projection.blockXToLon(0.0), 1.0E-12);
      assertEquals(spawnLatitude, projection.blockZToLat(0.0), 1.0E-12);

      double[][] places = {
         {39.0968, -120.0324},
         {37.7749, -122.4194},
         {47.6062, -122.3321},
         {35.6762, 139.6503},
         {-33.8688, 151.2093}
      };
      for (double[] place : places) {
         double blockX = projection.lonToBlockX(place[1]);
         double blockZ = projection.latToBlockZ(place[0]);
         assertEquals(place[1], projection.blockXToLon(blockX), 1.0E-10);
         assertEquals(place[0], projection.blockZToLat(blockZ), 1.0E-10);
      }
   }

   @Test
   void centeredProjectionPreservesMercatorDistortionAtGeographicLatitude() {
      WorldProjection projection = WorldProjection.centeredOn(30.0, 37.7459, -119.5332);
      double[] latitudes = {-60.0, 0.0, 37.7459, 60.0, 80.0};
      for (double latitude : latitudes) {
         double blockZ = projection.latToBlockZ(latitude);
         assertEquals(
            EarthProjection.heightScaleCorrectionAtLatitude(latitude),
            projection.heightScaleCorrection(blockZ),
            2.0E-9
         );
      }
   }

   @Test
   void centeredProjectionMovesLongitudeSeamOppositeSpawnWithoutRepeatedWrapping() {
      WorldProjection projection = WorldProjection.centeredOn(30.0, 37.7459, -119.5332);
      double oppositeLongitude = WorldProjection.wrapLongitude(-119.5332 + 180.0);
      double edge = 180.0 * projection.equatorialBlocksPerDegree();

      assertEquals(-edge, projection.lonToBlockX(oppositeLongitude), 1.0E-8);
      assertEquals(oppositeLongitude, projection.blockXToLon(-edge), 1.0E-10);
      assertTrue(projection.blockXToLon(edge + 1.0) > 180.0);
      assertTrue(projection.containsHorizontalBlock(edge, 0.0));
      assertFalse(projection.containsHorizontalBlock(Math.nextUp(edge), 0.0));
      assertFalse(projection.containsHorizontalBlock(0.0, -edge));
   }

   @Test
   void projectedMetersAreOriginIndependentForSharedCaches() {
      double latitude = 39.0968;
      double longitude = -120.0324;
      WorldProjection global = WorldProjection.global(30.0);
      WorldProjection centered = WorldProjection.centeredOn(30.0, 37.7459, -119.5332);

      double globalX = global.lonToBlockX(longitude);
      double globalZ = global.latToBlockZ(latitude);
      double centeredX = centered.lonToBlockX(longitude);
      double centeredZ = centered.latToBlockZ(latitude);

      assertEquals(global.blockXToProjectedMeters(globalX), centered.blockXToProjectedMeters(centeredX), 1.0E-8);
      assertEquals(global.blockZToProjectedMeters(globalZ), centered.blockZToProjectedMeters(centeredZ), 1.0E-8);
      assertEquals(centeredX, centered.projectedMetersToBlockX(global.blockXToProjectedMeters(globalX)), 1.0E-8);
      assertEquals(centeredZ, centered.projectedMetersToBlockZ(global.blockZToProjectedMeters(globalZ)), 1.0E-8);
   }

   @Test
   void globalProjectedEastingWrapsContinuouslyAtTheRealAntimeridian() {
      double circumference = EarthProjection.EQUATOR_CIRCUMFERENCE_METERS;
      double half = circumference * 0.5;

      assertEquals(-half, WorldProjection.wrapProjectedX(half), 1.0E-8);
      assertEquals(-half + 30.0, WorldProjection.wrapProjectedX(half + 30.0), 1.0E-8);
      assertEquals(half - 30.0, WorldProjection.wrapProjectedX(-half - 30.0), 1.0E-8);
      assertEquals(
         WorldProjection.wrapProjectedX(half + 1_000.0),
         WorldProjection.wrapProjectedX(half + circumference + 1_000.0),
         1.0E-8
      );
   }

   @Test
   void settingsSelectGlobalProjectionByDefaultAndCenteredProjectionOnlyWhenEnabled() {
      EarthGeneratorSettings globalSettings = EarthGeneratorSettings.DEFAULT.withSpawn(37.7459, -119.5332);
      EarthGeneratorSettings centeredSettings = globalSettings.withCenterWorldOnSpawn(true);

      assertFalse(globalSettings.projection().isCentered());
      assertTrue(centeredSettings.projection().isCentered());
      assertEquals(0.0, centeredSettings.projection().lonToBlockX(centeredSettings.spawnLongitude()), 1.0E-9);
      assertEquals(0.0, centeredSettings.projection().latToBlockZ(centeredSettings.spawnLatitude()), 1.0E-9);
   }
}
