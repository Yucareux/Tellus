package com.yucareux.tellus.worldgen;

/**
 * Dense mixed-radix BlockPos packing for Tellus true-height worlds.
 *
 * <p>A fixed X/Z/Y bit split cannot represent the complete 1:1 Web Mercator
 * square and Tellus' true-height range in 64 bits. This profile instead packs
 * only the coordinates that a 1:1 Tellus world can actually use. The low four
 * bits remain the Y-within-section value so {@code BlockPos.getFlatIndex}
 * continues to work.</p>
 */
public final class HighYPackedCoordinateProfile {
   public static final String PROFILE_PROPERTY = "tellus.experimentalHeight.coordinateProfile";
   public static final String PROFILE_ID = "global_mercator_dense_v1";

   /** Compatibility metadata exposed by BlockPos and DimensionType. */
   public static final int HORIZONTAL_BITS = 26;
   public static final int Y_BITS = 14;

   /**
    * floor(+-180 * EarthProjection.METERS_PER_DEGREE) at world scale 1.
    * Web Mercator has the same projected extent on both horizontal axes.
    */
   public static final int X_MIN = -20_037_509;
   public static final int X_MAX = 20_037_508;
   public static final int Z_MIN = X_MIN;
   public static final int Z_MAX = X_MAX;
   public static final long X_SIZE = (long)X_MAX - X_MIN + 1L;
   public static final long Z_SIZE = (long)Z_MAX - Z_MIN + 1L;
   public static final long HORIZONTAL_POSITION_COUNT = X_SIZE * Z_SIZE;

   /**
    * Covers the true-height dimension plus 192 blocks of transient engine safety on each side.
    * The lower safety range is stored in the old uppermost safety slots so every in-dimension
    * packed value remains byte-for-byte compatible with existing worlds.
    */
   public static final int Y_MIN = -2_240;
   public static final int Y_MAX = 9_231;
   public static final int Y_SIZE = Y_MAX - Y_MIN + 1;
   public static final int TELLUS_DIMENSION_MIN_Y = -2_048;
   public static final int TELLUS_DIMENSION_MAX_Y = 9_039;
   public static final int TELLUS_DIMENSION_Y_SIZE = TELLUS_DIMENSION_MAX_Y - TELLUS_DIMENSION_MIN_Y + 1;
   public static final int PACKED_SAFETY_BLOCKS = 192;

   private static final int LOWER_SAFETY_SLOT_START = TELLUS_DIMENSION_Y_SIZE + PACKED_SAFETY_BLOCKS;

   /**
    * Broad global codec bounds required by vanilla density functions and standard Tellus dimension resources.
    * Actual Increase Height dimensions remain inside the dense packed Y range above.
    */
   public static final int DIMENSION_MIN_Y = -4_080;
   public static final int DIMENSION_MAX_Y = 12_271;
   public static final int DIMENSION_Y_SIZE = DIMENSION_MAX_Y - DIMENSION_MIN_Y + 1;

   private static final String REQUESTED_PROFILE = System.getProperty(PROFILE_PROPERTY, PROFILE_ID).trim();
   private static final boolean ENABLED = PROFILE_ID.equals(REQUESTED_PROFILE);

   static {
      validateAxisSizes(X_SIZE, Z_SIZE);
      validateYAlignment(Y_SIZE, Y_MIN);
      long maximumColumn = Long.divideUnsigned(-1L, Y_SIZE);
      int maximumColumnRemainder = (int)Long.remainderUnsigned(-1L, Y_SIZE);
      long lastColumn = HORIZONTAL_POSITION_COUNT - 1L;
      if (lastColumn > maximumColumn || lastColumn == maximumColumn && Y_SIZE - 1 > maximumColumnRemainder) {
         throw new ExceptionInInitializerError("Dense Tellus coordinate profile exceeds 64 bits");
      }
   }

   private static void validateAxisSizes(long xSize, long zSize) {
      if (xSize != zSize) {
         throw new ExceptionInInitializerError("Dense Tellus coordinate profile requires equal Mercator axis sizes");
      }
   }

   private static void validateYAlignment(int ySize, int yMin) {
      if ((ySize & 15) != 0 || (yMin & 15) != 0) {
         throw new ExceptionInInitializerError("Dense Tellus Y range must preserve the low four section-local Y bits");
      }
   }

   private HighYPackedCoordinateProfile() {
   }

   public static boolean isEnabled() {
      return ENABLED;
   }

   public static String requestedProfile() {
      return REQUESTED_PROFILE;
   }

   public static String launchPropertyInstruction() {
      return "-D" + PROFILE_PROPERTY + "=" + PROFILE_ID;
   }

   public static long pack(int x, int y, int z) {
      if (!containsBlock(x, y, z)) {
         throw new IllegalArgumentException(
            "Coordinate outside dense Tellus packed range: (" + x + "," + y + "," + z + "); supported " + describeBounds()
         );
      }

      long column = ((long)z - Z_MIN) * X_SIZE + ((long)x - X_MIN);
      long yIndex = y >= TELLUS_DIMENSION_MIN_Y
         ? (long)y - TELLUS_DIMENSION_MIN_Y
         : LOWER_SAFETY_SLOT_START + (long)y - Y_MIN;
      // Overflow past Long.MAX_VALUE is intentional: the value is an unsigned 64-bit index.
      return column * Y_SIZE + yIndex;
   }

   /**
    * Packs an arbitrary transient {@code BlockPos} lookup without allowing an
    * out-of-world camera or entity position to terminate the game. Vanilla
    * accepts those positions in {@code BlockPos.asLong}; clamp them to the
    * nearest representable edge while keeping {@link #pack} strict for world
    * data and validation.
    */
   public static long packClamped(int x, int y, int z) {
      return pack(
         Math.max(X_MIN, Math.min(X_MAX, x)),
         Math.max(Y_MIN, Math.min(Y_MAX, y)),
         Math.max(Z_MIN, Math.min(Z_MAX, z))
      );
   }

   public static int unpackX(long packed) {
      long column = unpackColumn(packed);
      return (int)(column % X_SIZE) + X_MIN;
   }

   public static int unpackY(long packed) {
      int yIndex = (int)Long.remainderUnsigned(packed, Y_SIZE);
      return yIndex < LOWER_SAFETY_SLOT_START
         ? yIndex + TELLUS_DIMENSION_MIN_Y
         : yIndex - LOWER_SAFETY_SLOT_START + Y_MIN;
   }

   public static int unpackZ(long packed) {
      long column = unpackColumn(packed);
      return (int)(column / X_SIZE) + Z_MIN;
   }

   public static boolean containsBlock(int x, int y, int z) {
      return containsHorizontal(x, z) && y >= Y_MIN && y <= Y_MAX;
   }

   public static boolean containsHorizontal(int x, int z) {
      return x >= X_MIN && x <= X_MAX && z >= Z_MIN && z <= Z_MAX;
   }

   public static boolean containsHorizontalRange(int minX, int maxX, int minZ, int maxZ) {
      return minX >= X_MIN && maxX <= X_MAX && minZ >= Z_MIN && maxZ <= Z_MAX;
   }

   public static boolean isCanonicalPackedValue(long packed) {
      return unpackColumn(packed) < HORIZONTAL_POSITION_COUNT;
   }

   public static String describeBounds() {
      return "X=" + X_MIN + ".." + X_MAX + ", Z=" + Z_MIN + ".." + Z_MAX + ", Y=" + Y_MIN + ".." + Y_MAX;
   }

   private static long unpackColumn(long packed) {
      return Long.divideUnsigned(packed, Y_SIZE);
   }
}
