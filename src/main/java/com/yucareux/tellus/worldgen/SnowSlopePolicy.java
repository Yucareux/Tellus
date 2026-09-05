package com.yucareux.tellus.worldgen;

public final class SnowSlopePolicy {
   private static final long COVERAGE_SALT = 7640891576956012809L;

   private SnowSlopePolicy() {
   }

   public static int coveragePercent(double slopeDegrees) {
      if (Double.isNaN(slopeDegrees) || slopeDegrees < 45.0) {
         return 100;
      } else if (slopeDegrees < 50.0) {
         return 75;
      } else if (slopeDegrees < 55.0) {
         return 40;
      } else if (slopeDegrees < 60.0) {
         return 10;
      } else {
         return 0;
      }
   }

   public static boolean hasFullCoverage(double slopeDegrees) {
      return coveragePercent(slopeDegrees) == 100;
   }

   public static boolean shouldCover(int worldX, int worldZ, double slopeDegrees) {
      int coverage = coveragePercent(slopeDegrees);
      if (coverage >= 100) {
         return true;
      } else if (coverage <= 0) {
         return false;
      }

      long mixed = mix64(COVERAGE_SALT ^ (long)worldX * 341873128712L ^ (long)worldZ * 132897987541L);
      return Math.floorMod(mixed, 100L) < coverage;
   }

   public static double slopeDegrees(
      double eastElevationMeters,
      double westElevationMeters,
      double northElevationMeters,
      double southElevationMeters,
      double eastWestDistanceMeters,
      double northSouthDistanceMeters
   ) {
      if (!Double.isFinite(eastElevationMeters)
         || !Double.isFinite(westElevationMeters)
         || !Double.isFinite(northElevationMeters)
         || !Double.isFinite(southElevationMeters)
         || !(eastWestDistanceMeters > 0.0)
         || !(northSouthDistanceMeters > 0.0)) {
         return Double.NaN;
      }

      double gradientX = (eastElevationMeters - westElevationMeters) / eastWestDistanceMeters;
      double gradientZ = (southElevationMeters - northElevationMeters) / northSouthDistanceMeters;
      return Math.toDegrees(Math.atan(Math.hypot(gradientX, gradientZ)));
   }

   private static long mix64(long value) {
      value ^= value >>> 33;
      value *= -49064778989728563L;
      value ^= value >>> 33;
      value *= -4265267296055464877L;
      return value ^ value >>> 33;
   }
}
