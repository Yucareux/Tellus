package com.yucareux.tellus.preload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.WorldProjection;
import org.junit.jupiter.api.Test;

class TerrainPreloadAreaTest {
   @Test
   void centeredAreaUsesChunksPerSide() {
      TerrainPreloadArea area = TerrainPreloadArea.centered(20.0, -103.0, 12, 30.0);

      assertEquals(12, area.chunksPerSide());
      assertEquals(144, area.totalChunks());
      assertEquals(11, area.maxChunkX() - area.minChunkX());
      assertEquals(11, area.maxChunkZ() - area.minChunkZ());
   }

   @Test
   void spawnCenteredProjectionKeepsAreaAndFutureSpawnNearBlockOrigin() {
      WorldProjection projection = WorldProjection.centeredOn(30.0, 37.7459, -119.5332);
      TerrainPreloadArea area = TerrainPreloadArea.centered(37.7459, -119.5332, 12, projection);

      assertTrue(area.containsChunk(0, 0));
      assertTrue(area.minChunkX() <= 0 && area.maxChunkX() >= 0);
      assertTrue(area.minChunkZ() <= 0 && area.maxChunkZ() >= 0);
      assertEquals(0.0, projection.lonToBlockX(area.centerLongitude()), 1.0E-9);
      assertEquals(0.0, projection.latToBlockZ(area.centerLatitude()), 1.0E-9);
   }

   @Test
   void wrappedBoundsStayOnTheMapCopyNearestTheirNegativeLongitudeCenter() {
      double centerLongitude = -179.99;
      WorldProjection projection = WorldProjection.centeredOn(30.0, 0.0, centerLongitude);
      TerrainPreloadArea area = TerrainPreloadArea.centered(0.0, centerLongitude, 12, projection);

      assertTrue(area.westLongitude() < area.eastLongitude());
      assertEquals(centerLongitude, (area.westLongitude() + area.eastLongitude()) * 0.5, 0.02);
      assertTrue(area.eastLongitude() < -179.0);
   }

   @Test
   void rectangularAreaReportsItsActualChunkCount() {
      TerrainPreloadArea area = TerrainPreloadArea.fromChunkBounds(0.0, 0.0, 4, 1.0, 10, 20, 13, 21);

      assertEquals(4, area.chunkWidth());
      assertEquals(2, area.chunkDepth());
      assertEquals(8, area.totalChunks());
      assertTrue(area.summary().startsWith("4 x 2 chunks"));
   }

   @Test
   void defaultLimitAllowsLargePreloadAreas() {
      assertEquals(8480, TerrainPreloadArea.maxChunksPerSide());
      assertEquals(5000, TerrainPreloadArea.clampChunksPerSide(5000));

      TerrainPreloadArea area = TerrainPreloadArea.centered(20.0, -103.0, 8480, 1.0);

      assertEquals(8480, area.chunksPerSide());
      assertEquals(71_910_400, area.totalChunks());
      assertEquals(8480 * TerrainPreloadArea.CHUNK_SIZE, area.maxBlockX() - area.minBlockX() + 1);
   }

   @Test
   void oneToOneLargeAreaKeepsItsExactChunkFootprint() {
      TerrainPreloadArea area = TerrainPreloadArea.centered(17.0732, -96.7266, 4092, 1.0);

      assertEquals(4092 * TerrainPreloadArea.CHUNK_SIZE, area.maxBlockX() - area.minBlockX() + 1);
      assertEquals(4092 * TerrainPreloadArea.CHUNK_SIZE, area.maxBlockZ() - area.minBlockZ() + 1);
      double longitudeSpan = area.eastLongitude() - area.westLongitude();
      assertTrue(longitudeSpan > 0.58 && longitudeSpan < 0.60);
   }

   @Test
   void worldScaleChangesMapFootprint() {
      TerrainPreloadArea smallScale = TerrainPreloadArea.centered(20.0, -103.0, 16, 10.0);
      TerrainPreloadArea largeScale = TerrainPreloadArea.centered(20.0, -103.0, 16, 30.0);
      double smallLongitudeSpan = smallScale.eastLongitude() - smallScale.westLongitude();
      double largeLongitudeSpan = largeScale.eastLongitude() - largeScale.westLongitude();

      assertTrue(largeLongitudeSpan > smallLongitudeSpan * 2.5);
      assertTrue(largeLongitudeSpan < smallLongitudeSpan * 3.5);
   }

   @Test
   void settingsOverridesApplyThroughCodec() {
      TerrainPreloadSettingsOverrides overrides = TerrainPreloadSettingsOverrides.from(EarthGeneratorSettings.DEFAULT)
         .withWorldScale(12.0)
         .withEnableRoads(false)
         .withEnableBuildings(false)
         .withEnableWater(false)
         .withUndergroundDepth(256)
         .withAddStructures(false);

      EarthGeneratorSettings settings = overrides.apply(EarthGeneratorSettings.DEFAULT);

      assertEquals(12.0, settings.worldScale(), 0.0001);
      assertEquals(false, settings.enableRoads());
      assertEquals(false, settings.enableBuildings());
      assertTrue(settings.enableWater());
      assertEquals(256, settings.undergroundDepth());
      assertEquals(false, settings.addVillages());
      assertEquals(false, settings.addStrongholds());
   }

   @Test
   void preloadOverridesCanUseSelectedAreaCenterAsSpawnpoint() {
      EarthGeneratorSettings settings = TerrainPreloadSettingsOverrides.from(EarthGeneratorSettings.DEFAULT)
         .apply(EarthGeneratorSettings.DEFAULT, 35.3606, 138.7274);

      assertEquals(35.3606, settings.spawnLatitude(), 0.0001);
      assertEquals(138.7274, settings.spawnLongitude(), 0.0001);
   }

   @Test
   void preloadOverridesMoveTheCenteredProjectionOriginWithSelectedSpawn() {
      EarthGeneratorSettings base = EarthGeneratorSettings.DEFAULT.withCenterWorldOnSpawn(true);
      EarthGeneratorSettings settings = TerrainPreloadSettingsOverrides.from(base).apply(base, 35.3606, 138.7274);

      assertTrue(settings.centerWorldOnSpawn());
      assertEquals(0.0, settings.projection().lonToBlockX(138.7274), 1.0E-9);
      assertEquals(0.0, settings.projection().latToBlockZ(35.3606), 1.0E-9);
   }

   @Test
   void rejectsOverflowingOrInconsistentChunkBounds() {
      assertThrows(
         IllegalArgumentException.class,
         () -> TerrainPreloadArea.fromChunkBounds(0.0, 0.0, 2, 1.0, 0, 0, 2, 0)
      );
      assertThrows(
         IllegalArgumentException.class,
         () -> TerrainPreloadArea.fromChunkBounds(0.0, 0.0, 1, 1.0, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0)
      );
   }

   @Test
   void capsConfiguredAreaSizeBeforeChunkCountCanOverflow() {
      String key = "tellus.preload.maxChunksPerSide";
      String previous = System.getProperty(key);
      try {
         System.setProperty(key, Integer.toString(Integer.MAX_VALUE));
         assertEquals(TerrainPreloadArea.HARD_MAX_CHUNKS_PER_SIDE, TerrainPreloadArea.maxChunksPerSide());
         TerrainPreloadArea area = TerrainPreloadArea.centered(0.0, 0.0, Integer.MAX_VALUE, 1.0);
         assertTrue(area.totalChunks() > 0);
      } finally {
         if (previous == null) {
            System.clearProperty(key);
         } else {
            System.setProperty(key, previous);
         }
      }
   }
}
