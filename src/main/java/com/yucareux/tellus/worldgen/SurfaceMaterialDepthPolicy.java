package com.yucareux.tellus.worldgen;

/**
 * Deterministic, world-seeded variation for natural surface material columns.
 * The low-frequency component keeps adjacent columns from looking striped while
 * the detail component gives the boundaries an irregular, vanilla-like shape.
 */
public final class SurfaceMaterialDepthPolicy {
   public static final int MIN_FULL_SNOW_DEPTH = 3;
   public static final int MAX_FULL_SNOW_DEPTH = 8;
   private static final int NATURAL_BROAD_CELL_BLOCKS = 23;
   private static final int NATURAL_DETAIL_CELL_BLOCKS = 8;
   private static final int POWDER_BROAD_CELL_BLOCKS = 11;
   private static final int POWDER_DETAIL_CELL_BLOCKS = 4;
   private static final double POWDER_SNOW_THRESHOLD = 0.34;
   private static final long NATURAL_BROAD_SALT = 1379410921758401782L;
   private static final long NATURAL_DETAIL_SALT = 8024937712915970328L;
   private static final long SNOW_DEPTH_BROAD_SALT = 5297092466199082147L;
   private static final long SNOW_DEPTH_DETAIL_SALT = 2838927217971765761L;
   private static final long POWDER_BROAD_SALT = 5412538886491830033L;
   private static final long POWDER_DETAIL_SALT = 8582074519071182603L;

   private SurfaceMaterialDepthPolicy() {
   }

   /**
    * Varies a nominal natural surface depth by one block. A nominal vanilla-like
    * depth of four therefore produces three- to five-block columns.
    */
   public static int naturalDepth(int nominalDepth, int worldX, int worldZ, long worldSeed) {
      int safeNominalDepth = Math.max(1, nominalDepth);
      double variation = blendedNoise(
         worldX,
         worldZ,
         worldSeed,
         NATURAL_BROAD_CELL_BLOCKS,
         NATURAL_DETAIL_CELL_BLOCKS,
         NATURAL_BROAD_SALT,
         NATURAL_DETAIL_SALT
      );
      int offset = variation < 0.4 ? -1 : variation > 0.6 ? 1 : 0;
      return Math.max(2, safeNominalDepth + offset);
   }

   /**
    * Resolves both the material and depth of a snow column. Partial slope
    * coverage is always one block deep; continuous snowfields vary from three
    * to eight blocks and can therefore form deeper drifts than ordinary soil.
    */
   public static SnowColumn snowColumn(int worldX, int worldZ, long worldSeed, boolean fullSlopeCoverage) {
      int depth = fullSlopeCoverage ? fullSnowDepth(worldX, worldZ, worldSeed) : 1;
      return new SnowColumn(depth, isPowderSnow(worldX, worldZ, worldSeed));
   }

   private static int fullSnowDepth(int worldX, int worldZ, long worldSeed) {
      double depthNoise = blendedNoise(
         worldX,
         worldZ,
         worldSeed,
         NATURAL_BROAD_CELL_BLOCKS,
         NATURAL_DETAIL_CELL_BLOCKS,
         SNOW_DEPTH_BROAD_SALT,
         SNOW_DEPTH_DETAIL_SALT
      );
      int depthRange = MAX_FULL_SNOW_DEPTH - MIN_FULL_SNOW_DEPTH + 1;
      int depthOffset = Math.min(depthRange - 1, (int)(depthNoise * depthRange));
      return MIN_FULL_SNOW_DEPTH + depthOffset;
   }

   private static boolean isPowderSnow(int worldX, int worldZ, long worldSeed) {
      double broad = smoothValueNoise(worldX, worldZ, POWDER_BROAD_CELL_BLOCKS, worldSeed, POWDER_BROAD_SALT);
      double detail = smoothValueNoise(
         worldX + 37,
         worldZ - 53,
         POWDER_DETAIL_CELL_BLOCKS,
         worldSeed,
         POWDER_DETAIL_SALT
      );
      return broad * 0.68 + detail * 0.32 < POWDER_SNOW_THRESHOLD;
   }

   private static double blendedNoise(
      int worldX,
      int worldZ,
      long worldSeed,
      int broadCellBlocks,
      int detailCellBlocks,
      long broadSalt,
      long detailSalt
   ) {
      double broad = smoothValueNoise(worldX, worldZ, broadCellBlocks, worldSeed, broadSalt);
      double detail = smoothValueNoise(worldX - 29, worldZ + 41, detailCellBlocks, worldSeed, detailSalt);
      return broad * 0.64 + detail * 0.36;
   }

   private static double smoothValueNoise(int worldX, int worldZ, int cellSize, long worldSeed, long salt) {
      int cellX = Math.floorDiv(worldX, cellSize);
      int cellZ = Math.floorDiv(worldZ, cellSize);
      double fractionX = fade((double)Math.floorMod(worldX, cellSize) / (double)cellSize);
      double fractionZ = fade((double)Math.floorMod(worldZ, cellSize) / (double)cellSize);
      double northWest = cellNoise(cellX, cellZ, worldSeed, salt);
      double northEast = cellNoise(cellX + 1, cellZ, worldSeed, salt);
      double southWest = cellNoise(cellX, cellZ + 1, worldSeed, salt);
      double southEast = cellNoise(cellX + 1, cellZ + 1, worldSeed, salt);
      double north = lerp(fractionX, northWest, northEast);
      double south = lerp(fractionX, southWest, southEast);
      return lerp(fractionZ, north, south);
   }

   private static double cellNoise(int cellX, int cellZ, long worldSeed, long salt) {
      long mixed = mix64(worldSeed ^ salt ^ (long)cellX * 341873128712L ^ (long)cellZ * 132897987541L);
      return (double)(mixed >>> 11) * 0x1.0p-53;
   }

   private static double fade(double value) {
      return value * value * value * (value * (value * 6.0 - 15.0) + 10.0);
   }

   private static double lerp(double delta, double start, double end) {
      return start + delta * (end - start);
   }

   private static long mix64(long value) {
      value ^= value >>> 33;
      value *= -49064778989728563L;
      value ^= value >>> 33;
      value *= -4265267296055464877L;
      return value ^ value >>> 33;
   }

   public record SnowColumn(int depth, boolean powderSnow) {
      public SnowColumn {
         if (depth < 1) {
            throw new IllegalArgumentException("depth must be positive");
         }
      }
   }
}
