package com.yucareux.tellus.worldgen;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheHandle;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.world.data.mask.TellusLandMaskSource;
import com.yucareux.tellus.world.data.osm.OsmPerf;
import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.osm.TellusOsmWaterSource;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.util.Mth;

public final class DhLodWaterResolver implements TellusCacheHandle {
   private static final int ESA_NO_DATA = 0;
   private static final int ESA_WATER = 80;
   private static final int ESA_MANGROVES = 95;
   private static final int MAX_RASTER_CACHE = intProperty("tellus.dhWaterRasterCacheSize", 128, 16, 2048);
   private static final int INLAND_OCEAN_TRANSITION_BLOCKS = intProperty("tellus.dhWaterInlandOceanTransitionBlocks", 48, 0, 512);
   private static final int OCEAN_FLOOR_TRANSITION_BLOCKS = intProperty(
      "tellus.dhWaterOceanFloorTransitionBlocks",
      intProperty(
         "tellus.water.oceanFloorTransitionBlocks",
         OceanFloorProfile.DEFAULT_TRANSITION_BLOCKS,
         0,
         OceanFloorProfile.MAX_TRANSITION_BLOCKS
      ),
      0,
      OceanFloorProfile.MAX_TRANSITION_BLOCKS
   );
   private static final int OCEAN_LOD_LINEAR_DEPTH = intProperty("tellus.dhWaterOceanLinearDepth", 48, 1, 512);
   private static final int OCEAN_LOD_DEPTH_COMPRESSION_BLOCKS = intProperty("tellus.dhWaterOceanDepthCompressionBlocks", 32, 1, 512);
   private static final boolean OCEAN_LOD_DEPTH_COMPRESSION_ENABLED = Boolean.parseBoolean(
      System.getProperty("tellus.dhWaterOceanDepthCompressionEnabled", "false")
   );
   private static final int INLAND_SIMPLE_WATER_DEPTH = intProperty("tellus.lodInlandWaterDepth", 20, 1, 64);
   private static final int INLAND_SIMPLE_MAX_SLOPE_PER_FOUR_BLOCKS = intProperty("tellus.dhWaterMaxSlopePerFourBlocks", 5, 0, 64);
   private static final int WATER_QUERY_MAX_TILES = intProperty("tellus.dhWaterMaxQueryTiles", 64, 1, 4096);
   private static final int WATER_QUERY_MIN_TILES = intProperty("tellus.dhWaterMinQueryTiles", 4, 1, WATER_QUERY_MAX_TILES);
   private static final int WATER_QUERY_FULL_DETAIL = intProperty("tellus.dhWaterFullQueryMaxDetail", 6, 0, 24);
   private static final int[] SAMPLE_OFFSETS_SINGLE = new int[]{0, 0};
   private static final int[] SAMPLE_OFFSETS_CELL_2 = new int[]{-1, -1, 0, -1, -1, 0, 0, 0};
   private static final int[] SAMPLE_OFFSETS_SMALL_GRID = new int[]{-1, -1, 0, -1, 1, -1, -1, 0, 0, 0, 1, 0, -1, 1, 0, 1, 1, 1};
   private static final int[] NEIGHBOR_OFFSETS = new int[]{1, 0, -1, 0, 0, 1, 0, -1};
   private final EarthChunkGenerator generator;
   private final EarthGeneratorSettings settings;
   private final WorldProjection projection;
   private final TellusLandMaskSource landMaskSource;
   private final TellusOsmWaterSource osmWaterSource;
   private final WaterSurfaceResolver fullWaterResolver;
   private final Cache<DhLodWaterResolver.AreaKey, DhLodWaterResolver.RasterizedWaterArea> rasterCache;
   private final AtomicLong cacheGeneration = new AtomicLong();
   private final ThreadLocal<InlandWaterFlowAnalyzer.Workspace> flowWorkspace = ThreadLocal.withInitial(
      InlandWaterFlowAnalyzer.Workspace::new
   );

   public DhLodWaterResolver(EarthChunkGenerator generator) {
      this.generator = generator;
      this.settings = generator.settings();
      this.projection = this.settings.projection();
      this.landMaskSource = TellusWorldgenSources.landMask();
      this.osmWaterSource = TellusWorldgenSources.osmWater();
      this.fullWaterResolver = TellusWorldgenSources.waterResolver(this.settings);
      this.rasterCache = CacheBuilder.newBuilder().maximumSize(MAX_RASTER_CACHE).build();
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
      this.rasterCache.invalidateAll();
      this.rasterCache.cleanUp();
   }

   public DhLodWaterResolver.AreaResult resolveArea(
      int baseX,
      int baseZ,
      int lodSizePoints,
      int cellSize,
      int[] worldXs,
      int[] worldZs,
      int[] baseTerrainSurface,
      int[] coverClasses,
      boolean useDetailedWaterResolver
   ) {
      int area = lodSizePoints * lodSizePoints;
      if (area == 0) {
         return new DhLodWaterResolver.AreaResult(new int[0], new int[0], new boolean[0], new boolean[0]);
      } else if (worldXs.length != lodSizePoints
         || worldZs.length != lodSizePoints
         || baseTerrainSurface.length != area
         || coverClasses.length != area) {
         throw new IllegalArgumentException("Invalid DH water area input dimensions");
      } else {
         DhLodWaterResolver.RasterizedWaterArea rasterized = this.rasterizedWaterArea(baseX, baseZ, lodSizePoints, cellSize);
         int[] terrainSurface = Arrays.copyOf(baseTerrainSurface, area);
         int[] waterSurface = Arrays.copyOf(baseTerrainSurface, area);
         int[] minimumWaterSurface = new int[area];
         boolean[] hasWater = new boolean[area];
         boolean[] ocean = new boolean[area];
         int[] oceanCoastDistance = new int[area];
         boolean[] directLineWater = new boolean[area];
         boolean[] detailedInlandWater = useDetailedWaterResolver ? new boolean[area] : null;
         boolean[] waterfall = new boolean[area];
         boolean[] demFlowWater = new boolean[area];
         boolean[] flowCorrection = new boolean[area];
         int[] adaptiveBlendRadius = new int[area];
         boolean[] waterfallProtection = Arrays.copyOf(rasterized.waterfallNoCarve(), area);
         boolean osmWaterEnabled = this.settings.enableWater();
         double worldScale = this.settings.worldScale();
         boolean[] renderWater = rasterized.renderWater();
         boolean[] rasterizedOcean = rasterized.ocean();
         boolean[] sampledOcean = rasterized.sampledOcean();
         boolean[] lineWater = rasterized.lineWater();
         boolean[] areaWater = rasterized.areaWater();
         boolean[] flowingWater = rasterized.flowingWater();
         long[] waterBodyKeys = rasterized.waterBodyKeys();
         int[] waterBodySurfaceHints = rasterized.waterBodySurfaceHints();
         boolean rasterIncomplete = rasterized.incomplete();
         int seaLevel = this.generator.getSeaLevel();
         TellusLandMaskSource.LandMaskSampler landMaskSampler = this.landMaskSource.newSampler();
         double previewResolutionMeters = Math.max(worldScale, cellSize * worldScale);
         boolean anyWater = false;
         Arrays.fill(minimumWaterSurface, Integer.MIN_VALUE);

         for (int localZ = 0; localZ < lodSizePoints; localZ++) {
            throwIfCancelled();
            int worldZ = worldZs[localZ];
            int row = localZ * lodSizePoints;

            for (int localX = 0; localX < lodSizePoints; localX++) {
               int index = row + localX;
               int worldX = worldXs[localX];
               int coverClass = coverClasses[index];
               int surface = terrainSurface[index];
               OceanCoastSample coastSample = osmWaterEnabled ? this.fullWaterResolver.oceanCoastSample(worldX, worldZ) : null;
               if (coastSample != null && !coastSample.complete()) {
                  throw new OceanCoverageUnavailableException(coastSample.coverageStatus(), worldX, worldZ);
               }
               boolean overtureOcean = coastSample != null && coastSample.ocean();
               boolean overtureWater = osmWaterEnabled && renderWater[index] && !rasterizedOcean[index];
               boolean esaWater = !osmWaterEnabled && coverClass == ESA_WATER;
               if (coastSample != null) {
                  oceanCoastDistance[index] = coastSample.coastDistance();
               }
               if (osmWaterEnabled) {
                  boolean cellHasWater = overtureOcean || overtureWater;
                  hasWater[index] = cellHasWater;
                  ocean[index] = overtureOcean;
                  if (overtureOcean) {
                     waterSurface[index] = this.fullWaterResolver.resolveOceanWaterSurface(worldX, worldZ);
                  }
                  anyWater |= cellHasWater;
                  continue;
               }
               boolean sampledOceanCell = sampledOcean[index];
               boolean belowSeaLevel = surface <= seaLevel;
               boolean needsLandMask = !overtureOcean
                  && shouldSampleLandMask(sampledOceanCell, belowSeaLevel, coverClass, rasterIncomplete);
               TellusLandMaskSource.LandMaskSample landMaskSample = needsLandMask
                  ? landMaskSampler.sample(worldX, worldZ, this.projection)
                  : null;
               boolean oceanHint = overtureOcean || sampledOceanCell;
               boolean oceanMaskCandidate = oceanHint
                  || isKnownOceanMask(landMaskSample) && (coverClass == ESA_NO_DATA || coverClass == ESA_WATER);
               boolean demLandOverride = oceanMaskCandidate
                  && this.generator.resolveLodMapterhornLandOverride(worldX, worldZ, previewResolutionMeters);
               boolean cellOcean = !demLandOverride
                  && shouldClassifyWaterCellAsOcean(osmWaterEnabled, overtureWater, sampledOceanCell)
                  && classifyWaterCellAsOcean(oceanHint, osmWaterEnabled, landMaskSample, surface, coverClass, seaLevel);
               boolean cellHasWater = !demLandOverride && (overtureWater || cellOcean || esaWater);
               if (!demLandOverride && !cellHasWater && sampledOceanCell && (belowSeaLevel || isKnownOceanMask(landMaskSample))) {
                  cellHasWater = true;
                  cellOcean = true;
               }

               if (cellHasWater) {
                  if (coverClass == ESA_MANGROVES) {
                     minimumWaterSurface[index] = this.generator.resolveLodMangroveWaterSurface(worldX, worldZ, seaLevel);
                  }
               } else if (!osmWaterEnabled && coverClass == ESA_MANGROVES) {
                  int mangroveSurface = this.generator.resolveLodMangroveWaterSurface(worldX, worldZ, seaLevel);
                  if (mangroveSurface > surface) {
                     cellHasWater = true;
                     minimumWaterSurface[index] = mangroveSurface;
                  }
               }

               hasWater[index] = cellHasWater;
               ocean[index] = cellOcean;
               if (cellOcean) {
                  waterSurface[index] = this.fullWaterResolver.resolveOceanWaterSurface(worldX, worldZ);
               }
               anyWater |= cellHasWater;
            }
         }

         if (!anyWater) {
            return new DhLodWaterResolver.AreaResult(terrainSurface, waterSurface, hasWater, ocean);
         }

         this.assignWaterSurfaces(
	            waterSurface,
	            baseTerrainSurface,
	            minimumWaterSurface,
	            hasWater,
	            ocean,
	            lineWater,
	            areaWater,
	            flowingWater,
	            directLineWater,
	            waterfall,
	            demFlowWater,
	            flowCorrection,
	            adaptiveBlendRadius,
	            waterfallProtection,
	            waterBodyKeys,
	            waterBodySurfaceHints,
	            rasterized.sourceGeneration(),
	            lodSizePoints,
	            seaLevel,
	            cellSize
         );
         this.applyInlandOceanSurfaceTransition(
            waterSurface, hasWater, ocean, waterfall, demFlowWater, lodSizePoints, cellSize
         );
         // Polygon reaches may intentionally require deep excavation. Their
         // DEM-driven classification already happened in the shared flow
         // analyzer, so the generic lake cut/slope filters must not undo it.
         removeSteepNonFlowInlandWater(
            waterSurface,
            baseTerrainSurface,
            hasWater,
            ocean,
            directLineWater,
            waterfallProtection,
            demFlowWater,
            lodSizePoints,
            cellSize,
            INLAND_SIMPLE_MAX_SLOPE_PER_FOUR_BLOCKS
         );
         removeOverBudgetNonFlowInlandWater(
            waterSurface,
            baseTerrainSurface,
            hasWater,
            ocean,
            directLineWater,
            waterfall,
            demFlowWater
         );

         for (int localZ = 0; localZ < lodSizePoints; localZ++) {
            throwIfCancelled();
            int worldZ = worldZs[localZ];
            int row = localZ * lodSizePoints;

            for (int localX = 0; localX < lodSizePoints; localX++) {
               int index = row + localX;
               if (hasWater[index]) {
	                  int worldX = worldXs[localX];
		                  int surface = terrainSurface[index];
	                  int cellWaterSurface = waterSurface[index];
                  if (waterfall[index]) {
                     terrainSurface[index] = baseTerrainSurface[index];
                     continue;
	                  } else if (directLineWater[index]) {
	                     surface = WaterSurfaceResolver.directLineRiverTerrainSurface(cellWaterSurface);
	                  } else if (ocean[index]) {
	                     surface = this.fullWaterResolver.resolveOceanTerrainSurface(
                           worldX, worldZ, cellWaterSurface, previewResolutionMeters
                        );
                  } else {
                     surface = simpleInlandWaterTerrainSurface(cellWaterSurface);
                  }

                  WaterSurfaceResolver.WaterColumnData column = normalizeLodWaterColumn(
                     new WaterSurfaceResolver.WaterColumnData(true, ocean[index], surface, cellWaterSurface)
                  );
                  terrainSurface[index] = column.terrainSurface();
                  waterSurface[index] = column.waterSurface();
               } else {
                  waterSurface[index] = terrainSurface[index];
               }
            }
         }

         applyInlandDepthProfile(
            terrainSurface,
            waterSurface,
            hasWater,
            ocean,
            directLineWater,
            waterfall,
            demFlowWater,
            detailedInlandWater,
            worldXs,
            worldZs,
            lodSizePoints,
            cellSize,
            useDetailedWaterResolver
         );
         applyOceanLodDepthCompression(terrainSurface, waterSurface, ocean, oceanCoastDistance);
         markLodWaterfallProtection(
            waterfall,
            waterfallProtection,
            lodSizePoints,
            blendCells(this.fullWaterResolver.inlandFlowParameters().waterfallProtectionBlocks(), Math.max(1, cellSize))
         );
         this.prepareLodAdaptiveBlendRadii(
            adaptiveBlendRadius,
            flowCorrection,
            waterfallProtection,
            baseTerrainSurface,
            waterSurface,
            hasWater,
            ocean,
            waterfall,
            lodSizePoints
         );
         int inlandBlendCells = maximumBlendCells(adaptiveBlendRadius, hasWater, ocean, waterfall, cellSize);
         if (inlandBlendCells > 0) {
            boolean[] inlandMask = new boolean[area];
            boolean[] protectedInlandWater = new boolean[area];

            for (int i = 0; i < area; i++) {
               if (hasWater[i] && !ocean[i] && !directLineWater[i] && !waterfall[i] && !waterfallProtection[i]) {
                  inlandMask[i] = true;
               }
               protectedInlandWater[i] = !flowCorrection[i]
                  || waterfallProtection[i]
                  || detailedInlandWater != null && detailedInlandWater[i];
            }

            if (this.settings.shorelineBlendCliffLimit()) {
               applyInlandShorelineFloorRamp(
                  terrainSurface,
                  waterSurface,
                  inlandMask,
                  protectedInlandWater,
                  lodSizePoints,
                  inlandBlendCells
               );
            }

            this.applyAdaptiveShorelineBlend(
               terrainSurface,
               baseTerrainSurface,
               waterSurface,
               hasWater,
               ocean,
               waterfall,
               flowCorrection,
               adaptiveBlendRadius,
               waterfallProtection,
               lodSizePoints,
               cellSize
            );
         }

         this.applyExperimentalOceanDepthCap(terrainSurface, waterSurface, ocean);
         return new DhLodWaterResolver.AreaResult(terrainSurface, waterSurface, hasWater, ocean);
      }
   }

   static int simpleInlandWaterTerrainSurface(int waterSurface) {
      return waterSurface - INLAND_SIMPLE_WATER_DEPTH;
   }

   static int simpleInlandWaterDepthForDistance(int distanceCells) {
      return INLAND_SIMPLE_WATER_DEPTH;
   }

   static boolean classifyWaterCellAsOcean(
      boolean overtureOcean,
      boolean strictOverture,
      TellusLandMaskSource.LandMaskSample landMaskSample,
      int surface,
      int coverClass,
      int seaLevel
   ) {
      return OceanClassification.isOcean(overtureOcean, strictOverture, landMaskSample, surface, coverClass, seaLevel);
   }

   static boolean classifyWaterCellAsOcean(
      boolean overtureOcean,
      boolean strictOverture,
      TellusLandMaskSource.LandMaskSample landMaskSample,
      int surface,
      int coverClass,
      int seaLevel,
      boolean mapterhornLandOverride
   ) {
      return OceanClassification.isOcean(
         overtureOcean, strictOverture, landMaskSample, surface, coverClass, seaLevel, mapterhornLandOverride
      );
   }

   static int oceanFloorTransitionBlocks() {
      return OCEAN_FLOOR_TRANSITION_BLOCKS;
   }

   static boolean shouldClassifyWaterCellAsOcean(boolean osmWaterEnabled, boolean overtureWater, boolean sampledOceanCell) {
      return !osmWaterEnabled || overtureWater || sampledOceanCell;
   }

   private void assignWaterSurfaces(
      int[] waterSurface,
      int[] baseTerrainSurface,
      int[] minimumWaterSurface,
      boolean[] hasWater,
	      boolean[] ocean,
	      boolean[] lineWater,
	      boolean[] areaWater,
	      boolean[] flowingWater,
	      boolean[] directLineWater,
	      boolean[] waterfall,
	      boolean[] demFlowWater,
	      boolean[] flowCorrection,
	      int[] adaptiveBlendRadius,
	      boolean[] waterfallProtection,
	      long[] waterBodyKeys,
	      int[] waterBodySurfaceHints,
	      long sourceGeneration,
	      int lodSizePoints,
	      int seaLevel,
	      int cellSize
   ) {
      int area = lodSizePoints * lodSizePoints;
      boolean[] visited = new boolean[area];
      int[] queue = new int[area];
      int[] componentCells = new int[area];
      int[] polygonCells = new int[area];
      int[] borderHeights = new int[area];
      int[] componentSurfaceHints = new int[area];
      int[] waterfallTop = new int[area];

      for (int start = 0; start < area; start++) {
         if (!hasWater[start]) {
            waterSurface[start] = baseTerrainSurface[start];
         } else if (ocean[start]) {
            waterSurface[start] = resolvedOceanWaterSurface(waterSurface[start], minimumWaterSurface[start]);
            visited[start] = true;
         } else if (!visited[start]) {
            int queueHead = 0;
            int queueTail = 0;
            int componentCount = 0;
            int borderCount = 0;
            int minBorderHeight = Integer.MAX_VALUE;
            int minComponentTerrain = Integer.MAX_VALUE;
            int maxComponentTerrain = Integer.MIN_VALUE;
            int lineWaterCount = 0;
            int areaWaterCount = 0;
            int flowingWaterCount = 0;
            int minComponentX = Integer.MAX_VALUE;
            int maxComponentX = Integer.MIN_VALUE;
            int minComponentZ = Integer.MAX_VALUE;
            int maxComponentZ = Integer.MIN_VALUE;
            long componentWaterBodyKey = 0L;
            int componentWaterSurfaceHint = Integer.MIN_VALUE;
            int componentSurfaceHintCount = 0;
            int lastComponentSurfaceHint = Integer.MIN_VALUE;
            long componentTerrainSum = 0L;
            int minSurface = minimumWaterSurface[start];
            visited[start] = true;
            queue[queueTail++] = start;

            while (queueHead < queueTail) {
               int index = queue[queueHead++];
               componentCells[componentCount++] = index;
               int componentTerrain = baseTerrainSurface[index];
               minComponentTerrain = Math.min(minComponentTerrain, componentTerrain);
               maxComponentTerrain = Math.max(maxComponentTerrain, componentTerrain);
               componentTerrainSum += componentTerrain;
               minSurface = Math.max(minSurface, minimumWaterSurface[index]);
               if (lineWater[index]) {
                  lineWaterCount++;
               }
               if (areaWater[index] && !waterfallProtection[index]) {
                  polygonCells[areaWaterCount++] = index;
               }
               if (flowingWater[index]) {
                  flowingWaterCount++;
               }

               long waterBodyKey = waterBodyKeys[index];
               if (waterBodyKey != 0L
                  && (componentWaterBodyKey == 0L || Long.compareUnsigned(waterBodyKey, componentWaterBodyKey) < 0)) {
                  componentWaterBodyKey = waterBodyKey;
               }

               int waterBodySurfaceHint = waterBodySurfaceHints[index];
               if (waterBodySurfaceHint != Integer.MIN_VALUE && waterBodySurfaceHint != lastComponentSurfaceHint) {
                  componentSurfaceHints[componentSurfaceHintCount++] = waterBodySurfaceHint;
                  lastComponentSurfaceHint = waterBodySurfaceHint;
               }

               int x = index % lodSizePoints;
               int z = index / lodSizePoints;
               minComponentX = Math.min(minComponentX, x);
               maxComponentX = Math.max(maxComponentX, x);
               minComponentZ = Math.min(minComponentZ, z);
               maxComponentZ = Math.max(maxComponentZ, z);

               for (int n = 0; n < NEIGHBOR_OFFSETS.length; n += 2) {
                  int nx = x + NEIGHBOR_OFFSETS[n];
                  int nz = z + NEIGHBOR_OFFSETS[n + 1];
                  if (nx >= 0 && nz >= 0 && nx < lodSizePoints && nz < lodSizePoints) {
                     int neighbor = nz * lodSizePoints + nx;
                     if (hasWater[neighbor] && !ocean[neighbor]) {
                        if (!visited[neighbor]) {
                           visited[neighbor] = true;
                           queue[queueTail++] = neighbor;
                        }
                     } else {
                        // Treat ocean neighbors as borders at their resolved surface so isolated inland-water
                        // cells embedded in ocean (coastal misclassification) inherit a flat surface
                        // instead of falling through to per-cell bathymetry depth.
                        int borderHeight = ocean[neighbor] ? waterSurface[neighbor] : baseTerrainSurface[neighbor];
                        minBorderHeight = Math.min(minBorderHeight, borderHeight);
                        if (borderCount < borderHeights.length) {
                           borderHeights[borderCount++] = borderHeight;
                        }
                     }
                  }
               }
            }

            boolean componentBelowSea = maxComponentTerrain != Integer.MIN_VALUE && maxComponentTerrain <= seaLevel;
            int noBorderSurface = fallbackInlandComponentSurface(componentCount, componentTerrainSum, maxComponentTerrain, seaLevel);
            int componentSurface = borderCount > 0 ? percentile(borderHeights, borderCount, 0.1) : noBorderSurface;
	            boolean flowDominated = WaterSurfaceResolver.isFlowDominatedWaterComponent(componentCount, flowingWaterCount);
	            boolean lineOnlyRiver = lineWaterCount > 0 && areaWaterCount == 0;
	            if (lineOnlyRiver) {
	               for (int i = 0; i < componentCount; i++) {
	                  int cell = componentCells[i];
	                  directLineWater[cell] = true;
	                  demFlowWater[cell] = false;
	                  waterSurface[cell] = WaterSurfaceResolver.directLineRiverWaterSurface(baseTerrainSurface[cell]);
	               }
	            } else if (!flowDominated) {
	               componentWaterSurfaceHint = aggregateFeatureSurfaceHints(
	                  componentSurfaceHints, componentSurfaceHintCount
	               );
	               if (componentWaterSurfaceHint != Integer.MIN_VALUE) {
	                  componentWaterSurfaceHint = this.fullWaterResolver.resolveFastStableLakeSurface(
	                     componentWaterBodyKey, componentWaterSurfaceHint, sourceGeneration
	                  );
	               }
	               componentSurface = resolvedInlandComponentSurface(
	                  componentSurface, componentWaterSurfaceHint, minSurface, minBorderHeight, componentBelowSea, seaLevel
	               );
               for (int i = 0; i < componentCount; i++) {
                  int cell = componentCells[i];
                  directLineWater[cell] = waterfallProtection[cell]
                     || WaterSurfaceResolver.shouldUseDirectLineWaterCell(lineWater[cell], areaWater[cell]);
                  waterSurface[cell] = directLineWater[cell]
                     ? WaterSurfaceResolver.directLineRiverWaterSurface(baseTerrainSurface[cell])
                     : componentSurface;
               }
            } else {
               for (int i = 0; i < componentCount; i++) {
                  int cell = componentCells[i];
                  if (waterfallProtection[cell]
                     || WaterSurfaceResolver.shouldUseDirectLineWaterCell(lineWater[cell], areaWater[cell])) {
                     directLineWater[cell] = true;
                     demFlowWater[cell] = false;
                     waterSurface[cell] = WaterSurfaceResolver.directLineRiverWaterSurface(baseTerrainSurface[cell]);
                     continue;
                  }
                  demFlowWater[cell] = true;
                  int x = cell % lodSizePoints;
                  int z = cell / lodSizePoints;
                  int ceiling = baseTerrainSurface[cell];
                  for (int offset = 0; offset < NEIGHBOR_OFFSETS.length; offset += 2) {
                     int nx = x + NEIGHBOR_OFFSETS[offset];
                     int nz = z + NEIGHBOR_OFFSETS[offset + 1];
                     if (nx < 0 || nz < 0 || nx >= lodSizePoints || nz >= lodSizePoints) {
                        continue;
                     }
                     int neighbor = nz * lodSizePoints + nx;
                     if (ocean[neighbor]) {
                        ceiling = Math.min(ceiling, waterSurface[neighbor]);
                     } else if (!hasWater[neighbor]) {
                        ceiling = Math.min(ceiling, baseTerrainSurface[neighbor]);
                     }
                  }
                  directLineWater[cell] = false;
                  waterSurface[cell] = ceiling;
               }
               int componentWidthCells = Math.max(
                  1,
                  Math.min(maxComponentX - minComponentX + 1, maxComponentZ - minComponentZ + 1)
               );
               InlandWaterFlowAnalyzer.analyze(
                  lodSizePoints,
                  polygonCells,
                  areaWaterCount,
                  baseTerrainSurface,
                  waterSurface,
                  ocean,
                  Math.max(1, cellSize),
                  componentWidthCells * Math.max(1, cellSize),
                  this.fullWaterResolver.inlandFlowParameters(),
                  waterSurface,
                  hasWater,
                  waterfall,
                  waterfallTop,
                  flowCorrection,
                  adaptiveBlendRadius,
                  waterfallProtection,
                  this.flowWorkspace.get()
               );
               for (int i = 0; i < areaWaterCount; i++) {
                  int cell = polygonCells[i];
                  if (waterfall[cell]) {
                     // Fast LODs have no fluid simulation, so retain a water
                     // column from the preserved DEM floor to the upstream top.
                     hasWater[cell] = true;
                  }
               }
            }
         }
      }
   }

   static int stableInlandComponentSurface(int fallbackSurface, int featureSurfaceHint) {
      return featureSurfaceHint == Integer.MIN_VALUE ? fallbackSurface : featureSurfaceHint;
   }

   static int resolvedOceanWaterSurface(int localOceanSurface, int minimumWaterSurface) {
      return Math.max(localOceanSurface, minimumWaterSurface);
   }

   static int aggregateFeatureSurfaceHints(int[] surfaceHints, int hintCount) {
      return hintCount <= 0 ? Integer.MIN_VALUE : percentile(surfaceHints, hintCount, 0.25);
   }

   static int resolvedInlandComponentSurface(
      int fallbackSurface,
      int featureSurfaceHint,
      int minimumSurface,
      int minimumBorderHeight,
      boolean componentBelowSea,
      int seaLevel
   ) {
      int surface = stableInlandComponentSurface(fallbackSurface, featureSurfaceHint);
      int borderCap = featureSurfaceHint == Integer.MIN_VALUE ? minimumBorderHeight : Integer.MAX_VALUE;
      return cappedInlandComponentSurface(surface, minimumSurface, borderCap, componentBelowSea, seaLevel);
   }

   static int cappedInlandComponentSurface(
      int componentSurface, int minSurface, int minBorderHeight, boolean componentBelowSea, int seaLevel
   ) {
      if (minSurface != Integer.MIN_VALUE) {
         componentSurface = Math.max(componentSurface, minSurface);
      }

      // If every cell in the component sits at or below sea level, the component is almost
      // certainly misclassified ocean (or a coastal water body that should drain to ocean),
      // not a mountain lake. Cap the surface at seaLevel so tall land borders do not lift it.
      if (componentBelowSea) {
         componentSurface = Math.min(componentSurface, seaLevel);
      }

      return WaterSurfaceResolver.capInlandLakeWaterSurface(componentSurface, minBorderHeight);
   }

   static boolean removeOverBudgetInlandWater(
      int[] waterSurface, int[] baseTerrainSurface, boolean[] hasWater, boolean[] ocean, boolean[] lineWater
   ) {
      return removeOverBudgetInlandWater(waterSurface, baseTerrainSurface, null, hasWater, ocean, lineWater, null);
   }

   private static boolean removeOverBudgetInlandWater(
      int[] waterSurface,
      int[] baseTerrainSurface,
      int[] terrainSurface,
      boolean[] hasWater,
      boolean[] ocean,
      boolean[] lineWater,
      boolean[] protectedWater
   ) {
      boolean changed = false;

	      for (int index = 0; index < hasWater.length; index++) {
	         if (hasWater[index] && !ocean[index] && (protectedWater == null || !protectedWater[index])) {
		            boolean rejected = lineWater[index]
		               ? waterSurface[index] > baseTerrainSurface[index]
	               : baseTerrainSurface[index] - waterSurface[index] > WaterSurfaceResolver.lakeMaxTerrainCut();
	            if (rejected) {
	               hasWater[index] = false;
	               waterSurface[index] = baseTerrainSurface[index];
               if (terrainSurface != null) {
                  terrainSurface[index] = baseTerrainSurface[index];
               }

               changed = true;
            }
         }
      }

      return changed;
   }

   private static boolean removeOverBudgetNonFlowInlandWater(
      int[] waterSurface,
      int[] baseTerrainSurface,
      boolean[] hasWater,
      boolean[] ocean,
      boolean[] directLineWater,
      boolean[] protectedWater,
      boolean[] demFlowWater
   ) {
      boolean[] protectedOrFlow = Arrays.copyOf(protectedWater, protectedWater.length);
      for (int index = 0; index < protectedOrFlow.length; index++) {
         protectedOrFlow[index] |= demFlowWater[index];
      }
      return removeOverBudgetInlandWater(
         waterSurface, baseTerrainSurface, null, hasWater, ocean, directLineWater, protectedOrFlow
      );
   }

   static boolean removeSteepInlandWater(
      int[] waterSurface,
      int[] baseTerrainSurface,
      boolean[] hasWater,
      boolean[] ocean,
      boolean[] lineWater,
      int lodSizePoints,
      int cellSize,
      int maxSlopePerFourBlocks
   ) {
      return removeSteepInlandWater(
         waterSurface,
         baseTerrainSurface,
         hasWater,
         ocean,
         lineWater,
         null,
         lodSizePoints,
         cellSize,
         maxSlopePerFourBlocks
      );
   }

   private static boolean removeSteepNonFlowInlandWater(
      int[] waterSurface,
      int[] baseTerrainSurface,
      boolean[] hasWater,
      boolean[] ocean,
      boolean[] lineWater,
      boolean[] protectedWater,
      boolean[] demFlowWater,
      int lodSizePoints,
      int cellSize,
      int maxSlopePerFourBlocks
   ) {
      boolean[] protectedOrFlow = Arrays.copyOf(protectedWater, protectedWater.length);
      for (int index = 0; index < protectedOrFlow.length; index++) {
         protectedOrFlow[index] |= demFlowWater[index];
      }
      return removeSteepInlandWater(
         waterSurface,
         baseTerrainSurface,
         hasWater,
         ocean,
         lineWater,
         protectedOrFlow,
         lodSizePoints,
         cellSize,
         maxSlopePerFourBlocks
      );
   }

   private static boolean removeSteepInlandWater(
      int[] waterSurface,
      int[] baseTerrainSurface,
      boolean[] hasWater,
      boolean[] ocean,
      boolean[] lineWater,
      boolean[] protectedWater,
      int lodSizePoints,
      int cellSize,
      int maxSlopePerFourBlocks
   ) {
      if (maxSlopePerFourBlocks <= 0 || lodSizePoints <= 1) {
         return false;
      }

      boolean changed = false;
      boolean[] rejected = new boolean[hasWater.length];

      for (int z = 0; z < lodSizePoints; z++) {
         int row = z * lodSizePoints;
         for (int x = 0; x < lodSizePoints; x++) {
            int index = row + x;
            if (!hasWater[index]
               || ocean[index]
               || lineWater[index]
               || protectedWater != null && protectedWater[index]) {
               continue;
            }

            int center = baseTerrainSurface[index];
            int maxDifference = 0;
            for (int offsetIndex = 0; offsetIndex < NEIGHBOR_OFFSETS.length; offsetIndex += 2) {
               int nx = x + NEIGHBOR_OFFSETS[offsetIndex];
               int nz = z + NEIGHBOR_OFFSETS[offsetIndex + 1];
               if (nx >= 0 && nz >= 0 && nx < lodSizePoints && nz < lodSizePoints) {
                  int neighbor = nz * lodSizePoints + nx;
                  maxDifference = Math.max(maxDifference, Math.abs(center - baseTerrainSurface[neighbor]));
               }
            }

            if (isSlopeTooSteep(maxDifference, cellSize, maxSlopePerFourBlocks)) {
               rejected[index] = true;
            }
         }
      }

      for (int index = 0; index < rejected.length; index++) {
         if (rejected[index]) {
            hasWater[index] = false;
            waterSurface[index] = baseTerrainSurface[index];
            changed = true;
         }
      }

      return changed;
   }

   static boolean isSlopeTooSteep(int heightDifference, int cellSize, int maxSlopePerFourBlocks) {
      return (long)Math.max(0, heightDifference) * 4L
         > (long)Math.max(0, maxSlopePerFourBlocks) * Math.max(1, cellSize);
   }

   static int fallbackInlandComponentSurface(int componentCount, long componentTerrainSum, int maxComponentTerrain, int seaLevel) {
      if (maxComponentTerrain != Integer.MIN_VALUE && maxComponentTerrain <= seaLevel) {
         return seaLevel;
      } else if (componentCount <= 0) {
         return seaLevel;
      } else {
         return (int)Math.round((double)componentTerrainSum / componentCount);
      }
   }

   private void applyInlandOceanSurfaceTransition(
      int[] waterSurface,
      boolean[] hasWater,
      boolean[] ocean,
      boolean[] protectedWater,
      boolean[] demFlowWater,
      int lodSizePoints,
      int cellSize
   ) {
      int transitionCells = blendCells(INLAND_OCEAN_TRANSITION_BLOCKS, Math.max(1, cellSize));
      if (transitionCells <= 0) {
         return;
      }

      int area = lodSizePoints * lodSizePoints;
      int[] distance = new int[area];
      int[] nearestOceanSurface = new int[area];
      int[] queue = new int[area];
      Arrays.fill(distance, -1);
      int queueHead = 0;
      int queueTail = 0;

      for (int index = 0; index < area; index++) {
         if (hasWater[index] && !ocean[index]) {
            int x = index % lodSizePoints;
            int z = index / lodSizePoints;
            int adjacentOceanSurface = Integer.MAX_VALUE;

            for (int n = 0; n < NEIGHBOR_OFFSETS.length; n += 2) {
               int nx = x + NEIGHBOR_OFFSETS[n];
               int nz = z + NEIGHBOR_OFFSETS[n + 1];
               if (nx >= 0 && nz >= 0 && nx < lodSizePoints && nz < lodSizePoints) {
                  int neighbor = nz * lodSizePoints + nx;
                  if (hasWater[neighbor] && ocean[neighbor]) {
                     adjacentOceanSurface = Math.min(adjacentOceanSurface, waterSurface[neighbor]);
                  }
               }
            }

            if (adjacentOceanSurface != Integer.MAX_VALUE) {
               distance[index] = 0;
               nearestOceanSurface[index] = adjacentOceanSurface;
               queue[queueTail++] = index;
            }
         }
      }

      while (queueHead < queueTail) {
         int packedIndex = queue[queueHead++];
         int currentDistance = distance[packedIndex];
         if (currentDistance < transitionCells) {
            int x = packedIndex % lodSizePoints;
            int z = packedIndex / lodSizePoints;

            for (int n = 0; n < NEIGHBOR_OFFSETS.length; n += 2) {
               int nx = x + NEIGHBOR_OFFSETS[n];
               int nz = z + NEIGHBOR_OFFSETS[n + 1];
               if (nx >= 0 && nz >= 0 && nx < lodSizePoints && nz < lodSizePoints) {
                  int neighbor = nz * lodSizePoints + nx;
                  if (hasWater[neighbor] && !ocean[neighbor] && distance[neighbor] == -1) {
                     distance[neighbor] = currentDistance + 1;
                     nearestOceanSurface[neighbor] = nearestOceanSurface[packedIndex];
                     queue[queueTail++] = neighbor;
                  }
               }
            }
         }
      }

      for (int index = 0; index < area; index++) {
         int currentDistance = distance[index];
         if (!protectedWater[index]
            && !demFlowWater[index]
            && currentDistance >= 0
            && currentDistance <= transitionCells) {
            double t = smoothstep(currentDistance / (double)Math.max(1, transitionCells));
            int currentSurface = waterSurface[index];
            int oceanSurface = nearestOceanSurface[index];
            waterSurface[index] = WaterSurfaceResolver.transitionedWaterSurface(currentSurface, oceanSurface, t);
         }
      }
   }

   private void applyOceanLodDepthCompression(
      int[] terrainSurface, int[] waterSurface, boolean[] ocean, int[] coastDistance
   ) {
      if (!OCEAN_LOD_DEPTH_COMPRESSION_ENABLED) {
         return;
      }
      int transitionBlocks = OceanFloorProfile.transitionBlocksForScale(
         OCEAN_FLOOR_TRANSITION_BLOCKS,
         this.settings.worldScale()
      );
      for (int index = 0; index < terrainSurface.length; index++) {
         if (ocean[index] && coastDistance[index] >= transitionBlocks) {
            terrainSurface[index] = displayOceanLodTerrainSurface(terrainSurface[index], waterSurface[index]);
         }
      }
   }

   private void applyExperimentalOceanDepthCap(int[] terrainSurface, int[] waterSurface, boolean[] ocean) {
      if (!this.settings.experimentalIncreaseHeight()) {
         return;
      }

      for (int index = 0; index < terrainSurface.length; index++) {
         if (ocean[index]) {
            int maxFloor = waterSurface[index] - 1;
            int minFloor = EarthGeneratorSettings.resolveHeightLimits(this.settings).minY()
               + WaterSurfaceResolver.oceanFloorSupportBlocks();
            terrainSurface[index] = Mth.clamp(terrainSurface[index], minFloor, maxFloor);
         }
      }
   }

   static int displayOceanLodTerrainSurface(int terrainSurface, int waterSurface) {
      return OCEAN_LOD_DEPTH_COMPRESSION_ENABLED
         ? compressedOceanLodTerrainSurface(terrainSurface, waterSurface)
         : terrainSurface;
   }

   private static int compressedOceanLodTerrainSurface(int terrainSurface, int waterSurface) {
      int depth = Math.max(1, waterSurface - terrainSurface);
      if (depth <= OCEAN_LOD_LINEAR_DEPTH) {
         return terrainSurface;
      } else {
         double compressed = OCEAN_LOD_LINEAR_DEPTH
            + Math.log1p((depth - OCEAN_LOD_LINEAR_DEPTH) / (double)OCEAN_LOD_DEPTH_COMPRESSION_BLOCKS)
               * OCEAN_LOD_DEPTH_COMPRESSION_BLOCKS;
         int compressedDepth = Math.max(OCEAN_LOD_LINEAR_DEPTH, (int)Math.round(compressed));
         return waterSurface - compressedDepth;
      }
   }

   private DhLodWaterResolver.RasterizedWaterArea rasterizedWaterArea(int baseX, int baseZ, int lodSizePoints, int cellSize) {
      int area = lodSizePoints * lodSizePoints;
      if (!this.settings.enableWater() || area == 0) {
         return DhLodWaterResolver.RasterizedWaterArea.dry(area);
      } else {
         long generation = this.cacheGeneration.get();
         DhLodWaterResolver.AreaKey key = new DhLodWaterResolver.AreaKey(baseX, baseZ, lodSizePoints, cellSize, generation);
         DhLodWaterResolver.RasterizedWaterArea cached = this.rasterCache.getIfPresent(key);
         if (cached != null) {
            return cached;
         } else {
            try {
               DhLodWaterResolver.RasterizedWaterArea built = this.rasterCache.get(
                  key, () -> this.buildRasterizedWaterArea(baseX, baseZ, lodSizePoints, cellSize)
               );
               if (built.incomplete()) {
                  this.rasterCache.invalidate(key);
               }
               return built;
            } catch (ExecutionException | UncheckedExecutionException error) {
               Throwable cause = error.getCause();
               if (cause instanceof RuntimeException runtimeException) {
                  throw runtimeException;
               } else if (cause instanceof Error fatal) {
                  throw fatal;
               } else {
                  throw new RuntimeException("Failed to rasterize DH water area", cause);
               }
            }
         }
      }
   }

   private DhLodWaterResolver.RasterizedWaterArea buildRasterizedWaterArea(int baseX, int baseZ, int lodSizePoints, int cellSize) {
      int area = lodSizePoints * lodSizePoints;
      long sourceGeneration = this.fullWaterResolver.currentCacheGeneration();
      int cellOffset = cellSize >> 1;
      int halfCell = cellSize >> 1;
      int minBlockX = baseX + cellOffset - halfCell;
      int minBlockZ = baseZ + cellOffset - halfCell;
      int maxBlockX = baseX + (lodSizePoints - 1) * cellSize + cellOffset + halfCell - 1;
      int maxBlockZ = baseZ + (lodSizePoints - 1) * cellSize + cellOffset + halfCell - 1;
      double worldScale = this.settings.worldScale();
      long queryStartNs = OsmPerf.now();
      OsmQueryMode queryMode = this.settings.distantHorizonsOsmNonBlockingFetch() ? OsmQueryMode.NON_BLOCKING : OsmQueryMode.BLOCKING;
      TellusOsmWaterSource.WaterQueryResult result = this.osmWaterSource
         .waterForAreaWithStatus(
            minBlockX,
            minBlockZ,
            maxBlockX,
            maxBlockZ,
            this.projection,
            WaterfallNoCarveZone.queryMarginBlocks(worldScale),
            queryMode,
            waterQueryTileBudget(cellSize)
         );
      if (shouldRetryPendingCoverage(result.coverageStatus(), queryMode)) {
         result = this.osmWaterSource.waterForAreaWithStatus(
            minBlockX,
            minBlockZ,
            maxBlockX,
            maxBlockZ,
            this.projection,
            WaterfallNoCarveZone.queryMarginBlocks(worldScale),
            OsmQueryMode.BLOCKING,
            waterQueryTileBudget(cellSize)
         );
      }
      OsmPerf.recordWaterQuery(OsmPerf.elapsedSince(queryStartNs), result.features().size());
      if (!result.complete()) {
         throw new OceanCoverageUnavailableException(result.coverageStatus(), minBlockX, minBlockZ);
      }
      List<OsmWaterFeature> features = result.features();
      boolean incomplete = false;
      if (features.isEmpty()) {
	         return incomplete
	            ? new DhLodWaterResolver.RasterizedWaterArea(
	               new boolean[area],
	               new boolean[area],
	               new boolean[area],
	               new boolean[area],
	               new boolean[area],
	               new boolean[area],
	               new boolean[area],
	               new long[area],
	               emptySurfaceHints(area),
	               sourceGeneration,
	               true
	            )
	            : DhLodWaterResolver.RasterizedWaterArea.dry(area);
      } else {
         int[] wetSampleMask = new int[area];
         boolean[] oceanSample = new boolean[area];
         boolean[] lineSample = new boolean[area];
         boolean[] areaSample = new boolean[area];
         boolean[] flowingSample = new boolean[area];
         boolean[] waterfallNoCarve = new boolean[area];
         long[] waterBodyKeys = new long[area];
         int[] waterBodySurfaceHints = emptySurfaceHints(area);
         int[] sampleOffsets = sampleOffsetsForCellSize(cellSize);
         int maxSampleOffset = maxSampleOffset(sampleOffsets);
         WaterfallNoCarveZone.markRegularCellGrid(
            waterfallNoCarve, baseX, baseZ, lodSizePoints, cellSize, features, this.projection
         );

         for (OsmWaterFeature feature : features) {
            throwIfCancelled();
            if (!feature.oceanHint() && !WaterfallNoCarveZone.isMarker(feature)) {
               this.rasterizeFeature(
               feature,
               baseX,
               baseZ,
               lodSizePoints,
               cellSize,
               cellOffset,
               sampleOffsets,
               maxSampleOffset,
               wetSampleMask,
	               oceanSample,
	               lineSample,
	               areaSample,
	               flowingSample,
	               waterBodyKeys,
               waterBodySurfaceHints
               );
            }
         }

         int totalSamples = sampleOffsets.length / 2;
	         boolean[] renderWater = new boolean[area];
	         boolean[] ocean = new boolean[area];
	         boolean[] lineWater = new boolean[area];
	         boolean[] areaWater = new boolean[area];
	         boolean[] flowingWater = new boolean[area];

         for (int i = 0; i < area; i++) {
            int wetMask = wetSampleMask[i];
            if (wetMask != 0) {
	               boolean render = shouldRenderExactWaterFootprint(Integer.bitCount(wetMask), totalSamples, oceanSample[i], lineSample[i]);
	               renderWater[i] = render;
	               ocean[i] = render && oceanSample[i];
	               lineWater[i] = render && lineSample[i];
	               areaWater[i] = render && areaSample[i];
	               flowingWater[i] = render && flowingSample[i];
	            }
	         }

	         int riverGapCells = WaterSurfaceResolver.riverConnectGapBlocks() / Math.max(1, cellSize);
	         WaterSurfaceResolver.repairFlowingWaterGaps(
	            renderWater, ocean, lineWater, flowingWater, lodSizePoints, riverGapCells
	         );

	         return new DhLodWaterResolver.RasterizedWaterArea(
	            renderWater, ocean, oceanSample, lineWater, areaWater, flowingWater, waterfallNoCarve,
	            waterBodyKeys, waterBodySurfaceHints, sourceGeneration, incomplete
	         );
	      }
	   }

   private void rasterizeFeature(
      OsmWaterFeature feature,
      int baseX,
      int baseZ,
      int lodSizePoints,
      int cellSize,
      int cellOffset,
      int[] sampleOffsets,
      int maxSampleOffset,
      int[] wetSampleMask,
      boolean[] oceanSample,
      boolean[] lineSample,
      boolean[] areaSample,
      boolean[] flowingSample,
      long[] waterBodyKeys,
      int[] waterBodySurfaceHints
   ) {
      if (feature.crossesWorldSeam(this.projection)) {
         return;
      }
	      boolean lineWaterFeature = WaterSurfaceResolver.isLineWaterGeometry(feature);
	      boolean flowingWaterFeature = isFlowingWaterFeature(feature);
      long waterBodyKey = stableOsmWaterBodyKey(feature);
      int waterBodySurfaceHint = waterBodyKey == 0L ? Integer.MIN_VALUE : this.fullWaterResolver.estimateFastLakeSurface(feature);
      double lonA = this.projection.lonToBlockX(feature.minLon());
      double lonB = this.projection.lonToBlockX(feature.maxLon());
      double minWorldX = Math.min(lonA, lonB);
      double maxWorldX = Math.max(lonA, lonB);
      double minLatWorldZ = this.projection.latToBlockZ(feature.minLat());
      double maxLatWorldZ = this.projection.latToBlockZ(feature.maxLat());
      double minWorldZ = Math.min(minLatWorldZ, maxLatWorldZ);
      double maxWorldZ = Math.max(minLatWorldZ, maxLatWorldZ);
      int searchRadius = Math.max(cellSize >> 1, maxSampleOffset);
      int minCellX = cellIndexForWorld(minWorldX - searchRadius, baseX, cellOffset, cellSize, lodSizePoints);
      int maxCellX = cellIndexForWorld(maxWorldX + searchRadius, baseX, cellOffset, cellSize, lodSizePoints);
      int minCellZ = cellIndexForWorld(minWorldZ - searchRadius, baseZ, cellOffset, cellSize, lodSizePoints);
      int maxCellZ = cellIndexForWorld(maxWorldZ + searchRadius, baseZ, cellOffset, cellSize, lodSizePoints);
      if (maxCellX < minCellX || maxCellZ < minCellZ) {
         return;
      }

      for (int localZ = minCellZ; localZ <= maxCellZ; localZ++) {
         throwIfCancelled();
         int worldZ = baseZ + localZ * cellSize + cellOffset;
         int row = localZ * lodSizePoints;

         for (int localX = minCellX; localX <= maxCellX; localX++) {
            int worldX = baseX + localX * cellSize + cellOffset;
            int index = row + localX;
            int mask = wetSampleMask[index];

            for (int offsetIndex = 0, sampleBit = 1; offsetIndex < sampleOffsets.length; offsetIndex += 2, sampleBit <<= 1) {
               int sampleX = worldX + sampleOffsets[offsetIndex];
               int sampleZ = worldZ + sampleOffsets[offsetIndex + 1];
               if (feature.containsBlock(sampleX, sampleZ, this.projection)) {
                  mask |= sampleBit;
                  if (feature.oceanHint()) {
                     oceanSample[index] = true;
                  }

	                  if (lineWaterFeature) {
	                     lineSample[index] = true;
	                  } else {
	                     areaSample[index] = true;
	                  }
	                  if (flowingWaterFeature) {
	                     flowingSample[index] = true;
	                  }

                  if (waterBodyKey != 0L) {
                     long existingKey = waterBodyKeys[index];
                     if (existingKey == 0L || Long.compareUnsigned(waterBodyKey, existingKey) < 0) {
                        waterBodyKeys[index] = waterBodyKey;
                        waterBodySurfaceHints[index] = waterBodySurfaceHint;
                     } else if (existingKey == waterBodyKey && waterBodySurfaceHint != Integer.MIN_VALUE) {
                        waterBodySurfaceHints[index] = waterBodySurfaceHints[index] == Integer.MIN_VALUE
                           ? waterBodySurfaceHint
                           : Math.min(waterBodySurfaceHints[index], waterBodySurfaceHint);
                     }
                  }
               }
            }

            wetSampleMask[index] = mask;
         }
      }
   }

   static boolean isFlowingWaterFeature(OsmWaterFeature feature) {
      return feature.flowingWater();
   }

   static long stableOsmWaterBodyKey(OsmWaterFeature feature) {
      return feature.flowingWater() || feature.oceanHint() ? 0L : feature.featureId();
   }

   static void applyInlandDepthProfile(
      int[] terrainSurface,
      int[] waterSurface,
      boolean[] hasWater,
      boolean[] ocean,
      boolean[] directLineWater,
      boolean[] waterfall,
      boolean[] riverWater,
      boolean[] detailedInlandWater,
      int[] worldXs,
      int[] worldZs,
      int lodSizePoints,
      int cellSize,
      boolean detailed
   ) {
      int area = lodSizePoints * lodSizePoints;
      if (!detailed) {
         for (int index = 0; index < area; index++) {
            if (isProfiledInlandWater(index, hasWater, ocean, directLineWater) && !waterfall[index]) {
               terrainSurface[index] = waterSurface[index] - simpleInlandWaterDepthForDistance(0);
            }
         }
         return;
      }

      if (detailedInlandWater == null) {
         throw new IllegalArgumentException("Detailed LOD water requires a reusable inland mask");
      }
      Arrays.fill(detailedInlandWater, false);
      boolean hasDetailedLake = false;
      for (int index = 0; index < area; index++) {
         boolean profiledWater = isProfiledInlandWater(index, hasWater, ocean, directLineWater)
            && !waterfall[index];
         detailedInlandWater[index] = profiledWater && !riverWater[index];
         hasDetailedLake |= detailedInlandWater[index];
         if (profiledWater && riverWater[index]) {
            terrainSurface[index] = waterSurface[index] - simpleInlandWaterDepthForDistance(0);
         }
      }
      if (!hasDetailedLake) {
         return;
      }

      int sampleSpacing = Math.max(1, cellSize);
      int maximumProfileCells = blendCells(LakeBedProfile.maximumShoreInfluenceBlocks(), sampleSpacing);
      int[] shoreDistance = lodShoreDistances(detailedInlandWater, lodSizePoints, maximumProfileCells);
      for (int localZ = 0; localZ < lodSizePoints; localZ++) {
         int row = localZ * lodSizePoints;
         for (int localX = 0; localX < lodSizePoints; localX++) {
            int index = row + localX;
            if (!detailedInlandWater[index]) {
               continue;
            }

            int depth = LakeBedProfile.sampledDepth(
               shoreDistance[index], sampleSpacing, worldXs[localX], worldZs[localZ]
            );
            terrainSurface[index] = waterSurface[index] - Math.max(1, depth);
         }
      }
   }

   private static boolean isProfiledInlandWater(
      int index, boolean[] hasWater, boolean[] ocean, boolean[] directLineWater
   ) {
      return hasWater[index] && !ocean[index] && !directLineWater[index];
   }

   static void applyInlandShorelineFloorRamp(
      int[] terrainSurface,
      int[] waterSurface,
      boolean[] inlandMask,
      boolean[] protectedWater,
      int lodSizePoints,
      int blendCells
   ) {
      if (blendCells <= 0) {
         return;
      }

      int area = lodSizePoints * lodSizePoints;
      int[] distance = lodShoreDistances(inlandMask, lodSizePoints, blendCells - 1);

      for (int index = 0; index < area; index++) {
         if (distance[index] >= 0 && (protectedWater == null || !protectedWater[index])) {
            terrainSurface[index] = WaterSurfaceResolver.rampedInlandShoreFloorForSteps(
               terrainSurface[index], waterSurface[index], distance[index] + 1
            );
         }
      }
   }

   private static int[] lodShoreDistances(boolean[] inlandMask, int lodSizePoints, int maximumDistance) {
      int area = lodSizePoints * lodSizePoints;
      int[] distance = new int[area];
      int[] queue = new int[area];
      Arrays.fill(distance, -1);
      int queueHead = 0;
      int queueTail = 0;

      for (int z = 0, index = 0; z < lodSizePoints; z++) {
         for (int x = 0; x < lodSizePoints; x++, index++) {
            if (inlandMask[index] && isLodShoreCell(inlandMask, lodSizePoints, x, z)) {
               distance[index] = 0;
               queue[queueTail++] = (z << 16) | x;
            }
         }
      }

      while (queueHead < queueTail) {
         int packed = queue[queueHead++];
         int x = packed & 0xFFFF;
         int z = packed >>> 16;
         int index = z * lodSizePoints + x;
         int currentDistance = distance[index];
         if (currentDistance >= maximumDistance) {
            continue;
         }
         for (int offsetIndex = 0; offsetIndex < NEIGHBOR_OFFSETS.length; offsetIndex += 2) {
            int nx = x + NEIGHBOR_OFFSETS[offsetIndex];
            int nz = z + NEIGHBOR_OFFSETS[offsetIndex + 1];
            if (nx >= 0 && nz >= 0 && nx < lodSizePoints && nz < lodSizePoints) {
               int neighbor = nz * lodSizePoints + nx;
               if (inlandMask[neighbor] && distance[neighbor] == -1) {
                  distance[neighbor] = currentDistance + 1;
                  queue[queueTail++] = (nz << 16) | nx;
               }
            }
         }
      }
      return distance;
   }

   private static boolean isLodShoreCell(boolean[] inlandMask, int lodSizePoints, int x, int z) {
      for (int offsetIndex = 0; offsetIndex < NEIGHBOR_OFFSETS.length; offsetIndex += 2) {
         int nx = x + NEIGHBOR_OFFSETS[offsetIndex];
         int nz = z + NEIGHBOR_OFFSETS[offsetIndex + 1];
         if (nx >= 0 && nz >= 0 && nx < lodSizePoints && nz < lodSizePoints && !inlandMask[nz * lodSizePoints + nx]) {
            return true;
         }
      }

      return false;
   }

   private void applyShorelineBlend(
      int[] terrainSurface, int[] baseTerrainSurface, int[] waterSurface, boolean[] waterMask, int lodSizePoints, int blendCells
   ) {
      if (blendCells > 0) {
         int area = lodSizePoints * lodSizePoints;
         int[] distance = new int[area];
         int[] nearestWaterSurface = new int[area];
         // Queue entries pack (z << 16) | x; index is recovered as z * lodSizePoints + x on dequeue.
         // This avoids per-cell % and / (~25 cycles each) in the inner BFS loop.
         int[] queue = new int[area];
         Arrays.fill(distance, -1);
         int queueHead = 0;
         int queueTail = 0;
         boolean hasBoundary = false;

         for (int z = 0, index = 0; z < lodSizePoints; z++) {
            for (int x = 0; x < lodSizePoints; x++, index++) {
               if (waterMask[index]) {
                  for (int offsetIndex = 0; offsetIndex < NEIGHBOR_OFFSETS.length; offsetIndex += 2) {
                     int nx = x + NEIGHBOR_OFFSETS[offsetIndex];
                     int nz = z + NEIGHBOR_OFFSETS[offsetIndex + 1];
                     if (nx >= 0 && nz >= 0 && nx < lodSizePoints && nz < lodSizePoints) {
                        int neighbor = nz * lodSizePoints + nx;
                        if (!waterMask[neighbor] && distance[neighbor] == -1) {
                           distance[neighbor] = 1;
                           nearestWaterSurface[neighbor] = waterSurface[index];
                           queue[queueTail++] = (nz << 16) | nx;
                           hasBoundary = true;
                        }
                     }
                  }
               }
            }
         }

         if (hasBoundary) {
            while (queueHead < queueTail) {
               int packed = queue[queueHead++];
               int x = packed & 0xFFFF;
               int z = packed >>> 16;
               int index = z * lodSizePoints + x;
               int currentDistance = distance[index];
               if (currentDistance < blendCells) {
                  for (int offsetIndex = 0; offsetIndex < NEIGHBOR_OFFSETS.length; offsetIndex += 2) {
                     int nx = x + NEIGHBOR_OFFSETS[offsetIndex];
                     int nz = z + NEIGHBOR_OFFSETS[offsetIndex + 1];
                     if (nx >= 0 && nz >= 0 && nx < lodSizePoints && nz < lodSizePoints) {
                        int neighbor = nz * lodSizePoints + nx;
                        if (!waterMask[neighbor] && distance[neighbor] == -1) {
                           distance[neighbor] = currentDistance + 1;
                           nearestWaterSurface[neighbor] = nearestWaterSurface[index];
                           queue[queueTail++] = (nz << 16) | nx;
                        }
                     }
                  }
               }
            }

            for (int index = 0; index < area; index++) {
               int currentDistance = distance[index];
               if (currentDistance > 0 && currentDistance <= blendCells) {
                  int sourceSurface = nearestWaterSurface[index];
                  int baseSurface = baseTerrainSurface[index];
                  int naturalized = WaterSurfaceResolver.naturalizedInlandBankSurface(
                     baseSurface,
                     sourceSurface,
                     currentDistance * 10,
                     blendCells,
                     this.settings.shorelineBlendCliffLimit()
                  );
                  if (naturalized < terrainSurface[index]) {
                     terrainSurface[index] = naturalized;
                  }
               }
            }
         }
      }
   }

   private void prepareLodAdaptiveBlendRadii(
      int[] adaptiveBlendRadius,
      boolean[] correctionRequired,
      boolean[] waterfallProtection,
      int[] baseTerrainSurface,
      int[] waterSurface,
      boolean[] hasWater,
      boolean[] ocean,
      boolean[] waterfall,
      int lodSizePoints
   ) {
      InlandWaterFlowAnalyzer.Parameters parameters = this.fullWaterResolver.inlandFlowParameters();
      for (int index = 0; index < hasWater.length; index++) {
         if (!hasWater[index] || ocean[index] || waterfall[index] || waterfallProtection[index]) {
            adaptiveBlendRadius[index] = 0;
            continue;
         }
         int cut = Math.max(0, baseTerrainSurface[index] - waterSurface[index]);
         if (cut <= 0 && !correctionRequired[index]) {
            adaptiveBlendRadius[index] = 0;
            continue;
         }
         correctionRequired[index] = true;
         int blendCut = Math.min(cut, parameters.maxProfileCut());
         int x = index % lodSizePoints;
         int z = index / lodSizePoints;
         int localSlope = 0;
         for (int offset = 0; offset < NEIGHBOR_OFFSETS.length; offset += 2) {
            int nx = x + NEIGHBOR_OFFSETS[offset];
            int nz = z + NEIGHBOR_OFFSETS[offset + 1];
            if (nx >= 0 && nz >= 0 && nx < lodSizePoints && nz < lodSizePoints) {
               int neighbor = nz * lodSizePoints + nx;
               localSlope = Math.max(localSlope, Math.abs(baseTerrainSurface[index] - baseTerrainSurface[neighbor]));
            }
         }
         int radius = parameters.baseBlendBlocks() + blendCut * 3 + Math.min(12, localSlope * 2);
         adaptiveBlendRadius[index] = Math.min(
            parameters.maxAdaptiveBlendBlocks(),
            Math.max(adaptiveBlendRadius[index], Math.max(parameters.baseBlendBlocks(), radius))
         );
      }
   }

   private static int maximumBlendCells(
      int[] adaptiveBlendRadius,
      boolean[] hasWater,
      boolean[] ocean,
      boolean[] waterfall,
      int cellSize
   ) {
      int maximum = 0;
      for (int index = 0; index < adaptiveBlendRadius.length; index++) {
         if (hasWater[index] && !ocean[index] && !waterfall[index]) {
            maximum = Math.max(maximum, blendCells(adaptiveBlendRadius[index], Math.max(1, cellSize)));
         }
      }
      return maximum;
   }

   private static void markLodWaterfallProtection(
      boolean[] waterfall, boolean[] protection, int lodSizePoints, int protectionCells
   ) {
      if (protectionCells <= 0) {
         return;
      }
      for (int index = 0; index < waterfall.length; index++) {
         if (!waterfall[index]) {
            continue;
         }
         int x = index % lodSizePoints;
         int z = index / lodSizePoints;
         for (int dz = -protectionCells; dz <= protectionCells; dz++) {
            int protectedZ = z + dz;
            if (protectedZ < 0 || protectedZ >= lodSizePoints) {
               continue;
            }
            for (int dx = -protectionCells; dx <= protectionCells; dx++) {
               int protectedX = x + dx;
               if (protectedX >= 0 && protectedX < lodSizePoints) {
                  protection[protectedZ * lodSizePoints + protectedX] = true;
               }
            }
         }
      }
   }

   private void applyAdaptiveShorelineBlend(
      int[] terrainSurface,
      int[] baseTerrainSurface,
      int[] waterSurface,
      boolean[] hasWater,
      boolean[] ocean,
      boolean[] waterfall,
      boolean[] correctionRequired,
      int[] adaptiveBlendRadius,
      boolean[] waterfallProtection,
      int lodSizePoints,
      int cellSize
   ) {
      int area = lodSizePoints * lodSizePoints;
      int[] remainingInfluence = new int[area];
      int[] sourceRadius = new int[area];
      int[] nearestWaterSurface = new int[area];
      Arrays.fill(remainingInfluence, -1);
      PriorityQueue<DhLodWaterResolver.LodBlendNode> queue = new PriorityQueue<>(
         (first, second) -> {
            int remainingComparison = Integer.compare(second.remaining(), first.remaining());
            if (remainingComparison != 0) {
               return remainingComparison;
            }
            int surfaceComparison = Integer.compare(first.waterSurface(), second.waterSurface());
            return surfaceComparison != 0 ? surfaceComparison : Integer.compare(first.index(), second.index());
         }
      );

      for (int index = 0; index < area; index++) {
         if (!hasWater[index]
            || ocean[index]
            || waterfall[index]
            || waterfallProtection[index]
            || !correctionRequired[index]
            || adaptiveBlendRadius[index] <= 0) {
            continue;
         }
         int radiusCells = blendCells(adaptiveBlendRadius[index], Math.max(1, cellSize));
         int initialRemaining = radiusCells - 1;
         int x = index % lodSizePoints;
         int z = index / lodSizePoints;
         for (int offset = 0; offset < NEIGHBOR_OFFSETS.length; offset += 2) {
            int nx = x + NEIGHBOR_OFFSETS[offset];
            int nz = z + NEIGHBOR_OFFSETS[offset + 1];
            if (nx < 0 || nz < 0 || nx >= lodSizePoints || nz >= lodSizePoints) {
               continue;
            }
            int neighbor = nz * lodSizePoints + nx;
            if (hasWater[neighbor] || waterfallProtection[neighbor]) {
               continue;
            }
            if (initialRemaining > remainingInfluence[neighbor]
               || initialRemaining == remainingInfluence[neighbor] && waterSurface[index] < nearestWaterSurface[neighbor]) {
               remainingInfluence[neighbor] = initialRemaining;
               sourceRadius[neighbor] = radiusCells;
               nearestWaterSurface[neighbor] = waterSurface[index];
               queue.add(new DhLodWaterResolver.LodBlendNode(neighbor, initialRemaining, radiusCells, waterSurface[index]));
            }
         }
      }

      while (!queue.isEmpty()) {
         DhLodWaterResolver.LodBlendNode node = queue.remove();
         int index = node.index();
         if (node.remaining() != remainingInfluence[index]
            || node.radius() != sourceRadius[index]
            || node.waterSurface() != nearestWaterSurface[index]
            || node.remaining() <= 0) {
            continue;
         }
         int x = index % lodSizePoints;
         int z = index / lodSizePoints;
         int nextRemaining = node.remaining() - 1;
         for (int offset = 0; offset < NEIGHBOR_OFFSETS.length; offset += 2) {
            int nx = x + NEIGHBOR_OFFSETS[offset];
            int nz = z + NEIGHBOR_OFFSETS[offset + 1];
            if (nx < 0 || nz < 0 || nx >= lodSizePoints || nz >= lodSizePoints) {
               continue;
            }
            int neighbor = nz * lodSizePoints + nx;
            if (hasWater[neighbor] || waterfallProtection[neighbor] || nextRemaining <= remainingInfluence[neighbor]) {
               continue;
            }
            remainingInfluence[neighbor] = nextRemaining;
            sourceRadius[neighbor] = node.radius();
            nearestWaterSurface[neighbor] = node.waterSurface();
            queue.add(new DhLodWaterResolver.LodBlendNode(neighbor, nextRemaining, node.radius(), node.waterSurface()));
         }
      }

      for (int index = 0; index < area; index++) {
         if (sourceRadius[index] <= 0 || remainingInfluence[index] < 0 || waterfallProtection[index]) {
            continue;
         }
         int distanceCells = sourceRadius[index] - remainingInfluence[index];
         int naturalized = WaterSurfaceResolver.naturalizedInlandBankSurface(
            baseTerrainSurface[index],
            nearestWaterSurface[index],
            distanceCells * 10,
            sourceRadius[index],
            this.settings.shorelineBlendCliffLimit()
         );
         if (naturalized < terrainSurface[index]) {
            terrainSurface[index] = naturalized;
         }
      }
   }

   private static int blendCells(int blendBlocks, int cellSize) {
      if (blendBlocks <= 0 || cellSize <= 0) {
         return 0;
      } else {
         return (blendBlocks + cellSize - 1) / cellSize;
      }
   }

   private static int percentile(int[] values, int length, double percentile) {
      if (length <= 0) {
         return 0;
      } else {
         Arrays.sort(values, 0, length);
         int index = (int)Math.floor(Mth.clamp(percentile, 0.0, 1.0) * (length - 1));
         return values[Mth.clamp(index, 0, length - 1)];
      }
   }

   private static double smoothstep(double value) {
      double t = Mth.clamp(value, 0.0, 1.0);
      return t * t * (3.0 - 2.0 * t);
   }

   private static int cellIndexForWorld(double world, int base, int cellOffset, int cellSize, int lodSizePoints) {
      int index = (int)Math.floor((world - (base + cellOffset)) / cellSize);
      return Mth.clamp(index, 0, lodSizePoints - 1);
   }

   private static int maxSampleOffset(int[] sampleOffsets) {
      int max = 0;

      for (int offset : sampleOffsets) {
         max = Math.max(max, Math.abs(offset));
      }

      return max;
   }

   private static void throwIfCancelled() {
      if (Thread.currentThread().isInterrupted()) {
         throw new CancellationException("DH water area resolution interrupted");
      }
   }

   static boolean shouldSampleLandMask(boolean sampledOcean, boolean belowSeaLevel, int coverClass, boolean rasterIncomplete) {
      return rasterIncomplete
         || sampledOcean
         || belowSeaLevel
         || coverClass == ESA_NO_DATA
         || coverClass == ESA_WATER;
   }

   private static boolean isKnownOceanMask(TellusLandMaskSource.LandMaskSample landMaskSample) {
      return landMaskSample != null && landMaskSample.known() && !landMaskSample.land();
   }

   private static int[] sampleOffsetsForCellSize(int cellSize) {
      if (cellSize <= 1) {
         return SAMPLE_OFFSETS_SINGLE;
      } else if (cellSize == 2) {
         return SAMPLE_OFFSETS_CELL_2;
      } else if (cellSize <= 4) {
         return SAMPLE_OFFSETS_SMALL_GRID;
      } else {
         int edge = Math.max(1, (cellSize >> 1) - 1);
         int quarter = Math.max(1, edge >> 1);
         return buildGridOffsets(new int[]{-edge, -quarter, 0, quarter, edge});
      }
   }

   private static int[] buildGridOffsets(int[] axisOffsets) {
      int[] offsets = new int[axisOffsets.length * axisOffsets.length * 2];
      int cursor = 0;

      for (int offsetZ : axisOffsets) {
         for (int offsetX : axisOffsets) {
            offsets[cursor++] = offsetX;
            offsets[cursor++] = offsetZ;
         }
      }

      return offsets;
   }

   private static boolean shouldRenderExactWaterFootprint(int wetSamples, int totalSamples, boolean ocean, boolean lineWater) {
      if (wetSamples <= 0 || totalSamples <= 0) {
         return false;
      } else if (lineWater) {
         return true;
      } else {
         return ocean ? wetSamples > totalSamples / 2 : wetSamples >= inlandExactWaterFootprintThreshold(totalSamples);
      }
   }

   private static int inlandExactWaterFootprintThreshold(int totalSamples) {
      // Near-player DH cells can be represented by just 1 or 4 probe samples.
      // Requiring multiple hits there either makes water impossible (1 sample)
      // or drops narrow OSM polygons too aggressively at close range.
      if (totalSamples <= 4) {
         return 1;
      } else {
         return Math.max(2, (totalSamples + 2) / 3);
      }
   }

   private static int[] emptySurfaceHints(int area) {
      int[] hints = new int[area];
      Arrays.fill(hints, Integer.MIN_VALUE);
      return hints;
   }

   private static WaterSurfaceResolver.WaterColumnData normalizeLodWaterColumn(WaterSurfaceResolver.WaterColumnData column) {
      if (!column.hasWater()) {
         return column;
      } else {
         int terrainSurface = column.terrainSurface();
         int waterSurface = column.waterSurface();
         if (terrainSurface >= waterSurface) {
            terrainSurface = waterSurface - 1;
         }

         return terrainSurface == column.terrainSurface()
            ? column
            : new WaterSurfaceResolver.WaterColumnData(true, column.isOcean(), terrainSurface, waterSurface);
      }
   }

   private static int intProperty(String key, int defaultValue, int minInclusive, int maxInclusive) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      } else {
         try {
            return Mth.clamp(Integer.parseInt(value), minInclusive, maxInclusive);
         } catch (NumberFormatException error) {
            return defaultValue;
         }
      }
   }

   static int waterQueryTileBudget(int cellSize) {
      int detail = 31 - Integer.numberOfLeadingZeros(Math.max(1, cellSize));
      int reduction = Math.max(0, detail - WATER_QUERY_FULL_DETAIL);
      int scaledBudget = WATER_QUERY_MAX_TILES >> Math.min(30, reduction);
      return Math.max(WATER_QUERY_MIN_TILES, scaledBudget);
   }

   static boolean shouldRetryPendingCoverage(
      TellusOsmWaterSource.CoverageStatus coverageStatus, OsmQueryMode queryMode
   ) {
      return coverageStatus == TellusOsmWaterSource.CoverageStatus.PENDING
         && queryMode == OsmQueryMode.NON_BLOCKING;
   }

   public record AreaResult(int[] terrainSurface, int[] waterSurface, boolean[] hasWater, boolean[] ocean) {
   }

   private record LodBlendNode(int index, int remaining, int radius, int waterSurface) {
   }

   private record AreaKey(int baseX, int baseZ, int lodSizePoints, int cellSize, long generation) {
   }

	   private record RasterizedWaterArea(
	      boolean[] renderWater,
	      boolean[] ocean,
	      boolean[] sampledOcean,
	      boolean[] lineWater,
	      boolean[] areaWater,
	      boolean[] flowingWater,
	      boolean[] waterfallNoCarve,
	      long[] waterBodyKeys,
	      int[] waterBodySurfaceHints,
	      long sourceGeneration,
	      boolean incomplete
	   ) {
	      private static DhLodWaterResolver.RasterizedWaterArea dry(int area) {
	         return new DhLodWaterResolver.RasterizedWaterArea(
	            new boolean[area],
	            new boolean[area],
	            new boolean[area],
	            new boolean[area],
	            new boolean[area],
	            new boolean[area],
	            new boolean[area],
	            new long[area],
	            emptySurfaceHints(area),
	            0L,
	            false
	         );
	      }
	   }
}
