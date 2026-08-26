package com.yucareux.tellus.worldgen;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheHandle;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.preload.TerrainPreloadPackage;
import com.yucareux.tellus.preload.TerrainPreloadPackageRegistry;
import com.yucareux.tellus.world.data.cover.TellusLandCoverSource;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource;
import com.yucareux.tellus.world.data.mask.TellusLandMaskSource;
import com.yucareux.tellus.world.data.osm.OsmPerf;
import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.osm.TellusOsmWaterSource;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.util.Mth;

public final class WaterSurfaceResolver implements TellusCacheHandle {
   private static final int ESA_NO_DATA = 0;
   private static final int ESA_WATER = 80;
   private static final byte WATER_NONE = 0;
   private static final byte WATER_INLAND = 1;
   private static final byte WATER_OCEAN = 2;
   private static final byte WATER_WATERFALL_DROP = 3;
   private static final int REGION_SIZE = 64;
   private static final int MAX_REGION_CACHE = intProperty("tellus.waterRegionCacheSize", 1024, 64, 8192);
   private static final int MAX_NEAR_WATER_CACHE = intProperty("tellus.waterNearChunkCacheSize", 8192, 256, 65536);
   private static final int REGION_LOOKUP_CAPACITY = intProperty("tellus.waterRegionLookupCapacity", 4, 1, 16);
   private static final int CHUNK_SHIFT = 4;
   private static final int CHUNK_SIZE = 16;
   private static final int CHUNK_AREA = 256;
   private static final int OCEAN_MIN_DEPTH = 1;
   private static final int OCEAN_FLOOR_SUPPORT_BLOCKS = intProperty("tellus.water.oceanFloorSupportBlocks", 8, 2, 32);
   private static final double EXPECTED_MAX_OCEAN_DEPTH_METERS = 11034.0;
   private static final double FALLBACK_OCEAN_MIN_DEPTH_METERS = 600.0;
   private static final double FALLBACK_OCEAN_MAX_DEPTH_METERS = 3200.0;
   private static final int SHORELINE_WALL_CLAMP_HEIGHT = 1;
   private static final int RIVER_MAX_TERRAIN_CUT = intProperty("tellus.water.riverMaxTerrainCut", 6, 0, 32);
   private static final int RIVER_FLOW_PROFILE_MAX_CUT = intProperty("tellus.water.riverFlowProfileMaxCut", 4, 0, 16);
   private static final int RIVER_HYDRO_FLATTEN_MAX_CUT = intProperty("tellus.water.riverHydroFlattenMaxCut", 12, 1, 48);
   private static final int RIVER_CONNECT_GAP_BLOCKS = intProperty("tellus.water.riverConnectGapBlocks", 4, 0, 16);
   private static final double RIVER_LINE_COMPONENT_RATIO = 0.5;
   private static final double RIVER_LINE_ASPECT_RATIO = 2.0;
   private static final int CLIFF_SLOPE_THRESHOLD = 5;
   private static final int INLAND_OCEAN_TRANSITION_BLOCKS = intProperty("tellus.water.inlandOceanTransitionBlocks", 48, 0, 512);
   private static final int OCEAN_FLOOR_TRANSITION_BLOCKS = intProperty(
      "tellus.water.oceanFloorTransitionBlocks",
      OceanFloorProfile.DEFAULT_TRANSITION_BLOCKS,
      0,
      OceanFloorProfile.MAX_TRANSITION_BLOCKS
   );
   private static final int INLAND_SHORE_BANK_RAMP_MAX_SLOPE = 2;
   private static final int INLAND_BANK_MAX_RISE_PER_BLOCK = 1;
   private static final int RIVER_MAX_DEPTH = intProperty("tellus.water.riverMaxDepth", 6, 1, 16);
   private static final int RIVER_DEPTH_DISTANCE_STEP = intProperty("tellus.water.riverDepthDistanceStep", 4, 1, 16);
   private static final int FLOW_CONTEXT_BLOCKS = intProperty("tellus.water.flowContextBlocks", 192, 64, 512);
   private static final int FLOW_HYDRO_FLATTEN_LOOKAHEAD_BLOCKS = intProperty(
      "tellus.water.hydroFlattenLookAheadBlocks", 48, 8, 192
   );
   private static final int FLOW_WATERFALL_LOOKAHEAD_BLOCKS = intProperty("tellus.water.waterfallLookAheadBlocks", 12, 2, 64);
   private static final int FLOW_WATERFALL_MIN_DROP_OVERRIDE = intProperty("tellus.water.waterfallMinDropBlocks", 0, 0, 64);
   private static final double FLOW_WATERFALL_MIN_DROP_METERS = 12.0;
   private static final int MAX_ADAPTIVE_BLEND_BLOCKS = intProperty("tellus.water.maxAdaptiveBlendBlocks", 48, 4, 128);
   private static final int WATERFALL_PROTECTION_BLOCKS = intProperty("tellus.water.waterfallProtectionBlocks", 4, 1, 16);
   private static final double RIVER_MIN_LENGTH_METERS = 750.0;
   private static final double RIVER_MAX_WIDTH_METERS = 400.0;
   private static final double RIVER_ASPECT_RATIO = 3.0;
   private static final double RIVER_LAKE_FILL_THRESHOLD = 0.6;
   private static final double RIVER_LAKE_ASPECT_FACTOR = 1.5;
   private static final double RIVER_LAKE_WIDTH_FACTOR = 0.75;
   private static final int RIVER_LAKE_MIN_WIDTH = 12;
   private static final double BORDER_HEIGHT_PERCENTILE = 0.1;
   private static final double LAKE_SURFACE_HINT_PERCENTILE = 0.25;
   private static final int LAKE_MAX_TERRAIN_CUT = intProperty("tellus.water.lakeMaxTerrainCut", 12, 1, 64);
   private static final int MAX_LAKE_SURFACE_CACHE = intProperty("tellus.waterLakeSurfaceCacheSize", 8192, 256, 65536);
   private static final int MAX_FEATURE_SURFACE_SAMPLES = intProperty("tellus.water.featureSurfaceSamples", 128, 8, 2048);
   private static final int ESA_LAKE_KEY_GRID_BLOCKS = intProperty("tellus.water.esaLakeKeyGridBlocks", 512, 64, 8192);
   private static final int ESA_LAKE_HEIGHT_BUCKET = intProperty("tellus.water.esaLakeHeightBucket", 4, 1, 64);
   private static final long ESA_LAKE_BODY_KEY_SALT = -2210690988109018255L;
   private static final int SEA_LEVEL_TOLERANCE = 2;
   private static final double BELOW_SEA_CELL_RATIO = 0.9;
   private static final double LANDMASK_INLAND_RATIO = 0.6;
   private static final int COARSE_CONNECT_STEP = 8;
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
   private final WorldProjection projection;
   private final TerrainPreloadPackageRegistry.SettingsView preloadedTerrain;
   private final boolean osmWaterEnabled;
   private final int seaLevel;
   private final Cache<Long, WaterSurfaceResolver.WaterRegionData> regionCache;
   private final Cache<Long, Boolean> nearWaterChunkCache;
   private final ThreadLocal<WaterSurfaceResolver.RegionLookup> regionLookup = ThreadLocal.withInitial(WaterSurfaceResolver.RegionLookup::new);
   private final AtomicLong cacheGeneration = new AtomicLong();
   private final long regionSalt;
   private final int riverLakeBlendDistance;
   private final int cliffSlopeThreshold;
   private final int riverMinLength;
   private final int riverMaxWidth;
   private final int maxDistanceToShore;
   private final int regionMargin;
   private final boolean regionClamped;
   private final Cache<Long, Integer> lakeSurfaceCache;
   private final OceanCoastField oceanCoastField;
   private final int oceanFloorTransitionBlocks;
   private final int minimumOffshoreDepth;
   private final int minimumOceanFloor;
   private final int expectedMaximumOceanDepth;
   private final InlandWaterFlowAnalyzer.Parameters flowParameters;

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
      this.projection = settings.projection();
      this.preloadedTerrain = TerrainPreloadPackageRegistry.instance().viewFor(settings);
      this.osmWaterEnabled = settings.enableWater();
      this.seaLevel = settings.effectiveHeightOffset();
      this.riverLakeBlendDistance = clampBlend(settings.riverLakeShorelineBlend());
      double scale = Math.max(1.0, settings.worldScale());
      this.cliffSlopeThreshold = Math.max(2, (int)Math.round(CLIFF_SLOPE_THRESHOLD / Math.sqrt(scale)));
      this.riverMinLength = this.metersToBlocks(RIVER_MIN_LENGTH_METERS);
      this.riverMaxWidth = this.metersToBlocks(RIVER_MAX_WIDTH_METERS);
      this.maxDistanceToShore = Math.max(
         LakeBedProfile.maximumShoreInfluenceBlocks(),
         Math.max(this.riverLakeBlendDistance, MAX_ADAPTIVE_BLEND_BLOCKS)
      );
      int rawRegionMargin = Math.max(
         this.maxDistanceToShore + SEA_LEVEL_TOLERANCE,
         this.osmWaterEnabled
            ? Math.max(FLOW_CONTEXT_BLOCKS, WaterfallNoCarveZone.queryMarginBlocks(settings.worldScale()))
            : 0
      );
      this.regionMargin = Math.min(rawRegionMargin, MAX_REGION_MARGIN_BLOCKS);
      this.regionClamped = rawRegionMargin > this.regionMargin;
      this.regionCache = CacheBuilder.newBuilder().maximumSize(MAX_REGION_CACHE).build();
      this.nearWaterChunkCache = CacheBuilder.newBuilder().maximumSize(MAX_NEAR_WATER_CACHE).build();
      this.lakeSurfaceCache = CacheBuilder.newBuilder().maximumSize(MAX_LAKE_SURFACE_CACHE).build();
      EarthGeneratorSettings.HeightLimits heightLimits = EarthGeneratorSettings.resolveHeightLimits(settings);
      this.minimumOceanFloor = Math.min(this.seaLevel - OCEAN_MIN_DEPTH, heightLimits.minY() + OCEAN_FLOOR_SUPPORT_BLOCKS);
      this.expectedMaximumOceanDepth = Math.max(
         OCEAN_MIN_DEPTH,
         (int)Math.round(
            EXPECTED_MAX_OCEAN_DEPTH_METERS
               * settings.effectiveOceanicHeightScale()
               / Math.max(1.0E-4, settings.effectiveVerticalWorldScale())
         )
      );
      this.oceanFloorTransitionBlocks = OceanFloorProfile.transitionBlocksForScale(
         OCEAN_FLOOR_TRANSITION_BLOCKS,
         settings.worldScale()
      );
      this.minimumOffshoreDepth = OceanFloorProfile.minimumOffshoreDepthForScale(settings.worldScale());
      this.oceanCoastField = new OceanCoastField(
         this.osmWaterSource,
         this.projection,
         this.oceanFloorTransitionBlocks,
         this::sampleRawOceanDepth
      );
      int scaledWaterfallDrop = Math.max(
         2,
         TerrainHeightTransform.blockOffsetAtLatitude(
            FLOW_WATERFALL_MIN_DROP_METERS,
            settings.spawnLatitude(),
            settings.effectiveVerticalWorldScale(),
            settings.effectiveTerrestrialHeightScale(),
            settings.effectiveOceanicHeightScale(),
            settings.experimentalIncreaseHeight(),
            settings.automaticHeightScaling()
         )
      );
      int waterfallMinDrop = FLOW_WATERFALL_MIN_DROP_OVERRIDE > 0
         ? FLOW_WATERFALL_MIN_DROP_OVERRIDE
         : Math.min(64, scaledWaterfallDrop);
      this.flowParameters = new InlandWaterFlowAnalyzer.Parameters(
         Math.min(RIVER_FLOW_PROFILE_MAX_CUT, Math.max(0, RIVER_MAX_TERRAIN_CUT - 1)),
         Math.max(RIVER_FLOW_PROFILE_MAX_CUT, RIVER_HYDRO_FLATTEN_MAX_CUT),
         FLOW_HYDRO_FLATTEN_LOOKAHEAD_BLOCKS,
         waterfallMinDrop,
         FLOW_WATERFALL_LOOKAHEAD_BLOCKS,
         this.riverLakeBlendDistance,
         Math.max(this.riverLakeBlendDistance, MAX_ADAPTIVE_BLEND_BLOCKS),
         WATERFALL_PROTECTION_BLOCKS,
         false
      );
      this.regionSalt = Double.doubleToLongBits(settings.worldScale()) ^ -7046029254386353131L;
      TellusCacheRegistry.register(this);
   }

   @Override
   public TellusCacheDomain cacheDomain() {
      return TellusCacheDomain.OSM;
   }

   @Override
   public boolean matchesCacheDomain(TellusCacheDomain domain) {
      return domain == TellusCacheDomain.OSM
         || domain == TellusCacheDomain.LAND_COVER
         || domain == TellusCacheDomain.TERRAIN
         || domain == TellusCacheDomain.NORMALIZED_TERRAIN
         || domain == TellusCacheDomain.OPENWATERS;
   }

   @Override
   public void clearCache() {
      this.cacheGeneration.incrementAndGet();
      this.regionCache.invalidateAll();
      this.nearWaterChunkCache.invalidateAll();
      synchronized (this.lakeSurfaceCache) {
         this.lakeSurfaceCache.invalidateAll();
      }
      this.oceanCoastField.clear();
      this.regionCache.cleanUp();
      this.nearWaterChunkCache.cleanUp();
      synchronized (this.lakeSurfaceCache) {
         this.lakeSurfaceCache.cleanUp();
      }
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

   public WaterSurfaceResolver.WaterChunkData resolveChunkWaterDataExact(int chunkX, int chunkZ) {
      int regionX = regionCoord(chunkX << CHUNK_SHIFT);
      int regionZ = regionCoord(chunkZ << CHUNK_SHIFT);
      WaterSurfaceResolver.WaterRegionData region = this.resolveRegionData(regionX, regionZ);
      return new WaterSurfaceResolver.WaterChunkData(chunkX, chunkZ, region);
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
         WaterSurfaceResolver.WaterRegionData region = this.resolveRegionData(regionX, regionZ);
         OsmPerf.recordWaterChunkResolve(OsmPerf.elapsedSince(resolveStartNs), false, false, true);
         return new WaterSurfaceResolver.WaterChunkData(chunkX, chunkZ, region);
      } else {
         int padding = this.waterInfluencePadding();
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
      int padding = this.waterInfluencePadding();
      if (radius > 0 && (DEFER_DETAILED_WATER || this.hasWaterNearChunkCached(chunkX, chunkZ, padding))) {
         int blockX = chunkX << CHUNK_SHIFT;
         int blockZ = chunkZ << CHUNK_SHIFT;
         this.prefetchRegionsForBlock(blockX, blockZ, radius);
      }
   }

   public boolean hasWaterNearChunk(int chunkX, int chunkZ) {
      return this.hasWaterNearChunkCached(chunkX, chunkZ, this.waterInfluencePadding());
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
      int coverClass = this.sampleCoverClass(blockX, blockZ);
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
            TellusLandMaskSource.LandMaskSample landMaskSample = this.sampleLandMask(blockX, blockZ, previewResolutionMeters);
            int surface = this.sampleSurfaceHeight(blockX, blockZ, coverClass, landMaskSample, previewResolutionMeters);
            return new WaterSurfaceResolver.WaterColumnData(false, false, surface, surface);
         }
      } else {
         WaterSurfaceResolver.WaterRegionData cached = this.getRegionIfPresent(regionX, regionZ);
         if (cached != null) {
            return cached.columnData(blockX, blockZ);
         } else {
            if (!this.osmWaterEnabled && coverClass == ESA_NO_DATA) {
               TellusLandMaskSource.LandMaskSample landMaskSample = this.sampleLandMask(blockX, blockZ, previewResolutionMeters);
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

   public WaterSurfaceResolver.WaterfallInfo resolveWaterfallInfo(int blockX, int blockZ) {
      WaterSurfaceResolver.WaterRegionData region = this.resolveRegionData(regionCoord(blockX), regionCoord(blockZ));
      return region.waterfallInfo(blockX, blockZ);
   }

   /**
    * Prevents vanilla's infinite-source rule from turning a terrain-following
    * waterfall into a self-filling flood. The cached region mask deliberately
    * covers only mapped inland water inside an active Overture waterfall zone;
    * all other water keeps vanilla source conversion.
    */
   public boolean shouldSuppressWaterSourceConversion(int blockX, int blockZ) {
      if (!this.osmWaterEnabled || WaterfallNoCarveZone.radiusChunks(this.settings.worldScale()) <= 0) {
         return false;
      }
      WaterSurfaceResolver.WaterRegionData region = this.resolveRegionData(regionCoord(blockX), regionCoord(blockZ));
      return region.suppressesSourceConversion(blockX, blockZ);
   }

   static boolean isSourceConversionProtectedCell(
      boolean waterfallNoCarve, boolean lineWater, boolean areaWater, boolean inlandWater
   ) {
      return waterfallNoCarve && (lineWater || areaWater) && inlandWater;
   }

   static boolean shouldScheduleFlowSource(int waterSurface, WaterSurfaceResolver.WaterfallInfo drop) {
      return drop.waterfall()
         && waterSurface >= drop.upstreamSurface()
         && drop.upstreamSurface() > drop.terrainSurface();
   }

   public WaterSurfaceResolver.PreviewWaterGrid resolvePreviewWaterGrid(
      int gridSize,
      int cellSizeBlocks,
      int baseWorldX,
      int baseWorldZ,
      int[] rawTerrain,
      boolean[] inlandWater,
      boolean[] oceanWater,
      boolean[] lineWater,
      boolean[] areaWater,
      boolean[] flowingWater,
      boolean[] waterfallNoCarve,
      int[] oceanWaterSurface
   ) {
      PreviewWaterFlowResolver.Result result = PreviewWaterFlowResolver.resolve(
         gridSize,
         cellSizeBlocks,
         baseWorldX,
         baseWorldZ,
         rawTerrain,
         inlandWater,
         oceanWater,
         lineWater,
         areaWater,
         flowingWater,
         waterfallNoCarve,
         oceanWaterSurface,
         this.riverMinLength,
         this.riverMaxWidth,
         LAKE_MAX_TERRAIN_CUT,
         RIVER_CONNECT_GAP_BLOCKS,
         this.cliffSlopeThreshold,
         this.settings.shorelineBlendCliffLimit(),
         this.flowParameters
      );
      return new WaterSurfaceResolver.PreviewWaterGrid(
         result.terrainSurface(),
         result.waterSurface(),
         result.inlandWater(),
         result.waterfallDrop(),
         result.waterfallProtection()
      );
   }

   public WaterSurfaceResolver.WaterColumnData resolveFastColumnData(int blockX, int blockZ) {
      int coverClass = this.sampleCoverClass(blockX, blockZ);
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
            TellusOsmWaterSource.FastWaterSample sample = this.osmWaterSource.sampleWater(
               blockX, blockZ, this.projection, OsmQueryMode.BLOCKING
            );
            if (sample.coverageStatus() != TellusOsmWaterSource.CoverageStatus.COMPLETE) {
               throw new OceanCoverageUnavailableException(sample.coverageStatus(), blockX, blockZ);
            }
            TellusLandMaskSource.LandMaskSample landMaskSample = sample.hasWater()
               ? this.sampleLandMask(blockX, blockZ, previewResolutionMeters)
               : TellusLandMaskSource.LandMaskSample.unknown();
            WaterSurfaceResolver.SurfaceSample surfaceSample = this.sampleSurface(
               blockX, blockZ, coverClass, landMaskSample, previewResolutionMeters
            );
            int surface = surfaceSample.height();
            if (!sample.hasWater()) {
               return new WaterSurfaceResolver.WaterColumnData(false, false, surface, surface);
            } else {
               boolean isOcean = this.classifyWaterAsOcean(sample.ocean(), landMaskSample, surface, coverClass);
               int waterSurface = isOcean ? this.resolveOceanWaterSurface(blockX, blockZ) : Math.max(surface + 1, this.seaLevel);
               int terrainSurface = surface;
               if (isOcean) {
                  terrainSurface = this.sampleOceanTerrainHeight(blockX, blockZ, waterSurface, previewResolutionMeters);
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
      this.prefetchRegion(regionX, regionZ);

      for (int dz = -clampedRadius; dz <= clampedRadius; dz++) {
         for (int dx = -clampedRadius; dx <= clampedRadius; dx++) {
            if (dx != 0 || dz != 0) {
               this.prefetchRegion(regionX + dx, regionZ + dz);
            }
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
      long generation = this.cacheGeneration.get();
      long key = this.regionKey(regionX, regionZ, generation);
      if (this.regionCache.getIfPresent(key) == null) {
         try {
            WaterSurfaceResolver.WaterRegionData region = (WaterSurfaceResolver.WaterRegionData)this.regionCache.get(
               key, () -> this.buildRegionData(regionX, regionZ)
            );
            if (generation == this.cacheGeneration.get()) {
               this.regionLookup().put(regionX, regionZ, region);
            } else {
               this.regionCache.invalidate(key);
            }
         } catch (Exception error) {
            Tellus.LOGGER.debug("Failed to prefetch water region {}:{}", new Object[]{regionX, regionZ, error});
         }
      }
   }

   private WaterSurfaceResolver.WaterRegionData getRegionIfPresent(int regionX, int regionZ) {
      WaterSurfaceResolver.RegionLookup lookup = this.regionLookup();
      WaterSurfaceResolver.WaterRegionData region = lookup.find(regionX, regionZ);
      if (region != null) {
         return region;
      }

      long key = this.regionKey(regionX, regionZ, this.cacheGeneration.get());
      region = (WaterSurfaceResolver.WaterRegionData)this.regionCache.getIfPresent(key);
      if (region != null) {
         lookup.put(regionX, regionZ, region);
      }

      return region;
   }

   private WaterSurfaceResolver.WaterRegionData resolveRegionData(int regionX, int regionZ) {
      WaterSurfaceResolver.RegionLookup lookup = this.regionLookup();
      WaterSurfaceResolver.WaterRegionData region = lookup.find(regionX, regionZ);
      if (region != null) {
         return region;
      }

      while (true) {
         long generation = this.cacheGeneration.get();
         long key = this.regionKey(regionX, regionZ, generation);
         try {
            region = (WaterSurfaceResolver.WaterRegionData)this.regionCache.get(key, () -> this.buildRegionData(regionX, regionZ));
            if (generation == this.cacheGeneration.get()) {
               lookup.put(regionX, regionZ, region);
               return region;
            }

            this.regionCache.invalidate(key);
            lookup = this.regionLookup();
         } catch (Exception error) {
            OceanCoverageUnavailableException coverageError = findOceanCoverageError(error);
            if (coverageError != null) {
               throw coverageError;
            }
            Tellus.LOGGER.warn("Failed to build water region {}:{}", new Object[]{regionX, regionZ, error});
            throw new RuntimeException("Failed to build water region " + regionX + ":" + regionZ, error);
         }
      }
   }

   private WaterSurfaceResolver.RegionLookup regionLookup() {
      WaterSurfaceResolver.RegionLookup lookup = this.regionLookup.get();
      lookup.resetIfStale(this.cacheGeneration.get());
      return lookup;
   }

   private static OceanCoverageUnavailableException findOceanCoverageError(Throwable error) {
      Throwable current = error;
      while (current != null) {
         if (current instanceof OceanCoverageUnavailableException coverageError) {
            return coverageError;
         }
         current = current.getCause();
      }
      return null;
   }

   private boolean hasWaterNearChunkCached(int chunkX, int chunkZ, int padding) {
      int regionX = regionCoord(chunkX << CHUNK_SHIFT);
      int regionZ = regionCoord(chunkZ << CHUNK_SHIFT);
      long key = this.regionKey(regionX, regionZ, this.cacheGeneration.get()) ^ (long)padding * -7046029254386353131L;
      Boolean cached = (Boolean)this.nearWaterChunkCache.getIfPresent(key);
      if (cached != null) {
         return cached;
      }

      boolean hasWater = this.hasWaterNearRegion(regionX, regionZ, padding);
      this.nearWaterChunkCache.put(key, hasWater);
      return hasWater;
   }

   private boolean hasWaterNearRegion(int regionX, int regionZ, int padding) {
      int minX = regionX * REGION_SIZE - padding;
      int minZ = regionZ * REGION_SIZE - padding;
      int maxX = minX + REGION_SIZE - 1 + padding * 2;
      int maxZ = minZ + REGION_SIZE - 1 + padding * 2;
      double worldScale = this.settings.worldScale();
      if (this.osmWaterEnabled) {
         TellusOsmWaterSource.WaterQueryResult result = this.osmWaterSource.waterForAreaWithStatus(
            minX, minZ, maxX, maxZ, this.projection, 0, OsmQueryMode.BLOCKING
         );
         if (!result.complete()) {
            throw new OceanCoverageUnavailableException(result.coverageStatus(), minX, minZ);
         }
         return !result.features().isEmpty();
      }

      return this.hasLandCoverWaterCandidateInArea(minX, minZ, maxX, maxZ, worldScale);
   }

   private boolean hasLandCoverWaterCandidateInArea(int minX, int minZ, int maxX, int maxZ, double worldScale) {
      for (int z = minZ; z <= maxZ; z++) {
         for (int x = minX; x <= maxX; x++) {
            int coverClass = this.sampleCoverClass(x, z);
            if (coverClass == ESA_WATER) {
               return true;
            }

            if (coverClass == ESA_NO_DATA) {
               TellusLandMaskSource.LandMaskSample landMaskSample = this.sampleLandMask(x, z);
               int surface = this.sampleSurfaceHeight(x, z, coverClass, landMaskSample);
               if (landCoverMayContainWater(coverClass, landMaskSample, surface, this.seaLevel)) {
                  return true;
               }
            }
         }
      }

      return false;
   }

   static boolean landCoverMayContainWater(
      int coverClass, TellusLandMaskSource.LandMaskSample landMaskSample, int surface, int seaLevel
   ) {
      if (coverClass == ESA_WATER) {
         return true;
      } else if (coverClass != ESA_NO_DATA) {
         return false;
      } else {
         return surface <= seaLevel || landMaskSample != null && landMaskSample.known() && !landMaskSample.land();
      }
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
      return this.riverLakeBlendDistance;
   }

   private int waterInfluencePadding() {
      return Math.max(this.shorelinePadding(), this.regionMargin);
   }

   private boolean useLegacyBlockingWaterFallback() {
      return !DEFER_DETAILED_WATER;
   }

   private WaterSurfaceResolver.WaterColumnData coarseWaterColumnData(int blockX, int blockZ, int coverClass, double previewResolutionMeters) {
      if (this.osmWaterEnabled) {
         TellusOsmWaterSource.FastWaterSample overture = this.osmWaterSource.sampleWater(
            blockX, blockZ, this.projection, OsmQueryMode.BLOCKING
         );
         if (overture.coverageStatus() != TellusOsmWaterSource.CoverageStatus.COMPLETE) {
            throw new OceanCoverageUnavailableException(overture.coverageStatus(), blockX, blockZ);
         }
         WaterSurfaceResolver.SurfaceSample surfaceSample = this.sampleSurface(
            blockX, blockZ, false, previewResolutionMeters
         );
         int surface = surfaceSample.height();
         if (!overture.hasWater()) {
            return new WaterSurfaceResolver.WaterColumnData(false, false, surface, surface);
         }

         boolean ocean = overture.ocean();
         int waterSurface = ocean ? this.resolveOceanWaterSurface(blockX, blockZ) : Math.max(surface + 1, this.seaLevel);
         int terrainSurface = ocean
            ? this.sampleOceanTerrainHeight(blockX, blockZ, waterSurface, previewResolutionMeters)
            : Math.min(surface, waterSurface - OCEAN_MIN_DEPTH);
         return new WaterSurfaceResolver.WaterColumnData(true, ocean, terrainSurface, waterSurface);
      }

      TellusLandMaskSource.LandMaskSample landMaskSample = this.osmWaterEnabled
         ? TellusLandMaskSource.LandMaskSample.unknown()
         : this.sampleLandMask(blockX, blockZ, previewResolutionMeters);
      WaterSurfaceResolver.SurfaceSample surfaceSample = this.sampleSurface(
         blockX, blockZ, coverClass, landMaskSample, previewResolutionMeters
      );
      int surface = surfaceSample.height();
      boolean oceanMask = landMaskSample.known()
         ? !landMaskSample.land() && (coverClass == ESA_NO_DATA || coverClass == ESA_WATER)
         : coverClass == ESA_NO_DATA && surface <= this.seaLevel;
      if (suppressOceanWater(oceanMask, surfaceSample.mapterhornLandOverride())) {
         return new WaterSurfaceResolver.WaterColumnData(false, false, surface, surface);
      }
      boolean isOcean = this.classifyWaterAsOcean(false, landMaskSample, surface, coverClass);
      if (!shouldEmitCoarseFallbackWater(this.osmWaterEnabled, coverClass, isOcean)) {
         return new WaterSurfaceResolver.WaterColumnData(false, false, surface, surface);
      } else {
         int waterSurface = isOcean ? this.resolveOceanWaterSurface(blockX, blockZ) : Math.max(surface + 1, this.seaLevel);
         int terrainSurface = surface;
         int minDepth = Math.max(1, OCEAN_MIN_DEPTH);
         if (isOcean) {
            terrainSurface = this.sampleOceanTerrainHeight(blockX, blockZ, waterSurface, previewResolutionMeters);
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
      return isOcean || (!osmWaterEnabled && coverClass == ESA_WATER);
   }

   static boolean canConnectWaterComponentCells(boolean strictOverture, boolean firstOcean, boolean secondOcean) {
      return !strictOverture || firstOcean == secondOcean;
   }

   private long regionKey(int regionX, int regionZ, long generation) {
      return pack(regionX, regionZ) ^ this.regionSalt ^ Long.rotateLeft(generation * -7046029254386353131L, 23);
   }

   private WaterSurfaceResolver.WaterRegionData buildRegionData(int regionX, int regionZ) {
      long buildCacheGeneration = this.cacheGeneration.get();
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
      boolean[] mapterhornLandOverride = scratch.mapterhornLandOverride;
      boolean[] landMaskLand = scratch.landMaskLand;
      boolean[] lineWaterMask = scratch.lineWaterMask;
      boolean[] areaWaterMask = scratch.areaWaterMask;
      boolean[] flowingWaterMask = scratch.flowingWaterMask;
      boolean[] waterfallNoCarveMask = scratch.waterfallNoCarveMask;
      long[] waterBodyKeys = scratch.waterBodyKeys;
      int[] waterBodySurfaceHints = scratch.waterBodySurfaceHints;
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
      Arrays.fill(mapterhornLandOverride, 0, gridArea, false);
      Arrays.fill(lineWaterMask, 0, gridArea, false);
      Arrays.fill(areaWaterMask, 0, gridArea, false);
      Arrays.fill(flowingWaterMask, 0, gridArea, false);
      Arrays.fill(waterfallNoCarveMask, 0, gridArea, false);
      Arrays.fill(waterBodyKeys, 0, gridArea, 0L);
      Arrays.fill(waterBodySurfaceHints, 0, gridArea, Integer.MIN_VALUE);
      boolean hasWater = false;
      double worldScale = this.settings.worldScale();
      TellusLandMaskSource.LandMaskSampler landMaskSampler = this.osmWaterEnabled ? null : this.landMaskSource.newSampler();

      for (int dz = 0; dz < gridSize; dz++) {
         int worldZ = gridMinZ + dz;
         int row = dz * gridSize;
         int coarseZ = dz / coarseStep;
         int coarseRow = coarseZ * coarseSize;

         for (int dx = 0; dx < gridSize; dx++) {
            int worldX = gridMinX + dx;
            int index = row + dx;
            if (this.osmWaterEnabled) {
               landMaskLand[index] = false;
               WaterSurfaceResolver.SurfaceSample surfaceSample = this.sampleSurface(
                  worldX, worldZ, false, this.settings.worldScale()
               );
               surfaceHeights[index] = surfaceSample.height();
               mapterhornLandOverride[index] = surfaceSample.mapterhornLandOverride();
               baseWaterMask[index] = false;
               noDataMask[index] = false;
               continue;
            }

            TellusLandMaskSource.LandMaskSample landMaskSample = this.sampleLandMask(worldX, worldZ, landMaskSampler, worldScale);
            boolean maskKnown = landMaskSample.known();
            boolean landMaskIsLand = maskKnown && landMaskSample.land();
            boolean landMaskOcean = maskKnown && !landMaskIsLand;
            int coverClass = this.sampleCoverClass(worldX, worldZ);
            WaterSurfaceResolver.SurfaceSample surfaceSample = this.sampleSurface(
               worldX, worldZ, coverClass, landMaskSample, this.settings.worldScale()
            );
            int surface = surfaceSample.height();
            boolean demLandOverride = surfaceSample.mapterhornLandOverride();
            mapterhornLandOverride[index] = demLandOverride;
            boolean isNoData = coverClass == ESA_NO_DATA;
            boolean oceanMask;
            if (maskKnown) {
               oceanMask = landMaskOcean && (isNoData || coverClass == ESA_WATER);
            } else {
               oceanMask = isNoData && surface <= this.seaLevel;
            }

            boolean esaWater = !suppressOceanWater(oceanMask, demLandOverride)
               && (coverClass == ESA_WATER
                  || oceanMask && (surface <= this.seaLevel || landMaskOcean && (isNoData || coverClass == ESA_WATER)));
            boolean isWater = !this.osmWaterEnabled && esaWater;
            landMaskLand[index] = landMaskIsLand;
            surfaceHeights[index] = surface;
            baseWaterMask[index] = isWater;
            noDataMask[index] = isWater && oceanMask;
            if (isWater && landMaskOcean && (isNoData || coverClass == ESA_WATER)) {
               oceanHintMask[index] = true;
            }

            if (isWater) {
               hasWater = true;
               if (!oceanMask && surface <= inlandLevel) {
                  int coarseIndex = coarseRow + dx / coarseStep;
                  coarseWater[coarseIndex] = true;
               }
            }
         }
      }

      if (this.osmWaterEnabled) {
         boolean osmRegionHasWater = this.populateOsmBaseWaterMask(
               gridMinX,
               gridMinZ,
               gridSize,
               surfaceHeights,
               baseWaterMask,
               noDataMask,
               oceanHintMask,
               lineWaterMask,
               areaWaterMask,
               flowingWaterMask,
               waterfallNoCarveMask,
               waterBodyKeys,
               waterBodySurfaceHints
            );
         if (osmRegionHasWater) {
            hasWater = true;
         }

         for (int dz = 0; dz < gridSize; dz++) {
            int row = dz * gridSize;
            int worldZ = gridMinZ + dz;
            for (int dx = 0; dx < gridSize; dx++) {
               int index = row + dx;
               OceanCoastSample coast = this.oceanCoastField.sample(gridMinX + dx, worldZ);
               if (!coast.complete()) {
                  throw new OceanCoverageUnavailableException(coast.coverageStatus(), gridMinX + dx, worldZ);
               }
               if (coast.ocean()) {
                  // Overture ocean/sea polygons are definitive and override any
                  // overlapping inland-water geometry or DEM elevation.
                  baseWaterMask[index] = true;
                  noDataMask[index] = true;
                  oceanHintMask[index] = true;
                  lineWaterMask[index] = false;
                  areaWaterMask[index] = false;
                  flowingWaterMask[index] = false;
                  waterBodyKeys[index] = 0L;
                  waterBodySurfaceHints[index] = Integer.MIN_VALUE;
                  hasWater = true;
               } else {
                  oceanHintMask[index] = false;
                  noDataMask[index] = false;
               }
            }
         }

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
                  indexx,
                  componentCount,
                  gridSize,
                  gridMinX,
                  gridMinZ,
                  baseWaterMask,
                  noDataMask,
                  oceanHintMask,
                  landMaskLand,
                  lineWaterMask,
                  areaWaterMask,
                  flowingWaterMask,
                  waterBodyKeys,
                  waterBodySurfaceHints,
                  surfaceHeights,
                  componentIds
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
            boolean isOcean;
            if (this.osmWaterEnabled) {
               isOcean = component.oceanHinted;
            } else {
               boolean belowSea = component.cellCount > 0 && (double)component.belowSeaCellCount / component.cellCount >= BELOW_SEA_CELL_RATIO;
               boolean inlandConnectedComponent = belowSea && this.componentTouchesInlandConnected(component, inlandConnected, coarseSize, coarseStep, gridSize);
               boolean landMaskInland = component.cellCount > 0 && (double)component.landMaskLandCount / component.cellCount >= LANDMASK_INLAND_RATIO;
               isOcean = component.oceanHinted || !landMaskInland && component.touchesNoData || !landMaskInland && belowSea && !inlandConnectedComponent;
            }

            component.isOcean = isOcean;
            if (!isOcean && !this.osmWaterEnabled) {
               this.attachEsaLakeSurfaceIdentity(component, gridMinX, gridMinZ, gridSize, surfaceHeights);
            }

            int componentSurface;
            if (isOcean) {
               int representativeCell = component.cells.getInt(0);
               int representativeX = gridMinX + representativeCell % gridSize;
               int representativeZ = gridMinZ + representativeCell / gridSize;
               componentSurface = this.resolveOceanWaterSurface(representativeX, representativeZ);
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
               component.channelWidth = minDim;
               double aspect = (double)maxDim / minDim;
               boolean riverShape = shouldClassifyInlandComponentAsRiver(
                  maxDim,
                  minDim,
                  aspect,
                  this.riverMinLength,
                  this.riverMaxWidth,
                  component.cellCount,
                  component.lineWaterCellCount,
                  component.waterBodyKey != 0L,
                  component.touchesEdge,
                  this.regionClamped
               );
               boolean lineDominated = isLineDominatedWaterComponent(component.cellCount, component.lineWaterCellCount);
               boolean flowDominated = isFlowDominatedWaterComponent(component.cellCount, component.flowingWaterCellCount);
               if (flowDominated) {
                  riverShape = true;
               }
               if (riverShape && !lineDominated && this.shouldTreatRiverAsLake(component, width, height, minDim, aspect)) {
                  riverShape = flowDominated;
               }

               component.riverShape = riverShape;
               if (!riverShape) {
                  componentSurface = this.resolveStableLakeSurface(
                     component, capInlandLakeWaterSurface(componentSurface, component.minBorderHeight), buildCacheGeneration
                  );
                  this.fillComponentSurface(component, waterSurface, componentSurface);
               } else {
                  // The connected component is still the analysis boundary, but
                  // its DEM profile is preserved. The flow pass below turns this
                  // per-cell ceiling into bounded hydro-flat reaches.
                  this.fillComponentSurfaceFromDem(component, waterSurface, surfaceHeights);
               }
            }
         }


         boolean[] inlandWaterMask = scratch.inlandWaterMask;
         boolean[] oceanComponentMask = scratch.oceanComponentMask;
         boolean[] terraceWaterMask = scratch.terraceWaterMask;
         boolean[] directRiverWaterMask = scratch.directRiverWaterMask;
         boolean[] waterfallDropMask = scratch.waterfallDropMask;
         boolean[] flowCorrectionMask = scratch.flowCorrectionMask;
         boolean[] waterfallProtectionMask = scratch.waterfallProtectionMask;
         int[] waterfallTop = scratch.waterfallTop;
         int[] adaptiveBlendRadius = scratch.adaptiveBlendRadius;
         Arrays.fill(inlandWaterMask, 0, gridArea, false);
         Arrays.fill(oceanComponentMask, 0, gridArea, false);
         Arrays.fill(terraceWaterMask, 0, gridArea, false);
         Arrays.fill(directRiverWaterMask, 0, gridArea, false);
         Arrays.fill(waterfallDropMask, 0, gridArea, false);
         Arrays.fill(flowCorrectionMask, 0, gridArea, false);
         System.arraycopy(waterfallNoCarveMask, 0, waterfallProtectionMask, 0, gridArea);
         Arrays.fill(waterfallTop, 0, gridArea, Integer.MIN_VALUE);
         Arrays.fill(adaptiveBlendRadius, 0, gridArea, 0);

         for (int ix = 0; ix < componentCount; ix++) {
            WaterSurfaceResolver.ComponentData componentx = components[ix];
            for (int c = 0; c < componentx.cells.size(); c++) {
               int cell = componentx.cells.getInt(c);
               if (componentx.isOcean) {
                  oceanComponentMask[cell] = true;
               } else if (waterfallNoCarveMask[cell]
                  || shouldUseDirectLineWaterCell(lineWaterMask[cell], areaWaterMask[cell])) {
                  // A centreline outside polygon coverage follows the DEM
                  // directly. An Overture waterfall point applies the same DEM
                  // behavior to all mapped water in its chunk-aligned zone.
                  directRiverWaterMask[cell] = true;
                  waterSurface[cell] = directLineRiverWaterSurface(surfaceHeights[cell]);
               } else if (componentx.riverShape || !this.shouldRejectWaterCell(componentx, surfaceHeights[cell], waterSurface[cell])) {
                  inlandWaterMask[cell] = true;
                  terraceWaterMask[cell] = componentx.riverShape && areaWaterMask[cell];
               }
            }
         }

         applyDirectRiverWaterSurfaces(waterSurface, surfaceHeights, directRiverWaterMask);

         boolean[] waterMask = scratch.waterMask;
         boolean[] shapedWaterMask = scratch.cascadeMask;
         boolean[] landMask = scratch.landMask;
         rebuildWaterAndLandMasks(waterMask, landMask, oceanComponentMask, inlandWaterMask, directRiverWaterMask, gridArea);
         rebuildWaterMask(shapedWaterMask, oceanComponentMask, inlandWaterMask, gridArea);

         this.applyRiverBankWaterSurfaceCaps(
            waterSurface, surfaceHeights, terraceWaterMask, landMask, componentIds, components, componentCount, gridSize
         );
         this.applyRiverOceanOutletCaps(waterSurface, terraceWaterMask, oceanComponentMask, gridSize);
         this.applyInlandOceanSurfaceTransition(waterSurface, inlandWaterMask, oceanComponentMask, gridSize);
         for (int ix = 0; ix < componentCount; ix++) {
            WaterSurfaceResolver.ComponentData componentx = components[ix];
            if (!componentx.isOcean && componentx.riverShape && !componentx.areaWaterCells.isEmpty()) {
               IntArrayList flowCells = scratch.flowCells;
               flowCells.clear();
               for (int cellIndex = 0; cellIndex < componentx.areaWaterCells.size(); cellIndex++) {
                  int cell = componentx.areaWaterCells.getInt(cellIndex);
                  if (!waterfallNoCarveMask[cell]) {
                     flowCells.add(cell);
                  }
               }
               if (flowCells.isEmpty()) {
                  continue;
               }
               InlandWaterFlowAnalyzer.analyze(
                  gridSize,
                  flowCells.elements(),
                  flowCells.size(),
                  surfaceHeights,
                  waterSurface,
                  oceanComponentMask,
                  1,
                  componentx.channelWidth,
                  this.flowParameters,
                  waterSurface,
                  inlandWaterMask,
                  waterfallDropMask,
                  waterfallTop,
                  flowCorrectionMask,
                  adaptiveBlendRadius,
                  waterfallProtectionMask,
                  scratch.flowWorkspace
               );
            }
         }
         for (int index = 0; index < gridArea; index++) {
            terraceWaterMask[index] &= inlandWaterMask[index];
         }
         rebuildWaterAndLandMasks(waterMask, landMask, oceanComponentMask, inlandWaterMask, directRiverWaterMask, gridArea);
         rebuildWaterMask(shapedWaterMask, oceanComponentMask, inlandWaterMask, gridArea);
         if (this.repairRejectedInlandWaterCells(
            inlandWaterMask, terraceWaterMask, waterSurface, surfaceHeights, componentIds, components, componentCount
         )) {
            rebuildWaterAndLandMasks(waterMask, landMask, oceanComponentMask, inlandWaterMask, directRiverWaterMask, gridArea);
            rebuildWaterMask(shapedWaterMask, oceanComponentMask, inlandWaterMask, gridArea);
         }

         IntArrayList shoreWater = scratch.shoreWater;
         this.collectInlandShoreWater(shoreWater, inlandWaterMask, gridSize, gridArea);

         int[] waterDistanceCost = scratch.waterDistanceCost;
         int maxDistanceBlocks = Math.min(this.maxDistanceToShore, this.regionMargin);
         this.computeWeightedDistance(waterDistanceCost, inlandWaterMask, shoreWater, gridSize, maxDistanceBlocks, DIST_COST_CARDINAL);
         int maxDistanceCost = maxDistanceBlocks * DIST_COST_CARDINAL;
         if (this.repairRejectedInlandWaterCells(
            inlandWaterMask, terraceWaterMask, waterSurface, surfaceHeights, componentIds, components, componentCount
         )) {
            rebuildWaterAndLandMasks(waterMask, landMask, oceanComponentMask, inlandWaterMask, directRiverWaterMask, gridArea);
            rebuildWaterMask(shapedWaterMask, oceanComponentMask, inlandWaterMask, gridArea);
            this.collectInlandShoreWater(shoreWater, inlandWaterMask, gridSize, gridArea);
            this.computeWeightedDistance(waterDistanceCost, inlandWaterMask, shoreWater, gridSize, maxDistanceBlocks, DIST_COST_CARDINAL);
         }
         this.prepareAdaptiveBlendRadii(
            adaptiveBlendRadius,
            flowCorrectionMask,
            waterfallProtectionMask,
            surfaceHeights,
            waterSurface,
            inlandWaterMask,
            componentIds,
            components,
            componentCount,
            gridSize
         );
         boolean[] cliffLandMask = scratch.cliffLandMask;
         boolean[] cliffWaterMask = scratch.cliffWaterMask;
         Arrays.fill(cliffLandMask, 0, gridArea, false);
         Arrays.fill(cliffWaterMask, 0, gridArea, false);

	         for (int indexxx = 0; indexxx < gridArea; indexxx++) {
	            if (shapedWaterMask[indexxx]) {
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

         for (int indexxxxx = 0; indexxxxx < gridArea; indexxxxx++) {
            if (oceanComponentMask[indexxxxx]) {
	               waterFlags[indexxxxx] = WATER_OCEAN;
	               int x = indexxxxx % gridSize;
	               int z = indexxxxx / gridSize;
	               terrainSurface[indexxxxx] = this.sampleOceanTerrainHeight(
                     gridMinX + x, gridMinZ + z, waterSurface[indexxxxx], this.settings.worldScale()
                  );
               } else if (waterfallDropMask[indexxxxx]) {
                  waterFlags[indexxxxx] = WATER_WATERFALL_DROP;
                  terrainSurface[indexxxxx] = surfaceHeights[indexxxxx];
               } else if (directRiverWaterMask[indexxxxx]) {
                  waterFlags[indexxxxx] = WATER_INLAND;
                  waterSurface[indexxxxx] = directLineRiverWaterSurface(surfaceHeights[indexxxxx]);
                  terrainSurface[indexxxxx] = directLineRiverTerrainSurface(waterSurface[indexxxxx]);
               } else if (inlandWaterMask[indexxxxx]) {
               waterFlags[indexxxxx] = WATER_INLAND;
               int componentId = componentIds[indexxxxx];
               WaterSurfaceResolver.ComponentData componentx = componentId >= 0 && componentId < componentCount
                  ? components[componentId]
                  : null;
               if (cliffWaterMask[indexxxxx] || waterfallProtectionMask[indexxxxx]) {
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
                  double distance = distanceCost / (double)DIST_COST_CARDINAL;
                  int x = indexxxxx % gridSize;
                  int z = indexxxxx / gridSize;
                  int depth = componentx != null && componentx.riverShape
                     ? computeRiverDepth(distance)
                     : LakeBedProfile.depth(distance, gridMinX + x, gridMinZ + z);
                  int floor = waterSurface[indexxxxx] - depth;
                  if (floor >= waterSurface[indexxxxx]) {
                     floor = waterSurface[indexxxxx] - 1;
                  }

                  terrainSurface[indexxxxx] = floor;
               }
            }
         }

         this.applyExperimentalOceanDepthCap(terrainSurface, waterSurface, oceanComponentMask);
         if (this.settings.shorelineBlendCliffLimit()) {
            this.applyInlandShorelineFloorRamp(
               terrainSurface, waterSurface, inlandWaterMask, waterfallProtectionMask, waterDistanceCost, gridSize
            );
         }

         this.applyAdaptiveShorelineBlend(
            terrainSurface,
            surfaceHeights,
            waterSurface,
            inlandWaterMask,
            landMask,
            cliffLandMask,
            flowCorrectionMask,
            adaptiveBlendRadius,
            waterfallProtectionMask,
            gridSize
         );
         this.applyShorelineWallClamp(
            terrainSurface, waterSurface, shapedWaterMask, landMask, cliffLandMask, waterfallProtectionMask, gridSize
         );
         int[] regionTerrain = new int[REGION_SIZE * REGION_SIZE];
         int[] regionWater = new int[REGION_SIZE * REGION_SIZE];
         int[] regionRaw = new int[REGION_SIZE * REGION_SIZE];
         byte[] regionFlags = new byte[REGION_SIZE * REGION_SIZE];
         boolean[] regionSourceConversionProtection = new boolean[REGION_SIZE * REGION_SIZE];

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
               regionSourceConversionProtection[regionIndex] = isSourceConversionProtectedCell(
                  waterfallNoCarveMask[gridIndex],
                  lineWaterMask[gridIndex],
                  areaWaterMask[gridIndex],
                  flag == WATER_INLAND
               );
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
         return new WaterSurfaceResolver.WaterRegionData(
            regionMinX,
            regionMinZ,
            regionTerrain,
            regionWater,
            regionFlags,
            regionRaw,
            regionSourceConversionProtection
         );
      }
   }

   private WaterSurfaceResolver.WaterRegionData buildDryRegionData(
      int regionX, int regionZ, int regionMinX, int regionMinZ, int gridMinX, int gridMinZ, int gridSize, int[] surfaceHeights, long startNanos
   ) {
      int[] regionTerrain = new int[REGION_SIZE * REGION_SIZE];
      int[] regionWater = new int[REGION_SIZE * REGION_SIZE];
      int[] regionRaw = new int[REGION_SIZE * REGION_SIZE];
      byte[] regionFlags = new byte[REGION_SIZE * REGION_SIZE];
      boolean[] regionSourceConversionProtection = new boolean[REGION_SIZE * REGION_SIZE];

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

      return new WaterSurfaceResolver.WaterRegionData(
         regionMinX,
         regionMinZ,
         regionTerrain,
         regionWater,
         regionFlags,
         regionRaw,
         regionSourceConversionProtection
      );
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
      boolean[] lineWaterMask,
      boolean[] areaWaterMask,
      boolean[] flowingWaterMask,
      long[] waterBodyKeys,
      int[] waterBodySurfaceHints,
      int[] surfaceHeights,
      int[] componentIds
   ) {
      IntArrayList cells = new IntArrayList();
      IntArrayList areaWaterCells = new IntArrayList();
      IntArrayList borderHeights = new IntArrayList();
      WaterSurfaceResolver.ComponentData component = new WaterSurfaceResolver.ComponentData(
         componentId, cells, areaWaterCells, borderHeights
      );
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

         if (lineWaterMask[index]) {
            component.lineWaterCellCount++;
         }
         if (areaWaterMask[index]) {
            areaWaterCells.add(index);
         }
         if (flowingWaterMask[index]) {
            component.flowingWaterCellCount++;
         }

         long waterBodyKey = waterBodyKeys[index];
         if (waterBodyKey != 0L) {
            component.recordWaterBodyKey(waterBodyKey);
            int surfaceHint = waterBodySurfaceHints[index];
            component.recordSurfaceHint(surfaceHint);
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
	                  int borderHeight = surfaceHeights[neighbor];
	                  borderHeights.add(borderHeight);
	                  component.minBorderHeight = Math.min(component.minBorderHeight, borderHeight);
               } else if (componentIds[neighbor] == -1
                  && canConnectWaterComponentCells(this.osmWaterEnabled, oceanHintMask[index], oceanHintMask[neighbor])) {
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

   private void attachEsaLakeSurfaceIdentity(
      WaterSurfaceResolver.ComponentData component, int gridMinX, int gridMinZ, int gridSize, int[] surfaceHeights
   ) {
      if (component.waterBodyKey != 0L || component.cells.isEmpty()) {
         return;
      }

      int minSurface = Integer.MAX_VALUE;
      int minWorldX = gridMinX;
      int minWorldZ = gridMinZ;
      int sampleStep = Math.max(1, (component.cells.size() + MAX_FEATURE_SURFACE_SAMPLES - 1) / MAX_FEATURE_SURFACE_SAMPLES);

      for (int i = 0; i < component.cells.size(); i++) {
         int cell = component.cells.getInt(i);
         int surface = surfaceHeights[cell];
         if (surface < minSurface) {
            int localX = cell % gridSize;
            int localZ = cell / gridSize;
            minSurface = surface;
            minWorldX = gridMinX + localX;
            minWorldZ = gridMinZ + localZ;
         }

         if (i % sampleStep == 0) {
            component.surfaceHints.add(surface);
         }
      }

      if (minSurface != Integer.MAX_VALUE) {
         component.surfaceHints.add(minSurface);
         component.recordWaterBodyKey(stableEsaLakeBodyKey(minWorldX, minWorldZ, minSurface));
      }
   }

   static long stableEsaLakeBodyKey(int worldX, int worldZ, int surface) {
      int basinX = Math.floorDiv(worldX, ESA_LAKE_KEY_GRID_BLOCKS);
      int basinZ = Math.floorDiv(worldZ, ESA_LAKE_KEY_GRID_BLOCKS);
      int heightBucket = Math.floorDiv(surface, ESA_LAKE_HEIGHT_BUCKET);
      long key = pack(basinX, basinZ) ^ Long.rotateLeft((long)heightBucket * -7046029254386353131L, 17) ^ ESA_LAKE_BODY_KEY_SALT;
      return key == 0L ? ESA_LAKE_BODY_KEY_SALT : key;
   }

   private int resolveStableLakeSurface(
      WaterSurfaceResolver.ComponentData component, int fallbackSurface, long expectedCacheGeneration
   ) {
      int candidate = fallbackSurface;
      if (!component.surfaceHints.isEmpty()) {
         int hintedSurface = percentile(component.surfaceHints, LAKE_SURFACE_HINT_PERCENTILE);
         candidate = Math.min(fallbackSurface, hintedSurface);
      }

      if (component.waterBodyKey == 0L) {
         return candidate;
      }

      return this.cacheLowestStableLakeSurface(component.waterBodyKey, candidate, expectedCacheGeneration);
   }

   int estimateFastLakeSurface(OsmWaterFeature feature) {
      if (feature.crossesWorldSeam(this.projection)) {
         return Integer.MIN_VALUE;
      }
      long waterBodyKey = stableOsmWaterBodyKey(feature);
      if (waterBodyKey == 0L) {
         return Integer.MIN_VALUE;
      }

      int partCount = feature.partCount();
      double[][] partXs = new double[partCount][];
      double[][] partZs = new double[partCount][];
      for (int part = 0; part < partCount; part++) {
         int pointCount = feature.pointCount(part);
         double[] xs = new double[pointCount];
         double[] zs = new double[pointCount];
         for (int point = 0; point < pointCount; point++) {
            xs[point] = this.projection.lonToBlockX(feature.lonAt(part, point));
            zs[point] = this.projection.latToBlockZ(feature.latAt(part, point));
         }

         partXs[part] = xs;
         partZs[part] = zs;
      }

      return this.estimateOsmFeatureSurface(partXs, partZs);
   }

   long currentCacheGeneration() {
      return this.cacheGeneration.get();
   }

   OceanCoastSample oceanCoastSample(int blockX, int blockZ) {
      return this.oceanCoastField.sample(blockX, blockZ);
   }

   InlandWaterFlowAnalyzer.Parameters inlandFlowParameters() {
      return this.flowParameters;
   }

   public int resolveOceanTerrainSurface(
      int blockX, int blockZ, int waterSurface, double requestedResolutionMeters
   ) {
      return this.sampleOceanTerrainHeight(blockX, blockZ, waterSurface, requestedResolutionMeters);
   }

   public int resolveOceanWaterSurface(int blockX, int blockZ) {
      double localSurfaceElevation = LandlockedSeaLevel.surfaceElevationMetersAtBlock(
         blockX, blockZ, this.projection
      );
      return Double.isFinite(localSurfaceElevation) ? this.scaleElevationToHeight(localSurfaceElevation, blockZ) : this.seaLevel;
   }

   int resolveFastStableLakeSurface(
      long waterBodyKey, int candidateSurface, long expectedCacheGeneration
   ) {
      if (waterBodyKey == 0L) {
         return candidateSurface;
      }

      return this.cacheLowestStableLakeSurface(waterBodyKey, candidateSurface, expectedCacheGeneration);
   }

   private int cacheLowestStableLakeSurface(long waterBodyKey, int candidate, long expectedCacheGeneration) {
      synchronized (this.lakeSurfaceCache) {
         if (this.cacheGeneration.get() != expectedCacheGeneration) {
            return candidate;
         }

         return this.lakeSurfaceCache.asMap().merge(
            this.stableLakeSurfaceKey(waterBodyKey), candidate, WaterSurfaceResolver::lowestStableLakeSurface
         );
      }
   }

   static int lowestStableLakeSurface(int cachedSurface, int candidateSurface) {
      return Math.min(cachedSurface, candidateSurface);
   }

   private long stableLakeSurfaceKey(long waterBodyKey) {
      return waterBodyKey ^ this.regionSalt ^ -4417276706812531889L;
   }

	   private boolean shouldRejectWaterCell(WaterSurfaceResolver.ComponentData component, int rawSurface, int waterSurface) {
	      return component.riverShape
	         ? shouldRejectRiverWaterCell(rawSurface, waterSurface, RIVER_MAX_TERRAIN_CUT)
	         : shouldRejectLakeWaterCell(rawSurface, waterSurface, LAKE_MAX_TERRAIN_CUT);
	   }

   static boolean shouldRejectLakeWaterCell(int rawSurface, int waterSurface, int maxTerrainCut) {
      return rawSurface - waterSurface > maxTerrainCut;
   }

		   static boolean shouldRejectRiverWaterCell(int rawSurface, int waterSurface, int maxTerrainCut) {
		      return waterSurface > rawSurface;
		   }

	   static int lakeMaxTerrainCut() {
	      return LAKE_MAX_TERRAIN_CUT;
	   }

   static int riverMaxTerrainCut() {
      return RIVER_MAX_TERRAIN_CUT;
   }

   static int riverConnectGapBlocks() {
      return RIVER_CONNECT_GAP_BLOCKS;
   }

	   static int capInlandLakeWaterSurface(int waterSurface, int minAdjacentLandSurface) {
	      return minAdjacentLandSurface == Integer.MAX_VALUE ? waterSurface : Math.min(waterSurface, minAdjacentLandSurface);
	   }

	   static int lowestInlandRiverWaterSurface(int minRiverTerrain, int minAdjacentLandSurface, int fallbackSurface) {
	      int surface = minRiverTerrain == Integer.MAX_VALUE ? fallbackSurface : minRiverTerrain;
	      return minAdjacentLandSurface == Integer.MAX_VALUE ? surface : Math.min(surface, minAdjacentLandSurface);
	   }

   static int directLineRiverWaterSurface(int rawSurface) {
      return rawSurface;
   }

   static int directLineRiverTerrainSurface(int waterSurface) {
      return waterSurface == Integer.MIN_VALUE ? waterSurface : waterSurface - 1;
   }

		   static int capInlandRiverWaterSurface(int waterSurface, int rawSurface, int minAdjacentLandSurface) {
	      int capped = Math.min(waterSurface, rawSurface);
	      return minAdjacentLandSurface == Integer.MAX_VALUE ? capped : Math.min(capped, minAdjacentLandSurface);
	   }

	   static int repairRejectedRiverWaterSurface(int waterSurface, int rawSurface, int maxTerrainCut) {
	      return shouldRejectRiverWaterCell(rawSurface, waterSurface, maxTerrainCut)
	         ? Math.min(waterSurface, rawSurface)
	         : waterSurface;
	   }

   static boolean isLineDominatedWaterComponent(int cellCount, int lineWaterCellCount) {
      return cellCount > 0 && lineWaterCellCount > 0 && (double)lineWaterCellCount / cellCount >= RIVER_LINE_COMPONENT_RATIO;
   }

   static boolean isFlowDominatedWaterComponent(int cellCount, int flowingWaterCellCount) {
      return cellCount > 0 && flowingWaterCellCount > 0 && (double)flowingWaterCellCount / cellCount >= RIVER_LINE_COMPONENT_RATIO;
   }

   static boolean isLineOnlyWaterComponent(int cellCount, int lineWaterCellCount, int flowingWaterCellCount) {
      return cellCount > 0 && lineWaterCellCount == cellCount && flowingWaterCellCount == cellCount;
   }

   static boolean shouldUseDirectLineWaterCell(boolean lineWater, boolean areaWater) {
      return lineWater && !areaWater;
   }

   static boolean isLineWaterGeometry(OsmWaterFeature feature) {
      return feature.lineGeometry();
   }

   static boolean shouldClassifyInlandComponentAsRiver(
      int maxDim,
      int minDim,
      double aspect,
      int riverMinLength,
      int riverMaxWidth,
      int cellCount,
      int lineWaterCellCount,
      boolean hasStableWaterBodyKey,
      boolean touchesEdge,
      boolean regionClamped
   ) {
      if (maxDim <= 0 || minDim <= 0 || cellCount <= 0) {
         return false;
      }

      boolean lineDominated = isLineDominatedWaterComponent(cellCount, lineWaterCellCount);
      if (hasStableWaterBodyKey && !lineDominated) {
         return false;
      }

      boolean narrowShape = maxDim >= riverMinLength && minDim <= riverMaxWidth && aspect >= RIVER_ASPECT_RATIO;
      if (narrowShape) {
         return true;
      }

      if (lineDominated) {
         int lineMaxWidth = Math.max(2, riverMaxWidth / 2);
         return maxDim >= 2 && minDim <= lineMaxWidth && aspect >= RIVER_LINE_ASPECT_RATIO;
      }

      return touchesEdge
         && !regionClamped
         && maxDim >= riverMinLength
         && minDim <= riverMaxWidth
         && aspect >= RIVER_LINE_ASPECT_RATIO;
   }

   static int computeRiverDepth(double distance) {
      int depth = 1 + (int)Math.floor(Math.max(0.0, distance - 1.0) / RIVER_DEPTH_DISTANCE_STEP);
      return Mth.clamp(depth, 1, RIVER_MAX_DEPTH);
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
      boolean[] oceanHintMask,
      boolean[] lineWaterMask,
      boolean[] areaWaterMask,
      boolean[] flowingWaterMask,
      boolean[] waterfallNoCarveMask,
      long[] waterBodyKeys,
      int[] waterBodySurfaceHints
   ) {
      int maxBlockX = gridMinX + gridSize - 1;
      int maxBlockZ = gridMinZ + gridSize - 1;
      long queryStartNs = OsmPerf.now();
      TellusOsmWaterSource.WaterQueryResult query = this.osmWaterSource
         .waterForAreaWithStatus(
            gridMinX,
            gridMinZ,
            maxBlockX,
            maxBlockZ,
            this.projection,
            WaterfallNoCarveZone.queryMarginBlocks(this.settings.worldScale()),
            OsmQueryMode.BLOCKING
         );
      OsmPerf.recordWaterQuery(OsmPerf.elapsedSince(queryStartNs), query.features().size());
      if (!query.complete()) {
         throw new OceanCoverageUnavailableException(query.coverageStatus(), gridMinX, gridMinZ);
      }
      if (query.features().isEmpty()) {
         return false;
      } else {
         WaterfallNoCarveZone.markBlockGrid(
            waterfallNoCarveMask,
            gridMinX,
            gridMinZ,
            gridSize,
            gridSize,
            query.features(),
            this.projection
         );

         for (OsmWaterFeature feature : query.features()) {
            if (!feature.oceanHint() && !WaterfallNoCarveZone.isMarker(feature)) {
               this.rasterizeOsmWaterFeature(
                  feature,
                  gridMinX,
                  gridMinZ,
                  gridSize,
                  baseWaterMask,
                  noDataMask,
                  oceanHintMask,
                  lineWaterMask,
                  areaWaterMask,
                  flowingWaterMask,
                  waterBodyKeys,
                  waterBodySurfaceHints
               );
            }
         }

         repairFlowingWaterGaps(
            baseWaterMask, oceanHintMask, lineWaterMask, flowingWaterMask, gridSize, RIVER_CONNECT_GAP_BLOCKS
         );

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
      boolean[] baseWaterMask,
      boolean[] noDataMask,
      boolean[] oceanHintMask,
      boolean[] lineWaterMask,
      boolean[] areaWaterMask,
      boolean[] flowingWaterMask,
      long[] waterBodyKeys,
      int[] waterBodySurfaceHints
   ) {
      if (feature.crossesWorldSeam(this.projection)) {
         return;
      }
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
            double worldX = this.projection.lonToBlockX(feature.lonAt(part, point));
            double worldZ = this.projection.latToBlockZ(feature.latAt(part, point));
            partXs[part][point] = worldX;
            partZs[part][point] = worldZ;
            minWorldX = Math.min(minWorldX, Mth.floor(worldX));
            maxWorldX = Math.max(maxWorldX, Mth.ceil(worldX));
            minWorldZ = Math.min(minWorldZ, Mth.floor(worldZ));
            maxWorldZ = Math.max(maxWorldZ, Mth.ceil(worldZ));
         }
      }

      long waterBodyKey = stableOsmWaterBodyKey(feature);
      int waterBodySurfaceHint = waterBodyKey == 0L ? Integer.MIN_VALUE : this.estimateOsmFeatureSurface(partXs, partZs);
      int gridMaxX = gridMinX + gridSize - 1;
      int gridMaxZ = gridMinZ + gridSize - 1;
      boolean lineWaterGeometry = isLineWaterGeometry(feature);
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
                  lineWaterMask,
                  flowingWaterMask,
                  feature.oceanHint(),
                  lineWaterGeometry,
                  feature.flowingWater(),
                  waterBodyKey,
                  waterBodySurfaceHint,
                  waterBodyKeys,
                  waterBodySurfaceHints,
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
               (worldX, worldZ) -> {
                  this.markOsmWaterCell(
                     worldX,
                     worldZ,
                     gridMinX,
                     gridMinZ,
                     gridSize,
                     baseWaterMask,
                     noDataMask,
                     oceanHintMask,
                     lineWaterMask,
                     flowingWaterMask,
                     feature.oceanHint(),
                     false,
                     feature.flowingWater(),
                     waterBodyKey,
                     waterBodySurfaceHint,
                     waterBodyKeys,
                     waterBodySurfaceHints
                  );
                  markOsmAreaWaterCell(worldX, worldZ, gridMinX, gridMinZ, gridSize, areaWaterMask);
               }
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
      boolean[] lineWaterMask,
      boolean[] flowingWaterMask,
      boolean oceanHint,
      boolean lineWater,
      boolean flowingWater,
      long waterBodyKey,
      int waterBodySurfaceHint,
      long[] waterBodyKeys,
      int[] waterBodySurfaceHints,
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
               this.markOsmWaterCell(
                  worldX,
                  worldZ,
                  gridMinX,
                  gridMinZ,
                  gridSize,
                  baseWaterMask,
                  noDataMask,
                  oceanHintMask,
                  lineWaterMask,
                  flowingWaterMask,
                  oceanHint,
                  lineWater,
                  flowingWater,
                  waterBodyKey,
                  waterBodySurfaceHint,
                  waterBodyKeys,
                  waterBodySurfaceHints
               );
            }
         }
      }

      this.rasterizeOsmLineCenterPath(
         startX,
         startZ,
         endX,
         endZ,
         gridMinX,
         gridMinZ,
         gridSize,
         baseWaterMask,
         noDataMask,
         oceanHintMask,
         lineWaterMask,
         flowingWaterMask,
         oceanHint,
         lineWater,
         flowingWater,
         waterBodyKey,
         waterBodySurfaceHint,
         waterBodyKeys,
         waterBodySurfaceHints
      );
   }

   private void rasterizeOsmLineCenterPath(
      double startX,
      double startZ,
      double endX,
      double endZ,
      int gridMinX,
      int gridMinZ,
      int gridSize,
      boolean[] baseWaterMask,
      boolean[] noDataMask,
      boolean[] oceanHintMask,
      boolean[] lineWaterMask,
      boolean[] flowingWaterMask,
      boolean oceanHint,
      boolean lineWater,
      boolean flowingWater,
      long waterBodyKey,
      int waterBodySurfaceHint,
      long[] waterBodyKeys,
      int[] waterBodySurfaceHints
   ) {
      double dx = endX - startX;
      double dz = endZ - startZ;
      int steps = Math.max(1, (int)Math.ceil(Math.max(Math.abs(dx), Math.abs(dz)) * 2.0));
      int previousX = nearestBlock(startX);
      int previousZ = nearestBlock(startZ);
      this.markConnectedOsmLineCells(
         previousX,
         previousZ,
         previousX,
         previousZ,
         gridMinX,
         gridMinZ,
         gridSize,
         baseWaterMask,
         noDataMask,
         oceanHintMask,
         lineWaterMask,
         flowingWaterMask,
         oceanHint,
         lineWater,
         flowingWater,
         waterBodyKey,
         waterBodySurfaceHint,
         waterBodyKeys,
         waterBodySurfaceHints
      );

      for (int step = 1; step <= steps; step++) {
         double t = (double)step / steps;
         int x = nearestBlock(startX + dx * t);
         int z = nearestBlock(startZ + dz * t);
         this.markConnectedOsmLineCells(
            previousX,
            previousZ,
            x,
            z,
            gridMinX,
            gridMinZ,
            gridSize,
            baseWaterMask,
            noDataMask,
            oceanHintMask,
            lineWaterMask,
            flowingWaterMask,
            oceanHint,
            lineWater,
            flowingWater,
            waterBodyKey,
            waterBodySurfaceHint,
            waterBodyKeys,
            waterBodySurfaceHints
         );
         previousX = x;
         previousZ = z;
      }
   }

   private void markConnectedOsmLineCells(
      int fromX,
      int fromZ,
      int toX,
      int toZ,
      int gridMinX,
      int gridMinZ,
      int gridSize,
      boolean[] baseWaterMask,
      boolean[] noDataMask,
      boolean[] oceanHintMask,
      boolean[] lineWaterMask,
      boolean[] flowingWaterMask,
      boolean oceanHint,
      boolean lineWater,
      boolean flowingWater,
      long waterBodyKey,
      int waterBodySurfaceHint,
      long[] waterBodyKeys,
      int[] waterBodySurfaceHints
   ) {
      int x = fromX;
      int z = fromZ;
      this.markOsmLineCell(
         x,
         z,
         gridMinX,
         gridMinZ,
         gridSize,
         baseWaterMask,
         noDataMask,
         oceanHintMask,
         lineWaterMask,
         flowingWaterMask,
         oceanHint,
         lineWater,
         flowingWater,
         waterBodyKey,
         waterBodySurfaceHint,
         waterBodyKeys,
         waterBodySurfaceHints
      );

      while (x != toX || z != toZ) {
         int stepX = Integer.compare(toX, x);
         if (stepX != 0) {
            x += stepX;
            this.markOsmLineCell(
               x,
               z,
               gridMinX,
               gridMinZ,
               gridSize,
               baseWaterMask,
               noDataMask,
               oceanHintMask,
               lineWaterMask,
               flowingWaterMask,
               oceanHint,
               lineWater,
               flowingWater,
               waterBodyKey,
               waterBodySurfaceHint,
               waterBodyKeys,
               waterBodySurfaceHints
            );
         }

         int stepZ = Integer.compare(toZ, z);
         if (stepZ != 0) {
            z += stepZ;
            this.markOsmLineCell(
               x,
               z,
               gridMinX,
               gridMinZ,
               gridSize,
               baseWaterMask,
               noDataMask,
               oceanHintMask,
               lineWaterMask,
               flowingWaterMask,
               oceanHint,
               lineWater,
               flowingWater,
               waterBodyKey,
               waterBodySurfaceHint,
               waterBodyKeys,
               waterBodySurfaceHints
            );
         }
      }
   }

   private void markOsmLineCell(
      int worldX,
      int worldZ,
      int gridMinX,
      int gridMinZ,
      int gridSize,
      boolean[] baseWaterMask,
      boolean[] noDataMask,
      boolean[] oceanHintMask,
      boolean[] lineWaterMask,
      boolean[] flowingWaterMask,
      boolean oceanHint,
      boolean lineWater,
      boolean flowingWater,
      long waterBodyKey,
      int waterBodySurfaceHint,
      long[] waterBodyKeys,
      int[] waterBodySurfaceHints
   ) {
      this.markOsmWaterCell(
         worldX,
         worldZ,
         gridMinX,
         gridMinZ,
         gridSize,
         baseWaterMask,
         noDataMask,
         oceanHintMask,
         lineWaterMask,
         flowingWaterMask,
         oceanHint,
         lineWater,
         flowingWater,
         waterBodyKey,
         waterBodySurfaceHint,
         waterBodyKeys,
         waterBodySurfaceHints
      );
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
      boolean[] lineWaterMask,
      boolean[] flowingWaterMask,
      boolean oceanHint,
      boolean lineWater,
      boolean flowingWater,
      long waterBodyKey,
      int waterBodySurfaceHint,
      long[] waterBodyKeys,
      int[] waterBodySurfaceHints
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
         if (lineWater) {
            lineWaterMask[index] = true;
         }
         if (flowingWater) {
            flowingWaterMask[index] = true;
         }

         if (waterBodyKey != 0L) {
            long existingKey = waterBodyKeys[index];
            if (existingKey == 0L || Long.compareUnsigned(waterBodyKey, existingKey) < 0) {
               waterBodyKeys[index] = waterBodyKey;
               waterBodySurfaceHints[index] = waterBodySurfaceHint;
            } else if (existingKey == waterBodyKey && waterBodySurfaceHints[index] == Integer.MIN_VALUE) {
               waterBodySurfaceHints[index] = waterBodySurfaceHint;
            }
         }
      }
   }

   private static void markOsmAreaWaterCell(
      int worldX, int worldZ, int gridMinX, int gridMinZ, int gridSize, boolean[] areaWaterMask
   ) {
      int localX = worldX - gridMinX;
      int localZ = worldZ - gridMinZ;
      if (localX >= 0 && localZ >= 0 && localX < gridSize && localZ < gridSize) {
         areaWaterMask[localZ * gridSize + localX] = true;
      }
   }

   private static long stableOsmWaterBodyKey(OsmWaterFeature feature) {
      if (feature.flowingWater() || feature.oceanHint()) {
         return 0L;
      }

      return feature.featureId();
   }

   static int repairFlowingWaterGaps(
      boolean[] waterMask,
      boolean[] oceanHintMask,
      boolean[] lineWaterMask,
      boolean[] flowingWaterMask,
      int gridSize,
      int maxGap
   ) {
      if (maxGap <= 0 || gridSize <= 0 || waterMask.length < gridSize * gridSize) {
         return 0;
      }

      int repaired = 0;
      int maxDistance = maxGap + 1;
      for (int start = 0; start < gridSize * gridSize; start++) {
         if (!flowingWaterMask[start] || oceanHintMask[start]) {
            continue;
         }

         int startX = start % gridSize;
         int startZ = start / gridSize;
         for (int direction = 0; direction < NEIGHBOR_OFFSETS_8.length; direction += 2) {
            int dx = NEIGHBOR_OFFSETS_8[direction];
            int dz = NEIGHBOR_OFFSETS_8[direction + 1];
            for (int distance = 1; distance <= maxDistance; distance++) {
               int x = startX + dx * distance;
               int z = startZ + dz * distance;
               if (x < 0 || z < 0 || x >= gridSize || z >= gridSize) {
                  break;
               }

               int target = z * gridSize + x;
               if (oceanHintMask[target]) {
                  break;
               }
               if (!waterMask[target]) {
                  continue;
               }
               if (distance > 1) {
                  repaired += fillFlowingWaterConnection(
                     startX,
                     startZ,
                     x,
                     z,
                     gridSize,
                     waterMask,
                     lineWaterMask,
                     flowingWaterMask,
                     lineWaterMask[start] && lineWaterMask[target]
                  );
               }
               break;
            }
         }
      }
      return repaired;
   }

   private static int fillFlowingWaterConnection(
      int fromX,
      int fromZ,
      int toX,
      int toZ,
      int gridSize,
      boolean[] waterMask,
      boolean[] lineWaterMask,
      boolean[] flowingWaterMask,
      boolean lineWater
   ) {
      int repaired = 0;
      int x = fromX;
      int z = fromZ;
      while (x != toX || z != toZ) {
         int stepX = Integer.compare(toX, x);
         if (stepX != 0) {
            x += stepX;
            repaired += markFlowingConnectionCell(x, z, gridSize, waterMask, lineWaterMask, flowingWaterMask, lineWater);
         }

         int stepZ = Integer.compare(toZ, z);
         if (stepZ != 0) {
            z += stepZ;
            repaired += markFlowingConnectionCell(x, z, gridSize, waterMask, lineWaterMask, flowingWaterMask, lineWater);
         }
      }
      return repaired;
   }

   private static int markFlowingConnectionCell(
      int x,
      int z,
      int gridSize,
      boolean[] waterMask,
      boolean[] lineWaterMask,
      boolean[] flowingWaterMask,
      boolean lineWater
   ) {
      int index = z * gridSize + x;
      boolean repaired = !waterMask[index];
      waterMask[index] = true;
      flowingWaterMask[index] = true;
      if (lineWater) {
         lineWaterMask[index] = true;
      }
      return repaired ? 1 : 0;
   }

   private int estimateOsmFeatureSurface(double[][] partXs, double[][] partZs) {
      IntArrayList samples = new IntArrayList();
      int totalPoints = 0;

      for (double[] xs : partXs) {
         totalPoints += xs.length;
      }

      int sampleStep = Math.max(1, (totalPoints + MAX_FEATURE_SURFACE_SAMPLES - 1) / MAX_FEATURE_SURFACE_SAMPLES);
      int pointIndex = 0;

      for (int part = 0; part < partXs.length; part++) {
         double[] xs = partXs[part];
         double[] zs = partZs[part];

         for (int point = 0; point < xs.length; point++) {
            if (pointIndex++ % sampleStep == 0) {
               int worldX = Mth.floor(xs[point] + 0.5);
               int worldZ = Mth.floor(zs[point] + 0.5);
               TellusLandMaskSource.LandMaskSample landMaskSample = this.sampleLandMask(worldX, worldZ);
               int coverClass = this.sampleCoverClass(worldX, worldZ);
               samples.add(this.sampleSurfaceHeight(worldX, worldZ, coverClass, landMaskSample));
            }
         }
      }

      return samples.isEmpty() ? Integer.MIN_VALUE : percentile(samples, LAKE_SURFACE_HINT_PERCENTILE);
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

   private void applyRiverBankWaterSurfaceCaps(
      int[] waterSurface,
      int[] surfaceHeights,
      boolean[] riverWaterMask,
      boolean[] landMask,
      int[] componentIds,
      WaterSurfaceResolver.ComponentData[] components,
      int componentCount,
      int gridSize
   ) {
      int gridArea = gridSize * gridSize;

	      for (int index = 0; index < gridArea; index++) {
	         if (riverWaterMask[index]) {
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

            waterSurface[index] = capInlandRiverWaterSurface(waterSurface[index], surfaceHeights[index], minLandHeight);
         }
      }
   }

   private void applyRiverOceanOutletCaps(
      int[] waterSurface, boolean[] riverWaterMask, boolean[] oceanWaterMask, int gridSize
   ) {
      for (int index = 0; index < riverWaterMask.length; index++) {
         if (!riverWaterMask[index]) {
            continue;
         }
         int x = index % gridSize;
         int z = index / gridSize;
         int outletSurface = Integer.MAX_VALUE;
         for (int offset = 0; offset < NEIGHBOR_OFFSETS.length; offset += 2) {
            int nx = x + NEIGHBOR_OFFSETS[offset];
            int nz = z + NEIGHBOR_OFFSETS[offset + 1];
            if (nx >= 0 && nz >= 0 && nx < gridSize && nz < gridSize) {
               int neighbor = nz * gridSize + nx;
               if (oceanWaterMask[neighbor]) {
                  outletSurface = Math.min(outletSurface, waterSurface[neighbor]);
               }
            }
         }
         if (outletSurface != Integer.MAX_VALUE && outletSurface < waterSurface[index]) {
            waterSurface[index] = outletSurface;
         }
      }
   }

   private void prepareAdaptiveBlendRadii(
      int[] adaptiveBlendRadius,
      boolean[] correctionRequired,
      boolean[] waterfallProtection,
      int[] rawTerrain,
      int[] waterSurface,
      boolean[] inlandWaterMask,
      int[] componentIds,
      WaterSurfaceResolver.ComponentData[] components,
      int componentCount,
      int gridSize
   ) {
      for (int index = 0; index < inlandWaterMask.length; index++) {
         if (!inlandWaterMask[index] || waterfallProtection[index]) {
            adaptiveBlendRadius[index] = 0;
            continue;
         }
         int cut = Math.max(0, rawTerrain[index] - waterSurface[index]);
         if (cut <= 0 && !correctionRequired[index]) {
            adaptiveBlendRadius[index] = 0;
            continue;
         }
         correctionRequired[index] = true;
         int componentId = componentIds[index];
         WaterSurfaceResolver.ComponentData component = componentId >= 0 && componentId < componentCount
            ? components[componentId]
            : null;
         int blendCut = component != null && component.riverShape
            ? Math.min(cut, this.flowParameters.maxProfileCut())
            : cut;
         int width = component == null ? 1 : component.channelWidth;
         int localSlope = localComponentSlope(index, gridSize, rawTerrain, componentIds, componentId);
         int radius = this.riverLakeBlendDistance
            + blendCut * 3
            + Math.min(12, localSlope * 2)
            + Math.min(12, Math.max(1, width) / 3);
         adaptiveBlendRadius[index] = Math.min(
            Math.max(this.riverLakeBlendDistance, MAX_ADAPTIVE_BLEND_BLOCKS),
            Math.max(adaptiveBlendRadius[index], Math.max(this.riverLakeBlendDistance, radius))
         );
      }
   }

   private static int localComponentSlope(
      int index, int gridSize, int[] terrain, int[] componentIds, int componentId
   ) {
      int slope = 0;
      int x = index % gridSize;
      int z = index / gridSize;
      for (int offset = 0; offset < NEIGHBOR_OFFSETS.length; offset += 2) {
         int nx = x + NEIGHBOR_OFFSETS[offset];
         int nz = z + NEIGHBOR_OFFSETS[offset + 1];
         if (nx >= 0 && nz >= 0 && nx < gridSize && nz < gridSize) {
            int neighbor = nz * gridSize + nx;
            if (componentIds[neighbor] == componentId) {
               slope = Math.max(slope, Math.abs(terrain[index] - terrain[neighbor]));
            }
         }
      }
      return slope;
   }

	   private boolean repairRejectedInlandWaterCells(
	      boolean[] inlandWaterMask,
	      boolean[] riverWaterMask,
	      int[] waterSurface,
      int[] surfaceHeights,
      int[] componentIds,
      WaterSurfaceResolver.ComponentData[] components,
      int componentCount
   ) {
      boolean maskChanged = false;

	      for (int index = 0; index < inlandWaterMask.length; index++) {
	         if (inlandWaterMask[index]) {
	            int componentId = componentIds[index];
	            WaterSurfaceResolver.ComponentData component = componentId >= 0 && componentId < componentCount ? components[componentId] : null;
	            if (component != null && component.riverShape && this.shouldRejectWaterCell(component, surfaceHeights[index], waterSurface[index])) {
	               waterSurface[index] = repairRejectedRiverWaterSurface(waterSurface[index], surfaceHeights[index], RIVER_MAX_TERRAIN_CUT);
	            }

	            if (component != null && this.shouldRejectWaterCell(component, surfaceHeights[index], waterSurface[index])) {
	               inlandWaterMask[index] = false;
	               if (riverWaterMask != null) {
	                  riverWaterMask[index] = false;
	               }
	               maskChanged = true;
	            }
	         }
	      }

	      return maskChanged;
	   }

	   private static void rebuildWaterAndLandMasks(
	      boolean[] waterMask,
	      boolean[] landMask,
	      boolean[] oceanWaterMask,
	      boolean[] inlandWaterMask,
	      boolean[] directRiverWaterMask,
	      int gridArea
	   ) {
	      for (int index = 0; index < gridArea; index++) {
	         waterMask[index] = oceanWaterMask[index] || inlandWaterMask[index] || directRiverWaterMask[index];
	         landMask[index] = !waterMask[index];
	      }
	   }

	   private static void rebuildWaterMask(boolean[] waterMask, boolean[] oceanWaterMask, boolean[] inlandWaterMask, int gridArea) {
	      for (int index = 0; index < gridArea; index++) {
	         waterMask[index] = oceanWaterMask[index] || inlandWaterMask[index];
	      }
	   }

   private static void applyDirectRiverWaterSurfaces(
      int[] waterSurface, int[] surfaceHeights, boolean[] directRiverWaterMask
   ) {
      for (int index = 0; index < directRiverWaterMask.length; index++) {
         if (directRiverWaterMask[index]) {
            waterSurface[index] = directLineRiverWaterSurface(surfaceHeights[index]);
         }
      }
   }

	   private void collectInlandShoreWater(IntArrayList shoreWater, boolean[] inlandWaterMask, int gridSize, int gridArea) {
	      shoreWater.clear();

	      for (int index = 0; index < gridArea; index++) {
	         if (inlandWaterMask[index]) {
	            int x = index % gridSize;
	            int z = index / gridSize;
	            if (this.isShoreCell(x, z, gridSize, inlandWaterMask)) {
	               shoreWater.add(index);
	            }
	         }
	      }
	   }

   private void applyInlandOceanSurfaceTransition(int[] waterSurface, boolean[] inlandWaterMask, boolean[] oceanWaterMask, int gridSize) {
      if (INLAND_OCEAN_TRANSITION_BLOCKS <= 0) {
         return;
      }

      int gridArea = gridSize * gridSize;
      WaterSurfaceResolver.RegionScratch scratch = REGION_SCRATCH.get();
      IntArrayList oceanEdge = scratch.shoreLand;
      int[] nearestOceanSurface = scratch.nearestSurface;
      oceanEdge.clear();

      for (int index = 0; index < gridArea; index++) {
         if (inlandWaterMask[index]) {
            int x = index % gridSize;
            int z = index / gridSize;
            int adjacentOceanSurface = Integer.MAX_VALUE;

            for (int n = 0; n < NEIGHBOR_OFFSETS.length; n += 2) {
               int nx = x + NEIGHBOR_OFFSETS[n];
               int nz = z + NEIGHBOR_OFFSETS[n + 1];
               if (nx >= 0 && nz >= 0 && nx < gridSize && nz < gridSize && oceanWaterMask[nz * gridSize + nx]) {
                  adjacentOceanSurface = Math.min(adjacentOceanSurface, waterSurface[nz * gridSize + nx]);
               }
            }

            if (adjacentOceanSurface != Integer.MAX_VALUE) {
               nearestOceanSurface[index] = adjacentOceanSurface;
               oceanEdge.add(index);
            }
         }
      }

      if (oceanEdge.isEmpty()) {
         return;
      }

      int transitionBlocks = Math.min(INLAND_OCEAN_TRANSITION_BLOCKS, this.regionMargin);
      if (transitionBlocks <= 0) {
         return;
      }

      int[] oceanDistanceCost = scratch.landDistanceCost;
      this.computeWeightedDistanceWithSurface(
         oceanDistanceCost, nearestOceanSurface, inlandWaterMask, oceanEdge, gridSize, transitionBlocks, 0
      );
      int maxCost = transitionBlocks * DIST_COST_CARDINAL;

      for (int index = 0; index < gridArea; index++) {
         int distanceCost = oceanDistanceCost[index];
         if (inlandWaterMask[index] && distanceCost != Integer.MAX_VALUE && distanceCost <= maxCost) {
            double t = smoothstep(distanceCost / (double)Math.max(1, maxCost));
            int currentSurface = waterSurface[index];
            int oceanSurface = nearestOceanSurface[index];
            waterSurface[index] = transitionedWaterSurface(currentSurface, oceanSurface, t);
         }
      }
   }

   static int transitionedWaterSurface(int currentSurface, int oceanSurface, double transitionProgress) {
      int transitioned = (int)Math.round(Mth.lerp(transitionProgress, oceanSurface, currentSurface));
      return currentSurface >= oceanSurface
         ? Mth.clamp(transitioned, oceanSurface, currentSurface)
         : Mth.clamp(transitioned, currentSurface, oceanSurface);
   }

   private void applyShorelineWallClamp(
      int[] terrainSurface,
      int[] waterSurface,
      boolean[] waterMask,
      boolean[] landMask,
      boolean[] cliffLandMask,
      boolean[] waterfallProtectionMask,
      int gridSize
   ) {
      int gridArea = gridSize * gridSize;

      for (int index = 0; index < gridArea; index++) {
         if (landMask[index] && !cliffLandMask[index] && !waterfallProtectionMask[index]) {
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
               if (diff > 0 && diff <= SHORELINE_WALL_CLAMP_HEIGHT) {
                  terrainSurface[index] = minWaterSurface;
               }
            }
         }
      }
   }

   private void applyInlandShorelineFloorRamp(
      int[] terrainSurface,
      int[] waterSurface,
      boolean[] inlandWaterMask,
      boolean[] waterfallProtectionMask,
      int[] waterDistanceCost,
      int gridSize
   ) {
      int blendDistance = Math.min(this.riverLakeBlendDistance, this.regionMargin);
      if (blendDistance <= 0) {
         return;
      }

      int maxBlendCost = blendDistance * DIST_COST_CARDINAL;
      int gridArea = gridSize * gridSize;

      for (int index = 0; index < gridArea; index++) {
         int distanceCost = waterDistanceCost[index];
         if (inlandWaterMask[index]
            && !waterfallProtectionMask[index]
            && distanceCost != Integer.MAX_VALUE
            && distanceCost <= maxBlendCost) {
            terrainSurface[index] = rampedInlandShoreFloor(terrainSurface[index], waterSurface[index], distanceCost);
         }
      }
   }

   static int rampedInlandShoreFloor(int terrainFloor, int waterSurface, int distanceCost) {
      if (distanceCost == Integer.MAX_VALUE) {
         return terrainFloor;
      }

      int distanceBlocks = Math.max(1, (distanceCost + DIST_COST_CARDINAL - 1) / DIST_COST_CARDINAL);
      return rampedInlandShoreFloorForSteps(terrainFloor, waterSurface, distanceBlocks);
   }

   static int rampedInlandShoreFloorForSteps(int terrainFloor, int waterSurface, int distanceSteps) {
      int rampDepth = 1 + Math.max(0, distanceSteps - 1) * INLAND_SHORE_BANK_RAMP_MAX_SLOPE;
      int shallowFloor = waterSurface - rampDepth;
      int rampedFloor = Math.max(terrainFloor, shallowFloor);
      return Math.min(rampedFloor, waterSurface - OCEAN_MIN_DEPTH);
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
            blendLandMask[index] = landMask[index];
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
                     int sourceSurface = nearestSurface[indexx];
                     int base = baseSurface[indexx];
                     int naturalized = naturalizedInlandBankSurface(
                        base,
                        sourceSurface,
                        distanceCost,
                        blendDistance,
                        this.settings.shorelineBlendCliffLimit()
                     );
                     if (naturalized < terrainSurface[indexx]) {
                        terrainSurface[indexx] = naturalized;
                     }
                  }
               }
            }
         }
      }
   }

   private void applyAdaptiveShorelineBlend(
      int[] terrainSurface,
      int[] baseSurface,
      int[] waterSurface,
      boolean[] waterMask,
      boolean[] landMask,
      boolean[] cliffLandMask,
      boolean[] correctionRequired,
      int[] adaptiveBlendRadius,
      boolean[] waterfallProtection,
      int gridSize
   ) {
      int gridArea = gridSize * gridSize;
      WaterSurfaceResolver.RegionScratch scratch = REGION_SCRATCH.get();
      int[] remainingInfluence = scratch.adaptiveRemainingInfluence;
      int[] sourceRadius = scratch.adaptiveSourceRadius;
      int[] nearestSurface = scratch.nearestSurface;
      Arrays.fill(remainingInfluence, 0, gridArea, -1);
      Arrays.fill(sourceRadius, 0, gridArea, 0);

      int maximumRadius = 0;
      for (int index = 0; index < gridArea; index++) {
         if (waterMask[index] && correctionRequired[index] && !waterfallProtection[index]) {
            maximumRadius = Math.max(maximumRadius, adaptiveBlendRadius[index]);
         }
      }
      if (maximumRadius <= 0) {
         return;
      }

      int maximumCost = maximumRadius * DIST_COST_CARDINAL;
      scratch.ensureBucketCapacity(maximumCost + 1);
      IntArrayList[] buckets = scratch.buckets;
      boolean[] bucketUsed = scratch.bucketUsed;
      IntArrayList usedBuckets = scratch.usedBuckets;
      usedBuckets.clear();

      for (int index = 0; index < gridArea; index++) {
         int radius = adaptiveBlendRadius[index];
         if (!waterMask[index] || !correctionRequired[index] || radius <= 0 || waterfallProtection[index]) {
            continue;
         }
         int x = index % gridSize;
         int z = index / gridSize;
         int initialRemaining = radius * DIST_COST_CARDINAL - DIST_COST_CARDINAL;
         if (initialRemaining < 0) {
            continue;
         }
         for (int offset = 0; offset < NEIGHBOR_OFFSETS.length; offset += 2) {
            int nx = x + NEIGHBOR_OFFSETS[offset];
            int nz = z + NEIGHBOR_OFFSETS[offset + 1];
            if (nx < 0 || nz < 0 || nx >= gridSize || nz >= gridSize) {
               continue;
            }
            int neighbor = nz * gridSize + nx;
            if (!landMask[neighbor] || cliffLandMask[neighbor] || waterfallProtection[neighbor]) {
               continue;
            }
            if (initialRemaining > remainingInfluence[neighbor]
               || initialRemaining == remainingInfluence[neighbor] && waterSurface[index] < nearestSurface[neighbor]) {
               remainingInfluence[neighbor] = initialRemaining;
               sourceRadius[neighbor] = radius;
               nearestSurface[neighbor] = waterSurface[index];
               addBucket(buckets, bucketUsed, usedBuckets, initialRemaining, neighbor);
            }
         }
      }

      for (int remaining = maximumCost; remaining >= 0; remaining--) {
         IntArrayList bucket = buckets[remaining];
         if (bucket == null || bucket.isEmpty()) {
            continue;
         }
         for (int bucketIndex = 0; bucketIndex < bucket.size(); bucketIndex++) {
            int index = bucket.getInt(bucketIndex);
            if (remainingInfluence[index] != remaining || remaining <= 0) {
               continue;
            }
            int x = index % gridSize;
            int z = index / gridSize;
            for (int offset = 0; offset < NEIGHBOR_OFFSETS_8.length; offset += 2) {
               int nx = x + NEIGHBOR_OFFSETS_8[offset];
               int nz = z + NEIGHBOR_OFFSETS_8[offset + 1];
               if (nx < 0 || nz < 0 || nx >= gridSize || nz >= gridSize) {
                  continue;
               }
               int neighbor = nz * gridSize + nx;
               if (!landMask[neighbor] || cliffLandMask[neighbor] || waterfallProtection[neighbor]) {
                  continue;
               }
               int nextRemaining = remaining - NEIGHBOR_COSTS_8[offset / 2];
               if (nextRemaining < 0 || nextRemaining <= remainingInfluence[neighbor]) {
                  continue;
               }
               remainingInfluence[neighbor] = nextRemaining;
               sourceRadius[neighbor] = sourceRadius[index];
               nearestSurface[neighbor] = nearestSurface[index];
               addBucket(buckets, bucketUsed, usedBuckets, nextRemaining, neighbor);
            }
         }
         bucket.clear();
      }

      for (int index = 0; index < gridArea; index++) {
         int radius = sourceRadius[index];
         if (radius <= 0 || remainingInfluence[index] < 0 || waterfallProtection[index]) {
            continue;
         }
         int distanceCost = radius * DIST_COST_CARDINAL - remainingInfluence[index];
         int naturalized = naturalizedInlandBankSurface(
            baseSurface[index],
            nearestSurface[index],
            distanceCost,
            radius,
            this.settings.shorelineBlendCliffLimit()
         );
         if (naturalized < terrainSurface[index]) {
            terrainSurface[index] = naturalized;
         }
      }
      clearBuckets(buckets, bucketUsed, usedBuckets);
   }

   static int naturalizedInlandBankSurface(
      int baseSurface,
      int waterSurface,
      int distanceCost,
      int blendDistance,
      boolean limitCliffSlope
   ) {
      if (distanceCost == Integer.MAX_VALUE || blendDistance <= 0) {
         return baseSurface;
      }

      int distanceBlocks = Math.max(1, (distanceCost + DIST_COST_CARDINAL - 1) / DIST_COST_CARDINAL);
      if (distanceBlocks > blendDistance || baseSurface <= waterSurface) {
         return baseSurface;
      }

      double progress = distanceBlocks / (double)blendDistance;
      int blended = (int)Math.round(Mth.lerp(smoothstep(progress), waterSurface, baseSurface));
      if (limitCliffSlope) {
         int slopeLimited = waterSurface + distanceBlocks * INLAND_BANK_MAX_RISE_PER_BLOCK;
         blended = Math.min(blended, slopeLimited);
      }
      return Mth.clamp(blended, waterSurface, baseSurface);
   }

   private static double smoothstep(double value) {
      double t = Mth.clamp(value, 0.0, 1.0);
      return t * t * (3.0 - 2.0 * t);
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

   private void fillComponentSurfaceFromDem(
      WaterSurfaceResolver.ComponentData component, int[] waterSurface, int[] surfaceHeights
   ) {
      for (int i = 0; i < component.cells.size(); i++) {
         int cell = component.cells.getInt(i);
         waterSurface[cell] = surfaceHeights[cell];
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

   private TerrainPreloadPackage.Sample samplePreloadedTerrain(
      double blockX, double blockZ, double previewResolutionMeters
   ) {
      return this.preloadedTerrain.sample(Mth.floor(blockX), Mth.floor(blockZ), previewResolutionMeters);
   }

   private int sampleCoverClass(int blockX, int blockZ) {
      TerrainPreloadPackage.Sample preloaded = this.preloadedTerrain.sample(blockX, blockZ, this.settings.worldScale());
      return preloaded == null ? this.landCoverSource.sampleCoverClass(blockX, blockZ, this.projection) : preloaded.coverClass();
   }

   private TellusLandMaskSource.LandMaskSample sampleLandMask(int blockX, int blockZ) {
      return this.sampleLandMask(blockX, blockZ, this.settings.worldScale());
   }

   private TellusLandMaskSource.LandMaskSample sampleLandMask(
      int blockX, int blockZ, double previewResolutionMeters
   ) {
      TerrainPreloadPackage.Sample preloaded = this.preloadedTerrain.sample(blockX, blockZ, previewResolutionMeters);
      if (preloaded == null) {
         return this.landMaskSource.sampleLandMask(blockX, blockZ, this.projection);
      } else {
         return preloaded.landMaskKnown()
            ? TellusLandMaskSource.LandMaskSample.known(preloaded.land())
            : TellusLandMaskSource.LandMaskSample.unknown();
      }
   }

   private TellusLandMaskSource.LandMaskSample sampleLandMask(
      int blockX, int blockZ, TellusLandMaskSource.LandMaskSampler sampler, double previewResolutionMeters
   ) {
      TerrainPreloadPackage.Sample preloaded = this.preloadedTerrain.sample(blockX, blockZ, previewResolutionMeters);
      if (preloaded == null) {
         return sampler == null
            ? this.landMaskSource.sampleLandMask(blockX, blockZ, this.projection)
            : sampler.sample(blockX, blockZ, this.projection);
      } else {
         return preloaded.landMaskKnown()
            ? TellusLandMaskSource.LandMaskSample.known(preloaded.land())
            : TellusLandMaskSource.LandMaskSample.unknown();
      }
   }

   private int sampleSurfaceHeight(double blockX, double blockZ, int coverClass, TellusLandMaskSource.LandMaskSample landMaskSample) {
      return this.sampleSurfaceHeight(blockX, blockZ, coverClass, landMaskSample, this.settings.worldScale());
   }

   private int sampleSurfaceHeight(
      double blockX, double blockZ, int coverClass, TellusLandMaskSource.LandMaskSample landMaskSample, double previewResolutionMeters
   ) {
      return this.sampleSurface(
         blockX, blockZ, coverClass, landMaskSample, previewResolutionMeters
      ).height();
   }

   private WaterSurfaceResolver.SurfaceSample sampleSurface(
      double blockX,
      double blockZ,
      int coverClass,
      TellusLandMaskSource.LandMaskSample landMaskSample,
      double previewResolutionMeters
   ) {
      boolean oceanMask = this.useOceanZoom(landMaskSample, coverClass);
      return this.sampleSurface(blockX, blockZ, oceanMask, previewResolutionMeters);
   }

   private WaterSurfaceResolver.SurfaceSample sampleSurface(
      double blockX, double blockZ, boolean oceanMask, double previewResolutionMeters
   ) {
      TerrainPreloadPackage.Sample preloaded = this.samplePreloadedTerrain(blockX, blockZ, previewResolutionMeters);
      if (preloaded != null) {
         return new WaterSurfaceResolver.SurfaceSample(
            preloaded.terrainHeight(), preloaded.mapterhornLandOverride()
         );
      }

      TellusElevationSource.ResolvedElevationSample elevation = this.elevationSource.sampleResolvedPreviewElevationMeters(
         blockX, blockZ, this.projection, oceanMask, this.settings.demSelection(), previewResolutionMeters
      );
      return new WaterSurfaceResolver.SurfaceSample(
         this.scaleElevationToHeight(elevation.elevationMeters(), blockZ), elevation.mapterhornLandOverride()
      );
   }

   private int sampleOceanTerrainHeight(double blockX, double blockZ, int waterSurface, double previewResolutionMeters) {
      OceanCoastSample coast = this.oceanCoastField.sample(Mth.floor(blockX), Mth.floor(blockZ));
      if (!coast.complete()) {
         throw new OceanCoverageUnavailableException(coast.coverageStatus(), Mth.floor(blockX), Mth.floor(blockZ));
      }

      OceanCoastField.BathymetrySample raw = this.sampleRawOceanDepth(Mth.floor(blockX), Mth.floor(blockZ));
      int rawFloor = waterSurface - Math.max(OCEAN_MIN_DEPTH, raw.depth());
      int profiledFloor = OceanFloorProfile.floorHeight(
            waterSurface,
            rawFloor,
            coast.coastDistance(),
            coast.correctionRequired(),
            this.oceanFloorTransitionBlocks,
            Math.max(1.0, previewResolutionMeters / Math.max(1.0E-4, this.settings.worldScale())),
            this.minimumOffshoreDepth
         );
      return this.clampOceanTerrainHeight(
         OceanFloorProfile.fitFloorToWorld(
            waterSurface,
            profiledFloor,
            this.minimumOceanFloor,
            this.expectedMaximumOceanDepth
         ),
         waterSurface
      );
   }

   private OceanCoastField.BathymetrySample sampleRawOceanDepth(int blockX, int blockZ) {
      double elevation = this.elevationSource.sampleOpenWatersElevationMeters(
         blockX, blockZ, this.projection, this.settings.worldScale()
      );
      if (Double.isFinite(elevation)) {
         if (elevation < 0.0) {
            int floor = this.scaleElevationToHeight(elevation, blockZ);
            int waterSurface = this.resolveOceanWaterSurface(blockX, blockZ);
            return new OceanCoastField.BathymetrySample(true, Math.max(OCEAN_MIN_DEPTH, waterSurface - floor));
         }

         // A zero or positive OpenWaters value inside a definitive Overture
         // ocean polygon is bad bathymetry, not evidence of land.
         return new OceanCoastField.BathymetrySample(false, OCEAN_MIN_DEPTH);
      }

      return new OceanCoastField.BathymetrySample(
         false,
         fallbackOceanDepthBlocks(
            blockX,
            blockZ,
            this.settings.worldScale(),
            this.settings.effectiveOceanicHeightScale()
         )
      );
   }

   private int clampOceanTerrainHeight(int terrainSurface, int waterSurface) {
      return clampOceanTerrainHeight(
         terrainSurface, waterSurface, this.minimumOceanFloor, this.settings.experimentalIncreaseHeight()
      );
   }

   static int clampOceanTerrainHeight(
      int terrainSurface, int waterSurface, int minimumOceanFloor, boolean enforceWorldFloor
   ) {
      int clamped = Math.min(terrainSurface, waterSurface - OCEAN_MIN_DEPTH);
      return enforceWorldFloor ? Math.max(clamped, minimumOceanFloor) : clamped;
   }

   private void applyExperimentalOceanDepthCap(int[] terrainSurface, int[] waterSurface, boolean[] oceanMask) {
      if (!this.settings.experimentalIncreaseHeight()) {
         return;
      }

      for (int index = 0; index < terrainSurface.length; index++) {
         if (oceanMask[index]) {
            terrainSurface[index] = this.clampOceanTerrainHeight(terrainSurface[index], waterSurface[index]);
         }
      }
   }

   static int fallbackOceanDepthBlocks(double blockX, double blockZ, double worldScale, double oceanicHeightScale) {
      double scale = Math.max(1.0E-4, worldScale);
      int cellSize = Math.max(16, (int)Math.round(1000.0 / scale));
      long cellX = Math.floorDiv(Mth.floor(blockX), cellSize);
      long cellZ = Math.floorDiv(Mth.floor(blockZ), cellSize);
      double t = unitHash(cellX * -7046029254386353131L ^ cellZ * -4417276706812531889L);
      double depthMeters = Mth.lerp(t, FALLBACK_OCEAN_MIN_DEPTH_METERS, FALLBACK_OCEAN_MAX_DEPTH_METERS);
      int depth = (int)Math.round(depthMeters * Math.max(0.0, oceanicHeightScale) / scale);
      return Math.max(OCEAN_MIN_DEPTH + 1, depth);
   }

   private static double unitHash(long value) {
      long mixed = value;
      mixed ^= mixed >>> 33;
      mixed *= -49064778989728563L;
      mixed ^= mixed >>> 33;
      mixed *= -4265267296055464877L;
      mixed ^= mixed >>> 33;
      return (mixed >>> 11) * 0x1.0p-53;
   }

   private int scaleElevationToHeight(double elevation, double blockZ) {
      return TerrainHeightTransform.blockOffset(
         elevation,
         blockZ,
         this.projection,
         this.settings.effectiveTerrestrialHeightScale(),
         this.settings.effectiveOceanicHeightScale(),
         this.settings.experimentalIncreaseHeight(),
         this.settings.automaticHeightScaling()
      ) + this.settings.effectiveHeightOffset();
   }

   static int scaleElevationToHeight(double elevation, double blockZ, EarthGeneratorSettings settings) {
      return TerrainHeightTransform.blockOffset(
         elevation,
         blockZ,
         settings.projection(),
         settings.effectiveTerrestrialHeightScale(),
         settings.effectiveOceanicHeightScale(),
         settings.experimentalIncreaseHeight(),
         settings.automaticHeightScaling()
      ) + settings.effectiveHeightOffset();
   }

   private boolean classifyWaterAsOcean(
      boolean overtureOcean,
      TellusLandMaskSource.LandMaskSample landMaskSample,
      int surface,
      int coverClass
   ) {
      return OceanClassification.isOcean(overtureOcean, this.osmWaterEnabled, landMaskSample, surface, coverClass, this.seaLevel);
   }

   static boolean suppressOceanWater(boolean oceanMask, boolean mapterhornLandOverride) {
      return false;
   }

   static int oceanFloorTransitionBlocks() {
      return OCEAN_FLOOR_TRANSITION_BLOCKS;
   }

   static int oceanFloorSupportBlocks() {
      return OCEAN_FLOOR_SUPPORT_BLOCKS;
   }

   private boolean useOceanZoom(TellusLandMaskSource.LandMaskSample landSample, int coverClass) {
      if (this.osmWaterEnabled || !landSample.known()) {
         return false;
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

   private static int nearestBlock(double value) {
      return Mth.floor(value + 0.5);
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

   private static final class ComponentData {
      private final int id;
      private final IntArrayList cells;
      private final IntArrayList areaWaterCells;
      private final IntArrayList borderHeights;
      private int minX = Integer.MAX_VALUE;
      private int maxX = Integer.MIN_VALUE;
      private int minZ = Integer.MAX_VALUE;
      private int maxZ = Integer.MIN_VALUE;
	      private int minHeight = Integer.MAX_VALUE;
	      private int minBorderHeight = Integer.MAX_VALUE;
	      private long heightSum;
      private int cellCount;
      private int landMaskLandCount;
      private int lineWaterCellCount;
      private int flowingWaterCellCount;
      private int belowSeaCellCount;
      private boolean touchesNoData;
      private boolean touchesEdge;
      private boolean oceanHinted;
      private boolean isOcean;
      private boolean riverShape;
      private long waterBodyKey;
      private int channelWidth = 1;
      private final IntArrayList surfaceHints = new IntArrayList();
      private int lastSurfaceHint = Integer.MIN_VALUE;

      private ComponentData(int id, IntArrayList cells, IntArrayList areaWaterCells, IntArrayList borderHeights) {
         this.id = id;
         this.cells = cells;
         this.areaWaterCells = areaWaterCells;
         this.borderHeights = borderHeights;
      }

      private int averageHeight() {
         if (this.cellCount <= 0) {
            return this.minHeight == Integer.MAX_VALUE ? 0 : this.minHeight;
         } else {
            return (int)Math.round((double)this.heightSum / this.cellCount);
         }
      }

      private void recordWaterBodyKey(long bodyKey) {
         if (bodyKey != 0L && (this.waterBodyKey == 0L || Long.compareUnsigned(bodyKey, this.waterBodyKey) < 0)) {
            this.waterBodyKey = bodyKey;
         }
      }

      private void recordSurfaceHint(int surfaceHint) {
         if (surfaceHint != Integer.MIN_VALUE && (this.surfaceHints.isEmpty() || surfaceHint != this.lastSurfaceHint)) {
            this.surfaceHints.add(surfaceHint);
            this.lastSurfaceHint = surfaceHint;
         }
      }
   }

   private static final class RegionLookup {
      private final int[] regionXs = new int[REGION_LOOKUP_CAPACITY];
      private final int[] regionZs = new int[REGION_LOOKUP_CAPACITY];
      private final WaterSurfaceResolver.WaterRegionData[] regions = new WaterSurfaceResolver.WaterRegionData[REGION_LOOKUP_CAPACITY];
      private int size;
      private long generation = Long.MIN_VALUE;

      private void resetIfStale(long generation) {
         if (this.generation != generation) {
            Arrays.fill(this.regions, null);
            this.size = 0;
            this.generation = generation;
         }
      }

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

   private record SurfaceSample(int height, boolean mapterhornLandOverride) {
   }

   private static final class RegionScratch {
      private int capacity;
      private boolean[] baseWaterMask;
      private boolean[] noDataMask;
      private boolean[] oceanHintMask;
      private boolean[] mapterhornLandOverride;
      private boolean[] landMaskLand;
      private boolean[] lineWaterMask;
      private boolean[] areaWaterMask;
      private boolean[] flowingWaterMask;
      private boolean[] waterfallNoCarveMask;
      private long[] waterBodyKeys;
      private int[] waterBodySurfaceHints;
      private int[] surfaceHeights;
      private int[] componentIds;
      private WaterSurfaceResolver.ComponentData[] components;
      private int[] waterSurface;
      private int[] terrainSurface;
      private byte[] waterFlags;
	      private boolean[] inlandWaterMask;
	      private boolean[] oceanComponentMask;
	      private boolean[] terraceWaterMask;
	      private boolean[] directRiverWaterMask;
	      private boolean[] waterMask;
      private boolean[] landMask;
      private boolean[] cliffLandMask;
      private boolean[] cliffWaterMask;
      private boolean[] blendLandMask;
      private boolean[] cascadeMask;
      private boolean[] waterfallDropMask;
      private boolean[] flowCorrectionMask;
      private boolean[] waterfallProtectionMask;
      private int[] waterDistanceCost;
      private int[] landDistanceCost;
      private int[] nearestSurface;
      private int[] waterfallTop;
      private int[] adaptiveBlendRadius;
      private int[] adaptiveRemainingInfluence;
      private int[] adaptiveSourceRadius;
      private boolean[] landSource;
      private final IntArrayList shoreWater = new IntArrayList();
      private final IntArrayList shoreLand = new IntArrayList();
      private final IntArrayList flowCells = new IntArrayList();
      private int coarseCapacity;
      private boolean[] coarseWater;
      private boolean[] coarseInlandSeed;
      private boolean[] coarseInlandConnected;
      private final IntArrayList coarseQueue = new IntArrayList();
      private IntArrayList[] buckets;
      private boolean[] bucketUsed;
      private final IntArrayList usedBuckets = new IntArrayList();
      private int bucketCapacity;
      private final InlandWaterFlowAnalyzer.Workspace flowWorkspace = new InlandWaterFlowAnalyzer.Workspace();

      private void ensureCapacity(int size) {
         if (size > this.capacity) {
            this.capacity = size;
            this.baseWaterMask = new boolean[size];
            this.noDataMask = new boolean[size];
            this.oceanHintMask = new boolean[size];
            this.mapterhornLandOverride = new boolean[size];
            this.landMaskLand = new boolean[size];
            this.lineWaterMask = new boolean[size];
            this.areaWaterMask = new boolean[size];
            this.flowingWaterMask = new boolean[size];
            this.waterfallNoCarveMask = new boolean[size];
            this.waterBodyKeys = new long[size];
            this.waterBodySurfaceHints = new int[size];
            this.surfaceHeights = new int[size];
            this.componentIds = new int[size];
            this.components = new WaterSurfaceResolver.ComponentData[size];
            this.waterSurface = new int[size];
            this.terrainSurface = new int[size];
            this.waterFlags = new byte[size];
	            this.inlandWaterMask = new boolean[size];
	            this.oceanComponentMask = new boolean[size];
	            this.terraceWaterMask = new boolean[size];
	            this.directRiverWaterMask = new boolean[size];
	            this.waterMask = new boolean[size];
            this.landMask = new boolean[size];
            this.cliffLandMask = new boolean[size];
            this.cliffWaterMask = new boolean[size];
            this.blendLandMask = new boolean[size];
            this.cascadeMask = new boolean[size];
            this.waterfallDropMask = new boolean[size];
            this.flowCorrectionMask = new boolean[size];
            this.waterfallProtectionMask = new boolean[size];
            this.waterDistanceCost = new int[size];
            this.landDistanceCost = new int[size];
            this.nearestSurface = new int[size];
            this.waterfallTop = new int[size];
            this.adaptiveBlendRadius = new int[size];
            this.adaptiveRemainingInfluence = new int[size];
            this.adaptiveSourceRadius = new int[size];
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
         byte flag = this.waterFlags[WaterSurfaceResolver.chunkIndex(localX, localZ)];
         return flag == WATER_INLAND || flag == WATER_OCEAN;
      }

      public boolean isOcean(int localX, int localZ) {
         return this.waterFlags[WaterSurfaceResolver.chunkIndex(localX, localZ)] == WATER_OCEAN;
      }

      public boolean isWaterfallDrop(int localX, int localZ) {
         return this.waterFlags[WaterSurfaceResolver.chunkIndex(localX, localZ)] == WATER_WATERFALL_DROP;
      }

      public boolean approximate() {
         return this.approximate;
      }
   }

   public record WaterColumnData(boolean hasWater, boolean isOcean, int terrainSurface, int waterSurface) {
   }

   public record WaterfallInfo(boolean waterfall, int terrainSurface, int upstreamSurface) {
      private static final WaterSurfaceResolver.WaterfallInfo NONE = new WaterSurfaceResolver.WaterfallInfo(
         false, Integer.MIN_VALUE, Integer.MIN_VALUE
      );
   }

   public record PreviewWaterGrid(
      int[] terrainSurface,
      int[] waterSurface,
      boolean[] inlandWater,
      boolean[] waterfallDrop,
      boolean[] waterfallProtection
   ) {
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
      private final boolean[] sourceConversionProtection;

      private WaterRegionData(
         int minX,
         int minZ,
         int[] terrainSurface,
         int[] waterSurface,
         byte[] waterFlags,
         int[] rawSurface,
         boolean[] sourceConversionProtection
      ) {
         this.minX = minX;
         this.minZ = minZ;
         this.terrainSurface = terrainSurface;
         this.waterSurface = waterSurface;
         this.waterFlags = waterFlags;
         this.rawSurface = rawSurface;
         this.sourceConversionProtection = sourceConversionProtection;
      }

      private WaterSurfaceResolver.WaterColumnData columnData(int blockX, int blockZ) {
         int index = this.index(blockX, blockZ);
         byte flag = this.waterFlags[index];
         boolean hasWater = flag == WATER_INLAND || flag == WATER_OCEAN;
         int resolvedWaterSurface = hasWater ? this.waterSurface[index] : this.terrainSurface[index];
         return new WaterSurfaceResolver.WaterColumnData(hasWater, flag == WATER_OCEAN, this.terrainSurface[index], resolvedWaterSurface);
      }

      private WaterSurfaceResolver.WaterfallInfo waterfallInfo(int blockX, int blockZ) {
         int index = this.index(blockX, blockZ);
         return this.waterFlags[index] == WATER_WATERFALL_DROP
            ? new WaterSurfaceResolver.WaterfallInfo(true, this.terrainSurface[index], this.waterSurface[index])
            : WaterSurfaceResolver.WaterfallInfo.NONE;
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

      private boolean suppressesSourceConversion(int blockX, int blockZ) {
         return this.sourceConversionProtection[this.index(blockX, blockZ)];
      }

      private int index(int blockX, int blockZ) {
         int localX = blockX - this.minX;
         int localZ = blockZ - this.minZ;
         return localZ * REGION_SIZE + localX;
      }
   }
}
