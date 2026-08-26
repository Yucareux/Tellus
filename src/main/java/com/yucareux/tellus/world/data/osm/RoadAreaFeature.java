package com.yucareux.tellus.world.data.osm;

import com.yucareux.tellus.worldgen.WorldProjection;
import java.util.Locale;
import java.util.Objects;

public final class RoadAreaFeature {
   private final long featureId;
   private final RoadClass roadClass;
   private final String highwayTag;
   private final String roadSurface;
   private final String subclass;
   private final double[][] longitudes;
   private final double[][] latitudes;
   private final double minLon;
   private final double maxLon;
   private final double minLat;
   private final double maxLat;

   public RoadAreaFeature(
      long featureId,
      RoadClass roadClass,
      String highwayTag,
      String roadSurface,
      String subclass,
      double[][] longitudes,
      double[][] latitudes
   ) {
      this.featureId = featureId;
      this.roadClass = Objects.requireNonNull(roadClass, "roadClass");
      this.highwayTag = normalizeHighwayTag(highwayTag);
      this.roadSurface = RoadSurfaceStyle.normalizeSurface(roadSurface);
      this.subclass = RoadSurfaceStyle.normalizeSubclass(subclass);
      this.longitudes = copyParts(Objects.requireNonNull(longitudes, "longitudes"));
      this.latitudes = copyParts(Objects.requireNonNull(latitudes, "latitudes"));
      if (this.longitudes.length != this.latitudes.length || this.longitudes.length == 0) {
         throw new IllegalArgumentException("Road area feature requires matching geometry parts");
      }

      double lowLon = Double.POSITIVE_INFINITY;
      double highLon = Double.NEGATIVE_INFINITY;
      double lowLat = Double.POSITIVE_INFINITY;
      double highLat = Double.NEGATIVE_INFINITY;
      for (int part = 0; part < this.longitudes.length; part++) {
         double[] lonPart = this.longitudes[part];
         double[] latPart = this.latitudes[part];
         if (lonPart.length != latPart.length || lonPart.length < 4) {
            throw new IllegalArgumentException("Road area feature part has invalid point count");
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

   public long featureId() {
      return this.featureId;
   }

   public RoadClass roadClass() {
      return this.roadClass;
   }

   public String highwayTag() {
      return this.highwayTag;
   }

   public String roadSurface() {
      return this.roadSurface;
   }

   public String subclass() {
      return this.subclass;
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

   public boolean intersects(double south, double west, double north, double east) {
      return this.maxLon >= west && this.minLon <= east && this.maxLat >= south && this.minLat <= north;
   }

   public boolean containsBlock(int blockX, int blockZ, WorldProjection projection) {
      if (projection.worldScale() <= 0.0) {
         return false;
      }

      double lon = projection.blockXToLon(blockX);
      double lat = projection.blockZToLat(blockZ);
      return this.containsLonLat(lon, lat);
   }

   public boolean containsLonLat(double lon, double lat) {
      if (lon < this.minLon || lon > this.maxLon || lat < this.minLat || lat > this.maxLat) {
         return false;
      }

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

   private static double[][] copyParts(double[][] input) {
      double[][] copy = new double[input.length][];
      for (int i = 0; i < input.length; i++) {
         copy[i] = input[i].clone();
      }
      return copy;
   }

   private static String normalizeHighwayTag(String highwayTag) {
      return highwayTag == null ? "" : highwayTag.trim().toLowerCase(Locale.ROOT);
   }
}
