package com.yucareux.tellus.world.data.osm;

import com.yucareux.tellus.worldgen.WorldProjection;
import java.util.Objects;

public final class OsmWaterFeature {
   private static final double LINE_HALF_WIDTH_BLOCKS = 0.5;
   private final long featureId;
   private final boolean lineGeometry;
   private final boolean pointGeometry;
   private final boolean oceanHint;
   private final OsmWaterKind kind;
   private final double[][] longitudes;
   private final double[][] latitudes;
   private final double minLon;
   private final double maxLon;
   private final double minLat;
   private final double maxLat;

   public OsmWaterFeature(long featureId, boolean lineGeometry, boolean oceanHint, double[][] longitudes, double[][] latitudes) {
      this(featureId, lineGeometry, oceanHint, OsmWaterKind.UNKNOWN, longitudes, latitudes);
   }

   public OsmWaterFeature(
      long featureId, boolean lineGeometry, boolean oceanHint, OsmWaterKind kind, double[][] longitudes, double[][] latitudes
   ) {
      this(featureId, lineGeometry, false, oceanHint, kind, longitudes, latitudes);
   }

   OsmWaterFeature(
      long featureId,
      boolean lineGeometry,
      boolean pointGeometry,
      boolean oceanHint,
      OsmWaterKind kind,
      double[][] longitudes,
      double[][] latitudes
   ) {
      this.featureId = featureId;
      this.lineGeometry = lineGeometry;
      this.pointGeometry = pointGeometry;
      if (lineGeometry && pointGeometry) {
         throw new IllegalArgumentException("Water feature cannot be both a line and a point");
      }
      this.kind = Objects.requireNonNullElse(kind, OsmWaterKind.UNKNOWN);
      this.oceanHint = oceanHint || this.kind.ocean();
      this.longitudes = copyParts(Objects.requireNonNull(longitudes, "longitudes"));
      this.latitudes = copyParts(Objects.requireNonNull(latitudes, "latitudes"));
      if (this.longitudes.length != this.latitudes.length || this.longitudes.length == 0) {
         throw new IllegalArgumentException("Water feature requires matching geometry parts");
      } else {
         double lowLon = Double.POSITIVE_INFINITY;
         double highLon = Double.NEGATIVE_INFINITY;
         double lowLat = Double.POSITIVE_INFINITY;
         double highLat = Double.NEGATIVE_INFINITY;

         for (int part = 0; part < this.longitudes.length; part++) {
            double[] lonPart = this.longitudes[part];
            double[] latPart = this.latitudes[part];
            int minPoints = this.pointGeometry ? 1 : this.lineGeometry ? 2 : 4;
            if (lonPart.length != latPart.length || lonPart.length < minPoints) {
               throw new IllegalArgumentException("Water feature part has invalid point count");
            }

            for (int point = 0; point < lonPart.length; point++) {
               double lon = lonPart[point];
               double lat = latPart[point];
               lowLon = Math.min(lowLon, lon);
               highLon = Math.max(highLon, lon);
               lowLat = Math.min(lowLat, lat);
               highLat = Math.max(highLat, lat);
            }
         }

         this.minLon = lowLon;
         this.maxLon = highLon;
         this.minLat = lowLat;
         this.maxLat = highLat;
      }
   }

   public static OsmWaterFeature waterfallMarker(long featureId, double longitude, double latitude) {
      return new OsmWaterFeature(
         featureId,
         false,
         true,
         false,
         OsmWaterKind.WATERFALL,
         new double[][]{{longitude}},
         new double[][]{{latitude}}
      );
   }

   public long featureId() {
      return this.featureId;
   }

   public boolean lineGeometry() {
      return this.lineGeometry;
   }

   public boolean pointGeometry() {
      return this.pointGeometry;
   }

   public boolean waterfallMarker() {
      return this.pointGeometry && this.kind == OsmWaterKind.WATERFALL;
   }

   public boolean oceanHint() {
      return this.oceanHint;
   }

   public OsmWaterKind kind() {
      return this.kind;
   }

   public boolean flowingWater() {
      return !this.pointGeometry && (this.lineGeometry || this.kind.flowing());
   }

   public int partCount() {
      return this.longitudes.length;
   }

   public int pointCount(int partIndex) {
      return this.longitudes[partIndex].length;
   }

   public double lonAt(int partIndex, int pointIndex) {
      return this.longitudes[partIndex][pointIndex];
   }

   public double latAt(int partIndex, int pointIndex) {
      return this.latitudes[partIndex][pointIndex];
   }

   public double minLon() {
      return this.minLon;
   }

   public double maxLon() {
      return this.maxLon;
   }

   public double minLat() {
      return this.minLat;
   }

   public double maxLat() {
      return this.maxLat;
   }

   public boolean intersects(double south, double west, double north, double east) {
      return this.maxLon >= west && this.minLon <= east && this.maxLat >= south && this.minLat <= north;
   }

   public boolean containsBlock(int blockX, int blockZ, WorldProjection projection) {
      if (projection.worldScale() <= 0.0) {
         return false;
      } else if (this.crossesWorldSeam(projection)) {
         return false;
      } else if (this.pointGeometry) {
         return false;
      } else if (this.lineGeometry) {
         return this.touchesBlockLine(blockX, blockZ, projection);
      } else {
         double lon = projection.blockXToLon(blockX);
         double lat = projection.blockZToLat(blockZ);
         return this.containsLonLat(lon, lat);
      }
   }

   public boolean containsLonLat(double lon, double lat) {
      if (this.lineGeometry || this.pointGeometry || lon < this.minLon || lon > this.maxLon || lat < this.minLat || lat > this.maxLat) {
         return false;
      } else {
         boolean inside = false;

         for (int part = 0; part < this.longitudes.length; part++) {
            double[] lonPart = this.longitudes[part];
            double[] latPart = this.latitudes[part];
            int points = lonPart.length;

            for (int i = 0, j = points - 1; i < points; j = i++) {
               double lonA = lonPart[i];
               double latA = latPart[i];
               double lonB = lonPart[j];
               double latB = latPart[j];
               if ((latA > lat) != (latB > lat)) {
                  double crossLon = (lonB - lonA) * (lat - latA) / (latB - latA) + lonA;
                  if (lon <= crossLon) {
                     inside = !inside;
                  }
               }
            }
         }

         return inside;
      }
   }

   private boolean touchesBlockLine(int blockX, int blockZ, WorldProjection projection) {
      double queryX = blockX;
      double queryZ = blockZ;
      double maxDistanceSq = LINE_HALF_WIDTH_BLOCKS * LINE_HALF_WIDTH_BLOCKS + 1.0E-6;

      for (int part = 0; part < this.longitudes.length; part++) {
         double[] lonPart = this.longitudes[part];
         double[] latPart = this.latitudes[part];

         for (int point = 1; point < lonPart.length; point++) {
            double startX = projection.lonToBlockX(lonPart[point - 1]);
            double startZ = projection.latToBlockZ(latPart[point - 1]);
            double endX = projection.lonToBlockX(lonPart[point]);
            double endZ = projection.latToBlockZ(latPart[point]);
            if (distanceToSegmentSq(queryX, queryZ, startX, startZ, endX, endZ) <= maxDistanceSq) {
               return true;
            }
         }
      }

      return false;
   }

   public boolean crossesWorldSeam(WorldProjection projection) {
      for (int part = 0; part < this.partCount(); part++) {
         int points = this.pointCount(part);
         if (points < 2) {
            continue;
         }
         double previousX = projection.lonToBlockX(this.lonAt(part, this.lineGeometry ? 0 : points - 1));
         int firstPoint = this.lineGeometry ? 1 : 0;
         for (int point = firstPoint; point < points; point++) {
            double currentX = projection.lonToBlockX(this.lonAt(part, point));
            if (projection.crossesWorldSeam(previousX, currentX)) {
               return true;
            }
            previousX = currentX;
         }
      }
      return false;
   }

   private static double distanceToSegmentSq(double px, double pz, double ax, double az, double bx, double bz) {
      double dx = bx - ax;
      double dz = bz - az;
      double lengthSq = dx * dx + dz * dz;
      if (lengthSq <= 1.0E-9) {
         double distX = px - ax;
         double distZ = pz - az;
         return distX * distX + distZ * distZ;
      } else {
         double t = ((px - ax) * dx + (pz - az) * dz) / lengthSq;
         t = Math.max(0.0, Math.min(1.0, t));
         double projX = ax + t * dx;
         double projZ = az + t * dz;
         double distX = px - projX;
         double distZ = pz - projZ;
         return distX * distX + distZ * distZ;
      }
   }

   private static double[][] copyParts(double[][] input) {
      double[][] copy = new double[input.length][];

      for (int i = 0; i < input.length; i++) {
         copy[i] = input[i].clone();
      }

      return copy;
   }
}
