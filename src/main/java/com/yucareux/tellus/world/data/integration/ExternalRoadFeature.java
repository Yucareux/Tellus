package com.yucareux.tellus.world.data.integration;

import com.yucareux.tellus.world.data.osm.RoadClass;
import com.yucareux.tellus.world.data.osm.RoadMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ExternalRoadFeature(
   String source,
   String sourceId,
   RoadClass roadClass,
   RoadMode mode,
   int bridgeLevel,
   String highwayTag,
   List<GeoPoint> points,
   Map<String, String> tags
) {
   public ExternalRoadFeature {
      source = normalizeRequired(source, "source");
      sourceId = normalizeRequired(sourceId, "sourceId");
      roadClass = Objects.requireNonNull(roadClass, "roadClass");
      mode = Objects.requireNonNull(mode, "mode");
      highwayTag = highwayTag == null ? "" : highwayTag.trim().toLowerCase();
      if (bridgeLevel < 0) {
         throw new IllegalArgumentException("bridgeLevel must be >= 0");
      }
      points = copyLine(points);
      tags = tags == null ? Map.of() : Map.copyOf(tags);
   }

   private static List<GeoPoint> copyLine(List<GeoPoint> points) {
      Objects.requireNonNull(points, "points");
      List<GeoPoint> copy = List.copyOf(points);
      if (copy.size() < 2) {
         throw new IllegalArgumentException("road feature requires at least two points");
      }
      return copy;
   }

   private static String normalizeRequired(String value, String name) {
      if (value == null || value.isBlank()) {
         throw new IllegalArgumentException(name + " must not be blank");
      }
      return value.trim();
   }
}
