package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.preload.TerrainPreloadArea;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.DoublePredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.DimensionType;

public final class ExperimentalHeightSupport {
   private static final double SCALE_DIAGNOSTIC_INCREMENT = 0.001;
   private static final int MINIMUM_SCALE_SEARCH_STEPS = 64;

   private ExperimentalHeightSupport() {
   }

   public static boolean isRuntimeProfileActive() {
      return HighYPackedCoordinateProfile.isEnabled();
   }

   public static String launchPropertyInstruction() {
      return HighYPackedCoordinateProfile.launchPropertyInstruction();
   }

   public static void validateActiveRuntimeProfileOrThrow() {
      if (!HighYPackedCoordinateProfile.isEnabled()) {
         return;
      }

      List<String> failures = new ArrayList<>();
      validateRuntimeProfile(failures);
      validateDimensionHeightLimits(
         new EarthGeneratorSettings.HeightLimits(
            HighYPackedCoordinateProfile.TELLUS_DIMENSION_MIN_Y,
            HighYPackedCoordinateProfile.TELLUS_DIMENSION_Y_SIZE,
            HighYPackedCoordinateProfile.TELLUS_DIMENSION_Y_SIZE
         ),
         failures
      );
      validatePackedRoundTrip(
         HighYPackedCoordinateProfile.X_MIN,
         HighYPackedCoordinateProfile.Y_MIN,
         HighYPackedCoordinateProfile.Z_MIN,
         failures
      );
      validatePackedRoundTrip(
         HighYPackedCoordinateProfile.X_MAX,
         HighYPackedCoordinateProfile.Y_MAX,
         HighYPackedCoordinateProfile.Z_MAX,
         failures
      );
      validatePackedFlatIndex(-7_271_424, -113, -10_025_677, failures);
      if (!failures.isEmpty()) {
         throw new IllegalStateException("Dense Tellus packed-coordinate profile failed runtime validation: " + String.join("; ", failures));
      }
   }

   public static void configureWorldBorder(EarthGeneratorSettings settings, WorldBorder border) {
      Objects.requireNonNull(settings, "settings");
      Objects.requireNonNull(border, "border");
      if (settings.experimentalIncreaseHeight()) {
         int absoluteLimit = Math.max(-HighYPackedCoordinateProfile.X_MIN, HighYPackedCoordinateProfile.X_MAX + 1);
         border.setAbsoluteMaxSize(absoluteLimit);
      }
   }

   public static void validateOrThrow(EarthGeneratorSettings settings, EarthGeneratorSettings.HeightLimits limits) {
      if (!settings.experimentalIncreaseHeight()) {
         return;
      }

      List<String> failures = new ArrayList<>();
      validateRuntimeProfile(failures);
      validateDimensionHeightLimits(limits, failures);
      int minY = limits.minY();
      int maxY = limits.minY() + limits.height() - 1;
      validatePackedRoundTrip(0, minY, 0, failures);
      validatePackedRoundTrip(0, maxY, 0, failures);
      validatePackedRoundTrip(HighYPackedCoordinateProfile.X_MAX, maxY, HighYPackedCoordinateProfile.Z_MAX, failures);
      validatePackedRoundTrip(HighYPackedCoordinateProfile.X_MIN, minY, HighYPackedCoordinateProfile.Z_MIN, failures);
      validatePackedRoundTrip(9_672_000, 8848, 3_248_000, failures);
      validatePackedRoundTrip(15_827_000, -384, 1_271_000, failures);
      validatePackedRoundTrip(-17_330_081, 10_240, -2_144_470, failures);
      if (!failures.isEmpty()) {
         throw new IllegalStateException(
            "Increase Height cannot be enabled safely on this Minecraft build: " + String.join("; ", failures)
         );
      }

      validateConfiguredSpawnOrThrow(settings);
   }

   public static void validateConfiguredSpawnOrThrow(EarthGeneratorSettings settings) {
      Objects.requireNonNull(settings, "settings");
      if (!settings.experimentalIncreaseHeight()) {
         return;
      }

      WorldProjection projection = settings.projection();
      int spawnX = projectedBlockX(settings.spawnLongitude(), projection);
      int spawnZ = projectedBlockZ(settings.spawnLatitude(), projection);
      if (HighYPackedCoordinateProfile.containsHorizontal(spawnX, spawnZ)) {
         return;
      }

      double minimumScale = findMinimumSupportedWorldScale(
         settings.worldScale(),
         scale -> HighYPackedCoordinateProfile.containsHorizontal(
            projectedBlockX(settings.spawnLongitude(), projection.withWorldScale(scale)),
            projectedBlockZ(settings.spawnLatitude(), projection.withWorldScale(scale))
         )
      );
      throw horizontalRangeException(
         spawnX,
         spawnX,
         spawnZ,
         spawnZ,
         "configured spawn lat=" + settings.spawnLatitude() + ", lon=" + settings.spawnLongitude(),
         settings.worldScale(),
         minimumScale
      );
   }

   public static void validatePreloadAreaOrThrow(EarthGeneratorSettings settings, TerrainPreloadArea area) {
      Objects.requireNonNull(settings, "settings");
      Objects.requireNonNull(area, "area");
      if (
         !settings.experimentalIncreaseHeight()
            || HighYPackedCoordinateProfile.containsHorizontalRange(area.minBlockX(), area.maxBlockX(), area.minBlockZ(), area.maxBlockZ())
      ) {
         return;
      }

      double minimumScale = findMinimumSupportedWorldScale(
         area.worldScale(), scale -> {
            TerrainPreloadArea scaledArea = TerrainPreloadArea.centered(
               area.centerLatitude(),
               area.centerLongitude(),
               area.chunksPerSide(),
               settings.projection().withWorldScale(scale)
            );
            return HighYPackedCoordinateProfile.containsHorizontalRange(
               scaledArea.minBlockX(), scaledArea.maxBlockX(), scaledArea.minBlockZ(), scaledArea.maxBlockZ()
            );
         }
      );
      throw horizontalRangeException(
         area.minBlockX(),
         area.maxBlockX(),
         area.minBlockZ(),
         area.maxBlockZ(),
         "preload area "
            + area.chunksPerSide()
            + "x"
            + area.chunksPerSide()
            + " chunks centered at lat="
            + area.centerLatitude()
            + ", lon="
            + area.centerLongitude(),
         area.worldScale(),
         minimumScale
      );
   }

   public static void validateHorizontalRangeOrThrow(EarthGeneratorSettings settings, int minX, int maxX, int minZ, int maxZ, String context) {
      if (!settings.experimentalIncreaseHeight() || HighYPackedCoordinateProfile.containsHorizontalRange(minX, maxX, minZ, maxZ)) {
         return;
      }

      throw horizontalRangeException(minX, maxX, minZ, maxZ, context, Double.NaN, Double.NaN);
   }

   public static boolean isHorizontalRangeSupported(EarthGeneratorSettings settings, int minX, int maxX, int minZ, int maxZ) {
      return !settings.experimentalIncreaseHeight() || HighYPackedCoordinateProfile.containsHorizontalRange(minX, maxX, minZ, maxZ);
   }

   public static void validateHorizontalPositionOrThrow(EarthGeneratorSettings settings, int x, int z, String context) {
      validateHorizontalRangeOrThrow(settings, x, x, z, z, context);
   }

   public static void validateBlockPositionOrThrow(EarthGeneratorSettings settings, int x, int y, int z, String context) {
      if (!settings.experimentalIncreaseHeight() || HighYPackedCoordinateProfile.containsBlock(x, y, z)) {
         return;
      }

      throw new IllegalStateException(
         "Increase Height coordinate is outside the active packed-coordinate profile for "
            + context
            + ": requested ("
            + x
            + ","
            + y
            + ","
            + z
            + "); supported "
            + HighYPackedCoordinateProfile.describeBounds()
      );
   }

   private static void validateRuntimeProfile(List<String> failures) {
      if (!HighYPackedCoordinateProfile.isEnabled()) {
         String requested = HighYPackedCoordinateProfile.requestedProfile();
         failures.add(
            "launch with "
               + HighYPackedCoordinateProfile.launchPropertyInstruction()
               + " to enable the experimental packed-coordinate profile"
               + (requested.isBlank() ? "" : " (current value: '" + requested + "')")
         );
      }

      if (BlockPos.PACKED_Y_LENGTH != HighYPackedCoordinateProfile.Y_BITS) {
         failures.add("BlockPos.PACKED_Y_LENGTH=" + BlockPos.PACKED_Y_LENGTH + ", expected " + HighYPackedCoordinateProfile.Y_BITS);
      }

      MinecraftVersionCompat.validatePackedHorizontalLength(failures);
   }

   private static void validateDimensionHeightLimits(EarthGeneratorSettings.HeightLimits limits, List<String> failures) {
      int minY = limits.minY();
      int maxY = limits.minY() + limits.height() - 1;
      if (DimensionType.BITS_FOR_Y != HighYPackedCoordinateProfile.Y_BITS) {
         failures.add("DimensionType.BITS_FOR_Y=" + DimensionType.BITS_FOR_Y + ", expected " + HighYPackedCoordinateProfile.Y_BITS);
      }

      if (DimensionType.Y_SIZE != HighYPackedCoordinateProfile.DIMENSION_Y_SIZE) {
         failures.add("DimensionType.Y_SIZE=" + DimensionType.Y_SIZE + ", expected " + HighYPackedCoordinateProfile.DIMENSION_Y_SIZE);
      }

      if (
         DimensionType.MIN_Y != HighYPackedCoordinateProfile.DIMENSION_MIN_Y
            || DimensionType.MAX_Y != HighYPackedCoordinateProfile.DIMENSION_MAX_Y
      ) {
         failures.add(
            "DimensionType codec bounds="
               + DimensionType.MIN_Y
               + ".."
               + DimensionType.MAX_Y
               + ", expected "
               + HighYPackedCoordinateProfile.DIMENSION_MIN_Y
               + ".."
               + HighYPackedCoordinateProfile.DIMENSION_MAX_Y
         );
      }

      if (DimensionType.MIN_Y > minY || DimensionType.MAX_Y < maxY || DimensionType.Y_SIZE < limits.height()) {
         failures.add(
            "DimensionType height range "
               + DimensionType.MIN_Y
               + ".."
               + DimensionType.MAX_Y
               + " (size "
               + DimensionType.Y_SIZE
               + ") does not cover Tellus limits "
               + minY
               + ".."
               + maxY
               + " (height "
               + limits.height()
               + ")"
         );
      }
   }

   private static int projectedBlockX(double longitude, WorldProjection projection) {
      return Mth.floor(projection.lonToBlockX(longitude));
   }

   private static int projectedBlockZ(double latitude, WorldProjection projection) {
      return Mth.floor(projection.latToBlockZ(latitude));
   }

   private static double findMinimumSupportedWorldScale(double currentScale, DoublePredicate supportedAtScale) {
      double low = Double.isFinite(currentScale) && currentScale > 0.0 ? currentScale : 0.0;
      double high = Math.max(1.0, low);
      while (!supportedAtScale.test(high)) {
         if (high >= Double.MAX_VALUE * 0.5) {
            return Double.NaN;
         }

         high *= 2.0;
      }

      for (int step = 0; step < MINIMUM_SCALE_SEARCH_STEPS; step++) {
         double midpoint = low + (high - low) * 0.5;
         if (supportedAtScale.test(midpoint)) {
            high = midpoint;
         } else {
            low = midpoint;
         }
      }

      return high;
   }

   private static IllegalStateException horizontalRangeException(
      int minX, int maxX, int minZ, int maxZ, String context, double currentWorldScale, double minimumWorldScale
   ) {
      StringBuilder message = new StringBuilder(
         "Increase Height coordinate range is outside the active packed-coordinate profile for "
      );
      message.append(context)
         .append(": requested X=")
         .append(minX)
         .append("..")
         .append(maxX)
         .append(", Z=")
         .append(minZ)
         .append("..")
         .append(maxZ)
         .append("; supported ")
         .append(HighYPackedCoordinateProfile.describeBounds());
      if (Double.isFinite(minimumWorldScale)) {
         double safeScale = Math.ceil(minimumWorldScale / SCALE_DIAGNOSTIC_INCREMENT) * SCALE_DIAGNOSTIC_INCREMENT;
         message.append(
            String.format(
               Locale.ROOT,
               "; world scale %.3f is too small here; increase world scale to at least %.3f, disable Increase Height, or choose an in-range location",
               currentWorldScale,
               safeScale
            )
         );
      }

      return new IllegalStateException(message.toString());
   }

   private static void validatePackedRoundTrip(int x, int y, int z, List<String> failures) {
      long packed = BlockPos.asLong(x, y, z);
      BlockPos unpacked = BlockPos.of(packed);
      if (unpacked.getX() != x || unpacked.getY() != y || unpacked.getZ() != z) {
         failures.add(
            "expected (" + x + "," + y + "," + z + ") but decoded (" + unpacked.getX() + "," + unpacked.getY() + "," + unpacked.getZ() + ")"
         );
      }
   }

   private static void validatePackedFlatIndex(int x, int y, int z, List<String> failures) {
      long flat = BlockPos.getFlatIndex(BlockPos.asLong(x, y, z));
      BlockPos unpacked = BlockPos.of(flat);
      int expectedY = Math.floorDiv(y, 16) * 16;
      if (unpacked.getX() != x || unpacked.getY() != expectedY || unpacked.getZ() != z) {
         failures.add(
            "flat index for ("
               + x
               + ","
               + y
               + ","
               + z
               + ") decoded ("
               + unpacked.getX()
               + ","
               + unpacked.getY()
               + ","
               + unpacked.getZ()
               + ")"
         );
      }
   }
}
