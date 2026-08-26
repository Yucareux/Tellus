package com.yucareux.tellus.world.data.cover;

import com.yucareux.tellus.worldgen.EarthProjection;
import com.yucareux.tellus.worldgen.WorldProjection;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TellusLandCoverSourceTest {
   @Test
   void mapsEveryOvertureLandCoverSubtypeToStableWorldgenClasses() {
      assertEquals(10, TellusLandCoverSource.coverClassForSubtype("forest"));
      assertEquals(20, TellusLandCoverSource.coverClassForSubtype("shrub"));
      assertEquals(30, TellusLandCoverSource.coverClassForSubtype("grass"));
      assertEquals(40, TellusLandCoverSource.coverClassForSubtype("crop"));
      assertEquals(50, TellusLandCoverSource.coverClassForSubtype("urban"));
      assertEquals(60, TellusLandCoverSource.coverClassForSubtype("barren"));
      assertEquals(70, TellusLandCoverSource.coverClassForSubtype("snow"));
      assertEquals(90, TellusLandCoverSource.coverClassForSubtype("wetland"));
      assertEquals(95, TellusLandCoverSource.coverClassForSubtype("mangrove"));
      assertEquals(100, TellusLandCoverSource.coverClassForSubtype("moss"));
      assertEquals(-1, TellusLandCoverSource.coverClassForSubtype("unknown"));
   }

   @Test
   void selectsAvailableZoomFromWorldAndLodResolution() {
      assertEquals(13, TellusLandCoverSource.selectZoom(1.0, 8, 13, 512));
      assertEquals(13, TellusLandCoverSource.selectZoom(10.0, 8, 13, 512));
      assertEquals(12, TellusLandCoverSource.selectZoom(20.0, 8, 13, 512));
      assertEquals(11, TellusLandCoverSource.selectZoom(40.0, 8, 13, 512));
      assertEquals(10, TellusLandCoverSource.selectZoom(80.0, 8, 13, 512));
      assertEquals(9, TellusLandCoverSource.selectZoom(160.0, 8, 13, 512));
      assertEquals(8, TellusLandCoverSource.selectZoom(320.0, 8, 13, 512));
      assertEquals(8, TellusLandCoverSource.selectZoom(1000.0, 8, 13, 512));
      assertEquals(12, TellusLandCoverSource.selectZoom(1.0, 8, 12, 512));
      assertEquals(8, TellusLandCoverSource.selectZoom(10000.0, 8, 13, 512));
   }

   @Test
   void rasterizesLandCoverWithoutTurningWaterIntoCoarseCoverCells() {
      Tile.Feature forest = polygonFeature(0, 4096, 0, 4096, 0, 0);
      Tile.Feature water = polygonFeature(2048, 4096, 0, 4096, -1, -1);
      Tile.Layer landCover = Tile.Layer.newBuilder()
         .setVersion(2)
         .setName("land_cover")
         .setExtent(4096)
         .addKeys("subtype")
         .addKeys("cartography")
         .addValues(Tile.Value.newBuilder().setStringValue("forest"))
         .addValues(Tile.Value.newBuilder().setStringValue("{\"sort_key\":2}"))
         .addFeatures(forest)
         .build();
      Tile.Layer waterLayer = Tile.Layer.newBuilder()
         .setVersion(2)
         .setName("water")
         .setExtent(4096)
         .addFeatures(water)
         .build();
      byte[] payload = Tile.newBuilder().addLayers(landCover).addLayers(waterLayer).build().toByteArray();

      byte[] raster = TellusLandCoverSource.rasterizeVectorTile(payload, 8);

      assertEquals(10, raster[4 * 8 + 1] & 255);
      assertEquals(10, raster[4 * 8 + 6] & 255);
   }

   @Test
   void drawsLowerSortKeyLandCoverInFrontOfOverlappingBackground() {
      Tile.Feature forest = polygonFeature(1024, 3072, 1024, 3072, 0, 0, 1, 1);
      Tile.Feature shrub = polygonFeature(0, 4096, 0, 4096, 0, 2, 1, 3);
      Tile.Layer landCover = Tile.Layer.newBuilder()
         .setVersion(2)
         .setName("land_cover")
         .setExtent(4096)
         .addKeys("subtype")
         .addKeys("cartography")
         .addValues(Tile.Value.newBuilder().setStringValue("forest"))
         .addValues(Tile.Value.newBuilder().setStringValue("{\"sort_key\":2}"))
         .addValues(Tile.Value.newBuilder().setStringValue("shrub"))
         .addValues(Tile.Value.newBuilder().setStringValue("{\"sort_key\":4}"))
         .addFeatures(forest)
         .addFeatures(shrub)
         .build();

      byte[] raster = TellusLandCoverSource.rasterizeVectorTile(
         Tile.newBuilder().addLayers(landCover).build().toByteArray(),
         8
      );

      assertEquals(20, raster[1 * 8 + 1] & 255);
      assertEquals(10, raster[4 * 8 + 4] & 255);
   }

   @Test
   void findsNearestNonWaterLandClass() {
      int[][] cover = new int[5][5];
      for (int y = 0; y < cover.length; y++) {
         for (int x = 0; x < cover[y].length; x++) {
            cover[y][x] = 80;
         }
      }
      cover[3][3] = 30;
      cover[2][0] = 10;

      int nearest = TellusLandCoverSource.findNearestLandCoverClass(
         2,
         2,
         4,
         (x, y) -> x >= 0 && y >= 0 && y < cover.length && x < cover[y].length ? cover[y][x] : Integer.MIN_VALUE
      );

      assertEquals(30, nearest);
   }

   @Test
   void skipsNoDataWaterAndMangrovesWhenFindingTerrainReference() {
      int[][] cover = new int[][]{
         {80, 80, 80, 80, 80},
         {80, 0, 95, 0, 80},
         {80, 95, 80, 95, 80},
         {80, 0, 95, 40, 80},
         {80, 80, 80, 80, 80}
      };

      int nearest = TellusLandCoverSource.findNearestLandCoverClass(
         2,
         2,
         4,
         (x, y) -> x >= 0 && y >= 0 && y < cover.length && x < cover[y].length ? cover[y][x] : Integer.MIN_VALUE
      );

      assertEquals(40, nearest);
   }

   @Test
   void marksNearestLandSearchIncompleteWhenAnExpectedTilePixelIsUnavailable() {
      TellusLandCoverSource.NearestLandSearch search = TellusLandCoverSource.findNearestLandCoverClassWithCoverage(
         2,
         2,
         2,
         (x, y) -> x == 1 && y == 1 ? Integer.MIN_VALUE : x == 4 && y == 2 ? 30 : 80,
         (x, y) -> x >= 0 && y >= 0 && x < 5 && y < 5
      );

      assertEquals(30, search.coverClass());
      assertFalse(search.complete());
   }

   @Test
   void treatsOutOfCoveragePixelsAsCompleteAbsence() {
      TellusLandCoverSource.NearestLandSearch search = TellusLandCoverSource.findNearestLandCoverClassWithCoverage(
         0,
         0,
         2,
         (x, y) -> x < 0 || y < 0 ? Integer.MIN_VALUE : x == 1 && y == 0 ? 40 : 80,
         (x, y) -> x >= 0 && y >= 0
      );

      assertEquals(40, search.coverClass());
      assertTrue(search.complete());
   }

   @Test
   void distinguishesSocketTimeoutsFromActualThreadInterruption() throws Exception {
      assertFalse(TellusLandCoverSource.isInterruptedLoad(new SocketTimeoutException("read timed out")));
      assertTrue(TellusLandCoverSource.isInterruptedLoad(new InterruptedIOException("cancelled")));
      assertTrue(TellusLandCoverSource.isInterruptedLoad(new ClosedByInterruptException()));
   }

   @Test
   void retriesTransientInitializationFailuresWithBoundedBackoff() {
      assertEquals(0, TellusLandCoverSource.retryDelaySeconds(0));
      assertEquals(1, TellusLandCoverSource.retryDelaySeconds(1));
      assertEquals(16, TellusLandCoverSource.retryDelaySeconds(5));
      assertEquals(60, TellusLandCoverSource.retryDelaySeconds(6));
      assertEquals(60, TellusLandCoverSource.retryDelaySeconds(100));
   }

   @Test
   void rejectsSamplesBeyondTheFiniteProjectedEarth() {
      double worldScale = 1.0;
      WorldProjection projection = WorldProjection.global(worldScale);
      double northEdge = projection.latToBlockZ(EarthProjection.MAX_MERCATOR_LATITUDE);
      double southEdge = projection.latToBlockZ(-EarthProjection.MAX_MERCATOR_LATITUDE);
      double minZ = Math.min(northEdge, southEdge);
      double maxZ = Math.max(northEdge, southEdge);

      assertTrue(TellusLandCoverSource.isWithinTileCoverage(0.0, minZ, projection));
      assertTrue(TellusLandCoverSource.isWithinTileCoverage(0.0, maxZ, projection));
      assertFalse(TellusLandCoverSource.isWithinTileCoverage(0.0, minZ - 1.0, projection));
      assertFalse(TellusLandCoverSource.isWithinTileCoverage(0.0, maxZ + 1.0, projection));
      assertFalse(TellusLandCoverSource.isWithinTileCoverage(Double.NaN, 0.0, projection));
      assertFalse(TellusLandCoverSource.isWithinTileCoverage(0.0, 0.0, WorldProjection.global(0.0)));
   }

   private static Tile.Feature polygonFeature(int minX, int maxX, int minY, int maxY, int firstTagKey, int firstTagValue) {
      return polygonFeature(minX, maxX, minY, maxY, firstTagKey, firstTagValue, 1, 1);
   }

   private static Tile.Feature polygonFeature(
      int minX,
      int maxX,
      int minY,
      int maxY,
      int firstTagKey,
      int firstTagValue,
      int secondTagKey,
      int secondTagValue
   ) {
      Tile.Feature.Builder feature = Tile.Feature.newBuilder()
         .setType(Tile.GeomType.POLYGON)
         .addGeometry(command(1, 1))
         .addGeometry(zigZag(minX))
         .addGeometry(zigZag(minY))
         .addGeometry(command(2, 3))
         .addGeometry(zigZag(maxX - minX))
         .addGeometry(zigZag(0))
         .addGeometry(zigZag(0))
         .addGeometry(zigZag(maxY - minY))
         .addGeometry(zigZag(minX - maxX))
         .addGeometry(zigZag(0))
         .addGeometry(command(7, 1));
      if (firstTagKey >= 0) {
         feature.addTags(firstTagKey).addTags(firstTagValue).addTags(secondTagKey).addTags(secondTagValue);
      }
      return feature.build();
   }

   private static int command(int id, int count) {
      return count << 3 | id;
   }

   private static int zigZag(int value) {
      return value << 1 ^ value >> 31;
   }
}
