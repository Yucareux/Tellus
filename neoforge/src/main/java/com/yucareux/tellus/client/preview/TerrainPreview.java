package com.yucareux.tellus.client.preview;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.world.data.cover.TellusLandCoverSource;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource.DemUsage;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource.ElevationDiagnostic;
import com.yucareux.tellus.world.data.koppen.TellusKoppenSource;
import com.yucareux.tellus.world.data.mask.TellusLandMaskSource;
import com.yucareux.tellus.world.data.osm.OsmBuildingFeature;
import com.yucareux.tellus.world.data.osm.OsmBuildingKind;
import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.world.data.osm.RoadClass;
import com.yucareux.tellus.world.data.osm.RoadFeature;
import com.yucareux.tellus.world.data.osm.TellusOsmBuildingSource;
import com.yucareux.tellus.world.data.osm.TellusOsmRoadSource;
import com.yucareux.tellus.world.data.osm.TellusOsmWaterSource;
import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.source.DownloadProgressReporter;
import com.yucareux.tellus.worldgen.building.BuildingBlueprint;
import com.yucareux.tellus.worldgen.building.BuildingProfile;
import com.yucareux.tellus.worldgen.building.TellusBuildingBlueprints;
import com.yucareux.tellus.worldgen.building.TellusBuildingProfiles;
import com.yucareux.tellus.worldgen.building.TellusBuildingStyles;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.MountainSurfaceRules;
import com.yucareux.tellus.worldgen.EarthProjection;
import com.yucareux.tellus.worldgen.TellusWorldgenSources;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;

@OnlyIn(Dist.CLIENT)
public final class TerrainPreview implements AutoCloseable {
   private static final int PREVIEW_GRID_SIZE = 257;
   private static final double PREVIEW_RADIUS_BLOCKS = 256.0;
   private static final int PREVIEW_INFO_PROVIDER_GRID_SIZE = 25;
   private static final int PREVIEW_OSM_MARGIN_BLOCKS = 32;
   private static final int PREVIEW_ELEVATION_PREFETCH_RADIUS = 1;
   private static final int PREVIEW_LAND_COVER_PREFETCH_RADIUS = 1;
   private static final int PREVIEW_LAND_MASK_PREFETCH_RADIUS = 1;
   private static final int PREVIEW_OSM_PREFETCH_RADIUS = 1;
   private static final long PREVIEW_WATER_OVERLAY_UNITS = 24576L;
   private static final long PREVIEW_ROAD_OVERLAY_UNITS = 32768L;
   private static final long PREVIEW_BUILDING_OVERLAY_UNITS = 24576L;
   private static final String ACTIVITY_DOWNLOAD_ELEVATION = "Sampling elevation data";
   private static final String ACTIVITY_DOWNLOAD_LAND_COVER = "Sampling land cover";
   private static final String ACTIVITY_DOWNLOAD_CLIMATE = "Sampling climate data";
   private static final String ACTIVITY_BUILD_HEIGHTS = "Normalizing terrain heights";
   private static final String ACTIVITY_BUILD_CENTER = "Centering terrain";
   private static final String ACTIVITY_BUILD_COLORS = "Coloring terrain";
   private static final String ACTIVITY_BUILD_TREES = "Applying vegetation markers";
   private static final String ACTIVITY_BUILD_HEIGHT_OFFSETS = "Applying feature heights";
   private static final String ACTIVITY_BUILD_INFO = "Summarizing DEM coverage";
   private static final String ACTIVITY_OSM_WATER_FETCH = "Loading OSM water";
   private static final String ACTIVITY_OSM_WATER_RASTER = "Rasterizing OSM water";
   private static final String ACTIVITY_OSM_ROADS_FETCH = "Loading OSM roads";
   private static final String ACTIVITY_OSM_ROADS_RASTER = "Rasterizing OSM roads";
   private static final String ACTIVITY_OSM_BUILDINGS_FETCH = "Loading OSM buildings";
   private static final String ACTIVITY_OSM_BUILDINGS_RASTER = "Rasterizing OSM buildings";
   private static final int ESA_NO_DATA = 0;
   private static final int ESA_WATER = 80;
   private static final double PREVIEW_INLAND_WATER_DEPTH_BLOCKS = 6.0;
   private static final int PREVIEW_FLAT_WATER_COLOR = waterColorForDepth(PREVIEW_INLAND_WATER_DEPTH_BLOCKS);
   private static final int BUILDING_PREVIEW_COLOR = 10000536;
   private static final Vector3f LIGHT_DIR = new Vector3f(-0.4F, 0.8F, -0.4F).normalize();
   private final TellusElevationSource elevationSource = new TellusElevationSource();
   private final TellusLandCoverSource landCoverSource = new TellusLandCoverSource();
   private final TellusKoppenSource koppenSource = new TellusKoppenSource();
   private final TellusLandMaskSource landMaskSource = new TellusLandMaskSource();
   private final TellusOsmRoadSource osmRoadSource = TellusWorldgenSources.osmRoads();
   private final TellusOsmBuildingSource osmBuildingSource = TellusWorldgenSources.osmBuildings();
   private final TellusOsmWaterSource osmWaterSource = TellusWorldgenSources.osmWater();
   private final ExecutorService executor;
   private final AtomicInteger requestId = new AtomicInteger();
   private final AtomicReference<TerrainPreview.PreviewStatus> status = new AtomicReference<>(
      new TerrainPreview.PreviewStatus(TerrainPreview.PreviewStage.COMPLETE, 1.0F, null)
   );
   private final AtomicReference<TerrainPreview.PreviewInfo> info = new AtomicReference<>();
   private Future<TerrainPreview.PreviewMesh> pending;
   private volatile TerrainPreview.PreviewMesh mesh;
   private volatile TerrainPreview.PreviewBaseSnapshot baseSnapshot;
   private volatile EarthGeneratorSettings lastSettings;

   public TerrainPreview() {
      this.executor = Executors.newSingleThreadExecutor(new TerrainPreview.PreviewThreadFactory());
   }

   public void requestRebuild(EarthGeneratorSettings settings) {
      int id = this.requestId.incrementAndGet();
      if (this.pending != null) {
         this.pending.cancel(true);
      }

      this.lastSettings = settings;
      TerrainPreview.PreviewBaseSnapshot cached = this.baseSnapshot;
      if (this.canReuseBaseSnapshot(settings, cached)) {
         this.info.set(cached.info());
         this.updateStatus(id, TerrainPreview.PreviewStage.LOADING, 0.0F, ACTIVITY_BUILD_COLORS);
      } else {
         this.info.set(null);
         this.updateStatus(id, TerrainPreview.PreviewStage.DOWNLOADING, 0.0F, ACTIVITY_DOWNLOAD_ELEVATION);
      }

      this.pending = this.executor.submit(() -> this.buildMesh(settings, id));
   }

   public void tick() {
      Future<TerrainPreview.PreviewMesh> future = this.pending;
      if (future != null && future.isDone()) {
         this.pending = null;

         try {
            TerrainPreview.PreviewMesh preview = future.get();
            if (preview != null) {
               this.mesh = preview;
               this.info.set(preview.info);
            }
         } catch (CancellationException var3) {
         } catch (InterruptedException var4) {
            Thread.currentThread().interrupt();
         } catch (ExecutionException var5) {
            Tellus.LOGGER.warn("Preview render update failed", var5.getCause() != null ? var5.getCause() : var5);
         }
      }
   }

   public boolean isLoading() {
      return this.pending != null;
   }

   public TerrainPreview.PreviewStatus getStatus() {
      return Objects.requireNonNull(this.status.get(), "status");
   }

   public TerrainPreview.PreviewInfo getInfo() {
      return this.info.get();
   }

   public EarthGeneratorSettings getLastSettings() {
      return this.lastSettings;
   }

   public void render(
      GuiGraphics graphics,
      int x,
      int y,
      int width,
      int height,
      float rotationX,
      float rotationY,
      float zoom,
      TerrainPreviewWidget.RenderMode renderMode
   ) {
      TerrainPreview.PreviewMesh preview = this.mesh;
      if (preview != null && width > 0 && height > 0) {
         Matrix4f modelView = buildModelView(rotationX, rotationY);
         Matrix4f projection = buildProjection(width, height, zoom);
         graphics.enableScissor(x, y, x + width, y + height);
         renderPreviewMesh(preview, renderMode, modelView, projection, x, y, width, height);
         graphics.disableScissor();
      }
   }

   private static void renderPreviewMesh(
      TerrainPreview.PreviewMesh mesh,
      TerrainPreviewWidget.RenderMode renderMode,
      Matrix4f modelView,
      Matrix4f projection,
      int x,
      int y,
      int width,
      int height
   ) {
      float[] heights = mesh.heightsFor(renderMode);
      int[] colors = mesh.colorsFor(renderMode);
      int stride = mesh.granularity;
      if (mesh.size <= stride) {
         return;
      }

      int quadsX = (mesh.size - 1) / stride;
      int quadsZ = (mesh.size - 1) / stride;
      int quadCount = quadsX * quadsZ;
      int[] quadTopLeft = new int[quadCount];
      float[] quadDepth = new float[quadCount];
      Vector3f view = new Vector3f();
      Vector3f normal = new Vector3f();
      float depthScale = 0.25F;
      int quadIndex = 0;

      for (int zIndex = 0; zIndex < mesh.size - stride; zIndex += stride) {
         float z0 = mesh.axis[zIndex];
         float z1 = mesh.axis[zIndex + stride];
         int rowIndex = zIndex * mesh.size;
         int nextRowIndex = (zIndex + stride) * mesh.size;

         for (int xIndex = 0; xIndex < mesh.size - stride; xIndex += stride) {
            int idx = rowIndex + xIndex;
            int idxRight = idx + stride;
            int idxDown = nextRowIndex + xIndex;
            int idxDownRight = idxDown + stride;
            float v0 = modelView.transformPosition(mesh.axis[xIndex], heights[idx], z0, view).z;
            float v1 = modelView.transformPosition(mesh.axis[xIndex + stride], heights[idxRight], z0, view).z;
            float v2 = modelView.transformPosition(mesh.axis[xIndex], heights[idxDown], z1, view).z;
            float v3 = modelView.transformPosition(mesh.axis[xIndex + stride], heights[idxDownRight], z1, view).z;
            float maxZ = Math.max(Math.max(v0, v1), Math.max(v2, v3));
            if (maxZ > -0.05F) {
               quadTopLeft[quadIndex] = -1;
               quadDepth[quadIndex] = Float.POSITIVE_INFINITY;
            } else {
               quadTopLeft[quadIndex] = idx;
               quadDepth[quadIndex] = (v0 + v1 + v2 + v3) * depthScale;
            }

            quadIndex++;
         }
      }

      if (quadCount > 1) {
         sortPreviewQuads(quadTopLeft, quadDepth, 0, quadCount - 1);
      }

      BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
      Vector3f projected = new Vector3f();
      float x0 = x;
      float y0 = y;
      float drawWidth = width;
      float drawHeight = height;

      for (int i = 0; i < quadCount; i++) {
         int idx = quadTopLeft[i];
         if (idx >= 0) {
            int xIndex = idx % mesh.size;
            int zIndex = idx / mesh.size;
            int idxRight = idx + stride;
            int idxDown = idx + stride * mesh.size;
            int idxDownRight = idxDown + stride;
            float worldX0 = mesh.axis[xIndex];
            float worldX1 = mesh.axis[xIndex + stride];
            float worldZ0 = mesh.axis[zIndex];
            float worldZ1 = mesh.axis[zIndex + stride];
            float shade = computePreviewQuadShade(
               worldX0,
               heights[idx],
               worldZ0,
               worldX1,
               heights[idxRight],
               worldZ0,
               worldX0,
               heights[idxDown],
               worldZ1,
               worldX1,
               heights[idxDownRight],
               worldZ1,
               normal
            );
            int quadColor = applyPreviewShade(colors[idx], shade);
            emitPreviewVertex(buffer, modelView, projection, worldX0, heights[idxDown], worldZ1, quadColor, x0, y0, drawWidth, drawHeight, view, projected);
            emitPreviewVertex(buffer, modelView, projection, worldX1, heights[idxDownRight], worldZ1, quadColor, x0, y0, drawWidth, drawHeight, view, projected);
            emitPreviewVertex(buffer, modelView, projection, worldX1, heights[idxRight], worldZ0, quadColor, x0, y0, drawWidth, drawHeight, view, projected);
            emitPreviewVertex(buffer, modelView, projection, worldX0, heights[idx], worldZ0, quadColor, x0, y0, drawWidth, drawHeight, view, projected);
         }
      }

      RenderSystem.enableBlend();
      RenderSystem.defaultBlendFunc();
      RenderSystem.setShader(GameRenderer::getPositionColorShader);
      BufferUploader.drawWithShader(buffer.buildOrThrow());
      RenderSystem.disableBlend();
   }

   private static Matrix4f buildProjection(int width, int height, float zoom) {
      float aspect = (float)width / height;
      float effectiveFov = Mth.clamp(50.0F / Math.max(zoom, 0.01F), 15.0F, 120.0F);
      return new Matrix4f().setPerspective((float)Math.toRadians(effectiveFov), aspect, 0.05F, 100.0F);
   }

   private static Matrix4f buildModelView(float rotationX, float rotationY) {
      return new Matrix4f().identity().translate(0.0F, 0.0F, -2.35F).rotateX(rotationX).rotateY(rotationY);
   }

   private TerrainPreview.PreviewMesh buildMesh(EarthGeneratorSettings settings, int id) {
      if (this.shouldAbortRequest(id)) {
         return null;
      }

      TerrainPreview.PreviewBaseSnapshot cached = this.baseSnapshot;
      if (this.canReuseBaseSnapshot(settings, cached)) {
         return this.buildMeshFromSnapshot(settings, id, cached);
      }

      int size = PREVIEW_GRID_SIZE;
      double[] blockHeights = new double[size * size];
      boolean[] esaWaterMask = new boolean[size * size];
      double[] elevations = new double[size * size];
      int coverStride = 2;
      int coverSize = (size + coverStride - 1) / coverStride;
      int climateStride = 4;
      int climateSize = (size + climateStride - 1) / climateStride;
      long downloadDone = 0L;
      boolean roadsPreviewEnabled = settings.enableRoads() && settings.worldScale() > 0.0 && settings.worldScale() <= 15.0;
      boolean buildingsPreviewEnabled = settings.enableBuildings() && settings.worldScale() > 0.0 && settings.worldScale() <= 15.0 && this.osmBuildingSource.available();
      double worldScale = settings.worldScale();
      boolean useVisualCover = worldScale > 0.0 && worldScale < 10.0;
      long coverDownloadUnits = (long)coverSize * coverSize * (useVisualCover ? 2L : 1L);
      long downloadTotal = (long)size * size + coverDownloadUnits + (long)climateSize * climateSize;
      boolean waterPreviewEnabled = settings.enableWater() && worldScale > 0.0 && this.osmWaterSource.available();
      boolean remaSnowEnabled = TellusElevationSource.usesPolarDem(settings.demSelection()) && worldScale > 0.0;
      double remaSnowBoundaryZ = remaSnowEnabled ? TellusElevationSource.remaBoundaryBlockZ(worldScale) : Double.POSITIVE_INFINITY;
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      double centerX = settings.spawnLongitude() * blocksPerDegree;
      double centerZ = EarthProjection.latToBlockZ(settings.spawnLatitude(), worldScale);
      double radius = PREVIEW_RADIUS_BLOCKS;
      double step = radius * 2.0 / (size - 1);
      double previewResolutionMeters = Math.max(worldScale, step * worldScale);
      double minWorldX = centerX - radius;
      double minWorldZ = centerZ - radius;
      double maxWorldX = centerX + radius;
      double maxWorldZ = centerZ + radius;
      float[] xCoords = buildAxisCoordinates(size);
      this.queueBasePreviewPrefetch(centerX, centerZ, worldScale, settings.demSelection(), previewResolutionMeters);
      this.queueOsmPreviewPrefetch(centerX, centerZ, worldScale, minWorldX, minWorldZ, maxWorldX, maxWorldZ, waterPreviewEnabled, roadsPreviewEnabled, buildingsPreviewEnabled);

      TerrainPreview.DownloadNetworkTracker downloadTracker = new TerrainPreview.DownloadNetworkTracker();
      int[] coverClasses = new int[coverSize * coverSize];
      int[] visualCoverClasses = new int[coverSize * coverSize];
      byte[] climateGroups = new byte[climateSize * climateSize];
      double minElevation = Double.POSITIVE_INFINITY;
      double maxElevation = Double.NEGATIVE_INFINITY;
      int minSurfaceY = Integer.MAX_VALUE;
      int maxSurfaceY = Integer.MIN_VALUE;

      try (DownloadProgressReporter.Scope ignored = DownloadProgressReporter.push(downloadTracker)) {
         for (int z = 0; z < size; z++) {
            if (this.shouldAbortRequest(id)) {
               return null;
            }

            double blockZ = centerZ - radius + z * step;

            for (int x = 0; x < size; x++) {
               if (this.shouldAbortRequest(id)) {
                  return null;
               }

               double blockX = centerX - radius + x * step;
               int idx = x + z * size;
               TellusLandMaskSource.LandMaskSample landMaskSample = this.landMaskSource.sampleLandMask(blockX, blockZ, worldScale);
               int pointCoverClass = landMaskSample.known() && landMaskSample.land()
                  ? ESA_NO_DATA
                  : this.landCoverSource.sampleCoverClass(blockX, blockZ, worldScale, previewResolutionMeters);
               boolean oceanZoom = useOceanZoom(landMaskSample, pointCoverClass);
               double elevation = this.elevationSource.samplePreviewElevationMeters(
                  blockX,
                  blockZ,
                  worldScale,
                  oceanZoom,
                  settings.demSelection(),
                  previewResolutionMeters
               );
               if (this.shouldAbortRequest(id)) {
                  return null;
               }

               int surfaceY = scaledSurfaceY(elevation, settings);
               elevations[idx] = elevation;
               blockHeights[idx] = surfaceY;
               esaWaterMask[idx] = pointCoverClass == ESA_WATER;
               minElevation = Math.min(minElevation, elevation);
               maxElevation = Math.max(maxElevation, elevation);
               minSurfaceY = Math.min(minSurfaceY, surfaceY);
               maxSurfaceY = Math.max(maxSurfaceY, surfaceY);
               if ((++downloadDone & 255L) == 0L) {
                  this.updateDownloadStatus(id, downloadDone, downloadTotal, downloadTracker, ACTIVITY_DOWNLOAD_ELEVATION);
               }
            }

            this.updateDownloadStatus(id, downloadDone, downloadTotal, downloadTracker, ACTIVITY_DOWNLOAD_ELEVATION);
         }

         for (int z = 0; z < coverSize; z++) {
            if (this.shouldAbortRequest(id)) {
               return null;
            }

            int sampleZ = Math.min(size - 1, z * coverStride);
            double blockZ = centerZ - radius + sampleZ * step;

            for (int xx = 0; xx < coverSize; xx++) {
               if (this.shouldAbortRequest(id)) {
                  return null;
               }

               int sampleX = Math.min(size - 1, xx * coverStride);
               double blockX = centerX - radius + sampleX * step;
               int idx = xx + z * coverSize;
               int rawCoverClass = this.landCoverSource.sampleCoverClass(blockX, blockZ, worldScale, previewResolutionMeters);
               coverClasses[idx] = rawCoverClass;
               visualCoverClasses[idx] = rawCoverClass;
               if (this.shouldAbortRequest(id)) {
                  return null;
               }

               if ((++downloadDone & 255L) == 0L) {
                  this.updateDownloadStatus(id, downloadDone, downloadTotal, downloadTracker, ACTIVITY_DOWNLOAD_LAND_COVER);
               }

               if (useVisualCover) {
                  visualCoverClasses[idx] = this.landCoverSource.sampleVisualCoverClass(blockX, blockZ, worldScale, previewResolutionMeters);
                  if (this.shouldAbortRequest(id)) {
                     return null;
                  }

                  if ((++downloadDone & 255L) == 0L) {
                     this.updateDownloadStatus(id, downloadDone, downloadTotal, downloadTracker, ACTIVITY_DOWNLOAD_LAND_COVER);
                  }
               }
            }

            this.updateDownloadStatus(id, downloadDone, downloadTotal, downloadTracker, ACTIVITY_DOWNLOAD_LAND_COVER);
         }

         for (int z = 0; z < climateSize; z++) {
            if (this.shouldAbortRequest(id)) {
               return null;
            }

            int sampleZ = Math.min(size - 1, z * climateStride);
            double blockZ = centerZ - radius + sampleZ * step;

            for (int xxx = 0; xxx < climateSize; xxx++) {
               if (this.shouldAbortRequest(id)) {
                  return null;
               }

               int sampleX = Math.min(size - 1, xxx * climateStride);
               double blockX = centerX - radius + sampleX * step;
               int idx = xxx + z * climateSize;
               String koppen = this.koppenSource.sampleDitheredCode(blockX, blockZ, worldScale);
               if (this.shouldAbortRequest(id)) {
                  return null;
               }

               climateGroups[idx] = climateGroup(koppen);
               if ((++downloadDone & 255L) == 0L) {
                  this.updateDownloadStatus(id, downloadDone, downloadTotal, downloadTracker, ACTIVITY_DOWNLOAD_CLIMATE);
               }
            }

            this.updateDownloadStatus(id, downloadDone, downloadTotal, downloadTracker, ACTIVITY_DOWNLOAD_CLIMATE);
         }

         this.updateDownloadStatus(id, downloadTotal, downloadTotal, downloadTracker, ACTIVITY_DOWNLOAD_CLIMATE);
      }

      if (this.shouldAbortRequest(id)) {
         return null;
      }

      float[] heights = new float[size * size];
      float min = Float.POSITIVE_INFINITY;
      float max = Float.NEGATIVE_INFINITY;
      long gridArea = (long)size * size;
      long treeOverlayUnits = gridArea;
      long heightOffsetUnits = gridArea;
      long infoUnits = (long)PREVIEW_INFO_PROVIDER_GRID_SIZE * PREVIEW_INFO_PROVIDER_GRID_SIZE;
      long buildTotal = gridArea * 3L
         + treeOverlayUnits
         + heightOffsetUnits
         + infoUnits
         + (waterPreviewEnabled ? PREVIEW_WATER_OVERLAY_UNITS : 0L)
         + (roadsPreviewEnabled ? PREVIEW_ROAD_OVERLAY_UNITS : 0L)
         + (buildingsPreviewEnabled ? PREVIEW_BUILDING_OVERLAY_UNITS : 0L);
      long buildDone = 0L;
      this.updateStatus(id, TerrainPreview.PreviewStage.LOADING, 0.0F, ACTIVITY_BUILD_HEIGHTS);

      for (int i = 0; i < blockHeights.length; i++) {
         float value = (float)((blockHeights[i] - settings.heightOffset()) / radius * 0.7F);
         heights[i] = value;
         min = Math.min(min, value);
         max = Math.max(max, value);
         if ((i + 1) % size == 0) {
            if (this.shouldAbortRequest(id)) {
               return null;
            }

            buildDone += size;
            this.updateBuildStatus(id, buildDone, buildTotal, ACTIVITY_BUILD_HEIGHTS);
         }
      }

      float center = (min + max) * 0.5F;

      for (int ix = 0; ix < heights.length; ix++) {
         heights[ix] -= center;
         if ((ix + 1) % size == 0) {
            if (this.shouldAbortRequest(id)) {
               return null;
            }

            buildDone += size;
            this.updateBuildStatus(id, buildDone, buildTotal, ACTIVITY_BUILD_CENTER);
         }
      }

      this.updateBuildStatus(id, buildDone, buildTotal, ACTIVITY_BUILD_COLORS);
      int seaLevel = settings.resolveSeaLevel();
      float[] terrainHeights = heights.clone();
      float[] detailHeights = heights.clone();
      int[] terrainColors = new int[size * size];
      int[] detailColors = new int[size * size];
      float[] detailHeightOffsets = new float[size * size];

      for (int z = 0; z < size; z++) {
         if (this.shouldAbortRequest(id)) {
            return null;
         }

         double blockZ = minWorldZ + z * step;
         int coverZ = Math.min(coverSize - 1, z / coverStride);
         int climateZ = Math.min(climateSize - 1, z / climateStride);

         for (int xxxx = 0; xxxx < size; xxxx++) {
            int idx = xxxx + z * size;
            int coverX = Math.min(coverSize - 1, xxxx / coverStride);
            int climateX = Math.min(climateSize - 1, xxxx / climateStride);
            int coverIdx = coverX + coverZ * coverSize;
            int climateIdx = climateX + climateZ * climateSize;
            int rawCoverClass = coverClasses[coverIdx];
            int visualCoverClass = visualCoverClasses[coverIdx];
            byte climateGroup = climateGroups[climateIdx];
            int slopeDiff = previewSlopeDiff(blockHeights, size, idx, step);
            int convexity = previewConvexity(blockHeights, size, idx, step);
            double slope = computeSlope(blockHeights, size, idx, step);
            int color = colorForPreview(
               rawCoverClass,
               visualCoverClass,
               climateGroup,
               elevations[idx],
               blockHeights[idx],
               slopeDiff,
               convexity,
               slope,
               seaLevel,
               esaWaterMask[idx],
               !waterPreviewEnabled,
               settings.enableWater(),
               remaSnowEnabled && blockZ >= remaSnowBoundaryZ
            );
            terrainColors[idx] = color;
            detailColors[idx] = color;
         }

         buildDone += size;
         this.updateBuildStatus(id, buildDone, buildTotal, ACTIVITY_BUILD_COLORS);
      }

      TerrainPreview.PreviewInfo placeholderInfo = previewInfoPlaceholder(minElevation, maxElevation, minSurfaceY, maxSurfaceY);
      this.publishInterimMesh(id, size, terrainHeights, terrainColors, detailHeights, detailColors, xCoords, placeholderInfo);

      if (!this.overlayTreePreviewMarkers(
         id,
         buildTotal,
         buildDone,
         detailColors,
         detailHeightOffsets,
         coverClasses,
         visualCoverClasses,
         coverSize,
         coverStride,
         settings,
         minWorldX,
         minWorldZ,
         step
      )) {
         return null;
      }

      buildDone += treeOverlayUnits;
      if (waterPreviewEnabled) {
         if (!this.processWaterPreviewOverlay(
            id, buildTotal, buildDone, terrainColors, detailColors, detailHeightOffsets, settings, minWorldX, minWorldZ, maxWorldX, maxWorldZ, step
         )) {
            return null;
         }

         buildDone += PREVIEW_WATER_OVERLAY_UNITS;
      }

      if (roadsPreviewEnabled) {
         if (!this.processRoadPreviewOverlay(id, buildTotal, buildDone, terrainColors, detailColors, settings, centerX, centerZ, radius, step)) {
            return null;
         }

         buildDone += PREVIEW_ROAD_OVERLAY_UNITS;
      }

      if (!this.applyFeatureHeightOffsets(id, buildTotal, buildDone, detailHeights, detailHeightOffsets, size)) {
         return null;
      }

      buildDone += heightOffsetUnits;
      if (buildingsPreviewEnabled) {
         if (!this.processBuildingPreviewOverlay(
            id, buildTotal, buildDone, terrainHeights, terrainColors, detailHeights, detailColors, blockHeights, settings, centerX, centerZ, radius, step, center
         )) {
            return null;
         }

         buildDone += PREVIEW_BUILDING_OVERLAY_UNITS;
      }

      if (this.shouldAbortRequest(id)) {
         return null;
      }

      TerrainPreview.PreviewInfo previewInfo = this.buildPreviewInfo(
         id, settings, centerX, centerZ, minElevation, maxElevation, minSurfaceY, maxSurfaceY, buildTotal, buildDone
      );
      if (previewInfo == null || this.shouldAbortRequest(id)) {
         return null;
      }

      buildDone += infoUnits;
      this.baseSnapshot = new TerrainPreview.PreviewBaseSnapshot(
         worldScale,
         settings.terrestrialHeightScale(),
         settings.oceanicHeightScale(),
         settings.heightOffset(),
         settings.spawnLatitude(),
         settings.spawnLongitude(),
         settings.demSelection(),
         size,
         coverStride,
         coverSize,
         climateStride,
         climateSize,
         centerX,
         centerZ,
         radius,
         step,
         minWorldX,
         minWorldZ,
         maxWorldX,
         maxWorldZ,
         blockHeights,
         esaWaterMask,
         elevations,
         coverClasses,
         visualCoverClasses,
         climateGroups,
         heights,
         center,
         xCoords,
         previewInfo
      );
      this.updateStatus(id, TerrainPreview.PreviewStage.COMPLETE, 1.0F);
      return new TerrainPreview.PreviewMesh(size, 1, terrainHeights, terrainColors, detailHeights, detailColors, xCoords, previewInfo);
   }

   private TerrainPreview.PreviewMesh buildMeshFromSnapshot(EarthGeneratorSettings settings, int id, TerrainPreview.PreviewBaseSnapshot snapshot) {
      if (this.shouldAbortRequest(id)) {
         return null;
      }

      int size = snapshot.size();
      double worldScale = snapshot.worldScale();
      boolean roadsPreviewEnabled = settings.enableRoads() && worldScale > 0.0 && worldScale <= 15.0;
      boolean buildingsPreviewEnabled = settings.enableBuildings() && worldScale > 0.0 && worldScale <= 15.0 && this.osmBuildingSource.available();
      boolean waterPreviewEnabled = settings.enableWater() && worldScale > 0.0 && this.osmWaterSource.available();
      boolean remaSnowEnabled = TellusElevationSource.usesPolarDem(settings.demSelection()) && worldScale > 0.0;
      double remaSnowBoundaryZ = remaSnowEnabled ? TellusElevationSource.remaBoundaryBlockZ(worldScale) : Double.POSITIVE_INFINITY;
      this.queueOsmPreviewPrefetch(
         snapshot.centerX(),
         snapshot.centerZ(),
         worldScale,
         snapshot.minWorldX(),
         snapshot.minWorldZ(),
         snapshot.maxWorldX(),
         snapshot.maxWorldZ(),
         waterPreviewEnabled,
         roadsPreviewEnabled,
         buildingsPreviewEnabled
      );
      long gridArea = (long)size * size;
      long buildTotal = gridArea * 3L
         + (waterPreviewEnabled ? PREVIEW_WATER_OVERLAY_UNITS : 0L)
         + (roadsPreviewEnabled ? PREVIEW_ROAD_OVERLAY_UNITS : 0L)
         + (buildingsPreviewEnabled ? PREVIEW_BUILDING_OVERLAY_UNITS : 0L);
      long buildDone = 0L;
      this.updateStatus(id, TerrainPreview.PreviewStage.LOADING, 0.0F, ACTIVITY_BUILD_COLORS);
      int seaLevel = settings.resolveSeaLevel();
      float[] terrainHeights = snapshot.heights().clone();
      float[] detailHeights = snapshot.heights().clone();
      int[] terrainColors = new int[size * size];
      int[] detailColors = new int[size * size];
      float[] detailHeightOffsets = new float[size * size];

      for (int z = 0; z < size; z++) {
         if (this.shouldAbortRequest(id)) {
            return null;
         }

         double blockZ = snapshot.minWorldZ() + z * snapshot.step();
         int coverZ = Math.min(snapshot.coverSize() - 1, z / snapshot.coverStride());
         int climateZ = Math.min(snapshot.climateSize() - 1, z / snapshot.climateStride());

         for (int x = 0; x < size; x++) {
            int idx = x + z * size;
            int coverX = Math.min(snapshot.coverSize() - 1, x / snapshot.coverStride());
            int climateX = Math.min(snapshot.climateSize() - 1, x / snapshot.climateStride());
            int coverIdx = coverX + coverZ * snapshot.coverSize();
            int climateIdx = climateX + climateZ * snapshot.climateSize();
            int rawCoverClass = snapshot.coverClasses()[coverIdx];
            int visualCoverClass = snapshot.visualCoverClasses()[coverIdx];
            byte climateGroup = snapshot.climateGroups()[climateIdx];
            int slopeDiff = previewSlopeDiff(snapshot.blockHeights(), size, idx, snapshot.step());
            int convexity = previewConvexity(snapshot.blockHeights(), size, idx, snapshot.step());
            double slope = computeSlope(snapshot.blockHeights(), size, idx, snapshot.step());
            int color = colorForPreview(
               rawCoverClass,
               visualCoverClass,
               climateGroup,
               snapshot.elevations()[idx],
               snapshot.blockHeights()[idx],
               slopeDiff,
               convexity,
               slope,
               seaLevel,
               snapshot.esaWaterMask()[idx],
               !waterPreviewEnabled,
               settings.enableWater(),
               remaSnowEnabled && blockZ >= remaSnowBoundaryZ
            );
            terrainColors[idx] = color;
            detailColors[idx] = color;
         }

         buildDone += size;
         this.updateBuildStatus(id, buildDone, buildTotal, ACTIVITY_BUILD_COLORS);
      }

      this.publishInterimMesh(id, size, terrainHeights, terrainColors, detailHeights, detailColors, snapshot.xCoords(), snapshot.info());

      if (!this.overlayTreePreviewMarkers(
         id,
         buildTotal,
         buildDone,
         detailColors,
         detailHeightOffsets,
         snapshot.coverClasses(),
         snapshot.visualCoverClasses(),
         snapshot.coverSize(),
         snapshot.coverStride(),
         settings,
         snapshot.minWorldX(),
         snapshot.minWorldZ(),
         snapshot.step()
      )) {
         return null;
      }

      buildDone += gridArea;
      if (waterPreviewEnabled) {
         if (!this.processWaterPreviewOverlay(
            id,
            buildTotal,
            buildDone,
            terrainColors,
            detailColors,
            detailHeightOffsets,
            settings,
            snapshot.minWorldX(),
            snapshot.minWorldZ(),
            snapshot.maxWorldX(),
            snapshot.maxWorldZ(),
            snapshot.step()
         )) {
            return null;
         }

         buildDone += PREVIEW_WATER_OVERLAY_UNITS;
      }

      if (roadsPreviewEnabled) {
         if (!this.processRoadPreviewOverlay(
            id,
            buildTotal,
            buildDone,
            terrainColors,
            detailColors,
            settings,
            snapshot.centerX(),
            snapshot.centerZ(),
            snapshot.radius(),
            snapshot.step()
         )) {
            return null;
         }

         buildDone += PREVIEW_ROAD_OVERLAY_UNITS;
      }

      if (!this.applyFeatureHeightOffsets(id, buildTotal, buildDone, detailHeights, detailHeightOffsets, size)) {
         return null;
      }

      buildDone += gridArea;
      if (buildingsPreviewEnabled) {
         if (!this.processBuildingPreviewOverlay(
            id,
            buildTotal,
            buildDone,
            terrainHeights,
            terrainColors,
            detailHeights,
            detailColors,
            snapshot.blockHeights(),
            settings,
            snapshot.centerX(),
            snapshot.centerZ(),
            snapshot.radius(),
            snapshot.step(),
            snapshot.heightCenter()
         )) {
            return null;
         }

         buildDone += PREVIEW_BUILDING_OVERLAY_UNITS;
      }

      if (this.shouldAbortRequest(id)) {
         return null;
      }

      this.updateStatus(id, TerrainPreview.PreviewStage.COMPLETE, 1.0F);
      return new TerrainPreview.PreviewMesh(
         size, 1, terrainHeights, terrainColors, detailHeights, detailColors, snapshot.xCoords(), snapshot.info()
      );
   }

   private boolean canReuseBaseSnapshot(EarthGeneratorSettings settings, TerrainPreview.PreviewBaseSnapshot snapshot) {
      return snapshot != null
         && Double.compare(snapshot.worldScale(), settings.worldScale()) == 0
         && Double.compare(snapshot.terrestrialHeightScale(), settings.terrestrialHeightScale()) == 0
         && Double.compare(snapshot.oceanicHeightScale(), settings.oceanicHeightScale()) == 0
         && snapshot.heightOffset() == settings.heightOffset()
         && Double.compare(snapshot.spawnLatitude(), settings.spawnLatitude()) == 0
         && Double.compare(snapshot.spawnLongitude(), settings.spawnLongitude()) == 0
         && snapshot.demSelection().equals(settings.demSelection());
   }

   private void queueBasePreviewPrefetch(
      double centerX,
      double centerZ,
      double worldScale,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (worldScale > 0.0) {
         Util.backgroundExecutor().execute(() -> {
            try {
               this.elevationSource.prefetchTiles(
                  centerX, centerZ, worldScale, PREVIEW_ELEVATION_PREFETCH_RADIUS, demSelection, previewResolutionMeters
               );
            } catch (RuntimeException ignored) {
            }
         });
         Util.backgroundExecutor().execute(() -> {
            try {
               this.landCoverSource.prefetchTiles(centerX, centerZ, worldScale, PREVIEW_LAND_COVER_PREFETCH_RADIUS, previewResolutionMeters);
            } catch (RuntimeException ignored) {
            }
         });
         Util.backgroundExecutor().execute(() -> {
            try {
               this.landMaskSource.prefetchTiles(centerX, centerZ, worldScale, PREVIEW_LAND_MASK_PREFETCH_RADIUS);
            } catch (RuntimeException ignored) {
            }
         });
      }
   }

   private void queueOsmPreviewPrefetch(
      double centerX,
      double centerZ,
      double worldScale,
      double minWorldX,
      double minWorldZ,
      double maxWorldX,
      double maxWorldZ,
      boolean waterPreviewEnabled,
      boolean roadsPreviewEnabled,
      boolean buildingsPreviewEnabled
   ) {
      if (waterPreviewEnabled) {
         this.queueWaterPreviewPrefetch(minWorldX, minWorldZ, maxWorldX, maxWorldZ, worldScale);
      }

      if (roadsPreviewEnabled) {
         this.osmRoadSource.prefetchTiles(centerX, centerZ, worldScale, PREVIEW_OSM_PREFETCH_RADIUS);
      }

      if (buildingsPreviewEnabled) {
         this.osmBuildingSource.prefetchTiles(centerX, centerZ, worldScale, PREVIEW_OSM_PREFETCH_RADIUS);
      }
   }

   public static int scaledSurfaceY(double elevation, EarthGeneratorSettings settings) {
      double scale = elevation >= 0.0 ? settings.terrestrialHeightScale() : settings.oceanicHeightScale();
      double scaled = elevation * scale / settings.worldScale();
      int base = elevation >= 0.0 ? Mth.ceil(scaled) : Mth.floor(scaled);
      return base + settings.heightOffset();
   }

   private void publishInterimMesh(
      int id,
      int size,
      float[] terrainHeights,
      int[] terrainColors,
      float[] detailHeights,
      int[] detailColors,
      float[] xCoords,
      TerrainPreview.PreviewInfo info
   ) {
      if (!this.shouldAbortRequest(id)) {
         this.mesh = new TerrainPreview.PreviewMesh(
            size,
            1,
            terrainHeights.clone(),
            terrainColors.clone(),
            detailHeights.clone(),
            detailColors.clone(),
            xCoords,
            info
         );
         this.info.set(info);
      }
   }

   private static float[] buildAxisCoordinates(int size) {
      float[] xCoords = new float[size];

      for (int i = 0; i < size; i++) {
         xCoords[i] = (float)(-1.0 + 2.0 * i / (size - 1));
      }

      return xCoords;
   }

   private static TerrainPreview.PreviewInfo previewInfoPlaceholder(
      double minElevation, double maxElevation, int minSurfaceY, int maxSurfaceY
   ) {
      return new TerrainPreview.PreviewInfo(List.of(), List.of(), 0, minElevation, maxElevation, minSurfaceY, maxSurfaceY);
   }

   private TerrainPreview.PreviewInfo buildPreviewInfo(
      int id,
      EarthGeneratorSettings settings,
      double centerX,
      double centerZ,
      double minElevation,
      double maxElevation,
      int minSurfaceY,
      int maxSurfaceY,
      long buildTotal,
      long buildBaseDone
   ) {
      double radius = PREVIEW_RADIUS_BLOCKS;
      int providerGridSize = PREVIEW_INFO_PROVIDER_GRID_SIZE;
      double providerStep = radius * 2.0 / (providerGridSize - 1);
      double imageStep = radius * 2.0 / (PREVIEW_GRID_SIZE - 1);
      double previewResolutionMeters = Math.max(settings.worldScale(), imageStep * settings.worldScale());
      EnumMap<DemUsage, Integer> primaryCounts = new EnumMap<>(DemUsage.class);
      Map<Integer, Integer> resolutionCounts = new HashMap<>();
      int providerSampleCount = 0;
      int blendedMask = 0;
      long progressDone = 0L;

      for (int z = 0; z < providerGridSize; z++) {
         if (this.shouldAbortRequest(id)) {
            return null;
         }

         double blockZ = centerZ - radius + z * providerStep;

         for (int x = 0; x < providerGridSize; x++) {
            double blockX = centerX - radius + x * providerStep;
            boolean oceanZoom = this.useOceanZoom(blockX, blockZ, settings.worldScale(), previewResolutionMeters);
            ElevationDiagnostic diagnostic = this.elevationSource.samplePreviewDiagnostic(
               blockX,
               blockZ,
               settings.worldScale(),
               oceanZoom,
               settings.demSelection(),
               previewResolutionMeters
            );
            if (this.shouldAbortRequest(id)) {
               return null;
            }

            primaryCounts.merge(diagnostic.primaryProvider(), 1, Integer::sum);
            double displayResolutionMeters = diagnostic.displayResolutionMeters();
            if (Double.isFinite(displayResolutionMeters) && displayResolutionMeters > 0.0) {
               resolutionCounts.merge(resolutionBucketKey(displayResolutionMeters), 1, Integer::sum);
            }

            if (diagnostic.usesMultipleProviders()) {
               blendedMask |= diagnostic.providerMask() & ~diagnostic.primaryProvider().bit();
            }

            providerSampleCount++;
         }

         progressDone += providerGridSize;
         this.updateBuildStatus(id, buildBaseDone + progressDone, buildTotal, ACTIVITY_BUILD_INFO);
      }

      List<TerrainPreview.PreviewProviderShare> primaryProviders = new ArrayList<>(primaryCounts.size());
      for (DemUsage provider : DemUsage.values()) {
         Integer count = primaryCounts.get(provider);
         if (count != null && count > 0) {
            primaryProviders.add(new TerrainPreview.PreviewProviderShare(provider, count / (double)Math.max(1, providerSampleCount)));
         }
      }

      primaryProviders.sort((left, right) -> Double.compare(right.share(), left.share()));
      List<TerrainPreview.PreviewResolutionShare> primaryResolutions = new ArrayList<>(resolutionCounts.size());
      for (Map.Entry<Integer, Integer> entry : resolutionCounts.entrySet()) {
         int count = entry.getValue();
         if (count > 0) {
            primaryResolutions.add(
               new TerrainPreview.PreviewResolutionShare(resolutionBucketMeters(entry.getKey()), count / (double)Math.max(1, providerSampleCount))
            );
         }
      }

      primaryResolutions.sort((left, right) -> {
         int shareCompare = Double.compare(right.share(), left.share());
         return shareCompare != 0 ? shareCompare : Double.compare(left.resolutionMeters(), right.resolutionMeters());
      });
      return new TerrainPreview.PreviewInfo(
         List.copyOf(primaryProviders),
         List.copyOf(primaryResolutions),
         blendedMask,
         minElevation,
         maxElevation,
         minSurfaceY,
         maxSurfaceY
      );
   }

   private static int resolutionBucketKey(double resolutionMeters) {
      return Math.max(1, (int)Math.round(resolutionMeters * 100.0));
   }

   private static double resolutionBucketMeters(int bucketKey) {
      return bucketKey / 100.0;
   }

   private static double computeSlope(double[] heights, int size, int idx, double step) {
      int x = idx % size;
      int z = idx / size;
      int idxRight = x + 1 < size ? idx + 1 : idx;
      int idxDown = z + 1 < size ? idx + size : idx;
      double dx = Math.abs(heights[idxRight] - heights[idx]);
      double dz = Math.abs(heights[idxDown] - heights[idx]);
      double diff = Math.max(dx, dz);
      return step <= 0.0 ? diff : diff / step;
   }

   private static int previewSlopeDiff(double[] heights, int size, int idx, double step) {
      int x = idx % size;
      int z = idx / size;
      double center = heights[idx];
      double east = heights[z * size + Math.min(size - 1, x + 1)];
      double west = heights[z * size + Math.max(0, x - 1)];
      double north = heights[Math.max(0, z - 1) * size + x];
      double south = heights[Math.min(size - 1, z + 1) * size + x];
      double maxDiff = Math.max(Math.max(Math.abs(east - center), Math.abs(west - center)), Math.max(Math.abs(north - center), Math.abs(south - center)));
      double scaledStep = Math.max(4.0, step);
      return (int)Math.round(maxDiff * 4.0 / scaledStep);
   }

   private static int previewConvexity(double[] heights, int size, int idx, double step) {
      int x = idx % size;
      int z = idx / size;
      double center = heights[idx];
      double east = heights[z * size + Math.min(size - 1, x + 1)];
      double west = heights[z * size + Math.max(0, x - 1)];
      double north = heights[Math.max(0, z - 1) * size + x];
      double south = heights[Math.min(size - 1, z + 1) * size + x];
      double neighborAverage = (east + west + north + south) * 0.25;
      double scaledStep = Math.max(4.0, step);
      return (int)Math.round((neighborAverage - center) * 4.0 / scaledStep);
   }

   private boolean overlayTreePreviewMarkers(
      int requestId,
      long buildTotal,
      long buildBaseDone,
      int[] colors,
      float[] heightOffsets,
      int[] coverClasses,
      int[] visualCoverClasses,
      int coverSize,
      int coverStride,
      EarthGeneratorSettings settings,
      double minWorldX,
      double minWorldZ,
      double step
   ) {
      if (!(settings.worldScale() <= 0.0) && !(settings.worldScale() > 60.0) && !(step <= 0.0)) {
         int size = PREVIEW_GRID_SIZE;
         float density = treeMarkerDensity(settings.worldScale());
         if (!(density <= 0.0F)) {
            float treeHeight = treePreviewHeight(settings.worldScale());
            boolean expandedMarkers = settings.worldScale() <= 12.0;

            for (int z = 0; z < size; z++) {
               if (this.shouldAbortRequest(requestId)) {
                  return false;
               }

               int coverZ = Math.min(coverSize - 1, z / coverStride);

               for (int x = 0; x < size; x++) {
                  int coverX = Math.min(coverSize - 1, x / coverStride);
                  int coverIdx = coverX + coverZ * coverSize;
                  if (MountainSurfaceRules.isTreeMarkerCoverClass(coverClasses[coverIdx], visualCoverClasses[coverIdx])) {
                     long blockX = Mth.floor(minWorldX + x * step);
                     long blockZ = Mth.floor(minWorldZ + z * step);
                     if (!(hashToUnitDouble(blockX, blockZ) > density)) {
                        float centerBlend = 0.56F * (0.68F + (float)hashToUnitDouble(blockX, blockZ, 967164898L) * 0.32F);
                        float centerHeight = treeHeight * (0.7F + (float)hashToUnitDouble(blockX, blockZ, 1837846289L) * 0.45F);
                        paintTreePreviewPoint(colors, heightOffsets, size, x, z, centerBlend, centerHeight);
                        float ringBlend = centerBlend * (0.5F + (float)hashToUnitDouble(blockX, blockZ, 3257595197L) * 0.2F);
                        float ringHeight = centerHeight * (0.32F + (float)hashToUnitDouble(blockX, blockZ, 182545271L) * 0.2F);
                        paintTreeCardinalLobe(colors, heightOffsets, size, x, z, 1, 0, blockX, blockZ, 1090592539L, ringBlend, ringHeight);
                        paintTreeCardinalLobe(colors, heightOffsets, size, x, z, -1, 0, blockX, blockZ, 2553043431L, ringBlend, ringHeight);
                        paintTreeCardinalLobe(colors, heightOffsets, size, x, z, 0, 1, blockX, blockZ, 1601858105L, ringBlend, ringHeight);
                        paintTreeCardinalLobe(colors, heightOffsets, size, x, z, 0, -1, blockX, blockZ, 3074239821L, ringBlend, ringHeight);
                        if (expandedMarkers) {
                           float diagonalChance = 0.23F + (float)hashToUnitDouble(blockX, blockZ, 2765689601L) * 0.22F;
                           float diagonalBlend = centerBlend * 0.44F;
                           float diagonalHeight = centerHeight * 0.24F;
                           paintTreeDiagonalLobe(
                              colors, heightOffsets, size, x, z, 1, 1, blockX, blockZ, 3539041045L, diagonalChance, diagonalBlend, diagonalHeight
                           );
                           paintTreeDiagonalLobe(
                              colors, heightOffsets, size, x, z, 1, -1, blockX, blockZ, 431787567L, diagonalChance, diagonalBlend, diagonalHeight
                           );
                           paintTreeDiagonalLobe(
                              colors, heightOffsets, size, x, z, -1, 1, blockX, blockZ, 2120467777L, diagonalChance, diagonalBlend, diagonalHeight
                           );
                           paintTreeDiagonalLobe(
                              colors, heightOffsets, size, x, z, -1, -1, blockX, blockZ, 1254269913L, diagonalChance, diagonalBlend, diagonalHeight
                           );
                        }
                     }
                  }
               }

               this.updateBuildStatus(requestId, buildBaseDone + (long)(z + 1) * size, buildTotal, ACTIVITY_BUILD_TREES);
            }
         }
      }

      return !this.shouldAbortRequest(requestId);
   }

   private boolean applyFeatureHeightOffsets(int requestId, long buildTotal, long buildBaseDone, float[] heights, float[] offsets, int rowSize) {
      int count = Math.min(heights.length, offsets.length);
      if (count <= 0) {
         return !this.shouldAbortRequest(requestId);
      } else {
         int safeRowSize = Math.max(1, rowSize);

         for (int rowStart = 0; rowStart < count; rowStart += safeRowSize) {
            if (this.shouldAbortRequest(requestId)) {
               return false;
            }

            int rowEnd = Math.min(count, rowStart + safeRowSize);

            for (int i = rowStart; i < rowEnd; i++) {
               float offset = offsets[i];
               if (offset > 0.0F) {
                  heights[i] += offset;
               }
            }

            this.updateBuildStatus(requestId, buildBaseDone + rowEnd, buildTotal, ACTIVITY_BUILD_HEIGHT_OFFSETS);
         }

         return !this.shouldAbortRequest(requestId);
      }
   }

   private static float treeMarkerDensity(double worldScale) {
      if (worldScale <= 8.0) {
         return 0.085F;
      } else if (worldScale <= 20.0) {
         return 0.065F;
      } else {
         return worldScale <= 35.0 ? 0.05F : 0.04F;
      }
   }

   private static void paintTreePreviewPoint(int[] colors, float[] heightOffsets, int size, int x, int z, float blend, float height) {
      blendPreviewColor(colors, size, x, z, 2976308, blend);
      setPreviewHeightOffset(heightOffsets, size, x, z, height);
   }

   private static void paintTreeCardinalLobe(
      int[] colors, float[] heightOffsets, int size, int baseX, int baseZ, int dx, int dz, long blockX, long blockZ, long salt, float blend, float height
   ) {
      double chanceRoll = hashToUnitDouble(blockX, blockZ, salt);
      if (!(chanceRoll < 0.2)) {
         float lobeBlend = blend * (0.78F + (float)hashToUnitDouble(blockX + dx, blockZ + dz, salt ^ 23063L) * 0.34F);
         float lobeHeight = height * (0.75F + (float)hashToUnitDouble(blockX + dx, blockZ + dz, salt ^ 39985L) * 0.35F);
         paintTreePreviewPoint(colors, heightOffsets, size, baseX + dx, baseZ + dz, lobeBlend, lobeHeight);
      }
   }

   private static void paintTreeDiagonalLobe(
      int[] colors,
      float[] heightOffsets,
      int size,
      int baseX,
      int baseZ,
      int dx,
      int dz,
      long blockX,
      long blockZ,
      long salt,
      float chance,
      float blend,
      float height
   ) {
      if (!(hashToUnitDouble(blockX, blockZ, salt) >= chance)) {
         float lobeBlend = blend * (0.8F + (float)hashToUnitDouble(blockX + dx, blockZ + dz, salt ^ 10001L) * 0.3F);
         float lobeHeight = height * (0.7F + (float)hashToUnitDouble(blockX + dx, blockZ + dz, salt ^ 28215L) * 0.4F);
         paintTreePreviewPoint(colors, heightOffsets, size, baseX + dx, baseZ + dz, lobeBlend, lobeHeight);
      }
   }

   private static float treePreviewHeight(double worldScale) {
      if (worldScale <= 8.0) {
         return 0.034F;
      } else {
         return worldScale <= 25.0 ? 0.028F : 0.022F;
      }
   }

   private static void blendPreviewColor(int[] colors, int size, int x, int z, int targetColor, float amount) {
      if (x >= 0 && z >= 0 && x < size && z < size) {
         int idx = x + z * size;
         colors[idx] = blendColor(colors[idx], targetColor, amount);
      }
   }

   private static void setPreviewHeightOffset(float[] heightOffsets, int size, int x, int z, float height) {
      if (!(height <= 0.0F) && x >= 0 && z >= 0 && x < size && z < size) {
         int idx = x + z * size;
         heightOffsets[idx] = Math.max(heightOffsets[idx], height);
      }
   }

   private static double hashToUnitDouble(long x, long z) {
      return hashToUnitDouble(x, z, 1609587929392839161L);
   }

   private static double hashToUnitDouble(long x, long z, long salt) {
      long mixed = mix64(x * -7046029254386353131L ^ z * -4417276706812531889L ^ salt);
      return (mixed >>> 11 & 9007199254740991L) * 1.110223E-16F;
   }

   private static long mix64(long value) {
      long mixed = value ^ value >>> 33;
      mixed *= -49064778989728563L;
      mixed ^= mixed >>> 33;
      mixed *= -4265267296055464877L;
      return mixed ^ mixed >>> 33;
   }

   private void queueWaterPreviewPrefetch(double minWorldX, double minWorldZ, double maxWorldX, double maxWorldZ, double worldScale) {
      if (this.osmWaterSource.available() && worldScale > 0.0) {
         this.osmWaterSource.waterForAreaWithStatus(
            Mth.floor(minWorldX), Mth.floor(minWorldZ), Mth.ceil(maxWorldX), Mth.ceil(maxWorldZ), worldScale, PREVIEW_OSM_MARGIN_BLOCKS, OsmQueryMode.NON_BLOCKING
         );
      }
   }

   private boolean processWaterPreviewOverlay(
      int id,
      long buildTotal,
      long overlayBaseDone,
      int[] terrainColors,
      int[] detailColors,
      float[] detailHeightOffsets,
      EarthGeneratorSettings settings,
      double minWorldX,
      double minWorldZ,
      double maxWorldX,
      double maxWorldZ,
      double step
   ) {
      if (this.shouldAbortRequest(id) || !(settings.worldScale() > 0.0) || !(step > 0.0)) {
         return false;
      }

      final TerrainPreview.OsmOverlayStageProgress overlayProgress = new TerrainPreview.OsmOverlayStageProgress(
         id, buildTotal, overlayBaseDone, PREVIEW_WATER_OVERLAY_UNITS, 0.4F, 0.6F, ACTIVITY_OSM_WATER_FETCH, ACTIVITY_OSM_WATER_RASTER
      );
      overlayProgress.publish();
      final TerrainPreview.DownloadNetworkTracker networkTracker = new TerrainPreview.DownloadNetworkTracker();

      List<OsmWaterFeature> features;
      try (DownloadProgressReporter.Scope ignored = DownloadProgressReporter.push(new DownloadProgressReporter.Listener() {
            @Override
            public void onRequestStarted(long expectedBytes) {
               networkTracker.onRequestStarted(expectedBytes);
               overlayProgress.updateFetch(networkTracker.progress());
            }

            @Override
            public void onBytesRead(int bytes) {
               networkTracker.onBytesRead(bytes);
               overlayProgress.updateFetch(networkTracker.progress());
            }

            @Override
            public void onRequestFinished() {
               networkTracker.onRequestFinished();
               overlayProgress.updateFetch(networkTracker.progress());
            }
         })) {
         features = this.osmWaterSource
            .waterForAreaWithStatus(
               Mth.floor(minWorldX), Mth.floor(minWorldZ), Mth.ceil(maxWorldX), Mth.ceil(maxWorldZ), settings.worldScale(), PREVIEW_OSM_MARGIN_BLOCKS, OsmQueryMode.BLOCKING
            )
            .features();
      }

      if (this.shouldAbortRequest(id)) {
         return false;
      }

      overlayProgress.updateFetch(1.0F);
      if (features.isEmpty()) {
         overlayProgress.updateRaster(1.0F);
         overlayProgress.finish();
      } else {
         if (!this.overlayWaterPreviewColors(
            id, terrainColors, detailColors, detailHeightOffsets, settings, minWorldX, minWorldZ, step, features, overlayProgress::updateRaster
         )) {
            return false;
         }

         overlayProgress.finish();
      }

      return !this.shouldAbortRequest(id);
   }

   private boolean processRoadPreviewOverlay(
      int id,
      long buildTotal,
      long overlayBaseDone,
      int[] terrainColors,
      int[] detailColors,
      EarthGeneratorSettings settings,
      double centerX,
      double centerZ,
      double radius,
      double step
   ) {
      if (this.shouldAbortRequest(id)) {
         return false;
      }

      double minWorldX = centerX - radius;
      double minWorldZ = centerZ - radius;
      double maxWorldX = centerX + radius;
      double maxWorldZ = centerZ + radius;
      final TerrainPreview.OsmOverlayStageProgress overlayProgress = new TerrainPreview.OsmOverlayStageProgress(
         id, buildTotal, overlayBaseDone, 32768L, 0.35F, 0.65F, ACTIVITY_OSM_ROADS_FETCH, ACTIVITY_OSM_ROADS_RASTER
      );
      overlayProgress.publish();
      final TerrainPreview.DownloadNetworkTracker networkTracker = new TerrainPreview.DownloadNetworkTracker();

      List<RoadFeature> roads;
      try (DownloadProgressReporter.Scope ignored = DownloadProgressReporter.push(new DownloadProgressReporter.Listener() {
            @Override
            public void onRequestStarted(long expectedBytes) {
               networkTracker.onRequestStarted(expectedBytes);
               overlayProgress.updateFetch(networkTracker.progress());
            }

            @Override
            public void onBytesRead(int bytes) {
               networkTracker.onBytesRead(bytes);
               overlayProgress.updateFetch(networkTracker.progress());
            }

            @Override
            public void onRequestFinished() {
               networkTracker.onRequestFinished();
               overlayProgress.updateFetch(networkTracker.progress());
            }
         })) {
         roads = this.osmRoadSource
            .roadsForArea(Mth.floor(minWorldX), Mth.floor(minWorldZ), Mth.ceil(maxWorldX), Mth.ceil(maxWorldZ), settings.worldScale(), 64);
      }

      if (this.shouldAbortRequest(id)) {
         return false;
      }

      overlayProgress.updateFetch(1.0F);
      if (roads.isEmpty()) {
         overlayProgress.updateRaster(1.0F);
         overlayProgress.finish();
      } else {
         if (!this.overlayRoadPreviewColors(id, terrainColors, detailColors, settings, minWorldX, minWorldZ, step, roads, overlayProgress::updateRaster)) {
            return false;
         }

         overlayProgress.finish();
      }

      return !this.shouldAbortRequest(id);
   }

   private boolean processBuildingPreviewOverlay(
      int id,
      long buildTotal,
      long overlayBaseDone,
      float[] terrainHeights,
      int[] terrainColors,
      float[] detailHeights,
      int[] detailColors,
      double[] blockHeights,
      EarthGeneratorSettings settings,
      double centerX,
      double centerZ,
      double radius,
      double step,
      float heightCenter
   ) {
      if (this.shouldAbortRequest(id) || !(step > 0.0)) {
         return false;
      }

      double minWorldX = centerX - radius;
      double minWorldZ = centerZ - radius;
      double maxWorldX = centerX + radius;
      double maxWorldZ = centerZ + radius;
      final TerrainPreview.OsmOverlayStageProgress overlayProgress = new TerrainPreview.OsmOverlayStageProgress(
         id,
         buildTotal,
         overlayBaseDone,
         PREVIEW_BUILDING_OVERLAY_UNITS,
         0.35F,
         0.65F,
         ACTIVITY_OSM_BUILDINGS_FETCH,
         ACTIVITY_OSM_BUILDINGS_RASTER
      );
      overlayProgress.publish();
      final TerrainPreview.DownloadNetworkTracker networkTracker = new TerrainPreview.DownloadNetworkTracker();

      List<OsmBuildingFeature> features;
      try (DownloadProgressReporter.Scope ignored = DownloadProgressReporter.push(new DownloadProgressReporter.Listener() {
            @Override
            public void onRequestStarted(long expectedBytes) {
               networkTracker.onRequestStarted(expectedBytes);
               overlayProgress.updateFetch(networkTracker.progress());
            }

            @Override
            public void onBytesRead(int bytes) {
               networkTracker.onBytesRead(bytes);
               overlayProgress.updateFetch(networkTracker.progress());
            }

            @Override
            public void onRequestFinished() {
               networkTracker.onRequestFinished();
               overlayProgress.updateFetch(networkTracker.progress());
            }
         })) {
         features = this.osmBuildingSource.buildingsForArea(
            Mth.floor(minWorldX), Mth.floor(minWorldZ), Mth.ceil(maxWorldX), Mth.ceil(maxWorldZ), settings.worldScale(), PREVIEW_OSM_MARGIN_BLOCKS
         );
      }

      if (this.shouldAbortRequest(id)) {
         return false;
      }

      overlayProgress.updateFetch(1.0F);
      if (features.isEmpty()) {
         overlayProgress.updateRaster(1.0F);
         overlayProgress.finish();
      } else {
         if (!this.overlayBuildingPreviewMeshes(
            id, terrainHeights, terrainColors, detailHeights, detailColors, blockHeights, settings, minWorldX, minWorldZ, step, heightCenter, features, overlayProgress::updateRaster
         )) {
            return false;
         }

         overlayProgress.finish();
      }

      return !this.shouldAbortRequest(id);
   }

   private boolean overlayWaterPreviewColors(
      int requestId,
      int[] terrainColors,
      int[] detailColors,
      float[] detailHeightOffsets,
      EarthGeneratorSettings settings,
      double minWorldX,
      double minWorldZ,
      double step,
      List<OsmWaterFeature> features,
      TerrainPreview.RoadRasterProgress progress
   ) {
      if (this.shouldAbortRequest(requestId)) {
         return false;
      }

      progress.onProgress(0.0F);
      if (features.isEmpty() || !(settings.worldScale() > 0.0) || !(step > 0.0)) {
         progress.onProgress(1.0F);
         return !this.shouldAbortRequest(requestId);
      }

      int size = PREVIEW_GRID_SIZE;
      int area = size * size;
      byte[] waterKind = new byte[area];
      double worldScale = settings.worldScale();
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      double[] sampleWorldX = new double[size];
      double[] sampleWorldZ = new double[size];
      double[] sampleLon = new double[size];
      double[] sampleLat = new double[size];

      for (int i = 0; i < size; i++) {
         double worldX = minWorldX + i * step;
         double worldZ = minWorldZ + i * step;
         sampleWorldX[i] = worldX;
         sampleWorldZ[i] = worldZ;
         sampleLon[i] = worldX / blocksPerDegree;
         sampleLat[i] = EarthProjection.blockZToLat(worldZ, worldScale);
      }

      int totalFeatures = Math.max(1, features.size());
      int processedFeatures = 0;

      for (OsmWaterFeature feature : features) {
         if (this.shouldAbortRequest(requestId)) {
            return false;
         }

         if (!this.rasterizeWaterPreviewFeature(
            requestId, feature, sampleWorldX, sampleWorldZ, sampleLon, sampleLat, blocksPerDegree, worldScale, minWorldX, minWorldZ, step, size, waterKind
         )) {
            return false;
         }

         processedFeatures++;
         progress.onProgress((float)processedFeatures / (float)totalFeatures * 0.82F);
      }

      for (int z = 0; z < size; z++) {
         if (this.shouldAbortRequest(requestId)) {
            return false;
         }

         int row = z * size;

         for (int x = 0; x < size; x++) {
            int idx = row + x;
            byte kind = waterKind[idx];
            if (kind != 0) {
               int color = PREVIEW_FLAT_WATER_COLOR;
               terrainColors[idx] = color;
               detailColors[idx] = color;
               detailHeightOffsets[idx] = 0.0F;
            }
         }

         progress.onProgress(0.82F + (float)(z + 1) / (float)size * 0.18F);
      }

      progress.onProgress(1.0F);
      return !this.shouldAbortRequest(requestId);
   }

   private boolean overlayRoadPreviewColors(
      int requestId,
      int[] terrainColors,
      int[] detailColors,
      EarthGeneratorSettings settings,
      double minWorldX,
      double minWorldZ,
      double step,
      List<RoadFeature> roads,
      TerrainPreview.RoadRasterProgress progress
   ) {
      if (this.shouldAbortRequest(requestId)) {
         return false;
      }

      progress.onProgress(0.0F);
      double worldScale = settings.worldScale();
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      TerrainPreview.PreviewRoadWidths widths = previewRoadWidths(settings.worldScale());
      List<RoadFeature> main = new ArrayList<>();
      List<RoadFeature> normal = new ArrayList<>();
      List<RoadFeature> dirt = new ArrayList<>();

      for (RoadFeature road : roads) {
         if (this.shouldAbortRequest(requestId)) {
            return false;
         }

         switch (road.roadClass()) {
            case MAIN:
               main.add(road);
               break;
            case NORMAL:
               normal.add(road);
               break;
            case DIRT:
               dirt.add(road);
         }
      }

      int size = PREVIEW_GRID_SIZE;
      int area = size * size;
      byte[] selectedClass = new byte[area];
      boolean[] blocked = new boolean[area];
      TerrainPreview.RoadRasterProgressTracker rasterProgress = new TerrainPreview.RoadRasterProgressTracker(
         countRoadSegments(main) + countRoadSegments(normal) + countRoadSegments(dirt), size, progress
      );
      if (!this.rasterizeRoadPreviewClass(requestId, main, 1, widths.main(), blocksPerDegree, worldScale, minWorldX, minWorldZ, step, size, selectedClass, blocked, rasterProgress)) {
         return false;
      }

      if (!this.rasterizeRoadPreviewClass(requestId, normal, 2, widths.normal(), blocksPerDegree, worldScale, minWorldX, minWorldZ, step, size, selectedClass, blocked, rasterProgress)) {
         return false;
      }

      if (!this.rasterizeRoadPreviewClass(requestId, dirt, 3, widths.dirt(), blocksPerDegree, worldScale, minWorldX, minWorldZ, step, size, selectedClass, blocked, rasterProgress)) {
         return false;
      }

      for (int z = 0; z < size; z++) {
         if (this.shouldAbortRequest(requestId)) {
            return false;
         }

         int row = z * size;

         for (int x = 0; x < size; x++) {
            int idx = row + x;
            byte cls = selectedClass[idx];
            if (cls > 0) {
               int roadColor = switch (cls) {
                  case 1 -> 6712174;
                  case 2 -> 11776947;
                  default -> 9071178;
               };
               terrainColors[idx] = roadColor;
               detailColors[idx] = roadColor;
            }
         }

         rasterProgress.onPaintRow();
      }

      rasterProgress.finish();
      return !this.shouldAbortRequest(requestId);
   }

   private boolean overlayBuildingPreviewMeshes(
      int requestId,
      float[] terrainHeights,
      int[] terrainColors,
      float[] detailHeights,
      int[] detailColors,
      double[] blockHeights,
      EarthGeneratorSettings settings,
      double minWorldX,
      double minWorldZ,
      double step,
      float heightCenter,
      List<OsmBuildingFeature> features,
      TerrainPreview.RoadRasterProgress progress
   ) {
      if (this.shouldAbortRequest(requestId) || features.isEmpty()) {
         return false;
      }

      progress.onProgress(0.0F);
      int size = PREVIEW_GRID_SIZE;
      int area = size * size;
      double worldScale = settings.worldScale();
      Map<String, TerrainPreview.PreviewBuildingGroupScratch> groups = new HashMap<>();
      List<TerrainPreview.PreviewRasterizedBuildingFeature> partFeatures = new ArrayList<>();
      List<TerrainPreview.PreviewRasterizedBuildingFeature> footprintFeatures = new ArrayList<>();
      int totalFeatures = Math.max(1, features.size());
      int processedFeatures = 0;

      for (OsmBuildingFeature feature : features) {
         if (this.shouldAbortRequest(requestId)) {
            return false;
         }

         boolean groundContact = feature.kind() != OsmBuildingKind.PART || this.previewBuildingMinHeightBlocks(feature.minHeightMeters(), worldScale) <= 0;
         TerrainPreview.PreviewRasterizedBuildingFeature rasterized = this.rasterizePreviewBuildingFeature(
            feature, resolvePreviewBuildingGroupId(feature), groundContact, minWorldX, minWorldZ, step, size, worldScale
         );
         if (rasterized != null) {
            TerrainPreview.PreviewBuildingGroupScratch group = groups.computeIfAbsent(rasterized.groupId(), key -> new TerrainPreview.PreviewBuildingGroupScratch());
            for (int index : rasterized.occupiedIndices()) {
               int surface = (int)Math.round(blockHeights[index]);
               group.fallbackSamples().add(surface);
               if (groundContact) {
                  group.groundSamples().add(surface);
               }
            }

            if (feature.kind() == OsmBuildingKind.PART) {
               partFeatures.add(rasterized);
            } else {
               footprintFeatures.add(rasterized);
            }
         }

         processedFeatures++;
         if ((processedFeatures & 7) == 0 || processedFeatures == totalFeatures) {
            progress.onProgress((float)processedFeatures / (float)totalFeatures * 0.72F);
         }
      }

      if (partFeatures.isEmpty() && footprintFeatures.isEmpty()) {
         progress.onProgress(1.0F);
         return !this.shouldAbortRequest(requestId);
      }

      for (TerrainPreview.PreviewBuildingGroupScratch group : groups.values()) {
         IntArrayList samples = !group.groundSamples().isEmpty() ? group.groundSamples() : group.fallbackSamples();
         if (!samples.isEmpty()) {
            group.setBaseY(medianValue(samples));
         }
      }

      boolean[] partCoverage = new boolean[area];
      boolean[] terrainBuildingMask = new boolean[area];
      boolean[] detailBuildingMask = new boolean[area];
      float[] detailBuildingHeights = new float[area];
      Arrays.fill(detailBuildingHeights, Float.NEGATIVE_INFINITY);
      int totalRasterFeatures = Math.max(1, partFeatures.size() + footprintFeatures.size());
      int rasterizedCount = 0;

      for (TerrainPreview.PreviewRasterizedBuildingFeature rasterized : partFeatures) {
         if (this.shouldAbortRequest(requestId)) {
            return false;
         }

         TerrainPreview.PreviewBuildingGroupScratch group = groups.get(rasterized.groupId());
         if (group != null && group.baseY() != Integer.MIN_VALUE) {
            BuildingProfile profile = TellusBuildingProfiles.resolveProfile(rasterized.feature(), worldScale, null, worldScale == 1.0);
            BuildingBlueprint blueprint = this.previewBlueprintForFeature(rasterized.feature(), rasterized.groupId(), group.baseY(), profile, worldScale);
            TerrainPreview.PreviewBoundaryInfo boundaryInfo = this.computePreviewBoundaryInfo(rasterized);
            int previewColor = previewColorForProfile(profile, blueprint);

            for (int order = 0; order < rasterized.occupiedIndices().length; order++) {
               int index = rasterized.occupiedIndices()[order];
               int gridX = index % size;
               int gridZ = index / size;
               int roofY = blueprint.roofTopY((int)Math.round(minWorldX + gridX * step), (int)Math.round(minWorldZ + gridZ * step), boundaryInfo.boundaryDistance(order));
               float roofHeight = previewHeightForSurfaceY(roofY, settings, heightCenter);
               partCoverage[index] = true;
               detailBuildingMask[index] = true;
               terrainColors[index] = previewColor;
               detailColors[index] = previewColor;
               detailBuildingHeights[index] = Math.max(detailBuildingHeights[index], roofHeight);
               if (rasterized.groundContact()) {
                  terrainBuildingMask[index] = true;
               }
            }
         }

         rasterizedCount++;
         progress.onProgress(0.72F + (float)rasterizedCount / (float)totalRasterFeatures * 0.28F);
      }

      for (TerrainPreview.PreviewRasterizedBuildingFeature rasterized : footprintFeatures) {
         if (this.shouldAbortRequest(requestId)) {
            return false;
         }

         TerrainPreview.PreviewBuildingGroupScratch group = groups.get(rasterized.groupId());
         if (group != null && group.baseY() != Integer.MIN_VALUE) {
            BuildingProfile profile = TellusBuildingProfiles.resolveProfile(rasterized.feature(), worldScale, null, worldScale == 1.0);
            BuildingBlueprint blueprint = this.previewBlueprintForFeature(rasterized.feature(), rasterized.groupId(), group.baseY(), profile, worldScale);
            TerrainPreview.PreviewBoundaryInfo boundaryInfo = this.computePreviewBoundaryInfo(rasterized);
            int previewColor = previewColorForProfile(profile, blueprint);

            for (int order = 0; order < rasterized.occupiedIndices().length; order++) {
               int index = rasterized.occupiedIndices()[order];
               if (!partCoverage[index]) {
                  int gridX = index % size;
                  int gridZ = index / size;
                  int roofY = blueprint.roofTopY((int)Math.round(minWorldX + gridX * step), (int)Math.round(minWorldZ + gridZ * step), boundaryInfo.boundaryDistance(order));
                  float roofHeight = previewHeightForSurfaceY(roofY, settings, heightCenter);
                  terrainBuildingMask[index] = true;
                  detailBuildingMask[index] = true;
                  terrainColors[index] = previewColor;
                  detailColors[index] = previewColor;
                  detailBuildingHeights[index] = Math.max(detailBuildingHeights[index], roofHeight);
               }
            }
         }

         rasterizedCount++;
         progress.onProgress(0.72F + (float)rasterizedCount / (float)totalRasterFeatures * 0.28F);
      }

      for (int index = 0; index < area; index++) {
         if (terrainBuildingMask[index]) {
            terrainColors[index] = terrainColors[index] == 0 ? BUILDING_PREVIEW_COLOR : terrainColors[index];
         }

         if (detailBuildingMask[index]) {
            detailColors[index] = detailColors[index] == 0 ? BUILDING_PREVIEW_COLOR : detailColors[index];
            if (Float.isFinite(detailBuildingHeights[index])) {
               detailHeights[index] = Math.max(terrainHeights[index], detailBuildingHeights[index]);
            }
         }
      }

      progress.onProgress(1.0F);
      return !this.shouldAbortRequest(requestId);
   }

   private TerrainPreview.PreviewRasterizedBuildingFeature rasterizePreviewBuildingFeature(
      OsmBuildingFeature feature,
      String groupId,
      boolean groundContact,
      double minWorldX,
      double minWorldZ,
      double step,
      int size,
      double worldScale
   ) {
      if (!(worldScale > 0.0) || !(step > 0.0)) {
         return null;
      }

      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      double minBlockX = feature.minBlockX(blocksPerDegree);
      double maxBlockX = feature.maxBlockX(blocksPerDegree);
      double minBlockZ = feature.minBlockZ(worldScale);
      double maxBlockZ = feature.maxBlockZ(worldScale);
      int minGridX = (int)Math.floor((minBlockX - minWorldX) / step) - 1;
      int maxGridX = (int)Math.ceil((maxBlockX - minWorldX) / step) + 1;
      int minGridZ = (int)Math.floor((minBlockZ - minWorldZ) / step) - 1;
      int maxGridZ = (int)Math.ceil((maxBlockZ - minWorldZ) / step) + 1;
      if (maxGridX < 0 || maxGridZ < 0 || minGridX >= size || minGridZ >= size) {
         return null;
      }

      minGridX = Mth.clamp(minGridX, 0, size - 1);
      maxGridX = Mth.clamp(maxGridX, 0, size - 1);
      minGridZ = Mth.clamp(minGridZ, 0, size - 1);
      maxGridZ = Mth.clamp(maxGridZ, 0, size - 1);
      int localWidth = maxGridX - minGridX + 1;
      int localHeight = maxGridZ - minGridZ + 1;
      boolean[] occupiedMask = new boolean[Math.max(1, localWidth * localHeight)];
      IntArrayList occupied = new IntArrayList();

      for (int gridZ = minGridZ; gridZ <= maxGridZ; gridZ++) {
         double worldZ = minWorldZ + gridZ * step;

         for (int gridX = minGridX; gridX <= maxGridX; gridX++) {
            double worldX = minWorldX + gridX * step;
            if (feature.containsWorld(worldX, worldZ, worldScale)) {
               int localIndex = (gridX - minGridX) + (gridZ - minGridZ) * localWidth;
               occupiedMask[localIndex] = true;
               occupied.add(gridX + gridZ * size);
            }
         }
      }

      if (occupied.isEmpty()) {
         int fallbackX = Mth.clamp((int)Math.round(((minBlockX + maxBlockX) * 0.5 - minWorldX) / step), 0, size - 1);
         int fallbackZ = Mth.clamp((int)Math.round(((minBlockZ + maxBlockZ) * 0.5 - minWorldZ) / step), 0, size - 1);
         occupiedMask[Math.min(occupiedMask.length - 1, Math.max(0, (fallbackX - minGridX) + (fallbackZ - minGridZ) * localWidth))] = true;
         occupied.add(fallbackX + fallbackZ * size);
      }

      return new TerrainPreview.PreviewRasterizedBuildingFeature(
         feature, groupId, groundContact, minGridX, minGridZ, localWidth, localHeight, occupiedMask, occupied.toIntArray()
      );
   }

   private boolean rasterizeRoadPreviewClass(
      int requestId,
      List<RoadFeature> roads,
      int classId,
      int widthBlocks,
      double blocksPerDegree,
      double worldScale,
      double minWorldX,
      double minWorldZ,
      double step,
      int size,
      byte[] selectedClass,
      boolean[] blocked,
      TerrainPreview.RoadRasterProgressTracker progressTracker
   ) {
      if (!roads.isEmpty() && widthBlocks > 0 && !(step <= 0.0)) {
         int area = size * size;
         boolean[] candidates = new boolean[area];
         double halfWidth = Math.max(0.5, (widthBlocks - 1) * 0.5);
         double radiusSq = halfWidth * halfWidth + 1.0E-6;

         for (RoadFeature road : roads) {
            if (this.shouldAbortRequest(requestId)) {
               return false;
            }

            int points = road.pointCount();
            if (points >= 2) {
               double[] worldX = new double[points];
               double[] worldZ = new double[points];

               for (int i = 0; i < points; i++) {
                  worldX[i] = road.lonAt(i) * blocksPerDegree;
                  worldZ[i] = EarthProjection.latToBlockZ(road.latAt(i), worldScale);
               }

               for (int i = 1; i < points; i++) {
                  if (this.shouldAbortRequest(requestId)) {
                     return false;
                  }

                  double x1 = worldX[i - 1];
                  double z1 = worldZ[i - 1];
                  double x2 = worldX[i];
                  double z2 = worldZ[i];
                  double dx = x2 - x1;
                  double dz = z2 - z1;
                  double lenSq = dx * dx + dz * dz;
                  if (lenSq <= 1.0E-6) {
                     progressTracker.onSegmentProcessed();
                  } else {
                     int minGridX = Mth.clamp((int)Math.floor((Math.min(x1, x2) - halfWidth - minWorldX) / step), 0, size - 1);
                     int maxGridX = Mth.clamp((int)Math.floor((Math.max(x1, x2) + halfWidth - minWorldX) / step), 0, size - 1);
                     int minGridZ = Mth.clamp((int)Math.floor((Math.min(z1, z2) - halfWidth - minWorldZ) / step), 0, size - 1);
                     int maxGridZ = Mth.clamp((int)Math.floor((Math.max(z1, z2) + halfWidth - minWorldZ) / step), 0, size - 1);

                     for (int gz = minGridZ; gz <= maxGridZ; gz++) {
                        double sampleZ = minWorldZ + gz * step;
                        int row = gz * size;

                        for (int gx = minGridX; gx <= maxGridX; gx++) {
                           double sampleX = minWorldX + gx * step;
                           double t = ((sampleX - x1) * dx + (sampleZ - z1) * dz) / lenSq;
                           t = Mth.clamp(t, 0.0, 1.0);
                           double px = x1 + t * dx;
                           double pz = z1 + t * dz;
                           double ddx = sampleX - px;
                           double ddz = sampleZ - pz;
                           double distSq = ddx * ddx + ddz * ddz;
                           if (distSq <= radiusSq) {
                              candidates[row + gx] = true;
                           }
                        }
                     }

                     progressTracker.onSegmentProcessed();
                  }
               }
            }
         }

         for (int ix = 0; ix < area; ix++) {
            if (this.shouldAbortRequest(requestId)) {
               return false;
            }

            if (candidates[ix] && !blocked[ix]) {
               selectedClass[ix] = (byte)classId;
               blocked[ix] = true;
            }
         }
      }

      return !this.shouldAbortRequest(requestId);
   }

   private boolean rasterizeWaterPreviewFeature(
      int requestId,
      OsmWaterFeature feature,
      double[] sampleWorldX,
      double[] sampleWorldZ,
      double[] sampleLon,
      double[] sampleLat,
      double blocksPerDegree,
      double worldScale,
      double minWorldX,
      double minWorldZ,
      double step,
      int size,
      byte[] waterKind
   ) {
      byte kind = (byte)(feature.oceanHint() ? 2 : 1);
      return feature.lineGeometry()
         ? this.rasterizeWaterPreviewLineFeature(
            requestId, feature, kind, sampleWorldX, sampleWorldZ, blocksPerDegree, worldScale, minWorldX, minWorldZ, step, size, waterKind
         )
         : this.rasterizeWaterPreviewPolygonFeature(
            requestId, feature, kind, sampleLon, sampleLat, blocksPerDegree, worldScale, minWorldX, minWorldZ, step, size, waterKind
         );
   }

   private boolean rasterizeWaterPreviewPolygonFeature(
      int requestId,
      OsmWaterFeature feature,
      byte kind,
      double[] sampleLon,
      double[] sampleLat,
      double blocksPerDegree,
      double worldScale,
      double minWorldX,
      double minWorldZ,
      double step,
      int size,
      byte[] waterKind
   ) {
      double minBlockX = feature.minLon() * blocksPerDegree;
      double maxBlockX = feature.maxLon() * blocksPerDegree;
      double z0 = EarthProjection.latToBlockZ(feature.minLat(), worldScale);
      double z1 = EarthProjection.latToBlockZ(feature.maxLat(), worldScale);
      double minBlockZ = Math.min(z0, z1);
      double maxBlockZ = Math.max(z0, z1);
      int minGridX = (int)Math.floor((minBlockX - minWorldX) / step) - 1;
      int maxGridX = (int)Math.ceil((maxBlockX - minWorldX) / step) + 1;
      int minGridZ = (int)Math.floor((minBlockZ - minWorldZ) / step) - 1;
      int maxGridZ = (int)Math.ceil((maxBlockZ - minWorldZ) / step) + 1;
      if (maxGridX < 0 || maxGridZ < 0 || minGridX >= size || minGridZ >= size) {
         return true;
      }

      minGridX = Mth.clamp(minGridX, 0, size - 1);
      maxGridX = Mth.clamp(maxGridX, 0, size - 1);
      minGridZ = Mth.clamp(minGridZ, 0, size - 1);
      maxGridZ = Mth.clamp(maxGridZ, 0, size - 1);

      for (int gz = minGridZ; gz <= maxGridZ; gz++) {
         if (this.shouldAbortRequest(requestId)) {
            return false;
         }

         double lat = sampleLat[gz];
         int row = gz * size;

         for (int gx = minGridX; gx <= maxGridX; gx++) {
            if (feature.containsLonLat(sampleLon[gx], lat)) {
               markWaterPreviewCell(waterKind, row + gx, kind);
            }
         }
      }

      return true;
   }

   private boolean rasterizeWaterPreviewLineFeature(
      int requestId,
      OsmWaterFeature feature,
      byte kind,
      double[] sampleWorldX,
      double[] sampleWorldZ,
      double blocksPerDegree,
      double worldScale,
      double minWorldX,
      double minWorldZ,
      double step,
      int size,
      byte[] waterKind
   ) {
      double halfWidth = Math.max(0.5, step * 0.55);
      double radiusSq = halfWidth * halfWidth + 1.0E-6;

      for (int part = 0; part < feature.partCount(); part++) {
         int points = feature.pointCount(part);
         if (points < 2) {
            continue;
         }

         double previousX = feature.lonAt(part, 0) * blocksPerDegree;
         double previousZ = EarthProjection.latToBlockZ(feature.latAt(part, 0), worldScale);

         for (int point = 1; point < points; point++) {
            if (this.shouldAbortRequest(requestId)) {
               return false;
            }

            double currentX = feature.lonAt(part, point) * blocksPerDegree;
            double currentZ = EarthProjection.latToBlockZ(feature.latAt(part, point), worldScale);
            double dx = currentX - previousX;
            double dz = currentZ - previousZ;
            double lenSq = dx * dx + dz * dz;
            if (lenSq > 1.0E-6) {
               int minGridX = Mth.clamp((int)Math.floor((Math.min(previousX, currentX) - halfWidth - minWorldX) / step), 0, size - 1);
               int maxGridX = Mth.clamp((int)Math.floor((Math.max(previousX, currentX) + halfWidth - minWorldX) / step), 0, size - 1);
               int minGridZ = Mth.clamp((int)Math.floor((Math.min(previousZ, currentZ) - halfWidth - minWorldZ) / step), 0, size - 1);
               int maxGridZ = Mth.clamp((int)Math.floor((Math.max(previousZ, currentZ) + halfWidth - minWorldZ) / step), 0, size - 1);

               for (int gz = minGridZ; gz <= maxGridZ; gz++) {
                  double sampleZ = sampleWorldZ[gz];
                  int row = gz * size;

                  for (int gx = minGridX; gx <= maxGridX; gx++) {
                     double sampleX = sampleWorldX[gx];
                     double t = ((sampleX - previousX) * dx + (sampleZ - previousZ) * dz) / lenSq;
                     t = Mth.clamp(t, 0.0, 1.0);
                     double projX = previousX + t * dx;
                     double projZ = previousZ + t * dz;
                     double ddx = sampleX - projX;
                     double ddz = sampleZ - projZ;
                     if (ddx * ddx + ddz * ddz <= radiusSq) {
                        markWaterPreviewCell(waterKind, row + gx, kind);
                     }
                  }
               }
            }

            previousX = currentX;
            previousZ = currentZ;
         }
      }

      return true;
   }

   private static void markWaterPreviewCell(byte[] waterKind, int index, byte kind) {
      if (kind > waterKind[index]) {
         waterKind[index] = kind;
      }
   }

   private static TerrainPreview.PreviewRoadWidths previewRoadWidths(double worldScale) {
      double factor = roadWidthFactorForScale(worldScale);

      return new TerrainPreview.PreviewRoadWidths(
         widthForScale(RoadClass.MAIN.baseWidth(), factor),
         widthForScale(RoadClass.NORMAL.baseWidth(), factor),
         widthForScale(RoadClass.DIRT.baseWidth(), factor)
      );
   }

   private static int widthForScale(int baseWidth, double factor) {
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

   private static int countRoadSegments(List<RoadFeature> roads) {
      int count = 0;

      for (RoadFeature road : roads) {
         count += Math.max(0, road.pointCount() - 1);
      }

      return count;
   }

   private BuildingBlueprint previewBlueprintForFeature(OsmBuildingFeature feature, String groupId, int baseY, BuildingProfile profile, double worldScale) {
      int floorY = baseY + this.previewBuildingMinHeightBlocks(feature.minHeightMeters(), worldScale) + 1;
      int roofBaseY = Math.max(baseY + this.previewBuildingHeightBlocks(feature.heightMeters(), worldScale), floorY + profile.floorCount() * profile.storeyHeightBlocks());
      int topY = roofBaseY + Math.max(profile.parapetHeight(), profile.roofRise());
      return TellusBuildingBlueprints.create(groupId, feature, profile, 0L, baseY, floorY, roofBaseY, topY, List.of(), worldScale);
   }

   private TerrainPreview.PreviewBoundaryInfo computePreviewBoundaryInfo(TerrainPreview.PreviewRasterizedBuildingFeature rasterized) {
      int width = rasterized.width();
      int height = rasterized.height();
      int area = width * height;
      int[] distance = new int[area];
      Arrays.fill(distance, Integer.MAX_VALUE);
      ArrayDeque<Integer> queue = new ArrayDeque<>();
      boolean[] occupied = rasterized.occupiedMask();

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
            this.propagatePreviewBoundaryDistance(occupied, distance, queue, index - 1, nextDistance);
         }
         if (localX + 1 < width) {
            this.propagatePreviewBoundaryDistance(occupied, distance, queue, index + 1, nextDistance);
         }
         if (localZ > 0) {
            this.propagatePreviewBoundaryDistance(occupied, distance, queue, index - width, nextDistance);
         }
         if (localZ + 1 < height) {
            this.propagatePreviewBoundaryDistance(occupied, distance, queue, index + width, nextDistance);
         }
      }

      int[] ordered = new int[rasterized.occupiedIndices().length];
      for (int order = 0; order < rasterized.occupiedIndices().length; order++) {
         int globalIndex = rasterized.occupiedIndices()[order];
         int gridX = globalIndex % PREVIEW_GRID_SIZE;
         int gridZ = globalIndex / PREVIEW_GRID_SIZE;
         int localIndex = (gridX - rasterized.minGridX()) + (gridZ - rasterized.minGridZ()) * width;
         ordered[order] = localIndex >= 0 && localIndex < distance.length && distance[localIndex] != Integer.MAX_VALUE ? distance[localIndex] : 0;
      }

      return new TerrainPreview.PreviewBoundaryInfo(ordered);
   }

   private void propagatePreviewBoundaryDistance(boolean[] occupied, int[] distance, ArrayDeque<Integer> queue, int index, int nextDistance) {
      if (occupied[index] && nextDistance < distance[index]) {
         distance[index] = nextDistance;
         queue.add(index);
      }
   }

   private int previewBuildingHeightBlocks(double meters, double worldScale) {
      return Math.max(3, (int)Math.round(meters / worldScale));
   }

   private int previewBuildingMinHeightBlocks(double meters, double worldScale) {
      return Math.max(0, (int)Math.round(meters / worldScale));
   }

   private static float previewHeightForSurfaceY(int surfaceY, EarthGeneratorSettings settings, float center) {
      return (float)((surfaceY - settings.heightOffset()) / PREVIEW_RADIUS_BLOCKS * 0.7F) - center;
   }

   private static int medianValue(IntArrayList values) {
      int[] sorted = values.toIntArray();
      Arrays.sort(sorted);
      return sorted[sorted.length >> 1];
   }

   private static int previewColorForProfile(BuildingProfile profile, BuildingBlueprint blueprint) {
      return TellusBuildingStyles.previewColor(profile, blueprint.blueprintSeed());
   }

   private static String resolvePreviewBuildingGroupId(OsmBuildingFeature feature) {
      if (feature.kind() == OsmBuildingKind.PART) {
         String buildingId = feature.buildingId();
         return buildingId != null ? "part:" + buildingId : "part:" + feature.featureId();
      } else {
         return "footprint:" + feature.featureId();
      }
   }

   private void updateDownloadStatus(
      int id, long downloadDone, long downloadTotal, TerrainPreview.DownloadNetworkTracker tracker, String activity
   ) {
      float sampleProgress;
      if (downloadTotal <= 0L) {
         sampleProgress = 1.0F;
      } else {
         sampleProgress = Mth.clamp((float)downloadDone / (float)downloadTotal, 0.0F, 1.0F);
      }

      float networkProgress = tracker == null ? 0.0F : tracker.progress();
      this.updateStatus(id, TerrainPreview.PreviewStage.DOWNLOADING, Math.max(sampleProgress, networkProgress), activity);
   }

   private void updateBuildStatus(int id, long buildDone, long buildTotal, String activity) {
      float progress = buildTotal <= 0L ? 1.0F : Mth.clamp((float)buildDone / (float)buildTotal, 0.0F, 1.0F);
      this.updateStatus(id, TerrainPreview.PreviewStage.LOADING, progress, activity);
   }

   private static byte climateGroup(String koppen) {
      if (koppen != null && !koppen.isEmpty()) {
         char group = Character.toUpperCase(koppen.charAt(0));

         return switch (group) {
            case 'A' -> 1;
            case 'B' -> 2;
            case 'C' -> 3;
            case 'D' -> 4;
            case 'E' -> 5;
            default -> 0;
         };
      } else {
         return 0;
      }
   }

   private static int colorForPreview(
      int terrainCoverClass,
      int visualCoverClass,
      byte climateGroup,
      double elevationMeters,
      double terrainHeight,
      int slopeDiff,
      int convexity,
      double slope,
      int seaLevel,
      boolean esaWater,
      boolean fallbackWaterEnabled,
      boolean flattenWaterColor,
      boolean remaSnowTerrain
   ) {
      int surfaceCoverClass = MountainSurfaceRules.resolveSurfaceCoverClass(terrainCoverClass, visualCoverClass);
      double waterDepth = previewWaterDepthBlocks(surfaceCoverClass, esaWater, terrainHeight, seaLevel, fallbackWaterEnabled);
      if (waterDepth >= 0.0) {
         return flattenWaterColor ? PREVIEW_FLAT_WATER_COLOR : waterColorForDepth(waterDepth);
      } else {
         int heightAboveSea = (int)Math.round(terrainHeight) - seaLevel;
         MountainSurfaceRules.ApproximateSurface mountainSurface = MountainSurfaceRules.classifyApproximateSurface(
            terrainCoverClass, visualCoverClass, heightAboveSea, slopeDiff, convexity, remaSnowTerrain
         );
         if (mountainSurface.isSnow()) {
            return 16119285;
         } else if (mountainSurface.isMountain()) {
            return mountainPreviewColor(mountainSurface.palette());
         } else {
            int base = baseColorForCover(surfaceCoverClass, elevationMeters);
            int tinted = applyClimateTint(base, climateGroup, surfaceCoverClass);
            return applyRockTint(tinted, slope);
         }
      }
   }

   private static int mountainPreviewColor(MountainSurfaceRules.ApproximatePalette palette) {
      return switch (palette) {
         case DEEPSLATE -> 0x545A60;
         case DEEPSLATE_TALUS -> 0x666C71;
         case DEEPSLATE_SCREE -> 0x787D82;
         case WEATHERED_ANDESITE -> 0x898D92;
         case TUFF -> 0x7B8175;
         case SNOW -> 16119285;
         default -> 0x545A60;
      };
   }

   // Keep preview shading lightweight; the full water resolver is too expensive to run per pixel.
   private static double previewWaterDepthBlocks(int coverClass, boolean esaWater, double terrainHeight, int seaLevel, boolean enabled) {
      if (!enabled) {
         return -1.0;
      }

      if (esaWater || coverClass == ESA_WATER) {
         return Math.max(PREVIEW_INLAND_WATER_DEPTH_BLOCKS, seaLevel - terrainHeight);
      } else {
         return coverClass == ESA_NO_DATA && terrainHeight <= (double)seaLevel ? Math.max(0.0, seaLevel - terrainHeight) : -1.0;
      }
   }

   private static int baseColorForCover(int coverClass, double elevationMeters) {
      return switch (coverClass) {
         case 10 -> 4168275;
         case 20 -> 9413460;
         case 30 -> 8240987;
         case 40 -> 10991978;
         case 50 -> 9079434;
         case 60 -> 13087354;
         case 90 -> 4951130;
         case 95 -> 3107646;
         case 100 -> 8367723;
         default -> colorForElevation(elevationMeters);
      };
   }

   private static int waterColorForDepth(double depthBlocks) {
      if (depthBlocks <= 2.0) {
         return lerpColor(13218686, 4958171, depthBlocks / 2.0);
      } else if (depthBlocks <= 12.0) {
         return lerpColor(4958171, 1924763, (depthBlocks - 2.0) / 10.0);
      } else {
         return depthBlocks <= 80.0 ? lerpColor(1924763, 466493, (depthBlocks - 12.0) / 68.0) : 466493;
      }
   }

   private boolean useOceanZoom(double blockX, double blockZ, double worldScale, double previewResolutionMeters) {
      TellusLandMaskSource.LandMaskSample landSample = this.landMaskSource.sampleLandMask(blockX, blockZ, worldScale);
      int coverClass = landSample.known() && landSample.land()
         ? ESA_NO_DATA
         : this.landCoverSource.sampleCoverClass(blockX, blockZ, worldScale, previewResolutionMeters);
      return useOceanZoom(landSample, coverClass);
   }

   private static boolean useOceanZoom(TellusLandMaskSource.LandMaskSample landSample, int coverClass) {
      if (!landSample.known()) {
         return true;
      } else if (landSample.land()) {
         return false;
      } else {
         return coverClass == ESA_NO_DATA || coverClass == ESA_WATER;
      }
   }

   private static int applyClimateTint(int base, byte climateGroup, int coverClass) {
      float amount = climateBlendStrength(coverClass);
      if (!(amount <= 0.0F) && climateGroup != 0) {
         int tint = tintForClimate(climateGroup);
         if (tint == 0) {
            return base;
         } else {
            if (climateGroup == 5) {
               amount = Math.min(0.65F, amount + 0.2F);
            }

            return blendColor(base, tint, amount);
         }
      } else {
         return base;
      }
   }

   private static float climateBlendStrength(int coverClass) {
      return switch (coverClass) {
         case 10, 30, 40, 90, 100 -> 0.35F;
         case 20 -> 0.25F;
         case 60 -> 0.15F;
         case 95 -> 0.2F;
         default -> 0.0F;
      };
   }

   private static int tintForClimate(byte climateGroup) {
      return switch (climateGroup) {
         case 1 -> 3054935;
         case 2 -> 13676658;
         case 3 -> 8367971;
         case 4 -> 7176850;
         case 5 -> 13227746;
         default -> 0;
      };
   }

   private static int applyRockTint(int base, double slope) {
      if (slope <= 1.2) {
         return base;
      } else {
         double amount = (slope - 1.2) / 1.6;
         return blendColor(base, 10526880, (float)Mth.clamp(amount, 0.0, 1.0));
      }
   }

   private static int colorForElevation(double elevation) {
      if (elevation < 0.0) {
         double depth = -elevation;
         if (depth < 60.0) {
            return lerpColor(4958171, 1924763, depth / 60.0);
         } else {
            return depth < 2000.0 ? lerpColor(1924763, 466493, (depth - 60.0) / 1940.0) : 466493;
         }
      } else if (elevation < 120.0) {
         return lerpColor(13218686, 4168275, elevation / 120.0);
      } else if (elevation < 900.0) {
         return lerpColor(4168275, 8359757, (elevation - 120.0) / 780.0);
      } else if (elevation < 2200.0) {
         return lerpColor(8359757, 9206372, (elevation - 900.0) / 1300.0);
      } else if (elevation < 3800.0) {
         return lerpColor(9206372, 10526880, (elevation - 2200.0) / 1600.0);
      } else {
         return elevation < 5200.0 ? lerpColor(10526880, 16119285, (elevation - 3800.0) / 1400.0) : 16119285;
      }
   }

   private static int lerpColor(int a, int b, double t) {
      double clamped = Mth.clamp(t, 0.0, 1.0);
      int ar = a >> 16 & 0xFF;
      int ag = a >> 8 & 0xFF;
      int ab = a & 0xFF;
      int br = b >> 16 & 0xFF;
      int bg = b >> 8 & 0xFF;
      int bb = b & 0xFF;
      int r = (int)Math.round(ar + (br - ar) * clamped);
      int g = (int)Math.round(ag + (bg - ag) * clamped);
      int bch = (int)Math.round(ab + (bb - ab) * clamped);
      return r << 16 | g << 8 | bch;
   }

   private static int blendColor(int base, int tint, float amount) {
      if (amount <= 0.0F) {
         return base;
      } else {
         float clamped = Mth.clamp(amount, 0.0F, 1.0F);
         int br = base >> 16 & 0xFF;
         int bg = base >> 8 & 0xFF;
         int bb = base & 0xFF;
         int tr = tint >> 16 & 0xFF;
         int tg = tint >> 8 & 0xFF;
         int tb = tint & 0xFF;
         int r = Math.round(br + (tr - br) * clamped);
         int g = Math.round(bg + (tg - bg) * clamped);
         int b = Math.round(bb + (tb - bb) * clamped);
         return r << 16 | g << 8 | b;
      }
   }

   @Override
   public void close() {
      if (this.pending != null) {
         this.pending.cancel(true);
      }

      this.executor.shutdownNow();
   }

   private void updateStatus(int id, TerrainPreview.PreviewStage stage, float progress) {
      this.updateStatus(id, stage, progress, null);
   }

   private void updateStatus(int id, TerrainPreview.PreviewStage stage, float progress, String activity) {
      if (id == this.requestId.get()) {
         this.status.set(new TerrainPreview.PreviewStatus(stage, Mth.clamp(progress, 0.0F, 1.0F), activity));
      }
   }

   private boolean shouldAbortRequest(int id) {
      return Thread.currentThread().isInterrupted() || id != this.requestId.get();
   }

   @OnlyIn(Dist.CLIENT)
   private static final class DownloadNetworkTracker implements DownloadProgressReporter.Listener {
      private long knownBytesTotal;
      private long knownBytesRead;
      private long requestsStarted;
      private long requestsCompleted;
      private long unknownSizeRequests;

      @Override
      public void onRequestStarted(long expectedBytes) {
         this.requestsStarted++;
         if (expectedBytes > 0L) {
            this.knownBytesTotal += expectedBytes;
         } else {
            this.unknownSizeRequests++;
         }
      }

      @Override
      public void onBytesRead(int bytes) {
         if (bytes > 0) {
            this.knownBytesRead += bytes;
         }
      }

      @Override
      public void onRequestFinished() {
         this.requestsCompleted++;
      }

      private float progress() {
         float requestProgress = this.requestsStarted <= 0L ? 0.0F : Mth.clamp((float)this.requestsCompleted / (float)this.requestsStarted, 0.0F, 1.0F);
         float byteProgress = this.knownBytesTotal <= 0L ? 0.0F : Mth.clamp((float)this.knownBytesRead / (float)this.knownBytesTotal, 0.0F, 1.0F);
         if (this.knownBytesTotal > 0L && this.unknownSizeRequests <= 0L) {
            return Math.max(byteProgress, requestProgress);
         } else {
            return this.knownBytesTotal > 0L ? Mth.clamp(byteProgress * 0.85F + requestProgress * 0.15F, 0.0F, 1.0F) : requestProgress;
         }
      }
   }

   @OnlyIn(Dist.CLIENT)
   private final class OsmOverlayStageProgress {
      private final int requestId;
      private final long buildTotal;
      private final long overlayBaseDone;
      private final long overlayProcessUnits;
      private final float fetchWeight;
      private final float rasterWeight;
      private final String fetchActivity;
      private final String rasterActivity;
      private float fetchProgress;
      private float rasterProgress;
      private float emittedStageProgress;
      private long lastPublishMs;
      private String activity;

      private OsmOverlayStageProgress(
         int requestId,
         long buildTotal,
         long overlayBaseDone,
         long overlayProcessUnits,
         float fetchWeight,
         float rasterWeight,
         String fetchActivity,
         String rasterActivity
      ) {
         this.requestId = requestId;
         this.buildTotal = Math.max(1L, buildTotal);
         this.overlayBaseDone = overlayBaseDone;
         this.overlayProcessUnits = Math.max(1L, overlayProcessUnits);
         this.fetchWeight = Math.max(0.0F, fetchWeight);
         this.rasterWeight = Math.max(0.0F, rasterWeight);
         this.fetchActivity = fetchActivity;
         this.rasterActivity = rasterActivity;
         this.activity = fetchActivity;
      }

      private void updateFetch(float value) {
         this.fetchProgress = Math.max(this.fetchProgress, Mth.clamp(value, 0.0F, 1.0F));
         this.activity = this.fetchActivity;
         this.publish();
      }

      private void updateRaster(float value) {
         this.rasterProgress = Math.max(this.rasterProgress, Mth.clamp(value, 0.0F, 1.0F));
         this.activity = this.rasterActivity;
         this.publish();
      }

      private void finish() {
         this.fetchProgress = 1.0F;
         this.rasterProgress = 1.0F;
         this.activity = this.rasterActivity;
         this.publishNow();
      }

      private void publish() {
         long now = System.currentTimeMillis();
         float stageProgress = this.overlayStageProgress();
         if (!(stageProgress < this.emittedStageProgress + 0.0015F) || now - this.lastPublishMs >= 40L) {
            this.publishNow();
         }
      }

      private void publishNow() {
         this.lastPublishMs = System.currentTimeMillis();
         this.emittedStageProgress = Math.max(this.emittedStageProgress, this.overlayStageProgress());
         long overlayUnits = Math.round(this.emittedStageProgress * (float)this.overlayProcessUnits);
         float totalProgress = Mth.clamp((float)(this.overlayBaseDone + overlayUnits) / (float)this.buildTotal, 0.0F, 1.0F);
         TerrainPreview.this.updateStatus(this.requestId, TerrainPreview.PreviewStage.PROCESSING_OSM, totalProgress, this.activity);
      }

      private float overlayStageProgress() {
         float weightSum = this.fetchWeight + this.rasterWeight;
         return weightSum <= 0.0F
            ? Math.max(this.fetchProgress, this.rasterProgress)
            : Mth.clamp((this.fetchProgress * this.fetchWeight + this.rasterProgress * this.rasterWeight) / weightSum, 0.0F, 1.0F);
      }
   }

   @OnlyIn(Dist.CLIENT)
   private record PreviewBaseSnapshot(
      double worldScale,
      double terrestrialHeightScale,
      double oceanicHeightScale,
      int heightOffset,
      double spawnLatitude,
      double spawnLongitude,
      EarthGeneratorSettings.DemSelection demSelection,
      int size,
      int coverStride,
      int coverSize,
      int climateStride,
      int climateSize,
      double centerX,
      double centerZ,
      double radius,
      double step,
      double minWorldX,
      double minWorldZ,
      double maxWorldX,
      double maxWorldZ,
      double[] blockHeights,
      boolean[] esaWaterMask,
      double[] elevations,
      int[] coverClasses,
      int[] visualCoverClasses,
      byte[] climateGroups,
      float[] heights,
      float heightCenter,
      float[] xCoords,
      TerrainPreview.PreviewInfo info
   ) {
      private PreviewBaseSnapshot {
         demSelection = Objects.requireNonNull(demSelection, "demSelection");
         info = Objects.requireNonNull(info, "info");
      }
   }

   @OnlyIn(Dist.CLIENT)
   private static final class PreviewMesh {
      private final int size;
      private final int granularity;
      private final float[] terrainHeights;
      private final int[] terrainColors;
      private final float[] detailHeights;
      private final int[] detailColors;
      private final float[] axis;
      private final TerrainPreview.PreviewInfo info;

      private PreviewMesh(
         int size,
         int granularity,
         float[] terrainHeights,
         int[] terrainColors,
         float[] detailHeights,
         int[] detailColors,
         float[] axis,
         TerrainPreview.PreviewInfo info
      ) {
         this.size = size;
         this.granularity = granularity;
         this.terrainHeights = terrainHeights;
         this.terrainColors = terrainColors;
         this.detailHeights = detailHeights;
         this.detailColors = detailColors;
         this.axis = axis;
         this.info = Objects.requireNonNull(info, "info");
      }

      private float[] heightsFor(TerrainPreviewWidget.RenderMode renderMode) {
         return renderMode == TerrainPreviewWidget.RenderMode.FULL_DETAIL ? this.detailHeights : this.terrainHeights;
      }

      private int[] colorsFor(TerrainPreviewWidget.RenderMode renderMode) {
         return renderMode == TerrainPreviewWidget.RenderMode.FULL_DETAIL ? this.detailColors : this.terrainColors;
      }
   }

   @OnlyIn(Dist.CLIENT)
   public record PreviewInfo(
      List<TerrainPreview.PreviewProviderShare> primaryProviders,
      List<TerrainPreview.PreviewResolutionShare> primaryResolutions,
      int blendedProviderMask,
      double minElevationMeters,
      double maxElevationMeters,
      int minSurfaceY,
      int maxSurfaceY
   ) {
      public PreviewInfo {
         primaryProviders = List.copyOf(primaryProviders);
         primaryResolutions = List.copyOf(primaryResolutions);
      }

      public TerrainPreview.PreviewProviderShare mainProvider() {
         return this.primaryProviders.isEmpty() ? null : this.primaryProviders.get(0);
      }

      public boolean hasMixedPrimaryProviders() {
         return this.primaryProviders.size() > 1;
      }

      public TerrainPreview.PreviewResolutionShare mainResolution() {
         return this.primaryResolutions.isEmpty() ? null : this.primaryResolutions.get(0);
      }

      public boolean hasMixedPrimaryResolutions() {
         return this.primaryResolutions.size() > 1;
      }

      public boolean hasBlendedProviders() {
         return this.blendedProviderMask != 0;
      }

      public boolean minWithinLimits() {
         return this.minSurfaceY >= EarthGeneratorSettings.MIN_WORLD_Y;
      }

      public boolean maxWithinLimits() {
         return this.maxSurfaceY <= EarthGeneratorSettings.MAX_WORLD_Y;
      }

      public List<DemUsage> blendedProviders() {
         List<DemUsage> providers = new ArrayList<>();
         for (DemUsage provider : DemUsage.values()) {
            if ((this.blendedProviderMask & provider.bit()) != 0) {
               providers.add(provider);
            }
         }

         return providers;
      }
   }

   @OnlyIn(Dist.CLIENT)
   public record PreviewProviderShare(DemUsage provider, double share) {
      public PreviewProviderShare {
         provider = Objects.requireNonNull(provider, "provider");
      }
   }

   @OnlyIn(Dist.CLIENT)
   public record PreviewResolutionShare(double resolutionMeters, double share) {
   }

   @OnlyIn(Dist.CLIENT)
   private record PreviewRoadWidths(int main, int normal, int dirt) {
   }

   @OnlyIn(Dist.CLIENT)
   public static enum PreviewStage {
      DOWNLOADING,
      LOADING,
      PROCESSING_OSM,
      COMPLETE;
   }

   @OnlyIn(Dist.CLIENT)
   public record PreviewStatus(TerrainPreview.PreviewStage stage, float progress, String activity) {
      public PreviewStatus(TerrainPreview.PreviewStage stage, float progress, String activity) {
         Objects.requireNonNull(stage, "stage");
         this.stage = stage;
         this.progress = progress;
         this.activity = activity == null || activity.isBlank() ? null : activity;
      }
   }

   @OnlyIn(Dist.CLIENT)
   private static final class PreviewThreadFactory implements ThreadFactory {
      @Override
      public Thread newThread(Runnable runnable) {
         Thread thread = new Thread(runnable, "tellus-preview");
         thread.setDaemon(true);
         return thread;
      }
   }

   @OnlyIn(Dist.CLIENT)
   private static final class PreviewBuildingGroupScratch {
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

      private void setBaseY(int value) {
         this.baseY = value;
      }
   }

   @OnlyIn(Dist.CLIENT)
   private record PreviewRasterizedBuildingFeature(
      OsmBuildingFeature feature,
      String groupId,
      boolean groundContact,
      int minGridX,
      int minGridZ,
      int width,
      int height,
      boolean[] occupiedMask,
      int[] occupiedIndices
   ) {
      private PreviewRasterizedBuildingFeature {
         feature = Objects.requireNonNull(feature, "feature");
         groupId = Objects.requireNonNull(groupId, "groupId");
         occupiedMask = occupiedMask.clone();
         occupiedIndices = occupiedIndices.clone();
      }
   }

   @OnlyIn(Dist.CLIENT)
   private record PreviewBoundaryInfo(int[] boundaryDistance) {
      private PreviewBoundaryInfo {
         boundaryDistance = boundaryDistance.clone();
      }

      private int boundaryDistance(int order) {
         return this.boundaryDistance[order];
      }
   }

   @FunctionalInterface
   @OnlyIn(Dist.CLIENT)
   private interface RoadRasterProgress {
      void onProgress(float var1);
   }

   @OnlyIn(Dist.CLIENT)
   private static final class RoadRasterProgressTracker {
      private final int totalSegments;
      private final int totalPaintRows;
      private final TerrainPreview.RoadRasterProgress callback;
      private int processedSegments;
      private int paintedRows;
      private float emittedProgress;

      private RoadRasterProgressTracker(int totalSegments, int totalPaintRows, TerrainPreview.RoadRasterProgress callback) {
         this.totalSegments = Math.max(1, totalSegments);
         this.totalPaintRows = Math.max(1, totalPaintRows);
         this.callback = callback;
         this.emittedProgress = 0.0F;
      }

      private void onSegmentProcessed() {
         this.processedSegments = Math.min(this.totalSegments, this.processedSegments + 1);
         this.publish(false);
      }

      private void onPaintRow() {
         this.paintedRows = Math.min(this.totalPaintRows, this.paintedRows + 1);
         this.publish(false);
      }

      private void finish() {
         this.processedSegments = this.totalSegments;
         this.paintedRows = this.totalPaintRows;
         this.publish(true);
      }

      private void publish(boolean force) {
         float segmentProgress = Mth.clamp((float)this.processedSegments / this.totalSegments, 0.0F, 1.0F);
         float paintProgress = Mth.clamp((float)this.paintedRows / this.totalPaintRows, 0.0F, 1.0F);
         float progress = Mth.clamp(segmentProgress * 0.9F + paintProgress * 0.1F, 0.0F, 1.0F);
         if (force || !(progress < this.emittedProgress + 0.003F)) {
            this.emittedProgress = Math.max(this.emittedProgress, progress);
            this.callback.onProgress(this.emittedProgress);
         }
      }
   }

   private static void emitPreviewVertex(
      VertexConsumer consumer,
      Matrix4f modelView,
      Matrix4f projection,
      float worldX,
      float worldY,
      float worldZ,
      int rgb,
      float x0,
      float y0,
      float width,
      float height,
      Vector3f view,
      Vector3f projected
   ) {
      modelView.transformPosition(worldX, worldY, worldZ, view);
      projection.transformProject(view, projected);
      float screenX = x0 + (projected.x + 1.0F) * 0.5F * width;
      float screenY = y0 + (1.0F - projected.y) * 0.5F * height;
      int argb = 0xFF000000 | rgb & 16777215;
      consumer.addVertex(screenX, screenY, 0.0F).setColor(argb);
   }

   private static float computePreviewQuadShade(
      float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, Vector3f normal
   ) {
      float a1x = x1 - x0;
      float a1y = y1 - y0;
      float a1z = z1 - z0;
      float b1x = x2 - x0;
      float b1y = y2 - y0;
      float b1z = z2 - z0;
      float n1x = a1y * b1z - a1z * b1y;
      float n1y = a1z * b1x - a1x * b1z;
      float n1z = a1x * b1y - a1y * b1x;
      float a2x = x3 - x1;
      float a2y = y3 - y1;
      float a2z = z3 - z1;
      float b2x = x2 - x1;
      float b2y = y2 - y1;
      float b2z = z2 - z1;
      float n2x = a2y * b2z - a2z * b2y;
      float n2y = a2z * b2x - a2x * b2z;
      float n2z = a2x * b2y - a2y * b2x;
      normal.set(n1x + n2x, n1y + n2y, n1z + n2z);
      if (normal.lengthSquared() < 1.0E-9F) {
         normal.set(n1x, n1y, n1z);
      }

      if (normal.y < 0.0F) {
         normal.negate();
      }

      normal.normalize();
      float shade = Mth.clamp(normal.dot(LIGHT_DIR), 0.0F, 1.0F);
      shade = 0.45F + shade * 0.55F;
      return Math.round(shade * 16.0F) / 16.0F;
   }

   private static int applyPreviewShade(int rgb, float shade) {
      int r = rgb >> 16 & 0xFF;
      int g = rgb >> 8 & 0xFF;
      int b = rgb & 0xFF;
      r = Mth.clamp(Math.round(r * shade), 0, 255);
      g = Mth.clamp(Math.round(g * shade), 0, 255);
      b = Mth.clamp(Math.round(b * shade), 0, 255);
      return r << 16 | g << 8 | b;
   }

   private static void sortPreviewQuads(int[] quadTopLeft, float[] quadDepth, int left, int right) {
      int i = left;
      int j = right;
      float pivot = quadDepth[left + right >>> 1];

      while (i <= j) {
         while (quadDepth[i] < pivot) {
            i++;
         }

         while (quadDepth[j] > pivot) {
            j--;
         }

         if (i <= j) {
            swapPreviewQuads(quadTopLeft, quadDepth, i, j);
            i++;
            j--;
         }
      }

      if (left < j) {
         sortPreviewQuads(quadTopLeft, quadDepth, left, j);
      }

      if (i < right) {
         sortPreviewQuads(quadTopLeft, quadDepth, i, right);
      }
   }

   private static void swapPreviewQuads(int[] quadTopLeft, float[] quadDepth, int i, int j) {
      int tempIndex = quadTopLeft[i];
      quadTopLeft[i] = quadTopLeft[j];
      quadTopLeft[j] = tempIndex;
      float tempDepth = quadDepth[i];
      quadDepth[i] = quadDepth[j];
      quadDepth[j] = tempDepth;
   }
}
