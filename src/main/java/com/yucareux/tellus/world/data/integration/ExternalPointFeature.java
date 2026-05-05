package com.yucareux.tellus.world.data.integration;

import java.util.Map;
import java.util.Objects;

public record ExternalPointFeature(
   String source,
   String sourceId,
   ExternalPointKind kind,
   String typeTag,
   GeoPoint point,
   Map<String, String> tags
) {
   public ExternalPointFeature {
      source = Objects.requireNonNullElse(source, "");
      sourceId = Objects.requireNonNullElse(sourceId, "");
      kind = Objects.requireNonNull(kind, "kind");
      typeTag = Objects.requireNonNullElse(typeTag, "");
      point = Objects.requireNonNull(point, "point");
      tags = tags == null ? Map.of() : Map.copyOf(tags);
   }
}
