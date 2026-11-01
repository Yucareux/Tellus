package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.osm.OsmWaterKind;
import java.util.function.DoubleBinaryOperator;
import org.junit.jupiter.api.Test;
import static com.yucareux.tellus.worldgen.InlandHydrologyRegressionTest.feature;
import static com.yucareux.tellus.worldgen.InlandHydrologyRegressionTest.planner;
import static org.junit.jupiter.api.Assertions.*;

/** Falls follow the terrain down their drop, as the elevation model shows it, and leave the terrain around them as it is. */
class WaterfallTerrainTest {
   /** A plateau at 180 dropping 30 blocks over 24 (z 400 to 424) to a floor: a cliff the model's resolution blurs. */
   private static double blurredCliff(double x, double z) {
      return 180.0 - Math.max(0.0, Math.min(30.0, (z - 400.0) * 1.25)) - z * 0.005 + Math.min(4.0, Math.abs(x - 512) * 0.2);
   }

   @Test void streamRunsDownABlurredCliffAsTheTerrainShowsIt() {
      OsmWaterFeature stream = feature(1, true, OsmWaterKind.STREAM, 512, 200, 512, 700);
      InlandHydrologyPlanner planner = planner(WaterfallTerrainTest::blurredCliff, 30, stream);
      int previous = Integer.MAX_VALUE;
      for (int z = 396; z <= 430; z++) {
         int raw = (int)Math.ceil(blurredCliff(512, z));
         InlandHydrologyPlanner.Column column = planner.sample(512, z, raw);
         assertTrue(column.wet(), "No water at z=" + z);
         // Down the slope, not held level to its top and then cut down to its foot.
         assertTrue(column.water() >= raw - 3 && column.water() <= raw + (column.falling() ? 2 : 0), "Water off the terrain at z=" + z + ": " + column);
         assertTrue(column.terrain() >= raw - 4, "Slope dug out at z=" + z + ": " + column);
         assertTrue(column.water() <= previous + (column.falling() ? 2 : 0), "Water rises at z=" + z);
         if (!column.falling()) previous = column.water();
      }
   }

   @Test void streamKeepsToASlopeTheNetworkReadsLower() {
      // A steady slope the network's coarser model reads 8 blocks low: its level steps down a cell at a time below the
      // terrain, which used to drop the water sheer at each step into a trench.
      DoubleBinaryOperator dem = (x, z) -> 400.0 - z * 0.5 + Math.min(6.0, Math.abs(x - 512) * 0.5);
      OsmWaterFeature stream = feature(7, true, OsmWaterKind.STREAM, 512, 100, 512, 900);
      InlandHydrologyPlanner planner = new InlandHydrologyPlanner(InlandHydrologyRegressionTest.input(stream), 1.0, 24,
         new InlandHydrologyPlanner.TerrainSampler() {
            @Override public InlandHydrologyPlanner.Elevation sample(int x, int z) {
               return new InlandHydrologyPlanner.Elevation(dem.applyAsDouble(x, z), 2);
            }

            @Override public InlandHydrologyPlanner.Elevation sampleAtResolution(int x, int z, double meters) {
               return new InlandHydrologyPlanner.Elevation(dem.applyAsDouble(x, z) - 8.0, meters);
            }
         }, (x, z) -> Integer.MIN_VALUE, (x, z) -> true, (m, z) -> m);
      for (int z = 300; z <= 700; z++) {
         int raw = (int)Math.ceil(dem.applyAsDouble(512, z));
         InlandHydrologyPlanner.Column column = planner.sample(512, z, raw);
         assertTrue(column.wet() && !column.falling(), "Fall on a steady slope at z=" + z + ": " + column);
         assertTrue(column.water() >= raw - 3, "Water in a trench at z=" + z + ": " + column);
      }
   }

   @Test void riverAreaRunsDownABlurredCliffAsTheTerrainShowsIt() {
      OsmWaterFeature area = feature(2, false, OsmWaterKind.RIVER, 400, 200, 624, 200, 624, 700, 400, 700, 400, 200);
      OsmWaterFeature river = feature(3, true, OsmWaterKind.RIVER, 512, 210, 512, 690);
      InlandHydrologyPlanner planner = planner(WaterfallTerrainTest::blurredCliff, 30, area, river);
      for (int x = 480; x <= 544; x += 16) for (int z = 402; z <= 422; z += 2) {
         int raw = (int)Math.ceil(blurredCliff(x, z));
         InlandHydrologyPlanner.Column column = planner.sample(x, z, raw);
         assertTrue(column.wet(), "No water at " + x + "," + z);
         // Neither the pool above held out over the slope nor the pool below cut into it: no terraces.
         assertTrue(column.water() >= raw - 3 && column.water() <= raw + (column.falling() ? 3 : 0), "Water off the terrain at " + x + "," + z + ": " + column);
         // Shallow water over the fall, the slope under it not dug out.
         assertTrue(column.terrain() >= raw - 4, "Slope dug out at " + x + "," + z + ": " + column);
      }
   }

   @Test void fallOverACliffDigsNoPlungePool() {
      OsmWaterFeature area = feature(4, false, OsmWaterKind.RIVER, 400, 200, 624, 200, 624, 700, 400, 700, 400, 200);
      OsmWaterFeature river = feature(5, true, OsmWaterKind.RIVER, 512, 210, 512, 690);
      InlandHydrologyPlanner planner = planner((x, z) -> z < 460 ? 180 : 120, 8, area, river);
      InlandHydrologyPlanner.Column curtain = planner.sample(512, 460, 120);
      assertTrue(curtain.falling() && curtain.water() >= 178, "No curtain at the cliff: " + curtain);
      assertTrue(curtain.terrain() >= 117, "The curtain stands in a pit: " + curtain);
      // The pool at its foot is no deeper than the river further down.
      int far = planner.sample(512, 640, 120).terrain();
      for (int z = 462; z <= 490; z += 4) assertTrue(planner.sample(512, z, 120).terrain() >= far, "Plunge pool at z=" + z);
   }

   @Test void streamOverACliffCutsNoNotchIntoItsLip() {
      OsmWaterFeature stream = feature(6, true, OsmWaterKind.STREAM, 800, 500, 1200, 500);
      InlandHydrologyPlanner planner = planner((x, z) -> (x < 1000 ? 200.0 : 100.0) - x * 0.01 + Math.min(3.0, Math.abs(z - 500) * 0.2), 2, stream);
      boolean falls = false;
      for (int x = 990; x <= 1010; x++) {
         int raw = (int)Math.ceil((x < 1000 ? 200.0 : 100.0) - x * 0.01);
         InlandHydrologyPlanner.Column column = planner.sample(x, 500, raw);
         falls |= column.falling();
         // The ground under the water keeps its height on both sides of the cliff.
         assertTrue(column.terrain() >= raw - 4, "Notch or pit at x=" + x + ": " + column);
      }
      assertTrue(falls, "No curtain over the cliff");
   }
}
