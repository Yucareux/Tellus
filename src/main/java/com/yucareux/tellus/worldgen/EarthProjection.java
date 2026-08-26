package com.yucareux.tellus.worldgen;

/**
 * Spherical Mercator kernel shared by every {@link WorldProjection}.
 *
 * <p>Everything that depends on a world's block origin (block{@code <->}latitude/longitude conversion,
 * the block-row height correction, ground metres per block) lives on {@link WorldProjection}; this class
 * only holds the origin-independent constants and latitude formulas.</p>
 */
public final class EarthProjection {
   public static final double METERS_PER_DEGREE = 111319.49166666667;
   public static final double EQUATOR_CIRCUMFERENCE_METERS = METERS_PER_DEGREE * 360.0;
   public static final double MAX_MERCATOR_LATITUDE = 85.05112878;
   static final double EARTH_RADIUS_METERS = METERS_PER_DEGREE * 180.0 / Math.PI;
   private static final double MAX_MERCATOR_Y_METERS = EARTH_RADIUS_METERS * mercatorYRatio(MAX_MERCATOR_LATITUDE);
   private static final double MAX_LEGACY_Y_METERS = MAX_MERCATOR_LATITUDE * METERS_PER_DEGREE;
   private static final EarthProjection.ProjectionMode PROJECTION_MODE = resolveMode(System.getProperty("tellus.projection.mode", "mercator"));

   private EarthProjection() {
   }

   /**
    * Blocks spanned by one degree of longitude at the equator. This is only a resolution heuristic;
    * coordinate conversion must go through {@link WorldProjection} so the world origin is applied.
    */
   public static double equatorialBlocksPerDegree(double worldScale) {
      return worldScale <= 0.0 ? 0.0 : METERS_PER_DEGREE / worldScale;
   }

   public static double worldScaleFromBlocksPerDegree(double blocksPerDegree) {
      return blocksPerDegree <= 0.0 ? 0.0 : METERS_PER_DEGREE / blocksPerDegree;
   }

   /**
    * Returns the vertical terrain multiplier needed to keep Mercator-projected landscapes at the same
    * local scale in all three axes: spherical Mercator enlarges both horizontal axes by
    * {@code sec(latitude)}, so elevation is multiplied by the same factor. Legacy projection mode keeps
    * its historical uncorrected vertical scale.
    */
   public static double heightScaleCorrectionAtLatitude(double latitude) {
      if (PROJECTION_MODE == EarthProjection.ProjectionMode.LEGACY) {
         return 1.0;
      }

      double latitudeRadians = Math.toRadians(clampLatitude(latitude));
      return 1.0 / Math.cos(latitudeRadians);
   }

   public static double clampLatitude(double latitude) {
      return Math.max(-MAX_MERCATOR_LATITUDE, Math.min(MAX_MERCATOR_LATITUDE, latitude));
   }

   public static String projectionModeId() {
      return PROJECTION_MODE.id();
   }

   static boolean isLegacyMode() {
      return PROJECTION_MODE == EarthProjection.ProjectionMode.LEGACY;
   }

   /**
    * Projected northing in metres for a latitude: the Mercator {@code R ln(tan(pi/4 + lat/2))}, or the
    * equirectangular {@code lat * METERS_PER_DEGREE} in legacy mode. Callers clamp the latitude first.
    */
   static double northingMeters(double latitude) {
      if (isLegacyMode()) {
         return latitude * METERS_PER_DEGREE;
      }

      return EARTH_RADIUS_METERS * mercatorYRatio(latitude);
   }

   static double latitudeFromNorthingMeters(double northing) {
      if (isLegacyMode()) {
         return northing / METERS_PER_DEGREE;
      }

      return Math.toDegrees(Math.atan(Math.sinh(northing / EARTH_RADIUS_METERS)));
   }

   /** Northing of {@link #MAX_MERCATOR_LATITUDE}: half the height of the Web Mercator square. */
   static double maxNorthingMeters() {
      return isLegacyMode() ? MAX_LEGACY_Y_METERS : MAX_MERCATOR_Y_METERS;
   }

   private static EarthProjection.ProjectionMode resolveMode(String value) {
      if (value == null) {
         return EarthProjection.ProjectionMode.MERCATOR;
      } else {
         return "legacy".equalsIgnoreCase(value.trim()) ? EarthProjection.ProjectionMode.LEGACY : EarthProjection.ProjectionMode.MERCATOR;
      }
   }

   private static double mercatorYRatio(double latitude) {
      double latitudeRadians = Math.toRadians(latitude);
      return Math.log(Math.tan(Math.PI * 0.25 + latitudeRadians * 0.5));
   }

   private static enum ProjectionMode {
      LEGACY("legacy"),
      MERCATOR("mercator");

      private final String id;

      private ProjectionMode(String id) {
         this.id = id;
      }

      private String id() {
         return this.id;
      }
   }
}
