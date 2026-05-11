package com.yucareux.tellus.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.serialization.Lifecycle;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.config.HmaAccessConfig;
import com.yucareux.tellus.client.preview.TerrainPreview;
import com.yucareux.tellus.client.preview.TerrainPreviewWidget;
import com.yucareux.tellus.client.widget.CustomizationList;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleFunction;
import java.util.stream.Stream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Button.OnPress;
import net.minecraft.client.gui.components.CycleButton.Builder;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.Holder;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.HolderLookup.RegistryLookup;
import net.minecraft.core.RegistryAccess.Frozen;
import net.minecraft.core.RegistryAccess.ImmutableRegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.util.Mth;
import net.minecraft.Util;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;

@Environment(EnvType.CLIENT)
public class EarthCustomizeScreen extends Screen {
   
   private static final Component TITLE = Objects.requireNonNull(Component.translatable("options.tellus.customize_world_title.name"), "customizeTitle");
   
   private static final Component YES = Objects.requireNonNull(Component.translatable("gui.yes").withStyle(ChatFormatting.GREEN), "yesLabel");
   
   private static final Component NO = Objects.requireNonNull(Component.translatable("gui.no").withStyle(ChatFormatting.RED), "noLabel");
   
   private static final Component WORK_IN_PROGRESS = Objects.requireNonNull(
      Component.translatable("tellus.customize.work_in_progress").withStyle(ChatFormatting.GRAY), "workInProgressLabel"
   );
   
   private static final ResourceLocation DYNAMIC_DIMENSION_TYPE_ID = Objects.requireNonNull(
      ResourceLocation.fromNamespaceAndPath("tellus", "earth_dynamic"), "dynamicDimensionTypeId"
   );
   
   private static final ResourceKey<DimensionType> DYNAMIC_DIMENSION_TYPE_KEY = Objects.requireNonNull(
      ResourceKey.create(Registries.DIMENSION_TYPE, DYNAMIC_DIMENSION_TYPE_ID), "dynamicDimensionTypeKey"
   );
   private static final double OSM_ROADS_AND_BUILDINGS_MAX_WORLD_SCALE = 15.0;
   private final CreateWorldScreen parent;
   private final List<EarthCustomizeScreen.CategoryDefinition> categories;
   private CustomizationList list;
   
   private final TerrainPreview preview = new TerrainPreview();
   private TerrainPreviewWidget previewWidget;
   
   private TerrainPreviewWidget.ViewState pendingPreviewViewState;
   private String currentCategoryId;
   private long previewDirtyAt = -1L;
   private double spawnLatitude = 27.9881;
   private double spawnLongitude = 86.925;
   private boolean useLocalOrigin = EarthGeneratorSettings.DEFAULT.useLocalOrigin();
   private double originLatitude = EarthGeneratorSettings.DEFAULT.originLatitude();
   private double originLongitude = EarthGeneratorSettings.DEFAULT.originLongitude();
   private boolean localOriginFollowsSpawn = true;

   public EarthCustomizeScreen(CreateWorldScreen parent, WorldCreationContext worldCreationContext) {
      super(TITLE);
      this.parent = parent;
      this.categories = this.createCategories();
      this.applySettingsToCategories(resolveInitialSettings(worldCreationContext));
   }

   protected void init() {
      int listTop = 40;
      int listHeight = Math.max(0, this.height - 36 - listTop);
      int listWidth = Math.max(140, this.width / 2 - 20);
      int previewWidth = Math.max(140, this.width - listWidth - 40);
      int previewHeight = Math.max(80, this.height - 80);
      EarthGeneratorSettings settings = this.buildSettings();
      this.list = new CustomizationList(this.minecraft, listWidth, listHeight, listTop, 20);
      this.list.setX(10);
      this.addRenderableWidget(this.list);
      if (this.previewWidget != null) {
         this.previewWidget.close();
      }

      int previewX = this.width - previewWidth - 10;
      this.previewWidget = new TerrainPreviewWidget(previewX, listTop, previewWidth, previewHeight, this.preview);
      this.previewWidget.setFullscreenAction(this::openPreviewFullScreen);
      this.previewWidget.setAutoAdjustAction(this::applyPreviewAutoAdjust);
      if (this.pendingPreviewViewState != null) {
         this.previewWidget.setViewState(this.pendingPreviewViewState);
         this.pendingPreviewViewState = null;
      }

      this.addRenderableWidget(this.previewWidget);
      this.showCategories();
      if (!settings.equals(this.preview.getLastSettings()) || !this.preview.isLoading() && this.preview.getInfo() == null) {
         this.previewWidget.requestRebuild(settings);
      }

      int buttonY = this.height - 28;
      Component spawnpointLabel = Objects.requireNonNull(Component.translatable("gui.earth.spawnpoint"), "spawnpointLabel");
      this.addRenderableWidget(Button.builder(spawnpointLabel, button -> {
         if (this.minecraft != null) {
            this.minecraft.setScreen(new EarthSpawnpointScreen(this));
         }
      }).bounds(this.width / 2 - 155, buttonY, 150, 20).build());
      Component doneLabel = Objects.requireNonNull(Component.translatable("gui.done"), "doneLabel");
      this.addRenderableWidget(Button.builder(doneLabel, button -> this.onClose()).bounds(this.width / 2 + 5, buttonY, 150, 20).build());
   }

   private void onSettingsChanged() {
      this.previewDirtyAt = System.currentTimeMillis();
   }

   public void applySpawnpoint(double latitude, double longitude) {
      this.spawnLatitude = latitude;
      this.spawnLongitude = longitude;
      if (this.findToggleValue("use_local_origin", this.useLocalOrigin)) {
         this.originLatitude = latitude;
         this.originLongitude = longitude;
      }

      this.previewDirtyAt = System.currentTimeMillis();
   }

   public double getSpawnLatitude() {
      return this.spawnLatitude;
   }

   public double getSpawnLongitude() {
      return this.spawnLongitude;
   }

   private void openPreviewFullScreen() {
      if (this.minecraft != null && this.previewWidget != null) {
         TerrainPreviewWidget.ViewState viewState = Objects.requireNonNull(this.previewWidget.getViewState(), "viewState");
         this.minecraft.setScreen(new TerrainPreviewScreen(this, this.preview, viewState));
      }
   }

   public void applyPreviewViewState( TerrainPreviewWidget.ViewState state) {
      this.pendingPreviewViewState = state;
      if (this.previewWidget != null && this.minecraft != null && this.minecraft.screen == this) {
         this.previewWidget.setViewState(state);
         this.pendingPreviewViewState = null;
      }
   }

   public EarthGeneratorSettings applyPreviewAutoAdjust(TerrainPreview.PreviewInfo info) {
      EarthGeneratorSettings current = this.buildSettings();
      double targetWorldScale = findBestWorldScale(current, info);
      int targetHeightOffset = findBestHeightOffset(current, info, targetWorldScale);
      int delta = targetHeightOffset - current.heightOffset();
      this.setSliderValue("world_scale", targetWorldScale);
      this.setSliderValue("height_offset", targetHeightOffset);
      if (!current.isSeaLevelAutomatic()) {
         this.setSliderValue("sea_level", current.seaLevel() + delta);
      }

      if (current.minAltitude() != Integer.MIN_VALUE) {
         this.setSliderValue("min_altitude", current.minAltitude() + delta);
      }

      if (current.maxAltitude() != Integer.MIN_VALUE) {
         this.setSliderValue("max_altitude", current.maxAltitude() + delta);
      }

      if (this.minecraft != null && this.minecraft.screen == this) {
         this.refreshCurrentCategory();
      }

      return this.buildSettings();
   }

   public void onClose() {
      if (this.minecraft != null) {
         EarthGeneratorSettings settings = Objects.requireNonNull(this.buildSettings(), "generatorSettings");
         WorldCreationContext current = Objects.requireNonNull(this.parent.getUiState().getSettings(), "worldCreationContext");
         EarthGeneratorSettings.HeightLimits limits = Objects.requireNonNull(EarthGeneratorSettings.resolveHeightLimits(settings), "heightLimits");
         WorldCreationContext updated = Objects.requireNonNull(updateWorldCreationContext(current, settings, limits), "updatedWorldContext");
         this.parent.getUiState().setSettings(updated);
         this.preview.close();
         this.minecraft.setScreen(this.parent);
      }
   }

   private static WorldCreationContext updateWorldCreationContext(
      WorldCreationContext current, EarthGeneratorSettings settings, EarthGeneratorSettings.HeightLimits limits
   ) {
      WorldDimensions selectedDimensions = current.selectedDimensions();
      LevelStem overworldStem = (LevelStem)selectedDimensions.get(LevelStem.OVERWORLD)
         .orElseThrow(() -> new IllegalStateException("Overworld settings missing"));
      Holder<DimensionType> baseType = Objects.requireNonNull(overworldStem.type(), "overworldDimensionType");
      DimensionType updatedType = Objects.requireNonNull(
         EarthGeneratorSettings.applyHeightLimits((DimensionType)baseType.value(), limits), "updatedDimensionType"
      );
      ResourceKey<DimensionType> overworldKey = Objects.requireNonNull(
         overworldStem.type().unwrapKey().orElse(DYNAMIC_DIMENSION_TYPE_KEY), "overworldDimensionTypeKey"
      );
      EarthCustomizeScreen.RegistryUpdate registryUpdate = updateDimensionTypeRegistry(current.worldgenRegistries(), updatedType, overworldKey);
      LayeredRegistryAccess<RegistryLayer> registriesWithTypes = registryUpdate.registries();
      RegistryLookup<DimensionType> dimensionTypes = registriesWithTypes.compositeAccess().lookupOrThrow(Registries.DIMENSION_TYPE);
      Holder<DimensionType> overworldHolder = Objects.requireNonNull(registryUpdate.holder(), "overworldDimensionTypeHolder");
      if (Tellus.LOGGER.isInfoEnabled()) {
         DimensionType registryType = (DimensionType)dimensionTypes.getOrThrow(overworldKey).value();
         Tellus.LOGGER
            .info(
               "Tellus world settings: scale={}, minAltitude={}, maxAltitude={}, heightOffset={}, limits=[minY={}, height={}, logicalHeight={}], overworldKey={}, updatedType=[{}], registryType=[{}]",
               new Object[]{
                  settings.worldScale(),
                  settings.minAltitude(),
                  settings.maxAltitude(),
                  settings.heightOffset(),
                  limits.minY(),
                  limits.height(),
                  limits.logicalHeight(),
                  overworldKey.location(),
                  describeDimensionType(updatedType),
                  describeDimensionType(registryType)
               }
            );
      }

      ChunkGenerator generator = Objects.requireNonNull(EarthChunkGenerator.create(registriesWithTypes.compositeAccess(), settings), "overworldGenerator");
      WorldDimensions updatedDimensions = Objects.requireNonNull(
         updateDimensions(selectedDimensions, overworldHolder, generator, dimensionTypes), "updatedDimensions"
      );
      Registry<LevelStem> updatedDatapackDimensions = Objects.requireNonNull(
         updateDatapackDimensions(current.datapackDimensions(), overworldHolder, generator, dimensionTypes), "updatedDatapackDimensions"
      );
      LayeredRegistryAccess<RegistryLayer> updatedRegistries = Objects.requireNonNull(
         updateWorldgenLevelStems(registriesWithTypes, updatedDatapackDimensions), "updatedRegistries"
      );
      return new WorldCreationContext(
         current.options(),
         updatedDatapackDimensions,
         updatedDimensions,
         updatedRegistries,
         current.dataPackResources(),
         current.dataConfiguration()
      );
   }

   
   private static Registry<LevelStem> updateDatapackDimensions(
       Registry<LevelStem> source,
       Holder<DimensionType> overworldHolder,
       ChunkGenerator overworldGenerator,
      RegistryLookup<DimensionType> dimensionTypes
   ) {
      Holder<DimensionType> safeOverworldHolder = Objects.requireNonNull(overworldHolder, "overworldHolder");
      ChunkGenerator safeOverworldGenerator = Objects.requireNonNull(overworldGenerator, "overworldGenerator");
      RegistryLookup<DimensionType> dimensionTypesChecked = Objects.requireNonNull(dimensionTypes, "dimensionTypes");
      Lifecycle lifecycle = Objects.requireNonNull(
         source instanceof MappedRegistry<LevelStem> mapped ? mapped.registryLifecycle() : Lifecycle.experimental(), "datapackDimensionsLifecycle"
      );
      MappedRegistry<LevelStem> copy = new MappedRegistry<>(Registries.LEVEL_STEM, lifecycle);
      List<Entry<ResourceKey<LevelStem>, LevelStem>> entries = new ArrayList<>(source.entrySet());
      entries.sort(Comparator.comparingInt(entryx -> source.getId((LevelStem)entryx.getValue())));

      for (Entry<ResourceKey<LevelStem>, LevelStem> entry : entries) {
         ResourceKey<LevelStem> key = Objects.requireNonNull(entry.getKey(), "dimensionStemKey");
         LevelStem stem = Objects.requireNonNull(entry.getValue(), "dimensionStem");
         LevelStem updatedStem;
         if (key.equals(LevelStem.OVERWORLD)) {
            updatedStem = new LevelStem(safeOverworldHolder, safeOverworldGenerator);
         } else {
            ResourceKey<DimensionType> typeKey = (ResourceKey<DimensionType>)stem.type().unwrapKey().orElse(null);
            Holder<DimensionType> typeHolder = typeKey != null
               ? Objects.requireNonNull(dimensionTypesChecked.getOrThrow(typeKey), "dimensionType")
               : Objects.requireNonNull(stem.type(), "stemDimensionType");
            updatedStem = new LevelStem(typeHolder, stem.generator());
         }

         RegistrationInfo info = Objects.requireNonNull(source.registrationInfo(key).orElse(RegistrationInfo.BUILT_IN), "dimensionStemRegistrationInfo");
         copy.register(key, updatedStem, info);
      }

      return copy.freeze();
   }

   private static LayeredRegistryAccess<RegistryLayer> updateWorldgenLevelStems(
      LayeredRegistryAccess<RegistryLayer> registries, Registry<LevelStem> updatedLevelStems
   ) {
      LayeredRegistryAccess<RegistryLayer> updated = registries;
      boolean updatedAny = false;

      for (RegistryLayer layer : RegistryLayer.values()) {
         Frozen layerAccess = updated.getLayer(layer);
         if (!layerAccess.lookup(Registries.LEVEL_STEM).isEmpty()) {
            Frozen updatedLayer = replaceRegistry(layerAccess, Registries.LEVEL_STEM, updatedLevelStems);
            updated = replaceLayer(updated, layer, updatedLayer);
            updatedAny = true;
         }
      }

      LayeredRegistryAccess<RegistryLayer> result = updatedAny ? updated : registries;
      return Objects.requireNonNull(result, "updatedRegistries");
   }

   
   private static WorldDimensions updateDimensions(
      WorldDimensions dimensions,
      Holder<DimensionType> overworldHolder,
      ChunkGenerator overworldGenerator,
      RegistryLookup<DimensionType> dimensionTypes
   ) {
      Holder<DimensionType> safeOverworldHolder = Objects.requireNonNull(overworldHolder, "overworldHolder");
      ChunkGenerator safeOverworldGenerator = Objects.requireNonNull(overworldGenerator, "overworldGenerator");
      RegistryLookup<DimensionType> dimensionTypesChecked = Objects.requireNonNull(dimensionTypes, "dimensionTypes");
      Map<ResourceKey<LevelStem>, LevelStem> updatedStems = new LinkedHashMap<>();
      dimensions.dimensions()
         .forEach(
            (key, stem) -> {
               Holder<DimensionType> typeHolder;
               if (key.equals(LevelStem.OVERWORLD)) {
                  typeHolder = safeOverworldHolder;
               } else {
                  ResourceKey<DimensionType> typeKey = (ResourceKey<DimensionType>)stem.type().unwrapKey().orElse(null);
                  typeHolder = typeKey != null
                     ? Objects.requireNonNull(dimensionTypesChecked.getOrThrow(typeKey), "dimensionType")
                     : Objects.requireNonNull(stem.type(), "stemDimensionType");
               }

               updatedStems.put((ResourceKey<LevelStem>)key, new LevelStem(typeHolder, stem.generator()));
            }
         );
      return new WorldDimensions(WorldDimensions.withOverworld(updatedStems, safeOverworldHolder, safeOverworldGenerator));
   }

   public void tick() {
      super.tick();
      if (this.previewWidget != null) {
         this.previewWidget.tick();
      }

      if (this.previewDirtyAt > 0L && System.currentTimeMillis() - this.previewDirtyAt >= 350L) {
         this.previewDirtyAt = -1L;
         if (this.previewWidget != null) {
            this.previewWidget.requestRebuild(this.buildSettings());
         }
      }
   }

   public void removed() {
      if (this.previewWidget != null) {
         this.previewWidget.close();
      }

      super.removed();
   }

   
   private EarthGeneratorSettings buildSettings() {
      double worldScale = this.findSliderValue("world_scale", EarthGeneratorSettings.DEFAULT.worldScale());
      EarthGeneratorSettings.DemSelection demSelection = this.buildDemSelection();
      double terrestrialScale = this.findSliderValue("terrestrial_height_scale", EarthGeneratorSettings.DEFAULT.terrestrialHeightScale());
      double oceanicScale = this.findSliderValue("oceanic_height_scale", EarthGeneratorSettings.DEFAULT.oceanicHeightScale());
      int heightOffset = (int)Math.round(this.findSliderValue("height_offset", EarthGeneratorSettings.DEFAULT.heightOffset()));
      int seaLevel = this.resolveSeaLevelSetting("sea_level", -64.0);
      int maxAltitude = this.resolveAltitudeSetting("max_altitude", -1.0);
      int minAltitude = this.resolveAltitudeSetting("min_altitude", -2048.0);
      int riverLakeShorelineBlend = (int)Math.round(
         this.findSliderValue("river_lake_shoreline_blend", EarthGeneratorSettings.DEFAULT.riverLakeShorelineBlend())
      );
      int oceanShorelineBlend = (int)Math.round(this.findSliderValue("ocean_shoreline_blend", EarthGeneratorSettings.DEFAULT.oceanShorelineBlend()));
      boolean shorelineBlendCliffLimit = this.findToggleValue("shoreline_blend_cliff_limit", EarthGeneratorSettings.DEFAULT.shorelineBlendCliffLimit());
      boolean caveGeneration = this.findToggleValue("cave_generation", EarthGeneratorSettings.DEFAULT.caveGeneration());
      boolean oreDistribution = this.findToggleValue("ore_distribution", EarthGeneratorSettings.DEFAULT.oreDistribution());
      boolean lavaPools = this.findToggleValue("lava_pools", EarthGeneratorSettings.DEFAULT.lavaPools());
      boolean enableRoads = this.findToggleValue("enable_roads", EarthGeneratorSettings.DEFAULT.enableRoads());
      boolean enableBuildings = this.findToggleValue("enable_buildings", EarthGeneratorSettings.DEFAULT.enableBuildings());
      boolean enableWater = this.findToggleValue("enable_water", EarthGeneratorSettings.DEFAULT.enableWater());
      boolean useLocalOrigin = this.findToggleValue("use_local_origin", EarthGeneratorSettings.DEFAULT.useLocalOrigin());
      boolean deepDark = this.findToggleValue("deep_dark", EarthGeneratorSettings.DEFAULT.deepDark());
      boolean geodes = this.findToggleValue("geodes", EarthGeneratorSettings.DEFAULT.geodes());
      boolean addStrongholds = this.findToggleValue("add_strongholds", EarthGeneratorSettings.DEFAULT.addStrongholds());
      boolean addVillages = this.findToggleValue("add_villages", EarthGeneratorSettings.DEFAULT.addVillages());
      boolean addMineshafts = this.findToggleValue("add_mineshafts", EarthGeneratorSettings.DEFAULT.addMineshafts());
      boolean addOceanMonuments = this.findToggleValue("add_ocean_monuments", EarthGeneratorSettings.DEFAULT.addOceanMonuments());
      boolean addWoodlandMansions = this.findToggleValue("add_woodland_mansions", EarthGeneratorSettings.DEFAULT.addWoodlandMansions());
      boolean addDesertTemples = this.findToggleValue("add_desert_temples", EarthGeneratorSettings.DEFAULT.addDesertTemples());
      boolean addJungleTemples = this.findToggleValue("add_jungle_temples", EarthGeneratorSettings.DEFAULT.addJungleTemples());
      boolean addPillagerOutposts = this.findToggleValue("add_pillager_outposts", EarthGeneratorSettings.DEFAULT.addPillagerOutposts());
      boolean addRuinedPortals = this.findToggleValue("add_ruined_portals", EarthGeneratorSettings.DEFAULT.addRuinedPortals());
      boolean addShipwrecks = this.findToggleValue("add_shipwrecks", EarthGeneratorSettings.DEFAULT.addShipwrecks());
      boolean addOceanRuins = this.findToggleValue("add_ocean_ruins", EarthGeneratorSettings.DEFAULT.addOceanRuins());
      boolean addBuriedTreasure = this.findToggleValue("add_buried_treasure", EarthGeneratorSettings.DEFAULT.addBuriedTreasure());
      boolean addIgloos = this.findToggleValue("add_igloos", EarthGeneratorSettings.DEFAULT.addIgloos());
      boolean addWitchHuts = this.findToggleValue("add_witch_huts", EarthGeneratorSettings.DEFAULT.addWitchHuts());
      boolean addAncientCities = this.findToggleValue("add_ancient_cities", EarthGeneratorSettings.DEFAULT.addAncientCities());
      boolean addTrialChambers = this.findToggleValue("add_trial_chambers", EarthGeneratorSettings.DEFAULT.addTrialChambers());
      boolean addTrailRuins = this.findToggleValue("add_trail_ruins", EarthGeneratorSettings.DEFAULT.addTrailRuins());
      boolean distantHorizonsWaterResolver = this.findToggleValue(
         "distant_horizons_water_resolver", EarthGeneratorSettings.DEFAULT.distantHorizonsWaterResolver()
      );
      boolean distantHorizonsOsmFeatures = EarthGeneratorSettings.DEFAULT.distantHorizonsOsmFeatures();
      int distantHorizonsOsmRoadMaxDetail = EarthGeneratorSettings.DEFAULT.distantHorizonsOsmRoadMaxDetail();
      int distantHorizonsOsmBuildingMaxDetail = EarthGeneratorSettings.DEFAULT.distantHorizonsOsmBuildingMaxDetail();
      boolean distantHorizonsOsmNonBlockingFetch = EarthGeneratorSettings.DEFAULT.distantHorizonsOsmNonBlockingFetch();
      boolean realtimeTime = this.findToggleValue("realtime_time", EarthGeneratorSettings.DEFAULT.realtimeTime());
      boolean realtimeWeather = this.findToggleValue("realtime_weather", EarthGeneratorSettings.DEFAULT.realtimeWeather());
      boolean historicalSnow = false;
      boolean voxyChunkPregenEnabled = this.findToggleValue("voxy_chunk_pregen_enabled", EarthGeneratorSettings.DEFAULT.voxyChunkPregenEnabled());
      int voxyChunkPregenMaxRadius = (int)Math.round(
         this.findSliderValue("voxy_chunk_pregen_max_radius", EarthGeneratorSettings.DEFAULT.voxyChunkPregenMaxRadius())
      );
      int voxyChunkPregenChunksPerTick = (int)Math.round(
         this.findSliderValue("voxy_chunk_pregen_chunks_per_tick", EarthGeneratorSettings.DEFAULT.voxyChunkPregenChunksPerTick())
      );
      EarthGeneratorSettings.DistantHorizonsRenderMode renderMode = this.findRenderMode(
         "distant_horizons_render_mode", EarthGeneratorSettings.DEFAULT.distantHorizonsRenderMode()
      );
      if (!roadsAndBuildingsSupportedForWorldScale(worldScale)) {
         enableRoads = false;
         enableBuildings = false;
      }

      if (renderMode == EarthGeneratorSettings.DistantHorizonsRenderMode.ULTRA_FAST) {
         distantHorizonsWaterResolver = false;
      }

      return new EarthGeneratorSettings(
         worldScale,
         terrestrialScale,
         oceanicScale,
         heightOffset,
         seaLevel,
         this.spawnLatitude,
         this.spawnLongitude,
         useLocalOrigin,
         useLocalOrigin ? this.spawnLatitude : this.originLatitude,
         useLocalOrigin ? this.spawnLongitude : this.originLongitude,
         minAltitude,
         maxAltitude,
         riverLakeShorelineBlend,
         oceanShorelineBlend,
         shorelineBlendCliffLimit,
         caveGeneration,
         oreDistribution,
         lavaPools,
         addStrongholds,
         addVillages,
         addMineshafts,
         addOceanMonuments,
         addWoodlandMansions,
         addDesertTemples,
         addJungleTemples,
         addPillagerOutposts,
         addRuinedPortals,
         addShipwrecks,
         addOceanRuins,
         addBuriedTreasure,
         addIgloos,
         addWitchHuts,
         addAncientCities,
         addTrialChambers,
         addTrailRuins,
         deepDark,
         geodes,
         distantHorizonsWaterResolver,
         distantHorizonsOsmFeatures,
         distantHorizonsOsmRoadMaxDetail,
         distantHorizonsOsmBuildingMaxDetail,
         distantHorizonsOsmNonBlockingFetch,
         realtimeTime,
         realtimeWeather,
         historicalSnow,
         voxyChunkPregenEnabled,
         voxyChunkPregenMaxRadius,
         voxyChunkPregenChunksPerTick,
         renderMode,
         demSelection,
         enableRoads,
         enableBuildings,
         enableWater
      );
   }

   private static EarthGeneratorSettings resolveInitialSettings(WorldCreationContext worldCreationContext) {
      EarthGeneratorSettings defaultSettings = Objects.requireNonNull(EarthGeneratorSettings.DEFAULT, "defaultSettings");
      if (worldCreationContext == null) {
         return defaultSettings;
      } else {
         LevelStem overworld = (LevelStem)worldCreationContext.selectedDimensions().get(LevelStem.OVERWORLD).orElse(null);
         if (overworld == null) {
            return defaultSettings;
         } else {
            return overworld.generator() instanceof EarthChunkGenerator earthGenerator
               ? Objects.requireNonNull(earthGenerator.settings(), "generatorSettings")
               : defaultSettings;
         }
      }
   }

   private void applySettingsToCategories(EarthGeneratorSettings settings) {
      this.applySettingsToCategories(settings, false);
   }

   private void applySettingsToCategories(EarthGeneratorSettings settings, boolean preserveSpawnpoint) {
      EarthGeneratorSettings initialSettings = Objects.requireNonNull(settings, "initialSettings");
      if (!preserveSpawnpoint) {
         this.spawnLatitude = initialSettings.spawnLatitude();
         this.spawnLongitude = initialSettings.spawnLongitude();
         this.useLocalOrigin = initialSettings.useLocalOrigin();
         this.originLatitude = initialSettings.originLatitude();
         this.originLongitude = initialSettings.originLongitude();
         this.localOriginFollowsSpawn = sameCoordinate(this.originLatitude, this.spawnLatitude)
            && sameCoordinate(this.originLongitude, this.spawnLongitude);
      }

      this.setSliderValue("world_scale", initialSettings.worldScale());
      this.setDemSelectionValue(initialSettings.demSelection());
      this.setSliderValue("terrestrial_height_scale", initialSettings.terrestrialHeightScale());
      this.setSliderValue("oceanic_height_scale", initialSettings.oceanicHeightScale());
      this.setSliderValue("height_offset", initialSettings.heightOffset());
      this.setSliderValue("sea_level", initialSettings.seaLevel() == -2147483647 ? -64.0 : initialSettings.seaLevel());
      this.setSliderValue("max_altitude", initialSettings.maxAltitude() == Integer.MIN_VALUE ? -1.0 : initialSettings.maxAltitude());
      this.setSliderValue("min_altitude", initialSettings.minAltitude() == Integer.MIN_VALUE ? -2048.0 : initialSettings.minAltitude());
      this.setSliderValue("river_lake_shoreline_blend", initialSettings.riverLakeShorelineBlend());
      this.setSliderValue("ocean_shoreline_blend", initialSettings.oceanShorelineBlend());
      this.setToggleValue("shoreline_blend_cliff_limit", initialSettings.shorelineBlendCliffLimit());
      this.setToggleValue("cave_generation", initialSettings.caveGeneration());
      this.setToggleValue("ore_distribution", initialSettings.oreDistribution());
      this.setToggleValue("lava_pools", initialSettings.lavaPools());
      this.setToggleValue("enable_roads", initialSettings.enableRoads());
      this.setToggleValue("enable_buildings", initialSettings.enableBuildings());
      this.setToggleValue("enable_water", initialSettings.enableWater());
      this.setToggleValue("use_local_origin", initialSettings.useLocalOrigin());
      this.setToggleValue("deep_dark", initialSettings.deepDark());
      this.setToggleValue("geodes", initialSettings.geodes());
      this.setToggleValue("add_strongholds", initialSettings.addStrongholds());
      this.setToggleValue("add_villages", initialSettings.addVillages());
      this.setToggleValue("add_mineshafts", initialSettings.addMineshafts());
      this.setToggleValue("add_ocean_monuments", initialSettings.addOceanMonuments());
      this.setToggleValue("add_woodland_mansions", initialSettings.addWoodlandMansions());
      this.setToggleValue("add_desert_temples", initialSettings.addDesertTemples());
      this.setToggleValue("add_jungle_temples", initialSettings.addJungleTemples());
      this.setToggleValue("add_pillager_outposts", initialSettings.addPillagerOutposts());
      this.setToggleValue("add_ruined_portals", initialSettings.addRuinedPortals());
      this.setToggleValue("add_shipwrecks", initialSettings.addShipwrecks());
      this.setToggleValue("add_ocean_ruins", initialSettings.addOceanRuins());
      this.setToggleValue("add_buried_treasure", initialSettings.addBuriedTreasure());
      this.setToggleValue("add_igloos", initialSettings.addIgloos());
      this.setToggleValue("add_witch_huts", initialSettings.addWitchHuts());
      this.setToggleValue("add_ancient_cities", initialSettings.addAncientCities());
      this.setToggleValue("add_trial_chambers", initialSettings.addTrialChambers());
      this.setToggleValue("add_trail_ruins", initialSettings.addTrailRuins());
      this.setToggleValue("distant_horizons_water_resolver", initialSettings.distantHorizonsWaterResolver());
      this.setToggleValue("realtime_time", initialSettings.realtimeTime());
      this.setToggleValue("realtime_weather", initialSettings.realtimeWeather());
      this.setToggleValue("historical_snow", false);
      this.setToggleValue("voxy_chunk_pregen_enabled", initialSettings.voxyChunkPregenEnabled());
      this.setSliderValue("voxy_chunk_pregen_max_radius", initialSettings.voxyChunkPregenMaxRadius());
      this.setSliderValue("voxy_chunk_pregen_chunks_per_tick", initialSettings.voxyChunkPregenChunksPerTick());
      this.setRenderModeValue("distant_horizons_render_mode", initialSettings.distantHorizonsRenderMode());
   }

   private void setSliderValue(String key, double value) {
      for (EarthCustomizeScreen.CategoryDefinition category : this.categories) {
         for (EarthCustomizeScreen.SettingDefinition setting : category.getSettings()) {
            if (setting instanceof EarthCustomizeScreen.SliderDefinition slider && slider.key.equals(key)) {
               slider.value = Mth.clamp(value, slider.min, slider.max);
               return;
            }
         }
      }
   }

   private static boolean sameCoordinate(double first, double second) {
      return Math.abs(first - second) < 1.0E-7;
   }

   private void setToggleValue(String key, boolean value) {
      for (EarthCustomizeScreen.CategoryDefinition category : this.categories) {
         for (EarthCustomizeScreen.SettingDefinition setting : category.getSettings()) {
            if (setting instanceof EarthCustomizeScreen.ToggleDefinition toggle && toggle.key.equals(key)) {
               toggle.value = value;
               return;
            }
         }
      }
   }

   private void setRenderModeValue(String key, EarthGeneratorSettings.DistantHorizonsRenderMode value) {
      for (EarthCustomizeScreen.CategoryDefinition category : this.categories) {
         for (EarthCustomizeScreen.SettingDefinition setting : category.getSettings()) {
            if (setting instanceof EarthCustomizeScreen.ModeDefinition mode && mode.key.equals(key)) {
               mode.value = value;
               return;
            }
         }
      }
   }

   private void setDemSelectionValue(EarthGeneratorSettings.DemSelection demSelection) {
      EarthGeneratorSettings.DemSelection normalized = Objects.requireNonNull(demSelection, "demSelection");
      this.setToggleValue("dem_automatic", normalized.automatic());
      for (EarthGeneratorSettings.DemProvider provider : EarthGeneratorSettings.DemSelection.userSelectableProviders()) {
         this.setDemProviderToggleValue(provider, normalized.isEnabled(provider));
      }
   }

   private void setDemProviderToggleValue(EarthGeneratorSettings.DemProvider provider, boolean value) {
      for (EarthCustomizeScreen.CategoryDefinition category : this.categories) {
         for (EarthCustomizeScreen.SettingDefinition setting : category.getSettings()) {
            if (setting instanceof EarthCustomizeScreen.DemProviderToggleDefinition providerToggle && providerToggle.provider == provider) {
               providerToggle.value = value;
               return;
            }
         }
      }
   }

   private double findSliderValue(String key, double fallback) {
      for (EarthCustomizeScreen.CategoryDefinition category : this.categories) {
         for (EarthCustomizeScreen.SettingDefinition setting : category.getSettings()) {
            if (setting instanceof EarthCustomizeScreen.SliderDefinition slider && slider.key.equals(key)) {
               return slider.value;
            }
         }
      }

      return fallback;
   }

   private boolean findToggleValue(String key, boolean fallback) {
      for (EarthCustomizeScreen.CategoryDefinition category : this.categories) {
         for (EarthCustomizeScreen.SettingDefinition setting : category.getSettings()) {
            if (setting instanceof EarthCustomizeScreen.ToggleDefinition toggle && toggle.key.equals(key)) {
               return toggle.value;
            }
         }
      }

      return fallback;
   }

   private EarthGeneratorSettings.DistantHorizonsRenderMode findRenderMode(String key, EarthGeneratorSettings.DistantHorizonsRenderMode fallback) {
      for (EarthCustomizeScreen.CategoryDefinition category : this.categories) {
         for (EarthCustomizeScreen.SettingDefinition setting : category.getSettings()) {
            if (setting instanceof EarthCustomizeScreen.ModeDefinition mode && mode.key.equals(key)) {
               return mode.value;
            }
         }
      }

      return fallback;
   }

   private boolean findDemProviderToggleValue(EarthGeneratorSettings.DemProvider provider, boolean fallback) {
      for (EarthCustomizeScreen.CategoryDefinition category : this.categories) {
         for (EarthCustomizeScreen.SettingDefinition setting : category.getSettings()) {
            if (setting instanceof EarthCustomizeScreen.DemProviderToggleDefinition providerToggle && providerToggle.provider == provider) {
               return providerToggle.value;
            }
         }
      }

      return fallback;
   }

   private EarthGeneratorSettings.DemSelection buildDemSelection() {
      boolean automatic = this.findToggleValue("dem_automatic", EarthGeneratorSettings.DEFAULT.demSelection().automatic());
      int enabledProviderMask = 0;

      for (EarthGeneratorSettings.DemProvider provider : EarthGeneratorSettings.DemSelection.userSelectableProviders()) {
         boolean enabled = this.findDemProviderToggleValue(
            provider, EarthGeneratorSettings.DEFAULT.demSelection().isEnabled(provider)
         );
         if (enabled) {
            enabledProviderMask |= provider.selectionBit();
         }
      }

      return automatic ? EarthGeneratorSettings.DemSelection.automaticSelection() : EarthGeneratorSettings.DemSelection.manual(enabledProviderMask);
   }

   public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      super.render(graphics, mouseX, mouseY, delta);
      graphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 16777215);
   }

   private List<EarthCustomizeScreen.CategoryDefinition> createCategories() {
      List<EarthCustomizeScreen.CategoryDefinition> categories = new ArrayList<>();
      boolean distantHorizonsInstalled = FabricLoader.getInstance().isModLoaded("distanthorizons");
      boolean voxyInstalled = FabricLoader.getInstance().isModLoaded("voxy");
      EarthCustomizeScreen.HmaTokenDefinition hmaToken = new EarthCustomizeScreen.HmaTokenDefinition(HmaAccessManager.savedToken());
      EarthCustomizeScreen.CategoryDefinition hmaAccessCategory = new EarthCustomizeScreen.CategoryDefinition(
         "hma_access", hmaAccessEntries(hmaToken)
      ).hideFromRoot().parent("world");
      EarthCustomizeScreen.CategoryDefinition demProvidersCategory = new EarthCustomizeScreen.CategoryDefinition(
         "dem_providers",
         List.of(
            toggle("dem_automatic", EarthGeneratorSettings.DEFAULT.demSelection().automatic()),
            demProviderToggle(
               EarthGeneratorSettings.DemProvider.TERRARIUM, EarthGeneratorSettings.DEFAULT.demSelection().isEnabled(EarthGeneratorSettings.DemProvider.TERRARIUM)
            ),
            demProviderToggle(
               EarthGeneratorSettings.DemProvider.SWISSALTI3D,
               EarthGeneratorSettings.DEFAULT.demSelection().isEnabled(EarthGeneratorSettings.DemProvider.SWISSALTI3D)
            ),
            demProviderToggle(EarthGeneratorSettings.DemProvider.AHN, EarthGeneratorSettings.DEFAULT.demSelection().isEnabled(EarthGeneratorSettings.DemProvider.AHN)),
            demProviderToggle(
               EarthGeneratorSettings.DemProvider.CANELEVATION,
               EarthGeneratorSettings.DEFAULT.demSelection().isEnabled(EarthGeneratorSettings.DemProvider.CANELEVATION)
            ),
            demProviderToggle(
               EarthGeneratorSettings.DemProvider.NORWAYDTM1,
               EarthGeneratorSettings.DEFAULT.demSelection().isEnabled(EarthGeneratorSettings.DemProvider.NORWAYDTM1)
            ),
            demProviderToggle(
               EarthGeneratorSettings.DemProvider.JAPANGSI,
               EarthGeneratorSettings.DEFAULT.demSelection().isEnabled(EarthGeneratorSettings.DemProvider.JAPANGSI)
            ),
            demProviderToggle(
               EarthGeneratorSettings.DemProvider.USGS, EarthGeneratorSettings.DEFAULT.demSelection().isEnabled(EarthGeneratorSettings.DemProvider.USGS)
            ),
            demProviderToggle(
               EarthGeneratorSettings.DemProvider.COPERNICUS,
               EarthGeneratorSettings.DEFAULT.demSelection().isEnabled(EarthGeneratorSettings.DemProvider.COPERNICUS)
            ),
            demProviderToggle(
               EarthGeneratorSettings.DemProvider.ARCTICDEM,
               EarthGeneratorSettings.DEFAULT.demSelection().isEnabled(EarthGeneratorSettings.DemProvider.ARCTICDEM)
            )
         )
      ).hideFromRoot().parent("world");
      List<EarthCustomizeScreen.SettingDefinition> worldSettings = new ArrayList<>(
         List.of(
            slider("world_scale", 30.0, 1.0, 500.0, 5.0)
               .withDisplay(EarthCustomizeScreen::formatWorldScale)
               .withScale(EarthCustomizeScreen.SliderScale.power(3.0)),
            this.categoryLink(demProvidersCategory)
               .withLabel(Component.translatable("property.tellus.dem_provider.name"))
               .withTooltip(Component.translatable("property.tellus.dem_provider.tooltip").withStyle(ChatFormatting.GRAY)),
            toggle("use_local_origin", EarthGeneratorSettings.DEFAULT.useLocalOrigin())
         )
      );
      worldSettings.add(
         this.categoryLink(hmaAccessCategory)
            .active(false)
            .withTooltip(Component.translatable("tellus.hma_access.button.tooltip").withStyle(ChatFormatting.GRAY))
      );
      worldSettings.addAll(
         List.of(
            slider("terrestrial_height_scale", 1.0, 0.0, 50.0, 0.5)
               .withDisplay(EarthCustomizeScreen::formatMultiplier)
               .withScale(EarthCustomizeScreen.SliderScale.power(3.0)),
            slider("oceanic_height_scale", 1.0, 0.0, 50.0, 0.5)
               .withDisplay(EarthCustomizeScreen::formatMultiplier)
               .withScale(EarthCustomizeScreen.SliderScale.power(3.0)),
            slider("height_offset", EarthGeneratorSettings.DEFAULT.heightOffset(), -2000.0, 128.0, 1.0)
               .withDisplay(EarthCustomizeScreen::formatHeightOffset),
            slider("sea_level", -64.0, -64.0, 256.0, 1.0).withDisplay(EarthCustomizeScreen::formatSeaLevel),
            slider("max_altitude", -1.0, -1.0, 2031.0, 16.0).withDisplay(EarthCustomizeScreen::formatMaxAltitude),
            slider("min_altitude", EarthGeneratorSettings.DEFAULT.minAltitude(), -2048.0, 2031.0, 16.0).withDisplay(EarthCustomizeScreen::formatMinAltitude),
            slider("river_lake_shoreline_blend", EarthGeneratorSettings.DEFAULT.riverLakeShorelineBlend(), 0.0, 10.0, 1.0)
               .withDisplay(EarthCustomizeScreen::formatHeightOffset),
            slider("ocean_shoreline_blend", EarthGeneratorSettings.DEFAULT.oceanShorelineBlend(), 0.0, 10.0, 1.0)
               .withDisplay(EarthCustomizeScreen::formatHeightOffset),
            toggle("shoreline_blend_cliff_limit", EarthGeneratorSettings.DEFAULT.shorelineBlendCliffLimit())
         )
      );
      categories.add(
         new EarthCustomizeScreen.CategoryDefinition("world", worldSettings)
      );
      categories.add(demProvidersCategory);
      categories.add(hmaAccessCategory);
      categories.add(
         new EarthCustomizeScreen.CategoryDefinition(
            "openstreetmaps_features",
            List.of(
               toggle("enable_roads", EarthGeneratorSettings.DEFAULT.enableRoads()),
               toggle("enable_buildings", EarthGeneratorSettings.DEFAULT.enableBuildings()),
               toggle("enable_water", EarthGeneratorSettings.DEFAULT.enableWater())
            )
         )
      );
      categories.add(
         new EarthCustomizeScreen.CategoryDefinition(
            "ecological",
            List.of(
               toggle("land_vegetation", true).locked(true),
               slider("land_vegetation_density", 100.0, 0.0, 200.0, 5.0).withDisplay(EarthCustomizeScreen::formatPercent).locked(true),
               slider("trees_density", 100.0, 0.0, 200.0, 5.0).withDisplay(EarthCustomizeScreen::formatPercent).locked(true),
               toggle("aquatic_vegetation", true).locked(true),
               toggle("crops_in_villages", true).locked(true)
            )
         )
      );
      categories.add(
         new EarthCustomizeScreen.CategoryDefinition(
            "geological",
            List.of(
               toggle("cave_generation", EarthGeneratorSettings.DEFAULT.caveGeneration()),
               toggle("ore_distribution", EarthGeneratorSettings.DEFAULT.oreDistribution()),
               toggle("lava_pools", EarthGeneratorSettings.DEFAULT.lavaPools())
            )
         )
      );
      categories.add(
         new EarthCustomizeScreen.CategoryDefinition(
            "structure",
            List.of(
               toggle("add_strongholds", EarthGeneratorSettings.DEFAULT.addStrongholds()),
               toggle("add_villages", EarthGeneratorSettings.DEFAULT.addVillages()),
               toggle("add_mineshafts", EarthGeneratorSettings.DEFAULT.addMineshafts()),
               toggle("add_ocean_monuments", EarthGeneratorSettings.DEFAULT.addOceanMonuments()),
               toggle("add_woodland_mansions", EarthGeneratorSettings.DEFAULT.addWoodlandMansions()),
               toggle("add_desert_temples", EarthGeneratorSettings.DEFAULT.addDesertTemples()),
               toggle("add_jungle_temples", EarthGeneratorSettings.DEFAULT.addJungleTemples()),
               toggle("add_pillager_outposts", EarthGeneratorSettings.DEFAULT.addPillagerOutposts()),
               toggle("add_ruined_portals", EarthGeneratorSettings.DEFAULT.addRuinedPortals()),
               toggle("add_shipwrecks", EarthGeneratorSettings.DEFAULT.addShipwrecks()),
               toggle("add_ocean_ruins", EarthGeneratorSettings.DEFAULT.addOceanRuins()),
               toggle("add_buried_treasure", EarthGeneratorSettings.DEFAULT.addBuriedTreasure()),
               toggle("add_igloos", EarthGeneratorSettings.DEFAULT.addIgloos()),
               toggle("add_witch_huts", EarthGeneratorSettings.DEFAULT.addWitchHuts()),
               toggle("add_ancient_cities", EarthGeneratorSettings.DEFAULT.addAncientCities()),
               toggle("add_trial_chambers", EarthGeneratorSettings.DEFAULT.addTrialChambers()),
               toggle("add_trail_ruins", EarthGeneratorSettings.DEFAULT.addTrailRuins()),
               toggle("deep_dark", EarthGeneratorSettings.DEFAULT.deepDark()),
               toggle("geodes", EarthGeneratorSettings.DEFAULT.geodes())
            )
         )
      );
      categories.add(
         new EarthCustomizeScreen.CategoryDefinition(
            "realtime",
            List.of(
               toggle("realtime_time", EarthGeneratorSettings.DEFAULT.realtimeTime()),
               toggle("realtime_weather", EarthGeneratorSettings.DEFAULT.realtimeWeather()),
               toggle("historical_snow", false).forceDisabled(true, historicalSnowReworkTooltip())
            )
         )
      );
      EarthCustomizeScreen.CategoryDefinition distantHorizonsCategory = Objects.requireNonNull(
         new EarthCustomizeScreen.CategoryDefinition(
               "distant_horizons",
               List.of(
                  mode("distant_horizons_render_mode", EarthGeneratorSettings.DEFAULT.distantHorizonsRenderMode()),
                  toggle("distant_horizons_water_resolver", EarthGeneratorSettings.DEFAULT.distantHorizonsWaterResolver())
               )
            )
            .hideFromRoot()
            .parent("compatibility"),
         "distantHorizonsCategory"
      );
      EarthCustomizeScreen.CategoryDefinition voxyCategory = Objects.requireNonNull(
         new EarthCustomizeScreen.CategoryDefinition(
               "voxy",
               List.of(
                  toggle("voxy_chunk_pregen_enabled", EarthGeneratorSettings.DEFAULT.voxyChunkPregenEnabled()),
                  slider("voxy_chunk_pregen_max_radius", EarthGeneratorSettings.DEFAULT.voxyChunkPregenMaxRadius(), 0.0, 512.0, 1.0)
                     .withDisplay(EarthCustomizeScreen::formatChunkRadius),
                  slider("voxy_chunk_pregen_chunks_per_tick", EarthGeneratorSettings.DEFAULT.voxyChunkPregenChunksPerTick(), 1.0, 200.0, 1.0)
                     .withDisplay(EarthCustomizeScreen::formatChunksPerTick)
               )
            )
            .hideFromRoot()
            .parent("compatibility"),
         "voxyCategory"
      );
      if (!distantHorizonsInstalled) {
         disableCategoryForMissingMod(distantHorizonsCategory, "distanthorizons");
      }

      if (!voxyInstalled) {
         disableCategoryForMissingMod(voxyCategory, "voxy");
      }

      List<EarthCustomizeScreen.SettingDefinition> compatibilitySettings = new ArrayList<>();
      compatibilitySettings.add(
         this.categoryLink(distantHorizonsCategory)
            .active(distantHorizonsInstalled)
            .withTooltip(distantHorizonsInstalled ? null : requiresModTooltip("distanthorizons"))
      );
      compatibilitySettings.add(this.categoryLink(voxyCategory).active(voxyInstalled).withTooltip(voxyInstalled ? null : requiresModTooltip("voxy")));
      if (distantHorizonsInstalled && voxyInstalled) {
         compatibilitySettings.add(infoSubtle(Component.translatable("tellus.compatibility.both_mods_warning")));
         compatibilitySettings.add(infoSubtle(Component.translatable("tellus.compatibility.priority_warning")));
         compatibilitySettings.add(infoSubtle(Component.translatable("tellus.compatibility.exclusive_warning")));
      }

      compatibilitySettings.add(comingSoonButton());
      categories.add(new EarthCustomizeScreen.CategoryDefinition("compatibility", compatibilitySettings));
      categories.add(distantHorizonsCategory);
      categories.add(voxyCategory);
      categories.add(
         new EarthCustomizeScreen.CategoryDefinition(
            "cache",
            List.of(
               cacheEntry(EarthCustomizeScreen.CacheMetric.OSM, true),
               cacheEntry(EarthCustomizeScreen.CacheMetric.ESA, true),
               cacheEntry(EarthCustomizeScreen.CacheMetric.KOPPEN, true),
               cacheEntry(EarthCustomizeScreen.CacheMetric.TERRAIN, true),
               cacheEntry(EarthCustomizeScreen.CacheMetric.SWISSALTI3D, true),
               cacheEntry(EarthCustomizeScreen.CacheMetric.AHN, true),
               cacheEntry(EarthCustomizeScreen.CacheMetric.CANELEVATION, true),
               cacheEntry(EarthCustomizeScreen.CacheMetric.NORWAYDTM1, true),
               cacheEntry(EarthCustomizeScreen.CacheMetric.JAPANGSI, true),
               cacheEntry(EarthCustomizeScreen.CacheMetric.ARCTICDEM, true),
               cacheEntry(EarthCustomizeScreen.CacheMetric.USGS, true),
               cacheEntry(EarthCustomizeScreen.CacheMetric.COPERNICUS, true),
               cacheEntry(EarthCustomizeScreen.CacheMetric.TOTAL, false),
               cacheActionButton(Component.translatable("tellus.cache.delete_all"), EarthCustomizeScreen.CacheManager::deleteAll)
            )
         )
      );
      categories.add(new EarthCustomizeScreen.CategoryDefinition("data_sources", dataSourcesEntries()));
      return categories;
   }

   private static EarthCustomizeScreen.SliderDefinition slider(String key, double defaultValue, double min, double max, double step) {
      return new EarthCustomizeScreen.SliderDefinition(key, defaultValue, min, max, step);
   }

   private static EarthCustomizeScreen.ToggleDefinition toggle(String key, boolean defaultValue) {
      return new EarthCustomizeScreen.ToggleDefinition(key, defaultValue);
   }

   private static EarthCustomizeScreen.DemProviderToggleDefinition demProviderToggle(
      EarthGeneratorSettings.DemProvider provider, boolean defaultValue
   ) {
      return new EarthCustomizeScreen.DemProviderToggleDefinition(provider, defaultValue);
   }

   private static EarthCustomizeScreen.ModeDefinition mode(String key, EarthGeneratorSettings.DistantHorizonsRenderMode defaultValue) {
      return new EarthCustomizeScreen.ModeDefinition(key, defaultValue);
   }

   private static List<EarthCustomizeScreen.SettingDefinition> hmaAccessEntries(EarthCustomizeScreen.HmaTokenDefinition tokenDefinition) {
      List<EarthCustomizeScreen.SettingDefinition> entries = new ArrayList<>();
      entries.add(infoHeader("High Mountain Asia 8 m Access"));
      entries.add(infoLine("Tellus can use NSIDC High Mountain Asia 8 m DEM tiles, but Earthdata access is required."));
      entries.add(infoSubtle("1. Create an Earthdata account."));
      entries.add(infoLink("https://urs.earthdata.nasa.gov/users/new"));
      entries.add(infoSubtle("2. Follow the Earthdata token guide and generate a bearer token."));
      entries.add(infoLink("https://urs.earthdata.nasa.gov/documentation/for_users/user_token"));
      entries.add(infoSubtle("3. Paste the bearer token below and test it."));
      entries.add(infoSubtle(Component.translatable("tellus.hma_access.instruction.test_before_save")));
      entries.add(infoSubtle("Stored locally in config/tellus-hma-access.properties on this computer."));
      entries.add(infoSpacer());
      entries.add(infoSubtle("Earthdata bearer token"));
      entries.add(tokenDefinition);
      entries.add(new EarthCustomizeScreen.HmaAccessButtonsDefinition(tokenDefinition));
      entries.add(new EarthCustomizeScreen.HmaAccessStatusDefinition());
      entries.add(infoSpacer());
      return entries;
   }

   private EarthCustomizeScreen.CategoryLinkDefinition categoryLink( EarthCustomizeScreen.CategoryDefinition targetCategory) {
      return new EarthCustomizeScreen.CategoryLinkDefinition(targetCategory);
   }

   private static void disableCategoryForMissingMod( EarthCustomizeScreen.CategoryDefinition category,  String modId) {
      Component tooltip = requiresModTooltip(modId);

      for (EarthCustomizeScreen.SettingDefinition setting : category.getSettings()) {
         if (setting instanceof EarthCustomizeScreen.ModeDefinition mode) {
            mode.unavailable(tooltip);
         } else if (setting instanceof EarthCustomizeScreen.ToggleDefinition toggle) {
            toggle.unavailable(tooltip);
         } else if (setting instanceof EarthCustomizeScreen.SliderDefinition slider) {
            slider.unavailable(tooltip);
         }
      }
   }

   private static EarthCustomizeScreen.ButtonDefinition comingSoonButton() {
      Component label = Objects.requireNonNull(Component.translatable("gui.tellus.coming_soon"), "comingSoonLabel");
      Component tooltip = Objects.requireNonNull(
         Component.translatable("tellus.customize.coming_soon").withStyle(ChatFormatting.GRAY).copy().append(" ").append(WORK_IN_PROGRESS),
         "comingSoonTooltip"
      );
      return new EarthCustomizeScreen.ButtonDefinition(label, tooltip, false);
   }

   private static EarthCustomizeScreen.CacheEntryDefinition cacheEntry(EarthCustomizeScreen.CacheMetric metric, boolean allowDelete) {
      return new EarthCustomizeScreen.CacheEntryDefinition(metric, allowDelete);
   }

   private static EarthCustomizeScreen.CacheActionDefinition cacheActionButton( Component label,  Runnable action) {
      return new EarthCustomizeScreen.CacheActionDefinition(label, action);
   }

   private static List<EarthCustomizeScreen.SettingDefinition> dataSourcesEntries() {
      List<EarthCustomizeScreen.SettingDefinition> entries = new ArrayList<>();
      entries.add(infoHeader("ESA WorldCover 2021 (land cover)"));
      entries.add(infoLine("ESA WorldCover 2021 (10 m land cover, v200)"));
      entries.add(infoLine("© ESA WorldCover project / Contains modified Copernicus Sentinel data (2021)"));
      entries.add(infoLine("processed by ESA WorldCover consortium."));
      entries.add(infoSubtle("License: CC BY 4.0"));
      entries.add(infoLink("https://creativecommons.org/licenses/by/4.0/"));
      entries.add(infoLink("https://doi.org/10.5281/zenodo.7254221"));
      entries.add(infoLine("In-game processing: reprojected to the world grid, resampled to blocks,"));
      entries.add(infoLine("and cached as tiles for fast lookup."));
      entries.add(infoSpacer());
      entries.add(infoHeader("Köppen–Geiger climate classification (1 km, Beck et al. 2018)"));
      entries.add(infoLine("Source: Beck, H.E., Zimmermann, N.E., McVicar, T.R., et al. (2018)."));
      entries.add(infoLine("Present and future Köppen–Geiger climate classification maps at 1-km resolution"));
      entries.add(infoLine("(Scientific Data)."));
      entries.add(infoSubtle("License: CC BY 4.0"));
      entries.add(infoLink("https://creativecommons.org/licenses/by/4.0/"));
      entries.add(infoSubtle("Publication DOI:"));
      entries.add(infoLink("https://doi.org/10.1038/sdata.2018.214"));
      entries.add(infoLine("In-game processing: reprojected and resampled to match the world grid."));
      entries.add(infoLine("Cached for fast lookup."));
      entries.add(infoSpacer());
      entries.add(infoHeader("Terrain Tiles (global DEM tiles)"));
      entries.add(infoLine("Terrain Tiles (AWS Open Data Registry / Mapzen Jörð)"));
      entries.add(infoLine("Accessed on " + formatLocalDate() + " from"));
      entries.add(infoLink("https://registry.opendata.aws/terrain-tiles"));
      entries.add(infoLine("Source attributions for Terrain Tiles:"));
      entries.add(infoSubtle("ArcticDEM terrain data: DEM(s) were created from DigitalGlobe, Inc. imagery"));
      entries.add(infoSubtle("and funded under National Science Foundation awards 1043681, 1559691, and 1542736;"));
      entries.add(infoSubtle("Australia terrain data © Commonwealth of Australia (Geoscience Australia) 2017;"));
      entries.add(infoSubtle("Austria terrain data © offene Daten Österreichs – Digitales Geländemodell (DGM) Österreich;"));
      entries.add(infoSubtle("Canada terrain data contains information licensed under the Open Government Licence – Canada;"));
      entries.add(infoSubtle("Europe terrain data produced using Copernicus data and information funded by the"));
      entries.add(infoSubtle("European Union – EU-DEM layers;"));
      entries.add(infoSubtle("Global ETOPO1 terrain data U.S. National Oceanic and Atmospheric Administration;"));
      entries.add(infoSubtle("New Zealand terrain data Copyright 2011 Crown copyright (c) Land Information"));
      entries.add(infoSubtle("New Zealand and the New Zealand Government (All rights reserved);"));
      entries.add(infoSubtle("Norway terrain data © Kartverket;"));
      entries.add(infoSubtle("United Kingdom terrain data © Environment Agency copyright and/or database right 2015."));
      entries.add(infoSubtle("All rights reserved;"));
      entries.add(infoSubtle("United States 3DEP (formerly NED) and global GMTED2010 and SRTM terrain data"));
      entries.add(infoSubtle("courtesy of the U.S. Geological Survey."));
      entries.add(infoSpacer());
      entries.add(infoHeader("swissALTI3D 0.5 m / 2 m"));
      entries.add(infoLine("swisstopo swissALTI3D digital terrain model"));
      entries.add(infoLine("Accessed on " + formatLocalDate() + " from"));
      entries.add(infoLink("https://www.swisstopo.admin.ch/en/height-model-swissalti3d"));
      entries.add(infoLink("https://data.geo.admin.ch/api/stac/v0.9/collections/ch.swisstopo.swissalti3d"));
      entries.add(infoLink("https://data.geo.admin.ch/api/stac/v0.9/collections/ch.swisstopo.swissalti3d/items"));
      entries.add(infoLink("https://www.swisstopo.admin.ch/en/free-geodata-ogd"));
      entries.add(infoLink("https://www.swisstopo.admin.ch/en/conditions-geodata"));
      entries.add(infoLine("In-game processing: sampled from official swisstopo swissALTI3D COG tiles"));
      entries.add(infoLine("with byte-range reads and cached locally. Automatic prefers"));
      entries.add(infoLine("0.5 m at fine scales and 2 m at broader scales inside swissALTI3D coverage."));
      entries.add(infoSubtle("Terrain data source: Federal Office of Topography swisstopo."));
      entries.add(infoSpacer());
      entries.add(infoHeader("AHN DTM 0.5 m"));
      entries.add(infoLine("Actueel Hoogtebestand Nederland (AHN) DTM 0,5m"));
      entries.add(infoLine("Accessed on " + formatLocalDate() + " from"));
      entries.add(infoLink("https://service.pdok.nl/rws/actueel-hoogtebestand-nederland/atom/dtm_05m.xml"));
      entries.add(infoLink("https://service.pdok.nl/rws/actueel-hoogtebestand-nederland/atom/downloads/dtm_05m/kaartbladindex.json"));
      entries.add(infoLine("In-game processing: sampled from official PDOK/RWS AHN DTM COG tiles"));
      entries.add(infoLine("with byte-range reads and cached locally for reuse."));
      entries.add(infoSubtle("AHN terrain data provided by Rijkswaterstaat through PDOK."));
      entries.add(infoSpacer());
      entries.add(infoHeader("CANElevation 2 m / 30 m DTM"));
      entries.add(infoLine("Natural Resources Canada CANElevation DEMs"));
      entries.add(infoLine("Accessed on " + formatLocalDate() + " from"));
      entries.add(infoLink("https://registry.opendata.aws/canelevation-dem/"));
      entries.add(infoLine("In-game processing: sampled from public CANElevation GeoTIFF tiles"));
      entries.add(infoLine("with byte-range reads and cached locally. Automatic prefers"));
      entries.add(infoLine("HRDEM 2 m in Canada where available and falls back to MRDEM 30 m elsewhere."));
      entries.add(infoSubtle("High Resolution Digital Elevation Model (HRDEM) 2 m DTM and"));
      entries.add(infoSubtle("Multi-Resolution Digital Elevation Model (MRDEM) 30 m DTM"));
      entries.add(infoSubtle("provided by Natural Resources Canada under the Open Government Licence - Canada."));
      entries.add(infoSpacer());
      entries.add(infoHeader("Norway DTM1 1 m"));
      entries.add(infoLine("Kartverket / Geonorge DTM 1 Høydedata"));
      entries.add(infoLine("Accessed on " + formatLocalDate() + " from"));
      entries.add(infoLink("https://nedlasting.geonorge.no/geonorge/ATOM/hoydedata/Hoydedata_ServiceFeed.atom"));
      entries.add(infoLink("https://nedlasting.geonorge.no/geonorge/ATOM/hoydedata/datasett/DTM1.atom"));
      entries.add(infoLink("https://data.norge.no/nlod/en/2.0"));
      entries.add(infoLine("In-game processing: sampled from official Geonorge DTM1 GeoTIFF tiles"));
      entries.add(infoLine("with byte-range reads and cached locally. Automatic prefers"));
      entries.add(infoLine("Norway DTM1 across published mainland Norway coverage."));
      entries.add(infoSubtle("Terrain data provided by Kartverket under the Norwegian Licence for Open Government Data (NLOD) 2.0."));
      entries.add(infoSpacer());
      entries.add(infoHeader("Japan GSI elevation tiles"));
      entries.add(infoLine("Geospatial Information Authority of Japan (GSI) elevation tiles"));
      entries.add(infoLine("Accessed on " + formatLocalDate() + " from"));
      entries.add(infoLink("https://maps.gsi.go.jp/development/ichiran.html#dem"));
      entries.add(infoLink("https://maps.gsi.go.jp/development/demtile.html"));
      entries.add(infoLink("https://maps.gsi.go.jp/development/hyokochi.html"));
      entries.add(infoLink("https://maps.gsi.go.jp/help/termsofuse.html"));
      entries.add(infoLine("In-game processing: sampled from official GSI PNG elevation tiles"));
      entries.add(infoLine("with internal DEM1A/5A/5B/5C/10B fallback and cached locally."));
      entries.add(infoLine("Automatic prefers Japan GSI in Japan and falls through to the next"));
      entries.add(infoLine("best DEM when higher-resolution GSI tiles are missing or nodata."));
      entries.add(infoSubtle("Terrain data provided by the Geospatial Information Authority of Japan under GSI tile terms of use."));
      entries.add(infoSpacer());
      entries.add(infoHeader("Polar Geospatial Center DEMs"));
      entries.add(infoLine("ArcticDEM and REMA mosaic DEMs"));
      entries.add(infoLine("Accessed on " + formatLocalDate() + " from"));
      entries.add(infoLink("https://registry.opendata.aws/pgc-arcticdem/"));
      entries.add(infoLink("https://registry.opendata.aws/pgc-rema/"));
      entries.add(infoLine("In-game processing: sampled from public PGC mosaic GeoTIFF tiles"));
      entries.add(infoLine("with byte-range reads and cached locally for reuse."));
      entries.add(infoSubtle("ArcticDEM terrain data: DEM(s) were created from DigitalGlobe, Inc. imagery"));
      entries.add(infoSubtle("and funded under National Science Foundation awards 1043681, 1559691, and 1542736."));
      entries.add(infoSubtle("REMA terrain data: Reference Elevation Model of Antarctica provided by"));
      entries.add(infoSubtle("the Polar Geospatial Center and collaborators."));
      entries.add(infoSpacer());
      entries.add(infoHeader("USGS 3DEP Bare-Earth DEM"));
      entries.add(infoLine("USGS 3DEP Bare-Earth DEM Dynamic Service"));
      entries.add(infoLine("Accessed on " + formatLocalDate() + " from"));
      entries.add(infoLink("https://elevation.nationalmap.gov/arcgis/rest/services/3DEPElevation/ImageServer?f=pjson"));
      entries.add(infoLine("In-game processing: sampled from public USGS 3DEP dynamic GeoTIFF tiles"));
      entries.add(infoLine("and cached locally. Automatic uses USGS across the U.S."));
      entries.add(infoSubtle("USGS 3DEP products and services:"));
      entries.add(infoLink("https://www.usgs.gov/3d-elevation-program/about-3dep-products-services"));
      entries.add(infoSpacer());
      entries.add(infoHeader("Copernicus DEM GLO-30 / GLO-90"));
      entries.add(infoLine("Copernicus Digital Elevation Model (GLO-30 / GLO-90)"));
      entries.add(infoLine("Accessed on " + formatLocalDate() + " from"));
      entries.add(infoLink("https://registry.opendata.aws/copernicus-dem/"));
      entries.add(infoLine("In-game processing: sampled from public Copernicus COG tiles"));
      entries.add(infoLine("with Terrain Tiles retained automatically where its footprint metadata"));
      entries.add(infoLine("indicates higher native resolution outside regional DEM coverage."));
      entries.add(infoSpacer());
      entries.add(infoHeader("Open-Meteo (weather)"));
      entries.add(infoLine("Weather data provided by Open-Meteo.com."));
      entries.add(infoLink("https://open-meteo.com/"));
      entries.add(infoSubtle("License: CC BY 4.0"));
      entries.add(infoLink("https://creativecommons.org/licenses/by/4.0/"));
      entries.add(infoLine("Credit: \"Weather data by Open-Meteo.com\"."));
      entries.add(infoLink("https://doi.org/10.5281/ZENODO.7970649"));
      return entries;
   }

   private static EarthCustomizeScreen.TextLineDefinition infoHeader(String text) {
      return new EarthCustomizeScreen.TextLineDefinition(Component.literal(text), -604044, null);
   }

   private static EarthCustomizeScreen.TextLineDefinition infoLine(String text) {
      return new EarthCustomizeScreen.TextLineDefinition(Component.literal(text), -1710619, null);
   }

   private static EarthCustomizeScreen.TextLineDefinition infoSubtle(String text) {
      return new EarthCustomizeScreen.TextLineDefinition(Component.literal(text), -4605511, null);
   }

   private static EarthCustomizeScreen.TextLineDefinition infoSubtle( Component text) {
      return new EarthCustomizeScreen.TextLineDefinition(text, -4605511, null);
   }

   private static EarthCustomizeScreen.TextLineDefinition infoLink(String url) {
      return new EarthCustomizeScreen.TextLineDefinition(Component.literal(url), -11141121, url);
   }

   private static EarthCustomizeScreen.SpacerDefinition infoSpacer() {
      return new EarthCustomizeScreen.SpacerDefinition();
   }

   private static String formatWorldScale(double value) {
      if (value < 1000.0) {
         double rounded = Math.round(value * 10.0) / 10.0;
         return Math.abs(rounded - Math.rint(rounded)) < 1.0E-6
            ? String.format(Locale.ROOT, "1:%.0fm", rounded)
            : String.format(Locale.ROOT, "1:%.1fm", rounded);
      } else {
         return String.format(Locale.ROOT, "1:%.1fkm", value / 1000.0);
      }
   }

   private static String formatLocalDate() {
      DateTimeFormatter formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault());
      return formatter.format(LocalDate.now());
   }

   private static String formatMultiplier(double value) {
      return String.format(Locale.ROOT, "%.1fx", value);
   }

   private static String formatHeightOffset(double value) {
      return String.format(Locale.ROOT, "%.0f blocks", value);
   }

   private static String formatSeaLevel(double value) {
      return value <= -63.5 ? "Automatic" : String.format(Locale.ROOT, "%.0f blocks", value);
   }

   private static String formatPercent(double value) {
      return String.format(Locale.ROOT, "%.0f%%", value);
   }

   private static String formatChunkRadius(double value) {
      return String.format(Locale.ROOT, "%.0f chunks", value);
   }

   private static String formatChunksPerTick(double value) {
      return String.format(Locale.ROOT, "%.0f chunks/tick", value);
   }

   private static String formatMaxAltitude(double value) {
      return formatAltitude(value, -1.0);
   }

   private static String formatMinAltitude(double value) {
      return formatAltitude(value, -2048.0);
   }

   
   private static Component formatRenderMode(EarthGeneratorSettings.DistantHorizonsRenderMode mode) {
      return Objects.requireNonNull(Component.translatable("property.tellus.distant_horizons_render_mode.value." + mode.id()), "renderModeLabel");
   }

   
   private static Component formatDemProvider(EarthGeneratorSettings.DemProvider provider) {
      return Objects.requireNonNull(Component.translatable("property.tellus.dem_provider.value." + provider.id()), "demProviderLabel");
   }

   private static String formatAltitude(double value, double autoValue) {
      return value <= autoValue + 0.5 ? "Automatic" : String.format(Locale.ROOT, "%.0f blocks", value);
   }

   private static double findBestWorldScale(EarthGeneratorSettings settings, TerrainPreview.PreviewInfo info) {
      for (int step = 10; step <= 5000; step++) {
         double worldScale = step / 10.0;
         if (canFitPreviewAtWorldScale(settings, info, worldScale)) {
            return worldScale;
         }
      }

      return 500.0;
   }

   private static boolean canFitPreviewAtWorldScale(EarthGeneratorSettings settings, TerrainPreview.PreviewInfo info, double worldScale) {
      int minBase = scaledSurfaceY(info.minElevationMeters(), worldScale, settings.terrestrialHeightScale(), settings.oceanicHeightScale(), 0);
      int maxBase = scaledSurfaceY(info.maxElevationMeters(), worldScale, settings.terrestrialHeightScale(), settings.oceanicHeightScale(), 0);
      int minOffset = Math.max(EarthGeneratorSettings.MIN_WORLD_Y - minBase, -2000);
      int maxOffset = Math.min(EarthGeneratorSettings.MAX_WORLD_Y - maxBase, 128);
      return minOffset <= maxOffset;
   }

   private static int findBestHeightOffset(EarthGeneratorSettings settings, TerrainPreview.PreviewInfo info, double worldScale) {
      int minBase = scaledSurfaceY(info.minElevationMeters(), worldScale, settings.terrestrialHeightScale(), settings.oceanicHeightScale(), 0);
      int maxBase = scaledSurfaceY(info.maxElevationMeters(), worldScale, settings.terrestrialHeightScale(), settings.oceanicHeightScale(), 0);
      int minOffset = Math.max(EarthGeneratorSettings.MIN_WORLD_Y - minBase, -2000);
      int maxOffset = Math.min(EarthGeneratorSettings.MAX_WORLD_Y - maxBase, 128);
      if (minOffset > maxOffset) {
         return Mth.clamp(settings.heightOffset(), -2000, 128);
      } else {
         return Mth.clamp((int)Math.round((minOffset + maxOffset) * 0.5), minOffset, maxOffset);
      }
   }

   private static int scaledSurfaceY(double elevation, double worldScale, double terrestrialScale, double oceanicScale, int heightOffset) {
      double scale = elevation >= 0.0 ? terrestrialScale : oceanicScale;
      double scaled = elevation * scale / worldScale;
      int base = elevation >= 0.0 ? Mth.ceil(scaled) : Mth.floor(scaled);
      return base + heightOffset;
   }

   private static String formatBytes(long bytes) {
      if (bytes <= 0L) {
         return "0 B";
      } else {
         double value = bytes;
         String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};

         int unit;
         for (unit = 0; value >= 1024.0 && unit < units.length - 1; unit++) {
            value /= 1024.0;
         }

         return unit == 0
            ? Objects.requireNonNull(String.format(Locale.ROOT, "%d B", bytes), "formattedBytes")
            : Objects.requireNonNull(String.format(Locale.ROOT, "%.1f %s", value, units[unit]), "formattedBytes");
      }
   }

   
   private static Component settingName(String key) {
      return Objects.requireNonNull(Component.translatable("property.tellus." + key + ".name"), "settingName");
   }

   
   private static Component settingTooltip(String key) {
      return Objects.requireNonNull(Component.translatable("property.tellus." + key + ".tooltip").withStyle(ChatFormatting.GRAY), "settingTooltip");
   }

   
   private static Component requiresModTooltip( String modId) {
      return Objects.requireNonNull(
         Component.translatable("tellus.compatibility.requires_mod", new Object[]{compatibilityModName(modId)}).withStyle(ChatFormatting.GRAY),
         "requiresModTooltip"
      );
   }

   
   private static Component compatibilityModName( String modId) {
      return switch (modId) {
         case "distanthorizons" -> (MutableComponent)Objects.requireNonNull(Component.translatable("tellus.compatibility.mod.distant_horizons"), "modName");
         case "voxy" -> (MutableComponent)Objects.requireNonNull(Component.translatable("tellus.compatibility.mod.voxy"), "modName");
         default -> (MutableComponent)Objects.requireNonNull(Component.literal(modId), "modName");
      };
   }

   
   private static Component workInProgressTooltip(String key) {
      return Objects.requireNonNull(settingTooltip(key), "settingTooltip").copy().append(Component.literal(" ")).append(WORK_IN_PROGRESS);
   }

   private static Component historicalSnowReworkTooltip() {
      return Objects.requireNonNull(
         Component.translatable("property.tellus.historical_snow.rework.tooltip").withStyle(ChatFormatting.GRAY),
         "historicalSnowReworkTooltip"
      );
   }

   private static String describeDimensionType(DimensionType type) {
      return "minY=" + type.minY() + ",height=" + type.height() + ",logicalHeight=" + type.logicalHeight();
   }

   private int resolveAltitudeSetting(String key, double autoValue) {
      double value = this.findSliderValue(key, autoValue);
      return value <= autoValue + 0.5 ? Integer.MIN_VALUE : (int)Math.round(value);
   }

   private int resolveSeaLevelSetting(String key, double autoValue) {
      double value = this.findSliderValue(key, autoValue);
      return value <= autoValue + 0.5 ? -2147483647 : (int)Math.round(value);
   }

   private static EarthCustomizeScreen.RegistryUpdate updateDimensionTypeRegistry(
      LayeredRegistryAccess<RegistryLayer> registries, DimensionType updatedType, ResourceKey<DimensionType> targetKey
   ) {
      LayeredRegistryAccess<RegistryLayer> updatedRegistries = registries;
      boolean updatedAny = false;
      ResourceKey<DimensionType> nonNullTargetKey = Objects.requireNonNull(targetKey, "targetDimensionTypeKey");

      for (RegistryLayer layer : RegistryLayer.values()) {
         Frozen layerAccess = updatedRegistries.getLayer(layer);
         if (!layerAccess.lookup(Registries.DIMENSION_TYPE).isEmpty()) {
            Registry<DimensionType> source = layerAccess.registryOrThrow(Registries.DIMENSION_TYPE);
            Lifecycle lifecycle = Objects.requireNonNull(
               source instanceof MappedRegistry<DimensionType> mapped ? mapped.registryLifecycle() : Lifecycle.experimental(), "dimensionTypeLifecycle"
            );
            MappedRegistry<DimensionType> copy = new MappedRegistry<>(Registries.DIMENSION_TYPE, lifecycle);
            List<Entry<ResourceKey<DimensionType>, DimensionType>> entries = new ArrayList<>(source.entrySet());
            entries.sort(Comparator.comparingInt(entry -> source.getId(entry.getValue())));

            for (Entry<ResourceKey<DimensionType>, DimensionType> entry : entries) {
               ResourceKey<DimensionType> key = Objects.requireNonNull(entry.getKey(), "dimensionTypeKey");
               if (!key.equals(nonNullTargetKey)) {
                  DimensionType value = Objects.requireNonNull(entry.getValue(), "dimensionType");
                  RegistrationInfo info = Objects.requireNonNull(source.registrationInfo(key).orElse(RegistrationInfo.BUILT_IN), "registrationInfo");
                  copy.register(key, value, info);
               }
            }

            Optional<KnownPack> emptyKnownPack = Objects.requireNonNull(Optional.empty(), "emptyKnownPack");
            RegistrationInfo targetInfo = Objects.requireNonNull(
               source.registrationInfo(nonNullTargetKey)
                  .map(infox -> new RegistrationInfo(emptyKnownPack, Objects.requireNonNull(infox.lifecycle(), "dimensionTypeLifecycle")))
                  .orElseGet(() -> new RegistrationInfo(emptyKnownPack, Objects.requireNonNull(Lifecycle.experimental(), "experimentalLifecycle"))),
               "dimensionTypeRegistrationInfo"
            );
            copy.register(nonNullTargetKey, Objects.requireNonNull(updatedType, "updatedType"), targetInfo);
            Registry<DimensionType> frozen = copy.freeze();
            Frozen updatedLayer = replaceRegistry(layerAccess, Registries.DIMENSION_TYPE, frozen);
            updatedRegistries = replaceLayer(updatedRegistries, layer, updatedLayer);
            updatedAny = true;
         }
      }

      if (!updatedAny) {
         throw new IllegalStateException("Dimension type registry missing");
      } else {
         RegistryLookup<DimensionType> dimensionTypes = updatedRegistries.compositeAccess().lookupOrThrow(Registries.DIMENSION_TYPE);
         Holder<DimensionType> holder = Objects.requireNonNull(dimensionTypes.getOrThrow(nonNullTargetKey), "dimensionTypeHolder");
         return new EarthCustomizeScreen.RegistryUpdate(updatedRegistries, holder);
      }
   }

   private static Frozen replaceRegistry(Frozen source, ResourceKey<? extends Registry<?>> registryKey, Registry<?> replacement) {
      Map<ResourceKey<? extends Registry<?>>, Registry<?>> registryMap = new LinkedHashMap<>();
      source.registries().forEach(entry -> registryMap.put(entry.key(), entry.value()));
      registryMap.put(registryKey, replacement);
      return new ImmutableRegistryAccess(registryMap).freeze();
   }

   
   private static LayeredRegistryAccess<RegistryLayer> replaceLayer(
       LayeredRegistryAccess<RegistryLayer> registries,  RegistryLayer target, Frozen replacement
   ) {
      Frozen replacementChecked = Objects.requireNonNull(replacement, "replacement");
      RegistryLayer[] layers = RegistryLayer.values();
      List<Frozen> replacements = new ArrayList<>();
      boolean found = false;

      for (RegistryLayer layer : layers) {
         if (!found) {
            if (layer == target) {
               found = true;
               replacements.add(replacementChecked);
            }
         } else {
            replacements.add(registries.getLayer(layer));
         }
      }

      if (!found) {
         throw new IllegalStateException("Registry layer missing: " + target);
      } else {
         return registries.replaceFrom(target, replacements);
      }
   }

   private void showCategories() {
      this.currentCategoryId = null;
      this.setPreviewVisible(true);
      this.list.clear();

      for (EarthCustomizeScreen.CategoryDefinition category : this.categories) {
         if (category.showInRootMenu()) {
            Component label = Objects.requireNonNull(category.getLabel(), "categoryLabel");
            Button button = Button.builder(label, btn -> this.showCategory(category)).bounds(0, 0, this.list.getRowWidth(), 20).build();
            this.list.addWidget(button);
         }
      }

      this.list.setScrollAmount(0.0);
   }

   private void showCategory(EarthCustomizeScreen.CategoryDefinition category) {
      this.currentCategoryId = category.getId();
      this.list.clear();
      String parentCategoryId = category.parentCategoryId();
      EarthCustomizeScreen.CategoryDefinition backTarget = parentCategoryId == null ? null : this.findCategoryById(parentCategoryId);
      Component backLabel = Objects.requireNonNull(Component.translatable("gui.back"), "backLabel");
      Button back = Button.builder(backLabel, btn -> {
         if (backTarget != null) {
            this.showCategory(backTarget);
         } else {
            this.showCategories();
         }
      }).bounds(0, 0, this.list.getRowWidth(), 20).build();
      this.list.addWidget(back);
      boolean hidePreview = isPreviewHiddenCategory(category.getId());
      this.setPreviewVisible(!hidePreview);
      this.applyCategoryConstraints(category);
      if ("cache".equals(category.getId())) {
         EarthCustomizeScreen.CacheManager.requestRefresh();
      }

      if ("world".equals(category.getId())) {
         this.list.addWidget(this.createWorldHeaderActions(category));
      }

      if ("structure".equals(category.getId())) {
         this.list.addWidget(this.createStructureHeaderActions(category));
      }

      Runnable onChange = this::onSettingsChanged;
      if ("distant_horizons".equals(category.getId()) || "voxy".equals(category.getId()) || "dem_providers".equals(category.getId())) {
         onChange = () -> {
            this.onSettingsChanged();
            this.showCategory(category);
         };
      }

      if ("openstreetmaps_features".equals(category.getId()) && !this.roadsAndBuildingsSupportedForSelectedScale()) {
         this.list.addWidget(
            infoSubtle(Component.translatable("tellus.openstreetmaps_features.scale_limit_warning")).createWidget(onChange)
         );
      }

      for (EarthCustomizeScreen.SettingDefinition setting : category.getSettings()) {
         this.list.addWidget(setting.createWidget(onChange));
      }

      this.list.setScrollAmount(0.0);
   }

   
   private EarthCustomizeScreen.CategoryDefinition findCategoryById( String id) {
      String targetId = Objects.requireNonNull(id, "id");

      for (EarthCustomizeScreen.CategoryDefinition category : this.categories) {
         if (category.getId().equals(targetId)) {
            return category;
         }
      }

      return null;
   }

   private AbstractWidget createWorldHeaderActions(EarthCustomizeScreen.CategoryDefinition category) {
      EarthGeneratorSettings defaultSettings = Objects.requireNonNull(EarthGeneratorSettings.DEFAULT, "defaultSettings");
      Component restoreDefaultsLabel = Objects.requireNonNull(Component.translatable("gui.tellus.restore_defaults"), "restoreDefaultsLabel");
      Component selectPresetLabel = Objects.requireNonNull(Component.translatable("gui.tellus.select_preset"), "selectPresetLabel");
      Component comingSoonTooltip = Objects.requireNonNull(
         Component.translatable("gui.tellus.coming_soon").withStyle(ChatFormatting.GRAY), "selectPresetTooltip"
      );
      return new EarthCustomizeScreen.DualButtonWidget(restoreDefaultsLabel, btn -> {
         this.applySettingsToCategories(defaultSettings, true);
         this.onSettingsChanged();
         this.showCategory(category);
      }, selectPresetLabel, btn -> {}, false, comingSoonTooltip);
   }

   
   private AbstractWidget createStructureHeaderActions( EarthCustomizeScreen.CategoryDefinition category) {
      Component enableAllLabel = Objects.requireNonNull(Component.translatable("gui.tellus.enable_all"), "enableAllLabel");
      Component disableAllLabel = Objects.requireNonNull(Component.translatable("gui.tellus.disable_all"), "disableAllLabel");
      return new EarthCustomizeScreen.DualButtonWidget(enableAllLabel, btn -> {
         this.setCategoryToggleValues(category, true);
         this.onSettingsChanged();
         this.showCategory(category);
      }, disableAllLabel, btn -> {
         this.setCategoryToggleValues(category, false);
         this.onSettingsChanged();
         this.showCategory(category);
      }, true, null);
   }

   private void setCategoryToggleValues( EarthCustomizeScreen.CategoryDefinition category, boolean value) {
      for (EarthCustomizeScreen.SettingDefinition setting : category.getSettings()) {
         if (setting instanceof EarthCustomizeScreen.ToggleDefinition toggle && !toggle.locked && !toggle.unavailable) {
            toggle.value = value;
         }
      }
   }

   private void applyCategoryConstraints(EarthCustomizeScreen.CategoryDefinition category) {
      boolean bothCompatibilityModsInstalled = FabricLoader.getInstance().isModLoaded("distanthorizons") && FabricLoader.getInstance().isModLoaded("voxy");
      boolean voxyEnabled = bothCompatibilityModsInstalled
         && this.findToggleValue("voxy_chunk_pregen_enabled", EarthGeneratorSettings.DEFAULT.voxyChunkPregenEnabled());
      if ("openstreetmaps_features".equals(category.getId())) {
         EarthCustomizeScreen.ToggleDefinition roads = null;
         EarthCustomizeScreen.ToggleDefinition buildings = null;

         for (EarthCustomizeScreen.SettingDefinition setting : category.getSettings()) {
            if (setting instanceof EarthCustomizeScreen.ToggleDefinition toggle && toggle.key.equals("enable_roads")) {
               roads = toggle;
            }

            if (setting instanceof EarthCustomizeScreen.ToggleDefinition toggle && toggle.key.equals("enable_buildings")) {
               buildings = toggle;
            }
         }

         boolean scaleSupported = this.roadsAndBuildingsSupportedForSelectedScale();
         if (roads != null) {
            roads.forceDisabled(
               !scaleSupported,
               Component.translatable("property.tellus.enable_roads.scale_limit.tooltip").withStyle(ChatFormatting.GRAY)
            );
         }

         if (buildings != null) {
            buildings.forceDisabled(
               !scaleSupported,
               Component.translatable("property.tellus.enable_buildings.scale_limit.tooltip").withStyle(ChatFormatting.GRAY)
            );
         }
      } else if ("dem_providers".equals(category.getId())) {
         EarthCustomizeScreen.ToggleDefinition automatic = null;
         Map<EarthGeneratorSettings.DemProvider, EarthCustomizeScreen.DemProviderToggleDefinition> providerToggles = new LinkedHashMap<>();

         for (EarthCustomizeScreen.SettingDefinition setting : category.getSettings()) {
            if (setting instanceof EarthCustomizeScreen.ToggleDefinition toggle && toggle.key.equals("dem_automatic")) {
               automatic = toggle;
            }

            if (setting instanceof EarthCustomizeScreen.DemProviderToggleDefinition providerToggle) {
               providerToggles.put(providerToggle.provider, providerToggle);
            }
         }

         Component automaticTooltip = Component.translatable("tellus.dem_provider.force_disabled.automatic").withStyle(ChatFormatting.GRAY);
         if (automatic != null && automatic.value) {
            for (EarthCustomizeScreen.DemProviderToggleDefinition providerToggle : providerToggles.values()) {
               providerToggle.value = true;
               providerToggle.forceDisabled(true, automaticTooltip);
            }
            return;
         }

         for (EarthCustomizeScreen.DemProviderToggleDefinition providerToggle : providerToggles.values()) {
            providerToggle.forceDisabled(false);
         }

         EarthCustomizeScreen.DemProviderToggleDefinition terrainTiles = providerToggles.get(EarthGeneratorSettings.DemProvider.TERRARIUM);
         EarthCustomizeScreen.DemProviderToggleDefinition copernicus = providerToggles.get(EarthGeneratorSettings.DemProvider.COPERNICUS);
         if (terrainTiles != null && copernicus != null) {
            if (!terrainTiles.value) {
               copernicus.value = true;
               copernicus.forceDisabled(
                  true, Component.translatable("tellus.dem_provider.force_disabled.copernicus_required").withStyle(ChatFormatting.GRAY)
               );
            } else if (!copernicus.value) {
               terrainTiles.value = true;
               terrainTiles.forceDisabled(
                  true, Component.translatable("tellus.dem_provider.force_disabled.terrain_tiles_required").withStyle(ChatFormatting.GRAY)
               );
            }
         }
      } else if ("voxy".equals(category.getId())) {
         EarthCustomizeScreen.ToggleDefinition enabled = null;
         EarthCustomizeScreen.SliderDefinition maxRadius = null;
         EarthCustomizeScreen.SliderDefinition chunksPerTick = null;

         for (EarthCustomizeScreen.SettingDefinition setting : category.getSettings()) {
            if (setting instanceof EarthCustomizeScreen.ToggleDefinition toggle && toggle.key.equals("voxy_chunk_pregen_enabled")) {
               enabled = toggle;
            }

            if (setting instanceof EarthCustomizeScreen.SliderDefinition slider && slider.key.equals("voxy_chunk_pregen_max_radius")) {
               maxRadius = slider;
            }

            if (setting instanceof EarthCustomizeScreen.SliderDefinition slider && slider.key.equals("voxy_chunk_pregen_chunks_per_tick")) {
               chunksPerTick = slider;
            }
         }

         if (enabled != null && maxRadius != null && chunksPerTick != null) {
            boolean disable = !enabled.value;
            maxRadius.forceDisabled(disable);
            chunksPerTick.forceDisabled(disable);
         }
      } else if ("distant_horizons".equals(category.getId())) {
         EarthCustomizeScreen.ModeDefinition renderMode = null;
         EarthCustomizeScreen.ToggleDefinition waterResolver = null;

         for (EarthCustomizeScreen.SettingDefinition setting : category.getSettings()) {
            if (setting instanceof EarthCustomizeScreen.ModeDefinition mode && mode.key.equals("distant_horizons_render_mode")) {
               renderMode = mode;
            }

            if (setting instanceof EarthCustomizeScreen.ToggleDefinition toggle && toggle.key.equals("distant_horizons_water_resolver")) {
               waterResolver = toggle;
            }
         }

         if (renderMode != null && waterResolver != null) {
            boolean blockedByVoxy = bothCompatibilityModsInstalled && voxyEnabled;
            boolean ultraFast = renderMode.value == EarthGeneratorSettings.DistantHorizonsRenderMode.ULTRA_FAST;
            renderMode.forceDisabled(blockedByVoxy);
            waterResolver.forceDisabled(ultraFast || blockedByVoxy);
            if (ultraFast) {
               waterResolver.value = false;
            }
         }
      }
   }

   private boolean roadsAndBuildingsSupportedForSelectedScale() {
      return roadsAndBuildingsSupportedForWorldScale(this.findSliderValue("world_scale", EarthGeneratorSettings.DEFAULT.worldScale()));
   }

   private static boolean roadsAndBuildingsSupportedForWorldScale(double worldScale) {
      return worldScale > 0.0 && worldScale <= OSM_ROADS_AND_BUILDINGS_MAX_WORLD_SCALE;
   }

   private static boolean isPreviewHiddenCategory(String id) {
      return "cache".equals(id) || "data_sources".equals(id) || "hma_access".equals(id);
   }

   private void setPreviewVisible(boolean visible) {
      this.updateLayout(visible);
   }

   private void refreshCurrentCategory() {
      if (this.currentCategoryId == null) {
         this.showCategories();
      } else {
         EarthCustomizeScreen.CategoryDefinition category = this.findCategoryById(this.currentCategoryId);
         if (category != null) {
            this.showCategory(category);
         } else {
            this.showCategories();
         }
      }
   }

   private void updateLayout(boolean previewVisible) {
      if (this.list != null) {
         int listTop = 40;
         int listHeight = Math.max(0, this.height - 36 - listTop);
         int listWidth = previewVisible ? Math.max(140, this.width / 2 - 20) : Math.max(140, this.width - 20);
         this.list.setX(10);
         this.list.setY(listTop);
         this.list.setWidth(listWidth);
         this.list.setHeight(listHeight);
         if (this.previewWidget != null) {
            this.previewWidget.visible = previewVisible;
            this.previewWidget.active = previewVisible;
            if (previewVisible) {
               int previewWidth = Math.max(140, this.width - listWidth - 40);
               int previewHeight = Math.max(80, this.height - 80);
               int previewX = this.width - previewWidth - 10;
               this.previewWidget.setX(previewX);
               this.previewWidget.setY(listTop);
               this.previewWidget.setWidth(previewWidth);
               this.previewWidget.setHeight(previewHeight);
            }
         }
      }
   }

   private static boolean isShiftDown() {
      long window = Minecraft.getInstance().getWindow().getWindow();
      return InputConstants.isKeyDown(window, 340) || InputConstants.isKeyDown(window, 344);
   }

   @Environment(EnvType.CLIENT)
   private static final class ButtonDefinition implements EarthCustomizeScreen.SettingDefinition {
      
      private final Component label;
      
      private final Component tooltip;
      private final boolean active;

      private ButtonDefinition(Component label, Component tooltip, boolean active) {
         this.label = Objects.requireNonNull(label, "buttonLabel");
         this.tooltip = tooltip;
         this.active = active;
      }

      @Override
      public AbstractWidget createWidget(Runnable onChange) {
         Button button = Button.builder(this.label, btn -> {}).bounds(0, 0, 0, 20).build();
         button.active = this.active;
         if (this.tooltip != null) {
            button.setTooltip(Tooltip.create(this.tooltip));
         }

         return button;
      }
   }

   @Environment(EnvType.CLIENT)
   private static final class CacheActionDefinition implements EarthCustomizeScreen.SettingDefinition {
      
      private final Component label;
      
      private final Runnable action;

      private CacheActionDefinition( Component label,  Runnable action) {
         this.label = Objects.requireNonNull(label, "label");
         this.action = Objects.requireNonNull(action, "action");
      }

      @Override
      public AbstractWidget createWidget(Runnable onChange) {
         return new EarthCustomizeScreen.CacheActionWidget(this.label, this.action);
      }
   }

   @Environment(EnvType.CLIENT)
   private static final class CacheActionWidget extends AbstractWidget {
      private final Button button;

      private CacheActionWidget( Component label,  Runnable action) {
         super(0, 0, 0, 20, Component.empty());
         Component safeLabel = Objects.requireNonNull(label, "label");
         Runnable safeAction = Objects.requireNonNull(action, "action");
         this.button = Button.builder(safeLabel, btn -> safeAction.run()).bounds(0, 0, 0, 20).build();
      }

      protected void renderWidget( GuiGraphics graphics, int mouseX, int mouseY, float delta) {
         EarthCustomizeScreen.CacheSnapshot snapshot = EarthCustomizeScreen.CacheManager.snapshot();
         this.button.active = snapshot.ready() && snapshot.totalBytes() > 0L;
         this.button.setX(this.getX());
         this.button.setY(this.getY());
         this.button.setWidth(this.width);
         this.button.setHeight(this.height);
         this.button.render(graphics, mouseX, mouseY, delta);
      }

      public void onClick(double mouseX, double mouseY) {
         this.button.mouseClicked(mouseX, mouseY, 0);
      }

      protected void onDrag(double mouseX, double mouseY, double deltaX, double deltaY) {
         this.button.mouseDragged(mouseX, mouseY, 0, deltaX, deltaY);
      }

      public void onRelease(double mouseX, double mouseY) {
         this.button.mouseReleased(mouseX, mouseY, 0);
      }

      protected void updateWidgetNarration( NarrationElementOutput narration) {
      }
   }

   @Environment(EnvType.CLIENT)
   private static final class CacheEntryDefinition implements EarthCustomizeScreen.SettingDefinition {
      private final EarthCustomizeScreen.CacheMetric metric;
      private final boolean allowDelete;

      private CacheEntryDefinition(EarthCustomizeScreen.CacheMetric metric, boolean allowDelete) {
         this.metric = Objects.requireNonNull(metric, "metric");
         this.allowDelete = allowDelete;
      }

      @Override
      public AbstractWidget createWidget(Runnable onChange) {
         return new EarthCustomizeScreen.CacheEntryWidget(this.metric, this.allowDelete);
      }
   }

   @Environment(EnvType.CLIENT)
   private static final class CacheEntryWidget extends AbstractWidget {
      
      private static final Component DELETE_LABEL = Objects.requireNonNull(Component.translatable("tellus.cache.delete"), "deleteLabel");
      private final EarthCustomizeScreen.CacheMetric metric;
      private final Button deleteButton;
      private final boolean allowDelete;

      private CacheEntryWidget(EarthCustomizeScreen.CacheMetric metric, boolean allowDelete) {
         super(0, 0, 0, 20, Component.empty());
         this.metric = Objects.requireNonNull(metric, "metric");
         this.allowDelete = allowDelete;
         this.deleteButton = Button.builder(DELETE_LABEL, btn -> EarthCustomizeScreen.CacheManager.delete(metric)).bounds(0, 0, 0, 20).build();
      }

      protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
         Font font = Minecraft.getInstance().font;
         EarthCustomizeScreen.CacheSnapshot snapshot = EarthCustomizeScreen.CacheManager.snapshot();
         boolean ready = snapshot.ready();
         long bytes = snapshot.bytesFor(this.metric);
         String sizeText = ready ? EarthCustomizeScreen.formatBytes(bytes) : "...";
         int buttonWidth = Math.max(96, font.width(DELETE_LABEL) + 12);
         int buttonX = this.getX() + this.width - buttonWidth;
         int buttonY = this.getY();
         int sizeWidth = font.width(sizeText);
         int sizeX = buttonX - 4 - sizeWidth;
         int labelX = this.getX() + 4;
         int textY = this.getY() + (this.height - 9) / 2;
         Component label = this.metric.label();
         graphics.drawString(font, label, labelX, textY, -1, false);
         graphics.drawString(font, sizeText, sizeX, textY, -6250336, false);
         if (this.allowDelete) {
            this.deleteButton.setX(buttonX);
            this.deleteButton.setY(buttonY);
            this.deleteButton.setWidth(buttonWidth);
            this.deleteButton.setHeight(this.height);
            this.deleteButton.active = ready && bytes > 0L;
            this.deleteButton.render(graphics, mouseX, mouseY, delta);
         }
      }

      public void onClick(double mouseX, double mouseY) {
         if (this.allowDelete) {
            this.deleteButton.mouseClicked(mouseX, mouseY, 0);
         }
      }

      protected void onDrag(double mouseX, double mouseY, double deltaX, double deltaY) {
         if (this.allowDelete) {
            this.deleteButton.mouseDragged(mouseX, mouseY, 0, deltaX, deltaY);
         }
      }

      public void onRelease(double mouseX, double mouseY) {
         if (this.allowDelete) {
            this.deleteButton.mouseReleased(mouseX, mouseY, 0);
         }
      }

      protected void updateWidgetNarration( NarrationElementOutput narration) {
      }
   }

   @Environment(EnvType.CLIENT)
   private static final class HmaAccessManager {
      private static final String TEST_URL
         = "https://data.nsidc.earthdatacloud.nasa.gov/nsidc-cumulus-prod-protected/HMA/HMA_DEM8m_MOS/1/2002/01/28/HMA_DEM8m_MOS_20170716_tile-677.tif";
      private static final int TEST_SUCCESS_COLOR = 5635925;
      private static final int CONNECT_TIMEOUT_MS = 8000;
      private static final int READ_TIMEOUT_MS = 12000;
      private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
         Thread thread = new Thread(runnable, "tellus-hma-access");
         thread.setDaemon(true);
         return thread;
      });
      private static final AtomicReference<EarthCustomizeScreen.HmaAccessState> STATE = new AtomicReference<>(initialState());
      private static final AtomicBoolean TEST_IN_FLIGHT = new AtomicBoolean(false);
      private static final AtomicReference<String> VERIFIED_TOKEN = new AtomicReference<>("");

      private static EarthCustomizeScreen.HmaAccessState state() {
         return STATE.get();
      }

      private static String savedToken() {
         return HmaAccessConfig.bearerToken();
      }

      private static boolean isTesting() {
         return state().testing();
      }

      private static boolean canSaveToken(String token) {
         String normalized = normalizeToken(token);
         return !normalized.isBlank() && !Objects.equals(normalized, savedToken()) && Objects.equals(normalized, VERIFIED_TOKEN.get());
      }

      private static void onTokenEdited(String token) {
         String normalized = normalizeToken(token);
         if (state().testing() || Objects.equals(normalized, VERIFIED_TOKEN.get())) {
            return;
         }

         if (Objects.equals(normalized, savedToken())) {
            STATE.set(initialState());
         } else if (!normalized.isBlank()) {
            STATE.set(testBeforeSaveState());
         } else if (HmaAccessConfig.hasBearerToken()) {
            STATE.set(savedState());
         } else {
            STATE.set(notConfiguredState());
         }
      }

      private static void saveToken(String token) {
         String normalized = normalizeToken(token);

         try {
            if (normalized.isBlank()) {
               VERIFIED_TOKEN.set("");
               HmaAccessConfig.clearBearerToken();
               STATE.set(notConfiguredState());
            } else if (Objects.equals(normalized, savedToken())) {
               STATE.set(savedState());
            } else if (!canSaveToken(normalized)) {
               STATE.set(testBeforeSaveState());
            } else {
               HmaAccessConfig.setBearerToken(normalized);
               VERIFIED_TOKEN.set(normalized);
               STATE.set(savedState());
            }
         } catch (RuntimeException error) {
            Tellus.LOGGER.warn("Failed to save HMA access token", error);
            STATE.set(new EarthCustomizeScreen.HmaAccessState(false, Component.translatable("tellus.hma_access.status.save_failed"), -65536));
         }
      }

      private static void clearToken() {
         try {
            VERIFIED_TOKEN.set("");
            HmaAccessConfig.clearBearerToken();
            STATE.set(notConfiguredState());
         } catch (RuntimeException error) {
            Tellus.LOGGER.warn("Failed to clear HMA access token", error);
            STATE.set(new EarthCustomizeScreen.HmaAccessState(false, Component.translatable("tellus.hma_access.status.save_failed"), -65536));
         }
      }

      private static void testToken(String token) {
         String normalized = normalizeToken(token);
         if (normalized.isBlank()) {
            STATE.set(new EarthCustomizeScreen.HmaAccessState(false, Component.translatable("tellus.hma_access.status.token_missing"), -6250336));
         } else if (TEST_IN_FLIGHT.compareAndSet(false, true)) {
            STATE.set(new EarthCustomizeScreen.HmaAccessState(true, Component.translatable("tellus.hma_access.status.testing"), -11167233));
            CompletableFuture.<EarthCustomizeScreen.HmaAccessState>supplyAsync(() -> performAccessTest(normalized), EXECUTOR).whenComplete((state, error) -> {
               if (error != null || state == null) {
                  Tellus.LOGGER.warn("Failed to test HMA access token", error);
                  STATE.set(new EarthCustomizeScreen.HmaAccessState(false, Component.translatable("tellus.hma_access.status.network_failed"), -65536));
               } else {
                  STATE.set(state);
               }

               TEST_IN_FLIGHT.set(false);
            });
         }
      }

      private static EarthCustomizeScreen.HmaAccessState performAccessTest(String token) {
         HttpURLConnection connection = null;

         try {
            connection = openProbeConnection(TEST_URL);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            int status = connection.getResponseCode();
            if (isSuccessStatus(status)) {
               VERIFIED_TOKEN.set(token);
               return successState();
            } else if (status == 401 || status == 403) {
               VERIFIED_TOKEN.compareAndSet(token, "");
               return new EarthCustomizeScreen.HmaAccessState(false, Component.translatable("tellus.hma_access.status.test_failed_auth"), -65536);
            } else if (isRedirectStatus(status)) {
               return probeRedirectTarget(connection.getHeaderField("Location"), token);
            } else {
               VERIFIED_TOKEN.compareAndSet(token, "");
               return new EarthCustomizeScreen.HmaAccessState(false, Component.translatable("tellus.hma_access.status.http_error", status), -65536);
            }
         } catch (IOException error) {
            Tellus.LOGGER.debug("HMA token test failed", error);
            VERIFIED_TOKEN.compareAndSet(token, "");
            return new EarthCustomizeScreen.HmaAccessState(false, Component.translatable("tellus.hma_access.status.network_failed"), -65536);
         } finally {
            if (connection != null) {
               connection.disconnect();
            }
         }
      }

      private static HttpURLConnection openProbeConnection(String targetUrl) throws IOException {
         HttpURLConnection connection = (HttpURLConnection)URI.create(targetUrl).toURL().openConnection();
         connection.setInstanceFollowRedirects(false);
         connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
         connection.setReadTimeout(READ_TIMEOUT_MS);
         connection.setRequestMethod("GET");
         connection.setRequestProperty("User-Agent", "Tellus/1.0 (Minecraft Mod)");
         connection.setRequestProperty("Range", "bytes=0-0");
         return connection;
      }

      private static EarthCustomizeScreen.HmaAccessState probeRedirectTarget(String location, String token) {
         if (location == null || location.isBlank()) {
            VERIFIED_TOKEN.compareAndSet(token, "");
            return new EarthCustomizeScreen.HmaAccessState(false, Component.translatable("tellus.hma_access.status.network_failed"), -65536);
         } else if (isEarthdataLoginRedirect(location)) {
            VERIFIED_TOKEN.compareAndSet(token, "");
            return new EarthCustomizeScreen.HmaAccessState(false, Component.translatable("tellus.hma_access.status.test_failed_auth"), -65536);
         } else {
            HttpURLConnection redirectConnection = null;

            try {
               redirectConnection = openProbeConnection(location);
               int status = redirectConnection.getResponseCode();
               if (isSuccessStatus(status)) {
                  VERIFIED_TOKEN.set(token);
                  return successState();
               } else if (status == 401 || status == 403) {
                  VERIFIED_TOKEN.compareAndSet(token, "");
                  return new EarthCustomizeScreen.HmaAccessState(false, Component.translatable("tellus.hma_access.status.test_failed_auth"), -65536);
               } else {
                  VERIFIED_TOKEN.compareAndSet(token, "");
                  return new EarthCustomizeScreen.HmaAccessState(false, Component.translatable("tellus.hma_access.status.http_error", status), -65536);
               }
            } catch (IOException error) {
               Tellus.LOGGER.debug("HMA token redirect probe failed", error);
               VERIFIED_TOKEN.compareAndSet(token, "");
               return new EarthCustomizeScreen.HmaAccessState(false, Component.translatable("tellus.hma_access.status.network_failed"), -65536);
            } finally {
               if (redirectConnection != null) {
                  redirectConnection.disconnect();
               }
            }
         }
      }

      private static boolean isEarthdataLoginRedirect(String location) {
         try {
            String host = URI.create(location).getHost();
            return host != null && host.equals("urs.earthdata.nasa.gov");
         } catch (IllegalArgumentException error) {
            return false;
         }
      }

      private static boolean isRedirectStatus(int status) {
         return status >= 300 && status < 400;
      }

      private static boolean isSuccessStatus(int status) {
         return status == 200 || status == 206;
      }

      private static EarthCustomizeScreen.HmaAccessState successState() {
         return new EarthCustomizeScreen.HmaAccessState(false, Component.translatable("tellus.hma_access.status.test_success"), TEST_SUCCESS_COLOR);
      }

      private static EarthCustomizeScreen.HmaAccessState savedState() {
         return new EarthCustomizeScreen.HmaAccessState(false, Component.translatable("tellus.hma_access.status.saved"), -10461088);
      }

      private static EarthCustomizeScreen.HmaAccessState notConfiguredState() {
         return new EarthCustomizeScreen.HmaAccessState(false, Component.translatable("tellus.hma_access.status.not_configured"), -6250336);
      }

      private static EarthCustomizeScreen.HmaAccessState testBeforeSaveState() {
         return new EarthCustomizeScreen.HmaAccessState(false, Component.translatable("tellus.hma_access.status.test_before_save"), -6250336);
      }

      private static EarthCustomizeScreen.HmaAccessState initialState() {
         return HmaAccessConfig.hasBearerToken()
            ? savedState()
            : notConfiguredState();
      }

      private static String normalizeToken(String token) {
         return token == null ? "" : token.trim();
      }
   }

   @Environment(EnvType.CLIENT)
   private record HmaAccessState(boolean testing, Component message, int color) {
      private HmaAccessState {
         Objects.requireNonNull(message, "message");
      }
   }

   @Environment(EnvType.CLIENT)
   private static final class CacheManager {
      private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(new EarthCustomizeScreen.CacheThreadFactory());
      private static final AtomicReference<EarthCustomizeScreen.CacheSnapshot> SNAPSHOT = new AtomicReference<>(EarthCustomizeScreen.CacheSnapshot.empty());
      private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);

      private static EarthCustomizeScreen.CacheSnapshot snapshot() {
         return SNAPSHOT.get();
      }

      private static void requestRefresh() {
         refreshAsync(null);
      }

      private static void delete(EarthCustomizeScreen.CacheMetric metric) {
         List<Path> paths = metric.resolvePaths();
         if (!paths.isEmpty()) {
            refreshAsync(() -> {
               metric.clearRuntimeCache();
               for (Path path : paths) {
                  deleteDirectory(path);
               }
            });
         }
      }

      private static void deleteAll() {
         refreshAsync(() -> {
            TellusCacheRegistry.clearAll();
            for (EarthCustomizeScreen.CacheMetric metric : EarthCustomizeScreen.CacheMetric.values()) {
               for (Path path : metric.resolvePaths()) {
                  deleteDirectory(path);
               }
            }
         });
      }

      private static void refreshAsync( Runnable task) {
         if (IN_FLIGHT.compareAndSet(false, true)) {
            SNAPSHOT.set(EarthCustomizeScreen.CacheSnapshot.empty());
            CompletableFuture.<EarthCustomizeScreen.CacheSnapshot>supplyAsync(() -> {
               if (task != null) {
                  task.run();
               }

               return computeSnapshot();
            }, EXECUTOR).whenComplete((snapshot, error) -> {
               if (snapshot != null && error == null) {
                  SNAPSHOT.set(snapshot);
               } else {
                  SNAPSHOT.set(EarthCustomizeScreen.CacheSnapshot.empty());
               }

               IN_FLIGHT.set(false);
            });
         }
      }

      private static EarthCustomizeScreen.CacheSnapshot computeSnapshot() {
         long osmBytes = sizeFor(EarthCustomizeScreen.CacheMetric.OSM);
         long esaBytes = sizeFor(EarthCustomizeScreen.CacheMetric.ESA);
         long koppenBytes = sizeFor(EarthCustomizeScreen.CacheMetric.KOPPEN);
         long terrainBytes = sizeFor(EarthCustomizeScreen.CacheMetric.TERRAIN);
         long swissAlti3dBytes = sizeFor(EarthCustomizeScreen.CacheMetric.SWISSALTI3D);
         long ahnBytes = sizeFor(EarthCustomizeScreen.CacheMetric.AHN);
         long canElevationBytes = sizeFor(EarthCustomizeScreen.CacheMetric.CANELEVATION);
         long norwayDtm1Bytes = sizeFor(EarthCustomizeScreen.CacheMetric.NORWAYDTM1);
         long japanGsiBytes = sizeFor(EarthCustomizeScreen.CacheMetric.JAPANGSI);
         long arcticDemBytes = sizeFor(EarthCustomizeScreen.CacheMetric.ARCTICDEM);
         long usgsBytes = sizeFor(EarthCustomizeScreen.CacheMetric.USGS);
         long copernicusBytes = sizeFor(EarthCustomizeScreen.CacheMetric.COPERNICUS);
         return new EarthCustomizeScreen.CacheSnapshot(
            true,
            osmBytes,
            esaBytes,
            koppenBytes,
            terrainBytes,
            swissAlti3dBytes,
            ahnBytes,
            canElevationBytes,
            norwayDtm1Bytes,
            japanGsiBytes,
            arcticDemBytes,
            usgsBytes,
            copernicusBytes
         );
      }

      private static long sizeFor(EarthCustomizeScreen.CacheMetric metric) {
         long total = 0L;

         for (Path path : metric.resolvePaths()) {
            if (Files.exists(path)) {
               try {
                  try (Stream<Path> stream = Files.walk(path)) {
                     total += stream.filter(x$0 -> Files.isRegularFile(x$0)).mapToLong(file -> {
                        try {
                           return Files.size(file);
                        } catch (IOException var2x) {
                           return 0L;
                        }
                     }).sum();
                  }
               } catch (IOException var8) {
                  Tellus.LOGGER.warn("Failed to scan cache at {}", path, var8);
               }
            }
         }

         return total;
      }

      private static void deleteDirectory(Path root) {
         if (Files.exists(root)) {
            Path deleteRoot = moveAsideForDeletion(root);
            if (deleteRoot != null) {
               try {
                  deleteTree(deleteRoot);
               } catch (IOException var3) {
                  Tellus.LOGGER.warn("Failed to delete cache folder {}", deleteRoot, var3);
               }
            }
         }
      }

      private static Path moveAsideForDeletion(Path root) {
         Path deleteRoot = root.resolveSibling(root.getFileName() + ".deleting-" + System.nanoTime());

         try {
            Files.move(root, deleteRoot, StandardCopyOption.ATOMIC_MOVE);
            return deleteRoot;
         } catch (AtomicMoveNotSupportedException var4) {
            try {
               Files.move(root, deleteRoot);
               return deleteRoot;
            } catch (IOException var3) {
               Tellus.LOGGER.warn("Failed to prepare cache folder {} for deletion", root, var3);
               return null;
            }
         } catch (IOException var5) {
            Tellus.LOGGER.warn("Failed to prepare cache folder {} for deletion", root, var5);
            return null;
         }
      }

      private static void deleteTree(Path root) throws IOException {
         Files.walkFileTree(root, new SimpleFileVisitor<>() {
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
               Files.deleteIfExists(file);
               return FileVisitResult.CONTINUE;
            }

            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
               if (error != null) {
                  throw error;
               }

               Files.deleteIfExists(dir);
               return FileVisitResult.CONTINUE;
            }
         });
      }
   }

   @Environment(EnvType.CLIENT)
   private static enum CacheMetric {
      OSM("tellus.cache.section.osm", "tellus/cache/map", TellusCacheDomain.OSM),
      ESA("tellus.cache.section.esa", "tellus/cache/worldcover2021", TellusCacheDomain.LAND_COVER),
      KOPPEN("tellus.cache.section.koppen", "tellus/cache/koppen", TellusCacheDomain.KOPPEN),
      TERRAIN(
         "tellus.cache.section.terrain",
         new String[]{"tellus/cache/elevation-tellus", "tellus/cache/elevation-normalized"},
         new TellusCacheDomain[]{TellusCacheDomain.TERRAIN, TellusCacheDomain.NORMALIZED_TERRAIN}
      ),
      SWISSALTI3D("tellus.cache.section.swissalti3d", "tellus/cache/elevation-swissalti3d", TellusCacheDomain.SWISSALTI3D),
      AHN("tellus.cache.section.ahn", "tellus/cache/elevation-ahn", TellusCacheDomain.AHN),
      CANELEVATION("tellus.cache.section.canelevation", "tellus/cache/elevation-canelevation", TellusCacheDomain.CANELEVATION),
      NORWAYDTM1("tellus.cache.section.norwaydtm1", "tellus/cache/elevation-norway-dtm1", TellusCacheDomain.NORWAYDTM1),
      JAPANGSI("tellus.cache.section.japangsi", "tellus/cache/elevation-japangsi", TellusCacheDomain.JAPANGSI),
      ARCTICDEM(
         "tellus.cache.section.arcticdem",
         new String[]{"tellus/cache/elevation-arcticdem", "tellus/cache/elevation-rema"},
         new TellusCacheDomain[]{TellusCacheDomain.ARCTICDEM, TellusCacheDomain.REMA}
      ),
      USGS("tellus.cache.section.usgs", "tellus/cache/elevation-usgs3dep", TellusCacheDomain.USGS),
      COPERNICUS("tellus.cache.section.copernicus", "tellus/cache/elevation-copernicus", TellusCacheDomain.COPERNICUS),
      TOTAL("tellus.cache.section.total", new String[0], new TellusCacheDomain[0]);

      
      private final String labelKey;
      private final String[] relativePaths;
      private final TellusCacheDomain[] domains;

      private CacheMetric( String labelKey,  String relativePath,  TellusCacheDomain domain) {
         this(
            labelKey,
            relativePath == null ? null : new String[]{relativePath},
            domain == null ? null : new TellusCacheDomain[]{domain}
         );
      }

      private CacheMetric( String labelKey,  String[] relativePaths,  TellusCacheDomain[] domains) {
         this.labelKey = Objects.requireNonNull(labelKey, "labelKey");
         this.relativePaths = relativePaths;
         this.domains = domains;
      }

      
      private Component label() {
         return Objects.requireNonNull(Component.translatable(this.labelKey), "cacheLabel");
      }

      
      private List<Path> resolvePaths() {
         if (this.relativePaths == null || this.relativePaths.length == 0) {
            return List.of();
         } else {
            List<Path> paths = new ArrayList<>(this.relativePaths.length);

            for (String relativePath : this.relativePaths) {
               if (relativePath != null) {
                  paths.add(Minecraft.getInstance().gameDirectory.toPath().resolve(relativePath));
               }
            }

            return paths;
         }
      }

      private void clearRuntimeCache() {
         if (this.domains != null) {
            for (TellusCacheDomain domain : this.domains) {
               if (domain != null) {
                  TellusCacheRegistry.clear(domain);
               }
            }
         }
      }
   }

   @Environment(EnvType.CLIENT)
   private record CacheSnapshot(
      boolean ready,
      long osmBytes,
      long esaBytes,
      long koppenBytes,
      long terrainBytes,
      long swissAlti3dBytes,
      long ahnBytes,
      long canElevationBytes,
      long norwayDtm1Bytes,
      long japanGsiBytes,
      long arcticDemBytes,
      long usgsBytes,
      long copernicusBytes
   ) {
      private static EarthCustomizeScreen.CacheSnapshot empty() {
         return new EarthCustomizeScreen.CacheSnapshot(false, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
      }

      private long bytesFor(EarthCustomizeScreen.CacheMetric metric) {
         return switch (metric) {
            case OSM -> this.osmBytes;
            case ESA -> this.esaBytes;
            case KOPPEN -> this.koppenBytes;
            case TERRAIN -> this.terrainBytes;
            case SWISSALTI3D -> this.swissAlti3dBytes;
            case AHN -> this.ahnBytes;
            case CANELEVATION -> this.canElevationBytes;
            case NORWAYDTM1 -> this.norwayDtm1Bytes;
            case JAPANGSI -> this.japanGsiBytes;
            case ARCTICDEM -> this.arcticDemBytes;
            case USGS -> this.usgsBytes;
            case COPERNICUS -> this.copernicusBytes;
            case TOTAL -> this.totalBytes();
         };
      }

      private long totalBytes() {
         return this.osmBytes
            + this.esaBytes
            + this.koppenBytes
            + this.terrainBytes
            + this.swissAlti3dBytes
            + this.ahnBytes
            + this.canElevationBytes
            + this.norwayDtm1Bytes
            + this.japanGsiBytes
            + this.arcticDemBytes
            + this.usgsBytes
            + this.copernicusBytes;
      }
   }

   @Environment(EnvType.CLIENT)
   private static final class CacheThreadFactory implements ThreadFactory {
      private int index;

      @Override
      public Thread newThread(Runnable runnable) {
         Thread thread = new Thread(runnable, "tellus-cache-" + ++this.index);
         thread.setDaemon(true);
         return thread;
      }
   }

   @Environment(EnvType.CLIENT)
   private static final class CategoryDefinition {
      private final String id;
      private final List<EarthCustomizeScreen.SettingDefinition> settings;
      private boolean showInRootMenu = true;
      
      private String parentCategoryId;

      private CategoryDefinition(String id, List<EarthCustomizeScreen.SettingDefinition> settings) {
         this.id = id;
         this.settings = settings;
      }

      private EarthCustomizeScreen.CategoryDefinition hideFromRoot() {
         this.showInRootMenu = false;
         return this;
      }

      private EarthCustomizeScreen.CategoryDefinition parent( String parentCategoryId) {
         this.parentCategoryId = Objects.requireNonNull(parentCategoryId, "parentCategoryId");
         return this;
      }

      private String getId() {
         return this.id;
      }

      
      private Component getLabel() {
         return this.getLabel(false);
      }

      
      private Component getLabel(boolean selected) {
         Component base = Objects.requireNonNull(Component.translatable("category.tellus." + this.id + ".name"), "categoryLabel");
         return !selected ? base : Objects.requireNonNull(base.copy().withStyle(ChatFormatting.YELLOW), "selectedCategoryLabel");
      }

      private List<EarthCustomizeScreen.SettingDefinition> getSettings() {
         return this.settings;
      }

      private boolean showInRootMenu() {
         return this.showInRootMenu;
      }

      
      private String parentCategoryId() {
         return this.parentCategoryId;
      }
   }

   @Environment(EnvType.CLIENT)
   private final class CategoryLinkDefinition implements EarthCustomizeScreen.SettingDefinition {
      
      private final EarthCustomizeScreen.CategoryDefinition targetCategory;
      private boolean active = true;
      
      private Component label;
      private Component tooltip;

      private CategoryLinkDefinition( EarthCustomizeScreen.CategoryDefinition targetCategory) {
         this.targetCategory = Objects.requireNonNull(targetCategory, "targetCategory");
      }

      private EarthCustomizeScreen.CategoryLinkDefinition active(boolean active) {
         this.active = active;
         return this;
      }

      private EarthCustomizeScreen.CategoryLinkDefinition withLabel(Component label) {
         this.label = Objects.requireNonNull(label, "label");
         return this;
      }

      private EarthCustomizeScreen.CategoryLinkDefinition withTooltip( Component tooltip) {
         this.tooltip = tooltip;
         return this;
      }

      @Override
      public AbstractWidget createWidget(Runnable onChange) {
         Component label = this.label != null ? this.label : this.targetCategory.getLabel();
         Button button = Button.builder(label, btn -> EarthCustomizeScreen.this.showCategory(this.targetCategory)).bounds(0, 0, 0, 20).build();
         button.active = this.active;
         if (this.tooltip != null) {
            button.setTooltip(Tooltip.create(this.tooltip));
         }

         return button;
      }
   }

   @Environment(EnvType.CLIENT)
   private static final class DemProviderToggleDefinition implements EarthCustomizeScreen.SettingDefinition {
      private final EarthGeneratorSettings.DemProvider provider;
      private boolean value;
      private boolean forceDisabled;
      private Component forceDisabledTooltip;

      private DemProviderToggleDefinition(EarthGeneratorSettings.DemProvider provider, boolean defaultValue) {
         this.provider = Objects.requireNonNull(provider, "provider");
         this.value = defaultValue;
      }

      private EarthCustomizeScreen.DemProviderToggleDefinition forceDisabled(boolean forceDisabled) {
         this.forceDisabled = forceDisabled;
         if (!forceDisabled) {
            this.forceDisabledTooltip = null;
         }

         return this;
      }

      private EarthCustomizeScreen.DemProviderToggleDefinition forceDisabled(boolean forceDisabled, Component tooltip) {
         this.forceDisabled = forceDisabled;
         this.forceDisabledTooltip = forceDisabled ? Objects.requireNonNull(tooltip, "forceDisabledTooltip") : null;
         return this;
      }

      @Override
      public AbstractWidget createWidget(Runnable onChange) {
         Component name = EarthCustomizeScreen.formatDemProvider(this.provider);
         Component tooltip = this.forceDisabled && this.forceDisabledTooltip != null
            ? this.forceDisabledTooltip
            : EarthCustomizeScreen.settingTooltip("dem_provider");
         Builder<Boolean> builder = CycleButton.booleanBuilder(EarthCustomizeScreen.YES, EarthCustomizeScreen.NO)
            .withInitialValue(this.value)
            .withTooltip(value -> Tooltip.create(tooltip));
         CycleButton<Boolean> button = builder.create(0, 0, 0, 20, name, (btn, value) -> {
            this.value = value;
            onChange.run();
         });
         button.active = !this.forceDisabled;
         return button;
      }
   }

   @Environment(EnvType.CLIENT)
   private static final class DualButtonWidget extends AbstractWidget {
      private final Button leftButton;
      private final Button rightButton;

      private DualButtonWidget(
          Component leftLabel,
          OnPress leftAction,
          Component rightLabel,
          OnPress rightAction,
         boolean rightActive,
          Component rightTooltip
      ) {
         super(0, 0, 0, 20, Component.empty());
         this.leftButton = Button.builder(Objects.requireNonNull(leftLabel, "leftLabel"), Objects.requireNonNull(leftAction, "leftAction"))
            .bounds(0, 0, 0, 20)
            .build();
         this.rightButton = Button.builder(Objects.requireNonNull(rightLabel, "rightLabel"), Objects.requireNonNull(rightAction, "rightAction"))
            .bounds(0, 0, 0, 20)
            .build();
         this.rightButton.active = rightActive;
         if (rightTooltip != null) {
            this.rightButton.setTooltip(Tooltip.create(rightTooltip));
         }
      }

      protected void renderWidget( GuiGraphics graphics, int mouseX, int mouseY, float delta) {
         int leftWidth = Math.max(0, (this.width - 4) / 2);
         int rightWidth = Math.max(0, this.width - leftWidth - 4);
         int x = this.getX();
         int y = this.getY();
         this.leftButton.setX(x);
         this.leftButton.setY(y);
         this.leftButton.setWidth(leftWidth);
         this.leftButton.setHeight(this.height);
         this.rightButton.setX(x + leftWidth + 4);
         this.rightButton.setY(y);
         this.rightButton.setWidth(rightWidth);
         this.rightButton.setHeight(this.height);
         this.leftButton.render(graphics, mouseX, mouseY, delta);
         this.rightButton.render(graphics, mouseX, mouseY, delta);
      }

      public boolean mouseClicked(double mouseX, double mouseY, int button) {
         boolean leftClicked = this.leftButton.mouseClicked(mouseX, mouseY, button);
         boolean rightClicked = this.rightButton.mouseClicked(mouseX, mouseY, button);
         return leftClicked || rightClicked;
      }

      protected void onDrag(double mouseX, double mouseY, double deltaX, double deltaY) {
         this.leftButton.mouseDragged(mouseX, mouseY, 0, deltaX, deltaY);
         this.rightButton.mouseDragged(mouseX, mouseY, 0, deltaX, deltaY);
      }

      public void onRelease(double mouseX, double mouseY) {
         this.leftButton.mouseReleased(mouseX, mouseY, 0);
         this.rightButton.mouseReleased(mouseX, mouseY, 0);
      }

      protected void updateWidgetNarration( NarrationElementOutput narration) {
      }
   }

   @Environment(EnvType.CLIENT)
   private static final class HmaTokenDefinition implements EarthCustomizeScreen.SettingDefinition {
      private String value;
      private EditBox widget;

      private HmaTokenDefinition(String initialValue) {
         this.value = HmaAccessManager.savedToken();
         this.setValue(initialValue);
      }

      private String value() {
         return this.value;
      }

      private void setValue(String value) {
         String normalized = HmaAccessManager.normalizeToken(value);
         this.value = normalized;
         EditBox widget = this.widget;
         if (widget != null && !Objects.equals(widget.getValue(), normalized)) {
            widget.setValue(normalized);
         }
      }

      @Override
      public AbstractWidget createWidget(Runnable onChange) {
         EditBox widget = new EditBox(Minecraft.getInstance().font, 0, 0, 0, 20, Component.literal("Earthdata bearer token"));
         widget.setMaxLength(4096);
         widget.setHint(Component.translatable("tellus.hma_access.token.hint"));
         widget.setTooltip(Tooltip.create(Component.translatable("tellus.hma_access.token.tooltip")));
         widget.setValue(this.value);
         widget.setResponder(value -> {
            String normalized = HmaAccessManager.normalizeToken(value);
            this.value = normalized;
            HmaAccessManager.onTokenEdited(normalized);
         });
         this.widget = widget;
         return widget;
      }
   }

   @Environment(EnvType.CLIENT)
   private static final class HmaAccessButtonsDefinition implements EarthCustomizeScreen.SettingDefinition {
      private final EarthCustomizeScreen.HmaTokenDefinition tokenDefinition;

      private HmaAccessButtonsDefinition(EarthCustomizeScreen.HmaTokenDefinition tokenDefinition) {
         this.tokenDefinition = Objects.requireNonNull(tokenDefinition, "tokenDefinition");
      }

      @Override
      public AbstractWidget createWidget(Runnable onChange) {
         return new EarthCustomizeScreen.HmaAccessButtonsWidget(this.tokenDefinition);
      }
   }

   @Environment(EnvType.CLIENT)
   private static final class HmaAccessButtonsWidget extends AbstractWidget {
      private final EarthCustomizeScreen.HmaTokenDefinition tokenDefinition;
      private final Button saveButton;
      private final Button testButton;
      private final Button clearButton;

      private HmaAccessButtonsWidget(EarthCustomizeScreen.HmaTokenDefinition tokenDefinition) {
         super(0, 0, 0, 20, Component.empty());
         this.tokenDefinition = Objects.requireNonNull(tokenDefinition, "tokenDefinition");
         this.saveButton = Button.builder(Component.translatable("tellus.hma_access.action.save"), btn -> {
            HmaAccessManager.saveToken(this.tokenDefinition.value());
         }).bounds(0, 0, 0, 20).build();
         this.testButton = Button.builder(Component.translatable("tellus.hma_access.action.test"), btn -> {
            HmaAccessManager.testToken(this.tokenDefinition.value());
         }).bounds(0, 0, 0, 20).build();
         this.clearButton = Button.builder(Component.translatable("tellus.hma_access.action.clear"), btn -> {
            this.tokenDefinition.setValue("");
            HmaAccessManager.clearToken();
         }).bounds(0, 0, 0, 20).build();
      }

      protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
         String currentToken = HmaAccessManager.normalizeToken(this.tokenDefinition.value());
         String savedToken = HmaAccessManager.savedToken();
         this.saveButton.active = HmaAccessManager.canSaveToken(currentToken);
         this.testButton.active = !currentToken.isBlank() && !HmaAccessManager.isTesting();
         this.clearButton.active = !currentToken.isBlank() || !savedToken.isBlank();
         int gap = 4;
         int buttonWidth = Math.max(0, (this.width - gap * 2) / 3);
         int remaining = Math.max(0, this.width - buttonWidth * 3 - gap * 2);
         int x = this.getX();
         int y = this.getY();
         this.saveButton.setX(x);
         this.saveButton.setY(y);
         this.saveButton.setWidth(buttonWidth);
         this.saveButton.setHeight(this.height);
         this.testButton.setX(x + buttonWidth + gap);
         this.testButton.setY(y);
         this.testButton.setWidth(buttonWidth);
         this.testButton.setHeight(this.height);
         this.clearButton.setX(x + (buttonWidth + gap) * 2);
         this.clearButton.setY(y);
         this.clearButton.setWidth(buttonWidth + remaining);
         this.clearButton.setHeight(this.height);
         this.saveButton.render(graphics, mouseX, mouseY, delta);
         this.testButton.render(graphics, mouseX, mouseY, delta);
         this.clearButton.render(graphics, mouseX, mouseY, delta);
      }

      public boolean mouseClicked(double mouseX, double mouseY, int button) {
         boolean saveClicked = this.saveButton.mouseClicked(mouseX, mouseY, button);
         boolean testClicked = this.testButton.mouseClicked(mouseX, mouseY, button);
         boolean clearClicked = this.clearButton.mouseClicked(mouseX, mouseY, button);
         return saveClicked || testClicked || clearClicked;
      }

      protected void onDrag(double mouseX, double mouseY, double deltaX, double deltaY) {
         this.saveButton.mouseDragged(mouseX, mouseY, 0, deltaX, deltaY);
         this.testButton.mouseDragged(mouseX, mouseY, 0, deltaX, deltaY);
         this.clearButton.mouseDragged(mouseX, mouseY, 0, deltaX, deltaY);
      }

      public void onRelease(double mouseX, double mouseY) {
         this.saveButton.mouseReleased(mouseX, mouseY, 0);
         this.testButton.mouseReleased(mouseX, mouseY, 0);
         this.clearButton.mouseReleased(mouseX, mouseY, 0);
      }

      protected void updateWidgetNarration(NarrationElementOutput narration) {
      }
   }

   @Environment(EnvType.CLIENT)
   private static final class HmaAccessStatusDefinition implements EarthCustomizeScreen.SettingDefinition {
      @Override
      public AbstractWidget createWidget(Runnable onChange) {
         return new EarthCustomizeScreen.HmaAccessStatusWidget();
      }
   }

   @Environment(EnvType.CLIENT)
   private static final class HmaAccessStatusWidget extends AbstractWidget {
      private HmaAccessStatusWidget() {
         super(0, 0, 0, 20, Component.empty());
      }

      protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
         EarthCustomizeScreen.HmaAccessState state = HmaAccessManager.state();
         Font font = Minecraft.getInstance().font;
         int textWidth = font.width(state.message());
         int textX = this.getX() + Math.max(0, (this.width - textWidth) / 2);
         int textY = this.getY() + (this.height - 9) / 2;
         graphics.drawString(font, state.message(), textX, textY, state.color(), false);
      }

      protected void updateWidgetNarration(NarrationElementOutput narration) {
      }
   }

   @Environment(EnvType.CLIENT)
   private static final class EarthSlider extends AbstractSliderButton {
      private final EarthCustomizeScreen.SliderDefinition definition;
      private final Runnable onChange;

      private EarthSlider(int x, int y, int width, int height, EarthCustomizeScreen.SliderDefinition definition, Runnable onChange) {
         super(x, y, width, height, Component.empty(), 0.0);
         this.definition = definition;
         this.onChange = onChange;
         this.value = this.toPosition(definition.value);
         this.updateMessage();
      }

      protected void updateMessage() {
         double value = this.toValue(this.value);
         String fallback = Objects.requireNonNull(String.format(Locale.ROOT, "%.2f", value), "formattedValue");
         String valueText = this.definition.display != null ? Objects.requireNonNullElse(this.definition.display.apply(value), fallback) : fallback;
         MutableComponent message = EarthCustomizeScreen.settingName(this.definition.key)
            .copy()
            .append(": ")
            .append(Component.literal(Objects.requireNonNull(valueText, "valueText")));
         this.setMessage(message);
      }

      protected void applyValue() {
         double rawValue = this.toValue(this.value);
         double snappedValue = this.snap(rawValue, this.definition.step);
         if (Math.abs(snappedValue - rawValue) > 1.0E-6) {
            this.value = this.toPosition(snappedValue);
         }

         if (Math.abs(this.definition.value - snappedValue) > 1.0E-6) {
            this.definition.value = snappedValue;
            this.onChange.run();
         }
      }

      private double snap(double value, double step) {
         double effectiveStep = step;
         if ("world_scale".equals(this.definition.key) && EarthCustomizeScreen.isShiftDown()) {
            effectiveStep = Math.max(0.1, step / 50.0);
         }

         if (effectiveStep <= 0.0) {
            return Mth.clamp(value, this.definition.min, this.definition.max);
         } else if ("world_scale".equals(this.definition.key)) {
            double firstStep = this.definition.min;
            double cutoff = (firstStep + effectiveStep) * 0.5;
            if (value <= cutoff) {
               return firstStep;
            } else {
               double snapped = Math.round(value / effectiveStep) * effectiveStep;
               if (effectiveStep < 1.0) {
                  snapped = Math.round(snapped * 10.0) / 10.0;
               }

               double adjusted = Math.max(effectiveStep, snapped);
               return Mth.clamp(adjusted, this.definition.min, this.definition.max);
            }
         } else {
            double snapped = this.definition.min + Math.round((value - this.definition.min) / effectiveStep) * effectiveStep;
            return Mth.clamp(snapped, this.definition.min, this.definition.max);
         }
      }

      private double toPosition(double value) {
         double position = (Mth.clamp(value, this.definition.min, this.definition.max) - this.definition.min) / (this.definition.max - this.definition.min);
         return this.definition.scale.reverse(position);
      }

      private double toValue(double position) {
         double scaled = this.definition.scale.apply(position);
         return this.definition.min + (this.definition.max - this.definition.min) * Mth.clamp(scaled, 0.0, 1.0);
      }
   }

   @Environment(EnvType.CLIENT)
   private static final class ModeDefinition implements EarthCustomizeScreen.SettingDefinition {
      
      private static final List<EarthGeneratorSettings.DistantHorizonsRenderMode> MODES = createModes();
      private final String key;
      private EarthGeneratorSettings.DistantHorizonsRenderMode value;
      private boolean locked;
      private boolean forceDisabled;
      private boolean unavailable;
      
      private Component unavailableTooltip;

      
      private static List<EarthGeneratorSettings.DistantHorizonsRenderMode> createModes() {
         List<EarthGeneratorSettings.DistantHorizonsRenderMode> modes = new ArrayList<>(3);
         modes.add(EarthGeneratorSettings.DistantHorizonsRenderMode.DETAILED);
         modes.add(EarthGeneratorSettings.DistantHorizonsRenderMode.FAST);
         modes.add(EarthGeneratorSettings.DistantHorizonsRenderMode.ULTRA_FAST);
         return modes;
      }

      private ModeDefinition(String key, EarthGeneratorSettings.DistantHorizonsRenderMode defaultValue) {
         this.key = key;
         this.value = defaultValue;
      }

      private EarthCustomizeScreen.ModeDefinition unavailable( Component tooltip) {
         this.unavailable = true;
         this.unavailableTooltip = Objects.requireNonNull(tooltip, "unavailableTooltip");
         return this;
      }

      private EarthCustomizeScreen.ModeDefinition forceDisabled(boolean forceDisabled) {
         this.forceDisabled = forceDisabled;
         return this;
      }

      @Override
      public AbstractWidget createWidget(Runnable onChange) {
         Component name = EarthCustomizeScreen.settingName(this.key);
         Component tooltip = this.unavailableTooltip != null
            ? this.unavailableTooltip
            : (this.locked ? EarthCustomizeScreen.workInProgressTooltip(this.key) : EarthCustomizeScreen.settingTooltip(this.key));
         Builder<EarthGeneratorSettings.DistantHorizonsRenderMode> builder = CycleButton.builder(EarthCustomizeScreen::formatRenderMode)
            .withInitialValue(this.value)
            .withValues(MODES)
            .withTooltip(value -> Tooltip.create(tooltip));
         CycleButton<EarthGeneratorSettings.DistantHorizonsRenderMode> button = builder.create(0, 0, 0, 20, name, (btn, value) -> {
            this.value = value;
            onChange.run();
         });
         button.active = !this.locked && !this.forceDisabled && !this.unavailable;
         return button;
      }
   }

   @Environment(EnvType.CLIENT)
   private record RegistryUpdate(LayeredRegistryAccess<RegistryLayer> registries, Holder<DimensionType> holder) {
   }

   @Environment(EnvType.CLIENT)
   private interface SettingDefinition {
      AbstractWidget createWidget(Runnable var1);
   }

   @Environment(EnvType.CLIENT)
   private static final class SliderDefinition implements EarthCustomizeScreen.SettingDefinition {
      private final String key;
      private final double min;
      private final double max;
      private final double step;
      private double value;
      private DoubleFunction<String> display;
      private EarthCustomizeScreen.SliderScale scale = EarthCustomizeScreen.SliderScale.linear();
      private boolean locked;
      private boolean forceDisabled;
      private boolean unavailable;
      
      private Component unavailableTooltip;

      private SliderDefinition(String key, double defaultValue, double min, double max, double step) {
         this.key = key;
         this.value = defaultValue;
         this.min = min;
         this.max = max;
         this.step = step;
      }

      private EarthCustomizeScreen.SliderDefinition withDisplay(DoubleFunction<String> display) {
         this.display = display;
         return this;
      }

      private EarthCustomizeScreen.SliderDefinition withScale(EarthCustomizeScreen.SliderScale scale) {
         this.scale = scale;
         return this;
      }

      private EarthCustomizeScreen.SliderDefinition locked(boolean locked) {
         this.locked = locked;
         return this;
      }

      private EarthCustomizeScreen.SliderDefinition forceDisabled(boolean forceDisabled) {
         this.forceDisabled = forceDisabled;
         return this;
      }

      private EarthCustomizeScreen.SliderDefinition unavailable( Component tooltip) {
         this.unavailable = true;
         this.unavailableTooltip = Objects.requireNonNull(tooltip, "unavailableTooltip");
         return this;
      }

      @Override
      public AbstractWidget createWidget(Runnable onChange) {
         EarthCustomizeScreen.EarthSlider slider = new EarthCustomizeScreen.EarthSlider(0, 0, 0, 20, this, onChange);
         Component tooltip = this.unavailableTooltip != null
            ? this.unavailableTooltip
            : (this.locked ? EarthCustomizeScreen.workInProgressTooltip(this.key) : EarthCustomizeScreen.settingTooltip(this.key));
         slider.setTooltip(Tooltip.create(tooltip));
         slider.active = !this.locked && !this.forceDisabled && !this.unavailable;
         return slider;
      }
   }

   @Environment(EnvType.CLIENT)
   private interface SliderScale {
      double apply(double var1);

      double reverse(double var1);

      static EarthCustomizeScreen.SliderScale linear() {
         return new EarthCustomizeScreen.SliderScale() {
            @Override
            public double apply(double value) {
               return value;
            }

            @Override
            public double reverse(double value) {
               return value;
            }
         };
      }

      static EarthCustomizeScreen.SliderScale power(double power) {
         return new EarthCustomizeScreen.SliderScale() {
            @Override
            public double apply(double value) {
               return Math.pow(value, power);
            }

            @Override
            public double reverse(double value) {
               return Math.pow(value, 1.0 / power);
            }
         };
      }
   }

   @Environment(EnvType.CLIENT)
   private static final class SpacerDefinition implements EarthCustomizeScreen.SettingDefinition {
      @Override
      public AbstractWidget createWidget(Runnable onChange) {
         return new EarthCustomizeScreen.SpacerWidget();
      }
   }

   @Environment(EnvType.CLIENT)
   private static final class SpacerWidget extends AbstractWidget {
      private SpacerWidget() {
         super(0, 0, 0, 20, Component.empty());
      }

      protected void renderWidget( GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      }

      protected void updateWidgetNarration( NarrationElementOutput narration) {
      }
   }

   @Environment(EnvType.CLIENT)
   private static final class TextLineDefinition implements EarthCustomizeScreen.SettingDefinition {
      
      private final Component text;
      private final int color;
      
      private final String url;

      private TextLineDefinition(Component text, int color,  String url) {
         this.text = Objects.requireNonNull(text, "text");
         this.color = color;
         this.url = url;
      }

      @Override
      public AbstractWidget createWidget(Runnable onChange) {
         return new EarthCustomizeScreen.TextLineWidget(this.text, this.color, this.url);
      }
   }

   @Environment(EnvType.CLIENT)
   private static final class TextLineWidget extends AbstractWidget {
      
      private final Component text;
      private final int color;
      
      private final String url;

      private TextLineWidget(Component text, int color,  String url) {
         super(0, 0, 0, 20, Component.empty());
         this.text = Objects.requireNonNull(text, "text");
         this.color = color;
         this.url = url;
      }

      protected void renderWidget( GuiGraphics graphics, int mouseX, int mouseY, float delta) {
         if (!this.text.getString().isEmpty()) {
            Font font = Minecraft.getInstance().font;
            int textWidth = font.width(this.text);
            boolean hover = this.url != null && this.isMouseOver(mouseX, mouseY);
            int drawColor = hover ? -5570561 : this.color;
            int availableWidth = Math.max(1, this.width - 8);
            float scale = textWidth > availableWidth ? (float)availableWidth / (float)textWidth : 1.0F;
            float scaledWidth = textWidth * scale;
            float scaledHeight = 9.0F * scale;
            float textX = this.getX() + (this.width - scaledWidth) * 0.5F;
            float textY = this.getY() + (this.height - scaledHeight) * 0.5F;
            graphics.pose().pushPose();
            graphics.pose().translate(textX, textY, 0.0F);
            graphics.pose().scale(scale, scale, 1.0F);
            graphics.drawString(font, this.text, 0, 0, drawColor, true);
            if (this.url != null) {
               int underlineY = 9;
               graphics.fill(0, underlineY, textWidth, underlineY + 1, drawColor);
            }

            graphics.pose().popPose();
         }
      }

      public boolean mouseClicked(double mouseX, double mouseY, int button) {
         String url = this.url;
         if (url != null && button == 0 && this.isMouseOver(mouseX, mouseY)) {
            Util.getPlatform().openUri(url);
            return true;
         } else {
            return false;
         }
      }

      protected void updateWidgetNarration( NarrationElementOutput narration) {
      }
   }

   @Environment(EnvType.CLIENT)
   private static final class ToggleDefinition implements EarthCustomizeScreen.SettingDefinition {
      private final String key;
      private boolean value;
      private boolean locked;
      private boolean forceDisabled;
      private boolean unavailable;
      
      private Component unavailableTooltip;
      
      private Component forceDisabledTooltip;

      private ToggleDefinition(String key, boolean defaultValue) {
         this.key = key;
         this.value = defaultValue;
      }

      private EarthCustomizeScreen.ToggleDefinition locked(boolean locked) {
         this.locked = locked;
         return this;
      }

      private EarthCustomizeScreen.ToggleDefinition forceDisabled(boolean forceDisabled) {
         this.forceDisabled = forceDisabled;
         if (!forceDisabled) {
            this.forceDisabledTooltip = null;
         }

         return this;
      }

      private EarthCustomizeScreen.ToggleDefinition forceDisabled(boolean forceDisabled, Component tooltip) {
         this.forceDisabled = forceDisabled;
         this.forceDisabledTooltip = forceDisabled ? Objects.requireNonNull(tooltip, "forceDisabledTooltip") : null;
         return this;
      }

      private EarthCustomizeScreen.ToggleDefinition unavailable( Component tooltip) {
         this.unavailable = true;
         this.unavailableTooltip = Objects.requireNonNull(tooltip, "unavailableTooltip");
         return this;
      }

      @Override
      public AbstractWidget createWidget(Runnable onChange) {
         Component name = EarthCustomizeScreen.settingName(this.key);
         Component tooltip = this.unavailableTooltip != null
            ? this.unavailableTooltip
            : (this.forceDisabled && this.forceDisabledTooltip != null
               ? this.forceDisabledTooltip
               : (this.locked ? EarthCustomizeScreen.workInProgressTooltip(this.key) : EarthCustomizeScreen.settingTooltip(this.key)));
         Builder<Boolean> builder = CycleButton.booleanBuilder(EarthCustomizeScreen.YES, EarthCustomizeScreen.NO)
            .withInitialValue(this.value)
            .withTooltip(value -> Tooltip.create(tooltip));
         CycleButton<Boolean> button = builder.create(0, 0, 0, 20, name, (btn, value) -> {
            this.value = value;
            onChange.run();
         });
         button.active = !this.locked && !this.forceDisabled && !this.unavailable;
         return button;
      }
   }
}
