package com.yucareux.tellus.world.data.integration;

import com.yucareux.tellus.world.data.osm.OsmBuildingFeature;
import com.yucareux.tellus.world.data.osm.OsmBuildingKind;
import com.yucareux.tellus.world.data.osm.OsmBuildingMetadata;
import com.yucareux.tellus.world.data.osm.RoadFeature;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ExternalFeatureAdapters {
   public static final String TELLUS_OVERTURE_SOURCE = "tellus-overture";
   private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
   private static final long FNV_PRIME = 0x100000001b3L;

   private ExternalFeatureAdapters() {
   }

   public static RoadFeature toTellusRoad(ExternalRoadFeature feature) {
      Objects.requireNonNull(feature, "feature");
      int pointCount = feature.points().size();
      double[] longitudes = new double[pointCount];
      double[] latitudes = new double[pointCount];
      for (int index = 0; index < pointCount; index++) {
         GeoPoint point = feature.points().get(index);
         longitudes[index] = point.longitude();
         latitudes[index] = point.latitude();
      }

      return new RoadFeature(
         stableFeatureId(feature.source(), feature.sourceId()),
         feature.roadClass(),
         feature.mode(),
         feature.bridgeLevel(),
         feature.highwayTag(),
         longitudes,
         latitudes,
         feature.tags()
      );
   }

   public static ExternalRoadFeature fromTellusRoad(RoadFeature feature) {
      return fromTellusRoad(TELLUS_OVERTURE_SOURCE, feature);
   }

   public static ExternalRoadFeature fromTellusRoad(String source, RoadFeature feature) {
      Objects.requireNonNull(feature, "feature");
      List<GeoPoint> points = new ArrayList<>(feature.pointCount());
      for (int index = 0; index < feature.pointCount(); index++) {
         points.add(new GeoPoint(feature.latAt(index), feature.lonAt(index)));
      }

      Map<String, String> tags = new LinkedHashMap<>(feature.tags());
      putIfNotBlank(tags, "highway", feature.highwayTag());
      tags.put("road_class", feature.roadClass().name());
      tags.put("road_mode", feature.mode().name());
      if (feature.bridgeLevel() > 0) {
         tags.put("bridge_level", Integer.toString(feature.bridgeLevel()));
      }

      return new ExternalRoadFeature(
         source,
         Long.toString(feature.wayId()),
         feature.roadClass(),
         feature.mode(),
         feature.bridgeLevel(),
         feature.highwayTag(),
         points,
         tags
      );
   }

   public static OsmBuildingFeature toTellusBuilding(ExternalBuildingFeature feature) {
      Objects.requireNonNull(feature, "feature");
      double[][] longitudes = new double[feature.rings().size()][];
      double[][] latitudes = new double[feature.rings().size()][];
      for (int ringIndex = 0; ringIndex < feature.rings().size(); ringIndex++) {
         List<GeoPoint> ring = feature.rings().get(ringIndex);
         longitudes[ringIndex] = new double[ring.size()];
         latitudes[ringIndex] = new double[ring.size()];
         for (int pointIndex = 0; pointIndex < ring.size(); pointIndex++) {
            GeoPoint point = ring.get(pointIndex);
            longitudes[ringIndex][pointIndex] = point.longitude();
            latitudes[ringIndex][pointIndex] = point.latitude();
         }
      }

      Map<String, String> tags = feature.tags();
      OsmBuildingMetadata metadata = new OsmBuildingMetadata(
         firstTag(tags, "building_class", "class", "building"),
         firstTag(tags, "subtype", "building:part"),
         firstTag(tags, "use", "building:use"),
         firstTag(tags, "name"),
         feature.floorCount(),
         firstTag(tags, "roof_shape", "roof:shape"),
         intFromTag(firstTag(tags, "roof_levels", "roof:levels"), 0),
         doubleFromTag(firstTag(tags, "roof_height", "roof:height"), 0.0),
         firstTag(tags, "roof_material", "roof:material"),
         firstTag(tags, "wall_material", "building_material", "building:material", "facade_material", "facade:material", "material"),
         firstTag(tags, "roof_color", "roof_colour", "roof:color", "roof:colour"),
         firstTag(tags, "wall_color", "wall_colour", "building_color", "building_colour", "building:color", "building:colour", "facade:color", "facade:colour", "color", "colour")
      );
      OsmBuildingKind kind = feature.kind() == ExternalBuildingKind.PART ? OsmBuildingKind.PART : OsmBuildingKind.FOOTPRINT;
      String buildingId = firstTag(tags, "building_id", "building:id");
      if (buildingId == null) {
         buildingId = feature.source() + ":" + feature.sourceId();
      }

      return new OsmBuildingFeature(
         kind,
         stableFeatureId(feature.source(), feature.sourceId()),
         buildingId,
         Boolean.parseBoolean(firstTag(tags, "has_parts")),
         metadata,
         feature.heightMeters(),
         feature.minHeightMeters(),
         longitudes,
         latitudes
      );
   }

   public static ExternalBuildingFeature fromTellusBuilding(OsmBuildingFeature feature) {
      return fromTellusBuilding(TELLUS_OVERTURE_SOURCE, feature);
   }

   public static ExternalBuildingFeature fromTellusBuilding(String source, OsmBuildingFeature feature) {
      Objects.requireNonNull(feature, "feature");
      List<List<GeoPoint>> rings = new ArrayList<>(feature.partCount());
      for (int part = 0; part < feature.partCount(); part++) {
         List<GeoPoint> ring = new ArrayList<>(feature.pointCount(part));
         for (int point = 0; point < feature.pointCount(part); point++) {
            ring.add(new GeoPoint(feature.latAt(part, point), feature.lonAt(part, point)));
         }
         rings.add(ring);
      }

      OsmBuildingMetadata metadata = feature.metadata();
      Map<String, String> tags = new LinkedHashMap<>();
      putIfNotBlank(tags, "building_id", feature.buildingId());
      putIfNotBlank(tags, "building_class", metadata.buildingClass());
      putIfNotBlank(tags, "subtype", metadata.subtype());
      putIfNotBlank(tags, "use", metadata.use());
      putIfNotBlank(tags, "name", metadata.name());
      putIfNotBlank(tags, "roof_shape", metadata.roofShape());
      if (metadata.roofLevels() > 0) {
         tags.put("roof_levels", Integer.toString(metadata.roofLevels()));
      }
      if (metadata.roofHeightMeters() > 0.0) {
         tags.put("roof_height", Double.toString(metadata.roofHeightMeters()));
      }
      putIfNotBlank(tags, "roof_material", metadata.roofMaterial());
      putIfNotBlank(tags, "wall_material", metadata.wallMaterial());
      putIfNotBlank(tags, "roof_color", metadata.roofColor());
      putIfNotBlank(tags, "wall_color", metadata.wallColor());
      if (feature.hasParts()) {
         tags.put("has_parts", "true");
      }

      return new ExternalBuildingFeature(
         source,
         Long.toString(feature.featureId()),
         feature.kind() == OsmBuildingKind.PART ? ExternalBuildingKind.PART : ExternalBuildingKind.FOOTPRINT,
         feature.heightMeters(),
         feature.minHeightMeters(),
         metadata.floorCount(),
         rings,
         tags
      );
   }

   private static void putIfNotBlank(Map<String, String> tags, String key, String value) {
      if (value != null && !value.isBlank()) {
         tags.put(key, value);
      }
   }

   private static String firstTag(Map<String, String> tags, String... keys) {
      for (String key : keys) {
         String value = tags.get(key);
         if (value != null && !value.isBlank()) {
            return value;
         }
      }
      return null;
   }

   private static int intFromTag(String value, int defaultValue) {
      double parsed = doubleFromTag(value, defaultValue);
      return Math.max(0, (int)Math.round(parsed));
   }

   private static double doubleFromTag(String value, double defaultValue) {
      if (value == null || value.isBlank()) {
         return defaultValue;
      }
      String normalized = value.trim().replace(',', '.');
      StringBuilder number = new StringBuilder();
      boolean seenDigit = false;
      for (int index = 0; index < normalized.length(); index++) {
         char ch = normalized.charAt(index);
         if ((ch >= '0' && ch <= '9') || ch == '.' || (ch == '-' && number.isEmpty())) {
            number.append(ch);
            if (ch >= '0' && ch <= '9') {
               seenDigit = true;
            }
         } else if (seenDigit) {
            break;
         }
      }
      if (!seenDigit) {
         return defaultValue;
      }
      try {
         double parsed = Double.parseDouble(number.toString());
         return parsed > 0.0 ? parsed : defaultValue;
      } catch (NumberFormatException error) {
         return defaultValue;
      }
   }

   private static long stableFeatureId(String source, String sourceId) {
      long hash = FNV_OFFSET_BASIS;
      String key = source + ":" + sourceId;
      for (int index = 0; index < key.length(); index++) {
         hash ^= key.charAt(index);
         hash *= FNV_PRIME;
      }
      return hash == 0L ? 1L : hash;
   }
}
