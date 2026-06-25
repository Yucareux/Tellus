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
import com.yucareux.tellus.world.data.elevation.TellusElevationSource;
import com.yucareux.tellus.world.data.osm.BridgeSupportLayout;
import com.yucareux.tellus.world.data.osm.OsmBuildingFeature;
import com.yucareux.tellus.world.data.osm.OsmPerf;
import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.world.data.osm.RoadClass;
import com.yucareux.tellus.world.data.osm.RoadFeature;
import com.yucareux.tellus.world.data.osm.RoadMode;
import com.yucareux.tellus.world.realtime.TellusRealtimeState;
import com.yucareux.tellus.worldgen.DhLodWaterResolver;
import com.yucareux.tellus.worldgen.EarthBiomeSource;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.EarthProjection;
import com.yucareux.tellus.worldgen.WaterSurfaceResolver;
import com.yucareux.tellus.worldgen.building.BuildingBlueprint;
import com.yucareux.tellus.worldgen.building.BuildingProfile;
import com.yucareux.tellus.worldgen.building.BuildingPlacementSupport;
import com.yucareux.tellus.worldgen.building.TellusBuildingBlueprints;
import com.yucareux.tellus.worldgen.building.TellusBuildingLighting;
import com.yucareux.tellus.worldgen.building.TellusBuildingMaterials;
import com.yucareux.tellus.worldgen.building.TellusBuildingProfiles;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

public final class TellusLodGenerator implements IDhApiWorldGenerator {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final boolean LOD_TIMING_LOGGING = Boolean.parseBoolean(System.getProperty("tellus.dhLodTiming", "false"));
   private static final long PREFETCH_DEDUP_WINDOW_MS = 3000L;
   private static final long PREFETCH_STALE_WINDOW_MS = 15000L;
   private static final int PREFETCH_DEDUP_MAX = 4096;
   private static final int FAST_RENDER_ULTRA_FAST_MIN_DETAIL = intProperty("tellus.dhFastRenderUltraFastMinDetail", 4, 0, 24);
   private static final int FAST_RENDER_SKIP_SHORELINE_MIN_DETAIL = intProperty("tellus.dhFastRenderSkipShorelineMinDetail", 3, 0, 24);
   private static final double ESA_WORLD_COVER_RESOLUTION_METERS = 10.0;
   private static final int ESA_NO_DATA = 0;
   private static final int ESA_BUILT_UP = 50;
   private static final int ESA_SNOW_ICE = 70;
   private static final int ESA_WATER = 80;
   private static final int ESA_MANGROVES = 95;
   private static final double ROAD_LIGHT_BASE_SPACING_METERS = 40.0;
   private static final int ROAD_LIGHT_BLOCK_LIGHT = 15;
   private static final TellusLodGenerator.CanopyProfile TREE_COVER_FALLBACK_CANOPY_PROFILE = new TellusLodGenerator.CanopyProfile(
      false, false, false, false, false, false, false, false, false, false, false, true, false, false, false, false, false, false, false, 70, 3, 2, 3, 10
   );
   private static final Map<Holder<Biome>, TellusLodGenerator.CanopyProfile> CANOPY_PROFILES = new ConcurrentHashMap<>();
   private final EarthChunkGenerator generator;
   private final EarthBiomeSource biomeSource;
   private final DhLodWaterResolver dhWaterResolver;
   private final ThreadLocal<TellusLodGenerator.WrapperCache> wrapperCache;
   private final ConcurrentHashMap<Object, Long> recentPrefetches = new ConcurrentHashMap<>();
   private final AtomicInteger prefetchCleanupCounter = new AtomicInteger();

   public TellusLodGenerator(IDhApiLevelWrapper levelWrapper, EarthChunkGenerator generator) {
      this.generator = generator;
      this.biomeSource = (EarthBiomeSource)generator.getBiomeSource();
      this.dhWaterResolver = new DhLodWaterResolver(generator);
      this.wrapperCache = ThreadLocal.withInitial(() -> new TellusLodGenerator.WrapperCache(levelWrapper));
   }

   public void preGeneratorTaskStart() {
   }

   public byte getLargestDataDetailLevel() {
      return 24;
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
      return CompletableFuture.runAsync(() -> {
         boolean handledCancellation = false;
         TellusLodGenerator.LodTimingTrace timingTrace = new TellusLodGenerator.LodTimingTrace(
            chunkPosMinX,
            chunkPosMinZ,
            detailLevel,
            pooledFullDataSource.getWidthInDataColumns(),
            1 << detailLevel,
            generatorMode,
            this.generator.settings().distantHorizonsRenderMode()
         );

         try {
            timingTrace.addPhase("prefetch", 0L);
            long prefetchStart = beginTimingPhase(timingTrace);
            this.prefetchLodResources(chunkPosMinX, chunkPosMinZ, detailLevel, pooledFullDataSource.getWidthInDataColumns());
            endTimingPhase(timingTrace, "prefetch", prefetchStart);
            this.buildLod(pooledFullDataSource, chunkPosMinX, chunkPosMinZ, detailLevel, generatorMode, timingTrace);
            timingTrace.logSuccess();
         } catch (Throwable throwable) {
            handledCancellation = isInterruptedLodGeneration(throwable);
            this.resetLodOutput(pooledFullDataSource);
            if (handledCancellation) {
               timingTrace.logCancelled();
            } else {
               timingTrace.logFailure(throwable);
            }
         }

         resultConsumer.accept(pooledFullDataSource);
         if (handledCancellation) {
            Thread.interrupted();
         }
      }, worldGeneratorThreadPool);
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

   private void resetLodOutput(IDhApiFullDataSource output) {
      int width = output.getWidthInDataColumns();
      List<DhApiTerrainDataPoint> emptyColumn = List.of();

      for (int z = 0; z < width; z++) {
         for (int x = 0; x < width; x++) {
            output.setApiDataPointColumn(x, z, emptyColumn);
         }
      }
   }

   private static void throwIfLodCancelled() {
      if (Thread.currentThread().isInterrupted()) {
         throw new CancellationException("DH LOD generation interrupted");
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

         for (int i = 0; i < lodSizePoints; i++) {
            worldXs[i] = baseX + i * cellSize + cellOffset;
            worldZs[i] = baseZ + i * cellSize + cellOffset;
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
         trace.addPhase("waterProbe", 0L);
         trace.addPhase("detailedWater", 0L);
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
         int maxY = minY + this.generator.getGenDepth();
         int absoluteTop = maxY - minY;
         TellusLodGenerator.WrapperCache wrappers = this.wrapperCache.get();
         IDhApiBlockStateWrapper waterBlock = wrappers.getBlockState(Blocks.WATER.defaultBlockState());
         IDhApiBlockStateWrapper roadMainBlock = wrappers.getBlockState(Blocks.GRAY_CONCRETE.defaultBlockState());
         IDhApiBlockStateWrapper roadNormalBlock = wrappers.getBlockState(Blocks.CYAN_TERRACOTTA.defaultBlockState());
         IDhApiBlockStateWrapper roadDirtBlock = wrappers.getBlockState(Blocks.DIRT_PATH.defaultBlockState());
         IDhApiBlockStateWrapper bridgeSupportShaftBlock = wrappers.getBlockState(Blocks.QUARTZ_PILLAR.defaultBlockState());
         IDhApiBlockStateWrapper bridgeSupportCapBlock = wrappers.getBlockState(Blocks.QUARTZ_BRICKS.defaultBlockState());
         IDhApiBlockStateWrapper roadLightBaseBlock = wrappers.getBlockState(Blocks.STONE_BRICK_WALL.defaultBlockState());
         IDhApiBlockStateWrapper roadLightFenceBlock = wrappers.getBlockState(Blocks.OAK_FENCE.defaultBlockState());
         IDhApiBlockStateWrapper roadLightGlowBlock = wrappers.getBlockState(Blocks.GLOWSTONE.defaultBlockState());
         IDhApiBlockStateWrapper roadLightCapBlock = wrappers.getBlockState(Blocks.SPRUCE_TRAPDOOR.defaultBlockState());
         List<DhApiTerrainDataPoint> columnDataPoints = new ArrayList<>(12);
         int coverStride = coverSampleStride(detailLevel, lodSizePoints);
         boolean allowWaterVegetation = detailLevel <= 4;
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
         boolean useSharedTerrainCache = detailLevel == 2;
         EarthChunkGenerator.LodSharedTerrainCache sharedTerrainCache = null;
         EarthChunkGenerator.LodShorelineCache shorelineCache = null;
         EarthChunkGenerator.LodMountainTransitionCache mountainTransitionCache = null;
         boolean suppressCoarseShoreline = this.shouldSuppressCoarseShoreline(detail);
         trace.note("shorelineMode", suppressCoarseShoreline ? "suppressed" : "full");
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
               int coverClass = reuseSharedPreviewCoverSamples
                  ? sharedTerrainCache.sampleCoverClass(sampleWorldX, sampleWorldZ)
                  : this.generator.sampleCoverClass(sampleWorldX, sampleWorldZ, previewResolutionMeters);
               int visualCoverClass = sampleVisualCover && !isHardRawCoverClass(coverClass)
                  ? reuseSharedPreviewCoverSamples
                     ? sharedTerrainCache.sampleVisualCoverClass(sampleWorldX, sampleWorldZ)
                     : this.generator.sampleVisualCoverClass(sampleWorldX, sampleWorldZ, coverClass, previewResolutionMeters)
                  : coverClass;

               for (int dz = 0; dz < coverStride; dz++) {
                  int localZ = baseLocalZ + dz;
                  if (localZ < lodSizePoints) {
                     int worldZ = worldZs[localZ];

                     for (int dx = 0; dx < coverStride; dx++) {
                        int localX = baseLocalX + dx;
                        if (localX < lodSizePoints) {
                           int worldX = worldXs[localX];
                           int index = localZ * lodSizePoints + localX;
                           baseTerrainSurface[index] = this.generator.resolveLodTerrainSurface(worldX, worldZ, coverClass, previewResolutionMeters);
                           coverClasses[index] = coverClass;
                           visualCoverClasses[index] = visualCoverClass;
                        }
                     }
                  }
               }
            }
         }
         endTimingPhase(trace, "sample", phaseStart);
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
               int vegetationSurface = isOcean ? Mth.clamp(baseTerrainSurface[index], minY, maxY - 1) : surfaceY;
               surfaceYs[index] = surfaceY;
               vegetationSurfaceYs[index] = Mth.clamp(vegetationSurface, minY, maxY - 1);
               waterSurfaces[index] = waterSurface;
               underwaterFlags[index] = hasWater && waterSurface > surfaceY;
               Holder<Biome> biomeHolder = this.biomeSource.getBiomeAtBlock(worldX, worldZ, coverClasses[index], visualCoverClasses[index], hasWater, isOcean);
               biomeHolders[index] = biomeHolder;
               biomeWrappers[index] = wrappers.getBiome(biomeHolder);
            }
         }

         phaseStart = beginTimingPhase(trace);
         TellusLodGenerator.LodBuildingMaskResult buildingMaskResult = buildingsActive
            ? this.buildLodBuildingMask(worldXs, worldZs, surfaceYs, lodSizePoints, cellSize, osmQueryMode)
            : new TellusLodGenerator.LodBuildingMaskResult(null, null, false);
         endTimingPhase(trace, "buildingMask", phaseStart);

         TellusLodGenerator.LodBuildingColumn[] buildingColumns = buildingMaskResult.columns();
         phaseStart = beginTimingPhase(trace);
         TellusLodGenerator.LodRoadMaskResult roadMaskResult = roadsActive
            ? this.buildLodRoadClassMask(worldXs, worldZs, surfaceYs, lodSizePoints, cellSize, mainRoadsOnly, osmQueryMode, buildingColumns)
            : new TellusLodGenerator.LodRoadMaskResult(null, null, null, null, null, null, null, null, false);
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
         if (cellSize > 4) {
            phaseStart = beginTimingPhase(trace);
            for (int localZ = 0; localZ < lodSizePoints; localZ++) {
               int worldZ = worldZs[localZ];
               int row = localZ * lodSizePoints;
               for (int localX = 0; localX < lodSizePoints; localX++) {
                  int index = row + localX;
                  if (coverClasses[index] == ESA_SNOW_ICE || visualCoverClasses[index] == ESA_SNOW_ICE) {
                     long packed = this.generator.sampleLodSnowSlopeShape(worldXs[localX], worldZ);
                     lodSlopeDiffs[index] = (int)(packed >> 32);
                     lodConvexities[index] = (int)packed;
                  }
               }
            }
            endTimingPhase(trace, "snowSlopeRefine", phaseStart);
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
                           shorelineCache
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
                           shorelineCache
                        );
                     }
                  } else if (emitTimingEnabled) {
                     long generatorStart = System.nanoTime();
                     lodSurface = this.generator.resolveLodSurface(
                        biomeHolder, worldX, worldZ, surfaceY, underwater, coverClass, visualCoverClass, surfaceResolveProfiler, shorelineCache
                     );
                     generatorSurfaceResolveNanos = System.nanoTime() - generatorStart;
                  } else {
                     lodSurface = this.generator.resolveLodSurface(
                        biomeHolder, worldX, worldZ, surfaceY, underwater, coverClass, visualCoverClass, shorelineCache
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
                     roadBlock = switch (roadClassId) {
                        case 1 -> roadMainBlock;
                        case 2 -> roadNormalBlock;
                        default -> roadDirtBlock;
                     };
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

                  if (!hasRoad && !hasBuilding && !underwater && snowActive && TellusRealtimeState.shouldApplySnow(worldX, worldZ)) {
                     topBlock = wrappers.getBlockState(Blocks.SNOW_BLOCK.defaultBlockState());
                  }

                  int slopeDiff = lodSlopeDiffs[index];
                  boolean useBadlandsBands = !underwater
                     && !hasRoad
                     && !hasBuilding
                     && !hasBridgeSupport
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
                     int bandDepth = Math.min(16, surfaceY - minY + 1);
                     int bandBottomY = Math.max(minY, surfaceY - bandDepth + 1);
                     int bandBottomLayer = toLayerTop(bandBottomY, minY, absoluteTop);
                     if (bandBottomLayer > lastLayerTop) {
                        columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, bandBottomLayer, fillerBlock, biome));
                        lastLayerTop = bandBottomLayer;
                     }

                     while (lastLayerTop < topLayerBase) {
                        int segmentTop = Math.min(topLayerBase, lastLayerTop + 3);
                        int bandY = segmentTop - 1;
                        IDhApiBlockStateWrapper bandBlock = wrappers.getBlockState(this.generator.resolveBadlandsBandBlock(worldX, worldZ, bandY));
                        columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, segmentTop, bandBlock, biome));
                        lastLayerTop = segmentTop;
                     }
                  } else if (topLayerBase > lastLayerTop) {
                     columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, topLayerBase, fillerBlock, biome));
                     lastLayerTop = topLayerBase;
                  }

                  if (surfaceTop > lastLayerTop) {
                     columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, surfaceTop, topBlock, biome));
                     lastLayerTop = surfaceTop;
                  }

                  if (emitTimingEnabled) {
                     emitBaseLayersNanos += System.nanoTime() - columnPhaseStart;
                  }

                  boolean allowCanopy = !hasRoad && !hasBuilding && !hasBridgeSupport && (coverClass == 10 && !underwater || isMangrove);
                  columnPhaseStart = emitTimingEnabled ? System.nanoTime() : 0L;
                  TellusLodGenerator.CanopyColumn canopyColumn = allowCanopy ? resolveCanopyColumn(canopyProfile, worldX, worldZ, cellSize) : null;
                  if (canopyColumn != null) {
                     emitCanopyColumns++;
                  }

                  boolean deferMangroveCanopy = isMangrove && underwater;
                  if (!deferMangroveCanopy) {
                     lastLayerTop = appendCanopyColumn(canopyColumn, lastLayerTop, absoluteTop, wrappers, biome, columnDataPoints);
                  }

                  if (emitTimingEnabled) {
                     emitCanopyNanos += System.nanoTime() - columnPhaseStart;
                  }

                  columnPhaseStart = emitTimingEnabled ? System.nanoTime() : 0L;
                  if (underwater && !hasBridgeSupport) {
                     int waterTop = toLayerTop(waterSurface, minY, absoluteTop);
                     if (waterTop > lastLayerTop) {
                        int waterDepth = waterSurface - vegetationSurfaceY;
                        TellusLodGenerator.WaterVegetationColumn vegetation = allowWaterVegetation
                           ? resolveWaterVegetationColumn(canopyProfile, worldX, worldZ, waterDepth)
                           : null;
                        if (vegetation != null) {
                           int vegetationBaseTop = toLayerTop(vegetationSurfaceY, minY, absoluteTop);
                           vegetationBaseTop = Mth.clamp(vegetationBaseTop, lastLayerTop, waterTop);
                           if (vegetationBaseTop > lastLayerTop) {
                              columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, vegetationBaseTop, waterBlock, biome));
                              lastLayerTop = vegetationBaseTop;
                           }

                           int vegTop = Math.min(waterTop, lastLayerTop + vegetation.height);
                           if (vegTop > lastLayerTop) {
                              IDhApiBlockStateWrapper vegBlock = wrappers.getBlockState(vegetation.blockState);
                              columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, vegTop, vegBlock, biome));
                              lastLayerTop = vegTop;
                           }

                           if (waterTop > lastLayerTop) {
                              columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, waterTop, waterBlock, biome));
                              lastLayerTop = waterTop;
                           }
                        } else {
                           columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, waterTop, waterBlock, biome));
                           lastLayerTop = waterTop;
                        }
                     }
                  }

                  if (emitTimingEnabled) {
                     emitWaterNanos += System.nanoTime() - columnPhaseStart;
                  }

                  if (deferMangroveCanopy) {
                     columnPhaseStart = emitTimingEnabled ? System.nanoTime() : 0L;
                     lastLayerTop = appendCanopyColumn(canopyColumn, lastLayerTop, absoluteTop, wrappers, biome, columnDataPoints);
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
      int lodSizePoints = output.getWidthInDataColumns();
      int cellSize = 1 << detailLevel;
      int cellOffset = cellSize >> 1;
      int baseX = SectionPos.sectionToBlockCoord(chunkPosMinX);
      int baseZ = SectionPos.sectionToBlockCoord(chunkPosMinZ);
      int minY = this.generator.getMinY();
      int maxY = minY + this.generator.getGenDepth();
      int absoluteTop = maxY - minY;
      TellusLodGenerator.WrapperCache wrappers = this.wrapperCache.get();
      IDhApiBlockStateWrapper snowTopBlock = wrappers.getBlockState(Blocks.SNOW_BLOCK.defaultBlockState());
      IDhApiBlockStateWrapper waterBlock = wrappers.getBlockState(Blocks.WATER.defaultBlockState());
      IDhApiBlockStateWrapper roadMainBlock = wrappers.getBlockState(Blocks.GRAY_CONCRETE.defaultBlockState());
      IDhApiBlockStateWrapper roadNormalBlock = wrappers.getBlockState(Blocks.CYAN_TERRACOTTA.defaultBlockState());
      IDhApiBlockStateWrapper roadDirtBlock = wrappers.getBlockState(Blocks.DIRT_PATH.defaultBlockState());
      IDhApiBlockStateWrapper airBlock = wrappers.airBlock();
      List<DhApiTerrainDataPoint> columnDataPoints = new ArrayList<>(8);
      EarthGeneratorSettings settings = this.generator.settings();
      double previewResolutionMeters = lodPreviewResolutionMeters(settings, cellSize);
      boolean roadsActive = this.shouldRenderDhRoads(detail);
      boolean buildingsActive = this.shouldRenderDhBuildings(detail);
      boolean baseDetailedWater = settings.distantHorizonsWaterResolver()
         && detailLevel <= 5
         && settings.distantHorizonsRenderMode() != EarthGeneratorSettings.DistantHorizonsRenderMode.ULTRA_FAST;
      boolean preferNonBlockingOsm = settings.distantHorizonsOsmNonBlockingFetch();
      OsmQueryMode osmQueryMode = preferNonBlockingOsm ? OsmQueryMode.NON_BLOCKING : OsmQueryMode.BLOCKING;
      boolean mainRoadsOnly = roadsActive && detail == settings.distantHorizonsOsmRoadMaxDetail();
      TellusRealtimeState.PrecipitationMode precipitationMode = TellusRealtimeState.precipitationMode();
      boolean snowActive = TellusRealtimeState.isWeatherEnabled() && precipitationMode == TellusRealtimeState.PrecipitationMode.SNOW
         || TellusRealtimeState.isHistoricalSnowEnabled();
      trace.note("roads", roadsActive);
      trace.note("buildings", buildingsActive);
      trace.note("detailedWater", false);
      trace.note("osm", roadsActive || buildingsActive || settings.enableWater() ? osmQueryMode : "DISABLED");
      trace.addPhase("sample", 0L);
      trace.addPhase("waterProbe", 0L);
      trace.addPhase("detailedWater", 0L);
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
      boolean remaSnowEnabled = TellusElevationSource.usesPolarDem(settings.demSelection()) && settings.worldScale() > 0.0;
      double remaSnowBoundaryZ = remaSnowEnabled ? TellusElevationSource.remaBoundaryBlockZ(settings.worldScale()) : Double.POSITIVE_INFINITY;
      int area = lodSizePoints * lodSizePoints;
      int[] worldXs = new int[lodSizePoints];
      int[] worldZs = new int[lodSizePoints];

      for (int i = 0; i < lodSizePoints; i++) {
         worldXs[i] = baseX + i * cellSize + cellOffset;
         worldZs[i] = baseZ + i * cellSize + cellOffset;
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
      long phaseStart = beginTimingPhase(trace);

      for (int localZ = 0; localZ < lodSizePoints; localZ++) {
         throwIfLodCancelled();
         int worldZ = worldZs[localZ];
         boolean remaSnowTerrain = remaSnowEnabled && worldZ >= remaSnowBoundaryZ;

         for (int localX = 0; localX < lodSizePoints; localX++) {
            int index = localZ * lodSizePoints + localX;
            int worldX = worldXs[localX];
            int coverClass = this.generator.sampleCoverClass(worldX, worldZ, previewResolutionMeters);
            int visualCoverClass = sampleVisualCover && !isHardRawCoverClass(coverClass)
               ? this.generator.sampleVisualCoverClass(worldX, worldZ, coverClass, previewResolutionMeters)
               : coverClass;
            baseTerrainSurface[index] = this.generator.resolveLodTerrainSurface(worldX, worldZ, coverClass, previewResolutionMeters);
            coverClasses[index] = coverClass;
            visualCoverClasses[index] = visualCoverClass;
            remaSnowTerrainFlags[index] = remaSnowTerrain;
         }
      }
      endTimingPhase(trace, "sample", phaseStart);
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
            surfaceYs[index] = surfaceY;
            waterSurfaces[index] = waterSurface;
            underwaterFlags[index] = hasWater && waterSurface > surfaceY;
            Holder<Biome> biomeHolder = this.biomeSource.getBiomeAtBlock(worldX, worldZ, coverClasses[index], visualCoverClasses[index], hasWater, isOcean);
            biomeHolders[index] = biomeHolder;
            biomeWrappers[index] = wrappers.getBiome(biomeHolder);
         }
      }

      phaseStart = beginTimingPhase(trace);
      TellusLodGenerator.LodBuildingMaskResult buildingMaskResult = buildingsActive
         ? this.buildLodBuildingMask(worldXs, worldZs, surfaceYs, lodSizePoints, cellSize, osmQueryMode)
         : new TellusLodGenerator.LodBuildingMaskResult(null, null, false);
      endTimingPhase(trace, "buildingMask", phaseStart);
      TellusLodGenerator.LodBuildingColumn[] buildingColumns = buildingMaskResult.columns();
      int[] buildingFlattenedSurface = buildingMaskResult.flattenedSurface();
      phaseStart = beginTimingPhase(trace);
      TellusLodGenerator.LodRoadMaskResult roadMaskResult = roadsActive
         ? this.buildUltraFastRoadMask(worldXs, worldZs, lodSizePoints, cellSize, mainRoadsOnly, osmQueryMode)
         : new TellusLodGenerator.LodRoadMaskResult(null, null, null, null, null, null, null, null, false);
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
      if (cellSize > 4) {
         phaseStart = beginTimingPhase(trace);
         for (int localZ = 0; localZ < lodSizePoints; localZ++) {
            int worldZ = worldZs[localZ];
            int row = localZ * lodSizePoints;
            for (int localX = 0; localX < lodSizePoints; localX++) {
               int index = row + localX;
               if (coverClasses[index] == ESA_SNOW_ICE || visualCoverClasses[index] == ESA_SNOW_ICE) {
                  long packed = this.generator.sampleLodSnowSlopeShape(worldXs[localX], worldZ);
                  lodSlopeDiffs[index] = (int)(packed >> 32);
                  lodConvexities[index] = (int)packed;
               }
            }
         }
         endTimingPhase(trace, "snowSlopeRefine", phaseStart);
      }
      byte[] roadClassMask = roadMaskResult.mask();
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

      for (int localZ = 0; localZ < lodSizePoints; localZ++) {
         throwIfLodCancelled();

         for (int localX = 0; localX < lodSizePoints; localX++) {
            int index = localZ * lodSizePoints + localX;
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
               remaSnowTerrainFlags[index]
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
               topBlock = switch (roadClassId) {
                  case 1 -> roadMainBlock;
                  case 2 -> roadNormalBlock;
                  default -> roadDirtBlock;
               };
               if (underwater) {
                  surfaceY = Math.max(surfaceY, waterSurface);
                  underwater = false;
               }
            } else if (!hasBuilding && !underwater && snowActive && TellusRealtimeState.shouldApplySnow(worldX, worldZ)) {
               topBlock = snowTopBlock;
            }

            if (emitTimingEnabled) {
               emitClassifyNanos += System.nanoTime() - columnPhaseStart;
            }

            emitColumns++;
            if (hasBuilding) {
               emitBuildingColumns++;
            }

            if (hasRoad) {
               emitRoadColumns++;
            }

            if (underwater) {
               emitUnderwaterColumns++;
            }

            int lastLayerTop = 0;
            int surfaceTop = toLayerTop(surfaceY, minY, absoluteTop);
            int topLayerBase = Math.max(0, surfaceTop - 1);
            columnPhaseStart = emitTimingEnabled ? System.nanoTime() : 0L;
            if (topLayerBase > lastLayerTop) {
               columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, topLayerBase, fillerBlock, biome));
               lastLayerTop = topLayerBase;
            }

            if (surfaceTop > lastLayerTop) {
               columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, surfaceTop, topBlock, biome));
               lastLayerTop = surfaceTop;
            }

            if (underwater) {
               int waterTop = toLayerTop(waterSurface, minY, absoluteTop);
               if (waterTop > lastLayerTop) {
                  columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, waterTop, waterBlock, biome));
                  lastLayerTop = waterTop;
               }
            }

            if (emitTimingEnabled) {
               emitBaseLayersNanos += System.nanoTime() - columnPhaseStart;
            }

            boolean allowCanopy = !hasRoad && !hasBuilding && (coverClass == 10 && !underwater || isMangrove);
            columnPhaseStart = emitTimingEnabled ? System.nanoTime() : 0L;
            TellusLodGenerator.CanopyColumn canopyColumn = allowCanopy ? resolveCanopyColumn(sampledCanopyProfile, worldX, worldZ, cellSize) : null;
            boolean deferMangroveCanopy = isMangrove && underwater;
            if (!deferMangroveCanopy) {
               lastLayerTop = appendCanopyColumn(canopyColumn, lastLayerTop, absoluteTop, wrappers, biome, columnDataPoints);
            }

            if (canopyColumn != null) {
               emitCanopyColumns++;
            }

            if (emitTimingEnabled) {
               emitCanopyNanos += System.nanoTime() - columnPhaseStart;
            }

            if (lastLayerTop < absoluteTop) {
               if (deferMangroveCanopy) {
                  columnPhaseStart = emitTimingEnabled ? System.nanoTime() : 0L;
                  lastLayerTop = appendCanopyColumn(canopyColumn, lastLayerTop, absoluteTop, wrappers, biome, columnDataPoints);
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

               columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, absoluteTop, airBlock, biome));
            }

            int columnPointCount = columnDataPoints.size();
            emitPoints += columnPointCount;
            emitMaxPoints = Math.max(emitMaxPoints, columnPointCount);
            if (columnPointCount > 8) {
               emitColumnsOverInitialCapacity++;
            }

            columnPhaseStart = emitTimingEnabled ? System.nanoTime() : 0L;
            output.setApiDataPointColumn(localX, localZ, columnDataPoints);
            columnDataPoints.clear();
            if (emitTimingEnabled) {
               emitOutputNanos += System.nanoTime() - columnPhaseStart;
            }
         }
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

   private static int roadLightSpacingBlocks(double worldScale) {
      if (!(worldScale > 0.0)) {
         return 40;
      } else {
         return Mth.clamp((int)Math.round(ROAD_LIGHT_BASE_SPACING_METERS / worldScale), 3, 40);
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

   private void prefetchLodResources(int chunkPosMinX, int chunkPosMinZ, byte detailLevel, int lodSizePoints) {
      if (lodSizePoints > 0) {
         int detail = Byte.toUnsignedInt(detailLevel);
         boolean roadsActive = this.shouldRenderDhRoads(detail);
         boolean buildingsActive = this.shouldRenderDhBuildings(detail);
         EarthGeneratorSettings settings = this.generator.settings();
         int cellSize = 1 << detailLevel;
         int cellOffset = cellSize >> 1;
         double previewResolutionMeters = lodPreviewResolutionMeters(settings, cellSize);
         int baseX = SectionPos.sectionToBlockCoord(chunkPosMinX);
         int baseZ = SectionPos.sectionToBlockCoord(chunkPosMinZ);
         int minBlockX = baseX + cellOffset;
         int minBlockZ = baseZ + cellOffset;
         int maxBlockX = baseX + (lodSizePoints - 1) * cellSize + cellOffset;
         int maxBlockZ = baseZ + (lodSizePoints - 1) * cellSize + cellOffset;
         if (this.useUltraFastLodMode(detail)) {
            if (roadsActive || buildingsActive) {
               int grid = Math.min(3, Math.max(2, lodSizePoints / 16));
               if (grid <= 1) {
                  grid = 2;
               }

               for (int gz = 0; gz < grid; gz++) {
                  int worldZ = lerpBlock(minBlockZ, maxBlockZ, gz, grid);

                  for (int gx = 0; gx < grid; gx++) {
                     int worldX = lerpBlock(minBlockX, maxBlockX, gx, grid);
                     this.prefetchAtBlock(worldX, worldZ, roadsActive, buildingsActive, false, previewResolutionMeters);
                  }
               }
            } else {
               int center = Math.max(0, lodSizePoints / 2);
               int centerX = baseX + center * cellSize + cellOffset;
               int centerZ = baseZ + center * cellSize + cellOffset;
               this.prefetchAtBlock(centerX, centerZ, false, false, false, previewResolutionMeters);
            }
         } else {
            int grid = Math.min(5, Math.max(2, lodSizePoints / 8));
            if (grid <= 1) {
               grid = 2;
            }

            for (int gz = 0; gz < grid; gz++) {
               int worldZ = lerpBlock(minBlockZ, maxBlockZ, gz, grid);

               for (int gx = 0; gx < grid; gx++) {
                  int worldX = lerpBlock(minBlockX, maxBlockX, gx, grid);
                  this.prefetchAtBlock(worldX, worldZ, roadsActive, buildingsActive, false, previewResolutionMeters);
               }
            }
         }
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
      double worldScale = settings.worldScale();
      return worldScale > 0.0
         && worldScale < ESA_WORLD_COVER_RESOLUTION_METERS
         && effectiveCoverResolutionMeters(worldScale, previewResolutionMeters) < ESA_WORLD_COVER_RESOLUTION_METERS;
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
         boolean hadCacheMisses = roadQuery.hadCacheMisses();
         if (roads.isEmpty()) {
            OsmPerf.recordDhRoadMaskBuild(OsmPerf.elapsedSince(buildStartNs), 0);
            return new TellusLodGenerator.LodRoadMaskResult(null, null, null, null, null, null, null, null, hadCacheMisses);
         } else {
            List<RoadFeature> mainRoads = new ArrayList<>();
            List<RoadFeature> normalRoads = mainRoadsOnly ? List.<RoadFeature>of() : new ArrayList<>();
            List<RoadFeature> dirtRoads = mainRoadsOnly ? List.<RoadFeature>of() : new ArrayList<>();

            for (RoadFeature road : roads) {
               throwIfLodCancelled();
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

            double blocksPerDegree = EarthProjection.blocksPerDegree(settings.worldScale());
            int mainRoadWidth = roadWidthForScale(RoadClass.MAIN.baseWidth(), settings.worldScale());
            int normalRoadWidth = roadWidthForScale(RoadClass.NORMAL.baseWidth(), settings.worldScale());
            int dirtRoadWidth = roadWidthForScale(RoadClass.DIRT.baseWidth(), settings.worldScale());
            byte[] selectedClass = new byte[lodSizePoints * lodSizePoints];
            rasterizeLodRoadClass(mainRoads, (byte)1, mainRoadWidth, blocksPerDegree, worldXs, worldZs, lodSizePoints, cellSize, selectedClass);
            if (!mainRoadsOnly) {
               rasterizeLodRoadClass(normalRoads, (byte)2, normalRoadWidth, blocksPerDegree, worldXs, worldZs, lodSizePoints, cellSize, selectedClass);
               rasterizeLodRoadClass(dirtRoads, (byte)3, dirtRoadWidth, blocksPerDegree, worldXs, worldZs, lodSizePoints, cellSize, selectedClass);
            }

            boolean hasRoadCoverage = false;
            for (byte classId : selectedClass) {
               if (classId > 0) {
                  hasRoadCoverage = true;
                  break;
               }
            }

            OsmPerf.recordDhRoadMaskBuild(OsmPerf.elapsedSince(buildStartNs), roads.size());
            return !hasRoadCoverage ? new TellusLodGenerator.LodRoadMaskResult(null, null, null, null, null, null, null, null, hadCacheMisses)
               : new TellusLodGenerator.LodRoadMaskResult(selectedClass, null, null, null, null, null, null, null, hadCacheMisses);
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

            double blocksPerDegree = EarthProjection.blocksPerDegree(settings.worldScale());
            int mainRoadWidth = roadWidthForScale(RoadClass.MAIN.baseWidth(), settings.worldScale());
            int normalRoadWidth = roadWidthForScale(RoadClass.NORMAL.baseWidth(), settings.worldScale());
            int dirtRoadWidth = roadWidthForScale(RoadClass.DIRT.baseWidth(), settings.worldScale());
            byte[] selectedClass = new byte[lodSizePoints * lodSizePoints];
            rasterizeLodRoadClass(mainRoads, (byte)1, mainRoadWidth, blocksPerDegree, worldXs, worldZs, lodSizePoints, cellSize, selectedClass);
            if (!mainRoadsOnly) {
               rasterizeLodRoadClass(normalRoads, (byte)2, normalRoadWidth, blocksPerDegree, worldXs, worldZs, lodSizePoints, cellSize, selectedClass);
               rasterizeLodRoadClass(dirtRoads, (byte)3, dirtRoadWidth, blocksPerDegree, worldXs, worldZs, lodSizePoints, cellSize, selectedClass);
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
                  hasRoadLights = this.rasterizeLodRoadLights(
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
      int[] worldXs, int[] worldZs, int[] terrainSurfaces, int lodSizePoints, int cellSize, OsmQueryMode fetchMode
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

   private static TellusLodGenerator.LodRasterizedBuildingFeature rasterizeLodBuildingFeature(
      OsmBuildingFeature feature, String groupId, int[] worldXs, int[] worldZs, int lodSizePoints, int cellSize, double worldScale
   ) {
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      double minWorldX = Math.min(worldXs[0], worldXs[lodSizePoints - 1]);
      double maxWorldX = Math.max(worldXs[0], worldXs[lodSizePoints - 1]);
      double minWorldZ = Math.min(worldZs[0], worldZs[lodSizePoints - 1]);
      double maxWorldZ = Math.max(worldZs[0], worldZs[lodSizePoints - 1]);
      double featureMinX = feature.minBlockX(blocksPerDegree);
      double featureMaxX = feature.maxBlockX(blocksPerDegree);
      double featureMinZ = feature.minBlockZ(worldScale);
      double featureMaxZ = feature.maxBlockZ(worldScale);
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
               if (feature.containsWorld(worldX + 0.5, worldZ + 0.5, worldScale)) {
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

   private static void addLodBuildingFeatureCoverage(
      TellusLodGenerator.LodRasterizedBuildingFeature rasterized,
      int baseY,
      int[] overlyingPartFloorY,
      TellusLodGenerator.LodBuildingColumn[] columns,
      int[] flattenedSurface,
      int[] recordedPartFloorY,
      int[] worldXs,
      int[] worldZs,
      int cellSize,
      double worldScale
   ) {
      if (baseY != Integer.MIN_VALUE) {
         int minHeightBlocks = buildingMinHeightBlocks(rasterized.feature().minHeightMeters(), worldScale);
         int floorY = baseY + minHeightBlocks + 1;
         BuildingProfile profile = TellusBuildingProfiles.resolveProfile(rasterized.feature(), worldScale, null, false);
         int roofBaseY = Math.max(baseY + buildingHeightBlocks(rasterized.feature().heightMeters(), worldScale), floorY + profile.floorCount() * profile.storeyHeightBlocks());
         int topY = roofBaseY + Math.max(profile.parapetHeight(), profile.roofRise());
         BuildingBlueprint blueprint = TellusBuildingBlueprints.create(rasterized.groupId(), rasterized.feature(), profile, 0L, baseY, floorY, roofBaseY, topY, List.of(), worldScale);
         TellusBuildingMaterials.BuildingMaterialPalette palette = TellusBuildingMaterials.resolvePalette(blueprint);
         int[] boundaryDistance = computeLodBoundaryDistance(rasterized);
         boolean groundContact = rasterized.feature().kind() != com.yucareux.tellus.world.data.osm.OsmBuildingKind.PART || minHeightBlocks <= 0;

         for (int order = 0; order < rasterized.occupiedCells().length; order++) {
            int cellIndex = rasterized.occupiedCells()[order];
            int localX = cellIndex % worldXs.length;
            int localZ = cellIndex / worldXs.length;
            int distance = boundaryDistance[order];
            int cellTopY = blueprint.roofTopY(worldXs[localX], worldZs[localZ], distance);
            if (profile.roofProfile() == BuildingProfile.RoofProfile.FLAT
               || profile.roofProfile() == BuildingProfile.RoofProfile.FLAT_CROWN
               || profile.roofProfile() == BuildingProfile.RoofProfile.FLAT_SKYLIGHT) {
               cellTopY += profile.parapetHeight();
            }

            if (overlyingPartFloorY != null) {
               cellTopY = BuildingPlacementSupport.capLowerColumnTopY(floorY, cellTopY, overlyingPartFloorY[cellIndex]);
            }

            if (cellTopY < floorY) {
               continue;
            }

            TellusLodGenerator.LodBuildingColumn column = columns[cellIndex];
            if (column == null) {
               column = new TellusLodGenerator.LodBuildingColumn();
               columns[cellIndex] = column;
            }

            int highestFloor = blueprint.highestActiveFloor(distance);
            int roofStart = Math.max(floorY, blueprint.roofBaseY(distance));
            int facadeEnd = Math.min(cellTopY, roofStart - 1);
            if (facadeEnd >= floorY) {
               int facadeFloor = Math.min(highestFloor, blueprint.floorIndexAtY((floorY + facadeEnd) >> 1));
               BlockState facadeBlock = TellusBuildingMaterials.resolveLodFacadeBlock(blueprint, palette, distance, facadeFloor);
               column.addSpan(
                  floorY,
                  facadeEnd,
                  facadeBlock,
                  TellusBuildingLighting.resolveLodFacadeLightLevel(
                     blueprint, facadeBlock, palette.window(), distance, worldXs[localX], worldZs[localZ], facadeFloor, cellSize
                  )
               );
            }

            if (cellTopY >= roofStart) {
               column.addSpan(
                  roofStart,
                  cellTopY,
                  TellusBuildingMaterials.resolveLodRoofBlock(palette, blueprint.isFacadeCell(distance, highestFloor)),
                  (byte)0
               );
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

   private static void rasterizeLodRoadClass(
      List<RoadFeature> roads,
      byte classId,
      int widthBlocks,
      double blocksPerDegree,
      int[] worldXs,
      int[] worldZs,
      int lodSizePoints,
      int cellSize,
      byte[] selectedClass
   ) {
      if (!roads.isEmpty() && widthBlocks > 0 && lodSizePoints > 0 && cellSize > 0) {
         double worldScale = EarthProjection.worldScaleFromBlocksPerDegree(blocksPerDegree);
         double minWorldX = Math.min(worldXs[0], worldXs[lodSizePoints - 1]);
         double minWorldZ = Math.min(worldZs[0], worldZs[lodSizePoints - 1]);
         double maxWorldX = Math.max(worldXs[0], worldXs[lodSizePoints - 1]);
         double maxWorldZ = Math.max(worldZs[0], worldZs[lodSizePoints - 1]);
         double halfWidth = Math.max(0.5, (widthBlocks - 1) * 0.5) + cellSize * 0.5;
         double radiusSq = halfWidth * halfWidth + 1.0E-6;

         for (RoadFeature road : roads) {
            int points = road.pointCount();
            if (points >= 2) {
               double roadMinX = road.minLon() * blocksPerDegree;
               double roadMaxX = road.maxLon() * blocksPerDegree;
               double roadMinZ = EarthProjection.latToBlockZ(road.maxLat(), worldScale);
               double roadMaxZ = EarthProjection.latToBlockZ(road.minLat(), worldScale);
               if (!(roadMaxX < minWorldX - halfWidth)
                  && !(roadMinX > maxWorldX + halfWidth)
                  && !(roadMaxZ < minWorldZ - halfWidth)
                  && !(roadMinZ > maxWorldZ + halfWidth)) {
                  double x1 = road.lonAt(0) * blocksPerDegree;
                  double z1 = EarthProjection.latToBlockZ(road.latAt(0), worldScale);

                  for (int i = 1; i < points; i++) {
                     double x2 = road.lonAt(i) * blocksPerDegree;
                     double z2 = EarthProjection.latToBlockZ(road.latAt(i), worldScale);
                     double dx = x2 - x1;
                     double dz = z2 - z1;
                     double lenSq = dx * dx + dz * dz;
                     if (lenSq <= 1.0E-6) {
                        x1 = x2;
                        z1 = z2;
                     } else {
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
                                 if (ddx * ddx + ddz * ddz <= radiusSq) {
                                    selectedClass[index] = classId;
                                 }
                              }
                           }
                        }

                        x1 = x2;
                        z1 = z2;
                     }
                  }
               }
            }
         }
      }
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
      if (roads.isEmpty() || roadWidth <= 0 || lodSizePoints <= 0 || cellSize <= 0) {
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
            if (road.mode() == RoadMode.TUNNEL) {
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
               roadWorldXs[i] = road.lonAt(i) * blocksPerDegree;
               roadWorldZs[i] = EarthProjection.latToBlockZ(road.latAt(i), worldScale);
            }

            double totalLength = 0.0;
            for (int i = 0; i < segmentCount; i++) {
               double dx = roadWorldXs[i + 1] - roadWorldXs[i];
               double dz = roadWorldZs[i + 1] - roadWorldZs[i];
               segmentStarts[i] = totalLength;
               segmentLengths[i] = Math.sqrt(dx * dx + dz * dz);
               totalLength += segmentLengths[i];
            }

            double endpointInset = Math.max(Math.max(4.0, roadWidth), spacingBlocks * 0.75);
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
                  roadWidth,
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
                  if (!(lateral <= 0.05)) {
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
      if (span < Math.max(0.75, cellSize * 0.35)) {
         return -1;
      }

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
         double halfWidth = Math.max(0.5, (widthBlocks - 1) * 0.5) + cellSize * 0.5;
         double radiusSq = halfWidth * halfWidth + 1.0E-6;
         boolean hasBridgeDeck = false;

         for (RoadFeature road : roads) {
            int points = road.pointCount();
            if (points >= 2) {
               double roadMinX = road.minLon() * blocksPerDegree;
               double roadMaxX = road.maxLon() * blocksPerDegree;
               double roadMinZ = EarthProjection.latToBlockZ(road.maxLat(), worldScale);
               double roadMaxZ = EarthProjection.latToBlockZ(road.minLat(), worldScale);
               if (!(roadMaxX < minWorldX - halfWidth)
                  && !(roadMinX > maxWorldX + halfWidth)
                  && !(roadMaxZ < minWorldZ - halfWidth)
                  && !(roadMinZ > maxWorldZ + halfWidth)) {
                  double startWorldX = road.lonAt(0) * blocksPerDegree;
                  double startWorldZ = EarthProjection.latToBlockZ(road.latAt(0), worldScale);
                  double previousX = startWorldX;
                  double previousZ = startWorldZ;
                  double endWorldX = startWorldX;
                  double endWorldZ = startWorldZ;
                  double totalLength = 0.0;

                  for (int i = 1; i < points; i++) {
                     double currentX = road.lonAt(i) * blocksPerDegree;
                     double currentZ = EarthProjection.latToBlockZ(road.latAt(i), worldScale);
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
                        double x2 = road.lonAt(i) * blocksPerDegree;
                        double z2 = EarthProjection.latToBlockZ(road.latAt(i), worldScale);
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
         int points = road.pointCount();
         if (points < 2) {
            continue;
         }

         BridgeSupportLayout.SupportStyle style = BridgeSupportLayout.styleFor(road.roadClass(), roadWidth);
         double radius = style.maxFootprintRadius() + cellSize * 0.5;
         double roadMinX = road.minLon() * blocksPerDegree;
         double roadMaxX = road.maxLon() * blocksPerDegree;
         double roadMinZ = EarthProjection.latToBlockZ(road.maxLat(), worldScale);
         double roadMaxZ = EarthProjection.latToBlockZ(road.minLat(), worldScale);
         if (roadMaxX < minWorldX - radius
            || roadMinX > maxWorldX + radius
            || roadMaxZ < minWorldZ - radius
            || roadMinZ > maxWorldZ + radius) {
            continue;
         }

         double startWorldX = road.lonAt(0) * blocksPerDegree;
         double startWorldZ = EarthProjection.latToBlockZ(road.latAt(0), worldScale);
         double endWorldX = road.lonAt(points - 1) * blocksPerDegree;
         double endWorldZ = EarthProjection.latToBlockZ(road.latAt(points - 1), worldScale);
         int startSurface = this.sampleRoadSurfaceForLodBridge(Mth.floor(startWorldX), Mth.floor(startWorldZ), roadSurfaceCache);
         int endSurface = this.sampleRoadSurfaceForLodBridge(Mth.floor(endWorldX), Mth.floor(endWorldZ), roadSurfaceCache);

         BridgeSupportLayout.forEachSupport(road, blocksPerDegree, worldScale, roadWidth, placement -> {
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
      return Math.max(baseline + rise, localRoadSurface + clearance);
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

   private void prefetchAtBlock(
      int blockX,
      int blockZ,
      boolean includeRoadsPrefetch,
      boolean includeBuildingsPrefetch,
      boolean includeDetailedWaterPrefetch,
      double previewResolutionMeters
   ) {
      int chunkX = SectionPos.blockToSectionCoord(blockX);
      int chunkZ = SectionPos.blockToSectionCoord(blockZ);
      if (this.shouldRunPrefetch(
         new TellusLodGenerator.ChunkPrefetchKey(
            chunkX, chunkZ, includeRoadsPrefetch, includeBuildingsPrefetch, includeDetailedWaterPrefetch, previewResolutionMeters
         )
      )) {
         this.generator.prefetchForChunk(
            chunkX, chunkZ, includeRoadsPrefetch, includeDetailedWaterPrefetch, includeBuildingsPrefetch, previewResolutionMeters, false
         );
      }
   }

   private boolean shouldRunPrefetch(Object key) {
      long now = System.currentTimeMillis();
      boolean[] scheduled = new boolean[]{false};
      this.recentPrefetches.compute(key, (ignored, last) -> {
         if (last == null || now - last >= PREFETCH_DEDUP_WINDOW_MS) {
            scheduled[0] = true;
            return now;
         } else {
            return last;
         }
      });
      if (scheduled[0]) {
         this.cleanupRecentPrefetches(now);
      }

      return scheduled[0];
   }

   private void cleanupRecentPrefetches(long now) {
      if (this.recentPrefetches.size() > PREFETCH_DEDUP_MAX || (this.prefetchCleanupCounter.incrementAndGet() & 255) == 0) {
         for (Map.Entry<Object, Long> entry : this.recentPrefetches.entrySet()) {
            if (now - entry.getValue() >= PREFETCH_STALE_WINDOW_MS) {
               this.recentPrefetches.remove(entry.getKey(), entry.getValue());
            }
         }
      }
   }

   private static int lerpBlock(int min, int max, int index, int count) {
      if (count <= 1) {
         return min;
      } else {
         double t = (double)index / (count - 1);
         return (int)Math.round(min + (max - min) * t);
      }
   }

   private static TellusLodGenerator.CanopyProfile canopyProfile(Holder<Biome> biome) {
      return CANOPY_PROFILES.computeIfAbsent(biome, TellusLodGenerator::buildCanopyProfile);
   }

   private static TellusLodGenerator.CanopyProfile resolveTreeCoverCanopyProfile(TellusLodGenerator.CanopyProfile biomeProfile, int coverClass) {
      return coverClass == 10 && !biomeProfile.isMangrove() && biomeProfile.canopyBaseChance() <= 0 ? TREE_COVER_FALLBACK_CANOPY_PROFILE : biomeProfile;
   }

   private static TellusLodGenerator.CanopyProfile buildCanopyProfile(Holder<Biome> biome) {
      boolean isMangrove = biome.is(Biomes.MANGROVE_SWAMP);
      boolean isDarkForest = biome.is(Biomes.DARK_FOREST);
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
      int canopyBaseChance;
      if (isMangrove) {
         canopyBaseChance = 85;
      } else if (isDarkForest) {
         canopyBaseChance = 80;
      } else if (isBambooJungle) {
         canopyBaseChance = 75;
      } else if (isSparseJungle) {
         canopyBaseChance = 50;
      } else if (isWindsweptForest) {
         canopyBaseChance = 45;
      } else if (isWoodedBadlands) {
         canopyBaseChance = 40;
      } else if (isWindsweptSavanna) {
         canopyBaseChance = 35;
      } else if (isSavannaPlateau) {
         canopyBaseChance = 45;
      } else if (isJungle) {
         canopyBaseChance = 75;
      } else if (isForest) {
         canopyBaseChance = 70;
      } else if (isTaiga) {
         canopyBaseChance = 65;
      } else if (isCherryGrove) {
         canopyBaseChance = 60;
      } else if (isSwamp) {
         canopyBaseChance = 55;
      } else if (isSavanna) {
         canopyBaseChance = 50;
      } else {
         canopyBaseChance = 0;
      }

      int canopyBaseRadius;
      if (isMangrove) {
         canopyBaseRadius = 5;
      } else if (isSparseJungle) {
         canopyBaseRadius = 3;
      } else if (isBambooJungle) {
         canopyBaseRadius = 4;
      } else if (isJungle) {
         canopyBaseRadius = 5;
      } else if (isDarkForest) {
         canopyBaseRadius = 4;
      } else if (isWindsweptForest || isWoodedBadlands) {
         canopyBaseRadius = 2;
      } else if (isSavannaTree) {
         canopyBaseRadius = 3;
      } else if (!isForest && !isTaiga && !isCherryGrove && !isSwamp) {
         canopyBaseRadius = 0;
      } else {
         canopyBaseRadius = 3;
      }

      boolean isTallCanopy = isMangrove || isDarkForest || isJungle;
      int canopyBaseHeight;
      if (isMangrove) {
         canopyBaseHeight = 4;
      } else if (isJungle) {
         canopyBaseHeight = 4;
      } else if (isTallCanopy) {
         canopyBaseHeight = 3;
      } else if (isTaiga) {
         canopyBaseHeight = 3;
      } else {
         canopyBaseHeight = 2;
      }

      int canopyMaxHeight;
      if (isMangrove) {
         canopyMaxHeight = 5;
      } else if (isJungle) {
         canopyMaxHeight = 5;
      } else if (!isTallCanopy && !isTaiga) {
         canopyMaxHeight = 3;
      } else {
         canopyMaxHeight = 4;
      }

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
         waterVegetationChance
      );
   }

   private static TellusLodGenerator.CanopyColumn resolveCanopyColumn(TellusLodGenerator.CanopyProfile profile, int worldX, int worldZ, int cellSize) {
      int baseChance = canopyCenterChancePercent(profile);
      int chance = boostCanopyChancePercent(baseChance);
      if (chance <= 0) {
         return null;
      } else {
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
         } else {
            int crownHeight = profile.canopyBaseHeight();
            int falloff = bestRadius - bestDist;
            if (falloff >= 2) {
               crownHeight++;
            }

            if (falloff >= 4) {
               crownHeight++;
            }

            int maxHeight = profile.canopyMaxHeight();
            crownHeight += bestHash >>> 19 & 1;
            if (bestCenter) {
               crownHeight++;
            }

            crownHeight = Math.min(crownHeight, maxHeight);
            if (crownHeight <= 0) {
               return null;
            } else {
               int centerTrunkHeight = canopyTrunkHeight(profile, bestHash);
               int trunkHeight = bestCenter ? centerTrunkHeight : 0;
               int leafLift = canopyLeafLift(profile, bestCenter, centerTrunkHeight, bestDist, bestHash);
               BlockState leavesBlock = selectCanopyBlock(profile, worldX, worldZ);
               if (leavesBlock == null) {
                  return null;
               } else {
                  BlockState trunkBlock = trunkHeight > 0 ? selectTrunkBlock(profile, worldX, worldZ, bestHash) : null;
                  return new TellusLodGenerator.CanopyColumn(trunkHeight, leafLift, crownHeight, leavesBlock, trunkBlock);
               }
            }
         }
      }
   }

   private static int appendCanopyColumn(
      TellusLodGenerator.CanopyColumn canopyColumn,
      int lastLayerTop,
      int absoluteTop,
      TellusLodGenerator.WrapperCache wrappers,
      IDhApiBiomeWrapper biome,
      List<DhApiTerrainDataPoint> columnDataPoints
   ) {
      if (canopyColumn != null && lastLayerTop < absoluteTop) {
         int layerTop = lastLayerTop;
         if (canopyColumn.trunkHeight > 0 && canopyColumn.trunkBlock != null) {
            int trunkTop = Math.min(absoluteTop, lastLayerTop + canopyColumn.trunkHeight);
            if (trunkTop > lastLayerTop) {
               IDhApiBlockStateWrapper trunkBlock = wrappers.getBlockState(canopyColumn.trunkBlock);
               columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, lastLayerTop, trunkTop, trunkBlock, biome));
               layerTop = trunkTop;
            }
         }

         if (canopyColumn.leafLift > 0) {
            int liftTop = Math.min(absoluteTop, layerTop + canopyColumn.leafLift);
            if (liftTop > layerTop) {
               columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, layerTop, liftTop, wrappers.airBlock(), biome));
               layerTop = liftTop;
            }
         }

         if (canopyColumn.leavesHeight > 0 && canopyColumn.leavesBlock != null) {
            int canopyTop = Math.min(absoluteTop, layerTop + canopyColumn.leavesHeight);
            if (canopyTop > layerTop) {
               IDhApiBlockStateWrapper canopyBlock = wrappers.getBlockState(canopyColumn.leavesBlock);
               columnDataPoints.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, layerTop, canopyTop, canopyBlock, biome));
               layerTop = canopyTop;
            }
         }

         return layerTop;
      } else {
         return lastLayerTop;
      }
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

   private static int canopyCenterChancePercent(TellusLodGenerator.CanopyProfile profile) {
      return profile.canopyBaseChance();
   }

   private static int boostCanopyChancePercent(int baseChance) {
      int boosted = (baseChance * 3 + 1) / 2;
      return Math.min(100, boosted);
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
         scaledRadius = Math.min(scaledRadius, gridSize - 1);
         return scaledRadius + (centerHash >>> 16 & 1);
      }
   }

   private static boolean hasCanopyCenter(int centerHash, int chancePercent) {
      int roll = centerHash >>> 24 & 0xFF;
      int threshold = chancePercent * 255 / 100;
      return roll < threshold;
   }

   private static int canopyTrunkHeight(TellusLodGenerator.CanopyProfile profile, int centerHash) {
      int jitter = centerHash >>> 21 & 3;
      if (jitter == 3) {
         jitter = 2;
      }

      if (profile.isMangrove()) {
         return 6 + jitter + (centerHash >>> 19 & 1);
      } else if (profile.isJungle()) {
         int height = 10 + jitter;
         if ((centerHash >>> 18 & 7) == 0) {
            height += 8;
         }

         return height;
      } else if (profile.isSavannaFamily()) {
         return 5 + jitter;
      } else {
         int height = 3 + jitter;
         if (profile.isTallCanopy()) {
            height = Math.min(5, height + 1);
         }

         return height;
      }
   }

   private static int canopyLeafLift(TellusLodGenerator.CanopyProfile profile, boolean isCenter, int centerTrunkHeight, int bestDist, int centerHash) {
      if (isCenter) {
         return 0;
      } else {
         int baseLift = Math.max(1, centerTrunkHeight - Math.max(0, bestDist - 1));
         int lift = profile.isTallCanopy() ? Math.max(2, baseLift) : Math.max(1, baseLift);
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
            int hash = mixHash(worldX, worldZ, 1013904223);
            if (!hasClusterCenter(hash, chance)) {
               return null;
            } else {
               boolean kelp = shouldUseKelp(profile, waterDepth, hash);
               BlockState blockState = kelp ? Blocks.KELP_PLANT.defaultBlockState() : Blocks.SEAGRASS.defaultBlockState();
               int maxHeight = Math.min(4, Math.max(1, waterDepth - 1));
               if (maxHeight <= 0) {
                  return null;
               } else {
                  int height = 1 + (hash >>> 12 & 3);
                  height = Math.min(height, maxHeight);
                  return height <= 0 ? null : new TellusLodGenerator.WaterVegetationColumn(height, blockState);
               }
            }
         }
      }
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

   private static BlockState selectCanopyBlock(TellusLodGenerator.CanopyProfile profile, int worldX, int worldZ) {
      if (profile.isWindsweptForest()) {
         return Blocks.SPRUCE_LEAVES.defaultBlockState();
      } else if (profile.isWoodedBadlands()) {
         return Blocks.OAK_LEAVES.defaultBlockState();
      } else if (profile.isWindsweptSavanna() || profile.isSavannaPlateau()) {
         return Blocks.ACACIA_LEAVES.defaultBlockState();
      } else if (profile.isSparseJungle() || profile.isBambooJungle()) {
         return Blocks.JUNGLE_LEAVES.defaultBlockState();
      } else if (profile.isMangrove()) {
         return Blocks.MANGROVE_LEAVES.defaultBlockState();
      } else if (profile.isDarkForest()) {
         return Blocks.DARK_OAK_LEAVES.defaultBlockState();
      } else if (profile.isCherryGrove()) {
         return Blocks.CHERRY_LEAVES.defaultBlockState();
      } else if (profile.isJungle()) {
         return Blocks.JUNGLE_LEAVES.defaultBlockState();
      } else if (profile.isTaiga()) {
         return Blocks.SPRUCE_LEAVES.defaultBlockState();
      } else if (profile.isSavanna()) {
         return Blocks.ACACIA_LEAVES.defaultBlockState();
      } else if (profile.isSwamp()) {
         return Blocks.OAK_LEAVES.defaultBlockState();
      } else if (profile.isForest()) {
         int hash = mixHash(worldX, worldZ, 2135587861);
         return (hash >>> 28 & 3) == 0 ? Blocks.BIRCH_LEAVES.defaultBlockState() : Blocks.OAK_LEAVES.defaultBlockState();
      } else {
         return null;
      }
   }

   private static BlockState selectTrunkBlock(TellusLodGenerator.CanopyProfile profile, int worldX, int worldZ, int centerHash) {
      if (profile.isWindsweptForest()) {
         return Blocks.SPRUCE_LOG.defaultBlockState();
      } else if (profile.isWoodedBadlands()) {
         return Blocks.OAK_LOG.defaultBlockState();
      } else if (profile.isWindsweptSavanna() || profile.isSavannaPlateau()) {
         return Blocks.ACACIA_LOG.defaultBlockState();
      } else if (profile.isSparseJungle() || profile.isBambooJungle()) {
         return Blocks.JUNGLE_LOG.defaultBlockState();
      } else if (profile.isMangrove()) {
         return Blocks.MANGROVE_LOG.defaultBlockState();
      } else if (profile.isDarkForest()) {
         return Blocks.DARK_OAK_LOG.defaultBlockState();
      } else if (profile.isCherryGrove()) {
         return Blocks.CHERRY_LOG.defaultBlockState();
      } else if (profile.isJungle()) {
         return Blocks.JUNGLE_LOG.defaultBlockState();
      } else if (profile.isTaiga()) {
         return Blocks.SPRUCE_LOG.defaultBlockState();
      } else if (profile.isSavanna()) {
         return Blocks.ACACIA_LOG.defaultBlockState();
      } else if (profile.isSwamp()) {
         return Blocks.OAK_LOG.defaultBlockState();
      } else if (profile.isForest()) {
         int hash = mixHash(worldX, worldZ, 2135587861) ^ centerHash;
         return (hash >>> 28 & 3) == 0 ? Blocks.BIRCH_LOG.defaultBlockState() : Blocks.OAK_LOG.defaultBlockState();
      } else {
         return Blocks.OAK_LOG.defaultBlockState();
      }
   }

   private static int mixHash(int worldX, int worldZ, int seed) {
      int h = worldX * 522133279 ^ worldZ * -1640531527 ^ seed * 668265261;
      h ^= h >>> 15;
      h *= -2048144789;
      h ^= h >>> 13;
      h *= -1028477387;
      return h ^ h >>> 16;
   }

   private static boolean hasClusterCenter(int centerHash, int chancePercent) {
      int roll = centerHash >>> 24 & 0xFF;
      int threshold = chancePercent * 255 / 100;
      return roll < threshold;
   }

   public EDhApiWorldGeneratorReturnType getReturnType() {
      return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES;
   }

   public boolean runApiValidation() {
      return false;
   }

   public void close() {
   }

   private static final class CanopyColumn {
      private final int trunkHeight;
      private final int leafLift;
      private final int leavesHeight;
      private final BlockState leavesBlock;
      private final BlockState trunkBlock;

      private CanopyColumn(int trunkHeight, int leafLift, int leavesHeight, BlockState leavesBlock, BlockState trunkBlock) {
         this.trunkHeight = trunkHeight;
         this.leafLift = leafLift;
         this.leavesHeight = leavesHeight;
         this.leavesBlock = leavesBlock;
         this.trunkBlock = trunkBlock;
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
      int waterVegetationChance
   ) {
      private boolean isTallCanopy() {
         return this.isMangrove || this.isDarkForest || this.isJungle;
      }

      private boolean isSavannaFamily() {
         return this.isSavanna || this.isWindsweptSavanna || this.isSavannaPlateau;
      }
   }

   private record LodRoadMaskResult(
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

   private record ChunkPrefetchKey(
      int chunkX,
      int chunkZ,
      boolean includeRoadsPrefetch,
      boolean includeBuildingsPrefetch,
      boolean includeDetailedWaterPrefetch,
      double previewResolutionMeters
   ) {
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
         if (this.enabled) {
            LOGGER.info(this.summary("success"));
         }
      }

      private void logCancelled() {
         if (this.enabled) {
            LOGGER.info(this.summary("cancelled"));
         }
      }

      private void logFailure(Throwable throwable) {
         if (this.enabled) {
            LOGGER.warn(this.summary("failed"), throwable);
         }
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
         builder.append(" total=").append(formatMillis(System.nanoTime() - this.startNanos));
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
      private final Map<BlockState, IDhApiBlockStateWrapper> blockStates = new IdentityHashMap<>();
      private final Map<Holder<Biome>, IDhApiBiomeWrapper> biomes = new HashMap<>();

      private WrapperCache(IDhApiLevelWrapper levelWrapper) {
         this.levelWrapper = levelWrapper;
         this.airBlock = Delayed.wrapperFactory.getAirBlockStateWrapper();
         this.defaultBiome = this.lookupBiomeById(Biomes.PLAINS);
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

      private IDhApiBiomeWrapper lookupBiome(Holder<Biome> biome) {
         IDhApiBiomeWrapper result = biome.unwrapKey().map(this::lookupBiomeById).orElse(null);
         return result != null ? result : Objects.requireNonNull(this.defaultBiome, "No default biome available");
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
