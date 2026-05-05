package com.yucareux.tellus.world.data.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ExternalBuildingFeature(
   String source,
   String sourceId,
   ExternalBuildingKind kind,
   double heightMeters,
   double minHeightMeters,
   int floorCount,
   List<List<GeoPoint>> rings,
   Map<String, String> tags
) {
   public ExternalBuildingFeature {
      source = normalizeRequired(source, "source");
      sourceId = normalizeRequired(sourceId, "sourceId");
      kind = Objects.requireNonNull(kind, "kind");
      if (!Double.isFinite(heightMeters) || heightMeters <= 0.0) {
         throw new IllegalArgumentException("heightMeters must be finite and > 0");
      }
      if (!Double.isFinite(minHeightMeters) || minHeightMeters < 0.0) {
         throw new IllegalArgumentException("minHeightMeters must be finite and >= 0");
      }
      if (heightMeters <= minHeightMeters) {
         throw new IllegalArgumentException("heightMeters must be greater than minHeightMeters");
      }
      if (floorCount < 1) {
         throw new IllegalArgumentException("floorCount must be >= 1");
      }
      rings = copyRings(rings);
      tags = tags == null ? Map.of() : Map.copyOf(tags);
   }

   public List<GeoPoint> outerRing() {
      return this.rings.get(0);
   }

   public List<List<GeoPoint>> innerRings() {
      return this.rings.size() <= 1 ? List.of() : this.rings.subList(1, this.rings.size());
   }

   private static List<List<GeoPoint>> copyRings(List<List<GeoPoint>> rings) {
      Objects.requireNonNull(rings, "rings");
      if (rings.isEmpty()) {
         throw new IllegalArgumentException("building feature requires at least one ring");
      }
      List<List<GeoPoint>> copy = new ArrayList<>(rings.size());
      for (List<GeoPoint> ring : rings) {
         List<GeoPoint> ringCopy = List.copyOf(Objects.requireNonNull(ring, "ring"));
         if (ringCopy.size() < 4) {
            throw new IllegalArgumentException("building rings require at least four points");
         }
         copy.add(ringCopy);
      }
      return List.copyOf(copy);
   }

   private static String normalizeRequired(String value, String name) {
      if (value == null || value.isBlank()) {
         throw new IllegalArgumentException(name + " must not be blank");
      }
      return value.trim();
   }
}
