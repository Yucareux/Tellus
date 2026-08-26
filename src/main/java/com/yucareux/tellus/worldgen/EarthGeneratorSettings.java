package com.yucareux.tellus.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import com.mojang.serialization.MapEncoder.Implementation;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.util.Mth;
import net.minecraft.world.level.dimension.DimensionType;

public record EarthGeneratorSettings(
   double worldScale,
   double terrestrialHeightScale,
   double oceanicHeightScale,
   int heightOffset,
   double spawnLatitude,
   double spawnLongitude,
   int minAltitude,
   int maxAltitude,
   int riverLakeShorelineBlend,
   int oceanShorelineBlend,
   boolean shorelineBlendCliffLimit,
   boolean caveGeneration,
   boolean oreDistribution,
   boolean geologicalStonePatches,
   boolean lavaPools,
   boolean addStrongholds,
   boolean addVillages,
   boolean addMineshafts,
   boolean addOceanMonuments,
   boolean addWoodlandMansions,
   boolean addDesertTemples,
   boolean addJungleTemples,
   boolean addPillagerOutposts,
   boolean addRuinedPortals,
   boolean addShipwrecks,
   boolean addOceanRuins,
   boolean addBuriedTreasure,
   boolean addIgloos,
   boolean addWitchHuts,
   boolean addAncientCities,
   boolean addTrialChambers,
   boolean addTrailRuins,
   boolean deepDark,
   boolean geodes,
   boolean distantHorizonsWaterResolver,
   boolean distantHorizonsOsmFeatures,
   int distantHorizonsOsmRoadMaxDetail,
   int distantHorizonsOsmBuildingMaxDetail,
   boolean distantHorizonsOsmNonBlockingFetch,
   boolean realtimeTime,
   boolean realtimeWeather,
   boolean historicalSnow,
   boolean voxyChunkPregenEnabled,
   int voxyChunkPregenMaxRadius,
   int voxyChunkPregenChunksPerTick,
   EarthGeneratorSettings.DistantHorizonsRenderMode distantHorizonsRenderMode,
   EarthGeneratorSettings.DemSelection demSelection,
   boolean enableRoads,
   boolean enableBuildings,
   boolean enableWater,
   boolean thinShellTerrain,
   boolean climateBasedBuiltUpTerrain,
   boolean randomBiomes,
   double randomBiomeDensity,
   long randomBiomeSeed,
   List<String> randomBiomeIds,
   boolean experimentalIncreaseHeight,
   boolean tellusManagedTerrainDownloads,
   boolean showTerrainDownloadOverlay,
   boolean cavesReachSurface,
   int undergroundDepth,
   boolean customTrees,
   boolean automaticHeightScaling,
   boolean hugeRedMushrooms,
   boolean worldScaleAtSpawn,
   boolean centerWorldOnSpawn
) {
   public static final double DEFAULT_SPAWN_LATITUDE = 27.9881;
   public static final double DEFAULT_SPAWN_LONGITUDE = 86.925;
   public static final int AUTO_ALTITUDE = Integer.MIN_VALUE;
   public static final int MIN_WORLD_Y = -2032;
   public static final int MAX_WORLD_HEIGHT = 4064;
   public static final int MAX_WORLD_Y = 2031;
   public static final int EXPERIMENTAL_HEIGHT_OFFSET = 0;
   public static final int EXPERIMENTAL_DEEP_OCEAN_HEIGHT_OFFSET = 1_392;
   public static final int MIN_UNDERGROUND_DEPTH = 64;
   public static final int MAX_UNDERGROUND_DEPTH = 512;
   public static final int DEFAULT_UNDERGROUND_DEPTH = 256;
   private static final int ALTITUDE_TOLERANCE = 50;
   private static final int HEIGHT_ALIGNMENT = 16;
   private static final double EVEREST_ELEVATION_METERS = 8848.0;
   private static final double MARIANA_TRENCH_METERS = -11034.0;
   public static final double MAX_WORLD_SCALE = 1000.0;
   private static final int MAX_VOXY_PREGEN_RADIUS = 1024;
   private static final int MAX_VOXY_PREGEN_CHUNKS_PER_TICK = 200;
   private static final int MAX_DH_OSM_DETAIL = 24;
   private static final boolean FIXED_DH_OSM_FEATURES = true;
   private static final int FIXED_DH_OSM_ROAD_MAX_DETAIL = 6;
   private static final int FIXED_DH_OSM_BUILDING_MAX_DETAIL = 6;
   private static final boolean FIXED_DH_OSM_NON_BLOCKING_FETCH = true;
   public static final double DEFAULT_RANDOM_BIOME_DENSITY = 0.12;
   public static final long DEFAULT_RANDOM_BIOME_SEED = 0L;
   private static final double MIN_RANDOM_BIOME_DENSITY = 0.0;
   private static final double MAX_RANDOM_BIOME_DENSITY = 0.4;
   public static final boolean DEFAULT_AUTOMATIC_HEIGHT_SCALING = true;
   /**
    * When enabled, the World Scale control in the UI is interpreted as real-world metres per block at the
    * spawn point instead of at the equator. The serialized {@code world_scale} always stays the equatorial
    * value so terrain generation is unaffected; this flag only changes how the UI presents and edits it.
    */
   public static final boolean DEFAULT_WORLD_SCALE_AT_SPAWN = false;
   /**
    * When enabled, block (0, 0) is the spawn point instead of 0°N 0°E (see {@link WorldProjection}). This
    * changes where every real-world place lands in block space, so it is fixed when the world is created
    * and defaults to off so existing worlds keep their layout.
    */
   public static final boolean DEFAULT_CENTER_WORLD_ON_SPAWN = false;
   public static final EarthGeneratorSettings DEFAULT = new EarthGeneratorSettings(
      30.0,
      1.0,
      1.0,
      64,
      27.9881,
      86.925,
      -64,
      Integer.MIN_VALUE,
      5,
      5,
      true,
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
      false,
      true,
      FIXED_DH_OSM_FEATURES,
      FIXED_DH_OSM_ROAD_MAX_DETAIL,
      FIXED_DH_OSM_BUILDING_MAX_DETAIL,
      FIXED_DH_OSM_NON_BLOCKING_FETCH,
      false,
      false,
      false,
      false,
      96,
      4,
      EarthGeneratorSettings.DistantHorizonsRenderMode.FAST,
      EarthGeneratorSettings.DemSelection.mapterhornSelection(),
      true,
      true,
      true,
      false,
      false,
      false,
      DEFAULT_RANDOM_BIOME_DENSITY,
      DEFAULT_RANDOM_BIOME_SEED,
      MinecraftVersionCompat.defaultRandomBiomeIds(),
      false,
      false,
      false,
      false,
      DEFAULT_UNDERGROUND_DEPTH,
      true,
      DEFAULT_AUTOMATIC_HEIGHT_SCALING,
      false,
      DEFAULT_WORLD_SCALE_AT_SPAWN,
      DEFAULT_CENTER_WORLD_ON_SPAWN
   );
   private static final MapCodec<EarthGeneratorSettings.BaseToggles> BASE_TOGGLES_CODEC = RecordCodecBuilder.mapCodec(
      instance -> instance.group(
            Codec.BOOL.optionalFieldOf("cave_generation").forGetter(EarthGeneratorSettings.BaseToggles::caveGeneration),
            Codec.BOOL.optionalFieldOf("cave_carvers").forGetter(EarthGeneratorSettings.BaseToggles::caveCarvers),
            Codec.BOOL.optionalFieldOf("large_caves").forGetter(EarthGeneratorSettings.BaseToggles::largeCaves),
            Codec.BOOL.optionalFieldOf("canyon_carvers").forGetter(EarthGeneratorSettings.BaseToggles::canyonCarvers),
            Codec.BOOL.fieldOf("ore_distribution").orElse(DEFAULT.oreDistribution()).forGetter(EarthGeneratorSettings.BaseToggles::oreDistribution),
            Codec.BOOL
               .fieldOf("geological_stone_patches")
               .orElse(false)
               .forGetter(EarthGeneratorSettings.BaseToggles::geologicalStonePatches),
            Codec.BOOL.fieldOf("lava_pools").orElse(DEFAULT.lavaPools()).forGetter(EarthGeneratorSettings.BaseToggles::lavaPools)
         )
         .apply(instance, EarthGeneratorSettings::createBaseToggles)
   );
   private static final MapCodec<EarthGeneratorSettings.SettingsBase> BASE_CODEC = RecordCodecBuilder.mapCodec(
      instance -> instance.group(
            Codec.DOUBLE.fieldOf("world_scale").orElse(DEFAULT.worldScale()).forGetter(EarthGeneratorSettings.SettingsBase::worldScale),
            Codec.DOUBLE
               .fieldOf("terrestrial_height_scale")
               .orElse(DEFAULT.terrestrialHeightScale())
               .forGetter(EarthGeneratorSettings.SettingsBase::terrestrialHeightScale),
            Codec.DOUBLE
               .fieldOf("oceanic_height_scale")
               .orElse(DEFAULT.oceanicHeightScale())
               .forGetter(EarthGeneratorSettings.SettingsBase::oceanicHeightScale),
            Codec.INT.fieldOf("height_offset").orElse(DEFAULT.heightOffset()).forGetter(EarthGeneratorSettings.SettingsBase::heightOffset),
            Codec.DOUBLE.fieldOf("spawn_latitude").orElse(DEFAULT.spawnLatitude()).forGetter(EarthGeneratorSettings.SettingsBase::spawnLatitude),
            Codec.DOUBLE.fieldOf("spawn_longitude").orElse(DEFAULT.spawnLongitude()).forGetter(EarthGeneratorSettings.SettingsBase::spawnLongitude),
            Codec.INT.fieldOf("min_altitude").orElse(DEFAULT.minAltitude()).forGetter(EarthGeneratorSettings.SettingsBase::minAltitude),
            Codec.INT.fieldOf("max_altitude").orElse(DEFAULT.maxAltitude()).forGetter(EarthGeneratorSettings.SettingsBase::maxAltitude),
            Codec.INT
               .fieldOf("river_lake_shoreline_blend")
               .orElse(DEFAULT.riverLakeShorelineBlend())
               .forGetter(EarthGeneratorSettings.SettingsBase::riverLakeShorelineBlend),
            Codec.INT
               .fieldOf("ocean_shoreline_blend")
               .orElse(DEFAULT.oceanShorelineBlend())
               .forGetter(EarthGeneratorSettings.SettingsBase::oceanShorelineBlend),
            Codec.BOOL
               .fieldOf("shoreline_blend_cliff_limit")
               .orElse(DEFAULT.shorelineBlendCliffLimit())
               .forGetter(EarthGeneratorSettings.SettingsBase::shorelineBlendCliffLimit),
            BASE_TOGGLES_CODEC.forGetter(
               settings -> new EarthGeneratorSettings.BaseToggles(
                  Optional.of(settings.caveGeneration()),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  settings.oreDistribution(),
                  settings.geologicalStonePatches(),
                  settings.lavaPools()
               )
            )
         )
         .apply(
            instance,
            (worldScale, terrestrialHeightScale, oceanicHeightScale, heightOffset, spawnLatitude, spawnLongitude, minAltitude, maxAltitude, riverLakeShorelineBlend, oceanShorelineBlend, shorelineBlendCliffLimit, toggles) -> createSettingsBase(
               worldScale,
               terrestrialHeightScale,
               oceanicHeightScale,
               heightOffset,
               spawnLatitude,
               spawnLongitude,
               minAltitude,
               maxAltitude,
               riverLakeShorelineBlend,
               oceanShorelineBlend,
               shorelineBlendCliffLimit,
               toggles.resolveCaveGeneration(),
               toggles.oreDistribution(),
               toggles.geologicalStonePatches(),
               toggles.lavaPools()
            )
         )
   );
   private static final MapCodec<EarthGeneratorSettings.DistantHorizonsRenderMode> DISTANT_HORIZONS_RENDER_MODE_CODEC = EarthGeneratorSettings.DistantHorizonsRenderMode.CODEC
      .fieldOf("distant_horizons_render_mode")
      .orElse(DEFAULT.distantHorizonsRenderMode());
   private static final MapCodec<Boolean> DEM_AUTOMATIC_FIELD_CODEC = Codec.BOOL.fieldOf("dem_automatic");
   private static final MapCodec<List<String>> DEM_ENABLED_PROVIDERS_FIELD_CODEC = Codec.STRING.listOf().fieldOf("dem_enabled_providers");
   private static final MapCodec<EarthGeneratorSettings.DemProvider> LEGACY_DEM_PROVIDER_CODEC = EarthGeneratorSettings.DemProvider.CODEC
      .fieldOf("dem_provider")
      .orElse(EarthGeneratorSettings.DemProvider.AUTO);
   private static final MapCodec<EarthGeneratorSettings.DemSelection> DEM_SELECTION_CODEC = MapCodec.of(
      new Implementation<EarthGeneratorSettings.DemSelection>() {
         public <T> RecordBuilder<T> encode(EarthGeneratorSettings.DemSelection input, DynamicOps<T> ops, RecordBuilder<T> prefix) {
            RecordBuilder<T> builder = EarthGeneratorSettings.DEM_AUTOMATIC_FIELD_CODEC.encode(input.automatic(), ops, prefix);
            return EarthGeneratorSettings.DEM_ENABLED_PROVIDERS_FIELD_CODEC.encode(input.enabledProviderIds(), ops, builder);
         }

         public <T> Stream<T> keys(DynamicOps<T> ops) {
            return Stream.concat(
               EarthGeneratorSettings.DEM_AUTOMATIC_FIELD_CODEC.keys(ops), EarthGeneratorSettings.DEM_ENABLED_PROVIDERS_FIELD_CODEC.keys(ops)
            );
         }
      },
      new com.mojang.serialization.MapDecoder.Implementation<EarthGeneratorSettings.DemSelection>() {
         public <T> DataResult<EarthGeneratorSettings.DemSelection> decode(DynamicOps<T> ops, MapLike<T> input) {
            boolean hasAutomatic = input.get("dem_automatic") != null;
            boolean hasEnabledProviders = input.get("dem_enabled_providers") != null;
            if (hasAutomatic || hasEnabledProviders) {
               DataResult<Boolean> automatic = hasAutomatic
                  ? EarthGeneratorSettings.DEM_AUTOMATIC_FIELD_CODEC.decode(ops, input)
                  : DataResult.success(!hasEnabledProviders ? EarthGeneratorSettings.DEFAULT.demSelection().automatic() : false);
               DataResult<List<String>> enabledProviders = hasEnabledProviders
                  ? EarthGeneratorSettings.DEM_ENABLED_PROVIDERS_FIELD_CODEC.decode(ops, input)
                  : DataResult.success(EarthGeneratorSettings.DEFAULT.demSelection().enabledProviderIds());
               return automatic.apply2(EarthGeneratorSettings.DemSelection::fromSerializedIds, enabledProviders);
            }

            return input.get("dem_provider") != null
               ? EarthGeneratorSettings.LEGACY_DEM_PROVIDER_CODEC.decode(ops, input).map(EarthGeneratorSettings.DemSelection::fromLegacyProvider)
               : DataResult.success(EarthGeneratorSettings.DEFAULT.demSelection());
         }

         public <T> Stream<T> keys(DynamicOps<T> ops) {
            Stream<T> currentKeys = Stream.concat(
               EarthGeneratorSettings.DEM_AUTOMATIC_FIELD_CODEC.keys(ops), EarthGeneratorSettings.DEM_ENABLED_PROVIDERS_FIELD_CODEC.keys(ops)
            );
            return Stream.concat(currentKeys, EarthGeneratorSettings.LEGACY_DEM_PROVIDER_CODEC.keys(ops));
         }
      }
   );
   private static final MapCodec<Boolean> DISTANT_HORIZONS_WATER_RESOLVER_CODEC = Codec.BOOL
      .fieldOf("distant_horizons_water_resolver")
      .orElse(DEFAULT.distantHorizonsWaterResolver());
   private static final MapCodec<Boolean> DISTANT_HORIZONS_OSM_FEATURES_CODEC = Codec.BOOL
      .fieldOf("distant_horizons_osm_features")
      .orElse(DEFAULT.distantHorizonsOsmFeatures());
   private static final MapCodec<Integer> DISTANT_HORIZONS_OSM_ROAD_MAX_DETAIL_CODEC = Codec.intRange(0, MAX_DH_OSM_DETAIL)
      .fieldOf("distant_horizons_osm_road_max_detail")
      .orElse(DEFAULT.distantHorizonsOsmRoadMaxDetail());
   private static final MapCodec<Integer> DISTANT_HORIZONS_OSM_BUILDING_MAX_DETAIL_CODEC = Codec.intRange(0, MAX_DH_OSM_DETAIL)
      .fieldOf("distant_horizons_osm_building_max_detail")
      .orElse(DEFAULT.distantHorizonsOsmBuildingMaxDetail());
   private static final MapCodec<Boolean> DISTANT_HORIZONS_OSM_NON_BLOCKING_FETCH_CODEC = Codec.BOOL
      .fieldOf("distant_horizons_osm_non_blocking_fetch")
      .orElse(DEFAULT.distantHorizonsOsmNonBlockingFetch());
   private static final MapCodec<Boolean> TELLUS_MANAGED_TERRAIN_DOWNLOADS_CODEC = Codec.BOOL
      .fieldOf("tellus_managed_terrain_downloads")
      .orElse(false);
   private static final MapCodec<Boolean> SHOW_TERRAIN_DOWNLOAD_OVERLAY_CODEC = Codec.BOOL
      .fieldOf("show_terrain_download_overlay")
      .orElse(false);
   private static final MapCodec<Boolean> CAVES_REACH_SURFACE_CODEC = Codec.BOOL
      .fieldOf("caves_reach_surface")
      .orElse(DEFAULT.cavesReachSurface());
   private static final MapCodec<Integer> UNDERGROUND_DEPTH_CODEC = Codec.intRange(MIN_UNDERGROUND_DEPTH, MAX_UNDERGROUND_DEPTH)
      .fieldOf("underground_depth")
      .orElse(DEFAULT.undergroundDepth());
   private static final MapCodec<Boolean> CUSTOM_TREES_CODEC = Codec.BOOL
      .fieldOf("custom_trees")
      .orElse(DEFAULT.customTrees());
   private static final MapCodec<Boolean> HUGE_RED_MUSHROOMS_CODEC = Codec.BOOL
      .fieldOf("huge_red_mushrooms")
      .orElse(DEFAULT.hugeRedMushrooms());
   // Worlds saved before this field existed must keep the equatorial World Scale presentation.
   private static final MapCodec<Boolean> WORLD_SCALE_AT_SPAWN_CODEC = Codec.BOOL
      .fieldOf("world_scale_at_spawn")
      .orElse(false);
   // Worlds saved before this field existed were generated with block (0, 0) at 0°N 0°E.
   private static final MapCodec<Boolean> CENTER_WORLD_ON_SPAWN_CODEC = Codec.BOOL
      .fieldOf("center_world_on_spawn")
      .orElse(false);
   private static final MapCodec<Boolean> REALTIME_TIME_CODEC = Codec.BOOL.fieldOf("realtime_time").orElse(DEFAULT.realtimeTime());
   private static final MapCodec<Boolean> REALTIME_WEATHER_CODEC = Codec.BOOL.fieldOf("realtime_weather").orElse(DEFAULT.realtimeWeather());
   private static final MapCodec<Boolean> HISTORICAL_SNOW_CODEC = Codec.BOOL.fieldOf("historical_snow").orElse(DEFAULT.historicalSnow());
   private static final MapCodec<Boolean> ENABLE_ROADS_CODEC = Codec.BOOL.fieldOf("enable_roads").orElse(DEFAULT.enableRoads());
   private static final MapCodec<Boolean> ENABLE_BUILDINGS_CODEC = Codec.BOOL.fieldOf("enable_buildings").orElse(DEFAULT.enableBuildings());
   private static final MapCodec<Boolean> ENABLE_WATER_CODEC = Codec.BOOL.fieldOf("enable_water").orElse(DEFAULT.enableWater());
   private static final MapCodec<Boolean> THIN_SHELL_TERRAIN_CODEC = Codec.BOOL.fieldOf("thin_shell_terrain").orElse(DEFAULT.thinShellTerrain());
   private static final MapCodec<Boolean> CLIMATE_BASED_BUILT_UP_TERRAIN_CODEC = Codec.BOOL
      .fieldOf("climate_based_built_up_terrain")
      .orElse(DEFAULT.climateBasedBuiltUpTerrain());
   private static final MapCodec<Boolean> RANDOM_BIOMES_CODEC = Codec.BOOL.fieldOf("random_biomes").orElse(DEFAULT.randomBiomes());
   private static final MapCodec<Double> RANDOM_BIOME_DENSITY_CODEC = Codec.DOUBLE
      .fieldOf("random_biome_density")
      .orElse(DEFAULT.randomBiomeDensity());
   private static final MapCodec<Long> RANDOM_BIOME_SEED_CODEC = Codec.LONG.fieldOf("random_biome_seed").orElse(DEFAULT.randomBiomeSeed());
   private static final MapCodec<List<String>> RANDOM_BIOME_IDS_CODEC = Codec.STRING
      .listOf()
      .fieldOf("random_biome_ids")
      .orElse(DEFAULT.randomBiomeIds());
   private static final MapCodec<Boolean> EXPERIMENTAL_INCREASE_HEIGHT_CODEC = Codec.BOOL
      .fieldOf("experimental_increase_height")
      .orElse(DEFAULT.experimentalIncreaseHeight());
   private static final MapCodec<Boolean> AUTOMATIC_HEIGHT_SCALING_CODEC = Codec.BOOL
      .fieldOf("automatic_height_scaling")
      .orElse(DEFAULT_AUTOMATIC_HEIGHT_SCALING);
   private static final MapCodec<Optional<String>> EXPERIMENTAL_HEIGHT_COORDINATE_PROFILE_CODEC = Codec.STRING
      .optionalFieldOf("experimental_height_coordinate_profile");
   private static final MapCodec<Boolean> VOXY_CHUNK_PREGEN_ENABLED_CODEC = Codec.BOOL
      .fieldOf("voxy_chunk_pregen_enabled")
      .orElse(DEFAULT.voxyChunkPregenEnabled());
   private static final MapCodec<Integer> VOXY_CHUNK_PREGEN_MAX_RADIUS_CODEC = Codec.intRange(0, MAX_VOXY_PREGEN_RADIUS)
      .fieldOf("voxy_chunk_pregen_max_radius")
      .orElse(DEFAULT.voxyChunkPregenMaxRadius());
   private static final MapCodec<Integer> VOXY_CHUNK_PREGEN_CHUNKS_PER_TICK_CODEC = Codec.intRange(1, MAX_VOXY_PREGEN_CHUNKS_PER_TICK)
      .fieldOf("voxy_chunk_pregen_chunks_per_tick")
      .orElse(DEFAULT.voxyChunkPregenChunksPerTick());
   private static final MapCodec<Boolean> DEEP_DARK_CODEC = Codec.BOOL.fieldOf("deep_dark").orElse(DEFAULT.deepDark());
   private static final MapCodec<Boolean> GEODES_CODEC = Codec.BOOL.fieldOf("geodes").orElse(DEFAULT.geodes());
   private static final MapCodec<EarthGeneratorSettings.StructureSettings> STRUCTURE_CODEC = RecordCodecBuilder.mapCodec(
      instance -> instance.group(
            Codec.BOOL.fieldOf("add_strongholds").orElse(DEFAULT.addStrongholds()).forGetter(EarthGeneratorSettings.StructureSettings::addStrongholds),
            Codec.BOOL.fieldOf("add_villages").orElse(DEFAULT.addVillages()).forGetter(EarthGeneratorSettings.StructureSettings::addVillages),
            Codec.BOOL.fieldOf("add_mineshafts").orElse(DEFAULT.addMineshafts()).forGetter(EarthGeneratorSettings.StructureSettings::addMineshafts),
            Codec.BOOL
               .fieldOf("add_ocean_monuments")
               .orElse(DEFAULT.addOceanMonuments())
               .forGetter(EarthGeneratorSettings.StructureSettings::addOceanMonuments),
            Codec.BOOL
               .fieldOf("add_woodland_mansions")
               .orElse(DEFAULT.addWoodlandMansions())
               .forGetter(EarthGeneratorSettings.StructureSettings::addWoodlandMansions),
            Codec.BOOL.fieldOf("add_desert_temples").orElse(DEFAULT.addDesertTemples()).forGetter(EarthGeneratorSettings.StructureSettings::addDesertTemples),
            Codec.BOOL.fieldOf("add_jungle_temples").orElse(DEFAULT.addJungleTemples()).forGetter(EarthGeneratorSettings.StructureSettings::addJungleTemples),
            Codec.BOOL
               .fieldOf("add_pillager_outposts")
               .orElse(DEFAULT.addPillagerOutposts())
               .forGetter(EarthGeneratorSettings.StructureSettings::addPillagerOutposts),
            Codec.BOOL.fieldOf("add_ruined_portals").orElse(DEFAULT.addRuinedPortals()).forGetter(EarthGeneratorSettings.StructureSettings::addRuinedPortals),
            Codec.BOOL.fieldOf("add_shipwrecks").orElse(DEFAULT.addShipwrecks()).forGetter(EarthGeneratorSettings.StructureSettings::addShipwrecks),
            Codec.BOOL.fieldOf("add_ocean_ruins").orElse(DEFAULT.addOceanRuins()).forGetter(EarthGeneratorSettings.StructureSettings::addOceanRuins),
            Codec.BOOL
               .fieldOf("add_buried_treasure")
               .orElse(DEFAULT.addBuriedTreasure())
               .forGetter(EarthGeneratorSettings.StructureSettings::addBuriedTreasure),
            Codec.BOOL.fieldOf("add_igloos").orElse(DEFAULT.addIgloos()).forGetter(EarthGeneratorSettings.StructureSettings::addIgloos),
            Codec.BOOL.fieldOf("add_witch_huts").orElse(DEFAULT.addWitchHuts()).forGetter(EarthGeneratorSettings.StructureSettings::addWitchHuts),
            Codec.BOOL.fieldOf("add_ancient_cities").orElse(DEFAULT.addAncientCities()).forGetter(EarthGeneratorSettings.StructureSettings::addAncientCities),
            Codec.BOOL.fieldOf("add_trial_chambers").orElse(DEFAULT.addTrialChambers()).forGetter(EarthGeneratorSettings.StructureSettings::addTrialChambers)
         )
         .apply(instance, EarthGeneratorSettings::createStructureSettings)
   );
   private static final MapCodec<Boolean> TRAIL_RUINS_CODEC = Codec.BOOL.fieldOf("add_trail_ruins").orElse(DEFAULT.addTrailRuins());
   private static final MapCodec<EarthGeneratorSettings> MAP_CODEC = MapCodec.of(
      new Implementation<EarthGeneratorSettings>() {
         public <T> RecordBuilder<T> encode(EarthGeneratorSettings input, DynamicOps<T> ops, RecordBuilder<T> prefix) {
            RecordBuilder<T> builder = EarthGeneratorSettings.BASE_CODEC.encode(EarthGeneratorSettings.SettingsBase.fromSettings(input), ops, prefix);
            builder = EarthGeneratorSettings.DISTANT_HORIZONS_RENDER_MODE_CODEC.encode(input.distantHorizonsRenderMode(), ops, builder);
            builder = EarthGeneratorSettings.DEM_SELECTION_CODEC.encode(input.demSelection(), ops, builder);
            builder = EarthGeneratorSettings.DISTANT_HORIZONS_WATER_RESOLVER_CODEC.encode(input.distantHorizonsWaterResolver(), ops, builder);
            builder = EarthGeneratorSettings.DISTANT_HORIZONS_OSM_FEATURES_CODEC.encode(input.distantHorizonsOsmFeatures(), ops, builder);
            builder = EarthGeneratorSettings.DISTANT_HORIZONS_OSM_ROAD_MAX_DETAIL_CODEC.encode(input.distantHorizonsOsmRoadMaxDetail(), ops, builder);
            builder = EarthGeneratorSettings.DISTANT_HORIZONS_OSM_BUILDING_MAX_DETAIL_CODEC.encode(input.distantHorizonsOsmBuildingMaxDetail(), ops, builder);
            builder = EarthGeneratorSettings.DISTANT_HORIZONS_OSM_NON_BLOCKING_FETCH_CODEC.encode(input.distantHorizonsOsmNonBlockingFetch(), ops, builder);
            builder = EarthGeneratorSettings.REALTIME_TIME_CODEC.encode(input.realtimeTime(), ops, builder);
            builder = EarthGeneratorSettings.REALTIME_WEATHER_CODEC.encode(input.realtimeWeather(), ops, builder);
            builder = EarthGeneratorSettings.HISTORICAL_SNOW_CODEC.encode(input.historicalSnow(), ops, builder);
            builder = EarthGeneratorSettings.ENABLE_ROADS_CODEC.encode(input.enableRoads(), ops, builder);
            builder = EarthGeneratorSettings.ENABLE_BUILDINGS_CODEC.encode(input.enableBuildings(), ops, builder);
            builder = EarthGeneratorSettings.ENABLE_WATER_CODEC.encode(input.enableWater(), ops, builder);
            builder = EarthGeneratorSettings.THIN_SHELL_TERRAIN_CODEC.encode(input.thinShellTerrain(), ops, builder);
            builder = EarthGeneratorSettings.CLIMATE_BASED_BUILT_UP_TERRAIN_CODEC.encode(input.climateBasedBuiltUpTerrain(), ops, builder);
            builder = EarthGeneratorSettings.RANDOM_BIOMES_CODEC.encode(input.randomBiomes(), ops, builder);
            builder = EarthGeneratorSettings.RANDOM_BIOME_DENSITY_CODEC.encode(input.randomBiomeDensity(), ops, builder);
            builder = EarthGeneratorSettings.RANDOM_BIOME_SEED_CODEC.encode(input.randomBiomeSeed(), ops, builder);
            builder = EarthGeneratorSettings.RANDOM_BIOME_IDS_CODEC.encode(input.randomBiomeIds(), ops, builder);
            builder = EarthGeneratorSettings.EXPERIMENTAL_INCREASE_HEIGHT_CODEC.encode(input.experimentalIncreaseHeight(), ops, builder);
            builder = EarthGeneratorSettings.AUTOMATIC_HEIGHT_SCALING_CODEC.encode(input.automaticHeightScaling(), ops, builder);
            builder = EarthGeneratorSettings.EXPERIMENTAL_HEIGHT_COORDINATE_PROFILE_CODEC.encode(
               input.experimentalIncreaseHeight() ? Optional.of(HighYPackedCoordinateProfile.PROFILE_ID) : Optional.empty(), ops, builder
            );
            builder = EarthGeneratorSettings.VOXY_CHUNK_PREGEN_ENABLED_CODEC.encode(input.voxyChunkPregenEnabled(), ops, builder);
            builder = EarthGeneratorSettings.VOXY_CHUNK_PREGEN_MAX_RADIUS_CODEC.encode(input.voxyChunkPregenMaxRadius(), ops, builder);
            builder = EarthGeneratorSettings.VOXY_CHUNK_PREGEN_CHUNKS_PER_TICK_CODEC.encode(input.voxyChunkPregenChunksPerTick(), ops, builder);
            builder = EarthGeneratorSettings.DEEP_DARK_CODEC.encode(input.deepDark(), ops, builder);
            builder = EarthGeneratorSettings.GEODES_CODEC.encode(input.geodes(), ops, builder);
            builder = EarthGeneratorSettings.STRUCTURE_CODEC.encode(EarthGeneratorSettings.StructureSettings.fromSettings(input), ops, builder);
            builder = EarthGeneratorSettings.TRAIL_RUINS_CODEC.encode(input.addTrailRuins(), ops, builder);
            builder = EarthGeneratorSettings.TELLUS_MANAGED_TERRAIN_DOWNLOADS_CODEC.encode(input.tellusManagedTerrainDownloads(), ops, builder);
            builder = EarthGeneratorSettings.SHOW_TERRAIN_DOWNLOAD_OVERLAY_CODEC.encode(input.showTerrainDownloadOverlay(), ops, builder);
            builder = EarthGeneratorSettings.CAVES_REACH_SURFACE_CODEC.encode(input.cavesReachSurface(), ops, builder);
            builder = EarthGeneratorSettings.UNDERGROUND_DEPTH_CODEC.encode(input.undergroundDepth(), ops, builder);
            builder = EarthGeneratorSettings.CUSTOM_TREES_CODEC.encode(input.customTrees(), ops, builder);
            builder = EarthGeneratorSettings.HUGE_RED_MUSHROOMS_CODEC.encode(input.hugeRedMushrooms(), ops, builder);
            builder = EarthGeneratorSettings.WORLD_SCALE_AT_SPAWN_CODEC.encode(input.worldScaleAtSpawn(), ops, builder);
            return EarthGeneratorSettings.CENTER_WORLD_ON_SPAWN_CODEC.encode(input.centerWorldOnSpawn(), ops, builder);
         }

         public <T> Stream<T> keys(DynamicOps<T> ops) {
            Stream<T> baseKeys = EarthGeneratorSettings.BASE_CODEC.keys(ops);
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DISTANT_HORIZONS_RENDER_MODE_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DEM_SELECTION_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DISTANT_HORIZONS_WATER_RESOLVER_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DISTANT_HORIZONS_OSM_FEATURES_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DISTANT_HORIZONS_OSM_ROAD_MAX_DETAIL_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DISTANT_HORIZONS_OSM_BUILDING_MAX_DETAIL_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DISTANT_HORIZONS_OSM_NON_BLOCKING_FETCH_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.REALTIME_TIME_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.REALTIME_WEATHER_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.HISTORICAL_SNOW_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.ENABLE_ROADS_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.ENABLE_BUILDINGS_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.ENABLE_WATER_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.THIN_SHELL_TERRAIN_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.CLIMATE_BASED_BUILT_UP_TERRAIN_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.RANDOM_BIOMES_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.RANDOM_BIOME_DENSITY_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.RANDOM_BIOME_SEED_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.RANDOM_BIOME_IDS_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.EXPERIMENTAL_INCREASE_HEIGHT_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.AUTOMATIC_HEIGHT_SCALING_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.EXPERIMENTAL_HEIGHT_COORDINATE_PROFILE_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.VOXY_CHUNK_PREGEN_ENABLED_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.VOXY_CHUNK_PREGEN_MAX_RADIUS_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.VOXY_CHUNK_PREGEN_CHUNKS_PER_TICK_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DEEP_DARK_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.GEODES_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.TELLUS_MANAGED_TERRAIN_DOWNLOADS_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.SHOW_TERRAIN_DOWNLOAD_OVERLAY_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.CAVES_REACH_SURFACE_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.UNDERGROUND_DEPTH_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.CUSTOM_TREES_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.HUGE_RED_MUSHROOMS_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.WORLD_SCALE_AT_SPAWN_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.CENTER_WORLD_ON_SPAWN_CODEC.keys(ops));
            Stream<T> structureKeys = Stream.concat(baseKeys, EarthGeneratorSettings.STRUCTURE_CODEC.keys(ops));
            return Stream.concat(structureKeys, EarthGeneratorSettings.TRAIL_RUINS_CODEC.keys(ops));
         }
      },
      new com.mojang.serialization.MapDecoder.Implementation<EarthGeneratorSettings>() {
         public <T> DataResult<EarthGeneratorSettings> decode(DynamicOps<T> ops, MapLike<T> input) {
            DataResult<EarthGeneratorSettings.SettingsBase> base = EarthGeneratorSettings.BASE_CODEC.decode(ops, input);
            DataResult<EarthGeneratorSettings.DistantHorizonsRenderMode> distantHorizonsRenderMode = EarthGeneratorSettings.DISTANT_HORIZONS_RENDER_MODE_CODEC
               .decode(ops, input);
            DataResult<EarthGeneratorSettings.DemSelection> demSelection = EarthGeneratorSettings.DEM_SELECTION_CODEC.decode(ops, input);
            DataResult<Boolean> distantHorizonsWaterResolver = EarthGeneratorSettings.DISTANT_HORIZONS_WATER_RESOLVER_CODEC.decode(ops, input);
            DataResult<Boolean> distantHorizonsOsmFeatures = EarthGeneratorSettings.DISTANT_HORIZONS_OSM_FEATURES_CODEC.decode(ops, input);
            DataResult<Integer> distantHorizonsOsmRoadMaxDetail = EarthGeneratorSettings.DISTANT_HORIZONS_OSM_ROAD_MAX_DETAIL_CODEC.decode(ops, input);
            DataResult<Integer> distantHorizonsOsmBuildingMaxDetail = EarthGeneratorSettings.DISTANT_HORIZONS_OSM_BUILDING_MAX_DETAIL_CODEC.decode(ops, input);
            DataResult<Boolean> distantHorizonsOsmNonBlockingFetch = EarthGeneratorSettings.DISTANT_HORIZONS_OSM_NON_BLOCKING_FETCH_CODEC.decode(ops, input);
            DataResult<Boolean> realtimeTime = EarthGeneratorSettings.REALTIME_TIME_CODEC.decode(ops, input);
            DataResult<Boolean> realtimeWeather = EarthGeneratorSettings.REALTIME_WEATHER_CODEC.decode(ops, input);
            DataResult<Boolean> historicalSnow = EarthGeneratorSettings.HISTORICAL_SNOW_CODEC.decode(ops, input);
            DataResult<Boolean> enableRoads = EarthGeneratorSettings.ENABLE_ROADS_CODEC.decode(ops, input);
            DataResult<Boolean> enableBuildings = EarthGeneratorSettings.ENABLE_BUILDINGS_CODEC.decode(ops, input);
            DataResult<Boolean> enableWater = EarthGeneratorSettings.ENABLE_WATER_CODEC.decode(ops, input);
            DataResult<Boolean> thinShellTerrain = EarthGeneratorSettings.THIN_SHELL_TERRAIN_CODEC.decode(ops, input);
            DataResult<Boolean> climateBasedBuiltUpTerrain = EarthGeneratorSettings.CLIMATE_BASED_BUILT_UP_TERRAIN_CODEC.decode(ops, input);
            DataResult<Boolean> randomBiomes = EarthGeneratorSettings.RANDOM_BIOMES_CODEC.decode(ops, input);
            DataResult<Double> randomBiomeDensity = EarthGeneratorSettings.RANDOM_BIOME_DENSITY_CODEC.decode(ops, input);
            DataResult<Long> randomBiomeSeed = EarthGeneratorSettings.RANDOM_BIOME_SEED_CODEC.decode(ops, input);
            DataResult<List<String>> randomBiomeIds = EarthGeneratorSettings.RANDOM_BIOME_IDS_CODEC.decode(ops, input);
            DataResult<Boolean> managedTerrainDownloads = EarthGeneratorSettings.TELLUS_MANAGED_TERRAIN_DOWNLOADS_CODEC.decode(ops, input);
            DataResult<Boolean> showTerrainDownloadOverlay = EarthGeneratorSettings.SHOW_TERRAIN_DOWNLOAD_OVERLAY_CODEC.decode(ops, input);
            DataResult<Boolean> cavesReachSurface = EarthGeneratorSettings.CAVES_REACH_SURFACE_CODEC.decode(ops, input);
            DataResult<Integer> undergroundDepth = EarthGeneratorSettings.UNDERGROUND_DEPTH_CODEC.decode(ops, input);
            DataResult<Boolean> customTrees = EarthGeneratorSettings.CUSTOM_TREES_CODEC.decode(ops, input);
            DataResult<Boolean> hugeRedMushrooms = EarthGeneratorSettings.HUGE_RED_MUSHROOMS_CODEC.decode(ops, input);
            DataResult<Boolean> worldScaleAtSpawn = EarthGeneratorSettings.WORLD_SCALE_AT_SPAWN_CODEC.decode(ops, input);
            DataResult<Boolean> centerWorldOnSpawn = EarthGeneratorSettings.CENTER_WORLD_ON_SPAWN_CODEC.decode(ops, input);
            DataResult<Boolean> experimentalIncreaseHeight = EarthGeneratorSettings.EXPERIMENTAL_INCREASE_HEIGHT_CODEC.decode(ops, input);
            DataResult<Boolean> automaticHeightScaling = EarthGeneratorSettings.AUTOMATIC_HEIGHT_SCALING_CODEC.decode(ops, input);
            DataResult<Optional<String>> experimentalHeightCoordinateProfile = EarthGeneratorSettings.EXPERIMENTAL_HEIGHT_COORDINATE_PROFILE_CODEC
               .decode(ops, input);
            DataResult<Boolean> voxyChunkPregenEnabled = EarthGeneratorSettings.VOXY_CHUNK_PREGEN_ENABLED_CODEC.decode(ops, input);
            DataResult<Integer> voxyChunkPregenMaxRadius = EarthGeneratorSettings.VOXY_CHUNK_PREGEN_MAX_RADIUS_CODEC.decode(ops, input);
            DataResult<Integer> voxyChunkPregenChunksPerTick = EarthGeneratorSettings.VOXY_CHUNK_PREGEN_CHUNKS_PER_TICK_CODEC.decode(ops, input);
            DataResult<Boolean> deepDark = EarthGeneratorSettings.DEEP_DARK_CODEC.decode(ops, input);
            DataResult<Boolean> geodes = EarthGeneratorSettings.GEODES_CODEC.decode(ops, input);
            DataResult<EarthGeneratorSettings.StructureSettings> structures = EarthGeneratorSettings.STRUCTURE_CODEC.decode(ops, input);
            DataResult<Boolean> trailRuins = EarthGeneratorSettings.TRAIL_RUINS_CODEC.decode(ops, input);
            DataResult<EarthGeneratorSettings.SettingsBase> withRenderMode = base.apply2(
               EarthGeneratorSettings::applyDistantHorizonsRenderMode, distantHorizonsRenderMode
            );
            DataResult<EarthGeneratorSettings.SettingsBase> withDemSelection = withRenderMode.apply2(EarthGeneratorSettings::applyDemSelection, demSelection);
            DataResult<EarthGeneratorSettings.SettingsBase> withWaterResolver = withDemSelection.apply2(
               EarthGeneratorSettings::applyDistantHorizonsWaterResolver, distantHorizonsWaterResolver
            );
            DataResult<EarthGeneratorSettings.SettingsBase> withOsmFeatures = withWaterResolver.apply2(
               EarthGeneratorSettings::applyDistantHorizonsOsmFeatures, distantHorizonsOsmFeatures
            );
            DataResult<EarthGeneratorSettings.SettingsBase> withRealtimeTime = withOsmFeatures.apply2(EarthGeneratorSettings::applyRealtimeTime, realtimeTime);
            DataResult<EarthGeneratorSettings.SettingsBase> withRealtimeWeather = withRealtimeTime.apply2(
               EarthGeneratorSettings::applyRealtimeWeather, realtimeWeather
            );
            DataResult<EarthGeneratorSettings.SettingsBase> withHistoricalSnow = withRealtimeWeather.apply2(
               EarthGeneratorSettings::applyHistoricalSnow, historicalSnow
            );
            DataResult<EarthGeneratorSettings.SettingsBase> withVoxyEnabled = withHistoricalSnow.apply2(
               EarthGeneratorSettings::applyVoxyChunkPregenEnabled, voxyChunkPregenEnabled
            );
            DataResult<EarthGeneratorSettings.SettingsBase> withVoxyMaxRadius = withVoxyEnabled.apply2(
               EarthGeneratorSettings::applyVoxyChunkPregenMaxRadius, voxyChunkPregenMaxRadius
            );
            DataResult<EarthGeneratorSettings.SettingsBase> withVoxyChunksPerTick = withVoxyMaxRadius.apply2(
               EarthGeneratorSettings::applyVoxyChunkPregenChunksPerTick, voxyChunkPregenChunksPerTick
            );
            DataResult<EarthGeneratorSettings> settings = withVoxyChunksPerTick.map(EarthGeneratorSettings.SettingsBase::toSettings);
            settings = settings.apply2(EarthGeneratorSettings::applyDistantHorizonsOsmRoadMaxDetail, distantHorizonsOsmRoadMaxDetail);
            settings = settings.apply2(EarthGeneratorSettings::applyDistantHorizonsOsmBuildingMaxDetail, distantHorizonsOsmBuildingMaxDetail);
            settings = settings.apply2(EarthGeneratorSettings::applyDistantHorizonsOsmNonBlockingFetch, distantHorizonsOsmNonBlockingFetch);
            settings = settings.apply2(EarthGeneratorSettings::applyEnableRoads, enableRoads);
            settings = settings.apply2(EarthGeneratorSettings::applyEnableBuildings, enableBuildings);
            settings = settings.apply2(EarthGeneratorSettings::applyEnableWater, enableWater);
            settings = settings.apply2(EarthGeneratorSettings::applyClimateBasedBuiltUpTerrain, climateBasedBuiltUpTerrain);
            settings = settings.apply2(EarthGeneratorSettings::applyDeepDark, deepDark);
            settings = settings.apply2(EarthGeneratorSettings::applyGeodes, geodes);
            settings = settings.apply2(EarthGeneratorSettings::withStructureSettings, structures);
            settings = settings.apply2(EarthGeneratorSettings::applyTrailRuins, trailRuins);
            settings = settings.apply2(EarthGeneratorSettings::applyThinShellTerrain, thinShellTerrain);
            settings = settings.apply2(EarthGeneratorSettings::applyRandomBiomes, randomBiomes);
            settings = settings.apply2(EarthGeneratorSettings::applyRandomBiomeDensity, randomBiomeDensity);
            settings = settings.apply2(EarthGeneratorSettings::applyRandomBiomeSeed, randomBiomeSeed);
            settings = settings.apply2(EarthGeneratorSettings::applyRandomBiomeIds, randomBiomeIds);
            DataResult<EarthGeneratorSettings> withExperimentalHeight = settings.apply2(
               EarthGeneratorSettings::applyExperimentalIncreaseHeight, experimentalIncreaseHeight
            );
            DataResult<EarthGeneratorSettings> withAutomaticHeightScaling = withExperimentalHeight.apply2(
               EarthGeneratorSettings::applyAutomaticHeightScaling, automaticHeightScaling
            );
            DataResult<EarthGeneratorSettings> validated = withAutomaticHeightScaling.flatMap(
               decodedSettings -> experimentalHeightCoordinateProfile.flatMap(
                  profile -> validateExperimentalHeightCoordinateProfile(decodedSettings, profile)
               )
            );
            DataResult<EarthGeneratorSettings.NetworkSettings> networkSettings = managedTerrainDownloads.apply2(
               (managed, showOverlay) -> new EarthGeneratorSettings.NetworkSettings(managed, showOverlay, DEFAULT.cavesReachSurface()),
               showTerrainDownloadOverlay
            );
            networkSettings = networkSettings.apply2(
               (network, enabled) -> new EarthGeneratorSettings.NetworkSettings(network.managedTerrainDownloads(), network.showOverlay(), enabled),
               cavesReachSurface
            );
            DataResult<EarthGeneratorSettings> withNetworkSettings = validated.apply2(EarthGeneratorSettings::applyNetworkSettings, networkSettings);
            DataResult<EarthGeneratorSettings> withUndergroundDepth = withNetworkSettings.apply2(
               EarthGeneratorSettings::applyUndergroundDepth, undergroundDepth
            );
            DataResult<EarthGeneratorSettings> withCustomTrees = withUndergroundDepth.apply2(
               EarthGeneratorSettings::applyCustomTrees, customTrees
            );
            DataResult<EarthGeneratorSettings> withHugeRedMushrooms = withCustomTrees.apply2(
               EarthGeneratorSettings::applyHugeRedMushrooms, hugeRedMushrooms
            );
            DataResult<EarthGeneratorSettings> withWorldScaleAtSpawn = withHugeRedMushrooms.apply2(
               EarthGeneratorSettings::applyWorldScaleAtSpawn, worldScaleAtSpawn
            );
            return withWorldScaleAtSpawn.apply2(EarthGeneratorSettings::applyCenterWorldOnSpawn, centerWorldOnSpawn);
         }

         public <T> Stream<T> keys(DynamicOps<T> ops) {
            Stream<T> baseKeys = EarthGeneratorSettings.BASE_CODEC.keys(ops);
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DISTANT_HORIZONS_RENDER_MODE_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DEM_SELECTION_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DISTANT_HORIZONS_WATER_RESOLVER_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DISTANT_HORIZONS_OSM_FEATURES_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DISTANT_HORIZONS_OSM_ROAD_MAX_DETAIL_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DISTANT_HORIZONS_OSM_BUILDING_MAX_DETAIL_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DISTANT_HORIZONS_OSM_NON_BLOCKING_FETCH_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.REALTIME_TIME_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.REALTIME_WEATHER_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.HISTORICAL_SNOW_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.ENABLE_ROADS_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.ENABLE_BUILDINGS_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.ENABLE_WATER_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.THIN_SHELL_TERRAIN_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.CLIMATE_BASED_BUILT_UP_TERRAIN_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.RANDOM_BIOMES_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.RANDOM_BIOME_DENSITY_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.RANDOM_BIOME_SEED_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.RANDOM_BIOME_IDS_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.EXPERIMENTAL_INCREASE_HEIGHT_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.AUTOMATIC_HEIGHT_SCALING_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.EXPERIMENTAL_HEIGHT_COORDINATE_PROFILE_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.VOXY_CHUNK_PREGEN_ENABLED_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.VOXY_CHUNK_PREGEN_MAX_RADIUS_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.VOXY_CHUNK_PREGEN_CHUNKS_PER_TICK_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DEEP_DARK_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.GEODES_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.TELLUS_MANAGED_TERRAIN_DOWNLOADS_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.SHOW_TERRAIN_DOWNLOAD_OVERLAY_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.CAVES_REACH_SURFACE_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.UNDERGROUND_DEPTH_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.CUSTOM_TREES_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.HUGE_RED_MUSHROOMS_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.WORLD_SCALE_AT_SPAWN_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.CENTER_WORLD_ON_SPAWN_CODEC.keys(ops));
            Stream<T> structureKeys = Stream.concat(baseKeys, EarthGeneratorSettings.STRUCTURE_CODEC.keys(ops));
            return Stream.concat(structureKeys, EarthGeneratorSettings.TRAIL_RUINS_CODEC.keys(ops));
         }
      }
   );
   public static final Codec<EarthGeneratorSettings> CODEC = MAP_CODEC.codec();

   public EarthGeneratorSettings(
      double worldScale,
      double terrestrialHeightScale,
      double oceanicHeightScale,
      int heightOffset,
      double spawnLatitude,
      double spawnLongitude,
      int minAltitude,
      int maxAltitude,
      int riverLakeShorelineBlend,
      int oceanShorelineBlend,
      boolean shorelineBlendCliffLimit,
      boolean caveGeneration,
      boolean oreDistribution,
      boolean geologicalStonePatches,
      boolean lavaPools,
      boolean addStrongholds,
      boolean addVillages,
      boolean addMineshafts,
      boolean addOceanMonuments,
      boolean addWoodlandMansions,
      boolean addDesertTemples,
      boolean addJungleTemples,
      boolean addPillagerOutposts,
      boolean addRuinedPortals,
      boolean addShipwrecks,
      boolean addOceanRuins,
      boolean addBuriedTreasure,
      boolean addIgloos,
      boolean addWitchHuts,
      boolean addAncientCities,
      boolean addTrialChambers,
      boolean addTrailRuins,
      boolean deepDark,
      boolean geodes,
      boolean distantHorizonsWaterResolver,
      boolean distantHorizonsOsmFeatures,
      int distantHorizonsOsmRoadMaxDetail,
      int distantHorizonsOsmBuildingMaxDetail,
      boolean distantHorizonsOsmNonBlockingFetch,
      boolean realtimeTime,
      boolean realtimeWeather,
      boolean historicalSnow,
      boolean voxyChunkPregenEnabled,
      int voxyChunkPregenMaxRadius,
      int voxyChunkPregenChunksPerTick,
      EarthGeneratorSettings.DistantHorizonsRenderMode distantHorizonsRenderMode,
      EarthGeneratorSettings.DemSelection demSelection,
      boolean enableRoads,
      boolean enableBuildings,
      boolean enableWater,
      boolean thinShellTerrain,
      boolean climateBasedBuiltUpTerrain,
      boolean randomBiomes,
      double randomBiomeDensity,
      long randomBiomeSeed,
      List<String> randomBiomeIds,
      boolean experimentalIncreaseHeight,
      boolean tellusManagedTerrainDownloads,
      boolean showTerrainDownloadOverlay,
      boolean cavesReachSurface,
      int undergroundDepth,
      boolean customTrees,
      boolean automaticHeightScaling,
      boolean hugeRedMushrooms,
      boolean worldScaleAtSpawn,
      boolean centerWorldOnSpawn
   ) {
      worldScale = clampWorldScale(worldScale);
      randomBiomeDensity = Mth.clamp(randomBiomeDensity, MIN_RANDOM_BIOME_DENSITY, MAX_RANDOM_BIOME_DENSITY);
      randomBiomeIds = MinecraftVersionCompat.normalizeRandomBiomeSelection(randomBiomeIds);
      voxyChunkPregenMaxRadius = Mth.clamp(voxyChunkPregenMaxRadius, 0, MAX_VOXY_PREGEN_RADIUS);
      voxyChunkPregenChunksPerTick = Mth.clamp(voxyChunkPregenChunksPerTick, 1, MAX_VOXY_PREGEN_CHUNKS_PER_TICK);
      undergroundDepth = Mth.clamp(undergroundDepth, MIN_UNDERGROUND_DEPTH, MAX_UNDERGROUND_DEPTH);
      if (experimentalIncreaseHeight) {
         terrestrialHeightScale = 1.0;
         oceanicHeightScale = 1.0;
         heightOffset = experimentalHeightOffset(automaticHeightScaling);
         minAltitude = AUTO_ALTITUDE;
         maxAltitude = AUTO_ALTITUDE;
      }

      distantHorizonsOsmFeatures = FIXED_DH_OSM_FEATURES;
      distantHorizonsOsmRoadMaxDetail = FIXED_DH_OSM_ROAD_MAX_DETAIL;
      distantHorizonsOsmBuildingMaxDetail = FIXED_DH_OSM_BUILDING_MAX_DETAIL;
      distantHorizonsOsmNonBlockingFetch = FIXED_DH_OSM_NON_BLOCKING_FETCH;
      this.worldScale = worldScale;
      this.terrestrialHeightScale = terrestrialHeightScale;
      this.oceanicHeightScale = oceanicHeightScale;
      this.heightOffset = heightOffset;
      this.spawnLatitude = spawnLatitude;
      this.spawnLongitude = spawnLongitude;
      this.minAltitude = minAltitude;
      this.maxAltitude = maxAltitude;
      this.riverLakeShorelineBlend = riverLakeShorelineBlend;
      this.oceanShorelineBlend = oceanShorelineBlend;
      this.shorelineBlendCliffLimit = shorelineBlendCliffLimit;
      this.caveGeneration = caveGeneration;
      this.oreDistribution = oreDistribution;
      this.geologicalStonePatches = geologicalStonePatches;
      this.lavaPools = lavaPools;
      this.addStrongholds = addStrongholds;
      this.addVillages = addVillages;
      this.addMineshafts = addMineshafts;
      this.addOceanMonuments = addOceanMonuments;
      this.addWoodlandMansions = addWoodlandMansions;
      this.addDesertTemples = addDesertTemples;
      this.addJungleTemples = addJungleTemples;
      this.addPillagerOutposts = addPillagerOutposts;
      this.addRuinedPortals = addRuinedPortals;
      this.addShipwrecks = addShipwrecks;
      this.addOceanRuins = addOceanRuins;
      this.addBuriedTreasure = addBuriedTreasure;
      this.addIgloos = addIgloos;
      this.addWitchHuts = addWitchHuts;
      this.addAncientCities = addAncientCities;
      this.addTrialChambers = addTrialChambers;
      this.addTrailRuins = addTrailRuins;
      this.deepDark = deepDark;
      this.geodes = geodes;
      this.distantHorizonsWaterResolver = distantHorizonsWaterResolver;
      this.distantHorizonsOsmFeatures = distantHorizonsOsmFeatures;
      this.distantHorizonsOsmRoadMaxDetail = distantHorizonsOsmRoadMaxDetail;
      this.distantHorizonsOsmBuildingMaxDetail = distantHorizonsOsmBuildingMaxDetail;
      this.distantHorizonsOsmNonBlockingFetch = distantHorizonsOsmNonBlockingFetch;
      this.realtimeTime = realtimeTime;
      this.realtimeWeather = realtimeWeather;
      this.historicalSnow = historicalSnow;
      this.voxyChunkPregenEnabled = voxyChunkPregenEnabled;
      this.voxyChunkPregenMaxRadius = voxyChunkPregenMaxRadius;
      this.voxyChunkPregenChunksPerTick = voxyChunkPregenChunksPerTick;
      this.distantHorizonsRenderMode = Objects.requireNonNull(distantHorizonsRenderMode, "distantHorizonsRenderMode");
      this.demSelection = Objects.requireNonNull(demSelection, "demSelection");
      this.enableRoads = enableRoads;
      this.enableBuildings = enableBuildings;
      // Retain the serialized field for old worlds, but Overture vector water is now the only supported source.
      this.enableWater = true;
      this.thinShellTerrain = thinShellTerrain;
      this.climateBasedBuiltUpTerrain = climateBasedBuiltUpTerrain;
      this.randomBiomes = randomBiomes;
      this.randomBiomeDensity = randomBiomeDensity;
      this.randomBiomeSeed = randomBiomeSeed;
      this.randomBiomeIds = randomBiomeIds;
      this.experimentalIncreaseHeight = experimentalIncreaseHeight;
      this.tellusManagedTerrainDownloads = tellusManagedTerrainDownloads;
      this.showTerrainDownloadOverlay = showTerrainDownloadOverlay;
      this.cavesReachSurface = cavesReachSurface;
      this.undergroundDepth = undergroundDepth;
      this.customTrees = customTrees;
      this.automaticHeightScaling = automaticHeightScaling;
      this.hugeRedMushrooms = hugeRedMushrooms;
      this.worldScaleAtSpawn = worldScaleAtSpawn;
      this.centerWorldOnSpawn = centerWorldOnSpawn;
   }

   /**
    * The block{@code <->}geographic mapping of this world. Callers that convert many coordinates should hold
    * on to the returned instance rather than calling this in a loop.
    */
   public WorldProjection projection() {
      return WorldProjection.of(this);
   }

   /**
    * Real-world metres covered by one block at the configured spawn point. Spherical Mercator enlarges the
    * horizontal axes by {@code sec(latitude)}, so the ground scale at spawn is the equatorial
    * {@link #worldScale()} divided by that correction.
    */
   public double groundScaleAtSpawn() {
      return groundScaleAtLatitude(this.worldScale, this.spawnLatitude);
   }

   /**
    * The World Scale value to present in the UI: the ground scale at spawn when
    * {@link #worldScaleAtSpawn()} is enabled, otherwise the equatorial scale.
    */
   public double displayedWorldScale() {
      return displayedWorldScale(this.worldScale, this.spawnLatitude, this.worldScaleAtSpawn);
   }

   public static double groundScaleAtLatitude(double equatorialWorldScale, double latitude) {
      if (!(equatorialWorldScale > 0.0) || !Double.isFinite(latitude)) {
         return equatorialWorldScale;
      }

      return equatorialWorldScale / EarthProjection.heightScaleCorrectionAtLatitude(latitude);
   }

   public static double displayedWorldScale(double equatorialWorldScale, double spawnLatitude, boolean worldScaleAtSpawn) {
      return worldScaleAtSpawn ? groundScaleAtLatitude(equatorialWorldScale, spawnLatitude) : equatorialWorldScale;
   }

   /**
    * Converts a World Scale value entered in the UI back to the equatorial scale stored in
    * {@code world_scale}. This is the inverse of {@link #displayedWorldScale(double, double, boolean)}.
    */
   public static double equatorialWorldScale(double displayedWorldScale, double spawnLatitude, boolean worldScaleAtSpawn) {
      if (!worldScaleAtSpawn || !(displayedWorldScale > 0.0) || !Double.isFinite(spawnLatitude)) {
         return displayedWorldScale;
      }

      return displayedWorldScale * EarthProjection.heightScaleCorrectionAtLatitude(spawnLatitude);
   }

   public double effectiveTerrestrialHeightScale() {
      return this.experimentalIncreaseHeight ? 1.0 : this.terrestrialHeightScale;
   }

   public double effectiveOceanicHeightScale() {
      return this.experimentalIncreaseHeight ? 1.0 : this.oceanicHeightScale;
   }

   public int effectiveHeightOffset() {
      return this.experimentalIncreaseHeight ? experimentalHeightOffset(this.automaticHeightScaling) : this.heightOffset;
   }

   public static int experimentalHeightOffset(boolean automaticHeightScaling) {
      return automaticHeightScaling ? EXPERIMENTAL_HEIGHT_OFFSET : EXPERIMENTAL_DEEP_OCEAN_HEIGHT_OFFSET;
   }

   public int effectiveMinAltitude() {
      return this.experimentalIncreaseHeight ? AUTO_ALTITUDE : this.minAltitude;
   }

   public int effectiveMaxAltitude() {
      return this.experimentalIncreaseHeight ? AUTO_ALTITUDE : this.maxAltitude;
   }

   public double effectiveVerticalWorldScale() {
      return this.worldScale;
   }

   public boolean usesTerrainShell() {
      return true;
   }

   public boolean suppressesUndergroundGenerationForTerrainShell() {
      return false;
   }

   private static EarthGeneratorSettings.StructureSettings createStructureSettings(
      Boolean addStrongholds,
      Boolean addVillages,
      Boolean addMineshafts,
      Boolean addOceanMonuments,
      Boolean addWoodlandMansions,
      Boolean addDesertTemples,
      Boolean addJungleTemples,
      Boolean addPillagerOutposts,
      Boolean addRuinedPortals,
      Boolean addShipwrecks,
      Boolean addOceanRuins,
      Boolean addBuriedTreasure,
      Boolean addIgloos,
      Boolean addWitchHuts,
      Boolean addAncientCities,
      Boolean addTrialChambers
   ) {
      return new EarthGeneratorSettings.StructureSettings(
         Objects.requireNonNull(addStrongholds, "addStrongholds"),
         Objects.requireNonNull(addVillages, "addVillages"),
         Objects.requireNonNull(addMineshafts, "addMineshafts"),
         Objects.requireNonNull(addOceanMonuments, "addOceanMonuments"),
         Objects.requireNonNull(addWoodlandMansions, "addWoodlandMansions"),
         Objects.requireNonNull(addDesertTemples, "addDesertTemples"),
         Objects.requireNonNull(addJungleTemples, "addJungleTemples"),
         Objects.requireNonNull(addPillagerOutposts, "addPillagerOutposts"),
         Objects.requireNonNull(addRuinedPortals, "addRuinedPortals"),
         Objects.requireNonNull(addShipwrecks, "addShipwrecks"),
         Objects.requireNonNull(addOceanRuins, "addOceanRuins"),
         Objects.requireNonNull(addBuriedTreasure, "addBuriedTreasure"),
         Objects.requireNonNull(addIgloos, "addIgloos"),
         Objects.requireNonNull(addWitchHuts, "addWitchHuts"),
         Objects.requireNonNull(addAncientCities, "addAncientCities"),
         Objects.requireNonNull(addTrialChambers, "addTrialChambers")
      );
   }

   private static EarthGeneratorSettings.SettingsBase createSettingsBase(
      Double worldScale,
      Double terrestrialHeightScale,
      Double oceanicHeightScale,
      Integer heightOffset,
      Double spawnLatitude,
      Double spawnLongitude,
      Integer minAltitude,
      Integer maxAltitude,
      Integer riverLakeShorelineBlend,
      Integer oceanShorelineBlend,
      Boolean shorelineBlendCliffLimit,
      Boolean caveGeneration,
      Boolean oreDistribution,
      Boolean geologicalStonePatches,
      Boolean lavaPools
   ) {
      int resolvedHeightOffset = Objects.requireNonNull(heightOffset, "heightOffset");
      double resolvedWorldScale = clampWorldScale(Objects.requireNonNull(worldScale, "worldScale"));
      return new EarthGeneratorSettings.SettingsBase(
         resolvedWorldScale,
         Objects.requireNonNull(terrestrialHeightScale, "terrestrialHeightScale"),
         Objects.requireNonNull(oceanicHeightScale, "oceanicHeightScale"),
         resolvedHeightOffset,
         Objects.requireNonNull(spawnLatitude, "spawnLatitude"),
         Objects.requireNonNull(spawnLongitude, "spawnLongitude"),
         Objects.requireNonNull(minAltitude, "minAltitude"),
         Objects.requireNonNull(maxAltitude, "maxAltitude"),
         Objects.requireNonNull(riverLakeShorelineBlend, "riverLakeShorelineBlend"),
         Objects.requireNonNull(oceanShorelineBlend, "oceanShorelineBlend"),
         Objects.requireNonNull(shorelineBlendCliffLimit, "shorelineBlendCliffLimit"),
         Objects.requireNonNull(caveGeneration, "caveGeneration"),
         Objects.requireNonNull(oreDistribution, "oreDistribution"),
         Objects.requireNonNull(geologicalStonePatches, "geologicalStonePatches"),
         Objects.requireNonNull(lavaPools, "lavaPools"),
         DEFAULT.distantHorizonsWaterResolver(),
         DEFAULT.distantHorizonsOsmFeatures(),
         DEFAULT.realtimeTime(),
         DEFAULT.realtimeWeather(),
         DEFAULT.historicalSnow(),
         DEFAULT.voxyChunkPregenEnabled(),
         DEFAULT.voxyChunkPregenMaxRadius(),
         DEFAULT.voxyChunkPregenChunksPerTick(),
         DEFAULT.distantHorizonsRenderMode(),
         DEFAULT.demSelection()
      );
   }

   private static EarthGeneratorSettings.BaseToggles createBaseToggles(
      Optional<Boolean> caveGeneration,
      Optional<Boolean> caveCarvers,
      Optional<Boolean> largeCaves,
      Optional<Boolean> canyonCarvers,
      Boolean oreDistribution,
      Boolean geologicalStonePatches,
      Boolean lavaPools
   ) {
      return new EarthGeneratorSettings.BaseToggles(
         caveGeneration,
         caveCarvers,
         largeCaves,
         canyonCarvers,
         Objects.requireNonNull(oreDistribution, "oreDistribution"),
         Objects.requireNonNull(geologicalStonePatches, "geologicalStonePatches"),
         Objects.requireNonNull(lavaPools, "lavaPools")
      );
   }

   private static double clampWorldScale(double worldScale) {
      return worldScale <= 0.0 ? worldScale : Math.min(worldScale, MAX_WORLD_SCALE);
   }

   private static EarthGeneratorSettings.SettingsBase applyDistantHorizonsRenderMode(
      EarthGeneratorSettings.SettingsBase settings, EarthGeneratorSettings.DistantHorizonsRenderMode renderMode
   ) {
      return settings.withDistantHorizonsRenderMode(Objects.requireNonNull(renderMode, "renderMode"));
   }

   private static EarthGeneratorSettings.SettingsBase applyDemSelection(
      EarthGeneratorSettings.SettingsBase settings, EarthGeneratorSettings.DemSelection demSelection
   ) {
      Objects.requireNonNull(demSelection, "demSelection");
      return settings.withDemSelection(EarthGeneratorSettings.DemSelection.mapterhornSelection());
   }

   private static EarthGeneratorSettings.SettingsBase applyDistantHorizonsWaterResolver(EarthGeneratorSettings.SettingsBase settings, Boolean enabled) {
      return settings.withDistantHorizonsWaterResolver(Objects.requireNonNull(enabled, "distantHorizonsWaterResolver"));
   }

   private static EarthGeneratorSettings.SettingsBase applyDistantHorizonsOsmFeatures(EarthGeneratorSettings.SettingsBase settings, Boolean enabled) {
      return settings.withDistantHorizonsOsmFeatures(Objects.requireNonNull(enabled, "distantHorizonsOsmFeatures"));
   }

   private static EarthGeneratorSettings.SettingsBase applyRealtimeTime(EarthGeneratorSettings.SettingsBase settings, Boolean enabled) {
      return settings.withRealtimeTime(Objects.requireNonNull(enabled, "realtimeTime"));
   }

   private static EarthGeneratorSettings.SettingsBase applyRealtimeWeather(EarthGeneratorSettings.SettingsBase settings, Boolean enabled) {
      return settings.withRealtimeWeather(Objects.requireNonNull(enabled, "realtimeWeather"));
   }

   private static EarthGeneratorSettings.SettingsBase applyHistoricalSnow(EarthGeneratorSettings.SettingsBase settings, Boolean enabled) {
      return settings.withHistoricalSnow(Objects.requireNonNull(enabled, "historicalSnow"));
   }

   private static EarthGeneratorSettings applyDistantHorizonsOsmRoadMaxDetail(EarthGeneratorSettings settings, Integer value) {
      return settings.withDistantHorizonsOsmRoadMaxDetail(Objects.requireNonNull(value, "distantHorizonsOsmRoadMaxDetail"));
   }

   private static EarthGeneratorSettings applyDistantHorizonsOsmBuildingMaxDetail(EarthGeneratorSettings settings, Integer value) {
      return settings.withDistantHorizonsOsmBuildingMaxDetail(Objects.requireNonNull(value, "distantHorizonsOsmBuildingMaxDetail"));
   }

   private static EarthGeneratorSettings applyDistantHorizonsOsmNonBlockingFetch(EarthGeneratorSettings settings, Boolean enabled) {
      return settings.withDistantHorizonsOsmNonBlockingFetch(Objects.requireNonNull(enabled, "distantHorizonsOsmNonBlockingFetch"));
   }

   private static EarthGeneratorSettings applyEnableRoads(EarthGeneratorSettings settings, Boolean enabled) {
      return settings.withEnableRoads(Objects.requireNonNull(enabled, "enableRoads"));
   }

   private static EarthGeneratorSettings applyEnableBuildings(EarthGeneratorSettings settings, Boolean enabled) {
      return settings.withEnableBuildings(Objects.requireNonNull(enabled, "enableBuildings"));
   }

   private static EarthGeneratorSettings applyEnableWater(EarthGeneratorSettings settings, Boolean enabled) {
      return settings.withEnableWater(Objects.requireNonNull(enabled, "enableWater"));
   }

   private static EarthGeneratorSettings applyThinShellTerrain(EarthGeneratorSettings settings, Boolean enabled) {
      return settings.withThinShellTerrain(Objects.requireNonNull(enabled, "thinShellTerrain"));
   }

   private static EarthGeneratorSettings applyClimateBasedBuiltUpTerrain(EarthGeneratorSettings settings, Boolean enabled) {
      return settings.withClimateBasedBuiltUpTerrain(Objects.requireNonNull(enabled, "climateBasedBuiltUpTerrain"));
   }

   private static EarthGeneratorSettings applyRandomBiomes(EarthGeneratorSettings settings, Boolean enabled) {
      return settings.withRandomBiomes(Objects.requireNonNull(enabled, "randomBiomes"));
   }

   private static EarthGeneratorSettings applyRandomBiomeDensity(EarthGeneratorSettings settings, Double density) {
      return settings.withRandomBiomeDensity(Objects.requireNonNull(density, "randomBiomeDensity"));
   }

   private static EarthGeneratorSettings applyRandomBiomeSeed(EarthGeneratorSettings settings, Long seed) {
      return settings.withRandomBiomeSeed(Objects.requireNonNull(seed, "randomBiomeSeed"));
   }

   private static EarthGeneratorSettings applyRandomBiomeIds(EarthGeneratorSettings settings, List<String> biomeIds) {
      return settings.withRandomBiomeIds(Objects.requireNonNull(biomeIds, "randomBiomeIds"));
   }

   private static EarthGeneratorSettings applyNetworkSettings(EarthGeneratorSettings settings, EarthGeneratorSettings.NetworkSettings network) {
      Objects.requireNonNull(network, "network");
      return settings.withNetworkSettings(network.managedTerrainDownloads(), network.showOverlay(), network.cavesReachSurface());
   }

   private static EarthGeneratorSettings applyUndergroundDepth(EarthGeneratorSettings settings, Integer undergroundDepth) {
      return settings.withUndergroundDepth(Objects.requireNonNull(undergroundDepth, "undergroundDepth"));
   }

   private static EarthGeneratorSettings applyCustomTrees(EarthGeneratorSettings settings, Boolean customTrees) {
      return settings.withCustomTrees(Objects.requireNonNull(customTrees, "customTrees"));
   }

   private static EarthGeneratorSettings applyHugeRedMushrooms(EarthGeneratorSettings settings, Boolean hugeRedMushrooms) {
      return settings.withHugeRedMushrooms(Objects.requireNonNull(hugeRedMushrooms, "hugeRedMushrooms"));
   }

   private static EarthGeneratorSettings applyWorldScaleAtSpawn(EarthGeneratorSettings settings, Boolean worldScaleAtSpawn) {
      return settings.withWorldScaleAtSpawn(Objects.requireNonNull(worldScaleAtSpawn, "worldScaleAtSpawn"));
   }

   private static EarthGeneratorSettings applyCenterWorldOnSpawn(EarthGeneratorSettings settings, Boolean centerWorldOnSpawn) {
      return settings.withCenterWorldOnSpawn(Objects.requireNonNull(centerWorldOnSpawn, "centerWorldOnSpawn"));
   }

   public EarthGeneratorSettings withNetworkSettings(boolean managedTerrainDownloads, boolean showOverlay) {
      return this.withNetworkSettings(managedTerrainDownloads, showOverlay, this.cavesReachSurface);
   }

   private EarthGeneratorSettings withNetworkSettings(boolean managedTerrainDownloads, boolean showOverlay, boolean cavesReachSurface) {
      return new EarthGeneratorSettings(
         this.worldScale, this.terrestrialHeightScale, this.oceanicHeightScale, this.heightOffset,
         this.spawnLatitude, this.spawnLongitude, this.minAltitude, this.maxAltitude,
         this.riverLakeShorelineBlend, this.oceanShorelineBlend, this.shorelineBlendCliffLimit, this.caveGeneration, this.oreDistribution, this.geologicalStonePatches, this.lavaPools,
         this.addStrongholds, this.addVillages, this.addMineshafts, this.addOceanMonuments, this.addWoodlandMansions,
         this.addDesertTemples, this.addJungleTemples, this.addPillagerOutposts, this.addRuinedPortals, this.addShipwrecks,
         this.addOceanRuins, this.addBuriedTreasure, this.addIgloos, this.addWitchHuts, this.addAncientCities,
         this.addTrialChambers, this.addTrailRuins, this.deepDark, this.geodes, this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures, this.distantHorizonsOsmRoadMaxDetail, this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch, this.realtimeTime, this.realtimeWeather, this.historicalSnow,
         this.voxyChunkPregenEnabled, this.voxyChunkPregenMaxRadius, this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode, this.demSelection, this.enableRoads, this.enableBuildings, this.enableWater,
         this.thinShellTerrain, this.climateBasedBuiltUpTerrain, this.randomBiomes, this.randomBiomeDensity,
         this.randomBiomeSeed, this.randomBiomeIds, this.experimentalIncreaseHeight, managedTerrainDownloads, showOverlay, cavesReachSurface,
         this.undergroundDepth, this.customTrees,
         this.automaticHeightScaling,
         this.hugeRedMushrooms, this.worldScaleAtSpawn, this.centerWorldOnSpawn
      );
   }

   public EarthGeneratorSettings withUndergroundDepth(int undergroundDepth) {
      return new EarthGeneratorSettings(
         this.worldScale, this.terrestrialHeightScale, this.oceanicHeightScale, this.heightOffset,
         this.spawnLatitude, this.spawnLongitude, this.minAltitude, this.maxAltitude,
         this.riverLakeShorelineBlend, this.oceanShorelineBlend, this.shorelineBlendCliffLimit, this.caveGeneration, this.oreDistribution, this.geologicalStonePatches, this.lavaPools,
         this.addStrongholds, this.addVillages, this.addMineshafts, this.addOceanMonuments, this.addWoodlandMansions,
         this.addDesertTemples, this.addJungleTemples, this.addPillagerOutposts, this.addRuinedPortals, this.addShipwrecks,
         this.addOceanRuins, this.addBuriedTreasure, this.addIgloos, this.addWitchHuts, this.addAncientCities,
         this.addTrialChambers, this.addTrailRuins, this.deepDark, this.geodes, this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures, this.distantHorizonsOsmRoadMaxDetail, this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch, this.realtimeTime, this.realtimeWeather, this.historicalSnow,
         this.voxyChunkPregenEnabled, this.voxyChunkPregenMaxRadius, this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode, this.demSelection, this.enableRoads, this.enableBuildings, this.enableWater,
         this.thinShellTerrain, this.climateBasedBuiltUpTerrain, this.randomBiomes, this.randomBiomeDensity,
         this.randomBiomeSeed, this.randomBiomeIds, this.experimentalIncreaseHeight, this.tellusManagedTerrainDownloads,
         this.showTerrainDownloadOverlay, this.cavesReachSurface, undergroundDepth, this.customTrees,
         this.automaticHeightScaling,
         this.hugeRedMushrooms, this.worldScaleAtSpawn, this.centerWorldOnSpawn
      );
   }

   public EarthGeneratorSettings withCustomTrees(boolean customTrees) {
      return new EarthGeneratorSettings(
         this.worldScale, this.terrestrialHeightScale, this.oceanicHeightScale, this.heightOffset,
         this.spawnLatitude, this.spawnLongitude, this.minAltitude, this.maxAltitude,
         this.riverLakeShorelineBlend, this.oceanShorelineBlend, this.shorelineBlendCliffLimit, this.caveGeneration, this.oreDistribution, this.geologicalStonePatches, this.lavaPools,
         this.addStrongholds, this.addVillages, this.addMineshafts, this.addOceanMonuments, this.addWoodlandMansions,
         this.addDesertTemples, this.addJungleTemples, this.addPillagerOutposts, this.addRuinedPortals, this.addShipwrecks,
         this.addOceanRuins, this.addBuriedTreasure, this.addIgloos, this.addWitchHuts, this.addAncientCities,
         this.addTrialChambers, this.addTrailRuins, this.deepDark, this.geodes, this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures, this.distantHorizonsOsmRoadMaxDetail, this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch, this.realtimeTime, this.realtimeWeather, this.historicalSnow,
         this.voxyChunkPregenEnabled, this.voxyChunkPregenMaxRadius, this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode, this.demSelection, this.enableRoads, this.enableBuildings, this.enableWater,
         this.thinShellTerrain, this.climateBasedBuiltUpTerrain, this.randomBiomes, this.randomBiomeDensity,
         this.randomBiomeSeed, this.randomBiomeIds, this.experimentalIncreaseHeight, this.tellusManagedTerrainDownloads,
         this.showTerrainDownloadOverlay, this.cavesReachSurface, this.undergroundDepth, customTrees, this.automaticHeightScaling, this.hugeRedMushrooms, this.worldScaleAtSpawn, this.centerWorldOnSpawn
      );
   }

   public EarthGeneratorSettings withHugeRedMushrooms(boolean hugeRedMushrooms) {
      return new EarthGeneratorSettings(
         this.worldScale, this.terrestrialHeightScale, this.oceanicHeightScale, this.heightOffset,
         this.spawnLatitude, this.spawnLongitude, this.minAltitude, this.maxAltitude,
         this.riverLakeShorelineBlend, this.oceanShorelineBlend, this.shorelineBlendCliffLimit, this.caveGeneration, this.oreDistribution, this.geologicalStonePatches, this.lavaPools,
         this.addStrongholds, this.addVillages, this.addMineshafts, this.addOceanMonuments, this.addWoodlandMansions,
         this.addDesertTemples, this.addJungleTemples, this.addPillagerOutposts, this.addRuinedPortals, this.addShipwrecks,
         this.addOceanRuins, this.addBuriedTreasure, this.addIgloos, this.addWitchHuts, this.addAncientCities,
         this.addTrialChambers, this.addTrailRuins, this.deepDark, this.geodes, this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures, this.distantHorizonsOsmRoadMaxDetail, this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch, this.realtimeTime, this.realtimeWeather, this.historicalSnow,
         this.voxyChunkPregenEnabled, this.voxyChunkPregenMaxRadius, this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode, this.demSelection, this.enableRoads, this.enableBuildings, this.enableWater,
         this.thinShellTerrain, this.climateBasedBuiltUpTerrain, this.randomBiomes, this.randomBiomeDensity,
         this.randomBiomeSeed, this.randomBiomeIds, this.experimentalIncreaseHeight, this.tellusManagedTerrainDownloads,
         this.showTerrainDownloadOverlay, this.cavesReachSurface, this.undergroundDepth, this.customTrees,
         this.automaticHeightScaling, hugeRedMushrooms, this.worldScaleAtSpawn, this.centerWorldOnSpawn
      );
   }

   public EarthGeneratorSettings withWorldScaleAtSpawn(boolean worldScaleAtSpawn) {
      return new EarthGeneratorSettings(
         this.worldScale, this.terrestrialHeightScale, this.oceanicHeightScale, this.heightOffset,
         this.spawnLatitude, this.spawnLongitude, this.minAltitude, this.maxAltitude,
         this.riverLakeShorelineBlend, this.oceanShorelineBlend, this.shorelineBlendCliffLimit, this.caveGeneration, this.oreDistribution, this.geologicalStonePatches, this.lavaPools,
         this.addStrongholds, this.addVillages, this.addMineshafts, this.addOceanMonuments, this.addWoodlandMansions,
         this.addDesertTemples, this.addJungleTemples, this.addPillagerOutposts, this.addRuinedPortals, this.addShipwrecks,
         this.addOceanRuins, this.addBuriedTreasure, this.addIgloos, this.addWitchHuts, this.addAncientCities,
         this.addTrialChambers, this.addTrailRuins, this.deepDark, this.geodes, this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures, this.distantHorizonsOsmRoadMaxDetail, this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch, this.realtimeTime, this.realtimeWeather, this.historicalSnow,
         this.voxyChunkPregenEnabled, this.voxyChunkPregenMaxRadius, this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode, this.demSelection, this.enableRoads, this.enableBuildings, this.enableWater,
         this.thinShellTerrain, this.climateBasedBuiltUpTerrain, this.randomBiomes, this.randomBiomeDensity,
         this.randomBiomeSeed, this.randomBiomeIds, this.experimentalIncreaseHeight, this.tellusManagedTerrainDownloads,
         this.showTerrainDownloadOverlay, this.cavesReachSurface, this.undergroundDepth, this.customTrees,
         this.automaticHeightScaling, this.hugeRedMushrooms, worldScaleAtSpawn, this.centerWorldOnSpawn
      );
   }

   public EarthGeneratorSettings withSpawn(double spawnLatitude, double spawnLongitude) {
      return new EarthGeneratorSettings(
         this.worldScale, this.terrestrialHeightScale, this.oceanicHeightScale, this.heightOffset,
         spawnLatitude, spawnLongitude, this.minAltitude, this.maxAltitude,
         this.riverLakeShorelineBlend, this.oceanShorelineBlend, this.shorelineBlendCliffLimit, this.caveGeneration, this.oreDistribution, this.geologicalStonePatches, this.lavaPools,
         this.addStrongholds, this.addVillages, this.addMineshafts, this.addOceanMonuments, this.addWoodlandMansions,
         this.addDesertTemples, this.addJungleTemples, this.addPillagerOutposts, this.addRuinedPortals, this.addShipwrecks,
         this.addOceanRuins, this.addBuriedTreasure, this.addIgloos, this.addWitchHuts, this.addAncientCities,
         this.addTrialChambers, this.addTrailRuins, this.deepDark, this.geodes, this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures, this.distantHorizonsOsmRoadMaxDetail, this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch, this.realtimeTime, this.realtimeWeather, this.historicalSnow,
         this.voxyChunkPregenEnabled, this.voxyChunkPregenMaxRadius, this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode, this.demSelection, this.enableRoads, this.enableBuildings, this.enableWater,
         this.thinShellTerrain, this.climateBasedBuiltUpTerrain, this.randomBiomes, this.randomBiomeDensity,
         this.randomBiomeSeed, this.randomBiomeIds, this.experimentalIncreaseHeight, this.tellusManagedTerrainDownloads,
         this.showTerrainDownloadOverlay, this.cavesReachSurface, this.undergroundDepth, this.customTrees,
         this.automaticHeightScaling, this.hugeRedMushrooms, this.worldScaleAtSpawn, this.centerWorldOnSpawn
      );
   }

   public EarthGeneratorSettings withWorldScale(double worldScale) {
      return new EarthGeneratorSettings(
         worldScale, this.terrestrialHeightScale, this.oceanicHeightScale, this.heightOffset,
         this.spawnLatitude, this.spawnLongitude, this.minAltitude, this.maxAltitude,
         this.riverLakeShorelineBlend, this.oceanShorelineBlend, this.shorelineBlendCliffLimit, this.caveGeneration, this.oreDistribution, this.geologicalStonePatches, this.lavaPools,
         this.addStrongholds, this.addVillages, this.addMineshafts, this.addOceanMonuments, this.addWoodlandMansions,
         this.addDesertTemples, this.addJungleTemples, this.addPillagerOutposts, this.addRuinedPortals, this.addShipwrecks,
         this.addOceanRuins, this.addBuriedTreasure, this.addIgloos, this.addWitchHuts, this.addAncientCities,
         this.addTrialChambers, this.addTrailRuins, this.deepDark, this.geodes, this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures, this.distantHorizonsOsmRoadMaxDetail, this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch, this.realtimeTime, this.realtimeWeather, this.historicalSnow,
         this.voxyChunkPregenEnabled, this.voxyChunkPregenMaxRadius, this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode, this.demSelection, this.enableRoads, this.enableBuildings, this.enableWater,
         this.thinShellTerrain, this.climateBasedBuiltUpTerrain, this.randomBiomes, this.randomBiomeDensity,
         this.randomBiomeSeed, this.randomBiomeIds, this.experimentalIncreaseHeight, this.tellusManagedTerrainDownloads,
         this.showTerrainDownloadOverlay, this.cavesReachSurface, this.undergroundDepth, this.customTrees,
         this.automaticHeightScaling, this.hugeRedMushrooms, this.worldScaleAtSpawn, this.centerWorldOnSpawn
      );
   }

   public EarthGeneratorSettings withCenterWorldOnSpawn(boolean centerWorldOnSpawn) {
      return new EarthGeneratorSettings(
         this.worldScale, this.terrestrialHeightScale, this.oceanicHeightScale, this.heightOffset,
         this.spawnLatitude, this.spawnLongitude, this.minAltitude, this.maxAltitude,
         this.riverLakeShorelineBlend, this.oceanShorelineBlend, this.shorelineBlendCliffLimit, this.caveGeneration, this.oreDistribution, this.geologicalStonePatches, this.lavaPools,
         this.addStrongholds, this.addVillages, this.addMineshafts, this.addOceanMonuments, this.addWoodlandMansions,
         this.addDesertTemples, this.addJungleTemples, this.addPillagerOutposts, this.addRuinedPortals, this.addShipwrecks,
         this.addOceanRuins, this.addBuriedTreasure, this.addIgloos, this.addWitchHuts, this.addAncientCities,
         this.addTrialChambers, this.addTrailRuins, this.deepDark, this.geodes, this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures, this.distantHorizonsOsmRoadMaxDetail, this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch, this.realtimeTime, this.realtimeWeather, this.historicalSnow,
         this.voxyChunkPregenEnabled, this.voxyChunkPregenMaxRadius, this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode, this.demSelection, this.enableRoads, this.enableBuildings, this.enableWater,
         this.thinShellTerrain, this.climateBasedBuiltUpTerrain, this.randomBiomes, this.randomBiomeDensity,
         this.randomBiomeSeed, this.randomBiomeIds, this.experimentalIncreaseHeight, this.tellusManagedTerrainDownloads,
         this.showTerrainDownloadOverlay, this.cavesReachSurface, this.undergroundDepth, this.customTrees,
         this.automaticHeightScaling, this.hugeRedMushrooms, this.worldScaleAtSpawn, centerWorldOnSpawn
      );
   }

   private static EarthGeneratorSettings.SettingsBase applyVoxyChunkPregenEnabled(EarthGeneratorSettings.SettingsBase settings, Boolean enabled) {
      return settings.withVoxyChunkPregenEnabled(Objects.requireNonNull(enabled, "voxyChunkPregenEnabled"));
   }

   private static EarthGeneratorSettings.SettingsBase applyVoxyChunkPregenMaxRadius(EarthGeneratorSettings.SettingsBase settings, Integer maxRadius) {
      return settings.withVoxyChunkPregenMaxRadius(Objects.requireNonNull(maxRadius, "voxyChunkPregenMaxRadius"));
   }

   private static EarthGeneratorSettings.SettingsBase applyVoxyChunkPregenChunksPerTick(EarthGeneratorSettings.SettingsBase settings, Integer chunksPerTick) {
      return settings.withVoxyChunkPregenChunksPerTick(Objects.requireNonNull(chunksPerTick, "voxyChunkPregenChunksPerTick"));
   }

   private EarthGeneratorSettings withStructureSettings(EarthGeneratorSettings.StructureSettings structures) {
      return new EarthGeneratorSettings(
         this.worldScale,
         this.terrestrialHeightScale,
         this.oceanicHeightScale,
         this.heightOffset,
         this.spawnLatitude,
         this.spawnLongitude,
         this.minAltitude,
         this.maxAltitude,
         this.riverLakeShorelineBlend,
         this.oceanShorelineBlend,
         this.shorelineBlendCliffLimit,
         this.caveGeneration,
         this.oreDistribution,
         this.geologicalStonePatches,
         this.lavaPools,
         structures.addStrongholds(),
         structures.addVillages(),
         structures.addMineshafts(),
         structures.addOceanMonuments(),
         structures.addWoodlandMansions(),
         structures.addDesertTemples(),
         structures.addJungleTemples(),
         structures.addPillagerOutposts(),
         structures.addRuinedPortals(),
         structures.addShipwrecks(),
         structures.addOceanRuins(),
         structures.addBuriedTreasure(),
         structures.addIgloos(),
         structures.addWitchHuts(),
         structures.addAncientCities(),
         structures.addTrialChambers(),
         this.addTrailRuins,
         this.deepDark,
         this.geodes,
         this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures,
         this.distantHorizonsOsmRoadMaxDetail,
         this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch,
         this.realtimeTime,
         this.realtimeWeather,
         this.historicalSnow,
         this.voxyChunkPregenEnabled,
         this.voxyChunkPregenMaxRadius,
         this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode,
         this.demSelection,
         this.enableRoads,
         this.enableBuildings,
         this.enableWater,
         this.thinShellTerrain,
         this.climateBasedBuiltUpTerrain,
         this.randomBiomes,
         this.randomBiomeDensity,
         this.randomBiomeSeed,
         this.randomBiomeIds,
         this.experimentalIncreaseHeight,
         this.tellusManagedTerrainDownloads,
         this.showTerrainDownloadOverlay,
         this.cavesReachSurface,
         this.undergroundDepth,
         this.customTrees,
         this.automaticHeightScaling,
         this.hugeRedMushrooms, this.worldScaleAtSpawn, this.centerWorldOnSpawn
      );
   }

   private static EarthGeneratorSettings applyTrailRuins(EarthGeneratorSettings settings, Boolean addTrailRuins) {
      return settings.withTrailRuins(Objects.requireNonNull(addTrailRuins, "addTrailRuins"));
   }

   private static EarthGeneratorSettings applyDeepDark(EarthGeneratorSettings settings, Boolean deepDark) {
      return settings.withDeepDark(Objects.requireNonNull(deepDark, "deepDark"));
   }

   private static EarthGeneratorSettings applyGeodes(EarthGeneratorSettings settings, Boolean geodes) {
      return settings.withGeodes(Objects.requireNonNull(geodes, "geodes"));
   }

   private EarthGeneratorSettings withTrailRuins(boolean addTrailRuins) {
      return new EarthGeneratorSettings(
         this.worldScale,
         this.terrestrialHeightScale,
         this.oceanicHeightScale,
         this.heightOffset,
         this.spawnLatitude,
         this.spawnLongitude,
         this.minAltitude,
         this.maxAltitude,
         this.riverLakeShorelineBlend,
         this.oceanShorelineBlend,
         this.shorelineBlendCliffLimit,
         this.caveGeneration,
         this.oreDistribution,
         this.geologicalStonePatches,
         this.lavaPools,
         this.addStrongholds,
         this.addVillages,
         this.addMineshafts,
         this.addOceanMonuments,
         this.addWoodlandMansions,
         this.addDesertTemples,
         this.addJungleTemples,
         this.addPillagerOutposts,
         this.addRuinedPortals,
         this.addShipwrecks,
         this.addOceanRuins,
         this.addBuriedTreasure,
         this.addIgloos,
         this.addWitchHuts,
         this.addAncientCities,
         this.addTrialChambers,
         addTrailRuins,
         this.deepDark,
         this.geodes,
         this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures,
         this.distantHorizonsOsmRoadMaxDetail,
         this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch,
         this.realtimeTime,
         this.realtimeWeather,
         this.historicalSnow,
         this.voxyChunkPregenEnabled,
         this.voxyChunkPregenMaxRadius,
         this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode,
         this.demSelection,
         this.enableRoads,
         this.enableBuildings,
         this.enableWater,
         this.thinShellTerrain,
         this.climateBasedBuiltUpTerrain,
         this.randomBiomes,
         this.randomBiomeDensity,
         this.randomBiomeSeed,
         this.randomBiomeIds,
         this.experimentalIncreaseHeight,
         this.tellusManagedTerrainDownloads,
         this.showTerrainDownloadOverlay,
         this.cavesReachSurface,
         this.undergroundDepth,
         this.customTrees,
         this.automaticHeightScaling,
         this.hugeRedMushrooms, this.worldScaleAtSpawn, this.centerWorldOnSpawn
      );
   }

   private EarthGeneratorSettings withDeepDark(boolean deepDark) {
      return new EarthGeneratorSettings(
         this.worldScale,
         this.terrestrialHeightScale,
         this.oceanicHeightScale,
         this.heightOffset,
         this.spawnLatitude,
         this.spawnLongitude,
         this.minAltitude,
         this.maxAltitude,
         this.riverLakeShorelineBlend,
         this.oceanShorelineBlend,
         this.shorelineBlendCliffLimit,
         this.caveGeneration,
         this.oreDistribution,
         this.geologicalStonePatches,
         this.lavaPools,
         this.addStrongholds,
         this.addVillages,
         this.addMineshafts,
         this.addOceanMonuments,
         this.addWoodlandMansions,
         this.addDesertTemples,
         this.addJungleTemples,
         this.addPillagerOutposts,
         this.addRuinedPortals,
         this.addShipwrecks,
         this.addOceanRuins,
         this.addBuriedTreasure,
         this.addIgloos,
         this.addWitchHuts,
         this.addAncientCities,
         this.addTrialChambers,
         this.addTrailRuins,
         deepDark,
         this.geodes,
         this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures,
         this.distantHorizonsOsmRoadMaxDetail,
         this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch,
         this.realtimeTime,
         this.realtimeWeather,
         this.historicalSnow,
         this.voxyChunkPregenEnabled,
         this.voxyChunkPregenMaxRadius,
         this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode,
         this.demSelection,
         this.enableRoads,
         this.enableBuildings,
         this.enableWater,
         this.thinShellTerrain,
         this.climateBasedBuiltUpTerrain,
         this.randomBiomes,
         this.randomBiomeDensity,
         this.randomBiomeSeed,
         this.randomBiomeIds,
         this.experimentalIncreaseHeight,
         this.tellusManagedTerrainDownloads,
         this.showTerrainDownloadOverlay,
         this.cavesReachSurface,
         this.undergroundDepth,
         this.customTrees,
         this.automaticHeightScaling,
         this.hugeRedMushrooms, this.worldScaleAtSpawn, this.centerWorldOnSpawn
      );
   }

   private EarthGeneratorSettings withGeodes(boolean geodes) {
      return new EarthGeneratorSettings(
         this.worldScale,
         this.terrestrialHeightScale,
         this.oceanicHeightScale,
         this.heightOffset,
         this.spawnLatitude,
         this.spawnLongitude,
         this.minAltitude,
         this.maxAltitude,
         this.riverLakeShorelineBlend,
         this.oceanShorelineBlend,
         this.shorelineBlendCliffLimit,
         this.caveGeneration,
         this.oreDistribution,
         this.geologicalStonePatches,
         this.lavaPools,
         this.addStrongholds,
         this.addVillages,
         this.addMineshafts,
         this.addOceanMonuments,
         this.addWoodlandMansions,
         this.addDesertTemples,
         this.addJungleTemples,
         this.addPillagerOutposts,
         this.addRuinedPortals,
         this.addShipwrecks,
         this.addOceanRuins,
         this.addBuriedTreasure,
         this.addIgloos,
         this.addWitchHuts,
         this.addAncientCities,
         this.addTrialChambers,
         this.addTrailRuins,
         this.deepDark,
         geodes,
         this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures,
         this.distantHorizonsOsmRoadMaxDetail,
         this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch,
         this.realtimeTime,
         this.realtimeWeather,
         this.historicalSnow,
         this.voxyChunkPregenEnabled,
         this.voxyChunkPregenMaxRadius,
         this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode,
         this.demSelection,
         this.enableRoads,
         this.enableBuildings,
         this.enableWater,
         this.thinShellTerrain,
         this.climateBasedBuiltUpTerrain,
         this.randomBiomes,
         this.randomBiomeDensity,
         this.randomBiomeSeed,
         this.randomBiomeIds,
         this.experimentalIncreaseHeight,
         this.tellusManagedTerrainDownloads,
         this.showTerrainDownloadOverlay,
         this.cavesReachSurface,
         this.undergroundDepth,
         this.customTrees,
         this.automaticHeightScaling,
         this.hugeRedMushrooms, this.worldScaleAtSpawn, this.centerWorldOnSpawn
      );
   }

   private EarthGeneratorSettings withDistantHorizonsOsmRoadMaxDetail(int maxDetail) {
      return new EarthGeneratorSettings(
         this.worldScale,
         this.terrestrialHeightScale,
         this.oceanicHeightScale,
         this.heightOffset,
         this.spawnLatitude,
         this.spawnLongitude,
         this.minAltitude,
         this.maxAltitude,
         this.riverLakeShorelineBlend,
         this.oceanShorelineBlend,
         this.shorelineBlendCliffLimit,
         this.caveGeneration,
         this.oreDistribution,
         this.geologicalStonePatches,
         this.lavaPools,
         this.addStrongholds,
         this.addVillages,
         this.addMineshafts,
         this.addOceanMonuments,
         this.addWoodlandMansions,
         this.addDesertTemples,
         this.addJungleTemples,
         this.addPillagerOutposts,
         this.addRuinedPortals,
         this.addShipwrecks,
         this.addOceanRuins,
         this.addBuriedTreasure,
         this.addIgloos,
         this.addWitchHuts,
         this.addAncientCities,
         this.addTrialChambers,
         this.addTrailRuins,
         this.deepDark,
         this.geodes,
         this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures,
         maxDetail,
         this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch,
         this.realtimeTime,
         this.realtimeWeather,
         this.historicalSnow,
         this.voxyChunkPregenEnabled,
         this.voxyChunkPregenMaxRadius,
         this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode,
         this.demSelection,
         this.enableRoads,
         this.enableBuildings,
         this.enableWater,
         this.thinShellTerrain,
         this.climateBasedBuiltUpTerrain,
         this.randomBiomes,
         this.randomBiomeDensity,
         this.randomBiomeSeed,
         this.randomBiomeIds,
         this.experimentalIncreaseHeight,
         this.tellusManagedTerrainDownloads,
         this.showTerrainDownloadOverlay,
         this.cavesReachSurface,
         this.undergroundDepth,
         this.customTrees,
         this.automaticHeightScaling,
         this.hugeRedMushrooms, this.worldScaleAtSpawn, this.centerWorldOnSpawn
      );
   }

   private EarthGeneratorSettings withDistantHorizonsOsmBuildingMaxDetail(int maxDetail) {
      return new EarthGeneratorSettings(
         this.worldScale,
         this.terrestrialHeightScale,
         this.oceanicHeightScale,
         this.heightOffset,
         this.spawnLatitude,
         this.spawnLongitude,
         this.minAltitude,
         this.maxAltitude,
         this.riverLakeShorelineBlend,
         this.oceanShorelineBlend,
         this.shorelineBlendCliffLimit,
         this.caveGeneration,
         this.oreDistribution,
         this.geologicalStonePatches,
         this.lavaPools,
         this.addStrongholds,
         this.addVillages,
         this.addMineshafts,
         this.addOceanMonuments,
         this.addWoodlandMansions,
         this.addDesertTemples,
         this.addJungleTemples,
         this.addPillagerOutposts,
         this.addRuinedPortals,
         this.addShipwrecks,
         this.addOceanRuins,
         this.addBuriedTreasure,
         this.addIgloos,
         this.addWitchHuts,
         this.addAncientCities,
         this.addTrialChambers,
         this.addTrailRuins,
         this.deepDark,
         this.geodes,
         this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures,
         this.distantHorizonsOsmRoadMaxDetail,
         maxDetail,
         this.distantHorizonsOsmNonBlockingFetch,
         this.realtimeTime,
         this.realtimeWeather,
         this.historicalSnow,
         this.voxyChunkPregenEnabled,
         this.voxyChunkPregenMaxRadius,
         this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode,
         this.demSelection,
         this.enableRoads,
         this.enableBuildings,
         this.enableWater,
         this.thinShellTerrain,
         this.climateBasedBuiltUpTerrain,
         this.randomBiomes,
         this.randomBiomeDensity,
         this.randomBiomeSeed,
         this.randomBiomeIds,
         this.experimentalIncreaseHeight,
         this.tellusManagedTerrainDownloads,
         this.showTerrainDownloadOverlay,
         this.cavesReachSurface,
         this.undergroundDepth,
         this.customTrees,
         this.automaticHeightScaling,
         this.hugeRedMushrooms, this.worldScaleAtSpawn, this.centerWorldOnSpawn
      );
   }

   private EarthGeneratorSettings withDistantHorizonsOsmNonBlockingFetch(boolean nonBlockingFetch) {
      return new EarthGeneratorSettings(
         this.worldScale,
         this.terrestrialHeightScale,
         this.oceanicHeightScale,
         this.heightOffset,
         this.spawnLatitude,
         this.spawnLongitude,
         this.minAltitude,
         this.maxAltitude,
         this.riverLakeShorelineBlend,
         this.oceanShorelineBlend,
         this.shorelineBlendCliffLimit,
         this.caveGeneration,
         this.oreDistribution,
         this.geologicalStonePatches,
         this.lavaPools,
         this.addStrongholds,
         this.addVillages,
         this.addMineshafts,
         this.addOceanMonuments,
         this.addWoodlandMansions,
         this.addDesertTemples,
         this.addJungleTemples,
         this.addPillagerOutposts,
         this.addRuinedPortals,
         this.addShipwrecks,
         this.addOceanRuins,
         this.addBuriedTreasure,
         this.addIgloos,
         this.addWitchHuts,
         this.addAncientCities,
         this.addTrialChambers,
         this.addTrailRuins,
         this.deepDark,
         this.geodes,
         this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures,
         this.distantHorizonsOsmRoadMaxDetail,
         this.distantHorizonsOsmBuildingMaxDetail,
         nonBlockingFetch,
         this.realtimeTime,
         this.realtimeWeather,
         this.historicalSnow,
         this.voxyChunkPregenEnabled,
         this.voxyChunkPregenMaxRadius,
         this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode,
         this.demSelection,
         this.enableRoads,
         this.enableBuildings,
         this.enableWater,
         this.thinShellTerrain,
         this.climateBasedBuiltUpTerrain,
         this.randomBiomes,
         this.randomBiomeDensity,
         this.randomBiomeSeed,
         this.randomBiomeIds,
         this.experimentalIncreaseHeight,
         this.tellusManagedTerrainDownloads,
         this.showTerrainDownloadOverlay,
         this.cavesReachSurface,
         this.undergroundDepth,
         this.customTrees,
         this.automaticHeightScaling,
         this.hugeRedMushrooms, this.worldScaleAtSpawn, this.centerWorldOnSpawn
      );
   }

   private EarthGeneratorSettings withEnableRoads(boolean enableRoads) {
      return new EarthGeneratorSettings(
         this.worldScale,
         this.terrestrialHeightScale,
         this.oceanicHeightScale,
         this.heightOffset,
         this.spawnLatitude,
         this.spawnLongitude,
         this.minAltitude,
         this.maxAltitude,
         this.riverLakeShorelineBlend,
         this.oceanShorelineBlend,
         this.shorelineBlendCliffLimit,
         this.caveGeneration,
         this.oreDistribution,
         this.geologicalStonePatches,
         this.lavaPools,
         this.addStrongholds,
         this.addVillages,
         this.addMineshafts,
         this.addOceanMonuments,
         this.addWoodlandMansions,
         this.addDesertTemples,
         this.addJungleTemples,
         this.addPillagerOutposts,
         this.addRuinedPortals,
         this.addShipwrecks,
         this.addOceanRuins,
         this.addBuriedTreasure,
         this.addIgloos,
         this.addWitchHuts,
         this.addAncientCities,
         this.addTrialChambers,
         this.addTrailRuins,
         this.deepDark,
         this.geodes,
         this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures,
         this.distantHorizonsOsmRoadMaxDetail,
         this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch,
         this.realtimeTime,
         this.realtimeWeather,
         this.historicalSnow,
         this.voxyChunkPregenEnabled,
         this.voxyChunkPregenMaxRadius,
         this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode,
         this.demSelection,
         enableRoads,
         this.enableBuildings,
         this.enableWater,
         this.thinShellTerrain,
         this.climateBasedBuiltUpTerrain,
         this.randomBiomes,
         this.randomBiomeDensity,
         this.randomBiomeSeed,
         this.randomBiomeIds,
         this.experimentalIncreaseHeight,
         this.tellusManagedTerrainDownloads,
         this.showTerrainDownloadOverlay,
         this.cavesReachSurface,
         this.undergroundDepth,
         this.customTrees,
         this.automaticHeightScaling,
         this.hugeRedMushrooms, this.worldScaleAtSpawn, this.centerWorldOnSpawn
      );
   }

   private EarthGeneratorSettings withEnableBuildings(boolean enableBuildings) {
      return new EarthGeneratorSettings(
         this.worldScale,
         this.terrestrialHeightScale,
         this.oceanicHeightScale,
         this.heightOffset,
         this.spawnLatitude,
         this.spawnLongitude,
         this.minAltitude,
         this.maxAltitude,
         this.riverLakeShorelineBlend,
         this.oceanShorelineBlend,
         this.shorelineBlendCliffLimit,
         this.caveGeneration,
         this.oreDistribution,
         this.geologicalStonePatches,
         this.lavaPools,
         this.addStrongholds,
         this.addVillages,
         this.addMineshafts,
         this.addOceanMonuments,
         this.addWoodlandMansions,
         this.addDesertTemples,
         this.addJungleTemples,
         this.addPillagerOutposts,
         this.addRuinedPortals,
         this.addShipwrecks,
         this.addOceanRuins,
         this.addBuriedTreasure,
         this.addIgloos,
         this.addWitchHuts,
         this.addAncientCities,
         this.addTrialChambers,
         this.addTrailRuins,
         this.deepDark,
         this.geodes,
         this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures,
         this.distantHorizonsOsmRoadMaxDetail,
         this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch,
         this.realtimeTime,
         this.realtimeWeather,
         this.historicalSnow,
         this.voxyChunkPregenEnabled,
         this.voxyChunkPregenMaxRadius,
         this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode,
         this.demSelection,
         this.enableRoads,
         enableBuildings,
         this.enableWater,
         this.thinShellTerrain,
         this.climateBasedBuiltUpTerrain,
         this.randomBiomes,
         this.randomBiomeDensity,
         this.randomBiomeSeed,
         this.randomBiomeIds,
         this.experimentalIncreaseHeight,
         this.tellusManagedTerrainDownloads,
         this.showTerrainDownloadOverlay,
         this.cavesReachSurface,
         this.undergroundDepth,
         this.customTrees,
         this.automaticHeightScaling,
         this.hugeRedMushrooms, this.worldScaleAtSpawn, this.centerWorldOnSpawn
      );
   }

   private EarthGeneratorSettings withEnableWater(boolean enableWater) {
      return new EarthGeneratorSettings(
         this.worldScale,
         this.terrestrialHeightScale,
         this.oceanicHeightScale,
         this.heightOffset,
         this.spawnLatitude,
         this.spawnLongitude,
         this.minAltitude,
         this.maxAltitude,
         this.riverLakeShorelineBlend,
         this.oceanShorelineBlend,
         this.shorelineBlendCliffLimit,
         this.caveGeneration,
         this.oreDistribution,
         this.geologicalStonePatches,
         this.lavaPools,
         this.addStrongholds,
         this.addVillages,
         this.addMineshafts,
         this.addOceanMonuments,
         this.addWoodlandMansions,
         this.addDesertTemples,
         this.addJungleTemples,
         this.addPillagerOutposts,
         this.addRuinedPortals,
         this.addShipwrecks,
         this.addOceanRuins,
         this.addBuriedTreasure,
         this.addIgloos,
         this.addWitchHuts,
         this.addAncientCities,
         this.addTrialChambers,
         this.addTrailRuins,
         this.deepDark,
         this.geodes,
         this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures,
         this.distantHorizonsOsmRoadMaxDetail,
         this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch,
         this.realtimeTime,
         this.realtimeWeather,
         this.historicalSnow,
         this.voxyChunkPregenEnabled,
         this.voxyChunkPregenMaxRadius,
         this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode,
         this.demSelection,
         this.enableRoads,
         this.enableBuildings,
         enableWater,
         this.thinShellTerrain,
         this.climateBasedBuiltUpTerrain,
         this.randomBiomes,
         this.randomBiomeDensity,
         this.randomBiomeSeed,
         this.randomBiomeIds,
         this.experimentalIncreaseHeight,
         this.tellusManagedTerrainDownloads,
         this.showTerrainDownloadOverlay,
         this.cavesReachSurface,
         this.undergroundDepth,
         this.customTrees,
         this.automaticHeightScaling,
         this.hugeRedMushrooms, this.worldScaleAtSpawn, this.centerWorldOnSpawn
      );
   }

   private EarthGeneratorSettings withThinShellTerrain(boolean thinShellTerrain) {
      return new EarthGeneratorSettings(
         this.worldScale,
         this.terrestrialHeightScale,
         this.oceanicHeightScale,
         this.heightOffset,
         this.spawnLatitude,
         this.spawnLongitude,
         this.minAltitude,
         this.maxAltitude,
         this.riverLakeShorelineBlend,
         this.oceanShorelineBlend,
         this.shorelineBlendCliffLimit,
         this.caveGeneration,
         this.oreDistribution,
         this.geologicalStonePatches,
         this.lavaPools,
         this.addStrongholds,
         this.addVillages,
         this.addMineshafts,
         this.addOceanMonuments,
         this.addWoodlandMansions,
         this.addDesertTemples,
         this.addJungleTemples,
         this.addPillagerOutposts,
         this.addRuinedPortals,
         this.addShipwrecks,
         this.addOceanRuins,
         this.addBuriedTreasure,
         this.addIgloos,
         this.addWitchHuts,
         this.addAncientCities,
         this.addTrialChambers,
         this.addTrailRuins,
         this.deepDark,
         this.geodes,
         this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures,
         this.distantHorizonsOsmRoadMaxDetail,
         this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch,
         this.realtimeTime,
         this.realtimeWeather,
         this.historicalSnow,
         this.voxyChunkPregenEnabled,
         this.voxyChunkPregenMaxRadius,
         this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode,
         this.demSelection,
         this.enableRoads,
         this.enableBuildings,
         this.enableWater,
         thinShellTerrain,
         this.climateBasedBuiltUpTerrain,
         this.randomBiomes,
         this.randomBiomeDensity,
         this.randomBiomeSeed,
         this.randomBiomeIds,
         this.experimentalIncreaseHeight,
         this.tellusManagedTerrainDownloads,
         this.showTerrainDownloadOverlay,
         this.cavesReachSurface,
         this.undergroundDepth,
         this.customTrees,
         this.automaticHeightScaling,
         this.hugeRedMushrooms, this.worldScaleAtSpawn, this.centerWorldOnSpawn
      );
   }

   private EarthGeneratorSettings withClimateBasedBuiltUpTerrain(boolean climateBasedBuiltUpTerrain) {
      return new EarthGeneratorSettings(
         this.worldScale,
         this.terrestrialHeightScale,
         this.oceanicHeightScale,
         this.heightOffset,
         this.spawnLatitude,
         this.spawnLongitude,
         this.minAltitude,
         this.maxAltitude,
         this.riverLakeShorelineBlend,
         this.oceanShorelineBlend,
         this.shorelineBlendCliffLimit,
         this.caveGeneration,
         this.oreDistribution,
         this.geologicalStonePatches,
         this.lavaPools,
         this.addStrongholds,
         this.addVillages,
         this.addMineshafts,
         this.addOceanMonuments,
         this.addWoodlandMansions,
         this.addDesertTemples,
         this.addJungleTemples,
         this.addPillagerOutposts,
         this.addRuinedPortals,
         this.addShipwrecks,
         this.addOceanRuins,
         this.addBuriedTreasure,
         this.addIgloos,
         this.addWitchHuts,
         this.addAncientCities,
         this.addTrialChambers,
         this.addTrailRuins,
         this.deepDark,
         this.geodes,
         this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures,
         this.distantHorizonsOsmRoadMaxDetail,
         this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch,
         this.realtimeTime,
         this.realtimeWeather,
         this.historicalSnow,
         this.voxyChunkPregenEnabled,
         this.voxyChunkPregenMaxRadius,
         this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode,
         this.demSelection,
         this.enableRoads,
         this.enableBuildings,
         this.enableWater,
         this.thinShellTerrain,
         climateBasedBuiltUpTerrain,
         this.randomBiomes,
         this.randomBiomeDensity,
         this.randomBiomeSeed,
         this.randomBiomeIds,
         this.experimentalIncreaseHeight,
         this.tellusManagedTerrainDownloads,
         this.showTerrainDownloadOverlay,
         this.cavesReachSurface,
         this.undergroundDepth,
         this.customTrees,
         this.automaticHeightScaling,
         this.hugeRedMushrooms, this.worldScaleAtSpawn, this.centerWorldOnSpawn
      );
   }

   private EarthGeneratorSettings withRandomBiomes(boolean randomBiomes) {
      return this.withRandomBiomeSettings(randomBiomes, this.randomBiomeDensity, this.randomBiomeSeed, this.randomBiomeIds);
   }

   private EarthGeneratorSettings withRandomBiomeDensity(double randomBiomeDensity) {
      return this.withRandomBiomeSettings(this.randomBiomes, randomBiomeDensity, this.randomBiomeSeed, this.randomBiomeIds);
   }

   private EarthGeneratorSettings withRandomBiomeSeed(long randomBiomeSeed) {
      return this.withRandomBiomeSettings(this.randomBiomes, this.randomBiomeDensity, randomBiomeSeed, this.randomBiomeIds);
   }

   private EarthGeneratorSettings withRandomBiomeIds(List<String> randomBiomeIds) {
      return this.withRandomBiomeSettings(this.randomBiomes, this.randomBiomeDensity, this.randomBiomeSeed, randomBiomeIds);
   }

   private EarthGeneratorSettings withRandomBiomeSettings(
      boolean randomBiomes, double randomBiomeDensity, long randomBiomeSeed, List<String> randomBiomeIds
   ) {
      return new EarthGeneratorSettings(
         this.worldScale,
         this.terrestrialHeightScale,
         this.oceanicHeightScale,
         this.heightOffset,
         this.spawnLatitude,
         this.spawnLongitude,
         this.minAltitude,
         this.maxAltitude,
         this.riverLakeShorelineBlend,
         this.oceanShorelineBlend,
         this.shorelineBlendCliffLimit,
         this.caveGeneration,
         this.oreDistribution,
         this.geologicalStonePatches,
         this.lavaPools,
         this.addStrongholds,
         this.addVillages,
         this.addMineshafts,
         this.addOceanMonuments,
         this.addWoodlandMansions,
         this.addDesertTemples,
         this.addJungleTemples,
         this.addPillagerOutposts,
         this.addRuinedPortals,
         this.addShipwrecks,
         this.addOceanRuins,
         this.addBuriedTreasure,
         this.addIgloos,
         this.addWitchHuts,
         this.addAncientCities,
         this.addTrialChambers,
         this.addTrailRuins,
         this.deepDark,
         this.geodes,
         this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures,
         this.distantHorizonsOsmRoadMaxDetail,
         this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch,
         this.realtimeTime,
         this.realtimeWeather,
         this.historicalSnow,
         this.voxyChunkPregenEnabled,
         this.voxyChunkPregenMaxRadius,
         this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode,
         this.demSelection,
         this.enableRoads,
         this.enableBuildings,
         this.enableWater,
         this.thinShellTerrain,
         this.climateBasedBuiltUpTerrain,
         randomBiomes,
         randomBiomeDensity,
         randomBiomeSeed,
         randomBiomeIds,
         this.experimentalIncreaseHeight,
         this.tellusManagedTerrainDownloads,
         this.showTerrainDownloadOverlay,
         this.cavesReachSurface,
         this.undergroundDepth,
         this.customTrees,
         this.automaticHeightScaling,
         this.hugeRedMushrooms, this.worldScaleAtSpawn, this.centerWorldOnSpawn
      );
   }

   private static EarthGeneratorSettings applyExperimentalIncreaseHeight(EarthGeneratorSettings settings, Boolean enabled) {
      return settings.withExperimentalIncreaseHeight(Objects.requireNonNull(enabled, "experimentalIncreaseHeight"));
   }

   private static EarthGeneratorSettings applyAutomaticHeightScaling(EarthGeneratorSettings settings, Boolean enabled) {
      return settings.withAutomaticHeightScaling(Objects.requireNonNull(enabled, "automaticHeightScaling"));
   }

   private static DataResult<EarthGeneratorSettings> validateExperimentalHeightCoordinateProfile(
      EarthGeneratorSettings settings, Optional<String> serializedProfile
   ) {
      if (!settings.experimentalIncreaseHeight()) {
         return DataResult.success(settings);
      }

      if (serializedProfile.isEmpty()) {
         return DataResult.error(
            () -> "Increase Height world is missing experimental_height_coordinate_profile; it may use an incompatible legacy BlockPos encoding"
         );
      }

      String profile = serializedProfile.get();
      if (!HighYPackedCoordinateProfile.PROFILE_ID.equals(profile)) {
         return DataResult.error(
            () -> "Increase Height world uses incompatible coordinate profile '"
               + profile
               + "'; expected '"
               + HighYPackedCoordinateProfile.PROFILE_ID
               + "'"
         );
      }

      return DataResult.success(settings);
   }

   private EarthGeneratorSettings withExperimentalIncreaseHeight(boolean experimentalIncreaseHeight) {
      return new EarthGeneratorSettings(
         this.worldScale,
         this.terrestrialHeightScale,
         this.oceanicHeightScale,
         this.heightOffset,
         this.spawnLatitude,
         this.spawnLongitude,
         this.minAltitude,
         this.maxAltitude,
         this.riverLakeShorelineBlend,
         this.oceanShorelineBlend,
         this.shorelineBlendCliffLimit,
         this.caveGeneration,
         this.oreDistribution,
         this.geologicalStonePatches,
         this.lavaPools,
         this.addStrongholds,
         this.addVillages,
         this.addMineshafts,
         this.addOceanMonuments,
         this.addWoodlandMansions,
         this.addDesertTemples,
         this.addJungleTemples,
         this.addPillagerOutposts,
         this.addRuinedPortals,
         this.addShipwrecks,
         this.addOceanRuins,
         this.addBuriedTreasure,
         this.addIgloos,
         this.addWitchHuts,
         this.addAncientCities,
         this.addTrialChambers,
         this.addTrailRuins,
         this.deepDark,
         this.geodes,
         this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures,
         this.distantHorizonsOsmRoadMaxDetail,
         this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch,
         this.realtimeTime,
         this.realtimeWeather,
         this.historicalSnow,
         this.voxyChunkPregenEnabled,
         this.voxyChunkPregenMaxRadius,
         this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode,
         this.demSelection,
         this.enableRoads,
         this.enableBuildings,
         this.enableWater,
         this.thinShellTerrain,
         this.climateBasedBuiltUpTerrain,
         this.randomBiomes,
         this.randomBiomeDensity,
         this.randomBiomeSeed,
         this.randomBiomeIds,
         experimentalIncreaseHeight,
         this.tellusManagedTerrainDownloads,
         this.showTerrainDownloadOverlay,
         this.cavesReachSurface,
         this.undergroundDepth,
         this.customTrees,
         this.automaticHeightScaling,
         this.hugeRedMushrooms, this.worldScaleAtSpawn, this.centerWorldOnSpawn
      );
   }

   private EarthGeneratorSettings withAutomaticHeightScaling(boolean automaticHeightScaling) {
      return new EarthGeneratorSettings(
         this.worldScale,
         this.terrestrialHeightScale,
         this.oceanicHeightScale,
         this.heightOffset,
         this.spawnLatitude,
         this.spawnLongitude,
         this.minAltitude,
         this.maxAltitude,
         this.riverLakeShorelineBlend,
         this.oceanShorelineBlend,
         this.shorelineBlendCliffLimit,
         this.caveGeneration,
         this.oreDistribution,
         this.geologicalStonePatches,
         this.lavaPools,
         this.addStrongholds,
         this.addVillages,
         this.addMineshafts,
         this.addOceanMonuments,
         this.addWoodlandMansions,
         this.addDesertTemples,
         this.addJungleTemples,
         this.addPillagerOutposts,
         this.addRuinedPortals,
         this.addShipwrecks,
         this.addOceanRuins,
         this.addBuriedTreasure,
         this.addIgloos,
         this.addWitchHuts,
         this.addAncientCities,
         this.addTrialChambers,
         this.addTrailRuins,
         this.deepDark,
         this.geodes,
         this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures,
         this.distantHorizonsOsmRoadMaxDetail,
         this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch,
         this.realtimeTime,
         this.realtimeWeather,
         this.historicalSnow,
         this.voxyChunkPregenEnabled,
         this.voxyChunkPregenMaxRadius,
         this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode,
         this.demSelection,
         this.enableRoads,
         this.enableBuildings,
         this.enableWater,
         this.thinShellTerrain,
         this.climateBasedBuiltUpTerrain,
         this.randomBiomes,
         this.randomBiomeDensity,
         this.randomBiomeSeed,
         this.randomBiomeIds,
         this.experimentalIncreaseHeight,
         this.tellusManagedTerrainDownloads,
         this.showTerrainDownloadOverlay,
         this.cavesReachSurface,
         this.undergroundDepth,
         this.customTrees,
         automaticHeightScaling,
         this.hugeRedMushrooms, this.worldScaleAtSpawn, this.centerWorldOnSpawn
      );
   }

   public static EarthGeneratorSettings.HeightLimits resolveHeightLimits(EarthGeneratorSettings settings) {
      if (settings.experimentalIncreaseHeight()) {
         return resolveExperimentalHeightLimits();
      }

      int autoMin = computeAutoMinAltitude(settings);
      int autoMax = computeAutoMaxAltitude(settings);
      boolean autoMinEnabled = settings.effectiveMinAltitude() == Integer.MIN_VALUE;
      boolean autoMaxEnabled = settings.effectiveMaxAltitude() == Integer.MIN_VALUE;
      if ((!autoMinEnabled || autoMin >= MIN_WORLD_Y) && (!autoMaxEnabled || autoMax <= MAX_WORLD_Y)) {
         int resolvedMin = autoMinEnabled ? autoMin : settings.effectiveMinAltitude();
         int resolvedMax = autoMaxEnabled ? autoMax : settings.effectiveMaxAltitude();
         if (resolvedMin > resolvedMax) {
            int swap = resolvedMin;
            resolvedMin = resolvedMax;
            resolvedMax = swap;
         }

         resolvedMin = Mth.clamp(resolvedMin, MIN_WORLD_Y, MAX_WORLD_Y);
         resolvedMax = Mth.clamp(resolvedMax, MIN_WORLD_Y, MAX_WORLD_Y);
         int alignedMin = alignDown(resolvedMin, HEIGHT_ALIGNMENT);
         int alignedTop = alignUp(resolvedMax + 1, HEIGHT_ALIGNMENT);
         int height = alignedTop - alignedMin;
         if (alignedMin >= MIN_WORLD_Y && alignedTop - 1 <= MAX_WORLD_Y && height <= MAX_WORLD_HEIGHT) {
            if (height <= 0) {
               height = HEIGHT_ALIGNMENT;
               alignedTop = alignedMin + height;
               if (alignedTop - 1 > MAX_WORLD_Y) {
                  return EarthGeneratorSettings.HeightLimits.maxRange();
               }
            }

            return new EarthGeneratorSettings.HeightLimits(alignedMin, height, height);
         } else {
            return EarthGeneratorSettings.HeightLimits.maxRange();
         }
      } else {
         return EarthGeneratorSettings.HeightLimits.maxRange();
      }
   }

   private static EarthGeneratorSettings.HeightLimits resolveExperimentalHeightLimits() {
      // Increase Height owns the complete land-priority packed range. Keeping
      // this fixed prevents a valid high-latitude column from being clipped by
      // spawn-local automatic bounds.
      return new EarthGeneratorSettings.HeightLimits(
         HighYPackedCoordinateProfile.TELLUS_DIMENSION_MIN_Y,
         HighYPackedCoordinateProfile.TELLUS_DIMENSION_Y_SIZE,
         HighYPackedCoordinateProfile.TELLUS_DIMENSION_Y_SIZE
      );
   }

   private static int computeAutoMaxAltitude(EarthGeneratorSettings settings) {
      double verticalScale = settings.effectiveVerticalWorldScale();
      if (verticalScale <= 0.0) {
         return settings.effectiveHeightOffset();
      } else {
         double scaled = EVEREST_ELEVATION_METERS
            * settings.effectiveTerrestrialHeightScale()
            * automaticHeightScaleCorrection(settings)
            / verticalScale;
         int maxSurface = Mth.ceil(scaled) + settings.effectiveHeightOffset();
         return maxSurface + ALTITUDE_TOLERANCE;
      }
   }

   private static int computeAutoMinAltitude(EarthGeneratorSettings settings) {
      double verticalScale = settings.effectiveVerticalWorldScale();
      if (verticalScale <= 0.0) {
         return settings.effectiveHeightOffset() - settings.undergroundDepth();
      } else {
         double scaled = MARIANA_TRENCH_METERS
            * settings.effectiveOceanicHeightScale()
            * automaticHeightScaleCorrection(settings)
            / verticalScale;
         int minSurface = Mth.floor(scaled) + settings.effectiveHeightOffset();
         return minSurface - settings.undergroundDepth();
      }
   }

   private static double automaticHeightScaleCorrection(EarthGeneratorSettings settings) {
      if (!settings.automaticHeightScaling()) {
         return 1.0;
      }

      // Reserve the selected latitude while retaining Everest as the global summit reference.
      return Math.max(
         EarthProjection.heightScaleCorrectionAtLatitude(DEFAULT_SPAWN_LATITUDE),
         EarthProjection.heightScaleCorrectionAtLatitude(settings.spawnLatitude())
      );
   }

   private static int alignDown(int value, int alignment) {
      int remainder = Math.floorMod(value, alignment);
      return value - remainder;
   }

   private static int alignUp(int value, int alignment) {
      int remainder = Math.floorMod(value, alignment);
      return remainder == 0 ? value : value + (alignment - remainder);
   }

   public static DimensionType applyHeightLimits(DimensionType base, EarthGeneratorSettings.HeightLimits limits) {
      return MinecraftVersionCompat.applyHeightLimits(base, limits);
   }

   private record BaseToggles(
      Optional<Boolean> caveGeneration,
      Optional<Boolean> caveCarvers,
      Optional<Boolean> largeCaves,
      Optional<Boolean> canyonCarvers,
      boolean oreDistribution,
      boolean geologicalStonePatches,
      boolean lavaPools
   ) {
      private boolean resolveCaveGeneration() {
         if (this.caveGeneration.isPresent()) {
            return this.caveGeneration.get();
         } else {
            boolean hasLegacy = this.caveCarvers.isPresent() || this.largeCaves.isPresent() || this.canyonCarvers.isPresent();
            return !hasLegacy
               ? EarthGeneratorSettings.DEFAULT.caveGeneration()
               : this.caveCarvers.orElse(false) || this.largeCaves.orElse(false) || this.canyonCarvers.orElse(false);
         }
      }
   }

   public static enum DemProvider {
      AUTO("auto", false, 0),
      TERRARIUM("terrarium", true, 1);

      public static final Codec<EarthGeneratorSettings.DemProvider> CODEC = Codec.STRING
         .xmap(EarthGeneratorSettings.DemProvider::fromId, EarthGeneratorSettings.DemProvider::id);
      private final String id;
      private final boolean userSelectable;
      private final int selectionBit;

      private DemProvider(String id, boolean userSelectable, int selectionBit) {
         this.id = Objects.requireNonNull(id, "id");
         this.userSelectable = userSelectable;
         this.selectionBit = selectionBit;
      }

      public String id() {
         return this.id;
      }

      public boolean userSelectable() {
         return this.userSelectable;
      }

      public boolean available() {
         return true;
      }

      public int selectionBit() {
         return this.selectionBit;
      }

      public static EarthGeneratorSettings.DemProvider fromId(String id) {
         return "terrarium".equalsIgnoreCase(id) ? TERRARIUM : AUTO;
      }
   }

   public record DemSelection(boolean automatic, int enabledProviderMask) {
      private static final List<EarthGeneratorSettings.DemProvider> USER_SELECTABLE_PROVIDERS = List.of(EarthGeneratorSettings.DemProvider.TERRARIUM);
      private static final int MAPTERHORN_PROVIDER_MASK = EarthGeneratorSettings.DemProvider.TERRARIUM.selectionBit();
      private static final List<String> PROVIDER_IDS = List.of(EarthGeneratorSettings.DemProvider.TERRARIUM.id());
      private static final EarthGeneratorSettings.DemSelection MAPTERHORN_SELECTION = new EarthGeneratorSettings.DemSelection(false, MAPTERHORN_PROVIDER_MASK);

      public DemSelection {
         automatic = false;
         enabledProviderMask = MAPTERHORN_PROVIDER_MASK;
      }

      public static EarthGeneratorSettings.DemSelection automaticSelection() {
         return MAPTERHORN_SELECTION;
      }

      public static EarthGeneratorSettings.DemSelection mapterhornSelection() {
         return MAPTERHORN_SELECTION;
      }

      public static EarthGeneratorSettings.DemSelection manual(int enabledProviderMask) {
         return MAPTERHORN_SELECTION;
      }

      public static EarthGeneratorSettings.DemSelection manual(List<EarthGeneratorSettings.DemProvider> providers) {
         return MAPTERHORN_SELECTION;
      }

      private static EarthGeneratorSettings.DemSelection fromSerializedIds(boolean automatic, List<String> providerIds) {
         return MAPTERHORN_SELECTION;
      }

      public static EarthGeneratorSettings.DemSelection fromLegacyProvider(EarthGeneratorSettings.DemProvider legacyProvider) {
         return MAPTERHORN_SELECTION;
      }

      public boolean isEnabled(EarthGeneratorSettings.DemProvider provider) {
         return provider == EarthGeneratorSettings.DemProvider.TERRARIUM;
      }

      public boolean terrainTilesEnabled() {
         return true;
      }

      public boolean isAllEnabled() {
         return true;
      }

      public String fingerprint() {
         return "mapterhorn";
      }

      public List<EarthGeneratorSettings.DemProvider> enabledProvidersInUiOrder() {
         return USER_SELECTABLE_PROVIDERS;
      }

      public List<String> enabledProviderIds() {
         return PROVIDER_IDS;
      }

      public static List<EarthGeneratorSettings.DemProvider> userSelectableProviders() {
         return USER_SELECTABLE_PROVIDERS;
      }

      public static int fullUserSelectableMask() {
         return MAPTERHORN_PROVIDER_MASK;
      }

      public static int maskFromProviders(Iterable<EarthGeneratorSettings.DemProvider> providers) {
         return MAPTERHORN_PROVIDER_MASK;
      }

      public static int maskFromProviderIds(List<String> providerIds) {
         return MAPTERHORN_PROVIDER_MASK;
      }
   }

   public static enum DistantHorizonsRenderMode {
      FAST("fast"),
      ULTRA_FAST("ultra_fast"),
      DETAILED("detailed");

      public static final Codec<EarthGeneratorSettings.DistantHorizonsRenderMode> CODEC = Codec.STRING
         .xmap(EarthGeneratorSettings.DistantHorizonsRenderMode::fromId, EarthGeneratorSettings.DistantHorizonsRenderMode::id);
      private final String id;

      private DistantHorizonsRenderMode(String id) {
         this.id = Objects.requireNonNull(id, "id");
      }

      public String id() {
         return this.id;
      }

      public static EarthGeneratorSettings.DistantHorizonsRenderMode fromId(String id) {
         if (id == null) {
            return FAST;
         } else {
            for (EarthGeneratorSettings.DistantHorizonsRenderMode mode : values()) {
               if (mode.id.equalsIgnoreCase(id)) {
                  return mode;
               }
            }

            return FAST;
         }
      }
   }

   public record HeightLimits(int minY, int height, int logicalHeight) {
      public static EarthGeneratorSettings.HeightLimits maxRange() {
         return new EarthGeneratorSettings.HeightLimits(MIN_WORLD_Y, MAX_WORLD_HEIGHT, MAX_WORLD_HEIGHT);
      }
   }

   private record SettingsBase(
      double worldScale,
      double terrestrialHeightScale,
      double oceanicHeightScale,
      int heightOffset,
      double spawnLatitude,
      double spawnLongitude,
      int minAltitude,
      int maxAltitude,
      int riverLakeShorelineBlend,
      int oceanShorelineBlend,
      boolean shorelineBlendCliffLimit,
      boolean caveGeneration,
      boolean oreDistribution,
      boolean geologicalStonePatches,
      boolean lavaPools,
      boolean distantHorizonsWaterResolver,
      boolean distantHorizonsOsmFeatures,
      boolean realtimeTime,
      boolean realtimeWeather,
      boolean historicalSnow,
      boolean voxyChunkPregenEnabled,
      int voxyChunkPregenMaxRadius,
      int voxyChunkPregenChunksPerTick,
      EarthGeneratorSettings.DistantHorizonsRenderMode distantHorizonsRenderMode,
      EarthGeneratorSettings.DemSelection demSelection
   ) {
      private static EarthGeneratorSettings.SettingsBase fromSettings(EarthGeneratorSettings settings) {
         return new EarthGeneratorSettings.SettingsBase(
            settings.worldScale(),
            settings.terrestrialHeightScale(),
            settings.oceanicHeightScale(),
            settings.heightOffset(),
            settings.spawnLatitude(),
            settings.spawnLongitude(),
            settings.minAltitude(),
            settings.maxAltitude(),
            settings.riverLakeShorelineBlend(),
            settings.oceanShorelineBlend(),
            settings.shorelineBlendCliffLimit(),
            settings.caveGeneration(),
            settings.oreDistribution(),
            settings.geologicalStonePatches(),
            settings.lavaPools(),
            settings.distantHorizonsWaterResolver(),
            settings.distantHorizonsOsmFeatures(),
            settings.realtimeTime(),
            settings.realtimeWeather(),
            settings.historicalSnow(),
            settings.voxyChunkPregenEnabled(),
            settings.voxyChunkPregenMaxRadius(),
            settings.voxyChunkPregenChunksPerTick(),
            settings.distantHorizonsRenderMode(),
            settings.demSelection()
         );
      }

      private EarthGeneratorSettings.SettingsBase withDistantHorizonsWaterResolver(boolean enabled) {
         return new EarthGeneratorSettings.SettingsBase(
            this.worldScale,
            this.terrestrialHeightScale,
            this.oceanicHeightScale,
            this.heightOffset,
            this.spawnLatitude,
            this.spawnLongitude,
            this.minAltitude,
            this.maxAltitude,
            this.riverLakeShorelineBlend,
            this.oceanShorelineBlend,
            this.shorelineBlendCliffLimit,
            this.caveGeneration,
            this.oreDistribution,
            this.geologicalStonePatches,
            this.lavaPools,
            enabled,
            this.distantHorizonsOsmFeatures,
            this.realtimeTime,
            this.realtimeWeather,
            this.historicalSnow,
            this.voxyChunkPregenEnabled,
            this.voxyChunkPregenMaxRadius,
            this.voxyChunkPregenChunksPerTick,
            this.distantHorizonsRenderMode,
            this.demSelection
         );
      }

      private EarthGeneratorSettings.SettingsBase withDistantHorizonsOsmFeatures(boolean enabled) {
         return new EarthGeneratorSettings.SettingsBase(
            this.worldScale,
            this.terrestrialHeightScale,
            this.oceanicHeightScale,
            this.heightOffset,
            this.spawnLatitude,
            this.spawnLongitude,
            this.minAltitude,
            this.maxAltitude,
            this.riverLakeShorelineBlend,
            this.oceanShorelineBlend,
            this.shorelineBlendCliffLimit,
            this.caveGeneration,
            this.oreDistribution,
            this.geologicalStonePatches,
            this.lavaPools,
            this.distantHorizonsWaterResolver,
            enabled,
            this.realtimeTime,
            this.realtimeWeather,
            this.historicalSnow,
            this.voxyChunkPregenEnabled,
            this.voxyChunkPregenMaxRadius,
            this.voxyChunkPregenChunksPerTick,
            this.distantHorizonsRenderMode,
            this.demSelection
         );
      }

      private EarthGeneratorSettings.SettingsBase withRealtimeTime(boolean enabled) {
         return new EarthGeneratorSettings.SettingsBase(
            this.worldScale,
            this.terrestrialHeightScale,
            this.oceanicHeightScale,
            this.heightOffset,
            this.spawnLatitude,
            this.spawnLongitude,
            this.minAltitude,
            this.maxAltitude,
            this.riverLakeShorelineBlend,
            this.oceanShorelineBlend,
            this.shorelineBlendCliffLimit,
            this.caveGeneration,
            this.oreDistribution,
            this.geologicalStonePatches,
            this.lavaPools,
            this.distantHorizonsWaterResolver,
            this.distantHorizonsOsmFeatures,
            enabled,
            this.realtimeWeather,
            this.historicalSnow,
            this.voxyChunkPregenEnabled,
            this.voxyChunkPregenMaxRadius,
            this.voxyChunkPregenChunksPerTick,
            this.distantHorizonsRenderMode,
            this.demSelection
         );
      }

      private EarthGeneratorSettings.SettingsBase withRealtimeWeather(boolean enabled) {
         return new EarthGeneratorSettings.SettingsBase(
            this.worldScale,
            this.terrestrialHeightScale,
            this.oceanicHeightScale,
            this.heightOffset,
            this.spawnLatitude,
            this.spawnLongitude,
            this.minAltitude,
            this.maxAltitude,
            this.riverLakeShorelineBlend,
            this.oceanShorelineBlend,
            this.shorelineBlendCliffLimit,
            this.caveGeneration,
            this.oreDistribution,
            this.geologicalStonePatches,
            this.lavaPools,
            this.distantHorizonsWaterResolver,
            this.distantHorizonsOsmFeatures,
            this.realtimeTime,
            enabled,
            this.historicalSnow,
            this.voxyChunkPregenEnabled,
            this.voxyChunkPregenMaxRadius,
            this.voxyChunkPregenChunksPerTick,
            this.distantHorizonsRenderMode,
            this.demSelection
         );
      }

      private EarthGeneratorSettings.SettingsBase withHistoricalSnow(boolean enabled) {
         return new EarthGeneratorSettings.SettingsBase(
            this.worldScale,
            this.terrestrialHeightScale,
            this.oceanicHeightScale,
            this.heightOffset,
            this.spawnLatitude,
            this.spawnLongitude,
            this.minAltitude,
            this.maxAltitude,
            this.riverLakeShorelineBlend,
            this.oceanShorelineBlend,
            this.shorelineBlendCliffLimit,
            this.caveGeneration,
            this.oreDistribution,
            this.geologicalStonePatches,
            this.lavaPools,
            this.distantHorizonsWaterResolver,
            this.distantHorizonsOsmFeatures,
            this.realtimeTime,
            this.realtimeWeather,
            enabled,
            this.voxyChunkPregenEnabled,
            this.voxyChunkPregenMaxRadius,
            this.voxyChunkPregenChunksPerTick,
            this.distantHorizonsRenderMode,
            this.demSelection
         );
      }

      private EarthGeneratorSettings.SettingsBase withVoxyChunkPregenEnabled(boolean enabled) {
         return new EarthGeneratorSettings.SettingsBase(
            this.worldScale,
            this.terrestrialHeightScale,
            this.oceanicHeightScale,
            this.heightOffset,
            this.spawnLatitude,
            this.spawnLongitude,
            this.minAltitude,
            this.maxAltitude,
            this.riverLakeShorelineBlend,
            this.oceanShorelineBlend,
            this.shorelineBlendCliffLimit,
            this.caveGeneration,
            this.oreDistribution,
            this.geologicalStonePatches,
            this.lavaPools,
            this.distantHorizonsWaterResolver,
            this.distantHorizonsOsmFeatures,
            this.realtimeTime,
            this.realtimeWeather,
            this.historicalSnow,
            enabled,
            this.voxyChunkPregenMaxRadius,
            this.voxyChunkPregenChunksPerTick,
            this.distantHorizonsRenderMode,
            this.demSelection
         );
      }

      private EarthGeneratorSettings.SettingsBase withVoxyChunkPregenMaxRadius(int maxRadius) {
         return new EarthGeneratorSettings.SettingsBase(
            this.worldScale,
            this.terrestrialHeightScale,
            this.oceanicHeightScale,
            this.heightOffset,
            this.spawnLatitude,
            this.spawnLongitude,
            this.minAltitude,
            this.maxAltitude,
            this.riverLakeShorelineBlend,
            this.oceanShorelineBlend,
            this.shorelineBlendCliffLimit,
            this.caveGeneration,
            this.oreDistribution,
            this.geologicalStonePatches,
            this.lavaPools,
            this.distantHorizonsWaterResolver,
            this.distantHorizonsOsmFeatures,
            this.realtimeTime,
            this.realtimeWeather,
            this.historicalSnow,
            this.voxyChunkPregenEnabled,
            maxRadius,
            this.voxyChunkPregenChunksPerTick,
            this.distantHorizonsRenderMode,
            this.demSelection
         );
      }

      private EarthGeneratorSettings.SettingsBase withVoxyChunkPregenChunksPerTick(int chunksPerTick) {
         return new EarthGeneratorSettings.SettingsBase(
            this.worldScale,
            this.terrestrialHeightScale,
            this.oceanicHeightScale,
            this.heightOffset,
            this.spawnLatitude,
            this.spawnLongitude,
            this.minAltitude,
            this.maxAltitude,
            this.riverLakeShorelineBlend,
            this.oceanShorelineBlend,
            this.shorelineBlendCliffLimit,
            this.caveGeneration,
            this.oreDistribution,
            this.geologicalStonePatches,
            this.lavaPools,
            this.distantHorizonsWaterResolver,
            this.distantHorizonsOsmFeatures,
            this.realtimeTime,
            this.realtimeWeather,
            this.historicalSnow,
            this.voxyChunkPregenEnabled,
            this.voxyChunkPregenMaxRadius,
            chunksPerTick,
            this.distantHorizonsRenderMode,
            this.demSelection
         );
      }

      private EarthGeneratorSettings.SettingsBase withDistantHorizonsRenderMode(EarthGeneratorSettings.DistantHorizonsRenderMode renderMode) {
         return new EarthGeneratorSettings.SettingsBase(
            this.worldScale,
            this.terrestrialHeightScale,
            this.oceanicHeightScale,
            this.heightOffset,
            this.spawnLatitude,
            this.spawnLongitude,
            this.minAltitude,
            this.maxAltitude,
            this.riverLakeShorelineBlend,
            this.oceanShorelineBlend,
            this.shorelineBlendCliffLimit,
            this.caveGeneration,
            this.oreDistribution,
            this.geologicalStonePatches,
            this.lavaPools,
            this.distantHorizonsWaterResolver,
            this.distantHorizonsOsmFeatures,
            this.realtimeTime,
            this.realtimeWeather,
            this.historicalSnow,
            this.voxyChunkPregenEnabled,
            this.voxyChunkPregenMaxRadius,
            this.voxyChunkPregenChunksPerTick,
            renderMode,
            this.demSelection
         );
      }

      private EarthGeneratorSettings.SettingsBase withDemSelection(EarthGeneratorSettings.DemSelection demSelection) {
         return new EarthGeneratorSettings.SettingsBase(
            this.worldScale,
            this.terrestrialHeightScale,
            this.oceanicHeightScale,
            this.heightOffset,
            this.spawnLatitude,
            this.spawnLongitude,
            this.minAltitude,
            this.maxAltitude,
            this.riverLakeShorelineBlend,
            this.oceanShorelineBlend,
            this.shorelineBlendCliffLimit,
            this.caveGeneration,
            this.oreDistribution,
            this.geologicalStonePatches,
            this.lavaPools,
            this.distantHorizonsWaterResolver,
            this.distantHorizonsOsmFeatures,
            this.realtimeTime,
            this.realtimeWeather,
            this.historicalSnow,
            this.voxyChunkPregenEnabled,
            this.voxyChunkPregenMaxRadius,
            this.voxyChunkPregenChunksPerTick,
            this.distantHorizonsRenderMode,
            Objects.requireNonNull(demSelection, "demSelection")
         );
      }

      private EarthGeneratorSettings toSettings() {
      return new EarthGeneratorSettings(
         this.worldScale,
         this.terrestrialHeightScale,
         this.oceanicHeightScale,
         this.heightOffset,
         this.spawnLatitude,
            this.spawnLongitude,
            this.minAltitude,
            this.maxAltitude,
            this.riverLakeShorelineBlend,
            this.oceanShorelineBlend,
            this.shorelineBlendCliffLimit,
            this.caveGeneration,
            this.oreDistribution,
            this.geologicalStonePatches,
            this.lavaPools,
            EarthGeneratorSettings.DEFAULT.addStrongholds(),
            EarthGeneratorSettings.DEFAULT.addVillages(),
            EarthGeneratorSettings.DEFAULT.addMineshafts(),
            EarthGeneratorSettings.DEFAULT.addOceanMonuments(),
            EarthGeneratorSettings.DEFAULT.addWoodlandMansions(),
            EarthGeneratorSettings.DEFAULT.addDesertTemples(),
            EarthGeneratorSettings.DEFAULT.addJungleTemples(),
            EarthGeneratorSettings.DEFAULT.addPillagerOutposts(),
            EarthGeneratorSettings.DEFAULT.addRuinedPortals(),
            EarthGeneratorSettings.DEFAULT.addShipwrecks(),
            EarthGeneratorSettings.DEFAULT.addOceanRuins(),
            EarthGeneratorSettings.DEFAULT.addBuriedTreasure(),
            EarthGeneratorSettings.DEFAULT.addIgloos(),
            EarthGeneratorSettings.DEFAULT.addWitchHuts(),
            EarthGeneratorSettings.DEFAULT.addAncientCities(),
            EarthGeneratorSettings.DEFAULT.addTrialChambers(),
            EarthGeneratorSettings.DEFAULT.addTrailRuins(),
            EarthGeneratorSettings.DEFAULT.deepDark(),
            EarthGeneratorSettings.DEFAULT.geodes(),
            this.distantHorizonsWaterResolver,
            this.distantHorizonsOsmFeatures,
            EarthGeneratorSettings.DEFAULT.distantHorizonsOsmRoadMaxDetail(),
            EarthGeneratorSettings.DEFAULT.distantHorizonsOsmBuildingMaxDetail(),
            EarthGeneratorSettings.DEFAULT.distantHorizonsOsmNonBlockingFetch(),
            this.realtimeTime,
            this.realtimeWeather,
            this.historicalSnow,
            this.voxyChunkPregenEnabled,
            this.voxyChunkPregenMaxRadius,
            this.voxyChunkPregenChunksPerTick,
            this.distantHorizonsRenderMode,
            this.demSelection,
            EarthGeneratorSettings.DEFAULT.enableRoads(),
            EarthGeneratorSettings.DEFAULT.enableBuildings(),
            EarthGeneratorSettings.DEFAULT.enableWater(),
            EarthGeneratorSettings.DEFAULT.thinShellTerrain(),
            EarthGeneratorSettings.DEFAULT.climateBasedBuiltUpTerrain(),
            EarthGeneratorSettings.DEFAULT.randomBiomes(),
            EarthGeneratorSettings.DEFAULT.randomBiomeDensity(),
            EarthGeneratorSettings.DEFAULT.randomBiomeSeed(),
            EarthGeneratorSettings.DEFAULT.randomBiomeIds(),
            EarthGeneratorSettings.DEFAULT.experimentalIncreaseHeight(),
            true,
            true,
            EarthGeneratorSettings.DEFAULT.cavesReachSurface(),
            EarthGeneratorSettings.DEFAULT.undergroundDepth(),
            EarthGeneratorSettings.DEFAULT.customTrees(),
            EarthGeneratorSettings.DEFAULT.automaticHeightScaling(),
            EarthGeneratorSettings.DEFAULT.hugeRedMushrooms(),
            EarthGeneratorSettings.DEFAULT.worldScaleAtSpawn(),
            EarthGeneratorSettings.DEFAULT.centerWorldOnSpawn()
         );
      }
   }

   private record NetworkSettings(boolean managedTerrainDownloads, boolean showOverlay, boolean cavesReachSurface) {
   }

   private record StructureSettings(
      boolean addStrongholds,
      boolean addVillages,
      boolean addMineshafts,
      boolean addOceanMonuments,
      boolean addWoodlandMansions,
      boolean addDesertTemples,
      boolean addJungleTemples,
      boolean addPillagerOutposts,
      boolean addRuinedPortals,
      boolean addShipwrecks,
      boolean addOceanRuins,
      boolean addBuriedTreasure,
      boolean addIgloos,
      boolean addWitchHuts,
      boolean addAncientCities,
      boolean addTrialChambers
   ) {
      private static EarthGeneratorSettings.StructureSettings fromSettings(EarthGeneratorSettings settings) {
         return new EarthGeneratorSettings.StructureSettings(
            settings.addStrongholds(),
            settings.addVillages(),
            settings.addMineshafts(),
            settings.addOceanMonuments(),
            settings.addWoodlandMansions(),
            settings.addDesertTemples(),
            settings.addJungleTemples(),
            settings.addPillagerOutposts(),
            settings.addRuinedPortals(),
            settings.addShipwrecks(),
            settings.addOceanRuins(),
            settings.addBuriedTreasure(),
            settings.addIgloos(),
            settings.addWitchHuts(),
            settings.addAncientCities(),
            settings.addTrialChambers()
         );
      }
   }
}
