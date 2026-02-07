package com.yucareux.tellus.client.screen;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.client.preview.TerrainPreview;
import com.yucareux.tellus.client.preview.TerrainPreviewWidget;
import com.yucareux.tellus.client.preview.TerrainPreviewWidget.ViewState;
import com.yucareux.tellus.client.widget.CustomizationList;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.mojang.serialization.Lifecycle;
import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.function.DoubleFunction;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.ChatFormatting;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.lwjgl.glfw.GLFW;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class EarthCustomizeScreen extends Screen {
	private static final @NonNull Component TITLE = Objects.requireNonNull(
			Component.translatable("options.tellus.customize_world_title.name"),
			"customizeTitle");
	private static final @NonNull Component YES = Objects.requireNonNull(
			Component.translatable("gui.yes").withStyle(ChatFormatting.GREEN),
			"yesLabel");
	private static final @NonNull Component NO = Objects.requireNonNull(
			Component.translatable("gui.no").withStyle(ChatFormatting.RED),
			"noLabel");
	private static final @NonNull Component WORK_IN_PROGRESS = Objects.requireNonNull(
			Component.translatable("tellus.customize.work_in_progress").withStyle(ChatFormatting.GRAY),
			"workInProgressLabel");

	private static final int ENTRY_HEIGHT = 20;
	private static final int LIST_TOP = 40;
	private static final int LIST_BOTTOM_PADDING = 36;
	private static final int SIDE_PADDING = 10;
	private static final int PREVIEW_PADDING = 20;
	private static final long PREVIEW_DEBOUNCE_MS = 350L;
	private static final int INFO_TITLE_COLOR = 0xFFF6C874;
	private static final int INFO_TEXT_COLOR = 0xFFE5E5E5;
	private static final int INFO_SUBTLE_COLOR = 0xFFB9B9B9;
	private static final int INFO_LINK_COLOR = 0xFF55FFFF;
	private static final int INFO_LINK_HOVER_COLOR = 0xFFAAFFFF;
	private static final double AUTO_MAX_ALTITUDE = -1.0;
	private static final double AUTO_MIN_ALTITUDE = EarthGeneratorSettings.MIN_WORLD_Y - 16.0;
	private static final double ALTITUDE_AUTO_EPSILON = 0.5;
	private static final double AUTO_SEA_LEVEL = -64.0;
	private static final double SEA_LEVEL_AUTO_EPSILON = 0.5;
	private static final @NonNull Identifier DYNAMIC_DIMENSION_TYPE_ID = Objects
			.requireNonNull(Identifier.fromNamespaceAndPath("tellus", "earth_dynamic"), "dynamicDimensionTypeId");
	private static final @NonNull ResourceKey<DimensionType> DYNAMIC_DIMENSION_TYPE_KEY = Objects.requireNonNull(
			ResourceKey.create(Registries.DIMENSION_TYPE, DYNAMIC_DIMENSION_TYPE_ID), "dynamicDimensionTypeKey");

	private final CreateWorldScreen parent;
	private final List<CategoryDefinition> categories;

	private CustomizationList list;
	private final @NonNull TerrainPreview preview = new TerrainPreview();
	private TerrainPreviewWidget previewWidget;
	private @Nullable ViewState pendingPreviewViewState;
	private long previewDirtyAt = -1L;
	private double spawnLatitude = EarthGeneratorSettings.DEFAULT_SPAWN_LATITUDE;
	private double spawnLongitude = EarthGeneratorSettings.DEFAULT_SPAWN_LONGITUDE;

	public EarthCustomizeScreen(CreateWorldScreen parent, WorldCreationContext worldCreationContext) {
		super(TITLE);
		this.parent = parent;
		this.categories = createCategories();
	}

	@Override
	protected void init() {
		int listTop = LIST_TOP;
		int listHeight = Math.max(0, this.height - LIST_BOTTOM_PADDING - listTop);
		int listWidth = Math.max(140, this.width / 2 - PREVIEW_PADDING);
		int previewWidth = Math.max(140, this.width - listWidth - PREVIEW_PADDING * 2);
		int previewHeight = Math.max(80, this.height - 80);

		this.list = new CustomizationList(this.minecraft, listWidth, listHeight, listTop, ENTRY_HEIGHT);
		this.list.setX(SIDE_PADDING);
		this.addRenderableWidget(this.list);

		int previewX = this.width - previewWidth - SIDE_PADDING;
		this.previewWidget = new TerrainPreviewWidget(previewX, listTop, previewWidth, previewHeight, this.preview);
		this.previewWidget.setFullscreenAction(this::openPreviewFullScreen);
		if (this.pendingPreviewViewState != null) {
			this.previewWidget.setViewState(this.pendingPreviewViewState);
			this.pendingPreviewViewState = null;
		}
		this.addRenderableWidget(this.previewWidget);

		this.showCategories();
		this.previewWidget.requestRebuild(this.buildSettings());

		int buttonY = this.height - 28;
		Component spawnpointLabel = Objects.requireNonNull(
				Component.translatable("gui.earth.spawnpoint"),
				"spawnpointLabel");
		this.addRenderableWidget(Button.builder(spawnpointLabel, button -> {
			if (this.minecraft != null) {
				this.minecraft.setScreen(new EarthSpawnpointScreen(this));
			}
		}).bounds(this.width / 2 - 155, buttonY, 150, ENTRY_HEIGHT).build());

		Component doneLabel = Objects.requireNonNull(Component.translatable("gui.done"), "doneLabel");
		this.addRenderableWidget(Button.builder(doneLabel, button -> this.onClose())
				.bounds(this.width / 2 + 5, buttonY, 150, ENTRY_HEIGHT)
				.build());
	}

	private void onSettingsChanged() {
		this.previewDirtyAt = System.currentTimeMillis();
	}

	public void applySpawnpoint(double latitude, double longitude) {
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
			@NonNull
			ViewState viewState = Objects.requireNonNull(this.previewWidget.getViewState(), "viewState");
			this.minecraft.setScreen(new TerrainPreviewScreen(this, this.preview, viewState));
		}
	}

	public void applyPreviewViewState(@NonNull ViewState state) {
		this.pendingPreviewViewState = state;
		if (this.previewWidget != null && this.minecraft != null && this.minecraft.screen == this) {
			this.previewWidget.setViewState(state);
			this.pendingPreviewViewState = null;
		}
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			EarthGeneratorSettings settings = Objects.requireNonNull(this.buildSettings(), "generatorSettings");
			WorldCreationContext current = Objects.requireNonNull(this.parent.getUiState().getSettings(),
					"worldCreationContext");
			EarthGeneratorSettings.HeightLimits limits = Objects.requireNonNull(
					EarthGeneratorSettings.resolveHeightLimits(settings),
					"heightLimits");
			WorldCreationContext updated = Objects.requireNonNull(
					updateWorldCreationContext(current, settings, limits),
					"updatedWorldContext");
			this.parent.getUiState().setSettings(updated);
			this.preview.close();
			this.minecraft.setScreen(this.parent);
		}
	}

	private static @NonNull WorldCreationContext updateWorldCreationContext(
			@NonNull WorldCreationContext current,
			@NonNull EarthGeneratorSettings settings,
			EarthGeneratorSettings.@NonNull HeightLimits limits) {
		WorldDimensions selectedDimensions = current.selectedDimensions();
		LevelStem overworldStem = selectedDimensions.get(LevelStem.OVERWORLD)
				.orElseThrow(() -> new IllegalStateException("Overworld settings missing"));
		Holder<DimensionType> baseType = Objects.requireNonNull(overworldStem.type(), "overworldDimensionType");
		DimensionType updatedType = Objects.requireNonNull(
				EarthGeneratorSettings.applyHeightLimits(baseType.value(), limits),
				"updatedDimensionType");

		@NonNull
		ResourceKey<DimensionType> overworldKey = Objects.requireNonNull(
				overworldStem.type().unwrapKey().orElse(DYNAMIC_DIMENSION_TYPE_KEY),
				"overworldDimensionTypeKey");
		RegistryUpdate registryUpdate = updateDimensionTypeRegistry(current.worldgenRegistries(), updatedType,
				overworldKey);
		LayeredRegistryAccess<RegistryLayer> registriesWithTypes = registryUpdate.registries();
		HolderLookup.RegistryLookup<DimensionType> dimensionTypes = registriesWithTypes.compositeAccess()
				.lookupOrThrow(Registries.DIMENSION_TYPE);
		@NonNull
		Holder<DimensionType> overworldHolder = Objects.requireNonNull(
				registryUpdate.holder(),
				"overworldDimensionTypeHolder");

		if (Tellus.LOGGER.isInfoEnabled()) {
			DimensionType registryType = dimensionTypes.getOrThrow(overworldKey).value();
			Tellus.LOGGER.info(
					"Tellus world settings: scale={}, minAltitude={}, maxAltitude={}, heightOffset={}, limits=[minY={}, height={}, logicalHeight={}], overworldKey={}, updatedType=[{}], registryType=[{}]",
					settings.worldScale(),
					settings.minAltitude(),
					settings.maxAltitude(),
					settings.heightOffset(),
					limits.minY(),
					limits.height(),
					limits.logicalHeight(),
					overworldKey.identifier(),
					describeDimensionType(updatedType),
					describeDimensionType(registryType));
		}

		ChunkGenerator generator = Objects.requireNonNull(
				EarthChunkGenerator.create(registriesWithTypes.compositeAccess(), settings), "overworldGenerator");
		WorldDimensions updatedDimensions = updateDimensions(
				selectedDimensions,
				overworldHolder,
				generator,
				dimensionTypes);
		Registry<LevelStem> updatedDatapackDimensions = updateDatapackDimensions(
				current.datapackDimensions(),
				overworldHolder,
				generator,
				dimensionTypes);
		LayeredRegistryAccess<RegistryLayer> updatedRegistries = updateWorldgenLevelStems(
				registriesWithTypes,
				updatedDatapackDimensions);

		return new WorldCreationContext(
				current.options(),
				updatedDatapackDimensions,
				updatedDimensions,
				updatedRegistries,
				current.dataPackResources(),
				current.dataConfiguration(),
				current.initialWorldCreationOptions());
	}

	private static @NonNull Registry<LevelStem> updateDatapackDimensions(
			@NonNull Registry<LevelStem> source,
			@NonNull Holder<DimensionType> overworldHolder,
			@NonNull ChunkGenerator overworldGenerator,
			HolderLookup.RegistryLookup<DimensionType> dimensionTypes) {
		HolderLookup.RegistryLookup<DimensionType> dimensionTypesChecked = Objects.requireNonNull(dimensionTypes,
				"dimensionTypes");
		Lifecycle lifecycle = Objects.requireNonNull(
				source instanceof MappedRegistry<LevelStem> mapped
						? mapped.registryLifecycle()
						: Lifecycle.experimental(),
				"datapackDimensionsLifecycle");
		MappedRegistry<LevelStem> copy = new MappedRegistry<>(Registries.LEVEL_STEM, lifecycle);
		List<Map.Entry<ResourceKey<LevelStem>, LevelStem>> entries = new ArrayList<>(source.entrySet());
		entries.sort(Comparator.comparingInt(entry -> source.getId(entry.getValue())));

		for (Map.Entry<ResourceKey<LevelStem>, LevelStem> entry : entries) {
			ResourceKey<LevelStem> key = Objects.requireNonNull(entry.getKey(), "dimensionStemKey");
			LevelStem stem = Objects.requireNonNull(entry.getValue(), "dimensionStem");
			LevelStem updatedStem;
			if (key.equals(LevelStem.OVERWORLD)) {
				updatedStem = new LevelStem(overworldHolder, overworldGenerator);
			} else {
				ResourceKey<DimensionType> typeKey = stem.type().unwrapKey().orElse(null);
				Holder<DimensionType> typeHolder = typeKey != null
						? Objects.requireNonNull(dimensionTypesChecked.getOrThrow(typeKey), "dimensionType")
						: Objects.requireNonNull(stem.type(), "stemDimensionType");
				updatedStem = new LevelStem(typeHolder, stem.generator());
			}
			RegistrationInfo info = Objects.requireNonNull(
					source.registrationInfo(key).orElse(RegistrationInfo.BUILT_IN),
					"dimensionStemRegistrationInfo");
			copy.register(key, updatedStem, info);
		}

		return copy.freeze();
	}

	private static @NonNull LayeredRegistryAccess<RegistryLayer> updateWorldgenLevelStems(
			@NonNull LayeredRegistryAccess<RegistryLayer> registries,
			@NonNull Registry<LevelStem> updatedLevelStems) {
		@NonNull
		LayeredRegistryAccess<RegistryLayer> updated = registries;
		boolean updatedAny = false;
		for (RegistryLayer layer : RegistryLayer.values()) {
			RegistryAccess.Frozen layerAccess = updated.getLayer(layer);
			if (layerAccess.lookup(Registries.LEVEL_STEM).isEmpty()) {
				continue;
			}
			RegistryAccess.Frozen updatedLayer = replaceRegistry(layerAccess, Registries.LEVEL_STEM, updatedLevelStems);
			updated = replaceLayer(updated, layer, updatedLayer);
			updatedAny = true;
		}
		LayeredRegistryAccess<RegistryLayer> result = updatedAny ? updated : registries;
		return Objects.requireNonNull(result, "updatedRegistries");
	}

	private static @NonNull WorldDimensions updateDimensions(
			@NonNull WorldDimensions dimensions,
			@NonNull Holder<DimensionType> overworldHolder,
			@NonNull ChunkGenerator overworldGenerator,
			HolderLookup.RegistryLookup<DimensionType> dimensionTypes) {
		HolderLookup.RegistryLookup<DimensionType> dimensionTypesChecked = Objects.requireNonNull(dimensionTypes,
				"dimensionTypes");
		Map<ResourceKey<LevelStem>, LevelStem> updatedStems = new LinkedHashMap<>();
		dimensions.dimensions().forEach((key, stem) -> {
			Holder<DimensionType> typeHolder;
			if (key.equals(LevelStem.OVERWORLD)) {
				typeHolder = overworldHolder;
			} else {
				ResourceKey<DimensionType> typeKey = stem.type().unwrapKey().orElse(null);
				typeHolder = typeKey != null
						? Objects.requireNonNull(dimensionTypesChecked.getOrThrow(typeKey), "dimensionType")
						: Objects.requireNonNull(stem.type(), "stemDimensionType");
			}
			updatedStems.put(key, new LevelStem(typeHolder, stem.generator()));
		});

		return new WorldDimensions(WorldDimensions.withOverworld(
				updatedStems,
				overworldHolder,
				overworldGenerator));
	}

	@Override
	public void tick() {
		super.tick();
		if (this.previewWidget != null) {
			this.previewWidget.tick();
		}
		if (this.previewDirtyAt > 0L && System.currentTimeMillis() - this.previewDirtyAt >= PREVIEW_DEBOUNCE_MS) {
			this.previewDirtyAt = -1L;
			if (this.previewWidget != null) {
				this.previewWidget.requestRebuild(this.buildSettings());
			}
		}
	}

	@Override
	public void removed() {
		if (this.previewWidget != null) {
			this.previewWidget.close();
		}
		super.removed();
	}

	private @NonNull EarthGeneratorSettings buildSettings() {
		double worldScale = this.findSliderValue("world_scale", EarthGeneratorSettings.DEFAULT.worldScale());
		double terrestrialScale = this.findSliderValue("terrestrial_height_scale",
				EarthGeneratorSettings.DEFAULT.terrestrialHeightScale());
		double oceanicScale = this.findSliderValue("oceanic_height_scale",
				EarthGeneratorSettings.DEFAULT.oceanicHeightScale());
		int heightOffset = (int) Math
				.round(this.findSliderValue("height_offset", EarthGeneratorSettings.DEFAULT.heightOffset()));
		int seaLevel = this.resolveSeaLevelSetting("sea_level", AUTO_SEA_LEVEL);
		int maxAltitude = this.resolveAltitudeSetting("max_altitude", AUTO_MAX_ALTITUDE);
		int minAltitude = this.resolveAltitudeSetting("min_altitude", AUTO_MIN_ALTITUDE);
		int riverLakeShorelineBlend = (int) Math.round(
				this.findSliderValue("river_lake_shoreline_blend",
						EarthGeneratorSettings.DEFAULT.riverLakeShorelineBlend()));
		int oceanShorelineBlend = (int) Math.round(
				this.findSliderValue("ocean_shoreline_blend", EarthGeneratorSettings.DEFAULT.oceanShorelineBlend()));
		boolean shorelineBlendCliffLimit = this.findToggleValue(
				"shoreline_blend_cliff_limit",
				EarthGeneratorSettings.DEFAULT.shorelineBlendCliffLimit());
		boolean caveGeneration = this.findToggleValue(
				"cave_generation",
				EarthGeneratorSettings.DEFAULT.caveGeneration());
		boolean oreDistribution = this.findToggleValue(
				"ore_distribution",
				EarthGeneratorSettings.DEFAULT.oreDistribution());
		boolean lavaPools = this.findToggleValue(
				"lava_pools",
				EarthGeneratorSettings.DEFAULT.lavaPools());
		boolean deepDark = this.findToggleValue("deep_dark", EarthGeneratorSettings.DEFAULT.deepDark());
		boolean geodes = this.findToggleValue("geodes", EarthGeneratorSettings.DEFAULT.geodes());
		boolean addStrongholds = this.findToggleValue(
				"add_strongholds",
				EarthGeneratorSettings.DEFAULT.addStrongholds());
		boolean addVillages = this.findToggleValue("add_villages", EarthGeneratorSettings.DEFAULT.addVillages());
		boolean addMineshafts = this.findToggleValue(
				"add_mineshafts",
				EarthGeneratorSettings.DEFAULT.addMineshafts());
		boolean addOceanMonuments = this.findToggleValue(
				"add_ocean_monuments",
				EarthGeneratorSettings.DEFAULT.addOceanMonuments());
		boolean addWoodlandMansions = this.findToggleValue(
				"add_woodland_mansions",
				EarthGeneratorSettings.DEFAULT.addWoodlandMansions());
		boolean addDesertTemples = this.findToggleValue(
				"add_desert_temples",
				EarthGeneratorSettings.DEFAULT.addDesertTemples());
		boolean addJungleTemples = this.findToggleValue(
				"add_jungle_temples",
				EarthGeneratorSettings.DEFAULT.addJungleTemples());
		boolean addPillagerOutposts = this.findToggleValue(
				"add_pillager_outposts",
				EarthGeneratorSettings.DEFAULT.addPillagerOutposts());
		boolean addRuinedPortals = this.findToggleValue(
				"add_ruined_portals",
				EarthGeneratorSettings.DEFAULT.addRuinedPortals());
		boolean addShipwrecks = this.findToggleValue(
				"add_shipwrecks",
				EarthGeneratorSettings.DEFAULT.addShipwrecks());
		boolean addOceanRuins = this.findToggleValue(
				"add_ocean_ruins",
				EarthGeneratorSettings.DEFAULT.addOceanRuins());
		boolean addBuriedTreasure = this.findToggleValue(
				"add_buried_treasure",
				EarthGeneratorSettings.DEFAULT.addBuriedTreasure());
		boolean addIgloos = this.findToggleValue("add_igloos", EarthGeneratorSettings.DEFAULT.addIgloos());
		boolean addWitchHuts = this.findToggleValue("add_witch_huts", EarthGeneratorSettings.DEFAULT.addWitchHuts());
		boolean addAncientCities = this.findToggleValue(
				"add_ancient_cities",
				EarthGeneratorSettings.DEFAULT.addAncientCities());
		boolean addTrialChambers = this.findToggleValue(
				"add_trial_chambers",
				EarthGeneratorSettings.DEFAULT.addTrialChambers());
		boolean addTrailRuins = this.findToggleValue("add_trail_ruins", EarthGeneratorSettings.DEFAULT.addTrailRuins());
		boolean distantHorizonsWaterResolver = this.findToggleValue(
				"distant_horizons_water_resolver",
				EarthGeneratorSettings.DEFAULT.distantHorizonsWaterResolver());
		boolean realtimeTime = this.findToggleValue("realtime_time", EarthGeneratorSettings.DEFAULT.realtimeTime());
		boolean realtimeWeather = this.findToggleValue("realtime_weather",
				EarthGeneratorSettings.DEFAULT.realtimeWeather());
		boolean historicalSnow = this.findToggleValue("historical_snow",
				EarthGeneratorSettings.DEFAULT.historicalSnow());
		boolean experimentalRoads = this.findToggleValue("experimental_roads",
				EarthGeneratorSettings.DEFAULT.experimentalRoads());
		EarthGeneratorSettings.DistantHorizonsRenderMode renderMode = this.findRenderMode(
				"distant_horizons_render_mode",
				EarthGeneratorSettings.DEFAULT.distantHorizonsRenderMode());
		return new EarthGeneratorSettings(
				worldScale,
				terrestrialScale,
				oceanicScale,
				heightOffset,
				seaLevel,
				this.spawnLatitude,
				this.spawnLongitude,
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
				realtimeTime,
				realtimeWeather,
				historicalSnow,
				experimentalRoads,
				renderMode);
	}

	private double findSliderValue(String key, double fallback) {
		for (CategoryDefinition category : this.categories) {
			for (SettingDefinition setting : category.getSettings()) {
				if (setting instanceof SliderDefinition slider && slider.key.equals(key)) {
					return slider.value;
				}
			}
		}
		return fallback;
	}

	private boolean findToggleValue(String key, boolean fallback) {
		for (CategoryDefinition category : this.categories) {
			for (SettingDefinition setting : category.getSettings()) {
				if (setting instanceof ToggleDefinition toggle && toggle.key.equals(key)) {
					return toggle.value;
				}
			}
		}
		return fallback;
	}

	private EarthGeneratorSettings.DistantHorizonsRenderMode findRenderMode(
			String key,
			EarthGeneratorSettings.DistantHorizonsRenderMode fallback) {
		for (CategoryDefinition category : this.categories) {
			for (SettingDefinition setting : category.getSettings()) {
				if (setting instanceof ModeDefinition mode && mode.key.equals(key)) {
					return mode.value;
				}
			}
		}
		return fallback;
	}

	@Override
	public void render(@NonNull GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.render(graphics, mouseX, mouseY, delta);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
	}

	private static List<CategoryDefinition> createCategories() {
		List<CategoryDefinition> categories = new ArrayList<>();

		categories.add(new CategoryDefinition("world", List.of(
				slider("world_scale", 35.0, 1.0, 500.0, 5.0)
						.withDisplay(EarthCustomizeScreen::formatWorldScale)
						.withScale(SliderScale.power(3.0)),
				slider("terrestrial_height_scale", 1.0, 0.0, 50.0, 0.5)
						.withDisplay(EarthCustomizeScreen::formatMultiplier)
						.withScale(SliderScale.power(3.0)),
				slider("oceanic_height_scale", 1.0, 0.0, 50.0, 0.5)
						.withDisplay(EarthCustomizeScreen::formatMultiplier)
						.withScale(SliderScale.power(3.0)),
				slider("height_offset", EarthGeneratorSettings.DEFAULT.heightOffset(), -63.0, 128.0, 1.0)
						.withDisplay(EarthCustomizeScreen::formatHeightOffset),
				slider("sea_level", AUTO_SEA_LEVEL, AUTO_SEA_LEVEL, 256.0, 1.0)
						.withDisplay(EarthCustomizeScreen::formatSeaLevel),
				slider("max_altitude", AUTO_MAX_ALTITUDE, AUTO_MAX_ALTITUDE, EarthGeneratorSettings.MAX_WORLD_Y, 16.0)
						.withDisplay(EarthCustomizeScreen::formatMaxAltitude),
				slider("min_altitude", EarthGeneratorSettings.DEFAULT.minAltitude(),
						AUTO_MIN_ALTITUDE, EarthGeneratorSettings.MAX_WORLD_Y, 16.0)
						.withDisplay(EarthCustomizeScreen::formatMinAltitude),
				slider("river_lake_shoreline_blend", EarthGeneratorSettings.DEFAULT.riverLakeShorelineBlend(), 0.0,
						10.0, 1.0)
						.withDisplay(EarthCustomizeScreen::formatHeightOffset),
				slider("ocean_shoreline_blend", EarthGeneratorSettings.DEFAULT.oceanShorelineBlend(), 0.0, 10.0, 1.0)
						.withDisplay(EarthCustomizeScreen::formatHeightOffset),
				toggle("shoreline_blend_cliff_limit", EarthGeneratorSettings.DEFAULT.shorelineBlendCliffLimit()),
				toggle("experimental_roads", EarthGeneratorSettings.DEFAULT.experimentalRoads()))));

		categories.add(new CategoryDefinition("ecological", List.of(
				toggle("land_vegetation", true).locked(true),
				slider("land_vegetation_density", 100.0, 0.0, 200.0, 5.0)
						.withDisplay(EarthCustomizeScreen::formatPercent)
						.locked(true),
				slider("trees_density", 100.0, 0.0, 200.0, 5.0)
						.withDisplay(EarthCustomizeScreen::formatPercent)
						.locked(true),
				toggle("aquatic_vegetation", true).locked(true),
				toggle("crops_in_villages", true).locked(true))));

		categories.add(new CategoryDefinition("geological", List.of(
				toggle("cave_generation", EarthGeneratorSettings.DEFAULT.caveGeneration()),
				toggle("ore_distribution", EarthGeneratorSettings.DEFAULT.oreDistribution()),
				toggle("lava_pools", EarthGeneratorSettings.DEFAULT.lavaPools()))));

		categories.add(new CategoryDefinition("structure", List.of(
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
				toggle("geodes", EarthGeneratorSettings.DEFAULT.geodes()))));

		categories.add(new CategoryDefinition("realtime", List.of(
				toggle("realtime_time", EarthGeneratorSettings.DEFAULT.realtimeTime()),
				toggle("realtime_weather", EarthGeneratorSettings.DEFAULT.realtimeWeather()),
				toggle("historical_snow", EarthGeneratorSettings.DEFAULT.historicalSnow()))));

		categories.add(new CategoryDefinition("compatibility", List.of(
				mode("distant_horizons_render_mode", EarthGeneratorSettings.DEFAULT.distantHorizonsRenderMode()),
				mode("distant_horizons_render_mode", EarthGeneratorSettings.DEFAULT.distantHorizonsRenderMode()),
				toggle("distant_horizons_water_resolver",
						EarthGeneratorSettings.DEFAULT.distantHorizonsWaterResolver()),
				comingSoonButton())));

		categories.add(new CategoryDefinition("cache", List.of(
				cacheEntry(CacheMetric.OSM, true),
				cacheEntry(CacheMetric.ESA, true),
				cacheEntry(CacheMetric.TERRAIN, true),
				cacheEntry(CacheMetric.TOTAL, false),
				cacheActionButton(
						Component.translatable("tellus.cache.delete_all"),
						CacheManager::deleteAll))));

		categories.add(new CategoryDefinition("data_sources", dataSourcesEntries()));

		return categories;
	}

	private static SliderDefinition slider(
			String key,
			double defaultValue,
			double min,
			double max,
			double step) {
		return new SliderDefinition(key, defaultValue, min, max, step);
	}

	private static ToggleDefinition toggle(String key, boolean defaultValue) {
		return new ToggleDefinition(key, defaultValue);
	}

	private static ModeDefinition mode(
			String key,
			EarthGeneratorSettings.DistantHorizonsRenderMode defaultValue) {
		return new ModeDefinition(key, defaultValue);
	}

	private static ButtonDefinition comingSoonButton() {
		Component label = Objects.requireNonNull(Component.translatable("gui.tellus.coming_soon"), "comingSoonLabel");
		Component tooltip = Objects.requireNonNull(
				Component.translatable("tellus.customize.coming_soon")
						.withStyle(ChatFormatting.GRAY)
						.copy()
						.append(Component.literal(" "))
						.append(WORK_IN_PROGRESS),
				"comingSoonTooltip");
		return new ButtonDefinition(label, tooltip, false);
	}

	private static CacheEntryDefinition cacheEntry(CacheMetric metric, boolean allowDelete) {
		return new CacheEntryDefinition(metric, allowDelete);
	}

	private static CacheActionDefinition cacheActionButton(@NonNull Component label, @NonNull Runnable action) {
		return new CacheActionDefinition(label, action);
	}

	private static List<SettingDefinition> dataSourcesEntries() {
		List<SettingDefinition> entries = new ArrayList<>();

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
		entries.add(infoSubtle(
				"Austria terrain data © offene Daten Österreichs – Digitales Geländemodell (DGM) Österreich;"));
		entries.add(infoSubtle(
				"Canada terrain data contains information licensed under the Open Government Licence – Canada;"));
		entries.add(infoSubtle("Europe terrain data produced using Copernicus data and information funded by the"));
		entries.add(infoSubtle("European Union – EU-DEM layers;"));
		entries.add(infoSubtle("Global ETOPO1 terrain data U.S. National Oceanic and Atmospheric Administration;"));
		entries.add(infoSubtle("Mexico terrain data source: INEGI, Continental relief, 2016;"));
		entries.add(infoSubtle("New Zealand terrain data Copyright 2011 Crown copyright (c) Land Information"));
		entries.add(infoSubtle("New Zealand and the New Zealand Government (All rights reserved);"));
		entries.add(infoSubtle("Norway terrain data © Kartverket;"));
		entries.add(
				infoSubtle("United Kingdom terrain data © Environment Agency copyright and/or database right 2015."));
		entries.add(infoSubtle("All rights reserved;"));
		entries.add(infoSubtle("United States 3DEP (formerly NED) and global GMTED2010 and SRTM terrain data"));
		entries.add(infoSubtle("courtesy of the U.S. Geological Survey."));
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

	private static TextLineDefinition infoHeader(@NonNull String text) {
		return new TextLineDefinition(Component.literal(text), INFO_TITLE_COLOR, null);
	}

	private static TextLineDefinition infoLine(@NonNull String text) {
		return new TextLineDefinition(Component.literal(text), INFO_TEXT_COLOR, null);
	}

	private static TextLineDefinition infoSubtle(@NonNull String text) {
		return new TextLineDefinition(Component.literal(text), INFO_SUBTLE_COLOR, null);
	}

	private static TextLineDefinition infoLink(@NonNull String url) {
		return new TextLineDefinition(Component.literal(url), INFO_LINK_COLOR, url);
	}

	private static SpacerDefinition infoSpacer() {
		return new SpacerDefinition();
	}

	private static String formatWorldScale(double value) {
		if (value < 1000.0) {
			return String.format(Locale.ROOT, "1:%.0fm", value);
		}
		return String.format(Locale.ROOT, "1:%.1fkm", value / 1000.0);
	}

	private static String formatLocalDate() {
		DateTimeFormatter formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
				.withLocale(Locale.getDefault());
		return formatter.format(LocalDate.now());
	}

	private static String formatMultiplier(double value) {
		return String.format(Locale.ROOT, "%.1fx", value);
	}

	private static String formatHeightOffset(double value) {
		return String.format(Locale.ROOT, "%.0f blocks", value);
	}

	private static String formatSeaLevel(double value) {
		if (value <= AUTO_SEA_LEVEL + SEA_LEVEL_AUTO_EPSILON) {
			return "Automatic";
		}
		return String.format(Locale.ROOT, "%.0f blocks", value);
	}

	private static String formatPercent(double value) {
		return String.format(Locale.ROOT, "%.0f%%", value);
	}

	private static String formatMaxAltitude(double value) {
		return formatAltitude(value, AUTO_MAX_ALTITUDE);
	}

	private static String formatMinAltitude(double value) {
		return formatAltitude(value, AUTO_MIN_ALTITUDE);
	}

	private static @NonNull Component formatRenderMode(EarthGeneratorSettings.DistantHorizonsRenderMode mode) {
		return Objects.requireNonNull(
				Component.translatable("property.tellus.distant_horizons_render_mode.value." + mode.id()),
				"renderModeLabel");
	}

	private static String formatAltitude(double value, double autoValue) {
		if (value <= autoValue + ALTITUDE_AUTO_EPSILON) {
			return "Automatic";
		}
		return String.format(Locale.ROOT, "%.0f blocks", value);
	}

	private static @NonNull String formatBytes(long bytes) {
		if (bytes <= 0) {
			return "0 B";
		}
		double value = bytes;
		String[] units = { "B", "KB", "MB", "GB", "TB" };
		int unit = 0;
		while (value >= 1024.0 && unit < units.length - 1) {
			value /= 1024.0;
			unit++;
		}
		if (unit == 0) {
			@NonNull
			String formatted = Objects.requireNonNull(String.format(Locale.ROOT, "%d B", bytes), "formattedBytes");
			return formatted;
		}
		@NonNull
		String formatted = Objects.requireNonNull(String.format(Locale.ROOT, "%.1f %s", value, units[unit]),
				"formattedBytes");
		return formatted;
	}

	private static @NonNull Component settingName(String key) {
		return Objects.requireNonNull(Component.translatable("property.tellus." + key + ".name"), "settingName");
	}

	private static @NonNull Component settingTooltip(String key) {
		return Objects.requireNonNull(
				Component.translatable("property.tellus." + key + ".tooltip").withStyle(ChatFormatting.GRAY),
				"settingTooltip");
	}

	private static @NonNull Component workInProgressTooltip(String key) {
		return Objects.requireNonNull(settingTooltip(key), "settingTooltip")
				.copy()
				.append(Component.literal(" "))
				.append(WORK_IN_PROGRESS);
	}

	private static String describeDimensionType(DimensionType type) {
		return "minY=" + type.minY() + ",height=" + type.height() + ",logicalHeight=" + type.logicalHeight();
	}

	private int resolveAltitudeSetting(String key, double autoValue) {
		double value = this.findSliderValue(key, autoValue);
		if (value <= autoValue + ALTITUDE_AUTO_EPSILON) {
			return EarthGeneratorSettings.AUTO_ALTITUDE;
		}
		return (int) Math.round(value);
	}

	private int resolveSeaLevelSetting(String key, double autoValue) {
		double value = this.findSliderValue(key, autoValue);
		if (value <= autoValue + SEA_LEVEL_AUTO_EPSILON) {
			return EarthGeneratorSettings.AUTO_SEA_LEVEL;
		}
		return (int) Math.round(value);
	}

	private static @NonNull RegistryUpdate updateDimensionTypeRegistry(
			@NonNull LayeredRegistryAccess<RegistryLayer> registries,
			@NonNull DimensionType updatedType,
			@NonNull ResourceKey<DimensionType> targetKey) {
		LayeredRegistryAccess<RegistryLayer> updatedRegistries = registries;
		boolean updatedAny = false;
		for (RegistryLayer layer : RegistryLayer.values()) {
			RegistryAccess.Frozen layerAccess = updatedRegistries.getLayer(layer);
			if (layerAccess.lookup(Registries.DIMENSION_TYPE).isEmpty()) {
				continue;
			}
			Registry<DimensionType> source = layerAccess.lookupOrThrow(Registries.DIMENSION_TYPE);
			Lifecycle lifecycle = Objects.requireNonNull(
					source instanceof MappedRegistry<DimensionType> mapped
							? mapped.registryLifecycle()
							: Lifecycle.experimental(),
					"dimensionTypeLifecycle");
			MappedRegistry<DimensionType> copy = new MappedRegistry<>(Registries.DIMENSION_TYPE, lifecycle);
			List<Map.Entry<ResourceKey<DimensionType>, DimensionType>> entries = new ArrayList<>(source.entrySet());
			entries.sort(Comparator.comparingInt(entry -> source.getId(entry.getValue())));

			for (Map.Entry<ResourceKey<DimensionType>, DimensionType> entry : entries) {
				ResourceKey<DimensionType> key = Objects.requireNonNull(entry.getKey(), "dimensionTypeKey");
				if (key.equals(targetKey)) {
					continue;
				}
				DimensionType value = Objects.requireNonNull(entry.getValue(), "dimensionType");
				RegistrationInfo info = Objects.requireNonNull(
						source.registrationInfo(key).orElse(RegistrationInfo.BUILT_IN),
						"registrationInfo");
				copy.register(key, value, info);
			}

			@NonNull
			Optional<KnownPack> emptyKnownPack = Objects.requireNonNull(
					Optional.<KnownPack>empty(),
					"emptyKnownPack");
			RegistrationInfo targetInfo = Objects.requireNonNull(
					source.registrationInfo(targetKey)
							.map(info -> new RegistrationInfo(
									emptyKnownPack,
									Objects.requireNonNull(info.lifecycle(), "dimensionTypeLifecycle")))
							.orElseGet(() -> new RegistrationInfo(
									emptyKnownPack,
									Objects.requireNonNull(Lifecycle.experimental(), "experimentalLifecycle"))),
					"dimensionTypeRegistrationInfo");
			copy.register(
					targetKey,
					Objects.requireNonNull(updatedType, "updatedType"),
					targetInfo);
			Registry<DimensionType> frozen = copy.freeze();
			RegistryAccess.Frozen updatedLayer = replaceRegistry(layerAccess, Registries.DIMENSION_TYPE, frozen);
			updatedRegistries = replaceLayer(updatedRegistries, layer, updatedLayer);
			updatedAny = true;
		}

		if (!updatedAny) {
			throw new IllegalStateException("Dimension type registry missing");
		}

		HolderLookup.RegistryLookup<DimensionType> dimensionTypes = updatedRegistries.compositeAccess()
				.lookupOrThrow(Registries.DIMENSION_TYPE);
		Holder<DimensionType> holder = Objects.requireNonNull(
				dimensionTypes.getOrThrow(targetKey),
				"dimensionTypeHolder");
		return new RegistryUpdate(updatedRegistries, holder);
	}

	private static RegistryAccess.Frozen replaceRegistry(
			RegistryAccess.Frozen source,
			ResourceKey<? extends Registry<?>> registryKey,
			Registry<?> replacement) {
		Map<ResourceKey<? extends Registry<?>>, Registry<?>> registryMap = new LinkedHashMap<>();
		source.registries().forEach(entry -> registryMap.put(entry.key(), entry.value()));
		registryMap.put(registryKey, replacement);
		return new RegistryAccess.ImmutableRegistryAccess(registryMap).freeze();
	}

	private static @NonNull LayeredRegistryAccess<RegistryLayer> replaceLayer(
			@NonNull LayeredRegistryAccess<RegistryLayer> registries,
			@NonNull RegistryLayer target,
			RegistryAccess.Frozen replacement) {
		RegistryAccess.Frozen replacementChecked = Objects.requireNonNull(replacement, "replacement");
		RegistryLayer[] layers = RegistryLayer.values();
		List<RegistryAccess.Frozen> replacements = new ArrayList<>();
		boolean found = false;
		for (RegistryLayer layer : layers) {
			if (!found) {
				if (layer == target) {
					found = true;
					replacements.add(replacementChecked);
				}
				continue;
			}
			replacements.add(registries.getLayer(layer));
		}
		if (!found) {
			throw new IllegalStateException("Registry layer missing: " + target);
		}
		return registries.replaceFrom(target, replacements);
	}

	private record RegistryUpdate(LayeredRegistryAccess<RegistryLayer> registries, Holder<DimensionType> holder) {
	}

	private interface SettingDefinition {
		AbstractWidget createWidget(Runnable onChange);
	}

	private void showCategories() {
		setPreviewVisible(true);
		this.list.clear();
		for (CategoryDefinition category : this.categories) {
			Component label = Objects.requireNonNull(category.getLabel(), "categoryLabel");
			Button button = Button.builder(label, btn -> this.showCategory(category))
					.bounds(0, 0, this.list.getRowWidth(), ENTRY_HEIGHT)
					.build();
			this.list.addWidget(button);
		}
		this.list.setScrollAmount(0.0);
	}

	private void showCategory(CategoryDefinition category) {
		this.list.clear();
		Component backLabel = Objects.requireNonNull(Component.translatable("gui.back"), "backLabel");
		Button back = Button.builder(backLabel, btn -> this.showCategories())
				.bounds(0, 0, this.list.getRowWidth(), ENTRY_HEIGHT)
				.build();
		this.list.addWidget(back);

		boolean hidePreview = isPreviewHiddenCategory(category.getId());
		setPreviewVisible(!hidePreview);

		if ("cache".equals(category.getId())) {
			CacheManager.requestRefresh();
		}

		for (SettingDefinition setting : category.getSettings()) {
			this.list.addWidget(setting.createWidget(this::onSettingsChanged));
		}
		this.list.setScrollAmount(0.0);
	}

	private static boolean isPreviewHiddenCategory(String id) {
		return "cache".equals(id) || "data_sources".equals(id);
	}

	private void setPreviewVisible(boolean visible) {
		updateLayout(visible);
	}

	private void updateLayout(boolean previewVisible) {
		if (this.list == null) {
			return;
		}
		int listTop = LIST_TOP;
		int listHeight = Math.max(0, this.height - LIST_BOTTOM_PADDING - listTop);
		int listWidth = previewVisible
				? Math.max(140, this.width / 2 - PREVIEW_PADDING)
				: Math.max(140, this.width - SIDE_PADDING * 2);

		this.list.setX(SIDE_PADDING);
		this.list.setY(listTop);
		this.list.setWidth(listWidth);
		this.list.setHeight(listHeight);

		if (this.previewWidget == null) {
			return;
		}
		this.previewWidget.visible = previewVisible;
		this.previewWidget.active = previewVisible;

		if (!previewVisible) {
			return;
		}

		int previewWidth = Math.max(140, this.width - listWidth - PREVIEW_PADDING * 2);
		int previewHeight = Math.max(80, this.height - 80);
		int previewX = this.width - previewWidth - SIDE_PADDING;
		this.previewWidget.setX(previewX);
		this.previewWidget.setY(listTop);
		this.previewWidget.setWidth(previewWidth);
		this.previewWidget.setHeight(previewHeight);
	}

	private static final class CategoryDefinition {
		private final String id;
		private final List<SettingDefinition> settings;

		private CategoryDefinition(String id, List<SettingDefinition> settings) {
			this.id = id;
			this.settings = settings;
		}

		private String getId() {
			return this.id;
		}

		private @NonNull Component getLabel() {
			return this.getLabel(false);
		}

		private @NonNull Component getLabel(boolean selected) {
			Component base = Objects.requireNonNull(
					Component.translatable("category.tellus." + this.id + ".name"),
					"categoryLabel");
			if (!selected) {
				return base;
			}
			return Objects.requireNonNull(base.copy().withStyle(ChatFormatting.YELLOW), "selectedCategoryLabel");
		}

		private List<SettingDefinition> getSettings() {
			return this.settings;
		}

	}

	private static final class ToggleDefinition implements SettingDefinition {
		private final String key;
		private boolean value;
		private boolean locked;

		private ToggleDefinition(String key, boolean defaultValue) {
			this.key = key;
			this.value = defaultValue;
		}

		private ToggleDefinition locked(boolean locked) {
			this.locked = locked;
			return this;
		}

		@Override
		public AbstractWidget createWidget(Runnable onChange) {
			Component name = settingName(this.key);
			Component tooltip = this.locked
					? workInProgressTooltip(this.key)
					: settingTooltip(this.key);
			CycleButton.Builder<Boolean> builder = CycleButton.booleanBuilder(YES, NO, this.value)
					.withTooltip(value -> Tooltip.create(tooltip));
			CycleButton<Boolean> button = builder.create(0, 0, 0, ENTRY_HEIGHT, name, (btn, value) -> {
				this.value = value;
				onChange.run();
			});
			button.active = !this.locked;
			return button;
		}
	}

	private static final class ButtonDefinition implements SettingDefinition {
		private final @NonNull Component label;
		private final @Nullable Component tooltip;
		private final boolean active;

		private ButtonDefinition(Component label, Component tooltip, boolean active) {
			this.label = Objects.requireNonNull(label, "buttonLabel");
			this.tooltip = tooltip;
			this.active = active;
		}

		@Override
		public AbstractWidget createWidget(Runnable onChange) {
			Button button = Button.builder(this.label, btn -> {
			}).bounds(0, 0, 0, ENTRY_HEIGHT).build();
			button.active = this.active;
			if (this.tooltip != null) {
				button.setTooltip(Tooltip.create(this.tooltip));
			}
			return button;
		}
	}

	private static final class CacheActionDefinition implements SettingDefinition {
		private final @NonNull Component label;
		private final @NonNull Runnable action;

		private CacheActionDefinition(@NonNull Component label, @NonNull Runnable action) {
			this.label = Objects.requireNonNull(label, "label");
			this.action = Objects.requireNonNull(action, "action");
		}

		@Override
		public AbstractWidget createWidget(Runnable onChange) {
			return new CacheActionWidget(this.label, this.action);
		}
	}

	private static final class CacheEntryDefinition implements SettingDefinition {
		private final CacheMetric metric;
		private final boolean allowDelete;

		private CacheEntryDefinition(CacheMetric metric, boolean allowDelete) {
			this.metric = Objects.requireNonNull(metric, "metric");
			this.allowDelete = allowDelete;
		}

		@Override
		public AbstractWidget createWidget(Runnable onChange) {
			return new CacheEntryWidget(this.metric, this.allowDelete);
		}
	}

	private static final class TextLineDefinition implements SettingDefinition {
		private final @NonNull Component text;
		private final int color;
		private final @Nullable String url;

		private TextLineDefinition(Component text, int color, @Nullable String url) {
			this.text = Objects.requireNonNull(text, "text");
			this.color = color;
			this.url = url;
		}

		@Override
		public AbstractWidget createWidget(Runnable onChange) {
			return new TextLineWidget(this.text, this.color, this.url);
		}
	}

	private static final class SpacerDefinition implements SettingDefinition {
		@Override
		public AbstractWidget createWidget(Runnable onChange) {
			return new SpacerWidget();
		}
	}

	private static final class CacheEntryWidget extends AbstractWidget {
		private static final int PADDING = 4;
		private static final int MIN_BUTTON_WIDTH = 96;
		private static final @NonNull Component DELETE_LABEL = Objects
				.requireNonNull(Component.translatable("tellus.cache.delete"), "deleteLabel");

		private final CacheMetric metric;
		private final Button deleteButton;
		private final boolean allowDelete;

		private CacheEntryWidget(CacheMetric metric, boolean allowDelete) {
			super(0, 0, 0, ENTRY_HEIGHT, Component.empty());
			this.metric = Objects.requireNonNull(metric, "metric");
			this.allowDelete = allowDelete;
			this.deleteButton = Button.builder(DELETE_LABEL, btn -> CacheManager.delete(metric))
					.bounds(0, 0, 0, ENTRY_HEIGHT)
					.build();
		}

		@Override
		protected void renderWidget(@NonNull GuiGraphics graphics, int mouseX, int mouseY, float delta) {
			var font = Minecraft.getInstance().font;
			CacheSnapshot snapshot = CacheManager.snapshot();
			boolean ready = snapshot.ready();
			long bytes = snapshot.bytesFor(this.metric);
			@NonNull
			String sizeText = ready ? formatBytes(bytes) : "...";

			int buttonWidth = Math.max(MIN_BUTTON_WIDTH, font.width(DELETE_LABEL) + 12);
			int buttonX = this.getX() + this.width - buttonWidth;
			int buttonY = this.getY();
			int sizeWidth = font.width(sizeText);
			int sizeX = buttonX - PADDING - sizeWidth;
			int labelX = this.getX() + PADDING;
			int textY = this.getY() + (this.height - font.lineHeight) / 2;

			Component label = this.metric.label();
			graphics.drawString(font, label, labelX, textY, 0xFFFFFFFF, false);
			graphics.drawString(font, sizeText, sizeX, textY, 0xFFA0A0A0, false);

			if (this.allowDelete) {
				this.deleteButton.setX(buttonX);
				this.deleteButton.setY(buttonY);
				this.deleteButton.setWidth(buttonWidth);
				this.deleteButton.setHeight(this.height);
				this.deleteButton.active = ready && bytes > 0;
				this.deleteButton.render(graphics, mouseX, mouseY, delta);
			}
		}

		@Override
		public void onClick(@NonNull MouseButtonEvent event, boolean doubleClick) {
			if (this.allowDelete) {
				this.deleteButton.mouseClicked(event, doubleClick);
			}
		}

		@Override
		protected void onDrag(@NonNull MouseButtonEvent event, double deltaX, double deltaY) {
			if (this.allowDelete) {
				this.deleteButton.mouseDragged(event, deltaX, deltaY);
			}
		}

		@Override
		public void onRelease(@NonNull MouseButtonEvent event) {
			if (this.allowDelete) {
				this.deleteButton.mouseReleased(event);
			}
		}

		@Override
		protected void updateWidgetNarration(@NonNull NarrationElementOutput narration) {
		}
	}

	private static final class TextLineWidget extends AbstractWidget {
		private final @NonNull Component text;
		private final int color;
		private final @Nullable String url;

		private TextLineWidget(Component text, int color, @Nullable String url) {
			super(0, 0, 0, ENTRY_HEIGHT, Component.empty());
			this.text = Objects.requireNonNull(text, "text");
			this.color = color;
			this.url = url;
		}

		@Override
		protected void renderWidget(@NonNull GuiGraphics graphics, int mouseX, int mouseY, float delta) {
			if (this.text.getString().isEmpty()) {
				return;
			}
			var font = Minecraft.getInstance().font;
			int textWidth = font.width(this.text);
			int textX = this.getX() + Math.max(0, (this.width - textWidth) / 2);
			int textY = this.getY() + (this.height - font.lineHeight) / 2;
			boolean hover = this.url != null && this.isMouseOver(mouseX, mouseY);
			int drawColor = hover ? INFO_LINK_HOVER_COLOR : this.color;
			graphics.drawString(font, this.text, textX, textY, drawColor, true);
			if (this.url != null) {
				int underlineY = textY + font.lineHeight;
				graphics.fill(textX, underlineY, textX + textWidth, underlineY + 1, drawColor);
			}
		}

		@Override
		public boolean mouseClicked(@NonNull MouseButtonEvent event, boolean isPrimary) {
			String url = this.url;
			if (url != null && event.button() == 0 && this.isMouseOver(event.x(), event.y())) {
				Util.getPlatform().openUri(url);
				return true;
			}
			return false;
		}

		@Override
		protected void updateWidgetNarration(@NonNull NarrationElementOutput narration) {
		}
	}

	private static final class SpacerWidget extends AbstractWidget {
		private SpacerWidget() {
			super(0, 0, 0, ENTRY_HEIGHT, Component.empty());
		}

		@Override
		protected void renderWidget(@NonNull GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		}

		@Override
		protected void updateWidgetNarration(@NonNull NarrationElementOutput narration) {
		}
	}

	private static final class CacheActionWidget extends AbstractWidget {
		private final Button button;

		private CacheActionWidget(@NonNull Component label, @NonNull Runnable action) {
			super(0, 0, 0, ENTRY_HEIGHT, Component.empty());
			Component safeLabel = Objects.requireNonNull(label, "label");
			Runnable safeAction = Objects.requireNonNull(action, "action");
			this.button = Button.builder(safeLabel, btn -> safeAction.run())
					.bounds(0, 0, 0, ENTRY_HEIGHT)
					.build();
		}

		@Override
		protected void renderWidget(@NonNull GuiGraphics graphics, int mouseX, int mouseY, float delta) {
			CacheSnapshot snapshot = CacheManager.snapshot();
			this.button.active = snapshot.ready() && snapshot.totalBytes() > 0;
			this.button.setX(this.getX());
			this.button.setY(this.getY());
			this.button.setWidth(this.width);
			this.button.setHeight(this.height);
			this.button.render(graphics, mouseX, mouseY, delta);
		}

		@Override
		public void onClick(@NonNull MouseButtonEvent event, boolean doubleClick) {
			this.button.mouseClicked(event, doubleClick);
		}

		@Override
		protected void onDrag(@NonNull MouseButtonEvent event, double deltaX, double deltaY) {
			this.button.mouseDragged(event, deltaX, deltaY);
		}

		@Override
		public void onRelease(@NonNull MouseButtonEvent event) {
			this.button.mouseReleased(event);
		}

		@Override
		protected void updateWidgetNarration(@NonNull NarrationElementOutput narration) {
		}
	}

	private enum CacheMetric {
		OSM("tellus.cache.section.osm", "tellus/cache/map"),
		ESA("tellus.cache.section.esa", "tellus/cache/worldcover2021"),
		TERRAIN("tellus.cache.section.terrain", "tellus/cache/elevation-tellus"),
		TOTAL("tellus.cache.section.total", null);

		private final @NonNull String labelKey;
		private final @Nullable String relativePath;

		CacheMetric(@NonNull String labelKey, @Nullable String relativePath) {
			this.labelKey = Objects.requireNonNull(labelKey, "labelKey");
			this.relativePath = relativePath;
		}

		private @NonNull Component label() {
			return Objects.requireNonNull(Component.translatable(this.labelKey), "cacheLabel");
		}

		private @Nullable Path resolvePath() {
			if (this.relativePath == null) {
				return null;
			}
			return Minecraft.getInstance().gameDirectory.toPath().resolve(this.relativePath);
		}
	}

	private record CacheSnapshot(boolean ready, long osmBytes, long esaBytes, long terrainBytes) {
		private static CacheSnapshot empty() {
			return new CacheSnapshot(false, 0L, 0L, 0L);
		}

		private long bytesFor(CacheMetric metric) {
			return switch (metric) {
				case OSM -> this.osmBytes;
				case ESA -> this.esaBytes;
				case TERRAIN -> this.terrainBytes;
				case TOTAL -> totalBytes();
			};
		}

		private long totalBytes() {
			return this.osmBytes + this.esaBytes + this.terrainBytes;
		}
	}

	private static final class CacheManager {
		private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(new CacheThreadFactory());
		private static final AtomicReference<CacheSnapshot> SNAPSHOT = new AtomicReference<>(CacheSnapshot.empty());
		private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);

		private static CacheSnapshot snapshot() {
			return SNAPSHOT.get();
		}

		private static void requestRefresh() {
			refreshAsync(null);
		}

		private static void delete(CacheMetric metric) {
			Path path = metric.resolvePath();
			if (path == null) {
				return;
			}
			refreshAsync(() -> deleteDirectory(path));
		}

		private static void deleteAll() {
			refreshAsync(() -> {
				for (CacheMetric metric : CacheMetric.values()) {
					Path path = metric.resolvePath();
					if (path != null) {
						deleteDirectory(path);
					}
				}
			});
		}

		private static void refreshAsync(@Nullable Runnable task) {
			if (!IN_FLIGHT.compareAndSet(false, true)) {
				return;
			}
			SNAPSHOT.set(CacheSnapshot.empty());
			CompletableFuture.supplyAsync(() -> {
				if (task != null) {
					task.run();
				}
				return computeSnapshot();
			}, EXECUTOR)
					.whenComplete((snapshot, error) -> {
						if (snapshot != null && error == null) {
							SNAPSHOT.set(snapshot);
						} else {
							SNAPSHOT.set(CacheSnapshot.empty());
						}
						IN_FLIGHT.set(false);
					});
		}

		private static CacheSnapshot computeSnapshot() {
			long osmBytes = sizeFor(CacheMetric.OSM);
			long esaBytes = sizeFor(CacheMetric.ESA);
			long terrainBytes = sizeFor(CacheMetric.TERRAIN);
			return new CacheSnapshot(true, osmBytes, esaBytes, terrainBytes);
		}

		private static long sizeFor(CacheMetric metric) {
			Path path = metric.resolvePath();
			if (path == null || !Files.exists(path)) {
				return 0L;
			}
			try (var stream = Files.walk(path)) {
				return stream.filter(Files::isRegularFile)
						.mapToLong(file -> {
							try {
								return Files.size(file);
							} catch (IOException e) {
								return 0L;
							}
						})
						.sum();
			} catch (IOException e) {
				Tellus.LOGGER.warn("Failed to scan cache at {}", path, e);
				return 0L;
			}
		}

		private static void deleteDirectory(Path root) {
			if (!Files.exists(root)) {
				return;
			}
			try (var stream = Files.walk(root)) {
				stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
						.forEach(path -> {
							try {
								Files.deleteIfExists(path);
							} catch (IOException e) {
								Tellus.LOGGER.warn("Failed to delete cache path {}", path, e);
							}
						});
			} catch (IOException e) {
				Tellus.LOGGER.warn("Failed to delete cache folder {}", root, e);
			}
		}
	}

	private static final class CacheThreadFactory implements ThreadFactory {
		private int index;

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, "tellus-cache-" + (++this.index));
			thread.setDaemon(true);
			return thread;
		}
	}

	private static final class ModeDefinition implements SettingDefinition {
		private static final @NonNull List<EarthGeneratorSettings.DistantHorizonsRenderMode> MODES = createModes();

		private final String key;
		private EarthGeneratorSettings.DistantHorizonsRenderMode value;
		private boolean locked;

		private static @NonNull List<EarthGeneratorSettings.DistantHorizonsRenderMode> createModes() {
			List<EarthGeneratorSettings.DistantHorizonsRenderMode> modes = new ArrayList<>(2);
			modes.add(EarthGeneratorSettings.DistantHorizonsRenderMode.FAST);
			modes.add(EarthGeneratorSettings.DistantHorizonsRenderMode.DETAILED);
			return modes;
		}

		private ModeDefinition(String key, EarthGeneratorSettings.DistantHorizonsRenderMode defaultValue) {
			this.key = key;
			this.value = defaultValue;
		}

		@Override
		public AbstractWidget createWidget(Runnable onChange) {
			Component name = settingName(this.key);
			Component tooltip = this.locked
					? workInProgressTooltip(this.key)
					: settingTooltip(this.key);
			CycleButton.Builder<EarthGeneratorSettings.DistantHorizonsRenderMode> builder = CycleButton.builder(
					EarthCustomizeScreen::formatRenderMode,
					this.value).withValues(MODES).withTooltip(value -> Tooltip.create(tooltip));
			CycleButton<EarthGeneratorSettings.DistantHorizonsRenderMode> button = builder.create(
					0,
					0,
					0,
					ENTRY_HEIGHT,
					name,
					(btn, value) -> {
						this.value = value;
						onChange.run();
					});
			button.active = !this.locked;
			return button;
		}
	}

	private static final class SliderDefinition implements SettingDefinition {
		private final String key;
		private final double min;
		private final double max;
		private final double step;
		private double value;
		private DoubleFunction<String> display;
		private SliderScale scale = SliderScale.linear();
		private boolean locked;

		private SliderDefinition(String key, double defaultValue, double min, double max, double step) {
			this.key = key;
			this.value = defaultValue;
			this.min = min;
			this.max = max;
			this.step = step;
		}

		private SliderDefinition withDisplay(DoubleFunction<String> display) {
			this.display = display;
			return this;
		}

		private SliderDefinition withScale(SliderScale scale) {
			this.scale = scale;
			return this;
		}

		private SliderDefinition locked(boolean locked) {
			this.locked = locked;
			return this;
		}

		@Override
		public AbstractWidget createWidget(Runnable onChange) {
			EarthSlider slider = new EarthSlider(0, 0, 0, ENTRY_HEIGHT, this, onChange);
			Component tooltip = this.locked
					? workInProgressTooltip(this.key)
					: settingTooltip(this.key);
			slider.setTooltip(Tooltip.create(tooltip));
			slider.active = !this.locked;
			return slider;
		}
	}

	private static final class EarthSlider extends AbstractSliderButton {
		private static final double EPSILON = 1.0e-6;

		private final SliderDefinition definition;
		private final Runnable onChange;

		private EarthSlider(int x, int y, int width, int height, SliderDefinition definition, Runnable onChange) {
			super(x, y, width, height, Component.empty(), 0.0);
			this.definition = definition;
			this.onChange = onChange;
			this.value = this.toPosition(definition.value);
			this.updateMessage();
		}

		@Override
		protected void updateMessage() {
			double value = this.toValue(this.value);
			String fallback = Objects.requireNonNull(String.format(Locale.ROOT, "%.2f", value), "formattedValue");
			String valueText = this.definition.display != null
					? Objects.requireNonNullElse(this.definition.display.apply(value), fallback)
					: fallback;
			MutableComponent message = settingName(this.definition.key)
					.copy()
					.append(": ")
					.append(Component.literal(Objects.requireNonNull(valueText, "valueText")));
			this.setMessage(message);
		}

		@Override
		protected void applyValue() {
			double rawValue = this.toValue(this.value);
			double snappedValue = this.snap(rawValue, this.definition.step);
			if (Math.abs(snappedValue - rawValue) > EPSILON) {
				this.value = this.toPosition(snappedValue);
			}
			if (Math.abs(this.definition.value - snappedValue) > EPSILON) {
				this.definition.value = snappedValue;
				this.onChange.run();
			}
		}

		private double snap(double value, double step) {
			double effectiveStep = step;
			if ("world_scale".equals(this.definition.key) && isShiftDown()) {
				effectiveStep = Math.max(1.0, step / 5.0);
			}
			if (effectiveStep <= 0.0) {
				return Mth.clamp(value, this.definition.min, this.definition.max);
			}
			if ("world_scale".equals(this.definition.key)) {
				double firstStep = this.definition.min;
				double cutoff = (firstStep + effectiveStep) * 0.5;
				if (value <= cutoff) {
					return firstStep;
				}
				double snapped = Math.round(value / effectiveStep) * effectiveStep;
				double adjusted = Math.max(effectiveStep, snapped);
				return Mth.clamp(adjusted, this.definition.min, this.definition.max);
			}
			double snapped = this.definition.min
					+ Math.round((value - this.definition.min) / effectiveStep) * effectiveStep;
			return Mth.clamp(snapped, this.definition.min, this.definition.max);
		}

		private double toPosition(double value) {
			double position = (Mth.clamp(value, this.definition.min, this.definition.max) - this.definition.min)
					/ (this.definition.max - this.definition.min);
			return this.definition.scale.reverse(position);
		}

		private double toValue(double position) {
			double scaled = this.definition.scale.apply(position);
			return this.definition.min + (this.definition.max - this.definition.min) * Mth.clamp(scaled, 0.0, 1.0);
		}
	}

	private static boolean isShiftDown() {
		return InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
				|| InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
	}

	private interface SliderScale {
		double apply(double value);

		double reverse(double value);

		static SliderScale linear() {
			return new SliderScale() {
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

		static SliderScale power(double power) {
			return new SliderScale() {
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
}
