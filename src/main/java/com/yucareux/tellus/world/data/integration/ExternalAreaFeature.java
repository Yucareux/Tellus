package com.yucareux.tellus.world.data.integration;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ExternalAreaFeature(
   String source,
   String sourceId,
   ExternalAreaKind kind,
   String typeTag,
   List<List<GeoPoint>> rings,
   Map<String, String> tags
) {
   public ExternalAreaFeature {
      source = Objects.requireNonNullElse(source, "");
      sourceId = Objects.requireNonNullElse(sourceId, "");
      kind = Objects.requireNonNull(kind, "kind");
      typeTag = Objects.requireNonNullElse(typeTag, "");
      rings = rings == null ? List.of() : rings.stream().map(List::copyOf).toList();
      tags = tags == null ? Map.of() : Map.copyOf(tags);
   }
}
