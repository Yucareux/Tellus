package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.osm.OsmWaterKind;
import java.util.function.DoubleBinaryOperator;
import org.junit.jupiter.api.Test;
import static com.yucareux.tellus.worldgen.InlandHydrologyRegressionTest.feature;
import static com.yucareux.tellus.worldgen.InlandHydrologyRegressionTest.planner;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Generation artifacts found by scanning cached real regions: water held on raised beds between
 * walls ("water bridges"), trenches through divides, and cliffs where bank shaping stops.
 */
class InlandHydrologyArtifactTest {
   /** Water may replace the ground's top block, or stand at most one block above it. */
   private static void assertNotRaised(InlandHydrologyPlanner planner, DoubleBinaryOperator dem, int x, int z) {
      int raw = (int)Math.ceil(dem.applyAsDouble(x, z));
      var column = planner.sample(x, z, raw);
      if (!column.wet() || column.falling()) return;
      assertTrue(column.water() <= raw + 1, "Water held above the ground at " + x + "," + z + ": " + column + " over " + raw);
      assertTrue(column.terrain() <= raw, "Raised bed at " + x + "," + z + ": " + column + " over " + raw);
   }

   /** The water of the nearest wet column within three blocks; routes may run a block or two aside. */
   private static int water(InlandHydrologyPlanner planner, DoubleBinaryOperator dem, int x, int z) {
      for (int radius = 0; radius <= 3; radius++)
         for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
            var column = planner.sample(x + dx, z + dz, (int)Math.ceil(dem.applyAsDouble(x + dx, z + dz)));
            if (column.wet()) return column.water();
         }
      return fail("Missing water near " + x + "," + z);
   }

   @Test void streamsJoinedOverAColKeepTheFarValleyAtItsOwnLevel() {
      // One mapped line runs from the far valley's head down to its low point, up over a col and down
      // the near valley (waterways are drawn downstream, but a mis-joined line can climb a col); the far
      // valley drains south. A single fit from the lowest end used to hold the whole far valley at a
      // level pooled with the col.
      DoubleBinaryOperator dem = (x, z) -> {
         double along = x < 420 ? 100 + (x - 100) * 50.0 / 320 : x < 500 ? 150 + (x - 420)
            : x < 620 ? 230 - (x - 500) * 100.0 / 120 : 130 + (x - 620) * 90.0 / 280;
         double main = along + Math.abs(z - 500) * 0.6;
         double south = z >= 500 ? 130 - (z - 500) * 15.0 / 400 + Math.abs(x - 620) * 0.6 : Double.POSITIVE_INFINITY;
         return Math.min(main, south);
      };
      OsmWaterFeature main = feature(40, true, OsmWaterKind.STREAM, 900, 500, 100, 500);
      OsmWaterFeature south = feature(41, true, OsmWaterKind.STREAM, 620, 500, 620, 900);
      var planner = planner(dem, 30, main, south);
      for (int x = 104; x <= 896; x += 2) for (int z = 494; z <= 506; z += 2) assertNotRaised(planner, dem, x, z);
      for (int z = 500; z <= 896; z += 2) for (int x = 614; x <= 626; x += 2) assertNotRaised(planner, dem, x, z);
      assertEquals(129, water(planner, dem, 620, 500), 3, "The far valley's low point drains south at its own level");
      assertEquals(124, water(planner, dem, 260, 500), 3, "The near valley is not dug down towards the far outlet");
   }

   @Test void valleyFloorBelowEveryEndDrainsToItsLowPoint() {
      // Both mapped ends lie well above the valley floor between them, and nothing lower is mapped.
      DoubleBinaryOperator dem = (x, z) -> (x < 500 ? 140 - (x - 100) * 0.1 : 100 + (x - 500) * 0.125) + Math.abs(z - 500) * 0.6;
      var planner = planner(dem, 30, feature(42, true, OsmWaterKind.RIVER, 100, 500, 900, 500));
      for (int x = 104; x <= 896; x += 2) for (int z = 494; z <= 506; z += 2) assertNotRaised(planner, dem, x, z);
      assertEquals(99, water(planner, dem, 500, 500), 2, "The floor is not flooded up to the lower end's level");
   }

   @Test void lowerPoolDoesNotHoldUpTheRiverDescendingBelowTheFall() {
      // Below a mapped fall the river keeps descending; the lower pool is measured some way below
      // the crest and used to hold the whole channel within the fall's influence at that level.
      DoubleBinaryOperator dem = (x, z) -> (z < 460 ? 180 : 140 - (z - 460) * 0.5) + Math.abs(x - 512) * 0.8;
      OsmWaterFeature river = feature(43, true, OsmWaterKind.RIVER, 512, 200, 512, 900);
      OsmWaterFeature fall = feature(44, true, OsmWaterKind.WATERFALL, 470, 460, 554, 460);
      var planner = planner(dem, 30, river, fall);
      for (int z = 470; z <= 760; z += 2) for (int x = 508; x <= 516; x += 2) assertNotRaised(planner, dem, x, z);
   }

   @Test void banksContinueAroundTheOutsideOfABendWithoutCliffs() {
      // A narrow stream in a steep V-shaped valley, so banks carve deeply. On the outside of a
      // right-angle bend the bank used to stop in wedges of untouched terrain, each a cliff.
      DoubleBinaryOperator dem = (x, z) -> 100 + (x <= 500 ? (500 - x) * 0.05 : 0) - (z >= 500 ? (z - 500) * 0.02 : 0) + bendDistance(x, z) * 1.5;
      var planner = planner(dem, 30, feature(45, true, OsmWaterKind.STREAM, 200, 500, 500, 500, 500, 800));
      int[][] terrain = new int[121][121], raw = new int[121][121];
      for (int dz = 0; dz <= 120; dz++) for (int dx = 0; dx <= 120; dx++) {
         int x = 440 + dx, z = 440 + dz;
         raw[dz][dx] = (int)Math.ceil(dem.applyAsDouble(x, z));
         var column = planner.sample(x, z, raw[dz][dx]);
         terrain[dz][dx] = column.wet() ? Integer.MIN_VALUE : column.terrain();
      }
      for (int dz = 0; dz <= 120; dz++) for (int dx = 0; dx < 120; dx++) {
         if (terrain[dz][dx + 1] == Integer.MIN_VALUE || terrain[dz][dx] == Integer.MIN_VALUE) continue;
         int made = Math.abs(terrain[dz][dx + 1] - terrain[dz][dx]) - Math.abs(raw[dz][dx + 1] - raw[dz][dx]);
         assertTrue(made < 4, "Cliff at " + (440 + dx) + "," + (440 + dz) + " of " + made + " blocks");
      }
      for (int dz = 0; dz < 120; dz++) for (int dx = 0; dx <= 120; dx++) {
         if (terrain[dz + 1][dx] == Integer.MIN_VALUE || terrain[dz][dx] == Integer.MIN_VALUE) continue;
         int made = Math.abs(terrain[dz + 1][dx] - terrain[dz][dx]) - Math.abs(raw[dz + 1][dx] - raw[dz][dx]);
         assertTrue(made < 4, "Cliff at " + (440 + dx) + "," + (440 + dz) + " of " + made + " blocks");
      }
   }

   /** Distance to the polyline (200,500)-(500,500)-(500,800). */
   private static double bendDistance(double x, double z) {
      double first = x <= 500 ? Math.abs(z - 500) : Math.hypot(x - 500, z - 500);
      double second = z >= 500 ? Math.abs(x - 500) : Math.hypot(x - 500, z - 500);
      return Math.min(first, second);
   }

   @Test void streamHeadEndsInARoundedValleyNotAStraightCliff() {
      // The stream ends partway up a steep valley; its banks used to stop at a straight line there.
      DoubleBinaryOperator dem = (x, z) -> 100 + (x - 200) * 0.05 + (x <= 600 ? Math.abs(z - 500) : Math.hypot(x - 600, z - 500)) * 1.6;
      var planner = planner(dem, 30, feature(46, true, OsmWaterKind.STREAM, 200, 500, 600, 500));
      for (int z = 504; z <= 512; z += 2) assertNoCliffAlong(planner, dem, z);
   }

   private static void assertNoCliffAlong(InlandHydrologyPlanner planner, DoubleBinaryOperator dem, int z) {
      int previous = Integer.MIN_VALUE, previousRaw = 0;
      for (int x = 580; x <= 650; x++) {
         int raw = (int)Math.ceil(dem.applyAsDouble(x, z));
         var column = planner.sample(x, z, raw);
         if (column.wet()) { previous = Integer.MIN_VALUE; continue; }
         int terrain = column.terrain();
         if (previous != Integer.MIN_VALUE)
            assertTrue(Math.abs(terrain - previous) - Math.abs(raw - previousRaw) < 4, "Cliff behind the head at " + x + "," + z);
         previous = terrain; previousRaw = raw;
      }
   }

   @Test void pondSmallerThanABlockLeavesNoDryCrater() {
      DoubleBinaryOperator dem = (x, z) -> 100 + (z - 500) * 0.4;
      OsmWaterFeature pond = feature(47, false, OsmWaterKind.POND, 500.2, 500.2, 500.8, 500.2, 500.8, 500.8, 500.2, 500.8, 500.2, 500.2);
      var planner = planner(dem, 30, pond);
      for (int z = 470; z <= 530; z++) for (int x = 470; x <= 530; x++) {
         var column = planner.sample(x, z, (int)Math.ceil(dem.applyAsDouble(x, z)));
         assertFalse(column.wet() || column.bank(), "A pond without any water column shapes " + x + "," + z + ": " + column);
      }
   }

   @Test void pondOnASlopeDoesNotSpreadOntoTheGroundBelowIt() {
      DoubleBinaryOperator dem = (x, z) -> 100 + (z - 500) * 2.0;
      OsmWaterFeature pond = feature(48, false, OsmWaterKind.POND, 494, 494, 506, 494, 506, 506, 494, 506, 494, 494);
      var planner = planner(dem, 30, pond);
      for (int z = 480; z <= 520; z++) for (int x = 480; x <= 520; x++) assertNotRaised(planner, dem, x, z);
   }

   @Test void thinDiagonalUntypedPolygonIsRiverEvidence() {
      double[] diagonal = {100, 100, 106, 94, 706, 694, 700, 700, 100, 100};
      assertTrue(new HydrologyWaterMap.Shape(feature(49, false, OsmWaterKind.UNKNOWN, diagonal), 1.0).river,
         "A long, thin polygon is a river at any orientation");
      double[] square = {100, 100, 400, 100, 400, 400, 100, 400, 100, 100};
      assertFalse(new HydrologyWaterMap.Shape(feature(50, false, OsmWaterKind.UNKNOWN, square), 1.0).river);
      double[] lake = {100, 100, 1000, 100, 1000, 400, 100, 400, 100, 100};
      assertFalse(new HydrologyWaterMap.Shape(feature(51, false, OsmWaterKind.UNKNOWN, lake), 1.0).river);
   }

   @Test void lakeIslandShoreMeetsTheWater() {
      // A mapped island (a hole in the lake, wound the other way) rising gently from the lake's flat surface. Its
      // shore was left unshaped, two blocks above the water, while the lake's own shores meet it.
      double[][] rings = {{300, 300, 700, 300, 700, 700, 300, 700, 300, 300},
         {460.5, 460.5, 460.5, 539.5, 539.5, 539.5, 539.5, 460.5, 460.5, 460.5}};
      double[][] lon = new double[2][], lat = new double[2][];
      for (int r = 0; r < 2; r++) {
         lon[r] = new double[rings[r].length / 2]; lat[r] = new double[lon[r].length];
         for (int i = 0; i < lon[r].length; i++) {
            lon[r][i] = EarthProjection.blockXToLongitude(rings[r][i * 2], 1.0);
            lat[r][i] = EarthProjection.blockZToLat(rings[r][i * 2 + 1], 1.0);
         }
      }
      DoubleBinaryOperator dem = (x, z) -> 100 + 0.3 * Math.max(0, Math.min(Math.min(x - 460.5, 539.5 - x), Math.min(z - 460.5, 539.5 - z)));
      var planner = planner(dem, 30, new OsmWaterFeature(54, false, false, OsmWaterKind.LAKE, lon, lat));
      int[][] shoreAndWater = {{500, 461, 500, 460}, {500, 539, 500, 540}, {461, 500, 460, 500}, {539, 500, 540, 500}};
      for (int[] s : shoreAndWater) {
         var water = planner.sample(s[2], s[3], (int)Math.ceil(dem.applyAsDouble(s[2], s[3])));
         var shore = planner.sample(s[0], s[1], (int)Math.ceil(dem.applyAsDouble(s[0], s[1])));
         assertTrue(water.wet() && !shore.wet(), "Island shore at " + s[0] + "," + s[1] + ": " + shore + " beside " + water);
         assertEquals(water.water(), shore.terrain(), "Island shore at " + s[0] + "," + s[1] + " does not meet the water");
      }
      var top = planner.sample(500, 500, 112);
      assertFalse(top.wet(), "The island stays land");
      assertEquals(112, top.terrain(), "The island's top keeps its terrain");
   }

   @Test void lakeWithArmsAtDifferentHeightsIsNotWalledIn() {
      // The Great Salt Lake at 1:1: the terrain model shows its arms (and a receded bed) at different heights. Set by
      // the higher arm, most of the lake, the water stood six blocks above the lower arm's shore, held in by a wall.
      OsmWaterFeature lake = feature(55, false, OsmWaterKind.LAKE, 300, 300, 700, 300, 700, 700, 300, 700, 300, 300);
      DoubleBinaryOperator arms = (x, z) -> (x < 420 ? 100 : 106)
         + (x < 300 || x > 700 || z < 300 || z > 700 ? 0.5 + 0.05 * Math.max(Math.max(300 - x, x - 700), Math.max(300 - z, z - 700)) : 0);
      var planner = planner(arms, 30, lake);
      assertEquals(planner.sample(350, 500, 100).water(), planner.sample(600, 500, 106).water(), "One lake, one level");
      int[][] lowShore = {{299, 500}, {295, 400}, {290, 600}, {350, 299}, {380, 701}};
      for (int[] s : lowShore) {
         int raw = (int)Math.ceil(arms.applyAsDouble(s[0], s[1]));
         var column = planner.sample(s[0], s[1], raw);
         assertTrue(!column.wet() && column.terrain() <= raw, "Walled shore at " + s[0] + "," + s[1] + ": " + column + " over " + raw);
      }
      // A flat lake whose outline runs down a slope on one side keeps the level of its own water.
      DoubleBinaryOperator slope = (x, z) -> x < 310 ? 100 - 2.0 * (310 - x)
         : 100 + 0.05 * Math.max(0, Math.max(x - 700, Math.max(300 - z, z - 700)));
      assertEquals(99, planner(slope, 30, lake).sample(500, 500, 100).water(), "A slope below the outline sank the lake");
   }

   @Test void riverBesideAHigherLakeKeepsItsOwnLevel() {
      // A lake on a terrace a few blocks from a river is not that river's inlet or outlet.
      DoubleBinaryOperator dem = (x, z) -> z < 494 ? 130 : 100 + x * 0.01 + Math.abs(z - 500) * 0.8;
      OsmWaterFeature river = feature(52, true, OsmWaterKind.RIVER, 900, 500, 100, 500);
      OsmWaterFeature lake = feature(53, false, OsmWaterKind.LAKE, 400, 470, 600, 470, 600, 492, 400, 492, 400, 470);
      var planner = planner(dem, 30, river, lake);
      for (int x = 380; x <= 620; x += 2) for (int z = 497; z <= 503; z++) assertNotRaised(planner, dem, x, z);
      assertEquals(104, water(planner, dem, 500, 500), 2);
   }
}
