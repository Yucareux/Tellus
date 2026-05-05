package com.yucareux.tellus.world.data.integration;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ExternalLineFeature(
   String source,
   String sourceId,
   ExternalLineKind kind,
   String typeTag,
   List<GeoPoint> points,
   Map<String, String> tags
) {
   public ExternalLineFeature {
      source = Objects.requireNonNullElse(source, "");
      sourceId = Objects.requireNonNullElse(sourceId, "");
      kind = Objects.requireNonNull(kind, "kind");
      typeTag = Objects.requireNonNullElse(typeTag, "");
      points = points == null ? List.of() : List.copyOf(points);
      tags = tags == null ? Map.of() : Map.copyOf(tags);
   }
}
