package com.yucareux.tellus.world.data.osm;

import com.yucareux.tellus.worldgen.WorldProjection;
import java.util.Arrays;
import java.util.function.Consumer;
import net.minecraft.util.Mth;

public final class BridgeSupportLayout {
   private static final double MAIN_TARGET_SPAN_AT_ONE = 18.0;
   private static final double MAIN_TARGET_SPAN_AT_TEN = 8.0;
   private static final double NORMAL_TARGET_SPAN_AT_ONE = 14.0;
   private static final double NORMAL_TARGET_SPAN_AT_TEN = 6.0;
   private static final double MIN_SUPPORT_SPAN_FACTOR = 0.85;

   private BridgeSupportLayout() {
   }

   public static boolean crossesWorldSeam(RoadFeature road, WorldProjection projection) {
      if (road == null || projection == null || road.pointCount() < 2) {
         return false;
      }
      double previousX = projection.lonToBlockX(road.lonAt(0));
      for (int point = 1; point < road.pointCount(); point++) {
         double currentX = projection.lonToBlockX(road.lonAt(point));
         if (projection.crossesWorldSeam(previousX, currentX)) {
            return true;
         }
         previousX = currentX;
      }
      return false;
   }

   public static void forEachSupport(
      RoadFeature road, WorldProjection projection, int roadWidth, Consumer<BridgeSupportLayout.SupportPlacement> consumer
   ) {
      if (road == null || consumer == null || road.pointCount() < 2 || roadWidth <= 0) {
         return;
      }

      double[] worldXs = new double[road.pointCount()];
      double[] worldZs = new double[road.pointCount()];
      for (int i = 0; i < road.pointCount(); i++) {
         worldXs[i] = projection.lonToBlockX(road.lonAt(i));
         worldZs[i] = projection.latToBlockZ(road.latAt(i));
      }

      int runStart = 0;
      for (int i = 1; i < road.pointCount(); i++) {
         if (projection.crossesWorldSeam(worldXs[i - 1], worldXs[i])) {
            emitSupportsForRun(road, projection, roadWidth, consumer, worldXs, worldZs, runStart, i);
            runStart = i;
         }
      }
      emitSupportsForRun(road, projection, roadWidth, consumer, worldXs, worldZs, runStart, road.pointCount());
   }

   private static void emitSupportsForRun(
      RoadFeature road,
      WorldProjection projection,
      int roadWidth,
      Consumer<BridgeSupportLayout.SupportPlacement> consumer,
      double[] allWorldXs,
      double[] allWorldZs,
      int startInclusive,
      int endExclusive
   ) {
      int pointCount = endExclusive - startInclusive;
      if (pointCount < 2) {
         return;
      }

      double[] worldXs = Arrays.copyOfRange(allWorldXs, startInclusive, endExclusive);
      double[] worldZs = Arrays.copyOfRange(allWorldZs, startInclusive, endExclusive);
      double[] segmentLengths = new double[pointCount - 1];
      double totalLength = 0.0;
      for (int i = 1; i < pointCount; i++) {
         double dx = worldXs[i] - worldXs[i - 1];
         double dz = worldZs[i] - worldZs[i - 1];
         double segmentLength = Math.sqrt(dx * dx + dz * dz);
         segmentLengths[i - 1] = segmentLength;
         totalLength += segmentLength;
      }

      if (totalLength <= 1.0E-6) {
         return;
      }

      double targetSpan = targetSpanBlocks(road.roadClass(), projection.worldScale());
      double endInset = endInsetBlocks(roadWidth, targetSpan);
      double usableStart = Math.min(endInset, totalLength * 0.5);
      double usableEnd = Math.max(usableStart, totalLength - endInset);
      double usableSpan = usableEnd - usableStart;
      if (usableSpan < targetSpan * MIN_SUPPORT_SPAN_FACTOR) {
         return;
      }

      int spanCount = Math.max(2, Math.round((float)(usableSpan / targetSpan)));
      int supportCount = spanCount - 1;
      double step = usableSpan / spanCount;

      for (int i = 1; i <= supportCount; i++) {
         double station = usableStart + step * i;
         BridgeSupportLayout.SupportPlacement placement = placementAtStation(station, totalLength, worldXs, worldZs, segmentLengths);
         if (placement != null) {
            consumer.accept(placement);
         }
      }
   }

   public static BridgeSupportLayout.SupportStyle styleFor(RoadClass roadClass, int roadWidth) {
      if (roadClass == RoadClass.MAIN) {
         double capHalfAcross = Math.max(2.5, roadWidth * 0.5 - 0.35);
         double shaftOffset = Math.max(1.8, Math.min(capHalfAcross - 1.1, roadWidth * 0.25));
         return new BridgeSupportLayout.SupportStyle(2, shaftOffset, 0.9, 0.9, 1.55, capHalfAcross, 2, 5);
      } else {
         double capHalfAcross = Math.max(1.75, roadWidth * 0.45);
         return new BridgeSupportLayout.SupportStyle(1, 0.0, 0.9, 0.9, 1.25, capHalfAcross, 1, 4);
      }
   }

   private static double targetSpanBlocks(RoadClass roadClass, double worldScale) {
      double t = Mth.clamp((worldScale - 1.0) / 9.0, 0.0, 1.0);
      return switch (roadClass) {
         case MAIN -> Mth.lerp(t, MAIN_TARGET_SPAN_AT_ONE, MAIN_TARGET_SPAN_AT_TEN);
         case NORMAL, DIRT -> Mth.lerp(t, NORMAL_TARGET_SPAN_AT_ONE, NORMAL_TARGET_SPAN_AT_TEN);
      };
   }

   private static double endInsetBlocks(int roadWidth, double targetSpan) {
      return Math.max(roadWidth * 1.75, targetSpan * 0.5);
   }

   private static BridgeSupportLayout.SupportPlacement placementAtStation(
      double station, double totalLength, double[] worldXs, double[] worldZs, double[] segmentLengths
   ) {
      double clampedStation = Mth.clamp(station, 0.0, totalLength);
      double traversed = 0.0;

      for (int i = 0; i < segmentLengths.length; i++) {
         double length = segmentLengths[i];
         if (length <= 1.0E-6) {
            traversed += length;
         } else if (clampedStation <= traversed + length || i == segmentLengths.length - 1) {
            double t = Mth.clamp((clampedStation - traversed) / length, 0.0, 1.0);
            double x1 = worldXs[i];
            double z1 = worldZs[i];
            double x2 = worldXs[i + 1];
            double z2 = worldZs[i + 1];
            double tangentX = (x2 - x1) / length;
            double tangentZ = (z2 - z1) / length;
            double normalX = -tangentZ;
            double normalZ = tangentX;
            double centerX = Mth.lerp(t, x1, x2);
            double centerZ = Mth.lerp(t, z1, z2);
            return new BridgeSupportLayout.SupportPlacement(clampedStation, totalLength, centerX, centerZ, tangentX, tangentZ, normalX, normalZ);
         } else {
            traversed += length;
         }
      }

      return null;
   }

   public record SupportPlacement(
      double station,
      double totalLength,
      double centerX,
      double centerZ,
      double tangentX,
      double tangentZ,
      double normalX,
      double normalZ
   ) {
   }

   public record SupportStyle(
      int shaftCount,
      double shaftOffset,
      double shaftHalfAlong,
      double shaftHalfAcross,
      double capHalfAlong,
      double capHalfAcross,
      int capThickness,
      int minClearance
   ) {
      public double maxFootprintRadius() {
         double maxAcross = this.shaftCount > 1 ? Math.max(this.capHalfAcross, Math.abs(this.shaftOffset) + this.shaftHalfAcross) : Math.max(this.capHalfAcross, this.shaftHalfAcross);
         double maxAlong = Math.max(this.capHalfAlong, this.shaftHalfAlong);
         return Math.sqrt(maxAlong * maxAlong + maxAcross * maxAcross);
      }
   }
}
