package com.yucareux.tellus.worldgen;

/**
 * Known landlocked seas whose water surface must not inherit the global ocean level.
 * Callers still decide whether a column is ocean water; this class only supplies a
 * geographically scoped surface-height override for those already-water columns.
 */
public final class LandlockedSeaLevel {
   public static final double CASPIAN_SURFACE_ELEVATION_METERS = -28.0;

   // Published geographic extent of the Caspian basin (approximately
   // 36°34'35"-47°13' N, 46°38'39"-54°44' E).
   private static final double CASPIAN_MIN_LATITUDE = 36.576;
   private static final double CASPIAN_MAX_LATITUDE = 47.217;
   private static final double CASPIAN_MIN_LONGITUDE = 46.644;
   private static final double CASPIAN_MAX_LONGITUDE = 54.734;

   private LandlockedSeaLevel() {
   }

   public static double surfaceElevationMetersAtBlock(int blockX, int blockZ, WorldProjection projection) {
      if (!(projection.worldScale() > 0.0)) {
         return Double.NaN;
      }

      double longitude = projection.blockXToLon(blockX);
      double latitude = projection.blockZToLat(blockZ);
      return surfaceElevationMeters(longitude, latitude);
   }

   public static double surfaceElevationMeters(double longitude, double latitude) {
      return isCaspian(longitude, latitude) ? CASPIAN_SURFACE_ELEVATION_METERS : Double.NaN;
   }

   static boolean isCaspian(double longitude, double latitude) {
      return longitude >= CASPIAN_MIN_LONGITUDE
         && longitude <= CASPIAN_MAX_LONGITUDE
         && latitude >= CASPIAN_MIN_LATITUDE
         && latitude <= CASPIAN_MAX_LATITUDE;
   }
}
