package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.preload.TerrainPreloadPackage;
import com.yucareux.tellus.preload.TerrainPreloadPackageRegistry;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.world.data.canopy.TellusCanopyHeightSource;
import com.yucareux.tellus.world.data.cover.TellusLandCoverSource;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource;
import com.yucareux.tellus.world.data.koppen.TellusKoppenSource;
import com.yucareux.tellus.world.data.mask.TellusLandMaskSource;
import com.yucareux.tellus.world.data.ocean.OisstOceanClimateSource;
import com.yucareux.tellus.world.data.osm.TellusOsmBuildingSource;
import com.yucareux.tellus.world.data.osm.TellusOsmInfrastructureSource;
import com.yucareux.tellus.world.data.osm.TellusOsmRoadSource;
import com.yucareux.tellus.world.data.osm.TellusOsmSandSource;
import com.yucareux.tellus.world.data.osm.TellusOsmWaterSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import net.minecraft.world.level.ChunkPos;

public final class TellusWorldgenSources {
   private static final TellusCanopyHeightSource CANOPY_HEIGHT = new TellusCanopyHeightSource();
   private static final TellusLandCoverSource LAND_COVER = new TellusLandCoverSource();
   private static final TellusLandMaskSource LAND_MASK = new TellusLandMaskSource();
   private static final TellusElevationSource ELEVATION = new TellusElevationSource();
   private static final TellusKoppenSource KOPPEN = new TellusKoppenSource();
   private static final OisstOceanClimateSource OCEAN_CLIMATE = new OisstOceanClimateSource();
   private static final TellusOsmRoadSource OSM_ROADS = new TellusOsmRoadSource();
   private static final TellusOsmInfrastructureSource OSM_INFRASTRUCTURE = new TellusOsmInfrastructureSource();
   private static final TellusOsmBuildingSource OSM_BUILDINGS = new TellusOsmBuildingSource();
   private static final TellusOsmWaterSource OSM_WATER = new TellusOsmWaterSource();
   private static final TellusOsmSandSource OSM_SAND = new TellusOsmSandSource();
   private static final boolean PREFETCH_ENABLED = Boolean.parseBoolean(System.getProperty("tellus.prefetch.enabled", "true"));
   private static final int LAND_COVER_PREFETCH_RADIUS = intProperty("tellus.prefetch.landcover.radius", 1);
   private static final int CANOPY_HEIGHT_PREFETCH_RADIUS = intProperty("tellus.prefetch.canopyHeight.radius", 1);
   private static final int ELEVATION_PREFETCH_RADIUS = intProperty("tellus.prefetch.elevation.radius", 1);
   private static final int LAND_MASK_PREFETCH_RADIUS = intProperty("tellus.prefetch.landmask.radius", 1);
   private static final boolean WATER_PREFETCH_ENABLED = Boolean.parseBoolean(System.getProperty("tellus.prefetch.water.enabled", "true"));
   private static final int WATER_PREFETCH_RADIUS = intProperty("tellus.prefetch.water.radius", 1);
   private static final int SAND_PREFETCH_RADIUS = intProperty("tellus.prefetch.sand.radius", 1);
   private static final int CHUNK_DETAIL_PREFETCH_RADIUS = intProperty("tellus.chunkdetail.prefetchRadius", 1);
   private static final int ROADS_PREFETCH_RADIUS = intProperty("tellus.prefetch.roads.radius", 1);
   private static final int INFRASTRUCTURE_PREFETCH_RADIUS = intProperty("tellus.prefetch.infrastructure.radius", 1);
   private static final int BUILDINGS_PREFETCH_RADIUS = intProperty("tellus.prefetch.buildings.radius", 1);
   private static final int LOD_OSM_SURFACE_MAX_DETAIL = intProperty("tellus.dhOsmSurfaceMaxDetail", 6);
   private static final ExecutorService PREFETCH_EXECUTOR = createPrefetchExecutor();
   private static final ThreadPoolExecutor LOD_PREFETCH_EXECUTOR = createLodPrefetchExecutor();
   private static final ExecutorService TERRAIN_DETAIL_EXECUTOR = createTerrainDetailExecutor();
   private static final ConcurrentMap<EarthGeneratorSettings, WaterSurfaceResolver> WATER_RESOLVERS = new ConcurrentHashMap<>();

   private TellusWorldgenSources() {
   }

   static TellusLandCoverSource landCover() {
      return LAND_COVER;
   }

   public static TellusCanopyHeightSource canopyHeight() {
      return CANOPY_HEIGHT;
   }

   static TellusElevationSource elevation() {
      return ELEVATION;
   }

   static TellusKoppenSource koppen() {
      return KOPPEN;
   }

   static OisstOceanClimateSource oceanClimate() {
      return OCEAN_CLIMATE;
   }

   public static TellusLandMaskSource landMask() {
      return LAND_MASK;
   }

   public static TellusOsmRoadSource osmRoads() {
      return OSM_ROADS;
   }

   public static TellusOsmInfrastructureSource osmInfrastructure() {
      return OSM_INFRASTRUCTURE;
   }

   public static TellusOsmBuildingSource osmBuildings() {
      return OSM_BUILDINGS;
   }

   public static TellusOsmWaterSource osmWater() {
      return OSM_WATER;
   }

   public static WaterSurfaceResolver previewWaterResolver(EarthGeneratorSettings settings) {
      return waterResolver(settings);
   }

   public static TellusOsmSandSource osmSand() {
      return OSM_SAND;
   }

   static WaterSurfaceResolver waterResolver(EarthGeneratorSettings settings) {
      Objects.requireNonNull(settings, "settings");
      WaterSurfaceResolver resolver = WATER_RESOLVERS.computeIfAbsent(settings, value -> new WaterSurfaceResolver(LAND_COVER, LAND_MASK, ELEVATION, value));
      return Objects.requireNonNull(resolver, "waterResolver");
   }

   public static TerrainPreloadPackage.Sample samplePreloadTerrain(
      int blockX, int blockZ, EarthGeneratorSettings settings, double previewResolutionMeters
   ) {
      Objects.requireNonNull(settings, "settings");
      double worldScale = settings.worldScale();
      WorldProjection projection = settings.projection();
      int coverClass = LAND_COVER.sampleCoverClass(blockX, blockZ, projection, previewResolutionMeters);
      TellusLandMaskSource.LandMaskSample landMask = LAND_MASK.sampleLandMask(blockX, blockZ, projection);
      boolean oceanCandidate = landMask.known() && !landMask.land() && (coverClass == 0 || coverClass == 80);
      TellusElevationSource.ResolvedElevationSample elevation = ELEVATION.sampleResolvedPreviewElevationMeters(
         blockX, blockZ, projection, oceanCandidate, settings.demSelection(), previewResolutionMeters
      );
      double elevationMeters = elevation.elevationMeters();
      int terrainHeight;
      if (!Double.isFinite(elevationMeters)) {
         terrainHeight = settings.effectiveHeightOffset();
      } else {
         int relativeHeight = TerrainHeightTransform.blockOffset(
            elevationMeters,
            blockZ,
            projection,
            settings.effectiveTerrestrialHeightScale(),
            settings.effectiveOceanicHeightScale(),
            settings.experimentalIncreaseHeight(),
            settings.automaticHeightScaling()
         );
         terrainHeight = relativeHeight + settings.effectiveHeightOffset();
      }

      boolean openWatersSelected = elevation.primaryProvider() == TellusElevationSource.DemUsage.OPENWATERS;
      boolean oceanElevationSelected = oceanCandidate && Double.isFinite(elevationMeters) && !elevation.mapterhornLandOverride();
      return new TerrainPreloadPackage.Sample(
         terrainHeight,
         coverClass,
         landMask.known(),
         landMask.land(),
         openWatersSelected,
         elevation.mapterhornLandOverride(),
         oceanElevationSelected
      );
   }

   static void warmCriticalTerrainInputsForChunk(ChunkPos pos, EarthGeneratorSettings settings, double previewResolutionMeters) {
      if (ManagedTerrainNetworkPolicy.isCacheOnly()) return;
      Objects.requireNonNull(pos, "pos");
      Objects.requireNonNull(settings, "settings");
      int centerX = pos.getMinBlockX() + 8;
      int centerZ = pos.getMinBlockZ() + 8;
      double worldScale = settings.worldScale();
      WorldProjection projection = settings.projection();
      boolean packagedTerrain = hasPreloadedTerrainForChunk(pos, settings, previewResolutionMeters);
      List<CompletableFuture<Void>> futures = new ArrayList<>(4);
      if (!packagedTerrain) {
         futures.add(
            submitCriticalWarmup(() -> LAND_COVER.prefetchTiles(centerX, centerZ, projection, Math.max(0, LAND_COVER_PREFETCH_RADIUS), previewResolutionMeters))
         );
         futures.add(
            submitCriticalWarmup(
               () -> ELEVATION.prefetchTiles(centerX, centerZ, projection, Math.max(0, ELEVATION_PREFETCH_RADIUS), settings.demSelection(), previewResolutionMeters)
            )
         );
         futures.add(submitCriticalWarmup(() -> LAND_MASK.prefetchTiles(centerX, centerZ, projection, Math.max(1, LAND_MASK_PREFETCH_RADIUS))));
      }

      if (settings.customTrees() && CANOPY_HEIGHT_PREFETCH_RADIUS >= 0) {
         futures.add(
            submitCriticalWarmup(
               () -> CANOPY_HEIGHT.prefetchTiles(
                  centerX,
                  centerZ,
                  projection,
                  Math.max(0, CANOPY_HEIGHT_PREFETCH_RADIUS),
                  previewResolutionMeters
               )
            )
         );
      }

         if (SAND_PREFETCH_RADIUS > 0 && worldScale > 0.0) {
            futures.add(submitCriticalWarmup(() -> OSM_SAND.prefetchTiles(centerX, centerZ, projection, SAND_PREFETCH_RADIUS)));
         }

      for (CompletableFuture<Void> future : futures) {
         try {
            future.join();
         } catch (RuntimeException error) {
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            Tellus.LOGGER.debug("Failed to warm Tellus terrain input for chunk {}", pos, cause);
         }
      }
   }

   static void prefetchForChunk(ChunkPos pos, EarthGeneratorSettings settings) {
      prefetchForChunk(pos, settings, true, true, true, settings.worldScale());
   }

   static void prefetchForChunk(ChunkPos pos, EarthGeneratorSettings settings, boolean includeRoadsPrefetch) {
      prefetchForChunk(pos, settings, includeRoadsPrefetch, true, true, settings.worldScale());
   }

   static void prefetchForChunk(ChunkPos pos, EarthGeneratorSettings settings, boolean includeRoadsPrefetch, boolean includeDetailedWaterPrefetch) {
      prefetchForChunk(pos, settings, includeRoadsPrefetch, includeDetailedWaterPrefetch, true, settings.worldScale());
   }

   static void prefetchForChunk(
      ChunkPos pos, EarthGeneratorSettings settings, boolean includeRoadsPrefetch, boolean includeDetailedWaterPrefetch, boolean includeBuildingsPrefetch
   ) {
      prefetchForChunk(pos, settings, includeRoadsPrefetch, includeDetailedWaterPrefetch, includeBuildingsPrefetch, settings.worldScale());
   }

   static void prefetchForChunk(
      ChunkPos pos,
      EarthGeneratorSettings settings,
      boolean includeRoadsPrefetch,
      boolean includeDetailedWaterPrefetch,
      boolean includeBuildingsPrefetch,
      double previewResolutionMeters
   ) {
      prefetchForChunk(pos, settings, includeRoadsPrefetch, includeDetailedWaterPrefetch, includeBuildingsPrefetch, previewResolutionMeters, true);
   }

   static void prefetchForChunk(
      ChunkPos pos,
      EarthGeneratorSettings settings,
      boolean includeRoadsPrefetch,
      boolean includeDetailedWaterPrefetch,
      boolean includeBuildingsPrefetch,
      double previewResolutionMeters,
      boolean allowInlineExecution
   ) {
      if (ManagedTerrainNetworkPolicy.isCacheOnly()) return;
      if (PREFETCH_ENABLED && PREFETCH_EXECUTOR != null) {
         if (!hasPreloadedTerrainForChunk(pos, settings, previewResolutionMeters)) {
            prefetchTerrainForChunk(pos, settings, previewResolutionMeters, allowInlineExecution);
         }

         prefetchWaterForChunk(pos, settings, includeDetailedWaterPrefetch, previewResolutionMeters, allowInlineExecution);
         prefetchOsmDetailsForChunk(pos, settings, includeRoadsPrefetch, includeBuildingsPrefetch, previewResolutionMeters, allowInlineExecution);
      }
   }

   static CompletableFuture<Void> prefetchForArea(
      int minBlockX,
      int minBlockZ,
      int maxBlockX,
      int maxBlockZ,
      EarthGeneratorSettings settings,
      boolean includeRoadsPrefetch,
      boolean includeBuildingsPrefetch,
      double previewResolutionMeters
   ) {
      if (ManagedTerrainNetworkPolicy.isCacheOnly() || !PREFETCH_ENABLED || PREFETCH_EXECUTOR == null) {
         return CompletableFuture.completedFuture(null);
      }

      int minX = Math.min(minBlockX, maxBlockX);
      int minZ = Math.min(minBlockZ, maxBlockZ);
      int maxX = Math.max(minBlockX, maxBlockX);
      int maxZ = Math.max(minBlockZ, maxBlockZ);
      double worldScale = settings.worldScale();
      WorldProjection projection = settings.projection();
      int marginBlocks = Math.max(16, (int)Math.ceil(64.0 / Math.max(1.0, worldScale)));
      int lodCellBlocks = lodCellSize(worldScale, previewResolutionMeters);
      List<CompletableFuture<Void>> sourceFutures = new ArrayList<>(8);
      sourceFutures.add(submitAreaPrefetch("land cover", () ->
         LAND_COVER.preloadAreaInputsIntoMemory(minX, minZ, maxX, maxZ, projection, previewResolutionMeters, 0, null)
      ));
      if (settings.customTrees()) {
         sourceFutures.add(submitAreaPrefetch("canopy height", () ->
            CANOPY_HEIGHT.preloadAreaInputs(minX, minZ, maxX, maxZ, projection, previewResolutionMeters, 0, null)
         ));
      }
      sourceFutures.add(submitAreaPrefetch("elevation", () ->
         ELEVATION.preloadAreaInputsIntoMemory(minX, minZ, maxX, maxZ, projection, settings.demSelection(), previewResolutionMeters, 0, null)
      ));
      sourceFutures.add(submitAreaPrefetch("land mask", () ->
         LAND_MASK.preloadAreaInputsIntoMemory(minX, minZ, maxX, maxZ, projection, 0, null)
      ));
      if (SAND_PREFETCH_RADIUS > 0 && worldScale > 0.0 && lodDetail(lodCellBlocks) <= LOD_OSM_SURFACE_MAX_DETAIL) {
         sourceFutures.add(submitAreaPrefetch("sand", () ->
            OSM_SAND.preloadAreaTiles(minX, minZ, maxX, maxZ, projection, Math.max(1, SAND_PREFETCH_RADIUS), 0, null)
         ));
      }
      if (WATER_PREFETCH_ENABLED && settings.enableWater()) {
         int waterTileBudget = DhLodWaterResolver.waterQueryTileBudget(lodCellBlocks);
         sourceFutures.add(submitAreaPrefetch("water", () ->
            OSM_WATER.downloadAreaInputs(minX, minZ, maxX, maxZ, projection, marginBlocks, waterTileBudget, 0, null)
         ));
      }
      if (includeRoadsPrefetch && settings.enableRoads() && worldScale > 0.0 && worldScale <= 15.0) {
         sourceFutures.add(submitAreaPrefetch("roads", () ->
            OSM_ROADS.preloadAreaInputs(minX, minZ, maxX, maxZ, projection, marginBlocks, 0, null)
         ));
         sourceFutures.add(submitAreaPrefetch("road infrastructure", () ->
            OSM_INFRASTRUCTURE.preloadAreaInputs(minX, minZ, maxX, maxZ, projection, marginBlocks, 0, null)
         ));
      }
      if (includeBuildingsPrefetch && settings.enableBuildings() && worldScale > 0.0 && worldScale <= 15.0) {
         sourceFutures.add(submitAreaPrefetch("buildings", () ->
            OSM_BUILDINGS.preloadAreaInputs(minX, minZ, maxX, maxZ, projection, marginBlocks, 0, null)
         ));
      }

      TellusWorldgenSources.CancellablePrefetchFuture result = new TellusWorldgenSources.CancellablePrefetchFuture();
      sourceFutures.forEach(result::attach);
      CompletableFuture.allOf(sourceFutures.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
         if (result.isDone()) {
            return;
         }
         if (error != null) {
            result.completeExceptionally(error);
         } else {
            // Fast LOD lake beds are resolved directly on the sampled area, so
            // prebuilding overlapping block-resolution water regions only adds
            // I/O and allocation pressure without improving their output.
            result.complete(null);
         }
      });
      return result;
   }

   private static int lodCellSize(double worldScale, double previewResolutionMeters) {
      if (!(worldScale > 0.0) || !Double.isFinite(previewResolutionMeters) || previewResolutionMeters <= 0.0) {
         return 1;
      }
      double blocks = previewResolutionMeters / worldScale;
      return (int)Math.max(1L, Math.min(Integer.MAX_VALUE, Math.round(blocks)));
   }

   private static int lodDetail(int cellSize) {
      return 31 - Integer.numberOfLeadingZeros(Math.max(1, cellSize));
   }

   private static boolean hasPreloadedTerrainForChunk(
      ChunkPos pos, EarthGeneratorSettings settings, double previewResolutionMeters
   ) {
      int centerX = pos.getMinBlockX() + 8;
      int centerZ = pos.getMinBlockZ() + 8;
      return TerrainPreloadPackageRegistry.instance().viewFor(settings).contains(centerX, centerZ, previewResolutionMeters);
   }

   public static int preloadAreaTaskCount(EarthGeneratorSettings settings) {
      return preloadAreaTaskCount(0, 0, 0, 0, settings);
   }

   public static int preloadAreaTaskCount(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, EarthGeneratorSettings settings
   ) {
      return preloadAreaTaskCount(minBlockX, minBlockZ, maxBlockX, maxBlockZ, settings, Double.NaN);
   }

   public static int preloadAreaTaskCount(
      int minBlockX,
      int minBlockZ,
      int maxBlockX,
      int maxBlockZ,
      EarthGeneratorSettings settings,
      double packagePreviewResolutionMeters
   ) {
      Objects.requireNonNull(settings, "settings");
      double worldScale = settings.worldScale();
      WorldProjection projection = settings.projection();
      int marginBlocks = Math.max(16, (int)Math.ceil(64.0 / Math.max(1.0, worldScale)));
      int tasks = ELEVATION.preloadAreaTaskCount(
         minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, settings.demSelection(), worldScale
      )
         + LAND_COVER.preloadAreaTaskCount(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, worldScale)
         + (settings.customTrees()
            ? CANOPY_HEIGHT.preloadAreaTaskCount(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, worldScale)
            : 0)
         + LAND_MASK.preloadAreaTaskCount(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection)
         + OSM_SAND.downloadAreaTileCount(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, Math.max(1, SAND_PREFETCH_RADIUS));
      if (hasDistinctPackageResolution(worldScale, packagePreviewResolutionMeters)) {
         tasks += ELEVATION.preloadAreaTaskCount(
            minBlockX,
            minBlockZ,
            maxBlockX,
            maxBlockZ,
            projection,
            settings.demSelection(),
            packagePreviewResolutionMeters
         );
         tasks += LAND_COVER.preloadAreaTaskCount(
            minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, packagePreviewResolutionMeters
         );
         if (settings.customTrees()) {
            tasks += CANOPY_HEIGHT.preloadAreaTaskCount(
               minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, packagePreviewResolutionMeters
            );
         }
      }
      if (settings.enableWater()) {
         tasks += OSM_WATER.downloadAreaTaskCount(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, marginBlocks);
      }

      if (settings.enableRoads() && worldScale > 0.0 && worldScale <= 15.0) {
         tasks += OSM_ROADS.downloadAreaTaskCount(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, marginBlocks)
            + OSM_INFRASTRUCTURE.downloadAreaTaskCount(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, marginBlocks);
      }

      if (settings.enableBuildings() && worldScale > 0.0 && worldScale <= 15.0) {
         tasks += OSM_BUILDINGS.downloadAreaTaskCount(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, marginBlocks);
      }

      return tasks;
   }

   public static void preloadAreaInputs(
      int minBlockX,
      int minBlockZ,
      int maxBlockX,
      int maxBlockZ,
      EarthGeneratorSettings settings,
      BiConsumer<Integer, String> progressConsumer
   ) {
      preloadAreaInputs(minBlockX, minBlockZ, maxBlockX, maxBlockZ, settings, Double.NaN, progressConsumer);
   }

   public static void preloadAreaInputs(
      int minBlockX,
      int minBlockZ,
      int maxBlockX,
      int maxBlockZ,
      EarthGeneratorSettings settings,
      double packagePreviewResolutionMeters,
      BiConsumer<Integer, String> progressConsumer
   ) {
      Objects.requireNonNull(settings, "settings");
      BiConsumer<Integer, String> progress = progressConsumer == null ? (completed, detail) -> {
      } : progressConsumer;
      double worldScale = settings.worldScale();
      WorldProjection projection = settings.projection();
      int marginBlocks = Math.max(16, (int)Math.ceil(64.0 / Math.max(1.0, worldScale)));
      int completed = 0;

      completed = ELEVATION.preloadAreaInputs(
         minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, settings.demSelection(), worldScale, completed, progress
      );

      completed = LAND_COVER.preloadAreaInputs(
         minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, worldScale, completed, progress
      );
      if (settings.customTrees()) {
         completed = CANOPY_HEIGHT.preloadAreaInputs(
            minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, worldScale, completed, progress
         );
      }

      if (hasDistinctPackageResolution(worldScale, packagePreviewResolutionMeters)) {
         completed = ELEVATION.preloadAreaInputs(
            minBlockX,
            minBlockZ,
            maxBlockX,
            maxBlockZ,
            projection,
            settings.demSelection(),
            packagePreviewResolutionMeters,
            completed,
            progress
         );
         completed = LAND_COVER.preloadAreaInputs(
            minBlockX,
            minBlockZ,
            maxBlockX,
            maxBlockZ,
            projection,
            packagePreviewResolutionMeters,
            completed,
            progress
         );
         if (settings.customTrees()) {
            completed = CANOPY_HEIGHT.preloadAreaInputs(
               minBlockX,
               minBlockZ,
               maxBlockX,
               maxBlockZ,
               projection,
               packagePreviewResolutionMeters,
               completed,
               progress
            );
         }
      }

      completed = LAND_MASK.preloadAreaInputs(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, completed, progress);

      completed = OSM_SAND.downloadAreaTiles(
         minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, Math.max(1, SAND_PREFETCH_RADIUS), completed, progress
      );

      if (settings.enableWater()) {
         completed = OSM_WATER.downloadAreaInputs(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, marginBlocks, completed, progress);
      }

      if (settings.enableRoads() && worldScale > 0.0 && worldScale <= 15.0) {
         completed = OSM_ROADS.downloadAreaInputs(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, marginBlocks, completed, progress);
         completed = OSM_INFRASTRUCTURE.downloadAreaInputs(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, marginBlocks, completed, progress);
      }

      if (settings.enableBuildings() && worldScale > 0.0 && worldScale <= 15.0) {
         completed = OSM_BUILDINGS.downloadAreaInputs(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, marginBlocks, completed, progress);
      }
   }

   private static boolean hasDistinctPackageResolution(double worldScale, double packagePreviewResolutionMeters) {
      if (!Double.isFinite(packagePreviewResolutionMeters) || packagePreviewResolutionMeters <= 0.0) {
         return false;
      }
      double tolerance = Math.max(1.0E-6, Math.abs(worldScale) * 1.0E-6);
      return Math.abs(packagePreviewResolutionMeters - worldScale) > tolerance;
   }

   static void prefetchTerrainForChunk(
      ChunkPos pos, EarthGeneratorSettings settings, double previewResolutionMeters, boolean allowInlineExecution
   ) {
      if (ManagedTerrainNetworkPolicy.isCacheOnly()) return;
      if (PREFETCH_ENABLED && PREFETCH_EXECUTOR != null) {
         int centerX = pos.getMinBlockX() + 8;
         int centerZ = pos.getMinBlockZ() + 8;
         double worldScale = settings.worldScale();
         WorldProjection projection = settings.projection();
         if (LAND_COVER_PREFETCH_RADIUS > 0) {
            submitPrefetch(() -> LAND_COVER.prefetchTiles(centerX, centerZ, projection, LAND_COVER_PREFETCH_RADIUS, previewResolutionMeters), allowInlineExecution);
         }

         if (settings.customTrees() && CANOPY_HEIGHT_PREFETCH_RADIUS >= 0) {
            submitPrefetch(
               () -> CANOPY_HEIGHT.prefetchTiles(
                  centerX,
                  centerZ,
                  projection,
                  Math.max(0, CANOPY_HEIGHT_PREFETCH_RADIUS),
                  previewResolutionMeters
               ),
               allowInlineExecution
            );
         }

         if (ELEVATION_PREFETCH_RADIUS > 0) {
            int elevationPrefetchRadius = worldScale > 0.0
                  && Double.isFinite(previewResolutionMeters)
                  && previewResolutionMeters > worldScale
               ? 0
               : ELEVATION_PREFETCH_RADIUS;
            submitPrefetch(
               () -> ELEVATION.prefetchTiles(centerX, centerZ, projection, elevationPrefetchRadius, settings.demSelection(), previewResolutionMeters),
               allowInlineExecution
            );
         }

         if (LAND_MASK_PREFETCH_RADIUS > 0) {
            submitPrefetch(() -> LAND_MASK.prefetchTiles(centerX, centerZ, projection, LAND_MASK_PREFETCH_RADIUS), allowInlineExecution);
         }
      }
   }

   static void prefetchWaterForChunk(
      ChunkPos pos,
      EarthGeneratorSettings settings,
      boolean includeDetailedWaterPrefetch,
      double previewResolutionMeters,
      boolean allowInlineExecution
   ) {
      if (ManagedTerrainNetworkPolicy.isCacheOnly()) return;
      if (PREFETCH_ENABLED && PREFETCH_EXECUTOR != null) {
         int centerX = pos.getMinBlockX() + 8;
         int centerZ = pos.getMinBlockZ() + 8;
         double worldScale = settings.worldScale();
         WorldProjection projection = settings.projection();
         if (WATER_PREFETCH_ENABLED && WATER_PREFETCH_RADIUS > 0 && settings.enableWater()) {
            submitPrefetch(() -> OSM_WATER.prefetchTiles(centerX, centerZ, projection, WATER_PREFETCH_RADIUS), allowInlineExecution);
         }

         if (SAND_PREFETCH_RADIUS > 0 && worldScale > 0.0) {
            submitPrefetch(() -> OSM_SAND.prefetchTiles(centerX, centerZ, projection, SAND_PREFETCH_RADIUS), allowInlineExecution);
         }

         if (includeDetailedWaterPrefetch && CHUNK_DETAIL_PREFETCH_RADIUS > 0) {
            submitPrefetch(
               () -> waterResolver(settings).prefetchRegionsForChunk(
                  MinecraftVersionCompat.chunkX(pos), MinecraftVersionCompat.chunkZ(pos), CHUNK_DETAIL_PREFETCH_RADIUS
               ),
               allowInlineExecution
            );
         }
      }
   }

   static void prefetchOsmDetailsForChunk(
      ChunkPos pos,
      EarthGeneratorSettings settings,
      boolean includeRoadsPrefetch,
      boolean includeBuildingsPrefetch,
      double previewResolutionMeters,
      boolean allowInlineExecution
   ) {
      if (ManagedTerrainNetworkPolicy.isCacheOnly()) return;
      if (PREFETCH_ENABLED && PREFETCH_EXECUTOR != null) {
         int centerX = pos.getMinBlockX() + 8;
         int centerZ = pos.getMinBlockZ() + 8;
         double worldScale = settings.worldScale();
         WorldProjection projection = settings.projection();
         if (includeRoadsPrefetch && settings.enableRoads() && worldScale <= 15.0 && ROADS_PREFETCH_RADIUS > 0) {
            submitPrefetch(() -> OSM_ROADS.prefetchTiles(centerX, centerZ, projection, ROADS_PREFETCH_RADIUS), allowInlineExecution);
         }

         if (includeRoadsPrefetch && settings.enableRoads() && worldScale <= 15.0 && INFRASTRUCTURE_PREFETCH_RADIUS > 0) {
            submitPrefetch(() -> OSM_INFRASTRUCTURE.prefetchTiles(centerX, centerZ, projection, INFRASTRUCTURE_PREFETCH_RADIUS), allowInlineExecution);
         }

         if (includeBuildingsPrefetch && settings.enableBuildings() && worldScale > 0.0 && worldScale <= 15.0 && BUILDINGS_PREFETCH_RADIUS > 0) {
            submitPrefetch(() -> OSM_BUILDINGS.prefetchTiles(centerX, centerZ, projection, BUILDINGS_PREFETCH_RADIUS), allowInlineExecution);
         }
      }
   }

   static void prefetchWaterRegionsForArea(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, EarthGeneratorSettings settings) {
      prefetchWaterRegionsForArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ, settings, true);
   }

   static void prefetchWaterRegionsForArea(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, EarthGeneratorSettings settings, boolean allowInlineExecution
   ) {
      if (ManagedTerrainNetworkPolicy.isCacheOnly()) return;
      if (PREFETCH_ENABLED && PREFETCH_EXECUTOR != null && WATER_PREFETCH_ENABLED) {
         submitPrefetch(() -> waterResolver(settings).prefetchRegionsForArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ), allowInlineExecution);
      }
   }

   static CompletableFuture<Void> submitTerrainDetailTask(Runnable task) {
      if (TERRAIN_DETAIL_EXECUTOR != null) {
         try {
            return CompletableFuture.runAsync(task, TERRAIN_DETAIL_EXECUTOR);
         } catch (RejectedExecutionException error) {
         }
      }

      try {
         task.run();
         return CompletableFuture.completedFuture(null);
      } catch (RuntimeException error) {
         CompletableFuture<Void> failed = new CompletableFuture<>();
         failed.completeExceptionally(error);
         return failed;
      }
   }

   private static void submitPrefetch(Runnable task, boolean allowInlineExecution) {
      try {
         PREFETCH_EXECUTOR.execute(task);
      } catch (RejectedExecutionException error) {
         if (allowInlineExecution) {
            try {
               task.run();
            } catch (RuntimeException inlineError) {
               Tellus.LOGGER.debug("Failed to execute Tellus prefetch task inline", inlineError);
            }
         } else {
            EarthChunkGenerator.recordTerrainStreamingPrefetchQueueRejection();
            Tellus.LOGGER.debug("Skipped Tellus prefetch task because the queue is full");
         }
      } catch (RuntimeException error) {
         Tellus.LOGGER.debug("Failed to schedule Tellus prefetch task", error);
      }
   }

   private static CompletableFuture<Void> submitCriticalWarmup(Runnable task) {
      if (PREFETCH_EXECUTOR != null) {
         try {
            return CompletableFuture.runAsync(task, PREFETCH_EXECUTOR);
         } catch (RejectedExecutionException error) {
         }
      }

      try {
         task.run();
         return CompletableFuture.completedFuture(null);
      } catch (RuntimeException error) {
         CompletableFuture<Void> failed = new CompletableFuture<>();
         failed.completeExceptionally(error);
         return failed;
      }
   }

   private static CompletableFuture<Void> submitAreaPrefetch(String sourceName, Runnable task) {
      CompletableFuture<Void> future = submitLodPrefetch(task);
      future.whenComplete((ignored, error) -> {
         if (error != null && !future.isCancelled()) {
            Tellus.LOGGER.debug("Failed to prefetch exact Tellus {} tile range", sourceName, error);
         }
      });
      return future;
   }

   private static CompletableFuture<Void> submitLodPrefetch(Runnable task) {
      TellusWorldgenSources.CancellablePrefetchFuture result = new TellusWorldgenSources.CancellablePrefetchFuture();
      if (LOD_PREFETCH_EXECUTOR == null) {
         result.complete(null);
         return result;
      }

      try {
         Future<?> submitted = LOD_PREFETCH_EXECUTOR.submit(() -> {
            if (result.isCancelled()) {
               return;
            }
            try {
               task.run();
               result.complete(null);
            } catch (CancellationException error) {
               result.cancel(false);
            } catch (Throwable error) {
               result.completeExceptionally(error);
            }
         });
         result.attach(submitted);
      } catch (RejectedExecutionException error) {
         result.completeExceptionally(error);
      }
      return result;
   }

   private static ExecutorService createPrefetchExecutor() {
      if (!PREFETCH_ENABLED) {
         return null;
      } else {
         TellusWorldgenSources.ThreadBounds bounds = resolveThreadBounds(
            "tellus.prefetch.threads.min", "tellus.prefetch.threads.max", "tellus.prefetch.threads", 2, 8
         );
         int minThreads = bounds.min();
         int maxThreads = bounds.max();
         int queueSize = intProperty("tellus.prefetch.queue", 256);
         return createAdaptiveExecutor("tellus-prefetch-", minThreads, maxThreads, queueSize);
      }
   }

   private static ThreadPoolExecutor createLodPrefetchExecutor() {
      if (!PREFETCH_ENABLED) {
         return null;
      }
      TellusWorldgenSources.ThreadBounds bounds = resolveThreadBounds(
         "tellus.dhPrefetch.threads.min", "tellus.dhPrefetch.threads.max", "tellus.dhPrefetch.threads", 2, 8
      );
      int queueSize = intProperty("tellus.dhPrefetch.queue", 128);
      return createAdaptiveExecutor("tellus-dh-prefetch-", bounds.min(), bounds.max(), queueSize);
   }

   private static ExecutorService createTerrainDetailExecutor() {
      TellusWorldgenSources.ThreadBounds bounds = resolveThreadBounds(
         "tellus.terraindetail.threads.min", "tellus.terraindetail.threads.max", null, 4, 12
      );
      int queueSize = intProperty("tellus.terraindetail.queue", 1024);
      return createAdaptiveExecutor("tellus-terrain-", bounds.min(), bounds.max(), queueSize);
   }

   private static TellusWorldgenSources.AdaptiveThreadPoolExecutor createAdaptiveExecutor(String threadPrefix, int minThreads, int maxThreads, int queueSize) {
      ThreadFactory factory = new ThreadFactory() {
         private final AtomicInteger index = new AtomicInteger();

         @Override
         public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, threadPrefix + this.index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
         }
      };
      TellusWorldgenSources.AdaptiveThreadPoolExecutor executor = new TellusWorldgenSources.AdaptiveThreadPoolExecutor(
         Math.max(1, minThreads),
         Math.max(1, maxThreads),
         30L,
         TimeUnit.SECONDS,
         new ArrayBlockingQueue<>(Math.max(1, queueSize)),
         factory,
         new AbortPolicy()
      );
      executor.allowCoreThreadTimeOut(true);
      return executor;
   }

   private static TellusWorldgenSources.ThreadBounds resolveThreadBounds(
      String minKey, String maxKey, String legacyKey, int defaultMinThreads, int defaultMaxThreads
   ) {
      Integer maxOverride = intPropertyNullable(maxKey);
      Integer minOverride = intPropertyNullable(minKey);
      Integer legacyThreads = legacyKey == null ? null : intPropertyNullable(legacyKey);
      int maxThreads;
      if (maxOverride != null) {
         maxThreads = maxOverride;
      } else if (legacyThreads != null) {
         maxThreads = legacyThreads;
      } else {
         int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
         maxThreads = Math.min(defaultMaxThreads, Math.max(defaultMinThreads, cores * 2));
      }

      int minThreads = minOverride != null ? minOverride : Math.min(defaultMinThreads, maxThreads);
      minThreads = Math.max(1, Math.min(minThreads, maxThreads));
      maxThreads = Math.max(1, maxThreads);
      return new TellusWorldgenSources.ThreadBounds(minThreads, maxThreads);
   }

   private static int intProperty(String key, int defaultValue) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      } else {
         try {
            return Math.max(0, Integer.parseInt(value));
         } catch (NumberFormatException var4) {
            return defaultValue;
         }
      }
   }

   private static Integer intPropertyNullable(String key) {
      String value = System.getProperty(key);
      if (value == null) {
         return null;
      } else {
         try {
            return Integer.parseInt(value);
         } catch (NumberFormatException var3) {
            return null;
         }
      }
   }

   private static final class AdaptiveThreadPoolExecutor extends ThreadPoolExecutor {
      private final int minThreads;
      private final int maxThreads;

      private AdaptiveThreadPoolExecutor(
         int minThreads,
         int maxThreads,
         long keepAliveTime,
         TimeUnit unit,
         ArrayBlockingQueue<Runnable> workQueue,
         ThreadFactory threadFactory,
         RejectedExecutionHandler handler
      ) {
         super(minThreads, maxThreads, keepAliveTime, unit, workQueue, threadFactory, handler);
         this.minThreads = minThreads;
         this.maxThreads = maxThreads;
      }

      @Override
      public void execute(Runnable command) {
         this.maybeAdjustCore();
         super.execute(command);
      }

      private void maybeAdjustCore() {
         int queueSize = this.getQueue().size();
         int active = this.getActiveCount();
         int core = this.getCorePoolSize();
         if (queueSize > active * 2 && core < this.maxThreads) {
            int nextCore = Math.min(this.maxThreads, core + 1);
            this.setCorePoolSize(nextCore);
            this.prestartCoreThread();
         } else {
            if (queueSize == 0 && active <= this.minThreads && core > this.minThreads) {
               this.setCorePoolSize(this.minThreads);
            }
         }
      }
   }

   private record ThreadBounds(int min, int max) {
   }

   private static final class CancellablePrefetchFuture extends CompletableFuture<Void> {
      private final CopyOnWriteArrayList<Future<?>> children = new CopyOnWriteArrayList<>();

      private void attach(Future<?> child) {
         this.children.add(child);
         if (this.isCancelled()) {
            cancelChild(child, true);
         }
      }

      @Override
      public boolean cancel(boolean mayInterruptIfRunning) {
         boolean cancelled = super.cancel(mayInterruptIfRunning);
         if (cancelled) {
            for (Future<?> child : this.children) {
               cancelChild(child, mayInterruptIfRunning);
            }
         }
         return cancelled;
      }

      private static void cancelChild(Future<?> child, boolean mayInterruptIfRunning) {
         child.cancel(mayInterruptIfRunning);
         if (child instanceof Runnable runnable && LOD_PREFETCH_EXECUTOR != null) {
            LOD_PREFETCH_EXECUTOR.remove(runnable);
         }
      }
   }
}
