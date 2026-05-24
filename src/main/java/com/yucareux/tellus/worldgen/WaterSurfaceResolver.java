package com.yucareux.tellus.worldgen;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.world.data.cover.TellusLandCoverSource;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource;
import com.yucareux.tellus.world.data.mask.TellusLandMaskSource;
import com.yucareux.tellus.world.data.osm.OsmPerf;
import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.osm.TellusOsmWaterSource;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.Arrays;
import net.minecraft.util.Mth;

public final class WaterSurfaceResolver {
   private static final int ESA_NO_DATA = 0;
   private static final int ESA_WATER = 80;
   private static final byte WATER_NONE = 0;
   private static final byte WATER_INLAND = 1;
   private static final byte WATER_OCEAN = 2;
   private static final int REGION_SIZE = 64;
   private static final int MAX_REGION_CACHE = intProperty("tellus.waterRegionCacheSize", 1024, 64, 8192);
   private static final int MAX_NEAR_WATER_CACHE = intProperty("tellus.waterNearChunkCacheSize", 8192, 256, 65536);
   private static final int REGION_LOOKUP_CAPACITY = intProperty("tellus.waterRegionLookupCapacity", 4, 1, 16);
   private static final int CHUNK_SHIFT = 4;
   private static final int CHUNK_SIZE = 16;
   private static final int CHUNK_MASK = 15;
   private static final int CHUNK_AREA = 256;
   private static final int INLAND_SHORE_DEPTH1_LIMIT = 5;
   private static final int INLAND_SHORE_DEPTH3_LIMIT = 8;
   private static final int INLAND_SHORE_DEPTH4_LIMIT = 10;
   private static final int INLAND_RANDOM_DEPTH_MIN = 3;
   private static final int INLAND_RANDOM_DEPTH_MAX = 6;
   private static final int INLAND_MAX_DEPTH = 30;
   private static final int INLAND_DEEP_DISTANCE_STEP = 6;
   private static final int OCEAN_MIN_DEPTH = 3;
   private static final int MAX_WATER_WALL_HEIGHT = 8;
   private static final int WATER_CASCADE_STEP_HEIGHT = 2;
   private static final int WATER_TERRACE_TRIGGER_HEIGHT = 6;
   private static final int CLIFF_SLOPE_THRESHOLD = 5;
   private static final double RIVER_MIN_LENGTH_METERS = 750.0;
   private static final double RIVER_MAX_WIDTH_METERS = 400.0;
   private static final double RIVER_ASPECT_RATIO = 3.0;
   private static final double RIVER_LAKE_FILL_THRESHOLD = 0.6;
   private static final double RIVER_LAKE_ASPECT_FACTOR = 1.5;
   private static final double RIVER_LAKE_WIDTH_FACTOR = 0.75;
   private static final int RIVER_LAKE_MIN_WIDTH = 12;
   private static final double BORDER_HEIGHT_PERCENTILE = 0.1;
   private static final int SEA_LEVEL_TOLERANCE = 2;
   private static final double BELOW_SEA_CELL_RATIO = 0.9;
   private static final double LANDMASK_INLAND_RATIO = 0.6;
   private static final int COARSE_CONNECT_STEP = 8;
   private static final int LAKE_SMOOTH_PASSES = 1;
   private static final int MAX_REGION_MARGIN_BLOCKS = 512;
   private static final boolean DEFER_DETAILED_WATER = Boolean.parseBoolean(System.getProperty("tellus.chunkdetail.deferDetailedWater", "true"));
   private static final int DIST_COST_CARDINAL = 10;
   private static final int DIST_COST_DIAGONAL = 14;
   private static final int[] NEIGHBOR_OFFSETS = new int[]{1, 0, -1, 0, 0, 1, 0, -1};
   private static final int[] NEIGHBOR_OFFSETS_8 = new int[]{1, 0, -1, 0, 0, 1, 0, -1, 1, 1, 1, -1, -1, 1, -1, -1};
   private static final int[] NEIGHBOR_COSTS_8 = new int[]{
      DIST_COST_CARDINAL,
      DIST_COST_CARDINAL,
      DIST_COST_CARDINAL,
      DIST_COST_CARDINAL,
      DIST_COST_DIAGONAL,
      DIST_COST_DIAGONAL,
      DIST_COST_DIAGONAL,
      DIST_COST_DIAGONAL
   };
   private static final boolean DEBUG_WATER = Boolean.getBoolean("tellus.debugWater");
   private static final ThreadLocal<WaterSurfaceResolver.RegionScratch> REGION_SCRATCH = ThreadLocal.withInitial(WaterSurfaceResolver.RegionScratch::new);
   private final TellusLandCoverSource landCoverSource;
   private final TellusLandMaskSource landMaskSource;
   private final TellusElevationSource elevationSource;
   private final TellusOsmWaterSource osmWaterSource;
   private final EarthGeneratorSettings settings;
   private final boolean osmWaterEnabled;
   private final int seaLevel;
   private final Cache<Long, WaterSurfaceResolver.WaterRegionData> regionCache;
   private final Cache<Long, Boolean> nearWaterChunkCache;
   private final ThreadLocal<WaterSurfaceResolver.RegionLookup> regionLookup = ThreadLocal.withInitial(WaterSurfaceResolver.RegionLookup::new);
   private final long regionSalt;
   private final int riverLakeBlendDistance;
   private final int oceanBlendDistance;
   private final int cliffSlopeThreshold;
   private final boolean limitShorelineBlendBySlope;
   private final int riverMinLength;
   private final int riverMaxWidth;
   private final int maxDistanceToShore;
   private final int regionMargin;
   private final boolean regionClamped;

   public WaterSurfaceResolver(
      TellusLandCoverSource landCoverSource,
      TellusLandMaskSource landMaskSource,
      TellusElevationSource elevationSource,
      EarthGeneratorSettings settings
   ) {
      this.landCoverSource = landCoverSource;
      this.landMaskSource = landMaskSource;
      this.elevationSource = elevationSource;
      this.osmWaterSource = TellusWorldgenSources.osmWater();
      this.settings = settings;
      this.osmWaterEnabled = settings.enableWater();
      this.seaLevel = settings.resolveSeaLevel();
      this.riverLakeBlendDistance = clampBlend(settings.riverLakeShorelineBlend());
      this.oceanBlendDistance = clampBlend(settings.oceanShorelineBlend());
      double scale = Math.max(1.0, settings.worldScale());
      this.cliffSlopeThreshold = Math.max(2, (int)Math.round(CLIFF_SLOPE_THRESHOLD / Math.sqrt(scale)));
      this.limitShorelineBlendBySlope = settings.shorelineBlendCliffLimit();
      this.riverMinLength = this.metersToBlocks(RIVER_MIN_LENGTH_METERS);
      this.riverMaxWidth = this.metersToBlocks(RIVER_MAX_WIDTH_METERS);
      int maxDepthDistance = INLAND_SHORE_DEPTH4_LIMIT + Math.max(0, INLAND_MAX_DEPTH - INLAND_RANDOM_DEPTH_MAX) * INLAND_DEEP_DISTANCE_STEP;
      this.maxDistanceToShore = Math.max(maxDepthDistance, Math.max(this.riverLakeBlendDistance, this.oceanBlendDistance));
      int rawRegionMargin = this.maxDistanceToShore + SEA_LEVEL_TOLERANCE;
      this.regionMargin = Math.min(rawRegionMargin, MAX_REGION_MARGIN_BLOCKS);
      this.regionClamped = rawRegionMargin > this.regionMargin;
      this.regionCache = CacheBuilder.newBuilder().maximumSize(MAX_REGION_CACHE).build();
      this.nearWaterChunkCache = CacheBuilder.newBuilder().maximumSize(MAX_NEAR_WATER_CACHE).build();
      this.regionSalt = Double.doubleToLongBits(settings.worldScale()) ^ -7046029254386353131L;
   }

   public boolean isWaterClass(int coverClass) {
      return this.osmWaterEnabled || coverClass == ESA_WATER || coverClass == ESA_NO_DATA;
   }

   public WaterSurfaceResolver.WaterChunkData resolveChunkWaterData(int chunkX, int chunkZ) {
      return this.resolveChunkWaterData(chunkX, chunkZ, null);
   }

   public WaterSurfaceResolver.WaterChunkData resolveChunkWaterData(int chunkX, int chunkZ, int[] dryTerrainSurfaces) {
      return this.resolveChunkWaterDataFast(chunkX, chunkZ, dryTerrainSurfaces);
   }

   public WaterSurfaceResolver.WaterChunkData resolveChunkWaterDataFast(int chunkX, int chunkZ, int[] dryTerrainSurfaces) {
      long resolveStartNs = OsmPerf.now();
      int regionX = regionCoord(chunkX << CHUNK_SHIFT);
      int regionZ = regionCoord(chunkZ << CHUNK_SHIFT);
      WaterSurfaceResolver.WaterRegionData cached = this.getRegionIfPresent(regionX, regionZ);
      if (cached != null) {
         OsmPerf.recordWaterChunkResolve(OsmPerf.elapsedSince(resolveStartNs), true, false, false);
         return new WaterSurfaceResolver.WaterChunkData(chunkX, chunkZ, cached);
      } else if (!this.useLegacyBlockingWaterFallback()) {
         this.prefetchRegionsForChunk(chunkX, chunkZ, 1);
         WaterSurfaceResolver.WaterRegionData prefetched = this.getRegionIfPresent(regionX, regionZ);
         if (prefetched != null) {
            OsmPerf.recordWaterChunkResolve(OsmPerf.elapsedSince(resolveStartNs), false, false, true);
            return new WaterSurfaceResolver.WaterChunkData(chunkX, chunkZ, prefetched);
         }

         WaterSurfaceResolver.WaterChunkData fallback = dryTerrainSurfaces != null
            ? WaterSurfaceResolver.WaterChunkData.dryFromTerrain(dryTerrainSurfaces)
            : this.buildDryChunkData();
         OsmPerf.recordWaterChunkResolve(OsmPerf.elapsedSince(resolveStartNs), false, true, false);
         return fallback;
      } else {
         int padding = this.shorelinePadding();
         if (!this.hasWaterNearChunkCached(chunkX, chunkZ, padding)) {
            WaterSurfaceResolver.WaterChunkData fallback = dryTerrainSurfaces != null
               ? WaterSurfaceResolver.WaterChunkData.dryFromTerrain(dryTerrainSurfaces)
               : this.buildDryChunkData();
            OsmPerf.recordWaterChunkResolve(OsmPerf.elapsedSince(resolveStartNs), false, false, false);
            return fallback;
         } else {
            WaterSurfaceResolver.WaterRegionData region = this.resolveRegionData(regionX, regionZ);
            OsmPerf.recordWaterChunkResolve(OsmPerf.elapsedSince(resolveStartNs), false, false, true);
            return new WaterSurfaceResolver.WaterChunkData(chunkX, chunkZ, region);
         }
      }
   }

   public void prefetchRegionsForChunk(int chunkX, int chunkZ, int radius) {
      int padding = this.shorelinePadding();
      if (radius > 0 && (DEFER_DETAILED_WATER || this.hasWaterNearChunkCached(chunkX, chunkZ, padding))) {
         int blockX = chunkX << CHUNK_SHIFT;
         int blockZ = chunkZ << CHUNK_SHIFT;
         this.prefetchRegionsForBlock(blockX, blockZ, radius);
      }
   }

   public WaterSurfaceResolver.WaterInfo resolveWaterInfo(int blockX, int blockZ, int coverClass) {
      if (!this.isWaterClass(coverClass)) {
         return WaterSurfaceResolver.WaterInfo.LAND;
      } else {
         WaterSurfaceResolver.WaterColumnData column = this.resolveColumnData(blockX, blockZ, coverClass);
         return !column.hasWater()
            ? WaterSurfaceResolver.WaterInfo.LAND
            : new WaterSurfaceResolver.WaterInfo(true, column.isOcean(), column.waterSurface(), column.terrainSurface());
      }
   }

   public WaterSurfaceResolver.WaterInfo resolveFastWaterInfo(int blockX, int blockZ, int coverClass) {
      if (!this.osmWaterEnabled && !this.isWaterClass(coverClass)) {
         return WaterSurfaceResolver.WaterInfo.LAND;
      } else {
         WaterSurfaceResolver.WaterColumnData column = this.resolveFastColumnData(blockX, blockZ, coverClass);
         return !column.hasWater()
            ? WaterSurfaceResolver.WaterInfo.LAND
            : new WaterSurfaceResolver.WaterInfo(true, column.isOcean(), column.waterSurface(), column.terrainSurface());
      }
   }

   public WaterSurfaceResolver.WaterInfo resolveBlendedWaterInfo(int blockX, int blockZ, int coverClass) {
      return this.resolveWaterInfo(blockX, blockZ, coverClass);
   }

   public WaterSurfaceResolver.WaterColumnData resolveColumnData(int blockX, int blockZ) {
      int coverClass = this.landCoverSource.sampleCoverClass(blockX, blockZ, this.settings.worldScale());
      return this.resolveColumnData(blockX, blockZ, coverClass);
   }

   public WaterSurfaceResolver.WaterColumnData resolveColumnData(int blockX, int blockZ, int coverClass) {
      return this.resolveColumnData(blockX, blockZ, coverClass, this.settings.worldScale());
   }

   public WaterSurfaceResolver.WaterColumnData resolveColumnData(int blockX, int blockZ, int coverClass, double previewResolutionMeters) {
      int regionX = regionCoord(blockX);
      int regionZ = regionCoord(blockZ);
      if (!this.isWaterClass(coverClass)) {
         WaterSurfaceResolver.WaterRegionData cached = this.getRegionIfPresent(regionX, regionZ);
         if (cached != null) {
            int surface = cached.rawSurface(blockX, blockZ);
            return new WaterSurfaceResolver.WaterColumnData(false, false, surface, surface);
         } else {
            TellusLandMaskSource.LandMaskSample landMaskSample = this.landMaskSource.sampleLandMask(blockX, blockZ, this.settings.worldScale());
            int surface = this.sampleSurfaceHeight(blockX, blockZ, coverClass, landMaskSample, previewResolutionMeters);
            return new WaterSurfaceResolver.WaterColumnData(false, false, surface, surface);
         }
      } else {
         WaterSurfaceResolver.WaterRegionData cached = this.getRegionIfPresent(regionX, regionZ);
         if (cached != null) {
            return cached.columnData(blockX, blockZ);
         } else {
            if (!this.osmWaterEnabled && coverClass == ESA_NO_DATA) {
               TellusLandMaskSource.LandMaskSample landMaskSample = this.landMaskSource.sampleLandMask(blockX, blockZ, this.settings.worldScale());
               int surface = this.sampleSurfaceHeight(blockX, blockZ, coverClass, landMaskSample, previewResolutionMeters);
               if (surface > this.seaLevel) {
                  return new WaterSurfaceResolver.WaterColumnData(false, false, surface, surface);
               }
            }

            WaterSurfaceResolver.WaterRegionData region = this.resolveRegionData(regionX, regionZ);
            return region.columnData(blockX, blockZ);
         }
      }
   }

   public WaterSurfaceResolver.WaterColumnData resolveFastColumnData(int blockX, int blockZ) {
      int coverClass = this.landCoverSource.sampleCoverClass(blockX, blockZ, this.settings.worldScale());
      return this.resolveFastColumnData(blockX, blockZ, coverClass);
   }

   public WaterSurfaceResolver.WaterColumnData resolveFastColumnData(int blockX, int blockZ, int coverClass) {
      return this.resolveFastColumnData(blockX, blockZ, coverClass, this.settings.worldScale());
   }

   public WaterSurfaceResolver.WaterColumnData resolveFastColumnData(int blockX, int blockZ, int coverClass, double previewResolutionMeters) {
      if (!this.osmWaterEnabled) {
         return this.useLegacyBlockingWaterFallback()
            ? this.resolveColumnData(blockX, blockZ, coverClass, previewResolutionMeters)
            : this.coarseWaterColumnData(blockX, blockZ, coverClass, previewResolutionMeters);
      } else {
         int regionX = regionCoord(blockX);
         int regionZ = regionCoord(blockZ);
         WaterSurfaceResolver.WaterRegionData cached = this.getRegionIfPresent(regionX, regionZ);
         if (cached != null) {
            return cached.columnData(blockX, blockZ);
         } else if (!this.useLegacyBlockingWaterFallback()) {
            return this.coarseWaterColumnData(blockX, blockZ, coverClass, previewResolutionMeters);
         } else {
            TellusLandMaskSource.LandMaskSample landMaskSample = this.landMaskSource.sampleLandMask(blockX, blockZ, this.settings.worldScale());
            int surface = this.sampleSurfaceHeight(blockX, blockZ, coverClass, landMaskSample, previewResolutionMeters);
            TellusOsmWaterSource.FastWaterSample sample = this.osmWaterSource.sampleWater(blockX, blockZ, this.settings.worldScale(), OsmQueryMode.BLOCKING);
            if (!sample.hasWater()) {
               return new WaterSurfaceResolver.WaterColumnData(false, false, surface, surface);
            } else {
               boolean isOcean = OceanClassification.isOcean(sample.ocean(), landMaskSample, surface, coverClass, this.seaLevel);
               int waterSurface = isOcean ? this.seaLevel : Math.max(surface + 1, this.seaLevel);
               int terrainSurface = surface;
               if (isOcean) {
                  terrainSurface = Math.min(terrainSurface, waterSurface - OCEAN_MIN_DEPTH);
               } else {
                  int maxFloor = waterSurface - Math.max(1, OCEAN_MIN_DEPTH);
                  if (terrainSurface > maxFloor) {
                     terrainSurface = maxFloor;
                  }
               }

               if (terrainSurface >= waterSurface) {
                  terrainSurface = waterSurface - 1;
               }

               return new WaterSurfaceResolver.WaterColumnData(true, isOcean, terrainSurface, waterSurface);
            }
         }
      }
   }

   public void prefetchRegionsForBlock(int blockX, int blockZ, int radius) {
      int regionX = regionCoord(blockX);
      int regionZ = regionCoord(blockZ);
      int clampedRadius = Math.max(0, radius);

      for (int dz = -clampedRadius; dz <= clampedRadius; dz++) {
         for (int dx = -clampedRadius; dx <= clampedRadius; dx++) {
            this.prefetchRegion(regionX + dx, regionZ + dz);
         }
      }
   }

   public void prefetchRegionsForArea(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
      int minX = Math.min(minBlockX, maxBlockX);
      int maxX = Math.max(minBlockX, maxBlockX);
      int minZ = Math.min(minBlockZ, maxBlockZ);
      int maxZ = Math.max(minBlockZ, maxBlockZ);
      int minRegionX = regionCoord(minX);
      int maxRegionX = regionCoord(maxX);
      int minRegionZ = regionCoord(minZ);
      int maxRegionZ = regionCoord(maxZ);

      for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
         for (int rx = minRegionX; rx <= maxRegionX; rx++) {
            this.prefetchRegion(rx, rz);
         }
      }
   }

   private void prefetchRegion(int regionX, int regionZ) {
      long key = this.regionKey(regionX, regionZ);
      if (this.regionCache.getIfPresent(key) == null) {
         try {
            WaterSurfaceResolver.WaterRegionData region = (WaterSurfaceResolver.WaterRegionData)this.regionCache.get(
               key, () -> this.buildRegionData(regionX, regionZ)
            );
            this.regionLookup.get().put(regionX, regionZ, region);
         } catch (Exception error) {
            Tellus.LOGGER.debug("Failed to prefetch water region {}:{}", new Object[]{regionX, regionZ, error});
         }
      }
   }

   private WaterSurfaceResolver.WaterRegionData getRegionIfPresent(int regionX, int regionZ) {
      WaterSurfaceResolver.RegionLookup lookup = this.regionLookup.get();
      WaterSurfaceResolver.WaterRegionData region = lookup.find(regionX, regionZ);
      if (region != null) {
         return region;
      }

      long key = this.regionKey(regionX, regionZ);
      region = (WaterSurfaceResolver.WaterRegionData)this.regionCache.getIfPresent(key);
      if (region != null) {
         lookup.put(regionX, regionZ, region);
      }

      return region;
   }

   private WaterSurfaceResolver.WaterRegionData resolveRegionData(int regionX, int regionZ) {
      WaterSurfaceResolver.RegionLookup lookup = this.regionLookup.get();
      WaterSurfaceResolver.WaterRegionData region = lookup.find(regionX, regionZ);
      if (region != null) {
         return region;
      }

      long key = this.regionKey(regionX, regionZ);
      try {
         region = (WaterSurfaceResolver.WaterRegionData)this.regionCache.get(key, () -> this.buildRegionData(regionX, regionZ));
         lookup.put(regionX, regionZ, region);
         return region;
      } catch (Exception error) {
         Tellus.LOGGER.warn("Failed to build water region {}:{}", new Object[]{regionX, regionZ, error});
         throw new RuntimeException("Failed to build water region " + regionX + ":" + regionZ, error);
      }
   }

   private boolean hasWaterNearChunkCached(int chunkX, int chunkZ, int padding) {
      long key = pack(chunkX, chunkZ) ^ this.regionSalt;
      Boolean cached = (Boolean)this.nearWaterChunkCache.getIfPresent(key);
      if (cached != null) {
         return cached;
      }

      boolean hasWater = this.hasWaterNearChunk(chunkX, chunkZ, padding);
      this.nearWaterChunkCache.put(key, hasWater);
      return hasWater;
   }

   private boolean hasWaterNearChunk(int chunkX, int chunkZ, int padding) {
      int minX = (chunkX << CHUNK_SHIFT) - padding;
      int minZ = (chunkZ << CHUNK_SHIFT) - padding;
      int maxX = (chunkX << CHUNK_SHIFT) + CHUNK_MASK + padding;
      int maxZ = (chunkZ << CHUNK_SHIFT) + CHUNK_MASK + padding;
      double worldScale = this.settings.worldScale();
      if (this.osmWaterEnabled) {
         return this.osmWaterSource.hasWaterInArea(minX, minZ, maxX, maxZ, worldScale, 0, OsmQueryMode.BLOCKING);
      }

      for (int z = minZ; z <= maxZ; z++) {
         for (int x = minX; x <= maxX; x++) {
            int coverClass = this.landCoverSource.sampleCoverClass(x, z, worldScale);
            if (coverClass == ESA_WATER) {
               return true;
            }

            if (coverClass == ESA_NO_DATA) {
               TellusLandMaskSource.LandMaskSample landMaskSample = this.landMaskSource.sampleLandMask(x, z, worldScale);
               int surface = this.sampleSurfaceHeight(x, z, coverClass, landMaskSample);
               if (surface <= this.seaLevel) {
                  return true;
               }
            }
         }
      }

      return false;
   }

   private WaterSurfaceResolver.WaterChunkData buildDryChunkData() {
      int[] terrainSurface = new int[CHUNK_AREA];
      int[] waterSurface = new int[CHUNK_AREA];
      byte[] waterFlags = new byte[CHUNK_AREA];
      int coarseSurface = this.seaLevel - 1;
      Arrays.fill(terrainSurface, coarseSurface);
      Arrays.fill(waterSurface, coarseSurface);
      Arrays.fill(waterFlags, WATER_NONE);

      return WaterSurfaceResolver.WaterChunkData.fromArrays(terrainSurface, waterSurface, waterFlags, true);
   }

   private int shorelinePadding() {
      return Math.max(this.riverLakeBlendDistance, this.oceanBlendDistance);
   }

   private boolean useLegacyBlockingWaterFallback() {
      return !DEFER_DETAILED_WATER;
   }

   private WaterSurfaceResolver.WaterColumnData coarseWaterColumnData(int blockX, int blockZ, int coverClass, double previewResolutionMeters) {
      TellusLandMaskSource.LandMaskSample landMaskSample = this.landMaskSource.sampleLandMask(blockX, blockZ, this.settings.worldScale());
      int surface = this.sampleSurfaceHeight(blockX, blockZ, coverClass, landMaskSample, previewResolutionMeters);
      boolean isOcean = OceanClassification.isOcean(false, landMaskSample, surface, coverClass, this.seaLevel);
      if (!shouldEmitCoarseFallbackWater(this.osmWaterEnabled, coverClass, isOcean)) {
         return new WaterSurfaceResolver.WaterColumnData(false, false, surface, surface);
      } else {
         int waterSurface = isOcean ? this.seaLevel : Math.max(surface + 1, this.seaLevel);
         int terrainSurface = surface;
         int minDepth = Math.max(1, OCEAN_MIN_DEPTH);
         if (isOcean) {
            terrainSurface = Math.min(terrainSurface, waterSurface - OCEAN_MIN_DEPTH);
         } else if (terrainSurface > waterSurface - minDepth) {
            terrainSurface = waterSurface - minDepth;
         }

         if (terrainSurface >= waterSurface) {
            terrainSurface = waterSurface - 1;
         }

         return new WaterSurfaceResolver.WaterColumnData(true, isOcean, terrainSurface, waterSurface);
      }
   }

   static boolean shouldEmitCoarseFallbackWater(boolean osmWaterEnabled, int coverClass, boolean isOcean) {
      return isOcean || !osmWaterEnabled && coverClass == ESA_WATER;
   }

   private long regionKey(int regionX, int regionZ) {
      return pack(regionX, regionZ) ^ this.regionSalt;
   }

   private WaterSurfaceResolver.WaterRegionData buildRegionData(int regionX, int regionZ) {
      long startNanos = DEBUG_WATER ? System.nanoTime() : 0L;
      int regionMinX = regionX * REGION_SIZE;
      int regionMinZ = regionZ * REGION_SIZE;
      int gridSize = REGION_SIZE + this.regionMargin * 2;
      int gridMinX = regionMinX - this.regionMargin;
      int gridMinZ = regionMinZ - this.regionMargin;
      int gridArea = gridSize * gridSize;
      WaterSurfaceResolver.RegionScratch scratch = REGION_SCRATCH.get();
      scratch.ensureCapacity(gridArea);
      scratch.resetLists();
      boolean[] baseWaterMask = scratch.baseWaterMask;
      boolean[] noDataMask = scratch.noDataMask;
      boolean[] oceanHintMask = scratch.oceanHintMask;
      boolean[] landMaskLand = scratch.landMaskLand;
      int[] surfaceHeights = scratch.surfaceHeights;
      int coarseStep = COARSE_CONNECT_STEP;
      int inlandLevel = this.seaLevel + SEA_LEVEL_TOLERANCE;
      int coarseSize = (gridSize + coarseStep - 1) / coarseStep;
      int coarseArea = coarseSize * coarseSize;
      scratch.ensureCoarseCapacity(coarseArea);
      boolean[] coarseWater = scratch.coarseWater;
      boolean[] coarseInlandSeed = scratch.coarseInlandSeed;
      Arrays.fill(coarseWater, 0, coarseArea, false);
      Arrays.fill(coarseInlandSeed, 0, coarseArea, false);
      Arrays.fill(baseWaterMask, 0, gridArea, false);
      Arrays.fill(noDataMask, 0, gridArea, false);
      Arrays.fill(oceanHintMask, 0, gridArea, false);
      boolean hasWater = false;
      double worldScale = this.settings.worldScale();

      for (int dz = 0; dz < gridSize; dz++) {
         int worldZ = gridMinZ + dz;
         int row = dz * gridSize;
         int coarseZ = dz / coarseStep;
         int coarseRow = coarseZ * coarseSize;

         for (int dx = 0; dx < gridSize; dx++) {
            int worldX = gridMinX + dx;
            int index = row + dx;
            TellusLandMaskSource.LandMaskSample landMaskSample = this.landMaskSource.sampleLandMask(worldX, worldZ, worldScale);
            boolean maskKnown = landMaskSample.known();
            boolean landMaskIsLand = maskKnown && landMaskSample.land();
            int coverClass = this.landCoverSource.sampleCoverClass(worldX, worldZ, worldScale);
            int surface = this.sampleSurfaceHeight(worldX, worldZ, coverClass, landMaskSample);
            boolean isNoData = coverClass == ESA_NO_DATA;
            boolean oceanMask;
            if (maskKnown) {
               oceanMask = !landMaskIsLand && (isNoData || coverClass == ESA_WATER);
            } else {
               oceanMask = isNoData && surface <= this.seaLevel;
            }

            boolean isWater = coverClass == ESA_WATER || oceanMask && surface <= this.seaLevel;
            landMaskLand[index] = landMaskIsLand;
            surfaceHeights[index] = surface;
            if (!this.osmWaterEnabled) {
               baseWaterMask[index] = isWater;
               noDataMask[index] = oceanMask;
               if (isWater) {
                  hasWater = true;
                  if (!oceanMask && surface <= inlandLevel) {
                     int coarseIndex = coarseRow + dx / coarseStep;
                     coarseWater[coarseIndex] = true;
                  }
               }
            }
         }
      }

      if (this.osmWaterEnabled) {
         hasWater = this.populateOsmBaseWaterMask(gridMinX, gridMinZ, gridSize, surfaceHeights, baseWaterMask, noDataMask, oceanHintMask);

         for (int dz = 0; dz < gridSize; dz++) {
            int coarseZ = dz / coarseStep;
            int coarseRow = coarseZ * coarseSize;
            int row = dz * gridSize;

            for (int dx = 0; dx < gridSize; dx++) {
               int index = row + dx;
               if (baseWaterMask[index] && !noDataMask[index] && surfaceHeights[index] <= inlandLevel) {
                  coarseWater[coarseRow + dx / coarseStep] = true;
               }
            }
         }
      }

      if (!hasWater) {
         return this.buildDryRegionData(regionX, regionZ, regionMinX, regionMinZ, gridMinX, gridMinZ, gridSize, surfaceHeights, startNanos);
      } else {
         int[] componentIds = scratch.componentIds;
         Arrays.fill(componentIds, 0, gridArea, -1);
         WaterSurfaceResolver.ComponentData[] components = scratch.components;
         int componentCount = 0;

         for (int dz = 0; dz < gridSize; dz++) {
            int row = dz * gridSize;
            int coarseZ = dz / coarseStep;
            int coarseRow = coarseZ * coarseSize;

            for (int dx = 0; dx < gridSize; dx++) {
               int indexx = row + dx;
               if (baseWaterMask[indexx] && !noDataMask[indexx] && surfaceHeights[indexx] <= inlandLevel) {
                  boolean touchesBelowSeaLand = false;

                  for (int i = 0; i < NEIGHBOR_OFFSETS.length; i += 2) {
                     int nx = dx + NEIGHBOR_OFFSETS[i];
                     int nz = dz + NEIGHBOR_OFFSETS[i + 1];
                     if (nx >= 0 && nz >= 0 && nx < gridSize && nz < gridSize) {
                        int neighbor = nz * gridSize + nx;
                        if (!baseWaterMask[neighbor] && surfaceHeights[neighbor] <= inlandLevel) {
                           touchesBelowSeaLand = true;
                           break;
                        }
                     }
                  }

                  if (touchesBelowSeaLand) {
                     int coarseIndex = coarseRow + dx / coarseStep;
                     coarseInlandSeed[coarseIndex] = true;
                  }
               }
            }
         }

         for (int indexx = 0; indexx < gridArea; indexx++) {
            if (baseWaterMask[indexx] && componentIds[indexx] == -1) {
               WaterSurfaceResolver.ComponentData component = this.buildComponent(
                  indexx, componentCount, gridSize, gridMinX, gridMinZ, baseWaterMask, noDataMask, oceanHintMask, landMaskLand, surfaceHeights, componentIds
               );
               components[componentCount] = component;
               componentCount++;
            }
         }

         int[] waterSurface = scratch.waterSurface;
         int[] terrainSurface = scratch.terrainSurface;
         byte[] waterFlags = scratch.waterFlags;
         Arrays.fill(waterFlags, 0, gridArea, (byte)0);
         System.arraycopy(surfaceHeights, 0, terrainSurface, 0, gridArea);
         boolean[] inlandConnected = this.buildInlandConnectivity(scratch, coarseArea, coarseSize);

         for (int ix = 0; ix < componentCount; ix++) {
            WaterSurfaceResolver.ComponentData component = components[ix];
            boolean belowSea = component.cellCount > 0 && (double)component.belowSeaCellCount / component.cellCount >= BELOW_SEA_CELL_RATIO;
            boolean inlandConnectedComponent = belowSea && this.componentTouchesInlandConnected(component, inlandConnected, coarseSize, coarseStep, gridSize);
            boolean landMaskInland = component.cellCount > 0 && (double)component.landMaskLandCount / component.cellCount >= LANDMASK_INLAND_RATIO;
            boolean isOcean = component.oceanHinted || !landMaskInland && component.touchesNoData || !landMaskInland && belowSea && !inlandConnectedComponent;
            component.isOcean = isOcean;
            int componentSurface;
            if (isOcean) {
               componentSurface = this.seaLevel;
            } else {
               int spillHeight = component.borderHeights.isEmpty() ? component.averageHeight() : percentile(component.borderHeights, BORDER_HEIGHT_PERCENTILE);
               componentSurface = spillHeight;
            }

            this.fillComponentSurface(component, waterSurface, componentSurface);
            if (!isOcean) {
               int width = component.maxX - component.minX + 1;
               int height = component.maxZ - component.minZ + 1;
               int maxDim = Math.max(width, height);
               int minDim = Math.max(1, Math.min(width, height));
               double aspect = (double)maxDim / minDim;
               boolean riverShape = maxDim >= this.riverMinLength && minDim <= this.riverMaxWidth && aspect >= RIVER_ASPECT_RATIO;
               if (!riverShape && component.touchesEdge && !this.regionClamped) {
                  riverShape = maxDim >= this.riverMinLength;
               }

               if (riverShape && this.shouldTreatRiverAsLake(component, width, height, minDim, aspect)) {
                  riverShape = false;
               }

               if (riverShape) {
                  WaterSurfaceResolver.RiverSurface riverSurface = this.buildRiverSurface(component, componentSurface, gridSize);

                  for (int c = 0; c < component.cells.size(); c++) {
                     int cell = component.cells.getInt(c);
                     int x = cell % gridSize;
                     int z = cell / gridSize;
                     waterSurface[cell] = riverSurface.surfaceAt(x, z);
                  }
               }
            }
         }

         boolean[] inlandWaterMask = scratch.inlandWaterMask;
         boolean[] oceanComponentMask = scratch.oceanComponentMask;
         Arrays.fill(inlandWaterMask, 0, gridArea, false);
         Arrays.fill(oceanComponentMask, 0, gridArea, false);

         for (int ix = 0; ix < componentCount; ix++) {
            WaterSurfaceResolver.ComponentData componentx = components[ix];
            boolean ocean = componentx.isOcean;

            for (int c = 0; c < componentx.cells.size(); c++) {
               int cell = componentx.cells.getInt(c);
               if (ocean) {
                  oceanComponentMask[cell] = true;
               } else {
                  inlandWaterMask[cell] = true;
               }
            }
         }

         boolean[] waterMask = scratch.waterMask;

         for (int indexxx = 0; indexxx < gridArea; indexxx++) {
            waterMask[indexxx] = oceanComponentMask[indexxx] || inlandWaterMask[indexxx];
         }

         boolean[] landMask = scratch.landMask;

         for (int indexxx = 0; indexxx < gridArea; indexxx++) {
            landMask[indexxx] = !waterMask[indexxx];
         }

         this.applyWaterSurfaceTerraces(waterSurface, surfaceHeights, inlandWaterMask, landMask, gridSize);
         boolean[] cliffLandMask = scratch.cliffLandMask;
         boolean[] cliffWaterMask = scratch.cliffWaterMask;
         Arrays.fill(cliffLandMask, 0, gridArea, false);
         Arrays.fill(cliffWaterMask, 0, gridArea, false);

         for (int indexxx = 0; indexxx < gridArea; indexxx++) {
            if (waterMask[indexxx]) {
               int x = indexxx % gridSize;
               int z = indexxx / gridSize;
               int waterSurfaceY = waterSurface[indexxx];
               int waterTerrainY = surfaceHeights[indexxx];

               for (int ix = 0; ix < NEIGHBOR_OFFSETS.length; ix += 2) {
                  int nx = x + NEIGHBOR_OFFSETS[ix];
                  int nz = z + NEIGHBOR_OFFSETS[ix + 1];
                  if (nx >= 0 && nz >= 0 && nx < gridSize && nz < gridSize) {
                     int neighbor = nz * gridSize + nx;
                     if (landMask[neighbor]) {
                        int landHeight = surfaceHeights[neighbor];
                        if (landHeight - waterSurfaceY >= this.cliffSlopeThreshold) {
                           cliffWaterMask[indexxx] = true;
                        }

                        if (landHeight - waterTerrainY >= this.cliffSlopeThreshold) {
                           cliffLandMask[neighbor] = true;
                        }
                     }
                  }
               }
            }
         }

         IntArrayList shoreWater = scratch.shoreWater;
         shoreWater.clear();

         for (int indexxxx = 0; indexxxx < gridArea; indexxxx++) {
            if (inlandWaterMask[indexxxx]) {
               int x = indexxxx % gridSize;
               int z = indexxxx / gridSize;
               if (this.isShoreCell(x, z, gridSize, inlandWaterMask)) {
                  shoreWater.add(indexxxx);
               }
            }
         }

         int[] waterDistanceCost = scratch.waterDistanceCost;
         int maxDistanceBlocks = Math.min(this.maxDistanceToShore, this.regionMargin);
         this.computeWeightedDistance(waterDistanceCost, inlandWaterMask, shoreWater, gridSize, maxDistanceBlocks, DIST_COST_CARDINAL);
         int maxDistanceCost = maxDistanceBlocks * DIST_COST_CARDINAL;

         for (int indexxxxx = 0; indexxxxx < gridArea; indexxxxx++) {
            if (oceanComponentMask[indexxxxx]) {
               waterFlags[indexxxxx] = WATER_OCEAN;
               int floor = surfaceHeights[indexxxxx];
               int maxFloor = waterSurface[indexxxxx] - OCEAN_MIN_DEPTH;
               if (floor > maxFloor) {
                  floor = maxFloor;
               }

               terrainSurface[indexxxxx] = floor;
            } else if (inlandWaterMask[indexxxxx]) {
               waterFlags[indexxxxx] = WATER_INLAND;
               if (cliffWaterMask[indexxxxx]) {
                  int floor = surfaceHeights[indexxxxx];
                  int maxFloor = waterSurface[indexxxxx] - OCEAN_MIN_DEPTH;
                  if (floor > maxFloor) {
                     floor = maxFloor;
                  }

                  terrainSurface[indexxxxx] = floor;
               } else {
                  int distanceCost = waterDistanceCost[indexxxxx];
                  if (distanceCost == Integer.MAX_VALUE) {
                     distanceCost = maxDistanceCost;
                  }

                  int componentId = componentIds[indexxxxx];
                  if (componentId >= 0 && componentId < componentCount) {
                     WaterSurfaceResolver.ComponentData componentx = components[componentId];
                     if (componentx != null && !componentx.isOcean) {
                        componentx.maxDistanceCost = Math.max(componentx.maxDistanceCost, distanceCost);
                     }
                  }

                  double distance = distanceCost / (double)DIST_COST_CARDINAL;
                  int x = indexxxxx % gridSize;
                  int z = indexxxxx / gridSize;
                  int depth = this.computeInlandDepth(distance, gridMinX + x, gridMinZ + z);
                  int floor = waterSurface[indexxxxx] - depth;
                  if (floor >= waterSurface[indexxxxx]) {
                     floor = waterSurface[indexxxxx] - 1;
                  }

                  terrainSurface[indexxxxx] = floor;
               }
            }
         }

         this.applyShorelineBlend(terrainSurface, surfaceHeights, waterSurface, inlandWaterMask, landMask, cliffLandMask, gridSize, this.riverLakeBlendDistance);
         this.applyShorelineBlend(terrainSurface, surfaceHeights, waterSurface, oceanComponentMask, landMask, cliffLandMask, gridSize, this.oceanBlendDistance);
         this.smoothLakeBeds(
            terrainSurface, waterSurface, inlandWaterMask, cliffWaterMask, componentIds, components, componentCount, waterDistanceCost, gridSize
         );
         this.applyShorelineWallClamp(terrainSurface, waterSurface, waterMask, landMask, cliffLandMask, gridSize);
         int[] regionTerrain = new int[REGION_SIZE * REGION_SIZE];
         int[] regionWater = new int[REGION_SIZE * REGION_SIZE];
         int[] regionRaw = new int[REGION_SIZE * REGION_SIZE];
         byte[] regionFlags = new byte[REGION_SIZE * REGION_SIZE];

         for (int dz = 0; dz < REGION_SIZE; dz++) {
            int worldZ = regionMinZ + dz;
            int gridZ = worldZ - gridMinZ;
            int gridRow = gridZ * gridSize;
            int regionRow = dz * REGION_SIZE;

            for (int dxx = 0; dxx < REGION_SIZE; dxx++) {
               int worldXx = regionMinX + dxx;
               int gridX = worldXx - gridMinX;
               int gridIndex = gridRow + gridX;
               int regionIndex = regionRow + dxx;
               int terrain = terrainSurface[gridIndex];
               regionTerrain[regionIndex] = terrain;
               byte flag = waterFlags[gridIndex];
               regionFlags[regionIndex] = flag;
               regionWater[regionIndex] = flag == WATER_NONE ? terrain : waterSurface[gridIndex];
               regionRaw[regionIndex] = surfaceHeights[gridIndex];
            }
         }

         if (DEBUG_WATER) {
            long elapsed = System.nanoTime() - startNanos;
            Tellus.LOGGER
               .info(
                  "Water region {}:{} computed in {} ms (scale {}, margin {})",
                  new Object[]{regionX, regionZ, elapsed / 1000000L, this.settings.worldScale(), this.regionMargin}
               );
         }

         clearComponents(components, componentCount);
         return new WaterSurfaceResolver.WaterRegionData(regionMinX, regionMinZ, regionTerrain, regionWater, regionFlags, regionRaw);
      }
   }

   private WaterSurfaceResolver.WaterRegionData buildDryRegionData(
      int regionX, int regionZ, int regionMinX, int regionMinZ, int gridMinX, int gridMinZ, int gridSize, int[] surfaceHeights, long startNanos
   ) {
      int[] regionTerrain = new int[REGION_SIZE * REGION_SIZE];
      int[] regionWater = new int[REGION_SIZE * REGION_SIZE];
      int[] regionRaw = new int[REGION_SIZE * REGION_SIZE];
      byte[] regionFlags = new byte[REGION_SIZE * REGION_SIZE];

      for (int dz = 0; dz < REGION_SIZE; dz++) {
         int worldZ = regionMinZ + dz;
         int gridZ = worldZ - gridMinZ;
         int gridRow = gridZ * gridSize;
         int regionRow = dz * REGION_SIZE;

         for (int dx = 0; dx < REGION_SIZE; dx++) {
            int worldX = regionMinX + dx;
            int gridX = worldX - gridMinX;
            int gridIndex = gridRow + gridX;
            int regionIndex = regionRow + dx;
            int terrain = surfaceHeights[gridIndex];
            regionTerrain[regionIndex] = terrain;
            regionWater[regionIndex] = terrain;
            regionRaw[regionIndex] = terrain;
            regionFlags[regionIndex] = WATER_NONE;
         }
      }

      if (DEBUG_WATER) {
         long elapsed = System.nanoTime() - startNanos;
         Tellus.LOGGER
            .info(
               "Water region {}:{} computed in {} ms (scale {}, margin {})",
               new Object[]{regionX, regionZ, elapsed / 1000000L, this.settings.worldScale(), this.regionMargin}
            );
      }

      return new WaterSurfaceResolver.WaterRegionData(regionMinX, regionMinZ, regionTerrain, regionWater, regionFlags, regionRaw);
   }

   private WaterSurfaceResolver.ComponentData buildComponent(
      int startIndex,
      int componentId,
      int gridSize,
      int gridMinX,
      int gridMinZ,
      boolean[] waterMask,
      boolean[] noDataMask,
      boolean[] oceanHintMask,
      boolean[] landMaskLand,
      int[] surfaceHeights,
      int[] componentIds
   ) {
      IntArrayList cells = new IntArrayList();
      IntArrayList borderHeights = new IntArrayList();
      WaterSurfaceResolver.ComponentData component = new WaterSurfaceResolver.ComponentData(componentId, cells, borderHeights);
      cells.add(startIndex);
      componentIds[startIndex] = componentId;

      for (int queueIndex = 0; queueIndex < cells.size(); queueIndex++) {
         int index = cells.getInt(queueIndex);
         int x = index % gridSize;
         int z = index / gridSize;
         int height = surfaceHeights[index];
         component.heightSum += height;
         component.cellCount++;
         if (height <= this.seaLevel + SEA_LEVEL_TOLERANCE) {
            component.belowSeaCellCount++;
         }

         component.minX = Math.min(component.minX, x);
         component.maxX = Math.max(component.maxX, x);
         component.minZ = Math.min(component.minZ, z);
         component.maxZ = Math.max(component.maxZ, z);
         if (height < component.minHeight) {
            component.minHeight = height;
            component.minHeightIndex = index;
         }

         if (height > component.maxHeight) {
            component.maxHeight = height;
            component.maxHeightIndex = index;
         }

         if (noDataMask[index]) {
            component.touchesNoData = true;
         }

         if (oceanHintMask[index]) {
            component.oceanHinted = true;
         }

         if (landMaskLand[index]) {
            component.landMaskLandCount++;
         }

         if (x == 0 || z == 0 || x == gridSize - 1 || z == gridSize - 1) {
            component.touchesEdge = true;
         }

         for (int i = 0; i < NEIGHBOR_OFFSETS.length; i += 2) {
            int nx = x + NEIGHBOR_OFFSETS[i];
            int nz = z + NEIGHBOR_OFFSETS[i + 1];
            if (nx >= 0 && nz >= 0 && nx < gridSize && nz < gridSize) {
               int neighbor = nz * gridSize + nx;
               if (!waterMask[neighbor]) {
                  borderHeights.add(surfaceHeights[neighbor]);
               } else if (componentIds[neighbor] == -1) {
                  componentIds[neighbor] = componentId;
                  cells.add(neighbor);
               }
            } else {
               component.touchesEdge = true;
            }
         }
      }

      return component;
   }

   private WaterSurfaceResolver.RiverSurface buildRiverSurface(WaterSurfaceResolver.ComponentData component, int inlandSurface, int gridSize) {
      int minIndex = component.minHeightIndex;
      int maxIndex = component.maxHeightIndex;
      int minX = minIndex % gridSize;
      int minZ = minIndex / gridSize;
      int maxX = maxIndex % gridSize;
      int maxZ = maxIndex / gridSize;
      double axisX = maxX - minX;
      double axisZ = maxZ - minZ;
      double axisLength = Math.sqrt(axisX * axisX + axisZ * axisZ);
      if (axisLength < 1.0) {
         return new WaterSurfaceResolver.RiverSurface(minX, minZ, 0.0, 0.0, 0.0, 1.0, inlandSurface, inlandSurface);
      } else {
         double ux = axisX / axisLength;
         double uz = axisZ / axisLength;
         double minProj = Double.POSITIVE_INFINITY;
         double maxProj = Double.NEGATIVE_INFINITY;

         for (int i = 0; i < component.cells.size(); i++) {
            int cell = component.cells.getInt(i);
            int x = cell % gridSize;
            int z = cell / gridSize;
            double proj = (x - minX) * ux + (z - minZ) * uz;
            minProj = Math.min(minProj, proj);
            maxProj = Math.max(maxProj, proj);
         }

         double length = Math.max(1.0, maxProj - minProj);
         int flatSurface = Math.min(inlandSurface, component.maxHeight);
         return new WaterSurfaceResolver.RiverSurface(minX, minZ, ux, uz, minProj, length, flatSurface, flatSurface);
      }
   }

   private int computeInlandDepth(double distance, int worldX, int worldZ) {
      if (distance <= INLAND_SHORE_DEPTH1_LIMIT) {
         return 1;
      } else if (distance <= INLAND_SHORE_DEPTH3_LIMIT) {
         return 3;
      } else if (distance <= INLAND_SHORE_DEPTH4_LIMIT) {
         return 4;
      } else {
         long seed = seedFromCoords(worldX, 5, worldZ);
         int jitterRange = INLAND_RANDOM_DEPTH_MAX - INLAND_RANDOM_DEPTH_MIN + 1;
         int jitter = INLAND_RANDOM_DEPTH_MIN + Math.floorMod(seed, jitterRange);
         int extra = (int)Math.floor(Math.max(0.0, distance - INLAND_SHORE_DEPTH4_LIMIT) / INLAND_DEEP_DISTANCE_STEP);
         int depth = jitter + extra;
         return Math.min(INLAND_MAX_DEPTH, depth);
      }
   }

   private boolean[] buildInlandConnectivity(WaterSurfaceResolver.RegionScratch scratch, int coarseArea, int coarseSize) {
      boolean[] coarseWater = scratch.coarseWater;
      boolean[] coarseInlandSeed = scratch.coarseInlandSeed;
      boolean[] coarseInlandConnected = scratch.coarseInlandConnected;
      Arrays.fill(coarseInlandConnected, 0, coarseArea, false);
      IntArrayList queue = scratch.coarseQueue;
      queue.clear();

      for (int i = 0; i < coarseArea; i++) {
         if (coarseWater[i] && coarseInlandSeed[i]) {
            coarseInlandConnected[i] = true;
            queue.add(i);
         }
      }

      for (int qi = 0; qi < queue.size(); qi++) {
         int index = queue.getInt(qi);
         int x = index % coarseSize;
         int z = index / coarseSize;

         for (int ix = 0; ix < NEIGHBOR_OFFSETS.length; ix += 2) {
            int nx = x + NEIGHBOR_OFFSETS[ix];
            int nz = z + NEIGHBOR_OFFSETS[ix + 1];
            if (nx >= 0 && nz >= 0 && nx < coarseSize && nz < coarseSize) {
               int neighbor = nz * coarseSize + nx;
               if (coarseWater[neighbor] && !coarseInlandConnected[neighbor]) {
                  coarseInlandConnected[neighbor] = true;
                  queue.add(neighbor);
               }
            }
         }
      }

      return coarseInlandConnected;
   }

   private boolean componentTouchesInlandConnected(
      WaterSurfaceResolver.ComponentData component, boolean[] inlandConnected, int coarseSize, int step, int gridSize
   ) {
      for (int i = 0; i < component.cells.size(); i++) {
         int cell = component.cells.getInt(i);
         int x = cell % gridSize;
         int z = cell / gridSize;
         int coarseIndex = z / step * coarseSize + x / step;
         if (inlandConnected[coarseIndex]) {
            return true;
         }
      }

      return false;
   }

   private boolean populateOsmBaseWaterMask(
      int gridMinX,
      int gridMinZ,
      int gridSize,
      int[] surfaceHeights,
      boolean[] baseWaterMask,
      boolean[] noDataMask,
      boolean[] oceanHintMask
   ) {
      int maxBlockX = gridMinX + gridSize - 1;
      int maxBlockZ = gridMinZ + gridSize - 1;
      long queryStartNs = OsmPerf.now();
      TellusOsmWaterSource.WaterQueryResult query = this.osmWaterSource
         .waterForAreaWithStatus(gridMinX, gridMinZ, maxBlockX, maxBlockZ, this.settings.worldScale(), 0, OsmQueryMode.BLOCKING);
      OsmPerf.recordWaterQuery(OsmPerf.elapsedSince(queryStartNs), query.features().size());
      if (query.features().isEmpty()) {
         return false;
      } else {
         double blocksPerDegree = EarthProjection.blocksPerDegree(this.settings.worldScale());

         for (OsmWaterFeature feature : query.features()) {
            this.rasterizeOsmWaterFeature(feature, gridMinX, gridMinZ, gridSize, blocksPerDegree, baseWaterMask, noDataMask, oceanHintMask);
         }

         for (boolean water : baseWaterMask) {
            if (water) {
               return true;
            }
         }

         return false;
      }
   }

   private void rasterizeOsmWaterFeature(
      OsmWaterFeature feature,
      int gridMinX,
      int gridMinZ,
      int gridSize,
      double blocksPerDegree,
      boolean[] baseWaterMask,
      boolean[] noDataMask,
      boolean[] oceanHintMask
   ) {
      int partCount = feature.partCount();
      double[][] partXs = new double[partCount][];
      double[][] partZs = new double[partCount][];
      int minWorldX = Integer.MAX_VALUE;
      int maxWorldX = Integer.MIN_VALUE;
      int minWorldZ = Integer.MAX_VALUE;
      int maxWorldZ = Integer.MIN_VALUE;

      for (int part = 0; part < partCount; part++) {
         int points = feature.pointCount(part);
         partXs[part] = new double[points];
         partZs[part] = new double[points];

         for (int point = 0; point < points; point++) {
            double worldX = feature.lonAt(part, point) * blocksPerDegree;
            double worldZ = EarthProjection.latToBlockZ(feature.latAt(part, point), this.settings.worldScale());
            partXs[part][point] = worldX;
            partZs[part][point] = worldZ;
            minWorldX = Math.min(minWorldX, Mth.floor(worldX));
            maxWorldX = Math.max(maxWorldX, Mth.ceil(worldX));
            minWorldZ = Math.min(minWorldZ, Mth.floor(worldZ));
            maxWorldZ = Math.max(maxWorldZ, Mth.ceil(worldZ));
         }
      }

      int gridMaxX = gridMinX + gridSize - 1;
      int gridMaxZ = gridMinZ + gridSize - 1;
      if (feature.lineGeometry()) {
         for (int part = 0; part < partCount; part++) {
            double[] xs = partXs[part];
            double[] zs = partZs[part];

            for (int point = 1; point < xs.length; point++) {
               this.rasterizeOsmLineSegment(
                  xs[point - 1],
                  zs[point - 1],
                  xs[point],
                  zs[point],
                  gridMinX,
                  gridMinZ,
                  gridMaxX,
                  gridMaxZ,
                  baseWaterMask,
                  noDataMask,
                  oceanHintMask,
                  feature.oceanHint(),
                  gridSize
               );
            }
         }
      } else {
         int clampedMinX = Math.max(gridMinX, minWorldX);
         int clampedMaxX = Math.min(gridMaxX, maxWorldX);
         int clampedMinZ = Math.max(gridMinZ, minWorldZ);
         int clampedMaxZ = Math.min(gridMaxZ, maxWorldZ);
         if (clampedMaxX >= clampedMinX && clampedMaxZ >= clampedMinZ) {
            ScanlinePolygonRasterizer.fill(
               partXs,
               partZs,
               clampedMinX,
               clampedMinZ,
               clampedMaxX,
               clampedMaxZ,
               (worldX, worldZ) -> this.markOsmWaterCell(
                     worldX,
                     worldZ,
                     gridMinX,
                     gridMinZ,
                     gridSize,
                     baseWaterMask,
                     noDataMask,
                     oceanHintMask,
                     feature.oceanHint()
                  )
            );
         }
      }
   }

   private void rasterizeOsmLineSegment(
      double startX,
      double startZ,
      double endX,
      double endZ,
      int gridMinX,
      int gridMinZ,
      int gridMaxX,
      int gridMaxZ,
      boolean[] baseWaterMask,
      boolean[] noDataMask,
      boolean[] oceanHintMask,
      boolean oceanHint,
      int gridSize
   ) {
      double halfWidth = 0.5;
      double maxDistanceSq = halfWidth * halfWidth + 1.0E-6;
      int minX = Math.max(gridMinX, Mth.floor(Math.min(startX, endX) - halfWidth - 1.0));
      int maxX = Math.min(gridMaxX, Mth.floor(Math.max(startX, endX) + halfWidth + 1.0));
      int minZ = Math.max(gridMinZ, Mth.floor(Math.min(startZ, endZ) - halfWidth - 1.0));
      int maxZ = Math.min(gridMaxZ, Mth.floor(Math.max(startZ, endZ) + halfWidth + 1.0));

      for (int worldZ = minZ; worldZ <= maxZ; worldZ++) {
         for (int worldX = minX; worldX <= maxX; worldX++) {
            double distanceSq = distanceToSegmentSq(worldX, worldZ, startX, startZ, endX, endZ);
            if (!(distanceSq > maxDistanceSq)) {
               this.markOsmWaterCell(worldX, worldZ, gridMinX, gridMinZ, gridSize, baseWaterMask, noDataMask, oceanHintMask, oceanHint);
            }
         }
      }
   }

   private void markOsmWaterCell(
      int worldX,
      int worldZ,
      int gridMinX,
      int gridMinZ,
      int gridSize,
      boolean[] baseWaterMask,
      boolean[] noDataMask,
      boolean[] oceanHintMask,
      boolean oceanHint
   ) {
      int localX = worldX - gridMinX;
      int localZ = worldZ - gridMinZ;
      if (localX >= 0 && localZ >= 0 && localX < gridSize && localZ < gridSize) {
         int index = localZ * gridSize + localX;
         baseWaterMask[index] = true;
         if (oceanHint) {
            noDataMask[index] = true;
            oceanHintMask[index] = true;
         }
      }
   }

   private static double distanceToSegmentSq(double px, double pz, double ax, double az, double bx, double bz) {
      double dx = bx - ax;
      double dz = bz - az;
      double lengthSq = dx * dx + dz * dz;
      if (lengthSq <= 1.0E-9) {
         double distX = px - ax;
         double distZ = pz - az;
         return distX * distX + distZ * distZ;
      } else {
         double t = ((px - ax) * dx + (pz - az) * dz) / lengthSq;
         t = Mth.clamp(t, 0.0, 1.0);
         double projX = ax + t * dx;
         double projZ = az + t * dz;
         double distX = px - projX;
         double distZ = pz - projZ;
         return distX * distX + distZ * distZ;
      }
   }

   private void applyWaterSurfaceTerraces(int[] waterSurface, int[] surfaceHeights, boolean[] inlandWaterMask, boolean[] landMask, int gridSize) {
      int gridArea = gridSize * gridSize;
      WaterSurfaceResolver.RegionScratch scratch = REGION_SCRATCH.get();
      IntArrayList queue = scratch.cascadeQueue;
      queue.clear();
      boolean[] cascadeMask = scratch.cascadeMask;
      Arrays.fill(cascadeMask, 0, gridArea, false);

      for (int index = 0; index < gridArea; index++) {
         if (inlandWaterMask[index]) {
            int x = index % gridSize;
            int z = index / gridSize;
            int minLandHeight = Integer.MAX_VALUE;

            for (int i = 0; i < NEIGHBOR_OFFSETS.length; i += 2) {
               int nx = x + NEIGHBOR_OFFSETS[i];
               int nz = z + NEIGHBOR_OFFSETS[i + 1];
               if (nx >= 0 && nz >= 0 && nx < gridSize && nz < gridSize) {
                  int neighbor = nz * gridSize + nx;
                  if (landMask[neighbor]) {
                     minLandHeight = Math.min(minLandHeight, surfaceHeights[neighbor]);
                  }
               }
            }

            int surface = waterSurface[index];
            int cap = Integer.MAX_VALUE;
            if (minLandHeight != Integer.MAX_VALUE) {
               cap = minLandHeight + MAX_WATER_WALL_HEIGHT;
               if (surface > cap) {
                  surface = cap;
               }
            }

            int minSurface = surfaceHeights[index] + 1;
            boolean shouldTerrace = minSurface <= cap && minSurface - surface > WATER_TERRACE_TRIGGER_HEIGHT;
            if (shouldTerrace) {
               surface = minSurface;
            }

            waterSurface[index] = surface;
            if (shouldTerrace) {
               cascadeMask[index] = true;
               queue.add(index);
            }
         }
      }

      for (int qi = 0; qi < queue.size(); qi++) {
         int indexx = queue.getInt(qi);
         if (cascadeMask[indexx]) {
            int x = indexx % gridSize;
            int z = indexx / gridSize;
            int surfacex = waterSurface[indexx];

            for (int ix = 0; ix < NEIGHBOR_OFFSETS.length; ix += 2) {
               int nx = x + NEIGHBOR_OFFSETS[ix];
               int nz = z + NEIGHBOR_OFFSETS[ix + 1];
               if (nx >= 0 && nz >= 0 && nx < gridSize && nz < gridSize) {
                  int neighbor = nz * gridSize + nx;
                  if (cascadeMask[neighbor]) {
                     int target = surfacex + WATER_CASCADE_STEP_HEIGHT;
                     int minSurfacex = surfaceHeights[neighbor] + 1;
                     if (target < minSurfacex) {
                        target = minSurfacex;
                     }

                     if (target < waterSurface[neighbor]) {
                        waterSurface[neighbor] = target;
                        queue.add(neighbor);
                     }
                  }
               }
            }
         }
      }
   }

   private void applyShorelineWallClamp(
      int[] terrainSurface, int[] waterSurface, boolean[] waterMask, boolean[] landMask, boolean[] cliffLandMask, int gridSize
   ) {
      int gridArea = gridSize * gridSize;

      for (int index = 0; index < gridArea; index++) {
         if (landMask[index] && !cliffLandMask[index]) {
            int x = index % gridSize;
            int z = index / gridSize;
            int minWaterSurface = Integer.MAX_VALUE;

            for (int i = 0; i < NEIGHBOR_OFFSETS.length; i += 2) {
               int nx = x + NEIGHBOR_OFFSETS[i];
               int nz = z + NEIGHBOR_OFFSETS[i + 1];
               if (nx >= 0 && nz >= 0 && nx < gridSize && nz < gridSize) {
                  int neighbor = nz * gridSize + nx;
                  if (waterMask[neighbor]) {
                     minWaterSurface = Math.min(minWaterSurface, waterSurface[neighbor]);
                  }
               }
            }

            if (minWaterSurface != Integer.MAX_VALUE) {
               int terrain = terrainSurface[index];
               int diff = minWaterSurface - terrain;
               if (diff > 0 && diff <= WATER_CASCADE_STEP_HEIGHT) {
                  terrainSurface[index] = minWaterSurface;
               }
            }
         }
      }
   }

   private void applyShorelineBlend(
      int[] terrainSurface,
      int[] baseSurface,
      int[] waterSurface,
      boolean[] waterMask,
      boolean[] landMask,
      boolean[] cliffLandMask,
      int gridSize,
      int blendDistance
   ) {
      if (blendDistance > 0) {
         int gridArea = gridSize * gridSize;
         WaterSurfaceResolver.RegionScratch scratch = REGION_SCRATCH.get();
         int[] landDistanceCost = scratch.landDistanceCost;
         int[] nearestSurface = scratch.nearestSurface;
         boolean[] landSource = scratch.landSource;
         boolean[] blendLandMask = scratch.blendLandMask;
         Arrays.fill(landSource, 0, gridArea, false);

         for (int index = 0; index < gridArea; index++) {
            blendLandMask[index] = landMask[index] && (!this.limitShorelineBlendBySlope || !cliffLandMask[index]);
         }

         IntArrayList shoreLand = scratch.shoreLand;
         shoreLand.clear();

         for (int index = 0; index < gridArea; index++) {
            if (waterMask[index]) {
               int x = index % gridSize;
               int z = index / gridSize;
               int sourceSurface = waterSurface[index];

               for (int n = 0; n < NEIGHBOR_OFFSETS.length; n += 2) {
                  int nx = x + NEIGHBOR_OFFSETS[n];
                  int nz = z + NEIGHBOR_OFFSETS[n + 1];
                  if (nx >= 0 && nz >= 0 && nx < gridSize && nz < gridSize) {
                     int neighbor = nz * gridSize + nx;
                     if (blendLandMask[neighbor] && !landSource[neighbor]) {
                        landSource[neighbor] = true;
                        nearestSurface[neighbor] = sourceSurface;
                        shoreLand.add(neighbor);
                     }
                  }
               }
            }
         }

         if (!shoreLand.isEmpty()) {
            this.computeWeightedDistanceWithSurface(
               landDistanceCost, nearestSurface, blendLandMask, shoreLand, gridSize, blendDistance, DIST_COST_CARDINAL
            );
            int maxBlendCost = blendDistance * DIST_COST_CARDINAL;

            for (int indexx = 0; indexx < gridArea; indexx++) {
               if (blendLandMask[indexx]) {
                  int distanceCost = landDistanceCost[indexx];
                  if (distanceCost != Integer.MAX_VALUE && distanceCost <= maxBlendCost) {
                     double distance = distanceCost / (double)DIST_COST_CARDINAL;
                     double t = distance / blendDistance;
                     int sourceSurface = nearestSurface[indexx];
                     int base = baseSurface[indexx];
                     int blended = (int)Math.round(Mth.lerp(t, sourceSurface, base));
                     if (blended < base) {
                        terrainSurface[indexx] = blended;
                     }
                  }
               }
            }
         }
      }
   }

   private boolean shouldTreatRiverAsLake(WaterSurfaceResolver.ComponentData component, int width, int height, int minDim, double aspect) {
      if (minDim <= 0) {
         return false;
      } else if (aspect >= RIVER_ASPECT_RATIO * RIVER_LAKE_ASPECT_FACTOR) {
         return false;
      } else {
         int minWidth = Math.max(RIVER_LAKE_MIN_WIDTH, (int)Math.round(this.riverMaxWidth * RIVER_LAKE_WIDTH_FACTOR));
         if (minDim < minWidth) {
            return false;
         } else {
            int area = width * height;
            if (area <= 0) {
               return false;
            } else {
               double fillRatio = (double)component.cellCount / area;
               return fillRatio >= RIVER_LAKE_FILL_THRESHOLD;
            }
         }
      }
   }

   private void smoothLakeBeds(
      int[] terrainSurface,
      int[] waterSurface,
      boolean[] inlandWaterMask,
      boolean[] cliffWaterMask,
      int[] componentIds,
      WaterSurfaceResolver.ComponentData[] components,
      int componentCount,
      int[] waterDistanceCost,
      int gridSize
   ) {
      int minSmoothCost = 100;
      WaterSurfaceResolver.RegionScratch scratch = REGION_SCRATCH.get();
      scratch.ensureCapacity(terrainSurface.length);
      int[] smoothed = scratch.smoothedTerrain;

      for (int pass = 0; pass < LAKE_SMOOTH_PASSES; pass++) {
         System.arraycopy(terrainSurface, 0, smoothed, 0, terrainSurface.length);

         for (int i = 0; i < componentCount; i++) {
            WaterSurfaceResolver.ComponentData component = components[i];
            if (!component.isOcean && component.maxDistanceCost > minSmoothCost + 10) {
               for (int c = 0; c < component.cells.size(); c++) {
                  int cell = component.cells.getInt(c);
                  if (!cliffWaterMask[cell]) {
                     int distanceCost = waterDistanceCost[cell];
                     if (distanceCost != Integer.MAX_VALUE && distanceCost > minSmoothCost) {
                        int x = cell % gridSize;
                        int z = cell / gridSize;
                        int sum = terrainSurface[cell];
                        int count = 1;

                        for (int n = 0; n < NEIGHBOR_OFFSETS_8.length; n += 2) {
                           int nx = x + NEIGHBOR_OFFSETS_8[n];
                           int nz = z + NEIGHBOR_OFFSETS_8[n + 1];
                           if (nx >= 0 && nz >= 0 && nx < gridSize && nz < gridSize) {
                              int neighbor = nz * gridSize + nx;
                              if (inlandWaterMask[neighbor] && componentIds[neighbor] == component.id) {
                                 sum += terrainSurface[neighbor];
                                 count++;
                              }
                           }
                        }

                        int avg = (int)Math.round((double)sum / count);
                        int maxFloor = waterSurface[cell] - 1;
                        if (avg > maxFloor) {
                           avg = maxFloor;
                        }

                        smoothed[cell] = avg;
                     }
                  }
               }
            }
         }

         System.arraycopy(smoothed, 0, terrainSurface, 0, terrainSurface.length);
      }
   }

   private static void clearComponents(WaterSurfaceResolver.ComponentData[] components, int count) {
      for (int i = 0; i < count; i++) {
         components[i] = null;
      }
   }

   private boolean isShoreCell(int x, int z, int gridSize, boolean[] waterMask) {
      for (int i = 0; i < NEIGHBOR_OFFSETS.length; i += 2) {
         int nx = x + NEIGHBOR_OFFSETS[i];
         int nz = z + NEIGHBOR_OFFSETS[i + 1];
         if (nx < 0 || nz < 0 || nx >= gridSize || nz >= gridSize) {
            return true;
         }

         int neighbor = nz * gridSize + nx;
         if (!waterMask[neighbor]) {
            return true;
         }
      }

      return false;
   }

   private void fillComponentSurface(WaterSurfaceResolver.ComponentData component, int[] waterSurface, int surface) {
      for (int i = 0; i < component.cells.size(); i++) {
         waterSurface[component.cells.getInt(i)] = surface;
      }
   }

   private void computeWeightedDistance(int[] distances, boolean[] allowed, IntArrayList sources, int gridSize, int maxDistanceBlocks, int initialCost) {
      this.computeWeightedDistanceInternal(distances, null, allowed, sources, gridSize, maxDistanceBlocks, initialCost);
   }

   private void computeWeightedDistanceWithSurface(
      int[] distances, int[] nearestSurface, boolean[] allowed, IntArrayList sources, int gridSize, int maxDistanceBlocks, int initialCost
   ) {
      this.computeWeightedDistanceInternal(distances, nearestSurface, allowed, sources, gridSize, maxDistanceBlocks, initialCost);
   }

   private void computeWeightedDistanceInternal(
      int[] distances, int[] nearestSurface, boolean[] allowed, IntArrayList sources, int gridSize, int maxDistanceBlocks, int initialCost
   ) {
      int gridArea = gridSize * gridSize;
      Arrays.fill(distances, 0, gridArea, Integer.MAX_VALUE);
      if (!sources.isEmpty()) {
         int maxCost = Math.max(0, maxDistanceBlocks) * DIST_COST_CARDINAL;
         WaterSurfaceResolver.RegionScratch scratch = REGION_SCRATCH.get();
         scratch.ensureBucketCapacity(maxCost + 1);
         IntArrayList[] buckets = scratch.buckets;
         boolean[] bucketUsed = scratch.bucketUsed;
         IntArrayList usedBuckets = scratch.usedBuckets;
         usedBuckets.clear();
         int minCost = Integer.MAX_VALUE;

         for (int i = 0; i < sources.size(); i++) {
            int index = sources.getInt(i);
            if (allowed[index] && initialCost <= maxCost && initialCost < distances[index]) {
               distances[index] = initialCost;
               addBucket(buckets, bucketUsed, usedBuckets, initialCost, index);
               if (initialCost < minCost) {
                  minCost = initialCost;
               }
            }
         }

         if (minCost == Integer.MAX_VALUE) {
            clearBuckets(buckets, bucketUsed, usedBuckets);
         } else {
            for (int cost = minCost; cost <= maxCost; cost++) {
               IntArrayList bucket = buckets[cost];
               if (bucket != null && !bucket.isEmpty()) {
                  for (int bucketIndex = 0; bucketIndex < bucket.size(); bucketIndex++) {
                     int index = bucket.getInt(bucketIndex);
                     if (cost == distances[index] && cost < maxCost) {
                        int x = index % gridSize;
                        int z = index / gridSize;
                        int sourceSurface = nearestSurface != null ? nearestSurface[index] : 0;

                        for (int ix = 0; ix < NEIGHBOR_OFFSETS_8.length; ix += 2) {
                           int nx = x + NEIGHBOR_OFFSETS_8[ix];
                           int nz = z + NEIGHBOR_OFFSETS_8[ix + 1];
                           if (nx >= 0 && nz >= 0 && nx < gridSize && nz < gridSize) {
                              int neighbor = nz * gridSize + nx;
                              if (allowed[neighbor]) {
                                 int nextCost = cost + NEIGHBOR_COSTS_8[ix / 2];
                                 if (nextCost < distances[neighbor] && nextCost <= maxCost) {
                                    distances[neighbor] = nextCost;
                                    if (nearestSurface != null) {
                                       nearestSurface[neighbor] = sourceSurface;
                                    }

                                    addBucket(buckets, bucketUsed, usedBuckets, nextCost, neighbor);
                                 }
                              }
                           }
                        }
                     }
                  }

                  bucket.clear();
               }
            }

            clearBuckets(buckets, bucketUsed, usedBuckets);
         }
      }
   }

   private static void addBucket(IntArrayList[] buckets, boolean[] bucketUsed, IntArrayList usedBuckets, int cost, int index) {
      IntArrayList bucket = buckets[cost];
      if (bucket == null) {
         bucket = new IntArrayList();
         buckets[cost] = bucket;
      }

      if (!bucketUsed[cost]) {
         bucket.clear();
         bucketUsed[cost] = true;
         usedBuckets.add(cost);
      }

      bucket.add(index);
   }

   private static void clearBuckets(IntArrayList[] buckets, boolean[] bucketUsed, IntArrayList usedBuckets) {
      for (int i = 0; i < usedBuckets.size(); i++) {
         int cost = usedBuckets.getInt(i);
         IntArrayList bucket = buckets[cost];
         if (bucket != null) {
            bucket.clear();
         }

         bucketUsed[cost] = false;
      }

      usedBuckets.clear();
   }

   private int sampleSurfaceHeight(double blockX, double blockZ, int coverClass, TellusLandMaskSource.LandMaskSample landMaskSample) {
      return this.sampleSurfaceHeight(blockX, blockZ, coverClass, landMaskSample, this.settings.worldScale());
   }

   private int sampleSurfaceHeight(
      double blockX, double blockZ, int coverClass, TellusLandMaskSource.LandMaskSample landMaskSample, double previewResolutionMeters
   ) {
      boolean oceanZoom = this.useOceanZoom(landMaskSample, coverClass);
      return this.sampleSurfaceHeight(blockX, blockZ, oceanZoom, previewResolutionMeters);
   }

   private int sampleSurfaceHeight(double blockX, double blockZ, boolean oceanZoom, double previewResolutionMeters) {
      double elevation = this.elevationSource.samplePreviewElevationMeters(
         blockX, blockZ, this.settings.worldScale(), oceanZoom, this.settings.demSelection(), previewResolutionMeters
      );
      double heightScale = elevation >= 0.0 ? this.settings.terrestrialHeightScale() : this.settings.oceanicHeightScale();
      double scaled = elevation * heightScale / this.settings.worldScale();
      int offset = this.settings.heightOffset();
      int height = elevation >= 0.0 ? Mth.ceil(scaled) : Mth.floor(scaled);
      return height + offset;
   }

   private boolean useOceanZoom(TellusLandMaskSource.LandMaskSample landSample, int coverClass) {
      if (!landSample.known()) {
         return true;
      } else if (landSample.land()) {
         return false;
      } else {
         return coverClass == ESA_NO_DATA || coverClass == ESA_WATER;
      }
   }

   private int metersToBlocks(double meters) {
      double scale = Math.max(1.0E-4, this.settings.worldScale());
      int blocks = (int)Math.round(meters / scale);
      return Math.max(1, blocks);
   }

   private static int clampBlend(int blocks) {
      return Mth.clamp(blocks, 0, 10);
   }

   private static int percentile(IntArrayList values, double percentile) {
      int size = values.size();
      if (size == 0) {
         return 0;
      } else {
         int index = (int)Math.floor(percentile * (size - 1));
         index = Mth.clamp(index, 0, size - 1);
         return selectNth(values.elements(), size, index);
      }
   }

   private static int selectNth(int[] data, int length, int index) {
      int left = 0;
      int right = length - 1;

      while (left < right) {
         int pivotIndex = left + (right - left >>> 1);
         pivotIndex = partition(data, left, right, pivotIndex);
         if (index == pivotIndex) {
            return data[index];
         }

         if (index < pivotIndex) {
            right = pivotIndex - 1;
         } else {
            left = pivotIndex + 1;
         }
      }

      return data[left];
   }

   private static int partition(int[] data, int left, int right, int pivotIndex) {
      int pivotValue = data[pivotIndex];
      swap(data, pivotIndex, right);
      int storeIndex = left;

      for (int i = left; i < right; i++) {
         if (data[i] < pivotValue) {
            swap(data, storeIndex, i);
            storeIndex++;
         }
      }

      swap(data, right, storeIndex);
      return storeIndex;
   }

   private static void swap(int[] data, int left, int right) {
      if (left != right) {
         int temp = data[left];
         data[left] = data[right];
         data[right] = temp;
      }
   }

   private static int regionCoord(int blockCoord) {
      return Math.floorDiv(blockCoord, REGION_SIZE);
   }

   private static int chunkIndex(int localX, int localZ) {
      return localZ * CHUNK_SIZE + localX;
   }

   private static long pack(int x, int z) {
      return (long)x << 32 ^ z & 4294967295L;
   }

   private static int intProperty(String key, int fallback, int min, int max) {
      String value = System.getProperty(key);
      if (value == null || value.isBlank()) {
         return fallback;
      } else {
         try {
            return Mth.clamp(Integer.parseInt(value.trim()), min, max);
         } catch (NumberFormatException ignored) {
            return fallback;
         }
      }
   }

   private static long seedFromCoords(int x, int y, int z) {
      long seed = x * 3129871 ^ z * 116129781L ^ y;
      seed = seed * seed * 42317861L + seed * 11L;
      return seed >> 16;
   }

   private static final class ComponentData {
      private final int id;
      private final IntArrayList cells;
      private final IntArrayList borderHeights;
      private int minX = Integer.MAX_VALUE;
      private int maxX = Integer.MIN_VALUE;
      private int minZ = Integer.MAX_VALUE;
      private int maxZ = Integer.MIN_VALUE;
      private int minHeight = Integer.MAX_VALUE;
      private int maxHeight = Integer.MIN_VALUE;
      private int minHeightIndex = -1;
      private int maxHeightIndex = -1;
      private long heightSum;
      private int cellCount;
      private int landMaskLandCount;
      private int belowSeaCellCount;
      private boolean touchesNoData;
      private boolean touchesEdge;
      private boolean oceanHinted;
      private boolean isOcean;
      private int maxDistanceCost;

      private ComponentData(int id, IntArrayList cells, IntArrayList borderHeights) {
         this.id = id;
         this.cells = cells;
         this.borderHeights = borderHeights;
      }

      private int averageHeight() {
         if (this.cellCount <= 0) {
            return this.minHeight == Integer.MAX_VALUE ? 0 : this.minHeight;
         } else {
            return (int)Math.round((double)this.heightSum / this.cellCount);
         }
      }
   }

   private static final class RegionLookup {
      private final int[] regionXs = new int[REGION_LOOKUP_CAPACITY];
      private final int[] regionZs = new int[REGION_LOOKUP_CAPACITY];
      private final WaterSurfaceResolver.WaterRegionData[] regions = new WaterSurfaceResolver.WaterRegionData[REGION_LOOKUP_CAPACITY];
      private int size;

      private WaterSurfaceResolver.WaterRegionData find(int regionX, int regionZ) {
         for (int i = 0; i < this.size; i++) {
            WaterSurfaceResolver.WaterRegionData region = this.regions[i];
            if (region != null && this.regionXs[i] == regionX && this.regionZs[i] == regionZ) {
               if (i > 0) {
                  this.moveToFront(i);
               }

               return this.regions[0];
            }
         }

         return null;
      }

      private void put(int regionX, int regionZ, WaterSurfaceResolver.WaterRegionData region) {
         for (int i = 0; i < this.size; i++) {
            if (this.regions[i] != null && this.regionXs[i] == regionX && this.regionZs[i] == regionZ) {
               this.regions[i] = region;
               if (i > 0) {
                  this.moveToFront(i);
               }

               return;
            }
         }

         int insertIndex = Math.min(this.size, REGION_LOOKUP_CAPACITY - 1);
         if (this.size < REGION_LOOKUP_CAPACITY) {
            this.size++;
         }

         for (int i = insertIndex; i > 0; i--) {
            this.regionXs[i] = this.regionXs[i - 1];
            this.regionZs[i] = this.regionZs[i - 1];
            this.regions[i] = this.regions[i - 1];
         }

         this.regionXs[0] = regionX;
         this.regionZs[0] = regionZ;
         this.regions[0] = region;
      }

      private void moveToFront(int index) {
         int regionX = this.regionXs[index];
         int regionZ = this.regionZs[index];
         WaterSurfaceResolver.WaterRegionData region = this.regions[index];

         for (int i = index; i > 0; i--) {
            this.regionXs[i] = this.regionXs[i - 1];
            this.regionZs[i] = this.regionZs[i - 1];
            this.regions[i] = this.regions[i - 1];
         }

         this.regionXs[0] = regionX;
         this.regionZs[0] = regionZ;
         this.regions[0] = region;
      }
   }

   private static final class RegionScratch {
      private int capacity;
      private boolean[] baseWaterMask;
      private boolean[] noDataMask;
      private boolean[] oceanHintMask;
      private boolean[] landMaskLand;
      private int[] surfaceHeights;
      private int[] componentIds;
      private WaterSurfaceResolver.ComponentData[] components;
      private int[] waterSurface;
      private int[] terrainSurface;
      private int[] smoothedTerrain;
      private byte[] waterFlags;
      private boolean[] inlandWaterMask;
      private boolean[] oceanComponentMask;
      private boolean[] waterMask;
      private boolean[] landMask;
      private boolean[] cliffLandMask;
      private boolean[] cliffWaterMask;
      private boolean[] blendLandMask;
      private boolean[] cascadeMask;
      private int[] waterDistanceCost;
      private int[] landDistanceCost;
      private int[] nearestSurface;
      private boolean[] landSource;
      private final IntArrayList shoreWater = new IntArrayList();
      private final IntArrayList shoreLand = new IntArrayList();
      private final IntArrayList cascadeQueue = new IntArrayList();
      private int coarseCapacity;
      private boolean[] coarseWater;
      private boolean[] coarseInlandSeed;
      private boolean[] coarseInlandConnected;
      private final IntArrayList coarseQueue = new IntArrayList();
      private IntArrayList[] buckets;
      private boolean[] bucketUsed;
      private final IntArrayList usedBuckets = new IntArrayList();
      private int bucketCapacity;

      private void ensureCapacity(int size) {
         if (size > this.capacity) {
            this.capacity = size;
            this.baseWaterMask = new boolean[size];
            this.noDataMask = new boolean[size];
            this.oceanHintMask = new boolean[size];
            this.landMaskLand = new boolean[size];
            this.surfaceHeights = new int[size];
            this.componentIds = new int[size];
            this.components = new WaterSurfaceResolver.ComponentData[size];
            this.waterSurface = new int[size];
            this.terrainSurface = new int[size];
            this.smoothedTerrain = new int[size];
            this.waterFlags = new byte[size];
            this.inlandWaterMask = new boolean[size];
            this.oceanComponentMask = new boolean[size];
            this.waterMask = new boolean[size];
            this.landMask = new boolean[size];
            this.cliffLandMask = new boolean[size];
            this.cliffWaterMask = new boolean[size];
            this.blendLandMask = new boolean[size];
            this.cascadeMask = new boolean[size];
            this.waterDistanceCost = new int[size];
            this.landDistanceCost = new int[size];
            this.nearestSurface = new int[size];
            this.landSource = new boolean[size];
         }
      }

      private void ensureCoarseCapacity(int size) {
         if (size > this.coarseCapacity) {
            this.coarseCapacity = size;
            this.coarseWater = new boolean[size];
            this.coarseInlandSeed = new boolean[size];
            this.coarseInlandConnected = new boolean[size];
         }
      }

      private void ensureBucketCapacity(int size) {
         if (size > this.bucketCapacity) {
            this.bucketCapacity = size;
            this.buckets = new IntArrayList[size];
            this.bucketUsed = new boolean[size];
         }
      }

      private void resetLists() {
         this.shoreWater.clear();
         this.shoreLand.clear();
         this.cascadeQueue.clear();
      }
   }

   private static final class RiverSurface {
      private final int originX;
      private final int originZ;
      private final double ux;
      private final double uz;
      private final double minProj;
      private final double length;
      private final int lowSurface;
      private final int highSurface;

      private RiverSurface(int originX, int originZ, double ux, double uz, double minProj, double length, int lowSurface, int highSurface) {
         this.originX = originX;
         this.originZ = originZ;
         this.ux = ux;
         this.uz = uz;
         this.minProj = minProj;
         this.length = length;
         this.lowSurface = lowSurface;
         this.highSurface = highSurface;
      }

      private int surfaceAt(int x, int z) {
         double proj = (x - this.originX) * this.ux + (z - this.originZ) * this.uz;
         double t = (proj - this.minProj) / Math.max(1.0, this.length);
         t = Mth.clamp(t, 0.0, 1.0);
         double surface = this.lowSurface + t * (this.highSurface - this.lowSurface);
         int height = (int)Math.round(surface);
         return Mth.clamp(height, this.lowSurface, this.highSurface);
      }
   }

   public static final class WaterChunkData {
      private final int[] terrainSurface;
      private final int[] waterSurface;
      private final byte[] waterFlags;
      private final boolean approximate;

      private WaterChunkData(int[] terrainSurface, int[] waterSurface, byte[] waterFlags, boolean approximate) {
         this.terrainSurface = terrainSurface;
         this.waterSurface = waterSurface;
         this.waterFlags = waterFlags;
         this.approximate = approximate;
      }

      private WaterChunkData(int chunkX, int chunkZ, WaterSurfaceResolver.WaterRegionData region) {
         int minX = chunkX << CHUNK_SHIFT;
         int minZ = chunkZ << CHUNK_SHIFT;
         this.terrainSurface = new int[CHUNK_AREA];
         this.waterSurface = new int[CHUNK_AREA];
         this.waterFlags = new byte[CHUNK_AREA];
         this.approximate = false;

         for (int dz = 0; dz < CHUNK_SIZE; dz++) {
            int worldZ = minZ + dz;

            for (int dx = 0; dx < CHUNK_SIZE; dx++) {
               int worldX = minX + dx;
               int index = WaterSurfaceResolver.chunkIndex(dx, dz);
               this.terrainSurface[index] = region.terrainSurface(worldX, worldZ);
               this.waterSurface[index] = region.waterSurface(worldX, worldZ);
               this.waterFlags[index] = region.waterFlag(worldX, worldZ);
            }
         }
      }

      private static WaterSurfaceResolver.WaterChunkData dryFromTerrain(int[] terrainSurface) {
         if (terrainSurface.length != CHUNK_AREA) {
            throw new IllegalArgumentException("Expected " + CHUNK_AREA + " dry terrain samples, got " + terrainSurface.length);
         } else {
            int[] terrainCopy = Arrays.copyOf(terrainSurface, CHUNK_AREA);
            return new WaterSurfaceResolver.WaterChunkData(terrainCopy, terrainCopy.clone(), new byte[CHUNK_AREA], true);
         }
      }

      public static WaterSurfaceResolver.WaterChunkData fromArrays(int[] terrainSurface, int[] waterSurface, byte[] waterFlags, boolean approximate) {
         if (terrainSurface.length != CHUNK_AREA || waterSurface.length != CHUNK_AREA || waterFlags.length != CHUNK_AREA) {
            throw new IllegalArgumentException("Water chunk arrays must all have length " + CHUNK_AREA);
         } else {
            return new WaterSurfaceResolver.WaterChunkData(
               Arrays.copyOf(terrainSurface, CHUNK_AREA), Arrays.copyOf(waterSurface, CHUNK_AREA), Arrays.copyOf(waterFlags, CHUNK_AREA), approximate
            );
         }
      }

      public int terrainSurface(int localX, int localZ) {
         return this.terrainSurface[WaterSurfaceResolver.chunkIndex(localX, localZ)];
      }

      public int waterSurface(int localX, int localZ) {
         return this.waterSurface[WaterSurfaceResolver.chunkIndex(localX, localZ)];
      }

      public boolean hasWater(int localX, int localZ) {
         return this.waterFlags[WaterSurfaceResolver.chunkIndex(localX, localZ)] != WATER_NONE;
      }

      public boolean isOcean(int localX, int localZ) {
         return this.waterFlags[WaterSurfaceResolver.chunkIndex(localX, localZ)] == WATER_OCEAN;
      }

      public boolean approximate() {
         return this.approximate;
      }
   }

   public record WaterColumnData(boolean hasWater, boolean isOcean, int terrainSurface, int waterSurface) {
   }

   public record WaterInfo(boolean isWater, boolean isOcean, int surface, int terrainSurface) {
      static final WaterSurfaceResolver.WaterInfo LAND = new WaterSurfaceResolver.WaterInfo(false, false, Integer.MIN_VALUE, Integer.MIN_VALUE);
   }

   private static final class WaterRegionData {
      private final int minX;
      private final int minZ;
      private final int[] terrainSurface;
      private final int[] waterSurface;
      private final byte[] waterFlags;
      private final int[] rawSurface;

      private WaterRegionData(int minX, int minZ, int[] terrainSurface, int[] waterSurface, byte[] waterFlags, int[] rawSurface) {
         this.minX = minX;
         this.minZ = minZ;
         this.terrainSurface = terrainSurface;
         this.waterSurface = waterSurface;
         this.waterFlags = waterFlags;
         this.rawSurface = rawSurface;
      }

      private WaterSurfaceResolver.WaterColumnData columnData(int blockX, int blockZ) {
         int index = this.index(blockX, blockZ);
         byte flag = this.waterFlags[index];
         return new WaterSurfaceResolver.WaterColumnData(flag != WATER_NONE, flag == WATER_OCEAN, this.terrainSurface[index], this.waterSurface[index]);
      }

      private int terrainSurface(int blockX, int blockZ) {
         return this.terrainSurface[this.index(blockX, blockZ)];
      }

      private int waterSurface(int blockX, int blockZ) {
         return this.waterSurface[this.index(blockX, blockZ)];
      }

      private int rawSurface(int blockX, int blockZ) {
         return this.rawSurface[this.index(blockX, blockZ)];
      }

      private byte waterFlag(int blockX, int blockZ) {
         return this.waterFlags[this.index(blockX, blockZ)];
      }

      private int index(int blockX, int blockZ) {
         int localX = blockX - this.minX;
         int localZ = blockZ - this.minZ;
         return localZ * REGION_SIZE + localX;
      }
   }
}
