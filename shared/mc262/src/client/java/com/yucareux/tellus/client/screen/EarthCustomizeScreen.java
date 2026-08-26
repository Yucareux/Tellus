package com.yucareux.tellus.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.serialization.Lifecycle;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.cache.TellusCacheManager;
import com.yucareux.tellus.client.preview.TerrainPreview;
import com.yucareux.tellus.client.preview.TerrainPreviewWidget;
import com.yucareux.tellus.client.widget.CustomizationList;
import com.yucareux.tellus.platform.TellusPlatform;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.EarthProjection;
import com.yucareux.tellus.worldgen.ExperimentalHeightSupport;
import com.yucareux.tellus.worldgen.RandomBiomeCatalog;
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
import java.util.function.Consumer;
import java.util.function.DoubleFunction;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Button.OnPress;
import net.minecraft.client.gui.components.CycleButton.Builder;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.input.MouseButtonEvent;
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
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;

public class EarthCustomizeScreen extends Screen {
   
   private static final Component TITLE = Objects.requireNonNull(Component.translatable("options.tellus.customize_world_title.name"), "customizeTitle");
   
   private static final Component YES = Objects.requireNonNull(Component.translatable("gui.yes").withStyle(ChatFormatting.GREEN), "yesLabel");
   
   private static final Component NO = Objects.requireNonNull(Component.translatable("gui.no").withStyle(ChatFormatting.RED), "noLabel");
   
   private static final Component WORK_IN_PROGRESS = Objects.requireNonNull(
      Component.translatable("tellus.customize.work_in_progress").withStyle(ChatFormatting.GRAY), "workInProgressLabel"
   );
   
   private static final Identifier DYNAMIC_DIMENSION_TYPE_ID = Objects.requireNonNull(
      Identifier.fromNamespaceAndPath("tellus", "earth_dynamic"), "dynamicDimensionTypeId"
   );
   
   private static final ResourceKey<DimensionType> DYNAMIC_DIMENSION_TYPE_KEY = Objects.requireNonNull(
      ResourceKey.create(Registries.DIMENSION_TYPE, DYNAMIC_DIMENSION_TYPE_ID), "dynamicDimensionTypeKey"
   );
   private static final double OSM_ROADS_AND_BUILDINGS_MAX_WORLD_SCALE = 15.0;
   private static final String BIOME_TOGGLE_PREFIX = "random_biome.";
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
   private long randomBiomeSeed = EarthGeneratorSettings.DEFAULT.randomBiomeSeed();
   private Component validationError;

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
            this.minecraft.gui.setScreen(new EarthSpawnpointScreen(this));
         }
      }).bounds(this.width / 2 - 155, buttonY, 150, 20).build());
      Component doneLabel = Objects.requireNonNull(Component.translatable("gui.done"), "doneLabel");
      this.addRenderableWidget(Button.builder(doneLabel, button -> this.onClose()).bounds(this.width / 2 + 5, buttonY, 150, 20).build());
   }

   private void onSettingsChanged() {
      this.validationError = null;
      this.previewDirtyAt = System.currentTimeMillis();
   }

   public void applySpawnpoint(double latitude, double longitude) {
      this.validationError = null;
      this.spawnLatitude = latitude;
      this.spawnLongitude = longitude;
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
         this.minecraft.gui.setScreen(new TerrainPreviewScreen(this, this.preview, viewState));
      }
   }

   public void applyPreviewViewState( TerrainPreviewWidget.ViewState state) {
      this.pendingPreviewViewState = state;
      if (this.previewWidget != null && this.minecraft != null && this.minecraft.gui.screen() == this) {
         this.previewWidget.setViewState(state);
         this.pendingPreviewViewState = null;
      }
   }

   public EarthGeneratorSettings applyPreviewAutoAdjust(TerrainPreview.PreviewInfo info) {
      EarthGeneratorSettings current = this.buildSettings();
      if (current.experimentalIncreaseHeight()) {
         return current;
      }

      double targetWorldScale = findBestWorldScale(current, info);
      int targetHeightOffset = findBestHeightOffset(current, info, targetWorldScale);
      int targetMinSurface = scaledSurfaceY(
         info.minElevationMeters(), current.spawnLatitude(), targetWorldScale, current.terrestrialHeightScale(), current.oceanicHeightScale(),
         targetHeightOffset, current.automaticHeightScaling()
      );
      int targetMaxSurface = scaledSurfaceY(
         info.maxElevationMeters(), current.spawnLatitude(), targetWorldScale, current.terrestrialHeightScale(), current.oceanicHeightScale(),
         targetHeightOffset, current.automaticHeightScaling()
      );
      this.setSliderValue("world_scale", this.displayedWorldScale(targetWorldScale, this.isWorldScaleAtSpawnEnabled()));
      this.setSliderValue("height_offset", targetHeightOffset);
      this.setSliderValue("min_altitude", targetMinSurface - current.undergroundDepth());
      this.setSliderValue("max_altitude", targetMaxSurface + 50);

      if (this.minecraft != null && this.minecraft.gui.screen() == this) {
         this.refreshCurrentCategory();
      }

      return this.buildSettings();
   }

   public void onClose() {
      if (this.minecraft != null) {
         EarthGeneratorSettings settings = Objects.requireNonNull(this.buildSettings(), "generatorSettings");
         WorldCreationContext current = Objects.requireNonNull(this.parent.getUiState().getSettings(), "worldCreationContext");
         EarthGeneratorSettings.HeightLimits limits = Objects.requireNonNull(EarthGeneratorSettings.resolveHeightLimits(settings), "heightLimits");
         try {
            ExperimentalHeightSupport.validateOrThrow(settings, limits);
            WorldCreationContext updated = Objects.requireNonNull(updateWorldCreationContext(current, settings, limits), "updatedWorldContext");
            this.parent.getUiState().setSettings(updated);
            this.preview.close();
            this.minecraft.gui.setScreen(this.parent);
         } catch (IllegalStateException error) {
            Tellus.LOGGER.warn("Tellus world settings validation failed", error);
            this.validationError = settings.experimentalIncreaseHeight() && !ExperimentalHeightSupport.isRuntimeProfileActive()
               ? experimentalHeightValidationFailedTooltip()
               : Component.translatable("tellus.validation.world_settings_failed").withStyle(ChatFormatting.RED);
         }
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
                  overworldKey.identifier(),
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
         current.dataConfiguration(),
         current.initialWorldCreationOptions()
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

   public EarthGeneratorSettings currentGeneratorSettings() {
      return this.buildSettings();
   }

   public void applyPreloadSettings(EarthGeneratorSettings settings) {
      Objects.requireNonNull(settings, "settings");
      this.spawnLatitude = settings.spawnLatitude();
      this.spawnLongitude = settings.spawnLongitude();
      this.setToggleValue("world_scale_at_spawn", settings.worldScaleAtSpawn());
      this.setToggleValue("center_world_on_spawn", settings.centerWorldOnSpawn());
      this.setSliderValue("world_scale", this.displayedWorldScale(settings.worldScale(), settings.worldScaleAtSpawn()));
      this.setSliderValue("underground_depth", settings.undergroundDepth());
      this.setToggleValue("cave_generation", settings.caveGeneration());
      this.setToggleValue("caves_reach_surface", settings.cavesReachSurface());
      this.setToggleValue("ore_distribution", settings.oreDistribution());
      this.setToggleValue("geological_stone_patches", settings.geologicalStonePatches());
      this.setToggleValue("enable_roads", settings.enableRoads());
      this.setToggleValue("enable_buildings", settings.enableBuildings());
      this.setToggleValue("thin_shell_terrain", settings.thinShellTerrain());
      this.setToggleValue("huge_red_mushrooms", settings.hugeRedMushrooms());
      this.setToggleValue("add_strongholds", settings.addStrongholds());
      this.setToggleValue("add_villages", settings.addVillages());
      this.setToggleValue("add_mineshafts", settings.addMineshafts());
      this.setToggleValue("add_ocean_monuments", settings.addOceanMonuments());
      this.setToggleValue("add_woodland_mansions", settings.addWoodlandMansions());
      this.setToggleValue("add_desert_temples", settings.addDesertTemples());
      this.setToggleValue("add_jungle_temples", settings.addJungleTemples());
      this.setToggleValue("add_pillager_outposts", settings.addPillagerOutposts());
      this.setToggleValue("add_ruined_portals", settings.addRuinedPortals());
      this.setToggleValue("add_shipwrecks", settings.addShipwrecks());
      this.setToggleValue("add_ocean_ruins", settings.addOceanRuins());
      this.setToggleValue("add_buried_treasure", settings.addBuriedTreasure());
      this.setToggleValue("add_igloos", settings.addIgloos());
      this.setToggleValue("add_witch_huts", settings.addWitchHuts());
      this.setToggleValue("add_ancient_cities", settings.addAncientCities());
      this.setToggleValue("add_trial_chambers", settings.addTrialChambers());
      this.setToggleValue("add_trail_ruins", settings.addTrailRuins());
      this.setToggleValue("automatic_height_scaling", settings.automaticHeightScaling());
      this.setToggleValue("experimental_increase_height", settings.experimentalIncreaseHeight() && ExperimentalHeightSupport.isRuntimeProfileActive());
      this.applyPreloadHeightGuard(settings);
      this.onSettingsChanged();
   }

   private void applyPreloadHeightGuard(EarthGeneratorSettings settings) {
      if (settings.experimentalIncreaseHeight()) {
         return;
      }

      EarthGeneratorSettings.HeightLimits limits = EarthGeneratorSettings.resolveHeightLimits(settings);
      if (limits.height() >= EarthGeneratorSettings.MAX_WORLD_HEIGHT || limits.minY() <= EarthGeneratorSettings.MIN_WORLD_Y) {
         this.setToggleValue("experimental_increase_height", false);
         this.setSliderValue("min_altitude", -64.0);
         this.setSliderValue("max_altitude", 511.0);
      }
   }

   
   private EarthGeneratorSettings buildSettings() {
      boolean worldScaleAtSpawn = this.findToggleValue("world_scale_at_spawn", EarthGeneratorSettings.DEFAULT.worldScaleAtSpawn());
      boolean centerWorldOnSpawn = this.findToggleValue(
         "center_world_on_spawn", EarthGeneratorSettings.DEFAULT.centerWorldOnSpawn()
      );
      double requestedWorldScale = this.findSliderValue("world_scale", EarthGeneratorSettings.DEFAULT.worldScale());
      double worldScale = Math.min(
         EarthGeneratorSettings.MAX_WORLD_SCALE,
         EarthGeneratorSettings.equatorialWorldScale(requestedWorldScale, this.spawnLatitude, worldScaleAtSpawn)
      );
      double effectiveDisplayedScale = EarthGeneratorSettings.displayedWorldScale(
         worldScale, this.spawnLatitude, worldScaleAtSpawn
      );
      if (Math.abs(effectiveDisplayedScale - requestedWorldScale) > 1.0E-6) {
         this.setSliderValue("world_scale", effectiveDisplayedScale);
      }
      EarthGeneratorSettings.DemSelection demSelection = this.buildDemSelection();
      boolean experimentalIncreaseHeight = this.findToggleValue(
         "experimental_increase_height", EarthGeneratorSettings.DEFAULT.experimentalIncreaseHeight()
      );
      boolean automaticHeightScaling = this.findToggleValue(
         "automatic_height_scaling", EarthGeneratorSettings.DEFAULT.automaticHeightScaling()
      );
      double terrestrialScale = this.findSliderValue("terrestrial_height_scale", EarthGeneratorSettings.DEFAULT.terrestrialHeightScale());
      double oceanicScale = this.findSliderValue("oceanic_height_scale", EarthGeneratorSettings.DEFAULT.oceanicHeightScale());
      int heightOffset = (int)Math.round(this.findSliderValue("height_offset", EarthGeneratorSettings.DEFAULT.heightOffset()));
      int maxAltitude = this.resolveAltitudeSetting("max_altitude", -1.0);
      int minAltitude = this.resolveAltitudeSetting("min_altitude", -2048.0);
      if (experimentalIncreaseHeight) {
         terrestrialScale = 1.0;
         oceanicScale = 1.0;
         heightOffset = EarthGeneratorSettings.experimentalHeightOffset(automaticHeightScaling);
         minAltitude = EarthGeneratorSettings.AUTO_ALTITUDE;
         maxAltitude = EarthGeneratorSettings.AUTO_ALTITUDE;
      }

      int riverLakeShorelineBlend = EarthGeneratorSettings.DEFAULT.riverLakeShorelineBlend();
      int oceanShorelineBlend = EarthGeneratorSettings.DEFAULT.oceanShorelineBlend();
      boolean shorelineBlendCliffLimit = EarthGeneratorSettings.DEFAULT.shorelineBlendCliffLimit();
      boolean caveGeneration = this.findToggleValue("cave_generation", EarthGeneratorSettings.DEFAULT.caveGeneration());
      boolean cavesReachSurface = this.findToggleValue("caves_reach_surface", EarthGeneratorSettings.DEFAULT.cavesReachSurface());
      int undergroundDepth = (int)Math.round(
         this.findSliderValue("underground_depth", EarthGeneratorSettings.DEFAULT.undergroundDepth())
      );
      boolean oreDistribution = this.findToggleValue("ore_distribution", EarthGeneratorSettings.DEFAULT.oreDistribution());
      boolean geologicalStonePatches = this.findToggleValue("geological_stone_patches", EarthGeneratorSettings.DEFAULT.geologicalStonePatches());
      boolean lavaPools = this.findToggleValue("lava_pools", EarthGeneratorSettings.DEFAULT.lavaPools());
      boolean enableRoads = this.findToggleValue("enable_roads", EarthGeneratorSettings.DEFAULT.enableRoads());
      boolean enableBuildings = this.findToggleValue("enable_buildings", EarthGeneratorSettings.DEFAULT.enableBuildings());
      boolean climateBasedBuiltUpTerrain = this.findToggleValue("climate_based_built_up_terrain", EarthGeneratorSettings.DEFAULT.climateBasedBuiltUpTerrain());
      boolean customTrees = this.findToggleValue("custom_trees", EarthGeneratorSettings.DEFAULT.customTrees());
      boolean hugeRedMushrooms = this.findToggleValue("huge_red_mushrooms", EarthGeneratorSettings.DEFAULT.hugeRedMushrooms());
      boolean randomBiomes = this.findToggleValue("random_biomes", EarthGeneratorSettings.DEFAULT.randomBiomes());
      double randomBiomeDensity = this.findSliderValue("random_biome_density", EarthGeneratorSettings.DEFAULT.randomBiomeDensity() * 100.0) / 100.0;
      List<String> randomBiomeIds = this.selectedRandomBiomeIds();
      boolean thinShellTerrain = this.findToggleValue("thin_shell_terrain", EarthGeneratorSettings.DEFAULT.thinShellTerrain());
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
      boolean tellusManagedTerrainDownloads = this.findToggleValue(
         "tellus_managed_terrain_downloads", EarthGeneratorSettings.DEFAULT.tellusManagedTerrainDownloads()
      );
      boolean showTerrainDownloadOverlay = this.findToggleValue(
         "show_terrain_download_overlay", EarthGeneratorSettings.DEFAULT.showTerrainDownloadOverlay()
      );
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
         this.spawnLatitude,
         this.spawnLongitude,
         minAltitude,
         maxAltitude,
         riverLakeShorelineBlend,
         oceanShorelineBlend,
         shorelineBlendCliffLimit,
         caveGeneration,
         oreDistribution,
         geologicalStonePatches,
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
         true,
         thinShellTerrain,
         climateBasedBuiltUpTerrain,
         randomBiomes,
         randomBiomeDensity,
         this.randomBiomeSeed,
         randomBiomeIds,
         experimentalIncreaseHeight,
         tellusManagedTerrainDownloads,
         showTerrainDownloadOverlay,
         cavesReachSurface,
         undergroundDepth,
         customTrees,
         automaticHeightScaling,
         hugeRedMushrooms,
         worldScaleAtSpawn,
         centerWorldOnSpawn
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
      }

      this.setToggleValue("world_scale_at_spawn", initialSettings.worldScaleAtSpawn());
      this.setToggleValue("center_world_on_spawn", initialSettings.centerWorldOnSpawn());
      this.setSliderValue("world_scale", this.displayedWorldScale(initialSettings.worldScale(), initialSettings.worldScaleAtSpawn()));
      this.setSliderValue("underground_depth", initialSettings.undergroundDepth());
      this.setDemSelectionValue(initialSettings.demSelection());
      this.setSliderValue("terrestrial_height_scale", initialSettings.terrestrialHeightScale());
      this.setSliderValue("oceanic_height_scale", initialSettings.oceanicHeightScale());
      this.setSliderValue("height_offset", initialSettings.heightOffset());
      this.setSliderValue("max_altitude", initialSettings.maxAltitude() == Integer.MIN_VALUE ? -1.0 : initialSettings.maxAltitude());
      this.setSliderValue("min_altitude", initialSettings.minAltitude() == Integer.MIN_VALUE ? -2048.0 : initialSettings.minAltitude());
      this.setToggleValue("cave_generation", initialSettings.caveGeneration());
      this.setToggleValue("caves_reach_surface", initialSettings.cavesReachSurface());
      this.setToggleValue("ore_distribution", initialSettings.oreDistribution());
      this.setToggleValue("geological_stone_patches", initialSettings.geologicalStonePatches());
      this.setToggleValue("lava_pools", initialSettings.lavaPools());
      this.setToggleValue("enable_roads", initialSettings.enableRoads());
      this.setToggleValue("enable_buildings", initialSettings.enableBuildings());
      this.setToggleValue("thin_shell_terrain", initialSettings.thinShellTerrain());
      this.setToggleValue("climate_based_built_up_terrain", initialSettings.climateBasedBuiltUpTerrain());
      this.setToggleValue("custom_trees", initialSettings.customTrees());
      this.setToggleValue("huge_red_mushrooms", initialSettings.hugeRedMushrooms());
      this.setToggleValue("random_biomes", initialSettings.randomBiomes());
      this.setSliderValue("random_biome_density", initialSettings.randomBiomeDensity() * 100.0);
      this.randomBiomeSeed = initialSettings.randomBiomeSeed();
      this.setRandomBiomeSelection(initialSettings.randomBiomeIds());
      this.setToggleValue("automatic_height_scaling", initialSettings.automaticHeightScaling());
      this.setToggleValue("experimental_increase_height", initialSettings.experimentalIncreaseHeight() && ExperimentalHeightSupport.isRuntimeProfileActive());
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
      this.setToggleValue("tellus_managed_terrain_downloads", initialSettings.tellusManagedTerrainDownloads());
      this.setToggleValue("show_terrain_download_overlay", initialSettings.showTerrainDownloadOverlay());
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

   private EarthGeneratorSettings.DemSelection buildDemSelection() {
      return EarthGeneratorSettings.DemSelection.mapterhornSelection();
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      super.extractRenderState(graphics, mouseX, mouseY, delta);
      graphics.centeredText(this.font, this.title, this.width / 2, 20, 16777215);
      if (this.validationError != null) {
         int messageWidth = Math.max(120, this.width - 24);
         graphics.textWithWordWrap(this.font, this.validationError, 12, Math.max(42, this.height - 58), messageWidth, 16733525, true);
      }
   }

   private List<EarthCustomizeScreen.CategoryDefinition> createCategories() {
      List<EarthCustomizeScreen.CategoryDefinition> categories = new ArrayList<>();
      boolean distantHorizonsInstalled = TellusPlatform.isModLoaded("distanthorizons");
      boolean voxyInstalled = TellusPlatform.isModLoaded("voxy");
      List<EarthCustomizeScreen.SettingDefinition> worldSettings = new ArrayList<>(
         List.of(
            slider("world_scale", 30.0, 1.0, EarthGeneratorSettings.MAX_WORLD_SCALE, 5.0)
               .withDisplay(EarthCustomizeScreen::formatWorldScale)
               .withScale(EarthCustomizeScreen.SliderScale.power(3.0)),
            toggle("world_scale_at_spawn", EarthGeneratorSettings.DEFAULT.worldScaleAtSpawn()).onToggled(this::onWorldScaleAtSpawnToggled),
            toggle("center_world_on_spawn", EarthGeneratorSettings.DEFAULT.centerWorldOnSpawn()),
            new AutoAdjustDefinition(),
            toggle("automatic_height_scaling", EarthGeneratorSettings.DEFAULT.automaticHeightScaling()),
            toggle("experimental_increase_height", EarthGeneratorSettings.DEFAULT.experimentalIncreaseHeight())
               .withTooltip(experimentalIncreaseHeightTooltip())
               .forceDisabled(!ExperimentalHeightSupport.isRuntimeProfileActive(), experimentalHeightRuntimeDisabledTooltip()),
            slider(
               "underground_depth",
               EarthGeneratorSettings.DEFAULT.undergroundDepth(),
               EarthGeneratorSettings.MIN_UNDERGROUND_DEPTH,
               EarthGeneratorSettings.MAX_UNDERGROUND_DEPTH,
               16.0
            ).withDisplay(EarthCustomizeScreen::formatUndergroundDepth)
         )
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
            slider("max_altitude", -1.0, -1.0, 2031.0, 16.0).withDisplay(EarthCustomizeScreen::formatMaxAltitude),
            slider("min_altitude", EarthGeneratorSettings.DEFAULT.minAltitude(), -2048.0, 2031.0, 16.0).withDisplay(EarthCustomizeScreen::formatMinAltitude)
         )
      );
      categories.add(
         new EarthCustomizeScreen.CategoryDefinition("world", worldSettings)
      );
      categories.add(
         new EarthCustomizeScreen.CategoryDefinition(
            "openstreetmaps_features",
            List.of(
               toggle("enable_roads", EarthGeneratorSettings.DEFAULT.enableRoads()),
               toggle("enable_buildings", EarthGeneratorSettings.DEFAULT.enableBuildings()),
               toggle("climate_based_built_up_terrain", EarthGeneratorSettings.DEFAULT.climateBasedBuiltUpTerrain())
            )
         )
      );
      EarthCustomizeScreen.CategoryDefinition biomesCategory = new EarthCustomizeScreen.CategoryDefinition(
         "biomes", biomeSettings(RandomBiomeCatalog.minecraft26_2OverworldBiomeIds())
      ).hideFromRoot().parent("ecological");
      categories.add(
         new EarthCustomizeScreen.CategoryDefinition(
            "ecological",
            List.of(
               toggle("random_biomes", EarthGeneratorSettings.DEFAULT.randomBiomes()),
               this.categoryLink(biomesCategory),
               slider("random_biome_density", EarthGeneratorSettings.DEFAULT.randomBiomeDensity() * 100.0, 0.0, 40.0, 1.0).withDisplay(EarthCustomizeScreen::formatPercent),
               toggle("custom_trees", EarthGeneratorSettings.DEFAULT.customTrees()),
               toggle("huge_red_mushrooms", EarthGeneratorSettings.DEFAULT.hugeRedMushrooms()),
               slider("trees_density", 100.0, 0.0, 200.0, 5.0).withDisplay(EarthCustomizeScreen::formatPercent).locked(true),
               toggle("aquatic_vegetation", true).locked(true)
            )
         )
      );
      categories.add(biomesCategory);
      categories.add(
         new EarthCustomizeScreen.CategoryDefinition(
            "geological",
            List.of(
               toggle("cave_generation", EarthGeneratorSettings.DEFAULT.caveGeneration()),
               toggle("caves_reach_surface", EarthGeneratorSettings.DEFAULT.cavesReachSurface()),
               toggle("ore_distribution", EarthGeneratorSettings.DEFAULT.oreDistribution()),
               toggle("geological_stone_patches", EarthGeneratorSettings.DEFAULT.geologicalStonePatches()),
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
      categories.add(
         new EarthCustomizeScreen.CategoryDefinition(
            "network",
            List.of(
               toggle("tellus_managed_terrain_downloads", EarthGeneratorSettings.DEFAULT.tellusManagedTerrainDownloads()),
               toggle("show_terrain_download_overlay", EarthGeneratorSettings.DEFAULT.showTerrainDownloadOverlay())
            )
         ).hideFromRoot()
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
               cacheEntry(TellusCacheManager.Metric.OSM, true),
               cacheEntry(TellusCacheManager.Metric.LAND_COVER, true),
               cacheEntry(TellusCacheManager.Metric.CANOPY_HEIGHT, true),
               cacheEntry(TellusCacheManager.Metric.KOPPEN, true),
               cacheEntry(TellusCacheManager.Metric.TERRAIN, true),
               cacheEntry(TellusCacheManager.Metric.OPENWATERS, true),
               cacheEntry(TellusCacheManager.Metric.OISST, true),
               cacheEntry(TellusCacheManager.Metric.PRELOADED_TERRAIN, true),
               cacheEntry(TellusCacheManager.Metric.TOTAL, false),
               cacheActionButton(Component.translatable("tellus.cache.delete_all"), TellusCacheManager::deleteAll)
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

   private static List<EarthCustomizeScreen.SettingDefinition> biomeSettings(List<String> biomeIds) {
      List<EarthCustomizeScreen.SettingDefinition> settings = new ArrayList<>(biomeIds.size());
      for (String biomeId : biomeIds) {
         settings.add(toggle(BIOME_TOGGLE_PREFIX + biomeId, true));
      }
      return List.copyOf(settings);
   }

   private List<String> selectedRandomBiomeIds() {
      EarthCustomizeScreen.CategoryDefinition category = this.findCategoryById("biomes");
      if (category == null) {
         return EarthGeneratorSettings.DEFAULT.randomBiomeIds();
      }

      List<String> selected = new ArrayList<>();
      for (EarthCustomizeScreen.SettingDefinition setting : category.getSettings()) {
         if (setting instanceof EarthCustomizeScreen.ToggleDefinition toggle
            && toggle.value
            && toggle.key.startsWith(BIOME_TOGGLE_PREFIX)) {
            selected.add(toggle.key.substring(BIOME_TOGGLE_PREFIX.length()));
         }
      }
      return List.copyOf(selected);
   }

   private void setRandomBiomeSelection(List<String> biomeIds) {
      EarthCustomizeScreen.CategoryDefinition category = this.findCategoryById("biomes");
      if (category == null) {
         return;
      }

      for (EarthCustomizeScreen.SettingDefinition setting : category.getSettings()) {
         if (setting instanceof EarthCustomizeScreen.ToggleDefinition toggle && toggle.key.startsWith(BIOME_TOGGLE_PREFIX)) {
            String biomeId = toggle.key.substring(BIOME_TOGGLE_PREFIX.length());
            toggle.value = biomeIds.contains(biomeId);
         }
      }
   }

   private static EarthCustomizeScreen.ModeDefinition mode(String key, EarthGeneratorSettings.DistantHorizonsRenderMode defaultValue) {
      return new EarthCustomizeScreen.ModeDefinition(key, defaultValue);
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

   private static EarthCustomizeScreen.CacheEntryDefinition cacheEntry(TellusCacheManager.Metric metric, boolean allowDelete) {
      return new EarthCustomizeScreen.CacheEntryDefinition(metric, allowDelete);
   }

   private static EarthCustomizeScreen.CacheActionDefinition cacheActionButton( Component label,  Runnable action) {
      return new EarthCustomizeScreen.CacheActionDefinition(label, action);
   }

   private static List<EarthCustomizeScreen.SettingDefinition> dataSourcesEntries() {
      List<EarthCustomizeScreen.SettingDefinition> entries = new ArrayList<>();
      entries.add(infoHeader("tellus.data_sources.overture.title"));
      entries.add(infoLine("tellus.data_sources.overture.description"));
      entries.add(infoLine("tellus.data_sources.overture.copyright"));
      entries.add(infoLine("tellus.data_sources.overture.sentinel"));
      entries.add(infoSubtle("tellus.data_sources.overture.licenses"));
      entries.add(infoLink("https://docs.overturemaps.org/attribution/"));
      entries.add(infoLink("https://docs.overturemaps.org/schema/reference/base/land_cover/"));
      entries.add(infoLine("tellus.data_sources.overture.processing_1"));
      entries.add(infoLine("tellus.data_sources.overture.processing_2"));
      entries.add(infoSpacer());
      entries.add(infoHeader("tellus.data_sources.canopy_height.title"));
      entries.add(infoLine("tellus.data_sources.canopy_height.source"));
      entries.add(infoLine("tellus.data_sources.canopy_height.publication"));
      entries.add(infoSubtle("tellus.data_sources.license_cc_by"));
      entries.add(infoLink("https://doi.org/10.3929/ethz-b-000609802"));
      entries.add(infoLink("https://www.arcgis.com/home/item.html?id=2a3dfb00c2c6425f85bd70da420d58eb"));
      entries.add(infoLine("tellus.data_sources.canopy_height.processing_1"));
      entries.add(infoLine("tellus.data_sources.canopy_height.processing_2"));
      entries.add(infoSpacer());
      entries.add(infoHeader("tellus.data_sources.koppen.title"));
      entries.add(infoLine("tellus.data_sources.koppen.source"));
      entries.add(infoLine("tellus.data_sources.koppen.publication_1"));
      entries.add(infoLine("tellus.data_sources.koppen.publication_2"));
      entries.add(infoSubtle("tellus.data_sources.license_cc_by"));
      entries.add(infoLink("https://creativecommons.org/licenses/by/4.0/"));
      entries.add(infoSubtle("tellus.data_sources.publication_doi"));
      entries.add(infoLink("https://doi.org/10.1038/sdata.2018.214"));
      entries.add(infoLine("tellus.data_sources.koppen.processing_1"));
      entries.add(infoLine("tellus.data_sources.koppen.processing_2"));
      entries.add(infoSpacer());
      entries.add(infoHeader("tellus.data_sources.mapterhorn.title"));
      entries.add(infoLine("tellus.data_sources.mapterhorn.source"));
      entries.add(infoLine("tellus.data_sources.accessed", formatLocalDate()));
      entries.add(infoLink("https://mapterhorn.com/"));
      entries.add(infoLine("tellus.data_sources.mapterhorn.processing_1"));
      entries.add(infoLine("tellus.data_sources.mapterhorn.processing_2"));
      entries.add(infoSpacer());
      entries.add(infoHeader("tellus.data_sources.openwaters.title"));
      entries.add(infoLine("tellus.data_sources.openwaters.source"));
      entries.add(infoLine("tellus.data_sources.accessed", formatLocalDate()));
      entries.add(infoLink("https://github.com/openwatersio/openwaters.io"));
      entries.add(infoLine("tellus.data_sources.openwaters.processing_1"));
      entries.add(infoLine("tellus.data_sources.openwaters.processing_2"));
      entries.add(infoSpacer());
      entries.add(infoHeader("tellus.data_sources.openmeteo.title"));
      entries.add(infoLine("tellus.data_sources.openmeteo.data"));
      entries.add(infoLink("https://open-meteo.com/"));
      entries.add(infoSubtle("tellus.data_sources.license_cc_by"));
      entries.add(infoLink("https://creativecommons.org/licenses/by/4.0/"));
      entries.add(infoLine("tellus.data_sources.openmeteo.credit"));
      entries.add(infoLink("https://doi.org/10.5281/ZENODO.7970649"));
      return entries;
   }

   private static EarthCustomizeScreen.TextLineDefinition infoHeader(String translationKey, Object... args) {
      return new EarthCustomizeScreen.TextLineDefinition(Component.translatable(translationKey, args), -604044, null);
   }

   private static EarthCustomizeScreen.TextLineDefinition infoLine(String translationKey, Object... args) {
      return new EarthCustomizeScreen.TextLineDefinition(Component.translatable(translationKey, args), -1710619, null);
   }

   private static EarthCustomizeScreen.TextLineDefinition infoSubtle(String translationKey, Object... args) {
      return new EarthCustomizeScreen.TextLineDefinition(Component.translatable(translationKey, args), -4605511, null);
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
      return Component.translatable("tellus.value.blocks", String.format(Locale.ROOT, "%.0f", value)).getString();
   }

   private static String formatUndergroundDepth(double value) {
      return Component.translatable("tellus.value.blocks", String.format(Locale.ROOT, "-%.0f", value)).getString();
   }

   private static String formatPercent(double value) {
      return String.format(Locale.ROOT, "%.0f%%", value);
   }

   private static String formatChunkRadius(double value) {
      return Component.translatable("tellus.value.chunks", String.format(Locale.ROOT, "%.0f", value)).getString();
   }

   private static String formatChunksPerTick(double value) {
      return Component.translatable("tellus.value.chunks_per_tick", String.format(Locale.ROOT, "%.0f", value)).getString();
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

   

   private static String formatAltitude(double value, double autoValue) {
      return value <= autoValue + 0.5
         ? Component.translatable("tellus.value.automatic").getString()
         : Component.translatable("tellus.value.blocks", String.format(Locale.ROOT, "%.0f", value)).getString();
   }

   private static double findBestWorldScale(EarthGeneratorSettings settings, TerrainPreview.PreviewInfo info) {
      for (int step = 10; step <= (int)Math.round(EarthGeneratorSettings.MAX_WORLD_SCALE * 10.0); step++) {
         double worldScale = step / 10.0;
         if (canFitPreviewAtWorldScale(settings, info, worldScale)) {
            return worldScale;
         }
      }

      return EarthGeneratorSettings.MAX_WORLD_SCALE;
   }

   private static boolean canFitPreviewAtWorldScale(EarthGeneratorSettings settings, TerrainPreview.PreviewInfo info, double worldScale) {
      int minBase = scaledSurfaceY(
         info.minElevationMeters(), settings.spawnLatitude(), worldScale, settings.terrestrialHeightScale(), settings.oceanicHeightScale(),
         0, settings.automaticHeightScaling()
      );
      int maxBase = scaledSurfaceY(
         info.maxElevationMeters(), settings.spawnLatitude(), worldScale, settings.terrestrialHeightScale(), settings.oceanicHeightScale(),
         0, settings.automaticHeightScaling()
      );
      int minOffset = Math.max(EarthGeneratorSettings.MIN_WORLD_Y + settings.undergroundDepth() - minBase, -2000);
      int maxOffset = Math.min(EarthGeneratorSettings.MAX_WORLD_Y - 50 - maxBase, 128);
      return minOffset <= maxOffset;
   }

   private static int findBestHeightOffset(EarthGeneratorSettings settings, TerrainPreview.PreviewInfo info, double worldScale) {
      int minBase = scaledSurfaceY(
         info.minElevationMeters(), settings.spawnLatitude(), worldScale, settings.terrestrialHeightScale(), settings.oceanicHeightScale(),
         0, settings.automaticHeightScaling()
      );
      int maxBase = scaledSurfaceY(
         info.maxElevationMeters(), settings.spawnLatitude(), worldScale, settings.terrestrialHeightScale(), settings.oceanicHeightScale(),
         0, settings.automaticHeightScaling()
      );
      int minOffset = Math.max(EarthGeneratorSettings.MIN_WORLD_Y + settings.undergroundDepth() - minBase, -2000);
      int maxOffset = Math.min(EarthGeneratorSettings.MAX_WORLD_Y - 50 - maxBase, 128);
      if (minOffset > maxOffset) {
         return Mth.clamp(settings.heightOffset(), -2000, 128);
      } else {
         return Mth.clamp((int)Math.round((minOffset + maxOffset) * 0.5), minOffset, maxOffset);
      }
   }

   private static int scaledSurfaceY(
      double elevation, double latitude, double worldScale, double terrestrialScale, double oceanicScale, int heightOffset,
      boolean automaticHeightScaling
   ) {
      double scale = elevation >= 0.0 ? terrestrialScale : oceanicScale;
      double correction = automaticHeightScaling ? EarthProjection.heightScaleCorrectionAtLatitude(latitude) : 1.0;
      double scaled = elevation * scale * correction / worldScale;
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
      if (key.startsWith(BIOME_TOGGLE_PREFIX)) {
         return Objects.requireNonNull(
            Component.translatable("biome.minecraft." + key.substring(BIOME_TOGGLE_PREFIX.length())), "biomeName"
         );
      }
      return Objects.requireNonNull(Component.translatable("property.tellus." + key + ".name"), "settingName");
   }

   
   private static Component settingTooltip(String key) {
      if (key.startsWith(BIOME_TOGGLE_PREFIX)) {
         return Objects.requireNonNull(Component.translatable("property.tellus.biomes.tooltip").withStyle(ChatFormatting.GRAY), "biomeTooltip");
      }
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

   private static Component experimentalIncreaseHeightTooltip() {
      return Objects.requireNonNull(
         Component.translatable("property.tellus.experimental_increase_height.tooltip")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal("\n"))
            .append(Component.translatable("property.tellus.experimental_increase_height.warning").withStyle(ChatFormatting.RED)),
         "experimentalIncreaseHeightTooltip"
      );
   }

   private static Component experimentalHeightControlsDisabledTooltip() {
      return Objects.requireNonNull(
         Component.translatable("tellus.experimental_increase_height.controls_disabled.tooltip").withStyle(ChatFormatting.GRAY),
         "experimentalHeightControlsDisabledTooltip"
      );
   }

   private static Component experimentalHeightRuntimeDisabledTooltip() {
      return Objects.requireNonNull(
         Component.translatable("tellus.experimental_increase_height.runtime_disabled.tooltip", ExperimentalHeightSupport.launchPropertyInstruction())
            .withStyle(ChatFormatting.GRAY),
         "experimentalHeightRuntimeDisabledTooltip"
      );
   }

   private static Component experimentalHeightValidationFailedTooltip() {
      return Objects.requireNonNull(
         Component.translatable("tellus.experimental_increase_height.validation_failed", ExperimentalHeightSupport.launchPropertyInstruction())
            .withStyle(ChatFormatting.RED),
         "experimentalHeightValidationFailedTooltip"
      );
   }

   private static String describeDimensionType(DimensionType type) {
      return "minY=" + type.minY() + ",height=" + type.height() + ",logicalHeight=" + type.logicalHeight();
   }

   private int resolveAltitudeSetting(String key, double autoValue) {
      double value = this.findSliderValue(key, autoValue);
      return value <= autoValue + 0.5 ? Integer.MIN_VALUE : (int)Math.round(value);
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
            Registry<DimensionType> source = layerAccess.lookupOrThrow(Registries.DIMENSION_TYPE);
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
         TellusCacheManager.requestRefresh();
         this.list.addWidget(new EarthCustomizeScreen.CacheProgressWidget());
      }

      if ("world".equals(category.getId())) {
         this.list.addWidget(this.createWorldHeaderActions(category));
      }

      if ("structure".equals(category.getId()) || "biomes".equals(category.getId())) {
         this.list.addWidget(this.createStructureHeaderActions(category));
      }

      Runnable onChange = this::onSettingsChanged;
      if ("world".equals(category.getId())
         || "geological".equals(category.getId())
         || "distant_horizons".equals(category.getId())
         || "voxy".equals(category.getId())) {
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
      boolean bothCompatibilityModsInstalled = TellusPlatform.isModLoaded("distanthorizons") && TellusPlatform.isModLoaded("voxy");
      boolean voxyEnabled = bothCompatibilityModsInstalled
         && this.findToggleValue("voxy_chunk_pregen_enabled", EarthGeneratorSettings.DEFAULT.voxyChunkPregenEnabled());
      if ("world".equals(category.getId())) {
         boolean experimentalIncreaseHeight = this.isExperimentalIncreaseHeightEnabled();
         Component tooltip = experimentalHeightControlsDisabledTooltip();
         for (EarthCustomizeScreen.SettingDefinition setting : category.getSettings()) {
            if (setting instanceof EarthCustomizeScreen.SliderDefinition slider && isExperimentalAltitudeControl(slider.key)) {
               slider.forceDisabled(experimentalIncreaseHeight, tooltip);
            }
         }
      } else if ("geological".equals(category.getId())) {
         EarthCustomizeScreen.ToggleDefinition caveGeneration = null;
         EarthCustomizeScreen.ToggleDefinition cavesReachSurface = null;
         for (EarthCustomizeScreen.SettingDefinition setting : category.getSettings()) {
            if (setting instanceof EarthCustomizeScreen.ToggleDefinition toggle && toggle.key.equals("cave_generation")) {
               caveGeneration = toggle;
            } else if (setting instanceof EarthCustomizeScreen.ToggleDefinition toggle && toggle.key.equals("caves_reach_surface")) {
               cavesReachSurface = toggle;
            }
         }
         if (cavesReachSurface != null) {
            cavesReachSurface.forceDisabled(caveGeneration == null || !caveGeneration.value);
         }
      } else if ("network".equals(category.getId())) {
         EarthCustomizeScreen.ToggleDefinition managedDownloads = null;
         EarthCustomizeScreen.ToggleDefinition overlay = null;
         for (EarthCustomizeScreen.SettingDefinition setting : category.getSettings()) {
            if (setting instanceof EarthCustomizeScreen.ToggleDefinition toggle && toggle.key.equals("tellus_managed_terrain_downloads")) {
               managedDownloads = toggle;
            } else if (setting instanceof EarthCustomizeScreen.ToggleDefinition toggle && toggle.key.equals("show_terrain_download_overlay")) {
               overlay = toggle;
            }
         }
         if (managedDownloads != null && overlay != null) {
            overlay.forceDisabled(!managedDownloads.value);
         }
      } else if ("openstreetmaps_features".equals(category.getId())) {
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

   private boolean isExperimentalIncreaseHeightEnabled() {
      return this.findToggleValue("experimental_increase_height", EarthGeneratorSettings.DEFAULT.experimentalIncreaseHeight());
   }

   private static boolean isExperimentalAltitudeControl(String key) {
      return "terrestrial_height_scale".equals(key)
         || "oceanic_height_scale".equals(key)
         || "height_offset".equals(key)
         || "min_altitude".equals(key)
         || "max_altitude".equals(key);
   }

   private boolean roadsAndBuildingsSupportedForSelectedScale() {
      return roadsAndBuildingsSupportedForWorldScale(this.selectedEquatorialWorldScale());
   }

   private boolean isWorldScaleAtSpawnEnabled() {
      return this.findToggleValue("world_scale_at_spawn", EarthGeneratorSettings.DEFAULT.worldScaleAtSpawn());
   }

   /** The stored (equatorial) world scale implied by the current slider value and presentation mode. */
   private double selectedEquatorialWorldScale() {
      return EarthGeneratorSettings.equatorialWorldScale(
         this.findSliderValue("world_scale", EarthGeneratorSettings.DEFAULT.worldScale()), this.spawnLatitude, this.isWorldScaleAtSpawnEnabled()
      );
   }

   private double displayedWorldScale(double equatorialWorldScale, boolean worldScaleAtSpawn) {
      return EarthGeneratorSettings.displayedWorldScale(equatorialWorldScale, this.spawnLatitude, worldScaleAtSpawn);
   }

   /**
    * Re-expresses the World Scale slider when its presentation mode changes so the stored equatorial
    * scale, and therefore the generated world, stays the same.
    */
   private void onWorldScaleAtSpawnToggled(boolean worldScaleAtSpawn) {
      double sliderValue = this.findSliderValue("world_scale", EarthGeneratorSettings.DEFAULT.worldScale());
      double equatorialWorldScale = EarthGeneratorSettings.equatorialWorldScale(sliderValue, this.spawnLatitude, !worldScaleAtSpawn);
      this.setSliderValue("world_scale", this.displayedWorldScale(equatorialWorldScale, worldScaleAtSpawn));
   }

   private static boolean roadsAndBuildingsSupportedForWorldScale(double worldScale) {
      return worldScale > 0.0 && worldScale <= OSM_ROADS_AND_BUILDINGS_MAX_WORLD_SCALE;
   }

   private static boolean isPreviewHiddenCategory(String id) {
      return "cache".equals(id) || "data_sources".equals(id);
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
      return InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), 340) || InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), 344);
   }

   private final class AutoAdjustDefinition implements EarthCustomizeScreen.SettingDefinition {
      @Override
      public AbstractWidget createWidget(Runnable onChange) {
         boolean disabled = EarthCustomizeScreen.this.isExperimentalIncreaseHeightEnabled();
         Component tooltip = disabled
            ? experimentalHeightControlsDisabledTooltip()
            : Component.translatable("property.tellus.auto_adjust.tooltip").withStyle(ChatFormatting.GRAY);
         Button button = Button.builder(Component.translatable("tellus.preview.info.auto_adjust"), clicked -> {
            TerrainPreview.PreviewInfo info = EarthCustomizeScreen.this.preview.getInfo();
            if (info != null) {
               EarthGeneratorSettings adjusted = EarthCustomizeScreen.this.applyPreviewAutoAdjust(info);
               EarthCustomizeScreen.this.onSettingsChanged();
               if (EarthCustomizeScreen.this.previewWidget != null) {
                  EarthCustomizeScreen.this.previewWidget.requestRebuild(adjusted);
               }
            }
         }).bounds(0, 0, 0, 20).build();
         button.active = !disabled;
         button.setTooltip(Tooltip.create(tooltip));
         return button;
      }
   }

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

   private static final class CacheActionWidget extends AbstractWidget {
      private final Button button;

      private CacheActionWidget( Component label,  Runnable action) {
         super(0, 0, 0, 20, Component.empty());
         Component safeLabel = Objects.requireNonNull(label, "label");
         Runnable safeAction = Objects.requireNonNull(action, "action");
         this.button = Button.builder(safeLabel, btn -> safeAction.run()).bounds(0, 0, 0, 20).build();
      }

      protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
         TellusCacheManager.Snapshot snapshot = TellusCacheManager.snapshot();
         this.button.active = snapshot.ready() && snapshot.totalFiles() > 0L;
         this.button.setX(this.getX());
         this.button.setY(this.getY());
         this.button.setWidth(this.width);
         this.button.setHeight(this.height);
         this.button.extractRenderState(graphics, mouseX, mouseY, delta);
      }

      public void onClick(MouseButtonEvent event, boolean doubleClick) {
         this.button.mouseClicked(event, doubleClick);
      }

      protected void onDrag( MouseButtonEvent event, double deltaX, double deltaY) {
         this.button.mouseDragged(event, deltaX, deltaY);
      }

      public void onRelease( MouseButtonEvent event) {
         this.button.mouseReleased(event);
      }

      protected void updateWidgetNarration( NarrationElementOutput narration) {
      }
   }

   private static final class CacheProgressWidget extends AbstractWidget {
      private static final Component CANCEL_LABEL = Objects.requireNonNull(Component.translatable("gui.cancel"), "cancelLabel");
      private static final int TEXT_COLOR = 0xFFFFFFFF;
      private static final int READY_TEXT_COLOR = 0xFF80C080;
      private static final int FAILED_TEXT_COLOR = 0xFFFF5555;
      private static final int BAR_BACKGROUND = 0xFF101010;
      private static final int BAR_BORDER = 0xFF6F6F6F;
      private static final int CALCULATING_FILL = 0xFF4C9BE8;
      private static final int DELETING_FILL = 0xFFE0A030;
      private final Button cancelButton;

      private CacheProgressWidget() {
         super(0, 0, 0, 20, Component.empty());
         this.cancelButton = Button.builder(CANCEL_LABEL, button -> TellusCacheManager.cancelDeletion()).bounds(0, 0, 0, 20).build();
      }

      @Override
      protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
         Font font = Minecraft.getInstance().font;
         TellusCacheManager.Snapshot snapshot = TellusCacheManager.snapshot();
         if (snapshot.status() == TellusCacheManager.Status.READY) {
            graphics.centeredText(
               font,
               Component.translatable("tellus.cache.ready"),
               this.getX() + this.width / 2,
               this.getY() + (this.height - 9) / 2,
               READY_TEXT_COLOR
            );
            return;
         }
         if (snapshot.status() == TellusCacheManager.Status.FAILED) {
            graphics.centeredText(
               font,
               Component.translatable("tellus.cache.failed"),
               this.getX() + this.width / 2,
               this.getY() + (this.height - 9) / 2,
               FAILED_TEXT_COLOR
            );
            return;
         }

         boolean deleting = snapshot.status() == TellusCacheManager.Status.DELETING;
         int contentLeft = this.getX() + 4;
         int contentRight = this.getX() + this.width - 4;
         if (deleting) {
            int cancelWidth = Math.max(58, font.width(CANCEL_LABEL) + 12);
            int cancelX = this.getX() + this.width - cancelWidth;
            this.cancelButton.setX(cancelX);
            this.cancelButton.setY(this.getY());
            this.cancelButton.setWidth(cancelWidth);
            this.cancelButton.setHeight(this.height);
            this.cancelButton.active = !snapshot.cancelling();
            this.cancelButton.extractRenderState(graphics, mouseX, mouseY, delta);
            contentRight = cancelX - 4;
         }

         String label = Component.translatable(
            deleting
               ? snapshot.cancelling() ? "tellus.cache.cancelling" : "tellus.cache.deleting"
               : "tellus.cache.calculating"
         ).getString();
         String percent = String.format(Locale.ROOT, "%.0f%%", snapshot.progress() * 100.0);
         int percentWidth = font.width(percent);
         int percentX = Math.max(contentLeft, contentRight - percentWidth);
         int labelWidth = Math.max(0, percentX - contentLeft - 4);
         graphics.text(font, fitCacheProgressText(font, label, labelWidth), contentLeft, this.getY() + 1, TEXT_COLOR, false);
         graphics.text(font, percent, percentX, this.getY() + 1, TEXT_COLOR, false);

         int barX = contentLeft;
         int barY = this.getY() + this.height - 5;
         int barWidth = Math.max(0, contentRight - contentLeft);
         if (barWidth > 1) {
            graphics.fill(barX, barY, barX + barWidth, barY + 4, BAR_BACKGROUND);
            graphics.outline(barX, barY, barWidth, 4, BAR_BORDER);
            int fillWidth = (int)Math.round(Math.max(0, barWidth - 2) * snapshot.progress());
            if (fillWidth > 0) {
               graphics.fill(barX + 1, barY + 1, barX + 1 + fillWidth, barY + 3, deleting ? DELETING_FILL : CALCULATING_FILL);
            }
         }
      }

      @Override
      public void onClick(MouseButtonEvent event, boolean doubleClick) {
         if (TellusCacheManager.snapshot().status() == TellusCacheManager.Status.DELETING) {
            this.cancelButton.mouseClicked(event, doubleClick);
         }
      }

      @Override
      protected void onDrag(MouseButtonEvent event, double deltaX, double deltaY) {
         if (TellusCacheManager.snapshot().status() == TellusCacheManager.Status.DELETING) {
            this.cancelButton.mouseDragged(event, deltaX, deltaY);
         }
      }

      @Override
      public void onRelease(MouseButtonEvent event) {
         if (TellusCacheManager.snapshot().status() == TellusCacheManager.Status.DELETING) {
            this.cancelButton.mouseReleased(event);
         }
      }

      @Override
      protected void updateWidgetNarration(NarrationElementOutput narration) {
      }

      private static String fitCacheProgressText(Font font, String text, int maxWidth) {
         if (maxWidth <= 0) {
            return "";
         }
         if (font.width(text) <= maxWidth) {
            return text;
         }

         String suffix = "...";
         int end = text.length();
         while (end > 0 && font.width(text.substring(0, end) + suffix) > maxWidth) {
            end--;
         }
         return end == 0 ? "" : text.substring(0, end) + suffix;
      }
   }

   private static final class CacheEntryDefinition implements EarthCustomizeScreen.SettingDefinition {
      private final TellusCacheManager.Metric metric;
      private final boolean allowDelete;

      private CacheEntryDefinition(TellusCacheManager.Metric metric, boolean allowDelete) {
         this.metric = Objects.requireNonNull(metric, "metric");
         this.allowDelete = allowDelete;
      }

      @Override
      public AbstractWidget createWidget(Runnable onChange) {
         return new EarthCustomizeScreen.CacheEntryWidget(this.metric, this.allowDelete);
      }
   }

   private static final class CacheEntryWidget extends AbstractWidget {
      
      private static final Component DELETE_LABEL = Objects.requireNonNull(Component.translatable("tellus.cache.delete"), "deleteLabel");
      private final TellusCacheManager.Metric metric;
      private final Button deleteButton;
      private final boolean allowDelete;

      private CacheEntryWidget(TellusCacheManager.Metric metric, boolean allowDelete) {
         super(0, 0, 0, 20, Component.empty());
         this.metric = Objects.requireNonNull(metric, "metric");
         this.allowDelete = allowDelete;
         this.deleteButton = Button.builder(DELETE_LABEL, btn -> TellusCacheManager.delete(metric)).bounds(0, 0, 0, 20).build();
      }

      protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
         Font font = Minecraft.getInstance().font;
         TellusCacheManager.Snapshot snapshot = TellusCacheManager.snapshot();
         boolean ready = snapshot.ready();
         long bytes = snapshot.bytesFor(this.metric);
         String sizeText = snapshot.hasUsageFor(this.metric) ? EarthCustomizeScreen.formatBytes(bytes) : "...";
         int buttonWidth = Math.max(96, font.width(DELETE_LABEL) + 12);
         int buttonX = this.getX() + this.width - buttonWidth;
         int buttonY = this.getY();
         int sizeWidth = font.width(sizeText);
         int sizeX = buttonX - 4 - sizeWidth;
         int labelX = this.getX() + 4;
         int textY = this.getY() + (this.height - 9) / 2;
         Component label = Component.translatable(this.metric.labelKey());
         graphics.text(font, label, labelX, textY, -1, false);
         graphics.text(font, sizeText, sizeX, textY, -6250336, false);
         if (this.allowDelete) {
            this.deleteButton.setX(buttonX);
            this.deleteButton.setY(buttonY);
            this.deleteButton.setWidth(buttonWidth);
            this.deleteButton.setHeight(this.height);
            this.deleteButton.active = ready && snapshot.filesFor(this.metric) > 0L;
            this.deleteButton.extractRenderState(graphics, mouseX, mouseY, delta);
         }
      }

      public void onClick( MouseButtonEvent event, boolean doubleClick) {
         if (this.allowDelete) {
            this.deleteButton.mouseClicked(event, doubleClick);
         }
      }

      protected void onDrag( MouseButtonEvent event, double deltaX, double deltaY) {
         if (this.allowDelete) {
            this.deleteButton.mouseDragged(event, deltaX, deltaY);
         }
      }

      public void onRelease( MouseButtonEvent event) {
         if (this.allowDelete) {
            this.deleteButton.mouseReleased(event);
         }
      }

      protected void updateWidgetNarration( NarrationElementOutput narration) {
      }
   }

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

   private final class CategoryLinkDefinition implements EarthCustomizeScreen.SettingDefinition {
      
      private final EarthCustomizeScreen.CategoryDefinition targetCategory;
      private boolean active = true;
      
      private Component tooltip;

      private CategoryLinkDefinition( EarthCustomizeScreen.CategoryDefinition targetCategory) {
         this.targetCategory = Objects.requireNonNull(targetCategory, "targetCategory");
      }

      private EarthCustomizeScreen.CategoryLinkDefinition active(boolean active) {
         this.active = active;
         return this;
      }

      private EarthCustomizeScreen.CategoryLinkDefinition withTooltip( Component tooltip) {
         this.tooltip = tooltip;
         return this;
      }

      @Override
      public AbstractWidget createWidget(Runnable onChange) {
         Component label = this.targetCategory.getLabel();
         Button button = Button.builder(label, btn -> EarthCustomizeScreen.this.showCategory(this.targetCategory)).bounds(0, 0, 0, 20).build();
         button.active = this.active;
         if (this.tooltip != null) {
            button.setTooltip(Tooltip.create(this.tooltip));
         }

         return button;
      }
   }

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

      protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
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
         this.leftButton.extractRenderState(graphics, mouseX, mouseY, delta);
         this.rightButton.extractRenderState(graphics, mouseX, mouseY, delta);
      }

      public boolean mouseClicked( MouseButtonEvent event, boolean isPrimary) {
         boolean leftClicked = this.leftButton.mouseClicked(event, isPrimary);
         boolean rightClicked = this.rightButton.mouseClicked(event, isPrimary);
         return leftClicked || rightClicked;
      }

      protected void onDrag( MouseButtonEvent event, double deltaX, double deltaY) {
         this.leftButton.mouseDragged(event, deltaX, deltaY);
         this.rightButton.mouseDragged(event, deltaX, deltaY);
      }

      public void onRelease( MouseButtonEvent event) {
         this.leftButton.mouseReleased(event);
         this.rightButton.mouseReleased(event);
      }

      protected void updateWidgetNarration( NarrationElementOutput narration) {
      }
   }

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
         Builder<EarthGeneratorSettings.DistantHorizonsRenderMode> builder = CycleButton.builder(EarthCustomizeScreen::formatRenderMode, this.value)
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

   private record RegistryUpdate(LayeredRegistryAccess<RegistryLayer> registries, Holder<DimensionType> holder) {
   }

   private interface SettingDefinition {
      AbstractWidget createWidget(Runnable var1);
   }

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
      private Component forceDisabledTooltip;

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
         if (!forceDisabled) {
            this.forceDisabledTooltip = null;
         }

         return this;
      }

      private EarthCustomizeScreen.SliderDefinition forceDisabled(boolean forceDisabled, Component tooltip) {
         this.forceDisabled = forceDisabled;
         this.forceDisabledTooltip = forceDisabled ? Objects.requireNonNull(tooltip, "forceDisabledTooltip") : null;
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
            : (this.forceDisabled && this.forceDisabledTooltip != null
               ? this.forceDisabledTooltip
               : (this.locked ? EarthCustomizeScreen.workInProgressTooltip(this.key) : EarthCustomizeScreen.settingTooltip(this.key)));
         slider.setTooltip(Tooltip.create(tooltip));
         slider.active = !this.locked && !this.forceDisabled && !this.unavailable;
         return slider;
      }
   }

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

   private static final class SpacerDefinition implements EarthCustomizeScreen.SettingDefinition {
      @Override
      public AbstractWidget createWidget(Runnable onChange) {
         return new EarthCustomizeScreen.SpacerWidget();
      }
   }

   private static final class SpacerWidget extends AbstractWidget {
      private SpacerWidget() {
         super(0, 0, 0, 20, Component.empty());
      }

      protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      }

      protected void updateWidgetNarration( NarrationElementOutput narration) {
      }
   }

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

      protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
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
            graphics.pose().pushMatrix();
            graphics.pose().translate(textX, textY);
            graphics.pose().scale(scale, scale);
            graphics.text(font, this.text, 0, 0, drawColor, true);
            if (this.url != null) {
               int underlineY = 9;
               graphics.fill(0, underlineY, textWidth, underlineY + 1, drawColor);
            }

            graphics.pose().popMatrix();
         }
      }

      public boolean mouseClicked( MouseButtonEvent event, boolean isPrimary) {
         String url = this.url;
         if (url != null && event.button() == 0 && this.isMouseOver(event.x(), event.y())) {
            Util.getPlatform().openUri(url);
            return true;
         } else {
            return false;
         }
      }

      protected void updateWidgetNarration( NarrationElementOutput narration) {
      }
   }

   private static final class ToggleDefinition implements EarthCustomizeScreen.SettingDefinition {
      private final String key;
      private boolean value;
      private boolean locked;
      private boolean forceDisabled;
      private boolean unavailable;
      
      private Component unavailableTooltip;
      
      private Component forceDisabledTooltip;
      private Component tooltipOverride;
      private Consumer<Boolean> onToggled;

      private ToggleDefinition(String key, boolean defaultValue) {
         this.key = key;
         this.value = defaultValue;
      }

      private EarthCustomizeScreen.ToggleDefinition onToggled(Consumer<Boolean> onToggled) {
         this.onToggled = Objects.requireNonNull(onToggled, "onToggled");
         return this;
      }

      private EarthCustomizeScreen.ToggleDefinition locked(boolean locked) {
         this.locked = locked;
         return this;
      }

      private EarthCustomizeScreen.ToggleDefinition withTooltip(Component tooltip) {
         this.tooltipOverride = Objects.requireNonNull(tooltip, "tooltipOverride");
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
               : (
                  this.tooltipOverride != null
                     ? this.tooltipOverride
                     : (this.locked ? EarthCustomizeScreen.workInProgressTooltip(this.key) : EarthCustomizeScreen.settingTooltip(this.key))
               ));
         Builder<Boolean> builder = CycleButton.booleanBuilder(EarthCustomizeScreen.YES, EarthCustomizeScreen.NO, this.value)
            .withTooltip(value -> Tooltip.create(tooltip));
         CycleButton<Boolean> button = builder.create(0, 0, 0, 20, name, (btn, value) -> {
            this.value = value;
            if (this.onToggled != null) {
               this.onToggled.accept(value);
            }
            onChange.run();
         });
         button.active = !this.locked && !this.forceDisabled && !this.unavailable;
         return button;
      }
   }
}
