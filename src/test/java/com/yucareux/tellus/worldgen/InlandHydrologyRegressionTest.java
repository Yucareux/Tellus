package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.osm.OsmWaterKind;
import com.yucareux.tellus.world.data.osm.TellusOsmWaterSource;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.DoubleBinaryOperator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Whole-solver fixtures: no Minecraft launch, remote tiles or renderer involved. */
class InlandHydrologyRegressionTest {
   static OsmWaterFeature feature(long id, boolean line, OsmWaterKind kind, double... xz) {
      double[] lon = new double[xz.length / 2], lat = new double[lon.length];
      for (int i = 0; i < lon.length; i++) {
         lon[i] = EarthProjection.blockXToLongitude(xz[i * 2], 1.0);
         lat[i] = EarthProjection.blockZToLat(xz[i * 2 + 1], 1.0);
      }
      return new OsmWaterFeature(id, line, false, kind, new double[][]{lon}, new double[][]{lat});
   }

   static InlandWaterInput input(OsmWaterFeature... features) {
      return new InlandWaterInput() {
         private TellusOsmWaterSource.WaterQueryResult result(List<OsmWaterFeature> selected) {
            return new TellusOsmWaterSource.WaterQueryResult(selected, TellusOsmWaterSource.CoverageStatus.COMPLETE, 14);
         }
         public TellusOsmWaterSource.WaterQueryResult waterForAreaWithStatus(int x, int z, int xx, int zz,
            double scale, int margin, OsmQueryMode mode) { return result(List.of(features)); }
         public TellusOsmWaterSource.WaterQueryResult connectedWaterBody(OsmWaterFeature seed, double scale) {
            return result(List.of(features).stream().filter(f -> f.featureId() == seed.featureId()).toList());
         }
         public TellusOsmWaterSource.WaterQueryResult connectedWaterfall(OsmWaterFeature seed, double scale) {
            return connectedWaterBody(seed, scale);
         }
      };
   }

   static InlandHydrologyPlanner planner(DoubleBinaryOperator dem, double resolution, OsmWaterFeature... features) {
      return new InlandHydrologyPlanner(input(features), 1.0, 24,
         (x, z) -> new InlandHydrologyPlanner.Elevation(dem.applyAsDouble(x, z), resolution),
         (x, z) -> Integer.MIN_VALUE, (x, z) -> true, (m, z) -> m);
   }

   @Test void onlyMappedLakesCountAsLakeWater() {
      OsmWaterFeature lake = feature(900, false, OsmWaterKind.LAKE, 100, 100, 300, 100, 300, 300, 100, 300, 100, 100);
      OsmWaterFeature river = feature(901, false, OsmWaterKind.RIVER, 500, 100, 540, 100, 540, 900, 500, 900, 500, 100);
      OsmWaterFeature stream = feature(902, true, OsmWaterKind.STREAM, 700, 100, 700, 900);
      InlandHydrologyPlanner planner = planner((x, z) -> 100.0, 30, lake, river, stream);
      assertTrue(planner.nearLake(200, 200, 8.0));
      assertTrue(planner.nearLake(305, 200, 8.0), "Lake water spread past the outline");
      assertFalse(planner.nearLake(320, 200, 8.0));
      assertFalse(planner.nearLake(520, 500, 8.0), "River area");
      assertFalse(planner.nearLake(700, 500, 8.0), "Stream line");
      assertFalse(planner.nearLake(400, 600, 8.0), "Dry land");
   }

   @Test void narrowRiverFollowingTileEdgeDoesNotSproutTransverseChannels() {
      OsmWaterFeature line = feature(1, true, OsmWaterKind.RIVER, 1022, 128, 1022, 896);
      InlandHydrologyPlanner planner = planner((x, z) -> 80.0 + Math.abs(x - 1022) * 0.4, 30, line);
      for (int z = 150; z < 880; z += 4) {
         assertTrue(planner.sample(1022, z, 80).wet(), "Missing river at z=" + z);
         assertFalse(planner.sample(1036, z, 86).wet(), "Invented side channel at z=" + z);
      }
   }

   @Test void obliqueCrossingStaysInItsValleyAndConnected() {
      OsmWaterFeature line = feature(2, true, OsmWaterKind.RIVER, 820, 290, 1220, 690);
      InlandHydrologyPlanner planner = planner((x, z) -> 100 + Math.abs(z - (x - 530)) * 1.8, 30, line);
      assertConnected(planner, 1004, 474, 1044, 514, 994, 464, 1060, 530);
      for (int x = 995; x <= 1055; x++) for (int z = 465; z <= 525; z++) {
         if (Math.abs(z - x + 530) > 12)
            assertFalse(planner.sample(x, z, 150).wet(), "Transverse spur at " + x + "," + z);
      }
   }

   @Test void grazingTileCrossingDoesNotCutAcrossTheCanyonWall() {
      OsmWaterFeature line = feature(7, true, OsmWaterKind.RIVER, 850, -358, 1200, 1392);
      InlandHydrologyPlanner planner = planner((x, z) -> 200 + Math.abs(x - (1024 + (z - 512) / 5.0)) * 3, 30, line);
      for (int x = 965; x <= 1080; x += 2) for (int z = 410; z <= 614; z += 2) {
         if (Math.abs(x - (1024 + (z - 512) / 5.0)) > 12)
            assertFalse(planner.sample(x, z, 300).wet(), "Water cuts canyon wall at " + x + "," + z);
      }
      assertConnected(planner, 1008, 432, 1040, 592, 985, 414, 1060, 608);
   }

   @Test void narrowDiagonalStreamHasAFaceConnectedWaterPath() {
      OsmWaterFeature stream = feature(8, true, OsmWaterKind.STREAM, 200, 200, 750, 750);
      InlandHydrologyPlanner planner = planner((x, z) -> 100 + Math.abs(x - z) * 8, 8, stream);
      assertConnected(planner, 250, 250, 700, 700, 230, 230, 720, 720);
   }

   @Test void splitRiverKeepsBothArmsAroundAnIsland() {
      OsmWaterFeature upper = feature(9, true, OsmWaterKind.RIVER,
         150, 500, 300, 500, 400, 400, 600, 400, 700, 500, 850, 500);
      OsmWaterFeature lower = feature(10, true, OsmWaterKind.RIVER, 300, 500, 400, 600, 600, 600, 700, 500);
      InlandHydrologyPlanner planner = planner((x, z) -> 100 + x * 0.002, 30, upper, lower);
      assertConnected(planner, 170, 500, 830, 500, 140, 370, 860, 630);
      assertConnected(planner, 500, 400, 500, 600, 280, 370, 720, 630);
      assertTrue(planner.sample(500, 400, 101).wet());
      assertTrue(planner.sample(500, 600, 101).wet());
   }

   // Map waterways are drawn downstream, and a mapped river area has its waterway line inside it; the
   // fixtures below follow the same conventions as real map data.

   @Test void waterfallAreaConnectsItsPoolsWithoutCreatingASecondCrest() {
      OsmWaterFeature upper = feature(11, false, OsmWaterKind.RIVER, 280, 220, 744, 220, 744, 460, 280, 460, 280, 220);
      OsmWaterFeature lower = feature(12, false, OsmWaterKind.RIVER, 280, 520, 744, 520, 744, 800, 280, 800, 280, 520);
      OsmWaterFeature fall = feature(13, false, OsmWaterKind.WATERFALL, 280, 460, 744, 460, 744, 520, 280, 520, 280, 460);
      OsmWaterFeature line = feature(14, true, OsmWaterKind.RIVER, 512, 230, 512, 790);
      InlandHydrologyPlanner planner = planner((x, z) -> z < 460 ? 180 : 120, 8, upper, lower, fall, line);
      assertConnected(planner, 512, 432, 512, 560, 490, 420, 535, 580);
      assertTrue(planner.sample(512, 460, 120).falling(), "The upstream edge is the lip");
      assertFalse(planner.sample(512, 520, 120).falling(), "The downstream area boundary is not another lip");
      assertEquals(119, planner.sample(512, 510, 120).water(), 2);
   }

   @Test void sharpBendAndTributaryStayConnected() {
      OsmWaterFeature bend = feature(3, true, OsmWaterKind.RIVER, 200, 350, 450, 350, 450, 650, 720, 650);
      OsmWaterFeature branch = feature(4, true, OsmWaterKind.STREAM, 450, 500, 650, 500);
      InlandHydrologyPlanner planner = planner((x, z) -> 100, 30, bend, branch);
      assertConnected(planner, 225, 350, 695, 650, 180, 325, 750, 675);
      assertConnected(planner, 450, 480, 630, 500, 425, 470, 670, 530);
   }

   @org.junit.jupiter.params.ParameterizedTest
   @org.junit.jupiter.params.provider.ValueSource(ints = {0, 512, -1024})
   void curvedWaterfallHasOnlyTwoPoolsAndOneCrest(int offset) {
      OsmWaterFeature river = feature(5, false, OsmWaterKind.RIVER,
         280, 220, 744, 220, 744, 800, 280, 800, 280, 220);
      OsmWaterFeature fall = feature(6, true, OsmWaterKind.WATERFALL,
         280, 460, 400, 510, 512, 535, 624, 510, 744, 460);
      OsmWaterFeature line = feature(7, true, OsmWaterKind.RIVER, 512, 230, 512, 790);
      DoubleBinaryOperator crest = (x, z) -> x < 400 ? 460 + (x - 280) * 50 / 120
         : x < 512 ? 510 + (x - 400) * 25 / 112 : x < 624 ? 535 - (x - 512) * 25 / 112 : 510 - (x - 624) * 50 / 120;
      if (offset != 0) {
         river = translated(river, offset);
         fall = translated(fall, offset);
         line = translated(line, offset);
      }
      InlandHydrologyPlanner planner = planner((x, z) -> z - offset < crest.applyAsDouble(x - offset, z - offset) ? 180 : 120, 8, river, fall, line);
      for (int x = 320; x <= 704; x += 16) {
         int lip = (int)Math.round(crest.applyAsDouble(x, 0));
         var above = planner.sample(x + offset, lip + offset - 12, 180);
         var below = planner.sample(x + offset, lip + offset + 12, 120);
         assertTrue(above.wet() && below.wet(), "Dry waterfall approach at x=" + x);
         assertEquals(179, above.water(), 2, "Broken upper pool at x=" + x);
         assertEquals(119, below.water(), 2, "Raised lower pool at x=" + x);
      }
   }

   @Test void coarseFootprintKeepsTheCurtainWhenDemAndMappedLipDiffer() {
      OsmWaterFeature river = feature(20, false, OsmWaterKind.RIVER,
         280, 220, 744, 220, 744, 800, 280, 800, 280, 220);
      OsmWaterFeature fall = feature(21, true, OsmWaterKind.WATERFALL, 280, 460, 744, 460);
      OsmWaterFeature line = feature(27, true, OsmWaterKind.RIVER, 512, 230, 512, 790);
      InlandHydrologyPlanner planner = planner((x, z) -> z < 482 ? 180 : 120, 30, river, fall, line);
      assertFalse(planner.sample(512, 480, 180).falling(), "The cell center is still in the upper pool");
      var coarse = planner.sampleFootprint(512, 480, 180, 32);
      assertTrue(coarse.wet() && coarse.falling(), "The footprint intersects the displaced curtain");
      assertEquals(179, coarse.water(), 2);
      assertTrue(coarse.terrain() < 119, "The curtain must contain the entire drop before fluid ticks");
      planner.clear();
      assertEquals(coarse, planner.sampleFootprint(512, 480, 180, 32), "Request order must not change the LOD");
   }

   @Test void waterfallDoesNotFlattenANearbySeparateStream() {
      OsmWaterFeature river = feature(22, false, OsmWaterKind.RIVER,
         280, 220, 744, 220, 744, 800, 280, 800, 280, 220);
      OsmWaterFeature fall = feature(23, true, OsmWaterKind.WATERFALL, 280, 460, 744, 460);
      OsmWaterFeature stream = feature(24, true, OsmWaterKind.STREAM, 790, 400, 990, 400);
      OsmWaterFeature line = feature(28, true, OsmWaterKind.RIVER, 512, 230, 512, 790);
      DoubleBinaryOperator dem = (x, z) -> x >= 760 ? 145 : z < 460 ? 180 : 120;
      var ordinary = planner(dem, 30, river, stream, line).sample(880, 400, 145);
      var withFall = planner(dem, 30, river, stream, fall, line).sample(880, 400, 145);
      assertTrue(ordinary.wet() && withFall.wet());
      assertEquals(ordinary.water(), withFall.water());
      assertFalse(withFall.falling());
   }

   @Test void isolatedDemPitDoesNotPunctureTheUpperWaterfallPool() {
      OsmWaterFeature river = feature(25, false, OsmWaterKind.RIVER,
         280, 220, 744, 220, 744, 800, 280, 800, 280, 220);
      OsmWaterFeature fall = feature(26, true, OsmWaterKind.WATERFALL, 280, 460, 744, 460);
      OsmWaterFeature line = feature(29, true, OsmWaterKind.RIVER, 512, 230, 512, 790);
      var planner = planner((x, z) -> z < 460 && !(Math.abs(x - 512) <= 1 && Math.abs(z - 430) <= 1) ? 180 : 120,
         30, river, fall, line);
      var pit = planner.sample(512, 430, 120);
      assertTrue(pit.wet());
      assertEquals(179, pit.water(), 2);
      assertFalse(pit.falling());
   }

   /**
    * Lake Quill: a cirque lake whose terrain model blurs the walls 40 blocks up into the water along the outline, and
    * an outlet mapped from the shore whose terrain starts at that blurred height. The lake fills its outline and the
    * outlet leaves at the lake's level instead of high on the slope above it.
    */
   @Test void lakeFillsABlurredShoreAndItsOutletLeavesAtTheLakeLevel() {
      OsmWaterFeature lake = feature(40, false, OsmWaterKind.LAKE, 0, 0, 400, 0, 400, 400, 0, 400, 0, 0);
      OsmWaterFeature outlet = feature(41, true, OsmWaterKind.STREAM, 200, 0, 200, -300);
      InlandHydrologyPlanner planner = planner((x, z) -> {
         double edge = Math.min(Math.min(x, 400 - x), Math.min(z, 400 - z));
         if (edge >= 0) return edge >= 60 ? 100.0 : 140.0 - edge * 40.0 / 60.0;
         if (z < 0 && Math.abs(x - 200) < 40) return 140.0 + z * 0.2;
         return 140.0 - edge * 1.5;
      }, 30, lake, outlet);
      int lakeWater = planner.sample(200, 200, 100).water();
      InlandHydrologyPlanner.Column shore = planner.sample(200, 10, 134);
      assertTrue(shore.wet() && shore.water() == lakeWater, "Blurred shore left dry: " + shore);
      for (int z = -10; z >= -40; z -= 10) {
         InlandHydrologyPlanner.Column column = planner.sample(200, z, (int)Math.ceil(140.0 + z * 0.2));
         assertTrue(column.wet() && column.water() <= lakeWater, "Outlet above its lake (" + lakeWater + ") at z=" + z + ": " + column);
      }
   }

   @Test void unmeasurableBanksAtLargeScaleOnlyReadNearbyMap() {
      // A mapped river narrower than half a block has no measurable banks, so every bank search
      // widens to its limit. At 1:30 that limit is 4 km of ground, not 4096 blocks (123 km).
      double scale = 30.0;
      double[] outline = {200, 500, 800, 500, 800, 500.3, 200, 500.3, 200, 500};
      double[] lon = new double[outline.length / 2], lat = new double[lon.length];
      for (int i = 0; i < lon.length; i++) {
         lon[i] = EarthProjection.blockXToLongitude(outline[i * 2], scale);
         lat[i] = EarthProjection.blockZToLat(outline[i * 2 + 1], scale);
      }
      OsmWaterFeature river = new OsmWaterFeature(30, false, false, OsmWaterKind.RIVER, new double[][]{lon}, new double[][]{lat});
      List<int[]> queries = new java.util.ArrayList<>();
      InlandWaterInput recording = new InlandWaterInput() {
         private final InlandWaterInput delegate = input(river);
         public TellusOsmWaterSource.WaterQueryResult waterForAreaWithStatus(int x, int z, int xx, int zz,
            double s, int margin, OsmQueryMode mode) {
            synchronized (queries) { queries.add(new int[]{x, z, xx, zz}); }
            return delegate.waterForAreaWithStatus(x, z, xx, zz, s, margin, mode);
         }
         public TellusOsmWaterSource.WaterQueryResult connectedWaterBody(OsmWaterFeature seed, double s) {
            return delegate.connectedWaterBody(seed, s);
         }
         public TellusOsmWaterSource.WaterQueryResult connectedWaterfall(OsmWaterFeature seed, double s) {
            return delegate.connectedWaterfall(seed, s);
         }
      };
      InlandHydrologyPlanner planner = new InlandHydrologyPlanner(recording, scale, 24,
         (x, z) -> new InlandHydrologyPlanner.Elevation(100 + Math.abs(z - 500) * 0.5, 1), (x, z) -> Integer.MIN_VALUE,
         (x, z) -> true, (m, z) -> m);
      assertTrue(planner.sample(500, 500, 100).managed());
      assertFalse(queries.isEmpty());
      for (int[] query : queries) {
         assertTrue(query[0] >= 200 - 1024 && query[2] <= 800 + 1024 && query[1] >= 500 - 1024 && query[3] <= 500 + 1024,
            "Map read far from the river: " + java.util.Arrays.toString(query));
      }
   }

   static OsmWaterFeature translated(OsmWaterFeature feature, int offset) {
      double[] pts = new double[feature.pointCount(0) * 2];
      for (int i = 0; i < pts.length / 2; i++) {
         pts[i * 2] = EarthProjection.longitudeToBlockX(feature.lonAt(0, i), 1) + offset;
         pts[i * 2 + 1] = EarthProjection.latToBlockZ(feature.latAt(0, i), 1) + offset;
      }
      return feature(feature.featureId(), feature.lineGeometry(), feature.kind(), pts);
   }

   static void assertConnected(InlandHydrologyPlanner planner, int ax, int az, int bx, int bz,
      int minX, int minZ, int maxX, int maxZ) {
      int width = maxX - minX + 1, height = maxZ - minZ + 1;
      boolean[] seen = new boolean[width * height];
      ArrayDeque<Integer> queue = new ArrayDeque<>();
      // River routing can move several blocks within the valley.
      for (int z = az - 8; z <= az + 8; z++) for (int x = ax - 8; x <= ax + 8; x++) {
         if (x < minX || x > maxX || z < minZ || z > maxZ || !planner.sample(x, z, 100).wet()) continue;
         int c = (z - minZ) * width + x - minX; seen[c] = true; queue.add(c);
      }
      while (!queue.isEmpty()) {
         int c = queue.removeFirst(), x = minX + c % width, z = minZ + c / width;
         if (Math.abs(x - bx) <= 8 && Math.abs(z - bz) <= 8) return;
         for (int d = 0; d < 8; d += 2) {
            int nx = x + InlandHydrologyPlanner.DX[d], nz = z + InlandHydrologyPlanner.DZ[d];
            if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
            int n = (nz - minZ) * width + nx - minX;
            if (!seen[n] && planner.sample(nx, nz, 100).wet()) { seen[n] = true; queue.add(n); }
         }
      }
      fail("No connected water path from " + ax + "," + az + " to " + bx + "," + bz);
   }
}
