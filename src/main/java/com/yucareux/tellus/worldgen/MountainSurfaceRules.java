package com.yucareux.tellus.worldgen;

public final class MountainSurfaceRules {
   public static final int ESA_NO_DATA = 0;
   public static final int ESA_TREE_COVER = 10;
   public static final int ESA_SHRUBLAND = 20;
   public static final int ESA_GRASSLAND = 30;
   public static final int ESA_CROPLAND = 40;
   public static final int ESA_BUILT = 50;
   public static final int ESA_BARE = 60;
   public static final int ESA_SNOW_ICE = 70;
   public static final int ESA_WATER = 80;
   public static final int ESA_WETLAND = 90;
   public static final int ESA_MANGROVES = 95;
   public static final int ESA_MOSS_LICHEN = 100;
   /**
    * Zone anchors. Callers pass heights mapped by {@link MountainElevationZones#zoneHeight},
    * so these sit at latitude-dependent real elevations rather than fixed block heights.
    */
   public static final int SURFACE_ALPINE_HEIGHT_ABOVE_SEA = 200;
   public static final int SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA = 120;
   public static final int MATERIAL_STONE = 0;
   public static final int MATERIAL_ANDESITE = 1;
   public static final int MATERIAL_GRAVEL = 2;
   public static final int MATERIAL_COBBLESTONE = 3;
   public static final int MATERIAL_DIORITE = 4;
   public static final int MATERIAL_PACKED_ICE = 5;
   public static final int MATERIAL_BLUE_ICE = 6;
   public static final int MATERIAL_SNOW = 7;
   private static final long FORM_PRIMARY_SALT = 2914627375160349221L;
   private static final long FORM_ACCENT_SALT = -6157224553818219851L;
   private static final long GLACIER_PRIMARY_SALT = 8724352394416290811L;
   private static final long GLACIER_ACCENT_SALT = -2389441726380115407L;

   private MountainSurfaceRules() {
   }

   /**
    * The land cover a column's ground, trees and biome follow. Every class but
    * water and missing data takes the blended border: keeping the raw 10 m pixels
    * made forest, town and snow edges staircases at fine scales, and left rock
    * pixels inside glaciers as perfect squares.
    */
   public static int resolveSurfaceCoverClass(int terrainCoverClass, int visualCoverClass) {
      return !isWaterLikeCoverClass(terrainCoverClass) && !isWaterLikeCoverClass(visualCoverClass) ? visualCoverClass : terrainCoverClass;
   }

   public static MountainSurfaceRules.ShorelineMaterial classifyShorelineMaterial(
      int surfaceCoverClass,
      byte climateGroup,
      int heightAboveSea,
      int slopeDiff,
      int convexity,
      MountainSurfaceRules.ShorelineKind shoreKind,
      int distanceToShore,
      boolean preferRedSand
   ) {
      if (shoreKind == MountainSurfaceRules.ShorelineKind.NONE || distanceToShore < 0) {
         return MountainSurfaceRules.ShorelineMaterial.NONE;
      } else if (surfaceCoverClass == ESA_WETLAND || surfaceCoverClass == ESA_MANGROVES) {
         return MountainSurfaceRules.ShorelineMaterial.PRESERVE_WETLAND;
      } else if (surfaceCoverClass == ESA_BUILT || surfaceCoverClass == ESA_SNOW_ICE) {
         return MountainSurfaceRules.ShorelineMaterial.NONE;
      } else {
         boolean ocean = shoreKind == MountainSurfaceRules.ShorelineKind.OCEAN;
         boolean aridClimate = climateGroup == 2;
         boolean coldClimate = climateGroup == 4 || climateGroup == 5;
         boolean tropicalClimate = climateGroup == 1;
         boolean sparseCover = surfaceCoverClass == ESA_BARE
            || surfaceCoverClass == ESA_SHRUBLAND
            || surfaceCoverClass == ESA_GRASSLAND
            || surfaceCoverClass == ESA_CROPLAND;
         boolean woodedCover = surfaceCoverClass == ESA_TREE_COVER || surfaceCoverClass == ESA_MOSS_LICHEN;
         boolean steepShore = slopeDiff >= (ocean ? 3 : 2) || convexity <= -2;
         boolean elevatedColdShore = heightAboveSea >= SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA && (slopeDiff >= 2 || coldClimate);
         if (steepShore || elevatedColdShore || coldClimate && distanceToShore <= 2) {
            return MountainSurfaceRules.ShorelineMaterial.GRAVEL;
         } else if (ocean) {
            if (aridClimate) {
               return preferRedSand ? MountainSurfaceRules.ShorelineMaterial.RED_SAND : MountainSurfaceRules.ShorelineMaterial.SAND;
            } else if (tropicalClimate || sparseCover || distanceToShore <= 4 || !woodedCover) {
               return MountainSurfaceRules.ShorelineMaterial.SAND;
            } else {
               return MountainSurfaceRules.ShorelineMaterial.NONE;
            }
         } else if (woodedCover && !aridClimate && !tropicalClimate) {
            return MountainSurfaceRules.ShorelineMaterial.NONE;
         } else if (aridClimate || tropicalClimate) {
            return distanceToShore <= 1 || sparseCover && distanceToShore <= 2
               ? MountainSurfaceRules.ShorelineMaterial.SAND
               : MountainSurfaceRules.ShorelineMaterial.NONE;
         } else {
            return distanceToShore <= 1 || sparseCover && distanceToShore <= 2
               ? MountainSurfaceRules.ShorelineMaterial.GRAVEL
               : MountainSurfaceRules.ShorelineMaterial.NONE;
         }
      }
   }

   public static boolean isWaterLikeCoverClass(int coverClass) {
      return coverClass == ESA_WATER || coverClass == ESA_MANGROVES || coverClass == ESA_NO_DATA;
   }

   public static boolean isTreeCoverClass(int coverClass) {
      return coverClass == ESA_TREE_COVER;
   }

   public static boolean isTreeMarkerCoverClass(int terrainCoverClass, int visualCoverClass) {
      int surfaceCoverClass = resolveSurfaceCoverClass(terrainCoverClass, visualCoverClass);
      return surfaceCoverClass == ESA_TREE_COVER || surfaceCoverClass == ESA_MANGROVES;
   }

   public static boolean isVegetatedCoverClass(int coverClass) {
      return coverClass == ESA_TREE_COVER
         || coverClass == ESA_SHRUBLAND
         || coverClass == ESA_GRASSLAND
         || coverClass == ESA_CROPLAND
         || coverClass == ESA_MOSS_LICHEN;
   }

   /**
    * Keeps distinctive biome materials authoritative over generic land-cover mountain materials.
    * ESA bare ground describes vegetation cover, so it must not turn elevated desert sand into stone.
    */
   public static boolean preservesBiomeSurfacePalette(boolean desertBiome, boolean badlandsBiome) {
      return desertBiome || badlandsBiome;
   }

   public static boolean isMountainRockyCover(int coverClass, int heightAboveSea) {
      // Keep vegetated land cover authoritative here. The generator can still
      // expose stone locally through its steep-slope surface fallback.
      return coverClass == ESA_NO_DATA
         ? heightAboveSea >= SURFACE_ALPINE_HEIGHT_ABOVE_SEA
         : coverClass == ESA_SNOW_ICE || coverClass == ESA_BARE;
   }

   public static boolean qualifiesForMountainPalette(int coverClass, int heightAboveSea, int slopeDiff, int convexity) {
      int ruggedness = slopeDiff + Math.max(0, -convexity);
      if (coverClass == ESA_NO_DATA) {
         return heightAboveSea >= SURFACE_ALPINE_HEIGHT_ABOVE_SEA
            || heightAboveSea >= SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA && (slopeDiff >= 3 || ruggedness >= 5);
      } else if (!isMountainRockyCover(coverClass, heightAboveSea)) {
         return false;
      } else if (coverClass == ESA_SNOW_ICE || heightAboveSea >= SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA) {
         return true;
      } else {
         return heightAboveSea >= SURFACE_ALPINE_HEIGHT_ABOVE_SEA || slopeDiff >= 3 || ruggedness >= 5;
      }
   }

   public static float vegetationTransitionWeight(int terrainCoverClass, int visualCoverClass, int heightAboveSea) {
      int surfaceCoverClass = resolveSurfaceCoverClass(terrainCoverClass, visualCoverClass);
      return vegetationTransitionWeightForSurfaceCoverClass(surfaceCoverClass, heightAboveSea);
   }

   public static float vegetationTransitionWeightForSurfaceCoverClass(int surfaceCoverClass, int heightAboveSea) {
      return switch (surfaceCoverClass) {
         case ESA_TREE_COVER -> 1.0F;
         case ESA_SHRUBLAND -> 0.6F;
         case ESA_GRASSLAND, ESA_CROPLAND -> heightAboveSea >= SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA ? 0.24F : 0.38F;
         case ESA_MOSS_LICHEN -> heightAboveSea >= SURFACE_ALPINE_HEIGHT_ABOVE_SEA ? 0.22F : 0.42F;
         default -> 0.0F;
      };
   }

   public static MountainSurfaceRules.ApproximateSurface classifyApproximateSurface(
      int terrainCoverClass, int visualCoverClass, int heightAboveSea, int slopeDiff, int convexity, boolean snowLikeTerrain
   ) {
      return classifyApproximateSurface(terrainCoverClass, visualCoverClass, heightAboveSea, slopeDiff, convexity, snowLikeTerrain, 0, 0);
   }

   public static MountainSurfaceRules.ApproximateSurface classifyApproximateSurface(
      int terrainCoverClass,
      int visualCoverClass,
      int heightAboveSea,
      int slopeDiff,
      int convexity,
      boolean snowLikeTerrain,
      int worldX,
      int worldZ
   ) {
      int surfaceCoverClass = resolveSurfaceCoverClass(terrainCoverClass, visualCoverClass);
      float vegetationTransitionWeight = vegetationTransitionWeightForSurfaceCoverClass(surfaceCoverClass, heightAboveSea);
      return classifyApproximateSurface(
         terrainCoverClass, visualCoverClass, heightAboveSea, slopeDiff, convexity, snowLikeTerrain, vegetationTransitionWeight, worldX, worldZ
      );
   }

   public static MountainSurfaceRules.ApproximateSurface classifyApproximateSurface(
      int terrainCoverClass,
      int visualCoverClass,
      int heightAboveSea,
      int slopeDiff,
      int convexity,
      boolean snowLikeTerrain,
      float vegetationTransitionWeight,
      int worldX,
      int worldZ
   ) {
      int surfaceCoverClass = resolveSurfaceCoverClass(terrainCoverClass, visualCoverClass);
      boolean snowSource = hasSnowSource(surfaceCoverClass, snowLikeTerrain);
      if (snowSource) {
         if (retainsMountainSnow(surfaceCoverClass, snowLikeTerrain, heightAboveSea, slopeDiff, convexity, worldX, worldZ)) {
            return new MountainSurfaceRules.ApproximateSurface(
               surfaceCoverClass, MountainSurfaceRules.ApproximatePalette.SNOW, snowForm(slopeDiff, convexity)
            );
         }
      }

      boolean mountainCandidate = snowSource
         || qualifiesForMountainPalette(surfaceCoverClass, heightAboveSea, slopeDiff, convexity);
      if (!mountainCandidate) {
         MountainSurfaceRules.MountainForm form = heightAboveSea >= SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA
               && isVegetatedCoverClass(surfaceCoverClass)
               && slopeDiff <= 2
            ? MountainSurfaceRules.MountainForm.ALPINE_MEADOW
            : MountainSurfaceRules.MountainForm.NONE;
         return new MountainSurfaceRules.ApproximateSurface(surfaceCoverClass, MountainSurfaceRules.ApproximatePalette.NONE, form);
      } else {
         MountainSurfaceRules.MountainForm form = landform(
            surfaceCoverClass, heightAboveSea, slopeDiff, convexity, worldX, worldZ
         );
         if (snowSource && prefersSnowStreak(surfaceCoverClass, snowLikeTerrain, heightAboveSea, slopeDiff, convexity, worldX, worldZ, form)) {
            return new MountainSurfaceRules.ApproximateSurface(surfaceCoverClass, MountainSurfaceRules.ApproximatePalette.SNOW_STREAK, form);
         } else {
            return new MountainSurfaceRules.ApproximateSurface(surfaceCoverClass, MountainSurfaceRules.ApproximatePalette.STONE, form);
         }
      }
   }

   public static boolean hasSnowSource(int surfaceCoverClass, boolean snowLikeTerrain) {
      return snowLikeTerrain || surfaceCoverClass == ESA_SNOW_ICE;
   }

   public static boolean retainsMountainSnow(
      int surfaceCoverClass, boolean snowLikeTerrain, int heightAboveSea, int slopeDiff, int convexity, int worldX, int worldZ
   ) {
      if (!hasSnowSource(surfaceCoverClass, snowLikeTerrain)) {
         return false;
      } else {
         return approximateSnowRetentionScore(heightAboveSea, slopeDiff, convexity, worldX, worldZ) >= 52;
      }
   }

   public static boolean prefersSnowStreak(
      int surfaceCoverClass,
      boolean snowLikeTerrain,
      int heightAboveSea,
      int slopeDiff,
      int convexity,
      int worldX,
      int worldZ,
      MountainSurfaceRules.MountainForm form
   ) {
      if (!hasSnowSource(surfaceCoverClass, snowLikeTerrain)
         || retainsMountainSnow(surfaceCoverClass, snowLikeTerrain, heightAboveSea, slopeDiff, convexity, worldX, worldZ)
         || heightAboveSea < SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA
         || slopeDiff < 3) {
         return false;
      } else {
         int score = slopeDiff * 11;
         score += Math.max(0, convexity) * 12;
         score += Math.max(0, (heightAboveSea - SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA) / 3);
         score += (int)Math.round((approximateMask(worldX, worldZ, 72, 8361902749107219433L) - 0.38) * 62.0);
         score += form == MountainSurfaceRules.MountainForm.DRAINAGE_CHUTE ? 24 : 0;
         score += form == MountainSurfaceRules.MountainForm.CLIFF_FACE ? 10 : 0;
         return score >= 68;
      }
   }

   public static boolean prefersMountainStonePatch(
      int surfaceCoverClass, int heightAboveSea, int slopeDiff, int convexity, float vegetationTransitionWeight, int worldX, int worldZ
   ) {
      if (!qualifiesForMountainPalette(surfaceCoverClass, heightAboveSea, slopeDiff, convexity)
         || heightAboveSea < SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA
         || slopeDiff < 2) {
         return false;
      } else {
         int ruggedness = slopeDiff + Math.max(0, -convexity);
         double scarMask = approximateMask(worldX + heightAboveSea * 2, worldZ - heightAboveSea, 160, 6792168434296017429L);
         double breakMask = approximateMask(worldX - heightAboveSea, worldZ + heightAboveSea * 2, 52, 2145517839928346117L);
         int score = -38;
         score += ruggedness * 8;
         score += Math.max(0, (heightAboveSea - SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA) / 4);
         score += convexity <= 0 ? 10 : -4;
         score -= Math.round(vegetationTransitionWeight * 34.0F);
         score += (int)Math.round((scarMask - 0.44) * 76.0);
         score += (int)Math.round((breakMask - 0.5) * 24.0);
         return score >= 34;
      }
   }

   private static int approximateSnowRetentionScore(int heightAboveSea, int slopeDiff, int convexity, int worldX, int worldZ) {
      int score = 58;
      score += Math.max(-12, Math.min(34, (heightAboveSea - 110) / 2));
      score -= slopeDiff * 13;
      score += Math.max(-20, Math.min(26, convexity * 10));
      score += (int)Math.round((approximateMask(worldX, worldZ, 112, 3476291847715014363L) - 0.5) * 24.0);
      if (heightAboveSea >= SURFACE_ALPINE_HEIGHT_ABOVE_SEA) {
         score += 6;
      }

      if (slopeDiff <= 1 && convexity >= -1) {
         score += 12;
      } else if (slopeDiff >= 5) {
         score -= 14;
      }

      return Math.max(0, Math.min(100, score));
   }

   /**
    * Landform of an exposed column. Slope is the steepest rise over a four-block
    * baseline and convexity is the four-neighbour mean minus the centre, so a
    * positive convexity on a slope means the ground steepens uphill.
    */
   public static MountainSurfaceRules.MountainForm landform(
      int surfaceCoverClass, int heightAboveSea, int slopeDiff, int convexity, int worldX, int worldZ
   ) {
      int ruggedness = slopeDiff + Math.max(0, -convexity);
      double cliffMask = approximateMask(worldX, worldZ, 144, 5516042115276107717L);
      if (surfaceCoverClass == ESA_SNOW_ICE && heightAboveSea >= SURFACE_ALPINE_HEIGHT_ABOVE_SEA && slopeDiff <= 3 && convexity >= -1) {
         return MountainSurfaceRules.MountainForm.SNOWFIELD;
      } else if (heightAboveSea >= SURFACE_ALPINE_HEIGHT_ABOVE_SEA && isGlacierHollow(slopeDiff, convexity)) {
         // A high hollow: a cirque floor, debris-filled where no ice is mapped.
         return MountainSurfaceRules.MountainForm.GLACIER_BASIN;
      } else if (slopeDiff >= 6 || ruggedness >= 9 && cliffMask > 0.28) {
         return MountainSurfaceRules.MountainForm.CLIFF_FACE;
      } else if (heightAboveSea >= SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA && slopeDiff >= 4 && convexity >= 1) {
         // Steep and concave: the gullies and couloirs that channel debris down a face.
         return MountainSurfaceRules.MountainForm.DRAINAGE_CHUTE;
      } else if (heightAboveSea >= SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA && slopeDiff >= 5) {
         return MountainSurfaceRules.MountainForm.STEEP_ROCKY_SLOPE;
      } else if (heightAboveSea >= SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA && slopeDiff >= 2 && convexity >= 1) {
         // Scree rests at roughly 27-44 degrees where the slope eases below steeper rock.
         return MountainSurfaceRules.MountainForm.TALUS_SLOPE;
      } else if (heightAboveSea >= SURFACE_ALPINE_HEIGHT_ABOVE_SEA && convexity <= -2 && slopeDiff >= 2) {
         return MountainSurfaceRules.MountainForm.RIDGE;
      } else if (heightAboveSea >= SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA && isVegetatedCoverClass(surfaceCoverClass) && slopeDiff <= 2) {
         return MountainSurfaceRules.MountainForm.ALPINE_MEADOW;
      } else {
         return MountainSurfaceRules.MountainForm.VALLEY_WALL;
      }
   }

   /** A gentle hollow, where a mapped glacier shows bare ice rather than snow. */
   public static boolean isGlacierHollow(int slopeDiff, int convexity) {
      return slopeDiff <= 2 && convexity >= 1;
   }

   /** Form of a column that keeps its mapped snow. */
   public static MountainSurfaceRules.MountainForm snowForm(int slopeDiff, int convexity) {
      return isGlacierHollow(slopeDiff, convexity)
         ? MountainSurfaceRules.MountainForm.GLACIER_BASIN
         : MountainSurfaceRules.MountainForm.SNOWFIELD;
   }

   /** Landforms covered in loose debris, which the steep-slope rock rule must not repaint. */
   public static boolean isLooseDebrisForm(MountainSurfaceRules.MountainForm form) {
      return form == MountainSurfaceRules.MountainForm.TALUS_SLOPE
         || form == MountainSurfaceRules.MountainForm.DRAINAGE_CHUTE
         || form == MountainSurfaceRules.MountainForm.GLACIER_BASIN;
   }

   /**
    * Top material of loose debris, as clustered patches per landform. Scree and
    * gullies are lighter than the rock faces above them. Rock faces and ridges
    * take the local bedrock from {@link StrataPolicy} instead.
    */
   public static int formSurfaceMaterial(MountainSurfaceRules.MountainForm form, int worldX, int worldZ) {
      int primary = SurfacePatchNoise.percent(worldX, worldZ, FORM_PRIMARY_SALT);
      int accent = SurfacePatchNoise.percent(worldX, worldZ, FORM_ACCENT_SALT);
      return switch (form) {
         case TALUS_SLOPE -> primary < 55
            ? MATERIAL_GRAVEL
            : accent < 45 ? MATERIAL_ANDESITE : accent < 78 ? MATERIAL_DIORITE : MATERIAL_COBBLESTONE;
         case DRAINAGE_CHUTE -> primary < 75 ? MATERIAL_GRAVEL : accent < 60 ? MATERIAL_COBBLESTONE : MATERIAL_ANDESITE;
         case GLACIER_BASIN -> primary < 50 ? MATERIAL_GRAVEL : accent < 60 ? MATERIAL_STONE : MATERIAL_COBBLESTONE;
         default -> MATERIAL_STONE;
      };
   }

   /** Surface of a mapped glacier's hollow: mostly bare ice, with snow patches and blue ice. */
   public static int glacierSurfaceMaterial(int worldX, int worldZ) {
      if (SurfacePatchNoise.percent(worldX, worldZ, GLACIER_PRIMARY_SALT) < 30) {
         return MATERIAL_SNOW;
      }

      return SurfacePatchNoise.percent(worldX, worldZ, GLACIER_ACCENT_SALT) < 17 ? MATERIAL_BLUE_ICE : MATERIAL_PACKED_ICE;
   }

   /** Share of a steep, unforested vegetated slope that shows rock outcrops. */
   public static int exposedRockPercent(int slopeDiff) {
      if (slopeDiff < 3) {
         return 0;
      } else if (slopeDiff == 3) {
         return 35;
      } else if (slopeDiff == 4) {
         return 60;
      } else {
         return slopeDiff == 5 ? 80 : 95;
      }
   }

   /** Share of a highland meadow covered by scree patches. */
   public static int alpineMeadowScreePercent(int slopeDiff) {
      return slopeDiff >= 2 ? 25 : 0;
   }

   private static double approximateMask(int worldX, int worldZ, int cellSize, long salt) {
      int cellX = Math.floorDiv(worldX, cellSize);
      int cellZ = Math.floorDiv(worldZ, cellSize);
      double fracX = (double)Math.floorMod(worldX, cellSize) / (double)cellSize;
      double fracZ = (double)Math.floorMod(worldZ, cellSize) / (double)cellSize;
      double v00 = approximateCellNoise(cellX, cellZ, salt);
      double v10 = approximateCellNoise(cellX + 1, cellZ, salt);
      double v01 = approximateCellNoise(cellX, cellZ + 1, salt);
      double v11 = approximateCellNoise(cellX + 1, cellZ + 1, salt);
      double i0 = lerp(fracX, v00, v10);
      double i1 = lerp(fracX, v01, v11);
      return lerp(fracZ, i0, i1);
   }

   private static double approximateCellNoise(int cellX, int cellZ, long salt) {
      long seed = salt ^ cellX * 341873128712L ^ cellZ * 132897987541L;
      seed ^= seed >>> 33;
      seed *= -49064778989728563L;
      seed ^= seed >>> 33;
      seed *= -4265267296055464877L;
      seed ^= seed >>> 33;
      long bits = seed >>> 11 & 9007199254740991L;
      return bits / 9.007199E15F;
   }

   private static double lerp(double delta, double start, double end) {
      return start + delta * (end - start);
   }

   public static enum ApproximatePalette {
      NONE,
      SNOW,
      SNOW_STREAK,
      STONE;
   }

   public record ApproximateSurface(
      int surfaceCoverClass, MountainSurfaceRules.ApproximatePalette palette, MountainSurfaceRules.MountainForm form
   ) {
      public ApproximateSurface(int surfaceCoverClass, MountainSurfaceRules.ApproximatePalette palette) {
         this(surfaceCoverClass, palette, MountainSurfaceRules.MountainForm.NONE);
      }

      public boolean isMountain() {
         return this.palette != MountainSurfaceRules.ApproximatePalette.NONE
            && this.palette != MountainSurfaceRules.ApproximatePalette.SNOW
            && this.palette != MountainSurfaceRules.ApproximatePalette.SNOW_STREAK;
      }

      public boolean isSnow() {
         return this.palette == MountainSurfaceRules.ApproximatePalette.SNOW
            || this.palette == MountainSurfaceRules.ApproximatePalette.SNOW_STREAK;
      }
   }

   public static enum MountainForm {
      NONE,
      RIDGE,
      CLIFF_FACE,
      STEEP_ROCKY_SLOPE,
      TALUS_SLOPE,
      VALLEY_WALL,
      SNOWFIELD,
      GLACIER_BASIN,
      DRAINAGE_CHUTE,
      ALPINE_MEADOW;
   }

   public static enum ShorelineKind {
      NONE,
      OCEAN,
      INLAND;
   }

   public static enum ShorelineMaterial {
      NONE,
      SAND,
      RED_SAND,
      GRAVEL,
      PRESERVE_WETLAND;
   }
}
