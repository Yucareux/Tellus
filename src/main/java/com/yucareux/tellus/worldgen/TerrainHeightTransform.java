package com.yucareux.tellus.worldgen;

/**
 * Converts real-world elevations into Tellus terrain heights.
 *
 * <p>When automatic height scaling is enabled, terrain receives the Mercator
 * correction needed to keep its vertical and horizontal scales equal at the
 * sampled latitude. Increase Height worlds use a land-priority profile while
 * that correction is active. If it is disabled, the unused height above
 * Everest is reassigned to the ocean and abyssal depths receive a much larger
 * compressed range.</p>
 */
public final class TerrainHeightTransform {
   public static final double EXPERIMENTAL_LATITUDE_CORRECTION_CAP_AT_SCALE_ONE = 1.6;
   public static final double EXPERIMENTAL_LAND_SOFT_CEILING_START_BLOCKS = 10_000.0;
   public static final double EXPERIMENTAL_LAND_SOFT_CEILING_BLOCKS = 10_240.0;
   public static final double EXPERIMENTAL_OCEAN_DEPTH_LIMIT_BLOCKS = 384.0;
   public static final double EXPERIMENTAL_DEEP_OCEAN_DEPTH_LIMIT_BLOCKS = 1_520.0;

   private TerrainHeightTransform() {
   }

   public static double scaledElevationBlocks(
      double elevationMeters,
      double blockZ,
      WorldProjection projection,
      double terrestrialHeightScale,
      double oceanicHeightScale,
      boolean experimentalIncreaseHeight,
      boolean automaticHeightScaling
   ) {
      double worldScale = projection.worldScale();
      if (!Double.isFinite(elevationMeters) || !(worldScale > 0.0)) {
         return Double.NaN;
      }

      return scaleAndCompress(
         elevationMeters,
         worldScale,
         terrestrialHeightScale,
         oceanicHeightScale,
         experimentalIncreaseHeight,
         automaticHeightScaling,
         heightScaleCorrection(blockZ, projection, experimentalIncreaseHeight, automaticHeightScaling)
      );
   }

   public static double scaledElevationBlocksAtLatitude(
      double elevationMeters,
      double latitude,
      double worldScale,
      double terrestrialHeightScale,
      double oceanicHeightScale,
      boolean experimentalIncreaseHeight,
      boolean automaticHeightScaling
   ) {
      if (!Double.isFinite(elevationMeters) || !(worldScale > 0.0)) {
         return Double.NaN;
      }

      return scaleAndCompress(
         elevationMeters,
         worldScale,
         terrestrialHeightScale,
         oceanicHeightScale,
         experimentalIncreaseHeight,
         automaticHeightScaling,
         heightScaleCorrectionAtLatitude(latitude, worldScale, experimentalIncreaseHeight, automaticHeightScaling)
      );
   }

   private static double scaleAndCompress(
      double elevationMeters,
      double worldScale,
      double terrestrialHeightScale,
      double oceanicHeightScale,
      boolean experimentalIncreaseHeight,
      boolean automaticHeightScaling,
      double correction
   ) {
      double heightScale = elevationMeters >= 0.0 ? terrestrialHeightScale : oceanicHeightScale;
      double scaled = elevationMeters * heightScale * correction / worldScale;
      if (!experimentalIncreaseHeight) {
         return scaled;
      }

      return elevationMeters >= 0.0
         ? compressExperimentalLandHeight(scaled)
         : -compressExperimentalOceanDepth(-scaled, automaticHeightScaling);
   }

   public static double heightScaleCorrection(
      double blockZ, WorldProjection projection, boolean experimentalIncreaseHeight, boolean automaticHeightScaling
   ) {
      if (!automaticHeightScaling) {
         return 1.0;
      }

      return capExperimentalCorrection(
         projection.heightScaleCorrection(blockZ),
         projection.worldScale(),
         experimentalIncreaseHeight
      );
   }

   public static int blockOffset(
      double elevationMeters,
      double blockZ,
      WorldProjection projection,
      double terrestrialHeightScale,
      double oceanicHeightScale,
      boolean experimentalIncreaseHeight,
      boolean automaticHeightScaling
   ) {
      double scaled = scaledElevationBlocks(
         elevationMeters,
         blockZ,
         projection,
         terrestrialHeightScale,
         oceanicHeightScale,
         experimentalIncreaseHeight,
         automaticHeightScaling
      );
      return roundedAwayFromSeaLevel(elevationMeters, scaled);
   }

   public static int blockOffsetAtLatitude(
      double elevationMeters,
      double latitude,
      double worldScale,
      double terrestrialHeightScale,
      double oceanicHeightScale,
      boolean experimentalIncreaseHeight,
      boolean automaticHeightScaling
   ) {
      double scaled = scaledElevationBlocksAtLatitude(
         elevationMeters,
         latitude,
         worldScale,
         terrestrialHeightScale,
         oceanicHeightScale,
         experimentalIncreaseHeight,
         automaticHeightScaling
      );
      return roundedAwayFromSeaLevel(elevationMeters, scaled);
   }

   public static double heightScaleCorrectionAtLatitude(
      double latitude, double worldScale, boolean experimentalIncreaseHeight, boolean automaticHeightScaling
   ) {
      if (!automaticHeightScaling) {
         return 1.0;
      }

      return capExperimentalCorrection(
         EarthProjection.heightScaleCorrectionAtLatitude(latitude),
         worldScale,
         experimentalIncreaseHeight
      );
   }

   private static double capExperimentalCorrection(
      double exact, double worldScale, boolean experimentalIncreaseHeight
   ) {
      if (!experimentalIncreaseHeight) {
         return exact;
      }

      double scaleAwareCap = Math.max(1.0, EXPERIMENTAL_LATITUDE_CORRECTION_CAP_AT_SCALE_ONE * worldScale);
      return Math.min(exact, scaleAwareCap);
   }

   static double compressExperimentalLandHeight(double heightBlocks) {
      if (!(heightBlocks > EXPERIMENTAL_LAND_SOFT_CEILING_START_BLOCKS)) {
         return heightBlocks;
      }

      double span = EXPERIMENTAL_LAND_SOFT_CEILING_BLOCKS - EXPERIMENTAL_LAND_SOFT_CEILING_START_BLOCKS;
      double excess = heightBlocks - EXPERIMENTAL_LAND_SOFT_CEILING_START_BLOCKS;
      return EXPERIMENTAL_LAND_SOFT_CEILING_START_BLOCKS + span * -Math.expm1(-excess / span);
   }

   static double compressExperimentalOceanDepth(double depthBlocks, boolean automaticHeightScaling) {
      if (!(depthBlocks > 0.0)) {
         return Math.max(0.0, depthBlocks);
      }

      double limit = experimentalOceanDepthLimit(automaticHeightScaling);
      return limit * Math.tanh(depthBlocks / limit);
   }

   public static double experimentalOceanDepthLimit(boolean automaticHeightScaling) {
      return automaticHeightScaling
         ? EXPERIMENTAL_OCEAN_DEPTH_LIMIT_BLOCKS
         : EXPERIMENTAL_DEEP_OCEAN_DEPTH_LIMIT_BLOCKS;
   }

   private static int roundedAwayFromSeaLevel(double elevationMeters, double scaled) {
      if (!Double.isFinite(scaled)) {
         return 0;
      }

      return elevationMeters >= 0.0 ? (int)Math.ceil(scaled) : (int)Math.floor(scaled);
   }
}
