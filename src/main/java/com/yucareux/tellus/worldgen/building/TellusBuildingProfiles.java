package com.yucareux.tellus.worldgen.building;

import com.yucareux.tellus.world.data.osm.OsmBuildingFeature;
import com.yucareux.tellus.world.data.osm.OsmBuildingMetadata;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

public final class TellusBuildingProfiles {
   private static final double DEFAULT_STOREY_METERS = 3.2;

   private TellusBuildingProfiles() {
   }

   public static BuildingProfile resolveProfile(OsmBuildingFeature feature, double worldScale, Holder<Biome> biome, boolean interiorsEnabled) {
      OsmBuildingMetadata metadata = feature.metadata();
      int floorCount = Math.max(1, metadata.floorCount());
      double area = feature.areaSquareMeters();
      BuildingProfile.Archetype archetype = resolveArchetype(metadata, area, floorCount, feature.heightMeters());
      BuildingProfile.ClimateFamily climate = climateFamily(biome);
      int storeyHeightBlocks = interiorsEnabled ? 4 : Math.max(1, (int)Math.round(DEFAULT_STOREY_METERS / Math.max(1.0, worldScale)));
      BuildingProfile.RoofProfile roofProfile = resolveRoofProfile(metadata, archetype, feature);
      int parapetHeight = switch (archetype) {
         case HOUSE -> 0;
         case APARTMENT, COMMERCIAL, INDUSTRIAL -> 1;
         case TOWER -> 2;
         case GENERIC -> 1;
      };
      int taggedRoofRise = resolveTaggedRoofRise(metadata, worldScale, storeyHeightBlocks);
      int roofRise = switch (roofProfile) {
         case GABLED_X, GABLED_Z, HIPPED -> taggedRoofRise > 0 ? taggedRoofRise : Math.max(1, Math.min(4, (int)Math.round(feature.heightMeters() / 12.0)));
         case FLAT_CROWN -> taggedRoofRise > 0 ? taggedRoofRise : 2;
         case FLAT_SKYLIGHT -> taggedRoofRise > 0 ? taggedRoofRise : 1;
         case FLAT -> taggedRoofRise;
      };
      int setbackEveryFloors = archetype == BuildingProfile.Archetype.TOWER && floorCount >= 12 ? 8 : 0;
      int maxSetback = archetype == BuildingProfile.Archetype.TOWER ? 3 : 0;
      int windowSpacing = switch (archetype) {
         case HOUSE -> 3;
         case APARTMENT -> 3;
         case COMMERCIAL -> 4;
         case INDUSTRIAL -> 5;
         case TOWER -> 4;
         case GENERIC -> 4;
      };
      return new BuildingProfile(
         archetype,
         roofProfile,
         climate,
         floorCount,
         storeyHeightBlocks,
         interiorsEnabled,
         parapetHeight,
         roofRise,
         setbackEveryFloors,
         maxSetback,
         windowSpacing,
         metadata.primaryType(),
         metadata.wallMaterial(),
         metadata.roofMaterial(),
         metadata.wallColor(),
         metadata.roofColor()
      );
   }

   public static int inferFloorCount(double heightMeters) {
      return Math.max(1, (int)Math.round(heightMeters / DEFAULT_STOREY_METERS));
   }

   private static int resolveTaggedRoofRise(OsmBuildingMetadata metadata, double worldScale, int storeyHeightBlocks) {
      if (metadata.roofHeightMeters() > 0.0) {
         return Math.max(1, (int)Math.round(metadata.roofHeightMeters() / Math.max(1.0, worldScale)));
      }
      if (metadata.roofLevels() > 0) {
         return Math.max(1, metadata.roofLevels() * Math.max(1, storeyHeightBlocks));
      }
      return 0;
   }

   private static BuildingProfile.Archetype resolveArchetype(OsmBuildingMetadata metadata, double areaSquareMeters, int floorCount, double heightMeters) {
      String type = metadata.primaryType();
      String normalized = type == null ? "" : type.toLowerCase();
      if (heightMeters >= 30.0 || floorCount >= 10 || containsAny(normalized, "tower", "skyscraper", "highrise", "high-rise")) {
         return BuildingProfile.Archetype.TOWER;
      }

      if (containsAny(normalized, "warehouse", "industrial", "factory", "shed", "hangar")) {
         return BuildingProfile.Archetype.INDUSTRIAL;
      }

      if (containsAny(normalized, "house", "home", "residential", "dwelling", "villa", "cabin", "bungalow")) {
         if (areaSquareMeters <= 180.0 && floorCount <= 3) {
            return BuildingProfile.Archetype.HOUSE;
         }

         return BuildingProfile.Archetype.APARTMENT;
      }

      if (areaSquareMeters <= 180.0 && floorCount <= 3) {
         return BuildingProfile.Archetype.HOUSE;
      }

      if (areaSquareMeters >= 700.0 && floorCount <= 3) {
         return BuildingProfile.Archetype.INDUSTRIAL;
      }

      if (containsAny(normalized, "apartment", "flat", "condo", "residence", "residential")) {
         return BuildingProfile.Archetype.APARTMENT;
      }

      if (containsAny(normalized, "office", "shop", "retail", "commercial", "mall", "hotel", "school", "civic")) {
         return BuildingProfile.Archetype.COMMERCIAL;
      }

      if (areaSquareMeters <= 600.0 && floorCount <= 6) {
         return BuildingProfile.Archetype.APARTMENT;
      }

      return areaSquareMeters > 0.0 ? BuildingProfile.Archetype.COMMERCIAL : BuildingProfile.Archetype.GENERIC;
   }

   private static BuildingProfile.RoofProfile resolveRoofProfile(OsmBuildingMetadata metadata, BuildingProfile.Archetype archetype, OsmBuildingFeature feature) {
      String roofShape = metadata.roofShape();
      if (roofShape != null) {
         String normalized = roofShape.toLowerCase();
         if (normalized.contains("hip")) {
            return BuildingProfile.RoofProfile.HIPPED;
         }
         if (normalized.contains("gabled") || normalized.contains("gable")) {
            return feature.widthLongerThanDepth() ? BuildingProfile.RoofProfile.GABLED_Z : BuildingProfile.RoofProfile.GABLED_X;
         }
         if (normalized.contains("flat")) {
            return archetype == BuildingProfile.Archetype.TOWER ? BuildingProfile.RoofProfile.FLAT_CROWN : BuildingProfile.RoofProfile.FLAT;
         }
      }

      return switch (archetype) {
         case HOUSE -> feature.widthLongerThanDepth() ? BuildingProfile.RoofProfile.GABLED_Z : BuildingProfile.RoofProfile.HIPPED;
         case INDUSTRIAL -> BuildingProfile.RoofProfile.FLAT_SKYLIGHT;
         case TOWER -> BuildingProfile.RoofProfile.FLAT_CROWN;
         case APARTMENT, COMMERCIAL, GENERIC -> BuildingProfile.RoofProfile.FLAT;
      };
   }

   public static BuildingProfile.ClimateFamily climateFamily(Holder<Biome> biome) {
      if (biome == null) {
         return BuildingProfile.ClimateFamily.TEMPERATE;
      }

      Biome value = biome.value();
      float temperature = value.getBaseTemperature();
      boolean precipitation = value.hasPrecipitation();
      if (temperature <= 0.3F) {
         return BuildingProfile.ClimateFamily.COLD;
      }
      if (temperature >= 1.2F && !precipitation) {
         return BuildingProfile.ClimateFamily.ARID;
      }
      if (temperature >= 1.0F && precipitation) {
         return BuildingProfile.ClimateFamily.TROPICAL;
      }
      return BuildingProfile.ClimateFamily.TEMPERATE;
   }

   private static boolean containsAny(String value, String... parts) {
      for (String part : parts) {
         if (value.contains(part)) {
            return true;
         }
      }
      return false;
   }
}
