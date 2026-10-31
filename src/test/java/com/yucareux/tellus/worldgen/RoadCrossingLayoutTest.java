package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RoadCrossingLayoutTest {
   private static final double DIAGONAL = Math.sqrt(0.5);

   @Test
   void snapsCrossingsOnTheRoadToTheirStation() {
      double[] stations = RoadCrossingLayout.crossingStations(
         new double[]{0.0, 20.0, 20.0}, new double[]{0.0, 0.0, 20.0}, new double[]{12.0, 20.5, 10.0}, new double[]{0.4, 5.0, 8.0}
      );

      assertArrayEquals(new double[]{12.0, 25.0}, stations, 1.0E-9);
   }

   @Test
   void stripesAlternateAcrossAStraightRoadOnlyInsideTheBand() {
      double[] crossings = {10.0};
      for (int lateral = -4; lateral <= 4; lateral++) {
         assertEquals(lateral % 2 == 0, RoadCrossingLayout.marking(crossings, 11.0, lateral, 1.0, 0.0, false), "lateral " + lateral);
      }

      assertFalse(RoadCrossingLayout.marking(crossings, 13.0, 0.0, 1.0, 0.0, false));
      assertTrue(RoadCrossingLayout.marking(crossings, 13.0, 1.0, 1.0, 0.0, true));
   }

   @Test
   void diagonalStripesAreSolidTwoCellStaircases() {
      double[] crossings = {0.0};
      for (int x = -3; x <= 3; x++) {
         for (int z = -3; z <= 3; z++) {
            double station = (x + z) * DIAGONAL;
            if (Math.abs(station) > 2.0) {
               continue;
            }

            int diagonal = z - x;
            boolean expected = Math.floorMod(Math.floorDiv(diagonal + 1, 2), 2) == 0;
            assertEquals(expected, RoadCrossingLayout.marking(crossings, station, diagonal * DIAGONAL, DIAGONAL, DIAGONAL, false), x + "," + z);
         }
      }
   }
}
