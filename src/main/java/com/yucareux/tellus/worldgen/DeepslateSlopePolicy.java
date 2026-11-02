package com.yucareux.tellus.worldgen;

/**
 * Which steep DEM slopes show bare bedrock, and how deep that rock layer goes. The rock itself comes from
 * {@link StrataPolicy}; deepslate is only used where the local rock calls for it.
 */
public final class DeepslateSlopePolicy {
   private static final long COVERAGE_SALT = -4942790177534073029L;
   private static final int MAX_SURFACE_DEPTH = 65536;

   private DeepslateSlopePolicy() {
   }

   public static double coveragePercent(double slopeDegrees) {
      if (!Double.isFinite(slopeDegrees) || slopeDegrees <= 40.0) {
         return 0.0;
      } else if (slopeDegrees >= 65.0) {
         return 100.0;
      } else {
         return (slopeDegrees - 40.0) * 4.0;
      }
   }

   /**
    * Partial coverage forms connected bands of exposed rock rather than a
    * per-block sprinkle, so near terrain and every LOD detail level agree.
    */
   public static boolean shouldCover(int worldX, int worldZ, double slopeDegrees) {
      return SurfacePatchNoise.covers(worldX, worldZ, COVERAGE_SALT, coveragePercent(slopeDegrees));
   }

   public static int surfaceDepthForRelief(int baseDepth, int localReliefBlocks) {
      int normalizedBaseDepth = Math.min(MAX_SURFACE_DEPTH, Math.max(1, baseDepth));
      if (localReliefBlocks < normalizedBaseDepth) {
         return normalizedBaseDepth;
      }

      return localReliefBlocks >= MAX_SURFACE_DEPTH ? MAX_SURFACE_DEPTH : localReliefBlocks + 1;
   }
}
