package com.yucareux.tellus.worldgen.building;

import java.util.Locale;

public record BuildingProfile(
   BuildingProfile.Archetype archetype,
   BuildingProfile.RoofProfile roofProfile,
   BuildingProfile.ClimateFamily climateFamily,
   int floorCount,
   int storeyHeightBlocks,
   boolean interiorsEnabled,
   int parapetHeight,
   int roofRise,
   int setbackEveryFloors,
   int maxSetback,
   int windowSpacing,
   String primaryType,
   String wallMaterial,
   String roofMaterial,
   String wallColor,
   String roofColor
) {
   public BuildingProfile {
      floorCount = Math.max(1, floorCount);
      storeyHeightBlocks = Math.max(1, storeyHeightBlocks);
      parapetHeight = Math.max(0, parapetHeight);
      roofRise = Math.max(0, roofRise);
      setbackEveryFloors = Math.max(0, setbackEveryFloors);
      maxSetback = Math.max(0, maxSetback);
      windowSpacing = Math.max(2, windowSpacing);
      primaryType = normalizeMaterial(primaryType);
      wallMaterial = normalizeMaterial(wallMaterial);
      roofMaterial = normalizeMaterial(roofMaterial);
      wallColor = normalizeMaterial(wallColor);
      roofColor = normalizeMaterial(roofColor);
   }

   private static String normalizeMaterial(String value) {
      return value == null || value.isBlank() ? "" : value.trim().toLowerCase(Locale.ROOT);
   }

   public enum Archetype {
      HOUSE,
      APARTMENT,
      COMMERCIAL,
      INDUSTRIAL,
      TOWER,
      GENERIC
   }

   public enum RoofProfile {
      GABLED_X,
      GABLED_Z,
      HIPPED,
      FLAT,
      FLAT_CROWN,
      FLAT_SKYLIGHT
   }

   public enum ClimateFamily {
      TEMPERATE,
      COLD,
      ARID,
      TROPICAL
   }
}
