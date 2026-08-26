package com.yucareux.tellus.world.data.osm;

import com.yucareux.tellus.worldgen.WorldProjection;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeSupportLayoutTest {
   @Test
   void movedWorldSeamDoesNotTurnShortBridgeIntoPlanetSpanningBridge() {
      WorldProjection projection = WorldProjection.centeredOn(30.0, 37.7459, -119.5332);
      double seamLongitude = WorldProjection.wrapLongitude(projection.originLongitude() + 180.0);
      RoadFeature bridge = new RoadFeature(
         1L,
         RoadClass.MAIN,
         RoadMode.BRIDGE,
         1,
         "primary",
         new double[]{seamLongitude - 0.0001, seamLongitude + 0.0001},
         new double[]{37.0, 37.0}
      );

      assertTrue(BridgeSupportLayout.crossesWorldSeam(bridge, projection));
      List<BridgeSupportLayout.SupportPlacement> supports = new ArrayList<>();
      BridgeSupportLayout.forEachSupport(bridge, projection, 8, supports::add);
      assertTrue(supports.isEmpty(), "one-point runs on either side of the seam cannot place supports");
   }

   @Test
   void ordinaryBridgeStillProducesBoundedSupports() {
      WorldProjection projection = WorldProjection.centeredOn(30.0, 37.7459, -119.5332);
      RoadFeature bridge = new RoadFeature(
         2L,
         RoadClass.MAIN,
         RoadMode.BRIDGE,
         1,
         "primary",
         new double[]{-119.54, -119.53, -119.52},
         new double[]{37.74, 37.74, 37.74}
      );

      assertFalse(BridgeSupportLayout.crossesWorldSeam(bridge, projection));
      List<BridgeSupportLayout.SupportPlacement> supports = new ArrayList<>();
      BridgeSupportLayout.forEachSupport(bridge, projection, 8, supports::add);
      assertFalse(supports.isEmpty());
      assertTrue(supports.size() < 100);
      assertTrue(supports.stream().allMatch(support -> Math.abs(support.centerX()) < 100.0));
   }
}
