package com.yucareux.tellus.world.data.osm;

import com.yucareux.tellus.worldgen.WorldProjection;
import java.util.Objects;

public final class OsmBuildingFeature {
   private final OsmBuildingKind kind;
   private final long featureId;
   private final String buildingId;
   private final boolean hasParts;
   private final OsmBuildingMetadata metadata;
   private final double heightMeters;
   private final double minHeightMeters;
   private final double[][] longitudes;
   private final double[][] latitudes;
   private final double minLon;
   private final double maxLon;
   private final double minLat;
   private final double maxLat;
   private final double centroidLon;
   private final double centroidLat;
   private final double areaSquareMeters;

   public OsmBuildingFeature(
      OsmBuildingKind kind,
      long featureId,
      String buildingId,
      boolean hasParts,
      OsmBuildingMetadata metadata,
      double heightMeters,
      double minHeightMeters,
      double[][] longitudes,
      double[][] latitudes
   ) {
      this.kind = Objects.requireNonNull(kind, "kind");
      this.featureId = featureId;
      this.buildingId = buildingId == null || buildingId.isBlank() ? null : buildingId;
      this.hasParts = hasParts;
      this.metadata = Objects.requireNonNull(metadata, "metadata");
      this.heightMeters = heightMeters;
      this.minHeightMeters = minHeightMeters;
      this.longitudes = copyParts(Objects.requireNonNull(longitudes, "longitudes"));
      this.latitudes = copyParts(Objects.requireNonNull(latitudes, "latitudes"));
      if (this.longitudes.length != this.latitudes.length || this.longitudes.length == 0) {
         throw new IllegalArgumentException("Building feature requires matching geometry parts");
      } else {
         double lowLon = Double.POSITIVE_INFINITY;
         double highLon = Double.NEGATIVE_INFINITY;
         double lowLat = Double.POSITIVE_INFINITY;
         double highLat = Double.NEGATIVE_INFINITY;

         for (int part = 0; part < this.longitudes.length; part++) {
            double[] lonPart = this.longitudes[part];
            double[] latPart = this.latitudes[part];
            if (lonPart.length != latPart.length || lonPart.length < 4) {
               throw new IllegalArgumentException("Building feature part has invalid point count");
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
         double[] centroid = computeCentroid(this.longitudes, this.latitudes);
         this.centroidLon = centroid[0];
         this.centroidLat = centroid[1];
         this.areaSquareMeters = Math.max(1.0, approximateAreaSquareMeters(this.longitudes, this.latitudes, this.centroidLat));
      }
   }

   public OsmBuildingKind kind() {
      return this.kind;
   }

   public long featureId() {
      return this.featureId;
   }

   public String buildingId() {
      return this.buildingId;
   }

   public boolean hasParts() {
      return this.hasParts;
   }

   public OsmBuildingMetadata metadata() {
      return this.metadata;
   }

   public double heightMeters() {
      return this.heightMeters;
   }

   public double minHeightMeters() {
      return this.minHeightMeters;
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

   public double centroidLon() {
      return this.centroidLon;
   }

   public double centroidLat() {
      return this.centroidLat;
   }

   public double areaSquareMeters() {
      return this.areaSquareMeters;
   }

   public boolean intersects(double south, double west, double north, double east) {
      return this.maxLon >= west && this.minLon <= east && this.maxLat >= south && this.minLat <= north;
   }

   public boolean containsWorld(double worldX, double worldZ, WorldProjection projection) {
      if (projection.worldScale() <= 0.0) {
         return false;
      } else {
         double lon = projection.blockXToLon(worldX);
         double lat = projection.blockZToLat(worldZ);
         return this.containsLonLat(lon, lat);
      }
   }

   public boolean containsLonLat(double lon, double lat) {
      if (lon < this.minLon || lon > this.maxLon || lat < this.minLat || lat > this.maxLat) {
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

   public double minBlockX(WorldProjection projection) {
      return Math.min(projection.lonToBlockX(this.minLon), projection.lonToBlockX(this.maxLon));
   }

   public double maxBlockX(WorldProjection projection) {
      return Math.max(projection.lonToBlockX(this.minLon), projection.lonToBlockX(this.maxLon));
   }

   public double minBlockZ(WorldProjection projection) {
      double z1 = projection.latToBlockZ(this.minLat);
      double z2 = projection.latToBlockZ(this.maxLat);
      return Math.min(z1, z2);
   }

   public double maxBlockZ(WorldProjection projection) {
      double z1 = projection.latToBlockZ(this.minLat);
      double z2 = projection.latToBlockZ(this.maxLat);
      return Math.max(z1, z2);
   }

   public double[] centroidWorld(WorldProjection projection) {
      return new double[]{projection.lonToBlockX(this.centroidLon), projection.latToBlockZ(this.centroidLat)};
   }

   public boolean crossesWorldSeam(WorldProjection projection) {
      for (int part = 0; part < this.partCount(); part++) {
         int points = this.pointCount(part);
         if (points < 2) {
            continue;
         }
         double previousX = projection.lonToBlockX(this.lonAt(part, points - 1));
         for (int point = 0; point < points; point++) {
            double currentX = projection.lonToBlockX(this.lonAt(part, point));
            if (projection.crossesWorldSeam(previousX, currentX)) {
               return true;
            }
            previousX = currentX;
         }
      }
      return false;
   }

   public boolean widthLongerThanDepth() {
      double metersPerLonDegree = 111319.49166666667 * Math.cos(Math.toRadians(this.centroidLat));
      double widthMeters = Math.abs(this.maxLon - this.minLon) * Math.max(1.0, metersPerLonDegree);
      double depthMeters = Math.abs(this.maxLat - this.minLat) * 111319.49166666667;
      return widthMeters >= depthMeters;
   }

   private static double[][] copyParts(double[][] input) {
      double[][] copy = new double[input.length][];

      for (int i = 0; i < input.length; i++) {
         copy[i] = input[i].clone();
      }

      return copy;
   }

   private static double[] computeCentroid(double[][] longitudes, double[][] latitudes) {
      double centroidLon = 0.0;
      double centroidLat = 0.0;
      double totalWeight = 0.0;

      for (int part = 0; part < longitudes.length; part++) {
         double[] lonPart = longitudes[part];
         double[] latPart = latitudes[part];
         double signedArea = polygonSignedArea(lonPart, latPart);
         double weight = Math.abs(signedArea);
         if (weight <= 1.0E-12) {
            for (int point = 0; point < lonPart.length - 1; point++) {
               centroidLon += lonPart[point];
               centroidLat += latPart[point];
               totalWeight++;
            }
         } else {
            double factor = 0.0;
            double cx = 0.0;
            double cy = 0.0;

            for (int i = 0, j = lonPart.length - 1; i < lonPart.length; j = i++) {
               double cross = lonPart[j] * latPart[i] - lonPart[i] * latPart[j];
               factor += cross;
               cx += (lonPart[j] + lonPart[i]) * cross;
               cy += (latPart[j] + latPart[i]) * cross;
            }

            if (Math.abs(factor) > 1.0E-12) {
               centroidLon += cx / (3.0 * factor) * weight;
               centroidLat += cy / (3.0 * factor) * weight;
               totalWeight += weight;
            }
         }
      }

      if (totalWeight <= 1.0E-12) {
         return new double[]{longitudes[0][0], latitudes[0][0]};
      }

      return new double[]{centroidLon / totalWeight, centroidLat / totalWeight};
   }

   private static double approximateAreaSquareMeters(double[][] longitudes, double[][] latitudes, double centroidLat) {
      double metersPerLon = 111319.49166666667 * Math.cos(Math.toRadians(centroidLat));
      double total = 0.0;

      for (int part = 0; part < longitudes.length; part++) {
         double[] lonPart = longitudes[part];
         double[] latPart = latitudes[part];
         double area = 0.0;

         for (int i = 0, j = lonPart.length - 1; i < lonPart.length; j = i++) {
            double x1 = lonPart[j] * metersPerLon;
            double y1 = latPart[j] * 111319.49166666667;
            double x2 = lonPart[i] * metersPerLon;
            double y2 = latPart[i] * 111319.49166666667;
            area += x1 * y2 - x2 * y1;
         }

         total += Math.abs(area) * 0.5;
      }

      return total;
   }

   private static double polygonSignedArea(double[] lonPart, double[] latPart) {
      double area = 0.0;

      for (int i = 0, j = lonPart.length - 1; i < lonPart.length; j = i++) {
         area += lonPart[j] * latPart[i] - lonPart[i] * latPart[j];
      }

      return area * 0.5;
   }
}
