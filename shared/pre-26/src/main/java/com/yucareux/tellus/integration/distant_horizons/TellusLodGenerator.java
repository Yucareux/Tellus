package com.yucareux.tellus.integration.distant_horizons;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.DhApi.Delayed;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import com.yucareux.tellus.world.data.osm.BridgeSupportLayout;
import com.yucareux.tellus.world.data.osm.OsmBuildingFeature;
import com.yucareux.tellus.world.data.osm.OsmPerf;
import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.world.data.osm.OsmStreetLightFeature;
import com.yucareux.tellus.world.data.osm.RoadAreaFeature;
import com.yucareux.tellus.world.data.osm.RoadClass;
import com.yucareux.tellus.world.data.osm.RoadFeature;
import com.yucareux.tellus.world.data.osm.RoadMode;
import com.yucareux.tellus.world.data.osm.RoadSurfaceStyle;
import com.yucareux.tellus.world.data.canopy.TellusCanopyHeightSource;
import com.yucareux.tellus.world.data.resolve.ResolveEcoregion;
import com.yucareux.tellus.world.data.resolve.TellusResolveSource;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainAvailability;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainCompatibility;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.world.realtime.TellusRealtimeState;
import com.yucareux.tellus.worldgen.DhLodWaterResolver;
import com.yucareux.tellus.worldgen.EarthBiomeSource;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.EarthProjection;
import com.yucareux.tellus.worldgen.WorldProjection;
import com.yucareux.tellus.worldgen.ExperimentalHeightSupport;
import com.yucareux.tellus.worldgen.TellusWorldgenSources;
import com.yucareux.tellus.worldgen.WaterSurfaceResolver;
import com.yucareux.tellus.worldgen.building.BuildingBlueprint;
import com.yucareux.tellus.worldgen.building.BuildingProfile;
import com.yucareux.tellus.worldgen.building.BuildingPlacementSupport;
import com.yucareux.tellus.worldgen.building.TellusBuildingBlueprints;
import com.yucareux.tellus.worldgen.building.TellusBuildingLighting;
import com.yucareux.tellus.worldgen.building.TellusBuildingMaterials;
import com.yucareux.tellus.worldgen.building.TellusBuildingProfiles;
import com.yucareux.tellus.worldgen.tree.TellusProceduralTreeGenerator;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

public final class TellusLodGenerator implements IDhApiWorldGenerator {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final boolean LOD_TIMING_LOGGING = Boolean.parseBoolean(
      System.getProperty("tellus.dhLodTiming", System.getProperty("tellus.lodTiming", "false"))
   );
   private static final long LOD_TIMING_THRESHOLD_NS = TimeUnit.MILLISECONDS.toNanos(
      intProperty("tellus.dhLodTimingThresholdMs", 0, 0, 600000)
   );
   private static final int LOD_PREFETCH_BATCH_WINDOW_MS = intProperty("tellus.dhPrefetchBatchWindowMs", 2, 0, 25);
   private static final int LOD_PREFETCH_BATCH_MAX_REQUESTS = intProperty("tellus.dhPrefetchBatchMaxRequests", 4, 1, 16);
   private static final int FAST_RENDER_ULTRA_FAST_MIN_DETAIL = intProperty("tellus.dhFastRenderUltraFastMinDetail", 4, 0, 24);
   private static final int FAST_RENDER_SKIP_SHORELINE_MIN_DETAIL = intProperty("tellus.dhFastRenderSkipShorelineMinDetail", 3, 0, 24);
   private static final int ULTRA_FAST_COARSE_SAMPLE_MIN_DETAIL = intProperty(
      "tellus.dhUltraFastCoarseSampleMinDetail", LodSamplingGrid.FULL_QUALITY_MAX_DETAIL + 1, 0, 24
   );
   private static final int ULTRA_FAST_COARSE_SAMPLE_MAX_STRIDE = intProperty("tellus.dhUltraFastCoarseSampleMaxStride", 8, 1, 64);
   private static final int LOD_SURFACE_SHAPE_REFINE_MAX_DETAIL = intProperty("tellus.dhSurfaceShapeRefineMaxDetail", 6, 0, 24);
   private static final int LOD_OSM_SURFACE_MAX_DETAIL = intProperty("tellus.dhOsmSurfaceMaxDetail", 6, 0, 24);
   private static final boolean SHARED_TERRAIN_CACHE_ENABLED = Boolean.parseBoolean(
      System.getProperty("tellus.dhSharedTerrainCacheEnabled", "false")
   );
   private static final int LOD_WATER_FULL_VOLUME_MAX_DEPTH = intProperty("tellus.dhWaterFullVolumeMaxDepth", 6, 0, 64);
   private static final int LOD_WATER_SURFACE_LAYER_DEPTH = intProperty("tellus.dhWaterSurfaceLayerDepth", 1, 1, 16);
   private static final int LOD_WATER_VEGETATION_MAX_DETAIL = intProperty("tellus.dhWaterVegetationMaxDetail", 8, 0, 24);
   private static final int DH_FULL_DATA_MAX_RELATIVE_HEIGHT = 6095;
   private static final double ESA_WORLD_COVER_RESOLUTION_METERS = 10.0;
   private static final int ESA_NO_DATA = 0;
   private static final int ESA_TREE_COVER = 10;
   private static final int ESA_BUILT_UP = 50;
   private static final int ESA_WATER = 80;
   private static final int ESA_MANGROVES = 95;
   private static final double ROAD_LIGHT_BASE_SPACING_METERS = 40.0;
   private static final int ROAD_LIGHT_MIN_SPACING_BLOCKS = 8;
   private static final int ROAD_LIGHT_MIN_ROAD_WIDTH_BLOCKS = 2;
   private static final double ROAD_LIGHT_EDGE_TOLERANCE_BLOCKS = 0.55;
   private static final int ROAD_LIGHT_BLOCK_LIGHT = 15;
   private static final int RANDOM_BIOME_TREE_CHANCE = 35;
   private static final long RANDOM_BIOME_TREE_SALT = -7163147898164839021L;
   private static final int RANDOM_BIOME_PATCH_GRID_BLOCKS = 512;
   private static final long RANDOM_BIOME_LAND_PATCH_SALT = -4348849565147123417L;
   private static final long JAVA_RANDOM_MULTIPLIER = 25214903917L;
   private static final long JAVA_RANDOM_ADDEND = 11L;
   private static final long JAVA_RANDOM_MASK = 281474976710655L;
   private static final TellusLodGenerator.CanopyProfile TREE_COVER_FALLBACK_CANOPY_PROFILE = new TellusLodGenerator.CanopyProfile(
      false,
      false,
      false,
      false,
      false,
      false,
      false,
      false,
      false,
      false,
      false,
      true,
      false,
      false,
      false,
      false,
      false,
      false,
      false,
      62,
      3,
      2,
      3,
      10,
      TellusLodGenerator.TreeLodFamily.MIXED_FOREST
   );
   private static final Map<Holder<Biome>, TellusLodGenerator.CanopyProfile> CANOPY_PROFILES = new ConcurrentHashMap<>();
   private final EarthChunkGenerator generator;
   private final WorldProjection projection;
   private final EarthBiomeSource biomeSource;
   private final DhLodWaterResolver dhWaterResolver;
   private final ThreadLocal<TellusLodGenerator.WrapperCache> wrapperCache;
   private final LodPrefetchBatcher lodPrefetchBatcher;
   private final String managedTerrainKey;

   public TellusLodGenerator(IDhApiLevelWrapper levelWrapper, EarthChunkGenerator generator) {
      this.generator = generator;
      this.projection = generator.settings().projection();
      this.biomeSource = (EarthBiomeSource)generator.getBiomeSource();
      this.dhWaterResolver = new DhLodWaterResolver(generator);
      this.wrapperCache = ThreadLocal.withInitial(() -> new TellusLodGenerator.WrapperCache(levelWrapper));
      this.lodPrefetchBatcher = new LodPrefetchBatcher(
         LOD_PREFETCH_BATCH_WINDOW_MS, LOD_PREFETCH_BATCH_MAX_REQUESTS, this::startLodPrefetch
      );
      this.managedTerrainKey = ManagedTerrainAvailability.key(generator);
   }

   public static boolean isTimingEnabled() {
      return LOD_TIMING_LOGGING;
   }

   public void preGeneratorTaskStart() {
   }

   public byte getLargestDataDetailLevel() {
      return 24;
   }

   public byte getGenerationAvailability(int chunkPosMinX, int chunkPosMinZ, int widthChunks, byte targetDataDetail) {
      if (!DistantHorizonsIntegration.isDistantGenerationReady()) {
         return ManagedTerrainAvailability.WAIT;
      }
      return this.managedDownloadsActive()
         ? ManagedTerrainAvailability.availability(this.managedTerrainKey, chunkPosMinX, chunkPosMinZ, widthChunks)
         : ManagedTerrainAvailability.READY;
   }

   private boolean managedDownloadsActive() {
      return this.generator.settings().tellusManagedTerrainDownloads() && ManagedTerrainCompatibility.isGenerationGateAvailable();
   }

   public CompletableFuture<Void> generateLod(
      int chunkPosMinX,
      int chunkPosMinZ,
      int lodPosX,
      int lodPosZ,
      byte detailLevel,
      IDhApiFullDataSource pooledFullDataSource,
      EDhApiDistantGeneratorMode generatorMode,
      ExecutorService worldGeneratorThreadPool,
      Consumer<IDhApiFullDataSource> resultConsumer
   ) {
      TellusLodGenerator.CancellableLodFuture generationFuture = new TellusLodGenerator.CancellableLodFuture();
      TellusLodGenerator.LodTimingTrace timingTrace = new TellusLodGenerator.LodTimingTrace(
         chunkPosMinX,
         chunkPosMinZ,
         detailLevel,
         pooledFullDataSource.getWidthInDataColumns(),
         1 << detailLevel,
         generatorMode,
         this.generator.settings().distantHorizonsRenderMode()
      );
      boolean managedDownloads = this.managedDownloadsActive();
      try {
         CompletableFuture<Void> preparation = DistantHorizonsIntegration.distantGenerationReadyFuture().thenCompose(ignored -> {
            if (generationFuture.isCancelled()) {
               return CompletableFuture.failedFuture(new CancellationException("Tellus LOD generation cancelled before prefetch"));
            }
            timingTrace.addPhase("prefetch", 0L);
            if (managedDownloads) {
               timingTrace.note("prefetchBatchSize", 0);
               timingTrace.note("downloadOwner", "tellus");
               return CompletableFuture.completedFuture(null);
            }

            long prefetchStart = beginTimingPhase(timingTrace);
            LodPrefetchBatcher.Submission prefetchSubmission = this.prefetchLodResources(
               chunkPosMinX, chunkPosMinZ, detailLevel, pooledFullDataSource.getWidthInDataColumns()
            );
            CompletableFuture<Void> prefetchFuture = prefetchSubmission.future();
            generationFuture.attachCancellation(prefetchFuture);
            return prefetchFuture.handle((prefetchIgnored, prefetchError) -> {
               endTimingPhase(timingTrace, "prefetch", prefetchStart);
               timingTrace.note("prefetchBatchSize", prefetchSubmission.batchSize());
               if (prefetchError != null) {
                  Throwable cause = unwrapCompletionFailure(prefetchError);
                  if (isInterruptedLodGeneration(cause) || cause instanceof Error) {
                     throw propagateLodGenerationFailure(cause);
                  }
                  LOGGER.debug(
                     "Tellus exact LOD tile prefetch completed with an error; generation will use normal source fallbacks.",
                     cause
                  );
               }
               return null;
            });
         });
         generationFuture.attachCancellation(preparation);
         generationFuture.attach(preparation.thenRunAsync(generationFuture.track(() -> {
            boolean handledCancellation = false;
            try (ManagedTerrainNetworkPolicy.Scope ignored = managedDownloads ? ManagedTerrainNetworkPolicy.cacheOnly() : null) {
               this.buildLod(pooledFullDataSource, chunkPosMinX, chunkPosMinZ, detailLevel, generatorMode, timingTrace);
               timingTrace.logSuccess();
               resultConsumer.accept(pooledFullDataSource);
            } catch (Throwable throwable) {
               handledCancellation = isInterruptedLodGeneration(throwable);
               if (handledCancellation) {
                  timingTrace.logCancelled();
               } else {
                  LOGGER.warn(
                     "Tellus DH LOD generation failed at chunk=[{}, {}], lod=[{}, {}], detail={}; discarding partial output so DH can retry.",
                     chunkPosMinX,
                     chunkPosMinZ,
                     lodPosX,
                     lodPosZ,
                     Byte.toUnsignedInt(detailLevel),
                     throwable
                  );
                  timingTrace.logFailure(throwable);
               }
               throw propagateLodGenerationFailure(throwable);
            } finally {
               if (handledCancellation) {
                  Thread.interrupted();
               }
            }
         }), worldGeneratorThreadPool));
      } catch (Throwable error) {
         generationFuture.completeExceptionally(error);
      }
      return generationFuture;
   }

   private static Throwable unwrapCompletionFailure(Throwable error) {
      Throwable current = error;
      while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
         current = current.getCause();
      }
      return current;
   }

   private static boolean isInterruptedLodGeneration(Throwable throwable) {
      if (Thread.currentThread().isInterrupted()) {
         return true;
      } else {
         for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof CancellationException
               || current instanceof InterruptedException
               || current instanceof InterruptedIOException
               || current instanceof ClosedByInterruptException) {
               return true;
            }
         }

         return false;
      }
   }

   private static void throwIfLodCancelled() {
      if (Thread.currentThread().isInterrupted()) {
         throw new CancellationException("DH LOD generation interrupted");
      }
   }

   private static RuntimeException propagateLodGenerationFailure(Throwable throwable) {
      if (throwable instanceof RuntimeException runtimeException) {
         return runtimeException;
      } else if (throwable instanceof Error error) {
         throw error;
      } else {
         return new CompletionException(throwable);
      }
   }

   private static long beginTimingPhase(TellusLodGenerator.LodTimingTrace trace) {
      return trace.isEnabled() ? System.nanoTime() : 0L;
   }

   private static void endTimingPhase(TellusLodGenerator.LodTimingTrace trace, String phase, long startNanos) {
      if (trace.isEnabled()) {
         trace.addPhase(phase, System.nanoTime() - startNanos);
      }
   }

   private void buildLod(
      IDhApiFullDataSource output,
      int chunkPosMinX,
      int chunkPosMinZ,
      byte detailLevel,
      EDhApiDistantGeneratorMode generatorMode,
      TellusLodGenerator.LodTimingTrace trace
   ) {
      int detail = Byte.toUnsignedInt(detailLevel);
      trace.note("surfaceMode", this.useUltraFastLodMode(detail) ? "ultra_fast" : "detailed");
      if (this.useUltraFastLodMode(detail)) {
         this.buildUltraFastLod(output, chunkPosMinX, chunkPosMinZ, detailLevel, trace);
      } else {
         int lodSizePoints = output.getWidthInDataColumns();
         int cellSize = 1 << detailLevel;
         int cellOffset = cellSize >> 1;
         EarthGeneratorSettings settings = this.generator.settings();
         double previewResolutionMeters = lodPreviewResolutionMeters(settings, cellSize);
         boolean baseDetailedWater = settings.distantHorizonsWaterResolver() && detailLevel <= 5;
         TellusRealtimeState.PrecipitationMode precipitationMode = TellusRealtimeState.precipitationMode();
         boolean snowActive = TellusRealtimeState.isWeatherEnabled() && precipitationMode == TellusRealtimeState.PrecipitationMode.SNOW
            || TellusRealtimeState.isHistoricalSnowEnabled();
         int baseX = SectionPos.sectionToBlockCoord(chunkPosMinX);
         int baseZ = SectionPos.sectionToBlockCoord(chunkPosMinZ);
         int[] worldXs = new int[lodSizePoints];
         int[] worldZs = new int[lodSizePoints];
         TellusLodGenerator.CanopyRequestCache canopyRequestCache = new TellusLodGenerator.CanopyRequestCache(this.generator);

         for (int i = 0; i < lodSizePoints; i++) {
            worldXs[i] = baseX + i * cellSize + cellOffset;
            worldZs[i] = baseZ + i * cellSize + cellOffset;
         }

         if (shouldSkipExperimentalLodTile(settings, worldXs, worldZs, lodSizePoints, trace)) {
            return;
         }

         boolean roadsActive = this.shouldRenderDhRoads(detail);
         boolean buildingsActive = this.shouldRenderDhBuildings(detail);
         boolean preferNonBlockingOsm = settings.distantHorizonsOsmNonBlockingFetch();
         OsmQueryMode osmQueryMode = preferNonBlockingOsm ? OsmQueryMode.NON_BLOCKING : OsmQueryMode.BLOCKING;
         boolean mainRoadsOnly = roadsActive && detail == settings.distantHorizonsOsmRoadMaxDetail();
         trace.note("roads", roadsActive);
         trace.note("buildings", buildingsActive);
         trace.note("osm", osmQueryMode);
         trace.note("coverStride", coverSampleStride(detailLevel, lodSizePoints));
         trace.note("waterStride", detailedWaterStride(detailLevel, lodSizePoints));
         trace.addPhase("sample", 0L);
         trace.addPhase("sample.cover", 0L);
         trace.addPhase("sample.visualCover", 0L);
         trace.addPhase("sample.terrain", 0L);
         trace.addPhase("sample.repair", 0L);
         trace.addPhase("waterProbe", 0L);
         trace.addPhase("detailedWater", 0L);
         trace.addPhase("biomeResolve", 0L);
         trace.addPhase("buildingMask", 0L);
         trace.addPhase("roadMask", 0L);
         trace.addPhase("terrainMetrics", 0L);
         trace.addPhase("sharedTerrainCache", 0L);
         trace.addPhase("shorelineCache", 0L);
         trace.addPhase("mountainTransitionCache", 0L);
         trace.addPhase("emit", 0L);
         trace.addPhase("emit.surfaceResolve", 0L);
         trace.addPhase("emit.surfaceResolve.local", 0L);
         trace.addPhase("emit.surfaceResolve.generator", 0L);
         trace.addPhase("emit.surfaceResolve.generator.effectiveCover", 0L);
         trace.addPhase("emit.surfaceResolve.generator.surfaceCoverClass", 0L);
         trace.addPhase("emit.surfaceResolve.generator.basePalette", 0L);
         trace.addPhase("emit.surfaceResolve.generator.shoreline", 0L);
         trace.addPhase("emit.surfaceResolve.generator.shoreline.context", 0L);
         trace.addPhase("emit.surfaceResolve.generator.shoreline.classify", 0L);
         trace.addPhase("emit.surfaceResolve.generator.slopeOverride", 0L);
         trace.addPhase("emit.surfaceResolve.generator.slope.transition", 0L);
         trace.addPhase("emit.surfaceResolve.generator.slope.context", 0L);
         trace.addPhase("emit.surfaceResolve.generator.slope.rockPalette", 0L);
         trace.addPhase("emit.surfaceResolve.generator.result", 0L);
         trace.addPhase("emit.baseLayers", 0L);
         trace.addPhase("emit.canopy", 0L);
         trace.addPhase("emit.water", 0L);
         trace.addPhase("emit.features", 0L);
         trace.addPhase("emit.output", 0L);
         int minY = this.generator.getMinY();
         int maxY = dhCompatibleMaxY(minY, this.generator);
         int absoluteTop = maxY - minY;
         TellusLodGenerator.WrapperCache wrappers = this.wrapperCache.get();
         IDhApiBlockStateWrapper waterBlock = wrappers.getBlockState(Blocks.WATER.defaultBlockState());
         IDhApiBlockStateWrapper roadMainBlock = wrappers.getBlockState(Blocks.GRAY_CONCRETE.defaultBlockState());
         IDhApiBlockStateWrapper roadNormalBlock = wrappers.getBlockState(Blocks.SMOOTH_STONE.defaultBlockState());
         IDhApiBlockStateWrapper roadDirtBlock = wrappers.getBlockState(Blocks.DIRT_PATH.defaultBlockState());
         IDhApiBlockStateWrapper roadGravelBlock = wrappers.getBlockState(Blocks.GRAVEL.defaultBlockState());
         IDhApiBlockStateWrapper roadMarkingBlock = wrappers.getBlockState(Blocks.WHITE_CONCRETE.defaultBlockState());
         IDhApiBlockStateWrapper bridgeSupportShaftBlock = wrappers.getBlockState(Blocks.QUARTZ_PILLAR.defaultBlockState());
         IDhApiBlockStateWrapper bridgeSupportCapBlock = wrappers.getBlockState(Blocks.QUARTZ_BRICKS.defaultBlockState());
         IDhApiBlockStateWrapper roadLightBaseBlock = wrappers.getBlockState(Blocks.STONE_BRICK_WALL.defaultBlockState());
         IDhApiBlockStateWrapper roadLightFenceBlock = wrappers.getBlockState(Blocks.OAK_FENCE.defaultBlockState());
         IDhApiBlockStateWrapper roadLightGlowBlock = wrappers.getBlockState(Blocks.GLOWSTONE.defaultBlockState());
         IDhApiBlockStateWrapper roadLightCapBlock = wrappers.getBlockState(Blocks.SPRUCE_TRAPDOOR.defaultBlockState());
         List<DhApiTerrainDataPoint> columnDataPoints = new ArrayList<>(12);
         int coverStride = coverSampleStride(detailLevel, lodSizePoints);
         boolean allowWaterVegetation = detail <= LOD_WATER_VEGETATION_MAX_DETAIL;
         int area = lodSizePoints * lodSizePoints;
         int[] baseTerrainSurface = new int[area];
         int[] surfaceYs = new int[area];
         int[] vegetationSurfaceYs = new int[area];
         int[] waterSurfaces = new int[area];
         boolean[] underwaterFlags = new boolean[area];
         int[] coverClasses = new int[area];
         int[] visualCoverClasses = new int[area];
         IDhApiBiomeWrapper[] biomeWrappers = new IDhApiBiomeWrapper[area];
         Holder<Biome>[] biomeHolders = newBiomeHolderArray(area);
         int[] lodSlopeDiffs = new int[area];
         int[] lodConvexities = new int[area];
         BlockState lastTopState = null;
         BlockState lastFillerState = null;
         TellusLodGenerator.SurfaceWrapperPair lastSurfaceWrapper = null;
         boolean sampleVisualCover = shouldSampleVisualCover(settings, previewResolutionMeters);
         boolean useSharedTerrainCache = SHARED_TERRAIN_CACHE_ENABLED && detailLevel == 2;
         EarthChunkGenerator.LodSharedTerrainCache sharedTerrainCache = null;
         EarthChunkGenerator.LodShorelineCache shorelineCache = null;
         EarthChunkGenerator.LodMountainTransitionCache mountainTransitionCache = null;
         boolean suppressCoarseShoreline = this.shouldSuppressCoarseShoreline(detail);
         trace.note("shorelineMode", suppressCoarseShoreline ? "suppressed" : "full");
         boolean sampleTimingEnabled = trace.isEnabled();
         long sampleCoverNanos = 0L;
         long sampleVisualCoverNanos = 0L;
         long sampleTerrainNanos = 0L;
         long sampleRepairNanos = 0L;
         long phaseStart = beginTimingPhase(trace);
         if (useSharedTerrainCache) {
            sharedTerrainCache = this.generator.buildLodSharedTerrainCache(
               worldXs[0], worldXs[lodSizePoints - 1], worldZs[0], worldZs[lodSizePoints - 1], previewResolutionMeters
            );
         }
         endTimingPhase(trace, "sharedTerrainCache", phaseStart);
         trace.note("sharedTerrainCache", sharedTerrainCache == null ? "disabled" : sharedTerrainCache.mode());
         if (sharedTerrainCache != null) {
            shorelineCache = sharedTerrainCache.shorelineCache();
            mountainTransitionCache = sharedTerrainCache.mountainTransitionCache();
         }

         boolean reuseSharedPreviewCoverSamples = sharedTerrainCache != null;
         phaseStart = beginTimingPhase(trace);

         for (int baseLocalZ = 0; baseLocalZ < lodSizePoints; baseLocalZ += coverStride) {
            throwIfLodCancelled();

            for (int baseLocalX = 0; baseLocalX < lodSizePoints; baseLocalX += coverStride) {
               int sampleWorldX = worldXs[baseLocalX];
               int sampleWorldZ = worldZs[baseLocalZ];
               long samplePartStart = sampleTimingEnabled ? System.nanoTime() : 0L;
               int coverClass = reuseSharedPreviewCoverSamples
                  ? sharedTerrainCache.sampleCoverClass(sampleWorldX, sampleWorldZ)
                  : this.generator.sampleCoverClass(sampleWorldX, sampleWorldZ, previewResolutionMeters);
               if (sampleTimingEnabled) {
                  sampleCoverNanos += System.nanoTime() - samplePartStart;
                  samplePartStart = System.nanoTime();
               }
               int visualCoverClass = sampleVisualCover && !isHardRawCoverClass(coverClass)
                  ? reuseSharedPreviewCoverSamples
                     ? sharedTerrainCache.sampleVisualCoverClass(sampleWorldX, sampleWorldZ)
                     : this.generator.sampleVisualCoverClass(sampleWorldX, sampleWorldZ, coverClass, previewResolutionMeters)
                  : coverClass;
               if (sampleTimingEnabled) {
                  sampleVisualCoverNanos += System.nanoTime() - samplePartStart;
               }

               for (int dz = 0; dz < coverStride; dz++) {
                  int localZ = baseLocalZ + dz;
                  if (localZ < lodSizePoints) {
                     int worldZ = worldZs[localZ];

                     for (int dx = 0; dx < coverStride; dx++) {
                        int localX = baseLocalX + dx;
                        if (localX < lodSizePoints) {
                           int worldX = worldXs[localX];
                           int index = localZ * lodSizePoints + localX;
                           samplePartStart = sampleTimingEnabled ? System.nanoTime() : 0L;
                           baseTerrainSurface[index] = this.generator.resolveLodTerrainSurface(worldX, worldZ, coverClass, previewResolutionMeters);
                           if (sampleTimingEnabled) {
                              sampleTerrainNanos += System.nanoTime() - samplePartStart;
                           }
                           coverClasses[index] = coverClass;
                           visualCoverClasses[index] = visualCoverClass;
                        }
                     }
                  }
               }
            }
         }
         long sampleRepairStart = sampleTimingEnabled ? System.nanoTime() : 0L;
         this.generator.repairLodTerrainSurfaceGrid(baseTerrainSurface, coverClasses, lodSizePoints);
         if (sampleTimingEnabled) {
            sampleRepairNanos += System.nanoTime() - sampleRepairStart;
         }
         endTimingPhase(trace, "sample", phaseStart);
         trace.addPhase("sample.cover", sampleCoverNanos);
         trace.addPhase("sample.visualCover", sampleVisualCoverNanos);
         trace.addPhase("sample.terrain", sampleTerrainNanos);
         trace.addPhase("sample.repair", sampleRepairNanos);
         trace.note("detailedWater", baseDetailedWater);
         phaseStart = beginTimingPhase(trace);
         DhLodWaterResolver.AreaResult waterArea = this.dhWaterResolver.resolveArea(
            baseX,
            baseZ,
            lodSizePoints,
            cellSize,
            worldXs,
            worldZs,
            baseTerrainSurface,
            coverClasses,
            baseDetailedWater
         );
         endTimingPhase(trace, "detailedWater", phaseStart);

         int[] resolvedTerrainSurface = waterArea.terrainSurface();
         int[] resolvedWaterSurface = waterArea.waterSurface();
        boolean[] resolvedHasWater = waterArea.hasWater();
        boolean[] resolvedOcean = waterArea.ocean();

         phaseStart = beginTimingPhase(trace);
         for (int localZ = 0; localZ < lodSizePoints; localZ++) {
            throwIfLodCancelled();
            int worldZ = worldZs[localZ];
            int row = localZ * lodSizePoints;

            for (int localX = 0; localX < lodSizePoints; localX++) {
               int index = row + localX;
               int worldX = worldXs[localX];
               int surfaceY = Mth.clamp(resolvedTerrainSurface[index], minY, maxY - 1);
               int waterSurface = Mth.clamp(resolvedWaterSurface[index], minY, maxY - 1);
               boolean hasWater = resolvedHasWater[index];
               boolean isOcean = resolvedOcean[index];
               int terrainCoverClass = this.generator.resolveDryOsmTerrainCoverClass(
                  worldX, worldZ, coverClasses[index], hasWater
               );
               if (terrainCoverClass != coverClasses[index]) {
                  coverClasses[index] = terrainCoverClass;
                  visualCoverClasses[index] = terrainCoverClass;
               }
               int vegetationSurface = surfaceY;
               surfaceYs[index] = surfaceY;
               vegetationSurfaceYs[index] = Mth.clamp(vegetationSurface, minY, maxY - 1);
               waterSurfaces[index] = waterSurface;
               underwaterFlags[index] = hasWater && waterSurface > surfaceY;
               Holder<Biome> biomeHolder = this.biomeSource
                  .getLodBiomeAtBlock(
                     worldX, worldZ, coverClasses[index], visualCoverClasses[index], hasWater, isOcean, previewResolutionMeters
                  );
               biomeHolders[index] = biomeHolder;
               biomeWrappers[index] = wrappers.getBiome(biomeHolder);
            }
         }
         endTimingPhase(trace, "biomeResolve", phaseStart);

         phaseStart = beginTimingPhase(trace);
         TellusLodGenerator.LodBuildingMaskResult buildingMaskResult;
         if (buildingsActive) {
            try {
               buildingMaskResult = this.buildLodBuildingMask(worldXs, worldZs, surfaceYs, biomeHolders, lodSizePoints, cellSize, osmQueryMode);
            } catch (Throwable throwable) {
               if (isInterruptedLodGeneration(throwable) || throwable instanceof Error) {
                  throw propagateLodGenerationFailure(throwable);
               }

               LOGGER.warn(
                  "Tellus DH building LOD mask failed at chunk=[{}, {}], detail={}; rendering this LOD tile without building features.",
                  chunkPosMinX,
                  chunkPosMinZ,
                  Byte.toUnsignedInt(detailLevel),
                  throwable
               );
               buildingMaskResult = new TellusLodGenerator.LodBuildingMaskResult(null, null, false);
            }
         } else {
            buildingMaskResult = new TellusLodGenerator.LodBuildingMaskResult(null, null, false);
         }
         endTimingPhase(trace, "buildingMask", phaseStart);

         TellusLodGenerator.LodBuildingColumn[] buildingColumns = buildingMaskResult.columns();
         phaseStart = beginTimingPhase(trace);
         TellusLodGenerator.LodRoadMaskResult roadMaskResult;
         if (roadsActive) {
            try {
               roadMaskResult = this.buildLodRoadClassMask(
                  worldXs, worldZs, surfaceYs, lodSizePoints, cellSize, mainRoadsOnly, osmQueryMode, buildingColumns
               );
            } catch (Throwable throwable) {
               if (isInterruptedLodGeneration(throwable) || throwable instanceof Error) {
                  throw propagateLodGenerationFailure(throwable);
               }

               LOGGER.warn(
                  "Tellus DH road LOD mask failed at chunk=[{}, {}], detail={}; rendering this LOD tile without road features.",
                  chunkPosMinX,
                  chunkPosMinZ,
                  Byte.toUnsignedInt(detailLevel),
                  throwable
               );
               roadMaskResult = new TellusLodGenerator.LodRoadMaskResult(null, null, null, null, null, null, null, null, false);
            }
         } else {
            roadMaskResult = new TellusLodGenerator.LodRoadMaskResult(null, null, null, null, null, null, null, null, false);
         }
         endTimingPhase(trace, "roadMask", phaseStart);
         phaseStart = beginTimingPhase(trace);
         for (int localZ = 0; localZ < lodSizePoints; localZ++) {
            int row = localZ * lodSizePoints;

            for (int localX = 0; localX < lodSizePoints; localX++) {
               int index = row + localX;
               lodSlopeDiffs[index] = lodSlopeDiff(surfaceYs, lodSizePoints, localX, localZ, cellSize);
               lodConvexities[index] = lodConvexity(surfaceYs, lodSizePoints, localX, localZ, cellSize);
            }
         }
         endTimingPhase(trace, "terrainMetrics", phaseStart);
         if (cellSize > 4 && detail <= LOD_SURFACE_SHAPE_REFINE_MAX_DETAIL) {
            phaseStart = beginTimingPhase(trace);
            for (int localZ = 0; localZ < lodSizePoints; localZ++) {
               int worldZ = worldZs[localZ];
               int row = localZ * lodSizePoints;
               for (int localX = 0; localX < lodSizePoints; localX++) {
                  int index = row + localX;
                  if (this.generator
                     .shouldRefineLodSurfaceShape(
                        coverClasses[index], visualCoverClasses[index], surfaceYs[index], lodSlopeDiffs[index], lodConvexities[index]
                     )) {
                     long packed = this.generator.sampleLodSurfaceShape(worldXs[localX], worldZ, previewResolutionMeters);
                     lodSlopeDiffs[index] = (int)(packed >> 32);
                     lodConvexities[index] = (int)packed;
                  }
               }
            }
            endTimingPhase(trace, "surfaceShapeRefine", phaseStart);
         }
         if (shorelineCache == null) {
            phaseStart = beginTimingPhase(trace);
            shorelineCache = this.generator.buildLodShorelineCache(
               worldXs[0], worldXs[lodSizePoints - 1], worldZs[0], worldZs[lodSizePoints - 1], previewResolutionMeters
            );
            endTimingPhase(trace, "shorelineCache", phaseStart);
         }
         trace.note("shorelineCache", shorelineCache.mode());
         if (mountainTransitionCache == null) {
            phaseStart = beginTimingPhase(trace);
            mountainTransitionCache = this.generator.buildLodMountainTransitionCache(
               worldXs[0], worldXs[lodSizePoints - 1], worldZs[0], worldZs[lodSizePoints - 1], previewResolutionMeters
            );
            endTimingPhase(trace, "mountainTransitionCache", phaseStart);
         }
         trace.note("mountainTransitionCache", mountainTransitionCache == null ? "disabled" : mountainTransitionCache.mode());

         byte[] roadClassMask = roadMaskResult.mask();
         byte[] roadStyleMask = roadMaskResult.styleMask();
         boolean[] roadMarkingMask = roadMaskResult.markingMask();
         int[] roadBridgeDeckYMask = roadMaskResult.bridgeDeckY();
         int[] bridgeSupportShaftBottomMask = roadMaskResult.bridgeSupportShaftBottomY();
         int[] bridgeSupportShaftTopMask = roadMaskResult.bridgeSupportShaftTopY();
         int[] bridgeSupportCapBottomMask = roadMaskResult.bridgeSupportCapBottomY();
         int[] bridgeSupportCapTopMask = roadMaskResult.bridgeSupportCapTopY();
         int[] roadLightBaseYMask = roadMaskResult.roadLightBaseY();
         byte[] roadLightFenceCountMask = roadMaskResult.roadLightFenceCount();
         int[] buildingFlattenedSurface = buildingMaskResult.flattenedSurface();
         boolean emitTimingEnabled = trace.isEnabled();
         long emitSurfaceResolveNanos = 0L;
         long emitSurfaceResolveLocalNanos = 0L;
         long emitSurfaceResolveGeneratorNanos = 0L;
         long emitBaseLayersNanos = 0L;
         long emitCanopyNanos = 0L;
         long emitWaterNanos = 0L;
         long emitFeaturesNanos = 0L;
         long emitOutputNanos = 0L;
         int emitColumns = 0;
         int emitPoints = 0;
         int emitMaxPoints = 0;
         int emitColumnsOverInitialCapacity = 0;
         int emitBuildingColumns = 0;
         int emitRoadColumns = 0;
         int emitBridgeRoadColumns = 0;
         int emitBridgeSupportColumns = 0;
         int emitUnderwaterColumns = 0;
         int emitCanopyColumns = 0;
         int emitBadlandsColumns = 0;
         TellusLodGenerator.LodSurfaceResolveProfiler surfaceResolveProfiler = emitTimingEnabled
            ? new TellusLodGenerator.LodSurfaceResolveProfiler()
            : null;
         phaseStart = beginTimingPhase(trace);
         this.generator.setLodMountainTransitionCache(mountainTransitionCache);
         this.generator.setLodShorelineOverrideSuppressed(suppressCoarseShoreline);

         try {
            for (int localZ = 0; localZ < lodSizePoints; localZ++) {
               throwIfLodCancelled();
               int worldZ = worldZs[localZ];

               for (int localXx = 0; localXx < lodSizePoints; localXx++) {
                  int worldX = worldXs[localXx];
                  int index = localZ * lodSizePoints + localXx;
                  int surfaceY = surfaceYs[index];
                  int vegetationSurfaceY = vegetationSurfaceYs[index];
                  int waterSurface = waterSurfaces[index];
                  boolean underwater = underwaterFlags[index];
                  int coverClass = coverClasses[index];
                  int visualCoverClass = visualCoverClasses[index];
                  Holder<Biome> biomeHolder = biomeHolders[index];
                  IDhApiBiomeWrapper biome = biomeWrappers[index];
                  TellusLodGenerator.CanopyProfile biomeCanopyProfile = canopyProfile(biomeHolder);
                  TellusLodGenerator.CanopyProfile canopyProfile = resolveTreeCoverCanopyProfile(biomeCanopyProfile, coverClass);
                  boolean isMangrove = canopyProfile.isMangrove() || coverClass == ESA_MANGROVES;
                  TellusLodGenerator.LodBuildingColumn buildingColumn = buildingColumns == null ? null : buildingColumns[index];
                  boolean hasBuilding = buildingColumn != null;
                  int originalSurfaceY = surfaceY;
                  if (hasBuilding) {
                     int flattenedSurface = buildingFlattenedSurface == null ? Integer.MIN_VALUE : buildingFlattenedSurface[index];
                     if (flattenedSurface != Integer.MIN_VALUE) {
                        surfaceY = Mth.clamp(flattenedSurface, minY, maxY - 1);
                        vegetationSurfaceY = surfaceY;
                        waterSurface = surfaceY;
                        underwater = false;
                     }
                  }

                  long columnPhaseStart = emitTimingEnabled ? System.nanoTime() : 0L;
                  boolean reusePrecomputedSurface = !hasBuilding || surfaceY == originalSurfaceY;
                  long generatorSurfaceResolveNanos = 0L;
                  EarthChunkGenerator.LodSurface lodSurface;
                  if (reusePrecomputedSurface) {
                     if (emitTimingEnabled) {
                        long generatorStart = System.nanoTime();
                        lodSurface = this.generator.resolveLodSurface(
                           biomeHolder,
                           worldX,
                           worldZ,
                           surfaceY,
                           underwater,
                           coverClass,
                           visualCoverClass,
                           lodSlopeDiffs[index],
                           lodConvexities[index],
                           surfaceResolveProfiler,
                           shorelineCache,
                           osmQueryMode
                        );
                        generatorSurfaceResolveNanos = System.nanoTime() - generatorStart;
                     } else {
                        lodSurface = this.generator.resolveLodSurface(
                           biomeHolder,
                           worldX,
                           worldZ,
                           surfaceY,
                           underwater,
                           coverClass,
                           visualCoverClass,
                           lodSlopeDiffs[index],
                           lodConvexities[index],
                           shorelineCache,
                           osmQueryMode
                        );
                     }
                  } else if (emitTimingEnabled) {
                     long generatorStart = System.nanoTime();
                     lodSurface = this.generator.resolveLodSurface(
                        biomeHolder, worldX, worldZ, surfaceY, underwater, coverClass, visualCoverClass, surfaceResolveProfiler, shorelineCache, osmQueryMode
                     );
                     generatorSurfaceResolveNanos = System.nanoTime() - generatorStart;
                  } else {
                     lodSurface = this.generator.resolveLodSurface(
                        biomeHolder, worldX, worldZ, surfaceY, underwater, coverClass, visualCoverClass, shorelineCache, osmQueryMode
                     );
                  }

                  BlockState topState = lodSurface.top();
                  BlockState fillerState = lodSurface.filler();
                  TellusLodGenerator.SurfaceWrapperPair surfaceWrapper;
                  if (topState == lastTopState && fillerState == lastFillerState && lastSurfaceWrapper != null) {
                     surfaceWrapper = lastSurfaceWrapper;
                  } else {
                     surfaceWrapper = new TellusLodGenerator.SurfaceWrapperPair(
                        wrappers.getBlockState(topState), wrappers.getBlockState(fillerState)
                     );
                     lastTopState = topState;
                     lastFillerState = fillerState;
                     lastSurfaceWrapper = surfaceWrapper;
                  }

                  IDhApiBlockStateWrapper fillerBlock = surfaceWrapper.filler();
                  IDhApiBlockStateWrapper topBlock = surfaceWrapper.top();
                  int roadClassId = roadClassMask == null ? 0 : roadClassMask[index];
                  boolean hasRoad = roadClassId > 0 && !hasBuilding;
                  int bridgeSupportShaftBottomY = bridgeSupportShaftBottomMask == null ? Integer.MIN_VALUE : bridgeSupportShaftBottomMask[index];
                  int bridgeSupportShaftTopY = bridgeSupportShaftTopMask == null ? Integer.MIN_VALUE : bridgeSupportShaftTopMask[index];
                  int bridgeSupportCapBottomY = bridgeSupportCapBottomMask == null ? Integer.MIN_VALUE : bridgeSupportCapBottomMask[index];
                  int bridgeSupportCapTopY = bridgeSupportCapTopMask == null ? Integer.MIN_VALUE : bridgeSupportCapTopMask[index];
                  boolean hasBridgeShaft = bridgeSupportShaftBottomY != Integer.MIN_VALUE && bridgeSupportShaftTopY != Integer.MIN_VALUE;
                  boolean hasBridgeCap = bridgeSupportCapBottomY != Integer.MIN_VALUE && bridgeSupportCapTopY != Integer.MIN_VALUE;
                  boolean hasBridgeSupport = !hasBuilding && hasBridgeCap;
                  int roadLightBaseY = roadLightBaseYMask == null ? Integer.MIN_VALUE : roadLightBaseYMask[index];
                  IDhApiBlockStateWrapper roadBlock = null;
                  int bridgeDeckY = Integer.MIN_VALUE;
                  boolean bridgeRoad = false;
                  if (hasRoad) {
                     roadBlock = lodRoadBlockForStyle(
                        roadClassId,
                        roadStyleMask == null ? 0 : roadStyleMask[index],
                        roadMarkingMask != null && roadMarkingMask[index],
                        roadMainBlock,
                        roadNormalBlock,
                        roadDirtBlock,
                        roadGravelBlock,
                        roadMarkingBlock
                     );
                     int bridgeDeckCandidate = roadBridgeDeckYMask == null ? Integer.MIN_VALUE : roadBridgeDeckYMask[index];
                     if (bridgeDeckCandidate != Integer.MIN_VALUE && bridgeDeckCandidate > surfaceY) {
                        bridgeDeckY = Mth.clamp(bridgeDeckCandidate, minY, maxY - 1);
                        bridgeRoad = true;
                     } else {
                        topBlock = roadBlock;
                        if (underwater) {
                           surfaceY = Math.max(surfaceY, waterSurface);
                           vegetationSurfaceY = Math.min(vegetationSurfaceY, surfaceY);
                           underwater = false;
                        }
                     }
                  }

                  if (bridgeRoad) {
                     topBlock = surfaceWrapper.top();
                     if (surfaceY > bridgeDeckY) {
                        bridgeRoad = false;
                        topBlock = roadBlock;
                     }
                  }

                  boolean snowColoredLodColumn = false;
                  if (!hasRoad
                     && !hasBuilding
                     && !underwater
                     && snowActive
                     && TellusRealtimeState.shouldApplySnow(worldX, worldZ)
                     && this.generator.shouldPlaceSnowAt(worldX, worldZ)) {
                     topBlock = wrappers.getBlockState(Blocks.SNOW_BLOCK.defaultBlockState());
                     snowColoredLodColumn = true;
                  } else if (!hasRoad && !hasBuilding && !underwater && topState.is(Blocks.SNOW_BLOCK)) {
                     snowColoredLodColumn = true;
                  }

                  int slopeDiff = lodSlopeDiffs[index];
                  boolean useBadlandsBands = !underwater
                     && !hasRoad
                     && !hasBuilding
                     && !hasBridgeSupport
                     && !snowColoredLodColumn
                     && slopeDiff >= 3
                     && biomeHolder.is(BiomeTags.IS_BADLANDS);
                  if (emitTimingEnabled) {
                     long totalSurfaceResolveNanos = System.nanoTime() - columnPhaseStart;
                     emitSurfaceResolveNanos += totalSurfaceResolveNanos;
                     emitSurfaceResolveGeneratorNanos += generatorSurfaceResolveNanos;
                     emitSurfaceResolveLocalNanos += Math.max(0L, totalSurfaceResolveNanos - generatorSurfaceResolveNanos);
                  }

                  emitColumns++;
                  if (hasBuilding) {
                     emitBuildingColumns++;
                  }

                  if (hasRoad) {
                     emitRoadColumns++;
                  }

                  if (bridgeRoad) {
                     emitBridgeRoadColumns++;
                  }

                  if (hasBridgeSupport) {
                     emitBridgeSupportColumns++;
                  }

                  if (underwater) {
                     emitUnderwaterColumns++;
                  }

                  if (useBadlandsBands) {
                     emitBadlandsColumns++;
                  }

                  int lastLayerTop = 0;
                  int surfaceTop = toLayerTop(surfaceY, minY, absoluteTop);
                  int topLayerBase = Math.max(0, surfaceTop - 1);
                  columnPhaseStart = emitTimingEnabled ? System.nanoTime() : 0L;
                  if (useBadlandsBands) {
                     int bandDepth = Math.min(this.generator.resolveBadlandsBandDepth(slopeDiff), surfaceY - minY + 1);
                     int bandBottomY = Math.max(minY, surfaceY - bandDepth + 1);
                     int bandBottomLayer = toLayerTop(bandBottomY, minY, absoluteTop);
                     if (bandBottomLayer > lastLayerTop) {
                        columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, bandBottomLayer, fillerBlock, biome));
                        lastLayerTop = bandBottomLayer;
                     }

                     while (lastLayerTop < topLayerBase) {
                        int bandY = minY + lastLayerTop;
                        BlockState bandState = this.generator.resolveBadlandsBandBlock(worldX, worldZ, bandY);
                        int segmentTop = lastLayerTop + 1;
                        while (segmentTop < topLayerBase
                           && bandState.equals(this.generator.resolveBadlandsBandBlock(worldX, worldZ, minY + segmentTop))) {
                           segmentTop++;
                        }
                        IDhApiBlockStateWrapper bandBlock = wrappers.getBlockState(bandState);
                        columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, segmentTop, bandBlock, biome));
                        lastLayerTop = segmentTop;
                     }
                  } else {
                     lastLayerTop = appendSealedLodBaseColumn(columnDataPoints, lastLayerTop, topLayerBase, fillerBlock, biome);
                  }

                  if (surfaceTop > lastLayerTop) {
                     columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, surfaceTop, topBlock, biome));
                     lastLayerTop = surfaceTop;
                  }

                  if (emitTimingEnabled) {
                     emitBaseLayersNanos += System.nanoTime() - columnPhaseStart;
                  }

                  boolean allowCanopy = !hasRoad
                     && !hasBuilding
                     && !hasBridgeSupport
                     && shouldAllowCanopy(this.generator.settings(), coverClass, canopyProfile, worldX, worldZ, underwater, isMangrove, this.generator.worldSeed());
                  IDhApiBiomeWrapper canopyLeafBiome = resolveCanopyLeafBiome(
                     wrappers, biome, canopyProfile, coverClass
                  );
                  columnPhaseStart = emitTimingEnabled ? System.nanoTime() : 0L;
                  TellusLodGenerator.CanopyColumn canopyColumn = allowCanopy
                     ? resolveCanopyColumn(
                        canopyProfile,
                        settings.customTrees(),
                        settings.hugeRedMushrooms(),
                        biomeHolder,
                        worldX,
                        worldZ,
                        cellSize,
                        settings.worldScale(),
                        previewResolutionMeters,
                        this.generator.worldSeed(),
                        canopyRequestCache
                     )
                     : null;
                  if (canopyColumn != null) {
                     emitCanopyColumns++;
                  }

                  boolean deferMangroveCanopy = isMangrove && underwater;
                  if (!deferMangroveCanopy) {
                     lastLayerTop = appendCanopyColumn(canopyColumn, lastLayerTop, minY, absoluteTop, wrappers, biome, canopyLeafBiome, columnDataPoints);
                  }

                  if (emitTimingEnabled) {
                     emitCanopyNanos += System.nanoTime() - columnPhaseStart;
                  }

                  columnPhaseStart = emitTimingEnabled ? System.nanoTime() : 0L;
                  if (underwater && !hasBridgeSupport) {
                     int waterTop = toLayerTop(waterSurface, minY, absoluteTop);
                     if (waterTop > lastLayerTop) {
                        lastLayerTop = appendLodWaterColumn(
                           lastLayerTop,
                           waterTop,
                           minY,
                           absoluteTop,
                           waterSurface,
                           vegetationSurfaceY,
                           allowWaterVegetation,
                           canopyProfile,
                           worldX,
                           worldZ,
                           waterBlock,
                           wrappers.airBlock(),
                           wrappers,
                           biome,
                           columnDataPoints
                        );
                     }
                  }

                  if (emitTimingEnabled) {
                     emitWaterNanos += System.nanoTime() - columnPhaseStart;
                  }

                  if (deferMangroveCanopy) {
                     columnPhaseStart = emitTimingEnabled ? System.nanoTime() : 0L;
                     lastLayerTop = appendCanopyColumn(canopyColumn, lastLayerTop, minY, absoluteTop, wrappers, biome, canopyLeafBiome, columnDataPoints);
                     if (emitTimingEnabled) {
                        emitCanopyNanos += System.nanoTime() - columnPhaseStart;
                     }
                  }

                  columnPhaseStart = emitTimingEnabled ? System.nanoTime() : 0L;
                  if (hasBridgeSupport) {
                     if (hasBridgeShaft) {
                        int shaftBottomTop = toLayerTop(bridgeSupportShaftBottomY - 1, minY, absoluteTop);
                        int shaftTopTop = toLayerTop(bridgeSupportShaftTopY, minY, absoluteTop);
                        if (shaftBottomTop > lastLayerTop) {
                           columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, shaftBottomTop, wrappers.airBlock(), biome));
                           lastLayerTop = shaftBottomTop;
                        }

                        if (shaftTopTop > lastLayerTop) {
                           columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, shaftTopTop, bridgeSupportShaftBlock, biome));
                           lastLayerTop = shaftTopTop;
                        }
                     }

                     int capBottomTop = toLayerTop(bridgeSupportCapBottomY - 1, minY, absoluteTop);
                     int capTopTop = toLayerTop(bridgeSupportCapTopY, minY, absoluteTop);
                     if (capBottomTop > lastLayerTop) {
                        columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, capBottomTop, wrappers.airBlock(), biome));
                        lastLayerTop = capBottomTop;
                     }

                     if (capTopTop > lastLayerTop) {
                        columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, capTopTop, bridgeSupportCapBlock, biome));
                        lastLayerTop = capTopTop;
                     }
                  }

                  if (bridgeRoad && roadBlock != null) {
                     int bridgeDeckTop = toLayerTop(bridgeDeckY, minY, absoluteTop);
                     if (bridgeDeckTop > lastLayerTop) {
                        int deckBaseTop = Math.max(lastLayerTop, bridgeDeckTop - 1);
                        if (deckBaseTop > lastLayerTop) {
                           columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, deckBaseTop, wrappers.airBlock(), biome));
                           lastLayerTop = deckBaseTop;
                        }

                        columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, bridgeDeckTop, roadBlock, biome));
                        lastLayerTop = bridgeDeckTop;
                     }
                  }

                  if (buildingColumn != null) {
                     lastLayerTop = appendBuildingColumn(buildingColumn, lastLayerTop, minY, absoluteTop, wrappers, biome, columnDataPoints);
                  }

                  if (roadLightBaseY != Integer.MIN_VALUE && !hasBuilding && !hasBridgeSupport) {
                     int roadLightFenceCount = roadLightFenceCountMask == null ? 0 : Byte.toUnsignedInt(roadLightFenceCountMask[index]);
                     lastLayerTop = appendRoadLightColumn(
                        roadLightBaseY,
                        roadLightFenceCount,
                        lastLayerTop,
                        minY,
                        absoluteTop,
                        roadLightBaseBlock,
                        roadLightFenceBlock,
                        roadLightGlowBlock,
                        roadLightCapBlock,
                        biome,
                        columnDataPoints
                     );
                  }

                  if (lastLayerTop < absoluteTop) {
                     columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, absoluteTop, wrappers.airBlock(), biome));
                  }

                  if (emitTimingEnabled) {
                     emitFeaturesNanos += System.nanoTime() - columnPhaseStart;
                  }

                  int columnPointCount = columnDataPoints.size();
                  emitPoints += columnPointCount;
                  emitMaxPoints = Math.max(emitMaxPoints, columnPointCount);
                  if (columnPointCount > 12) {
                     emitColumnsOverInitialCapacity++;
                  }

                  columnPhaseStart = emitTimingEnabled ? System.nanoTime() : 0L;
                  output.setApiDataPointColumn(localXx, localZ, columnDataPoints);
                  columnDataPoints.clear();
                  if (emitTimingEnabled) {
                     emitOutputNanos += System.nanoTime() - columnPhaseStart;
                  }
               }
            }
         } finally {
            this.generator.clearLodMountainTransitionCache();
            this.generator.clearLodShorelineOverrideSuppressed();
         }
         endTimingPhase(trace, "emit", phaseStart);
         trace.addPhase("emit.surfaceResolve", emitSurfaceResolveNanos);
         trace.addPhase("emit.surfaceResolve.local", emitSurfaceResolveLocalNanos);
         trace.addPhase("emit.surfaceResolve.generator", emitSurfaceResolveGeneratorNanos);
         if (surfaceResolveProfiler != null) {
            surfaceResolveProfiler.flushTo(trace);
         }
         trace.addPhase("emit.baseLayers", emitBaseLayersNanos);
         trace.addPhase("emit.canopy", emitCanopyNanos);
         trace.addPhase("emit.water", emitWaterNanos);
         trace.addPhase("emit.features", emitFeaturesNanos);
         trace.addPhase("emit.output", emitOutputNanos);
         trace.stat("emit.columns", emitColumns);
         trace.stat("emit.points", emitPoints);
         trace.stat("emit.avgPointsPerColumn", emitColumns == 0 ? "0.00" : String.format(Locale.ROOT, "%.2f", (double)emitPoints / (double)emitColumns));
         trace.stat("emit.maxPointsPerColumn", emitMaxPoints);
         trace.stat("emit.columnsOverInitialCapacity", emitColumnsOverInitialCapacity);
         trace.stat("emit.underwaterColumns", emitUnderwaterColumns);
         trace.stat("emit.canopyColumns", emitCanopyColumns);
         trace.stat("emit.buildingColumns", emitBuildingColumns);
         trace.stat("emit.roadColumns", emitRoadColumns);
         trace.stat("emit.bridgeRoadColumns", emitBridgeRoadColumns);
         trace.stat("emit.bridgeSupportColumns", emitBridgeSupportColumns);
         trace.stat("emit.badlandsColumns", emitBadlandsColumns);
      }
   }

   private void buildUltraFastLod(
      IDhApiFullDataSource output, int chunkPosMinX, int chunkPosMinZ, byte detailLevel, TellusLodGenerator.LodTimingTrace trace
   ) {
      int detail = Byte.toUnsignedInt(detailLevel);
      int outputSizePoints = output.getWidthInDataColumns();
      int outputCellSize = 1 << detailLevel;
      int sampleStride = LodSamplingGrid.strideForDetail(
         detail, ULTRA_FAST_COARSE_SAMPLE_MIN_DETAIL, ULTRA_FAST_COARSE_SAMPLE_MAX_STRIDE, outputSizePoints
      );
      int lodSizePoints = LodSamplingGrid.sampleWidth(outputSizePoints, sampleStride);
      int cellSize = outputCellSize * sampleStride;
      int cellOffset = cellSize >> 1;
      int baseX = SectionPos.sectionToBlockCoord(chunkPosMinX);
      int baseZ = SectionPos.sectionToBlockCoord(chunkPosMinZ);
      int minY = this.generator.getMinY();
      EarthGeneratorSettings settings = this.generator.settings();
      int maxY = dhCompatibleMaxY(minY, this.generator);
      int absoluteTop = maxY - minY;
      TellusLodGenerator.WrapperCache wrappers = this.wrapperCache.get();
      IDhApiBlockStateWrapper snowTopBlock = wrappers.getBlockState(Blocks.SNOW_BLOCK.defaultBlockState());
      IDhApiBlockStateWrapper waterBlock = wrappers.getBlockState(Blocks.WATER.defaultBlockState());
      IDhApiBlockStateWrapper roadMainBlock = wrappers.getBlockState(Blocks.GRAY_CONCRETE.defaultBlockState());
      IDhApiBlockStateWrapper roadNormalBlock = wrappers.getBlockState(Blocks.SMOOTH_STONE.defaultBlockState());
      IDhApiBlockStateWrapper roadDirtBlock = wrappers.getBlockState(Blocks.DIRT_PATH.defaultBlockState());
      IDhApiBlockStateWrapper roadGravelBlock = wrappers.getBlockState(Blocks.GRAVEL.defaultBlockState());
      IDhApiBlockStateWrapper roadMarkingBlock = wrappers.getBlockState(Blocks.WHITE_CONCRETE.defaultBlockState());
      IDhApiBlockStateWrapper airBlock = wrappers.airBlock();
      List<DhApiTerrainDataPoint> columnDataPoints = new ArrayList<>(8);
      double previewResolutionMeters = lodPreviewResolutionMeters(settings, cellSize);
      boolean roadsActive = this.shouldRenderDhRoads(detail);
      boolean buildingsActive = this.shouldRenderDhBuildings(detail);
      boolean baseDetailedWater = settings.distantHorizonsWaterResolver()
         && detailLevel <= 5
         && settings.distantHorizonsRenderMode() != EarthGeneratorSettings.DistantHorizonsRenderMode.ULTRA_FAST;
      boolean allowWaterVegetation = detail <= LOD_WATER_VEGETATION_MAX_DETAIL;
      boolean preferNonBlockingOsm = settings.distantHorizonsOsmNonBlockingFetch();
      OsmQueryMode osmQueryMode = preferNonBlockingOsm ? OsmQueryMode.NON_BLOCKING : OsmQueryMode.BLOCKING;
      boolean mainRoadsOnly = roadsActive && detail == settings.distantHorizonsOsmRoadMaxDetail();
      TellusRealtimeState.PrecipitationMode precipitationMode = TellusRealtimeState.precipitationMode();
      boolean snowActive = TellusRealtimeState.isWeatherEnabled() && precipitationMode == TellusRealtimeState.PrecipitationMode.SNOW
         || TellusRealtimeState.isHistoricalSnowEnabled();
      trace.note("roads", roadsActive);
      trace.note("buildings", buildingsActive);
      trace.note("coarseSampleStride", sampleStride);
      trace.note("coarseSampleGrid", lodSizePoints + "x" + lodSizePoints);
      trace.note("detailedWater", false);
      trace.note("osm", roadsActive || buildingsActive || settings.enableWater() ? osmQueryMode : "DISABLED");
      trace.addPhase("sample", 0L);
      trace.addPhase("sample.cover", 0L);
      trace.addPhase("sample.visualCover", 0L);
      trace.addPhase("sample.terrain", 0L);
      trace.addPhase("sample.repair", 0L);
      trace.addPhase("waterProbe", 0L);
      trace.addPhase("detailedWater", 0L);
      trace.addPhase("biomeResolve", 0L);
      trace.addPhase("buildingMask", 0L);
      trace.addPhase("roadMask", 0L);
      trace.addPhase("terrainMetrics", 0L);
      trace.addPhase("emit", 0L);
      trace.addPhase("emit.classify", 0L);
      trace.addPhase("emit.baseLayers", 0L);
      trace.addPhase("emit.canopy", 0L);
      trace.addPhase("emit.features", 0L);
      trace.addPhase("emit.output", 0L);
      boolean sampleVisualCover = shouldSampleVisualCover(settings, previewResolutionMeters);
      int area = lodSizePoints * lodSizePoints;
      int[] worldXs = new int[lodSizePoints];
      int[] worldZs = new int[lodSizePoints];
      TellusLodGenerator.CanopyRequestCache canopyRequestCache = new TellusLodGenerator.CanopyRequestCache(this.generator);

      for (int i = 0; i < lodSizePoints; i++) {
         worldXs[i] = baseX + i * cellSize + cellOffset;
         worldZs[i] = baseZ + i * cellSize + cellOffset;
      }

      if (shouldSkipExperimentalLodTile(settings, worldXs, worldZs, lodSizePoints, trace)) {
         return;
      }

      int[] baseTerrainSurface = new int[area];
      int[] surfaceYs = new int[area];
      int[] waterSurfaces = new int[area];
      boolean[] underwaterFlags = new boolean[area];
      int[] coverClasses = new int[area];
      int[] visualCoverClasses = new int[area];
      int[] lodSlopeDiffs = new int[area];
      int[] lodConvexities = new int[area];
      IDhApiBiomeWrapper[] biomeWrappers = new IDhApiBiomeWrapper[area];
      Holder<Biome>[] biomeHolders = newBiomeHolderArray(area);
      boolean[] remaSnowTerrainFlags = new boolean[area];
      boolean sampleTimingEnabled = trace.isEnabled();
      long sampleCoverNanos = 0L;
      long sampleVisualCoverNanos = 0L;
      long sampleTerrainNanos = 0L;
      long sampleRepairNanos = 0L;
      long phaseStart = beginTimingPhase(trace);

      for (int localZ = 0; localZ < lodSizePoints; localZ++) {
         throwIfLodCancelled();
         int worldZ = worldZs[localZ];
         boolean remaSnowTerrain = false;

         for (int localX = 0; localX < lodSizePoints; localX++) {
            int index = localZ * lodSizePoints + localX;
            int worldX = worldXs[localX];
            long samplePartStart = sampleTimingEnabled ? System.nanoTime() : 0L;
            int coverClass = this.generator.sampleCoverClass(worldX, worldZ, previewResolutionMeters);
            if (sampleTimingEnabled) {
               sampleCoverNanos += System.nanoTime() - samplePartStart;
               samplePartStart = System.nanoTime();
            }
            int visualCoverClass = sampleVisualCover && !isHardRawCoverClass(coverClass)
               ? this.generator.sampleVisualCoverClass(worldX, worldZ, coverClass, previewResolutionMeters)
               : coverClass;
            if (sampleTimingEnabled) {
               sampleVisualCoverNanos += System.nanoTime() - samplePartStart;
               samplePartStart = System.nanoTime();
            }
            baseTerrainSurface[index] = this.generator.resolveLodTerrainSurface(worldX, worldZ, coverClass, previewResolutionMeters);
            if (sampleTimingEnabled) {
               sampleTerrainNanos += System.nanoTime() - samplePartStart;
            }
            coverClasses[index] = coverClass;
            visualCoverClasses[index] = visualCoverClass;
            remaSnowTerrainFlags[index] = remaSnowTerrain;
         }
      }
      long sampleRepairStart = sampleTimingEnabled ? System.nanoTime() : 0L;
      this.generator.repairLodTerrainSurfaceGrid(baseTerrainSurface, coverClasses, lodSizePoints);
      if (sampleTimingEnabled) {
         sampleRepairNanos += System.nanoTime() - sampleRepairStart;
      }
      endTimingPhase(trace, "sample", phaseStart);
      trace.addPhase("sample.cover", sampleCoverNanos);
      trace.addPhase("sample.visualCover", sampleVisualCoverNanos);
      trace.addPhase("sample.terrain", sampleTerrainNanos);
      trace.addPhase("sample.repair", sampleRepairNanos);
      trace.note("detailedWater", baseDetailedWater);
      phaseStart = beginTimingPhase(trace);
      DhLodWaterResolver.AreaResult waterArea = this.dhWaterResolver.resolveArea(
         baseX,
         baseZ,
         lodSizePoints,
         cellSize,
         worldXs,
         worldZs,
         baseTerrainSurface,
         coverClasses,
         baseDetailedWater
      );
      endTimingPhase(trace, "detailedWater", phaseStart);

      int[] resolvedTerrainSurface = waterArea.terrainSurface();
      int[] resolvedWaterSurface = waterArea.waterSurface();
      boolean[] resolvedHasWater = waterArea.hasWater();
      boolean[] resolvedOcean = waterArea.ocean();

      phaseStart = beginTimingPhase(trace);
      for (int localZ = 0; localZ < lodSizePoints; localZ++) {
         throwIfLodCancelled();
         int worldZ = worldZs[localZ];

         for (int localX = 0; localX < lodSizePoints; localX++) {
            int index = localZ * lodSizePoints + localX;
            int worldX = worldXs[localX];
            int surfaceY = Mth.clamp(resolvedTerrainSurface[index], minY, maxY - 1);
            int waterSurface = Mth.clamp(resolvedWaterSurface[index], minY, maxY - 1);
            boolean hasWater = resolvedHasWater[index];
            boolean isOcean = resolvedOcean[index];
            int terrainCoverClass = this.generator.resolveDryOsmTerrainCoverClass(
               worldX, worldZ, coverClasses[index], hasWater
            );
            if (terrainCoverClass != coverClasses[index]) {
               coverClasses[index] = terrainCoverClass;
               visualCoverClasses[index] = terrainCoverClass;
            }
            surfaceYs[index] = surfaceY;
            waterSurfaces[index] = waterSurface;
            underwaterFlags[index] = hasWater && waterSurface > surfaceY;
            Holder<Biome> biomeHolder = this.biomeSource
               .getLodBiomeAtBlock(
                  worldX, worldZ, coverClasses[index], visualCoverClasses[index], hasWater, isOcean, previewResolutionMeters
               );
            biomeHolders[index] = biomeHolder;
            biomeWrappers[index] = wrappers.getBiome(biomeHolder);
         }
      }
      endTimingPhase(trace, "biomeResolve", phaseStart);

      phaseStart = beginTimingPhase(trace);
      TellusLodGenerator.LodBuildingMaskResult buildingMaskResult;
      if (buildingsActive) {
         try {
            buildingMaskResult = this.buildLodBuildingMask(worldXs, worldZs, surfaceYs, biomeHolders, lodSizePoints, cellSize, osmQueryMode);
         } catch (Throwable throwable) {
            if (isInterruptedLodGeneration(throwable) || throwable instanceof Error) {
               throw propagateLodGenerationFailure(throwable);
            }

            LOGGER.warn(
               "Tellus DH building LOD mask failed at chunk=[{}, {}], detail={}; rendering this LOD tile without building features.",
               chunkPosMinX,
               chunkPosMinZ,
               Byte.toUnsignedInt(detailLevel),
               throwable
            );
            buildingMaskResult = new TellusLodGenerator.LodBuildingMaskResult(null, null, false);
         }
      } else {
         buildingMaskResult = new TellusLodGenerator.LodBuildingMaskResult(null, null, false);
      }
      endTimingPhase(trace, "buildingMask", phaseStart);
      TellusLodGenerator.LodBuildingColumn[] buildingColumns = buildingMaskResult.columns();
      int[] buildingFlattenedSurface = buildingMaskResult.flattenedSurface();
      phaseStart = beginTimingPhase(trace);
      TellusLodGenerator.LodRoadMaskResult roadMaskResult;
      if (roadsActive) {
         try {
            roadMaskResult = this.buildUltraFastRoadMask(worldXs, worldZs, lodSizePoints, cellSize, mainRoadsOnly, osmQueryMode);
         } catch (Throwable throwable) {
            if (isInterruptedLodGeneration(throwable) || throwable instanceof Error) {
               throw propagateLodGenerationFailure(throwable);
            }

            LOGGER.warn(
               "Tellus DH road LOD mask failed at chunk=[{}, {}], detail={}; rendering this LOD tile without road features.",
               chunkPosMinX,
               chunkPosMinZ,
               Byte.toUnsignedInt(detailLevel),
               throwable
            );
            roadMaskResult = new TellusLodGenerator.LodRoadMaskResult(null, null, null, null, null, null, null, null, false);
         }
      } else {
         roadMaskResult = new TellusLodGenerator.LodRoadMaskResult(null, null, null, null, null, null, null, null, false);
      }
      endTimingPhase(trace, "roadMask", phaseStart);
      phaseStart = beginTimingPhase(trace);
      for (int localZ = 0; localZ < lodSizePoints; localZ++) {
         int row = localZ * lodSizePoints;

         for (int localX = 0; localX < lodSizePoints; localX++) {
            int index = row + localX;
            lodSlopeDiffs[index] = lodSlopeDiff(surfaceYs, lodSizePoints, localX, localZ, cellSize);
            lodConvexities[index] = lodConvexity(surfaceYs, lodSizePoints, localX, localZ, cellSize);
         }
      }
      endTimingPhase(trace, "terrainMetrics", phaseStart);
      if (cellSize > 4 && detail <= LOD_SURFACE_SHAPE_REFINE_MAX_DETAIL) {
         phaseStart = beginTimingPhase(trace);
         for (int localZ = 0; localZ < lodSizePoints; localZ++) {
            int worldZ = worldZs[localZ];
            int row = localZ * lodSizePoints;
            for (int localX = 0; localX < lodSizePoints; localX++) {
               int index = row + localX;
               if (this.generator
                  .shouldRefineLodSurfaceShape(
                     coverClasses[index], visualCoverClasses[index], surfaceYs[index], lodSlopeDiffs[index], lodConvexities[index]
                  )) {
                  long packed = this.generator.sampleLodSurfaceShape(worldXs[localX], worldZ, previewResolutionMeters);
                  lodSlopeDiffs[index] = (int)(packed >> 32);
                  lodConvexities[index] = (int)packed;
               }
            }
         }
         endTimingPhase(trace, "surfaceShapeRefine", phaseStart);
      }
      byte[] roadClassMask = roadMaskResult.mask();
      byte[] roadStyleMask = roadMaskResult.styleMask();
      boolean[] roadMarkingMask = roadMaskResult.markingMask();
      boolean emitTimingEnabled = trace.isEnabled();
      long emitClassifyNanos = 0L;
      long emitBaseLayersNanos = 0L;
      long emitCanopyNanos = 0L;
      long emitFeaturesNanos = 0L;
      long emitOutputNanos = 0L;
      int emitColumns = 0;
      int emitPoints = 0;
      int emitMaxPoints = 0;
      int emitColumnsOverInitialCapacity = 0;
      int emitUnderwaterColumns = 0;
      int emitCanopyColumns = 0;
      int emitBuildingColumns = 0;
      int emitRoadColumns = 0;
      BlockState lastTopState = null;
      BlockState lastFillerState = null;
      TellusLodGenerator.SurfaceWrapperPair lastSurfaceWrapper = null;
      phaseStart = beginTimingPhase(trace);
      EarthChunkGenerator.LodMountainTransitionCache mountainTransitionCache = this.generator.buildLodMountainTransitionCache(
         worldXs[0], worldXs[lodSizePoints - 1], worldZs[0], worldZs[lodSizePoints - 1], previewResolutionMeters
      );
      this.generator.setLodMountainTransitionCache(mountainTransitionCache);

      try {
         for (int localZ = 0; localZ < lodSizePoints; localZ++) {
         throwIfLodCancelled();

         for (int localX = 0; localX < lodSizePoints; localX++) {
            int index = localZ * lodSizePoints + localX;
            int outputStartX = localX * sampleStride;
            int outputStartZ = localZ * sampleStride;
            int outputEndX = Math.min(outputSizePoints, outputStartX + sampleStride);
            int outputEndZ = Math.min(outputSizePoints, outputStartZ + sampleStride);
            int outputReplicaCount = (outputEndX - outputStartX) * (outputEndZ - outputStartZ);
            int worldX = worldXs[localX];
            int worldZ = worldZs[localZ];
            int surfaceY = surfaceYs[index];
            int waterSurface = waterSurfaces[index];
            boolean underwater = underwaterFlags[index];
            int coverClass = coverClasses[index];
            int visualCoverClass = visualCoverClasses[index];
            Holder<Biome> biomeHolder = biomeHolders[index];
            IDhApiBiomeWrapper biome = biomeWrappers[index];
            TellusLodGenerator.CanopyProfile biomeCanopyProfile = canopyProfile(biomeHolder);
            TellusLodGenerator.CanopyProfile sampledCanopyProfile = resolveTreeCoverCanopyProfile(biomeCanopyProfile, coverClass);
            boolean isMangrove = sampledCanopyProfile.isMangrove() || coverClass == ESA_MANGROVES;
            TellusLodGenerator.LodBuildingColumn buildingColumn = buildingColumns == null ? null : buildingColumns[index];
            boolean hasBuilding = buildingColumn != null;
            if (hasBuilding) {
               int flattenedSurface = buildingFlattenedSurface == null ? Integer.MIN_VALUE : buildingFlattenedSurface[index];
               if (flattenedSurface != Integer.MIN_VALUE) {
                  surfaceY = Mth.clamp(flattenedSurface, minY, maxY - 1);
                  waterSurface = surfaceY;
                  underwater = false;
               }
            }

            long columnPhaseStart = emitTimingEnabled ? System.nanoTime() : 0L;
            EarthChunkGenerator.LodSurface lodSurface = this.generator.resolveUltraFastLodSurface(
               biomeHolder,
               worldX,
               worldZ,
               surfaceY,
               underwater,
               coverClass,
               visualCoverClass,
               lodSlopeDiffs[index],
               lodConvexities[index],
               remaSnowTerrainFlags[index],
               osmQueryMode,
               detail <= LOD_OSM_SURFACE_MAX_DETAIL
            );
            BlockState topState = lodSurface.top();
            BlockState fillerState = lodSurface.filler();
            TellusLodGenerator.SurfaceWrapperPair surfaceWrapper;
            if (topState == lastTopState && fillerState == lastFillerState && lastSurfaceWrapper != null) {
               surfaceWrapper = lastSurfaceWrapper;
            } else {
               surfaceWrapper = new TellusLodGenerator.SurfaceWrapperPair(
                  wrappers.getBlockState(topState), wrappers.getBlockState(fillerState)
               );
               lastTopState = topState;
               lastFillerState = fillerState;
               lastSurfaceWrapper = surfaceWrapper;
            }
            IDhApiBlockStateWrapper fillerBlock = surfaceWrapper.filler();
            IDhApiBlockStateWrapper topBlock = surfaceWrapper.top();
            int roadClassId = roadClassMask == null ? 0 : roadClassMask[index];
            boolean hasRoad = roadClassId > 0 && !hasBuilding;
            if (hasRoad) {
               topBlock = lodRoadBlockForStyle(
                  roadClassId,
                  roadStyleMask == null ? 0 : roadStyleMask[index],
                  roadMarkingMask != null && roadMarkingMask[index],
                  roadMainBlock,
                  roadNormalBlock,
                  roadDirtBlock,
                  roadGravelBlock,
                  roadMarkingBlock
               );
               if (underwater) {
                  surfaceY = Math.max(surfaceY, waterSurface);
                  underwater = false;
               }
            } else if (!hasBuilding
               && !underwater
               && snowActive
               && TellusRealtimeState.shouldApplySnow(worldX, worldZ)
               && this.generator.shouldPlaceSnowAt(worldX, worldZ)) {
               topBlock = snowTopBlock;
            }

            if (emitTimingEnabled) {
               emitClassifyNanos += System.nanoTime() - columnPhaseStart;
            }

            emitColumns += outputReplicaCount;
            if (hasBuilding) {
               emitBuildingColumns += outputReplicaCount;
            }

            if (hasRoad) {
               emitRoadColumns += outputReplicaCount;
            }

            if (underwater) {
               emitUnderwaterColumns += outputReplicaCount;
            }

            int lastLayerTop = 0;
            int surfaceTop = toLayerTop(surfaceY, minY, absoluteTop);
            int topLayerBase = Math.max(0, surfaceTop - 1);
            columnPhaseStart = emitTimingEnabled ? System.nanoTime() : 0L;
            lastLayerTop = appendSealedLodBaseColumn(columnDataPoints, lastLayerTop, topLayerBase, fillerBlock, biome);

            if (surfaceTop > lastLayerTop) {
               columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, surfaceTop, topBlock, biome));
               lastLayerTop = surfaceTop;
            }

            if (underwater) {
               int waterTop = toLayerTop(waterSurface, minY, absoluteTop);
               if (waterTop > lastLayerTop) {
                  lastLayerTop = appendLodWaterColumn(
                     lastLayerTop,
                     waterTop,
                     minY,
                     absoluteTop,
                     waterSurface,
                     surfaceY,
                     allowWaterVegetation,
                     sampledCanopyProfile,
                     worldX,
                     worldZ,
                     waterBlock,
                     airBlock,
                     wrappers,
                     biome,
                     columnDataPoints
                  );
               }
            }

            if (emitTimingEnabled) {
               emitBaseLayersNanos += System.nanoTime() - columnPhaseStart;
            }

            boolean allowCanopy = !hasRoad
               && !hasBuilding
               && shouldAllowCanopy(this.generator.settings(), coverClass, sampledCanopyProfile, worldX, worldZ, underwater, isMangrove, this.generator.worldSeed());
            IDhApiBiomeWrapper canopyLeafBiome = resolveCanopyLeafBiome(
               wrappers, biome, sampledCanopyProfile, coverClass
            );
            columnPhaseStart = emitTimingEnabled ? System.nanoTime() : 0L;
            TellusLodGenerator.CanopyColumn canopyColumn = allowCanopy
               ? resolveCanopyColumn(
                  sampledCanopyProfile,
                  this.generator.settings().customTrees(),
                  this.generator.settings().hugeRedMushrooms(),
                  biomeHolder,
                  worldX,
                  worldZ,
                  cellSize,
                  this.generator.settings().worldScale(),
                  previewResolutionMeters,
                  this.generator.worldSeed(),
                  canopyRequestCache
               )
               : null;
            boolean deferMangroveCanopy = isMangrove && underwater;
            if (!deferMangroveCanopy) {
               lastLayerTop = appendCanopyColumn(canopyColumn, lastLayerTop, minY, absoluteTop, wrappers, biome, canopyLeafBiome, columnDataPoints);
            }

            if (canopyColumn != null) {
               emitCanopyColumns += outputReplicaCount;
            }

            if (emitTimingEnabled) {
               emitCanopyNanos += System.nanoTime() - columnPhaseStart;
            }

            if (lastLayerTop < absoluteTop) {
               if (deferMangroveCanopy) {
                  columnPhaseStart = emitTimingEnabled ? System.nanoTime() : 0L;
                  lastLayerTop = appendCanopyColumn(canopyColumn, lastLayerTop, minY, absoluteTop, wrappers, biome, canopyLeafBiome, columnDataPoints);
                  if (emitTimingEnabled) {
                     emitCanopyNanos += System.nanoTime() - columnPhaseStart;
                  }
               }

               columnPhaseStart = emitTimingEnabled ? System.nanoTime() : 0L;
               if (buildingColumn != null) {
                  lastLayerTop = appendBuildingColumn(buildingColumn, lastLayerTop, minY, absoluteTop, wrappers, biome, columnDataPoints);
               }

               if (emitTimingEnabled) {
                  emitFeaturesNanos += System.nanoTime() - columnPhaseStart;
               }

               if (lastLayerTop < absoluteTop) {
                  columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, absoluteTop, airBlock, biome));
               }
            }

            int columnPointCount = columnDataPoints.size();
            emitPoints += columnPointCount * outputReplicaCount;
            emitMaxPoints = Math.max(emitMaxPoints, columnPointCount);
            if (columnPointCount > 8) {
               emitColumnsOverInitialCapacity += outputReplicaCount;
            }

            columnPhaseStart = emitTimingEnabled ? System.nanoTime() : 0L;
            for (int outputZ = outputStartZ; outputZ < outputEndZ; outputZ++) {
               for (int outputX = outputStartX; outputX < outputEndX; outputX++) {
                  output.setApiDataPointColumn(outputX, outputZ, columnDataPoints);
               }
            }
            columnDataPoints.clear();
            if (emitTimingEnabled) {
               emitOutputNanos += System.nanoTime() - columnPhaseStart;
            }
         }
         }
      } finally {
         this.generator.clearLodMountainTransitionCache();
      }
      endTimingPhase(trace, "emit", phaseStart);
      trace.addPhase("emit.classify", emitClassifyNanos);
      trace.addPhase("emit.baseLayers", emitBaseLayersNanos);
      trace.addPhase("emit.canopy", emitCanopyNanos);
      trace.addPhase("emit.features", emitFeaturesNanos);
      trace.addPhase("emit.output", emitOutputNanos);
      trace.stat("emit.columns", emitColumns);
      trace.stat("emit.points", emitPoints);
      trace.stat("emit.avgPointsPerColumn", emitColumns == 0 ? "0.00" : String.format(Locale.ROOT, "%.2f", (double)emitPoints / (double)emitColumns));
      trace.stat("emit.maxPointsPerColumn", emitMaxPoints);
      trace.stat("emit.columnsOverInitialCapacity", emitColumnsOverInitialCapacity);
      trace.stat("emit.underwaterColumns", emitUnderwaterColumns);
      trace.stat("emit.canopyColumns", emitCanopyColumns);
      trace.stat("emit.buildingColumns", emitBuildingColumns);
      trace.stat("emit.roadColumns", emitRoadColumns);
   }

   private boolean useUltraFastLodMode(int detailLevel) {
      EarthGeneratorSettings.DistantHorizonsRenderMode renderMode = this.generator.settings().distantHorizonsRenderMode();
      return renderMode == EarthGeneratorSettings.DistantHorizonsRenderMode.ULTRA_FAST
         || renderMode == EarthGeneratorSettings.DistantHorizonsRenderMode.FAST && detailLevel >= FAST_RENDER_ULTRA_FAST_MIN_DETAIL;
   }

   private boolean shouldSuppressCoarseShoreline(int detailLevel) {
      return this.generator.settings().distantHorizonsRenderMode() == EarthGeneratorSettings.DistantHorizonsRenderMode.FAST
         && detailLevel >= FAST_RENDER_SKIP_SHORELINE_MIN_DETAIL;
   }

   private static int toLayerTop(int inclusiveTopY, int minY, int absoluteTop) {
      return Mth.clamp(inclusiveTopY - minY + 1, 0, absoluteTop);
   }

   /**
    * Seals the visual terrain mass below a direct LOD surface. Real chunks may use a hollow terrain shell,
    * but encoding that void in a coarse DH column exposes horizontal gaps whenever adjacent samples differ
    * by more than the shell depth. The resolved filler is retained so slope materials such as deepslate remain
    * visible on the resulting cliff face.
    */
   static int appendSealedLodBaseColumn(
      List<DhApiTerrainDataPoint> columnDataPoints,
      int lastLayerTop,
      int topLayerBase,
      IDhApiBlockStateWrapper fillerBlock,
      IDhApiBiomeWrapper biome
   ) {
      if (topLayerBase > lastLayerTop) {
         columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, topLayerBase, fillerBlock, biome));
         lastLayerTop = topLayerBase;
      }

      return lastLayerTop;
   }

   private static int dhCompatibleMaxY(int minY, EarthChunkGenerator generator) {
      int genDepth = generator.getGenDepth();
      EarthGeneratorSettings settings = generator.settings();
      int maxY = minY + genDepth;
      if (!settings.experimentalIncreaseHeight() || DistantHorizonsIntegration.supportsExperimentalGeneratorHeight(generator)) {
         return maxY;
      }

      return Math.min(maxY, minY + DH_FULL_DATA_MAX_RELATIVE_HEIGHT);
   }

   private static boolean shouldSkipExperimentalLodTile(
      EarthGeneratorSettings settings, int[] worldXs, int[] worldZs, int lodSizePoints, TellusLodGenerator.LodTimingTrace trace
   ) {
      if (lodSizePoints <= 0) {
         return false;
      }

      int minX = Math.min(worldXs[0], worldXs[lodSizePoints - 1]);
      int maxX = Math.max(worldXs[0], worldXs[lodSizePoints - 1]);
      int minZ = Math.min(worldZs[0], worldZs[lodSizePoints - 1]);
      int maxZ = Math.max(worldZs[0], worldZs[lodSizePoints - 1]);
      if (ExperimentalHeightSupport.isHorizontalRangeSupported(settings, minX, maxX, minZ, maxZ)) {
         return false;
      }

      trace.note("experimentalHeight", "skipped_out_of_bounds");
      return true;
   }

   private static int roadLightSpacingBlocks(double worldScale) {
      if (!(worldScale > 0.0)) {
         return 40;
      } else {
         return Mth.clamp((int)Math.round(ROAD_LIGHT_BASE_SPACING_METERS / worldScale), ROAD_LIGHT_MIN_SPACING_BLOCKS, 40);
      }
   }

   private static int roadLightMinimumSpacingBlocks(int spacingBlocks) {
      return Math.max(3, (int)Math.round(spacingBlocks * 0.75));
   }

   private static int roadLightFenceCount(double worldScale) {
      if (worldScale <= 3.0) {
         return 3;
      } else {
         return worldScale <= 8.0 ? 2 : 1;
      }
   }

   private static TellusLodGenerator.SampledRoadStation sampleRoadStation(
      double[] worldXs, double[] worldZs, double[] segmentStarts, double[] segmentLengths, double station
   ) {
      for (int i = 0; i < segmentLengths.length; i++) {
         double segmentLength = segmentLengths[i];
         if (!(segmentLength <= 1.0E-6)) {
            double segmentStart = segmentStarts[i];
            double segmentEnd = segmentStart + segmentLength;
            if (station <= segmentEnd + 1.0E-6 || i == segmentLengths.length - 1) {
               double dx = worldXs[i + 1] - worldXs[i];
               double dz = worldZs[i + 1] - worldZs[i];
               double t = Mth.clamp((station - segmentStart) / segmentLength, 0.0, 1.0);
               return new TellusLodGenerator.SampledRoadStation(worldXs[i] + dx * t, worldZs[i] + dz * t, dx / segmentLength, dz / segmentLength);
            }
         }
      }

      return null;
   }

   private static int appendRoadLightColumn(
      int baseY,
      int fenceCount,
      int lastLayerTop,
      int minY,
      int absoluteTop,
      IDhApiBlockStateWrapper baseBlock,
      IDhApiBlockStateWrapper fenceBlock,
      IDhApiBlockStateWrapper glowBlock,
      IDhApiBlockStateWrapper capBlock,
      IDhApiBiomeWrapper biome,
      List<DhApiTerrainDataPoint> columnDataPoints
   ) {
      int layerTop = lastLayerTop;
      int wallTop = toLayerTop(baseY + 1, minY, absoluteTop);
      if (wallTop > layerTop) {
         columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, layerTop, wallTop, baseBlock, biome));
         layerTop = wallTop;
      }

      int fenceTop = toLayerTop(baseY + fenceCount + 1, minY, absoluteTop);
      if (fenceTop > layerTop) {
         columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, layerTop, fenceTop, fenceBlock, biome));
         layerTop = fenceTop;
      }

      int glowTop = toLayerTop(baseY + fenceCount + 2, minY, absoluteTop);
      if (glowTop > layerTop) {
         columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, ROAD_LIGHT_BLOCK_LIGHT, 15, layerTop, glowTop, glowBlock, biome));
         layerTop = glowTop;
      }

      int capTop = toLayerTop(baseY + fenceCount + 3, minY, absoluteTop);
      if (capTop > layerTop) {
         columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, ROAD_LIGHT_BLOCK_LIGHT, 15, layerTop, capTop, capBlock, biome));
         layerTop = capTop;
      }

      return layerTop;
   }

   private static int lodSlopeDiff(int[] surfaceYs, int gridSize, int x, int z, int cellSize) {
      int index = z * gridSize + x;
      int center = surfaceYs[index];
      int east = surfaceYs[z * gridSize + Math.min(gridSize - 1, x + 1)];
      int west = surfaceYs[z * gridSize + Math.max(0, x - 1)];
      int north = surfaceYs[Math.max(0, z - 1) * gridSize + x];
      int south = surfaceYs[Math.min(gridSize - 1, z + 1) * gridSize + x];
      int maxDiff = Math.max(Math.max(Math.abs(east - center), Math.abs(west - center)), Math.max(Math.abs(north - center), Math.abs(south - center)));
      int scaledStep = Math.max(4, cellSize);
      return maxDiff * 4 / scaledStep;
   }

   private static int lodConvexity(int[] surfaceYs, int gridSize, int x, int z, int cellSize) {
      int index = z * gridSize + x;
      int center = surfaceYs[index];
      int east = surfaceYs[z * gridSize + Math.min(gridSize - 1, x + 1)];
      int west = surfaceYs[z * gridSize + Math.max(0, x - 1)];
      int north = surfaceYs[Math.max(0, z - 1) * gridSize + x];
      int south = surfaceYs[Math.min(gridSize - 1, z + 1) * gridSize + x];
      int neighborAverage = (east + west + north + south) / 4;
      int scaledStep = Math.max(4, cellSize);
      return (neighborAverage - center) * 4 / scaledStep;
   }

   private LodPrefetchBatcher.Submission prefetchLodResources(int chunkPosMinX, int chunkPosMinZ, byte detailLevel, int lodSizePoints) {
      if (lodSizePoints <= 0) {
         return LodPrefetchBatcher.completedSubmission();
      }

      int detail = Byte.toUnsignedInt(detailLevel);
      EarthGeneratorSettings settings = this.generator.settings();
      double previewResolutionMeters = lodPreviewResolutionMeters(settings, 1 << detailLevel);
      LodBlockRange range = LodBlockRange.forDhTile(chunkPosMinX, chunkPosMinZ, detail, lodSizePoints);
      boolean roadsActive = this.shouldRenderDhRoads(detail);
      boolean buildingsActive = this.shouldRenderDhBuildings(detail);
      LodPrefetchBatcher.Request request = new LodPrefetchBatcher.Request(
         range, detail, roadsActive, buildingsActive, previewResolutionMeters
      );
      return settings.distantHorizonsRenderMode() == EarthGeneratorSettings.DistantHorizonsRenderMode.FAST
         ? this.lodPrefetchBatcher.submit(request)
         : this.lodPrefetchBatcher.submitImmediately(request);
   }

   private CompletableFuture<Void> startLodPrefetch(LodPrefetchBatcher.Request request) {
      LodBlockRange range = request.range();
      return this.generator.prefetchForArea(
         range.minX(),
         range.minZ(),
         range.maxX(),
         range.maxZ(),
         request.includeRoadsPrefetch(),
         request.includeBuildingsPrefetch(),
         request.previewResolutionMeters()
      );
   }

   private void awaitPrefetch(CompletableFuture<Void> future) {
      try {
         future.get();
      } catch (InterruptedException error) {
         future.cancel(true);
         Thread.currentThread().interrupt();
         throw new CancellationException("Tellus LOD prefetch interrupted");
      } catch (ExecutionException error) {
         Throwable cause = error.getCause();
         if (isInterruptedLodGeneration(cause) || cause instanceof Error) {
            throw propagateLodGenerationFailure(cause);
         }
         LOGGER.debug("Tellus exact LOD tile prefetch completed with an error; generation will use normal source fallbacks.", cause);
      }
   }

   private static int coverSampleStride(int detailLevel, int lodSizePoints) {
      if (detailLevel < 7) {
         return 1;
      } else {
         int shift = Math.min(2, detailLevel - 7 + 1);
         int stride = 1 << shift;
         stride = Math.min(stride, 4);
         return Math.min(stride, lodSizePoints);
      }
   }

   private static boolean shouldSampleVisualCover(EarthGeneratorSettings settings, double previewResolutionMeters) {
      // The current visual-cover methods are exact aliases of the raw cover
      // sampler. Re-sampling here doubles WorldCover work without changing a
      // single emitted column.
      return false;
   }

   private static double effectiveCoverResolutionMeters(double worldScale, double previewResolutionMeters) {
      return Double.isFinite(previewResolutionMeters) && previewResolutionMeters > 0.0
         ? Math.max(worldScale, previewResolutionMeters)
         : worldScale;
   }

   private static boolean isHardRawCoverClass(int coverClass) {
      return coverClass == ESA_NO_DATA || coverClass == ESA_WATER || coverClass == ESA_MANGROVES || coverClass == ESA_BUILT_UP;
   }

   private static int detailedWaterStride(int detailLevel, int lodSizePoints) {
      if (detailLevel < 5) {
         return 1;
      } else {
         int shift = Math.min(2, detailLevel - 5 + 1);
         int stride = 1 << shift;
         stride = Math.min(stride, 4);
         return Math.min(stride, lodSizePoints);
      }
   }

   private static double lodPreviewResolutionMeters(EarthGeneratorSettings settings, int cellSize) {
      double worldScale = settings.worldScale();
      return worldScale > 0.0 ? Math.max(worldScale, worldScale * (double)cellSize) : Double.NaN;
   }

   private static int intProperty(String key, int defaultValue, int minInclusive, int maxInclusive) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      } else {
         try {
            return Mth.clamp(Integer.parseInt(value), minInclusive, maxInclusive);
         } catch (NumberFormatException error) {
            LOGGER.debug("Invalid integer system property {}='{}', using {}", key, value, defaultValue);
            return defaultValue;
         }
      }
   }

   private boolean shouldRenderDhRoads(int detailLevel) {
      EarthGeneratorSettings settings = this.generator.settings();
      return settings.enableRoads()
         && settings.distantHorizonsOsmFeatures()
         && settings.worldScale() > 0.0
         && settings.worldScale() <= 15.0
         && detailLevel <= settings.distantHorizonsOsmRoadMaxDetail();
   }

   private boolean shouldRenderDhBuildings(int detailLevel) {
      EarthGeneratorSettings settings = this.generator.settings();
      return settings.enableBuildings()
         && settings.distantHorizonsOsmFeatures()
         && settings.worldScale() > 0.0
         && settings.worldScale() <= 15.0
         && detailLevel <= settings.distantHorizonsOsmBuildingMaxDetail();
   }

   private TellusLodGenerator.LodRoadMaskResult buildUltraFastRoadMask(
      int[] worldXs, int[] worldZs, int lodSizePoints, int cellSize, boolean mainRoadsOnly, OsmQueryMode fetchMode
   ) {
      long buildStartNs = OsmPerf.now();
      if (lodSizePoints > 0 && worldXs.length >= lodSizePoints && worldZs.length >= lodSizePoints) {
         EarthGeneratorSettings settings = this.generator.settings();
         int minWorldX = Math.min(worldXs[0], worldXs[lodSizePoints - 1]);
         int maxWorldX = Math.max(worldXs[0], worldXs[lodSizePoints - 1]);
         int minWorldZ = Math.min(worldZs[0], worldZs[lodSizePoints - 1]);
         int maxWorldZ = Math.max(worldZs[0], worldZs[lodSizePoints - 1]);
         EarthChunkGenerator.OsmRoadQueryResult roadQuery = this.generator
            .fetchOsmRoadsForAreaDetailed(minWorldX, minWorldZ, maxWorldX, maxWorldZ, 64, fetchMode);
         List<RoadFeature> roads = roadQuery.features();
         EarthChunkGenerator.OsmRoadAreaQueryResult areaQuery = this.generator
            .fetchOsmRoadAreasForAreaDetailed(minWorldX, minWorldZ, maxWorldX, maxWorldZ, 64, fetchMode);
         List<RoadAreaFeature> roadAreas = mainRoadsOnly ? List.of() : areaQuery.features();
         boolean hadCacheMisses = roadQuery.hadCacheMisses() || areaQuery.hadCacheMisses();
         if (roads.isEmpty() && roadAreas.isEmpty()) {
            OsmPerf.recordDhRoadMaskBuild(OsmPerf.elapsedSince(buildStartNs), 0);
            return new TellusLodGenerator.LodRoadMaskResult(null, null, null, null, null, null, null, null, hadCacheMisses);
         } else {
            List<RoadFeature> mainRoads = new ArrayList<>();
            List<RoadFeature> normalRoads = mainRoadsOnly ? List.<RoadFeature>of() : new ArrayList<>();
            List<RoadFeature> dirtRoads = mainRoadsOnly ? List.<RoadFeature>of() : new ArrayList<>();

            for (RoadFeature road : roads) {
               throwIfLodCancelled();
               if (BridgeSupportLayout.crossesWorldSeam(road, this.projection)) {
                  continue;
               }
               switch (road.roadClass()) {
                  case MAIN:
                     mainRoads.add(road);
                     break;
                  case NORMAL:
                     if (!mainRoadsOnly) {
                        normalRoads.add(road);
                     }
                     break;
                  case DIRT:
                     if (!mainRoadsOnly) {
                        dirtRoads.add(road);
                     }
               }
            }

            double blocksPerDegree = this.projection.equatorialBlocksPerDegree();
            int mainRoadWidth = roadWidthForScale(RoadClass.MAIN.baseWidth(), settings.worldScale());
            int normalRoadWidth = roadWidthForScale(RoadClass.NORMAL.baseWidth(), settings.worldScale());
            int dirtRoadWidth = roadWidthForScale(RoadClass.DIRT.baseWidth(), settings.worldScale());
            byte[] selectedClass = new byte[lodSizePoints * lodSizePoints];
            byte[] selectedStyle = new byte[selectedClass.length];
            boolean[] selectedMarking = new boolean[selectedClass.length];
            rasterizeLodRoadClass(
               mainRoads, (byte)1, mainRoadWidth, blocksPerDegree, worldXs, worldZs, lodSizePoints, cellSize, selectedClass, selectedStyle, selectedMarking
            );
            if (!mainRoadsOnly) {
               rasterizeLodRoadClass(
                  normalRoads, (byte)2, normalRoadWidth, blocksPerDegree, worldXs, worldZs, lodSizePoints, cellSize, selectedClass, selectedStyle, selectedMarking
               );
               rasterizeLodRoadClass(
                  dirtRoads, (byte)3, dirtRoadWidth, blocksPerDegree, worldXs, worldZs, lodSizePoints, cellSize, selectedClass, selectedStyle, selectedMarking
               );
            }
            rasterizeLodRoadAreas(roadAreas, settings.worldScale(), worldXs, worldZs, lodSizePoints, selectedClass, selectedStyle);

            boolean hasRoadCoverage = false;
            for (byte classId : selectedClass) {
               if (classId > 0) {
                  hasRoadCoverage = true;
                  break;
               }
            }

            OsmPerf.recordDhRoadMaskBuild(OsmPerf.elapsedSince(buildStartNs), roads.size());
            return !hasRoadCoverage ? new TellusLodGenerator.LodRoadMaskResult(null, null, null, null, null, null, null, null, hadCacheMisses)
               : new TellusLodGenerator.LodRoadMaskResult(selectedClass, selectedStyle, selectedMarking, null, null, null, null, null, null, null, hadCacheMisses);
         }
      } else {
         OsmPerf.recordDhRoadMaskBuild(OsmPerf.elapsedSince(buildStartNs), 0);
         return new TellusLodGenerator.LodRoadMaskResult(null, null, null, null, null, null, null, null, false);
      }
   }

   private TellusLodGenerator.LodRoadMaskResult buildLodRoadClassMask(
      int[] worldXs,
      int[] worldZs,
      int[] surfaceYs,
      int lodSizePoints,
      int cellSize,
      boolean mainRoadsOnly,
      OsmQueryMode fetchMode,
      TellusLodGenerator.LodBuildingColumn[] buildingColumns
   ) {
      long buildStartNs = OsmPerf.now();
      if (lodSizePoints > 0 && worldXs.length >= lodSizePoints && worldZs.length >= lodSizePoints) {
         EarthGeneratorSettings settings = this.generator.settings();
         int minWorldX = Math.min(worldXs[0], worldXs[lodSizePoints - 1]);
         int maxWorldX = Math.max(worldXs[0], worldXs[lodSizePoints - 1]);
         int minWorldZ = Math.min(worldZs[0], worldZs[lodSizePoints - 1]);
         int maxWorldZ = Math.max(worldZs[0], worldZs[lodSizePoints - 1]);
         EarthChunkGenerator.OsmRoadQueryResult roadQuery = this.generator
            .fetchOsmRoadsForAreaDetailed(minWorldX, minWorldZ, maxWorldX, maxWorldZ, 64, fetchMode);
         List<RoadFeature> roads = roadQuery.features();
         boolean hadCacheMisses = roadQuery.hadCacheMisses();
         if (roads.isEmpty()) {
            OsmPerf.recordDhRoadMaskBuild(OsmPerf.elapsedSince(buildStartNs), 0);
            return new TellusLodGenerator.LodRoadMaskResult(null, null, null, null, null, null, null, null, hadCacheMisses);
         } else {
            List<RoadFeature> mainRoads = new ArrayList<>();
            List<RoadFeature> mainBridgeRoads = new ArrayList<>();
            List<RoadFeature> normalRoads = mainRoadsOnly ? List.<RoadFeature>of() : new ArrayList<>();
            List<RoadFeature> normalBridgeRoads = mainRoadsOnly ? List.<RoadFeature>of() : new ArrayList<>();
            List<RoadFeature> dirtRoads = mainRoadsOnly ? List.<RoadFeature>of() : new ArrayList<>();
            List<RoadFeature> dirtBridgeRoads = mainRoadsOnly ? List.<RoadFeature>of() : new ArrayList<>();

            for (RoadFeature road : roads) {
               throwIfLodCancelled();
               if (BridgeSupportLayout.crossesWorldSeam(road, this.projection)) {
                  continue;
               }
               switch (road.roadClass()) {
                  case MAIN:
                     mainRoads.add(road);
                     if (road.mode() == RoadMode.BRIDGE) {
                        mainBridgeRoads.add(road);
                     }
                     break;
                  case NORMAL:
                     if (!mainRoadsOnly) {
                        normalRoads.add(road);
                        if (road.mode() == RoadMode.BRIDGE) {
                           normalBridgeRoads.add(road);
                        }
                     }
                     break;
                  case DIRT:
                     if (!mainRoadsOnly) {
                        dirtRoads.add(road);
                        if (road.mode() == RoadMode.BRIDGE) {
                           dirtBridgeRoads.add(road);
                        }
                     }
               }
            }

            double blocksPerDegree = this.projection.equatorialBlocksPerDegree();
            int mainRoadWidth = roadWidthForScale(RoadClass.MAIN.baseWidth(), settings.worldScale());
            int normalRoadWidth = roadWidthForScale(RoadClass.NORMAL.baseWidth(), settings.worldScale());
            int dirtRoadWidth = roadWidthForScale(RoadClass.DIRT.baseWidth(), settings.worldScale());
            byte[] selectedClass = new byte[lodSizePoints * lodSizePoints];
            byte[] selectedStyle = new byte[selectedClass.length];
            boolean[] selectedMarking = new boolean[selectedClass.length];
            rasterizeLodRoadClass(
               mainRoads, (byte)1, mainRoadWidth, blocksPerDegree, worldXs, worldZs, lodSizePoints, cellSize, selectedClass, selectedStyle, selectedMarking
            );
            if (!mainRoadsOnly) {
               rasterizeLodRoadClass(
                  normalRoads, (byte)2, normalRoadWidth, blocksPerDegree, worldXs, worldZs, lodSizePoints, cellSize, selectedClass, selectedStyle, selectedMarking
               );
               rasterizeLodRoadClass(
                  dirtRoads, (byte)3, dirtRoadWidth, blocksPerDegree, worldXs, worldZs, lodSizePoints, cellSize, selectedClass, selectedStyle, selectedMarking
               );
            }

            boolean hasRoadCoverage = false;

            for (byte classId : selectedClass) {
               if (classId > 0) {
                  hasRoadCoverage = true;
                  break;
               }
            }

            if (!hasRoadCoverage) {
               OsmPerf.recordDhRoadMaskBuild(OsmPerf.elapsedSince(buildStartNs), roads.size());
               return new TellusLodGenerator.LodRoadMaskResult(null, null, null, null, null, null, null, null, hadCacheMisses);
            } else {
               boolean hasBridgeDeck = false;
               int[] bridgeDeckY = null;
               boolean hasBridgeSupport = false;
               int[] bridgeSupportShaftBottomY = null;
               int[] bridgeSupportShaftTopY = null;
               int[] bridgeSupportCapBottomY = null;
               int[] bridgeSupportCapTopY = null;
               boolean hasRoadLights = false;
               int[] roadLightBaseY = null;
               byte[] roadLightFenceCount = null;
               if (!mainBridgeRoads.isEmpty() || !normalBridgeRoads.isEmpty() || !dirtBridgeRoads.isEmpty()) {
                  bridgeDeckY = new int[selectedClass.length];
                  Arrays.fill(bridgeDeckY, Integer.MIN_VALUE);
                  Map<Long, Integer> roadSurfaceCache = new HashMap<>();
                  hasBridgeDeck = this.rasterizeLodBridgeDeck(
                     mainBridgeRoads,
                     (byte)1,
                     mainRoadWidth,
                     blocksPerDegree,
                     worldXs,
                     worldZs,
                     lodSizePoints,
                     cellSize,
                     selectedClass,
                     bridgeDeckY,
                     roadSurfaceCache
                  );
                  if (!mainRoadsOnly) {
                     hasBridgeDeck |= this.rasterizeLodBridgeDeck(
                        normalBridgeRoads,
                        (byte)2,
                        normalRoadWidth,
                        blocksPerDegree,
                        worldXs,
                        worldZs,
                        lodSizePoints,
                        cellSize,
                        selectedClass,
                        bridgeDeckY,
                        roadSurfaceCache
                     );
                     hasBridgeDeck |= this.rasterizeLodBridgeDeck(
                        dirtBridgeRoads,
                        (byte)3,
                        dirtRoadWidth,
                        blocksPerDegree,
                        worldXs,
                        worldZs,
                        lodSizePoints,
                        cellSize,
                        selectedClass,
                        bridgeDeckY,
                        roadSurfaceCache
                     );
                  }

                  if (!mainBridgeRoads.isEmpty() || !normalBridgeRoads.isEmpty()) {
                     bridgeSupportShaftBottomY = new int[selectedClass.length];
                     bridgeSupportShaftTopY = new int[selectedClass.length];
                     bridgeSupportCapBottomY = new int[selectedClass.length];
                     bridgeSupportCapTopY = new int[selectedClass.length];
                     Arrays.fill(bridgeSupportShaftBottomY, Integer.MIN_VALUE);
                     Arrays.fill(bridgeSupportShaftTopY, Integer.MIN_VALUE);
                     Arrays.fill(bridgeSupportCapBottomY, Integer.MIN_VALUE);
                     Arrays.fill(bridgeSupportCapTopY, Integer.MIN_VALUE);
                     hasBridgeSupport = this.rasterizeLodBridgeSupports(
                        mainBridgeRoads,
                        mainRoadWidth,
                        blocksPerDegree,
                        worldXs,
                        worldZs,
                        surfaceYs,
                        lodSizePoints,
                        cellSize,
                        selectedClass,
                        bridgeDeckY,
                        buildingColumns,
                        roadSurfaceCache,
                        bridgeSupportShaftBottomY,
                        bridgeSupportShaftTopY,
                        bridgeSupportCapBottomY,
                        bridgeSupportCapTopY
                     );
                     if (!mainRoadsOnly) {
                        hasBridgeSupport |= this.rasterizeLodBridgeSupports(
                           normalBridgeRoads,
                           normalRoadWidth,
                           blocksPerDegree,
                           worldXs,
                           worldZs,
                           surfaceYs,
                           lodSizePoints,
                           cellSize,
                           selectedClass,
                           bridgeDeckY,
                           buildingColumns,
                           roadSurfaceCache,
                           bridgeSupportShaftBottomY,
                           bridgeSupportShaftTopY,
                           bridgeSupportCapBottomY,
                           bridgeSupportCapTopY
                        );
                     }
                  }
               }

               int roadLightSpacing = roadLightSpacingBlocks(settings.worldScale());
               if (cellSize <= roadLightSpacing) {
                  roadLightBaseY = new int[selectedClass.length];
                  roadLightFenceCount = new byte[selectedClass.length];
                  Arrays.fill(roadLightBaseY, Integer.MIN_VALUE);
                  boolean[] occupiedLightCells = new boolean[selectedClass.length];
                  IntArrayList occupiedLightIndices = new IntArrayList();
                  EarthChunkGenerator.OsmStreetLightQueryResult streetLightQuery = this.generator
                     .fetchOsmStreetLightsForAreaDetailed(minWorldX, minWorldZ, maxWorldX, maxWorldZ, 8, fetchMode);
                  hadCacheMisses |= streetLightQuery.hadCacheMisses();
                  hasRoadLights = this.rasterizeLodExactRoadLights(
                     streetLightQuery.features(),
                     blocksPerDegree,
                     settings.worldScale(),
                     worldXs,
                     worldZs,
                     surfaceYs,
                     lodSizePoints,
                     cellSize,
                     selectedClass,
                     bridgeDeckY,
                     bridgeSupportShaftBottomY,
                     bridgeSupportShaftTopY,
                     bridgeSupportCapBottomY,
                     bridgeSupportCapTopY,
                     buildingColumns,
                     roadLightBaseY,
                     roadLightFenceCount,
                     occupiedLightCells,
                     occupiedLightIndices
                  );
                  hasRoadLights |= this.rasterizeLodRoadLights(
                     mainRoads,
                     (byte)1,
                     mainRoadWidth,
                     blocksPerDegree,
                     worldXs,
                     worldZs,
                     surfaceYs,
                     lodSizePoints,
                     cellSize,
                     selectedClass,
                     bridgeDeckY,
                     bridgeSupportShaftBottomY,
                     bridgeSupportShaftTopY,
                     bridgeSupportCapBottomY,
                     bridgeSupportCapTopY,
                     buildingColumns,
                     roadLightBaseY,
                     roadLightFenceCount,
                     occupiedLightCells,
                     occupiedLightIndices
                  );
                  if (!mainRoadsOnly) {
                     hasRoadLights |= this.rasterizeLodRoadLights(
                        normalRoads,
                        (byte)2,
                        normalRoadWidth,
                        blocksPerDegree,
                        worldXs,
                        worldZs,
                        surfaceYs,
                        lodSizePoints,
                        cellSize,
                        selectedClass,
                        bridgeDeckY,
                        bridgeSupportShaftBottomY,
                        bridgeSupportShaftTopY,
                        bridgeSupportCapBottomY,
                        bridgeSupportCapTopY,
                        buildingColumns,
                        roadLightBaseY,
                        roadLightFenceCount,
                        occupiedLightCells,
                        occupiedLightIndices
                     );
                     hasRoadLights |= this.rasterizeLodRoadLights(
                        dirtRoads,
                        (byte)3,
                        dirtRoadWidth,
                        blocksPerDegree,
                        worldXs,
                        worldZs,
                        surfaceYs,
                        lodSizePoints,
                        cellSize,
                        selectedClass,
                        bridgeDeckY,
                        bridgeSupportShaftBottomY,
                        bridgeSupportShaftTopY,
                        bridgeSupportCapBottomY,
                        bridgeSupportCapTopY,
                        buildingColumns,
                        roadLightBaseY,
                        roadLightFenceCount,
                        occupiedLightCells,
                        occupiedLightIndices
                     );
                  }
               }

               OsmPerf.recordDhRoadMaskBuild(OsmPerf.elapsedSince(buildStartNs), roads.size());
               return new TellusLodGenerator.LodRoadMaskResult(
                  selectedClass,
                  selectedStyle,
                  selectedMarking,
                  hasBridgeDeck ? bridgeDeckY : null,
                  hasBridgeSupport ? bridgeSupportShaftBottomY : null,
                  hasBridgeSupport ? bridgeSupportShaftTopY : null,
                  hasBridgeSupport ? bridgeSupportCapBottomY : null,
                  hasBridgeSupport ? bridgeSupportCapTopY : null,
                  hasRoadLights ? roadLightBaseY : null,
                  hasRoadLights ? roadLightFenceCount : null,
                  hadCacheMisses
               );
            }
         }
      } else {
         OsmPerf.recordDhRoadMaskBuild(OsmPerf.elapsedSince(buildStartNs), 0);
         return new TellusLodGenerator.LodRoadMaskResult(null, null, null, null, null, null, null, null, false);
      }
   }

   private TellusLodGenerator.LodBuildingMaskResult buildLodBuildingMask(
      int[] worldXs, int[] worldZs, int[] terrainSurfaces, Holder<Biome>[] biomeHolders, int lodSizePoints, int cellSize, OsmQueryMode fetchMode
   ) {
      long buildStartNs = OsmPerf.now();
      if (lodSizePoints > 0 && worldXs.length >= lodSizePoints && worldZs.length >= lodSizePoints) {
         int minWorldX = Math.min(worldXs[0], worldXs[lodSizePoints - 1]);
         int maxWorldX = Math.max(worldXs[0], worldXs[lodSizePoints - 1]);
         int minWorldZ = Math.min(worldZs[0], worldZs[lodSizePoints - 1]);
         int maxWorldZ = Math.max(worldZs[0], worldZs[lodSizePoints - 1]);
         int marginBlocks = Math.max(8, cellSize);
         EarthChunkGenerator.OsmBuildingQueryResult query = this.generator
            .fetchOsmBuildingsForAreaDetailed(minWorldX, minWorldZ, maxWorldX, maxWorldZ, marginBlocks, fetchMode);
         if (query.hadCacheMisses() && fetchMode == OsmQueryMode.NON_BLOCKING) {
            query = this.generator
               .fetchOsmBuildingsForAreaDetailed(minWorldX, minWorldZ, maxWorldX, maxWorldZ, marginBlocks, OsmQueryMode.BLOCKING);
         }
         List<OsmBuildingFeature> features = query.features();
         boolean hadCacheMisses = query.hadCacheMisses();
         if (features.isEmpty()) {
            OsmPerf.recordDhBuildingMaskBuild(OsmPerf.elapsedSince(buildStartNs), 0);
            return new TellusLodGenerator.LodBuildingMaskResult(null, null, hadCacheMisses);
         } else {
            Map<String, TellusLodGenerator.LodBuildingGroupScratch> groups = new HashMap<>();
            List<TellusLodGenerator.LodRasterizedBuildingFeature> partFeatures = new ArrayList<>();
            List<TellusLodGenerator.LodRasterizedBuildingFeature> footprintFeatures = new ArrayList<>();

            for (OsmBuildingFeature feature : features) {
               throwIfLodCancelled();
               String groupId = feature.kind() == com.yucareux.tellus.world.data.osm.OsmBuildingKind.PART
                  ? feature.buildingId() != null ? "part:" + feature.buildingId() : "part:" + feature.featureId()
                  : "footprint:" + feature.featureId();
               TellusLodGenerator.LodRasterizedBuildingFeature rasterized = rasterizeLodBuildingFeature(
                  feature, groupId, worldXs, worldZs, lodSizePoints, cellSize, this.generator.settings().worldScale()
               );
               if (rasterized != null && rasterized.occupiedCells().length > 0) {
                  TellusLodGenerator.LodBuildingGroupScratch group = groups.computeIfAbsent(
                     groupId, id -> new TellusLodGenerator.LodBuildingGroupScratch()
                  );
                  boolean groundContact = feature.kind() != com.yucareux.tellus.world.data.osm.OsmBuildingKind.PART
                     || buildingMinHeightBlocks(feature.minHeightMeters(), this.generator.settings().worldScale()) <= 0;

                  for (int cellIndex : rasterized.occupiedCells()) {
                     group.fallbackSamples().add(terrainSurfaces[cellIndex]);
                     if (groundContact) {
                        group.groundSamples().add(terrainSurfaces[cellIndex]);
                     }
                  }
                  if (feature.kind() == com.yucareux.tellus.world.data.osm.OsmBuildingKind.PART) {
                     partFeatures.add(rasterized);
                  } else {
                     footprintFeatures.add(rasterized);
                  }
               }
            }

            if (partFeatures.isEmpty() && footprintFeatures.isEmpty()) {
               OsmPerf.recordDhBuildingMaskBuild(OsmPerf.elapsedSince(buildStartNs), features.size());
               return new TellusLodGenerator.LodBuildingMaskResult(null, null, hadCacheMisses);
            } else {
               for (TellusLodGenerator.LodBuildingGroupScratch group : groups.values()) {
                  IntArrayList samples = !group.groundSamples().isEmpty() ? group.groundSamples() : group.fallbackSamples();
                  if (!samples.isEmpty()) {
                     group.setBaseY(medianValue(samples));
                  }
               }

               int area = lodSizePoints * lodSizePoints;
               TellusLodGenerator.LodBuildingColumn[] columns = new TellusLodGenerator.LodBuildingColumn[area];
               int[] flattenedSurface = new int[area];
               Arrays.fill(flattenedSurface, Integer.MIN_VALUE);
               int[] overlyingPartFloorY = new int[area];
               Arrays.fill(overlyingPartFloorY, Integer.MAX_VALUE);

               for (TellusLodGenerator.LodRasterizedBuildingFeature rasterized : partFeatures) {
                  throwIfLodCancelled();
                  int baseY = groups.get(rasterized.groupId()).baseY();
                  addLodBuildingFeatureCoverage(
                     rasterized,
                     baseY,
                     null,
                     columns,
                     flattenedSurface,
                     overlyingPartFloorY,
                     worldXs,
                     worldZs,
                     biomeHolders,
                     cellSize,
                     this.generator.settings().worldScale()
                  );
               }

               for (TellusLodGenerator.LodRasterizedBuildingFeature rasterized : footprintFeatures) {
                  throwIfLodCancelled();
                  int baseY = groups.get(rasterized.groupId()).baseY();
                  addLodBuildingFeatureCoverage(
                     rasterized,
                     baseY,
                     overlyingPartFloorY,
                     columns,
                     flattenedSurface,
                     null,
                     worldXs,
                     worldZs,
                     biomeHolders,
                     cellSize,
                     this.generator.settings().worldScale()
                  );
               }

               boolean hasCoverage = false;

               for (TellusLodGenerator.LodBuildingColumn column : columns) {
                  if (column != null && !column.isEmpty()) {
                     hasCoverage = true;
                     break;
                  }
               }

               OsmPerf.recordDhBuildingMaskBuild(OsmPerf.elapsedSince(buildStartNs), features.size());
               return !hasCoverage ? new TellusLodGenerator.LodBuildingMaskResult(null, null, hadCacheMisses)
                  : new TellusLodGenerator.LodBuildingMaskResult(columns, flattenedSurface, hadCacheMisses);
            }
         }
      } else {
         OsmPerf.recordDhBuildingMaskBuild(OsmPerf.elapsedSince(buildStartNs), 0);
         return new TellusLodGenerator.LodBuildingMaskResult(null, null, false);
      }
   }

   private TellusLodGenerator.LodRasterizedBuildingFeature rasterizeLodBuildingFeature(
      OsmBuildingFeature feature, String groupId, int[] worldXs, int[] worldZs, int lodSizePoints, int cellSize, double worldScale
   ) {
      if (feature.crossesWorldSeam(this.projection)) {
         return null;
      }
      double minWorldX = Math.min(worldXs[0], worldXs[lodSizePoints - 1]);
      double maxWorldX = Math.max(worldXs[0], worldXs[lodSizePoints - 1]);
      double minWorldZ = Math.min(worldZs[0], worldZs[lodSizePoints - 1]);
      double maxWorldZ = Math.max(worldZs[0], worldZs[lodSizePoints - 1]);
      double featureMinX = feature.minBlockX(this.projection);
      double featureMaxX = feature.maxBlockX(this.projection);
      double featureMinZ = feature.minBlockZ(this.projection);
      double featureMaxZ = feature.maxBlockZ(this.projection);
      IntArrayList occupiedCells = new IntArrayList();
      if (!(featureMaxX < minWorldX - cellSize)
         && !(featureMinX > maxWorldX + cellSize)
         && !(featureMaxZ < minWorldZ - cellSize)
         && !(featureMinZ > maxWorldZ + cellSize)) {
         int minGridX = Mth.clamp((int)Math.floor((featureMinX - minWorldX - cellSize) / cellSize), 0, lodSizePoints - 1);
         int maxGridX = Mth.clamp((int)Math.floor((featureMaxX - minWorldX + cellSize) / cellSize), 0, lodSizePoints - 1);
         int minGridZ = Mth.clamp((int)Math.floor((featureMinZ - minWorldZ - cellSize) / cellSize), 0, lodSizePoints - 1);
         int maxGridZ = Mth.clamp((int)Math.floor((featureMaxZ - minWorldZ + cellSize) / cellSize), 0, lodSizePoints - 1);
         int width = maxGridX - minGridX + 1;
         int height = maxGridZ - minGridZ + 1;
         boolean[] occupiedMask = new boolean[Math.max(1, width * height)];

         for (int gz = minGridZ; gz <= maxGridZ; gz++) {
            int worldZ = worldZs[gz];
            int row = gz * lodSizePoints;

            for (int gx = minGridX; gx <= maxGridX; gx++) {
               int worldX = worldXs[gx];
               if (feature.containsWorld(worldX + 0.5, worldZ + 0.5, this.projection)) {
                  occupiedMask[(gx - minGridX) + (gz - minGridZ) * width] = true;
                  occupiedCells.add(row + gx);
               }
            }
         }

         if (!occupiedCells.isEmpty()) {
            return new TellusLodGenerator.LodRasterizedBuildingFeature(
               feature, groupId, lodSizePoints, minGridX, minGridZ, width, height, occupiedMask, occupiedCells.toIntArray()
            );
         }
      }

      return null;
   }

   private void addLodBuildingFeatureCoverage(
      TellusLodGenerator.LodRasterizedBuildingFeature rasterized,
      int baseY,
      int[] overlyingPartFloorY,
      TellusLodGenerator.LodBuildingColumn[] columns,
      int[] flattenedSurface,
      int[] recordedPartFloorY,
      int[] worldXs,
      int[] worldZs,
      Holder<Biome>[] biomeHolders,
      int cellSize,
      double worldScale
   ) {
      if (baseY != Integer.MIN_VALUE) {
         int minHeightBlocks = buildingMinHeightBlocks(rasterized.feature().minHeightMeters(), worldScale);
         int floorY = baseY + minHeightBlocks + 1;
         BuildingProfile profile = TellusBuildingProfiles.resolveProfile(
            rasterized.feature(), worldScale, sampleLodBuildingBiome(rasterized, worldXs, worldZs, biomeHolders, worldScale), worldScale == 1.0
         );
         int roofBaseY = Math.max(baseY + buildingHeightBlocks(rasterized.feature().heightMeters(), worldScale), floorY + profile.floorCount() * profile.storeyHeightBlocks());
         int topY = roofBaseY + Math.max(profile.parapetHeight(), profile.roofRise());
         BuildingBlueprint blueprint = TellusBuildingBlueprints.create(rasterized.groupId(), rasterized.feature(), profile, this.generator.worldSeed(), baseY, floorY, roofBaseY, topY, List.of(), this.projection);
         TellusBuildingMaterials.BuildingMaterialPalette palette = TellusBuildingMaterials.resolvePalette(blueprint);
         int[] boundaryDistance = computeLodBoundaryDistance(rasterized);
         int occupiedCount = rasterized.occupiedCells().length;
         int[] structuralDistances = new int[occupiedCount];
         int[] cellTopYs = new int[occupiedCount];
         int[] orderByLocalIndex = new int[Math.max(1, rasterized.width() * rasterized.height())];
         Arrays.fill(cellTopYs, Integer.MIN_VALUE);
         Arrays.fill(orderByLocalIndex, -1);
         boolean groundContact = rasterized.feature().kind() != com.yucareux.tellus.world.data.osm.OsmBuildingKind.PART || minHeightBlocks <= 0;

         for (int order = 0; order < occupiedCount; order++) {
            int cellIndex = rasterized.occupiedCells()[order];
            int localX = cellIndex % worldXs.length;
            int localZ = cellIndex / worldXs.length;
            int localIndex = lodLocalIndex(rasterized, cellIndex);
            if (localIndex >= 0 && localIndex < orderByLocalIndex.length) {
               orderByLocalIndex[localIndex] = order;
            }
            int distance = scaleLodBoundaryDistance(boundaryDistance[order], cellSize);
            structuralDistances[order] = distance;
            int cellTopY = blueprint.roofTopY(worldXs[localX], worldZs[localZ], distance);
            if (isFlatRoof(profile.roofProfile()) && boundaryDistance[order] == 0) {
               cellTopY += profile.parapetHeight();
            }

            if (overlyingPartFloorY != null) {
               cellTopY = BuildingPlacementSupport.capLowerColumnTopY(floorY, cellTopY, overlyingPartFloorY[cellIndex]);
            }

            if (cellTopY < floorY) {
               continue;
            }
            cellTopYs[order] = cellTopY;
         }

         for (int order = 0; order < occupiedCount; order++) {
            int cellTopY = cellTopYs[order];
            if (cellTopY < floorY) {
               continue;
            }

            int cellIndex = rasterized.occupiedCells()[order];
            int localX = cellIndex % worldXs.length;
            int localZ = cellIndex / worldXs.length;
            int worldX = worldXs[localX];
            int worldZ = worldZs[localZ];
            int distance = structuralDistances[order];

            TellusLodGenerator.LodBuildingColumn column = columns[cellIndex];
            if (column == null) {
               column = new TellusLodGenerator.LodBuildingColumn();
               columns[cellIndex] = column;
            }

            int highestFloor = blueprint.highestActiveFloor(distance);
            int roofStart = Math.max(floorY, blueprint.roofBaseY(distance));
            int facadeEnd = Math.min(cellTopY, roofStart - 1);
            if (facadeEnd >= floorY) {
               addLodFacadeSpans(column, blueprint, palette, distance, worldX, worldZ, floorY, facadeEnd, highestFloor, cellSize);
            }

            if (cellTopY >= roofStart) {
               int roofMaterialDistance = lodFacadeDistanceForFloor(blueprint, distance, cellSize, highestFloor);
               BlockState roofBlock = TellusBuildingMaterials.resolveLodRoofBlock(blueprint, palette, roofMaterialDistance, worldX, worldZ);
               if (cellTopY > roofStart) {
                  column.addSpan(roofStart, cellTopY - 1, roofBlock, (byte)0);
               }
               column.addSpan(
                  cellTopY,
                  cellTopY,
                  resolveLodRoofSurfaceBlock(
                     rasterized, blueprint, palette, structuralDistances, boundaryDistance, cellTopYs, orderByLocalIndex, order, worldX, worldZ, cellSize
                  ),
                  (byte)0
               );
               BlockState roofDetail = TellusBuildingMaterials.resolveLodRoofDetailBlock(blueprint, palette, distance, worldX, worldZ);
               if (roofDetail != null) {
                  int detailHeight = TellusBuildingMaterials.resolveLodRoofDetailHeight(blueprint, distance, worldX, worldZ);
                  column.addSpan(cellTopY + 1, cellTopY + Math.max(1, detailHeight), roofDetail, (byte)0);
               }
            }

            if (groundContact && floorY - 1 > flattenedSurface[cellIndex]) {
               flattenedSurface[cellIndex] = floorY - 1;
            }

            if (recordedPartFloorY != null) {
               recordedPartFloorY[cellIndex] = Math.min(recordedPartFloorY[cellIndex], floorY);
            }
         }
      }
   }

   private Holder<Biome> sampleLodBuildingBiome(
      TellusLodGenerator.LodRasterizedBuildingFeature rasterized,
      int[] worldXs,
      int[] worldZs,
      Holder<Biome>[] biomeHolders,
      double worldScale
   ) {
      if (biomeHolders == null || rasterized.occupiedCells().length == 0) {
         return null;
      }

      double[] centroid = rasterized.feature().centroidWorld(this.projection);
      int bestCell = rasterized.occupiedCells()[0];
      double bestDistance = Double.POSITIVE_INFINITY;
      for (int cell : rasterized.occupiedCells()) {
         int localX = cell % worldXs.length;
         int localZ = cell / worldXs.length;
         double dx = worldXs[localX] - centroid[0];
         double dz = worldZs[localZ] - centroid[1];
         double distance = dx * dx + dz * dz;
         if (distance < bestDistance) {
            bestDistance = distance;
            bestCell = cell;
         }
      }

      return bestCell >= 0 && bestCell < biomeHolders.length ? biomeHolders[bestCell] : null;
   }

   private static void addLodFacadeSpans(
      TellusLodGenerator.LodBuildingColumn column,
      BuildingBlueprint blueprint,
      TellusBuildingMaterials.BuildingMaterialPalette palette,
      int boundaryDistance,
      int worldX,
      int worldZ,
      int floorY,
      int facadeEnd,
      int highestFloor,
      int cellSize
   ) {
      int floorCount = Math.max(1, highestFloor + 1);
      int maxBands = cellSize <= 2 ? 16 : cellSize <= 4 ? 10 : 6;
      int floorStep = Math.max(1, (floorCount + maxBands - 1) / maxBands);
      for (int floorIndex = 0; floorIndex <= highestFloor; floorIndex += floorStep) {
         int endFloor = Math.min(highestFloor, floorIndex + floorStep - 1);
         int bandStart = Math.max(floorY, blueprint.floorBottomY(floorIndex));
         int bandEnd = Math.min(facadeEnd, blueprint.floorTopY(endFloor));
         if (bandEnd < bandStart) {
            continue;
         }

         int sampleFloor = (floorIndex + endFloor) >>> 1;
         int materialDistance = lodFacadeDistanceForFloor(blueprint, boundaryDistance, cellSize, sampleFloor);
         BlockState facadeBlock = TellusBuildingMaterials.resolveLodFacadeBlock(
            blueprint, palette, materialDistance, worldX, worldZ, sampleFloor
         );
         column.addSpan(
            bandStart,
            bandEnd,
            facadeBlock,
            TellusBuildingLighting.resolveLodFacadeLightLevel(
               blueprint, facadeBlock, palette.window(), materialDistance, worldX, worldZ, sampleFloor, cellSize
            )
         );
      }
   }

   private static BlockState resolveLodRoofSurfaceBlock(
      TellusLodGenerator.LodRasterizedBuildingFeature rasterized,
      BuildingBlueprint blueprint,
      TellusBuildingMaterials.BuildingMaterialPalette palette,
      int[] structuralDistances,
      int[] boundaryDistance,
      int[] cellTopYs,
      int[] orderByLocalIndex,
      int order,
      int worldX,
      int worldZ,
      int cellSize
   ) {
      int distance = structuralDistances[order];
      int highestFloor = blueprint.highestActiveFloor(distance);
      int roofMaterialDistance = lodFacadeDistanceForFloor(blueprint, distance, cellSize, highestFloor);
      BlockState roofBlock = TellusBuildingMaterials.resolveLodRoofBlock(blueprint, palette, roofMaterialDistance, worldX, worldZ);
      if (!isPitchedRoof(blueprint.profile().roofProfile())) {
         return roofBlock;
      }

      Direction lowerDirection = lowerLodRoofNeighborDirection(
         rasterized, blueprint, structuralDistances, boundaryDistance, cellTopYs, orderByLocalIndex, order, worldX, worldZ
      );
      return lowerDirection == null ? roofBlock : TellusBuildingMaterials.resolveRoofStairBlock(palette, lowerDirection.getOpposite());
   }

   private static Direction lowerLodRoofNeighborDirection(
      TellusLodGenerator.LodRasterizedBuildingFeature rasterized,
      BuildingBlueprint blueprint,
      int[] structuralDistances,
      int[] boundaryDistance,
      int[] cellTopYs,
      int[] orderByLocalIndex,
      int order,
      int worldX,
      int worldZ
   ) {
      int localIndex = lodLocalIndex(rasterized, rasterized.occupiedCells()[order]);
      if (localIndex < 0) {
         return null;
      }

      int localX = localIndex % rasterized.width();
      int localZ = localIndex / rasterized.width();
      int cellTopY = cellTopYs[order];
      Direction bestDirection = null;
      int bestDrop = 0;
      for (Direction direction : Direction.Plane.HORIZONTAL) {
         int neighborX = localX + direction.getStepX();
         int neighborZ = localZ + direction.getStepZ();
         if (neighborX < 0 || neighborX >= rasterized.width() || neighborZ < 0 || neighborZ >= rasterized.height()) {
            continue;
         }

         int neighborLocalIndex = neighborX + neighborZ * rasterized.width();
         int neighborOrder = orderByLocalIndex[neighborLocalIndex];
         if (neighborOrder < 0 || cellTopYs[neighborOrder] == Integer.MIN_VALUE) {
            continue;
         }

         int drop = cellTopY - cellTopYs[neighborOrder];
         if (drop > bestDrop) {
            bestDrop = drop;
            bestDirection = direction;
         }
      }

      if (bestDrop > 0) {
         return bestDirection;
      }
      return boundaryDistance[order] == 0 ? lodRoofSlopeLowerDirection(blueprint, worldX, worldZ, structuralDistances[order]) : null;
   }

   private static Direction lodRoofSlopeLowerDirection(BuildingBlueprint blueprint, int worldX, int worldZ, int boundaryDistance) {
      int highestFloor = blueprint.highestActiveFloor(boundaryDistance);
      int setback = blueprint.setbackForFloor(highestFloor);
      int minX = blueprint.minWorldX() + setback;
      int maxX = blueprint.maxWorldX() - setback;
      int minZ = blueprint.minWorldZ() + setback;
      int maxZ = blueprint.maxWorldZ() - setback;
      double centerX = (minX + maxX) * 0.5;
      double centerZ = (minZ + maxZ) * 0.5;
      return switch (blueprint.profile().roofProfile()) {
         case GABLED_X -> worldZ <= centerZ ? Direction.NORTH : Direction.SOUTH;
         case GABLED_Z -> worldX <= centerX ? Direction.WEST : Direction.EAST;
         case SKILLION -> blueprint.width() >= blueprint.depth() ? Direction.WEST : Direction.NORTH;
         case HIPPED, PYRAMIDAL -> {
            int west = Math.abs(worldX - minX);
            int east = Math.abs(maxX - worldX);
            int north = Math.abs(worldZ - minZ);
            int south = Math.abs(maxZ - worldZ);
            int best = Math.min(Math.min(west, east), Math.min(north, south));
            if (best == west) {
               yield Direction.WEST;
            } else if (best == east) {
               yield Direction.EAST;
            } else if (best == north) {
               yield Direction.NORTH;
            }
            yield Direction.SOUTH;
         }
         default -> null;
      };
   }

   private static int lodFacadeDistanceForFloor(BuildingBlueprint blueprint, int structuralDistance, int cellSize, int floorIndex) {
      int setback = blueprint.setbackForFloor(floorIndex);
      int tolerance = Math.max(0, cellSize >> 1);
      return Math.abs(structuralDistance - setback) <= tolerance ? setback : structuralDistance;
   }

   private static int scaleLodBoundaryDistance(int boundaryDistance, int cellSize) {
      if (cellSize <= 1) {
         return Math.max(0, boundaryDistance);
      }
      return Math.max(0, boundaryDistance * cellSize + (cellSize >> 1));
   }

   private static int lodLocalIndex(TellusLodGenerator.LodRasterizedBuildingFeature rasterized, int globalIndex) {
      int gridX = globalIndex % rasterized.lodSize();
      int gridZ = globalIndex / rasterized.lodSize();
      int localX = gridX - rasterized.minGridX();
      int localZ = gridZ - rasterized.minGridZ();
      return localX < 0 || localX >= rasterized.width() || localZ < 0 || localZ >= rasterized.height()
         ? -1
         : localX + localZ * rasterized.width();
   }

   private static boolean isFlatRoof(BuildingProfile.RoofProfile roofProfile) {
      return roofProfile == BuildingProfile.RoofProfile.FLAT
         || roofProfile == BuildingProfile.RoofProfile.FLAT_PARAPET
         || roofProfile == BuildingProfile.RoofProfile.FLAT_CROWN
         || roofProfile == BuildingProfile.RoofProfile.FLAT_SKYLIGHT;
   }

   private static boolean isPitchedRoof(BuildingProfile.RoofProfile roofProfile) {
      return roofProfile == BuildingProfile.RoofProfile.GABLED_X
         || roofProfile == BuildingProfile.RoofProfile.GABLED_Z
         || roofProfile == BuildingProfile.RoofProfile.HIPPED
         || roofProfile == BuildingProfile.RoofProfile.PYRAMIDAL
         || roofProfile == BuildingProfile.RoofProfile.SKILLION;
   }

   private static int[] computeLodBoundaryDistance(TellusLodGenerator.LodRasterizedBuildingFeature rasterized) {
      int width = rasterized.width();
      int height = rasterized.height();
      int[] distance = new int[width * height];
      Arrays.fill(distance, Integer.MAX_VALUE);
      boolean[] occupied = rasterized.occupiedMask();
      ArrayDeque<Integer> queue = new ArrayDeque<>();

      for (int localZ = 0; localZ < height; localZ++) {
         for (int localX = 0; localX < width; localX++) {
            int index = localX + localZ * width;
            if (!occupied[index]) {
               continue;
            }

            boolean boundary = localX == 0
               || localX == width - 1
               || localZ == 0
               || localZ == height - 1
               || !occupied[Math.max(0, localX - 1) + localZ * width]
               || !occupied[Math.min(width - 1, localX + 1) + localZ * width]
               || !occupied[localX + Math.max(0, localZ - 1) * width]
               || !occupied[localX + Math.min(height - 1, localZ + 1) * width];
            if (boundary) {
               distance[index] = 0;
               queue.add(index);
            }
         }
      }

      while (!queue.isEmpty()) {
         int index = queue.removeFirst();
         int localX = index % width;
         int localZ = index / width;
         int nextDistance = distance[index] + 1;
         if (localX > 0) {
            propagateLodBoundaryDistance(occupied, distance, queue, index - 1, nextDistance);
         }
         if (localX + 1 < width) {
            propagateLodBoundaryDistance(occupied, distance, queue, index + 1, nextDistance);
         }
         if (localZ > 0) {
            propagateLodBoundaryDistance(occupied, distance, queue, index - width, nextDistance);
         }
         if (localZ + 1 < height) {
            propagateLodBoundaryDistance(occupied, distance, queue, index + width, nextDistance);
         }
      }

      int[] ordered = new int[rasterized.occupiedCells().length];
      for (int order = 0; order < rasterized.occupiedCells().length; order++) {
         int globalIndex = rasterized.occupiedCells()[order];
         int gridX = globalIndex % rasterized.lodSize();
         int gridZ = globalIndex / rasterized.lodSize();
         int localIndex = (gridX - rasterized.minGridX()) + (gridZ - rasterized.minGridZ()) * width;
         ordered[order] = localIndex >= 0 && localIndex < distance.length && distance[localIndex] != Integer.MAX_VALUE ? distance[localIndex] : 0;
      }
      return ordered;
   }

   private static void propagateLodBoundaryDistance(boolean[] occupied, int[] distance, ArrayDeque<Integer> queue, int index, int nextDistance) {
      if (occupied[index] && nextDistance < distance[index]) {
         distance[index] = nextDistance;
         queue.add(index);
      }
   }

   private static int buildingHeightBlocks(double meters, double worldScale) {
      return Math.max(3, (int)Math.round(meters / worldScale));
   }

   private static int buildingMinHeightBlocks(double meters, double worldScale) {
      return Math.max(0, (int)Math.round(meters / worldScale));
   }

   private static int medianValue(IntArrayList values) {
      int[] sorted = values.toIntArray();
      Arrays.sort(sorted);
      return sorted[sorted.length >> 1];
   }

   private void rasterizeLodRoadAreas(
      List<RoadAreaFeature> roadAreas,
      double worldScale,
      int[] worldXs,
      int[] worldZs,
      int lodSizePoints,
      byte[] selectedClass,
      byte[] selectedStyle
   ) {
      if (roadAreas == null || roadAreas.isEmpty() || !(worldScale > 0.0) || lodSizePoints <= 0) {
         return;
      }

      double blocksPerDegree = this.projection.equatorialBlocksPerDegree();
      int minWorldX = Math.min(worldXs[0], worldXs[lodSizePoints - 1]);
      int minWorldZ = Math.min(worldZs[0], worldZs[lodSizePoints - 1]);
      int maxWorldX = Math.max(worldXs[0], worldXs[lodSizePoints - 1]);
      int maxWorldZ = Math.max(worldZs[0], worldZs[lodSizePoints - 1]);
      int stepX = lodSizePoints > 1 ? Math.max(1, Math.abs(worldXs[1] - worldXs[0])) : 1;
      int stepZ = lodSizePoints > 1 ? Math.max(1, Math.abs(worldZs[1] - worldZs[0])) : stepX;
      for (RoadAreaFeature area : roadAreas) {
         int minGridX = Mth.clamp((int)Math.floor((this.projection.lonToBlockX(area.minLon()) - minWorldX) / stepX), 0, lodSizePoints - 1);
         int maxGridX = Mth.clamp((int)Math.ceil((this.projection.lonToBlockX(area.maxLon()) - minWorldX) / stepX), 0, lodSizePoints - 1);
         int minGridZ = Mth.clamp((int)Math.floor((this.projection.latToBlockZ(area.maxLat()) - minWorldZ) / stepZ), 0, lodSizePoints - 1);
         int maxGridZ = Mth.clamp((int)Math.ceil((this.projection.latToBlockZ(area.minLat()) - minWorldZ) / stepZ), 0, lodSizePoints - 1);
         byte classId = (byte)roadClassId(area.roadClass());
         byte style = RoadSurfaceStyle.surfaceStyleId(area.roadClass(), area.highwayTag(), area.roadSurface(), area.subclass(), 0, 0);
         if (maxGridX < minGridX || maxGridZ < minGridZ || this.projection.lonToBlockX(area.maxLon()) < minWorldX || this.projection.lonToBlockX(area.minLon()) > maxWorldX) {
            continue;
         }

         for (int gz = minGridZ; gz <= maxGridZ; gz++) {
            int row = gz * lodSizePoints;
            double sampleZ = worldZs[gz];
            if (sampleZ < minWorldZ || sampleZ > maxWorldZ) {
               continue;
            }
            for (int gx = minGridX; gx <= maxGridX; gx++) {
               int index = row + gx;
               if (selectedClass[index] > 0) {
                  continue;
               }

               double sampleX = worldXs[gx];
               double lon = this.projection.blockXToLon(sampleX);
               double lat = this.projection.blockZToLat(sampleZ);
               if (area.containsLonLat(lon, lat)) {
                  selectedClass[index] = classId;
                  if (selectedStyle != null) {
                     selectedStyle[index] = style;
                  }
               }
            }
         }
      }
   }

   private void rasterizeLodRoadClass(
      List<RoadFeature> roads,
      byte classId,
      int widthBlocks,
      double blocksPerDegree,
      int[] worldXs,
      int[] worldZs,
      int lodSizePoints,
      int cellSize,
      byte[] selectedClass,
      byte[] selectedStyle,
      boolean[] selectedMarking
   ) {
      if (!roads.isEmpty() && widthBlocks > 0 && lodSizePoints > 0 && cellSize > 0) {
         double worldScale = EarthProjection.worldScaleFromBlocksPerDegree(blocksPerDegree);
         double minWorldX = Math.min(worldXs[0], worldXs[lodSizePoints - 1]);
         double minWorldZ = Math.min(worldZs[0], worldZs[lodSizePoints - 1]);
         double maxWorldX = Math.max(worldXs[0], worldXs[lodSizePoints - 1]);
         double maxWorldZ = Math.max(worldZs[0], worldZs[lodSizePoints - 1]);

         for (RoadFeature road : roads) {
            int points = road.pointCount();
            if (points >= 2) {
               int featureWidthBlocks = RoadSurfaceStyle.effectiveRoadWidth(road, widthBlocks, worldScale);
               double halfWidth = Math.max(0.5, (featureWidthBlocks - 1) * 0.5) + cellSize * 0.5;
               double radiusSq = halfWidth * halfWidth + 1.0E-6;
               double roadLonA = this.projection.lonToBlockX(road.minLon());
               double roadLonB = this.projection.lonToBlockX(road.maxLon());
               double roadMinX = Math.min(roadLonA, roadLonB);
               double roadMaxX = Math.max(roadLonA, roadLonB);
               double roadMinZ = this.projection.latToBlockZ(road.maxLat());
               double roadMaxZ = this.projection.latToBlockZ(road.minLat());
               if (!(roadMaxX < minWorldX - halfWidth)
                  && !(roadMinX > maxWorldX + halfWidth)
                  && !(roadMaxZ < minWorldZ - halfWidth)
                  && !(roadMinZ > maxWorldZ + halfWidth)) {
                  double x1 = this.projection.lonToBlockX(road.lonAt(0));
                  double z1 = this.projection.latToBlockZ(road.latAt(0));
                  double segmentStart = 0.0;

                  for (int i = 1; i < points; i++) {
                     double x2 = this.projection.lonToBlockX(road.lonAt(i));
                     double z2 = this.projection.latToBlockZ(road.latAt(i));
                     double dx = x2 - x1;
                     double dz = z2 - z1;
                     double lenSq = dx * dx + dz * dz;
                     if (lenSq <= 1.0E-6) {
                        x1 = x2;
                        z1 = z2;
                     } else {
                        double segmentLength = Math.sqrt(lenSq);
                        int minGridX = Mth.clamp((int)Math.floor((Math.min(x1, x2) - halfWidth - minWorldX) / cellSize), 0, lodSizePoints - 1);
                        int maxGridX = Mth.clamp((int)Math.floor((Math.max(x1, x2) + halfWidth - minWorldX) / cellSize), 0, lodSizePoints - 1);
                        int minGridZ = Mth.clamp((int)Math.floor((Math.min(z1, z2) - halfWidth - minWorldZ) / cellSize), 0, lodSizePoints - 1);
                        int maxGridZ = Mth.clamp((int)Math.floor((Math.max(z1, z2) + halfWidth - minWorldZ) / cellSize), 0, lodSizePoints - 1);

                        for (int gz = minGridZ; gz <= maxGridZ; gz++) {
                           double sampleZ = worldZs[gz];
                           int row = gz * lodSizePoints;

                           for (int gx = minGridX; gx <= maxGridX; gx++) {
                              int index = row + gx;
                              if (selectedClass[index] == 0) {
                                 double sampleX = worldXs[gx];
                                 double t = ((sampleX - x1) * dx + (sampleZ - z1) * dz) / lenSq;
                                 t = Mth.clamp(t, 0.0, 1.0);
                                 double px = x1 + t * dx;
                                 double pz = z1 + t * dz;
                                 double ddx = sampleX - px;
                                 double ddz = sampleZ - pz;
                                 double distanceSq = ddx * ddx + ddz * ddz;
                                 if (distanceSq <= radiusSq) {
                                    selectedClass[index] = classId;
                                    if (selectedStyle != null) {
                                       selectedStyle[index] = RoadSurfaceStyle.surfaceStyleId(road, worldXs[gx], worldZs[gz]);
                                    }
                                    if (selectedMarking != null) {
                                       double station = segmentStart + t * segmentLength;
                                       double lateralDistance = ((sampleX - x1) * -dz + (sampleZ - z1) * dx) / segmentLength;
                                       selectedMarking[index] = RoadSurfaceStyle.shouldDrawLaneMarking(
                                          road, featureWidthBlocks, station, lateralDistance, Math.sqrt(distanceSq), Math.max(0.45, cellSize * 0.35)
                                       );
                                    }
                                 }
                              }
                           }
                        }

                        segmentStart += segmentLength;
                        x1 = x2;
                        z1 = z2;
                     }
                  }
               }
            }
         }
      }
   }

   private boolean rasterizeLodExactRoadLights(
      List<OsmStreetLightFeature> streetLights,
      double blocksPerDegree,
      double worldScale,
      int[] worldXs,
      int[] worldZs,
      int[] surfaceYs,
      int lodSizePoints,
      int cellSize,
      byte[] selectedClass,
      int[] bridgeDeckY,
      int[] bridgeSupportShaftBottomY,
      int[] bridgeSupportShaftTopY,
      int[] bridgeSupportCapBottomY,
      int[] bridgeSupportCapTopY,
      TellusLodGenerator.LodBuildingColumn[] buildingColumns,
      int[] roadLightBaseY,
      byte[] roadLightFenceCount,
      boolean[] occupiedLightCells,
      IntArrayList occupiedLightIndices
   ) {
      if (streetLights == null || streetLights.isEmpty() || lodSizePoints <= 0 || cellSize <= 0) {
         return false;
      }

      int spacingBlocks = roadLightSpacingBlocks(worldScale);
      int minLampSpacingBlocks = roadLightMinimumSpacingBlocks(spacingBlocks);
      int fenceCount = roadLightFenceCount(worldScale);
      double minWorldX = Math.min(worldXs[0], worldXs[lodSizePoints - 1]);
      double minWorldZ = Math.min(worldZs[0], worldZs[lodSizePoints - 1]);
      boolean hasRoadLights = false;

      for (OsmStreetLightFeature streetLight : streetLights) {
         double lampX = this.projection.lonToBlockX(streetLight.longitude());
         double lampZ = this.projection.latToBlockZ(streetLight.latitude());
         int anchorIndex = findLodExactRoadLightAnchor(lampX, lampZ, worldXs, worldZs, lodSizePoints, cellSize, minWorldX, minWorldZ, selectedClass);
         if (anchorIndex < 0 || occupiedLightCells[anchorIndex] || hasNearbyLodRoadLight(anchorIndex, worldXs, worldZs, minLampSpacingBlocks, occupiedLightIndices)) {
            continue;
         }

         int baseY = bridgeDeckY != null && bridgeDeckY[anchorIndex] != Integer.MIN_VALUE ? bridgeDeckY[anchorIndex] : surfaceYs[anchorIndex];
         int minLampY = baseY + 1;
         int maxLampY = baseY + fenceCount + 3;
         TellusLodGenerator.LodBuildingColumn buildingColumn = buildingColumns == null ? null : buildingColumns[anchorIndex];
         if ((buildingColumn == null || !buildingColumn.intersectsSpan(minLampY, maxLampY))
            && !lodRoadLightBridgeSupportConflicts(
               anchorIndex, minLampY, maxLampY, bridgeSupportShaftBottomY, bridgeSupportShaftTopY, bridgeSupportCapBottomY, bridgeSupportCapTopY
            )) {
            roadLightBaseY[anchorIndex] = baseY;
            roadLightFenceCount[anchorIndex] = (byte)fenceCount;
            occupiedLightCells[anchorIndex] = true;
            occupiedLightIndices.add(anchorIndex);
            hasRoadLights = true;
         }
      }

      return hasRoadLights;
   }

   private static int findLodExactRoadLightAnchor(
      double lampX,
      double lampZ,
      int[] worldXs,
      int[] worldZs,
      int lodSizePoints,
      int cellSize,
      double minWorldX,
      double minWorldZ,
      byte[] selectedClass
   ) {
      double scanRadius = 5.0 + cellSize;
      int minGridX = Mth.clamp((int)Math.floor((lampX - scanRadius - minWorldX) / cellSize), 0, lodSizePoints - 1);
      int maxGridX = Mth.clamp((int)Math.floor((lampX + scanRadius - minWorldX) / cellSize), 0, lodSizePoints - 1);
      int minGridZ = Mth.clamp((int)Math.floor((lampZ - scanRadius - minWorldZ) / cellSize), 0, lodSizePoints - 1);
      int maxGridZ = Mth.clamp((int)Math.floor((lampZ + scanRadius - minWorldZ) / cellSize), 0, lodSizePoints - 1);
      double maxDistanceSq = scanRadius * scanRadius;
      int bestIndex = -1;
      int bestBoundary = -1;
      double bestDistanceSq = Double.POSITIVE_INFINITY;

      for (int gz = minGridZ; gz <= maxGridZ; gz++) {
         double sampleZ = worldZs[gz];
         int row = gz * lodSizePoints;

         for (int gx = minGridX; gx <= maxGridX; gx++) {
            int index = row + gx;
            if (selectedClass[index] <= 0) {
               continue;
            }

            int boundary = lodRoadBoundaryScore(gx, gz, lodSizePoints, selectedClass);
            if (boundary <= 0) {
               continue;
            }

            double dx = worldXs[gx] - lampX;
            double dz = sampleZ - lampZ;
            double distanceSq = dx * dx + dz * dz;
            if (distanceSq <= maxDistanceSq && (boundary > bestBoundary || boundary == bestBoundary && distanceSq < bestDistanceSq)) {
               bestBoundary = boundary;
               bestDistanceSq = distanceSq;
               bestIndex = index;
            }
         }
      }

      return bestIndex;
   }

   private static int lodRoadBoundaryScore(int gridX, int gridZ, int lodSizePoints, byte[] selectedClass) {
      int index = gridZ * lodSizePoints + gridX;
      if (selectedClass[index] <= 0) {
         return 0;
      }

      int score = 0;
      if (gridX <= 0 || selectedClass[index - 1] == 0) {
         score++;
      }
      if (gridX >= lodSizePoints - 1 || selectedClass[index + 1] == 0) {
         score++;
      }
      if (gridZ <= 0 || selectedClass[index - lodSizePoints] == 0) {
         score++;
      }
      if (gridZ >= lodSizePoints - 1 || selectedClass[index + lodSizePoints] == 0) {
         score++;
      }

      return score;
   }

   private boolean rasterizeLodRoadLights(
      List<RoadFeature> roads,
      byte classId,
      int roadWidth,
      double blocksPerDegree,
      int[] worldXs,
      int[] worldZs,
      int[] surfaceYs,
      int lodSizePoints,
      int cellSize,
      byte[] selectedClass,
      int[] bridgeDeckY,
      int[] bridgeSupportShaftBottomY,
      int[] bridgeSupportShaftTopY,
      int[] bridgeSupportCapBottomY,
      int[] bridgeSupportCapTopY,
      TellusLodGenerator.LodBuildingColumn[] buildingColumns,
      int[] roadLightBaseY,
      byte[] roadLightFenceCount,
      boolean[] occupiedLightCells,
      IntArrayList occupiedLightIndices
   ) {
      if (roads.isEmpty() || roadWidth < ROAD_LIGHT_MIN_ROAD_WIDTH_BLOCKS || lodSizePoints <= 0 || cellSize <= 0) {
         return false;
      } else {
         double worldScale = EarthProjection.worldScaleFromBlocksPerDegree(blocksPerDegree);
         int spacingBlocks = roadLightSpacingBlocks(worldScale);
         int minLampSpacingBlocks = roadLightMinimumSpacingBlocks(spacingBlocks);
         int fenceCount = roadLightFenceCount(worldScale);
         double minWorldX = Math.min(worldXs[0], worldXs[lodSizePoints - 1]);
         double minWorldZ = Math.min(worldZs[0], worldZs[lodSizePoints - 1]);
         boolean hasRoadLights = false;

         for (RoadFeature road : roads) {
            if (road.mode() == RoadMode.TUNNEL || BridgeSupportLayout.crossesWorldSeam(road, this.projection)) {
               continue;
            }

            int featureRoadWidth = RoadSurfaceStyle.effectiveRoadWidth(road, roadWidth, worldScale);
            if (featureRoadWidth < ROAD_LIGHT_MIN_ROAD_WIDTH_BLOCKS) {
               continue;
            }

            int segmentCount = road.pointCount() - 1;
            if (segmentCount <= 0) {
               continue;
            }

            double[] roadWorldXs = new double[road.pointCount()];
            double[] roadWorldZs = new double[road.pointCount()];
            double[] segmentStarts = new double[segmentCount];
            double[] segmentLengths = new double[segmentCount];

            for (int i = 0; i < road.pointCount(); i++) {
               roadWorldXs[i] = this.projection.lonToBlockX(road.lonAt(i));
               roadWorldZs[i] = this.projection.latToBlockZ(road.latAt(i));
            }

            double totalLength = 0.0;
            for (int i = 0; i < segmentCount; i++) {
               double dx = roadWorldXs[i + 1] - roadWorldXs[i];
               double dz = roadWorldZs[i + 1] - roadWorldZs[i];
               segmentStarts[i] = totalLength;
               segmentLengths[i] = Math.sqrt(dx * dx + dz * dz);
               totalLength += segmentLengths[i];
            }

            double endpointInset = Math.max(Math.max(4.0, featureRoadWidth), spacingBlocks * 0.75);
            if (!(totalLength > endpointInset * 2.0)) {
               continue;
            }

            boolean placeLeft = true;
            for (double station = endpointInset; station <= totalLength - endpointInset + 1.0E-6; station += spacingBlocks) {
               TellusLodGenerator.SampledRoadStation sampled = sampleRoadStation(roadWorldXs, roadWorldZs, segmentStarts, segmentLengths, station);
               if (sampled == null) {
                  placeLeft = !placeLeft;
                  continue;
               }

               int anchorIndex = findLodRoadLightAnchor(
                  sampled,
                  placeLeft,
                  featureRoadWidth,
                  classId,
                  road.mode(),
                  worldXs,
                  worldZs,
                  lodSizePoints,
                  cellSize,
                  minWorldX,
                  minWorldZ,
                  selectedClass,
                  bridgeDeckY
               );
               if (anchorIndex >= 0
                  && !occupiedLightCells[anchorIndex]
                  && !hasNearbyLodRoadLight(anchorIndex, worldXs, worldZs, minLampSpacingBlocks, occupiedLightIndices)) {
                  int baseY = road.mode() == RoadMode.BRIDGE && bridgeDeckY != null && bridgeDeckY[anchorIndex] != Integer.MIN_VALUE
                     ? bridgeDeckY[anchorIndex]
                     : surfaceYs[anchorIndex];
                  int minLampY = baseY + 1;
                  int maxLampY = baseY + fenceCount + 3;
                  TellusLodGenerator.LodBuildingColumn buildingColumn = buildingColumns == null ? null : buildingColumns[anchorIndex];
                  if ((buildingColumn == null || !buildingColumn.intersectsSpan(minLampY, maxLampY))
                     && !lodRoadLightBridgeSupportConflicts(
                        anchorIndex, minLampY, maxLampY, bridgeSupportShaftBottomY, bridgeSupportShaftTopY, bridgeSupportCapBottomY, bridgeSupportCapTopY
                     )) {
                     roadLightBaseY[anchorIndex] = baseY;
                     roadLightFenceCount[anchorIndex] = (byte)fenceCount;
                     occupiedLightCells[anchorIndex] = true;
                     occupiedLightIndices.add(anchorIndex);
                     hasRoadLights = true;
                  }
               }

               placeLeft = !placeLeft;
            }
         }

         return hasRoadLights;
      }
   }

   private static int findLodRoadLightAnchor(
      TellusLodGenerator.SampledRoadStation sampled,
      boolean placeLeft,
      int roadWidth,
      byte classId,
      RoadMode roadMode,
      int[] worldXs,
      int[] worldZs,
      int lodSizePoints,
      int cellSize,
      double minWorldX,
      double minWorldZ,
      byte[] selectedClass,
      int[] bridgeDeckY
   ) {
      double normalX = placeLeft ? -sampled.tangentZ() : sampled.tangentZ();
      double normalZ = placeLeft ? sampled.tangentX() : -sampled.tangentX();
      double scanRadius = Math.max(cellSize + 1.0, roadWidth + cellSize);
      double alongTolerance = Math.max(cellSize * 0.6, roadWidth * 0.45 + cellSize * 0.25);
      double minimumEdgeLateral = Math.max(cellSize * 0.25, Math.max(cellSize * 0.5, (roadWidth - 1) * 0.5) - ROAD_LIGHT_EDGE_TOLERANCE_BLOCKS);
      int minGridX = Mth.clamp((int)Math.floor((sampled.worldX() - scanRadius - minWorldX) / cellSize), 0, lodSizePoints - 1);
      int maxGridX = Mth.clamp((int)Math.floor((sampled.worldX() + scanRadius - minWorldX) / cellSize), 0, lodSizePoints - 1);
      int minGridZ = Mth.clamp((int)Math.floor((sampled.worldZ() - scanRadius - minWorldZ) / cellSize), 0, lodSizePoints - 1);
      int maxGridZ = Mth.clamp((int)Math.floor((sampled.worldZ() + scanRadius - minWorldZ) / cellSize), 0, lodSizePoints - 1);
      int bestIndex = -1;
      double bestLateral = Double.NEGATIVE_INFINITY;
      double bestAlong = Double.POSITIVE_INFINITY;
      double bestDistanceSq = Double.POSITIVE_INFINITY;
      double minLateral = Double.POSITIVE_INFINITY;
      double maxLateral = Double.NEGATIVE_INFINITY;

      for (int gz = minGridZ; gz <= maxGridZ; gz++) {
         double sampleZ = worldZs[gz];
         int row = gz * lodSizePoints;

         for (int gx = minGridX; gx <= maxGridX; gx++) {
            int index = row + gx;
            boolean bridgeCell = bridgeDeckY != null && bridgeDeckY[index] != Integer.MIN_VALUE;
            boolean modeMatches = roadMode == RoadMode.BRIDGE ? bridgeCell : !bridgeCell;
            if (selectedClass[index] == classId && modeMatches) {
               double dx = worldXs[gx] - sampled.worldX();
               double dz = sampleZ - sampled.worldZ();
               double along = dx * sampled.tangentX() + dz * sampled.tangentZ();
               if (!(Math.abs(along) > alongTolerance)) {
                  double lateral = dx * normalX + dz * normalZ;
                  minLateral = Math.min(minLateral, lateral);
                  maxLateral = Math.max(maxLateral, lateral);
                  if (!(lateral < minimumEdgeLateral)) {
                     double distanceSq = dx * dx + dz * dz;
                     double absAlong = Math.abs(along);
                     if (lateral > bestLateral + 1.0E-6
                        || Math.abs(lateral - bestLateral) <= 1.0E-6 && absAlong < bestAlong - 1.0E-6
                        || Math.abs(lateral - bestLateral) <= 1.0E-6 && Math.abs(absAlong - bestAlong) <= 1.0E-6 && distanceSq < bestDistanceSq) {
                        bestLateral = lateral;
                        bestAlong = absAlong;
                        bestDistanceSq = distanceSq;
                        bestIndex = index;
                     }
                  }
               }
            }
         }
      }

      if (bestIndex < 0 || minLateral == Double.POSITIVE_INFINITY || maxLateral == Double.NEGATIVE_INFINITY) {
         return -1;
      }

      double span = maxLateral - minLateral;
      if (span > roadWidth + cellSize * 0.75) {
         return -1;
      }

      return bestIndex;
   }

   private static boolean hasNearbyLodRoadLight(
      int anchorIndex, int[] worldXs, int[] worldZs, int minSpacingBlocks, IntArrayList occupiedLightIndices
   ) {
      if (occupiedLightIndices == null || occupiedLightIndices.isEmpty()) {
         return false;
      } else {
         int gridSize = worldXs.length;
         int anchorX = anchorIndex % gridSize;
         int anchorZ = anchorIndex / gridSize;
         int anchorWorldX = worldXs[anchorX];
         int anchorWorldZ = worldZs[anchorZ];
         int minSpacingSq = minSpacingBlocks * minSpacingBlocks;

         for (int i = 0; i < occupiedLightIndices.size(); i++) {
            int occupiedIndex = occupiedLightIndices.getInt(i);
            int occupiedX = occupiedIndex % gridSize;
            int occupiedZ = occupiedIndex / gridSize;
            int dx = worldXs[occupiedX] - anchorWorldX;
            int dz = worldZs[occupiedZ] - anchorWorldZ;
            if (dx * dx + dz * dz < minSpacingSq) {
               return true;
            }
         }

         return false;
      }
   }

   private static boolean lodRoadLightBridgeSupportConflicts(
      int index,
      int minY,
      int maxY,
      int[] bridgeSupportShaftBottomY,
      int[] bridgeSupportShaftTopY,
      int[] bridgeSupportCapBottomY,
      int[] bridgeSupportCapTopY
   ) {
      return bridgeSupportShaftBottomY != null
            && bridgeSupportShaftTopY != null
            && bridgeSupportShaftBottomY[index] != Integer.MIN_VALUE
            && bridgeSupportShaftTopY[index] != Integer.MIN_VALUE
            && bridgeSupportShaftTopY[index] >= minY
            && bridgeSupportShaftBottomY[index] <= maxY
         || bridgeSupportCapBottomY != null
            && bridgeSupportCapTopY != null
            && bridgeSupportCapBottomY[index] != Integer.MIN_VALUE
            && bridgeSupportCapTopY[index] != Integer.MIN_VALUE
            && bridgeSupportCapTopY[index] >= minY
            && bridgeSupportCapBottomY[index] <= maxY;
   }

   private static IDhApiBlockStateWrapper lodRoadBlockForStyle(
      int roadClassId,
      byte style,
      boolean marking,
      IDhApiBlockStateWrapper roadMainBlock,
      IDhApiBlockStateWrapper roadNormalBlock,
      IDhApiBlockStateWrapper roadDirtBlock,
      IDhApiBlockStateWrapper roadGravelBlock,
      IDhApiBlockStateWrapper roadMarkingBlock
   ) {
      if (marking) {
         return roadMarkingBlock;
      }

      return switch (style) {
         case RoadSurfaceStyle.STYLE_PAVED_LIGHT,
            RoadSurfaceStyle.STYLE_PAVED_SMOOTH,
            RoadSurfaceStyle.STYLE_PEDESTRIAN,
            RoadSurfaceStyle.STYLE_COBBLESTONE,
            RoadSurfaceStyle.STYLE_STONE_PAVERS,
            RoadSurfaceStyle.STYLE_BRICK,
            RoadSurfaceStyle.STYLE_CONCRETE -> roadNormalBlock;
         case RoadSurfaceStyle.STYLE_SAND, RoadSurfaceStyle.STYLE_WOOD -> roadDirtBlock;
         case RoadSurfaceStyle.STYLE_GRAVEL -> roadGravelBlock;
         case RoadSurfaceStyle.STYLE_DIRT -> roadDirtBlock;
         default -> switch (roadClassId) {
            case 1 -> roadMainBlock;
            case 2 -> roadNormalBlock;
            default -> roadDirtBlock;
         };
      };
   }

   private boolean rasterizeLodBridgeDeck(
      List<RoadFeature> roads,
      byte classId,
      int widthBlocks,
      double blocksPerDegree,
      int[] worldXs,
      int[] worldZs,
      int lodSizePoints,
      int cellSize,
      byte[] selectedClass,
      int[] bridgeDeckY,
      Map<Long, Integer> roadSurfaceCache
   ) {
      if (!roads.isEmpty() && widthBlocks > 0 && lodSizePoints > 0 && cellSize > 0) {
         double worldScale = EarthProjection.worldScaleFromBlocksPerDegree(blocksPerDegree);
         double minWorldX = Math.min(worldXs[0], worldXs[lodSizePoints - 1]);
         double minWorldZ = Math.min(worldZs[0], worldZs[lodSizePoints - 1]);
         double maxWorldX = Math.max(worldXs[0], worldXs[lodSizePoints - 1]);
         double maxWorldZ = Math.max(worldZs[0], worldZs[lodSizePoints - 1]);
         boolean hasBridgeDeck = false;

         for (RoadFeature road : roads) {
            int points = road.pointCount();
            if (points >= 2) {
               int featureWidthBlocks = RoadSurfaceStyle.effectiveRoadWidth(road, widthBlocks, worldScale);
               double halfWidth = Math.max(0.5, (featureWidthBlocks - 1) * 0.5) + cellSize * 0.5;
               double radiusSq = halfWidth * halfWidth + 1.0E-6;
               double roadLonA = this.projection.lonToBlockX(road.minLon());
               double roadLonB = this.projection.lonToBlockX(road.maxLon());
               double roadMinX = Math.min(roadLonA, roadLonB);
               double roadMaxX = Math.max(roadLonA, roadLonB);
               double roadMinZ = this.projection.latToBlockZ(road.maxLat());
               double roadMaxZ = this.projection.latToBlockZ(road.minLat());
               if (!(roadMaxX < minWorldX - halfWidth)
                  && !(roadMinX > maxWorldX + halfWidth)
                  && !(roadMaxZ < minWorldZ - halfWidth)
                  && !(roadMinZ > maxWorldZ + halfWidth)) {
                  double startWorldX = this.projection.lonToBlockX(road.lonAt(0));
                  double startWorldZ = this.projection.latToBlockZ(road.latAt(0));
                  double previousX = startWorldX;
                  double previousZ = startWorldZ;
                  double endWorldX = startWorldX;
                  double endWorldZ = startWorldZ;
                  double totalLength = 0.0;

                  for (int i = 1; i < points; i++) {
                     double currentX = this.projection.lonToBlockX(road.lonAt(i));
                     double currentZ = this.projection.latToBlockZ(road.latAt(i));
                     double deltaX = currentX - previousX;
                     double deltaZ = currentZ - previousZ;
                     double segmentLength = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
                     totalLength += segmentLength;
                     previousX = currentX;
                     previousZ = currentZ;
                     endWorldX = currentX;
                     endWorldZ = currentZ;
                  }

                  if (!(totalLength <= 1.0E-6)) {
                     int startSurface = this.sampleRoadSurfaceForLodBridge(Mth.floor(startWorldX), Mth.floor(startWorldZ), roadSurfaceCache);
                     int endSurface = this.sampleRoadSurfaceForLodBridge(Mth.floor(endWorldX), Mth.floor(endWorldZ), roadSurfaceCache);
                     double segmentStart = 0.0;
                     double x1 = startWorldX;
                     double z1 = startWorldZ;

                     for (int i = 1; i < points; i++) {
                        double x2 = this.projection.lonToBlockX(road.lonAt(i));
                        double z2 = this.projection.latToBlockZ(road.latAt(i));
                        double dx = x2 - x1;
                        double dz = z2 - z1;
                        double lenSq = dx * dx + dz * dz;
                        if (lenSq <= 1.0E-6) {
                           x1 = x2;
                           z1 = z2;
                        } else {
                           double segmentLength = Math.sqrt(lenSq);
                           int minGridX = Mth.clamp((int)Math.floor((Math.min(x1, x2) - halfWidth - minWorldX) / cellSize), 0, lodSizePoints - 1);
                           int maxGridX = Mth.clamp((int)Math.floor((Math.max(x1, x2) + halfWidth - minWorldX) / cellSize), 0, lodSizePoints - 1);
                           int minGridZ = Mth.clamp((int)Math.floor((Math.min(z1, z2) - halfWidth - minWorldZ) / cellSize), 0, lodSizePoints - 1);
                           int maxGridZ = Mth.clamp((int)Math.floor((Math.max(z1, z2) + halfWidth - minWorldZ) / cellSize), 0, lodSizePoints - 1);

                           for (int gz = minGridZ; gz <= maxGridZ; gz++) {
                              double sampleZ = worldZs[gz];
                              int row = gz * lodSizePoints;

                              for (int gx = minGridX; gx <= maxGridX; gx++) {
                                 int index = row + gx;
                                 if (selectedClass[index] == classId) {
                                    double sampleX = worldXs[gx];
                                    double t = ((sampleX - x1) * dx + (sampleZ - z1) * dz) / lenSq;
                                    t = Mth.clamp(t, 0.0, 1.0);
                                    double px = x1 + t * dx;
                                    double pz = z1 + t * dz;
                                    double ddx = sampleX - px;
                                    double ddz = sampleZ - pz;
                                    if (!(ddx * ddx + ddz * ddz > radiusSq)) {
                                       double station = segmentStart + t * segmentLength;
                                       int localRoadSurface = this.sampleRoadSurfaceForLodBridge(
                                          Mth.floor(sampleX), Mth.floor(sampleZ), roadSurfaceCache
                                       );
                                       int deckY = bridgeDeckYAtStation(
                                          station,
                                          totalLength,
                                          startSurface,
                                          endSurface,
                                          localRoadSurface,
                                          road.bridgeLevel(),
                                          road.roadClass(),
                                          worldScale
                                       );
                                       if (deckY > bridgeDeckY[index]) {
                                          bridgeDeckY[index] = deckY;
                                          hasBridgeDeck = true;
                                       }
                                    }
                                 }
                              }
                           }

                           segmentStart += segmentLength;
                           x1 = x2;
                           z1 = z2;
                        }
                     }
                  }
               }
            }
         }

         return hasBridgeDeck;
      } else {
         return false;
      }
   }

   private boolean rasterizeLodBridgeSupports(
      List<RoadFeature> roads,
      int roadWidth,
      double blocksPerDegree,
      int[] worldXs,
      int[] worldZs,
      int[] surfaceYs,
      int lodSizePoints,
      int cellSize,
      byte[] selectedClass,
      int[] bridgeDeckY,
      TellusLodGenerator.LodBuildingColumn[] buildingColumns,
      Map<Long, Integer> roadSurfaceCache,
      int[] shaftBottomY,
      int[] shaftTopY,
      int[] capBottomY,
      int[] capTopY
   ) {
      if (roads.isEmpty() || roadWidth <= 0 || lodSizePoints <= 0 || cellSize <= 0) {
         return false;
      }

      double worldScale = EarthProjection.worldScaleFromBlocksPerDegree(blocksPerDegree);
      double minWorldX = Math.min(worldXs[0], worldXs[lodSizePoints - 1]);
      double minWorldZ = Math.min(worldZs[0], worldZs[lodSizePoints - 1]);
      double maxWorldX = Math.max(worldXs[0], worldXs[lodSizePoints - 1]);
      double maxWorldZ = Math.max(worldZs[0], worldZs[lodSizePoints - 1]);
      boolean[] hasSupports = new boolean[1];

      for (RoadFeature road : roads) {
         int featureRoadWidth = RoadSurfaceStyle.effectiveRoadWidth(road, roadWidth, worldScale);
         int points = road.pointCount();
         if (points < 2) {
            continue;
         }

         BridgeSupportLayout.SupportStyle style = BridgeSupportLayout.styleFor(road.roadClass(), featureRoadWidth);
         double radius = style.maxFootprintRadius() + cellSize * 0.5;
         double roadLonA = this.projection.lonToBlockX(road.minLon());
         double roadLonB = this.projection.lonToBlockX(road.maxLon());
         double roadMinX = Math.min(roadLonA, roadLonB);
         double roadMaxX = Math.max(roadLonA, roadLonB);
         double roadMinZ = this.projection.latToBlockZ(road.maxLat());
         double roadMaxZ = this.projection.latToBlockZ(road.minLat());
         if (roadMaxX < minWorldX - radius
            || roadMinX > maxWorldX + radius
            || roadMaxZ < minWorldZ - radius
            || roadMinZ > maxWorldZ + radius) {
            continue;
         }

         double startWorldX = this.projection.lonToBlockX(road.lonAt(0));
         double startWorldZ = this.projection.latToBlockZ(road.latAt(0));
         double endWorldX = this.projection.lonToBlockX(road.lonAt(points - 1));
         double endWorldZ = this.projection.latToBlockZ(road.latAt(points - 1));
         int startSurface = this.sampleRoadSurfaceForLodBridge(Mth.floor(startWorldX), Mth.floor(startWorldZ), roadSurfaceCache);
         int endSurface = this.sampleRoadSurfaceForLodBridge(Mth.floor(endWorldX), Mth.floor(endWorldZ), roadSurfaceCache);

         BridgeSupportLayout.forEachSupport(road, this.projection, featureRoadWidth, placement -> {
            IntArrayList capCells = new IntArrayList();
            IntArrayList[] shaftCells = new IntArrayList[style.shaftCount()];
            int[] minTerrain = new int[style.shaftCount()];
            int[] maxTerrain = new int[style.shaftCount()];

            for (int i = 0; i < style.shaftCount(); i++) {
               shaftCells[i] = new IntArrayList();
               minTerrain[i] = Integer.MAX_VALUE;
               maxTerrain[i] = Integer.MIN_VALUE;
            }

            int localRoadSurface = this.sampleRoadSurfaceForLodBridge(
               Mth.floor(placement.centerX()), Mth.floor(placement.centerZ()), roadSurfaceCache
            );
            int deckY = bridgeDeckYAtStation(
               placement.station(),
               placement.totalLength(),
               startSurface,
               endSurface,
               localRoadSurface,
               road.bridgeLevel(),
               road.roadClass(),
               worldScale
            );
            int supportCapTop = deckY - 1;
            int supportCapBottom = supportCapTop - style.capThickness() + 1;
            if (supportCapTop < supportCapBottom) {
               return;
            }

            int minGridX = Mth.clamp((int)Math.floor((placement.centerX() - radius - minWorldX) / cellSize), 0, lodSizePoints - 1);
            int maxGridX = Mth.clamp((int)Math.floor((placement.centerX() + radius - minWorldX) / cellSize), 0, lodSizePoints - 1);
            int minGridZ = Mth.clamp((int)Math.floor((placement.centerZ() - radius - minWorldZ) / cellSize), 0, lodSizePoints - 1);
            int maxGridZ = Mth.clamp((int)Math.floor((placement.centerZ() + radius - minWorldZ) / cellSize), 0, lodSizePoints - 1);

            for (int gz = minGridZ; gz <= maxGridZ; gz++) {
               double sampleZ = worldZs[gz];
               int row = gz * lodSizePoints;

               for (int gx = minGridX; gx <= maxGridX; gx++) {
                  double sampleX = worldXs[gx];
                  double deltaX = sampleX - placement.centerX();
                  double deltaZ = sampleZ - placement.centerZ();
                  double along = deltaX * placement.tangentX() + deltaZ * placement.tangentZ();
                  double across = deltaX * placement.normalX() + deltaZ * placement.normalZ();
                  int index = row + gx;
                  if (Math.abs(along) <= style.capHalfAlong() + cellSize * 0.5 && Math.abs(across) <= style.capHalfAcross() + cellSize * 0.5) {
                     capCells.add(index);
                  }

                  for (int shaftIndex = 0; shaftIndex < style.shaftCount(); shaftIndex++) {
                     double shaftAcross = style.shaftCount() == 1 ? 0.0 : (shaftIndex == 0 ? -style.shaftOffset() : style.shaftOffset());
                     if (Math.abs(along) <= style.shaftHalfAlong() + cellSize * 0.5 && Math.abs(across - shaftAcross) <= style.shaftHalfAcross() + cellSize * 0.5) {
                        shaftCells[shaftIndex].add(index);
                        int terrainY = surfaceYs[index];
                        minTerrain[shaftIndex] = Math.min(minTerrain[shaftIndex], terrainY);
                        maxTerrain[shaftIndex] = Math.max(maxTerrain[shaftIndex], terrainY);
                     }
                  }
               }
            }

            if (capCells.isEmpty()) {
               return;
            }

            int[] supportBottoms = new int[style.shaftCount()];
            int[] supportTops = new int[style.shaftCount()];
            int sharedBottom = Integer.MAX_VALUE;
            int requiredClearance = Math.max(1, Math.min(style.minClearance(), bridgeTargetClearanceBlocks(road.roadClass(), worldScale)));
            for (int shaftIndex = 0; shaftIndex < style.shaftCount(); shaftIndex++) {
               if (shaftCells[shaftIndex].isEmpty() || minTerrain[shaftIndex] == Integer.MAX_VALUE || maxTerrain[shaftIndex] == Integer.MIN_VALUE) {
                  return;
               }

               if (supportCapBottom - maxTerrain[shaftIndex] < requiredClearance) {
                  return;
               }

               supportBottoms[shaftIndex] = minTerrain[shaftIndex];
               supportTops[shaftIndex] = supportCapBottom - 1;
               if (supportTops[shaftIndex] < supportBottoms[shaftIndex]) {
                  return;
               }

               sharedBottom = Math.min(sharedBottom, supportBottoms[shaftIndex]);
            }

            for (int i = 0; i < capCells.size(); i++) {
               if (this.lodBridgeSupportConflicts(
                  capCells.getInt(i), supportCapBottom, supportCapTop, selectedClass, bridgeDeckY, surfaceYs, buildingColumns
               )) {
                  return;
               }
            }

            for (int shaftIndex = 0; shaftIndex < style.shaftCount(); shaftIndex++) {
               for (int i = 0; i < shaftCells[shaftIndex].size(); i++) {
                  if (this.lodBridgeSupportConflicts(
                     shaftCells[shaftIndex].getInt(i),
                     supportBottoms[shaftIndex],
                     supportTops[shaftIndex],
                     selectedClass,
                     bridgeDeckY,
                     surfaceYs,
                     buildingColumns
                  )) {
                     return;
                  }
               }
            }

            for (int i = 0; i < capCells.size(); i++) {
               mergeLodSupportColumn(capCells.getInt(i), supportCapBottom, supportCapTop, capBottomY, capTopY);
            }

            for (int shaftIndex = 0; shaftIndex < style.shaftCount(); shaftIndex++) {
               for (int i = 0; i < shaftCells[shaftIndex].size(); i++) {
                  mergeLodSupportColumn(shaftCells[shaftIndex].getInt(i), supportBottoms[shaftIndex], supportTops[shaftIndex], shaftBottomY, shaftTopY);
               }
            }

            hasSupports[0] = true;
         });
      }

      return hasSupports[0];
   }

   private boolean lodBridgeSupportConflicts(
      int index,
      int bottomY,
      int topY,
      byte[] selectedClass,
      int[] bridgeDeckY,
      int[] surfaceYs,
      TellusLodGenerator.LodBuildingColumn[] buildingColumns
   ) {
      if (topY < bottomY) {
         return false;
      }

      if (selectedClass[index] > 0) {
         int bridgeDeck = bridgeDeckY[index];
         if (bridgeDeck != Integer.MIN_VALUE) {
            if (bridgeDeck >= bottomY && bridgeDeck <= topY) {
               return true;
            }
         } else {
            int roadSurface = surfaceYs[index];
            if (roadSurface >= bottomY && roadSurface <= topY) {
               return true;
            }
         }
      }

      TellusLodGenerator.LodBuildingColumn buildingColumn = buildingColumns == null ? null : buildingColumns[index];
      return buildingColumn != null && buildingColumn.intersectsSpan(bottomY, topY);
   }

   private static void mergeLodSupportColumn(int index, int bottomY, int topY, int[] bottoms, int[] tops) {
      if (topY < bottomY) {
         return;
      }

      if (tops[index] == Integer.MIN_VALUE) {
         bottoms[index] = bottomY;
         tops[index] = topY;
      } else {
         bottoms[index] = Math.min(bottoms[index], bottomY);
         tops[index] = Math.max(tops[index], topY);
      }
   }

   private int sampleRoadSurfaceForLodBridge(int worldX, int worldZ, Map<Long, Integer> roadSurfaceCache) {
      long packed = packWorldColumn(worldX, worldZ);
      Integer cached = roadSurfaceCache.get(packed);
      if (cached != null) {
         return cached;
      } else {
         WaterSurfaceResolver.WaterColumnData column = this.generator.resolveLodWaterColumn(worldX, worldZ);
         int roadSurface = column.hasWater() && column.waterSurface() > column.terrainSurface() ? column.waterSurface() : column.terrainSurface();
         roadSurfaceCache.put(packed, roadSurface);
         return roadSurface;
      }
   }

   private static long packWorldColumn(int worldX, int worldZ) {
      return (long)worldX << 32 ^ worldZ & 4294967295L;
   }

   private static int roadWidthForScale(int baseWidth, double worldScale) {
      double factor = roadWidthFactorForScale(worldScale);

      return Math.max(1, (int)Math.round(baseWidth * factor));
   }

   private static int roadClassId(RoadClass roadClass) {
      return switch (roadClass) {
         case MAIN -> 1;
         case NORMAL -> 2;
         case DIRT -> 3;
      };
   }

   private static double roadWidthFactorForScale(double worldScale) {
      if (!(worldScale > 0.0)) {
         return 0.25;
      } else if (worldScale <= 1.0) {
         return 1.8;
      } else if (worldScale <= 5.0) {
         double t = (worldScale - 1.0) / 4.0;
         return Mth.lerp(Mth.clamp(t, 0.0, 1.0), 1.8, 1.0);
      } else if (worldScale <= 10.0) {
         double t = (worldScale - 5.0) / 5.0;
         return Mth.lerp(Mth.clamp(t, 0.0, 1.0), 1.0, 0.5);
      } else {
         return 0.25;
      }
   }

   private static int bridgeRiseAtStation(double station, double totalLength, int bridgeLevel) {
      int requestedRise = Math.max(0, bridgeLevel) * 3;
      requestedRise = Math.min(requestedRise, 10);
      if (requestedRise > 0 && !(totalLength <= 1.0E-6)) {
         double maxRiseByLength = totalLength / 8.0;
         int targetRise = Math.min(requestedRise, Math.max(0, (int)Math.floor(maxRiseByLength)));
         if (targetRise <= 0) {
            return 0;
         } else {
            double clampedStation = Mth.clamp(station, 0.0, totalLength);
            double rampLength = targetRise * 4;
            double rise;
            if (totalLength >= rampLength * 2.0) {
               if (clampedStation < rampLength) {
                  rise = targetRise * (clampedStation / rampLength);
               } else if (clampedStation > totalLength - rampLength) {
                  rise = targetRise * ((totalLength - clampedStation) / rampLength);
               } else {
                  rise = targetRise;
               }
            } else {
               double half = totalLength * 0.5;
               if (half <= 1.0E-6) {
                  rise = targetRise;
               } else if (clampedStation <= half) {
                  rise = targetRise * (clampedStation / half);
               } else {
                  rise = targetRise * ((totalLength - clampedStation) / half);
               }
            }

            return Math.max(0, (int)Math.round(Mth.clamp(rise, 0.0, targetRise)));
         }
      } else {
         return 0;
      }
   }

   private static int bridgeDeckBaselineAtStation(double station, double totalLength, int startSurface, int endSurface) {
      return interpolateDeckAtStation(station, totalLength, startSurface, endSurface);
   }

   private static int bridgeDeckYAtStation(
      double station,
      double totalLength,
      int startSurface,
      int endSurface,
      int localRoadSurface,
      int bridgeLevel,
      RoadClass roadClass,
      double worldScale
   ) {
      int baseline = bridgeDeckBaselineAtStation(station, totalLength, startSurface, endSurface);
      int rise = bridgeRiseAtStation(station, totalLength, bridgeLevel);
      int clearance = bridgeClearanceAtStation(station, totalLength, roadClass, worldScale);
      int connectedDeck = baseline + rise;
      boolean valleyBridge = localRoadSurface + bridgeValleyDepthBlocks(worldScale) < baseline;
      return valleyBridge ? Math.max(connectedDeck, localRoadSurface + clearance) : Math.max(connectedDeck, localRoadSurface);
   }

   private static int bridgeValleyDepthBlocks(double worldScale) {
      double safeScale = worldScale > 0.0 ? worldScale : 1.0;
      return Math.max(2, (int)Math.round(4.0 / safeScale));
   }

   private static int bridgeClearanceAtStation(double station, double totalLength, RoadClass roadClass, double worldScale) {
      int targetClearance = bridgeTargetClearanceBlocks(roadClass, worldScale);
      if (targetClearance <= 0 || totalLength <= 1.0E-6) {
         return 0;
      } else {
         double clampedStation = Mth.clamp(station, 0.0, totalLength);
         double rampLength = targetClearance * 4.0;
         double clearance;
         if (totalLength >= rampLength * 2.0) {
            if (clampedStation < rampLength) {
               clearance = targetClearance * (clampedStation / rampLength);
            } else if (clampedStation > totalLength - rampLength) {
               clearance = targetClearance * ((totalLength - clampedStation) / rampLength);
            } else {
               clearance = targetClearance;
            }
         } else {
            double half = totalLength * 0.5;
            if (half <= 1.0E-6) {
               clearance = targetClearance;
            } else if (clampedStation <= half) {
               clearance = targetClearance * (clampedStation / half);
            } else {
               clearance = targetClearance * ((totalLength - clampedStation) / half);
            }
         }

         return Math.max(0, (int)Math.round(Mth.clamp(clearance, 0.0, targetClearance)));
      }
   }

   private static int bridgeTargetClearanceBlocks(RoadClass roadClass, double worldScale) {
      double safeScale = worldScale > 0.0 ? worldScale : 1.0;
      double clearanceMeters = switch (roadClass) {
         case MAIN -> 6.0;
         case NORMAL -> 5.0;
         case DIRT -> 3.0;
      };
      return Math.max(1, (int)Math.ceil(clearanceMeters / safeScale));
   }

   private static int interpolateDeckAtStation(double station, double totalLength, int startSurface, int endSurface) {
      if (totalLength <= 1.0E-6) {
         return startSurface;
      } else {
         double progress = Mth.clamp(station / totalLength, 0.0, 1.0);
         double interpolated = startSurface + (endSurface - startSurface) * progress;
         return (int)Math.round(interpolated);
      }
   }

   private static TellusLodGenerator.CanopyProfile canopyProfile(Holder<Biome> biome) {
      return CANOPY_PROFILES.computeIfAbsent(biome, TellusLodGenerator::buildCanopyProfile);
   }

   private static TellusLodGenerator.CanopyProfile resolveTreeCoverCanopyProfile(TellusLodGenerator.CanopyProfile biomeProfile, int coverClass) {
      return coverClass == ESA_TREE_COVER && !biomeProfile.isMangrove() && biomeProfile.canopyBaseChance() <= 0
         ? TREE_COVER_FALLBACK_CANOPY_PROFILE
         : biomeProfile;
   }

   private static IDhApiBiomeWrapper resolveCanopyLeafBiome(
      TellusLodGenerator.WrapperCache wrappers,
      IDhApiBiomeWrapper columnBiome,
      TellusLodGenerator.CanopyProfile canopyProfile,
      int coverClass
   ) {
      return coverClass == ESA_TREE_COVER || canopyProfile.isMangrove()
         ? wrappers.canopyLeafBiome(canopyProfile, columnBiome)
         : columnBiome;
   }

   private static TellusLodGenerator.CanopyProfile buildCanopyProfile(Holder<Biome> biome) {
      boolean isMangrove = biome.is(Biomes.MANGROVE_SWAMP);
      boolean isPaleGarden = isBiomePath(biome, "pale_garden");
      boolean isMushroomFields = biome.is(Biomes.MUSHROOM_FIELDS);
      boolean isDarkForest = biome.is(Biomes.DARK_FOREST);
      boolean isBirchForest = biome.is(Biomes.BIRCH_FOREST);
      boolean isOldGrowthBirchForest = biome.is(Biomes.OLD_GROWTH_BIRCH_FOREST);
      boolean isOldGrowthTaiga = biome.is(Biomes.OLD_GROWTH_PINE_TAIGA) || biome.is(Biomes.OLD_GROWTH_SPRUCE_TAIGA);
      boolean isBambooJungle = biome.is(Biomes.BAMBOO_JUNGLE);
      boolean isSparseJungle = biome.is(Biomes.SPARSE_JUNGLE);
      boolean isWindsweptForest = biome.is(Biomes.WINDSWEPT_FOREST);
      boolean isWoodedBadlands = biome.is(Biomes.WOODED_BADLANDS);
      boolean isWindsweptSavanna = biome.is(Biomes.WINDSWEPT_SAVANNA);
      boolean isSavannaPlateau = biome.is(Biomes.SAVANNA_PLATEAU);
      boolean isCherryGrove = biome.is(Biomes.CHERRY_GROVE);
      boolean isSwamp = biome.is(Biomes.SWAMP);
      boolean isWarmOcean = biome.is(Biomes.WARM_OCEAN);
      boolean isLukewarmOcean = biome.is(Biomes.LUKEWARM_OCEAN);
      boolean isDeepLukewarmOcean = biome.is(Biomes.DEEP_LUKEWARM_OCEAN);
      boolean isJungle = biome.is(BiomeTags.IS_JUNGLE);
      boolean isForest = biome.is(BiomeTags.IS_FOREST);
      boolean isTaiga = biome.is(BiomeTags.IS_TAIGA);
      boolean isSavanna = biome.is(BiomeTags.IS_SAVANNA);
      boolean isOcean = biome.is(BiomeTags.IS_OCEAN);
      boolean isRiver = biome.is(BiomeTags.IS_RIVER);
      boolean isSavannaTree = isSavanna || isWindsweptSavanna || isSavannaPlateau;
      TellusLodGenerator.TreeLodFamily treeFamily;
      if (isMangrove) {
         treeFamily = TellusLodGenerator.TreeLodFamily.MANGROVE;
      } else if (isPaleGarden) {
         treeFamily = TellusLodGenerator.TreeLodFamily.PALE_OAK;
      } else if (isMushroomFields) {
         treeFamily = TellusLodGenerator.TreeLodFamily.MUSHROOM;
      } else if (isCherryGrove) {
         treeFamily = TellusLodGenerator.TreeLodFamily.CHERRY;
      } else if (isDarkForest) {
         treeFamily = TellusLodGenerator.TreeLodFamily.DARK_OAK;
      } else if (isOldGrowthBirchForest) {
         treeFamily = TellusLodGenerator.TreeLodFamily.OLD_GROWTH_BIRCH;
      } else if (isBirchForest) {
         treeFamily = TellusLodGenerator.TreeLodFamily.BIRCH;
      } else if (isOldGrowthTaiga) {
         treeFamily = TellusLodGenerator.TreeLodFamily.OLD_GROWTH_SPRUCE;
      } else if (isBambooJungle || isSparseJungle || isJungle) {
         treeFamily = TellusLodGenerator.TreeLodFamily.JUNGLE;
      } else if (isWindsweptForest) {
         treeFamily = TellusLodGenerator.TreeLodFamily.WINDSWEPT_FOREST;
      } else if (isWoodedBadlands) {
         treeFamily = TellusLodGenerator.TreeLodFamily.WOODED_BADLANDS;
      } else if (isSavannaTree) {
         treeFamily = TellusLodGenerator.TreeLodFamily.ACACIA;
      } else if (isTaiga) {
         treeFamily = TellusLodGenerator.TreeLodFamily.SPRUCE;
      } else if (isSwamp) {
         treeFamily = TellusLodGenerator.TreeLodFamily.SWAMP_OAK;
      } else if (isForest) {
         treeFamily = TellusLodGenerator.TreeLodFamily.MIXED_FOREST;
      } else {
         treeFamily = TellusLodGenerator.TreeLodFamily.NONE;
      }

      int canopyBaseChance = treeFamily.baseChance();
      int canopyBaseRadius = treeFamily.baseRadius();
      int canopyBaseHeight = treeFamily.baseLeavesHeight();
      int canopyMaxHeight = treeFamily.maxLeavesHeight();

      int waterVegetationChance;
      if (isWarmOcean || isLukewarmOcean) {
         waterVegetationChance = 19;
      } else if (isDeepLukewarmOcean) {
         waterVegetationChance = 18;
      } else if (isMangrove) {
         waterVegetationChance = 17;
      } else if (isSwamp) {
         waterVegetationChance = 14;
      } else if (isOcean) {
         waterVegetationChance = 15;
      } else if (isRiver) {
         waterVegetationChance = 12;
      } else {
         waterVegetationChance = 10;
      }

      return new TellusLodGenerator.CanopyProfile(
         isMangrove,
         isDarkForest,
         isBambooJungle,
         isSparseJungle,
         isWindsweptForest,
         isWoodedBadlands,
         isWindsweptSavanna,
         isSavannaPlateau,
         isCherryGrove,
         isSwamp,
         isJungle,
         isForest,
         isTaiga,
         isSavanna,
         isOcean,
         isRiver,
         isWarmOcean,
         isLukewarmOcean,
         isDeepLukewarmOcean,
         canopyBaseChance,
         canopyBaseRadius,
         canopyBaseHeight,
         canopyMaxHeight,
         waterVegetationChance,
         treeFamily
      );
   }

   private static TellusLodGenerator.CanopyColumn resolveCanopyColumn(
      TellusLodGenerator.CanopyProfile profile,
      boolean customTrees,
      boolean hugeRedMushrooms,
      Holder<Biome> biomeHolder,
      int worldX,
      int worldZ,
      int cellSize,
      double worldScale,
      double previewResolutionMeters,
      long worldSeed,
      TellusLodGenerator.CanopyRequestCache requestCache
   ) {
      if (!customTrees) {
         return resolveMinecraftCanopyColumn(profile, worldX, worldZ, cellSize, hugeRedMushrooms);
      }

      int baseChance = canopyCenterChancePercent(profile);
      int chance = boostCanopyChancePercent(baseChance);
      if (chance <= 0) {
         return null;
      } else {
         boolean useFullDetailAnchors = !profile.isMangrove();
         int gridSize = useFullDetailAnchors
            ? TellusProceduralTreeGenerator.PLACEMENT_CELL_SIZE
            : canopyGridSize(cellSize);
         int cellX = Math.floorDiv(worldX, gridSize);
         int cellZ = Math.floorDiv(worldZ, gridSize);
         int bestDist = Integer.MAX_VALUE;
         int bestRadius = 0;
         int bestHash = 0;
         int bestCenterX = 0;
         int bestCenterZ = 0;
         long bestSeed = 0L;
         long bestDistanceSquared = Long.MAX_VALUE;
         boolean bestCenter = false;

         int scanRadius = useFullDetailAnchors ? 2 : 1;
         for (int dz = -scanRadius; dz <= scanRadius; dz++) {
            int testCellZ = cellZ + dz;

            for (int dx = -scanRadius; dx <= scanRadius; dx++) {
               int testCellX = cellX + dx;
               int centerHash = mixHash(testCellX, testCellZ, 1831565813);
               if (useFullDetailAnchors) {
                  TellusProceduralTreeGenerator.TreeAnchor anchor =
                     TellusProceduralTreeGenerator.anchorForCell(testCellX, testCellZ, worldSeed);
                  int offsetX = worldX - anchor.worldX();
                  int offsetZ = worldZ - anchor.worldZ();
                  long distanceSquared = (long)offsetX * offsetX + (long)offsetZ * offsetZ;
                  if (distanceSquared < bestDistanceSquared) {
                     double centerDistance = Math.sqrt(distanceSquared);
                     int cellPadding = Math.max(0, cellSize - 1) / 2;
                     bestDist = Math.max(0, (int)Math.round(centerDistance) - cellPadding);
                     bestRadius = 12;
                     bestHash = centerHash;
                     bestCenterX = anchor.worldX();
                     bestCenterZ = anchor.worldZ();
                     bestSeed = anchor.seed();
                     bestDistanceSquared = distanceSquared;
                     bestCenter = Math.abs(offsetX) <= cellPadding && Math.abs(offsetZ) <= cellPadding;
                  }
               } else if (hasCanopyCenter(centerHash, chance)) {
                  int offsetX = centerOffset(centerHash, gridSize);
                  int offsetZ = centerOffset(centerHash >>> 8, gridSize);
                  int centerX = testCellX * gridSize + offsetX;
                  int centerZ = testCellZ * gridSize + offsetZ;
                  int dist = Math.abs(worldX - centerX) + Math.abs(worldZ - centerZ);
                  int radius = canopyRadius(profile, centerHash, gridSize);
                  if (dist <= radius && dist < bestDist) {
                     bestDist = dist;
                     bestRadius = radius;
                     bestHash = centerHash;
                     bestCenterX = centerX;
                     bestCenterZ = centerZ;
                     bestCenter = dist == 0;
                  }
               }
            }
         }

         if (bestDist == Integer.MAX_VALUE) {
            return null;
         } else {
            TellusCanopyHeightSource.CanopySample canopy = requestCache.canopy(
               bestCenterX, bestCenterZ, worldScale, previewResolutionMeters
            );
            boolean lowCanopyBush = canopy.available() && canopy.maximumHeightMeters() < 2.0;
            TellusProceduralTreeGenerator.TreePlan treePlan = useFullDetailAnchors
               ? requestCache.treePlan(bestCenterX, bestCenterZ, worldScale, bestSeed, biomeHolder, canopy)
               : null;
            if (treePlan != null && (!treePlan.present() || bestDist > treePlan.crownRadius())) {
               return null;
            }
            if (treePlan == null && lowCanopyBush && bestDist > 2) {
               return null;
            }
            TellusLodGenerator.DataCanopyDimensions dataDimensions;
            if (treePlan != null) {
               dataDimensions = dataCanopyDimensions(treePlan, bestDist);
            } else if (lowCanopyBush) {
               dataDimensions = bushCanopyDimensions(bestDist);
            } else if (canopy.available()) {
               dataDimensions = dataCanopyDimensions(profile, canopy, bestRadius, bestDist, bestHash, false);
            } else {
               dataDimensions = null;
            }
            int crownHeight = dataDimensions != null
               ? dataDimensions.crownHeight()
               : canopyLeavesHeight(profile, bestRadius, bestDist, bestCenter, bestHash);
            if (crownHeight <= 0) {
               return null;
            } else {
               TellusLodGenerator.TreeSpecies species = treePlan != null
                  ? null
                  : selectCanopySpecies(profile, worldX, worldZ, bestHash, hugeRedMushrooms);
               int centerTrunkHeight = dataDimensions != null
                  ? dataDimensions.trunkHeight()
                  : canopyTrunkHeight(profile, species, bestHash);
               int trunkHeight = bestCenter ? centerTrunkHeight : 0;
               int rootHeight = treePlan != null || lowCanopyBush
                  ? 0
                  : canopyRootHeight(profile, species, bestCenter, bestDist, bestHash);
               int leafLift = dataDimensions != null
                  ? bestCenter ? 0 : dataDimensions.leafLift()
                  : canopyLeafLift(profile, species, bestCenter, centerTrunkHeight, bestDist, bestHash);
               BlockState leavesBlock = treePlan != null
                  ? TellusProceduralTreeGenerator.leavesState(treePlan.profile(), bestSeed)
                  : selectCanopyBlock(species);
               if (leavesBlock == null) {
                  return null;
               } else {
                  BlockState rootBlock = rootHeight > 0 ? selectRootBlock(species) : null;
                  BlockState trunkBlock = trunkHeight > 0
                     ? treePlan != null
                        ? TellusProceduralTreeGenerator.logState(treePlan.profile(), bestSeed)
                        : selectTrunkBlock(species)
                     : null;
                  int anchorSurfaceY = useFullDetailAnchors
                     ? requestCache.treeAnchorSurface(bestCenterX, bestCenterZ, worldScale)
                     : LodCanopyVerticalLayout.UNANCHORED_SURFACE_Y;
                  return new TellusLodGenerator.CanopyColumn(
                     rootHeight, trunkHeight, leafLift, crownHeight, leavesBlock, trunkBlock, rootBlock, anchorSurfaceY
                  );
               }
            }
         }
      }
   }

   private static TellusLodGenerator.CanopyColumn resolveMinecraftCanopyColumn(
      TellusLodGenerator.CanopyProfile profile, int worldX, int worldZ, int cellSize, boolean hugeRedMushrooms
   ) {
      int baseChance = canopyCenterChancePercent(profile);
      int chance = boostCanopyChancePercent(baseChance);
      if (chance <= 0) {
         return null;
      }

      int gridSize = canopyGridSize(cellSize);
      int cellX = Math.floorDiv(worldX, gridSize);
      int cellZ = Math.floorDiv(worldZ, gridSize);
      int bestDist = Integer.MAX_VALUE;
      int bestRadius = 0;
      int bestHash = 0;
      boolean bestCenter = false;

      for (int dz = -1; dz <= 1; dz++) {
         int testCellZ = cellZ + dz;
         for (int dx = -1; dx <= 1; dx++) {
            int testCellX = cellX + dx;
            int centerHash = mixHash(testCellX, testCellZ, 1831565813);
            if (hasCanopyCenter(centerHash, chance)) {
               int offsetX = centerOffset(centerHash, gridSize);
               int offsetZ = centerOffset(centerHash >>> 8, gridSize);
               int centerX = testCellX * gridSize + offsetX;
               int centerZ = testCellZ * gridSize + offsetZ;
               int dist = Math.abs(worldX - centerX) + Math.abs(worldZ - centerZ);
               int radius = canopyRadius(profile, centerHash, gridSize);
               if (dist <= radius && dist < bestDist) {
                  bestDist = dist;
                  bestRadius = radius;
                  bestHash = centerHash;
                  bestCenter = dist == 0;
               }
            }
         }
      }

      if (bestDist == Integer.MAX_VALUE) {
         return null;
      }

      int crownHeight = canopyLeavesHeight(profile, bestRadius, bestDist, bestCenter, bestHash);
      if (crownHeight <= 0) {
         return null;
      }

      TellusLodGenerator.TreeSpecies species = selectCanopySpecies(profile, worldX, worldZ, bestHash, hugeRedMushrooms);
      int centerTrunkHeight = canopyTrunkHeight(profile, species, bestHash);
      int trunkHeight = bestCenter ? centerTrunkHeight : 0;
      int rootHeight = canopyRootHeight(profile, species, bestCenter, bestDist, bestHash);
      int leafLift = canopyLeafLift(profile, species, bestCenter, centerTrunkHeight, bestDist, bestHash);
      BlockState leavesBlock = selectCanopyBlock(species);
      if (leavesBlock == null) {
         return null;
      }

      BlockState rootBlock = rootHeight > 0 ? selectRootBlock(species) : null;
      BlockState trunkBlock = trunkHeight > 0 ? selectTrunkBlock(species) : null;
      return new TellusLodGenerator.CanopyColumn(
         rootHeight,
         trunkHeight,
         leafLift,
         crownHeight,
         leavesBlock,
         trunkBlock,
         rootBlock,
         LodCanopyVerticalLayout.UNANCHORED_SURFACE_Y
      );
   }

   private static TellusLodGenerator.DataCanopyDimensions dataCanopyDimensions(
      TellusProceduralTreeGenerator.TreePlan plan, int distance
   ) {
      if (plan.bush()) {
         return bushCanopyDimensions(distance);
      }
      int centerCrownHeight = Math.max(1, plan.height() - plan.crownBase());
      double normalizedDistance = plan.crownRadius() <= 0
         ? 0.0
         : Mth.clamp(distance / (double)(plan.crownRadius() + 0.5), 0.0, 1.0);
      double sectionScale = Math.sqrt(Math.max(0.04, 1.0 - normalizedDistance * normalizedDistance));
      if (plan.profile() == TellusProceduralTreeGenerator.Profile.SAVANNA
         || plan.profile() == TellusProceduralTreeGenerator.Profile.MEDITERRANEAN
         || plan.profile() == TellusProceduralTreeGenerator.Profile.MALLEE) {
         sectionScale = 0.64 + sectionScale * 0.36;
      }
      int crownHeight = Math.max(1, (int)Math.round(centerCrownHeight * sectionScale));
      int centerTrunkHeight = Math.max(2, plan.crownBase());
      int leafLift = centerTrunkHeight + Math.max(0, (centerCrownHeight - crownHeight) / 2);
      return new TellusLodGenerator.DataCanopyDimensions(centerTrunkHeight, crownHeight, leafLift);
   }

   private static TellusLodGenerator.DataCanopyDimensions bushCanopyDimensions(int distance) {
      return distance <= 1
         ? new TellusLodGenerator.DataCanopyDimensions(1, 2, 0)
         : new TellusLodGenerator.DataCanopyDimensions(1, 1, 1);
   }

   private static TellusLodGenerator.DataCanopyDimensions dataCanopyDimensions(
      TellusLodGenerator.CanopyProfile profile,
      TellusCanopyHeightSource.CanopySample canopy,
      int radius,
      int distance,
      int centerHash,
      boolean coastRedwood
   ) {
      double reference = canopy.centerHeightMeters() * 0.30
         + canopy.percentile75Meters() * 0.42
         + canopy.percentile90Meters() * 0.28;
      if (coastRedwood) {
         reference = TellusProceduralTreeGenerator.calibratedCoastRedwoodHeight(reference);
      }
      double age = 0.72 + ((centerHash >>> 8) & 255) / 255.0 * 0.38;
      if (((centerHash >>> 20) & 31) == 0) {
         double maximum = coastRedwood
            ? TellusProceduralTreeGenerator.calibratedCoastRedwoodHeight(canopy.maximumHeightMeters())
            : canopy.maximumHeightMeters();
         reference = Math.max(reference, maximum);
         age = Math.max(age, coastRedwood ? 1.14 : 1.08);
      }
      int targetHeight = Mth.clamp((int)Math.round(reference * age), 4, coastRedwood ? 112 : 96);
      double crownFraction;
      TellusLodGenerator.TreeLodFamily family = profile.treeFamily();
      if (coastRedwood) {
         crownFraction = 0.36;
      } else if (family == TellusLodGenerator.TreeLodFamily.SPRUCE || family == TellusLodGenerator.TreeLodFamily.OLD_GROWTH_SPRUCE) {
         crownFraction = 0.58;
      } else if (family == TellusLodGenerator.TreeLodFamily.ACACIA) {
         crownFraction = 0.26;
      } else if (family == TellusLodGenerator.TreeLodFamily.JUNGLE || family == TellusLodGenerator.TreeLodFamily.MANGROVE) {
         crownFraction = 0.44;
      } else {
         crownFraction = 0.38;
      }
      int centerCrownHeight = Mth.clamp((int)Math.round(targetHeight * crownFraction), 2, 32);
      int centerTrunkHeight = Math.max(2, targetHeight - centerCrownHeight);
      double normalizedDistance = radius <= 0 ? 0.0 : Mth.clamp(distance / (double)(radius + 1), 0.0, 1.0);
      int crownHeight = Math.max(1, (int)Math.round(centerCrownHeight * Math.sqrt(Math.max(0.05, 1.0 - normalizedDistance * normalizedDistance))));
      int leafLift = centerTrunkHeight + Math.max(0, (centerCrownHeight - crownHeight) / 2);
      return new TellusLodGenerator.DataCanopyDimensions(centerTrunkHeight, crownHeight, leafLift);
   }

   private static int appendCanopyColumn(
      TellusLodGenerator.CanopyColumn canopyColumn,
      int lastLayerTop,
      int minY,
      int absoluteTop,
      TellusLodGenerator.WrapperCache wrappers,
      IDhApiBiomeWrapper biome,
      IDhApiBiomeWrapper leafBiome,
      List<DhApiTerrainDataPoint> columnDataPoints
   ) {
      if (canopyColumn != null && lastLayerTop < absoluteTop) {
         int layerTop = lastLayerTop;
         int anchorLayerTop = LodCanopyVerticalLayout.anchorLayerTop(
            canopyColumn.anchorSurfaceY, minY, absoluteTop, lastLayerTop
         );
         int relativeBottom = 0;
         if (canopyColumn.rootHeight > 0 && canopyColumn.rootBlock != null) {
            layerTop = appendCanopyBlockSpan(
               anchorLayerTop,
               relativeBottom,
               canopyColumn.rootHeight,
               layerTop,
               absoluteTop,
               wrappers.getBlockState(canopyColumn.rootBlock),
               wrappers.airBlock(),
               biome,
               biome,
               columnDataPoints
            );
         }
         relativeBottom += canopyColumn.rootHeight;

         if (canopyColumn.trunkHeight > 0 && canopyColumn.trunkBlock != null) {
            layerTop = appendCanopyBlockSpan(
               anchorLayerTop,
               relativeBottom,
               canopyColumn.trunkHeight,
               layerTop,
               absoluteTop,
               wrappers.getBlockState(canopyColumn.trunkBlock),
               wrappers.airBlock(),
               biome,
               biome,
               columnDataPoints
            );
         }
         relativeBottom += canopyColumn.trunkHeight + canopyColumn.leafLift;

         if (canopyColumn.leavesHeight > 0 && canopyColumn.leavesBlock != null) {
            layerTop = appendCanopyBlockSpan(
               anchorLayerTop,
               relativeBottom,
               canopyColumn.leavesHeight,
               layerTop,
               absoluteTop,
               wrappers.getBlockState(canopyColumn.leavesBlock),
               wrappers.airBlock(),
               biome,
               leafBiome,
               columnDataPoints
            );
         }

         return layerTop;
      } else {
         return lastLayerTop;
      }
   }

   private static int appendCanopyBlockSpan(
      int anchorLayerTop,
      int relativeBottom,
      int height,
      int lastLayerTop,
      int absoluteTop,
      IDhApiBlockStateWrapper block,
      IDhApiBlockStateWrapper airBlock,
      IDhApiBiomeWrapper airBiome,
      IDhApiBiomeWrapper blockBiome,
      List<DhApiTerrainDataPoint> columnDataPoints
   ) {
      LodCanopyVerticalLayout.Span span = LodCanopyVerticalLayout.visibleSpan(
         lastLayerTop, anchorLayerTop, relativeBottom, height, absoluteTop
      );
      if (!span.visible()) {
         return lastLayerTop;
      }
      if (span.bottom() > lastLayerTop) {
         columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, span.bottom(), airBlock, airBiome));
      }
      columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, span.bottom(), span.top(), block, blockBiome));
      return span.top();
   }

   private static boolean shouldEmitFullWaterVolume(int waterDepthLayers) {
      return LOD_WATER_FULL_VOLUME_MAX_DEPTH > 0 && waterDepthLayers <= LOD_WATER_FULL_VOLUME_MAX_DEPTH;
   }

   private static int appendLodWaterSurfaceLayer(
      int lastLayerTop,
      int waterTop,
      IDhApiBlockStateWrapper waterBlock,
      IDhApiBlockStateWrapper airBlock,
      IDhApiBiomeWrapper biome,
      List<DhApiTerrainDataPoint> columnDataPoints
   ) {
      if (waterTop <= lastLayerTop) {
         return lastLayerTop;
      }

      int waterBottom = Math.max(lastLayerTop, waterTop - LOD_WATER_SURFACE_LAYER_DEPTH);
      if (waterBottom > lastLayerTop) {
         columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, waterBottom, airBlock, biome));
         lastLayerTop = waterBottom;
      }

      columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, waterTop, waterBlock, biome));
      return waterTop;
   }

   private static int appendLodWaterColumn(
      int lastLayerTop,
      int waterTop,
      int minY,
      int absoluteTop,
      int waterSurface,
      int vegetationSurfaceY,
      boolean allowWaterVegetation,
      TellusLodGenerator.CanopyProfile profile,
      int worldX,
      int worldZ,
      IDhApiBlockStateWrapper waterBlock,
      IDhApiBlockStateWrapper airBlock,
      TellusLodGenerator.WrapperCache wrappers,
      IDhApiBiomeWrapper biome,
      List<DhApiTerrainDataPoint> columnDataPoints
   ) {
      if (waterTop <= lastLayerTop) {
         return lastLayerTop;
      }

      int waterDepthLayers = waterTop - lastLayerTop;
      boolean emitFullWaterVolume = shouldEmitFullWaterVolume(waterDepthLayers);
      int waterDepth = waterSurface - vegetationSurfaceY;
      TellusLodGenerator.WaterVegetationColumn vegetation = allowWaterVegetation
         ? resolveWaterVegetationColumn(profile, worldX, worldZ, waterDepth)
         : null;
      if (vegetation == null) {
         if (emitFullWaterVolume) {
            columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, waterTop, waterBlock, biome));
            return waterTop;
         }

         return appendLodWaterSurfaceLayer(lastLayerTop, waterTop, waterBlock, airBlock, biome, columnDataPoints);
      }

      int vegetationBaseTop = toLayerTop(vegetationSurfaceY, minY, absoluteTop);
      vegetationBaseTop = Mth.clamp(vegetationBaseTop, lastLayerTop, waterTop);
      IDhApiBlockStateWrapper lowerFillBlock = emitFullWaterVolume ? waterBlock : airBlock;
      if (vegetationBaseTop > lastLayerTop) {
         columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, vegetationBaseTop, lowerFillBlock, biome));
         lastLayerTop = vegetationBaseTop;
      }

      int vegTop = Math.min(waterTop, lastLayerTop + vegetation.height);
      if (vegTop <= lastLayerTop) {
         return emitFullWaterVolume
            ? appendFullWaterRemainder(lastLayerTop, waterTop, waterBlock, biome, columnDataPoints)
            : appendLodWaterSurfaceLayer(lastLayerTop, waterTop, waterBlock, airBlock, biome, columnDataPoints);
      }

      IDhApiBlockStateWrapper vegBlock = wrappers.getBlockState(vegetation.blockState);
      columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, vegTop, vegBlock, biome));
      lastLayerTop = vegTop;
      return emitFullWaterVolume
         ? appendFullWaterRemainder(lastLayerTop, waterTop, waterBlock, biome, columnDataPoints)
         : appendLodWaterSurfaceLayer(lastLayerTop, waterTop, waterBlock, airBlock, biome, columnDataPoints);
   }

   private static int appendFullWaterRemainder(
      int lastLayerTop,
      int waterTop,
      IDhApiBlockStateWrapper waterBlock,
      IDhApiBiomeWrapper biome,
      List<DhApiTerrainDataPoint> columnDataPoints
   ) {
      if (waterTop > lastLayerTop) {
         columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, waterTop, waterBlock, biome));
         return waterTop;
      }

      return lastLayerTop;
   }

   private static int appendBuildingColumn(
      TellusLodGenerator.LodBuildingColumn buildingColumn,
      int lastLayerTop,
      int minY,
      int absoluteTop,
      TellusLodGenerator.WrapperCache wrappers,
      IDhApiBiomeWrapper biome,
      List<DhApiTerrainDataPoint> columnDataPoints
   ) {
      if (buildingColumn == null || buildingColumn.isEmpty()) {
         return lastLayerTop;
      } else {
         int layerTop = lastLayerTop;

         for (int i = 0; i < buildingColumn.size(); i++) {
            int spanStart = buildingColumn.startY(i);
            int spanEnd = buildingColumn.endY(i);
            int spanBaseTop = toLayerTop(spanStart - 1, minY, absoluteTop);
            int spanTop = toLayerTop(spanEnd, minY, absoluteTop);
            if (spanBaseTop > layerTop) {
               columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, layerTop, spanBaseTop, wrappers.airBlock(), biome));
               layerTop = spanBaseTop;
            }

            if (spanTop > layerTop) {
               columnDataPoints.add(
                  DhApiTerrainDataPoint.create(
                     (byte)0, buildingColumn.emittedLight(i), 15, layerTop, spanTop, wrappers.getBlockState(buildingColumn.blockState(i)), biome
                  )
               );
               layerTop = spanTop;
            }
         }

         return layerTop;
      }
   }

   private static boolean shouldAllowCanopy(
      EarthGeneratorSettings settings,
      int coverClass,
      TellusLodGenerator.CanopyProfile profile,
      int worldX,
      int worldZ,
      boolean underwater,
      boolean isMangrove,
      long worldSeed
   ) {
      if (isMangrove) {
         return true;
      } else if (underwater || !profile.hasCanopy()) {
         return false;
      } else if (coverClass == ESA_TREE_COVER) {
         return true;
      } else {
         return shouldAllowRandomBiomeCanopy(settings, coverClass, worldX, worldZ, worldSeed);
      }
   }

   private static boolean shouldAllowRandomBiomeCanopy(EarthGeneratorSettings settings, int coverClass, int worldX, int worldZ, long worldSeed) {
      if (!settings.randomBiomes()
         || coverClass == ESA_NO_DATA
         || coverClass == ESA_BUILT_UP
         || coverClass == ESA_WATER
         || coverClass == ESA_MANGROVES
         || !isRandomBiomeLandPatchActive(settings, worldX, worldZ)) {
         return false;
      }

      int treeCellSize = settings.customTrees() ? TellusProceduralTreeGenerator.PLACEMENT_CELL_SIZE : 5;
      int cellX = Math.floorDiv(worldX, treeCellSize);
      int cellZ = Math.floorDiv(worldZ, treeCellSize);
      long treeSeed = settings.customTrees()
         ? TellusProceduralTreeGenerator.anchorForCell(cellX, cellZ, worldSeed).seed()
         : seedFromCoords(cellX, 0, cellZ) ^ worldSeed;
      return seededRandomInt(treeSeed ^ RANDOM_BIOME_TREE_SALT, 100) < RANDOM_BIOME_TREE_CHANCE;
   }

   private static boolean isRandomBiomeLandPatchActive(EarthGeneratorSettings settings, int blockX, int blockZ) {
      double density = Mth.clamp(settings.randomBiomeDensity(), 0.0, 0.4);
      if (density <= 0.0) {
         return false;
      } else {
         double noise = sampleValueNoise(blockX, blockZ, RANDOM_BIOME_PATCH_GRID_BLOCKS, settings.randomBiomeSeed() ^ RANDOM_BIOME_LAND_PATCH_SALT);
         return noise >= 1.0 - density;
      }
   }

   private static int canopyCenterChancePercent(TellusLodGenerator.CanopyProfile profile) {
      return profile.canopyBaseChance();
   }

   private static int boostCanopyChancePercent(int baseChance) {
      int boosted = (baseChance * 6 + 2) / 5;
      return Math.min(95, boosted);
   }

   private static int canopyGridSize(int cellSize) {
      int detailLevel = Math.max(0, Integer.numberOfTrailingZeros(cellSize));
      int scale = Math.min(8, Math.max(0, detailLevel - 2));
      int gridFromDetail = 8 + (scale << 1);
      int gridFromCell = 8 + Math.max(-2, (cellSize - 8) / 4);
      return Mth.clamp(Math.min(gridFromDetail, gridFromCell), 6, 24);
   }

   @SuppressWarnings("unchecked")
   private static Holder<Biome>[] newBiomeHolderArray(int size) {
      return (Holder<Biome>[])new Holder<?>[size];
   }

   private static int canopyRadius(TellusLodGenerator.CanopyProfile profile, int centerHash, int gridSize) {
      int baseRadius = profile.canopyBaseRadius();
      if (baseRadius == 0) {
         return 0;
      } else {
         int scaledRadius = Math.max(1, baseRadius * gridSize / 8);
         if (profile.treeFamily().isWideCrown()) {
            scaledRadius++;
         } else if (profile.treeFamily().isNarrowCrown()) {
            scaledRadius = Math.max(1, scaledRadius - 1);
         }

         scaledRadius = Math.min(scaledRadius, gridSize - 1);
         int jitter = profile.treeFamily().isFlatCrown() ? 0 : centerHash >>> 16 & 1;
         return scaledRadius + jitter;
      }
   }

   private static boolean hasCanopyCenter(int centerHash, int chancePercent) {
      int roll = centerHash >>> 24 & 0xFF;
      int threshold = chancePercent * 255 / 100;
      return roll < threshold;
   }

   private static int canopyLeavesHeight(
      TellusLodGenerator.CanopyProfile profile, int bestRadius, int bestDist, boolean bestCenter, int centerHash
   ) {
      int falloff = bestRadius - bestDist;
      int height = profile.canopyBaseHeight();
      TellusLodGenerator.TreeLodFamily family = profile.treeFamily();
      if (family == TellusLodGenerator.TreeLodFamily.SPRUCE || family == TellusLodGenerator.TreeLodFamily.OLD_GROWTH_SPRUCE) {
         height = Math.max(1, profile.canopyMaxHeight() - Math.max(0, bestDist / 2));
         if (bestCenter) {
            height++;
         }
      } else if (family == TellusLodGenerator.TreeLodFamily.ACACIA) {
         height = 2 + (falloff >= 2 ? 1 : 0);
      } else if (family == TellusLodGenerator.TreeLodFamily.MUSHROOM) {
         height = 1 + (bestCenter || falloff >= 2 ? 1 : 0);
      } else {
         if (falloff >= 2) {
            height++;
         }

         if (falloff >= 4 || family.isDenseCrown()) {
            height++;
         }

         height += centerHash >>> 19 & 1;
         if (bestCenter && family.isTallCrown()) {
            height++;
         }
      }

      return Mth.clamp(height, 1, profile.canopyMaxHeight());
   }

   private static int canopyTrunkHeight(
      TellusLodGenerator.CanopyProfile profile, TellusLodGenerator.TreeSpecies species, int centerHash
   ) {
      int jitter = centerHash >>> 21 & 3;
      if (jitter == 3) {
         jitter = 2;
      }

      TellusLodGenerator.TreeLodFamily family = profile.treeFamily();
      if (species == TellusLodGenerator.TreeSpecies.MANGROVE) {
         return 5 + jitter + (centerHash >>> 19 & 1);
      } else if (species == TellusLodGenerator.TreeSpecies.JUNGLE) {
         int height = 9 + jitter;
         if ((centerHash >>> 18 & 7) == 0) {
            height += 8;
         }

         return height;
      } else if (family == TellusLodGenerator.TreeLodFamily.OLD_GROWTH_SPRUCE) {
         return 8 + jitter + (centerHash >>> 18 & 3);
      } else if (species == TellusLodGenerator.TreeSpecies.SPRUCE) {
         return 6 + jitter;
      } else if (family == TellusLodGenerator.TreeLodFamily.OLD_GROWTH_BIRCH) {
         return 7 + jitter;
      } else if (species == TellusLodGenerator.TreeSpecies.BIRCH) {
         return 5 + jitter;
      } else if (species == TellusLodGenerator.TreeSpecies.ACACIA) {
         return 5 + jitter;
      } else if (species == TellusLodGenerator.TreeSpecies.DARK_OAK || species == TellusLodGenerator.TreeSpecies.PALE_OAK) {
         return 4 + jitter;
      } else if (species == TellusLodGenerator.TreeSpecies.HUGE_BROWN_MUSHROOM || species == TellusLodGenerator.TreeSpecies.HUGE_RED_MUSHROOM) {
         return 3 + jitter;
      } else if (species == TellusLodGenerator.TreeSpecies.CHERRY) {
         return 3 + jitter;
      } else {
         return 4 + jitter;
      }
   }

   private static int canopyRootHeight(
      TellusLodGenerator.CanopyProfile profile,
      TellusLodGenerator.TreeSpecies species,
      boolean isCenter,
      int bestDist,
      int centerHash
   ) {
      if (species != TellusLodGenerator.TreeSpecies.MANGROVE || bestDist > 2) {
         return 0;
      } else {
         int base = isCenter ? 2 : 1;
         return base + (centerHash >>> 17 & 1);
      }
   }

   private static int canopyLeafLift(
      TellusLodGenerator.CanopyProfile profile,
      TellusLodGenerator.TreeSpecies species,
      boolean isCenter,
      int centerTrunkHeight,
      int bestDist,
      int centerHash
   ) {
      if (isCenter) {
         return 0;
      } else {
         TellusLodGenerator.TreeLodFamily family = profile.treeFamily();
         int lift;
         if (family == TellusLodGenerator.TreeLodFamily.SPRUCE || family == TellusLodGenerator.TreeLodFamily.OLD_GROWTH_SPRUCE) {
            lift = Math.max(1, centerTrunkHeight - bestDist * 2);
         } else if (species == TellusLodGenerator.TreeSpecies.ACACIA) {
            int crownDrop = bestDist <= 2 ? 2 : 1;
            lift = Math.max(2, centerTrunkHeight - crownDrop);
         } else if (species == TellusLodGenerator.TreeSpecies.JUNGLE) {
            lift = Math.max(4, centerTrunkHeight - Math.max(1, bestDist));
         } else if (species == TellusLodGenerator.TreeSpecies.MANGROVE) {
            lift = Math.max(2, centerTrunkHeight - bestDist);
         } else if (species == TellusLodGenerator.TreeSpecies.DARK_OAK || species == TellusLodGenerator.TreeSpecies.PALE_OAK) {
            lift = Math.max(2, centerTrunkHeight - Math.max(0, bestDist - 1));
         } else if (species == TellusLodGenerator.TreeSpecies.HUGE_BROWN_MUSHROOM || species == TellusLodGenerator.TreeSpecies.HUGE_RED_MUSHROOM) {
            lift = Math.max(2, centerTrunkHeight);
         } else {
            lift = Math.max(1, centerTrunkHeight - Math.max(0, bestDist - 1));
         }

         if (bestDist > 1 && (centerHash >>> 20 & 1) == 0) {
            lift = Math.max(1, lift - 1);
         }

         return lift;
      }
   }

   private static TellusLodGenerator.WaterVegetationColumn resolveWaterVegetationColumn(
      TellusLodGenerator.CanopyProfile profile, int worldX, int worldZ, int waterDepth
   ) {
      if (waterDepth < 1) {
         return null;
      } else {
         int chance = waterVegetationChancePercent(profile);
         if (chance <= 0) {
            return null;
         } else {
            int hash = LodOceanVegetationPolicy.patchHash(worldX, worldZ);
            if (!LodOceanVegetationPolicy.shouldPlace(chance, hash)) {
               return null;
            } else {
               boolean coral = LodOceanVegetationPolicy.shouldUseCoral(profile.isWarmOcean(), waterDepth, hash);
               boolean kelp = !coral && shouldUseKelp(profile, waterDepth, hash);
               BlockState blockState = coral
                  ? selectCoralBlock(hash)
                  : kelp ? Blocks.KELP_PLANT.defaultBlockState() : Blocks.SEAGRASS.defaultBlockState();
               int height = LodOceanVegetationPolicy.columnHeight(coral, waterDepth, hash);
               return height <= 0 ? null : new TellusLodGenerator.WaterVegetationColumn(height, blockState);
            }
         }
      }
   }

   private static BlockState selectCoralBlock(int patchHash) {
      return switch (LodOceanVegetationPolicy.coralVariant(patchHash)) {
         case 0 -> Blocks.TUBE_CORAL_BLOCK.defaultBlockState();
         case 1 -> Blocks.BRAIN_CORAL_BLOCK.defaultBlockState();
         case 2 -> Blocks.BUBBLE_CORAL_BLOCK.defaultBlockState();
         case 3 -> Blocks.FIRE_CORAL_BLOCK.defaultBlockState();
         default -> Blocks.HORN_CORAL_BLOCK.defaultBlockState();
      };
   }

   private static int waterVegetationChancePercent(TellusLodGenerator.CanopyProfile profile) {
      return profile.waterVegetationChance();
   }

   private static boolean shouldUseKelp(TellusLodGenerator.CanopyProfile profile, int waterDepth, int centerHash) {
      if (profile.isRiver()) {
         return false;
      } else if (waterDepth < 6) {
         return false;
      } else {
         int chance;
         if (profile.isWarmOcean()) {
            chance = 15;
         } else if (profile.isLukewarmOcean() || profile.isDeepLukewarmOcean()) {
            chance = 25;
         } else if (profile.isOcean()) {
            chance = 35;
         } else {
            chance = 0;
         }

         int roll = centerHash >>> 18 & 0xFF;
         int threshold = chance * 255 / 100;
         return roll < threshold;
      }
   }

   private static int centerOffset(int hash, int gridSize) {
      return Math.floorMod(hash, gridSize);
   }

   private static TellusLodGenerator.TreeSpecies selectCanopySpecies(
      TellusLodGenerator.CanopyProfile profile, int worldX, int worldZ, int centerHash, boolean hugeRedMushrooms
   ) {
      return profile.treeFamily().selectSpecies(mixHash(worldX, worldZ, centerHash), hugeRedMushrooms);
   }

   private static BlockState selectCanopyBlock(TellusLodGenerator.TreeSpecies species) {
      return species.leavesBlock();
   }

   private static BlockState selectTrunkBlock(TellusLodGenerator.TreeSpecies species) {
      return species.trunkBlock();
   }

   private static BlockState selectRootBlock(TellusLodGenerator.TreeSpecies species) {
      return species.rootBlock();
   }

   private static int mixHash(int worldX, int worldZ, int seed) {
      int h = worldX * 522133279 ^ worldZ * -1640531527 ^ seed * 668265261;
      h ^= h >>> 15;
      h *= -2048144789;
      h ^= h >>> 13;
      h *= -1028477387;
      return h ^ h >>> 16;
   }

   private static boolean isBiomePath(Holder<Biome> biome, String path) {
      return biome.unwrapKey().map(key -> key.toString().contains("minecraft:" + path)).orElse(false);
   }

   private static double sampleValueNoise(int blockX, int blockZ, int grid, long salt) {
      int gridX = Math.floorDiv(blockX, grid);
      int gridZ = Math.floorDiv(blockZ, grid);
      double fracX = Math.floorMod(blockX, grid) / (double)grid;
      double fracZ = Math.floorMod(blockZ, grid) / (double)grid;
      double sx = smoothstep(fracX);
      double sz = smoothstep(fracZ);
      double n00 = gridNoise(gridX, gridZ, salt);
      double n10 = gridNoise(gridX + 1, gridZ, salt);
      double n01 = gridNoise(gridX, gridZ + 1, salt);
      double n11 = gridNoise(gridX + 1, gridZ + 1, salt);
      double nx0 = Mth.lerp(sx, n00, n10);
      double nx1 = Mth.lerp(sx, n01, n11);
      return Mth.lerp(sz, nx0, nx1);
   }

   private static double gridNoise(int gridX, int gridZ, long salt) {
      return hashToUnit(gridSeed(gridX, gridZ, salt));
   }

   private static long gridSeed(int gridX, int gridZ, long salt) {
      return gridX * 341873128712L + gridZ * 132897987541L + salt;
   }

   private static double smoothstep(double value) {
      return value * value * (3.0 - 2.0 * value);
   }

   private static double hashToUnit(long seed) {
      seed ^= seed >>> 33;
      seed *= -49064778989728563L;
      seed ^= seed >>> 33;
      seed *= -4265267296055464877L;
      seed ^= seed >>> 33;
      return (seed >>> 11) * 1.110223E-16F;
   }

   private static long seedFromCoords(int x, int y, int z) {
      long seed = x * 3129871 ^ z * 116129781L ^ y;
      seed = seed * seed * 42317861L + seed * 11L;
      return seed >> 16;
   }

   private static int seededRandomInt(long seed, int bound) {
      if (bound <= 0) {
         throw new IllegalArgumentException("bound must be positive");
      }

      long state = (seed ^ JAVA_RANDOM_MULTIPLIER) & JAVA_RANDOM_MASK;
      if ((bound & -bound) == bound) {
         state = nextJavaRandomState(state);
         return (int)(((long)bound * (long)((int)(state >>> 17))) >> 31);
      } else {
         int bits;
         int value;
         do {
            state = nextJavaRandomState(state);
            bits = (int)(state >>> 17);
            value = bits % bound;
         } while (bits - value + (bound - 1) < 0);

         return value;
      }
   }

   private static long nextJavaRandomState(long state) {
      return (state * JAVA_RANDOM_MULTIPLIER + JAVA_RANDOM_ADDEND) & JAVA_RANDOM_MASK;
   }

   private static BlockState blockStateByField(String fieldName, Block fallback) {
      try {
         Object value = Blocks.class.getField(fieldName).get(null);
         return value instanceof Block block ? block.defaultBlockState() : fallback.defaultBlockState();
      } catch (IllegalAccessException | NoSuchFieldException error) {
         return fallback.defaultBlockState();
      }
   }

   public EDhApiWorldGeneratorReturnType getReturnType() {
      return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES;
   }

   public boolean runApiValidation() {
      return false;
   }

   public void close() {
      this.lodPrefetchBatcher.close();
   }

   private static final class CancellableLodFuture extends CompletableFuture<Void> {
      private volatile CompletableFuture<Void> delegate;
      private final ConcurrentLinkedQueue<CompletableFuture<?>> cancellationDelegates = new ConcurrentLinkedQueue<>();
      private volatile Thread runner;

      private Runnable track(Runnable task) {
         return () -> {
            Thread current = Thread.currentThread();
            this.runner = current;
            if (this.isCancelled()) {
               this.runner = null;
               return;
            }
            try {
               task.run();
            } finally {
               if (this.runner == current) {
                  this.runner = null;
               }
            }
         };
      }

      private void attach(CompletableFuture<Void> delegate) {
         this.delegate = delegate;
         if (this.isCancelled()) {
            delegate.cancel(true);
         }
         delegate.whenComplete((ignored, error) -> {
            if (error == null) {
               this.complete(null);
            } else {
               this.completeExceptionally(error);
            }
         });
      }

      private void attachCancellation(CompletableFuture<?> delegate) {
         this.cancellationDelegates.add(delegate);
         delegate.whenComplete((ignored, error) -> this.cancellationDelegates.remove(delegate));
         if (this.isCancelled()) {
            delegate.cancel(true);
         }
      }

      @Override
      public boolean cancel(boolean mayInterruptIfRunning) {
         boolean cancelled = super.cancel(mayInterruptIfRunning);
         if (cancelled) {
            CompletableFuture<Void> currentDelegate = this.delegate;
            if (currentDelegate != null) {
               currentDelegate.cancel(mayInterruptIfRunning);
            }
            for (CompletableFuture<?> cancellationDelegate : this.cancellationDelegates) {
               cancellationDelegate.cancel(mayInterruptIfRunning);
            }
            Thread currentRunner = this.runner;
            if (mayInterruptIfRunning && currentRunner != null) {
               currentRunner.interrupt();
            }
         }
         return cancelled;
      }
   }

   private record DataCanopyDimensions(int trunkHeight, int crownHeight, int leafLift) {
   }

   private static final class CanopyRequestCache {
      private final EarthChunkGenerator generator;
      private final WorldProjection projection;
      private final Map<Long, TellusCanopyHeightSource.CanopySample> canopySamples = new HashMap<>();
      private final Map<Long, ResolveEcoregion> ecoregions = new HashMap<>();
      private final Map<TellusLodGenerator.CanopyPlanKey, TellusProceduralTreeGenerator.TreePlan> treePlans = new HashMap<>();
      private final Map<Long, Integer> treeAnchorSurfaces = new HashMap<>();

      private CanopyRequestCache(EarthChunkGenerator generator) {
         this.generator = Objects.requireNonNull(generator, "generator");
         this.projection = generator.settings().projection();
      }

      private TellusCanopyHeightSource.CanopySample canopy(
         int worldX, int worldZ, double worldScale, double previewResolutionMeters
      ) {
         long key = packHorizontal(worldX, worldZ);
         return this.canopySamples.computeIfAbsent(
            key,
            ignored -> TellusWorldgenSources.canopyHeight()
               .sampleCanopyLocalOnly(worldX, worldZ, this.projection, previewResolutionMeters)
         );
      }

      private TellusProceduralTreeGenerator.TreePlan treePlan(
         int worldX,
         int worldZ,
         double worldScale,
         long treeSeed,
         Holder<Biome> biome,
         TellusCanopyHeightSource.CanopySample canopy
      ) {
         TellusLodGenerator.CanopyPlanKey key = new TellusLodGenerator.CanopyPlanKey(
            worldX, worldZ, treeSeed, biome
         );
         return this.treePlans.computeIfAbsent(key, ignored -> {
            long horizontalKey = packHorizontal(worldX, worldZ);
            ResolveEcoregion ecoregion = this.ecoregions.computeIfAbsent(
               horizontalKey,
               unused -> TellusResolveSource.shared().sampleEcoregion(worldX, worldZ, this.projection)
            );
            return TellusProceduralTreeGenerator.plan(biome, ecoregion, canopy, treeSeed);
         });
      }

      private int treeAnchorSurface(int worldX, int worldZ, double worldScale) {
         long key = packHorizontal(worldX, worldZ);
         return this.treeAnchorSurfaces.computeIfAbsent(key, ignored -> {
            int coverClass = this.generator.sampleCoverClass(worldX, worldZ, worldScale);
            return this.generator.resolveLodTerrainSurface(worldX, worldZ, coverClass, worldScale);
         });
      }

      private static long packHorizontal(int worldX, int worldZ) {
         return (long)worldX << 32 ^ worldZ & 0xFFFF_FFFFL;
      }
   }

   private record CanopyPlanKey(int worldX, int worldZ, long treeSeed, Holder<Biome> biome) {
   }

   private static final class CanopyColumn {
      private final int rootHeight;
      private final int trunkHeight;
      private final int leafLift;
      private final int leavesHeight;
      private final BlockState leavesBlock;
      private final BlockState trunkBlock;
      private final BlockState rootBlock;
      private final int anchorSurfaceY;

      private CanopyColumn(
         int rootHeight,
         int trunkHeight,
         int leafLift,
         int leavesHeight,
         BlockState leavesBlock,
         BlockState trunkBlock,
         BlockState rootBlock,
         int anchorSurfaceY
      ) {
         this.rootHeight = rootHeight;
         this.trunkHeight = trunkHeight;
         this.leafLift = leafLift;
         this.leavesHeight = leavesHeight;
         this.leavesBlock = leavesBlock;
         this.trunkBlock = trunkBlock;
         this.rootBlock = rootBlock;
         this.anchorSurfaceY = anchorSurfaceY;
      }
   }

   private static enum TreeSpecies {
      OAK(Blocks.OAK_LOG.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState()),
      BIRCH(Blocks.BIRCH_LOG.defaultBlockState(), Blocks.BIRCH_LEAVES.defaultBlockState()),
      SPRUCE(Blocks.SPRUCE_LOG.defaultBlockState(), Blocks.SPRUCE_LEAVES.defaultBlockState()),
      JUNGLE(Blocks.JUNGLE_LOG.defaultBlockState(), Blocks.JUNGLE_LEAVES.defaultBlockState()),
      ACACIA(Blocks.ACACIA_LOG.defaultBlockState(), Blocks.ACACIA_LEAVES.defaultBlockState()),
      DARK_OAK(Blocks.DARK_OAK_LOG.defaultBlockState(), Blocks.DARK_OAK_LEAVES.defaultBlockState()),
      MANGROVE(Blocks.MANGROVE_LOG.defaultBlockState(), Blocks.MANGROVE_LEAVES.defaultBlockState(), Blocks.MANGROVE_ROOTS.defaultBlockState()),
      CHERRY(Blocks.CHERRY_LOG.defaultBlockState(), Blocks.CHERRY_LEAVES.defaultBlockState()),
      PALE_OAK(blockStateByField("PALE_OAK_LOG", Blocks.DARK_OAK_LOG), blockStateByField("PALE_OAK_LEAVES", Blocks.DARK_OAK_LEAVES)),
      HUGE_BROWN_MUSHROOM(Blocks.MUSHROOM_STEM.defaultBlockState(), Blocks.BROWN_MUSHROOM_BLOCK.defaultBlockState()),
      HUGE_RED_MUSHROOM(Blocks.MUSHROOM_STEM.defaultBlockState(), Blocks.RED_MUSHROOM_BLOCK.defaultBlockState());

      private final BlockState trunkBlock;
      private final BlockState leavesBlock;
      private final BlockState rootBlock;

      private TreeSpecies(BlockState trunkBlock, BlockState leavesBlock) {
         this(trunkBlock, leavesBlock, null);
      }

      private TreeSpecies(BlockState trunkBlock, BlockState leavesBlock, BlockState rootBlock) {
         this.trunkBlock = trunkBlock;
         this.leavesBlock = leavesBlock;
         this.rootBlock = rootBlock;
      }

      private BlockState trunkBlock() {
         return this.trunkBlock;
      }

      private BlockState leavesBlock() {
         return this.leavesBlock;
      }

      private BlockState rootBlock() {
         return this.rootBlock;
      }
   }

   private static enum TreeLodFamily {
      NONE(0, 0, 0, 0),
      MIXED_FOREST(62, 3, 2, 3),
      BIRCH(58, 2, 2, 3),
      OLD_GROWTH_BIRCH(50, 3, 3, 4),
      SPRUCE(62, 3, 3, 5),
      OLD_GROWTH_SPRUCE(54, 4, 4, 6),
      WINDSWEPT_FOREST(45, 2, 2, 4),
      JUNGLE(72, 5, 4, 6),
      ACACIA(48, 4, 2, 3),
      DARK_OAK(76, 4, 3, 4),
      MANGROVE(82, 5, 4, 5),
      CHERRY(60, 3, 2, 4),
      PALE_OAK(76, 4, 3, 4),
      SWAMP_OAK(50, 3, 2, 3),
      WOODED_BADLANDS(38, 2, 2, 3),
      MUSHROOM(45, 3, 1, 2);

      private final int baseChance;
      private final int baseRadius;
      private final int baseLeavesHeight;
      private final int maxLeavesHeight;

      private TreeLodFamily(int baseChance, int baseRadius, int baseLeavesHeight, int maxLeavesHeight) {
         this.baseChance = baseChance;
         this.baseRadius = baseRadius;
         this.baseLeavesHeight = baseLeavesHeight;
         this.maxLeavesHeight = maxLeavesHeight;
      }

      private int baseChance() {
         return this.baseChance;
      }

      private int baseRadius() {
         return this.baseRadius;
      }

      private int baseLeavesHeight() {
         return this.baseLeavesHeight;
      }

      private int maxLeavesHeight() {
         return this.maxLeavesHeight;
      }

      private TellusLodGenerator.TreeSpecies selectSpecies(int hash, boolean hugeRedMushrooms) {
         int roll = hash & 0xFF;
         return switch (this) {
            case MIXED_FOREST -> roll < 70 ? TellusLodGenerator.TreeSpecies.BIRCH : TellusLodGenerator.TreeSpecies.OAK;
            case BIRCH, OLD_GROWTH_BIRCH -> TellusLodGenerator.TreeSpecies.BIRCH;
            case SPRUCE, OLD_GROWTH_SPRUCE -> TellusLodGenerator.TreeSpecies.SPRUCE;
            case WINDSWEPT_FOREST -> roll < 185 ? TellusLodGenerator.TreeSpecies.SPRUCE : TellusLodGenerator.TreeSpecies.OAK;
            case JUNGLE -> TellusLodGenerator.TreeSpecies.JUNGLE;
            case ACACIA -> TellusLodGenerator.TreeSpecies.ACACIA;
            case DARK_OAK -> TellusLodGenerator.TreeSpecies.DARK_OAK;
            case MANGROVE -> TellusLodGenerator.TreeSpecies.MANGROVE;
            case CHERRY -> TellusLodGenerator.TreeSpecies.CHERRY;
            case PALE_OAK -> TellusLodGenerator.TreeSpecies.PALE_OAK;
            case SWAMP_OAK, WOODED_BADLANDS -> TellusLodGenerator.TreeSpecies.OAK;
            case MUSHROOM -> !hugeRedMushrooms || roll < 128
               ? TellusLodGenerator.TreeSpecies.HUGE_BROWN_MUSHROOM
               : TellusLodGenerator.TreeSpecies.HUGE_RED_MUSHROOM;
            case NONE -> TellusLodGenerator.TreeSpecies.OAK;
         };
      }

      private boolean isWideCrown() {
         return this == DARK_OAK || this == PALE_OAK || this == MANGROVE || this == JUNGLE || this == OLD_GROWTH_SPRUCE;
      }

      private boolean isNarrowCrown() {
         return this == BIRCH || this == WOODED_BADLANDS;
      }

      private boolean isFlatCrown() {
         return this == ACACIA || this == MUSHROOM;
      }

      private boolean isDenseCrown() {
         return this == DARK_OAK || this == PALE_OAK || this == MANGROVE;
      }

      private boolean isTallCrown() {
         return this == JUNGLE || this == OLD_GROWTH_SPRUCE || this == MANGROVE;
      }
   }

   private record CanopyProfile(
      boolean isMangrove,
      boolean isDarkForest,
      boolean isBambooJungle,
      boolean isSparseJungle,
      boolean isWindsweptForest,
      boolean isWoodedBadlands,
      boolean isWindsweptSavanna,
      boolean isSavannaPlateau,
      boolean isCherryGrove,
      boolean isSwamp,
      boolean isJungle,
      boolean isForest,
      boolean isTaiga,
      boolean isSavanna,
      boolean isOcean,
      boolean isRiver,
      boolean isWarmOcean,
      boolean isLukewarmOcean,
      boolean isDeepLukewarmOcean,
      int canopyBaseChance,
      int canopyBaseRadius,
      int canopyBaseHeight,
      int canopyMaxHeight,
      int waterVegetationChance,
      TellusLodGenerator.TreeLodFamily treeFamily
   ) {
      private boolean hasCanopy() {
         return this.treeFamily != TellusLodGenerator.TreeLodFamily.NONE && this.canopyBaseChance > 0;
      }

      private boolean isSavannaFamily() {
         return this.isSavanna || this.isWindsweptSavanna || this.isSavannaPlateau;
      }
   }

   private record LodRoadMaskResult(
      byte[] mask,
      byte[] styleMask,
      boolean[] markingMask,
      int[] bridgeDeckY,
      int[] bridgeSupportShaftBottomY,
      int[] bridgeSupportShaftTopY,
      int[] bridgeSupportCapBottomY,
      int[] bridgeSupportCapTopY,
      int[] roadLightBaseY,
      byte[] roadLightFenceCount,
      boolean hadCacheMisses
   ) {
      private LodRoadMaskResult(
         byte[] mask,
         boolean[] markingMask,
         int[] bridgeDeckY,
         int[] bridgeSupportShaftBottomY,
         int[] bridgeSupportShaftTopY,
         int[] bridgeSupportCapBottomY,
         int[] bridgeSupportCapTopY,
         int[] roadLightBaseY,
         byte[] roadLightFenceCount,
         boolean hadCacheMisses
      ) {
         this(
            mask,
            null,
            markingMask,
            bridgeDeckY,
            bridgeSupportShaftBottomY,
            bridgeSupportShaftTopY,
            bridgeSupportCapBottomY,
            bridgeSupportCapTopY,
            roadLightBaseY,
            roadLightFenceCount,
            hadCacheMisses
         );
      }

      private LodRoadMaskResult(
         byte[] mask,
         int[] bridgeDeckY,
         int[] bridgeSupportShaftBottomY,
         int[] bridgeSupportShaftTopY,
         int[] bridgeSupportCapBottomY,
         int[] bridgeSupportCapTopY,
         int[] roadLightBaseY,
         byte[] roadLightFenceCount,
         boolean hadCacheMisses
      ) {
         this(
            mask,
            null,
            null,
            bridgeDeckY,
            bridgeSupportShaftBottomY,
            bridgeSupportShaftTopY,
            bridgeSupportCapBottomY,
            bridgeSupportCapTopY,
            roadLightBaseY,
            roadLightFenceCount,
            hadCacheMisses
         );
      }
   }

   private record LodBuildingMaskResult(TellusLodGenerator.LodBuildingColumn[] columns, int[] flattenedSurface, boolean hadCacheMisses) {
   }

   private static final class LodBuildingGroupScratch {
      private final IntArrayList groundSamples = new IntArrayList();
      private final IntArrayList fallbackSamples = new IntArrayList();
      private int baseY = Integer.MIN_VALUE;

      private IntArrayList groundSamples() {
         return this.groundSamples;
      }

      private IntArrayList fallbackSamples() {
         return this.fallbackSamples;
      }

      private int baseY() {
         return this.baseY;
      }

      private void setBaseY(int baseY) {
         this.baseY = baseY;
      }
   }

   private record LodRasterizedBuildingFeature(
      OsmBuildingFeature feature, String groupId, int lodSize, int minGridX, int minGridZ, int width, int height, boolean[] occupiedMask, int[] occupiedCells
   ) {
   }

   private record SampledRoadStation(double worldX, double worldZ, double tangentX, double tangentZ) {
   }

   private static final class LodBuildingColumn {
      private int[] starts = new int[2];
      private int[] ends = new int[2];
      private BlockState[] blockStates = new BlockState[2];
      private byte[] emittedLights = new byte[2];
      private int size;

      private boolean isEmpty() {
         return this.size == 0;
      }

      private int size() {
         return this.size;
      }

      private int startY(int index) {
         return this.starts[index];
      }

      private int endY(int index) {
         return this.ends[index];
      }

      private BlockState blockState(int index) {
         return this.blockStates[index];
      }

      private byte emittedLight(int index) {
         return this.emittedLights[index];
      }

      private boolean intersectsSpan(int minY, int maxY) {
         for (int i = 0; i < this.size; i++) {
            if (this.ends[i] >= minY && this.starts[i] <= maxY) {
               return true;
            }
         }

         return false;
      }

      private void addSpan(int startY, int endY, BlockState blockState, byte emittedLight) {
         if (blockState == null || endY < startY) {
            return;
         }

         int mergedStart = startY;
         int mergedEnd = endY;
         int insertAt = this.size;

         for (int i = 0; i < this.size; i++) {
            if (mergedEnd + 1 < this.starts[i]) {
               insertAt = i;
               break;
            }

            if (this.blockStates[i] == blockState
               && this.emittedLights[i] == emittedLight
               && mergedStart <= this.ends[i] + 1
               && mergedEnd + 1 >= this.starts[i]) {
               mergedStart = Math.min(mergedStart, this.starts[i]);
               mergedEnd = Math.max(mergedEnd, this.ends[i]);
               this.removeAt(i--);
               insertAt = i + 1;
            }
         }

         this.ensureCapacity(this.size + 1);
         if (insertAt < this.size) {
            System.arraycopy(this.starts, insertAt, this.starts, insertAt + 1, this.size - insertAt);
            System.arraycopy(this.ends, insertAt, this.ends, insertAt + 1, this.size - insertAt);
            System.arraycopy(this.blockStates, insertAt, this.blockStates, insertAt + 1, this.size - insertAt);
            System.arraycopy(this.emittedLights, insertAt, this.emittedLights, insertAt + 1, this.size - insertAt);
         }

         this.starts[insertAt] = mergedStart;
         this.ends[insertAt] = mergedEnd;
         this.blockStates[insertAt] = blockState;
         this.emittedLights[insertAt] = emittedLight;
         this.size++;
      }

      private void removeAt(int index) {
         if (index >= 0 && index < this.size) {
            if (index + 1 < this.size) {
               System.arraycopy(this.starts, index + 1, this.starts, index, this.size - index - 1);
               System.arraycopy(this.ends, index + 1, this.ends, index, this.size - index - 1);
               System.arraycopy(this.blockStates, index + 1, this.blockStates, index, this.size - index - 1);
               System.arraycopy(this.emittedLights, index + 1, this.emittedLights, index, this.size - index - 1);
            }

            this.size--;
         }
      }

      private void ensureCapacity(int capacity) {
         if (capacity > this.starts.length) {
            int newCapacity = Math.max(capacity, this.starts.length << 1);
            this.starts = Arrays.copyOf(this.starts, newCapacity);
            this.ends = Arrays.copyOf(this.ends, newCapacity);
            this.blockStates = Arrays.copyOf(this.blockStates, newCapacity);
            this.emittedLights = Arrays.copyOf(this.emittedLights, newCapacity);
         }
      }
   }

   private record SurfaceWrapperPair(IDhApiBlockStateWrapper top, IDhApiBlockStateWrapper filler) {
   }

   private static final class LodSurfaceResolveProfiler implements EarthChunkGenerator.LodSurfaceProfiler {
      private final LinkedHashMap<String, Long> phaseNanos = new LinkedHashMap<>();

      @Override
      public void addPhase(String phase, long nanos) {
         this.phaseNanos.merge(phase, Math.max(0L, nanos), Long::sum);
      }

      private void flushTo(TellusLodGenerator.LodTimingTrace trace) {
         for (Map.Entry<String, Long> entry : this.phaseNanos.entrySet()) {
            trace.addPhase("emit.surfaceResolve." + entry.getKey(), entry.getValue());
         }
      }
   }

   private static final class LodTimingTrace {
      private final boolean enabled;
      private final int chunkPosMinX;
      private final int chunkPosMinZ;
      private final int detailLevel;
      private final int lodSizePoints;
      private final int cellSize;
      private final EDhApiDistantGeneratorMode generatorMode;
      private final EarthGeneratorSettings.DistantHorizonsRenderMode renderMode;
      private final long startNanos;
      private final LinkedHashMap<String, String> notes = new LinkedHashMap<>();
      private final LinkedHashMap<String, String> stats = new LinkedHashMap<>();
      private final LinkedHashMap<String, Long> phaseNanos = new LinkedHashMap<>();

      private LodTimingTrace(
         int chunkPosMinX,
         int chunkPosMinZ,
         byte detailLevel,
         int lodSizePoints,
         int cellSize,
         EDhApiDistantGeneratorMode generatorMode,
         EarthGeneratorSettings.DistantHorizonsRenderMode renderMode
      ) {
         this.enabled = LOD_TIMING_LOGGING;
         this.chunkPosMinX = chunkPosMinX;
         this.chunkPosMinZ = chunkPosMinZ;
         this.detailLevel = Byte.toUnsignedInt(detailLevel);
         this.lodSizePoints = lodSizePoints;
         this.cellSize = cellSize;
         this.generatorMode = generatorMode;
         this.renderMode = renderMode;
         this.startNanos = this.enabled ? System.nanoTime() : 0L;
      }

      private boolean isEnabled() {
         return this.enabled;
      }

      private void note(String key, Object value) {
         if (this.enabled) {
            this.notes.put(key, String.valueOf(value));
         }
      }

      private void addPhase(String phase, long nanos) {
         if (this.enabled) {
            this.phaseNanos.merge(phase, Math.max(0L, nanos), Long::sum);
         }
      }

      private void stat(String key, Object value) {
         if (this.enabled) {
            this.stats.put(key, String.valueOf(value));
         }
      }

      private void logSuccess() {
         if (this.shouldLog()) {
            LOGGER.info(this.summary("success"));
         }
      }

      private void logCancelled() {
         if (this.shouldLog()) {
            LOGGER.info(this.summary("cancelled"));
         }
      }

      private void logFailure(Throwable throwable) {
         if (this.enabled) {
            LOGGER.warn(this.summary("failed"), throwable);
         }
      }

      private boolean shouldLog() {
         return this.enabled && this.elapsedNanos() >= LOD_TIMING_THRESHOLD_NS;
      }

      private long elapsedNanos() {
         return this.enabled ? System.nanoTime() - this.startNanos : 0L;
      }

      private String summary(String status) {
         StringBuilder builder = new StringBuilder(256);
         builder.append("DH LOD timing status=").append(status);
         builder.append(" chunk=[").append(this.chunkPosMinX).append(", ").append(this.chunkPosMinZ).append(']');
         builder.append(" detail=").append(this.detailLevel);
         builder.append(" width=").append(this.lodSizePoints);
         builder.append(" cell=").append(this.cellSize);
         builder.append(" genMode=").append(this.generatorMode);
         builder.append(" render=").append(this.renderMode.id());
         builder.append(" total=").append(formatMillis(this.elapsedNanos()));
         if (!this.notes.isEmpty()) {
            builder.append(" notes={");
            boolean first = true;
            for (Map.Entry<String, String> entry : this.notes.entrySet()) {
               if (!first) {
                  builder.append(", ");
               }

               builder.append(entry.getKey()).append('=').append(entry.getValue());
               first = false;
            }

            builder.append('}');
         }

         if (!this.phaseNanos.isEmpty()) {
            builder.append(" phases={");
            boolean first = true;
            for (Map.Entry<String, Long> entry : this.phaseNanos.entrySet()) {
               if (!first) {
                  builder.append(", ");
               }

               builder.append(entry.getKey()).append('=').append(formatMillis(entry.getValue()));
               first = false;
            }

            builder.append('}');
         }

         if (!this.stats.isEmpty()) {
            builder.append(" stats={");
            boolean first = true;
            for (Map.Entry<String, String> entry : this.stats.entrySet()) {
               if (!first) {
                  builder.append(", ");
               }

               builder.append(entry.getKey()).append('=').append(entry.getValue());
               first = false;
            }

            builder.append('}');
         }

         return builder.toString();
      }

      private static String formatMillis(long nanos) {
         return String.format(Locale.ROOT, "%.3fms", (double)nanos / 1000000.0);
      }
   }

   private static final class WaterVegetationColumn {
      private final int height;
      private final BlockState blockState;

      private WaterVegetationColumn(int height, BlockState blockState) {
         this.height = height;
         this.blockState = blockState;
      }
   }

   private static class WrapperCache {
      private final IDhApiLevelWrapper levelWrapper;
      private final IDhApiBlockStateWrapper airBlock;
      private final IDhApiBiomeWrapper defaultBiome;
      private final IDhApiBiomeWrapper forestBiome;
      private final IDhApiBiomeWrapper taigaBiome;
      private final IDhApiBiomeWrapper jungleBiome;
      private final IDhApiBiomeWrapper savannaBiome;
      private final IDhApiBiomeWrapper darkForestBiome;
      private final IDhApiBiomeWrapper mangroveBiome;
      private final IDhApiBiomeWrapper cherryGroveBiome;
      private final IDhApiBiomeWrapper swampBiome;
      private final Map<BlockState, IDhApiBlockStateWrapper> blockStates = new IdentityHashMap<>();
      private final Map<Holder<Biome>, IDhApiBiomeWrapper> biomes = new HashMap<>();

      private WrapperCache(IDhApiLevelWrapper levelWrapper) {
         this.levelWrapper = levelWrapper;
         this.airBlock = Delayed.wrapperFactory.getAirBlockStateWrapper();
         this.defaultBiome = this.lookupBiomeById(Biomes.PLAINS);
         IDhApiBiomeWrapper forest = this.lookupBiomeById(Biomes.FOREST);
         this.forestBiome = forest != null ? forest : Objects.requireNonNull(this.defaultBiome, "No default biome available");
         this.taigaBiome = this.lookupBiomeByIdOrFallback(Biomes.TAIGA, this.forestBiome);
         this.jungleBiome = this.lookupBiomeByIdOrFallback(Biomes.JUNGLE, this.forestBiome);
         this.savannaBiome = this.lookupBiomeByIdOrFallback(Biomes.SAVANNA, this.forestBiome);
         this.darkForestBiome = this.lookupBiomeByIdOrFallback(Biomes.DARK_FOREST, this.forestBiome);
         this.mangroveBiome = this.lookupBiomeByIdOrFallback(Biomes.MANGROVE_SWAMP, this.forestBiome);
         this.cherryGroveBiome = this.lookupBiomeByIdOrFallback(Biomes.CHERRY_GROVE, this.forestBiome);
         this.swampBiome = this.lookupBiomeByIdOrFallback(Biomes.SWAMP, this.forestBiome);
      }

      public IDhApiBlockStateWrapper airBlock() {
         return this.airBlock;
      }

      public IDhApiBlockStateWrapper getBlockState(BlockState blockState) {
         return this.blockStates.computeIfAbsent(blockState, this::lookupBlockState);
      }

      private IDhApiBlockStateWrapper lookupBlockState(BlockState blockState) {
         try {
            return Delayed.wrapperFactory.getBlockStateWrapper(new BlockState[]{blockState}, this.levelWrapper);
         } catch (ClassCastException var3) {
            throw new IllegalStateException(var3);
         }
      }

      public IDhApiBiomeWrapper getBiome(Holder<Biome> biome) {
         return this.biomes.computeIfAbsent(biome, this::lookupBiome);
      }

      public IDhApiBiomeWrapper canopyLeafBiome(TellusLodGenerator.CanopyProfile profile, IDhApiBiomeWrapper fallback) {
         if (profile.isMangrove()) {
            return this.mangroveBiome;
         } else if (profile.treeFamily() == TellusLodGenerator.TreeLodFamily.PALE_OAK) {
            return this.darkForestBiome;
         } else if (profile.isDarkForest()) {
            return this.darkForestBiome;
         } else if (profile.isSparseJungle() || profile.isBambooJungle() || profile.isJungle()) {
            return this.jungleBiome;
         } else if (profile.isTaiga() || profile.isWindsweptForest()) {
            return this.taigaBiome;
         } else if (profile.isSavannaFamily()) {
            return this.savannaBiome;
         } else if (profile.isCherryGrove()) {
            return this.cherryGroveBiome;
         } else if (profile.isSwamp()) {
            return this.swampBiome;
         } else {
            return this.forestBiome != null ? this.forestBiome : fallback;
         }
      }

      private IDhApiBiomeWrapper lookupBiome(Holder<Biome> biome) {
         IDhApiBiomeWrapper result = biome.unwrapKey().map(this::lookupBiomeById).orElse(null);
         return result != null ? result : Objects.requireNonNull(this.defaultBiome, "No default biome available");
      }

      private IDhApiBiomeWrapper lookupBiomeByIdOrFallback(ResourceKey<Biome> biome, IDhApiBiomeWrapper fallback) {
         IDhApiBiomeWrapper result = this.lookupBiomeById(biome);
         return result != null ? result : fallback;
      }

      private IDhApiBiomeWrapper lookupBiomeById(ResourceKey<Biome> biome) {
         try {
            return Delayed.wrapperFactory.getBiomeWrapper(biome.location().toString(), this.levelWrapper);
         } catch (IOException var3) {
            TellusLodGenerator.LOGGER.warn("Could not find biome with id {}, will not use for LODs", biome.location());
            return null;
         }
      }
   }
}
