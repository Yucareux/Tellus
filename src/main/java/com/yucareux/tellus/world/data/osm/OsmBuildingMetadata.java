package com.yucareux.tellus.world.data.osm;

public record OsmBuildingMetadata(
   String buildingClass,
   String subtype,
   String use,
   String name,
   int floorCount,
   String roofShape,
   int roofLevels,
   double roofHeightMeters,
   String roofMaterial,
   String wallMaterial,
   String roofColor,
   String wallColor
) {
   public OsmBuildingMetadata {
      floorCount = Math.max(1, floorCount);
      roofLevels = Math.max(0, roofLevels);
      roofHeightMeters = Double.isFinite(roofHeightMeters) && roofHeightMeters > 0.0 ? roofHeightMeters : 0.0;
      buildingClass = normalize(buildingClass);
      subtype = normalize(subtype);
      use = normalize(use);
      name = normalize(name);
      roofShape = normalize(roofShape);
      roofMaterial = normalize(roofMaterial);
      wallMaterial = normalize(wallMaterial);
      roofColor = normalize(roofColor);
      wallColor = normalize(wallColor);
   }

   public String primaryType() {
      return this.use != null ? this.use : this.subtype != null ? this.subtype : this.buildingClass;
   }

   private static String normalize(String value) {
      return value == null || value.isBlank() ? null : value;
   }
}
