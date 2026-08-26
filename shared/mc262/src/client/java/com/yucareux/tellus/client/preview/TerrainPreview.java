package com.yucareux.tellus.client.preview;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.mixin.client.GuiGraphicsAccessor;
import com.yucareux.tellus.world.data.biome.BiomeClassification;
import com.yucareux.tellus.world.data.biome.BiomeClassificationProviders;
import com.yucareux.tellus.world.data.canopy.TellusCanopyHeightSource;
import com.yucareux.tellus.world.data.cover.TellusLandCoverSource;
import com.yucareux.tellus.world.data.elevation.ElevationGridRepair;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource.DemUsage;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource.ElevationDiagnostic;
import com.yucareux.tellus.world.data.koppen.TellusKoppenSource;
import com.yucareux.tellus.world.data.mask.TellusLandMaskSource;
import com.yucareux.tellus.world.data.osm.OsmBuildingFeature;
import com.yucareux.tellus.world.data.osm.OsmBuildingKind;
import com.yucareux.tellus.world.data.osm.BridgeSupportLayout;
import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.world.data.osm.RoadAreaFeature;
import com.yucareux.tellus.world.data.osm.RoadClass;
import com.yucareux.tellus.world.data.osm.RoadFeature;
import com.yucareux.tellus.world.data.osm.RoadSurfaceStyle;
import com.yucareux.tellus.world.data.osm.TellusOsmBuildingSource;
import com.yucareux.tellus.world.data.osm.TellusOsmRoadSource;
import com.yucareux.tellus.world.data.osm.TellusOsmWaterSource;
import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.resolve.ResolveEcoregion;
import com.yucareux.tellus.world.data.resolve.TellusResolveSource;
import com.yucareux.tellus.world.data.source.DownloadProgressReporter;
import com.yucareux.tellus.worldgen.building.BuildingBlueprint;
import com.yucareux.tellus.worldgen.building.BuildingProfile;
import com.yucareux.tellus.worldgen.building.TellusBuildingBlueprints;
import com.yucareux.tellus.worldgen.building.TellusBuildingProfiles;
import com.yucareux.tellus.worldgen.building.TellusBuildingStyles;
import com.yucareux.tellus.worldgen.BadlandsTerrainPolicy;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.MountainSurfaceRules;
import com.yucareux.tellus.worldgen.EarthProjection;
import com.yucareux.tellus.worldgen.WorldProjection;
import com.yucareux.tellus.worldgen.TerrainHeightTransform;
import com.yucareux.tellus.worldgen.DeepslateSlopePolicy;
import com.yucareux.tellus.worldgen.SnowSlopePolicy;
import com.yucareux.tellus.worldgen.TerrainSlopePolicy;
import com.yucareux.tellus.worldgen.OceanCoverageUnavailableException;
import com.yucareux.tellus.worldgen.TellusWorldgenSources;
import com.yucareux.tellus.worldgen.WaterSurfaceResolver;
import com.yucareux.tellus.worldgen.WaterfallNoCarveZone;
import com.yucareux.tellus.worldgen.arnis.ArnisBuildingRules;
import com.yucareux.tellus.worldgen.tree.TellusProceduralTreeGenerator;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class TerrainPreview implements AutoCloseable {
   private static final int PREVIEW_GRID_SIZE = 513;
   private static final double PREVIEW_RADIUS_BLOCKS = 512.0;
   private static final float PREVIEW_VERTICAL_BLOCK_SCALE = 0.7F / (float)PREVIEW_RADIUS_BLOCKS;
   private static final float PREVIEW_CAMERA_FOV_DEGREES = 36.0F;
   private static final int PREVIEW_INFO_PROVIDER_GRID_SIZE = 25;
   private static final int PREVIEW_OSM_MARGIN_BLOCKS = 32;
   private static final int PREVIEW_ELEVATION_PREFETCH_RADIUS = 1;
   private static final int PREVIEW_ELEVATION_MAX_ATTEMPTS = 3;
   private static final int PREVIEW_LAND_COVER_PREFETCH_RADIUS = 1;
   private static final int PREVIEW_LAND_MASK_PREFETCH_RADIUS = 1;
   private static final int PREVIEW_OSM_PREFETCH_RADIUS = 1;
   private static final int PREVIEW_CANOPY_MAX_TILES = 32;
   private static final long PREVIEW_WATER_OVERLAY_UNITS = 24576L;
   private static final long PREVIEW_ROAD_OVERLAY_UNITS = 32768L;
   private static final long PREVIEW_BUILDING_OVERLAY_UNITS = 24576L;
   private static final String ACTIVITY_DOWNLOAD_ELEVATION = "tellus.preview.loading.sample_elevation";
   private static final String ACTIVITY_DOWNLOAD_LAND_COVER = "tellus.preview.loading.sample_land_cover";
   private static final String ACTIVITY_DOWNLOAD_CLIMATE = "tellus.preview.loading.sample_climate";
   private static final String ACTIVITY_DOWNLOAD_CANOPY = "tellus.preview.loading.sample_canopy";
   private static final String ACTIVITY_BUILD_HEIGHTS = "tellus.preview.loading.normalize_heights";
   private static final String ACTIVITY_BUILD_CENTER = "tellus.preview.loading.center_terrain";
   private static final String ACTIVITY_BUILD_COLORS = "tellus.preview.loading.color_terrain";
   private static final String ACTIVITY_BUILD_TREES = "tellus.preview.loading.place_trees";
   private static final String ACTIVITY_BUILD_HEIGHT_OFFSETS = "tellus.preview.loading.apply_feature_heights";
   private static final String ACTIVITY_BUILD_INFO = "tellus.preview.loading.summarize_dem";
   private static final String ACTIVITY_OSM_WATER_FETCH = "tellus.preview.loading.fetch_water";
   private static final String ACTIVITY_OSM_WATER_RASTER = "tellus.preview.loading.rasterize_water";
   private static final String ACTIVITY_OSM_ROADS_FETCH = "tellus.preview.loading.fetch_roads";
   private static final String ACTIVITY_OSM_ROADS_RASTER = "tellus.preview.loading.rasterize_roads";
   private static final String ACTIVITY_OSM_BUILDINGS_FETCH = "tellus.preview.loading.fetch_buildings";
   private static final String ACTIVITY_OSM_BUILDINGS_RASTER = "tellus.preview.loading.rasterize_buildings";
   private static final long STATUS_PUBLISH_INTERVAL_MS = 80L;
   private static final int ESA_NO_DATA = 0;
   private static final int ESA_WATER = 80;
   private static final double PREVIEW_INLAND_WATER_DEPTH_BLOCKS = 6.0;
   private static final double PREVIEW_OCEAN_LINEAR_DEPTH_BLOCKS = 48.0;
   private static final double PREVIEW_OCEAN_DEPTH_COMPRESSION_BLOCKS = 24.0;
   private static final double PREVIEW_OCEAN_MAX_DEPTH_BLOCKS = 128.0;
   private static final int PREVIEW_FLAT_WATER_COLOR = waterColorForDepth(PREVIEW_INLAND_WATER_DEPTH_BLOCKS);
   private static final int PREVIEW_ROAD_MARKING_COLOR = 0xF2F4F1;
   private static final int PREVIEW_ROAD_PAVED_DARK_COLOR = 0x34393B;
   private static final int PREVIEW_ROAD_PAVED_LIGHT_COLOR = 0x777D7F;
   private static final int PREVIEW_ROAD_PAVED_SMOOTH_COLOR = 0x9EA3A0;
   private static final int PREVIEW_ROAD_PEDESTRIAN_COLOR = 0xB6BAB3;
   private static final int PREVIEW_ROAD_GRAVEL_COLOR = 0x77756C;
   private static final int PREVIEW_ROAD_DIRT_COLOR = 0x7D694D;
   private static final int PREVIEW_ROAD_COBBLESTONE_COLOR = 0x696D68;
   private static final int PREVIEW_ROAD_STONE_PAVERS_COLOR = 0x8F928B;
   private static final int PREVIEW_ROAD_BRICK_COLOR = 0x8A5145;
   private static final int PREVIEW_ROAD_SAND_COLOR = 0xB7A46C;
   private static final int PREVIEW_ROAD_WOOD_COLOR = 0x8B6A43;
   private static final int PREVIEW_ROAD_CONCRETE_COLOR = 0xA4A7A2;
   private static final int BUILDING_PREVIEW_COLOR = 10000536;
   private static final int PREVIEW_SKY_TOP_COLOR = 0xFF83B4CC;
   private static final int PREVIEW_SKY_HORIZON_COLOR = 0xFFC5D3C2;
   private static final int PREVIEW_SKY_GROUND_COLOR = 0xFF66745F;
   private static final int PREVIEW_FOG_COLOR = 0xC5D3C2;
   private static final int PREVIEW_TREE_TRUNK_COLOR = 0x6B4A2D;
   private static final int PREVIEW_TREE_LEAF_COLOR = 0x4C9141;
   private static final int PREVIEW_DEEPSLATE_COLOR = 0x505456;
   private static final int PREVIEW_BADLANDS_MAX_WALL_BANDS = 96;
   private static final int PREVIEW_STONE_FILL_COLOR = 0x73787B;
   private static final int PREVIEW_STONE_BOTTOM_COLOR = 0x4B5053;
   private static final int PREVIEW_DEEP_WATER_VOLUME_COLOR = 0x0A416B;
   private static final int PREVIEW_CLOUD_TOP_COLOR = 0xB8F4F5F2;
   private static final int PREVIEW_CLOUD_SIDE_COLOR = 0xA4D8DEDE;
   private static final int PREVIEW_CLOUD_BOTTOM_COLOR = 0x8CC1C9CA;
   private static final Identifier PREVIEW_SUN_TEXTURE = Identifier.fromNamespaceAndPath("minecraft", "textures/environment/celestial/sun.png");
   private static final int PREVIEW_SUN_REFLECTION_COLOR = 0xFFF0BC;
   private static final float PREVIEW_SUN_X = 0.0F;
   private static final float PREVIEW_SUN_Y = -0.1F;
   private static final float PREVIEW_SUN_Z = -1.0F;
   private static final int PREVIEW_CLOUD_GRID_SIZE = 40;
   private static final float PREVIEW_CLOUD_MIN = -0.98F;
   private static final float PREVIEW_CLOUD_MAX = 0.98F;
   private static final double PREVIEW_CLOUD_CLEARANCE_BLOCKS = 96.0;
   private static final float PREVIEW_SOLID_BASE_DEPTH_CELLS = 8.0F;
   private static final float PREVIEW_VERTICAL_CELL_RATIO = 0.7F;
   private static final int PREVIEW_SHADOW_STEPS = 28;
   private static final Vector3f LIGHT_DIR = new Vector3f(-0.48F, 0.78F, -0.4F).normalize();
   private static final RenderPipeline PREVIEW_SUN_PIPELINE = RenderPipeline.builder()
      .withLocation("pipeline/tellus_preview_sun")
      .withBindGroupLayout(BindGroupLayouts.GLOBALS)
      .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
      .withVertexShader("core/position_tex_color")
      .withFragmentShader("core/position_tex_color")
      .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
      .withColorTargetState(new ColorTargetState(BlendFunction.OVERLAY))
      .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
      .withPrimitiveTopology(PrimitiveTopology.QUADS)
      .build();
   private static final RenderPipeline PREVIEW_PIPELINE = RenderPipeline.builder()
      .withLocation("pipeline/tellus_terrain_preview")
      .withBindGroupLayout(BindGroupLayouts.GLOBALS)
      .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
      .withVertexShader("core/gui")
      .withFragmentShader("core/gui")
      .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
      .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
      .withPrimitiveTopology(PrimitiveTopology.QUADS)
      .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
      .withCull(false)
      .build();
   private final TellusElevationSource elevationSource = new TellusElevationSource();
   private final TellusLandCoverSource landCoverSource = new TellusLandCoverSource();
   private final TellusCanopyHeightSource canopyHeightSource = TellusWorldgenSources.canopyHeight();
   private final TellusKoppenSource koppenSource = new TellusKoppenSource();
   private final TellusLandMaskSource landMaskSource = new TellusLandMaskSource();
   private final TellusResolveSource resolveSource = TellusResolveSource.shared();
   private final TellusOsmRoadSource osmRoadSource = TellusWorldgenSources.osmRoads();
   private final TellusOsmBuildingSource osmBuildingSource = TellusWorldgenSources.osmBuildings();
   private final TellusOsmWaterSource osmWaterSource = TellusWorldgenSources.osmWater();
   private final ExecutorService executor;
   private final AtomicInteger requestId = new AtomicInteger();
   private final AtomicReference<TerrainPreview.PreviewStatus> status = new AtomicReference<>(
      new TerrainPreview.PreviewStatus(TerrainPreview.PreviewStage.COMPLETE, 1.0F, null, null)
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
      GuiGraphicsExtractor graphics,
      int x,
      int y,
      int width,
      int height,
      float rotationX,
      float rotationY,
      float cameraDistance,
      TerrainPreviewWidget.RenderMode renderMode,
      boolean cloudsVisible
   ) {
      if (width <= 0 || height <= 0) {
         return;
      }

      renderPreviewSky(graphics, x, y, width, height);
      renderPreviewSun(graphics, x, y, width, height, rotationX, rotationY);
      TerrainPreview.PreviewMesh preview = this.mesh;
      if (preview == null) {
         return;
      }

      Matrix4f modelView = buildModelView(rotationX, rotationY, cameraDistance);
      Matrix4f projection = buildProjection(width, height);
      Matrix3x2f pose = new Matrix3x2f(graphics.pose());
      ScreenRectangle rawBounds = new ScreenRectangle(x, y, width, height);
      ScreenRectangle bounds = rawBounds.transformAxisAligned(pose);
      GuiRenderState renderState = ((GuiGraphicsAccessor)graphics).tellus$getGuiRenderState();
      renderState.addGuiElement(
         new TerrainPreview.TerrainPreviewRenderState(preview, renderMode, cloudsVisible, modelView, projection, pose, rawBounds, bounds)
      );
   }

   private static void renderPreviewSky(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
      int horizonY = y + Math.max(1, Math.round(height * 0.72F));
      graphics.fillGradient(x, y, x + width, horizonY, PREVIEW_SKY_TOP_COLOR, PREVIEW_SKY_HORIZON_COLOR);
      graphics.fillGradient(x, horizonY, x + width, y + height, PREVIEW_SKY_HORIZON_COLOR, PREVIEW_SKY_GROUND_COLOR);
   }

   private static void renderPreviewSun(
      GuiGraphicsExtractor graphics, int x, int y, int width, int height, float rotationX, float rotationY
   ) {
      Vector3f viewDirection = new Vector3f(PREVIEW_SUN_X, PREVIEW_SUN_Y, PREVIEW_SUN_Z).normalize();
      new Matrix4f().identity().rotateX(rotationX).rotateY(rotationY).transformDirection(viewDirection);
      float depth = -viewDirection.z;
      if (depth <= 0.01F) {
         return;
      }

      float tanHalfFov = (float)Math.tan(Math.toRadians(PREVIEW_CAMERA_FOV_DEGREES * 0.5F));
      float aspect = (float)width / height;
      float normalizedX = viewDirection.x / (depth * tanHalfFov * aspect);
      float normalizedY = viewDirection.y / (depth * tanHalfFov);
      int sunSize = Math.max(1, Math.min(Math.min(width, height), Mth.clamp(Math.round(Math.min(width, height) * 0.11F), 24, 72)));
      int sunX = x + Math.round((normalizedX * 0.5F + 0.5F) * width) - sunSize / 2;
      int sunY = y + Math.round((0.5F - normalizedY * 0.5F) * height) - sunSize / 2;
      if (sunX + sunSize <= x || sunX >= x + width || sunY + sunSize <= y || sunY >= y + height) {
         return;
      }

      graphics.enableScissor(x, y, x + width, y + height);
      graphics.blit(PREVIEW_SUN_PIPELINE, PREVIEW_SUN_TEXTURE, sunX, sunY, 0.0F, 0.0F, sunSize, sunSize, 32, 32);
      graphics.disableScissor();
   }

   private static Matrix4f buildProjection(int width, int height) {
      float aspect = (float)width / height;
      return new Matrix4f().setPerspective((float)Math.toRadians(PREVIEW_CAMERA_FOV_DEGREES), aspect, 0.05F, 100.0F);
   }

   private static Matrix4f buildModelView(float rotationX, float rotationY, float cameraDistance) {
      return new Matrix4f().identity().translate(0.0F, 0.0F, -cameraDistance).rotateX(rotationX).rotateY(rotationY);
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
      boolean[] missingElevationMask = new boolean[size * size];
      boolean[] oceanFallbackMask = new boolean[size * size];
      boolean[] mapterhornLandOverride = new boolean[size * size];
      int coverStride = 2;
      int coverSize = (size + coverStride - 1) / coverStride;
      int climateStride = 4;
      int climateSize = (size + climateStride - 1) / climateStride;
      long gridArea = (long)size * size;
      long downloadDone = 0L;
      boolean roadsPreviewEnabled = settings.enableRoads() && settings.worldScale() > 0.0 && settings.worldScale() <= 15.0;
      boolean buildingsPreviewEnabled = settings.enableBuildings() && settings.worldScale() > 0.0 && settings.worldScale() <= 15.0 && this.osmBuildingSource.available();
      double worldScale = settings.worldScale();
      WorldProjection projection = settings.projection();
      boolean useVisualCover = worldScale > 0.0 && worldScale < 10.0;
      long terrainDownloadUnits = gridArea;
      long coverDownloadUnits = (long)coverSize * coverSize * (useVisualCover ? 2L : 1L);
      long climateDownloadUnits = (long)climateSize * climateSize;
      boolean waterPreviewEnabled = settings.enableWater() && worldScale > 0.0 && this.osmWaterSource.available();
      double centerX = projection.lonToBlockX(settings.spawnLongitude());
      double centerZ = projection.latToBlockZ(settings.spawnLatitude());
      double radius = PREVIEW_RADIUS_BLOCKS;
      double step = radius * 2.0 / (size - 1);
      double previewResolutionMeters = Math.max(worldScale, step * worldScale);
      double minWorldX = centerX - radius;
      double minWorldZ = centerZ - radius;
      double maxWorldX = centerX + radius;
      double maxWorldZ = centerZ + radius;
      int previewMinBlockX = Mth.floor(minWorldX);
      int previewMinBlockZ = Mth.floor(minWorldZ);
      int previewMaxBlockX = Mth.ceil(maxWorldX);
      int previewMaxBlockZ = Mth.ceil(maxWorldZ);
      boolean canopyPreviewEnabled = settings.customTrees() && worldScale > 0.0 && worldScale <= 60.0;
      double canopyPreviewResolutionMeters = canopyPreviewEnabled
         ? TellusCanopyHeightSource.resolutionForAreaTileBudget(
            previewMinBlockX,
            previewMinBlockZ,
            previewMaxBlockX,
            previewMaxBlockZ,
            projection,
            previewResolutionMeters,
            PREVIEW_CANOPY_MAX_TILES
         )
         : previewResolutionMeters;
      long canopyDownloadUnits = canopyPreviewEnabled
         ? this.canopyHeightSource.preloadAreaTaskCount(
            previewMinBlockX,
            previewMinBlockZ,
            previewMaxBlockX,
            previewMaxBlockZ,
            projection,
            canopyPreviewResolutionMeters
         )
         : 0L;
      long downloadTotal = terrainDownloadUnits + coverDownloadUnits + climateDownloadUnits + canopyDownloadUnits;
      int seaLevel = settings.effectiveHeightOffset();
      float[] xCoords = buildAxisCoordinates(size);
      this.queueBasePreviewPrefetch(centerX, centerZ, projection, settings.demSelection(), previewResolutionMeters);
      this.queueOsmPreviewPrefetch(
         centerX,
         centerZ,
         projection,
         minWorldX,
         minWorldZ,
         maxWorldX,
         maxWorldZ,
         waterPreviewEnabled,
         roadsPreviewEnabled,
         buildingsPreviewEnabled
      );

      TerrainPreview.DownloadStageProgress downloadProgress = new TerrainPreview.DownloadStageProgress(id, downloadTotal);
      int[] coverClasses = new int[coverSize * coverSize];
      int[] visualCoverClasses = new int[coverSize * coverSize];
      byte[] climateGroups = new byte[climateSize * climateSize];
      byte[] climateBasedBuiltUpCoverClasses = new byte[climateSize * climateSize];
      String[] climateCodes = new String[climateSize * climateSize];
      double minElevation = Double.POSITIVE_INFINITY;
      double maxElevation = Double.NEGATIVE_INFINITY;
      int minSurfaceY = Integer.MAX_VALUE;
      int maxSurfaceY = Integer.MIN_VALUE;

      DownloadProgressReporter.Scope scope = DownloadProgressReporter.push(downloadProgress);
      try {
         long terrainSampleDone = 0L;
         int missingElevationCount = 0;
         for (int attempt = 0; attempt < PREVIEW_ELEVATION_MAX_ATTEMPTS; attempt++) {
            if (attempt > 0) {
               this.elevationSource.retryMissingTiles();
            }
            Arrays.fill(elevations, Double.NaN);
            Arrays.fill(missingElevationMask, false);
            Arrays.fill(mapterhornLandOverride, false);
            Arrays.fill(esaWaterMask, false);
            Arrays.fill(oceanFallbackMask, false);
            downloadDone = 0L;
            terrainSampleDone = 0L;
            missingElevationCount = 0;
            downloadProgress.updateSamples(downloadDone, terrainSampleDone, terrainDownloadUnits, ACTIVITY_DOWNLOAD_ELEVATION, "Terrain samples");

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
                  TellusLandMaskSource.LandMaskSample landMaskSample = this.landMaskSource.sampleLandMask(blockX, blockZ, projection);
                  int pointCoverClass = landMaskSample.known() && landMaskSample.land()
                     ? ESA_NO_DATA
                     : this.landCoverSource.sampleCoverClass(blockX, blockZ, projection, previewResolutionMeters);
                  boolean oceanZoom = useOceanZoom(landMaskSample);
                  TellusElevationSource.ResolvedElevationSample elevationSample = this.elevationSource.sampleResolvedPreviewElevationMeters(
                     blockX,
                     blockZ,
                     projection,
                     oceanZoom,
                     settings.demSelection(),
                     previewResolutionMeters
                  );
                  double elevation = elevationSample.elevationMeters();
                  mapterhornLandOverride[idx] = elevationSample.mapterhornLandOverride();
                  if (this.shouldAbortRequest(id)) {
                     return null;
                  }

                  if (!Double.isFinite(elevation)) {
                     missingElevationMask[idx] = true;
                     missingElevationCount++;
                  } else {
                     int surfaceY = scaledSurfaceY(elevation, blockZ, settings, projection);
                     elevations[idx] = elevation;
                     blockHeights[idx] = surfaceY;
                     esaWaterMask[idx] = !settings.enableWater()
                        && pointCoverClass == ESA_WATER
                        && !(oceanZoom && elevationSample.mapterhornLandOverride());
                     oceanFallbackMask[idx] = !settings.enableWater()
                        && isPreviewOceanFallback(
                           landMaskSample, surfaceY, pointCoverClass, seaLevel, elevationSample.mapterhornLandOverride()
                        );
                  }
                  downloadDone++;
                  terrainSampleDone++;
                  if ((downloadDone & 255L) == 0L) {
                     downloadProgress.updateSamples(downloadDone, terrainSampleDone, terrainDownloadUnits, ACTIVITY_DOWNLOAD_ELEVATION, "Terrain samples");
                  }
               }

               downloadProgress.updateSamples(downloadDone, terrainSampleDone, terrainDownloadUnits, ACTIVITY_DOWNLOAD_ELEVATION, "Terrain samples");
            }
            if (missingElevationCount == 0) {
               break;
            }
         }

         if (missingElevationCount > 0) {
            if (!ElevationGridRepair.repairMissing(elevations, missingElevationMask, size, size)) {
               Tellus.LOGGER.warn("Terrain preview elevation unavailable for the complete preview area after {} attempts", PREVIEW_ELEVATION_MAX_ATTEMPTS);
               this.updateStatus(id, TerrainPreview.PreviewStage.COMPLETE, 1.0F);
               return null;
            }
            Tellus.LOGGER.warn(
               "Repaired {} unavailable terrain preview elevation samples after {} download attempts",
               missingElevationCount,
               PREVIEW_ELEVATION_MAX_ATTEMPTS
            );
         }

         minElevation = Double.POSITIVE_INFINITY;
         maxElevation = Double.NEGATIVE_INFINITY;
         minSurfaceY = Integer.MAX_VALUE;
         maxSurfaceY = Integer.MIN_VALUE;
         for (int idx = 0; idx < elevations.length; idx++) {
            double elevation = elevations[idx];
            int z = idx / size;
            double blockZ = centerZ - radius + z * step;
            int surfaceY = scaledSurfaceY(elevation, blockZ, settings, projection);
            blockHeights[idx] = surfaceY;
            minElevation = Math.min(minElevation, elevation);
            maxElevation = Math.max(maxElevation, elevation);
            minSurfaceY = Math.min(minSurfaceY, surfaceY);
            maxSurfaceY = Math.max(maxSurfaceY, surfaceY);
         }

         long coverSampleDone = 0L;
         downloadProgress.updateSamples(downloadDone, coverSampleDone, coverDownloadUnits, ACTIVITY_DOWNLOAD_LAND_COVER, "Land-cover samples");
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
               int rawCoverClass = this.landCoverSource.sampleCoverClass(blockX, blockZ, projection, previewResolutionMeters);
               boolean replacedDryEsaWater = false;
               if (settings.enableWater() && rawCoverClass == ESA_WATER) {
                  rawCoverClass = this.landCoverSource.sampleNearestLandCoverClass(
                     blockX, blockZ, projection, MountainSurfaceRules.ESA_BARE
                  );
                  replacedDryEsaWater = true;
               }
               coverClasses[idx] = rawCoverClass;
               visualCoverClasses[idx] = rawCoverClass;
               if (this.shouldAbortRequest(id)) {
                  return null;
               }

               downloadDone++;
               coverSampleDone++;
               if ((downloadDone & 255L) == 0L) {
                  downloadProgress.updateSamples(downloadDone, coverSampleDone, coverDownloadUnits, ACTIVITY_DOWNLOAD_LAND_COVER, "Land-cover samples");
               }

               if (useVisualCover && !replacedDryEsaWater) {
                  visualCoverClasses[idx] = this.landCoverSource.sampleVisualCoverClass(
                     blockX, blockZ, projection, previewResolutionMeters
                  );
                  if (this.shouldAbortRequest(id)) {
                     return null;
                  }

                  downloadDone++;
                  coverSampleDone++;
                  if ((downloadDone & 255L) == 0L) {
                     downloadProgress.updateSamples(downloadDone, coverSampleDone, coverDownloadUnits, ACTIVITY_DOWNLOAD_LAND_COVER, "Land-cover samples");
                  }
               }
            }

            downloadProgress.updateSamples(downloadDone, coverSampleDone, coverDownloadUnits, ACTIVITY_DOWNLOAD_LAND_COVER, "Land-cover samples");
         }

         long climateSampleDone = 0L;
         downloadProgress.updateSamples(downloadDone, climateSampleDone, climateDownloadUnits, ACTIVITY_DOWNLOAD_CLIMATE, "Climate samples");
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
               String koppen = this.koppenSource.sampleDitheredCode(blockX, blockZ, projection);
               if (this.shouldAbortRequest(id)) {
                  return null;
               }

               int coverX = Math.min(coverSize - 1, sampleX / coverStride);
               int coverZ = Math.min(coverSize - 1, sampleZ / coverStride);
               int visualCoverClass = visualCoverClasses[coverX + coverZ * coverSize];
               if (BadlandsTerrainPolicy.isDryCanyonCover(visualCoverClass)) {
                  String coherentKoppen = this.koppenSource.sampleSmoothedCode(blockX, blockZ, projection);
                  if (BadlandsTerrainPolicy.shouldUseCoherentAridClimate(visualCoverClass, coherentKoppen)) {
                     koppen = coherentKoppen;
                  }
               }

               climateCodes[idx] = koppen;
               climateGroups[idx] = climateGroup(koppen);
               climateBasedBuiltUpCoverClasses[idx] = (byte)climateBasedBuiltUpPreviewCoverClass(koppen);
               downloadDone++;
               climateSampleDone++;
               if ((downloadDone & 255L) == 0L) {
                  downloadProgress.updateSamples(downloadDone, climateSampleDone, climateDownloadUnits, ACTIVITY_DOWNLOAD_CLIMATE, "Climate samples");
               }
            }

            downloadProgress.updateSamples(downloadDone, climateSampleDone, climateDownloadUnits, ACTIVITY_DOWNLOAD_CLIMATE, "Climate samples");
         }

         if (canopyDownloadUnits > 0L) {
            long canopyBaseDone = downloadDone;
            downloadProgress.updateSamples(
               canopyBaseDone, 0L, canopyDownloadUnits, ACTIVITY_DOWNLOAD_CANOPY, "Canopy tiles"
            );
            if (hasPreviewTreeCover(coverClasses, visualCoverClasses)) {
               this.canopyHeightSource.preloadAreaInputs(
                  previewMinBlockX,
                  previewMinBlockZ,
                  previewMaxBlockX,
                  previewMaxBlockZ,
                  projection,
                  canopyPreviewResolutionMeters,
                  0,
                  (completed, detail) -> downloadProgress.updateSamples(
                     canopyBaseDone + completed,
                     completed,
                     canopyDownloadUnits,
                     ACTIVITY_DOWNLOAD_CANOPY,
                     "Canopy tiles"
                  )
               );
            }
            downloadDone = canopyBaseDone + canopyDownloadUnits;
            downloadProgress.updateSamples(
               downloadDone, canopyDownloadUnits, canopyDownloadUnits, ACTIVITY_DOWNLOAD_CANOPY, "Canopy tiles"
            );
         }

         downloadProgress.finish(
            canopyDownloadUnits > 0L ? ACTIVITY_DOWNLOAD_CANOPY : ACTIVITY_DOWNLOAD_CLIMATE,
            canopyDownloadUnits > 0L ? "Canopy tiles" : "Climate samples"
         );
      } finally {
         scope.close();
      }

      if (this.shouldAbortRequest(id)) {
         return null;
      }

      double[] previewBlockHeights = previewDisplayBlockHeights(blockHeights, oceanFallbackMask, settings);
      float[] heights = new float[size * size];
      float min = Float.POSITIVE_INFINITY;
      float max = Float.NEGATIVE_INFINITY;
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
      this.updateStatus(
         id, TerrainPreview.PreviewStage.LOADING, 0.0F, ACTIVITY_BUILD_HEIGHTS, formatCountProgress("Height rows", 0L, size)
      );

      for (int i = 0; i < blockHeights.length; i++) {
         float value = (float)((previewBlockHeights[i] - settings.heightOffset()) / radius * 0.7F);
         heights[i] = value;
         min = Math.min(min, value);
         max = Math.max(max, value);
         if ((i + 1) % size == 0) {
            if (this.shouldAbortRequest(id)) {
               return null;
            }

            buildDone += size;
            this.updateBuildStatus(id, buildDone, buildTotal, ACTIVITY_BUILD_HEIGHTS, formatCountProgress("Height rows", (i + 1) / size, size));
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
            this.updateBuildStatus(id, buildDone, buildTotal, ACTIVITY_BUILD_CENTER, formatCountProgress("Center rows", (ix + 1) / size, size));
         }
      }

      this.updateBuildStatus(id, buildDone, buildTotal, ACTIVITY_BUILD_COLORS, formatCountProgress("Color rows", 0L, size));
      float[] terrainHeights = heights.clone();
      float[] detailHeights = heights.clone();
      int[] terrainColors = new int[size * size];
      int[] detailColors = new int[size * size];
      boolean[] desertMask = new boolean[size * size];
      boolean[] badlandsMask = new boolean[size * size];
      float[] detailHeightOffsets = new float[size * size];
      int[] waterColors = new int[size * size];
      Arrays.fill(waterColors, -1);
      float[] waterSurfaceHeights = terrainHeights.clone();
      float previewSeaSurfaceHeight = (float)((seaLevel - settings.heightOffset()) / radius * 0.7F) - center;
      List<TerrainPreview.PreviewTree> previewTrees = new ArrayList<>();

      for (int z = 0; z < size; z++) {
         if (this.shouldAbortRequest(id)) {
            return null;
         }

         double blockZ = minWorldZ + z * step;
         int coverZ = Math.min(coverSize - 1, z / coverStride);
         int climateZ = Math.min(climateSize - 1, z / climateStride);

         for (int xxxx = 0; xxxx < size; xxxx++) {
            int idx = xxxx + z * size;
            double blockX = minWorldX + xxxx * step;
            int coverX = Math.min(coverSize - 1, xxxx / coverStride);
            int climateX = Math.min(climateSize - 1, xxxx / climateStride);
            int coverIdx = coverX + coverZ * coverSize;
            int climateIdx = climateX + climateZ * climateSize;
            int rawCoverClass = coverClasses[coverIdx];
            int visualCoverClass = visualCoverClasses[coverIdx];
            byte climateGroup = climateGroups[climateIdx];
            if (settings.climateBasedBuiltUpTerrain()
               && MountainSurfaceRules.resolveSurfaceCoverClass(rawCoverClass, visualCoverClass) == MountainSurfaceRules.ESA_BUILT) {
               rawCoverClass = climateBasedBuiltUpCoverClasses[climateIdx];
               visualCoverClass = rawCoverClass;
            }

            int slopeDiff = previewSlopeDiff(previewBlockHeights, size, idx, step);
            int convexity = previewConvexity(previewBlockHeights, size, idx, step);
            double slope = computeSlope(previewBlockHeights, size, idx, step);
            double demSlopeDegrees = previewDemSlopeDegrees(elevations, size, idx, step, blockZ, settings.projection());
            boolean badlands = isPreviewBadlands(
               rawCoverClass,
               visualCoverClass,
               climateCodes[climateIdx],
               previewRegionalReliefMeters(elevations, size, idx, step, settings.worldScale())
            );
            boolean desert = !badlands && isPreviewDesert(rawCoverClass, visualCoverClass, climateCodes[climateIdx]);
            int color = colorForPreview(
               rawCoverClass,
               visualCoverClass,
               climateGroup,
               elevations[idx],
               previewBlockHeights[idx],
               slopeDiff,
               convexity,
               slope,
               demSlopeDegrees,
               seaLevel,
               esaWaterMask[idx],
               oceanFallbackMask[idx],
               !waterPreviewEnabled,
               settings.enableWater(),
               false,
               desert,
               badlands,
               (int)Math.round(blockX),
               (int)Math.round(blockZ)
            );
            terrainColors[idx] = color;
            detailColors[idx] = color;
            desertMask[idx] = desert;
            int surfaceCoverClass = MountainSurfaceRules.resolveSurfaceCoverClass(rawCoverClass, visualCoverClass);
            double waterDepth = previewWaterDepthBlocks(
               surfaceCoverClass,
               esaWaterMask[idx],
               previewBlockHeights[idx],
               seaLevel,
               oceanFallbackMask[idx],
               !waterPreviewEnabled
            );
            if (waterDepth >= 0.0) {
               waterColors[idx] = color;
               if (oceanFallbackMask[idx]) {
                  waterSurfaceHeights[idx] = previewSeaSurfaceHeight;
               }
            } else {
               badlandsMask[idx] = badlands;
            }
         }

         buildDone += size;
         this.updateBuildStatus(id, buildDone, buildTotal, ACTIVITY_BUILD_COLORS, formatCountProgress("Color rows", z + 1L, size));
      }

      TerrainPreview.PreviewInfo placeholderInfo = previewInfoPlaceholder(minElevation, maxElevation, minSurfaceY, maxSurfaceY);
      this.publishInterimMesh(
         id,
         size,
         terrainHeights,
         terrainColors,
         detailHeights,
         detailColors,
         waterColors,
         waterSurfaceHeights,
         previewBadlandsData(badlandsMask, minWorldX, minWorldZ, step, radius, center, settings.heightOffset()),
         xCoords,
         placeholderInfo
      );

      if (!this.buildPreviewTrees(
         id,
         buildTotal,
         buildDone,
         detailColors,
         coverClasses,
         visualCoverClasses,
         coverSize,
         coverStride,
         climateCodes,
         climateSize,
         climateStride,
         settings,
         minWorldX,
         minWorldZ,
         step,
         canopyPreviewResolutionMeters,
         previewTrees
      )) {
         return null;
      }

      buildDone += treeOverlayUnits;
      if (waterPreviewEnabled) {
         if (!this.processWaterPreviewOverlay(
            id,
            buildTotal,
            buildDone,
            terrainHeights,
            terrainColors,
            detailHeights,
            detailColors,
            detailHeightOffsets,
            waterColors,
            waterSurfaceHeights,
            previewSeaSurfaceHeight,
            previewBlockHeights,
            mapterhornLandOverride,
            settings,
            minWorldX,
            minWorldZ,
            maxWorldX,
            maxWorldZ,
            step
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
            id,
            buildTotal,
            buildDone,
            terrainHeights,
            terrainColors,
            detailHeights,
            detailColors,
            previewBlockHeights,
            settings,
            centerX,
            centerZ,
            radius,
            step,
            center
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
         settings.centerWorldOnSpawn(),
         settings.demSelection(),
         settings.enableWater(),
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
         oceanFallbackMask,
         mapterhornLandOverride,
         elevations,
         coverClasses,
         visualCoverClasses,
         climateGroups,
         climateCodes,
         climateBasedBuiltUpCoverClasses,
         desertMask,
         badlandsMask,
         heights,
         center,
         xCoords,
         previewInfo
      );
      this.updateStatus(id, TerrainPreview.PreviewStage.COMPLETE, 1.0F);
      return preparePreviewMesh(new TerrainPreview.PreviewMesh(
         size,
         1,
         terrainHeights,
         terrainColors,
         detailHeights,
         detailColors,
         waterColors,
         waterSurfaceHeights,
         previewBadlandsData(badlandsMask, minWorldX, minWorldZ, step, radius, center, settings.heightOffset()),
         xCoords,
         filterPreviewTrees(previewTrees, detailColors),
         previewInfo
      ));
   }

   private TerrainPreview.PreviewMesh buildMeshFromSnapshot(EarthGeneratorSettings settings, int id, TerrainPreview.PreviewBaseSnapshot snapshot) {
      if (this.shouldAbortRequest(id)) {
         return null;
      }

      int size = snapshot.size();
      double worldScale = snapshot.worldScale();
      WorldProjection projection = settings.projection();
      boolean roadsPreviewEnabled = settings.enableRoads() && worldScale > 0.0 && worldScale <= 15.0;
      boolean buildingsPreviewEnabled = settings.enableBuildings() && worldScale > 0.0 && worldScale <= 15.0 && this.osmBuildingSource.available();
      boolean waterPreviewEnabled = settings.enableWater() && worldScale > 0.0 && this.osmWaterSource.available();
      this.queueOsmPreviewPrefetch(
         snapshot.centerX(),
         snapshot.centerZ(),
         settings.projection(),
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
      this.updateStatus(id, TerrainPreview.PreviewStage.LOADING, 0.0F, ACTIVITY_BUILD_COLORS, formatCountProgress("Color rows", 0L, size));
      int seaLevel = settings.effectiveHeightOffset();
      double[] previewBlockHeights = previewDisplayBlockHeights(snapshot.blockHeights(), snapshot.oceanFallbackMask(), settings);
      TerrainPreview.PreviewHeightData previewHeightData = buildPreviewHeightData(previewBlockHeights, settings);
      float[] terrainHeights = previewHeightData.heights().clone();
      float[] detailHeights = previewHeightData.heights().clone();
      int[] terrainColors = new int[size * size];
      int[] detailColors = new int[size * size];
      float[] detailHeightOffsets = new float[size * size];
      int[] waterColors = new int[size * size];
      Arrays.fill(waterColors, -1);
      float[] waterSurfaceHeights = terrainHeights.clone();
      float previewSeaSurfaceHeight = (float)((seaLevel - settings.heightOffset()) / snapshot.radius() * 0.7F)
         - previewHeightData.center();
      List<TerrainPreview.PreviewTree> previewTrees = new ArrayList<>();

      for (int z = 0; z < size; z++) {
         if (this.shouldAbortRequest(id)) {
            return null;
         }

         double blockZ = snapshot.minWorldZ() + z * snapshot.step();
         int coverZ = Math.min(snapshot.coverSize() - 1, z / snapshot.coverStride());
         int climateZ = Math.min(snapshot.climateSize() - 1, z / snapshot.climateStride());

         for (int x = 0; x < size; x++) {
            int idx = x + z * size;
            double blockX = snapshot.minWorldX() + x * snapshot.step();
            int coverX = Math.min(snapshot.coverSize() - 1, x / snapshot.coverStride());
            int climateX = Math.min(snapshot.climateSize() - 1, x / snapshot.climateStride());
            int coverIdx = coverX + coverZ * snapshot.coverSize();
            int climateIdx = climateX + climateZ * snapshot.climateSize();
            int rawCoverClass = snapshot.coverClasses()[coverIdx];
            int visualCoverClass = snapshot.visualCoverClasses()[coverIdx];
            byte climateGroup = snapshot.climateGroups()[climateIdx];
            if (settings.climateBasedBuiltUpTerrain()
               && MountainSurfaceRules.resolveSurfaceCoverClass(rawCoverClass, visualCoverClass) == MountainSurfaceRules.ESA_BUILT) {
               rawCoverClass = snapshot.climateBasedBuiltUpCoverClasses()[climateIdx];
               visualCoverClass = rawCoverClass;
            }

            int slopeDiff = previewSlopeDiff(previewBlockHeights, size, idx, snapshot.step());
            int convexity = previewConvexity(previewBlockHeights, size, idx, snapshot.step());
            double slope = computeSlope(previewBlockHeights, size, idx, snapshot.step());
            double demSlopeDegrees = previewDemSlopeDegrees(
               snapshot.elevations(), size, idx, snapshot.step(), blockZ, settings.projection()
            );
            int color = colorForPreview(
               rawCoverClass,
               visualCoverClass,
               climateGroup,
               snapshot.elevations()[idx],
               previewBlockHeights[idx],
               slopeDiff,
               convexity,
               slope,
               demSlopeDegrees,
               seaLevel,
               snapshot.esaWaterMask()[idx],
               snapshot.oceanFallbackMask()[idx],
               !waterPreviewEnabled,
               settings.enableWater(),
               false,
               snapshot.desertMask()[idx],
               snapshot.badlandsMask()[idx],
               (int)Math.round(blockX),
               (int)Math.round(blockZ)
            );
            terrainColors[idx] = color;
            detailColors[idx] = color;
            int surfaceCoverClass = MountainSurfaceRules.resolveSurfaceCoverClass(rawCoverClass, visualCoverClass);
            double waterDepth = previewWaterDepthBlocks(
               surfaceCoverClass,
               snapshot.esaWaterMask()[idx],
               previewBlockHeights[idx],
               seaLevel,
               snapshot.oceanFallbackMask()[idx],
               !waterPreviewEnabled
            );
            if (waterDepth >= 0.0) {
               waterColors[idx] = color;
               if (snapshot.oceanFallbackMask()[idx]) {
                  waterSurfaceHeights[idx] = previewSeaSurfaceHeight;
               }
            }
         }

         buildDone += size;
         this.updateBuildStatus(id, buildDone, buildTotal, ACTIVITY_BUILD_COLORS, formatCountProgress("Color rows", z + 1L, size));
      }

      this.publishInterimMesh(
         id,
         size,
         terrainHeights,
         terrainColors,
         detailHeights,
         detailColors,
         waterColors,
         waterSurfaceHeights,
         previewBadlandsData(
            snapshot.badlandsMask(),
            snapshot.minWorldX(),
            snapshot.minWorldZ(),
            snapshot.step(),
            snapshot.radius(),
            previewHeightData.center(),
            settings.heightOffset()
         ),
         snapshot.xCoords(),
         snapshot.info()
      );

      if (!this.buildPreviewTrees(
         id,
         buildTotal,
         buildDone,
         detailColors,
         snapshot.coverClasses(),
         snapshot.visualCoverClasses(),
         snapshot.coverSize(),
         snapshot.coverStride(),
         snapshot.climateCodes(),
         snapshot.climateSize(),
         snapshot.climateStride(),
         settings,
         snapshot.minWorldX(),
         snapshot.minWorldZ(),
         snapshot.step(),
         settings.customTrees()
            ? TellusCanopyHeightSource.resolutionForAreaTileBudget(
               Mth.floor(snapshot.minWorldX()),
               Mth.floor(snapshot.minWorldZ()),
               Mth.ceil(snapshot.maxWorldX()),
               Mth.ceil(snapshot.maxWorldZ()),
               settings.projection(),
               Math.max(settings.worldScale(), snapshot.step() * settings.worldScale()),
               PREVIEW_CANOPY_MAX_TILES
            )
            : Math.max(settings.worldScale(), snapshot.step() * settings.worldScale()),
         previewTrees
      )) {
         return null;
      }

      buildDone += gridArea;
      if (waterPreviewEnabled) {
         if (!this.processWaterPreviewOverlay(
            id,
            buildTotal,
            buildDone,
            terrainHeights,
            terrainColors,
            detailHeights,
            detailColors,
            detailHeightOffsets,
            waterColors,
            waterSurfaceHeights,
            previewSeaSurfaceHeight,
            previewBlockHeights,
            snapshot.mapterhornLandOverride(),
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
            previewBlockHeights,
            settings,
            snapshot.centerX(),
            snapshot.centerZ(),
            snapshot.radius(),
            snapshot.step(),
            previewHeightData.center()
         )) {
            return null;
         }

         buildDone += PREVIEW_BUILDING_OVERLAY_UNITS;
      }

      if (this.shouldAbortRequest(id)) {
         return null;
      }

      this.updateStatus(id, TerrainPreview.PreviewStage.COMPLETE, 1.0F);
      return preparePreviewMesh(new TerrainPreview.PreviewMesh(
         size,
         1,
         terrainHeights,
         terrainColors,
         detailHeights,
         detailColors,
         waterColors,
         waterSurfaceHeights,
         previewBadlandsData(
            snapshot.badlandsMask(),
            snapshot.minWorldX(),
            snapshot.minWorldZ(),
            snapshot.step(),
            snapshot.radius(),
            previewHeightData.center(),
            settings.heightOffset()
         ),
         snapshot.xCoords(),
         filterPreviewTrees(previewTrees, detailColors),
         snapshot.info()
      ));
   }

   private boolean canReuseBaseSnapshot(EarthGeneratorSettings settings, TerrainPreview.PreviewBaseSnapshot snapshot) {
      return snapshot != null
         && Double.compare(snapshot.worldScale(), settings.worldScale()) == 0
         && Double.compare(snapshot.terrestrialHeightScale(), settings.terrestrialHeightScale()) == 0
         && Double.compare(snapshot.oceanicHeightScale(), settings.oceanicHeightScale()) == 0
         && snapshot.heightOffset() == settings.heightOffset()
         && Double.compare(snapshot.spawnLatitude(), settings.spawnLatitude()) == 0
         && Double.compare(snapshot.spawnLongitude(), settings.spawnLongitude()) == 0
         && snapshot.centerWorldOnSpawn() == settings.centerWorldOnSpawn()
         && snapshot.demSelection().equals(settings.demSelection())
         && snapshot.osmWaterEnabled() == settings.enableWater();
   }

   private void queueBasePreviewPrefetch(
      double centerX,
      double centerZ,
      WorldProjection projection,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      double worldScale = projection.worldScale();
      if (worldScale > 0.0) {
         Util.backgroundExecutor().execute(() -> {
            try {
               this.elevationSource.prefetchTiles(
                  centerX, centerZ, projection, PREVIEW_ELEVATION_PREFETCH_RADIUS, demSelection, previewResolutionMeters
               );
            } catch (RuntimeException ignored) {
            }
         });
         Util.backgroundExecutor().execute(() -> {
            try {
               this.landCoverSource.prefetchTiles(
                  centerX, centerZ, projection, PREVIEW_LAND_COVER_PREFETCH_RADIUS, previewResolutionMeters
               );
            } catch (RuntimeException ignored) {
            }
         });
         Util.backgroundExecutor().execute(() -> {
            try {
               this.landMaskSource.prefetchTiles(centerX, centerZ, projection, PREVIEW_LAND_MASK_PREFETCH_RADIUS);
            } catch (RuntimeException ignored) {
            }
         });
      }
   }

   private void queueOsmPreviewPrefetch(
      double centerX,
      double centerZ,
      WorldProjection projection,
      double minWorldX,
      double minWorldZ,
      double maxWorldX,
      double maxWorldZ,
      boolean waterPreviewEnabled,
      boolean roadsPreviewEnabled,
      boolean buildingsPreviewEnabled
   ) {
      if (waterPreviewEnabled) {
         this.queueWaterPreviewPrefetch(minWorldX, minWorldZ, maxWorldX, maxWorldZ, projection);
      }

      if (roadsPreviewEnabled) {
         this.osmRoadSource.prefetchTiles(centerX, centerZ, projection, PREVIEW_OSM_PREFETCH_RADIUS);
      }

      if (buildingsPreviewEnabled) {
         this.osmBuildingSource.prefetchTiles(centerX, centerZ, projection, PREVIEW_OSM_PREFETCH_RADIUS);
      }
   }

   public static int scaledSurfaceY(
      double elevation, double blockZ, EarthGeneratorSettings settings, WorldProjection projection
   ) {
      int base = TerrainHeightTransform.blockOffset(
         elevation,
         blockZ,
         projection,
         settings.effectiveTerrestrialHeightScale(),
         settings.effectiveOceanicHeightScale(),
         settings.experimentalIncreaseHeight(),
         settings.automaticHeightScaling()
      );
      return base + settings.effectiveHeightOffset();
   }

   private void publishInterimMesh(
      int id,
      int size,
      float[] terrainHeights,
      int[] terrainColors,
      float[] detailHeights,
      int[] detailColors,
      int[] waterColors,
      float[] waterSurfaceHeights,
      TerrainPreview.PreviewBadlandsData badlands,
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
            waterColors.clone(),
            waterSurfaceHeights.clone(),
            badlands.copy(),
            xCoords,
            new TerrainPreview.PreviewTree[0],
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

   private static TerrainPreview.PreviewBadlandsData previewBadlandsData(
      boolean[] mask,
      double minWorldX,
      double minWorldZ,
      double step,
      double radius,
      float heightCenter,
      int heightOffset
   ) {
      double blocksPerHeightUnit = radius / 0.7;
      return new TerrainPreview.PreviewBadlandsData(
         mask,
         minWorldX,
         minWorldZ,
         step,
         blocksPerHeightUnit,
         heightOffset + heightCenter * blocksPerHeightUnit
      );
   }

   private static TerrainPreview.PreviewInfo previewInfoPlaceholder(
      double minElevation, double maxElevation, int minSurfaceY, int maxSurfaceY
   ) {
      return new TerrainPreview.PreviewInfo(List.of(), List.of(), 0, minElevation, maxElevation, minSurfaceY, maxSurfaceY);
   }

   private static TerrainPreview.PreviewMesh preparePreviewMesh(TerrainPreview.PreviewMesh mesh) {
      mesh.geometryFor(TerrainPreviewWidget.RenderMode.TERRAIN_ONLY);
      mesh.geometryFor(TerrainPreviewWidget.RenderMode.FULL_DETAIL);
      mesh.cloudGeometry();
      return mesh;
   }

   private static double[] previewDisplayBlockHeights(double[] rawBlockHeights, boolean[] oceanFallbackMask, EarthGeneratorSettings settings) {
      if (!settings.enableWater()) {
         return rawBlockHeights;
      } else {
         double[] displayHeights = rawBlockHeights.clone();
         int seaLevel = settings.effectiveHeightOffset();

         for (int i = 0; i < displayHeights.length; i++) {
            if (oceanFallbackMask[i] && displayHeights[i] >= seaLevel) {
               displayHeights[i] = seaLevel - 1;
            }

            if (displayHeights[i] < seaLevel) {
               displayHeights[i] = seaLevel - previewOceanDisplayDepth(seaLevel - displayHeights[i]);
            }
         }

         return displayHeights;
      }
   }

   private static double previewOceanDisplayDepth(double depthBlocks) {
      if (!(depthBlocks > 0.0)) {
         return 0.0;
      } else if (!Double.isFinite(depthBlocks)) {
         return PREVIEW_OCEAN_MAX_DEPTH_BLOCKS;
      } else if (depthBlocks <= PREVIEW_OCEAN_LINEAR_DEPTH_BLOCKS) {
         return depthBlocks;
      } else {
         double compressed = PREVIEW_OCEAN_LINEAR_DEPTH_BLOCKS
            + Math.log1p((depthBlocks - PREVIEW_OCEAN_LINEAR_DEPTH_BLOCKS) / PREVIEW_OCEAN_DEPTH_COMPRESSION_BLOCKS)
               * PREVIEW_OCEAN_DEPTH_COMPRESSION_BLOCKS;
         return Math.min(PREVIEW_OCEAN_MAX_DEPTH_BLOCKS, compressed);
      }
   }

   private static TerrainPreview.PreviewHeightData buildPreviewHeightData(double[] blockHeights, EarthGeneratorSettings settings) {
      float[] heights = new float[blockHeights.length];
      float min = Float.POSITIVE_INFINITY;
      float max = Float.NEGATIVE_INFINITY;

      for (int i = 0; i < blockHeights.length; i++) {
         float value = (float)((blockHeights[i] - settings.heightOffset()) / PREVIEW_RADIUS_BLOCKS * 0.7F);
         heights[i] = value;
         min = Math.min(min, value);
         max = Math.max(max, value);
      }

      float center = (min + max) * 0.5F;

      for (int i = 0; i < heights.length; i++) {
         heights[i] -= center;
      }

      return new TerrainPreview.PreviewHeightData(heights, center);
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
            boolean oceanZoom = this.useOceanZoom(blockX, blockZ, settings.projection());
            ElevationDiagnostic diagnostic = this.elevationSource.samplePreviewDiagnostic(
               blockX,
               blockZ,
               settings.projection(),
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
         this.updateBuildStatus(
            id,
            buildBaseDone + progressDone,
            buildTotal,
            ACTIVITY_BUILD_INFO,
            formatCountProgress("Coverage samples", progressDone, (long)providerGridSize * providerGridSize)
         );
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

   private static double previewDemSlopeDegrees(
      double[] elevations, int size, int idx, double stepBlocks, double blockZ, WorldProjection projection
   ) {
      if (size < 2) {
         return Double.NaN;
      }
      int x = idx % size;
      int z = idx / size;
      double center = elevations[idx];
      double east = x + 1 < size ? elevations[idx + 1] : Double.NaN;
      double west = x > 0 ? elevations[idx - 1] : Double.NaN;
      double north = z > 0 ? elevations[idx - size] : Double.NaN;
      double south = z + 1 < size ? elevations[idx + size] : Double.NaN;
      double runX = stepBlocks * projection.groundMetersPerBlockX(blockZ);
      double runZ = stepBlocks * projection.groundMetersPerBlockZ(blockZ);
      return TerrainSlopePolicy.localSlopeDegrees(center, east, west, north, south, runX, runZ);
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

   private boolean buildPreviewTrees(
      int requestId,
      long buildTotal,
      long buildBaseDone,
      int[] colors,
      int[] coverClasses,
      int[] visualCoverClasses,
      int coverSize,
      int coverStride,
      String[] climateCodes,
      int climateSize,
      int climateStride,
      EarthGeneratorSettings settings,
      double minWorldX,
      double minWorldZ,
      double step,
      double canopyPreviewResolutionMeters,
      List<TerrainPreview.PreviewTree> trees
   ) {
      if (!(settings.worldScale() <= 0.0) && !(settings.worldScale() > 60.0) && !(step <= 0.0)) {
         int size = PREVIEW_GRID_SIZE;
         float density = treeMarkerDensity(settings.worldScale());
         if (!(density <= 0.0F)) {
            for (int z = 1; z < size - 1; z++) {
               if (this.shouldAbortRequest(requestId)) {
                  return false;
               }

               int coverZ = Math.min(coverSize - 1, z / coverStride);
               int climateZ = Math.min(climateSize - 1, z / climateStride);

               for (int x = 1; x < size - 1; x++) {
                  int coverX = Math.min(coverSize - 1, x / coverStride);
                  int coverIdx = coverX + coverZ * coverSize;
                  if (MountainSurfaceRules.isTreeMarkerCoverClass(coverClasses[coverIdx], visualCoverClasses[coverIdx])) {
                     int blockX = Mth.floor(minWorldX + x * step);
                     int blockZ = Mth.floor(minWorldZ + z * step);
                     if (!(hashToUnitDouble(blockX, blockZ) > density)) {
                        int index = x + z * size;
                        if (!settings.customTrees()) {
                           float height = minecraftTreePreviewHeight(settings.worldScale())
                              * (0.72F + (float)hashToUnitDouble(blockX, blockZ, 1837846289L) * 0.42F);
                           float canopyScale = 0.82F + (float)hashToUnitDouble(blockX, blockZ, 3257595197L) * 0.38F;
                           float trunkScale = 0.82F + (float)hashToUnitDouble(blockX, blockZ, 182545271L) * 0.3F;
                           int leafColor = blendColor(PREVIEW_TREE_LEAF_COLOR, colors[index], 0.18F);
                           int trunkColor = blendColor(PREVIEW_TREE_TRUNK_COLOR, colors[index], 0.08F);
                           trees.add(
                              new TerrainPreview.PreviewTree(
                                 x,
                                 z,
                                 height,
                                 canopyScale,
                                 trunkScale,
                                 0.46F,
                                 0.0F,
                                 0.0F,
                                 false,
                                 TellusProceduralTreeGenerator.Profile.TEMPERATE_BROADLEAF,
                                 trunkColor,
                                 leafColor,
                                 colors[index]
                              )
                           );
                           continue;
                        }

                        int climateX = Math.min(climateSize - 1, x / climateStride);
                        String koppen = climateCodes[climateX + climateZ * climateSize];
                        int surfaceCoverClass = MountainSurfaceRules.resolveSurfaceCoverClass(
                           coverClasses[coverIdx], visualCoverClasses[coverIdx]
                        );
                        ResourceKey<Biome> biomeKey = resolvePreviewTreeBiome(
                           surfaceCoverClass, koppen, blockX, blockZ, settings.projection()
                        );
                        ResolveEcoregion ecoregion = this.resolveSource.sampleEcoregion(
                           blockX, blockZ, settings.projection()
                        );
                        TellusCanopyHeightSource.CanopySample canopy = this.canopyHeightSource.sampleCanopyLocalOnly(
                           blockX, blockZ, settings.projection(), canopyPreviewResolutionMeters
                        );
                        long treeSeed = previewTreeSeed(blockX, blockZ);
                        TellusProceduralTreeGenerator.TreePlan plan = TellusProceduralTreeGenerator.plan(
                           biomeKey, ecoregion, canopy, treeSeed
                        );
                        if (plan.present()) {
                           TellusProceduralTreeGenerator.Profile profile = plan.profile();
                           float height = treePreviewHeight(plan);
                           float canopyScale = Mth.clamp(plan.crownRadius() / 3.2F, 0.72F, 2.8F);
                           float trunkScale = Mth.clamp(plan.trunkRadius() * 0.86F, 0.76F, 2.8F);
                           float crownBaseRatio = Mth.clamp(
                              (float)plan.crownBase() / Math.max(1, plan.height()), 0.16F, 0.86F
                           );
                           float leanX = (float)plan.leanX() / (float)PREVIEW_RADIUS_BLOCKS;
                           float leanZ = (float)plan.leanZ() / (float)PREVIEW_RADIUS_BLOCKS;
                           int leafColor = blendColor(previewTreeLeafColor(profile), colors[index], 0.12F);
                           int trunkColor = blendColor(previewTreeTrunkColor(profile), colors[index], 0.06F);
                           trees.add(
                              new TerrainPreview.PreviewTree(
                                 x,
                                 z,
                                 height,
                                 canopyScale,
                                 trunkScale,
                                 crownBaseRatio,
                                 leanX,
                                 leanZ,
                                 plan.bush(),
                                 profile,
                                 trunkColor,
                                 leafColor,
                                 colors[index]
                              )
                           );
                        }
                     }
                  }
               }

               this.updateBuildStatus(
                  requestId,
                  buildBaseDone + (long)(z + 1) * size,
                  buildTotal,
                  ACTIVITY_BUILD_TREES,
                  formatCountProgress("Tree rows", z + 1L, size)
               );
            }
         }
      }

      return !this.shouldAbortRequest(requestId);
   }

   private static ResourceKey<Biome> resolvePreviewTreeBiome(
      int surfaceCoverClass,
      String koppen,
      int blockX,
      int blockZ,
      WorldProjection projection
   ) {
      ResourceKey<Biome> biomeKey = BiomeClassificationProviders.findBiomeKey(
         surfaceCoverClass, koppen, blockX, blockZ, projection
      );
      if (biomeKey == null) {
         biomeKey = BiomeClassification.findBiomeKey(surfaceCoverClass, koppen);
      }
      return biomeKey == null ? BiomeClassification.findFallbackKey(surfaceCoverClass) : biomeKey;
   }

   private static boolean hasPreviewTreeCover(int[] coverClasses, int[] visualCoverClasses) {
      int count = Math.min(coverClasses.length, visualCoverClasses.length);
      for (int index = 0; index < count; index++) {
         if (MountainSurfaceRules.isTreeMarkerCoverClass(coverClasses[index], visualCoverClasses[index])) {
            return true;
         }
      }
      return false;
   }

   private static TerrainPreview.PreviewTree[] filterPreviewTrees(List<TerrainPreview.PreviewTree> candidates, int[] colors) {
      List<TerrainPreview.PreviewTree> trees = new ArrayList<>(candidates.size());
      for (TerrainPreview.PreviewTree tree : candidates) {
         int index = tree.gridX() + tree.gridZ() * PREVIEW_GRID_SIZE;
         if (index >= 0 && index < colors.length && colors[index] == tree.sourceColor()) {
            trees.add(tree);
         }
      }

      return trees.toArray(TerrainPreview.PreviewTree[]::new);
   }

   private boolean applyFeatureHeightOffsets(int requestId, long buildTotal, long buildBaseDone, float[] heights, float[] offsets, int rowSize) {
      int count = Math.min(heights.length, offsets.length);
      if (count <= 0) {
         return !this.shouldAbortRequest(requestId);
      } else {
         int safeRowSize = Math.max(1, rowSize);
         long totalRows = (count + (long)safeRowSize - 1L) / safeRowSize;

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

            long completedRows = (rowEnd + (long)safeRowSize - 1L) / safeRowSize;
            this.updateBuildStatus(
               requestId,
               buildBaseDone + rowEnd,
               buildTotal,
               ACTIVITY_BUILD_HEIGHT_OFFSETS,
               formatCountProgress("Feature rows", completedRows, totalRows)
            );
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

   private static float treePreviewHeight(TellusProceduralTreeGenerator.TreePlan plan) {
      int minimumHeight = plan.bush() ? 3 : 4;
      return Math.max(minimumHeight, plan.height()) * PREVIEW_VERTICAL_BLOCK_SCALE;
   }

   private static float minecraftTreePreviewHeight(double worldScale) {
      float blocks = worldScale <= 8.0 ? 8.0F : worldScale <= 25.0 ? 7.0F : 6.0F;
      return blocks * PREVIEW_VERTICAL_BLOCK_SCALE;
   }

   private static long previewTreeSeed(int blockX, int blockZ) {
      return mix64(
         (long)blockX * -7046029254386353131L
            ^ (long)blockZ * -4417276706812531889L
            ^ 0x42C5A19F371D2E6BL
      );
   }

   private static int previewTreeLeafColor(TellusProceduralTreeGenerator.Profile profile) {
      return switch (profile) {
         case TEMPERATE_BROADLEAF -> PREVIEW_TREE_LEAF_COLOR;
         case DARK_BROADLEAF -> 0x315F31;
         case PALE_BROADLEAF -> 0x8B9B78;
         case BIRCH -> 0x67A64D;
         case SUBARCTIC_BIRCH -> 0x788E57;
         case CHERRY -> 0xE6A0C2;
         case CONIFER -> 0x326C3E;
         case TALL_CONIFER -> 0x285B35;
         case PINE -> 0x3D7341;
         case COAST_REDWOOD -> 0x245A35;
         case TROPICAL -> 0x2F8B3D;
         case DRY_BROADLEAF -> 0x6F9142;
         case EUCALYPTUS -> 0x759B78;
         case MALLEE -> 0x7D9266;
         case MEDITERRANEAN -> 0x557A3E;
         case SAVANNA -> 0x76953E;
         case SWAMP -> 0x416F3A;
      };
   }

   private static int previewTreeTrunkColor(TellusProceduralTreeGenerator.Profile profile) {
      return switch (profile) {
         case BIRCH, SUBARCTIC_BIRCH -> 0xD1C8AA;
         case CHERRY -> 0x81555B;
         case PALE_BROADLEAF -> 0xA69D87;
         case DARK_BROADLEAF -> 0x4C3428;
         case CONIFER, TALL_CONIFER, PINE -> 0x674329;
         case COAST_REDWOOD -> 0x7A3F2E;
         case TROPICAL, DRY_BROADLEAF -> 0x70502E;
         case EUCALYPTUS, MALLEE -> 0xB7AA91;
         case MEDITERRANEAN, SAVANNA -> 0x725036;
         case SWAMP -> 0x59402C;
         case TEMPERATE_BROADLEAF -> PREVIEW_TREE_TRUNK_COLOR;
      };
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

   private void queueWaterPreviewPrefetch(
      double minWorldX, double minWorldZ, double maxWorldX, double maxWorldZ, WorldProjection projection
   ) {
      double worldScale = projection.worldScale();
      if (this.osmWaterSource.available() && worldScale > 0.0) {
         this.osmWaterSource.waterForAreaWithStatus(
            Mth.floor(minWorldX), Mth.floor(minWorldZ), Mth.ceil(maxWorldX), Mth.ceil(maxWorldZ), projection,
            Math.max(PREVIEW_OSM_MARGIN_BLOCKS, WaterfallNoCarveZone.queryMarginBlocks(worldScale)), OsmQueryMode.NON_BLOCKING
         );
      }
   }

   private boolean processWaterPreviewOverlay(
      int id,
      long buildTotal,
      long overlayBaseDone,
      float[] terrainHeights,
      int[] terrainColors,
      float[] detailHeights,
      int[] detailColors,
      float[] detailHeightOffsets,
      int[] waterColors,
      float[] waterSurfaceHeights,
      float previewSeaSurfaceHeight,
      double[] blockHeights,
      boolean[] mapterhornLandOverride,
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
      DownloadProgressReporter.Scope scope = DownloadProgressReporter.push(new DownloadProgressReporter.Listener() {
            @Override
            public void onRequestStarted(long expectedBytes) {
               networkTracker.onRequestStarted(expectedBytes);
               overlayProgress.updateFetch(networkTracker.progress(), networkTracker.detail());
            }

            @Override
            public void onBytesRead(int bytes) {
               networkTracker.onBytesRead(bytes);
               overlayProgress.updateFetch(networkTracker.progress(), networkTracker.detail());
            }

            @Override
            public void onRequestFinished() {
               networkTracker.onRequestFinished();
               overlayProgress.updateFetch(networkTracker.progress(), networkTracker.detail());
            }
         });
      try {
         features = this.osmWaterSource
            .waterForAreaWithStatus(
               Mth.floor(minWorldX), Mth.floor(minWorldZ), Mth.ceil(maxWorldX), Mth.ceil(maxWorldZ), settings.projection(),
               Math.max(PREVIEW_OSM_MARGIN_BLOCKS, WaterfallNoCarveZone.queryMarginBlocks(settings.worldScale())), OsmQueryMode.BLOCKING
            )
            .features();
      } finally {
         scope.close();
      }

      if (this.shouldAbortRequest(id)) {
         return false;
      }

      overlayProgress.updateFetch(1.0F, networkTracker.detail());
      if (features.isEmpty()) {
         overlayProgress.updateRaster(1.0F);
         overlayProgress.finish();
      } else {
         if (!this.overlayWaterPreviewColors(
            id,
            terrainHeights,
            terrainColors,
            detailHeights,
            detailColors,
            detailHeightOffsets,
            waterColors,
            waterSurfaceHeights,
            previewSeaSurfaceHeight,
            blockHeights,
            mapterhornLandOverride,
            settings,
            minWorldX,
            minWorldZ,
            step,
            features,
            overlayProgress::updateRaster
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
      List<RoadAreaFeature> roadAreas;
      DownloadProgressReporter.Scope scope = DownloadProgressReporter.push(new DownloadProgressReporter.Listener() {
            @Override
            public void onRequestStarted(long expectedBytes) {
               networkTracker.onRequestStarted(expectedBytes);
               overlayProgress.updateFetch(networkTracker.progress(), networkTracker.detail());
            }

            @Override
            public void onBytesRead(int bytes) {
               networkTracker.onBytesRead(bytes);
               overlayProgress.updateFetch(networkTracker.progress(), networkTracker.detail());
            }

            @Override
            public void onRequestFinished() {
               networkTracker.onRequestFinished();
               overlayProgress.updateFetch(networkTracker.progress(), networkTracker.detail());
            }
         });
      try {
         roads = this.osmRoadSource
            .roadsForArea(
               Mth.floor(minWorldX),
               Mth.floor(minWorldZ),
               Mth.ceil(maxWorldX),
               Mth.ceil(maxWorldZ),
               settings.projection(),
               64
            );
         roadAreas = this.osmRoadSource
            .roadAreasForArea(
               Mth.floor(minWorldX),
               Mth.floor(minWorldZ),
               Mth.ceil(maxWorldX),
               Mth.ceil(maxWorldZ),
               settings.projection(),
               64,
               OsmQueryMode.BLOCKING
            );
      } finally {
         scope.close();
      }

      if (this.shouldAbortRequest(id)) {
         return false;
      }

      overlayProgress.updateFetch(1.0F, networkTracker.detail());
      if (roads.isEmpty() && roadAreas.isEmpty()) {
         overlayProgress.updateRaster(1.0F);
         overlayProgress.finish();
      } else {
         if (!this.overlayRoadPreviewColors(id, terrainColors, detailColors, settings, minWorldX, minWorldZ, step, roads, roadAreas, overlayProgress::updateRaster)) {
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
      DownloadProgressReporter.Scope scope = DownloadProgressReporter.push(new DownloadProgressReporter.Listener() {
            @Override
            public void onRequestStarted(long expectedBytes) {
               networkTracker.onRequestStarted(expectedBytes);
               overlayProgress.updateFetch(networkTracker.progress(), networkTracker.detail());
            }

            @Override
            public void onBytesRead(int bytes) {
               networkTracker.onBytesRead(bytes);
               overlayProgress.updateFetch(networkTracker.progress(), networkTracker.detail());
            }

            @Override
            public void onRequestFinished() {
               networkTracker.onRequestFinished();
               overlayProgress.updateFetch(networkTracker.progress(), networkTracker.detail());
            }
         });
      try {
         features = this.osmBuildingSource.buildingsForArea(
            Mth.floor(minWorldX),
            Mth.floor(minWorldZ),
            Mth.ceil(maxWorldX),
            Mth.ceil(maxWorldZ),
            settings.projection(),
            PREVIEW_OSM_MARGIN_BLOCKS
         );
      } finally {
         scope.close();
      }

      if (this.shouldAbortRequest(id)) {
         return false;
      }

      overlayProgress.updateFetch(1.0F, networkTracker.detail());
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
      float[] terrainHeights,
      int[] terrainColors,
      float[] detailHeights,
      int[] detailColors,
      float[] detailHeightOffsets,
      int[] waterColors,
      float[] waterSurfaceHeights,
      float previewSeaSurfaceHeight,
      double[] blockHeights,
      boolean[] mapterhornLandOverride,
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
      boolean[] lineWaterMask = new boolean[area];
      boolean[] areaWaterMask = new boolean[area];
      boolean[] flowingWaterMask = new boolean[area];
      boolean[] waterfallNoCarveMask = new boolean[area];
      double worldScale = settings.worldScale();
      WorldProjection projection = settings.projection();
      int seaLevel = settings.effectiveHeightOffset();
      double previewResolutionMeters = Math.max(worldScale, step * worldScale);
      WaterSurfaceResolver waterResolver = TellusWorldgenSources.previewWaterResolver(settings);
      boolean oceanProfileAvailable = true;
      double[] sampleWorldX = new double[size];
      double[] sampleWorldZ = new double[size];
      double[] sampleLon = new double[size];
      double[] sampleLat = new double[size];

      for (int i = 0; i < size; i++) {
         double worldX = minWorldX + i * step;
         double worldZ = minWorldZ + i * step;
         sampleWorldX[i] = worldX;
         sampleWorldZ[i] = worldZ;
         sampleLon[i] = projection.blockXToLon(worldX);
         sampleLat[i] = projection.blockZToLat(worldZ);
      }

      int totalFeatures = Math.max(1, features.size());
      int processedFeatures = 0;
      WaterfallNoCarveZone.markSampleGrid(
         waterfallNoCarveMask, sampleWorldX, sampleWorldZ, features, projection
      );

      for (OsmWaterFeature feature : features) {
         if (this.shouldAbortRequest(requestId)) {
            return false;
         }

         if (!WaterfallNoCarveZone.isMarker(feature) && !this.rasterizeWaterPreviewFeature(
            requestId,
            feature,
            sampleWorldX,
            sampleWorldZ,
            sampleLon,
            sampleLat,
            projection,
            minWorldX,
            minWorldZ,
            step,
            size,
            waterKind,
            lineWaterMask,
            areaWaterMask,
            flowingWaterMask
         )) {
            return false;
         }

         processedFeatures++;
         progress.onProgress((float)processedFeatures / (float)totalFeatures * 0.70F);
      }

      int[] rawTerrain = new int[area];
      int[] oceanWaterSurface = new int[area];
      boolean[] inlandWaterMask = new boolean[area];
      boolean[] oceanWaterMask = new boolean[area];
      Arrays.fill(oceanWaterSurface, seaLevel);
      for (int z = 0; z < size; z++) {
         if (this.shouldAbortRequest(requestId)) {
            return false;
         }
         int row = z * size;
         for (int x = 0; x < size; x++) {
            int idx = row + x;
            rawTerrain[idx] = Mth.floor(blockHeights[idx]);
            byte kind = waterKind[idx];
            if (kind == 1) {
               inlandWaterMask[idx] = true;
            } else if (kind == 2 && !mapterhornLandOverride[idx]) {
               oceanWaterMask[idx] = true;
               oceanWaterSurface[idx] = waterResolver.resolveOceanWaterSurface(
                  Mth.floor(sampleWorldX[x]), Mth.floor(sampleWorldZ[z])
               );
            }
         }
         progress.onProgress(0.70F + (float)(z + 1) / (float)size * 0.08F);
      }

      WaterSurfaceResolver.PreviewWaterGrid previewWater = waterResolver.resolvePreviewWaterGrid(
         size,
         Math.max(1, (int)Math.round(step)),
         Mth.floor(sampleWorldX[0]),
         Mth.floor(sampleWorldZ[0]),
         rawTerrain,
         inlandWaterMask,
         oceanWaterMask,
         lineWaterMask,
         areaWaterMask,
         flowingWaterMask,
         waterfallNoCarveMask,
         oceanWaterSurface
      );
      progress.onProgress(0.82F);

      for (int z = 0; z < size; z++) {
         if (this.shouldAbortRequest(requestId)) {
            return false;
         }

         int row = z * size;

         for (int x = 0; x < size; x++) {
            int idx = row + x;
            int terrainSurface = previewWater.terrainSurface()[idx];
            if (!oceanWaterMask[idx] && terrainSurface != rawTerrain[idx]) {
               blockHeights[idx] = terrainSurface;
               float profiledTerrainHeight = previewSeaSurfaceHeight
                  + (float)((terrainSurface - seaLevel) / PREVIEW_RADIUS_BLOCKS * PREVIEW_VERTICAL_CELL_RATIO);
               terrainHeights[idx] = profiledTerrainHeight;
               detailHeights[idx] = profiledTerrainHeight;
            }

            if (previewWater.inlandWater()[idx] || previewWater.waterfallDrop()[idx]) {
               int resolvedWaterSurface = previewWater.waterSurface()[idx];
               int depth = Math.max(1, resolvedWaterSurface - terrainSurface);
               int color = previewWater.waterfallDrop()[idx]
                  ? PREVIEW_FLAT_WATER_COLOR
                  : waterColorForDepth(depth);
               terrainColors[idx] = color;
               detailColors[idx] = color;
               detailHeightOffsets[idx] = 0.0F;
               waterColors[idx] = color;
               waterSurfaceHeights[idx] = previewSeaSurfaceHeight
                  + (float)((resolvedWaterSurface - seaLevel) / PREVIEW_RADIUS_BLOCKS * PREVIEW_VERTICAL_CELL_RATIO);
            } else if (oceanWaterMask[idx]) {
               int resolvedWaterSurface = oceanWaterSurface[idx];
               terrainSurface = Math.min(rawTerrain[idx], resolvedWaterSurface - 1);
               if (oceanProfileAvailable) {
                  try {
                     terrainSurface = waterResolver.resolveOceanTerrainSurface(
                        Mth.floor(sampleWorldX[x]), Mth.floor(sampleWorldZ[z]), resolvedWaterSurface, previewResolutionMeters
                     );
                  } catch (OceanCoverageUnavailableException error) {
                     oceanProfileAvailable = false;
                     Tellus.LOGGER.debug("Ocean coast profile unavailable while building terrain preview", error);
                  }
               }

               int color = waterColorForDepth(Math.max(1.0, resolvedWaterSurface - terrainSurface));
               terrainColors[idx] = color;
               detailColors[idx] = color;
               detailHeightOffsets[idx] = 0.0F;
               waterColors[idx] = color;
               blockHeights[idx] = terrainSurface;
               float previewWaterSurfaceHeight = previewSeaSurfaceHeight
                  + (float)((resolvedWaterSurface - seaLevel) / PREVIEW_RADIUS_BLOCKS * PREVIEW_VERTICAL_CELL_RATIO);
               float profiledHeight = previewSeaSurfaceHeight
                  + (float)((terrainSurface - seaLevel) / PREVIEW_RADIUS_BLOCKS * PREVIEW_VERTICAL_CELL_RATIO);
               terrainHeights[idx] = profiledHeight;
               detailHeights[idx] = profiledHeight;
               waterSurfaceHeights[idx] = previewWaterSurfaceHeight;
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
      List<RoadAreaFeature> roadAreas,
      TerrainPreview.RoadRasterProgress progress
   ) {
      if (this.shouldAbortRequest(requestId)) {
         return false;
      }

      progress.onProgress(0.0F);
      double worldScale = settings.worldScale();
      WorldProjection projection = settings.projection();
      TerrainPreview.PreviewRoadWidths widths = previewRoadWidths(settings.worldScale());
      List<RoadFeature> main = new ArrayList<>();
      List<RoadFeature> normal = new ArrayList<>();
      List<RoadFeature> dirt = new ArrayList<>();

      for (RoadFeature road : roads) {
         if (this.shouldAbortRequest(requestId)) {
            return false;
         }
         if (BridgeSupportLayout.crossesWorldSeam(road, projection)) {
            continue;
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
      byte[] selectedStyle = new byte[area];
      boolean[] selectedMarking = new boolean[area];
      boolean[] blocked = new boolean[area];
      TerrainPreview.RoadRasterProgressTracker rasterProgress = new TerrainPreview.RoadRasterProgressTracker(
         countRoadSegments(main) + countRoadSegments(normal) + countRoadSegments(dirt), size, progress
      );
      if (!this.rasterizeRoadPreviewClass(
         requestId, main, 1, widths.main(), projection, minWorldX, minWorldZ, step, size, selectedClass, selectedStyle, selectedMarking, blocked, rasterProgress
      )) {
         return false;
      }

      if (!this.rasterizeRoadPreviewClass(
         requestId, normal, 2, widths.normal(), projection, minWorldX, minWorldZ, step, size, selectedClass, selectedStyle, selectedMarking, blocked, rasterProgress
      )) {
         return false;
      }

      if (!this.rasterizeRoadPreviewClass(
         requestId, dirt, 3, widths.dirt(), projection, minWorldX, minWorldZ, step, size, selectedClass, selectedStyle, selectedMarking, blocked, rasterProgress
      )) {
         return false;
      }

      this.rasterizeRoadAreaPreview(requestId, roadAreas, projection, minWorldX, minWorldZ, step, size, selectedClass, selectedStyle);

      for (int z = 0; z < size; z++) {
         if (this.shouldAbortRequest(requestId)) {
            return false;
         }

         int row = z * size;

         for (int x = 0; x < size; x++) {
            int idx = row + x;
            byte cls = selectedClass[idx];
            if (cls > 0) {
               int roadColor = selectedMarking[idx] ? PREVIEW_ROAD_MARKING_COLOR : roadPreviewColor(selectedStyle[idx], cls);
               terrainColors[idx] = roadColor;
               detailColors[idx] = roadColor;
            }
         }

         rasterProgress.onPaintRow();
      }

      rasterProgress.finish();
      return !this.shouldAbortRequest(requestId);
   }

   private void rasterizeRoadAreaPreview(
      int requestId,
      List<RoadAreaFeature> roadAreas,
      WorldProjection projection,
      double minWorldX,
      double minWorldZ,
      double step,
      int size,
      byte[] selectedClass,
      byte[] selectedStyle
   ) {
      if (roadAreas == null || roadAreas.isEmpty() || !(projection.worldScale() > 0.0)) {
         return;
      }

      for (RoadAreaFeature area : roadAreas) {
         if (this.shouldAbortRequest(requestId)) {
            return;
         }

         double areaX1 = projection.lonToBlockX(area.minLon());
         double areaX2 = projection.lonToBlockX(area.maxLon());
         int minGridX = Mth.clamp((int)Math.floor((Math.min(areaX1, areaX2) - minWorldX) / step), 0, size - 1);
         int maxGridX = Mth.clamp((int)Math.ceil((Math.max(areaX1, areaX2) - minWorldX) / step), 0, size - 1);
         int minGridZ = Mth.clamp((int)Math.floor((projection.latToBlockZ(area.maxLat()) - minWorldZ) / step), 0, size - 1);
         int maxGridZ = Mth.clamp((int)Math.ceil((projection.latToBlockZ(area.minLat()) - minWorldZ) / step), 0, size - 1);
         byte classId = (byte)roadClassId(area.roadClass());
         byte style = RoadSurfaceStyle.surfaceStyleId(area.roadClass(), area.highwayTag(), area.roadSurface(), area.subclass(), 0, 0);
         for (int gz = minGridZ; gz <= maxGridZ; gz++) {
            double sampleZ = minWorldZ + gz * step;
            int row = gz * size;
            for (int gx = minGridX; gx <= maxGridX; gx++) {
               int index = row + gx;
               if (selectedClass[index] > 0) {
                  continue;
               }

               double sampleX = minWorldX + gx * step;
               double lon = projection.blockXToLon(sampleX);
               double lat = projection.blockZToLat(sampleZ);
               if (area.containsLonLat(lon, lat)) {
                  selectedClass[index] = classId;
                  selectedStyle[index] = style;
               }
            }
         }
      }
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
      WorldProjection projection = settings.projection();
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
            feature, resolvePreviewBuildingGroupId(feature), groundContact, minWorldX, minWorldZ, step, size, projection
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
            BuildingBlueprint blueprint = this.previewBlueprintForFeature(
               rasterized.feature(), rasterized.groupId(), group.baseY(), profile, projection
            );
            TerrainPreview.PreviewBoundaryInfo boundaryInfo = this.computePreviewBoundaryInfo(rasterized);

            for (int order = 0; order < rasterized.occupiedIndices().length; order++) {
               int index = rasterized.occupiedIndices()[order];
               int gridX = index % size;
               int gridZ = index / size;
               int worldX = (int)Math.round(minWorldX + gridX * step);
               int worldZ = (int)Math.round(minWorldZ + gridZ * step);
               int boundaryDistance = boundaryInfo.boundaryDistance(order);
               int roofY = blueprint.roofTopY(worldX, worldZ, boundaryDistance);
               float roofHeight = previewHeightForSurfaceY(roofY, settings, heightCenter);
               int previewColor = previewBuildingCellColor(profile, blueprint, boundaryDistance, worldX, worldZ);
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
            BuildingBlueprint blueprint = this.previewBlueprintForFeature(
               rasterized.feature(), rasterized.groupId(), group.baseY(), profile, projection
            );
            TerrainPreview.PreviewBoundaryInfo boundaryInfo = this.computePreviewBoundaryInfo(rasterized);

            for (int order = 0; order < rasterized.occupiedIndices().length; order++) {
               int index = rasterized.occupiedIndices()[order];
               if (!partCoverage[index]) {
                  int gridX = index % size;
                  int gridZ = index / size;
                  int worldX = (int)Math.round(minWorldX + gridX * step);
                  int worldZ = (int)Math.round(minWorldZ + gridZ * step);
                  int boundaryDistance = boundaryInfo.boundaryDistance(order);
                  int roofY = blueprint.roofTopY(worldX, worldZ, boundaryDistance);
                  float roofHeight = previewHeightForSurfaceY(roofY, settings, heightCenter);
                  int previewColor = previewBuildingCellColor(profile, blueprint, boundaryDistance, worldX, worldZ);
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
      WorldProjection projection
   ) {
      if (!(projection.worldScale() > 0.0) || !(step > 0.0) || featureCrossesWorldSeam(feature, projection)) {
         return null;
      }

      double minBlockX = feature.minBlockX(projection);
      double maxBlockX = feature.maxBlockX(projection);
      double minBlockZ = feature.minBlockZ(projection);
      double maxBlockZ = feature.maxBlockZ(projection);
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
            if (feature.containsWorld(worldX, worldZ, projection)) {
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
      WorldProjection projection,
      double minWorldX,
      double minWorldZ,
      double step,
      int size,
      byte[] selectedClass,
      byte[] selectedStyle,
      boolean[] selectedMarking,
      boolean[] blocked,
      TerrainPreview.RoadRasterProgressTracker progressTracker
   ) {
      if (!roads.isEmpty() && widthBlocks > 0 && !(step <= 0.0)) {
         int area = size * size;
         boolean[] candidates = new boolean[area];
         byte[] candidateStyle = new byte[area];
         boolean[] candidateMarking = new boolean[area];
         double markingDistanceSq = Math.max(0.35, step * step * 0.25);

         for (RoadFeature road : roads) {
            if (this.shouldAbortRequest(requestId)) {
               return false;
            }

            int points = road.pointCount();
            if (points >= 2) {
               double[] worldX = new double[points];
               double[] worldZ = new double[points];

               for (int i = 0; i < points; i++) {
                  worldX[i] = projection.lonToBlockX(road.lonAt(i));
                  worldZ[i] = projection.latToBlockZ(road.latAt(i));
               }

               int featureWidth = RoadSurfaceStyle.effectiveRoadWidth(road, widthBlocks, projection.worldScale());
               double halfWidth = Math.max(0.5, (featureWidth - 1) * 0.5);
               double radiusSq = halfWidth * halfWidth + 1.0E-6;
               byte style = RoadSurfaceStyle.surfaceStyleId(road, 0, 0);
               double segmentStart = 0.0;
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
                     double segmentLength = Math.sqrt(lenSq);
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
                              int index = row + gx;
                              candidates[index] = true;
                              candidateStyle[index] = style;
                              double lateralDistance = ((sampleX - x1) * -dz + (sampleZ - z1) * dx) / segmentLength;
                              candidateMarking[index] |= RoadSurfaceStyle.shouldDrawLaneMarking(
                                 road, featureWidth, segmentStart + t * segmentLength, lateralDistance, Math.sqrt(distSq), Math.sqrt(markingDistanceSq)
                              );
                           }
                        }
                     }

                     segmentStart += segmentLength;
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
               selectedStyle[ix] = candidateStyle[ix] == 0 ? RoadSurfaceStyle.STYLE_PAVED_DARK : candidateStyle[ix];
               selectedMarking[ix] = candidateMarking[ix];
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
      WorldProjection projection,
      double minWorldX,
      double minWorldZ,
      double step,
      int size,
      byte[] waterKind,
      boolean[] lineWaterMask,
      boolean[] areaWaterMask,
      boolean[] flowingWaterMask
   ) {
      if (waterFeatureCrossesWorldSeam(feature, projection)) {
         return true;
      }
      byte kind = (byte)(feature.oceanHint() ? 2 : 1);
      return feature.lineGeometry()
         ? this.rasterizeWaterPreviewLineFeature(
            requestId,
            feature,
            kind,
            sampleWorldX,
            sampleWorldZ,
            projection,
            minWorldX,
            minWorldZ,
            step,
            size,
            waterKind,
            lineWaterMask,
            flowingWaterMask
         )
         : this.rasterizeWaterPreviewPolygonFeature(
            requestId,
            feature,
            kind,
            sampleLon,
            sampleLat,
            projection,
            minWorldX,
            minWorldZ,
            step,
            size,
            waterKind,
            lineWaterMask,
            areaWaterMask,
            flowingWaterMask
         );
   }

   private boolean rasterizeWaterPreviewPolygonFeature(
      int requestId,
      OsmWaterFeature feature,
      byte kind,
      double[] sampleLon,
      double[] sampleLat,
      WorldProjection projection,
      double minWorldX,
      double minWorldZ,
      double step,
      int size,
      byte[] waterKind,
      boolean[] lineWaterMask,
      boolean[] areaWaterMask,
      boolean[] flowingWaterMask
   ) {
      double x0 = projection.lonToBlockX(feature.minLon());
      double x1 = projection.lonToBlockX(feature.maxLon());
      double minBlockX = Math.min(x0, x1);
      double maxBlockX = Math.max(x0, x1);
      double z0 = projection.latToBlockZ(feature.minLat());
      double z1 = projection.latToBlockZ(feature.maxLat());
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
               markWaterPreviewCell(
                  waterKind,
                  lineWaterMask,
                  flowingWaterMask,
                  row + gx,
                  kind,
                  false,
                  feature.flowingWater()
               );
               if (kind == 1) {
                  areaWaterMask[row + gx] = true;
               }
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
      WorldProjection projection,
      double minWorldX,
      double minWorldZ,
      double step,
      int size,
      byte[] waterKind,
      boolean[] lineWaterMask,
      boolean[] flowingWaterMask
   ) {
      double halfWidth = Math.max(0.5, step * 0.55);
      double radiusSq = halfWidth * halfWidth + 1.0E-6;

      for (int part = 0; part < feature.partCount(); part++) {
         int points = feature.pointCount(part);
         if (points < 2) {
            continue;
         }

         double previousX = projection.lonToBlockX(feature.lonAt(part, 0));
         double previousZ = projection.latToBlockZ(feature.latAt(part, 0));

         for (int point = 1; point < points; point++) {
            if (this.shouldAbortRequest(requestId)) {
               return false;
            }

            double currentX = projection.lonToBlockX(feature.lonAt(part, point));
            double currentZ = projection.latToBlockZ(feature.latAt(part, point));
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
                        markWaterPreviewCell(
                           waterKind,
                           lineWaterMask,
                           flowingWaterMask,
                           row + gx,
                           kind,
                           true,
                           feature.flowingWater()
                        );
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

   private static void markWaterPreviewCell(
      byte[] waterKind,
      boolean[] lineWaterMask,
      boolean[] flowingWaterMask,
      int index,
      byte kind,
      boolean lineWater,
      boolean flowingWater
   ) {
      if (kind > waterKind[index]) {
         waterKind[index] = kind;
      }
      if (kind == 1) {
         lineWaterMask[index] |= lineWater;
         flowingWaterMask[index] |= flowingWater;
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

   private static int roadClassId(RoadClass roadClass) {
      return switch (roadClass) {
         case MAIN -> 1;
         case NORMAL -> 2;
         case DIRT -> 3;
      };
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

   private static int roadPreviewColor(byte style, byte roadClass) {
      return switch (style) {
         case RoadSurfaceStyle.STYLE_PAVED_LIGHT -> PREVIEW_ROAD_PAVED_LIGHT_COLOR;
         case RoadSurfaceStyle.STYLE_PAVED_SMOOTH -> PREVIEW_ROAD_PAVED_SMOOTH_COLOR;
         case RoadSurfaceStyle.STYLE_PEDESTRIAN -> PREVIEW_ROAD_PEDESTRIAN_COLOR;
         case RoadSurfaceStyle.STYLE_GRAVEL -> PREVIEW_ROAD_GRAVEL_COLOR;
         case RoadSurfaceStyle.STYLE_DIRT -> PREVIEW_ROAD_DIRT_COLOR;
         case RoadSurfaceStyle.STYLE_COBBLESTONE -> PREVIEW_ROAD_COBBLESTONE_COLOR;
         case RoadSurfaceStyle.STYLE_STONE_PAVERS -> PREVIEW_ROAD_STONE_PAVERS_COLOR;
         case RoadSurfaceStyle.STYLE_BRICK -> PREVIEW_ROAD_BRICK_COLOR;
         case RoadSurfaceStyle.STYLE_SAND -> PREVIEW_ROAD_SAND_COLOR;
         case RoadSurfaceStyle.STYLE_WOOD -> PREVIEW_ROAD_WOOD_COLOR;
         case RoadSurfaceStyle.STYLE_CONCRETE -> PREVIEW_ROAD_CONCRETE_COLOR;
         default -> roadClass == 3 ? PREVIEW_ROAD_GRAVEL_COLOR : PREVIEW_ROAD_PAVED_DARK_COLOR;
      };
   }

   private BuildingBlueprint previewBlueprintForFeature(
      OsmBuildingFeature feature, String groupId, int baseY, BuildingProfile profile, WorldProjection projection
   ) {
      double worldScale = projection.worldScale();
      int floorY = baseY + this.previewBuildingMinHeightBlocks(feature.minHeightMeters(), worldScale) + 1;
      int roofBaseY = Math.max(baseY + this.previewBuildingHeightBlocks(feature.heightMeters(), worldScale), floorY + profile.floorCount() * profile.storeyHeightBlocks());
      int topY = roofBaseY + Math.max(profile.parapetHeight(), profile.roofRise());
      return TellusBuildingBlueprints.create(
         groupId, feature, profile, 0L, baseY, floorY, roofBaseY, topY, List.of(), projection
      );
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

   private static int previewBuildingCellColor(BuildingProfile profile, BuildingBlueprint blueprint, int boundaryDistance, int worldX, int worldZ) {
      ArnisBuildingRules.RooftopEquipment equipment = ArnisBuildingRules.rooftopEquipmentAt(blueprint, boundaryDistance, worldX, worldZ);
      if (equipment != ArnisBuildingRules.RooftopEquipment.NONE) {
         return switch (equipment) {
            case HVAC -> 0xB8BEC1;
            case SOLAR_PANEL -> 0x273643;
            case ANTENNA, VENT_STACK -> 0x5E6468;
            case WATER_TANK -> 0x756044;
            case ROOF_ACCESS -> 0x7C8286;
            case NONE -> BUILDING_PREVIEW_COLOR;
         };
      }

      int base = previewColorForProfile(profile, blueprint);
      return boundaryDistance == 0 ? blendPreviewColor(base, 0xE7E2D5, 0.18F) : base;
   }

   private static int blendPreviewColor(int base, int overlay, float amount) {
      float clamped = Mth.clamp(amount, 0.0F, 1.0F);
      int br = base >> 16 & 255;
      int bg = base >> 8 & 255;
      int bb = base & 255;
      int or = overlay >> 16 & 255;
      int og = overlay >> 8 & 255;
      int ob = overlay & 255;
      int r = Mth.clamp(Math.round(br + (or - br) * clamped), 0, 255);
      int g = Mth.clamp(Math.round(bg + (og - bg) * clamped), 0, 255);
      int b = Mth.clamp(Math.round(bb + (ob - bb) * clamped), 0, 255);
      return r << 16 | g << 8 | b;
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

   private void updateBuildStatus(int id, long buildDone, long buildTotal, String activity, String detail) {
      float progress = buildTotal <= 0L ? 1.0F : Mth.clamp((float)buildDone / (float)buildTotal, 0.0F, 1.0F);
      this.updateStatus(id, TerrainPreview.PreviewStage.LOADING, progress, activity, detail);
   }

   private static String formatCountProgress(String label, long done, long total) {
      long safeTotal = Math.max(1L, total);
      long safeDone = Math.max(0L, Math.min(done, safeTotal));
      String key = switch (label) {
         case "Terrain samples" -> "tellus.preview.progress.terrain_samples";
         case "Land-cover samples" -> "tellus.preview.progress.land_cover_samples";
         case "Climate samples" -> "tellus.preview.progress.climate_samples";
         case "Canopy tiles" -> "tellus.preview.progress.canopy_tiles";
         case "Height rows" -> "tellus.preview.progress.height_rows";
         case "Center rows" -> "tellus.preview.progress.center_rows";
         case "Color rows" -> "tellus.preview.progress.color_rows";
         case "Coverage samples" -> "tellus.preview.progress.coverage_samples";
         case "Tree rows" -> "tellus.preview.progress.tree_rows";
         case "Feature rows" -> "tellus.preview.progress.feature_rows";
         default -> null;
      };
      return key == null
         ? String.format(Locale.ROOT, "%s %d/%d", label, safeDone, safeTotal)
         : Component.translatable(key, safeDone, safeTotal).getString();
   }

   private static String formatPercentProgress(String label, float progress) {
      String percent = String.format(Locale.ROOT, "%.0f", Mth.clamp(progress, 0.0F, 1.0F) * 100.0F);
      return "Raster progress".equals(label)
         ? Component.translatable("tellus.preview.progress.raster", percent).getString()
         : label + " " + percent + "%";
   }

   private static String formatBytes(long bytes) {
      if (bytes >= 1024L * 1024L) {
         return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
      } else if (bytes >= 1024L) {
         return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
      } else {
         return String.format(Locale.ROOT, "%d B", Math.max(0L, bytes));
      }
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

   private static int climateBasedBuiltUpPreviewCoverClass(String koppen) {
      if (koppen == null || koppen.isBlank()) {
         return MountainSurfaceRules.ESA_GRASSLAND;
      }

      return switch (koppen.trim().toUpperCase(Locale.ROOT)) {
         case "BWH", "BWK" -> MountainSurfaceRules.ESA_BARE;
         case "AW", "BSH" -> MountainSurfaceRules.ESA_SHRUBLAND;
         default -> MountainSurfaceRules.ESA_GRASSLAND;
      };
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
      double demSlopeDegrees,
      int seaLevel,
      boolean esaWater,
      boolean oceanWater,
      boolean fallbackInlandWaterEnabled,
      boolean flattenWaterColor,
      boolean remaSnowTerrain,
      boolean desert,
      boolean badlands,
      int worldX,
      int worldZ
   ) {
      int surfaceCoverClass = MountainSurfaceRules.resolveSurfaceCoverClass(terrainCoverClass, visualCoverClass);
      double waterDepth = previewWaterDepthBlocks(surfaceCoverClass, esaWater, terrainHeight, seaLevel, oceanWater, fallbackInlandWaterEnabled);
      if (waterDepth >= 0.0) {
         return flattenWaterColor ? PREVIEW_FLAT_WATER_COLOR : waterColorForDepth(waterDepth);
      } else {
         boolean snowSource = MountainSurfaceRules.hasSnowSource(surfaceCoverClass, remaSnowTerrain);
         if (snowSource && SnowSlopePolicy.shouldCover(worldX, worldZ, demSlopeDegrees)) {
            return 16119285;
         }
         if (badlands) {
            return badlandsPlateauPreviewColor(worldX, worldZ);
         }
         if (DeepslateSlopePolicy.shouldCover(worldX, worldZ, demSlopeDegrees)) {
            return PREVIEW_DEEPSLATE_COLOR;
         }
         if (MountainSurfaceRules.preservesBiomeSurfacePalette(desert, badlands)) {
            int base = baseColorForCover(MountainSurfaceRules.ESA_BARE, elevationMeters);
            return applyClimateTint(base, climateGroup, MountainSurfaceRules.ESA_BARE);
         }
         if (snowSource) {
            return mountainPreviewColor(MountainSurfaceRules.ApproximatePalette.STONE);
         }

         int heightAboveSea = (int)Math.round(terrainHeight) - seaLevel;
         float vegetationTransitionWeight = MountainSurfaceRules.vegetationTransitionWeightForSurfaceCoverClass(surfaceCoverClass, heightAboveSea);
         MountainSurfaceRules.ApproximateSurface mountainSurface = MountainSurfaceRules.classifyApproximateSurface(
            terrainCoverClass,
            visualCoverClass,
            heightAboveSea,
            slopeDiff,
            convexity,
            remaSnowTerrain,
            vegetationTransitionWeight,
            worldX,
            worldZ
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

   private static boolean isPreviewDesert(int terrainCoverClass, int visualCoverClass, String koppen) {
      int surfaceCoverClass = MountainSurfaceRules.resolveSurfaceCoverClass(terrainCoverClass, visualCoverClass);
      var biomeKey = BiomeClassification.findBiomeKey(surfaceCoverClass, koppen);
      if (biomeKey == null) {
         biomeKey = BiomeClassification.findFallbackKey(surfaceCoverClass);
      }

      return Biomes.DESERT.equals(biomeKey);
   }

   private static boolean isPreviewBadlands(int terrainCoverClass, int visualCoverClass, String koppen, double regionalReliefMeters) {
      int surfaceCoverClass = MountainSurfaceRules.resolveSurfaceCoverClass(terrainCoverClass, visualCoverClass);
      var biomeKey = BiomeClassification.findBiomeKey(surfaceCoverClass, koppen);
      if (biomeKey == null) {
         biomeKey = BiomeClassification.findFallbackKey(surfaceCoverClass);
      }

      return Biomes.BADLANDS.equals(biomeKey)
         || BadlandsTerrainPolicy.shouldPromoteToBadlands(surfaceCoverClass, koppen, regionalReliefMeters);
   }

   private static double previewRegionalReliefMeters(
      double[] elevations, int size, int index, double step, double worldScale
   ) {
      if (!(worldScale > 0.0) || !(step > 0.0)) {
         return Double.NaN;
      }

      int x = index % size;
      int z = index / size;
      int sampleRadius = Math.max(1, (int)Math.ceil(BadlandsTerrainPolicy.CANYON_RELIEF_SAMPLE_METERS / worldScale / step));
      int west = Math.max(0, x - sampleRadius);
      int east = Math.min(size - 1, x + sampleRadius);
      int north = Math.max(0, z - sampleRadius);
      int south = Math.min(size - 1, z + sampleRadius);
      double center = elevations[index];
      double westElevation = elevations[west + z * size];
      double eastElevation = elevations[east + z * size];
      double northElevation = elevations[x + north * size];
      double southElevation = elevations[x + south * size];
      double min = Math.min(center, Math.min(Math.min(westElevation, eastElevation), Math.min(northElevation, southElevation)));
      double max = Math.max(center, Math.max(Math.max(westElevation, eastElevation), Math.max(northElevation, southElevation)));
      return max - min;
   }

   private static int badlandsPlateauPreviewColor(int worldX, int worldZ) {
      return switch (BadlandsTerrainPolicy.plateauMaterialIndex(worldX, worldZ)) {
         case BadlandsTerrainPolicy.PLATEAU_COARSE_DIRT -> 0x76543A;
         case BadlandsTerrainPolicy.PLATEAU_TERRACOTTA -> 0x985E44;
         case BadlandsTerrainPolicy.PLATEAU_BROWN_TERRACOTTA -> 0x4D3323;
         default -> 0xBE6621;
      };
   }

   private static int badlandsBandPreviewColor(int worldX, int worldZ, int y) {
      return switch (BadlandsTerrainPolicy.bandMaterialIndex(worldX, worldZ, y)) {
         case BadlandsTerrainPolicy.ORANGE_TERRACOTTA -> 0xA9541A;
         case BadlandsTerrainPolicy.YELLOW_TERRACOTTA -> 0xBA8524;
         case BadlandsTerrainPolicy.BROWN_TERRACOTTA -> 0x4D3323;
         case BadlandsTerrainPolicy.RED_TERRACOTTA -> 0x8E3C2E;
         case BadlandsTerrainPolicy.LIGHT_GRAY_TERRACOTTA -> 0x876B62;
         case BadlandsTerrainPolicy.WHITE_TERRACOTTA -> 0xD1B1A1;
         default -> 0x985E44;
      };
   }

   private static int mountainPreviewColor(MountainSurfaceRules.ApproximatePalette palette) {
      return switch (palette) {
         case STONE -> 0x888B8E;
         case SNOW, SNOW_STREAK -> 16119285;
         default -> 0x888B8E;
      };
   }

   // Keep preview shading lightweight; the full water resolver is too expensive to run per pixel.
   private static double previewWaterDepthBlocks(
      int coverClass, boolean esaWater, double terrainHeight, int seaLevel, boolean oceanWater, boolean fallbackInlandWaterEnabled
   ) {
      if (oceanWater) {
         return Math.max(0.0, seaLevel - terrainHeight);
      } else if (!fallbackInlandWaterEnabled) {
         return -1.0;
      } else if (esaWater || coverClass == ESA_WATER) {
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

   private static boolean featureCrossesWorldSeam(OsmBuildingFeature feature, WorldProjection projection) {
      return feature.crossesWorldSeam(projection);
   }

   private static boolean waterFeatureCrossesWorldSeam(OsmWaterFeature feature, WorldProjection projection) {
      return feature.crossesWorldSeam(projection);
   }

   private boolean useOceanZoom(double blockX, double blockZ, WorldProjection projection) {
      TellusLandMaskSource.LandMaskSample landSample = this.landMaskSource.sampleLandMask(blockX, blockZ, projection);
      if (landSample.known() && landSample.land()) {
         return false;
      }

      return useOceanZoom(landSample);
   }

   private static boolean useOceanZoom(TellusLandMaskSource.LandMaskSample landSample) {
      if (!landSample.known()) {
         return false;
      } else if (landSample.land()) {
         return false;
      } else {
         return true;
      }
   }

   private static boolean isPreviewOceanFallback(
      TellusLandMaskSource.LandMaskSample landMaskSample,
      int surfaceY,
      int coverClass,
      int seaLevel,
      boolean mapterhornLandOverride
   ) {
      if (mapterhornLandOverride) {
         return false;
      }
      if (landMaskSample.known()) {
         return !landMaskSample.land();
      } else {
         return coverClass == ESA_NO_DATA && surfaceY <= seaLevel;
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
      this.requestId.incrementAndGet();
      Future<TerrainPreview.PreviewMesh> future = this.pending;
      this.pending = null;
      if (future != null) {
         future.cancel(true);
      }

      this.mesh = null;
      this.baseSnapshot = null;
      this.info.set(null);
      this.executor.shutdownNow();
   }

   private void updateStatus(int id, TerrainPreview.PreviewStage stage, float progress) {
      this.updateStatus(id, stage, progress, null, null);
   }

   private void updateStatus(int id, TerrainPreview.PreviewStage stage, float progress, String activity) {
      this.updateStatus(id, stage, progress, activity, null);
   }

   private void updateStatus(int id, TerrainPreview.PreviewStage stage, float progress, String activity, String detail) {
      if (id == this.requestId.get()) {
         this.status.set(new TerrainPreview.PreviewStatus(stage, Mth.clamp(progress, 0.0F, 1.0F), activity, detail));
      }
   }

   private boolean shouldAbortRequest(int id) {
      return Thread.currentThread().isInterrupted() || id != this.requestId.get();
   }

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

      private String detail() {
         if (this.requestsStarted <= 0L) {
            return null;
         }

         String requestDetail = Component.translatable(
            "tellus.preview.progress.requests", this.requestsCompleted, this.requestsStarted
         ).getString();
         if (this.knownBytesTotal > 0L) {
            return requestDetail + ", " + formatBytes(this.knownBytesRead) + "/" + formatBytes(this.knownBytesTotal);
         } else {
            return this.knownBytesRead > 0L ? requestDetail + ", " + formatBytes(this.knownBytesRead) : requestDetail;
         }
      }
   }

   private final class DownloadStageProgress implements DownloadProgressReporter.Listener {
      private final int requestId;
      private final long sampleTotal;
      private final TerrainPreview.DownloadNetworkTracker networkTracker = new TerrainPreview.DownloadNetworkTracker();
      private long sampleDone;
      private long phaseDone;
      private long phaseTotal = 1L;
      private long lastPublishMs;
      private float emittedProgress;
      private String activity;
      private String phaseLabel;

      private DownloadStageProgress(int requestId, long sampleTotal) {
         this.requestId = requestId;
         this.sampleTotal = Math.max(1L, sampleTotal);
      }

      @Override
      public void onRequestStarted(long expectedBytes) {
         this.networkTracker.onRequestStarted(expectedBytes);
         this.publish(false);
      }

      @Override
      public void onBytesRead(int bytes) {
         this.networkTracker.onBytesRead(bytes);
         this.publish(false);
      }

      @Override
      public void onRequestFinished() {
         this.networkTracker.onRequestFinished();
         this.publish(false);
      }

      private void updateSamples(long sampleDone, long phaseDone, long phaseTotal, String activity, String phaseLabel) {
         boolean phaseChanged = !Objects.equals(this.activity, activity) || !Objects.equals(this.phaseLabel, phaseLabel);
         this.sampleDone = Math.max(this.sampleDone, sampleDone);
         this.phaseDone = phaseDone;
         this.phaseTotal = Math.max(1L, phaseTotal);
         this.activity = activity;
         this.phaseLabel = phaseLabel;
         this.publish(phaseChanged);
      }

      private void finish(String activity, String phaseLabel) {
         this.sampleDone = this.sampleTotal;
         this.phaseDone = this.phaseTotal;
         this.activity = activity;
         this.phaseLabel = phaseLabel;
         this.publish(true);
      }

      private void publish(boolean force) {
         long now = System.currentTimeMillis();
         float progress = Mth.clamp((float)this.sampleDone / (float)this.sampleTotal, 0.0F, 1.0F);
         if (force || !(progress < this.emittedProgress + 0.0015F) || now - this.lastPublishMs >= STATUS_PUBLISH_INTERVAL_MS) {
            this.lastPublishMs = now;
            this.emittedProgress = Math.max(this.emittedProgress, progress);
            TerrainPreview.this.updateStatus(
               this.requestId, TerrainPreview.PreviewStage.DOWNLOADING, this.emittedProgress, this.activity, this.detail()
            );
         }
      }

      private String detail() {
         String phaseDetail = this.phaseLabel == null ? null : formatCountProgress(this.phaseLabel, this.phaseDone, this.phaseTotal);
         String networkDetail = this.networkTracker.detail();
         if (phaseDetail == null) {
            return networkDetail;
         } else {
            return networkDetail == null ? phaseDetail : phaseDetail + " | " + networkDetail;
         }
      }
   }

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
      private String detail;

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

      private void updateFetch(float value, String detail) {
         this.fetchProgress = Math.max(this.fetchProgress, Mth.clamp(value, 0.0F, 1.0F));
         this.activity = this.fetchActivity;
         this.detail = detail;
         this.publish();
      }

      private void updateRaster(float value) {
         this.rasterProgress = Math.max(this.rasterProgress, Mth.clamp(value, 0.0F, 1.0F));
         this.activity = this.rasterActivity;
         this.detail = formatPercentProgress("Raster progress", this.rasterProgress);
         this.publish();
      }

      private void finish() {
         this.fetchProgress = 1.0F;
         this.rasterProgress = 1.0F;
         this.activity = this.rasterActivity;
         this.detail = formatPercentProgress("Raster progress", 1.0F);
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
         TerrainPreview.this.updateStatus(this.requestId, TerrainPreview.PreviewStage.PROCESSING_OSM, totalProgress, this.activity, this.detail);
      }

      private float overlayStageProgress() {
         float weightSum = this.fetchWeight + this.rasterWeight;
         return weightSum <= 0.0F
            ? Math.max(this.fetchProgress, this.rasterProgress)
            : Mth.clamp((this.fetchProgress * this.fetchWeight + this.rasterProgress * this.rasterWeight) / weightSum, 0.0F, 1.0F);
      }
   }

   private record PreviewBaseSnapshot(
      double worldScale,
      double terrestrialHeightScale,
      double oceanicHeightScale,
      int heightOffset,
      double spawnLatitude,
      double spawnLongitude,
      boolean centerWorldOnSpawn,
      EarthGeneratorSettings.DemSelection demSelection,
      boolean osmWaterEnabled,
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
      boolean[] oceanFallbackMask,
      boolean[] mapterhornLandOverride,
      double[] elevations,
      int[] coverClasses,
      int[] visualCoverClasses,
      byte[] climateGroups,
      String[] climateCodes,
      byte[] climateBasedBuiltUpCoverClasses,
      boolean[] desertMask,
      boolean[] badlandsMask,
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

   private record PreviewHeightData(float[] heights, float center) {
   }

   private record PreviewTree(
      int gridX,
      int gridZ,
      float height,
      float canopyScale,
      float trunkScale,
      float crownBaseRatio,
      float leanX,
      float leanZ,
      boolean bush,
      TellusProceduralTreeGenerator.Profile profile,
      int trunkColor,
      int leafColor,
      int sourceColor
   ) {
      private PreviewTree {
         profile = Objects.requireNonNull(profile, "profile");
      }
   }

   private record PreviewBadlandsData(
      boolean[] mask,
      double minWorldX,
      double minWorldZ,
      double step,
      double blocksPerHeightUnit,
      double worldYAtPreviewZero
   ) {
      private PreviewBadlandsData {
         mask = Objects.requireNonNull(mask, "mask");
      }

      private TerrainPreview.PreviewBadlandsData copy() {
         return new TerrainPreview.PreviewBadlandsData(
            this.mask.clone(),
            this.minWorldX,
            this.minWorldZ,
            this.step,
            this.blocksPerHeightUnit,
            this.worldYAtPreviewZero
         );
      }

      private int worldX(int gridX) {
         return (int)Math.round(this.minWorldX + gridX * this.step);
      }

      private int worldZ(int gridZ) {
         return (int)Math.round(this.minWorldZ + gridZ * this.step);
      }

      private double worldY(float previewY) {
         return this.worldYAtPreviewZero + previewY * this.blocksPerHeightUnit;
      }
   }

   private static final class PreviewMesh {
      private final int size;
      private final int granularity;
      private final float[] terrainHeights;
      private final int[] terrainColors;
      private final float[] detailHeights;
      private final int[] detailColors;
      private final int[] waterColors;
      private final float[] waterSurfaceHeights;
      private final TerrainPreview.PreviewBadlandsData badlands;
      private final float[] axis;
      private final TerrainPreview.PreviewTree[] trees;
      private final TerrainPreview.PreviewInfo info;
      private volatile TerrainPreview.PreviewGeometry terrainGeometry;
      private volatile TerrainPreview.PreviewGeometry detailGeometry;
      private volatile TerrainPreview.PreviewGeometry clouds;

      private PreviewMesh(
         int size,
         int granularity,
         float[] terrainHeights,
         int[] terrainColors,
         float[] detailHeights,
         int[] detailColors,
         int[] waterColors,
         float[] waterSurfaceHeights,
         TerrainPreview.PreviewBadlandsData badlands,
         float[] axis,
         TerrainPreview.PreviewTree[] trees,
         TerrainPreview.PreviewInfo info
      ) {
         this.size = size;
         this.granularity = granularity;
         this.terrainHeights = terrainHeights;
         this.terrainColors = terrainColors;
         this.detailHeights = detailHeights;
         this.detailColors = detailColors;
         this.waterColors = waterColors;
         this.waterSurfaceHeights = waterSurfaceHeights;
         this.badlands = Objects.requireNonNull(badlands, "badlands");
         this.axis = axis;
         this.trees = trees.clone();
         this.info = Objects.requireNonNull(info, "info");
      }

      private float[] heightsFor(TerrainPreviewWidget.RenderMode renderMode) {
         return renderMode == TerrainPreviewWidget.RenderMode.FULL_DETAIL ? this.detailHeights : this.terrainHeights;
      }

      private int[] colorsFor(TerrainPreviewWidget.RenderMode renderMode) {
         return renderMode == TerrainPreviewWidget.RenderMode.FULL_DETAIL ? this.detailColors : this.terrainColors;
      }

      private TerrainPreview.PreviewGeometry geometryFor(TerrainPreviewWidget.RenderMode renderMode) {
         TerrainPreview.PreviewGeometry cached = renderMode == TerrainPreviewWidget.RenderMode.FULL_DETAIL
            ? this.detailGeometry
            : this.terrainGeometry;
         if (cached != null) {
            return cached;
         }

         synchronized (this) {
            cached = renderMode == TerrainPreviewWidget.RenderMode.FULL_DETAIL ? this.detailGeometry : this.terrainGeometry;
            if (cached == null) {
               cached = buildPreviewGeometry(this, renderMode);
               if (renderMode == TerrainPreviewWidget.RenderMode.FULL_DETAIL) {
                  this.detailGeometry = cached;
               } else {
                  this.terrainGeometry = cached;
               }
            }
         }

         return cached;
      }

      private TerrainPreview.PreviewGeometry cloudGeometry() {
         TerrainPreview.PreviewGeometry cached = this.clouds;
         if (cached != null) {
            return cached;
         }

         synchronized (this) {
            cached = this.clouds;
            if (cached == null) {
               cached = buildPreviewCloudGeometry(this);
               this.clouds = cached;
            }
         }

         return cached;
      }
   }

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

   public record PreviewProviderShare(DemUsage provider, double share) {
      public PreviewProviderShare {
         provider = Objects.requireNonNull(provider, "provider");
      }
   }

   public record PreviewResolutionShare(double resolutionMeters, double share) {
   }

   private record PreviewRoadWidths(int main, int normal, int dirt) {
   }

   public static enum PreviewStage {
      DOWNLOADING,
      LOADING,
      PROCESSING_OSM,
      COMPLETE;
   }

   public record PreviewStatus(TerrainPreview.PreviewStage stage, float progress, String activity, String detail) {
      public PreviewStatus(TerrainPreview.PreviewStage stage, float progress, String activity, String detail) {
         Objects.requireNonNull(stage, "stage");
         this.stage = stage;
         this.progress = progress;
         this.activity = activity == null || activity.isBlank() ? null : activity;
         this.detail = detail == null || detail.isBlank() ? null : detail;
      }
   }

   private static final class PreviewThreadFactory implements ThreadFactory {
      @Override
      public Thread newThread(Runnable runnable) {
         Thread thread = new Thread(runnable, "tellus-preview");
         thread.setDaemon(true);
         return thread;
      }
   }

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

   private record PreviewBoundaryInfo(int[] boundaryDistance) {
      private PreviewBoundaryInfo {
         boundaryDistance = boundaryDistance.clone();
      }

      private int boundaryDistance(int order) {
         return this.boundaryDistance[order];
      }
   }

   @FunctionalInterface
   private interface RoadRasterProgress {
      void onProgress(float var1);
   }

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

   private static TerrainPreview.PreviewGeometry buildPreviewGeometry(
      TerrainPreview.PreviewMesh mesh, TerrainPreviewWidget.RenderMode renderMode
   ) {
      float[] heights = mesh.heightsFor(renderMode);
      int[] baseColors = mesh.colorsFor(renderMode);
      int stride = Math.max(1, mesh.granularity);
      int cellCount = (mesh.size - 1) / stride;
      if (cellCount <= 0) {
         return new TerrainPreview.PreviewGeometry(new float[0], new int[0], 0);
      }

      float cellSize = Math.abs(mesh.axis[stride] - mesh.axis[0]);
      float verticalUnit = Math.max(1.0E-5F, cellSize * PREVIEW_VERTICAL_CELL_RATIO);
      float[] cellHeights = new float[cellCount * cellCount];
      float[] surfaceHeights = new float[cellCount * cellCount];
      int[] cellColors = new int[cellCount * cellCount];
      boolean[] waterCells = new boolean[cellCount * cellCount];
      boolean[] badlandsCells = new boolean[cellCount * cellCount];

      for (int cellZ = 0; cellZ < cellCount; cellZ++) {
         int z = cellZ * stride;
         int rowIndex = z * mesh.size;
         int nextRowIndex = (z + stride) * mesh.size;

         for (int cellX = 0; cellX < cellCount; cellX++) {
            int x = cellX * stride;
            int index = rowIndex + x;
            float averageHeight = (heights[index]
                  + heights[index + stride]
                  + heights[nextRowIndex + x]
                  + heights[nextRowIndex + x + stride])
               * 0.25F;
            int cellIndex = cellX + cellZ * cellCount;
            float floorHeight = Math.round(averageHeight / verticalUnit) * verticalUnit;
            boolean water = mesh.waterColors[index] >= 0 && baseColors[index] == mesh.waterColors[index];
            cellHeights[cellIndex] = floorHeight;
            surfaceHeights[cellIndex] = water
               ? Math.max(floorHeight + verticalUnit * 0.08F, Math.round(mesh.waterSurfaceHeights[index] / verticalUnit) * verticalUnit)
               : floorHeight;
            cellColors[cellIndex] = previewTopMaterialColor(baseColors[index], x, z, 0x53A9);
            waterCells[cellIndex] = water;
            badlandsCells[cellIndex] = mesh.badlands.mask()[index] && isBadlandsPlateauPreviewColor(baseColors[index]) && !water;
         }
      }

      float[] occluderHeights = surfaceHeights.clone();
      if (renderMode == TerrainPreviewWidget.RenderMode.FULL_DETAIL) {
         addPreviewTreeOccluders(mesh, cellHeights, occluderHeights, cellCount, verticalUnit);
      }

      float[] bakedShadows = buildPreviewShadowMap(surfaceHeights, occluderHeights, cellCount, cellSize, verticalUnit);
      TerrainPreview.PreviewGeometryBuilder builder = new TerrainPreview.PreviewGeometryBuilder(cellCount * cellCount * 10);
      float sideThreshold = verticalUnit * 0.5F;

      for (int cellZ = 0; cellZ < cellCount; cellZ++) {
         int z = cellZ * stride;
         float z0 = mesh.axis[z];
         float z1 = mesh.axis[z + stride];

         for (int cellX = 0; cellX < cellCount; cellX++) {
            int x = cellX * stride;
            float x0 = mesh.axis[x];
            float x1 = mesh.axis[x + stride];
            int cellIndex = cellX + cellZ * cellCount;
            float top = cellHeights[cellIndex];
            int topColor = cellColors[cellIndex];
            float shadowShade = 1.0F - bakedShadows[cellIndex] * 0.48F;
            float aoSouthWest = previewCornerAo(cellHeights, cellCount, cellX, cellZ, -1, 1, top, verticalUnit);
            float aoSouthEast = previewCornerAo(cellHeights, cellCount, cellX, cellZ, 1, 1, top, verticalUnit);
            float aoNorthEast = previewCornerAo(cellHeights, cellCount, cellX, cellZ, 1, -1, top, verticalUnit);
            float aoNorthWest = previewCornerAo(cellHeights, cellCount, cellX, cellZ, -1, -1, top, verticalUnit);
            float floorShade = waterCells[cellIndex] ? shadowShade * 0.8F : shadowShade;
            addLitPreviewQuad(
               builder,
               topColor,
               0.0F,
               1.0F,
               0.0F,
               x0,
               top,
               z1,
               x1,
               top,
               z1,
               x1,
               top,
               z0,
               x0,
               top,
               z0,
               floorShade * aoSouthWest,
               floorShade * aoSouthEast,
               floorShade * aoNorthEast,
               floorShade * aoNorthWest
            );

            if (waterCells[cellIndex]) {
               float waterTop = surfaceHeights[cellIndex] + verticalUnit * 0.04F;
               float shoreline = previewWaterShoreline(waterCells, cellCount, cellX, cellZ);
               int waterColor = previewWaterSurfaceColor(topColor, cellX, cellZ, shoreline, cellCount);
               float waterShadow = 1.0F - bakedShadows[cellIndex] * 0.34F;
               float shoreAo = 1.0F - shoreline * 0.08F;
               addLitPreviewQuad(
                  builder,
                  waterColor,
                  0.0F,
                  1.0F,
                  0.0F,
                  x0,
                  waterTop,
                  z1,
                  x1,
                  waterTop,
                  z1,
                  x1,
                  waterTop,
                  z0,
                  x0,
                  waterTop,
                  z0,
                  waterShadow * shoreAo,
                  waterShadow * shoreAo,
                  waterShadow * shoreAo,
                  waterShadow * shoreAo
               );
            }

            if (cellX + 1 < cellCount) {
               int eastIndex = cellIndex + 1;
               float eastTop = cellHeights[eastIndex];
               if (top > eastTop + sideThreshold) {
                  int sideColor = previewSideMaterialColor(topColor, x, z, 0x19D3);
                  float bottomShade = shadowShade * 0.68F;
                  float upperShade = shadowShade * 0.94F;
                  addPreviewTerrainWall(
                     builder,
                     mesh,
                     badlandsCells[cellIndex],
                     x,
                     z,
                     sideColor,
                     0x19D3,
                     1.0F,
                     0.0F,
                     x1,
                     z0,
                     x1,
                     z1,
                     eastTop,
                     top,
                     bottomShade,
                     upperShade
                  );
               } else if (eastTop > top + sideThreshold) {
                  int sideColor = previewSideMaterialColor(cellColors[eastIndex], x + stride, z, 0x27B1);
                  float eastShadow = 1.0F - bakedShadows[eastIndex] * 0.48F;
                  float bottomShade = eastShadow * 0.68F;
                  float upperShade = eastShadow * 0.94F;
                  addPreviewTerrainWall(
                     builder,
                     mesh,
                     badlandsCells[eastIndex],
                     x + stride,
                     z,
                     sideColor,
                     0x27B1,
                     -1.0F,
                     0.0F,
                     x1,
                     z1,
                     x1,
                     z0,
                     top,
                     eastTop,
                     bottomShade,
                     upperShade
                  );
               }
            }

            if (cellZ + 1 < cellCount) {
               int southIndex = cellIndex + cellCount;
               float southTop = cellHeights[southIndex];
               if (top > southTop + sideThreshold) {
                  int sideColor = previewSideMaterialColor(topColor, x, z, 0x3157);
                  float bottomShade = shadowShade * 0.68F;
                  float upperShade = shadowShade * 0.94F;
                  addPreviewTerrainWall(
                     builder,
                     mesh,
                     badlandsCells[cellIndex],
                     x,
                     z,
                     sideColor,
                     0x3157,
                     0.0F,
                     1.0F,
                     x1,
                     z1,
                     x0,
                     z1,
                     southTop,
                     top,
                     bottomShade,
                     upperShade
                  );
               } else if (southTop > top + sideThreshold) {
                  int sideColor = previewSideMaterialColor(cellColors[southIndex], x, z + stride, 0x4A6D);
                  float southShadow = 1.0F - bakedShadows[southIndex] * 0.48F;
                  float bottomShade = southShadow * 0.68F;
                  float upperShade = southShadow * 0.94F;
                  addPreviewTerrainWall(
                     builder,
                     mesh,
                     badlandsCells[southIndex],
                     x,
                     z + stride,
                     sideColor,
                     0x4A6D,
                     0.0F,
                     -1.0F,
                     x0,
                     z1,
                     x1,
                     z1,
                     top,
                     southTop,
                     bottomShade,
                     upperShade
                  );
               }
            }
         }
      }

      addPreviewSolidTerrainVolume(builder, mesh.axis, cellHeights, cellCount, stride, verticalUnit);
      addPreviewWaterVolumeSides(builder, mesh.axis, cellHeights, surfaceHeights, cellColors, waterCells, cellCount, stride, verticalUnit);

      if (renderMode == TerrainPreviewWidget.RenderMode.FULL_DETAIL) {
         addPreviewTreeGeometry(builder, mesh, cellHeights, cellCount, cellSize, verticalUnit);
      }

      return builder.build();
   }

   private static TerrainPreview.PreviewGeometry buildPreviewCloudGeometry(TerrainPreview.PreviewMesh mesh) {
      int stride = Math.max(1, mesh.granularity);
      int cellCount = (mesh.size - 1) / stride;
      if (cellCount <= 0) {
         return new TerrainPreview.PreviewGeometry(new float[0], new int[0], 0);
      }

      float cellSize = Math.abs(mesh.axis[stride] - mesh.axis[0]);
      float verticalUnit = Math.max(1.0E-5F, cellSize * PREVIEW_VERTICAL_CELL_RATIO);
      TerrainPreview.PreviewGeometryBuilder builder = new TerrainPreview.PreviewGeometryBuilder(2048);
      float cloudBase = previewCloudBaseHeight(mesh, verticalUnit);
      addPreviewClouds(builder, cloudBase, verticalUnit);
      return builder.build();
   }

   private static boolean isBadlandsPlateauPreviewColor(int color) {
      return color == 0x76543A || color == 0x985E44 || color == 0x4D3323 || color == 0xBE6621;
   }

   private static void addPreviewTerrainWall(
      TerrainPreview.PreviewGeometryBuilder builder,
      TerrainPreview.PreviewMesh mesh,
      boolean badlands,
      int gridX,
      int gridZ,
      int fallbackColor,
      long salt,
      float normalX,
      float normalZ,
      float firstX,
      float firstZ,
      float secondX,
      float secondZ,
      float bottom,
      float top,
      float bottomShade,
      float topShade
   ) {
      if (!badlands) {
         addLitPreviewQuad(
            builder,
            fallbackColor,
            normalX,
            0.0F,
            normalZ,
            firstX,
            bottom,
            firstZ,
            firstX,
            top,
            firstZ,
            secondX,
            top,
            secondZ,
            secondX,
            bottom,
            secondZ,
            bottomShade,
            topShade,
            topShade,
            bottomShade
         );
         return;
      }

      double worldBottom = mesh.badlands.worldY(bottom);
      double worldTop = mesh.badlands.worldY(top);
      int segmentCount = Mth.clamp((int)Math.ceil(Math.abs(worldTop - worldBottom)), 1, PREVIEW_BADLANDS_MAX_WALL_BANDS);
      int worldX = mesh.badlands.worldX(gridX);
      int worldZ = mesh.badlands.worldZ(gridZ);
      for (int segment = 0; segment < segmentCount; segment++) {
         float lowFraction = segment / (float)segmentCount;
         float highFraction = (segment + 1) / (float)segmentCount;
         float low = bottom + (top - bottom) * lowFraction;
         float high = bottom + (top - bottom) * highFraction;
         int worldY = (int)Math.floor(mesh.badlands.worldY((low + high) * 0.5F));
         int bandColor = previewTopMaterialColor(
            badlandsBandPreviewColor(worldX, worldZ, worldY), worldX, worldZ, salt ^ worldY * 0x9E3779B9L
         );
         float lowShade = bottomShade + (topShade - bottomShade) * lowFraction;
         float highShade = bottomShade + (topShade - bottomShade) * highFraction;
         addLitPreviewQuad(
            builder,
            bandColor,
            normalX,
            0.0F,
            normalZ,
            firstX,
            low,
            firstZ,
            firstX,
            high,
            firstZ,
            secondX,
            high,
            secondZ,
            secondX,
            low,
            secondZ,
            lowShade,
            highShade,
            highShade,
            lowShade
         );
      }
   }

   private static float previewCloudBaseHeight(TerrainPreview.PreviewMesh mesh, float verticalUnit) {
      float highestPoint = Float.NEGATIVE_INFINITY;
      for (float height : mesh.detailHeights) {
         highestPoint = Math.max(highestPoint, height);
      }

      float clearance = (float)(PREVIEW_CLOUD_CLEARANCE_BLOCKS / PREVIEW_RADIUS_BLOCKS * PREVIEW_VERTICAL_CELL_RATIO);
      return (float)Math.ceil((highestPoint + clearance) / verticalUnit) * verticalUnit;
   }

   private static void addPreviewClouds(TerrainPreview.PreviewGeometryBuilder builder, float cloudBase, float verticalUnit) {
      float thickness = Math.max(0.006F, verticalUnit * 2.0F);
      boolean[] occupied = new boolean[PREVIEW_CLOUD_GRID_SIZE * PREVIEW_CLOUD_GRID_SIZE];
      for (int cloud = 0; cloud < 11; cloud++) {
         float centerX = -0.84F + (float)hashToUnitDouble(cloud, 11L, 0x17A3L) * 1.68F;
         float centerZ = -0.84F + (float)hashToUnitDouble(cloud, 23L, 0x28B5L) * 1.68F;
         float width = 0.14F + (float)hashToUnitDouble(cloud, 37L, 0x39C7L) * 0.16F;
         float depth = 0.06F + (float)hashToUnitDouble(cloud, 41L, 0x4AD9L) * 0.08F;
         markPreviewCloud(
            occupied,
            centerX - width * 0.5F,
            centerX + width * 0.5F,
            centerZ - depth * 0.5F,
            centerZ + depth * 0.5F
         );

         for (int lobe = 0; lobe < 2; lobe++) {
            long key = cloud * 3L + lobe;
            float offsetX = ((float)hashToUnitDouble(key, 67L, 0x6CFDL) - 0.5F) * width * 1.2F;
            float offsetZ = ((float)hashToUnitDouble(key, 71L, 0x7D0FL) - 0.5F) * depth * 1.4F;
            float lobeWidth = width * (0.38F + (float)hashToUnitDouble(key, 83L, 0x8E21L) * 0.28F);
            float lobeDepth = depth * (0.55F + (float)hashToUnitDouble(key, 97L, 0x9F33L) * 0.35F);
            markPreviewCloud(
               occupied,
               centerX + offsetX - lobeWidth * 0.5F,
               centerX + offsetX + lobeWidth * 0.5F,
               centerZ + offsetZ - lobeDepth * 0.5F,
               centerZ + offsetZ + lobeDepth * 0.5F
            );
         }
      }

      addPreviewCloudMesh(builder, occupied, cloudBase, cloudBase + thickness);
   }

   private static void markPreviewCloud(boolean[] occupied, float minX, float maxX, float minZ, float maxZ) {
      float cellSize = (PREVIEW_CLOUD_MAX - PREVIEW_CLOUD_MIN) / PREVIEW_CLOUD_GRID_SIZE;
      int minCellX = Mth.clamp((int)Math.floor((minX - PREVIEW_CLOUD_MIN) / cellSize), 0, PREVIEW_CLOUD_GRID_SIZE - 1);
      int maxCellX = Mth.clamp((int)Math.ceil((maxX - PREVIEW_CLOUD_MIN) / cellSize), 1, PREVIEW_CLOUD_GRID_SIZE);
      int minCellZ = Mth.clamp((int)Math.floor((minZ - PREVIEW_CLOUD_MIN) / cellSize), 0, PREVIEW_CLOUD_GRID_SIZE - 1);
      int maxCellZ = Mth.clamp((int)Math.ceil((maxZ - PREVIEW_CLOUD_MIN) / cellSize), 1, PREVIEW_CLOUD_GRID_SIZE);
      for (int cellZ = minCellZ; cellZ < maxCellZ; cellZ++) {
         for (int cellX = minCellX; cellX < maxCellX; cellX++) {
            occupied[cellZ * PREVIEW_CLOUD_GRID_SIZE + cellX] = true;
         }
      }
   }

   private static float previewCloudCoordinate(int cell) {
      return PREVIEW_CLOUD_MIN + (PREVIEW_CLOUD_MAX - PREVIEW_CLOUD_MIN) * cell / PREVIEW_CLOUD_GRID_SIZE;
   }

   private static void addPreviewCloudMesh(
      TerrainPreview.PreviewGeometryBuilder builder, boolean[] occupied, float minY, float maxY
   ) {
      boolean[] covered = new boolean[occupied.length];
      for (int cellZ = 0; cellZ < PREVIEW_CLOUD_GRID_SIZE; cellZ++) {
         for (int cellX = 0; cellX < PREVIEW_CLOUD_GRID_SIZE; cellX++) {
            int index = cellZ * PREVIEW_CLOUD_GRID_SIZE + cellX;
            if (!occupied[index] || covered[index]) {
               continue;
            }

            int width = 1;
            while (cellX + width < PREVIEW_CLOUD_GRID_SIZE && occupied[index + width] && !covered[index + width]) {
               width++;
            }

            int depth = 1;
            while (cellZ + depth < PREVIEW_CLOUD_GRID_SIZE) {
               boolean canGrow = true;
               int row = (cellZ + depth) * PREVIEW_CLOUD_GRID_SIZE + cellX;
               for (int offset = 0; offset < width; offset++) {
                  if (!occupied[row + offset] || covered[row + offset]) {
                     canGrow = false;
                     break;
                  }
               }
               if (!canGrow) {
                  break;
               }
               depth++;
            }

            for (int offsetZ = 0; offsetZ < depth; offsetZ++) {
               int row = (cellZ + offsetZ) * PREVIEW_CLOUD_GRID_SIZE + cellX;
               Arrays.fill(covered, row, row + width, true);
            }

            float minX = previewCloudCoordinate(cellX);
            float maxX = previewCloudCoordinate(cellX + width);
            float minZ = previewCloudCoordinate(cellZ);
            float maxZ = previewCloudCoordinate(cellZ + depth);
            addLitPreviewQuad(builder, PREVIEW_CLOUD_TOP_COLOR, 0.0F, 1.0F, 0.0F, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, minX, maxY, minZ);
            addLitPreviewQuad(builder, PREVIEW_CLOUD_BOTTOM_COLOR, 0.0F, -1.0F, 0.0F, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ);
         }
      }

      for (int cellZ = 0; cellZ < PREVIEW_CLOUD_GRID_SIZE; cellZ++) {
         for (int cellX = 0; cellX < PREVIEW_CLOUD_GRID_SIZE; cellX++) {
            int index = cellZ * PREVIEW_CLOUD_GRID_SIZE + cellX;
            if (!occupied[index]) {
               continue;
            }

            float minX = previewCloudCoordinate(cellX);
            float maxX = previewCloudCoordinate(cellX + 1);
            float minZ = previewCloudCoordinate(cellZ);
            float maxZ = previewCloudCoordinate(cellZ + 1);
            if (cellX == PREVIEW_CLOUD_GRID_SIZE - 1 || !occupied[index + 1]) {
               addLitPreviewQuad(builder, PREVIEW_CLOUD_SIDE_COLOR, 1.0F, 0.0F, 0.0F, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, maxX, minY, maxZ);
            }
            if (cellX == 0 || !occupied[index - 1]) {
               addLitPreviewQuad(builder, PREVIEW_CLOUD_SIDE_COLOR, -1.0F, 0.0F, 0.0F, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ, minX, minY, minZ);
            }
            if (cellZ == PREVIEW_CLOUD_GRID_SIZE - 1 || !occupied[index + PREVIEW_CLOUD_GRID_SIZE]) {
               addLitPreviewQuad(builder, PREVIEW_CLOUD_SIDE_COLOR, 0.0F, 0.0F, 1.0F, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ, minX, minY, maxZ);
            }
            if (cellZ == 0 || !occupied[index - PREVIEW_CLOUD_GRID_SIZE]) {
               addLitPreviewQuad(builder, PREVIEW_CLOUD_SIDE_COLOR, 0.0F, 0.0F, -1.0F, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, maxX, minY, minZ);
            }
         }
      }
   }

   private static void addPreviewSolidTerrainVolume(
      TerrainPreview.PreviewGeometryBuilder builder,
      float[] axis,
      float[] cellHeights,
      int cellCount,
      int stride,
      float verticalUnit
   ) {
      float minimumHeight = Float.POSITIVE_INFINITY;
      for (float height : cellHeights) {
         minimumHeight = Math.min(minimumHeight, height);
      }

      float baseHeight = (float)Math.floor((minimumHeight - verticalUnit * PREVIEW_SOLID_BASE_DEPTH_CELLS) / verticalUnit) * verticalUnit;
      float minAxis = axis[0];
      float maxAxis = axis[cellCount * stride];
      addLitPreviewQuad(
         builder,
         PREVIEW_STONE_BOTTOM_COLOR,
         0.0F,
         -1.0F,
         0.0F,
         minAxis,
         baseHeight,
         minAxis,
         maxAxis,
         baseHeight,
         minAxis,
         maxAxis,
         baseHeight,
         maxAxis,
         minAxis,
         baseHeight,
         maxAxis,
         0.82F,
         0.82F,
         0.68F,
         0.68F
      );

      int lastCell = cellCount - 1;
      for (int cell = 0; cell < cellCount; cell++) {
         int coordinate = cell * stride;
         float edge0 = axis[coordinate];
         float edge1 = axis[coordinate + stride];
         int northColor = previewTopMaterialColor(PREVIEW_STONE_FILL_COLOR, cell, 0, 0x2A17);
         int southColor = previewTopMaterialColor(PREVIEW_STONE_FILL_COLOR, cell, lastCell, 0x3B29);
         int westColor = previewTopMaterialColor(PREVIEW_STONE_FILL_COLOR, 0, cell, 0x4C3D);
         int eastColor = previewTopMaterialColor(PREVIEW_STONE_FILL_COLOR, lastCell, cell, 0x5D4F);
         addPreviewVolumeWall(builder, northColor, 0.0F, -1.0F, edge0, minAxis, edge1, minAxis, baseHeight, cellHeights[cell], 0.64F, 0.96F);
         addPreviewVolumeWall(
            builder,
            southColor,
            0.0F,
            1.0F,
            edge1,
            maxAxis,
            edge0,
            maxAxis,
            baseHeight,
            cellHeights[cell + lastCell * cellCount],
            0.64F,
            0.96F
         );
         addPreviewVolumeWall(
            builder,
            westColor,
            -1.0F,
            0.0F,
            minAxis,
            edge1,
            minAxis,
            edge0,
            baseHeight,
            cellHeights[cell * cellCount],
            0.64F,
            0.96F
         );
         addPreviewVolumeWall(
            builder,
            eastColor,
            1.0F,
            0.0F,
            maxAxis,
            edge0,
            maxAxis,
            edge1,
            baseHeight,
            cellHeights[lastCell + cell * cellCount],
            0.64F,
            0.96F
         );
      }
   }

   private static void addPreviewWaterVolumeSides(
      TerrainPreview.PreviewGeometryBuilder builder,
      float[] axis,
      float[] cellHeights,
      float[] surfaceHeights,
      int[] cellColors,
      boolean[] waterCells,
      int cellCount,
      int stride,
      float verticalUnit
   ) {
      for (int cellZ = 0; cellZ < cellCount; cellZ++) {
         int z = cellZ * stride;
         float z0 = axis[z];
         float z1 = axis[z + stride];
         for (int cellX = 0; cellX < cellCount; cellX++) {
            int cellIndex = cellX + cellZ * cellCount;
            if (!waterCells[cellIndex]) {
               continue;
            }

            int x = cellX * stride;
            float x0 = axis[x];
            float x1 = axis[x + stride];
            float floor = cellHeights[cellIndex];
            float waterTop = surfaceHeights[cellIndex] + verticalUnit * 0.04F;
            float shoreline = previewWaterShoreline(waterCells, cellCount, cellX, cellZ);
            int surfaceColor = previewWaterSurfaceColor(cellColors[cellIndex], cellX, cellZ, shoreline, cellCount);
            int volumeColor = previewWaterVolumeSideColor(surfaceColor, waterTop - floor, verticalUnit);

            if (cellX == 0 || !waterCells[cellIndex - 1]) {
               float bottom = cellX == 0 ? floor : Math.max(floor, cellHeights[cellIndex - 1]);
               addPreviewVolumeWall(builder, volumeColor, -1.0F, 0.0F, x0, z1, x0, z0, bottom, waterTop, 0.52F, 0.92F);
            }
            if (cellX == cellCount - 1 || !waterCells[cellIndex + 1]) {
               float bottom = cellX == cellCount - 1 ? floor : Math.max(floor, cellHeights[cellIndex + 1]);
               addPreviewVolumeWall(builder, volumeColor, 1.0F, 0.0F, x1, z0, x1, z1, bottom, waterTop, 0.52F, 0.92F);
            }
            if (cellZ == 0 || !waterCells[cellIndex - cellCount]) {
               float bottom = cellZ == 0 ? floor : Math.max(floor, cellHeights[cellIndex - cellCount]);
               addPreviewVolumeWall(builder, volumeColor, 0.0F, -1.0F, x0, z0, x1, z0, bottom, waterTop, 0.52F, 0.92F);
            }
            if (cellZ == cellCount - 1 || !waterCells[cellIndex + cellCount]) {
               float bottom = cellZ == cellCount - 1 ? floor : Math.max(floor, cellHeights[cellIndex + cellCount]);
               addPreviewVolumeWall(builder, volumeColor, 0.0F, 1.0F, x1, z1, x0, z1, bottom, waterTop, 0.52F, 0.92F);
            }
         }
      }
   }

   private static int previewWaterVolumeSideColor(int surfaceColor, float depth, float verticalUnit) {
      float depthFactor = Mth.clamp(depth / Math.max(verticalUnit, verticalUnit * 32.0F), 0.0F, 1.0F);
      int rgb = blendColor(surfaceColor, PREVIEW_DEEP_WATER_VOLUME_COLOR, 0.48F + depthFactor * 0.32F);
      int alpha = Mth.clamp(Math.round(190.0F + depthFactor * 45.0F), 0, 255);
      return alpha << 24 | rgb & 0xFFFFFF;
   }

   private static void addPreviewVolumeWall(
      TerrainPreview.PreviewGeometryBuilder builder,
      int color,
      float normalX,
      float normalZ,
      float x0,
      float z0,
      float x1,
      float z1,
      float bottom,
      float top,
      float bottomShade,
      float topShade
   ) {
      if (top <= bottom + 1.0E-5F) {
         return;
      }

      addLitPreviewQuad(
         builder,
         color,
         normalX,
         0.0F,
         normalZ,
         x0,
         bottom,
         z0,
         x0,
         top,
         z0,
         x1,
         top,
         z1,
         x1,
         bottom,
         z1,
         bottomShade,
         topShade,
         topShade,
         bottomShade
      );
   }

   private static void addPreviewTreeOccluders(
      TerrainPreview.PreviewMesh mesh,
      float[] cellHeights,
      float[] occluderHeights,
      int cellCount,
      float verticalUnit
   ) {
      int stride = Math.max(1, mesh.granularity);
      for (TerrainPreview.PreviewTree tree : mesh.trees) {
         int centerX = Mth.clamp(tree.gridX() / stride, 0, cellCount - 1);
         int centerZ = Mth.clamp(tree.gridZ() / stride, 0, cellCount - 1);
         float base = cellHeights[centerX + centerZ * cellCount];
         float height = Math.max(verticalUnit * 3.0F, Math.round(tree.height() / verticalUnit) * verticalUnit);
         int radius = Math.max(1, Mth.ceil(tree.canopyScale()));

         for (int dz = -radius; dz <= radius; dz++) {
            int z = centerZ + dz;
            if (z < 0 || z >= cellCount) {
               continue;
            }

            for (int dx = -radius; dx <= radius; dx++) {
               int x = centerX + dx;
               if (x >= 0 && x < cellCount && dx * dx + dz * dz <= radius * radius + 1) {
                  int index = x + z * cellCount;
                  occluderHeights[index] = Math.max(occluderHeights[index], base + height * 0.88F);
               }
            }
         }
      }
   }

   private static float[] buildPreviewShadowMap(
      float[] surfaceHeights, float[] occluderHeights, int cellCount, float cellSize, float verticalUnit
   ) {
      float[] shadows = new float[surfaceHeights.length];
      float horizontalLight = (float)Math.sqrt(LIGHT_DIR.x * LIGHT_DIR.x + LIGHT_DIR.z * LIGHT_DIR.z);
      if (horizontalLight < 1.0E-5F) {
         return shadows;
      }

      float stepX = LIGHT_DIR.x / horizontalLight;
      float stepZ = LIGHT_DIR.z / horizontalLight;
      float rayRise = cellSize * LIGHT_DIR.y / horizontalLight;

      for (int z = 0; z < cellCount; z++) {
         for (int x = 0; x < cellCount; x++) {
            int index = x + z * cellCount;
            float originHeight = surfaceHeights[index];
            float shadow = 0.0F;
            int lastSampleX = x;
            int lastSampleZ = z;

            for (int step = 1; step <= PREVIEW_SHADOW_STEPS; step++) {
               int sampleX = Math.round(x + stepX * step);
               int sampleZ = Math.round(z + stepZ * step);
               if (sampleX < 0 || sampleZ < 0 || sampleX >= cellCount || sampleZ >= cellCount) {
                  break;
               }

               if (sampleX == lastSampleX && sampleZ == lastSampleZ) {
                  continue;
               }

               lastSampleX = sampleX;
               lastSampleZ = sampleZ;
               float rayHeight = originHeight + rayRise * step;
               float clearance = occluderHeights[sampleX + sampleZ * cellCount] - rayHeight;
               if (clearance > 0.0F) {
                  float strength = Mth.clamp(0.38F + clearance / (verticalUnit * 5.0F), 0.0F, 1.0F);
                  float distanceFade = 1.0F - (float)(step - 1) / (PREVIEW_SHADOW_STEPS * 1.35F);
                  shadow = Math.max(shadow, strength * distanceFade);
               }
            }

            shadows[index] = Mth.clamp(shadow, 0.0F, 1.0F);
         }
      }

      return shadows;
   }

   private static float previewCornerAo(
      float[] heights, int cellCount, int cellX, int cellZ, int directionX, int directionZ, float top, float verticalUnit
   ) {
      float sideX = previewAoContribution(heights, cellCount, cellX + directionX, cellZ, top, verticalUnit);
      float sideZ = previewAoContribution(heights, cellCount, cellX, cellZ + directionZ, top, verticalUnit);
      float diagonal = previewAoContribution(heights, cellCount, cellX + directionX, cellZ + directionZ, top, verticalUnit);
      float occlusion = sideX * 0.48F + sideZ * 0.48F + diagonal * (sideX > 0.0F && sideZ > 0.0F ? 0.18F : 0.3F);
      return Mth.clamp(1.0F - occlusion, 0.58F, 1.0F);
   }

   private static float previewAoContribution(
      float[] heights, int cellCount, int x, int z, float top, float verticalUnit
   ) {
      if (x < 0 || z < 0 || x >= cellCount || z >= cellCount) {
         return 0.0F;
      }

      float difference = heights[x + z * cellCount] - top;
      if (difference <= verticalUnit * 0.45F) {
         return 0.0F;
      }

      return Mth.clamp(0.18F + difference / verticalUnit * 0.075F, 0.0F, 0.62F);
   }

   private static float previewWaterShoreline(boolean[] waterCells, int cellCount, int cellX, int cellZ) {
      float shoreline = 0.0F;

      for (int dz = -2; dz <= 2; dz++) {
         for (int dx = -2; dx <= 2; dx++) {
            if (dx == 0 && dz == 0) {
               continue;
            }

            int x = cellX + dx;
            int z = cellZ + dz;
            if (x < 0 || z < 0 || x >= cellCount || z >= cellCount || waterCells[x + z * cellCount]) {
               continue;
            }

            int distance = Math.max(Math.abs(dx), Math.abs(dz));
            shoreline = Math.max(shoreline, distance == 1 ? (dx == 0 || dz == 0 ? 1.0F : 0.72F) : 0.28F);
         }
      }

      return shoreline;
   }

   private static int previewWaterSurfaceColor(int floorColor, int x, int z, float shoreline, int cellCount) {
      int rgb = blendColor(floorColor, 0x55B6D2, 0.48F);
      rgb = blendColor(rgb, 0xC7E9DF, shoreline * 0.28F);
      float shimmer = (float)hashToUnitDouble(x, z, 0x61F3L);
      if (((x * 3 + z * 5) & 7) == 0) {
         rgb = blendColor(rgb, 0xD9F5EF, 0.08F + shimmer * 0.08F);
      }

      float worldX = -1.0F + ((float)x + 0.5F) * 2.0F / Math.max(1, cellCount);
      float worldZ = -1.0F + ((float)z + 0.5F) * 2.0F / Math.max(1, cellCount);
      float sunDistance = (float)Math.sqrt(PREVIEW_SUN_X * PREVIEW_SUN_X + PREVIEW_SUN_Z * PREVIEW_SUN_Z);
      float lineDistance = Math.abs(worldX * PREVIEW_SUN_Z - worldZ * PREVIEW_SUN_X) / Math.max(1.0E-5F, sunDistance);
      float towardSun = (worldX * PREVIEW_SUN_X + worldZ * PREVIEW_SUN_Z) / Math.max(1.0E-5F, sunDistance);
      float reflectionWidth = 1.0F - Mth.clamp(lineDistance / 0.18F, 0.0F, 1.0F);
      float reflectionLength = Mth.clamp((towardSun + 0.12F) / 0.92F, 0.0F, 1.0F);
      float reflection = reflectionWidth * reflectionWidth * reflectionLength * (0.12F + shimmer * 0.2F);
      rgb = blendColor(rgb, PREVIEW_SUN_REFLECTION_COLOR, reflection);

      int alpha = Mth.clamp(Math.round(178.0F + shoreline * 38.0F), 0, 255);
      return alpha << 24 | rgb & 0xFFFFFF;
   }

   private static void addPreviewTreeGeometry(
      TerrainPreview.PreviewGeometryBuilder builder,
      TerrainPreview.PreviewMesh mesh,
      float[] cellHeights,
      int cellCount,
      float cellSize,
      float verticalUnit
   ) {
      for (TerrainPreview.PreviewTree tree : mesh.trees) {
         int cellX = Mth.clamp(tree.gridX() / Math.max(1, mesh.granularity), 0, cellCount - 1);
         int cellZ = Mth.clamp(tree.gridZ() / Math.max(1, mesh.granularity), 0, cellCount - 1);
         float base = cellHeights[cellX + cellZ * cellCount];
         float height = Math.max(verticalUnit * 3.0F, Math.round(tree.height() / verticalUnit) * verticalUnit);
         float centerX = mesh.axis[tree.gridX()];
         float centerZ = mesh.axis[tree.gridZ()];
         float trunkHalfWidth = cellSize * 0.18F * tree.trunkScale();
         float canopyHalfWidth = cellSize * 0.82F * tree.canopyScale();
         float crownBase = Mth.clamp(tree.crownBaseRatio(), 0.16F, 0.86F);
         int trunkColor = previewTopMaterialColor(tree.trunkColor(), tree.gridX(), tree.gridZ(), 0x67C1);
         int lowerLeaves = previewTopMaterialColor(tree.leafColor(), tree.gridX(), tree.gridZ(), 0x78E5);
         int upperLeaves = previewTopMaterialColor(
            blendColor(tree.leafColor(), 0xD3E2C5, 0.13F), tree.gridX(), tree.gridZ(), 0x8F3B
         );
         int shadedLeaves = previewTopMaterialColor(
            scalePreviewColor(tree.leafColor(), 0.86F), tree.gridX(), tree.gridZ(), 0x4D27
         );

         if (tree.bush()) {
            addPreviewTreeTrunk(
               builder,
               centerX,
               centerZ,
               base,
               height,
               0.0F,
               0.0F,
               trunkHalfWidth,
               1.0F / 3.0F,
               trunkColor
            );
            addPreviewCrownBox(
               builder, tree, centerX, centerZ, base, height, canopyHalfWidth, 0.0F, 0.0F,
               0.16F, 0.72F, shadedLeaves
            );
            addPreviewCrownBox(
               builder, tree, centerX, centerZ, base, height, canopyHalfWidth * 0.72F, 0.0F, 0.0F,
               0.58F, 1.0F, upperLeaves
            );
            continue;
         }

         if (tree.profile() == TellusProceduralTreeGenerator.Profile.MALLEE
            || tree.profile() == TellusProceduralTreeGenerator.Profile.SUBARCTIC_BIRCH) {
            addPreviewMultiStemTrunks(
               builder,
               tree,
               centerX,
               centerZ,
               base,
               height,
               trunkHalfWidth,
               canopyHalfWidth,
               trunkColor
            );
         } else {
            addPreviewTreeTrunk(
               builder,
               centerX,
               centerZ,
               base,
               height,
               tree.leanX(),
               tree.leanZ(),
               trunkHalfWidth,
               previewTrunkTopRatio(tree.profile(), crownBase),
               trunkColor
            );
         }

         switch (tree.profile()) {
            case CONIFER, TALL_CONIFER, COAST_REDWOOD -> {
               float lowerWidth = tree.profile() == TellusProceduralTreeGenerator.Profile.COAST_REDWOOD
                  ? canopyHalfWidth * 0.82F
                  : canopyHalfWidth;
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, lowerWidth, 0.0F, 0.0F,
                  crownBase, Math.max(crownBase + 0.16F, 0.66F), shadedLeaves
               );
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, lowerWidth * 0.72F, 0.0F, 0.0F,
                  Math.max(crownBase + 0.12F, 0.55F), 0.84F, lowerLeaves
               );
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, lowerWidth * 0.42F, 0.0F, 0.0F,
                  0.77F, 1.0F, upperLeaves
               );
            }
            case PINE -> {
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, canopyHalfWidth, 0.0F, 0.0F,
                  Math.max(crownBase, 0.61F), 0.82F, shadedLeaves
               );
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, canopyHalfWidth * 0.78F, 0.0F, 0.0F,
                  0.76F, 0.93F, lowerLeaves
               );
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, canopyHalfWidth * 0.42F, 0.0F, 0.0F,
                  0.89F, 1.0F, upperLeaves
               );
            }
            case TROPICAL -> {
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, canopyHalfWidth * 1.08F, 0.0F, 0.0F,
                  crownBase, 0.76F, shadedLeaves
               );
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, canopyHalfWidth, canopyHalfWidth * 0.16F, 0.0F,
                  0.67F, 0.9F, lowerLeaves
               );
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, canopyHalfWidth * 0.7F, -canopyHalfWidth * 0.12F, 0.0F,
                  0.84F, 1.0F, upperLeaves
               );
            }
            case SAVANNA -> {
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, canopyHalfWidth * 1.28F, 0.0F, 0.0F,
                  Math.max(crownBase, 0.6F), 0.83F, shadedLeaves
               );
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, canopyHalfWidth * 1.08F, 0.0F, 0.0F,
                  0.78F, 0.96F, lowerLeaves
               );
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, canopyHalfWidth * 0.58F, 0.0F, 0.0F,
                  0.9F, 1.0F, upperLeaves
               );
            }
            case EUCALYPTUS -> {
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, canopyHalfWidth * 0.54F, -canopyHalfWidth * 0.5F, 0.0F,
                  Math.max(crownBase, 0.56F), 0.79F, shadedLeaves
               );
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, canopyHalfWidth * 0.58F, canopyHalfWidth * 0.46F, canopyHalfWidth * 0.12F,
                  0.67F, 0.91F, lowerLeaves
               );
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, canopyHalfWidth * 0.52F, 0.0F, -canopyHalfWidth * 0.38F,
                  0.81F, 1.0F, upperLeaves
               );
            }
            case MALLEE -> {
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, canopyHalfWidth * 0.68F, -canopyHalfWidth * 0.5F, 0.0F,
                  Math.max(crownBase, 0.45F), 0.78F, shadedLeaves
               );
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, canopyHalfWidth * 0.72F, canopyHalfWidth * 0.48F, 0.0F,
                  0.52F, 0.86F, lowerLeaves
               );
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, canopyHalfWidth * 0.64F, 0.0F, canopyHalfWidth * 0.42F,
                  0.68F, 1.0F, upperLeaves
               );
            }
            case MEDITERRANEAN, DRY_BROADLEAF -> {
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, canopyHalfWidth * 0.78F, -canopyHalfWidth * 0.34F, 0.0F,
                  crownBase, 0.78F, shadedLeaves
               );
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, canopyHalfWidth * 0.82F, canopyHalfWidth * 0.3F, 0.0F,
                  Math.max(crownBase + 0.08F, 0.58F), 0.88F, lowerLeaves
               );
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, canopyHalfWidth * 0.6F, 0.0F, canopyHalfWidth * 0.28F,
                  0.78F, 1.0F, upperLeaves
               );
            }
            case BIRCH -> {
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, canopyHalfWidth * 0.74F, 0.0F, 0.0F,
                  crownBase, 0.78F, shadedLeaves
               );
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, canopyHalfWidth * 0.62F, 0.0F, 0.0F,
                  0.7F, 1.0F, upperLeaves
               );
            }
            case SUBARCTIC_BIRCH -> {
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, canopyHalfWidth * 0.74F, -canopyHalfWidth * 0.28F, 0.0F,
                  Math.max(crownBase, 0.38F), 0.82F, shadedLeaves
               );
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, canopyHalfWidth * 0.7F, canopyHalfWidth * 0.25F, 0.0F,
                  0.55F, 1.0F, upperLeaves
               );
            }
            case CHERRY -> {
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, canopyHalfWidth * 1.08F, 0.0F, 0.0F,
                  Math.max(crownBase, 0.44F), 0.74F, shadedLeaves
               );
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, canopyHalfWidth, canopyHalfWidth * 0.12F, 0.0F,
                  0.65F, 0.89F, lowerLeaves
               );
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, canopyHalfWidth * 0.68F, -canopyHalfWidth * 0.1F, 0.0F,
                  0.82F, 1.0F, upperLeaves
               );
            }
            case DARK_BROADLEAF, PALE_BROADLEAF, SWAMP, TEMPERATE_BROADLEAF -> {
               float widthScale = tree.profile() == TellusProceduralTreeGenerator.Profile.DARK_BROADLEAF ? 1.14F
                  : tree.profile() == TellusProceduralTreeGenerator.Profile.SWAMP ? 1.08F
                  : 1.0F;
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, canopyHalfWidth * widthScale, 0.0F, 0.0F,
                  crownBase, 0.79F, shadedLeaves
               );
               addPreviewCrownBox(
                  builder, tree, centerX, centerZ, base, height, canopyHalfWidth * 0.76F * widthScale, 0.0F, 0.0F,
                  0.7F, 1.0F, upperLeaves
               );
            }
         }
      }
   }

   private static float previewTrunkTopRatio(
      TellusProceduralTreeGenerator.Profile profile, float crownBase
   ) {
      return switch (profile) {
         case CONIFER, TALL_CONIFER, COAST_REDWOOD, PINE -> 0.97F;
         case EUCALYPTUS -> 0.91F;
         case TROPICAL -> 0.86F;
         case SAVANNA, MEDITERRANEAN, DRY_BROADLEAF -> Math.max(0.72F, crownBase + 0.16F);
         default -> Math.max(0.78F, crownBase + 0.2F);
      };
   }

   private static void addPreviewTreeTrunk(
      TerrainPreview.PreviewGeometryBuilder builder,
      float centerX,
      float centerZ,
      float base,
      float height,
      float leanX,
      float leanZ,
      float halfWidth,
      float topRatio,
      int color
   ) {
      float clampedTop = Mth.clamp(topRatio, 0.35F, 0.98F);
      float trunkX = centerX + leanX * clampedTop * 0.42F;
      float trunkZ = centerZ + leanZ * clampedTop * 0.42F;
      addPreviewBox(
         builder,
         trunkX - halfWidth,
         trunkX + halfWidth,
         base,
         base + height * clampedTop,
         trunkZ - halfWidth,
         trunkZ + halfWidth,
         color,
         scalePreviewColor(color, 0.84F),
         false
      );
   }

   private static void addPreviewMultiStemTrunks(
      TerrainPreview.PreviewGeometryBuilder builder,
      TerrainPreview.PreviewTree tree,
      float centerX,
      float centerZ,
      float base,
      float height,
      float trunkHalfWidth,
      float canopyHalfWidth,
      int color
   ) {
      boolean mallee = tree.profile() == TellusProceduralTreeGenerator.Profile.MALLEE;
      float stemHalfWidth = trunkHalfWidth * (mallee ? 0.62F : 0.7F);
      float spread = canopyHalfWidth * (mallee ? 0.24F : 0.18F);
      float topRatio = mallee ? 0.7F : 0.78F;
      addPreviewTreeTrunk(
         builder, centerX - spread, centerZ, base, height, tree.leanX() - spread * 0.35F,
         tree.leanZ(), stemHalfWidth, topRatio, color
      );
      addPreviewTreeTrunk(
         builder, centerX + spread, centerZ, base, height, tree.leanX() + spread * 0.35F,
         tree.leanZ(), stemHalfWidth, topRatio * 0.94F, color
      );
      if (mallee) {
         addPreviewTreeTrunk(
            builder, centerX, centerZ + spread * 0.75F, base, height, tree.leanX(),
            tree.leanZ() + spread * 0.3F, stemHalfWidth, topRatio * 0.9F, color
         );
      }
   }

   private static void addPreviewCrownBox(
      TerrainPreview.PreviewGeometryBuilder builder,
      TerrainPreview.PreviewTree tree,
      float centerX,
      float centerZ,
      float base,
      float height,
      float halfWidth,
      float offsetX,
      float offsetZ,
      float minRatio,
      float maxRatio,
      int color
   ) {
      float clampedMin = Mth.clamp(minRatio, 0.12F, 0.96F);
      float clampedMax = Mth.clamp(Math.max(clampedMin + 0.04F, maxRatio), clampedMin + 0.04F, 1.0F);
      float leanRatio = (clampedMin + clampedMax) * 0.5F;
      float crownX = centerX + tree.leanX() * leanRatio + offsetX;
      float crownZ = centerZ + tree.leanZ() * leanRatio + offsetZ;
      float safeHalfWidth = Math.max(halfWidth, 1.0E-5F);
      addPreviewBox(
         builder,
         crownX - safeHalfWidth,
         crownX + safeHalfWidth,
         base + height * clampedMin,
         base + height * clampedMax,
         crownZ - safeHalfWidth,
         crownZ + safeHalfWidth,
         color,
         scalePreviewColor(color, 0.84F),
         true
      );
   }

   private static void addPreviewBox(
      TerrainPreview.PreviewGeometryBuilder builder,
      float minX,
      float maxX,
      float minY,
      float maxY,
      float minZ,
      float maxZ,
      int topColor,
      int sideColor,
      boolean includeTop
   ) {
      addLitPreviewQuad(builder, sideColor, 1.0F, 0.0F, 0.0F, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, maxX, minY, maxZ);
      addLitPreviewQuad(builder, sideColor, -1.0F, 0.0F, 0.0F, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ, minX, minY, minZ);
      addLitPreviewQuad(builder, sideColor, 0.0F, 0.0F, 1.0F, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ, minX, minY, maxZ);
      addLitPreviewQuad(builder, sideColor, 0.0F, 0.0F, -1.0F, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, maxX, minY, minZ);
      if (includeTop) {
         addLitPreviewQuad(builder, topColor, 0.0F, 1.0F, 0.0F, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, minX, maxY, minZ);
      }
   }

   private static void addLitPreviewQuad(
      TerrainPreview.PreviewGeometryBuilder builder,
      int color,
      float normalX,
      float normalY,
      float normalZ,
      float x0,
      float y0,
      float z0,
      float x1,
      float y1,
      float z1,
      float x2,
      float y2,
      float z2,
      float x3,
      float y3,
      float z3
   ) {
      addLitPreviewQuad(
         builder,
         color,
         normalX,
         normalY,
         normalZ,
         x0,
         y0,
         z0,
         x1,
         y1,
         z1,
         x2,
         y2,
         z2,
         x3,
         y3,
         z3,
         1.0F,
         1.0F,
         1.0F,
         1.0F
      );
   }

   private static void addLitPreviewQuad(
      TerrainPreview.PreviewGeometryBuilder builder,
      int color,
      float normalX,
      float normalY,
      float normalZ,
      float x0,
      float y0,
      float z0,
      float x1,
      float y1,
      float z1,
      float x2,
      float y2,
      float z2,
      float x3,
      float y3,
      float z3,
      float shade0,
      float shade1,
      float shade2,
      float shade3
   ) {
      builder.addVertex(x0, y0, z0, applyPreviewLighting(scalePreviewColor(color, shade0), normalX, normalY, normalZ, x0, z0));
      builder.addVertex(x1, y1, z1, applyPreviewLighting(scalePreviewColor(color, shade1), normalX, normalY, normalZ, x1, z1));
      builder.addVertex(x2, y2, z2, applyPreviewLighting(scalePreviewColor(color, shade2), normalX, normalY, normalZ, x2, z2));
      builder.addVertex(x3, y3, z3, applyPreviewLighting(scalePreviewColor(color, shade3), normalX, normalY, normalZ, x3, z3));
   }

   private static int previewTopMaterialColor(int rgb, int x, int z, long salt) {
      float brightness = 0.9F + (float)hashToUnitDouble(x, z, salt) * 0.2F;
      int varied = scalePreviewColor(rgb, brightness);
      int r = Math.min(255, ((varied >> 16 & 0xFF) + 2) / 5 * 5);
      int g = Math.min(255, ((varied >> 8 & 0xFF) + 2) / 5 * 5);
      int b = Math.min(255, ((varied & 0xFF) + 2) / 5 * 5);
      return r << 16 | g << 8 | b;
   }

   private static int previewSideMaterialColor(int topColor, int x, int z, long salt) {
      int r = topColor >> 16 & 0xFF;
      int g = topColor >> 8 & 0xFF;
      int b = topColor & 0xFF;
      int sideColor;
      if (g > r * 1.13F && g > b * 1.1F) {
         sideColor = blendColor(topColor, 0x745538, 0.58F);
      } else if (r > 218 && g > 218 && b > 218) {
         sideColor = blendColor(topColor, 0xAAB0B2, 0.24F);
      } else {
         sideColor = scalePreviewColor(topColor, b > r * 1.2F ? 0.9F : 0.84F);
      }

      return previewTopMaterialColor(sideColor, x, z, salt);
   }

   private static int scalePreviewColor(int rgb, float scale) {
      int alpha = rgb & 0xFF000000;
      int r = Mth.clamp(Math.round((rgb >> 16 & 0xFF) * scale), 0, 255);
      int g = Mth.clamp(Math.round((rgb >> 8 & 0xFF) * scale), 0, 255);
      int b = Mth.clamp(Math.round((rgb & 0xFF) * scale), 0, 255);
      return alpha | r << 16 | g << 8 | b;
   }

   private static int applyPreviewLighting(
      int rgb, float normalX, float normalY, float normalZ, float worldX, float worldZ
   ) {
      int alpha = rgb >>> 24;
      if (alpha == 0) {
         alpha = 0xFF;
      }

      float dot = normalX * LIGHT_DIR.x + normalY * LIGHT_DIR.y + normalZ * LIGHT_DIR.z;
      float sun = (float)Math.pow(Math.max(0.0F, dot), 0.72);
      float sky = 0.5F + 0.5F * Mth.clamp(normalY, -1.0F, 1.0F);
      float redLight = Mth.clamp(0.38F + sky * 0.16F + sun * 0.66F, 0.32F, 1.16F);
      float greenLight = Mth.clamp(0.43F + sky * 0.18F + sun * 0.6F, 0.36F, 1.16F);
      float blueLight = Mth.clamp(0.5F + sky * 0.2F + sun * 0.5F, 0.42F, 1.16F);
      int r = Mth.clamp(Math.round((rgb >> 16 & 0xFF) * redLight), 0, 255);
      int g = Mth.clamp(Math.round((rgb >> 8 & 0xFF) * greenLight), 0, 255);
      int b = Mth.clamp(Math.round((rgb & 0xFF) * blueLight), 0, 255);
      int lit = r << 16 | g << 8 | b;
      float distance = (float)Math.sqrt(worldX * worldX + worldZ * worldZ);
      float fog = Mth.clamp((distance - 0.62F) / 0.75F, 0.0F, 1.0F);
      fog = fog * fog * (3.0F - 2.0F * fog) * 0.78F;
      return alpha << 24 | blendColor(lit, PREVIEW_FOG_COLOR, fog) & 0xFFFFFF;
   }


   private record PreviewGeometry(float[] positions, int[] colors, int vertexCount) {
   }

   private static final class PreviewGeometryBuilder {
      private float[] positions;
      private int[] colors;
      private int vertexCount;

      private PreviewGeometryBuilder(int expectedVertices) {
         int capacity = Math.max(16, expectedVertices);
         this.positions = new float[capacity * 3];
         this.colors = new int[capacity];
      }

      private void addVertex(float x, float y, float z, int color) {
         this.ensureCapacity(this.vertexCount + 1);
         int positionIndex = this.vertexCount * 3;
         this.positions[positionIndex] = x;
         this.positions[positionIndex + 1] = y;
         this.positions[positionIndex + 2] = z;
         this.colors[this.vertexCount] = color;
         this.vertexCount++;
      }

      private void ensureCapacity(int requiredVertices) {
         if (requiredVertices > this.colors.length) {
            int capacity = Math.max(requiredVertices, this.colors.length + this.colors.length / 2);
            this.positions = Arrays.copyOf(this.positions, capacity * 3);
            this.colors = Arrays.copyOf(this.colors, capacity);
         }
      }

      private TerrainPreview.PreviewGeometry build() {
         return new TerrainPreview.PreviewGeometry(
            Arrays.copyOf(this.positions, this.vertexCount * 3), Arrays.copyOf(this.colors, this.vertexCount), this.vertexCount
         );
      }
   }
   private static final class TerrainPreviewRenderState implements GuiElementRenderState {
      private final TerrainPreview.PreviewMesh mesh;
      private final TerrainPreviewWidget.RenderMode renderMode;
      private final boolean cloudsVisible;
      private final Matrix4f modelView;
      private final Matrix4f projection;
      private final Matrix3x2fc pose;
      private final ScreenRectangle rawBounds;
      private final ScreenRectangle bounds;
      private final ScreenRectangle scissor;

      private TerrainPreviewRenderState(
         TerrainPreview.PreviewMesh mesh,
         TerrainPreviewWidget.RenderMode renderMode,
         boolean cloudsVisible,
         Matrix4f modelView,
         Matrix4f projection,
         Matrix3x2fc pose,
         ScreenRectangle rawBounds,
         ScreenRectangle bounds
      ) {
         this.mesh = mesh;
         this.renderMode = Objects.requireNonNull(renderMode, "renderMode");
         this.cloudsVisible = cloudsVisible;
         this.modelView = modelView;
         this.projection = projection;
         this.pose = Objects.requireNonNull(pose, "pose");
         this.rawBounds = Objects.requireNonNull(rawBounds, "rawBounds");
         this.bounds = Objects.requireNonNull(bounds, "bounds");
         this.scissor = this.bounds;
      }

      public RenderPipeline pipeline() {
         return PREVIEW_PIPELINE;
      }

      public TextureSetup textureSetup() {
         return TextureSetup.noTexture();
      }

      public ScreenRectangle scissorArea() {
         return this.scissor;
      }

      public ScreenRectangle bounds() {
         return this.bounds;
      }

      public void buildVertices(VertexConsumer consumer) {
         this.buildGeometryVertices(consumer, this.mesh.geometryFor(this.renderMode));
         if (this.cloudsVisible) {
            this.buildGeometryVertices(consumer, this.mesh.cloudGeometry());
         }
      }

      private void buildGeometryVertices(VertexConsumer consumer, TerrainPreview.PreviewGeometry geometry) {
         Vector3f view0 = new Vector3f();
         Vector3f view1 = new Vector3f();
         Vector3f view2 = new Vector3f();
         Vector3f view3 = new Vector3f();
         Vector3f projected0 = new Vector3f();
         Vector3f projected1 = new Vector3f();
         Vector3f projected2 = new Vector3f();
         Vector3f projected3 = new Vector3f();

         for (int vertex = 0; vertex + 3 < geometry.vertexCount(); vertex += 4) {
            int position0 = vertex * 3;
            int position1 = position0 + 3;
            int position2 = position1 + 3;
            int position3 = position2 + 3;
            if (this.project(geometry.positions()[position0], geometry.positions()[position0 + 1], geometry.positions()[position0 + 2], view0, projected0)
               && this.project(geometry.positions()[position1], geometry.positions()[position1 + 1], geometry.positions()[position1 + 2], view1, projected1)
               && this.project(geometry.positions()[position2], geometry.positions()[position2 + 1], geometry.positions()[position2 + 2], view2, projected2)
               && this.project(geometry.positions()[position3], geometry.positions()[position3 + 1], geometry.positions()[position3 + 2], view3, projected3)) {
               this.emitProjectedVertex(consumer, projected0, geometry.colors()[vertex]);
               this.emitProjectedVertex(consumer, projected1, geometry.colors()[vertex + 1]);
               this.emitProjectedVertex(consumer, projected2, geometry.colors()[vertex + 2]);
               this.emitProjectedVertex(consumer, projected3, geometry.colors()[vertex + 3]);
            }
         }
      }

      private boolean project(float worldX, float worldY, float worldZ, Vector3f view, Vector3f projected) {
         this.modelView.transformPosition(worldX, worldY, worldZ, view);
         if (view.z >= -0.05F) {
            return false;
         }

         this.projection.transformProject(view, projected);
         return Float.isFinite(projected.x) && Float.isFinite(projected.y) && Float.isFinite(projected.z);
      }

      private void emitProjectedVertex(VertexConsumer consumer, Vector3f projected, int rgb) {
         float screenX = this.rawBounds.left() + (projected.x + 1.0F) * 0.5F * this.rawBounds.width();
         float screenY = this.rawBounds.top() + (1.0F - projected.y) * 0.5F * this.rawBounds.height();
         float transformedX = this.pose.m00() * screenX + this.pose.m10() * screenY + this.pose.m20();
         float transformedY = this.pose.m01() * screenX + this.pose.m11() * screenY + this.pose.m21();
         float depth = Mth.clamp((1.0F - projected.z) * 500.0F, 0.0F, 1000.0F);
         consumer.addVertex(transformedX, transformedY, depth).setColor(rgb);
      }
   }
}
