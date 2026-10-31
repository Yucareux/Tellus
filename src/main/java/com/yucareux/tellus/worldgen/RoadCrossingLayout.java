package com.yucareux.tellus.worldgen;

import java.util.Arrays;

public final class RoadCrossingLayout {
   public static final double[] NO_CROSSINGS = new double[0];
   static final double BAND_HALF_LENGTH = 2.5;
   // Crossing nodes sit on the road's own way, so anything further off belongs to another road.
   static final double SNAP_DISTANCE = 2.0;

   private RoadCrossingLayout() {
   }

   public static double[] crossingStations(double[] xs, double[] zs, double[] crossingXs, double[] crossingZs) {
      double[] stations = new double[crossingXs.length];
      int count = 0;

      for (int c = 0; c < crossingXs.length; c++) {
         double bestDistanceSq = SNAP_DISTANCE * SNAP_DISTANCE;
         double bestStation = Double.NaN;
         double segmentStart = 0.0;
         for (int i = 1; i < xs.length; i++) {
            double dx = xs[i] - xs[i - 1];
            double dz = zs[i] - zs[i - 1];
            double lengthSq = dx * dx + dz * dz;
            if (lengthSq <= 1.0E-6) {
               continue;
            }

            double t = Math.max(0.0, Math.min(1.0, ((crossingXs[c] - xs[i - 1]) * dx + (crossingZs[c] - zs[i - 1]) * dz) / lengthSq));
            double offsetX = xs[i - 1] + t * dx - crossingXs[c];
            double offsetZ = zs[i - 1] + t * dz - crossingZs[c];
            double distanceSq = offsetX * offsetX + offsetZ * offsetZ;
            double length = Math.sqrt(lengthSq);
            if (distanceSq <= bestDistanceSq) {
               bestDistanceSq = distanceSq;
               bestStation = segmentStart + t * length;
            }

            segmentStart += length;
         }

         if (!Double.isNaN(bestStation)) {
            stations[count++] = bestStation;
         }
      }

      return count == 0 ? NO_CROSSINGS : Arrays.copyOf(stations, count);
   }

   public static boolean marking(double[] crossingStations, double station, double lateral, double dirX, double dirZ, boolean laneMarking) {
      for (double crossing : crossingStations) {
         if (Math.abs(station - crossing) <= BAND_HALF_LENGTH) {
            // Bars run with traffic; measuring across in grid steps keeps diagonal bars solid staircases, not dotted lines.
            double across = lateral / (Math.abs(dirX) + Math.abs(dirZ));
            return Math.floorMod((int)Math.floor(across + 0.5), 2) == 0;
         }
      }

      return laneMarking;
   }
}
